(ns re-frame.adapter.sub-override-context
  "Shared React context carrying a Story render's `:sub-overrides` map into
  a DESCENDANT view's deferred render (rf2-7pgiz).

  ## Why a React context (not a dynamic var)

  Story's lowest-fidelity ladder rung lets a designer pin a view into an
  `:error` / `:loading` / `:empty` state by naming subscription
  query-vectors and the values they should surface — no events, no
  app-db (`tools/story/spec/017-Testing-Story.md` §View-state
  subscription overrides). A normally-authored view reaches its subs via
  `@(rf/subscribe [:q])`, so the override map has to survive from the
  Story render-scope component into the view's OWN reaction, several
  component layers deeper. A `binding`-bound dynamic var does NOT survive
  that boundary — the binding unwinds before the descendant's deferred
  render runs (empirically confirmed under react-dom/server; see
  rf2-7pgiz). React mutates a context's `_currentValue` as Provider
  boundaries are entered/exited during render, so a read from inside any
  descendant render sees the closest enclosing Provider's value. This is
  the exact mechanism `re-frame.adapter.context` uses to propagate the
  frame-id; this ns mirrors it for the override map.

  ## Why it lives in core (CLJS-only)

  Core already `:require`s React directly via `re-frame.views`, so this
  file adds no new transitive runtime dep. It is CLJS-only
  (`re_frame/adapter/sub_override_context.cljs`) — the JVM build never
  loads it, mirroring `re-frame.adapter.context`. Core only DECLARES the
  carriage + the consult; the resolver that READS this context is
  published from the Story side via the `:subs/resolve-sub-override`
  late-bind hook, so core stays tools-free and bundle-isolation holds.

  ## Production cost

  The override map is consulted in `re-frame.subs/subscribe` ONLY inside
  the existing `(when interop/debug-enabled? ...)` envelope, so the whole
  seam DCEs under `:advanced` + `goog.DEBUG=false`. This context object
  is constructed at module-init regardless (a single
  `React.createContext` call, like `frame-context`), but it is never
  READ on the production path because the consult is elided.

  ## Honesty boundary

  The override map carried here feeds ONLY the derefed reaction a view
  sees — it never writes app-db and never reaches `compute-sub`. See
  rf2-7pgiz and `re-frame.story.sub-overrides`."
  (:require ["react" :as React]
            [re-frame.interop :as interop]))

(defonce override-context
  ;; Default `nil` — no overrides in scope. A descendant `subscribe`
  ;; consult against an unwrapped tree reads `nil` and misses, so the
  ;; view reads its real subscription. Mirrors `frame-context`'s
  ;; `:rf/default` defaulting (here the "no Provider" value is nil).
  (.createContext React nil))

;; rf2-fa4ly parity: stamp a human-readable `displayName` so React
;; DevTools' Context inspector labels the entry `rf2-sub-overrides`
;; rather than the opaque default. Dev-only — gated under
;; `interop/debug-enabled?` so the string literal DCEs in production
;; (same pattern as `re-frame.adapter.context`'s `rf2-frame` stamp).
(when interop/debug-enabled?
  (set! (.-displayName ^js override-context) "rf2-sub-overrides"))

(defn current-overrides
  "Read the closest enclosing Provider's override map off the shared
  context object's `_currentValue` (the substrate-portable path that
  UIx/Helix `use-context` and Reagent's `:contextType` read are both
  sugar over). Returns the `{query-vector value}` map, or nil outside
  any Story override-scope Provider.

  Mirrors `re-frame.adapter.context/function-component-current-frame`'s
  `_currentValue` read; no coercion is needed because the value is a
  plain CLJS map (or nil), never a prop-stringified keyword."
  []
  (.-_currentValue ^js override-context))
