(ns re-frame2-pair-mcp.tools.eval-cljs
  "Tool: eval-cljs — evaluate one CLJS form.

  ## Launch-flag gate (rf2-a0z0h; inverts the prior rf2-cxx5s default)

  The eval-cljs tool is the REPL primitive of a pair-debug session —
  arbitrary form evaluation against the live re-frame2 runtime is the
  whole reason an operator installs re-frame2-pair-mcp. Published
  builds default this surface **ON**; the operator opts OUT via
  `--no-eval` at server launch for the rare paranoid case (CI runs,
  shared dev environments where multiple humans share a single MCP
  process).

  ### Threat-model rationale

  The prior rf2-cxx5s gate (default OFF) parallelled `--allow-writes`
  in shape but not in effect. `--allow-writes` is load-bearing because
  pair-tool writes can confuse the debug audit trail (\"did my app
  produce this state change, or did the pair tool?\"). `--allow-eval`
  did NOT parallel that protection: eval-cljs can express any write
  the writes-gate would block. The two gates are not independent —
  once eval is on, writes are de-facto on. So a default-OFF eval
  surface added friction without adding a separable protection.

  The real defence is **don't expose this MCP to untrusted callers**.
  Once an operator has installed re-frame2-pair-mcp and wired it into
  `~/.claude.json`, they've already declared trust in the surface.

  ### Implementation

  The gate is a single atom (`eval-allowed?`) set by
  `server.cljs/main` from `process.argv` before the dispatcher starts
  handling tools/call requests. The atom defaults to `true`; passing
  `--no-eval` flips it to `false`. Tests flip the atom directly via
  `set-eval-allowed!`.

  ## Opt-in Promise awaiting (rf2-xn4f9)

  `cljs-eval` captures the synchronous return value of the form and
  `pr-str`'s it. When the form returns a JS Promise — any async work
  (`fetch`, `.layout()`, `async` fns, anything chained with `.then`) —
  the synchronous return IS the Promise object; `pr-str` produces a
  `\"#object[Promise ...]\"` string that says \"I'm a Promise\" with no
  access to the eventually-resolved value. The historical workaround
  was a two-call mailbox dance (stash on `js/window`, return a sentinel,
  read the global on a second call, repeat-poll until resolved).

  When the caller passes `:await true`, the server automates that
  dance:

    1. The user's form is wrapped browser-side. The wrapper evaluates
       the form; if the result is a thenable, it stashes a mailbox
       entry on `js/globalThis.__rf2pair_await__` and chains
       `.then` / `.catch` to record the resolved value or rejection
       reason as `pr-str` text into that mailbox.
    2. The wrapper returns immediately. Its synchronous value is a
       sentinel — either `{:rf.mcp/await-direct <v>}` for a
       non-thenable form (passthrough) or
       `{:rf.mcp/await-mailbox <id>}` for a thenable.
    3. On a mailbox sentinel, the server polls the mailbox via a tiny
       second eval (`read-and-clear-mailbox`) at a fast cadence until
       `:status` flips off `:pending`, or `:timeout-ms` elapses.
    4. `:resolved` → `{:ok? true :value <edn-value> :build ...}`.
    5. `:rejected` →
       `{:ok? false :reason :rf.error/eval-cljs-rejected
         :rejection \"<pr-str of rejection>\" ...}`.
    6. Timeout →
       `{:ok? false :reason :rf.error/eval-cljs-timeout
         :timeout-ms n ...}`.

  ### Why opt-in (not always-on)

  Two principled reasons (the back-compat argument doesn't bind in
  pre-alpha):

    - **Promise pass-through is sometimes intentional**. Some forms
      deliberately return a Promise object to hand off to other code;
      auto-awaiting would change the contract for those callers.
    - **Timeout policy should be caller-controlled** rather than
      implicit. The caller knows whether their async work is a 5ms
      `Promise.resolve` or a multi-second layout computation; the
      server shouldn't pick.

  The default `:await false` preserves today's semantics — the form's
  synchronous return is `pr-str`'d and returned verbatim. Callers
  who DO want to wait on a Promise opt in explicitly and pick the
  deadline.

  ## Frame targeting (rf2-ntuzf)

  Every other structured op (`dispatch`, `snapshot`, `get-path`,
  `trace-window`, `watch-epochs`, `subscribe`, `reset-frame-db`)
  accepts an optional `:frame` arg targeting a named frame. Pre-
  rf2-ntuzf `eval-cljs` did NOT — the form ran against whatever
  ambient frame context existed at the call site (the MCP server's
  context is `:rf/default`), so `(rf/subscribe ...)` /
  `(rf/dispatch ...)` inside an `eval-cljs` form silently targeted
  `:rf/default` even in a multi-frame app.

  When `:frame :rf/xray` is supplied, the form is wrapped server-side
  in `(re-frame.core/with-frame :rf/xray <user-form>)` before being
  sent over nREPL. `with-frame` is the framework's lexical frame-
  binding macro (per Spec 002 §with-frame) — `*current-frame*` is
  bound to the named frame for the form's dynamic extent, so any
  `(rf/subscribe ...)` / `(rf/dispatch ...)` / `(rf/current-frame)`
  inside the form resolves against the requested frame.

  The wrap composes orthogonally with `:await true`: a Promise-
  returning form wrapped in `with-frame` is still a Promise; the
  await mailbox dance proceeds normally. Frame binding only lasts
  for the form's synchronous evaluation — once the Promise resolves
  on a later tick, the original lexical frame is gone (this matches
  the macro's contract per Spec 002, where async closures must
  capture via `bound-fn` / `dispatcher` / `subscriber`)."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defonce ^:private eval-allowed?
  ;; Default ON in published builds. `server.cljs/main` flips this to
  ;; `false` when `--no-eval` is present in `process.argv`.
  (atom true))

(defn set-eval-allowed!
  "Set the eval-cljs launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! eval-allowed? (boolean enabled?)))

(defn eval-allowed-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @eval-allowed?)

;; ---------------------------------------------------------------------------
;; Await mode — wrap the user's form to detect thenables + automate the
;; mailbox dance (rf2-xn4f9).
;; ---------------------------------------------------------------------------

(def ^:private default-await-timeout-ms
  "Default deadline for awaiting a Promise return when the caller doesn't
  specify `:timeout-ms`. 5 seconds is generous for typical async work
  (layout, fetch, async fns); pathologically long async forms (heavy
  IndexedDB scans, multi-second elkjs layouts) can raise it. Mirrors
  shape with `tail-build`'s `:wait-ms`."
  5000)

(def ^:private mailbox-poll-ms
  "Cadence at which the server re-reads the mailbox after a thenable
  sentinel. 25ms = ~40 polls/sec — small enough to land within ~25ms
  of resolution, cheap enough on the nREPL socket to not flood it.
  The polling stops as soon as the mailbox status flips off
  `:pending`, so the cadence matters only for short-lived awaits.

  Aligned with the cost profile of `cljs-eval` round-trips (~5-15ms
  warm) — finer than `tail-build`'s 100ms because await is in the
  inner debug loop and the polled form is cheap (a single property
  read + clear)."
  25)

(defn- await-mailbox-key
  "The unique key written to `js/globalThis.__rf2pair_await__` for this
  call. Random-uuid keeps concurrent awaits (a future N-agent
  workflow, or interleaved tool calls in a single host) from clobbering
  each other's mailboxes."
  []
  (str "await-" (random-uuid)))

(defn- await-wrap-form
  "Wrap the user's `form-str` so the browser-side eval:

    - evaluates the user form (may return any value, thenable or not),
    - synchronously returns `{:rf.mcp/await-direct <v>}` for non-thenable
      results (server fast-paths back to the today-shape value),
    - for thenable results, installs a mailbox entry, chains
      `.then` / `.catch` to record the outcome, and synchronously
      returns `{:rf.mcp/await-mailbox <id>}`.

  All resolved/rejected values are `pr-str`'d into the mailbox so they
  survive the next `cljs-eval` round-trip as EDN text — the server
  reads them back unchanged.

  The wrapper deliberately uses `(some? (.-then v))` rather than
  `(instance? js/Promise v)` so any thenable (jQuery deferreds, axios
  responses, library-specific promise shims) is recognised — matching
  the JS-ecosystem `await` semantic."
  [form-str mailbox-id]
  (str
    "(let [user-fn# (fn [] " form-str ")"
    "      v# (user-fn#)"
    "      mailbox# (or (.-__rf2pair_await__ js/globalThis)"
    "                   (let [m# (cljs.core/js-obj)]"
    "                     (set! (.-__rf2pair_await__ js/globalThis) m#)"
    "                     m#))]"
    "  (if (and (some? v#)"
    "           (or (object? v#) (instance? js/Object v#))"
    "           (fn? (some-> v# .-then)))"
    "    (do"
    "      (aset mailbox# " (pr-str mailbox-id)
    "            (cljs.core/js-obj \"status\" \"pending\"))"
    "      (-> v#"
    "          (.then (fn [r#]"
    "                   (aset mailbox# " (pr-str mailbox-id)
    "                         (cljs.core/js-obj"
    "                           \"status\" \"resolved\""
    "                           \"value\" (cljs.core/pr-str r#)))))"
    "          (.catch (fn [e#]"
    "                    (aset mailbox# " (pr-str mailbox-id)
    "                          (cljs.core/js-obj"
    "                            \"status\" \"rejected\""
    "                            \"rejection\" (cljs.core/pr-str e#))))))"
    "      {:rf.mcp/await-mailbox " (pr-str mailbox-id) "})"
    "    {:rf.mcp/await-direct v#}))"))

(defn- read-mailbox-form
  "Read + clear the mailbox entry for `mailbox-id`. Resolves to one of:

    - `{:status :pending}`               — promise hasn't settled yet
    - `{:status :resolved :value v}`     — value is the post-pr-str EDN
    - `{:status :rejected :rejection s}` — s is pr-str of the rejection
    - `{:status :missing}`               — mailbox slot is gone (would
      indicate a serious wire-shape regression; we surface it rather
      than silently retrying)

  Cleared on a non-pending read so a repeated poll after resolution
  doesn't re-read stale data."
  [mailbox-id]
  (str
    "(let [mailbox# (or (.-__rf2pair_await__ js/globalThis) (cljs.core/js-obj))"
    "      entry#   (aget mailbox# " (pr-str mailbox-id) ")]"
    "  (cond"
    "    (nil? entry#)"
    "    {:status :missing}"
    "    (= \"pending\" (aget entry# \"status\"))"
    "    {:status :pending}"
    "    (= \"resolved\" (aget entry# \"status\"))"
    "    (let [v# (cljs.reader/read-string (aget entry# \"value\"))]"
    "      (js-delete mailbox# " (pr-str mailbox-id) ")"
    "      {:status :resolved :value v#})"
    "    (= \"rejected\" (aget entry# \"status\"))"
    "    (let [s# (aget entry# \"rejection\")]"
    "      (js-delete mailbox# " (pr-str mailbox-id) ")"
    "      {:status :rejected :rejection s#})"
    "    :else"
    "    {:status :missing}))"))

(defn- discard-mailbox-form
  "Best-effort drop of the mailbox slot — called after a timeout so a
  late resolution doesn't pile up garbage on `js/globalThis`. Errors
  are swallowed; the timeout result is already on its way back."
  [mailbox-id]
  (str
    "(when-let [mailbox# (.-__rf2pair_await__ js/globalThis)]"
    "  (js-delete mailbox# " (pr-str mailbox-id) ")"
    "  nil)"))

(defn- poll-mailbox!
  "Poll the mailbox at `mailbox-poll-ms` cadence until status is non-
  `:pending` or `timeout-ms` elapses. Resolves to the final mailbox
  read result map (`:resolved` / `:rejected` / `:timeout` / `:missing`).
  On `:timeout`, fires-and-forgets a discard-form so the late resolution
  doesn't accumulate."
  [conn build-id mailbox-id timeout-ms]
  (let [start    (js/Date.now)
        read-form (read-mailbox-form mailbox-id)]
    (js/Promise.
      (fn [resolve _reject]
        (letfn [(poll []
                  (let [elapsed (- (js/Date.now) start)]
                    (if (>= elapsed timeout-ms)
                      (do
                        ;; Fire-and-forget discard; ignore any error.
                        (-> (nrepl/cljs-eval-value conn build-id
                                                   (discard-mailbox-form mailbox-id))
                            (.catch (fn [_] nil)))
                        (resolve {:status :timeout}))
                      (-> (nrepl/cljs-eval-value conn build-id read-form)
                          (.then (fn [r]
                                   (if (and (map? r) (= :pending (:status r)))
                                     (js/setTimeout poll mailbox-poll-ms)
                                     (resolve (if (map? r) r {:status :missing})))))
                          (.catch (fn [_]
                                    ;; Transient read failure — keep polling
                                    ;; until timeout. Reading the mailbox is
                                    ;; cheap; one hiccup shouldn't abort the
                                    ;; await.
                                    (js/setTimeout poll mailbox-poll-ms)))))))]
          (poll))))))

(defn- await-result->envelope
  "Translate a `poll-mailbox!` result into the eval-cljs envelope shape.
  `resolved-build` is the build the eval ran against; echoed back on
  every envelope so the caller's auto-detect path is visible."
  [resolved-build timeout-ms mailbox-result]
  (case (:status mailbox-result)
    :resolved
    (wire/ok-text {:ok?   true
                   :value (:value mailbox-result)
                   :build resolved-build})

    :rejected
    (wire/ok-text {:ok?       false
                   :reason    :rf.error/eval-cljs-rejected
                   :rejection (:rejection mailbox-result)
                   :build     resolved-build})

    :timeout
    (wire/ok-text {:ok?        false
                   :reason     :rf.error/eval-cljs-timeout
                   :timeout-ms timeout-ms
                   :build      resolved-build})

    ;; :missing — defensive; should not happen in practice.
    (wire/ok-text {:ok?    false
                   :reason :rf.error/eval-cljs-mailbox-missing
                   :hint   (str "eval-cljs await mailbox vanished before the result was read. "
                                "This indicates a wire-shape regression or a page-reload "
                                "destroying the mailbox between the wrap and the poll.")
                   :build  resolved-build})))

(defn- handle-sentinel
  "Inspect the wrapper's synchronous return — a `:rf.mcp/await-direct`
  map carries the form's value verbatim (non-thenable fast path);
  a `:rf.mcp/await-mailbox` map kicks off the polling loop. Anything
  else is a regression in `await-wrap-form` — surface a structured
  error rather than silently returning the wrong shape."
  [conn resolved-build timeout-ms sentinel]
  (cond
    (and (map? sentinel) (contains? sentinel :rf.mcp/await-direct))
    (js/Promise.resolve
      (wire/ok-text {:ok?   true
                     :value (:rf.mcp/await-direct sentinel)
                     :build resolved-build}))

    (and (map? sentinel) (contains? sentinel :rf.mcp/await-mailbox))
    (-> (poll-mailbox! conn resolved-build
                       (:rf.mcp/await-mailbox sentinel)
                       timeout-ms)
        (.then (partial await-result->envelope resolved-build timeout-ms)))

    :else
    (js/Promise.resolve
      (wire/ok-text {:ok?    false
                     :reason :rf.error/eval-cljs-await-wrap-failed
                     :hint   "the await wrapper returned an unrecognised sentinel"
                     :sentinel (pr-str sentinel)
                     :build  resolved-build}))))

;; ---------------------------------------------------------------------------
;; Tool entry point.
;; ---------------------------------------------------------------------------

(defn- wrap-in-frame
  "Wrap `form-str` in `(re-frame.core/with-frame <frame-kw> <form>)`
  per rf2-ntuzf. Returns the wrapped source verbatim — callers feed
  this into the await wrapper or send it straight over nREPL.

  `frame-kw` is emitted as an EDN literal via `pr-str` so a kebab-case
  / namespaced keyword survives the round-trip without quoting
  surprises. `with-frame` is the framework's lexical frame-binding
  macro (Spec 002 §with-frame); per its contract, async closures
  inside the form must capture via `bound-fn` / `dispatcher` /
  `subscriber` for the binding to survive later ticks. We document
  that asymmetry in the ns docstring rather than enforcing it here —
  the body is opaque user-supplied source."
  [form-str frame-kw]
  (str "(re-frame.core/with-frame " (pr-str frame-kw) " " form-str ")"))

(defn eval-cljs-tool [conn args]
  (let [form       (wire/arg args :form)
        build-id   (wire/arg-build conn args)
        explicit?  (wire/arg-build-explicit? conn args)
        await?     (boolean (wire/arg args :await))
        timeout-ms (or (wire/arg args :timeout-ms) default-await-timeout-ms)
        ;; rf2-ntuzf — optional `:frame` arg targets a named frame for
        ;; the form's lexical scope. Same coercion as dispatch/
        ;; snapshot/get-path: bare names (\"rf/default\") and EDN-
        ;; shaped strings (\":rf/default\") both accepted.
        frame      (some-> (wire/arg args :frame) args/->frame-keyword)]
    (cond
      (not @eval-allowed?)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :rf.error/eval-cljs-disabled
           :hint   (str "eval-cljs has been disabled for this server "
                        "instance via --no-eval; relaunch without that "
                        "flag to enable.")}))

      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-form
                        :hint "usage: eval-cljs {form '<cljs-form>' [build :app] [await true] [timeout-ms 5000] [frame :rf/xray]}"}))

      :else
      ;; rf2-ivlb3: resolve the build (auto-detect the single running one
      ;; when no explicit :build was passed) and confirm a live runtime
      ;; BEFORE eval'ing. Never emit `:ok? true :value nil` for a build
      ;; with no runtime — that's indistinguishable from a genuine nil.
      (let [;; rf2-ntuzf: apply the with-frame wrap before await
            ;; wrapping. The await wrap operates on whatever source
            ;; we send over the wire; with-frame is just additional
            ;; outer-CLJS so it composes orthogonally with await.
            user-form (if frame (wrap-in-frame form frame) form)]
        (-> (probe/resolve-and-preflight! conn build-id explicit?)
            (.then (fn [resolved-build]
                     (if-not await?
                       ;; Default path — today's semantics: pr-str the
                       ;; synchronous return verbatim, Promises included.
                       (-> (nrepl/cljs-eval-value conn resolved-build user-form)
                           (.then (fn [v]
                                    (wire/ok-text
                                      (cond-> {:ok?   true
                                               :value v
                                               :build resolved-build}
                                        frame (assoc :frame frame))))))
                       ;; Await path (rf2-xn4f9) — wrap the form, dispatch
                       ;; on the sentinel, poll the mailbox if needed.
                       (let [mailbox-id (await-mailbox-key)
                             wrapped    (await-wrap-form user-form mailbox-id)]
                         (-> (nrepl/cljs-eval-value conn resolved-build wrapped)
                             (.then (partial handle-sentinel conn resolved-build timeout-ms)))))))
            (.catch (fn [err] (probe/err->result :eval-error err))))))))
