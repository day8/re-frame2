(ns re-frame.machine-internal-lane-cljs-test
  "A real machine action's `:fx [[:dispatch …]]` continuation is ordinary
  internal-lane work (Spec 002 §Run-to-completion, Spec 005 §Level 4): it
  joins the lane behind the sibling its family queued first, and a plain
  handler running as that continuation queues its own child the same way.
  Machine origin gives no priority. Core's `router-internal-lane-cljs-test`
  pins the lane rule itself; ordering against external input is pinned on the
  JVM in `re-frame.router-lanes-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.router :as rf.router]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest machine-continuations-queue-fifo-behind-earlier-siblings
  (let [run-log (atom [])
        log!    (fn [k] (swap! run-log conj k))]
    (doseq [id [:sib :cont-2]]
      (rf/reg-event id (fn [_ _] (log! id) {})))
    (rf/reg-event :cont-1
      (fn [_ _]
        (log! :cont-1)
        {:fx [[:dispatch [:cont-2]]]}))
    (rf/reg-machine :lanes/quiesce
      {:initial :idle
       :actions {:fire (fn [_]
                         (log! :machine-action)
                         {:fx [[:dispatch [:cont-1]]]})}
       :states  {:idle {:on {:go {:target :done :action :fire}}}
                 :done {}}})
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf.router/dispatch! [:lanes/quiesce [:go]] {})
        (rf.router/dispatch! [:sib] {})
        {}))
    (rf/dispatch-sync [:seed])
    ;; lane [M sib] -> M queues :cont-1 -> [sib :cont-1] -> :cont-1 queues
    ;; :cont-2 -> [:cont-2]
    (is (= [:seed :machine-action :sib :cont-1 :cont-2] @run-log))))
