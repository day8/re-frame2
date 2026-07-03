(ns counter.core
  "The smallest possible re-frame2 app: a number and two buttons. One slice
   of app-db, three event handlers, one subscription, one view, one frame.

   Small on purpose. With nothing else in the way, you can watch a single
   click make the whole loop turn. The click dispatches an event, a handler
   returns a new app-db, the subscription re-derives, and the view
   re-renders — round and round. That loop is the entire idea of re-frame2;
   every bigger app is this same shape with more parts bolted on. The guide
   walks it slowly in `docs/core/concepts/events-and-the-cascade.md`.

   The frame is stood up in exactly one place: the `frame-provider` at the
   render root in `run` below. It names the frame and seeds it once.

   On the menu: `reg-event`, `reg-sub`, and `reg-view` with its frame-bound
   `dispatch`/`subscribe`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; -- Events / subs -----------------------------------------------------------
;;
;; An event handler is a pure function with a simple job: read the world,
;; describe the next one. It takes coeffects (which carry the current
;; app-db) and the event vector, and returns an effect map. The `{:db …}`
;; key just means "make this the new app-db", and the runtime commits it
;; for you atomically — all at once, no half-applied state. Notice the
;; handler never reaches out and pokes app-db — it only computes a value
;; and hands it back. Pure all the way down. See
;; `docs/core/glossary.md#event-handler`.

(rf/reg-event :counter/initialise
  (fn [{:keys [db]} _event] {:db {:counter/value 5}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (update db :counter/value dec)}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; -- Views -------------------------------------------------------------------
;;
;; A view is a pure function from subscription values to hiccup. This one
;; deref's a `subscribe` to read the count, and dispatches an event when a
;; button is clicked. That's it — no logic about what inc/dec mean. The
;; view asks for state and announces clicks; the handlers above do the
;; thinking. Keeping those two jobs apart is most of why re-frame2 views
;; stay easy to reason about.
;;
;; You'll notice `dispatch` and `subscribe` are used here with no require
;; and no `rf/` prefix. `reg-view` hands them to you as locals, already
;; wired to the frame in scope at render time — the `frame-provider` in
;; `run` below is what puts this tree in a frame. `reg-view` also def's a
;; Var named for the symbol, which is how `counter-app` can name
;; `counter-buttons` just below. See `docs/core/concepts/views.md`.

(rf/reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root view does nothing but render `counter-buttons`. A touch
;; ceremonial for one child, but it gives `run` a single tree to wrap in
;; the `frame-provider` that stands up the frame.

(rf/reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root lives in an atom and is created lazily inside `mount!`, never
;; at ns-load. The rule: loading a namespace must touch no DOM. Otherwise
;; two example namespaces that get required together would both lunge for
;; the shared `#app` element and race to `create-root` on it — and races
;; are exactly the bug you don't want in your test harness. See
;; examples/TESTING.md, "mount-isolation".

(defonce react-root (atom nil))

;; The frame's whole life story fits in one place: the `frame-provider {:id
;; app-frame …}` in `mount!` below. First mount creates the frame and runs its
;; `:initial-events` once to seed app-db. After that, every
;; `dispatch`/`subscribe` in the tree finds its way home to that frame.
;; Mount again under the same `:id` — a hot reload, say — and the provider
;; hands back the frame that's already alive instead of re-seeding, so your
;; counter doesn't snap back to 5 every time you save. See
;; `docs/core/concepts/frames.md`.
;;
;; `app-frame` is just an id we picked. `:rf/default` looks special but
;; isn't — it's an ordinary frame id like any other. The runtime never
;; guesses which frame you mean, so we name one here and hand it over
;; explicitly. See
;; `docs/core/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

;; `mount!` is browser setup: create the root lazily, then render the view tree
;; inside the frame-provider. `^:dev/after-load` is shadow's cue to re-run it on
;; each reload so your edited views re-render into the same root and same frame.
;; This is the canonical mount/boot shape, spelled the same in the quickstart,
;; the boot-and-mount how-to, and the todomvc example. See
;; `docs/core/how-to/boot-and-mount-an-app.md`.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))

(defn run []
  ;; `init!` tells re-frame2 which reactive substrate to render through —
  ;; here, Reagent. Each adapter ns exports an `adapter` var; you require the
  ;; ns and pass that var. One call, at startup, and you're done. Note it
  ;; installs the adapter for the whole process, not a frame — frames come
  ;; later, via the provider. See `docs/core/glossary.md#init`.
  (rf/init! reagent-adapter/adapter)
  (mount!))
