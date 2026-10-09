(ns re-frame.machine-minted-cofx-replay-token-test
  "Coeffects minted inside a machine run reach the replay token: epoch
  assembly folds `:rf.cofx/generated` traces into it, so facts a guard or
  action mints mid-run sit beside the run-start ones. Capture and strict
  re-presentation are pinned end to end by `re-frame.epoch-replay-cljs-test`'s
  `restore-then-replay-by-id-reproduces-a-mid-run-machine-mint`; this is the
  control that makes the captured fact load-bearing."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Publishes the machine hooks; the fixture keeps those ns-load
            ;; registrations across tests.
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.epoch/clear-history!)
                (rf.epoch/clear-epoch-listeners!))}))

(defn- machine-state [machine-id]
  (-> (:rf.db/runtime (rf/frame-state-value :test/main))
      (get-in [:rf.runtime/machines :snapshots machine-id])
      :state))

;; `:inner`'s guard requires `:replay/gen`, which is not in `:go`'s ensure-set,
;; so under `:live` it is minted mid-drain.
(def ^:private mint-machine
  {:initial :a
   :guards  {:check {:rf.cofx/requires [:replay/gen]
                     :fn (fn [{cofx :rf.cofx}] (some? (:replay/gen cofx)))}}
   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
   :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
             :b    {:on {:inner {:target :done :guard :check}}}
             :done {}}})

(deftest strict-without-the-minted-fact-diverges-control
  (rf/make-frame {:id :test/main})
  (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
  (rf/reg-machine :replay/mint mint-machine)
  (rf/dispatch-sync [:replay/mint [:go]]
                    {:frame :test/main
                     :rf.cofx {:rf/time-ms 111}
                     :rf.cofx/mint-policy :strict})
  (is (not= :done (machine-state :replay/mint))
      "strict refuses to mint the absent fact, so the machine does not reach :done"))
