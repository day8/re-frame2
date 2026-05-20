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

;; ---- sticky resolution cache (rf2-f72pd) ----------------------------------
;;
;; `get-fn-cached` is a sticky variant of `get-fn` for hooks that are
;; published once at boot and never withdrawn in production
;; (`:schemas/validate-*!`, `:flows/run-flows!`, `:epoch/settle!`,
;; `:epoch/capture-event`, `:event-emit/dispatch-on-event`,
;; `:router/dispatch!`, …). These run on every dispatch — the dispatch
;; cascade reads ~6+ keys per event, so a 100-event cascade was 600+
;; identical atom-derefs of `hooks` plus 600+ identical map lookups.
;;
;; The cache memoises the resolution: first hit reads `hooks`, populates
;; `fn-cache`, and returns; subsequent hits read `fn-cache` directly. On
;; the JVM the gain is bounded (atom-deref is JIT-friendly); on V8 the
;; relevant pressure is megamorphic-IC busting at the `@hooks` site,
;; which the per-key dedicated slot avoids.
;;
;; Invariant: `set-fn!` and `chain-fn!` invalidate the slot for the key
;; they re-publish. Production registers each hook once at boot and
;; never again — cache hits 100%. Dev hot-reload of an artefact
;; re-registers — the next cached lookup re-resolves through `hooks`,
;; identical to today's behaviour. The pattern mirrors
;; `re-frame.registrar/emit!-cache` (which memoised `:trace/emit!`
;; under the same logic before this generalised mechanism existed).

(defonce ^:private fn-cache
  ;; hook-key → resolved-fn. Distinct from `hooks` so the cache slot
  ;; semantics are clean: only positive resolutions are cached, and
  ;; `set-fn!` / `chain-fn!` clear the slot atomically. Nil resolutions
  ;; (key not yet published) fall through to the `hooks` lookup every
  ;; call so a deferred publication is visible the next dispatch.
  (atom {}))

(defn invalidate-cache!
  "Drop the cached resolution for `hook-key`. Called from `set-fn!` and
  `chain-fn!` so the next `get-fn-cached` re-resolves through `hooks`.
  Public so test fixtures and dev-time refresh tooling can force a
  re-resolve."
  [hook-key]
  (swap! fn-cache dissoc hook-key)
  nil)

(defn set-fn!
  "Register a fn under hook-key. The producing namespace calls this at
  the bottom of its file; consumers look it up via `get-fn` (one-shot)
  or `get-fn-cached` (sticky / hot-path).

  Invalidates the sticky resolution cache for `hook-key` so any
  previously-cached resolution is dropped — the next `get-fn-cached`
  call re-resolves through `hooks`. This guarantees hot-reload of an
  artefact swaps the resolved fn on the very next dispatch."
  [hook-key f]
  (swap! hooks assoc hook-key f)
  (invalidate-cache! hook-key)
  nil)

(defn get-fn
  "Return the fn registered under hook-key, or nil if no producer has
  registered it yet. Callers MUST handle the nil case (the common
  pattern is `(when-let [f (late-bind/get-fn ...)] (f args))`).

  Use `get-fn-cached` instead at hot-path call sites that read the
  same key on every dispatch — `get-fn` re-derefs `hooks` and re-walks
  the map every call; `get-fn-cached` memoises the resolution."
  [hook-key]
  (get @hooks hook-key))

(defn get-fn-cached
  "Sticky variant of `get-fn` (rf2-f72pd) — memoises the resolved fn
  for `hook-key` so subsequent calls read a per-key atom slot rather
  than re-deref'ing the global `hooks` map.

  Returns the resolved fn, or nil when no producer has published the
  key yet. Nil resolutions are NOT cached — a deferred publication is
  visible on the next call.

  The cache is invalidated on `set-fn!` / `chain-fn!` for the key, so
  dev-time hot-reload of an artefact re-resolves on the next dispatch.
  Use at hot-path call sites — every dispatch reads
  `:schemas/validate-event!`, `:schemas/validate-app-schema!`,
  `:flows/run-flows!`, `:epoch/settle!`, `:epoch/capture-event`,
  `:event-emit/dispatch-on-event`, `:router/dispatch!` — so a
  100-event cascade resolved each ~100 times before this cache."
  [hook-key]
  (or (get @fn-cache hook-key)
      (when-let [resolved (get @hooks hook-key)]
        (swap! fn-cache assoc hook-key resolved)
        resolved)))

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
