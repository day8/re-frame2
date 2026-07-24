(ns re-frame.freehand.emit-cljs-lowering-cljs-test
  "Focused golden for the production JSX call shape (rf2-dj7pav)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.emit-cljs :as emit]
            [re-frame.freehand.compiler.env :as env]))

(defn- resolve-sym [sym]
  (case sym
    child-view {:fqn 'app.views/child-view
                :meta {:rf.ui/view true :rf.ui/children? true}}
    ;; a NAMESPACE alias resolving a stamped view var (`:require [app.views :as v]`)
    v/card     {:fqn 'app.views/card :meta {:rf.ui/view true}}
    ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
    raw-fn {:fqn 're-frame.freehand/raw-fn :meta {}}
    event  {:fqn 're-frame.freehand/event :meta {}}
    v/spread      {:fqn 're-frame.freehand/spread :meta {}}
    v/spread-safe {:fqn 're-frame.freehand/spread-safe :meta {}}
    v/html        {:fqn 're-frame.freehand/html :meta {}}
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
  '#{re-frame.freehand.runtime/jsx2 re-frame.freehand.runtime/jsx3
     re-frame.freehand.runtime/jsxs2 re-frame.freehand.runtime/jsxs3})

(deftest compiler-authored-sites-call-jsx-runtime-module-directly
  (let [form  (emitted '[:div [child-view {:label label}] [:span "tail"]])
        calls (vec (jsx-calls form))]
    (is (seq calls))
    (is (every? #(= 're-frame.freehand.runtime/jsx-runtime
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
    (is (= '("data-z" (re-frame.freehand.runtime/attr-val (mark! :first))
             "checked" (re-frame.freehand.runtime/attr-val (mark! :second)))
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
    (is (some #{'re-frame.freehand.rules/classes-str} (forms-of props-form))
        "without a static base the general empty-class semantics remain")))

(deftest event-sites-lower-to-committed-runtime-callbacks
  (let [ordinary   (emitted '[:button {:on-click [:cart/add id]} "add"])
        controlled (emitted '[:input {:value value
                                      :on-input [:form/typed :rf.ui/value]}])
        once-form  (emitted '[:button {:on-click {:event [:save]
                                                   :once true}} "save"])
        dynamic    (emitted '[:button {:on-click handler-value} "go"])
        data-call  (first (filter #(and (seq? %)
                                       (= 're-frame.freehand.events/data-handler
                                          (first %)))
                                  (forms-of controlled)))
        dyn-call   (first (filter #(and (seq? %)
                                       (= 're-frame.freehand.events/dynamic-handler
                                          (first %)))
                                  (forms-of dynamic)))
        once-call  (first (filter #(and (seq? %)
                                       (= 're-frame.freehand.events/data-handler
                                          (first %)))
                                  (forms-of once-form)))]
    (is (some #{'re-frame.freehand.events/data-handler} (forms-of ordinary)))
    (is (not-any? #{'re-frame.freehand.runtime/dispatch-event!}
                  (forms-of ordinary))
        "the staging dispatch hook is no longer generated")
    (is (some? data-call))
    (is (odd? (nth data-call 3))
        "bit 0 is the compile-proven controlled-input synchronous door")
    (is (pos? (bit-and 8 (nth once-call 3)))
        "bit 3 carries the committed site's once policy")
    (is (some? dyn-call)
        "runtime-classified values still enter the per-site commit boundary")))

(deftest controlled-ui-event-lowers-to-event-handler-through-the-sync-door
  (let [controlled (emitted '[:input {:value value
                                      :on-input (event [e]
                                                  [:form/typed (.. e -target -value)])}])
        uncontrolled (emitted '[:input {:on-input (event [e]
                                                    [:log (.. e -target -value)])}])
        ev-call    (first (filter #(and (seq? %)
                                        (= 're-frame.freehand.events/event-handler
                                           (first %)))
                                  (forms-of controlled)))
        un-call    (first (filter #(and (seq? %)
                                        (= 're-frame.freehand.events/event-handler
                                           (first %)))
                                  (forms-of uncontrolled)))]
    (is (some? ev-call)
        "a v/event handler lowers to the compiler-known event-handler seam")
    (is (= 're-frame.freehand.events/callback (first (nth ev-call 2)))
        (str "the analyzer's ONE lowering of (v/event …) — the roster constructor "
             "the interpreted macro expands to — is passed for invocation with "
             "the native event"))
    (is (odd? (nth ev-call 3))
        "bit 0 rides the controlled-input synchronous door, as a literal vector does")
    (is (some? un-call))
    (is (even? (nth un-call 3))
        "an uncontrolled v/event site batches — no sync bit")
    (is (not-any? #{'re-frame.freehand.events/dynamic-handler} (forms-of controlled))
        "v/event is compiler-known, not runtime-classified like a dynamic value")))

(deftest passive-handler-maps-lower-to-one-native-ref-not-a-react-handler
  (let [form (emitted
              '[:button {:ref (raw-fn authored-ref)
                         :on-wheel {:event [:scroll/tick]
                                    :passive true
                                    :capture true
                                    :once true}}
                "scroll"])
        printed (pr-str form)]
    (is (some #{'re-frame.freehand.events/passive-ref} (forms-of form))
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
    (is (some #{'re-frame.freehand.runtime/assert-object-ref!} (forms-of unmarked))
        "an unmarked dynamic ref is guarded as an object-ref position")
    (is (not-any? #{'re-frame.freehand.runtime/assert-object-ref!}
                  (forms-of explicit))
        "v/raw-fn is the explicit callback-ref capability")
    (is (= 1 (count (filter #{'authored-ref} (forms-of unmarked))))
        "the guarded dynamic ref expression is evaluated once")
    (is (str/includes? (pr-str dom-event) "\"keydown\"")
        "native DOM event spelling retains the standard normalization")
    (is (str/includes? (pr-str custom-event) "\"my-event\"")
        "custom-element event tails are verbatim")
    (is (not (str/includes? (pr-str custom-event) "\"myevent\"")))))

(deftest declared-ref-forwards-onto-the-internal-view-props-object
  ;; rf2-u53yy.3: React 19 ref-as-prop — passing :ref to an internal-view call
  ;; carries it on the props object under the `ref` key; the callee accepts it by
  ;; declaring :ref in its header. Object refs pass through verbatim (no
  ;; element-only object-ref guard at the seam — the view forwards like a foreign
  ;; component); a (v/raw-fn f) callback ref forwards its fn.
  (let [form       (emitted '[child-view {:label label :ref my-ref}])
        call       (first (jsx-calls form))
        props-form (nth call 4)]
    (is (some #{"ref"} (forms-of props-form))
        "the forwarded ref rides the props object under the `ref` key")
    (is (some #{'my-ref} (forms-of props-form))
        "the authored object ref is carried into the view's props")
    (is (= 're-frame.freehand.runtime/jsx-runtime (nth call 2))
        "the forwarded-ref view still lowers through the ordinary jsx runtime")
    (is (not-any? #{'re-frame.freehand.runtime/assert-object-ref!} (forms-of props-form))
        "the object ref forwards verbatim at the view seam, as at a foreign one"))
  (testing "a (v/raw-fn f) callback ref forwards its fn to the internal view"
    (let [props-form (nth (first (jsx-calls (emitted '[child-view {:ref (raw-fn cb)}]))) 4)]
      (is (some #{"ref"} (forms-of props-form)))
      (is (some #{'cb} (forms-of props-form))
          "the explicit callback-ref fn is forwarded verbatim"))))

(deftest keyed-passive-ownership-carries-the-once-bound-row-key
  (let [key-expr '(:id row)
        form (emitted
              '(for [row rows]
                 [:button {:key (:id row)
                           :on-click {:event [:row/click]
                                      :passive true}}
                  "row"]))
        passive-call (first (filter #(and (seq? %)
                                          (= 're-frame.freehand.events/passive-ref
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
               '("__proto__" (re-frame.freehand.runtime/attr-val value))
               '("__proto__" value))
             (drop 2 props-form))
          (str (name surface) " retains the authored own property and value")))))

;; ---------------------------------------------------------------------------
;; DEV bare-view-alias diagnostic (rf2-vxgfnd.95.15) — the emit-side wiring.
;; The COMPILE stage cannot tell a bare `(def alias other/view)` copy from a
;; genuine foreign React component (both resolve without `:rf.ui/view` meta), so
;; EVERY foreign head carries the DEV-gated runtime guard; the runtime shell-
;; marker check does the discrimination. View heads (canonical + namespace
;; alias) resolve the stamped var, classify :view, and stay quiet — zero cost.
;; ---------------------------------------------------------------------------

(defn- bare-alias-guard [form]
  (first (filter #(and (seq? %)
                       (= 'if (first %))
                       (some #{'re-frame.freehand.runtime/warn-bare-view-alias!}
                             (forms-of %)))
                 (forms-of form))))

(deftest foreign-head-carries-the-dev-gated-bare-view-alias-guard
  (let [form  (emitted '[ForeignComp {:x 1}])
        guard (bare-alias-guard form)]
    (is (some? guard)
        "a foreign-classified head is consulted at runtime for a bare view-alias")
    (is (= 'js/goog.DEBUG (second guard))
        "the guard is goog.DEBUG-gated so :advanced elides it in production")
    (is (= '(re-frame.freehand.runtime/warn-bare-view-alias! ForeignComp) (nth guard 2))
        "the dev arm consults the shell marker, passing the authored head")
    (is (= 'ForeignComp (nth guard 3))
        "the production arm is the bare head verbatim — no residue")))

(deftest canonical-and-namespace-alias-view-heads-stay-quiet
  (testing "a canonical defview head emits no guard (compile-time quiet)"
    (is (nil? (bare-alias-guard (emitted '[child-view {:label label}])))))
  (testing "a namespace-aliased view head resolves the stamped var and stays quiet"
    (is (nil? (bare-alias-guard (emitted '[v/card {:x 1}])))))
  (testing "no view head ever routes through the runtime bare-view-alias guard"
    (is (not-any? #{'re-frame.freehand.runtime/warn-bare-view-alias!}
                  (forms-of (emitted '[:div [child-view {:label l}]
                                       [v/card {:x 1}]]))))))

;; ---------------------------------------------------------------------------
;; v/spread at a FOREIGN component call site (rf2-u53yy.5)
;; ---------------------------------------------------------------------------

(deftest foreign-spread-merges-the-forwarded-map-under-the-literal-props
  (let [form  (emitted '[ForeignComp
                         (v/spread {:selected d :on-change (event [v] [:pick v])}
                                    forwarded)])
        props (nth form 2)]
    (is (= 're-frame.freehand.runtime/jsx-spread2 (first form))
        "a foreign spread routes through the runtime-built-props jsx helper")
    (is (= '(re-frame.freehand.runtime/warn-bare-view-alias! ForeignComp)
           (nth (nth form 1) 2))
        "the foreign head keeps its dev-gated bare-view-alias guard")
    (is (= "let" (name (first props)))
        "the merged props object binds single-evaluation temporaries")
    (is (some #{'re-frame.freehand.runtime/foreign-spread-props} (forms-of props))
        "the literal props are layered over the forwarded map by foreign-spread-props")
    (is (some #{'re-frame.freehand.events/event-handler} (forms-of props))
        "the literal part's v/event compiles to a committed callback")
    (is (= 1 (count (filter #{'forwarded} (forms-of form))))
        "the forwarded runtime map evaluates exactly once")
    (is (= '(cljs.core/array) (last form))
        "no positional children on this call site")))

(deftest foreign-spread-with-a-key-selects-the-three-arg-jsx-helper
  (let [form (emitted '[ForeignComp (v/spread {:key k :a 1} m)])]
    (is (= 're-frame.freehand.runtime/jsx-spread3 (first form))
        "a literal :key in the spread's literal part selects the 3-arg spread jsx")
    (is (= 'k (nth form 3)) "the key expression rides the jsx key argument")))

;; ---------------------------------------------------------------------------
;; v/spread + v/html compose (rf2-29s75)
;;
;; The general-spread branch builds its props object at RUNTIME and never goes
;; through `element-prop-pairs`, so the compiler-owned dangerouslySetInnerHTML
;; has to be attached at that seam explicitly. Before the fix the CLJS emitter
;; dropped the markup here (empty children array, no __html) while the JVM
;; emitter kept the `tree/html` leaf — a silent dual-emitter divergence. The
;; cross-host proof lives in the parity corpus (:spread-trusted /
;; :safe-spread-trusted); these are the focused lowering assertions.
;; ---------------------------------------------------------------------------

(defn- occurrences [sym form]
  (count (filter #{sym} (forms-of form))))

(defn- html-objs [form]
  (filter #(and (seq? %)
                (= 'cljs.core/js-obj (first %))
                (= "__html" (second %)))
          (forms-of form)))

(defn- inner-html-sets [form]
  (filter #(and (seq? %)
                (= 'cljs.core/unchecked-set (first %))
                (= "dangerouslySetInnerHTML" (nth % 2 nil)))
          (forms-of form)))

(deftest general-spread-carries-trusted-markup-to-react
  (let [form     (emitted '[:div (v/spread base ovr) (v/html markup)])
        sets     (vec (inner-html-sets form))
        objs     (vec (html-objs form))
        children (last form)]
    (is (= 're-frame.freehand.runtime/jsx-spread2 (first form))
        "an unkeyed spread element still routes through the spread jsx helper")
    (is (= 1 (count sets))
        "exactly one dangerouslySetInnerHTML is attached to the spread props object")
    (is (= 1 (count objs))
        "exactly one __html carrier is built")
    (is (= '(cljs.core/js-obj "__html" markup) (first objs))
        "the authored v/html expression is the __html value, unwrapped and unsanitised")
    (is (= '(cljs.core/array) children)
        "the markup takes NO positional child slot — React's children-vs-innerHTML
         conflict cannot arise")))

(deftest spread-and-markup-expressions-evaluate-once-each-in-source-order
  (let [form    (emitted '[:div (v/spread base ovr) (v/html markup)])
        props   (nth form 2)
        [_ bindings & body] props
        init    (second bindings)]
    ;; The emitter's syntax-quoted `let` resolves against the CORE OF THE HOST
    ;; reading this .cljc — clojure.core/let on the JVM (the only host the
    ;; emitter actually runs on), cljs.core/let when this test is compiled for
    ;; the node-test build. Assert the binding form, not the core alias.
    (is (= "let" (name (first props)))
        "the runtime-built props object is bound to a single-evaluation temporary")
    (is (= 're-frame.freehand.runtime/spread->props (first init))
        "the temporary is the object spread->props returned")
    (is (every? #(= 1 (occurrences % form)) '[base ovr markup])
        "base, overrides and the markup expression each evaluate exactly once")
    (is (and (= 1 (occurrences 'base init)) (= 1 (occurrences 'ovr init)))
        "base and overrides are spread->props arguments — evaluated in authored order")
    (is (zero? (occurrences 'markup init))
        "the markup expression is NOT part of the spread call")
    (is (= 1 (occurrences 'markup (vec body)))
        "the markup evaluates in the let BODY — i.e. AFTER base and overrides,
         matching source order and the JVM emitter's children thunk")
    (is (= (last body) (first bindings))
        "the let returns the same props object it mutated")))

(deftest trusted-markup-wins-over-a-runtime-smuggled-inner-html-key
  (let [form  (emitted '[:div (v/spread base ovr) (v/html markup)])
        props (nth form 2)
        [_ _ & body] props]
    (is (= 'cljs.core/unchecked-set (ffirst body))
        "the compiler-owned prop is set AFTER spread->props returns, so the
         visible (v/html …) site — the trust assertion — wins any same-key
         value carried in through the runtime prop map (see rf2-5pr75)")))

(deftest spread-without-trusted-markup-is-unchanged
  (let [plain (emitted '[:div (v/spread base ovr) [:span "a"] [:span "b"]])]
    (is (empty? (inner-html-sets plain))
        "an ordinary spread element gains no innerHTML prop")
    (is (empty? (html-objs plain)))
    (is (some #(and (seq? %)
                    (= 're-frame.freehand.runtime/spread->props (first %)))
              (forms-of plain))
        "the runtime-built props object is still the bare spread->props call")
    (is (= 2 (count (rest (last (filter #(and (seq? %)
                                              (= 'cljs.core/array (first %)))
                                        (forms-of plain))))))
        "ordinary children still ride the spread call's children array")))

(deftest safe-spread-composes-with-trusted-markup-in-both-key-shapes
  (testing "keyless — the sibling spread form already composed; pin it"
    (let [form (emitted '[:div (v/spread-safe {:class "owned"} caller)
                          (v/html markup)])]
      (is (= 're-frame.freehand.runtime/jsx-spread2 (first form)))
      (is (= 1 (count (html-objs form))))
      (is (= '(cljs.core/array) (last form))
          "no positional child accompanies the markup")))
  (testing "keyed — the jsx-spread3 arm carries the markup too"
    (let [form (emitted '[:div (v/spread-safe {:key k :class "owned"} caller)
                          (v/html markup)])]
      (is (= 're-frame.freehand.runtime/jsx-spread3 (first form)))
      (is (= 1 (count (html-objs form))))
      (is (= 'k (nth form 3))
          "the authored key still reaches React's third argument")
      (is (= '(cljs.core/array) (last form))))))
