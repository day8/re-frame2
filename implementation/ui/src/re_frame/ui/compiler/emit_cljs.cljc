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
            [re-frame.source-coords :as source-coord]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.rules :as rules]
            ;; JVM-only: a self-recursive view completes the same-named `:refer`
            ;; shadowing in the LIVE CLJS analyzer state so its def/declare
            ;; resolve to the current-ns Var (see `emit-defview`). Never loaded on
            ;; the CLJS host — the emitter only mutates analyzer state on the JVM
            ;; macro-expansion path.
            #?(:clj [cljs.env])))

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

    :handler
    ;; ui/handler — the explicit imperative committed callback (the bare-fn
    ;; shorthand's visible spelling); its return is dropped, no dispatch.
    `(re-frame.ui.events/handler-callback
      ~(site-key-form h) ~(:form h) ~(debug-site-form h))

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

(defn- annotate-host-root-props
  "Stamp the DEV view-evidence DOM annotation onto a compiler-owned host root's
  props object: `data-rf2-source-coord` (the `<ns>:<sym>:<line>:<col>` source
  coordinate — click-to-source) and `data-rf-view` (the view id — the record
  identity Xray matches its hover-highlight against). This is today's attribute
  vocabulary (Spec 004 §View identity — Source ↔ DOM navigation; Spec 006
  §Source-coord annotation + §View tagging contract), reusing the ONE cross-host
  `re-frame.source-coords` value projections so the compiler-owned host root and
  the Reagent/UIx/Helix adapter walks emit byte-identical attribute values by
  construction — existing Xray click-to-source works day one.

  The stamp sits behind `^boolean js/goog.DEBUG`, so under :advanced +
  goog.DEBUG=false Closure constant-folds the gate to false and DCEs the whole
  branch — the literal `data-rf2-source-coord` / `data-rf-view` strings never
  reach the production bundle (both are on the standard elision sentinel set).
  The props object is mutated in place BEFORE it reaches the JSX runtime, exactly
  as the trusted-markup spread branch sets `dangerouslySetInnerHTML`.

  AUTHORED OWN VALUE WINS (rf2-x1nbv): each attribute is stamped ONLY when the
  props object does not already carry it — an author-supplied own value (a literal
  pair, a dynamic attr, a `ui/spread` merge, or a `ui/spread-safe` result) is never
  overwritten. This is the adapter / trust-programmer law: because this runs over
  the FINAL props object (after every spread/spread-safe merge), the surviving
  authored value wins uniformly, and the JVM `static-form` twin matches it. The
  `unchecked-get` presence reads sit INSIDE the `goog.DEBUG` gate, so they DCE with
  the stamp under :advanced + goog.DEBUG=false.

  The per-commit integer `render-key` is deliberately NOT stamped here: it is
  minted at CONNECTED COMMIT in the ViewCell layout effect (`reactive/commit*`),
  which runs AFTER this child host element has already committed, so no honest
  value for THIS commit exists at render time. Stamping render-key onto the DOM
  is therefore a commit-time concern owned by the substrate, not the compiler."
  [props-form {:keys [source-coord view-tag]}]
  (let [p (gensym "rf-ui-host-root")]
    `(let [~p ~props-form]
       (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
         (when (cljs.core/undefined? (cljs.core/unchecked-get ~p "data-rf2-source-coord"))
           (cljs.core/unchecked-set ~p "data-rf2-source-coord" ~source-coord))
         (when (cljs.core/undefined? (cljs.core/unchecked-get ~p "data-rf-view"))
           (cljs.core/unchecked-set ~p "data-rf-view" ~view-tag)))
       ~p)))

(defn- jsx-call
  ([tag-form pairs children-forms key-info]
   (jsx-call tag-form pairs children-forms key-info nil))
  ([tag-form pairs children-forms key-info annotate]
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
     (jsx-runtime-call multi? tag-form
                       (cond-> props-form
                         annotate (annotate-host-root-props annotate))
                       key-info))))

(defn- emit-element [node st inline?]
  (let [static?       (:static? node)
        inner-inline? (or inline? static?)
        tag-str       (name (:tag node))
        key-info      (get-in node [:props :key])]
    (cond
      (get-in node [:props :spread])
      ;; ui/spread: the props object is runtime-built by `spread->props`;
      ;; children ride the same call.
      ;;
      ;; TRUSTED MARKUP (rf2-29s75): `ui/spread` and `ui/html` COMPOSE — an
      ;; element may carry a runtime-built prop map AND a trusted-markup sole
      ;; child, and BOTH take effect. This branch does not go through
      ;; `element-prop-pairs` (the `:safe-spread` sibling below does, which is
      ;; why it already composed), so the compiler-owned
      ;; `dangerouslySetInnerHTML` is attached here explicitly; without it the
      ;; markup was silently dropped on CLJS while the JVM emitter kept it —
      ;; a dual-emitter divergence against Spec 004 §Interop.
      ;;
      ;; The prop is set AFTER `spread->props` returns, so the visible
      ;; `(ui/html ...)` site — the trust assertion — WINS any same-key value
      ;; smuggled through the runtime prop map (see rf2-5pr75).
      ;;
      ;; EVALUATION ORDER: `base`/`overrides` are `spread->props` arguments, so
      ;; they evaluate once each in authored order BEFORE the markup expression,
      ;; matching source order and the JVM path (whose `:children` thunk runs
      ;; after the opts map's `:dyn (merge base overrides)`). The
      ;; single-evaluation `let` temporary mirrors the `:safe-spread` branch and
      ;; folds away under :advanced.
      ;;
      ;; The analyzer sets `:children []` on an html-kid element, so no
      ;; positional child accompanies the markup and React's
      ;; children-vs-innerHTML conflict cannot arise.
      (let [spread     (get-in node [:props :spread])
            sugar      (get-in node [:props :class :base-str])
            built      `(re-frame.ui.runtime/spread->props
                         ~tag-str
                         ~(when (seq sugar) sugar)
                         ~(:base spread)
                         ~(:overrides spread)
                         ~(site-key-form spread)
                         ~(debug-site-form (assoc spread :classification :spread)))
            props-form (if-some [html (:html node)]
                         (let [props-sym (gensym "rf-ui-spread")]
                           `(let [~props-sym ~built]
                              (cljs.core/unchecked-set
                               ~props-sym "dangerouslySetInnerHTML"
                               (cljs.core/js-obj "__html" ~(:form html)))
                              ~props-sym))
                         built)
            ;; A DEV-gated view-evidence annotation rides the host root's props
            ;; when this spread element is the view's compiler-owned root (a
            ;; spread element is a single DOM host node, so the same annotation
            ;; as the plain-element branch applies — parity with emit-jvm's
            ;; `static-form` and the adapter source-coord walk). It DCEs in
            ;; production.
            props-form (cond-> props-form
                         (:rf.ui/annotate node)
                         (annotate-host-root-props (:rf.ui/annotate node)))
            chs        (children-forms st (:children node) inner-inline?)]
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
      ;;
      ;; EVALUATION ORDER (rf2-m5h0f): the AUTHORED argument order is
      ;; `(ui/spread-safe owned caller)`, so BOTH hosts evaluate the OWNED prop
      ;; expressions BEFORE the CALLER ones. The JVM emitter already forces this
      ;; — its `tree/element` opts-map literal lists the owned `:norm`/`:dyn`/
      ;; `:dyn-events` slots ahead of the `:spread-safe :caller` slot, and a
      ;; Clojure map literal evaluates its value expressions left-to-right, once
      ;; each. CLJS matches it here with single-evaluation `let` temporaries:
      ;; the owned object is bound first, then the caller object; each authored
      ;; expression runs exactly once, in owned-then-caller order (a bare
      ;; `(spread-safe-props caller-obj owned-obj)` would evaluate the caller
      ;; argument first — the pre-fix CLJS-vs-JVM divergence). Under :advanced
      ;; the temporaries fold away.
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
            owned-sym   (gensym "rf-ui-owned")
            caller-sym  (gensym "rf-ui-caller")
            props-form  `(let [~owned-sym ~owned-obj
                               ~caller-sym ~caller-obj]
                           (re-frame.ui.runtime/spread-safe-props ~caller-sym ~owned-sym))
            ;; Host-root view-evidence annotation (DEV-gated) when this
            ;; spread-safe element is the view's compiler-owned root — parity
            ;; with the plain-element / `ui/spread` branches, emit-jvm, and the
            ;; adapter source-coord walk. Rides ABOVE the owned/caller merge, so
            ;; it stamps the final props object; it DCEs in production.
            props-form  (cond-> props-form
                          (:rf.ui/annotate node)
                          (annotate-host-root-props (:rf.ui/annotate node)))
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
            ;; prebuilt static props object under a dynamic key. A DEV-gated
            ;; view-evidence annotation rides the host root's props when this
            ;; element is the view's compiler-owned root (marked by
            ;; `mark-host-root-annotation`); it DCEs in production.
            call  (jsx-call tag-str pairs chs key-info (:rf.ui/annotate node))]
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
  callback the seam invokes via ui/slot; a `ui/event`/`ui/handler` prop lowers
  to the SAME per-site-stable committed callback a DOM handler rides (the one
  closed boundary law); every other prop carries its walked value verbatim."
  [{:keys [slot value render-fn marker callback-fn] :as entry} st]
  (case marker
    :render-fn
    [slot `(re-frame.ui.runtime/render-fn
            (fn [~@(:params render-fn)] ~(emit-node (:body render-fn) st false))
            ~(count (:params render-fn)))]

    :ui-event
    ;; foreign/view committed event callback — flags 0 (the controlled-input
    ;; door is DOM-only); the invoker's first arg binds the event.
    [slot `(re-frame.ui.events/event-handler
            ~(site-key-form entry) ~callback-fn 0 ~(debug-site-form entry))]

    :handler
    [slot `(re-frame.ui.events/handler-callback
            ~(site-key-form entry) ~callback-fn ~(debug-site-form entry))]

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
        ;; A forwarded ref rides the props object as `ref` (React 19
        ;; ref-as-prop): a foreign component receives it, and an internal view
        ;; receives it iff it declares `:ref` in its header. Object refs pass
        ;; through verbatim; a `(ui/raw-fn f)` callback ref carries its fn.
        props-form (ordered-literal-object
                    (concat (map #(component-prop-pair % st) entries)
                            (when ref-a
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
        head-sym (if self? (:fqn node) (:sym node))
        ;; DEV bare-view-alias diagnostic (rf2-vxgfnd.95.15): a foreign-
        ;; classified head whose runtime VALUE is a registered view shell is a
        ;; bare `(def alias other/view)` var copy — the compiler saw a foreign
        ;; React component and dropped the view's closed-props/children checks +
        ;; manifest identity. `warn-bare-view-alias!` consults the DEV shell
        ;; marker and warns once (deduped) at render. goog.DEBUG-gated so
        ;; `:advanced` folds straight to the bare head — no residue, no cost.
        ;; View heads resolve the stamped var (classified :view) and stay quiet.
        head-form (if (= :foreign (:op node))
                    `(if ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
                       (re-frame.ui.runtime/warn-bare-view-alias! ~head-sym)
                       ~head-sym)
                    head-sym)
        spread    (get-in node [:props :spread])]
    (when self? (swap! st assoc :self-ref? true))
    (if spread
      ;; ui/spread at a FOREIGN component call site (rf2-u53yy.5): the LITERAL
      ;; part's compiled props are built as a literal object; the forwarded
      ;; runtime map is layered UNDER them (literal wins) by
      ;; `foreign-spread-props`, which passes the forwarded keys through
      ;; UNCONVERTED (a foreign boundary owns its own prop ABI). Children ride
      ;; the same jsx-spread call.
      ;;
      ;; EVALUATION ORDER: the authored order is `(ui/spread literal runtime)`,
      ;; so the literal object binds BEFORE the forwarded expression — the
      ;; single-evaluation `let` temporaries mirror the element spread branch
      ;; and fold away under :advanced.
      (let [literal-obj (ordered-literal-object
                         (concat (map #(component-prop-pair % st) entries)
                                 (when ref-a [["ref" (:form ref-a)]])))
            lit-sym     (gensym "rf-ui-lit")
            fwd-sym     (gensym "rf-ui-fwd")
            props-obj   `(let [~lit-sym ~literal-obj
                               ~fwd-sym ~(:base spread)]
                           (re-frame.ui.runtime/foreign-spread-props ~fwd-sym ~lit-sym))]
        (if (:present? key-info)
          `(re-frame.ui.runtime/jsx-spread3 ~head-form ~props-obj
                                            ~(:expr key-info)
                                            (cljs.core/array ~@chs))
          `(re-frame.ui.runtime/jsx-spread2 ~head-form ~props-obj
                                            (cljs.core/array ~@chs))))
      (jsx-runtime-call multi? head-form props-form key-info))))

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

(defn- emit-presence
  "`ui/presence` → the runtime retention boundary. The compiled keyed children
  (a `for` emits a JS array) are handed as one array; the boundary tracks them
  by React key across renders, driving the :mounting/:present/:unmounting phase
  each child reads with `(presence-phase)`."
  [node st]
  `(re-frame.ui.presence-runtime/presence-boundary
    ~(:timeout-ms node)
    (cljs.core/array ~@(children-forms st (:children node) false))))

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
                    (fn [~@params] ~(emit-node body st false))
                    ~(count params))
                  (:slot-value node))]
    `(let [~rf ~slotval]
       (when (re-frame.ui.runtime/slot-ready? ~rf)
         (re-frame.ui.runtime/check-slot-arity! ~rf ~(count (:args node)))
         (~rf ~@(:args node))))))

(defn- emit-error-boundary
  "`ui/error-boundary` → the runtime class boundary. The fallback view is the
  head component (self-recursive fallbacks target the current-ns Var, mirroring
  emit-component). When :on-error is present, capture the committed frame at the
  owning view's render and build the after-commit dispatch closure (appends the
  caught error to the authored event vector)."
  [node st]
  (let [fb        (:fallback node)
        self-fqn  (:self-fqn @st)
        self-fb?  (and (some? self-fqn) (= (:fqn fb) self-fqn))
        fb-sym    (if self-fb? (:fqn fb) (:sym fb))
        _         (when self-fb? (swap! st assoc :self-ref? true))
        reset-key (when (:has-reset-key? node) (:reset-key node))
        on-error  (:on-error node)
        child     (emit-node (:child node) st false)
        on-error-form
        (when on-error
          `(let [ops# (re-frame.ui.frames/frame-ops)]
             (fn [error#]
               ((:dispatch ops#) (conj ~on-error error#) {:source :ui}))))]
    `(re-frame.ui.runtime/error-boundary
      ~fb-sym ~reset-key ~on-error-form ~child)))

(defn emit-standalone
  "Emit a CAPABILITY-FREE AST as a self-contained CLJS render form (no ViewCell
  hook skeleton). Used by def-level constructors — re-frame.ui.react/lazy's
  fallback template. Static elements the emitter would hoist to module-level
  defs are bound in a local `let` instead (each is an immutable static React
  element, built once when the enclosing thunk closure is minted)."
  [ast]
  (let [st   (new-state 'rf-ui-standalone)
        body (emit-node ast st false)
        defs (:defs @st)]
    (if (seq defs)
      `(let [~@(mapcat (fn [d] [(nth d 1) (nth d 2)]) defs)] ~body)
      body)))

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
    :error-boundary (emit-error-boundary node st)
    :presence (emit-presence node st)
    ;; client-only (S5 phase flip, rf2-3omxp; Spec 011 §Phase flip): the site
    ;; compiles to a PHASE-CONDITIONAL runtime boundary. It renders the
    ;; capability-free fallback in `:server` phase (the JVM/SSR + first-hydration
    ;; render) and the client subtree in `:client` phase, reading a root-scoped
    ;; phase context a hydrating root flips `:server` -> `:client` in one update
    ;; after its hydration commit. Both arms therefore ride the client bundle:
    ;; the fallback template now compiles in (the ruled, perf-budget-covered cost
    ;; — the browser must materialise the fallback vdom during the hydration
    ;; render). A non-hydrating mount has no phase Provider, so the boundary reads
    ;; the `:client` default and renders the client subtree on the first render,
    ;; byte-identical to S3.
    :client-only `(re-frame.ui.runtime/client-only
                   ~(emit-node (:fallback node) st false)
                   ~(emit-node (:child node) st false))
    ;; Leading (effect …) statements spliced before the template: a `do`
    ;; sequences the lowered effect hook calls, then renders the template.
    :hook-prefix `(do ~@(:statements node) ~(emit-node (:body node) st inline?))
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
    ;; Host-faithful: native destructure lowers a defaulted slot to
    ;; `(get props :slot default)` — `get` is a function, so the default operand
    ;; is EVALUATED as part of the lookup even when the slot is present (it is an
    ;; eager third argument). Evaluate `d#` unconditionally (once), then select
    ;; it only when the slot is absent — matching the host's eval count/effects,
    ;; not just its final value.
    `(let [d# ~default
           v# (cljs.core/unchecked-get ~props-sym ~slot)]
       (if (cljs.core/undefined? v#) d# v#))
    `(cljs.core/unchecked-get ~props-sym ~slot)))

(defn header-bindings
  "The CLJS header `let` bindings, in canonical host `destructure` order
  (rf2-vxgfnd.283): `:as` binds the whole props map FIRST — matching the JVM
  native destructuring the JVM emitter uses — then ONE property-read per host
  binding UNIT. These consume `parse-header`'s `:binding-units` — the FULL host
  plan, not the collapsed derived projection — so the emission reproduces the
  host's executable semantics exactly (rf2-nedxb): every host initializer runs,
  and a qualified-group-name collision (`{:keys [ns/x] x :other}`) emits BOTH
  units binding one local, the later `:ns/x` winning by host `let` last-wins.
  The units are already the host `bes` order with winning lookup slots (a
  qualified `:keys` local reads its qualified slot), so there is nothing to sort
  or de-collide here. Emitting `:as` last, or the units in parse order, could
  resolve a dependent `:or` default to a different symbol on CLJS than on the
  JVM — including a public reactive authoring var."
  [header]
  (concat
   (when (:as-sym header)
     [(:as-sym header) `(re-frame.ui.runtime/props->map ~props-sym)])
   (mapcat (fn [{:keys [slot pattern default]}]
             [pattern (slot-read slot default)])
           (:binding-units header))))

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
  adapter source-coord walk applies to a non-DOM root.

  `emit-element` reads the marker off the plain element and stamps the props
  behind the goog.DEBUG gate (see `annotate-host-root-props`). This walk is kept
  byte-identical to `re-frame.ui.compiler.emit-jvm/mark-host-root-annotation`: a
  prop/attribute annotation is inherently cross-emitter, so the two walks MUST
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
  [{:keys [vname view-id display-name docstring header slots ast manifest
           closed-keys children? self-fqn]}]
  (let [st         (doto (new-state vname) (swap! assoc :self-fqn self-fqn))
        ;; The DEV view-evidence DOM annotation for this view's compiler-owned
        ;; host root — the source coordinate + view id, in today's attribute
        ;; vocabulary (Spec 004 §View identity). Marked onto the effective root
        ;; element and stamped behind the goog.DEBUG gate; a non-element root
        ;; carries none. Compile-time literals via the cross-host
        ;; `re-frame.source-coords` projections (parity with the adapter walks).
        annotate   {:source-coord (source-coord/format-source-coord
                                   view-id (:source manifest))
                    :view-tag     (source-coord/format-view-id view-id)}
        body       (->> (emit-node (mark-host-root-annotation ast annotate) st false)
                        (hoist-literal-sub-queries st))
        binds      (vec (header-bindings header))
        render-sym (symbol (str (name vname) "$render"))
        host-render-sym (symbol (str (name vname) "$host_render"))
        ;; The raw body is descriptor data in DEV. Stable Inner supplies the
        ;; fixed ViewCell hook skeleton to EVERY dev view, so adding the first
        ;; sub is a same-signature edit. Production specializes: a sub-bearing
        ;; view uses the host wrapper below; a sub-free view goes straight from
        ;; React.memo to the raw body with zero ViewCell hooks.
        has-subs?  (boolean (seq (:subs (:sites manifest))))
        has-locals? (boolean (seq (:locals (:sites manifest))))
        ;; A ui/dispatch-fn site reads the committed frame through the EventOwner
        ;; exactly like an :on-* handler site, so it selects an event-owner
        ;; wrapper (which also consumes the frame context for provider retargets).
        has-dispatch-fns? (boolean (seq (:dispatch-fns (:sites manifest))))
        has-events? (boolean (or (seq (:events (:sites manifest)))
                                 has-dispatch-fns?))
        ;; rf2-vxgfnd.253 (extending .228): a `(frame)` site resolves the AMBIENT
        ;; committed frame, and so does EVERY sub target (a sub site implicitly
        ;; captures the ambient frame/incarnation). So
        ;; any reactive view MUST become a real React frame-context CONSUMER or a
        ;; provider retarget (A→B) memo-bails the non-consumer child and its held
        ;; subs/ops stay locked to the old frame. The sub wrappers
        ;; therefore consume the context unconditionally — frame-ops presence no
        ;; longer forks the selection, so there are NO separate `-frame` variants.
        ;; `render-frame` covers a frame-only view (a `(frame)` site but no
        ;; sub — a context consumer with no ViewCell); a view with NONE of
        ;; the three stays on the inert direct React.memo path.
        has-frame-ops? (boolean (seq (:frame-ops (:sites manifest))))
        inner-body (if (seq binds) `(let [~@binds] ~body) body)
        ;; DEV: a local-bearing body runs under the render-phase guard so a
        ;; (local …) set!/update! invoked during render fails loud. Production
        ;; (goog.DEBUG false) emits the body directly — no flag, no thunk.
        inner      (if has-locals?
                     `(if ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
                        (re-frame.ui.hooks/with-local-render-guard
                         (fn [] ~inner-body))
                        ~inner-body)
                     inner-body)
        host-render (cond
                      (and has-subs? has-events?)
                      're-frame.ui.viewcell/render-subs-and-events
                      has-events? 're-frame.ui.viewcell/render-events
                      has-subs?  're-frame.ui.viewcell/render-subs
                      has-frame-ops? 're-frame.ui.viewcell/render-frame)
        var-meta   (cond-> {:rf.ui/view true
                            :rf.ui/view-id view-id
                            :rf.ui/children? children?}
                     docstring   (assoc :doc docstring)
                     closed-keys (assoc :rf.ui/closed-prop-keys (vec closed-keys)))]
    ;; A self-recursive view whose name collides with a same-named `:refer`
    ;; (e.g. `(defview sub …)` where the ns refers re-frame.ui/sub) must resolve
    ;; its OWN declare/def/head to the current-namespace Var, not the refer. The
    ;; CLJS analyzer already signals this shadowing on the def (its `:redef`
    ;; warning: "sub … replaced by <ns>/sub", and it adds sub to `:excludes`) —
    ;; but cljs.analyzer/resolve-var ranks `:uses` (refers) ABOVE `:defs` and
    ;; ignores `:excludes`, so a bare def name still resolves through the refer
    ;; and BOTH clobbers the public authoring Var (`re_frame.ui.sub = …`) and
    ;; leaves the qualified self head (`<ns>.sub`) undefined — the split identity.
    ;; Completing the shadowing the analyzer already intends — dropping the view's
    ;; own name from the live ns `:uses` — makes the declare, the def, and the
    ;; recursive head share the one canonical current-ns Var. JVM-only: this is
    ;; the macro-expansion path with the live compiler env; non-recursive views
    ;; and the CLJS-host golden path are untouched.
    #?(:clj
       (when (and (:self-ref? @st) self-fqn (some? cljs.env/*compiler*))
         (swap! cljs.env/*compiler* update-in
                [:cljs.analyzer/namespaces (symbol (namespace self-fqn)) :uses]
                dissoc vname)))
    `(do
       ;; A self-recursive view forward-declares its Var so the `$render` fn
       ;; (emitted before the view's own `(def …)`) may reference the
       ;; current-namespace Var by its qualified name without an undeclared-Var
       ;; warning — and so the declaration, not a same-named `:refer`, owns the
       ;; name at the reference site. Non-recursive views emit unchanged.
       ~@(when (:self-ref? @st) [`(declare ~vname)])
       ~@(:defs @st)
       ;; The `$render` / `$host_render` fns are compiler-internal implementation
       ;; helpers, never public API — `^:no-doc` so they stay off the documented /
       ;; manifest public surface (the CLJS api-manifest probe treats a fully-rowed
       ;; ns like `re-frame.ui` as exact, and a framework-authored `defview` there
       ;; would otherwise leak these two helpers as untracked public vars).
       (defn ~(vary-meta render-sym assoc :no-doc true) [~props-sym]
         ~inner)
       ~@(when host-render
           [`(defn ~(vary-meta host-render-sym assoc :no-doc true) [~props-sym]
               (~host-render
                ~view-id (fn [] (~render-sym ~props-sym))))])
       (def ~(vary-meta vname merge var-meta)
         (let [compare# ~(comparator-form header slots)]
           (if ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
             (re-frame.ui.runtime/register-view!
              ~view-id ~render-sym compare# ~display-name (quote ~manifest))
             ;; The production arm is deliberately direct React.memo. Closure
             ;; folds away the sibling registration arm and with it every HMR
             ;; slot/listener/dynamic-lookup/extra-Fiber helper. `memo-view`
             ;; carries an `@nosideeffects` JSDoc annotation, so Closure DCEs
             ;; this call whole when the view def is UNREFERENCED (G-18 facade
             ;; isolation, rf2-edpam).
             (re-frame.ui.runtime/memo-view
              ~(if host-render host-render-sym render-sym)
              compare# ~display-name))))
       ~vname)))
