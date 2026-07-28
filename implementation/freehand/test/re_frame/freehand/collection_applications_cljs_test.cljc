(ns re-frame.freehand.collection-applications-cljs-test
  "THE VIRTUAL-TABLE PILOT'S APPLICATIONS, headlessly, on both hosts
  (rf2-86i64, rf2-pa57v).

  [[re-frame.freehand.collection-applications]] holds the two applications
  the retired virtual-table pilot leaves behind — a read-only ledger and a
  hundred-cell editing grid — re-pointed at
  [[re-frame.freehand.collection]]. This file settles what they DECIDE:
  how many rows each window holds, which records they read, what each row
  states about its absolute place in a collection the DOM does not
  contain, and that one engine really is carrying both.

  Every claim is an EXACT integer. A windowed table's whole promise is a
  count — ten thousand records go in, twenty-four rows come out — and
  `fewer than N` would pass for a table that rendered the lot and hid most
  of it behind `overflow`.

  The mounted half is
  [[re-frame.freehand.collection-applications-dom-cljs-test]], which owns
  the one claim a structural render cannot make: a hundred REAL controlled
  `input` elements in a document, and nothing left behind when the root
  goes.

  Not enrolled in the conformance index and citing no `FH-` id, exactly as
  the pilot was not — these are applications of a law proven elsewhere
  (FH-CTRL-021)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.collection-applications :as app]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)

;; ---------------------------------------------------------------------------
;; The windows every row below is asserted against
;; ---------------------------------------------------------------------------
;;
;; A 640px viewport over 32px rows is exactly TWENTY visible; the overscan
;; band adds four at the bottom and clamps at the top, so the window is 24.
;; The pilot answered 25 here, counting a partially-visible row that an
;; exactly aligned viewport does not have — which is one of the reasons the
;; collection line won.

(def ^:private ledger-total 10000)
(def ^:private ledger-count-at-top 24)

;; Scrolled to row 100: rows 96 through 123 — twenty visible, four of
;; overscan at each end. The pilot answered 29.
(def ^:private ledger-scroll-offset 3200)
(def ^:private ledger-first-at-scroll 96)
(def ^:private ledger-count-at-scroll 28)

;; Ten rows of ten columns, and the count is a property of the viewport
;; rather than of the five-hundred-row sheet.
(def ^:private grid-total 500)
(def ^:private grid-window-rows 10)
(def ^:private grid-window-cells (* grid-window-rows (count app/grid-columns)))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(defn- seed! [m] (frame/replace-app-db! fid (app/seed-db m)))

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- tree-of [view]
  (t/with-render (t/render [view {:list-id "acme-table"}])))

(defn- part [tree p] (t/find-all tree #(= p (:data-part (t/attrs %)))))
(defn- rows-of [tree] (part tree "row"))
(defn- viewport-of [tree] (first (part tree "viewport")))
(defn- shells-of [tree] (part tree "row-shell"))

(defn- row-codes
  "The `code` cell of every rendered ledger row, in document order — the
  identity a row actually READ, rather than one the engine published."
  [tree]
  (mapv t/text
        (t/find-all tree #(= "code" (:data-col (t/attrs %))))))

;; ===========================================================================
;; The ledger — one engine, wearing a grid
;; ===========================================================================

(deftest the-ledger-renders-the-window-and-states-the-collection
  (testing "Ten thousand records reach the application and EXACTLY
            twenty-four rows are built — twenty visible plus the overscan
            band, clamped at the top.

            The canvas still declares the full scroll height and the
            viewport still reports all ten thousand rows, so the scrollbar
            and the screen reader are both honest about a dataset the tree
            never walked."
    (app/register!)
    (seed! {:ledger-n ledger-total})
    (let [tree (tree-of app/ledger)
          vp   (t/attrs (viewport-of tree))]
      (is (= ledger-count-at-top (count (rows-of tree)))
          "exactly 24 rows for a 10,000-record dataset")
      (is (< ledger-count-at-top ledger-total)
          "non-vacuous: the collection is very much larger than the window")
      (is (= (mapv #(str "AC-" %) (range 0 ledger-count-at-top)) (row-codes tree))
          "and they are the first 24 records, in order, each having read its
           own record by identity")
      (is (= "grid" (:role vp))
          "the viewport wears the role the APPLICATION chose — the engine
           named none")
      (is (= (str ledger-total) (:aria-rowcount vp))
          "and reports all 10,000 rows to a screen reader")
      (is (= app/component-id (:data-component vp))
          "under the application's own component scope")
      (is (= (str (* ledger-total app/row-extent) "px")
             (:height (:style (t/attrs (first (part tree "canvas"))))))
          "while the canvas is the full 10,000 x 32px, so the scrollbar
           measures the dataset and not the window"))))

(deftest the-ledger-window-slides-and-every-row-states-its-absolute-place
  (testing "A scroll is an ordinary event into ordinary app-db — the
            application's own, since the engine owns no record — and the
            next render reads it back. The window moves to exactly the
            twenty-eight rows around row 100, and every one of them states
            its ABSOLUTE `aria-rowindex` rather than its place in the
            window, which is what a screen reader needs and what the DOM
            alone cannot say."
    (app/register!)
    (seed! {:ledger-n ledger-total})
    (send! [:acme.app.ledger/scrolled ledger-scroll-offset])
    (let [tree  (tree-of app/ledger)
          attrs (mapv t/attrs (rows-of tree))
          span  (range ledger-first-at-scroll
                       (+ ledger-first-at-scroll ledger-count-at-scroll))]
      (is (= ledger-count-at-scroll (count attrs))
          "exactly 28 rows around row 100")
      (is (= (mapv #(str "AC-" %) span) (row-codes tree))
          "AC-96 through AC-123, in order")
      (is (= (mapv (comp str inc) span) (mapv :aria-rowindex attrs))
          "each stating its absolute row number, ascending, with no
           renumbering to the window")
      (is (not= "1" (:aria-rowindex (first attrs)))
          "non-vacuous: the first rendered row is NOT the first record")
      (is (= ledger-scroll-offset
             (get-in (frame/frame-app-db-value fid) [:acme.app/ledger :scroll]))
          "and the offset the window came from is ordinary application
           state, where a tool reads it and a snapshot restores it"))))

;; ===========================================================================
;; The editing grid — the same engine, a hundred controlled cells
;; ===========================================================================

(deftest the-editing-grid-windows-five-hundred-rows-down-to-a-hundred-cells
  (testing "The editing workload is a hundred controlled cells — ten
            windowed rows of ten — no matter how large the sheet is. The
            cell count is bounded by the viewport, which is the reason an
            editing grid is virtualized at all.

            Each cell carries its OWN intent as data, and that is the
            property worth asserting: a hundred distinct event vectors,
            each assertable by equality without a browser."
    (app/register!)
    (seed! {:grid-n grid-total})
    (let [tree   (tree-of app/editing-grid)
          inputs (t/find-all tree #(= :input (:tag %)))]
      (is (= grid-total (count (get-in (frame/frame-app-db-value fid)
                                       [:acme.app/grid :ids])))
          "non-vacuous: five hundred rows went in")
      (is (= grid-window-rows (count (rows-of tree)))
          "exactly ten rows are built")
      (is (= grid-window-cells (count inputs))
          "exactly one hundred controlled cells are built")
      (is (= (vec (for [r (range grid-window-rows) c (range 10)]
                    (str "g" r "/c" c)))
             (mapv #(:data-cell (t/attrs %)) inputs))
          "row-major, and every cell addresses itself")
      (is (= [:acme.app.grid/cell-edited "g7" :c3 :re-frame.freehand/value]
             (:on-input (t/attrs (nth inputs 73))))
          "one cell's edit intent, as data — assertable by equality")
      (is (= grid-window-cells
             (count (into #{} (map #(:on-input (t/attrs %))) inputs)))
          "and all one hundred intents are distinct, so no cell writes
           through another's address"))))

;; ===========================================================================
;; One engine, two shapes — the whole point of the retirement
;; ===========================================================================

(deftest one-engine-serves-both-applications-with-the-same-mechanics
  (testing "This is what the retirement bought. The ledger and the editing
            grid are different widgets — one read-only, one a hundred
            controlled inputs — and they run on ONE virtualization engine
            with no second window arithmetic, no second scroll host and no
            second canvas between them.

            The mechanics are the engine's and identical in shape; the
            semantics are each application's and different. That pair is
            the claim, and asserting only the first half would be satisfied
            by two copies of the same widget."
    (app/register!)
    (seed! {:ledger-n ledger-total :grid-n grid-total})
    (let [a (tree-of app/ledger)
          b (tree-of app/editing-grid)]
      (is (= (count (shells-of a)) (count (rows-of a)))
          "the ledger renders one engine shell per application row")
      (is (= (count (shells-of b)) (count (rows-of b)))
          "and so does the grid — one mechanics layer under one semantic
           layer, in both")
      (is (= ["presentation"]
             (distinct (mapv #(:role (t/attrs %))
                             (concat (shells-of a) (shells-of b)))))
          "every positioned shell is presentational in both, so the
           accessibility tree sees the application's row and not the
           engine's box")
      (is (= "grid"
             (:role (t/attrs (viewport-of a)))
             (:role (t/attrs (viewport-of b))))
          "both wear the role the pilot's editing grid needed and a
           listbox-hard-coded control could not have given either")
      (is (not= (count (rows-of a)) (count (rows-of b)))
          "non-vacuous: the two windows really are different sizes, so this
           is one engine under two geometries rather than one call twice")
      (is (empty? (t/find-all a #(= :input (:tag %))))
          "and the two are genuinely different widgets — the ledger has no
           controlled cell anywhere in it"))))

;; ===========================================================================
;; The part roster
;; ===========================================================================

(deftest the-part-roster-under-a-caller-is-closed-and-split-by-layer
  (testing "A part id is API, and under the engine/semantics split it has
            two owners. `viewport`, `canvas` and `row-shell` are the
            ENGINE's — mechanics, emitted whoever calls it — and `row` and
            `cell` are the APPLICATION's. Together they are the whole
            roster a stylesheet may address, and there is nothing else in
            the tree.

            The pilot asserted this over one component that owned both
            halves. Asserting it over the split is the stronger reading: it
            says which layer each name belongs to, so a part migrating
            between them is a visible change rather than a silent one."
    (app/register!)
    (seed! {:ledger-n ledger-total})
    (let [tree    (tree-of app/ledger)
          emitted (into #{} (comp (map t/attrs) (keep :data-part))
                        (t/find-all tree map?))]
      (is (= #{"viewport" "canvas" "row-shell" "row" "cell"} emitted)
          "the emitted parts are exactly the five the two layers declare")
      (is (= #{app/component-id}
             (into #{} (comp (map t/attrs) (keep :data-component))
                   (t/find-all tree map?)))
          "under one component scope, and only one — the engine publishes
           no scope of its own for an application's to collide with"))))
