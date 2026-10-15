(ns re-frame.build.spec-resource-test
  "Hold an interned Var unbound so a second resolver must await its namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.build.spec-resource :as rf.build.spec-resource]))

(def ^:private gate-ns "re-frame.build.spec-resource-test.gate")

(def ^:private unique-name-sequence (atom 0))

(def ^:private fixture-classpath-root (atom nil))

(defn- write-blocked-fixture-namespace!
  [release-gate-name]
  (let [fixture-ns-name    (str "re-frame.build.race-fixture-"
                                (swap! unique-name-sequence inc))
        fixture-source-file (io/file @fixture-classpath-root
                                     (str (-> fixture-ns-name
                                              (str/replace "-" "_")
                                              (str/replace "." "/"))
                                          ".clj"))]
    (io/make-parents fixture-source-file)
    ;; RT/var reaches the gate without requiring another namespace inside the load.
    (spit fixture-source-file
          (str "(ns " fixture-ns-name ")\n"
               "(def probe-fn\n"
               "  (do @@(clojure.lang.RT/var \"" gate-ns "\" \""
               release-gate-name "\")\n"
               "      (fn [] :probe)))\n"))
    (symbol fixture-ns-name)))

(defn- observe-resolver-during-load
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
        observer-result         (promise)
        loader-thread           (doto (Thread. #(deliver loader-result
                                                         (observe-var-state))
                                               "spec-resource-loader")
                                  (.setDaemon true))
        observer-thread         (doto (Thread. #(deliver observer-result
                                                         (observe-var-state))
                                               "spec-resource-observer")
                                  (.setDaemon true))]
    (try
      (.start loader-thread)
      ;; The initializer holds this Var unbound until the observer reaches it.
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
          {:during-load     during-load
           :observer-result (do
                              (deliver release-gate :go)
                              (deref observer-result 10000 ::timeout))
           :loader-result   (deref loader-result 10000 ::timeout)}))
      (finally
        ;; A parked loader would hold the require lock over every later test.
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
      ;; Both resolver threads inherit the classloader containing the fixture.
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

(deftest resolve-after-require-never-hands-out-an-unbound-var
  (is (= {:during-load ::blocked
          :observer-result {:state :bound}
          :loader-result {:state :bound}}
         (observe-resolver-during-load rf.build.spec-resource/resolve-after-require))))

(deftest resolve-after-require-reports-a-missing-var
  (let [release-gate-name (str "release-" (swap! unique-name-sequence inc))
        _release-gate-var (intern (create-ns (symbol gate-ns))
                                  (symbol release-gate-name)
                                  (doto (promise) (deliver :go)))
        fixture-ns-symbol (write-blocked-fixture-namespace! release-gate-name)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not defined after loading"
          (rf.build.spec-resource/resolve-after-require
           (symbol (name fixture-ns-symbol) "absent"))))))
