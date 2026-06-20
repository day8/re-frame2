(ns re-frame.machine-noop-signal-test
  "A genuine machine no-op is signalled ONCE, consistently.

  `commit-or-finalize` suppresses the `:rf.machine/transition` emit when the
  macrostep is a genuine no-op — `:before` == `:after` AND the combined
  (boot + step) `:cascade` is empty AND zero `:microsteps`. So:

    - An unhandled / guard-blocked event emits exactly one benign
      `:rf.machine.event/unhandled-no-op` and NO no-change
      `:rf.machine/transition`.
    - A redundant `[:rf.machine/start]` on an already-booted machine emits
      NOTHING — reserved-`:rf/*` lifecycle is carved out of `unhandled-no-op`,
      and the no-change transition is suppressed.

  The legitimate first bootstrap (`:initial-entry`) carries a non-empty
  initial-descent `:cascade`, so it is NEVER a no-op and its transition
  trace survives (negative guard). A real transition (`:before` !=
  `:after`, or a cascade carrying an `:action` / exit / entry step) is
  likewise untouched.

  JVM-only — the dynamic-var listener path is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` installs the late-bind hooks +
            ;; reserved fxs `reg-machine` relies on (the artefact otherwise
            ;; throws `:rf.error/machines-artefact-missing` when run in
            ;; isolation via `-n`).
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Routed through the shared `mtest/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces! [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

;; ---------------------------------------------------------------------------
;; Fixture — a flat machine with a guarded clause so we can exercise both an
;; unhandled event (no `:on` entry at all) and a blocked-guard no-op (an
;; `:on` entry whose guard fails). A real transition (`:tick`) is the
;; positive control.
;; ---------------------------------------------------------------------------

(defn- reg-tl! []
  (rf/reg-machine :rf2-coozg/tl
    {:initial :red
     :data    {:locked? true}
     :guards  {:unlocked? (fn [{:keys [data]}] (not (:locked? data)))}
     :states  {:red    {:on {:tick {:target :green}
                             ;; `:force` only fires when unlocked — with
                             ;; `:locked? true` the guard blocks it, so the
                             ;; clause MATCHES the event-id but resolves to no
                             ;; transition: a guard-blocked no-op.
                             :force {:target :green :guard :unlocked?}}}
               :green  {:on {:tick {:target :red}}}}}))

(defn- boot! []
  (rf/dispatch-sync [:rf2-coozg/tl [:rf.machine/start]]))

;; ---------------------------------------------------------------------------
;; 1. Unhandled user event — exactly ONE no-op signal (`unhandled-no-op`),
;;    NOT also a no-change `:rf.machine/transition`.
;; ---------------------------------------------------------------------------

(deftest unhandled-event-emits-only-the-no-op-signal
  (testing "an event with no matching `:on` clause emits exactly one
   `:rf.machine.event/unhandled-no-op` and NO no-change `:rf.machine/transition`"
    (reg-tl!)
    (boot!)
    (let [evs       (record-traces!
                      (fn [] (rf/dispatch-sync [:rf2-coozg/tl [:no-such-event]])))
          no-ops    (ops evs :rf.machine.event/unhandled-no-op)
          trans     (ops evs :rf.machine/transition)]
      (is (= 1 (count no-ops))
          "exactly one unhandled-no-op signals the benign no-op")
      (is (= 0 (count trans))
          "NO redundant no-change :rf.machine/transition (RED before coozg: was 1)"))))

;; ---------------------------------------------------------------------------
;; 2. Guard-blocked event — same single-signal contract.
;; ---------------------------------------------------------------------------

(deftest guard-blocked-event-emits-only-the-no-op-signal
  (testing "an event whose only matching clause has a failing guard resolves
   to no transition — exactly one `unhandled-no-op`, NO no-change transition"
    (reg-tl!)
    (boot!)
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:rf2-coozg/tl [:force]])))
          no-ops (ops evs :rf.machine.event/unhandled-no-op)
          trans  (ops evs :rf.machine/transition)]
      (is (= 1 (count no-ops))
          "exactly one unhandled-no-op for the guard-blocked no-op")
      (is (= 0 (count trans))
          "NO redundant no-change :rf.machine/transition (RED before coozg: was 1)"))))

;; ---------------------------------------------------------------------------
;; 3. Redundant bootstrap on an already-booted machine — emits NEITHER
;;    signal (reserved-`:rf/*` lifecycle is exempt from unhandled-no-op,
;;    and the no-change transition is suppressed too). The no-op is
;;    consistent: nothing fires for genuine non-events.
;; ---------------------------------------------------------------------------

(deftest redundant-start-emits-no-no-op-signal
  (testing "a redundant `[:rf.machine/start]` on an already-booted machine is
   a no-op that emits neither `unhandled-no-op` (reserved-`:rf/*` carve-out)
   nor a `:rf.machine/transition` — and, per F‴ (rf2-gl588), no second
   `:rf.machine/started` either (the snapshot is present + not pending, so
   `maybe-boot` does not re-fire and the start marker short-circuits)."
    (reg-tl!)
    (boot!) ;; first start — the legitimate one (asserted in test 4)
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:rf2-coozg/tl [:rf.machine/start]])))
          no-ops  (ops evs :rf.machine.event/unhandled-no-op)
          trans   (ops evs :rf.machine/transition)
          started (ops evs :rf.machine/started)]
      (is (= 0 (count no-ops))
          "reserved-:rf/* lifecycle never emits unhandled-no-op (rf2-t4582)")
      (is (= 0 (count trans))
          "no `:rf.machine/transition` — a pure start never runs the step")
      (is (= 0 (count started))
          "no second `:rf.machine/started` — the machine is already alive"))))

;; ---------------------------------------------------------------------------
;; 4. The LEGITIMATE first start runs its `:initial-entry` cascade and
;;    signals the BIRTH with a `:rf.machine/started` trace — NOT a
;;    `:rf.machine/transition`. The start marker is a PURE init-kick:
;;    it STOPS after initial-entry, so no transition row is emitted (no
;;    `before == after` self-transition). It is not an unhandled-no-op.
;; ---------------------------------------------------------------------------

(deftest first-start-signals-birth-via-started-trace
  (testing "the machine's BIRTH (first `[:rf.machine/start]`) emits a
   `:rf.machine/started` trace carrying the installed initial state — and,
   per F‴, NO `:rf.machine/transition` (pure init-kick: init then stop) and
   NO `unhandled-no-op`"
    (reg-tl!)
    (let [evs     (record-traces! boot!)
          no-ops  (ops evs :rf.machine.event/unhandled-no-op)
          trans   (ops evs :rf.machine/transition)
          started (ops evs :rf.machine/started)]
      (is (= 0 (count no-ops))
          "start is framework init, never an unhandled-no-op (rf2-t4582)")
      (is (= 0 (count trans))
          "no `:rf.machine/transition` — F‴ start is a pure init-kick (stops)")
      (is (= 1 (count started))
          "the birth is signalled by exactly one `:rf.machine/started` trace")
      (let [tr (first started)]
        (is (= [:rf2-coozg/tl :red :explicit]
               [(:machine-id (:tags tr))
                (:state (:tags tr))
                (:cause (:tags tr))])
            "the started trace names the machine, its :initial state :red, and :explicit cause")))))

;; ---------------------------------------------------------------------------
;; 5. Real transition — exactly ONE `:rf.machine/transition`, NO
;;    `unhandled-no-op`. The headline path is unchanged.
;; ---------------------------------------------------------------------------

(deftest real-transition-emits-one-transition-no-no-op
  (testing "a state-changing event emits exactly one `:rf.machine/transition`
   and NO `unhandled-no-op` — the real-transition path is untouched"
    (reg-tl!)
    (boot!)
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:rf2-coozg/tl [:tick]])))
          no-ops (ops evs :rf.machine.event/unhandled-no-op)
          trans  (ops evs :rf.machine/transition)]
      (is (= 0 (count no-ops))
          "a handled event is never an unhandled-no-op")
      (is (= 1 (count trans))
          "exactly one transition trace for the real :red -> :green move")
      (let [tr (first trans)]
        (is (= [:red :green]
               [(:state (:before (:tags tr))) (:state (:after (:tags tr)))])
            "before/after span the real transition")))))

;; ---------------------------------------------------------------------------
;; 6. Internal self-transition (action runs, no state change) is NOT a
;;    no-op — its cascade carries an `:action` step, so the transition
;;    trace survives even though `:before` == `:after`.
;; ---------------------------------------------------------------------------

(defn- reg-internal! []
  (rf/reg-machine :rf2-coozg/internal
    {:initial :on
     :data    {:n 0}
     :actions {:bump (fn [{:keys [data]}] {:data (update data :n inc)})}
     ;; Internal transition: omit `:target` — the action runs, no exit/entry,
     ;; no state change. NOT a no-op: an action ran.
     :states  {:on {:on {:nudge {:action :bump}}}}}))

(deftest internal-self-transition-is-not-a-no-op
  (testing "an internal transition (action runs, no `:target`, `:before` ==
   `:after`) is NOT a no-op — its cascade carries an `:action` step, so the
   `:rf.machine/transition` trace survives and NO unhandled-no-op fires"
    (reg-internal!)
    (rf/dispatch-sync [:rf2-coozg/internal [:rf.machine/start]])
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:rf2-coozg/internal [:nudge]])))
          no-ops (ops evs :rf.machine.event/unhandled-no-op)
          trans  (ops evs :rf.machine/transition)]
      (is (= 0 (count no-ops))
          "the event WAS handled (the action ran) — never a no-op")
      (is (= 1 (count trans))
          "the internal-transition trace survives (an action ran)")
      (let [tr (first trans)]
        (is (seq (:cascade (:tags tr)))
            ":cascade carries the :action step (so it's not classified no-op)")
        (is (= 1 (get-in (:after (:tags tr)) [:data :n]))
            "the action bumped :data :n")))))

;; ---------------------------------------------------------------------------
;; 7. Parallel-region machine: when EVERY region declines, the aggregate
;;    `unhandled-no-op` (emitted once by `parallel-machine-transition`) is
;;    the sole signal — the no-change `:rf.machine/transition` is suppressed
;;    through the SAME `commit-or-finalize` seam.
;; ---------------------------------------------------------------------------

(defn- reg-parallel! []
  (rf/reg-machine :rf2-coozg/par
    {:type :parallel
     :regions
     {:a {:initial :a0 :states {:a0 {:on {:go-a {:target :a1}}}
                                :a1 {}}}
      :b {:initial :b0 :states {:b0 {:on {:go-b {:target :b1}}}
                                :b1 {}}}}}))

(deftest parallel-all-regions-decline-emits-only-the-no-op-signal
  (testing "a parallel machine where every region declines emits exactly one
   aggregate `unhandled-no-op` and NO no-change `:rf.machine/transition`"
    (reg-parallel!)
    (rf/dispatch-sync [:rf2-coozg/par [:rf.machine/start]])
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:rf2-coozg/par [:no-region-handles-this]])))
          no-ops (ops evs :rf.machine.event/unhandled-no-op)
          trans  (ops evs :rf.machine/transition)]
      (is (= 1 (count no-ops))
          "exactly one aggregate unhandled-no-op (all regions declined)")
      (is (= 0 (count trans))
          "NO redundant no-change :rf.machine/transition (RED before coozg: was 1)"))))
