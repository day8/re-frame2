(ns re-frame.fresco.tool-snapshot-cljs-test
  "WHAT THE TOOL DOOR STATES, AGAINST WHAT REACT AND THE CELL TABLE HOLD.

  ## `:snapshot` is the number `getSnapshot` returns

  React stores an entry's `getSnapshot` result and compares it, and that
  closure sums the epochs along the entry's ordered read SEQUENCE — a
  query read twice counts twice. The tool's rows carry each read once, so
  a sum over a row's reads is a different number wherever a body repeats
  a read. The witness is [[re-frame.fresco.test.runtime/snapshot-of]],
  which calls the closure React holds.

  ## An attribution edge is a cell some boundary reads

  A cell whose last reader leaves stays in the table until the reaper's
  grace macrotask disposes it. Inside that window no boundary holds the
  key, so the attribution read leaves it out rather than listing a
  subscription with zero readers."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.evidence :as rf.fresco.evidence]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.fresco.tool :as rf.fresco.tool]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::tool-snapshot)

(rf/reg-sub :ts/left  (fn [db _] (:left db)))
(rf/reg-sub :ts/right (fn [db _] (:right db)))

(rf/reg-event :ts/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :ts/bump (fn [{:keys [db]} _] {:db (update db :left inc)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- seeded! []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:ts/seed {:left 1 :right 2}]))
  frame-id)

(defn- mount!
  "Render `body-fn` as a boundary and commit it through the seam React
  occupies. Answers `[entry cleanup]`: the read-set entry whose
  `getSnapshot` React holds, and the cleanup React would hold."
  [body-fn]
  (rf.fresco.impl.collector/render-body frame-id body-fn {})
  (let [entry (rf.fresco.impl.collector/last-reads)]
    [entry (rf.fresco.impl.collector/commit-boundary! entry (fn []))]))

(defn- reads-left-once [_]
  (rf.fresco/sub [:ts/left])
  nil)

(defn- reads-left-twice [_]
  (rf.fresco/sub [:ts/left])
  (rf.fresco/sub [:ts/left])
  nil)

(defn- left-epoch []
  (.-epoch ^js (get @rf.fresco.impl.collector/!cells [frame-id [:ts/left]])))

(defn- bump! []
  (rf/with-frame frame-id (rf/dispatch-sync [:ts/bump])))

(deftest a-query-read-twice-reports-the-snapshot-react-compares
  (seeded!)
  (let [[^js entry cleanup] (mount! reads-left-twice)]
    (bump!)
    (testing "the premise: one cell, read twice in order, with a non-zero epoch"
      (is (= 2 (alength (.-keys entry)))
          "the read sequence holds the query twice")
      (is (= 1 (count (rf.fresco.test.runtime/reads-of entry)))
          "and the read set holds it once")
      (is (pos? (left-epoch))
          "a zero epoch would make the twice-counted sum equal the once-counted one"))
    (let [why (first (:explanations (rf.fresco.tool/explain-render)))]
      (is (= (rf.fresco.test.runtime/snapshot-of entry) (:snapshot why))
          "`:snapshot` is the number `getSnapshot` returns")
      (is (= (* 2 (left-epoch)) (:snapshot why))
          "which counts the repeated read twice"))
    (cleanup)))

(deftest one-read-set-read-once-and-twice-states-no-snapshot
  (seeded!)
  (let [[^js once-entry once]   (mount! reads-left-once)
        [^js twice-entry twice] (mount! reads-left-twice)]
    (bump!)
    (testing "the premise: two entries over ONE read set fold into one row,
              and React holds a different number for each"
      (is (= 1 (count (:boundaries (rf.fresco.tool/read-mounted-boundaries)))))
      (is (= (rf.fresco.test.runtime/reads-of once-entry)
             (rf.fresco.test.runtime/reads-of twice-entry)))
      (is (not= (rf.fresco.test.runtime/snapshot-of once-entry)
                (rf.fresco.test.runtime/snapshot-of twice-entry))))
    (is (= rf.fresco.evidence/unknown
           (:snapshot (first (:explanations (rf.fresco.tool/explain-render)))))
        "no single number is what both boundaries compare, so the row states none")
    (once)
    (twice)))

(deftest a-released-cell-is-absent-from-attribution-before-the-reaper-runs
  (seeded!)
  (let [[_ left-cleanup]  (mount! reads-left-once)
        [_ right-cleanup] (mount! (fn [_] (rf.fresco/sub [:ts/right]) nil))]
    (left-cleanup)
    (testing "the premise: the released cell is still in the table, with no reader"
      (is (contains? @rf.fresco.impl.collector/!cells [frame-id [:ts/left]]))
      (is (zero? (count (rf.fresco.test.runtime/cell-readers [frame-id [:ts/left]])))))
    (let [by-sub (into {}
                       (map (juxt :sub-id identity))
                       (:edges (rf.fresco.tool/read-read-attribution)))]
      (is (nil? (:ts/left by-sub))
          "a key no boundary holds is absent, not an edge with `:fan-out 0`")
      (is (= 1 (:fan-out (:ts/right by-sub)))
          "the control: a held key stays listed"))
    (right-cleanup)))
