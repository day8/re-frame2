(ns re-frame.router-next-tick-ordering-cljs-test
  "The router drains on a next-turn TASK, not a microtask (Spec 002 §Drain
  scheduling). A microtask queued right after a `dispatch` must see the event
  not yet drained; a drain scheduled as a microtask, or run inline, would
  already have run. Nothing here depends on which task mechanism
  `goog.async.nextTick` picks — `setImmediate`, `MessageChannel` or the
  `setTimeout` fallback are all tasks."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter
                                               :async?  true}))

(deftest router-drain-lands-after-the-microtask-checkpoint
  (async done
    (rf/reg-event :rt/mark (fn [{:keys [db]} _] {:db (assoc db :marked true)}))
    (let [at-microtask (atom nil)
          marked?      #(boolean (:marked (rf/app-db-value :rf/default)))]
      (rf/dispatch [:rt/mark])
      (js/queueMicrotask #(reset! at-microtask (marked?)))
      ;; Two tasks out is past the drain's own tick.
      (rf.interop/next-tick
        (fn []
          (rf.interop/next-tick
            (fn []
              (is (= {:at-microtask false :after-the-tick true}
                     {:at-microtask @at-microtask :after-the-tick (marked?)})
                  "not drained at the microtask checkpoint; drained once the router's task ran")
              (done))))))))
