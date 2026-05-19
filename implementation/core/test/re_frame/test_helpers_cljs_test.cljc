(ns re-frame.test-helpers-cljs-test
  "Unit coverage for `re-frame.test-helpers` (rf2-irp6j).

  Dual-runtime: the file is named `*_cljs_test.cljc` so both the JVM
  test runner and the shadow-cljs `:node-test` build pick it up.
  Every assertion runs identically under both runtimes because the
  helpers walk plain hiccup data — no React, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Tiny view fns used as fixtures
;; ---------------------------------------------------------------------------

(defn- counter-button
  "Form-1 leaf — a `[:button ...]` carrying `:data-testid` +
  `:on-click`."
  [{:keys [n on-click]}]
  [:button {:data-testid "counter-inc"
            :on-click    on-click}
   (str "Count: " n)])

(defn- counter-view
  "Form-1 parent that nests `counter-button` as a function component
  in the hiccup. Exercises the `expand-tree` recursion."
  [{:keys [n on-inc]}]
  [:div {:data-testid "counter-root"}
   [:span {:data-testid "counter-label"} "Counter"]
   [counter-button {:n n :on-click on-inc}]])

(defn- list-view
  "Form-1 view emitting a homogeneous family of testid'd rows. Used to
  exercise `find-all-by-testid` and `find-by-testid-prefix`."
  [items]
  [:ul {:data-testid "items"}
   (for [{:keys [id label]} items]
     [:li {:data-testid (str "item-" id)
           :key         id}
      label])])

;; ---------------------------------------------------------------------------
;; expand-tree
;; ---------------------------------------------------------------------------

(deftest expand-tree-passes-through-keyword-tags
  (testing "vectors with a keyword tag are walked, not invoked"
    (let [tree [:div {:k 1} [:span "hi"]]
          out  (h/expand-tree tree)]
      (is (= [:div {:k 1} [:span "hi"]] out)))))

(deftest expand-tree-invokes-function-components
  (testing "a vector starting with a fn is invoked with its args"
    (let [tree [counter-button {:n 7 :on-click identity}]
          out  (h/expand-tree tree)]
      (is (vector? out))
      (is (= :button (first out)))
      (is (= "counter-inc" (:data-testid (second out)))))))

(deftest expand-tree-recurses-into-nested-function-components
  (testing "a parent view with a child fn-component expands both"
    (let [tree (counter-view {:n 3 :on-inc identity})
          out  (h/expand-tree tree)]
      ;; outer :div
      (is (= :div (first out)))
      ;; inner button was a fn-component — should be expanded to :button
      (let [inner (some #(when (and (vector? %) (= :button (first %))) %)
                        (tree-seq vector? seq out))]
        (is (some? inner) "nested function component was not expanded")))))

(deftest expand-tree-handles-leaves
  (testing "non-vector/non-seq inputs are returned unchanged"
    (is (= "hi"      (h/expand-tree "hi")))
    (is (= 42        (h/expand-tree 42)))
    (is (= nil       (h/expand-tree nil)))
    (is (= {:a 1}    (h/expand-tree {:a 1})))))

;; ---------------------------------------------------------------------------
;; attrs / children
;; ---------------------------------------------------------------------------

(deftest attrs-returns-map-when-present
  (is (= {:k 1} (h/attrs [:div {:k 1} "child"]))))

(deftest attrs-returns-nil-when-second-is-child
  (testing "no attrs map — second element is a child"
    (is (nil? (h/attrs [:div "child"]))))
  (testing "non-hiccup"
    (is (nil? (h/attrs "string")))
    (is (nil? (h/attrs nil)))))

(deftest children-returns-after-attrs
  (is (= ["a" "b"] (h/children [:div {:k 1} "a" "b"]))))

(deftest children-returns-after-tag-when-no-attrs
  (is (= ["a" "b"] (h/children [:div "a" "b"]))))

(deftest children-empty-when-no-children
  (is (= [] (h/children [:div {:k 1}])))
  (is (= [] (h/children [:div]))))

;; ---------------------------------------------------------------------------
;; find-by-testid family
;; ---------------------------------------------------------------------------

(deftest find-by-testid-finds-outer-node
  (let [tree (counter-view {:n 0 :on-inc identity})
        hit  (h/find-by-testid tree "counter-root")]
    (is (some? hit))
    (is (= :div (first hit)))))

(deftest find-by-testid-walks-into-function-components
  (testing "the testid lives inside a nested function component — the
            walker expands the component to reach it"
    (let [tree (counter-view {:n 0 :on-inc identity})
          hit  (h/find-by-testid tree "counter-inc")]
      (is (some? hit) "find-by-testid did not expand the nested fn-component")
      (is (= :button (first hit))))))

(deftest find-by-testid-returns-nil-when-no-match
  (let [tree (counter-view {:n 0 :on-inc identity})]
    (is (nil? (h/find-by-testid tree "does-not-exist")))))

(deftest find-by-testid-returns-first-match
  (testing "multiple matches → only the first is returned"
    (let [tree [:div
                [:span {:data-testid "dup"} "first"]
                [:span {:data-testid "dup"} "second"]]
          hit  (h/find-by-testid tree "dup")]
      (is (= "first" (last hit))))))

(deftest find-all-by-testid-returns-every-match
  (let [tree [:div
              [:span {:data-testid "dup"} "first"]
              [:span {:data-testid "dup"} "second"]
              [:span {:data-testid "other"} "third"]]
        hits (h/find-all-by-testid tree "dup")]
    (is (= 2 (count hits)))
    (is (= "first"  (last (first hits))))
    (is (= "second" (last (second hits))))))

(deftest find-all-by-testid-empty-when-no-match
  (is (= [] (h/find-all-by-testid [:div] "nope"))))

(deftest find-by-testid-prefix-matches-stem
  (let [tree (list-view [{:id 1 :label "a"}
                         {:id 2 :label "b"}
                         {:id 3 :label "c"}])
        hits (h/find-by-testid-prefix tree "item-")]
    (is (= 3 (count hits)))
    (is (= ["item-1" "item-2" "item-3"]
           (mapv (comp :data-testid second) hits)))))

;; ---------------------------------------------------------------------------
;; text-content
;; ---------------------------------------------------------------------------

(deftest text-content-collects-string-leaves
  (let [tree [:div [:span "hello "] [:span "world"]]]
    (is (= "hello world" (h/text-content tree)))))

(deftest text-content-coerces-numbers
  (is (= "Count: 5" (h/text-content [:span "Count: " 5]))))

(deftest text-content-walks-function-components
  (let [tree (counter-view {:n 42 :on-inc identity})]
    (is (= "Count: 42"
           (h/text-content (h/find-by-testid tree "counter-inc"))))))

(deftest text-content-empty-on-no-strings
  (is (= "" (h/text-content [:div {:k 1}]))))

;; ---------------------------------------------------------------------------
;; extract-handler / invoke-handler
;; ---------------------------------------------------------------------------

(deftest extract-handler-pulls-on-click
  (let [tree (counter-view {:n 0 :on-inc identity})
        btn  (h/find-by-testid tree "counter-inc")]
    (is (fn? (h/extract-handler btn :on-click)))))

(deftest extract-handler-nil-when-missing
  (let [tree (counter-view {:n 0 :on-inc identity})
        btn  (h/find-by-testid tree "counter-inc")]
    (is (nil? (h/extract-handler btn :on-change)))))

(deftest invoke-handler-calls-and-returns
  (let [fired (atom nil)
        tree  (counter-view {:n 0 :on-inc #(reset! fired %)})
        btn   (h/find-by-testid tree "counter-inc")]
    (h/invoke-handler btn :on-click :evt-arg)
    (is (= :evt-arg @fired)
        "invoke-handler did not pass args through to the handler")))

(deftest invoke-handler-returns-handler-result
  (let [tree [:button {:on-click (fn [_] :returned-value)}]]
    (is (= :returned-value (h/invoke-handler tree :on-click nil)))))

(deftest invoke-handler-throws-on-missing-handler
  (let [tree [:div {:k 1}]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (h/invoke-handler tree :on-click)))))

(deftest invoke-handler-throws-on-non-hiccup
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
               (h/invoke-handler nil :on-click)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
               (h/invoke-handler "string" :on-click))))

;; ---------------------------------------------------------------------------
;; testid (authoring helper)
;; ---------------------------------------------------------------------------

(deftest testid-emits-attrs-map
  (is (= {:data-testid "foo"} (h/testid "foo"))))

(deftest testid-merges-extra-attrs
  (let [m (h/testid "foo" {:on-click :handler :class "bar"})]
    (is (= "foo"     (:data-testid m)))
    (is (= :handler  (:on-click m)))
    (is (= "bar"     (:class m)))))

(deftest testid-id-wins-over-extra-collision
  (testing "an :data-testid in extra is overridden by the id arg"
    (let [m (h/testid "outer" {:data-testid "inner"})]
      (is (= "outer" (:data-testid m))))))

(deftest testid-round-trips-with-find-by-testid
  (testing "a view authored with `testid` is reachable by find-by-testid"
    (let [tree [:button (h/testid "go" {:on-click identity}) "Go"]
          hit  (h/find-by-testid tree "go")]
      (is (some? hit))
      (is (fn? (h/extract-handler hit :on-click))))))
