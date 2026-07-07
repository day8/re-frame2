(ns seven-guis.circle-drawer.core
  "7GUIs #6 — Circle Drawer.

   Click on a canvas to drop a circle. Right-click one to resize it: a slider
   pops up in a modal, and closing the modal commits the new size. Undo and
   Redo walk back and forth through everything you've done.

   This is the 7GUIs undo/redo challenge, and undo is the whole point. The
   trick is *where* the history lives: not tangled up inside a component, but
   right in app-db as plain data. An interceptor takes a snapshot before each
   undoable event, and the Undo/Redo events just pop and push those snapshots.
   History is state, so it gets to be data like everything else.

   Worth watching here:
   - Undo / redo built from one interceptor plus a couple of sibling events
   - A modal dialog kept as state, not as a mounted React component
   - A continuous slider drag that, cleverly, adds no history steps at all
   - A schema-bound list, validated on every commit

   What you'll see is the *primitive* pattern — enough to do undo well. A
   full-featured undo library (per-slice history, depth limits, custom
   policies) belongs in user or library code. The framework gives you the
   hook; the rest is yours."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Pulling in `re-frame.schemas` wires up the hooks that teach
            ;; `rf/reg-app-schema` how to do its job. Require it, then use it.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; Each circle needs an id, and the choice of *what kind* of id is more
;; interesting than it looks. We use a plain counting `:int`, handed out from a
;; counter we keep in app-db. Why not just reach for `(random-uuid)`? Because
;; this id ends up in durable app-db (`[:drawer :circles]`), and anything that
;; lands in app-db should be a function of prior state. Replay the same event
;; stream and you must get the same ids back — a random uuid would betray that
;; the moment you replayed (see "Recordable vs ambient coeffects" in
;; docs/core/glossary.md). The id itself is humble: a React `:key` and
;; something to aim a right-click at. And since undo/redo snapshots whole
;; `:circles` vectors, the ids simply ride along in the snapshot and come back
;; unchanged.
(def Circle
  [:map
   [:id      :int]
   [:x       :double]
   [:y       :double]
   [:radius  pos-int?]])

(def DrawerState
  [:map
   [:circles   [:vector Circle]]
   [:next-id    :int]                           ;; the next id to hand out — counts up, never repeats
   [:dialog    [:maybe [:map                    ;; the resize modal, or nil when nothing's open
                        [:circle-id      :int]
                        [:initial-radius pos-int?]
                        [:draft-radius   pos-int?]]]]
   [:undo      [:vector :any]]                  ;; past :circles values, newest on top
   [:redo      [:vector :any]]])                ;; undone :circles values, waiting for Redo

;; Now bind the schema to a frame, and from then on every commit to that frame
;; gets checked against it. Schemas are frame-local — each frame validates its
;; own. There's a timing wrinkle: this line runs at ns-load, long before any
;; provider has mounted, so there's no frame "in scope" to pick up implicitly.
;; We just say which one out loud with `with-frame`. The `:rf/default` here is
;; the same frame the render root's `frame-provider` names below.
(rf/with-frame :rf/default
  (rf/reg-app-schema [:drawer] {:schema DrawerState}))

;; ============================================================================
;; UNDO INTERCEPTOR
;; ============================================================================
;;
;; Here's the engine room. An interceptor wraps a handler with a :before pass
;; and an :after pass — think of it as a chance to peek at the world on the way
;; in and tidy up on the way out. This one remembers what :circles looked like
;; *before* the handler ran (:before), and if the handler actually changed
;; things, files that old value onto the :undo stack (:after). Redo gets wiped,
;; because once you take a new action the future you'd undone-into is gone.
;;
;; The clever part is opting in. An event becomes undoable simply by listing
;; this interceptor's id (`:drawer/undoable`) in its :interceptors — nothing
;; more. Only the events that *commit* a change wear it, which is exactly why
;; dragging the slider around can stay silent and add no history at all.
;; See docs/core/interceptors.md.

(rf/reg-interceptor :drawer/undoable
  {:doc "Snapshot :circles before an undoable handler runs; push the prior
         value onto :undo and clear :redo when the handler changed it."}
  {:before (fn before [ctx]
             ;; On the way in: stash the old :circles. We read it from coeffects,
             ;; which is the db as it stands *before* the handler touches it.
             ;; Namespaced key on ctx itself (NOT a bare key stuffed into the
             ;; flat :coeffects map) — interceptor-private bookkeeping stays
             ;; off to the side instead of risking collision with a real
             ;; declared coeffect. See docs/core/interceptors.md.
             (let [db   (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc ctx ::prior-circles prior)))
   :after  (fn after [ctx]
             ;; On the way out: only bother recording history if the handler
             ;; actually changed :circles. No change, no undo step.
             (let [prior     (::prior-circles ctx)
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
  {:doc "Start with a blank canvas. `:next-id` is our id dispenser — the first
         circle gets 1, and it climbs from there."}
  (fn handler-drawer-initialise [{:keys [db]} _]
    {:db (assoc db :drawer {:circles [] :next-id 1 :dialog nil :undo [] :redo []})}))

(rf/reg-event :drawer/add-circle
  {:doc "You clicked the canvas, so a new circle appears at default size. It
         takes the next id from `:next-id`, then bumps the counter — that's how
         the id stays a function of prior state instead of a roll of the dice.
         The counter sits *outside* the `:circles` snapshot that undo/redo
         tracks, so it just keeps climbing and can never accidentally reissue an
         id that's still in use."
   :interceptors [:drawer/undoable]}
  (fn handler-drawer-add-circle [{:keys [db]} [_ x y]]
    {:db (let [id (get-in db [:drawer :next-id])]
      (-> db
          (update-in [:drawer :circles] conj {:id id :x x :y y :radius 30})
          (assoc-in  [:drawer :next-id] (inc id))))}))

(rf/reg-event :drawer/open-dialog
  {:doc "You right-clicked a circle, so the resize dialog opens on it. Just
         opening the dialog changes nothing you'd want to undo, so it skips the
         interceptor."}
  (fn handler-drawer-open-dialog [{:keys [db]} [_ circle-id]]
    {:db (let [{:keys [radius]} (->> (get-in db [:drawer :circles])
                                (filter #(= circle-id (:id %)))
                                first)]
      (assoc-in db [:drawer :dialog] {:circle-id      circle-id
                                      :initial-radius radius
                                      :draft-radius   radius}))}))

(rf/reg-event :drawer/dialog-drag
  {:doc "The slider is moving. We only update the *draft* radius — the circle on
         screen keeps its real size until you close the dialog and commit. And
         since this event skips `:drawer/undoable`, a frantic slider wiggle
         leaves the history completely untouched."}
  (fn handler-drawer-dialog-drag [{:keys [db]} [_ new-radius]]
    {:db (assoc-in db [:drawer :dialog :draft-radius] new-radius)}))

(rf/reg-event :drawer/close-dialog
  {:doc "Closing the dialog is the moment the new radius actually lands. Here's
         the payoff for keeping the drag draft-only: :circles never changed while
         the slider moved, so when the interceptor reaches for the 'before'
         snapshot it finds the state from *before the dialog even opened*. The
         entire resize — however many slider ticks it took — collapses into one
         tidy undo step."
   :interceptors [:drawer/undoable]}
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
  {:doc "Step back. Pop the top snapshot off :undo and restore it, after parking
         the current :circles on :redo so Redo can bring it back."}
  (fn handler-drawer-undo [{:keys [db]} _]
    {:db (let [{:keys [undo redo circles]} (:drawer db)]
      (if (empty? undo)
        db
        (-> db
            (assoc-in [:drawer :circles] (peek undo))
            (update-in [:drawer :undo] pop)
            (update-in [:drawer :redo] (fnil conj []) circles))))}))

(rf/reg-event :drawer/redo
  {:doc "Step forward again — the mirror image of undo. Pop the top of :redo and
         restore it, parking the current :circles back on :undo."}
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

;; One Layer-2 slice sub does the digging; the Layer-3 readers below all read
;; from it. `:drawer/slice` reaches into app-db once to grab the `:drawer` map,
;; and the subs below chain off it with `:<-`. So the [:drawer ...] walk happens
;; a single time per app-db swap, not once per dependent recompute — a small
;; habit that keeps a subscription graph cheap as it grows.
;; See docs/core/subscriptions.md.

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

(rf/reg-view drawer-view []
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

;; We hold the React root in an atom and create it lazily inside `run`, never at
;; ns-load. The rule is that loading a namespace should touch the DOM zero times
;; — otherwise two example namespaces sharing one page would both lunge for the
;; same `#app` and race each other to `create-root`. See the mount-isolation
;; convention in examples/TESTING.md.
(defonce react-root (atom nil))

;; The frame's whole life story happens in one place: the
;; `frame-provider {:id app-frame …}` down in `run`. On the first mount it
;; creates the frame, applies its config, and fires `:initial-events` once to
;; seed app-db. From then on, every `dispatch` and `subscribe` in the tree below
;; finds its way to that frame. Hot-reload is the nice part — the provider spots
;; the frame already exists, reuses it, and skips the re-seed, so your circles
;; survive the reload instead of vanishing.
;;
;; `app-frame` is just a name we picked. `:rf/default` carries no special powers;
;; it's an ordinary frame id, named here and handed to the provider like any
;; other. See docs/core/frames.md.
(def app-frame :rf/default)

(defn run []
  ;; `init!` tells re-frame2 to render through Reagent. One adapter, one process.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:drawer/initialise]]}
                 [drawer-view]])))
