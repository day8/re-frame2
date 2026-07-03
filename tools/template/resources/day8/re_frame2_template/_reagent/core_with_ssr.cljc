(ns {{namespace}}.core
  "One app that runs twice — server-side render + client-side hydration.

   The server renders the counter to HTML so the first paint arrives
   ready-made; the client then hydrates that same HTML and takes over,
   fully interactive. Both runs are the *same code* — which is why this
   file is `.cljc` and not `.cljs`. The events, subscription, schema, and
   view are written once and shared. The `#?(:clj …)` / `#?(:cljs …)`
   reader conditionals carve out the few spots that genuinely differ: the
   JVM render path on one side, the browser mount on the other.

   Emitted by `day8/re-frame2-template` under `:include-ssr? true`
   (Reagent-only in v1). The canonical worked example this mirrors is
   `examples/capabilities/ssr/ssr/core.cljc` in the re-frame2 repo, and
   the full story lives in Spec 011 — SSR & Hydration.

   The tour, top to bottom:
   - The whole-app-db schema, kept as a plain value so it can be
     registered against whichever frame the entry point stands up (the
     throwaway per-request server frame, or the fixed client frame).
   - `:counter/initialise` / `:counter/increment` — the shared events.
   - `:counter/value` — the shared subscription.
   - `counter-app` — the shared root view (`reg-view`).
   - SERVER ENTRY: `server-init-events` / `root-view` — the vectors the
     Ring host-adapter (see server.clj) hands to `ssr-handler`.
   - CLIENT ENTRY: `run`, which boots through `ssr/hydrate!` — READ the
     embedded payload, HYDRATE (dispatch the framework-owned `:rf/hydrate`
     event), VERIFY the render hash — then mounts on top of the seeded
     state. No flash, no re-fetch."
  (:require [re-frame.core :as rf]
            ;; Schema hooks + the Malli adapter — required as side-effecting
            ;; loads so the `reg-app-schema` call below has a real validator
            ;; to resolve to (without the Malli adapter the default validator
            ;; soft-passes per Spec 010).
            [re-frame.schemas]
            #?(:cljs [re-frame.schemas.malli])
            ;; The SSR machinery: the framework-owned `:rf/hydrate` event, the
            ;; render/hash hooks, and (JVM-side) the pure hiccup -> HTML
            ;; emitter. Required on both platforms so `:rf/hydrate` is
            ;; auto-registered for the client hydrate path.
            [re-frame.ssr :as ssr]
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; A plain value, registered per-frame by whichever entry point brings a
;; frame up. Parking it in a `def` means namespace-load never reaches for
;; an ambient frame that isn't there yet (reg-app-schema is frame-local,
;; EP-0002). See ../../../examples/capabilities/ssr/ssr/core.cljc for the
;; longer discussion.

(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; ============================================================================
;; EVENTS
;; ============================================================================

;; Per-request server boot. Seeds the counter so the server render has a
;; value to show. Server-only (`:platforms #{:server}`); the client never
;; dispatches it — it hydrates the server's finished state instead.
(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side boot — seeds the counter."
   :platforms #{:server}}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; Client-side bootstrap for a plain first load (no server render). Fires
;; only when `ssr/hydrate!` finds no payload — see `run` below.
(rf/reg-event :counter/initialise
  {:doc "Client-side seed for a plain (non-server-rendered) first load."}
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; The button dispatches this. Same handler on both platforms — but only
;; the client ever renders the button, so in practice it runs client-side.
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
;; `reg-view` auto-`def`s the symbol, registers it under `(keyword *ns*
;; sym)`, and injects a frame-bound `subscribe` / `dispatch` resolved at
;; render time. That single detail is what lets one view run twice: on the
;; JVM render path a deref just reads the current value; on the client the
;; same deref registers a reaction so the view re-renders on change. Same
;; code, two behaviours, picked up from the context.
;;
;; The `^{:rf/id :app/root}` metadata pins the id the server + client both
;; name (server.clj's `:root-view [:app/root]`, and `run`'s render below).

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
;; server.clj wires these two into `re-frame.ssr.ring/ssr-handler`, which
;; owns the per-request lifecycle (frame create -> drain -> response read
;; -> render -> frame destroy). The handler dispatches `server-init-events`
;; into a fresh per-request `:server` frame, drains to fixed point, then
;; renders `root-view`. The schema is registered per-request by
;; `register-schema!` (server.clj calls it from `:on-error`-free happy
;; path via the handler's construction; here we expose the pieces).

(def server-init-events
  "The `:initial-events` vector the Ring SSR handler dispatches into each
   per-request server frame."
  [[:rf/server-init]])

(def root-view
  "The root view id the SSR handler renders after the drain settles."
  [:app/root])

;; A JVM-side helper the SSR test (and server.clj) call to attach the
;; whole-app-db schema to a frame. reg-app-schema is frame-local, so this
;; must run under a live frame scope — never at ns-load.
(defn register-schema!
  "Attach the whole-app-db schema to `frame-id`. Call once per frame,
   under a live frame scope."
  [frame-id]
  (rf/reg-app-schema [] {:schema CounterDb :frame frame-id}))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; On the client the whole boot is `ssr/hydrate!` — the mirror of the
;; server render. It rolls three steps into one, and the order is the
;; point:
;;   1. READ    — pull the embedded `__rf_payload` <script> by its id. A
;;                malformed payload fails closed (nothing replaces app-db);
;;                a missing one just means "nobody server-rendered this" — a
;;                plain first load.
;;   2. HYDRATE — dispatch-sync `[:rf/hydrate payload]` BEFORE the first
;;                render. The framework handler swaps in the server's slice
;;                and stashes the server's render hash for step 3.
;;   3. VERIFY  — once the first render is on screen, hash the render tree
;;                and compare it to the server's. Disagree and the runtime
;;                raises `:rf.ssr/hydration-mismatch`.

#?(:cljs (defonce ^:private react-root (atom nil)))

;; The client's app-frame id. Threaded through `ssr/hydrate!` (where the
;; server's state gets seeded) and the root `frame-provider` (where every
;; dispatch / subscribe in the tree resolves). It must be a `:client`
;; frame — the `:rf/hydrate` handler fires compatibility-check effects a
;; `:server` frame would skip.
(def app-frame :rf/default)

#?(:cljs
   (defn ^:export init
     "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
      shadow's hot-reload pipeline re-invokes it on each rebuild."
     []
     ;; Point the runtime at the Reagent substrate (idempotent — installs
     ;; the adapter on the first call, no-op after).
     (rf/init! reagent-adapter/adapter)
     ;; Stand up the client app-frame before hydrating into it.
     ;; Re-registering an existing frame is a no-op, so hot-reload shrugs.
     (rf/reg-frame app-frame {:doc      "{{name}} SSR client app-frame"
                              :platform :client})
     ;; Register the schema against the client frame — the mirror of the
     ;; per-request registration on the server.
     (register-schema! app-frame)
     ;; One call, all three steps — READ, HYDRATE, VERIFY — against the
     ;; same `app-frame` the mount below uses. `:render-tree-fn` must hash
     ;; the EXACT tree the server hashed: the server hashed
     ;; `((rf/view :app/root))` (the view fn CALLED), so we call it the
     ;; same way here — not the `[(rf/view :app/root)]` vector form Reagent
     ;; mounts. `hydrate!` returns the payload it applied, or nil on a
     ;; plain client load.
     (let [payload (ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; No payload — a plain first load (dev `watch app` with no server
         ;; in front). Seed the counter so the page isn't a blank frame.
         (rf/dispatch-sync [:counter/initialise] {:frame app-frame})))
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Mount inside the app-frame's `frame-provider` so every dispatch /
       ;; subscribe in the tree resolves to the frame we just hydrated.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]]))))
