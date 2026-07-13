(ns re-frame.ui.tool-evidence-cljs-test
  "rf2-vxgfnd.75 — the first-party tool projection over the ViewCell DEBUG
  invalidation-evidence plane (`re-frame.ui.tool.evidence`).

  The rows:

    - IDENTITY-OWNED LIFECYCLE — install claims the projection for an owner;
      a second owner's install is REJECTED (the first registration is never
      silently cleared); uninstall is owner-checked and releases the sink
      callback plus every retained entry (zero retention when closed);
    - HMR-SAFE — a same-owner re-install is an idempotent re-arm that keeps
      the accumulated evidence (the `:after-load` path), including recovery
      after a fixture `reset-scheduler!` cleared the raw slot;
    - BATCH EVIDENCE, STABLY KEYED — each flushed batch's bounded record
      accretes into one per-cell accumulator carrying first/latest epoch,
      total occurrence count, batch count, the cause union, the bounded
      shown-target sample, and the honest rf2-vxgfnd.74 loss account — keyed
      by a stable projection ordinal + view id, with NO payload retention;
    - CROSS-BATCH LOSS HONESTY — re-delivering the same overflow targets
      across batches never inflates the distinct-omission loss;
    - PRUNING — dead (torn-down) cells disappear from the projection;
    - OBSERVATIONAL — the scheduler's revisions/notifications are untouched
      and no sink escape is recorded (rf2-vxgfnd.73's containment applies).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node)
  AND `clojure -M:test` (JVM), so the projection is graft-checked on both."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.reactive           :as reactive]
            [re-frame.ui.tool.evidence      :as evidence]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(def ^:private fid :rf/default)

(defn- tk [id q] [:sub id q])

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- rc!
  "Render (probe) `queries` under the default frame, then commit — the cell
  ends observing it (the reactive-epoch-test technique for driving REAL
  observation-port fan-out at this tier)."
  [cell queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:tool-ev/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- entry-for
  "The projection row for view id `vid`, or nil."
  [vid]
  (some #(when (= vid (:view-id %)) %) (evidence/projection)))

(def ^:private bounded-evidence-keys
  #{:batches :first-epoch :latest-epoch :count :causes :targets
    :dropped :dropped-exact?})

;; ===========================================================================
;; Identity-owned lifecycle — never clobbers, releases everything
;; ===========================================================================

(deftest install-is-identity-owned-and-never-clobbers
  (is (nil? (evidence/installed-owner)) "unowned at rest")
  (is (true? (evidence/install! ::tool-a)) "a fresh install claims the projection")
  (is (= ::tool-a (evidence/installed-owner)))

  (testing "a SECOND owner's install is rejected — nothing changes (AC3)"
    (is (false? (evidence/install! ::tool-b)))
    (is (= ::tool-a (evidence/installed-owner)) "the first owner still holds it"))

  (testing "…and the first owner's projection is STILL live, not clobbered"
    (let [cell (reactive/make-cell ::v)]
      (reactive/mark-dirty! cell 1)
      (reactive/flush-pending!)
      (is (some? (entry-for ::v)) "the batch reached tool-a's projection")))

  (testing "a non-owner uninstall is refused and releases nothing"
    (is (false? (evidence/uninstall! ::tool-b)))
    (is (= ::tool-a (evidence/installed-owner)))
    (is (some? (entry-for ::v)) "tool-a's accumulated evidence survives"))

  (testing "the owner's uninstall releases the sink AND every entry (AC4)"
    (is (true? (evidence/uninstall! ::tool-a)))
    (is (nil? (evidence/installed-owner)))
    (is (= [] (evidence/projection)) "zero retained cells/evidence")
    ;; the raw slot is free: a flush while uninstalled projects nothing…
    (let [cell (reactive/make-cell ::w)]
      (reactive/mark-dirty! cell 2)
      (reactive/flush-pending!)
      (is (= [] (evidence/projection)) "nothing delivered while uninstalled"))
    ;; …and a different tool can now claim it
    (is (true? (evidence/install! ::tool-b)) "the freed slot is claimable")
    (is (= ::tool-b (evidence/installed-owner)))
    (is (= [] (evidence/projection)) "the new owner starts empty")))

(deftest same-owner-reinstall-is-an-idempotent-rearm-that-keeps-evidence
  ;; The HMR path: `:after-load` re-runs the installing namespace. The
  ;; re-install must neither error, nor clobber, nor lose the accumulation.
  (is (true? (evidence/install! ::tool-a)))
  (let [cell (reactive/make-cell ::v)]
    (doseq [e [1 2 3]] (reactive/mark-dirty! cell e))
    (reactive/flush-pending!)
    (is (= 3 (get-in (entry-for ::v) [:evidence :count])))

    (testing "re-install: true, same owner, evidence KEPT"
      (is (true? (evidence/install! ::tool-a)))
      (is (= ::tool-a (evidence/installed-owner)))
      (is (= 3 (get-in (entry-for ::v) [:evidence :count]))
          "the accumulated evidence survives the re-install"))

    (testing "re-install RE-ARMS after a fixture reset cleared the raw slot"
      ;; `reset-scheduler!` (the test-fixture clean slate) drops the raw
      ;; evidence-sink underneath the tool tier. A same-owner install is the
      ;; documented recovery: it re-arms delivery without losing ownership.
      (reactive/reset-scheduler!)
      (is (true? (evidence/install! ::tool-a)))
      (reactive/mark-dirty! cell 4)
      (reactive/flush-pending!)
      (is (= 4 (get-in (entry-for ::v) [:evidence :count]))
          "delivery resumed into the SAME accumulator (batches accrete)")
      (is (= 2 (get-in (entry-for ::v) [:evidence :batches]))))))

;; ===========================================================================
;; Batch evidence content — stable identity, bounded record, no payloads
;; ===========================================================================

(deftest the-projection-carries-batch-evidence-with-stable-identity
  (is (true? (evidence/install! ::xray)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)
        hits-a (atom 0)]
    (reactive/subscribe cell-a (fn [] (swap! hits-a inc)))
    (doseq [e (range 1 9)] (reactive/mark-dirty! cell-a e))
    (reactive/mark-dirty! cell-b 9)
    (reactive/flush-pending!)

    (testing "one row per cell, in stable ordinal order"
      (let [rows (evidence/projection)]
        (is (= 2 (count rows)))
        (is (= [(:cell-id (first rows)) (:cell-id (second rows))]
               (sort [(:cell-id (first rows)) (:cell-id (second rows))]))
            "rows sort by the stable projection ordinal")
        (is (= #{::va ::vb} (into #{} (map :view-id) rows))
            "each row names its authoring view")))

    (testing "the accumulator exposes the full bounded batch summary (AC2)"
      (let [{:keys [evidence root-id]} (entry-for ::va)]
        (is (= 1 (:first-epoch evidence)) "anchored to the FIRST movement")
        (is (= 8 (:latest-epoch evidence)) "…tracking the LATEST")
        (is (= 8 (:count evidence)) "every occurrence counted")
        (is (= 1 (:batches evidence)) "one flushed batch so far")
        (is (= #{} (:causes evidence)) "epoch-only marks carry no cause")
        (is (= [] (:targets evidence)))
        (is (= #{} (:dropped evidence)) "the rf2-vxgfnd.74 loss field, empty")
        (is (true? (:dropped-exact? evidence)) "…and exact")
        (is (nil? root-id) "no live client root owns a Tier-1 cell")))

    (testing "no payload retention — exactly the bounded keys (AC2)"
      (is (= bounded-evidence-keys
             (set (keys (:evidence (entry-for ::va)))))))

    (testing "a later batch ACCRETES under the SAME identity"
      (let [id-before (:cell-id (entry-for ::va))]
        (doseq [e (range 9 13)] (reactive/mark-dirty! cell-a e))
        (reactive/flush-pending!)
        (let [{:keys [cell-id evidence]} (entry-for ::va)]
          (is (= id-before cell-id) "the projection ordinal is stable")
          (is (= 1 (:first-epoch evidence)) "the anchor never rewrites")
          (is (= 12 (:latest-epoch evidence)))
          (is (= 12 (:count evidence)))
          (is (= 2 (:batches evidence))))
        (is (= 1 (get-in (entry-for ::vb) [:evidence :count]))
            "the sibling row is untouched")))

    (testing "the projection is OBSERVATIONAL (rf2-vxgfnd.73 containment seam)"
      (is (= 2 (reactive/revision cell-a)) "two batches → two advances, as ever")
      (is (= 2 @hits-a) "…and two notifications — scheduling untouched")
      (is (nil? (reactive/last-evidence-sink-escape)) "no contained escape"))))

(deftest real-port-fanout-projects-cause-and-target
  ;; End-to-end through the REAL observation-port fan-out at this tier: an
  ;; HMR re-registration fires each committed site's live lease `on-change`
  ;; with its rich payload; the projection must surface the cause + target.
  (rf/reg-sub :tool-ev/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (is (true? (evidence/install! ::xray)))
  (let [cell (rc! (reactive/make-cell ::v) [[:tool-ev/a]])]
    (rf/reg-sub :tool-ev/a (fn [db _] (:a db)))   ;; re-register → real :hmr fan-out
    (reactive/flush-pending!)
    (let [{:keys [evidence]} (entry-for ::v)]
      (is (= #{:hmr} (:causes evidence)) "the real cause is projected")
      (is (= [(tk fid [:tool-ev/a])] (:targets evidence))
          "…with the moving target key")
      (is (= 1 (:count evidence))))))

;; ===========================================================================
;; Cross-batch loss honesty (the rf2-vxgfnd.74 axis, cumulative tier)
;; ===========================================================================

(deftest cross-batch-loss-stays-honest
  ;; 10 distinct targets overflow the shown cap (8) in ONE window: 2 distinct
  ;; omissions. Re-delivering the SAME 10 in a SECOND batch must not inflate
  ;; the cumulative loss — omission identity is already recorded.
  (let [n       10
        ids     (mapv (fn [i] (keyword "tool-ev" (str "s" i))) (range n))
        queries (mapv vector ids)]
    (doseq [i (range n)]
      (rf/reg-sub (ids i) (fn [db _] (get db i))))
    (seed! (into {} (map (fn [i] [i i])) (range n)))
    (is (true? (evidence/install! ::xray)))
    (let [cell (rc! (reactive/make-cell ::v) queries)]
      (doseq [i (range n)]                  ;; batch 1: 10 distinct :hmr targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (reactive/flush-pending!)
      (let [{:keys [evidence]} (entry-for ::v)]
        (is (= 10 (:count evidence)))
        (is (= 8 (count (:targets evidence))) "shown sample capped")
        (is (= 2 (count (:dropped evidence))) "two distinct omissions")
        (is (true? (:dropped-exact? evidence))))

      ;; a real re-render re-acquires fresh leases on the re-registered
      ;; nodes before any further movement can fan out to this cell
      (rc! cell queries)
      (doseq [i (range n)]                  ;; batch 2: the SAME 10 targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (reactive/flush-pending!)
      (let [{:keys [evidence]} (entry-for ::v)]
        (is (= 20 (:count evidence)) "occurrences keep counting")
        (is (= 2 (:batches evidence)))
        (is (= 8 (count (:targets evidence))) "the shown sample is stable")
        (is (= 2 (count (:dropped evidence)))
            "re-delivered omissions do NOT inflate the distinct loss (not 4)")
        (is (true? (:dropped-exact? evidence))
            "…and the account stays exact")))))

;; ===========================================================================
;; Pruning — dead cells disappear; nothing survives teardown
;; ===========================================================================

(deftest dead-cells-disappear-from-the-projection
  (is (true? (evidence/install! ::xray)))
  (let [cell-a (reactive/make-cell ::va)
        cell-b (reactive/make-cell ::vb)]
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (reactive/flush-pending!)
    (is (= 2 (count (evidence/projection))) "both cells project")

    (reactive/teardown! cell-a)             ;; host/root teardown → :dead
    (let [rows (evidence/projection)]
      (is (= 1 (count rows)) "the dead cell disappeared (AC4)")
      (is (= ::vb (:view-id (first rows)))))

    (testing "uninstall then releases the survivor too — zero retention"
      (is (true? (evidence/uninstall! ::xray)))
      (is (= [] (evidence/projection))))))
