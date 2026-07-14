(ns re-frame.ui.analyze-reject-cljs-test
  "Template-grammar REJECT table: every rejected form throws a compile
  error carrying {:rf.ui.compile/error <id>} — the S1e didactic-message
  roster keys off these ids. Runs on both hosts (injected resolution)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.analyze-accept-cljs-test :refer [mk-env]]))

(defn reject-id
  "nil when accepted; the :rf.ui.compile/error id when rejected."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.ui.compile/error (ex-data ex)))))

(deftest heads
  (is (= :rf.ui.compile/dynamic-head
         (reject-id '[(if x :div :span) "y"]))
      "dynamic tag heads")
  (is (= :rf.ui.compile/dynamic-head
         (reject-id '(let [h child-view] [h {}])))
      "local-bound component heads are dynamic heads")
  (is (= :rf.ui.compile/unresolved-head
         (reject-id '[nope-not-a-thing {}]))
      "unresolved heads name the (declare ^:rf.ui/view ...) fix")
  (is (= :rf.ui.compile/bad-tag (reject-id [:ns/div "x"]))
      "element heads are unqualified keywords"))

(deftest children-position
  (is (= :rf.ui.compile/keyword-child (reject-id [:div :oops])))
  (is (= :rf.ui.compile/markup-returning-map
         (reject-id '[:ul (map render-item items)]))
      "markup-returning map -> use (for ...) with :key"))

(deftest keyed-lists
  (is (= :rf.ui.compile/unkeyed-list-item
         (reject-id '(for [x xs] [:li x])))
      "missing :key = build failure")
  (is (= :rf.ui.compile/unkeyed-list-item
         (reject-id '(for [x xs] (str x))))
      "for body must be a keyed element/view/fragment")
  (is (= :rf.ui.compile/constant-list-key
         (reject-id '(for [x xs] [:li {:key 1} x])))
      "a constant key guarantees duplicates")
  (is (= :rf.ui.compile/nested-for-body
         (reject-id '(for [x xs] (for [y x] [:li {:key y} y]))))
      "nested iteration = multiple binding pairs in ONE for (Q6)")
  (is (= :rf.ui.compile/bad-for
         (reject-id '(for [x xs :unknown y] [:li {:key x} x])))
      "the modifier subgrammar is :let/:when/:while (Q6)"))

(deftest finite-sites
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs] [:li {:key x} (sub [:q x])]))))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs, y (sub [:q x])] [:li {:key y} y])))
      "later coll expressions evaluate per row")
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs :let [v (sub [:q x])]] [:li {:key x} v]))))
  (is (= :rf.ui.compile/lease-in-loop
         (reject-id '(for [x xs] [:li {:key x} (str (lease {:resource x}))]))))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click [::open (sub [:q])]} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click {:event [::open (sub [:q])]}} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click (fn [_] (sub [:q]))} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:ref (raw-fn (fn [_] (sub [:q])))}])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title (mapv (fn [x] (sub [:q x])) xs)}])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title (loop [x 0] (sub [:q x]))}])))
  (is (= :rf.ui.compile/lease-in-loop
         (reject-id '[:button {:on-click (fn [_] (lease {:resource :x}))} "x"])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (if-let [x maybe] (sub [:q x]) nil)}]))
      "opaque binder macros fail loudly instead of receiving an unsound rewrite")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (-> [:q] sub)}]))
      "a macro cannot turn a bare resolved sub reference into an unindexed call")
  ;; rf2-vxgfnd.217 — a COMPUTED callee is an evaluated position, so it is
  ;; analyzed under the same rules; a callee that cannot own a finite site is
  ;; rejected didactically rather than silently swallowing an invisible read.
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title ((fn [_] (sub [:q])) 1)}]))
      "an immediately-invoked fn callee with a reactive body is a deferred site")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((-> [:q] sub) 1)}]))
      "a bare sub reference under a macro in the callee position fails loudly too")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (sub [:q])}} value] [:div x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (-> [:q] sub)}} value]
                       [:div x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(for [{:keys [x] :or {x (sub [:q])}} xs]
                       [:div {:key x} x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:button {:on-click
                               (fn [{:keys [x] :or {x (sub [:q])}}] x)}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (sub)}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (sub [:a] [:b])}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (lease)}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (lease {:a 1} {:b 2})}]))))

(deftest computed-callee-value-escape
  ;; rf2-vxgfnd.252 — a reactive authoring var (sub/lease/frame) is sound ONLY
  ;; as a compiler-owned DIRECT CALL HEAD, which the rewriter lowers to an
  ;; indexed runtime site. A BARE reactive var that instead flows as a VALUE —
  ;; into a computed callee, a let alias, an argument, or a collection — leaves
  ;; the manifest under-declaring while a public authoring var survives; the
  ;; optimized build then elides the ViewCell and the read escapes ownership.
  ;; This is the escape #5855/.217 (direct calls in computed callees) did not
  ;; close. Removing the leaf guard makes every reject row below fail (they
  ;; silently re-accept), so this deftest is the mutation fixture too.
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((if p sub inc) [:q])}]))
      "bare sub in a computed (if …) callee — the .252 counterexample")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((identity sub) [:q])}]))
      "bare sub through a computed-callee wrapper escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [f sub] (f [:q]))}]))
      "a let-bound sub alias becomes an unindexed invocation target")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (str sub)}]))
      "a bare sub passed as an argument is value flow, not a render site")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((if p lease inc) {:resource :r})}]))
      "bare lease escapes into a computed callee identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [g frame] (g))}]))
      "bare frame escapes through a let alias identically")
  ;; The soundness rule does not touch the legal forms:
  (is (nil? (reject-id '[:div {:title ((if (sub [:op]) inc dec) 1)}]))
      "a DIRECT (sub …) in the computed callee is still one indexed site (.217)")
  (is (nil? (reject-id '[:div {:title (let [sub identity] (sub query))}]))
      "a local shadowing sub is an ordinary call, never a reactive site")
  (is (nil? (reject-id '[:div {:title '((if p sub inc) [:q])}]))
      "quoted data is inert — never an invocation target"))

(deftest or-default-value-escape
  ;; rf2-dzyqis — the sibling gap PR #5874 (.252) left open. reject-reactive-
  ;; binding! catches an executable (sub …)/(lease …)/(frame) embedded in a
  ;; binding pattern, but a BARE reactive authoring var used as a destructuring
  ;; :or DEFAULT is a value, not a call. Binding patterns never pass through
  ;; expression rewriting, so that bare var flows as a VALUE into the host's
  ;; destructuring default with no compiler-owned render site — the manifest
  ;; under-declares and the optimized build elides the read. The reject is
  ;; binding-position-AWARE: every symbol the pattern BINDS is treated as a
  ;; local, so only a bare var reaching a default from OUTSIDE the pattern is
  ;; rejected. Removing the :or-default guard silently re-accepts every reject
  ;; row below, so this deftest is the mutation fixture too.
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x sub}} m] x)}]))
      "bare sub as an :or default — the rf2-dzyqis counterexample")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x lease}} m] x)}]))
      "bare lease as an :or default escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x frame}} m] x)}]))
      "bare frame as an :or default escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (fn [{:keys [x] :or {x sub}}] x)}]))
      "a bare sub :or default in an fn destructuring arg escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x (str sub)}} m] x)}]))
      "a bare sub flowing through an :or default expression escapes too")
  ;; The binding-position-aware guard leaves the legal forms alone:
  (is (nil? (reject-id '[:div {:title (let [{:keys [sub]} m] (str sub))}]))
      "a local NAMED sub the pattern binds is a legitimate lexical shadow")
  (is (nil? (reject-id '[:div {:title (let [{:keys [sub a] :or {a sub}} m] a)}]))
      "an :or default referencing a sub the SAME pattern binds is the shadow")
  (is (nil? (reject-id '[:div {:title (let [{:keys [x] :or {x 0}} m] x)}]))
      "an ordinary literal :or default is untouched")
  (is (nil? (reject-id '[:div {:title (let [{:keys [x] :or {x 'sub}} m] x)}]))
      "quoted data in an :or default is inert — never a reactive read"))

(deftest frame-finite-sites
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '(for [x xs] [:li {:key x} (:frame (frame))])))
      "(frame) is a finite render-time site — never per-row")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '(for [x xs :let [h (frame)]] [:li {:key x} (str h)])))
      "loop :let modifiers are row-scoped")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:button {:on-click (fn [_] (do-send! (:dispatch (frame))))} "x"]))
      "deferred callbacks cannot capture the frame at event time — hoist the read")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:button {:on-click [::open (:frame (frame))]} "x"]))
      "event-vector args evaluate at event time — deferred scope")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:div {:ref (raw-fn (fn [_] (frame)))}]))
      "raw-fn bodies are host-deferred")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:div {:title (loop [x 0] (frame))}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (frame :app)}]))
      "(frame) takes no arguments — explicit targeting is frame-provider's job")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (frame)}} value] [:div x])))
      "binding patterns/defaults cannot own a lexical render site")
  (is (nil? (reject-id '[:div {:title (-> (frame) :dispatch)}]))
      "a DIRECT call below a transparent macro is indexed normally")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (-> x frame)}]))
      "a bare frame reference below a macro would become an unindexed
       call after expansion — rejected before expansion can hide it"))

(deftest loop-captured-handlers
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [t ts] [:li {:key (:id t) :on-click [::open (:id t)]} "x"])))
      "per-row committed slots need per-row instances — extract a keyed child view")
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [t ts]
                       [:li {:key (:id t)
                             :on-click {:event [::open t] :prevent-default true}} "x"])))
      "the options form is a site too")
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [{:keys [id]} ts] [:li {:key id :on-click [::open id]} "x"])))
      "destructured loop bindings count")
  (is (nil? (reject-id '(let [id 1] [:li {:on-click [::open id]} "x"])))
      "non-loop locals in event vectors are the normal case"))

(deftest handler-grammar
  (is (= :rf.ui.compile/bad-event-vector
         (reject-id '[:button {:on-click [event-sym 1]} "x"]))
      "event vectors start with a literal event-id keyword")
  (is (= :rf.ui.compile/bad-handler-options
         (reject-id '[:button {:on-click {:event [:a/b] :bubble true}} "x"]))
      "the listener option vocabulary is closed")
  (is (= :rf.ui.compile/bad-handler-options
         (reject-id '[:button {:on-click {:prevent-default true}} "x"]))
      "options maps need a literal :event vector")
  (is (= :rf.ui.compile/handler-option-unavailable-s1
         (reject-id '[:button {:on-click {:event [:a/b] :once true}} "x"]))
      ":once/:passive land S3 — rejected loudly rather than dropped")
  (is (= :rf.ui.compile/handler-option-unavailable-s1
         (reject-id '[:button {:on-click {:event [:a/b] :passive true}} "x"]))))

(deftest bare-fn-law
  (is (= :rf.ui.compile/bare-fn-prop
         (reject-id '[ForeignComp {:on-select (fn [x] x)}]))
      "bare fn at a foreign boundary — invoker/phase unknown")
  (is (= :rf.ui.compile/bare-fn-prop
         (reject-id '[child-view {:cb #(inc %)}]))
      "bare fn at a view boundary")
  (is (= :rf.ui.compile/bare-fn-prop
         (reject-id '[:div {:data-cb (fn [x] x)} "x"]))
      "bare fn at a non-event DOM prop")
  (is (= :rf.ui.compile/bare-fn-ref
         (reject-id '[:div {:ref (fn [el] el)} "x"]))
      "callback refs must be explicit (ui/raw-fn f)")
  (is (nil? (reject-id '[:div {:ref (raw-fn (fn [el] el))} "x"]))
      "(ui/raw-fn f) is the callback-ref spelling")
  (is (= :rf.ui.compile/ref-on-view-s1
         (reject-id '[child-view {:ref r}]))
      ":ref forwarding on internal views lands S3 (conservative pin)"))

(deftest element-props
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:class-name "x"}]))
      "one spelling per name: :class")
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:label {:html-for "x"}]))
      "one spelling per name: :for")
  (is (= :rf.ui.compile/rejected-prop-spelling
         (reject-id '[:div {:dangerouslySetInnerHTML {:__html "x"}}]))
      "dangerouslySetInnerHTML does not exist — ui/html is the spelling")
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:children [x]}]))
      "children are positional")
  (is (= :rf.ui.compile/id-sugar-conflict (reject-id '[:div#a {:id "b"}]))
      "two id spellings on one element is an ambiguity")
  (is (= :rf.ui.compile/duplicate-id-sugar (reject-id [:div#a#b "x"])))
  (is (= :rf.ui.compile/collection-attr-value (reject-id '[:div {:data-foo {:a 1}} "x"]))
      "collections are only meaningful for :class/:style")
  (is (= :rf.ui.compile/non-keyword-prop (reject-id '[:div {"str-key" 1} "x"])))
  (is (= :rf.ui.compile/bad-style (reject-id '[:div {:style {(kw) 1}} "x"]))
      ":style keys must be literal keywords")
  (is (= :rf.ui.compile/bad-class (reject-id '[:div {:class {(kw) true}} "x"]))
      ":class flag-map keys must be literal names")
  ;; hiccup structure disambiguates props: a non-map second element is a
  ;; CHILD, never a dynamic props map — runtime prop maps go through
  ;; (ui/spread base overrides)
  (is (nil? (reject-id '[:div some-expr "x"]))
      "a non-map expression after the tag is a dynamic child, not props"))

(deftest void-elements
  (doseq [tag [:br :hr :img :input :param :keygen]]
    (is (= :rf.ui.compile/void-children (reject-id [tag "child"]))
        (str tag " cannot have children (React 19 throw parity incl. param/keygen)")))
  (is (= :rf.ui.compile/void-children (reject-id [:menuitem "child"]))
      "menuitem rejects children though it is not self-closing")
  (is (nil? (reject-id [:br])) "void elements without children are fine"))

(deftest component-call-sites
  (is (= :rf.ui.compile/children-prop (reject-id '[child-view {:children [x]}]))
      ":children as an explicit prop — children are positional (Q4)")
  (is (= :rf.ui.compile/children-not-accepted
         (reject-id '[leaf-view {} [:p "kid"]]))
      "children to a view that declares none (Q4)")
  (is (= :rf.ui.compile/undeclared-prop
         (reject-id '[closed-view {:a 1 :c 3}]))
      ":props present = closed map (Q2)")
  (is (nil? (reject-id '[closed-view {:a 1 :b 2 :key k}]))
      "declared keys + :key pass the closed check")
  ;; a non-map expression at a component site is a CHILD (the structural
  ;; literal-props pin): a childless view therefore rejects it outright
  (is (= :rf.ui.compile/children-not-accepted
         (reject-id '[leaf-view props-expr]))
      "no dynamic-props back door — a non-map is a child, and leaf views take none"))

(deftest interop-positions
  (is (= :rf.ui.compile/html-not-sole-child
         (reject-id '[:div [:span "s"] (html "<b>x</b>")]))
      "(ui/html ...) must be the SOLE child of a DOM element")
  (is (= :rf.ui.compile/html-not-sole-child
         (reject-id '(html "<b>x</b>")))
      "standalone ui/html has no host element to own the markup")
  (is (= :rf.ui.compile/raw-fn-child (reject-id '(raw-fn f))))
  (is (= :rf.ui.compile/bad-spread (reject-id '(spread base)))
      "(ui/spread ...) belongs in an element's props position")
  (is (= :rf.ui.compile/bad-raw (reject-id '(raw)))
      "(ui/raw react-element) takes one argument")
  (is (= :rf.ui.compile/void-children (reject-id '[:br (html "<b>x</b>")]))
      "void elements cannot own trusted markup either"))

(deftest statically-pure-bodies
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(do (prn "side effect") [:p "x"])))
      "multi-form do — side effects don't belong in templates")
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(when c [:p "a"] [:p "b"])))
      "when takes ONE template form; siblings wrap in [:<> ...]")
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(let [x 1] [:p "a"] [:p x])))))

(deftest fragment-props
  (is (= :rf.ui.compile/bad-fragment-props
         (reject-id '[:<> {:key k :class "x"} [:p "a"]]))
      "fragments take only {:key ...}"))
