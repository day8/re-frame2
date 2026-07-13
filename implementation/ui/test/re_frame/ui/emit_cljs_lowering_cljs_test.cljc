(ns re-frame.ui.emit-cljs-lowering-cljs-test
  "Focused golden for the production JSX call shape (rf2-dj7pav)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.emit-cljs :as emit]
            [re-frame.ui.compiler.env :as env]))

(defn- resolve-sym [sym]
  (case sym
    child-view {:fqn 'app.views/child-view
                :meta {:rf.ui/view true :rf.ui/children? true}}
    nil))

(defn- emitted [form]
  (let [e   (env/make-env {:host :clj
                           :ns-sym 'app.test
                           :resolver resolve-sym})
        ast (ana/analyze e form)]
    (emit/emit-inline ast 'lowering-golden)))

(defn- forms-of [form]
  (tree-seq coll? seq form))

(defn- jsx-calls [form]
  (filter #(and (seq? %)
                (= 'js* (first %))
                (string? (second %))
                (re-find #"^\(0,~\{\}\.jsx" (second %)))
          (forms-of form)))

(def wrapper-symbols
  '#{re-frame.ui.runtime/jsx2 re-frame.ui.runtime/jsx3
     re-frame.ui.runtime/jsxs2 re-frame.ui.runtime/jsxs3})

(deftest compiler-authored-sites-call-jsx-runtime-module-directly
  (let [form  (emitted '[:div [child-view {:label label}] [:span "tail"]])
        calls (vec (jsx-calls form))]
    (is (seq calls))
    (is (every? #(= 're-frame.ui.runtime/jsx-runtime
                    (nth % 2)) calls)
        "every ordinary element/component call uses the imported module object")
    (is (not-any? wrapper-symbols (forms-of form))
        "no compiler-authored site routes through the retained runtime helpers")
    (is (some #(re-find #"\.jsxs\)" (second %)) calls)
        "multi-child sites select React's jsxs property")))

(deftest key-presence-selects-exact-react-arity
  (testing "an absent key uses jsx(type, props)"
    (let [call (first (jsx-calls (emitted '[:div "x"])))]
      (is (= "(0,~{}.jsx)(~{},~{})" (second call)))
      (is (= 5 (count call))
          "js* template + module + exactly two React arguments")))
  (testing "present falsey keys still use jsx(type, props, key)"
    (doseq [k [nil false]]
      (let [call (first (jsx-calls (emitted [:div {:key k} "x"])))]
        (is (= "(0,~{}.jsx)(~{},~{},~{})" (second call)))
        (is (= 6 (count call))
            "js* template + module + exactly three React arguments")
        (is (= k (last call))
            "the authored falsey key is passed through, not treated as absent")))))

(deftest literal-props-preserve-key-spelling-and-value-order
  (let [call       (first (jsx-calls
                           (emitted '[:input {:data-z (mark! :first)
                                              :checked (mark! :second)}])))
        props-form (nth call 4)]
    (is (= 'js* (first props-form)))
    (is (= '("data-z" (re-frame.ui.runtime/attr-val (mark! :first))
             "checked" (re-frame.ui.runtime/attr-val (mark! :second)))
           (drop 2 props-form))
        "literal string keys and authored value order are explicit arguments")
    (is (re-find #"^\(\{~\{\}:~\{\},~\{\}:~\{\}\}\)$"
                 (second props-form))
        "the narrow lowering is one ordered literal, not an assignment IIFE")))
