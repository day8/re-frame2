(ns re-frame.test-quiet
  "Silent-on-success test reporter.

  Loading this namespace installs `defmethod` overrides on
  `clojure.test/report` (JVM) and `cljs.test/report` (CLJS) so a green
  test run emits exactly the canonical 3-line summary:

      Ran N tests containing M assertions.
      0 failures, 0 errors.

  Per-namespace `Testing <ns>` banners are SUPPRESSED on the success
  path; per-`deftest` `Testing <var>` banners were already silent in
  both reporters by default and stay that way.

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

  The reporter is BUFFERLESS — we don't capture failure-message bytes
  and replay them.  Instead, the first `:fail` / `:error` inside an
  unprinted namespace prints the namespace banner immediately, then
  delegates to the default method via direct re-entry.  This means the
  failure output shape (the precise text clojure.test or cljs.test
  emits for `FAIL in (...)`, the `expected:` / `actual:` lines, the
  stack) is byte-for-byte unchanged.

  Loading this namespace MULTIPLE TIMES is idempotent — `defmethod`
  silently replaces the existing dispatch entry.

  Reload-friendly: dropping the `defmethod` overrides at the top of the
  test runtime is fine.  The methods close over the namespace's
  per-thread atom (`*ns-banner-state*`), which is reset on each
  `:begin-test-ns`."
  (:require
    #?(:clj  [clojure.test]
       :cljs [cljs.test])
    #?(:clj  [clojure.stacktrace])))

;; ----------------------------------------------------------------------
;; Per-thread banner state.
;;
;; A `clojure.test/run-tests` (or `cljs.test/run-tests`) call walks
;; namespaces serially on the calling thread.  Holding banner state in
;; a thread-local atom is sufficient for sequential drivers (every
;; canonical test runner — cognitect-test-runner, shadow.test.node,
;; shadow.test.browser — is single-threaded on the reporter callback);
;; we don't need a dynamic var.  A plain atom is fine on the JVM since
;; the multi-method body runs on the test-driver thread.  Under CLJS
;; the single-threaded runtime makes the atom trivially safe.
;;
;; Shape: {:ns <symbol> :banner-printed? <bool>}.  Reset on each
;; :begin-test-ns; consulted on each :fail / :error.

(def ^:private banner-state
  (atom {:ns nil :banner-printed? false}))

(defn- reset-ns-banner!
  [ns-sym]
  (reset! banner-state {:ns ns-sym :banner-printed? false}))

(defn- ensure-banner-printed!
  []
  (let [{:keys [ns banner-printed?]} @banner-state]
    (when (and ns (not banner-printed?))
      (println)
      (println "Testing" (name ns))
      (swap! banner-state assoc :banner-printed? true))))

;; ----------------------------------------------------------------------
;; JVM overrides — clojure.test/report dispatches on (:type m).

#?(:clj
   (do
     (defmethod clojure.test/report :begin-test-ns [m]
       ;; Default behaviour prints "\nTesting <ns>"; we suppress and
       ;; defer to ensure-banner-printed! on first failure/error.
       (reset-ns-banner! (ns-name (:ns m))))

     (defmethod clojure.test/report :end-test-ns [_m]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :begin-test-var [_m]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :end-test-var [_m]
       ;; No-op (also the default).
       )

     (defmethod clojure.test/report :fail [m]
       (clojure.test/with-test-out
         (ensure-banner-printed!)
         (clojure.test/inc-report-counter :fail)
         (println "\nFAIL in" (clojure.test/testing-vars-str m))
         (when (seq clojure.test/*testing-contexts*)
           (println (clojure.test/testing-contexts-str)))
         (when-let [message (:message m)] (println message))
         (println "expected:" (pr-str (:expected m)))
         (println "  actual:" (pr-str (:actual m)))))

     (defmethod clojure.test/report :error [m]
       (clojure.test/with-test-out
         (ensure-banner-printed!)
         (clojure.test/inc-report-counter :error)
         (println "\nERROR in" (clojure.test/testing-vars-str m))
         (when (seq clojure.test/*testing-contexts*)
           (println (clojure.test/testing-contexts-str)))
         (when-let [message (:message m)] (println message))
         (println "expected:" (pr-str (:expected m)))
         (print "  actual: ")
         (let [actual (:actual m)]
           (if (instance? Throwable actual)
             (clojure.stacktrace/print-cause-trace actual clojure.test/*stack-trace-depth*)
             (prn actual)))))))

;; ----------------------------------------------------------------------
;; CLJS overrides — cljs.test/report dispatches on
;; [(:reporter env) (:type m)] with default ::cljs.test/default.

#?(:cljs
   (do
     (defmethod cljs.test/report [:cljs.test/default :begin-test-ns] [m]
       (reset-ns-banner! (:ns m)))

     (defmethod cljs.test/report [:cljs.test/default :end-test-ns] [_m]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :begin-test-var] [_m]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :end-test-var] [_m]
       ;; No-op (also the default).
       )

     (defmethod cljs.test/report [:cljs.test/default :fail] [m]
       (ensure-banner-printed!)
       (cljs.test/inc-report-counter! :fail)
       (println "\nFAIL in" (cljs.test/testing-vars-str m))
       (when (seq (:testing-contexts (cljs.test/get-current-env)))
         (println (cljs.test/testing-contexts-str)))
       (when-let [message (:message m)] (println message))
       (let [formatter-fn (or (:formatter (cljs.test/get-current-env)) pr-str)]
         (println "expected:" (formatter-fn (:expected m)))
         (println "  actual:" (formatter-fn (:actual m)))))

     (defmethod cljs.test/report [:cljs.test/default :error] [m]
       (ensure-banner-printed!)
       (cljs.test/inc-report-counter! :error)
       (println "\nERROR in" (cljs.test/testing-vars-str m))
       (when (seq (:testing-contexts (cljs.test/get-current-env)))
         (println (cljs.test/testing-contexts-str)))
       (when-let [message (:message m)] (println message))
       (let [formatter-fn (or (:formatter (cljs.test/get-current-env)) pr-str)]
         (println "expected:" (formatter-fn (:expected m)))
         (println "  actual:" (formatter-fn (:actual m)))))))
