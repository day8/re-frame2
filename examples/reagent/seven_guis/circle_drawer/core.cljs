(ns seven-guis.circle-drawer.core
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
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; EP-0010 (Causal World Inputs): a circle's `:id` is written into durable
;; app-db (`[:drawer :circles]`), so it must be a function of prior frame-state
;; — never an ambient `(random-uuid)` at the durable-write site (a fresh
;; event-stream replay would mint different ids). The id is an internal handle
;; (React `:key` + context-menu target), so we mint it deterministically as a
;; monotonic `:int` from a db-held counter — the todomvc `allocate-next-id`
;; idiom — rather than a uuid. Undo/redo snapshot whole `:circles` vectors, so
;; the ids ride the snapshot and round-trip unchanged.
(def Circle
  [:map
   [:id      :int]
   [:x       :double]
   [:y       :double]
   [:radius  pos-int?]])

(def DrawerState
  [:map
   [:circles   [:vector Circle]]
   [:next-id    :int]                           ;; deterministic id allocator (EP-0010)
   [:dialog    [:maybe [:map
                        [:circle-id      :int]
                        [:initial-radius pos-int?]
                        [:draft-radius   pos-int?]]]]
   [:undo      [:vector :any]]                  ;; stack of prior :circles values
   [:redo      [:vector :any]]])

;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `run`/`reg-frame app-frame`), so name it explicitly so the
;; schema binds to the app frame whose commits it validates.
(with-frame :rf/default
  (rf/reg-app-schema [:drawer] {:schema DrawerState}))

;; ============================================================================
;; UNDO INTERCEPTOR
;; ============================================================================
;;
;; Captures :circles before the handler runs; pushes the prior value onto :undo
;; and clears :redo. Events tagged as undoable reference this interceptor BY ID
;; (`:undoable`) in their :interceptors list (EP-0022 — interceptors are
;; registered descriptors referenced by keyword, not inline values). Continuous
;; events (slider drag) opt out by not referencing it; only the *commit* event
;; uses it.

(rf/reg-interceptor :undoable
  {:doc "Snapshot :circles before an undoable handler runs; push the prior
         value onto :undo and clear :redo when the handler changed it."}
  {:before (fn before [ctx]
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

(rf/reg-event :drawer/initialise
  {:doc "Seed an empty canvas. `:next-id` is the deterministic id allocator
         (EP-0010) — circles take monotonic int ids starting at 1."}
  (fn handler-drawer-initialise [{:keys [db]} _]
    {:db (assoc db :drawer {:circles [] :next-id 1 :dialog nil :undo [] :redo []})}))

(rf/reg-event :drawer/add-circle
  {:doc "Click on canvas. Adds a circle of default radius. The id is minted
         deterministically from the db-held `:next-id` counter (EP-0010 causal
         world inputs — a durable id must be a function of prior frame-state,
         not an ambient `(random-uuid)` read), then the counter is bumped. The
         counter is NOT in the undoable `:circles` snapshot, so it advances
         monotonically across undo/redo and never re-mints a live id."
   :interceptors [:undoable]}
  (fn handler-drawer-add-circle [{:keys [db]} [_ x y]]
    {:db (let [id (get-in db [:drawer :next-id])]
      (-> db
          (update-in [:drawer :circles] conj {:id id :x x :y y :radius 30})
          (assoc-in  [:drawer :next-id] (inc id))))}))

(rf/reg-event :drawer/open-dialog
  {:doc "Right-clicked a circle. Opens the adjust-diameter dialog. Not undoable."}
  (fn handler-drawer-open-dialog [{:keys [db]} [_ circle-id]]
    {:db (let [{:keys [radius]} (->> (get-in db [:drawer :circles])
                                (filter #(= circle-id (:id %)))
                                first)]
      (assoc-in db [:drawer :dialog] {:circle-id      circle-id
                                      :initial-radius radius
                                      :draft-radius   radius}))}))

(rf/reg-event :drawer/dialog-drag
  {:doc "Slider movement during the dialog. Updates the draft radius only;
         the circle itself is not mutated until the dialog commits.
         Continuous; does NOT push undo."}
  (fn handler-drawer-dialog-drag [{:keys [db]} [_ new-radius]]
    {:db (assoc-in db [:drawer :dialog :draft-radius] new-radius)}))

(rf/reg-event :drawer/close-dialog
  {:doc "Dialog closed (committing the new radius). The :circles vector
         was untouched while the slider moved, so the undoable
         interceptor's prior-snapshot is exactly the pre-dialog state —
         the whole edit collapses into a single undo step."
   :interceptors [:undoable]}
  (fn handler-drawer-close-dialog [{:keys [db]} _]
    {:db (let [{:keys [circle-id draft-radius]} (get-in db [:drawer :dialog])]
      (-> db
          (update-in [:drawer :circles]
                     (fn [cs]
                       (mapv #(if (= circle-id (:id %))
                                (assoc % :radius draft-radius)
                                %)
                             cs)))
          (assoc-in [:drawer :dialog] nil)))}))

(rf/reg-event :drawer/undo
  {:doc "Pop one snapshot from :undo, push current :circles to :redo."}
  (fn handler-drawer-undo [{:keys [db]} _]
    {:db (let [{:keys [undo redo circles]} (:drawer db)]
      (if (empty? undo)
        db
        (-> db
            (assoc-in [:drawer :circles] (peek undo))
            (update-in [:drawer :undo] pop)
            (update-in [:drawer :redo] (fnil conj []) circles))))}))

(rf/reg-event :drawer/redo
  {:doc "Pop one snapshot from :redo, push current :circles to :undo."}
  (fn handler-drawer-redo [{:keys [db]} _]
    {:db (let [{:keys [undo redo circles]} (:drawer db)]
      (if (empty? redo)
        db
        (-> db
            (assoc-in [:drawer :circles] (peek redo))
            (update-in [:drawer :redo] pop)
            (update-in [:drawer :undo] (fnil conj []) circles))))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

;; Layer-2 slice sub. The Layer-3 readers below chain off this one via
;; `:<-`, so the [:drawer ...] traversal happens once per app-db swap
;; (in `:drawer/slice`) rather than once per dependent recompute. Matches
;; the realworld layering pattern (`:articles/slice → :articles/data → ...`).

(rf/reg-sub :drawer/slice (fn [db _] (:drawer db)))

(rf/reg-sub :drawer/circles
  :<- [:drawer/slice]
  (fn [drawer _] (:circles drawer)))

(rf/reg-sub :drawer/dialog
  :<- [:drawer/slice]
  (fn [drawer _] (:dialog drawer)))

(rf/reg-sub :drawer/can-undo?
  :<- [:drawer/slice]
  (fn [drawer _] (seq (:undo drawer))))

(rf/reg-sub :drawer/can-redo?
  :<- [:drawer/slice]
  (fn [drawer _] (seq (:redo drawer))))

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
      [:button {:data-testid "drawer-undo"
                :on-click #(dispatch [:drawer/undo]) :disabled (not can-undo?)} "Undo"]
      [:button {:data-testid "drawer-redo"
                :on-click #(dispatch [:drawer/redo]) :disabled (not can-redo?)} "Redo"]]
     [:svg {:data-testid "drawer-canvas"
            :width 600 :height 400 :style {:border "1px solid #999"}
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
       [:div.dialog {:data-testid "drawer-dialog"
                     :style {:border "1px solid #999" :padding "10px" :margin-top "5px"}}
        [:p (str "Adjust diameter of circle " (:circle-id dialog))]
        [:input {:type      "range"
                 :data-testid "drawer-slider"
                 :min       5 :max 100 :step 1
                 :value     (:draft-radius dialog)
                 :on-change #(dispatch [:drawer/dialog-drag
                                        (js/parseInt (.. % -target -value))])}]
        [:button {:data-testid "drawer-close"
                  :on-click #(dispatch [:drawer/close-dialog])} "Close"]])]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:drawer/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [drawer-view]])))
