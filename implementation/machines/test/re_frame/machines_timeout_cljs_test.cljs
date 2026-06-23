(ns re-frame.machines-timeout-cljs-test
  "CLJS-side coverage for EP-0029 A4 `:timeout` / `:on-timeout` under the
  Reagent reactive substrate.

  Confirms cross-host parity for:
    - the duration grammar (integer-ms OR ISO-8601 only; the XState
      `\"5s\"` / `\"10ms\"` shorthand is rejected — exercises the CLJS
      `js/parseInt` / `js/parseFloat` / `js/Math.round` parse path);
    - registration fail-loud validation;
    - the dispatch boundary — a state `:timeout` desugars onto an `:after`
      timer that arms on entry and fires the `:on-timeout` transition.

  Mirrors the JVM timeout_test.clj. Prefer dispatch-sync of the synthetic
  `:rf.machine.timer/after-elapsed` event over wall-clock setTimeout waits
  so the test is deterministic under Node."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.timeout :as timeout]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private snapshot mtest/snapshot)

(deftest duration-grammar-cljs
  (testing "integer-ms + ISO-8601 resolve cross-host (CLJS parse path)"
    (is (= 5000   (timeout/resolve-duration-ms 5000)))
    (is (= 5000   (timeout/resolve-duration-ms "PT5S")))
    (is (= 120000 (timeout/resolve-duration-ms "PT2M")))
    (is (= 500    (timeout/resolve-duration-ms "PT0.5S")))
    (is (= 5400000 (timeout/resolve-duration-ms "PT1H30M"))))
  (testing "the XState `5s` / `10ms` shorthand is rejected cross-host"
    (is (nil? (timeout/resolve-duration-ms "5s")))
    (is (nil? (timeout/resolve-duration-ms "10ms")))
    (is (nil? (timeout/resolve-duration-ms "P")))
    (is (nil? (timeout/resolve-duration-ms 0)))))

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "ttc" (str (gensym))) machine) nil
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest registration-fail-loud-cljs
  (testing ":timeout without :on-timeout fails loud"
    (is (= :rf.error/machine-timeout-without-on-timeout
           (reg-error-id {:initial :w :states {:w {:timeout 5000} :d {}}}))))
  (testing "the `5s` shorthand duration fails loud"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :w :states {:w {:timeout "5s" :on-timeout :d} :d {}}}))))
  (testing "a spawn :timeout with no :on-timeout fails loud"
    (is (= :rf.error/machine-timeout-without-on-timeout
           (reg-error-id {:initial :l
                          :states {:l {:spawn {:machine-id :stub :timeout 10000}}
                                   :to {}}})))))

(deftest state-timeout-arms-and-fires-cljs
  (testing "a state :timeout arms an :after timer and the timer-elapsed event fires :on-timeout"
    (let [m {:initial :idle :data {}
             :states {:idle      {:on {:go :waiting}}
                      :waiting   {:timeout "PT5S" :on-timeout {:target :timed-out}}
                      :timed-out {}}}]
      (rf/reg-machine :ttc/fire m)
      (rf/dispatch-sync [:ttc/fire [:go]])
      (let [s (snapshot :ttc/fire)]
        (is (= :waiting (:state s)))
        (is (= 1 (get-in s [:data :rf/after-epoch [:waiting]]))
            "the desugared :after timer armed with epoch 1"))
      (rf/dispatch-sync [:ttc/fire [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]])
      (is (= :timed-out (:state (snapshot :ttc/fire)))
          "the :on-timeout transition fired when the resolved-ms timer elapsed"))))
