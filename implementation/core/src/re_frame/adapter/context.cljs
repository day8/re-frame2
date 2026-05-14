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

  Factored out of re-frame.views so every React-shaped adapter (UIx,
  Helix) reads the same context object."
  (:require ["react" :as React]
            [re-frame.frame :as frame]
            [re-frame.trace :as trace]))

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

;; ---- coercion helper for React-context reads ------------------------------
;;
;; Defensive cover for raw-hiccup Provider mounts. The canonical user-
;; facing surface (`rf/frame-provider` -> `frame-provider-component`)
;; mounts the Provider via Reagent's `:r>` interop head, which passes
;; the props through as a raw JS object — `convert-prop-value` is
;; bypassed entirely and the `:value` keyword (including its namespace)
;; survives the React-context round trip.
;;
;; A user who writes `[:> (.-Provider frame-context) {:value :foo}]`
;; directly (raw `:>` interop, not `rf/frame-provider`) still hits
;; stock Reagent's prop-conversion under the classic adapter
;; (`day8/re-frame2-reagent`). That path stringifies the keyword:
;; `:foo` -> `\"foo\"`. The slim adapter (`day8/reagent-slim`)
;; preserves keywords on non-HTML prop names so the read returns the
;; keyword directly under that build.
;;
;; This helper round-trips the stringified shape back to a keyword so
;; the resolution chain returns the same shape regardless of which
;; adapter is loaded and whether the Provider was mounted via the
;; canonical surface or raw hiccup. Note: stock Reagent's
;; `(name kw)` is lossy for namespaced keywords — a raw-hiccup mount
;; under the classic adapter with `{:value :foo/bar}` will reach the
;; reader as `:bar`, not `:foo/bar`. The canonical surface preserves
;; namespaces; raw-hiccup mounts that need namespaced frame-ids should
;; switch to `rf/frame-provider` or to `provider-element` directly.

(defn coerce-context-value
  "Coerce a raw React-context read (from `(.-context cmp)` or
  `_currentValue`) into a frame-id keyword, or nil when the read does
  not name a frame. Tolerates a prop-stringified keyword shape that
  raw-hiccup `[:> Provider {:value :foo}]` mounts produce under the
  classic Reagent adapter — see the section header above for the
  namespace-preservation contract."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (not= "" v)) (keyword v)))

(defn- value-type-tag
  "Return a short keyword tag describing v's runtime type, for
  `:rf.error/frame-context-corrupted` diagnostic payloads. Names
  shapes the bead enumerates (nil, false, number, empty-string, JS
  object, …) directly so dashboards can branch without reflecting on
  pr-str output."
  [v]
  (cond
    (nil? v)              :nil
    (false? v)            :boolean
    (true? v)             :boolean
    (and (string? v)
         (= "" v))        :empty-string
    (string? v)           :string
    (keyword? v)          :keyword
    (number? v)           :number
    (symbol? v)           :symbol
    (map? v)              :map
    (vector? v)           :vector
    (sequential? v)       :sequential
    (coll? v)             :collection
    (fn? v)               :fn
    :else                 :js-object))

(defn- emit-frame-context-corrupted!
  "Emit `:rf.error/frame-context-corrupted` (per Spec 009 §Error
  categories). The React-context value at the function-component read
  site (`_currentValue`) was a shape `coerce-context-value` cannot
  resolve to a frame keyword — typically nil, false, a number, an
  empty string, or a JS object. Recovery is `:replaced-with-default`:
  the resolution chain falls through to `:rf/default`."
  [v]
  (trace/emit-error! :rf.error/frame-context-corrupted
                     {:received v
                      :type     (value-type-tag v)
                      :recovery :replaced-with-default
                      :reason   "React-context `_currentValue` is not a frame keyword; check the closest `frame-provider` boundary (or whether the subtree was rendered through an unwrapped portal)."}))

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
  `[:> ...]` interop call.

  Corrupted-`_currentValue` detection (rf2-8q66): the
  `createContext` default is `:rf/default` (a keyword), so a
  function-component read should always observe either a keyword or
  the prop-stringified-keyword shape. Anything else (nil, false, a
  number, an empty string, a JS object) means the React-context
  boundary was disturbed — a portal rendering outside its Provider, a
  library mutating `_currentValue`, or a Provider authored with a
  non-keyword value. The runtime emits
  `:rf.error/frame-context-corrupted` and falls through to
  `:rf/default` (recovery `:replaced-with-default`)."
  []
  (or frame/*current-frame*
      (let [v (.-_currentValue ^js frame-context)]
        (or (coerce-context-value v)
            ;; Corrupted branch: value is not a frame keyword and not
            ;; a non-empty string — covers nil, false, numbers, empty
            ;; strings, and JS objects. Emit the structured error and
            ;; fall through.
            (do (emit-frame-context-corrupted! v)
                nil)))
      :rf/default))
