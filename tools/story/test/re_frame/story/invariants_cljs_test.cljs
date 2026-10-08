(ns re-frame.story.invariants-cljs-test
  "CLJS coverage for `re-frame.story.invariants` (spec/017 §Invariant
  sentinels): check-epoch's CLJS catch arm, and the `with-invariants` macro
  expanding to ClojureScript and observing a real CLJS frame's epochs. The
  platform-neutral core is covered on the JVM by `re-frame.story.invariants-test`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.machines]
            [re-frame.story.invariants :as rf.story.invariants])
  (:require-macros [re-frame.story.invariants :refer [with-invariants]]))

(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-all!)
    (reset! rf.frame/frames {})
    ;; COLD-START the slot: destroy, then seat. `init!` is idempotent only
    ;; for the adapter ALREADY SEATED — handed a DIFFERENT one it raises
    ;; `:rf.error/adapter-already-installed` rather than ignoring the call.
    ;; This ns shares the node bundle with suites that seat Reagent, UIx and
    ;; the SSR adapter, so a bare `init!` here would raise whenever one of
    ;; them ran first.
    (rf/destroy-adapter!)
    (rf/init! rf.substrate.plain-atom/adapter)
    (rf.epoch/clear-epoch-listeners!)
    (rf.epoch/clear-history!)
    (rf.frame/ensure-default-frame!)
    (test-fn)))

(defn- epoch-rec
  [epoch-id m]
  (merge {:epoch-id epoch-id :frame :test/frame :outcome :ok
          :db-before {} :db-after {} :trace-events []}
         m))

(deftest check-epoch-isolates-throw-cljs
  (testing "a throwing predicate is caught on CLJS"
    (let [c (rf.story.invariants/coerce-invariant 0 (fn [_] (throw (ex-info "boom" {}))))
          v (rf.story.invariants/check-epoch c (epoch-rec 1 {}))]
      (is (string? (:error v))))))

(defn- with-captured-reports
  [f]
  (let [reports (atom [])]
    (with-redefs [cljs.test/report (fn [m] (swap! reports conj m))]
      (f))
    @reports))

(deftest with-invariants-live-cljs
  (testing "the sentinel observes a real frame's epochs and reports once per failing epoch"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :dec  (fn [{:keys [db]} _] {:db (update db :n dec)}))
    (let [reports (with-captured-reports
                    (fn []
                      (with-invariants [(fn [e] (>= (:n (:db-after e)) 0))]
                        (rf/dispatch-sync [:seed] {:frame :test/main})
                        (rf/dispatch-sync [:dec]  {:frame :test/main})
                        (rf/dispatch-sync [:dec]  {:frame :test/main}))))]
      (is (= 2 (count (filter #(= :fail (:type %)) reports)))
          "two failing epochs → two failures"))))
