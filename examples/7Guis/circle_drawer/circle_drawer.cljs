(ns circle-drawer.circle-drawer
  "7GUIs #6 — Circle Drawer.

   A canvas. Click to add a circle at the click position with a default
   diameter. Right-click a circle to open a context menu (Adjust diameter
   or Delete). 'Adjust diameter' opens a slider in a modal dialog; closing
   the dialog commits. Undo/Redo buttons step through the history.

   The 7GUIs page calls this out as a test of *undo/redo*. The classic trap
   is to maintain an ad-hoc undo stack inside the component. The re-frame2
   approach: an interceptor that snapshots app-db before each undoable event,
   and Undo/Redo events that pop/push from the snapshot stacks.

   Demonstrates:
   - Undo / redo via interceptor + sibling event           (CP-3 / interceptors)
   - Modal dialog as state, not as React component identity (P8: low hidden context)
   - Continuous slider drag without history pollution      (interceptor opt-out)
   - Schema-bound list                                     (CP-8)

   Note: a full undo library (multi-policy, per-slice, max-depth, etc.)
   would naturally live in user/library space (cf. re-frame-undo today).
   This example shows the canonical primitive pattern; productionising it is
   library work, not framework work."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame-2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.substrate.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def Circle
  [:map
   [:id      :uuid]
   [:x       :double]
   [:y       :double]
   [:radius  pos-int?]])

(def DrawerState
  [:map
   [:circles   [:vector Circle]]
   [:dialog    [:maybe [:map
                        [:circle-id      :uuid]
                        [:initial-radius pos-int?]
                        [:draft-radius   pos-int?]]]]
   [:undo      [:vector :any]]                  ;; stack of prior :circles values
   [:redo      [:vector :any]]])

(rf/reg-app-schema [:drawer] DrawerState)

;; ============================================================================
;; UNDO INTERCEPTOR
;; ============================================================================
;;
;; Captures :circles before the handler runs; pushes the prior value onto :undo
;; and clears :redo. Events tagged as undoable use this interceptor in their
;; :interceptors list. Continuous events (slider drag) opt out by not using it,
;; only the *commit* event uses it.

(def undoable
  {:id    :undoable
   :before (fn before [ctx]
             ;; snapshot taken from coeffects (the pre-handler db).
             (let [db   (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc-in ctx [:coeffects :prior-circles] prior)))
   :after  (fn after [ctx]
             ;; if the handler changed db, push the prior value to :undo.
             (let [prior     (get-in ctx [:coeffects :prior-circles])
                   db-after  (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :drawer/initialise
  {:doc "Seed an empty canvas."}
  (fn handler-drawer-initialise [db _]
    (assoc db :drawer {:circles [] :dialog nil :undo [] :redo []})))

(rf/reg-event-db :drawer/add-circle
  {:doc "Click on canvas. Adds a circle of default radius."}
  [undoable]
  (fn handler-drawer-add-circle [db [_ x y]]
    (update-in db [:drawer :circles] conj
               {:id (random-uuid) :x x :y y :radius 30})))

(rf/reg-event-db :drawer/open-dialog
  {:doc "Right-clicked a circle. Opens the adjust-diameter dialog. Not undoable."}
  (fn handler-drawer-open-dialog [db [_ circle-id]]
    (let [{:keys [radius]} (->> (get-in db [:drawer :circles])
                                (filter #(= circle-id (:id %)))
                                first)]
      (assoc-in db [:drawer :dialog] {:circle-id      circle-id
                                      :initial-radius radius
                                      :draft-radius   radius}))))

(rf/reg-event-db :drawer/dialog-drag
  {:doc "Slider movement during the dialog. Updates the draft radius only;
         the circle itself is not mutated until the dialog commits.
         Continuous; does NOT push undo."}
  (fn handler-drawer-dialog-drag [db [_ new-radius]]
    (assoc-in db [:drawer :dialog :draft-radius] new-radius)))

(rf/reg-event-db :drawer/close-dialog
  {:doc "Dialog closed (committing the new radius). The :circles vector
         was untouched while the slider moved, so the undoable
         interceptor's prior-snapshot is exactly the pre-dialog state —
         the whole edit collapses into a single undo step."}
  [undoable]
  (fn handler-drawer-close-dialog [db _]
    (let [{:keys [circle-id draft-radius]} (get-in db [:drawer :dialog])]
      (-> db
          (update-in [:drawer :circles]
                     (fn [cs]
                       (mapv #(if (= circle-id (:id %))
                                (assoc % :radius draft-radius)
                                %)
                             cs)))
          (assoc-in [:drawer :dialog] nil)))))

(rf/reg-event-db :drawer/undo
  {:doc "Pop one snapshot from :undo, push current :circles to :redo."}
  (fn handler-drawer-undo [db _]
    (let [{:keys [undo redo circles]} (:drawer db)]
      (if (empty? undo)
        db
        (-> db
            (assoc-in [:drawer :circles] (peek undo))
            (update-in [:drawer :undo] pop)
            (update-in [:drawer :redo] (fnil conj []) circles))))))

(rf/reg-event-db :drawer/redo
  {:doc "Pop one snapshot from :redo, push current :circles to :undo."}
  (fn handler-drawer-redo [db _]
    (let [{:keys [undo redo circles]} (:drawer db)]
      (if (empty? redo)
        db
        (-> db
            (assoc-in [:drawer :circles] (peek redo))
            (update-in [:drawer :redo] pop)
            (update-in [:drawer :undo] (fnil conj []) circles))))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :drawer/circles (fn [db _] (get-in db [:drawer :circles])))
(rf/reg-sub :drawer/dialog  (fn [db _] (get-in db [:drawer :dialog])))
(rf/reg-sub :drawer/can-undo?
  (fn [db _] (seq (get-in db [:drawer :undo]))))
(rf/reg-sub :drawer/can-redo?
  (fn [db _] (seq (get-in db [:drawer :redo]))))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view drawer-view []
  (let [circles    @(subscribe [:drawer/circles])
        dialog     @(subscribe [:drawer/dialog])
        can-undo?  @(subscribe [:drawer/can-undo?])
        can-redo?  @(subscribe [:drawer/can-redo?])]
    [:div.drawer
     [:div.row
      [:button {:on-click #(dispatch [:drawer/undo]) :disabled (not can-undo?)} "Undo"]
      [:button {:on-click #(dispatch [:drawer/redo]) :disabled (not can-redo?)} "Redo"]]
     [:svg {:width 600 :height 400 :style {:border "1px solid #999"}
            :on-click (fn [e]
                        (let [rect (.. e -currentTarget getBoundingClientRect)
                              x    (- (.. e -clientX) (.-left rect))
                              y    (- (.. e -clientY) (.-top rect))]
                          (dispatch [:drawer/add-circle x y])))}
      (for [{:keys [id x y radius]} circles]
        ^{:key id}
        [:circle {:cx x :cy y :r radius :fill "transparent" :stroke "black"
                  :on-context-menu (fn [e]
                                     (.preventDefault e)
                                     (dispatch [:drawer/open-dialog id]))}])]

     (when dialog
       [:div.dialog {:style {:border "1px solid #999" :padding "10px" :margin-top "5px"}}
        [:p (str "Adjust diameter of circle " (:circle-id dialog))]
        [:input {:type      "range"
                 :min       5 :max 100 :step 1
                 :value     (:draft-radius dialog)
                 :on-change #(dispatch [:drawer/dialog-drag
                                        (js/parseInt (.. % -target -value))])}]
        [:button {:on-click #(dispatch [:drawer/close-dialog])} "Close"]])]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn drawer-tests []
  (with-frame [f (rf/make-frame {:on-create [:drawer/initialise]})]
    ;; Add three circles; undo stack grows.
    (rf/dispatch-sync [:drawer/add-circle 100 100] {:frame f})
    (rf/dispatch-sync [:drawer/add-circle 200 200] {:frame f})
    (rf/dispatch-sync [:drawer/add-circle 300 300] {:frame f})
    (assert (= 3 (count (rf/compute-sub [:drawer/circles] (rf/get-frame-db f)))))
    (assert       (rf/compute-sub [:drawer/can-undo?] (rf/get-frame-db f)))

    ;; Two undos → one circle.
    (rf/dispatch-sync [:drawer/undo] {:frame f})
    (rf/dispatch-sync [:drawer/undo] {:frame f})
    (assert (= 1 (count (rf/compute-sub [:drawer/circles] (rf/get-frame-db f)))))
    (assert (rf/compute-sub [:drawer/can-redo?] (rf/get-frame-db f)))

    ;; Redo restores.
    (rf/dispatch-sync [:drawer/redo] {:frame f})
    (assert (= 2 (count (rf/compute-sub [:drawer/circles] (rf/get-frame-db f)))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; React root named `react-root` (not `root`) so it does NOT collide
;; with the `drawer-view` reg-view above.
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:drawer/initialise])
  (rdc/render react-root [drawer-view]))
