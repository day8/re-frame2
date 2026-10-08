(ns re-frame.machine-front-of-queue-cljs-test
  "Spec 005 §Level 4 end to end: a real machine action's `:fx [[:dispatch …]]`
  continuation leap-frogs an already-queued external event, and FIFO resumes
  once control leaves the machine — a plain handler running as that
  continuation queues its own `:fx` dispatch at the back. Core's
  `router-front-of-queue-cljs-test` pins the queue-insertion rule itself."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.router :as rf.router]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest machine-continuation-leapfrogs-then-plain-children-queue-fifo
  (let [run-log (atom [])
        log!    (fn [k] (swap! run-log conj k))]
    (doseq [id [:ext :cont-2]]
      (rf/reg-event id (fn [_ _] (log! id) {})))
    (rf/reg-event :cont-1
      (fn [_ _]
        (log! :cont-1)
        {:fx [[:dispatch [:cont-2]]]}))
    (rf/reg-machine :rf2-j20a7/quiesce
      {:initial :idle
       :actions {:fire (fn [_]
                         (log! :machine-action)
                         {:fx [[:dispatch [:cont-1]]]})}
       :states  {:idle {:on {:go {:target :done :action :fire}}}
                 :done {}}})
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf.router/dispatch! [:rf2-j20a7/quiesce [:go]] {})
        (rf.router/dispatch! [:ext] {})
        {}))
    (rf/dispatch-sync [:seed])
    ;; queue [M ext] -> M fronts :cont-1 -> [:cont-1 ext] -> plain :cont-1
    ;; backs :cont-2 -> [ext :cont-2]
    (is (= [:seed :machine-action :cont-1 :ext :cont-2] @run-log))))
