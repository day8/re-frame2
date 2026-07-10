(ns {{namespace}}.core
  "Shared server-render and client-hydration entry point.

   Events, subscriptions, schema, and view run on both platforms. The Ring
   host creates a short-lived frame per request; the browser creates a
   client frame, applies the embedded payload with `ssr/hydrate!`, verifies
   the render hash, and mounts the same view."
  (:require [re-frame.core :as rf]
            ;; Installs the default Malli validator before schema attachment.
            [re-frame.schemas]
            [re-frame.ssr :as ssr]
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; Keep the schema as data and attach it after each platform creates a frame.

(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; ============================================================================
;; EVENTS
;; ============================================================================

;; The client hydrates this server-seeded state instead of running this event.
(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side boot — seeds the counter."
   :platforms #{:server}}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; Used only for a plain client load with no hydration payload.
(rf/reg-event :counter/initialise
  {:doc "Client-side seed for a plain (non-server-rendered) first load."}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :counter/value
  (fn [db _query]
    (:counter/value db)))

;; ============================================================================
;; VIEW
;; ============================================================================
;;
;; Pin the view id so the server and browser resolve the same registration.

(rf/reg-view ^{:rf/id :app/root} counter-app []
  [:div
   [:h1 "{{name}}"]
   [:button {:data-testid "increment"
             :on-click     #(dispatch [:counter/increment])}
    "+1"]
   [:span {:data-testid "counter-value"
           :style        {:margin "0 1em"}}
    @(subscribe [:counter/value])]])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; ssr-handler owns the per-request frame id. Its initial events run inside
;; that frame, so the zero-arity helper attaches to the ambient request frame.
;; The browser knows its frame id and uses the explicit arity.
(defn register-schema!
  ([] (rf/reg-app-schema [] CounterDb))
  ([frame-id] (rf/reg-app-schema [] {:frame frame-id} CounterDb)))

;; Attach the schema before the following seed event returns its :db effect.
(rf/reg-event :ssr/register-schema
  {:doc       "Per-request schema attach — registers the whole-app-db
                schema against the frame ssr-handler is constructing for
                this request (the ambient `:initial-events` scope)."
   :platforms #{:server}}
  (fn [_cofx _event]
    (register-schema!)
    {}))

(def server-init-events
  "The `:initial-events` vector the Ring SSR handler dispatches into each
   request frame. Schema attachment must precede the seed commit."
  [[:ssr/register-schema]
   [:rf/server-init]])

(def root-view
  "The root view id the SSR handler renders after the drain settles."
  [:app/root])

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; hydrate! reads and applies the embedded payload before the first render,
;; then compares the client render-tree hash with the server marker.

#?(:cljs (defonce ^:private react-root (atom nil)))

;; Hydration and the view tree must target the same client frame.
(def app-frame :rf/default)

#?(:cljs
   (defn ^:export init
     "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
      shadow's hot-reload pipeline re-invokes it on each rebuild."
     []
     (rf/init! reagent-adapter/adapter)
     ;; Create the frame before attaching its schema or hydrating state.
     (rf/reg-frame app-frame {:doc      "{{name}} SSR client app-frame"
                              :platform :client})
     (register-schema! app-frame)
     ;; Hash the realised view value, matching the server render input.
     (let [payload (ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; A plain client load has no server state to apply.
         (rf/dispatch-sync [:counter/initialise] {:frame app-frame})))
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Resolve view registrations and dispatches against the hydrated frame.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]]))))
