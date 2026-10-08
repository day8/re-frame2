(ns day8.re-frame2-xray.panels.app-db-diff-helpers-cljs-test
  "Pure-data tests for Xray's App-DB Diff panel helpers: the per-path diff
  (`diff-paths`) and the current-state section model
  (`current-state-sections`). View-side wiring is exercised in
  `app_db_diff_cljs_test.cljs` and `app_db_diff_state_cljs_test.cljs`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]))

;; ---- diff-paths ---------------------------------------------------------

(deftest diff-paths-reports-a-single-change-as-one-triple
  (are [before after triple] (= [triple] (h/diff-paths before after))
    {:a 1}      {:a 1 :b 2}  {:op :added    :path [:b] :before nil :after 2}
    {:a 1 :b 2} {:a 1}       {:op :removed  :path [:b] :before 2   :after nil}
    {:a 1}      {:a 2}       {:op :modified :path [:a] :before 1   :after 2}))

(deftest diff-paths-mixed-ops
  (testing "nested maps diff at the leaf path, and the triples are sorted by
            path-as-string for stability"
    (is (= [{:op :modified :path [:cart :items]
             :before [{:id 7 :qty 1}] :after [{:id 7 :qty 1} {:id 22 :qty 1}]}
            {:op :added :path [:cart :totals] :before nil :after {:gross 48}}
            {:op :added :path [:flash] :before nil :after "Welcome"}
            {:op :removed :path [:user/auth] :before :anon :after nil}]
           (h/diff-paths {:cart {:items [{:id 7 :qty 1}]}
                          :user/auth :anon}
                         {:cart  {:items [{:id 7 :qty 1} {:id 22 :qty 1}]
                                  :totals {:gross 48}}
                          :flash "Welcome"})))))

(deftest diff-paths-equal-but-rebuilt-leaf-is-no-change
  (testing "a leaf the handler REBUILT to an `=` value is no change, as the
            runtime's value equality says, not `~ [:todos] X → X`"
    (let [before {:loading? true
                  :todos    [{:id 1 :done false} {:id 2 :done false}]
                  :user     {:roles #{:admin :ops}}}
          ;; a filter that removes nothing, and a set rebuilt with `into`:
          ;; both `=`, neither `identical?`
          after  (-> before
                     (assoc :loading? false)
                     (update :todos #(vec (remove :done %)))
                     (update-in [:user :roles] #(into #{} %)))]
      (is (= [{:op :modified :path [:loading?] :before true :after false}]
             (h/diff-paths before after)))
      (is (= [] (h/diff-paths (:todos before) (:todos after)))
          "the top-level non-map arm applies the same `=` test")))
  (testing "a top-level non-map value that changed is one :modified at []"
    (is (= [{:op :modified :path [] :before [1] :after [2]}]
           (h/diff-paths [1] [2])))))

;; ---- the current-state section model ------------------------------------
;;
;; TOP is app-db minus the reserved `:rf*` keys and always present; each
;; populated runtime area, read from the SEPARATE runtime-db partition at
;; its `:rf.runtime/*` path, is a section — machines / spawned fan out one
;; entry per id, the rest are singletons. Empty areas are omitted.

(defn- area-by [model area]
  (some (fn [a] (when (= area (:area a)) a)) (:areas model)))

(deftest current-state-sections-top-is-user-domain
  (testing "TOP is app-db minus every reserved :rf*-namespaced key; the
            runtime-db partition does not contribute to it"
    (is (= {:cart {:items []} :user "ada"}
           (:top (h/current-state-sections
                   {:cart {:items []} :user "ada" :rf.machine/transient {:x 1}}
                   {:rf.runtime/routing {:current {:route-id :app/home}}}))))))

(deftest current-state-sections-tolerates-whole-redacted-value
  (testing "the `:rf/redacted` scalar an unreachable observed frame fails
            closed to reads as an empty partition, as nil does, rather than
            being iterated"
    (are [app-db runtime-db]
         (= {:top {} :before-top h/no-diff :areas []}
            (h/current-state-sections app-db runtime-db))
      :rf/redacted :rf/redacted
      nil          nil))
  (testing "a whole-redacted pre-image reads as an empty one"
    (is (= {:top {:counter 1} :before-top {} :areas []}
           (h/current-state-sections {:counter 1} {}
                                     {:app :rf/redacted :runtime :rf/redacted})))))

(deftest current-state-sections-enumerates-only-populated-areas
  (is (= #{:rf/machines :rf/route}
         (set (map :area (:areas (h/current-state-sections
                                   {:counter 1}
                                   {:rf.runtime/routing  {:current {:route-id :home}}
                                    :rf.runtime/machines {:snapshots {:auth {:state :idle}}}})))))))

(deftest current-state-sections-machines-fan-out-one-per-instance
  (testing "one instance entry per machine id, sorted by (pr-str id), each
            carrying its own snapshot"
    (is (= {:area      :rf/machines
            :kind      :instances
            :empty?    false
            :instances [{:id :auth       :value {:state :idle}    :before h/no-diff}
                        {:id :title/flow :value {:state :playing} :before h/no-diff}]}
           (area-by (h/current-state-sections
                      {} {:rf.runtime/machines {:snapshots {:title/flow {:state :playing}
                                                            :auth       {:state :idle}}}})
                    :rf/machines)))))

(deftest current-state-sections-route-is-singleton
  (let [route {:route-id :app/article :params {:id "A"}
               :query {} :fragment nil :transition :idle
               :error nil :nav-token "nav-1"}]
    (is (= {:area :rf/route :kind :singleton :empty? false :value route :before h/no-diff}
           (area-by (h/current-state-sections {} {:rf.runtime/routing {:current route}})
                    :rf/route)))))

(deftest current-state-sections-area-order-is-stable
  (testing "with every runtime subsystem populated, the areas render in
            `reserved-area-order`"
    (let [runtime-db {:rf.runtime/machines {:snapshots  {:auth {:state :idle}}
                                            :spawned    {:parent {:invoke :child}}}
                      :rf.runtime/routing  {:current             {:route-id :home}
                                            :pending-navigation  {:to :next}}
                      :rf.runtime/elision  {:declarations {}}}]
      (is (= h/reserved-area-order
             (mapv :area (:areas (h/current-state-sections {} runtime-db))))))))

;; ---- inline-diff section model (spec/021 §4.3) ---------------------------
;;
;; The 3-arity threads a `{:app .. :runtime ..}` pre-image, so each section
;; carries a `:before` for the inline `← changed` annotation; a slice absent
;; from the pre-image reads the `added` sentinel.

(deftest current-state-sections-3-arity-top-before-is-prior-user-domain
  (is (= {:top {:counter 2} :before-top {:counter 1} :areas []}
         (h/current-state-sections {:counter 2} {} {:app {:counter 1} :runtime {}}))))

(deftest current-state-sections-3-arity-instance-before-is-prior-snapshot
  (let [rt-before {:rf.runtime/machines {:snapshots {:title/flow {:state :idle}}}}
        rt-after  {:rf.runtime/machines {:snapshots {:title/flow {:state :loaded}
                                                     :auth       {:state :idle}}}}]
    (is (= [{:id :auth       :value {:state :idle}   :before h/added}
            {:id :title/flow :value {:state :loaded} :before {:state :idle}}]
           (:instances (area-by (h/current-state-sections {} rt-after
                                                          {:app {} :runtime rt-before})
                                :rf/machines))))))

(deftest current-state-sections-3-arity-singleton-before-is-prior-slice
  (let [rt-before {:rf.runtime/routing {:current {:route-id :home}}}
        rt-after  {:rf.runtime/routing {:current            {:route-id :cart}
                                        :pending-navigation {:to :checkout}}}
        model     (h/current-state-sections {} rt-after {:app {} :runtime rt-before})]
    (is (= [{:route-id :home} {:route-id :cart} h/added]
           [(:before (area-by model :rf/route))
            (:value (area-by model :rf/route))
            (:before (area-by model :rf/pending-navigation))]))))

(deftest current-state-sections-3-arity-nil-before-safe
  (testing "a nil app pre-image (the boot epoch) diffs against {}"
    (is (= {} (:before-top (h/current-state-sections
                             {:counter 1}
                             {:rf.runtime/routing {:current {:route-id :home}}}
                             {:app nil :runtime nil}))))))

;; ---- a whole-section removal stays visible --------------------------------
;;
;; In diff mode the ids walked are the union of both sides: a before-only
;; instance or slot carries the `removed` sentinel as its `:value` and its
;; prior state as `:before`, and an area this epoch emptied survives the
;; empty-area filter.

(deftest current-state-sections-destroyed-instance-reads-removed
  (let [rt-before {:rf.runtime/machines {:snapshots {:door/main {:state :open}
                                                     :other     {:state :idle}}}}
        rt-after  {:rf.runtime/machines {:snapshots {:other {:state :idle}}}}]
    (is (= [{:id :door/main :value h/removed       :before {:state :open}}
            {:id :other     :value {:state :idle}  :before {:state :idle}}]
           (:instances (area-by (h/current-state-sections {} rt-after
                                                          {:app {} :runtime rt-before})
                                :rf/machines))))))

(deftest current-state-sections-area-emptied-this-epoch-survives
  (testing "the ONLY machine destroyed: the area survives because the
            pre-image carried state"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/machines {:snapshots {}}}
                  {:app {} :runtime {:rf.runtime/machines {:snapshots {:door/main {:state :open}}}}})]
      (is (= [[:door/main h/removed {:state :open}]]
             (mapv (juxt :id :value :before) (:instances (area-by model :rf/machines)))))))
  (testing "a cleared pending-navigation reads as a `removed` singleton"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/routing {:current {:id :home}}}
                  {:app {} :runtime {:rf.runtime/routing {:current            {:id :home}
                                                          :pending-navigation {:to :app/settings}}}})]
      (is (= [h/removed {:to :app/settings}]
             ((juxt :value :before) (area-by model :rf/pending-navigation))))))
  (testing "a slot emptied to a PRESENT `{}` diffs as itself, not as a removal"
    (let [model (h/current-state-sections
                  {} {:rf.runtime/routing {:pending-navigation {}}}
                  {:app {} :runtime {:rf.runtime/routing {:pending-navigation {:to :x}}}})]
      (is (= [{} {:to :x}]
             ((juxt :value :before) (area-by model :rf/pending-navigation)))))))

(deftest current-state-sections-removal-needs-a-pre-image
  (testing "control — without a pre-image an empty area is omitted, and an
            area empty on BOTH sides stays omitted in diff mode"
    (is (= [] (:areas (h/current-state-sections {} {:rf.runtime/machines {:snapshots {}}})))
        "no-diff mode: no removal claim to make")
    (is (= [] (:areas (h/current-state-sections
                        {} {} {:app {} :runtime {:rf.runtime/routing {:pending-navigation {}}}})))
        "empty before AND after: still omitted")))
