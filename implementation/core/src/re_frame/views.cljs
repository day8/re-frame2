(ns re-frame.views
  "Views — reg-view, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  Form-1 (a render fn) is the canonical view shape. Form-2/3 are
  supported via Reagent's native handling.

  reg-view auto-defs the local Var (per Spec 004 §reg-view defs the Var
  by default); the Var is the canonical call-site reference. For
  late-binding by id (e.g. across module boundaries, runtime-computed
  ids, or hot-reload semantics), call `(re-frame.core/view :id)`
  to obtain the wrapped fn and use `[(rf/view :id) args]` as the
  hiccup head.

  Per rf2-lh7p the file has been split into three internally-cohesive
  sub-namespaces; this ns is now the orchestration entry point that
  ties them together:

    - `re-frame.views.provider`                — frame-provider + the
                                                 React-context bridge
                                                 and per-render
                                                 instance-token
                                                 machinery
    - `re-frame.views.source-coord-annotation` — Spec 006 source-coord
                                                 DOM annotation walk
    - `re-frame.views.warn-once`               — per-process warn-once
                                                 caches (non-DOM root,
                                                 plain-fn-under-non-
                                                 default-frame)

  The publicly-referenced surface is re-exported via plain `def` aliases
  below so existing call sites — `re-frame.core/reg-view*`, the test
  files, the late-bind hook table, and the adapter ns docstrings —
  continue to resolve through this ns unchanged.

  The `*render-key*` dynamic var lives in THIS ns (rather than under
  `re-frame.views.provider` alongside the rest of the instance-token
  machinery) because tests read it via `re-frame.views/*render-key*`
  from inside a render-fn while the wrapper below binds the same Var.
  Putting the canonical Var here makes the read and the binding hit
  identical Vars — a `(def ^:dynamic *render-key* provider/*render-key*)`
  re-export would create a SECOND Var whose binding the test wouldn't
  observe."
  (:require [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace :include-macros true]
            [re-frame.views.provider :as provider]
            [re-frame.views.source-coord-annotation :as source-coord]
            [re-frame.views.warn-once :as warn-once]))

;; ---- *render-key* (rf2-piag / rf2-t5tx) ----------------------------------
;;
;; Bound by the `reg-view*` wrapper below for the duration of each render.
;; Lives here (the public ns) so `re-frame.views/*render-key*` is the
;; canonical Var that wrapper-binding and consumer-reads share — see
;; the ns docstring above for why this can't move under provider.

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
;;
;; Plain `def` aliases for the publicly-referenced ordinary fns / data
;; surfaces. Each one is reached by external call sites through
;; `re-frame.views/<name>`:
;;
;;   re-frame.adapter.reagent + runtime_cljs_test → frame-provider, build-frame-provider
;;   late-bind hook table (:adapter/current-frame),
;;     views-current-component-cljs-test          → current-frame
;;   render_key_cljs_test, elision_probe          → mint-instance-token!
;;   source_coord_parity_cljs_test                → #'format-source-coord
;;   warn_once_fixture_isolation_cljs_test        → clear-warned-non-dom-roots!
;;   late-bind hook table                         → maybe-warn-plain-fn-under-non-default-frame!,
;;                                                  clear-plain-fn-warned-pairs!
;;
;; Keeping these as plain defs (rather than `:refer`-imported) makes
;; `#'re-frame.views/<name>` resolve in this ns — the CLJS analyser
;; tracks each `def` form's Var under the defining ns, so
;; `:refer` does not surface a Var under the consuming ns. Functions are
;; resolved by value, so a `def` alias works for invocation; only
;; dynamic-var binding semantics force the `*render-key*` exception
;; above.

(def frame-provider provider/frame-provider)
(def build-frame-provider provider/build-frame-provider)
(def current-frame provider/current-frame)
(def mint-instance-token! provider/mint-instance-token!)

(def format-source-coord source-coord/format-source-coord)

(def clear-warned-non-dom-roots! warn-once/clear-warned-non-dom-roots!)
(def clear-plain-fn-warned-pairs! warn-once/clear-plain-fn-warned-pairs!)
(def maybe-warn-plain-fn-under-non-default-frame!
  warn-once/maybe-warn-plain-fn-under-non-default-frame!)

;; The React-context object is consumed by `reg-view*` below (the
;; `:contextType` static-field) and by the warn-once helpers in
;; `re-frame.views.warn-once`. Aliased privately here for parity with
;; the pre-split shape — no external caller reaches for it.
(def ^:private frame-context provider/frame-context)

(defn- emit-render-trace!
  "Emit a `:view/render` trace event tagged with the in-flight
  `:render-key`. The trace also carries the `:frame` tag so the
  epoch-capture buffer (per re-frame.epoch §capture-event!) can route
  the render into the right per-frame cascade. Goes through late-bind
  so this ns doesn't depend on re-frame.trace (which itself routes
  through late-bind for registrar/views ordering reasons). Production
  builds elide via the `interop/debug-enabled?` gate the trace surface
  itself rides."
  [render-key]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn :trace/emit!)]
      (emit! :view :view/render
             {:render-key render-key
              :frame      (provider/current-frame)}))))

;; ---- reg-view -------------------------------------------------------------

(defn reg-view*
  "Reagent-aware view registration. Wraps `render-fn` with the React
  `:contextType` static-field metadata used to resolve the surrounding
  frame at render time, then registers it in the :view kind of the
  registrar.

  Per Spec 004 §reg-view*: this is the plain-fn surface delegated to
  by `re-frame.core/reg-view*` on CLJS. `metadata` is merged into the
  registry slot's metadata as-is; source-coord capture is performed
  by the caller (`re-frame.core/reg-view*`).

  Note (rf2-kdwc): Reagent's create-class / fn-to-class machinery
  recognises `:contextType` (camelCase, the React static-field name),
  not `:context-type` (kebab). The earlier shape used the kebab key
  and was silently ignored, which is why frame-provider context
  resolution fell back to :rf/default.

  Per rf2-piag / rf2-t5tx: each render binds `*render-key*` to the
  tuple `[id instance-token]` for the body, so the trace recorder can
  attribute the render. The instance-token is minted at mount and
  reused across re-renders of the same component instance (per
  Spec 004 §Render-tree primitives).

  Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1): when
  `interop/debug-enabled?` is true, the wrapper merges
  `:data-rf2-source-coord` onto the rendered root DOM element. The
  attribute value carries `<ns>:<sym>:<line>:<col>`, derived from the
  registry id and the coords captured by the reg-view macro at
  expansion time. Production builds elide the entire annotation
  branch via the `interop/debug-enabled?` gate.

  Per rf2-00li: substrates whose `render-fn` returns React elements
  (UIx, Helix) cannot be served by the hiccup-shape `inject-source-
  coord-attr` walk below — a React element is neither a hiccup
  vector nor a fn, so the walk would mis-classify it as a non-DOM
  root and skip annotation with a one-shot warning. Those adapters
  publish a substrate-side `wrap-view` through the `:adapter/wrap-
  view` late-bind hook. When the hook is set, the substrate-supplied
  wrap-view replaces the inline annotation path: it wraps `render-fn`
  so each call returns a React element with `data-rf2-source-coord`
  injected via `React.cloneElement`. The Reagent adapter does NOT
  publish the hook (it has no React-side walk needed); the inline
  hiccup walk continues to serve it."
  [id metadata render-fn]
  (let [adapter-wrap-view (late-bind/get-fn :adapter/wrap-view)
        ;; rf2-00li: if an adapter has registered a substrate-side
        ;; wrap-view (UIx, Helix), call it to wrap render-fn before
        ;; binding into the frame-aware-view. This replaces the inline
        ;; hiccup-shape walk for substrates whose render-fn output is
        ;; a React element.
        ;;
        ;; The hook may be registered (e.g. test bundle loaded UIx +
        ;; Helix adapter ns's) yet return nil — each adapter's routing
        ;; closure returns nil when its own adapter is NOT the
        ;; installed one (per rf2-0d35), so the chain bottoms out at
        ;; nil when the Reagent adapter is installed even though
        ;; UIx + Helix have published into the hook. A nil from the
        ;; hook means "no substrate wrap applied" — keep render-fn
        ;; unchanged and run the inline hiccup walk (the Reagent
        ;; behaviour) below.
        ;;
        ;; The adapter's wrap-view body itself sits inside
        ;; `(when interop/debug-enabled? ...)`, so under :advanced +
        ;; goog.DEBUG=false the wrapped fn collapses to the bare
        ;; user-fn (no cloneElement) — keeping the elision contract.
        wrapped-by-adapter (when adapter-wrap-view
                             (adapter-wrap-view id metadata render-fn))
        wrap-applied?      (some? wrapped-by-adapter)
        render-fn          (if wrap-applied? wrapped-by-adapter render-fn)
        coord-attr (when (and interop/debug-enabled? (not wrap-applied?))
                     (source-coord/format-source-coord id metadata))
        ;; Per rf2-ryri7: pre-compute the view's HandlerScope once at
        ;; registration time. The registrar `metadata` map carries the
        ;; source-coord stamp, `:sensitive?`, and `:rf.trace/no-emit?`
        ;; readings — all three derive from meta and are fixed for the
        ;; life of the registered view. Each render binds the scope
        ;; (via `with-handler-scope`, which inherits parent's
        ;; `:call-site` / `:dispatch-id`) around the user render-fn
        ;; invocation. Errors emitted during render (subscribe-miss
        ;; against this frame, sub exception during a render-time
        ;; deref, etc.) ride the view's `:trigger-handler` coord;
        ;; `:view/render` emits ride `:sensitive?` per Spec 009
        ;; §Privacy and short-circuit when `:no-emit?` is true.
        view-scope (trace/handler-scope-from-meta :view id metadata)
        wrapped (with-meta
                  (fn frame-aware-view [& args]
                    (let [tok        (provider/reagent-component-token)
                          render-key [id tok]]
                      (binding [*render-key* render-key]
                        (trace/with-handler-scope view-scope
                          (emit-render-trace! render-key)
                          ;; Per Spec 009 §Performance instrumentation
                          ;; (rf2-du3i): every render of a registered
                          ;; view brackets the user render-fn in
                          ;; performance marks so prod builds with the
                          ;; perf flag enabled produce a
                          ;; `rf:render:<view-id>` measure entry.
                          ;; Default-off; under :advanced +
                          ;; `re-frame.performance/enabled?=false` the
                          ;; bracket DCEs and the form collapses to the
                          ;; bare `(apply render-fn args)` call.
                          (let [out (performance/mark-and-measure :render id
                                      (apply render-fn args))]
                            (if (and interop/debug-enabled? (not wrap-applied?))
                              (source-coord/inject-source-coord-attr id coord-attr out)
                              out))))))
                  {:contextType frame-context})]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))
