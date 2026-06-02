(ns re-frame.machine-pure-error-contract-test
  "Coverage & rigour pass (rf2-ynjts.10). Direct pure-engine (JVM)
  coverage for the runtime error / resolution contracts that the
  REGISTRATION-TIME validator (`validate-machine!`) does NOT cover —
  because the pure-call surface (`re-frame.machines/machine-transition`,
  the conformance corpus, JVM fixtures) reaches the engine WITHOUT
  running `validate-machine!`. A grep of the machines test tree before
  this file returned ZERO direct matches for these contracts:

    - the BENIGN unhandled-event no-op (rf2-ugdas — xstate-v5 parity). An
      unhandled event is no longer an error: it emits the benign
      `:rf.machine.event/unhandled-no-op` trace (op-type `:rf.machine`,
      NOT `:error` / `:warning`) and leaves the snapshot unchanged. This
      holds uniformly for domain AND reserved-`:rf/*` events alike — the
      former reserved-namespace `unhandled-event-warnable?` carve-out is
      retired with the error advisory.

    - `:rf.error/machine-bad-state-form` — `state-path` throws on a
      `:state` that is neither keyword nor vector (transition.cljc:232).
      A pure-engine guard with no registration backstop.

    - `:rf.error/machine-bad-guard-form` / `:rf.error/machine-bad-action-
      form` — `resolve-guard` / `resolve-action` throw at TRANSITION TIME
      when a `:guard` / `:action` ref is neither fn, keyword, nor nil
      (transition.cljc:54 / :65). Both resolvers are called OUTSIDE the
      `evaluate-guard` / `run-action` try-blocks, so a bad FORM (vs a
      throwing body) propagates straight out of `machine-transition`.
      The registration validator only checks keyword refs RESOLVE — it
      never sees a non-keyword/non-fn `:guard` value, and the pure-call
      surface skips registration entirely.

    - `:rf.error/machine-unresolved-guard` at TRANSITION TIME — a dangling
      keyword ref reaching the engine on the pure-call surface (no
      registration validation). Distinct from the registration-time
      throw the `nested-validation` suite covers.

    - `chase-ref` one-level indirection — a `{:short-name :registered-id}`
      binding map resolves the short-name to the registered fn through
      one hop (transition.cljc:30-42). The indirection + dangling-tail
      behaviour was exercised only transitively.

  All assertions are pure functions of their arguments — no frame, no
  dispatch loop, no app-db, no wall-clock — so they are deterministic by
  construction (the determinism canon)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.machines.transition :as transition]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; rf2-ugdas — the BENIGN unhandled-event no-op (xstate-v5 parity). Through
;; the pure macrostep, an unhandled event — domain OR reserved-:rf/* — emits
;; the benign `:rf.machine.event/unhandled-no-op` trace (op-type :rf.machine,
;; NOT :error / :warning) and leaves the snapshot unchanged. NO
;; :rf.error/machine-unhandled-event is ever emitted (the advisory is
;; retired). Pins the engine's actual emission decision (transition.cljc).
;; ---------------------------------------------------------------------------

(defn- capture-events!
  "Drive a pure `machine-transition` while a tooling listener records every
  emitted trace event (full envelope). Returns the vector of events
  (deterministic — no wall-clock / random)."
  [definition snapshot event]
  (let [seen (atom [])]
    (trace/register-listener! ::ops (fn [ev] (swap! seen conj ev)))
    (try
      (machines/machine-transition definition snapshot event)
      (finally (trace/unregister-listener! ::ops)))
    @seen))

(defn- capture-ops!
  "As `capture-events!` but projects each event to its `:operation`."
  [definition snapshot event]
  (mapv :operation (capture-events! definition snapshot event)))

(def ^:private no-handler-spec
  "A machine that handles only `:known`; everything else is unhandled."
  {:id     :probe/unhandled
   :initial :a
   :data    {}
   :states  {:a {:on {:known {:target :a}}}}})

(deftest domain-unhandled-event-emits-the-benign-no-op
  (testing "an unhandled DOMAIN event emits exactly one benign
   :rf.machine.event/unhandled-no-op and NO error advisory"
    (let [evs       (capture-events! no-handler-spec {:state :a :data {}} [:nope])
          ops       (mapv :operation evs)
          no-op-evs (filter #(= :rf.machine.event/unhandled-no-op (:operation %)) evs)]
      (is (= 1 (count no-op-evs))
          "exactly one benign no-op trace for a domain event")
      (is (zero? (count (filter #{:rf.error/machine-unhandled-event} ops)))
          "the retired error advisory is NEVER emitted")
      (testing "the no-op is op-type :rf.machine (NOT :error / :warning) so it
       is benign / not an issue"
        (is (= :rf.machine (:op-type (first no-op-evs)))
            "op-type is the machine-activity family, not a severity"))
      (testing "the no-op carries {:machine-id :event :state} per Spec 009"
        (let [{:keys [tags]} (first no-op-evs)]
          (is (= :probe/unhandled (:machine-id tags)))
          (is (= [:nope] (:event tags)))
          (is (= :a (:state tags)))))))

  (testing "the snapshot is unchanged on an unhandled event (no state churn)"
    (let [{s ::result/snap} (machines/machine-transition
                              no-handler-spec {:state :a :data {}} [:nope])]
      (is (= :a (:state s)) "state unchanged"))))

(deftest reserved-rf-unhandled-event-also-emits-the-benign-no-op
  (testing "the spawn case stops being special — a reserved-:rf/* unhandled
   event emits the SAME benign no-op as a domain event (the old reserved-
   namespace carve-out is retired with the error advisory)"
    (doseq [ev [[:rf.machine.spawn/spawned]
                [:rf.story.lifecycle/events-complete]
                [:rf/anything]]]
      (let [evs       (capture-events! no-handler-spec {:state :a :data {}} ev)
            ops       (mapv :operation evs)
            no-op-evs (filter #(= :rf.machine.event/unhandled-no-op (:operation %)) evs)]
        (is (= 1 (count no-op-evs))
            (str "exactly one benign no-op for reserved-namespace event " ev))
        (is (= :rf.machine (:op-type (first no-op-evs)))
            (str "op-type :rf.machine for " ev))
        (is (zero? (count (filter #{:rf.error/machine-unhandled-event} ops)))
            (str "no retired error advisory for " ev)))))

  (testing "the reserved-namespace no-op still returns an unchanged snapshot"
    (let [{s ::result/snap} (machines/machine-transition
                             no-handler-spec {:state :a :data {}}
                             [:rf.story.lifecycle/events-complete])]
      (is (= :a (:state s)) "state unchanged for the benign reserved ping"))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-bad-state-form — state-path throws on a malformed
;; :state (transition.cljc:232). No registration backstop; pure-engine guard.
;; ---------------------------------------------------------------------------

(deftest state-path-rejects-malformed-state
  (testing "state-path coerces the two legal forms"
    (is (= [:a] (transition/state-path :a))
        "a keyword normalises to a 1-element path")
    (is (= [:a :b] (transition/state-path [:a :b]))
        "a vector path passes through"))

  (testing "a :state that is neither keyword nor vector throws
   :rf.error/machine-bad-state-form"
    (let [e (try (transition/state-path "not-a-state") nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a string :state throws")
      (is (= "not-a-state" (:state (ex-data e)))
          "ex-data carries the offending :state value")
      (is (= ":rf.error/machine-bad-state-form" (ex-message e))
          "message names the bad-state-form contract"))))

(deftest bad-state-form-propagates-through-machine-transition
  (testing "a malformed snapshot :state surfaces the bad-state-form error
   out of the pure macrostep (the engine does not swallow it)"
    (let [e (try (machines/machine-transition no-handler-spec
                                              {:state 42 :data {}} [:known])
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "an integer :state throws out of machine-transition")
      (is (= ":rf.error/machine-bad-state-form" (ex-message e))))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-bad-guard-form / :rf.error/machine-bad-action-form —
;; resolve-guard / resolve-action throw at TRANSITION TIME on a ref that is
;; neither fn, keyword, nor nil (transition.cljc:54 / :65). Both resolvers
;; run OUTSIDE the evaluate-guard / run-action try, so a bad FORM (as
;; opposed to a throwing body) propagates out of machine-transition.
;; ---------------------------------------------------------------------------

(deftest bad-guard-form-propagates-through-machine-transition
  (testing "a :guard whose value is neither fn / keyword / nil throws
   :rf.error/machine-bad-guard-form out of the macrostep — NOT swallowed by
   evaluate-guard's try (resolve-guard runs before the try)"
    (let [spec {:id     :probe/bad-guard
                :initial :a
                :data    {}
                :states  {:a {:on {:go {:target :b :guard "not-a-guard"}}}
                          :b {}}}
          e    (try (machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a string :guard form throws")
      (is (= ":rf.error/machine-bad-guard-form" (ex-message e)))
      (is (= "not-a-guard" (:guard (ex-data e)))
          "ex-data carries the offending guard form"))))

(deftest bad-action-form-propagates-through-machine-transition
  (testing "an :action whose value is neither fn / keyword / nil throws
   :rf.error/machine-bad-action-form out of the macrostep — NOT swallowed by
   run-action's try (resolve-action runs before the try)"
    (let [spec {:id     :probe/bad-action
                :initial :a
                :data    {}
                :states  {:a {:on {:go {:target :b :action 99}}}
                          :b {}}}
          e    (try (machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a numeric :action form throws")
      (is (= ":rf.error/machine-bad-action-form" (ex-message e)))
      (is (= 99 (:action (ex-data e)))
          "ex-data carries the offending action form"))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-unresolved-guard at TRANSITION TIME — a dangling keyword
;; ref reaching the engine on the pure-call surface (which skips
;; validate-machine!). Distinct from the registration-time throw the
;; nested-validation suite covers.
;; ---------------------------------------------------------------------------

(deftest unresolved-guard-keyword-throws-at-transition-time
  (testing "a dangling :guard KEYWORD ref (no entry in :guards) throws
   :rf.error/machine-unresolved-guard when the engine resolves it on the
   pure-call surface — registration validation never ran"
    (let [spec {:id     :probe/dangling-guard
                :initial :a
                :data    {}
                :guards  {}                         ;; :nope is not registered
                :states  {:a {:on {:go {:target :b :guard :nope}}}
                          :b {}}}
          e    (try (machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a dangling guard keyword throws at transition time")
      (is (= ":rf.error/machine-unresolved-guard" (ex-message e)))
      (is (= :nope (:guard (ex-data e)))
          "ex-data carries the unresolved guard keyword")
      (is (= :probe/dangling-guard (:machine-id (ex-data e)))
          "ex-data carries the machine-id"))))

;; ---------------------------------------------------------------------------
;; chase-ref one-level indirection — a {:short-name :registered-id} binding
;; resolves the short-name to the registered fn through ONE hop
;; (transition.cljc:30-42). Exercised here through the public guard surface.
;; ---------------------------------------------------------------------------

(deftest chase-ref-resolves-one-level-of-indirection
  (testing "a :guard short-name that points at ANOTHER key in :guards (which
   holds the fn) resolves through the one-level chase and gates the transition"
    (let [spec {:id     :probe/indirect-guard
                :initial :a
                :data    {:open? true}
                :guards  {:gate   :is-open?                       ;; short-name → registered id
                          :is-open? (fn [{d :data}] (:open? d))}  ;; registered id → fn
                :states  {:a {:on {:go {:target :b :guard :gate}}}
                          :b {}}}
          {s-pass ::result/snap} (machines/machine-transition
                                  spec {:state :a :data {:open? true}} [:go])
          {s-fail ::result/snap} (machines/machine-transition
                                  spec {:state :a :data {:open? false}} [:go])]
      (is (= :b (:state s-pass))
          "indirected guard resolved + passed ⇒ transition fires")
      (is (= :a (:state s-fail))
          "indirected guard resolved + failed ⇒ no transition (guard gated it)"))))
