(ns re-frame.jvm-prod-gate-integration-test
  "Despite the namespace's name, this suite is NOT THE LOAD-TIME GATE. It
  rebinds `rf.interop/debug-enabled?` with `with-redefs`, after the framework
  has loaded, and so pins the REBINDABLE VAR: trace emission re-reads the gate
  at call time, so a rebind to `false` silences trace listeners. The load-time
  gate is `scripts/test-core-prod-gate.sh` (pinned by
  `re-frame.prod-gate-lane-pin-test`) and `re-frame.prod-gate-dispatch-jvm-test`.

  The silent listener is an ABSENCE, which a dispatch that never ran would also
  satisfy, so the test pairs it with a WITNESS: the handler's app-db write."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest trace-listener-silent-when-debug-disabled
  (with-redefs [rf.interop/debug-enabled? false]
    (let [seen (atom [])]
      (rf/register-listener! :trace :prod-gate/recorder #(swap! seen conj %))
      (rf/reg-event :prod-gate/silent
                    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate/silent])
      (rf/unregister-listener! :trace :prod-gate/recorder)
      (is (= 1 (:n (:rf.db/app (rf/frame-state-value :rf/default))))
          "WITNESS: the dispatch ran, so the listener had a real cascade to miss")
      (is (empty? @seen)))))
