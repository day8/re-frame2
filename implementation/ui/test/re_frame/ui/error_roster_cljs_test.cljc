(ns re-frame.ui.error-roster-cljs-test
  "THE frozen S1e compile-error roster (rf2-vxgfnd.5; Spec 004 rewrite
  §Template grammar 'compile-error roster with didactic messages').

  One grammar-driven table per tier asserts, for EVERY rejected form:

    1. the STABLE id — `{:rf.ui.compile/error <id>}` ex-data (S1b pinned
       the ids; this roster freezes the full set: renames/additions/
       removals are roster changes and must edit `frozen-error-roster`);
    2. the DIDACTIC message — each message NAMES THE ESCAPE (the correct
       spelling the author should use), pinned by substring (stable in
       meaning, not bytes);
    3. file:line anchoring — errors thrown through the `defview` /
       `custom-element` expansion path carry :file/:line ex-data
       (JVM-asserted; the macro JVM expands for both hosts).

  CLASSIFICATION (the S1e compile-vs-runtime split): every
  `:rf.ui.compile/*` id is a COMPILE diagnostic — thrown at
  macroexpansion, never emitted at runtime, never a trace — so NONE
  needs a Spec 009 catalogue row (Conventions `:rf.ui.compile/*`
  reservation). The runtime tier is the `:rf.error/ui-*` family +
  `:rf.error/jvm-host-op`, all seven catalogued by the S1b slice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.header :as header]
            #?(:clj [re-frame.ui :as ui])))

;; ---------------------------------------------------------------------------
;; The frozen roster
;; ---------------------------------------------------------------------------

(def frozen-error-roster
  "Every stable compile-error id. FROZEN: a diff to this set is a
  contract change to the S1e roster, not a refactor."
  #{;; heads + children
    :rf.ui.compile/dynamic-head
    :rf.ui.compile/unresolved-head
    :rf.ui.compile/bad-tag
    :rf.ui.compile/duplicate-id-sugar
    :rf.ui.compile/keyword-child
    :rf.ui.compile/markup-returning-map
    :rf.ui.compile/lazy-seq-child
    :rf.ui.compile/unsupported-form
    ;; keyed lists + finite sites
    :rf.ui.compile/unkeyed-list-item
    :rf.ui.compile/constant-list-key
    :rf.ui.compile/nested-for-body
    :rf.ui.compile/bad-for
    :rf.ui.compile/sub-in-loop
    :rf.ui.compile/lease-in-loop
    ;; handlers
    :rf.ui.compile/loop-capturing-handler
    :rf.ui.compile/bad-event-vector
    :rf.ui.compile/bad-handler-options
    :rf.ui.compile/contradictory-handler-options
    :rf.ui.compile/handler-option-unavailable-s1
    ;; props
    :rf.ui.compile/bad-class
    :rf.ui.compile/bad-style
    :rf.ui.compile/rejected-prop-spelling
    :rf.ui.compile/bare-fn-prop
    :rf.ui.compile/bare-fn-ref
    :rf.ui.compile/ref-on-view-s1
    :rf.ui.compile/dynamic-props-map
    :rf.ui.compile/bad-spread
    :rf.ui.compile/non-keyword-prop
    :rf.ui.compile/id-sugar-conflict
    :rf.ui.compile/collection-attr-value
    ;; structure
    :rf.ui.compile/void-children
    :rf.ui.compile/html-not-sole-child
    :rf.ui.compile/bad-html
    :rf.ui.compile/bad-raw
    :rf.ui.compile/raw-fn-child
    :rf.ui.compile/children-prop
    :rf.ui.compile/children-not-accepted
    :rf.ui.compile/undeclared-prop
    :rf.ui.compile/bad-fragment-props
    ;; control forms
    :rf.ui.compile/multi-form-body
    :rf.ui.compile/bad-cond
    :rf.ui.compile/bad-let
    :rf.ui.compile/bad-if
    ;; declaration grammar (defview / custom-element / header)
    :rf.ui.compile/bad-defview-args
    :rf.ui.compile/positional-args
    :rf.ui.compile/key-prop-declared
    :rf.ui.compile/ref-prop-declared-s1
    :rf.ui.compile/unknown-option
    :rf.ui.compile/bad-view-id
    :rf.ui.compile/bad-custom-element})

(def frozen-warning-roster
  "Dev WARNINGS (env/warn!, never thrown) — same namespace, same freeze."
  #{:rf.ui.compile/placeholder-not-top-level
    :rf.ui.compile/bare-fn-in-loop})

(def ^:private jvm-tier-ids
  "Ids thrown only inside the JVM-side expansion pipeline
  (`defview*` / `custom-element*` — the macro JVM expands for BOTH
  hosts, so these hold for the CLJS path too)."
  #{:rf.ui.compile/unknown-option
    :rf.ui.compile/bad-view-id
    :rf.ui.compile/bad-custom-element})

(def ^:private direct-call-ids
  "Ids exercised by a direct analyzer-fn call below (defensive sites the
  hiccup structural pins make unreachable through `analyze` itself)."
  #{:rf.ui.compile/dynamic-props-map})

;; ---------------------------------------------------------------------------
;; Injected environment (pure analyzer — identical on both hosts)
;; ---------------------------------------------------------------------------

(def ^:private core-heads
  (into #{} (map (comp symbol name))
        (concat ana/markup-map-fqns ana/lazy-seq-fqns)))

(defn- resolver [sym]
  (if (contains? core-heads sym)
    {:fqn (symbol "clojure.core" (name sym)) :meta {}}
    (case sym
      sub         {:fqn 're-frame.ui/sub :meta {}}
      lease       {:fqn 're-frame.ui/lease :meta {}}
      raw         {:fqn 're-frame.ui/raw :meta {}}
      html        {:fqn 're-frame.ui/html :meta {}}
      raw-fn      {:fqn 're-frame.ui/raw-fn :meta {}}
      spread      {:fqn 're-frame.ui/spread :meta {}}
      child-view  {:fqn 'app.views/child-view
                   :meta {:rf.ui/view true :rf.ui/children? true}}
      leaf-view   {:fqn 'app.views/leaf-view
                   :meta {:rf.ui/view true :rf.ui/children? false}}
      closed-view {:fqn 'app.views/closed-view
                   :meta {:rf.ui/view true :rf.ui/closed-prop-keys [:a :b]}}
      ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
      nil)))

(defn- mk-env []
  (-> (env/make-env {:host :clj :ns-sym 'app.roster
                     :self 'self-view :self-id :app.roster/self-view
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn- reject
  "Analyze `form`; -> the thrown compile-error ExceptionInfo, nil when
  accepted."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      ex)))

(defn- assert-row!
  "One roster row: `form` throws `id`, and the message contains every
  `names` fragment (the escape the author should use)."
  [ex id form names]
  (is (some? ex) (str (pr-str form) " must be rejected [" id "]"))
  (when ex
    (is (= id (:rf.ui.compile/error (ex-data ex)))
        (str (pr-str form) " carries its frozen roster id"))
    (doseq [n names]
      (is (str/includes? (ex-message ex) n)
          (str id " must name the escape " (pr-str n)
               " — got: " (ex-message ex))))))

;; ---------------------------------------------------------------------------
;; Analyzer tier — template grammar
;; ---------------------------------------------------------------------------

(def analyzer-roster
  "[id rejected-form [escape-naming fragments]] — the template-grammar
  rejection table. Every distinct throw SITE has a row."
  [;; heads
   [:rf.ui.compile/dynamic-head '[(if x :div :span) "y"] ["ui/raw"]]
   [:rf.ui.compile/dynamic-head '(let [h child-view] [h {}]) ["ui/raw"]]
   [:rf.ui.compile/unresolved-head '[nope-not-a-thing {}] ["declare ^:rf.ui/view"]]
   [:rf.ui.compile/bad-tag [:ns/div "x"] ["write :div"]]
   [:rf.ui.compile/bad-tag [:.card "x"] [":div.card"]]
   [:rf.ui.compile/duplicate-id-sugar [:div#a#b "x"] ["one #id"]]
   ;; children position
   [:rf.ui.compile/keyword-child [:div :oops] ["\"oops\""]]
   [:rf.ui.compile/markup-returning-map '[:ul (map render-item items)] ["(for ["]]
   [:rf.ui.compile/markup-returning-map '[:ul (mapcat render-rows groups)] ["(for ["]]
   [:rf.ui.compile/lazy-seq-child '[:ul (filter visible? items)] ["(for [" "str/join"]]
   [:rf.ui.compile/unsupported-form {:not "renderable"} ["(str"]]
   ;; keyed lists + finite sites
   [:rf.ui.compile/unkeyed-list-item '(for [x xs] [:li x]) [":key"]]
   [:rf.ui.compile/unkeyed-list-item '(for [x xs] (str x)) ["keyed element"]]
   [:rf.ui.compile/constant-list-key '(for [x xs] [:li {:key 1} x]) ["vary per row"]]
   [:rf.ui.compile/nested-for-body '(for [x xs] (for [y x] [:li {:key y} y])) ["ONE for"]]
   [:rf.ui.compile/bad-for '(for [x xs :unknown y] [:li {:key x} x]) [":let / :when / :while"]]
   [:rf.ui.compile/bad-for '(for [] [:li {:key 1} "x"]) ["seq-exprs"]]
   [:rf.ui.compile/sub-in-loop '(for [x xs] [:li {:key x} (sub [:q x])]) ["keyed child view"]]
   [:rf.ui.compile/lease-in-loop '(for [x xs] [:li {:key x} (str (lease {:r x}))]) ["keyed child view"]]
   ;; handlers
   [:rf.ui.compile/loop-capturing-handler
    '(for [t ts] [:li {:key (:id t) :on-click [::open (:id t)]} "x"])
    ["keyed child view" "as a prop"]]
   [:rf.ui.compile/bad-event-vector '[:button {:on-click [event-sym 1]} "x"] ["[:domain/event"]]
   [:rf.ui.compile/bad-handler-options
    '[:button {:on-click {:event [:a/b] :bubble true}} "x"] [":stop-propagation"]]
   [:rf.ui.compile/bad-handler-options
    '[:button {:on-click {:prevent-default true}} "x"] [":event [:domain/event"]]
   [:rf.ui.compile/contradictory-handler-options
    '[:button {:on-click {:event [:a/b] :passive true :prevent-default true}} "x"]
    ["preventDefault" "Drop one"]]
   [:rf.ui.compile/handler-option-unavailable-s1
    '[:button {:on-click {:event [:a/b] :passive true}} "x"] ["S3" "Remove the option"]]
   [:rf.ui.compile/handler-option-unavailable-s1
    '[:button {:on-click {:event [:a/b] :once true}} "x"] ["S3"]]
   ;; props
   [:rf.ui.compile/bad-class '[:div {:class {(kw) true}} "x"] ["literal names"]]
   [:rf.ui.compile/bad-style '[:div {:style {(kw) 1}} "x"] ["dynamic expression"]]
   [:rf.ui.compile/rejected-prop-spelling '[:div {:class-name "x"}] [":class"]]
   [:rf.ui.compile/rejected-prop-spelling
    '[:div {:dangerouslySetInnerHTML {:__html "x"}}] ["(ui/html"]]
   [:rf.ui.compile/bare-fn-prop '[:div {:data-cb (fn [x] x)} "x"] ["ui/raw-fn"]]
   [:rf.ui.compile/bare-fn-prop '[ForeignComp {:on-select (fn [x] x)}] ["ui/raw-fn"]]
   [:rf.ui.compile/bare-fn-ref '[:div {:ref (fn [el] el)} "x"] ["(ui/raw-fn f)"]]
   [:rf.ui.compile/ref-on-view-s1 '[child-view {:ref r}] ["S3"]]
   [:rf.ui.compile/bad-spread '[:div (spread)] ["(ui/spread base"]]
   [:rf.ui.compile/bad-spread '(spread base) ["props position"]]
   [:rf.ui.compile/non-keyword-prop '[:div {"str-key" 1} "x"] ["literal keywords"]]
   [:rf.ui.compile/non-keyword-prop '[child-view {"k" 1}] ["literal keywords"]]
   [:rf.ui.compile/id-sugar-conflict '[:div#a {:id "b"}] ["Keep one"]]
   [:rf.ui.compile/collection-attr-value '[:div {:data-foo {:a 1}} "x"] ["str/join"]]
   ;; structure + interop positions
   [:rf.ui.compile/void-children [:br "child"] ["cannot have children"]]
   [:rf.ui.compile/html-not-sole-child '[:div [:span "s"] (html "<b>x</b>")] ["[:div (ui/html s)]"]]
   [:rf.ui.compile/html-not-sole-child '(html "<b>x</b>") ["[:div (ui/html s)]"]]
   [:rf.ui.compile/bad-html '[:div (html "a" "b")] ["exactly one argument"]]
   [:rf.ui.compile/bad-html '[:div (html 42)] ["requires a string"]]
   [:rf.ui.compile/bad-raw '(raw) ["one argument"]]
   [:rf.ui.compile/raw-fn-child '(raw-fn f) ["prop positions"]]
   [:rf.ui.compile/children-prop '[child-view {:children [x]}] ["positional"]]
   [:rf.ui.compile/children-not-accepted '[leaf-view {} [:p "kid"]] [":children"]]
   [:rf.ui.compile/undeclared-prop '[closed-view {:a 1 :c 3}] ["declared:"]]
   [:rf.ui.compile/bad-fragment-props '[:<> {:key k :class "x"} [:p "a"]] ["{:key"]]
   ;; control forms
   [:rf.ui.compile/multi-form-body '(when c [:p "a"] [:p "b"]) ["[:<>"]]
   [:rf.ui.compile/bad-cond '(cond a) [":else"]]
   [:rf.ui.compile/bad-let '(let [x] [:p "a"]) ["even bindings"]]
   [:rf.ui.compile/bad-let '(letfn (f) [:p "a"]) ["fnspecs vector"]]
   [:rf.ui.compile/bad-if '(if a [:p "a"] [:p "b"] [:p "c"]) ["test then else"]]])

(deftest analyzer-tier-roster
  (doseq [[id form names] analyzer-roster]
    (testing (str id " <- " (pr-str form))
      (assert-row! (reject form) id form names))))

(deftest dynamic-props-map-diagnostic
  ;; hiccup structure makes a non-map/non-spread props position a CHILD,
  ;; so the guard is only reachable by direct call — it stays didactic.
  (let [ex (try
             (ana/analyze-element-props
              (mk-env) {:tag :div :id nil :classes []} false 'props-expr)
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) ex ex))]
    (assert-row! ex :rf.ui.compile/dynamic-props-map 'props-expr ["ui/spread"])))

(deftest lazy-seq-vocabulary-sweep
  ;; EVERY member of the closed raw-lazy-seq vocabulary rejects in child
  ;; position, naming both escapes (keyed for-rows; str/join text).
  (doseq [head (into (sorted-set) (map (comp symbol name)) ana/lazy-seq-fqns)]
    (let [form [:ul (list head 'xs)]]
      (testing (str "lazy-seq-child <- " (pr-str form))
        (assert-row! (reject form) :rf.ui.compile/lazy-seq-child form
                     [(str "(" head) "(for [" "str/join"])))))

(deftest markup-map-vocabulary-sweep
  (doseq [head (into (sorted-set) (map (comp symbol name)) ana/markup-map-fqns)]
    (let [form [:ul (list head 'f 'xs)]]
      (testing (str "markup-returning-map <- " (pr-str form))
        (assert-row! (reject form) :rf.ui.compile/markup-returning-map form
                     ["(for ["])))))

(deftest lazy-heads-stay-legal-in-opaque-positions
  ;; only RENDERED CONTENT rejects raw seqs — expression positions are
  ;; opaque (prop values, for-collections, if-tests).
  (is (nil? (reject '[:div {:data-n (filter visible? items)} "x"]))
      "prop values are opaque expressions")
  (is (nil? (reject '(for [x (filter visible? items)] [:li {:key x} x])))
      "the for collection is an opaque expression")
  (is (nil? (reject '(if (seq items) [:p "some"] [:p "none"])))
      "test positions are opaque expressions"))

;; ---------------------------------------------------------------------------
;; Warning tier
;; ---------------------------------------------------------------------------

(defn- warnings-for [form]
  (let [e (mk-env)]
    (ana/analyze e form)
    @(:warnings e)))

(deftest warning-tier-roster
  (let [[w :as ws] (warnings-for '(for [x xs] [:li {:key x :on-click (fn [] x)} "y"]))]
    (is (= [:rf.ui.compile/bare-fn-in-loop] (mapv :id ws)))
    (is (str/includes? (:msg w) "event vector or a keyed child view")
        "bare-fn-in-loop names both escapes"))
  (let [[w :as ws] (warnings-for '[:button {:on-click [:a/b {:v :rf.ui/value}]} "x"])]
    (is (= [:rf.ui.compile/placeholder-not-top-level] (mapv :id ws)))
    (is (str/includes? (:msg w) "top-level")
        "placeholder-not-top-level names the splice rule")))

;; ---------------------------------------------------------------------------
;; Header tier — the Q2 surface (host-shared fns; runs on both hosts)
;; ---------------------------------------------------------------------------

(def header-roster
  "[id argv [escape-naming fragments]] — defview header rejections."
  [[:rf.ui.compile/positional-args '[a b] ["one props map"]]
   [:rf.ui.compile/key-prop-declared '[{k :key}] ["call site"]]
   [:rf.ui.compile/ref-prop-declared-s1 '[{r :ref}] ["S3"]]
   [:rf.ui.compile/bad-defview-args '[{:strs [a]}] [":keys"]]
   [:rf.ui.compile/bad-defview-args '["nope"] ["map-destructuring"]]
   [:rf.ui.compile/bad-defview-args '[{:keys [a] :or [a 1]}] [":or needs a map"]]
   [:rf.ui.compile/bad-defview-args '[{:foo [a]}] ["supported: :keys"]]])

(deftest header-tier-roster
  (doseq [[id argv names] header-roster]
    (testing (str id " <- " (pr-str argv))
      (let [ex (try
                 (header/parse-header argv)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo
                           :cljs cljs.core/ExceptionInfo) ex ex))]
        (assert-row! ex id argv names)))))

;; ---------------------------------------------------------------------------
;; Completeness — the freeze
;; ---------------------------------------------------------------------------

(deftest roster-is-frozen-and-complete
  (is (= frozen-error-roster
         (-> #{}
             (into (map first) analyzer-roster)
             (into (map first) header-roster)
             (into direct-call-ids)
             (into jvm-tier-ids)))
      (str "every frozen id has an exercised rejected form, and no id is "
           "thrown outside the frozen roster — additions/renames edit "
           "frozen-error-roster deliberately")))

;; ---------------------------------------------------------------------------
;; JVM pipeline tier — defview / custom-element expansion (the macro JVM
;; expands for both hosts, so these pins hold for the CLJS path too)
;; ---------------------------------------------------------------------------

#?(:clj
   (do

(defn- expand-ex
  "Macroexpand; -> the compile-error ExceptionInfo (unwrapping the
  CompilerException some paths add), nil when it expands."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex ex)
    (catch Exception ex
      (let [c (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo c) c)))))

(def jvm-roster
  "[id declaration-form [escape-naming fragments]]."
  [[:rf.ui.compile/bad-defview-args
    '(re-frame.ui/defview v [:div "no argv"]) ["argument vector is missing"]]
   [:rf.ui.compile/multi-form-body
    '(re-frame.ui/defview v [] [:div "a"] [:div "b"]) ["ONE template form"]]
   [:rf.ui.compile/unknown-option
    '(re-frame.ui/defview v {:memo false} [] [:div]) [":display-name"]]
   [:rf.ui.compile/bad-view-id
    '(re-frame.ui/defview v {:id :unqualified} [] [:div]) ["qualified keyword"]]
   [:rf.ui.compile/key-prop-declared
    '(re-frame.ui/defview v {:props [:map [:key :string]]} [] [:div "x"])
    ["call site"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :plain {}) ["containing '-'"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el "nope") ["options map"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el {:events []}) ["{:properties"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el {:properties [:a]}) ["literal set"]]])

(deftest jvm-pipeline-roster
  (doseq [[id form names] jvm-roster]
    (testing (str id " <- " (pr-str form))
      (assert-row! (expand-ex form) id form names)))
  (is (every? frozen-error-roster (map first jvm-roster))))

(deftest compile-errors-carry-source-anchor
  ;; S1e file:line anchoring: errors thrown through the expansion path
  ;; carry the declaration form's source coordinates in ex-data (the
  ;; meta &form carries — attached explicitly here so the assertion is
  ;; deterministic across readers).
  (doseq [form ['(re-frame.ui/defview v [a b] [:div a])
                '(re-frame.ui/defview v [] [:ul (map f xs)])
                '(re-frame.ui/custom-element :plain {})]]
    (let [data (ex-data (expand-ex (with-meta form {:line 42 :column 7})))]
      (is (some? (:file data)) (str (pr-str form) " anchors :file"))
      (is (= 42 (:line data)) (str (pr-str form) " anchors :line"))
      (is (= 7 (:column data)) (str (pr-str form) " anchors :column"))
      (is (some? (:rf.ui.compile/error data)) "id survives anchoring"))))

))
