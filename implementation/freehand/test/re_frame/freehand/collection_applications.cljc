(ns re-frame.freehand.collection-applications
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
  are visible and the overscan band adds 4. The counts are 24, and the
  difference is one of the reasons the collection line won.

  Everything here is CONSUMER code — `v/defview`, `v/sub`, `v/render-fn`,
  event vectors, `reg-event` and `reg-sub`. It adds no substrate machinery
  and asks for none.

  NOT a test namespace (no `-cljs-test` suffix), and that matters to more
  than tidiness: a `deftest` in here would be pulled into the `:browser-test`
  bundle as a transitive require of the mounted suite, and that lane is
  narrowed to DOM-dependent tests on purpose. The structural laws are
  [[re-frame.freehand.collection-applications-cljs-test]]'s; the mounted one
  is [[re-frame.freehand.collection-applications-dom-cljs-test]]'s.

  Not enrolled in the conformance index and citing no `FH-` id, exactly
  as the pilot was not: these are APPLICATIONS of a law proven elsewhere
  (FH-CTRL-021), and their value is that they are shaped like real ones.

  Dev/test scope ONLY."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.collection :as coll]))

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

