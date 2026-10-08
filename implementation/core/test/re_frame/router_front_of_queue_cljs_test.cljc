(ns re-frame.router-front-of-queue-cljs-test
  "Router queue order (Spec 005 §Level 4): a dispatch flagged
  `:rf.machine/internal?` leap-frogs already-queued external events, flagged
  siblings keeping source order, while unflagged dispatches stay FIFO at the
  back. Front insertion changes order only: each leap-frogged event is still
  its own dequeued event with its own `:rf.event/run-start`. The flag is set
  explicitly on `re-frame.router/dispatch!` here; the machines artefact covers
  it end to end in `re-frame.machine-front-of-queue-cljs-test`.

  Dual-target `.cljc`: the JVM runner selects `-test$` and Shadow's
  `:node-test` build selects `cljs-test$`, so the `-cljs-test` suffix is what
  makes it run on both."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest machine-internal-dispatches-leapfrog-the-fifo-queue
  ;; One dispatch-sync drain: the seed's child dispatches enqueue onto it,
  ;; then dequeue in queue order.
  (let [run-log (atom [])
        seen    (atom [])]
    (doseq [id [:ext :plain :c1 :c2]]
      (rf/reg-event id (fn [_ _] (swap! run-log conj id) {})))
    (rf/reg-event :seed
      (fn [_ _]
        (swap! run-log conj :seed)
        (rf.router/dispatch! [:ext] {})
        (rf.router/dispatch! [:plain] {})
        (rf.router/dispatch! [:c1] {:rf.machine/internal? true})
        (rf.router/dispatch! [:c2] {:rf.machine/internal? true})
        {}))
    (rf/register-listener! :trace ::run-starts (fn [ev] (swap! seen conj ev)))
    (try
      (rf/dispatch-sync [:seed] {:frame :rf/default})
      (finally (rf/unregister-listener! :trace ::run-starts)))
    (is (= [:seed :c1 :c2 :ext :plain] @run-log)
        "flagged dispatches run first in source order; unflagged ones stay FIFO behind them")
    (when rf.interop/debug-enabled?
      (is (= [:seed :c1 :c2 :ext :plain]
             (->> @seen
                  (filter #(= :rf.event/run-start (:operation %)))
                  (mapv #(:rf.trace/event-id (:tags %)))))
          "one :rf.event/run-start per dequeued event, none collapsed"))))
