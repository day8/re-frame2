(ns re-frame.ui.compiler.emit-cljs
  "AST -> direct react/jsx-runtime CLJS forms (the client emitter).

  - jsx/jsxs calls with compile-time-converted quoted prop names
    (cljs.core/js-obj with literal string keys — safe under :advanced);
  - maximal fully-static subtrees hoist to module constants;
  - fully-static props/style objects on otherwise-dynamic elements hoist;
  - event vectors lower to stable per-site callbacks whose meaning and frame
    destination are retargeted only by the winning layout commit;
  - per-slot rf= memo comparator (straight-line over declared slots;
    generic for :as views);
  - `(when ^boolean js/goog.DEBUG ...)`-wrapped dev checks strip under
    :advanced.

  This namespace only RUNS on the JVM (macro expansion) but is .cljc so
  both hosts' test suites can golden the emission as data."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.rules :as rules]))

(def ^:private props-sym 'rf-ui-props)

;; Compiler-local stack of the runtime keys for enclosing keyed rows. It is
;; carried only into native passive-ref ownership; capture-free data handlers
;; deliberately keep their one shared lexical callback (Spec 004 §Loops).
(def ^:dynamic *row-key-forms* [])

(defn- new-state [view-name]
  (atom {:defs [] :n 0 :sub-queries {}
         :view-name (name view-name)}))

(defn- hoist! [st kind form]
  (let [{:keys [n view-name]} @st
        sym (symbol (str view-name "$" (name kind) "$" n))]
    (swap! st #(-> % (update :n inc) (update :defs conj `(def ~sym ~form))))
    sym))

(defn- literal-data?
  "True for self-evaluating/collection data that CLJS would otherwise rebuild
  inside the render function. Lists and symbols remain runtime expressions."
  [x]
  (cond
    (ana/literal-scalar? x) true
    (vector? x) (every? literal-data? x)
    (map? x) (every? (fn [[k v]] (and (literal-data? k) (literal-data? v))) x)
    (set? x) (every? literal-data? x)
    :else false))

(defn- hoist-literal-sub-queries
  [st form]
  (letfn [(hoist-one [x]
            (if (ana/runtime-sub-form? x)
              (let [[runtime sid query] x]
                (if (literal-data? query)
                  (let [query-sym
                        (or (get-in @st [:sub-queries query])
                            (let [sym (hoist! st :query query)]
                              (swap! st assoc-in [:sub-queries query] sym)
                              sym))]
                    (with-meta (list runtime sid query-sym) (meta x)))
                  x))
              x))
          (visit [x]
            ;; `quote` is data, not executable compiler output. Returning the
            ;; exact form preserves its payload spelling and metadata while the
            ;; lower-level walk remains post-order everywhere executable.
            (if (and (seq? x) (= 'quote (first x)))
              x
              (walk/walk visit hoist-one x)))]
    (visit form)))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- placeholder-form [x]
  (case x
    :rf.ui/value   `re-frame.ui.events/value-placeholder
    :rf.ui/checked `re-frame.ui.events/checked-placeholder
    :rf.ui/key     `re-frame.ui.events/key-placeholder
    x))

(defn- site-key-form [{:keys [sid site-index]}]
  `(if ~(with-meta 'js/goog.DEBUG {:tag 'boolean}) ~sid ~site-index))

(defn- debug-site-form
  [{:keys [sid view-id source-coord path classification]}]
  `(when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
     {:sid ~sid
      :view-id ~view-id
      :source-coord ~source-coord
      :path ~path
      :classification ~classification}))

(defn- handler-flags
  [h prevent? stop?]
  (+ (if (:sync? h) 1 0)
     (if prevent? 2 0)
     (if stop? 4 0)
     (if (get-in h [:form :once]) 8 0)))

(defn- vector-handler-form
  [h ev-vec prevent? stop?]
  `(re-frame.ui.events/data-handler
    ~(site-key-form h)
    [~@(map placeholder-form ev-vec)]
    ~(handler-flags h prevent? stop?)
    ~(debug-site-form h)))

(defn- handler-form [h]
  (case (:classification h)
    :vector
    (vector-handler-form h (:form h) false false)

    :options
    (let [{:keys [event prevent-default stop-propagation]} (:form h)]
      (vector-handler-form h event prevent-default stop-propagation))

    :ui-event
    ;; The site proof is static (`handler-flags` carries the controlled-input
    ;; sync bit exactly as a literal vector does); the compiled fn produces the
    ;; event vector at invocation, so its outcome is classified there.
    `(re-frame.ui.events/event-handler
      ~(site-key-form h) ~(:form h) ~(handler-flags h false false)
      ~(debug-site-form h))

    :fn
    `(re-frame.ui.events/dynamic-handler
      ~(site-key-form h) ~(:form h) ~(debug-site-form h))

    :dynamic
    `(re-frame.ui.events/dynamic-handler
      ~(site-key-form h) ~(:form h) ~(debug-site-form h))))

(defn- native-event-name
  "Literal :on-* spelling -> the browser addEventListener event type.  Native
  DOM event types are the hyphen-collapsed author spelling except dblclick;
  custom-element event tails are their verbatim author spelling."
  [handler-name custom?]
  (if custom?
    (subs handler-name 3)
    (case handler-name
      "on-double-click" "dblclick"
      (str/replace (subs handler-name 3) "-" ""))))

(defn- passive-listener-spec
  [h custom?]
  `[~(site-key-form h)
    ~(native-event-name (:name h) custom?)
    ~(handler-form h)
    ~(:capture? h)])

(defn- emitted-ref-form
  "Preserve the explicit callback-ref marker. Unmarked dynamic values are
  object-ref positions; the dev guard rejects a function without invoking it.
  The branch and single-evaluation carrier disappear under :advanced."
  [{:keys [form raw-fn?] :as ref-a}]
  (when ref-a
    (if raw-fn?
      form
      (let [ref-value (gensym "rf-ui-ref")]
        `(let [~ref-value ~form]
           (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.ui.runtime/assert-object-ref! ~ref-value))
           ~ref-value)))))

;; ---------------------------------------------------------------------------
;; Element props
;; ---------------------------------------------------------------------------

(defn- class-form [c]
  (let [{:keys [base-str flags dyn]} c]
    (cond
      (and (empty? flags) (nil? dyn))
      (when (seq base-str) base-str)

      ;; The common one-flag/static-base case is exactly a binary string
      ;; choice. Avoid generic `str`'s conversion of the false `when` arm;
      ;; the condition is still evaluated once and the non-empty static base
      ;; keeps both results canonical.
      (and (seq base-str) (nil? dyn) (= 1 (count flags)))
      (let [[n f] (first flags)]
        `(if ~f ~(str base-str " " n) ~base-str))

      ;; literal flag maps over a non-empty static base (the `.sugar
      ;; {:class {:flag cond}}` idiom) compile STRAIGHT-LINE: the flag
      ;; order is compile-time-known (analyzer pre-sorts), the base
      ;; guarantees a non-empty string, and the generic classes-str
      ;; vector/join machinery costs ~0.5us per element — G-1 measured
      ;; it as a 20-row-list regression (rf2-vxgfnd.6)
      (and (seq base-str) (nil? dyn))
      `(str ~base-str ~@(map (fn [[n f]] `(when ~f ~(str " " n))) flags))

      :else
      `(re-frame.ui.rules/classes-str
        [~@(when (seq base-str) [base-str])
         ~@(map (fn [[n f]] `(when ~f ~n)) flags)
         ~@(when dyn [`(re-frame.ui.rules/class-val ~dyn)])]))))

(defn- style-form [st s inline?]
  (if-let [dyn (:dyn s)]
    `(re-frame.ui.runtime/style-obj ~dyn)
    (let [pairs (mapcat (fn [{:keys [css-name value literal?]}]
                          [(rules/react-style-name css-name)
                           (if literal?
                             (if (keyword? value) (name value) value)
                             `(re-frame.ui.runtime/style-val ~value))])
                        (:entries s))
          form  `(cljs.core/js-obj ~@pairs)]
      (if (and (:static? s) (not inline?))
        (hoist! st :style form)
        form))))

(defn- attr-pair [{:keys [react-name kind value literal?]}]
  [react-name
   (cond
     (and literal? (keyword? value)) (name value)
     literal? value
     (= kind :property) value                      ; properties pass through
     :else `(re-frame.ui.runtime/attr-val ~value))])

(defn- element-prop-pairs
  "-> [{:name str :form any :static? bool} ...] in canonical order:
  class, [sugar-id + author props in source order], style, ref."
  [st node inner-inline?]
  (let [{:keys [props]} node
        class-a (:class props)
        class-p (when class-a
                  (when-some [f (class-form class-a)]
                    [{:name "className" :form f :static? (:static? class-a)}]))
        attr-ps (map (fn [a]
                       (let [[n f] (attr-pair a)]
                         {:name n :form f :static? (:literal? a)}))
                     (:attrs props))
        passive-events (filter :passive? (:events props))
        react-events   (remove :passive? (:events props))
        event-ps (map (fn [h]
                        {:name (rules/react-event-name (:name h) (:capture? h))
                         :form (handler-form h)
                         :static? false})
                      react-events)
        style-p (when (:style props)
                  [{:name "style"
                    :form (style-form st (:style props) inner-inline?)
                    :static? (:static? (:style props))}])
        authored-ref-form (emitted-ref-form (:ref props))
        ref-form (cond
                   (seq passive-events)
                   `(re-frame.ui.events/passive-ref
                     [~@(map #(passive-listener-spec % (:custom? node))
                             passive-events)]
                     ~authored-ref-form
                     ~(when (seq *row-key-forms*) (vec *row-key-forms*)))

                   (:ref props)
                   authored-ref-form)
        ref-p   (when ref-form
                  [{:name "ref" :form ref-form :static? false}])
        html-p  (when (:html node)
                  [{:name "dangerouslySetInnerHTML"
                    :form `(cljs.core/js-obj "__html" ~(:form (:html node)))
                    :static? (:static? (:html node))}])]
    (vec (concat class-p attr-ps event-ps style-p ref-p html-p))))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(declare emit-node)

(defn- children-forms [st nodes inline?]
  (into [] (keep #(emit-node % st inline?)) nodes))

(defn- ordered-literal-object
  "Emit an object literal for compile-known string keys. `js-obj` preserves
  dynamic value order through an IIFE; here the literal's own left-to-right
  property evaluation provides that guarantee without the per-render call.
  Keys remain string expressions, so :advanced cannot rename React/custom
  property spelling. JavaScript gives a colon-form `__proto__` definition
  prototype-setter semantics, so that one magic key uses a computed property:
  an ordinary own data property with the same evaluation order."
  [pairs]
  (let [pairs (vec pairs)]
    (list* 'js*
           (str "({"
                (str/join "," (map (fn [[k _]]
                                      (if (= "__proto__" k)
                                        "[~{}]:~{}"
                                        "~{}:~{}"))
                                    pairs))
                "})")
           (mapcat identity pairs))))

(defn- jsx-runtime-call [multi? tag-form props-form key-info]
  ;; Invoke the imported JS MODULE PROPERTY, not a CLJS var holding the
  ;; imported function and not a forwarding wrapper. `js*` is intentional at
  ;; this emitter boundary: a CLJS property-read invocation is otherwise
  ;; treated as IFn (or as a receiver-preserving method call), while the JS
  ;; module alias that makes `(jsxrt/jsx ...)` direct exists only in runtime's
  ;; namespace. These four closed templates emit exactly the same plain JS
  ;; call shape as a caller-local `react/jsx-runtime` alias.
  ;; `:present?` deliberately selects React's 3-argument API even when the
  ;; authored key expression is nil or false.
  (list* 'js*
         (case [multi? (boolean (:present? key-info))]
           [false false] "(0,~{}.jsx)(~{},~{})"
           [false true]  "(0,~{}.jsx)(~{},~{},~{})"
           [true false]  "(0,~{}.jsxs)(~{},~{})"
           [true true]   "(0,~{}.jsxs)(~{},~{},~{})")
         're-frame.ui.runtime/jsx-runtime
         tag-form
         props-form
         (when (:present? key-info) [(:expr key-info)])))

(defn- jsx-call [tag-form pairs children-forms key-info]
  (let [nch           (count children-forms)
        multi?        (> nch 1)
        children-form (cond
                        (zero? nch) nil
                        (= 1 nch)   (first children-forms)
                        :else       `(cljs.core/array ~@children-forms))
        props-form    (ordered-literal-object
                       (concat (map (fn [{:keys [name form]}] [name form]) pairs)
                               (when (some? children-form)
                                 [["children" children-form]])))]
    (jsx-runtime-call multi? tag-form props-form key-info)))

(defn- emit-element [node st inline?]
  (let [static?       (:static? node)
        inner-inline? (or inline? static?)
        tag-str       (name (:tag node))
        key-info      (get-in node [:props :key])]
    (cond
      (get-in node [:props :spread])
      (let [spread (get-in node [:props :spread])
            sugar (get-in node [:props :class :base-str])
            props-form `(re-frame.ui.runtime/spread->props
                         ~tag-str
                         ~(when (seq sugar) sugar)
                         ~(:base spread)
                         ~(:overrides spread)
                         ~(site-key-form spread)
                         ~(debug-site-form (assoc spread :classification :spread)))
            chs (children-forms st (:children node) inner-inline?)]
        ;; spread props objects are runtime-built; children ride the same call
        (if (:present? key-info)
          `(re-frame.ui.runtime/jsx-spread3 ~tag-str ~props-form
                                            ~(:expr key-info)
                                            (cljs.core/array ~@chs))
          `(re-frame.ui.runtime/jsx-spread2 ~tag-str ~props-form
                                            (cljs.core/array ~@chs))))

      (get-in node [:props :safe-spread])
      ;; ui/spread-safe: the OWNED props compile normally (their :on-* handlers
      ;; keep the compiled per-site sync-door callback), and the CALLER attrs
      ;; are a runtime-built object layered UNDER them — owned props win any
      ;; collision, :class composes (owned classes first), and the owned-key
      ;; deny law is enforced at runtime by spread-safe->props in EVERY build.
      (let [ss          (get-in node [:props :safe-spread])
            owned-pairs (element-prop-pairs st node inner-inline?)
            owned-obj   (ordered-literal-object
                         (map (fn [{:keys [name form]}] [name form]) owned-pairs))
            caller-obj  `(re-frame.ui.runtime/spread-safe->props
                          ~tag-str
                          ~(:base ss)
                          ~(:owned-handler-keys ss)
                          ~(site-key-form ss)
                          ~(debug-site-form (assoc ss :classification :spread)))
            props-form  `(re-frame.ui.runtime/spread-safe-props ~caller-obj ~owned-obj)
            chs         (children-forms st (:children node) inner-inline?)]
        (if (:present? key-info)
          `(re-frame.ui.runtime/jsx-spread3 ~tag-str ~props-form
                                            ~(:expr key-info)
                                            (cljs.core/array ~@chs))
          `(re-frame.ui.runtime/jsx-spread2 ~tag-str ~props-form
                                            (cljs.core/array ~@chs))))

      :else
      (let [pairs (element-prop-pairs st node inner-inline?)
            chs   (children-forms st (:children node) inner-inline?)
            ;; prebuilt static props object under a dynamic key
            call  (jsx-call tag-str pairs chs key-info)]
        (if (and static? (not inline?))
          (hoist! st :el call)
          call)))))

(defn- emit-fragment [node st inline?]
  (let [static?       (:static? node)
        inner-inline? (or inline? static?)
        chs  (children-forms st (:children node) inner-inline?)
        call (jsx-call `re-frame.ui.runtime/Fragment [] chs (:key node))]
    (if (and static? (not inline?))
      (hoist! st :el call)
      call)))

(defn- component-prop-pair
  "One [slot value-form] pair for a component call-site prop. A compiled render
  slot (`ui/render-fn`) emits its lexically-visible pure body as a marked
  callback the seam invokes via ui/slot; every other prop carries its walked
  value verbatim."
  [{:keys [slot value render-fn]} st]
  (if render-fn
    [slot `(re-frame.ui.runtime/render-fn
            (fn [~@(:params render-fn)] ~(emit-node (:body render-fn) st false)))]
    [slot value]))

(defn- emit-component [node st inline?]
  (let [key-info (get-in node [:props :key])
        chs      (children-forms st (:children node) false)
        nch      (count chs)
        children-form (cond
                        (zero? nch) nil
                        (= 1 nch)   (first chs)
                        :else       `(cljs.core/array ~@chs))
        entries  (get-in node [:props :entries])
        ref-a    (get-in node [:props :ref])
        props-form (ordered-literal-object
                    (concat (map #(component-prop-pair % st) entries)
                            (when (and ref-a (= :foreign (:op node)))
                              [["ref" (:form ref-a)]])
                            (when (some? children-form)
                              [["children" children-form]])))
        multi?   (> nch 1)
        ;; A self-recursive head (the exact view being compiled) MUST target the
        ;; current-namespace Var (its canonical `:fqn`), never the authored
        ;; spelling: the render fn is a separate `$render` def emitted BEFORE the
        ;; view's own `(def …)`, so a bare self head would resolve through any
        ;; same-named `:refer` (cljs.analyzer/resolve-var checks `:uses` ahead of
        ;; `:defs`) and capture the public authoring Var. `emit-defview` reads the
        ;; recorded flag to forward-`declare` the Var so the qualified reference
        ;; is not undeclared. Unrelated foreign/view heads keep their spelling.
        self-fqn (:self-fqn @st)
        self?    (and (some? self-fqn) (= (:fqn node) self-fqn))
        head-sym (if self? (:fqn node) (:sym node))]
    (when self? (swap! st assoc :self-ref? true))
    (jsx-runtime-call multi? head-sym props-form key-info)))

(defn- row-key-expr [body]
  (or (get-in body [:props :key :expr]) (get-in body [:key :expr])))

(defn- replace-row-key-expr
  "Use the once-bound row key for the body root's React key. The analyzer has
  already restricted a for body to an element/view/foreign/fragment."
  [body key-form]
  (if (= :fragment (:op body))
    (assoc-in body [:key :expr] key-form)
    (assoc-in body [:props :key :expr] key-form)))

(defn- emit-for [node st]
  (let [arr  (gensym "rf-ui-arr")
        seen (gensym "rf-ui-seen")
        row-key (gensym "rf-ui-row-key")
        kexpr (row-key-expr (:body node))
        row  (binding [*row-key-forms* (conj *row-key-forms* row-key)]
               (emit-node (replace-row-key-expr (:body node) row-key)
                          st false))]
    `(let [~arr (cljs.core/array)
           ~seen (cljs.core/js-obj)]
       (doseq [~@(:seq-exprs node)]
         ;; Bind once: React identity, duplicate checking, and passive native
         ;; attachment ownership must all name the exact same occurrence.
         (let [~row-key ~kexpr]
           (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.ui.runtime/check-key! ~seen ~row-key))
           (.push ~arr ~row)))
       ~arr)))

(defn- emit-frame-root
  "A top-region `frame-root` SCOPES its preflight-ensured frame to its
  children through the shared React frame context (rf2-vxgfnd.25 —
  `frames/scope-element`), so an ambient `(sub …)` in a descendant view
  resolves the frame-root's frame (the compiled sub-read consults the
  React-context tier via the rf2-vxgfnd.24 `:adapter/current-frame` reader).
  Its ENSURE semantics ride the extracted static plan (the descriptor
  `:frame-plans` + the client preflight, `re-frame.ui.frames`), which runs
  at host preflight BEFORE any render — never the rendered tree; the emitted
  Provider only SCOPES the already-live frame. Children stay in an array so
  the Provider keys them (mirrors `emit-frame-provider`)."
  [node st inline?]
  `(re-frame.ui.frames/scope-element
    ~(:frame-id node)
    (cljs.core/array ~@(children-forms st (:children node) inline?))))

(defn- emit-frame-provider
  "S2c: `frame-provider` scopes its children to an already-live frame
  (a runtime `:frame` target) through the shared React frame context —
  `re-frame.ui.frames/provider-scope-element` validates the target
  (fail-loud on nil / bad / absent-frame) and wraps the children in the
  context Provider. Children stay in an array so the Provider keys them."
  [node st inline?]
  `(re-frame.ui.frames/provider-scope-element
    ~(:frame node)
    (cljs.core/array ~@(children-forms st (:children node) inline?))))

(defn- emit-slot
  "A `ui/slot` invocation: gate the render-fn value (nil renders nothing; a
  non-render-fn value is the didactic `invalid-slot!`), then invoke it with
  the runtime args — a fixed-arity call whose args evaluate only when the slot
  renders. The output joins the surrounding children like any other child."
  [node st]
  (let [rf      (gensym "rf-ui-slot")
        slotval (if-let [{:keys [params body]} (:render-fn node)]
                  `(re-frame.ui.runtime/render-fn
                    (fn [~@params] ~(emit-node body st false)))
                  (:slot-value node))]
    `(let [~rf ~slotval]
       (when (re-frame.ui.runtime/slot-ready? ~rf)
         (~rf ~@(:args node))))))

(defn emit-node
  "AST node -> CLJS form (nil for a statically-absent child)."
  [node st inline?]
  (case (:op node)
    :nothing  nil
    :text     (:value node)
    :expr     `(re-frame.ui.runtime/child ~(:form node))
    :raw      (:form node)
    :html     nil ; carried by the parent element's dangerouslySetInnerHTML
    :element  (emit-element node st inline?)
    :fragment (emit-fragment node st inline?)
    :frame-root (emit-frame-root node st inline?)
    :frame-provider (emit-frame-provider node st inline?)
    :view     (emit-component node st inline?)
    :foreign  (emit-component node st inline?)
    :slot     (emit-slot node st)
    :if       `(if ~(:test node)
                 ~(emit-node (:then node) st false)
                 ~(emit-node (:else node) st false))
    :let      `(let ~(:bindings node) ~(emit-node (:body node) st false))
    :letfn    `(letfn ~(:fnspecs node) ~(emit-node (:body node) st false))
    :case     `(case ~(:expr node)
                 ~@(mapcat (fn [[test branch]] [test (emit-node branch st false)])
                           (:clauses node))
                 ~@(when (not= ::ana/none (:default node))
                     [(emit-node (:default node) st false)]))
    :for      (emit-for node st)))

;; ---------------------------------------------------------------------------
;; Inline emission (root templates — S1c)
;; ---------------------------------------------------------------------------

(defn emit-inline
  "Emit `ast` as ONE inline CLJS expression: what `hoist!` would lift to
  module-level `def`s becomes `let` bindings around the body instead
  (creation order preserves dependencies). The root-template path —
  `ui/mount` / `ui/render!` / `ui/hydrate-root` sites may sit inside a
  function body (an init fn), where module-level defs are illegal; module
  hoisting stays a defview-only optimisation."
  [ast name-hint]
  (let [st    (new-state name-hint)
        body  (emit-node ast st false)
        binds (into [] (mapcat rest) (:defs @st))]  ; (def sym form) -> sym form
    (if (seq binds)
      `(let [~@binds] ~body)
      body)))

;; ---------------------------------------------------------------------------
;; Header lowering (Q2)
;; ---------------------------------------------------------------------------

(defn- slot-read [slot default]
  (if (some? default)
    `(let [v# (cljs.core/unchecked-get ~props-sym ~slot)]
       (if (cljs.core/undefined? v#) ~default v#))
    `(cljs.core/unchecked-get ~props-sym ~slot)))

(defn header-bindings
  "The CLJS header `let` bindings, in canonical host `destructure` order
  (rf2-vxgfnd.283): `:as` binds the whole props map FIRST — matching the JVM
  native destructuring the JVM emitter uses — then ONE property-read per
  collapsed binding unit. The entries are already the host `bes` order with
  winning lookup slots (`parse-header` routed them through the ONE canonical
  binding plan, `bp/assoc-binding-units`, then `header/collapse-entries`), so
  there is NOTHING left to sort or de-collide here: two header entries binding
  the same local — whether they collided in the `bes` map or only after a
  qualified group local name-strips onto an explicit local — already collapsed
  in `parse-header` to one entry, so this emits exactly one read of the host's
  winning slot (never two, never a silent last-wins), and a qualified `:keys`
  local reads its qualified slot. Emitting `:as` last, or the
  entries in parse order, could resolve a dependent `:or` default to a different
  symbol on CLJS than on the JVM — including a public reactive authoring var."
  [header]
  (concat
   (when (:as-sym header)
     [(:as-sym header) `(re-frame.ui.runtime/props->map ~props-sym)])
   (mapcat (fn [{:keys [slot pattern default]}]
             [pattern (slot-read slot default)])
           (:entries header))))

(defn comparator-form
  "The generated straight-line rf= comparator (RULED: Object.is OR = per
  slot); generic for :as views."
  [header slots]
  (if (= :as (:mode header))
    `re-frame.ui.runtime/props-equal-generic?
    (let [slot-strs (cond-> (mapv #(if-let [ns* (namespace %)]
                                     (str ns* "/" (name %))
                                     (name %))
                                  slots)
                      (:children? header) (conj "children"))
          slot-strs (vec (distinct slot-strs))]
      (if (empty? slot-strs)
        `(fn [_# _#] true)
        ;; explicit gensyms — auto-gensyms don't span the nested
        ;; syntax-quote in the map fn
        (let [prev (gensym "prev") nxt (gensym "next")]
          `(fn [~prev ~nxt]
             (and ~@(map (fn [s]
                           `(re-frame.ui.eq/rf=
                             (cljs.core/unchecked-get ~prev ~s)
                             (cljs.core/unchecked-get ~nxt ~s)))
                         slot-strs))))))))

;; ---------------------------------------------------------------------------
;; defview
;; ---------------------------------------------------------------------------

(defn emit-defview
  [{:keys [vname view-id display-name docstring header slots ast manifest
           closed-keys children? lease-declarations self-fqn]}]
  (let [st         (doto (new-state vname) (swap! assoc :self-fqn self-fqn))
        body       (->> (emit-node ast st false)
                        (hoist-literal-sub-queries st))
        leases     (mapv #(update % :descriptor
                                  (partial hoist-literal-sub-queries st))
                          lease-declarations)
        binds      (vec (header-bindings header))
        render-sym (symbol (str (name vname) "$render"))
        host-render-sym (symbol (str (name vname) "$host_render"))
        ;; The raw body is descriptor data in DEV. Stable Inner supplies the
        ;; fixed ViewCell hook skeleton to EVERY dev view, so adding the first
        ;; sub is a same-signature edit. Production specializes: a sub-bearing
        ;; view uses the host wrapper below; a sub-free view goes straight from
        ;; React.memo to the raw body with zero ViewCell hooks.
        has-subs?  (boolean (seq (:subs (:sites manifest))))
        has-leases? (boolean (seq leases))
        has-events? (boolean (seq (:events (:sites manifest))))
        ;; rf2-vxgfnd.253 (extending .228): a `(frame)` site resolves the AMBIENT
        ;; committed frame, and so does EVERY sub target and EVERY lease owner (a
        ;; sub/lease site implicitly captures the ambient frame/incarnation). So
        ;; any reactive view MUST become a real React frame-context CONSUMER or a
        ;; provider retarget (A→B) memo-bails the non-consumer child and its held
        ;; subs/leases/ops stay locked to the old frame. The sub/lease wrappers
        ;; therefore consume the context unconditionally — frame-ops presence no
        ;; longer forks the selection, so there are NO separate `-frame` variants.
        ;; `render-frame` covers a frame-only view (a `(frame)` site but no
        ;; sub/lease — a context consumer with no ViewCell); a view with NONE of
        ;; the three stays on the inert direct React.memo path.
        has-frame-ops? (boolean (seq (:frame-ops (:sites manifest))))
        lease-binds (vec
                     (mapcat (fn [{:keys [sid descriptor]}]
                               [(gensym "lease")
                                `(re-frame.ui.reactive/lease-site
                                  ~sid ~descriptor)])
                             leases))
        rendered   (if (seq lease-binds)
                     `(let [~@lease-binds] ~body)
                     body)
        inner      (if (seq binds) `(let [~@binds] ~rendered) rendered)
        host-render (cond
                      (and has-subs? has-leases? has-events?)
                      're-frame.ui.viewcell/render-subs-leases-and-events
                      (and has-subs? has-events?)
                      're-frame.ui.viewcell/render-subs-and-events
                      (and has-leases? has-events?)
                      're-frame.ui.viewcell/render-leases-and-events
                      has-events? 're-frame.ui.viewcell/render-events
                      (and has-subs? has-leases?)
                      're-frame.ui.viewcell/render-subs-and-leases
                      has-subs?  're-frame.ui.viewcell/render-subs
                      has-leases? 're-frame.ui.viewcell/render-leases
                      has-frame-ops? 're-frame.ui.viewcell/render-frame)
        var-meta   (cond-> {:rf.ui/view true
                            :rf.ui/view-id view-id
                            :rf.ui/children? children?}
                     docstring   (assoc :doc docstring)
                     closed-keys (assoc :rf.ui/closed-prop-keys (vec closed-keys)))]
    `(do
       ;; A self-recursive view forward-declares its Var so the `$render` fn
       ;; (emitted before the view's own `(def …)`) may reference the
       ;; current-namespace Var by its qualified name without an undeclared-Var
       ;; warning — and so the declaration, not a same-named `:refer`, owns the
       ;; name at the reference site. Non-recursive views emit unchanged.
       ~@(when (:self-ref? @st) [`(declare ~vname)])
       ~@(:defs @st)
       (defn ~render-sym [~props-sym]
         ~inner)
       ~@(when host-render
           [`(defn ~host-render-sym [~props-sym]
               (~host-render
                ~view-id (fn [] (~render-sym ~props-sym))))])
       (def ~(vary-meta vname merge var-meta)
         (let [compare# ~(comparator-form header slots)]
           (if ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.ui.runtime/register-view!
              ~view-id ~render-sym compare# ~display-name (quote ~manifest))
             ;; The production arm is deliberately direct React.memo. Closure
             ;; folds away the sibling registration arm and with it every HMR
             ;; slot/listener/dynamic-lookup/extra-Fiber helper.
             (re-frame.ui.runtime/memo-view
              ~(if host-render host-render-sym render-sym)
              compare# ~display-name))))
       ~vname)))
