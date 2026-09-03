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

   4. **`:spawn-all` join-no-hang + ATOMIC reject (the correctness
      call-out).** An unregistered child TYPE in a `:spawn-all` set REJECTS
      the WHOLE invoke — it does not DEADLOCK the `:all` join (a never-running
      spec-less child would never dispatch `:on-child-done`, blocking
      `(= n-done n-total)` forever) AND it does not orphan the registered
      siblings (rf2-qb1j5z). `spawn-all-init-fx` seeds a reject SENTINEL
      (`{:rf/spawn-all-rejected? true}`, no `:children`): the join interceptor
      treats it as no live child-bearing join so a stray sibling completion is the documented
      no-op (no hang), and the registered siblings' per-child spawns detect
      the sentinel and SUPPRESS themselves (no live orphan actor with no
      seeded join to ever tear it down).

   5. **No false reject.** A registered `:machine-id` and an inline
      `:definition` spawn still install cleanly (the gate fires only on the
      unregistered-type case)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading the machines facade registers its late-bind hooks +
            ;; the `:rf.machine/spawn` / `:rf.machine/destroy` reserved fxs
            ;; (so `rf/reg-machine` is available when this ns runs alone).
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry (a `defonce` atom) cleared so a listener from
;; one test cannot leak into the next (mirrors
;; write_after_destroy_always_on_cljs_test).
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-db rf.machines.test-support/runtime-db)

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
        (is (not (some #{:ghost/worker#1} (rf.machines.spawn-order/frame-order :rf/default)))
            "no spawn-order entry recorded for the rejected actor")
        ;; The parent itself transitioned to :working (the parent macrostep
        ;; is independent — only the child spawn is rejected).
        (is (= :working (rf.machines.test-support/machine-state :sup/ghost))
            "the parent still transitions; only the unregistered child is rejected"))
      ;; No fx-substrate spawn trace fired for the rejected actor.
      (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
          "NO :rf.machine.spawn/spawned trace for the rejected spawn")
      (is (empty? (rf.machines.test-support/events-of :rf.machine.lifecycle/spawned))
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
        (let [trace-ev (first (rf.machines.test-support/events-of :rf.error/machine-spawn-unregistered-type))]
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
            whole invoke ATOMICALLY (a reject sentinel is seeded, no
            :children; the registered sibling is suppressed) rather than
            deadlocking the join or orphaning the sibling — the never-running
            spec-less child can never satisfy (= n-done n-total)"
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
        ;; FAIL-CLOSED, and EXACTLY ONCE (rf2-smya7a). `spawn-all-init-fx`'s
        ;; preflight is the SOLE emitter for a rejected invoke: the offending
        ;; child's own per-child spawn fx consults the invoke sentinel BEFORE
        ;; its child-local unregistered gate and suppresses silently, rather
        ;; than fanning a second, duplicate record for the same child.
        (is (= 1 (count records))
            "exactly ONE always-on reject for the one unregistered child")
        (is (every? #(= :gc/missing (:machine-id %)) records)
            "every reject names the unregistered child TYPE :gc/missing")
        ;; A reject SENTINEL was seeded (no :children) — the join cannot hang
        ;; because the interceptor treats a childless slot as no live child-bearing
        ;; join, so a sibling completion drives nothing toward (= n-done n-total).
        (is (= {:rf/spawn-all-rejected? true}
               (get-in (frame-db) [:rf.runtime/machines :spawned :sup/join [:forking]]))
            "the reject sentinel (no :children) is seeded for a :spawn-all with an unregistered child")
        ;; ATOMIC reject: the registered sibling :gc/ok was SUPPRESSED — no
        ;; orphan actor installed (rf2-qb1j5z).
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots :gc/ok#1]))
            "the registered sibling was suppressed — no orphan snapshot")
        (is (not (some #{:gc/ok#1} (rf.machines.spawn-order/frame-order :rf/default)))
            "the registered sibling was suppressed — nothing recorded in spawn-order")
        ;; The parent is still in :forking — it did NOT advance to :ready
        ;; (the join never resolves) but it also did NOT hang the dispatch
        ;; (dispatch-sync returned).
        (is (= :forking (rf.machines.test-support/machine-state :sup/join))
            "parent stays in :forking — no deadlock, just a refused join")))))

;; ---------------------------------------------------------------------------
;; (4b) EXACT reject CARDINALITY — one per offending child, never doubled.
;; ---------------------------------------------------------------------------

(defn- reject-machine-ids
  "The `:machine-id` of every unregistered-type reject the dev TRACE axis
  fanned (the always-on axis is captured separately by `with-error-records`)."
  []
  (mapv #(get-in % [:tags :machine-id])
        (rf.machines.test-support/events-of :rf.error/machine-spawn-unregistered-type)))

(defn- spawn-all-parent
  "A `:spawn-all :all` parent over `children`, for cardinality fixtures."
  [children]
  {:initial :idle
   :states
   {:idle    {:on {:start :forking}}
    :forking {:spawn-all {:children        children
                          :join            :all
                          :on-child-done   :gc/done
                          :on-child-error  :gc/failed
                          :on-all-complete [:all/done]}
              :on {:all/done :ready}}
    :ready   {}}})

(deftest spawn-all-emits-exactly-one-reject-per-offending-child
  (testing "N unregistered children fan EXACTLY N always-on records and
            EXACTLY N dev traces — one per offending machine-id, never two.
            Duplicate production-surviving records distort off-box failure
            rates and make ONE malformed invoke look like TWO independent
            boundary failures per child (rf2-smya7a)"
    (let [ok-child {:initial :running :data {} :states {:running {}}}]
      (rf/reg-machine :card/ok ok-child)
      ;; :card/missing-a and :card/missing-b are NEVER reg-machine'd.
      (rf/reg-machine :sup/card
                      (spawn-all-parent [{:id :a  :machine-id :card/missing-a}
                                         {:id :ok :machine-id :card/ok}
                                         {:id :b  :machine-id :card/missing-b}]))
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/card [:start]]))]
        ;; TWO offending children ⇒ TWO records. The old gate order (child-local
        ;; unregistered check ahead of the invoke sentinel) produced FOUR.
        (is (= 2 (count records))
            "exactly TWO always-on records — one per offending child, not four")
        (is (= [:card/missing-a :card/missing-b]
               (sort (mapv :machine-id records)))
            "one record per offending machine-id — no duplicates, no sibling")
        ;; The dev-trace axis carries the same cardinality.
        (is (= [:card/missing-a :card/missing-b]
               (sort (reject-machine-ids)))
            "exactly TWO dev traces — one per offending machine-id")
        ;; The registered sibling was suppressed, not merely un-rejected.
        (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots :card/ok#1]))
            "the registered sibling installs no snapshot under the rejected invoke")
        (is (not (some #{:card/ok#1} (rf.machines.spawn-order/frame-order :rf/default)))
            "the registered sibling records no spawn-order entry")
        (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
            "no :rf.machine.spawn/spawned trace for ANY child of a rejected invoke")))))

(deftest spawn-all-reject-cardinality-is-order-invariant
  (testing "the per-child reject count is invariant under child ORDER and under
            the number / placement of registered siblings — the offending
            children are preflighted as a SET at the invoke boundary, so a
            leading, trailing, or sandwiched sibling cannot change it"
    (let [ok-child {:initial :running :data {} :states {:running {}}}]
      (rf/reg-machine :card/ok2 ok-child)
      ;; The mirror image of the fixture above: offenders LAST, two registered
      ;; siblings leading, and the offending ids swapped in declaration order.
      (rf/reg-machine :sup/card2
                      (spawn-all-parent [{:id :ok1 :machine-id :card/ok2}
                                         {:id :ok2 :machine-id :card/ok2}
                                         {:id :b   :machine-id :card/missing-b2}
                                         {:id :a   :machine-id :card/missing-a2}]))
      (let [records (with-error-records
                      #(rf/dispatch-sync [:sup/card2 [:start]]))]
        (is (= 2 (count records))
            "still exactly TWO records — order and sibling count are irrelevant")
        (is (= [:card/missing-a2 :card/missing-b2]
               (sort (mapv :machine-id records)))
            "one record per offending machine-id regardless of declaration order")
        (is (= {:rf/spawn-all-rejected? true}
               (get-in (frame-db) [:rf.runtime/machines :spawned :sup/card2 [:forking]]))
            "one childless reject sentinel — no live join")))))

(deftest spawn-all-parent-exit-clears-the-reject-sentinel
  (testing "parent exit clears the reject sentinel and leaves no valid sibling
            live or orphaned — the rejected invoke owns no teardown debt"
    (let [ok-child {:initial :running :data {} :states {:running {}}}
          parent   (assoc-in (spawn-all-parent [{:id :ok :machine-id :card/ok3}
                                                {:id :x  :machine-id :card/missing-c}])
                             [:states :forking :on :back] :idle)]
      (rf/reg-machine :card/ok3 ok-child)
      (rf/reg-machine :sup/card3 parent)
      (rf/dispatch-sync [:sup/card3 [:start]])
      (is (= {:rf/spawn-all-rejected? true}
             (get-in (frame-db) [:rf.runtime/machines :spawned :sup/card3 [:forking]]))
          "(precondition) the childless reject sentinel is seeded")
      ;; Leave :forking — `destroy-spawn-all-children!` finds nothing to tear
      ;; down and clears the slot.
      (rf/dispatch-sync [:sup/card3 [:back]])
      (is (= :idle (rf.machines.test-support/machine-state :sup/card3))
          "(precondition) the parent left the :spawn-all state")
      (is (nil? (get-in (frame-db) [:rf.runtime/machines :spawned :sup/card3 [:forking]]))
          "parent exit CLEARS the reject sentinel")
      (is (nil? (get-in (frame-db) [:rf.runtime/machines :snapshots :card/ok3#1]))
          "no valid sibling was left live or orphaned by the rejected invoke"))))

(deftest spawn-all-registered-sibling-completion-is-noop-not-hang
  (testing "after the join is rejected, a hand-driven :on-child-done finds the
            childless reject sentinel and falls through to the documented
            no-op — proving the join cannot be driven into a hang post-reject"
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
      ;; A childless reject sentinel — the precondition for "cannot hang".
      (is (= {:rf/spawn-all-rejected? true}
             (get-in (frame-db) [:rf.runtime/machines :spawned :sup/join2 [:forking]]))
          "(precondition) the childless reject sentinel is seeded")
      ;; Hand-drive a child-done event into the parent. The registered sibling
      ;; :gc/ok2 was suppressed, so this stands in for any stray completion:
      ;; the interceptor sees the childless sentinel, treats it as no live
      ;; child-bearing join, and it is a documented no-op — it does NOT throw and does NOT hang.
      (rf/dispatch-sync [:sup/join2 [:gc/done :ok]])
      (is (= :forking (rf.machines.test-support/machine-state :sup/join2))
          "a child-done against a childless reject sentinel (no live join) is a no-op — never resolves, never hangs"))))

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
    (is (some? (rf.late-bind/get-fn :error-emit/dispatch-error-record))
        "the non-event union-record hook is registered at error-emit ns-load")))
