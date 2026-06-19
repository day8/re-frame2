(ns re-frame.resources.test-support
  "Test-support namespace for the resources artefact (Spec 016).

  This namespace is the home for resources test-only fixtures and reset
  helpers — kept OUT of the always-on `re-frame.resources` production
  façade so a test-runner-internal surface never reaches a production
  registry (the rf2-dbiv8 / rf2-cdmle posture: test fixtures live behind
  an explicit test-support require, mirroring
  `re-frame.routing.test-support` and `re-frame.http.test-support`).

  Production posture: this namespace is unreferenced from any production
  module, so CLJS `:advanced` trims it wholesale and JVM/SSR sees
  classpath absence through the normal artefact-require boundary.

  ## What lives here

  - `reset-resources!` — the per-test reset thunk the shared CLJS
    `make-reset-runtime-fixture` reset-hooks table fires (via the
    `:resources/reset-resources!` late-bind hook the façade publishes):
    clears the `:resource`-kind registrar entries and the host-side
    transient generation high-water marks (which are NOT runtime-db
    state, so a runtime/frames reset does not clear them).

  The reset helper clears the registrar (resource + mutation kinds), the
  host-side generation cache, the runtime cache, and the host-side work-
  ledger handles. Deterministic ensure/refetch replay without a live Fetch
  rides on the managed-HTTP canned-stub fixtures (`re-frame.http-test-
  support`), reached through the same managed-HTTP transport the runtime
  uses."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources.mutation-events :as mutation-events]
            [re-frame.resources.mutation-registry :as mutation-registry]
            [re-frame.resources.registry :as registry]
            [re-frame.resources.revalidate-listeners :as revalidate-listeners]
            [re-frame.resources.scope-registry :as scope-registry]
            [re-frame.resources.state :as state]
            [re-frame.resources.subs :as subs]
            [re-frame.resources.timers :as timers]
            [re-frame.resources.work-ledger :as work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

(defn reset-resources!
  "Per-test reset: clear every `:resource`-kind registrar entry and the
  host-side transient generation high-water marks. Fired by the shared
  CLJS `make-reset-runtime-fixture` reset-hooks table via the
  `:resources/reset-resources!` late-bind hook this namespace publishes
  at ns-load (kept behind this explicit test-support require so the
  production façade carries no test-fixture surface — rf2-dbiv8). Per
  Spec 016 (test isolation)."
  []
  (registrar/clear-kind! registry/resource-kind)
  ;; clear the :mutation registrar kind too (rf2-dwme29) — the mutation
  ;; registry is the causal-write counterpart of the resource registry.
  (registrar/clear-kind! mutation-registry/mutation-kind)
  ;; and the :resource-scope registrar kind (rf2-hls77w) — the named
  ;; scope-resolver registry (EP-0016 D3). Pure resolvers hold no per-frame
  ;; runtime state, so clearing the registrar kind is the whole reset.
  (registrar/clear-kind! scope-registry/scope-kind)
  (state/reset-cache!)
  ;; drop the host-side work-ledger handles too (rf2-afpdkn) — also
  ;; host-side transient state not cleared by the runtime / frames reset.
  (work-ledger/reset-cache!)
  ;; and the host-side stale / GC timer handles (rf2-nbjewi) — likewise
  ;; host-side transient state; cancels any armed timers so a leftover timer
  ;; cannot fire into a later test's frame.
  (timers/reset-cache!)
  ;; and the host-side focus/reconnect revalidation listeners (rf2-vtblcq) —
  ;; likewise host-side transient state; detaches any installed window
  ;; listeners so a leftover listener cannot dispatch into a later test's frame.
  (revalidate-listeners/reset-cache!)
  ;; and the host-side scope-mismatch dev-warning dedupe set (rf2-rsmiru) —
  ;; host-side transient dev state; clearing it lets each test observe the
  ;; one-shot warning freshly without a prior test's emission masking it.
  (subs/reset-scope-mismatch-warnings!)
  ;; and the framework-owned `:rf.resource/items` merge memo (EP-0021 R3) —
  ;; host-side transient derivation cache (the infinite-feed merged-list
  ;; projection), not runtime-db; cleared so a prior test's feed merge cannot
  ;; be served for a later test's `=`-equal page vector.
  (subs/reset-merge-memo!)
  ;; and the host-side WRITE-side scope-mismatch dev-warning dedupe set
  ;; (rf2-byl7bk.4) — the mutation-settlement complement of the sub-side
  ;; warning; likewise host-side transient dev state, cleared so each test
  ;; observes the one-shot `:rf.warning/mutation-scope-mismatch` freshly.
  (mutation-events/reset-mutation-scope-mismatch-warnings!)
  ;; and the host-side settle-time SKIPPED-TARGET dev-warning dedupe set
  ;; (rf2-1vpbld) — the dedicated drop-and-warn tripwire for recoverable
  ;; post-write `:patches` / `:populates` / `:removes` targets; likewise
  ;; host-side transient dev state, cleared so each test observes the one-shot
  ;; `:rf.warning/mutation-target-skipped` freshly.
  (mutation-events/reset-mutation-target-skipped-warnings!)
  nil)

;; Publish the reset hook from this test-support ns-load — the shared CLJS
;; make-reset-runtime-fixture reset-hooks table consults
;; `:resources/reset-resources!` by key and no-ops when this namespace is
;; absent (production builds never load it). Mirrors the
;; re-frame.routing.test-support / re-frame.http.test-support posture.
(late-bind/set-fn! :resources/reset-resources! reset-resources!)
