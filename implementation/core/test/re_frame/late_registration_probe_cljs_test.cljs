(ns re-frame.late-registration-probe-cljs-test
  "SCRATCH PROBE for rf2-ma8r — not a deliverable. Pins the
  `make-frame` -> `reg-*` -> `dispatch` sequence and probes each link of
  the reprojection chain so a failure names WHICH link broke."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest probe-late-registration-after-make-frame
  (testing "make-frame -> reg-event -> dispatch-sync"
    (let [fid :probe/late]
      (rf/make-frame {:id fid})
      (println "PROBE flush-publisher-resolvable?"
               (some? (late-bind/get-fn-cached :live-frame/flush-projection!)))
      (println "PROBE mark-dirty-publisher-resolvable?"
               (some? (late-bind/get-fn-cached :live-frame/mark-projection-dirty!)))
      (println "PROBE live-frame-ids-after-make-frame" (pr-str (live-frame/live-frame-ids)))
      (rf/reg-event ::late (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
      (println "PROBE registrar-lookup-truthy?"
               (some? (registrar/lookup :event ::late)))
      ;; The SYNCHRONOUS all-or-nothing twin: if the resilient sweep is
      ;; swallowing a per-frame assembly throw, this surfaces it.
      (let [flushed (try
                      {:ok (live-frame/flush-pending-reprojection!)}
                      (catch :default e {:threw (str e)}))]
        (println "PROBE explicit-flush-result" (pr-str flushed)))
      (rf/dispatch-sync [::late] {:frame fid})
      (println "PROBE app-db-after-dispatch" (pr-str (rf/app-db-value fid)))
      (is (true? (:ran (rf/app-db-value fid)))
          "a reg-event issued AFTER make-frame must be visible to that frame"))))

(deftest control-registration-before-make-frame
  (testing "reg-event -> make-frame -> dispatch-sync (the workaround order)"
    (let [fid :probe/early]
      (rf/reg-event ::early (fn [{:keys [db]} _] {:db (assoc db :ran true)}))
      (rf/make-frame {:id fid})
      (rf/dispatch-sync [::early] {:frame fid})
      (is (true? (:ran (rf/app-db-value fid)))
          "control: registering before make-frame must work"))))
