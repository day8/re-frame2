(ns day8.re-frame2-xray.views.edn-inspector-protocol
  "Custom-formatters protocol for the Xray edn-inspector widget.

  ## Why this exists

  The widget is a *closed* renderer — it classifies every built-in
  CLJS shape natively (maps, vectors, sets, records, map-entries,
  sentinels, scalars). That covers the universe of shapes Xray itself
  produces. Consuming applications, however, may
  carry **domain types** (typed records, custom deftypes, opaque
  wrappers around external library objects) that the closed renderer
  has no better fallback for than `pr-str`.

  This protocol opens an extension seam *without* opening the
  internals of the renderer. A consuming type implements
  `IXrayEdnInspector`; the widget checks `satisfies?` at the top of
  every `render-node` call and, if true, defers to the consumer's
  two methods. Otherwise the closed renderer's built-in dispatch
  runs unchanged.

  ## Contract

      (render-header v opts)  ;; hiccup for the header row (inline,
                              ;; collapsed-summary, or the open
                              ;; bracket — whatever the consumer
                              ;; wants visually at the node's head).
                              ;; Returning nil falls back to the
                              ;; built-in renderer for that node.

      (render-body v opts)    ;; hiccup for the expanded body, or
                              ;; nil for a header-only render.
                              ;; The widget wraps the body in the
                              ;; standard indented container
                              ;; (left-rule + 14px padding).

  Both methods receive the same `opts` map the widget itself
  threads through `render-node` — `:panel-id`, `:mount-id`, `:path`,
  `:depth`, `:expansion-map`, `:default-expanded-depth`,
  `:max-inline-width`, `:max-depth`. Consumer impls are FREE to
  ignore everything they don't need; they SHOULD honour `:path` /
  `:panel-id` / `:mount-id` if they want their own toggle behaviour
  to compose with the widget's expansion slot (`expansion-key`
  in `edn-inspector.cljs` is the public composer).

  ## Fall-through

  - `(satisfies? IXrayEdnInspector v)` false → built-in renderer.
  - `render-header` returns `nil` → built-in renderer for this
    node (skips the protocol entirely for the node, including
    the body).
  - `render-body` returns `nil` + header non-nil → header-only
    render (no expanded body, no toggle).

  This three-state fall-through lets a consumer override just the
  header (e.g. add a custom tag) while still using the built-in
  body, or vice versa, without splitting the protocol into four
  methods.

  ## Boundary

  The protocol is the ONLY public extension seam in the edn-inspector
  widget. Per the B.9 interaction model the widget itself ships
  exactly one path-click interaction; consumers do NOT extend
  interactions through this protocol — only the visual rendering of
  values.

  ## Spec

  See `tools/xray/spec/021-Dynamic-Panel-Designs.md` §10.0.6 for
  the normative description + a worked example."
  )

(defprotocol IXrayEdnInspector
  "Custom-formatters protocol for the Xray edn-inspector widget.
  Consumers implement this on their domain types to override how
  the widget renders them. See the ns docstring for the contract.

  Both methods MAY return `nil` to fall back to the built-in
  renderer at that step."
  (-xray-render-header [v opts]
    "Return hiccup for the node's header (collapsed summary or
    inline content). Nil falls through to the built-in renderer for
    the whole node.")
  (-xray-render-body [v opts]
    "Return hiccup for the node's expanded body. Nil suppresses
    the body (header-only render)."))

(defn xray-render-header
  "Safe accessor — returns the consumer's `-xray-render-header`
  output, or nil if `v` does not satisfy the protocol. Catches
  arbitrary errors from consumer impls so a broken third-party
  formatter can never blank the whole inspector."
  [v opts]
  (when (satisfies? IXrayEdnInspector v)
    (try (-xray-render-header v opts)
         (catch :default _ nil))))

(defn xray-render-body
  "Safe accessor — see `xray-render-header`."
  [v opts]
  (when (satisfies? IXrayEdnInspector v)
    (try (-xray-render-body v opts)
         (catch :default _ nil))))

(defn satisfies-xray-edn-inspector?
  "Predicate convenience — true iff `v` satisfies `IXrayEdnInspector`.
  Exists so callers don't need to `:refer` the protocol symbol when
  all they need is the gate."
  [v]
  (satisfies? IXrayEdnInspector v))
