(ns re-frame.machines-wildcard-fallthrough-cljs-test
  "Per Spec 005 §Transition resolution / §Wildcard transitions.
  CORRECTNESS coverage for the CROSS-KEY explicit→`:*` fallthrough rule.

  The invariant: `:*` is the LEAST-PRIORITY ENABLED transition at
  its level — NOT a 'no explicit KEY exists' fallback. A guard-blocked
  explicit `:on` entry must fall through to the same-level `:*`, and if no
  same-level wildcard is enabled, resolution walks to the parent (whose own
  explicit→`:*` resolution then repeats), down to a genuine no-op only when
  NO enabled transition exists anywhere. This matches XState v5's
  transition-selection order (descend priority within a state before
  walking to its ancestor). Exercised through `reg-machine` /
  `dispatch-sync`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---------------------------------------------------------------------------
;; (b) guard-blocked explicit AND no enabled same-level :* → parent :* fires
;; ---------------------------------------------------------------------------

(deftest guard-blocked-explicit-falls-through-to-parent-wildcard
  (testing "blocked leaf explicit + guard-blocked leaf :* → walks to parent :*"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit   (tag :leaf-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}
             :states
             ;; leaf's explicit AND its :* are both guard-blocked.
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}
                               :*   {:guard :never :action :leaf-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/parent-wild-blocked-leaf-star machine)
      (rf/dispatch-sync [:icj9t/parent-wild-blocked-leaf-star [:foo]])
      (is (= [:parent-wildcard] @log)
          "both leaf candidates blocked ⇒ leaf yields nothing ⇒ parent :* fires;
           neither guard-blocked leaf candidate fires"))))

;; ---------------------------------------------------------------------------
;; (e) compound coverage — leaf blocked explicit, leaf :*, parent :*
;; ---------------------------------------------------------------------------

(deftest compound-explicit-blocked-prefers-leaf-wildcard-over-parent
  (testing "compound: blocked leaf explicit falls through to LEAF :* before the parent"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-explicit   (tag :leaf-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:* {:action :parent-wildcard}}        ;; parent :* exists
             :states
             ;; leaf has a guard-blocked explicit AND an ENABLED :*.
             {:dashboard {:on {:foo {:guard :never :action :leaf-explicit}
                               :*   {:action :leaf-wildcard}}}}}}}]
      (rf/reg-machine :icj9t/compound machine)
      ;; Within-level priority: leaf explicit blocked → leaf :* (enabled)
      ;; fires; the leaf level is satisfied so the walk stops — parent :*
      ;; is shadowed.
      (rf/dispatch-sync [:icj9t/compound [:foo]])
      (is (= [:leaf-wildcard] @log)
          "leaf :* fired before any parent walk — same-level priority;
           neither the blocked leaf explicit nor the parent :* fired"))))
