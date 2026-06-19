(ns re-frame.test-quiet
  "Silent-on-success test reporter.

  Loading this namespace installs `defmethod` overrides on
  `clojure.test/report` (JVM) and `cljs.test/report` (CLJS) so the
  REPORTER's own green-run output is exactly the canonical 3-line
  summary:

      Ran N tests containing M assertions.
      0 failures, 0 errors.

  Per-namespace `Testing <ns>` banners are SUPPRESSED on the success
  path; per-`deftest` `Testing <var>` banners were already silent in
  both reporters by default and stay that way.

  This reporter governs only the REPORTER's output.  The cross-runtime
  quiet contract (rf2-nrk066) is completed by the runtime entry points
  (`re-frame.test-quiet.runner` on the JVM,
  `re-frame.test-quiet.shadow-node` on CLJS node): stdout is
  PASS-THROUGH (a test's bare `(println …)` reaches stdout — only
  cognitect's discovery banner is dropped), and stderr/warning noise is
  BUFFERED and replayed only on RED.  So a green run shows the summary
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

  Output is therefore proportional to *failure count*, not test count —
  agents that read test output burn context proportional to actionable
  signal.  rf2-try1x is the parent bead; the rationale (~10-25K tokens
  per agent run on green-path noise) is captured there.

  Dispatch:

   - JVM (`clojure.test/report`): single-key dispatch on `(:type m)`.
     We `defmethod` `:begin-test-ns` / `:end-test-ns` /
     `:begin-test-var` / `:end-test-var` / `:fail` / `:error`.
   - CLJS (`cljs.test/report`): tuple dispatch on
     `[(:reporter env) (:type m)]` with the default reporter keyed at
     `::cljs.test/default`.  Same set of method keys, prefixed with
     the reporter sentinel.

  The *reporter* is bufferless for FAILURE TEXT — we don't capture
  failure-message bytes and replay them (the runners separately buffer
  stderr/warning noise; that buffering lives in the runtime entry points,
  not here).  Instead, the first `:fail` / `:error` inside an
  unprinted namespace prints the namespace banner immediately, then
  DELEGATES to the captured default `report` method.  This means the
  failure output shape (the precise text clojure.test or cljs.test
  emits for `FAIL in (...)`, the `expected:` / `actual:` lines, the
  stack) is owned by the test library, not forked here — any upstream
  change to failure formatting, formatter handling, or stack-depth
  behaviour is inherited automatically.

  Delegation mechanism: at namespace-load time — BEFORE we install our
  own overrides — we capture the library's default `:fail` / `:error`
  methods via `get-method`.  Our override then prints the withheld
  banner and invokes that captured method.  The default already wraps
  its body in `with-test-out` (JVM) / writes through `*out*` (CLJS), so
  the banner is emitted into the same stream by forcing it under the
  same `with-test-out` on the JVM and by a plain `println` on CLJS.
  Capturing the methods rather than re-`defmethod`-ing inline keeps the
  failure path a thin banner-prefix over the real reporter.

  Loading this namespace MULTIPLE TIMES is idempotent — `defmethod`
  silently replaces the existing dispatch entry.

  Reload-friendly: dropping the `defmethod` overrides at the top of the
  test runtime is fine.  The methods close over the namespace's
  `printed-banners` atom (a set of namespaces whose banner has already
  been flushed), which the failure path consults and grows.

  Nested-run correctness (rf2-8n97n.1 / .2): the banner namespace is
  derived from the FAILING var's metadata at `:fail` / `:error` time,
  NOT from a single mutable \"current ns\" cell.  A nested
  `run-tests` called from inside a `deftest` fires its own
  `:begin-test-ns`; with a single-cell design that clobbers the
  \"current ns\" to the inner namespace, so a subsequent outer failure
  is mislabelled (prints the inner ns banner) or — once the inner run
  flushed its banner — orphaned (no banner at all).  Reading the ns off
  the var that actually failed makes the banner always match the
  failure, regardless of nesting."
  (:require
    #?(:clj  [clojure.test]
       :cljs [cljs.test])))

;; ----------------------------------------------------------------------
;; Banner state.
;;
;; A `clojure.test/run-tests` (or `cljs.test/run-tests`) call walks
;; namespaces serially on the calling thread.  Every canonical test
;; runner — cognitect-test-runner, shadow.test.node, shadow.test.browser
;; — is single-threaded on the reporter callback, so a plain atom is
;; sufficient: on the JVM the multi-method body runs on the test-driver
;; thread, and under CLJS the single-threaded runtime makes it trivially
;; safe.
;;
;; We DON'T track a single mutable "current ns" cell — that is the source
;; of the nested-run mislabel / orphan bugs (rf2-8n97n.1 / .2), because a
;; nested run's `:begin-test-ns` clobbers it.  Instead we record the SET
;; of namespaces whose banner has already been flushed, and derive the
;; namespace to flush from the failing var itself (see `failing-ns`).
;;
;; The set is cleared when an OUTERMOST namespace is entered (ns-depth 0
;; at `:begin-test-ns`) so a fresh `run-tests` — or the next sibling ns
;; within one run — re-flushes banners.  A NESTED run's `:begin-test-ns`
;; fires while the outer ns is still open (ns-depth >= 1), so it does NOT
;; clear: the outer run's already-printed banners stay suppressed while
;; the inner ns can still print its own.  `:begin-test-ns`/`:end-test-ns`
;; are balanced per ns and never nested for sibling nses, so the depth
;; counter rises above 0 only across a nested run-tests boundary.

(def ^:private printed-banners
  (atom #{}))

(def ^:private ns-depth
  (atom 0))

(defn- on-begin-test-ns!
  "Track nesting depth and clear the printed-banner set on entry to an
  outermost namespace so each top-level run (and each sibling ns within
  it) re-flushes its banner.  Nested-run entries (depth >= 1) preserve
  the outer run's printed set."
  []
  (when (zero? @ns-depth)
    (reset! printed-banners #{}))
  (swap! ns-depth inc))

(defn- on-end-test-ns!
  []
  (swap! ns-depth (fn [d] (max 0 (dec d)))))

(defn- ns-banner-printed?
  [ns-sym]
  (contains? @printed-banners ns-sym))

(defn- mark-ns-printed!
  [ns-sym]
  (swap! printed-banners conj ns-sym))

(defn- print-banner!
  "Flush the `Testing <ns>` banner for `ns-sym` exactly once.  No-op if
  `ns-sym` is nil or its banner was already printed."
  [ns-sym]
  (when (and ns-sym (not (ns-banner-printed? ns-sym)))
    (println)
    (println "Testing" (name ns-sym))
    (mark-ns-printed! ns-sym)))

;; ----------------------------------------------------------------------
;; JVM overrides — clojure.test/report dispatches on (:type m).

#?(:clj
   (defn- failing-ns
     "The namespace symbol of the var that is currently failing/erroring,
     read off `clojure.test/*testing-vars*` (the same stack
     `testing-vars-str` renders).  Returns nil if no var is in scope.

     This is what makes the banner nesting-correct: it is derived from
     the var that actually failed, so a nested run-tests can never
     mislabel or orphan the outer failure's banner (rf2-8n97n.1 / .2)."
     []
     (when-let [v (first clojure.test/*testing-vars*)]
       (when-let [ns (:ns (meta v))]
         (ns-name ns)))))

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
     (defmethod clojure.test/report :begin-test-ns [_m]
       ;; Default behaviour prints "\nTesting <ns>"; we suppress and
       ;; defer to print-banner! on first failure/error.  The banner ns
       ;; is derived from the failing var (failing-ns), not from this
       ;; event, so a nested run-tests can't clobber it — here we only
       ;; track nesting depth + clear the printed-banner set on an
       ;; outermost entry.
       (on-begin-test-ns!))

     (defmethod clojure.test/report :end-test-ns [_m]
       (on-end-test-ns!))

     (defmethod clojure.test/report :begin-test-var [_m]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :end-test-var [_m]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :fail [m]
       ;; Flush the withheld banner, then DELEGATE to clojure.test's
       ;; default `:fail` reporter so the FAIL block + expected/actual
       ;; lines stay byte-for-byte the library's own output (failure
       ;; formatting is not forked here).  The banner `println` runs
       ;; under the SAME `with-test-out` the default uses, so both land
       ;; on `*test-out*` in order.
       (clojure.test/with-test-out
         (print-banner! (failing-ns)))
       (jvm-default-fail m))

     (defmethod clojure.test/report :error [m]
       (clojure.test/with-test-out
         (print-banner! (failing-ns)))
       (jvm-default-error m))))

;; ----------------------------------------------------------------------
;; CLJS overrides — cljs.test/report dispatches on
;; [(:reporter env) (:type m)] with default ::cljs.test/default.

#?(:cljs
   (defn- failing-ns
     "The namespace symbol of the var currently failing/erroring, read
     off the env's `:testing-vars` stack (the same stack
     `testing-vars-str` renders).  Derived from the var that actually
     failed so a nested run-tests can't mislabel/orphan the outer
     banner (rf2-8n97n.1 / .2).  Returns nil if no var is in scope."
     []
     (when-let [v (first (:testing-vars (cljs.test/get-current-env)))]
       (let [ns (:ns (meta v))]
         (when ns (symbol (name ns)))))))

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
     (defmethod cljs.test/report [:cljs.test/default :begin-test-ns] [_m]
       ;; Suppress the default "Testing <ns>"; defer to print-banner! on
       ;; first failure/error (banner ns derived from the failing var,
       ;; not this event).  Here we only track nesting depth + clear the
       ;; printed-banner set on an outermost entry.
       (on-begin-test-ns!))

     (defmethod cljs.test/report [:cljs.test/default :end-test-ns] [_m]
       (on-end-test-ns!))

     (defmethod cljs.test/report [:cljs.test/default :begin-test-var] [_m]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :end-test-var] [_m]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :fail] [m]
       ;; Flush the withheld banner, then DELEGATE to cljs.test's default
       ;; `:fail` reporter so the FAIL block + expected/actual lines stay
       ;; the library's own output (formatter handling, counters, etc. are
       ;; not forked here).  Both write through `*out*`, so the banner and
       ;; the delegated block stay in order.
       (print-banner! (failing-ns))
       (cljs-default-fail m))

     (defmethod cljs.test/report [:cljs.test/default :error] [m]
       (print-banner! (failing-ns))
       (cljs-default-error m))))
