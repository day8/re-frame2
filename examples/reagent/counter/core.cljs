(ns counter.core
  "A minimal counter against the re-frame2 API.

   Demonstrates: `reg-event`, `reg-sub`, `reg-view` with frame-bound
   `dispatch`/`subscribe` injection."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core    :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; -- Events / subs (handler registry is app-global) --------------------------

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
;; The `:counter-buttons` view holds the UI. Per Spec 004 §reg-view, the
;; defn-shape macro auto-injects `dispatch` and `subscribe` as lexical
;; bindings; both resolve at render time to the frame in scope (the
;; default frame here).
;;
;; reg-view auto-defs a Var named after the supplied symbol and
;; registers the view under (keyword *ns* sym).

(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"} :data-testid "counter-value"} @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; The root `counter-app` view renders `counter-buttons` against the
;; default frame. Initialisation runs in `run` via `dispatch-sync`.

(reg-view counter-app []
  [counter-buttons])

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. This example uses `:rf/default` as its app frame id (a
;; migration may pick `:rf/default`, but the runtime will not infer it):
;; `init!` installs the adapter (it does NOT create the frame), `reg-frame`
;; registers the app frame, the boot dispatch runs under `with-frame`, and
;; the render is wrapped in a `frame-provider` so every in-tree
;; `dispatch`/`subscribe` resolves to the app frame.
(def app-frame :rf/default)

(defn run []
  ;; rf/init! takes the adapter spec map directly. Each adapter
  ;; ns exports an `adapter` var; consumers require the ns and pass the
  ;; var explicitly. There is no default-adapter registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:counter/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [counter-app]])))
