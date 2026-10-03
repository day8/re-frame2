(ns re-frame.machines-tags-cljs-test
  "CLJS-side coverage for `:fsm/tags` — state tags (the Nine States pattern)
  under the Reagent reactive substrate.

  Per Spec 005 §State tags: a state-node body may declare `:tags
  <set-of-keywords>`. The runtime maintains the union of every active
  state's tag set at `[:rf.runtime/machines :snapshots <id> :tags]` in the snapshot and ships
  the `:rf.machine/has-tag?` framework sub to query it.

  Covered here: the `:rf.machine/has-tag?` sub read through a Reagent
  reaction, and false for an unknown machine. The snapshot `:tags` union
  itself (flat, compound, elided when empty, recomputed after an `:always`
  microstep) is tags-test's, and machine-property-cljs-test's
  prop-tags-is-active-configuration-union runs the projection invariant on
  both hosts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest machine-tags-has-tag-sub-cljs
  (testing ":rf.machine/has-tag? returns true iff the snapshot's :tags contains tag"
    (let [m {:initial :loading
             :data    {}
             :states  {:loading {:tags #{:loading :transient}
                                 :on   {:done :resolved}}
                       :resolved {:tags #{:done}}}}]
      (rf/reg-machine :tags/sub m)
      (rf/dispatch-sync [:tags/sub [:no-op]])
      (is (= true  @(rf/subscribe [:rf.machine/has-tag? :tags/sub :loading])))
      (is (= true  @(rf/subscribe [:rf.machine/has-tag? :tags/sub :transient])))
      (is (= false @(rf/subscribe [:rf.machine/has-tag? :tags/sub :done])))
      (rf/dispatch-sync [:tags/sub [:done]])
      (is (= false @(rf/subscribe [:rf.machine/has-tag? :tags/sub :loading])))
      (is (= true  @(rf/subscribe [:rf.machine/has-tag? :tags/sub :done])))))

  (testing ":rf.machine/has-tag? returns false for an unknown machine"
    (is (= false @(rf/subscribe [:rf.machine/has-tag? :tags/unknown :anything])))))
