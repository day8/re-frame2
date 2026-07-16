(ns re-frame.ui.substrate-flush-render-convergence-cljs-test
  "rf2-0faipl — protect the FIRST-PARTY ADAPTER's `flush-render!`
  (`re-frame.ui.substrate/ui-flush-render!`) ADAPTER-ONLY branches through the
  REAL assembled adapter, not just the shared `reactive/converge-flush!`
  primitive.

  The adapter's synchronous render-commit has two branches no existing fixture
  exercised (the only adapter browser fixture performs one ordinary
  invalidation; the open-drain fixtures exercise only `ui.test/flush!`):

    1. the SHARED open-drain guard call — a flush forced inside a still-open
       event drain must throw `:rf.error/flush-in-open-epoch` BEFORE publishing
       any pending ViewCell (removing the guard would let it publish a
       partially-settled read side); and
    2. the commit-triggered RE-DIRTY loop, now bounded by
       `reactive/converge-flush!` — a non-quiescent cascade must fail loud with
       `:rf.error/flush-convergence-exceeded` (removing the loop would silently
       leave cells pending and never converge / never diagnose).

  These fixtures drive the adapter's `:flush-render!` directly (assembled with
  trivial root injections) and pin all four acceptance behaviours: the bound
  trip, a one-shot commit-triggered follow-up that returns quiescent, the
  in-open-epoch rejection before publication, and a throwing thunk that
  propagates unchanged without publishing pending work.

  CLJS-only (`.cljs`, node-runnable — NOT DOM-gated): the adapter's
  `flush-render!` is `react-dom/flushSync`, which runs its callback and flushes
  synchronously even with no mounted root, so the branch logic is exercised
  headlessly under `test:ui` / `test:cljs`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]
            [re-frame.ui.substrate         :as substrate]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private adapter-flush-site 're-frame.ui.substrate/flush-render!)

(defn- adapter-flush!
  "The first-party adapter's real `:flush-render!` (= `ui-flush-render!`),
  assembled with trivial root injections (the flush path never consults them)."
  []
  (:flush-render! (substrate/adapter-with-client-roots
                    (fn [thunk] (thunk))
                    (fn [] nil))))

(defn- ex-of [thunk]
  (try (thunk) nil (catch cljs.core/ExceptionInfo e e)))

;; ===========================================================================
;; Branch 2 — the commit-triggered re-dirty loop is BOUNDED
;; ===========================================================================

(deftest non-quiescent-cascade-trips-the-adapter-bound
  (reactive/reset-scheduler!)
  (let [flush! (adapter-flush!)
        cell   (reactive/make-cell ::runaway)]
    ;; A listener that re-marks the cell on EVERY publication — the registry can
    ;; never quiesce across `flushSync` passes.
    (reactive/subscribe cell (fn [] (reactive/mark-dirty! cell 3)))
    (let [ex   (ex-of #(flush! (fn [] (reactive/mark-dirty! cell 1))))
          data (some-> ex ex-data)]
      (is (some? ex)
          "the adapter's re-dirty loop is BOUNDED — a runaway fails loud, never spins")
      (is (= :rf.error/flush-convergence-exceeded (:rf.error/id data)))
      (is (= reactive/flush-convergence-budget (:passes data)))
      (is (pos? (:pending data)))
      (is (= adapter-flush-site (:where data))
          "the diagnostic names the first-party adapter's flush-render! site"))))

(deftest one-shot-commit-triggered-followup-returns-quiescent
  (reactive/reset-scheduler!)
  (let [flush! (adapter-flush!)
        cell   (reactive/make-cell ::one-shot)
        again  (atom false)]
    ;; A legit one-shot follow-up (a layout effect that dispatches on first
    ;; commit): the listener re-marks the cell exactly once.
    (reactive/subscribe cell
      (fn [] (when (compare-and-set! again false true)
               (reactive/mark-dirty! cell 2))))
    (is (nil? (flush! (fn [] (reactive/mark-dirty! cell 1))))
        "the adapter flush returns nil once settled")
    (testing "ordinary multi-pass convergence settles — zero pending, final state committed"
      (is (= 0 (reactive/pending-cell-count)) "no cell left pending")
      (is (false? (reactive/dirty? cell)))
      (is (= 2 (reactive/revision cell))
          "revision advanced twice — the initial publish + the drained follow-up"))))

;; ===========================================================================
;; Branch 1 — the open-drain guard rejects BEFORE any publication
;; ===========================================================================

(deftest flush-inside-open-event-drain-throws-before-publication
  (reactive/reset-scheduler!)
  (let [flush! (adapter-flush!)
        cell   (reactive/make-cell ::pending)]
    (reactive/mark-dirty! cell 1)
    (is (= 1 (reactive/pending-cell-count)) "a cell is pending before the flush")
    (let [ex (binding [frame/*run-frame-state-before* {:rf/open-drain true}
                       frame/*current-frame*          nil]
               ;; A flush forced from inside a still-open run-to-completion drain.
               (ex-of #(flush! (fn [] (reactive/mark-dirty! cell 2)))))]
      (is (= :rf.error/flush-in-open-epoch (:rf.error/id (ex-data ex)))
          "the shared open-drain guard rejects the in-epoch flush")
      (is (= adapter-flush-site (:where (ex-data ex)))
          "and names the adapter flush-render! site"))
    (testing "the guard threw BEFORE touching the registry — nothing was published"
      (is (= 1 (reactive/pending-cell-count))
          "the pending cell was NOT flushed inside the open drain")
      (is (true? (reactive/dirty? cell)))
      (is (= 0 (reactive/revision cell)) "no revision advanced"))))

;; ===========================================================================
;; A throwing thunk propagates unchanged WITHOUT publishing pending work
;; ===========================================================================

(deftest throwing-thunk-propagates-and-does-not-publish
  (reactive/reset-scheduler!)
  (let [flush! (adapter-flush!)
        cell   (reactive/make-cell ::pending)]
    (reactive/mark-dirty! cell 1)
    (let [ex (ex-of #(flush! (fn [] (throw (ex-info "write boom" {:rf/probe 7})))))]
      (is (some? ex) "the thunk's throw propagates out of flush-render!")
      (is (= "write boom" (ex-message ex)) "…unchanged")
      (is (= 7 (:rf/probe (ex-data ex)))))
    (testing "the pending publication never ran (the thunk threw before flush-pending!)"
      (is (= 1 (reactive/pending-cell-count)) "the pre-marked cell is still pending")
      (is (true? (reactive/dirty? cell)))
      (is (= 0 (reactive/revision cell)) "no cell was published"))))
