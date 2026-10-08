(ns re-frame.story.test-support-test
  "JVM coverage for the canonical Story test-fixture helper
  (`re-frame.story.test-support`): a variant run under the helper reaches
  `:ready` rather than parking at `:pre-mount`, the canonical vocabulary is
  installed, and Story's registry, play state, run-state and config do not
  leak between tests.

  JVM-only (`.clj`): on the JVM `run-variant` returns a synchronously
  resolved `CompletableFuture`, so the lifecycle result is observable inline."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.test-support :as rf.story.test-support]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

;; The fixture under test IS the helper, declared in the fn form (the map
;; form silently skips on the JVM; see `re-frame.story.meta-fixtures-test`).
(use-fixtures :each
  (rf.story.test-support/use-fixtures
    {:adapter rf.substrate.plain-atom/adapter
     :install [#(rf.story/reg-variant :story.fixture/seeded
                  {:tags  #{:test}
                   :setup [[:rf.story.test/noop]]})]}))

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run-variant target)))

(deftest canonical-vocabulary-installed-by-helper
  (testing "the seven REGISTERED canonical :rf.assert/* handlers are on the
            framework registrar after the reset (:rf.assert/schema-error is
            tape-evaluated, never registered)"
    (let [events (rf.registrar/registrations :event)]
      (is (= 7 (count (filter #(contains? events %)
                              (rf.story/canonical-assertion-ids))))))))

;; ---- per-test isolation ---------------------------------------------------
;;
;; Each pair is order-independent: whichever test runs first asserts the
;; pristine state and then dirties it; the other proves the fixture rolled the
;; dirt back.

(deftest install-thunk-ran-and-isolation-holds-a
  (testing "the :install thunk ran; neither the sibling's variant nor its
            config leaked in"
    (is (rf.story/registered? :variant :story.fixture/seeded))
    (is (not (rf.story/registered? :variant :story.fixture/only-in-b)))
    (is (= {} (rf.story.config/get-global-args)))
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile))
    (rf.story/reg-variant :story.fixture/only-in-a {:tags #{:test}})
    (rf.story/configure! {:rf.story/global-args    {:rf.cfg/leaked :from-a}
                          :rf.story/egress-profile :rf.egress/local-raw})))

(deftest install-thunk-ran-and-isolation-holds-b
  (testing "the :install thunk ran again; the sibling's variant and config are gone"
    (is (rf.story/registered? :variant :story.fixture/seeded))
    (is (not (rf.story/registered? :variant :story.fixture/only-in-a)))
    (is (= {} (rf.story.config/get-global-args)))
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile))
    (rf.story/reg-variant :story.fixture/only-in-b {:tags #{:test}})
    (rf.story/configure! {:rf.story/global-args    {:rf.cfg/leaked :from-b}
                          :rf.story/egress-profile :rf.egress/local-raw})))

(deftest run-state-wiped-between-tests
  (testing "a test starts with an empty play run-state, and a run records one
            for the next test's fixture to wipe"
    (is (empty? @rf.story.play.runner-events/run-state))
    (rf.story/reg-variant :story.fixture/runs
      {:tags   #{:test}
       :script [[:dispatch-sync [:rf.assert/no-warnings]]]})
    (run-target :story.fixture/runs)
    (is (contains? @rf.story.play.runner-events/run-state :story.fixture/runs))))

;; play.cljc's per-process `pending-exceptions` and `stepper-state` atoms are
;; keyed by frame-id and evicted by per-frame teardown, so a reset that
;; bypasses frame teardown must wipe them or a stale stepper session leaks.

(deftest clear-all-evicts-play-atoms
  (testing "rf.story/clear-all! wipes pending-exceptions + stepper-state"
    (reset! rf.story.play/pending-exceptions {:story.leak/frame [{:op-type :error}]})
    (reset! rf.story.play/stepper-state      {:story.leak/frame {:remaining [] :ran [] :results []}})
    (rf.story/clear-all!)
    (is (= [{} {}] [@rf.story.play/pending-exceptions @rf.story.play/stepper-state]))))

(deftest story-reset-evicts-play-atoms
  (testing "the helper's reset wipes both play atoms inside the bracket"
    (reset! rf.story.play/pending-exceptions {:story.leak/frame [{:op-type :error}]})
    (reset! rf.story.play/stepper-state      {:story.leak/frame {:remaining [] :ran [] :results []}})
    (rf.story.test-support/with-clean-registry
      {:adapter rf.substrate.plain-atom/adapter}
      (fn []
        (is (= [{} {}] [@rf.story.play/pending-exceptions @rf.story.play/stepper-state]))
        nil))))

(deftest with-clean-registry-brackets-and-returns
  (testing "with-clean-registry runs the thunk inside a full reset and returns
            its value; the variant reaches :ready, not :pre-mount"
    (is (= :ready
           (rf.story.test-support/with-clean-registry
             {:adapter rf.substrate.plain-atom/adapter}
             (fn []
               (rf.story/reg-variant :story.fixture/bracketed
                 {:tags   #{:test}
                  :script [[:dispatch-sync [:rf.assert/no-warnings]]]})
               (:lifecycle (run-target :story.fixture/bracketed))))))))

;; ---- config reset ---------------------------------------------------------
;;
;; `global-args` is Layer 1 of args resolution, so a leaked global arg would
;; silently change the effective args of an unrelated later variant.

(deftest config-reset-all-restores-every-leakable-atom
  (testing "rf.story.config/reset-all! restores every leakable config atom to
            its load-time default"
    (rf.story/configure! {:rf.story/global-args     {:theme :dark}
                          :rf.story/editor          :cursor
                          :rf.story/project-root    "/tmp/proj"
                          :rf.story/egress-profile  :rf.egress/local-raw})
    (rf.story.config/note-suppressed! :some.variant/x)
    (rf.story.config/add-global-decorator! [:some/global-decorator])
    (is (= [{:theme :dark} :rf.egress/local-raw]
           [(rf.story.config/get-global-args) @rf.story.config/session-egress-profile])
        "sanity: the mutations landed")
    (rf.story.config/reset-all!)
    (is (= [{} [] :vscode nil :rf.egress/local-redacted 0]
           [(rf.story.config/get-global-args)
            (rf.story.config/get-global-decorators)
            (rf.story.config/get-editor)
            (rf.story.config/get-project-root)
            @rf.story.config/session-egress-profile
            (rf.story.config/suppressed-count :some.variant/x)]))))

(deftest config-reset-all-leaves-toggle-off-callbacks
  (testing "reset-all! neither clears nor fires the load-time toggle-off
            callbacks; a real reveal → redact narrowing still fires them"
    (let [fired (atom 0)]
      (rf.story.config/register-toggle-off-callback! ::probe (fn [_frame-id] (swap! fired inc)))
      (rf.story.config/set-egress-profile! :rf.egress/local-raw)
      (rf.story.config/reset-all!)
      (try
        (is (zero? @fired) "reset-all! restored the profile without firing the hook")
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (= 1 @fired) "the callback survived reset-all! and fires on a real narrowing")
        (finally
          (rf.story.config/unregister-toggle-off-callback! ::probe))))))
