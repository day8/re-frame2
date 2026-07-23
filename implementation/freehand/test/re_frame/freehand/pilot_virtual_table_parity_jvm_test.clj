(ns re-frame.freehand.pilot-virtual-table-parity-jvm-test
  "THE VIRTUAL-TABLE PILOT, promoted — the compiled arm and whole-tree
  promotion parity.

  `pilot-virtual-table-cljs-test` proves the interpreted table on both
  hosts. This file adds the promoted twin: the same declarations with
  `{:compiled true}` and nothing else changed, asserted against the same
  exact windows, and then compared to the interpreted tree WHOLE — the
  table, the caller that supplies the row content, and the declared child
  that content mounts.

  JVM-only, and the reason is a real bound rather than a convention: a
  compiled body carrying `v/slot` lowers through the STRUCTURAL emitter,
  which is the host both modes share, and the CLJS/React lowering of the
  form is not built. `:slot` is an admitted node kind of
  `:re-frame.freehand/v1` — the analyzer accepts the body and the grammar
  check passes it — so the gap is in the React emitter alone. The
  promoted arm is therefore proven where it can be proven, and the
  compiled tier's browser cell is BLOCKED rather than claimed."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [re-frame.core :as rf]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-virtual-table :as ui]
            [re-frame.freehand.pilot-virtual-table-compiled :as compiled]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Seams — the same ones the interpreted suite renders through
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)

(defn- init! [] (ui/register!) (ui/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

(defn- render!
  [form]
  (let [cand (cell/candidate (cell/cell :acme/probe) fid)]
    (cell/with-capture cand (fn [] (t/render form)))))

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- part? [p n] (and (map? n) (= p (:data-part (t/attrs n)))))
(defn- node-part [tree p] (t/find tree (partial part? p)))
(defn- rows-of [tree] (t/find-all tree (partial part? "row")))
(defn- row-keys [tree] (mapv :key (rows-of tree)))
(defn- row-data-keys [tree] (mapv #(:data-row-key (t/attrs %)) (rows-of tree)))

(defn- boundaries
  [tree view-name]
  (t/find-all tree #(and (map? %) (= view-name (some-> (:view-id %) name)))))

(def ^:private total 10000)
(def ^:private rows (ui/ledger-rows total))
(def ^:private keys-at-top (mapv #(str "r" %) (range 0 25)))
(def ^:private keys-at-3200 (mapv #(str "r" %) (range 96 125)))

;; ===========================================================================
;; The compiled arm, against the same exact windows
;; ===========================================================================

(deftest the-promoted-table-windows-ten-thousand-rows-to-exactly-twenty-five
  (testing "Promotion does not change the arithmetic: ten thousand
            records reach the compiled table and EXACTLY twenty-five rows
            are built, keyed by the first twenty-five record ids, under a
            canvas that still declares the whole scroll height."
    (is (= total (count rows)) "non-vacuous: ten thousand records went in")
    (let [tree (render! [compiled/ledger {:rows rows}])]
      (is (= 25 (count (rows-of tree)))
          "exactly 25 row nodes for a 10000-row dataset")
      (is (= keys-at-top (row-data-keys tree))
          "and they are the first 25 records, in order")
      (is (= "10000" (:aria-rowcount (t/attrs (node-part tree "viewport"))))
          "the grid still reports all 10000 rows")
      (is (= "320000px" (:height (:style (t/attrs (node-part tree "canvas")))))
          "and the canvas is the full 10000 × 32px"))))

(deftest the-promoted-window-slides-with-the-scroll-offset
  (testing "The compiled table reads the same subscription and answers
            the same window: exactly twenty-nine rows around row 100,
            each keyed by its record and each carrying its ABSOLUTE row
            number."
    (send! [:acme.ui.table/scrolled ui/ledger-key 3200])
    (let [tree (render! [compiled/ledger {:rows rows}])]
      (is (= 29 (count (rows-of tree))) "exactly 29 rows around row 100")
      (is (= keys-at-3200 (row-data-keys tree)) "r96 … r124, in order")
      (is (= keys-at-3200 (row-keys tree))
          "and each row's structural :key IS the record's id")
      (is (= ["97" "125"]
             [(:aria-rowindex (t/attrs (first (rows-of tree))))
              (:aria-rowindex (t/attrs (last (rows-of tree))))])
          "aria-rowindex is the absolute row number"))))

(deftest a-promoted-row-that-stays-in-the-window-keeps-its-key
  (testing "One row of scroll, and twenty-eight of the twenty-nine keys
            are the same keys — the compiled tier's rows are as reusable
            as the interpreted tier's."
    (send! [:acme.ui.table/scrolled ui/ledger-key 3200])
    (let [before (row-data-keys (render! [compiled/ledger {:rows rows}]))
          _      (send! [:acme.ui.table/scrolled ui/ledger-key 3232])
          after  (row-data-keys (render! [compiled/ledger {:rows rows}]))
          stayed (set/intersection (set before) (set after))]
      (is (= 29 (count before)) "29 rows before the scroll")
      (is (= 29 (count after)) "29 rows after it")
      (is (= 28 (count stayed)) "exactly 28 rows survive the scroll unchanged")
      (is (= ["r96"] (vec (set/difference (set before) (set after))))
          "exactly one row left the top")
      (is (= ["r125"] (vec (set/difference (set after) (set before))))
          "and exactly one arrived at the bottom"))))

(deftest a-compiled-caller-row-slot-renders-caller-content-per-row
  (testing "A COMPILED render-fn handed to a COMPILED slot — the crossing
            law's requirement, since an interpreted render-fn answers
            markup and a compiled one answers a node. The caller's content
            is built once per visible row, with the record and its
            absolute index, and the slot-carrying prop records as the
            render-fn contract."
    (let [tree  (render! [compiled/ledger {:rows rows}])
          cells (boundaries tree "ledger-row-cells")
          table (t/find tree #(and (map? %)
                                   (= "data-table" (some-> (:view-id %) name))))]
      (is (= 25 (count cells))
          "the caller's content was built exactly 25 times, not 10000")
      (is (= {:record (nth rows 0) :index 0} (t/attrs (first cells)))
          "the first invocation got the first record and its absolute index")
      (is (= {:record (nth rows 24) :index 24} (t/attrs (last cells)))
          "and the last got record 24")
      (is (= "24AC-2472" (t/text (last (rows-of tree))))
          "the caller's cells render inside the row")
      (is (= {:rf.ui/opaque :v/render-fn} (:row (t/attrs table)))
          "and the slot-carrying prop records as the render-fn contract"))))

(deftest a-promoted-absent-row-slot-renders-rows-with-nothing-in-them
  (testing "`:row` is optional in the compiled tier too: the rows are
            built, keyed and positioned, and empty."
    (let [small (ui/ledger-rows 6)
          tree  (render! [compiled/data-table {:table-key  [:acme.app/bare :t]
                                               :rows       small
                                               :row-key    :id
                                               :row-h      32
                                               :viewport-h 640
                                               :label      "Bare"}])]
      (is (= 6 (count (rows-of tree))) "every row is still there")
      (is (= (mapv :id small) (row-data-keys tree)) "still keyed by the record")
      (is (= [""] (distinct (mapv t/text (rows-of tree))))
          "and every one of them is empty"))))

(deftest the-promoted-part-roster-is-exactly-what-the-table-addresses
  (testing "The public part roster survives promotion unchanged — a
            stylesheet written against the interpreted table reaches the
            same regions of the compiled one."
    (let [tree     (render! [compiled/ledger {:rows (ui/ledger-rows 40)}])
          declared (set (map name ui/part-ids))
          emitted  (into #{} (comp (map t/attrs) (keep :data-part))
                         (t/find-all tree map?))]
      (is (= declared emitted) "the emitted parts are exactly the declared ones")
      (is (= 4 (count declared)) "non-vacuous: there are four of them")
      (is (= #{ui/component-id}
             (into #{} (comp (map t/attrs) (keep :data-component))
                   (t/find-all tree map?)))
          "under one component scope, and only one"))))

;; ===========================================================================
;; The scroll reader, on the host that has a payload map
;; ===========================================================================

(deftest the-scroll-offset-reads-off-the-structural-payload
  (testing "The table's one host-shaped read. On the JVM the structural
            host fires no native event, so a callback's payload IS the map
            a test supplies — the same arrangement `payload-map` makes for
            the closed projections. The CLJS arm of the same reader is
            proven against a live DOM event in the browser suite."
    (is (= 3200 (ui/scroll-offset {:scroll-top 3200}))
        "the offset the payload carries")
    (is (= 0 (ui/scroll-offset {}))
        "and an absent offset is the top rather than a nil in an event vector")
    (send! [:acme.ui.table/scrolled ui/ledger-key (ui/scroll-offset {:scroll-top 3200})])
    (is (= 96 (:start (ui/window {:total total :row-h 32 :viewport-h 640
                                  :scroll-top 3200})))
        "and it is the number the window is computed from")))

;; ===========================================================================
;; Whole-tree promotion parity
;; ===========================================================================

(defn- as-interpreted-ids
  "Rewrite the compiled twin's view ids onto the interpreted originals'.
  A view id names where a declaration LIVES, and living in another file
  is the only difference these declarations have."
  [x]
  (walk/postwalk
    (fn [n]
      (if (and (keyword? n)
               (= "re-frame.freehand.pilot-virtual-table-compiled" (namespace n)))
        (keyword "re-frame.freehand.pilot-virtual-table" (name n))
        n))
    x))

(deftest promotion-changes-nothing-about-the-table
  (testing "The whole composition renders to the SAME structural tree
            interpreted and compiled, at rest and scrolled: the same
            windowed rows, the same keys, the same caller content at the
            same sites, the same geometry, the same scroll site. Promotion
            is a one-line change per declaration and it changes when a
            mistake is caught, never what the page is."
    (let [small (ui/ledger-rows 40)]
      (doseq [top [0 3200]]
        (send! [:acme.ui.table/scrolled ui/ledger-key top])
        (let [interpreted (render! [ui/ledger {:rows small}])
              promoted    (as-interpreted-ids (render! [compiled/ledger {:rows small}]))]
          (is (seq (rows-of interpreted))
              (str "non-vacuous: there are rows to compare at scroll " top))
          (is (= interpreted promoted)
              (str "the promoted tree IS the interpreted tree at scroll " top)))))))
