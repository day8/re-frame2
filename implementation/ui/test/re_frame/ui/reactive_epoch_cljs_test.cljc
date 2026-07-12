(ns re-frame.ui.reactive-epoch-cljs-test
  "rf2-vxgfnd.10 (S2d) — epoch coalescing + `flush!` scope over the ViewCell
  notification scheduler (03 §3 invariant 6 'one notification per cell per
  render batch — the boundary is drain quiescence, not epoch close'; Spec 006
  §Epoch finalization). Headless fixtures on the REAL
  observation port + plain-atom sub-cache — the value-movement `on-change`
  watch channel is a reactive-host surface, so these fixtures drive the
  notification seam DIRECTLY (`mark-dirty!` with an epoch tag) exactly the
  way the reactive spine's watch fan-out would, then force with `flush!`.

  The rows:

    - DRAIN COALESCING — N event/frame EPOCHS committed in ONE
      run-to-completion drain advance a cell's revision ONCE, coalescing
      into ONE render batch (the corrected sixth invariant: the render
      boundary is drain quiescence, NOT epoch close; epoch ids are cause
      evidence, never render triggers). A separate later drain notifies
      again — later work stays observable, but NO render count follows from
      the epoch count (replaces the retired false gate `N epochs ⇒ N renders`);
    - flush! SCOPE (the Q51 ruling) — the frame arity `flush-frame!` flushes
      only cells observing that frame; a scoped `flush-scope!` leaves
      out-of-scope cells pending; the global `flush-pending!` /
      `ui.test/flush!` drains every root;
    - NO EPOCH WORK LEAKS ACROSS ROOTS — a per-frame / per-scope flush never
      advances a cell outside its scope;
    - REENTRANCY-SAFE BY CONSTRUCTION — a notify-triggered re-entrant flush
      finds the registry already drained and cannot double-advance (the
      safety the dev-tier `:rf.error/flush-in-open-epoch` signal, whose
      typed throw + Spec 009 catalogue row ride the S2f 009 batch, sits atop);
    - DISCARD ON DISCONNECT/TEARDOWN — an unmounted cell leaves the registry
      without a stale flush;
    - THE SLICE-SCOPED PROBE MEMO — `sub-read` threads one memo per slice, so
      sibling probes compute shared derivation parents once.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node)
  AND `clojure -M:test` (JVM), so the scheduler is graft-checked on both."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]
            [re-frame.ui.test              :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- seed! [id db] (frame/replace-app-db! id db))
(defn- tk [id q] [:sub id q])

(defn- rc!
  "Render (probe) `queries` under frame `id`, then commit — the cell ends
  observing `id`."
  [cell id queries]
  (rf/with-frame id
    (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))
  (reactive/commit! cell)
  cell)

;; ===========================================================================
;; Drain coalescing — N epochs in ONE drain → ONE render batch
;; ===========================================================================

(deftest n-deltas-in-one-epoch-notify-once
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (testing "N sub deltas in ONE epoch (same epoch tag) coalesce to one advance"
      (dotimes [_ 5] (reactive/mark-dirty! cell 7))
      (is (reactive/dirty? cell) "the cell is pending")
      (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 5 deltas")
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell)) "N deltas → ONE revision advance")
      (is (= 1 @hits) "N deltas → ONE notification")
      (is (not (reactive/dirty? cell)))
      (is (= 0 (reactive/pending-cell-count))))
    (testing "a fresh epoch after the flush notifies again (not swallowed)"
      (reactive/mark-dirty! cell 8)
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell)))
      (is (= 2 @hits)))))

(deftest coalescing-is-independent-of-the-epoch-tag-while-pending
  ;; while already pending, ANY further delta folds in — the coalescing
  ;; gate is the pending flag; the epoch tag is evidence, not a second key.
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/mark-dirty! cell 1)
    (reactive/mark-dirty! cell 2)          ;; different tag, still pending
    (reactive/mark-dirty! cell 2)
    (reactive/flush-pending!)
    (is (= 1 (reactive/revision cell)))
    (is (= 1 @hits) "still one notification across the flush boundary")))

(deftest n-epochs-in-one-drain-coalesce-to-one-render-batch
  ;; The corrected sixth invariant, replacing the retired false gate
  ;; "N epochs ⇒ N renders". A run-to-completion drain may settle SEVERAL
  ;; queued events, each committing its OWN epoch record, before the host
  ;; regains control (and flushes). Every one of those epochs folds into ONE
  ;; render batch — the render boundary is DRAIN QUIESCENCE, not epoch close.
  ;; Epoch ids ride the invalidation as CAUSE EVIDENCE only: coalescing keys
  ;; on the pending flag, never on the epoch tag. Render SEPARATION follows
  ;; DRAIN boundaries, never the epoch count.
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (testing "8 distinct epochs committed in ONE drain ⇒ ONE render batch"
      ;; one drain: 8 queued events each commit their own epoch, each firing
      ;; on-change with a DISTINCT epoch tag, all BEFORE the flush that rides
      ;; drain quiescence (the CLJS microtask / the headless explicit flush)
      (doseq [e (range 1 9)] (reactive/mark-dirty! cell e))
      (is (reactive/dirty? cell) "the cell is pending after the drain")
      (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 8 epochs")
      (is (= 1 (reactive/pending-epoch cell))
          "the pending notification stays anchored to the FIRST epoch's
           evidence; later queued epochs fold in without re-anchoring")
      (reactive/flush-pending!)              ;; the drain-quiescence read batch
      (is (= 1 (reactive/revision cell)) "8 epochs in one drain ⇒ ONE revision advance")
      (is (= 1 @hits) "⇒ ONE notification — one render batch, not eight")
      (is (nil? (reactive/pending-epoch cell)) "the evidence tag clears with the flush"))
    (testing "a SEPARATE later drain renders separately — later work stays observable"
      (reactive/mark-dirty! cell 9)
      (is (= 9 (reactive/pending-epoch cell)) "a fresh drain re-anchors the evidence")
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell))
          "render SEPARATION follows DRAIN boundaries, never the epoch count")
      (is (= 2 @hits)))))

;; ===========================================================================
;; flush! scope (the Q51 ruling) + no epoch work leaks across roots
;; ===========================================================================

(deftest scoped-flush-leaves-out-of-scope-cells-pending
  (let [c1 (reactive/make-cell ::c1)
        c2 (reactive/make-cell ::c2)]
    (reactive/mark-dirty! c1 1)
    (reactive/mark-dirty! c2 1)
    (is (= 2 (reactive/pending-cell-count)))
    (testing "a scoped flush advances only the matching cell"
      (is (= 1 (reactive/flush-scope! #(identical? % c1))) "one cell flushed")
      (is (= 1 (reactive/revision c1)))
      (is (= 0 (reactive/revision c2)) "the out-of-scope cell stays put — no leak")
      (is (reactive/dirty? c2))
      (is (= 1 (reactive/pending-cell-count))))
    (testing "the remaining scope flushes independently"
      (reactive/flush-scope! #(identical? % c2))
      (is (= 1 (reactive/revision c2)))
      (is (= 0 (reactive/pending-cell-count))))))

(deftest flush-frame-flushes-only-that-frames-cells
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/make-frame {:id ::f2})
  (seed! fid  {:a 1})
  (seed! ::f2 {:a 2})
  (let [c1 (rc! (reactive/make-cell ::c1) fid   [[:r/a]])
        c2 (rc! (reactive/make-cell ::c2) ::f2  [[:r/a]])]
    (is (= #{(tk fid  [:r/a])} (reactive/committed-target-keys c1)))
    (is (= #{(tk ::f2 [:r/a])} (reactive/committed-target-keys c2)))
    (is (reactive/cell-observes-frame? c1 fid))
    (is (reactive/cell-observes-frame? c2 ::f2))
    (is (not (reactive/cell-observes-frame? c1 ::f2)) "frames are isolated scopes")
    (reactive/mark-dirty! c1 (frame/frame-commit-epoch fid))
    (reactive/mark-dirty! c2 (frame/frame-commit-epoch ::f2))
    (testing "flush-frame! flushes only the cells observing that frame"
      (is (= 1 (reactive/flush-frame! fid)) "only the :rf/default cell")
      (is (= 1 (reactive/revision c1)))
      (is (= 0 (reactive/revision c2)) "the ::f2 cell's epoch work did not leak across roots")
      (is (reactive/dirty? c2)))
    (testing "the other frame flushes on its own scope"
      (is (= 1 (reactive/flush-frame! ::f2)))
      (is (= 1 (reactive/revision c2)))
      (is (= 0 (reactive/pending-cell-count))))))

(deftest global-flush-drains-every-root
  (let [c1 (reactive/make-cell ::c1)
        c2 (reactive/make-cell ::c2)
        c3 (reactive/make-cell ::c3)]
    (doseq [c [c1 c2 c3]] (reactive/mark-dirty! c 1))
    (is (= 3 (reactive/pending-cell-count)))
    (testing "ui.test/flush! is the test-only GLOBAL all-roots spelling"
      (uit/flush!)
      (is (= 1 (reactive/revision c1)))
      (is (= 1 (reactive/revision c2)))
      (is (= 1 (reactive/revision c3)))
      (is (= 0 (reactive/pending-cell-count))))))

;; ===========================================================================
;; Reentrancy — safe by construction (the flush-in-open-epoch safety net)
;; ===========================================================================

(deftest reentrant-flush-during-notify-does-not-double-advance
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)]
    ;; a listener that re-enters the GLOBAL flush from inside its own notify
    (reactive/subscribe cell (fn []
                               (swap! hits inc)
                               (reactive/flush-pending!)))  ;; re-entrant
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (reactive/revision cell))
        "the atomic drain-then-notify means the re-entrant flush sees an
         empty registry — one advance, never two")
    (is (= 1 @hits))))

;; ===========================================================================
;; Discard on disconnect / teardown — no stale flush of an unmounted cell
;; ===========================================================================

(deftest disconnect-discards-pending-notification
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (reactive/mark-dirty! cell 1)
    (is (= 1 (reactive/pending-cell-count)))
    (reactive/disconnect! cell)
    (is (= :disconnected (reactive/lifecycle cell)))
    (is (not (reactive/dirty? cell)) "a disconnected cell holds no pending flush")
    (is (= 0 (reactive/pending-cell-count)) "and leaves the registry")
    (testing "a later global flush does not advance the unmounted cell"
      (reactive/flush-pending!)
      (is (= 0 (reactive/revision cell))))))

(deftest teardown-discards-pending-notification
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (reactive/mark-dirty! cell 1)
    (reactive/teardown! cell)
    (is (= :dead (reactive/lifecycle cell)))
    (is (= 0 (reactive/pending-cell-count)) "teardown drops the pending flush")))

;; ===========================================================================
;; The slice-scoped probe memo — sibling probes share derivation parents
;; ===========================================================================

(deftest slice-memo-shares-derivation-parents-within-one-render
  (let [parent-runs (atom 0)]
    (rf/reg-sub :s/parent (fn [db _] (swap! parent-runs inc) (:n db)))
    (rf/reg-sub :s/a :<- [:s/parent] (fn [n _] [:a n]))
    (rf/reg-sub :s/b :<- [:s/parent] (fn [n _] [:b n]))
    (seed! fid {:n 5})
    (reactive/reset-scheduler!)          ;; a clean slice
    (reset! parent-runs 0)
    (let [cell (reactive/make-cell ::v)
          out  (rf/with-frame fid
                 (reactive/with-capture cell
                   (fn [] [(reactive/sub-read [:s/a])
                           (reactive/sub-read [:s/b])])))]
      (is (= [[:a 5] [:b 5]] out))
      (is (= 1 @parent-runs)
          "sibling cold probes in ONE render compute the shared parent ONCE
           via the slice-scoped memo threaded through sub-read")
      (is (nil? (entry [:s/parent])) "probes stayed ownership-free — no cache node"))))

;; ===========================================================================
;; Bounded invalidation evidence across a coalesced batch (rf2-vxgfnd.46)
;; ===========================================================================
;;
;; The observation port emits a rich constant-work invalidation payload
;; (`:cause`/`:target`/`:node-*`/`:frame-epoch`/`:registry-epoch`). re-frame.ui
;; keeps TWO planes: the production scheduler folds only the pending flag +
;; identity-deduped registry membership; a DEBUG plane accumulates a BOUNDED,
;; constant-size causal summary of the coalesced batch for tooling. Coalescing
;; and evidence stay independent — one dirty enrolment / one render, yet the
;; debug plane retains enough to attribute that render to its contributing
;; movement (first/latest epoch, a cause set, a capped target vector + a
;; dropped-count) WITHOUT forcing a render per epoch.

(deftest n-invalidations-in-one-drain-preserve-bounded-evidence
  ;; N invalidations with DISTINCT epochs fold before one flush: ONE revision
  ;; advance + ONE notification, while the evidence plane summarizes the whole
  ;; batch (first/latest epoch + fold count) and the flush CARRIES it to the
  ;; consumer sink — never a per-epoch render (AC 2/3/5).
  (let [cell (reactive/make-cell ::v)
        hits (atom 0)
        seen (atom [])]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/set-evidence-sink! (fn [c ev] (swap! seen conj [c ev])))
    (doseq [e (range 1 9)] (reactive/mark-dirty! cell e))
    (testing "the pending window's evidence summarizes the whole batch"
      (let [ev (reactive/pending-evidence cell)]
        (is (= 1 (:first-epoch ev)) "anchored to the FIRST epoch")
        (is (= 8 (:latest-epoch ev)) "…and tracks the LATEST")
        (is (= 8 (:count ev)) "all 8 invalidations folded")
        (is (= 0 (:dropped ev)) "nothing dropped")))
    (is (= 1 (reactive/pending-cell-count)) "enrolled ONCE despite 8 marks")
    (reactive/flush-pending!)
    (testing "coalesced to ONE render, and the flush CARRIED the evidence"
      (is (= 1 (reactive/revision cell)) "ONE revision advance")
      (is (= 1 @hits) "ONE notification — one render batch, not eight")
      (is (= 1 (count @seen)) "the sink received the coalesced batch exactly once")
      (is (identical? cell (first (first @seen))))
      (is (= 8 (:count (second (first @seen)))) "…with the full coalesced count")
      (is (nil? (reactive/pending-evidence cell)) "evidence clears with the flush"))))

(deftest evidence-folds-the-real-cause-and-target-from-the-port-payload
  ;; The rich port payload is CONSUMED at on-change, not discarded (AC 1/4). A
  ;; committed cell whose sub is re-registered receives a REAL `:hmr`
  ;; invalidation carrying the moving target; the evidence records both.
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) fid [[:r/a]])]
    (is (nil? (reactive/pending-evidence cell)) "clean before any movement")
    (rf/reg-sub :r/a (fn [db _] (:a db)))   ;; HMR re-registration → real :hmr fan-out
    (let [ev (reactive/pending-evidence cell)]
      (is (reactive/dirty? cell) "the HMR invalidation marked the cell dirty")
      (is (= #{:hmr} (:causes ev)) "the real :hmr cause is recorded, not thrown away")
      (is (= [(tk fid [:r/a])] (:targets ev)) "…with the moving target key")
      (is (= 1 (:count ev))))))

(deftest evidence-target-vector-is-bounded-with-a-loss-account
  ;; MORE distinct moving targets than the cap fold into a CAPPED target vector
  ;; plus an explicit dropped-count — the evidence is constant-size, and
  ;; overflow is reported rather than silently lost (AC 3).
  (let [n       10                          ;; > the target cap (8)
        ids     (mapv (fn [i] (keyword "r" (str "s" i))) (range n))
        queries (mapv vector ids)]
    (doseq [i (range n)]
      (rf/reg-sub (ids i) (fn [db _] (get db i))))
    (seed! fid (into {} (map (fn [i] [i i])) (range n)))
    (let [cell (rc! (reactive/make-cell ::v) fid queries)]
      (doseq [i (range n)]                  ;; re-register each → n distinct :hmr targets
        (rf/reg-sub (ids i) (fn [db _] (get db i))))
      (let [ev (reactive/pending-evidence cell)]
        (is (= n (:count ev)) "every invalidation is counted")
        (is (= 8 (count (:targets ev))) "the target vector is capped at target-cap")
        (is (= 2 (:dropped ev)) "the 2 overflow targets are counted, not silently lost")
        (is (= #{:hmr} (:causes ev)) "the cause set stays bounded"))
      (testing "the whole capped batch still coalesces to ONE render"
        (let [hits (atom 0)]
          (reactive/subscribe cell (fn [] (swap! hits inc)))
          (reactive/flush-dirty! cell)
          (is (= 1 @hits) "one notification for the whole bounded batch"))))))
