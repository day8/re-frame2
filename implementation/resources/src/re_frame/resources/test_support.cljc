(ns re-frame.resources.test-support
  "Test-support namespace for the resources artefact (Spec 016).

  This namespace is the home for resources test-only fixtures and reset
  helpers — kept OUT of the always-on `re-frame.resources` production
  façade so a test-runner-internal surface never reaches a production
  registry. Test fixtures live behind an explicit test-support require, mirroring
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
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.mutation-events :as rf.resources.mutation-events]
            [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
            [re-frame.resources.registry :as rf.resources.registry]
            [re-frame.resources.revalidate-listeners :as rf.resources.revalidate-listeners]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.subs :as rf.resources.subs]
            [re-frame.resources.timers :as rf.resources.timers]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]))

#?(:clj (set! *warn-on-reflection* true))

(defn reset-resources!
  "Per-test reset: clear every `:resource`-kind registrar entry and the
  host-side transient generation high-water marks. Fired by the shared
  CLJS `make-reset-runtime-fixture` reset-hooks table via the
  `:resources/reset-resources!` late-bind hook this namespace publishes
  at ns-load (kept behind this explicit test-support require so the
  production façade carries no test-fixture surface). Per
  Spec 016 (test isolation)."
  []
  (rf.registrar/clear-kind! rf.resources.registry/resource-kind)
  ;; Clear the mutation registrar kind too; the mutation
  ;; registry is the causal-write counterpart of the resource registry.
  (rf.registrar/clear-kind! rf.resources.mutation-registry/mutation-kind)
  ;; Also clear the named resource-scope resolver registry. Pure resolvers hold
  ;; no per-frame
  ;; runtime state, so clearing the registrar kind is the whole reset.
  (rf.registrar/clear-kind! rf.resources.scope-registry/scope-kind)
  (rf.resources.state/reset-cache!)
  ;; Drop the host-side work-ledger handles too; they are
  ;; host-side transient state not cleared by the runtime / frames reset.
  (rf.resources.work-ledger/reset-cache!)
  ;; Cancel and drop host-side stale / GC timer handles; likewise
  ;; host-side transient state; cancels any armed timers so a leftover timer
  ;; cannot fire into a later test's frame.
  (rf.resources.timers/reset-cache!)
  ;; Remove host-side focus/reconnect revalidation listeners;
  ;; likewise host-side transient state; detaches any installed window
  ;; listeners so a leftover listener cannot dispatch into a later test's frame.
  (rf.resources.revalidate-listeners/reset-cache!)
  ;; Clear the host-side scope-mismatch dev-warning dedupe set;
  ;; host-side transient dev state; clearing it lets each test observe the
  ;; one-shot warning freshly without a prior test's emission masking it.
  (rf.resources.subs/reset-scope-mismatch-warnings!)
  ;; Clear the framework-owned `:rf.resource/items` merge memo;
  ;; host-side transient derivation cache (the infinite-feed merged-list
  ;; projection), not runtime-db; cleared so a prior test's feed merge cannot
  ;; be served for a later test's `=`-equal page vector.
  (rf.resources.subs/reset-merge-memo!)
  ;; and the host-side WRITE-side scope-mismatch dev-warning dedupe set
  ;; (rf2-byl7bk.4) — the mutation-settlement complement of the sub-side
  ;; warning; likewise host-side transient dev state, cleared so each test
  ;; observes the one-shot `:rf.warning/mutation-scope-mismatch` freshly.
  (rf.resources.mutation-events/reset-mutation-scope-mismatch-warnings!)
  ;; and the host-side settle-time SKIPPED-TARGET dev-warning dedupe set
  ;; (rf2-1vpbld) — the dedicated drop-and-warn tripwire for recoverable
  ;; post-write `:patches` / `:populates` / `:removes` targets; likewise
  ;; host-side transient dev state, cleared so each test observes the one-shot
  ;; `:rf.warning/mutation-target-skipped` freshly.
  (rf.resources.mutation-events/reset-mutation-target-skipped-warnings!)
  nil)

;; Publish the reset hook from this test-support ns-load — the shared CLJS
;; make-reset-runtime-fixture reset-hooks table consults
;; `:resources/reset-resources!` by key and no-ops when this namespace is
;; absent (production builds never load it). Mirrors the
;; re-frame.routing.test-support / re-frame.http.test-support posture.
(rf.late-bind/set-fn! :resources/reset-resources! reset-resources!)
