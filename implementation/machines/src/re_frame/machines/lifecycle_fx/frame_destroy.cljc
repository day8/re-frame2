(ns re-frame.machines.lifecycle-fx.frame-destroy
  "Frame-destroy machine-cascade orchestrator.

  Per Spec 005 §Cross-Spec Interactions §1 — Frame disposal with active
  machine instances — `destroy-frame!` must:

    1. Walk every active machine on the frame in **reverse-creation
       order** (most recently spawned disposes first).
    2. Run each machine's `:exit` cascade BEFORE clearing its snapshot
       (the same Spec 005 §Declarative `:spawn` §Composition rule
       enforced by `:rf.machine/destroy` — leaf-to-root exit, side
       effects fire against the live snapshot).
    3. Abort that actor's in-flight `:rf.http/managed` requests
       (preserves the rf2-wvkn `:http/abort-on-actor-destroy` contract
       across every destroy trigger including frame destroy).
    4. Apply the unified app-db teardown projection — dissoc
       `[:rf.runtime/machines :snapshots <id>]`, release `[:rf.runtime/machines :system-ids <sid>]` when the
       actor was system-id-bound, prune `[:rf.runtime/machines :spawned]` slots.
    5. Unregister the live actor event handler so subsequent dispatch
       to the address surfaces `:rf.error/no-such-handler` cleanly.
    6. Emit `:rf.machine.lifecycle/destroyed` with
       `:reason :parent-frame-destroyed` per actor — the contract
       observable in `frame_lifecycle_test/destroy-frame-cascade-
       emits-per-active-machine`.

  After every machine has settled, sub-cache disposes / substrate
  releases / `:frame/destroyed` trace fires from `frame/destroy-frame!`
  itself.

  Surfaces:
   - `teardown-on-frame-destroy!` — late-bound at
     `:machines/teardown-on-frame-destroy!`; called from
     `frame/destroy-frame!`.

  Source-of-truth on order: a process-side
  `re-frame.machines.spawn-order` atom records each spawned actor at
  install time. Snapshots that landed via direct `[:rf.runtime/machines :snapshots]` assoc
  (test fixtures, hydration payloads, `replace-runtime-db!`, `restore-epoch!`)
  are not tracked in the transient channel but DO live in the durable
  runtime-db — the walker covers them as a stragglers pass after the
  recorded vector drains.

  Per rf2-apfait — restore/hydration/full-runtime-db-replacement repopulate
  durable runtime-db snapshots WITHOUT repopulating the process-side
  spawn-order atom (the atom is transient bookkeeping, not durable state per
  Spec 002 §Durable vs transient). A restored SPAWNED actor's snapshot
  carries `:rf/machine-type` at its root (stamped by `install-spawn!`; a
  SINGLETON snapshot never carries it — actor_liveness_test:213). The
  straggler pass therefore SPLITS on that durable discriminator: spawned
  stragglers run the FULL `destroy/destroy-single-actor!` teardown (handler
  unregister, schema-mark clear, system-id release, timer cancel, snapshot
  dissoc) in reverse-creation order DERIVED from the durable actor-id (the
  `#<n>` suffix), not the singleton straggler path that skips all of that.
  True singletons keep the exit-cascade-only straggler path."
  (:require [clojure.string :as str]
            [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.destroy :as destroy]
            [re-frame.machines.lifecycle-fx.exit-cascade :as exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- frame-machines-snapshot
  "Snapshot of `[:rf.runtime/machines :snapshots]` on `frame-id`'s
  RUNTIME-DB (a map of actor-id → snapshot), or an empty map. Read at the
  start of the walk so we have a stable view of which actors were live; the
  walk itself swaps the container and these reads are not re-evaluated.
  EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state."
  [frame-id]
  (let [container (frame/runtime-db-container frame-id)
        rt        (when container (adapter/read-container container))]
    (or (get-in rt (paths/snapshot-path)) {})))

(defn- spawned-snapshot?
  "Per rf2-apfait — true iff `snapshot` is a SPAWNED actor's snapshot, not
  a singleton's. The durable, app-db-derived discriminator is the presence
  of `:rf/machine-type` at the snapshot root: `install-spawn!` stamps it on
  every spawned actor (keyword TYPE or inline `:definition`), and a
  singleton's `build-initial-snapshot` snapshot never carries it
  (actor_liveness_test:213-216). The discriminator survives
  restore/hydration/`replace-runtime-db!` because it rides the durable
  snapshot value, so a restored spawned actor absent from the transient
  spawn-order atom is still recognised as spawned."
  [snapshot]
  (some? (:rf/machine-type snapshot)))

(defn- creation-rank
  "Per rf2-apfait — derive a best-effort creation-order rank for a restored
  spawned `actor-id` whose process-side spawn-order entry was lost across
  restore/hydration. Spawned ids are allocated as `<type>#<n>` with a
  monotonic per-type counter (`allocate-actor-id-in-runtime-db`), so the
  trailing `#<n>` integer is the durable creation sequence the spawn-order
  vector would otherwise carry. Returns the parsed `n` as a Long, or -1
  for an id with no `#<n>` suffix (explicit `:fixed-actor-id` literals,
  region-prefixed ids) so those sort oldest — there is no reverse-creation
  contract to honour for ids the allocator never sequenced.

  Ordering the spawned-straggler walk by DESCENDING rank reconstructs the
  newest-first exit order Spec 005 §Cross-Spec Interactions §1 requires,
  derived purely from durable runtime-db (per the bead's acceptance
  criterion)."
  [actor-id]
  (let [s (name actor-id)
        i (str/last-index-of s "#")]
    (if (and i (< (inc i) (count s)))
      (let [tail (subs s (inc i))]
        (if (re-matches #"\d+" tail)
          #?(:clj (Long/parseLong tail) :cljs (js/parseInt tail 10))
          -1))
      -1)))

(defn- emit-lifecycle-destroyed!
  "Emit the `:rf.machine.lifecycle/destroyed` notification per actor,
  carrying the frame id, the machine id, the snapshot's `:state` (when
  present), and the unified discriminator `:reason
  :parent-frame-destroyed`.

  Per Spec 009 §Actor lifecycle observation (009:240-269) this is NOT a
  legacy event: `:rf.machine.lifecycle/destroyed` is the canonical
  REGISTRAR-substrate observation axis (\"the actor's handler / snapshot
  was reaped\", including frame-exit reaping), the deliberate sibling of
  the FX-substrate `:rf.machine/destroyed` (\"a destroy fx ran\"). The
  two are parallel observation axes carrying which substrate observed
  the teardown — never a new-vs-old pair. Frame-exit reaping fires the
  registrar axis only (no destroy fx runs), which is exactly why this
  cascade emits this event. Preserves the trace contract observable in
  `frame_lifecycle_test/destroy-frame-cascade-emits-per-active-machine`."
  [frame-id actor-id snapshot]
  (trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
               {:frame      frame-id
                ;; rf2-ws5thu — the reaped actor's live INSTANCE address;
                ;; `:machine-id` is reserved for the registered TYPE.
                :actor-id   actor-id
                :last-state (:state snapshot)
                :reason     :parent-frame-destroyed}))

(defn- run-singleton-exit-cascade!
  "Singleton-machine destroy on frame teardown: run the actor's `:exit`
  cascade so Spec 005 §Final states §Composition with `:entry` /
  `:exit` symmetry holds, then abort in-flight HTTP. Does NOT
  unregister the handler — singleton handlers live in the global
  registrar per Spec 005 §Spawning §v1-partial footnote: the handler
  outlives any particular frame. Snapshot dissoc is moot at this point
  (the frame's app-db is about to be released).

  The HTTP-abort fires the shared `:http/abort-on-actor-destroy`
  late-bind hook via `finalize/abort-actor-in-flight-http!` — the same
  best-effort, idempotent helper the spawn-destroy + final-state
  teardowns use, so the rf2-wvkn contract has one home.

  EP-0025 (rf2-398kql): this path formerly noted it deliberately did NOT clear
  `:data-schema` marks (rf2-egvm4t). The whole machine `:data-schema`→marks
  bridge is now removed (schema-field classification killed in favour of
  frame-declared paths), so there are no schema marks for any teardown path —
  singleton or spawned — to clear or preserve."
  [frame-id actor-id]
  (exit-cascade/run-child-exit! frame-id actor-id)
  (finalize/abort-actor-in-flight-http! actor-id)
  nil)

(defn teardown-on-frame-destroy!
  "Run the full machine-cascade teardown for `frame-id`. Idempotent
  against double-invocation, fail-soft against missing artefacts.

  Per rf2-vsigt + rf2-apfait the orchestration is:
   1. Snapshot `[:rf.runtime/machines :snapshots]` once.
   2. Build the disposal order in three segments:
      a. Recorded spawn-order reversed (newest first) — the live-process
         spawned actors, walked in true reverse-creation order.
      b. SPAWNED stragglers — snapshots that carry `:rf/machine-type` but
         are absent from the (transient) spawn-order atom. This is the
         restore / hydration / `replace-runtime-db!` case (rf2-apfait):
         durable runtime-db carries the snapshot, the transient atom does
         not. Walked newest-first by `creation-rank` derived from the
         durable `<type>#<n>` actor-id, so reverse-creation order is
         reconstructed from durable state alone.
      c. SINGLETON stragglers — snapshots with no `:rf/machine-type`
         (registered via `reg-machine`, seeded directly). App-db iteration
         order — there is no reverse-creation contract for singletons.
   3. For each actor:
      a. Emit `:rf.machine.lifecycle/destroyed` BEFORE the destroy
         work so trace consumers see the signal while the handler
         still resolves — same convention as Spec 005 §Cancellation
         cascade D6.
      b. Spawned actors (recorded OR restored straggler): run the full
         single-actor destroy — exit-cascade → http-abort →
         schema-mark clear → timer cancel → unified teardown projection →
         system-id-release trace → handler-unregister → spawn-order forget.
      c. Singletons (registered via `reg-machine`, snapshot present but no
         `:rf/machine-type`): run the `:exit` cascade + HTTP abort, but
         DO NOT unregister the handler — singleton handlers live in the
         global registrar per Spec 005 §Spawning v1-partial footnote and
         outlive any particular frame.
   4. Clear the frame's spawn-order slot."
  [frame-id]
  (when frame-id
    (let [snapshots    (frame-machines-snapshot frame-id)
          recorded     (spawn-order/frame-order frame-id)
          ;; Reverse recorded vector → newest spawn first.
          newest-first (reverse recorded)
          recorded-set (set recorded)
          ;; Stragglers: actor-ids in [:rf.runtime/machines :snapshots] but absent
          ;; from the recorded spawn-order vector. Covers singleton
          ;; machines (registered via `reg-machine`), hydrated SSR
          ;; payloads, restored runtime-db, and test fixtures that seed
          ;; snapshots directly.
          stragglers   (remove recorded-set (keys snapshots))
          ;; rf2-apfait — partition stragglers on the durable
          ;; `:rf/machine-type` discriminator. Spawned stragglers (restore
          ;; / hydration / replace-runtime-db! repopulated the durable
          ;; snapshot but not the transient spawn-order atom) MUST get the
          ;; full spawned teardown, ordered newest-first by the durable
          ;; `#<n>` creation rank. Singletons keep the exit-only path.
          {spawned-stragglers   true
           singleton-stragglers false}
          (group-by (fn [actor-id] (spawned-snapshot? (get snapshots actor-id)))
                    stragglers)
          spawned-stragglers'  (sort-by creation-rank #(compare %2 %1)
                                        spawned-stragglers)]
      ;; (b) Recorded spawned actors: full destroy in reverse-creation order.
      (doseq [actor-id newest-first]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        ;; `destroy-single-actor!` is fail-soft against a vanished
        ;; container and missing artefacts; wrap defensively so one
        ;; bad actor can't strand the rest of the cascade.
        (try (destroy/destroy-single-actor! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; (b') Restored spawned stragglers: SAME full destroy, newest-first
      ;; by durable creation rank. rf2-apfait — before this fix these
      ;; flowed through the singleton straggler path and skipped handler
      ;; unregister / schema-mark clear / system-id release / timer cancel
      ;; / snapshot dissoc.
      (doseq [actor-id spawned-stragglers']
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        (try (destroy/destroy-single-actor! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; (c) Singletons: trace + exit cascade + HTTP abort only. The
      ;; handler stays registered (lives in the global registrar, outlives
      ;; the frame).
      (doseq [actor-id singleton-stragglers]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        (try (run-singleton-exit-cascade! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; Drop the frame's spawn-order slot — every recorded actor is
      ;; gone, and a fresh `reg-frame` under the same id starts with a
      ;; clean order channel.
      (spawn-order/clear-frame! frame-id))
    nil))
