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
       (the `:http/abort-on-actor-destroy` contract holds
       across every destroy trigger including frame destroy).
    4. Apply the unified runtime-db teardown projection — dissoc
       `[:rf.runtime/machines :snapshots <id>]`, release `[:rf.runtime/machines :system-ids <sid>]` when the
       actor was system-id-bound, prune `[:rf.runtime/machines :spawned]` slots.
    5. Clear any stale registrar entry; normal spawned actors have no
       per-instance registration.
    6. Emit `:rf.machine.lifecycle/destroyed` with
       `:reason :parent-frame-destroyed` per actor — the contract
       observable in `frame_lifecycle_test/destroy-frame-cascade-
       emits-per-active-machine`.

  After every machine has settled, sub-cache disposes / substrate
  releases / `:frame/destroyed` trace fires from `rf.frame/destroy-frame!`
  itself.

  Surfaces:
   - `teardown-on-frame-destroy!` — late-bound at
     `:machines/teardown-on-frame-destroy!`; called from
     `rf.frame/destroy-frame!`.

  Source-of-truth on order: the DURABLE `[:rf.runtime/machines
  :spawn-order]` vector, appended by `install-spawn!` in the same
  runtime-db swap that lands each snapshot and pruned by the unified
  teardown projection in the swap that dissocs it. Reversing that vector
  IS the reverse-creation walk, and because it rides the durable
  runtime-db value it survives every supported round trip —
  `replace-frame-state!`, `restore-epoch!`, SSR hydration — with no restore
  callback.

  The process-side `re-frame.machines.spawn-order` atom is a transient
  cache (Spec 002 §Durable vs transient) and this walk does not consult it
  at all — neither for ORDER nor for MEMBERSHIP. No runtime-state install
  clears it, so after a restore that rewinds PAST a spawn it names an actor
  the installed durable value discarded; unioning it into the membership
  reaped that dead actor, and — the durable segment walking first — placed
  it AFTER the older actor durable state kept, inverting the reverse-creation
  order this walk exists to honour (rf2-1vlyg audit). What the frame holds
  is what its runtime-db says it holds.

  rf2-1vlyg — this used to be the other way round. The atom was the
  authority, and a restored actor absent from it was ranked by parsing the
  `#<n>` suffix off its actor-id. That suffix is allocated by a
  **per-id-prefix** counter, so it sequences one machine type and cannot
  order two: three actors created `:probe/a#1`, `:probe/a#2`, `:probe/b#1`
  sorted to `[:probe/a#2 :probe/a#1 :probe/b#1]`, exiting the OLDER
  `:probe/a#2` ahead of the NEWEST actor and inverting the stack
  discipline Spec 005 §Cross-Spec Interactions §1 pins. An id supplied
  through `:fixed-actor-id` carries no suffix at all. The parse was
  attempting to reconstruct information the durable state did not contain,
  which no cleverer parse could fix — so the order is now recorded.

  Snapshots can still land by direct `[:rf.runtime/machines :snapshots]`
  assoc, outside any spawn (test fixtures, hand-built payloads); those
  carry no durable order entry and are walked as an UNSEQUENCED tail,
  deterministically but with no reverse-creation claim attached — there is
  no order to honour for actors nothing ever sequenced.

  A restored SPAWNED actor's snapshot carries `:rf/machine-type` at its
  root (stamped by `install-spawn!`; a SINGLETON snapshot never carries it
  — actor_liveness_test:213). The walk SPLITS on that durable
  discriminator: spawned actors run the full
  `rf.machines.lifecycle-fx.destroy/destroy-single-actor!` teardown (registrar cleanup, system-id
  release, timer cancel, snapshot dissoc); true singletons keep the
  exit-cascade-only straggler path."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.machines.lifecycle-fx.destroy :as rf.machines.lifecycle-fx.destroy]
            [re-frame.machines.lifecycle-fx.exit-cascade :as rf.machines.lifecycle-fx.exit-cascade]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn- frame-runtime-db
  "`frame-id`'s RUNTIME-DB value, or nil. Read ONCE at the start of the
  walk so the snapshot map and the durable spawn-order vector come from
  the same instant and cannot disagree; the walk itself swaps the
  container and this read is not re-evaluated.
  EP-0001: machine snapshots are durable runtime-db state."
  [frame-id]
  (when-let [container (rf.frame/runtime-db-container frame-id)]
    (rf.substrate.adapter/read-container container)))

(defn- spawned-snapshot?
  "True iff `snapshot` is a spawned actor's snapshot, not
  a singleton's. The durable runtime-db discriminator is the presence
  of `:rf/machine-type` at the snapshot root: `install-spawn!` stamps it on
  every spawned actor (keyword TYPE or inline `:definition`), and a
  singleton's `build-initial-snapshot` snapshot never carries it
  (actor_liveness_test:213-216). The discriminator survives
  restore/hydration/`replace-runtime-db!` because it rides the durable
  snapshot value, so a restored spawned actor absent from the transient
  spawn-order atom is still recognised as spawned."
  [snapshot]
  (some? (:rf/machine-type snapshot)))

(defn- unsequenced-order
  "Deterministic walk order for spawned actors the durable spawn-order
  vector does NOT carry — snapshots assoc'd straight into
  `[:rf.runtime/machines :snapshots]` by a fixture or a hand-built
  payload, which no spawn ever sequenced.

  Sorted by the printed actor-id purely so the walk is REPRODUCIBLE
  (runtime-db map iteration order is not). This is explicitly **not** a
  creation order and makes no reverse-creation claim: nothing recorded one
  for these actors, and per rf2-1vlyg the id spelling cannot supply it —
  the `#<n>` suffix sequences a single id-prefix, and a `:fixed-actor-id`
  has no suffix at all. Every actor that went through `install-spawn!`
  carries a real durable rank and never reaches this tail."
  [actor-ids]
  (sort-by str actor-ids))

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
  (rf.trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
               {:frame      frame-id
                ;; The reaped actor's live INSTANCE address;
                ;; `:machine-id` is reserved for the registered TYPE.
                :actor-id   actor-id
                :last-state (:state snapshot)
                :reason     :parent-frame-destroyed}))

(defn- run-singleton-exit-cascade!
  "Singleton-machine destroy on frame teardown: run the actor's `:exit`
  cascade so Spec 005 §Final states §Composition with `:entry` /
  `:exit` symmetry holds, then abort in-flight HTTP. Does NOT
  clear the registrar entry — singleton handlers live in the global
  registrar per Spec 005 §Spawning §v1-partial footnote: the handler
  outlives any particular frame. Snapshot dissoc is moot at this point
  (the frame's runtime-db is about to be released).

  The HTTP-abort fires the shared `:http/abort-on-actor-destroy`
  late-bind hook via `rf.machines.lifecycle-fx.finalize/abort-actor-in-flight-http!` — the same
  best-effort, idempotent helper the spawn-destroy + final-state
  teardowns use, so the abort contract has one home. It is frame-exact
  (rf2-wjfm): the frame being torn down is threaded through, so a
  same-named singleton in a sibling frame keeps its in-flight requests.

  A machine's `[:schemas :data]` schema is validation-only; it does not produce
  a per-instance marks table. There are therefore no schema marks for any
  teardown path — singleton or spawned — to clear or preserve."
  [frame-id actor-id]
  (rf.machines.lifecycle-fx.exit-cascade/run-child-exit! frame-id actor-id)
  (rf.machines.lifecycle-fx.finalize/abort-actor-in-flight-http! frame-id actor-id)
  nil)

(defn teardown-on-frame-destroy!
  "Run the full machine-cascade teardown for `frame-id`. Idempotent
  against double-invocation, fail-soft against missing artefacts.

  The orchestration is:
   1. Read the frame's runtime-db once — both the
      `[:rf.runtime/machines :snapshots]` map and the durable
      `[:rf.runtime/machines :spawn-order]` vector come from that one
      instant.
   2. Build the disposal order in three segments:
      a. The durable spawn-order vector REVERSED (newest first) — every
         actor that went through `install-spawn!`, in true
         reverse-creation order. This is the whole of the ordering
         contract, and it holds identically for a live process and for a
         frame that has been restored, hydrated or wholesale-replaced,
         because the vector is durable runtime-db state rather than
         process-side bookkeeping (rf2-1vlyg).
      b. UNSEQUENCED spawned actors — snapshots carrying
         `:rf/machine-type` that the durable vector does not name. Only a
         directly-assoc'd fixture / hand-built payload reaches here.
         Walked in `unsequenced-order`: deterministic, with no
         reverse-creation claim, because nothing ever sequenced them.
      c. Singleton stragglers — snapshots with no `:rf/machine-type`
         (registered via `reg-machine`, seeded directly). Runtime-db iteration
         order — there is no reverse-creation contract for singletons.
   3. For each actor:
      a. Emit `:rf.machine.lifecycle/destroyed` BEFORE the destroy
         work so trace consumers see the signal while the handler
         still resolves — same convention as Spec 005 §Cancellation
         cascade D6.
      b. Spawned actors (durably sequenced OR unsequenced): run the full
         single-actor destroy — exit-cascade → http-abort →
         timer cancel → unified teardown projection →
         system-id-release trace → registrar cleanup → spawn-order forget.
      c. Singletons (registered via `reg-machine`, snapshot present but no
         `:rf/machine-type`): run the `:exit` cascade + HTTP abort, but
         DO NOT unregister the handler — singleton handlers live in the
         global registrar per Spec 005 §Spawning v1-partial footnote and
         outlive any particular frame.
   4. Clear the frame's spawn-order slot."
  [frame-id]
  (when frame-id
    (let [runtime-db   (frame-runtime-db frame-id)
          snapshots    (or (get-in runtime-db (rf.machines.paths/snapshot-path)) {})
          ;; The DURABLE total creation order, oldest → newest. Written by
          ;; `install-spawn!` inside the swap that landed each snapshot and
          ;; pruned by the teardown projection inside the swap that removed
          ;; it, so it names exactly the live spawned actors — and it says so
          ;; identically before and after a restore / hydration /
          ;; `replace-runtime-db!`, which is the whole point (rf2-1vlyg).
          durable      (rf.machines.spawn-order/durable-order runtime-db)
          durable-set  (set durable)
          ;; Reverse the durable vector → newest spawn first. THE
          ;; reverse-creation walk, per Spec 005 §Cross-Spec Interactions §1.
          newest-first (reverse durable)
          ;; Spawned actors the durable order does not name. A spawn always
          ;; records itself, so this is the directly-assoc'd fixture /
          ;; hand-built payload case only.
          ;;
          ;; Membership comes from the DURABLE snapshots alone. The transient
          ;; `spawn-order` cache used to be unioned in here, which reaped
          ;; actors the frame no longer holds: no runtime-state install clears
          ;; that cache, so after a `restore-epoch!` / `replace-frame-state!`
          ;; that rewinds PAST a spawn it still names the discarded actor —
          ;; and because the durable segment walks first, the discarded NEWER
          ;; actor was torn down AFTER the older one durable state kept,
          ;; inverting the very reverse-creation order this walk exists to
          ;; honour (rf2-1vlyg audit). A cache-only id has no snapshot, no
          ;; durable order entry and no state to tear down; it is not a live
          ;; actor of this frame.
          unsequenced  (->> (keys snapshots)
                            (remove durable-set)
                            distinct
                            ;; Partition on the durable `:rf/machine-type`
                            ;; discriminator: a spawned snapshot MUST get the
                            ;; full teardown (registrar cleanup / system-id
                            ;; release / timer cancel / snapshot dissoc), while
                            ;; a singleton keeps the exit-only path below.
                            (group-by (fn [actor-id]
                                        (spawned-snapshot? (get snapshots actor-id)))))
          singleton-stragglers (get unsequenced false)
          unsequenced-spawned  (unsequenced-order (get unsequenced true))]
      ;; (a) Durably sequenced spawned actors: full destroy, newest first.
      (doseq [actor-id newest-first]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        ;; `destroy-single-actor!` is fail-soft against a vanished
        ;; container and missing artefacts; wrap defensively so one
        ;; bad actor can't strand the rest of the cascade.
        (try (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-id actor-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      ;; (b) Unsequenced spawned actors: the SAME full teardown, in a
      ;; deterministic order that claims no creation sequence — nothing ever
      ;; recorded one for them (see `unsequenced-order`).
      (doseq [actor-id unsequenced-spawned]
        (let [snapshot (get snapshots actor-id)]
          (emit-lifecycle-destroyed! frame-id actor-id snapshot))
        (try (rf.machines.lifecycle-fx.destroy/destroy-single-actor! frame-id actor-id)
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
      ;; gone, and a fresh construction under the same id starts with a
      ;; clean order channel.
      (rf.machines.spawn-order/clear-frame! frame-id))
    nil))
