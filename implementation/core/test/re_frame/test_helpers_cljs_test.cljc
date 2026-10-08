(ns re-frame.test-helpers-cljs-test
  "`re-frame.test-helpers` (a published testing-tier API) over plain hiccup
  data, identically on the JVM and :node-test."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test :refer-macros [are deftest is testing]])
            [clojure.string :as str]
            [re-frame.test-helpers :as rf.test-helpers]))

(defn- counter-button [{:keys [n on-click]}]
  [:button {:data-testid "counter-inc" :on-click on-click}
   (str "Count: " n)])

(defn- counter-view
  "Nests `counter-button` as a function component, so finding its testid
  needs the `expand-tree` recursion."
  [{:keys [n on-inc]}]
  [:div {:data-testid "counter-root"}
   [:span {:data-testid "counter-label"} "Counter"]
   [counter-button {:n n :on-click on-inc}]])

(defn- list-view [items]
  [:ul {:data-testid "items"}
   (for [{:keys [id label]} items]
     [:li {:data-testid (str "item-" id) :key id} label])])

;; ---- expand-tree ---------------------------------------------------------

(deftest expand-tree-handles-leaves
  (is (= ["hi" 42 nil {:a 1}] (map rf.test-helpers/expand-tree ["hi" 42 nil {:a 1}]))
      "non-vector/non-seq inputs are returned unchanged"))

;; A Form-3 reagent-slim class is expanded through its stashed :reagent-render
;; slot, never called as a fn (that crashes without `new`). The fake stamps a
;; plain fn with the tags reagent-slim's `create-class*` sets, so no reagent
;; dependency is needed.

#?(:cljs
   (defn- fake-reagent-class [render-fn]
     (let [klass (fn [& _] (throw (ex-info "do not call" {})))]
       (set! (.-cljsReagentClass ^js klass) true)
       (set! (.-cljsReagentRender ^js klass) render-fn)
       klass)))

#?(:cljs
   (deftest expand-tree-recurses-through-reagent-class
     (let [leaf      (fn [s] [:span {:data-testid "leaf"} s])
           render-fn (fn [{:keys [label]}] [:div {:data-testid "wrap"} [leaf label]])]
       (let [out (rf.test-helpers/expand-tree [(fake-reagent-class render-fn) {:label "hi"}])]
         (is (= [:div :span] [(first out) (first (nth out 2))])
             "the class renders through its render slot, and the nested fn component expands")))))

;; ---- attrs / children ----------------------------------------------------

(deftest attrs-reads-the-attrs-map-slot
  (testing "the second element is the attrs map only when it is a map; a child
            in that slot, or non-hiccup input, reads nil"
    (are [node expected] (= expected (rf.test-helpers/attrs node))
      [:div {:k 1} "child"] {:k 1}
      [:div "child"]        nil
      "string"              nil
      nil                   nil)))

(deftest children-are-everything-after-the-tag-and-attrs
  (are [node expected] (= expected (rf.test-helpers/children node))
    [:div {:k 1} "a" "b"] ["a" "b"]
    [:div "a" "b"]        ["a" "b"]
    [:div {:k 1}]         []
    [:div]                []))

;; ---- find-by-testid family -----------------------------------------------

(deftest find-by-testid-walks-into-function-components
  (is (= :button (first (rf.test-helpers/find-by-testid (counter-view {:n 0 :on-inc identity})
                                                         "counter-inc")))))

(deftest find-all-by-testid-returns-every-match
  (let [tree [:div
              [:span {:data-testid "dup"} "first"]
              [:span {:data-testid "dup"} "second"]
              [:span {:data-testid "other"} "third"]]]
    (is (= ["first" "second"] (mapv last (rf.test-helpers/find-all-by-testid tree "dup"))))
    (is (= "first" (last (rf.test-helpers/find-by-testid tree "dup")))
        "find-by-testid returns only the first match")))

(deftest find-by-testid-prefix-matches-stem
  (is (= ["item-1" "item-2" "item-3"]
         (mapv (comp :data-testid second)
               (rf.test-helpers/find-by-testid-prefix
                 (list-view [{:id 1 :label "a"} {:id 2 :label "b"} {:id 3 :label "c"}])
                 "item-")))))

;; ---- find-by-attr family -------------------------------------------------
;;
;; Generic over the attribute keyword: Story keys on `:data-test`, Xray on
;; `:data-rf-xray-*`; the testid helpers above are the `:data-testid` case.

(deftest find-by-attr-resolves-data-test
  (let [tree [:section {:data-test "page-root"}
              [:button {:data-test "submit" :on-click identity} "Go"]
              [:span {:data-test "label"} "hello"]]]
    (is (= [:button "hello" nil]
           [(first (rf.test-helpers/find-by-attr tree :data-test "submit"))
            (last (rf.test-helpers/find-by-attr tree :data-test "label"))
            (rf.test-helpers/find-by-attr tree :data-test "missing")]))))

(deftest find-all-by-attr-collects-every-match
  (let [tree [:ul
              [:li {:data-test "row"} "a"]
              [:li {:data-test "row"} "b"]
              [:li {:data-test "skip"} "c"]
              [:li {:data-test "row"} "d"]]]
    (is (= ["a" "b" "d"] (mapv last (rf.test-helpers/find-all-by-attr tree :data-test "row"))))
    (is (= [] (rf.test-helpers/find-all-by-attr tree :data-test "none")) "no match is an empty vector")))

(deftest find-by-attr-prefix-matches-stem
  (let [tree [:ul
              [:li {:data-test "row-1"} "a"]
              [:li {:data-test "row-2"} "b"]
              [:li {:data-test "other"} "c"]
              [:li {:data-test 42} "n"]
              [:li {:data-test "row-3"} "d"]]]
    (is (= ["row-1" "row-2" "row-3"]
           (mapv (comp :data-test second) (rf.test-helpers/find-by-attr-prefix tree :data-test "row-")))
        "a non-string attr value never matches a prefix")))

;; ---- text-content --------------------------------------------------------

(deftest text-content-joins-string-and-number-leaves
  (are [node expected] (= expected (rf.test-helpers/text-content node))
    [:div [:span "hello "] [:span "world"]] "hello world"
    [:span "Count: " 5]                     "Count: 5"
    [:div {:k 1}]                           ""))

;; ---- extract-handler / invoke-handler ------------------------------------

(deftest invoke-handler-calls-and-returns
  (let [fired (atom nil)
        btn   (rf.test-helpers/find-by-testid
                (counter-view {:n 0 :on-inc #(do (reset! fired %) :returned-value)})
                "counter-inc")]
    (is (= [:returned-value :evt-arg]
           [(rf.test-helpers/invoke-handler btn :on-click :evt-arg) @fired]))))

(deftest invoke-handler-throws-on-missing-handler-or-non-hiccup
  (doseq [node [[:div {:k 1}] nil "string"]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (rf.test-helpers/invoke-handler node :on-click))
        (pr-str node))))

;; ---- testid (authoring helper) -------------------------------------------

(deftest testid-builds-the-attrs-map
  (is (= [{:data-testid "foo"}
          {:data-testid "foo" :on-click :handler :class "bar"}
          {:data-testid "outer"}]
         [(rf.test-helpers/testid "foo")
          (rf.test-helpers/testid "foo" {:on-click :handler :class "bar"})
          (rf.test-helpers/testid "outer" {:data-testid "inner"})])
      "extra attrs merge in; the id arg wins over an extra :data-testid"))

;; The copyable `testid` example must show the `reg-view`-injected `dispatch`
;; INSIDE the `rf/reg-view` form that binds it: a bare deferred `rf/dispatch`
;; raises :rf.error/no-frame-context once the render scope has unwound, and a
;; detached fragment leaves `dispatch` unresolved. The example is pinned as one
;; exact form on whitespace-collapsed text, so re-indenting stays green while
;; enclosure holds by construction rather than by a pattern.

(def ^:private canonical-testid-example
  "(rf/reg-view counter-inc-button [] [:button (testid \"counter-inc\" {:on-click #(dispatch [:counter/inc])}) \"+\"])")

(deftest testid-docstring-shows-the-binding-context-for-injected-dispatch
  (let [doc (:doc (meta #'rf.test-helpers/testid))]
    (is (str/includes? (str/replace (str/trim (str doc)) #"\s+" " ") canonical-testid-example))
    (is (nil? (re-find #"#\(rf/dispatch " (str doc)))
        "no bare deferred `rf/dispatch` in the example")))
