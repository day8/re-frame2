(ns re-frame.freehand.bench.b2-cljs-test
  "B2's DETERMINISTIC half, run on both hosts.

  B2's whole correctness claim is a pair of exact counts: the
  capability-free arm omits every ViewCell it mounts, and the three-read
  arm omits none. Those are properties — a static function of each
  declaration's analyzed sites — they reproduce on any host, and a
  violation reds. This suite proves the counts are the written arithmetic,
  that the oracle behind them DISCRIMINATES (a reactive view is not
  counted as omitted), and that the workloads run green and publish the
  decomposition D021 asks B2 to publish.

  The half this suite does NOT touch: how big any timing is. The only
  distribution B2 publishes is the harness's own iteration clock, and this
  file asserts it was published, never that it was fast — that is the
  half D021 forbids gating on, mechanised in the harness itself
  ([[re-frame.freehand.bench.measure]]) rather than restated here."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b2 :as b2]
            [re-frame.freehand.bench.measure :as m]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

;; ---------------------------------------------------------------------------
;; Each arm earns its verdict
;; ---------------------------------------------------------------------------

(deftest b2-the-free-arm-omits-every-view-cell
  (testing "Every capability-free declaration is proven elidable by its
            manifest — no reactive site, so the ViewCell shell is omitted.
            The count is a fact about these declarations before it is a
            total over a mount."
    (doseq [view @#'b2/free-views]
      (let [mm (v/manifest view)]
        (is (= :elided (:view-cell mm))
            (str (:view-id mm) " omits the ViewCell shell"))
        (is (false? (:reactive? mm))
            (str (:view-id mm) " carries no reactive site"))))))

(deftest b2-the-three-read-arm-retains-every-view-cell
  (testing "Every three-read declaration observes context and keeps its
            ViewCell — three reactive reads each, so the arm is the
            discrimination that a zero omitted count is a real absence and
            not a broken oracle."
    (doseq [view @#'b2/read-views]
      (let [mm (v/manifest view)]
        (is (= :present (:view-cell mm))
            (str (:view-id mm) " retains the ViewCell shell"))
        (is (true? (:reactive? mm))
            (str (:view-id mm) " carries a reactive site"))
        (is (= 3 (+ (count (:subscriptions mm)) (count (:events mm))))
            (str (:view-id mm) " carries three reactive reads"))))))

;; ---------------------------------------------------------------------------
;; The count is the written arithmetic, and it discriminates
;; ---------------------------------------------------------------------------

(deftest b2-the-omitted-count-is-the-written-arithmetic
  (testing "The free arm's omitted count is the one its roster PREDICTS at
            every scale — `(count free-views) × scale` — not whatever the
            walk happened to produce, and the read arm's is exactly zero."
    (doseq [scale [0 1 2 650]]
      (let [{:keys [B2/free-arm-omitted-cells B2/read-arm-omitted-cells]}
            (b2/observe (:fixture (b2/workload {:id       :B2/probe
                                                :doc      "arithmetic probe"
                                                :scale    scale
                                                :sampling {:warmup 0 :samples 1}})))]
        (is (= (b2/expected-omitted scale) free-arm-omitted-cells)
            (str "scale " scale ": the free arm omits the predicted count"))
        (is (= 0 read-arm-omitted-cells)
            (str "scale " scale ": the three-read arm omits none"))))))

(deftest b2-the-count-would-notice-a-view-that-stopped-eliding
  (testing "A count that could only ever read its whole arm is vacuous.
            The oracle reads each type's manifest, so a plan mixing an
            elided and a retained view counts only the elided one's mounts
            — which is exactly what would happen if a free-arm type
            regressed into keeping its shell."
    (let [omitted #'b2/omitted
          elided  (first @#'b2/free-views)
          present (first @#'b2/read-views)]
      (is (= :elided (:view-cell (v/manifest elided))))
      (is (= :present (:view-cell (v/manifest present))))
      (is (= 100 (omitted [[elided 100] [present 100]]))
          "only the elided view's mounts are counted — the present one drops out")
      (is (= 0 (omitted [[present 100]]))
          "an all-reactive plan omits nothing")
      (is (= 200 (omitted [[elided 100] [elided 100]]))
          "and an all-inert plan omits every mount"))))

;; ---------------------------------------------------------------------------
;; The registered workloads
;; ---------------------------------------------------------------------------

(deftest b2-registers-the-mass-mount-and-the-small-case
  (testing "D021 requires a small realistic case beside the stress case.
            Both are registered into the standing evidence suite, both run
            one roster, and the storm omits a cells-shaped number of
            shells under the baseline D021 names for B2."
    (let [registered (bench/registered)]
      (doseq [id [:B2/mass-mount :B2/small-mount]]
        (is (contains? registered id) (str id " is in the standing evidence suite"))))
    (let [stress (first (filter #(= :B2/mass-mount (:id %)) b2/workloads))
          small  (first (filter #(= :B2/small-mount (:id %)) b2/workloads))]
      (is (<= 1000 (get-in stress [:fixture :free-arm :omitted-view-cells]))
          "the storm omits a cells-shaped number of ViewCell shells")
      (is (< (get-in small [:fixture :free-arm :omitted-view-cells]) 100)
          "and the small case is genuinely small")
      (is (= :interpreted-vs-compiled (:kind (:baseline stress)))
          "under the baseline D021 names for B2"))))

(deftest b2-runs-green-and-publishes-its-decomposition
  (testing "Each registered workload runs, its two deterministic counts
            hold, and the mount decomposition and retained-object counts
            ride the record's fixture with full provenance — every one a
            per-declaration static fact behind the gated totals."
    (doseq [w b2/workloads]
      (let [record (bench/run-workload w provenance)
            label  (str (:id w) ": ")
            free   (get-in record [:fixture :free-arm])
            read   (get-in record [:fixture :read-arm])]
        (is (empty? (:gate-failures record)) (str label "no deterministic property was violated"))
        (is (= (:omitted-view-cells free)
               (get-in record [:properties :B2/free-arm-omitted-cells :observed]))
            (str label "the gated free-arm count matches the published decomposition"))
        (is (= 0 (get-in record [:properties :B2/read-arm-omitted-cells :observed]))
            (str label "the three-read arm omits none"))
        (testing "the decomposition names every declaration and its retained shells"
          (is (= 0 (:retained-view-cells free))
              (str label "the free arm retains no ViewCell"))
          (is (= (:boundaries read) (:retained-view-cells read))
              (str label "the three-read arm retains every ViewCell it mounts"))
          (doseq [line (concat (:decomposition free) (:decomposition read))]
            (is (contains? #{:elided :present} (:view-cell line))
                (str label (:view-id line) " carries a decided verdict"))
            (is (set? (:capabilities line))
                (str label (:view-id line) " publishes its capability roster"))))
        (testing "and the harness's own reading is published as evidence, with no verdict"
          (let [d (get-in record [:distribution :bench/iteration-ms])]
            (is (some? d) (str label "iteration-ms was published"))
            (is (= (:samples (:sampling w)) (:n d))
                (str label "it summarises every measured iteration"))
            (is (not (contains? d :pass?))
                (str label "it carries no verdict — it is evidence"))))))))

(deftest b2-carries-the-provenance-a-published-result-needs
  (testing "A count without its environment is not release evidence. The
            record names its revision, fixture parameters, build mode,
            runtime and hardware class — the last DETECTED, never a literal
            naming one machine."
    (let [record (bench/run-workload (first b2/workloads) provenance)]
      (is (= "test-fixture-revision" (:revision record)))
      (is (= (:scale (:fixture (first b2/workloads))) (:scale (:fixture record)))
          "the scale parameter rides the record — mount counts are parameters, not constants")
      (is (keyword? (get-in record [:build :optimizations])))
      (is (boolean? (get-in record [:build :instrumentation?])))
      (is (seq (get-in record [:host :runtime])))
      (is (keyword? (get-in record [:host :hardware-class])))
      (is (= :evidence (:status record))))))

;; ---------------------------------------------------------------------------
;; The counts gate; the timing cannot
;; ---------------------------------------------------------------------------

(deftest b2-omitted-counts-gate-and-nothing-else-can
  (testing "The asymmetry, read off B2's own declarations: both omitted
            counts class as gates, and the only distribution it publishes
            — the harness clock — classes as evidence. A future edit that
            reached for a budget on that timing would be refused when the
            workload is CONSTRUCTED, not here."
    (let [by-id (into {} (map (juxt :id identity))
                      (:measurements (bench/workload (first b2/workloads))))]
      (doseq [mid [:B2/free-arm-omitted-cells :B2/read-arm-omitted-cells]]
        (is (m/gate? (get by-id mid)) (str mid " gates")))
      (is (m/evidence? (get by-id :bench/iteration-ms)) ":bench/iteration-ms is evidence"))))
