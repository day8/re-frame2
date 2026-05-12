(ns websocket.core
  "Entry point for the Pattern-WebSocket worked example (rf2-yf97).

   This is the canonical re-frame2 example for spec/Pattern-WebSocket.md.
   Every key piece of the pattern is exercised:

   - **Hierarchical compound `:active`** parenting `:connecting`,
     `:authenticating`, `:connected` — the socket-actor `:invoke` is
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
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [websocket.schema]
            [websocket.connection]
            [websocket.messages]
            [websocket.views :as views]))

;; ============================================================================
;; RE-REGISTRATION HOOK
;; ============================================================================
;;
;; Some test fixtures upstream of this example (alphabetically before
;; `re-frame.websocket-cljs-test` in the node-test run order) call
;; `re-frame.registrar/clear-all!` without restoring afterwards —
;; notably the Story `:rf.assert/*` test fixture. When that happens,
;; the ns-load registrations the example relies on disappear before
;; this example's own test ns gets a chance to run.
;;
;; To recover, every sub-namespace exposes an idempotent
;; `register-all!` helper; this ns's `register-all!` calls them all,
;; then re-installs the top-level `:ws.app/initialise` event. The
;; test fixture calls this from its `:before` step. The function is
;; idempotent (every `rf/reg-*` is last-write-wins) so it's also safe
;; for hot-reload from the REPL.
;;
;; This pattern mirrors `counter-with-stories.stories/register-all!`,
;; which solves the same problem for the Story side-table.

(defn- re-register-machines-fx-and-subs!
  "Re-fire the framework-shipped `:rf.machine/*` fx + the `:rf/machine`
   / `:rf/machine-has-tag?` subs.

   Idempotent (last-write-wins). Necessary because upstream test
   namespaces (alphabetically before `re-frame.websocket-cljs-test`)
   call `re-frame.registrar/clear-all!` without restoring, which
   wipes the ns-load-time registrations in `re-frame.machines`.
   Without these in place:
   - declarative `:invoke` silently no-ops (the spawn fx isn't found);
   - `rf/has-tag?` returns false even when the tag is in the snapshot
     (the framework sub isn't registered)."
  []
  (when-let [spawn-fx (late-bind/get-fn :machines/spawn-fx)]
    (fx/reg-fx :rf.machine/spawn spawn-fx))
  (when-let [destroy-fx (late-bind/get-fn :machines/destroy-machine-fx)]
    (fx/reg-fx :rf.machine/destroy destroy-fx))
  (when-let [invoke-all-init-fx (late-bind/get-fn :machines/invoke-all-init-fx)]
    (fx/reg-fx :rf.machine/invoke-all-init invoke-all-init-fx))
  (when-let [after-schedule-fx (late-bind/get-fn :machines/after-schedule-fx)]
    (fx/reg-fx :rf.machine/after-schedule after-schedule-fx))
  (when-let [after-cancel-fx (late-bind/get-fn :machines/after-cancel-fx)]
    (fx/reg-fx :rf.machine/after-cancel after-cancel-fx))
  ;; The framework subs that read machine state — both registered at
  ;; machines.cljc ns-load time and equally vulnerable to `clear-all!`.
  (rf/reg-sub :rf/machine
    (fn [db [_ machine-id]]
      (get-in db [:rf/machines machine-id])))
  (rf/reg-sub :rf/machine-has-tag?
    (fn [db [_ machine-id tag]]
      (contains? (get-in db [:rf/machines machine-id :tags]) tag))))

(defn register-all!
  "Re-fire every `reg-*` this example depends on. Safe to call at any
   point during the app's lifetime — every `reg-*` is last-write-wins."
  []
  (re-register-machines-fx-and-subs!)
  (websocket.schema/register-all!)
  (websocket.connection/register-all!)
  (websocket.messages/register-all!)
  (rf/reg-event-fx :ws.app/initialise
    {:doc "App boot. Seeds the messages slice + materialises the
           connection machine's initial `:disconnected` snapshot.

           Namespaced under `:ws.app/*` (not `:app/initialise`) so the
           example can coexist with the realworld + counter examples
           without re-registering a common event key."}
    (fn handler-app-initialise [_ _]
      {:fx [[:dispatch [:ws.messages/initialise]]
            [:dispatch [:ws.connection/initialise]]]})))

(register-all!)

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; Gated on `(exists? js/document)` so the namespace can load in
;; non-DOM CLJS hosts (shadow-cljs :node-test, headless test harnesses).
;; The headless fixtures live in `test/websocket/<feature>_test.cljs`
;; and run without React.

(defonce react-root
  (when (exists? js/document)
    (rdc/create-root (js/document.getElementById "app"))))

(defn ^:export run []
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:ws.app/initialise])
  (when react-root
    (rdc/render react-root [views/root-view])))
