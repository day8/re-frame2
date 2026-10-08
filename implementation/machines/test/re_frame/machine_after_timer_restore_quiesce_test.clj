(ns re-frame.machine-after-timer-restore-quiesce-test
  "Epoch restore swaps the frame's runtime-db wholesale, so a pre-restore
  `:after` timer's liveness reverts — but its host-clock handle is not
  frame-state and would still fire. The `:machines/on-frame-restored!` hook
  (`cancel-frame-timers-on-restore!`) releases the restored frame's handles
  (Managed-Effects §restore: \"epoch restore MUST NOT revive host work\")."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest restore-hook-releases-only-the-restored-frames-handles
  (rf/reg-machine :rq/m {:initial :idle
                         :data    {}
                         :states  {:idle    {:on {:fetch :loading}}
                                   :loading {:after {3600000 :timeout}}
                                   :timeout {}}})
  (doseq [f [:rq/restored :rq/sibling]]
    (rf/make-frame {:id f})
    (rf/dispatch-sync [:rq/m [:fetch]] {:frame f}))
  (is (= #{:rq/restored :rq/sibling} (set (keys @rf.machines.timer/after-timers)))
      "precondition: both frames armed an :after timer")
  ((rf.late-bind/get-fn :machines/on-frame-restored!) :rq/restored)
  (is (= #{:rq/sibling} (set (keys @rf.machines.timer/after-timers)))
      "the restored frame's handles are released; a sibling frame's are untouched"))
