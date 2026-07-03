(ns re-frame.performance
  "Performance API instrumentation — prod timing, default off (rf2-du3i).

  Per Spec 009 §Performance instrumentation. Distinct from the trace
  surface: trace runs in dev (and is too noisy to keep on in prod);
  the Performance API path is intended for **production timing** and is
  default-off behind a compile-time `goog-define` flag so Closure DCE
  elides every call site under `:advanced` unless the consumer opts in.

  Mechanism:

    - `enabled?` is a `goog-define`d boolean, default `false`. A consumer
      flips it via `:closure-defines {re-frame.performance/enabled? true}`
      in their shadow-cljs.edn / compiler-options.
    - `mark-and-measure` is a **macro** that expands to
      `(if enabled? <gated-bracket> (do ~@body))`. With the flag false
      at compile time, Closure constant-folds the gate; the gated
      branch DCEs and every helper string (the entry name, the
      `performance.measure` call site, the helper ns code) elides. The
      macro shape — rather than a fn — is what keeps the entry-name
      construction inside the gate too: a function-shaped helper would
      force its arg expressions to evaluate regardless, which would leave
      the `\"rf:\"` prefix string surviving DCE in the off bundle.
    - The runtime call sites (event dispatch, sub recompute, fx
      execution, render) wrap their hot-path work via `mark-and-measure`
      with a stable `rf:<bucket>:<id>` name.

  Observer-first contract — entries are **delivered, not retained**:

    - The bracket emits **one** `performance.measure` per invocation
      using the **options-bag** form
      `performance.measure(name, {start, end})` with numeric
      `performance.now()` timestamps. No `performance.mark` entries are
      allocated — the two marks per bracket were pure buffer growth with
      no documented consumer (nothing reads the `:start` / `:end` marks),
      so the options-bag form drops two-thirds of the entry churn.
    - After emitting, the bracket **clears the measure by name**
      (`performance.clearMeasures(name)`) so the host's User-Timing entry
      buffer does NOT accumulate. A live `PerformanceObserver` still
      receives the entry — observer callbacks fire at `measure()` time,
      before the clear — so timing is delivered to any attached observer
      / APM; the buffer just doesn't grow. This is the real bound: the
      framework clears after emit, NOT the (factually unbounded — W3C
      `maxBufferSize` is Infinite for measure entries) host buffer.
    - `retain-entries?` is a second `goog-define`d boolean, default
      `false`. Flip it (`:closure-defines {re-frame.performance/retain-entries? true}`)
      for one-shot DevTools / console workflows that read the retained
      buffer via `(.getEntriesByType js/performance \"measure\")` — with
      it on the per-emit clear is skipped so entries persist for
      `getEntriesByType` readers. Long-running (RUM) sessions leave it
      off and read via a `PerformanceObserver`.

  Naming convention (per Spec 009 §Performance instrumentation):
    rf:event:<event-id>     — event handler invocation
    rf:sub:<sub-id>          — subscription recompute
    rf:fx:<fx-key>           — fx handler invocation
    rf:render:<view-id>      — view render (when substrate exposes a hook)

  JVM is a no-op — the Performance API is browser-only.

  This is **separate from trace** by design. Trace is dev-only (gated on
  `re-frame.interop/debug-enabled?` / `goog.DEBUG`); the perf surface is
  prod-friendly and gated on `re-frame.performance/enabled?`. The two
  flags compose: a build that wants both flips both. A typical prod
  build has `goog.DEBUG=false` and `re-frame.performance/enabled?` either
  true or false depending on whether timing instrumentation is wanted."
  #?(:cljs (:require-macros [re-frame.performance])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the compile-time flags ------------------------------------------------

#?(:cljs
   (goog-define ^boolean enabled? false))

#?(:clj
   ;; JVM has no `:advanced` and no DCE. Two scopes use this var:
   ;;
   ;;   1. The CLJS macro body, expanded at compile time on the JVM —
   ;;      it reads this var to decide whether to emit the gated bracket
   ;;      or just the body. Default: emit the gated bracket so the CLJS
   ;;      compiler then has the full closed-over body to constant-fold
   ;;      against the goog-define value at :advanced time.
   ;;
   ;;   2. JVM-runtime callers (headless tests, SSR). The CLJC fn forms
   ;;      below treat the JVM as a permanent no-op — the Performance API
   ;;      is browser-only — so the JVM expansion is just `(do body...)`.
   (def ^:const enabled? false))

;; When true, the per-emit `performance.clearMeasures(name)` is skipped so
;; entries persist in the host's User-Timing buffer for one-shot
;; `getEntriesByType('measure')` readers (DevTools / console workflows).
;; Default false: entries are delivered to observers then cleared, so the
;; buffer does not grow across a long-running session. See the ns docstring
;; §Observer-first contract.
#?(:cljs
   (goog-define ^boolean retain-entries? false))

#?(:clj
   (def ^:const retain-entries? false))

;; ---- name builder (used by the macro expansion at the call site) ----------

(defn build-name
  "Build a `rf:<bucket>:<id>` entry name from the bucket keyword and the
  registered id keyword/symbol/string. Stable shape across every emit
  site so consumers filter on the `rf:` prefix without parsing per-
  bucket shapes.

  Per Spec 009 §Performance instrumentation §Naming convention.

  Two scopes use this fn:

    1. The CLJS macro body, expanded at compile time on the JVM — it
       emits `(build-name ~bucket ~id)` inside the gated branch. The
       call is JVM-runnable so test fixtures can predict the entry name
       without booting CLJS.
    2. CLJS runtime callers reached when the perf gate is on (the
       call lives inside the gated branch). Under `:advanced +
       enabled?=false` the entire branch DCEs and this fn vanishes
       from the production bundle.

  The body is pure data-shaping with no platform-specific calls — one
  `.cljc` definition serves both JVM and CLJS callers."
  [bucket id]
  (str "rf:" (name bucket) ":"
       (cond
         (keyword? id) (if-let [n (namespace id)]
                         (str n "/" (name id))
                         (name id))
         (symbol? id)  (str id)
         :else         (str id))))

;; ---- the macro -------------------------------------------------------------

#?(:clj
   (defmacro mark-and-measure
     "Macro form. Expands to a `(if enabled? <gated-bracket> (do body...))`
     so that under :advanced + `re-frame.performance/enabled?=false`,
     Closure constant-folds the gate, DCEs the gated branch, and the
     surviving form is just `(do body...)`. The bucket / id arguments are
     evaluated *inside* the gated branch — when the flag is off at
     compile time, even the entry-name construction is dead code.

     Usage at the call site:

         (perf/mark-and-measure :event event-id
           (interceptor/execute-chain full-chain initial-ctx))

     Three args: a `:bucket` keyword (`:event` / `:sub` / `:fx` /
     `:render` per Spec 009 §Performance instrumentation), an `id` value
     (keyword/symbol/string), and one or more body forms. Returns the
     last body form's value. JVM expansion is `(do body...)` — the
     Performance API is browser-only.

     Entry shape (when the flag is on): one `performance.measure` via the
     options-bag form `performance.measure(name, {start, end})` with
     numeric `performance.now()` timestamps — **no** `performance.mark`
     entries are allocated. Unless `retain-entries?` is on, the measure is
     cleared by name (`performance.clearMeasures(name)`) immediately after
     emit so the host's User-Timing buffer does not grow; a live
     `PerformanceObserver` still receives the entry (its callback fires at
     `measure()` time, before the clear). See the ns docstring
     §Observer-first contract.

     Exception isolation: when the flag is on, the bracket is wrapped in
     try/finally so the `measure` entry (and its clear) land even if the
     body throws. The exception still propagates."
     [bucket id & body]
     (if (:ns &env)
       ;; CLJS expansion. Constant-fold-able shape:
       ;;   (if enabled? <gated> (do body...))
       ;; Closure replaces `enabled?` with the goog-define value and
       ;; eliminates the dead branch under :advanced.
       `(if re-frame.performance/enabled?
          (let [nm#    (re-frame.performance/build-name ~bucket ~id)
                start# (.now js/performance)]
            (try
              (do ~@body)
              (finally
                (try
                  ;; Options-bag measure: numeric start/end timestamps,
                  ;; so no mark entries are ever allocated (two-thirds of
                  ;; the old per-bracket buffer growth; nothing reads the
                  ;; marks). The entry is delivered to any live
                  ;; PerformanceObserver at this call, then — unless the
                  ;; consumer opted into buffer retention — cleared so the
                  ;; buffer does not accumulate across a long session.
                  (.measure js/performance nm#
                            (cljs.core/js-obj "start" start#
                                              "end"   (.now js/performance)))
                  (when-not re-frame.performance/retain-entries?
                    (.clearMeasures js/performance nm#))
                  (catch :default _# nil)))))
          (do ~@body))
       ;; JVM expansion: pure pass-through. The Performance API is
       ;; browser-only; JVM-runtime callers (headless tests, SSR) get
       ;; the body run with no instrumentation overhead.
       `(do ~@body))))
