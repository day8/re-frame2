(ns re-frame.freehand.pilot-virtual-table
  "THE VIRTUAL-TABLE PILOT — a windowed table with a CALLER-SUPPLIED row
  slot, built the way a component-library author would build it, plus the
  two applications that call it.

  Everything here is CONSUMER code: `v/defview`, `v/sub`, `v/render-fn`,
  `v/slot`, event vectors, `reg-event` and `reg-sub`. It adds
  no substrate machinery and asks for none — there is no virtualisation
  service, no scroll registry, no measurement protocol and no grid
  framework. A virtual table is a windowed `for` over a range the
  application state decides, and the whole pilot is a test of whether the
  public surface can say that.

  ## The shape of the problem

  Ten thousand records, a viewport that shows about twenty, and a row
  whose content the CALLER owns. Three things have to be true at once:

  1. the component renders the window and NOT the collection, so the cost
     is the viewport's and not the dataset's;
  2. the caller's content composes into each row without the component
     knowing anything about it; and
  3. a row keeps its identity as the window slides, so scrolling reorders
     nothing and remounts nothing that stayed.

  ## Where the window comes from, and why nothing is measured

  The window is a PURE FUNCTION of four numbers — the scroll offset, the
  row height, the viewport height and the overscan — and [[window]] is
  that function, exported and directly testable without a host. The
  scroll offset is ordinary application state: a scroll event carries the
  offset as the reserved `::v/scroll-top` projection, so the intent is a
  plain event vector assertable by equality — no `v/event` body, no
  imperative `.-scrollTop` read. An ordinary `reg-event-db` writes it, and
  the view reads it back through `v/sub`. There is no scroll subscription
  protocol and no imperative read of the DOM during render.

  The row and viewport heights are the CALLER's, declared as props. That
  is the mainstream virtual-list contract — react-window's `FixedSizeList`
  takes `height` and `itemSize` for exactly this reason — and it is what
  keeps this component free of measurement. Measurement matters here
  because a view that measures cannot be promoted: `v/behavior` is the
  substrate's only before-paint home and the compiled grammar has no
  behavior form, so an auto-sizing table (one that reads its own
  viewport's height instead of being told it) is interpreted forever.
  Declaring the geometry is therefore not a dodge — it is the difference
  between a table that can be compiled and one that cannot, and the
  design takes the compilable half deliberately.

  ## What the LIBRARY owns and what the CALLER owns

  The library owns the window arithmetic, the scroll plumbing, the
  canvas that gives the scrollbar something to measure, the row
  positioning, and the grid a11y skeleton (`role`, `aria-rowcount`,
  `aria-rowindex`). The caller owns the row's content, supplied as one
  `v/render-fn` prop invoked per visible row with the record and its
  absolute index. The component never inspects a record beyond asking
  the caller's `:row-key` for its identity, so it is a table of anything.

  ## Two planes, per D018

  Visual values ride the CSS cascade: the root publishes its geometry as
  namespaced custom properties (`--acme-table-row-h`,
  `--acme-table-viewport-h`) so a stylesheet can align a header, a
  spinner or a zebra stripe to the same numbers the arithmetic uses,
  without the component naming a single colour. Structure is addressed
  by a closed, public part roster ([[part-ids]]) emitted as literal
  `data-part` values under one `data-component` scope.

  ## What is NOT here

  Column definitions, sorting, selection, resizing, sticky headers,
  variable row heights and horizontal virtualisation. All of them are
  the caller's, and every one of them would be a grid framework rather
  than a proof about the view substrate. A caller that wants a header
  writes a header."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; ===========================================================================
;; THE COMPONENT LIBRARY — `acme.ui`
;; ===========================================================================

(def component-id
  "The `data-component` marker that scopes this component's part names.
  A library decision; the substrate reserves nothing for `acme.*`."
  "acme/data-table")

(def part-ids
  "The PUBLIC part roster, in emission order — every region a caller's
  stylesheet or structural test may address, and no others.

  A CLOSED set. `root` scopes and publishes the geometry tokens,
  `viewport` is the scrolling box, `canvas` is the full-height surface
  the scrollbar measures, and `row` is one positioned row. Everything
  INSIDE a row belongs to the caller and carries no address of ours."
  [:root :viewport :canvas :row])

(def tables-root
  "Where this library keeps its per-instance scroll state — a library
  decision, and ordinary app-db, so every table's position is visible to
  tools and serialisable.

  The VALUE time-travels like any other state; whether a MOUNTED viewport
  follows it is a separate question the table answers with `:restore-scroll?`
  (see [[data-table]]). Without restoration the entry is a DOM-DERIVED CACHE —
  the viewport writes it on scroll and reads it to pick the window, but
  nothing writes it back, so a fresh or time-travelled state renders a remote
  window while the viewport still sits at scrollTop 0. A table that needs the
  reverse direction opts into the `:layout` [[restore-scroll]] behavior, which
  is the honest cost: a viewport a behavior restores cannot be promoted,
  because the compiled grammar admits no behavior form."
  :acme.ui/tables)

(def default-overscan
  "Rows rendered beyond each edge of the viewport. A library default the
  caller may override, not a substrate constant."
  4)

;; ---------------------------------------------------------------------------
;; The window — one pure function of four numbers
;; ---------------------------------------------------------------------------

(defn rows-per-viewport
  "How many rows a viewport of `viewport-h` can show at `row-h`, counting
  the partially visible one at the bottom edge.

  Integer arithmetic on purpose: a ceiling computed with floating point
  answers differently on the two hosts at the boundary, and this number
  decides how many rows commit."
  [viewport-h row-h]
  (inc (quot (+ (long viewport-h) (dec (long row-h))) (long row-h))))

(defn window
  "The half-open row range `[start end)` a table shows, as data.

      (window {:total 10000 :row-h 32 :viewport-h 640 :scroll-top 0})
      ;; => {:start 0 :end 25 :count 25 :skipped 9975}

  `:count` is how many rows RENDER and `:skipped` is how many do not;
  the two always sum to `:total`, which is what makes \"windowing commits
  only visible rows\" an exact claim rather than a hopeful one.

  Pure, total, and host-identical — so the arithmetic is assertable
  without a viewport, a browser or a render."
  [{:keys [total row-h viewport-h scroll-top overscan]}]
  (let [total    (long total)
        row-h    (long row-h)
        overscan (long (or overscan default-overscan))
        top      (max 0 (long (or scroll-top 0)))
        span     (rows-per-viewport viewport-h row-h)
        first-r  (min (quot top row-h) (max 0 (dec total)))
        start    (max 0 (- first-r overscan))
        end      (max start (min total (+ first-r span overscan)))]
    {:start   start
     :end     end
     :count   (- end start)
     :skipped (- total (- end start))}))

;; ---------------------------------------------------------------------------
;; The dataflow — one read, two transitions
;; ---------------------------------------------------------------------------

(defn register!
  "Register the library's whole dataflow. One subscription and two
  events is the entire state model of a virtual table."
  []
  (rf/reg-sub :acme.ui.table/scroll-top
    (fn [db [_ k]] (get-in db [tables-root k :scroll-top] 0)))

  (rf/reg-event :acme.ui.table/scrolled
    (fn [{:keys [db]} [_ k top]]
      {:db (assoc-in db [tables-root k :scroll-top] (max 0 (long top)))}))

  ;; THE LIFETIME FENCE. One end event drops the instance's record. The
  ;; owner of the table — the route, the dialog, the panel — dispatches
  ;; it on the way out; the render never cleans up after itself.
  (rf/reg-event :acme.ui.table/released
    (fn [{:keys [db]} [_ k]]
      {:db (update db tables-root dissoc k)})))

;; ---------------------------------------------------------------------------
;; Restoring the offset — the one imperative seam, and its cost
;; ---------------------------------------------------------------------------
;;
;; The dataflow above is a ONE-WAY street: a scroll event writes the offset
;; into app-db, and render reads it back to pick the window. That is all a
;; DOM-DERIVED CACHE needs — the viewport is the source of truth and app-db
;; trails it. But a cache cannot be RESTORED. Seed the offset before a mount,
;; or move it by time travel under a mounted table, and render picks the
;; remote window while the fresh viewport still sits at scrollTop 0: the rows
;; are painted at 3072px and the box shows blank.
;;
;; Writing the persisted offset BACK onto the viewport is host work — it reads
;; and mutates a live DOM node before the browser paints — which is exactly
;; what `v/behavior`'s `:layout` timing is for and the ONE seam the substrate
;; sanctions for it. The cost is explicit, and it is the point: a viewport a
;; behavior restores cannot be PROMOTED, because the compiled grammar admits
;; no behavior form. So restoration is OPT-IN (`:restore-scroll?`), the default
;; table stays a compilable cache, and a table that needs the reverse
;; direction takes it knowing it has chosen the interpreted tier.

(defn- restore-scroll-top!
  "Move `node`'s `scrollTop` to `top`, but ONLY when they differ — so a write
  that would change nothing fires no spurious scroll event, and the behavior
  never overwrites an offset the viewport already holds (the ordinary
  user-scroll path, where the DOM already carries the value app-db just
  learned)."
  [node top]
  #?(:cljs
     (let [top (max 0 (long (or top 0)))]
       (when (not= (.-scrollTop node) top)
         (set! (.-scrollTop node) top))))
  nil)

(v/defbehavior restore-scroll
  "Write the persisted scroll offset back onto the viewport, before paint.

  `:config` carries `{:scroll-top n}` — the offset app-db holds for this
  table. `:connect` restores it into a freshly mounted viewport; `:update`
  follows it when the stored value MOVES (a time-travel restore, a
  programmatic set) while the DOM has not. Both write through
  [[restore-scroll-top!]], which touches the node only when it disagrees with
  the state, so an ordinary user scroll — where app-db is merely trailing the
  DOM — moves no host state at all.

  `:layout`, because a restore that ran after paint would show the wrong rows
  for one frame. Attaching this behavior is what makes a restoring table
  interpreted-only: it is the promotion cost the [[tables-root]] note names."
  {:timing  :layout
   :connect (fn [{:keys [node config]}]
              (restore-scroll-top! node (:scroll-top config))
              nil)
   :update  (fn [{:keys [node config]}]
              (restore-scroll-top! node (:scroll-top config))
              nil)})

;; ---------------------------------------------------------------------------
;; The component
;; ---------------------------------------------------------------------------

(v/defview data-table
  "A windowed table whose row content the CALLER supplies.

      [data-table {:table-key  [:ledger :q3]
                   :rows       rows
                   :row-key    :id
                   :row-h      32
                   :viewport-h 640
                   :label      \"Q3 ledger\"
                   :row        (v/render-fn [r i]
                                 [:span.cell (:code r)])}]

  `:rows` is an indexed collection, `:row-key` reads a record's stable
  identity (`:id` by default), and `:row` is a two-argument render-fn
  invoked with the record and its ABSOLUTE index — absolute, because the
  index a caller wants is the row's place in the dataset and not its
  place in the window.

  `:row` may be absent, and then the table renders its rows empty: a
  component may offer content it does not require.

  `:restore-scroll?` turns the app-db offset from a DOM-derived cache into
  CONTROLLED, restorable state: the viewport is wrapped in the `:layout`
  [[restore-scroll]] behavior, which writes the persisted offset back onto a
  freshly mounted or time-travelled viewport before paint. It is off by
  default because attaching a behavior makes the table interpreted-only — the
  compiled grammar has no behavior form — so a caller opts in for a table
  whose scroll position must survive a mount or a restore, and pays the
  promotion cost knowingly."
  {:props [:map
           [:table-key :any]
           [:rows :any]
           [:row-h :int]
           [:viewport-h :int]
           [:label :string]
           [:row {:optional true} :any]
           [:row-key {:optional true} :any]
           [:overscan {:optional true} :int]
           [:restore-scroll? {:optional true} :boolean]]}
  [{:keys [table-key rows row row-h viewport-h label row-key overscan
           restore-scroll?]}]
  (let [total  (count rows)
        top    (v/sub [:acme.ui.table/scroll-top table-key])
        key-of (or row-key :id)
        w      (window {:total      total
                        :row-h      row-h
                        :viewport-h viewport-h
                        :scroll-top top
                        :overscan   overscan})
        start  (:start w)
        end    (:end w)
        ;; The scrolling box, bound so the `:restore-scroll?` decision can
        ;; wrap it in the restore behavior without duplicating it. The
        ;; behavior owns exactly ONE node (`v/behavior`'s one-node rule), and
        ;; the viewport — the element that carries `scrollTop` — is that node.
        viewport
        [:div {:data-part     "viewport"
               :role          "grid"
               :aria-label    label
               :aria-rowcount total
               :style         {:height viewport-h :overflow-y "auto"}
               :on-scroll     [:acme.ui.table/scrolled table-key ::v/scroll-top]}
         [:div {:data-part "canvas"
                :style     {:height (* total row-h) :position "relative"}}
          ;; Two spellings the grammar decides for you, and both are worth
          ;; naming because a Reagent-shaped hand reaches for the other one:
          ;;
          ;;   * the `:let` MODIFIER, not a `let` wrapping the body — a
          ;;     compiled `for` body is a keyed element, view or fragment
          ;;     DIRECTLY, with nothing in between; and
          ;;   * `:key` in the PROPS MAP, not `^{:key …}` metadata — the
          ;;     tree carries no metadata contract, so the key is an
          ;;     ordinary reserved prop slot in both modes.
          (for [i (range start end)
                :let [r (nth rows i)]]
            [:div {:key           (key-of r)
                   :data-part     "row"
                   :role          "row"
                   :aria-rowindex (inc i)
                   :data-row-key  (str (key-of r))
                   :style         {:position "absolute"
                                   :top      (* i row-h)
                                   :height   row-h}}
             (v/slot row r i)])]]]
    [:div {:data-component component-id
           :data-part      "root"
           :style          {:--acme-table-row-h      (str row-h "px")
                            :--acme-table-viewport-h (str viewport-h "px")}}
     (if restore-scroll?
       ;; Restorable: the viewport follows the persisted offset before paint.
       ;; `:config` is DATA — the offset alone — and the behavior reads it on
       ;; connect and on every move, which is what closes the reverse
       ;; direction render alone cannot.
       [v/behavior {:use restore-scroll :config {:scroll-top top}} viewport]
       ;; Cache-only: the offset trails the DOM and nothing writes it back.
       viewport)]))

;; ===========================================================================
;; THE APPLICATIONS — two callers, two shapes of row content
;; ===========================================================================

(def ledger-key
  "The ledger table's instance key. A caller pays exactly one key."
  [:acme.app/ledger :q3])

(def grid-key
  "The editing grid's instance key."
  [:acme.app/grid :sheet-1])

(def row-h 32)
(def viewport-h 640)

(def grid-columns
  "Ten columns; ten windowed rows of them is the hundred-cell editing
  workload."
  [:c0 :c1 :c2 :c3 :c4 :c5 :c6 :c7 :c8 :c9])

(defn ledger-rows
  "`n` generated records — the B3 workload's dataset, generated rather
  than fixtured because ten thousand literal maps is not a fixture."
  [n]
  (mapv (fn [i]
          {:id     (str "r" i)
           :code   (str "AC-" i)
           :amount (* 3 i)})
        (range n)))

(defn grid-rows
  [n]
  (mapv (fn [i] {:id (str "g" i)}) (range n)))

(defn register-app!
  "The applications' own dataflow: one cell value, one edit."
  []
  (rf/reg-sub :acme.app.grid/cell
    (fn [db [_ row-id col]] (get-in db [:acme.app/cells row-id col] "")))

  (rf/reg-event :acme.app.grid/cell-edited
    (fn [{:keys [db]} [_ row-id col text]]
      {:db (assoc-in db [:acme.app/cells row-id col] text)})))

(v/defview ledger-row-cells
  "The ledger's row content, as a declared child — so the pilot proves a
  slot renders a BOUNDARY and not merely markup, and so the caller's
  content is itself a promotable unit."
  {:props [:map [:record :any] [:index :int]]}
  [{:keys [record index]}]
  [:span.acme-row-body
   [:span.acme-cell {:role "gridcell" :data-col "index"} (str index)]
   [:span.acme-cell {:role "gridcell" :data-col "code"} (:code record)]
   [:span.acme-cell {:role "gridcell" :data-col "amount"} (str (:amount record))]])

(v/defview ledger
  "The caller. It supplies the row content and knows nothing about the
  window; the table supplies the window and knows nothing about the
  content."
  {:props [:map [:rows :any]]}
  [{:keys [rows]}]
  [data-table {:table-key  ledger-key
               :rows       rows
               :row-key    :id
               :row-h      row-h
               :viewport-h viewport-h
               :label      "Q3 ledger"
               :row        (v/render-fn [r i]
                             [ledger-row-cells {:record r :index i}])}])

(v/defview grid-cell
  "ONE controlled cell, and its own boundary.

  A cell is a declared child rather than an inline `v/sub` inside the
  row's loop, for the reason the compiled grammar states out loud when
  it refuses `sub-in-loop`: a read per row belongs to a boundary that can
  be recomputed on its own. That is also what makes the hundred-cell
  workload interesting — a keystroke moves ONE cell's subscription, and
  the ninety-nine others have nothing to recompute."
  {:props [:map [:row-id :string] [:col :keyword]]}
  [{:keys [row-id col]}]
  [:input.acme-grid-cell
   {:type      :text
    :data-cell (str row-id "/" (name col))
    :value     (v/sub [:acme.app.grid/cell row-id col])
    :on-input  [:acme.app.grid/cell-edited row-id col ::v/value]}])

(v/defview grid-row-cells
  "One editing row: ten controlled cells, each carrying its OWN intent as
  data. Per-cell intent is the property the substrate is actually
  claiming here — `[:acme.app.grid/cell-edited \"g7\" :c3 ::v/value]` is
  assertable by equality without a browser, for every one of the hundred
  cells."
  {:props [:map [:record :any]]}
  [{:keys [record]}]
  (let [row-id (:id record)]
    [:span.acme-grid-row
     (for [col grid-columns]
       [grid-cell {:key col :row-id row-id :col col}])]))

(v/defview editing-grid
  "The hundred-cell editing workload: a windowed table whose row slot
  renders ten controlled inputs. The window keeps the cell count bounded
  by the viewport rather than by the dataset, which is the whole reason
  an editing grid is a virtual table in the first place."
  {:props [:map [:rows :any]]}
  [{:keys [rows]}]
  [data-table {:table-key  grid-key
               :rows       rows
               :row-key    :id
               :row-h      row-h
               ;; Ten rows of ten cells — the hundred-cell window.
               :viewport-h (* 9 row-h)
               :overscan   0
               :label      "Editing grid"
               :row        (v/render-fn [r _i]
                             [grid-row-cells {:record r}])}])
