(ns re-frame.machines-initial-cascade-cljs-test
  "CLJS-side coverage for the initial-state cascade on first dispatch
  (rf2-m1tv). Per Spec 005 §Initial-state cascading: when a machine is first
  instantiated and its declared `:initial` lands on a compound state, the
  runtime descends the `:initial` chain to a leaf path on snapshot synthesis.
  Without this, the first event resolves against the wrong state-node level.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-initial-cascade-on-first-dispatch
  (testing "compound :initial chain descends to a leaf on first-dispatch snapshot synthesis (rf2-m1tv)"
    (let [machine
          {:initial :foo
           :data    {}
           :states
           {:foo {:initial :bar
                  :states
                  {:bar {:on {:go :baz}}
                   :baz {}}}}}]
      (rf/reg-machine :rf2-m1tv/flow machine)
      ;; First event: the runtime synthesises the initial snapshot, which
      ;; MUST cascade :foo → :bar to a leaf path. The :go transition is
      ;; declared on :bar, so resolving against the synthesised initial
      ;; snapshot only fires if :state is [:foo :bar].
      (rf/dispatch-sync [:rf2-m1tv/flow [:go]])
      (is (= [:foo :baz] (:state (snapshot :rf2-m1tv/flow)))
          "first-dispatch initial-cascade landed at the leaf and the :go transition fired")))

  (testing "deeper compound :initial chain (three levels) cascades to leaf"
    (let [machine
          {:initial :a
           :data    {}
           :states
           {:a {:initial :b
                :states
                {:b {:initial :c
                     :states
                     {:c {:on {:next :d}}
                      :d {}}}}}}}]
      (rf/reg-machine :rf2-m1tv/deep machine)
      (rf/dispatch-sync [:rf2-m1tv/deep [:next]])
      (is (= [:a :b :d] (:state (snapshot :rf2-m1tv/deep)))
          ":initial chain a→b→c cascaded to leaf; :next transition resolved at :c"))))
