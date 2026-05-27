(ns websocket.core
  "Entry point for the Pattern-WebSocket worked example.

   This is the canonical re-frame2 example for spec/Pattern-WebSocket.md.
   Every key piece of the pattern is exercised:

   - **Hierarchical compound `:active`** parenting `:connecting`,
     `:authenticating`, `:connected` — the socket-actor `:spawn` is
     anchored on the parent so it survives the success-path leaf
     transitions.

   - **`:after` exponential backoff** in `:reconnecting`, with the
     epoch invariant taking care of stale timers from prior visits.

   - **`:always` cascades** — `:reconnecting`'s max-retries guard, and
     `:connected`'s queue-flush on entry.

   - **`:fsm/tags`** — `:websocket/connected`, `:websocket/reconnecting`,
     `:websocket/failed` so the view asks tag-shaped questions instead
     of unfolding the snapshot's hierarchical `:state` vector.

   - **Pattern-StaleDetection composed twice** — once for the backoff
     timer (runtime built-in), once for the connection-epoch
     (`:current-socket?` guard against the live `:socket-id`).

   - **Request/reply correlation** — `:in-flight` map, request-id
     stamp, timeout via `:dispatch-later`, reply-event dispatch on
     the correlated `:ws/received`.

   - **Reconnect cascade** — exit-from-`:active` clears the
     `:socket-id`; the runtime destroys the socket actor; the
     `:reconnecting` `:after` re-enters `:active` which spawns a
     fresh one.

   Run standalone via `npm run test:examples`; the mock server keeps
   the app self-contained."
  (:require [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [websocket.schema]
            [websocket.connection]
            [websocket.messages]
            [websocket.views :as views]))

;; ============================================================================
;; APP-BOOT EVENT
;; ============================================================================

(rf/reg-event-fx :ws.app/initialise
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
;; React root held in an atom and populated lazily inside `run` rather
;; than at ns-load. Multiple example namespaces co-required by the
;; browser-test bundle's wrapper test namespaces share a single
;; `#app` element; running `create-root` at ns-load would race multiple
;; roots onto the same container and leak example-A's mount into
;; example-B's tests. Mounting in `run` keeps ns-load DOM-side-effect-free.
;; The headless fixtures live in `test/websocket/<feature>_test.cljs`
;; and run in any CLJS host without React.

(defonce react-root (atom nil))

(defn run []
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:ws.app/initialise])
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [views/root-view])))
