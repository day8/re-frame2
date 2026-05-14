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

#?(:clj (set! *warn-on-reflection* true))

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

(defn chain-fn!
  "Wire `step-fn` into the chained hook under `hook-key` so calling the
  hook runs `step-fn` AND every previously-registered step.

  Pairs with the inline get-prev-and-wrap idiom several chained
  hooks use (`:adapter/clear-warn-once-caches!` published by the
  React-shaped adapters + re-frame.views.warn-once; `:reagent/set-hiccup-emitter!`
  chained across the Reagent / UIx / Helix / reagent-slim adapters
  per rf2-4z7bp). Each adapter ns previously open-coded:

      (let [previous (late-bind/get-fn hook-key)]
        (late-bind/set-fn! hook-key
          (fn chained [& args]
            (apply step-fn args)
            (when previous (apply previous args))
            nil)))

  Replaced by the single-line call:

      (late-bind/chain-fn! hook-key step-fn)

  Semantics:
    * `step-fn` runs FIRST on every invocation (insertion-order
      head of the chain — last-registered step is the outer wrapper).
    * Each previous handler is invoked with the same `args` after
      `step-fn` returns.
    * Per-step throws propagate; the chain does NOT swallow them
      (each contributing ns is responsible for its own try/catch
      semantics).
    * Returns nil — chained hooks are side-effecting (cache resets,
      emitter installs); callers do not consume a return value.

  Per rf2-1fh5h (UIx batch). Sibling for routed hooks is
  `re-frame.substrate.adapter/route-hook!` (single-step routing,
  dispatched by installed-adapter identity)."
  [hook-key step-fn]
  (let [previous (get-fn hook-key)]
    (set-fn! hook-key
      (fn chained-hook [& args]
        (apply step-fn args)
        (when previous (apply previous args))
        nil))))

(defn require-fn!
  "Return the fn registered under `hook-key`, or throw a structured
  `:rf.error/<artefact>-artefact-missing` ex-info when the hook is
  unregistered.

  The throw shape matches the documented missing-artefact contract used
  by every `re-frame.core-<artefact>` wrapper (see rf2-5b6x — the
  late-bind-missing test suite locks this shape across all six per-
  feature splits):

    Message:  `<error-keyword>` printed as a string (e.g.
              \":rf.error/flows-artefact-missing\").
    ex-data:  {:where    <where-sym>            ;; user-facing fn symbol
               :recovery :no-recovery
               :reason   \"<where-sym> requires <maven> on the classpath;
                          add it to deps and require <require-ns> at app boot.\"
               & extra}                          ;; per-call slots

  Args:
    hook-key      — the late-bind hook key (e.g. `:flows/reg-flow`).
    where-sym     — the user-facing fn symbol stamped on the error
                    (e.g. `'rf/reg-flow`) so greping for the symbol
                    finds the call site in user code.
    artefact-info — a map carrying the artefact's Maven coordinates and
                    the producing ns name:
                      {:error-keyword :rf.error/flows-artefact-missing
                       :maven         \"day8/re-frame2-flows\"
                       :require-ns    \"re-frame.flows\"}
    extra-data    — (optional) per-call ex-data slots like `:flow-id` /
                    `:path` / `:route-id` / `:machine-id` / `:frame`.

  Pairs with the `re-frame.core-artefact/defwrapper` factory (rf2-h824v)
  which drives 26+ call sites across seven `core_<artefact>.cljc`
  wrappers from a declarative table."
  ([hook-key where-sym artefact-info]
   (require-fn! hook-key where-sym artefact-info nil))
  ([hook-key where-sym {:keys [error-keyword maven require-ns]} extra-data]
   (or (get @hooks hook-key)
       (throw (ex-info (str error-keyword)
                       (merge {:where    where-sym
                               :recovery :no-recovery
                               :reason   (str where-sym " requires " maven
                                              " on the classpath; add it to deps and require "
                                              require-ns " at app boot.")}
                              extra-data))))))
