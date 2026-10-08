(ns re-frame.story-loaders-teardown-cljs-test
  "A variant body's `:loaders-teardown`: events dispatch-synced into the
  frame on `destroy-variant!` to close what `:loaders` opened
  (002-Runtime §Loader teardown contract)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.schemas    :as rf.story.schemas]
            [re-frame.subs             :as rf.subs]
            [malli.core                :as m]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  ;; Re-register the machines `:rf/machine` runtime-db sub the clear dropped.
  (rf.subs/reg-runtime-sub :rf/machine
    (fn [runtime-db [_ machine-id]]
      (get-in runtime-db [:rf.runtime/machines :snapshots machine-id])))
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (reset! rf.story.frames/stub-call-log {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(deftest schema-rejects-non-vector-loaders-teardown
  (is (not (m/validate rf.story.schemas/Variant
                       {:loaders-teardown [:not-a-vector-of-vectors] :setup []}))))

(deftest loaders-teardown-events-fire-in-declared-order
  (let [fired (atom [])]
    (rf/reg-event :step/one   (fn [{:keys [db]} _] (swap! fired conj :one) {:db db}))
    (rf/reg-event :step/two   (fn [{:keys [db]} _] (swap! fired conj :two) {:db db}))
    (rf/reg-event :step/three (fn [{:keys [db]} _] (swap! fired conj :three) {:db db}))
    (rf.story/reg-variant :story.lt.order/v
      {:loaders-teardown [[:step/one] [:step/two] [:step/three]] :setup []})
    (async done
      (-> (rf.story/run-variant :story.lt.order/v)
          (rf.story.async/then
            (fn [_]
              (is (= [] @fired) "nothing fires while the variant is live")
              (rf.story/destroy-variant! :story.lt.order/v)
              (is (= [:one :two :three] @fired))
              (done)))))))

(deftest loaders-teardown-fires-before-decorator-teardown
  (testing "the body's narrower loader cleanup runs before the wider
            decorator :teardown"
    (let [fired (atom [])]
      (rf/reg-event :dec/teardown (fn [{:keys [db]} _] (swap! fired conj :decorator) {:db db}))
      (rf/reg-event :dec/init (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :lt/cleanup (fn [{:keys [db]} _] (swap! fired conj :loaders-teardown) {:db db}))
      (rf.story/reg-decorator :outer-dec
        {:kind :frame-setup :init [[:dec/init]] :teardown [[:dec/teardown]]})
      (rf.story/reg-variant :story.lt.order2/v
        {:decorators [[:outer-dec]] :loaders-teardown [[:lt/cleanup]] :setup []})
      (async done
        (-> (rf.story/run-variant :story.lt.order2/v)
            (rf.story.async/then
              (fn [_]
                (rf.story/destroy-variant! :story.lt.order2/v)
                (is (= [:loaders-teardown :decorator] @fired))
                (done))))))))

(deftest throwing-loaders-teardown-continues-walk
  (let [fired (atom [])]
    (rf/reg-event :step/before (fn [{:keys [db]} _] (swap! fired conj :before) {:db db}))
    (rf/reg-event :step/boom (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-event :step/after (fn [{:keys [db]} _] (swap! fired conj :after) {:db db}))
    (rf.story/reg-variant :story.lt.continue/v
      {:loaders-teardown [[:step/before] [:step/boom] [:step/after]] :setup []})
    (async done
      (-> (rf.story/run-variant :story.lt.continue/v)
          (rf.story.async/then
            (fn [_]
              (rf.story/destroy-variant! :story.lt.continue/v)
              (is (= [:before :after] @fired) "the walk continues past the throw")
              (done)))))))

(deftest throwing-loaders-teardown-records-assertion
  (testing "a throw is caught, recorded as :rf.error/exception at
            :phase-loaders-teardown, and the frame still goes. A decorator
            probe tears down last and copies the records out."
    (let [captured (atom nil)]
      (rf/reg-event :boom/cleanup (fn [_ _] (throw (ex-info "lt boom" {:why :test}))))
      (rf/reg-event ::probe-init (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event ::probe-snapshot
        (fn [{:keys [db]} _] (reset! captured (:rf.story/assertions db)) {:db db}))
      (rf.story/reg-decorator :lt-probe
        {:kind :frame-setup :init [[::probe-init]] :teardown [[::probe-snapshot]]})
      (rf.story/reg-variant :story.lt.record/v
        {:decorators [[:lt-probe]] :loaders-teardown [[:boom/cleanup]] :setup []})
      (async done
        (-> (rf.story/run-variant :story.lt.record/v)
            (rf.story.async/then
              (fn [_]
                (is (nil? (rf.story/destroy-variant! :story.lt.record/v)))
                (is (not (contains? (rf.story/variant-frames) :story.lt.record/v)))
                (let [err (first (filter #(= :rf.error/exception (:assertion %)) @captured))]
                  (is (= {:phase :phase-loaders-teardown :passed? false :event [:boom/cleanup]
                          :variant-id :story.lt.record/v}
                         (select-keys err [:phase :passed? :event :variant-id])))
                  (is (= {:why :test} (:data (:error err)))))
                (done))))))))
