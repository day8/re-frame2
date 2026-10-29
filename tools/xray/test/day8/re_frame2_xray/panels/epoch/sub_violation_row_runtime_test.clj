(ns day8.re-frame2-xray.panels.epoch.sub-violation-row-runtime-test
  "The SUBSCRIPTIONS row a `:sub-return` schema violation attaches to, over
  the records real events leave.

  A sub is validated on every recompute, so an unrelated event that makes it
  recompute to the same failing value records a fresh violation on a row that
  did NOT change. The view shows a row carrying a violation in every filter
  mode, its `:changed` default included, so this is the row shape that
  depends on it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            ;; Loaded for its late-bind hooks: without it no sub schema validates.
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- sub-rows [record]
  (mapv (juxt :sub-vec :changed? (comp count :violations))
        (:rows (some #(when (= :subscriptions (:step %)) %) (proj/project record)))))

(deftest an-unchanged-recompute-carries-its-violation-on-its-row
  (rf/make-frame {:id :test/main})
  (rf/reg-sub :cart/total {:schema :int} (fn [db _] (get-in db [:cart :total])))
  (rf/reg-event :cart/seed (fn [{:keys [db]} _] {:db (assoc-in db [:cart :total] "12.50")}))
  (rf/reg-event :cart/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/dispatch-sync [:cart/seed] {:frame :test/main})
  (let [s (rf/subscribe [:cart/total] {:frame :test/main})]
    @s
    (rf/dispatch-sync [:cart/touch] {:frame :test/main})
    @s)
  (let [[seed touch] (take-last 2 (rf.epoch/epoch-history :test/main))
        touch-steps  (proj/project touch)
        subs         (some #(when (= :subscriptions (:step %)) %) touch-steps)]
    (is (= [[[:cart/total] true 1]] (sub-rows seed))
        "control: the first run changes the value and carries the violation")
    (is (= [[[:cart/total] false 1]] (sub-rows touch))
        "the unchanged recompute carries a fresh violation on its row")
    (is (empty? (:violations subs))
        "the step itself carries none: the row is the violation's only home")
    (is (= :error (proj/step-status subs))
        "and the step is graded red for it")))
