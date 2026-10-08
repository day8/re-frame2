(ns re-frame.tags-test
  "Per Spec 005 §State tags (the Nine States pattern), through the live
  runtime: `:tags` is recomputed after an `:always` microstep, and the
  `:rf.machine/has-tag?` framework sub reads it. The pure tag union (flat,
  compound, elided when empty, print/read round-trip) is pinned by the
  conformance fixtures spec/conformance/fixtures/tags-*.edn."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest tags-recomputed-on-always-microstep
  (rf/reg-machine :tags/always
    {:initial :asking
     :data    {:correct-count 0}
     :guards  {:enough? (fn [{d :data}] (>= (:correct-count d) 1))}
     :actions {:bump (fn [{d :data}] {:data (update d :correct-count inc)})}
     :states  {:asking {:tags   #{:active}
                        :always [{:guard :enough? :target :winner}]
                        :on     {:answer-correct {:action :bump}}}
               :winner {:tags #{:terminal :celebrate}}}})
  (rf/dispatch-sync [:tags/always [:answer-correct]])
  (is (= {:state :winner :tags #{:terminal :celebrate}}
         (select-keys (rf.machines.test-support/snapshot :tags/always) [:state :tags]))))

(deftest machine-has-tag-sub
  (testing ":rf.machine/has-tag? returns true iff :tags contains the tag"
    (rf/reg-machine :tags/sub
      {:initial :idle
       :data    {}
       :states  {:idle     {:tags #{:loading :transient}
                            :on   {:done :resolved}}
                 :resolved {:tags #{:done}}}})
    (rf/dispatch-sync [:tags/sub [:no-op]])
    (let [has? (fn [tag] @(rf/subscribe [:rf.machine/has-tag? :tags/sub tag]))]
      (is (= [true false] (mapv has? [:loading :done])))
      (rf/dispatch-sync [:tags/sub [:done]])
      (is (= [false true] (mapv has? [:loading :done])))))
  (testing ":rf.machine/has-tag? returns false for an unknown machine"
    (is (= false @(rf/subscribe [:rf.machine/has-tag? :tags/unknown-machine :x])))))
