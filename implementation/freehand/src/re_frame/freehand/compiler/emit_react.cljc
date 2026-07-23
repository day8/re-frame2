(ns re-frame.freehand.compiler.emit-react
  "AST -> forms building REAL React elements — the compiled tier's BROWSER
  lowering, and the third emitter over the one normalized AST.

  ## What it targets, and what it deliberately does not

  Its siblings are [[re-frame.freehand.compiler.emit-jvm]], which builds
  the versioned structural tree on either host, and the interpreted walk
  in `re-frame.freehand.react`, which discovers markup at render. This
  one resolves the same structure at build time and emits the
  `react/createElement` calls the interpreted walk would have produced —
  no Hiccup walk, no tag parse, no prop-name projection, no head
  classification at render.

  It emits into Freehand's OWN runtime and nothing else. Every rule a
  compiled element needs — the class composition, the style
  canonicaliser, the attribute-value grammar, the canonical prop names,
  the controlled-input door, the committed event site, the boundary call
  normalization, what counts as a child — already has exactly one
  implementation, and this emitter reaches those implementations rather
  than restating them. That is the whole cross-mode parity argument: a
  compiled view and its interpreted twin do not agree about conversion,
  they *share* it, and the only difference is WHEN the shape was
  resolved.

  ## Static subtrees are built once

  A subtree with no key, no ref, no handler site, no dynamic attribute
  and no dynamic child is a CONSTANT React element. Those hoist into a
  `let` around the emitted body, so the closure the descriptor carries
  builds them once at declaration time and every render of every
  occurrence reuses the same immutable element. They ride a `let` rather
  than module-level `def`s because the body is emitted INSIDE the
  descriptor's entry map, where a `def` is not a legal form.

  ## Children are spliced, not nested

  A dynamic child is a runtime value whose shape the compiler cannot
  know: forwarded `:children`, a seq, text, nothing. It is classified by
  the interpreted walk's own child classifier and SPLICED into the
  parent's argument list, exactly as `apply createElement` splices a
  run — so a compiled parent and an interpreted one hand React the same
  child sequence, and neither introduces an array boundary the other
  does not have.

  That splice is why every element is built through `createElement` with
  VARARG children rather than through the `jsx` / `jsxs` runtime. React
  treats a vararg run and a single array child differently — identity,
  and the dev-time key expectation — so an element whose children came
  from a forwarded run would acquire a key expectation its interpreted
  twin does not have, on markup neither author wrote. Specializing the
  wholly-static arms to `jsxs`, where no splice is possible and the
  question does not arise, is a separable optimisation over exactly the
  subtrees this emitter already proves constant.

  There is no unknown-node arm. `re-frame.freehand.compiler.grammar/check!`
  refuses every node kind outside `:re-frame.freehand/v1` before emission,
  so escaping is structural rather than defensive and there is nowhere
  for an interpreted fallback to hide inside compiled markup.

  Like its siblings this namespace only RUNS on the JVM (macro
  expansion), and is `.cljc` so both hosts' suites can golden the
  emission as data.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.top-layer :as top-layer]))

(declare emit-node)

;; ---------------------------------------------------------------------------
;; Hoisting — the constant subtrees
;; ---------------------------------------------------------------------------

(def ^:private top-layer-keys
  "The DOM top layer's desired-state pair. It never reaches React as a
  prop — an emitter drops the namespace that is the whole of its meaning
  — so it is withheld from the props object and installed as the
  commit-time host call instead, exactly as the interpreted walk does."
  #{top-layer/popover-open?-key top-layer/modal-open?-key})

(defn- new-state [] (atom {:binds [] :n 0}))

(defn- hoist!
  "Bind `form` once, outside the render, and answer the symbol naming it."
  [st form]
  (let [sym (symbol (str "rf-fh-const-" (:n @st)))]
    (swap! st #(-> % (update :n inc) (update :binds into [sym form])))
    sym))

(defn- constant-node?
  "True when this node builds the SAME React element on every render, so
  it may be built once and shared.

  The analyzer's own `:static?` verdict settles the element's props — no
  key, no ref, no handler site, every attribute literal, class and style
  wholly literal. What it does not settle is the CHILDREN, so this walks
  them: one dynamic descendant anywhere below makes the whole subtree a
  per-render build."
  [node]
  (case (:op node)
    (:text :nothing) true
    :element  (and (:static? node)
                   (not-any? #(contains? top-layer-keys (:k %))
                             (get-in node [:props :attrs]))
                   (every? constant-node? (:children node)))
    :fragment (and (not (:present? (:key node)))
                   (every? constant-node? (:children node)))
    false))

;; ---------------------------------------------------------------------------
;; Element props
;; ---------------------------------------------------------------------------

(defn- static-class-string
  "The wholly-literal `className`, already composed — or nil when the
  element declares no classes at all.

  Composed through [[re-frame.freehand.conversion/class-string]]'s own
  answer for the SAME name sequence the analyzer folded, so the compiled
  string and the interpreted one cannot differ in separator, order or
  emptiness."
  [cls]
  (when (seq (:base-str cls)) (:base-str cls)))

(defn- dynamic-class-form
  "The runtime `className` write: the sugar names the tag carried, plus
  the AUTHORED `:class` value, composed by the one class rule the
  interpreted walk composes with.

  The analyzer offers two plans and this takes the WHOLE one
  (`:sugar` + `:runtime`) rather than the split literals-then-computed
  one, for the reason the analyzer's own docstring gives: a flag map
  mixing literal and computed entries sorts ALL its truthy names
  together. Taking the split plan here would order a promoted
  declaration's classes differently from its interpreted twin's."
  [o tag cls]
  `(re-frame.freehand.compiled-react/class! ~o ~tag ~(vec (:sugar cls)) ~(:runtime cls)))

(defn- static-style-obj
  "A wholly-literal `:style` map, canonicalised at build time into the
  JS object React takes — property names in React's own spelling, values
  through the shared CSS-value rule, nil entries dropped exactly as the
  interpreted walk drops them."
  [sty]
  (let [pairs (into []
                    (mapcat (fn [{:keys [css-name value]}]
                              (when (some? value)
                                [(conv/react-style-name css-name)
                                 (conv/css-value css-name value)])))
                    (:entries sty))]
    (when (seq pairs) `(cljs.core/js-obj ~@pairs))))

(defn- style-write
  "The runtime `style` write for a `:style` the compiler could not fold:
  the whole authored map (or the wholly-dynamic expression) handed to the
  one style canonicaliser the interpreted walk uses."
  [o tag sty]
  (let [form (if-let [dyn (:dyn sty)]
               dyn
               (into {} (map (fn [{:keys [css-name value]}] [(keyword css-name) value]))
                     (:entries sty)))]
    `(re-frame.freehand.compiled-react/style! ~o ~tag ~form)))

(defn- literal-attr-pair
  "One literal attribute as its `[react-prop-name value]` pair, resolved
  at BUILD time — the projection the interpreted walk pays per attribute
  per render, paid here once.

  A literal outside the attribute-value grammar is refused HERE rather
  than surviving to the first render that reaches it, with the diagnostic
  the JVM emitter raises for the same value."
  [e tag {:keys [k value react-name kind]}]
  (when (some? value)
    (if (= :property kind)
      [react-name value]
      (let [semantic (conv/attr-value value)]
        (when (= ::conv/reject semantic)
          (env/fail! e :rf.ui.compile/collection-attr-value
                     (str "the " k " attribute on " tag " carries " (pr-str value)
                          ", which has no attribute spelling — an attribute value is "
                          "a string, keyword, symbol, number or boolean; :class and "
                          ":style have their own grammars")
                     {:attr k}))
        [(conv/react-prop-name (conv/attr-key k)) semantic]))))

(defn- element-facts
  "The CONTROLLED-INPUT door's element half for one handler site, as a
  compile-time constant.

  This is the answer to the ABI question the donor's emitter settled by
  pre-encoding a synchronous-lane bit into an integer flag. Freehand does
  not encode the verdict at all: the whole props map of a compiled
  element is lexically visible, so `:tag`, `:controlled?` and the
  handler's final `:slot` are all constants, and
  [[re-frame.freehand.controlled/door?]] decides at commit from those
  facts — the same predicate, on the same facts, that the interpreted
  walk hands it. There is no second decision, so there is nothing to
  diverge."
  [tag controlled? handler]
  {:tag         tag
   :controlled? controlled?
   :slot        (conv/react-event-name (:k handler))})

(defn- handler-writes
  [o tag controlled? events]
  (mapv (fn [{:keys [k sid form] :as handler}]
          `(re-frame.freehand.compiled-react/handler!
            ~o
            ~(conv/react-event-name k)
            (re-frame.freehand.reactive/event-site
             ~sid ~form ~(element-facts tag controlled? handler))))
        events))

(defn- children-form
  "The children argument array for one element / fragment / presence
  boundary, or nil when it declares none.

  A run of wholly-compiled children is one `array` literal. As soon as
  ONE child is a runtime value the array is built imperatively so that
  child can SPLICE — a forwarded `:children` run, a seq, or nothing at
  all contributes its own number of arguments, exactly as it does when
  the interpreted walk applies them to `createElement`."
  [e st nodes]
  (when (seq nodes)
    (let [forms (mapv (fn [n] [(constant-node? n) (emit-node e st n)]) nodes)
          forms (into [] (remove (fn [[_ f]] (nil? f))) forms)]
      (when (seq forms)
        (if (every? first forms)
          `(cljs.core/array ~@(map second forms))
          (let [a (gensym "rf-fh-kids")]
            `(let [~a (cljs.core/array)]
               ~@(map (fn [[const? f]]
                        (if const?
                          `(.push ~a ~f)
                          `(re-frame.freehand.compiled-react/push! ~a ~f)))
                      forms)
               ~a)))))))

(defn- emit-element*
  [e st node]
  (let [{:keys [tag props children]} node
        all-attrs   (:attrs props)
        top         (into {} (keep (fn [{:keys [k value]}]
                                     (when (contains? top-layer-keys k) [k value])))
                          all-attrs)
        attrs       (remove (fn [{:keys [k]}] (contains? top-layer-keys k)) all-attrs)
        controlled? (controlled/controlled-props? (map :k attrs))
        cls         (:class props)
        sty         (:style props)
        key-info    (:key props)
        literals    (into [] (comp (filter :literal?)
                                   (keep #(literal-attr-pair e tag %)))
                          attrs)
        literals    (cond-> literals
                      (static-class-string cls) (conj ["className" (static-class-string cls)])
                      (and sty (:static? sty) (static-style-obj sty))
                      (conj ["style" (static-style-obj sty)]))
        dynamics    (remove :literal? attrs)
        o           (gensym "rf-fh-props")
        writes      (cond-> []
                      (and cls (not (:static? cls))) (conj (dynamic-class-form o tag cls))
                      (and sty (not (:static? sty))) (conj (style-write o tag sty))
                      true (into (map (fn [{:keys [k value]}]
                                        `(re-frame.freehand.compiled-react/attr!
                                          ~o ~tag ~k ~value))
                                      dynamics))
                      true (into (handler-writes o tag controlled? (:events props)))
                      (seq top) (conj `(re-frame.freehand.top-layer/install!
                                        ~o ~tag ~top))
                      (:present? key-info)
                      (conj `(cljs.core/unchecked-set ~o "key" ~(:expr key-info))))
        props-form  (if (seq writes)
                      `(let [~o (cljs.core/js-obj ~@(mapcat identity literals))]
                         ~@writes
                         ~o)
                      `(cljs.core/js-obj ~@(mapcat identity literals)))]
    `(re-frame.freehand.compiled-react/el
      ~(name tag) ~props-form ~(children-form e st children))))

(defn- emit-element
  [e st node]
  (let [form (emit-element* e st node)]
    (if (constant-node? node) (hoist! st form) form)))

(defn- emit-fragment
  [e st node]
  (let [k    (:key node)
        form `(re-frame.freehand.compiled-react/fragment
               ~(boolean (:present? k)) ~(:expr k)
               ~(children-form e st (:children node)))]
    (if (constant-node? node) (hoist! st form) form)))

;; ---------------------------------------------------------------------------
;; Boundaries
;; ---------------------------------------------------------------------------

(defn- prop-value-form
  "One call-site prop value. `v/raw-fn` and `v/event` / `v/handler` props
  are OUTSIDE `:re-frame.freehand/v1`'s reachable surface today (the
  markers exist because the analyzer is shared with the wider grammar),
  so the ordinary arm is the whole of what a v1 crossing carries: the
  walked value, handed to the boundary verbatim, exactly as the
  interpreted walk hands it."
  [{:keys [value]}]
  value)

(defn- emit-view
  "An internal boundary crossing — ONE call, whatever mode the child was
  declared in.

  It resolves the descriptor's stable React component and normalizes the
  call through [[re-frame.freehand.descriptor/normalize-call]], the same
  function on the same props map the interpreted walk runs. That is the
  answer to the ABI question a Freehand `defview` var poses: the var
  holds a DESCRIPTOR, not a React component type, so a crossing cannot
  be a bare `createElement` on the head — it is a mount, and mounting is
  what the boundary already knows how to do. `:key` stripping, the
  reserved `:children` slot, the declared children policy and the props
  schema therefore cannot mean one thing in a compiled parent and
  another in an interpreted one."
  [e st node]
  (let [entries   (get-in node [:props :entries])
        key-info  (get-in node [:props :key])
        props-map (cond-> (into {} (map (fn [{:keys [k] :as en}] [k (prop-value-form en)])) entries)
                    (:present? key-info) (assoc :key (:expr key-info)))]
    `(re-frame.freehand.compiled-react/mount
      ~(:sym node) ~props-map
      ~@(mapv #(emit-node e st %) (:children node)))))

(defn- emit-for
  "A keyed list site. The row key is bound ONCE per row — React identity
  and the duplicate-key proof must name the exact same occurrence — and
  the DEV duplicate check mirrors the structural tier's `node/keyed-run`,
  so a colliding key fails at the compile-indexed list site rather than
  as a React console warning at the wrong end of the pipeline."
  [e st node]
  (let [a       (gensym "rf-fh-run")
        seen    (gensym "rf-fh-seen")
        row-key (gensym "rf-fh-row-key")
        body    (:body node)
        kexpr   (or (get-in body [:props :key :expr]) (get-in body [:key :expr]))
        body*   (if (= :fragment (:op body))
                  (assoc-in body [:key :expr] row-key)
                  (assoc-in body [:props :key :expr] row-key))]
    `(let [~a    (cljs.core/array)
           ~seen (cljs.core/js-obj)]
       (doseq [~@(:seq-exprs node)]
         (let [~row-key ~kexpr]
           (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.freehand.compiled-react/check-key! ~seen ~row-key))
           (.push ~a ~(emit-node e st body*))))
       ~a)))

(defn- emit-presence
  [e st node]
  `(re-frame.freehand.presence-runtime/presence-boundary
    ~(:timeout-ms node)
    ~(or (children-form e st (:children node)) `(cljs.core/array))))

;; ---------------------------------------------------------------------------
;; The walk
;; ---------------------------------------------------------------------------

(defn emit-node
  "AST node -> the CLJS form that builds it (nil for a statically-absent
  child).

  Every arm below is a `:re-frame.freehand/v1` node kind. There is no
  arm for anything else, because there is nothing else to reach here."
  [e st node]
  (case (:op node)
    :nothing  nil
    ;; A literal number is spelled by React's own number formatting, and
    ;; that spelling is knowable at build time — so it is a compile-time
    ;; string here rather than a per-render conversion.
    :text     (let [v (:value node)] (if (number? v) (conv/js-number-str v) v))
    :expr     `(re-frame.freehand.compiled-react/child ~(:form node))
    :element  (emit-element e st node)
    :fragment (emit-fragment e st node)
    :view     (emit-view e st node)
    :for      (emit-for e st node)
    :presence (emit-presence e st node)
    :if       `(if ~(:test node)
                 ~(emit-node e st (:then node))
                 ~(emit-node e st (:else node)))
    :let      `(let ~(:bindings node) ~(emit-node e st (:body node)))
    :letfn    `(letfn ~(:fnspecs node) ~(emit-node e st (:body node)))
    :case     `(case ~(:expr node)
                 ~@(mapcat (fn [[test branch]] [test (emit-node e st branch)])
                           (:clauses node))
                 ~@(when (not= ::ana/none (:default node))
                     [(emit-node e st (:default node))]))))

(defn emit-react-body
  "The BROWSER realisation a `{:compiled true}` declaration carries: a
  one-argument fn from the props map to the React element it renders.

  The props ABI is the interpreted one, deliberately. The declaration's
  own parameter vector is bound with the HOST's own destructuring — the
  same form, with the same `:keys` / `:or` / `:as` / namespaced-pattern
  semantics the interpreted declaration binds and the JVM structural body
  binds. One props ABI across both modes and both hosts is what makes
  `{:compiled true}` a one-line change rather than a port; a second one,
  reading slots off a host object, would mean a destructuring form could
  bind differently before and after promotion.

  Constant subtrees ride a `let` around the fn, so they are built once
  when the declaration is evaluated and shared by every occurrence."
  [e params ast]
  (let [st   (new-state)
        body `(fn ~params (re-frame.freehand.compiled-react/root ~(emit-node e st ast)))
        bs   (:binds @st)]
    (if (seq bs) `(let [~@bs] ~body) body)))
