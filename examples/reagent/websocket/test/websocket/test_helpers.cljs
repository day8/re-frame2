(ns websocket.test-helpers
  "Test-only re-registration scaffolding for the websocket worked example.

   The websocket example's sub-namespaces — `websocket.schema`,
   `websocket.connection`, `websocket.messages` — each install their
   reg-machine / reg-event-* / reg-sub / reg-app-schema entries at
   ns-load time (the production-app idiom; loading the ns IS the
   registration). The example's `core.cljs` then registers the top-level
   `:ws.app/initialise` event in the same idiomatic ns-load shape and
   mounts in `run`.

   Some test fixtures upstream of this example (alphabetically before
   `re-frame.websocket-cljs-test` in the node-test run order) call
   `re-frame.registrar/clear-all!` without restoring afterwards —
   notably the Story `:rf.assert/*` test fixture. When that happens,
   the ns-load registrations the example relies on disappear before
   this example's own test ns gets a chance to run.

   This namespace exists purely to recover from that. It owns:

   - `re-register-machines-fx-and-subs!` — re-fire the
     framework-shipped `:rf.machine/*` fx + the `:rf/machine` /
     `:rf/machine-has-tag?` subs that ns-load registered in
     `re-frame.machines`. Without these, declarative `:spawn` silently
     no-ops and `rf/machine-has-tag?` returns false even when the tag
     is in the snapshot.

   - `register-all!` — call the above and then re-fire every example
     sub-ns's idempotent `register-all!`, plus re-install
     `:ws.app/initialise`.

   Idempotent (last-write-wins). The wrapper test ns invokes
   `register-all!` from its `:each` fixture's `:init-fn`; the example's
   `core.cljs` does NOT depend on this namespace, so the production
   bundle does not carry the recovery dance.

   The narrow `(rf/reg-sub :rf/... ...)` calls below are recovery only —
   the user-code 'must not register under :rf/*' rule (Spec Conventions
   §Reserved-ns) is about origination; this ns republishes
   framework-owned registrations that an upstream fixture wiped. The
   recovery lives here, not in the example source, so readers of the
   teaching example don't see the dance."
  (:require [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [websocket.schema]
            [websocket.connection]
            [websocket.messages]))

(defn- re-register-machines-fx-and-subs!
  "Re-fire the framework-shipped `:rf.machine/*` fx + the `:rf/machine`
   / `:rf/machine-has-tag?` subs.

   Idempotent (last-write-wins). Necessary because upstream test
   namespaces (alphabetically before `re-frame.websocket-cljs-test`)
   call `re-frame.registrar/clear-all!` without restoring, which
   wipes the ns-load-time registrations in `re-frame.machines`.
   Without these in place:
   - declarative `:spawn` silently no-ops (the spawn fx isn't found);
   - `rf/machine-has-tag?` returns false even when the tag is in the
     snapshot (the framework sub isn't registered)."
  []
  (when-let [spawn-fx (late-bind/get-fn :machines/spawn-fx)]
    (fx/reg-fx :rf.machine/spawn spawn-fx))
  (when-let [destroy-fx (late-bind/get-fn :machines/destroy-machine-fx)]
    (fx/reg-fx :rf.machine/destroy destroy-fx))
  (when-let [spawn-all-init-fx (late-bind/get-fn :machines/spawn-all-init-fx)]
    (fx/reg-fx :rf.machine/spawn-all-init spawn-all-init-fx))
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
  "Re-fire every `reg-*` the websocket example depends on. Safe to call
   at any point during the app's lifetime — every `reg-*` is
   last-write-wins.

   This is the recovery seam the test fixture's `:init-fn` calls between
   tests. Production `(websocket.core/run)` does NOT call this; the
   example's sub-namespaces install their registrations at ns-load."
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
