(ns re-frame.ui.runtime
  "Client runtime of the compiled-view substrate — the small vocabulary
  the CLJS emitter's generated code calls. Deliberately tiny: no hiccup
  walker, tag parser, camelizer, or component-shape detector ships on the
  compiled template path (I-7). The one sanctioned runtime conversion is
  `spread->props` (`ui/spread`), which advertises its cost.

  S1 scope note (no reactivity in this slice): event handlers lower to
  fns calling `dispatch-event!`, which routes to an installable hook.
  Frame wiring (committed-frame dispatch, the sync door, per-site
  identity) lands S2/S3; until then the default hook throws loudly.
  Tests install a capture hook via `set-dispatch-hook!`."
  (:require ["react" :as react]
            ["react/jsx-runtime" :as jsxrt]
            [clojure.string :as str]
            [re-frame.registrar :as registrar]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.rules :as rules]))

;; ---------------------------------------------------------------------------
;; jsx bindings — fixed-arity defns, NOT var-bound imports: a CLJS var
;; bound to an imported JS value forces IFn-protocol dispatch at every
;; call site under :advanced; fixed-arity defns compile to direct static
;; calls Closure inlines to direct shim.jsx(...) calls (spike-measured
;; ~5% on a 20-row list render).
;; ---------------------------------------------------------------------------

(defn jsx2  [t p]   (jsxrt/jsx t p))
(defn jsx3  [t p k] (jsxrt/jsx t p k))
(defn jsxs2 [t p]   (jsxrt/jsxs t p))
(defn jsxs3 [t p k] (jsxrt/jsxs t p k))
(def Fragment jsxrt/Fragment)

;; ---------------------------------------------------------------------------
;; Memo + registration
;; ---------------------------------------------------------------------------

(defn memo-view
  "React.memo over the compiled render fn with the generated `rf=`
  comparator; displayName in dev."
  [render-fn compare-fn display-name]
  (let [c (react/memo render-fn compare-fn)]
    (when ^boolean js/goog.DEBUG
      (set! (.-displayName render-fn) display-name)
      (set! (.-displayName c) display-name))
    c))

(defn register-view!
  "Registrar `:view` entry for a compiled view (dev builds; the emitted
  call is goog.DEBUG-gated so production carries no manifests — I-12)."
  [id component manifest]
  (registrar/register! :view id (cond-> {:rf/id id
                                         :handler-fn component
                                         :rf.ui/compiled? true
                                         :rf.ui/manifest manifest}
                                  (:doc manifest) (assoc :doc (:doc manifest))))
  component)

;; ---------------------------------------------------------------------------
;; Dispatch — the S1 seam
;; ---------------------------------------------------------------------------

(defonce ^:private dispatch-hook (atom nil))

(defn set-dispatch-hook!
  "Install the event-vector consumer (tests; the S2 frame wiring replaces
  this seam). Returns the previous hook."
  [f]
  (let [prev @dispatch-hook]
    (reset! dispatch-hook f)
    prev))

(defn dispatch-event!
  "Route a (placeholder-spliced) event vector to the installed hook."
  [ev]
  (if-let [f @dispatch-hook]
    (f ev)
    (throw (ex-info
            (str "[:rf.error/ui-dispatch-unwired] compiled-view dispatch "
                 "is not wired in the S1 compiler slice — frame dispatch "
                 "lands with reactivity (S2/S3). Tests: install a hook "
                 "via re-frame.ui.runtime/set-dispatch-hook!.")
            {:rf.error/id :rf.error/ui-dispatch-unwired
             :event ev}))))

(defn dynamic-handler
  "Runtime classification of a DYNAMIC handler-position value (Spec 004
  §Handlers): vector -> dispatch; map -> options form; fn -> itself;
  nil -> no handler. Placeholders are compiled, so they are recognized in
  literal vectors only — a placeholder keyword inside a runtime vector
  dispatches as an ordinary keyword and dev warns."
  [v]
  (cond
    (nil? v) js/undefined

    (vector? v)
    (do (when ^boolean js/goog.DEBUG
          (when (some rules/placeholders v)
            (js/console.warn
             "[:rf.warning/placeholder-in-dynamic-vector] placeholder in a"
             "dynamic event vector — placeholders are compiled and only"
             "recognized in literal vectors; build the literal at the DOM"
             "site or use ui/event." (pr-str v))))
        (fn [_e] (dispatch-event! v)))

    (map? v)
    (let [{:keys [event prevent-default stop-propagation]} v]
      (when ^boolean js/goog.DEBUG
        (when (some #{:capture :passive :once} (keys v))
          (js/console.warn
           "[re-frame.ui] :capture/:passive/:once in a DYNAMIC handler map"
           "are not applied at S1 — write the options map literally at the"
           "site.")))
      (fn [e]
        (when prevent-default (.preventDefault e))
        (when stop-propagation (.stopPropagation e))
        (dispatch-event! event)))

    (fn? v) v

    :else
    (throw (ex-info
            (str "[:rf.error/ui-dispatch-unwired] a dynamic handler "
                 "expression produced an unusable value — handlers "
                 "classify by type: event vector, options map, fn, or nil")
            {:rf.error/id :rf.error/ui-dispatch-unwired :value v}))))

;; ---------------------------------------------------------------------------
;; sub — S1 stub (grammar compiles; reads land S2)
;; ---------------------------------------------------------------------------

(defn sub*
  [query]
  (throw (ex-info
          (str "[:rf.error/ui-sub-unavailable] (sub " (pr-str query) ") — "
               "reactive reads land with the S2 reactivity slice; no "
               "Stage-1 Tier-1 fixture may exercise a sub read.")
          {:rf.error/id :rf.error/ui-sub-unavailable :query query})))

(defn lease*
  [descriptor]
  (throw (ex-info
          (str "[:rf.error/ui-lease-unavailable] (lease " (pr-str descriptor)
               ") — leases land with the S2 observation slice.")
          {:rf.error/id :rf.error/ui-lease-unavailable :descriptor descriptor})))

;; ---------------------------------------------------------------------------
;; Dev checks (goog.DEBUG-stripped in production)
;; ---------------------------------------------------------------------------

(defn check-key!
  "Per-list-site dev duplicate-key check (string-coercion domain — key 1
  collides with \"1\")."
  [seen k]
  (when ^boolean js/goog.DEBUG
    (let [ks (rules/js-string-coerce k)]
      (if (unchecked-get seen ks)
        (js/console.warn
         "[:rf.error/ui-duplicate-key] duplicate key in keyed list:" (pr-str k)
         "(keys compare after React string coercion)")
        (unchecked-set seen ks true))))
  k)

(defn child
  "Dev validation of a DYNAMIC child value (the rejection roster's
  runtime arm): CLJS collections, seqs, keywords and symbols are not
  renderable — hiccup is compiled, not interpreted."
  [x]
  (when ^boolean js/goog.DEBUG
    (when (or (seq? x) (coll? x) (keyword? x) (symbol? x))
      (throw (ex-info
              (str "[:rf.error/ui-tree-malformed] a dynamic child produced "
                   (pr-str x) " — a runtime value cannot be a template. "
                   "Strings/numbers/nil/false render; markup needs a view "
                   "(or ui/raw for a host element); lists need (for ...) "
                   "with :key.")
              {:rf.error/id :rf.error/ui-tree-malformed :value x}))))
  x)

;; ---------------------------------------------------------------------------
;; Props ABI helpers (Q2/Q3)
;; ---------------------------------------------------------------------------

(defn decode-slot
  "Q3 decode D: \"children\" -> :children; split at the FIRST '/' ->
  namespaced keyword; else simple keyword. D∘E = identity for
  reader-producible keywords."
  [s]
  (if (= s "children")
    :children
    (let [i (.indexOf s "/")]
      (if (pos? i)
        (keyword (subs s 0 i) (subs s (inc i)))
        (keyword s)))))

(defn props->map
  "`:as` materialization (Q2): a CLJS persistent map of ALL present slots,
  decoded. Documented dev cost — prefer named slots."
  [props]
  (if (nil? props)
    {}
    (persistent!
     (reduce (fn [m k] (assoc! m (decode-slot k) (unchecked-get props k)))
             (transient {})
             (js/Object.keys props)))))

(defn props-equal-generic?
  "Generic `:as` comparator: rf= over the UNION of slots on both props
  objects (absent reads undefined, which rf= treats as nil — so
  absent-vs-present-nil compares equal, Q2 consequence)."
  [a b]
  (let [ka (js/Object.keys a)
        kb (js/Object.keys b)
        check (fn [ks]
                (loop [i 0]
                  (if (< i (.-length ks))
                    (let [k (aget ks i)]
                      (if (eq/rf= (unchecked-get a k) (unchecked-get b k))
                        (recur (inc i))
                        false))
                    true)))]
    (and ^boolean (check ka)
         (or (= (.-length ka) (.-length kb))
             ^boolean (check kb)))))

;; ---------------------------------------------------------------------------
;; Dynamic-value conversion (the emitted runtime half of the rule table)
;; ---------------------------------------------------------------------------

(defn style-val
  "DYNAMIC style value on the client: keywords stringify; numbers pass
  raw (React itself applies the px/unitless rule at render)."
  [v]
  (if (keyword? v) (name v) v))

(defn attr-val
  "DYNAMIC attr value on the client: keywords/symbols stringify (prop
  conversion is total on the DOM path); everything else passes through
  (React applies its own attr rules)."
  [v]
  (if (or (keyword? v) (symbol? v)) (name v) v))

(defn style-obj
  "Wholly-dynamic :style map -> React style object (kebab -> camel names;
  custom properties verbatim)."
  [m]
  (let [o (js-obj)]
    (doseq [[k v] m]
      (when (some? v)
        (unchecked-set o (rules/react-style-name (name k)) (style-val v))))
    o))

;; ---------------------------------------------------------------------------
;; ui/spread — the ONE generic runtime prop-map conversion
;; ---------------------------------------------------------------------------

(defn spread->props
  "Runtime conversion of author-space prop maps for a DOM/custom element
  (`ui/spread base overrides`): the same rule table as compiled props —
  :class composition (`.sugar` classes first), :style px/name rules,
  on-* runtime handler classification, custom-element property
  classification by `tag`. `:key` inside a spread is structural and
  ignored (dev warns)."
  [tag sugar-classes base overrides]
  (let [m (merge base overrides)
        property? (rules/custom-element-properties (keyword tag))
        o (js-obj)]
    (when (and (some? sugar-classes) (not (contains? m :class)))
      (unchecked-set o "className" sugar-classes))
    (doseq [[k v] m]
      (let [n (name k)]
        (cond
          (= k :key)
          (when ^boolean js/goog.DEBUG
            (js/console.warn "[re-frame.ui] :key inside (ui/spread ...) is"
                             "ignored — keys are structural and must be"
                             "literal at the site."))

          (= k :ref)
          (when ^boolean js/goog.DEBUG
            (js/console.warn "[re-frame.ui] :ref inside (ui/spread ...) is"
                             "not applied at S1 (refs land S3)."))

          (nil? v) nil

          (= k :class)
          (when-some [c (rules/classes-str [sugar-classes (rules/class-val v)])]
            (unchecked-set o "className" c))

          (= k :style)
          (unchecked-set o "style" (style-obj v))

          (str/starts-with? n "on-")
          (unchecked-set o (rules/react-event-name n false)
                         (dynamic-handler v))

          (property? k)
          (unchecked-set o (rules/custom-element-property-name n) v)

          :else
          (unchecked-set o (rules/react-prop-name n) (attr-val v)))))
    o))

(defn jsx-spread2
  "jsx over a runtime-built (spread) props object, children attached."
  [t props-obj children-arr]
  (case (.-length children-arr)
    0 (jsxrt/jsx t props-obj)
    1 (do (unchecked-set props-obj "children" (aget children-arr 0))
          (jsxrt/jsx t props-obj))
    (do (unchecked-set props-obj "children" children-arr)
        (jsxrt/jsxs t props-obj))))

(defn jsx-spread3
  [t props-obj k children-arr]
  (case (.-length children-arr)
    0 (jsxrt/jsx t props-obj k)
    1 (do (unchecked-set props-obj "children" (aget children-arr 0))
          (jsxrt/jsx t props-obj k))
    (do (unchecked-set props-obj "children" children-arr)
        (jsxrt/jsxs t props-obj k))))
