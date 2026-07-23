(ns re-frame.freehand.pilot-virtual-table-cljs-test
  "THE VIRTUAL-TABLE PILOT, headless — the deterministic half.

  Everything here is an EXACT claim. A virtual table's whole promise is a
  count: ten thousand records go in, twenty-five rows come out, and the
  other nine thousand nine hundred and seventy-five are work that never
  happened. `fewer than N` would pass for a table that rendered the lot
  and hid most of it behind `overflow`, so every row-count assertion
  below is an exact integer for a known window, and the rows that render
  and the rows that do not are asserted to sum to the dataset.

  Nothing here is timed. Comparator time and end-to-end latency are the
  B3 measurement's, and a marginal timing assertion inside a correctness
  gate is a flake everybody learns to ignore.

  This file is the INTERPRETED arm plus the mode-free arithmetic, and it
  runs on both hosts from one body. The COMPILED arm and the whole-tree
  promotion parity are `pilot-virtual-table-parity-jvm-test`, JVM-only
  for the reason `pilot-virtual-table-compiled` states: a compiled body
  carrying `v/slot` has no CLJS lowering yet. The mounted claims — a real
  scrollbar, real reconciliation, a real remount — belong to
  `pilot-virtual-table-dom-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.set :as set]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-virtual-table :as ui]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)

(defn- init! [] (ui/register!) (ui/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

(defn- render!
  "One render pass under a render candidate, so the table's `v/sub` read
  has a boundary to belong to — the same seam every structural
  subscription suite renders through."
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

;; ---------------------------------------------------------------------------
;; The dataset, and the two windows every row below is asserted against
;; ---------------------------------------------------------------------------

(def ^:private total 10000)
(def ^:private rows (ui/ledger-rows total))

;; row-h 32, viewport-h 640, overscan 4.
;;   at scroll 0    : first row 0,   span 21, [0 25)   → 25 rows
;;   at scroll 3200 : first row 100, span 21, [96 125) → 29 rows
(def ^:private window-at-top  {:start 0  :end 25  :count 25 :skipped 9975})
(def ^:private window-at-3200 {:start 96 :end 125 :count 29 :skipped 9971})

(def ^:private keys-at-top  (mapv #(str "r" %) (range 0 25)))
(def ^:private keys-at-3200 (mapv #(str "r" %) (range 96 125)))

;; ===========================================================================
;; The window — one pure function, before any render at all
;; ===========================================================================

(deftest the-window-is-a-pure-function-of-four-numbers
  (testing "The window a table shows is arithmetic, not measurement: the
            scroll offset, the row height, the viewport height and the
            overscan decide it, and `:count` + `:skipped` always account
            for the whole dataset. Asserted before any render, on both
            hosts, because a windowing rule that is only true inside a
            viewport is a rule you cannot test."
    (let [geometry {:total total :row-h 32 :viewport-h 640}
          case-of  (fn [m] (ui/window (merge geometry m)))]
      (is (= window-at-top (case-of {:scroll-top 0}))
          "at rest the window is the first 25 rows and skips 9975")
      (is (= window-at-3200 (case-of {:scroll-top 3200}))
          "scrolled to row 100 the window is [96 125)")
      (is (= window-at-3200 (case-of {:scroll-top 3210}))
          "a scroll offset PART WAY down a row lands in the same window —
           the first visible row is a floor division, not a rounding")
      (is (= {:start 100 :end 121 :count 21 :skipped 9979}
             (case-of {:scroll-top 3200 :overscan 0}))
          "with no overscan the window is exactly the rows the viewport
           covers, the partially visible one included")
      (is (= {:start 9995 :end 10000 :count 5 :skipped 9995}
             (case-of {:scroll-top (* 32 (dec total))}))
          "at the bottom the window CLAMPS to the dataset rather than
           running past it")
      (is (= {:start 0 :end 3 :count 3 :skipped 0}
             (ui/window {:total 3 :row-h 32 :viewport-h 640 :scroll-top 0}))
          "a dataset smaller than the viewport renders whole, and skips
           nothing")
      (is (= {:start 0 :end 0 :count 0 :skipped 0}
             (ui/window {:total 0 :row-h 32 :viewport-h 640 :scroll-top 0}))
          "an empty dataset is a window of nothing, not a negative one")
      (is (= window-at-top (case-of {:scroll-top -50}))
          "a negative offset — a rubber-band overscroll — is the top")
      (testing "every case accounts for the whole dataset"
        (doseq [top [0 1 31 32 3200 3210 319968 320000]]
          (let [w (case-of {:scroll-top top})]
            (is (= total (+ (:count w) (:skipped w)))
                (str "count + skipped = total at scroll " top))))))))

;; ===========================================================================
;; Windowing commits ONLY the visible rows — the exact count
;; ===========================================================================

(deftest windowing-commits-only-the-visible-rows
  (testing "Ten thousand records reach the table and EXACTLY twenty-five
            rows are built. The count is exact, the keys are the exact
            first twenty-five record ids in order, and the canvas still
            declares the full scroll height — so the scrollbar is honest
            about a dataset the tree never walked."
    (is (= total (count rows)) "non-vacuous: ten thousand records went in")
    (let [tree (render! [ui/ledger {:rows rows}])]
      (is (= (:count window-at-top) (count (rows-of tree)))
          "exactly 25 row nodes for a 10000-row dataset")
      (is (= keys-at-top (row-data-keys tree))
          "and they are the first 25 records, in order")
      (is (= "10000" (:aria-rowcount (t/attrs (node-part tree "viewport"))))
          "the grid still reports all 10000 rows to a screen reader")
      (is (= "320000px" (:height (:style (t/attrs (node-part tree "canvas")))))
          "and the canvas is the full 10000 × 32px, so the scrollbar measures
           the dataset and not the window"))))

(deftest the-window-slides-with-the-scroll-offset
  (testing "A scroll is an ordinary event into ordinary app-db, and the
            next render reads it back: the window moves to exactly the
            twenty-nine rows around row 100, and every one of them is
            keyed by its record's id."
    (send! [:acme.ui.table/scrolled ui/ledger-key 3200])
    (let [tree (render! [ui/ledger {:rows rows}])]
      (is (= (:count window-at-3200) (count (rows-of tree)))
          "exactly 29 rows around row 100")
      (is (= keys-at-3200 (row-data-keys tree)) "r96 … r124, in order")
      (is (= keys-at-3200 (row-keys tree))
          "and each row's structural :key IS the record's id, not its position
           in the window")
      (is (= ["97" "125"]
             [(:aria-rowindex (t/attrs (first (rows-of tree))))
              (:aria-rowindex (t/attrs (last (rows-of tree))))])
          "aria-rowindex is the ABSOLUTE row number, so a screen reader is told
           where in the ten thousand it is"))))

(deftest a-row-that-stays-in-the-window-keeps-its-key-across-a-scroll
  (testing "Scrolling by one row is what a keyed reconciler has to
            survive: twenty-eight of the twenty-nine keys are the same
            keys, so twenty-eight rows are the same rows and only the
            pair at the edges is work."
    (send! [:acme.ui.table/scrolled ui/ledger-key 3200])
    (let [before (row-data-keys (render! [ui/ledger {:rows rows}]))
          _      (send! [:acme.ui.table/scrolled ui/ledger-key 3232])
          after  (row-data-keys (render! [ui/ledger {:rows rows}]))
          stayed (set/intersection (set before) (set after))]
      (is (= 29 (count before)) "29 rows before the scroll")
      (is (= 29 (count after)) "29 rows after it")
      (is (= 28 (count stayed)) "exactly 28 rows survive the scroll unchanged")
      (is (= ["r96"] (vec (set/difference (set before) (set after))))
          "exactly one row left the top")
      (is (= ["r125"] (vec (set/difference (set after) (set before))))
          "and exactly one arrived at the bottom"))))

(deftest keyed-identity-travels-with-the-record-and-not-with-the-slot
  (testing "A reorder moves records, not rows. When a record moves to the
            front its key moves with it, and every other key is where the
            record put it — the premise a keyed reconciler needs before it
            can leave the unrelated rows alone."
    (let [small     (ui/ledger-rows 12)
          reordered (into [(nth small 7)]
                          (concat (subvec small 0 7) (subvec small 8)))
          before    (row-data-keys (render! [ui/ledger {:rows small}]))
          after     (row-data-keys (render! [ui/ledger {:rows reordered}]))]
      (is (= 12 (count reordered)) "non-vacuous: the reorder kept every record")
      (is (= (mapv :id small) before)
          "before the reorder, keys are the record order")
      (is (= (mapv :id reordered) after)
          "after it, keys are the NEW record order")
      (is (= "r7" (first after))
          "the moved record carried its key to the front")
      (is (= (set before) (set after))
          "and no key was invented or lost, so nothing the reconciler sees is a
           new row"))))

;; ===========================================================================
;; The caller's row slot
;; ===========================================================================

(deftest a-caller-row-slot-renders-caller-content-per-row
  (testing "The table knows nothing about a row's content. The caller
            supplies one `v/render-fn`, the table invokes it once per
            VISIBLE row with the record and its absolute index, and the
            content lands inside the row with no wrapper of the
            substrate's between them."
    (let [tree  (render! [ui/ledger {:rows rows}])
          cells (boundaries tree "ledger-row-cells")
          table (t/find tree #(and (map? %)
                                   (= "data-table" (some-> (:view-id %) name))))]
      (is (= 25 (count cells))
          "the caller's content was built exactly 25 times, once per visible
           row, and not 10000 times")
      (is (= {:record (nth rows 0) :index 0} (t/attrs (first cells)))
          "the first invocation got the first record and its ABSOLUTE index")
      (is (= {:record (nth rows 24) :index 24} (t/attrs (last cells)))
          "and the last got record 24")
      (is (= "24AC-2472" (t/text (last (rows-of tree))))
          "the caller's cells render inside the row")
      (is (= {:rf.ui/opaque :v/render-fn} (:row (t/attrs table)))
          "and the slot-carrying prop records on the boundary as the render-fn
           CONTRACT it is, so a test can assert the caller supplied content
           rather than a bare function"))))

(deftest an-absent-row-slot-renders-rows-with-nothing-in-them
  (testing "`:row` is optional, so a caller may take the windowing and
            supply no content at all. The rows are still built, still
            keyed and still positioned — they are simply empty."
    (let [small (ui/ledger-rows 6)
          tree  (render! [ui/data-table {:table-key  [:acme.app/bare :t]
                                         :rows       small
                                         :row-key    :id
                                         :row-h      32
                                         :viewport-h 640
                                         :label      "Bare"}])]
      (is (= 6 (count (rows-of tree))) "every row is still there")
      (is (= (mapv :id small) (row-data-keys tree)) "still keyed by the record")
      (is (= [""] (distinct (mapv t/text (rows-of tree))))
          "and every one of them is empty"))))

;; ===========================================================================
;; The scroll seam, and the part roster
;; ===========================================================================

(deftest the-scroll-position-is-ordinary-application-state
  (testing "A virtual table's one piece of interaction state is an
            integer in app-db, written by an ordinary event and dropped by
            an ordinary end event — visible to tools, serialisable, and
            restored by time travel like anything else. There is no scroll
            registry and nothing the render has to clean up after itself.

            The offset READER is host-shaped and is proven per host: the
            JVM payload seam in `pilot-virtual-table-parity-jvm-test`, the
            live DOM event in the browser suite."
    (send! [:acme.ui.table/scrolled ui/ledger-key 3200])
    (is (= 3200 (get-in (frame/frame-app-db-value fid)
                        [ui/tables-root ui/ledger-key :scroll-top]))
        "the offset is ordinary app-db")
    (send! [:acme.ui.table/scrolled ui/ledger-key -10])
    (is (= 0 (get-in (frame/frame-app-db-value fid)
                     [ui/tables-root ui/ledger-key :scroll-top]))
        "a rubber-band overscroll clamps at the top rather than going negative")
    (send! [:acme.ui.table/released ui/ledger-key])
    (is (nil? (get-in (frame/frame-app-db-value fid) [ui/tables-root ui/ledger-key]))
        "and the owner's one end event drops the instance's record")))

(deftest the-part-roster-is-exactly-what-the-table-addresses
  (testing "A part id is API. The table emits every part it declares and
            no part it does not, so the roster a stylesheet reads is the
            roster the DOM has."
    (let [tree     (render! [ui/ledger {:rows (ui/ledger-rows 40)}])
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
;; The hundred-cell editing workload (the B4 measurement's home)
;; ===========================================================================

(deftest the-editing-grid-windows-five-hundred-rows-down-to-a-hundred-cells
  (testing "The editing workload is a hundred controlled cells — ten
            windowed rows of ten — no matter how large the sheet is. The
            cell count is bounded by the viewport, which is the reason an
            editing grid is a virtual table at all.

            Each cell carries its OWN intent as data, and that is the
            property worth asserting here: a hundred distinct event
            vectors, each assertable by equality without a browser. The
            per-KEYSTROKE work counts this workload exists to produce
            belong to the B4 measurement — a different instrument, and not
            a correctness gate."
    (let [sheet  (ui/grid-rows 500)
          tree   (render! [ui/editing-grid {:rows sheet}])
          cells  (boundaries tree "grid-cell")
          inputs (t/find-all tree #(and (map? %) (= :input (:tag %))))]
      (is (= 500 (count sheet)) "non-vacuous: five hundred rows went in")
      (is (= 10 (count (rows-of tree))) "exactly ten rows are built")
      (is (= 100 (count cells)) "exactly one hundred cells are built")
      (is (= 100 (count inputs)) "and exactly one hundred inputs reach the host")
      (is (= (vec (for [r (range 10) c (range 10)] (str "g" r "/c" c)))
             (mapv #(:data-cell (t/attrs %)) inputs))
          "row-major, and every cell addresses itself")
      (is (= [:acme.app.grid/cell-edited "g7" :c3 :re-frame.freehand/value]
             (:on-input (t/attrs (nth inputs 73))))
          "one cell's edit intent, as data — assertable by equality")
      (is (= 100 (count (into #{} (map #(:on-input (t/attrs %))) inputs)))
          "and all one hundred intents are distinct, so no cell writes
           through another's address"))))
