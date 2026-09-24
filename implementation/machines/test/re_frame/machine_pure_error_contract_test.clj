(ns re-frame.machine-pure-error-contract-test
  "Direct pure-engine (JVM) coverage for the runtime error / resolution
  contracts that the REGISTRATION-TIME validator (`validate-machine!`) does
  NOT cover — because the pure-call surface
  (`re-frame.machines/machine-transition`, the conformance corpus, JVM
  fixtures) reaches the engine WITHOUT running `validate-machine!`:

    - the BENIGN unhandled-event no-op (xstate-v5 parity). An unknown USER
      event is NOT an error: it emits the benign
      `:rf.machine.event/unhandled-no-op` trace (op-type `:rf.machine`,
      NOT `:error` / `:warning`) and leaves the snapshot unchanged; no
      `:rf.error/machine-unhandled-event` advisory is emitted.
      Reserved-`:rf/*` framework lifecycle traffic (the synthetic creation
      marker `[:rf.machine/start]`, the spawn kick-off
      `[:rf.machine.spawn/spawned]`, the stories-runtime lifecycle pings)
      is NOT classified as an unknown-user-event no-op —
      `rf.machines.transition/unhandled-event-no-op?` gates the emit. Severity is
      benign (nothing throws); the SEMANTIC carve-out means the machine's
      BIRTH renders its `:initial-entry` cascade rather than a no-op.
      Domain (non-`:rf/*`) events emit the benign no-op.

    - `:rf.error/machine-bad-state-form` — `state-path` throws on a
      `:state` that is neither keyword nor vector (transition.cljc).
      A pure-engine guard with no registration backstop.

    - `:rf.error/machine-bad-guard-form` / `:rf.error/machine-bad-action-
      form` — `resolve-guard` / `resolve-action` throw at TRANSITION TIME
      when a `:guard` / `:action` ref is neither fn, keyword, nor nil
      (transition.cljc). Both resolvers are called OUTSIDE the
      `evaluate-guard` / `run-action` try-blocks, so a bad FORM (vs a
      throwing body) propagates straight out of `machine-transition`.
      The registration validator only checks keyword refs RESOLVE — it
      never sees a non-keyword/non-fn `:guard` value, and the pure-call
      surface skips registration entirely.

    - `:rf.error/machine-unresolved-guard` at TRANSITION TIME — a dangling
      keyword ref reaching the engine on the pure-call surface (no
      registration validation). Distinct from the registration-time
      throw the `nested-validation` suite covers.

    - `:rf.error/machine-bad-raise` — a `:raise` fx entry an action returns
      carrying anything besides its event vector throws where the flat and
      the parallel drains route fx. It is an action's OUTPUT, so no
      registration check can see it.

    - `chase-ref` one-level indirection — a `{:short-name :registered-id}`
      binding map resolves the short-name to the registered fn through
      one hop (transition.cljc).

  All assertions are pure functions of their arguments — no frame, no
  dispatch loop, no app-db, no wall-clock — so they are deterministic by
  construction (the determinism canon)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as rf.error]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.transition :as rf.machines.transition]))

;; ---------------------------------------------------------------------------
;; The BENIGN unhandled-event no-op (xstate-v5 parity) WITH the
;; reserved-:rf/* lifecycle carve-out. Through the pure macrostep, an
;; unknown USER event emits the benign `:rf.machine.event/unhandled-no-op`
;; trace (op-type :rf.machine, NOT :error / :warning) and leaves the
;; snapshot unchanged; an unknown USER event is NOT an error, so no
;; :rf.error/machine-unhandled-event advisory is emitted. A reserved-:rf/*
;; framework lifecycle event (bootstrap, spawn kick-off, stories pings) does
;; NOT emit the no-op — it is framework init, not an unknown user event.
;; Pins the engine's actual emission decision (transition.cljc
;; `unhandled-event-no-op?` + the emit-site gate).
;; ---------------------------------------------------------------------------

(defn- capture-events!
  "Drive a pure `machine-transition` while a tooling listener records every
  emitted trace event (full envelope). Returns the vector of events
  (deterministic — no wall-clock / random). Routed through the shared
  `rf.machines.test-support/with-trace-capture` — guaranteed unregister in a `finally`."
  [definition snapshot event]
  (rf.machines.test-support/with-trace-capture seen
    (rf.machines/machine-transition definition snapshot event)
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
          "no :rf.error/machine-unhandled-event advisory is emitted")
      (testing "the no-op is op-type :rf.machine (NOT :error / :warning) so it
       is benign / not an issue"
        (is (= :rf.machine (:op-type (first no-op-evs)))
            "op-type is the machine-activity family, not a severity"))
      (testing "the no-op carries {:actor-id :event :state} per Spec 009 (the live actor INSTANCE)"
        (let [{:keys [tags]} (first no-op-evs)]
          (is (= :probe/unhandled (:actor-id tags)))
          (is (not (contains? tags :machine-id))
              ":machine-id (the registered TYPE) is NOT on a live no-op row")
          (is (= [:nope] (:event tags)))
          (is (= :a (:state tags)))))))

  (testing "the snapshot is unchanged on an unhandled event (no state churn)"
    (let [{s :snapshot} (rf.machines/machine-transition
                              no-handler-spec {:state :a :data {}} [:nope])]
      (is (= :a (:state s)) "state unchanged"))))

(deftest reserved-rf-unhandled-event-does-not-emit-the-no-op
  (testing "a reserved-:rf/* lifecycle event that resolves to no
   transition does NOT emit the unhandled-no-op (it is framework init, not an
   unknown USER event). Covers the spawn kick-off, a stories lifecycle ping,
   and the bare reserved root"
    (doseq [ev [[:rf.machine.spawn/spawned]
                [:rf.story.lifecycle/events-complete]
                [:rf/anything]]]
      (let [evs       (capture-events! no-handler-spec {:state :a :data {}} ev)
            ops       (mapv :operation evs)
            no-op-evs (filter #(= :rf.machine.event/unhandled-no-op (:operation %)) evs)]
        (is (zero? (count no-op-evs))
            (str "NO unhandled-no-op for reserved-namespace event " ev))
        (is (zero? (count (filter #{:rf.error/machine-unhandled-event} ops)))
            (str "no error advisory for " ev " (the ping stays benign)")))))

  (testing "the reserved-namespace ping still returns an unchanged snapshot
   (no transition, no churn — benign, just unlabelled)"
    (let [{s :snapshot} (rf.machines/machine-transition
                             no-handler-spec {:state :a :data {}}
                             [:rf.story.lifecycle/events-complete])]
      (is (= :a (:state s)) "state unchanged for the benign reserved ping")))

  (testing "the negative guard — `unhandled-event-no-op?` is TRUE for a
   domain event (still a no-op) and FALSE for any reserved-:rf/* event"
    (is (rf.machines.transition/unhandled-event-no-op? [:nope])
        "a domain event is classified as an unknown-user-event no-op")
    (is (not (rf.machines.transition/unhandled-event-no-op? [:rf.machine/start]))
        "the synthetic creation marker is framework init, not a no-op")
    (is (not (rf.machines.transition/unhandled-event-no-op? [:rf.machine.spawn/spawned]))
        "the spawn kick-off is framework init, not a no-op")
    (is (not (rf.machines.transition/unhandled-event-no-op? [:rf.story.lifecycle/events-complete]))
        "a stories lifecycle ping rides the reserved root")
    (is (not (rf.machines.transition/unhandled-event-no-op? [:rf/anything]))
        "the bare reserved root is exempt")))

;; ---------------------------------------------------------------------------
;; The machine's BIRTH renders as :initial-entry, not a no-op. The start
;; threads the synthetic `[:rf.machine/start]` event into the initial-entry
;; cascade (Spec 005 §Synthetic creation marker). Because `:rf.machine/start`
;; is reserved-:rf/*, the start NEVER trips the unhandled-no-op even when the
;; initial state declares no `:on`; instead it runs the entry cascade and the
;; entry action emits `:rf.machine/action-ran` with `:phase :initial-entry`.
;; ---------------------------------------------------------------------------

(def ^:private boot-entry-spec
  "A flat machine whose initial state `:a` declares an `:entry` action and
  handles only `:known`. At bootstrap it runs the entry cascade; a later
  unknown user event no-ops."
  {:id      :probe/boot
   :initial :a
   :data    {}
   :actions {:on-enter (fn [_] {})}
   :states  {:a {:entry :on-enter
                 :on    {:known {:target :a}}}}})

(deftest bootstrap-renders-initial-entry-not-a-no-op
  (testing "the initial-entry cascade emits the :initial-entry-phase action-ran
   and installs the initial state — NOT an unhandled-no-op for
   [:rf.machine/start]"
    ;; Shared `with-trace-capture` — guaranteed unregister in a `finally`.
    (rf.machines.test-support/with-trace-capture seen
      (let [r    (rf.machines.parallel/apply-initial-entry-cascade
                   boot-entry-spec {:state :a :data {}})
            evs  @seen
            ops  (mapv :operation evs)
            no-op-evs (filter #(= :rf.machine.event/unhandled-no-op %) ops)
            entry-evs (filter #(and (= :rf.machine/action-ran (:operation %))
                                    (= :initial-entry (:phase (:tags %))))
                              evs)]
        (is (= :ok (:status r)) "the bootstrap cascade succeeds")
        (is (= :a (:state (:snapshot r))) "the initial state is installed")
        (is (zero? (count no-op-evs))
            "the bootstrap does NOT emit an unhandled-no-op (it is the machine's
             BIRTH, not an ignored event)")
        (is (pos? (count entry-evs))
            "the entry action ran with :phase :initial-entry — the :initial-entry
             cascade rendered")))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-bad-state-form — state-path throws on a malformed
;; :state (transition.cljc). No registration backstop; pure-engine guard.
;; ---------------------------------------------------------------------------

(deftest state-path-rejects-malformed-state
  (testing "state-path coerces the two legal forms"
    (is (= [:a] (rf.machines.transition/state-path :a))
        "a keyword normalises to a 1-element path")
    (is (= [:a :b] (rf.machines.transition/state-path [:a :b]))
        "a vector path passes through"))

  (testing "a :state that is neither keyword nor vector throws
   :rf.error/machine-bad-state-form"
    (let [e (try (rf.machines.transition/state-path "not-a-state") nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a string :state throws")
      (is (= "not-a-state" (:state (ex-data e)))
          "ex-data carries the offending :state value")
      (is (= :rf.error/machine-bad-state-form (:rf.error/id (ex-data e)))
          "ex-data carries the canonical bad-state-form discriminator"))))

(deftest bad-state-form-propagates-through-machine-transition
  (testing "a malformed snapshot :state surfaces the bad-state-form error
   out of the pure macrostep (the engine does not swallow it)"
    (let [e (try (rf.machines/machine-transition no-handler-spec
                                              {:state 42 :data {}} [:known])
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "an integer :state throws out of machine-transition")
      (is (= :rf.error/machine-bad-state-form (:rf.error/id (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-bad-guard-form / :rf.error/machine-bad-action-form —
;; resolve-guard / resolve-action throw at TRANSITION TIME on a ref that is
;; neither fn, keyword, nor nil (transition.cljc). Both resolvers
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
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a string :guard form throws")
      (is (= :rf.error/machine-bad-guard-form (:rf.error/id (ex-data e))))
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
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a numeric :action form throws")
      (is (= :rf.error/machine-bad-action-form (:rf.error/id (ex-data e))))
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
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a dangling guard keyword throws at transition time")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e))))
      (is (= :nope (:guard (ex-data e)))
          "ex-data carries the unresolved guard keyword")
      (is (= :probe/dangling-guard (:machine-id (ex-data e)))
          "ex-data carries the machine-id"))))

(deftest unresolved-action-keyword-throws-at-transition-time
  (testing "a dangling :action KEYWORD ref (no entry in :actions) throws
   :rf.error/machine-unresolved-action when the engine resolves it on the
   pure-call surface — registration validation never ran"
    (let [spec {:id      :probe/dangling-action
                :initial :a
                :data    {}
                :actions {}                          ;; :nope is not registered
                :states  {:a {:on {:go {:target :b :action :nope}}}
                          :b {}}}
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a dangling action keyword throws at transition time")
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data e))))
      (is (= :nope (:action (ex-data e)))
          "ex-data carries the unresolved action keyword")
      (is (= :probe/dangling-action (:machine-id (ex-data e)))
          "ex-data carries the machine-id"))))

;; ---------------------------------------------------------------------------
;; Runtime transition throws carry the canonical Spec 009 thrown-error
;; shape: a human :reason sentence, a :where, a :recovery, and a message that
;; LEADS with the sentence + TRAILS with the [:rf.error/<id>] token (never a
;; bare keyword). Covers a malformed transition VALUE form (the `:other` arm
;; of normalise-candidates) and the canonical-shape invariants.
;; ---------------------------------------------------------------------------

(deftest malformed-transition-value-throws-bad-on-clause
  (testing "an :on clause whose value is an unrecognised form (a string) throws
   :rf.error/machine-bad-on-clause out of the macrostep"
    (let [spec {:id      :probe/bad-on
                :initial :a
                :data    {}
                :states  {:a {:on {:go "not-a-transition-value"}}
                          :b {}}}
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a malformed :on value throws")
      (is (= :rf.error/machine-bad-on-clause (:rf.error/id (ex-data e))))
      (is (= "not-a-transition-value" (:value (ex-data e)))
          "ex-data carries the offending transition value"))))

(deftest malformed-always-value-throws-bad-always
  (testing "a state's :always value that is an unrecognised form (a number)
   throws the CATEGORISED :rf.error/machine-bad-always out of the macrostep
   that lands on it — NOT a raw uncategorised platform throw (an `assoc` on
   a non-map candidate). `:b`'s malformed :always is evaluated
   as soon as the transition from :a lands there (§Eventless :always fires
   after any transition that lands in this state)"
    (let [spec {:id      :probe/bad-always
                :initial :a
                :data    {}
                :states  {:a {:on {:go {:target :b}}}
                          :b {:always 42}}}
          e    (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "a malformed :always value throws")
      (is (= :rf.error/machine-bad-always (:rf.error/id (ex-data e))))
      (is (= 42 (:value (ex-data e)))
          "ex-data carries the offending transition value"))))

;; ---------------------------------------------------------------------------
;; :rf.error/machine-bad-raise — a `:raise` fx entry is exactly
;; `[:raise <event-vec>]`. An action returning XState's
;; `raise(event, {delay, id})` spelling throws where the drain routes its fx,
;; in the flat drain and in the parallel drain, instead of raising the event
;; at once without its options.
;; ---------------------------------------------------------------------------

(def ^:private raise-with-options [:raise [:search] {:delay 300 :id :deb}])

(defn- raising-machine
  "A machine whose `:go` action returns the one fx `entry`, verbatim, and
  whose `:search` moves to `:b` — flat, or as region `:r1` of a parallel
  machine beside a region `:r2` that moves on `:search` too."
  [entry parallel?]
  (let [actions {:go (fn [_] {:fx [entry]})}
        r1      {:initial :a
                 :states  {:a {:on {:go {:action :go} :search :b}} :b {}}}]
    (if parallel?
      {:id :probe/bad-raise-parallel :type :parallel :data {} :actions actions
       :regions {:r1 r1
                 :r2 {:initial :x :states {:x {:on {:search :y}} :y {}}}}}
      (merge {:id :probe/bad-raise :data {} :actions actions} r1))))

(defn- transition-or-throw
  "The pure transition's result, or the `ex-info` it threw."
  [definition state]
  (try (rf.machines/machine-transition definition {:state state :data {}} [:go])
       (catch clojure.lang.ExceptionInfo ex ex)))

(deftest raise-with-options-throws-bad-raise-in-the-flat-drain
  (testing "a three-element :raise throws :rf.error/machine-bad-raise"
    (let [e (transition-or-throw (raising-machine raise-with-options false) :a)]
      (is (= :rf.error/machine-bad-raise (:rf.error/id (ex-data e))))
      (is (= raise-with-options (:value (ex-data e)))
          "ex-data carries the offending entry")))
  (testing "control: the two-element [:raise <event-vec>] raises"
    (is (= :b (get-in (transition-or-throw (raising-machine [:raise [:search]] false) :a)
                      [:snapshot :state])))))

(deftest raise-with-options-throws-bad-raise-in-the-parallel-drain
  (testing "a region action's three-element :raise throws :rf.error/machine-bad-raise"
    (let [e (transition-or-throw (raising-machine raise-with-options true) {:r1 :a :r2 :x})]
      (is (= :rf.error/machine-bad-raise (:rf.error/id (ex-data e))))
      (is (= raise-with-options (:value (ex-data e)))
          "ex-data carries the offending entry")))
  (testing "control: the two-element [:raise <event-vec>] is broadcast to every region"
    (is (= {:r1 :b :r2 :y}
           (get-in (transition-or-throw (raising-machine [:raise [:search]] true) {:r1 :a :r2 :x})
                   [:snapshot :state])))))

(deftest transition-throws-carry-canonical-spec009-shape
  (testing "every transition runtime throw exposes a human message (not a bare
   keyword) carrying the [:rf.error/<id>] token, plus :reason / :where /
   :recovery in ex-data"
    (doseq [[label spec]
            [["bad guard form"
              {:id :probe/s1 :initial :a :data {}
               :states {:a {:on {:go {:target :b :guard "x"}}} :b {}}}]
             ["unresolved action"
              {:id :probe/s2 :initial :a :data {} :actions {}
               :states {:a {:on {:go {:target :b :action :nope}}} :b {}}}]
             ["malformed :on value"
              {:id :probe/s3 :initial :a :data {}
               :states {:a {:on {:go 99}} :b {}}}]
             ["malformed :always value"
              {:id :probe/s4 :initial :a :data {}
               :states {:a {:on {:go {:target :b}}} :b {:always 99}}}]
             ["malformed :raise entry"
              {:id :probe/s5 :initial :a :data {}
               :actions {:go (fn [_] {:fx [[:raise [:search] {:delay 300}]]})}
               :states {:a {:on {:go {:action :go}}}}}]]]
      (let [e   (try (rf.machines/machine-transition spec {:state :a :data {}} [:go])
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))
            msg (ex-message e)]
        (is (some? e) (str label " throws"))
        (is (not (rf.error/keyword-only-message? msg))
            (str label " message is a human sentence, never a bare keyword"))
        (is (rf.error/message-has-id-token? msg)
            (str label " message carries the [:rf.error/<id>] token"))
        (is (string? (:reason (ex-data e))) (str label " carries a :reason sentence"))
        (is (= 'rf/reg-machine (:where (ex-data e))) (str label " carries :where"))
        (is (keyword? (:recovery (ex-data e))) (str label " carries :recovery"))))))

;; ---------------------------------------------------------------------------
;; chase-ref one-level indirection — a {:short-name :registered-id} binding
;; resolves the short-name to the registered fn through ONE hop
;; (transition.cljc). Exercised here through the public guard surface.
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
          {s-pass :snapshot} (rf.machines/machine-transition
                                  spec {:state :a :data {:open? true}} [:go])
          {s-fail :snapshot} (rf.machines/machine-transition
                                  spec {:state :a :data {:open? false}} [:go])]
      (is (= :b (:state s-pass))
          "indirected guard resolved + passed ⇒ transition fires")
      (is (= :a (:state s-fail))
          "indirected guard resolved + failed ⇒ no transition (guard gated it)"))))
