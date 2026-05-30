(ns re-frame.story.author-expectations-test
  "Tests for the pure expectation-authoring substrate (rf2-ba86n.12,
  spec/021 §S5, spec/019).

  Runs on the JVM under `clojure -M:test` AND on CLJS under shadow's
  `:node-test` build — the substrate is pure data → data (catalog / atom
  builders / cost projection / snippet), so it pins the contract the dialog
  + assertion-strip + palette entry depend on without a host. The ns suffix
  `-test` lands it in the JVM runner; the `-cljs-test` companion
  (`author_expectations_cljs_test`) carries the dialog-transition coverage."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [re-frame.story.assertions          :as assertions]
            [re-frame.story.author-expectations :as author]
            [re-frame.story.requirements        :as requirements]))

;; ===========================================================================
;; CATALOG — covers the five acceptance surfaces
;; ===========================================================================

(deftest catalog-covers-the-acceptance-surfaces
  (testing "every authorable kind names a recognised canonical assertion id"
    (doseq [{:keys [kind assertion-id]} author/expectation-kinds]
      (is (assertions/assertion-id-known? assertion-id)
          (str kind " folds onto a known assertion id"))))
  (testing "the catalog spans every acceptance-criteria surface (rf2-ba86n.12)"
    (let [surfaces (into #{} (map :surface) author/expectation-kinds)]
      ;; app-db, subscriptions, rendered DOM, schema behaviour, browser/a11y
      (is (= #{:app-db :subscriptions :dom :schema :browser} surfaces)
          "app-db / subscriptions / DOM / schema / browser are all authorable")))
  (testing "kind-descriptor round-trips by kind"
    (doseq [{:keys [kind] :as d} author/expectation-kinds]
      (is (= d (author/kind-descriptor kind))))))

;; ===========================================================================
;; OPERAND PARSING + ATOM CONSTRUCTION — builds the CANONICAL vocabulary
;; ===========================================================================

(deftest expectation->atom-builds-canonical-atoms
  (testing "app-db value equals → :rf.assert/path-equals"
    (is (= [:rf.assert/path-equals [:counter :value] 5]
           (author/expectation->atom
             {:kind :app-db-equals
              :operands {:path "[:counter :value]" :expected "5"}}))))
  (testing "a bare keyword path lifts to a single-element path vector"
    (is (= [:rf.assert/path-equals [:open?] true]
           (author/expectation->atom
             {:kind :app-db-equals
              :operands {:path ":open?" :expected "true"}}))))
  (testing "subscription equals → :rf.assert/sub-equals"
    (is (= [:rf.assert/sub-equals [:counter/value] 5]
           (author/expectation->atom
             {:kind :sub-equals
              :operands {:query-v "[:counter/value]" :expected "5"}}))))
  (testing "app-db matches schema → :rf.assert/path-matches"
    (is (= [:rf.assert/path-matches [:user] [:map [:id :int]]]
           (author/expectation->atom
             {:kind :app-db-matches
              :operands {:path "[:user]" :schema "[:map [:id :int]]"}}))))
  (testing "no-warnings is a nullary atom"
    (is (= [:rf.assert/no-warnings]
           (author/expectation->atom {:kind :no-warnings :operands {}}))))
  (testing "DOM text → :rf.assert/dom-text"
    (is (= [:rf.assert/dom-text ".count" "5"]
           (author/expectation->atom
             {:kind :dom-text :operands {:selector "\".count\"" :text "\"5\""}}))))
  (testing "schema-error with a spec map → :rf.assert/schema-error spec"
    (is (= [:rf.assert/schema-error {:where :event :event :user/save}]
           (author/expectation->atom
             {:kind :schema-error
              :operands {:where-spec "{:where :event :event :user/save}"}}))))
  (testing "schema-error with no spec → bare atom"
    (is (= [:rf.assert/schema-error]
           (author/expectation->atom {:kind :schema-error :operands {}}))))
  (testing "an unparsable operand yields nil (the caller guards)"
    (is (nil? (author/expectation->atom
                {:kind :app-db-equals :operands {:path "" :expected "5"}}))
        "a blank required operand fails the parse → no atom")
    (is (nil? (author/expectation->atom
                {:kind :app-db-equals :operands {:path "[:a" :expected "5"}}))
        "a malformed EDN operand fails the parse → no atom")))

(deftest parse-operands-reports-per-field-errors
  (testing "ok? true when every operand parses"
    (let [{:keys [ok? values errors]}
          (author/parse-operands
            {:kind :app-db-equals :operands {:path "[:a]" :expected "1"}})]
      (is ok?)
      (is (= {:path [:a] :expected 1} values))
      (is (empty? errors))))
  (testing "ok? false + a per-field error when an operand is blank"
    (let [{:keys [ok? errors]}
          (author/parse-operands
            {:kind :app-db-equals :operands {:path "[:a]" :expected ""}})]
      (is (not ok?))
      (is (contains? errors :expected))))
  (testing "a malformed EDN operand carries the reader error string"
    (let [{:keys [ok? errors]}
          (author/parse-operands
            {:kind :sub-equals :operands {:query-v "[:a" :expected "1"}})]
      (is (not ok?))
      (is (string? (:query-v errors))))))

;; ===========================================================================
;; RUNNER COST / :cannot-run BEFORE SAVE — reads the EXISTING registry
;; ===========================================================================

(deftest expectation-cost-reads-the-requirement-registry
  (testing "an app-db expectation runs headless (no escalation cost)"
    (let [cost (author/expectation-cost [:rf.assert/path-equals [:a] 1])]
      (is (= #{:app-db} (:required cost)))
      (is (:headless? cost))
      (is (not (:cannot-run? cost)))
      (is (= :headless (:cheapest-runner cost)))
      (is (empty? (:missing cost)))))
  (testing "a sub-equals expectation needs :pure-subs but still runs headless"
    (let [cost (author/expectation-cost [:rf.assert/sub-equals [:s] 1])]
      (is (= #{:app-db :pure-subs} (:required cost)))
      (is (not (:cannot-run? cost)))
      (is (= :headless (:cheapest-runner cost)))))
  (testing "a DOM expectation CANNOT run headless — visible before save"
    (let [cost (author/expectation-cost [:rf.assert/dom-text ".x" "y"])]
      (is (= #{:dom} (:required cost)))
      (is (:cannot-run? cost) "the honest before-save cannot-run flag")
      (is (= :dom (:cheapest-runner cost)) "cheapest runner that CAN prove it")
      (is (contains? (:missing cost) :dom) "the missing token is surfaced")))
  (testing "a visual-snapshot expectation needs a browser runner"
    (let [cost (author/expectation-cost [:rf.assert/visual-snapshot])]
      (is (:cannot-run? cost))
      (is (= :browser (:cheapest-runner cost)))))
  (testing "structural a11y rides the :hiccup tier (cannot run headless, cheaper than browser)"
    (let [cost (author/expectation-cost [:rf.assert/a11y-structural])]
      (is (:cannot-run? cost))
      (is (= :hiccup (:cheapest-runner cost)))))
  (testing "cost agrees with the requirement registry it reads"
    (is (= (requirements/assertion-tokens [:rf.assert/dom-visible ".x"])
           (:required (author/expectation-cost [:rf.assert/dom-visible ".x"])))))
  (testing "a nil atom (unparsed row) projects an empty, non-throwing cost"
    (let [cost (author/expectation-cost nil)]
      (is (not (:cannot-run? cost)))
      (is (empty? (:required cost))))))

(deftest row-cost-projects-live-from-a-row
  (testing "a ready DOM row reports cannot-run before it is even an atom elsewhere"
    (is (:cannot-run?
          (author/row-cost
            {:kind :dom-text :operands {:selector "\".x\"" :text "\"y\""}}))))
  (testing "an unparsed row projects a non-throwing headless cost"
    (is (not (:cannot-run?
               (author/row-cost {:kind :app-db-equals :operands {}}))))))

;; ===========================================================================
;; DRAFT SUMMARY — the before-save honesty banner data
;; ===========================================================================

(deftest draft-summary-aggregates-cost-and-surfaces
  (let [draft {:rows [{:row-id 0 :kind :app-db-equals
                       :operands {:path "[:a]" :expected "1"}}
                      {:row-id 1 :kind :dom-text
                       :operands {:selector "\".x\"" :text "\"y\""}}
                      {:row-id 2 :kind :app-db-equals
                       :operands {:path "" :expected "1"}}]}  ; not ready
        {:keys [count ready atoms required cheapest-runner cannot-run-rows surfaces]}
        (author/draft-summary draft)]
    (testing "counts authored vs ready"
      (is (= 3 count))
      (is (= 2 ready) "the blank-path row is not ready"))
    (testing "atoms are the canonical vocabulary for ready rows only"
      (is (= [[:rf.assert/path-equals [:a] 1]
              [:rf.assert/dom-text ".x" "y"]]
             atoms)))
    (testing "required is the union of every ready atom's tokens"
      (is (= #{:app-db :dom} required)))
    (testing "cheapest runner proves the WHOLE draft (escalates to :dom for the DOM row)"
      (is (= :dom cheapest-runner)))
    (testing "cannot-run-rows lists the DOM row — the honest before-save list"
      (is (= 1 (clojure.core/count cannot-run-rows)))
      (is (= [:rf.assert/dom-text ".x" "y"] (:atom (first cannot-run-rows)))))
    (testing "surfaces span the authored kinds"
      (is (= #{:app-db :dom} surfaces)))))

;; ===========================================================================
;; SNIPPET — expectations become EXPLICIT variant DATA (the round-trip)
;; ===========================================================================

(deftest merge-assertions-is-additive-and-dedupes
  (testing "authored atoms append after existing, order preserved"
    (is (= [[:rf.assert/path-equals [:a] 1]
            [:rf.assert/no-warnings]]
           (author/merge-assertions
             [[:rf.assert/path-equals [:a] 1]]
             [[:rf.assert/no-warnings]]))))
  (testing "an exact duplicate is dropped (re-authoring is idempotent)"
    (is (= [[:rf.assert/path-equals [:a] 1]]
           (author/merge-assertions
             [[:rf.assert/path-equals [:a] 1]]
             [[:rf.assert/path-equals [:a] 1]]))))
  (testing "nil existing / authored are tolerated"
    (is (= [[:rf.assert/no-warnings]]
           (author/merge-assertions nil [[:rf.assert/no-warnings]])))
    (is (= [] (author/merge-assertions nil nil)))))

(deftest gen-expectations-snippet-round-trips
  (let [snippet (author/gen-expectations-snippet
                  {:variant-id :story.counter/expects-5
                   :extends    :story.counter/happy-path
                   :existing   [[:rf.assert/no-warnings]]
                   :authored   [[:rf.assert/path-equals [:counter :value] 5]]
                   :doc        "the counter holds 5 after two increments"})]
    (testing "the snippet is a reg-variant form carrying :assertions DATA"
      (is (str/includes? snippet "reg-variant"))
      (is (str/includes? snippet ":story.counter/expects-5"))
      (is (str/includes? snippet ":extends :story.counter/happy-path"))
      (is (str/includes? snippet ":assertions"))
      (is (str/includes? snippet ":tags #{:test}")
          "an authored-expectations variant is a runnable test by default"))
    (testing "the snippet read-string-s back to the merged variant body"
      (let [form (edn/read-string snippet)
            ;; (alias/reg-variant <id> <body>) — body is the 3rd element
            body (nth form 2)]
        (is (= :story.counter/happy-path (:extends body)))
        (is (= "the counter holds 5 after two increments" (:doc body)))
        (is (= [[:rf.assert/no-warnings]
                [:rf.assert/path-equals [:counter :value] 5]]
               (:assertions body))
            "existing + authored assertions merged, the round-trip")))))

(deftest assertions-known-validates-against-the-vocabulary
  (is (author/assertions-known?
        [[:rf.assert/path-equals [:a] 1] [:rf.assert/dom-text ".x" "y"]]))
  (is (not (author/assertions-known?
             [[:rf.assert/not-a-real-assertion]]))
      "an unknown id is rejected so the snippet can never reference one"))

(deftest default-id-prefix-is-distinct-from-siblings
  (testing "the prefix distinguishes authored-expectations from save / promotion"
    (is (= "expects" author/default-id-prefix))
    (is (not= "saved" author/default-id-prefix))
    (is (not= "regression" author/default-id-prefix))))
