(ns re-frame.prod-gate-dispatch-jvm-test
  "Dispatch under the REAL documented production gate. `make-frame` seals an
  image generation unconditionally, so the machinery that keeps it in step with
  later registrations must be unconditional too; were it gated on
  `interop/debug-enabled?`, every `reg-*` after `make-frame` would be invisible
  to dispatch under `-Dre-frame.debug=false`. The flag is read once at load, so
  `with-redefs` cannot reproduce that: this suite relaunches a fresh JVM with
  the property on its command line, running `re-frame.prod-gate-dispatch-probe`,
  and asserts on what the child observed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.prod-gate-dispatch-probe :as rf.prod-gate-dispatch-probe]))

(defn- java-binary []
  (let [win? (str/includes? (str/lower-case (System/getProperty "os.name")) "win")]
    (str (io/file (System/getProperty "java.home") "bin"
                  (if win? "java.exe" "java")))))

(defn- run-probe
  "Relaunch a fresh JVM on this classpath running the probe's `-main` with
  `jvm-opts` prepended, and return its stdout. Both pipes are drained
  concurrently: reading stderr only after stdout deadlocks a child that fills
  the stderr pipe."
  [jvm-opts]
  (let [cmd  (into [(java-binary)]
                   (concat jvm-opts
                           ["-cp" (System/getProperty "java.class.path")
                            "clojure.main"
                            "-m" "re-frame.prod-gate-dispatch-probe"]))
        proc (-> (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream false)
                 (.start))
        out  (future (slurp (io/reader (.getInputStream proc))))
        err  (future (slurp (io/reader (.getErrorStream proc))))]
    (.waitFor proc)
    @err
    @out))

(defn- parse-result [out]
  (some->> (str/split-lines out)
           (filter #(str/starts-with? % rf.prod-gate-dispatch-probe/result-marker))
           first
           (drop (count rf.prod-gate-dispatch-probe/result-marker))
           (apply str)
           edn/read-string))

;; One child JVM serves every deftest: the relaunch costs a JVM boot plus a
;; `re-frame.core` load.
(def ^:private observed
  (delay (parse-result (run-probe ["-Dre-frame.debug=false"]))))

(deftest gate-was-really-off
  ;; Without this the suite would pass vacuously once the property stopped
  ;; reaching the child.
  (is (false? (:debug-enabled? @observed))))

(deftest dispatch-sync-runs-its-handler-under-the-production-gate
  (is (= {:handler-runs 1 :app-db {:n 1} :errors []}
         (select-keys @observed [:handler-runs :app-db :errors]))))

(deftest cleared-registration-disappears-under-the-production-gate
  (let [result @observed]
    (is (= 1 (:runs-after-unregister result))
        "the cleared handler must not run a second time")
    (is (some #{:rf.error/no-such-handler} (:errors-after-unregister result))
        (pr-str (:errors-after-unregister result)))))
