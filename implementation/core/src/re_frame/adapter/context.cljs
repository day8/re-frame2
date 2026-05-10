(ns re-frame.adapter.context
  "Shared React context for frame propagation across substrate adapters.

  Per Spec 006 §Frame-provider via React context, the frame keyword is
  propagated through the React tree via a single React Context. Both the
  Reagent and UIx adapters read this same context object so a tree
  containing components from multiple substrates resolves frames
  consistently — and so a future mixed-substrate app (rf2-3yij Decision
  2) sees one shared frame-provider chain rather than per-adapter
  silos.

  The context lives in core (CLJS-only) because:

    1. Core already :requires React directly via re-frame.views, so this
       file adds no new transitive runtime dep. The plain-atom adapter
       (the JVM-runnable half) does not load this ns — it sits in
       re_frame/adapter/context.cljs (CLJS-only) and the JVM build
       never sees it.

    2. Both adapters MUST share the *same* React.createContext object —
       two separate createContext calls produce distinct contexts whose
       Provider/Consumer pairs do not interact. Putting the createContext
       call in a single shared ns guarantees identity.

  Per rf2-3yij Decision 2: factored out of re-frame.views for the UIx
  adapter to read."
  (:require ["react" :as React]
            [re-frame.frame :as frame]))

(defonce frame-context
  ;; The default value is :rf/default — Spec 002 §`:rf/default` guarantees
  ;; this frame always exists. Components without an enclosing
  ;; frame-provider resolve to it.
  (.createContext React :rf/default))

(defn provider-element
  "Build a React element for the frame-context Provider with `frame-kw`
  as its value and `children` as its child elements. Substrate-agnostic
  — both the Reagent adapter (via `:>` interop) and the UIx adapter
  (via `$`) can wrap the appropriate hiccup/expression form around this
  primitive.

  Returns a raw React element so callers don't pay for an extra
  reagent.core/as-element walk."
  [frame-kw & children]
  (apply React/createElement
         (.-Provider frame-context)
         #js {:value frame-kw}
         children))

;; ---- coercion helper for React-context reads (rf2-d4sf) ------------------
;;
;; Reagent's `convert-prop-value` (reagent.impl.template) stringifies
;; named values when they are passed as React props: `[:> Provider
;; {:value :foo} ...]` reaches React with `value=\"foo\"`, not the
;; keyword. Both Reagent's class-component context-read path
;; (`(.-context cmp)`) and the function-component `_currentValue` read
;; observe the same stringified shape, so the coercion is shared. The
;; createContext default (`:rf/default`) survives as a keyword because
;; it never passes through Reagent's prop-conversion.
;;
;; Per Spec 002 §Reading the frame from React context — Reagent
;; prop-conversion of named values.

(defn coerce-context-value
  "Coerce a raw React-context read (from `(.-context cmp)` or
  `_currentValue`) into a frame-id keyword, or nil when the read does
  not name a frame. Tolerates Reagent's prop-stringified shape per
  rf2-d4sf."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (not= "" v)) (keyword v)))

;; ---- function-component current-frame (UIx / Helix; rf2-d4sf) ------------
;;
;; UIx and Helix render function components — they have no class-
;; component-specific `(.-context cmp)` slot. The substrate-portable
;; way to observe the active Provider's value is to read
;; `_currentValue` directly off the shared context object. React
;; mutates this field as Provider boundaries are entered and exited
;; during render, so reads from inside a render see the closest
;; enclosing Provider's value.
;;
;; Per Spec 006 §Frame-provider via React context, this fn is the
;; canonical impl that the UIx and Helix adapters publish through the
;; `:adapter/current-frame` late-bind hook. Reagent has its own impl
;; in `re-frame.views/current-frame` that uses the class-component
;; `(.-context cmp)` path so the plain-fn-under-non-default-frame-once
;; warning's narrowness contract is preserved (plain Reagent fns
;; lacking `:contextType` continue to route to `:rf/default`, which is
;; what the warning targets).

(defn function-component-current-frame
  "Resolution chain for function-component substrates (UIx, Helix):

    1. `re-frame.frame/*current-frame*` (dynamic var) — set by
       `with-frame` / `bound-fn`.
    2. The closest enclosing frame-provider via React context. Reads
       `_currentValue` off the shared context object directly (the
       substrate-portable path; UIx's `use-context` and Helix's
       `use-context` are both sugar over this read).
    3. `:rf/default`.

  Tolerates Reagent's prop-stringified-keyword shape via
  `coerce-context-value` — relevant when a UIx / Helix subtree is
  embedded in a tree whose `frame-provider` was authored as a Reagent
  `[:> ...]` interop call."
  []
  (or frame/*current-frame*
      (when-some [v (.-_currentValue ^js frame-context)]
        (coerce-context-value v))
      :rf/default))
