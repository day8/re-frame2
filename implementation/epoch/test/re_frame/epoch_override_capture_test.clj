(ns re-frame.epoch-override-capture-test
  "`:rf/epoch-record` pins the envelope's `:fx-overrides` /
  `:interceptor-overrides` from the run-start trace (Spec-Schemas
  §`:rf/epoch-record`). What rides that trace, including the exclusion of the
  per-frame tier, is pinned by `re-frame.override-capture-trace-test` (core);
  the captured shapes and their re-supply on replay by
  `re-frame.epoch-replay-cljs-test`. This pins the hot path: no overrides, no
  keys."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Without it nothing records an epoch and the assertion reads nil.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest override-free-dispatch-omits-both-keys
  (rf/make-frame {:id :test/main})
  (rf/reg-event :probe/noop (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:probe/noop] {:frame :test/main})
  (is (= [:probe/noop {}]
         ((juxt :event-id #(select-keys % [:fx-overrides :interceptor-overrides]))
          (last (rf/epoch-history :test/main))))))
