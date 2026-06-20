(ns re-frame.machine-raise-cofx-replay-test
  "Replay-determinism of a generator-backed cofx fact MINTED by a
  same-macrostep RAISED event's guard/action.

  ## The mechanism

  `drain-to-fixed-point` re-runs `ensure-raised-cofx` per dequeued raise.
  Under `:live` it MINTS a fresh generator-backed recordable fact
  mid-macrostep when a raise-selected guard's `:rf.cofx/requires` is NOT in
  the external event's ensure-set. The minted value is written onto the
  engine-local machine def's `:rf/cofx` and threads forward IN the drain (so a
  later raise / `:always` re-presents it — in-drain consistency).

  The epoch's `:rf.cofx` replay token is captured at `:rf.event/run-start`
  (`re-frame.epoch.capture/find-trigger-event` reads the run-start trace's
  `:rf.event/cofx`), which the router emits from `assemble-initial-ctx`'s
  coeffects BEFORE the handler runs. A state machine's outer event handler
  declares no `:rf.cofx/requires` (they live on guards/actions), so
  `assemble-initial-ctx` mints NOTHING for it — and the machine's own ensure
  steps (`ensure-ctx-cofx` pre-drain, `ensure-raised-cofx` in-drain) run INSIDE
  the handler, AFTER run-start. So the run-start TRACE carries only the
  externally-supplied facts (e.g. `:rf/time-ms`), never the machine-minted ones.

  ## How replay stays deterministic

  `re-frame.epoch.capture/find-trigger-event` merges the cascade's
  `:rf.cofx/generated` mint traces (every actual recordable mint emits one,
  carrying the marks-projected produced value) onto the run-start replay token
  at epoch-ASSEMBLY time. The run-start TRACE itself remains the PRE-handler
  token — so this namespace's run-start-trace assertion below observes only
  `:rf/time-ms` (that is the trace's contract; the augmentation is an
  epoch-read concern). The end-to-end determinism proof against the assembled
  epoch record lives in the epoch artefact
  (`re-frame.machine-minted-cofx-replay-token-test`), whose test classpath
  carries epoch.

  ## What this namespace proves (machines surface, no epoch dep)

  (1) the in-drain MINT happens and the guard decides on it under `:live`;
  (2) the run-start TRACE carries only the external `:rf/time-ms` — documenting
      WHY the epoch-assembly merge is needed (the trace is the pre-handler
      token, so the captured-token augmentation cannot come from the trace
      emit and must be reconstructed from the mint traces);
  (3) the replay-determinism CONTRACT end-to-end: re-presenting the minted fact
      in the token under `:strict` reproduces the live decision (reaches :done),
      while WITHOUT it `:strict` refuses to mint and the machine does NOT
      advance — so the captured-token fact is necessary AND sufficient for a
      deterministic strict replay.

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

;; The shared machine spec. `:go`'s action raises `[:inner]`; `:inner`'s guard
;; requires the generator-backed `:replay/gen`, NOT in `:go`'s ensure-set, so it
;; is minted mid-drain by `ensure-raised-cofx` under `:live`. `seen` captures
;; the value the guard read so the test can prove WHICH value drove the decision.
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

(deftest live-mints-the-raised-guard-fact-and-trace-is-pre-handler-token
  (testing "rf2-08br0v / rf2-cheez6.1 — a raise-selected guard fact MINTED
   mid-macrostep under :live drives the machine decision; the run-start TRACE
   carries only the external :rf/time-ms (the pre-handler token), which is WHY
   the epoch-assembly merge reconstructs the minted facts from the mint traces."
    ;; A generator-backed recordable cofx minting a distinct, stable value.
    (let [seen      (atom ::unset)
          run-start (atom nil)]
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/mint (mint-machine seen))
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

      ;; (2) The run-start TRACE is the PRE-handler token — only :rf/time-ms.
      ;; This is the trace's contract (the augmentation is an epoch-read
      ;; concern, see the epoch artefact's companion test), and it documents
      ;; WHY epoch assembly reconstructs the minted facts from the cascade's
      ;; mint traces rather than from the run-start emit.
      (let [captured-cofx (:rf.event/cofx (:tags @run-start))]
        (is (some? @run-start) "a run-start trace was captured")
        (is (= {:rf/time-ms 111} captured-cofx)
            "the run-start TRACE carries only the external :rf/time-ms — the
             in-drain minted :replay/gen is reconstructed at epoch assembly,
             not stamped onto this trace")))))

(deftest strict-replay-with-the-minted-fact-is-deterministic
  (testing "rf2-cheez6.1 — the determinism CONTRACT: re-presenting the minted
   :replay/gen in the token under :strict (the replay policy) reproduces the
   live decision — the guard reads the recorded value verbatim (no host re-mint)
   and the machine reaches :done. This is what the captured-token fix makes
   possible end-to-end."
    (let [seen (atom ::unset)]
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/strict (mint-machine seen))
      ;; The token that the epoch fix captures: the external fact PLUS the
      ;; mid-drain minted fact. Under :strict the present-on-token fact is
      ;; delivered verbatim (no mint), so the guard fires and the machine
      ;; reaches :done — the live decision, reproduced.
      (rf/dispatch-sync [:replay/strict [:go]]
                        {:rf.cofx {:rf/time-ms 111 :replay/gen 100}
                         :rf.cofx/mint-policy :strict})
      (is (= 100 @seen)
          "STRICT delivered the recorded :replay/gen verbatim — same value the
           live run's guard read")
      (is (= :done (mtest/machine-state :replay/strict))
          "STRICT replay reproduced the live decision deterministically"))))

(deftest strict-replay-without-the-minted-fact-diverges-control
  (testing "rf2-cheez6.1 CONTROL — the SAME :strict dispatch with the PRE-FIX
   token (only :rf/time-ms, no :replay/gen) does NOT advance: strict refuses to
   mint the absent generator-backed fact → missing-required → the raised
   macrostep fails atomically. This is the divergence the captured-token fix
   removes; together with the test above it shows the token fact is necessary
   AND sufficient for a deterministic strict replay."
    (let [seen (atom ::unset)]
      (rf/reg-cofx :replay/gen {:recordable? true} (fn [] 100))
      (rf/reg-machine :replay/strict-nofact (mint-machine seen))
      (rf/dispatch-sync [:replay/strict-nofact [:go]]
                        {:rf.cofx {:rf/time-ms 111}
                         :rf.cofx/mint-policy :strict})
      (is (not= :done (mtest/machine-state :replay/strict-nofact))
          "strict refused to mint the absent :replay/gen → the machine did NOT
           reach :done (the pre-fix replay divergence)"))))
