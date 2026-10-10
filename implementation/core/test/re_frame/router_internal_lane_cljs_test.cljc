(ns re-frame.router-internal-lane-cljs-test
  "Router queue order (Spec 002 §Run-to-completion): every dispatch an event
  makes while it runs — an imperative `dispatch!` in the handler body as much
  as an `:fx [[:dispatch …]]` — joins the frame's internal lane in source
  order, and the lane is FIFO, so siblings run breadth-first before their own
  children. The lane changes order only: each event is still its own dequeued
  event with its own `:rf.event/run-start`. Ordering against external input
  needs an event held in flight on another thread, so it is pinned on the JVM
  in `re-frame.router-lanes-test`; the machines artefact covers machine
  children in `re-frame.machine-internal-lane-cljs-test`.

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

(deftest handler-dispatches-join-the-internal-lane-in-source-order
  ;; One dispatch-sync drain: the seed's dispatches join the internal lane in
  ;; the order they were made, then dequeue in lane order.
  (let [run-log (atom [])
        seen    (atom [])]
    (doseq [id [:imp-b :fx-c :a-child]]
      (rf/reg-event id (fn [_ _] (swap! run-log conj id) {})))
    (rf/reg-event :imp-a
      (fn [_ _]
        (swap! run-log conj :imp-a)
        {:fx [[:dispatch [:a-child]]]}))
    (rf/reg-event :seed
      (fn [_ _]
        (swap! run-log conj :seed)
        (rf.router/dispatch! [:imp-a] {})
        (rf.router/dispatch! [:imp-b] {})
        {:fx [[:dispatch [:fx-c]]]}))
    (rf/register-listener! :trace ::run-starts (fn [ev] (swap! seen conj ev)))
    (try
      (rf/dispatch-sync [:seed] {:frame :rf/default})
      (finally (rf/unregister-listener! :trace ::run-starts)))
    (is (= [:seed :imp-a :imp-b :fx-c :a-child] @run-log)
        "imperative and :fx dispatches share one FIFO lane; :imp-a's child queues behind its siblings")
    (when rf.interop/debug-enabled?
      (is (= [:seed :imp-a :imp-b :fx-c :a-child]
             (->> @seen
                  (filter #(= :rf.event/run-start (:operation %)))
                  (mapv #(:rf.trace/event-id (:tags %)))))
          "one :rf.event/run-start per dequeued event, none collapsed"))))
