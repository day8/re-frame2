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
  (:require [re-frame.source-coords :as source-coord]
            [re-frame.ui.compiler.analyze :as ana]
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

(defn- static-form
  "The `:static` slot value form for a `tree/element` call. `static` is the
  compile-time-normalized literal attr map (or nil). When `annotate` is present
  — this element is the view's compiler-owned host root, marked by
  `mark-host-root-annotation` — the DEV view-evidence annotation
  (`data-rf2-source-coord` + `data-rf-view`, today's attribute vocabulary per
  Spec 004 §View identity / Spec 006 §Source-coord annotation + §View tagging
  contract) merges onto the static attrs behind `re-frame.interop/debug-enabled?`
  (the JVM counterpart to the CLJS `goog.DEBUG` stamp — true by default, off under
  `-Dre-frame.debug=false`). So a dev JVM/SSR render carries the SAME host-root
  evidence the CLJS emit stamps (byte-identical values via the one cross-host
  `re-frame.source-coords` projection), and a production SSR build drops it —
  symmetric with the CLJS `:advanced` + goog.DEBUG=false DCE.

  AUTHORED OWN VALUE WINS (rf2-x1nbv): the annotation only fills an attribute the
  view author did NOT already supply as a LITERAL host-root attr — an authored
  own value in `:static` is preserved, never overwritten. A DYNAMIC / `ui/spread`
  / `ui/spread-safe`-caller authored value is layered OVER `:static` at runtime by
  `tree/element` (dyn over static; caller under owned), so it already wins there;
  this compile-time guard closes the remaining static path so the JVM law matches
  the CLJS `annotate-host-root-props` set-if-absent law across the whole collision
  matrix (static / dynamic / ui.spread / spread-safe)."
  [static annotate]
  (if annotate
    (let [{:keys [source-coord view-tag]} annotate
          base (or static {})
          ann  (cond-> {}
                 (not (contains? base :data-rf2-source-coord))
                 (assoc :data-rf2-source-coord source-coord)
                 (not (contains? base :data-rf-view))
                 (assoc :data-rf-view view-tag))]
      (if (seq ann)
        `(cond-> ~base re-frame.interop/debug-enabled? (merge ~ann))
        (when (seq base) base)))
    (when (seq static) static)))

(defn- with-host-root-provenance
  "Attach DEV, debug-gated PROVENANCE metadata to a host-root element node,
  recording the compiler's canonical view-evidence values
  (`:data-rf2-source-coord` / `:data-rf-view`) so `render-static`'s strip
  (`strip-host-root-annotation`) removes ONLY the generated markers — a key
  whose runtime value still equals the canonical value — and PRESERVES an
  authored own value of the same spelling under the rf2-x1nbv collision law
  (rf2-zgezz #3: the recursive strip otherwise deleted programmer-authored
  nested attributes too).

  Metadata is out-of-band: it is invisible to structural `=` (goldens),
  normalization N (parity/fingerprint), and every HTML serialiser — only the
  JVM render-static strip reads it. Gated on `interop/debug-enabled?`, matching
  the `static-form` stamp, so a production JVM/SSR build attaches nothing."
  [element-form annotate]
  (if annotate
    (let [{:keys [source-coord view-tag]} annotate]
      `(cond-> ~element-form
         re-frame.interop/debug-enabled?
         (vary-meta assoc :rf.ui/host-root-annotation
                    {:data-rf2-source-coord ~source-coord
                     :data-rf-view ~view-tag})))
    element-form))

(defn- emit-element [node]
  (let [{:keys [props]} node
        annotate  (:rf.ui/annotate node)
        tag       (:tag node)
        key-info  (:key props)
        child-forms (if (:html node)
                      [`(re-frame.ui.tree/html ~(:form (:html node)))]
                      (into [] (keep emit-node) (:children node)))
        child-thunk (when (seq child-forms)
                      `(fn [] (re-frame.ui.tree/children ~@child-forms)))]
    (with-host-root-provenance
     (cond
      (:spread props)
      (let [spread (:spread props)
            sugar (get-in props [:class :base-str])]
        `(re-frame.ui.tree/element
          ~tag
          {:static ~(static-form (when (seq sugar) {:class sugar}) annotate)
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
          {:static ~(static-form static annotate)
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
          {:static ~(static-form static annotate)
           :norm   ~(when (seq norm) norm)
           :dyn    ~(when (seq dyn) dyn)
           :events ~(when (seq stat-ev) stat-ev)
           :dyn-events ~(when (seq dyn-ev) dyn-ev)
           :key?   ~(:present? key-info)
           :key-val ~(:expr key-info)
           :props  ~(when (seq pp) pp)
           :children ~child-thunk}))) annotate)))

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

(defn- mark-host-root-annotation
  "Stamp the view-evidence DOM-annotation marker onto the view's effective host
  root(s) — the DOM `:element`(s) the compiler owns at the top of the render. The
  UNCONDITIONAL wrappers (`:hook-prefix` effect prefix, an authored `:let` /
  `:letfn`) are transparent — the marker rides through to the element they wrap.

  A COMMON CONDITIONAL root is narrowly DESCENDED so its concrete DOM arms are
  tagged: `if` / `if-not` / `when` / `when-not` / `cond` (each an `:if`), `case`
  (its clauses + default), and the if-let family (`if-let` / `when-let` /
  `if-some` / `when-some`, which desugar to `:let` over `:if`). So a view rooted
  at `(if loading? [:spinner] [:page])` tags whichever arm renders. Spec 006
  exempts only the render that yields nil; a later concrete DOM output must be
  tagged. The recursion RETAINS the per-branch host-root exemptions: a branch
  that is nil (`:nothing`, e.g. a one-arm `when`'s absent else), a fragment,
  another view/foreign component, or a loop (`:for`) is NOT a single
  compiler-owned host node and carries no annotation — the same exemption the
  CLJS emit and the adapter source-coord walk apply to a non-DOM root.

  `emit-element` reads the marker off the plain element and merges the annotation
  into its `:static` attrs behind the `interop/debug-enabled?` gate (see
  `static-form`). The JVM twin of
  `re-frame.ui.compiler.emit-cljs/mark-host-root-annotation`, kept BYTE-IDENTICAL:
  a prop/attribute annotation is inherently cross-emitter, so the two walks MUST
  descend the same forms or a conditional-rooted view diverges under the
  JVM<->CLJS parity gate and SSR/client hydration."
  [ast annotate]
  (case (:op ast)
    :element (assoc ast :rf.ui/annotate annotate)
    (:hook-prefix :let :letfn) (update ast :body mark-host-root-annotation annotate)
    :if   (-> ast
              (update :then mark-host-root-annotation annotate)
              (update :else mark-host-root-annotation annotate))
    :case (-> ast
              (update :clauses
                      (fn [clauses]
                        (mapv (fn [[test branch]]
                                [test (mark-host-root-annotation branch annotate)])
                              clauses)))
              (update :default
                      (fn [d]
                        (if (= ::ana/none d) d
                            (mark-host-root-annotation d annotate)))))
    ast))

(defn emit-defview
  [{:keys [vname view-id docstring header ast manifest closed-keys children?
           self-fqn]}]
  (let [;; The DEV view-evidence DOM annotation for this view's compiler-owned
        ;; host root — the source coordinate + view id, in today's attribute
        ;; vocabulary (Spec 004 §View identity), via the cross-host
        ;; `re-frame.source-coords` projections (byte-identical to the CLJS emit
        ;; and the adapter walks by construction). Marked onto the effective root
        ;; element and merged behind the `interop/debug-enabled?` gate; a
        ;; non-element root carries none.
        annotate {:source-coord (source-coord/format-source-coord
                                 view-id (:source manifest))
                  :view-tag     (source-coord/format-view-id view-id)}
        rendered (binding [*self-fqn* self-fqn]
                   (emit-node (mark-host-root-annotation ast annotate)))
        bind     (:binding-form header)
        var-meta (cond-> {:rf.ui/view true
                          :rf.ui/view-id view-id
                          ;; Kept byte-identical to the CLJS emitter's view
                          ;; descriptor (rf2-u53yy.1 S2). On the JVM host the
                          ;; whole-build view registry still rides the per-source
                          ;; slice (there is no Shadow disk cache to survive), so
                          ;; this metadata is not the harvest carrier here; it is
                          ;; carried for parity so `(meta #'view)` reads the same
                          ;; descriptor on both hosts.
                          :rf.ui/view-digest [(:template-fingerprint manifest)
                                              (:hook-signature manifest)]
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
