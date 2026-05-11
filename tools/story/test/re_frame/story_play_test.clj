(ns re-frame.story-play-test
  "JVM tests for re-frame2-story Stage 5 (rf2-h8et) — play sequence
  execution.

  Covers:

  - execute-play! returns a promise of the assertions vector.
  - Play events dispatch in declared order.
  - Mixed real-dispatches + :rf.assert/* events compose.
  - Trace-bus accumulators (dispatched? / effect-emitted /
    no-warnings).
  - Per-frame teardown clears accumulators.
  - The play-stepper hooks (begin-stepper! / step-once! / end-stepper!).
  - :loaders-complete-when non-default forms (registered event id,
    vector of event vectors)."
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
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.play       :as play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-counters!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (reset! assertions/warnings-accumulator          {})
  (reset! assertions/emitted-fx-accumulator        {})
  (reset! assertions/dispatched-events-accumulator {})
  (reset! play/stepper-state                       {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; execute-play! basic
;; ===========================================================================

(deftest execute-play-empty
  (testing "execute-play! against an empty :play resolves to []"
    (story/reg-variant :story.play/empty {:events []})
    (async/deref-blocking (story/run-variant :story.play/empty) 5000)
    (let [p (play/execute-play! :story.play/empty [])]
      (is (= [] (async/deref-blocking p 5000))))
    (story/destroy-variant! :story.play/empty)))

(deftest execute-play-dispatches-in-order
  (testing "play events dispatch in declared order"
    (let [order (atom [])]
      (rf/reg-event-db :step/a (fn [db _] (swap! order conj :a) db))
      (rf/reg-event-db :step/b (fn [db _] (swap! order conj :b) db))
      (rf/reg-event-db :step/c (fn [db _] (swap! order conj :c) db))
      (story/reg-variant :story.order/v
        {:events []
         :play   [[:step/a] [:step/b] [:step/c]]})
      (async/deref-blocking (story/run-variant :story.order/v) 5000)
      (is (= [:a :b :c] @order)))
    (story/destroy-variant! :story.order/v)))

(deftest execute-play-mixes-dispatches-and-assertions
  (testing "mixed sequence of regular events + :rf.assert/* events"
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.mix/v
      {:events []
       :play   [[:counter/inc]
                [:rf.assert/path-equals [:n] 1]
                [:counter/inc]
                [:rf.assert/path-equals [:n] 2]
                [:counter/inc]
                [:rf.assert/path-equals [:n] 3]]})
    (let [r (async/deref-blocking (story/run-variant :story.mix/v) 5000)]
      (is (= 3 (count (:assertions r))))
      (is (every? :passed? (:assertions r)))
      (is (= 3 (-> r :app-db :n))))
    (story/destroy-variant! :story.mix/v)))

(deftest execute-play-exception-records-phase-4
  (testing "an exception in a play event lands as :phase-4-play"
    (rf/reg-event-db :boom/now
      (fn [_ _] (throw (ex-info "boom" {:cause :test}))))
    (story/reg-variant :story.boom/v
      {:events []
       :play   [[:boom/now]
                [:rf.assert/path-equals [:after] :ok]]})
    (let [r (async/deref-blocking (story/run-variant :story.boom/v) 5000)
          exc (->> (:assertions r)
                   (filter #(= :rf.error/exception (:assertion %)))
                   first)]
      (is (some? exc) "an exception assertion record was captured")
      (is (= :phase-4-play (:phase exc))
          "the exception is tagged as phase-4-play (record-don't-throw)")
      ;; The follow-on assertion still ran:
      (is (= 2 (count (:assertions r)))))
    (story/destroy-variant! :story.boom/v)))

;; ===========================================================================
;; The trace-bus accumulators clear at play start
;; ===========================================================================

(deftest accumulators-reset-per-run
  (testing "trace-bus accumulators reset at the start of each play run"
    (rf/reg-event-db :do/work (fn [db _] (assoc db :did? true)))
    (story/reg-variant :story.reset/v
      {:events []
       :play   [[:do/work]
                [:rf.assert/dispatched? [:do/work]]]})
    (async/deref-blocking (story/run-variant :story.reset/v) 5000)
    ;; The first run's dispatched-events accumulator must NOT leak into
    ;; the second run — reset-variant tears the frame down + re-runs.
    (let [r2 (async/deref-blocking (story/reset-variant :story.reset/v) 5000)]
      (is (true? (-> r2 :assertions first :passed?))
          "second run's accumulator only sees that run's events"))
    (story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; Frame teardown clears accumulator entries
;; ===========================================================================

(deftest teardown-clears-accumulators
  (testing "destroy-variant! clears per-frame accumulator slots"
    (story/reg-variant :story.tear/v
      {:events []
       :play   [[:rf.assert/path-equals [:x] :nope]]})
    (async/deref-blocking (story/run-variant :story.tear/v) 5000)
    (story/destroy-variant! :story.tear/v)
    (is (not (contains? @assertions/warnings-accumulator :story.tear/v)))
    (is (not (contains? @assertions/emitted-fx-accumulator :story.tear/v)))
    (is (not (contains? @assertions/dispatched-events-accumulator :story.tear/v)))))

;; ===========================================================================
;; Play stepper
;; ===========================================================================

(deftest play-stepper-step-by-step
  (testing "begin-stepper! + step-once! drives the play one event at a time"
    (rf/reg-event-db :step/one (fn [db _] (assoc db :one? true)))
    (rf/reg-event-db :step/two (fn [db _] (assoc db :two? true)))
    (story/reg-variant :story.stepper/v
      {:events []
       :play   [[:step/one] [:step/two]]})
    ;; Run the variant phases 1-3; the play stepper takes over phase 4.
    (let [decorator-stack (story/resolve-decorators :story.stepper/v)]
      (re-frame.story.frames/allocate! :story.stepper/v decorator-stack)
      (loaders/start-loaders! :story.stepper/v)
      (loaders/finish-loaders! :story.stepper/v)
      (play/begin-stepper! :story.stepper/v)
      (is (play/play-stepper-active? :story.stepper/v))
      (let [ev1 (play/step-once! :story.stepper/v)]
        (is (= [:step/one] ev1))
        (is (true? (-> (rf/get-frame-db :story.stepper/v) :one?))))
      (let [ev2 (play/step-once! :story.stepper/v)]
        (is (= [:step/two] ev2))
        (is (true? (-> (rf/get-frame-db :story.stepper/v) :two?))))
      ;; Stepper exhausted.
      (is (nil? (play/step-once! :story.stepper/v)))
      (play/end-stepper! :story.stepper/v)
      (is (not (play/play-stepper-active? :story.stepper/v)))
      (story/destroy-variant! :story.stepper/v))))

;; ===========================================================================
;; :loaders-complete-when non-default forms — Stage 5 (rf2-h8et)
;; ===========================================================================

(deftest loaders-complete-when-registered-event
  (testing "registered event id form — handler sets :rf.story/loaders-complete?"
    (rf/reg-event-db :my.fixture/ready?
      (fn [db _]
        ;; The predicate event sets the completion slot to a custom
        ;; condition. Here: complete iff :loaded? is true.
        (assoc db :rf.story/loaders-complete? (boolean (:loaded? db)))))
    (rf/reg-event-db :test/mark-loaded
      (fn [db _] (assoc db :loaded? true)))
    (story/reg-variant :story.loaders/registered
      {:loaders                [[:test/mark-loaded]]
       :loaders-complete-when  :my.fixture/ready?
       :events                 []})
    (let [r (async/deref-blocking (story/run-variant :story.loaders/registered) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (true? (-> r :app-db :loaded?))))
    (story/destroy-variant! :story.loaders/registered)))

(deftest loaders-complete-when-vector
  (testing "vector-of-events form — complete when ALL listed events fired"
    (rf/reg-event-db :test/load-a (fn [db _] (assoc db :a? true)))
    (rf/reg-event-db :test/load-b (fn [db _] (assoc db :b? true)))
    (story/reg-variant :story.loaders/vector
      {:loaders                [[:test/load-a] [:test/load-b]]
       :loaders-complete-when  [[:test/load-a] [:test/load-b]]
       :events                 []})
    ;; Per rf2-v2g9, the play-runner's trace listener now installs
    ;; before the loader phase, so the dispatched-events accumulator
    ;; observes loader-phase events and the vector predicate can match.
    (let [r (async/deref-blocking (story/run-variant :story.loaders/vector) 5000)]
      (is (true? (-> r :app-db :a?)))
      (is (true? (-> r :app-db :b?)))
      (is (= :ready (:lifecycle r))
          "vector form's loaders-complete-when fires once both loaders run, transitioning the lifecycle to :ready"))
    (story/destroy-variant! :story.loaders/vector)))

(deftest loaders-complete-when-vector-trace-listener-installed-pre-loaders
  ;; rf2-v2g9 — the play-runner's per-frame trace listener installs
  ;; BEFORE the loader phase so `:loaders-complete-when`'s vector form
  ;; can match against the dispatched-events accumulator. Before the
  ;; fix the listener installed at play start (after loaders ran) and
  ;; the predicate never matched — the loader phase stayed in
  ;; `:loading`. This test pins that lifecycle to `:ready` and
  ;; verifies the accumulator was populated with the loader event.
  (testing "the loaders-complete-when vector form matches loader-phase dispatches"
    (rf/reg-event-db :fixture/loaded
      (fn [db _] (assoc db :fixture-loaded? true)))
    (story/reg-variant :story.v2g9/loader-vector
      {:loaders               [[:fixture/loaded]]
       :loaders-complete-when [[:fixture/loaded]]
       :events                []})
    (let [r (async/deref-blocking
              (story/run-variant :story.v2g9/loader-vector) 5000)]
      (is (true? (-> r :app-db :fixture-loaded?))
          "the loader event ran")
      (is (= :ready (:lifecycle r))
          "the loader phase advanced to :ready — the predicate saw the loader event"))
    (story/destroy-variant! :story.v2g9/loader-vector)))

(deftest loaders-complete-when-vector-without-listener-stalls
  ;; rf2-v2g9 — negative companion to the fix. We simulate the pre-fix
  ;; behaviour by clearing the listener-fed accumulator between the
  ;; loader dispatch and the predicate evaluation, then re-running the
  ;; predicate directly. With an empty accumulator the vector form is
  ;; false — which is the bug the fix prevents in the live pipeline.
  (testing "vector form with an empty accumulator (pre-fix simulation) returns false"
    (let [frame-id :story.v2g9/stalled
          body {:loaders-complete-when [[:fixture/loaded]]}]
      (reset! assertions/dispatched-events-accumulator {})
      (is (false? (loaders/evaluate-complete-when frame-id body))
          "predicate is false when the accumulator has no record of the required event")
      ;; And once the listener-fed accumulator carries the event, the
      ;; predicate flips to true (the post-fix observable).
      (assertions/record-dispatched! frame-id [:fixture/loaded])
      (is (true? (loaders/evaluate-complete-when frame-id body))
          "predicate is true once the trace listener has fed the accumulator"))))

(deftest loaders-complete-when-evaluate-vector-form
  (testing "vector-of-events evaluation reads the assertions dispatched-events accumulator"
    ;; Pre-seed the accumulator (this is what the trace listener does
    ;; during the play sequence).
    (let [frame-id :story.predfn/vector
          variant-body {:loaders-complete-when [[:fixture/loaded] [:auth/ready]]}]
      (assertions/record-dispatched! frame-id [:fixture/loaded])
      (is (false? (loaders/evaluate-complete-when frame-id variant-body))
          "missing one of the required events — predicate is false")
      (assertions/record-dispatched! frame-id [:auth/ready])
      (is (true? (loaders/evaluate-complete-when frame-id variant-body))
          "both events observed — predicate is true")
      (reset! assertions/dispatched-events-accumulator {}))))

(deftest loaders-complete-when-fn-form
  (testing "literal fn predicate is invoked with the frame's app-db"
    (let [frame-id :story.predfn/fn
          variant-body {:loaders-complete-when (fn [db]
                                                 (boolean (:done? db)))}]
      (rf/reg-frame frame-id {})
      (try
        (is (false? (loaders/evaluate-complete-when frame-id variant-body)))
        (rf/dispatch-sync [::set-done] {:frame frame-id})
        (finally
          (rf/destroy-frame frame-id))))))

;; Helper for the fn-form test above. Registered at top-level so the
;; dispatch in the test body can find it.
(rf/reg-event-db ::set-done (fn [db _] (assoc db :done? true)))
