(ns todomvc.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :showing
  (fn [db _]
    (:showing db)))

(rf/reg-sub :sorted-todos
  (fn [db _]
    (:todos db)))

(rf/reg-sub :todos
  :<- [:sorted-todos]
  (fn [sorted-todos _]
    (vals sorted-todos)))

(rf/reg-sub :visible-todos
  :<- [:todos]
  :<- [:showing]
  (fn [[todos showing] _]
    (let [predicate (case showing
                      :active    (complement :completed)
                      :completed :completed
                      identity)]
      (filter predicate todos))))

(rf/reg-sub :all-complete?
  :<- [:todos]
  (fn [todos _]
    (and (seq todos) (every? :completed todos))))

(rf/reg-sub :completed-count
  :<- [:todos]
  (fn [todos _]
    (count (filter :completed todos))))

(rf/reg-sub :footer-counts
  :<- [:todos]
  :<- [:completed-count]
  (fn [[todos completed-count] _]
    [(- (count todos) completed-count) completed-count]))
