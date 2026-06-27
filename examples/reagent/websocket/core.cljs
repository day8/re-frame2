(ns websocket.core
  "Entry point for the WebSocket example.

   This example models a WebSocket connection as a state machine. It
   exercises:

   - **Hierarchical compound `:active`** parenting `:connecting`,
     `:authenticating`, `:connected`. The socket-actor `:spawn` is on the
     parent so it survives the success-path leaf transitions.

   - **`:after` exponential backoff** in `:reconnecting`. Leaving the
     state cancels the timer, so a backoff from a prior visit can't fire
     late.

   - **`:always` cascades** — `:reconnecting`'s max-retries guard, and
     `:connected`'s queue-flush on entry.

   - **`:tags`** — `:websocket/connected`, `:websocket/reconnecting`,
     `:websocket/failed`, so the view asks tag-shaped questions instead of
     unfolding the snapshot's hierarchical `:state` vector.

   - **Two staleness defences** — the backoff timer (runtime-cancelled on
     exit) and the connection-epoch (`:current-socket?` guard against the
     live `:socket-id`).

   - **Request/reply correlation** — `:in-flight` map, request-id stamp,
     timeout via `:dispatch-later`, reply-event dispatch on the correlated
     `:ws/received`.

   - **Reconnect cascade** — exit-from-`:active` clears the `:socket-id`;
     the runtime destroys the socket actor; the `:reconnecting` `:after`
     re-enters `:active`, which spawns a fresh one.

   See docs/machines/concepts.md for the machine grammar and
   docs/machines/examples.md for this example's place in the set.

   Coverage lives in the CLJS substrate contract tests (`npm run
   test:cljs`, ns `re-frame.websocket-cljs-test`); the mock server keeps
   the app self-contained. The example tree is test-free, so
   `npm run test:adapter-smokes` does not build this example."
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
  {:doc "App boot. Seeds the messages slice + materialises the
         connection machine's initial `:disconnected` snapshot.

         Namespaced under `:ws.app/*` (not `:app/initialise`) so the
         example can coexist with the realworld + counter examples
         without re-registering a common event key."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:ws.messages/initialise]]
          [:dispatch [:ws.connection/initialise]]]}))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; The React root is held in an atom and created lazily inside `run`, not
;; at ns-load. Several example namespaces share one `#app` element in the
;; browser-test bundle; creating the root at ns-load would race multiple
;; roots onto the same container. Creating it in `run` keeps ns-load free
;; of DOM side effects.

(defonce react-root (atom nil))

;; The frame lives in one spot: the `frame-provider` at the render root.
;; `run` installs the adapter, then renders `[frame-provider {:id ...}
;; ...]`. The provider's `{:id}` shape creates the app frame on first
;; mount, applies its config, and runs the `:initial-events` seed once; on
;; hot reload it reuses that frame and skips the seed. So create, seed, and
;; scope-into-React all happen there. The frame id `:rf/default` matches
;; the id `schema.cljs` scopes its `reg-app-schema` to.
;; See docs/guide/concepts/frames.md.
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
