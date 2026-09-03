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

  Factored out of re-frame.views so every React-shaped adapter (UIx)
  reads the same context object."
  (:require ["react" :as React]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace]))

(def no-provider-sentinel
  "The React-context default value — the **no-provider sentinel**, NOT
  `:rf/default`. Per Spec 002 §Frame target resolution — the carried
  invariant (EP-0002): the React-context default carries no framework
  privilege and the runtime never synthesises a frame from absence. A
  component rendered beneath NO frame boundary — neither a `frame-provider`
  (SCOPE) nor a `frame-root` (ENSURE), which both install this same
  context — observes this sentinel; the resolver reads it as 'no frame in
  scope' and returns nil (it is the reader's nil, not a `:rf/default`
  floor). A dedicated namespaced keyword (rather than nil) so a raw
  `_currentValue` read can positively distinguish 'no boundary above me'
  from a genuinely corrupted context value (nil / false / a number / a JS
  object)."
  :rf.frame/no-provider)

(defonce frame-context
  ;; Default = the no-provider sentinel (NOT :rf/default). Components
  ;; beneath neither frame boundary — `frame-provider` (SCOPE) nor
  ;; `frame-root` (ENSURE) — observe this; the resolver maps it to nil (no
  ;; scope) rather than to a synthesised default frame, per the EP-0002
  ;; carried invariant.
  (.createContext React no-provider-sentinel))

;; rf2-fa4ly: stamp a human-readable `displayName` on the React Context
;; object so React DevTools' Context inspector shows the entry as
;; `rf2-frame.Provider` / `rf2-frame.Consumer` rather than the opaque
;; default ("Context.Provider"). Dev-only — gated under
;; `rf.interop/debug-enabled?` so the string literal DCEs in production.
;; React DevTools reads `.-displayName` off the Context object directly
;; (per the React DevTools backend's `getContextName` helper). The
;; label deliberately avoids the unmunged ns string "re-frame.frame"
;; to keep the elision sentinel unambiguous against keyword literals
;; under that namespace.
(when rf.interop/debug-enabled?
  (set! (.-displayName ^js frame-context) "rf2-frame"))

(defn provider-element
  "Build a React element for the frame-context Provider with `frame-kw`
  as its value and `children` as its child elements. Substrate-agnostic
  — the Reagent adapters (via `:>` interop) and the UIx adapter (via
  `$`) both wrap their element idiom around this primitive.

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
  (UIx's `(cljs.core/array …)`), a SINGLE element
  for one trailing child, a CLJS vector/seq, or `nil` (no
  children). All four collapse here: a JS array is spread via `array-seq`,
  an existing CLJS sequential is passed through, `nil` becomes no children,
  and any lone non-collection child is wrapped. React keys multi-child
  arrays correctly because the spread reaches `createElement` as distinct
  positional args, not a single array child.

  Shared by `re-frame.substrate.spine/build-frame-provider-element` (the
  scope-only `frame-provider` core) and `re-frame.views.frame-boundary`'s
  `frame-root-fc` / `frame-root-react-element` (the ENSURE `frame-root`
  cores), so every published element builder normalises children one
  identical way — `(apply provider-element frame-kw (normalize-children
  children))`."
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
;; The DOM output MUST be byte-identical across substrates AND across hosts
;; (Reagent's hiccup walk in `re-frame.views.source-coord-annotation`, the
;; React-element-clone walk in `re-frame.substrate.spine`, and the JVM
;; registration-boundary annotation in
;; `re-frame.views.jvm-source-coord-annotation` all emit the SAME attribute
;; string for the same view). rf2-5q0jv moved the single implementation into
;; the neutral `.cljc` contract owner `re-frame.source-coords`, co-located
;; with its inverse parsers, so a JVM copy and a CLJS copy can no longer
;; drift. These two vars are CLJS-side aliases preserving the historical
;; `re-frame.adapter.context` names the injection walks (and the
;; `re-frame.views` / `re-frame.substrate.spine` re-exports) already call.
;; The injection WALKS stay split (hiccup vs React-element are genuinely
;; different); only these pure formatters are shared.

(def format-source-coord
  "Render the registry slot's captured coords as the `data-rf2-source-coord`
  attribute value `<ns>:<sym>:<line>:<col>` (Spec 006 §Source-coord
  annotation). CLJS-side alias of the neutral cross-host owner
  [[re-frame.source-coords/format-source-coord]] (rf2-5q0jv) — kept under this
  name so the Reagent hiccup walk, the React-element-clone walk, and the
  `re-frame.views` re-export are unchanged."
  rf.source-coords/format-source-coord)

(def format-view-id
  "Render the registry id keyword as the `:data-rf-view` attribute value
  `(str id)`, so `:rf.foo/bar` → `\":rf.foo/bar\"` (Spec 006 §View tagging
  contract, rf2-01il5). CLJS-side alias of the neutral cross-host owner
  [[re-frame.source-coords/format-view-id]] (rf2-5q0jv)."
  rf.source-coords/format-view-id)

(defn non-dom-root-warning
  "Build the one-shot `console.warn` text for a reg-view'd component whose
  root element is a non-DOM root (a fn/class component or a React Fragment)
  — the source-coord walk skips the annotation and pair tools fall back to
  the registry's `:rf/id` (documented exemption per Spec 006 §Source-coord
  annotation). `type-tag` is the offending root's head/type for the
  diagnostic. `substrate-name` is an optional string identifying the host
  substrate (\"UIx\", …); it is omitted from the message when
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
  (rf.trace/emit-error! :rf.error/frame-context-corrupted
                     {:received v
                      :type     (value-type-tag v)
                      :recovery :no-frame-context
                      :reason   "React-context `_currentValue` is not a frame keyword and not the no-provider sentinel; check the closest frame boundary above this subtree — a `frame-provider` (SCOPE) or a `frame-root` (ENSURE) — or whether the subtree was rendered through an unwrapped portal."}))

;; ---- function-component current-frame (UIx; rf2-d4sf) ------------
;;
;; UIx renders function components — they have no class-
;; component-specific `(.-context cmp)` slot. The substrate-portable
;; way to observe the active Provider's value is to read the context
;; object's value slot directly. React mutates that field as Provider
;; boundaries are entered and exited during render, so reads from
;; inside a render see the closest enclosing Provider's value.
;;
;; WHICH SLOT, THOUGH (rf2-5rqn). `createContext` initialises TWO value
;; slots to the default — `_currentValue` and `_currentValue2` — and a
;; renderer claims one of them (React 19.2:
;; packages/react/src/ReactContext.js). The DOM client renderer pushes
;; and pops `_currentValue`; the Fizz server renderer
;; (`react-dom/server`) pushes and pops `_currentValue2`
;; (packages/react-server/src/ReactFizzNewContext.js). A reader that
;; consults only the primary therefore observes the no-provider sentinel
;; through an entire server render, and every ambient consumer funnelling
;; through it refuses with `:rf.error/no-frame-context` beneath a
;; provider that DID establish a scope. So the reader below falls back to
;; the secondary slot — and only when the primary is EXACTLY the untouched
;; sentinel, so a corrupted primary is never masked. Under the client
;; renderer the secondary is never written and still holds the sentinel,
;; which classifies to nil, so the fallback changes nothing there.
;; This is pinned evidence for the React version this repo pins, not a
;; claim about future React: if an upgrade removes or repurposes the
;; private slots, thread the public `useContext` return through the hook
;; surfaces instead (the documented escape hatch, rf2-5rqn's ruling).
;;
;; WHERE THE FALLBACK STOPS (measured, rf2-5rqn). Fizz pops the secondary
;; slot when a render EXITS normally, and does not when a render THROWS —
;; but the next render pops the abandoned snapshot before running any
;; component, so no render ever observes another render's frame. The
;; window is therefore between an aborted render and the next one, and
;; only a read taken OUTSIDE any render falls in it. That is not what the
;; ambient tier promises on either renderer: ambient resolution is a
;; render-time notion, and code running outside a render carries a frame
;; explicitly or binds one with `with-frame` — tier 1, which outranks both
;; slots. A stale-slot guard here was ruled out by name as
;; over-engineering. The behaviour is pinned by part 5 of
;; `assert-use-subscribe-ambient-under-ssr`.
;;
;; Per Spec 006 §Frame-provider via React context, this fn is the
;; canonical impl that the UIx adapter publishes through the
;; `:adapter/current-frame` late-bind hook. Reagent has its own impl
;; in `re-frame.views/current-frame` that uses the class-component
;; `(.-context cmp)` path: a plain Reagent fn lacking `:contextType`
;; cannot read the surrounding Provider's frame, so under EP-0002 (no
;; `:rf/default` floor) its ambient `subscribe`/`dispatch` resolves nil
;; and raises the always-on `:rf.error/no-frame-context` rather than
;; silently routing to a default. (This superseded the retired
;; `:rf.warning/plain-fn-under-non-default-frame-once` warning.)

(defn context-value->current-frame
  "Classify a raw frame-context value `v` into the scope frame keyword, or
  **nil** when `v` names no frame — the shared sentinel / coercion /
  corruption rules, applied to a value already OBTAINED from the shared
  frame-context. Two callers source `v` differently but classify it
  identically:

    - the public `useContext` RETURN and the class-component `.-context`
      read — the renderer-agnostic paths Hicasso's boundary, overlay and
      presence seams take (rf2-2rzx0); and
    - a direct `_currentValue` slot read — the substrate-portable reader path
      ([[function-component-current-frame]] below).

  Both must resolve one way, so the rules live here once rather than drifting
  between the two sites:

    - the no-provider sentinel resolves to nil ('no scope'), NOT a synthesised
      `:rf/default` (the EP-0002 carried invariant; the common, benign case —
      NOT corruption);
    - a frame keyword (or Reagent's prop-stringified-keyword shape, via
      `coerce-context-value`) names the enclosing Provider's frame;
    - anything else (false, a number, an empty string, a JS object) means the
      React-context boundary was disturbed (rf2-8q66) — a portal rendering
      outside its Provider, a library mutating the slot, or a Provider authored
      with a non-keyword value. The runtime emits
      `:rf.error/frame-context-corrupted` and returns nil (recovery
      `:no-frame-context`).

  Does NOT observe the dynamic tier (`rf.frame/*current-frame*`) — its callers
  layer that precedence in front of it."
  [v]
  (cond
    (= v no-provider-sentinel) nil
    (coerce-context-value v)   (coerce-context-value v)
    :else                      (do (emit-frame-context-corrupted! v)
                                   nil)))

(defn function-component-current-frame
  "Resolution chain (READER) for function-component substrates (UIx).
  Returns the scope frame, or **nil** when no scope is
  established — it never synthesises `:rf/default` (per Spec 002 §Frame
  target resolution — the carried invariant, EP-0002). The two tiers it
  observes:

    1. `re-frame.frame/*current-frame*` (dynamic var) — set by
       `with-frame` / `bind-fn`.
    2. The closest enclosing frame boundary via React context — either a
       `frame-provider` (SCOPE) or a `frame-root` (ENSURE); both install
       the same shared context, so a read resolves identically beneath
       either. Reads the ACTIVE value slot off the shared context object
       directly (the substrate-portable path; UIx's `use-context` is
       sugar over this read — which slot is active depends on the renderer,
       see the NOTE below) and classifies the value it selected, ONCE,
       through the shared [[context-value->current-frame]] rules.

  Returns nil when neither tier names a frame — a component rendered
  beneath neither frame boundary observes the no-provider sentinel, which
  resolves to nil ('no scope'). Public frame-scoped operations turn that
  nil into a loud `:rf.error/no-frame-context` via
  `rf.frame/require-current-frame!`; low-level readers / tooling model 'no
  context' with the nil directly.

  NOTE (rf2-2rzx0, repaired rf2-5rqn): React carries TWO value slots per
  context — `_currentValue` (primary; the client renderer) and
  `_currentValue2` (secondary; React 19.2's `react-dom/server` writes and
  reads THIS one). So tier 2 reads the primary slot and falls back to the
  secondary ONLY when the primary holds the untouched no-provider sentinel.
  Under the client renderer the secondary slot is never written, so it still
  holds `createContext`'s default — the same sentinel — and the fallback is
  inert. Callers that can observe the renderer-agnostic value (a component
  reading `useContext` inside render, e.g. the compiled ViewCell) may pass
  that value to [[context-value->current-frame]] directly instead."
  []
  (or rf.frame/*current-frame*
      (let [primary (.-_currentValue ^js frame-context)]
        (context-value->current-frame
          (if (= primary no-provider-sentinel)
            (.-_currentValue2 ^js frame-context)
            primary)))))
