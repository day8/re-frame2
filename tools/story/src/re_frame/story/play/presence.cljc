(ns re-frame.story.play.presence
  "The presence rung of Story playback — the seam a `[:flush-presence]`
  script step reaches for (rf2-qwzmt, S4-H; Spec 004 §Presence).

  ## Why a rung exists

  A variant whose view renders a `(v/presence {:timeout-ms n} …)` boundary
  RETAINS a removed keyed child in `:unmounting` until the `:timeout-ms`
  safety bound fires. Playback then has a settlement problem the
  `settled-boundary` ladder cannot solve: the retention is a CLOCK, not a
  queue. Draining the router to a fixed point (`:headless`), flushing
  reactions (`:cljs-reactive`) or awaiting a React commit (`:dom`) all leave
  the retained child exactly where it is — the next step races the timeout.
  The only wall-clock answer is `[:wait ms]`, which the determinism gate
  REFUSES (`re-frame.story.determinism` §The bare-`[:wait ms]` opt-out).

  The framework's own answer is its presence FAKE CLOCK
  (`re-frame.freehand.presence-runtime/advance-clock!`), which fires due exits
  with no wall-clock sleep. A bare advance goes to quiescence (every pending
  exit fires); an advance of `ms` moves the logical clock by that much, firing
  only the exits that come due — so a script can observe the retained
  `:unmounting` phase and THEN its terminal removal, deterministically.

  This namespace is the (thin) seam, nothing more. Story does NOT model
  presence, own a clock, or reimplement the three-phase machine — it CALLS
  the framework verb and lets the framework own the semantics.

  ## The host hook

  Story's shipped jar must not depend on the view substrate
  (`day8/re-frame2-freehand` is pre-publication — the same isolation
  `re-frame.story.view-tool` keeps), so the verb arrives through the
  `re-frame.story.late-bind` registry under `:flush-presence!`. The canonical
  installation is ONE `:require` of the optional bridge
  `re-frame.story.play.presence-host`, which holds the Freehand dependency on
  the app's side of the seam and installs the verb at its load time.
  `install-presence-flush!` below stays public for a host that wants to
  register a different advance.

  ## No host installed → `:cannot-run`, never a silent skip

  With NO host installed the advance DID NOT HAPPEN, and a requested
  `[:flush-presence]` therefore REFUSES (`:cannot-run`) at the executor
  (rf2-36biz). The earlier reading — that this should be a no-op mirroring
  the framework's JVM arm of `flush-presence!` — conflated two different
  facts. The JVM arm is a no-op because the structural host provably has no
  lifecycle to advance; an ABSENT HOOK proves nothing of the kind, because an
  app can load Freehand, render a real `(v/presence …)` boundary, and simply
  omit the bridge call. Its playback would then advance nothing while Story
  reported a clean verdict.

  Nor does the following assertion carry the refusal: the grammar requires no
  following assertion at all, and an `:assert-db` one needs only the headless
  floor, so it happily proves a state the un-advanced clock produced. Host
  parity is preserved where it is real — this namespace is `.cljc` and the
  bridge installs on both hosts, the JVM verb being the framework's own no-op.

  Pure data → data apart from the hook call itself, so both the JVM
  `clojure -M:test` gate and the node `npm run test:cljs` gate exercise it."
  (:require [re-frame.story.late-bind :as late-bind]))

(def presence-flush-hook-key
  "The `re-frame.story.late-bind` hook key a Freehand-hosted shell registers
  its presence-clock advance under. The fn signature is `(f ms-or-nil) → any`
  — `nil` means advance to quiescence, a number means advance the logical
  clock by that many milliseconds. Both map 1:1 onto the two arities of
  `re-frame.freehand.presence-runtime/advance-clock!`."
  :flush-presence!)

(defn install-presence-flush!
  "Register the host's presence-clock advance under the `:flush-presence!`
  late-bind hook. `flush-fn` takes ONE argument — the ms to advance by, or
  `nil` for 'to quiescence' — mirroring the clock advance's two arities.
  Idempotent (re-registration replaces, per Spec 001 hot-reload semantics)."
  [flush-fn]
  (late-bind/set-fn! presence-flush-hook-key flush-fn)
  nil)

(defn presence-flush-fn
  "The installed presence-advance fn, or nil when no host registered one
  (the bare JVM / a Story app with no presence boundaries)."
  []
  (late-bind/get-fn presence-flush-hook-key))

(defn- thenable
  "`x` as a thenable, or nil. CLJS-only, and CONDITIONAL: the shipped Freehand
  bridge is SYNCHRONOUS (its advance runs inside the substrate's `flushSync`
  commit, so the DOM is settled by the time it returns) and hands back nil,
  while a custom host — or one built on an awaited React `act`, as the donor's
  was — may hand back a Promise. Duck-typed on `.then` rather than
  `instance? js/Promise` so any thenable a host hands back is observed."
  [x]
  #?(:clj  (do x nil)
     :cljs (when (and (some? x) (fn? (unchecked-get x "then"))) x)))

(defn advance!
  "Advance the presence clock by `ms` (nil → to quiescence) through the
  installed host verb. Returns a small result map:

    {:status :advanced :ms <ms|nil>}   — the host verb ran
    {:status :no-host  :ms <ms|nil>}   — no host installed; NOTHING advanced
                                         (the executor projects this into a
                                         `:cannot-run` refusal — see the ns
                                         docstring)
    {:status :error    :ms … :error s} — the host verb threw

  An `:advanced` result additionally carries `:pending <thenable>` when the
  host verb returned one and has therefore NOT finished (rf2-iz0t8). A
  Promise-backed host does exactly that, and returning `:advanced` bare would
  report that the advance succeeded while its outcome was still unknown.
  `settle!` is how a caller waits for the answer; `:advanced` on its own means
  only 'the verb was reached'. The shipped Freehand bridge is synchronous and
  carries no `:pending` — `settle!` then calls back before it returns.

  Never throws, and never judges: this is pure data → data reporting of what
  happened. The executor (`runner-events/exec-flush-presence!`) projects the
  map into a step-result."
  [ms]
  (if-let [f (presence-flush-fn)]
    (try
      (let [ret (f ms)]
        (if-let [p (thenable ret)]
          {:status :advanced :ms ms :pending p}
          {:status :advanced :ms ms}))
      (catch #?(:clj Throwable :cljs :default) e
        {:status :error
         :ms     ms
         :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))}))
    {:status :no-host :ms ms}))

(defn settle!
  "Call `k` with the FINAL `advance!` result for `res`. Returns nil.

  SYNCHRONOUS — `k` runs before `settle!` returns — for every advance that is
  already over: the JVM verb, a synchronous custom host, `:no-host`, and a
  host that threw. Those paths keep their existing behaviour exactly.

  When `res` carries a `:pending` thenable (the Promise-backed CLJS host),
  `k` runs once it settles:

    fulfilled → the same `:advanced` result with `:pending` dropped
    rejected  → `{:status :error :ms … :error s}` — the SAME shape a
                synchronously throwing host produces, so a rejection needs no
                new vocabulary downstream

  This is the whole of rf2-iz0t8: before it, `advance!` returned `:advanced`
  the instant the verb was CALLED, the executor recorded a clean step, and a
  later rejection (a React `act` failure, `:rf.error/flush-convergence-
  exceeded`) became an unhandled rejection that could no longer change the
  recorded result — a presence-bearing play reported `:pass` over a flush that
  had actually failed.

  Rejection is observed via the two-argument `.then`, not a chained `.catch`:
  a `.catch` would also swallow — and re-enter `k` for — a throw from `k`
  itself on the fulfilled path."
  [res k]
  #?(:clj  (do (k res) nil)
     :cljs (if-let [p (:pending res)]
             (do (.then p
                        (fn [_] (k (dissoc res :pending)))
                        (fn [e] (k {:status :error
                                    :ms     (:ms res)
                                    :error  (str e)})))
                 nil)
             (do (k res) nil))))
