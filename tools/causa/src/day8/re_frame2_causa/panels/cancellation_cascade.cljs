(ns day8.re-frame2-causa.panels.cancellation-cascade
  "Cancellation-cascade visualiser (rf2-59e7k, parent rf2-5aw5v).

  Per `tools/causa/spec/019-Cross-Cutting-Insight.md` §M.3 / the
  cancellation-cascade section: when a parent machine decision triggers
  a destroy, every in-flight effect the child held aborts (per Spec
  014 §Abort on actor destroy / rf2-wvkn). Today those traces scatter
  through the Trace tab as a flurry; this visualiser folds them into
  ONE vertical waterfall:

      PARENT DECISION
        └─ [destroy-child] dispatched at 1234ms by :auth/logout
      CHILD TEARDOWN
        └─ child:user-session destroying at 1240ms (5 in-flight fxs)
      EFFECT ABORTS (in order)
        ├─ HTTP GET /api/profile  aborted at 1242ms (:actor-destroyed)
        ├─ HTTP POST /api/log     aborted at 1242ms (:actor-destroyed)
        ├─ WS send :heartbeat     aborted at 1243ms (:actor-destroyed)
        ├─ :after timer fire      aborted at 1243ms (:actor-destroyed)
        └─ machine-invoke fetch   aborted at 1244ms (:actor-destroyed)

  Each row is clickable → jumps to the underlying trace entry via the
  spine shim (`:rf.causa/select-dispatch-id`).

  ## Two mounts

    1. **Machines tab side-panel** — when the focused machine had a
       cancellation-anchor in the trace window, the visualiser mounts
       inline beside the chart. Reads
       `:rf.causa/cancellation-cascade-for-focused-machine`.

    2. **Popover** — opened from anywhere via
       `:rf.causa/cancellation-cascade-open` (e.g. a Trace row's
       'Show cancellation cascade' affordance). Reads
       `:rf.causa/cancellation-cascade-for-focused-event` (composes
       popover-focus → spine focus).

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — no Reagent / UIx /
  Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.cancellation-cascade-events :as events]
            [day8.re-frame2-causa.panels.cancellation-cascade-helpers :as h]
            [day8.re-frame2-causa.panels.cancellation-cascade-subs :as subs]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack type-scale]]))

;; ---- row styling primitives ----------------------------------------------

(def ^:private row-glyph
  {:parent-decision "●"
   :child-teardown  "└─"
   :effect-abort    "├─"
   :effect-abort-last "└─"})

(defn- row-colour
  "Per the bead's contract: decision = blue, teardown = orange,
  abort = red. Reads tokens off the theme map so the visualiser
  follows the active theme."
  [kind]
  (case kind
    :parent-decision (:cyan tokens)              ; blue-ish in the dark theme
    :child-teardown  (or (:accent-orange tokens)
                         (:warning-amber tokens)
                         (:yellow tokens))
    :effect-abort    (or (:red tokens)
                         (:error-red tokens)
                         (:danger tokens))
    (:text-secondary tokens)))

;; ---- header --------------------------------------------------------------

(defn- header
  "Top strip — title + the cascade summary + close button (popover
  mount only). `close-fn` is the on-click for the close button; nil
  in the side-panel mount."
  [cascade close-fn]
  [:div {:data-testid "rf-causa-cancellation-cascade-header"
         :style {:display "flex"
                 :align-items "center"
                 :justify-content "space-between"
                 :gap "12px"
                 :padding "10px 14px"
                 :background (:bg-2 tokens)
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:div
    [:div {:style {:font-family sans-stack
                   :font-size (:body type-scale)
                   :font-weight 600
                   :color (:text-primary tokens)}}
     "Cancellation cascade"]
    [:div {:data-testid "rf-causa-cancellation-cascade-summary"
           :style {:font-family sans-stack
                   :font-size "11px"
                   :color (:text-tertiary tokens)
                   :margin-top "2px"}}
     (h/cascade-summary cascade)]]
   (when close-fn
     [:button {:data-testid "rf-causa-cancellation-cascade-close"
               :on-click    close-fn
               :style       {:background  "transparent"
                             :border      "none"
                             :color       (:text-secondary tokens)
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "14px"}}
      "×"])])

;; ---- parent-decision row ------------------------------------------------

(defn- parent-decision-row
  "Render the parent-decision row at the top of the waterfall."
  [decision]
  (let [clickable? (boolean (:dispatch-id decision))]
    [:div {:data-testid "rf-causa-cancellation-cascade-decision-row"
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.causa/focus-trace-entry
                               {:dispatch-id (:dispatch-id decision)
                                :trace-id    (:trace-id decision)}]
                              {:frame :rf/causa})))
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "8px"
                   :padding       "8px 14px"
                   :cursor        (if clickable? "pointer" "default")
                   :font-family   mono-stack
                   :font-size     "12px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (row-colour :parent-decision)
                     :font-weight 700}}
      (:parent-decision row-glyph)]
     [:span {:style {:color       (:text-tertiary tokens)
                     :font-size   "10px"
                     :min-width   "70px"
                     :white-space "nowrap"}}
      (h/format-time-ms (:t decision))]
     [:span {:style {:color (:text-secondary tokens)
                     :text-transform "uppercase"
                     :font-size "9px"
                     :letter-spacing "0.5px"}}
      "DECISION"]
     [:span {:style {:color (:text-primary tokens)
                     :flex 1
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}}
      (h/format-event-vec (:event-vec decision))]
     (when-let [m (:machine-id decision)]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :white-space "nowrap"}}
        (str " by " m)])]))

;; ---- teardown row --------------------------------------------------------

(defn- teardown-row
  [{:keys [child-id t inflight-count reason dispatch-id trace-id]}]
  (let [clickable? (boolean dispatch-id)]
    [:div {:data-testid (str "rf-causa-cancellation-cascade-teardown-row-"
                             (str child-id))
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.causa/focus-trace-entry
                               {:dispatch-id dispatch-id
                                :trace-id    trace-id}]
                              {:frame :rf/causa})))
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "8px"
                   :padding       "6px 14px 6px 28px"
                   :cursor        (if clickable? "pointer" "default")
                   :font-family   mono-stack
                   :font-size     "12px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (row-colour :child-teardown)
                     :font-weight 700}}
      (:child-teardown row-glyph)]
     [:span {:style {:color       (:text-tertiary tokens)
                     :font-size   "10px"
                     :min-width   "70px"
                     :white-space "nowrap"}}
      (h/format-time-ms t)]
     [:span {:style {:color (row-colour :child-teardown)
                     :text-transform "uppercase"
                     :font-size "9px"
                     :letter-spacing "0.5px"}}
      "TEARDOWN"]
     [:span {:style {:color (:text-primary tokens)
                     :flex 1
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}}
      (str child-id)]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :white-space "nowrap"}}
      (str inflight-count
           (if (= 1 inflight-count)
             " in-flight fx"
             " in-flight fxs"))]
     (when reason
       [:span {:style {:color (:text-tertiary tokens)
                       :font-size "10px"
                       :white-space "nowrap"}}
        (str " · " reason)])]))

;; ---- abort row -----------------------------------------------------------

(defn- abort-row
  [{:keys [fx t cancel-cause url correlation-id
           dispatch-id trace-id]
    :as row}
   last?]
  (let [clickable? (boolean dispatch-id)]
    [:div {:data-testid (str "rf-causa-cancellation-cascade-abort-row-"
                             (str (or trace-id correlation-id)))
           :data-cancel-cause (str cancel-cause)
           :data-fx           (name (or fx :unknown))
           :on-click    (when clickable?
                          (fn [_]
                            (rf/dispatch
                              [:rf.causa/focus-trace-entry
                               {:dispatch-id dispatch-id
                                :trace-id    trace-id}]
                              {:frame :rf/causa})))
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "8px"
                   :padding       "6px 14px 6px 42px"
                   :cursor        (if clickable? "pointer" "default")
                   :font-family   mono-stack
                   :font-size     "12px"
                   :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:span {:style {:color (row-colour :effect-abort)
                     :font-weight 700}}
      (if last?
        (:effect-abort-last row-glyph)
        (:effect-abort row-glyph))]
     [:span {:style {:color       (:text-tertiary tokens)
                     :font-size   "10px"
                     :min-width   "70px"
                     :white-space "nowrap"}}
      (h/format-time-ms t)]
     [:span {:style {:color (row-colour :effect-abort)
                     :text-transform "uppercase"
                     :font-size "9px"
                     :letter-spacing "0.5px"}}
      "ABORT"]
     [:span {:style {:color (:text-primary tokens)
                     :flex 1
                     :overflow "hidden"
                     :text-overflow "ellipsis"
                     :white-space "nowrap"}}
      (h/format-fx-label row)]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :white-space "nowrap"}}
      (str cancel-cause)]]))

;; ---- collapsed expander row ---------------------------------------------

(defn- expand-button
  "Renders the 'Show all N' / 'Collapse' affordance under the abort
  list when collapse is active."
  [collapsed-count total-count expanded?]
  [:div {:data-testid "rf-causa-cancellation-cascade-expander"
         :style {:padding "8px 14px"
                 :text-align "center"
                 :border-bottom (str "1px solid " (:border-subtle tokens))}}
   [:button {:data-testid "rf-causa-cancellation-cascade-expand-toggle"
             :on-click    (fn [_]
                            (rf/dispatch
                              [:rf.causa/cancellation-cascade-toggle-expand]
                              {:frame :rf/causa}))
             :style       {:background "transparent"
                           :border (str "1px solid " (:border-default tokens))
                           :color (:accent-violet tokens)
                           :font-family sans-stack
                           :font-size "11px"
                           :padding "3px 10px"
                           :border-radius "10px"
                           :cursor "pointer"}}
    (if expanded?
      (str "Collapse · showing " total-count)
      (str "Show all " total-count " · "
           collapsed-count " hidden"))]])

;; ---- empty states --------------------------------------------------------

(defn- empty-state
  "Empty-state body — branches on the cascade's `:empty-kind`."
  [cascade]
  (let [kind (:empty-kind cascade)]
    [:div {:data-testid (str "rf-causa-cancellation-cascade-empty-"
                             (name (or kind :no-trigger)))
           :style {:padding "16px"
                   :color (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size "13px"
                   :text-align "center"}}
     (case kind
       :no-trigger
       [:div
        [:p {:style {:margin "0 0 6px 0"}}
         "No cancellation cascade in the trace window."]
        [:p {:style {:margin 0 :font-size "11px"}}
         "Cancellation cascades land here when a parent machine "
         "destroys a child while in-flight effects are running."]]

       :no-aborts
       [:div
        [:p {:style {:margin "0 0 6px 0"}}
         "Destroy fired — no in-flight effects to abort."]
        [:p {:style {:margin 0 :font-size "11px"}}
         "The cascade ran cleanly: the child machine was torn down "
         "without any HTTP / WS / timer / invoke cleanup."]]

       [:p {:style {:margin 0}} "No cascade data."])]))

;; ---- the body (waterfall list) -----------------------------------------

(defn- body
  "Render the waterfall body — decision + teardown rows + abort rows.
  Reads `:rf.causa/cancellation-cascade-expanded?` to decide whether
  the abort list is collapsed."
  [cascade]
  (let [{:keys [parent-decision child-teardowns effect-aborts]} cascade
        expanded? @(rf/subscribe [:rf.causa/cancellation-cascade-expanded?])
        collapse? (h/should-collapse? cascade)
        visible-aborts (cond
                         (not collapse?) effect-aborts
                         expanded?       effect-aborts
                         :else           (vec (take 5 effect-aborts)))
        hidden-count   (- (count effect-aborts) (count visible-aborts))]
    [:div {:data-testid "rf-causa-cancellation-cascade-body"
           :data-collapsed (str (and collapse? (not expanded?)))
           :data-aborts-shown (str (count visible-aborts))
           :data-aborts-total (str (count effect-aborts))
           :style {:flex 1
                   :overflow "auto"
                   :background (:bg-2 tokens)}}
     (when parent-decision
       (parent-decision-row parent-decision))
     (when (seq child-teardowns)
       [:div {:data-testid "rf-causa-cancellation-cascade-teardowns"}
        (doall
          (for [t child-teardowns]
            ^{:key (str "teardown-" (or (:trace-id t)
                                        (:child-id t)))}
            (teardown-row t)))])
     (when (seq visible-aborts)
       [:div {:data-testid "rf-causa-cancellation-cascade-aborts"}
        (doall
          (map-indexed
            (fn [idx row]
              (let [last? (= idx (dec (count visible-aborts)))]
                ^{:key (str "abort-" (or (:trace-id row)
                                         (:correlation-id row)
                                         idx))}
                (abort-row row last?)))
            visible-aborts))])
     (when (and collapse? (pos? hidden-count) (not expanded?))
       (expand-button hidden-count (count effect-aborts) expanded?))
     (when (and collapse? expanded?)
       (expand-button 0 (count effect-aborts) expanded?))]))

;; ---- public views --------------------------------------------------------

(defn render-cascade
  "Render the cascade as a self-contained block. `cascade` is the
  helpers/extract-cascade record; `close-fn` is optional (popover
  mount passes a close handler; side-panel mount passes nil).

  Always renders SOMETHING — the empty-state branches still render
  so the mount point can place this unconditionally."
  [cascade close-fn]
  [:section {:data-testid "rf-causa-cancellation-cascade"
             :data-empty-kind (when (:empty-kind cascade)
                                (name (:empty-kind cascade)))
             :style {:display "flex"
                     :flex-direction "column"
                     :height "100%"
                     :background (:bg-2 tokens)
                     :color (:text-primary tokens)
                     :font-family sans-stack
                     :border (str "1px solid " (:border-subtle tokens))
                     :border-radius "4px"
                     :overflow "hidden"}}
   (header cascade close-fn)
   (if (:empty-kind cascade)
     (empty-state cascade)
     (body cascade))])

(rf/reg-view SidePanel
  "Machines-tab side-panel mount. Reads
  `:rf.causa/cancellation-cascade-for-focused-machine` and renders
  only when the cascade is non-empty (i.e. the focused machine had a
  destroy in the trace window). Closed-state cost is one subscribe
  + a when-gate."
  []
  (let [cascade @(rf/subscribe [:rf.causa/cancellation-cascade-for-focused-machine])]
    ;; The bead's contract: mount in the side-rail WHEN a destroy
    ;; lands. Empty `:no-trigger` cascades render nothing so the
    ;; mount stays dormant most of the time.
    (when-not (= :no-trigger (:empty-kind cascade))
      (render-cascade cascade nil))))

;; ---- popover (overlay) ---------------------------------------------------

(defn- backdrop-style []
  {:position         "fixed"
   :top              0
   :left             0
   :right            0
   :bottom           0
   :background       "rgba(0,0,0,0.18)"
   :display          "flex"
   :align-items      "center"
   :justify-content  "center"
   :z-index          2147483644})

(defn- dialog-style []
  {:width            "720px"
   :max-width        "92vw"
   :height           "520px"
   :max-height       "82vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- handle-popover-keydown
  [^js e]
  (when (= "Escape" (.-key e))
    (.preventDefault e)
    (.stopPropagation e)
    (rf/dispatch [:rf.causa/cancellation-cascade-close]
                 {:frame :rf/causa})))

(rf/reg-view Popover
  "Overlay popover mount. Reads
  `:rf.causa/cancellation-cascade-popover-open?` and short-circuits to
  nil when closed (one subscribe + when-gate). When open, reads
  `:rf.causa/cancellation-cascade-for-focused-event` and renders the
  cascade body inside the dialog."
  []
  (when @(rf/subscribe [:rf.causa/cancellation-cascade-popover-open?])
    (let [cascade @(rf/subscribe [:rf.causa/cancellation-cascade-for-focused-event])
          close   (fn [_]
                    (rf/dispatch [:rf.causa/cancellation-cascade-close]
                                 {:frame :rf/causa}))]
      [:div {:data-testid "rf-causa-cancellation-cascade-popover-backdrop"
             :on-click    close
             :on-key-down handle-popover-keydown
             :tab-index   -1
             :style       (backdrop-style)}
       [:div {:data-testid "rf-causa-cancellation-cascade-popover-dialog"
              :on-click    #(.stopPropagation %)
              :on-key-down handle-popover-keydown
              :tab-index   0
              :style       (dialog-style)}
        (render-cascade cascade close)]])))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the visualiser's sub/event registrations.
  Called from `registry.cljs`'s `register-causa-handlers!` fan-out.

  The view-side `reg-view`s above are picked up at ns-load (the
  `reg-view` macro registers eagerly); this `install!` only registers
  the subs + events under the orchestrator's idempotency sentinel."
  []
  (subs/install!)
  (events/install!)
  nil)
