(ns re-frame.machine-raise-cofx-replay-test
  "Per rf2-08br0v — replay-determinism of a generator-backed cofx fact MINTED
  by a same-macrostep RAISED event's guard/action.

  ## The investigation (rf2-08br0v — confirmed REAL)

  `drain-to-fixed-point` re-runs `ensure-raised-cofx` per dequeued raise
  (rf2-xsdn5h). Under `:live` it MINTS a fresh generator-backed recordable
  fact mid-macrostep when a raise-selected guard's `:rf.cofx/requires` was NOT
  in the external event's ensure-set. The minted value is written onto the
  engine-local machine def's `:rf/cofx` and threads forward IN the drain (so a
  later raise / `:always` re-presents it — in-drain consistency).

  But it does NOT flow back to the token the EPOCH captures. The epoch's
  `:rf.cofx` replay token is captured at `:rf.event/run-start`
  (`re-frame.epoch.capture/find-trigger-event` reads the run-start trace's
  `:rf.event/cofx`), which the router emits from `assemble-initial-ctx`'s
  coeffects BEFORE the handler runs. A state machine's outer event handler
  declares no `:rf.cofx/requires` (they live on guards/actions), so
  `assemble-initial-ctx` mints NOTHING for it — and the machine's own ensure
  steps (`ensure-ctx-cofx` pre-drain, `ensure-raised-cofx` in-drain) run INSIDE
  the handler, AFTER run-start. So the captured replay token contains only the
  externally-supplied facts (e.g. `:rf/time-ms`), never the machine-minted ones.

  Consequence (the divergence): a `:live` original run mints a fresh value and
  a raise-selected guard decides on it; on `:strict` replay the epoch token
  lacks that fact, so `ensure-raised-cofx` (now mint-policy-aware, rf2-n0myjq)
  REFUSES to mint and surfaces `:rf.error/missing-required-cofx` — the replayed
  macrostep fails where the original advanced. Replay is non-deterministic.

  The structural fix (re-capturing the post-handler / post-mint cofx token into
  the epoch's run-start replay slot) is a CORE router + epoch capture-timing
  change, tracked as a follow-up. This namespace is the CHARACTERISATION test
  that LOCKS the confirmed divergence at the machines surface so the follow-up
  has a regression target: it asserts (1) the in-drain MINT happens and the
  guard decides on it under `:live`, and (2) the captured run-start replay token
  does NOT yet carry the minted fact — the precise gap. When the follow-up
  lands, assertion (2) flips (the token carries the minted fact) and this test
  is updated to assert replay determinism directly.

  JVM — drives the real dispatch path (`rf/dispatch-sync` through the live
  machine handler + transition engine + epoch capture)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest in-drain-minted-fact-is-not-yet-captured-in-the-replay-token
  (testing "rf2-08br0v — a raise-selected guard fact MINTED mid-macrostep under
   :live drives the machine decision, but is NOT (yet) captured in the epoch's
   run-start :rf.cofx replay token — the confirmed replay-determinism gap"
    ;; A generator-backed recordable cofx that mints a FRESH value each call.
    ;; Stamp a deterministic-but-distinct value via an atom counter so the test
    ;; can prove the minted value reached the guard.
    (let [minted    (atom 0)
          seen      (atom ::unset)]
      (rf/reg-cofx :replay/gen {:recordable? true}
                   (fn [] (swap! minted inc) (* 100 @minted)))
      (let [m {:initial :a
               :data    {}
               :guards  {:check
                         {:rf.cofx/requires [:replay/gen]
                          :fn (fn [{cofx :rf.cofx}]
                                (reset! seen (:replay/gen cofx))
                                ;; always selects the transition — the point is
                                ;; that it DECIDED on the minted value
                                (some? (:replay/gen cofx)))}}
               :actions {;; :go raises [:inner]; :inner's guard requires
                         ;; :replay/gen — NOT in :go's ensure-set, so it is
                         ;; minted mid-drain by ensure-raised-cofx under :live.
                         :raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
               :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
                         :b    {:on {:inner {:target :done :guard :check}}}
                         :done {}}}
            run-start (atom nil)]
        (rf/reg-machine :replay/mint m)
        (trace/register-listener!
          ::cap (fn [ev] (when (= :rf.event/run-start (:operation ev))
                           (reset! run-start ev))))
        ;; LIVE dispatch — only :rf/time-ms in the external token. :replay/gen is
        ;; minted mid-drain by the raised :inner's guard ensure.
        (rf/dispatch-sync [:replay/mint [:go]] {:rf.cofx {:rf/time-ms 111}})
        (trace/unregister-listener! ::cap)

        ;; (1) The mint DID happen and the guard decided on the fresh value.
        (is (= 100 @seen)
            "the raised guard read the MINTED generator-backed fact (mid-drain)")
        (is (= :done (mtest/machine-state :replay/mint))
            "the machine advanced on the minted value")

        ;; (2) THE GAP — the captured run-start replay token does NOT carry the
        ;; minted :replay/gen. It carries only the externally-supplied
        ;; :rf/time-ms. A :strict replay of this epoch would therefore lack the
        ;; fact and diverge (ensure-raised-cofx refuses to mint → missing-
        ;; required → the macrostep that here reached :done would FAIL).
        (let [captured-cofx (:rf.event/cofx (:tags @run-start) (:rf.event/cofx @run-start))]
          (is (some? @run-start) "a run-start trace was captured")
          (is (= {:rf/time-ms 111} captured-cofx)
              "CONFIRMED GAP (rf2-08br0v): the run-start replay token carries
               only the external :rf/time-ms — the in-drain minted :replay/gen
               is absent, so a :strict replay cannot re-present it. When the
               core capture-timing follow-up lands this assertion flips to
               expect :replay/gen 100 in the token, and replay becomes
               deterministic.")
          (is (not (contains? captured-cofx :replay/gen))
              "the minted fact is not (yet) in the captured replay token"))))))
