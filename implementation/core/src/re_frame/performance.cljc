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
      `performance.mark` / `performance.measure` call sites, the helper
      ns code) elides. The macro shape — rather than a fn — is what
      keeps the entry-name construction inside the gate too: a
      function-shaped helper would force its arg expressions to evaluate
      regardless, which would leave the `\"rf:\"` prefix string surviving
      DCE in the off bundle.
    - The runtime call sites (event dispatch, sub recompute, fx
      execution, render) wrap their hot-path work via `mark-and-measure`
      with a stable `rf:<bucket>:<id>` name. Consumers read entries via
      `(.getEntriesByType js/performance \"measure\")` and filter by
      the `rf:` prefix.

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

;; ---- the compile-time flag -------------------------------------------------

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

;; ---- name builder (used by the macro expansion at the call site) ----------

#?(:clj
   (defn build-name
     "Build a `rf:<bucket>:<id>` entry name from the bucket keyword and
     the registered id keyword/symbol/string. Stable shape across every
     emit site so consumers filter on the `rf:` prefix without parsing
     per-bucket shapes.

     Per Spec 009 §Performance instrumentation §Naming convention."
     [bucket id]
     (str "rf:" (name bucket) ":"
          (cond
            (keyword? id) (if-let [n (namespace id)]
                            (str n "/" (name id))
                            (name id))
            (symbol? id)  (str id)
            :else         (str id)))))

#?(:cljs
   (defn build-name
     "Build a `rf:<bucket>:<id>` entry name. Mirror of the JVM fn above
     so it is callable at runtime when the gate is on (the macro
     embeds a call to it inside the gated branch). At expansion time
     under :advanced + enabled?=false, every call to this fn lives
     inside the gated dead branch and DCEs."
     [bucket id]
     (str "rf:" (name bucket) ":"
          (cond
            (keyword? id) (if-let [n (namespace id)]
                            (str n "/" (name id))
                            (name id))
            (symbol? id)  (str id)
            :else         (str id)))))

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

     Exception isolation: when the flag is on, the bracket is wrapped in
     try/finally so the `:end` mark and the `measure` entry land even if
     the body throws. The exception still propagates."
     [bucket id & body]
     (if (:ns &env)
       ;; CLJS expansion. Constant-fold-able shape:
       ;;   (if enabled? <gated> (do body...))
       ;; Closure replaces `enabled?` with the goog-define value and
       ;; eliminates the dead branch under :advanced.
       `(if re-frame.performance/enabled?
          (let [nm#         (re-frame.performance/build-name ~bucket ~id)
                start-name# (str nm# ":start")
                end-name#   (str nm# ":end")]
            (.mark js/performance start-name#)
            (try
              (do ~@body)
              (finally
                (.mark js/performance end-name#)
                (try
                  (.measure js/performance nm# start-name# end-name#)
                  (catch :default _# nil)))))
          (do ~@body))
       ;; JVM expansion: pure pass-through. The Performance API is
       ;; browser-only; JVM-runtime callers (headless tests, SSR) get
       ;; the body run with no instrumentation overhead.
       `(do ~@body))))
