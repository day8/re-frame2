(ns re-frame.machine-minted-cofx-replay-token-test
  "End-to-end replay coverage for coeffects minted inside a machine run.

  Run-start contains only coeffects known before handler execution. Machine
  guards and actions may mint recordable facts later, so epoch assembly also
  folds `:rf.cofx/generated` traces into the replay token. Pre-handler mints
  merge idempotently; mid-run mints supply facts absent from run-start.

  The tests drive real machine dispatch, assert that the captured token contains
  the generated fact, and prove strict re-presentation reproduces the live
  decision without another host read."
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

(defn- last-record []
  (last (rf/epoch-history :test/main)))

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

(deftest mid-drain-minted-fact-is-captured-in-the-replay-token
  (testing "rf2-cheez6.1 / rf2-08br0v — a raise-selected guard fact MINTED
   mid-macrostep under :live drives the machine decision AND is now captured
   into the epoch's :rf.cofx replay token. Pre-fix the token carried only the
   external :rf/time-ms; this assertion FAILS without the find-trigger-event
   :rf.cofx/generated merge."
    ;; A generator-backed recordable cofx minting a distinct, stable value.
    (let [seen (atom ::unset)]
      (rf/make-frame {:id :test/main})
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/mint (mint-machine seen))

      ;; LIVE dispatch — only :rf/time-ms in the external token. :replay/gen is
      ;; minted mid-drain by the raised :inner's guard ensure.
      (rf/dispatch-sync [:replay/mint [:go]]
                        {:frame :test/main :rf.cofx {:rf/time-ms 111}})

      ;; (1) The mint DID happen and the machine advanced on the minted value.
      (is (= 100 @seen)
          "the raised guard read the MINTED generator-backed fact (mid-drain)")
      (is (= :done (machine-state :replay/mint))
          "the machine advanced to :done on the minted value")

      ;; The assembled epoch record's :rf.cofx replay token carries both the
      ;; external :rf/time-ms and the mid-drain minted :replay/gen.
      (let [rec   (last-record)
            token (:rf.cofx rec)]
        (is (some? rec) "an epoch record was assembled for the machine cascade")
        (is (= :replay/mint (first (:trigger-event rec)))
            "the record is the machine cascade we drove")
        (is (= 111 (:rf/time-ms token))
            "the external :rf/time-ms survives in the token")
        (is (= 100 (:replay/gen token))
            "the run-start replay token captures the mid-drain minted :replay/gen")))))

(deftest strict-replay-of-the-captured-token-is-deterministic
  (testing "the captured token re-presented under :strict (the
   replay mint policy) reproduces the live decision: the guard reads the
   recorded :replay/gen verbatim (no host re-mint) and the machine reaches
   :done. This is replay determinism, directly, and depends on the captured
   token carrying the minted fact. A second machine id (same spec) is
   the fresh :a instance the captured token replays against, so no whole-frame
   reset is needed."
    (let [live-seen   (atom ::unset)
          replay-seen (atom ::unset)]
      (rf/make-frame {:id :test/main})
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/mint   (mint-machine live-seen))
      (rf/reg-machine :replay/replay (mint-machine replay-seen))

      ;; Live capture against the first machine.
      (rf/dispatch-sync [:replay/mint [:go]]
                        {:frame :test/main :rf.cofx {:rf/time-ms 111}})
      (let [captured-token (:rf.cofx (last-record))]
        ;; Re-present the EXACT captured token under :strict (the replay policy)
        ;; against a fresh machine instance. Strict REFUSES to mint a declared-
        ;; absent generator-backed fact — so determinism here rides ENTIRELY on
        ;; the recorded value being present in the token.
        (rf/dispatch-sync [:replay/replay [:go]]
                          {:frame :test/main
                           :rf.cofx captured-token
                           :rf.cofx/mint-policy :strict})
        (is (= 100 @replay-seen)
            "STRICT replay re-presented the recorded :replay/gen verbatim
             (no host re-mint) — the guard read the SAME value as the live run")
        (is (= :done (machine-state :replay/replay))
            "STRICT replay reproduced the live decision: the machine reached
             :done deterministically")))))

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
           :done. This is the divergence the captured-token fix removes."))))
