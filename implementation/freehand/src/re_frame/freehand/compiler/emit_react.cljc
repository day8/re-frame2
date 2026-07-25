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
            [re-frame.freehand.events :as events]
            [re-frame.freehand.top-layer :as top-layer]))

(declare emit-node)

(defn- self-fqn
  "The canonical current-namespace Var of the view being compiled — `<ns>/<name>`
  — or nil outside a defview (a root form carries no `:self`). Identical to the
  `:fqn` `env/classify-head` stamps on an exact self component node."
  [e]
  (when (:self e) (symbol (str (:ns e)) (str (:self e)))))

;; ---------------------------------------------------------------------------
;; Hoisting — the constant subtrees
;; ---------------------------------------------------------------------------

(def ^:private top-layer-keys
  "The DOM top layer's desired-state pair. It never reaches React as a
  prop — an emitter drops the namespace that is the whole of its meaning
  — so it is withheld from the props object and installed as the
  commit-time host call instead, exactly as the interpreted walk does."
  #{top-layer/popover-open?-key top-layer/modal-open?-key})

(defn- top-layer-context
  "The element facts that ride WITH the desired-state pair into
  [[re-frame.freehand.top-layer/install!]]:
  [[re-frame.freehand.top-layer/context-keys]], read off this element.

  The install judges a declaration against the element that made it —
  `popover-open?` is legal only on a valid `:popover` mode, and the
  dismissal advisory asks whether the element handles the browser's own
  report. The interpreted walk hands it the whole authored attribute map,
  so it has both. A compiled element's props are written imperatively
  onto a JS object and there is no such map, so without this the install
  judges an EMPTY element: it reads a popover mode of nil and refuses
  every promoted popover, and it reads no `:on-toggle` and accuses a
  declaration that does reconcile itself.

  A reconciler handler rides as its runtime SOME?-VERDICT, not its callback
  and not a bare compile-time presence: the site's dynamic body may evaluate
  to nil, and the advisory judges reconciliation by `(some? (get attrs k))`.
  So the context carries whether the runtime site actually produced a
  handler — the `event-site` result the element's own `handler!` write
  already computes, bound once and shared, so the callback itself never
  rides the context and the expression is evaluated once (rf2-drpa3.173)."
  [props reconciler-syms]
  (into (into {} (keep (fn [{:keys [k value]}]
                         (when (contains? top-layer/context-keys k) [k value])))
              (:attrs props))
        (keep (fn [{:keys [k]}]
                (when (contains? top-layer/context-keys k)
                  ;; nil when the runtime site produced no handler, `true`
                  ;; otherwise — the shared advisory asks `(some? (get attrs k))`,
                  ;; so an absent handler must read as absent, not as a non-nil
                  ;; `false`. The callback itself never rides the context.
                  [k `(if (cljs.core/some? ~(get reconciler-syms k)) true nil)])))
        (:events props)))

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

(defn- build-time-attr?
  "Can this attribute be settled at BUILD time?

  A literal value can — that is the point of the tier. A literal `nil`
  cannot: whether a nil entry is DROPPED or written as a controlled
  input's empty value is one rule, it depends on facts about the whole
  element, and it belongs to the runtime rule the interpreted walk already
  applies rather than to a second statement of it here. So a literal nil
  rides the dynamic path beside the values only the render knows, and the
  compiled props object is the interpreted one by construction."
  [{:keys [literal? value]}]
  (and literal? (some? value)))

(defn- literal-attr-pair
  "One literal attribute as its `[react-prop-name value]` pair, resolved
  at BUILD time — the projection the interpreted walk pays per attribute
  per render, paid here once.

  A literal outside the attribute-value grammar is refused HERE rather
  than surviving to the first render that reaches it, with the diagnostic
  the JVM emitter raises for the same value."
  [e tag {:keys [k value react-name kind]}]
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
      [(conv/react-prop-name (conv/attr-key k)) semantic])))

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

(defn- spread-write
  "The runtime fold of `(v/spread base overrides)` onto the props object.

  The merged, refusal-checked, canonical author-space map is built by
  [[re-frame.freehand.node/spread-attrs]] — the ONE seam an interpreted
  body reaches through the public `v/spread` — so later-arg-wins, the
  `:key` refusal and the slot canonicalization are one implementation
  across both modes, and only the WRITE is this tier's."
  [o tag cls spread]
  `(re-frame.freehand.compiled-react/spread!
    ~o ~tag ~(vec (:sugar cls)) ~(:sid spread)
    ;; The element's `#id` sugar fact rides into the shared seam so a forwarded
    ;; id beside `#id` refuses here at runtime, exactly as the interpreted
    ;; element fold does — the compiled react path never reaches that fold
    ;; (rf2-5r1af).
    (re-frame.freehand.node/spread-attrs ~(:base spread) ~(:overrides spread)
                                         ~tag ~(:sugar-id spread))))

(defn- safe-spread-write
  "The runtime fold of `(v/spread-safe owned caller)`'s guarded caller map,
  UNDER the owned props this element already wrote.

  The deny law is [[re-frame.freehand.node/safe-caller-attrs]]'s — the
  every-build owned-key refusal plus the forwarding refusals `v/spread`
  applies — which is the same call the structural emitter makes, so a
  component library's bound means one thing in both modes."
  [o tag controlled? safe]
  `(re-frame.freehand.compiled-react/caller-spread!
    ~o ~tag ~(:sid safe) ~controlled?
    (re-frame.freehand.node/safe-caller-attrs
     ~(:base safe) ~(:owned-handler-keys safe))))

(defn- handler-writes
  "Attach each handler site's proxy. A reconciler handler on a promoted
  top-layer element has already had its `event-site` bound (so its write and
  the top-layer advisory context share ONE evaluation — rf2-drpa3.173);
  reference that binding. Every other site builds its proxy inline."
  [o tag controlled? events reconciler-syms]
  (mapv (fn [{:keys [k sid form] :as handler}]
          `(re-frame.freehand.compiled-react/handler!
            ~o
            ~(conv/react-event-name k)
            ~(or (get reconciler-syms k)
                 `(re-frame.freehand.reactive/event-site
                   ~sid ~form ~(element-facts tag controlled? handler)))))
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
        top-pair    (into {} (keep (fn [{:keys [k value]}]
                                     (when (contains? top-layer-keys k) [k value])))
                          all-attrs)
        attrs       (remove (fn [{:keys [k]}] (contains? top-layer-keys k)) all-attrs)
        controlled? (controlled/controlled-props? (map :k attrs))
        ;; A promoted top-layer element judges whether it reconciles its own
        ;; native dismissal. On the compiled tier the reconciler handlers
        ;; (:on-toggle / :on-before-toggle / :on-close / :on-cancel) are runtime
        ;; SITES whose dynamic bodies may evaluate to nil, so their presence in
        ;; the advisory context is a runtime some?-verdict, not a compile-time
        ;; `true`. Bind each such site's `event-site` ONCE and share it with the
        ;; `handler!` write, so the handler is evaluated once per render and the
        ;; context reflects what actually reached the DOM (rf2-drpa3.173).
        reconciler-events (when (seq top-pair)
                            (filterv #(contains? top-layer/context-keys (:k %))
                                     (:events props)))
        reconciler-syms   (into {} (map (fn [{:keys [k]}] [k (gensym "rf-fh-top-h")]))
                                reconciler-events)
        reconciler-binds  (into []
                                (mapcat (fn [{:keys [k sid form] :as h}]
                                          [(get reconciler-syms k)
                                           `(re-frame.freehand.reactive/event-site
                                             ~sid ~form ~(element-facts tag controlled? h))]))
                                reconciler-events)
        top         (cond-> top-pair
                      (seq top-pair) (into (top-layer-context props reconciler-syms)))
        ;; The multiple-select verdict for a LITERAL `multiple`, settled at
        ;; build time as the constant it is. It decides one thing: what an
        ;; EMPTY `<select>` value is. Acceptance of a collection value
        ;; deliberately does NOT turn on it (see
        ;; `controlled/select-value-slot?`). A RUNTIME `multiple` — a value
        ;; this constant cannot read — is settled at render time instead,
        ;; by the runtime verdict below; this arm is the literal case only.
        literal-multi? (controlled/multiple-select?
                         tag
                         (keep (fn [{:keys [k value] :as attr}]
                                 (when (build-time-attr? attr) [k value]))
                               attrs)
                         nil)
        cls         (:class props)
        sty         (:style props)
        key-info    (:key props)
        safe-spread (:safe-spread props)
        literals    (into [] (comp (filter build-time-attr?)
                                   (map #(literal-attr-pair e tag %)))
                          attrs)
        literals    (cond-> literals
                      (static-class-string cls) (conj ["className" (static-class-string cls)])
                      (and sty (:static? sty) (static-style-obj sty))
                      (conj ["style" (static-style-obj sty)]))
        dynamics    (remove build-time-attr? attrs)
        ;; A `<select>`'s `multiple` may be a value only the render knows —
        ;; a runtime `:multiple` prop, or a `v/spread-safe` caller that
        ;; legally carries one. When it is, the multiple-select verdict — the
        ;; one fact that decides what an EMPTY value is — is itself a RUNTIME
        ;; fact, settled ONCE over both sources beside the props object, the
        ;; way the structural builder settles it. A fully literal `multiple`
        ;; keeps the build-time constant above; a runtime one this tier could
        ;; not see used to settle it `false` and mis-shape an explicitly nil
        ;; value — in the direct props AND under a safe-spread caller
        ;; (rf2-sf9n5).
        dyn-multi   (when (= :select tag)
                      (first (filter #(= (controlled/prop-slot (:k %))
                                         (controlled/prop-slot :multiple))
                                     dynamics)))
        ;; Whether the OWNED props declare the `multiple` slot at all — by
        ;; presence, over every owned attr, literal or dynamic. The v/spread-
        ;; safe caller folds UNDER the owned props (owned wins), so it settles
        ;; the verdict only when the owned props are silent on the slot. An
        ;; owned literal `:multiple false` is build-time (so it is NOT in
        ;; `dynamics`, and `dyn-multi` misses it) yet still an owned decision
        ;; that must silence a caller's true (rf2-sf9n5 #6847 audit).
        owned-multi? (boolean (some #(= (controlled/prop-slot (:k %))
                                        (controlled/prop-slot :multiple))
                                    attrs))
        ;; The verdict is a runtime fact only where there is an owned dynamic
        ;; write to shape (a nil `value`/`checked`) AND a runtime source that
        ;; could flip it: a dynamic `:multiple`, or a safe-spread caller on a
        ;; `<select>`. Everywhere else the build-time constant stands and the
        ;; emitted form is unchanged.
        runtime-verdict? (and (= :select tag) (not literal-multi?) (seq dynamics)
                              (boolean (or dyn-multi safe-spread)))
        o           (gensym "rf-fh-props")
        ;; When the verdict is runtime, the owned dynamic values are bound
        ;; ONCE in source order and the caller expression AFTER them, so the
        ;; `:value` expression is evaluated before the `:multiple` one and the
        ;; verdict reads already-bound values — never an expression hoisted
        ;; ahead of the writes, which diverged from the interpreted walk's
        ;; whole-map-then-decide order (rf2-sf9n5).
        caller-sym  (when (and runtime-verdict? safe-spread) (gensym "rf-fh-caller"))
        dyn-syms    (when runtime-verdict? (mapv (fn [_] (gensym "rf-fh-dyn")) dynamics))
        dyn-multi-sym (when (and runtime-verdict? dyn-multi)
                        (first (keep-indexed (fn [i a] (when (identical? a dyn-multi) (nth dyn-syms i)))
                                             dynamics)))
        mverdict    (when runtime-verdict? (gensym "rf-fh-multi?"))
        multi-arg   (cond literal-multi?   true
                          runtime-verdict? mverdict
                          :else            false)
        writes      (cond-> []
                      (and cls (not (:static? cls))) (conj (dynamic-class-form o tag cls))
                      (and sty (not (:static? sty))) (conj (style-write o tag sty))
                      true (into (map-indexed
                                   (fn [i {:keys [k value]}]
                                     `(re-frame.freehand.compiled-react/attr!
                                       ~o ~tag ~k
                                       ~(if runtime-verdict? (nth dyn-syms i) value)
                                       ~multi-arg))
                                   dynamics))
                      true (into (handler-writes o tag controlled? (:events props)
                                                 reconciler-syms))
                      ;; Both forwards write LAST, and for the same reason: a
                      ;; forwarded map is folded against props that are already
                      ;; final. `v/spread` is the element's whole attribute map,
                      ;; so it composes with the sugar and nothing else; a
                      ;; `v/spread-safe` caller folds UNDER what the component
                      ;; owns, which it can only do once the owned writes are on
                      ;; the object. When a caller feeds the runtime verdict it
                      ;; is bound above and folded through that binding; otherwise
                      ;; it inlines through the shared write.
                      (:spread props) (conj (spread-write o tag cls (:spread props)))
                      safe-spread     (conj (if caller-sym
                                              `(re-frame.freehand.compiled-react/caller-spread!
                                                ~o ~tag ~(:sid safe-spread) ~controlled? ~caller-sym)
                                              (safe-spread-write o tag controlled? safe-spread)))
                      (seq top) (conj `(re-frame.freehand.top-layer/install!
                                        ~o ~tag ~top))
                      ;; The key is the LAST of the props, and it is a props
                      ;; expression: it is authored inside the map, so the
                      ;; interpreted walk evaluates it — with the rest of the
                      ;; map — before it ever looks at the child position. It
                      ;; therefore writes before the trusted markup below.
                      ;; Nothing about the OBJECT turns on this: `"key"` and
                      ;; `"dangerouslySetInnerHTML"` are different slots and
                      ;; neither write reads the other. What turns on it is
                      ;; EVALUATION ORDER, which is observable through side
                      ;; effects and through which of the two throws first —
                      ;; and with the markup emitted ahead of the key both
                      ;; diverged from the authored order: a throwing key let
                      ;; the markup expression run anyway, and a throwing
                      ;; markup expression pre-empted a key the author wrote
                      ;; first (rf2-rrosy, #6980 audit).
                      (:present? key-info)
                      (conj `(cljs.core/unchecked-set ~o "key" ~(:expr key-info)))
                      ;; Trusted markup writes LAST, and it is a write rather
                      ;; than a build-time literal for one reason: the string is
                      ;; usually an expression, and an expression authored as the
                      ;; element's CHILD must evaluate after the whole props map
                      ;; it follows in the source — the order the interpreted
                      ;; walk produces. It goes through the shared browser
                      ;; writer, so the string check and the `<textarea>` / void
                      ;; refusals are the interpreted walk's, not a second set
                      ;; here. The analyzer sets `:children []` on an html-kid
                      ;; element, so no positional child accompanies the markup
                      ;; and React's children-vs-innerHTML conflict cannot arise.
                      (:html node)
                      (conj `(re-frame.freehand.compiled-react/html!
                              ~o ~tag ~(:form (:html node)))))
        ;; Bindings, in evaluation order: owned dynamic values (source order),
        ;; then the guarded caller map (after the owned expressions), then the
        ;; one verdict derived from both — then each reconciler handler site,
        ;; bound once so its `handler!` write and the top-layer advisory context
        ;; share one evaluation (rf2-drpa3.173).
        binds       (cond-> []
                      runtime-verdict? (into (mapcat (fn [sym a] [sym (:value a)]) dyn-syms dynamics))
                      caller-sym       (conj caller-sym
                                             `(re-frame.freehand.node/safe-caller-attrs
                                               ~(:base safe-spread) ~(:owned-handler-keys safe-spread)))
                      runtime-verdict?
                      ;; EFFECTIVE-source precedence, not a union. The verdict is
                      ;; the owned declaration's when the owned props declare the
                      ;; slot — a dynamic owned `:multiple` reads its bound value,
                      ;; a literal owned one is the compile-time constant, false
                      ;; here (a literal TRUE keeps the constant path and never
                      ;; reaches this binding). Only a slot the owned props leave
                      ;; undeclared hands the decision to the safe-spread caller.
                      ;; ORing them shaped an owned nil `value` as the empty
                      ;; collection though the caller's `multiple` loses to owned
                      ;; at `caller-spread!` (rf2-sf9n5 #6847 audit).
                      (conj mverdict
                            (cond
                              dyn-multi
                              `(re-frame.freehand.controlled/multiple-select?
                                ~tag [[~(:k dyn-multi) ~dyn-multi-sym]] nil)
                              owned-multi?
                              literal-multi?
                              caller-sym
                              `(re-frame.freehand.controlled/multiple-select?
                                ~tag ~caller-sym nil)
                              :else false))
                      (seq reconciler-binds) (into reconciler-binds))
        props-form  (if (seq writes)
                      `(let [~o (cljs.core/js-obj ~@(mapcat identity literals))
                             ~@binds]
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
  "One call-site prop value — one arm per
  [[re-frame.freehand.compiler.grammar/crossing-prop-markers]] member.

  The ordinary arm (marker `nil`) is the walked value handed to the
  boundary verbatim, exactly as the interpreted walk hands it — including a
  runtime React ELEMENT, which crosses unwrapped and needs no marker of its
  own: React takes the element it was given.

  The other three carry ANALYSED CONTENT rather than a plain value, and
  each is the shape that reaches an emitter reading only `:value` as
  `nil`: a prop the boundary receives ABSENT, gating cleanly and
  rendering nothing, which is the quietest possible failure. Each is
  rebuilt into the very value the interpreted spelling expands to — the
  same [[re-frame.freehand.events/callback]] roster carrier — so the
  boundary records one marker whichever mode wrote the prop. Only a
  `:render-fn`'s BODY differs, and it differs the way every other arm
  does: it is emitted through this emitter, so the deferred body builds
  React elements."
  [e st {:keys [value marker render-fn]}]
  (case marker
    :render-fn `(events/callback
                 :render-fn
                 (fn [~@(:params render-fn)] ~(emit-node e st (:body render-fn)))
                 ~(count (:params render-fn)))
    ;; `v/raw-fn`'s whole promise is that the identity a foreign API receives
    ;; is the identity it was given, and the roster carrier is how the
    ;; boundary knows not to stabilize it. The analyzer strips the wrapper to
    ;; walk the fn, so the wrapper is restored here.
    :v/raw-fn `(events/raw-fn ~value)
    value))

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
        props-map (cond-> (into {} (map (fn [{:keys [k] :as en}] [k (prop-value-form e st en)])) entries)
                    (:present? key-info) (assoc :key (:expr key-info)))
        ;; The EXACT self-recursive head targets the current-namespace Var (its
        ;; canonical `:fqn`), never the raw authored `:sym`: a bare self head in
        ;; a namespace that REFERS a same-named public authoring verb (e.g.
        ;; `(defview sub …)` refers `re-frame.freehand/sub`) resolves through the
        ;; refer to the authoring Var, while the qualified `:fqn` resolves to the
        ;; view. The DECLARATION completes the matching shadow on its own
        ;; `(def …)` (`re-frame.freehand/shadow-same-named-refer!`), so both
        ;; halves name one Var. Every other head keeps its authored spelling.
        sf        (self-fqn e)
        self?     (and sf (= (:fqn node) sf))]
    `(re-frame.freehand.compiled-react/mount
      ~(if self? (:fqn node) (:sym node)) ~props-map
      ~@(mapv #(emit-node e st %) (:children node)))))

(defn- emit-for
  "A keyed list site. The row key is bound ONCE per row — React identity
  and the duplicate-key proof must name the exact same occurrence — and
  the DEV duplicate check mirrors the structural tier's `node/keyed-run`,
  so a colliding key fails at the compile-indexed list site rather than
  as a React console warning at the wrong end of the pipeline.

  The seen table is a `js/Set`, not an object used as a map: an object
  inherits `toString`, `constructor` and friends from `Object.prototype`,
  and a membership test that answers `key in obj` would reject the FIRST
  row keyed by any of those inherited names. Those are ordinary domain
  identifiers, and the structural tier's Clojure set accepts them — so an
  object here would make the two tiers disagree about which pages are
  legal."
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
           ~seen (js/Set.)]
       (doseq [~@(:seq-exprs node)]
         (let [~row-key ~kexpr]
           (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.freehand.compiled-react/check-key! ~seen ~row-key))
           (.push ~a ~(emit-node e st body*))))
       ~a)))

;; ---------------------------------------------------------------------------
;; Render slots
;; ---------------------------------------------------------------------------

(defn- emit-slot
  "A `v/slot` invocation — the BROWSER half of the lowering
  [[re-frame.freehand.compiler.emit-jvm/emit-slot]] states structurally,
  and the same runtime calls in the same order: the slot value and every
  argument evaluate ONCE, in source order; then the carrier is gated (nil
  renders nothing), its declared arity is checked against the argument
  count, and its body is invoked with the already-evaluated arguments.

  Only the render-fn's BODY differs, and it differs the way every other
  arm does: it is emitted through this emitter, so an inline slot builds
  React elements while its structural twin builds nodes. The carrier
  itself is [[re-frame.freehand.events/callback]]'s — the very value the
  interpreted `v/render-fn` macro expands to — so the arity contract, the
  slot gate and the boundary's opaque prop record are one implementation
  across both modes and both hosts (rf2-ckviw).

  The arguments are bound in the `let` BEFORE the gate rather than inlined
  at the call, so each evaluates exactly once whether or not the slot
  renders — the eager function-call semantics an interpreted `v/slot` has,
  preserved across promotion (rf2-drpa3.133). Inlining them inside the gate
  made an absent slot skip a `v/sub` the analyzer attributes to the
  enclosing view, silently changing dependency capture between modes."
  [e st node]
  (let [rf      (gensym "rf-fh-slot")
        slotval (if-let [{:keys [params body]} (:render-fn node)]
                  `(events/callback :render-fn
                                    (fn [~@params] ~(emit-node e st body))
                                    ~(count params))
                  (:slot-value node))
        args    (:args node)
        argsyms (mapv (fn [_] (gensym "rf-fh-arg")) args)]
    `(let [~rf ~slotval
           ~@(interleave argsyms args)]
       (when (events/slot-ready? ~rf)
         (events/check-slot-arity! ~rf ~(count args))
         ((events/callback-fn ~rf) ~@argsyms)))))

(defn- emit-presence
  [e st node]
  `(re-frame.freehand.presence-runtime/presence-boundary
    ~(:timeout-ms node)
    ~(or (children-form e st (:children node)) `(cljs.core/array))))

(defn- emit-behavior
  "A `v/behavior` attachment in the browser. The already-built child element is
  handed to `re-frame.freehand.behaviors`' shared runtime:
  `check-attach-opts!` validates the registered id and the config-is-data law,
  `use-attachment!` installs the CLOSED-timing lifecycle arms, and `attach`
  chains the ref onto the author's OWN node — the same functions the
  interpreted `behavior-component` reaches, so a compiled attachment and its
  interpreted twin connect, update, command and tear down identically (D013,
  rf2-drpa3.127). The one-element child shape was proven at build; the ref
  rides that one element."
  [e st node]
  (let [opts (cond-> {:use (:use node)}
               (:has-target? node) (assoc :target (:target node))
               (:has-config? node) (assoc :config (:config node)))]
    `(re-frame.freehand.behaviors/compiled-attachment
      ~(when (get-in node [:key :present?]) (get-in node [:key :expr]))
      ~opts
      ~(emit-node e st (:child node)))))

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
    :behavior (emit-behavior e st node)
    :for      (emit-for e st node)
    :slot     (emit-slot e st node)
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
