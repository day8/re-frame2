(ns re-frame.story-fx-stubs-per-fx-scenarios-test
  "`:rf.story/force-fx-stub` over the spec/015 matrix's non-`:http` fx-ids,
  over a registered real handler, and with a failure-shaped response. The
  code path is the same for every fx-id; these catch a change that
  special-cases `:http`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            ;; `:rf.assert/effect-emitted` reads the epoch tape.
            [re-frame.epoch]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.fx-stubs   :as rf.story.fx-stubs]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]))

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
  (reset! rf.story.play/stepper-state            {})
  (reset! rf.story.frames/stub-call-log          {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

(defn- stub-scenario!
  "Register an event emitting `fx-id` with `payload`, run a variant stubbing
  it, and assert the redirect: frame `:fx-overrides`, one logged call with the
  original payload, `observed-fx-ids`, and every assertion passing."
  [vid fx-id payload & {:keys [response extra-script]}]
  (let [event (keyword "do" (str (name fx-id) "-emit"))]
    (rf/reg-event event (fn [_ _] {:fx [[fx-id payload]]}))
    (rf.story/reg-variant vid
      {:decorators [[:rf.story/force-fx-stub fx-id (or response {:ack? true})]]
       :setup      []
       :script     (into [[:dispatch-sync [event]]
                          [:dispatch-sync [:rf.assert/effect-emitted fx-id]]]
                         extra-script)})
    (let [r (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)]
      (is (= [:ready true [{:fx-id fx-id :payload payload}] true]
             [(:lifecycle r)
              (contains? (:fx-overrides (rf/frame-meta vid)) fx-id)
              (mapv #(select-keys % [:fx-id :payload]) (rf.story.frames/stub-call-log-for vid))
              (contains? (rf.story.fx-stubs/observed-fx-ids vid) fx-id)])
          (str fx-id))
      (is (every? :passed? (:assertions r)) (str fx-id))
      (rf.story/destroy-variant! vid)
      r)))

(deftest analytics-fx-stub-scenario
  (stub-scenario! :story.fxscen.analytics/v :analytics {:event "page-view" :path "/home"}))

(deftest websocket-fx-stub-scenario
  (stub-scenario! :story.fxscen.websocket/v :websocket {:topic "live" :payload {:tick 1}}))

(deftest navigation-fx-stub-scenario
  (stub-scenario! :story.fxscen.navigation/v :navigation {:to "/dashboard" :replace? false}))

(deftest stub-overrides-real-handler
  (testing "the stub takes precedence over a registered real fx handler"
    (let [real-called? (atom false)]
      (rf/reg-fx :http (fn [_] (reset! real-called? true)
                         (throw (ex-info "real :http fx must not run under force-fx-stub" {}))))
      (stub-scenario! :story.fxoverride/real :http {:url "/should-not-hit-real"})
      (is (false? @real-called?)))))

(deftest stub-failure-mode-records-without-crash
  (testing "a failure-shaped stub response does not crash the run; the
            variant's own failure event records the state its assertions read"
    (let [failure {:status :error :code 500 :body {:reason "server-down"}}]
      (rf/reg-event :record/failure (fn [{:keys [db]} _] {:db (assoc db :http-result failure)}))
      (is (= 2 (count (:assertions
                        (stub-scenario! :story.fxfail/v :http {:url "/api/may-fail"}
                                        :response failure
                                        :extra-script [[:dispatch-sync [:record/failure]]
                                                       [:dispatch-sync [:rf.assert/path-equals
                                                                        [:http-result] failure]]]))))))))
