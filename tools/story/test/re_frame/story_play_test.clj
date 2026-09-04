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
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/pending-exceptions {})
  (reset! rf.story.play/stepper-state      {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; execute-play! basic
;; ===========================================================================

(deftest execute-play-empty
  (testing "execute-play! against an empty :script resolves to []"
    (rf.story/reg-variant :story.play/empty {:setup []})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.play/empty) 5000)
    (let [p (rf.story.play/execute-play! :story.play/empty [])]
      (is (= [] (rf.story.async/deref-blocking p 5000))))
    (rf.story/destroy-variant! :story.play/empty)))

(deftest execute-play-dispatches-in-order
  (testing "play events dispatch in declared order"
    (let [order (atom [])]
      (rf/reg-event :step/a (fn [{:keys [db]} _] (swap! order conj :a) {:db db}))
      (rf/reg-event :step/b (fn [{:keys [db]} _] (swap! order conj :b) {:db db}))
      (rf/reg-event :step/c (fn [{:keys [db]} _] (swap! order conj :c) {:db db}))
      (rf.story/reg-variant :story.order/v
        {:setup []
         :script [[:dispatch-sync [:step/a]]
                  [:dispatch-sync [:step/b]]
                  [:dispatch-sync [:step/c]]]})
      (rf.story.async/deref-blocking (rf.story/run-variant :story.order/v) 5000)
      (is (= [:a :b :c] @order)))
    (rf.story/destroy-variant! :story.order/v)))

(deftest execute-play-mixes-dispatches-and-assertions
  (testing "mixed sequence of regular events + :rf.assert/* events"
    (rf/reg-event :counter/inc
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf.story/reg-variant :story.mix/v
      {:setup []
       :script [[:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 1]]
                [:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 2]]
                [:dispatch-sync [:counter/inc]]
                [:dispatch-sync [:rf.assert/path-equals [:n] 3]]]})
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.mix/v) 5000)]
      (is (= 3 (count (:assertions r))))
      (is (every? :passed? (:assertions r)))
      (is (= 3 (-> r :app-db :n))))
    (rf.story/destroy-variant! :story.mix/v)))

(deftest execute-play-exception-records-phase-4
  (testing "an exception in a play event is captured + the script keeps walking"
    (rf/reg-event :boom/now
      (fn [_ _] (throw (ex-info "boom" {:cause :test}))))
    (rf.story/reg-variant :story.boom/v
      {:setup []
       :script [[:dispatch-sync [:boom/now]]
                [:dispatch-sync [:rf.assert/path-equals [:after] :ok]]]})
    (let [result (rf.story.async/deref-blocking (rf.story/run-variant :story.boom/v) 5000)]
      ;; rf2-z2dq8 (:play -> :script): re-frame's router catches the
      ;; handler exception and emits a `:rf.error/handler-exception`
      ;; trace event; the per-frame trace listener captures it, and the
      ;; runner-events driver drains it into `:rf.story/assertions` as a
      ;; `:rf.error/exception` record. The runner sees the dispatch-sync
      ;; return cleanly and continues; both steps walk.
      (let [state (rf.story.play.runner-events/current-state :story.boom/v)]
        (is (some #(and (= :rf.error/exception (:assertion %))
                        (= :phase-4-play (:phase %))
                        (= [:boom/now] (:event %)))
                  (:assertions result))
            "an :rf.error/exception assertion was recorded for :boom/now")
        ;; The follow-on assertion step still ran — runner records both
        ;; steps' results regardless of the handler exception.
        (is (= 2 (count (:results state)))
            "both play-script steps walked even after the handler threw")))
    (rf.story/destroy-variant! :story.boom/v)))

;; ===========================================================================
;; The trace-bus accumulators clear at play start
;; ===========================================================================

(deftest accumulators-reset-per-run
  (testing "trace-bus accumulators reset at the start of each play run"
    (rf/reg-event :do/work (fn [{:keys [db]} _] {:db (assoc db :did? true)}))
    (rf.story/reg-variant :story.reset/v
      {:setup []
       :script [[:dispatch-sync [:do/work]]
                [:dispatch-sync [:rf.assert/dispatched? [:do/work]]]]})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.reset/v) 5000)
    ;; The first run's dispatched-events accumulator must NOT leak into
    ;; the second run — reset-variant tears the frame down + re-runs.
    (let [r2 (rf.story.async/deref-blocking (rf.story/reset-variant :story.reset/v) 5000)]
      (is (true? (-> r2 :assertions first :passed?))
          "second run's accumulator only sees that run's events"))
    (rf.story/destroy-variant! :story.reset/v)))

;; ===========================================================================
;; rf2-ee38b.3 — framework :db / :fx fx-ids are excluded from :emitted-fx
;; ===========================================================================

(deftest effect-emitted-db-is-not-vacuously-true
  (testing "the ubiquitous framework :db effect is NOT recorded into the
            :emitted-fx accumulator, so :rf.assert/effect-emitted :db
            FAILS rather than vacuously passing on every variant"
    ;; A plain event that returns {:db ...} — emits the framework :db fx
    ;; but no user fx-id.
    (rf/reg-event :ee/touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (rf.story/reg-variant :story.ee/db
      {:setup []
       :script [[:dispatch-sync [:ee/touch]]
                     [:dispatch-sync [:rf.assert/effect-emitted :db]]]})
    (let [result (rf.story.async/deref-blocking (rf.story/run-variant :story.ee/db) 5000)
          ee-rec (first (filter #(= :rf.assert/effect-emitted (:assertion %))
                                (:assertions result)))]
      (is (some? ee-rec) "the effect-emitted assertion recorded")
      (is (false? (:passed? ee-rec))
          ":db is excluded from :emitted-fx, so the assertion fails (was
           vacuously true before rf2-ee38b.3)"))
    (rf.story/destroy-variant! :story.ee/db)))

;; ===========================================================================
;; Frame teardown clears per-frame accumulator entries
;;
;; rf2-luzky removed the `trace-accumulators` side-table; the only per-frame
;; accumulator the teardown hook (`:drop-assertion-accumulators`) now evicts
;; is the play module's `pending-exceptions` slot.
;; ===========================================================================

(deftest teardown-clears-accumulators
  (testing "destroy-variant! clears the per-frame pending-exceptions slot"
    (rf.story/reg-variant :story.tear/v
      {:setup []
       :script [[:dispatch-sync [:rf.assert/path-equals [:x] :nope]]]})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.tear/v) 5000)
    (rf.story/destroy-variant! :story.tear/v)
    (is (not (contains? @rf.story.play/pending-exceptions :story.tear/v)))))

;; ===========================================================================
;; Play stepper
;; ===========================================================================

(deftest play-stepper-step-by-step
  (testing "begin-stepper! + step-once! drives the play one STEP at a time
            (rf2-ee38b.3: step-once! returns the executed STEP, not the
            bare event vector)"
    (rf/reg-event :step/one (fn [{:keys [db]} _] {:db (assoc db :one? true)}))
    (rf/reg-event :step/two (fn [{:keys [db]} _] {:db (assoc db :two? true)}))
    (rf.story/reg-variant :story.stepper/v
      {:setup []
       :script [[:dispatch-sync [:step/one]]
                [:dispatch-sync [:step/two]]]})
    ;; Run the variant phases 1-3; the play stepper takes over phase 4.
    (let [decorator-stack (rf.story/resolve-decorators :story.stepper/v)]
      (re-frame.story.frames/allocate! :story.stepper/v decorator-stack)
      (rf.story.loaders/start-loaders! :story.stepper/v)
      (rf.story.loaders/finish-loaders! :story.stepper/v)
      (rf.story.play/begin-stepper! :story.stepper/v)
      (is (rf.story.play/play-stepper-active? :story.stepper/v))
      (let [s1 (rf.story.play/step-once! :story.stepper/v)]
        (is (= [:dispatch-sync [:step/one]] s1))
        (is (true? (-> (rf/app-db-value :story.stepper/v) :one?))))
      (let [s2 (rf.story.play/step-once! :story.stepper/v)]
        (is (= [:dispatch-sync [:step/two]] s2))
        (is (true? (-> (rf/app-db-value :story.stepper/v) :two?))))
      ;; Stepper exhausted.
      (is (nil? (rf.story.play/step-once! :story.stepper/v)))
      (rf.story.play/end-stepper! :story.stepper/v)
      (is (not (rf.story.play/play-stepper-active? :story.stepper/v)))
      (rf.story/destroy-variant! :story.stepper/v))))

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
    (rf/reg-event :st/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf.story/reg-variant :story.stepper/full
      {:setup []
       :script {:auto-run? false
                     :script    [[:dispatch-sync [:st/set-n 5]]
                                 [:wait 0]
                                 [:assert-db [:n] 5]              ; pass
                                 [:assert-db [:n] 99]             ; fail
                                 [:assert-dom "div.x" :visible]   ; skip (no DOM)
                                 [:click "button.y"]]}})          ; fail (no DOM)
    (let [decorator-stack (rf.story/resolve-decorators :story.stepper/full)]
      (re-frame.story.frames/allocate! :story.stepper/full decorator-stack)
      (rf.story.loaders/start-loaders! :story.stepper/full)
      (rf.story.loaders/finish-loaders! :story.stepper/full)
      (rf.story.play/begin-stepper! :story.stepper/full)
      ;; The substrate seeds the FULL six-step folded script.
      (is (= 6 (count (:remaining (get @rf.story.play/stepper-state :story.stepper/full))))
          "all six steps (incl. :wait / :assert-* / :click) are queued")
      ;; Walk every step.
      (dotimes [_ 6] (rf.story.play/step-once! :story.stepper/full))
      (is (nil? (rf.story.play/step-once! :story.stepper/full)) "exhausted after six steps")
      (let [results (:results (get @rf.story.play/stepper-state :story.stepper/full))]
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
      (let [slot (rf.story/read-assertions :story.stepper/full)]
        (is (some (fn [r] (and (= :rf.assert/path-equals (:assertion r))
                               (false? (:passed? r))))
                  slot)
            "the stepped folded :assert-db failure reached the slot as the
             canonical :rf.assert/path-equals record"))
      (rf.story.play/end-stepper! :story.stepper/full)
      (rf.story/destroy-variant! :story.stepper/full))))

;; ===========================================================================
;; Stepper guards on a never-begun frame — rf2-booyu CORRECTNESS
;;
;; `stepper-rewind!` / `stepper-step-back!` used `swap! stepper-state update
;; frame-id (fn [s] (when s …))`. On a MISSING key `update` still associates
;; the fn's nil return, leaving a `{frame-id nil}` entry. Because
;; `play-stepper-active?` is a `contains?` check, that nil entry flipped the
;; stepper to "active" for a frame whose stepper was never begun — a false
;; activation the UI would render as a live stepper widget. The guard now
;; only touches an existing, non-nil slot.
;; ===========================================================================

(deftest stepper-rewind-on-never-begun-frame-does-not-activate
  (testing "stepper-rewind! against a frame with no stepper session is a
            no-op — it does NOT insert a nil entry that would flip
            play-stepper-active? to true (rf2-booyu)"
    (is (not (rf.story.play/play-stepper-active? :story.stepper/never)))
    (rf.story.play/stepper-rewind! :story.stepper/never)
    (is (not (rf.story.play/play-stepper-active? :story.stepper/never))
        "rewind on a never-begun frame leaves the stepper inactive")
    (is (not (contains? @rf.story.play/stepper-state :story.stepper/never))
        "no {frame-id nil} entry was inserted into stepper-state")))

(deftest stepper-step-back-on-never-begun-frame-does-not-activate
  (testing "stepper-step-back! against a frame with no stepper session is a
            no-op — same nil-entry pollution guard as rewind (rf2-booyu)"
    (is (not (rf.story.play/play-stepper-active? :story.stepper/never2)))
    (rf.story.play/stepper-step-back! :story.stepper/never2)
    (is (not (rf.story.play/play-stepper-active? :story.stepper/never2))
        "step-back on a never-begun frame leaves the stepper inactive")
    (is (not (contains? @rf.story.play/stepper-state :story.stepper/never2))
        "no {frame-id nil} entry was inserted into stepper-state")))

(deftest stepper-rewind-still-rewinds-an-active-session
  (testing "the guard does not break the real path — rewind on an ACTIVE
            session still resets the cursor (every step back to :remaining)"
    (rf/reg-event :rw/one (fn [{:keys [db]} _] {:db (assoc db :one? true)}))
    (rf.story/reg-variant :story.stepper/rw
      {:setup []
       :script [[:dispatch-sync [:rw/one]]
                     [:dispatch-sync [:rw/one]]]})
    (let [decorator-stack (rf.story/resolve-decorators :story.stepper/rw)]
      (re-frame.story.frames/allocate! :story.stepper/rw decorator-stack)
      (rf.story.loaders/start-loaders! :story.stepper/rw)
      (rf.story.loaders/finish-loaders! :story.stepper/rw)
      (rf.story.play/begin-stepper! :story.stepper/rw)
      (rf.story.play/step-once! :story.stepper/rw)
      (is (= 1 (count (:ran (get @rf.story.play/stepper-state :story.stepper/rw)))))
      (rf.story.play/stepper-rewind! :story.stepper/rw)
      (let [s (get @rf.story.play/stepper-state :story.stepper/rw)]
        (is (= [] (:ran s))      "rewind emptied :ran")
        (is (= 2 (count (:remaining s))) "every step back to :remaining"))
      (rf.story.play/end-stepper! :story.stepper/rw)
      (rf.story/destroy-variant! :story.stepper/rw))))

;; ===========================================================================
;; :loaders-complete-when non-default forms — Stage 5 (rf2-h8et)
;; ===========================================================================

(deftest loaders-complete-when-registered-event
  (testing "registered event id form — handler sets :rf.story/loaders-complete?"
    (rf/reg-event :my.fixture/ready?
      (fn [{:keys [db]} _]
        ;; The predicate event sets the completion slot to a custom
        ;; condition. Here: complete iff :loaded? is true.
        {:db (assoc db :rf.story/loaders-complete? (boolean (:loaded? db)))}))
    (rf/reg-event :test/mark-loaded
      (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
    (rf.story/reg-variant :story.loaders/registered
      {:loaders                [[:test/mark-loaded]]
       :loaders-complete-when  :my.fixture/ready?
       :setup                 []})
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.loaders/registered) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (true? (-> r :app-db :loaded?))))
    (rf.story/destroy-variant! :story.loaders/registered)))

(deftest loaders-complete-when-vector
  (testing "vector-of-events form — complete when ALL listed events fired"
    (rf/reg-event :test/load-a (fn [{:keys [db]} _] {:db (assoc db :a? true)}))
    (rf/reg-event :test/load-b (fn [{:keys [db]} _] {:db (assoc db :b? true)}))
    (rf.story/reg-variant :story.loaders/vector
      {:loaders                [[:test/load-a] [:test/load-b]]
       :loaders-complete-when  [[:test/load-a] [:test/load-b]]
       :setup                 []})
    ;; Per rf2-v2g9, the play-runner's trace listener now installs
    ;; before the loader phase, so the dispatched-events accumulator
    ;; observes loader-phase events and the vector predicate can match.
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.loaders/vector) 5000)]
      (is (true? (-> r :app-db :a?)))
      (is (true? (-> r :app-db :b?)))
      (is (= :ready (:lifecycle r))
          "vector form's loaders-complete-when fires once both loaders run, transitioning the lifecycle to :ready"))
    (rf.story/destroy-variant! :story.loaders/vector)))

(deftest loaders-complete-when-vector-trace-listener-installed-pre-loaders
  ;; rf2-v2g9 — the play-runner's per-frame trace listener installs
  ;; BEFORE the loader phase so `:loaders-complete-when`'s vector form
  ;; can match against the dispatched-events accumulator. Before the
  ;; fix the listener installed at play start (after loaders ran) and
  ;; the predicate never matched — the loader phase stayed in
  ;; `:loading`. This test pins that lifecycle to `:ready` and
  ;; verifies the accumulator was populated with the loader event.
  (testing "the loaders-complete-when vector form matches loader-phase dispatches"
    (rf/reg-event :fixture/loaded
      (fn [{:keys [db]} _] {:db (assoc db :fixture-loaded? true)}))
    (rf.story/reg-variant :story.v2g9/loader-vector
      {:loaders               [[:fixture/loaded]]
       :loaders-complete-when [[:fixture/loaded]]
       :setup                []})
    (let [r (rf.story.async/deref-blocking
              (rf.story/run-variant :story.v2g9/loader-vector) 5000)]
      (is (true? (-> r :app-db :fixture-loaded?))
          "the loader event ran")
      (is (= :ready (:lifecycle r))
          "the loader phase advanced to :ready — the predicate saw the loader event"))
    (rf.story/destroy-variant! :story.v2g9/loader-vector)))

(deftest loaders-complete-when-vector-without-listener-stalls
  ;; rf2-v2g9 / rf2-q651r — negative companion to the fix. The vector form
  ;; reads the epoch-tape dispatched-events projection (the SSOT since
  ;; rf2-q651r — `rf.story.assertions/dispatched-events`), NOT the retired
  ;; `trace-accumulators` atom. We drive the projection directly via
  ;; with-redefs: an empty projection (no loader epoch yet) → false; once
  ;; the tape carries the required event → true.
  (testing "vector form with an empty tape projection returns false"
    (let [frame-id :story.v2g9/stalled
          body {:loaders-complete-when [[:fixture/loaded]]}]
      (with-redefs [rf.story.assertions/dispatched-events (constantly [])]
        (is (false? (rf.story.loaders/evaluate-complete-when frame-id body))
            "predicate is false when the tape carries no record of the required event"))
      (with-redefs [rf.story.assertions/dispatched-events (constantly [[:fixture/loaded]])]
        (is (true? (rf.story.loaders/evaluate-complete-when frame-id body))
            "predicate is true once the tape projection carries the loader event")))))

(deftest loaders-complete-when-evaluate-vector-form
  (testing "vector-of-events evaluation reads the epoch-tape dispatched-events projection"
    ;; rf2-q651r — the projection is the SSOT; drive it directly.
    (let [frame-id :story.predfn/vector
          variant-body {:loaders-complete-when [[:fixture/loaded] [:auth/ready]]}]
      (with-redefs [rf.story.assertions/dispatched-events (constantly [[:fixture/loaded]])]
        (is (false? (rf.story.loaders/evaluate-complete-when frame-id variant-body))
            "missing one of the required events — predicate is false"))
      (with-redefs [rf.story.assertions/dispatched-events (constantly [[:fixture/loaded] [:auth/ready]])]
        (is (true? (rf.story.loaders/evaluate-complete-when frame-id variant-body))
            "both events observed — predicate is true")))))

(deftest loaders-complete-when-fn-form
  (testing "literal fn predicate is invoked with the frame's app-db"
    (let [frame-id :story.predfn/fn
          variant-body {:loaders-complete-when (fn [db]
                                                 (boolean (:done? db)))}]
      (rf/make-frame {:id frame-id})
      (try
        (is (false? (rf.story.loaders/evaluate-complete-when frame-id variant-body)))
        (rf/dispatch-sync [::set-done] {:frame frame-id})
        (finally
          (rf/destroy-frame! frame-id))))))

;; Helper for the fn-form test above. Registered at top-level so the
;; dispatch in the test body can find it.
(rf/reg-event ::set-done (fn [{:keys [db]} _] {:db (assoc db :done? true)}))
