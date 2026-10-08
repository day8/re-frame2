(ns re-frame.story-play-test
  "Play-script execution through run-variant, the play stepper, and the
  `:loaders-complete-when` forms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            ;; The tape-projected assertions and the vector
            ;; `:loaders-complete-when` read the epoch tape.
            [re-frame.epoch]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.frames     :as rf.story.frames]
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

(defn- run-v! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(defn- begin-stepper-on! [vid]
  (rf.story.frames/allocate! vid (rf.story/resolve-decorators vid))
  (rf.story.loaders/start-loaders! vid)
  (rf.story.loaders/finish-loaders! vid)
  (rf.story.play/begin-stepper! vid))

(deftest play-script-mixes-dispatches-and-assertions
  (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf.story/reg-variant :story.mix/v
    {:setup  []
     :script (vec (mapcat (fn [n] [[:dispatch-sync [:counter/inc]]
                                   [:dispatch-sync [:rf.assert/path-equals [:n] n]]])
                          [1 2 3]))})
  (let [r (run-v! :story.mix/v)]
    (is (= [[true true true] 3] [(mapv :passed? (:assertions r)) (-> r :app-db :n)])))
  (rf.story/destroy-variant! :story.mix/v))

(deftest play-script-exception-records-phase-4
  (testing "a throwing play event is recorded at :phase-4-play and the script
            keeps walking"
    (rf/reg-event :boom/now (fn [_ _] (throw (ex-info "boom" {:cause :test}))))
    (rf.story/reg-variant :story.boom/v
      {:setup  []
       :script [[:dispatch-sync [:boom/now]]
                [:dispatch-sync [:rf.assert/path-equals [:after] :ok]]]})
    (let [result (run-v! :story.boom/v)]
      (is (some #(= [:rf.error/exception :phase-4-play [:boom/now]]
                    ((juxt :assertion :phase :event) %))
                (:assertions result)))
      (is (= 2 (count (:results (rf.story.play.runner-events/current-state :story.boom/v))))
          "both steps walked"))
    (rf.story/destroy-variant! :story.boom/v)))

(deftest effect-emitted-db-is-not-vacuously-true
  (testing "the framework :db effect is excluded from emitted-fx, so
            effect-emitted :db fails rather than passing on every variant"
    (rf/reg-event :ee/touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (rf.story/reg-variant :story.ee/db
      {:setup  []
       :script [[:dispatch-sync [:ee/touch]]
                [:dispatch-sync [:rf.assert/effect-emitted :db]]]})
    (is (= [false] (->> (:assertions (run-v! :story.ee/db))
                        (filter #(= :rf.assert/effect-emitted (:assertion %)))
                        (mapv :passed?))))
    (rf.story/destroy-variant! :story.ee/db)))

(deftest teardown-clears-accumulators
  (rf.story/reg-variant :story.tear/v
    {:setup [] :script [[:dispatch-sync [:rf.assert/path-equals [:x] :nope]]]})
  (run-v! :story.tear/v)
  (rf.story/destroy-variant! :story.tear/v)
  (is (not (contains? @rf.story.play/pending-exceptions :story.tear/v))))

(deftest play-stepper-step-by-step
  (testing "step-once! runs one step and returns it, nil once exhausted"
    (rf/reg-event :step/one (fn [{:keys [db]} _] {:db (assoc db :one? true)}))
    (rf/reg-event :step/two (fn [{:keys [db]} _] {:db (assoc db :two? true)}))
    (rf.story/reg-variant :story.stepper/v
      {:setup [] :script [[:dispatch-sync [:step/one]] [:dispatch-sync [:step/two]]]})
    (begin-stepper-on! :story.stepper/v)
    (is (rf.story.play/play-stepper-active? :story.stepper/v))
    (is (= [[:dispatch-sync [:step/one]] {:one? true}]
           [(rf.story.play/step-once! :story.stepper/v)
            (select-keys (rf/app-db-value :story.stepper/v) [:one? :two?])]))
    (is (= [[:dispatch-sync [:step/two]] {:one? true :two? true}]
           [(rf.story.play/step-once! :story.stepper/v)
            (select-keys (rf/app-db-value :story.stepper/v) [:one? :two?])]))
    (is (nil? (rf.story.play/step-once! :story.stepper/v)))
    (rf.story.play/end-stepper! :story.stepper/v)
    (is (not (rf.story.play/play-stepper-active? :story.stepper/v)))
    (rf.story/destroy-variant! :story.stepper/v)))

(deftest play-stepper-walks-every-step-type
  (testing "the stepper walks every step type over the folded plan: a shipping
            :assert-db / :assert-dom is an :assert checkpoint recorded as the
            canonical :rf.assert/path-equals"
    (rf/reg-event :st/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf.story/reg-variant :story.stepper/full
      {:setup  []
       :script {:auto-run? false
                :script    [[:dispatch-sync [:st/set-n 5]]
                            [:wait 0]
                            [:assert-db [:n] 5]
                            [:assert-db [:n] 99]
                            [:assert-dom "div.x" :visible]
                            [:click "button.y"]]}})
    (begin-stepper-on! :story.stepper/full)
    (is (= 6 (count (:remaining (get @rf.story.play/stepper-state :story.stepper/full)))))
    (dotimes [_ 6] (rf.story.play/step-once! :story.stepper/full))
    (is (nil? (rf.story.play/step-once! :story.stepper/full)))
    (let [results (:results (get @rf.story.play/stepper-state :story.stepper/full))]
      (is (= [:dispatch-sync :wait :assert :assert] (mapv :type (take 4 results))))
      (is (= [6 true false true]
             [(count results) (:passed? (nth results 2)) (:passed? (nth results 3))
              (boolean (:skipped? (nth results 4)))])))
    (is (some #(= [:rf.assert/path-equals false] ((juxt :assertion :passed?) %))
              (rf.story/read-assertions :story.stepper/full)))
    (rf.story.play/end-stepper! :story.stepper/full)
    (rf.story/destroy-variant! :story.stepper/full)))

;; `play-stepper-active?` is a `contains?` check, so a rewind or step-back
;; that `update`d a missing key would insert `{frame-id nil}` and show a live
;; stepper for a frame that never began one.

(deftest stepper-guards-on-never-begun-frame-do-not-activate
  (doseq [[step! frame-id] [[rf.story.play/stepper-rewind!    :story.stepper/never]
                            [rf.story.play/stepper-step-back! :story.stepper/never2]]]
    (step! frame-id)
    (is (not (contains? @rf.story.play/stepper-state frame-id)) (str frame-id))))

(deftest stepper-rewind-still-rewinds-an-active-session
  (rf/reg-event :rw/one (fn [{:keys [db]} _] {:db (assoc db :one? true)}))
  (rf.story/reg-variant :story.stepper/rw
    {:setup [] :script [[:dispatch-sync [:rw/one]] [:dispatch-sync [:rw/one]]]})
  (begin-stepper-on! :story.stepper/rw)
  (rf.story.play/step-once! :story.stepper/rw)
  (is (= 1 (count (:ran (get @rf.story.play/stepper-state :story.stepper/rw)))))
  (rf.story.play/stepper-rewind! :story.stepper/rw)
  (let [s (get @rf.story.play/stepper-state :story.stepper/rw)]
    (is (= [[] 2] [(:ran s) (count (:remaining s))])))
  (rf.story.play/end-stepper! :story.stepper/rw)
  (rf.story/destroy-variant! :story.stepper/rw))

;; ---- :loaders-complete-when non-default forms ----------------------------

(deftest loaders-complete-when-registered-event
  (testing "a registered predicate event sets :rf.story/loaders-complete?"
    (rf/reg-event :my.fixture/ready?
      (fn [{:keys [db]} _] {:db (assoc db :rf.story/loaders-complete? (boolean (:loaded? db)))}))
    (rf/reg-event :test/mark-loaded (fn [{:keys [db]} _] {:db (assoc db :loaded? true)}))
    (rf.story/reg-variant :story.loaders/registered
      {:loaders [[:test/mark-loaded]] :loaders-complete-when :my.fixture/ready? :setup []})
    (is (= :ready (:lifecycle (run-v! :story.loaders/registered))))
    (rf.story/destroy-variant! :story.loaders/registered)))

(deftest loaders-complete-when-vector
  (testing "the vector form completes once every listed event is on the tape"
    (rf/reg-event :test/load-a (fn [{:keys [db]} _] {:db (assoc db :a? true)}))
    (rf/reg-event :test/load-b (fn [{:keys [db]} _] {:db (assoc db :b? true)}))
    (rf.story/reg-variant :story.loaders/vector
      {:loaders               [[:test/load-a] [:test/load-b]]
       :loaders-complete-when [[:test/load-a] [:test/load-b]]
       :setup                 []})
    (is (= :ready (:lifecycle (run-v! :story.loaders/vector))))
    (rf.story/destroy-variant! :story.loaders/vector)))

(deftest loaders-complete-when-vector-form-needs-every-listed-event
  (doseq [[required tape expected] [[[[:fixture/loaded]] [] false]
                                    [[[:fixture/loaded] [:auth/ready]] [[:fixture/loaded]] false]
                                    [[[:fixture/loaded] [:auth/ready]]
                                     [[:fixture/loaded] [:auth/ready]] true]]]
    (with-redefs [rf.story.assertions/dispatched-events (constantly tape)]
      (is (= expected (rf.story.loaders/evaluate-complete-when
                        :story.predfn/vector {:loaders-complete-when required}))
          (pr-str required tape)))))

(deftest loaders-complete-when-fn-form
  (testing "a literal fn predicate reads the frame's app-db"
    (let [frame-id     :story.predfn/fn
          variant-body {:loaders-complete-when (fn [db] (boolean (:done? db)))}]
      (rf/reg-event ::set-done (fn [{:keys [db]} _] {:db (assoc db :done? true)}))
      (rf/make-frame {:id frame-id})
      (try
        (is (false? (rf.story.loaders/evaluate-complete-when frame-id variant-body)))
        (rf/dispatch-sync [::set-done] {:frame frame-id})
        (is (true? (rf.story.loaders/evaluate-complete-when frame-id variant-body)))
        (finally
          (rf/destroy-frame! frame-id))))))
