(ns re-frame.flows-per-frame-last-inputs-test
  "A failed flow's dirty-check rollback restores only the draining frame's
  rows. Drain locks are per frame, so a sibling can advance and commit its
  own row while this frame sits between snapshot and throw; restoring every
  frame's rows would revert the sibling's, and its next same-input drain would
  recompute and rewrite for nothing. JVM-only: CLJS has no threads."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_94OL5_STRESS_ITERS") Long/parseLong)
      4000))

(deftest concurrent-throwing-drain-does-not-clobber-sibling-dirty-check
  ;; Frame A's flow throws on every drain; frame B's inputs are stable after
  ;; its first drain, so B's derive must run exactly once.
  (let [b-calls (AtomicLong. 0)
        latch   (CountDownLatch. 1)
        drive   (fn [frame-id n-of]
                  (future (.await latch)
                          (dotimes [i stress-iters]
                            (rf/dispatch-sync [:set-n (n-of i)] {:frame frame-id}))))]
    (rf/make-frame {:id :a})
    (rf/make-frame {:id :b})
    (rf/reg-event :set-n (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (rf/reg-flow :throws {:frame :a :inputs [[:n]] :output-path [:out]}
      (fn [_] (throw (ex-info "boom-A" {}))))
    (rf/reg-flow :doubles {:frame :b :inputs [[:n]] :output-path [:out]}
      (fn [n] (.incrementAndGet b-calls) (* 2 n)))
    (rf/dispatch-sync [:set-n 7] {:frame :b})
    (let [a (drive :a inc)
          b (drive :b (constantly 7))]
      (.countDown latch)
      (is (not= ::timeout (deref a 120000 ::timeout)))
      (is (not= ::timeout (deref b 120000 ::timeout))))
    (is (= [1 [7]] [(.get b-calls) (rf.flows.registry/get-frame-flow-last-inputs :b :doubles)])
        "B derived once and its row survived A's rollbacks")))
