(ns re-frame.views
  "Views — reg-view, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  This ns is the orchestration entry point; the implementation lives in
  three cohesive sub-namespaces re-exported below:

    - `re-frame.views.provider`                — frame-provider, React-
                                                 context bridge, per-
                                                 render instance-token
    - `re-frame.views.source-coord-annotation` — Spec 006 source-coord
                                                 DOM annotation walk
    - `re-frame.views.warn-once`               — per-process warn-once
                                                 caches

  `*render-key*` lives in this ns rather than under provider because
  tests read it via `re-frame.views/*render-key*` while the wrapper
  below binds the same Var. A `def` re-export would create a SECOND
  Var whose binding the test wouldn't observe."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace :include-macros true]
            [re-frame.views.provider :as provider]
            [re-frame.views.source-coord-annotation :as source-coord]
            [re-frame.views.warn-once :as warn-once]))

(def ^:dynamic *render-key*
  "The `:render-key` for the in-flight render — a tuple
  `[<view-id> <instance-token>]`. Bound by the wrapper emitted by
  `reg-view*` for the duration of each render. Nil outside a registered
  view's render (the trace recorder treats nil as
  `[:rf.view/anonymous nil]` per Spec 004 §Render-tree primitives)."
  nil)

(defn current-render-key
  "Return the `:render-key` for the in-flight render, or
  `[:rf.view/anonymous nil]` when none is bound (e.g. inside a plain
  Reagent fn that bypassed reg-view). Per Spec 004 §Render-tree
  primitives — the anonymous fallback is the documented unbound-shape."
  []
  (or *render-key* [:rf.view/anonymous nil]))

;; ---- re-exported public surface ------------------------------------------
;; Plain `def` aliases so `#'re-frame.views/<name>` resolves in this ns
;; (a `:refer` would surface the Var under the producing ns, not here).

(def frame-provider provider/frame-provider)
(def build-frame-provider provider/build-frame-provider)
(def current-frame provider/current-frame)
(def mint-instance-token! provider/mint-instance-token!)

(def format-source-coord source-coord/format-source-coord)

(def clear-warned-non-dom-roots! warn-once/clear-warned-non-dom-roots!)
(def clear-plain-fn-warned-pairs! warn-once/clear-plain-fn-warned-pairs!)
(def maybe-warn-plain-fn-under-non-default-frame!
  warn-once/maybe-warn-plain-fn-under-non-default-frame!)

(def ^:private frame-context provider/frame-context)

(defn- emit-render-trace!
  "Emit a `:view/render` trace event tagged with the in-flight
  `:render-key` and current `:frame` (so the epoch capture buffer routes
  it to the right per-frame cascade). Goes through late-bind to keep
  this ns out of the re-frame.trace dep graph. Elided in production via
  `interop/debug-enabled?`."
  [render-key]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn :trace/emit!)]
      (emit! :view :view/render
             {:render-key render-key
              :frame      (provider/current-frame)}))))

;; ---- reg-view -------------------------------------------------------------

(defn- apply-adapter-wrap-view
  "Consult the `:adapter/wrap-view` late-bind hook for a substrate-side
  wrap. Returns `[render-fn wrap-applied?]`.

  UIx / Helix register a wrap-view because their render-fn output is a
  React element — neither hiccup nor a fn — so the inline hiccup walk
  would mis-classify the root; their wrap-view injects
  `data-rf2-source-coord` via `React.cloneElement`. The Reagent adapter
  does NOT publish the hook.

  The hook may be registered yet return nil — each adapter's routing
  closure short-circuits when its adapter is not the installed one — so
  a nil result means \"no substrate wrap applied; fall through to the
  inline walk\". The wrap body itself rides `(when interop/debug-enabled?
  ...)`, so under :advanced + goog.DEBUG=false it DCEs."
  [id metadata render-fn]
  (let [hook    (late-bind/get-fn :adapter/wrap-view)
        wrapped (when hook (hook id metadata render-fn))]
    (if (some? wrapped)
      [wrapped true]
      [render-fn false])))

(defn- view-coord-attr
  "Capture the source-coord stamp for the inline hiccup-walk
  annotation path (Spec 006 §Source-coord annotation). Returns nil
  under :advanced + goog.DEBUG=false, and also nil when the substrate
  hook has wrapped render-fn (its cloneElement path supersedes the walk)."
  [id metadata wrap-applied?]
  (when (and interop/debug-enabled? (not wrap-applied?))
    (source-coord/format-source-coord id metadata)))

(defn- build-frame-aware-view
  "Build the per-render wrapped fn that ties view registration into
  Reagent: each render binds `*render-key*` and `*handler-scope*`,
  emits the `:view/render` trace, brackets the user render-fn in
  performance marks, and (on the Reagent inline path) annotates the
  rendered hiccup root with the source-coord attribute.

  The returned fn carries `{:contextType frame-context}` meta — the
  camelCase static-field name is load-bearing; Reagent silently ignores
  the kebab `:context-type` shape."
  [id render-fn view-scope coord-attr wrap-applied?]
  (with-meta
    (fn frame-aware-view [& args]
      (let [tok        (provider/reagent-component-token)
            render-key [id tok]]
        (binding [*render-key* render-key]
          (trace/with-handler-scope view-scope
            (emit-render-trace! render-key)
            ;; Spec 009 §Performance instrumentation: when the perf
            ;; flag is enabled the bracket emits a `rf:render:<view-id>`
            ;; measure. Default-off; under :advanced +
            ;; `re-frame.performance/enabled?=false` the bracket DCEs.
            (let [out (performance/mark-and-measure :render id
                        (apply render-fn args))]
              (if (and interop/debug-enabled? (not wrap-applied?))
                (source-coord/inject-source-coord-attr id coord-attr out)
                out))))))
    {:contextType frame-context}))

(defn reg-view*
  "Reagent-aware view registration. Wraps `render-fn` with the React
  `:contextType` static-field metadata used to resolve the surrounding
  frame at render time, then registers it in the :view kind of the
  registrar.

  Spec 004 §reg-view*: the plain-fn surface delegated to by
  `re-frame.core/reg-view*` on CLJS. `metadata` is merged into the
  registry slot as-is; source-coord capture is performed by the caller.

  Each render binds `*render-key*` to `[id instance-token]` so the
  trace recorder can attribute the render. The instance-token is minted
  at mount and reused across re-renders of the same component instance
  (Spec 004 §Render-tree primitives).

  The view's HandlerScope is pre-computed once at registration time
  from `metadata` (source-coord stamp, `:sensitive?`, `:rf.trace/no-emit?`
  — all fixed for the life of the registered view). Each render binds
  the scope around the user render-fn invocation, so errors emitted
  during render ride the view's `:trigger-handler` coord and
  `:view/render` emits honour `:sensitive?` / `:no-emit?`."
  [id metadata render-fn]
  (let [[render-fn wrap-applied?] (apply-adapter-wrap-view id metadata render-fn)
        coord-attr (view-coord-attr id metadata wrap-applied?)
        view-scope (trace/handler-scope-from-meta :view id metadata)
        wrapped    (build-frame-aware-view id render-fn view-scope coord-attr wrap-applied?)]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))
