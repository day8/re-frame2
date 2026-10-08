(ns re-frame.sub-memo-layer-1-test
  "Layer-1 sub memoisation (Spec 006 §No-op via value equality): the
  specialised fixed-arity wrapper short-circuits on an `=` db exactly like the
  generic one, `identical?` or not."
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

(deftest layer-1-memo-value-equal-but-not-identical-skips
  (let [runs (atom 0)]
    (rf/reg-event :seed   (fn [_ _] {:db {:n 42 :other :a}}))
    ;; A fresh map, `=` to the seeded one.
    (rf/reg-event :reseed (fn [_ _] {:db {:n 42 :other :a}}))
    (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:n])]
      (is (= [42 1] [@r @runs]))
      (rf/dispatch-sync [:reseed])
      (is (= [42 1] [@r @runs]) "a value-equal db does not re-run the body"))))
