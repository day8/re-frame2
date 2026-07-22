(ns re-frame.freehand.bench-cljs-test
  "The harness: what a workload must declare, what a run emits, and where
  an exit code is allowed to come from."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.measure :as m]))

(defn- a-workload
  [overrides]
  (merge
    {:id           :test/tiny
     :doc          "a workload that observes one count and one duration"
     :fixture      {:rows 3}
     :baseline     {:kind :before-vs-after :reference {:revision "deadbeef"}}
     :sampling     {:warmup 1 :samples 3}
     :measurements [{:id :test/rows :doc "the row count" :observable :count :expect 3}
                    {:id :test/self-ms :doc "self time" :observable :duration-ms}]
     :run          (fn [{:keys [rows]}]
                     (let [t0 (m/now-ms)]
                       {:test/rows rows
                        :test/self-ms (- (m/now-ms) t0)}))}
    overrides))

(defn- defect
  [spec]
  (try (bench/workload spec) nil
       (catch #?(:clj Exception :cljs :default) e
         (or (:bench/defect (ex-data e)) :threw-without-a-defect))))

;; ---------------------------------------------------------------------------
;; Declaration
;; ---------------------------------------------------------------------------

(deftest a-workload-is-refused-before-it-burns-a-sampling-run
  (testing "the provenance a WORKLOAD owns is checked at declaration, not
            after ten minutes of sampling. The rest is detected at run
            time and checked when the record is emitted."
    (is (nil? (defect (a-workload {}))))
    (is (= :workload-id (defect (a-workload {:id "tiny"}))))
    (is (= :workload-doc (defect (dissoc (a-workload {}) :doc))))
    (is (= :workload-run (defect (dissoc (a-workload {}) :run))))
    (is (= :workload-measurements (defect (assoc (a-workload {}) :measurements []))))
    (doseq [omitted [:fixture :sampling :baseline]]
      (is (= :incomplete-workload (defect (dissoc (a-workload {}) omitted)))
          (str "a workload declaring no " omitted " was accepted")))
    (is (= :incomplete-workload
           (defect (assoc (a-workload {}) :baseline {:kind :vibes :reference {:x 1}})))
        "the baseline vocabulary is the four D021 names")))

(deftest a-duplicate-measurement-id-is-refused
  (testing "one id names one observation, or a distribution silently
            summarises two different things."
    (is (= :duplicate-measurement-id
           (defect (assoc (a-workload {}) :measurements
                          [{:id :test/rows :doc "a" :observable :count :expect 3}
                           {:id :test/rows :doc "b" :observable :count :expect 3}]))))))

(deftest every-workload-publishes-the-harnesss-own-reading
  (testing "the outer bound the workload's self time sits inside — which
            is why every result record has a distribution to publish."
    (let [norm (bench/workload (a-workload {}))]
      (is (contains? (set (map :id (:measurements norm))) :bench/iteration-ms))
      (is (m/evidence? bench/iteration-ms)))
    (testing "and normalisation is idempotent, so registering then
              running does not add it twice"
      (let [once (bench/workload (a-workload {}))]
        (is (= (:measurements once) (:measurements (bench/workload once))))))))

;; ---------------------------------------------------------------------------
;; The emitted record
;; ---------------------------------------------------------------------------

(deftest a-run-emits-a-validated-result-record
  (testing "D021 §Release evidence artifact: a compact EDN result naming
            its revision, fixture, build, host, sampling policy, baseline
            and distribution."
    (let [record (bench/run-workload (a-workload {})
                                     {:revision "cafebabe"
                                      :build {:optimizations :advanced :instrumentation? false}
                                      :host  {:runtime "a pinned worker" :hardware-class :release-worker}})]
      (is (= :test/tiny (:benchmark record)))
      (is (= "cafebabe" (:revision record)))
      (is (= {:rows 3} (:fixture record)))
      (is (= {:kind :before-vs-after :reference {:revision "deadbeef"}} (:baseline record)))
      (is (= {:warmup 1 :samples 3} (:sampling record)))
      (is (= :evidence (:status record)))
      (testing "the distributions are published, and carry no verdict"
        (is (= #{:test/self-ms :bench/iteration-ms} (set (keys (:distribution record)))))
        (doseq [[_ d] (:distribution record)]
          (is (= 3 (:n d)))
          (is (not (contains? d :pass?)))))
      (testing "the deterministic properties carry the verdicts"
        (is (= {:doc "the row count" :expected 3 :observed 3 :pass? true}
               (get-in record [:properties :test/rows])))
        (is (empty? (:gate-failures record)))))))

(deftest a-declared-measurement-must-be-observed-every-iteration
  (testing "otherwise the distribution is over a different thing each
            time it is sampled."
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (bench/run-workload (a-workload {:run (fn [_] {:test/rows 3})}))))))

;; ---------------------------------------------------------------------------
;; Where an exit code comes from
;; ---------------------------------------------------------------------------

(deftest the-exit-code-has-exactly-one-source
  (testing "a run over a workload whose only measurements are
            distributions cannot produce a non-zero exit code, because
            there is no gate to violate."
    (let [evidence-only (a-workload {:id :test/evidence-only
                                     :measurements [{:id :test/self-ms
                                                     :doc "self time"
                                                     :observable :duration-ms}]
                                     :run (fn [_] {:test/self-ms 999999.0})})
          {:keys [exit-code gate-failures results]} (bench/run [evidence-only])]
      (is (= 0 exit-code))
      (is (empty? gate-failures))
      (is (empty? (:properties (first results))))
      (is (= 999999.0 (get-in (first results) [:distribution :test/self-ms :p50]))))))

(deftest a-run-over-several-workloads-concatenates-their-verdicts
  (testing "one red workload reds the run; the others still publish."
    (let [red (a-workload {:id :test/red
                           :measurements [{:id :test/rows :doc "the row count"
                                           :observable :count :expect 99}
                                          {:id :test/self-ms :doc "self time"
                                           :observable :duration-ms}]})
          {:keys [exit-code gate-failures results]} (bench/run [(a-workload {}) red])]
      (is (= 1 exit-code))
      (is (= 1 (count gate-failures)))
      (is (= :test/rows (:measurement (first gate-failures))))
      (is (= 2 (count results)))
      (is (every? #(seq (:distribution %)) results)))))
