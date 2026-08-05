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
            [re-frame.trace :as trace :include-macros true]
            [re-frame.ui.eq :as eq]
            [re-frame.ui.events :as events]
            ;; The outward bridge `->react-component` scopes a supplied frame
            ;; through `frames/->react-scope-element` (rf2-01rwd). `frames` is
            ;; already a transitive dep (runtime -> events -> frames) and never
            ;; requires runtime, so the direct require is acyclic (rf2-u53yy.2).
            [re-frame.ui.frames :as frames]
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
;; DEV bare-view-alias diagnostic (rf2-vxgfnd.95.15, EP-0035 / rf2-ho1iba)
;;
;; `(def alias other/view)` copies the shell VALUE but not the var's
;; `:rf.ui/view` metadata (def never copies var meta), so the compiler
;; classifies the aliased head as a FOREIGN React component and silently drops
;; the view's closed-props / children compile-time checks and its manifest view
;; identity (Spec 004 Q5, env docstring §var-copies). The runtime VALUE at that
;; head is still the registered view SHELL, which `register-view!` stamps with
;; `view-shell-mark`; a foreign head consults the marker (DEV only, emitter-
;; gated) and warns ONCE per aliased view.
;;
;; This is a `:rf.ui.compile/*` COMPILE-TIER diagnostic (no Spec 009 catalogue
;; row; delivered by console.warn, never `error/throw-error!`). Every arm below
;; is referenced only from goog.DEBUG-gated emitted code (the emitter's foreign
;; head) or the DEV-only `register-view!`, so `:advanced` + goog.DEBUG=false
;; elides the marker set, the dedup set, and this warning entirely — no
;; production cost.
;; ---------------------------------------------------------------------------

(def ^:private view-shell-mark "rf$view_shell")

;; Process-lifetime one-shot dedup keyed by view id (see
;; `clear-bare-view-alias-warned!`, used by the diagnostic's test fixtures).
(def ^:private bare-view-alias-warned (js/Set.))

(defn clear-bare-view-alias-warned!
  "Reset the one-shot bare-view-alias diagnostic cache so a test fixture starts
  from a clean slate. DEV/test only."
  []
  (.clear bare-view-alias-warned))

(defn warn-bare-view-alias!
  "DEV diagnostic (`:rf.ui.compile/bare-view-alias`) for a foreign-classified
  component head whose runtime VALUE is a registered re-frame.ui view shell —
  i.e. a bare `(def alias other/view)` var copy, which drops the view's
  closed-props / children compile-time checks and its manifest view identity.
  Warns ONCE per aliased view (deduplicated by view id) and returns `head`
  unchanged; genuine foreign components, plain function props, and canonical /
  namespace-aliased view heads (which resolve the stamped var and never reach
  here) stay quiet. Referenced only from the emitter's goog.DEBUG-gated foreign
  head, so `:advanced` + goog.DEBUG=false elides it."
  [head]
  (when (some? head)
    (when-some [id (unchecked-get head view-shell-mark)]
      (when-not (.has bare-view-alias-warned id)
        (.add bare-view-alias-warned id)
        (js/console.warn
         (str "[re-frame.ui] the component head " (pr-str id) " is a bare var "
              "alias of a registered view. `(def alias other/view)` copies the "
              "value but NOT the view metadata, so the compiler treated it as a "
              "foreign React component and dropped this view's closed-props and "
              "children compile-time checks and its manifest view identity. "
              "Refer to the view by its canonical name, or alias the NAMESPACE "
              "(:require [... :as x] / :refer) so the head resolves the stamped "
              "view var. [:rf.ui.compile/bare-view-alias]")))))
  head)

;; ---------------------------------------------------------------------------
;; Memo + registration
;; ---------------------------------------------------------------------------

(defn ^{:jsdoc ["@nosideeffects"]} memo-view
  "React.memo over the compiled render fn with the generated `rf=`
  comparator. This is the direct production path: no shell, slot, listener,
  dynamic render lookup, or extra Fiber.

  The `@nosideeffects` JSDoc annotation (Closure-native, always honoured — it
  rides the type-info JSDoc channel Closure preserves) promises that building a
  memoized component is side-effect-free. Under `:advanced` + goog.DEBUG=false a
  production view def folds to exactly this call (the emitter's direct-React.memo
  arm; the DEV `displayName` writes below are goog.DEBUG-stripped), so the
  annotation lets Closure dead-code-eliminate an UNREFERENCED view def whole —
  render fn and sentinel strings included. Without it Closure keeps the call as a
  retained expression statement and a single-view import of a multi-view library
  namespace drags in every unimported sibling (G-18 facade isolation, rf2-edpam).
  The promise is honest where it matters: `memo-view` is reached ONLY on the
  goog.DEBUG=false production arm, so no build both runs the `displayName` writes
  and trusts this annotation."
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
    ;; Stamp the DEV shell so a foreign-classified head whose runtime value is
    ;; this shell (a bare `(def alias other/view)` var copy) is caught by
    ;; `warn-bare-view-alias!`. register-view! is DEV-only (the emitter's
    ;; production arm is direct React.memo), so the marker DCEs in production.
    (unchecked-set outer view-shell-mark id)
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
         ;; rf2-q9q9y — `k` is a caller-supplied `:key` and reaches here only
         ;; AFTER `js-string-coerce` (which is `(str v)`, bounded on a foreign
         ;; value), so two cyclic keys collide at the same coerced string and
         ;; arrive RAW at this warning. `error/pr-form` keeps the crossing
         ;; total; a bare `pr-str` overflowed on the way into `console.warn`.
         "[:rf.error/ui-duplicate-key] duplicate key in keyed list:" (error/pr-form k)
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
       ;; rf2-q9q9y — the guard admits only CLJS collections/seqs/keywords/
       ;; symbols, so a raw foreign object never lands here; a collection
       ;; HOLDING one does, and `[:p ctx.Provider]` is exactly that mixed
       ;; chain. Message half `error/pr-form`, ex-data half `error/safe-form`.
       (str "a dynamic child produced " (error/pr-form x) " — a runtime value "
            "cannot be a template. Strings/numbers/nil/false render; markup "
            "needs a view (or ui/raw for a host element); lists need "
            "(for ...) with :key")
       {:extra (error/safe-form {:value x})})))
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
(def ^:private render-fn-arity-mark "rf$render_fn_arity")

(defn render-fn
  "Mark a compiled callback closure as a `ui/render-fn` value, recording its
  fixed `arity` (the declared parameter count), and return it. The value is
  directly invocable; `slot` gates every invocation and `check-slot-arity!`
  enforces the arity host-identically before it fires."
  [f arity]
  (unchecked-set f render-fn-mark true)
  (unchecked-set f render-fn-arity-mark arity)
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
   ;; rf2-q9q9y — `slot-ready?` routes EVERYTHING that is neither nil nor a
   ;; marked render-fn here, so a React context provider (an ordinary
   ;; authoring slip) arrives raw. Message half `error/pr-form`, ex-data half
   ;; `error/safe-form`.
   (str "a ui/slot received " (error/pr-form x) " — a slot accepts only a "
        "ui/render-fn value (author it as (ui/render-fn [args…] template)) "
        "or nil (renders nothing). Ordinary function props are opaque "
        "identity-compared values and are never invoked as slots")
   {:extra (error/safe-form {:value x})}))

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

(defn check-slot-arity!
  "Enforce the host-independent ui/slot ↔ ui/render-fn arity contract BEFORE
  invocation: a slot passing `argc` runtime args to a render-fn compiled with a
  different fixed arity is the didactic `:rf.error/ui-tree-malformed` (the arity
  twin of `invalid-slot!`) on BOTH hosts — neither leans on the native call
  quirk (JS silently drops surplus args; the JVM throws a raw ArityException)."
  [rf argc]
  (let [arity (unchecked-get rf render-fn-arity-mark)]
    (when (not= arity argc)
      (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui/slot
       (str "a ui/slot passed " argc " argument" (when (not= 1 argc) "s")
            " to a ui/render-fn that declares " arity " parameter"
            (when (not= 1 arity) "s")
            " — a render-fn is a fixed-arity callback; the slot must pass exactly "
            "its declared parameters. Match the slot's argument count to the "
            "render-fn's parameter vector")
       {:extra {:expected arity :actual argc}}))))

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
  `tag`, all other names through the attr conversion table. Returns `o`.

  Every key is FIRST put through `rules/assert-spread-prop-key!` — the runtime
  half of the analyzer's literal rejected-spelling deny, which cannot see a map
  built at runtime (rf2-5pr75). It runs BEFORE the cond (so no later branch —
  including the custom-element property branch — can route around it) and in
  EVERY build (so production is never less safe than dev)."
  [o tag sugar-classes m event-site-key debug-site]
  (let [property? (rules/custom-element-properties (keyword tag))]
    (when (and (some? sugar-classes) (not (contains? m :class)))
      (unchecked-set o "className" sugar-classes))
    (doseq [[k v] m]
      (rules/assert-spread-prop-key! k)
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
  ;; assert-safe-caller! DENIES by canonical emitted slot AND returns the
  ;; canonicalized caller (each accepted key rewritten to its author keyword), so
  ;; convert lands every alternate spelling in the SAME slot as the literal
  ;; author keyword — the emitted prop set matches the classification (rf2-xdvob).
  (let [caller (rules/assert-safe-caller! caller owned-handler-keys)]
    (convert-prop-map! (js-obj) tag nil caller event-site-key debug-site)))

(defn spread-safe-props
  "Merge a `ui/spread-safe` element's CALLER object (`caller-obj`) and its
  compiled OWNED object (`owned-obj`) into the final props object: owned props
  WIN every collision (the caller can carry no owned/structural key — the guard
  denied them), and `className` composes with the owned classes first. Returns
  the merged object.

  NIL-MEANS-ABSENT (rf2-j4len): a compiled owned DYNAMIC prop whose value
  normalizes to nil sits in `owned-obj` as an explicit null (the owned object
  is a literal built once, so a nil `attr-val`/`class-val` still materializes
  the key). Layer owned OVER caller KEY-BY-KEY, skipping any nil owned value, so
  a nil owned prop stays ABSENT and the caller's value survives — matching the
  JVM `tree/element`, which drops nil owned :norm/:dyn before layering the
  caller attrs. `js/Object.assign` would copy the explicit null and erase the
  caller's value (the divergence). General for every dynamic owned prop, not a
  class-only patch; class then composes owned-first when both are non-nil."
  [caller-obj owned-obj]
  (let [caller-class (unchecked-get caller-obj "className")]
    (doseq [k (js/Object.keys owned-obj)]
      (let [v (unchecked-get owned-obj k)]
        (when (some? v)
          (unchecked-set caller-obj k v))))
    (let [owned-class (unchecked-get owned-obj "className")]
      (when (and (some? caller-class) (some? owned-class))
        (unchecked-set caller-obj "className" (str owned-class " " caller-class))))
    caller-obj))

;; ---------------------------------------------------------------------------
;; ui/spread at a FOREIGN component call site (rf2-u53yy.5)
;; ---------------------------------------------------------------------------

(defn- set-own!
  "Set `k`→`v` as an OWN enumerable data property on the plain object `o`. Routes
  the one magic key `\"__proto__\"` through `defineProperty`: a bare
  `o[\"__proto__\"] = v` on an Object.prototype-backed object invokes the prototype
  SETTER (mutating the chain) instead of defining an own property. The
  `defineProperty` data descriptor writes the verbatim own prop and leaves the
  output's prototype untouched — the runtime counterpart of the compiler's
  literal-JSX invariant (`ordered-literal-object`'s computed-key trick). Ordinary
  keys take the direct `unchecked-set` path."
  [o k v]
  (if (= "__proto__" k)
    (js/Object.defineProperty o k #js {:value v :writable true :enumerable true :configurable true})
    (unchecked-set o k v)))

(defn foreign-spread-props
  "Merge a FOREIGN component spread's forwarded runtime map (`fwd`) UNDER its
  LITERAL compiled props object (`literal-obj`), returning a fresh plain JS
  object. The literal props — the component's own compiled handlers and props —
  WIN every key collision BY PRESENCE; the forwarded map fills in the rest. A
  foreign boundary is OPEN: its props pass through UNCONVERTED (no DOM rule
  table, no kebab→camel, no handler classification — the foreign head owns its
  own prop ABI), so each forwarded key is the author keyword's verbatim name
  (`namespace/name`, matching the compiler's `prop-slot-name`) and its value is
  set as-is.

  LITERAL-WINS-BY-PRESENCE (rf2-xu095): a foreign literal defends its slots by
  KEY PRESENCE, not by value — unlike `spread-safe-props`' DOM-parity
  NIL-MEANS-ABSENT rule. A compiled literal prop present with an explicit null
  still WINS the collision (the forwarded value does NOT survive), and `false`/
  `0` are retained; layer EVERY own key of `literal-obj` over the forwarded
  value. There is no per-slot deny law — a foreign boundary defends no
  owned/structural key (no memo comparator or slot ABI to protect).

  `__proto__` (rf2-xu095): the one magic key with prototype-setter grammar on a
  normal object. Every own set routes through `set-own!` so a forwarded (or
  literal) `__proto__` lands as a VERBATIM own data property and the output
  keeps its ordinary Object.prototype — never a prototype mutation, matching the
  literal-JSX invariant. Returns the merged JS object."
  [fwd literal-obj]
  (let [o (js-obj)]
    (when (some? fwd)
      (doseq [[k v] fwd]
        (set-own! o (if-some [ns* (namespace k)] (str ns* "/" (name k)) (name k)) v)))
    (doseq [k (js/Object.keys literal-obj)]
      (set-own! o k (unchecked-get literal-obj k)))
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

;; ---------------------------------------------------------------------------
;; ui/client-only — the S5 phase flip (rf2-3omxp; Spec 011 §Phase flip)
;;
;; S3 shipped the `ui/client-only` macro: a site with a mandatory,
;; capability-free `:fallback` (the JVM/SSR + first-hydration render) and a
;; client subtree. What S3 left unbuilt is HOW the fallback becomes the client
;; subtree after a server-rendered root hydrates. This is that mechanism.
;;
;; A root renders in one of two PHASES, `:server` or `:client` (the same
;; vocabulary as the Root Manifest `:phase` key). The phase is ONE root-scoped
;; value, carried by `phase-context`. A compiled `ui/client-only` site is
;; phase-conditional: it reads the phase and renders its fallback in `:server`
;; phase, its client subtree in `:client` phase.
;;
;; The context DEFAULT is `:client`, so every tree with no phase Provider above
;; it — every non-hydrating mount (`mount` / `render!` / `ui.test/render`) and
;; `render-static` — reads `:client` and renders the client subtree on the
;; first and only render, byte-identical to S3 (there is no fallback pass to
;; flip away from). Only a hydrating root installs a Provider (see
;; `PhaseFlipper` / `with-phase-flip`), boots `:server`, and flips once.
;; ---------------------------------------------------------------------------

(defonce ^:private phase-context
  (react/createContext :client))

(defn hydration-adopting?
  "A hook — true ONLY while a HYDRATING root renders its `:server`-phase
  ADOPTION pass (its first render, before the post-commit flip), false on every
  ordinary client mount (which reads the `:client` context default). This is the
  ROOT-SCOPED HYDRATION FACT `PhaseFlipper` already installs for `ui/client-only`
  (rf2-3omxp): `presence` consults it on its first render so a server-adopted
  presence child begins `:present` — matching the server markup — instead of
  re-entering `:mounting` and fabricating an enter transition on hydrate
  (rf2-uqe1b). Reads the SAME `phase-context` `ClientOnly` reads, so the two stay
  in lockstep: no second signal, no change to the hydrate-root path."
  []
  (= :server (react/useContext phase-context)))

(defn ^:private ClientOnly
  ;; The phase-conditional runtime for one compiled `ui/client-only` site: a
  ;; React function component that SUBSCRIBES to the root-scoped phase context
  ;; (`useContext`). Because context propagation ignores `React.memo`
  ;; boundaries, React re-renders THIS boundary directly when a hydrating root
  ;; flips `:server` -> `:client` — the enclosing memoised ViewCell needs no
  ;; involvement. Returns `fallback` in `:server` phase, `children` (the client
  ;; subtree) in `:client` phase. A function component adds no DOM of its own,
  ;; so the `:server`-phase render is structurally the fallback — exactly the
  ;; server-emitted markup, so hydration is a clean adoption.
  [^js props]
  (if (= :server (react/useContext phase-context))
    (.-fallback props)
    (.-children props)))

(when ^boolean js/goog.DEBUG
  (set! (.-displayName ClientOnly) "rf.ui/client-only"))

(defn client-only
  "Build a phase-conditional `(ui/client-only {:fallback fb} child)` element —
  the CLJS emitter's lowering target (S5 phase flip, rf2-3omxp). `fallback` is
  the capability-free `:server`/first-hydration subtree; `child` is the client
  subtree. See `ClientOnly`."
  [fallback child]
  (jsx2 ClientOnly #js {:fallback fallback :children child}))

(defn ^:private emit-phase-flip!
  ;; The ONE `:rf.ssr/phase-flip` diagnostic trace, at the flip commit. Rides
  ;; the diagnostic channel via `re-frame.trace/emit!`; the `interop/debug-
  ;; enabled?` gate DCEs the whole call under `:advanced` + `goog.DEBUG=false`
  ;; (Spec 009 §Production builds). It is NOT an event and mints no epoch —
  ;; called from a React passive effect, outside any dispatch/handler scope, so
  ;; it never correlates to an epoch (`emit!` reads a nil `*handler-scope*`).
  [root-id]
  (when interop/debug-enabled?
    (trace/emit! :info :rf.ssr/phase-flip {:root-id root-id})))

(defn emit-hydration-mismatch!
  ;; The compiled-tier `:rf.ssr/hydration-mismatch` diagnostic (rf2-6z1i2, the
  ;; Path A ruling). A hydrating compiled root has NO structural render-tree
  ;; hash — the CLJS emitter produces React elements, not the hashable JVM
  ;; structural tree, so the `:rf/render-hash` / `:render-tree-fn` /
  ;; `verify-hydration!` hash channel is HICCUP-TIER-ONLY (Spec 011
  ;; §Hydration-mismatch detection). A compiled root instead VERIFIES by
  ;; React-native ADOPTION: React diffs the root's first `:server`-phase render
  ;; (its `ui/client-only` fallbacks) against the server DOM during hydration
  ;; and reports the divergences it RECOVERS FROM (text-content / structural)
  ;; through the root's `onRecoverableError` — NOT attribute-only mismatches,
  ;; which take React's dev-only warning path and surface no trace on this tier
  ;; (Spec 011 §Hydration-mismatch detection, the attribute-only boundary).
  ;; `re-frame.ui.client/hydrate-root*` surfaces that adoption-window divergence
  ;; HERE, as the SAME `:rf.ssr/hydration-mismatch` category the hiccup tier
  ;; emits — tier-discriminated by `:where` (the compiled adoption site), with
  ;; `:root-id` and the recoverable `:error` message, and NO
  ;; `:server-hash`/`:client-hash` (there is no compiled-tier hash to report).
  ;;
  ;; Rides the diagnostic channel via `re-frame.trace/emit!`; the
  ;; `interop/debug-enabled?` gate DCEs the whole call under `:advanced` +
  ;; `goog.DEBUG=false` (Spec 009 §Production builds), exactly like
  ;; `emit-phase-flip!`. It is NOT an event and mints no epoch — it fires from a
  ;; React root-error callback, outside any dispatch/handler scope.
  [root-id error]
  (when interop/debug-enabled?
    (trace/emit! :warning :rf.ssr/hydration-mismatch
                 {:root-id  root-id
                  :error    (some-> error .-message)
                  :where    're-frame.ui/hydrate-root
                  :recovery :warned-and-replaced})))

(defn ^:private PhaseFlipper
  ;; Drives the phase flip for ONE hydrating root. It boots `:server` (so the
  ;; first = hydration render produces fallbacks, matching the server markup),
  ;; then flips to `:client` as the root's NEXT ORDINARY UPDATE after the
  ;; hydration commit. A passive effect (`useEffect`) does the flip: passive
  ;; effects run after the commit AND after paint, so the flip never beats the
  ;; hydration adoption, and a painted fallback frame before the swap is by
  ;; design (there is no `flushSync` — the capability-free fallback is
  ;; presentable UI, nothing to race). The single phase-context write swaps
  ;; EVERY client-only site in the root in the one update it produces.
  ;;
  ;; The `:server`-phase first render is also what makes the compiled tier's
  ;; hydration VERIFICATION honest: React ADOPTS the server DOM by diffing it
  ;; against this fallback-bearing render, and reports the divergences it
  ;; recovers from (text / structural, not attribute-only) through the root's
  ;; `onRecoverableError` — which `hydrate-root*` surfaces as
  ;; `:rf.ssr/hydration-mismatch` (rf2-6z1i2). That verification is React-native
  ;; adoption, not a render-tree hash: a compiled root produces React elements,
  ;; not the hashable structural tree the hiccup-tier `:render-tree-fn` hash
  ;; channel consumes (Spec 011 §Hydration-mismatch detection, the two-tier
  ;; split). Because the flip is a POST-commit passive effect, it runs strictly
  ;; after that adoption — the Spec 011 timing constraint (the flip must not
  ;; precede the mismatch check) holds by construction. The flip clears the
  ;; root's `adoption-ref` on its `:server` commit, closing the adoption window
  ;; so a later recoverable error is NOT misclassified as a hydration mismatch.
  ;;
  ;; A root that fails to boot never commits this component, so its passive
  ;; effect never runs and it never flips — its server fallback markup stays
  ;; inert (`:rf.error/root-boot-failed` already fired; no additional error).
  ;; Per-root and independent: each flipper is keyed to its own root's commit,
  ;; so a slow/failed sibling never delays this flip.
  [^js props]
  (let [root-id   (.-rfRootId props)
        pair      (react/useState :server)
        phase     (aget pair 0)
        set-phase (aget pair 1)]
    (react/useEffect
     (fn phase-flip []
       ;; The effect fires once per phase value. On the `:server` (mount /
       ;; hydration) commit it CLOSES the adoption window (the hydration commit
       ;; has landed) and schedules the flip; on the `:client` commit — the
       ;; flip commit — it emits the trace. `phase` is monotonic (`:server` ->
       ;; `:client`, once), so the trace fires exactly once per hydrating root.
       (if (= :server phase)
         (do
           (when-some [adoption (.-rfAdoption props)]
             (set! (.-adopting adoption) false))
           (set-phase :client))
         (emit-phase-flip! root-id))
       js/undefined)
     #js [phase])
    (react/createElement (.-Provider phase-context)
                         #js {:value phase}
                         (.-children props))))

(when ^boolean js/goog.DEBUG
  (set! (.-displayName PhaseFlipper) "rf.ui/phase-flipper"))

(defn with-phase-flip
  "Wrap a hydrating root's compiled `element` in the phase flipper (S5 phase
  flip, rf2-3omxp). ONLY `ui/hydrate-root` calls this. A non-hydrating mount
  installs no flipper, so its tree has no phase Provider and every
  `ui/client-only` site reads the `:client` default and renders its client
  subtree on the first render — byte-identical to S3. `render-static` likewise
  installs none and stays pure `:server`.

  `adoption-ref` is the root-local mutable flag `#js {:adopting true}` the
  hydration-mismatch `onRecoverableError` wrapper reads (rf2-6z1i2): the flipper
  sets `.-adopting` false on its `:server` commit, closing the adoption window.
  Passed through from `hydrate-root*`, which owns both ends of the flag."
  [root-id element adoption-ref]
  (react/createElement PhaseFlipper
                       #js {:rfRootId root-id :rfAdoption adoption-ref}
                       element))

;; ---------------------------------------------------------------------------
;; ui/->react — the OUTWARD interop bridge (rf2-u53yy.2)
;;
;; `(ui/->react view)` exports a compiled re-frame.ui view as a React component a
;; FOREIGN React/UIx tree can render — the OUTWARD half of the foreign
;; boundary (Spec 004 §The React interop tier; the compat-boundary contract §3).
;; The inward half is `ui/raw`. Both traffic in plain React values and neither
;; couples `day8/re-frame2-ui` to any compat adapter. `re-frame.ui/->react` is
;; the public authoring name; this is its CLJS runtime (a JVM call is a
;; host-op — a React component export has no meaning in a structural render).
;; The bridge:
;;
;;   - returns a component memoised PER VIEW IDENTITY — a WeakMap keyed by the
;;     view value — so repeated `(ui/->react view)` calls (and, in DEV, the HMR
;;     generations that retain the view's stable shell) return the IDENTICAL
;;     object; a foreign parent re-render never remounts the exported subtree;
;;   - creates NO React root, mints NO root manifest, and runs NO host preflight
;;     — the exported subtree renders inside the host root the foreign parent
;;     owns, and frame creation stays with the host app's boot/event
;;     infrastructure. An exported view SCOPES and resolves frames; it never
;;     creates them (`frame-root` cannot appear in a `defview` body anyway);
;;   - SCOPES a supplied frame without owning it: the ONE reserved prop `frame`
;;     (a frame-id keyword or a live frame value) wraps the view in the shared
;;     React frame-context Provider through `frames/->react-scope-element`
;;     (SCOPE-only — the rf2-nyea0r split: providers scope, roots ensure). The
;;     prop is resolved by OWN-PROPERTY PRESENCE, not truthiness (rf2-01rwd):
;;     an OMITTED `frame` is the SOLE ambient-resolution case — the exported view
;;     resolves its frame by the ordinary ambient chain (a foreign
;;     `frame-provider`/`frame-root` above it, sharing the same context object),
;;     or fails loud with `:rf.error/no-frame-context`. An OWN `frame` prop is
;;     ALWAYS validated against the one frame-target grammar — INCLUDING an
;;     explicit `frame={null}`/`frame={undefined}`, which fails loud rather than
;;     silently adopting the ambient frame — and every typed failure (empty,
;;     malformed, absent/dead) NAMES `re-frame.ui/->react`, not `frame-provider`,
;;     so the error lands at the bridge the caller actually used;
;;   - THE ONE shallow props-conversion rule: the foreign JS props object is
;;     handed to the view by a single shallow copy, dropping ONLY the reserved
;;     `frame` key. Every remaining own-enumerable key is a view prop-ABI slot,
;;     matched by exact string name (namespace+name preserved — no camelisation,
;;     no deep walk). React's own `children` and `ref` are ordinary props under
;;     that rule, so children and ref semantics are preserved by construction (a
;;     ref rides through to the view; child :ref forwarding is the view's own
;;     contract).
;; ---------------------------------------------------------------------------

(defonce ^:private exported-component-cache
  ;; view value (a compiled `defview`'s React component object) -> its stable
  ;; exported wrapper. A WeakMap keyed by the view identity, so an unreferenced
  ;; view never pins its wrapper and `defonce` survives ns reload.
  (js/WeakMap.))

(defn- own-prop?
  "True when the foreign JS `obj` OWNS `k` as an own property — via
  `Object.prototype.hasOwnProperty.call` so a null-proto object or a prop
  literally named \"hasOwnProperty\" cannot break the check (the reagent-slim
  `strict-obj-has-key` idiom, rf2-tsuk6). Unlike a truthiness test it
  DISTINGUISHES an absent `k` from an explicitly-supplied `k={null}` /
  `k={undefined}` — the exactness the reserved `frame` prop turns on
  (rf2-01rwd)."
  [obj k]
  (.call (.. js/Object -prototype -hasOwnProperty) obj k))

(defn- exported-view-props
  "THE shallow props-conversion rule: a single shallow copy of the foreign JS
  `props` object dropping ONLY the reserved `frame` key. Every other
  own-enumerable key — the view's prop-ABI slots plus React's `children` and
  `ref` — copies through by exact string name, uncoerced."
  [props]
  (let [o (js-obj)]
    (doseq [k (js/Object.keys props)]
      (when (not= "frame" k)
        (unchecked-set o k (unchecked-get props k))))
    o))

(defn ->react-component
  "Runtime of `(ui/->react view)` — see the section header above. `view` is a
  compiled re-frame.ui view value (a `defview`'s var); the return is the stable
  exported React component. `re-frame.ui/->react` is the public authoring name
  and delegates here on CLJS."
  [view]
  (when-not (or (fn? view) (object? view))
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/->react
     ;; rf2-q9q9y — the guard passes any `object?`, so a BARE provider never
     ;; reaches this throw (measured). What does reach it: a hiccup vector,
     ;; which the message itself anticipates, and a JS ARRAY, whose
     ;; constructor is `js/Array` so `object?` is false. Both can carry a
     ;; cycle. Message half `error/pr-form`, ex-data half `error/safe-form`.
     (str "(ui/->react view) needs a compiled re-frame.ui view (a `defview` "
          "value — a React component), but received " (error/pr-form view)
          ". Pass the view itself, e.g. (def CartRow (ui/->react cart-row)); "
          "not its id keyword and not a rendered form")
     {:extra (error/safe-form {:value view})}))
  (or (.get exported-component-cache view)
      (let [exported
            (fn rf-ui->react [props]
              (let [element (jsx2 view (exported-view-props props))]
                (if (own-prop? props "frame")
                  ;; OWN `frame` prop: validate EVERY value — including an
                  ;; explicit `frame={null}`/`frame={undefined}` — and SCOPE,
                  ;; attributing any typed failure to `re-frame.ui/->react`
                  ;; (rf2-01rwd). An owned-but-invalid target NEVER silently
                  ;; falls through to the ambient chain. SCOPE-only:
                  ;; creates/refreshes/destroys nothing.
                  (frames/->react-scope-element (unchecked-get props "frame")
                                                #js [element])
                  ;; OMITTED `frame` prop — the SOLE ambient-resolution case: the
                  ;; exported view resolves its frame by the ordinary ambient
                  ;; chain, or fails loud with `:rf.error/no-frame-context`.
                  element)))]
        (when ^boolean js/goog.DEBUG
          (set! (.-displayName exported)
                (str "rf.ui/->react(" (or (.-displayName view) "view") ")")))
        (.set exported-component-cache view exported)
        exported)))
