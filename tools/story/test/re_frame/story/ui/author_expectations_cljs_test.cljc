(ns re-frame.story.ui.author-expectations-cljs-test
  "Tests for the expectation-authoring UI surface (rf2-ba86n.12, spec/021
  §S5).

  Two tiers (mirrors `save_variant_cljs_test` / `promotion_cljs_test`):

  - **JVM + CLJS** (pure draft transitions in `ui.author-expectations`) —
    add / remove / edit-operand / draft-atoms. These pin the contract the
    dialog ratom swaps over, and run host-free.

  - **CLJS-only** (the dialog ratom + button hiccup + dialog render depend
    on Reagent / DOM) — the button disabled state, the kind-picker /
    per-row cost stripe / coverage banner hiccup, and that the rendered
    dialog carries the cost-before-save honesty + the :assertions snippet.

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` build (ns suffix `-cljs-test`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.author-expectations :as ui]
            #?@(:cljs [[clojure.string :as str]
                       [re-frame.story.registrar :as registrar]])))

;; ===========================================================================
;; JVM + CLJS — pure draft transitions
;; ===========================================================================

(deftest add-row-mints-stable-ids
  (testing "add-row appends a fresh row of the kind with a stable id"
    (let [d  (ui/initial-draft)
          d1 (ui/add-row d :app-db-equals)
          d2 (ui/add-row d1 :dom-text)]
      (is (= 2 (count (:rows d2))))
      (is (= [:app-db-equals :dom-text] (mapv :kind (:rows d2))))
      (is (= [0 1] (mapv :row-id (:rows d2)))
          "row ids are stable + monotonic so React keys survive edits"))))

(deftest remove-row-drops-by-id
  (let [d (-> (ui/initial-draft)
              (ui/add-row :app-db-equals)
              (ui/add-row :no-warnings))]
    (is (= [1] (mapv :row-id (:rows (ui/remove-row d 0)))))
    (is (= 2 (count (:rows d))) "remove is non-mutating")))

(deftest set-operand-stores-raw-string
  (testing "set-operand stores the raw value verbatim (parsed at projection)"
    (let [d (-> (ui/initial-draft)
                (ui/add-row :app-db-equals)
                (ui/set-operand 0 :path "[:a]")
                (ui/set-operand 0 :expected "5"))]
      (is (= "[:a]" (get-in (first (:rows d)) [:operands :path])))
      (is (= "5" (get-in (first (:rows d)) [:operands :expected]))))))

(deftest set-variant-id-and-doc
  (let [d (-> (ui/initial-draft)
              (ui/set-variant-id :story.x/expects-1)
              (ui/set-doc "why"))]
    (is (= :story.x/expects-1 (:variant-id d)))
    (is (= "why" (:doc d)))))

(deftest draft-atoms-projects-ready-rows
  (testing "draft-atoms yields canonical atoms for the ready rows only"
    (let [d (-> (ui/initial-draft)
                (ui/add-row :app-db-equals)
                (ui/set-operand 0 :path "[:a]")
                (ui/set-operand 0 :expected "1")
                (ui/add-row :no-warnings)         ; nullary, always ready
                (ui/add-row :sub-equals))]        ; operands blank → not ready
      (is (= [[:rf.assert/path-equals [:a] 1]
              [:rf.assert/no-warnings]]
             (ui/draft-atoms d))))))

;; ===========================================================================
;; CLJS-only — button hiccup
;; ===========================================================================

#?(:cljs
   (deftest button-disabled-without-variant
     (testing "the button is disabled + hinted when no variant is focused"
       (let [attrs (second (ui/author-expectations-button nil))]
         (is (true? (:disabled attrs)))
         (is (str/includes? (:title attrs) "Select a variant"))
         (is (= "story-author-expectation-button" (:data-test attrs)))))))

#?(:cljs
   (deftest button-enabled-with-variant
     (testing "the button enables when a variant is focused"
       (let [attrs (second (ui/author-expectations-button :story.x/y))]
         (is (false? (:disabled attrs)))
         (is (fn? (:on-click attrs)))))))

;; ===========================================================================
;; CLJS-only — dialog ratom + render
;; ===========================================================================

#?(:cljs
   (defn- render-dialog
     "Realise the form-2 author-dialog into hiccup (call the component, then
      its returned render fn)."
     []
     ((ui/author-dialog))))

#?(:cljs
   (deftest dialog-not-rendered-when-closed
     (testing "the dialog renders nil while its ratom is closed"
       (reset! ui/dialog-atom ui/initial-dialog-state)
       (is (nil? (render-dialog))))))

#?(:cljs
   (deftest open-seeds-an-empty-draft-with-default-id
     (testing "open! flips :open? and seeds a default expects-<ms> id"
       (registrar/clear-all!)
       (ui/open! :story.counter/happy-path)
       (let [d @ui/dialog-atom]
         (is (:open? d))
         (is (= :story.counter/happy-path (:source-id d)))
         (is (empty? (:rows (:draft d))) "starts with no expectations")
         (is (qualified-keyword? (:variant-id (:draft d))))
         (is (str/includes? (name (:variant-id (:draft d))) "expects")
             "the default id carries the 'expects' prefix"))
       (ui/close!))))

#?(:cljs
   (deftest dialog-shows-cost-before-save-and-cannot-run
     (testing "the open dialog renders the per-row cost + a cannot-run flag
               for a DOM expectation — the honesty floor BEFORE save"
       (registrar/clear-all!)
       (ui/open! :story.counter/happy-path)
       ;; author one headless app-db expectation + one DOM expectation
       (ui/add-row! :app-db-equals)
       (ui/set-operand! 0 :path "[:counter :value]")
       (ui/set-operand! 0 :expected "5")
       (ui/add-row! :dom-text)
       (ui/set-operand! 1 :selector "\".count\"")
       (ui/set-operand! 1 :text "\"5\"")
       (let [flat (str (render-dialog))]
         (is (str/includes? flat "story-author-expectation-dialog"))
         ;; both authored rows are present as components (the per-row cost
         ;; stripe lives inside each `expectation-row`)
         (is (str/includes? flat "story-author-expectation-rows"))
         (is (str/includes? flat "expectation_row")
             "the authored rows render as expectation-row components")
         ;; the draft coverage banner reports the cheapest runner + the
         ;; explicit cannot-run honesty BEFORE save (escalates to :dom)
         (is (str/includes? flat "story-author-expectation-summary"))
         (is (str/includes? flat ":data-cheapest-runner \":dom\"")
             "the cheapest runner that proves the whole draft is surfaced")
         (is (str/includes? flat "story-author-expectation-cannot-run-summary"))
         (is (str/includes? flat "cannot run under the default headless runner")
             "the cannot-run honesty is visible BEFORE save")
         ;; the snippet carries the authored expectations as :assertions DATA
         (is (str/includes? flat "story-author-expectation-snippet"))
         (is (str/includes? flat ":assertions"))
         (is (str/includes? flat ":rf.assert/path-equals"))
         (is (str/includes? flat ":rf.assert/dom-text"))
         (is (str/includes? flat ":extends"))
         (is (str/includes? flat ":story.counter/happy-path")))
       (ui/close!))))

#?(:cljs
   (deftest expectation-row-renders-per-row-cost-stripe
     (testing "an authored DOM row renders its per-row cost stripe with the
               cannot-run-headless flag — the honesty floor at the row level
               (realised directly, since the strip lives inside the component)"
       (let [row  {:row-id 0 :kind :dom-text
                   :operands {:selector "\".count\"" :text "\"5\""}}
             flat (str (#'ui/expectation-row row))]
         (is (str/includes? flat "story-author-expectation-cost"))
         (is (str/includes? flat "story-author-expectation-cannot-run"))
         (is (str/includes? flat "cannot run headless"))
         (is (str/includes? flat ":dom") "the cheapest runner is shown")))))

#?(:cljs
   (deftest dialog-merges-existing-declared-assertions
     (testing "the snippet merges the source variant's declared :assertions
               with the authored atoms (the additive round-trip)"
       (registrar/clear-all!)
       (registrar/install-canonical-tags!)
       ;; register a source variant that already declares an assertion
       (registrar/reg-variant* :story.counter/has-assert
         {:assertions [[:rf.assert/no-warnings]]
          :tags       #{:test}})
       (ui/open! :story.counter/has-assert)
       (ui/add-row! :app-db-equals)
       (ui/set-operand! 0 :path "[:n]")
       (ui/set-operand! 0 :expected "1")
       (let [flat (str (render-dialog))]
         (is (str/includes? flat ":rf.assert/no-warnings") "existing kept")
         (is (str/includes? flat ":rf.assert/path-equals") "authored added"))
       (ui/close!))))

#?(:cljs
   (deftest kind-picker-covers-all-surfaces
     (testing "the rendered dialog kind-picker offers a chip per catalog kind"
       (registrar/clear-all!)
       (ui/open! :story.x/y)
       (let [flat (str (render-dialog))]
         (is (str/includes? flat "story-author-expectation-kind-picker"))
         ;; one chip per kind — spot-check the five acceptance surfaces
         (is (str/includes? flat "app-db-equals"))
         (is (str/includes? flat "sub-equals"))
         (is (str/includes? flat "dom-text"))
         (is (str/includes? flat "schema-error"))
         (is (str/includes? flat "a11y")))
       (ui/close!))))
