(ns re-frame.machine-minted-cofx-replay-token-test
  "Per rf2-cheez6.1 / rf2-08br0v — the STRUCTURAL fix + determinism proof for
  the machine-minted-cofx replay-token gap.

  ## The confirmed bug (rf2-08br0v)

  A generator-backed recordable cofx MINTED by a state machine's guard/action
  — `ensure-ctx-cofx` pre-drain, or `ensure-raised-cofx` in-drain for a
  raise-selected guard — is written onto the engine-local machine def's
  `:rf/cofx` and threads forward through the drain. But the value did NOT reach
  the epoch's `:rf.cofx` replay token. The router emits `:rf.event/run-start`
  carrying the cofx AS IT WAS after `assemble-initial-ctx`'s PRE-handler
  declared-only delivery (every fact minted for the OUTER event's declared
  `:rf.cofx/requires`). A machine's outer event declares NO requires — they live
  on guards/actions — so the router minted nothing for it, and the machine's own
  ensure steps run INSIDE the handler, AFTER run-start. Net: the run-start
  replay token carried only externally-supplied facts (e.g. `:rf/time-ms`),
  never the machine-minted ones. On `:strict` replay (mint-policy-aware,
  rf2-n0myjq) the missing fact yielded `:rf.error/missing-required-cofx` and the
  macrostep that advanced under `:live` FAILED — replay non-determinism.

  ## The structural fix (this bead)

  Every actual recordable mint — pre-handler in `assemble-initial-ctx` AND
  mid-drain in the machine ensure steps — emits a dev-only `:rf.cofx/generated`
  trace carrying `:rf.cofx/id` + the marks-projected `:rf.cofx/value`. Those
  emits ride the SAME in-flight cascade buffer the epoch capture harvests. So
  `re-frame.epoch.capture/find-trigger-event` now collects them and merges
  `{id -> value}` onto the run-start replay token at epoch-assembly time. This
  changes no LIVE behaviour (a read-only epoch-assembly walk), over-captures
  nothing (only facts a generator ACTUALLY minted emit `:rf.cofx/generated`),
  and is idempotent for the ordinary event path (a pre-handler mint is already
  in the token verbatim). The augmentation matters only for the mid-drain
  machine mints the token previously missed.

  ## What this namespace proves

  This is the EPOCH-side end-to-end test (the epoch test classpath carries
  machines as a test-only dep): it drives the REAL machine dispatch path, reads
  the assembled epoch record, and asserts the token NOW carries the mid-drain
  minted fact (the assertion that FAILS without the fix), then proves a `:strict`
  re-presentation of that exact captured token reproduces the live decision —
  replay determinism, directly. The machines-side companion
  (`re-frame.machine-raise-cofx-replay-test`) proves the end-to-end determinism
  contract (token-fact necessary and sufficient) at the machines surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect require — loads the machines late-bind hooks
            ;; (`:machines/reg-machine`, etc.). The capture/restore fixture
            ;; preserves these ns-load-time registrations across each test.
            [re-frame.machines]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (epoch/clear-history!)
                (epoch/clear-epoch-listeners!))}))

(defn- machine-state [machine-id]
  (-> (rf/runtime-db-value :test/main)
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
      (rf/reg-frame :test/main {})
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

      ;; (2) THE FLIP (the fix): the assembled epoch record's :rf.cofx replay
      ;; token now carries BOTH the external :rf/time-ms AND the mid-drain
      ;; minted :replay/gen. WITHOUT the fix this token would be {:rf/time-ms
      ;; 111} only and this assertion fails.
      (let [rec   (last-record)
            token (:rf.cofx rec)]
        (is (some? rec) "an epoch record was assembled for the machine cascade")
        (is (= :replay/mint (first (:trigger-event rec)))
            "the record is the machine cascade we drove")
        (is (= 111 (:rf/time-ms token))
            "the external :rf/time-ms survives in the token")
        (is (= 100 (:replay/gen token))
            "CONFIRMED FIX (rf2-cheez6.1): the mid-drain minted :replay/gen is
             now captured in the run-start replay token (was absent pre-fix)")))))

(deftest strict-replay-of-the-captured-token-is-deterministic
  (testing "rf2-cheez6.1 — the captured token re-presented under :strict (the
   replay mint policy) reproduces the live decision: the guard reads the
   recorded :replay/gen verbatim (no host re-mint) and the machine reaches
   :done. This is replay determinism, directly — and it depends on the captured
   token carrying the minted fact (the fix). A SECOND machine id (same spec) is
   the fresh :a instance the captured token replays against, so no whole-frame
   reset is needed."
    (let [live-seen   (atom ::unset)
          replay-seen (atom ::unset)]
      (rf/reg-frame :test/main {})
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
  (testing "rf2-cheez6.1 CONTROL — the same :strict dispatch WITHOUT the minted
   fact in the token does NOT advance (strict refuses to mint). This proves the
   captured token fact is LOAD-BEARING: without the fix's augmentation the
   replay token lacks :replay/gen and a :strict replay would diverge from the
   live :done."
    (let [seen (atom ::unset)]
      (rf/reg-frame :test/main {})
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/mint (mint-machine seen))

      ;; The PRE-FIX token shape: only :rf/time-ms, no :replay/gen.
      (rf/dispatch-sync [:replay/mint [:go]]
                        {:frame :test/main
                         :rf.cofx {:rf/time-ms 111}
                         :rf.cofx/mint-policy :strict})
      (is (not= :done (machine-state :replay/mint))
          "strict refused to mint the absent :replay/gen → missing-required →
           the raised macrostep failed atomically → the machine did NOT reach
           :done. This is the divergence the captured-token fix removes."))))
