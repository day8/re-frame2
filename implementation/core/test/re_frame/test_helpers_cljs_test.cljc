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
;; expand-tree — Form-3 reagent class detection (rf2-1c036 gap 1)
;;
;; The walker treats a hiccup vector whose head is a reagent-slim
;; class constructor (built by `reagent2.impl.component/create-class*`)
;; as a Form-3 component: it plucks the stashed `:reagent-render` slot
;; off the class and invokes that fn with the hiccup args, instead of
;; calling the class constructor directly (which would crash without
;; `new`).
;;
;; We fake-stamp a plain fn with the same property tags the reagent-
;; slim `create-class*` sets — that way the unit test does not have to
;; depend on reagent and runs identically on JVM (where the class-3
;; branch is a no-op) and Node-CLJS (where the property access fires).
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- fake-reagent-class
     "Stamp `f` with the reagent-slim class tags (`cljsReagentClass = true`
     + `cljsReagentRender = render-fn`) so test-helpers' class-3 branch
     fires on the wrapped fn. CLJS-only; on JVM there are no JS
     properties to set so the helper is omitted."
     [render-fn]
     (let [klass (fn [& _] (throw (ex-info "do not call" {})))]
       (set! (.-cljsReagentClass ^js klass) true)
       (set! (.-cljsReagentRender ^js klass) render-fn)
       klass)))

#?(:cljs
   (deftest expand-tree-expands-reagent-class
     (testing "a hiccup vector headed by a reagent class is expanded via
              the class's stashed :reagent-render slot, NOT by calling the
              class as a fn (which would crash)"
       (let [render-fn (fn [{:keys [n]}] [:button {:data-testid "class3-btn"}
                                          (str "n=" n)])
             klass     (fake-reagent-class render-fn)
             tree      [klass {:n 11}]
             out       (h/expand-tree tree)]
         (is (vector? out) "class-3 was expanded into a hiccup vector")
         (is (= :button (first out)))
         (is (= "class3-btn" (:data-testid (second out))))
         (is (= "n=11" (last out)))))))

#?(:cljs
   (deftest expand-tree-recurses-through-reagent-class
     (testing "a class-3 whose render-fn returns a nested function component
              is expanded all the way down"
       (let [leaf      (fn [s] [:span {:data-testid "leaf"} s])
             render-fn (fn [{:keys [label]}] [:div {:data-testid "wrap"}
                                              [leaf label]])
             klass     (fake-reagent-class render-fn)
             tree      [klass {:label "hi"}]
             out       (h/expand-tree tree)]
         (is (= :div  (first out)))
         (is (= :span (first (nth out 2)))
             "the nested function component under the class was expanded")))))

#?(:cljs
   (deftest find-by-testid-walks-through-reagent-class
     (testing "find-by-testid resolves a testid that lives inside a Form-3
              component's render output"
       (let [render-fn (fn [{:keys [v]}] [:p {:data-testid "inside-class"} v])
             klass     (fake-reagent-class render-fn)
             tree      [:section {:data-testid "outer"} [klass {:v "ok"}]]
             hit       (h/find-by-testid tree "inside-class")]
         (is (some? hit)
             "find-by-testid did not walk into the class-3 render output")
         (is (= :p (first hit)))
         (is (= "ok" (last hit)))))))

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
;; find-by-attr family (rf2-1c036 gap 2)
;;
;; Generic over the attribute keyword — `:data-testid` is the React
;; convention but Story keys on `:data-test` and Causa uses
;; `:data-rf-causa-*`. The testid wrappers above are thin aliases for
;; the `:data-testid`-bound case.
;; ---------------------------------------------------------------------------

(deftest find-by-attr-resolves-data-testid
  (testing "find-by-attr with :data-testid behaves like find-by-testid"
    (let [tree (counter-view {:n 0 :on-inc identity})
          hit  (h/find-by-attr tree :data-testid "counter-inc")]
      (is (some? hit))
      (is (= :button (first hit))))))

(deftest find-by-attr-resolves-data-test
  (testing "Story-style :data-test selectors are matched"
    (let [tree [:section {:data-test "page-root"}
                [:button {:data-test "submit"
                          :on-click identity} "Go"]
                [:span {:data-test "label"} "hello"]]]
      (is (= :button (first (h/find-by-attr tree :data-test "submit"))))
      (is (= "hello" (last (h/find-by-attr tree :data-test "label"))))
      (is (nil? (h/find-by-attr tree :data-test "missing"))))))

(deftest find-by-attr-resolves-custom-prefix
  (testing "Causa-style :data-rf-causa-* selectors are matched"
    (let [tree [:div {:data-rf-causa-id "frame-picker"}
                [:button {:data-rf-causa-id "btn-1"} "1"]
                [:button {:data-rf-causa-id "btn-2"} "2"]]]
      (is (some? (h/find-by-attr tree :data-rf-causa-id "btn-1")))
      (is (= "2" (last (h/find-by-attr tree :data-rf-causa-id "btn-2")))))))

(deftest find-by-attr-handles-arbitrary-keys
  (testing "any attribute key — :id, :name, :class — is matchable"
    (let [tree [:form {:id "login"}
                [:input {:name "user"}]
                [:input {:name "pass"}]]]
      (is (= "login" (-> (h/find-by-attr tree :id "login") second :id)))
      (is (= "pass"  (-> (h/find-by-attr tree :name "pass") second :name))))))

(deftest find-all-by-attr-collects-every-match
  (let [tree [:ul
              [:li {:data-test "row"} "a"]
              [:li {:data-test "row"} "b"]
              [:li {:data-test "skip"} "c"]
              [:li {:data-test "row"} "d"]]
        hits (h/find-all-by-attr tree :data-test "row")]
    (is (= 3 (count hits)))
    (is (= ["a" "b" "d"] (mapv last hits)))))

(deftest find-all-by-attr-empty-when-no-match
  (is (= [] (h/find-all-by-attr [:div {:data-test "x"}] :data-test "y"))))

(deftest find-by-attr-prefix-matches-stem
  (let [tree [:ul
              [:li {:data-test "row-1"} "a"]
              [:li {:data-test "row-2"} "b"]
              [:li {:data-test "other"} "c"]
              [:li {:data-test "row-3"} "d"]]
        hits (h/find-by-attr-prefix tree :data-test "row-")]
    (is (= 3 (count hits)))
    (is (= ["row-1" "row-2" "row-3"]
           (mapv (comp :data-test second) hits)))))

(deftest find-by-attr-prefix-ignores-non-string-values
  (testing "an attr whose value is a number/keyword does not match prefix"
    (let [tree [:div
                [:span {:data-test "row-1"} "x"]
                [:span {:data-test 42}     "y"]]
          hits (h/find-by-attr-prefix tree :data-test "row-")]
      (is (= 1 (count hits)))
      (is (= "x" (last (first hits)))))))

;; Back-compat: existing find-by-testid call sites must still work
;; (Causa tests + every internal caller key on the testid wrapper).

(deftest find-by-testid-still-routes-through-find-by-attr
  (testing "find-by-testid is a thin wrapper — semantics unchanged"
    (let [tree (counter-view {:n 0 :on-inc identity})
          via-testid (h/find-by-testid tree "counter-root")
          via-attr   (h/find-by-attr tree :data-testid "counter-root")]
      (is (= via-testid via-attr)
          "the wrapper and the underlying resolve to the identical node"))))

(deftest find-all-by-testid-still-routes-through-find-all-by-attr
  (let [tree [:div
              [:span {:data-testid "dup"} "first"]
              [:span {:data-testid "dup"} "second"]]
        via-testid (h/find-all-by-testid tree "dup")
        via-attr   (h/find-all-by-attr tree :data-testid "dup")]
    (is (= via-testid via-attr))))

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
