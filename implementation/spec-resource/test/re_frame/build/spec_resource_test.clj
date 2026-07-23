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

(def ^:private iterations
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

(def ^:private counter (atom 0))

(def ^:private fixture-root
  "Temp directory added to the classpath for the run; each iteration's
  generated namespace lands under it."
  (atom nil))

(defn- generate-fixture!
  "Write a namespace whose `def` interns `probe-fn` when it is ANALYSED
  and binds it only when its initializer returns — and whose initializer
  blocks on `gate` until this test releases it. Returns the namespace
  symbol."
  [gate-name]
  (let [ns-name (str "re-frame.build.race-fixture-" (swap! counter inc))
        file    (io/file @fixture-root (str (-> ns-name
                                                (str/replace "-" "_")
                                                (str/replace "." "/"))
                                            ".clj"))]
    (io/make-parents file)
    (spit file (str "(ns " ns-name ")\n"
                    "(def probe-fn\n"
                    "  (do @@(clojure.lang.RT/var \"" gate-ns "\" \"" gate-name "\")\n"
                    "      (fn [] :probe)))\n"))
    (symbol ns-name)))

(defn- run-probe
  "Cold-load one generated namespace on a loader thread and, while its
  `probe-fn` Var is interned but still unbound, have a SECOND thread
  reach for the same Var through `resolver`.

  Returns what the observer saw while the Var was unbound (`:state
  :unbound` / `:state :bound`, or `::blocked` when the resolver made it
  wait), plus its final answer once the load completes."
  [resolver]
  (let [gate-name (str "release-" (swap! counter inc))
        gate      (promise)
        _         (intern (create-ns (symbol gate-ns)) (symbol gate-name) gate)
        ns-sym    (generate-fixture! gate-name)
        sym       (symbol (name ns-sym) "probe-fn")
        answer    (fn [] (try
                           (let [v (resolver sym)]
                             {:state (if (bound? v) :bound :unbound)})
                           (catch Throwable t {:state :threw :error t})))
        loaded    (promise)
        armed     (promise)
        observed  (promise)
        loader    (doto (Thread. #(deliver loaded (answer)) "spec-resource-loader")
                    (.setDaemon true))
        observer  (doto (Thread. #(do (deliver armed :in) (deliver observed (answer)))
                                 "spec-resource-observer")
                    (.setDaemon true))]
    (try
      (.start loader)
      ;; Wait for the window to open: the Var exists (its `def` has been
      ;; analysed) while its initializer is still blocked on the gate.
      (let [interned? (loop [n 0]
                        (cond
                          (resolve sym) true
                          (> n 5000)    false
                          :else         (do (Thread/sleep 1) (recur (inc n)))))]
        (when-not interned?
          (throw (ex-info "Race fixture never interned its Var." {:sym sym})))
        (.start observer)
        (deref armed 5000 nil)
        ;; The observer is inside the resolver with the Var still unbound.
        ;; It either answers, or the serialized require path makes it wait.
        (let [in-window (loop [n 0]
                          (cond
                            (realized? observed)
                            (deref observed)

                            (= java.lang.Thread$State/BLOCKED (.getState observer))
                            ::blocked

                            (> n 1000)
                            ::grace-expired

                            :else (do (Thread/sleep 1) (recur (inc n)))))]
          {:sym       sym
           :in-window in-window
           :observed  (do (deliver gate :go) (deref observed 10000 ::timeout))
           :loaded    (deref loaded 10000 ::timeout)}))
      (finally
        ;; Never leave the loader parked inside the require lock: it would
        ;; hang every later `require` in this JVM.
        (deliver gate :go)))))

(use-fixtures :once
  (fn [run-tests]
    (let [dir    (doto (io/file (System/getProperty "java.io.tmpdir")
                                (str "rf2-spec-resource-race-" (System/nanoTime)))
                   (.mkdirs))
          thread (Thread/currentThread)
          prior  (.getContextClassLoader thread)
          loader (clojure.lang.DynamicClassLoader. prior)]
      ;; The generated namespaces must be loadable by `require`, so their
      ;; directory joins the classpath for the duration of the run. Threads
      ;; started below inherit this context loader.
      (.addURL loader (.toURL (.toURI dir)))
      (.setContextClassLoader thread loader)
      (reset! fixture-root dir)
      (try
        (run-tests)
        (finally
          (.setContextClassLoader thread prior)
          (reset! fixture-root nil)
          (doseq [f (reverse (file-seq dir))] (.delete f)))))))

(deftest requiring-resolve-hands-out-an-unbound-var
  (testing "the known-broken control: `requiring-resolve` resolves BEFORE it
            takes the require lock, so a second site reaches a Var that its
            own `def` has interned but not yet bound"
    (dotimes [_ iterations]
      (let [{:keys [sym in-window loaded]} (run-probe requiring-resolve)]
        (is (= {:state :unbound} in-window)
            (str sym ": the pre-fix shape must reproduce the defect, otherwise the "
                 "fixed assertion below proves nothing"))
        (is (= {:state :bound} loaded)
            (str sym ": the LOADING site is never the one that loses — which is why "
                 "a one-consumer fix reads as a fix"))))))

(deftest resolve-after-require-never-hands-out-an-unbound-var
  (testing "the shipped shape enters the serialized require path first, so a
            second site cannot observe the Var until its namespace has
            finished loading"
    (dotimes [_ iterations]
      (let [{:keys [sym in-window observed loaded]}
            (run-probe spec-resource/resolve-after-require)]
        (is (= ::blocked in-window)
            (str sym ": the observer must WAIT on the require lock rather than "
                 "resolve into the load"))
        (is (= {:state :bound} observed))
        (is (= {:state :bound} loaded))))))

(deftest resolve-after-require-reports-a-missing-var
  (testing "a namespace that loads without defining the Var is a version-skew
            failure, and says so rather than surfacing as a null call"
    (let [gate-name (str "release-" (swap! counter inc))
          _         (intern (create-ns (symbol gate-ns)) (symbol gate-name) (doto (promise) (deliver :go)))
          ns-sym    (generate-fixture! gate-name)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not defined after loading"
            (spec-resource/resolve-after-require (symbol (name ns-sym) "absent")))))))
