(ns re-frame.freehand.bench.b1-cljs-test
  "B1's DETERMINISTIC half, run on both hosts.

  D021 splits B1 in two and this suite is only ever the first half. The
  gate is EQUAL OUTPUT: the interpreted and compiled arms render the same
  finite template to the same structural tree, and the node count is the
  exact number the template's shape predicts. Those are properties, they
  reproduce on any host, and a violation reds.

  The other half — p50/p95 self time, per-node cost, allocation — is
  published evidence with no threshold and no comparator, and this suite
  deliberately asserts NOTHING about how large any of those numbers are.
  It asserts only that they were observed and published, because a
  workload that quietly stopped emitting a distribution would otherwise
  look exactly like a workload whose distribution was fine. The
  gate/evidence split is mechanised in the harness itself
  (`re-frame.freehand.bench.measure`), so this suite reads it off the
  workload rather than restating it: a timing declared as a gate would be
  refused at construction, before this file could have an opinion.

  The workloads under test are the REGISTERED ones — the same values
  `clojure -M:bench` publishes — at their declared sampling policy, not a
  cheaper look-alike."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b1 :as b1]
            [re-frame.freehand.bench.b1.compiled :as compiled]
            [re-frame.freehand.bench.b1.interpreted :as interpreted]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.tree :as tree]
            [re-frame.freehand :as v]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

;; ---------------------------------------------------------------------------
;; The gate: equal output
;; ---------------------------------------------------------------------------

(deftest b1-the-two-arms-render-the-same-tree
  (testing "B1's deterministic gate, stated directly on the trees before
            it is stated through the harness: one template, two lowerings,
            one structural value. The ONLY normalisation is the view id,
            which names the namespace a declaration lives in — everything
            else (tags, attributes, keys, children, text, order) is
            compared verbatim."
    (doseq [rows [0 1 6 250]]
      (let [i (tree/render [interpreted/template {:rows rows}])
            c (tree/render [compiled/template {:rows rows}])]
        (is (= (b1/mode-neutral i) (b1/mode-neutral c))
            (str rows " rows: the interpreted and compiled trees are equal"))
        (is (= (b1/expected-nodes rows) (count (tree-seq map? :children i)))
            (str rows " rows: the node count is the one the shape predicts"))))))

(deftest b1-the-equality-would-notice-a-difference
  (testing "An equality that cannot fail proves nothing. The two arms are
            genuinely the two modes, and the comparison genuinely
            discriminates — a tree from a DIFFERENT size is rejected by
            the same equality the gate trusts."
    (is (= :interpreted (:lowering (v/describe interpreted/template)))
        "the interpreted arm is declared interpreted")
    (is (= :compiled (:lowering (v/describe compiled/template)))
        "the compiled arm is declared compiled")
    (is (nil? (descriptor/render-body compiled/template))
        "the compiled arm has no interpreted body to fall back to")
    (is (not= (b1/mode-neutral (tree/render [interpreted/template {:rows 6}]))
              (b1/mode-neutral (tree/render [compiled/template {:rows 7}])))
        "the comparison discriminates")))

;; ---------------------------------------------------------------------------
;; The registered workloads
;; ---------------------------------------------------------------------------

(deftest b1-registers-the-stress-case-and-the-small-case
  (testing "D021 requires a small realistic case beside the stress case,
            so optimization is not tuned only to synthetic extremes. Both
            are registered, both run one template, and the stress case is
            in the 10^3-10^4 band the decision names."
    (let [registered (bench/registered)]
      (doseq [id [:B1/finite-template :B1/small-template]]
        (is (contains? registered id) (str id " is in the standing evidence suite"))))
    (let [stress (first (filter #(= :B1/finite-template (:id %)) b1/workloads))
          small  (first (filter #(= :B1/small-template (:id %)) b1/workloads))]
      (is (<= 1000 (:nodes (:fixture stress)) 10000)
          "the stress case renders a 10^3-10^4-node template")
      (is (< (:nodes (:fixture small)) 100)
          "and the small case is genuinely small")
      (is (= :interpreted-vs-compiled (:kind (:baseline stress)))
          "under the baseline D021 names for B1"))))

(deftest b1-runs-green-and-publishes-its-evidence
  (testing "Each registered workload runs, its deterministic properties
            hold, and every evidence measurement arrives with a full
            distribution. No assertion here is about how BIG a number is —
            that is the half D021 forbids gating on."
    (doseq [w b1/workloads]
      (let [record (bench/run-workload w provenance)
            label  (str (:id w) ": ")]
        (is (empty? (:gate-failures record)) (str label "no deterministic property was violated"))
        (is (true? (get-in record [:properties :B1/structural-parity :pass?]))
            (str label "the arms produced equal output"))
        (is (= (:nodes (:fixture w)) (get-in record [:properties :B1/node-count :observed]))
            (str label "over the predicted node count"))
        (testing "and the required evidence is published, with its distribution"
          (doseq [mid [:B1/interpreted-self-ms :B1/compiled-self-ms
                       :B1/interpreted-per-node-ms :B1/compiled-per-node-ms
                       :B1/interpreted-allocated-bytes :B1/compiled-allocated-bytes
                       :bench/iteration-ms]]
            (let [d (get-in record [:distribution mid])]
              (is (some? d) (str label mid " was published"))
              (is (= (:samples (:sampling w)) (:n d))
                  (str label mid " summarises every measured iteration"))
              (doseq [k [:p50 :p95 :p99 :min :max :mean]]
                (is (number? (get d k)) (str label mid " carries " k)))
              (is (not (contains? d :pass?))
                  (str label mid " carries no verdict — it is evidence")))))))))

(deftest b1-carries-the-provenance-a-published-result-needs
  (testing "D021: a median without its distribution and environment is not
            release evidence. The record names its revision, fixture
            parameters, build mode, runtime and hardware class — the last
            DETECTED or configured, never a literal naming one machine."
    (let [w      (first b1/workloads)
          record (bench/run-workload w provenance)]
      (is (= "test-fixture-revision" (:revision record)))
      (is (= (:fixture w) (:fixture record))
          "the fixture parameters ride the record — sizes are parameters, not constants")
      (is (keyword? (get-in record [:build :optimizations])))
      (is (boolean? (get-in record [:build :instrumentation?])))
      (is (seq (get-in record [:host :runtime])))
      (testing "the host block is DETECTED — the test overrides only the
                revision, so the runtime and the hardware class in the
                record are the ones this run actually observed"
        (is (keyword? (get-in record [:host :hardware-class])))
        (is (not (contains? provenance :host))))
      (is (= :evidence (:status record))))))

;; ---------------------------------------------------------------------------
;; The timing half cannot red the run
;; ---------------------------------------------------------------------------

(deftest b1-timings-cannot-gate
  (testing "The asymmetry, read off B1's own declarations rather than
            asserted about them: every duration and byte measurement it
            declares classes as evidence, and the two deterministic ones
            class as gates. A future edit that reached for a budget on a
            timing would be refused when the workload is CONSTRUCTED, not
            here."
    (let [by-id (into {} (map (juxt :id identity))
                     (:measurements (bench/workload (first b1/workloads))))]
      (doseq [mid [:B1/interpreted-self-ms :B1/compiled-self-ms
                   :B1/interpreted-per-node-ms :B1/compiled-per-node-ms
                   :B1/interpreted-allocated-bytes :B1/compiled-allocated-bytes]]
        (is (m/evidence? (get by-id mid)) (str mid " is evidence")))
      (doseq [mid [:B1/node-count :B1/structural-parity]]
        (is (m/gate? (get by-id mid)) (str mid " gates"))))))
