(ns day8.re-frame2-xray.panels.diff-engine-consistency-cljs-test
  "Empty-collection leaves inside a changed subtree, as every `:diff` lens
  reads them off `day8.re-frame2-xray.diff.engine/project`.

  An empty container (`[]`, `{}`, `#{}`, `'()`) is a terminal leaf: were the
  engine to recurse into it, it would emit no op and the slot would read
  `:same` inside a green `:added` cascade — the one path in the subtree lying
  to the operator. The check is `container?` + `empty?`, not per kind."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.diff.engine :as engine]))

(deftest empty-collection-leaf-removed-direct
  (testing "a wholly-removed empty-collection leaf classifies `:removed`, not `:same`"
    (is (= :removed (engine/op-at (engine/project {:a {:b []}} {}) [:a :b])))))

(deftest empty-collection-leaf-epoch2-witness
  (testing "the live step-deck epoch-2 :rf.db/runtime allocation:
            `:messages []` and `:rf/spawn-counter {}` paint :added alongside
            every other leaf under the wholly-added subtree"
    (let [after {:rf.db/runtime
                 {:rf.runtime/machines
                  {:snapshots
                   {:ws/connection
                    {:state :disconnected
                     :data  {:connections 0
                             :messages    []}
                     :rf/spawn-counter {}}}}}}
          proj (engine/project {} after)]
      (is (= :added (engine/op-at proj [:rf.db/runtime :rf.runtime/machines :snapshots
                                        :ws/connection :data :messages])))
      (is (= :added (engine/op-at proj [:rf.db/runtime :rf.runtime/machines :snapshots
                                        :ws/connection :rf/spawn-counter])))
      (is (= :added (engine/op-at proj [:rf.db/runtime]))
          "the whole :rf.db/runtime subtree is wholly-:added"))))

(deftest empty-collection-leaf-same-container-does-not-inherit
  (testing "an UNCHANGED empty leaf stays `:same`, and its presence does not
            promote a container with one added sibling to wholly-changed"
    (let [proj (engine/project {:a {:b []}} {:a {:b [] :c 1}})]
      (is (= :same (engine/op-at proj [:a :b])))
      (is (= :children (engine/op-at proj [:a]))))))
