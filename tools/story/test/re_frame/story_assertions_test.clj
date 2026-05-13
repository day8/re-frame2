(ns re-frame.story-assertions-test
  "JVM tests for re-frame2-story Stage 5 (rf2-h8et) — `:rf.assert/*`
  vocabulary.

  Covers each of the seven canonical assertion semantics from
  spec/007 line 304:

    1. :rf.assert/path-equals    — value at path matches
    2. :rf.assert/path-matches   — value at path validates against malli
    3. :rf.assert/sub-equals     — subscription returns expected
    4. :rf.assert/dispatched?    — event observed during play
    5. :rf.assert/state-is       — machine in given state
    6. :rf.assert/no-warnings    — no warning trace events captured
    7. :rf.assert/effect-emitted — fx-id emitted from a cascade

  Plus:
  - Record-don't-throw contract (IMPL-SPEC §2.3).
  - `assertions-passing?` predicate.
  - The canonical seven register at boot."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.loaders    :as loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  ;; Clear per-frame assertion accumulators between tests.
  (reset! assertions/warnings-accumulator          {})
  (reset! assertions/emitted-fx-accumulator        {})
  (reset! assertions/dispatched-events-accumulator {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; THE SEVEN CANONICAL ASSERTIONS REGISTER AT BOOT
;; ===========================================================================

(deftest canonical-seven-registered
  (testing "all seven :rf.assert/* event handlers register at install-canonical-vocabulary!"
    (let [events (re-frame.registrar/handlers :event)]
      (is (contains? events :rf.assert/path-equals))
      (is (contains? events :rf.assert/path-matches))
      (is (contains? events :rf.assert/sub-equals))
      (is (contains? events :rf.assert/dispatched?))
      (is (contains? events :rf.assert/state-is))
      (is (contains? events :rf.assert/no-warnings))
      (is (contains? events :rf.assert/effect-emitted)))))

(deftest canonical-assertion-ids-set-exported
  (testing "canonical-assertion-ids returns the seven"
    (is (= 7 (count (story/canonical-assertion-ids))))
    (is (= assertions/canonical-assertion-ids
           (story/canonical-assertion-ids)))))

;; ===========================================================================
;; :rf.assert/path-equals
;; ===========================================================================

(deftest path-equals-pass
  (testing ":rf.assert/path-equals passes when value at path matches"
    (rf/reg-event-db :test/set-status
      (fn [db _] (assoc-in db [:auth :status] :authenticated)))
    (story/reg-variant :story.auth/happy
      {:events [[:test/set-status]]
       :play   [[:rf.assert/path-equals [:auth :status] :authenticated]]})
    (let [r (async/deref-blocking (story/run-variant :story.auth/happy) 5000)]
      (is (= 1 (count (:assertions r))))
      (is (true? (-> r :assertions first :passed?)))
      (is (= :rf.assert/path-equals (-> r :assertions first :assertion))))
    (story/destroy-variant! :story.auth/happy)))

(deftest path-equals-fail
  (testing ":rf.assert/path-equals records failure on mismatch (no throw)"
    (story/reg-variant :story.auth/sad
      {:events []
       :play   [[:rf.assert/path-equals [:auth :status] :authenticated]]})
    (let [r (async/deref-blocking (story/run-variant :story.auth/sad) 5000)]
      (is (= 1 (count (:assertions r))))
      (is (false? (-> r :assertions first :passed?)))
      (is (= :authenticated (-> r :assertions first :expected)))
      (is (nil? (-> r :assertions first :actual))))
    (story/destroy-variant! :story.auth/sad)))

;; ===========================================================================
;; :rf.assert/path-matches
;; ===========================================================================

(deftest path-matches-pass
  (testing ":rf.assert/path-matches validates against malli"
    (rf/reg-event-db :test/set-count
      (fn [db _] (assoc db :n 42)))
    (story/reg-variant :story.malli/ok
      {:events [[:test/set-count]]
       :play   [[:rf.assert/path-matches [:n] :int]]})
    (let [r (async/deref-blocking (story/run-variant :story.malli/ok) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.malli/ok)))

(deftest path-matches-fail
  (testing ":rf.assert/path-matches records failure with explanation on schema mismatch"
    (rf/reg-event-db :test/set-bad
      (fn [db _] (assoc db :n "not a number")))
    (story/reg-variant :story.malli/bad
      {:events [[:test/set-bad]]
       :play   [[:rf.assert/path-matches [:n] :int]]})
    (let [r (async/deref-blocking (story/run-variant :story.malli/bad) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.malli/bad)))

;; ===========================================================================
;; :rf.assert/sub-equals
;; ===========================================================================

(deftest sub-equals-pass
  (testing ":rf.assert/sub-equals passes when sub returns expected"
    (rf/reg-event-db :test/init
      (fn [db _] (assoc db :counter 7)))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (story/reg-variant :story.sub/v
      {:events [[:test/init]]
       :play   [[:rf.assert/sub-equals [:counter] 7]]})
    (let [r (async/deref-blocking (story/run-variant :story.sub/v) 5000)]
      (is (true? (-> r :assertions first :passed?)))
      (is (= 7 (-> r :assertions first :actual))))
    (story/destroy-variant! :story.sub/v)))

(deftest sub-equals-fail
  (testing ":rf.assert/sub-equals records the mismatch"
    (rf/reg-event-db :test/init2
      (fn [db _] (assoc db :counter 3)))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (story/reg-variant :story.sub/bad
      {:events [[:test/init2]]
       :play   [[:rf.assert/sub-equals [:counter] 7]]})
    (let [r (async/deref-blocking (story/run-variant :story.sub/bad) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.sub/bad)))

;; ===========================================================================
;; :rf.assert/dispatched?
;; ===========================================================================

(deftest dispatched-pass
  (testing ":rf.assert/dispatched? passes when an earlier event in :play fired"
    (rf/reg-event-db :test/click
      (fn [db _] (assoc db :clicked? true)))
    (story/reg-variant :story.dispatched/v
      {:events []
       :play   [[:test/click]
                [:rf.assert/dispatched? [:test/click]]]})
    (let [r       (async/deref-blocking (story/run-variant :story.dispatched/v) 5000)
          asserts (:assertions r)
          last-a  (last asserts)]
      (is (true? (:passed? last-a)) "the dispatched? assertion saw the test/click event"))
    (story/destroy-variant! :story.dispatched/v)))

(deftest dispatched-fail
  (testing ":rf.assert/dispatched? records a fail when no matching event was dispatched"
    (story/reg-variant :story.dispatched/no
      {:events []
       :play   [[:rf.assert/dispatched? [:never/fired]]]})
    (let [r (async/deref-blocking (story/run-variant :story.dispatched/no) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.dispatched/no)))

;; ===========================================================================
;; :rf.assert/state-is
;; ===========================================================================

(deftest state-is-pass
  (testing ":rf.assert/state-is passes when machine snapshot matches"
    ;; Register a tiny machine snapshot manually via app-db.
    (rf/reg-event-db :test/seed-machine
      (fn [db _] (assoc-in db [:rf/machines :traffic-light] {:state :red})))
    (story/reg-variant :story.machine/red
      {:events [[:test/seed-machine]]
       :play   [[:rf.assert/state-is :traffic-light :red]]})
    (let [r (async/deref-blocking (story/run-variant :story.machine/red) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.machine/red)))

(deftest state-is-fail
  (testing ":rf.assert/state-is records the mismatch when state differs"
    (rf/reg-event-db :test/seed-machine2
      (fn [db _] (assoc-in db [:rf/machines :traffic-light] {:state :green})))
    (story/reg-variant :story.machine/mismatch
      {:events [[:test/seed-machine2]]
       :play   [[:rf.assert/state-is :traffic-light :red]]})
    (let [r (async/deref-blocking (story/run-variant :story.machine/mismatch) 5000)]
      (is (false? (-> r :assertions first :passed?)))
      (is (= :red   (-> r :assertions first :expected)))
      (is (= :green (-> r :assertions first :actual))))
    (story/destroy-variant! :story.machine/mismatch)))

;; ===========================================================================
;; :rf.assert/no-warnings  (Stage 5 trace-bus accumulator)
;; ===========================================================================

(deftest no-warnings-pass-when-silent
  (testing ":rf.assert/no-warnings passes when no warning was emitted"
    (story/reg-variant :story.warn/silent
      {:events []
       :play   [[:rf.assert/no-warnings]]})
    (let [r (async/deref-blocking (story/run-variant :story.warn/silent) 5000)]
      (is (true? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.warn/silent)))

;; ===========================================================================
;; :rf.assert/effect-emitted  (Stage 5 trace-bus accumulator + fx-stub log)
;; ===========================================================================

(deftest effect-emitted-fail-when-no-fx
  (testing ":rf.assert/effect-emitted records a fail when no fx fired"
    (story/reg-variant :story.fx/none
      {:events []
       :play   [[:rf.assert/effect-emitted :http]]})
    (let [r (async/deref-blocking (story/run-variant :story.fx/none) 5000)]
      (is (false? (-> r :assertions first :passed?))))
    (story/destroy-variant! :story.fx/none)))

;; ===========================================================================
;; Record-don't-throw contract — IMPL-SPEC §2.3
;; ===========================================================================

(deftest record-not-throw-on-failure
  (testing "a failing assertion never throws; the play sequence continues"
    (rf/reg-event-db :test/touch (fn [db _] (assoc db :touched true)))
    (story/reg-variant :story.contract/v
      {:events []
       :play   [[:rf.assert/path-equals [:nope] :unexpected]    ; fail
                [:test/touch]                                    ; should still fire
                [:rf.assert/path-equals [:touched] true]]})      ; pass
    (let [r (async/deref-blocking (story/run-variant :story.contract/v) 5000)]
      ;; Both assertions recorded — sequence did NOT halt on the first
      ;; failure.
      (is (= 2 (count (:assertions r))))
      (is (false? (-> r :assertions first :passed?)))
      (is (true?  (-> r :assertions second :passed?)))
      (is (true?  (-> r :app-db :touched))
          ":test/touch fired between the two assertions"))
    (story/destroy-variant! :story.contract/v)))

;; ===========================================================================
;; assertions-passing? — the cljs.test adapter predicate
;; ===========================================================================

(deftest assertions-passing-vacuously-true-on-empty
  (testing "an empty assertions list passes vacuously (spec/007 §Story-as-test)"
    (story/reg-variant :story.empty/v {:events [] :play []})
    (let [r (async/deref-blocking (story/run-variant :story.empty/v) 5000)]
      (is (true? (story/assertions-passing? r))
          "a variant with no :play still 'passes' for cljs.test integration")
      (is (empty? (:assertions r))))
    (story/destroy-variant! :story.empty/v)))

(deftest assertions-passing-true-on-all-pass
  (testing "passing? returns true when every assertion has :passed? true"
    (rf/reg-event-db :test/n (fn [db _] (assoc db :n 42)))
    (story/reg-variant :story.all-pass/v
      {:events [[:test/n]]
       :play   [[:rf.assert/path-equals [:n] 42]
                [:rf.assert/path-matches [:n] :int]]})
    (let [r (async/deref-blocking (story/run-variant :story.all-pass/v) 5000)]
      (is (true? (story/assertions-passing? r)))
      ;; Also accepts the assertions vector directly:
      (is (true? (story/assertions-passing? (:assertions r)))))
    (story/destroy-variant! :story.all-pass/v)))

(deftest assertions-passing-false-on-any-fail
  (testing "passing? returns false when any assertion failed"
    (rf/reg-event-db :test/n2 (fn [db _] (assoc db :n 1)))
    (story/reg-variant :story.any-fail/v
      {:events [[:test/n2]]
       :play   [[:rf.assert/path-equals [:n] 1]       ; pass
                [:rf.assert/path-equals [:n] 999]]})  ; fail
    (let [r (async/deref-blocking (story/run-variant :story.any-fail/v) 5000)]
      (is (false? (story/assertions-passing? r))))
    (story/destroy-variant! :story.any-fail/v)))

;; ===========================================================================
;; The assertion record carries the canonical fields
;; ===========================================================================

(deftest record-shape
  (testing "an assertion record carries :assertion :payload :passed? :elapsed-ms :reason"
    (rf/reg-event-db :test/init3 (fn [db _] (assoc db :x 1)))
    (story/reg-variant :story.shape/v
      {:events [[:test/init3]]
       :play   [[:rf.assert/path-equals [:x] 1]]})
    (let [r (async/deref-blocking (story/run-variant :story.shape/v) 5000)
          a (first (:assertions r))]
      (is (= :rf.assert/path-equals  (:assertion a)))
      (is (= [[:x] 1]                (:payload a)))
      (is (true?                     (:passed? a)))
      (is (number?                   (:elapsed-ms a)))
      (is (string?                   (:reason a))))
    (story/destroy-variant! :story.shape/v)))

;; ===========================================================================
;; read-assertions — public alias for the per-frame accumulator
;; ===========================================================================

(deftest read-assertions-public
  (testing "story/read-assertions returns the live accumulator"
    (rf/reg-event-db :test/q (fn [db _] (assoc db :q :ok)))
    (story/reg-variant :story.read/v
      {:events [[:test/q]]
       :play   [[:rf.assert/path-equals [:q] :ok]]})
    (let [_ (async/deref-blocking (story/run-variant :story.read/v) 5000)
          a (story/read-assertions :story.read/v)]
      (is (= 1 (count a)))
      (is (true? (:passed? (first a)))))
    (story/destroy-variant! :story.read/v)))

;; ===========================================================================
;; assertion-event? — play-runner discriminator
;; ===========================================================================

(deftest assertion-event-discriminator
  (testing "assertion-event? recognises :rf.assert/* but not other namespaces"
    (is (true?  (assertions/assertion-event? [:rf.assert/path-equals [:x] 1])))
    (is (true?  (assertions/assertion-event? [:rf.assert/no-warnings])))
    (is (false? (assertions/assertion-event? [:auth/login])))
    (is (false? (assertions/assertion-event? [:rf.story/something])))
    (is (false? (assertions/assertion-event? nil)))
    (is (false? (assertions/assertion-event? [])))))
