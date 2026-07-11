(ns re-frame.story-mcp.tools.lifecycle
  "Shared variant-lifecycle execution for `run-variant` and
  `preview-variant`.

  This namespace owns blocking invocation, the common timeout, and
  canonical exception normalization through `story/run-result`. That
  boundary guarantees settled and error outcomes carry the same evidence
  slots. Each tool still owns its projection, egress scrubbing,
  annotations, indicators, and wire envelope."
  (:require [re-frame.story :as story]
            ;; JVM-only: `async/deref-blocking` is `#?(:clj …)` and is used
            ;; solely by the JVM bounded-lifecycle blocker below. story-mcp
            ;; is a JVM stdio server (deps.edn), so the require is clj-only —
            ;; the CLJS branch of this ns never blocks.
            #?(:clj [re-frame.story.async :as async])))

(defn error-outcome
  "Assemble the ONE canonical error run-result for a synchronous throw /
  timeout out of `story/run-variant`, through the SAME `story/run-result`
  boundary a settled run uses (spec/017 §Run result). Pure given the
  variant key `vk` + the thrown `e`.

  Routing the failure through `story/run-result` fills every evidence
  slot with `[]`, so the wire projection never emits a bare `nil` where
  the run-result schema requires a sequence. Two extra slots are stamped
  so both consumers keep the fields they read off the outcome:

  - `:lifecycle :error` — the loader-lifecycle STATE `preview-variant`
    surfaces (distinct from the `:status` verdict);
  - `:frame vk` — the variant frame id `run-variant` surfaces (so the
    canonical outcome is self-describing rather than relying on each
    caller's `(:frame outcome vk)` fallback)."
  [vk e]
  (assoc (story/run-result
           {:variant/id vk
            :assertions [(story/assertion-record
                           {:assertion :rf.error/run-failed
                            :passed?   false
                            :error     true
                            :reason    (ex-message e)})]})
         :lifecycle :error
         :frame     vk))

(defn deadline-outcome
  "The ONE canonical outcome for a lifecycle `:timeout-ms` deadline that
  elapsed before the Story run settled. Routes a self-describing timeout
  through the SAME `error-outcome` boundary a synchronous throw uses, so
  an over-budget run reports the canonical `:status :error` verdict —
  NEVER a false `:pass` (spec/017 §Run result). Pure given `vk` +
  `timeout-ms`."
  [vk timeout-ms]
  (error-outcome
    vk
    (ex-info (str "Story lifecycle deadline exceeded: variant " (pr-str vk)
                  " did not settle within the " timeout-ms " ms ceiling. "
                  "The over-budget run was abandoned so it cannot monopolise "
                  "the single-threaded stdio server loop.")
             {:variant vk :timeout-ms timeout-ms})))

#?(:clj
   (defn- unwrap-async-cause
     "Peel the JVM async-plumbing wrappers (`ExecutionException` from the
     worker future, `CompletionException` from a rejected Story future) off
     `e` so the canonical error reason is the underlying Story throwable,
     not the executor's wrapper. Stops at the first non-wrapper cause.

     JVM-only, like the rest of this blocking-lifecycle owner: `story-mcp`
     is a JVM-side stdio server (see `deps.edn`)."
     [^Throwable e]
     (loop [t e]
       (let [c (.getCause t)]
         (if (and (or (instance? java.util.concurrent.ExecutionException t)
                      (instance? java.util.concurrent.CompletionException t))
                  (some? c)
                  (not (identical? c t)))
           (recur c)
           t)))))

(defn run-variant-blocking
  "Invoke `story/run-variant` for `vk` with `opts` and BLOCK for its
  unified run-result, capped at `timeout-ms` (the single-threaded-stdio
  ceiling both lifecycle tools share via `targs/resolve-timeout-ms`). A
  synchronous throw, or a deadline that elapses before the run settles, is
  normalized into the ONE canonical `error-outcome` — so `run-variant`
  and `preview-variant` return the SAME settled-or-error vocabulary and
  can never drift in their blocking / failure policy.

  ## The deadline covers the SYNCHRONOUS work (rf2-j538f7.31)

  On the JVM `story/run-variant` runs SYNCHRONOUSLY (a `[:wait ms]` step
  is an inline `Thread/sleep`), so it returns an ALREADY-settled future.
  Deref'ing that settled future under `timeout-ms` is a no-op — the
  advertised ceiling never bounds the real work, an over-budget run
  reports a false `:pass`, and it monopolises the single-threaded stdio
  loop for the full duration. To make the advertised bound REAL, the whole
  invoke-and-settle runs on a bounded worker `future` conveyed with this
  thread's dynamic bindings; the caller waits at most `timeout-ms`. When
  the deadline elapses the worker is CANCELLED (interrupting its
  `Thread/sleep`, so the scheduled post-wait continuation never runs and
  the stdio loop is freed) and the canonical `deadline-outcome` is
  returned — an honest bounded-deadline-exceeded `:error`, not a false
  `:pass`.

  Returns the outcome map (a settled run-result, or the canonical error
  outcome). The caller projects / scrubs / wire-shapes it.

  JVM-only body: `future` + the bounded timed `deref` (`clojure.core/
  deref`'s 3-arg arity) have no CLJS counterpart — `story-mcp` is a
  JVM-side stdio server (see `deps.edn`), so the reader conditional keeps
  the timed-deref off the CLJS surface (`cljs.core/deref` is 1-arg only,
  which clj-kondo would otherwise flag on the `.cljc`'s cljs branch)."
  [vk opts timeout-ms]
  #?(:clj
     (let [worker (future (async/deref-blocking (story/run-variant vk opts)))]
       (try
         (let [settled (deref worker timeout-ms ::timeout)]
           (if (identical? settled ::timeout)
             (do
               ;; Abandon the over-budget run: `future-cancel` interrupts the
               ;; worker so a JVM `[:wait]` (`Thread/sleep`) unblocks and the
               ;; scheduled post-wait continuation never fires, freeing the
               ;; single-threaded stdio loop at the ceiling.
               (future-cancel worker)
               (deadline-outcome vk timeout-ms))
             settled))
         (catch Throwable e
           ;; A synchronous throw from `story/run-variant`, or a rejected
           ;; Story future, surfaces here wrapped in the worker future's
           ;; `ExecutionException`. Unwrap to the underlying Story throwable
           ;; so the canonical error reason is honest.
           (future-cancel worker)
           (error-outcome vk (unwrap-async-cause e)))))
     :cljs
     (throw (ex-info "run-variant-blocking is JVM-only (story-mcp is a JVM stdio server)"
                     {:vk vk :opts opts :timeout-ms timeout-ms}))))
