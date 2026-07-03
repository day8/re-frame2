(ns re-frame.late-bind
  "Hook registry for cross-namespace and cross-artefact forward
  references. Producing ns calls `set-fn!` (single key) or `set-fns!`
  (map of entries) at load time; consumer calls `get-fn` at
  call time. Identical behaviour on JVM and CLJS.

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
  asserts the directory matches the in-tree publications, walking both
  the per-key `set-fn!` form and the map-form `set-fns!`
  block."
  (:require [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "Map of hook-key → fn. Populated by the producing namespace at
   load time. The authoritative key inventory is
   `re-frame.late-bind.directory/hooks`; the drift test enforces it
   matches the in-tree `set-fn!` call sites."}
  hooks
  (atom {}))

;; ---- sticky resolution cache ----------------------------------------------
;;
;; `get-fn-cached` is a sticky variant of `get-fn` for hooks that are
;; published once at boot and never withdrawn in production
;; (`:schemas/validate-*!`, `:flows/run-flows-on-db`, `:epoch/settle!`,
;; `:epoch/capture-event`, `:event-emit/dispatch-on-event`,
;; `:router/dispatch!`, …). These run on every dispatch — the dispatch
;; drain reads ~6+ keys per event, so a 100-event drain was 600+
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
;; re-registers — the next cached lookup re-resolves through `hooks`.
;; The pattern mirrors `re-frame.registrar/emit!-cache`, which memoises
;; `:trace/emit!` under the same logic.

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

(defn set-fns!
  "Register every `hook-key → fn` entry in `m` in one call.

  Equivalent to calling `set-fn!` once per entry, but reads as a single
  publication of an artefact's late-bind contract rather than a column
  of identical imperative side-effects. Feature artefacts (epoch, flows,
  schemas, machines, routing, http, ssr) publish ~15+ keys each at the
  bottom of their facade ns; the map form makes the contract scannable.

  Each entry invalidates its own slot in the resolution cache, identical
  to repeated `set-fn!` calls. Returns nil."
  [m]
  (doseq [[hook-key f] m]
    (set-fn! hook-key f))
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
  "Sticky variant of `get-fn` — memoises the resolved fn
  for `hook-key` so subsequent calls read a per-key atom slot rather
  than re-deref'ing the global `hooks` map.

  Returns the resolved fn, or nil when no producer has published the
  key yet. Nil resolutions are NOT cached — a deferred publication is
  visible on the next call.

  The cache is invalidated on `set-fn!` / `chain-fn!` for the key, so
  dev-time hot-reload of an artefact re-resolves on the next dispatch.
  Use at hot-path call sites — every dispatch reads
  `:schemas/validate-event!`, `:schemas/validate-app-schema!`,
  `:flows/run-flows-on-db`, `:epoch/settle!`, `:epoch/capture-event`,
  `:event-emit/dispatch-on-event`, `:router/dispatch!` — where a
  100-event drain would otherwise resolve each key ~100 times."
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

;; ---- warn-once clear-fn governance registry ------------------------------
;;
;; Every process-wide `defonce` warn-once cache in the adapter / views
;; family (`warned-non-dom-roots`, the `seen-render-keys` set,
;; the slim hiccup interpreter's `warned-keyword-prop` cache, and the
;; React-hook spine's per-adapter source-coord cache) must be wiped by the
;; standard `make-reset-runtime-fixture` between tests — otherwise a sibling
;; test's first-encounter warning silently swallows a later test's same-key
;; warning. The mechanism is the chained `:adapter/clear-warn-once-caches!`
;; late-bind hook the fixture fires.
;;
;; This defect class is fragile to wire by hand: chaining the clear-fn is a
;; one-liner, but with no single chokepoint nothing asserts the set of
;; `defonce` warn-once caches equals the set of clears in the chain — a cache
;; could be added with a bare `defonce` + a standalone clear-fn and silently
;; escape the fixture.
;;
;; `register-warn-once-clear-fn!` is that chokepoint: it both (a) chains
;; the clear-fn into `:adapter/clear-warn-once-caches!` and (b) records
;; the cache in this governance registry with `:arm` / `:armed?` probes.
;; The governance assertion (warn_once_clear_governance test) enumerates
;; the registry, arms every cache, fires the chain once, and asserts each
;; cache is empty afterwards — so a cache that registers but forgets the
;; chain (or escapes the chokepoint entirely) trips the gate.

(defonce
  ^{:doc "Vector of governance entries for the adapter/views warn-once
   `defonce` caches that MUST be wiped by the chained
   `:adapter/clear-warn-once-caches!` fixture hook. Populated at ns-load
   by `register-warn-once-clear-fn!`. Each entry is
   `{:label keyword, :clear-fn fn, :arm fn, :armed? fn}`. Consumed only by
   the warn-once-clear governance assertion; production never
   reads it. The registry is the authoritative enumeration of the cache
   class — the assertion proves every member is actually cleared by the
   single canonical chain."}
  warn-once-clear-registry
  (atom []))

(defn register-warn-once-clear-fn!
  "Canonical chokepoint for wiring a process-wide warn-once
  `defonce` cache into the chained `:adapter/clear-warn-once-caches!`
  fixture-reset hook. Call this — never a bare `chain-fn!` on that key —
  so the cache is BOTH chained AND enrolled in the governance registry
  the warn-once-clear assertion checks.

  Args (a single map):
    :label    — keyword naming the cache (for assertion failure messages).
    :clear-fn — the zero-arg clear-thunk (resets the cache to empty, nil).
    :arm      — zero-arg thunk that seeds the cache with a sentinel so
                `armed?` returns true. Lets the governance assertion drive
                the cache to a known non-empty state.
    :armed?   — zero-arg predicate true iff the cache holds the sentinel
                (i.e. has NOT been cleared).

  Effects: chains `:clear-fn` into `:adapter/clear-warn-once-caches!`
  (so `make-reset-runtime-fixture` wipes it) and appends the entry to
  `warn-once-clear-registry`. Returns nil.

  `re-frame.substrate.spine/install-clear-warn-once-step!` delegates here
  with default arm/armed? probes for the adapters' source-coord caches."
  [{:keys [label clear-fn arm armed?] :as entry}]
  (chain-fn! :adapter/clear-warn-once-caches! clear-fn)
  (swap! warn-once-clear-registry conj
         {:label label :clear-fn clear-fn :arm arm :armed? armed?})
  nil)

(defn require-fn!
  "Return the fn registered under `hook-key`, or throw a structured
  `:rf.error/<artefact>-artefact-missing` ex-info when the hook is
  unregistered.

  Carries the canonical thrown-error shape via the central builder
  `re-frame.error/throw-error!` (per Spec 009 §The thrown-error shape):

    Message:  the human `:reason` sentence + the trailing
              `[:rf.error/<artefact>-artefact-missing]` greppability
              token (e.g. \"rf/reg-flow requires
              com.day8.re-frame/re-frame2-flows on the classpath; add it
              to deps and require re-frame.flows at app boot.
              [:rf.error/flows-artefact-missing]\").
    ex-data:  {:rf.error/id <error-keyword>  ;; canonical discriminator
               :where       <where-sym>
               :recovery    :no-recovery
               :reason      \"<where-sym> requires <maven> on the classpath;
                             add it to deps and require <require-ns> at app boot.\"
               & extra-data}

  The `:rf.error/id` slot is the canonical discriminator consumers read
  uniformly (Xray, pair-tool, `:on-error`); the message LEADS with the
  human sentence and carries the category only inside the trailing token,
  so `.getMessage` reads as actionable prose while a log grep still
  pivots on the bracketed keyword. `where-sym` is the user-facing fn
  symbol stamped on the error. `artefact-info` carries
  `{:error-keyword :maven :require-ns}`. `extra-data` is the per-call
  ex-data merged in (e.g. `:flow-id`, `:route-id`).

  Pairs with `re-frame.core-artefact/defwrapper`."
  ([hook-key where-sym artefact-info]
   (require-fn! hook-key where-sym artefact-info nil))
  ([hook-key where-sym {:keys [error-keyword maven require-ns]} extra-data]
   (or (get @hooks hook-key)
       (error/throw-error!
         error-keyword
         where-sym
         (str where-sym " requires " maven
              " on the classpath; add it to deps and require "
              require-ns " at app boot.")
         {:extra extra-data}))))
