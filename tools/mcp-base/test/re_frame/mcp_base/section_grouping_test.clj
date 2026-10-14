(ns re-frame.mcp-base.section-grouping-test
  "Tests for the path-headed cluster projection of a diff-encode patch
  list at the MCP wire boundary."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.section-grouping :as rf.mcp-base.section-grouping]))

(def ^:private group rf.mcp-base.section-grouping/group-patches-into-sections)

(deftest empty-patches-emit-no-sections
  (is (= [] (group []) (group nil))))

(deftest root-replacement-projects-to-one-root-section
  (let [patches [[[] :assoc {:new :db}]]]
    (is (= [{:section-path [] :section-kind :modified :patches patches}] (group patches)))))

(deftest deep-singleton-promotes-to-parent-breadcrumb
  (let [patches [[[:user :prefs :theme] :assoc :dark]]]
    (is (= [{:section-path [:user :prefs] :section-kind :modified :patches patches}] (group patches)))))

(deftest siblings-under-common-prefix-coalesce-into-one-section
  ;; The head is the longest common prefix, not just its first segment.
  (let [patches [[[:cart :items 0 :discount] :assoc 0.1] [[:cart :items 0 :qty] :assoc 2]]]
    (is (= [{:section-path [:cart :items 0] :section-kind :modified :patches patches}] (group patches)))))

(deftest cluster-coalescence-respects-max-depth
  ;; Two leaves whose common ancestor [:a] sits 5 levels away stay apart
  ;; under the default budget of 3, and merge once the budget is raised.
  (let [patches [[[:a :b :c :d :e :leaf1] :assoc 1]
                 [[:a :B :C :D :E :leaf2] :assoc 2]]]
    (is (= 2 (count (group patches))))
    (is (= 1 (count (group patches {:max-coalesce-depth 10}))))))

(deftest cart-cascade-projects-to-3-cart-and-non-cart-sections
  ;; The three [:cart ...] patches sit within depth 3 of [:cart] and
  ;; coalesce there; [:user :last-edited-at] promotes to [:user]; [:flash]
  ;; is a top-level singleton and stays put.
  (let [patches [[[:cart :items 0 :qty]   :assoc 3]
                 [[:cart :totals :line]   :assoc 30]
                 [[:cart :totals :grand]  :assoc 33]
                 [[:user :last-edited-at] :assoc 12345]
                 [[:flash]                :assoc "Cart updated"]]]
    (is (= [[[:cart] :modified 3] [[:flash] :modified 1] [[:user] :modified 1]]
           (mapv (juxt :section-path :section-kind (comp count :patches)) (group patches))))))

(deftest section-kind-classification
  ;; `:assoc` covers both an insert and a change, so `:added` needs
  ;; :db-before to prove the container was absent; a stored nil counts
  ;; as present.
  (let [assocs [[[:user :name] :assoc "ada"] [[:user :email] :assoc "ada@example.com"]]]
    (are [patches opts kind] (= kind (:section-kind (first (group patches opts))))
      [[[:user :token] :dissoc] [[:user :session-id] :dissoc]] nil :removed
      [[[:user :name] :assoc "ada"] [[:user :token] :dissoc]]   nil :modified
      assocs nil                                                   :modified
      assocs {:db-before {}}                                       :added
      assocs {:db-before {:user {:name "bob"}}}                    :modified
      assocs {:db-before {:user nil}}                              :modified)))

(deftest sections-preserve-all-patches
  ;; Every input patch lands in exactly one section.
  (let [patches [[[:a :x] :assoc 1]
                 [[:a :y] :assoc 2]
                 [[:b :p] :assoc 3]
                 [[:b :q] :dissoc]
                 [[:c]    :assoc :singleton]]]
    (is (= (sort-by pr-str patches)
           (sort-by pr-str (rf.mcp-base.section-grouping/sections->patches (group patches)))))))

(deftest sections-are-sorted-ascending-by-final-section-path
  ;; Coalescing and singleton promotion shorten a section's path after the
  ;; walk-order sort, and an ancestor's pr-str sorts after a sibling that
  ;; extends its name ("[:cart]" > "[:cart-summary]"), so the output is
  ;; re-sorted by the final path.
  (is (= [[:cart-summary] [:cart]]
         (mapv :section-path (group [[[:cart :qty] :assoc 5] [[:cart-summary] :assoc :new]])))))
