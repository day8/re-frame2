(ns re-frame.late-bind
  "Hook registry for cross-namespace and cross-artefact forward
  references. Producing ns calls `set-fn!` at load time; consumer calls
  `get-fn` at call time. Identical behaviour on JVM and CLJS.

  Carries two flavours of forward reference:
    1. Cyclic-load: leaf namespaces (frame, fx, cofx, subs, router)
       calling into higher-level namespaces without `:require`ing them.
    2. Cross-artefact: re-frame.core reaching into an optional artefact
       (schemas, flows, routing, machines, ssr, epoch, http) without
       statically `:require`ing it — when the artefact is absent the
       lookup returns nil and the consumer no-ops (or throws a clear
       `:rf.error/<artefact>-artefact-missing`).

  The authoritative inventory of every published key lives in
  `re-frame.late-bind.directory` — one CLJC entry per hook key. The
  drift test `implementation/core/test/re_frame/late_bind_drift_test.clj`
  asserts the directory matches the in-tree `set-fn!` call sites.")

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

  Semantics:
    * `step-fn` runs FIRST on every invocation (last-registered step
      is the outer wrapper).
    * Each previous handler is invoked with the same `args` after
      `step-fn` returns.
    * Per-step throws propagate; the chain does NOT swallow them.
    * Returns nil — chained hooks are side-effecting (cache resets,
      emitter installs); callers do not consume a return value.

  Sibling for routed (single-step, dispatched by installed-adapter
  identity) hooks is `re-frame.substrate.adapter/route-hook!`."
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

    Message:  `<error-keyword>` printed as a string (e.g.
              \":rf.error/flows-artefact-missing\").
    ex-data:  {:where    <where-sym>
               :recovery :no-recovery
               :reason   \"<where-sym> requires <maven> on the classpath;
                          add it to deps and require <require-ns> at app boot.\"
               & extra-data}

  `where-sym` is the user-facing fn symbol stamped on the error.
  `artefact-info` carries `{:error-keyword :maven :require-ns}`.
  `extra-data` is the per-call ex-data merged in (e.g. `:flow-id`,
  `:route-id`).

  Pairs with `re-frame.core-artefact/defwrapper`."
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
