(ns re-frame.story-fx-stubs-test
  "JVM tests for re-frame2-story Stage 5 (rf2-h8et) —
  `:rf.story/force-fx-stub` decorator.

  Covers:

  - `:rf.story/force-fx-stub` registers at boot.
  - Ref-args expansion: `[:rf.story/force-fx-stub :http {...}]`
    materialises a per-reference body with `:fx-id` + `:response`.
  - Multiple references with distinct fx-ids each get a distinct
    stub-event-id.
  - The fx-overrides map threads onto the variant frame's config so
    re-frame's router redirects the fx.
  - `:rf.assert/effect-emitted` observes a stubbed fx.
  - Frame teardown drops the stub-event log."
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
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames     :as frames]
            [re-frame.story.fx-stubs   :as fx-stubs]
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
  (reset! frames/stub-call-log                     {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; Decorator registration
;; ===========================================================================

(deftest force-fx-stub-registered-at-boot
  (testing ":rf.story/force-fx-stub is registered as a decorator after install"
    (is (story/registered? :decorator :rf.story/force-fx-stub))
    (is (= :fx-override
           (:kind (story/handler-meta :decorator :rf.story/force-fx-stub))))
    (is (= story/force-fx-stub-id :rf.story/force-fx-stub))))

;; ===========================================================================
;; Ref-args expansion
;; ===========================================================================

(deftest force-fx-stub-ref-args-expansion
  (testing "ref-args expand into a per-reference body with fx-id + response"
    (story/reg-variant :story.fxstub/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]]
       :events     []})
    (let [r (story/resolve-decorators :story.fxstub/v)]
      (is (= 1 (count (:fx-override r))))
      (let [body (-> r :fx-override first :body)]
        (is (= :http              (:fx-id body)))
        (is (= {:status :pending} (:response body)))
        (is (= :fx-override       (:kind body)))))))

(deftest force-fx-stub-multiple-refs-distinct-fx-ids
  (testing "multiple force-fx-stub references with distinct fx-ids get distinct stub-event-ids"
    (story/reg-variant :story.fxstub-multi/v
      {:decorators [[:rf.story/force-fx-stub :http      {:status :a}]
                    [:rf.story/force-fx-stub :websocket {:status :b}]]
       :events     []})
    (let [r       (story/resolve-decorators :story.fxstub-multi/v)
          stack   (decorators/fx-overrides-map (:fx-override r))]
      (is (= 2 (count (:overrides stack))))
      (is (contains? (:overrides stack) :http))
      (is (contains? (:overrides stack) :websocket))
      (let [http-stub (get-in stack [:overrides :http])
            ws-stub   (get-in stack [:overrides :websocket])]
        (is (not= http-stub ws-stub)
            "different fx-ids yield distinct stub-event-ids")))))

;; ===========================================================================
;; expand-ref-args helper
;; ===========================================================================

(deftest expand-ref-args-helper
  (testing "expand-ref-args returns a body map for force-fx-stub refs"
    (is (= {:kind :fx-override :fx-id :http :response {:n 1}}
           (fx-stubs/expand-ref-args
             [:rf.story/force-fx-stub :http {:n 1}])))
    (is (nil? (fx-stubs/expand-ref-args [:some-other-decorator :http])))
    (is (nil? (fx-stubs/expand-ref-args nil)))
    (is (nil? (fx-stubs/expand-ref-args [])))))

;; ===========================================================================
;; The fx-overrides config threads onto the variant frame
;; ===========================================================================

(deftest force-fx-stub-installs-on-frame
  (testing "running a variant with force-fx-stub stamps :fx-overrides on the frame config"
    (story/reg-variant :story.fxstub-frame/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]]
       :events     []})
    (let [r (async/deref-blocking (story/run-variant :story.fxstub-frame/v) 5000)]
      (is (= :ready (:lifecycle r)))
      (let [overrides (:fx-overrides (rf/frame-meta :story.fxstub-frame/v))]
        (is (map? overrides))
        (is (contains? overrides :http)
            "the :http fx is redirected to the stub event")))
    (story/destroy-variant! :story.fxstub-frame/v)))

;; ===========================================================================
;; :rf.assert/effect-emitted with force-fx-stub
;; ===========================================================================

(deftest force-fx-stub-emits-fx-into-accumulator
  (testing "a stubbed fx emits into the assertion accumulator so :rf.assert/effect-emitted passes"
    (rf/reg-event-fx :do/http-call
      (fn [_ _]
        {:fx [[:http {:url "/test" :method :get}]]}))
    (story/reg-variant :story.fxemit/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :ok :body {:n 1}}]]
       :events     []
       :play       [[:do/http-call]
                    [:rf.assert/effect-emitted :http]]})
    (let [r (async/deref-blocking (story/run-variant :story.fxemit/v) 5000)
          last-a (last (:assertions r))]
      (is (true? (:passed? last-a))
          "force-fx-stub's stub event taps emitted-fx accumulator so :rf.assert/effect-emitted sees the call"))
    (story/destroy-variant! :story.fxemit/v)))

;; ===========================================================================
;; Stub event log inspection
;; ===========================================================================

(deftest force-fx-stub-log-captures-payload
  (testing "the stub fx-handler records the fx payload + response in the per-frame stub-call log"
    (rf/reg-event-fx :do/http-call2
      (fn [_ _]
        {:fx [[:http {:url "/api" :method :post}]]}))
    (story/reg-variant :story.fxlog/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :ok :body {}}]]
       :events     []
       :play       [[:do/http-call2]]})
    (async/deref-blocking (story/run-variant :story.fxlog/v) 5000)
    (let [log (re-frame.story.frames/stub-call-log-for :story.fxlog/v)]
      (is (= 1 (count log)))
      (is (= :http (:fx-id (first log)))
          "the stub log entry carries the original fx-id")
      (is (= {:url "/api" :method :post} (:payload (first log)))
          "the stub log entry carries the original fx payload"))
    ;; observed-fx-ids should also see the stub.
    (is (contains? (fx-stubs/observed-fx-ids :story.fxlog/v) :http)
        "observed-fx-ids surfaces the stubbed fx after the run")
    (story/destroy-variant! :story.fxlog/v)))
