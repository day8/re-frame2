(ns re-frame.story-fx-stubs-test
  "The `:rf.story/force-fx-stub` decorator: ref-args expansion, the per-frame
  stub-call log, and `:rf.assert/effect-emitted` over a stubbed fx."
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
            [re-frame.story.decorators :as rf.story.decorators]
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

(defn- run-v! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(defn- reg-http-variant! [vid event url script-tail]
  (rf/reg-event event (fn [_ _] {:fx [[:http {:url url}]]}))
  (rf.story/reg-variant vid
    {:decorators [[:rf.story/force-fx-stub :http {:status :ok}]]
     :setup      []
     :script     (into [[:dispatch-sync [event]]] script-tail)}))

(deftest force-fx-stub-ref-args-expansion
  (rf.story/reg-variant :story.fxstub/v
    {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]] :setup []})
  (is (= [{:fx-id :http :response {:status :pending} :kind :fx-override}]
         (mapv #(select-keys (:body %) [:fx-id :response :kind])
               (:fx-override (rf.story/resolve-decorators :story.fxstub/v))))))

(deftest force-fx-stub-multiple-refs-distinct-fx-ids
  (rf.story/reg-variant :story.fxstub-multi/v
    {:decorators [[:rf.story/force-fx-stub :http      {:status :a}]
                  [:rf.story/force-fx-stub :websocket {:status :b}]]
     :setup      []})
  (let [ov (:overrides (rf.story.decorators/fx-overrides-map
                         (:fx-override (rf.story/resolve-decorators :story.fxstub-multi/v))))]
    (is (= #{:http :websocket} (set (keys ov))))
    (is (not= (:http ov) (:websocket ov)))))

(deftest force-fx-stub-emits-fx-into-accumulator
  (reg-http-variant! :story.fxemit/v :do/http-call "/test"
                     [[:dispatch-sync [:rf.assert/effect-emitted :http]]])
  (is (true? (:passed? (last (:assertions (run-v! :story.fxemit/v))))))
  (rf.story/destroy-variant! :story.fxemit/v))

(deftest force-fx-stub-log-captures-payload
  (reg-http-variant! :story.fxlog/v :do/http-call2 "/api" [])
  (run-v! :story.fxlog/v)
  (is (= [{:fx-id :http :payload {:url "/api"}}]
         (mapv #(select-keys % [:fx-id :payload]) (rf.story.frames/stub-call-log-for :story.fxlog/v))))
  (is (= #{:http} (rf.story.fx-stubs/observed-fx-ids :story.fxlog/v)))
  (rf.story/destroy-variant! :story.fxlog/v))

(deftest force-fx-stub-log-is-per-frame
  (testing "two variants emitting the same fx id keep stub logs and effect
            assertions isolated by frame"
    (doseq [[vid event url] [[:story.fxisolation/a :do/http-a "/a"]
                             [:story.fxisolation/b :do/http-b "/b"]]]
      (reg-http-variant! vid event url [[:dispatch-sync [:rf.assert/effect-emitted :http]]]))
    (doseq [[vid url] [[:story.fxisolation/a "/a"] [:story.fxisolation/b "/b"]]]
      (is (every? :passed? (:assertions (run-v! vid))))
      (is (= [[{:url url}] #{:http}]
             [(mapv :payload (rf.story.frames/stub-call-log-for vid))
              (rf.story.fx-stubs/observed-fx-ids vid)])))
    (rf.story/destroy-variant! :story.fxisolation/a)
    (rf.story/destroy-variant! :story.fxisolation/b)))

(deftest destroy-variant-drops-stub-call-log
  (reg-http-variant! :story.fxdrop/v :do/http-drop "/drop" [])
  (run-v! :story.fxdrop/v)
  (is (= 1 (count (rf.story.frames/stub-call-log-for :story.fxdrop/v))) "precondition")
  (rf.story/destroy-variant! :story.fxdrop/v)
  (is (empty? (rf.story.frames/stub-call-log-for :story.fxdrop/v))))
