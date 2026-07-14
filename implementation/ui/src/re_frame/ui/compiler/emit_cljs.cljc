(ns re-frame.ui.compiler.emit-cljs
  "AST -> direct react/jsx-runtime CLJS forms (the client emitter).

  - jsx/jsxs calls with compile-time-converted quoted prop names
    (cljs.core/js-obj with literal string keys — safe under :advanced);
  - maximal fully-static subtrees hoist to module constants;
  - fully-static props/style objects on otherwise-dynamic elements hoist;
  - capture-free literal event vectors hoist to one module-level callback,
    deduped by event form; placeholders (:rf.ui/value ...) splice at
    compile time;
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
(def ^:private event-sym 'rf-ui-evt)

(defn- new-state [view-name]
  (atom {:defs [] :n 0 :handlers {} :sub-queries {}
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
  (walk/postwalk
   (fn [x]
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
   form))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- placeholder-form [x]
  (case x
    :rf.ui/value   `(cljs.core/unchecked-get
                     (cljs.core/unchecked-get ~event-sym "target") "value")
    :rf.ui/checked `(cljs.core/unchecked-get
                     (cljs.core/unchecked-get ~event-sym "target") "checked")
    :rf.ui/key     `(cljs.core/unchecked-get ~event-sym "key")
    x))

(defn- vector-handler-form [ev-vec {:keys [prevent? stop?]}]
  `(fn [~event-sym]
     ~@(when prevent? [`(.preventDefault ~event-sym)])
     ~@(when stop? [`(.stopPropagation ~event-sym)])
     (re-frame.ui.runtime/dispatch-event! [~@(map placeholder-form ev-vec)])))

(defn- handler-form [st h]
  (case (:classification h)
    :vector
    (let [f (vector-handler-form (:form h) {})]
      (if (:hoistable? h)
        (or (get-in @st [:handlers (:form h)])
            (let [sym (hoist! st :handler f)]
              (swap! st assoc-in [:handlers (:form h)] sym)
              sym))
        f))

    :options
    (let [{:keys [event prevent-default stop-propagation]} (:form h)
          f (vector-handler-form event {:prevent? prevent-default
                                        :stop? stop-propagation})]
      (if (:hoistable? h)
        (or (get-in @st [:handlers (:form h)])
            (let [sym (hoist! st :handler f)]
              (swap! st assoc-in [:handlers (:form h)] sym)
              sym))
        f))

    :fn      (:form h)
    :dynamic `(re-frame.ui.runtime/dynamic-handler ~(:form h))))

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
        event-ps (map (fn [h]
                        {:name (rules/react-event-name (:name h) (:capture? h))
                         :form (handler-form st h)
                         :static? (:hoistable? h)})
                      (:events props))
        style-p (when (:style props)
                  [{:name "style"
                    :form (style-form st (:style props) inner-inline?)
                    :static? (:static? (:style props))}])
        ref-p   (when (:ref props)
                  [{:name "ref" :form (:form (:ref props)) :static? false}])
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
    (if-let [spread (get-in node [:props :spread])]
      (let [sugar (get-in node [:props :class :base-str])
            props-form `(re-frame.ui.runtime/spread->props
                         ~tag-str
                         ~(when (seq sugar) sugar)
                         ~(:base spread)
                         ~(:overrides spread))
            chs (children-forms st (:children node) inner-inline?)]
        ;; spread props objects are runtime-built; children ride the same call
        (if (:present? key-info)
          `(re-frame.ui.runtime/jsx-spread3 ~tag-str ~props-form
                                            ~(:expr key-info)
                                            (cljs.core/array ~@chs))
          `(re-frame.ui.runtime/jsx-spread2 ~tag-str ~props-form
                                            (cljs.core/array ~@chs))))
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
                    (concat (map (fn [{:keys [slot value]}] [slot value]) entries)
                            (when (and ref-a (= :foreign (:op node)))
                              [["ref" (:form ref-a)]])
                            (when (some? children-form)
                              [["children" children-form]])))
        multi?   (> nch 1)]
    (jsx-runtime-call multi? (:sym node) props-form key-info)))

(defn- row-key-expr [body]
  (or (get-in body [:props :key :expr]) (get-in body [:key :expr])))

(defn- emit-for [node st]
  (let [arr  (gensym "rf-ui-arr")
        seen (gensym "rf-ui-seen")
        row  (emit-node (:body node) st false)
        kexpr (row-key-expr (:body node))]
    `(let [~arr (cljs.core/array)
           ~seen (cljs.core/js-obj)]
       (doseq [~@(:seq-exprs node)]
         ;; dev-only duplicate-key check; key exprs are pure — the
         ;; double evaluation exists in dev builds only
         (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
           (re-frame.ui.runtime/check-key! ~seen ~kexpr))
         (.push ~arr ~row))
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

(defn header-bindings [header]
  (concat
   (mapcat (fn [{:keys [slot pattern default]}]
             [pattern (slot-read slot default)])
           (:entries header))
   (when (:as-sym header)
     [(:as-sym header) `(re-frame.ui.runtime/props->map ~props-sym)])))

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
           closed-keys children? lease-declarations]}]
  (let [st         (new-state vname)
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
