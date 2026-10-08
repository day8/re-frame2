(ns re-frame.machine-raise-cofx-replay-test
  "A generator-backed fact a raised event's guard mints mid-macrostep replays
  deterministically: under `:strict` the recorded fact reproduces the live
  decision, and a token without it does not advance."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; :inner's guard fact is not in :go's ensure-set, so it is ensured mid-drain.
(def ^:private mint-machine
  {:initial :a
   :guards  {:check {:rf.cofx/requires [:replay/gen]
                     :fn (fn [{cofx :rf.cofx}] (some? (:replay/gen cofx)))}}
   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
   :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
             :b    {:on {:inner {:target :done :guard :check}}}
             :done {}}})

(deftest strict-replay-advances-only-with-the-minted-fact
  (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
  (doseq [[id token done?] [[:replay/with    {:rf/time-ms 111 :replay/gen 100} true]
                            [:replay/without {:rf/time-ms 111}                 false]]]
    (rf/reg-machine id mint-machine)
    (rf/dispatch-sync [id [:go]] {:rf.cofx token :rf.cofx/mint-policy :strict})
    (is (= done? (= :done (rf.machines.test-support/machine-state id))) (str id))))
