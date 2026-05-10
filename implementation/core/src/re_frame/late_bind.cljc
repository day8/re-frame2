(ns re-frame.late-bind
  "Late-binding hook registry for cross-namespace forward references.

  Some leaf namespaces (re-frame.frame, re-frame.fx, re-frame.cofx,
  re-frame.subs, re-frame.routing, re-frame.router) need to call into
  higher-level namespaces (re-frame.router, re-frame.flows,
  re-frame.schemas, re-frame.subs) but cannot `:require` them without
  introducing a cyclic load order.

  Per rf2-p7va the same mechanism carries cross-artefact references:
  `re-frame.schemas` ships in a separate Maven artefact
  (day8/re-frame-2-schemas), so re-frame.core / re-frame.test-support
  MUST NOT statically `:require` it — they look the schemas API up
  through the hook table at call time. When the schemas artefact is
  not on the classpath, the lookup returns nil and the consumer
  no-ops (or, in the case of `rf/reg-app-schema`, throws a clear
  `:rf.error/schemas-artefact-missing` so the misconfiguration is
  surfaced rather than silently absorbed).

  On the JVM we historically used `(resolve 'ns/sym)` to defer the
  lookup to runtime. That works on the JVM because `clojure.core/resolve`
  is a runtime fn — but ClojureScript has no runtime `resolve` (the
  symbol exists only as a compile-time analyzer affordance), so every
  CLJS call site silently no-op'd. That manifested as e.g.
  `(rf/make-frame {:on-create [:foo]})` returning a frame whose app-db
  was never seeded by `:on-create`, and `:fx [[:dispatch [...]]]`
  becoming a no-op.

  This namespace replaces those `resolve` calls with an explicit hook
  registry. The producing namespace registers its callable at load
  time via `set-fn!`; the consuming namespace fetches it via `get-fn`
  at call time. Identical behaviour on JVM and CLJS.

  Per Spec 002 §reg-frame is atomic: `:on-create` runs synchronously
  inside `reg-frame`; `make-frame` is a thin wrapper, so by the time
  `make-frame` returns the frame's app-db reflects the init handler's
  commits. Callers MUST register the `:on-create` event's handler
  BEFORE calling `make-frame` / `reg-frame`. (When the JVM/CLJS host
  loads `re-frame.core`, the canonical re-frame.router namespace is
  loaded — and registers its hooks — before any user code runs.)")

(defonce
  ^{:doc "Map of hook-key → fn. Populated by the producing namespace at
  load time. The set of keys is documented at each call site; v1 keys:

    :router/dispatch!         re-frame.router/dispatch!
    :router/dispatch-sync!    re-frame.router/dispatch-sync!
    :flows/reg-flow            re-frame.flows/reg-flow
    :flows/clear-flow          re-frame.flows/clear-flow
    :flows/reg-flow-fx!        re-frame.flows/reg-flow-fx!
    :flows/clear-flow-fx!      re-frame.flows/clear-flow-fx!
    :flows/run-flows!          re-frame.flows/run-flows!
    :flows/reset-last-inputs!  re-frame.flows/reset-last-inputs!
    :flows/reset-flows!        re-frame.flows/reset-flows!
    :schemas/validate-event!      re-frame.schemas/validate-event!
    :schemas/validate-app-db!     re-frame.schemas/validate-app-db!
    :schemas/validate-cofx!       re-frame.schemas/validate-cofx!
    :schemas/validate-sub-return! re-frame.schemas/validate-sub-return!
    :schemas/frame-schema-entries re-frame.schemas/frame-schema-entries
    :schemas/reg-app-schema       re-frame.schemas/reg-app-schema
    :schemas/app-schema-at        re-frame.schemas/app-schema-at
    :schemas/app-schemas          re-frame.schemas/app-schemas
    :schemas/app-schemas-digest   re-frame.schemas/app-schemas-digest
    :schemas/snapshot-by-frame    re-frame.schemas/snapshot-schemas-by-frame
    :schemas/restore-by-frame!    re-frame.schemas/restore-schemas-by-frame!
    :schemas/clear-by-frame!      re-frame.schemas/clear-schemas-by-frame!
    :schemas/set-schema-validator!         re-frame.schemas/set-schema-validator! ;; rf2-froe — pluggable validator
    :schemas/set-schema-explainer!         re-frame.schemas/set-schema-explainer! ;; rf2-froe — companion explainer
    :schemas/validate-with-registered-fn   re-frame.schemas/validate-with-registered-fn ;; rf2-r2uh boundary seam
    :schemas/explain-with-registered-fn    re-frame.schemas/explain-with-registered-fn  ;; rf2-r2uh boundary seam
    :machines/reg-machine             re-frame.machines/reg-machine* ;; rf2-8bp3 — plain-fn surface
    :machines/create-machine-handler  re-frame.machines/create-machine-handler
    :machines/machine-transition      re-frame.machines/machine-transition
    :machines/machines                re-frame.machines/machines
    :machines/machine-meta            re-frame.machines/machine-meta
    :machines/machine-by-system-id    re-frame.machines/machine-by-system-id
    :machines/reset-counters!         re-frame.machines/reset-counters!
    :machines/spawn-fx                re-frame.machines/spawn-fx
    :machines/destroy-machine-fx      re-frame.machines/destroy-machine-fx
    :routing/reg-route                re-frame.routing/reg-route
    :routing/match-url                re-frame.routing/match-url
    :routing/route-url                re-frame.routing/route-url
    :routing/reset-counters!          re-frame.routing/reset-counters!
    :routing/route-sub-fn             re-frame.routing/route-sub-fn
    :http/install-managed-request-stubs!   re-frame.http-managed/install-managed-request-stubs!
    :http/uninstall-managed-request-stubs! re-frame.http-managed/uninstall-managed-request-stubs!
    :http/with-managed-request-stubs*      re-frame.http-managed/with-managed-request-stubs*
    :http/clear-all-in-flight!             re-frame.http-managed/clear-all-in-flight!
    :http/reg-http-interceptor             re-frame.http-managed/reg-http-interceptor       ;; rf2-6y3q
    :http/clear-http-interceptor           re-frame.http-managed/clear-http-interceptor     ;; rf2-6y3q
    :http/clear-all-http-interceptors!     re-frame.http-managed/clear-all-http-interceptors! ;; rf2-6y3q
    :subs/subscribe-value     re-frame.subs/subscribe-value
    :ssr/render-tree-hash        re-frame.ssr/render-tree-hash
    :ssr/render-to-string        re-frame.ssr/render-to-string
    :ssr/reg-error-projector     re-frame.ssr/reg-error-projector
    :ssr/project-error           re-frame.ssr/project-error
    :reagent/set-hiccup-emitter! re-frame.adapter.reagent/set-hiccup-emitter!
    :views/maybe-warn-plain-fn-under-non-default-frame!  re-frame.views/maybe-warn-plain-fn-under-non-default-frame!
    :views/clear-plain-fn-warned-pairs!                  re-frame.views/clear-plain-fn-warned-pairs!
    :adapter/current-frame    re-frame.views/current-frame                                       ;; rf2-d4sf — Reagent path; class-component (.-context cmp)
                              re-frame.adapter.context/function-component-current-frame          ;;          — UIx / Helix path; function-component _currentValue read
                                                                                                  ;; Published by the active adapter ns at load time so subscribe / dispatch consult the React-context tier of the resolution chain. Signature: (fn []) -> frame-id keyword.
    :epoch/settle!            re-frame.epoch/settle!
    :epoch/discard-buffer!    re-frame.epoch/discard-buffer!
    :epoch/in-flight-buffer   re-frame.epoch/in-flight-buffer
    :epoch/capture-event      re-frame.epoch/capture-event!
    :epoch/epoch-history      re-frame.epoch/epoch-history
    :epoch/restore-epoch      re-frame.epoch/restore-epoch
    :epoch/register-epoch-cb  re-frame.epoch/register-epoch-cb!
    :epoch/remove-epoch-cb    re-frame.epoch/remove-epoch-cb!
    :epoch/configure!         re-frame.epoch/configure!
    :epoch/clear-history!     re-frame.epoch/clear-history!
    :epoch/clear-epoch-cbs!   re-frame.epoch/clear-epoch-cbs!
    :epoch/on-frame-destroyed re-frame.epoch/on-frame-destroyed!"}
  hooks
  (atom {}))

(defn set-fn!
  "Register a fn under hook-key. The producing namespace calls this at
  the bottom of its file; consumers look it up via `get-fn`."
  [hook-key f]
  (swap! hooks assoc hook-key f)
  nil)

(defn get-fn
  "Return the fn registered under hook-key, or nil if no producer has
  registered it yet. Callers MUST handle the nil case (the common
  pattern is `(when-let [f (late-bind/get-fn ...)] (f args))`)."
  [hook-key]
  (get @hooks hook-key))
