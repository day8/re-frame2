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

   Coverage lives in the CLJS substrate contract tests (`npm run
   test:cljs`, ns `re-frame.websocket-cljs-test`); the mock server keeps
   the app self-contained. The example tree is test-free (rf2-8cevm), so
   `npm run test:examples` does not build this example."
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
;; React root held in an atom and populated lazily inside `run` rather
;; than at ns-load. Multiple example namespaces co-required by the
;; browser-test bundle's wrapper test namespaces share a single
;; `#app` element; running `create-root` at ns-load would race multiple
;; roots onto the same container and leak example-A's mount into
;; example-B's tests. Mounting in `run` keeps ns-load DOM-side-effect-free.
;; The headless fixtures live in the framework test tree at
;; `implementation/adapters/reagent/test/re_frame/websocket_cljs_test.cljs`
;; (the example tree is test-free, rf2-8cevm) and run in any CLJS host
;; without React.

(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. The frame id is
;; `:rf/default` — the same id `schema.cljs` scopes its `reg-app-schema`
;; registration to. Matches the canonical mount in
;; examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:ws.app/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [views/root-view]])))
