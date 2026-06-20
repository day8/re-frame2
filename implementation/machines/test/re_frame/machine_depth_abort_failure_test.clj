(ns re-frame.machine-depth-abort-failure-test
  "A bounded-depth abort (`:always` / `:raise` depth limit tripped on a
  runaway cycle) surfaces as a FAILED macrostep, NOT a silent benign no-op.
  A runaway `a →:always b →:always a` cycle must be DISTINGUISHABLE from a
  guard-blocked no-op (XState v5 THROWS on such a cycle).

  The depth-abort returns a `result/fail` carrying the `::result/depth-abort?`
  sentinel; it routes through the SAME failure path a thrown action takes
  (the handler short-circuits to `{}` — no snapshot write reaches
  runtime-db, so the atomic rollback Spec 005 §Bounded depth requires is
  preserved). The precise `:rf.error/machine-{always,raise}-depth-exceeded`
  category is emitted once at the abort site (the single trace for the
  trip); the engine does NOT additionally emit the benign
  `:rf.machine.event/unhandled-no-op` — so the runaway is DISTINGUISHABLE
  from a guard-blocked no-op.

  JVM-only — the dynamic-var listener path is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` installs the late-bind hooks +
            ;; reserved fxs `reg-machine` relies on.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- record-traces! [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

;; ---------------------------------------------------------------------------
;; 1. `:always` cycle — a →:always b →:always a with always-true guards trips
;;    the depth limit. It surfaces as the depth-exceeded ERROR trace, NOT the
;;    benign unhandled-no-op; the snapshot rolls back atomically.
;; ---------------------------------------------------------------------------

(defn- reg-always-cycle! []
  (rf/reg-machine :rf2-y3jv8q/always
    {:initial            :start
     :data               {}
     :always-depth-limit 5
     :guards             {:p? (fn [_] true)}
     :states
     {:start {:on {:go {:target :a}}}
      :a     {:always [{:guard :p? :target :b}]}
      :b     {:always [{:guard :p? :target :a}]}}}))

(deftest always-cycle-surfaces-as-failed-macrostep-not-no-op
  (testing "an `:always` runaway cycle trips the depth limit and surfaces as a
   FAILED macrostep — the depth-exceeded error trace fires, NOT a benign
   `:rf.machine.event/unhandled-no-op`, and the snapshot rolls back atomically"
    (reg-always-cycle!)
    (rf/dispatch-sync [:rf2-y3jv8q/always [:rf.machine/start]])
    (let [evs       (record-traces!
                      (fn [] (rf/dispatch-sync [:rf2-y3jv8q/always [:go]])))
          depth-err (ops evs :rf.error/machine-always-depth-exceeded)
          no-ops    (ops evs :rf.machine.event/unhandled-no-op)]
      (is (= 1 (count depth-err))
          "exactly one :rf.error/machine-always-depth-exceeded error trace")
      (let [tr (first depth-err)]
        (is (= :error (:op-type tr))
            "the depth-exceeded trace is error-grade (a failed macrostep)")
        (is (= :no-recovery (:recovery tr))
            "the trip is :no-recovery"))
      ;; The CORE contract: a runaway cycle must NOT masquerade as the
      ;; benign no-op a guard-blocked / unhandled event emits — the abort
      ;; surfaces as a failed macrostep, distinguishable from a
      ;; guard-blocked no-op.
      (is (= 0 (count no-ops))
          "a runaway cycle is NOT a benign no-op — no unhandled-no-op fires")
      ;; Atomic rollback (Spec 005 §Bounded depth): the macrostep failed, so
      ;; no post-event snapshot reaches runtime-db — the pre-event :start
      ;; snapshot stays committed.
      (is (= :start (mtest/machine-state :rf2-y3jv8q/always))
          "macrostep is atomic; the cycle aborts; snapshot rolls back to :start"))))

;; ---------------------------------------------------------------------------
;; 2. `:raise` cycle — two states ping-pong by raising each other's resolving
;;    event. The raise-drain trips the depth limit; same failed-macrostep
;;    contract as the `:always` cycle.
;; ---------------------------------------------------------------------------

(defn- reg-raise-cycle! []
  (rf/reg-machine :rf2-y3jv8q/raise
    {:initial           :start
     :data              {}
     :raise-depth-limit 5
     ;; Each action re-raises an event the SAME hub state handles, so the
     ;; internal-event queue never drains to quiescence — a never-converging
     ;; raise cycle that trips :raise-depth-limit. Raises are emitted from
     ;; actions (the proven seed for the internal queue) rather than a
     ;; transition-level :fx key.
     :actions
     {:start (fn [{:keys [data]}] {:data data :fx [[:raise [:tick]]]})
      :tick  (fn [{:keys [data]}] {:data data :fx [[:raise [:tick]]]})}
     :states
     {:start {:on {:go   {:action :start}
                   :tick {:action :tick}}}}}))


(deftest raise-cycle-surfaces-as-failed-macrostep-not-no-op
  (testing "a `:raise` runaway cycle trips the raise-depth limit and surfaces
   as a FAILED macrostep — the raise-depth-exceeded error trace fires, NOT a
   benign no-op, and the snapshot rolls back atomically"
    (reg-raise-cycle!)
    (rf/dispatch-sync [:rf2-y3jv8q/raise [:rf.machine/start]])
    (let [evs       (record-traces!
                      (fn [] (rf/dispatch-sync [:rf2-y3jv8q/raise [:go]])))
          depth-err (ops evs :rf.error/machine-raise-depth-exceeded)
          no-ops    (ops evs :rf.machine.event/unhandled-no-op)]
      (is (= 1 (count depth-err))
          "exactly one :rf.error/machine-raise-depth-exceeded error trace")
      (is (= :error (:op-type (first depth-err)))
          "the depth-exceeded trace is error-grade (a failed macrostep)")
      (is (= 0 (count no-ops))
          "a runaway raise cycle is NOT a benign no-op — no unhandled-no-op")
      (is (= :start (mtest/machine-state :rf2-y3jv8q/raise))
          "macrostep is atomic; the cycle aborts; snapshot rolls back to :start"))))

;; ---------------------------------------------------------------------------
;; 3. Negative control — a genuine guard-blocked no-op still emits the BENIGN
;;    `unhandled-no-op` and NO depth-exceeded error. The depth-abort failure
;;    path must not bleed into the legitimate no-op classification.
;; ---------------------------------------------------------------------------

(defn- reg-blocked! []
  (rf/reg-machine :rf2-y3jv8q/blocked
    {:initial :red
     :data    {:locked? true}
     :guards  {:unlocked? (fn [{:keys [data]}] (not (:locked? data)))}
     :states  {:red {:on {:force {:target :green :guard :unlocked?}}}
               :green {}}}))

(deftest guard-blocked-no-op-is-not-a-depth-abort
  (testing "a guard-blocked no-op still emits the benign unhandled-no-op and
   NO depth-exceeded error — the legitimate no-op classification is intact"
    (reg-blocked!)
    (rf/dispatch-sync [:rf2-y3jv8q/blocked [:rf.machine/start]])
    (let [evs        (record-traces!
                       (fn [] (rf/dispatch-sync [:rf2-y3jv8q/blocked [:force]])))
          no-ops     (ops evs :rf.machine.event/unhandled-no-op)
          always-err (ops evs :rf.error/machine-always-depth-exceeded)
          raise-err  (ops evs :rf.error/machine-raise-depth-exceeded)]
      (is (= 1 (count no-ops))
          "the guard-blocked no-op still emits exactly one unhandled-no-op")
      (is (= 0 (+ (count always-err) (count raise-err)))
          "a benign no-op emits NO depth-exceeded error")
      (is (= :red (mtest/machine-state :rf2-y3jv8q/blocked))
          "the guard-blocked event leaves the snapshot at :red"))))
