(ns re-frame.freehand.compiler.emit-jvm
  "AST -> forms building the versioned public structural tree (node schema
  v1). The same normalized AST the React emitter consumes — no emitter
  consumes raw source or another emitter's output (the portability law).

  ## What this emitter targets

  The `:re-frame.freehand/v1` arms below emit calls into
  [[re-frame.freehand.node]] — the ONE canonicalizer, which the
  interpreted walk also builds through. That is deliberate and it is the
  whole promotion argument: a compiled body and an interpreted body do
  not agree about canonical form, they *share* it. Only the front end
  differs — one resolved the structure at compile time, the other
  discovers it at render — so adding `{:compiled true}` to a declaration
  cannot change the value it produces.

  The emitter has no unknown-node arm. Every node kind outside
  [[re-frame.freehand.compiler.grammar/admitted-ops]] is refused before
  emission, so escaping is structural rather than defensive, and there is
  nowhere for an interpreted fallback to hide.

  The arms below for node kinds v1 does not yet admit — trusted HTML,
  render slots, presence, client-only subtrees, error boundaries, frame
  scoping, host effects, foreign heads — are the donor's, unreached, and
  waiting for the slices that widen the grammar and land their runtime.
  They are kept rather than deleted because each one is the work that
  slice starts from.

  Event vectors are retained AS DATA (placeholders stay keywords) and
  fn-carried sites classify to the opaque marker at build time, by the
  value present — the same rule, in the same function, that the
  interpreted walk applies."
  (:require [re-frame.source-coords :as source-coord]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.rules :as rules]))

(declare emit-node)

;; ---------------------------------------------------------------------------
;; Element parts
;; ---------------------------------------------------------------------------

(defn- build-time-attr?
  "Can this attribute be settled at BUILD time?

  A literal value can — that is the emitter's real work, and the reason a
  wholly-literal element's `:attrs` map is a constant. A literal `nil`
  cannot, and deliberately so: whether a nil entry is DROPPED or kept as a
  controlled input's empty value is one rule, and it belongs to the one
  canonicaliser both structural modes reach ([[node/element]]) rather than
  to an emitter that would have to re-state it. So a literal nil rides the
  `:dyn` slot beside the values only the render knows, and the compiled
  tree answers what the interpreted tree answers by construction."
  [{:keys [literal? value]}]
  (and literal? (some? value)))

(defn- static-attr-entries
  "Compile-time semantic normalization of literal attr values — the
  emitter's real work, and the reason a wholly-literal element costs
  nothing at render: its `:attrs` map is a constant.

  The normalization is [[re-frame.freehand.conversion/attr-value]], the
  rule the interpreted walk applies to the same value at render. A
  literal outside the value grammar is refused HERE, at build time,
  rather than surviving to the first render that reaches it."
  [e attrs]
  (into {}
        (keep (fn [{:keys [k value] :as attr}]
                (when (build-time-attr? attr)
                  (let [v (conv/attr-value value)]
                    (when (= ::conv/reject v)
                      (env/fail! e :rf.ui.compile/collection-attr-value
                                 (str "the " k " attribute carries " (pr-str value)
                                      ", which has no attribute spelling — an attribute "
                                      "value is a string, keyword, symbol, number or "
                                      "boolean; :class and :style have their own grammars")
                                 {:attr k}))
                    [k v]))))
        attrs))

(defn- dyn-attr-entries
  "Attribute values `node/element` settles at RENDER, in author space —
  everything [[build-time-attr?]] leaves: the per-prop values only known
  then, and the literal nils whose verdict is the canonicaliser's. They
  fold through `node/element`'s `:dyn` slot, which applies exactly the
  normalization `static-attr-entries` applied at build time."
  [attrs]
  (into {}
        (keep (fn [{:keys [k value] :as attr}]
                (when-not (build-time-attr? attr) [k value])))
        attrs))

(defn- class-slots
  "The `:class` plan for `node/element`.

  A wholly-static class composition folds into the constant `:attrs` map
  and costs nothing at render. Anything else hands `node/element` the
  sugar names and ONE form evaluating to the authored `:class` value, and
  lets the shared class rule compose them — which is what keeps a flag
  map mixing literal and computed entries in the single lexicographic
  order the interpreted walk produces, rather than literals-then-computed."
  [cls]
  (if (:static? cls)
    (when (seq (:base-str cls)) {:static-class (:base-str cls)})
    {:sugar (:sugar cls) :class (:runtime cls)}))

(defn- style-slots
  "The `:style` plan. A wholly-literal map normalizes to its semantic form
  at build time and rides the constant `:attrs`; any dynamic entry hands
  the whole author-space map over for render-time normalization, so the
  literal and computed entries in one map cannot normalize by two rules."
  [sty]
  (cond
    (nil? sty)   nil
    (:dyn sty)   {:style (:dyn sty)}
    (:static? sty)
    (let [m (into {}
                  (keep (fn [{:keys [css-name value]}]
                          (when (some? value)
                            [(keyword css-name) (conv/css-value css-name value)])))
                  (:entries sty))]
      (when (seq m) {:static-style m}))
    :else
    {:style (into {} (map (fn [{:keys [css-name value]}] [(keyword css-name) value]))
                  (:entries sty))}))

(defn- event-slots
  "Handler sites, as the forms that produce their values. Classification
  is `node/classify-event`, at render, by the value present — the same
  function on the same value the interpreted walk classifies, so a
  literal event vector stays data and a callback becomes the opaque
  marker identically in both modes."
  [events]
  (into {} (map (fn [{:keys [k form]}] [k form])) events))

(defn- emit-element [e node]
  (let [{:keys [props tag]} node
        key-info    (:key props)
        spread      (:spread props)
        safe        (:safe-spread props)
        ;; `(v/spread …)` in the props position IS the element's whole attribute
        ;; map, so the class plan must stay the WHOLE plan: a static fold has
        ;; already flattened the `.class` sugar into one string, and a forwarded
        ;; `:class` folded on top of that would REPLACE the sugar rather than
        ;; compose after it — the one shape in which a promoted declaration
        ;; would answer a different element (`.card` silently gone).
        cls         (if spread
                      {:sugar (:sugar (:class props)) :class nil}
                      (class-slots (:class props)))
        sty         (style-slots (:style props))
        static      (cond-> (static-attr-entries e (:attrs props))
                      (:static-class cls) (assoc :class (:static-class cls))
                      (:static-style sty) (assoc :style (:static-style sty)))
        dyn         (dyn-attr-entries (:attrs props))
        events      (event-slots (:events props))
        child-forms (mapv #(emit-node e %) (:children node))]
    `(node/element
      ~(cond-> {:tag tag}
         (seq static)     (assoc :attrs static)
         (seq dyn)        (assoc :dyn dyn)
         ;; The forwarded maps go through the SAME constructors the public door
         ;; calls in an interpreted body — one merge, one deny law, one fold.
         ;; The element's `#id` sugar fact rides along so the seam refuses a
         ;; forwarded id beside the sugar, the ambiguity the literal path guards
         ;; but a spread walks round (rf2-5r1af).
         spread           (assoc :dyn `(node/spread-attrs ~(:base spread)
                                                          ~(:overrides spread)
                                                          ~tag ~(:sugar-id spread)))
         (contains? cls :class)  (assoc :class (:class cls) :sugar (:sugar cls))
         (contains? sty :style)  (assoc :style (:style sty))
         (seq events)     (assoc :events events)
         ;; `v/spread-safe`: the OWNED props compiled above, the guarded caller
         ;; folded under them by `node/element` — the same slot the interpreted
         ;; walk reaches through the reserved carrier key.
         safe             (assoc :caller `(node/safe-caller-attrs
                                           ~(:base safe)
                                           ~(:owned-handler-keys safe)))
         (:present? key-info) (assoc :key? true :key-val (:expr key-info))
         (seq child-forms) (assoc :children
                                  `(fn [] (node/children ~@child-forms)))))))

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn self-fqn
  "The canonical current-namespace Var of the view being compiled — `<ns>/<name>`
  — or nil outside a defview (a root form carries no `:self`). Identical to the
  `:fqn` `env/classify-head` stamps on an exact self component node."
  [e]
  (when (:self e) (symbol (str (:ns e)) (str (:self e)))))

(defn self-view-head
  "The symbol a `:view` component call is emitted AGAINST.

  For the EXACT self-recursive head — the view currently being compiled — that
  is the canonical current-namespace Var (the node's `:fqn`), never the raw
  authored `:sym`. In a namespace that REFERS a same-named public authoring verb
  (e.g. `(defview sub …)` where the ns refers `re-frame.freehand/sub`), the bare
  authored spelling resolves through the refer to the AUTHORING Var, while the
  qualified `:fqn` resolves to the view itself; the authored spelling stays
  diagnostic only (rf2 self-Var invariant). Every other head — an unrelated
  internal view, a foreign component, a local shadow — keeps its authored
  spelling."
  [e node]
  (let [sf (self-fqn e)]
    (if (and sf (= (:fqn node) sf)) (:fqn node) (:sym node))))

(defn- prop-value-form [e {:keys [value marker render-fn]}]
  (case marker
    :v/raw-fn  {:rf.ui/opaque :v/raw-fn}
    ;; v/event and v/handler at a component prop are committed callbacks whose
    ;; body the structural tree never evaluates. Emitting the FORM and letting
    ;; the boundary record it is deliberate: the same value the interpreted walk
    ;; sanitizes reaches the same sanitizer, so neither mode has its own opinion
    ;; about what a non-data prop records as.
    :ui-event   value
    :handler    value
    ;; A compiled render slot: the lexically-visible pure body compiles to the
    ;; SAME roster callback the interpreted `v/render-fn` macro expands to, so
    ;; the boundary records one marker whichever mode wrote the slot. What the
    ;; compiled body ANSWERS differs — a structural node rather than markup —
    ;; and that is D010, not a second carrier.
    :render-fn  `(events/callback
                  :render-fn
                  (fn [~@(:params render-fn)] ~(emit-node e (:body render-fn)))
                  ~(count (:params render-fn)))
    value))

(defn- emit-view
  "An internal boundary: ONE call, whatever mode the child was declared in.

  The compiler settled the head, the prop keys and the child structure;
  what it deliberately does NOT settle is the call normalization —
  `node/mount` runs `v/normalize-call`, the same function on the same
  props map the interpreted walk runs, so `:key` stripping, the reserved
  `:children` slot and the declared children policy cannot mean one thing
  in a compiled parent and another in an interpreted one."
  [e node]
  (let [entries   (get-in node [:props :entries])
        key-info  (get-in node [:props :key])
        child-forms (mapv #(emit-node e %) (:children node))
        props-map (into {}
                        (map (fn [{:keys [k] :as en}] [k (prop-value-form e en)]))
                        entries)
        props-map (cond-> props-map
                    (:present? key-info) (assoc :key (:expr key-info)))]
    `(node/mount ~(self-view-head e node) [~props-map ~@child-forms])))

(defn- emit-behavior
  "A `v/behavior` attachment on the structural host: an INERT MARKER. It
  crosses the SAME `node/mount` seam every internal boundary does, so the
  compiled marker is byte-identical to the interpreted structural render — the
  behavior descriptor's own `read-opts` validates the call (the registered id,
  the one-element child, the config-is-data law) and hands the decorated node
  back, and `node/boundary` records the id, the semantic target and the public
  config with the element as its child. Nothing connects: the JVM has no
  lifecycle, which is exactly the inert-marker claim (D013, FH-BEHAVIOR-003)."
  [e node]
  (let [opts (cond-> {:use (:use node)}
               (:has-target? node) (assoc :target (:target node))
               (:has-config? node) (assoc :config (:config node))
               (get-in node [:key :present?]) (assoc :key (get-in node [:key :expr])))]
    `(node/mount ~(:fqn node) [~opts ~(emit-node e (:child node))])))

(defn- emit-foreign [e node]
  (if (:lazy? node)
    ;; a re-frame.freehand.react/lazy component IS callable on the JVM structural
    ;; render: it renders its declared fallback (or nothing) and NEVER invokes
    ;; the load thunk (which is not even emitted on the JVM). Props/children are
    ;; irrelevant — the fallback is capability-free markup baked into the value.
    `(~(:sym node) {})
    ;; A NON-LAZY foreign component has no JVM structural realisation, and the v1
    ;; grammar refuses `:foreign` before emission (it is not in
    ;; `grammar/admitted-ops`, so `grammar/check!` raises first). This arm is
    ;; therefore unreachable in the normal pipeline; it is kept — like every other
    ;; below-v1 arm — as the starting point for the slice that admits foreign
    ;; heads. It now REFUSES THE SHAPE LOUDLY with the emitter's own compile
    ;; diagnostic rather than emitting a call to `tree/jvm-host-op!`, a var that is
    ;; defined nowhere: were a later grammar widening or a weaker seam to reach it,
    ;; expansion would raise an intentional Freehand error, not an unresolved
    ;; symbol (rf2-drpa3.174).
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "foreign React component " (:sym node) " has no JVM structural "
                    "render — a foreign component never appears in the JVM tree. "
                    "Wrap the subtree in v/client-only so the JVM renders its "
                    "capability-free fallback.")
               {:op :foreign :sym (:sym node)})))

(defn- emit-for
  "A keyed list site. `node/keyed-run` proves the keys before the run is
  spliced, so a duplicate key fails at the list site rather than in React."
  [e node]
  `(node/keyed-run
    (into [] (for [~@(:seq-exprs node)] ~(emit-node e (:body node))))))

(defn- emit-slot
  "A `v/slot` invocation: evaluate the slot value and every argument ONCE
  in source order, then gate the render-fn value (nil renders nothing; a
  bare fn — anything that is not a render-fn — is the didactic
  `invalid-slot!`) and invoke the carrier's compiled body with the
  already-evaluated args — a fixed-arity call. The rendered output is an
  ordinary tree child.

  The arguments are bound BEFORE the gate, not inlined inside it, so each
  runs exactly once whether or not the slot renders — the eager
  function-call semantics an interpreted `v/slot` has, preserved across
  promotion (rf2-drpa3.133). Gating the args away for a nil slot dropped a
  `v/sub` the analyzer attributes to the enclosing view, silently changing
  dependency capture between modes.

  The gate is the COMPILED half of the mode asymmetry the interpreted
  `v/slot` widens: an interpreted body may pass an ordinary pure function as
  parameterized content, a compiled one may not, because a function value is
  exactly the content the compiled tier cannot see."
  [e node]
  (let [rf      (gensym "rf-ui-slot")
        slotval (if-let [{:keys [params body]} (:render-fn node)]
                  `(events/callback :render-fn
                                    (fn [~@params] ~(emit-node e body))
                                    ~(count params))
                  (:slot-value node))
        args    (:args node)
        argsyms (mapv (fn [_] (gensym "rf-ui-arg")) args)]
    `(let [~rf ~slotval
           ~@(interleave argsyms args)]
       (when (events/slot-ready? ~rf)
         (events/check-slot-arity! ~rf ~(count args))
         ((events/callback-fn ~rf) ~@argsyms)))))

(defn emit-node
  "AST node -> the form that builds it (nil for a statically-absent child).

  The `:re-frame.freehand/v1` arms emit `node/*` calls. The arms below
  them are the donor's, for node kinds the grammar does not yet admit:
  `grammar/check!` refuses those bodies before emission, so they are
  unreachable today and are kept as the starting point for the slice that
  widens the grammar to cover them. The one that has no honest JVM form at
  all — a non-lazy `:foreign` — refuses the shape loudly through
  the emitter's own `env/fail!` rather than emitting a call to an undefined
  host-op var, so a later widening or a weaker seam raises an intentional
  Freehand diagnostic instead of an unresolved symbol (rf2-drpa3.174)."
  [e node]
  (case (:op node)
    :nothing  nil
    :text     (:value node)
    :expr     (:form node)
    :element  (emit-element e node)
    :fragment (let [k (:key node)]
                `(node/fragment
                  ~(:present? k) ~(:expr k)
                  (node/children ~@(mapv #(emit-node e %) (:children node)))))
    :view     (emit-view e node)
    :behavior (emit-behavior e node)
    :for      (emit-for e node)
    :if       `(if ~(:test node)
                 ~(emit-node e (:then node))
                 ~(emit-node e (:else node)))
    :let      `(let ~(:bindings node) ~(emit-node e (:body node)))
    :letfn    `(letfn ~(:fnspecs node) ~(emit-node e (:body node)))
    :case     `(case ~(:expr node)
                 ~@(mapcat (fn [[test branch]] [test (emit-node e branch)])
                           (:clauses node))
                 ~@(when (not= ::ana/none (:default node))
                     [(emit-node e (:default node))]))

    ;; ---- below :re-frame.freehand/v1 --------------------------------------
    :html     nil ; sole-child form carried by the parent element
    :foreign  (emit-foreign e node)
    :slot     (emit-slot e node)
    ;; error-boundary is a CLIENT recovery mechanism (Spec 004 §The JVM
    ;; structural subset): the JVM/SSR renders the guarded CHILD transparently
    ;; under the server failure policy (per 011); it never renders the fallback
    ;; (that is client recovery). A throw below it is the server's to project.
    :error-boundary (emit-node e (:child node))
    ;; presence: the JVM has no lifecycle, so it renders the children :present,
    ;; wrapped in the `:rf.ui/presence {:phase :present :timeout-ms n}` fragment
    ;; (§004B — presence metadata exposed structurally).
    :presence `(re-frame.freehand.tree/presence
                ~(:timeout-ms node)
                ~@(keep #(emit-node e %) (:children node)))
    ;; client-only: the JVM/SSR renders the deterministic capability-free
    ;; FALLBACK, wrapped in the `:rf.ui/boundary :client-only` fragment (§004B);
    ;; the browser-only client subtree never appears on the JVM tree.
    :client-only `(re-frame.freehand.tree/client-only-fallback
                   ~(emit-node e (:fallback node)))))

;; ---------------------------------------------------------------------------
;; The compiled structural body
;; ---------------------------------------------------------------------------

(defn emit-structural-body
  "The compiled realisation a `{:compiled true}` declaration carries: a
  one-argument fn from the props map to its structural node.

  It binds the declaration's own parameter vector — the SAME
  destructuring form the interpreted declaration binds, with the host's
  own semantics — so `:keys`, `:or`, `:as` and namespaced patterns cannot
  mean one thing before promotion and another after. The compiled prop
  ABI (direct property reads off a host props object) belongs to the
  React lowering, where there is a host props object to read."
  [e params ast]
  `(fn ~params ~(emit-node e ast)))

