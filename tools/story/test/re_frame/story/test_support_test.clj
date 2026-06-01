(ns re-frame.story.test-support-test
  "JVM coverage for the canonical Story test-fixture helper (rf2-lh99f).

  The helper (`re-frame.story/use-fixtures` / `with-clean-registry`,
  bodies in `re-frame.story.test-support`) replaces the hand-rolled
  `reset-all!` every consumer used to copy. The two things this suite
  locks:

  1. **No `:pre-mount` footgun.** A variant registered + run UNDER the
     helper reaches `:ready` — proving the canonical Story vocabulary
     (the seven `:rf.assert/*` handlers + the lifecycle machine) and the
     framework `:rf/machine` subscription are present after the reset.
     The footgun the bead names is a reset that wipes the `:rf/machine`
     sub and traps the variant at `:pre-mount`; the helper resets via
     registrar snapshot/restore, so it cannot happen.

  2. **Per-test isolation + run-state wipe.** The helper clears Story's
     side-table and the per-variant play run-state between tests.

  JVM-only (`.clj`): on the JVM `run-variant` returns a synchronously-
  resolved `CompletableFuture`, so the lifecycle result is observable
  inline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.test-support :as story-test]
            [re-frame.story.play.runner-events :as runner-events]))

;; The fixture under test IS the helper — required DIRECTLY from
;; `re-frame.story.test-support` (it is NOT on the `re-frame.story` facade
;; because it pulls `cljs.test`; the facade is production code). Declared
;; with the fn form per the `.cljc` cross-platform rule (the map form
;; silently skips on the JVM; see `re-frame.story.meta-fixtures-test`).
(use-fixtures :each
  (story-test/use-fixtures
    {:adapter plain-atom/adapter
     :install [#(story/reg-variant :story.fixture/seeded
                  {:tags  #{:test}
                   :setup [[:rf.story.test/noop]]})]}))

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (story/run-variant target)))

;; ---- 1. no :pre-mount footgun --------------------------------------------

(deftest helper-variant-reaches-ready-not-pre-mount
  (testing "a variant registered + run under the helper reaches :ready —
            the canonical vocabulary + lifecycle machine survive the
            reset, so the variant is NOT trapped at :pre-mount (rf2-lh99f
            — the footgun this helper closes)"
    (story/reg-variant :story.fixture/lands-ready
      {:tags   #{:test}
       :script [[:dispatch-sync [:rf.assert/no-warnings]]]})
    (let [result (run-target :story.fixture/lands-ready)]
      (is (= :ready (:lifecycle result))
          "the four-phase lifecycle reached :ready (NOT :pre-mount)")
      (is (not= :pre-mount (:lifecycle result))
          "the variant did not park at :pre-mount"))))

(deftest canonical-vocabulary-installed-by-helper
  (testing "the helper re-installs the canonical Story vocabulary each test —
            the :rf.assert/* event handlers are registered on the FRAMEWORK
            registrar (they're reg-event-fx handlers, not Story side-table
            entries)"
    (let [events (registrar/registrations :event)]
      (is (contains? events :rf.assert/path-equals)
          ":rf.assert/path-equals handler present after the reset")
      ;; The eighth canonical id (:rf.assert/schema-error) is tape-evaluated,
      ;; never a registered handler — so the REGISTERED set is the seven.
      (is (= 7 (count (filter #(contains? events %)
                              (story/canonical-assertion-ids))))
          "all seven REGISTERED canonical assertion handlers present"))))

;; ---- 2. per-test isolation + run-state wipe ------------------------------

(deftest install-thunk-ran-and-isolation-holds-a
  (testing "the :install thunk registered :story.fixture/seeded; a variant
            registered ONLY in another test is absent here (isolation)"
    (is (story/registered? :variant :story.fixture/seeded)
        "the :install thunk ran against the fresh registry")
    (is (not (story/registered? :variant :story.fixture/only-in-b))
        "a variant from a sibling test did not leak in")
    (story/reg-variant :story.fixture/only-in-a {:tags #{:test}})))

(deftest install-thunk-ran-and-isolation-holds-b
  (testing "the sibling test sees its OWN clean slate — :only-in-a from the
            previous test is gone (the registrar snapshot was restored)"
    (is (story/registered? :variant :story.fixture/seeded)
        "the :install thunk ran again for this test")
    (is (not (story/registered? :variant :story.fixture/only-in-a))
        "the previous test's variant was rolled back")
    (story/reg-variant :story.fixture/only-in-b {:tags #{:test}})))

(deftest run-state-wiped-between-tests
  (testing "the helper wipes the per-variant play run-state — a run in this
            test does not observe a previous test's run-state"
    ;; Nothing should be in the run-state at test start (fresh wipe).
    (is (empty? @runner-events/run-state)
        "play run-state is empty at the start of a freshly-reset test")
    (story/reg-variant :story.fixture/runs
      {:tags   #{:test}
       :script [[:dispatch-sync [:rf.assert/no-warnings]]]})
    (run-target :story.fixture/runs)
    ;; After a run the run-state carries this variant's outcome; the NEXT
    ;; test's fixture will wipe it (proven by the empty? check above firing
    ;; clean across the suite's deterministic-but-unordered test order).
    (is (contains? @runner-events/run-state :story.fixture/runs)
        "the run recorded its run-state for this frame")))

;; ---- 3. with-clean-registry — the programmatic bracket -------------------

(deftest with-clean-registry-brackets-and-returns
  (testing "with-clean-registry runs the thunk inside a full reset and
            returns its value; the variant reaches :ready inside the bracket"
    (let [lifecycle
          (story-test/with-clean-registry
            {:adapter plain-atom/adapter}
            (fn []
              (story/reg-variant :story.fixture/bracketed
                {:tags   #{:test}
                 :script [[:dispatch-sync [:rf.assert/no-warnings]]]})
              (:lifecycle (run-target :story.fixture/bracketed))))]
      (is (= :ready lifecycle)
          "the bracketed run reached :ready (no :pre-mount footgun)"))))
