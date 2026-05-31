(ns re-frame.machine-pure-error-contract-test
  "Coverage & rigour pass (rf2-ynjts.10). Direct pure-engine (JVM)
  coverage for the runtime error / resolution contracts that the
  REGISTRATION-TIME validator (`validate-machine!`) does NOT cover —
  because the pure-call surface (`re-frame.machines/machine-transition`,
  the conformance corpus, JVM fixtures) reaches the engine WITHOUT
  running `validate-machine!`. A grep of the machines test tree before
  this file returned ZERO direct matches for these contracts:

    - `transition/unhandled-event-warnable?` — the reserved-`:rf/*`
      namespace carve-out (transition.cljc:607). Documented in the
      remediation suite's docstring but never directly asserted. A
      refactor of the `ns` / `str/starts-with?` predicate would
      silently re-arm the unhandled-event advisory against benign
      framework lifecycle traffic (the stories-library `:rf.story.*`
      pings, the synthetic `:rf.machine/spawned` kick-off) and no test
      would catch it.

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
;; unhandled-event-warnable? — the reserved-:rf/* carve-out
;; (transition.cljc:607). Public pure predicate; assert both arms directly.
;; ---------------------------------------------------------------------------

(deftest unhandled-event-warnable?-flags-domain-events
  (testing "an ordinary domain event (un-namespaced or app-namespaced)
   IS warnable — a machine author forgot to handle it"
    (is (true? (transition/unhandled-event-warnable? [:logout]))
        "bare un-namespaced id is warnable")
    (is (true? (transition/unhandled-event-warnable? [:auth/succeeded]))
        "app-namespaced id is warnable")
    (is (true? (transition/unhandled-event-warnable? [:my.app.feature/ping]))
        "deeply-namespaced app id is warnable")))

(deftest unhandled-event-warnable?-carves-out-reserved-rf-namespace
  (testing "a reserved :rf-rooted framework lifecycle event is NOT warnable
   — benign framework traffic resolving to a no-op (Conventions.md §The
   single-root reserved set)"
    (is (false? (transition/unhandled-event-warnable? [:rf/anything]))
        "the bare :rf root is carved out")
    (is (false? (transition/unhandled-event-warnable? [:rf.machine/spawned]))
        "the synthetic spawn kick-off (005:1780) is carved out")
    (is (false? (transition/unhandled-event-warnable? [:rf.story.lifecycle/events-complete]))
        "the stories-library lifecycle ping is carved out")
    (is (false? (transition/unhandled-event-warnable? [:rf.assert/passed]))
        "the stories-library assertion event is carved out"))

  (testing "the carve-out is keyed on the `rf` / `rf.` namespace head,
   NOT a substring — an app namespace that merely CONTAINS `rf` stays
   warnable (guards against a `clojure.string/includes?`-style regression)"
    (is (true? (transition/unhandled-event-warnable? [:surf/wave]))
        "`surf` is not the reserved root even though it contains `rf`")
    (is (true? (transition/unhandled-event-warnable? [:my-rf-app/ev]))
        "`my-rf-app` is not the reserved root")
    (is (true? (transition/unhandled-event-warnable? [:rfx/ev]))
        "`rfx` is a distinct namespace, not `rf` / `rf.`-prefixed"))

  (testing "a non-keyword event id (no namespace) is warnable — the
   carve-out only suppresses keyword ids in the reserved namespace"
    (is (true? (transition/unhandled-event-warnable? ["string-event"]))
        "a string event-id has no namespace ⇒ warnable")))

;; ---------------------------------------------------------------------------
;; End-to-end carve-out through the pure macrostep: a reserved-:rf/*
;; unhandled event emits NO :rf.error/machine-unhandled-event, while a
;; domain unhandled event DOES. Pins the warnable predicate to the engine's
;; actual emission decision (single source of truth, transition.cljc:1654).
;; ---------------------------------------------------------------------------

(defn- capture-ops!
  "Drive a pure `machine-transition` while a tooling listener records the
  `:operation` of every emitted trace. Returns the vector of operations
  (deterministic — no wall-clock / random)."
  [definition snapshot event]
  (let [seen (atom [])]
    (trace/register-listener! ::ops (fn [ev] (swap! seen conj (:operation ev))))
    (try
      (machines/machine-transition definition snapshot event)
      (finally (trace/unregister-listener! ::ops)))
    @seen))

(def ^:private no-handler-spec
  "A machine that handles only `:known`; everything else is unhandled."
  {:id     :probe/unhandled
   :initial :a
   :data    {}
   :states  {:a {:on {:known {:target :a}}}}})

(deftest domain-unhandled-event-emits-the-advisory
  (testing "an unhandled DOMAIN event emits exactly one
   :rf.error/machine-unhandled-event and leaves the snapshot unchanged"
    (let [ops (capture-ops! no-handler-spec {:state :a :data {}} [:nope])]
      (is (= 1 (count (filter #{:rf.error/machine-unhandled-event} ops)))
          "exactly one unhandled-event advisory for a domain event")))

  (testing "the snapshot is unchanged on an unhandled event (no state churn)"
    (let [{s ::result/snap} (machines/machine-transition
                              no-handler-spec {:state :a :data {}} [:nope])]
      (is (= :a (:state s)) "state unchanged"))))

(deftest reserved-rf-unhandled-event-is-silent
  (testing "an unhandled RESERVED-:rf/* event emits NO unhandled-event
   advisory — the carve-out keeps framework lifecycle traffic quiet"
    (let [ops (capture-ops! no-handler-spec {:state :a :data {}}
                            [:rf.story.lifecycle/events-complete])]
      (is (zero? (count (filter #{:rf.error/machine-unhandled-event} ops)))
          "no advisory for a reserved-namespace unhandled event")))

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
