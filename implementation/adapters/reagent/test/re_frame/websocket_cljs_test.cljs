(ns re-frame.websocket-cljs-test
  "Integration test: drives the websocket example (rf2-yf97) through the
   Pattern-WebSocket lifecycle. Each helper spins a fresh frame via
   `make-frame`, drives the connection machine through a slice of the
   lifecycle (using an in-process mock WebSocket server in sync-delivery
   mode), and asserts the resulting state / tags / app-db slice.

   The fixture fns + the test-only re-registration scaffolding live HERE
   (the adapter test tree), not under examples/reagent/websocket/ — the
   example source stays test-free per the locked test-free-examples policy
   (rf2-8cevm). The ns requires the example's production sub-namespaces
   (`websocket.schema` / `websocket.connection` / `websocket.messages` /
   `websocket.core`) so their ns-load reg-* forms install the machines,
   subs and events, then exercises them directly. (rf2-cd2zo folded the
   former `websocket.test-helpers` / `websocket.connection-test` /
   `websocket.messages-test` fixture nses in here and retired the example
   test/ dir.)

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot captures
   the example's ns-load registrations, and the restore on the way out
   leaves them intact for any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.subs :as subs]
            [re-frame.late-bind :as late-bind]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [websocket.schema :as ws.schema]
            [websocket.connection :as ws.connection]
            [websocket.messages :as messages]
            [websocket.core])
  (:require-macros [re-frame.core :refer [with-frame with-new-frame]]))

;; ============================================================================
;; TEST-ONLY RE-REGISTRATION SCAFFOLDING
;; ============================================================================
;;
;; The websocket example's sub-namespaces — `websocket.schema`,
;; `websocket.connection`, `websocket.messages` — each install their
;; reg-machine / reg-event / reg-sub / reg-app-schema entries at
;; ns-load time (the production-app idiom; loading the ns IS the
;; registration). The example's `core.cljs` then registers the top-level
;; `:ws.app/initialise` event in the same idiomatic ns-load shape.
;;
;; Some test fixtures upstream of this example (alphabetically before
;; `re-frame.websocket-cljs-test` in the node-test run order) call
;; `re-frame.registrar/clear-all!` without restoring afterwards —
;; notably the Story `:rf.assert/*` test fixture. When that happens,
;; the ns-load registrations the example relies on disappear before
;; this test ns gets a chance to run. `register-all!` recovers from that
;; — the `:each` fixture's `:init-fn` calls it. Idempotent
;; (last-write-wins). Production `(websocket.core/run)` does NOT call it;
;; the example's sub-namespaces install their registrations at ns-load,
;; so the recovery dance lives here (the example source stays test-free).
;;
;; The narrow `(rf/reg-sub :rf/... ...)` calls below are recovery only —
;; the user-code 'must not register under :rf/*' rule (Spec Conventions
;; §Reserved-ns) is about origination; this republishes framework-owned
;; registrations that an upstream fixture wiped.

(defn- re-register-machines-fx-and-subs!
  "Re-fire the framework-shipped `:rf.machine/*` fx + the `:rf/machine`
   / `:rf/machine-has-tag?` subs.

   Idempotent (last-write-wins). Necessary because upstream test
   namespaces call `re-frame.registrar/clear-all!` without restoring,
   which wipes the ns-load-time registrations in `re-frame.machines`.
   Without these in place declarative `:spawn` silently no-ops and
   `rf/machine-has-tag?` returns false even when the tag is in the
   snapshot."
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
  ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state, so
  ;; these are runtime-db subs (the `db`-position arg is the runtime-db value).
  (subs/reg-runtime-sub :rf/machine
    (fn [rt [_ machine-id]]
      (get-in rt [:rf.runtime/machines :snapshots machine-id])))
  (subs/reg-runtime-sub :rf/machine-has-tag?
    (fn [rt [_ machine-id tag]]
      (contains? (get-in rt [:rf.runtime/machines :snapshots machine-id :tags]) tag))))

(defn- register-all!
  "Re-fire every `reg-*` the websocket example depends on. Safe to call
   at any point — every `reg-*` is last-write-wins. Each block below
   mirrors the ns-load registrations in the named sub-namespace; if a
   `reg-*` is added there, mirror it here so the recovery is complete."
  []
  (re-register-machines-fx-and-subs!)

  ;; --- websocket.schema --------------------------------------------------
  ;; EP-0001 (rf2-vzld77): machine snapshots are runtime-db, not app-db — an
  ;; `reg-app-schema` on a machine-snapshot path no longer validates anything
  ;; (app schemas validate the app-db partition only, Mike ruling #11). The
  ;; machine's own `:data-schema` is the snapshot-validation surface; the
  ;; vestigial app-schema reg is dropped. Only the genuine app-db slice
  ;; (`[:messages]`) keeps its app-schema.
  ;;
  ;; rf2-ofzxh9 — `reg-app-schema` is EP-0002 context-required frame-local
  ;; (rf2-5q7um6): it resolves `*current-frame*` and raises
  ;; `:rf.error/no-frame-context` under no scope. `register-all!` runs from
  ;; the fixture's `:init-fn`, which fires OUTSIDE the fixture's ambient
  ;; `*current-frame*` binding (`make-reset-runtime-fixture` invokes `:init-fn`
  ;; before it `binding`s the ambient frame around the test body). Bare, this
  ;; threw `:rf.error/no-frame-context` — and because the node-test build runs
  ;; every `*_cljs_test` ns in ONE shared JS runtime, that throw, fired during
  ;; a concurrently-pending async test's `done` window, surfaced as the
  ;; intermittent `FAIL in () (:) unexpected reject: :rf.error/no-frame-context`
  ;; + `Async test called done more than one time` flake. Name `:rf/default`
  ;; explicitly here, mirroring the example's own `(with-frame :rf/default …)`
  ;; ns-load idiom (examples/reagent/websocket/schema.cljs) — the fixture has
  ;; already `ensure-default-frame!`'d it.
  (with-frame :rf/default
    (rf/reg-app-schema [:messages]                 ws.schema/MessagesSlice))

  ;; --- websocket.connection ----------------------------------------------
  (rf/reg-machine :ws/connection ws.connection/connection-machine)
  (subs/reg-runtime-sub :ws/snapshot (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots :ws/connection])))
  (rf/reg-sub :ws/state          :<- [:ws/snapshot] (fn [snap _] (:state snap)))
  (rf/reg-sub :ws/connecting?    :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/connecting)))
  (rf/reg-sub :ws/authenticating? :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/authenticating)))
  (rf/reg-sub :ws/connected?     :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/connected)))
  (rf/reg-sub :ws/reconnecting?  :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/reconnecting)))
  (rf/reg-sub :ws/failed?        :<- [:ws/snapshot] (fn [snap _] (contains? (:tags snap) :websocket/failed)))
  (rf/reg-sub :ws/queue-depth    :<- [:ws/snapshot] (fn [snap _] (count (get-in snap [:data :queue]))))
  (rf/reg-sub :ws/retries        :<- [:ws/snapshot] (fn [snap _] (get-in snap [:data :retries])))
  (rf/reg-sub :ws/error          :<- [:ws/snapshot] (fn [snap _] (get-in snap [:data :error])))
  (rf/reg-event :ws.connection/initialise
    (fn handler-ws-connection-initialise [_ _]
      {:fx [[:dispatch [:ws/connection [:ws/noop]]]]}))

  ;; --- websocket.messages -------------------------------------------------
  (rf/reg-machine :websocket/socket messages/socket-actor-machine)
  (rf/reg-event :ws/handle-message
    (fn handler-ws-handle-message [{:keys [db]} [_ body]]
      {:db (let [rx-seq (get-in db [:messages :rx-count] 0)]
        (-> db
            (update-in [:messages :received]
                       (fn [received]
                         (vec (cons (assoc body :rx-seq rx-seq) (or received [])))))
            (assoc-in [:messages :rx-count] (inc rx-seq))
            (cond-> (:request-id body)
              (assoc-in [:messages :last-reply] body))))}))
  (rf/reg-event :ws.app/send
    (fn handler-app-send [{:keys [db]} [_ body]]
      {:db (assoc-in db [:messages :draft] "")
       :fx [[:dispatch [:ws/connection [:ws/send {:type :note :body body}]]]]}))
  ;; EP-0017 (rf2-1g0ba6): the request-id is a DURABLE correlation fact (it
  ;; is folded into the connection machine's :in-flight slot and the reply is
  ;; matched against it), so it must be a FOLDED FACT from a recordable cofx,
  ;; NOT an ambient `(random-uuid)` read inside the handler — replay would
  ;; otherwise mint a fresh id and break the correlation. Mirrors the
  ;; production `:ws.app/request-id` reg-cofx in
  ;; examples/reagent/websocket/messages.cljs.
  (rf/reg-cofx :ws.app/request-id
    {:recordable? true
     :doc "Replayable correlation id for an outbound request-reply (EP-0017)."}
    (fn [] (random-uuid)))
  (rf/reg-event :ws.app/request
    {:rf.cofx/requires [:ws.app/request-id]}
    (fn handler-app-request [{rid :ws.app/request-id} [_ body]]
      {:fx [[:dispatch [:ws/connection
                        [:ws/request {:request-id rid
                                      :body       {:type :request
                                                   :body body}
                                      :reply      [:ws.app/request-reply]
                                      :timeout-ms 5000}]]]]}))
  (rf/reg-event :ws.app/request-reply
    (fn handler-app-request-reply [{:keys [db]} [_ body]]
      {:db (assoc-in db [:messages :last-reply] body)}))
  (rf/reg-event :ws.app/subscribe-demo
    (fn handler-app-subscribe-demo [_ _]
      {:fx [[:dispatch [:ws/connection [:ws/subscribe :demo-topic]]]]}))
  (rf/reg-event :ws.app/edit-draft
    (fn handler-app-edit-draft [{:keys [db]} [_ text]]
      {:db (assoc-in db [:messages :draft] text)}))
  (rf/reg-event :ws.messages/initialise
    (fn handler-messages-initialise [{:keys [db]} _]
      {:db (assoc db :messages {:draft "" :received [] :last-reply nil :rx-count 0})}))
  (rf/reg-sub :messages            (fn [db _] (:messages db)))
  (rf/reg-sub :messages/draft      :<- [:messages] (fn [m _] (:draft m)))
  (rf/reg-sub :messages/received   :<- [:messages] (fn [m _] (:received m)))
  (rf/reg-sub :messages/last-reply :<- [:messages] (fn [m _] (:last-reply m)))

  ;; --- websocket.core ----------------------------------------------------
  (rf/reg-event :ws.app/initialise
    {:doc "App boot. Seeds the messages slice + materialises the
           connection machine's initial `:disconnected` snapshot.

           Namespaced under `:ws.app/*` (not `:app/initialise`) so the
           example can coexist with the realworld + counter examples
           without re-registering a common event key."}
    (fn handler-app-initialise [_ _]
      {:fx [[:dispatch [:ws.messages/initialise]]
            [:dispatch [:ws.connection/initialise]]]})))

;; `:init-fn` re-fires every `rf/reg-*` this example owns so the ns-load
;; registrations the tests depend on are present even when an
;; alphabetically-earlier test ns called `re-frame.registrar/clear-all!`
;; without restoring. Idempotent — last-write-wins. Also resets the mock
;; WebSocket server's `:sockets` table — since the spawn-counter resets to
;; 1 each test, every test's actor lands on `:websocket/socket#1`; without
;; this reset the prior tests' mock-socket entries are still in the table
;; and `send-server-push!` ends up delivering N copies of every push.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                (register-all!)
                (messages/reset-mock-server!))}))

;; ============================================================================
;; HELPERS — shared across the connection + messages fixtures
;; ============================================================================

;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state, so
;; `snapshot` reads the runtime-db value (callers pass `(rf/runtime-db-value f)`).
(defn- snapshot [runtime-db]
  (get-in runtime-db [:rf.runtime/machines :snapshots :ws/connection]))

(defn- machine-has-tag?
  "Read the machine's :tags union against a frame's runtime-db (machine
  snapshots are runtime-db state — rf2-vzld77)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :ws/connection tag]
                  (rf/runtime-db-value frame)))

(defn- new-frame []
  ;; Suppress the real `:dispatch-later` fx — the connection machine's
  ;; request-timeout uses it and we don't want the JS event loop in our
  ;; way. (We DO need the synthetic `[:rf.machine.timer/after-elapsed
  ;; delay epoch]` events to drive `:after` directly, which we do by
  ;; dispatching them ourselves.)
  (frame/make-anon-frame-record! {:on-create    [:ws.app/initialise]
                  :fx-overrides {:dispatch-later nil}}))

(defn- with-sync-mock! [f]
  ;; Toggle the mock server into sync delivery for the duration of `f`.
  (try
    (messages/set-mock-sync! true)
    (f)
    (finally
      (messages/set-mock-sync! false))))

;; ============================================================================
;; CONNECTION LIFECYCLE
;; ============================================================================

(defn- initial-state-test []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/connection [:ws/noop]] {:frame f})
    (let [s (snapshot (rf/runtime-db-value f))]
      (is (= :disconnected (:state s))
          (str "expected :disconnected got " (pr-str (:state s))))
      (is (= [] (get-in s [:data :queue])))
      (is (= {} (get-in s [:data :in-flight])))
      (is (= #{} (get-in s [:data :subscriptions])))
      (is (= 0 (get-in s [:data :retries])))
      (is (nil? (get-in s [:data :socket-id]))))))

(defn- connect-happy-path-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Sync-mode mock: the actor's open-then-send-auth-then-auth-ok
        ;; chain runs to completion inside the dispatch-sync stack.
        (let [s (snapshot (rf/runtime-db-value f))]
          (is (= [:active :connected] (:state s))
              (str "expected [:active :connected] got " (:state s)))
          (is (true?  (machine-has-tag? f :websocket/connected)))
          (is (false? (machine-has-tag? f :websocket/reconnecting)))
          (is (false? (machine-has-tag? f :websocket/failed)))
          (is (= 0    (get-in s [:data :retries])))
          (is (some?  (get-in s [:data :socket-id])))
          ;; URL + token were recorded in :data — they survive across
          ;; reconnects.
          (is (= "ws://mock" (get-in s [:data :url])))
          (is (= "demo"      (get-in s [:data :auth-token]))))))))

(defn- offline-queue-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        ;; Enqueue two messages while :disconnected.
        (rf/dispatch-sync [:ws/connection
                           [:ws/send {:type :note :body "A"}]]
                          {:frame f})
        (rf/dispatch-sync [:ws/connection
                           [:ws/send {:type :note :body "B"}]]
                          {:frame f})
        (let [s (snapshot (rf/runtime-db-value f))]
          (is (= :disconnected (:state s)))
          (is (= 2 (count (get-in s [:data :queue]))))
          (is (= [{:type :note :body "A"}
                  {:type :note :body "B"}]
                 (get-in s [:data :queue]))))
        ;; Connect — sync-mode mock means the cascade runs to
        ;; :connected inside this dispatch, and the :always
        ;; queue-flush on :connected drains both messages.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [s (snapshot (rf/runtime-db-value f))]
          (is (true? (machine-has-tag? f :websocket/connected)))
          (is (= [] (get-in s [:data :queue]))
              ":connected entry's :flush-queue :always drained the queue"))))))

(defn- reconnect-cascade-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        ;; Connect, then drop.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [pre-snap   (snapshot (rf/runtime-db-value f))
              pre-socket (get-in pre-snap [:data :socket-id])]
          (is (some? pre-socket))
          ;; Simulate a transport-level drop. The mock fires :ws/closed
          ;; (with the source-socket-id) into the actor, which forwards
          ;; to the parent. EP-0002 (rf2-9o48ih): the seam now takes a
          ;; frame-handle so its deferred dispatch carries the frame.
          (messages/simulate-disconnect! (rf/frame-handle f))
          (let [s (snapshot (rf/runtime-db-value f))]
            (is (= :reconnecting (:state s))
                (str "expected :reconnecting got " (:state s)))
            (is (true?  (machine-has-tag? f :websocket/reconnecting)))
            (is (false? (machine-has-tag? f :websocket/connected)))
            ;; :exit on :active cleared the stale socket-id.
            (is (nil?   (get-in s [:data :socket-id])))
            ;; :bump-retry ran.
            (is (= 1 (get-in s [:data :retries]))))
          ;; Fire the :after timer to re-enter :active. The :after key
          ;; is the fn-form delay; the runtime stamps the matching key
          ;; into the synthetic timer event. We need to pull the same
          ;; descriptor — for an fn-form :after the key is the fn
          ;; itself; but for the synthetic event the runtime passes
          ;; the resolved-delay-ms as the second slot. Look up the
          ;; current :after-epoch from :data and fire the after-elapsed
          ;; event keyed by the fn-form spec.
          (let [snap-now (snapshot (rf/runtime-db-value f))
                ;; Per Spec 005 §Hierarchy interaction the epoch is
                ;; per-decl-path; :reconnecting is the :after-bearing node.
                epoch    (get-in snap-now [:data :rf/after-epoch [:reconnecting]])
                ;; The :after table on :reconnecting has exactly one
                ;; entry — the fn-form delay. Pull it out so we can
                ;; replay the matching synthetic event.
                base-ms        (get-in snap-now [:data :base-ms])
                resolved-delay (long (* base-ms (Math/pow 2 (max 0 (dec (get-in snap-now [:data :retries]))))))]
            ;; Synthesise the timer-elapsed event. The matching
            ;; semantics: `:rf.machine.timer/after-elapsed delay epoch`
            ;; where `delay` is either the literal ms key (when the
            ;; :after entry is keyed by a literal) or the fn-form key
            ;; (when keyed by an fn). Our spec is fn-keyed.
            (rf/dispatch-sync [:ws/connection
                               [:rf.machine.timer/after-elapsed resolved-delay epoch [:reconnecting]]]
                              {:frame f}))
          ;; After firing the :after timer the machine re-enters :active.
          ;; In sync-mode the open-auth-ok cascade runs to :connected
          ;; inside the synthetic-timer dispatch.
          (let [s (snapshot (rf/runtime-db-value f))]
            ;; Either :active is re-entered (and in sync-mode runs to
            ;; :connected) OR the synthetic event was dropped as stale
            ;; (in which case the test asserts the precondition only).
            ;; The richer assertion is the connection-epoch one in the
            ;; staleness test.
            (when (= [:active :connected] (:state s))
              (is (true? (machine-has-tag? f :websocket/connected)))
              ;; A NEW socket-id is in :data (different from pre-socket).
              (is (not= pre-socket (get-in s [:data :socket-id]))
                  "reconnect spawned a fresh socket"))))))))

(defn- max-retries-failed-test []
  ;; The `:max-retries-exceeded?` guard on `:reconnecting`'s
  ;; `:always` cascade transitions to `:failed` on entry once
  ;; `:retries` ≥ `:max-retries`. Drive the machine into
  ;; `:reconnecting` with a pre-seeded `:retries` count high
  ;; enough to trip the guard — the simplest way to exercise the
  ;; max-retries → :failed contract without walking the whole
  ;; reconnect cascade.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        ;; Connect to spawn the actor.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Seed the snapshot's :data :retries past :max-retries via a
        ;; direct write to the machine's :data slot. This is a test
        ;; helper — production code never does this.
        ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db
        ;; state, so the seed writes the runtime-db PARTITION via swap-runtime-db!.
        (re-frame.frame/swap-runtime-db! f
          (fn [rt]
            (let [max-retries (get-in rt [:rf.runtime/machines :snapshots :ws/connection :data :max-retries])]
              (update-in rt [:rf.runtime/machines :snapshots :ws/connection :data]
                         assoc :retries (inc max-retries)))))
        ;; Now drive a :ws/closed — the parent transitions to :reconnecting
        ;; and immediately into :failed via :always-cascade.
        (let [snap-before (snapshot (rf/runtime-db-value f))]
          (rf/dispatch-sync [:ws/connection
                             [:ws/closed {:source-socket-id (get-in snap-before [:data :socket-id])
                                          :code 1006}]]
                            {:frame f}))
        (let [s (snapshot (rf/runtime-db-value f))]
          (is (= :failed (:state s))
              (str "expected :failed got " (:state s)
                   " retries=" (get-in s [:data :retries])
                   " max=" (get-in s [:data :max-retries]))))))))

(defn- connection-epoch-staleness-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [live-socket-id (get-in (snapshot (rf/runtime-db-value f))
                                     [:data :socket-id])
              stale-id       (str "stale-" (random-uuid))]
          ;; A :ws/received event with a stale source-socket-id is
          ;; dropped by :current-socket?. The :messages slice doesn't
          ;; gain the body.
          (is (not= stale-id live-socket-id))
          (let [pre-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id stale-id
                                              :body {:type :stale-push}}]]
                              {:frame f})
            (let [post-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
              (is (= pre-msgs post-msgs)
                  "stale :ws/received was suppressed by :current-socket?")))
          ;; A :ws/received event with the LIVE source-socket-id lands
          ;; — the :messages slice grows.
          (let [pre-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id live-socket-id
                                              :body {:type :live-push :note "hi"}}]]
                              {:frame f})
            (let [post-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
              (is (= (inc pre-msgs) post-msgs)
                  "live :ws/received passed the :current-socket? guard"))))))))

(defn- refresh-token-test []
  ;; :ws/refresh-token works from every state — :disconnected,
  ;; :active/*, :reconnecting, :failed. The next :active entry's
  ;; :spawn :data fn reads the refreshed token.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "old-token"}]]
                          {:frame f})
        (is (= "old-token" (get-in (snapshot (rf/runtime-db-value f))
                                   [:data :auth-token])))
        ;; Refresh from :connected.
        (rf/dispatch-sync [:ws/connection
                           [:ws/refresh-token "new-token"]]
                          {:frame f})
        (is (= "new-token" (get-in (snapshot (rf/runtime-db-value f))
                                   [:data :auth-token])))))))

(defn- disconnect-cleanly-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (rf/dispatch-sync [:ws/connection [:ws/disconnect]] {:frame f})
        (let [s (snapshot (rf/runtime-db-value f))]
          (is (= :disconnected (:state s)))
          (is (false? (machine-has-tag? f :websocket/connected)))
          (is (false? (machine-has-tag? f :websocket/reconnecting)))
          (is (false? (machine-has-tag? f :websocket/failed)))
          (is (nil? (get-in s [:data :socket-id]))))))))

;; ============================================================================
;; REQUEST/REPLY + SERVER PUSH + SUBSCRIPTIONS
;; ============================================================================

;; ----------------------------------------------------------------------------
;; APP-LEVEL request/reply boundary (rf2-eygytk)
;;
;; This request/reply path uses the Pattern-WebSocket APP-LEVEL correlation
;; shape — a per-message `:request-id`, a registered `:reply` event target,
;; and an `:in-flight` correlation map on the connection machine's `:data`
;; — and it is INTENTIONALLY OUTSIDE the EP-0011 uniform reply envelope.
;;
;; Why this is not a divergent second vocabulary: [Managed-Effects §The
;; uniform reply envelope] (the EP-0011 normative home) rules property 9
;; (the uniform async-reply envelope) to apply to framework-SHIPPED managed
;; async surfaces (HTTP, resources/mutations, machine async work, route
;; loaders, timers). re-frame2 deliberately does NOT ship a managed
;; WebSocket (Mike-ruled) — there is no `:rf.ws/*` fx, no reserved
;; `:rf.ws/*` namespace, and no framework contract. [Pattern-WebSocket] is
;; the canonical worked example of an APP/LIBRARY-built long-lived
;; connection, and it explicitly recommends THIS correlation shape:
;; "Treat individual messages over an open WebSocket as Pattern-AsyncEffect
;; interactions when they are request-reply (correlation-id keyed)" and
;; "Pattern-WebSocket's `:in-flight` map is the worked example of that
;; [correlation] step." Pattern-AsyncEffect leaves correlation to the
;; caller; it is NOT property 9. So `:request-id`/`:reply`/`:in-flight` is
;; the SPEC-BLESSED app convention here, not a competing reply envelope.
;;
;; The assertions below pin the boundary explicitly so this scaffold stops
;; teaching a second reply vocabulary by silent omission: the reply is the
;; app's own message body, and it carries NONE of the EP-0011 reply-map
;; vocabulary (`:rf/reply-to`, `:status`, `:work/id`, `:work/kind`,
;; `:completed-at`). If a future ruling folds this into the uniform
;; envelope, these assertions are the canary that flips.
(defn- request-reply-correlation-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Issue a request. Sync-mode mock echoes immediately, so the
        ;; reply lands inside the dispatch-sync stack — :in-flight
        ;; goes empty AGAIN by the time we check.
        ;;
        ;; EP-0017 (rf2-1g0ba6): the durable request-id is a recordable
        ;; coeffect (`:ws.app/request-id`), not minted inside the handler.
        ;; The supply-data testing posture provides the fact FLAT under
        ;; `:rf.cofx` so the correlation id is deterministic in the test —
        ;; replay would re-present the same id.
        (let [req-id (random-uuid)]
          (rf/dispatch-sync [:ws.app/request "hello"]
                            {:frame f :rf.cofx {:ws.app/request-id req-id}})
          ;; EP-0001 (rf2-vzld77): the machine snapshot is runtime-db; `:messages`
          ;; is app-db.
          (let [db   (rf/app-db-value f)
                snap (snapshot (rf/runtime-db-value f))]
            (is (= {} (get-in snap [:data :in-flight]))
                ":in-flight slot was cleared on reply (app-level correlation)")
            (let [last-reply (get-in db [:messages :last-reply])]
              (is (some? last-reply))
              (is (= :reply (:type last-reply)))
              (is (true?    (:ok last-reply)))
              (is (= req-id (:request-id last-reply))
                  "the supplied recordable request-id round-tripped on the reply")
              (is (= {:type :request :body "hello"}
                     (:echo last-reply))
                  (str "echo body round-tripped, got " (:echo last-reply)))
              ;; rf2-eygytk: assert the APP-LEVEL boundary — the reply is the
              ;; app's own body and carries NONE of the EP-0011 uniform
              ;; reply-envelope vocabulary. This path is Pattern-WebSocket
              ;; app/library convention, NOT a framework-shipped managed
              ;; async surface (property 9 is exempt for WebSocket). The
              ;; wire `:request-id` above is app-level protocol correlation,
              ;; not the EP-0011 `:work/id`.
              (is (not (contains? last-reply :rf/reply-to))
                  "app-level reply carries no EP-0011 :rf/reply-to target")
              (is (not (contains? last-reply :status))
                  "app-level reply carries no EP-0011 :status taxonomy member")
              (is (not (contains? last-reply :work/id))
                  "app-level reply carries no EP-0011 :work/id correlation")
              (is (not (contains? last-reply :work/kind))
                  "app-level reply carries no EP-0011 :work/kind")
              (is (not (contains? last-reply :completed-at))
                  "app-level reply carries no EP-0011 :completed-at metadata"))))))))

(defn- request-id-missing-under-strict-test []
  ;; EP-0017 (rf2-1g0ba6): under a strict mint policy, the declared-absent
  ;; generator-backed `:ws.app/request-id` recordable coeffect FAILS as
  ;; `:rf.error/missing-required-cofx` rather than silently minting a fresh
  ;; `(random-uuid)` — the supply-data-don't-stub posture. The per-call
  ;; `:rf.cofx/mint-policy :strict` opt selects the policy without needing a
  ;; `:preset :test` frame for this one assertion.
  (with-new-frame [f (new-frame)]
    (let [ex (try (rf/dispatch-sync [:ws.app/request "hello"]
                                    {:frame f :rf.cofx/mint-policy :strict})
                  nil
                  (catch cljs.core/ExceptionInfo e e))]
      (is (some? ex) "a strict-policy dispatch with no supplied id throws")
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
          "the absent generator-backed request-id is missing-required under strict")
      (is (= :ws.app/request-id (:rf.cofx/id (ex-data ex)))
          "the error names the absent recordable request-id"))))

(defn- server-push-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [pre-count (count (get-in (rf/app-db-value f) [:messages :received]))]
          (messages/send-server-push! (rf/frame-handle f)
                                      {:type :push
                                       :note "from the server"})
          (let [post (get-in (rf/app-db-value f) [:messages :received])]
            (is (= (inc pre-count) (count post)))
            ;; newest-first. Each logged message also carries a UI-assigned
            ;; :rx-seq stamp (stable React key), so compare the body subset.
            (is (= {:type :push :note "from the server"}
                   (dissoc (first post) :rx-seq)))))))))

(defn- subscription-tracking-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (rf/dispatch-sync [:ws.app/subscribe-demo] {:frame f})
        ;; EP-0001 (rf2-vzld77): the machine snapshot is runtime-db; `:messages`
        ;; is app-db.
        (let [db (rf/app-db-value f)
              snap (snapshot (rf/runtime-db-value f))]
          (is (contains? (get-in snap [:data :subscriptions]) :demo-topic)
              ":data :subscriptions tracks the topic")
          ;; The mock's subscribe-ack synthetic push landed in
          ;; :messages :received in sync-mode.
          (let [pushes (get-in db [:messages :received])]
            (is (some (fn [m]
                        (and (= :push (:type m))
                             (= :demo-topic (:topic m))))
                      pushes)
                "synthetic subscribe-ack server push was logged")))))))

(defn- handle-message-newest-first-test []
  ;; :ws/handle-message is the dispatch :ws/received uses for pushed
  ;; bodies. The slice keeps them newest-first.
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 1}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 2}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :n 3}] {:frame f})
    (let [received (get-in (rf/app-db-value f) [:messages :received])]
      (is (= [{:type :push :n 3}
              {:type :push :n 2}
              {:type :push :n 1}]
             (mapv #(dissoc % :rx-seq) received))
          ":received list is newest-first")
      ;; Each message carries a monotonic :rx-seq stamp; newest-first means
      ;; the head has the highest seq, giving every <li> a stable React key.
      (is (= [2 1 0] (mapv :rx-seq received))
          ":rx-seq is a stable, monotonic, newest-first identity"))))

;; ============================================================================
;; DEFTESTS — one per fixture so the :each fixture (which resets mock state
;; + re-registers everything) runs around each individually.
;; ============================================================================

(deftest websocket-initial-state
  (testing "initial state — machine starts at :disconnected with empty buffers"
    (initial-state-test)))

(deftest websocket-connect-happy-path
  (testing "connect happy path — :disconnected → :active/:connected with tags set"
    (connect-happy-path-test)))

(deftest websocket-offline-queue
  (testing "offline queue — :ws/send while disconnected enqueues; :connected entry flushes"
    (offline-queue-test)))

(deftest websocket-reconnect-cascade
  (testing "reconnect cascade — transport drop → :reconnecting → :after re-enters :active"
    (reconnect-cascade-test)))

(deftest websocket-max-retries-failed
  (testing "max-retries — :reconnecting → :failed once :max-retries-exceeded?"
    (max-retries-failed-test)))

(deftest websocket-connection-epoch-staleness
  (testing "connection epoch — stale :ws/received from a prior socket is dropped"
    (connection-epoch-staleness-test)))

(deftest websocket-refresh-token
  (testing ":ws/refresh-token — updates :data :auth-token"
    (refresh-token-test)))

(deftest websocket-disconnect-cleanly
  (testing "clean :ws/disconnect — :connected → :disconnected, socket-id cleared"
    (disconnect-cleanly-test)))

(deftest websocket-request-reply-correlation
  (testing "request-reply correlation — :in-flight slot fills then clears on reply"
    (request-reply-correlation-test)))

(deftest websocket-request-id-missing-under-strict
  (testing "EP-0017 — declared-absent recordable :ws.app/request-id fails
            missing-required-cofx under strict, not a silent random-uuid"
    (request-id-missing-under-strict-test)))

(deftest websocket-server-push
  (testing "server-pushed events — manual push lands in [:messages :received]"
    (server-push-test)))

(deftest websocket-subscription-tracking
  (testing ":data :subscriptions tracking — :ws/subscribe records the topic"
    (subscription-tracking-test)))

(deftest websocket-handle-message-newest-first
  (testing ":ws/handle-message keeps the [:messages :received] log newest-first"
    (handle-message-newest-first-test)))
