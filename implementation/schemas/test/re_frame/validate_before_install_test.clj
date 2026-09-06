(ns re-frame.validate-before-install-test
  "Adversarial contract pins for rf2-uhk9ko (Mike-ruled Option B,
  2026-07-11): the router validates the COMPLETE candidate frame
  transition BEFORE installing it. A schema rejection means the
  candidate was NEVER installed — not installed-then-rolled-back.

  The observable contract these tests hold the line on:

    1. A synchronous trace listener that reads the frame's app-db
       DURING the `:rf.error/schema-validation-failure` emit sees the
       OLD (pre-handler) value. Under the retired commit-then-rollback
       pair the container held the INVALID candidate at that moment.
    2. ZERO container watch callbacks fire for a rejected dispatch —
       the one physical frame-state container is never written.
    3. A rejection emits NO `:rf.event/db-changed`, NO
       `:rf.event/db-noop`, NO `:rf.event/frame-state-changed`, and no
       trace anywhere carries `:rf.trace/phase :rollback`. The always-on
       event-emit outcome is `:rolled-back` (public vocabulary for
       `transaction REJECTED` — not a physical write-pair).
    4. Privacy: the rejected candidate's data is unobservable — the
       container never holds it, and the failure trace's value-bearing
       slots redact per the schema's `:sensitive?` marks as usual.
    5. A wholesale validator-machinery throw REJECTS the candidate
       (trace `:rf.error/malformed-schema` with `:rollback? true`, then
       fail CLOSED) — the retired treat-as-pass fail-OPEN arm is gone.
    6. A successful commit validates the candidate EXACTLY ONCE (no
       post-commit second pass) and emits exactly one forward
       `:rf.event/db-changed`.
    7. JVM registry-generation coherence: one schema-registry snapshot
       per candidate — concurrent re-registration can never produce a
       mixed-generation validation (observable as a HALF-failed
       two-schema generation).

  Spec anchors: 010 §Per-step recovery row 4 (the `:db` effect is NOT
  installed; no db-changed fires), 009 §Canonical per-event trace
  sequence, 002/006 single-commit contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- seed-int-schema!
  "Register the canonical [:n]→[:int] app-schema + seed/ok/break events
  and drive the frame to the {:n 0} baseline."
  []
  (rf/reg-app-schema [:n] [:int])
  (rf/reg-event :n/init  (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :n/ok    (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
  (rf/reg-event :n/break (fn [{:keys [db]} _] {:db (assoc db :n "boom")}))
  (rf/dispatch-sync [:n/init])
  (is (= {:n 0} (rf/app-db-value :rf/default)) "baseline post-init state"))

;; ---------------------------------------------------------------------------
;; 1. Synchronous trace listener reads the OLD value during a rejection.
;; ---------------------------------------------------------------------------

(deftest sync-trace-listener-reads-old-value-during-rejection
  (testing "a sync :trace listener reading app-db at the
            schema-validation-failure emit observes the PRE-HANDLER value —
            the invalid candidate is never installed, so no listener can
            observe it through the container (rf2-uhk9ko Option B)"
    (seed-int-schema!)
    (let [seen (atom ::never-fired)]
      (rf/register-listener! :trace ::old-value-probe
        (fn [ev]
          (when (= :rf.error/schema-validation-failure (:operation ev))
            (reset! seen (rf/app-db-value :rf/default)))))
      (rf/dispatch-sync [:n/break])
      (rf/unregister-listener! :trace ::old-value-probe)
      (is (= {:n 0} @seen)
          "the listener read the OLD app-db during the rejection —
           NOT the invalid candidate (commit-then-rollback would have
           exposed {:n \"boom\"} here)")
      (is (= {:n 0} (rf/app-db-value :rf/default))
          "post-dispatch the container still holds the pre-handler value"))))

;; ---------------------------------------------------------------------------
;; 2. Zero container writes for a rejected dispatch.
;; ---------------------------------------------------------------------------

(deftest rejected-candidate-never-writes-the-container
  (testing "a rejected dispatch performs ZERO writes on the one physical
            frame-state container — no forward write, no restore write
            (the retired pair performed two)"
    (seed-int-schema!)
    (let [container (rf.frame/frame-state-container :rf/default)
          writes    (atom [])]
      (is (some? container) "precondition: the frame-state container resolves")
      (add-watch container ::write-probe
                 (fn [_ _ old new] (swap! writes conj [old new])))
      (rf/dispatch-sync [:n/break])
      (is (= [] @writes)
          "ZERO container watch callbacks — the rejected candidate was
           never installed and nothing was restored")
      ;; Sanity: the watch is live — a VALID commit fires it exactly once.
      (rf/dispatch-sync [:n/ok])
      (is (= 1 (count @writes))
          "a valid commit is exactly ONE container write (single-commit
           contract)")
      (remove-watch container ::write-probe))))

;; ---------------------------------------------------------------------------
;; 3. Rejection trace signature + always-on outcome.
;; ---------------------------------------------------------------------------

(deftest rejection-emits-no-change-traces-and-outcome-rolled-back
  (testing "a rejected dispatch emits NO db-changed / db-noop /
            frame-state-changed, no :rf.trace/phase :rollback anywhere,
            exactly one schema-validation-failure with :rollback? true,
            and the always-on event-emit outcome is :rolled-back"
    (seed-int-schema!)
    (let [records (atom [])]
      (rf/register-listener! :events ::outcome-probe
        (fn [record] (swap! records conj record)))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:n/break])
        (let [ops (mapv :operation @traces)]
          (is (not-any? #{:rf.event/db-changed
                          :rf.event/db-noop
                          :rf.event/frame-state-changed} ops)
              "no change trace of any kind fires for a rejected candidate")
          (is (not-any? #(= :rollback (get-in % [:tags :rf.trace/phase]))
                        @traces)
              "no trace carries :rf.trace/phase :rollback — the phase has
               no producer under validate-before-install")
          (let [violations (filterv #(= :rf.error/schema-validation-failure
                                        (:operation %))
                                    @traces)]
            (is (= 1 (count violations))
                "exactly one schema-validation-failure trace")
            (is (true? (get-in (first violations) [:tags :rollback?]))
                ":rollback? true stays the public transaction-REJECTED
                 vocabulary"))))
      (rf/unregister-listener! :events ::outcome-probe)
      (let [break-rec (first (filter #(= :n/break (:event-id %)) @records))]
        (is (= :rolled-back (:outcome break-rec))
            "the always-on event-emit outcome is :rolled-back")))))

;; ---------------------------------------------------------------------------
;; 4. Rejected-candidate privacy.
;; ---------------------------------------------------------------------------

(deftest rejected-candidate-privacy
  (testing "a rejected candidate carrying a sensitive slot is doubly
            unobservable: the container never holds it, and the failure
            trace redacts the value-bearing slots per :sensitive?"
    (rf/reg-app-schema [:auth] [:map [:token {:sensitive? true} :string]])
    (rf/reg-event :auth/init (fn [_ _] {:db {:auth {:token "ok"}}}))
    ;; 20260712 is the would-be-leaked candidate secret (violates :string).
    (rf/reg-event :auth/break (fn [{:keys [db]} _]
                                {:db (assoc-in db [:auth :token] 20260712)}))
    (rf/dispatch-sync [:auth/init])
    (let [container-at-failure (atom ::never-fired)]
      (rf/register-listener! :trace ::privacy-probe
        (fn [ev]
          (when (= :rf.error/schema-validation-failure (:operation ev))
            (reset! container-at-failure (rf/app-db-value :rf/default)))))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:auth/break])
        (rf/unregister-listener! :trace ::privacy-probe)
        (is (= {:auth {:token "ok"}} @container-at-failure)
            "the container never held the rejected candidate")
        (let [v (first (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces))]
          (is (some? v) "the failure trace fired")
          (is (= :rf/redacted (get-in v [:tags :value]))
              "the failing leaf value is redacted (sensitive slot)")
          ;; Per Spec 009 the schemas emit-site stamps `:tags :sensitive?`
          ;; and `emit-error!` HOISTS it to the top-level trace slot.
          (is (true? (:sensitive? v))
              ":sensitive? true is stamped (top-level) for consumer routing")
          (let [leaked? (atom false)]
            (walk/postwalk (fn [x] (when (= 20260712 x)
                                     (reset! leaked? true))
                             x)
                           (:tags v))
            (is (false? @leaked?)
                "the candidate secret appears NOWHERE in the trace tags")))))))

;; ---------------------------------------------------------------------------
;; 5. Validator-machinery throw fails CLOSED (trace-then-REJECT).
;; ---------------------------------------------------------------------------

(deftest validator-machinery-throw-rejects-fail-closed
  (testing "a wholesale validator-machinery throw REJECTS the candidate:
            :rf.error/malformed-schema with :rollback? true, container
            unchanged, no change traces, outcome :rolled-back — the
            retired treat-as-pass fail-OPEN arm is gone"
    (seed-int-schema!)
    (let [original (rf.late-bind/get-fn :schemas/validate-app-schema!)
          records  (atom [])]
      (try
        (rf.late-bind/set-fn! :schemas/validate-app-schema!
                           (fn [_db _event-id _frame _continue?]
                             (throw (ex-info "validator machinery exploded" {}))))
        (rf/register-listener! :events ::throw-probe
          (fn [record] (swap! records conj record)))
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:n/ok])
          (is (= {:n 0} (rf/app-db-value :rf/default))
              "the (well-typed!) candidate did NOT install — a throwing
               validator cannot prove conformance, so the transition is
               rejected fail-closed")
          (let [malformed (filterv #(= :rf.error/malformed-schema
                                       (:operation %))
                                   @traces)]
            (is (= 1 (count malformed))
                "the throw surfaced as one :rf.error/malformed-schema trace")
            (is (true? (get-in (first malformed) [:tags :rollback?]))
                "the trace carries :rollback? true — transaction rejected"))
          (is (not-any? #(#{:rf.event/db-changed
                            :rf.event/db-noop
                            :rf.event/frame-state-changed} (:operation %))
                        @traces)
              "no change traces on the fail-closed reject"))
        (rf/unregister-listener! :events ::throw-probe)
        (let [rec (first (filter #(= :n/ok (:event-id %)) @records))]
          (is (= :rolled-back (:outcome rec))
              "outcome :rolled-back — the reject reaches off-box shippers"))
        (finally
          (rf.late-bind/set-fn! :schemas/validate-app-schema! original))))))

;; ---------------------------------------------------------------------------
;; 6. Success path: exactly one validation pass, one commit, one forward emit.
;; ---------------------------------------------------------------------------

(deftest successful-commit-validates-exactly-once
  (testing "a successful commit runs candidate validation EXACTLY ONCE
            (pre-install; there is no post-commit second pass) and emits
            exactly one forward db-changed + one frame-state-changed"
    (seed-int-schema!)
    (let [original (rf.late-bind/get-fn :schemas/validate-app-schema!)
          calls    (atom 0)]
      (try
        (rf.late-bind/set-fn! :schemas/validate-app-schema!
                           (fn [db event-id frame-id continue?]
                             (swap! calls inc)
                             (original db event-id frame-id continue?)))
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:n/ok])
          (is (= 1 @calls)
              "validate-app-schema! ran exactly once for the candidate")
          (is (= {:n 42} (rf/app-db-value :rf/default)) "the commit landed")
          (let [ops (mapv :operation @traces)]
            (is (= 1 (count (filter #{:rf.event/db-changed} ops)))
                "exactly one forward db-changed")
            (is (= 1 (count (filter #{:rf.event/frame-state-changed} ops)))
                "exactly one frame-state-changed")))
        (finally
          (rf.late-bind/set-fn! :schemas/validate-app-schema! original))))))

;; ---------------------------------------------------------------------------
;; 7. JVM registry-generation race — one snapshot per candidate.
;; ---------------------------------------------------------------------------

(deftest registry-snapshot-is-generation-coherent-under-concurrent-flips
  (testing "candidate validation reads ONE schema-registry snapshot: with
            two schemas flipped ATOMICALLY between an all-pass and an
            all-fail generation on another thread, every dispatch fails
            0 or 2 entries — never 1 (a mixed-generation tear)"
    (rf/reg-event :race/seed  (fn [_ _] {:db {:a 0 :b 0}}))
    (rf/reg-event :race/write (fn [_ [_ i]] {:db {:a i :b i}}))
    (rf/dispatch-sync [:race/seed])
    ;; Build the two whole-registry generation values.
    (rf/reg-app-schema [:a] [:int])
    (rf/reg-app-schema [:b] [:int])
    (let [gen-pass (rf.schemas.storage/snapshot-schemas-by-frame)]
      (rf.schemas.storage/clear-schemas-by-frame!)
      (rf/reg-app-schema [:a] [:string])
      (rf/reg-app-schema [:b] [:string])
      (let [gen-fail (rf.schemas.storage/snapshot-schemas-by-frame)
            stop?    (atom false)
            flipper  (future
                       (loop [pass? true]
                         (when-not @stop?
                           (rf.schemas.storage/restore-schemas-by-frame!
                             (if pass? gen-pass gen-fail))
                           (recur (not pass?)))))]
        (try
          (dotimes [i 100]
            (with-trace-recorder! [traces]
              (rf/dispatch-sync [:race/write (inc i)])
              (let [failures (count (filter #(= :rf.error/schema-validation-failure
                                                (:operation %))
                                            @traces))]
                (is (contains? #{0 2} failures)
                    (str "dispatch " i " validated against ONE registry "
                         "generation (0 or 2 failures), got " failures)))))
          (finally
            (reset! stop? true)
            @flipper
            (rf.schemas.storage/restore-schemas-by-frame! gen-pass)))))))
