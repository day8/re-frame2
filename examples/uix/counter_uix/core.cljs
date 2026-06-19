(ns counter-uix.core
  "UIx variant of the counter example.

   Exercises the same dataflow as examples/reagent/counter but renders it
   through the UIx adapter — the React state model is hooks all the way
   down. Demonstrates:

     - `rf/init!` with the UIx adapter
     - `reg-event` / `reg-sub` (substrate-agnostic)
     - `use-subscribe` hook (UIx idiomatic)
     - `(:dispatch (rf/frame-handle))` for click handlers (components
       call dispatch / use-subscribe directly, no auto-injection)
     - The shared frame-context — the same React Context
       object the Reagent adapter consumes

   Different folder from examples/reagent/counter so the canonical Reagent
   counter is undisturbed; bundle isolation is verified by the
   per-example shadow-cljs builds and the production-elision grep."
  (:require [uix.core :refer [$ defui]]
            [uix.dom  :as uix-dom]
            [re-frame.core    :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

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
;; `reg-view` (the macro) stays Reagent-only;
;; UIx users write `defui` directly. There is no auto
;; injection — the component calls `use-subscribe` and takes
;; `dispatch` off a `(rf/frame-handle)` explicitly. The handle
;; captures the render-time frame, so the closed-over `dispatch`
;; targets the right frame even from an async callback.

(defui counter-buttons []
  (let [count    (uix-adapter/use-subscribe [:counter/value])
        dispatch (:dispatch (rf/frame-handle))]
    ($ :div
       ($ :button {:on-click #(dispatch [:counter/dec])} "-")
       ($ :span {:style #js {:margin "0 1em"} :data-testid "counter-value"} count)
       ($ :button {:on-click #(dispatch [:counter/inc])} "+"))))

(defui counter-app []
  ($ counter-buttons))

;; -- Mount -------------------------------------------------------------------
;;
;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.

(defonce react-root (atom nil))

;; The runtime never synthesises a frame from absence — an app must establish
;; its frame explicitly. `init!` installs the adapter (it does NOT create the
;; frame), `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in the UIx `frame-provider` so the
;; `use-subscribe` hook and the render-time `(rf/frame-handle)` capture resolve
;; to the app frame via React context. There is no `:rf/default` floor: a UIx
;; tree rendered with NO provider observes the no-provider sentinel and any
;; `use-subscribe` / `frame-handle` raises `:rf.error/no-frame-context`.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! uix-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:counter/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (uix-dom/create-root (js/document.getElementById "app"))))
    (uix-dom/render-root
      ($ uix-adapter/frame-provider-existing {:frame app-frame}
         ($ counter-app))
      @react-root)))
