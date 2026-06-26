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
;; returns a description of the change — `{:db …}` means "replace app-db
;; with this" — and the runtime commits it atomically at the end of the
;; cascade. The handler computes the new value; it never mutates app-db.

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
;; state by dereffing a `subscribe` and dispatches an event on click. It
;; holds no business logic — the inc/dec live in the handlers above.
;;
;; `reg-view` (Spec 004 §reg-view) auto-injects `dispatch` and `subscribe`
;; as lexical bindings, so they need no require here. Both resolve at
;; render time to the frame in scope — the `frame-provider` in the boot
;; below scopes this tree to the app frame. `reg-view` also defs a Var
;; named after the symbol and registers the view under (keyword *ns* sym),
;; so `counter-app` can reference `counter-buttons` directly.

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view just renders `counter-buttons`. The boot in
;; `run` wraps this tree in the `frame-provider` that establishes the frame.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; The whole frame lifecycle lives in one spot at the render root: the
;; `frame-provider {:id app-frame …}` below. On first mount it creates the
;; app frame, applies its config, and runs `:initial-events` once to seed
;; app-db. Thereafter every `dispatch`/`subscribe` in the tree resolves to
;; that frame. On hot reload the provider reuses the existing frame and
;; skips re-seeding, so the counter keeps its value across re-mounts.
;;
;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id
;; with no framework privilege — the runtime won't infer it, so we name it
;; here and hand it to the provider like any other id.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process. Each adapter ns
  ;; exports an `adapter` var; require the ns and pass that var directly.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))
