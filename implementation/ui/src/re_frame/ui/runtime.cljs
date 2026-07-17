(ns re-frame.ui.runtime
  "Client runtime of the compiled-view substrate — the small vocabulary
  the CLJS emitter's generated code calls. Deliberately tiny: no hiccup
  walker, tag parser, camelizer, or component-shape detector ships on the
  compiled template path (I-7). The one sanctioned runtime conversion is
  `spread->props` (`ui/spread`), which advertises its cost.

  Event ownership lives in `re-frame.ui.events`: generated callbacks are
  stable per mounted site and read only the winning committed frame/template.
  This namespace contains no process-global dispatch hook."
  (:require ["react" :as react]
            ["react/jsx-runtime" :as jsxrt]
            [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.events :as events]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.viewcell :as viewcell]))

;; ---------------------------------------------------------------------------
;; jsx binding.  Generated templates invoke `.jsx` / `.jsxs` on this module
;; object directly.  Binding the imported FUNCTIONS as CLJS vars would force
;; IFn-protocol dispatch; retaining the module object lets the emitter invoke
;; the property unbound with React's exact 2/3-argument shape.
;; The fixed-arity helpers remain for handwritten/runtime call sites.
;; ---------------------------------------------------------------------------

(def ^js jsx-runtime jsxrt)
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
  comparator. This is the direct production path: no shell, slot, listener,
  dynamic render lookup, or extra Fiber."
  [render-fn compare-fn display-name]
  (let [c (react/memo render-fn compare-fn)]
    (when ^boolean js/goog.DEBUG
      (set! (.-displayName render-fn) display-name)
      (set! (.-displayName c) display-name))
    c))

(defn- make-view-shells
  "Create the two stable DEV component identities for `id`.

  Outer owns the minimal body-revision useSyncExternalStore subscription and
  delegates prop comparison to the currently-published descriptor. Inner calls
  the current render function as an ORDINARY function, keeping authored hooks
  on Inner's stable Fiber. Only its key changes on hook-signature
  incompatibility."
  [id]
  (let [subscribe (fn [listener]
                    (reactive/subscribe-view! id listener))
        snapshot  (fn [] (reactive/view-generation id))
        inner     (fn stable-view-body [props]
                    ;; The DEV fixed hook skeleton is present from the first
                    ;; render, even while the body has no sub sites. Adding the
                    ;; first `sub` is therefore a same-signature body edit, not
                    ;; a hook-order change. Production specializes sub-free
                    ;; views to the raw render fn in the emitter.
                    (viewcell/render-dev
                     id
                     (fn []
                       (let [render-fn (:render-fn
                                       (reactive/view-descriptor id))]
                         (render-fn props)))))
        outer-fn  (fn stable-view-shell [props]
                    (react/useSyncExternalStore subscribe snapshot snapshot)
                    ;; React dev freezes the received props object. jsxDEV adds
                    ;; key diagnostics to the object it receives, so give the
                    ;; dev-only inner Fiber a shallow carrier of its own.
                    (jsx3 inner (js/Object.assign (js-obj) props)
                          (reactive/view-remount-generation id)))
        compare   (fn [prev next]
                    (let [compare-fn (:compare-fn
                                      (reactive/view-descriptor id))]
                      (compare-fn prev next)))
        outer     (react/memo outer-fn compare)]
    {:outer outer :inner inner}))

(defn register-view!
  "Register a compiled view in DEV and return its stable public shell.

  Descriptor/revisions are PREPARED before registrar publication so the
  registrar's synchronous hooks can never observe a new manifest paired with
  an old (or nil) render/comparator. Mounted shells are notified only after
  registrar success. A registrar throw compensates monotonically: the prior
  descriptor (or an unavailable first-load tombstone) is republished at a fresh
  revision and mounted shells are notified once, so no provisional render can
  commit or strand a cell on a revision that moved backwards. Publication
  listener failures are contained by the reactive authority after every
  snapshotted sibling runs; because commit is outside the registrar catch, an
  observer can never masquerade as registrar failure or trigger rollback."
  [id render-fn compare-fn display-name manifest]
  (let [{:keys [outer inner]}
        (reactive/ensure-view-shells! id #(make-view-shells id))
        descriptor {:render-fn render-fn
                    :compare-fn compare-fn
                    :manifest manifest}
        old-inner-name (.-displayName inner)
        old-outer-name (.-displayName outer)]
    (set! (.-displayName render-fn) display-name)
    (set! (.-displayName inner) (str display-name "$Body"))
    (set! (.-displayName outer) display-name)
    (let [publication
          (reactive/prepare-view-descriptor!
           id (:hook-signature manifest) descriptor)]
      (try
        (registrar/register!
         :view id
         (cond-> {:rf/id id
                  :handler-fn outer
                  :rf.ui/compiled? true
                  :rf.ui/manifest manifest}
           (:doc manifest) (assoc :doc (:doc manifest))))
        (catch :default e
          ;; A registrar hook may have synchronously published a newer winner.
          ;; Restore diagnostic names only when this transaction also restored
          ;; its descriptor; a stale failure must not clobber the winner.
          (when (reactive/rollback-view-descriptor! publication)
            (set! (.-displayName inner) old-inner-name)
            (set! (.-displayName outer) old-outer-name))
          (throw e)))
      (reactive/commit-view-descriptor! publication)
      outer)))

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

(defn assert-object-ref!
  "Development assertion for an unmarked dynamic :ref value. Callback refs
  are commit-phase functions and must be explicitly authored with ui/raw-fn;
  this check never invokes or mutates the supplied value."
  [ref-value]
  (when (fn? ref-value)
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/render
     (str "a dynamic :ref produced a function, but callback refs must be "
          "explicit: (ui/raw-fn f); object refs are preferred")
     {:extra {:value ref-value :kind :unmarked-callback-ref}}))
  ref-value)

(defn child
  "Dev validation of a DYNAMIC child value (the rejection roster's
  runtime arm): CLJS collections, seqs, keywords and symbols are not
  renderable — hiccup is compiled, not interpreted."
  [x]
  (when ^boolean js/goog.DEBUG
    (when (or (seq? x) (coll? x) (keyword? x) (symbol? x))
      (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui/render
       (str "a dynamic child produced " (pr-str x) " — a runtime value "
            "cannot be a template. Strings/numbers/nil/false render; markup "
            "needs a view (or ui/raw for a host element); lists need "
            "(for ...) with :key")
       {:extra {:value x}})))
  x)

;; ---------------------------------------------------------------------------
;; Compiled render slots — `ui/render-fn` + `ui/slot`
;;
;; A `ui/render-fn` value is a compiled pure-render callback: the CONSUMER
;; authors its body lexically at the call site, so both emitters COMPILE it
;; (closed grammar, no runtime hiccup). The value is the raw closure marked
;; with a string property so `ui/slot` can prove, at a library seam, that a
;; prop-carried value is a render-fn (or nil) before invoking it. The property
;; is a literal string key, so it survives `:advanced` renaming, and it is set
;; unconditionally (render slots are core, not a DEV-only affordance).
;; ---------------------------------------------------------------------------

(def ^:private render-fn-mark "rf$render_fn")

(defn render-fn
  "Mark a compiled callback closure as a `ui/render-fn` value and return it.
  The value is directly invocable; `slot` gates every invocation."
  [f]
  (unchecked-set f render-fn-mark true)
  f)

(defn render-fn?
  "True when `x` is a compiled `ui/render-fn` value."
  [x]
  (and (fn? x) (true? (unchecked-get x render-fn-mark))))

(defn invalid-slot!
  "The didactic error for a `ui/slot` value that is neither `nil` nor a
  `ui/render-fn`."
  [x]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/slot
   (str "a ui/slot received " (pr-str x) " — a slot accepts only a "
        "ui/render-fn value (author it as (ui/render-fn [args…] template)) "
        "or nil (renders nothing). Ordinary function props are opaque "
        "identity-compared values and are never invoked as slots")
   {:extra {:value x}}))

(defn slot-ready?
  "Gate a `ui/slot` value: `nil` renders nothing (false); a `ui/render-fn`
  renders (true); anything else is the didactic `invalid-slot!` error. Keeps
  the slot invocation fixed-arity at the call site — the args evaluate only
  when the slot renders."
  [x]
  (cond
    (nil? x)        false
    (render-fn? x)  true
    :else           (invalid-slot! x)))

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

(defn- convert-prop-map!
  "Convert an author-space prop map `m` into the props object `o` via the one
  rule table (shared by `spread->props` and `spread-safe->props`): :class
  composes (`sugar-classes` first when supplied), :style px/name rules, on-*
  runtime handler classification, custom-element property classification by
  `tag`, all other names through the attr conversion table. Returns `o`."
  [o tag sugar-classes m event-site-key debug-site]
  (let [property? (rules/custom-element-properties (keyword tag))]
    (when (and (some? sugar-classes) (not (contains? m :class)))
      (unchecked-set o "className" sugar-classes))
    (doseq [[k v] m]
      (let [n (name k)]
        (cond
          (= k :key)
          (when ^boolean js/goog.DEBUG
            (js/console.warn "[re-frame.ui] :key inside a spread form is"
                             "ignored — keys are structural and must be"
                             "literal at the site."))

          (= k :ref)
          (when ^boolean js/goog.DEBUG
            (js/console.warn "[re-frame.ui] :ref inside a spread form is"
                             "not applied at S1 (refs land S3)."))

          (nil? v) nil

          (= k :class)
          (when-some [c (rules/classes-str [sugar-classes (rules/class-val v)])]
            (unchecked-set o "className" c))

          (= k :style)
          (unchecked-set o "style" (style-obj v))

          (str/starts-with? n "on-")
          (unchecked-set o (rules/react-event-name n false)
                         (events/dynamic-handler
                          [event-site-key n]
                          v
                          (when interop/debug-enabled?
                            (assoc debug-site :prop k))))

          (property? k)
          (unchecked-set o (rules/custom-element-property-name n) v)

          :else
          (unchecked-set o (rules/react-prop-name n) (attr-val v)))))
    o))

(defn spread->props
  "Runtime conversion of author-space prop maps for a DOM/custom element
  (`ui/spread base overrides`): the same rule table as compiled props —
  :class composition (`.sugar` classes first), :style px/name rules,
  on-* runtime handler classification, custom-element property
  classification by `tag`. `:key` inside a spread is structural and
  ignored (dev warns)."
  [tag sugar-classes base overrides event-site-key debug-site]
  (let [m (merge base overrides)]
    (when interop/debug-enabled?
      (when (and (or (contains? m :value) (contains? m :checked))
                 (some #(contains? m %)
                       [:on-input :on-change :on-before-input]))
        (js/console.warn
         "[re-frame.ui] a controlled :value/:checked and input handler came "
         "through ui/spread. The controlled-input synchronous door requires "
         "literal co-present props plus a literal event vector; this site "
         "uses ordinary drain batching.")))
    (convert-prop-map! (js-obj) tag sugar-classes m event-site-key debug-site)))

;; ---------------------------------------------------------------------------
;; ui/spread-safe — the literal safe-spread policy runtime (owned-key deny in
;; EVERY build; the controlled owned site keeps its compiled sync-door handler)
;; ---------------------------------------------------------------------------

(defn spread-safe->props
  "Build the CALLER attr object of a `(ui/spread-safe owned caller)` element.
  `caller` is guarded FIRST by `rules/assert-safe-caller!` — a denied key (the
  structural/controlled/identity set plus the component's `owned-handler-keys`)
  throws in EVERY build (not goog.DEBUG-gated) — then converts through the one
  rule table (allowed on-* classify via the handler decision table; aria-*/
  data-*/title/:class/:style per the conversion table). Owned props ride their
  own compiled object and are layered on top by `spread-safe-props`, so this
  object carries NO sugar; :class here is the caller's alone."
  [tag caller owned-handler-keys event-site-key debug-site]
  (rules/assert-safe-caller! caller owned-handler-keys)
  (convert-prop-map! (js-obj) tag nil caller event-site-key debug-site))

(defn spread-safe-props
  "Merge a `ui/spread-safe` element's CALLER object (`caller-obj`) and its
  compiled OWNED object (`owned-obj`) into the final props object: owned props
  WIN every collision (the caller can carry no owned/structural key — the guard
  denied them), and `className` composes with the owned classes first. Returns
  the merged object."
  [caller-obj owned-obj]
  (let [caller-class (unchecked-get caller-obj "className")
        owned-class  (unchecked-get owned-obj "className")]
    (js/Object.assign caller-obj owned-obj)
    (when (and (some? caller-class) (some? owned-class))
      (unchecked-set caller-obj "className" (str owned-class " " caller-class)))
    caller-obj))

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

;; ---------------------------------------------------------------------------
;; ui/error-boundary — the explicit error component (S3)
;;
;; React error boundaries are class-only: getDerivedStateFromError /
;; componentDidCatch have NO hook equivalent, so this is the ONE class in the
;; compiled-view runtime. It catches render/lifecycle throws BELOW it (React
;; does not catch event-handler or async errors — those keep their own typed
;; paths); renders the fallback view with `:error` on catch; dispatches
;; `:on-error` AFTER the failing commit (componentDidCatch is a post-commit
;; lifecycle) through the frame the owning view captured at render; and clears
;; the caught error when `:reset-key` changes by `rf=` (retry = a state change
;; that changes the key). The fallback is a plain view — recursive dispatch
;; from it is the author's own loop to avoid (no runtime block, per Spec 004:
;; the recovery protocol is reset-key + fallback, nothing else).
;; ---------------------------------------------------------------------------

(defn- error-boundary-render [^js this]
  (let [props (.-props this)
        state (.-state this)]
    (if (some? (.-error state))
      (jsx2 (.-fallback props) #js {:error (.-error state)})
      (.-children props))))

(def ^js ErrorBoundary
  (let [ctor  (fn ErrorBoundary [^js props]
                (this-as this
                  (.call react/Component this props)
                  (set! (.-state this) #js {:error nil :resetKey (.-resetKey props)})
                  this))
        proto (js/Object.create (.-prototype react/Component))]
    (set! (.-prototype ctor) proto)
    (set! (.-constructor proto) ctor)
    ;; getDerivedStateFromError — the render-phase state transition into the
    ;; caught state; the fallback renders on the very next commit.
    (set! (.-getDerivedStateFromError ctor)
          (fn [error] #js {:error error}))
    ;; getDerivedStateFromProps — a changed :reset-key clears the caught error
    ;; (rf= so structural keys retry once, value-equal keys do not).
    (set! (.-getDerivedStateFromProps ctor)
          (fn [^js props ^js state]
            (when-not (eq/rf= (.-resetKey props) (.-resetKey state))
              #js {:error nil :resetKey (.-resetKey props)})))
    ;; componentDidCatch — post-commit; dispatch :on-error through the captured
    ;; live frame (never during render, I-1). The closure the emitter passes
    ;; appends the caught error to the authored event vector.
    (set! (.-componentDidCatch proto)
          (fn [error _info]
            (this-as this
              (when-some [on-error (.-onError ^js (.-props this))]
                (on-error error)))))
    (set! (.-render proto) (fn [] (this-as this (error-boundary-render this))))
    (when ^boolean js/goog.DEBUG
      (set! (.-displayName ctor) "rf.ui/error-boundary"))
    ctor))

(defn error-boundary
  "Build a `ui/error-boundary` element. `fallback` is the fallback view
  (rendered with `:error` on catch), `reset-key` clears the caught error when
  it changes (rf=), `on-error` (or nil) is the after-commit dispatch closure the
  emitter captured against the owning view's frame, and `child` is the guarded
  subtree."
  [fallback reset-key on-error child]
  (jsx2 ErrorBoundary
        #js {:fallback fallback :resetKey reset-key
             :onError on-error :children child}))
