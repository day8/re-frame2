(ns re-frame.hicasso.examples.grid.scaling-dom-cljs-test
  "L3 — THE NARROW-UPDATE SCALING CLAIM, MEASURED (rf2-hic-078).

  > Narrow-update body work scales with changed rows rather than all
  > mounted rows.
  >
  > — product specification §6, absolute correctness and user-visible
  > budgets

  That is a claim about a COUNT AT TWO SIZES, and this file is the only
  place in the bead where it is measured rather than reasoned about. The
  same application is mounted at 5x5 and at 10x10, one cell is typed
  into, and the boundary bodies that ran are counted. If the number is
  the same at both sizes the shape scales; if it grows, it does not.

  ## The instrument, and what it can and cannot see

  `re-frame.hicasso.impl.collector/body-runs` counts bodies React
  actually ran — a `React.memo` bail-out shows up as an increment that
  did not happen, so it measures adoption rather than inferring it from
  the comparator. A test may reach it; the applications may not, and
  `examples.witness-surface-cljs-test` is what says so.

  **It cannot see a props compare.** That matters for reading the table
  below honestly: the guide's coarse shape with SCALAR props runs the
  parent and one child, exactly like the narrow shape, while allocating N
  elements and comparing N props maps. Those are real costs and this
  instrument is blind to them. The shape that this gate CAN see is the
  one the guide names in the same breath — a cell prop carrying a fresh
  closure, which never compares equal — and that is the sabotage below.

  ## The three shapes, and the numbers this file pins

  | shape | 5x5 | 10x10 | grows with N? |
  |---|---|---|---|
  | the application (fine reads) | 2 | 2 | no |
  | coarse read, scalar props | 2 | 2 | no — invisible here, see above |
  | coarse read, closure props | 26 | 101 | YES |

  Two, and not one, in the fine row: the cell's body and its row's total,
  because the total genuinely depends on the cell that changed. That is
  *changed rows* in the specification's own sense — the work follows the
  data — and a witness that had contrived the total away would have
  measured a grid in which nothing was connected to anything.

  ## The coarse shapes live HERE

  They are anti-patterns, and an anti-pattern belongs in the positive
  control that has to be able to go red rather than in an application
  that is evidence about the door. `examples.grid.subs` deliberately
  registers no whole-grid read, so the coarse subscription below is
  minted in this namespace too — a cell in the application cannot be
  written coarsely without somebody adding one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.grid.app :as app]
            [re-frame.hicasso.examples.grid.events :as events]
            [re-frame.hicasso.examples.grid.subs :as subs]
            [re-frame.hicasso.examples.grid.views :as views]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The two coarse shapes — the guide's named anti-patterns, as controls
;; ---------------------------------------------------------------------------

(rf/reg-sub ::all-cells
  {:doc "The whole-grid view-model the application refuses to offer.

  Every keystroke changes this value, which is the whole of what makes
  the shapes below coarse."}
  (fn [db _] (:cells db)))

(h/defview coarse-cell
  "A cell rendered FROM PROPS. Reads nothing; its value arrives from a
  parent that read the whole grid."
  [{:keys [row col value]}]
  [:td [:input {:type "text" :value value
                :on-input [::events/edit row col ::h/value]}]])

(h/defview coarse-closure-cell
  "The same, plus the thing the guide names as the case where nothing
  bails: a callback minted in the parent's render.

  `codec/boundary-props=` compares function-valued props CONSERVATIVELY
  UNEQUAL — correct, because distinct functions must not bail out — so a
  props map carrying a fresh closure re-renders every time its parent
  does."
  [{:keys [row col value on-edit]}]
  [:td [:input {:type "text" :value value :data-row (str row) :data-col (str col)
                :on-input on-edit}]])

(h/defview coarse-grid
  "The guide's `;; Don't` shape: one whole-grid read at the top, cells
  rendered from props."
  [{:keys [closures?]}]
  (let [cells (h/sub [::all-cells])
        {:keys [rows cols]} (h/sub [::subs/dimensions])]
    [:table
     [:tbody
      (for [row (range rows)]
        [:tr {:key (str row)}
         (for [col (range cols)]
           (if closures?
             [coarse-closure-cell
              {:key     (str col)
               :row     row
               :col     col
               :value   (get cells [row col])
               ;; A FRESH closure per cell per render. Written with the
               ;; one callback form, which is the spelling an author
               ;; reaches for when they want the event itself.
               :on-edit (h/hfn [e] [::events/edit row col (.. e -target -value)])}]
             [coarse-cell {:key   (str col)
                           :row   row
                           :col   col
                           :value (get cells [row col])}]))])]]))

;; The fixture snapshots the registrar at the moment THIS form is
;; evaluated, so it sits below the registrations above — a `use-fixtures`
;; at the top of the file would strand `::all-cells` and every coarse
;; mount would refuse.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The instruments
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root needs a real DOM — " why)))

(def ^:private small {:rows 5 :cols 5})
(def ^:private full {:rows 10 :cols 10})

(defn- mount-grid!
  ([dimensions] (mount-grid! dimensions [views/grid {}]))
  ([dimensions form]
   (hm/mount! form {:initial-events (app/initial-events dimensions)})))

(defn- cell-node [m row col]
  (.querySelector (:container m) (str "#" (events/cell-id row col))))

(defn- set-native-value! [n v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)))

(defn- type-into! [n text]
  (set-native-value! n (str (.-value n) text))
  (.dispatchEvent n (js/Event. "input" #js {:bubbles true}))
  nil)

(defn- bodies-run
  "How many boundary bodies ran while `f` did — a delta, because the
  counter is monotone and page-wide."
  [f]
  (collector/reset-body-runs!)
  (f)
  (collector/body-runs))

(defn- amplification
  "The bodies one keystroke into cell `[row col]` runs, on a grid of
  `dimensions` built from `form`."
  [dimensions form row col]
  (let [m (mount-grid! dimensions form)
        n (or (cell-node m row col)
              (.querySelector (:container m)
                              (str "[data-row='" row "'][data-col='" col "']"))
              (nth (array-seq (.querySelectorAll (:container m) "input"))
                   (+ (* row (:cols dimensions)) col)))]
    (try
      (bodies-run #(do (type-into! n "1") (hm/settle! m)))
      (finally (hm/unmount! m)))))

;; ---------------------------------------------------------------------------
;; The application scales
;; ---------------------------------------------------------------------------

(deftest the-grid-mounts-a-hundred-controlled-cells
  ;; The premise, before anything is measured about it. A scaling claim
  ;; over a page that rendered five cells would be arithmetic.
  (if-not (browser?)
    (skip! "a hundred mounted controls")
    (let [m (mount-grid! full)]
      (is (= 100 (.-length (.querySelectorAll (:container m) "input"))))
      (is (= 10 (.-length (.querySelectorAll (:container m) ".total"))))
      (is (= "34" (.-value (cell-node m 3 4))))
      (hm/unmount! m))))

(deftest a-keystroke-costs-the-same-at-25-cells-and-at-100
  (if-not (browser?)
    (skip! "the scaling claim")
    (let [at-25  (amplification small [views/grid {}] 3 4)
          at-100 (amplification full [views/grid {}] 3 4)]
      (is (= 2 at-25 at-100)
          "THE ACCEPTANCE. Two bodies — the cell that was typed into and
           its row's total, which genuinely depends on it — at both sizes.
           Quadrupling the mounted cells changed nothing, which is
           specification §6's `narrow-update body work scales with changed
           rows rather than all mounted rows`, measured.

           Two and not more is also what pins the layout bodies OUT of the
           path: `grid` and the ten `grid-row`s read only the dimensions,
           so a keystroke does not notify them and they are not counted
           here. Were either on the path this number would be at least
           three at 5x5 and at least twelve at 10x10.")

      (testing "and a refused keystroke costs the same as an accepted one"
        ;; The model does not move, so nothing is notified and no body
        ;; runs at all. Worth pinning: a runtime that re-rendered on every
        ;; dispatch rather than on every changed READ would answer 2 here.
        (let [m (mount-grid! full)
              n (cell-node m 3 4)]
          (is (= 0 (bodies-run #(do (type-into! n "x") (hm/settle! m))))
              "a refusal notifies nothing, because the subscription's
               equality gate stops a value that did not move")
          (is (= "34" (.-value n))
              "and the field echoes the COMMITTED value — the refusal half
               of the controlled law, on the glass")
          (hm/unmount! m)))))

  (testing "a BROAD update costs what it changed, and not more"
    (if-not (browser?)
      (skip! "the broad contrast")
      (let [m (mount-grid! full)]
        (is (= 11 (bodies-run #(hm/dispatch-and-settle! m [::events/clear-row {:row 2}])))
            "ten cells and one total. The narrow number has something to
             be narrow COMPARED TO, and the same topology produces both —
             work follows the data, in either direction")
        (hm/unmount! m)))))

;; ---------------------------------------------------------------------------
;; The sabotage — a coarse read makes it red
;; ---------------------------------------------------------------------------

(deftest the-coarse-shape-with-fresh-closures-scales-with-the-GRID
  ;; THE POSITIVE CONTROL. Without it the row above is green having proved
  ;; only that this instrument can count to two, and the whole gate could
  ;; be satisfied by a runtime that never re-rendered anything.
  (if-not (browser?)
    (skip! "the sabotage")
    (let [at-25  (amplification small [coarse-grid {:closures? true}] 3 4)
          at-100 (amplification full [coarse-grid {:closures? true}] 3 4)]
      (is (= 26 at-25)
          "the parent plus every one of 25 cells")
      (is (= 101 at-100)
          "the parent plus every one of 100 cells — and the number GREW
           with the grid, which is the failure mode specification §6
           names. A cell prop carrying a closure minted in the parent's
           render never compares equal (`codec/boundary-props=` is
           conservative about functions, and correctly so), so nothing
           bails")
      (is (< at-25 at-100)
          "stated as the shape rather than as two constants: this is what
           `converts grid size into per-keystroke cost` looks like from
           the instrument, and the application's own row is `=` where this
           one is `<`"))))

(deftest the-coarse-shape-with-scalar-props-is-INVISIBLE-to-this-instrument
  ;; The honest limit, measured rather than asserted. The guide says the
  ;; bail-out keeps 99 bodies from running when the props are plain
  ;; values, and it does — so this shape counts the same as the narrow one
  ;; while allocating N elements and comparing N props maps per keystroke.
  ;;
  ;; It is recorded because a reader of the acceptance row above should
  ;; know exactly what it does and does not certify: `body-runs` is a
  ;; measure of BODIES, and the topology claim it supports is about
  ;; notification, not about allocation.
  (if-not (browser?)
    (skip! "the instrument's limit")
    (let [at-25  (amplification small [coarse-grid {:closures? false}] 3 4)
          at-100 (amplification full [coarse-grid {:closures? false}] 3 4)]
      (is (= 2 at-25 at-100)
          "the parent and the one cell whose props moved. Two, exactly as
           the application answers — so this gate CANNOT distinguish the
           two shapes, and a witness that claimed it could would be
           overstating its instrument. What separates them is the parent
           being notified at all, and the N props compares and N element
           allocations that follow; the shape is still wrong, and it is
           wrong for reasons this counter is blind to")
      (is (= at-25 at-100)
          "and it does not grow, which is why the sabotage above needs the
           closure to be a sabotage at all"))))
