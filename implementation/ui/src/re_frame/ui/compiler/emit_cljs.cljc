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
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.rules :as rules]))

(def ^:private props-sym 'rf-ui-props)
(def ^:private event-sym 'rf-ui-evt)

(defn- new-state [view-name]
  (atom {:defs [] :n 0 :handlers {} :view-name (name view-name)}))

(defn- hoist! [st kind form]
  (let [{:keys [n view-name]} @st
        sym (symbol (str view-name "$" (name kind) "$" n))]
    (swap! st #(-> % (update :n inc) (update :defs conj `(def ~sym ~form))))
    sym))

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
    (if (and (empty? flags) (nil? dyn))
      (when (seq base-str) base-str)
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

(defn- jsx-call [tag-form pairs children-forms key-info]
  (let [nch           (count children-forms)
        multi?        (> nch 1)
        children-form (cond
                        (zero? nch) nil
                        (= 1 nch)   (first children-forms)
                        :else       `(cljs.core/array ~@children-forms))
        props-form    `(cljs.core/js-obj
                        ~@(mapcat (fn [{:keys [name form]}] [name form]) pairs)
                        ~@(when (some? children-form) ["children" children-form]))
        has-key?      (:present? key-info)]
    (if has-key?
      (list (if multi? `re-frame.ui.runtime/jsxs3 `re-frame.ui.runtime/jsx3)
            tag-form props-form (:expr key-info))
      (list (if multi? `re-frame.ui.runtime/jsxs2 `re-frame.ui.runtime/jsx2)
            tag-form props-form))))

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
        props-form `(cljs.core/js-obj
                     ~@(mapcat (fn [{:keys [slot value]}] [slot value]) entries)
                     ~@(when (and ref-a (= :foreign (:op node)))
                         ["ref" (:form ref-a)])
                     ~@(when (some? children-form) ["children" children-form]))]
    (if (:present? key-info)
      `(re-frame.ui.runtime/jsx3 ~(:sym node) ~props-form ~(:expr key-info))
      `(re-frame.ui.runtime/jsx2 ~(:sym node) ~props-form))))

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
        `(fn [prev# next#]
           (and ~@(map (fn [s]
                         `(re-frame.ui.eq/rf=
                           (cljs.core/unchecked-get prev# ~s)
                           (cljs.core/unchecked-get next# ~s)))
                       slot-strs)))))))

;; ---------------------------------------------------------------------------
;; defview
;; ---------------------------------------------------------------------------

(defn emit-defview
  [{:keys [vname view-id display-name docstring header slots ast manifest
           closed-keys children?]}]
  (let [st         (new-state vname)
        body       (emit-node ast st false)
        binds      (vec (header-bindings header))
        render-sym (symbol (str (name vname) "$render"))
        var-meta   (cond-> {:rf.ui/view true
                            :rf.ui/view-id view-id
                            :rf.ui/children? children?}
                     docstring   (assoc :doc docstring)
                     closed-keys (assoc :rf.ui/closed-prop-keys (vec closed-keys)))]
    `(do
       ~@(:defs @st)
       (defn ~render-sym [~props-sym]
         ~(if (seq binds) `(let [~@binds] ~body) body))
       (def ~(vary-meta vname merge var-meta)
         (re-frame.ui.runtime/memo-view
          ~render-sym
          ~(comparator-form header slots)
          ~display-name))
       (when ~(with-meta 'js/goog.DEBUG {:tag 'boolean})
         (re-frame.ui.runtime/register-view! ~view-id ~vname (quote ~manifest)))
       ~vname)))
