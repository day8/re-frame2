(ns websocket.core
  "The entry point for the WebSocket example.

   The big idea: a connection is a question, not a value. You rarely care
   what's *in* the connection variable — you care which state you're in
   (connecting, authenticating, connected, dropped, reconnecting, given
   up) and which events move you between them. That is exactly what a
   machine is for, so this example models the whole connection lifecycle
   as one.

   This file is just the launchpad. The four interesting files do the
   real work:

   - `connection.cljs` — the `:ws/connection` machine, the heart of it.
   - `messages.cljs` — the spawned socket actor + a tiny mock server.
   - `views.cljs` — the UI that drives the machine and shows its state.
   - `schema.cljs` — Malli schemas for the machine `:data` and app-db.

   For the grammar the machine leans on, see docs/machines/concepts.md;
   for where this example sits among the others, docs/machines/examples.md.

   No network required: a small in-process mock server stands in for a
   real endpoint, so the app runs standalone. Its real coverage lives in
   the CLJS contract tests (`npm run test:cljs`, ns
   `re-frame.websocket-cljs-test`)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [websocket.schema]
            [websocket.connection]
            [websocket.messages]
            [websocket.views :as views]))

;; ============================================================================
;; APP-BOOT EVENT
;; ============================================================================

(rf/reg-event :ws.app/initialise
  {:doc "Boot the app: seed the messages slice and bring the connection
         machine to life in its `:disconnected` start state.

         The `:ws.app/*` prefix (rather than a plain `:app/initialise`)
         is deliberate. Several examples share one test bundle, and a
         common event key would have them stepping on each other's
         registrations. Namespacing keeps each example to itself."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:ws.messages/initialise]]
          [:dispatch [:ws.connection/initialise]]]}))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; We hold the React root in an atom and create it lazily inside `run`,
;; never at ns-load. The reason is mundane but real: several examples
;; share one `#app` element in the test bundle, and creating roots at
;; ns-load would race several of them onto the same container. Building it
;; in `run` keeps loading this namespace free of DOM side effects.

(defonce react-root (atom nil))

;; One place owns the frame: the `frame-provider` at the render root.
;; `run` installs the adapter, then renders `[frame-provider {:id ...}
;; ...]`. Pass the provider an `{:id}` and it does the whole dance for
;; you — on first mount it creates the frame, applies its config, and
;; fires the `:initial-events` seed once; on a hot reload it finds the
;; existing frame and skips the seed. Create, seed, and scope-into-React,
;; all in one spot.
;;
;; The id `:rf/default` is the same one `schema.cljs` scopes its
;; `reg-app-schema` to — they have to agree, or the schema lands in a
;; different frame than the app. See docs/guide/concepts/frames.md.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:ws.app/initialise]]}
                 [views/root-view]])))
