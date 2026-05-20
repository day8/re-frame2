(ns re-frame.machines-tags-cljs-test
  "CLJS-side coverage for `:fsm/tags` — state tags (rf2-ee0d Nine States
  Stage 1) under the Reagent reactive substrate.

  Per Spec 005 §State tags: a state-node body may declare `:tags
  <set-of-keywords>`. The runtime maintains the union of every active
  state's tag set at `[:rf/machines <id> :tags]` in the snapshot and ships
  `:rf/machine-has-tag?` + the `rf/machine-has-tag?` sugar to query it.

  Concerns covered:
    - Flat machine: snapshot's `:tags` is the active state's tag set.
    - Compound machine: `:tags` is the union along the active path.
    - No-declaration machine: empty union elided from snapshot.
    - `:rf/machine-has-tag?` sub + `rf/machine-has-tag?` sugar; false for unknown
      machine.
    - `:tags` reflects the post-`:always`-microstep state.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-tags-flat-active-state-cljs
  (testing "flat machine — snapshot's :tags is the active state's tag set"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:tags #{:idle/empty :active}
                                 :on   {:fetch :loading}}
                       :loading {:tags #{:loading :transient :active}
                                 :on   {:done :complete}}
                       :complete {:tags #{:done :terminal}}}}]
      (rf/reg-machine :tags/flat m)
      (rf/dispatch-sync [:tags/flat [:fetch]])
      (is (= #{:loading :transient :active}
             (:tags (snapshot :tags/flat))))
      (rf/dispatch-sync [:tags/flat [:done]])
      (is (= #{:done :terminal}
             (:tags (snapshot :tags/flat)))))))

(deftest machine-tags-compound-union-cljs
  (testing "compound machine — :tags is the union along the active path"
    (let [m {:initial :authed
             :data    {}
             :states
             {:authed
              {:tags    #{:auth :gated}
               :initial :dash
               :states  {:dash {:tags #{:home}
                                :on   {:nav-settings :settings}}
                         :settings {:tags #{:settings :writable}}}}}}]
      (rf/reg-machine :tags/compound m)
      (rf/dispatch-sync [:tags/compound [:nav-settings]])
      (is (= [:authed :settings] (:state (snapshot :tags/compound))))
      (is (= #{:auth :gated :settings :writable}
             (:tags (snapshot :tags/compound)))))))

(deftest machine-tags-no-declaration-elided-cljs
  (testing "no-tags machine — :tags slot is elided from the snapshot"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:on {:go :b}}
                       :b {}}}]
      (rf/reg-machine :tags/empty m)
      (rf/dispatch-sync [:tags/empty [:go]])
      (let [s (snapshot :tags/empty)]
        (is (= :b (:state s)))
        (is (not (contains? s :tags))
            "empty union elided from snapshot per Spec 005 §Snapshot shape change")))))

(deftest machine-tags-has-tag-sub-cljs
  (testing ":rf/machine-has-tag? returns true iff the snapshot's :tags contains tag"
    (let [m {:initial :loading
             :data    {}
             :states  {:loading {:tags #{:loading :transient}
                                 :on   {:done :resolved}}
                       :resolved {:tags #{:done}}}}]
      (rf/reg-machine :tags/sub m)
      (rf/dispatch-sync [:tags/sub [:no-op]])
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading])))
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :transient])))
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :done])))
      ;; rf/machine-has-tag? sugar resolves through the framework sub.
      (is (= true  @(rf/machine-has-tag? :tags/sub :loading)))
      (is (= false @(rf/machine-has-tag? :tags/sub :done)))
      (rf/dispatch-sync [:tags/sub [:done]])
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading])))
      (is (= true  @(rf/machine-has-tag? :tags/sub :done)))))

  (testing ":rf/machine-has-tag? returns false for an unknown machine"
    (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/unknown :anything])))))

(deftest machine-tags-recomputed-on-always-cljs
  (testing ":tags reflects the post-:always-microstep state, not the intermediate"
    (let [m {:initial :asking
             :data    {:n 0}
             :guards  {:enough? (fn [d _] (>= (:n d) 1))}
             :actions {:bump   (fn [d _] {:data (update d :n inc)})}
             :states  {:asking
                       {:tags   #{:active}
                        :always [{:guard :enough? :target :winner}]
                        :on     {:tick {:action :bump}}}
                       :winner {:tags #{:terminal :celebrate}}}}]
      (rf/reg-machine :tags/always m)
      (rf/dispatch-sync [:tags/always [:tick]])
      (is (= :winner (:state (snapshot :tags/always))))
      (is (= #{:terminal :celebrate}
             (:tags (snapshot :tags/always)))))))
