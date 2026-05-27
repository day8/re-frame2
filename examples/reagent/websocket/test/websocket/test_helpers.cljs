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

   - `register-all!` — call the above and then re-fire every `reg-*`
     the example's sub-namespaces install at ns-load, plus re-install
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
            ;; Pull in the example's sub-namespaces — their ns-load
            ;; reg-* forms ran the first time these were loaded. After
            ;; `clear-all!` we re-fire the same forms below; the require
            ;; here also pulls in the value bindings (`connection-machine`,
            ;; `socket-actor-machine`, `ConnectionSnapshot`, …) that the
            ;; recovery calls reference.
            [websocket.schema :as ws.schema]
            [websocket.connection :as ws.connection]
            [websocket.messages :as ws.messages]))

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
   example's sub-namespaces install their registrations at ns-load.

   Each block below mirrors the ns-load registrations in the named
   sub-namespace. If you add a `reg-*` there, mirror it here so the
   recovery is complete."
  []
  (re-register-machines-fx-and-subs!)

  ;; --- websocket.schema --------------------------------------------------
  (rf/reg-app-schema [:rf/machines :ws/connection] ws.schema/ConnectionSnapshot)
  (rf/reg-app-schema [:messages]                   ws.schema/MessagesSlice)

  ;; --- websocket.connection ----------------------------------------------
  (rf/reg-machine :ws/connection ws.connection/connection-machine)
  (rf/reg-sub :ws/snapshot       (fn [db _] (get-in db [:rf/machines :ws/connection])))
  (rf/reg-sub :ws/state          :<- [:ws/snapshot] (fn [snap _] (:state snap)))
  (rf/reg-sub :ws/connected?     :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/connected)))
  (rf/reg-sub :ws/reconnecting?  :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/reconnecting)))
  (rf/reg-sub :ws/failed?        :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/failed)))
  (rf/reg-sub :ws/queue-depth    :<- [:ws/snapshot] (fn [snap _] (count (get-in snap [:data :queue]))))
  (rf/reg-sub :ws/retries        :<- [:ws/snapshot] (fn [snap _] (get-in snap [:data :retries])))
  (rf/reg-sub :ws/error          :<- [:ws/snapshot] (fn [snap _] (get-in snap [:data :error])))
  (rf/reg-event-fx :ws.connection/initialise
    (fn handler-ws-connection-initialise [_ _]
      {:fx [[:dispatch [:ws/connection [:ws/noop]]]]}))

  ;; --- websocket.messages -------------------------------------------------
  (rf/reg-machine :websocket/socket ws.messages/socket-actor-machine)
  (rf/reg-event-db :ws/handle-message
    (fn handler-ws-handle-message [db [_ body]]
      (let [rx-seq (get-in db [:messages :rx-count] 0)]
        (-> db
            (update-in [:messages :received]
                       (fn [received]
                         (vec (cons (assoc body :rx-seq rx-seq) (or received [])))))
            (assoc-in [:messages :rx-count] (inc rx-seq))
            (cond-> (:request-id body)
              (assoc-in [:messages :last-reply] body))))))
  (rf/reg-event-fx :ws.app/send
    (fn handler-app-send [{:keys [db]} [_ body]]
      {:db (assoc-in db [:messages :draft] "")
       :fx [[:dispatch [:ws/connection [:ws/send {:type :note :body body}]]]]}))
  (rf/reg-event-fx :ws.app/request
    (fn handler-app-request [_ [_ body]]
      (let [rid (random-uuid)]
        {:fx [[:dispatch [:ws/connection
                          [:ws/request {:request-id rid
                                        :body       {:type :request
                                                     :body body}
                                        :reply      [:ws.app/request-reply]
                                        :timeout-ms 5000}]]]]})))
  (rf/reg-event-db :ws.app/request-reply
    (fn handler-app-request-reply [db [_ body]]
      (assoc-in db [:messages :last-reply] body)))
  (rf/reg-event-fx :ws.app/subscribe-demo
    (fn handler-app-subscribe-demo [_ _]
      {:fx [[:dispatch [:ws/connection [:ws/subscribe :demo-topic]]]]}))
  (rf/reg-event-db :ws.app/edit-draft
    (fn handler-app-edit-draft [db [_ text]]
      (assoc-in db [:messages :draft] text)))
  (rf/reg-event-fx :ws.messages/initialise
    (fn handler-messages-initialise [{:keys [db]} _]
      {:db (assoc db :messages {:draft "" :received [] :last-reply nil :rx-count 0})}))
  (rf/reg-sub :messages            (fn [db _] (:messages db)))
  (rf/reg-sub :messages/draft      :<- [:messages] (fn [m _] (:draft m)))
  (rf/reg-sub :messages/received   :<- [:messages] (fn [m _] (:received m)))
  (rf/reg-sub :messages/last-reply :<- [:messages] (fn [m _] (:last-reply m)))

  ;; --- websocket.core ----------------------------------------------------
  (rf/reg-event-fx :ws.app/initialise
    {:doc "App boot. Seeds the messages slice + materialises the
           connection machine's initial `:disconnected` snapshot.

           Namespaced under `:ws.app/*` (not `:app/initialise`) so the
           example can coexist with the realworld + counter examples
           without re-registering a common event key."}
    (fn handler-app-initialise [_ _]
      {:fx [[:dispatch [:ws.messages/initialise]]
            [:dispatch [:ws.connection/initialise]]]})))
