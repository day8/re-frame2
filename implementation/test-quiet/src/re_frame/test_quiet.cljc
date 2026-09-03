(ns re-frame.test-quiet
  "Silent-on-success test reporter.

  Loading this namespace installs `defmethod` overrides on
  `clojure.test/report` (JVM) and `cljs.test/report` (CLJS). The
  reporter's green output is one leading blank followed by the two
  canonical summary lines:

      Ran N tests containing M assertions.
      0 failures, 0 errors.

  Per-namespace `Testing <ns>` banners are suppressed on the success
  path; per-`deftest` `Testing <var>` banners were already silent in
  both reporters by default and stay that way.

  This namespace governs only reporter output. The runtime entry points
  (`re-frame.test-quiet.runner` on the JVM and
  `re-frame.test-quiet.shadow-node` on CLJS node) pass stdout through. The
  JVM runner filters cognitect's discovery banner. Each runner buffers its
  runtime-specific warning channel and replays it only on red. A green run shows the summary
  plus whatever a test deliberately printed, and never the expected
  warnings warning-heavy suites emit.  See those namespaces' docstrings
  for the runtime side of the contract.

  On failure or error the suppressed banner for that namespace is
  flushed lazily (once per namespace), so a red run looks like:

      Testing my.failing-ns

      FAIL in (my-test) (...)
      expected: ...
        actual: ...

      Ran N tests containing M assertions.
      1 failures, 0 errors.

  Dispatch:

   - JVM (`clojure.test/report`): single-key dispatch on `(:type m)`.
     We `defmethod` `:begin-test-ns` / `:end-test-ns` /
     `:begin-test-var` / `:end-test-var` / `:fail` / `:error`.
   - CLJS (`cljs.test/report`): tuple dispatch on
     `[(:reporter env) (:type m)]` with the default reporter keyed at
     `::cljs.test/default`.  Same set of method keys, prefixed with
     the reporter sentinel.

  The reporter does not buffer failure text: we don't capture
  failure-message bytes and replay them (the runners separately buffer
  stderr/warning noise; that buffering lives in the runtime entry points,
  not here).  Instead, the first `:fail` / `:error` inside an
  unprinted namespace prints the namespace banner immediately, then
  delegates to the captured default `report` method. This means the
  failure output shape (the precise text clojure.test or cljs.test
  emits for `FAIL in (...)`, the `expected:` / `actual:` lines, the
  stack) is owned by the test library, not forked here — any upstream
  change to failure formatting, formatter handling, or stack-depth
  behaviour is inherited automatically.

  At namespace-load time, before installing the overrides, we capture the
  library's default `:fail` / `:error`
  methods via `get-method`.  Our override then prints the withheld
  banner and invokes that captured method.  The default already wraps
  its body in `with-test-out` (JVM) / writes through `*out*` (CLJS), so
  the banner is emitted into the same stream by forcing it under the
  same `with-test-out` on the JVM and by a plain `println` on CLJS.
  Capturing the methods keeps the failure path a thin banner prefix over
  the real reporter. `defonce` protects those captures from reload-induced
  self-recursion.

  The failure banner comes from the failing var, so nested `run-tests`
  calls cannot relabel an outer failure. A balanced namespace stack
  supplies the fallback for `test-ns-hook` assertions, which run without
  a current test var."
  (:require
    #?(:clj  [clojure.test]
       :cljs [cljs.test])))

;; ----------------------------------------------------------------------
;; Banner state.
;;
;; Reporter callbacks are serial in the supported runners, so plain atoms
;; are sufficient for this process-local state.
;;
;; The primary banner namespace comes from the failing var, not mutable
;; current-namespace state, so nested runs cannot clobber it.
;;
;; The balanced namespace stack distinguishes outermost from nested entry,
;; resets the printed set between sibling namespaces, and supplies the
;; fallback for `test-ns-hook` failures that have no current var.

(def ^:private printed-banner-namespaces
  (atom #{}))

(def ^:private open-test-namespace-stack
  (atom []))

(defn- open-test-namespace!
  "Push `namespace-symbol` onto the open-namespace stack and clear the printed-banner
  set on entry to an outermost namespace (empty stack) so each top-level
  run and each sibling ns within it re-flushes its banner. Nested-run
  entries (non-empty stack) preserve the outer run's printed set."
  [namespace-symbol]
  (when (empty? @open-test-namespace-stack)
    (reset! printed-banner-namespaces #{}))
  (swap! open-test-namespace-stack conj namespace-symbol))

(defn- close-test-namespace!
  []
  (swap! open-test-namespace-stack
         (fn [namespace-stack]
           (if (seq namespace-stack) (pop namespace-stack) namespace-stack))))

(defn- current-test-namespace
  "The namespace of the innermost currently-open `test-ns` (top of the
  begin/end stack), or nil if none is open. The fallback banner ns for a
  failure with no failing var in scope (see `failure-banner-namespace`)."
  []
  (peek @open-test-namespace-stack))

(defn- namespace-banner-printed?
  [namespace-symbol]
  (contains? @printed-banner-namespaces namespace-symbol))

(defn- mark-namespace-banner-printed!
  [namespace-symbol]
  (swap! printed-banner-namespaces conj namespace-symbol))

(defn- print-namespace-banner!
  "Flush the `Testing <ns>` banner for `namespace-symbol` exactly once. No-op
  if `namespace-symbol` is nil or its banner was already printed."
  [namespace-symbol]
  (when (and namespace-symbol
             (not (namespace-banner-printed? namespace-symbol)))
    (println)
    (println "Testing" (name namespace-symbol))
    (mark-namespace-banner-printed! namespace-symbol)))

;; ----------------------------------------------------------------------
;; JVM overrides — clojure.test/report dispatches on (:type m).

#?(:clj
   (defn- failing-test-namespace
     "The namespace symbol of the var that is currently failing/erroring,
     read off `clojure.test/*testing-vars*` (the same stack
     `testing-vars-str` renders).  Returns nil if no var is in scope.

     The var-derived namespace remains correct across nested runs."
     []
     (when-let [test-var (first clojure.test/*testing-vars*)]
       (when-let [test-namespace (:ns (meta test-var))]
         (ns-name test-namespace)))))

#?(:clj
   (defn- report-event-namespace
     "The namespace symbol carried by a `:begin-test-ns` `report-event`.
     clojure.test passes the `clojure.lang.Namespace`; normalise to its
     `ns-name` symbol so it matches `failing-test-namespace` and prints cleanly."
     [report-event]
     (when-let [test-namespace (:ns report-event)]
       (if (instance? clojure.lang.Namespace test-namespace)
         (ns-name test-namespace)
         (symbol (name test-namespace))))))

#?(:clj
   (defn- failure-banner-namespace
     "The namespace to head the failure banner: the failing var's ns when a
     var is in scope, otherwise the innermost open `test-ns`."
     []
     (or (failing-test-namespace) (current-test-namespace))))

;; `defonce` (not `def`) so a namespace RELOAD does not re-capture: on a
;; first load `get-method` returns clojure.test's default (our override is
;; not installed yet); on a reload our override is already installed, so a
;; plain `def` would capture OUR method and recurse infinitely.  `defonce`
;; pins the original default for the lifetime of the runtime.
#?(:clj
   (defonce ^:private jvm-default-fail
     (get-method clojure.test/report :fail)))

#?(:clj
   (defonce ^:private jvm-default-error
     (get-method clojure.test/report :error)))

#?(:clj
   (do
     (defmethod clojure.test/report :begin-test-ns [report-event]
       ;; Default behaviour prints "\nTesting <ns>"; we suppress and defer
       ;; to print-namespace-banner! on first failure/error. The banner ns is
       ;; normally derived from the failing var (failing-test-namespace), not this
       ;; event, so a nested run-tests can't clobber it; we push this ns
       ;; onto the open-ns stack (for nesting-depth tracking + the
       ;; test-ns-hook no-var banner fallback) and clear the printed-banner
       ;; set on an outermost entry.
       (open-test-namespace! (report-event-namespace report-event)))

     (defmethod clojure.test/report :end-test-ns [_report-event]
       (close-test-namespace!))

     (defmethod clojure.test/report :begin-test-var [_report-event]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :end-test-var [_report-event]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :fail [report-event]
       ;; Flush the withheld banner, then DELEGATE to clojure.test's
       ;; default `:fail` reporter so the FAIL block + expected/actual
       ;; lines stay byte-for-byte the library's own output (failure
       ;; formatting is not forked here).  The banner `println` runs
       ;; under the SAME `with-test-out` the default uses, so both land
       ;; on `*test-out*` in order.
       (clojure.test/with-test-out
         (print-namespace-banner! (failure-banner-namespace)))
       (jvm-default-fail report-event))

     (defmethod clojure.test/report :error [report-event]
       (clojure.test/with-test-out
         (print-namespace-banner! (failure-banner-namespace)))
       (jvm-default-error report-event))))

;; ----------------------------------------------------------------------
;; CLJS overrides — cljs.test/report dispatches on
;; [(:reporter env) (:type m)] with default ::cljs.test/default.

#?(:cljs
   (defn- failing-test-namespace
     "The namespace symbol of the var currently failing/erroring, read
     off the env's `:testing-vars` stack (the same stack
     `testing-vars-str` renders).  Derived from the var that actually
     failed so a nested run-tests can't mislabel/orphan the outer
     banner. Returns nil if no var is in scope."
     []
     (when-let [test-var (first (:testing-vars (cljs.test/get-current-env)))]
       (let [test-namespace (:ns (meta test-var))]
         (when test-namespace (symbol (name test-namespace)))))))

#?(:cljs
   (defn- report-event-namespace
     "The namespace symbol carried by a `:begin-test-ns` `report-event`.
     cljs.test passes the ns symbol; normalise via `name`/`symbol` so it
     matches `failing-test-namespace`."
     [report-event]
     (when-let [test-namespace (:ns report-event)]
       (symbol (name test-namespace)))))

#?(:cljs
   (defn- failure-banner-namespace
     "The namespace to head the failure banner: the failing var's ns when a
     var is in scope, otherwise the innermost open `test-ns`."
     []
     (or (failing-test-namespace) (current-test-namespace))))

;; `defonce` for the same reload-safety reason as the JVM capture above:
;; pin cljs.test's ORIGINAL default reporters so a reload (e.g. shadow
;; hot-reload) never re-captures our own installed override and recurses.
#?(:cljs
   (defonce ^:private cljs-default-fail
     (get-method cljs.test/report [:cljs.test/default :fail])))

#?(:cljs
   (defonce ^:private cljs-default-error
     (get-method cljs.test/report [:cljs.test/default :error])))

#?(:cljs
   (do
     (defmethod cljs.test/report [:cljs.test/default :begin-test-ns] [report-event]
       ;; Suppress the default "Testing <ns>"; defer to
       ;; print-namespace-banner! on
       ;; first failure/error (banner ns normally derived from the failing
       ;; var, not this event).  Push this ns onto the open-ns stack (for
       ;; nesting-depth tracking + the no-current-var banner fallback) and
       ;; clear the printed-banner set on an outermost entry.
       (open-test-namespace! (report-event-namespace report-event)))

     (defmethod cljs.test/report [:cljs.test/default :end-test-ns] [_report-event]
       (close-test-namespace!))

     (defmethod cljs.test/report [:cljs.test/default :begin-test-var] [_report-event]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :end-test-var] [_report-event]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :fail] [report-event]
       ;; Flush the withheld banner, then DELEGATE to cljs.test's default
       ;; `:fail` reporter so the FAIL block + expected/actual lines stay
       ;; the library's own output (formatter handling, counters, etc. are
       ;; not forked here).  Both write through `*out*`, so the banner and
       ;; the delegated block stay in order.
       (print-namespace-banner! (failure-banner-namespace))
       (cljs-default-fail report-event))

     (defmethod cljs.test/report [:cljs.test/default :error] [report-event]
       (print-namespace-banner! (failure-banner-namespace))
       (cljs-default-error report-event))))
