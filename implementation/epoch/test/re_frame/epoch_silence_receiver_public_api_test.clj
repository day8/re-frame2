(ns re-frame.epoch-silence-receiver-public-api-test
  "The delayed-silence receiver rule is implementable through the PUBLIC API
  alone: a consumer hands a real emitted signal's tags to
  `rf/epoch-silence-current?` (Spec 009 §The delayed-silence emission
  linearization law). No private state is read here."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks, including
            ;; `:epoch/epoch-silence-current?`.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest an-unregistered-listener-discards-its-own-pending-silence
  (let [recorded (atom [])]
    (rf/make-frame {:id :test/dropped})
    (rf/reg-event :seed-dropped (fn [_ _] {:db {:n 0}}))
    (rf/register-listener! :trace ::drop-recorder (fn [ev] (swap! recorded conj ev)))
    (rf/register-listener! :epoch ::drop-watcher (fn [_] nil))
    (rf/dispatch-sync [:seed-dropped] {:frame :test/dropped})
    (rf/destroy-frame! :test/dropped)
    (let [tags (->> @recorded
                    (filter #(= :rf.epoch.cb/silenced-on-frame-destroy (:operation %)))
                    first
                    :tags)]
      (is (true? (rf/epoch-silence-current? tags)) "current while registered")
      (rf/unregister-listener! :epoch ::drop-watcher)
      (is (false? (rf/epoch-silence-current? tags))
          "after the drop the signal names no live registration"))))
