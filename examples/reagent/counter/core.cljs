(ns counter.core
  "The smallest possible re-frame2 app. One slice of app-db, three event
   handlers, one subscription, one view, all running in one frame.

   This is the cleanest place to meet the event cascade: a click
   dispatches an event, a handler returns a new app-db, a subscription
   re-derives, and the view re-renders. See the guide's
   `docs/guide/concepts/events-and-the-cascade.md`.

   The frame is set up in one spot — the render root's
   `frame-provider {:id …}` in `run` below creates the app frame and runs
   its `:initial-events` seed.

   Shows: `reg-event`, `reg-sub`, and `reg-view` with its frame-bound
   `dispatch`/`subscribe`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs -----------------------------------------------------------
;;
;; An event handler is a pure function: it takes coeffects (which carry the
;; current app-db) and the event vector, and returns an effect map. The
;; `{:db …}` key means "replace app-db with this value". The runtime
;; commits that value atomically. The handler computes the new value; it
;; never mutates app-db. See `docs/guide/glossary.md#event-handler`.

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
;; reads state by dereffing a `subscribe` and dispatches an event on click.
;; It holds no business logic — the inc/dec live in the handlers above.
;;
;; `reg-view` makes `dispatch` and `subscribe` available in the body as
;; local bindings, so they need no require here. Both resolve at render
;; time to the frame in scope — the `frame-provider` in `run` below scopes
;; this tree to the app frame. `reg-view` also defines a Var named after
;; the symbol, so `counter-app` can reference `counter-buttons` directly.
;; See `docs/guide/concepts/views.md`.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view just renders `counter-buttons`. `run` wraps
;; this tree in the `frame-provider` that establishes the frame.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. Loading a namespace must produce no DOM side effects, so that
;; co-required example namespaces don't race `create-root` onto the shared
;; `#app`. See examples/TESTING.md, "mount-isolation".

(defonce react-root (atom nil))

;; The whole frame lifecycle lives in one spot: the `frame-provider {:id
;; app-frame …}` in `run` below. The first mount creates the app frame and
;; runs its `:initial-events` once to seed app-db. From then on every
;; `dispatch`/`subscribe` in the tree resolves to that frame. A later mount
;; under the same `:id` (a hot reload) reuses the live frame and does not
;; re-seed, so the counter keeps its value. See
;; `docs/guide/concepts/frames.md`.
;;
;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id
;; with no special status: the runtime never infers a frame, so we name one
;; here and hand it to the provider. See
;; `docs/guide/glossary.md#frame-identity-is-carried-not-found`.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. Each adapter ns
  ;; exports an `adapter` var; require the ns and pass that var. Call once
  ;; at startup. See `docs/guide/glossary.md#init`.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))
