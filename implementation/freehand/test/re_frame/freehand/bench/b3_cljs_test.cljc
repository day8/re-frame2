(ns re-frame.freehand.bench.b3-cljs-test
  "B3's DETERMINISTIC half, run on both hosts.

  B3's correctness claim is four exact integers and one flag: ten thousand
  records exist, a few hundred rows are compared, an exact number commits
  and an exact number is skipped, and the two comparators name the same
  rows. This suite proves those are the WRITTEN arithmetic — spelled out
  step by step below, independently of the arithmetic function the
  workload uses — that the isolation the counts claim is real (a mutation
  outside the window commits nothing), that the comparators DISCRIMINATE
  (they notice a row that changed), and that the workloads run green and
  publish the distributions D021 asks B3 for.

  The half this suite does NOT touch: how big any timing is. B3 publishes
  four durations and this file asserts they were published, never that any
  was fast. Whether comparator specialization pays is a judgement made
  from those numbers by a person, which is why nothing here compares
  them."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b3 :as b3]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.tree :as tree]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

(def ^:private stress
  {:total 10000 :window 40})

(def ^:private small
  {:total 400 :window 12})

;; ---------------------------------------------------------------------------
;; The counts are the written arithmetic — written HERE, step by step
;; ---------------------------------------------------------------------------

(def ^:private stress-steps
  "The stress script's nine steps, with the counts each one produces,
  worked out by hand from a window of forty over ten thousand records.

  This table is the anchor under every count B3 gates. The workload
  derives its expectation from [[re-frame.freehand.bench.b3/expectation]],
  which walks the script; if that function and this table ever disagree,
  one of them is wrong and the suite says so — which is the only way a
  written expectation stays written rather than becoming a second copy of
  the code."
  [{:step :idle              :compared 40 :committed 0  :skipped 40}
   {:step :edit-row-0        :compared 40 :committed 1  :skipped 39}
   {:step :edit-row-9999     :compared 40 :committed 0  :skipped 40}
   {:step :scroll-to-20      :compared 20 :committed 20 :skipped 20}
   {:step :edit-row-20       :compared 40 :committed 1  :skipped 39}
   {:step :scroll-to-0       :compared 20 :committed 20 :skipped 20}
   {:step :scroll-to-160     :compared 0  :committed 40 :skipped 0}
   {:step :edit-row-160      :compared 40 :committed 1  :skipped 39}
   {:step :idle              :compared 40 :committed 0  :skipped 40}])

(defn- total-of [k] (reduce + 0 (map k stress-steps)))

(deftest b3-the-expectation-is-the-hand-written-arithmetic
  (testing "The workload's expectation function answers what the script
            means, step by step. Every step's committed and skipped counts
            sum to the window, because every visible row either commits or
            is skipped — there is no third outcome and no row unaccounted
            for."
    (doseq [{:keys [step compared committed skipped]} stress-steps]
      (is (= 40 (+ committed skipped))
          (str step ": every visible row either commits or is skipped"))
      (is (<= compared 40)
          (str step ": the comparison never touches more than the window")))
    (let [e (b3/expectation stress)]
      (is (= (total-of :compared) (:compared e)) "comparisons")
      (is (= (total-of :committed) (:committed e)) "committed rows")
      (is (= (total-of :skipped) (:skipped e)) "skipped rows")
      (is (= (* 40 (inc (count stress-steps))) (:rendered e))
          "rows rendered — the initial window plus one window per step"))))

(deftest b3-the-run-produces-exactly-those-counts
  (testing "And the measured run — real records, real props, the real `rf=`
            comparator — answers the same integers the arithmetic
            predicted. That agreement is the gate; the arithmetic knows
            nothing about records or `rf=`, and the run knows nothing about
            the arithmetic."
    (doseq [fixture [stress small]]
      (let [e     (b3/expectation fixture)
            obs   (b3/observe (b3/records (:total fixture)) fixture)
            label (str (:total fixture) "/" (:window fixture) ": ")]
        (is (= (:total fixture) (:B3/records obs)) (str label "records"))
        (is (= (:rendered e) (:B3/rows-rendered obs)) (str label "rows rendered"))
        (is (= (:compared e) (:B3/rows-compared obs)) (str label "rows compared"))
        (is (= (:committed e) (:B3/committed-rows obs)) (str label "rows committed"))
        (is (= (:skipped e) (:B3/skipped-rows obs)) (str label "rows skipped"))
        (is (true? (:B3/comparator-agreement obs)) (str label "the comparators agreed"))))))

;; ---------------------------------------------------------------------------
;; The comparison isolates rows
;; ---------------------------------------------------------------------------

(deftest b3-the-comparison-touches-the-window-and-not-the-collection
  (testing "The isolation claim as two integers side by side: ten thousand
            records exist, and the whole scripted sequence performs a few
            hundred comparisons over them. Windowing is what makes the
            second number a function of the window rather than of the
            dataset — so the same script over twenty-five times fewer
            records compares only as many rows as its own window holds."
    (let [big   (b3/expectation stress)
          tiny  (b3/expectation small)]
      (is (< (:compared big) (:total stress))
          "the stress case compares far fewer rows than it holds records")
      (is (<= (:compared big) (* 40 (count stress-steps)))
          "and never more than one window per step")
      (is (= (:compared tiny) (* (/ (:compared big) 40) 12))
          "the comparison count scales with the WINDOW, not with the dataset"))))

(deftest b3-a-mutation-outside-the-window-commits-nothing
  (testing "The sharpest form of the claim, isolated from the script: edit
            a record the window does not show, and the next render's rows
            are identical — nothing changed, nothing commits. This is what
            a table of ten thousand rows buys."
    (let [recs   (b3/records 10000)
          window 40
          before (b3/window-rows recs 0 window)
          after  (b3/window-rows (update-in recs [9999 :amount] inc) 0 window)
          index  (into {} (map (juxt :id identity)) before)]
      (is (= before after) "the window did not move and its rows did not change")
      (is (= #{} (b3/changed-keys b3/generated-equal? index after))
          "so the generated comparator names no row changed")
      (is (= #{} (b3/changed-keys b3/generic-equal? index after))
          "and neither does the generic one"))))

(deftest b3-the-comparison-would-notice-a-row-that-changed
  (testing "The discrimination behind that zero. A comparator that always
            answered `equal` would produce the same empty set above, so the
            same two comparators are shown naming exactly the row that DID
            change — and naming only it."
    (let [recs   (b3/records 10000)
          window 40
          before (b3/window-rows recs 0 window)
          index  (into {} (map (juxt :id identity)) before)]
      (doseq [row [0 17 39]]
        (let [after (b3/window-rows (update-in recs [row :amount] inc) 0 window)
              id    (:id (nth recs row))]
          (is (= #{id} (b3/changed-keys b3/generated-equal? index after))
              (str "row " row ": the generated comparator names exactly it"))
          (is (= #{id} (b3/changed-keys b3/generic-equal? index after))
              (str "row " row ": and so does the generic one")))))))

(deftest b3-a-scroll-keeps-the-rows-it-still-shows
  (testing "Matching by KEY is what makes a scroll cheap, and it is checked
            as an identity rather than as a count: slide the window by half
            its height and the rows it still shows are the same rows, so
            neither comparator names any of them changed. Matching by
            POSITION would name every one."
    (let [recs   (b3/records 10000)
          window 40
          before (b3/window-rows recs 0 window)
          after  (b3/window-rows recs 20 window)
          index  (into {} (map (juxt :id identity)) before)]
      (is (= 20 (count (filter #(contains? index (:id %)) after)))
          "half the new window is rows the old one also showed")
      (is (= #{} (b3/changed-keys b3/generated-equal? index after))
          "and none of them changed")
      (is (= (mapv :id (subvec before 20)) (mapv :id (subvec after 0 20)))
          "they are the same rows, in order, addressed by key"))))

;; ---------------------------------------------------------------------------
;; The parent renders the window
;; ---------------------------------------------------------------------------

(deftest b3-the-parent-renders-the-window-not-the-collection
  (testing "Counted off the rendered tree rather than off the props handed
            in: one row element per visible row, whatever the dataset
            behind it is."
    (doseq [{:keys [total window]} [stress small {:total 10000 :window 1}]]
      (let [rows (b3/window-rows (b3/records total) 0 window)
            t    (tree/render [b3/windowed-table {:rows rows}])]
        (is (= window (b3/rendered-rows t))
            (str total "/" window ": the render built one row element per visible row"))))))

;; ---------------------------------------------------------------------------
;; The registered workloads
;; ---------------------------------------------------------------------------

(deftest b3-registers-the-stress-case-and-the-small-case
  (testing "D021 requires a small realistic case beside the stress case.
            Both are registered into the standing evidence suite, both run
            one script shape, and the stress case is the ten thousand keyed
            records D021 names for B3 under the baseline it names."
    (let [registered (bench/registered)]
      (doseq [id [:B3/windowed-ledger :B3/small-windowed-ledger]]
        (is (contains? registered id) (str id " is in the standing evidence suite"))))
    (let [big (first (filter #(= :B3/windowed-ledger (:id %)) b3/workloads))
          sm  (first (filter #(= :B3/small-windowed-ledger (:id %)) b3/workloads))]
      (is (= 10000 (get-in big [:fixture :total])) "ten thousand keyed records")
      (is (= 40 (get-in big [:fixture :window])) "through a window of about forty")
      (is (< (get-in sm [:fixture :total]) 1000) "and the small case is genuinely small")
      (is (= :interpreted-vs-compiled (:kind (:baseline big)))
          "under the baseline D021 names for B3"))))

(deftest b3-runs-green-and-publishes-its-evidence
  (testing "Each registered workload runs, its six deterministic properties
            hold, and every duration it declared is published as a
            distribution carrying no verdict."
    (doseq [w b3/workloads]
      (let [record (bench/run-workload w provenance)
            label  (str (:id w) ": ")]
        (is (empty? (:gate-failures record)) (str label "no deterministic property was violated"))
        (doseq [mid [:B3/records :B3/rows-rendered :B3/rows-compared
                     :B3/committed-rows :B3/skipped-rows :B3/comparator-agreement]]
          (is (true? (get-in record [:properties mid :pass?]))
              (str label mid " held"))
          (is (= (get-in record [:properties mid :expected])
                 (get-in record [:properties mid :observed]))
              (str label mid " observed exactly what the arithmetic expected")))
        (is (= (get-in record [:fixture :total])
               (get-in record [:properties :B3/records :observed]))
            (str label "the gated record count is the fixture's dataset size"))
        (doseq [mid [:B3/generated-comparator-ms :B3/generic-comparator-ms
                     :B3/parent-render-ms :B3/end-to-end-ms :bench/iteration-ms]]
          (let [d (get-in record [:distribution mid])]
            (is (some? d) (str label mid " was published"))
            (is (= (:samples (:sampling w)) (:n d))
                (str label mid " summarises every measured iteration"))
            (is (not (contains? d :pass?))
                (str label mid " carries no verdict — it is evidence"))))))))

(deftest b3-carries-the-provenance-a-published-result-needs
  (testing "A count without its environment is not release evidence. The
            record names its revision, its fixture parameters — including
            the script the counts are counts OF — its build mode, runtime
            and hardware class."
    (let [w      (first b3/workloads)
          record (bench/run-workload w provenance)]
      (is (= "test-fixture-revision" (:revision record)))
      (is (= 10000 (get-in record [:fixture :total])))
      (is (= 40 (get-in record [:fixture :window])))
      (is (= 9 (count (get-in record [:fixture :script])))
          "the scripted mutation sequence rides the record")
      (is (keyword? (get-in record [:build :optimizations])))
      (is (boolean? (get-in record [:build :instrumentation?])))
      (is (seq (get-in record [:host :runtime])))
      (is (keyword? (get-in record [:host :hardware-class])))
      (is (= :evidence (:status record))))))

;; ---------------------------------------------------------------------------
;; The counts gate; the timings cannot
;; ---------------------------------------------------------------------------

(deftest b3-counts-gate-and-no-duration-can
  (testing "The asymmetry, read off B3's own declarations. Every count and
            the agreement flag class as gates; every duration classes as
            evidence. A future edit reaching for a latency budget would be
            refused when the workload is CONSTRUCTED, not here — which is
            why this file compares no milliseconds."
    (let [by-id (into {} (map (juxt :id identity))
                      (:measurements (bench/workload (first b3/workloads))))]
      (doseq [mid [:B3/records :B3/rows-rendered :B3/rows-compared
                   :B3/committed-rows :B3/skipped-rows :B3/comparator-agreement]]
        (is (m/gate? (get by-id mid)) (str mid " gates")))
      (doseq [mid [:B3/generated-comparator-ms :B3/generic-comparator-ms
                   :B3/parent-render-ms :B3/end-to-end-ms :bench/iteration-ms]]
        (is (m/evidence? (get by-id mid)) (str mid " is evidence"))))))
