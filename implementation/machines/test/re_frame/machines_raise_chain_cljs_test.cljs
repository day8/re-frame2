(ns re-frame.machines-raise-chain-cljs-test
  "CLJS-side coverage for the `:raise` chain (rf2-c0nt) under the Reagent
  reactive substrate.

  Per Spec 005 §`:raise`: an `:action` may return `{:fx [[:raise <event-vec>]]}`
  to re-enter the same machine pre-commit. The handler in `drain-raises`
  recurses through `machine-transition`, so a chain of raises threads through
  multiple transitions before the macrostep commits.

  Pre-fix `drain-raises` looked up `machine-transition` with `(resolve …)`.
  The fix (rf2-c0nt) drops that indirection in favour of a direct call backed
  by an explicit `(declare machine-transition)` forward reference. This test
  pins regression coverage for the :raise-chain path on CLJS so that future
  refactors of `drain-raises` cannot silently break the recursive entry point.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-raise-chain-cljs
  (testing "an action's [:raise <event>] re-enters machine-transition and fires the chained transition (rf2-c0nt)"
    (let [log (atom [])
          machine
          {:initial :idle
           :data    {}
           :actions {:bump-then-raise
                     (fn [_data _ev]
                       (swap! log conj :bump-action)
                       ;; Pre-commit raise — should chain into the :go-2 transition.
                       {:fx [[:raise [:go-2]]]})
                     :landed
                     (fn [_data _ev]
                       (swap! log conj :landed-action)
                       {})}
           :states
           {:idle    {:on {:go-1 {:target :middle :action :bump-then-raise}}}
            :middle  {:on {:go-2 {:target :final  :action :landed}}}
            :final   {}}}]
      (rf/reg-machine :rf2-c0nt/raise-chain machine)
      (rf/dispatch-sync [:rf2-c0nt/raise-chain [:go-1]])
      (is (= :final (:state (snapshot :rf2-c0nt/raise-chain)))
          ":raise chained idle → middle → final in a single macrostep (this is the assertion that fails on main pre-fix because drain-raises' runtime resolve returns nil on CLJS, so the chained transition never fires)")
      (is (= [:bump-action :landed-action] @log)
          "both the originating action and the raised transition's action ran")))

  (testing "a 2-deep :raise chain — three transitions in one macrostep"
    (let [log (atom [])
          mk-action (fn [k raised-ev]
                      (fn [_data _ev]
                        (swap! log conj k)
                        (if raised-ev
                          {:fx [[:raise raised-ev]]}
                          {})))
          machine
          {:initial :s0
           :data    {}
           :actions {:a1 (mk-action :a1 [:e2])
                     :a2 (mk-action :a2 [:e3])
                     :a3 (mk-action :a3 nil)}
           :states  {:s0 {:on {:e1 {:target :s1 :action :a1}}}
                     :s1 {:on {:e2 {:target :s2 :action :a2}}}
                     :s2 {:on {:e3 {:target :s3 :action :a3}}}
                     :s3 {}}}]
      (rf/reg-machine :rf2-c0nt/deep-chain machine)
      (rf/dispatch-sync [:rf2-c0nt/deep-chain [:e1]])
      (is (= :s3 (:state (snapshot :rf2-c0nt/deep-chain)))
          "depth-2 :raise chain reached the terminal state in a single macrostep")
      (is (= [:a1 :a2 :a3] @log)
          "all three actions fired in raise order"))))
