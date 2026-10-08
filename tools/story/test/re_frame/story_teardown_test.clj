(ns re-frame.story-teardown-test
  "`:frame-setup` decorators' `:teardown` slot (001-Authoring §`:teardown`,
  002-Runtime §Loader teardown contract) and what `destroy-variant!` evicts."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.schemas    :as rf.story.schemas]
            [re-frame.trace.tooling    :as rf.trace.tooling]
            [malli.core                :as m]))

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
  (reset! rf.story.frames/stub-call-log {})
  (reset! rf.story.frames/allocated-decorator-stacks {})
  (rf.story.play.runner-events/clear-all-runs!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

(defn- run-and-destroy! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000)
  (rf.story/destroy-variant! vid))

(defn- recording-event! [id fired tag]
  (rf/reg-event id (fn [{:keys [db]} _] (swap! fired conj tag) {:db db})))

(deftest schema-accepts-frame-setup-with-only-teardown
  (is (m/validate rf.story.schemas/Decorator {:kind :frame-setup :teardown [[:noop]]})))

(deftest schema-rejects-non-vector-teardown
  (is (not (m/validate rf.story.schemas/Decorator
                       {:kind :frame-setup :teardown [:not-a-vector-of-vectors]}))))

(deftest teardown-events-fire-in-declared-order-within-a-decorator
  (let [fired (atom [])]
    (doseq [[id tag] [[:step/one :one] [:step/two :two] [:step/three :three]]]
      (recording-event! id fired tag))
    (rf/reg-event :step/noop (fn [{:keys [db]} _] {:db db}))
    (rf.story/reg-decorator :multi-step-teardown
      {:kind     :frame-setup
       :init     [[:step/noop]]
       :teardown [[:step/one] [:step/two] [:step/three]]})
    (rf.story/reg-variant :story.feed/multi-step {:decorators [[:multi-step-teardown]] :setup []})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.feed/multi-step) 5000)
    (is (= [] @fired) "nothing fires while the variant is live")
    (rf.story/destroy-variant! :story.feed/multi-step)
    (is (= [:one :two :three] @fired))))

(deftest teardown-composes-in-reverse-declaration-order
  (testing "variant-level decorators tear down before the story-level one,
            later-declared first, mirroring function-scope cleanup"
    (let [fired (atom [])]
      (doseq [[dec tag] [[:outer-dec :outer] [:dec-a :a] [:dec-b :b]]
              :let [init    (keyword (name dec) "noop")
                    cleanup (keyword (name dec) "cleanup")]]
        (rf/reg-event init (fn [{:keys [db]} _] {:db db}))
        (recording-event! cleanup fired tag)
        (rf.story/reg-decorator dec {:kind :frame-setup :init [[init]] :teardown [[cleanup]]}))
      (rf.story/reg-story :story.teardown.order {:decorators [[:outer-dec]]})
      (rf.story/reg-variant :story.teardown.order/v {:decorators [[:dec-a] [:dec-b]] :setup []})
      (run-and-destroy! :story.teardown.order/v)
      (is (= [:b :a :outer] @fired)))))

;; Teardown walks the stack captured at allocate time, so a hot-reload that
;; swaps the variant's decorators still closes what the old :init opened.

(deftest teardown-uses-allocate-time-decorator-stack-after-hot-reload
  (let [fired (atom [])]
    (rf/reg-event :d1/init (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :d2/init (fn [{:keys [db]} _] {:db db}))
    (recording-event! :d1/cleanup fired :d1)
    (recording-event! :d2/cleanup fired :d2)
    (rf.story/reg-decorator :hot/d1 {:kind :frame-setup :init [[:d1/init]] :teardown [[:d1/cleanup]]})
    (rf.story/reg-decorator :hot/d2 {:kind :frame-setup :init [[:d2/init]] :teardown [[:d2/cleanup]]})
    (rf.story/reg-variant :story.hotdec/v {:decorators [[:hot/d1]] :setup []})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.hotdec/v) 5000)
    (rf.story/reg-variant :story.hotdec/v {:decorators [[:hot/d2]] :setup []})
    (is (= [:hot/d2] (mapv :id (:frame-setup (rf.story/resolve-decorators :story.hotdec/v))))
        "precondition: the current resolution is D2")
    (rf.story/destroy-variant! :story.hotdec/v)
    (is (= [:d1] @fired))))

(deftest throwing-teardown-records-exception-assertion
  (testing "a throwing teardown event is caught, recorded as an
            :rf.error/exception at :phase-teardown, and the frame still goes.
            A story-level probe tears down last and copies the records out."
    (let [captured (atom nil)]
      (rf/reg-event :boom/cleanup (fn [_ _] (throw (ex-info "teardown boom" {:why :test}))))
      (rf/reg-event :boom/noop (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event ::probe-snapshot
        (fn [{:keys [db]} _] (reset! captured (:rf.story/assertions db)) {:db db}))
      (rf.story/reg-decorator :boom-dec
        {:kind :frame-setup :init [[:boom/noop]] :teardown [[:boom/cleanup]]})
      (rf.story/reg-decorator :probe-dec
        {:kind :frame-setup :init [[:boom/noop]] :teardown [[::probe-snapshot]]})
      (rf.story/reg-story :story.teardown.record {:decorators [[:probe-dec]]})
      (rf.story/reg-variant :story.teardown.record/v {:decorators [[:boom-dec]] :setup []})
      (is (nil? (run-and-destroy! :story.teardown.record/v)))
      (is (not (contains? (rf.story/variant-frames) :story.teardown.record/v)))
      (let [err (first (filter #(= :rf.error/exception (:assertion %)) @captured))]
        (is (= {:phase :phase-teardown :passed? false :event [:boom/cleanup]
                :variant-id :story.teardown.record/v}
               (select-keys err [:phase :passed? :event :variant-id])))
        (is (= ["teardown boom" {:why :test}] ((juxt :message :data) (:error err))))))))

;; Destroy evicts a frame's play-runner run-state through the
;; `:drop-run-state` hook, or it would accumulate over a long session and a
;; re-allocated frame could read its predecessor's terminal play status.

(deftest destroy-variant-evicts-play-runner-run-state
  (rf/reg-event :rs/noop (fn [{:keys [db]} _] {:db db}))
  (rf.story/reg-variant :story.runstate/v {:setup [] :script [[:dispatch-sync [:rs/noop]]]})
  (let [vid     :story.runstate/v
        present (fn [] [(contains? @rf.story.play.runner-events/run-state vid)
                        (contains? @rf.story.play.runner-events/runs-by-play [vid nil])
                        (contains? @rf.story.play.runner-events/active-play vid)])]
    (rf.story.frames/allocate! vid (rf.story/resolve-decorators vid))
    (rf.story.loaders/start-loaders! vid)
    (rf.story.loaders/finish-loaders! vid)
    (let [done (promise)]
      (rf.story.play.runner-events/run! vid (fn [_] (deliver done :ok)))
      (deref done 5000 :timeout))
    (is (= [true true true] (present)))
    (rf.story/destroy-variant! vid)
    (is (= [false false false] (present)))
    (is (nil? (rf.story.play.runner-events/settle-boundaries vid nil)))))

(defn- play-listener-id? [id]
  ;; `play/install-trace-listener!` keys its listener `:re-frame.story.play/trace-<frame-id>`.
  (and (keyword? id)
       (= "re-frame.story.play" (namespace id))
       (clojure.string/starts-with? (name id) "trace-")))

(deftest reset-run-variant-does-not-accumulate-listeners
  (testing "two runs of one variant leave one play trace listener live, and
            destroy clears it"
    (let [live (atom #{})]
      (with-redefs [rf.trace.tooling/register-listener!
                    (fn [id f]
                      (swap! live conj id)
                      (swap! @#'rf.trace.tooling/listeners assoc id f)
                      id)
                    rf.trace.tooling/unregister-listener!
                    (fn [id]
                      (swap! live disj id)
                      (swap! @#'rf.trace.tooling/listeners dissoc id)
                      nil)]
        (rf/reg-event :lst/noop2 (fn [{:keys [db]} _] {:db db}))
        (rf.story/reg-variant :story.listener2/v {:setup [[:lst/noop2]]})
        (dotimes [_ 2]
          (rf.story.async/deref-blocking (rf.story/run-variant :story.listener2/v) 5000))
        (is (= 1 (count (filter play-listener-id? @live))))
        (rf.story/destroy-variant! :story.listener2/v)
        (is (not-any? play-listener-id? @live))))))
