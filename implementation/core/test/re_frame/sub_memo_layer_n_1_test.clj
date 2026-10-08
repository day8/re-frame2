(ns re-frame.sub-memo-layer-n-1-test
  "Single-input layer-2 sub memoisation (Spec 006 §No-op via value equality):
  the specialised fixed-arity wrapper hands the body `[upstream] query-v` like
  the generic one, short-circuits on an `=` upstream and recomputes on a
  `not=` one."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default` and ambient reads need a
  ;; carried frame (EP-0002), so register it and pin it as the scope.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest layer-n-1-body-receives-upstream-value-and-query-v
  (let [captured (atom nil)]
    (rf/reg-event :seed (fn [_ _] {:db {:n 99}}))
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 {:inputs [[:n]]}
                (fn [[n] query-v]
                  (reset! captured [n query-v])
                  (* 2 n)))
    (rf/dispatch-sync [:seed])
    (is (= [198 [99 [:n*2 :arg1 :arg2]]] [@(rf/subscribe [:n*2 :arg1 :arg2]) @captured]))))

(deftest layer-n-1-memo-handles-nil-and-false
  ;; A nil upstream (common) must not read as the wrapper's unset sentinel.
  (let [runs (atom 0)]
    (rf/reg-event :seed-nil   (fn [_ _] {:db {:n nil}}))
    (rf/reg-event :seed-false (fn [_ _] {:db {:n false}}))
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n-shadow {:inputs [[:n]]} (fn [[n] _] (swap! runs inc) n))
    (rf/dispatch-sync [:seed-nil])
    (let [r (rf/subscribe [:n-shadow])]
      (is (= [nil nil 1] [@r @r @runs]) "the body runs once for nil, then the memo skips")
      (rf/dispatch-sync [:seed-false])
      (is (= [false false 2] [@r @r @runs]) "nil -> false re-runs it once"))))

;; B over A, C over B: a db change A absorbs must not re-run B or C, and a
;; change A propagates runs each once.
(deftest layer-n-1-chain-propagates-and-suppresses-correctly
  (let [a-runs (atom 0)
        b-runs (atom 0)
        c-runs (atom 0)
        runs   #(vector @a-runs @b-runs @c-runs)]
    (rf/reg-event :seed   (fn [_ _]      {:db {:n 1 :other :x}}))
    (rf/reg-event :update (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-event :touch  (fn [{:keys [db]} _]     {:db (assoc db :other :y)}))
    (rf/reg-sub :a (fn [db _] (swap! a-runs inc) (:n db)))
    (rf/reg-sub :b {:inputs [[:a]]} (fn [[a] _] (swap! b-runs inc) (inc a)))
    (rf/reg-sub :c {:inputs [[:b]]} (fn [[b] _] (swap! c-runs inc) (inc b)))
    (rf/dispatch-sync [:seed])
    (let [rc (rf/subscribe [:c])]
      (is (= [3 [1 1 1]] [@rc (runs)]))
      (rf/dispatch-sync [:touch])
      (is (= [3 [2 1 1]] [@rc (runs)]) ":a re-runs but its result is =, so :b and :c do not")
      (rf/dispatch-sync [:update 10])
      (is (= [12 [3 2 2]] [@rc (runs)])))))
