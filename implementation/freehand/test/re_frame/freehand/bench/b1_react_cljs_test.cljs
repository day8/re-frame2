(ns re-frame.freehand.bench.b1-react-cljs-test
  "B1's React arm — the instrument, checked as an instrument.

  A measurement that only ever runs green tells you nothing about the
  thing it measures, so the three claims here are the three an instrument
  has to answer before anyone may cite it:

    - it SETTLES — the counters answer one value across repeated runs of
      an unchanged tree;
    - it DISCRIMINATES — they move when the tree changes, and the two of
      them move independently, so neither is a restatement of the other;
    - it cannot QUIETLY DEGRADE — a counter that stopped settling reds
      the run, and the wall clock beside it cannot red anything however
      far it moves.

  The third is checked through the harness rather than restated: the
  drift control below hands `bench/run` a workload whose count changes
  per iteration and requires exit 1 naming the property, and the
  gate/evidence split is read off the normalised workload rather than
  asserted about it. A timing filed as a gate would be refused when the
  workload is CONSTRUCTED, before this file could have an opinion."
  (:require ["react" :as react]
            [cljs.test :refer [deftest is testing]]
            [re-frame.freehand.bench :as bench]
            [re-frame.freehand.bench.b1-react :as b1r]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.react :as emitter]))

(def ^:private provenance
  "The revision a TEST supplies. A ClojureScript test process is not a
  build pipeline: no environment variable, no git, and nothing it could
  truthfully detect. The published run detects its own."
  {:revision "test-fixture-revision"})

(def ^:private stress
  (first (filter #(= :B1/react-template (:id %)) b1r/workloads)))

(def ^:private small
  (first (filter #(= :B1/react-small-template (:id %)) b1r/workloads)))

(def ^:private sweep
  "The sizes the counters are checked over. Both claims below are about
  the template at ANY size, so both read this one roster rather than each
  picking a pair."
  [0 1 2 6 40 400])

(defn- measured
  "The counts actually read off an emit of the template at `rows` — the
  measured value, never the prediction."
  [rows]
  (b1r/counts (emitter/element (b1r/template rows))))

;; ---------------------------------------------------------------------------
;; It settles
;; ---------------------------------------------------------------------------

(def ^:private repeats
  "How many times the unchanged tree is rendered when the settling claim
  is made directly. A fixture parameter: it is the number of independent
  observations behind the sentence \"identical across repeated runs\", and
  a reader should be able to see it rather than infer it."
  25)

(deftest react-arm-counts-settle-across-repeated-runs
  (testing "The claim the whole arm rests on, demonstrated rather than
            asserted: the same template rendered many times over answers
            ONE element count and ONE prop count. A counter that drifted
            with heap state, iteration order or the scheduler could not
            attribute anything, which is the exact complaint against the
            heap reading published beside it."
    (doseq [w [stress small]]
      (let [obs (vec (repeatedly repeats #(b1r/observe (:fixture w))))
            label (str (:id w) ": ")]
        (doseq [mid [:B1/react-elements :B1/react-props]]
          (is (= 1 (count (distinct (map mid obs))))
              (str label mid " answered one value across " repeats " renders — got "
                   (pr-str (distinct (map mid obs))))))))))

(deftest react-arm-counts-are-the-written-arithmetic
  (testing "And the value they settle on is the one the template's shape
            PREDICTS, at every size — not whatever the walk happened to
            produce. A gate that expects what it saw is not a gate, so the
            expectation is arithmetic over the fixture parameter and this
            row is where the arithmetic is checked against the walk."
    (doseq [rows sweep]
      (let [{:keys [elements props]} (measured rows)]
        (is (= (b1r/expected-elements rows) elements)
            (str rows " rows: the element count is the one the shape predicts"))
        (is (= (b1r/expected-props rows) props)
            (str rows " rows: the prop count is the one the shape predicts"))))))

;; ---------------------------------------------------------------------------
;; It discriminates
;; ---------------------------------------------------------------------------

(deftest react-arm-counts-move-with-the-tree
  (testing "A green reading is vacuous if the counter could never have
            read anything else. Both counters rise strictly with the
            workload, so a change that built fewer elements — or the same
            elements with fewer props — is a change either of them would
            show."
    (let [counts (mapv measured sweep)
          els    (mapv :elements counts)
          props  (mapv :props counts)]
      (is (= els (vec (sort els))) "the element count rises with the workload")
      (is (apply distinct? els) "and never repeats a value across the sweep")
      (is (= props (vec (sort props))) "the prop count rises with the workload")
      (is (apply distinct? props) "and never repeats a value across the sweep"))
    (testing "the two workloads registered over one template therefore
              gate against DIFFERENT written expectations, which is what
              makes the discrimination structural: a counter that stopped
              moving with the workload would fail one of them"
      (is (not= (:elements (:fixture stress)) (:elements (:fixture small))))
      (is (not= (:props (:fixture stress)) (:props (:fixture small)))))))

(deftest react-arm-the-two-counters-are-independent
  (testing "Props are not a restatement of elements. One pair of trees
            holds the element count fixed and moves the props; the other
            holds the props at zero and moves the elements. If either
            counter were derived from the other, one of these pairs could
            not exist."
    (let [bare    (b1r/counts (emitter/element [:div [:span]]))
          dressed (b1r/counts (emitter/element [:div.x [:span.y {:id "z"}]]))
          wider   (b1r/counts (emitter/element [:div [:span] [:span]]))]
      (is (= (:elements bare) (:elements dressed))
          "same elements")
      (is (< (:props bare) (:props dressed))
          "and the prop counter alone moved")
      (is (= (:props bare) (:props wider))
          "same props")
      (is (< (:elements bare) (:elements wider))
          "and the element counter alone moved"))))

(deftest react-arm-counts-what-the-emitter-answered
  (testing "The count is read off the ordinary return value, so it is a
            claim about React elements rather than about a shape this file
            invented. React's own predicate agrees with the walk: the
            emitted root is an element, its element children are elements,
            and its text children are not."
    (let [el (emitter/element (b1r/template 1))]
      (is (react/isValidElement el) "the emitter answered a React element")
      (is (= 9 (:elements (b1r/counts el)))
          "one row over the six-element skeleton")
      (is (not (react/isValidElement "row "))
          "text is a child but not an element, and is not counted as one"))))

;; ---------------------------------------------------------------------------
;; It runs through the harness, and the harness decides what may gate
;; ---------------------------------------------------------------------------

(deftest react-arm-registers-the-stress-case-and-the-small-case
  (testing "D021 requires a small realistic case beside the stress case.
            Both are registered into the standing evidence suite, both run
            one template, and the stress case is in the band the decision
            names for B1."
    (let [registered (bench/registered)]
      (doseq [id [:B1/react-template :B1/react-small-template]]
        (is (contains? registered id) (str id " is in the standing evidence suite"))))
    (is (<= 1000 (:elements (:fixture stress)) 10000)
        "the stress case builds a 10^3-10^4-element template")
    (is (< (:elements (:fixture small)) 100)
        "and the small case is genuinely small")
    (is (= :before-vs-after (:kind (:baseline stress)))
        "under the baseline this instrument exists to serve")))

(deftest react-arm-runs-green-and-publishes-its-evidence
  (testing "Each registered workload runs, its deterministic properties
            hold at their declared sampling policy, and every evidence
            measurement arrives with a full distribution. Nothing here
            asserts how BIG a number is — that is the half D021 forbids
            gating on."
    (doseq [w b1r/workloads]
      (let [record (bench/run-workload w provenance)
            label  (str (:id w) ": ")]
        (is (empty? (:gate-failures record)) (str label "no deterministic property was violated"))
        (is (= (:elements (:fixture w)) (get-in record [:properties :B1/react-elements :observed]))
            (str label "over the predicted element count"))
        (is (= (:props (:fixture w)) (get-in record [:properties :B1/react-props :observed]))
            (str label "and the predicted prop count"))
        (testing "and the required evidence is published, with its distribution"
          (doseq [mid [:B1/react-self-ms :B1/react-per-element-ms
                       :B1/react-heap-delta-bytes :bench/iteration-ms]]
            (let [d (get-in record [:distribution mid])]
              (is (some? d) (str label mid " was published"))
              (is (= (:samples (:sampling w)) (:n d))
                  (str label mid " summarises every measured iteration"))
              (is (not (contains? d :pass?))
                  (str label mid " carries no verdict — it is evidence")))))))))

(deftest react-arm-timings-cannot-gate
  (testing "The asymmetry, read off the arm's own declarations rather than
            asserted about them: its two counts class as gates, and its
            durations and its heap reading class as evidence. A future
            edit that reached for a budget on the heap delta would be
            refused when the workload is CONSTRUCTED, not here."
    (let [by-id (into {} (map (juxt :id identity))
                     (:measurements (bench/workload stress)))]
      (doseq [mid [:B1/react-elements :B1/react-props]]
        (is (m/gate? (get by-id mid)) (str mid " gates")))
      (doseq [mid [:B1/react-self-ms :B1/react-per-element-ms :B1/react-heap-delta-bytes]]
        (is (m/evidence? (get by-id mid)) (str mid " is evidence"))))))

(deftest react-arm-a-counter-that-stopped-settling-would-red
  (testing "The settling claim is enforced by the HARNESS, not by the row
            above that demonstrates it. This control hands `bench/run` the
            same workload with a count that drifts by one per iteration:
            the run exits 1, names the property, and says how many values
            it saw. Without this, a counter that quietly began to drift
            would be caught only by whoever happened to read the numbers."
    (let [n       (volatile! 0)
          control (assoc (b1r/workload {:id       :B1/react-drift-control
                                        :doc      "a count that drifts by one per iteration"
                                        :rows     2
                                        :sampling {:warmup 0 :samples 4}})
                         :run
                         (fn [fixture]
                           (update (b1r/observe fixture) :B1/react-elements + (vswap! n inc))))
          outcome (bench/run [control] provenance)
          failure (first (:gate-failures outcome))]
      (is (= 1 (:exit-code outcome)) "a drifting deterministic property reds the run")
      (is (= :B1/react-elements (:measurement failure)) "and the failure names the property")
      (is (= :one-value-per-run (:expected failure))
          "as an instability, not merely as a wrong value")
      (is (re-find #"distinct values" (:message failure))
          "and the message says what it saw"))
    (testing "and the control stays out of the standing suite — it fails
              on purpose, and a suite that carried it would never be green"
      (is (not (contains? (bench/registered) :B1/react-drift-control))))))
