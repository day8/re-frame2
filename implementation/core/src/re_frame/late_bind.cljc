(ns re-frame.late-bind
  "Late-binding hook registry for cross-namespace forward references.

  Some leaf namespaces (re-frame.frame, re-frame.fx, re-frame.cofx,
  re-frame.subs, re-frame.routing, re-frame.router) need to call into
  higher-level namespaces (re-frame.router, re-frame.flows,
  re-frame.schemas, re-frame.subs) but cannot `:require` them without
  introducing a cyclic load order.

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
    :flows/reg-flow-fx!       re-frame.flows/reg-flow-fx!
    :flows/clear-flow-fx!     re-frame.flows/clear-flow-fx!
    :flows/run-flows!         re-frame.flows/run-flows!
    :schemas/validate-event!     re-frame.schemas/validate-event!
    :schemas/validate-app-db!    re-frame.schemas/validate-app-db!
    :schemas/validate-cofx!      re-frame.schemas/validate-cofx!
    :schemas/validate-sub-return! re-frame.schemas/validate-sub-return!
    :schemas/frame-schema-entries re-frame.schemas/frame-schema-entries
    :schemas/app-schemas-digest  re-frame.schemas/app-schemas-digest
    :subs/subscribe-value     re-frame.subs/subscribe-value
    :ssr/render-tree-hash     re-frame.ssr/render-tree-hash
    :epoch/settle!            re-frame.epoch/settle!
    :epoch/discard-buffer!    re-frame.epoch/discard-buffer!
    :epoch/in-flight-buffer   re-frame.epoch/in-flight-buffer
    :epoch/capture-event      re-frame.epoch/capture-event!"}
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
