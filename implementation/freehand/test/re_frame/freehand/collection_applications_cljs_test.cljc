(ns re-frame.freehand.collection-applications-cljs-test
  "THE VIRTUAL-TABLE PILOT'S APPLICATIONS, now callers of
  [[re-frame.freehand.collection]] (rf2-86i64, rf2-pa57v).

  The pilot was a windowed table written as consumer code plus two
  applications that called it. rf2-86i64 settled which of the two virtual
  implementations survives — `re-frame.freehand.collection` — and retired
  the pilot's COMPONENT half. Its applications are not retired with it:
  they are here, re-pointed at the survivor, and they are the evidence
  that one engine serves several shapes rather than one.

  | the application | what it asks the engine for |
  |---|---|
  | [[ledger]] | ten thousand records, three read-only cells per row |
  | [[editing-grid]] | a sheet whose window is a hundred CONTROLLED inputs |

  Both wear `role=\"grid\"`, and that is exactly why
  [[re-frame.freehand.collection/virtual-collection]] names no role: a
  virtual list hard-coded to `listbox` could host neither of them. The
  role, the row count, the row's own semantics and the accessible position
  are supplied HERE, through `:attrs` for the viewport and through the row
  slot for the rows; the engine contributes the window, the canvas, the
  positioning and the scroll host, and nothing else.

  ## What changed in the port, and why each change is the point

  **The slot receives a KEY, not a record.** The pilot handed its row slot
  the record itself, so every edit anywhere in the dataset published a new
  vector and re-rendered the whole visible window. The engine hands over
  the row's key, its ABSOLUTE index and the collection's TOTAL, and the
  row reads its own record by identity inside its own boundary — which is
  the narrow-read design the survivor is built around.

  **The scroll offset is the APPLICATION's.** The pilot's component owned
  a per-instance record under `:acme.ui/tables`, with a registration to
  write it and a lifetime fence to drop it. The engine owns no record at
  all: `:scroll-offset` is a value these applications keep in their own
  app-db and hand down, so there is nothing to release and no library
  protocol to learn.

  **The window is one row narrower.** At a viewport that is an exact
  multiple of the row extent the pilot counted an extra partially-visible
  row that is not there — 25 rows in a 640px box over 32px rows, where 20
  are visible and the overscan band adds 4. The counts below are 24, and
  the difference is one of the reasons the collection line won.

  Not enrolled in the conformance index and citing no `FH-` id, exactly
  as the pilot was not: these are APPLICATIONS of a law proven elsewhere
  (FH-CTRL-021), and their value is that they are shaped like real ones."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.collection :as coll]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)

;; ---------------------------------------------------------------------------
;; The geometry both applications declare
;; ---------------------------------------------------------------------------
;;
;; The CALLER owns the numbers — the mainstream virtual-list contract, and
;; what keeps the engine free of measurement. Both viewports are exact
;; multiples of the row extent, which is the boundary the pilot's own
;; arithmetic got wrong.

(def component-id
  "The `data-component` marker scoping these applications' part names. An
  application decision; the substrate reserves nothing for `acme.*`, and
  the engine publishes no scope of its own for one to collide with."
  "acme/data-table")

(def row-extent 32)

(def ledger-viewport-extent
  "Twenty rows visible; with an overscan of four the window is 24."
  (* 20 row-extent))

(def ledger-overscan 4)

(def grid-viewport-extent
  "Ten rows visible and no overscan — the hundred-cell editing window,
  stated as arithmetic rather than as a fudge that happens to land there."
  (* 10 row-extent))

(def grid-columns
  "Ten columns; ten windowed rows of them is the hundred-cell workload."
  [:c0 :c1 :c2 :c3 :c4 :c5 :c6 :c7 :c8 :c9])

;; ---------------------------------------------------------------------------
;; The datasets — generated, because ten thousand literal maps is not a
;; fixture. Identities and records are SEPARATE, which is what lets a row
;; read its own record without the collection publishing a new vector.
;; ---------------------------------------------------------------------------

(defn ledger-ids [n] (mapv #(str "r" %) (range n)))

(defn ledger-records
  [n]
  (into {}
        (map (fn [i]
               [(str "r" i) {:id (str "r" i) :code (str "AC-" i) :amount (* 3 i)}]))
        (range n)))

(defn grid-ids [n] (mapv #(str "g" %) (range n)))

(defn seed-db
  "The frame value both applications read from."
  [{:keys [ledger-n grid-n ledger-scroll grid-scroll]}]
  {:acme.app/ledger {:ids     (ledger-ids (or ledger-n 0))
                     :records (ledger-records (or ledger-n 0))
                     :scroll  (or ledger-scroll 0)}
   :acme.app/grid   {:ids    (grid-ids (or grid-n 0))
                     :scroll (or grid-scroll 0)
                     :cells  {}}})

(defn register!
  "The applications' whole dataflow: four reads and three transitions for
  two windowed grids over ten thousand and five hundred rows. Public so
  the mounted suite starts from exactly these registrations."
  []
  (rf/reg-sub :acme.app.ledger/ids    (fn [db _] (get-in db [:acme.app/ledger :ids])))
  (rf/reg-sub :acme.app.ledger/scroll (fn [db _] (get-in db [:acme.app/ledger :scroll])))
  (rf/reg-sub :acme.app.ledger/record
    (fn [db [_ id]] (get-in db [:acme.app/ledger :records id])))
  (rf/reg-event :acme.app.ledger/scrolled
    (fn [{:keys [db]} [_ offset]]
      {:db (assoc-in db [:acme.app/ledger :scroll] (max 0 (long offset)))}))

  (rf/reg-sub :acme.app.grid/ids    (fn [db _] (get-in db [:acme.app/grid :ids])))
  (rf/reg-sub :acme.app.grid/scroll (fn [db _] (get-in db [:acme.app/grid :scroll])))
  (rf/reg-sub :acme.app.grid/cell
    (fn [db [_ row-id col]] (get-in db [:acme.app/grid :cells row-id col] "")))
  (rf/reg-event :acme.app.grid/scrolled
    (fn [{:keys [db]} [_ offset]]
      {:db (assoc-in db [:acme.app/grid :scroll] (max 0 (long offset)))}))
  (rf/reg-event :acme.app.grid/cell-edited
    (fn [{:keys [db]} [_ row-id col text]]
      {:db (assoc-in db [:acme.app/grid :cells row-id col] text)})))

;; ---------------------------------------------------------------------------
;; The ledger — a read-only table of ten thousand records
;; ---------------------------------------------------------------------------

(v/defview ledger-row
  "One ledger row, and the whole of what makes this a GRID rather than a
  listbox: `role=\"row\"`, `aria-rowindex` at the row's ABSOLUTE position,
  and three `gridcell`s. All three are spellings of the two facts the
  engine's row slot handed over — the index and the total — and choosing
  those spellings is precisely the decision the engine declines to make.

  It reads its own record by identity, so an edit to one record is one
  boundary's business."
  {:props [:map [:row-key :string] [:index :int] [:total :int]]}
  [{:keys [row-key index total]}]
  (let [record (v/sub [:acme.app.ledger/record row-key])]
    [:span {:data-part     "row"
            :role          "row"
            :aria-rowindex (inc index)
            :aria-rowcount total}
     [:span {:data-part "cell" :role "gridcell" :data-col "index"} (str index)]
     [:span {:data-part "cell" :role "gridcell" :data-col "code"} (:code record)]
     [:span {:data-part "cell" :role "gridcell" :data-col "amount"} (str (:amount record))]]))

(v/defview ledger
  "The ledger, as a caller of the ENGINE. It supplies the grid semantics
  and knows nothing about the window; the engine supplies the window and
  knows nothing about the semantics."
  [{:keys [list-id]}]
  (let [ids (v/sub [:acme.app.ledger/ids])]
    [coll/virtual-collection
     {:row-keys        ids
      :row-extent      row-extent
      :viewport-extent ledger-viewport-extent
      :scroll-offset   (v/sub [:acme.app.ledger/scroll])
      :overscan        ledger-overscan
      :on-scroll       [:acme.app.ledger/scrolled]
      :attrs           {:id             list-id
                        :data-component component-id
                        :role           "grid"
                        :aria-label     "Q3 ledger"
                        :aria-rowcount  (count ids)}
      :row             (v/render-fn [k i total]
                         [ledger-row {:row-key k :index i :total total}])}]))

;; ---------------------------------------------------------------------------
;; The editing grid — a hundred controlled cells, bounded by the viewport
;; ---------------------------------------------------------------------------

(v/defview grid-cell
  "ONE controlled cell, and its own boundary.

  A cell is a declared child rather than an inline `v/sub` inside the
  row's loop, for the reason the compiled grammar states out loud when it
  refuses `sub-in-loop`: a read per row belongs to a boundary that can be
  recomputed on its own. That is also what makes the hundred-cell workload
  interesting — a keystroke moves ONE cell's subscription and the
  ninety-nine others have nothing to recompute."
  {:props [:map [:row-id :string] [:col :keyword]]}
  [{:keys [row-id col]}]
  [:input.acme-grid-cell
   {:type      :text
    :data-part "cell"
    :role      "gridcell"
    :data-cell (str row-id "/" (name col))
    :value     (v/sub [:acme.app.grid/cell row-id col])
    :on-input  [:acme.app.grid/cell-edited row-id col ::v/value]}])

(v/defview grid-row
  "One editing row: ten controlled cells, each carrying its OWN intent as
  data. Per-cell intent is the property worth asserting —
  `[:acme.app.grid/cell-edited \"g7\" :c3 ::v/value]` is assertable by
  equality without a browser, for every one of the hundred cells."
  {:props [:map [:row-key :string] [:index :int] [:total :int]]}
  [{:keys [row-key index total]}]
  [:span {:data-part     "row"
          :role          "row"
          :aria-rowindex (inc index)
          :aria-rowcount total}
   (for [col grid-columns]
     [grid-cell {:key col :row-id row-key :col col}])])

(v/defview editing-grid
  "The hundred-cell editing workload: the same engine, wearing the same
  grid role, whose row slot renders ten controlled inputs. The window
  keeps the cell count bounded by the viewport rather than by the sheet,
  which is the whole reason an editing grid is virtualized at all."
  [{:keys [list-id]}]
  (let [ids (v/sub [:acme.app.grid/ids])]
    [coll/virtual-collection
     {:row-keys        ids
      :row-extent      row-extent
      :viewport-extent grid-viewport-extent
      :scroll-offset   (v/sub [:acme.app.grid/scroll])
      :overscan        0
      :on-scroll       [:acme.app.grid/scrolled]
      :attrs           {:id             list-id
                        :data-component component-id
                        :role           "grid"
                        :aria-label     "Editing grid"
                        :aria-rowcount  (count ids)}
      :row             (v/render-fn [k i total]
                         [grid-row {:row-key k :index i :total total}])}]))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ledger-total 10000)
(def grid-total 500)

;; A 640px viewport over 32px rows is exactly TWENTY visible; the overscan
;; band adds four at the bottom and clamps at the top, so the window is 24.
;; The pilot answered 25 here, counting a partially-visible row that an
;; exactly aligned viewport does not have.
(def ledger-count-at-top 24)

;; Scrolled to row 100: rows 96 through 123 — twenty visible, four of
;; overscan at each end. The pilot answered 29.
(def ledger-scroll-offset 3200)
(def ledger-first-at-scroll 96)
(def ledger-count-at-scroll 28)

;; Ten rows of ten columns, and the count is a property of the viewport
;; rather than of the five-hundred-row sheet.
(def grid-window-rows 10)
(def grid-window-cells (* grid-window-rows (count grid-columns)))

(defn seed!
  "Seed the frame with both datasets. Public so the mounted suite seeds
  from exactly this value."
  [m]
  (frame/replace-app-db! fid (seed-db m)))

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- tree-of [view]
  (t/with-render (t/render [view {:list-id "acme-table"}])))

(defn part [tree p] (t/find-all tree #(= p (:data-part (t/attrs %)))))
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
            band, clamped at the top. The count is exact, because `fewer
            than N` would pass for a table that rendered the lot and hid
            most of it behind `overflow`.

            The canvas still declares the full scroll height and the
            viewport still reports all ten thousand rows, so the scrollbar
            and the screen reader are both honest about a dataset the tree
            never walked."
    (register!)
    (seed! {:ledger-n ledger-total})
    (let [tree (tree-of ledger)
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
      (is (= component-id (:data-component vp))
          "under the application's own component scope")
      (is (= (str (* ledger-total row-extent) "px")
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
    (register!)
    (seed! {:ledger-n ledger-total})
    (send! [:acme.app.ledger/scrolled ledger-scroll-offset])
    (let [tree  (tree-of ledger)
          attrs (mapv t/attrs (rows-of tree))]
      (is (= ledger-count-at-scroll (count attrs))
          "exactly 28 rows around row 100")
      (is (= (mapv #(str "AC-" %)
                   (range ledger-first-at-scroll
                          (+ ledger-first-at-scroll ledger-count-at-scroll)))
             (row-codes tree))
          "AC-96 through AC-123, in order")
      (is (= (mapv (comp str inc)
                   (range ledger-first-at-scroll
                          (+ ledger-first-at-scroll ledger-count-at-scroll)))
             (mapv :aria-rowindex attrs))
          "each stating its absolute row number, ascending, with no
           renumbering to the window")
      (is (not= "1" (:aria-rowindex (first attrs)))
          "non-vacuous: the first rendered row is NOT the first record")
      (is (= (str ledger-scroll-offset)
             (str (get-in (frame/frame-app-db-value fid)
                          [:acme.app/ledger :scroll])))
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
    (register!)
    (seed! {:grid-n grid-total})
    (let [tree   (tree-of editing-grid)
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
      (is (= grid-window-cells (count (into #{} (map #(:on-input (t/attrs %))) inputs)))
          "and all one hundred intents are distinct, so no cell writes
           through another's address"))))

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
    (register!)
    (seed! {:ledger-n ledger-total :grid-n grid-total})
    (let [a (tree-of ledger)
          b (tree-of editing-grid)]
      (is (= (count (shells-of a)) (count (rows-of a)))
          "the ledger renders one engine shell per application row")
      (is (= (count (shells-of b)) (count (rows-of b)))
          "and so does the grid — one mechanics layer under one semantic
           layer, in both")
      (is (= ["presentation"]
             (distinct (mapv #(:role (t/attrs %)) (concat (shells-of a) (shells-of b)))))
          "every positioned shell is presentational in both, so the
           accessibility tree sees the application's row and not the
           engine's box")
      (is (= "grid" (:role (t/attrs (viewport-of a)))
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
            halves. Asserting it over the split is the stronger reading:
            it says which layer each name belongs to, so a part migrating
            between them is a visible change rather than a silent one."
    (register!)
    (seed! {:ledger-n ledger-total})
    (let [tree    (tree-of ledger)
          emitted (into #{} (comp (map t/attrs) (keep :data-part))
                        (t/find-all tree map?))]
      (is (= #{"viewport" "canvas" "row-shell" "row" "cell"} emitted)
          "the emitted parts are exactly the five the two layers declare")
      (is (= #{component-id}
             (into #{} (comp (map t/attrs) (keep :data-component))
                   (t/find-all tree map?)))
          "under one component scope, and only one — the engine publishes
           no scope of its own for an application's to collide with"))))
