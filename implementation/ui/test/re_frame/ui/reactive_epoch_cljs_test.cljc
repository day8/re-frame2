(ns re-frame.ui.reactive-epoch-cljs-test
  "rf2-vxgfnd.10 (S2d) — epoch coalescing + `flush!` scope over the ViewCell
  notification scheduler (03 §3 invariant 6 'epoch close notifies each dirty
  cell once'; Spec 006 §Epoch finalization). Headless fixtures on the REAL
  observation port + plain-atom sub-cache — the value-movement `on-change`
  watch channel is a reactive-host surface, so these fixtures drive the
  notification seam DIRECTLY (`mark-dirty!` with an epoch tag) exactly the
  way the reactive spine's watch fan-out would, then force with `flush!`.

  The rows:

    - EPOCH COALESCING — N sub deltas within one epoch advance a cell's
      revision ONCE (the sixth frozen invariant); a fresh epoch after the
      flush notifies again;
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
;; Epoch coalescing — N deltas within one epoch → ONE notification
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
