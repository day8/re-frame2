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
            [re-frame.story.config :as config]
            [re-frame.story.play :as play]
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
            registrar (they're reg-event handlers, not Story side-table
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

;; ---- 2b. play-atom isolation (rf2-eztym.2) -------------------------------
;;
;; play.cljc owns two per-process defonce atoms keyed by frame-id —
;; `pending-exceptions` and `stepper-state`. Per-frame teardown
;; (`frames/destroy!`) evicts them one frame at a time, but a registry reset
;; that bypasses frame teardown previously left both populated. The canonical
;; test-reset path (`story/clear-all!` → `play/clear-all-play-state!`, and
;; `story-reset!` via the fixture) must now wipe both, else a stepper /
;; pending-exception session in one test leaks into the next: a stale
;; `stepper-state` entry makes `play-stepper-active?` report a session the
;; later test never began.

(deftest clear-all-evicts-play-atoms
  (testing "story/clear-all! wipes pending-exceptions + stepper-state — the
            remaining un-reset per-process play state (rf2-eztym.2)"
    ;; Dirty both atoms directly (the per-frame mutators are private; these
    ;; are the slots a real begin-stepper! / handler-exception capture fill).
    (reset! play/pending-exceptions {:story.leak/frame [{:op-type :error}]})
    (reset! play/stepper-state      {:story.leak/frame {:remaining [] :ran [] :results []}})
    (is (play/play-stepper-active? :story.leak/frame)
        "sanity: the stale stepper session reads active before the reset")
    (story/clear-all!)
    (is (= {} @play/pending-exceptions)
        "clear-all! emptied pending-exceptions")
    (is (= {} @play/stepper-state)
        "clear-all! emptied stepper-state")
    (is (not (play/play-stepper-active? :story.leak/frame))
        "the stale stepper session is gone — play-stepper-active? reads false")))

(deftest story-reset-evicts-play-atoms
  (testing "the fixture's story-reset! (run via with-clean-registry) fully
            clears both play atoms, so no stepper / pending-exception state
            bleeds across a reset that bypasses frame teardown (rf2-eztym.2)"
    (reset! play/pending-exceptions {:story.leak/frame [{:op-type :error}]})
    (reset! play/stepper-state      {:story.leak/frame {:remaining [] :ran [] :results []}})
    (story-test/with-clean-registry
      {:adapter plain-atom/adapter}
      (fn []
        (is (= {} @play/pending-exceptions)
            "story-reset! emptied pending-exceptions inside the bracket")
        (is (= {} @play/stepper-state)
            "story-reset! emptied stepper-state inside the bracket")
        (is (not (play/play-stepper-active? :story.leak/frame))
            "no stale stepper session survives the reset")
        nil))))

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

;; ---- 4. config-leak isolation (rf2-6ez1u) --------------------------------
;;
;; The fixture (`story-reset!`) calls `config/reset-all!`, which restores
;; every leakable process-global config atom. These tests lock that a
;; `configure!` (or any config mutation) in one test cannot leak into a
;; sibling test in the same JVM run. The footgun the bead names:
;; `global-args` is Layer 1 of args resolution, so a leaked global arg
;; silently changes the EFFECTIVE args of an unrelated later variant.
;;
;; The two `config-isolation-*` tests mirror the `install-thunk-ran-*`
;; sibling-pair pattern above: whichever runs first asserts the pristine
;; default (proving NO prior test leaked) THEN mutates config; the other
;; proves the mutation did not survive the fixture reset — independent of
;; clojure.test's run order.

(deftest config-reset-all-restores-every-leakable-atom
  (testing "config/reset-all! (called by the fixture) restores every
            leakable config atom to its load-time default — direct,
            order-independent unit coverage of the reset surface
            (rf2-6ez1u)"
    ;; Dirty every leakable atom via the public configure! surface…
    (story/configure! {:rf.story/global-args     {:theme :dark}
                       :rf.story/editor          :cursor
                       :rf.story/project-root    "/tmp/proj"
                       :rf.story/egress-profile  :rf.egress/local-raw})
    (config/note-suppressed! :some.variant/x)
    (config/add-global-decorator! [:some/global-decorator])
    ;; sanity: the mutations landed
    (is (= {:theme :dark} (config/get-global-args)))
    (is (= :rf.egress/local-raw @config/session-egress-profile))
    ;; …then reset and assert the pristine load-time defaults.
    (config/reset-all!)
    (is (= {}      (config/get-global-args))      "global-args → {}")
    (is (= []      (config/get-global-decorators)) "global-decorators → []")
    (is (= :vscode (config/get-editor))           "editor → :vscode")
    (is (nil?      (config/get-project-root))     "project-root → nil")
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "egress-profile → :rf.egress/local-redacted")
    (is (= 0       (config/suppressed-count :some.variant/x))
        "suppressed-counters → {}")))

(deftest config-reset-all-leaves-toggle-off-callbacks
  (testing "config/reset-all! does NOT clear toggle-off-callbacks — those
            are load-time module registrations, not per-test state
            (rf2-6ez1u). Resetting egress-profile also does not FIRE them
            (reset! restores the default; it does not simulate a runtime
            reveal → redact narrowing)."
    (let [fired (atom 0)]
      ;; rf2-6z4znr — toggle-off callbacks now take the narrowed frame-id
      ;; (nil on a session-pin narrow).
      (config/register-toggle-off-callback! ::probe (fn [_frame-id] (swap! fired inc)))
      ;; widen to raw via the public surface, then reset-all!
      (config/set-egress-profile! :rf.egress/local-raw)
      (config/reset-all!)
      (try
        (is (= :rf.egress/local-redacted @config/session-egress-profile)
            "the profile was restored to its redacting default")
        (is (zero? @fired)
            "reset-all! restored the profile without firing the toggle-off hook")
        ;; the callback is still REGISTERED — a real runtime narrowing fires it
        (config/set-egress-profile! :rf.egress/local-raw)
        (config/set-egress-profile! :rf.egress/local-redacted)
        (is (= 1 @fired)
            "the load-time callback survived reset-all! and still fires on a
             real reveal → redact runtime narrowing")
        (finally
          (config/unregister-toggle-off-callback! ::probe))))))

(deftest config-isolation-a
  (testing "this test sees a pristine global config (no sibling leaked into
            it) THEN dirties global-args — the fixture must roll it back
            before config-isolation-b runs (rf2-6ez1u)"
    (is (= {} (config/get-global-args))
        "global-args is the pristine {} default — no sibling test leaked a
         configure! into this one")
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "egress-profile is the pristine :rf.egress/local-redacted default")
    ;; A variant with NO args resolves to exactly the empty global layer.
    (story/reg-variant :story.cfg/probe-a {:tags #{:test}})
    (is (= {} (story/resolve-args :story.cfg/probe-a))
        "with a clean global layer, an arg-less variant resolves to {}")
    ;; Now leak a Layer-1 global arg + widen the egress profile. If the
    ;; fixture's config/reset-all! did NOT run, config-isolation-b would
    ;; observe these.
    (story/configure! {:rf.story/global-args     {:rf.cfg/leaked :from-a}
                       :rf.story/egress-profile  :rf.egress/local-raw})
    (is (= {:rf.cfg/leaked :from-a} (story/resolve-args :story.cfg/probe-a))
        "the leaked global arg IS Layer 1 of resolution within this test —
         which is exactly why it must not survive into the next")))

(deftest config-isolation-b
  (testing "the sibling test sees its OWN clean config slate — the
            global-args + egress-profile config-isolation-a set are gone
            (config/reset-all! ran in the fixture between them) (rf2-6ez1u)"
    (is (= {} (config/get-global-args))
        "config-isolation-a's leaked global-args did NOT survive the reset")
    (is (= :rf.egress/local-redacted @config/session-egress-profile)
        "config-isolation-a's egress-profile widening did NOT survive the reset")
    (story/reg-variant :story.cfg/probe-b {:tags #{:test}})
    (is (= {} (story/resolve-args :story.cfg/probe-b))
        "with the global layer reset, an arg-less variant resolves to {} —
         NOT to {:rf.cfg/leaked :from-a} (the green-but-wrong footgun this
         closes)")
    ;; leak our own, symmetric to the a/b pattern — config-isolation-a's
    ;; reset proves the converse direction too under either run order.
    (story/configure! {:rf.story/global-args {:rf.cfg/leaked :from-b}})))
