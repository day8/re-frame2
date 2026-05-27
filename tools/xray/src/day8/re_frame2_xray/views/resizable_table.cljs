(ns day8.re-frame2-xray.views.resizable-table
  "Shared draggable-column-resize affordance for Xray tables (rf2-uji72).

  Wraps a header row + N body rows in a CSS-grid layout where
  `grid-template-columns` is driven from column defs + a re-frame
  state slot of per-column pixel overrides. Adjacent column pairs
  share a 4px gutter; pointer-down on the gutter captures the
  starting widths off the live DOM (so the first drag works even
  before any override exists), pointer-move computes a delta,
  pointer-up releases the listeners.

  Day-1 widths are in-memory only — localStorage persistence is a
  follow-up bead. The view is mode-agnostic: consumers pass column
  defs, a row-key fn, a row-attrs fn, and a row-cells fn that
  returns one hiccup node per column. The wrapper interleaves
  empty spacer divs into the body-row gutter tracks so columns
  align with the header.

  ## Consumer API

      [resizable-table
       {:table-id   :rf.xray.epoch/subscriptions  ;; unique kw
        :columns    [{:id :sub    :label \"sub\"     :default-flex \"1fr\"}
                     {:id :inputs :label \"inputs\"  :default-flex \"1fr\"}
                     {:id :value  :label \"value\"   :default-flex \"1fr\"}]
        :rows       rows
        :row-key    (fn [row idx] (str \"sub-\" idx))
        :row-attrs  (fn [row idx] {:data-testid (str \"rf-xray-epoch-sub-row-\" idx)
                                   :style {...}})
        :row-cells  (fn [row idx] [cell-1-hiccup cell-2-hiccup cell-3-hiccup])
        :header-attrs    {...}    ;; optional
        :container-attrs {...}    ;; optional
        :header-cell-style {...}  ;; optional per-header cell style
        }]

  See `subscriptions-table` in `panels/epoch/view.cljs` for the
  canonical consumer."
  (:require [clojure.string :as string]
            [reagent.core :as r]
            [re-frame.core :as rf]))

(def ^:private gutter-width-px 4)
(def ^:private min-col-width-px 24)

;; ---- Grid-template builder ----------------------------------------------

(defn- template-track
  "Resolve one column's grid-template track — px override when
  present, the column's `:default-flex` string otherwise (typically
  `\"1fr\"` or a percentage)."
  [{:keys [id default-flex]} overrides]
  (if-let [px (get overrides id)]
    (str (max min-col-width-px (long px)) "px")
    (or default-flex "1fr")))

(defn build-template
  "Build the `grid-template-columns` string for the table — N
  tracks for columns interleaved with (N-1) `4px` gutter tracks.
  Pure-data; testable in isolation."
  [columns overrides]
  (let [tracks (map #(template-track % overrides) columns)
        gutter (str gutter-width-px "px")]
    (string/join " " (interpose gutter tracks))))

;; ---- State + events ------------------------------------------------------

(defn install!
  "Register the `:rf.xray.column-widths/*` events + sub. Called
  from `registry/register-xray-handlers!`. Re-entrant: re-frame's
  registrar tolerates same-id re-registration."
  []
  (rf/reg-sub :rf.xray.column-widths/for-table
    (fn [db [_ table-id]]
      (get-in db [:rf.xray/column-widths table-id])))

  (rf/reg-event-db :rf.xray.column-widths/resize-pair
    (fn [db [_ table-id left-id left-px right-id right-px]]
      (-> db
          (assoc-in [:rf.xray/column-widths table-id left-id]
                    (long (max min-col-width-px left-px)))
          (assoc-in [:rf.xray/column-widths table-id right-id]
                    (long (max min-col-width-px right-px))))))

  (rf/reg-event-db :rf.xray.column-widths/reset
    (fn [db [_ table-id]]
      (update db :rf.xray/column-widths dissoc table-id))))

;; ---- Pointer plumbing ----------------------------------------------------

(defn- find-col-cell
  "Locate the rendered header cell for `col-id` inside the
  grid-row element. Cells stamp `data-rf-xray-resizable-col=<id>`
  on the wrapper div the wrapper view emits."
  [grid-el col-id]
  (.querySelector grid-el
                  (str "[data-rf-xray-resizable-col='" (name col-id) "']")))

(defn- on-pointer-down
  "Begin a drag: capture both adjacent columns' starting widths
  from the live DOM (so the first drag works even when state
  carries no override), register window-level move/up handlers,
  dispatch resize-pair events on each move, clean up on up."
  [table-id left-id right-id ev]
  (.preventDefault ev)
  (.stopPropagation ev)
  (when-let [gutter-el (.-currentTarget ev)]
    (let [grid-el  (.-parentElement gutter-el)
          left-el  (find-col-cell grid-el left-id)
          right-el (find-col-cell grid-el right-id)]
      (when (and left-el right-el)
        (let [left-px0  (.-offsetWidth left-el)
              right-px0 (.-offsetWidth right-el)
              start-x   (.-clientX ev)
              on-move   (fn [m-ev]
                          (let [delta     (- (.-clientX m-ev) start-x)
                                new-left  (max min-col-width-px (+ left-px0 delta))
                                new-right (max min-col-width-px (- right-px0 delta))]
                            (rf/dispatch [:rf.xray.column-widths/resize-pair
                                          table-id
                                          left-id new-left
                                          right-id new-right])))
              on-up     (fn on-up [_]
                          (.removeEventListener js/window "pointermove" on-move)
                          (.removeEventListener js/window "pointerup" on-up))]
          (.addEventListener js/window "pointermove" on-move)
          (.addEventListener js/window "pointerup" on-up))))))

;; ---- Header gutter (interactive) ----------------------------------------

(def ^:private gutter-base-style
  {:cursor      "col-resize"
   :background  "transparent"
   :transition  "background 0.15s ease"
   :user-select "none"
   :align-self  "stretch"})

(def ^:private gutter-hover-style
  (assoc gutter-base-style :background "var(--rf-xray-accent)"))

(defn- header-gutter
  "Render one interactive drag-handle between adjacent header
  columns. Local Reagent atom tracks hover (paint the 4px column
  with the accent colour on hover); pointer-down on the cell
  starts the drag flow."
  [_props]
  (let [hover? (r/atom false)]
    (fn [{:keys [table-id left-id right-id]}]
      [:div
       {:data-testid (str "rf-xray-resizable-gutter-"
                          (name table-id) "-" (name left-id))
        :data-rf-xray-resizable-gutter (name left-id)
        :style       (if @hover? gutter-hover-style gutter-base-style)
        :on-pointer-enter #(reset! hover? true)
        :on-pointer-leave #(reset! hover? false)
        :on-pointer-down  (fn [ev] (on-pointer-down table-id left-id right-id ev))}])))

;; ---- Body row spacer (non-interactive) ----------------------------------

(def ^:private body-spacer-style
  {:align-self "stretch"})

(defn- body-spacer
  "Empty 4px-wide div for a body row's gutter track. Non-
  interactive; lives only to occupy the grid slot so column cells
  line up with the header."
  [_left-id]
  [:div {:style body-spacer-style}])

;; ---- Header + body row weavers -------------------------------------------

(defn- weave-header
  "Interleave interactive gutter handles between N header cells.
  Returns a flat seq of (2N-1) hiccup nodes."
  [header-cells columns table-id]
  (let [n (count columns)]
    (apply concat
           (map-indexed
             (fn [i cell]
               (let [col-id (:id (nth columns i))]
                 (if (< i (dec n))
                   [(with-meta cell {:key (str "h-" (name col-id))})
                    (with-meta
                      [header-gutter
                       {:table-id table-id
                        :left-id  col-id
                        :right-id (:id (nth columns (inc i)))}]
                      {:key (str "g-" (name col-id))})]
                   [(with-meta cell {:key (str "h-" (name col-id))})])))
             header-cells))))

(defn- weave-body
  "Interleave empty spacer divs between N body-row cells. Returns
  a flat seq of (2N-1) hiccup nodes."
  [row-cells columns]
  (let [n (count columns)]
    (apply concat
           (map-indexed
             (fn [i cell]
               (let [col-id (:id (nth columns i))]
                 (if (< i (dec n))
                   [(with-meta cell {:key (str "c-" (name col-id))})
                    (with-meta (body-spacer col-id)
                               {:key (str "s-" (name col-id))})]
                   [(with-meta cell {:key (str "c-" (name col-id))})])))
             row-cells))))

;; ---- Top-level view ------------------------------------------------------

(defn- header-cell
  "Wrap a header label so the wrapper carries the
  `data-rf-xray-resizable-col` marker (used by the gutter
  pointer-down to find adjacent cells via DOM query)."
  [{:keys [id label header-cell-style]}]
  [:div {:data-rf-xray-resizable-col (name id)
         :style (merge {:padding "5px 8px"
                        :min-width 0}
                       header-cell-style)}
   label])

(defn resizable-table
  "See the ns docstring for the consumer API."
  [{:keys [table-id columns rows row-key row-attrs row-cells
           header-attrs container-attrs header-cell-style]}]
  (let [overrides @(rf/subscribe [:rf.xray.column-widths/for-table table-id])
        template  (build-template columns overrides)
        grid-style {:display "grid"
                    :grid-template-columns template
                    :align-items "stretch"}]
    (into [:div (merge {:data-rf-xray-resizable-table (name table-id)}
                       container-attrs)
           ;; Header
           (into [:div (merge {:style grid-style}
                              header-attrs)]
                 (weave-header
                   (for [col columns]
                     (header-cell (merge col
                                         (when header-cell-style
                                           {:header-cell-style header-cell-style}))))
                   columns
                   table-id))]
          ;; Body rows
          (for [[i row] (map-indexed vector rows)
                :let [attrs (or (when row-attrs (row-attrs row i)) {})
                      cells (row-cells row i)]]
            ^{:key (row-key row i)}
            [:div (-> attrs
                      (assoc :style (merge (:style attrs) grid-style)))
             (into [:<>] (weave-body cells columns))]))))
