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
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

(def no-provider-sentinel
  "The React-context default value — the **no-provider sentinel**, NOT
  `:rf/default`. Per Spec 002 §Frame target resolution — the carried
  invariant (EP-0002): the React-context default carries no framework
  privilege and the runtime never synthesises a frame from absence. A
  component rendered with NO enclosing `frame-provider` observes this
  sentinel; the resolver reads it as 'no frame in scope' and returns nil
  (it is the reader's nil, not a `:rf/default` floor). A dedicated
  namespaced keyword (rather than nil) so a raw `_currentValue` read can
  positively distinguish 'no Provider above me' from a genuinely corrupted
  context value (nil / false / a number / a JS object)."
  :rf.frame/no-provider)

(defonce frame-context
  ;; Default = the no-provider sentinel (NOT :rf/default). Components
  ;; without an enclosing frame-provider observe this; the resolver maps it
  ;; to nil (no scope) rather than to a synthesised default frame, per the
  ;; EP-0002 carried invariant.
  (.createContext React no-provider-sentinel))

;; rf2-fa4ly: stamp a human-readable `displayName` on the React Context
;; object so React DevTools' Context inspector shows the entry as
;; `rf2-frame.Provider` / `rf2-frame.Consumer` rather than the opaque
;; default ("Context.Provider"). Dev-only — gated under
;; `interop/debug-enabled?` so the string literal DCEs in production.
;; React DevTools reads `.-displayName` off the Context object directly
;; (per the React DevTools backend's `getContextName` helper). The
;; label deliberately avoids the unmunged ns string "re-frame.frame"
;; to keep the elision sentinel unambiguous against keyword literals
;; under that namespace.
(when interop/debug-enabled?
  (set! (.-displayName ^js frame-context) "rf2-frame"))

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

(defn normalize-children
  "Collapse a substrate element-macro's trailing-`$`-children value into a
  flat positional arg list (rf2-7kii2). The native trailing-children idiom
  hands a provider core whatever shape each substrate's element macro
  stashes on `:children` — a JS ARRAY for multiple trailing children
  (UIx's `(cljs.core/array …)`, Helix's `(into-array …)`), a SINGLE element
  for one trailing child (Helix), a CLJS vector/seq, or `nil` (no
  children). All four collapse here: a JS array is spread via `array-seq`,
  an existing CLJS sequential is passed through, `nil` becomes no children,
  and any lone non-collection child is wrapped. React keys multi-child
  arrays correctly because the spread reaches `createElement` as distinct
  positional args, not a single array child.

  Shared by `re-frame.substrate.spine/build-frame-provider-element` (the
  scope-only provider core) and `re-frame.views.owned-frame`'s
  `ensure-frame-fc` / `ensure-frame-react-element` (the ENSURE provider
  cores) so the three element builders normalise children one identical
  way — `(apply provider-element frame-kw (normalize-children children))`."
  [children]
  (cond
    (nil? children)        nil
    (array? children)      (array-seq children)
    (sequential? children) children
    :else                  [children]))

;; ---- source-coord annotation value formatters (Spec 006) ------------------
;;
;; The `data-rf2-source-coord` / `data-rf-view` DOM attribute VALUES are
;; pure string projections of the registry slot's id + captured coords.
;; The DOM output MUST be byte-identical across substrates (Reagent's
;; hiccup walk in `re-frame.views.source-coord-annotation` and the
;; React-element-clone walk in `re-frame.substrate.spine` produce the SAME
;; attribute string for the same view), so the formatters live here once —
;; a shared leaf both walks already require — rather than as drifting
;; per-walk copies. The injection WALKS stay split (hiccup vs React-element
;; are genuinely different); only these pure formatters are shared.

(defn format-source-coord
  "Render the registry slot's captured coords as the attribute value shape
  `<ns>:<sym>:<line>:<col>`. The id keyword's namespace and name give us
  `<ns>` and `<sym>`; `<line>` / `<col>` come from the captured coords
  (CLJS reg-view macro at expansion time). `<col>` is `?` when the column
  was not captured (the column-key is optional per Spec 001). Per Spec 006
  §Source-coord annotation. Shared by the Reagent hiccup walk
  (`re-frame.views.source-coord-annotation`) and the React-element-clone
  walk (`re-frame.substrate.spine`) so the attribute value is identical
  across substrates."
  [id coords]
  (let [ns-part  (or (namespace id) "?")
        sym-part (name id)
        line     (:line coords)
        col      (:column coords)]
    (str ns-part ":" sym-part ":"
         (if line (str line) "?")
         ":"
         (if col (str col) "?"))))

(defn format-view-id
  "Render the registry id keyword as the `:data-rf-view` attribute value.
  Returns `(str id)` so `:rf.foo/bar` → `\":rf.foo/bar\"`. The walker reads
  it back via `(keyword (subs s 1))` when the leading `:` is present. Per
  Spec 006 §View tagging contract (rf2-01il5). Shared by the Reagent and
  React-element walks so the attribute value is identical across
  substrates."
  [id]
  (str id))

(defn non-dom-root-warning
  "Build the one-shot `console.warn` text for a reg-view'd component whose
  root element is a non-DOM root (a fn/class component or a React Fragment)
  — the source-coord walk skips the annotation and pair tools fall back to
  the registry's `:rf/id` (documented exemption per Spec 006 §Source-coord
  annotation). `type-tag` is the offending root's head/type for the
  diagnostic. `substrate-name` is an optional string identifying the host
  substrate (\"UIx\", \"Helix\", …); it is omitted from the message when
  nil — the Reagent hiccup-walk path passes nil (no substrate qualifier),
  the React-element spine path passes its substrate name. Shared by
  `re-frame.views.warn-once` and `re-frame.substrate.spine` so the message
  text does not drift; each side keeps its OWN warn-once cache."
  [id type-tag substrate-name]
  (str "[re-frame] reg-view " id " — root element is "
       (pr-str type-tag)
       (when substrate-name (str " (" substrate-name ")"))
       "; data-rf2-source-coord skipped "
       "(Spec 006 §Source-coord annotation: pair tools fall back to "
       ":rf/id for non-DOM roots)."))

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
  resolve to a frame keyword AND is not the no-provider sentinel —
  typically false, a number, an empty string, or a JS object. Recovery is
  `:no-frame-context`: the resolution chain returns nil (NOT a synthesised
  `:rf/default` — per the EP-0002 carried invariant). A public frame-
  scoped operation reading nil then fails loudly with
  `:rf.error/no-frame-context`; the corruption is reported here as its own
  distinct category so a disturbed context boundary is not silently folded
  into ordinary 'no scope'."
  [v]
  (trace/emit-error! :rf.error/frame-context-corrupted
                     {:received v
                      :type     (value-type-tag v)
                      :recovery :no-frame-context
                      :reason   "React-context `_currentValue` is not a frame keyword and not the no-provider sentinel; check the closest `frame-provider` boundary (or whether the subtree was rendered through an unwrapped portal)."}))

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
;; `(.-context cmp)` path: a plain Reagent fn lacking `:contextType`
;; cannot read the surrounding Provider's frame, so under EP-0002 (no
;; `:rf/default` floor) its ambient `subscribe`/`dispatch` resolves nil
;; and raises the always-on `:rf.error/no-frame-context` rather than
;; silently routing to a default. (This superseded the retired
;; `:rf.warning/plain-fn-under-non-default-frame-once` warning.)

(defn function-component-current-frame
  "Resolution chain (READER) for function-component substrates (UIx,
  Helix). Returns the scope frame, or **nil** when no scope is
  established — it never synthesises `:rf/default` (per Spec 002 §Frame
  target resolution — the carried invariant, EP-0002). The two tiers it
  observes:

    1. `re-frame.frame/*current-frame*` (dynamic var) — set by
       `with-frame` / `frame-bound-fn`.
    2. The closest enclosing frame-provider via React context. Reads
       `_currentValue` off the shared context object directly (the
       substrate-portable path; UIx's `use-context` and Helix's
       `use-context` are both sugar over this read).

  Returns nil when neither tier names a frame — a component rendered with
  no enclosing `frame-provider` observes the no-provider sentinel, which
  resolves to nil ('no scope'). Public frame-scoped operations turn that
  nil into a loud `:rf.error/no-frame-context` via
  `frame/require-current-frame!`; low-level readers / tooling model 'no
  context' with the nil directly.

  Tolerates Reagent's prop-stringified-keyword shape via
  `coerce-context-value` — relevant when a UIx / Helix subtree is
  embedded in a tree whose `frame-provider` was authored as a Reagent
  `[:> ...]` interop call.

  Corrupted-`_currentValue` detection (rf2-8q66): the `createContext`
  default is now the no-provider sentinel (a keyword), so a function-
  component read should always observe either a frame keyword, the prop-
  stringified-keyword shape, or the sentinel. Anything else (false, a
  number, an empty string, a JS object) means the React-context boundary
  was disturbed — a portal rendering outside its Provider, a library
  mutating `_currentValue`, or a Provider authored with a non-keyword
  value. The runtime emits `:rf.error/frame-context-corrupted` and returns
  nil (recovery `:no-frame-context`)."
  []
  (or frame/*current-frame*
      (let [v (.-_currentValue ^js frame-context)]
        (cond
          ;; No enclosing Provider — the sentinel resolves to nil ('no
          ;; scope'), NOT a synthesised default. This is the common,
          ;; benign case; it is NOT corruption.
          (= v no-provider-sentinel) nil
          ;; A real frame keyword (or prop-stringified keyword) names the
          ;; enclosing Provider's frame.
          (coerce-context-value v)   (coerce-context-value v)
          ;; Corrupted branch: not a frame keyword, not the sentinel —
          ;; covers false, numbers, empty strings, and JS objects. Emit
          ;; the structured error and return nil (no synthesis).
          :else                      (do (emit-frame-context-corrupted! v)
                                         nil)))))
