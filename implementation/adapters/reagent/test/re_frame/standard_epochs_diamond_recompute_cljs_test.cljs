(ns re-frame.standard-epochs-diamond-recompute-cljs-test
  "Per rf2-xxf8x — substrate contract coverage for the standard-epochs
  Reactive-substrate section's DIAMOND redundant-recompute probe. The
  automated regression companion to the visual diamond probe (rf2-kt5nx,
  PR #2569) and the live measurement that found it clean (the join sub
  recomputes 1× per single root change at both the raw-Reagent and the
  re-frame reg-sub layers).

  The standard-epochs testbed
  (`tools/xray/testbeds/standard_epochs/core.cljs`) is test-free (locked,
  rf2-8cevm): its button #24 demonstrates the behaviour for Xray +
  re-frame2-pair inspection. The hard ASSERTION lives here — the substrate
  subs contract suite — sibling to
  `re-frame.standard-epochs-views-subs-lifecycle-cljs-test`.

  The diamond (byte-for-byte the testbed's `:standard-epochs/diamond-*`
  subs, registered under the same ids, so a drift between the testbed and
  this contract surfaces as a failure here):

         :diamond-root          (L1 — reads :views/diamond-root)
           /        \\
    :diamond-a    :diamond-b    (L2 — each :<- root)
           \\        /
         :diamond-c             (the JOINING sub :<- a,b)

  The join sub `:diamond-c` increments a plain (non-ratom) counter each
  time its compute fn RUNS. Bump the root ONCE: the counter should rise by
  exactly 1 (clean); a rise of 2 means the substrate recomputes the join
  sub TWICE per single root change (the push-based diamond redundant-
  recompute).

  THE METHODOLOGICAL TWIST (the bead is explicit — do not get this wrong).
  To COUNT recomputes you must exercise the PUSH path, which needs an
  EAGER consumer + a flush. A single lazy deref always pulls the join node
  exactly once and CANNOT reveal a double-compute (this was the first
  wrong measurement during the live investigation). So we stand a real
  mounted view in with `reagent.ratom/run!` (an auto-running reaction that
  eagerly derefs `:diamond-c`), reset the counter, dispatch ONE root
  change, `flush!` the reaction queue (the push), and assert the counter
  rose by EXACTLY 1.

  `diamond-c-runs` is a plain atom (NOT a ratom) reset before each
  measurement, and is NOT an input to any sub, so the `swap!` cannot feed
  back into the reactive graph.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ===========================================================================
;; The standard-epochs diamond, replicated verbatim from the testbed
;; ===========================================================================
;;
;; A classic reactive DIAMOND. `diamond-c-runs` is a plain atom (not a
;; ratom) and is NOT an input to any sub, so the swap! cannot feed back
;; into the graph. It is reset per measurement, not per ns-load, hence a
;; plain `def` (the testbed uses `defonce` for its long-lived instrument).

(def diamond-c-runs (atom 0))

(defn- register-diamond-subs! []
  (rf/reg-sub :standard-epochs/diamond-root          ;; L1
    (fn [db _] (get-in db [:views :diamond-root])))

  (rf/reg-sub :standard-epochs/diamond-a             ;; L2 — left arm
    :<- [:standard-epochs/diamond-root]
    (fn [root _] (* 10 (or root 0))))

  (rf/reg-sub :standard-epochs/diamond-b             ;; L2 — right arm
    :<- [:standard-epochs/diamond-root]
    (fn [root _] (inc (or root 0))))

  (rf/reg-sub :standard-epochs/diamond-c             ;; join — c = a + b
    :<- [:standard-epochs/diamond-a]
    :<- [:standard-epochs/diamond-b]
    (fn [[a b] _]
      (swap! diamond-c-runs inc)
      (+ a b))))

(defn- register-diamond-events! []
  (rf/reg-event-db :standard-epochs/seed
    (fn [_ _] {:views {:diamond-root 0}}))
  ;; Button #24 — bump :views/diamond-root once.
  (rf/reg-event-db :standard-epochs/bump-diamond
    (fn [db _] (update-in db [:views :diamond-root] (fnil inc 0)))))

;; ===========================================================================
;; The diamond join recomputes EXACTLY ONCE per single root change
;; ===========================================================================

(deftest diamond-join-recomputes-once
  (testing "rf2-xxf8x: with an EAGER consumer of the join sub mounted
   (an auto-running `ratom/run!` reaction standing in for a mounted view),
   a single root bump pushes EXACTLY ONE recompute through the join node —
   no push-based diamond double-compute. Exercising the push path (eager
   consumer + flush!) is load-bearing: a single lazy deref pulls the join
   once and cannot reveal a double-compute."
    (register-diamond-subs!)
    (register-diamond-events!)
    (rf/dispatch-sync [:standard-epochs/seed])

    ;; EAGER consumer ~ a mounted view: an auto-running reaction that
    ;; derefs the join sub. This puts the join node ON the push path, so a
    ;; redundant intermediate recompute would show up as a second run.
    (let [driver (ratom/run! (deref (rf/subscribe [:standard-epochs/diamond-c])))]
      (try
        (ratom/flush!)
        (is (= 1 @(rf/subscribe [:standard-epochs/diamond-c]))
            "precondition: at seeded root 0, c = a + b = (10×0) + (0+1) = 1")

        ;; --- ONE root bump → exactly ONE join recompute -----------------
        (reset! diamond-c-runs 0)
        (rf/dispatch-sync [:standard-epochs/bump-diamond])  ;; root 0 → 1
        (ratom/flush!)                                       ;; the push
        (is (= 1 @diamond-c-runs)
            "join recomputes EXACTLY once per single root change — no diamond double-compute")

        ;; --- N root bumps → N recomputes (not 2N) -----------------------
        (reset! diamond-c-runs 0)
        (dotimes [_ 5]
          (rf/dispatch-sync [:standard-epochs/bump-diamond])
          (ratom/flush!))
        (is (= 5 @diamond-c-runs)
            "5 root bumps → 5 join recomputes (not 10) — no double-compute under repetition")
        (finally
          (ratom/dispose! driver))))))
