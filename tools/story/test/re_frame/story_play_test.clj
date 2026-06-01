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
            [re-frame.story.play       :as play]
            [re-frame.story.play.runner-events :as runner-events]))

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
  (reset! play/pending-exceptions {})
  (reset! play/stepper-state      {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; execute-play! basic
;; ===========================================================================

(deftest execute-play-empty
  (testing "execute-play! against an empty :play-script resolves to []"
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
         :play-script [[:dispatch-sync [:step/a]]
                  [:dispatch-sync [:step/b]]
                  [:dispatch-sync [:step/c]]]})
      (async/deref-blocking (story/run-variant :story.order/v) 5000)
      (is (= [:a :b :c] @order)))
    (story/destroy-variant! :story.order/v)))

(deftest execute-play-mixes-dispatches-and-assertions
  (testing "mixed sequence of regular events + :rf.assert/* events"
    (rf/reg-event-db :counter/inc
      (fn [db _] (update db :n (fnil inc 0))))
    (story/reg-variant :story.mix/v
      {:events []
       :play-script [[:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 1]]
                [:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 2]]
                [:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 3]]]})
    (let [r (async/deref-blocking (story/run-variant :story.mix/v) 5000)]
      (is (= 3 (count (:assertions r))))
      (is (every? :passed? (:assertions r)))
      (is (= 3 (-> r :app-db :n))))
    (story/destroy-variant! :story.mix/v)))

(deftest execute-play-exception-records-phase-4
  (testing "an exception in a play event is captured + the script keeps walking"
    (rf/reg-event-db :boom/now
      (fn [_ _] (throw (ex-info "boom" {:cause :test}))))
    (story/reg-variant :story.boom/v
      {:events []
       :play-script [[:dispatch-sync [:boom/now]]
                [:dispatch-sync [:rf.assert/path-equals [:after] :ok]]]})
    (let [result (async/deref-blocking (story/run-variant :story.boom/v) 5000)]
      ;; rf2-z2dq8 (:play -> :play-script): re-frame's router catches the
      ;; handler exception and emits a `:rf.error/handler-exception`
      ;; trace event; the per-frame trace listener captures it, and the
      ;; runner-events driver drains it into `:rf.story/assertions` as a
      ;; `:rf.error/exception` record. The runner sees the dispatch-sync
      ;; return cleanly and continues; both steps walk.
      (let [state (runner-events/current-state :story.boom/v)]
        (is (some #(and (= :rf.error/exception (:assertion %))
                        (= :phase-4-play (:phase %))
                        (= [:boom/now] (:event %)))
                  (:assertions result))
            "an :rf.error/exception assertion was recorded for :boom/now")
        ;; The follow-on assertion step still ran — runner records both
        ;; steps' results regardless of the handler exception.
        (is (= 2 (count (:results state)))
            "both play-script steps walked even after the handler threw")))
    (story/destroy-variant! :story.boom/v)))

;; ===========================================================================
;; The trace-bus accumulators clear at play start
;; ===========================================================================

(deftest accumulators-reset-per-run
  (testing "trace-bus accumulators reset at the start of each play run"
    (rf/reg-event-db :do/work (fn [db _] (assoc db :did? true)))
    (story/reg-variant :story.reset/v
      {:events []
       :play-script [[:dispatch-sync [:do/work]]
                [:dispatch-sync [:rf.assert/dispatched? [:do/work]]]]})
    (async/deref-blocking (story/run-variant :story.reset/v) 5000)
    ;; The first run's dispatched-events accumulator must NOT leak into
    ;; the second run — reset-variant tears the frame down + re-runs.
    (let [r2 (async/deref-blocking (story/reset-variant :story.reset/v) 5000)]
      (is (true? (-> r2 :assertions first :passed?))
          "second run's accumulator only sees that run's events"))
    (story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; rf2-ee38b.3 — framework :db / :fx fx-ids are excluded from :emitted-fx
;; ===========================================================================

(deftest effect-emitted-db-is-not-vacuously-true
  (testing "the ubiquitous framework :db effect is NOT recorded into the
            :emitted-fx accumulator, so :rf.assert/effect-emitted :db
            FAILS rather than vacuously passing on every variant"
    ;; A plain event that returns {:db ...} — emits the framework :db fx
    ;; but no user fx-id.
    (rf/reg-event-db :ee/touch (fn [db _] (assoc db :touched? true)))
    (story/reg-variant :story.ee/db
      {:events []
       :play-script [[:dispatch-sync [:ee/touch]]
                     [:dispatch-sync [:rf.assert/effect-emitted :db]]]})
    (let [result (async/deref-blocking (story/run-variant :story.ee/db) 5000)
          ee-rec (first (filter #(= :rf.assert/effect-emitted (:assertion %))
                                (:assertions result)))]
      (is (some? ee-rec) "the effect-emitted assertion recorded")
      (is (false? (:passed? ee-rec))
          ":db is excluded from :emitted-fx, so the assertion fails (was
           vacuously true before rf2-ee38b.3)"))
    (story/destroy-variant! :story.ee/db)))

;; ===========================================================================
;; Frame teardown clears per-frame accumulator entries
;;
;; rf2-luzky removed the `trace-accumulators` side-table; the only per-frame
;; accumulator the teardown hook (`:drop-assertion-accumulators`) now evicts
;; is the play module's `pending-exceptions` slot.
;; ===========================================================================

(deftest teardown-clears-accumulators
  (testing "destroy-variant! clears the per-frame pending-exceptions slot"
    (story/reg-variant :story.tear/v
      {:events []
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:x] :nope]]]})
    (async/deref-blocking (story/run-variant :story.tear/v) 5000)
    (story/destroy-variant! :story.tear/v)
    (is (not (contains? @play/pending-exceptions :story.tear/v)))))

;; ===========================================================================
;; Play stepper
;; ===========================================================================

(deftest play-stepper-step-by-step
  (testing "begin-stepper! + step-once! drives the play one STEP at a time
            (rf2-ee38b.3: step-once! returns the executed STEP, not the
            bare event vector)"
    (rf/reg-event-db :step/one (fn [db _] (assoc db :one? true)))
    (rf/reg-event-db :step/two (fn [db _] (assoc db :two? true)))
    (story/reg-variant :story.stepper/v
      {:events []
       :play-script [[:dispatch-sync [:step/one]]
                [:dispatch-sync [:step/two]]]})
    ;; Run the variant phases 1-3; the play stepper takes over phase 4.
    (let [decorator-stack (story/resolve-decorators :story.stepper/v)]
      (re-frame.story.frames/allocate! :story.stepper/v decorator-stack)
      (loaders/start-loaders! :story.stepper/v)
      (loaders/finish-loaders! :story.stepper/v)
      (play/begin-stepper! :story.stepper/v)
      (is (play/play-stepper-active? :story.stepper/v))
      (let [s1 (play/step-once! :story.stepper/v)]
        (is (= [:dispatch-sync [:step/one]] s1))
        (is (true? (-> (rf/app-db-value :story.stepper/v) :one?))))
      (let [s2 (play/step-once! :story.stepper/v)]
        (is (= [:dispatch-sync [:step/two]] s2))
        (is (true? (-> (rf/app-db-value :story.stepper/v) :two?))))
      ;; Stepper exhausted.
      (is (nil? (play/step-once! :story.stepper/v)))
      (play/end-stepper! :story.stepper/v)
      (is (not (play/play-stepper-active? :story.stepper/v)))
      (story/destroy-variant! :story.stepper/v))))

(deftest play-stepper-walks-every-step-type
  (testing "rf2-ee38b.3 / rf2-5x1wt.19: the step-debugger walks ALL step
            types — :wait, :assert-db, :assert-dom, :click, :type — not
            just the dispatch steps the legacy variant-play-events
            projection surfaced. The cursor/total count every step and
            assert outcomes surface. Per rf2-5x1wt.19 the stepper consumes
            the FOLDED plan: a shipping :assert-db / :assert-dom step is
            rewritten to the canonical [:assert assertion-atom] checkpoint,
            so the recorded step type is :assert and the slot record carries
            the canonical :rf.assert/path-equals (no synthetic :rf.assert/db)."
    (rf/reg-event-db :st/set-n (fn [db [_ v]] (assoc db :n v)))
    (story/reg-variant :story.stepper/full
      {:events []
       :play-script {:auto-run? false
                     :script    [[:dispatch-sync [:st/set-n 5]]
                                 [:wait 0]
                                 [:assert-db [:n] 5]              ; pass
                                 [:assert-db [:n] 99]             ; fail
                                 [:assert-dom "div.x" :visible]   ; skip (no DOM)
                                 [:click "button.y"]]}})          ; fail (no DOM)
    (let [decorator-stack (story/resolve-decorators :story.stepper/full)]
      (re-frame.story.frames/allocate! :story.stepper/full decorator-stack)
      (loaders/start-loaders! :story.stepper/full)
      (loaders/finish-loaders! :story.stepper/full)
      (play/begin-stepper! :story.stepper/full)
      ;; The substrate seeds the FULL six-step folded script.
      (is (= 6 (count (:remaining (get @play/stepper-state :story.stepper/full))))
          "all six steps (incl. :wait / :assert-* / :click) are queued")
      ;; Walk every step.
      (dotimes [_ 6] (play/step-once! :story.stepper/full))
      (is (nil? (play/step-once! :story.stepper/full)) "exhausted after six steps")
      (let [results (:results (get @play/stepper-state :story.stepper/full))]
        (is (= 6 (count results)) "one result recorded per step")
        (is (= :dispatch-sync (:type (nth results 0))))
        (is (= :wait          (:type (nth results 1))))
        ;; rf2-5x1wt.19 — folded :assert-db / :assert-dom steps are :assert
        ;; checkpoints now.
        (is (= :assert (:type (nth results 2))))
        (is (true?  (:passed? (nth results 2))) ":assert-db [:n] 5 passes")
        (is (= :assert (:type (nth results 3))))
        (is (false? (:passed? (nth results 3))) ":assert-db [:n] 99 fails")
        (is (:skipped? (nth results 4)) ":assert-dom records :skipped? (no DOM)"))
      ;; The failing :assert-db landed in the :rf.story/assertions slot as
      ;; the CANONICAL :rf.assert/path-equals record (rf2-5x1wt.19).
      (let [slot (story/read-assertions :story.stepper/full)]
        (is (some (fn [r] (and (= :rf.assert/path-equals (:assertion r))
                               (false? (:passed? r))))
                  slot)
            "the stepped folded :assert-db failure reached the slot as the
             canonical :rf.assert/path-equals record"))
      (play/end-stepper! :story.stepper/full)
      (story/destroy-variant! :story.stepper/full))))

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
  ;; rf2-v2g9 / rf2-q651r — negative companion to the fix. The vector form
  ;; reads the epoch-tape dispatched-events projection (the SSOT since
  ;; rf2-q651r — `assertions/dispatched-events`), NOT the retired
  ;; `trace-accumulators` atom. We drive the projection directly via
  ;; with-redefs: an empty projection (no loader epoch yet) → false; once
  ;; the tape carries the required event → true.
  (testing "vector form with an empty tape projection returns false"
    (let [frame-id :story.v2g9/stalled
          body {:loaders-complete-when [[:fixture/loaded]]}]
      (with-redefs [assertions/dispatched-events (constantly [])]
        (is (false? (loaders/evaluate-complete-when frame-id body))
            "predicate is false when the tape carries no record of the required event"))
      (with-redefs [assertions/dispatched-events (constantly [[:fixture/loaded]])]
        (is (true? (loaders/evaluate-complete-when frame-id body))
            "predicate is true once the tape projection carries the loader event")))))

(deftest loaders-complete-when-evaluate-vector-form
  (testing "vector-of-events evaluation reads the epoch-tape dispatched-events projection"
    ;; rf2-q651r — the projection is the SSOT; drive it directly.
    (let [frame-id :story.predfn/vector
          variant-body {:loaders-complete-when [[:fixture/loaded] [:auth/ready]]}]
      (with-redefs [assertions/dispatched-events (constantly [[:fixture/loaded]])]
        (is (false? (loaders/evaluate-complete-when frame-id variant-body))
            "missing one of the required events — predicate is false"))
      (with-redefs [assertions/dispatched-events (constantly [[:fixture/loaded] [:auth/ready]])]
        (is (true? (loaders/evaluate-complete-when frame-id variant-body))
            "both events observed — predicate is true")))))

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
          (rf/destroy-frame! frame-id))))))

;; Helper for the fn-form test above. Registered at top-level so the
;; dispatch in the test body can find it.
(rf/reg-event-db ::set-done (fn [db _] (assoc db :done? true)))
