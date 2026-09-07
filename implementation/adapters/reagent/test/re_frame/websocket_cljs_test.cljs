(ns re-frame.websocket-cljs-test
  "Integration test: drives the websocket example (rf2-yf97) through the
   Pattern-WebSocket lifecycle. Each helper spins a fresh frame via
   `make-frame`, drives the connection machine through a slice of the
   lifecycle (using an in-process mock WebSocket server in sync-delivery
   mode), and asserts the resulting state / tags / app-db slice.

   The fixture fns + the test-only re-registration scaffolding live HERE
   (the adapter test tree), not under examples/patterns/websocket/ — the
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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.subs :as rf.subs]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [websocket.schema :as ws.schema]
            [websocket.connection :as ws.connection]
            [websocket.messages :as messages]
            [websocket.core :as ws.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; TEST-ONLY RE-REGISTRATION SCAFFOLDING
;; ============================================================================
;;
;; The websocket example's namespaces — `websocket.schema`,
;; `websocket.connection`, `websocket.messages`, `websocket.core` — each
;; install their reg-machine / reg-event / reg-cofx / reg-sub /
;; reg-app-schema entries at ns-load time (the production-app idiom;
;; loading the ns IS the registration). Each does it by calling its own
;; `register!`, and that named entry point is the whole reason this file
;; can recover the registrations without owning a copy of them.
;;
;; Some test fixtures upstream of this example (alphabetically before
;; `re-frame.websocket-cljs-test` in the node-test run order) call
;; `re-frame.registrar/clear-all!` without restoring afterwards —
;; notably the Story `:rf.assert/*` test fixture. When that happens,
;; the ns-load registrations the example relies on disappear before
;; this test ns gets a chance to run. `register-all!` recovers from that
;; — the `:each` fixture's `:init-fn` calls it. Idempotent
;; (last-write-wins). Production `(websocket.core/run)` does NOT call it;
;; the example's namespaces install their registrations at ns-load, so the
;; recovery dance lives here (the example source stays test-free).
;;
;; The narrow `(rf/reg-sub :rf/... ...)` calls below are recovery only —
;; the user-code 'must not register under :rf/*' rule (Spec Conventions
;; §Reserved-ns) is about origination; this republishes framework-owned
;; registrations that an upstream fixture wiped.

(defn- re-register-machines-fx-and-subs!
  "Re-fire the framework-shipped `:rf.machine/*` fx + the `:rf/machine`
   / `:rf.machine/has-tag?` subs.

   Idempotent (last-write-wins). Necessary because upstream test
   namespaces call `re-frame.registrar/clear-all!` without restoring,
   which wipes the ns-load-time registrations in `re-frame.machines`.
   Without these in place declarative `:spawn` silently no-ops and
   the `:rf.machine/has-tag?` sub returns false even when the tag is in
   the snapshot."
  []
  (when-let [spawn-fx (rf.late-bind/get-fn :machines/spawn-fx)]
    (rf.fx/reg-fx :rf.machine/spawn spawn-fx))
  (when-let [destroy-fx (rf.late-bind/get-fn :machines/destroy-machine-fx)]
    (rf.fx/reg-fx :rf.machine/destroy destroy-fx))
  (when-let [spawn-all-init-fx (rf.late-bind/get-fn :machines/spawn-all-init-fx)]
    (rf.fx/reg-fx :rf.machine/spawn-all-init spawn-all-init-fx))
  (when-let [after-schedule-fx (rf.late-bind/get-fn :machines/after-schedule-fx)]
    (rf.fx/reg-fx :rf.machine/after-schedule after-schedule-fx))
  (when-let [after-cancel-fx (rf.late-bind/get-fn :machines/after-cancel-fx)]
    (rf.fx/reg-fx :rf.machine/after-cancel after-cancel-fx))
  ;; The framework subs that read machine state — both registered at
  ;; machines.cljc ns-load time and equally vulnerable to `clear-all!`.
  ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state, so
  ;; these are runtime-db subs (the `db`-position arg is the runtime-db value).
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [rt [_ machine-id]]
      (get-in rt [:rf.runtime/machines :snapshots machine-id])))
  (rf.subs/reg-runtime-sub :rf.machine/has-tag?
    (fn [rt [_ machine-id tag]]
      (contains? (get-in rt [:rf.runtime/machines :snapshots machine-id :tags]) tag))))

(defn- register-all!
  "Recover every `reg-*` the websocket example owns, by calling the
   example's OWN registration entry points. Safe to call at any point —
   every `reg-*` is last-write-wins.

   THERE IS DELIBERATELY NO TEST-LOCAL COPY OF ANY EXAMPLE REGISTRATION
   HERE, and re-introducing one silently guts this suite. This fn used to
   re-declare the two ingress handlers, their bodies and their boundary
   metadata inline; because it runs on EVERY test and `reg-*` is
   last-write-wins, those copies replaced the example's own handlers
   before a single assertion ran. The suite then certified the copy: a
   fault planted in `websocket.messages`' `:ws.app/request-reply` body
   left it fully green, and `inbound-boundary-structural-test` read back
   the metadata this file had just installed rather than the example's
   (measured, rf2-idv1m). Calling `register!` is what puts the shipped
   definitions under test; `example-registrations-are-live-test` is the
   tripwire that fails first if a twin ever comes back.

   The only thing this file still installs itself is the FRAMEWORK's
   `:rf.machine/*` fx and subs, which no example namespace owns."
  []
  (re-register-machines-fx-and-subs!)
  ;; rf2-ofzxh9 — `reg-app-schema` is EP-0002 context-required frame-local
  ;; (rf2-5q7um6): it resolves `*current-frame*` and raises
  ;; `:rf.error/no-frame-context` under no scope. `register-all!` runs from
  ;; the fixture's `:init-fn`, which fires OUTSIDE the fixture's ambient
  ;; `*current-frame*` binding (`make-reset-runtime-fixture` invokes `:init-fn`
  ;; before it `binding`s the ambient frame around the test body). Bare, this
  ;; threw `:rf.error/no-frame-context` — and because the node-test build
  ;; runs every `*_cljs_test` ns in ONE shared JS runtime, that throw, fired
  ;; during a concurrently-pending async test's `done` window, surfaced as the
  ;; intermittent `FAIL in () (:) unexpected reject: :rf.error/no-frame-context`
  ;; + `Async test called done more than one time` flake. The example's own
  ;; `websocket.schema/register!` names `:rf/default` explicitly in its
  ;; `(rf/with-frame :rf/default …)` — exactly what this call site needs,
  ;; and one more thing that stays correct here only because it is not copied.
  (ws.schema/register!)
  (ws.connection/register!)
  (messages/register!)
  (ws.core/register!))

;; `:init-fn` re-fires every `rf/reg-*` this example owns so the ns-load
;; registrations the tests depend on are present even when an
;; alphabetically-earlier test ns called `re-frame.registrar/clear-all!`
;; without restoring. Idempotent — last-write-wins. Also resets the mock
;; WebSocket server's `:sockets` table — since the spawn-counter resets to
;; 1 each test, every test's actor lands on `:websocket/socket#1`; without
;; this reset the prior tests' mock-socket entries are still in the table
;; and `send-server-push!` ends up delivering N copies of every push.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (register-all!)
                (messages/reset-mock-server!))}))

;; ============================================================================
;; HELPERS — shared across the connection + messages fixtures
;; ============================================================================

;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state, so
;; `snapshot` reads the runtime-db value (callers pass
;; `(:rf.db/runtime (rf/frame-state-value f))`, per rf2-t3lftq — API-shrink
;; #3 retired `rf/runtime-db-value`).
(defn- snapshot [runtime-db]
  (get-in runtime-db [:rf.runtime/machines :snapshots :ws/connection]))

;; The live socket-actor id now lives in the framework-maintained
;; `:rf/spawned` slot on the parent's own :data, keyed by the :spawn-bearing
;; state's path (`[:active]`) — the rf2-yh21ah rewrite dropped the bespoke
;; `:socket-id` :data field. Mirrors `websocket.connection/socket-id`.
(defn- socket-id-of [snap]
  (get-in snap [:data :rf/spawned [:active]]))

(defn- request-token
  "The registration token `:register-request` minted for `request-id`, read
  off the live snapshot. Every synthesised `:ws/request-timeout` below stamps
  it, because the real scheduled event does: the token — not the id — is what
  ties a deadline to the registration that armed it (rf2-tb442). Returns nil
  once the slot is gone, which is itself the point."
  [f request-id]
  (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
          [:data :in-flight request-id :token]))

(defn- machine-has-tag?
  "Read the machine's :tags union against a frame's runtime-db (machine
  snapshots are runtime-db state — rf2-vzld77)."
  [frame tag]
  (rf/compute-sub [:rf.machine/has-tag? :ws/connection tag]
                  (:rf.db/runtime (rf/frame-state-value frame))))

(defn- new-frame []
  ;; Suppress the real `:dispatch-later` fx — the connection machine's
  ;; request-timeout uses it and we don't want the JS event loop in our
  ;; way. (We DO need the synthetic `[:rf.machine.timer/after-elapsed
  ;; delay epoch]` events to drive `:after` directly, which we do by
  ;; dispatching them ourselves.)
  (rf.frame/make-anon-frame-record! {:initial-events [[:ws.app/initialise]]
                  :fx-overrides {:dispatch-later nil}}))

(defn- with-sync-mock! [f]
  ;; Toggle the mock server into sync delivery for the duration of `f`.
  (try
    (messages/set-mock-sync! true)
    (f)
    (finally
      (messages/set-mock-sync! false))))

(defn- fire-after-timer!
  "Synthesise `:reconnecting`'s `:after` timer-elapsed event so the machine
   re-enters `:active` deterministically. The synthetic event must carry
   the SAME delay-key the runtime armed with — for a fn-form `:after`
   entry that is the FN itself (the `:after` table's key), because
   `pick-after-transition` resolves the table BY that key
   (`(get-in node [:after delay-key])`, Spec 005 §Hierarchy interaction);
   a resolved-ms NUMBER matches nothing and silently no-ops. Read the key
   straight off the registered spec, plus the node's current per-path
   epoch from the runtime-maintained `:rf/after-epoch` slot."
  [f]
  (let [snap      (snapshot (:rf.db/runtime (rf/frame-state-value f)))
        epoch     (get-in snap [:data :rf/after-epoch [:reconnecting]])
        delay-key (first (keys (get-in ws.connection/connection-machine
                                       [:states :reconnecting :after])))]
    (rf/dispatch-sync [:ws/connection
                       [:rf.machine.timer/after-elapsed delay-key epoch [:reconnecting]]]
                      {:frame f})))

;; ============================================================================
;; CONNECTION LIFECYCLE
;; ============================================================================

(defn- initial-state-test []
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/connection [:rf.machine/start]] {:frame f})
    (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
      (is (= :disconnected (:state s))
          (str "expected :disconnected got " (pr-str (:state s))))
      (is (= [] (get-in s [:data :queue])))
      (is (= {} (get-in s [:data :in-flight])))
      (is (= #{} (get-in s [:data :subscriptions])))
      (is (= 0 (get-in s [:data :retries])))
      ;; No socket spawned yet, so the :rf/spawned slot has no [:active] entry.
      (is (nil? (socket-id-of s))))))

(defn- connect-happy-path-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        ;; Sync-mode mock: the actor's open-then-send-auth-then-auth-ok
        ;; chain runs to completion inside the dispatch-sync stack.
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
          (is (= [:active :connected] (:state s))
              (str "expected [:active :connected] got " (:state s)))
          (is (true?  (machine-has-tag? f :websocket/connected)))
          (is (false? (machine-has-tag? f :websocket/reconnecting)))
          (is (false? (machine-has-tag? f :websocket/failed)))
          (is (= 0    (get-in s [:data :retries])))
          (is (some?  (socket-id-of s)))
          ;; URL + the OPAQUE credential reference were recorded in :data —
          ;; they survive across reconnects. Only the reference: the machine
          ;; never sees the bearer (rf2-iyjae).
          (is (= "ws://mock"      (get-in s [:data :url])))
          (is (= :ws.demo/cred-a  (get-in s [:data :cred-ref])))
          (is (not (contains? (:data s) :auth-token))
              "no raw-token field survives in machine :data"))))))

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
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
          (is (= :disconnected (:state s)))
          (is (= 2 (count (get-in s [:data :queue]))))
          ;; The queue now buffers the WHOLE inbound event (so a queued
          ;; :ws/request can rejoin :register-request on flush — see
          ;; websocket-queued-request-*), not the bare body — so each entry
          ;; is a `[:ws/send …]` vector.
          (is (= [[:ws/send {:type :note :body "A"}]
                  [:ws/send {:type :note :body "B"}]]
                 (get-in s [:data :queue]))))
        ;; Connect — sync-mode mock means the cascade runs to
        ;; :connected inside this dispatch, and the :always
        ;; queue-flush on :connected drains both messages.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
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
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [pre-snap   (snapshot (:rf.db/runtime (rf/frame-state-value f)))
              pre-socket (socket-id-of pre-snap)]
          (is (some? pre-socket))
          ;; Simulate a transport-level drop. The mock fires :ws/closed
          ;; (with the source-socket-id) into the actor, which forwards
          ;; to the parent. EP-0002 (rf2-9o48ih): the seam now takes a
          ;; capture-frame so its deferred dispatch carries the frame.
          (messages/simulate-disconnect! (rf/capture-frame f))
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= :reconnecting (:state s))
                (str "expected :reconnecting got " (:state s)))
            (is (true?  (machine-has-tag? f :websocket/reconnecting)))
            (is (false? (machine-has-tag? f :websocket/connected)))
            ;; Leaving :active tore the socket actor down; the runtime
            ;; cleared its id from the :rf/spawned slot for us — no :exit
            ;; null-out needed. So the connection clock reads nil.
            (is (nil?   (socket-id-of s)))
            ;; :on-socket-lost bumped the retry counter (and cleared the
            ;; then-empty in-flight map).
            (is (= 1 (get-in s [:data :retries]))))
          ;; Fire the :after timer to re-enter :active — `fire-after-timer!`
          ;; carries the fn-form delay-key the runtime armed with (a
          ;; resolved-ms number matches nothing — see the helper), so the
          ;; re-entry is deterministic and the assertions below are
          ;; unconditional. In sync-mode the open-auth-ok cascade runs to
          ;; :connected inside the synthetic-timer dispatch.
          (fire-after-timer! f)
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= [:active :connected] (:state s))
                (str "the :after re-entry ran to :connected, got " (:state s)))
            (is (true? (machine-has-tag? f :websocket/connected)))
            ;; A NEW socket id is in the :rf/spawned slot (a fresh actor,
            ;; so a different id from pre-socket).
            (is (some? (socket-id-of s)))
            (is (not= pre-socket (socket-id-of s))
                "reconnect spawned a fresh socket")))))))

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
                                         :cred-ref :ws.demo/cred-a}]]
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
        (let [snap-before (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
          (rf/dispatch-sync [:ws/connection
                             [:ws/closed {:source-socket-id (socket-id-of snap-before)
                                          :code 1006}]]
                            {:frame f}))
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
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
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (let [live-socket-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
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
          ;; — the :messages slice grows. (The body is a valid :push wire
          ;; frame — the closed InboundMessage union now rejects unknown
          ;; :type values at the boundary, which the boundary-rejection
          ;; test pins.)
          (let [pre-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id live-socket-id
                                              :body {:type :push :note "hi"}}]]
                              {:frame f})
            (let [post-msgs (count (get-in (rf/app-db-value f) [:messages :received]))]
              (is (= (inc pre-msgs) post-msgs)
                  "live :ws/received passed the :current-socket? guard"))))))))

(defn- rotate-cred-test []
  ;; :ws/rotate-cred works from every non-disconnected state — :active/*,
  ;; :reconnecting, :failed. Only the OPAQUE reference crosses the dispatch
  ;; boundary; the next :active entry's :spawn :data fn reads the rotated
  ;; reference and the new socket resolves it host-side (rf2-iyjae).
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (= :ws.demo/cred-a
               (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                       [:data :cred-ref])))
        ;; Rotate from :connected.
        (rf/dispatch-sync [:ws/connection
                           [:ws/rotate-cred :ws.demo/cred-b]]
                          {:frame f})
        (is (= :ws.demo/cred-b
               (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                       [:data :cred-ref])))))))

(defn- disconnect-cleanly-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (rf/dispatch-sync [:ws/connection [:ws/disconnect]] {:frame f})
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
          (is (= :disconnected (:state s)))
          (is (false? (machine-has-tag? f :websocket/connected)))
          (is (false? (machine-has-tag? f :websocket/reconnecting)))
          (is (false? (machine-has-tag? f :websocket/failed)))
          (is (nil? (socket-id-of s))))))))

;; ----------------------------------------------------------------------------
;; ADVERSARIAL / NEGATIVE — WebSocket lifecycle guards + in-flight/queued
;; correlation (rf2-3cgvt7 / rf2-r1rkvb / rf2-ryt25d)
;; ----------------------------------------------------------------------------

(defn- stale-lifecycle-events-dropped-test []
  ;; rf2-3cgvt7 — the `:current-socket?` epoch guard now covers the LIFECYCLE
  ;; transitions too (:ws/opened / :ws/auth-ok / :ws/auth-failed / :ws/closed),
  ;; not just :ws/received. The most dangerous straggler is a stale :ws/closed:
  ;; unguarded, a late close from a socket we've already replaced would tear
  ;; the LIVE connection down. Prove it's dropped, and that a genuine close
  ;; still passes.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [s0       (snapshot (:rf.db/runtime (rf/frame-state-value f)))
              live-id  (socket-id-of s0)
              stale-id (str "stale-" (random-uuid))
              retries0 (get-in s0 [:data :retries])]
          (is (not= stale-id live-id))
          ;; A stale :ws/closed must NOT drop us to :reconnecting.
          (rf/dispatch-sync [:ws/connection
                             [:ws/closed {:source-socket-id stale-id :code 1006}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (true? (machine-has-tag? f :websocket/connected))
                "stale :ws/closed dropped by :current-socket? — still connected")
            (is (= live-id (socket-id-of s))
                "the live socket survived the stale close")
            (is (= retries0 (get-in s [:data :retries]))
                "no retry bump from a stale close"))
          ;; The LIVE :ws/closed (correct source) still passes the guard.
          (rf/dispatch-sync [:ws/connection
                             [:ws/closed {:source-socket-id live-id :code 1006}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= :reconnecting (:state s))
                "live :ws/closed passed the guard and dropped to :reconnecting")
            (is (= (inc retries0) (get-in s [:data :retries]))
                ":on-socket-lost bumped the retry counter on the real drop")))))))

(defn- stale-auth-events-guarded-test []
  ;; rf2-3cgvt7 — sync-mode delivery races through :authenticating inside a
  ;; single dispatch, so the auth-outcome guards can't be pinned by parking
  ;; the machine there (and an async park would strand a real `setTimeout` —
  ;; the documented flake this file avoids). Verify them deterministically:
  ;;   (1) the guard fn — the SAME `:current-socket?` the close test exercises
  ;;       end-to-end — rejects a stale-sourced auth event and admits a live
  ;;       one, and
  ;;   (2) the auth (and opened/closed) transitions actually CARRY that guard.
  (let [entry (get-in ws.connection/connection-machine [:guards :current-socket?])
        ;; The example registers via `defmachine`, which co-locates each
        ;; `:guards` entry as `{:fn <fn> :source-coords … :source-code …}` for
        ;; Xray click-to-source; unwrap `:fn` to call the guard directly. (A
        ;; bare-fn entry — the old plain-`def` shape — is used as-is.)
        guard (if (map? entry) (:fn entry) entry)
        live  "socket-live"
        data  {:rf/spawned {[:active] live}}]
    (is (true?  (boolean (guard {:data  data
                                 :event [:ws/auth-ok {:source-socket-id live}]})))
        "live-sourced :ws/auth-ok is admitted")
    (is (false? (boolean (guard {:data  data
                                 :event [:ws/auth-failed {:source-socket-id "socket-old"}]})))
        "stale-sourced :ws/auth-failed is rejected")
    (is (false? (boolean (guard {:data  {}    ;; socket torn down: no :rf/spawned
                                 :event [:ws/auth-ok {:source-socket-id live}]})))
        "with no live socket, even a matching id is rejected (nil epoch)"))
  (let [m ws.connection/connection-machine]
    (is (= :current-socket?
           (get-in m [:states :active :states :connecting :on :ws/opened :guard]))
        ":ws/opened is epoch-guarded")
    (is (= :current-socket?
           (get-in m [:states :active :states :authenticating :on :ws/auth-ok :guard]))
        ":ws/auth-ok is epoch-guarded")
    (is (= :current-socket?
           (get-in m [:states :active :states :authenticating :on :ws/auth-failed :guard]))
        ":ws/auth-failed is epoch-guarded")
    (is (= :current-socket?
           (get-in m [:states :active :on :ws/closed :guard]))
        ":ws/closed is epoch-guarded")))

(defn- drop-fails-in-flight-request-test []
  ;; rf2-r1rkvb — a request already put on the wire, then orphaned by a socket
  ;; drop, must not leak in :in-flight forever. On the drop it is FAILED: the
  ;; slot clears and the waiting :reply-event fires with an explicit
  ;; connection-lost body (loss semantics = fail, not replay).
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        ;; A request whose wire :type the mock does NOT echo, so it sits
        ;; in-flight with no reply to clear it.
        (let [rid (random-uuid)]
          (rf/dispatch-sync [:ws/connection
                             [:ws/request {:request-id rid
                                           :body       {:type :silent-no-echo}
                                           :reply      [:ws.app/request-reply]
                                           :timeout-ms 5000}]]
                            {:frame f})
          (is (contains? (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
                         rid)
              "a request with no immediate reply sits in :in-flight")
          ;; Drop the socket.
          (messages/simulate-disconnect! (rf/capture-frame f))
          (let [s  (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                db (rf/app-db-value f)]
            (is (= :reconnecting (:state s)))
            (is (= {} (get-in s [:data :in-flight]))
                ":in-flight cleared on socket drop — no indefinite leak")
            (let [reply (get-in db [:messages :last-reply])]
              (is (some? reply) "the waiting reply-event fired on the drop")
              (is (false? (:ok reply)) "reply is an explicit failure")
              (is (= :ws/connection-lost (:error reply)))
              (is (= rid (:request-id reply))
                  "the failure names the dropped request")
              ;; LOSS PROVENANCE CONTROL (rf2-iyjae audit): the loss body is
              ;; the MACHINE's truth about the connection, and it says so.
              ;; :origin is stamped after receipt, so the hostile-frame test
              ;; below can prove no server frame reaches this arm.
              (is (= :ws/local (:origin reply))
                  "a connection-loss outcome is marked as locally minted"))))))))

(defn- timeout-fails-in-flight-request-test []
  ;; rf2-vqg8l6 — a request that TIMES OUT while the socket is still ALIVE must
  ;; not leave its caller hanging. The `:ws/request-timeout` handler (guarded by
  ;; `:current-socket?`, so the socket is live) FAILS the request the same way a
  ;; socket drop does: it clears the in-flight slot AND fires the waiting
  ;; `:reply-event` with an explicit `{:ok false :error :ws/timeout}` body —
  ;; `:on-socket-lost`'s per-request twin, scoped to the one request whose timer
  ;; elapsed. Unlike a drop, the connection stays up.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [live-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              rid     (random-uuid)]
          ;; A request whose wire :type the mock does NOT echo, so it sits
          ;; in-flight with no reply to clear it — a genuine no-answer.
          (rf/dispatch-sync [:ws/connection
                             [:ws/request {:request-id rid
                                           :body       {:type :silent-no-echo}
                                           :reply      [:ws.app/request-reply]
                                           :timeout-ms 5000}]]
                            {:frame f})
          (is (contains? (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
                         rid)
              "a request with no immediate reply sits in :in-flight")
          ;; Fire its timeout carrying the LIVE socket-id AND the registration
          ;; token, so it passes :own-request-timeout? — the socket-still-alive
          ;; timeout case. (new-frame suppresses the real :dispatch-later, so we
          ;; synthesise the timeout event exactly as :register-request would
          ;; have scheduled it, both fields included.)
          (rf/dispatch-sync [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            (request-token f rid)
                                                   :source-socket-id live-id}]]
                            {:frame f})
          (let [s  (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                db (rf/app-db-value f)]
            ;; A timeout does NOT tear the connection down (that's the drop
            ;; path) — the socket is still the same live actor.
            (is (true? (machine-has-tag? f :websocket/connected))
                "a request timeout leaves the live connection connected")
            (is (= live-id (socket-id-of s))
                "the socket survived the request timeout")
            (is (not (contains? (get-in s [:data :in-flight]) rid))
                ":in-flight slot cleared on timeout — no indefinite leak")
            (let [reply (get-in db [:messages :last-reply])]
              (is (some? reply) "the waiting reply-event fired on the timeout")
              (is (false? (:ok reply)) "reply is an explicit failure")
              (is (= :ws/timeout (:error reply)))
              (is (= rid (:request-id reply))
                  "the failure names the timed-out request")
              ;; TIMEOUT PROVENANCE CONTROL (rf2-iyjae audit) — the twin of
              ;; the loss control in drop-fails-in-flight-request-test.
              (is (= :ws/local (:origin reply))
                  "a timeout outcome is marked as locally minted"))))))))

;; ---------------------------------------------------------------------------
;; rf2-tb442 — a timeout belongs to a REGISTRATION, not to an id
;; ---------------------------------------------------------------------------
;;
;; The connection epoch (`:current-socket?`) rejects timers from an old
;; CONNECTION. It says nothing about an old REGISTRATION on the same live
;; socket, and the correlation id cannot cover that gap because the id is the
;; APP's: spec/Pattern-WebSocket.md §Message correlation invites a reusable
;; per-feature `[:feature/load slug]` vector, so uniqueness over a socket's
;; life was never a contract. Both tests below therefore use exactly that
;; reusable shape as the request-id.

(defn- log-tb442-reply! []
  ;; A COUNTING reply target, so "settled twice" and "settled by the wrong
  ;; timer" are direct assertions (the example's :ws.app/request-reply keeps
  ;; only the LAST outcome, where a spurious one is invisible). Each body is
  ;; logged and then forwarded to the boundary-validated
  ;; :ws.app/request-reply, so every outcome these tests observe is also
  ;; proven to pass the closed RequestOutcome contract.
  (rf/reg-event :ws.test/log-tb442-reply
    (fn handler-ws-test-log-tb442-reply [{:keys [db]} [_ body]]
      {:db (update-in db [:messages :reply-log] (fnil conj []) body)
       :fx [[:dispatch [:ws.app/request-reply body]]]})))

(defn- stale-timeout-cannot-settle-later-same-id-request-test []
  ;; rf2-tb442 — request A under id R completes; request B re-registers R on
  ;; the same STILL-LIVE socket, inside A's timeout window. A's uncancelled
  ;; timer then arrives naming R. Before the fix it passed :current-socket?,
  ;; deleted B's slot and handed B's caller a premature :ws/timeout — two
  ;; violations of the exactly-once/terminal-outcome promise in one event.
  ;; The registration :token is what makes it inert instead.
  (log-tb442-reply!)
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [live-id   (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              ;; The reusable per-feature id the spec recommends — the whole
              ;; point is that this value legitimately recurs.
              rid       [:feature/load "alpha"]
              in-flight #(get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
              log       #(get-in (rf/app-db-value f) [:messages :reply-log])
              request!  (fn []
                          (rf/dispatch-sync
                            [:ws/connection
                             [:ws/request {:request-id rid
                                           :body       {:type :silent-no-echo}
                                           :reply      [:ws.test/log-tb442-reply]
                                           :timeout-ms 5000}]]
                            {:frame f}))
              timeout!  (fn [token]
                          (rf/dispatch-sync
                            [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            token
                                                   :source-socket-id live-id}]]
                            {:frame f}))]
          ;; --- A: registered, then ANSWERED by the server -------------------
          (request!)
          (let [tok-a (request-token f rid)]
            (is (some? tok-a) "A's registration carries a token")
            ;; A correlated wire reply settles A and empties the slot. It
            ;; echoes A's own registration token, which is what a server that
            ;; honours the wire contract does. A's deadline is never
            ;; cancelled — nothing here can cancel it.
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id live-id
                                              :body {:type          :reply
                                                     :request-id    rid
                                                     :request-token tok-a
                                                     :ok            true}}]]
                              {:frame f})
            (is (not (contains? (in-flight) rid)) "A settled on the server's reply")
            (is (= 1 (count (log))) "A's caller was answered exactly once")
            (is (= :ws/server (:origin (last (log)))) "…by the server")

            ;; --- B: the SAME id, re-registered on the same live socket ------
            (request!)
            (let [tok-b (request-token f rid)]
              (is (some? tok-b) "B's registration carries a token")
              (is (not= tok-a tok-b)
                  "positive control: a second registration under the same id is a DIFFERENT registration")

              ;; --- A's stale timer fires ----------------------------------
              ;; It is stamped with the live socket, so the epoch check admits
              ;; it. Only the token stands between it and B.
              (timeout! tok-a)
              (is (= tok-b (get-in (in-flight) [rid :token]))
                  "THE REGRESSION: an earlier same-id request's timeout does not clear the later request's slot")
              (is (= 1 (count (log)))
                  "THE REGRESSION: it does not fire the later request's callback either")

              ;; --- and B's OWN timer still works --------------------------
              ;; Without this the assertions above would pass on a guard that
              ;; simply rejects everything.
              (timeout! tok-b)
              (is (not (contains? (in-flight) rid))
                  "B's own deadline still settles B")
              (is (= 2 (count (log))))
              (is (= {:origin :ws/local :request-id rid :ok false :error :ws/timeout}
                     (last (log)))
                  "B is settled by its own timeout, once, with the documented local body")))))))
  ;; The transition actually carries the compound guard (the structural half —
  ;; a guard nothing is wired to protects nothing).
  (is (= :own-request-timeout?
         (get-in ws.connection/connection-machine
                 [:states :active :states :connected :on :ws/request-timeout :guard]))
      ":ws/request-timeout is registration-guarded, not merely epoch-guarded"))

(defn- duplicate-in-flight-request-supersedes-test []
  ;; rf2-tb442 — the other half of a reusable id: TWO registrations under one
  ;; id at the same time. `:register-request` used to `assoc-in` straight over
  ;; the live entry, dropping the first caller's :reply-event on the floor
  ;; with nothing left to settle it. The policy is last-write-wins WITH the
  ;; displaced caller settled: it gets a terminal :ws/superseded outcome
  ;; before the new request goes on the wire.
  (log-tb442-reply!)
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [live-id   (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              rid       [:feature/load "beta"]
              in-flight #(get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
              log       #(get-in (rf/app-db-value f) [:messages :reply-log])
              request!  (fn []
                          (rf/dispatch-sync
                            [:ws/connection
                             [:ws/request {:request-id rid
                                           :body       {:type :silent-no-echo}
                                           :reply      [:ws.test/log-tb442-reply]
                                           :timeout-ms 5000}]]
                            {:frame f}))]
          (request!)
          (let [tok-a (request-token f rid)]
            (is (nil? (log)) "positive control: nothing has settled before the duplicate")
            ;; The duplicate, while A is genuinely still pending.
            (request!)
            (let [tok-b (request-token f rid)]
              (is (not= tok-a tok-b) "the slot now holds a different registration")
              (is (= [{:origin :ws/local :request-id rid :ok false :error :ws/superseded}]
                     (log))
                  "the displaced caller is SETTLED, exactly once, with the documented superseded body")
              (is (= {:origin :ws/local :request-id rid :ok false :error :ws/superseded}
                     (get-in (rf/app-db-value f) [:messages :last-reply]))
                  "the superseded body passes the closed RequestOutcome boundary")
              ;; A's timer is already inert — no cancellation facility needed.
              (rf/dispatch-sync [:ws/connection
                                 [:ws/request-timeout {:request-id       rid
                                                       :token            tok-a
                                                       :source-socket-id live-id}]]
                                {:frame f})
              (is (= tok-b (get-in (in-flight) [rid :token]))
                  "the displaced registration's timer cannot clear the surviving slot")
              (is (= 1 (count (log)))
                  "and cannot settle the displaced caller a second time")
              ;; B is a normal in-flight request with a working deadline.
              (rf/dispatch-sync [:ws/connection
                                 [:ws/request-timeout {:request-id       rid
                                                       :token            tok-b
                                                       :source-socket-id live-id}]]
                                {:frame f})
              (is (not (contains? (in-flight) rid)) "the surviving registration settles on its own deadline")
              (is (= 2 (count (log))))
              (is (= :ws/timeout (:error (last (log))))
                  "no caller is left waiting: two registrations, two terminal outcomes"))))))))

(defn- wire-reply-cannot-settle-a-later-same-id-registration-test []
  ;; rf2-tb442, SECOND PASS (audit of PR #8946) — the wire half of the same
  ;; gap the registration token closed on the deadline side.
  ;;
  ;; A is pending under R. B re-registers R: A is settled locally with
  ;; :ws/superseded, but A's request is already ON THE WIRE, and before the
  ;; fix it went out carrying nothing but the id. So the server had no way to
  ;; say which registration its reply answered, and :receive-message matched
  ;; the SLOT: A's reply arrived, found B's entry, cleared it and fired B's
  ;; callback with A's body. B's callback count stayed at exactly one — which
  ;; is what makes this the quieter half of the bug. The outcome was on time,
  ;; well-formed, and about a question B never asked; B's own reply then
  ;; arrived as unsolicited.
  ;;
  ;; The registration :token now rides out as :request-token and comes back
  ;; on the reply (schema/ReplyMessage requires it), so a reply settles the
  ;; slot only while the token it echoes is still the slot's.
  (log-tb442-reply!)
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [live-id   (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              ;; The reusable per-feature id the spec recommends — again, the
              ;; whole point is that this value legitimately recurs.
              rid       [:feature/load "gamma"]
              in-flight #(get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
              log       #(get-in (rf/app-db-value f) [:messages :reply-log])
              inbox     #(get-in (rf/app-db-value f) [:messages :received])
              request!  (fn [tag]
                          (rf/dispatch-sync
                            [:ws/connection
                             [:ws/request {:request-id rid
                                           ;; :silent-no-echo, so the mock
                                           ;; server answers nothing and we
                                           ;; inject each reply ourselves —
                                           ;; the ORDER is the whole subject.
                                           :body       {:type :silent-no-echo :tag tag}
                                           :reply      [:ws.test/log-tb442-reply]
                                           :timeout-ms 5000}]]
                            {:frame f}))
              ;; A server reply as the wire contract requires it: the id it
              ;; was filed under AND the registration token it answers.
              reply!    (fn [token tag]
                          (rf/dispatch-sync
                            [:ws/connection
                             [:ws/received {:source-socket-id live-id
                                            :body {:type          :reply
                                                   :request-id    rid
                                                   :request-token token
                                                   :ok            true
                                                   :echo          {:type :request :tag tag}}}]]
                            {:frame f}))]
          ;; --- A registers and stays pending --------------------------------
          (request! "A")
          (let [tok-a (request-token f rid)]
            (is (some? tok-a) "A's registration carries a token")
            (is (nil? (log)) "positive control: nothing has settled yet")

            ;; --- B takes the same id while A is genuinely still outstanding --
            (request! "B")
            (let [tok-b (request-token f rid)]
              (is (not= tok-a tok-b) "the slot now holds a different registration")
              (is (= [{:origin :ws/local :request-id rid :ok false :error :ws/superseded}]
                     (log))
                  "A is settled locally as superseded — but A's request is on the wire")

              ;; --- A's SERVER reply lands first -------------------------------
              ;; It passes :trusted-frame? (live socket, closed contract) and
              ;; names an id the slot really does hold. Only the token stands
              ;; between it and B.
              (reply! tok-a "A")
              (is (= tok-b (get-in (in-flight) [rid :token]))
                  "THE REGRESSION: a superseded registration's wire reply does not clear the later registration's slot")
              (is (= 1 (count (log)))
                  "THE REGRESSION: and does not fire the later registration's callback with the earlier request's body")
              (is (some #(= {:type :request :tag "A"} (:echo %)) (inbox))
                  "A's reply is not swallowed — it reaches the inbox as an unsolicited vetted frame")

              ;; --- B's own reply still settles B ------------------------------
              ;; Without this the assertions above would pass on a check that
              ;; simply refuses every wire reply.
              (reply! tok-b "B")
              (is (not (contains? (in-flight) rid))
                  "B settles on the reply that actually answers B")
              (is (= 2 (count (log))))
              (let [outcome (last (log))]
                (is (= :ws/server (:origin outcome)) "…and it is marked server-originated")
                (is (= tok-b (:request-token outcome))
                    "the outcome carries the token of the registration it answers")
                (is (= {:type :request :tag "B"} (:echo outcome))
                    "B's caller gets B's OWN body, never the superseded request's")))))))))

(defn- clean-disconnect-fails-in-flight-request-test []
  ;; rf2-b2jpr — a clean :ws/disconnect destroys the only socket capable of
  ;; replying, so leaving :active through the clean door must settle every
  ;; in-flight request exactly once — the SAME invariant the drop path owns
  ;; (:on-socket-lost), routed through the shared fail-in-flight helper.
  ;; Before the fix, the slot and its waiting :reply-event survived teardown
  ;; forever: the scheduled timeout carries the destroyed socket's id, so
  ;; :current-socket? rejects it after teardown and the only cleanup path is
  ;; gone, including across a later reconnect.
  ;;
  ;; The reply target is a test-local COUNTING event so at-most-once is a
  ;; direct assertion (the example's :ws.app/request-reply only keeps the
  ;; LAST reply — a duplicate would be invisible there). Each invocation is
  ;; logged, then forwarded to the boundary-validated :ws.app/request-reply,
  ;; so the machine's synthesised failure body is also proven to pass the
  ;; closed RequestOutcome contract (dev-lane step-1 enforces the :schema —
  ;; a rejected body would leave :last-reply untouched below).
  (rf/reg-event :ws.test/log-reply
    (fn handler-ws-test-log-reply [{:keys [db]} [_ body]]
      {:db (update-in db [:messages :reply-log] (fnil conj []) body)
       :fx [[:dispatch [:ws.app/request-reply body]]]}))
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [old-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              rid    (random-uuid)
              log    #(get-in (rf/app-db-value f) [:messages :reply-log])
              ;; A request whose wire :type the mock does NOT echo, so it sits
              ;; in-flight with no reply to clear it — genuinely on the wire.
              _      (rf/dispatch-sync [:ws/connection
                                        [:ws/request {:request-id rid
                                                      :body       {:type :silent-no-echo}
                                                      :reply      [:ws.test/log-reply]
                                                      :timeout-ms 5000}]]
                                       {:frame f})
              ;; Captured while the slot still exists — the straggler timeouts
              ;; below carry it, exactly as :register-request scheduled them.
              tok    (request-token f rid)]
          (is (some? old-id))
          (is (some? tok) "the registration was stamped with a token")
          ;; Positive control: the request is genuinely in :in-flight and no
          ;; reply has fired — the witness below cannot pass vacuously.
          (is (contains? (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
                         rid)
              "positive control: the silent request sits in :in-flight before disconnect")
          (is (nil? (log))
              "positive control: no reply-event invocation before disconnect")
          ;; Clean disconnect, mid-flight.
          (rf/dispatch-sync [:ws/connection [:ws/disconnect]] {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= :disconnected (:state s)))
            (is (nil? (socket-id-of s)) "the socket actor is torn down")
            (is (= {} (get-in s [:data :in-flight]))
                ":in-flight cleared on clean disconnect — no stranded slot")
            (is (= [{:origin :ws/local :request-id rid :ok false :error :ws/connection-lost}]
                   (log))
                "exactly one reply-event invocation, with the documented connection-termination body")
            (is (= {:origin :ws/local :request-id rid :ok false :error :ws/connection-lost}
                   (get-in (rf/app-db-value f) [:messages :last-reply]))
                "the synthesised failure body passes the closed RequestOutcome boundary"))
          ;; At-most-once control: deliver the already-scheduled timeout,
          ;; stamped with the DESTROYED socket's id, exactly as
          ;; :register-request scheduled it (new-frame suppresses the real
          ;; :dispatch-later). Nothing may fire twice or resurrect the slot.
          (rf/dispatch-sync [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            tok
                                                   :source-socket-id old-id}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= :disconnected (:state s))
                "the stale timeout does not move the machine")
            (is (= {} (get-in s [:data :in-flight]))
                "the stale timeout resurrects no slot")
            (is (= 1 (count (log)))
                "the callback count stays exactly one after the stale timeout"))
          ;; No leak across a reconnect: connect again (a FRESH socket), then
          ;; deliver the old-socket-stamped timeout once more — a straggler
          ;; landing after reconnect. :current-socket? drops it against the
          ;; new epoch; the new connection inherits no stale bookkeeping.
          (rf/dispatch-sync [:ws/connection
                             [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                            {:frame f})
          (is (true? (machine-has-tag? f :websocket/connected)))
          (let [new-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))]
            (is (some? new-id))
            (is (not= old-id new-id) "reconnect spawned a fresh socket"))
          (rf/dispatch-sync [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            tok
                                                   :source-socket-id old-id}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (true? (machine-has-tag? f :websocket/connected))
                "the straggler timeout leaves the fresh connection up")
            (is (= {} (get-in s [:data :in-flight]))
                "no stale bookkeeping crossed the reconnect")
            (is (= 1 (count (log)))
                "the callback count stays exactly one across the reconnect")))))))

(defn- fatal-fails-in-flight-request-test []
  ;; rf2-ni0ko — the last door out of :active that did not settle :in-flight.
  ;; :ws/fatal is the documented app-level escape hatch (spec/Pattern-WebSocket
  ;; §The machine, ':active / * --:ws/fatal--> :failed'), and leaving :active
  ;; destroys the socket actor exactly as a drop or a clean disconnect does —
  ;; so the SAME invariant applies: every in-flight request settles exactly
  ;; once on the way out. Before the fix the transition carried :record-error
  ;; alone, so the slot and its waiting :reply-event survived teardown
  ;; forever: the scheduled timeout carries the destroyed socket's id, so
  ;; :current-socket? rejects it once the socket is gone and the only cleanup
  ;; path has left with it — including across a later manual :ws/connect out
  ;; of :failed, which is the leak this asserts against.
  ;;
  ;; Mirrors websocket-clean-disconnect-fails-in-flight-request (rf2-b2jpr)
  ;; with :ws/fatal as the exit door, and adds the assertion that door owns:
  ;; the error is still recorded. Composing onto the shared fail-in-flight
  ;; helper rather than replacing :record-error is what keeps both true.
  (rf/reg-event :ws.test/log-fatal-reply
    (fn handler-ws-test-log-fatal-reply [{:keys [db]} [_ body]]
      {:db (update-in db [:messages :reply-log] (fnil conj []) body)
       :fx [[:dispatch [:ws.app/request-reply body]]]}))
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [old-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              rid    (random-uuid)
              log    #(get-in (rf/app-db-value f) [:messages :reply-log])
              ;; A request whose wire :type the mock does NOT echo, so it sits
              ;; in-flight with no reply to clear it — genuinely on the wire.
              _      (rf/dispatch-sync [:ws/connection
                                        [:ws/request {:request-id rid
                                                      :body       {:type :silent-no-echo}
                                                      :reply      [:ws.test/log-fatal-reply]
                                                      :timeout-ms 5000}]]
                                       {:frame f})
              ;; Captured while the slot still exists — the straggler timeouts
              ;; below carry it, exactly as :register-request scheduled them.
              tok    (request-token f rid)]
          (is (some? old-id))
          (is (some? tok) "the registration was stamped with a token")
          ;; Positive control: the request is genuinely in :in-flight and no
          ;; reply has fired — the witness below cannot pass vacuously.
          (is (contains? (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
                         rid)
              "positive control: the silent request sits in :in-flight before :ws/fatal")
          (is (nil? (log))
              "positive control: no reply-event invocation before :ws/fatal")
          ;; The app declares the connection unrecoverable, mid-flight.
          (rf/dispatch-sync [:ws/connection
                             [:ws/fatal {:error :ws/protocol-violation}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (true? (machine-has-tag? f :websocket/failed)))
            (is (nil? (socket-id-of s)) "the socket actor is torn down")
            (is (= :ws/protocol-violation (get-in s [:data :error]))
                "the error is still recorded — fail-in-flight COMPOSES with
                 :record-error rather than displacing it")
            (is (= {} (get-in s [:data :in-flight]))
                ":in-flight cleared on :ws/fatal — no stranded slot")
            (is (= [{:origin :ws/local :request-id rid :ok false :error :ws/connection-lost}]
                   (log))
                "exactly one reply-event invocation, with the documented connection-termination body")
            (is (= {:origin :ws/local :request-id rid :ok false :error :ws/connection-lost}
                   (get-in (rf/app-db-value f) [:messages :last-reply]))
                "the synthesised failure body passes the closed RequestOutcome boundary"))
          ;; At-most-once control: deliver the already-scheduled timeout,
          ;; stamped with the DESTROYED socket's id, exactly as
          ;; :register-request scheduled it (new-frame suppresses the real
          ;; :dispatch-later). Nothing may fire twice or resurrect the slot.
          (rf/dispatch-sync [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            tok
                                                   :source-socket-id old-id}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (true? (machine-has-tag? f :websocket/failed))
                "the stale timeout does not move the machine")
            (is (= {} (get-in s [:data :in-flight]))
                "the stale timeout resurrects no slot")
            (is (= 1 (count (log)))
                "the callback count stays exactly one after the stale timeout"))
          ;; No leak across the manual recovery :failed offers: :ws/connect
          ;; out of :failed spawns a FRESH socket, then the old-socket-stamped
          ;; timeout lands as a straggler. :current-socket? drops it against
          ;; the new epoch; the new connection inherits no stale bookkeeping.
          (rf/dispatch-sync [:ws/connection
                             [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                            {:frame f})
          (is (true? (machine-has-tag? f :websocket/connected)))
          (let [new-id (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))]
            (is (some? new-id))
            (is (not= old-id new-id) "the manual reconnect spawned a fresh socket"))
          (rf/dispatch-sync [:ws/connection
                             [:ws/request-timeout {:request-id       rid
                                                   :token            tok
                                                   :source-socket-id old-id}]]
                            {:frame f})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (true? (machine-has-tag? f :websocket/connected))
                "the straggler timeout leaves the fresh connection up")
            (is (= {} (get-in s [:data :in-flight]))
                "no stale bookkeeping crossed the manual reconnect out of :failed")
            (is (= 1 (count (log)))
                "the callback count stays exactly one across the reconnect")))))))

(defn- queued-request-registers-and-replies-on-connect-test []
  ;; rf2-ryt25d — a :ws/request issued OFF-connection must be buffered as its
  ;; event and, on connect, rejoin :register-request so it registers, sends
  ;; the body (not the envelope) and correlates its reply. Covers the offline
  ;; (disconnected) window end-to-end, then the reconnect window's enqueue.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        ;; --- offline window: request while :disconnected --------------------
        (let [rid (random-uuid)]
          (rf/dispatch-sync [:ws.app/request "queued-hello"]
                            {:frame f :rf.cofx {:ws.app/request-id rid}})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= :disconnected (:state s)))
            (is (= 1 (count (get-in s [:data :queue]))))
            (is (= :ws/request (ffirst (get-in s [:data :queue])))
                "buffered as the WHOLE :ws/request event, not a bare body"))
          ;; Connect — the :connected entry flushes the queued request through
          ;; :register-request; the mock echoes and the reply correlates, all
          ;; inside this sync dispatch.
          (rf/dispatch-sync [:ws/connection
                             [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                            {:frame f})
          (let [s  (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                db (rf/app-db-value f)]
            (is (true? (machine-has-tag? f :websocket/connected)))
            (is (= [] (get-in s [:data :queue])) "queue drained on connect")
            (is (= {} (get-in s [:data :in-flight]))
                "queued request registered AND its reply cleared the slot")
            (let [reply (get-in db [:messages :last-reply])]
              (is (some? reply) "the queued request got a correlated reply")
              (is (= rid (:request-id reply))
                  "correlation id round-tripped from the queued envelope")
              (is (true? (:ok reply)))
              (is (= {:type :request :body "queued-hello"} (:echo reply))
                  "the request BODY (not the queue envelope) went on the wire"))))
        ;; --- reconnect window: request while :reconnecting -----------------
        ;; Drop to :reconnecting, then a request there is buffered the same way
        ;; (the reconnect window uses the identical :enqueue-message path).
        (messages/simulate-disconnect! (rf/capture-frame f))
        (is (true? (machine-has-tag? f :websocket/reconnecting)))
        (let [rid2 (random-uuid)]
          (rf/dispatch-sync [:ws.app/request "reconnect-hello"]
                            {:frame f :rf.cofx {:ws.app/request-id rid2}})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= 1 (count (get-in s [:data :queue])))
                "a request issued while :reconnecting is buffered")
            (is (= :ws/request (ffirst (get-in s [:data :queue])))
                "buffered as the whole :ws/request event for a later flush")))))))

(defn- drive-to-failed!
  "Walk the connection machine into top-level `:failed` the same way
   `max-retries-failed-test` does: connect (spawning the actor), seed
   `:retries` past `:max-retries`, then fire a live `:ws/closed` so the parent
   steps to `:reconnecting` and immediately on to `:failed` via the
   `:max-retries-exceeded?` `:always` cascade."
  [f]
  (rf/dispatch-sync [:ws/connection
                     [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                    {:frame f})
  (re-frame.frame/swap-runtime-db! f
    (fn [rt]
      (let [max-retries (get-in rt [:rf.runtime/machines :snapshots :ws/connection :data :max-retries])]
        (update-in rt [:rf.runtime/machines :snapshots :ws/connection :data]
                   assoc :retries (inc max-retries)))))
  (let [snap-before (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
    (rf/dispatch-sync [:ws/connection
                       [:ws/closed {:source-socket-id (socket-id-of snap-before)
                                    :code 1006}]]
                      {:frame f})))

(defn- failed-state-queues-and-drains-test []
  ;; rf2-3fc89f.29 — the machine's offline contract must be UNIFORM: a send (or
  ;; request) issued while `:failed` must QUEUE, not vanish. `:failed` is a
  ;; top-level state, so it does NOT inherit `:active`'s parent `:ws/send` /
  ;; `:ws/request` enqueue transitions — it has to carry its own, exactly like
  ;; `:disconnected` and `:reconnecting`. Then a manual `:ws/connect` out of
  ;; `:failed` reaches `:connected` and the `:always` `:flush-queue` drains the
  ;; buffered work. Regression for the acknowledged-message-loss bug: before
  ;; the fix, `:ws.app/send` clears the draft and the machine drops the
  ;; unhandled send, so the message is lost with no way to recover it.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (drive-to-failed! f)
        (is (= :failed (:state (snapshot (:rf.db/runtime (rf/frame-state-value f)))))
            "precondition: machine is in top-level :failed")
        (is (true? (machine-has-tag? f :websocket/failed)))
        ;; --- a :ws/send in :failed must QUEUE (before the fix it was LOST) ---
        (rf/dispatch-sync [:ws/connection
                           [:ws/send {:type :note :body "keep-me"}]]
                          {:frame f})
        (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
          (is (= :failed (:state s))
              "a :ws/send does not move the machine out of :failed")
          (is (= [[:ws/send {:type :note :body "keep-me"}]]
                 (get-in s [:data :queue]))
              "the :failed-state send was buffered, not dropped"))
        ;; --- a :ws/request in :failed must QUEUE alongside it ----------------
        (let [rid (random-uuid)]
          (rf/dispatch-sync [:ws.app/request "failed-hello"]
                            {:frame f :rf.cofx {:ws.app/request-id rid}})
          (let [s (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
            (is (= 2 (count (get-in s [:data :queue])))
                "the :failed-state request was buffered alongside the send")
            (is (= :ws/request (first (get-in s [:data :queue 1])))
                "buffered as the WHOLE :ws/request event for a later flush"))
          ;; --- manual reconnect drains the queue (:failed → :connected) ------
          ;; :record-and-reset (the :ws/connect action out of :failed) leaves
          ;; :queue untouched, so the :connected entry's :always flush finds it.
          (rf/dispatch-sync [:ws/connection
                             [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                            {:frame f})
          (let [s  (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                db (rf/app-db-value f)]
            (is (true? (machine-has-tag? f :websocket/connected))
                "manual :ws/connect out of :failed reconnects to :connected")
            (is (= [] (get-in s [:data :queue]))
                ":connected entry's :always :flush-queue drained the failed-state queue")
            (is (= {} (get-in s [:data :in-flight]))
                "the queued request registered on flush AND its reply cleared the slot")
            (let [reply (get-in db [:messages :last-reply])]
              (is (some? reply)
                  "the queued failed-state request got a correlated reply (not lost)")
              (is (= rid (:request-id reply))
                  "correlation id round-tripped from the queued envelope")
              (is (true? (:ok reply))))))))))

(defn- failed-state-enqueue-transitions-declared-test []
  ;; rf2-3fc89f.29 — structural mirror of the runtime proof above: `:failed`
  ;; carries the SAME `:ws/send` / `:ws/request` → `:enqueue-message`
  ;; transitions as `:disconnected` and `:reconnecting`. The offline queue path
  ;; is uniform across every non-connected top-level state; `:failed` is not
  ;; special-cased into silently dropping accepted work.
  ;; `defmachine` co-locates a `:source-coords` map into each transition for
  ;; Xray click-to-source, so compare the `:action` value (not the whole map)
  ;; and assert it's a self-transition (no `:target` — enqueue leaves the
  ;; state unchanged).
  (let [m ws.connection/connection-machine]
    (doseq [state [:disconnected :reconnecting :failed]
            ev    [:ws/send :ws/request]]
      (let [t (get-in m [:states state :on ev])]
        (is (= :enqueue-message (:action t))
            (str state " enqueues " ev))
        (is (nil? (:target t))
            (str state " " ev " is a self-transition (no state change)"))))))

;; ============================================================================
;; REQUEST/REPLY + SERVER PUSH + SUBSCRIPTIONS
;; ============================================================================

;; ----------------------------------------------------------------------------
;; APP-LEVEL request/reply boundary
;;
;; This request/reply path uses the Pattern-WebSocket APP-LEVEL correlation
;; shape — a per-message `:request-id`, a registered `:reply` event target,
;; and an `:in-flight` correlation map on the connection machine's `:data`
;; — and it is INTENTIONALLY OUTSIDE the EP-0011 uniform reply envelope.
;;
;; The uniform envelope applies to framework-shipped managed async surfaces.
;; re-frame2 ships no managed WebSocket effect or reserved `:rf.ws/*`
;; namespace, so this application-owned connection also owns its correlation
;; vocabulary. Pattern-WebSocket uses this `:in-flight` shape as its worked
;; example.
;;
;; The assertions below make the boundary explicit: the reply is the app's own
;; message body, and it carries none of the framework reply-map
;; vocabulary (`:rf/reply-to`, `:status`, `:work/id`, `:work/kind`,
;; `:completed-at`).
(defn- request-reply-correlation-test []
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :cred-ref :ws.demo/cred-a}]]
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
                snap (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
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
              ;; SERVER PROVENANCE CONTROL (rf2-iyjae audit): the machine
              ;; marks a wire reply as the server's, so the closed
              ;; RequestOutcome union routes it to the :ws/server arm and
              ;; it can never be read as a locally minted loss/timeout.
              (is (= :ws/server (:origin last-reply))
                  "a correlated wire reply is marked as server-originated")
              ;; rf2-tb442 (audit PR #8946): the registration discriminator
              ;; made the ROUND TRIP through the real mock server —
              ;; :register-request put it on the outbound frame, the server
              ;; echoed it, and :receive-message matched it against the slot.
              ;; Pinned against :next-token rather than a literal, so it reads
              ;; as "the token this registration was minted with".
              (is (= (dec (get-in snap [:data :next-token]))
                     (:request-token last-reply))
                  "the registration token went out on the wire and came back on the reply")
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
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (let [pre-count (count (get-in (rf/app-db-value f) [:messages :received]))]
          (messages/send-server-push! (rf/capture-frame f)
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
                                         :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (rf/dispatch-sync [:ws.app/subscribe-demo] {:frame f})
        ;; EP-0001 (rf2-vzld77): the machine snapshot is runtime-db; `:messages`
        ;; is app-db.
        (let [db (rf/app-db-value f)
              snap (snapshot (:rf.db/runtime (rf/frame-state-value f)))]
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
  ;; The frames here satisfy the closed PushMessage arm — since rf2-iyjae's
  ;; audit the push arm is `:closed true` (an OPEN arm would let a hostile
  ;; frame carry a :request-id past the wire contract), so a test frame with
  ;; an ad-hoc extra key would now be refused at the ingress and this test
  ;; would be asserting against an empty log.
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/handle-message {:type :push :note "1"}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :note "2"}] {:frame f})
    (rf/dispatch-sync [:ws/handle-message {:type :push :note "3"}] {:frame f})
    (let [received (get-in (rf/app-db-value f) [:messages :received])]
      (is (= [{:type :push :note "3"}
              {:type :push :note "2"}
              {:type :push :note "1"}]
             (mapv #(dissoc % :rx-seq) received))
          ":received list is newest-first")
      ;; Each message carries a monotonic :rx-seq stamp; newest-first means
      ;; the head has the highest seq, giving every <li> a stable React key.
      (is (= [2 1 0] (mapv :rx-seq received))
          ":rx-seq is a stable, monotonic, newest-first identity"))))

;; ----------------------------------------------------------------------------
;; TRUST BOUNDARIES (rf2-iyjae) — inbound frames + credential discipline
;; ----------------------------------------------------------------------------

(defn- inbound-boundary-structural-test []
  ;; The RELEASE-build half of the inbound contract, pinned structurally.
  ;; In this dev lane the router's step-1 validation enforces the :schema
  ;; (the :rf.schema/at-boundary interceptor is a deliberate dev no-op —
  ;; the framework pins that split in re-frame.schemas-cljs-test
  ;; boundary-interceptor-noop-in-dev-cljs), so behavioural rejection
  ;; alone cannot distinguish a dev-only :schema tripwire from a
  ;; release-resident boundary: drop the interceptor and every dev-lane
  ;; rejection test stays green while the production build loses the
  ;; check entirely. What makes the check release-resident is the
  ;; registration carrying BOTH the :schema and the :rf.schema/at-boundary
  ;; reference — this is the assertion that goes red if someone removes
  ;; the interceptor and leaves the dev tripwire.
  (doseq [ingress [:ws/handle-message :ws.app/request-reply]]
    (let [m (rf/handler-meta {:source :store :kind :event :id ingress})]
      (is (some? (:schema m))
          (str ingress " declares a :schema (the closed wire contract)"))
      (is (some #{:rf.schema/at-boundary} (:interceptors m))
          (str ingress " attaches :rf.schema/at-boundary — the release-resident half")))))

(defn- example-registrations-are-live-test []
  ;; rf2-idv1m — THE ANTI-SHADOWING CONTROL, and the reason the fixture
  ;; above calls the example's `register!` fns instead of re-declaring what
  ;; they install.
  ;;
  ;; `register-all!` used to mirror the two ingress registrations inline.
  ;; It runs on every test and `reg-*` is last-write-wins, so those copies
  ;; replaced the example's own handlers before a single assertion ran, and
  ;; the suite became a certificate for the copy: a fault planted in
  ;; `websocket.messages`' `:ws.app/request-reply` body left all 29 tests
  ;; green, and `inbound-boundary-structural-test` read back the `:schema`
  ;; and `:rf.schema/at-boundary` this very file had just installed rather
  ;; than the example's. Three assertions, one per way the coupling can be
  ;; lost.
  (with-new-frame [f (new-frame)]
    (let [m (rf/handler-meta {:source :store :kind :event :id :ws.app/request-reply})]
      ;; (1) IDENTITY. The live registration is the EXAMPLE's, not a
      ;; look-alike. The example documents this ingress at length and the
      ;; hand-mirrored twin never carried the prose, so the registered
      ;; `:doc` is a direct, cheap witness to WHICH definition won the
      ;; last write — and it is what reds first if a twin comes back.
      (is (some? (:doc m))
          ":ws.app/request-reply is registered with the example's own :doc")
      (is (str/includes? (str (:doc m)) "RequestOutcome")
          "the live :ws.app/request-reply IS websocket.messages' registration")
      ;; (2) BOUNDARY METADATA, read off the live registry. Reds if the
      ;; example drops `:rf.schema/at-boundary` (the release-resident half)
      ;; or its closed `:schema`. This is the same pair
      ;; `inbound-boundary-structural-test` pins — asserted here too,
      ;; because until this fn existed that test was reading the fixture's
      ;; own metadata back to itself.
      (is (some? (:schema m))
          "the example declares the closed RequestOutcome wire contract")
      (is (some #{:rf.schema/at-boundary} (:interceptors m))
          "the example attaches :rf.schema/at-boundary"))
    ;; (3) BODY. Driven through a real dispatch, so it is the REGISTERED
    ;; handler that runs. Reds if the example's handler body stops
    ;; recording the outcome — the exact fault that used to pass.
    (let [outcome {:origin     :ws/local
                   :request-id (random-uuid)
                   :ok         false
                   :error      :ws/timeout}]
      (rf/dispatch-sync [:ws.app/request-reply outcome] {:frame f})
      (is (= outcome (get-in (rf/app-db-value f) [:messages :last-reply]))
          "the example's own :ws.app/request-reply body recorded the outcome"))))

(defn- inbound-boundary-rejection-test []
  ;; Malformed and unknown frames from the LIVE socket are refused, and
  ;; refused EARLY: the audit of the first rf2-iyjae pass found the
  ;; connection machine settling correlation state on the strength of an
  ;; unvalidated frame, so app-db stayed clean while a pending request's
  ;; :in-flight slot was silently consumed and its caller left waiting
  ;; forever. The assertions below therefore pin THREE things per hostile
  ;; frame: [:messages :received] and [:messages :last-reply] do not move,
  ;; the refusal is observable as :rf.error/schema-validation-failure
  ;; attributed to the ingress, and — the one the first pass missed — the
  ;; :in-flight slot is still there afterwards.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                          {:frame f})
        (is (true? (machine-has-tag? f :websocket/connected)))
        (let [live-id  (socket-id-of (snapshot (:rf.db/runtime (rf/frame-state-value f))))
              traces   (atom [])
              in-flight #(get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                 [:data :in-flight])
              deliver! (fn [body]
                         (rf/dispatch-sync [:ws/connection
                                            [:ws/received {:source-socket-id live-id
                                                           :body             body}]]
                                           {:frame f}))]
          (rf/register-listener! :trace ::boundary-traces
                                 (fn [r] (swap! traces conj r)))
          (try
            (let [pre-db    (rf/app-db-value f)
                  pre-msgs  (get-in pre-db [:messages :received])
                  pre-reply (get-in pre-db [:messages :last-reply])
                  rid       (random-uuid)]
              ;; Park a request in-flight (the mock doesn't echo this
              ;; :type), so every hostile frame below has a real
              ;; correlation slot to try to consume.
              (rf/dispatch-sync [:ws/connection
                                 [:ws/request {:request-id rid
                                               :body       {:type :silent-no-echo}
                                               :reply      [:ws.app/request-reply]
                                               :timeout-ms 5000}]]
                                {:frame f})
              (is (contains? (in-flight) rid)
                  "positive control: the request is genuinely in-flight before the hostile frames")

              ;; (1) unknown :type — the closed union has no arm for it.
              (deliver! {:type :evil/exec :cmd "drop tables"})
              ;; (2) malformed :push — :note must be a string.
              (deliver! {:type :push :note 42})
              ;; (3) malformed correlated :reply — right :request-id, but
              ;; missing :ok. It names a live slot, so before the audit fix
              ;; this frame cleared that slot on its way to being refused.
              (deliver! {:type :reply :request-id rid})
              ;; (4) LOCAL-FAILURE-SHAPED hostile frame. A server that saw
              ;; the wire request id sends back exactly the body the
              ;; machine mints for a timeout, with no :type at all. The
              ;; closed InboundMessage union has no arm for a frame without
              ;; a :type, so it never reaches the correlation branch — and
              ;; because :origin is stamped by the MACHINE after receipt,
              ;; it could not have matched the :ws/local arm of
              ;; RequestOutcome even if it had.
              (deliver! {:request-id rid :ok false :error :ws/timeout})
              ;; (5) hostile PUSH smuggling a :request-id. This is why the
              ;; push arm is `:closed true`: an open arm would let this
              ;; frame pass the wire contract under a :type that has
              ;; nothing to do with request-reply.
              (deliver! {:type :push :note "hi" :request-id rid})
              ;; (6) rf2-tb442 — an otherwise PERFECT reply that omits
              ;; :request-token. This is the shape a server which ignores
              ;; the registration discriminator sends back, and admitting
              ;; it would restore correlate-by-id-alone for every request.
              ;; :request-token is required by ReplyMessage, so the frame
              ;; is refused at the wire contract instead: nothing in the
              ;; connection moves, the slot below survives, and the
              ;; rejection record names the missing key on the very first
              ;; reply rather than leaving a silent mis-correlation.
              (deliver! {:type :reply :request-id rid :ok true})

              (let [db (rf/app-db-value f)]
                (is (= pre-msgs (get-in db [:messages :received]))
                    "no malformed/unknown frame reached [:messages :received]")
                (is (= pre-reply (get-in db [:messages :last-reply]))
                    "no malformed/unknown frame moved [:messages :last-reply]"))
              ;; THE AUDIT ASSERTION: refusing a frame at app-db is not
              ;; enough if the machine already spent the correlation.
              (is (contains? (in-flight) rid)
                  ":in-flight survives every hostile frame — no silently consumed correlation")

              ;; The refusals are observable — the schema-validation
              ;; failure signal fired, attributed to the ingress. (In this
              ;; dev lane the signal is step-1's :where :event emission; the
              ;; :source :boundary spelling is the production build's, where
              ;; the interceptor takes over — see the structural test.)
              (let [rejections (filter #(= :rf.error/schema-validation-failure
                                           (:operation %))
                                       @traces)
                    by-event   (into #{} (keep #(-> % :tags :event-id)) rejections)]
                (is (contains? by-event :ws/handle-message)
                    "each refusal is observable and attributed to the ingress")
                (is (<= 6 (count rejections))
                    "every one of the six refused frames was refused, not just the first"))

              ;; Control: a VALID push still lands after all that.
              (deliver! {:type :push :note "still fine"})
              (is (= (inc (count pre-msgs))
                     (count (get-in (rf/app-db-value f) [:messages :received])))
                  "a valid frame still lands — the boundary rejects, it doesn't block")

              ;; PENDING-TIMEOUT CONTROL. The request the hostile frames
              ;; tried to consume is still live, so its deadline still
              ;; settles it — with the machine's own locally minted body.
              ;; This is the assertion that makes "the slot survived" mean
              ;; "the caller still gets an answer" rather than "the map
              ;; still has a key in it".
              (rf/dispatch-sync [:ws/connection
                                 [:ws/request-timeout {:request-id       rid
                                                       :token            (request-token f rid)
                                                       :source-socket-id live-id}]]
                                {:frame f})
              (is (not (contains? (in-flight) rid))
                  "the surviving slot settles on its own deadline")
              (is (= {:origin :ws/local :request-id rid :ok false :error :ws/timeout}
                     (get-in (rf/app-db-value f) [:messages :last-reply]))
                  "the caller is answered by the machine's locally minted timeout, not by the hostile frame"))
            (finally
              (rf/unregister-listener! :trace ::boundary-traces))))))))

(defn- request-reply-ingress-rejection-test []
  ;; The SECOND app-db-writing ingress, held to its own contract. Since the
  ;; audit fix the connection machine refuses a bad frame before it ever
  ;; reaches a :reply-event, so this ingress is no longer exercised through
  ;; the machine — which is exactly why it needs its own test. A boundary
  ;; that is only safe because something upstream is careful is not a
  ;; boundary.
  ;;
  ;; The outcome is delivered from inside a cascade (an :fx dispatch), the
  ;; way the machine delivers one, rather than as a bare top-level
  ;; dispatch-sync.
  (rf/reg-event :ws.test/forward-outcome
    (fn handler-ws-test-forward-outcome [_ [_ body]]
      {:fx [[:dispatch [:ws.app/request-reply body]]]}))
  (with-new-frame [f (new-frame)]
    (rf/dispatch-sync [:ws.messages/initialise] {:frame f})
    (let [traces (atom [])
          rid    (random-uuid)
          send!  #(rf/dispatch-sync [:ws.test/forward-outcome %] {:frame f})
          reply  #(get-in (rf/app-db-value f) [:messages :last-reply])]
      (rf/register-listener! :trace ::outcome-traces (fn [r] (swap! traces conj r)))
      (try
        ;; A wire reply WITHOUT the machine's :origin stamp has no arm in
        ;; the closed RequestOutcome union — provenance is required, not
        ;; inferred from shape.
        (send! {:type :reply :request-id rid :request-token 0 :ok true})
        (is (nil? (reply))
            "an unstamped wire reply has no arm in the closed outcome union")
        ;; A body claiming to be locally minted but carrying wire fields
        ;; fails the closed :ws/local arm.
        (send! {:origin :ws/local :type :reply :request-id rid :ok false :error :ws/timeout})
        (is (nil? (reply))
            "a wire-shaped body cannot borrow the locally-minted arm")
        ;; An unknown :origin is a rejection, not a fall-through.
        (send! {:origin :ws/somewhere-else :request-id rid :ok false :error :ws/timeout})
        (is (nil? (reply))
            "an unrecognised :origin is refused by the closed multi")
        (let [by-event (into #{} (keep #(-> % :tags :event-id))
                             (filter #(= :rf.error/schema-validation-failure (:operation %))
                                     @traces))]
          (is (contains? by-event :ws.app/request-reply)
              "the correlated-reply-path refusal is observable and attributed"))
        ;; A stamped server reply that drops the registration discriminator
        ;; still has no arm: :request-token is a REQUIRED field of the
        ;; closed wire-reply shape, shared with ReplyMessage.
        (send! {:origin :ws/server :type :reply :request-id rid :ok true})
        (is (nil? (reply))
            "a server outcome missing :request-token fails the closed :ws/server arm")
        ;; Controls: BOTH legitimate producers still land.
        (send! {:origin :ws/server :type :reply :request-id rid :request-token 0 :ok true})
        (is (= :ws/server (:origin (reply)))
            "a properly stamped server reply lands")
        (send! {:origin :ws/local :request-id rid :ok false :error :ws/connection-lost})
        (is (= :ws/local (:origin (reply)))
            "a locally minted loss body lands")
        (finally
          (rf/unregister-listener! :trace ::outcome-traces))))))

(defn- credential-discipline-test []
  ;; AC 2 (rf2-iyjae): the resolved bearer is a distinctive sentinel
  ;; ("demo-bearer-secret-…", websocket.messages/resolve-credential). It
  ;; must be absent from the machine snapshot, app-db, and the whole
  ;; exercised event/trace surface across connect, drop/reconnect, and
  ;; rotation — and resolution must genuinely GATE authentication.
  ;;
  ;; Known limit, recorded so a green here is not over-read (rf2-iyjae
  ;; audit): this sweep reads serialisable surfaces, and a value captured
  ;; by a lexical host closure is on none of them. It cannot tell a bearer
  ;; resolved at the auth write from one resolved at socket-open and held
  ;; by the stored socket handle for the socket's lifetime — the audit
  ;; found exactly that, and the repair (resolving inside
  ;; mock-socket-for-actor's :auth branch, so the stored handle closes
  ;; over :cred-ref alone) is structural. What IS behavioural, and asserted
  ;; below, is that resolution gates authentication: an unresolvable
  ;; reference fails auth, so the seam is load-bearing rather than
  ;; decorative.
  (with-sync-mock!
    (fn []
      (with-new-frame [f (new-frame)]
        (let [traces (atom [])
              events (atom [])]
          (rf/register-listener! :trace  ::cred-traces (fn [r] (swap! traces conj r)))
          (rf/register-listener! :events ::cred-events (fn [r] (swap! events conj r)))
          (try
            ;; Initial connect authenticates via the closure-resolved bearer.
            (rf/dispatch-sync [:ws/connection
                               [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]]
                              {:frame f})
            (is (true? (machine-has-tag? f :websocket/connected))
                "initial connect authenticates through the resolver seam")
            ;; Rotate to cred-b, then drop: the reconnect's fresh spawn must
            ;; read the ROTATED reference out of :data and still authenticate.
            (rf/dispatch-sync [:ws/connection [:ws/rotate-cred :ws.demo/cred-b]]
                              {:frame f})
            (messages/simulate-disconnect! (rf/capture-frame f))
            (is (true? (machine-has-tag? f :websocket/reconnecting)))
            (fire-after-timer! f)
            (is (true? (machine-has-tag? f :websocket/connected))
                "reconnect after rotation authenticates with the rotated reference")
            (is (= :ws.demo/cred-b
                   (get-in (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                           [:data :cred-ref])))
            ;; The negative arm — rotate to an UNKNOWN reference and drop:
            ;; the fresh socket resolves nil, the mock's server side refuses
            ;; the auth frame, and the machine lands in :failed. This is
            ;; what proves the rotated reference actually REACHED the next
            ;; socket and that authentication depends on resolution — the
            ;; seam is load-bearing, not decorative.
            (rf/dispatch-sync [:ws/connection [:ws/rotate-cred :ws.demo/revoked]]
                              {:frame f})
            (messages/simulate-disconnect! (rf/capture-frame f))
            (is (true? (machine-has-tag? f :websocket/reconnecting)))
            (fire-after-timer! f)
            (is (true? (machine-has-tag? f :websocket/failed))
                "an unresolvable reference fails authentication — resolution gates auth")
            ;; The sentinel sweep: nothing the framework can inspect ever
            ;; saw a bearer. Sweep the machine snapshot, app-db, and every
            ;; captured trace + event record in one pr-str.
            (let [surface (pr-str {:snapshot (snapshot (:rf.db/runtime (rf/frame-state-value f)))
                                   :app-db   (rf/app-db-value f)
                                   :traces   @traces
                                   :events   @events})]
              ;; Control for the sweep itself: it must be able to SEE
              ;; content that is genuinely on the surface — the opaque
              ;; references are (they ride :data and dispatches by design).
              (is (str/includes? surface ":ws.demo/cred-b")
                  "sweep control: the sweep sees the opaque reference that IS on the surface")
              (is (not (str/includes? surface "demo-bearer-secret"))
                  "the raw bearer sentinel appears NOWHERE on the exercised snapshot/app-db/event/trace surface")
              (is (not (str/includes? surface ":auth-token"))
                  "no raw-token field anywhere on the exercised surface"))
            (finally
              (rf/unregister-listener! :trace  ::cred-traces)
              (rf/unregister-listener! :events ::cred-events))))))))

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

(deftest websocket-rotate-cred
  (testing ":ws/rotate-cred — updates :data :cred-ref (the opaque reference only)"
    (rotate-cred-test)))

(deftest websocket-disconnect-cleanly
  (testing "clean :ws/disconnect — :connected → :disconnected, socket-id cleared"
    (disconnect-cleanly-test)))

(deftest websocket-stale-lifecycle-events-dropped
  (testing "rf2-3cgvt7 — a stale :ws/closed from a replaced socket is dropped
            by :current-socket? (live connection survives); the real close passes"
    (stale-lifecycle-events-dropped-test)))

(deftest websocket-stale-auth-events-guarded
  (testing "rf2-3cgvt7 — the :current-socket? guard rejects stale-sourced auth
            events, and :ws/opened / :ws/auth-ok / :ws/auth-failed / :ws/closed
            all carry it"
    (stale-auth-events-guarded-test)))

(deftest websocket-drop-fails-in-flight-request
  (testing "rf2-r1rkvb — a socket drop fails + clears every in-flight request
            (no leak); the waiting reply-event gets a connection-lost body"
    (drop-fails-in-flight-request-test)))

(deftest websocket-timeout-fails-in-flight-request
  (testing "rf2-vqg8l6 — a request that times out on a still-live socket fails +
            clears its in-flight slot; the waiting reply-event gets a :ws/timeout
            body, and the connection stays up"
    (timeout-fails-in-flight-request-test)))

(deftest websocket-stale-timeout-cannot-settle-later-same-id-request
  (testing "rf2-tb442 — an uncancelled timeout from an EARLIER request under the
            same id, on the same still-live socket, can neither clear the later
            request's slot nor fire its callback; the later request's own
            deadline still settles it"
    (stale-timeout-cannot-settle-later-same-id-request-test)))

(deftest websocket-duplicate-in-flight-request-supersedes
  (testing "rf2-tb442 — re-registering an id that is still in flight supersedes:
            the displaced caller is settled once with :ws/superseded rather than
            silently overwritten, and its now-obsolete timer is inert"
    (duplicate-in-flight-request-supersedes-test)))

(deftest websocket-wire-reply-cannot-settle-a-later-same-id-registration
  (testing "rf2-tb442 (audit PR #8946) — a SERVER reply answering a superseded
            registration can neither clear the later same-id registration's slot
            nor deliver its body to that registration's caller; the registration
            token rides the wire and the reply must echo it back. The later
            registration still settles on the reply that actually answers it"
    (wire-reply-cannot-settle-a-later-same-id-registration-test)))

(deftest websocket-clean-disconnect-fails-in-flight-request
  (testing "rf2-b2jpr — a clean :ws/disconnect settles every in-flight request
            exactly once: the slot clears, the waiting reply-event fires one
            :ws/connection-lost failure, and the destroyed socket's stale
            timeout resurrects nothing — before or after a reconnect"
    (clean-disconnect-fails-in-flight-request-test)))

(deftest websocket-fatal-fails-in-flight-request
  (testing "rf2-ni0ko — :ws/fatal, the app-level escape hatch out of :active,
            settles every in-flight request exactly once AND still records the
            error: the slot clears, the waiting reply-event fires one
            :ws/connection-lost failure, and the destroyed socket's stale
            timeout resurrects nothing — before or after the manual :ws/connect
            recovery out of :failed"
    (fatal-fails-in-flight-request-test)))

(deftest websocket-queued-request-registers-and-replies-on-connect
  (testing "rf2-ryt25d — a :ws/request buffered off-connection registers and
            correlates its reply on connect; the reconnect window buffers alike"
    (queued-request-registers-and-replies-on-connect-test)))

(deftest websocket-failed-state-queues-and-drains
  (testing "rf2-3fc89f.29 — a :ws/send (and :ws/request) issued while :failed is
            QUEUED, not dropped; a manual :ws/connect out of :failed reaches
            :connected and the :always flush drains it — no acknowledged
            message is lost"
    (failed-state-queues-and-drains-test)))

(deftest websocket-failed-state-enqueue-transitions-declared
  (testing "rf2-3fc89f.29 — :failed carries the same :ws/send / :ws/request →
            :enqueue-message transitions as :disconnected and :reconnecting;
            the offline queue path is uniform across non-connected states"
    (failed-state-enqueue-transitions-declared-test)))

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

(deftest websocket-inbound-boundary-structural
  (testing "rf2-iyjae — both app-db-writing ingresses declare the closed wire
            :schema AND attach :rf.schema/at-boundary, the release-resident
            half a dev-lane rejection test cannot see"
    (inbound-boundary-structural-test)))

(deftest websocket-inbound-boundary-rejection
  (testing "rf2-iyjae — malformed bodies, unknown :type values, a
            local-failure-shaped frame and a :request-id-smuggling push are
            all refused observably, without touching :messages,
            :last-reply, or the pending :in-flight correlation; a valid
            frame still lands and the surviving request still times out"
    (inbound-boundary-rejection-test)))

(deftest websocket-request-reply-ingress-rejection
  (testing "rf2-iyjae — :ws.app/request-reply holds its own closed
            RequestOutcome contract independently of the machine: an
            unstamped or wrongly stamped outcome is refused observably,
            while both legitimate producers still land"
    (request-reply-ingress-rejection-test)))

(deftest websocket-credential-discipline
  (testing "rf2-iyjae — connect/reconnect/rotation authenticate through the
            opaque :cred-ref seam; an unresolvable reference fails auth; the
            raw bearer sentinel is absent from the exercised snapshot,
            app-db, event and trace surface"
    (credential-discipline-test)))

(deftest websocket-example-registrations-are-live
  (testing "rf2-idv1m — the suite exercises the EXAMPLE's ingress
            registration, not a fixture-local twin: its :doc, its closed
            :schema, its :rf.schema/at-boundary and its handler body are all
            read back off the live registry / a real dispatch"
    (example-registrations-are-live-test)))
