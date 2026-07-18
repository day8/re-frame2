(ns re-frame.ui.compiler.emit-jvm
  "AST -> JVM forms building the versioned public structural tree
  (node schema v1) via `re-frame.ui.tree`. The same normalized AST the
  CLJS emitter consumes — no emitter consumes raw source or another
  emitter's output (the portability law).

  Event vectors are retained AS DATA (placeholders stay keywords);
  fn-carried sites become the opaque marker; foreign heads and `ui/raw`
  children raise :rf.error/jvm-host-op lazily (only when their branch
  renders); `ui/raw`/`ui/raw-fn` PROP values become opaque markers
  WITHOUT evaluating their expressions (they may be host-only)."
  (:require [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.rules :as rules]))

(def ^:private props-sym 'rf-ui-props)

;; The canonical current-namespace Var of the view being emitted, bound by
;; `emit-defview` around its body. A self-recursive component head targets THIS
;; fqn (matching its `:fqn`) rather than the authored spelling, so the emitted
;; call names the current-namespace Var explicitly instead of relying on the
;; enclosing `defn`'s intern order to shadow a same-named `:refer` (rf2-rr26cq).
(def ^:dynamic *self-fqn* nil)

(declare emit-node)

;; ---------------------------------------------------------------------------
;; Element parts
;; ---------------------------------------------------------------------------

(defn- static-attr-entries
  "Compile-time semantic normalization of literal attr values."
  [attrs]
  (into {}
        (keep (fn [{:keys [k kind value literal?]}]
                (when literal?
                  (let [v (if (= :property kind)
                            value                       ; properties pass raw
                            (rules/attr-val-semantic k value))]
                    (when (some? v) [k v])))))
        attrs))

(defn- dyn-attr-entries [attrs]
  (into {}
        (keep (fn [{:keys [k kind value literal?]}]
                (when-not literal?
                  ;; properties pass raw; plain attrs are normalized by
                  ;; tree/element's :dyn path — mark properties via :norm
                  (when (not= :property kind)
                    [k value]))))
        attrs))

(defn- dyn-property-entries [attrs]
  (into {}
        (keep (fn [{:keys [k kind value literal?]}]
                (when (and (not literal?) (= :property kind))
                  [k value])))
        attrs))

(defn- class-entry
  "Semantic :class value: constant string when static, else a runtime
  classes-str form (sugar-first + lexicographic flags, pre-sorted by the
  analyzer)."
  [c]
  (when c
    (let [{:keys [base-str flags dyn]} c]
      (if (and (empty? flags) (nil? dyn))
        (when (seq base-str) base-str)
        `(rules/classes-str
          [~@(when (seq base-str) [base-str])
           ~@(map (fn [[n f]] `(when ~f ~n)) flags)
           ~@(when dyn [`(rules/class-val ~dyn)])])))))

(defn- style-entry
  "Semantic :style map form: {kw css-string}; literal values normalized
  at compile time, dynamic ones via css-val->str at render. A wholly-
  dynamic :style expression routes through tree/element's :dyn path
  (which applies the same normalization) — returns [:norm form] or
  [:dyn form]."
  [s]
  (when s
    (if-let [dyn (:dyn s)]
      [:dyn dyn]
      (let [m (into {}
                    (map (fn [{:keys [css-name value literal?]}]
                           [(keyword css-name)
                            (if literal?
                              (rules/css-val->str css-name value)
                              `(rules/css-val->str ~css-name ~value))]))
                    (:entries s))]
        (when (seq m) [:norm m])))))

(defn- event-entries
  "-> [static-events-map dyn-events-map-form]. Literal vectors/options
  maps stay data forms (args evaluate at render — locals capture);
  fn sites become the opaque marker (form NOT evaluated on the JVM);
  dynamic sites classify at render."
  [events]
  (reduce (fn [[stat dyn] {:keys [k classification form]}]
            (case classification
              :vector   [(assoc stat k form) dyn]
              :options  [(assoc stat k form) dyn]
              ;; ui/event and ui/handler, like a bare fn, are opaque committed
              ;; callbacks whose body the JVM tree never evaluates (non-
              ;; serialisable sites).
              :ui-event [(assoc stat k `re-frame.ui.tree/opaque-fn) dyn]
              :handler  [(assoc stat k `re-frame.ui.tree/opaque-fn) dyn]
              :fn       [(assoc stat k `re-frame.ui.tree/opaque-fn) dyn]
              :dynamic  [stat (assoc dyn k `(re-frame.ui.tree/classify-event ~form))]))
          [{} {}]
          events))

(defn- emit-element [node]
  (let [{:keys [props]} node
        tag       (:tag node)
        key-info  (:key props)
        child-forms (if (:html node)
                      [`(re-frame.ui.tree/html ~(:form (:html node)))]
                      (into [] (keep emit-node) (:children node)))
        child-thunk (when (seq child-forms)
                      `(fn [] (re-frame.ui.tree/children ~@child-forms)))]
    (cond
      (:spread props)
      (let [spread (:spread props)
            sugar (get-in props [:class :base-str])]
        `(re-frame.ui.tree/element
          ~tag
          {:static ~(when (seq sugar) {:class sugar})
           :dyn    (merge ~(:base spread) ~(:overrides spread))
           :key?   ~(:present? key-info)
           :key-val ~(:expr key-info)
           :children ~child-thunk}))

      ;; ui/spread-safe: the OWNED props emit exactly as a normal element's do
      ;; (from the same analysed props); the CALLER map rides a `:spread-safe`
      ;; opt that `tree/element` guards (owned-key deny in EVERY build) and
      ;; folds UNDER the owned attrs (owned wins, :class composes).
      ;;
      ;; EVALUATION ORDER (rf2-m5h0f) is LOAD-BEARING: the opts-map literal below
      ;; lists the owned `:norm`/`:dyn`/`:dyn-events` slots BEFORE the
      ;; `:spread-safe :caller` slot, so — a Clojure map literal evaluating its
      ;; value expressions left-to-right, once each — the owned prop expressions
      ;; run BEFORE the caller's, matching the authored `(spread-safe owned
      ;; caller)` order. The CLJS emitter forces the same owned-then-caller order
      ;; with `let` temporaries. Do NOT reorder these keys.
      (:safe-spread props)
      (let [ss      (:safe-spread props)
            [stat-ev dyn-ev] (event-entries (:events props))
            cls     (class-entry (:class props))
            [style-slot style-form] (style-entry (:style props))
            static  (cond-> (static-attr-entries (:attrs props))
                      (string? cls) (assoc :class cls))
            norm    (cond-> (dyn-property-entries (:attrs props))
                      (and cls (not (string? cls))) (assoc :class cls)
                      (= :norm style-slot)          (assoc :style style-form))
            dyn     (cond-> (dyn-attr-entries (:attrs props))
                      (= :dyn style-slot) (assoc :style style-form))
            pp      (into #{}
                          (comp (filter #(= :property (:kind %))) (map :k))
                          (:attrs props))]
        `(re-frame.ui.tree/element
          ~tag
          {:static ~(when (seq static) static)
           :norm   ~(when (seq norm) norm)
           :dyn    ~(when (seq dyn) dyn)
           :events ~(when (seq stat-ev) stat-ev)
           :dyn-events ~(when (seq dyn-ev) dyn-ev)
           :key?   ~(:present? key-info)
           :key-val ~(:expr key-info)
           :props  ~(when (seq pp) pp)
           :spread-safe {:caller ~(:base ss)
                         :owned-handler-keys ~(:owned-handler-keys ss)}
           :children ~child-thunk}))

      :else
      (let [[stat-ev dyn-ev] (event-entries (:events props))
            cls     (class-entry (:class props))
            [style-slot style-form] (style-entry (:style props))
            static  (cond-> (static-attr-entries (:attrs props))
                      (string? cls) (assoc :class cls))
            norm    (cond-> (dyn-property-entries (:attrs props))
                      (and cls (not (string? cls))) (assoc :class cls)
                      (= :norm style-slot)          (assoc :style style-form))
            dyn     (cond-> (dyn-attr-entries (:attrs props))
                      (= :dyn style-slot) (assoc :style style-form))
            pp      (into #{}
                          (comp (filter #(= :property (:kind %))) (map :k))
                          (:attrs props))]
        `(re-frame.ui.tree/element
          ~tag
          {:static ~(when (seq static) static)
           :norm   ~(when (seq norm) norm)
           :dyn    ~(when (seq dyn) dyn)
           :events ~(when (seq stat-ev) stat-ev)
           :dyn-events ~(when (seq dyn-ev) dyn-ev)
           :key?   ~(:present? key-info)
           :key-val ~(:expr key-info)
           :props  ~(when (seq pp) pp)
           :children ~child-thunk})))))

;; ---------------------------------------------------------------------------
;; Components
;; ---------------------------------------------------------------------------

(defn- prop-value-form [{:keys [value marker render-fn]}]
  (case marker
    :foreign    `re-frame.ui.tree/opaque-foreign
    :ui/raw-fn  {:rf.ui/opaque :ui/raw-fn}
    ;; ui/event and ui/handler at a component prop are non-serialisable committed
    ;; callbacks — opaque on the JVM structural tree (the body never evaluates,
    ;; the foreign invocation phase never happens server-side).
    :ui-event   `re-frame.ui.tree/opaque-fn
    :handler    `re-frame.ui.tree/opaque-fn
    ;; A compiled render slot: the lexically-visible pure body compiles to a
    ;; carrier the seam invokes via ui/slot. On the JVM its rendered output is
    ;; a structural tree node.
    :render-fn  `(re-frame.ui.tree/render-fn
                  (fn [~@(:params render-fn)] ~(emit-node (:body render-fn)))
                  ~(count (:params render-fn)))
    value))

(defn- emit-view [node]
  (let [entries   (get-in node [:props :entries])
        key-info  (get-in node [:props :key])
        child-forms (into [] (keep emit-node) (:children node))
        props-map (into {}
                        (map (fn [{:keys [k] :as en}] [k (prop-value-form en)]))
                        entries)
        props-form (if (seq child-forms)
                     `(assoc ~props-map :children
                             (some-> (re-frame.ui.tree/children ~@child-forms)
                                     re-frame.ui.tree/child-vec))
                     props-map)
        self?     (and (some? *self-fqn*) (= (:fqn node) *self-fqn*))
        head-sym  (if self? (:fqn node) (:sym node))
        call      `(~head-sym ~props-form)]
    (if (:present? key-info)
      `(assoc ~call :key ~(:expr key-info))
      call)))

(defn- emit-foreign [node]
  (if (:lazy? node)
    ;; a re-frame.ui.react/lazy component IS callable on the JVM structural
    ;; render: it renders its declared fallback (or nothing) and NEVER invokes
    ;; the load thunk (which is not even emitted on the JVM). Props/children are
    ;; irrelevant — the fallback is capability-free markup baked into the value.
    `(~(:sym node) {})
    `(re-frame.ui.tree/jvm-host-op!
      :foreign-component
      ~(str "foreign React component " (:sym node) " in a JVM render — "
            "foreign components never appear in the JVM tree; wrap the "
            "subtree in ui/client-only (S3)"))))

(defn- emit-for [node]
  `(re-frame.ui.tree/keyed-run
    (into [] (for [~@(:seq-exprs node)] ~(emit-node (:body node))))))

(defn- emit-slot
  "A `ui/slot` invocation: gate the render-fn value (nil renders nothing; a
  non-render-fn value is the didactic `invalid-slot!`), then invoke the
  carrier's compiled body with the runtime args — a fixed-arity call whose
  args evaluate only when the slot renders. The rendered output is an
  ordinary tree child."
  [node]
  (let [rf      (gensym "rf-ui-slot")
        slotval (if-let [{:keys [params body]} (:render-fn node)]
                  `(re-frame.ui.tree/render-fn (fn [~@params] ~(emit-node body))
                                               ~(count params))
                  (:slot-value node))]
    `(let [~rf ~slotval]
       (when (re-frame.ui.tree/slot-ready? ~rf)
         (re-frame.ui.tree/check-slot-arity! ~rf ~(count (:args node)))
         ((:f ~rf) ~@(:args node))))))

(defn emit-node
  "AST node -> JVM form (nil for a statically-absent child)."
  [node]
  (case (:op node)
    :nothing  nil
    :text     (:value node)
    :expr     (:form node)
    :raw      `(re-frame.ui.tree/jvm-host-op!
                :ui/raw "(ui/raw ...) is a host React element")
    :html     nil ; sole-child form carried by the parent element
    :element  (emit-element node)
    :fragment (let [k (:key node)]
                `(re-frame.ui.tree/fragment
                  ~(:present? k) ~(:expr k)
                  ~@(keep emit-node (:children node))))
    ;; A top-region frame-root SCOPES its ensured frame (rf2-vxgfnd.25): the
    ;; JVM has no React context, so it BINDS the dynamic-tier ambient frame
    ;; to the frame-root's literal :id around the subtree's construction (the
    ;; mirror of the CLJS scope-element / of jvm-provider-scope). ENSURE
    ;; already ran at host preflight; the subtree itself is a transparent
    ;; fragment in the structural tree. So an ambient (sub …) in a descendant
    ;; view resolves the frame-root's frame during a Tier-1 structural render.
    :frame-root `(re-frame.ui.frames/jvm-root-scope
                  ~(:frame-id node)
                  (fn []
                    (re-frame.ui.tree/fragment
                     false nil ~@(keep emit-node (:children node)))))
    ;; S2c: frame-provider scopes by BINDING the dynamic-tier ambient frame
    ;; around the subtree's construction (the JVM has no React context); the
    ;; subtree itself is a transparent fragment in the structural tree
    :frame-provider `(re-frame.ui.frames/jvm-provider-scope
                      ~(:frame node)
                      (fn []
                        (re-frame.ui.tree/fragment
                         false nil ~@(keep emit-node (:children node)))))
    :view     (emit-view node)
    :foreign  (emit-foreign node)
    :slot     (emit-slot node)
    ;; error-boundary is a CLIENT recovery mechanism (Spec 004 §The JVM
    ;; structural subset): the JVM/SSR renders the guarded CHILD transparently
    ;; under the server failure policy (per 011); it never renders the fallback
    ;; (that is client recovery). A throw below it is the server's to project.
    :error-boundary (emit-node (:child node))
    ;; presence: the JVM has no lifecycle, so it renders the children :present,
    ;; wrapped in the `:rf.ui/presence {:phase :present :timeout-ms n}` fragment
    ;; (§004B — presence metadata exposed structurally).
    :presence `(re-frame.ui.tree/presence
                ~(:timeout-ms node)
                ~@(keep emit-node (:children node)))
    ;; client-only: the JVM/SSR renders the deterministic capability-free
    ;; FALLBACK, wrapped in the `:rf.ui/boundary :client-only` fragment (§004B);
    ;; the browser-only client subtree never appears on the JVM tree.
    :client-only `(re-frame.ui.tree/client-only-fallback
                   ~(emit-node (:fallback node)))
    ;; Leading (effect …) statements: a `do` sequences the JVM effect stubs
    ;; (no-ops) then renders the template. `local` mutators / dispatch-fn stay
    ;; unevaluated in their statement bodies until a host test invokes them.
    :hook-prefix `(do ~@(:statements node) ~(emit-node (:body node)))
    :if       `(if ~(:test node)
                 ~(emit-node (:then node))
                 ~(emit-node (:else node)))
    :let      `(let ~(:bindings node) ~(emit-node (:body node)))
    :letfn    `(letfn ~(:fnspecs node) ~(emit-node (:body node)))
    :case     `(case ~(:expr node)
                 ~@(mapcat (fn [[test branch]] [test (emit-node branch)])
                           (:clauses node))
                 ~@(when (not= ::ana/none (:default node))
                     [(emit-node (:default node))]))
    :for      (emit-for node)))

;; ---------------------------------------------------------------------------
;; defview
;; ---------------------------------------------------------------------------

(defn emit-defview
  [{:keys [vname view-id docstring header ast manifest closed-keys children?
           lease-declarations self-fqn]}]
  (let [body     (binding [*self-fqn* self-fqn] (emit-node ast))
        bind     (:binding-form header)
        lease-binds
        (vec
         (mapcat (fn [{:keys [descriptor]}]
                   [(gensym "lease")
                    `(re-frame.ui.lease-descriptor/validate-descriptor! ~descriptor)])
                 lease-declarations))
        rendered (if (seq lease-binds)
                   `(let [~@lease-binds] ~body)
                   body)
        var-meta (cond-> {:rf.ui/view true
                          :rf.ui/view-id view-id
                          :rf.ui/children? children?}
                   docstring   (assoc :doc docstring)
                   closed-keys (assoc :rf.ui/closed-prop-keys (vec closed-keys)))]
    `(do
       (defn ~(vary-meta vname merge var-meta) [~props-sym]
         (re-frame.ui.tree/view-boundary
          ~view-id
          ~props-sym
          ~(if bind `(let [~bind ~props-sym] ~rendered) rendered)))
       (re-frame.ui.tree/register-view! ~view-id ~vname (quote ~manifest))
       (var ~vname))))
