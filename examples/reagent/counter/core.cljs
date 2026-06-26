(ns counter.core
  "The smallest possible re-frame2 app — and so the cleanest place to
   meet the event cascade. One slice of app-db, three event handlers,
   one subscription, one view, dispatched in a single explicitly-
   established frame.

   The whole frame is established in one spot: the render root's
   `frame-provider {:id …}` creates the app frame, configures it, and
   runs its `:initial-events` seed once.

   Demonstrates: `reg-event`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (the registrar is process-global) -------------------------
;;
;; Each handler is a pure (coeffects, event-vector) -> effect map fn. It
;; doesn't perform the change; it returns `{:db …}` ("replace app-db with
;; this") and the runtime commits it atomically at the end of the cascade.

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
;; A view is a pure fn from subscription values to hiccup. This one reads
;; state by dereffing a `subscribe` and dispatches an event on click —
;; no business logic; the inc/dec live in the handlers above.
;;
;; Per Spec 004 §reg-view, the defn-shape macro auto-injects `dispatch`
;; and `subscribe` as lexical bindings (so they aren't imported here);
;; both resolve at render time to the frame in scope — see the boot
;; below, where `frame-provider {:id app-frame}` scopes this tree to the
;; app frame. The macro also auto-defs a Var named after the supplied
;; symbol and registers the view under (keyword *ns* sym), so
;; `counter-app` can reference it directly.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view just renders `counter-buttons`; the boot
;; in `run` scopes the whole tree to `app-frame` and seeds it via the
;; provider's `:initial-events`.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; The runtime never synthesises a frame from absence — an app must
;; establish its frame explicitly. So the boot below does two things in
;; order: `init!` installs the adapter (it does NOT create the frame),
;; then the render's `frame-provider {:id app-frame}` does the rest in
;; one spot — on first mount it CREATES the app frame, applies its
;; config, and runs `:initial-events` once to seed it; thereafter every
;; in-tree `dispatch`/`subscribe` resolves to that frame. On hot reload
;; the provider REUSES the existing frame and does NOT re-seed.
;;
;; `:rf/default` is an ORDINARY frame id with no framework privilege — a
;; migration may reach for it out of habit, but the runtime won't infer
;; it; you establish it like any other.
(def app-frame :rf/default)

(defn run []
  ;; rf/init! takes the adapter spec map directly. Each adapter
  ;; ns exports an `adapter` var; consumers require the ns and pass the
  ;; var explicitly. There is no default-adapter registry.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))
