(ns re-frame.build.spec-resource-test
  "The deterministic control for the cold-load race
  [[re-frame.build.spec-resource/resolve-after-require]] exists to close.

  ## Why this is not a stress test

  The failure is a thread-scheduling race: a second resolver reaches a Var
  that has been INTERNED by the analysis of its `def` but not yet BOUND by
  its evaluation. Left to chance it reproduces on some runs and not
  others, which makes a green suite worthless as evidence — the defect
  shipped once already behind a wall of greens.

  So the window is not raced for, it is HELD OPEN. A synthetic namespace
  is generated per iteration whose single `def` blocks inside its
  initializer until this test releases it. While it blocks, the Var is
  interned and unbound — deterministically, for as long as the test
  wants — and two threads reach for it exactly as two macro-expansion
  threads do:

    * the PRE-FIX shape (`clojure.core/requiring-resolve`, whose first
      `resolve` runs BEFORE the require lock is taken) hands the observer
      the unbound Var. That is the shipped defect, pinned here as the
      known-broken control so the fixed assertion below cannot pass
      vacuously.

    * the SHIPPED shape (`resolve-after-require`, which enters the
      serialized require path first) cannot: the observer blocks on the
      same monitor the loader holds and resolves only once the namespace
      has finished loading.

  Two independent CALL SITES are the point — a per-consumer `delay` makes
  one consumer single-flight with itself and leaves two consumers free to
  race each other, which is exactly how the defect returned. Neither
  thread here shares any memoization with the other."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.build.spec-resource :as spec-resource]))

(def ^:private cold-load-iterations
  "Iterations per shape. Each one generates and cold-loads a fresh
  namespace, so every iteration is an independent first load — the only
  moment the race is reachable."
  25)

(def ^:private gate-ns
  "Namespace holding one promise per iteration. The generated fixture
  reaches it through `clojure.lang.RT/var`, which interns without
  requiring, so the fixture depends on nothing and can be loaded from a
  bare classpath directory."
  "re-frame.build.spec-resource-test.gate")

(def ^:private unique-name-sequence (atom 0))

(def ^:private fixture-classpath-root
  "Temp directory added to the classpath for the run; each iteration's
  generated namespace lands under it."
  (atom nil))

(defn- write-blocked-fixture-namespace!
  "Write a namespace whose `def` interns `probe-fn` when it is ANALYSED
  and binds it only when its initializer returns — and whose initializer
  blocks on `gate` until this test releases it. Returns the namespace
  symbol."
  [release-gate-name]
  (let [fixture-ns-name    (str "re-frame.build.race-fixture-"
                                (swap! unique-name-sequence inc))
        fixture-source-file (io/file @fixture-classpath-root
                                     (str (-> fixture-ns-name
                                              (str/replace "-" "_")
                                              (str/replace "." "/"))
                                          ".clj"))]
    (io/make-parents fixture-source-file)
    (spit fixture-source-file
          (str "(ns " fixture-ns-name ")\n"
               "(def probe-fn\n"
               "  (do @@(clojure.lang.RT/var \"" gate-ns "\" \""
               release-gate-name "\")\n"
               "      (fn [] :probe)))\n"))
    (symbol fixture-ns-name)))

(defn- observe-resolver-during-load
  "Cold-load one generated namespace on a loader thread and, while its
  `probe-fn` Var is interned but still unbound, have a SECOND thread
  reach for the same Var through `resolve-var`.

  Returns the probe Var symbol, what the observer saw during the held-open
  load (`:state :unbound` / `:state :bound`, or `::blocked` when the
  resolver made it wait), and both actors' final results after release."
  [resolve-var]
  (let [release-gate-name       (str "release-" (swap! unique-name-sequence inc))
        release-gate            (promise)
        _release-gate-var       (intern (create-ns (symbol gate-ns))
                                        (symbol release-gate-name)
                                        release-gate)
        fixture-ns-symbol       (write-blocked-fixture-namespace! release-gate-name)
        probe-var-symbol        (symbol (name fixture-ns-symbol) "probe-fn")
        observe-var-state       (fn [] (try
                                         (let [resolved-var (resolve-var probe-var-symbol)]
                                           {:state (if (bound? resolved-var)
                                                     :bound
                                                     :unbound)})
                                         (catch Throwable error
                                           {:state :threw :error error})))
        loader-result           (promise)
        observer-started         (promise)
        observer-result         (promise)
        loader-thread           (doto (Thread. #(deliver loader-result
                                                         (observe-var-state))
                                               "spec-resource-loader")
                                  (.setDaemon true))
        observer-thread         (doto (Thread. #(do
                                                 (deliver observer-started :in)
                                                 (deliver observer-result
                                                          (observe-var-state)))
                                               "spec-resource-observer")
                                  (.setDaemon true))]
    (try
      (.start loader-thread)
      ;; Wait for the window to open: the Var exists (its `def` has been
      ;; analysed) while its initializer is still blocked on the gate.
      (let [probe-var-interned? (loop [attempt 0]
                                  (cond
                                    (resolve probe-var-symbol) true
                                    (> attempt 5000)          false
                                    :else                     (do
                                                                (Thread/sleep 1)
                                                                (recur (inc attempt)))))]
        (when-not probe-var-interned?
          (throw (ex-info "Race fixture never interned its Var."
                          {:probe-var-symbol probe-var-symbol})))
        (.start observer-thread)
        (deref observer-started 5000 nil)
        ;; The observer has started and is about to call the resolver while
        ;; the Var is still unbound. It either answers, or the serialized
        ;; require path makes it wait.
        (let [during-load (loop [attempt 0]
                            (cond
                              (realized? observer-result)
                              (deref observer-result)

                              (= java.lang.Thread$State/BLOCKED
                                 (.getState observer-thread))
                              ::blocked

                              (> attempt 1000)
                              ::grace-expired

                              :else (do
                                      (Thread/sleep 1)
                                      (recur (inc attempt)))))]
          {:probe-var-symbol probe-var-symbol
           :during-load     during-load
           :observer-result (do
                              (deliver release-gate :go)
                              (deref observer-result 10000 ::timeout))
           :loader-result   (deref loader-result 10000 ::timeout)}))
      (finally
        ;; Never leave the loader parked inside the require lock: it would
        ;; hang every later `require` in this JVM.
        (deliver release-gate :go)))))

(use-fixtures :once
  (fn [run-suite]
    (let [fixture-dir            (doto (io/file (System/getProperty "java.io.tmpdir")
                                                (str "rf2-spec-resource-race-"
                                                     (System/nanoTime)))
                                   (.mkdirs))
          current-thread         (Thread/currentThread)
          prior-context-loader   (.getContextClassLoader current-thread)
          fixture-class-loader   (clojure.lang.DynamicClassLoader.
                                   prior-context-loader)]
      ;; The generated namespaces must be loadable by `require`, so their
      ;; directory joins the classpath for the duration of the run. Threads
      ;; started below inherit this context loader.
      (.addURL fixture-class-loader (.toURL (.toURI fixture-dir)))
      (.setContextClassLoader current-thread fixture-class-loader)
      (reset! fixture-classpath-root fixture-dir)
      (try
        (run-suite)
        (finally
          (.setContextClassLoader current-thread prior-context-loader)
          (reset! fixture-classpath-root nil)
          (doseq [file (reverse (file-seq fixture-dir))]
            (.delete file)))))))

(deftest requiring-resolve-hands-out-an-unbound-var
  (testing "the known-broken control: `requiring-resolve` resolves BEFORE it
            takes the require lock, so a second site reaches a Var that its
            own `def` has interned but not yet bound"
    (dotimes [_ cold-load-iterations]
      (let [{:keys [probe-var-symbol during-load loader-result]}
            (observe-resolver-during-load requiring-resolve)]
        (is (= {:state :unbound} during-load)
            (str probe-var-symbol
                 ": the pre-fix shape must reproduce the defect, otherwise the "
                 "fixed assertion below proves nothing"))
        (is (= {:state :bound} loader-result)
            (str probe-var-symbol
                 ": the LOADING site is never the one that loses — which is why "
                 "a one-consumer fix reads as a fix"))))))

(deftest resolve-after-require-never-hands-out-an-unbound-var
  (testing "the shipped shape enters the serialized require path first, so a
            second site cannot observe the Var until its namespace has
            finished loading"
    (dotimes [_ cold-load-iterations]
      (let [{:keys [probe-var-symbol during-load observer-result loader-result]}
            (observe-resolver-during-load spec-resource/resolve-after-require)]
        (is (= ::blocked during-load)
            (str probe-var-symbol
                 ": the observer must WAIT on the require lock rather than "
                 "resolve into the load"))
        (is (= {:state :bound} observer-result))
        (is (= {:state :bound} loader-result))))))

(deftest resolve-after-require-reports-a-missing-var
  (testing "a namespace that loads without defining the Var is a version-skew
            failure, and says so rather than surfacing as a null call"
    (let [release-gate-name (str "release-" (swap! unique-name-sequence inc))
          _release-gate-var (intern (create-ns (symbol gate-ns))
                                    (symbol release-gate-name)
                                    (doto (promise) (deliver :go)))
          fixture-ns-symbol (write-blocked-fixture-namespace! release-gate-name)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not defined after loading"
            (spec-resource/resolve-after-require
             (symbol (name fixture-ns-symbol) "absent")))))))
