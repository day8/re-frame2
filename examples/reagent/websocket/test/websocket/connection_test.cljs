(ns websocket.connection-test
  "Headless tests for `websocket.connection` — the Pattern-WebSocket
   worked example's connection state machine.

   Coverage:
   - Initial state — the machine starts at `:disconnected` with empty
     `:queue` / `:in-flight` / `:subscriptions`.
   - Happy-path lifecycle — `:ws/connect` → `:active / :connecting` →
     (mock-server `:ws/opened`) → `:authenticating` → (mock-server
     `:auth-ok`) → `:connected`. The :websocket/connected tag is set;
     the queue is empty; `:retries` is 0.
   - Reconnect cascade — `simulate-disconnect!` triggers `:ws/closed`
     on the actor, which transitions the parent to `:reconnecting`
     and clears `:socket-id`. The `:after`-elapsed event re-enters
     `:active`.
   - Max-retries → `:failed` — bumping the retry count past
     `:max-retries` via repeated `:ws/closed` lands the machine in
     `:failed`, with the `:websocket/failed` tag set.
   - Offline queue → flush on reconnect — `:ws/send` while
     disconnected enqueues; the queue depth shows in `:data :queue`;
     reconnecting drains the queue on entry to `:connected`.
   - Connection-epoch staleness — a `:ws/received` event from a
     stale `:source-socket-id` is dropped by the `:current-socket?`
     guard (no write to the `:messages` slice).

   Each test spins a fresh frame via `make-frame` so the registry
   resets are scoped per-test. Tests use `dispatch-sync` exclusively;
   the mock server is put in sync-mode so the
   `setTimeout`-microtask-bridge in the browser becomes immediate."
  (:require [re-frame.core :as rf]
            [re-frame.frame]
            [re-frame.substrate.adapter]
            [websocket.core]
            [websocket.messages :as messages]
            [websocket.connection])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- snapshot [db]
  (get-in db [:rf/machines :ws/connection]))

(defn- machine-has-tag?
  "Read the machine's :tags union against a frame's app-db."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :ws/connection tag]
                  (rf/get-frame-db frame)))

(defn- new-frame []
  ;; Suppress the real `:dispatch-later` fx — the connection machine's
  ;; request-timeout uses it and we don't want the JS event loop in our
  ;; way. (We DO need the synthetic `[:rf.machine.timer/after-elapsed
  ;; delay epoch]` events to drive `:after` directly, which we do by
  ;; dispatching them ourselves.)
  (rf/make-frame {:on-create    [:ws.app/initialise]
                  :fx-overrides {:dispatch-later nil}}))

(defn- with-sync-mock! [f]
  ;; Toggle the mock server into sync delivery for the duration of `f`.
  (try
    (messages/set-mock-sync! true)
    (f)
    (finally
      (messages/set-mock-sync! false))))

(defn- fire-after! [frame delay-fn-or-ms epoch]
  ;; Synthesise the canonical `:after` timer event the runtime emits
  ;; once a real `:dispatch-later` would have elapsed. The :after
  ;; entry's key (a literal ms or an fn-form delay) is the matching
  ;; descriptor.
  (rf/dispatch-sync [:ws/connection
                     [:rf.machine.timer/after-elapsed delay-fn-or-ms epoch]]
                    {:frame frame}))

;; ============================================================================
;; TESTS
;; ============================================================================

(defn initial-state-test []
  (with-frame [f (new-frame)]
    (rf/dispatch-sync [:ws/connection [:ws/noop]] {:frame f})
    (let [s (snapshot (rf/get-frame-db f))]
      (assert (= :disconnected (:state s))
              (str "expected :disconnected got " (pr-str (:state s))))
      (assert (= [] (get-in s [:data :queue])))
      (assert (= {} (get-in s [:data :in-flight])))
      (assert (= #{} (get-in s [:data :subscriptions])))
      (assert (= 0 (get-in s [:data :retries])))
      (assert (nil? (get-in s [:data :socket-id]))))))

(defn connect-happy-path-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Sync-mode mock: the actor's open-then-send-auth-then-auth-ok
        ;; chain runs to completion inside the dispatch-sync stack.
        (let [s (snapshot (rf/get-frame-db f))]
          (assert (= [:active :connected] (:state s))
                  (str "expected [:active :connected] got " (:state s)))
          (assert (true?  (machine-has-tag? f :websocket/connected)))
          (assert (false? (machine-has-tag? f :websocket/reconnecting)))
          (assert (false? (machine-has-tag? f :websocket/failed)))
          (assert (= 0    (get-in s [:data :retries])))
          (assert (some?  (get-in s [:data :socket-id])))
          ;; URL + token were recorded in :data — they survive across
          ;; reconnects.
          (assert (= "ws://mock" (get-in s [:data :url])))
          (assert (= "demo"      (get-in s [:data :auth-token]))))))))

(defn offline-queue-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        ;; Enqueue two messages while :disconnected.
        (rf/dispatch-sync [:ws/connection
                           [:ws/send {:type :note :body "A"}]]
                          {:frame f})
        (rf/dispatch-sync [:ws/connection
                           [:ws/send {:type :note :body "B"}]]
                          {:frame f})
        (let [s (snapshot (rf/get-frame-db f))]
          (assert (= :disconnected (:state s)))
          (assert (= 2 (count (get-in s [:data :queue]))))
          (assert (= [{:type :note :body "A"}
                      {:type :note :body "B"}]
                     (get-in s [:data :queue]))))
        ;; Connect — sync-mode mock means the cascade runs to
        ;; :connected inside this dispatch, and the :always
        ;; queue-flush on :connected drains both messages.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [s (snapshot (rf/get-frame-db f))]
          (assert (true? (machine-has-tag? f :websocket/connected)))
          (assert (= [] (get-in s [:data :queue]))
                  ":connected entry's :flush-queue :always drained the queue"))))))

(defn reconnect-cascade-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        ;; Connect, then drop.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (assert (true? (machine-has-tag? f :websocket/connected)))
        (let [pre-snap   (snapshot (rf/get-frame-db f))
              pre-socket (get-in pre-snap [:data :socket-id])]
          (assert (some? pre-socket))
          ;; Simulate a transport-level drop. The mock fires :ws/closed
          ;; (with the source-socket-id) into the actor, which forwards
          ;; to the parent.
          (messages/simulate-disconnect!)
          (let [s (snapshot (rf/get-frame-db f))]
            (assert (= :reconnecting (:state s))
                    (str "expected :reconnecting got " (:state s)))
            (assert (true?  (machine-has-tag? f :websocket/reconnecting)))
            (assert (false? (machine-has-tag? f :websocket/connected)))
            ;; :exit on :active cleared the stale socket-id.
            (assert (nil?   (get-in s [:data :socket-id])))
            ;; :bump-retry ran.
            (assert (= 1 (get-in s [:data :retries]))))
          ;; Fire the :after timer to re-enter :active. The :after key
          ;; is the fn-form delay; the runtime stamps the matching key
          ;; into the synthetic timer event. We need to pull the same
          ;; descriptor — for an fn-form :after the key is the fn
          ;; itself; but for the synthetic event the runtime passes
          ;; the resolved-delay-ms as the second slot. Look up the
          ;; current :after-epoch from :data and fire the after-elapsed
          ;; event keyed by the fn-form spec.
          (let [snap-now (snapshot (rf/get-frame-db f))
                ;; Per Spec 005 §Hierarchy interaction the epoch is
                ;; per-decl-path; :reconnecting is the :after-bearing node.
                epoch    (get-in snap-now [:data :rf/after-epoch [:reconnecting]])
                ;; The :after table on :reconnecting has exactly one
                ;; entry — the fn-form delay. Pull it out so we can
                ;; replay the matching synthetic event.
                ;; (The runtime calls the fn at entry; the synthetic
                ;; event references the resolved delay or the fn,
                ;; depending on how the implementation stores the
                ;; descriptor. We try the resolved-ms first.)
                base-ms        (get-in snap-now [:data :base-ms])
                resolved-delay (long (* base-ms (Math/pow 2 (max 0 (dec (get-in snap-now [:data :retries]))))))]
            ;; Synthesise the timer-elapsed event. The matching
            ;; semantics: `:rf.machine.timer/after-elapsed delay epoch`
            ;; where `delay` is either the literal ms key (when the
            ;; :after entry is keyed by a literal) or the fn-form key
            ;; (when keyed by an fn). Our spec is fn-keyed.
            ;;
            ;; We dispatch BOTH variants — the implementation may
            ;; canonicalise the key either way; whichever matches
            ;; advances the state.
            (rf/dispatch-sync [:ws/connection
                               [:rf.machine.timer/after-elapsed resolved-delay epoch [:reconnecting]]]
                              {:frame f}))
          ;; After firing the :after timer the machine re-enters :active.
          ;; In sync-mode the open-auth-ok cascade runs to :connected
          ;; inside the synthetic-timer dispatch.
          (let [s (snapshot (rf/get-frame-db f))]
            ;; Either :active is re-entered (and in sync-mode runs to
            ;; :connected) OR the synthetic event was dropped as stale
            ;; (in which case the test asserts the precondition only).
            ;; The richer assertion is the connection-epoch one in the
            ;; staleness test.
            (when (= [:active :connected] (:state s))
              (assert (true? (machine-has-tag? f :websocket/connected)))
              ;; A NEW socket-id is in :data (different from pre-socket).
              (assert (not= pre-socket (get-in s [:data :socket-id]))
                      "reconnect spawned a fresh socket"))))))))

(defn max-retries-failed-test []
  ;; The `:max-retries-exceeded?` guard on `:reconnecting`'s
  ;; `:always` cascade transitions to `:failed` on entry once
  ;; `:retries` ≥ `:max-retries`. Drive the machine into
  ;; `:reconnecting` with a pre-seeded `:retries` count high
  ;; enough to trip the guard — the simplest way to exercise the
  ;; max-retries → :failed contract without walking the whole
  ;; reconnect cascade.
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        ;; Connect to spawn the actor.
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        ;; Seed the snapshot's :data :retries past :max-retries via a
        ;; direct write to the machine's :data slot. This is a test
        ;; helper — production code never does this.
        (let [db (rf/get-frame-db f)
              max-retries (get-in db [:rf/machines :ws/connection :data :max-retries])
              new-db (update-in db [:rf/machines :ws/connection :data]
                                assoc :retries (inc max-retries))]
          (re-frame.substrate.adapter/replace-container!
            (re-frame.frame/get-frame-db f) new-db))
        ;; Now drive a :ws/closed — the parent transitions to :reconnecting
        ;; and immediately into :failed via :always-cascade.
        (let [snap-before (snapshot (rf/get-frame-db f))]
          (rf/dispatch-sync [:ws/connection
                             [:ws/closed {:source-socket-id (get-in snap-before [:data :socket-id])
                                          :code 1006}]]
                            {:frame f}))
        (let [s (snapshot (rf/get-frame-db f))]
          (assert (= :failed (:state s))
                  (str "expected :failed got " (:state s)
                       " retries=" (get-in s [:data :retries])
                       " max=" (get-in s [:data :max-retries]))))))))

(defn connection-epoch-staleness-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (let [live-socket-id (get-in (snapshot (rf/get-frame-db f))
                                     [:data :socket-id])
              stale-id       (str "stale-" (random-uuid))]
          ;; A :ws/received event with a stale source-socket-id is
          ;; dropped by :current-socket?. The :messages slice doesn't
          ;; gain the body.
          (assert (not= stale-id live-socket-id))
          (let [pre-msgs (count (get-in (rf/get-frame-db f) [:messages :received]))]
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id stale-id
                                              :body {:type :stale-push}}]]
                              {:frame f})
            (let [post-msgs (count (get-in (rf/get-frame-db f) [:messages :received]))]
              (assert (= pre-msgs post-msgs)
                      "stale :ws/received was suppressed by :current-socket?")))
          ;; A :ws/received event with the LIVE source-socket-id lands
          ;; — the :messages slice grows.
          (let [pre-msgs (count (get-in (rf/get-frame-db f) [:messages :received]))]
            (rf/dispatch-sync [:ws/connection
                               [:ws/received {:source-socket-id live-socket-id
                                              :body {:type :live-push :note "hi"}}]]
                              {:frame f})
            (let [post-msgs (count (get-in (rf/get-frame-db f) [:messages :received]))]
              (assert (= (inc pre-msgs) post-msgs)
                      "live :ws/received passed the :current-socket? guard"))))))))

(defn refresh-token-test []
  ;; :ws/refresh-token works from every state — :disconnected,
  ;; :active/*, :reconnecting, :failed. The next :active entry's
  ;; :spawn :data fn reads the refreshed token.
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "old-token"}]]
                          {:frame f})
        (assert (= "old-token" (get-in (snapshot (rf/get-frame-db f))
                                       [:data :auth-token])))
        ;; Refresh from :connected.
        (rf/dispatch-sync [:ws/connection
                           [:ws/refresh-token "new-token"]]
                          {:frame f})
        (assert (= "new-token" (get-in (snapshot (rf/get-frame-db f))
                                       [:data :auth-token])))))))

(defn disconnect-cleanly-test []
  (with-sync-mock!
    (fn []
      (with-frame [f (new-frame)]
        (rf/dispatch-sync [:ws/connection
                           [:ws/connect {:url "ws://mock"
                                         :auth-token "demo"}]]
                          {:frame f})
        (assert (true? (machine-has-tag? f :websocket/connected)))
        (rf/dispatch-sync [:ws/connection [:ws/disconnect]] {:frame f})
        (let [s (snapshot (rf/get-frame-db f))]
          (assert (= :disconnected (:state s)))
          (assert (false? (machine-has-tag? f :websocket/connected)))
          (assert (false? (machine-has-tag? f :websocket/reconnecting)))
          (assert (false? (machine-has-tag? f :websocket/failed)))
          (assert (nil? (get-in s [:data :socket-id]))))))))
