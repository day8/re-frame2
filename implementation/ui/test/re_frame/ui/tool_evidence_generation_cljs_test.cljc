(ns re-frame.ui.tool-evidence-generation-cljs-test
  "rf2-vxgfnd.147 — generation fencing of the evidence-projection
  lifecycle, the DETERMINISTIC single-thread half (runs on node AND the
  JVM; the two-thread latch races live in
  `tool_evidence_generation_race_jvm_test.clj`).

  The delayed-delivery replay technique: a race fixture captures the
  armed sink closure exactly the way `deliver-flush!` does (deref at
  delivery start — here `evidence/installed-sink`), lets the lifecycle
  move on (uninstall / reinstall / force-release), then invokes the
  captured closure as the DELAYED callback. Under the pre-.147 multi-atom
  publication every replay below corrupts the projection (repopulates an
  ownerless one, contaminates a successor's, or survives an ABA
  reinstall); under generation fencing each is a proven no-op.

  Also pins the CLJS-reachable reentrant/HMR generation cases: a
  mid-batch owner change delivers the REMAINING batch through the
  successor's armed generation (the documented legal linearization), and
  a same-owner re-arm keeps the span (generation continuity = evidence
  continuity)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]
            [re-frame.ui.tool.evidence     :as evidence]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(defn- entry-for [vid]
  (some #(when (= vid (:view-id %)) %) (evidence/projection)))

(def ^:private ev-1
  ;; one bounded batch record, as `fold-evidence` would deliver it
  {:first-epoch 1 :latest-epoch 1 :count 1
   :causes #{} :targets [] :dropped #{} :dropped-exact? true})

;; ===========================================================================
;; Race (a), single-thread replay: a delayed callback after uninstall must
;; not repopulate the ownerless projection (closed-retains-zero)
;; ===========================================================================

(deftest delayed-delivery-after-uninstall-is-fenced-out
  (is (true? (evidence/install! ::tool-a)))
  (let [cell    (reactive/make-cell ::v)
        delayed (evidence/installed-sink)]   ;; the deliver-flush! deref
    (is (some? delayed) "the armed closure is captured at delivery start")
    (is (true? (evidence/uninstall! ::tool-a)))
    ;; the delayed callback resumes AFTER the uninstall closed the span
    (delayed cell ev-1)
    (is (nil? (evidence/installed-owner)))
    (is (= [] (evidence/projection))
        "the ownerless projection stays EMPTY — the stale generation
         cannot repopulate it (red under unchecked publication)")))

(deftest delayed-delivery-after-force-release-is-fenced-out
  ;; the fixture-reset door must fence exactly like an owner uninstall —
  ;; the HMR/fixture generation case
  (is (true? (evidence/install! ::tool-a)))
  (let [cell    (reactive/make-cell ::v)
        delayed (evidence/installed-sink)]
    (evidence/force-release!)
    (delayed cell ev-1)
    (is (= [] (evidence/projection))
        "force-release! closes the generation; the in-flight delivery
         publishes nothing")))

;; ===========================================================================
;; Race (b), single-thread replay: A's late callback cannot contaminate
;; owner B's fresh projection
;; ===========================================================================

(deftest stale-owner-a-delivery-cannot-contaminate-owner-b
  (is (true? (evidence/install! ::tool-a)))
  (let [cell-a  (reactive/make-cell ::va)
        stale-a (evidence/installed-sink)]
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (true? (evidence/install! ::tool-b)))
    ;; B accretes real evidence through the CURRENT armed path
    (let [cell-b (reactive/make-cell ::vb)]
      (reactive/mark-dirty! cell-b 7)
      (reactive/flush-pending!)
      (is (= 1 (count (evidence/projection))) "B's fresh projection: one row")
      ;; A's delayed callback resumes under B's ownership
      (stale-a cell-a ev-1)
      (let [rows (evidence/projection)]
        (is (= 1 (count rows))
            "B's projection is uncontaminated — no ::va row appeared")
        (is (= ::vb (:view-id (first rows))))
        (is (= 1 (get-in (entry-for ::vb) [:evidence :count]))
            "…and B's own accumulator is untouched")))))

;; ===========================================================================
;; ABA safety: uninstall + reinstall of the SAME logical owner id is a NEW
;; generation — owner equality is not a reusable token
;; ===========================================================================

(deftest same-owner-reinstall-is-a-new-generation-not-an-aba-token
  (is (true? (evidence/install! ::tool-a)))
  (let [cell-old (reactive/make-cell ::v-old)
        sink-1   (evidence/installed-sink)]  ;; captured in incarnation 1
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (true? (evidence/install! ::tool-a)) "the same logical id re-claims")
    ;; incarnation 2 accretes real evidence
    (let [cell-new (reactive/make-cell ::v-new)]
      (reactive/mark-dirty! cell-new 3)
      (reactive/flush-pending!)
      (is (some? (entry-for ::v-new)))
      ;; incarnation 1's delayed callback replays — SAME owner id, CLOSED
      ;; generation: bare owner equality would re-admit it (the ABA hole)
      (sink-1 cell-old ev-1)
      (is (nil? (entry-for ::v-old))
          "the first incarnation's delivery is fenced out — generation,
           not owner equality, is the admission token (ABA-safe)")
      (is (= 1 (count (evidence/projection)))))))

(deftest same-owner-rearm-keeps-the-span-and-its-inflight-deliveries
  ;; the OTHER side of the ABA coin: an HMR re-arm (no uninstall) is the
  ;; SAME continuous ownership span — a delivery captured before the
  ;; re-arm remains valid, exactly as the accumulated evidence does
  (is (true? (evidence/install! ::tool-a)))
  (let [cell     (reactive/make-cell ::v)
        pre-arm  (evidence/installed-sink)]
    (is (true? (evidence/install! ::tool-a)) "the :after-load re-arm")
    (pre-arm cell ev-1)
    (is (= 1 (get-in (entry-for ::v) [:evidence :count]))
        "the pre-re-arm delivery published — generation continuity is
         evidence continuity within one ownership span")))

;; ===========================================================================
;; Stale-teardown protection (the sequential half of race (c)) — the JVM
;; latch fixture drives the concurrent half
;; ===========================================================================

(deftest stale-a-uninstall-cannot-release-owner-b
  (is (true? (evidence/install! ::tool-a)))
  (is (true? (evidence/uninstall! ::tool-a)))
  (is (true? (evidence/install! ::tool-b)))
  (let [cell (reactive/make-cell ::vb)]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (false? (evidence/uninstall! ::tool-a))
        "A's replayed teardown is refused against B's ownership")
    (is (= ::tool-b (evidence/installed-owner)) "B's owner token stands")
    (is (= 1 (count (evidence/projection))) "B's entries stand")
    ;; …and B's SINK was not disarmed: a further real flush still delivers
    (reactive/mark-dirty! cell 2)
    (reactive/flush-pending!)
    (is (= 2 (get-in (entry-for ::vb) [:evidence :count]))
        "delivery continues — the stale uninstall touched nothing")))

;; ===========================================================================
;; Reentrant owner change MID-BATCH: the documented legal linearization —
;; the remaining batch delivers through the CURRENT armed generation
;; ===========================================================================

(deftest reentrant-midbatch-uninstall-stops-all-further-delivery
  ;; a phase-2 listener closes the projection while the batch is
  ;; mid-delivery: `deliver-flush!` derefs the slot per cell, so every
  ;; delivery at or after the close finds either a disarmed slot or a
  ;; closed generation — nothing publishes, nothing is retained
  (is (true? (evidence/install! ::tool-a)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)]
    (reactive/subscribe cell-a (fn [] (evidence/uninstall! ::tool-a)))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (reactive/flush-batch-in-order! [cell-a cell-b])
    (is (nil? (evidence/installed-owner)))
    (is (= [] (evidence/projection))
        "the reentrant close retains zero — no post-close delivery lands")
    (is (nil? (reactive/last-evidence-sink-escape))
        "…and the handover raised no contained escape")))

(deftest reentrant-midbatch-owner-change-linearizes-to-the-successor
  ;; the documented LEGAL linearization of a mid-batch handover:
  ;; A-close → B-open → every subsequent delivery (including the cell
  ;; whose own listener performed the handover — its sink deref happens
  ;; AFTER its listeners ran) publishes through B's armed closure at B's
  ;; open generation. B owns those rows; A retains nothing.
  (is (true? (evidence/install! ::tool-a)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)]
    (reactive/subscribe cell-a (fn []
                                 (evidence/uninstall! ::tool-a)
                                 (evidence/install! ::tool-b)))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (reactive/flush-batch-in-order! [cell-a cell-b])
    (is (= ::tool-b (evidence/installed-owner)))
    (is (= #{::va ::vb} (into #{} (map :view-id) (evidence/projection)))
        "both deliveries linearized AFTER B's install and published into
         B's projection — no delivery published into the closed A span")
    (is (nil? (reactive/last-evidence-sink-escape)))))
