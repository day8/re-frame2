(ns re-frame.late-bind
  "Late-binding hook registry for cross-namespace forward references.

  Some leaf namespaces (re-frame.frame, re-frame.fx, re-frame.cofx,
  re-frame.subs, re-frame.routing, re-frame.router) need to call into
  higher-level namespaces (re-frame.router, re-frame.flows,
  re-frame.schemas, re-frame.subs) but cannot `:require` them without
  introducing a cyclic load order.

  Per rf2-p7va the same mechanism carries cross-artefact references:
  `re-frame.schemas` ships in a separate Maven artefact
  (day8/re-frame2-schemas), so re-frame.core / re-frame.test-support
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
  loaded — and registers its hooks — before any user code runs.)

  The authoritative inventory of every published key lives in
  `re-frame.late-bind.directory` — plain CLJC data, one entry per
  hook key, with the producer ns, design bead, and one-line
  description. The drift test
  `implementation/core/test/re_frame/late_bind_drift_test.clj`
  asserts the directory and the `set-fn!` call sites stay in sync.")

(defonce
  ^{:doc "Map of hook-key → fn. Populated by the producing namespace at
   load time. The authoritative key inventory is
   `re-frame.late-bind.directory/hooks`; the drift test enforces it
   matches the in-tree `set-fn!` call sites."}
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
