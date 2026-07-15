(ns re-frame.ui.emit-cljs-lowering-cljs-test
  "Focused golden for the production JSX call shape (rf2-dj7pav)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.emit-cljs :as emit]
            [re-frame.ui.compiler.env :as env]))

(defn- resolve-sym [sym]
  (case sym
    child-view {:fqn 'app.views/child-view
                :meta {:rf.ui/view true :rf.ui/children? true}}
    ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
    raw-fn {:fqn 're-frame.ui/raw-fn :meta {}}
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

(deftest one-class-flag-with-static-base-is-a-binary-string-choice
  (let [call       (first (jsx-calls
                           (emitted '[:div.todo-item
                                      {:class {:done done?}}])))
        props-form (nth call 4)
        class-form (nth props-form 3)]
    (is (= '(if done? "todo-item done" "todo-item") class-form)
        "one flag evaluates its condition once and needs no generic str")
    (is (= 1 (count (filter #{'done?} (forms-of class-form))))))
  (let [call       (first (jsx-calls
                           (emitted '[:div {:class {:done done?}}])))
        props-form (nth call 4)]
    (is (some #{'re-frame.ui.rules/classes-str} (forms-of props-form))
        "without a static base the general empty-class semantics remain")))

(deftest event-sites-lower-to-committed-runtime-callbacks
  (let [ordinary   (emitted '[:button {:on-click [:cart/add id]} "add"])
        controlled (emitted '[:input {:value value
                                      :on-input [:form/typed :rf.ui/value]}])
        once-form  (emitted '[:button {:on-click {:event [:save]
                                                   :once true}} "save"])
        dynamic    (emitted '[:button {:on-click handler-value} "go"])
        data-call  (first (filter #(and (seq? %)
                                       (= 're-frame.ui.events/data-handler
                                          (first %)))
                                  (forms-of controlled)))
        dyn-call   (first (filter #(and (seq? %)
                                       (= 're-frame.ui.events/dynamic-handler
                                          (first %)))
                                  (forms-of dynamic)))
        once-call  (first (filter #(and (seq? %)
                                       (= 're-frame.ui.events/data-handler
                                          (first %)))
                                  (forms-of once-form)))]
    (is (some #{'re-frame.ui.events/data-handler} (forms-of ordinary)))
    (is (not-any? #{'re-frame.ui.runtime/dispatch-event!}
                  (forms-of ordinary))
        "the staging dispatch hook is no longer generated")
    (is (some? data-call))
    (is (odd? (nth data-call 3))
        "bit 0 is the compile-proven controlled-input synchronous door")
    (is (pos? (bit-and 8 (nth once-call 3)))
        "bit 3 carries the committed site's once policy")
    (is (some? dyn-call)
        "runtime-classified values still enter the per-site commit boundary")))

(deftest passive-handler-maps-lower-to-one-native-ref-not-a-react-handler
  (let [form (emitted
              '[:button {:ref (raw-fn authored-ref)
                         :on-wheel {:event [:scroll/tick]
                                    :passive true
                                    :capture true
                                    :once true}}
                "scroll"])
        printed (pr-str form)]
    (is (some #{'re-frame.ui.events/passive-ref} (forms-of form))
        "the native listener composes through the element's sole ref prop")
    (is (not (str/includes? printed "onWheelCapture"))
        "the passive site never also installs React's synthetic handler")
    (is (some #{'authored-ref} (forms-of form))
        "the authored object/callback ref is carried into the composition")))

(deftest passive-lowering-preserves-ref-explicitness-and-host-event-spelling
  (let [unmarked (emitted
                  '[:button {:ref authored-ref
                             :on-wheel {:event [:scroll/tick]
                                        :passive true}}])
        explicit (emitted
                  '[:button {:ref (raw-fn authored-ref)
                             :on-wheel {:event [:scroll/tick]
                                        :passive true}}])
        dom-event (emitted
                   '[:div {:on-key-down {:event [:key/down]
                                         :passive true}}])
        custom-event (emitted
                      '[:rf-probe {:on-my-event {:event [:probe/fired]
                                                 :passive true}}])]
    (is (some #{'re-frame.ui.runtime/assert-object-ref!} (forms-of unmarked))
        "an unmarked dynamic ref is guarded as an object-ref position")
    (is (not-any? #{'re-frame.ui.runtime/assert-object-ref!}
                  (forms-of explicit))
        "ui/raw-fn is the explicit callback-ref capability")
    (is (= 1 (count (filter #{'authored-ref} (forms-of unmarked))))
        "the guarded dynamic ref expression is evaluated once")
    (is (str/includes? (pr-str dom-event) "\"keydown\"")
        "native DOM event spelling retains the standard normalization")
    (is (str/includes? (pr-str custom-event) "\"my-event\"")
        "custom-element event tails are verbatim")
    (is (not (str/includes? (pr-str custom-event) "\"myevent\"")))))

(deftest keyed-passive-ownership-carries-the-once-bound-row-key
  (let [key-expr '(:id row)
        form (emitted
              '(for [row rows]
                 [:button {:key (:id row)
                           :on-click {:event [:row/click]
                                      :passive true}}
                  "row"]))
        passive-call (first (filter #(and (seq? %)
                                          (= 're-frame.ui.events/passive-ref
                                             (first %)))
                                    (forms-of form)))
        occurrence-path (nth passive-call 3)
        row-key-sym (first occurrence-path)]
    (is (= 1 (count (filter #{key-expr} (forms-of form))))
        "the authored row key expression is evaluated once")
    (is (= 1 (count occurrence-path))
        "the native-ref owner receives the enclosing keyed occurrence")
    (is (symbol? row-key-sym))
    (is (> (count (filter #{row-key-sym} (forms-of form))) 2)
        "one binding feeds duplicate checking, React key, and passive ownership")))

(deftest prototype-setter-key-is-computed-on-every-literal-props-surface
  (doseq [[surface form]
          [[:dom '[:div {:__proto__ value}]]
           [:custom-element '[:proto-widget {:__proto__ value}]]
           [:view '[child-view {:__proto__ value}]]
           [:foreign '[ForeignComp {:__proto__ value}]]]]
    (let [call       (first (jsx-calls (emitted form)))
          props-form (nth call 4)]
      (is (= 'js* (first props-form)) (name surface))
      (is (= "({[~{}]:~{}})" (second props-form))
          (str (name surface) " avoids object-literal prototype-setter grammar"))
      (is (= (if (contains? #{:dom :custom-element} surface)
               '("__proto__" (re-frame.ui.runtime/attr-val value))
               '("__proto__" value))
             (drop 2 props-form))
          (str (name surface) " retains the authored own property and value")))))
