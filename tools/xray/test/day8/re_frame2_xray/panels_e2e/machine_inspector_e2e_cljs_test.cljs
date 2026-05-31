(ns day8.re-frame2-xray.panels-e2e.machine-inspector-e2e-cljs-test
  "Multi-frame e2e coverage for the Machine Inspector panel
  (rf2-7icrs, spec/017 — Machines row).

  The Machine Inspector panel reads three cross-cutting sources:

    1. `:rf.xray/registered-machines` — the set of machine ids the
       host has registered via `rf/reg-machine`.
    2. `:rf.xray/machine-snapshots` — the current snapshots for
       every machine, keyed by `:machine-id`.
    3. `:rf.xray/machine-transitions-for-focused-event` — the
       focused-event lens: the per-machine-transition records the
       chart-render path projects out of the FOCUSED epoch's
       `:trace-events` window.

  Bug class this catches:

  - rf2-hwuki — `:rf.machine/transition` emit dropped the `:frame`
    tag, which meant Xray's epoch-capture filter rejected the event
    and the Machine Inspector panel stayed empty even after a real
    machine transition fired. After rf2-hwuki lands (PR #1596), the
    transition tag is present and the panel reflects machine
    activity. Pre-#1596 this test catches the regression.

  - rf2-6pdr3 — the rf2-w06op machine_epochs worker found the
    chart-render path (`:rf.xray/machine-transitions-for-focused-event`)
    STILL appeared empty for real host-app machines and had to assert
    machine features via the snapshot mirror instead. Investigation
    (this bead) confirmed the framework `:frame`-tag fix (rf2-hwuki) is
    in place and the whole emit → epoch-capture → focused-event-lens
    chain is wired — but EVERY existing test of the lens injected
    synthetic `:trace-events` via the `:rf.xray/set-epoch-history-for-test`
    seam, so the FULL real pipeline (a real `reg-machine` transition
    flowing through real epoch capture into the lens) was never locked.
    The promised `machine-inspector-reflects-transition` test (named in
    this file's original docstring) was never written. The two
    `machine-inspector-focused-event-lens-*` tests below close that gap:
    they drive a REAL `[:deep/main [:work/go]]` transition through the
    real trace-bus + epoch-capture pipeline (NO injection seam) and
    assert the focused-event lens populates with the real transition
    record — the exact contract the chart-render path depends on."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deep-machine :as deep-machine]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-registered-machines-includes-host-machine
  (testing ":rf.xray/registered-machines reflects host's reg-machine call"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install!}
      (fn []
        (let [machines (e2e/sub-xray [:rf.xray/registered-machines])]
          (is (contains? (set machines) :deep/main)
              ":deep/main missing from :rf.xray/registered-machines"))))))

(deftest xray-machine-snapshots-include-host-machine-post-bootstrap
  (testing ":rf.xray/machine-snapshots carries :deep/main after bootstrap"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [snapshots (e2e/sub-xray [:rf.xray/machine-snapshots])]
          (is (map? snapshots)
              ":rf.xray/machine-snapshots is not a map")
          (is (contains? snapshots :deep/main)
              ":deep/main not in machine-snapshots post-bootstrap")
          (is (= :idle (get-in snapshots [:deep/main :state]))
              ":deep/main's initial state should be :idle after bootstrap"))))))

(deftest xray-machine-inspector-data-resolves-shape
  (testing ":rf.xray/machine-inspector-data composite resolves"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (let [data (e2e/sub-xray [:rf.xray/machine-inspector-data])]
          (is (map? data)
              ":rf.xray/machine-inspector-data did not return a map"))))))

;; ---- focused-event lens populates for a REAL machine (rf2-6pdr3) ---------
;;
;; The chart-render contract end-to-end: a real `reg-machine` transition
;; MUST surface in `:rf.xray/machine-transitions-for-focused-event` after
;; flowing through the REAL pipeline — trace-bus → epoch-capture (the
;; `:frame`-tag admission gate) → the focused epoch's `:trace-events` →
;; the focused-event lens. NO `:rf.xray/set-epoch-history-for-test`
;; injection seam: this is the gap rf2-6pdr3 reported (every prior lens
;; test injected synthetic trace events, so the real path was never locked
;; and the rf2-w06op deck saw an empty chart for real machines).

(deftest machine-inspector-focused-event-lens-populates-for-real-transition
  (testing "rf2-6pdr3 — a REAL `[:deep/main [:work/go]]` transition flows
            through the real trace-bus + epoch-capture pipeline and surfaces
            in `:rf.xray/machine-transitions-for-focused-event` (NO injection
            seam). This is the chart-render path's load-bearing contract —
            empty here means the Machine Inspector chart is blank in prod."
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        ;; Real host dispatch: drives :deep/main idle -> active. `dispatch-host`
        ;; settles the cascade, then syncs the framework's epoch ring (with
        ;; the captured `:rf.machine/transition` trace event) into Xray's
        ;; `:epoch-history` slot exactly as the production epoch-collector does.
        (e2e/dispatch-host [:deep/main [:work/go]])
        (let [records (e2e/sub-xray
                        [:rf.xray/machine-transitions-for-focused-event])]
          (is (vector? records)
              "lens returns a vector")
          (is (= 1 (count records))
              "exactly one machine transition fired in the focused cascade
               window — proves the transition trace was captured into the
               focused epoch's :trace-events (the rf2-hwuki :frame-tag gate)")
          (let [rec (first records)]
            (is (= :deep/main (:machine-id rec))
                "the focused-event record names the real host machine")
            (is (= :idle (:from-state rec))
                "from-state is the pre-transition leaf")
            (is (= :active (:to-state rec))
                "to-state is the post-transition leaf — the real engine ran")
            (is (= [:work/go] (:event rec))
                "the record carries the real driving event vector")))))))

(deftest machine-inspector-focused-event-lens-tracks-second-real-transition
  (testing "rf2-6pdr3 — a second real transition (`:work/reset`) re-fires the
            lens with the NEW cascade's transition record; proves the lens
            tracks the live focus across real cascades, not a one-shot
            capture artefact"
    (e2e/with-host-and-xray-frames
      {:install-host deep-machine/install-and-init!}
      (fn []
        (e2e/dispatch-host [:deep/main [:work/go]])
        (let [first-records (e2e/sub-xray
                              [:rf.xray/machine-transitions-for-focused-event])]
          (is (= :active (:to-state (first first-records)))
              "first cascade: idle -> active"))
        ;; Second real transition: active -> idle on :work/reset.
        (e2e/dispatch-host [:deep/main [:work/reset]])
        (let [second-records (e2e/sub-xray
                              [:rf.xray/machine-transitions-for-focused-event])]
          (is (= 1 (count second-records))
              "the second cascade's focused-event window has its own one
               transition record")
          (is (= :active (:from-state (first second-records)))
              "second cascade from-state: active")
          (is (= :idle (:to-state (first second-records)))
              "second cascade to-state: idle — focus flipped to the new
               real cascade and the lens re-fired with its transition"))))))
