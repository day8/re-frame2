(ns re-frame.story.author-expectations-test
  "Tests for the pure expectation-authoring substrate (spec/021 §S5,
  spec/019): the catalog, atom builders, cost projection and snippet the
  dialog, assertion strip and palette entry depend on. JVM-only: no CLJS
  build's ns-regexp selects a `-test` namespace."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [re-frame.story.assertions          :as rf.story.assertions]
            [re-frame.story.author-expectations :as rf.story.author-expectations]))

;; ===========================================================================
;; CATALOG — covers the five acceptance surfaces
;; ===========================================================================

(deftest catalog-covers-the-acceptance-surfaces
  (testing "every authorable kind names a recognised canonical assertion id"
    (doseq [{:keys [kind assertion-id]} rf.story.author-expectations/expectation-kinds]
      (is (rf.story.assertions/assertion-id-known? assertion-id)
          (str kind " folds onto a known assertion id"))))
  (testing "app-db / subscriptions / DOM / schema / browser are all authorable"
    (is (= #{:app-db :subscriptions :dom :schema :browser}
           (into #{} (map :surface) rf.story.author-expectations/expectation-kinds)))))

;; ===========================================================================
;; OPERAND PARSING + ATOM CONSTRUCTION — builds the CANONICAL vocabulary
;; ===========================================================================

(deftest expectation->atom-builds-canonical-atoms
  (doseq [[row expected]
          [[{:kind :app-db-equals :operands {:path ":open?" :expected "true"}}
            [:rf.assert/path-equals [:open?] true]]
           [{:kind :sub-equals :operands {:query-v "[:counter/value]" :expected "5"}}
            [:rf.assert/sub-equals [:counter/value] 5]]
           [{:kind :app-db-matches :operands {:path "[:user]" :schema "[:map [:id :int]]"}}
            [:rf.assert/path-matches [:user] [:map [:id :int]]]]
           [{:kind :no-warnings :operands {}}
            [:rf.assert/no-warnings]]
           [{:kind :schema-error :operands {:where-spec "{:where :event :event :user/save}"}}
            [:rf.assert/schema-error {:where :event :event :user/save}]]
           [{:kind :schema-error :operands {}}
            [:rf.assert/schema-error]]
           [{:kind :app-db-equals :operands {:path "[:a" :expected "5"}}
            nil]]]
    (is (= expected (rf.story.author-expectations/expectation->atom row)) (pr-str row))))

(deftest parse-operands-reports-per-field-errors
  (testing "ok? false + a per-field error when an operand is blank"
    (let [{:keys [ok? errors]}
          (rf.story.author-expectations/parse-operands
            {:kind :app-db-equals :operands {:path "[:a]" :expected ""}})]
      (is (not ok?))
      (is (contains? errors :expected))))
  (testing "a malformed EDN operand carries the reader error string"
    (let [{:keys [ok? errors]}
          (rf.story.author-expectations/parse-operands
            {:kind :sub-equals :operands {:query-v "[:a" :expected "1"}})]
      (is (not ok?))
      (is (string? (:query-v errors))))))

;; ===========================================================================
;; RUNNER COST / :cannot-run BEFORE SAVE — reads the requirement registry
;; ===========================================================================

(deftest expectation-cost-reads-the-requirement-registry
  (testing "a sub-equals expectation needs :pure-subs but still runs headless"
    (let [cost (rf.story.author-expectations/expectation-cost [:rf.assert/sub-equals [:s] 1])]
      (is (= {:required #{:app-db :pure-subs} :cheapest-runner :headless :headless? true :missing #{}}
             (dissoc cost :cannot-run?)))
      (is (not (:cannot-run? cost)))))
  (testing "a DOM expectation CANNOT run headless — visible before save"
    (let [cost (rf.story.author-expectations/expectation-cost [:rf.assert/dom-text ".x" "y"])]
      (is (= {:required #{:dom} :cheapest-runner :dom :headless? false :missing #{:dom}}
             (dissoc cost :cannot-run?)))
      (is (:cannot-run? cost)))))

;; ===========================================================================
;; DRAFT SUMMARY — the before-save honesty banner data
;; ===========================================================================

(deftest draft-summary-aggregates-cost-and-surfaces
  (let [summary (rf.story.author-expectations/draft-summary
                  {:rows [{:row-id 0 :kind :app-db-equals
                           :operands {:path "[:a]" :expected "1"}}
                          {:row-id 1 :kind :dom-text
                           :operands {:selector "\".x\"" :text "\"y\""}}
                          {:row-id 2 :kind :app-db-equals
                           :operands {:path "" :expected "1"}}]})]  ; not ready
    (testing "counts, ready atoms, the required union, the one runner proving the
              whole draft, and the surfaces the authored kinds span"
      (is (= {:count           3
              :ready           2
              :atoms           [[:rf.assert/path-equals [:a] 1]
                                [:rf.assert/dom-text ".x" "y"]]
              :required        #{:app-db :dom}
              :cheapest-runner :dom
              :surfaces        #{:app-db :dom}}
             (dissoc summary :cannot-run-rows))))
    (testing "cannot-run-rows lists the DOM row — the honest before-save list"
      (is (= [[:rf.assert/dom-text ".x" "y"]] (mapv :atom (:cannot-run-rows summary)))))))

;; ===========================================================================
;; SNIPPET — expectations become EXPLICIT variant DATA (the round-trip)
;; ===========================================================================

(deftest merge-assertions-is-additive-and-dedupes
  (testing "an exact duplicate is dropped (re-authoring is idempotent)"
    (is (= [[:rf.assert/path-equals [:a] 1]]
           (rf.story.author-expectations/merge-assertions
             [[:rf.assert/path-equals [:a] 1]]
             [[:rf.assert/path-equals [:a] 1]])))))

(deftest gen-expectations-snippet-round-trips
  (let [snippet (rf.story.author-expectations/gen-expectations-snippet
                  {:variant-id :story.counter/expects-5
                   :extends    :story.counter/happy-path
                   :existing   [[:rf.assert/no-warnings]]
                   :authored   [[:rf.assert/path-equals [:counter :value] 5]]
                   :doc        "the counter holds 5 after two increments"})]
    (testing "the snippet read-string-s back to a reg-variant form whose body
              carries the existing + authored assertions merged as :assertions
              DATA, tagged :test because an authored-expectations variant is
              a runnable test by default"
      (is (= (list 'rf.story/reg-variant :story.counter/expects-5
                   {:doc        "the counter holds 5 after two increments"
                    :extends    :story.counter/happy-path
                    :assertions [[:rf.assert/no-warnings]
                                 [:rf.assert/path-equals [:counter :value] 5]]
                    :tags       #{:test}})
             (edn/read-string snippet))))))
