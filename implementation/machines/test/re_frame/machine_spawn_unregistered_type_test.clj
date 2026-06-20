(ns re-frame.machine-spawn-unregistered-type-test
  "Fail-closed spawn of an UNREGISTERED machine TYPE.

  A `:rf.machine/spawn` (or a `:spawn-all` per-child) whose `:machine-id`
  names no registered machine TYPE — and which carries no inline
  `:definition` — is REJECTED fail-closed and emits the always-on
  `:rf.error/machine-spawn-unregistered-type`. There is no implicit
  spec-less spawn lifecycle: a rejected spawn installs NO snapshot, NO slot,
  NO system-id, allocates NO spawned-id, records NO spawn-order entry, fires
  NO `:rf.machine.spawn/spawned` trace, and dispatches NO `:start`.

  Pinned here (the bead's required contract):

   1. **Single `:spawn` reject contract.** A declarative `:spawn` of an
      unregistered TYPE installs nothing (snapshot / slot / system-id /
      spawn-order / `:start`) — the strongest atomicity.

   2. **Always-on conformance leg.** The reject fans ONE
      `:rf.error/machine-spawn-unregistered-type` record out through the
      corpus-wide `register-error-listener!` substrate (the EP-0008
      production-survivable axis, NOT gated by `interop/debug-enabled?`).

   3. **Privacy — structural tags only.** The always-on record AND the dev
      trace carry STRUCTURAL context only (`:machine-id` / `:frame` /
      `:reason` / `:recovery`); they NEVER carry the spawn `args` (`:start`
      payloads / `:data` may hold application secrets, and the always-on
      record is production-surviving + not privacy-gated).

   4. **`:spawn-all` join-no-hang (the correctness call-out).** An
      unregistered child TYPE in a `:spawn-all` set REJECTS — it does not
      DEADLOCK the `:all` join (a never-running spec-less child would never
      dispatch `:on-child-done`, blocking `(= n-done n-total)` forever).
      No join-state is seeded, so a registered sibling's later
      `:on-child-done` falls through to the documented no-op and the join
      cannot hang.

   5. **No false reject.** A registered `:machine-id` and an inline
      `:definition` spawn still install cleanly (the gate fires only on the
      unregistered-type case)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            ;; Loading the machines facade registers its late-bind hooks +
            ;; the `:rf.machine/spawn` / `:rf.machine/destroy` reserved fxs
            ;; (so `rf/reg-machine` is available when this ns runs alone).
            [re-frame.machines]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; Fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry (a `defonce` atom) cleared so a listener from
;; one test cannot leak into the next (mirrors
;; write_after_destroy_always_on_cljs_test).
(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (error-emit/clear-error-listeners!))})
  mtest/trace-capture-fixture)

(def ^:private frame-db mtest/runtime-db)

(defn- with-error-records
  "Run `thunk` while a corpus-wide always-on error listener records every
  fanned-out record. Returns the vector of records (oldest first), filtered
  to the unregistered-type category. Always unregisters in a `finally`."
  [thunk]
  (let [seen (atom [])]
    (rf/register-listener! :errors ::recorder (fn [r] (swap! seen conj r)))
    (try
      (thunk)
      (filterv #(= :rf.error/machine-spawn-unregistered-type (:error %)) @seen)
      (finally (rf/unregister-listener! :errors ::recorder)))))

;; ===========================================================================
;; (1) Single :spawn reject contract — installs NOTHING.
;; ===========================================================================

(deftest single-spawn-of-unregistered-type-installs-nothing
  (testing "a declarative :spawn naming an UNREGISTERED :machine-id rejects:
            no snapshot, no slot, no system-id, no spawned-id, no
            spawn-order, no :start dispatch"
    ;; NOTE: :ghost/worker is NEVER reg-machine'd — the unregistered TYPE.
    (let [parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :ghost/worker
                                     :system-id  :ghost-actor
                                     :start      [:go {:secret "tok"}]}
                             :on    {:done :idle}}}}]
      (rf/reg-machine :sup/ghost parent)
      (rf/dispatch-sync [:sup/ghost [:start]])
      (let [db (frame-db)]
        ;; No spawned-id allocated under the parent's invoke-id slot.
        (is (nil? (get-in db [:rf.runtime/machines :spawned :sup/ghost [:working]]))
            "no [:rf.runtime/machines :spawned …] slot for the rejected spawn")
        ;; No snapshot for the deterministic id the allocator WOULD have used.
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :ghost/worker#1]))
            "no snapshot installed for the rejected actor")
        ;; The spawn-counter was never bumped (no allocation happened).
        (is (nil? (get-in db [:rf.runtime/machines :spawn-counter :ghost/worker]))
            "no spawned-id was allocated (counter untouched)")
        ;; No :system-id reverse-index binding.
        (is (nil? (get-in db [:rf.runtime/machines :system-ids :ghost-actor]))
            "no :system-id binding for the rejected spawn")
        ;; No spawn-order entry for the rejected actor.
        (is (not (some #{:ghost/worker#1} (spawn-order/frame-order :rf/default)))
            "no spawn-order entry recorded for the rejected actor")
        ;; The parent itself transitioned to :working (the parent macrostep
        ;; is independent — only the child spawn is rejected).
        (is (= :working (mtest/machine-state :sup/ghost))
            "the parent still transitions; only the unregistered child is rejected"))
      ;; No fx-substrate spawn trace fired for the rejected actor.
      (is (empty? (mtest/events-of :rf.machine.spawn/spawned))
          "NO :rf.machine.spawn/spawned trace for the rejected spawn")
      (is (empty? (mtest/events-of :rf.machine.lifecycle/spawned))
          "NO :rf.machine.lifecycle/spawned (registrar-substrate) trace either"))))

;; ===========================================================================
;; (2) Always-on conformance leg — fans out through register-error-listener!.
;; ===========================================================================

(deftest reject-fans-out-on-always-on-axis
  (testing "the reject fans ONE :rf.error/machine-spawn-unregistered-type
            record out through the corpus-wide always-on error-listener"
    (let [parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :ghost/worker}}}}]
      (rf/reg-machine :sup/ghost2 parent)
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/ghost2 [:start]]))]
        (is (= 1 (count records))
            "exactly ONE always-on record for the rejected spawn")
        (let [r (first records)]
          (is (= :rf.error/machine-spawn-unregistered-type (:error r))
              "the new always-on category")
          (is (= :ghost/worker (:machine-id r))
              "structural :machine-id names the unregistered TYPE")
          (is (= :rf/default (:frame r))
              ":frame names the spawning frame")
          (is (string? (:reason r))
              ":reason is a structured human sentence")
          (is (number? (:time r))
              ":time is a wall-clock millis number"))))))

;; ===========================================================================
;; (3) Privacy — structural tags only; the spawn args NEVER ride.
;; ===========================================================================

(deftest reject-record-carries-no-spawn-args
  (testing "the always-on record + dev trace carry STRUCTURAL context only
            (:machine-id / :frame / :reason / :recovery) — NEVER the spawn
            args (a :start payload may hold application secrets)"
    (let [secret  "super-secret-token"
          parent  {:initial :idle
                   :states
                   {:idle    {:on {:start :working}}
                    :working {:spawn {:machine-id :ghost/worker
                                      :data       {:auth secret}
                                      :start      [:go {:token secret}]}}}}]
      (rf/reg-machine :sup/ghost3 parent)
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/ghost3 [:start]]))
            r       (first records)]
        (is (some? r) "(precondition) the reject fired")
        ;; The record's keyset is exactly the structural one.
        (is (= #{:error :machine-id :frame :reason :recovery :time}
               (set (keys r)))
            "the always-on record carries ONLY structural keys — no :args/:start/:data")
        ;; No value anywhere in the record echoes the secret.
        (is (not (some #(and (string? %) (.contains ^String % secret))
                       (vals r)))
            "no record value contains the secret :start / :data payload")
        ;; The dev trace likewise carries no spawn args / secret.
        (let [trace-ev (first (mtest/events-of :rf.error/machine-spawn-unregistered-type))]
          (is (some? trace-ev) "(precondition) the dev trace fired")
          (is (nil? (get-in trace-ev [:tags :args]))
              "dev trace carries no :args slot")
          (is (nil? (get-in trace-ev [:tags :start]))
              "dev trace carries no :start slot")
          (is (nil? (get-in trace-ev [:tags :data]))
              "dev trace carries no :data slot")
          (is (= :ghost/worker (get-in trace-ev [:tags :machine-id]))
              "dev trace carries the structural :machine-id")
          (is (not (some #(and (string? %) (.contains ^String % secret))
                         (vals (:tags trace-ev))))
              "no dev-trace tag value contains the secret"))))))

;; ===========================================================================
;; (4) :spawn-all join-no-hang — the correctness call-out.
;; ===========================================================================

(deftest spawn-all-with-unregistered-child-rejects-not-hangs
  (testing "an UNREGISTERED child TYPE in a :spawn-all :all set REJECTS the
            whole join (no join-state seeded) rather than deadlocking it
            forever — the never-running spec-less child can never satisfy
            (= n-done n-total)"
    (let [ok-child {:initial :running :data {} :states {:running {}}}
          ;; :gc/missing is NEVER reg-machine'd — the unregistered child.
          parent   {:initial :idle
                    :states
                    {:idle    {:on {:start :forking}}
                     :forking {:spawn-all
                               {:children        [{:id :ok      :machine-id :gc/ok}
                                                  {:id :missing :machine-id :gc/missing}]
                                :join            :all
                                :on-child-done   :gc/done
                                :on-child-error  :gc/failed
                                :on-all-complete [:all/done]}
                               :on {:all/done :ready :back :idle}}
                     :ready   {}}}]
      (rf/reg-machine :gc/ok ok-child)
      (rf/reg-machine :sup/join parent)
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/join [:start]]))]
        ;; FAIL-CLOSED: the unregistered child fans the reject (one from the
        ;; spawn-all-init pre-check; the per-child spawn-fx also rejects).
        (is (<= 1 (count records))
            "the unregistered child fans at least one always-on reject")
        (is (every? #(= :gc/missing (:machine-id %)) records)
            "every reject names the unregistered child TYPE :gc/missing")
        ;; NO join-state was seeded — the join cannot hang because there is
        ;; no slot for a sibling completion to drive toward (= n-done n-total).
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/join [:forking]]))
            "no join-state seeded for a :spawn-all containing an unregistered child")
        ;; The parent is still in :forking — it did NOT advance to :ready
        ;; (the join never resolves) but it also did NOT hang the dispatch
        ;; (dispatch-sync returned).
        (is (= :forking (mtest/machine-state :sup/join))
            "parent stays in :forking — no deadlock, just a refused join")))))

(deftest spawn-all-registered-sibling-completion-is-noop-not-hang
  (testing "after the join is rejected, the registered sibling's eventual
            :on-child-done finds NO seeded join-state and falls through to
            the documented no-op — proving the join cannot be driven into a
            hang post-reject"
    (let [ok-child {:initial :running
                    :data    {}
                    :states  {:running {:on {:finish {:target :done}}}
                              :done    {:final? true}}}
          parent   {:initial :idle
                    :states
                    {:idle    {:on {:start :forking}}
                     :forking {:spawn-all
                               {:children        [{:id :ok      :machine-id :gc/ok2}
                                                  {:id :missing :machine-id :gc/missing2}]
                                :join            :all
                                :on-child-done   :gc/done
                                :on-child-error  :gc/failed
                                :on-all-complete [:all/done]}
                               :on {:all/done :ready}}
                     :ready   {}}}]
      (rf/reg-machine :gc/ok2 ok-child)
      (rf/reg-machine :sup/join2 parent)
      (rf/dispatch-sync [:sup/join2 [:start]])
      ;; No join-state — the precondition for "cannot hang".
      (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/join2 [:forking]]))
          "(precondition) no join-state seeded")
      ;; Hand-drive a child-done event into the parent — the registered
      ;; sibling would normally drive the join. With no join-state it is a
      ;; documented no-op; crucially it does NOT throw and does NOT hang.
      (rf/dispatch-sync [:sup/join2 [:gc/done :ok]])
      (is (= :forking (mtest/machine-state :sup/join2))
          "a child-done with no seeded join-state is a no-op — never resolves, never hangs"))))

;; ===========================================================================
;; (5) No false reject — registered TYPE + inline :definition still spawn.
;; ===========================================================================

(deftest registered-type-spawn-not-rejected
  (testing "a :spawn naming a REGISTERED :machine-id installs normally — the
            gate fires ONLY on the unregistered-type case"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states {:idle    {:on {:start :working}}
                           :working {:spawn {:machine-id :real/worker}}}}]
      (rf/reg-machine :real/worker child)
      (rf/reg-machine :sup/real parent)
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/real [:start]]))]
        (is (empty? records)
            "no reject for a registered machine TYPE")
        (is (= :real/worker#1
               (get-in (frame-db) [:rf.runtime/machines :spawned :sup/real [:working]]))
            "the registered-type spawn installs its slot")
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots :real/worker#1]))
            "the registered-type spawn installs its snapshot")))))

(deftest inline-definition-spawn-not-rejected
  (testing "a :spawn carrying an inline :definition (no :machine-id) is NOT
            rejected — the gate keys on an unregistered :machine-id, and a
            :definition spawn IS its own spec (so it always resolves)"
    (let [parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   ;; A `:fixed-actor-id` gives the inline-definition spawn a
                   ;; deterministic address (an inline-`:definition`
                   ;; declarative `:spawn` has no `:machine-id` to drive the
                   ;; gensym allocator — orthogonal to this bead).
                   :working {:spawn {:definition    {:initial :running
                                                     :data    {}
                                                     :states  {:running {}}}
                                     :fixed-actor-id :inline/worker}}}}]
      (rf/reg-machine :sup/inline parent)
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/inline [:start]]))]
        (is (empty? records)
            "no reject for an inline :definition spawn")
        (is (some? (get-in (frame-db) [:rf.runtime/machines :snapshots :inline/worker]))
            "the inline-definition spawn installs its snapshot (gate did not fire)")))))

;; ===========================================================================
;; (6) The always-on hook the machines layer reaches error-emit through.
;; ===========================================================================

(deftest dispatch-error-record-hook-is-published
  (testing "machines ships above core's require graph, so the always-on
            reject reaches the listener via the
            :error-emit/dispatch-error-record late-bind hook — published at
            error-emit ns-load, so the lookup never misses in production"
    (is (some? (late-bind/get-fn :error-emit/dispatch-error-record))
        "the non-event union-record hook is registered at error-emit ns-load")))
