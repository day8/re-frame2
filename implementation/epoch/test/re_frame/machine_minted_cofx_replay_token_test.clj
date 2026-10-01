(ns re-frame.machine-minted-cofx-replay-token-test
  "End-to-end replay coverage for coeffects minted inside a machine run.

  Run-start contains only coeffects known before handler execution. Machine
  guards and actions may mint recordable facts later, so epoch assembly also
  folds `:rf.cofx/generated` traces into the replay token. Pre-handler mints
  merge idempotently; mid-run mints supply facts absent from run-start.

  The capture of a mid-run mint and its strict re-presentation are pinned end to
  end by `re-frame.epoch-replay-cljs-test`'s
  `restore-then-replay-by-id-reproduces-a-mid-run-machine-mint`. This file keeps
  the control that makes the captured fact load-bearing: a strict dispatch whose
  token lacks it does not reach the live decision."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect require — loads the machines late-bind hooks
            ;; (`:machines/reg-machine`, etc.). The capture/restore fixture
            ;; preserves these ns-load-time registrations across each test.
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

;; The shared machine spec: `:go`'s action raises `[:inner]`; `:inner`'s guard
;; requires the generator-backed `:replay/gen`, which is NOT in `:go`'s
;; ensure-set, so it is minted MID-DRAIN by `ensure-raised-cofx` under `:live`.
;; A guard that fires only when the minted fact is present (and the machine then
;; advances to `:done`) so the captured / replayed decision is observable.
(defn- mint-machine [seen]
  {:initial :a
   :data    {}
   :guards  {:check
             {:rf.cofx/requires [:replay/gen]
              :fn (fn [{cofx :rf.cofx}]
                    (reset! seen (:replay/gen cofx))
                    (some? (:replay/gen cofx)))}}
   :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
   :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
             :b    {:on {:inner {:target :done :guard :check}}}
             :done {}}})

(deftest strict-without-the-minted-fact-diverges-control
  (testing "the same :strict dispatch without the minted
   fact in the token does NOT advance (strict refuses to mint). This proves the
   captured token fact is load-bearing: without it the
   replay token lacks :replay/gen and a :strict replay would diverge from the
   live :done."
    (let [seen (atom ::unset)]
      (rf/make-frame {:id :test/main})
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/mint (mint-machine seen))

      ;; A token without the machine-minted replay generation.
      (rf/dispatch-sync [:replay/mint [:go]]
                        {:frame :test/main
                         :rf.cofx {:rf/time-ms 111}
                         :rf.cofx/mint-policy :strict})
      (is (not= :done (machine-state :replay/mint))
          "strict refused to mint the absent :replay/gen → missing-required →
           the raised macrostep failed atomically → the machine did NOT reach
           :done. This is the divergence capturing the minted fact prevents."))))
