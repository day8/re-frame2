(ns re-frame.standard-epochs-diamond-recompute-cljs-test
  "The DIAMOND redundant-recompute probe the standard-epochs testbed
  (`tools/xray/testbeds/standard_epochs/core.cljs`, test-free) demonstrates,
  asserted on a copy of its `:standard-epochs/diamond-*` subs under the same
  ids; the testbed is never loaded:

         :diamond-root          (L1 — reads :views/diamond-root)
           /        \\
    :diamond-a    :diamond-b    (L2 — each reads the root)
           \\        /
         :diamond-c             (the JOINING sub over a,b)

  The join sub `:diamond-c` increments a plain (non-ratom) counter each
  time its compute fn RUNS. Bump the root ONCE: the counter should rise by
  exactly 1 (clean); a rise of 2 means the substrate recomputes the join
  sub TWICE per single root change (the push-based diamond redundant-
  recompute).

  Counting recomputes needs the PUSH path, an EAGER consumer plus a flush:
  a single lazy deref pulls the join exactly once and cannot reveal a
  double-compute. So a `reagent.ratom/run!` reaction stands in for a
  mounted view."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ===========================================================================
;; The standard-epochs diamond
;; ===========================================================================
;;
;; `diamond-c-runs` is a plain atom, an input to no sub, so the swap! cannot
;; feed back into the graph; it is reset per measurement.

(def diamond-c-runs (atom 0))

(defn- register-diamond-subs! []
  (rf/reg-sub :standard-epochs/diamond-root          ;; L1
    (fn [db _] (get-in db [:views :diamond-root])))

  (rf/reg-sub :standard-epochs/diamond-a             ;; L2 — left arm
    {:inputs [[:standard-epochs/diamond-root]]}
    (fn [[root] _] (* 10 (or root 0))))

  (rf/reg-sub :standard-epochs/diamond-b             ;; L2 — right arm
    {:inputs [[:standard-epochs/diamond-root]]}
    (fn [[root] _] (inc (or root 0))))

  (rf/reg-sub :standard-epochs/diamond-c             ;; join — c = a + b
    {:inputs [[:standard-epochs/diamond-a] [:standard-epochs/diamond-b]]}
    (fn [[a b] _]
      (swap! diamond-c-runs inc)
      (+ a b))))

(defn- register-diamond-events! []
  (rf/reg-event :standard-epochs/seed
    (fn [{:keys [db]} _] {:db {:views {:diamond-root 0}}}))
  ;; Button #24 — bump :views/diamond-root once.
  (rf/reg-event :standard-epochs/bump-diamond
    (fn [{:keys [db]} _] {:db (update-in db [:views :diamond-root] (fnil inc 0))})))

;; ===========================================================================
;; The diamond join recomputes EXACTLY ONCE per single root change
;; ===========================================================================

(deftest diamond-join-recomputes-once
  (testing "with an EAGER consumer of the join sub mounted, a single root
   bump pushes EXACTLY ONE recompute through the join node"
    (register-diamond-subs!)
    (register-diamond-events!)
    (rf/dispatch-sync [:standard-epochs/seed])

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
