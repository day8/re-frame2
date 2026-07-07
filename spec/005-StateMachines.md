# Spec 005 — State Machines

> Status: Drafting. **Ergonomic library layered on the v1 foundation hooks — shipped and conformance-pinned.** The CLJS reference ships it as `implementation/machines/` (the capability matrix below is graded against the conformance corpus). Builds on the foundation hooks in [002-Frames.md §State machines are just event handlers](002-Frames.md); the foundation-vs-library split is per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap).
>
> **Why this Spec exists:** state machines and actors are in the pattern because **constrained execution models are easier to reason about than Turing-complete control flow.** A finite state machine has an enumerable state space and a discrete transition relation; an actor system bounds concurrency to message-passing across well-defined boundaries with run-to-completion semantics. This is disproportionately valuable for AI use — an AI can fully simulate a finite machine; it cannot fully simulate a free-form imperative program. The cost is some expressiveness; the benefit is an execution model that survives mechanical reasoning.
>
> For where Levels 1–4 sit in relation to the rest of the runtime (registrar, frame container, sub-cache, substrate adapter, trace bus), see [Runtime-Architecture](Runtime-Architecture.md).
>
> `:spawn` and `:spawn-all` (state-machine actors) are **managed external effects** — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the nine properties (effect-as-data, framework-owned actor lifecycle, structured failure taxonomy under `:rf.machine/*`, trace-bus observability, `:sensitive?` / `:large?` composition, built-in retry / abort / teardown via `:after` / `:always` / state-exit, in-flight actor registry, per-frame interceptor scoping, and — for the machine's *async* completions — the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope)). The machine's two async completions (a spawned actor finishing on a `:final?` leaf; a fired-or-stale `:after` timer) lower onto that envelope **internally**; the public statechart API (`:on-done` / `:on-error` / `:after` / actor-destroy) is preserved exactly. See [§Async completions share the uniform reply envelope](#async-completions-share-the-uniform-reply-envelope) for the lowering and its work-id / status / stale-suppression vocabulary.

## Abstract

A state machine in re-frame2 is **an event handler whose body interprets a transition table**. Machines are registered as event handlers via `reg-event + make-machine-handler`; the registered handler is the entire surface. The framework's machine-specific hooks live in 002 — drain semantics, the snapshot shape, the inspection trace surface, the `:raise` reserved fx-id (machine-internal) that the machine handler routes locally, and the `:rf.machine/spawn` / `:rf.machine/destroy` fx-ids (canonical actor-lifecycle).

For readers familiar with xstate, [§Lessons from xstate](#lessons-from-xstate-deliberate-divergences) at the end of this spec lists the divergences inline and forward-points to [CP-5-MachineGuide §Lessons from xstate](CP-5-MachineGuide.md#lessons-from-xstate-deliberate-divergences) for the full divergence table.

## Why machines

Machines serve two distinct use cases:

1. **High-level workflow.** Multi-step user flows (signup → verify → onboard → home), modal dismissal logic, wizard navigation. Without machines these get smeared across many event handlers and an `:app/screen` keyword in `app-db`; the smearing is the pain.
2. **Low-level protocols.** Async resource lifecycles (HTTP request: `idle → loading → success/error/retry`), websocket connection states, animation transitions. Without machines these live as ad-hoc keywords in some sub-tree of `app-db`, with handlers that have to remember "if state is `:loading`, ignore another `:fetch`."

Both want the same primitive but the ergonomics matter differently — workflow machines are few and named (one per major subsystem); protocol machines may have many concurrent instances (one per active resource). The same `make-machine-handler` factory covers both: a singleton machine is registered at boot via `reg-event`; a dynamic instance is registered at run time via the `[:rf.machine/spawn ...]` fx (per [§Spawning — dynamic actors](#spawning--dynamic-actors)).

## Naming — `:state` and `:data`

The snapshot is `{:state :data}`:

- `:state` — the FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Discrete; enumerable; what xstate calls `state.value`.
- `:data` — extended state, the machine's own private memory: a plain map distinct from `app-db`. The term tracks FSM literature and Erlang `gen_statem`'s "state data"; xstate calls the same slot "context".

The pair `{:state :data}` reads as the natural English idiom and matches a vocabulary that's well-represented in AI training corpora. We use `:data` to avoid the existing "context" overloading in re-frame's interceptor pipeline and React-context affordances.

> **`:data` is the canonical destructure key for the machine's working memory.** Per [§Guards](#guards) / [§Actions](#actions) and, every machine callback receives a SINGLE context-map argument with `:data` (the machine's `:data` slot — a plain map), `:event` (the inbound event vector), and on opt-in via destructure also `:state` (the discrete FSM-keyword) and `:meta` (any user `:meta` declared on the snapshot). Bodies that read individual fields destructure: `(fn [{:keys [data event]}] (:circle-id data))`, NOT `(get-in snapshot [:data :circle-id])`.

## Snapshot shape

```clojure
{:state <fsm-keyword-or-path-or-region-map>   ;; :idle | [:checkout :payment :credit-card] | {:data :loading :form :neutral}
 :data  <map>                                  ;; the machine's private memory
 :tags  #{<keyword>, ...}                      ;; OPTIONAL — runtime-projected union of every active state's :tags; see §State tags
 :meta  {<optional> ...}}                      ;; user-defined; carries one reserved slot, :rf/snapshot-version (see §Reserved snapshot-internal keys)
```

The runtime ALSO stamps a closed set of `:rf/*` slots inside the snapshot (some at the snapshot root, some inside `:data`) — the spawn-id counter, the `:after`-epoch counter, the bootstrap flag, the spawned-actor address keys, etc. These are framework-owned and catalogued in [Conventions §Reserved snapshot-internal keys](Conventions.md#reserved-snapshot-internal-keys-machine-runtime); user code MUST NOT write under them.

`:state` has **three arms**, disambiguated by the machine's declared shape:

- **Flat machines** — `:state` is a single FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Equivalent to xstate's `state.value` for a non-compound machine. The flat-machine grammar in [§Transition table grammar](#transition-table-grammar) and the Circle Drawer worked example use this form.
- **Compound machines** — `:state` is a **vector path** from the root state-node to the active leaf (`[:authenticated :cart :browsing]`). The vector form is required when any state in the machine is a compound state (declares its own nested `:states`); it disambiguates "which `:browsing`?" when the same leaf-keyword could appear under multiple parents. See [§Hierarchical compound states](#hierarchical-compound-states).
- **Parallel-region machines** — `:state` is a **map of region-name → that region's keyword-or-vector-path** (`{:data :loading :form :neutral :mode :active}`). The map form is required when the machine declares `:type :parallel`; every region is active simultaneously, and each region's value follows the flat-or-compound arms above. See [§Parallel regions](#parallel-regions).

Implementations accept all three forms on read. The flat / compound arms normalise to a vector path internally for uniform manipulation; the parallel arm is preserved as a map (each region runs its own state-tree). The parallel-region arm is first-class (`:fsm/parallel-regions`, per [§Capability matrix](#capability-matrix)).

Stability invariants — every conformant implementation upholds these so snapshots survive the wire (SSR hydration per [011](011-SSR.md)) and the time-axis (Tool-Pair epoch replay):

1. **Print/read round-trip.** `(read-string (pr-str snapshot))` returns an `=`-equal value. No functions, atoms, JS objects, or other unprintable values may appear in `:data`. Implementations that need such things keep them at a sibling path in `app-db`, not inside the snapshot.
2. **No in-flight microstate.** Snapshots represent *committed* state only. A machine mid-transition is not snapshotted; the runtime takes snapshots at the boundaries of `machine-transition`.
3. **Stable shape across re-registration.** Hot-reloading a machine handler does not invalidate existing snapshots whose `:state` is still a member of the new definition's `:states`. Snapshots whose `:state` is no longer present transition to the new `:initial` and emit `:rf.error/machine-state-not-in-definition` (per [Spec 009 §Trace events](009-Instrumentation.md); the `:rf.error/` form is canonical).
4. **Versioned via `:meta`.** When a definition's transition shape changes incompatibly, bump `:meta :rf/snapshot-version` on the *definition*. Restore compares the snapshot's `:rf/snapshot-version` to the definition's; mismatch emits `:rf.error/machine-snapshot-version-mismatch` (per [Spec 009 §Trace events](009-Instrumentation.md)).

### Restore semantics

The contract is "replace `runtime-db` at `[:rf.runtime/machines :snapshots <id>]` with the snapshot value." There is no separate restore-callback, no init event firing again — the snapshot *is* the state. SSR hydration ([011](011-SSR.md)) and Tool-Pair epoch restore both use this contract.

See [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot).

### Reserved snapshot-internal keys

Beyond the user-facing `{:state :data :tags? :meta?}` shape above, the runtime stamps a **closed** set of `:rf/*`-prefixed slots inside the snapshot (some at the snapshot root, some inside `:data`, one inside `:meta`) to thread per-machine bookkeeping through pure transitions and the SSR-survivable persisted state. These slots are framework-owned: user code MUST NOT write under them; conformance fixtures that pin them MUST treat them as the runtime's by-product. The set is **fixed-and-additive** — existing names cannot be repurposed; new keys are added by Spec change.

| Reserved key | Location | Value | When written / cleared |
|---|---|---|---|
| `:rf/spawn-counter` | snapshot root | `{<id-prefix> <int>}` per-prefix integer-counter map | Bumped by the pure spawn-id allocator on every `:spawn` / `:spawn-all` / hand-emitted `:rf.machine/spawn` so id sequencing is deterministic from the snapshot. Stamped as `{}` at snapshot synthesis; persists across `pr-str` / `read-string`. |
| `:rf/machine-type` | snapshot root | `<machine-id>` keyword OR an inline-`:definition` spec map | Stamped by the spawn-fx onto a SPAWNED actor's snapshot (absent on singleton snapshots) so the actor's TYPE — and therefore its handler — is recoverable purely from the (revertible) snapshot. A `:machine-id` spawn stores the registered TYPE keyword (the type outlives instances); an inline `:definition` spawn stores the spec map directly. The lazy resolver reads it on dispatch to re-materialise the actor's handler; the epoch restore precondition reads it to admit a spawned-actor snapshot as a valid restore target. This is what makes a spawned actor's LIVENESS a pure function of runtime-db (per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)). Persists across `pr-str` / `read-string` and through SSR hydration / epoch replay. |
| `:rf/bootstrap-pending?` | snapshot root | `true` (else absent) | Stamped at snapshot synthesis (singletons) and by the spawn-fx (spawned actors). The first event addressed to the machine runs the initial-entry cascade, then clears the slot via `dissoc`. NEVER `true` on a snapshot that has already processed an event. The slot survives `pr-str` round-trip so a snapshot persisted mid-bootstrap (the SSR boundary case) resumes correctly. Per [§Initial-state `:entry` fires on machine creation (start)](#initial-state-entry-fires-on-machine-creation-start). |
| `:rf/after-epoch` | inside `:data` | `{<decl-path-vector> <non-negative int>}` | The wall-clock `:after`-timer epoch map for flat / compound machines, keyed **per scheduling node** (its declaring state path), per [§Delayed `:after` transitions](#delayed-after-transitions) §Hierarchy interaction. `commit-snapshot` bumps ONLY the entries for nodes the transition exits or enters; a still-active parent above the LCA keeps its entry — and thus its in-flight `:after` timer — across a child-only sibling transition. The synthetic `:rf.machine.timer/after-elapsed` event carries `[delay-key epoch decl-path]`; the runtime fires the transition iff the scheduling node is still on the active path AND the carried epoch equals that node's current per-path entry. (Per-node, not a single scalar — a single scalar could not keep a parent's timer live across a child transition that itself bumps the counter; per Spec 005 §Hierarchy interaction the per-level tracking is normative.) |
| `:rf/after-epoch-by-region` | inside `:data` | `{<region-name> {<decl-path-vector> <non-negative int>}}` | Per-region `:after`-timer epoch map for parallel-region machines, per [§Per-region `:always` / `:after` / `:spawn` scoping](#per-region-always--after--spawn-scoping). Replaces `:rf/after-epoch` when `:type :parallel`; each region carries its own per-decl-path map so a sibling region's transition does not invalidate this region's in-flight timers via the shared `:data` slot. |
| `:rf/self-id` | inside `:data` | `<spawned-machine-id>` keyword | Stamped by the spawn-fx on a spawned actor's initial `:data` so the actor knows its own address (e.g. for self-`:dispatch`). Equal to the gensym'd spawned id; absent on singleton-machine snapshots. |
| `:rf/parent-id` | inside `:data` | `<parent-machine-id>` keyword | Stamped by the spawn-fx on a declarative-`:spawn` / `:spawn-all` spawned actor's initial `:data`. The finalize-cascade reads it to locate the parent's snapshot for `:on-done`. Absent on hand-emitted (non-declarative) spawns. |
| `:rf/invoke-id` | inside `:data` | `<vector-of-keywords>` — absolute prefix-path of the `:spawn`-bearing state node (the declarative spawn invocation path) | Stamped by the spawn-fx on a declarative-`:spawn` / `:spawn-all` spawned actor's initial `:data`. Together with `:rf/parent-id` it addresses the runtime spawn-registry slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`. This is the **invocation-path** identity (was the overloaded `:rf/spawn-id`), distinct from a spawned actor's instance address (`:rf/self-id`) and from an explicit actor-address INPUT (`:fixed-actor-id` on the InvokeSpec). |
| `:rf/spawn-all-id` | inside `:data` | `<vector-of-keywords>` — `:spawn-all`-bearing state's prefix-path | Stamped by the spawn-fx on each child of a `:spawn-all`. The finalize-cascade uses it to locate the parent's join bookkeeping at `[:rf.runtime/machines :spawned <parent-id> <invoke-all-id>]`. |
| `:rf/spawn-all-child-id` | inside `:data` | child-machine-id keyword (the `:id` of the child in the parent's `:spawn-all` `:children` map) | Stamped alongside `:rf/spawn-all-id` so the finalize-cascade can mark exactly which child finished. |
| `:rf/spawned` | inside `:data` (on the **SPAWNING / parent** machine) | `{<invoke-id> <spawned-id-or-children-map>}` — per-invoke map keyed by the `:spawn`-bearing state's absolute prefix-path; value is the bare `<spawned-id>` for a single `:spawn`, or a `{<child-id> <spawned-id>}` map for a `:spawn-all` | Written by the **pure transition reducer** at allocate-time, binding the assigned actor id(s) into the SPAWNING machine's own `:data` — the re-frame2 spelling of XState v5's `spawn(...)`-into-`context` capture. An action reads its own `:data` (`(get-in data [:rf/spawned <invoke-id>])`) to obtain the id of an actor it spawned and emit `[:rf.machine/destroy <id>]`, with no external-atom side-channel and no runtime-db reverse-index coupling. The REVERSE direction of `:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id` (those record the CHILD's lineage on the CHILD; this records the CHILD's id on the PARENT), keyed by the SAME `<invoke-id>` the child carries under `:rf/invoke-id`, so it mirrors the runtime registry slot `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`. Keyed (not a lossy single 'last' slot) so multi-spawn never clobbers. Always-written on a declarative spawn; absent on hand-emitted spawns. Region-scoped spawn keys are the in-region prefix-path (the region machine's own frame of reference). See [§Recording the spawned id user-side](#recording-the-spawned-id-user-side). |
| `:rf/snapshot-version` | inside `:meta` | `int` | Versioning slot for snapshot/definition compatibility (invariant 4 above). When a definition's transition shape changes incompatibly, the author bumps `:meta :rf/snapshot-version`; restore compares the snapshot's version against the definition's and emits `:rf.error/machine-snapshot-version-mismatch` (or, on the epoch-restore path, `:rf.epoch/restore-version-mismatch`) on disagreement. The epoch-restore probe resolves the *current* definition the same way dispatch does: a singleton by its snapshot key, a **spawned actor by its `:rf/machine-type`** (the instance-id key names no registered handler) — so a hot-reloaded spawned-actor TYPE's version drift is caught, not silently accepted. Per [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) and [Tool-Pair §Time-travel](Tool-Pair.md#time-travel). |

**Persistence posture.** The only transient snapshot-root slot is `:rf/bootstrap-pending?` (cleared on first event). All other slots ride the snapshot across `pr-str` / `read-string` and through SSR hydration ([011](011-SSR.md)) and Tool-Pair epoch replay. **Finality is computed, never stamped.** There is no `:rf/finished?` snapshot slot: the lifecycle-handler boundary *recomputes* whether the post-transition snapshot has finished — `:final? true` on the active leaf for a flat / compound machine, or every region's active leaf `:final?` for a parallel machine — directly from the post-transition `:state`, then fires `:on-done` + auto-destroy. Keeping finality a pure recompute (rather than a transient flag carried on the snapshot) leaves the pure `machine-transition` surface free of runtime-only bookkeeping, so the conformance corpus and JVM pure-fn tests see exactly the user-facing `{:state :data}` shape. See [§Final states](#final-states-final--on-done--output-key).

**Sibling vocabulary — runtime-stamped machine-spec keys (NOT snapshot-internal).** The runtime ALSO stamps a small set of `:rf/*` slots on the live machine-spec value (the runtime's record threaded through `apply-transition-once` and the lifecycle handlers) — these are NOT snapshot-internal and do NOT persist; they are reconstructed at handler-call time from the registrar and the dispatched event:

- `:rf/frame` — the owning frame's id (defaulting to `:rf/default`)
- `:rf/platform` — the active platform (`:client` / `:server`) per [011](011-SSR.md)
- `:rf/parent-id` — on the live machine-spec record: the machine's own id for a singleton, the parent's id for a spawned actor (trace addressing). NOTE this is a different binding from the `:rf/parent-id` stamped inside a spawned actor's `:data` (always the parent's id) — disambiguate by location
- `:rf/region` — present iff the spec is a synthetic region-machine of a `:type :parallel` parent; the region-name keyword scoping `:after`-epochs per [§Per-region scoping](#per-region-always--after--spawn-scoping)
- `:rf/transition-pure` — the sentinel parent-id used by the pure-transition path so `intercept-invoke-all-event` (and analogous join-bookkeeping interceptors) recognises a no-op call and short-circuits without consulting `app-db`. Stamped only by callers exercising the pure-fn `machine-transition` (conformance corpus, JVM pure-fn tests, SSR machine-pure surface); absent during normal handler-driven drains.

These spec-level keys are reachable from callbacks via the unified context-map's `:meta` key — `(fn [{:keys [data event meta]}] ...)` reads the value directly. No opt-in required (the context map is uniform across every callback slot).

**Open-map invariant.** Snapshots are open maps: user `:data` keys at any depth are fine. The runtime-reserved set above is the **closed** subset of `:rf/*`-prefixed slots the runtime owns inside the snapshot. The migration agent flags any user write to `[:rf.runtime/machines :snapshots <id> :data :rf/<reserved>]` or to `[:rf.runtime/machines :snapshots <id> :rf/<reserved>]` as a collision.

**Why the reserved keys live at the same level as user data — and the `:rf/runtime` consolidation that wasn't.** The audit (Finding 4) flagged that 7 reserved `:rf/*` keys interleave with user data inside `:data`, and that a single `(:rf/runtime data)` sub-map would consolidate the surface a user sees when introspecting a snapshot in dev tools. The consolidation was considered and **deferred** — it is principled but requires (a) a `:rf/snapshot-version` bump (every persisted snapshot in `localStorage` / Tool-Pair epoch history needs migration), (b) a conformance-fixture sweep (every fixture that pins a `:rf/*` slot under `:data` needs the new path), and (c) a migration-agent rule update. The cost is real and the win (smaller user-visible introspection surface) is presentational rather than load-bearing. The current shape is defensible: the `:rf/*` namespace is a documented closed catalogue, the open-map invariant catches collisions at the migration-agent boundary, and tools that present snapshots in a dev UI can filter the closed `:rf/*` set out of the user-data view without a schema change (10x's app-db panel and Xray's snapshot lens both do this). The consolidation remains a candidate for a future incompatibility-budgeted change (the next time a `:rf/snapshot-version` bump is needed for orthogonal reasons), at which point the sweep cost is amortised. Per audit Finding 4.

The same catalogue is mirrored in [Conventions §Reserved snapshot-internal keys (machine runtime)](Conventions.md#reserved-snapshot-internal-keys-machine-runtime) for cross-spec discoverability.

### Reserved synthetic events

The machine runtime emits a small **closed set** of synthetic event vectors that are not user-dispatched and do not come from `reg-event-*` — they are framework-generated and threaded through the standard dispatch pipeline so they appear in traces, conformance fixtures, and tooling exactly like any other event. User code MUST NOT register a handler for these names or hand-dispatch them; they are reserved for the runtime. The set is **fixed-and-additive** — existing names cannot be repurposed; new ones are added by Spec change.

| Synthetic event vector | Source | Reaches | Purpose |
|---|---|---|---|
| `[:rf.machine.spawn/spawned]` | The `:rf.machine/spawn` fx handler (per [§Synthetic `[:rf.machine.spawn/spawned]` on spawn](#synthetic-rfmachinespawnspawned-on-spawn)) when the spawn args carry no `:start`. | The spawned actor — dispatched as `[<spawned-id> [:rf.machine.spawn/spawned]]` after the initial-entry cascade settles. | Generic-child kick-off shape. Machines that need to do work on spawn declare `:on :rf.machine.spawn/spawned` (legacy kick-off form) or `:entry :fire-request` on the initial state (canonical ; the synthetic event still flows. A non-handler simply has no clause for it — and because the id is reserved-`:rf/*` framework lifecycle traffic it does **not** emit `:rf.machine.event/unhandled-no-op`, per the reserved-`:rf/*` carve-out in [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)). |
| `[:rf.machine/start]` | The eager creation kick (dispatched by the surrounding program as `[:machine-id [:rf.machine/start]]`) AND the initial-entry cascade (per [§Synthetic creation marker — `[:rf.machine/start]`](#synthetic-creation-marker--rfmachinestart)). | As the eager kick: the machine handler, which runs the initial-entry cascade then STOPS (a pure init-kick — never re-fed into the transition step). As the placeholder: the initial-entry `:entry` action(s), threaded as the context-map's `:event` value. Never reaches an `:on` map. | The creation marker (xstate `createActor(m).start()` / `xstate.init`). `:entry` actions that need to distinguish creation from user-driven entry destructure `:event` and check the first element against `:rf.machine/start`. |
| `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]` | `re-frame.machines.timer` (per [§Epoch-based stale detection](#epoch-based-stale-detection)). Scheduled via `:dispatch-later` at state entry for every `:after` entry; fires when the wall-clock window elapses. | The parent machine — dispatched as `[<machine-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch>]]`. Handled by the machine handler's `:after`-dispatch path. | Wire format between `schedule-after-timer!` and `pick-after-transition`. `<delay-key>` is the literal `:after` map key (a `pos-int?`, a subscription vector, or the resolved fn-output) that identifies which `:after` entry's timer fired; `<epoch>` is the value of `:rf/after-epoch` (or, for parallel-region machines, the region's slot in `:rf/after-epoch-by-region`) captured at scheduling time. The handler compares the carried `<epoch>` to the snapshot's current value; on mismatch the timer is stale and silently dropped (per [§Epoch-based stale detection](#epoch-based-stale-detection)). |

Conformance harnesses that dispatch these vectors directly (per the JVM pure-fn tests for `:after` timers) do so by emulating the runtime — they're exercising the same wire shape the runtime uses internally, not registering new handlers for these names.

### Pure-call no-op shim (SSR / pure introspection)

`re-frame.machines.join`'s `intercept-invoke-all-event` interceptor (the join-bookkeeping wiring per [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all)) reads from **runtime-db** at `[:rf.runtime/machines :spawned <parent-id> <invoke-all-id>]` to advance child-completion bookkeeping. When the interceptor is invoked through the **pure-call path** — exercising `machine-transition` directly without a live frame, i.e. the conformance corpus, JVM pure-fn tests, the SSR machine-pure surface — there is no `[:rf.runtime/machines :spawned]` slot seeded in runtime-db, so the interceptor would either error or write spurious join-state.

The contract: when no join-state is seeded for the active `(invoke-all-id, child-id)` pair, the interceptor returns its standard `{:db db :fx}` shape (a no-op effect-map) and emits no trace. **Externally observable behaviour:** pure-call invocations of a `:spawn-all`-bearing machine do NOT advance join bookkeeping; they exercise the transition reducer only. Authors of pure-call test corpora can rely on this — driving the parent through a sequence of events under `machine-transition` will run every transition, action, and entry/exit cascade, but the parent's `:spawn-all` child-completion bookkeeping is unobserved (since the runtime spawn registry isn't in scope).

The shim is keyed off the sentinel `:rf/transition-pure` parent-id stamp on the spec record (per the sibling-vocabulary list in [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys)) — the pure-call path stamps the sentinel; live-frame invocations stamp the actual parent's id. The interceptor short-circuits on the sentinel without consulting `app-db`.

## Where snapshots live

Every machine snapshot lives at a fixed reserved path: **`[:rf.runtime/machines :snapshots <machine-id>]`** in the frame's **runtime-db** partition (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)). The runtime owns this path; users do not pick a path per machine and `make-machine-handler` does not accept a `:path` key.

For the registration `(rf/reg-event :drawer/editor (rf/make-machine-handler {...}))`:

```clojure
;; in the frame's runtime-db, after initialisation:
{:rf.runtime/machines {:snapshots {:drawer/editor {:state :idle :data {:circle-id nil ...}}}}}
```

> **Snapshot is lazily initialised.** Registration creates the *handler*, not the snapshot. The first time the machine handler runs (the first dispatched event addressed to this id), the runtime resolves the snapshot via `(or (get-in runtime-db [:rf.runtime/machines :snapshots <id>]) <initial-from-spec>)` — so before the first event, `(get-in (rf/runtime-db-value frame-id) [:rf.runtime/machines :snapshots :drawer/editor])` returns `nil` and `@(rf/subscribe [:rf/machine :drawer/editor])` returns `nil`. The lifecycle trace `:rf.machine.lifecycle/created` (per [009](009-Instrumentation.md)) is emitted at registration to mark the handler's appearance in the registry — it does NOT imply the snapshot exists in runtime-db yet. Views that need to render before any event reaches the machine should treat `nil` as "not yet initialised" and tolerate it (or bring the snapshot alive ahead of any user event with the eager `[:machine-id [:rf.machine/start]]` kick, per [§When creation happens — eager start vs lazy first event](#when-creation-happens--eager-start-vs-lazy-first-event), if appearance-without-event is required).

For a spawned actor whose gensym'd id is `:request/protocol#42`:

```clojure
{:rf.runtime/machines {:snapshots {:request/protocol#42 {:state :loading :data {:url "/foo"}}}}}
```

`:rf.runtime/machines` is a **reserved runtime-db child** (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)); inside it, `:snapshots` holds the per-machine snapshot map — a `[:map-of :keyword :rf/machine-snapshot]` keyed by the machine's registered id. User code MUST NOT write under `[:rf.runtime/machines ...]`; it reads machine state through the `[:rf/machine <id>]` subscription, never raw runtime-db paths.

Why the locked path — the load-bearing reason is [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): locating snapshots in the **runtime-db partition** (part of the one frame-state container) is the named mechanism by which machine state inherits revertibility. When a frame's frame-state reverts, every machine snapshot reverts with it. A parallel ActorRef registry or a per-machine atom would put machine state outside frame-state and break the goal. The five concrete consequences below all flow from that:

1. **Encapsulation.** A machine's snapshot is its private state, in the framework-owned runtime-db; app-db is the rest of the app. The partition keeps the boundary visible at a glance and makes app-db a pure application contract.
2. **No path collisions.** Two features that both want a `[:foo :flow]` machine cannot accidentally share a snapshot location. Ids are already unique within a frame; reusing them as the in-runtime-db key inherits that uniqueness for free.
3. **Tooling.** `(get-in (rf/runtime-db-value frame-id) [:rf.runtime/machines :snapshots])` enumerates every live machine snapshot in one read. Pair tools, 10x, and conformance harnesses use this directly.
4. **Per-frame isolation is automatic.** Each frame has its own runtime-db, and thus its own `[:rf.runtime/machines :snapshots]` map. The same machine id can exist in multiple frames; their snapshots are isolated by virtue of living in different frames' runtime-dbs (per [002 §Frames](002-Frames.md)). Inside one frame, the id is unique.
5. **AI-amenability.** "Where is the snapshot?" has one answer at all times. AIs do not need to consult per-machine metadata to find state — and they find it in runtime-db, not interleaved with app data.

> **Cost: feature-locality.** Putting machine snapshots in the runtime-db partition means they don't sit alongside their feature's own app-db slice. A user inspecting `[:auth ...]` in app-db won't see the auth flow's machine snapshot there — it lives in runtime-db at `[:rf.runtime/machines :snapshots :auth/login-flow]`. This is a real cost. Machine state has *one* structural location, which gives uniform tool support, atomic revertibility per [Goal 2 of 000-Vision](000-Vision.md#frame-state-revertibility), a host-independent storage scheme, automatic SSR hydration / persistence / undo, **and a noise-free app-db**. Tooling can present a feature-local view (10x's app-db panel can render a "Machines" section adjacent to the feature's data) without compromising the storage scheme.

> **The from-scratch `{:db fresh-map}` footgun is structurally gone under the partition.** Machine snapshots are **runtime-db**, and an ordinary `:db` effect (`{:db (build-fresh-app-db ...)}`) replaces **only app-db** (per [002 §An ordinary `:db` return replaces only app-db](002-Frames.md#an-ordinary-db-return-replaces-only-app-db)). A fresh-map `:db` return cannot touch runtime-db — there is nothing to drop and no preservation code needed. There is no `:rf/runtime` root inside app-db (per [Conventions §The legacy `:rf/runtime` root](Conventions.md#the-legacy-rfruntime-root-hard-error-in-final-form)). Machine snapshot writes are themselves runtime-db writes (`:rf.db/runtime` effects from the framework-authority machine handler), so they never collide with app `:db` writes in either direction.

The runtime composes the snapshot-map's schema from the registered machines' `[:schemas :data]` slots (per [§Schema validation](#schema-validation)): the framework-owned **runtime-db** validator (`reg-runtime-schema`, per [010 §App schemas validate the app-db partition only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only) and [Spec-Schemas §`:rf/runtime-db`](Spec-Schemas.md#rfruntime-db)) constrains `[:rf.runtime/machines :snapshots]` to `[:map-of :keyword :rf/machine-snapshot]`, and per-machine entries refine `:rf/machine-snapshot` against each machine's declared `[:schemas :data]` schema (which describes the `:data` shape). Violations surface at the `:where :machine-data` boundary — row 7 of the per-step recovery table per [Spec 010 §Per-step recovery](010-Schemas.md#per-step-recovery).

### What the Single Store gives us for free

Because every machine's snapshot lives in the **runtime-db** partition at `[:rf.runtime/machines :snapshots <id>]` — not in a parallel ActorRef registry, not in a per-machine atom — every facility re-frame already provides for a frame's durable value automatically extends to machines (the runtime-db partition reverts, serialises, and hydrates atomically with app-db as one frame-state):

- **Undo / redo.** An undo interceptor that snapshots the frame-state before/after a handler captures machine state along with everything else.
- **Time-travel debugging.** Tool-Pair's epoch buffer records `:frame-state-before` / `:frame-state-after` on each drain; rewinding restores machines to their prior snapshot at no extra cost.
- **SSR hydration.** The `:rf/hydrate` event installs a coherent frame-state from the server-supplied payload (per [011](011-SSR.md)) — replacing both the app-db and runtime-db partitions; machine snapshots ride along with the rest of the state — no separate hydration channel.
- **Persistence (serialise-out today; production write-back deferred).** Serialising the frame-state (app-db + the serializable runtime-db projection) to localStorage / IndexedDB / a server endpoint serialises machines too — the *read/serialise* half is available now (it only reads a frame value). The *write-back* half — installing a deserialised runtime-db payload back into `[:rf.runtime/machines :snapshots <id>]` on reload so the next event sees the restored machines — has **no production-legal install path yet**: `:rf/hydrate` installs a runtime-db payload but is SSR-owned (a server-supplied payload, per [011](011-SSR.md)); a plain handler returning `:rf.db/runtime` is diagnosed (runtime-db writes are framework-authority — [§Where snapshots live](#where-snapshots-live)); and the epoch surface (`epoch/replace-frame-state!`) is dev-tier and elided from production builds. Application-driven persistent restore therefore awaits a production-legal frame-state install primitive (see the note below); until it lands, machine-snapshot round-tripping is available in the SSR-hydration and dev/time-travel paths above, not as an app-authored persist-and-reload loop.

  > **Install-primitive decision (deferred, operator).** A production-legal `:rf/install-frame-state` primitive — an app-authorable, non-SSR, non-dev way to write a deserialised runtime-db (or whole frame-state) payload back into a live frame — does not exist. It is the same missing primitive the local-first disposition names; if that disposition is ever "deferred-capable," this primitive is its keystone. Whether to home it here, its authority boundary (who may write runtime-db), and its schema-validation gate are open questions for the operator, out of scope for this spec today.
- **Conformance fixtures.** A fixture's `:fixture/expect :final-app-db` covers app data, and a frame-state expectation covers machine state without needing a machine-specific assertion.
- **Schema validation.** The framework-owned runtime-db validator (`reg-runtime-schema`, per [010 §App schemas validate the app-db partition only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only)) validates the whole machine map under `[:rf.runtime/machines :snapshots]`; per-machine `[:schemas :data]` slots refine it against each machine's `:data` shape at the `:where :machine-data` boundary (per [§Schema validation](#schema-validation); row 7 of [Spec 010 §Per-step recovery](010-Schemas.md#per-step-recovery)). Runtime-db paths are NOT validated by `reg-app-schema` — that validates the app-db partition only.
- **Trace replay.** Tool-Pair epochs replay events against `:frame-state-before` to reproduce a session; machine transitions replay along with everything else because their state is in the frame's runtime-db.
- **Snapshot-and-restore.** The epoch surface (`epoch/restore-epoch!` + `epoch/replace-frame-state!`) captures the frame-state and reapplies it; machines come back with the rest of state. This holds for SPAWNED actors too, not just singletons: a spawned actor's liveness is its snapshot's presence in the (revertible) frame value (per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)), so rewinding past a spawn removes the actor and rewinding past a destroy re-materialises a working handler — with zero registrar drift.

The argument: **the Single Store invariant pays off again.** Every frame-state capability re-frame already ships extends to machines without a single line of machine-specific implementation. This is why machine snapshots live in the frame's durable value (the runtime-db partition) rather than in a parallel substrate. The undo / redo, time-travel, persistence, and snapshot-restore items above are not coincidences — they are the concrete consequences of the named [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) when machine state lives inside the frame's persistent value.

## Transition table grammar

A transition table is pure data. Top-level shape:

```clojure
{:initial <fsm-keyword>                     ;; required — initial state
 :data    {<initial data>}                  ;; optional — initial data map
 :schemas {:data <schema>, ...}             ;; optional — machine-level schema declarations; `[:schemas :data]` validates `:data` at the `:where :machine-data` boundary (see §Schema validation)
 :guards  {<keyword> <fn>, ...}             ;; optional — machine-local named guard impls
 :actions {<keyword> <fn>, ...}             ;; optional — machine-local named action impls
 :states  {<fsm-keyword> <state-node>, ...} ;; required
 :on      {<event-id> <transition>, ...}    ;; optional — top-level fallback
 :meta    {<user-keys> ...}}                ;; optional — e.g. :rf/snapshot-version
```

The snapshot's location in `runtime-db` is `[:rf.runtime/machines :snapshots <id>]` — runtime-managed and not part of the transition-table grammar. See [§Where snapshots live](#where-snapshots-live).

> **The transition-table spec map MUST NOT carry `:id`.** A machine's id is the surrounding registration's event-id (the first arg to `reg-event` or, for dynamic instances, the gensym'd id allocated by the `[:rf.machine/spawn ...]` fx), not a field on the spec map. The runtime derives the id at handler-call time from the dispatched event vector's first element. Keeping `:id` out of the spec map keeps it a pure description of behaviour and lets the same spec value register against multiple ids if the application wants two independent machines with the same body.

#### Transition table top-level keys

| Key | Where | Notes |
|---|---|---|
| `:initial` | top-level | required — the initial FSM-keyword |
| `:data` | top-level | optional — initial data map |
| `:schemas` | top-level | optional — machine-level schema declarations. A closed sub-key map: `:data` (the live, wired category — validates the machine's `:data` slot at every macrostep boundary + at bootstrap; failures emit `:rf.error/schema-validation-failure :where :machine-data` and roll back the cascade), plus the declaration-only categories `:events` / `:tags` / `:meta` (abstract values; no wired behaviour yet); `[:schemas :output]` is live (the `:where :machine-output` boundary — see [§The `:schemas` map](#schema-validation)). `[:schemas :input]` and any unknown sub-key fail loud at registration (`:rf.error/machine-bad-schemas-key`); a non-map `:schemas` fails with `:rf.error/machine-bad-schemas`. See [§Schema validation](#schema-validation). |
| `:guards` | top-level | optional — `{<keyword> <fn>}` map of machine-local named guards; referenced by keyword from `:guard` slots |
| `:actions` | top-level | optional — `{<keyword> <fn>}` map of machine-local named actions; referenced by keyword from `:action` / `:entry` / `:exit` slots |
| `:meta` | top-level | optional — e.g. `:rf/snapshot-version` |
| `:states` | top-level | required — map of FSM-keyword → state node |
| `:on` | per-state and top-level | event-driven transition map |
| `:on` keys | exact event keyword, `:ns/*` namespace wildcard, or `:*` total wildcard | resolved most-specific-first: exact > `:ns/*` > `:*` (see [§Wildcard transitions](#wildcard-transitions)) |
| `:entry`, `:exit` | per-state | one fn or one keyword reference into the machine's `:actions` map |
| `:spawn` | per-state | declarative spawn-on-entry / destroy-on-exit child actor — sugar that desugars at registration time per [§Declarative `:spawn`](#declarative-spawn) |
| `:spawn-all` | per-state | declarative **spawn-and-join** of N parallel child actors (sugar over N `:spawn`s plus a join condition) — see [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all) |
| `:type :choice` + `:choice` | per-state | a **transient / choice** state: a routing node that resolves immediately on entry to the first guard-passing candidate — sugar over `:always`, see [§`:type :choice` (transient / choice states)](#type-choice-transient--choice-states) |
| `:internal-events` | top-level | optional — a **set** of keyword event-ids the machine raises + handles privately. An external dispatch of one is refused at the machine dispatch boundary; an internal `:raise` of it is handled normally — see [§Public / private `:internal-events`](#public--private-internal-events) |
| transition shape | per-event | `{:target :guard :action :meta}` |
| multiple-candidate transitions | per-event | vector of guarded specs, first-match-wins |
| self-transitions | per-event | omit `:target` or a self/ancestor `:target` (internal, default); add `:reenter? true` for external (exit+re-enter) |
| per-state `:meta` | per-state | tooling-visible, e.g. `{:terminal? true}` |

### State nodes

Every state in `:states` is a map. The complete state-node grammar — every key the v1 CLJS reference recognises:

```clojure
{:on      {<event-id> <transition>, ...}    ;; event-driven transitions
 :always  [<guarded-transition>, ...]        ;; eventless transitions (see §Eventless `:always`)
 :after   {<delay> <transition>, ...}        ;; delayed transitions (see §Delayed `:after`)
 :timeout <duration>                         ;; this state must finish before <duration> (see §`:timeout` / `:on-timeout`); requires :on-timeout
 :on-timeout <transition>                    ;; transition fired when :timeout elapses
 :tags    #{<keyword>, ...}                  ;; runtime-projected onto snapshot's :tags (see §State tags)
 :entry   <fn-or-keyword>                    ;; ran on entering this state; keyword resolves into :actions map
 :exit    <fn-or-keyword>                    ;; ran on exiting this state; keyword resolves into :actions map
 :spawn  <invoke-spec>                      ;; spawn child on entry; destroy on exit (see §Declarative :spawn)
 :spawn-all <invoke-all-spec>               ;; spawn N children in parallel and join (see §Spawn-and-join via :spawn-all)
 :type    :choice                            ;; marks a transient / choice state (see §:type :choice); requires :choice, forbids ordinary state keys
 :choice  [<guarded-transition>, ...]        ;; iff `:type :choice` — guarded candidates resolved immediately on entry (see §:type :choice)
 :final?  true                               ;; leaf-only — entering this state terminates the machine (see §Final states)
 :output-key <keyword>                       ;; iff `:final?` — designate which `:data` key is reported back via parent's `:on-done`
 :initial <fsm-keyword>                      ;; required IFF the state is itself compound (declares :states)
 :states  {<fsm-keyword> <state-node>, ...}  ;; nested substates — makes this a compound state
 :meta    {<user-keys> ...}}                 ;; user-defined meta (NOT used for terminal marking — see §Final states)
```

All keys are optional except `:initial` (which is required when `:states` is present — see [§Hierarchical compound states](#hierarchical-compound-states)). Capability-gating: `:always`, `:after`, `:tags`, `:spawn`, `:spawn-all`, `:states` / `:initial`, and the `:type :history` pseudo-state node (below) are claimed-capability features per [§Capability matrix](#capability-matrix) — a port that doesn't claim a capability rejects the corresponding keys at registration time with `:rf.error/machine-grammar-not-in-v1`.

One sibling-of-a-substate node is **not** an ordinary state but a **history pseudo-state** — `{:type :history :deep? <bool> :default-target <target>}` declared under a compound's `:states`. It is never occupied; it is a transition target that resolves to the compound's recorded (or default) configuration. Its grammar and constraints are specified in [§History states](#history-states-type-history--shallow--deep--default-target); it declares none of the ordinary state-node keys above.

A state node MUST NOT declare both `:spawn` and `:spawn-all` — they are mutually exclusive at the same node (a node spawning a single child uses `:spawn`; a node spawning N parallel children uses `:spawn-all`). `make-machine-handler` rejects the combination at registration time as a malformed transition table.

`:entry` and `:exit` are **single fns or single keyword references into the machine's `:actions` map** — never vectors. To run multiple actions on entry, write a fn that calls them in order (or name a compound entry in the machine's `:actions` map; the named id is richer for tooling).

`:spawn` is **declarative sugar** that `make-machine-handler` desugars into entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` fx at registration time; per-state at most one `:spawn`. See [§Declarative `:spawn`](#declarative-spawn) for the spec-spec keys, desugaring rules, composition with explicit `:entry` / `:exit`, and the deliberate omissions vs xstate.

### Transitions

A transition spec for `:on` may be:

```clojure
;; minimal — just a target
{:on {:right-click-circle :editing}}

;; with guard and action
{:on {:right-click-circle
      {:target :editing
       :guard  :circle-exists?
       :action (fn [{[_ id radius] :event}]
                 {:data {:circle-id id :initial-radius radius :preview-radius radius}})}}}

;; multiple candidates with guards (first matching wins)
{:on {:submit
      [{:target :rate-limited :guard :over-limit?}
       {:target :validating   :guard :email-valid?}
       {:target :rejected}]}}                        ;; fallthrough
```

Transition slots:

| slot | shape | when it runs |
|---|---|---|
| `:target` | FSM-keyword, `[:vector :keyword]` absolute path, or `:same-state` (self-target); omit for a targetless internal no-op | discriminates the next state |
| `:reenter?` | boolean (default `false`) | makes a self / ancestor target **external** (re-run `:exit`+`:entry`, restart `:after`/`:spawn`); a descendant target declared on a compound restarts the compound onto the named child. Without it an explicit active-path target still **re-resolves descendants** (only a targetless transition preserves them). See [§Self-transitions](#self-transitions--internal-default-vs-external-reenter) |
| `:guard` | one fn or one keyword reference (resolves into the machine's `:guards` map) | predicate; transition fires only if truthy |
| `:action` | one fn or one keyword reference (resolves into the machine's `:actions` map) | between exit and entry |
| `:meta` | map | tooling-visible, no runtime effect |

`:action` is **singular** — one fn or one keyword reference. Multiple steps compose inside the fn body or as a named compound entry in the machine's `:actions` map.

### Wildcard transitions

`:on` resolves **three event-descriptor tiers**, most-specific first:

1. the **exact** event id (`:mouse/down`);
2. the **namespace wildcard** `:ns/*` (`:mouse/*`) — matches any event in that keyword namespace (`:mouse/down`, `:mouse/up`, `:mouse/move`);
3. the **total wildcard** `:*` — matches any event.

Each wildcard is the **least-priority *enabled* transition at its level** relative to the tiers above it — it fires for any event the state does not *enabled*-handle by a more specific descriptor at the same level. "Does not handle" means *no more-specific candidate fired*, not merely *no more-specific key exists*: an `:on` entry whose guard is blocked counts as not handled, so resolution falls through to the next-coarser descriptor at the same level (exact → `:ns/*` → `:*`), then, if all are absent or blocked, on up the hierarchy — see below.

#### Namespaced (partial) event descriptors — `:ns/*`

re-frame2 events are vectors with **namespaced-keyword ids** (`[:mouse/down …]`), so the keyword namespace is the natural prefix tier. The descriptor `:mouse/*` (a valid keyword whose `name` is `"*"` and whose `namespace` is `"mouse"`) matches **any event whose namespace is `mouse`** — `:mouse/down`, `:mouse/up`, `:mouse/move` — and *only* those: it does **not** match `:keyboard/down` (namespace isolation). It lets an author factor a handler for a whole event namespace without an explicit `:*` body that re-checks the namespace.

This is re-frame2's spelling of **XState v5's partial (prefix) event descriptor** `mouse.*` (which matches `mouse.click`, `mouse.move`, …) and of [SCXML §3.12.1](https://www.w3.org/TR/scxml/#events)'s dot-delimited prefix tokens (`event="error"` matches `error.communication`, `error.execution`). XState/SCXML segment on the `.` token boundary; re-frame2 segments on the keyword's `/` namespace boundary — behavioural parity (a single prefix tier between exact and total), expressed in re-frame2's native keyword-namespace idiom rather than XState's dotted-string idiom. re-frame2's namespace prefix is a **single** level (one `/`); XState's dotted descriptors can prefix-match at any token depth (`a.*` matches `a.b.c`). For the namespaced-event factoring re-frame2 authors actually reach for (`:auth/succeeded` / `:auth/failed`, `:mouse/down` / `:mouse/up`), the single-namespace tier is the full equivalent.

A **non-namespaced** event id (a bare `:go`) has no `:ns/*` tier — only the exact key or the total `:*` can catch it.

```clojure
{:idle      {:on {:start :running
                  :*     :error}}        ;; any other event drops to :error

 :tracking  {:on {:mouse/down {:action :begin-drag}      ;; exact wins for :mouse/down
                  :mouse/*    {:action :note-move}        ;; any other :mouse/... event
                  :*          {:action :log-unknown}}}    ;; anything else

 :listening {:on {:msg/data {:action (fn [{ev :event}] {:data {:last ev}})}
                  :*        {:action (fn [{ev :event}] {:fx [[:log/unknown ev]]})}}}}
```

Precedence inside the standard transition lookup, **at each level**:

1. **Exact** event match at this level, in candidate-declaration order — the first candidate whose guard passes wins (a guarded candidate-vector falls through its own entries; an unguarded candidate is the unconditional fallback).
2. **Namespace wildcard** `:ns/*` at this level (the event id's namespace + `/*`) — consulted **whenever no exact candidate fired**, including the case where the exact key *exists* but every one of its candidates is guard-blocked. Absent for a non-namespaced event id.
3. **Total wildcard** `:*` at this level — consulted **whenever neither an exact nor a `:ns/*` candidate fired** (the key is absent, or all its candidates are guard-blocked).

A coarser descriptor fires after the more specific ones **at the same level**. Only if *no enabled exact, `:ns/*`, or `:*` candidate* exists at this level does the runtime walk up to the next level and try again (steps 1–3 repeat at each ancestor) — so a leaf `:ns/*` (or `:*`) shadows an exact match on the parent for the same event, and a guard-blocked exact at the leaf falls through to the leaf's own `:ns/*` then `:*` *before* any parent is consulted. This is **XState v5's transition-selection order**: a machine descends the priority ladder within a state (exact, partial-descriptor, catch-all) before walking out to an ancestor; a transition whose guard is not met is simply *not selected*, leaving lower-priority descriptors — including the wildcards — eligible. The full leaf-up-to-root walk is canonically specified at [§Transition resolution — deepest-wins with parent fallthrough](#transition-resolution--deepest-wins-with-parent-fallthrough); for a flat machine the path is one level deep and the three-step rule above is the whole story. If no enabled exact *or* wildcard candidate exists at any level, the snapshot is unchanged and the runtime emits a single benign `:rf.machine.event/unhandled-no-op` trace (see [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough) for the canonical name + the xstate-v5-parity rationale — an unhandled event is a no-op, not an error).

**A forbidden block is NOT a fallthrough.** Distinguish two superficially-similar exact-key cases — they resolve oppositely:

- An exact entry whose **guard is blocked** is *not enabled*, so resolution falls through to the same-level `:ns/*`, then `:*`, then up the hierarchy (the rule above).
- A **forbidden block** — `{:on {E {}}}` or `{:on {E nil}}` (see [§Forbidden transitions](#forbidden-transitions--a-child-opts-out-of-an-inherited-transition)) — is an *enabled* internal candidate (it has no guard, so it passes). It **wins its tier** and halts the walk: it does **not** fall through to a same-level `:ns/*` / `:*`, nor to a parent's exact or wildcard. The forbidden block is a deliberate "consume E here", so it shadows every coarser descriptor *and* every ancestor for that event — exactly the intent of opting out of an inherited transition. (This mirrors XState v5, where a guard-failed transition leaves lower-priority descriptors eligible while a `{E: undefined}` forbidden transition is itself selected and stops selection.)

### Self-transitions — internal default vs external `:reenter?`

re-frame2 follows the **internal-by-default** self-transition rule (introduced in XState v5 and retained by the v6 direction re-frame2 now tracks — see [§Lessons from xstate](#lessons-from-xstate-deliberate-divergences)). There are **three** distinct cases — do not collapse the first two:

- **Targetless (true internal no-op).** Omit `:target`. The transition's `:action` runs; `:exit` and `:entry` do **not**; `:after` timers are **not** restarted; declarative `:spawn` / `:spawn-all` children are **not** torn down; the **configuration — including any active descendants — is unchanged**. This is how to update `:data` without re-running any entry/exit machinery. *To preserve child states, omit the target entirely.*
- **Explicit target on the active path, *without* `:reenter?` (re-resolve descendants).** Declare a `:target` that resolves onto the **active path** — the declaring state itself (`:target :same-state` or a `:target` naming the state's own keyword), a **proper ancestor**, or a **descendant** the declaring compound names. The targeted state's **own** `:exit` / `:entry` do **not** fire (it is not re-entered), **but XState v5 re-resolves the targeted state's descendants**: the active children below the target are **exited** and the target's `:initial` chain **re-descends**. So at `[:process :step3]`, a transition declared on `:process` with `:target :process` exits `:step3` and re-enters `:process`'s `:initial` (`:step1`) — `:process` itself is *not* exited or entered. A **descendant** target named by a compound transition re-enters that targeted child (XState v5: *"child state nodes are always re-entered when targeted by transitions defined on compound state nodes"*) while the declaring compound survives. This is **not** a configuration no-op — *targeting a state re-resolves its child states to their initial state.* (Use a targetless transition if you want descendants preserved.)
- **External (`:reenter? true`).** Add `:reenter? true` to the `:target`. The state itself is **exited** (its `:exit` runs) and **re-entered** (its `:entry` runs), with the transition's `:action` firing in between. On a **compound** the re-entry restarts its subtree — `:after` timers restart from zero, `:spawn` / `:spawn-all` children are torn down and respawned. For a **self / proper-ancestor** target the re-entry re-descends the target's `:initial` chain. For a **descendant** target declared on the compound, `:reenter?` re-enters the **declaring compound** and then lands on the **named descendant** (not the compound's `:initial`) — see *parent-declared re-entry to a specific descendant* below.

`:reenter?` is meaningful **only** for a target on the active path (self / proper ancestor) **or** a descendant named by the declaring compound. For a target in a disjoint subtree the least-common-compound-ancestor already lies above both source and target, so `:exit` / `:entry` fire regardless — `:reenter?` is a no-op there. In particular a **child-declared** transition to a **sibling** does **not** re-enter the common parent even with `:reenter?` (the source is a descendant, so the domain is the unexited common ancestor).

> **This INVERTS the v4 / SCXML default.** SCXML's `<transition type="internal|external">` makes a targeted transition **external** by default (`type="internal"` opts out); XState v4 followed it (`internal: true` opted out). XState **v5 flipped the default** — targeted transitions are internal, and the inverted **`reenter: true`** is the opt-in for re-entry; the v6 direction retains this. re-frame2's `:reenter?` is that internal-by-default axis. An author trained on modern XState expects a self-target to **not** re-enter by default, and re-frame2 matches that expectation. A self / ancestor `:target` is internal by default; `:reenter? true` is the opt-in for exit + re-entry.

The external self-transition is the degenerate case (target = source) of the more general **ancestor-restart** geometry: an *external* (`:reenter? true`) `:target` naming a **proper ancestor** A on the active path restarts A — exit A's subtree (including A), then re-enter and re-initialise A. See [§Entry/exit cascading along the LCA §Computing the LCCA](#computing-the-lcca--the-longest-common-prefix-shortcut-and-its-one-exception) for the exit-set geometry that makes both cases fall out of the same LCCA rule.

**Parent-declared re-entry to a specific descendant.** `:reenter? true` on a transition **declared on** a compound `S` whose `:target` is a **descendant of `S`** restarts `S` *and lands on the named descendant*: `S` is **exited** (its `:exit` runs — `:after` timers cancel, `:spawn` children tear down) and **re-entered** (its `:entry` runs — timers restart, children respawn), then the configuration descends to the **named** descendant target (not `S`'s `:initial`). The SCXML mechanics: the transition's source is the *declaring* compound `S`, so the LCCA of `{S, descendant-target}` rises to `S`'s **parent** — `S` is a proper ancestor of the target but not of itself — putting `S` into the exit set. This is the only way to force the declaring compound's full restart while choosing the post-restart child; the `:reenter?` ancestor-restart above re-descends `S`'s `:initial` instead. A **child-declared** sibling transition is the contrast: its source is the *child*, the LCCA stays at the unexited common parent, and `:reenter?` is a no-op for it.

> **`:reenter?` vs a self-`:target` on `:always`.** An eventless `:always` may **never** declare a self-`:target` — guarded or not, with or without `:reenter?` — it is rejected at registration (see [§Self-loop forbidden at registration](#self-loop-forbidden-at-registration)); the canonical re-evaluate-until-condition loop is a **targetless** guarded `:always`. `:reenter?` is the external handle for an event-driven (`:on` / `:after`) self / ancestor transition, **not** an escape from the `:always` self-loop ban.

### Guards

A guard is **`(fn [{:keys [data event state meta] cofx :rf.cofx}] boolean)`** — a single context-map argument. **One inline fn or one keyword reference into the machine's `:guards` map** — never a compound data form.

`data` is the snapshot's `:data` slot (a plain map). `event` is the inbound event vector. `state` is the discrete FSM-keyword and `meta` is any user `:meta` declared on the snapshot — both available for introspection without an opt-in. `:rf.cofx` is the dispatch's recordable-coeffect record (see [§Causal host facts](#causal-host-facts--rfcofx-ep-0017)). The fn destructures whichever keys it needs; the runtime supplies the full map every time.

```clojure
;; destructure the keys you need — `data` is the snapshot's :data slot
:guard (fn [{:keys [data event]}]
         (some? (:circle-id data)))

;; keyword reference — resolves to (get-in spec [:guards :circle-exists?])
:guard :circle-exists?

;; compound logic — write the fn
:guard (fn [{:keys [data event]}]
         (and (active? data event) (under-quota? data event)))

;; even better — declare a named compound in the machine's :guards map
(rf/make-machine-handler
  {:guards {:active-and-under-quota?
            (fn [{:keys [data event]}]
              (and (active? data event) (under-quota? data event)))}
   :states {... :on {... {:guard :active-and-under-quota?}}}})
;; the name carries semantic meaning that visualisers / AIs read.
```

#### Snapshot introspection — `:state` / `:meta`

Guards or actions that need to branch on the discrete FSM state name or on any user `:meta` simply destructure the keys from the context map:

```clojure
:guard (fn [{:keys [data event state meta]}]
         (and (= :loading state)
              (under-quota? data)))
```

The context-map shape is uniform across every callback slot — `:state` and `:meta` are always present alongside `:data` and `:event` for `:guard` / `:action` / `:entry` / `:exit`. There is no opt-in flag and no separate 3-arity variant; the runtime delivers the full map and the user's destructure pattern decides what's bound.

#### Causal host facts — `:rf.cofx` (EP-0017)

A `:guard` / `:action` / `:entry` / `:exit` callback whose decision depends on a host fact — the wall-clock time, a random draw, a generated UUID — reads it from a **declared recordable coeffect**, **not** from an ambient host read (`(js/Date.now)`, `(rand)`, `(random-uuid)`). The dispatch's recordable coeffects ride the envelope's flat `:rf.cofx` map ([Spec 002 §Recordable coeffects](002-Frames.md#recordable-coeffects)). Folding recorded facts (rather than reading the ambient clock) is what makes a durable machine decision — and any `:data` it writes into the snapshot — **replay deterministically**: the same token replays the same decision under `restore-epoch!` / SSR hydration / replay.

The machine context map surfaces the **whole** recordable-coeffect record under the key **`:rf.cofx`** (the threading key); individual facts are reachable as flat leaves under their owner-qualified ids. A bare-fn guard/action reads the time off the record directly; a callback that wants the framework to *ensure* a fact declares it via **consumer attachment** (below).

```clojure
;; a guard / action that needs "now" reads the recorded fact, NOT (js/Date.now)
:guard  (fn [{cofx :rf.cofx}]
          (> (- (:rf/time-ms cofx) (:started-at data)) timeout-ms))

:action (fn [{cofx :rf.cofx}]
          {:data {:completed-at (:rf/time-ms cofx)}})   ;; durable, replayable
```

The `:rf.cofx` record carries `:rf/time-ms` whenever the machine is driven through the normal dispatch path (the router always stamps it). It is **absent** when the engine is driven as a pure function with no router coeffect (the conformance corpus / JVM fixtures); a destructure of `:rf.cofx` then binds `nil`, so pure-fn callers are unaffected. Region callbacks (inside a parallel machine) receive the same record.

##### Consumer attachment — declaring requirements on named entries

A fact-consuming guard/action declares its requirements with **`:rf.cofx/requires`** on the machine's **named** entry (the entry-map shape Spec 005's source-coords work established), so the declaration sits with the code that can be checked against it:

```clojure
:guards
{;; normal case — no facts consumed → bare fn, no nesting
 :retries-remaining?
 (fn [{:keys [data]}] (< (:attempts data) 3))

 ;; facts consumed → map form; the diet sits beside the destructure
 :within-retry-window?
 {:rf.cofx/requires [:rf/time-ms]
  :fn (fn [{:keys [data rf/time-ms]}]
        (< (- time-ms (:first-attempt-at data)) 60000))}}

:actions
{:schedule-retry
 {:rf.cofx/requires [:payment/retry-jitter-ms]
  :fn (fn [{:keys [data payment/retry-jitter-ms]}]
        {:data {:next-retry-in (+ 1000 retry-jitter-ms)}})}}
```

- **Inline callbacks cannot declare requirements.** Fact-consuming guards/actions MUST be named entries (the form already preferred for visualisers and AI legibility). `:rf.cofx/requires` declared on an inline callback — a bare-fn guard/action, or the key placed directly on an `:on` / `:always` / `:after` / `:on-done` / `:entry` / `:exit` slot, or on a `:guards`/`:actions` entry that is a map carrying `:rf.cofx/requires` but no `:fn` — fails registration with **`:rf.error/machine-cofx-requires-inline`** (an inline fn has no entry to attach a checkable diet to). A bare fn destructuring beyond `{:data :event :state :meta :rf.cofx}` is additionally **lintable** (`:rf.warning/machine-cofx-consume-undeclared`, a dev-only recommendation).
- At registration, `make-machine-handler` parses each named entry's `:rf.cofx/requires` (via the same `re-frame.cofx/parse-requires` the event-handler path uses — a malformed declaration is `:rf.error/cofx-request-invalid` / a duplicate id `:rf.error/cofx-name-collision`, identically) into a per-entry index, and at dispatch the handler **derives** the per-(state × event-type) ensure-set — the static union of `:rf.cofx/requires` across every guard/action any candidate transition for that event type can touch on the active state path (leaf→root), including the `:always` cascade reachable from candidate targets and from the current state — then ensures it onto the in-flight `:rf.cofx` record **before transition selection** runs. The `:always` cascade is followed to the **same multi-hop fixed point the runtime macrostep settles** (§[Drain semantics](#drain-semantics) — `A --go--> B`, `B :always--> C`, `C :always {:guard g}` is depth-2): a guard/action reached at chain depth ≥2 has its declared `:rf.cofx/requires` ensured up front exactly like a one-hop one, because the ensure step runs **once** before the whole macrostep. The closure chases every `:always` `:target` (cycle-terminating on visited paths), never stopping at the first hop — so guard-consumed facts are never generated per-fired-transition (a mid-selection host read would put nondeterminism in the fold's most sensitive spot — replay selecting a *different* transition). Generated values are written back into the record the epoch captures, so replay re-presents them. **The ensure step also re-runs for every `:raise`d internal event handled inside the macrostep** (including a user `:raise`, the synthetic compound `:on-done` `[:rf.machine/done <path>]` signal, and a parallel region's raise re-broadcast through the parent's internal-event queue): a same-macrostep raise can select a transition whose named guard/action declares its own `:rf.cofx/requires` that the *external* event's ensure-set never covered, so the engine ensures the raised event's diet — including the completed node's `:on-done` guard/action — onto the in-flight record before selecting its transition, threading the augmented record forward so a later raise / `:always` re-presents any generated value. **Ensuring honours the effective mint policy** — resolved exactly as the event-handler path resolves it (per-call `:rf.cofx/mint-policy` dispatch opt ▸ frame config ▸ the router's `:live` default): under `:strict` (replay / the `:test` preset) a declared-absent generator-backed guard/action fact is `:rf.error/missing-required-cofx`, never freshly minted; `:live` / `:explicit-live` generate. A recommended dev-only lint (`:rf.warning/machine-cofx-ambient-durable`) flags a named action declaring an *ambient*-grade id — "durable state folds facts, never reads", mechanically checkable.
- Timer (`:after`) fires, reply deliveries, and other synthetic machine events are dispatch envelopes like any other — each stamps or supplies its own `:rf.cofx`. The shipped `:after` epoch-staleness mechanism needs no coeffect machinery (its check was already facts-against-facts). **Flows do not generate** — a flow is derived state over declared inputs; flows continue reading `:rf/time-ms` from the threaded record as framework consumers of the envelope.

**The minting ladder** (preference order for "my machine needs X from the world"): (1) **derive from recorded state** where possible (`:rf/spawn-counter` is the exemplar — deterministic spawn identity from a snapshot-resident counter, no new fact recorded); (2) **ride the event payload** where the dispatch site owns the fact's meaning; (3) **recorded coeffect** only for genuinely fold-internal facts. Recorded coeffects are the last rung, not the default. (XState offers no parity guidance here — it has no replay contract; this surface is re-frame2's own.)

For a guard / action running **inside a parallel region**, the context map additionally carries `:tags` (the machine-wide active-configuration tag union) and `:all-state` (the full region-name → active-state map) — the cross-region `stateIn` substitute. These two keys are present only for region callbacks; flat / compound machines never carry them. See [§Cross-region coordination — tags as `stateIn`](#cross-region-coordination--tags-as-statein).

Compound logic is expressed via function composition or as a named entry in the machine's `:guards` map — the name carries semantic content visualisers and AIs read. Resolution is machine-scoped per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler); unresolved references fail registration with `:rf.error/machine-unresolved-guard`.

**No combinator data form (divergence from XState v5).** XState ships higher-order guard combinators — `and([...])`, `or([...])`, `not(...)`, `stateIn(...)` (from `xstate/guards`) — so authors can compose named guards declaratively, e.g. `guard: and(['isAuthed', 'hasQuota'])`. re-frame2 has **no `{:and …}` / `{:or …}` / `{:not …}` data form**; a `:guard` is exactly one inline fn or one keyword into the machine's `:guards` map (`GuardRef` in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)). Compound logic is ordinary Clojure boolean composition inside one fn, or — preferably — a *named* entry in `:guards` whose name carries the semantic content a visualiser or an AI reads (the `:active-and-under-quota?` idiom above). This is the spec's bias toward **one explicit primitive over many implicit conveniences** ([Principles](Principles.md)). An XState migrant reaching for `and`/`or`/`not` writes a fn (or a named guard) instead.

`stateIn(...)`'s substitute depends on what the guard is testing. For a guard reading **this machine's own** active state, it is the `:state` key already present in every guard's context map ([§Snapshot introspection](#snapshot-introspection--state--meta)). For the case `stateIn` actually exists to serve — **one parallel region's guard predicating on a sibling region's active state** — it is the machine-wide `:tags` union (the coarse, idiomatic substitute) or the `:all-state` region map (the precise substitute), both threaded into every region's guard/action ctx; see [§Cross-region coordination — tags as `stateIn`](#cross-region-coordination--tags-as-statein).

### Actions

An action is **`(fn [{:keys [data event state meta] cofx :rf.cofx}] effects)`** returning the `{:data :fx}` shape (or `nil`). Single context-map argument, same shape as guards (`:rf.cofx` is the dispatch's recordable-coeffect record; see [§Causal host facts](#causal-host-facts--rfcofx-ep-0017)). **One inline fn or one keyword reference into the machine's `:actions` map** — never a vector.

```clojure
;; inline — destructure :data and the event from the context map
:action (fn [{[_ id radius] :event}]
          {:data {:circle-id id :initial-radius radius :preview-radius radius}})

;; keyword reference — resolves to (get-in spec [:actions :clear-form])
:action :clear-form

;; the body lives in the machine's :actions map:
(rf/make-machine-handler
  {:actions {:clear-form
             (fn [_]
               {:data {:circle-id nil :initial-radius nil :preview-radius nil}})}
   :states {... :on {... {:action :clear-form}}}})
```

Multiple steps in one action are **fn composition**, not a vector:

```clojure
:action (fn [{:keys [data event] :as ctx}]
          (let [a (action-clear ctx)
                b (action-record-attempt ctx)]
            {:data (merge (:data a) (:data b))
             :fx   (into (:fx a []) (:fx b []))}))
```

If the composition is reused, name it in the machine's `:actions` map:

```clojure
(rf/make-machine-handler
  {:actions {:clear-and-record (fn [{:keys [data event]}] ...)}
   :states {... :on {... {:action :clear-and-record}}}})
```

This is the design rule from above: imperative composition is fns, not data DSLs; named entries in the machine's `:actions` map add semantic content visualisers and AIs can read. Resolution is machine-scoped per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler); unresolved references fail registration with `:rf.error/machine-unresolved-action`. Cross-machine reuse: define a Clojure var and reference it from each machine's `:actions` map.

### Schema validation

a machine spec MAY declare a machine-level **`:schemas`** map. The map is the single home for a machine's optional schema declarations; its **`[:schemas :data]`** entry validates the machine's `:data` slot — the user-domain extended state. Tools and optional validation learn a machine's data, events, completion payloads, tags, and meta from one place. The sub-key set is closed (see [§The `:schemas` map](#the-schemas-map) below); `:data` is the live, wired category. The map is unqualified, like `:data` / `:guards` / `:actions`:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schemas {:data DrawerData}        ;; [:schemas :data] validates :data
   :guards  {...}
   :actions {...}
   :states  {...}})
```

`[:schemas :data]`'s job is exactly the user-domain `:data` shape. The snapshot's `:state` slot is already validated at registration time (a transition targeting an unknown state fails registration with `:rf.error/machine-unresolved-target`, a malformed target with `:rf.error/machine-bad-target`); the snapshot's reserved `:rf/*` slots are framework-owned.

<a id="the-schemas-map"></a>

#### The `:schemas` map

`:schemas` is a **closed** sub-key map. The accepted categories are:

| Sub-key | Status | Role |
|---|---|---|
| `:data` | **live, wired** | the machine's working memory — exactly the data-context schema, validated at the `:where :machine-data` boundary (this section). |
| `:events` | declaration-only | machine event payloads. A machine-local home because machine events are handled by the machine's own `:on` table and dispatched as `[:machine-id [:inner-event ...]]` — `reg-event`'s `:schema` validates registered top-level events and cannot reach a machine's internal event vocabulary, so `[:schemas :events]` is additive, not a synonym for it. |
| `:output` | **live, wired** | the completion-output payload selected from a final state's `:data` via `:output-key` (the `result` the parent's `:on-done` receives — see [§Final states](#final-states-final--on-done--output-key)), validated at the `:where :machine-output` boundary (see [§Completion-output validation](#completion-output-validation) below). |
| `:tags` | declaration-only | the machine's closed tag vocabulary. |
| `:meta` | declaration-only | state/machine metadata shape. |

The **declaration-only** categories (`:events` / `:tags` / `:meta`) are accepted as part of the single-home machine contract — their `<schema>` values stay abstract — but carry **no wired behaviour** yet. `:data` (this section) and `:output` ([§Completion-output validation](#completion-output-validation)) are wired. Any **unknown** sub-key fails loud at registration with `:rf.error/machine-bad-schemas-key`; a **non-map** `:schemas` fails with `:rf.error/machine-bad-schemas`. `[:schemas :input]` is **not** accepted — state input is not adopted (a future addition would re-open it), so declaring it fails loud rather than no-opping.

> **SA-4 record — declaration-only sub-key wiring is post-v1, untracked.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md), "carry no wired behaviour **yet**" is a `:post-v1 tracked` **candidate** with no bead filed, so it remains a **post-v1, untracked note** — the declaration grammar (accept + fail-loud on unknown keys) is landed v1; only the *validator wiring* behind each accepted-but-inert sub-key is deferred. The wiring is not blocking: each sub-key already declares loudly, and `:data` / `:output` prove the validator-adapter path, so wiring a third sub-key is additive, not a redesign. Per-key reconsideration triggers (file one tracking bead per key **when its trigger fires**):
> - **`[:schemas :events]` wires when** a consumer needs **send-time payload validation** for a machine's *internal* event vocabulary — the `[:machine-id [:inner-event <payload>]]` inner form — that `reg-event`'s outer-event `:schema` (the `:where :event` boundary) cannot reach. Concretely: a real machine whose inner events carry structured payloads reports a bad-payload bug that only a `:where :machine-event` check would have caught.
> - **`[:schemas :tags]` wires when** a consumer wants the machine's **closed tag vocabulary** enforced — a `:tags <set>` slot (or a `machine-has-tag?` query) referencing a tag outside the declared set caught at registration rather than silently mis-typed.
> - **`[:schemas :meta]` wires when** a consumer wants **state/machine metadata shape** validated at the boundary where `:meta` is read (snapshot `:meta?` slot / `machine-meta`).
> - **Or mark permanently declarative** if, at v1.1 review, the accept-only grammar is judged sufficient (the schema value documents intent for AI / tooling without a runtime check) — in which case the "yet" is struck and the three sub-keys become a settled declarative-only stance. This ruling is itself the un-defer condition.

**Schema-library-agnostic — validation is optional.** A `<schema>` value is **opaque**: machine core never interprets it and requires **neither Malli nor JavaScript Standard Schema**. `[:schemas :data]` validation runs entirely through an OPTIONAL late-bound validator adapter — the registered `:schemas/validate-with-registered-fn` hook. A Malli adapter (the framework default) interprets Malli values; a project with no schema adapter still uses the `:schemas` grammar, paying zero validation cost (the hot path short-circuits when no validator is registered). The declaration grammar and the optional validator adapter are fully decoupled.

> **`[:schemas :data]` is re-frame2's spelling of XState's `context`.** re-frame2 calls a machine's working memory `:data` (not `context`), so the data-context schema is declared at `[:schemas :data]`, not XState v6's `schemas.context`. The role is identical; only the vocabulary diverges (re-frame2's `:data` tracks FSM / `gen_statem` "state data" and avoids re-frame's already-overloaded "context").

**Validation timing — the macrostep boundary.** `:data` is mutated at six sites (initial install, `:on-spawn-actions`, `:entry` actions, `:exit` actions, transition `:action`, `:always` / `:after` actions). The natural validation point is the **macrostep commit** — after the exit → transition-action → entry sequence settles and the runtime is about to write the new snapshot back to `runtime-db`. One validation per macrostep regardless of how many actions fired; the snapshot the framework would write is the value validated. This is the same lifecycle position as the existing `:where :app-db` check.

**Initial-data installation.** At machine bootstrap the initial `:data` is installed into the snapshot. The bootstrap cascade fires the initial state's `:entry` actions on the first event, then commits — the macrostep validator catches both the bootstrap typo (`:data {:circles}` declared but the schema requires `:cirles`) and any initial-`:entry` action that returns a violating `:data`.

**Spawn-time validation.** A spawned actor's initial `:data` is validated **before the snapshot lands in runtime-db** (rather than at the next macrostep commit). A failing spawn never installs — the actor never enters the runtime, and no parent state observes a half-installed child. The failure emits with `:phase :spawn` and `:rollback? false` (no commit to roll back).

**Escape-hatch validation.** A `[:rf.machine/update-snapshot {... :rf/patch {:data {...}}}]` (the [§Snapshot-level escape hatch](#snapshot-level-escape-hatch)) is validated **before the patch merges into runtime-db** — the fx computes the would-be-merged snapshot and validates its `:data` against the actor's `[:schemas :data]` schema. A failing patch is **not written** — the invalid `:data` never installs (a pre-write rejection, parity with spawn). The failure emits with `:phase :update-snapshot` and `:rollback? false` (no commit to roll back). The schema is resolved for both a singleton (`reg-machine`) and a spawned actor (its TYPE rides the snapshot's `:rf/machine-type`), so the escape hatch is covered uniformly. A machine with no `[:schemas :data]` schema, or a patch that doesn't touch `:data`, writes unchanged.

**Failure trace.** The boundary reuses the existing `:rf.error/schema-validation-failure` op with a new `:where` value:

```clojure
{:op   :rf.error/schema-validation-failure
 :tags {:where           :machine-data
        :failing-id      <machine-id>           ;; uniform error-emit alias
        :machine-id      <machine-id>           ;; domain-specific synonym
        :phase           :macrostep             ;; or :spawn / :bootstrap / :update-snapshot
        :value           <failing-:data-map>    ;; redactable per Spec 010 §`:sensitive?`
        :received        <failing-:data-map>    ;; parallels :where :app-db
        :schema          <the registered schema verbatim>
        :explain         <validator's explainer output>
        :rollback?       true                   ;; false for :phase :spawn / :update-snapshot
        :recovery        :no-recovery
        :reason          "Machine <id> :data failed schema..."}}
```

Consumers route on `:where` — the existing Issues triage, Xray projections, schema-violation-row plumbing, and the per-step attachment bead all flow through one row shape with one new `:where` case.

**Recovery — full-cascade rollback.** Machine state lives in `runtime-db`; the cascade's `:frame-state-before` / `:frame-state-after` capture the pre/post snapshot of EVERY machine along with the rest of the frame value. A failure at the `:where :machine-data` boundary rolls back the entire commit (same mechanism the `:where :app-db` boundary uses). The framework cannot surgically roll back just one machine's macrostep without affecting other state mutations the handler performed; full-cascade rollback is the only consistent option. For the Epoch panel: same blast-radius muting treatment as `:where :app-db` rollback.

**Production builds.** Per [010 §Production builds](010-Schemas.md#production-builds), the validation site is gated by `re-frame.interop/debug-enabled?` and DCEs to a no-op under `:advanced` + `goog.DEBUG=false`. The boundary is dev-only by default; apps needing production validation at system boundaries reach for the `:rf.schema/at-boundary` interceptor on the specific events that ingest untrusted machine `:data` (e.g. an SSR-hydrate that restores machine snapshots from the wire).

**Cross-reference.** Per [010 §Per-step recovery row 7](010-Schemas.md#per-step-recovery), this boundary is row 7 of the per-step recovery table; the `:where :machine-data` value is the closed-set extension to [Spec-Schemas §`SchemaValidationTags`](Spec-Schemas.md#per-category-tags-schemas). The two paragraphs at [§Where snapshots live](#where-snapshots-live) (schema composition) and in §What the Single Store gives us for free (the Schema-validation bullet) describe this surface as fact.

<a id="completion-output-validation"></a>

#### Completion-output validation (`[:schemas :output]` → the `:where :machine-output` boundary)

`[:schemas :output]` schemas the machine's **completion-output payload** — the `result` a finishing machine selects from its final state's `:data` via [`:output-key`](#final-states-final--on-done--output-key) and delivers to the spawning parent's `:on-done` callback. re-frame2 keeps **no long-lived `:output` snapshot slot** (unlike XState v6's `snapshot.output` — see [§Final states](#final-states-final--on-done--output-key)): completion is an event, so the output **flows as the completion-event payload**. The schema therefore validates that payload **at finalize time** — when the `result` is computed, immediately before it rides the `:rf.machine/done` trace and drives the parent's `:on-done`.

**The payload, not a snapshot field.** `[:schemas :output]` validates the `result` value — `(get-in child-snapshot [:data <output-key>])` — NOT the whole `:data` map (that is `[:schemas :data]`'s job) and NOT a persistent field. A machine with no `:output-key` on its final state produces a `nil` `result`; a `[:schemas :output]` schema that admits `nil` (or no `[:schemas :output]` schema at all) passes that vacuously.

**Best-effort fail-loud — no rollback.** The machine has **already reached its final state** when output is computed; the auto-destroy cascade is already running. A `[:schemas :output]` violation is therefore a **post-completion observation**: it emits the boundary trace loudly (so a mismatched completion payload surfaces as a bug in dev), but the completion **still flows** — the `:on-done` payload and the `:rf.machine/done` trace are unchanged, and there is **nothing to roll back** (`:rollback? false`). This is the post-commit best-effort asymmetry (parity with the [FX-atomicity posture](009-Instrumentation.md) — pre-commit transactional, post-commit best-effort): a schema typo on a completion payload must not be able to *deadlock* a finishing machine, only to *surface* the mismatch.

**Schema-library-agnostic, same adapter.** Validation routes ENTIRELY through the same optional late-bound `:schemas/validate-with-registered-fn` adapter the `:where :machine-data` boundary uses — `[:schemas :output]`'s `<schema>` value is opaque, machine core interprets nothing, and an app with no schema adapter pays zero cost (the hot path short-circuits when no validator is registered).

**Failure trace.** The boundary reuses the existing `:rf.error/schema-validation-failure` op with the `:where :machine-output` value and `:phase :completion`:

```clojure
{:op   :rf.error/schema-validation-failure
 :tags {:where      :machine-output
        :failing-id <machine-id>
        :machine-id <machine-id>
        :phase      :completion                  ;; the one machine-output phase
        :value      <failing-output-payload>     ;; the :output-key result; redactable per Spec 010 §`:sensitive?`
        :received   <failing-output-payload>
        :schema     <the registered [:schemas :output] schema verbatim>
        :explain    <validator's explainer output>
        :rollback?  false                        ;; the machine already finished — nothing to roll back
        :recovery   :no-recovery
        :reason     "Machine <id> completion output (the :output-key payload) failed schema..."}}
```

The value-bearing slots (`:value` / `:received` / `:explain`) route through the same `:schemas/redact-validation-tags` seam, so a `[:schemas :output]` schema that marks an output slot `:sensitive?` scrubs it before the trace fires.

**Production builds.** Per [010 §Production builds](010-Schemas.md#production-builds), the validation site is `re-frame.interop/debug-enabled?`-gated and DCEs to a no-op under `:advanced` + `goog.DEBUG=false` — parity with the `:where :machine-data` boundaries (dev-only by default, zero production cost).

**Parallel machines.** A parallel machine's `:output-key` may live on any region's final leaf (the cross-region scan — see [§Final states](#final-states-final--on-done--output-key)); the resolved `result` is validated against the one machine-level `[:schemas :output]` schema regardless of which region designated it.

#### `[:schemas :data]` is the re-frame2 analog of XState typed context

A machine's `:data` slot **is** XState's *context* — the value the machine carries across transitions. XState lets a developer declare that context's shape (via `schemas`), and Stately's inspector renders the declaration as a `Context: …` header on the chart. re-frame2's `[:schemas :data]` is the behavioural analog of that typed-context declaration: both **declare** the context shape, both make it **renderable** by tooling (the machines-viz Context panel reads `[:schemas :data]` the way Stately reads the typed-context declaration), and both pin the shape a reader can rely on. Declaring the schema at `[:schemas :data]` (rather than XState v6's `schemas.context`) keeps re-frame2's own `:data` vocabulary, but the role is identical to XState's typed context.

re-frame2 **exceeds** the XState benchmark on this surface, in one respect worth naming:

| | XState typed context | re-frame2 `[:schemas :data]` |
|---|---|---|
| Declaration | `schemas` | `[:schemas :data]` on the machine spec |
| Tooling render | Stately inspector `Context:` header | machines-viz declared Context panel |
| Enforcement | **compile-time only** — TypeScript types are erased at runtime; a value off the wire that violates the declared shape is *not* caught | **runtime validation** (via the optional registered validator adapter, e.g. Malli) at every macrostep-commit boundary, at bootstrap, and at spawn time — a violating `:data` emits `:rf.error/schema-validation-failure :where :machine-data` and rolls back the cascade (see §Schema validation above) |
| Production cost | zero (types erased) | zero — the validation site is `re-frame.interop/debug-enabled?`-gated and DCEs under `:advanced` + `goog.DEBUG=false`, so the runtime guarantee is dev-only by default with the same erased-in-production posture (production boundary validation is opt-in via `:rf.schema/at-boundary`) |

The divergence is the point: XState's typed context is a *static* contract a TypeScript compiler checks and then discards, whereas re-frame2's `[:schemas :data]` is the same declaration backed by an *actually-running* validation in dev (and an opt-in at production boundaries) — re-frame2 keeps the compile-time-equivalent declaration AND adds the runtime guarantee XState leaves to the type-erased layer, at no production cost.

### Trace events — guard evaluations and action runs

Every user-declared guard evaluation and every user-declared action invocation emits a public trace event under the reserved `:rf.machine/*` namespace (per [009 §The `:rf.machine/*` reserved namespace for trace events](009-Instrumentation.md) and):

- **`:rf.machine/guard-evaluated`** — emitted from the unified `evaluate-guard` helper at every user-declared guard call site (`:on`, `:after`, `:always`, `:spawn-all/join`). `:tags {:actor-id <live-instance-id> :guard-id <kw-or-fn> :input {:data <data> :event <event-vec>} :state <active-state> :outcome :pass | :fail | :threw :exception <Throwable on the throw path>}`. (A guard is evaluated against a LIVE actor's snapshot, so the addressed id is the running INSTANCE under `:actor-id`; `:machine-id` is reserved for the registered TYPE.) The `:state` tag carries the active state the guard ran against — the evaluating snapshot's `:state` (a leaf keyword for a flat machine, the vector path from root to leaf for a hierarchical machine, or the region's state value when the snapshot belongs to a parallel region). It is the source-path discriminator: two states that declare the same event with the same guard-id are otherwise indistinguishable by `(event, guard-id)` alone, so a single guard failure would paint both edges; stamping `:state` lets a consumer attribute the block to the one active state's edge. The synthesised always-true returned by `resolve-guard` for a nil guard-ref is NOT a user-declared evaluation — no trace. First-fail short-circuit on compound guards is preserved: subsequent legs simply do not evaluate. **A guard that throws SURFACES the error and ABORTS the macrostep (XState parity).** It emits one trace with `:outcome :threw` and `:exception <Throwable>` (observability preserved), then aborts transition selection: the candidate walk does **not** continue to the next sibling, no transition fires, the snapshot rolls back **atomically** (no write reaches `[:rf.runtime/machines :snapshots <id>]`), and the throw surfaces through the **same** failed-macrostep surface a thrown **action** takes — the `:rf.error/machine-action-exception` error category (per [§Errors](#errors)). This is the **same** failure path the bounded-depth abort uses (XState **throws** on a runaway eventless / `raise` cycle → re-frame2's `result/fail` macrostep), so a guard throw, an action throw, and a depth abort all converge on one failed-macrostep / atomic-rollback semantic. XState does **not** swallow a guard exception and silently demote to a lower-priority candidate, a wildcard tier, or an ancestor edge — neither does re-frame2.
- **`:rf.machine/action-ran`** — emitted from `run-action` for every user-declared action invocation. `:tags {:actor-id <live-instance-id> :action-id <kw-or-fn> :phase :exit | :transition | :entry | :always | :after-action | :initial-entry | :destroy-exit :input {:data <data> :event <event-vec>} :outcome <return-value> | :ok | :rf.error/action-threw :exception <Throwable on the throw path>}`. (An action runs against a LIVE actor's snapshot, so it addresses the running INSTANCE under `:actor-id`; `:machine-id` is reserved for the registered TYPE.) Success-with-nil-return collapses to `:outcome :ok` (action returned `nil`; the runtime treats it as the no-op `{}`). The throwing path emits one trace with `:outcome :rf.error/action-threw` and `:exception <Throwable>` before propagating the `result/fail` Result; the failure subsequently surfaces as `:rf.error/machine-action-exception` per [§Errors](#errors). The synthesised `(constantly nil)` no-op for a nil action-ref is NOT user-declared — no trace. Per every emit carries `:phase` from the closed set above — `:exit` / `:entry` for cascade actions, `:transition` for an `:on`-driven action, `:always` for an eventless step's action, `:after-action` for an `:after`-timer-driven action, `:initial-entry` for the creation initial-entry cascade, `:destroy-exit` for the destroy-time exit cascade. Downstream consumers (Xray's epoch panel Handler section) group rows by `:phase` directly from the trace stream.

Both traces flow through the standard trace bus, so `*handler-scope*` auto-stamps `:dispatch-id` into `:tags`. Downstream cascade-correlation (Xray's `:rf.xray/machine-transitions-for-focused-event` sub, devtools epoch buffers, conformance fixtures) groups guard/action traces with the originating event without any explicit threading. The payload schemas are pinned in [Spec-Schemas §`MachineGuardEvaluatedTags` and §`MachineActionRanTags`](Spec-Schemas.md). The redaction contract per [§Privacy — redacting machine `:data` at trace egress](#privacy--redacting-machine-data-at-trace-egress) governs the machine's `:data` egress: the MACHINE's own projection-relative `:data` classification (declared on the `reg-machine` spec as `:sensitive` / `:large` paths rooted at `[:data …]`, lowered per actor instance at spawn, `:source :machine`) scrubs matching slots in the snapshot-shaped `:before` / `:after` / `:snapshot` payloads.

**The failure is production-survivable.** A throwing action or guard (and the `:on-done` / destroy-`:exit` action variants) does NOT only ride the dev trace above — it surfaces `:rf.error/machine-action-exception` on the **always-on error-emit axis** (surface #4, NOT gated by `re-frame.interop/debug-enabled?`, per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)), so a machine throw reaches an off-box shipper (Sentry / Datadog) on a `:advanced` + `goog.DEBUG=false` build. The production-surviving record carries the machine attribution `:failing-id` (the throwing action / guard **keyword**, falling back to the actor instance id for an anonymous inline fn) and `:state` (the active state path) — machine-local code identifiers in the same privacy class as `:event-id` — so a shipper learns WHICH action / guard in WHICH state threw, not just the category. The record is STRUCTURAL-ONLY: the developer's arbitrary `:exception-data` (which may embed the same app secrets a `:sensitive` `:data` mark gates) rides the dev trace (redacted at the marks egress chokepoint) but NOT the always-on record.

#### The structured transition cascade — `:cascade` on `:rf.machine/transition`

The per-action `:rf.machine/action-ran` stream above lets a tool *reconstruct* what ran, but only by re-walking the LCA geometry to know which state each action belonged to and in what cascade order. The macrostep's headline `:rf.machine/transition` trace therefore **also carries a structured `:cascade` field** — the engine emits, in a single trace, the ordered step sequence that explains *how* the transition reached its after-state. One trace, one place for tooling to read; this is the contract Xray's epoch panel renders, and it **removes the need for app-level `:data :trail` workarounds**.

`:cascade` is a **vector of self-describing step maps in execution order**, following the ordering [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca) already defines — **exit deepest-first → transition `:action` at the LCA → entry shallowest-first + initial-descent**, then one step per `:always`/eventless microstep:

```clojure
;; one cascade step (the structural shape the consumer reads)
{:kind   :exit | :action | :entry | :microstep   ;; the structural boundary
 :state  [:running :conditioning :heating]        ;; state-path exited/entered;
                                                   ;;   the transition's decl-path for :action
 :region :climate | nil                            ;; parallel region (nil for flat/compound)
 :action :enter-heating | nil                      ;; the action id that fired
                                                   ;;   (nil ⇒ this boundary declared no
                                                   ;;    :exit/:entry action — still recorded
                                                   ;;    so the configuration walk is complete)
 :data-delta {:trail [...]}                        ;; the :data keys THIS step's action
                                                   ;;   added/changed (empty {} when no action,
                                                   ;;   or the action wrote no :data)
 :source :recorded | :default}                     ;; ADDITIVE, history-only: present ONLY on
                                                   ;;   an :entry step produced by a :type :history
                                                   ;;   restore — :recorded (replayed the owning
                                                   ;;   compound's last-active config) or :default
                                                   ;;   (no recording yet; fell back to
                                                   ;;   :default-target / :initial). Matches the
                                                   ;;   :rf.machine.history/restored event's :source.
                                                   ;;   ABSENT on every non-history step.

;; a :microstep step additionally carries the eventless step's own nested cascade
{:kind            :microstep
 :region          :climate | nil
 :microstep-index 0
 :from            :asking
 :to              :winner
 :steps           [ …exit/action/entry step maps for the eventless transition… ]}
```

Key properties:

- **Complete configuration walk.** Every state exited and entered is recorded — *including* boundaries with no declared `:exit`/`:entry` action (`:action nil`, empty `:data-delta`) — so the geometry is explainable without the spec. (An app-level `:data :trail` only captured *action-bearing* boundaries; the cascade is a superset.)
- **`:kind` is structural, not the action driver phase.** It is the closed set `:exit / :action / :entry / :microstep`. The orthogonal driver phase (`:transition` / `:always` / `:after-action` / `:initial-entry` / `:destroy-exit`) is what the per-action `:rf.machine/action-ran` emit stamps under `:phase`; the two dimensions never smear (see the `action-ran` `:phase` set above).
- **`:data-delta` is the minimal per-step contribution** — only the `:data` keys that step's action *changed*, never the whole (possibly large) `:data` map. This keeps the cascade small and side-steps a large-payload leak.
- **`:source` is an additive, history-only field.** An `:entry` step produced by a `:type :history` restore carries `:source :recorded` (the owning compound's last-active configuration was replayed) or `:source :default` (no recording existed yet, so the leaf came from the history node's `:default-target` / the compound's `:initial`), matching the `:rf.machine.history/restored` event's `:source` (see [§History states](#history-states-type-history--shallow--deep--default-target)). The key is **absent on every non-history step** — a consumer treats its absence as "ordinary cascade entry".
- **Per-region structure for parallel machines.** Each step carries its `:region`; the cascade is the per-region step sequences concatenated in region declaration order, so a consumer can group by `:region`. Flat / compound machines carry `:region nil`.
- **`:always` microsteps are explainable.** Each eventless macrostep iteration appends a `:microstep` step carrying its own nested `:steps` — so "all the steps" = the entry/exit cascade **plus** the microstep stream, rather than a bare count. This composes with the per-microstep `:rf.machine.microstep/transition` stream (the latter stays the per-microstep marker; `:cascade` is the macrostep-level structured rollup).
- **Bootstrap composition.** When one handler call both bootstraps the machine *and* processes a user event (the same call the `:before`/`:after` slots span), the initial-entry cascade's `:entry` steps prepend the event-driven steps, matching the macrostep the trace reports.

The privacy story rides the same machine-declared `:data` redaction as `:before` / `:after` (see [§Privacy — redacting machine `:data` at trace egress](#privacy--redacting-machine-data-at-trace-egress)). The field is observability via `trace/emit!` (production-elided through the standard `interop/debug-enabled?` gate), never production app-db, so it adds no new elision concern beyond the existing trace payload.

### `:machine-guard` / `:machine-action` handler-meta surfaces

`handler-meta` is a **general source-meta surface** — introspect any source-bearing thing by `(kind, id)`, not "introspect a registrar entry". Machine guards and actions are **NOT** registry kinds (there is no such registry kind, per [001 §Registry model](001-Registration.md), and `registrar/kinds` is the closed set `:event` / `:sub` / `:fx` / `:cofx` / `:interceptor` / `:view` / `:frame` / `:route` / `:head` / `:error-projector` / `:flow` / `:resource` / `:mutation` / `:resource-scope`). Their dev-only fn-source handler-meta is **DERIVED on demand** from the machine's existing `:event` registration spec, not stored as a separate registrar entry.

The `reg-machine` macro walks the literal spec form at expansion time and co-locates per-element source (`:guards {<id> {:fn .. :source-coords .. :source-code ..}}` — see [§Source-coord stamping](#source-coord-stamping)) onto each `:guards` / `:actions` entry. `reg-machine*` stores the whole stamped spec under `:rf/machine` in the machine's `:event` registration metadata. `(rf/handler-meta :machine-guard [machine-id guard-id])` dispatches the two machine kinds to a derivation that reads the source back out of that `:event` spec (the ten registrar kinds fall through to the registrar lookup). This resolves the prior **duplication** — the source the lens renders already lives on the `:event` registration; it is read, not copied into a second registrar entry. The addressing is **uniform** with the ten kinds, so the Xray focused-transition lens and re-frame-pair source-jump call sites are unchanged:

```clojure
(rf/handler-meta :machine-guard  [<machine-id> <guard-id>])
;; => {:rf/guard-id   <guard-id>
;;     :rf/machine-id <machine-id>
;;     :rf.handler/source  "(fn [{data :data}] ...)"
;;     :handler-fn    <the captured fn>
;;     :ns :line :file [:column]}        ;; per-element coord per Spec 001

(rf/handler-meta :machine-action [<machine-id> <action-id>])
;; => {:rf/action-id  <action-id>
;;     :rf/machine-id <machine-id>
;;     :rf.handler/source  "(fn [{data :data}] {:fx ...})"
;;     :handler-fn    <the captured fn>
;;     :ns :line :file [:column]}
```

The ids are 2-vectors `[<machine-id> <id>]` so a guard's id is naturally scoped to its declaring machine. `:rf/guard-id` and `:rf/action-id` are reserved markers (parallel to `:rf/cofx-id`) that the derived meta stamps so a tool reading the meta pivots on the marker rather than re-parsing the 2-vector id. (`(rf/registrations :machine-guard)` returns `{}` — there is no side-table to enumerate; tools that want the full set walk the machine's `:event` spec `:guards` / `:actions` maps directly.) `:rf.handler/source` carries the macro-time `pr-str` of the literal fn-form (parallel to the `reg-event-*` form-source capture per [Spec 009 §`:rf.handler/source`](009-Instrumentation.md)).

Production-elision per [Spec 009 §Production builds](009-Instrumentation.md): the macro emission omits the co-located `:source-coords` / `:source-code` slots under `:advanced` + `goog.DEBUG=false` (the dev arm of the `interop/debug-enabled?` gate DCEs), and the derivation is itself gated on `interop/debug-enabled?` (returning nil when the gate is false), so neither the `pr-str` fn-body bytes nor the `:rf.handler/source` keyword reach the production bundle (verified by the elision-probe co-located `:source-code` sentinels — see [009 §Production builds](009-Instrumentation.md)).

The `reg-machine*` plain-fn surface (per [§reg-machine vs reg-machine*](#reg-machine-vs-reg-machine)) bypasses the macro walker — programmatic registrations carry no co-located source under these kinds. Tools fall back to the call-site coords on the top-level `(rf/handler-meta :event <machine-id>)`, identical to the existing source-coord stamping fallback.

The runtime semantics — guard resolution + action invocation — are unchanged. The `:guards` / `:actions` maps inside the machine spec remain the source of truth the runtime resolves through; co-locating `:source-coords` / `:source-code` alongside the `:fn` carries observability metadata only, and the runtime engine reads each entry's `:fn` (bare-fn and keyword-reference entries are also accepted, per [§Source-coord stamping](#source-coord-stamping)).

Consumers: Xray's [Machine Inspector §Focused-transition lens](../tools/xray/spec/003-Machine-Inspector.md) renders guard / action fn-source inline under their declared id; re-frame-pair MCP surfaces source-jump for the same ids; future agents that inspect machine bodies read the form-strings from these surfaces.

## Action effect map — `{:data :fx}`

Actions return:

```clojure
{:data {<merged data updates>}        ;; the machine's own slice
 :fx   [[<fx-id> <args>] ...]}        ;; standard re-frame fx vector
```

Two keys. **Symmetric with `reg-event`'s `{:db :fx}`** — same shape, different scope. Both keys are optional. Returning `nil` means "no effects."

`:data` semantics: **merge with the existing data map** (last write wins on key collision). An explicit `nil` **sets the value to `nil` (the key remains present)**; the merge never dissocs — there is no key-removal idiom in a `:data` write, only key-set-to-`nil`. A consumer that must distinguish "absent" from "present `nil`" should not rely on a `:data` write to remove the key:

```clojure
{:data {:circle-id nil :initial-radius nil :preview-radius nil}}
;; → :circle-id, :initial-radius, :preview-radius are each PRESENT with value nil
```

When N action slots fire in one transition (`:exit` → `:action` → `:entry`), `:data` updates merge in slot order; `:fx` vectors concatenate left-to-right.

**XState-v5 parity — no `assign` to reorder.** XState v5 made a deliberate correctness fix over v4: `assign` actions are no longer hoisted ahead of other actions; they run **in declared order** interleaved with the transition's other actions, and each action observes the context produced by the assigns that ran before it. re-frame2 has **no separate `assign` primitive** — a `:data` write *is* the assign, and an action returns the atomic `{:data :fx}` map — so the v5 "no reorder" property is automatic rather than a rule the engine must enforce:

- **Across slots in one transition**, `:data` merges in the fixed slot order (`:exit` deepest-first → `:action` → `:entry` shallowest-first → `:initial` cascade — per [§Level 2](#level-2--across-the-action-slots-in-one-transition)), and the engine threads the in-flight `:data` forward, so a later slot's action **and guard** sees every earlier slot's write. That is exactly v5's guarantee, at slot granularity.
- **Within one action**, ordering is trivial: the fn is pure and returns `:data` and `:fx` together. There is no "assign then read it" *inside* a single action. The consequence an XState author should note: an `:fx` entry in an action **cannot read that same action's own `:data` write** — the merge has not happened yet at the point the `:fx` vector is built. To act on a freshly-computed value, either compute it in a `let` before building the effect map and use the local binding in both `:data` and `:fx`, or split the work into two slots (e.g. write in `:action`, read in the target's `:entry`).

### `:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`

Not separate top-level keys. The machine handler walks `:fx` left-to-right and routes by fx-id:

```clojure
{:fx [[:raise              [:event-1]]                                          ;; back into THIS machine, atomic, pre-commit
      [:raise              [:event-2]]
      [:rf.machine/spawn   {:machine-id :request/protocol
                            :system-id  :child   ;; address the child by name (its id is NOT recordable via :on-spawn — return dropped)
                            :start      [:begin]}]                              ;; child actor (see §Spawning)
      [:rf.machine/destroy actor-id]                                            ;; tear down a spawned actor
      [:dispatch           [:other-machine [:notify]]]                          ;; standard re-frame :dispatch
      [:http               {...}]]}                                             ;; any other registered fx
```

Routing rules (per [§Drain semantics](#drain-semantics)):

- `[:raise <event-vec>]` — appended to the machine's local pre-commit raise-queue.
- `[:rf.machine/spawn <spawn-spec>]` — a **pure runtime-db write**: installs the spawned actor's snapshot at `[:rf.runtime/machines :snapshots <spawned-id>]` (stamping its TYPE under `:rf/machine-type`) and tracks the id at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`. It registers NO per-instance handler — the actor's liveness IS its snapshot (per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)). The runtime invokes the spec's `:on-spawn` advisory callback (return is dropped); if `:start` is present, an event is queued to the new actor (which lazy-resolves its just-installed snapshot on first dispatch).
- `[:rf.machine/destroy <actor-id>]` — runs the actor's `:exit` action, then a **pure runtime-db write** dissociating its snapshot at `[:rf.runtime/machines :snapshots <actor-id>]` and its spawn-registry slot. It clears no registrar entry (a spawned actor has none). Symmetric counterpart to `:rf.machine/spawn`. Used directly by user actions and emitted by the desugaring of `:spawn` on state exit.
- Any other `[fx-id args]` — forwarded to the standard `do-fx` for runtime processing.

`:raise` is machine-internal and unqualified, matching re-frame's existing reserved unqualified fx names (`:dispatch`, `:dispatch-later`). `:rf.machine/spawn` and `:rf.machine/destroy` are namespaced under the framework's `:rf.<feature>/...` convention so user code can register them globally as canonical actor-lifecycle fxs (per [§Top-level boot-time spawn](#top-level-boot-time-spawn-rare)). They are listed in [Conventions.md §Reserved fx-ids](Conventions.md#reserved-fx-ids).

## Strict encapsulation — actions only see their own data

A machine *almost never* needs to write `app-db` directly; it acts on its own state, raises to itself, spawns/messages other actors, or emits fx. The locked rule is **strict encapsulation**: actions and guards cannot see `app-db` at all — only `{:state :data}` plus the event vector.

> **Why this is locked.** Strict encapsulation is one of the named consequences of [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility). If actions could read or write `app-db` outside `[:rf.runtime/machines :snapshots <id>]`, machine logic would create state changes that don't show up in any machine snapshot and don't roll back when the surrounding machine snapshot does. The whole machine's state has to live inside the frame's persistent value to revert with it; encapsulation is what stops machines from leaking state into parts of the value that aren't theirs.

- **Action signature:** `(fn [{:keys [data event state meta] cofx :rf.cofx}] effects)` — single context-map argument; user destructures the keys it needs.
- **Guard signature:** `(fn [{:keys [data event state meta] cofx :rf.cofx}] boolean)` — single context-map argument, same shape as `:action`.
- **What the fn sees:** the keys it destructures from the context map — `:data` (the snapshot's `:data` slot), `:event` (the inbound event vector), `:state` (the discrete FSM-keyword), `:meta` (any user `:meta` on the snapshot), and `:rf.cofx` (the dispatch's recordable-coeffect record; see [§Causal host facts](#causal-host-facts--rfcofx-ep-0017)). Never `app-db`. Facts a callback consumes are declared with `:rf.cofx/requires` on the named entry; `:rf.cofx` is the host-fact channel, threaded causally as recorded data.

The impure plumbing (reading the snapshot from runtime-db at `[:rf.runtime/machines :snapshots <id>]`, writing `:data` back as a `:rf.db/runtime` write, lowering `:fx` / `:raise` / `:rf.machine/spawn` into standard re-frame effects) lives in the *handler boundary* — the fn returned by `make-machine-handler`. **Inside the boundary: pure. Outside: standard re-frame.**

**Cross-cutting reads via the event payload.** A view that needs to pass the circle's current radius into the editor includes it in the dispatch:

```clojure
(rf/dispatch [:drawer/editor [:right-click-circle id radius]])
```

Whoever fires the event has the data; they pass it. The machine never reaches outside its own `:data`.

**Cross-cutting writes via `:fx [[:dispatch ...]]`.** To touch a sibling slice in `app-db`, an action dispatches a named event:

```clojure
:close-dialog
{:target :idle
 :action (fn [{:keys [data]}]
           {:fx   [[:dispatch [:drawer/apply-radius
                               (:circle-id data)
                               (:preview-radius data)]]]
            :data {:circle-id nil :initial-radius nil :preview-radius nil}})}
```

This forces every cross-encapsulation write to be a *named, traced, reusable event* rather than a quiet reach into someone else's data. Tracing shows the apply-radius event by name.

**Hard-disallow `:db`.** An action's effect map cannot contain `:db`. If one is present, the runtime emits the structured error `:rf.error/machine-action-wrote-db` and drops the `:db` key (the rest of the action's effects flow through). The error is registered as a category in [009 §Error contract](009-Instrumentation.md#error-contract).

**State-keyword is not in the action's return shape either** — only the transition's `:target` decides the next state. Actions cannot bypass the FSM.

## Path conventions in machine bodies

every machine callback receives a SINGLE context-map argument; the keys present per slot are:

| Slot | Signature | Ctx keys | What it returns |
|---|---|---|---|
| `:guard` | `(fn [{:keys [data event state meta] cofx :rf.cofx}] boolean)` | `:data :event :state :meta :rf.cofx` | a boolean |
| `:action` | `(fn [{:keys [data event state meta] cofx :rf.cofx}] effects)` | `:data :event :state :meta :rf.cofx` | `{:data ... :fx ...}` (or `nil`) |
| `:entry` / `:exit` | same as `:action` | `:data :event :state :meta :rf.cofx` | `{:data ... :fx ...}` (or `nil`) |
| `:on-spawn` | `(fn [{:keys [data id]}] _)` — **advisory** | `:data :id` | ignored (return is dropped; runtime tracks the spawn-id at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`) |
| `:on-done` | `(fn [{:keys [data result]}] new-data)` | `:data :result` | the parent's new `:data` map |
| `:after` delay-fn | `(fn [{:keys [snapshot]}] ms)` | `:snapshot` | a positive-int millisecond delay |
| `:spawn :data` fn | `(fn [{:keys [snapshot event]}] data)` | `:snapshot :event` | the child's initial data map |

**Why uniform context-map.** Slot-specific positional signatures (`(fn [data event])` for guards/actions, `(fn [data id])` for `:on-spawn`, `(fn [data result])` for `:on-done`, `(fn [snapshot])` for `:after`, `(fn [snapshot event])` for `:spawn :data`) would create a paste-from-`:guard`-into-`:on-spawn` trap: `id` silently bound to the event vector (or vice-versa) with no runtime signal — a class of bug that survives review because both args destructure the same way. The unified shape eliminates the trap structurally: every callback receives ONE map, the keys say what they carry, and future slot additions extend the key set without expanding the arity-permutation matrix.

**Return shape — narrow by purpose.** Three buckets:

- `:guard` → boolean (the transition's gate).
- `:action` / `:entry` / `:exit` / `:on-done` / `:spawn :data` → a fresh `:data` map (or, for actions, a `{:data :fx}` effects map). The runtime patches `:data` back into the snapshot. Logical state (`:loading` / `:loaded` / `:error`) is reserved for declarative transition apparatus (`:on` / `:always` / `:after`); callbacks can ONLY update working memory (the `:data` bag) — they cannot nudge the machine into a state the spec didn't declare. Matches xstate's `assign` invariant.
- `:on-spawn` / `:after` advisory cases — `:on-spawn` returns are dropped (the runtime tracks the spawn-id at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`); `:after` delay-fn returns the ms value the timer scheduler consumes.

<a id="snapshot-level-escape-hatch"></a>

**Snapshot-level escape hatch.** If a callback NEEDS to touch `:state` / `:meta` / `:data` plus something else in one atomic write, emit `[:rf.machine/update-snapshot {:rf/machine-id <id> :rf/patch {:data {...}}}]` from inside the callback's `:fx` vector — NOT a return-shape hidden contract. `:rf/machine-id` names the actor whose snapshot at `[:rf.runtime/machines :snapshots <id>]` is patched; `:rf/patch` is merged onto that snapshot, restricted to the permitted top-level keys above (`:state` / `:meta` / `:data`). User error/status state is *user-domain working memory* and lives under `:data` (where `[:schemas :data]` validation covers it) — not as bare snapshot-root keys. The `:data` patch is **not** exempt from that validation: the fx validates the would-be-merged snapshot's `:data` against the actor's `[:schemas :data]` schema **before** writing, and a violating patch is rejected — the invalid `:data` never installs (`:phase :update-snapshot`, `:rollback? false`; see [§Schema validation](#schema-validation)). A `:db` key in the patch is the same hard-disallow as in an action's effect map (it surfaces `:rf.error/machine-action-wrote-db` and is dropped); merging into a destroyed / unknown actor is a no-op.

The runtime is responsible for unwrapping the snapshot before calling these fns and for patching the result back into the snapshot. **User code never names `[:data ...]` paths inside the body**; if a callback needs to read or write a field, it does so on the destructured `data` directly (e.g. `(:pending data)`, `(assoc data :pending id)`).

The same principle holds for any data DSL the conformance corpus or a tooling layer interprets on top of the surface: a `:set` step inside a body operates on `:data`, so its path is data-relative. `[:set [:pending] x]` writes `data.:pending = x`. `[:set [:data :pending] x]` would write `data.:data.:pending = x`, which is virtually never what's wanted.

## Registration — the machine IS the event handler

A machine is registered as **one event handler** via `reg-event` whose body comes from `make-machine-handler`.

```clojure
(rf/reg-event :drawer/editor
  {:doc          "Modal-edit flow."
   :interceptors [:drawer/undoable]}                       ;; interceptor chains carry refs by id, in the metadata map
  (rf/make-machine-handler
    {:initial :idle                                       ;; initial FSM-keyword
     :data    {:circle-id nil :initial-radius nil :preview-radius nil}
     :guards  {:circle-exists? (fn [{data :data}] (some? (:circle-id data)))}
     :actions {:clear-error    (fn [_] {:data {:error nil}})}
     :states  { ... }}))
```

The `:guards` and `:actions` maps declare the machine's named guard / action implementations. Inside `:states`, a transition's `:guard :circle-exists?` resolves against this machine's `:guards` map; `:action :clear-error` resolves against `:actions`. **Each machine has its own guards/actions namespace** — there is no global `:machine-guard` / `:machine-action` registry. Inline fns remain first-class (`:guard (fn [...] ...)` skips the lookup).

Reference resolution:

- `:guard :form-valid?` → `(get-in spec [:guards :form-valid?])`.
- `:guard (fn [...] ...)` → inline fn, called directly.
- `:action :clear-error` → `(get-in spec [:actions :clear-error])`.
- `:action (fn [...] ...)` → inline fn, called directly.
- `:on-spawn :observe-spawn` (when `:on-spawn` appears as a keyword reference, e.g. inside a `:spawn` slot) → resolved against an optional `:on-spawn-actions` map at the spec root if present, then falling back to `:actions`. Inline fns work as for `:action`. The `:on-spawn-actions` map is intended for advisory spawn-observation callbacks (logging / instrumentation), distinct from transition-time `:actions` — their return is dropped, so they are NOT the parent's id-recording mechanism (use `:system-id` / the registry slot / `:rf.machine/update-snapshot` per [§Recording the spawned id user-side](#recording-the-spawned-id-user-side)); declaring the map is optional and the fallback to `:actions` keeps single-map machines simple.

`make-machine-handler` walks the transition table at construction time and verifies every keyword reference under a `:guard` or `:action` slot (in `:on`, `:always`, `:entry`, `:exit`) resolves to a key in the spec's `:guards` / `:actions` map. A miss fails registration with `:rf.error/machine-unresolved-guard` or `:rf.error/machine-unresolved-action` carrying `:tags {:guard-id <id> :machine-id <id>}` (or `:action-id`). This catches typos and undeclared references at registration time, not at runtime.

**Cross-machine reuse via Clojure vars.** When a guard or action is shared across machines, define it as a Clojure var and reference the var from each machine's `:guards` / `:actions` map:

```clojure
(defn user-authenticated? [data _]
  (some? (:user-id data)))

(rf/reg-event :auth/login {}
  (rf/make-machine-handler
    {:guards {:authenticated? user-authenticated?}
     ...}))

(rf/reg-event :settings/page {}
  (rf/make-machine-handler
    {:guards {:authenticated? user-authenticated?}
     ...}))
```

No framework support beyond ordinary Clojure var resolution. Each machine names the shared fn locally; the id is the meaning at the call site.

**Hot-reload.** Re-evaluating `(reg-event :machine-id (make-machine-handler {:guards {...} :actions {...} ...}))` re-registers the handler with new `:guards` / `:actions` impls. Mounted snapshots survive (only the handler changes); the next dispatch uses the new bodies. Standard hot-reload story.

What this gives:

- **One id, one registration.** Reuses the `:event` registry kind. `(registrations :event)` enumerates every machine alongside every other event handler; `(handler-meta :event :drawer/editor)` carries `:rf/machine? true` so tooling can identify it. The snapshot's location in `runtime-db` is fixed and runtime-managed (see [§Where snapshots live](#where-snapshots-live)).
- **Standard dispatch.** `dispatch` and `dispatch-sync` route to a machine the same way they route to any handler.
- **Hot-reload.** Re-eval of the registration replaces the table; live snapshots pick up the new interpretation on their next event.
- **Reading the snapshot.** Views read the snapshot via the framework-shipped `:rf/machine` sub — `@(rf/subscribe [:rf/machine :drawer/editor])` yields `{:state ... :data ...}` (or `nil` if not yet initialised). See [§Subscribing to machines via the `:rf/machine` sub](#subscribing-to-machines-via-the-rfmachine-sub).

Sub-events are how the machine receives its inputs:

```clojure
(rf/dispatch [:drawer/editor [:right-click-circle id radius]])
(rf/dispatch [:drawer/editor [:close-dialog]])
```

The handler dispatches on the second-position keyword (`:right-click-circle`, `:close-dialog`); the rest of the inner vector is the event payload visible to actions and guards.

#### Extra-args fold

The dispatched outer vector MAY carry **additional elements after the inner event**:

```clojure
[:machine-id [:inner-event-id & inner-args] & extra-args]
```

When the runtime drains an event with this shape, **the extras are appended (folded) onto the inner event** before it's interpreted, producing:

```clojure
inner-event = [:inner-event-id <inner-args>... <extra-args>...]
```

This makes the standard fx-callback convention work without ceremony. Idiomatic uses:

- **HTTP callbacks.** A handler emits `:on-success [:machine-id [:inner-id]]` — a 2-element template. The fx implementation does `(rf/dispatch (conj on-success response))`, which conj-onto-the-outer produces `[:machine-id [:inner-id] response]`. The runtime folds the trailing `response` into the inner event, so the machine handler sees `[:inner-id response]` and the action's `[_ result]` destructure receives `result`.

- **Promise / future resolution patterns.** Same shape: the surrounding async layer captures a 2-element template, conj's the resolved value, dispatches.

- **Chained dispatches that carry payload.** Any callsite that wants to "ship a value into the machine" can use the `[:machine-id [:event-id] payload]` form rather than constructing the inner vector manually.

The fold only applies when the outer event has length ≥ 3 AND the second element is itself a vector. Length-2 dispatches (`[:machine-id [:inner-id]]`) and the legacy single-arg form (`[:machine-id]`) are unaffected. The runtime resolves the outer-shape ambiguity by inspecting the second element's type — a vector second element means "sub-event, fold extras"; anything else means "use the whole vector as the inner event" (compatibility fallback).

### `make-machine-handler` is a pure factory

The fn `make-machine-handler` returns is the event handler. Crucially, the factory itself:

- **Registers nothing.** No `reg-*` side effects at construction time.
- **Closes over no global state.** No `(get-machine-by-id ...)` lookups bound at construction.
- **Does not know its own event id.** The handler's id is bound by the surrounding `reg-event` (or by the `[:rf.machine/spawn ...]` fx for dynamic instances).

This is a real constraint on the implementation, not just a testing affordance — it's what makes the singleton vs spawned symmetry clean (the registration happens *outside* the factory in both cases) and what makes Level-2 testing (per [§Testing](#testing)) possible without a test frame.

### `reg-machine` — public registration surface

Alongside the underlying `reg-event + make-machine-handler` form (per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler)), the framework ships **`reg-machine`** as the standard public registration entry point for machines. Both forms register the same thing — an event handler whose body interprets the transition table — and they reach the same registry slot. `reg-machine` is the surface that tools, examples, and CP-5-generated scaffolds default to.

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :data    {:attempts 0 :error nil}
   :guards  {:under-retry-limit (fn [{data :data}] (< (:attempts data) 3))}
   :actions {:begin-submit       (fn [{[_ creds] :event}] {:fx [[:http {...}]]})
             :record-failure     (fn [{data :data}]      {:data {:attempts (inc (:attempts data))}})}
   :states  { ... }})
```

**Surface signature.** Two forms, each with a bare arity and an **event-`:schema` arity**:

- `(rf/reg-machine machine-id machine)` / `(rf/reg-machine machine-id opts machine)` — **macro**. Walks the literal spec form at expansion time and CO-LOCATES per-element source onto each guard / action / on-spawn-action entry, plus a reference-site `:source-coords` co-located onto each map node (state-node / transition map) inside the `:states` tree (per [§Source-coord stamping](#source-coord-stamping)). The macro emits `(reg-machine* …)` after stamping; the runtime call site is the plain-fn surface. The optional `opts` precedes the spec.
- `(rf/reg-machine* machine-id machine)` / `(rf/reg-machine* machine-id opts machine)` — **plain fn**. Routes through the single registration home (below) — the `:rf/machine?` / `:rf/machine` registration-metadata stamp that makes the `[:schemas :data]` validation live. No source-coord walking — the spec is opaque data at the call site. The optional `opts` is the canonical Spec 001 MIDDLE slot — it precedes the spec, exactly as the `reg-machine` macro's `(reg-machine machine-id opts machine)` surface does.
- `(rf/defmachine name [doc] spec)` — **macro** (`def`-shape). Defines a Var holding the spec value with per-element source stamped at the definition site, for the `def`-then-register shape `(defmachine m {…})` / `(reg-machine :id m)`. Does not register. See [§Value-registered machines](#value-registered-machines--defmachine).

**The event-`:schema` arity — machine + event-vector schema.** `opts` is an optional registration-metadata map. Its `:schema` key is the validator for the dispatched **outer event vector** — the `:where :event` boundary (per [010 §Validation order](010-Schemas.md)) that runs *before* the machine handler. This is the **machine + event-vector-schema shape** (login / realworld auth): a machine that validates BOTH its `:data` (via the spec's `[:schemas :data]` schema, the `:where :machine-data` boundary) AND its inbound event vector (via the opts `:schema`, the `:where :event` boundary). Any other `opts` keys (`:doc`, `:rf.http/decode-schemas`, …) ride onto the registration metadata verbatim. The framework-owned `:rf/machine?` / `:rf/machine` keys are stamped by the home and MUST NOT appear in `opts` (supplying them raises `:rf.error/machine-reserved-meta-in-opts`).

```clojure
(rf/reg-machine :auth.login/flow
  {:schema AuthLoginEvent}            ;; validates the OUTER event vector (:where :event)
  {:initial :idle
   :schemas {:data AuthLoginData}    ;; [:schemas :data] validates the machine's :data (:where :machine-data)
   :data    {:attempts 0 :error nil}
   :states  { ... }})
```

The event-`:schema` arity is the one blessed spelling for a machine that needs BOTH `[:schemas :data]` validation and an event-vector schema; a hand-composed `(reg-event id {:schema … :rf/machine? true :rf/machine spec} (make-machine-handler spec))` does not wire `[:schemas :data]` validation. The event-`:schema` arity replaces that composition.

**The single registration home + auto-stamp + fail-loud guard.** Both `reg-machine` / `reg-machine*` (every arity) route through ONE registration home that stamps the `:rf/machine?` / `:rf/machine` registration metadata — the `:where :machine-data` post-commit walker resolves a machine's `[:schemas :data]` schema THROUGH `(machine-meta id)`, so without the stamp the schema validates nothing.

> **Durable `:data` classification is machine-owned.** There is no schema→marks redaction bridge from a machine's `:sensitive?` / `:large?` `:data` slots into snapshot egress. Durable machine `:data` egress classification is declared projection-relative on the `reg-machine` spec (`:sensitive` / `:large`), lowered per actor instance at spawn / first-boot (per [§Privacy](#privacy--redacting-machine-data-at-trace-egress)). The home runs the validation-stamp plus the projection-relative-classification shape check (`:rf.error/invalid-machine-classification`).

The bare `(reg-event id meta (make-machine-handler spec))` composition does not stamp the meta — so a `[:schemas :data]` schema declared on a hand-stamped machine is **inert** (validates nothing). `make-machine-handler` therefore **fails loud** when handed a `[:schemas :data]`-bearing spec outside the home: it raises `:rf.error/machine-schema-requires-reg-machine`, directing the author to `reg-machine` / `reg-machine*` (and, when the machine also validates its event vector, the event-`:schema` arity). A schema-LESS spec is unaffected — it has nothing inert, so the bare `reg-event` + `make-machine-handler` composition stays legal for it (the lazy spawned-actor materialisation seam relies on it).

Both forms live in `re-frame.machines` (the `day8/re-frame2-machines` artefact, per [Conventions.md](Conventions.md)). The `reg-machine` / `defmachine` **macros** are re-exported on the `re-frame.core` façade (they capture call-site source-coords); the plain-fn `reg-machine*` is **not** re-exported — reach it through `re-frame.machines/reg-machine*` (per [API.md §Front-porch boundary](API.md), the non-registration / plain-fn machine surface stays in its owning namespace). See [API.md §Machines](API.md#machines) for the canonical API table.

Both forms return `machine-id` per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

**Registration-metadata stamp.** Both forms record two keys on the registry slot's metadata map (per [001 §Metadata-map shape](001-Registration.md)):

- `:rf/machine? true` — the discriminator. `(rf/machines)` filters `(registrations :event)` by this flag (per [§Querying machines](#querying-machines)). User-written event handlers do not set this key.
- `:rf/machine <spec>` — the spec map passed to `reg-machine`. `(rf/machine-meta id)` reads this back; tools that walk the transition table (visualisers, conformance harnesses, CP-5-time scaffolders) consume the spec via this key. When the macro path stamps source, each `:guards` / `:actions` / `:on-spawn-actions` entry carries its co-located `:source-coords` / `:source-code`, and each `:states`-tree map node (state-node / transition map) carries its own reference-site `:source-coords` directly inside this spec map.

Source-coord stamping on the call site (`:ns` / `:line` / `:column` / `:file`) follows the standard rules from [001 §Source-coord stamping](001-Registration.md): the macro stamps; programmatic registration via `reg-machine*` does not. See [§Source-coord stamping](#source-coord-stamping) for the per-element index.

`reg-machine` is itself **late-bound** — `re-frame.core` carries the macro and a stub fn that resolves the producer through the late-bind hook table at registration time. Apps that use `reg-machine` MUST add `day8/re-frame2-machines` to their deps and require `re-frame.machines` at app boot; without it, the lookup throws `:rf.error/machines-artefact-missing`.

### `reg-machine` vs `reg-machine*`

The `reg-machine` convenience surface splits along Clojure's `let` / `let*`, `fn` / `fn*` idiom:

| Form | Shape | Source-coord stamping | Use case |
|---|---|---|---|
| `(rf/reg-machine machine-id machine-spec)` | **macro** | Yes when `machine-spec` is an inline literal — call-site coords on the registry slot, AND co-located per-element source on each guard / action / on-spawn-action entry + reference-site `:source-coords` co-located onto each `:states`-tree map node, walked from the literal spec form (per [§Source-coord stamping](#source-coord-stamping)). When `machine-spec` is a symbol / non-literal, only the call-site coords are stamped (per-element source comes from `defmachine` instead). | Standard form. Inline literal → full capture; value-registered (symbol) → pair with `defmachine` ([§Value-registered machines](#value-registered-machines--defmachine)). |
| `(rf/reg-machine machine-id opts machine-spec)` | **macro** | Same as the bare macro arity — `opts` is a runtime metadata expression (not walked) carrying the event-vector `:schema`. | The **machine + event-vector-schema shape** (login / realworld auth): a machine that ALSO validates its inbound event vector. |
| `(rf/reg-machine* machine-id machine-spec)` | plain fn | None — the call-site predates the registration; the spec is opaque data | Code-gen pipelines that produce specs at runtime, REPL exploration, conformance harnesses that synthesise machines from EDN fixtures. |
| `(rf/reg-machine* machine-id opts machine-spec)` | plain fn | None — `opts` is the canonical MIDDLE slot, mirroring the opts macro arity | Programmatic registration of the machine + event-vector-schema shape (the plain-fn counterpart of the opts macro arity). |
| `(rf/defmachine name [doc] spec)` | **macro** (`def`-shape) | Yes — walks the inline literal `spec` at the **definition site** and co-locates per-element source + the reference-site `:source-coords` on each `:states`-tree map node onto the def'd value (per [§Value-registered machines](#value-registered-machines--defmachine)). Does not register — pair with `(reg-machine id name)`. | The `def`-then-register shape: `(defmachine m {…})` then `(reg-machine :id m)` so a value-registered machine carries per-element source. |

The `reg-machine` / `defmachine` macros are re-exported on the `re-frame.core` façade; the plain-fn surface lives in `re-frame.machines/reg-machine*` and is reached through that namespace directly (it is **not** a `re-frame.core` façade export) for both JVM and CLJS programmatic callers. The inline `reg-machine` macro emits `(reg-machine* …)` after stamping; the runtime never reaches both surfaces independently.

### Source-coord stamping

When the `reg-machine` macro receives a literal-map spec form, it walks the form at expansion time and CO-LOCATES per-element source onto each guard / action / on-spawn-action entry, plus a reference-site `:source-coords` onto each map node (state-node / transition map) inside the `:states` tree. Tools (re-frame-pair, re-frame-10x, IDE jump-to-source) read one place per element — `(get-in (rf/machine-meta machine-id) [:guards <id> :source-coords])` for a named guard's coord, `(get-in (rf/machine-meta machine-id) [:states <id> :source-coords])` for a state-node's coord, `(get-in (rf/machine-meta machine-id) [:states <id> :on <event> :source-coords])` for a transition's coord.

> **Co-located source payloads.** Each guard / action carries its per-element payloads — the `:fn`, the coord, and the `pr-str` source — co-located on each `:guards` / `:actions` / `:on-spawn-actions` entry, so a consumer assembles one element from one place. There are no `:rf.machine/source-coords` / `:rf.machine/handler-source` side-indexes. For STATES: each `:states`-tree map node carries its own `:source-coords` directly (there is no flat `:rf.machine/state-coords` side-index paralleling the `:states` tree), so a tool that has navigated to a state-node already has its coord in hand.

#### What gets stamped — co-located element entries

Each `{<id> <fn>}` entry under `:guards` / `:actions` / `:on-spawn-actions` becomes ONE cohesive map:

```clojure
:guards  {:form-valid? {:fn            <compiled-fn>
                        :source-coords {:ns sym :file "path/login.cljs" :line 47 :column 13}
                        :source-code   "(fn [{data :data}] ...)"}}
:actions {:commit      {:fn            <compiled-fn>
                        :source-coords {:ns sym :file "path/login.cljs" :line 52 :column 13}
                        :source-code   "(fn [{data :data}] {:fx ...})"}}
```

- `:fn` is ALWAYS present — it's the function the transition engine invokes (the engine reads `(:fn entry)`; bare-fn and keyword-reference entries are also accepted for programmatic / indirection cases).
- `:source-coords` / `:source-code` are **DEBUG-only** — present in dev, ABSENT in production (see [§Production elision](#production-elision)). In production each entry collapses to `{:fn <compiled-fn>}`.

#### What gets stamped — reference-site `:states`-tree coords

Each MAP node inside `:states` — a state-node, a transition map under `:on` / `:always` / `:after`, a `:spawn` map — carries its OWN `:source-coords` directly on the node:

```clojure
:states {:idle {:on {:submit {:target :done
                              :guard  :form-valid?
                              :action (fn [_] {})
                              :source-coords {:ns sym :file "path/login.cljs" :line 80 :column 23}
                              ;; inline-fn source — keyed by slot:
                              :source-code   {:action "(fn [_] {})"}}}
                :source-coords {:ns sym :file "path/login.cljs" :line 78 :column 11}}
         :done {:source-coords {:ns sym :file "path/login.cljs" :line 84 :column 11}}}
```

This is the coord tools navigate to for "jump to call site" — where a transition / state-node lives in the spec tree, distinct from the per-element definition coord co-located on `:guards` / `:actions`. Because the coord lives ON the node, a tool that has already navigated from a snapshot to a state-node / transition map (the common gesture: clicking a state in the machine inspector, an edge in the diagram, a cascade row in the Epoch panel) reads `(:source-coords node)` with no separate index lookup.

#### Inline-fn / keyword slots (the exemption case)

Inline-fn and keyword slots inside `:states` — `:entry` / `:exit` / `:guard` / `:action` / `:on-spawn` — hold a fn or keyword VALUE, not a map, so there is no node to hang a `:source-coords` key on. They are **not** stamped with their OWN `:source-coords`; a tool resolving such a slot reads the `:source-coords` off its **nearest enclosing map node** (the transition map for an inline `:guard` / `:action`; the state-node for an inline `:entry` / `:exit`). This is the same source line in practice, and it mirrors the keyword-reference rule: a keyword (`:form-valid?`) is a name, not a source form, so it too falls back to the enclosing map's coord.

**Inline-fn `:source-code`.** The enclosing-map fallback works for `:source-coords` (the position is the same source line), but it does NOT supply an inline fn's CODE TEXT — `pr-str` of the enclosing transition map is `{:target :done :action (fn …)}`, not the action fn body. So an inline fn's `:source-code` (the `pr-str` of the fn literal) is co-located on the **enclosing map node** under a `:source-code` MAP keyed by the inline slot — `{:entry "…" :exit "…"}` on a state-node, `{:guard "…" :action "…"}` on a transition map — alongside the node's own `:source-coords`. A tool resolving an inline-fn slot key (`[… :action]`) reads `(get-in spec [… :source-code :action])` off the enclosing node. This is distinct from the named-element `:source-code` (a STRING on `:guards` / `:actions` entries): named entries live under the registry slots, never on a `:states`-tree map node, so the two `:source-code` shapes never share a map. **The inline-fn slot VALUE itself stays a bare fn** — the runtime engine resolves `:entry` / `:exit` / `:guard` / `:action` slots via `fn?` / `keyword?` and stamps the slot value as the trace `:action-id` / `:guard-id`, so it is never wrapped into a map. Keyword-reference slots (`:action :clear-hold`) carry NO inline `:source-code` — their body lives on the named `:actions` entry's own `:source-code`.

Concretely for `{:guards {:form-valid? (fn …)} :states {:idle {:on {:submit {:target :done :guard :form-valid? :action (fn [_] {})}}}}}`:

| Where | Co-located coord? | Inline `:source-code`? | Why |
|---|---|---|---|
| `:guards :form-valid?` entry's `:source-coords` / `:source-code` | ✓ (when defined) | ✓ (on the `:guards` entry) | fn literal carries reader meta + `pr-str` source |
| `:states :idle` state-node's `:source-coords` | ✓ | — (no inline `:entry`/`:exit` here) | state-node map carries reader meta (CLJS) |
| `:states :idle :on :submit` transition map's `:source-coords` | ✓ | — | transition map carries reader meta (CLJS) |
| `:states :idle :on :submit :guard` | — (resolves to the transition map) | — (`:form-valid?` is a keyword) | a keyword's body lives on `:guards :form-valid?` |
| `:states :idle :on :submit :action` | — (resolves to the transition map) | ✓ via `[:states :idle :on :submit :source-code :action]` | the inline fn is a value, not a map — its source rides the enclosing transition map's `:source-code` |

Tools resolving a slot walk UP from its spec-path to the nearest enclosing map carrying `:source-coords`; for a "jump to call site" click on a state's `:guard`, they land on the enclosing transition's coord (the same source line). For the inline fn's CODE, they read `(get-in spec [<enclosing-map-path> :source-code <slot>])` — the inline `:source-code` map on that same enclosing node.

#### Reading it back

```clojure
;; Per-element definition coord + source — ONE lookup per element:
(get-in (rf/machine-meta :auth/login) [:guards :form-valid? :source-coords])
;; => {:ns ... :line ... :column ... :file ...}
(get-in (rf/machine-meta :auth/login) [:actions :commit :source-code])
;; => "(fn [{data :data}] {:fx ...})"

;; Reference-site coords — read directly off the map node:
(get-in (rf/machine-meta :auth/login) [:states :form :source-coords])
;; => {:ns ... :line ... :column ... :file ...}
(get-in (rf/machine-meta :auth/login) [:states :form :on :submit :source-coords])
;; => {:ns ... :line ... :column ... :file ...}

;; Inline-fn source — read off the enclosing node's :source-code map by slot:
(get-in (rf/machine-meta :auth/login) [:states :form :on :submit :source-code :action])
;; => "(fn [{data :data}] {:fx ...})"   (nil for a keyword-reference :action)
```

The top-level call-site coords (the position of the `(rf/reg-machine ...)` form itself) live on the registry slot as `:ns` / `:line` / `:column` / `:file`, queryable via `(rf/handler-meta :event machine-id)`. These surfaces are independent: a tool highlighting the `reg-machine` declaration uses `handler-meta`; a tool highlighting a guard's definition reads its co-located `:source-coords` on the `:guards` entry; a tool highlighting a transition's source line reads the `:source-coords` on the transition map node.

#### Production elision

The macro emits an `(if interop/debug-enabled? <dev> <prod>)` branch. The DEV arm co-locates `{:fn .. :source-coords .. :source-code ..}` onto each element entry, co-locates `:source-coords` onto each `:states`-tree map node, AND co-locates the inline-fn `:source-code` map onto each enclosing node; the PROD arm collapses each element entry to `{:fn <fn>}` and runs NO state-source / inline-source splice, so prod state-nodes ship clean (just the user's authored `{:on … :tags …}`) and inline-fn slots ship as bare fns. Under `:advanced` + `goog.DEBUG=false` the closure compiler constant-folds the gate to `false` and DCEs the entire dev arm — every co-located `:source-code` string (named-element AND inline-fn) and every coord literal (per-element AND state-node / transition-map) are absent from the production bundle. The separable `:fn` is what lets the named-element source bytes DCE while the live function ships; inline-fn slots keep their bare fn while the inline `:source-code` map DCEs; state-nodes have no extra runtime cost in prod because the splice never runs. Verified by the `npm run test:elision` sentinel grep (the co-located `:source-code` fn-body fragments, which ride the same dev arm as the state-source / inline-source splices — there is no `:rf.machine/state-coords` keyword to grep, and `:source-coords` is shared with the guards/actions arm).

#### JVM caveat

Clojure's `LispReader` only attaches `:line` / `:column` metadata to *list* forms (function calls, `(fn …)` bodies). Map and vector literals do NOT carry reader meta on JVM. So on JVM the walker captures co-located definition-site fn coords reliably (under `:guards` / `:actions` / `:on-spawn-actions`) but state-node and transition-map `:source-coords` are unavailable — those need the CLJS reader (cljs.tools.reader, which DOES decorate maps/vectors). Per Goal 1 (CLJS reference) the tooling-facing path is CLJS-side; the JVM caveat affects only JVM-side tooling that reads the node coords directly.

#### Programmatic registration

`reg-machine*` (the plain-fn surface) and any `reg-machine` macro call where the spec arg is a non-literal (a symbol, a let-bound expression) skip the per-element walk: there's no literal tree to walk at expansion time. The registered spec's `:guards` / `:actions` entries are bare fns (no co-located source) and its `:states`-tree map nodes carry no `:source-coords` in those cases — tools fall back to the call-site coords on `handler-meta`.

#### Value-registered machines — `defmachine`

The common app shape defines the spec as a top-level value and registers it by symbol:

```clojure
(def door-machine {:initial :locked :guards {…} :actions {…} :states {…}})
(rf/reg-machine :door/main door-machine)
```

Here the `reg-machine` macro sees only the symbol `door-machine` at its call site — **not** the inline literal — so the per-element walk above captures nothing and the registered spec's `:guards` / `:actions` entries are bare fns with no co-located source. The fix is `defmachine`, a `def`-replacement that walks the literal **at the definition site** and co-locates the source onto the def'd value, so it travels into `reg-machine` with the value:

```clojure
(rf/defmachine door-machine
  "Optional docstring."
  {:initial :locked
   :guards  {:may-close? (fn [_] …)}
   :actions {:count-open (fn [_] …)
             :clear-hold (fn [_] …)}
   :states  {…}})

(rf/reg-machine :door/main door-machine)
;; (get-in (rf/machine-meta :door/main) [:actions :clear-hold :source-coords]) is now populated,
;; and (rf/handler-meta :machine-action [:door/main :clear-hold]) carries the fn source.
```

Normative rules:

- **`defmachine` performs exactly the same literal-spec walk as the inline `reg-machine` macro** — the same co-located per-element `:source-coords` / `:source-code` on each guard / action / on-spawn-action entry, and the same reference-site `:source-coords` co-located onto each `:states`-tree map node — but applies them to the def'd **value** rather than at a registration call site. The co-location rides the same `interop/debug-enabled?` gate and DCEs identically under `:advanced` + `goog.DEBUG=false`.
- **`defmachine` is a drop-in for `def`**: `(defmachine name spec)` or `(defmachine name "doc" spec)`. It introduces a Var holding the (source-stamped) spec map. The optional docstring rides onto the Var's metadata.
- **`defmachine` does NOT register the machine** — it only defines the stamped value. A subsequent `(reg-machine id name)` performs the registration; because the value already carries source, `reg-machine` (even seeing only the symbol) registers a spec (stored under `:rf/machine` on the `:event` registration) whose co-located source `(rf/handler-meta :machine-guard [machine-id guard-id])` **derives** on demand exactly as for an inline-registered machine (no registrar side-table written).
- **Use `defmachine` for the `def`-then-register shape; use the `reg-machine` macro directly for inline-literal registration.** Both yield identical per-element source; the choice is purely whether the spec value is named.

Without `defmachine`, the Epoch machine-cascade (and any tool reading `cascade-row-coord` / `cascade-row-source-form`) has no per-element source to render for value-registered machines, even though the inline-registered case works — this is the gap `defmachine` closes.

## Design rule — data DSLs vs functions

> **Use data DSLs** for *deferred function calls* (`:fx [[fx-id args]]`), *named effects* (`:on-match [event]`), *declarative shape descriptions* (schemas, hiccup), and *static dependency declarations* (`:<-`, `:platforms`).
>
> **Use functions** for *imperative composition* and *predicate composition*.

Applied to machines: the transition table is a data DSL because it describes named transitions, target states, and references to registered fns — those are deferred function calls and static dependency declarations. Composing two actions ("clear the error *and* record the attempt") is imperative composition, so the action slot holds **one fn** (which can compose freely in code). Composing two predicates ("email valid *and* under retry limit") is predicate composition, so the guard slot holds **one fn or one registered id** — and a registered compound guard with a meaningful name (`:active-and-under-quota?`) carries more semantic information at the call site.

## Inspectability bias

> **Inspectability bias.** Machine tables should prefer **named guards and actions declared in the machine's `:guards` / `:actions` maps** over inline fns. The id (`:under-quota?`) carries semantic meaning that visualisers, AIs, and humans all read; an inline `(fn [{:keys [data event]}] ...)` is opaque to inspection. Inline fns are escape hatches for trivial logic (one-liners with no branching), not the default form.

The id is the meaning at the call site; the inline fn is opaque to readers. The machine-scoped resolution mechanics — how keyword references in transition slots resolve against the machine's `:guards` / `:actions` maps, and how cross-machine reuse via Clojure vars works — are specified once in [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler).

This is a normative rule on top of the [data-DSL-vs-fn](#design-rule--data-dsls-vs-functions) rule: *both* forms are first-class at the grammar level (`:guard` and `:action` accept a fn or a keyword reference), but the **default form is the named keyword reference**. When a transition's logic is more than a single non-branching expression, name it in the machine's `:guards` / `:actions` map.

Why the bias (note — *not* "you can't see an inline fn's code": since [§Inline-fn / keyword slots](#inline-fn--keyword-slots-the-exemption-case) an inline fn's `:source-code` text is co-located on its enclosing node, so visualisers and Xray CAN render the body. The bias is about a *name*, *reuse*, and *addressability* — not source visibility):

- **Visualisers label arrows with ids.** A diagram exporter can label an arrow `:under-quota?` and have it carry meaning at a glance. An inline fn has the source available but no name — the diagram shows the whole body (or an anonymous `[fn]` glyph) where a name would have summarised the intent.
- **AIs and tooling reference ids, not closures.** When an AI reasons about a machine — generating tests, proposing changes, explaining behaviour — a keyword reference is a stable name it can resolve against the machine's `:guards` / `:actions` map (visible via `(machine-meta <id>)`). An inline fn is a closure with no public name to address, even though its source is now visible.
- **Humans read ids, not fn bodies.** A reviewer scanning a transition table sees `:guard :under-quota?` and knows what gates the transition; with `:guard (fn [{data :data ev :event}] ...)` they have to read the body to find out.
- **Tests read ids.** Level-1 (`machine-transition`) and Level-2 tests can stub or assert against named guards/actions by id — re-define the spec's `:guards` / `:actions` entry with a deterministic stand-in. Inline fns can only be replaced by re-writing the entire transition table.
- **Conformance fixtures read ids.** A fixture's expected `:fx` vector can name `[:dispatch [:audit/login-ok]]` against the action `:record-success` declared in the machine's `:actions` map; inline-fn equivalents are not addressable.

Inline fns remain acceptable for **trivial bodies that don't add meaning by being named** — e.g. `:guard (fn [{data :data}] (some? (:circle-id data)))` is fine; naming it as `:has-circle?` may add no information beyond what the body already shows. The test is whether the fn body is a single non-branching expression: yes → inline is OK; no → name it in `:guards` / `:actions`.

Cross-references: [Construction-Prompts.md](Construction-Prompts.md) covers scaffolding guidance.

## What 002 already gives us (recap)

[002 §State machines are just event handlers](002-Frames.md) commits the following at the foundation level:

- **The machine IS the event handler.** A registered event handler whose body comes from `make-machine-handler` is the machine; machines register under the `:event` registry kind.
- **Three-way conceptual split:** definition (data), instance (snapshot at the runtime-managed `[:rf.runtime/machines :snapshots <id>]`), frame (actor-system boundary).
- **Snapshot shape:** `{:state <fsm-keyword> :data <map>}`. `:state` is the discrete FSM keyword (`:idle`, `:editing`, ...); `:data` is the extended state — the machine's own private memory.
- **Pure transition contract:** `(machine-transition definition snapshot event)` → a `re-frame.machines.result` value: `result/ok?` discriminates, `result/snap` / `result/fx` read the post-transition snapshot and emitted fx vector (`result/fail` carries `result/info`).
- **Pure factory:** `(make-machine-handler spec) → fn`. Returns a re-frame event-handler fn whose construction is a pure value transform of `spec` — its identity (the surrounding `reg-event` id, or the `[:rf.machine/spawn ...]`-supplied id) is bound by the caller.
- **Definition shape:** transition table is pure data; guards/actions referenced by id or supplied as fns; both forms are first-class.
- **Inspection:** lifecycle/transition events emitted on the existing trace surface — discriminated by their `:rf.machine.*` `:operation` keyword (`:rf.machine.lifecycle/created`, `:rf.machine/transition`, `:rf.machine/snapshot-updated`, …). Machine-emitted dispatches carry `:source :machine-action` on the envelope (the actor-message path; `:dispatch` / `:dispatch-later` fx handlers stamp this when the parent envelope is `:rf.machine/internal? true`).
- **Composition:** ordinary `dispatch` between machines, made deterministic by drain semantics.
- **Discipline:** machines reuse the existing event registry, dispatch pipeline, and effect substrate; machine snapshots live as values in `runtime-db`.

This Spec describes everything else.

## Drain semantics

The two mechanisms (`:raise` and `:fx [:dispatch ...]`) compose at four nested levels. Each level has a single deterministic rule. Implementations must produce these orderings exactly.

### Level 1 — within a single action's effect map

An action returns `{:data ... :fx [...]}`. The two keys are independent:

- **`:data`** — a single map; merged into the current data map (last write wins on key collision).
- **`:fx`** — a vector of `[fx-id args]` pairs; processed **in vector order**. The machine handler's effect-map composer walks `:fx` and routes by fx-id:
  - `[:raise <event-vec>]` → appended to the local pre-commit raise-queue.
  - `[:rf.machine/spawn <spawn-spec>]` → installs the new actor's snapshot immediately (a pure runtime-db write — no per-instance handler registration; the actor's liveness IS its snapshot, per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)). Each spawn happens before the next `:fx` entry is processed; the spawned id is tracked at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` and the spec's `:on-spawn` advisory callback fires for observation — its return is dropped; if `:start` is present, an event is queued to the new actor (which lazy-resolves its snapshot on first dispatch).
  - any other `[fx-id args]` → forwarded to the standard `do-fx` for runtime processing.

The relative order of `:raise` entries in `:fx` is the order they enter the local raise-queue. The relative order of non-raise fx entries is the order they reach `do-fx`.

This Level-1 walk is the machine-layer instance of the runtime-wide [`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees) — `:db` (here, the lowered `:data` write) commits before any `:fx` entry, `:fx` entries process in source order, each entry's fx-handler returns before the next begins, and an fx-handler exception traces independently without halting subsequent entries. `:raise` is routed locally to the machine's pre-commit queue; `:rf.machine/spawn` and `:rf.machine/destroy` reach `do-fx` like any other registered fx (see [§`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx)).

### Level 2 — across the action slots in one transition

A transition that fires runs an ordered sequence of action slots. **Flat machines** see at most three slots, in this fixed order:

1. **`:exit`** action of the source state (one fn or registered id).
2. **`:action`** on the transition itself (one fn or registered id).
3. **`:entry`** action of the target state (one fn or registered id).

Each slot is optional; absent slots are skipped.

**Compound machines** generalise the slot list along the LCCA-cascade described in [§Hierarchical compound states §Entry/exit cascading](#entryexit-cascading-along-the-lca). Given source path A and target path B, with LCCA L (the **least common compound ancestor** — see that section for the exact definition; it is the longest common prefix *except* when B lies on A's active path, where it pulls up to re-enter the targeted ancestor):

1. **Exit cascade** — `:exit` actions of A's states from leaf back to (but not including) L. **Deepest-first.**
2. **Transition `:action`** — fires once at the LCCA boundary.
3. **Entry cascade** — `:entry` actions of B's states from (the level just below) L down to leaf. **Shallowest-first.**
4. **Initial cascade** — if B's leaf is itself a compound state, descend its `:initial` chain; each cascaded state's `:entry` action fires shallowest-first as the path lengthens.

For a flat machine, A and B are length-1 paths, L is the root, and the four-step generalisation collapses to the three-slot exit → action → entry order above.

Self-transitions: re-frame2 follows **XState v5** — a `:target` that names the same state as the source (or a proper ancestor on the active path) is **internal by default**. The targeted state's own `:exit` / `:entry` do **not** fire; on a compound, the target's descendants re-resolve to its `:initial` (see [§Self-transitions](#self-transitions--internal-default-vs-external-reenter) for the full three-case rule). Add **`:reenter? true`** to opt in to **external** — the state is exited and re-entered (`:exit` then `:entry` fire, with the transition's `:action` in between). Omit `:target` entirely for a pure **internal no-op** — the transition's `:action` runs; no exit/entry, no cascade, descendants preserved.

Composition across all action slots' returned effect maps:

- `:data` updates merge in slot order — exit-cascade (deepest-first) → action → entry-cascade (shallowest-first) → initial-cascade. Last write wins on key collision.
- `:fx` entries concatenate in slot order. Within the concatenated `:fx`, the Level-1 walk (`:raise` → local queue, `:rf.machine/spawn` / `:rf.machine/destroy` and the rest → `do-fx`) preserves order.

### Level 3 — within a single machine event

When a machine receives an event:

1. Resolve which transition fires (guards evaluated left-to-right; first match wins).
2. Run the action group (Level 2).
3. **Unified microstep loop — `:always` first, then dequeue one raise** (XState v5 / SCXML §3.13 macrostep): after the taken transition, the runtime runs ONE loop. Each iteration **prefers eventless (`:always`) transitions**: inspect the current state node's (and, for hierarchical compounds, every entered ancestor's deepest-first) `:always` vector; if a guarded entry matches (first-match-wins), apply that transition (run its `:action`, update the in-flight snapshot, accumulate its `:fx`, **append any events it raises to the *back*** of the internal-event queue), and loop. **Only when NO `:always` is enabled** does the runtime pop the **front** of the raise-queue, dispatch it through the same machinery, accumulate its `:fx` (including any `:rf.machine/spawn` / `:rf.machine/destroy` entries), **append any events it itself raises to the *back*** of the queue (behind the still-pending siblings) — and loop back to again prefer `:always`. The loop ends when no `:always` is enabled AND the raise-queue is empty.
4. **Order within the loop.** Two rules hold simultaneously. (a) **`:always` settles before the next raise is dequeued** — a transition that raises `R` while entering a state with an enabled `:always` takes the `:always` first and handles `R` in the post-`:always` state (XState v5 / SCXML §3.13; see [§Order with `:raise`](#order-with-raise)). (b) **FIFO among raised events** — once dequeued, raises drain front-to-back, a raise's own raises appending to the back; so raises `[A]` then `[B]` where `A` raises `[C]` process `A, B, C` (see the *FIFO for `:raise`* bullet in [§Why these rules](#why-these-rules)). See [§Eventless `:always` transitions](#eventless-always-transitions) for the full microstep semantics.
5. Commit the snapshot (state-keyword + merged data) to **runtime-db** at `[:rf.runtime/machines :snapshots <id>]`, in a single `:rf.db/runtime` write (the framework-authority machine handler's runtime-db effect, per [§Where snapshots live](#where-snapshots-live) — never an app-db `:db` write).
6. Emit the accumulated `:fx` as the event handler's return value, which the standard re-frame interceptor pipeline's `do-fx` then processes.

The whole machine event — including all raised sub-events **and all `:always` microsteps** — appears as **one logical step** (one **macrostep**) to the outside. The trace shows it as one `:rf.machine/transition` with the raise-cascade and microstep count in its `:tags`. xstate/SCXML macrostep semantics: external observers (subs, other machines, tools) see only the post-commit settled snapshot.

Bounded by `:raise-depth-limit` (default 16, exceeding emits `:rf.error/machine-raise-depth-exceeded`) and `:always-depth-limit` (default 16, exceeding emits `:rf.error/machine-always-depth-exceeded`). Both limits halt the cascade with the snapshot uncommitted; the recovery is `:no-recovery`.

### Level 4 — across the runtime

The router maintains a single per-frame queue. It is **FIFO by default with one exception** — machine-originated continuation events leap-frog to the front so a machine settles its macrostep before the next external event runs (SCXML-aligned).

- **Ordinary dispatched events go to the back.** Events whose origin is user code, the UI, a timer/promise/websocket callback, an async-effect response, or any `:fx [[:dispatch …]]` emitted by a **non-machine** handler — go to the **back** of the queue. This is plain FIFO, even if the event *targets* a machine. The arrival order is the run order.
- **Machine-internal continuation events go to the FRONT.** An event dispatched **from a machine's own processing** — its `:action` / `:entry` / `:exit` / transition handling, e.g. an action's `:fx [[:dispatch …]]` or an inter-machine dispatch — is inserted at the **front** of the queue, ahead of any already-queued external events. The effect: the machine drives its **macrostep to quiescence before the next external event is processed**, matching SCXML's "internal events run before external events" macrostep rule.
- **The cut is the dispatch's *origin*, not its target.** An event leap-frogs **iff** it is a machine-originated continuation — dispatched *during* machine action / transition processing. An event that merely *targets* a machine but originates from user code, the UI, or a non-machine effect stays **FIFO** at the back. (The router tags machine-internal events at dispatch time with a `:rf.machine/internal?` envelope mark; the origin-branching `do-fx :dispatch` and the front-of-queue splice live in [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode). This spec states the observable order; 002 carries the mechanism.)
- **Each dequeue runs to completion before the next.** A machine event's full Level-3 cascade (raised sub-events and snapshot commit) finishes before the next queue event is processed. Front-of-queue changes which event is dequeued next; it does not interleave cascades.
- **`do-fx` runs after the handler returns and before the next dequeue** — so for a *non-machine* handler, `:fx [[:dispatch :ev-X]]` emitted during event Y lands at the back, *after* anything Y queued earlier and *before* the next dequeue. For a *machine* handler, the same `:fx [[:dispatch …]]` lands at the front; multiple machine-internal dispatches from one macrostep preserve their source order at the front (the first emitted is dequeued first).

**`:raise` is unchanged — and is a different lever from front-of-queue.** `:raise` is the **in-memory, intra-macrostep, pre-commit** mechanism: a raised event drains through the machine's local raise-queue inside the *same* handler invocation, FIFO, against the evolving in-flight snapshot, and **never touches the router queue** (per [§`:raise`](#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx) and Level 3 above). Front-of-queue is the *separate* lever for machine-originated events that **do** traverse the router queue (`:fx [[:dispatch …]]`, inter-machine dispatches): these are real, separately-dequeued events that still settle ahead of external work. The two must not be blurred — `:raise` collapses chaining into *one* macrostep with no router round-trip; front-of-queue *orders* router-queue events so a machine's follow-on events run before external ones, each as its own dequeue.

**Consistent with epoch-per-event ([002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)).** Front-of-queue changes **order only, not granularity.** Each leap-frogged machine-internal continuation is still a separately-dequeued event, so it is still **its own epoch** with its own pipeline run and its own trace — exactly as [002 §One epoch per dequeued event](002-Frames.md#drain-versus-event--the-epoch-unit) requires. `:raise` sub-events and `:always` microsteps remain *inside* the triggering event's epoch (they are not dequeued); a front-of-queue `:fx [[:dispatch …]]` is a fresh dequeue and a fresh epoch — it simply runs sooner.

### Worked walkthrough

```
;; user code:
(rf/dispatch [:M [:start]])
(rf/dispatch [:other-thing])

;; runtime queue: [[:M [:start]] [:other-thing]]

;; --- dequeue [:M [:start]] -----------------------------------
;; suppose M's :start transition has:
;;   :action (fn [_] {:fx [[:raise [:input1]]
;;                           [:raise [:input2]]
;;                           [:dispatch :ev-A]]})
;; and :input1's transition has :action (fn [_] {:fx [[:dispatch :ev-B]]})
;; and :input2's transition has :action (fn [_] {:data {:n 1}})

;;   1. apply :start's action; walk :fx left-to-right:
;;        [:raise [:input1]]  → local raise-queue: [[:input1]]
;;        [:raise [:input2]]  → local raise-queue: [[:input1] [:input2]]
;;        [:dispatch :ev-A]   → outgoing fx: [[:dispatch :ev-A]]
;;
;;   2. drain local raise-queue:
;;      pop :input1 → run its transition; its :fx [[:dispatch :ev-B]]
;;        outgoing fx: [[:dispatch :ev-A] [:dispatch :ev-B]]
;;      pop :input2 → run its transition; data merges in
;;
;;   3. commit snapshot to runtime-db (one :rf.db/runtime write at [:rf.runtime/machines :snapshots <id>])
;;
;;   4. emit outgoing fx → these are MACHINE-ORIGINATED dispatches, so
;;      do-fx inserts :ev-A, :ev-B at the FRONT of the queue (Level 4),
;;      ahead of the already-queued external [:other-thing], preserving
;;      their source order (:ev-A before :ev-B). The machine drives its
;;      follow-on events to quiescence before the next EXTERNAL event.
;;
;; runtime queue: [[:ev-A] [:ev-B] [:other-thing]]

;; --- dequeue [:ev-A] -----------------------------------------
;;   :ev-A is a plain (non-machine) handler; suppose it dispatches [:ev-C].
;;   :ev-C originates from a NON-machine handler → goes to the BACK (FIFO).
;;   runtime queue after: [[:ev-B] [:other-thing] [:ev-C]]

;; --- dequeue [:ev-B] -----------------------------------------
;;   ... runs; the remaining machine-originated continuation settles ...

;; --- dequeue [:other-thing] BEFORE :ev-C ---------------------
;;   The external [:other-thing] was leap-frogged by the machine's
;;   :ev-A / :ev-B, but it still precedes :ev-C: it was queued (from user
;;   code) before :ev-C (a non-machine back-of-queue dispatch). FIFO holds
;;   among non-machine events; only machine-internal continuations jump.

;; --- dequeue [:ev-C] -----------------------------------------
;;   last. Each dequeued event above — machine-originated or not — is its
;;   own epoch (per 002 §Drain versus event); front-of-queue changed only
;;   the order, not the epoch granularity.

;; --- dequeue [:ev-C] -----------------------------------------
;;   ...
```

### Why these rules

- **FIFO at the runtime layer, with machine-internal events at the front** — external events keep actor-mailbox FIFO semantics, identical to the router's enqueue/dequeue order (the order the trace events are emitted; correlate via `:rf.trace/dispatch-id`). The single exception is machine-originated continuation events (Level 4 above), which leap-frog to the front so a machine completes its macrostep to quiescence before the next external event — SCXML's "internal before external" rule. The cut is the dispatch's *origin* (machine processing), not its target, so external dispatches stay predictably FIFO.
- **FIFO for `:raise` (XState v5 / SCXML parity).** The local raise-queue is drained **FIFO / breadth-first**, exactly as SCXML drains the *internal event queue* and XState v5 drains its internal `raise`d events. A transition that raises `[A]` then `[B]`, where `A`'s handler itself raises `[C]`, processes them in the order **`A, B, C`** — `C`, raised while handling `A`, goes to the *back* of the queue, behind the still-pending sibling `B`, **not** ahead of it. The macrostep boundary and the single atomic commit also match SCXML (external observers see only the settled snapshot — [§Level 3](#level-3--within-a-single-machine-event)). **This is the XState/SCXML gold standard for internal-event ordering, and re-frame2 follows it.** Mechanically: each microstep handling a raised event runs its transition and settles its own `:always` (per [§Order with `:raise`](#order-with-raise)) before the *next* internal event is dequeued, and any events it itself raises are enqueued at the back — SCXML's microstep loop verbatim. FIFO and depth-first settle to the same state whenever the chained transitions commute; they differ only when a single transition raises ≥2 events and an earlier one transitively raises more. An XState/SCXML machine that relies on FIFO raise ordering now ports **without** re-checking this axis. **FIFO-among-raises is a separate axis from `:always`-vs-raise ordering** (see [§Order with `:raise`](#order-with-raise)): the loop *prefers* enabled `:always` over dequeuing the front raise, and only the dequeue order among raises is FIFO.
- **Action / transition / event composition is left-to-right, in-spec-order** — readers of the transition table can compute the effect order by eye. No "actions can be reordered for optimisation"; the order in the source is the order at runtime.
- **Snapshot commit is atomic per machine event** — sub-events raised within a machine see the *evolving* data through the local raise-cascade, but external observers (subs, other machines, tools) only see the post-commit snapshot. This prevents partial-snapshot observation.
- **Bounded raise-depth** — protects against infinite `:raise` loops; emits a structured error.

These rules belong in the conformance corpus as fixtures that exercise each rule.

### Drain semantics gotchas

The four-level drain has a small number of recurring implementation mistakes. Each is observable as a deviation from the rules above; each has a single fix.

- **Implementing `:raise` via the runtime router queue rather than a local pre-commit queue.** *What goes wrong:* the raised event lands on the *global router queue*, behind other events queued in this turn — so external observers can interleave between the raise and its handling, and the macrostep is no longer atomic. *Instead:* keep a per-machine-event raise-queue inside the handler invocation; drain it **FIFO** (a raise's own raises append to the *back*, behind pending siblings — XState/SCXML internal-event-queue parity) before committing the snapshot, never via the runtime router. (The pitfall is *which queue*, not *which order*: the local raise-queue and the router queue are both FIFO; the bug is routing raises through the router at all.)
- **Committing the snapshot before draining the raise queue.** *What goes wrong:* sub-events in the cascade observe their own *partial* snapshot (the post-action commit), not the evolving in-flight one — so a chained raise can re-fire a transition mid-cascade. *Instead:* the snapshot is committed *after* the raise queue is drained (Level 3 step 4), exactly once, atomically.
- **Conflating `:fx [:dispatch <self-id>]` with `:raise`.** They have different semantics on two axes — commit timing and macrostep membership. `:raise` runs *before* commit, FIFO, **in the same logical step** (one macrostep, one epoch, no router round-trip), against the *evolving in-flight* snapshot. `:dispatch` to self is a **separate dequeued event** (its own epoch) that round-trips through the router queue and runs against the *post-commit* snapshot. Because the dispatch originates from machine processing, it leap-frogs to the **front** of the queue (Level 4 above) — it runs *before the next external event* but *after* the current macrostep commits, **not** inside it. *Instead:* use `:raise` for transition-chaining intended to settle inside one externally-observable macrostep; use `[:dispatch [<self-id> ...]]` only when you genuinely want a fresh post-commit epoch — front-of-queue means it still runs ahead of external work, but it is a distinct step, not part of this one.
- **Not bounding raise-depth.** *What goes wrong:* a buggy `a → raise b → raise a → ...` cycle hangs the runtime. *Instead:* enforce the default depth-16 limit and emit `:rf.error/machine-raise-depth-exceeded` when it's hit; halt the cascade and surface the path.
- **Treating "self-transition with `:target`" as external (the v4/SCXML reflex).** A self / proper-ancestor `:target` is **internal by default** (XState-v5) — `:exit`/`:entry` do **not** fire, the configuration is unchanged. *Instead:* add **`:reenter? true`** when you want exit/entry to fire (and a compound to re-descend its `:initial`). A self-target *without* `:reenter?` is observationally identical to a targetless internal transition. See [§Self-transitions](#self-transitions--internal-default-vs-external-reenter).
- **Treating "transition without `:target`" as external.** It is **internal** — neither `:exit` nor `:entry` fires; only the transition's `:action` runs. *Instead:* omit `:target` (or self-target without `:reenter?`) only when you want a pure data update with no exit/entry machinery; if you want exit/entry on a self/ancestor target, add `:reenter? true`; to move elsewhere, name a different target.
- **Forgetting to cascade `:initial` when entering a compound state via vector target.** *What goes wrong:* a transition with `:target [:authenticated]` (a compound state) lands the snapshot at the compound itself instead of descending — the snapshot's `:state` is `[:authenticated]` rather than `[:authenticated :dashboard]`, and downstream code that walks to a leaf gets confused. *Instead:* every vector target whose final segment names a compound state continues to cascade the compound's `:initial` chain until it hits a leaf; the snapshot's `:state` is always a leaf path. Each cascaded state's `:entry` fires shallowest-first.
- **Resolving keyword `:target` against the runtime's current path instead of the declaring state.** *What goes wrong:* a transition `{:target :browsing}` declared on a parent state is resolved as "the current state's sibling" rather than "this declaration's parent's child." Across deeply-nested machines the two diverge. *Instead:* keyword targets are **statically resolved** against the parent of the state-node that owns the `:on` map; the resolution is a property of the transition table, not of the snapshot. When in doubt, use the vector form — it is unambiguous.
- **Referencing an undeclared guard or action id.** *What goes wrong:* a transition slot `:guard :form-valid?` (or `:action :clear-error`) names a keyword that does not appear in the machine's `:guards` (or `:actions`) map — a typo, a stale reference left over after a rename, or a copy-paste from another machine's spec. *Instead:* `make-machine-handler` walks every `:guard` / `:action` slot at construction time (in `:on`, `:always`, `:entry`, `:exit`) and verifies each keyword reference resolves against the machine-local `:guards` / `:actions` map. Misses surface as `:rf.error/machine-unresolved-guard` or `:rf.error/machine-unresolved-action` at registration time, with `:tags {:guard-id <id> :machine-id <id>}` (or `:action-id`). The error fires before any snapshot is created — caught at registration, not at runtime.
- **Unbounded `:always` cycle.** *What goes wrong:* `:a` has `:always {:guard :p? :target :b}` and `:b` has `:always {:guard :q? :target :a}`; both guards remain true and the microstep loop never reaches a fixed point. The handler hangs the runtime. *Instead:* enforce the default depth-16 limit on the microstep loop and emit `:rf.error/machine-always-depth-exceeded` when it's hit; halt the cascade with the snapshot uncommitted, and surface the visited path. Gotcha is the same shape as the `:raise` cycle gotcha above — a separate counter, the same recovery pattern.
- **`:always` self-loop accepted at registration.** *What goes wrong:* a state declares `{:always [{:guard :ok? :target :same-state}]}` (or `{:target <itself>}`). The first microstep matches; the next microstep matches again on the same state and same guard; the loop runs to depth-limit and aborts. The author meant a no-op or got the topology wrong. *Instead:* `make-machine-handler` rejects **any** `:always` entry whose `:target` resolves to the declaring state itself (guarded or not) at registration time, surfacing `:rf.error/machine-always-self-loop` with `:tags {:state <state-keyword> :machine-id <id>}`. The error fires before any snapshot is created — caught at registration, not at runtime. (The canonical re-evaluate-until-condition / fixed-point pattern is a *targetless* guarded `:always` with an `:action` — `{:guard :more? :action :bump}` — whose action flips the guard false and the microstep loop settles; a re-entry on a genuinely changed condition targets a **distinct** state, never the declaring one.)
- **Forgetting to advance the `:after` epoch on state entry.** *What goes wrong:* a machine handler schedules a fresh `:after` timer at state entry but reuses the *prior* visit's epoch counter — so an in-flight timer from the previous visit fires with a matching epoch and triggers an unintended transition long after the user moved on. Symptom: "a timer fired that I thought was cancelled." *Instead:* each `:after`-bearing node's per-path `:rf/after-epoch` entry advances **on every entry to that node** (per [§Delayed `:after` transitions §Epoch-based stale detection](#epoch-based-stale-detection)); each scheduled timer carries its node's post-increment epoch and decl-path; the receiving handler validates both before firing. There is no cancellation — staleness is the cancellation mechanism.
- **Scheduling `:after` timers under SSR.** *What goes wrong:* a machine entered server-side schedules a `:dispatch-later` for a 5-second timeout; the SSR render captures the timer-handle but the request-frame is destroyed before it fires; the timer leaks (or fires against a destroyed frame). Symptom: stray events on the server, or hydration mismatches because the server's snapshot includes "scheduled timer" state the client doesn't share. *Instead:* `:after` no-ops in SSR mode (per [§SSR mode](#ssr-mode) and [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)); the entry action skips timer scheduling on the server, and the client schedules them after hydration.

These gotchas are also worth fixturising in the conformance corpus — each one is a single-fixture assertion against a specific deviation.

## Hierarchical compound states

A state node may itself contain a `:states` map — making it a **compound state** with its own substates. The grammar recurses: a substate may be a leaf (no `:states`) or another compound (has `:states`, must declare `:initial`). This extends the flat grammar additively; flat machines stay flat.

> **Why compound states exist.** They factor common transitions out to a parent, so every authenticated descendant inherits `:logout` without restating it. The runtime walks from the active leaf up to root looking for a matching transition — this is the **deepest-wins with parent fallthrough** rule documented below.

### Snapshot shape with hierarchy

For a compound machine, the snapshot's `:state` is a **vector path** from root to the active leaf:

```clojure
{:state [:authenticated :cart :browsing]
 :data  {...}}
```

A flat machine's `:state` remains a single keyword (`:idle`); see [§Snapshot shape](#snapshot-shape) for the dual form. A single-keyword `:K` read against a hierarchical definition is treated as the path `[:K]` (which must name a leaf at the root level).

### Initial-state cascading

Every compound state-node MUST declare `:initial` — the substate to enter when control reaches the compound state without a deeper target. Entering a compound state **cascades down its `:initial` chain** until it reaches a leaf:

```clojure
{:initial :authenticated
 :states
 {:authenticated
  {:initial :dashboard            ;; required because :authenticated is compound
   :states {:dashboard {...}
            :cart      {:initial  :browsing
                        :states {:browsing  {...}
                                 :paying    {...}
                                 :confirmed {...}}}}}}}
```

Targeting `[:authenticated]` lands the snapshot at `[:authenticated :dashboard]`; targeting `[:authenticated :cart]` lands at `[:authenticated :cart :browsing]`. Each cascaded state's `:entry` action fires in shallowest-first order as the path lengthens (see [§Entry/exit cascading](#entryexit-cascading-along-the-lca)).

A compound state without `:initial` is a registration error — emits `:rf.error/machine-compound-state-missing-initial` at registration time and, in the CLJS reference with schema validation enabled, fails the registration.

#### Initial-state `:entry` fires on machine creation (start)

When a machine **first comes into existence** — a singleton on its first dispatched event, or a spawned actor on `:rf.machine/spawn` — the initial-state cascade's `:entry` actions fire **once, shallowest-first**, as part of bringing the machine to life. For a flat machine that means the single initial state's `:entry` runs; for a compound machine with a multi-level `:initial` chain, **every** state along the chain runs its `:entry` in shallowest-first order.

```clojure
{:initial :outer
 :states  {:outer {:entry   :enter-outer        ;; fires on start
                   :initial :mid
                   :states  {:mid  {:entry   :enter-mid     ;; fires on start
                                    :initial :leaf
                                    :states  {:leaf {:entry :enter-leaf}}}}}}}
;; initial-entry log: [:enter-outer :enter-mid :enter-leaf]
```

The initial-entry cascade composes with **all** the slots the entry cascade carries — `:spawn`, `:spawn-all`, `:after` on any node along the initial chain emit their corresponding fx (`:rf.machine/spawn`, `:rf.machine/spawn-all-init`, `:after-schedule`) at creation time. So a `:requesting` initial state that declares `:entry :fire-request` AND `:spawn {:machine-id :rf.http/managed ...}` has the entry action run AND the child machine spawned, before the actor's first user-routed event arrives.

The cascade also composes with `:always`: once the initial leaf is entered, the runtime runs the eventless (`:always`) + raise settle (the initial macrostep — see [§When creation happens](#when-creation-happens--eager-start-vs-lazy-first-event)) before committing the birth, so an initial leaf whose `:always` guard already holds is settled past on start.

##### When creation happens — eager start vs lazy first event

Creation is a side-effect of the machine handler running, and the handler only runs when something is dispatched to the machine. So there are exactly two ways the **initial macrostep** fires — a per-machine choice, both kept:

- **Eager start** — the surrounding program deliberately dispatches the synthetic **`[:machine-id [:rf.machine/start]]`** kick (xstate parity with `createActor(m).start()`) to bring the machine alive *now* — typically when the initial state has work to do at birth (arm an `:after` timer, run an `:entry` action, wire a subscription). The kick runs the initial macrostep **then STOPS**.
- **Lazy** — no kick; the initial macrostep folds into the machine's **first real event** (the handler finds no snapshot, runs the initial macrostep, then processes the event). Used when the initial state is quiet (nothing to set up at birth).

> **The initial macrostep = initial-entry cascade + the eventless (`:always`) settle.** Per xstate v5 / SCXML §3.13, birth is not just the entry cascade: after the initial configuration is entered, the processor **immediately** runs the eventless microstep loop to a fixed point *before* waiting for any external event. re-frame2 mirrors this — once the initial-entry cascade has built the birth snapshot, the runtime runs the **same** `:raise`-drain + `:always` fixed-point loop the event macrostep uses (per [§Drain semantics](#drain-semantics) Level 3 and [§Microstep loop within drain](#microstep-loop-within-drain)), **before the machine-birth commit**. Consequence: a *transient* initial leaf whose `:always` guard already holds is settled **past** — unobserved — on start; the externally-visible birth state is the `:always` target, not the transient initial leaf. This applies to **both** birth paths (eager `[:rf.machine/start]` and lazy first-event) and to every region of a parallel machine (each region's birth `:always` settles independently; region-emitted `:raise`s re-broadcast through the parent's one internal-event queue per [§Parallel regions](#parallel-regions)). The settle is bounded by `:always-depth-limit` / `:raise-depth-limit` and is atomic — a runaway birth `:always` cycle rolls back to the post-cascade initial configuration. A machine with **no** `:always` settles in zero microsteps, so its birth is identical to a pure entry cascade.

> **The start marker is a *pure init-kick* (no self-trigger).** When the dispatched trigger **is** the reserved `:rf.machine/start` marker, the runtime runs the initial macrostep (initial-entry cascade + eventless settle) and **stops** — it does **not** re-feed the marker into a further transition step as a normal event. This matches xstate's `xstate.init`, which enters the initial state, runs its `:entry` actions and settles its eventless transitions, but is **not** itself re-processed as a user event. Consequence: an eager start never produces a `:before == after` self-transition row, and never trips a `:*` wildcard on the initial state (a creation kick can never reach an `:on` map). The birth is signalled instead by a dedicated `:rf.machine/started` trace (see [§The `:rf.machine/started` trace](#the-rfmachinestarted-trace)). When the birth `:always` settles onto a **final** leaf (a `:final?` initial-or-`:always`-target), the eager-start path runs the `:on-done` + auto-destroy cascade exactly as the lazy path would (per [§Final states](#final-states-final--on-done--output-key)).

For singleton machines the lazy-path initial macrostep's fx flow out as part of the **first event's** handler return value (the initial macrostep's `:fx` — entry cascade + birth `:always` — and the first event's transition cascade share the same `:fx` accumulator); the eager-start path emits only the initial macrostep's fx (it stops before any user-event transition step). For spawned actors creation fires when the runtime dispatches the actor's first event — the synthetic `[:rf.machine.spawn/spawned]` per [§Synthetic `[:rf.machine.spawn/spawned]` on spawn](#synthetic-rfmachinespawnspawned-on-spawn), or the user-supplied `:start` per [§Spawn-spec keys](#spawn-spec-keys).

**Error semantics.** A throw inside any initial-`:entry` action halts creation identically to a throw inside any other entry cascade: the snapshot does NOT commit, no `:fx` flow, and a single `:rf.error/machine-action-exception` trace fires (per [§Errors](#errors)). The pre-creation state — no snapshot at `[:rf.runtime/machines :snapshots <id>]` — is preserved. This holds on **both** the eager-start and lazy paths: an initial-`:entry` action that throws raises on the boot cascade itself, before any user event is processed.

The canonical shape for "do work on machine spawn" is `:entry :fire-request` on the initial state. Generic child machines MAY also declare `:on :rf.machine.spawn/spawned :action :fire-request` (the synthetic event the runtime dispatches when the spawn args carry no `:start`); this resolves as a no-op transition through the standard `:on` lookup. New code prefers `:entry`.

##### Synthetic creation marker — `[:rf.machine/start]`

The reserved `:rf.machine/start` marker has **two roles**, both `:entry`-only (it NEVER reaches an `:on` map):

1. **The eager creation kick.** Dispatched as `[:machine-id [:rf.machine/start]]` it brings a machine alive now (see [§When creation happens](#when-creation-happens--eager-start-vs-lazy-first-event)). As a *pure init-kick* it runs the initial macrostep — the initial-entry cascade **plus** the eventless (`:always`) + raise settle — then stops.
2. **The cascade `:event` placeholder.** When the initial-entry cascade fires (on either the eager or lazy path), the runtime threads the placeholder event vector `[:rf.machine/start]` through the action machinery under the context-map's `:event` key — needed because action fns receive `(fn [{:keys [data event state meta]}] effects)` and there is no user-dispatched event to thread on the lazy first-event path's boot.

Most `:entry` actions ignore the event argument (it's the data argument they read from). Authors writing introspection-capable `:entry` actions — actions that read the event vector to decide what to do — observe `[:rf.machine/start]` on the boot call, distinguishable from any user-dispatched event by the reserved `:rf.machine/*` namespace.

```clojure
;; Action reads the event head to log what triggered the entry.
(rf/reg-machine :auth-flow
  {:initial :requesting
   :states {:requesting {:entry :log-entry}}
   :actions {:log-entry
             (fn [{:keys [event]}]
               (let [trigger (first event)]
                 (println "entered :requesting via" trigger))
               {})}})

;; On creation, prints: "entered :requesting via :rf.machine/start"
;; On a later user-driven entry via `[:reset]`, prints: "entered :requesting via :reset"
```

The vector is **purely synthetic**: not registered as an event, never reaches a handler's `:on` map (the initial-entry cascade is `:entry`-only — there is no `:on :rf.machine/start` resolution; as the eager kick it is a pure init that stops, and as the placeholder it exists solely so the `:entry` action's event-argument is non-`nil`). The name is reserved under the `:rf.machine/*` namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); user code MUST NOT register a handler for this name. User code MAY dispatch `[:machine-id [:rf.machine/start]]` deliberately as the eager creation kick; it MUST NOT rely on any other interpretation of the marker.

> **The kick keyword is `:rf.machine/start`.** There is no `:rf.machine/bootstrap` keyword. The name has xstate parity (`createActor(m).start()`) and pairs with the past-tense `:rf.machine/started` trace below.

##### The `:rf.machine/started` trace

Whenever a machine runs its initial-entry cascade — the single creation site, fired on **both** the eager-start and lazy paths — the runtime emits one **`:rf.machine/started`** trace. This is the canonical signal that a machine came to life; tooling (Xray's Epoch panel) renders it as a `[START]` badge. Its `:tags`:

| Tag | Value |
|---|---|
| `:machine-id` | the machine / actor id |
| `:frame` | the frame the instance lives in |
| `:state` | the installed initial logical state (xstate `value`) |
| `:data` | the installed initial extended state (xstate `context`) |
| `:cause` | a `:rf.machine.start/cause` enum — **how** it started (below) |

**`:rf.machine.start/cause`** — `{:explicit :lazy :spawned}`:

- **`:explicit`** — singleton creation where the dispatched trigger *was* the `:rf.machine/start` marker (a deliberate eager kick; the handler found no snapshot).
- **`:lazy`** — singleton creation folded into a *real* first event (the handler found no snapshot; the trigger was an ordinary event). Flags that something dispatched to the machine before it was explicitly started — a possible ordering smell tooling can surface.
- **`:spawned`** — the snapshot was pre-seeded `:rf/bootstrap-pending?` by a spawn fx; initial-entry ran on the actor's first dispatch.

The trace is emitted **only when the initial-entry cascade actually runs**: a throwing initial-`:entry` short-circuits to `:rf.error/machine-action-exception` instead (no `:rf.machine/started`), and a redundant `[:rf.machine/start]` on an already-alive machine runs no cascade and emits nothing.

**Restoration paths emit NO `:rf.machine/started`.** SSR hydration, `restore-epoch!` / epoch replay, and `replace-frame-state!` install a snapshot **verbatim** — the snapshot *is* the state (per [§Restore semantics](#restore-semantics)). Such a snapshot is *present* and *not* `:rf/bootstrap-pending?`, so the runtime runs no initial-entry cascade and emits no `:rf.machine/started`. A restored machine was not *born* here; it was *reinstated*. (This falls out for free from the missing-or-pending creation predicate; no special-casing of restoration is needed.)

### Target resolution — vector vs keyword

A transition's `:target` admits **two forms**:

- **Vector form — absolute path from root.** `:target [:authenticated :cart]` always means the named root-level state's named substate, regardless of where the transition is declared. Vector targets are unambiguous and the recommended form for cross-level transitions.
- **Keyword form — relative to the state where the transition is declared.** `:target :review` resolves to a sibling of the current declaring state. The runtime resolves the keyword against the declaring state's *parent's* `:states` map. **Note:** "declaring state" is the state-node owning the `:on` map — not the state the machine happens to be in at runtime. This is a static resolution rule, evaluable from the transition table alone without consulting the snapshot.

Existing flat-machine targets (`:target :editing`) are keyword form, root-relative — unchanged from before, because in a flat machine the declaring state's parent is the root.

A target naming a compound state implicitly cascades through its `:initial` chain (see above). To target a specific leaf inside a compound, use the vector form: `:target [:cart :paying]`.

### Entry/exit cascading along the LCA

When the snapshot transitions from path A (the source) to path B (the target), the runtime walks both paths and computes the **LCCA** — the **least common compound ancestor**, the deepest compound state that is a **proper** ancestor of *both* A's leaf and B's target node. (XState v5 / SCXML §3.13 `findLCCA`; "LCA" is the historical short name and the section anchor, but the computation is the LCCA.) Three boundaries fire, in this order:

1. **Exit cascade.** Walk A from leaf back toward the LCCA, firing each state's `:exit` action — **deepest-first**. Stop at the LCCA exclusive (the LCCA itself does not exit; we are not leaving it).
2. **Transition `:action`.** Runs once at the LCCA boundary, between exit and entry. **Trace note:** the action *fires* at the LCCA boundary, but its cascade step is labelled with the **declaring-state path** (the state node owning the transition's `:on`/`:after` entry), not the LCCA — the label addresses *where the action was authored* (so tooling can source-jump to the inline action body) rather than the structural boundary it fires at. Exit/entry steps, by contrast, are labelled with the state they fire at.
3. **Entry cascade.** Walk B from (the level just below) the LCCA down to the target node, firing each state's `:entry` action — **shallowest-first**. If the target is itself a compound state, continue cascading via its `:initial` chain; the cascaded states' `:entry` actions fire as the path extends.

#### Computing the LCCA — the longest-common-prefix shortcut and its one exception

For the **common case** — A and B lie in *disjoint* subtrees (a sibling-leaf transition, a cross-level transition to a sibling subtree, or a transition to the root) — the LCCA *is* the longest common prefix of A and B: the two paths diverge at some node, and that node's parent (the last shared element) is a proper ancestor of both, so it survives the transition (it neither exits nor enters). This is why the longest-common-prefix shortcut is correct for the vast majority of transitions, and the worked examples below all fall in this case.

The shortcut **breaks whenever the target B (before its own `:initial` cascade) is on the active path or is a descendant the declaring compound names** — the geometries where B is itself one of the states the transition involves, so the LCCA must be a **proper** ancestor of the boundary node. Only a **targetless** transition is a true configuration no-op; every **explicit** target on the active path re-resolves at least the descendants below it (XState v5: *"a transition with an explicit target re-resolves child states to their initial state"*). Three sub-cases:

1. **Self / proper-ancestor target B, *without* `:reenter?` (re-resolve descendants).** B itself is **not** re-entered (its `:exit`/`:entry` do not fire), but the active states **strictly below B** exit and B's `:initial` chain re-descends. The boundary sits at **B's own depth**: B (and the prefix above it) survives; everything below B on the active path is replaced by B's re-resolved `:initial` configuration. Net effect at `[:process :step3]` with `:target :process`: `exit :step3 → action → enter :process's :initial (:step1)`; `:process` itself is untouched.
2. **Self / proper-ancestor target B, *with* `:reenter? true` (restart B).** The LCCA pulls **up to B's parent** and the geometry becomes a **restart of B**:
   - B and everything below it on the active path **exit** (deepest-first, **including B** — B's `:exit` runs);
   - the transition `:action` fires at the LCCA (B's parent);
   - B **re-enters** (B's `:entry` runs) and **re-descends its `:initial` chain** — B re-initialises to its initial child, regardless of which child was active when the transition fired.

   Net effect for an external (`:reenter? true`) target naming a proper ancestor A of the source: `exit (source-subtree leaf→A) → action → enter A → re-descend A's :initial`. This is how XState v5 / SCXML run an **external** transition to an ancestor (the LCCA of {source, A} is A's parent, so A is in the exit set), and it makes "external transition to A" a first-class **restart this compound state** operation. The external [self-transition](#self-transitions--internal-default-vs-external-reenter) is the degenerate case where A is the source leaf itself.
3. **Descendant target B named by the declaring compound S** (B is a *proper descendant* of S, the state on which the transition is declared). XState v5: *"child state nodes are always re-entered when targeted by transitions defined on compound state nodes."*
   - *Without* `:reenter?` — the **targeted descendant** B is re-entered (the active states below S down to B's parent exit, then S re-descends to B); the declaring compound S itself survives. The boundary sits at **B's parent**. Net effect at `[:parent :child :a]` with `:target [:parent :child]` declared on `:parent`: `exit :a, :child → action → re-enter :child → re-descend :child's :initial (:a)`; `:parent` untouched.
   - *With* `:reenter? true` — the **declaring compound S restarts** and lands on the named descendant B: the LCCA pulls up to **S's parent**, S exits + re-enters (its `:exit`/`:entry` fire, `:after` timers and `:spawn` children restart), then the configuration descends to **B** (not S's `:initial`). See [§Self-transitions](#self-transitions--internal-default-vs-external-reenter) (*Parent-declared re-entry to a specific descendant*).

> **Why the LCCA, not the plain longest common prefix?** If the LCA were computed as the longest common prefix of A and B's *initial-cascaded leaf*, a target whose `:initial` chain re-descends to the source would yield a common prefix equal to the *full* source path — the exit and entry sets would **both be empty** and the transition would be a **silent no-op**: the targeted descendant would not re-resolve, an ancestor restart's `:exit`/`:entry` would not fire. Getting the exit set wrong this way is the classic hierarchical-state-machine "LCCA trap". The boundary is therefore computed against the target **base** (the resolved target node *before* its `:initial` re-descent — the initial cascade is part of *entering* the target, not of locating the common ancestor): a self/ancestor target on the active path bounds at B's own depth (re-resolve descendants) or B's parent (restart, `:reenter?`); a descendant target named by the declaring compound bounds at B's parent (re-enter the targeted child) or — with `:reenter?` — at the declaring compound's parent (restart the compound onto B).

This is a generalisation of the flat exit → action → entry rule (where path length is 1 and the LCCA is the root).

### Transition resolution — deepest-wins with parent fallthrough

To resolve an event, the runtime walks the active path from **leaf up to root**, looking for the first state-node that *enables* a transition for the event. The first *enabled* match wins — and a guard-blocked candidate is **not** enabled, so it does not end the search; resolution descends the **three event-descriptor tiers** *within* a level — exact, then the namespace wildcard `:ns/*`, then the total wildcard `:*` (see [§Wildcard transitions](#wildcard-transitions)) — before walking up to the next ancestor:

1. Leaf state's `:on` — **exact** match (first guard-passing candidate).
2. Leaf state's `:on` — **`:ns/*` namespace wildcard** (the event id's namespace + `/*`). Consulted whenever step 1 yielded no enabled candidate — the exact key was absent **or** every one of its candidates was guard-blocked. Skipped for a non-namespaced event id.
3. Leaf state's `:on` — **`:*` total wildcard**. Consulted whenever steps 1–2 yielded no enabled candidate.
4. Parent state's `:on` — exact match.
5. Parent state's `:on` — `:ns/*`, then `:*` (same "no more-specific enabled candidate" rule as steps 2–3).
6. ... continue walking up ...
7. Top-level (root) `:on` — exact match.
8. Top-level `:on` — `:ns/*`, then `:*`.

This is **XState v5's transition-selection order**: within a state the runtime tries each descriptor in priority order (exact, partial-descriptor, catch-all) and selects the first whose guard is met; a guard-blocked transition is skipped, leaving lower-priority descriptors (including `:ns/*` and the catch-all `:*`) eligible; only when the whole state — exact *and* both wildcards — yields nothing does selection move to the ancestor. So each wildcard is the **least-priority enabled transition at its level** relative to the more specific tiers, not a "no more-specific *key* exists" fallback: `{:on {:mouse/down {:target :a :guard :g} :mouse/* :b}}` dispatching `:mouse/down` with `:g` false fires the same-level `:mouse/*` (`:b`), not the no-op; and `:mouse/*` is consulted for `:mouse/up` (no exact key) ahead of any `:*`.

If **no level enables a transition** for an **unknown user event** — no level has an enabled explicit candidate *or* an enabled `:*` — the snapshot is unchanged and the runtime emits the benign `:rf.machine.event/unhandled-no-op` trace (op-type `:rf.machine`, info-grade — see [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary)). **xstate-v5 parity (do not "fix" this back to an error):** xstate v5 removed the v4 `strict` flag — an event with no transition in the current state is simply ignored, no warning, no throw. re-frame2 adopts the same runtime semantics: an unhandled event is a no-op. Unlike xstate, which emits nothing, re-frame2 keeps the benign `:rf.machine.event/unhandled-no-op` observability trace so a debugger can report that an event arrived and was ignored — benign is not invisible. To "fail loudly on unknown" (the xstate-v5 idiom), declare a `:*` wildcard whose action throws; that is a real `:rf.error/machine-action-exception`, not an unhandled-event no-op. `:rf.machine.event/unhandled-no-op` is the canonical name.

**A no-op is single-signalled.** The `:rf.machine.event/unhandled-no-op` is the *sole* signal for an unhandled / guard-blocked event — the macrostep does **not** additionally emit a no-change `:rf.machine/transition` (one whose `:before` == `:after`, `:microsteps 0`, `:cascade []`). A no-change transition trace carries no information beyond the no-op signal and contradicts it (it borrows external-self-transition `{X} → {X}` vocabulary that implies the `:exit`/`:entry` firing that did not happen). The runtime suppresses the headline `:rf.machine/transition` emit whenever the macrostep changed nothing — `:before` == `:after`, an empty `:cascade`, and zero `:always` microsteps. This holds uniformly for an unhandled event and for an event that **enabled no transition at any level**. A *guard-blocked exact candidate is not by itself a no-op*: per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough) (steps 1–8) it falls through to the same-level `:ns/*`, then `:*`, and then up the hierarchy, and only resolves to the no-op when *no* level enables an exact *or* wildcard transition. The no-op fires for the truly-unhandled event — every candidate at every level (explicit and `:*`) was absent or guard-blocked. An eager `[:rf.machine/start]` kick never reaches this site at all: it is a *pure init-kick* — it runs the initial-entry cascade then STOPS, never re-fed into the transition step — so it emits **no** `:rf.machine/transition` (the birth is signalled by `:rf.machine/started`, per [§The `:rf.machine/started` trace](#the-rfmachinestarted-trace)), and a redundant `[:rf.machine/start]` on an already-alive machine likewise short-circuits and emits nothing. A machine's creation therefore never produces a `:before == after` self-transition row. (On the *lazy* path the macrostep is the real first event's transition — installing the initial state via a non-empty initial-descent `:cascade` — which is **not** a no-op and emits its `:rf.machine/transition` normally.) An internal self-transition (an action ran, no `:target`, `:before` == `:after`) likewise carries an `:action` cascade step and is never a no-op.

**Reserved-`:rf/*` lifecycle carve-out.** The no-op classifies an **unknown user event** a machine author may have forgotten to handle — it is NOT emitted for framework lifecycle traffic whose event-id lives in the reserved `:rf/*` root namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The synthetic creation marker `[:rf.machine/start]` (per [§Synthetic creation marker](#synthetic-creation-marker--rfmachinestart)), the spawn kick-off `[:rf.machine.spawn/spawned]` (per [§Spawn lifecycle — ordering](#spawn-lifecycle--ordering)), and the stories runtime's lifecycle / assertion pings (`:rf.story.lifecycle/*`, `:rf.assert/*`) are framework init, not events the author missed — a machine that declines them simply has no clause for them. (The eager `:rf.machine/start` kick is a pure init-kick that stops before the transition step, so it does not reach the no-op site as a trigger at all; the carve-out subsumes the marker only in its cascade-threaded-`:event`-placeholder role.) This **aligns with xstate**: xstate's own init (`xstate.init`) runs the initial-entry and is NOT reported as an unhandled event — only unknown user events are silently ignored. Distinguishing re-frame2's creation / spawn-kickoff makes us MORE like xstate, not a v5-parity violation. The carve-out is purely about **labelling, not severity** — nothing throws either way; severity stays benign.

The deepest-wins rule means a child state can **override** a parent's transition for the same event by declaring its own. Combined with parent fallthrough, this is how hierarchy factors common behaviour to the parent (every authenticated descendant inherits `:logout`) while still allowing local override.

#### Forbidden transitions — a child opts OUT of an inherited transition

Parent fallthrough is what *factors* a common transition to an ancestor; a child sometimes needs the inverse — to **opt out** of an inherited transition *without* replacing it with a real one. The mechanism falls out of the deepest-wins walk: a child `:on` entry that **matches but is internal** halts the leaf→root walk *at the child*, and an internal transition leaves the configuration unchanged — so the parent's inherited transition for that event is **never reached**. This is the **forbidden-transition idiom** — re-frame2's spelling of [XState v5's `on: {LOGOUT: undefined}`](https://stately.ai/docs/transitions#forbidden-transitions) (v4 `LOGOUT: undefined`) and of an [SCXML](https://www.w3.org/TR/scxml/#transition) **targetless internal** `<transition event="logout"/>`: a transition that *consumes* the event so the deepest-wins selection stops at the declaring node and the ancestor's transition is not taken.

Two equivalent spellings — a present `:on` key whose value is the **empty map** or **`nil`**:

```clojure
{:initial :authenticated
 :states
 {:authenticated
  {:initial :dashboard
   :on      {:logout [:unauthenticated]}    ;; factored to the parent — every descendant inherits it
   :states
   {:dashboard {:on {:open-modal :modal}}    ;; inherits the parent's :logout normally
    :modal     {:on {:logout {}}             ;; FORBIDDEN — empty map: a matching internal no-op,
                                             ;;            halts the walk → blocks the parent's :logout
                     ;; equivalently:  :logout nil
                     :close      :dashboard}}}}}}
```

While the machine rests in `:modal`, dispatching `:logout` resolves at `:modal` (a match) to an internal transition with no `:target` and no `:action` — the state is unchanged and the parent's `[:unauthenticated]` is **not** inherited. Leave `:modal` (e.g. `:close` → `:dashboard`) and `:logout` is inherited from `:authenticated` again. The idiom is the natural fit for "this substate must not honour a factored-to-parent transition right now" — an authenticated flow that blocks `:logout` while a modal / unsaved-edit guard is open.

Both forms are unified:

- **`{:on {:logout {}}}`** — an explicit empty transition map. Matches; internal (no `:target`); no-op (no `:action`).
- **`{:on {:logout nil}}`** — a **present** key with a `nil` value. `nil` is the natural Clojure analogue of XState's `undefined`, so it normalises to the **same** matching internal no-op — it **also blocks**. (Add an `:action` to either form — `{:on {:logout {:action :warn-cannot-logout}}}` — and the block still halts the walk *and* runs the action; the parent transition is still not inherited.)

**Absence is NOT a block.** This turns entirely on the key being **present**. A child whose `:on` table simply has *no* `:logout` entry inherits the parent's `:logout` as usual — absence means "I don't handle this; keep walking", presence-with-`nil`/`{}` means "I consume this here; stop walking". The distinction is the whole point: a missing key must keep falling through, a deliberately-`nil` key must block. (See [§Wildcard transitions](#wildcard-transitions) for how a forbidden block — an *enabled* internal candidate — differs from a *guard-blocked* candidate where the wildcard / parent fallthrough still applies.)

### Worked example — auth flow

```clojure
{:initial :unauthenticated
 :states
 {:unauthenticated
  {:on {:login [:authenticated]}}        ;; vector :target — absolute from root

  :authenticated
  {:initial :dashboard
   :on      {:logout [:unauthenticated]} ;; common — every authenticated descendant inherits
   :states
   {:dashboard
    {:on {:open-settings :settings        ;; keyword :target — sibling of :dashboard
          :open-cart     :cart}}
    :settings
    {:on {:close :dashboard}}
    :cart
    {:initial :browsing
     :on      {:close :dashboard}
     :states
     {:browsing  {:on {:checkout :paying}}
      :paying    {:on {:success   :confirmed
                       :failure   :browsing}}
      :confirmed {}}}}}}}
```

| Event | Source path | Target path | Notes |
|---|---|---|---|
| `:login` | `[:unauthenticated]` | `[:authenticated :dashboard]` | Target `[:authenticated]` cascades `:initial :dashboard`. |
| `:open-cart` | `[:authenticated :dashboard]` | `[:authenticated :cart :browsing]` | Keyword `:cart` resolves as sibling of `:dashboard`; cascades `:initial :browsing`. |
| `:checkout` | `[:authenticated :cart :browsing]` | `[:authenticated :cart :paying]` | Keyword `:paying` is sibling of `:browsing` inside `:cart`. |
| `:logout` | `[:authenticated :cart :paying]` | `[:unauthenticated]` | Deepest-wins walks `:paying` (no match), `:cart` (no match), `:authenticated` (match). Vector target is absolute. Exit cascade: `:paying` → `:cart` → `:authenticated`. |

For the `:logout` row, the LCA of `[:authenticated :cart :paying]` and `[:unauthenticated]` is the root, so the exit cascade runs every level of the source path; the entry cascade enters just `:unauthenticated`.

### Capability scope

Hierarchical compound states are claimed by the v1 CLJS reference per [§Capability matrix](#capability-matrix). What hierarchy gives you here:

- Nested `:states` and `:initial` cascading.
- Vector and keyword `:target` forms.
- LCA-based entry/exit cascading.
- Deepest-wins transition resolution with parent fallthrough.

Out of scope of *this section* — see the cross-reference for each:

- **Parallel regions** — first-class capability per [§Parallel regions](#parallel-regions); the N-machines-per-region substitute in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) remains the right answer when regions are independent features.
- **History pseudo-states** — first-class capability per [§History states](#history-states-type-history--shallow--deep--default-target); a `:type :history` node under a compound's `:states` re-enters the compound's last-active configuration (shallow / deep / default-target).
- **Compound-state `onDone` (`done.state.<compoundId>`)** — **first-class capability** per [§Final states §The done-state signal](#the-done-state-signal). A compound reaching its `:final?` child raises a transitionable in-machine `done.state.<compound>` an enclosing `:on-done` (on the compound) or an ancestor's `:on {:rf.machine/done …}` takes **while the machine keeps running** — the canonical "sub-flow completes → advance the outer flow" pattern. There is no hand-rolled `[:raise …]`-from-`:entry` substitute — use the first-class done-state signal. See [§Final states §The done-state signal](#the-done-state-signal) and the [§Final states §Embedded vs top-level](#embedded-vs-top-level--the-d7-reconciliation) reconciliation.

`:always`, `:after`, and `:spawn` are all specified independently of the hierarchy work above (see [§Eventless `:always` transitions](#eventless-always-transitions), [§Delayed `:after` transitions](#delayed-after-transitions), and [§Declarative `:spawn`](#declarative-spawn)). All three are state-node keys whose semantics compose with the hierarchical entry/exit cascade described above.

## Parallel regions

A machine may declare `:type :parallel` at the root and a `:regions` map keyed by region name. Each region is a **full state-tree** (its own `:initial`, `:states`, optional `:on` / `:tags` / `:after` / `:spawn` / `:always` on each state node). All regions are active simultaneously when the machine is active; the snapshot's `:state` is a **map** of region-name → that region's keyword-or-vector-path; transitions are **broadcast** across regions (every region's active state-node independently decides whether the event matches one of its `:on` keys); the run-to-completion macrostep drain settles every region before the snapshot commits.

xstate/SCXML term: **parallel state** / `<parallel>`. The motivating use case is **orthogonal axes of one feature** — one form with three independent axes (data cardinality / form validity / display mode), one widget with display + interaction state, one page whose render-mode is a function of three independent inputs. Parallel regions avoid the AND-state combinatorial explosion: three axes of three states each shrink from `3^3 = 27` flat states to nine states across three regions.

```clojure
(rf/reg-machine :ui/nine-states
  {:type    :parallel
   :data    {:items [] :error nil}                            ;; shared across all regions
   :guards  {:empty? (fn [{d :data}] (zero? (count (:items d))))}
   :actions {:bump   (fn [{d :data}] {:data (update d :count inc)})}
   :regions
   {:data
    {:initial :nothing
     :states  {:nothing  {:tags #{:data/idle} :on {:fetch :loading}}
               :loading  {:tags #{:data/loading :data/transient}
                          :on   {:loaded :resolving :failed :error}}
               :resolving {:always [{:guard :empty? :target :empty} {:target :some}]}
               :empty    {:tags #{:data/empty}}
               :some     {:tags #{:data/some}}
               :error    {:tags #{:data/error}}}}
    :form
    {:initial :neutral
     :states  {:neutral   {:tags #{:form/neutral}   :on {:submit-invalid :incorrect
                                                          :submit-valid   :correct}}
               :incorrect {:tags #{:form/invalid}   :on {:edit :neutral}}
               :correct   {:tags #{:form/success}   :on {:edit :neutral}}}}
    :mode
    {:initial :active
     :states  {:active {:tags #{:mode/active}   :on {:archive :done}}
               :done   {:tags #{:mode/done :mode/terminal}}}}}})
```

After the machine has settled at every region's `:initial`, the snapshot is:

```clojure
{:state {:data :nothing :form :neutral :mode :active}
 :data  {:items [] :error nil :count 0}
 :tags  #{:data/idle :form/neutral :mode/active}}
```

### When to reach for parallel regions

Parallel regions are for the **multi-axis-of-one-domain** case: one form with three orthogonal axes (data / form / mode), one connection with auth + lifecycle + request-queue, one widget with display + interaction. They share a single `:data` blob because the axes share a domain — the data the regions read and write is the *same data*, just sliced differently by each region's state.

If your axes are conceptually separate features (multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event), you don't want parallel regions — you want **N separate machines** colocated in app-db. See [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) for the N-machine pattern and worked example. Per §9.4 (Shared `:data` lock), per-region `:data` is **not** supported; if your axes need encapsulated `:data`, that's the substrate telling you to register N machines, not retrofit per-region data into one parallel machine.

### Snapshot shape

The snapshot's `:state` becomes the **third arm** described in [§Snapshot shape](#snapshot-shape) — a map of region-name → that region's keyword-or-vector-path:

```clojure
;; flat region — the value is a keyword
{:state {:data :loading :form :neutral :mode :active} ...}

;; compound region — the value is a vector path INSIDE that region
{:state {:auth [:authenticated :dashboard] :lifecycle :idle} ...}
```

Nested parallel regions (a region whose own state-tree declares `:type :parallel`) are not supported in v1. The validator rejects them at registration with `:rf.error/machine-parallel-nested-not-supported`. Two-level nesting can be modelled as a flatter cross-product or, more idiomatically, as multiple top-level parallel-region machines. This is a **deferred** (not permanently-blessed) divergence with a recorded reconsideration trigger — see [§Lessons from xstate — Three non-substrate divergences, item 2](#three-non-substrate-divergences--ruled-and-recorded).

The `:data` slot is **shared** across every region — there is no `:data` slot on a region body, and there is no per-region `:data` slot inside the snapshot. Region states see and write the same `:data` map; the action-effect contract is unchanged (`(fn [{:keys [data event]}] {:data {...}})`).

### Initial state

The initial snapshot's `:state` is the map of region-name → that region's initial cascade. Each region's `:initial` is required (just like a top-level flat machine's `:initial`); a region body whose own root is a compound state cascades through that region's `:initial` chain (per [§Initial-state cascading](#initial-state-cascading)). Each region's `:entry` cascade runs once at machine boot, and each region's birth `:always` settles independently as part of the initial macrostep (per [§When creation happens](#when-creation-happens--eager-start-vs-lazy-first-event)) — a region-emitted birth `:raise` re-broadcasts through the parent's one internal-event queue, exactly as during an event-driven macrostep.

```clojure
;; given the :ui/nine-states example above:
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state {:data :nothing :form :neutral :mode :active}
;;     :data  {:items [] :error nil}
;;     :tags  #{:data/idle :form/neutral :mode/active}}
```

### Transition broadcast

Every event delivered to a parallel-region machine is **broadcast** to every region. The broadcast is **select-then-apply** (two-phase — XState v5 / SCXML parity, verified against xstate@5.32.0): the enabled-transition **SELECTION** for every region is computed against **one frozen pre-broadcast snapshot** of the configuration / `:tags` (each region resolves the event through its own active state's deepest-wins lookup per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough), reading siblings' states via the *frozen* `:all-state` / `:tags` — see [§Cross-region coordination](#cross-region-coordination--tags-as-statein)); then the selected transitions are **APPLIED** in region-declaration order. Declaration order governs **only** the deterministic apply ordering (action / `:fx` order, `:data` accumulation), **never** which sibling transitions are selected — reordering the regions yields the **same** selected set. A parallel macrostep computes the new configuration **atomically** old→new: there is no intermediate configuration that some regions observe and others do not.

`:data` is the **one** value that flows during the apply phase: the selected transitions are applied in region-declaration order against the shared `:data` (so each region's action sees the prior region's `:data` writes — matching XState v5 `assign` accumulation). The frozen `:all-state` / `:tags` a guard **or action** reads do **not** flow (statechart atomicity); only `:data` does.

Three outcomes per region:

- **Region's state has a matching `:on` entry whose guard passes.** That region transitions: exit cascade → action → entry cascade. `:fx` accumulated by the region's actions joins the macrostep's `:fx` vector.
- **Region's state has no matching `:on` entry.** That region's `:state` is unchanged. No `:rf.machine.event/unhandled-no-op` fires for the region alone *unless every region declines the event* (see below).
- **Region's matching `:on` entry has a guard that returns false.** Same as "no match" — region stays put, no per-region trace fires.

If **every region** declines the event (no region matched a transition), the parallel **root's own `:on`** is consulted as the **ancestor fallback** (see [§Root parallel `:on` — the ancestor fallback](#root-parallel-on--the-ancestor-fallback) below). Only when the root `:on` *also* declines does the machine emit the benign `:rf.machine.event/unhandled-no-op` trace exactly once, matching the flat-machine semantics (an unhandled event is a no-op, not an error — xstate-v5 parity, see [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)). If **any** region handled the event, the snapshot commits with that region's transition applied, the root `:on` is **suppressed**, and no no-op trace fires.

The post-broadcast snapshot's `:state` is the map of region-name → that region's new state value. Regions that didn't transition keep their prior value in place.

### Root parallel `:on` — the ancestor fallback

A transition declared on the **`:type :parallel` root itself** — the root's own `:on` — is the **ancestor fallback** for its regions: deepest-wins with parent fallthrough, the parallel analog of the flat / compound machine-root `:on` fallback (per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough) steps 6-7) and of re-frame2's own compound-root `:on` fallthrough. This is the **XState v5 / SCXML gold standard** — a transition on a `<parallel>` node can target one or multiple of its regions ("Multiple transitions in parallel states", Stately/XState docs) — verified against xstate@5.32.0.

> **A first-class capability, not a silent drop.** A user-written root-level `:on` on a `:type :parallel` machine is validated and executed as the ancestor fallback, giving the v5 coordination affordance. Every compound root — including the parallel root — honours its `:on` as the ancestor fallback.

**Selection — atomic ancestor fallback (the load-bearing semantic):**

1. The root parallel transition is selected **ONLY when no region-local transition was selected** for the event. If **any** region handled the event, the root transition is **SUPPRESSED ENTIRELY** — atomic, all-or-nothing; it is *not* "fire the root for the regions that did not handle it". The root `:on`'s guard is evaluated against the **frozen pre-event snapshot** (the same two-phase frozen-selection model region guards use — see [§Cross-region coordination](#cross-region-coordination--tags-as-statein)).
2. When selected, the root transition runs its `:action` **once** against the shared `:data`, then **atomically** updates one or more region-qualified targets, leaving **UNtargeted** regions unchanged. A moved region then settles its own `:always` + `:raise`s through the same parent internal-event queue (per [§`:raise` is the exception](#per-region-always--after--spawn-scoping)).

**Target grammar.** A root `:on` transition's `:target` is one of:

- **targetless / action-only** — runs the `:action` / `:fx`, moves no region;
- a **single region-qualified target** `[<region> & <in-region-path>]` — a vector whose head is a declared region name, the rest the in-region path (`[:a :two]` moves region `:a` to its `:two`; `[:a :compound :leaf]` to a compound region's leaf);
- **multiple region-qualified targets** `[[<region> …] [<region> …]]` — the XState `target ['.a.x', '.b.y']` analog (`[[:a :x] [:b :y]]`).

Registration rejects a bare-keyword target, or a target whose head is not a declared region, with `:rf.error/machine-parallel-root-on-bad-target` (a root-only parallel machine has no flat sibling state to land a non-region-qualified target on). The root `:on` transition's `:guard` / `:action` refs are validated at registration like any other transition slot.

**Verified v5 cases** (xstate@5.32.0):

| event | region `:on` for event? | result | rule |
|---|---|---|---|
| `GO` (`{:go-all {:target [[:a :two] [:b :two]]}}` at root; no region declares `:go-all`) | no | `{a:two, b:two}` | no region handled → root fires, moves the targeted regions |
| `ONE` (`{:one {:target [:a :two]}}` at root) | no | `{a:two, b:one}` | root targets one region; the other is untargeted → unchanged |
| `GO` (root targets both; region `:a` handles `:go` locally) | `:a` yes | `{a:two, b:one}` | the **deeper** region transition wins; the root is **suppressed entirely** (NOT merged) |
| `RESET` (root `{:reset {:target [[:a :initial] [:b :resting]]}}`; region `:a` has `:on {:reset :special}`) | `:a` yes | `{a:special, b:resting}` | **competing-multi-region suppression** — `:a` competing suppresses the WHOLE root; `:b` stays UNCHANGED |

The last row is the **non-decomposable** case — the definitive parity fixture. A broadcast decomposition (region `:a`: `reset → special`; region `:b`: `reset → initial`) would fire both *independently* → `{a:special, b:initial}`. The atomic ancestor-fallback suppression gives `{a:special, b:resting}` (`:b` unchanged): a per-region `:on` has no cross-region suppression, so "this multi-region default fires UNLESS any region competes, in which case it is atomically suppressed" is a real capability per-region transitions cannot express.

**Root-level `:after` — the timer-driven ancestor fallback.** A `:type :parallel` root MAY declare its own `:after`. It is the **timer-driven analog of the root `:on`** ancestor fallback: a **root-owned** delayed transition. Per the XState v5 gold standard (`after` may be declared at *any* level, including a `<parallel>` node), and aligning a genuine semantic divergence (item 3 — ruled ALIGN): the region-`:after`-that-`:raise`s workaround is **semantically weaker** because its timer is bound to an *arbitrary region's* lifecycle (cancelled / restarted by that region's own transitions through the region's per-region `:after` epoch), whereas a root `:after` is **owned by the root** — scheduled when the parallel root is entered (machine birth), alive for the *whole* machine, and stale-gated by the root's *own* per-path epoch.

Semantics:

- **Scheduled at machine birth** — when the parallel root is entered, each root `:after` entry schedules its timer exactly as a state's `:after` does, emitting the same `:rf.machine.timer/scheduled` trace and `:rf.machine/after-schedule` fx. The root's per-path epoch lives at the flat snapshot slot `[:data :rf/after-epoch []]` (decl-path `[]` — the root is not a region).
- **Region-qualified targets — the same grammar as root `:on`.** A root `:after` `:target` is **targetless / action-only** (runs the `:action` / `:fx`, moves no region), a **single region-qualified target** `[<region> & <in-region-path>]`, or **multiple** `[[<region> …] [<region> …]]`. It runs its `:action` once against the shared `:data`, atomically moves the targeted region(s), and **leaves untargeted regions unchanged** — identical to the root `:on` apply path. A non-region-qualified target is rejected at registration with the **same** `:rf.error/machine-parallel-root-on-bad-target` keyword.
- **Cancel-on-exit via the root epoch.** A fired-or-stale root `:after` rides the same per-path epoch stale-gate every `:after` uses: a timer carrying a stale epoch (the root was torn down, or its epoch advanced) **drops** (emits `:rf.machine.timer/stale-after`); a guard that returns false **fires-and-discards** (`:rf.machine.timer/fired` with `:fired? false`); a live match **fires** (`:rf.machine.timer/fired` with `:fired? true`) and applies its transition. The root is the topmost node and never exits during normal operation, so the root `:after` behaves as a one-shot machine-lifetime timeout (the epoch backstops correctness on teardown).

### Cross-region coordination — tags as `stateIn`

xstate/SCXML term: **`stateIn(stateValue)`** (XState v5, from `xstate/guards`; v4 `in: '#someState'`) / the **`In(stateID)`** predicate (W3C SCXML B.1). This is the canonical orthogonal-region coordination primitive: one region's transition guard predicates on a **sibling** region's active state — region `:checkout`'s `:submit` guarded by "the `:form` region is in `:valid`" — without coupling the regions through shared `:data`.

re-frame2 ships the same **behaviour** without a separate `stateIn` primitive (behavioural parity, not API mimicry; see the *No combinator data form* note in [§Guards](#guards)). When a guard or action runs **inside a region** of a parallel machine, its context map carries two extra cross-region keys alongside the usual `:data` / `:event` / `:state` / `:meta`:

| ctx key | value | use |
|---|---|---|
| **`:tags`** | the **machine-wide** active-configuration tag union — every active state's `:tags` across **every** region (per [§Tags compose across regions](#tags-compose-across-regions)) | the **coarse** `stateIn` substitute: a sibling region advertises a state-tag, and any region's guard reads `(contains? (:tags ctx) :form/valid)`. This is **the** documented `stateIn` equivalent (the more general / more idiomatic mechanism — a tag is a *named* render-state, exactly the Nine States idiom). |
| **`:all-state`** | the full region-name → active-state map (`{:form :valid :checkout :idle}`) | the **precise** sibling-state read: `(= :valid (:form (:all-state ctx)))` matches a sibling region's discrete state value directly, the literal analog of `stateIn({form: 'valid'})`. |

Both keys reflect the **frozen pre-broadcast snapshot** — the configuration / tag union *as of the start of the macrostep* (or, across the FIFO `:raise` re-broadcast, the start of the current microstep). This is the **select-then-apply** model (per [§Transition broadcast](#transition-broadcast) — XState v5 / SCXML parity): every region's guard **and action** sees the **same** frozen sibling config, so the enabled-transition selection is **declaration-order-independent**. A region's guard does **not** see a sibling's **same-event** transition during selection — neither region sees the other's same-macrostep effect on `:all-state` / `:tags` (regions are genuinely simultaneous). A region reading its **own** current state uses `:state` (which *does* evolve through its own `:always` microstep loop); `:all-state` / `:tags` are the mechanism for reading **siblings**, and they are frozen.

> **Read-timing reversal, capability preserved.** An earlier engine resolved `:all-state` / `:tags` against the *evolving* macrostep snapshot — a sibling that transitioned earlier in declaration order within the same broadcast was visible to a later region's guard. That made the **selected** transition set depend on the **declaration order of orthogonal regions**, which breaks the defining property of parallel regions (simultaneity / independence) and diverges from XState v5 / SCXML, where a parallel macrostep selects against the pre-event configuration. The reversal **flips the read-timing to frozen pre-broadcast** for both guards and actions, but **keeps the earlier engine's capability fully intact**: a region guard / action still reads a sibling's state via `:all-state` (precise) / `:tags` (coarse) — only the snapshot those keys resolve against changed.

**Same-event cross-region coordination retimes off the frozen selection pass.** Under the frozen model a region whose decision depends on a sibling's **same-event** transition no longer fires on that same event during the selection pass. Two statechart-idiomatic paths re-couple them, differing in *when* convergence lands:

- **`:raise` — converges in the SAME macrostep (next microstep).** The sibling's transition action `:raise`s an internal event; that event re-enters the parent's single internal-event queue and is re-broadcast FIFO across every region (per [§`:raise` is the exception](#per-region-always--after--spawn-scoping)), re-selecting against the now-updated config (a fresh frozen view) — so the dependent region's `:on` resolves the raised event on the **next microstep, inside the one atomic macrostep**. This is the **in-macrostep** convergence path.
- **Guarded `:always` reading a sibling — converges on the NEXT EVENT.** A region carries `{:always {:target … :guard <reads sibling :all-state/:tags>}}`. A *plain* sibling transition does **not** itself trigger an in-macrostep cross-region re-broadcast (only `:raise` does), so the dependent region's `:always` is not re-evaluated against the sibling's same-event move within that macrostep; it re-selects against the **committed** config on the **next event delivered** (any event re-broadcasts, re-evaluating every region's `:always`). It fires — **not never** — just one event later. For strictly same-macrostep coupling, prefer the `:raise` path.

Both are bounded by the existing `:always-depth-limit` / `:raise-depth-limit` (default 16) — no infinite-loop risk — and reads of **prior-microstep / prior-event** sibling state are unaffected.

These two keys appear **only** for region guards/actions. A **flat / compound** machine's guard/action ctx is exactly `{:data :event :state :meta}` — unchanged — because its `stateIn` substitute is its own `:state` (a flat machine has no siblings to coordinate with). The implementation keys the cross-region keys off the parallel-region marker, so flat/compound machines are untouched.

#### Worked example — `:submit` guarded on a sibling region's validity

```clojure
;; The xstate `stateIn({form: 'valid'})` example, expressed as re-frame2's
;; tag substitute: :checkout's :submit fires only when the :form region is
;; in :valid (which advertises :form/valid in the machine-wide tag union).
(rf/reg-machine :ui/checkout
  {:type    :parallel
   :data    {}
   :guards  {:form-valid? (fn [{:keys [tags]}]
                            (contains? tags :form/valid))}
   :regions
   {:form     {:initial :editing
               :states  {:editing {:tags #{:form/editing} :on {:complete :valid}}
                         :valid   {:tags #{:form/valid}}}}
    :checkout {:initial :idle
               :states  {:idle       {:on {:submit {:target :submitting
                                                    :guard  :form-valid?}}}
                         :submitting {:tags #{:checkout/submitting}}}}}})

(rf/dispatch-sync [:ui/checkout [:submit]])
;; :form is still :editing → :form/valid is NOT in the union → :submit BLOCKED.
;; snapshot :state stays {:form :editing :checkout :idle}

(rf/dispatch-sync [:ui/checkout [:complete]])   ; :form → :valid (tag :form/valid)
(rf/dispatch-sync [:ui/checkout [:submit]])
;; :checkout's guard now reads :form/valid in (:tags ctx) → :submit FIRES.
;; snapshot :state → {:form :valid :checkout :submitting}
```

The precise variant reads the sibling's state value directly:

```clojure
:guards {:form-is-valid? (fn [{:keys [all-state]}] (= :valid (:form all-state)))}
```

Both styles ship; prefer the **tag** query — a tag is a named, visualiser-/AI-legible render-state, and it survives a sibling region refactoring its internal state names as long as the tag stays put.

> **Same-event coordination — the convergence paths.** The two `dispatch-sync` calls above are **separate events**, so each `:submit` selection reads the prior committed config — exactly right under the frozen model. What does **not** happen is one event flipping `:form` to `:valid` *and* `:checkout` to `:submitting` **in the same macrostep via the sibling read during selection**: a single event broadcasts to both regions against the **frozen** pre-event config, so `:checkout`'s guard sees `:form` still `:editing` and `:submit` does **not** fire that pass (declaration order is irrelevant — the result is the same whether `:form` or `:checkout` is declared first). To re-couple them, use one of the two statechart-idiomatic paths (per [§Cross-region coordination](#cross-region-coordination--tags-as-statein)) — they differ in *when* convergence lands.
>
> ```clojure
> ;; (a) :raise — converges in the SAME macrostep (next microstep). :form's
> ;;     :complete raises an internal event; the FIFO re-broadcast re-selects
> ;;     against the now-:valid :form and :checkout's :on fires next microstep,
> ;;     all inside the one atomic macrostep.
> :form {:initial :editing
>        :states  {:editing {:on {:complete {:target :valid
>                                            :action (fn [{:keys [data]}]
>                                                      {:data data
>                                                       :fx   [[:raise [:form-valid]]]})}}}
>                  :valid   {:tags #{:form/valid}}}}
> :checkout {:initial :idle
>            :states  {:idle       {:on {:form-valid {:target :submitting
>                                                     :guard  :form-valid?}}}
>                      :submitting {:tags #{:checkout/submitting}}}}
>
> ;; (b) guarded :always reading the sibling — converges on the NEXT EVENT. A
> ;;     plain :form transition does NOT trigger an in-macrostep cross-region
> ;;     re-broadcast, so :checkout's :always re-selects against the COMMITTED
> ;;     :form/valid on the next event delivered (it fires — not never — one
> ;;     event later). Prefer (a) for strictly same-macrostep coupling.
> :checkout {:initial :idle
>            :states  {:idle       {:always {:target :submitting :guard :form-valid?}}
>                      :submitting {:tags #{:checkout/submitting}}}}
> ```
>
> Both paths are bounded by the `:always-depth-limit` / `:raise-depth-limit` (16).

### Per-region `:always` / `:after` / `:spawn` scoping

Each region's state-node keys (`:always`, `:after`, `:spawn`, `:entry`, `:exit`) operate **scoped to that region**:

- **`:always`** — the macrostep's microstep loop runs **per region**. After a region's event-driven transition, that region's new state's `:always` entries are checked; matching guards fire transitions in that region. Other regions are not re-evaluated for `:always` on a sibling region's microstep; their own `:always` checks fire when that region itself transitions. Each region's microstep cascade settles to its own fixed point before commit.
- **`:after`** — an `:after` timer is scheduled / cancelled when **its region's state** entry / exit fires. One region's timer firing dispatches `[:rf.machine.timer/after-elapsed delay-key epoch]` back into the parent; the broadcast routes the synthetic event to every region; the bearing region picks it up via `pick-after-transition` (per [§Delayed `:after` transitions](#delayed-after-transitions)); sibling regions decline the synthetic event and stay put.
- **`:spawn`** — a region's `:spawn`-bearing state spawns / destroys actors **bound to that region's state**. The runtime-owned tracking slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` (per [§Declarative `:spawn`](#declarative-spawn)) uses an `:rf/invoke-id` that prefixes the region name onto the in-region prefix-path. Sibling regions never see the spawn / destroy cascade.
- **`:entry` / `:exit`** — fire on the region's own transitions, never on a sibling region's transitions.

**`:raise` is the exception — it is NOT region-scoped; it BROADCASTS.** A `:raise` emitted by any region's action does **not** stay local to that region. It re-enters the **parent parallel macrostep's single internal-event queue** and is re-broadcast across **every** region — exactly as XState v5 / SCXML deliver an internal (`raise`d / `<raise>`d) event to the *whole* machine, including every active parallel state. Each re-broadcast is its own **microstep**: selection is against the configuration *as of the start of that microstep* — a **fresh frozen** `:all-state` / `:tags` view (per [§Cross-region coordination](#cross-region-coordination--tags-as-statein)) that already reflects the **prior** microstep's committed region moves, while the in-flight **`:data`** the raise just wrote flows through. So a region's raise reaches its **siblings** (a sibling's `:on` / guard can resolve it against the in-flight `:data` the raise just wrote, and against the prior-microstep sibling config in the frozen `:all-state` / `:tags`), the **originating** region re-sees it too, and the queue drains **FIFO** (per the *FIFO for `:raise`* bullet in [§Why these rules](#why-these-rules)): raises surfaced earlier re-broadcast before raises surfaced later, and a raise emitted *while handling* a re-broadcast goes to the back. This is the spec-faithful behaviour: [§Desugaring rules](#desugaring-rules) pins `[:raise [:event]] ≡ [:fx [[:dispatch [<self-id> [:event]]]]]` *with pre-commit atomic semantics*, and for a parallel machine a self-`[:dispatch …]` broadcasts across regions (§Transition broadcast above) — so a region's `:raise` re-broadcasts the same way, but pre-commit and inside the one macrostep. Each re-broadcast still runs each region's own event-transition **and** that region's region-local `:always` settling (the `:always` bullet above is unaffected); the whole macrostep — external event + every re-broadcast internal event + every region's `:always` microsteps — commits **once, atomically**, and is bounded by the parent `:raise-depth-limit` (default 16; on exceed the *whole* macrostep rolls back, snapshot uncommitted, `:no-recovery`).

### Tags compose across regions

A parallel-region machine's `:tags` slot on the snapshot is the **union of every active state's `:tags`** across every active region. Tag union (per [§State tags](#state-tags)) extends naturally:

- For each region, walk the region's active configuration (root → leaf for compound regions; the single state for flat regions); union every active state-node's `:tags`.
- Across regions, union the per-region results.

```clojure
;; given the example above, after settling the initial state:
;; - region :data is at :nothing, which carries #{:data/idle}
;; - region :form is at :neutral, which carries #{:form/neutral}
;; - region :mode is at :active, which carries #{:mode/active}
;; → snapshot's :tags is #{:data/idle :form/neutral :mode/active}
```

The framework sub `:rf/machine-has-tag?` (per [§Querying tags](#querying-tags--the-rfmachine-has-tag-sub)) works unchanged — it asks "does the union contain this tag?" and the answer is yes iff any active state-node across any region declared the tag.

### Worked example — broadcast, shared `:data`, tags compose

```clojure
;; Both regions react to :reset; the action lives in the parent's :actions
;; map and is referenced from each region's :reset transition.
(rf/reg-machine :ui/example
  {:type    :parallel
   :data    {:count 0}
   :actions {:bump-count (fn [{d :data}] {:data (update d :count inc)})}
   :regions
   {:left
    {:initial :a
     :states  {:a {:tags #{:left/a} :on {:reset {:target :a :action :bump-count}}}}}
    :right
    {:initial :x
     :states  {:x {:tags #{:right/x} :on {:reset {:target :x :action :bump-count}}}}}}})

;; Initial snapshot:
;; {:state {:left :a :right :x} :data {:count 0} :tags #{:left/a :right/x}}

(rf/dispatch-sync [:ui/example [:reset]])
;; Both regions handle :reset (self-transition + :bump-count). The action
;; runs ONCE PER REGION against the shared :data — :count goes 0 → 1 → 2.
;; Snapshot after the macrostep:
;; {:state {:left :a :right :x} :data {:count 2} :tags #{:left/a :right/x}}
```

The action is run by each region that handles the event; shared `:data` flows through each region's actions sequentially. If you want an event to count once, register a coordinating action at the parent-machine level rather than per-region, or set up the regions so only one handles the event.

### Capability gating

Parallel regions are claimed as `:fsm/parallel-regions` in the v1 CLJS reference per [§Capability matrix](#capability-matrix). Ports that don't claim it raise `:rf.error/machine-grammar-not-in-v1` on `:type :parallel` at registration time. The schema extension (`:rf/state-node` gaining `:type` + `:regions`; `:rf/machine-snapshot`'s `:state` widened to the third arm) is documented in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot).

### Substitutes — when to use N machines instead

As noted in [§When to reach for parallel regions](#when-to-reach-for-parallel-regions), parallel regions are the right answer when the regions are orthogonal axes of one feature with one shared `:data`. The N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — separate `[:rf.runtime/machines :snapshots <id>]` entries coordinated via cross-actor dispatch — is the right answer when the regions are conceptually independent features that don't share data. Both patterns ship together; choose by domain shape.

### Trace events

Parallel-region transitions emit one `:rf.machine/transition` macrostep trace per dispatched event (matching flat / compound machines). The trace's `:before` and `:after` payloads carry the full snapshot (including the `:state` map shape). The internal per-region transitions and their microsteps surface through the per-region `:rf.machine.microstep/transition` events (per [§Eventless `:always` transitions §Trace events](#trace-events_1)); each carries a `:region` tag identifying which region produced the microstep so consumers can subscribe to per-region streams.

### Cross-references

- [§Snapshot shape](#snapshot-shape) — the three-arm `:state` form.
- [§State tags](#state-tags) — tag union extends across regions.
- [§Cross-region coordination — tags as `stateIn`](#cross-region-coordination--tags-as-statein) — the `:tags` / `:all-state` ctx keys threaded into region guards/actions (the XState v5 `stateIn` / SCXML `In()` substitute).
- [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — the N-machines-per-region pattern for the independent-features case.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — `:type` + `:regions` schema.
- [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) — `:state` widened.
- [Pattern-NineStates](Pattern-NineStates.md) — the motivating pattern.
- [conformance/fixtures/parallel-flat-two-regions.edn](conformance/fixtures/parallel-flat-two-regions.edn),
  [parallel-compound-region.edn](conformance/fixtures/parallel-compound-region.edn),
  [parallel-tags-union-across-regions.edn](conformance/fixtures/parallel-tags-union-across-regions.edn),
  [parallel-broadcast-event-both-regions.edn](conformance/fixtures/parallel-broadcast-event-both-regions.edn),
  [parallel-spawn-scoped-to-region.edn](conformance/fixtures/parallel-spawn-scoped-to-region.edn),
  [parallel-after-scoped-to-region.edn](conformance/fixtures/parallel-after-scoped-to-region.edn),
  [parallel-always-cascade-per-region.edn](conformance/fixtures/parallel-always-cascade-per-region.edn),
  [parallel-initial-state-per-region.edn](conformance/fixtures/parallel-initial-state-per-region.edn),
  [parallel-snapshot-round-trip.edn](conformance/fixtures/parallel-snapshot-round-trip.edn),
  [parallel-ssr-hydration.edn](conformance/fixtures/parallel-ssr-hydration.edn) — conformance fixtures.

## Eventless `:always` transitions

An `:always` transition fires automatically when its guard becomes true — no event needed. xstate/SCXML term: **transient** or **eventless** transition. The pattern handles "the snapshot just changed; if condition X is now true, immediately move to state Y" without the author having to manually `:raise` a synthetic event from every action that could enable the condition.

`:always` is a **state-node key** (alongside `:on`, `:entry`, `:exit`, `:spawn`) holding a vector of guarded transition specs. Checked **after entry** (or after any transition that lands in this state). First matching guard wins; subsequent entries in the vector are not evaluated.

```clojure
{:checking-form
 {:always [{:guard :form-valid?   :target :submitting}
           {:guard :form-invalid? :target :show-errors}]
  :on     {...}}}
```

### Microstep loop within drain

`:always` extends Level 3 of [§Drain semantics](#drain-semantics) with a microstep cascade. The cascade is **one unified loop** that, after the resolving transition, prefers eventless (`:always`) transitions and dequeues a raised internal event only when no `:always` is enabled (XState v5 / SCXML §3.13). Within a single machine event:

1. Apply the resolving transition (action + target); its raises seed the internal-event queue.
2. **Check `:always` of the current state.** If a guarded entry matches (first-match-wins), apply that transition (action + target), accumulate its `:fx`, append any events it raises to the **back** of the queue, and loop back to step 2.
3. **Only when no `:always` is enabled, dequeue one raise** (**FIFO** — front of the queue), handle it (it settles its own `:always` before returning), append any events it raises to the **back** of the queue, and loop back to step 2.
4. **Fixed point reached** when no `:always` entry matches AND the internal-event queue is empty. Commit the snapshot.
5. Emit accumulated `:fx`.

The whole cascade — initial transition, every `:always` microstep, every dequeued raise — commits **once, atomically**. External observers see only the final settled state. This is xstate/SCXML **macrostep** semantics: the externally-observable transition is the fixed point of the microstep loop.

**The initial macrostep is also such an event.** Steps 2–5 above are the *settle* phase, and they run at **machine birth** too: after the initial-entry cascade enters the initial configuration, the runtime runs the same unified `:always` + raise settle loop **before the birth commit**, so a transient initial leaf whose `:always` guard already holds is settled past — unobserved — on start (per [§When creation happens](#when-creation-happens--eager-start-vs-lazy-first-event), on both the eager `[:rf.machine/start]` and lazy first-event paths). Birth and event-driven transitions share one implementation of this settle phase; only step 1's *seed* differs (the entry cascade at birth, the resolving transition for an event).

### Order with `:raise`

Within the macrostep loop, **`:always` settles before the next raise is dequeued** — XState v5 / SCXML §3.13 `selectEventlessTransitions` runs every iteration, and only when *no* eventless transition is enabled does the processor pop one internal (raised) event. So a transition that raises `R` while entering a state whose `:always` is enabled takes the `:always` **first** and handles `R` in the **post-`:always`** state. The combined macrostep is the fixed point of this `(prefer :always, else dequeue one raise)` loop.

> **`:always` settles before the next raise is dequeued.** Verified against xstate@5.32.0, a raised event whose target also carries an enabled `:always` is handled in the **post-`:always`** state, not the transient state the raise's seed transition entered. This is XState v5 / SCXML parity.

`:raise` semantics within a single transition are otherwise unchanged. **FIFO among raised events is preserved**: once dequeued, raises drain front-to-back; a raised event is handled (settling its own `:always`) and only *then* is the next pending raise dequeued; a raise's own raises — and an `:always` step's own raises — go to the **back** of the queue, behind the still-pending siblings (XState v5 / SCXML internal-event-queue parity). See the *FIFO for `:raise`* bullet in [§Why these rules](#why-these-rules).

Conformance: [conformance/fixtures/always-settles-before-raise.edn](conformance/fixtures/always-settles-before-raise.edn) pins the XState v5 ordering — an action raises `R` while entering a state with an enabled `:always`; the final state is the post-`:always` raise target, with `R` handled after the `:always` transition.

### Bounded depth

Default microstep depth limit: **16** (matching `:raise`-depth's default). User-configurable at frame-config level (`:always-depth-limit`). Exceeding the limit:

- Emits `:rf.error/machine-always-depth-exceeded` with `:tags {:machine-id <id> :depth <limit> :path [<state> <state> ...]}`.
- Halts the cascade with the snapshot **uncommitted** — external observers do not see the partial path.
- Recovery: `:no-recovery` (the runtime cannot guess the author's intent for a non-converging cycle).

**A tripped depth limit is a FAILED macrostep, not a benign no-op (XState v5 parity).** XState v5 **throws** on a non-converging eventless / `raise` cycle; re-frame2 surfaces it as a **failed** macrostep — the abort routes through the **same** failure path a thrown action takes (the handler short-circuits to `{}`; no snapshot write reaches runtime-db, which **is** the atomic rollback). It does **not** emit the benign `:rf.machine.event/unhandled-no-op` an unhandled / guard-blocked event emits, so a runaway cycle is **distinguishable** from a guard-blocked decline rather than silently swallowing the triggering event. The `:rf.error/machine-always-depth-exceeded` (or `:rf.error/machine-raise-depth-exceeded`) error trace is the single signal for the trip.

The depth counter is **separate** from the `:raise` depth counter — a microstep that itself raises events does not double-count. The two limits compose: each microstep can raise up to 16 events, and the macrostep can include up to 16 microsteps.

### Hierarchy interaction

When the cascade enters a **compound state**, `:always` is checked at every entered level, **deepest-first**. This matches xstate/SCXML and the existing entry-cascade order ([§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)) — the leaf has the most specific knowledge of its own validity, so it gets first chance to redirect.

A match at any level resolves the microstep and the loop returns to step 2.

### Self-loop forbidden at registration

A state whose `:always` declares a `:target` that resolves to itself — guarded or not — is **rejected at registration time**:

```clojure
{:checking
 {:always [{:guard :ready? :target :checking}]}}    ;; rejected — guarded or not
```

`make-machine-handler` walks every `:always` entry at construction time and surfaces `:rf.error/machine-always-self-loop` with `:tags {:state <state-keyword> :machine-id <id>}` for any entry whose `:target` resolves to the declaring state itself (a keyword target equal to the state's own key, the `:same-state` sentinel, or a vector target equal to the state's own path). Rationale: an eventless `:always` that re-targets the state it just re-entered re-evaluates the same guard — it either fires repeatedly to depth-exceeded (if the guard remains true) or is a no-op (if the guard flips on the first hit); in both cases the author intended something else. This matches xstate/SCXML eventless-transition guidance: a *declared* target must differ from the current state node. Catch the typo at registration; surface the topology bug.

The rejection is decidable at registration purely from the entry's `:target` (registration cannot know whether a guard's truth would change between re-entries — the "self-target with a guard that will flip" case is undecidable, so a guarded self-`:target` is rejected alongside the unguarded one). A genuine "re-enter on a changed condition" need is expressed by targeting a **distinct** intermediate state (or by `:after` for a re-arming timer), not by a self-`:target`.

The canonical fixed-point / re-evaluate-until-condition loop is a **targetless guarded `:always`** with an `:action` — `{:guard :more? :action :bump}` (per [§What `:always` is *not*](#what-always-is-not)). An **internal** `:always` (no `:target`, only an `:action`) is NOT a self-loop and is permitted: its action flips the guard false and the microstep loop settles. When an `:always` *does* declare a `:target`, that target MUST differ from the declaring state node; only an explicit self-`:target` is rejected.

### `:source` classification — `:always`

`:always` microsteps run intra-macrostep and do NOT produce their own dispatch envelope — the resolving event's envelope is the only `:rf/dispatch-envelope` for the whole macrostep (per [§Microstep loop within drain](#microstep-loop-within-drain)). However, the per-microstep `:rf.machine.microstep/transition` trace (see [§Trace events](#trace-events_1)) carries `:source :always` so tools can filter "show only `:always`-driven microsteps" without needing to disambiguate from `:on`-driven transitions. The closed-set value `:always` is reserved on `:rf/dispatch-envelope`'s `:source` for forward consistency with the vocabulary (per [Spec-Schemas §`:rf/dispatch-envelope`](Spec-Schemas.md#rfdispatch-envelope)) — should a future revision lift `:always` to a substrate-dispatched event (today it's a pure microstep), the value already names the trigger correctly.

### Trace events

The runtime emits trace events at **two levels**, so tools can subscribe at the granularity they need:

- **Per-microstep** `:rf.machine.microstep/transition` — one event per microstep with `:tags {:actor-id <live-instance-id> :from <state> :to <state> :microstep-index <n> :source :always}` (a microstep belongs to a running actor's macrostep, addressed by the live INSTANCE `:actor-id`; `:machine-id` is the registered TYPE). Tools that want to see the inner cascade (visualisers, debuggers) consume these. The `:source :always` tag is stamped so the trigger kind is uniform with the dispatch-envelope vocabulary.
- **Outer macrostep** `:rf.machine/transition` — the existing event, augmented with `:tags { ... :microsteps <count>}` carrying the total number of microsteps in the macrostep. Tools that want only externally-observable transitions (UI inspectors, replay panels) consume this and ignore the per-microstep stream.

Both levels are emitted unconditionally; consumers filter.

### Guard references

Guards in `:always` resolve against the **machine's `:guards` map** (per [§Registration](#registration--the-machine-is-the-event-handler) and the machine-scoped lock per [§Resolved decisions](#globally-registered-guardsactions-vs-machine-scoped-resolved)). There is no separate registry, no global lookup. `make-machine-handler` walks every `:always` entry's `:guard` slot at registration time and verifies the keyword resolves; misses surface as `:rf.error/machine-unresolved-guard` exactly as for `:on` transitions.

### Worked example — quiz

```clojure
{:initial :asking
 :guards  {:enough-correct? (fn [{data :data}] (>= (:correct-count data) 10))}
 :actions {:count-correct   (fn [{d :data}] {:data {:correct-count (inc (:correct-count d))}})
           :count-wrong     (fn [{d :data}] {:data {:wrong-count (inc (:wrong-count d))}})}
 :states
 {:asking
  {:always [{:guard :enough-correct? :target :winner}]
   :on    {:answer-correct  {:action :count-correct}
           :answer-wrong    {:action :count-wrong :target :loser}}}
  :winner {...}
  :loser  {...}}}
```

Walkthrough: when the user dispatches `[:quiz [:answer-correct]]`, the machine's macrostep runs:

1. `:asking`'s `:answer-correct` transition fires; `:count-correct` increments `:correct-count` (no `:target`, internal transition — the snapshot stays at `:asking`).
2. Microstep check: `:asking`'s `:always` evaluates `:enough-correct?`. If `:correct-count` is now ≥ 10, the guard is true; the microstep transitions to `:winner`.
3. Fixed point: `:winner`'s `:always` (if any) is checked; assume it has none. Commit.
4. Trace surface: one outer `:rf.machine/transition` (`:asking` → `:winner`, `:microsteps 1`) plus one per-microstep `:rf.machine.microstep/transition`.

External observers see `:asking` → `:winner`. The "answer counted, still asking" intermediate state is invisible — exactly the property `:always` exists to provide.

### What `:always` is *not*

- **Not a mid-transition slot.** `:always` lives only on a state node (alongside `:on`, `:entry`, `:exit`); it is not a key inside a transition spec. The microstep loop is the cascade mechanism — there is no "always after this action."
- **Not on the root machine.** `:always` is a state-node key; the root has `:initial` as its cascade entry-point. (A root-level "fire as soon as the machine starts" need is met by `:initial` cascading into a leaf whose `:always` fires — and that fire happens **at birth**, as part of the initial macrostep's eventless settle, *before* the birth commit, on both the eager `[:rf.machine/start]` and lazy first-event paths. See [§When creation happens](#when-creation-happens--eager-start-vs-lazy-first-event) and [§Microstep loop within drain](#microstep-loop-within-drain).)
- **Not allowed as a self-targeting `:always`** (see above) — registration error.
- **Not a substitute for `:after`.** `:after` is for **time-delayed** transitions; `:always` fires immediately on guard truth. They are independent capabilities; see [§Delayed `:after` transitions](#delayed-after-transitions) for the full delayed-transition semantics. Both can co-exist on the same state node — they are independent slots.

## `:type :choice` (transient / choice states)

A **choice state** is an immediate routing node: enter it, evaluate guarded candidates in declaration order, take the first whose `:guard` passes, and leave — all within the same macrostep, with **no event needed**. xstate/SCXML term: a **transient** (eventless) state. It is named-intent grammar for a decision node — sugar over encoding the same routing as an ordinary state with an `:always` slot.

```clojure
{:checking
 {:type   :choice
  :choice [{:guard :valid? :target :accepted}
           {:target :rejected}]}}      ;; unguarded final = the default / else branch
```

The `:choice` value is a **declarative guarded-candidate array** — the same first-guard-pass-wins candidate form a normal `:on` / `:after` / `:always` slot takes (`{:target … :guard … :action …}` maps). The last candidate is conventionally an unguarded **default / else** branch so the choice state always resolves (see [§Choice registration rules](#choice-registration-rules) below).

### Distinct intent, ONE mechanism — desugars to `:always`

A choice state **is** an immediate eventless routing node, which is exactly what [§Eventless `:always`](#eventless-always-transitions) already implements: on entry (or any transition that lands in the state) the runtime walks the candidate vector, takes the first guard-pass, and settles it within the macrostep drain. So `:type :choice` / `:choice` **desugars** — at registration / transition normalisation time — into an ordinary state carrying the same candidate vector under `:always`, dropping the `:type :choice` / `:choice` keys. The whole `:always` machinery drives it unchanged: the candidate walk, the first-guard-pass select, the macrostep microstep loop, and the **birth-time settle** (so a transient *initial* choice leaf resolves on start, never externally observed — per [§Microstep loop within drain](#microstep-loop-within-drain)). The grammar is distinct; the runtime is reused. This mirrors how [§`:timeout` / `:on-timeout`](#timeout--on-timeout-state--spawn) desugars onto `:after`: a named-intent authoring concept lowering onto an existing mechanism, ONE mechanism per fact.

`:always` can already express immediate routing; `:choice` **names the intent** so diagrams and tools render a decision node and validation gives a clearer grammar.

### DIVERGENCE from XState — a declarative array, NOT a `choice`-function

XState v6 lets a `choice` state's routing be a `choice`-**function**. re-frame2 **rejects** that: a function may never be the edge. re-frame2's `:choice` is a **declarative guarded-candidate array** `[{:guard … :target …}]` — the edge topology stays **data** so diagrams, model tests, conformance fixtures, and AI tools can read it. A function-valued `:choice` (`:choice (fn …)`) **fails loud at registration** with `:rf.error/machine-bad-choice`. This is behavioural parity (an immediate routing node) without API-mimicry (the JavaScript function form).

### Choice registration rules

`make-machine-handler` validates the choice grammar on the **raw** (pre-desugar) spec so diagnostics name the `:type :choice` / `:choice` keys the author wrote. Each rule fails loud at registration:

- **`:type :choice` requires `:choice`** (and a `:choice` slot requires `:type :choice`) — one without the other is meaningless (`:rf.error/machine-choice-missing-choice` / `:rf.error/machine-choice-without-type`).
- **The `:choice` value must be a non-empty vector of candidate maps** — a fn (the rejected XState form), a keyword, an empty vector, or any other shape fails with `:rf.error/machine-bad-choice`.
- **A choice state must not also declare ordinary waiting-state behaviour** — `:entry`, `:exit`, `:on`, `:always`, `:after`, `:timeout`, `:on-timeout`, `:spawn`, `:spawn-all`, `:initial`, `:states`, `:final?`, `:output-key` — a choice state **only routes** (`:rf.error/machine-choice-extra-keys`). (A choice state therefore cannot carry a `:timeout`; the two named-intent grammars never combine on one node.)
- **The `:choice` vector must include an unguarded default / else candidate** (a candidate with no `:guard`) so the choice state always resolves. Without one the choice state could be entered with every guard failing and **no candidate to take** — a stuck transient node. This is the static "no matching candidate + no default" rejection; guards are runtime fns, so the only registration-time guarantee that a choice always resolves is a present default (`:rf.error/machine-choice-no-default`).
- **A choice candidate that targets the choice state's own declaring state is a self-loop** — an immediate eventless cycle — rejected exactly as an `:always` self-loop is (`:rf.error/machine-choice-self-loop`).

The desugared `:always` candidates' targets flow through the **same** target-resolution check every transition slot uses, so a `:choice` candidate naming a non-existent state surfaces `:rf.error/machine-unresolved-target` like any other malformed target.

### Worked example

```clojure
{:initial :idle
 :data    {}
 :guards  {:valid? (fn [{:keys [data]}] (boolean (:form-ok? data)))}
 :states
 {:idle     {:on {:submit :checking}}
  :checking {:type   :choice
             :choice [{:guard :valid? :target :accepted}
                      {:target :rejected}]}     ;; default / else
  :accepted {}
  :rejected {}}}
```

Dispatching `[:my-machine [:submit]]` enters `:checking`, which resolves **immediately** within the same macrostep: if `:valid?` passes the machine settles at `:accepted`, otherwise it falls through to the default `:rejected`. External observers never see `:checking` — it is a transient routing node, exactly the property `:choice` exists to provide. A choice state may also be the machine's **initial** state, in which case it resolves on birth (the initial macrostep's eventless settle).

## Public / private `:internal-events`

An **internal event** is an event the machine **raises and handles entirely within its own run-to-completion logic** — machine plumbing that is *not* part of the machine's public event surface. Views, tests, and other handlers must not be able to drive the machine by dispatching one. A machine-level declaration covers them:

```clojure
{:internal-events #{:tick :retry/internal}
 :actions {:arm (fn [_] {:fx [[:raise [:tick]]]})}
 :states
 {:waiting
  {:entry :arm                        ;; entry action raises :tick internally…
   :on    {:tick {:target :checking}}}}}   ;; …and it is handled by an ordinary :on clause
```

An internal `:raise` (`[:raise [:tick]]` from an `:entry` / `:exit` / transition action) may **produce** `:tick`; the raised event re-enters the macrostep's internal-event queue and runs **normal transition selection** against the `:on` map (per [§Order with `:raise`](#order-with-raise)). The `:on {:tick …}` clause is **how** the machine handles the raised event — that is the expected shape, *not* a collision. What `:internal-events` **adds** over a plain `:on` clause is the public / private **boundary**: the same name, dispatched from **outside**, is refused.

### DIVERGENCE from XState — a Clojure set, NOT an array

XState v6's `internalEvents` is an **array**. re-frame2 declares them as a Clojure **set** — `:internal-events #{:tick}` — a re-frame2 idiom: a set is the natural membership collection (the runtime boundary check is one `contains?`), order is irrelevant, and a duplicate is structurally impossible. This is alpha-drift-confirmed against XState v6 alpha.3 (no grammar drift vs the EP basis). Behavioural parity (a private event surface) without API-mimicry (the JavaScript array form).

### The boundary is enforced at the machine dispatch boundary

The visibility rule is enforced at the **machine dispatch boundary** — the handler entry, where an event arrives via `reg-event` dispatch (`(rf/dispatch [:my-machine [:tick]])`). An internal `:raise` is drained **inside** the macrostep through the FIFO internal-event queue (per [§Drain semantics](#drain-semantics)); it **never** re-enters via `reg-event` dispatch. So the only events reaching the handler entry are **external** dispatches — and a routed inner event whose head is a declared internal event is **refused** there: the handler emits `:rf.error/machine-internal-event-external-dispatch` and short-circuits with **no state change** (atomic — the committed snapshot is untouched, exactly the benign no-op shape an [unhandled event](#transition-resolution--deepest-wins-with-parent-fallthrough) takes). A self-raised internal event handled within the machine's own plumbing is unaffected; only the outside caller is refused. The refusal is a per-handler no-op (not a drain-halt), so the surrounding epoch outcome stays `:ok`.

This separates the **public** machine API from **private** machine plumbing: other code cannot accidentally depend on events meant only for the machine's own run-to-completion logic.

### Registration validation (fail-loud)

`make-machine-handler` validates the declaration at registration so a misdeclaration is caught **before** the runtime ever drives the machine:

- **`:internal-events` must be a set of keywords** — a **vector** (the rejected XState array form), a non-set collection, or a set with a non-keyword member fails with `:rf.error/machine-bad-internal-events`.
- **No declared internal event may be a reserved `:rf/*` framework event** — the synthetic creation marker `:rf.machine/start`, the `:rf.machine/done` completion signal, `:rf.machine.timer/after-elapsed`, the `:rf.machine.spawn/*` family, etc. Those are framework-owned **public** lifecycle traffic threaded through the standard dispatch pipeline (per [§Reserved synthetic events](#reserved-synthetic-events) and [Conventions §The single-root reserved set](Conventions.md#the-single-root-reserved-set)); a framework event can never be a machine's private event (`:rf.error/machine-internal-event-reserved`).

Note that declaring `:tick` internal **and** handling it via `:on {:tick …}` is the *expected* shape (the example above), not a collision — an `:on` clause is the handler for the raised event; `:internal-events` only adds the external-dispatch refusal.

### Worked example

```clojure
{:initial :waiting
 :internal-events #{:tick}
 :actions {:arm (fn [_] {:fx [[:raise [:tick]]]})}   ;; raise the private event…
 :states
 {:waiting {:on {:go {:target :armed :action :arm}}}
  :armed   {:on {:tick {:target :checking}}}          ;; …handled internally → :checking
  :checking {}}}
```

Dispatching `[:my-machine [:go]]` runs `:arm`, which **raises** `:tick`; the raised event drains inside the macrostep and drives `:armed → :checking`. But dispatching `[:my-machine [:tick]]` directly — from a view or a test — is **refused** at the boundary: the machine does not transition, and `:rf.error/machine-internal-event-external-dispatch` fires. The private event is reachable only by the machine itself.

## State tags

A state node may declare `:tags <set-of-keywords>`. At every transition, the runtime walks the active configuration, unions every active state-node's tag set, and stamps the result onto the snapshot at `:tags`. A framework sub asks the predicate question — "does this machine's snapshot carry this tag?" — without enumerating every nested path that contributes to the answer.

The motivating use case is the **Nine States pattern** ([Pattern-NineStates.md](Pattern-NineStates.md)): a page-level convention whose render decisions slice across three orthogonal axes (data cardinality, form validity, mode). Tags carry the per-axis intent (`:data/loading`, `:form/invalid`, `:mode/done`) so view-level subs can ask query-shaped questions without inventing N boolean discriminator subs per state.

```clojure
{:initial :editing
 :states
 {:editing  {:tags #{:active :editable}
             :on   {:archive :archived}}
  :archived {:tags #{:done :read-only :terminal}}}}
```

After entering `:archived`, the snapshot looks like:

```clojure
{:state :archived
 :data  {<...>}
 :tags  #{:done :read-only :terminal}}
```

### Semantic contract

- `:tags` is a **set of keywords** on a state node. Order doesn't matter; duplicates collapse. The implementation tolerates the obvious alternative shapes (a vector or single keyword) by coercing to a set; the canonical form is `[:set :keyword]` per [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node).
- The runtime computes `(apply set/union (map :tags active-state-nodes))` at every transition commit (after `:always` microsteps reach fixed point, before the macrostep's `:rf.machine/transition` trace fires — so traces carry the new tag set) and stores the result at `[:rf.runtime/machines :snapshots <id> :tags]` on the snapshot.
  - For a **flat** machine the active set is the single named state.
  - For a **compound** machine it's every state along the active path (root → every compound ancestor → leaf). This matches XState/SCXML's "any state in the active configuration carrying the tag is enough."
- `:tags` is **read-only** for users. Actions cannot return `:tags` in their effect map; the runtime owns the slot. It is a pure projection of `:state` — set the state, the tags follow.
- The framework reserves the `:rf/*` and `:rf.*/*` keyword namespaces (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); user-declared `:tags` must not use those prefixes. Any unreserved namespace is fair game, including dotted forms like `:ui.state/loading`.

### Snapshot shape change

Strictly additive — the `:rf/machine-snapshot` schema (see [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) gains one **optional** key:

```clojure
{:state <fsm-keyword-or-path>
 :data  <map>
 :tags  #{<keyword>, ...}            ;; NEW — derived at commit; optional
 :meta  {<...>}}
```

When the active configuration's tag union is **empty** (no active state declares `:tags`), the runtime **elides** the `:tags` key entirely. Pre-tag machines are byte-identical to post-tag machines that declare no tags — no snapshot grew, no print/read round-trip changed shape, no downstream reader has to special-case anything.

Implementations may also legally carry `:tags #{}` instead of eliding; both shapes are conformant. The CLJS reference elides — that's the optimisation [conformance fixture `tags-empty-when-no-declaration`](conformance/fixtures/tags-empty-when-no-declaration.edn) asserts.

### Querying tags — the `:rf/machine-has-tag?` sub

The framework ships one sub:

```clojure
;; framework-shipped — registered alongside :rf/machine in the machines ns
(reg-sub :rf/machine-has-tag?
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf.runtime/machines :snapshots machine-id :tags]) tag)))
```

User call site — the canonical predicate is the subscription vector:

```clojure
;; predicate
@(rf/subscribe [:rf/machine-has-tag? :ui/nine-states :data/loading])
;; => true | false
```

Reading the whole tag set is the normal snapshot read:

```clojure
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state ... :data ... :tags #{:data/loading :form/neutral :mode/active}}
```

The sub is **derived** — it reads the snapshot via `get-in` rather than chaining off `:rf/machine` — so a view that only cares about whether a specific tag is present re-renders only when the containment-bit flips, not on every tag-set change. Reagent's built-in equality dedup gates the boolean return.

### Compatibility

Strictly additive. Machines that declare no `:tags` keys produce snapshots without a `:tags` slot; existing views, subs, and traces don't care. The `:rf/machine-snapshot` schema's `{:optional true}` covers the migration. No existing public name collides — `:tags` is distinct in the state-node grammar from the `:meta` slot, which carries state-level tooling-visible metadata; per-state `:meta` is independently allowed and not synonymous with `:tags`.

Print/read survives: `:tags` is `#{<keyword>}` — a set of keywords; both halves are EDN-printable and EDN-readable. The Tool-Pair epoch buffer and SSR hydration paths handle `:tags` automatically because they round-trip the snapshot as opaque data.

### Tags on states only — not transitions

`:tags` is a state-node slot, not a transition-spec slot. Transitions don't carry tags — the question "is this transition tagged" is already answered by the existing trace-event vocabulary (`:source`, `:op-type`). Adding transition tags later is non-breaking; tags are not on transitions.

### What tags are *not*

- **Not an autonomous transition-driver.** A tag flipping on does not, by itself, *fire* a transition — there is no "on this tag appearing" trigger. A transition still needs an event or an eventless `:always` step to fire; if you need a state change to follow a `:data` condition autonomously, the canonical mechanism is an `:always` transition guarded on `:data`. **Guards CAN read the tag set, though** — for a parallel region's guard the machine-wide `:tags` union is in the context map as the cross-region `stateIn` substitute (see [§Cross-region coordination — tags as `stateIn`](#cross-region-coordination--tags-as-statein)), so a guard *can* predicate on a sibling region's render-state. The distinction: tags are a guard **input** (a sibling region advertises one, this region's guard reads it on the next event/`:always`), not a guard **trigger**.
- **Not a `:meta` synonym.** Per-state `:meta` (the long-standing tooling-visible slot, e.g. `{:terminal? true}`) lives alongside `:tags` and is independently queryable via `(machine-meta id)`. Tags are about **runtime active-configuration projection**; `:meta` is about **static state-node metadata**.
- **Not user-writable on the snapshot.** Actions can't return `:tags` in their `{:data :fx}` effect map; the slot is runtime-owned.
- **Not a substitute for `:rf/machine`.** Views that need the whole snapshot still subscribe to `:rf/machine`; `:rf/machine-has-tag?` is for the predicate-shaped query. Both are first-class.

### Worked example — read the page's render state in one tag-query

A view that wants "render the loading spinner whenever any data-loading state is active" today writes:

```clojure
(rf/reg-sub :ui.state/loading?
  :<- [:todos/status]
  (fn [status _] (= status :loading)))

(when @(rf/subscribe [:ui.state/loading?])
  [view-loading])
```

That works for the flat-status case; it doesn't scale to "loading, OR validating, OR retrying" without adding three more subs. With tags:

```clojure
{:loading     {:tags #{:data/loading} :on {...}}
 :validating  {:tags #{:data/loading} :on {...}}
 :retrying    {:tags #{:data/loading :data/retry} :on {...}}}

(when @(rf/subscribe [:rf/machine-has-tag? :todos/editor :data/loading])
  [view-loading])
```

The view doesn't enumerate the three states. New `:loading`-flavoured states added later carry the tag automatically; the view picks them up at no cost.

### Trace events

`:tags` is recomputed on every `:rf.machine/transition` and fires under the same trace event — there's no separate `:rf.machine.tag/changed` trace. The committed snapshot's `:tags` slot is visible in the existing trace's `:after` payload; observers that care about tag changes compare `(:tags (:before tr))` against `(:tags (:after tr))`.

If a future use case wants per-tag granular tracing, a `:rf.machine.tag/changed` trace event can be added additively without breaking the read pattern above.

### Capability gating

`:tags` is **`:fsm/tags`** in the capability matrix (per [§Capability matrix](#capability-matrix)) — claimed by the v1 CLJS reference. Ports that don't claim it raise `:rf.error/machine-grammar-not-in-v1` on `:tags` at registration time.

### Cross-references

- [Pattern-NineStates.md](Pattern-NineStates.md) — the motivating pattern.
- [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) — snapshot-schema extension.
- [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) — state-node-schema extension.
- [§Capability matrix](#capability-matrix) — `:fsm/tags` row.
- [conformance/fixtures/tags-flat-machine.edn](conformance/fixtures/tags-flat-machine.edn),
  [tags-compound-active-path-union.edn](conformance/fixtures/tags-compound-active-path-union.edn),
  [tags-empty-when-no-declaration.edn](conformance/fixtures/tags-empty-when-no-declaration.edn),
  [tags-round-trip-pr-str.edn](conformance/fixtures/tags-round-trip-pr-str.edn) — conformance fixtures.

## Delayed `:after` transitions

An `:after` transition fires after a specified time delay, no event needed. xstate/SCXML term: **delayed transition**. The pattern handles "after N milliseconds in this state, time out" without the author having to wire a `:dispatch-later` from `:entry` and a matching `:cancel-dispatch-later` on every other transition out of the state.

`:after` is a **state-node key** (alongside `:on`, `:entry`, `:exit`, `:always`, `:spawn`) holding a map of `ms → transition-spec`. Each entry runs an **independent** timer; on expiry, the corresponding transition fires (subject to its `:guard`). Entering the state schedules every entry's timer; exiting the state advances an epoch counter so in-flight timers from the prior visit are detected as stale and silently ignored.

```clojure
{:loading
 {:after {5000  :timeout
          30000 {:guard :still-loading? :target :hard-error}}
  :on    {:loaded :ready
          :failed :error}}}
```

### Value shape

Each `:after` map entry is `<delay> → <transition-spec>`. Both halves admit multiple forms.

**Delay (the key) — three forms:**

- **`pos-int?`** — literal milliseconds, computed at registration time. The default form for fixed timeouts (`{30000 :timeout}` — fire after 30 seconds).
- **Subscription vector** — `[<sub-id> & <args>]` resolved through the same machinery as `subscribe`. Canonical for **app-state-derived delays**: the delay reads from a flow / sub whose value reflects user preferences, feature-flag config, or any other `app-db`-derived setting. Re-resolves on subscription change (see [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)). Example: `{[:sub :timeout-config :auth] :timeout}` reads the auth-phase timeout from a registered sub.
- **`(fn [{:keys [snapshot]}] ms)`** — fn-valued delay, called once at state entry against the entering snapshot via the unified context-map. Returns a `pos-int?` ms value. The escape valve for delays computed from local machine `:data` (the snapshot is `{:state :data :meta?}`); `:data` is the only source of dynamic input that the subscription form cannot reach without a subscription wrapper. Example: `{(fn [{:keys [snapshot]}] (* 1000 (:retry-count (:data snapshot)))) :retry}`.

The subscription form is the **canonical** answer for "the delay should track an app-level configuration"; the fn form is the **local** answer for "the delay depends on this machine's own `:data`." Literal `pos-int?` covers the common case where the delay is a constant.

**Transition spec (the value) — three forms, identical to an `:on` clause:**

The `:after` value is resolved through the **same** value-form grammar as an `:on` clause (per [§Transitions](#transitions)) — the runtime normalises both through one shared candidate-walk, so the two slots can never drift apart.

- **`:keyword`** — sugar for `{:target <keyword>}`. The simple "fire after N ms; transition to state X" case.
- **`:rf/transition` map** — full transition spec with the same shape as an `:on` slot: `{:guard <guard-ref> :target <target> :action <action-ref> :meta <map>}`. Guards resolve **machine-locally** against the spec's `:guards` map, exactly as for `:on` and `:always`. If `:guard` is present and evaluates false at timer expiry, the transition is **suppressed** (the timer is treated as "fired and discarded; no transition") and the runtime emits a `:rf.machine.timer/fired` trace with `:fired? false`; the snapshot is unchanged and **other in-flight `:after` timers continue running** (per [§Multi-stage interaction with `:guard`](#multi-stage-interaction-with-guard)). The slot shape — `:guard`, `:target`, `:action`, `:meta` — matches the canonical `:rf/transition` shape used by `:on` (per [§Transitions](#transitions)) and `:always`.
- **guarded candidate-vector** — `[{:guard g1 :target s1 :action a1} {:guard g2 :target s2} … {:target sN :action aN}]`, a vector of `:rf/transition` maps resolved **first-guard-pass-wins** at timer expiry, **exactly** as an `:on` clause's multiple-candidate form. The candidates are walked in declaration order; the first whose `:guard` passes fires (running its `:action` and routing to its `:target`); an unguarded candidate is the **unconditional fallback** that ends the list. If **every** candidate's guard fails and there is no unguarded fallback, the firing is **guard-suppressed** — no transition, no epoch advance, the `:rf.machine.timer/fired` trace carries `:fired? false`, and sibling `:after` timers continue (per [§Multi-stage interaction with `:guard`](#multi-stage-interaction-with-guard)). This is the canonical "checkpoint with escalation/fallback at the same deadline" shape — e.g. `{6000 [{:guard :handshake-ok? :target :connected} {:target :failed :action :record-error}]}` connects iff the handshake completed by the 6 s deadline, otherwise records the error and fails.

Sugar normalises at registration time: `{5000 :timeout}` is equivalent to `{5000 {:target :timeout}}`, and a single map is the one-element case of the candidate-vector. The runtime sees the desugared form.

```clojure
;; Three delay forms in one state node:
{:loading
 {:after {30000                        {:target :timeout :guard :no-progress?}     ;; literal ms
          [:sub :timeout-config :slow] {:target :warn :action :log-slow}            ;; subscription
          (fn [{:keys [snapshot]}] (* 1000 (-> snapshot :data :retry-count)))
                                       :retry}                                      ;; local fn
  :on    {:loaded :ready
          :failed :error}}}
```

### Wall-clock from state entry

Each `:after` timer counts from the moment the machine **enters the state** (the `:entry`-cascade-final timestamp captured by the runtime at commit time). If the state has multiple `:after` entries, **each timer counts independently from the same entry-time** — a state with `{:after {5000 :warn 30000 :timeout}}` schedules both timers concurrently at entry; the 5000 ms timer is not chained off the 30000 ms timer.

Re-entering the same state restarts every `:after` timer from the new entry-time — the prior visit's in-flight timers go stale via the epoch advance ([§Epoch-based stale detection](#epoch-based-stale-detection)); the new visit's timers are scheduled fresh. There is no preserved "elapsed-so-far" across state re-entry: `:after` is **per-state-entry** semantics, not per-state-occupancy. A *re-entry* means an actual `:exit`/`:entry` boundary fired: an **external** (`:reenter? true`) self / ancestor transition, or a parent-cascade that re-enters the leaf. A self / ancestor `:target` **without** `:reenter?` is [internal](#self-transitions--internal-default-vs-external-reenter) — no `:exit`/`:entry`, so the `:after` epoch does **not** advance and the running timers are **left alone** (the v5 default).

### Whichever fires first wins

A state may have multiple in-flight transition triggers concurrently:

- **Multiple `:after` timers** — every entry in the `:after` map is its own independent timer.
- **`:on <event>` transitions** — any user-dispatched event the state's `:on` map handles.
- **`:always` transitions** — guards that may newly become true after an action commits.
- **`:spawn`'s child completion** — the spawned child dispatching back into the parent.

**Whichever fires first causes the transition; the others are cancelled** as part of the standard exit cascade. The mechanism:

1. The first trigger to dequeue at the parent's handler (timer expiry, user dispatch, child dispatch, `:always` microstep) drives the transition.
2. The transition's exit cascade runs (per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)).
3. As part of the exit cascade, the runtime advances the exited node's per-path `:rf/after-epoch` entry — every other in-flight `:after` timer from the just-exited state goes stale on its eventual firing.
4. Any `:spawn`-spawned child is destroyed via `:rf.machine/destroy` (the desugared `:exit` action). Per the [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) contract, in-flight `:rf.http/managed` requests inside the destroyed child cascade to abort — `:after` firing is one trigger of the same cancellation cascade as a parent-destroys-child shutdown.
5. User-dispatched events queued for the just-exited state but not yet drained are processed by the now-current state's `:on` map (which may handle them, route to `:*` wildcard, or — when no level matches — resolve as a benign `:rf.machine.event/unhandled-no-op`).

The cancellation cascade is **uniform across triggers** — the runtime does not distinguish "the timer fired" from "the user dispatched" from "the child completed" at the cascade level; each is just an event at the parent's handler boundary that resolves to a transition out of the state. The `:rf.machine.timer/stale-after` traces ([§Trace events](#trace-events)) are how observers see "this `:after` was racing and lost."

### Dynamic delay re-resolution

A subscription-vector delay (`[:sub-id & args]`) is **re-resolved** when its underlying subscription value changes:

1. **At state entry**, the runtime resolves the subscription, captures the current ms value, and schedules a timer for that delay.
2. **While the timer is in flight**, the runtime watches the subscription. If its value changes (a new `app-db` value flows through the sub) the runtime:
   - **Cancels the in-flight timer** (best-effort via `re-frame.interop/cancel-scheduled!`; epoch-based stale detection backstops cancellation per [§Epoch-based stale detection](#epoch-based-stale-detection)).
   - **Restarts the timer from the current moment** with the newly-resolved ms value. The window does **not** carry over elapsed-so-far; the replacement timer counts from the re-resolution time.
3. **When the timer expires**, the runtime fires the transition (subject to `:guard`).

**Why restart from the current moment, not extend/shorten the existing timer:** restart semantics is the simplest mental model and the easiest to reason about — at any moment, the timer's countdown reflects the *current* subscription value. Extending or shortening an existing timer requires the user to track elapsed-so-far, makes the wall-clock interaction non-monotonic (a timer set for 30 s could fire at 15 s if shortened, or never fire if perpetually extended), and complicates the `:rf.machine.timer/scheduled` trace stream (does the trace fire on each shortening?). Restart-from-now keeps the contract: every `:rf.machine.timer/scheduled` trace marks a fresh wall-clock window; every `:rf.machine.timer/fired` measures from the most-recent `:scheduled`.

**Stale-detection composes.** Each restart advances the scheduling node's per-path `:rf/after-epoch` entry (or a per-`:after`-entry sub-counter; implementation choice — the contract is "the prior in-flight timer is stale on firing"); the cancelled prior timer fires stale and emits `:rf.machine.timer/stale-after`.

**Trace.** A subscription-driven restart emits a paired `:rf.machine.timer/cancelled` (the prior timer cancelled by re-resolution; `:tags {:actor-id <id> :state <state> :delay <prior-ms> :epoch <e> :reason :on-resolution :rf.sub/id <sub-id> :rf.sub/query-v <subscription-vector>}`) followed by a fresh `:rf.machine.timer/scheduled` (the new timer). Tools that distinguish "the subscription changed" from "the state exited" filter on `:reason :on-resolution` vs `:reason :on-exit` on the same `/cancelled` event — every cancellation path emits the single unified event.

**Function-form delays do NOT re-resolve.** A `(fn [{:keys [snapshot]}] ms)` delay is called **once** at state entry; the snapshot's `:data` may change later but the timer does not re-evaluate. Authors who want a `:data`-derived delay that re-resolves on `:data` change use the subscription form (`[:sub :machine-data-derived-delay <machine-id>]` whose body reads from `[:rf.runtime/machines :snapshots <machine-id> :data ...]`) and pay the subscription cost; the fn form is the cheap "compute once at entry" escape valve.

**Subscription form under SSR.** Resolved at server render time (the runtime materialises the value), but **scheduling is suppressed** per [§SSR mode](#ssr-mode); the resolved ms value flows into the hydration payload as part of the snapshot's trace state but no timer fires server-side.

### Multi-stage interaction with `:guard`

When multiple `:after` entries declare `:guard`s, each timer's guard is checked **independently** at that timer's expiry:

- **Guard returns true (transition fires)** — the transition runs through the standard cascade; the exit advances the epoch; remaining in-flight `:after` timers from the just-exited state go stale on firing.
- **Guard returns false (transition suppressed)** — the runtime emits `:rf.machine.timer/fired` with `:fired? false`; the snapshot is unchanged; the state does NOT exit; **other in-flight `:after` timers from the same state continue running unchanged**. The expired-with-false-guard timer is **not re-scheduled** — the contract is "fired and discarded." If the author wants a timer that polls a guard until true, the surface is to fire a transition (sugar `{30000 :recheck-state}`) into a state whose `:always` evaluates the same guard and re-routes — `:after` itself is fire-once-per-state-entry.

Concretely: a state declaring `{:after {5000 {:guard :slow? :target :warn} 30000 {:target :timeout}}}` runs both timers concurrently from entry. At t=5s the 5000 ms timer fires; if `:slow?` returns false, the transition is suppressed; the 30000 ms timer is still in flight. At t=30s the 30000 ms timer fires unconditionally and the machine transitions to `:timeout` regardless of `:slow?`'s eventual truth. The author who writes the 5000 ms-with-guard form is opting for "if the condition is true at the 5 s checkpoint, escalate; otherwise let the longer timeout decide" — exactly the multi-stage timeout pattern.

**Per-entry candidate-vector resolution.** The two interactions above operate at the level of distinct `:after` *entries* (different delay-keys, each its own timer). A **single** `:after` entry whose value is a [guarded candidate-vector](#value-shape) resolves its candidates at **one** timer expiry, walked first-guard-pass-wins **exactly** as an `:on` clause resolves multiple candidates (per [§Transitions](#transitions)) — there is no second grammar. At expiry the runtime walks the candidate list in declaration order; the first candidate whose `:guard` passes fires (and **no** later candidate is evaluated); an unguarded candidate is the unconditional fallback. The guard-suppressed branch above applies to the candidate-vector as a whole: a firing is suppressed only when **every** candidate's guard fails **and** no unguarded fallback ends the list. This makes the "escalate-or-fallback at the same deadline" pattern a single `:after` entry — e.g. `{6000 [{:guard :handshake-ok? :target :connected} {:target :failed :action :record-error}]}` — rather than two competing entries whose relative firing order an author would otherwise have to reason about.

### No-invoke variant

A state with `:after` but no `:spawn` is a **pure timed-transition state** — the canonical shape for splash screens, animation gates, and user-prompt countdown timers. No child machine is spawned; the state's only behaviour is the timer (plus any user `:on` events).

```clojure
{:initial :splash
 :states
 {:splash {:after {3000 :main}             ;; show splash for 3 seconds
           :on    {:skip :main}}            ;; or user clicks 'skip'
  :main   {...}}}
```

The `:after` slot is **independent** of the `:spawn` slot — neither requires the other; both are state-node-level keys per [§State nodes](#state-nodes). Pure timed-transition states are the simplest `:after` use case and are exercised by the conformance fixture `after-no-invoke-splash` per [§Capability matrix](#capability-matrix).

### Epoch-based stale detection

> **Cross-cutting pattern.** This is one instance of the **stale-detection pattern** re-frame2 uses for any async-shaped feature where the receiving state's identity matters. See [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for the meta-pattern; the same idiom is used by [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression) and is the recommended default for future async-shaped substrates. Trace events follow the `<feature>/stale-<reason>` convention.
>
> **Uniform reply envelope.** This epoch gate **is** the [uniform reply envelope's stale-suppression gate](Managed-Effects.md#the-uniform-reply-envelope) for the machine `:after` timer — the declaring path + per-path epoch are the data-only suppression gate, and the `:rf.machine.timer/stale-after` trace carries the reply vocabulary additively. See [§Async completions share the uniform reply envelope](#async-completions-share-the-uniform-reply-envelope). Internal lowering only — the staleness behaviour described here is unchanged.

Re-frame2 does **not** introduce a `:cancel-dispatch-later` fx. Cancellation is unnecessary because every scheduled timer carries an **epoch** captured at scheduling time, and the receiving handler validates the epoch before firing.

The mechanism:

1. The machine handler maintains a **per-scheduling-node epoch map** in `:data` under the reserved key `:rf/after-epoch` — `{<decl-path-vector> <non-negative int>}`, each entry counting the visits to the `:after`-bearing node at that declaring path. The `:rf/`-namespace inside `:data` is reserved for runtime-managed keys; user code does not write under it.
2. **On state entry**, the handler increments that node's per-path epoch and, for each `:after` entry, schedules a `:dispatch-later` carrying the synthetic event `[<machine-id> [::after-elapsed <delay-key> <epoch> <decl-path>]]`. The exact event shape is implementation-internal; what's contractual is that the node's epoch AND its declaring path travel with the timer.
3. **On timer expiry**, the machine handler receives the synthetic event and resolves the scheduling node at the carried `<decl-path>`, comparing the carried epoch against that node's current per-path entry in `:data`.
   - **Live** — the scheduling node is still on the active path AND the carried epoch matches its per-path entry; the handler resolves the transition (subject to `:guard`) and fires it through the normal Level 3 drain.
   - **Stale** — the scheduling node has been exited (no longer on the active path), or its per-path entry advanced (a re-entry scheduled a fresh timer); the handler silently ignores it and emits a `:rf.machine.timer/stale-after` trace event with `:tags {:actor-id <id> :state <state> :delay <ms> :scheduled-epoch <e1> :current-epoch <e2>}`.
4. **On state exit** (any transition that lands the snapshot in a different state, including cross-level transitions per [§Hierarchical compound states](#hierarchical-compound-states)), only the exited (and newly-entered) `:after`-bearing nodes' per-path epochs advance. A still-active parent above the LCA keeps its entry, so its in-flight `:after` timer stays live across a child-only transition.

The epoch is tracked **per scheduling node** (its declaring state path), not as a single per-machine scalar — a single scalar could not satisfy the [§Hierarchy interaction](#hierarchy-interaction) contract, because a child sibling-transition that itself bumps the shared counter would stale a still-active parent's in-flight timer. Each node's exit advances only its own entry; re-entering the same node increments that entry again — timers from the *new* visit carry a fresh epoch while any prior in-flight timer observes the mismatch.

### Drain semantics interaction

`:after` does **not** introduce a new microstep loop. The synthetic timer-elapsed event lands in the standard runtime FIFO via `:dispatch-later`'s normal path, and when it dequeues, the machine handler treats it as an ordinary event:

1. Resolve the synthetic event to its declared transition (via the state's `:after` map, indexed by the carried delay).
2. Validate the epoch (above). On mismatch, emit `:rf.machine.timer/stale-after` and stop — no transition runs.
3. On match, evaluate `:guard` (if any). On false, the transition is suppressed and a `:rf.machine.timer/fired` trace is still emitted (with `:fired? false`); the snapshot is unchanged.
4. On match-and-guard-pass, run the transition through the standard Level 2 (exit / action / entry) and Level 3 (drain `:raise`, check `:always`) cascade. The transition exit advances the epoch; sibling `:after` timers from the just-exited state will all be stale by the time they fire.

`:after` is a **deferred event source**, not a new layer in the drain hierarchy. Per [§Drain semantics](#drain-semantics), it composes with `:raise` (which queues *before* commit) and `:dispatch` (which queues at the runtime layer) without changing their orderings: the timer-elapsed event arrives at the back of the runtime FIFO, no different from any other `:dispatch-later`.

### Hierarchy interaction

`:after` on a parent state remains active while the snapshot is in any child of that parent. Multiple `:after` timers can be in flight simultaneously across hierarchy levels — a parent's 30-second hard-timeout ticks alongside a child's 5-second progress timeout.

Per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca), the epoch counter advances on **any** state exit, whether the exit is a leaf-only transition or a multi-level cascade. A leaf-to-sibling transition under the same parent does not exit the parent, so the parent's `:after` timers stay live; a transition that exits the parent advances the epoch and all of the parent's pending `:after` timers go stale on next firing.

**Implementation note:** the epoch is tracked **per scheduling node**, not as a single per-machine scalar — `:rf/after-epoch` is a `{<decl-path-vector> <int>}` map keyed by each `:after`-bearing state's declaring path (per the [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys) table). `commit-snapshot` bumps **only** the entries for nodes the transition exits or enters; a leaf-only sibling-transition under a shared parent leaves the still-active parent's entry untouched, so the parent's pending `:after` timer stays live (its carried epoch still matches) while the just-exited leaf's entry is bumped (its timer goes stale on next firing). A single per-machine scalar could not satisfy this — any leaf transition would bump it and invalidate the parent's in-flight timer — which is exactly why the per-decl-path map is normative. The contract is *external* — "parent `:after` outlives sibling-leaf transitions; an exited node's timers go stale" — and `make-machine-handler` is responsible for upholding it via the per-node epoch map.

**Normative rule (external contract).** A parent state's `:after` timer is suspended-but-not-stale while the snapshot is in any child of the parent: leaf-only sibling transitions inside the same parent MUST NOT cause that parent's pending `:after` timer to fire as stale on its next match. Conversely, a transition whose LCA is at-or-above the parent MUST advance the epoch such that any of the parent's pending `:after` timers (from the just-exited visit) all observe a mismatch and silently drop. Implementations that cannot satisfy both clauses with a single per-machine epoch (because a leaf transition advances it) MUST track which `:after` entries belong to which level on the active path and selectively re-schedule only the levels the cascade newly enters. The per-level re-scheduling sketch above is the recommended implementation; the contract is the observable behaviour, not the implementation strategy.

### Multiple `:after` per state

All entries in an `:after` map run **independently**. Whichever timer fires first (and matches its `:guard`) triggers its transition; the resulting state exit advances the epoch and the remaining timers all go stale.

**Tie-break — the same-tick case.** The `:after` map is keyed by delay, so two entries with the *same* delay are impossible — the map keys dedupe. The only race is **two entries with _different_ delays whose host-clock callbacks land in the same scheduler tick** (the same JS macrotask, or the same JVM scheduler quantum). When that happens each fired timer is a **separate** synthetic timer-elapsed event ([§Epoch-based stale detection](#epoch-based-stale-detection)) dispatched independently; the order they reach the router is the **host scheduler's** order, not a machine-document order. There is no cross-timer arbitration step.

The observable outcome is **first-fired-wins, the rest drop stale**: the first synthetic event to dequeue at the parent's handler drives the transition, the state exit advances the scheduling node's per-path epoch, and every other same-tick timer's event then carries a now-stale epoch and silently drops (`:rf.machine.timer/stale-after`). No double-transition occurs.

This is a **divergence** from the XState v5 / SCXML *document-order conflict resolution* rule (SCXML §3.13: simultaneously-enabled transitions resolve deterministically by document order — earlier-listed wins). `:after` timers are real **host-clock deferred events**, not entries in a synchronous in-engine queue — each timer is its own epoch-bearing deferred event, and the observable outcome is *first valid timer to arrive wins; the transition makes the rest stale*. Authors must therefore **not** rely on a document-order tie-break between two same-tick `:after` timers; the deadline that elapses first (as the host clock reports it) is the one that fires.

```clojure
:loading
{:after {5000  :timeout                                    ;; first checkpoint
         30000 {:guard :still-loading? :target :hard-error}} ;; final checkpoint
 :on    {:loaded :ready
         :failed :error}}
```

If `:loaded` or `:failed` arrives before 5s, the machine transitions out of `:loading`; both timers go stale. If neither arrives by 5s, the 5000ms timer fires; the machine transitions to `:timeout`; the 30000ms timer's eventual firing is stale.

### SSR mode

`:after` **no-ops in SSR mode** — entry actions do not schedule timers, and the synthetic timer-elapsed event is never emitted. The server renders the current `:state` statically and the client hydrates that state without timer artefacts. See [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr) for the SSR-side rule.

This is consistent with `:platforms` gating on `reg-fx` (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)): timer scheduling is conceptually a `:client`-only concern. The first client render after hydration can re-fire entry actions to begin scheduling, depending on the implementation's hydration policy — the spec leaves the hydration-handoff timing to the host so long as the snapshot value is preserved.

**Machines are synchronous-only under SSR.** The synchronous machine substrate — plain transitions, `:always` microsteps, hierarchical entry/exit cascading, and `:spawn` / `:spawn-all` of children that do only **synchronous** co-effects — runs identically on the server. **Async machine work is outside the SSR allowed subset.** A `:spawn` / `:spawn-all` whose children exist primarily to drive *async* loads (`:http/post`, `:rf.http/managed`, websocket protocols, polling) is a **programmer error** on the server, not a supported shape. There is no machine-level render barrier under SSR: the only barrier the runtime installs is `drain-blocking-resources!` ([016-Resources §SSR and hydration](016-Resources.md#ssr-and-hydration)), which drains **resources only**, and `:after` (the natural deadline that might bound a fan-out) no-ops server-side per [§SSR mode](#ssr-mode) above. So a machine that awaits network I/O during the render simply hangs at its `:loading` state until the request-scoped frame is destroyed — the drain settles with the fetch still in flight (the JVM managed-HTTP transport is `sendAsync`, [014](014-HTTPRequests.md)) and the render sees the skeleton. The supported SSR fan-out-and-load shape is route-owned blocking **resources**, not a machine fan-out ([Cross-Spec-Interactions §4](Cross-Spec-Interactions.md#4-machines-under-ssr-allowed-subset); the machine-fan-out pattern is [retired](Pattern-SSR-Loaders.md)). Long-lived async child actors that DO belong on the client should be gated on the surrounding event handler running client-side, exactly as with `reg-fx :platforms`; the standard `:platforms`-style suppression at the spawn-fx layer applies rather than expecting the runtime to silently no-op the spawn. The hydration payload covers the snapshot value itself; child-actor handlers are not part of the wire shape and re-establish on the client side via the post-hydration entry replay (per [011-SSR](011-SSR.md)). The `spawn-all-under-ssr-ring` conformance fixture pins the out-of-subset symptom: a `:spawn-all` loader under `:platform :server` has its server deadline skipped and its snapshot stuck at `:loading`, never reaching `:ready`.

### Clock abstraction

> **Timer scheduling is the one ambient-clock exception.** [§Causal host facts](#causal-host-facts--rfcofx-ep-0017) forbids a guard / action from folding the *ambient* wall-clock (`(js/Date.now)`) into a durable machine *decision* — a host time that feeds `:data` or transition selection must arrive as a declared recordable coeffect (`:rf/time-ms`) so the decision replays deterministically. The `:after` scheduler below is the sanctioned exception: it reads the host clock to **schedule** a fire-and-forget timer (`schedule-after!`), not to decide anything durable. The decision the timer drives is gated **facts-against-facts** by the per-scheduling-node epoch (per [§Epoch-based stale detection](#epoch-based-stale-detection) and the `:rf/after-epoch` slot in [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys)), never by comparing wall-clocks — so timer scheduling stays replay-sound without recording the schedule instant. The synthetic timer-elapsed event that *does* reach the fold is an ordinary dispatch envelope and stamps its own `:rf/time-ms` like any other.

The clock primitives live in **`re-frame.interop`** — the existing clj/cljs-split interop layer that already houses platform-dependent atoms, `next-tick`, etc. Three primitives:

- `re-frame.interop/now-ms` — host-clock current time in milliseconds (a long).
- `re-frame.interop/schedule-after!` — host-clock `setTimeout`-equivalent. Returns an opaque handle.
- `re-frame.interop/cancel-scheduled!` — best-effort cancellation given the handle. Optional; epoch-based stale-detection makes cancellation an optimisation, not a correctness requirement.

The CLJS realisation uses `js/Date.now` and `js/setTimeout` / `js/clearTimeout`. The JVM realisation uses `System/currentTimeMillis` and a `ScheduledExecutorService`. Tests swap the interop layer using existing fixture patterns — there is **no new framework-level clock-configuration API**; the substitution happens at the namespace level (`with-redefs` in tests, alternative interop ns alias in conformance harnesses). If `:after` is exercised on a host whose interop layer hasn't been wired with a clock, the runtime emits `:rf.warning/no-clock-configured` (an advisory; the host falls back to a host-native clock if available).

> **`:rf.warning/*` vs `:rf.error/*` severity.** `:rf.warning/no-clock-configured` is the only `:rf.warning/*` emitted on a machine **runtime operation-recovery** path; the few other machine `:rf.warning/*` emits are dev-only **lints / advisories** (`:rf.warning/machine-cofx-consume-undeclared` and `:rf.warning/machine-cofx-ambient-durable` per [§Causal host facts](#causal-host-facts--rfcofx-ep-0017); `:rf.warning/on-spawn-return-ignored` per [§Recording the spawned id user-side](#recording-the-spawned-id-user-side)), not runtime operation outcomes — every *operation*-grade machine emit other than this one is `:rf.error/*`. Its recovery is `:warned-and-replaced` (fall back to the host-native clock — see [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)), distinct from the `:rf.error/*` machine emits whose recoveries are `:no-recovery`, `:replaced-with-default`, or `:logged-and-fallback` on a genuine failure path. The request completes correctly; the trace records a configuration drift the operator should address.

### `:source` classification — `:after-timer`

When an `:after` timer's delay elapses, the substrate dispatches the synthetic `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]` trigger event into the parent machine. That dispatch stamps **`:source :after-timer`** on the dispatch envelope so the Epoch panel's DISPATCH step renders "from :after timer" rather than `:unknown` (the residual default). The naming uses the spec's own term — `:after` — so the value greps back to this section. There is no separate `:rf/dispatch-origin` tag; `:source :after-timer` is the single functional-origin discriminator.

### Trace events

The runtime emits five trace events around every `:after`:

- **`:rf.machine.timer/scheduled`** — emitted when a timer is scheduled at state entry (or re-scheduled after a subscription-driven re-resolution per [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)). `:tags {:actor-id <id> :state <state> :delay <ms> :epoch <e> :delay-source <:literal | :sub | :fn> :rf.sub/id <sub-id, when :delay-source = :sub> :rf.sub/query-v <subscription-vector, same gate>}`. One event per `:after` entry per scheduling. (The timer's owning actor INSTANCE rides under `:actor-id`; `:machine-id` is reserved for the registered TYPE. The dynamic-delay subscription identity rides under the canonical `:rf.sub/id` + `:rf.sub/query-v`, not the bare `:sub-id`.)
- **`:rf.machine.timer/fired`** — emitted when a live (epoch-matching) timer's transition resolves. `:tags {:actor-id <id> :state <state> :delay <ms> :epoch <e> :fired? <bool>}`. `:fired? false` indicates the guard was checked and returned false; the transition was suppressed and other in-flight timers continue (per [§Multi-stage interaction with `:guard`](#multi-stage-interaction-with-guard)).
- **`:rf.machine.timer/stale-after`** — emitted when a stale (epoch-mismatched) timer fires. `:tags {:actor-id <id> :state <state> :delay <ms> :scheduled-epoch <e1> :current-epoch <e2>}`. The transition does not fire.
- **`:rf.machine.timer/cancelled`** — emitted on every `:after` timer cancellation, regardless of cause (one unified event id). `:tags {:actor-id <id> :state <state> :delay <prior-ms> :epoch <e> :reason :on-exit | :on-destroy | :on-resolution | :on-supersede | :on-frame-destroy | :on-restore :delay-source <:literal | :sub | :fn, when known> :rf.sub/id <sub-id, when :delay-source = :sub> :rf.sub/query-v <subscription-vector, same gate>}` (canonical subscription identity, not the bare `:sub-id`). The `:reason` closed set discriminates the six cancellation paths: `:on-exit` (state owning the `:after` was exited), `:on-destroy` (machine destroyed), `:on-resolution` (subscription-vector delay re-resolved), `:on-supersede` (a new schedule landed at an already-armed slot), `:on-frame-destroy` (the bearing frame was destroyed), `:on-restore` (epoch restore unwound the bearing epoch — the in-flight host-clock handle is released eagerly so the orphaned timer never fires against the restored state, per [Managed-Effects §SSR, preload, hydration, and restore](Managed-Effects.md#ssr-preload-hydration-and-restore)). Payload shape mirrors `:rf.machine.timer/scheduled` for arm-fire-cancel pairing by `(actor-id, state, epoch)` so the Xray Handler section's AFTER TIMERS sub-section can render scheduled→fired→cancelled rows directly from the trace stream.
- **`:rf.machine.timer/skipped-on-server`** — emitted in SSR mode when a state's `:after` entry is reached but timer scheduling is suppressed (per [§SSR mode](#ssr-mode)). `:tags {:actor-id <id> :state <state> :delay <ms>}`. Diagnostic: lets server-side tooling see which timers a real client run would have scheduled.

Tools subscribe to whichever granularity they need: `:scheduled` for timeline visualisation, `:fired` for the externally-observable transition, `:stale-after` for diagnosing "a timer should have fired but didn't" symptoms, `:cancelled` (with `:reason`) for the every cancellation cause, `:skipped-on-server` for confirming SSR no-op behaviour.

### Worked example

```clojure
{:initial :idle
 :guards  {:still-loading? (fn [{data :data}] (:loading? data))}
 :states
 {:idle    {:on {:fetch :loading}}

  :loading
  {:after {5000  :timeout
           30000 {:guard :still-loading? :target :hard-error}}
   :on    {:loaded :ready
           :failed :error}}

  :timeout    {:on {:retry :loading}}
  :hard-error {:on {:reset :idle}}
  :ready      {:on {:reset :idle}}
  :error      {:on {:reset :idle}}}}
```

Walkthrough (both timers belong to the same `[:loading]` node, so the node's per-path epoch behaves like a simple counter here). The user dispatches `[:fetch]`. The machine transitions `:idle` → `:loading`; `[:loading]`'s `:rf/after-epoch` entry advances from 0 to 1; both `:after` timers schedule with epoch 1 (`:rf.machine.timer/scheduled` × 2).

- **Path 1 — `:loaded` arrives at t=2s.** The machine transitions `:loading` → `:ready`; `[:loading]`'s epoch advances to 2. At t=5s the 5000ms timer fires; epoch carried = 1, current = 2; `:rf.machine.timer/stale-after` emits; ignored. At t=30s the 30000ms timer fires; same story.
- **Path 2 — neither arrives by t=5s.** The 5000ms timer fires; epoch matches; the transition resolves; `:rf.machine.timer/fired` emits with `:fired? true`; machine transitions `:loading` → `:timeout`; `[:loading]`'s epoch advances to 2. At t=30s the 30000ms timer fires with epoch 1; `:rf.machine.timer/stale-after` emits; ignored.
- **Path 3 — `:loaded` doesn't arrive but `:loading?` is still true at t=30s.** The 5000ms timer fired at t=5s and (suppose) the user dispatched `[:retry]` from `:timeout` at t=10s; the machine re-entered `:loading`; `[:loading]`'s epoch advanced to 3; both timers re-scheduled. The original 30000ms timer (epoch 1, scheduled at t=0) eventually fires; stale; ignored. The newly-scheduled 30000ms timer's guard `:still-loading?` is consulted at fire time.

External observers see one machine event per externally-visible transition; the timer scheduling and stale-suppression noise stays inside the trace stream.

### What `:after` does *not* include

- **Recurring timers.** `:after` fires once per state entry. For polling, the user re-enters the state (e.g., `:fetching → :waiting → :fetching` with `:waiting` carrying an `:after` that loops back).
- **Wall-clock delays.** `:after` is relative to entry time, not "fire at 9:00 AM tomorrow." Calendar-scheduled events are an application-level concern; the machine can react to a user-emitted `:dispatch-later` from outside.
- **Pause / resume.** No built-in pause; users pause by transitioning the snapshot out of the state (which makes the timers stale) and back in (which re-schedules with a fresh epoch). The `:rf/after-epoch` mechanism makes the round-trip idempotent.
- **A `:cancel-dispatch-later` fx.** The epoch mechanism replaces explicit cancellation; the runtime never needs to forget a scheduled timer, only to reject stale ones at expiry.

## `:timeout` / `:on-timeout` (state + spawn)

A state — or a `:spawn` spec — may declare a `:timeout` duration plus an `:on-timeout` transition that fires when the machine sits in that state (or its spawned child runs) past the duration. This is the `:timeout` / `:on-timeout` grammar: a named "this state / child must finish before this time" fact, distinct from the general `:after` table.

```clojure
{:states
 {:waiting
  {:timeout    "PT5S"
   :on-timeout {:target :timed-out}}

  :loading
  {:spawn {:machine-id :fetch-user
           :timeout    "PT10S"
           :on-timeout {:target :timed-out}}}}}
```

### Distinct intent, one mechanism

`:timeout` and `:after` express **different intent** and **may coexist** on the same state node:

- `:after` is the general delayed-transition table (`{ms → transition}`, possibly several entries, with literal / subscription-vector / fn delays). It answers "after N ms in this state, *consider* a transition."
- `:timeout` names the single deadline fact: "this state (or its child) must finish before this time." `:timeout` **requires** `:on-timeout`; an `:on-timeout` with no `:timeout` is meaningless.

re-frame2 keeps **ONE timer mechanism**. `:timeout` / `:on-timeout` **desugars** — at registration / transition normalisation time — into an `:after` entry keyed by the resolved-ms duration, mapping to the `:on-timeout` transition. The whole `:after` machinery (scheduling, per-decl-path `:rf/after-epoch` staleness, cancel-on-exit, the `:rf.machine.timer/*` traces) then drives it unchanged. So:

- **Entering** the state arms the timeout timer (as a desugared `:after`);
- **Leaving** the state cancels it (the standard exit cascade advances the epoch and emits `:rf.machine/after-cancel`);
- **Child completion** (for a spawn timeout) exits the spawn-bearing state, which cancels the timeout — the timer is anchored to the spawn-bearing state's entry, so it bounds the child's whole lifetime (spanning any internal retries), and when it fires the standard exit cascade tears the child down.

The grammar (separate keys, separate validation, separate duration rules) is what the **author** writes; the `:after` table is what the **engine** drives. A `:final?` state cannot declare `:timeout` (final means final — symmetric with the `:after` rule). A desugared-timeout ms must not collide with an explicit `:after` delay-key on the same node (`:rf.error/machine-timeout-after-collision`) — a silent merge would drop one of the two authored intents.

### Duration grammar — integer-ms OR ISO-8601 only

A `:timeout` / spawn-`:timeout` duration is **exactly one of**:

- a **positive integer** — literal milliseconds (`5000`);
- an **ISO-8601 duration string** — `"PT5S"`, `"PT2M"`, `"PT1H30M"`, `"PT0.5S"`, `"P1D"`, … (the `PnYnMnWnDTnHnMnS` form; case-insensitive; fractional seconds allowed; year / month use the fixed 365-day / 30-day convention).

**Divergence from XState.** XState v6 also accepts a readable shorthand such as `"10ms"` / `"5s"`. re-frame2 **REJECTS** that shorthand: a `"5s"` / `"10ms"` string (or any other malformed duration — a non-positive integer, a fn, a vector, the bare `"P"`) fails **loud** at registration with `:rf.error/machine-bad-timeout-duration`. Unlike `:after`, a `:timeout` does **not** admit subscription-vector / fn dynamic delays — a timeout is a fixed wall-clock deadline.

### Registration validation (fail-loud)

`reg-machine` rejects a malformed timeout at construction time (per [Spec 009 §The thrown-error shape](009-Instrumentation.md)):

| Violation | Error id |
| --- | --- |
| `:timeout` declared without `:on-timeout` (state or spawn) | `:rf.error/machine-timeout-without-on-timeout` |
| `:on-timeout` declared without `:timeout` (state or spawn) | `:rf.error/machine-on-timeout-without-timeout` |
| `:timeout` duration is neither a positive integer nor a valid ISO-8601 string (incl. the `"5s"` / `"10ms"` shorthand) | `:rf.error/machine-bad-timeout-duration` |
| a desugared-timeout ms collides with an explicit `:after` delay-key on the same node | `:rf.error/machine-timeout-after-collision` |

The `:on-timeout` transition target is resolved through the **same** target-resolution check `:after` uses (it desugars to `:after`), so an `:on-timeout` naming a non-existent state fails registration with `:rf.error/machine-unresolved-target`.

There is no `:timeout-ms` slot on `:spawn` / `:spawn-all` (`:rf.error/spawn-timeout-ms-removed` rejects it); use the `:timeout` / `:on-timeout` grammar above.

## Spawning — dynamic actors

If machines are event handlers and actors are machines, then **a spawned actor is addressable as an event handler whose id is the actor's address** — but, per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db), that handler is NOT a per-instance registrar entry. It is *resolved on demand* from the actor's snapshot. The mailbox / addressing semantics fall out of `dispatch` — no new primitive.

> **Teardown is explicit in v1.** Every spawned actor ends its life at a named `[:rf.machine/destroy <actor-id>]` site — there is no implicit ownership cascade. Auto-cleanup via an opt-in `:owned-by` relation is a v1.1+ direction; per [§Resolved decisions §Auto-cleanup of orphaned actors](#auto-cleanup-of-orphaned-actors--explicit-rfmachinedestroy-for-v1-resolved). The one composed-with cascade is the `:rf.http/managed`-abort cascade per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts), which fires off the explicit `:rf.machine/destroy`.

<a id="liveness-is-derived-from-app-db"></a>

### Liveness is derived from runtime-db

A spawned actor's **liveness IS the presence of its snapshot** at `[:rf.runtime/machines :snapshots <actor-id>]` in the frame's (revertible) value — nothing else. Spawn and destroy are **pure runtime-db writes**: spawn installs the snapshot (stamping the revertible TYPE reference under `:rf/machine-type`, per [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys)) and the spawn-registry slot; destroy removes them. There is **no per-instance event-handler registration** — the spawn does NOT add an entry to any registrar, and the destroy clears none.

When a dispatch targets an `<actor-id>` for which no handler is registered, the runtime **lazily resolves** the actor's handler from `runtime-db`: it reads the live snapshot, recovers the actor's TYPE (the `:machine-id` keyword names a registered machine — the TYPE is registered like a singleton and outlives every instance — or, for an inline `:definition` spawn, the spec rides the snapshot directly), and routes the event to that TYPE's handler with the `<actor-id>` context. The address form is `[<actor-id> <event>]`, exactly as for a singleton. If no live snapshot exists for the actor-id, the dispatch is a genuine `:rf.error/no-such-handler` — correct, because the actor is not alive in this frame value.

**Why this is load-bearing — it closes the [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) leak.** Because liveness is a pure function of the frame value (the snapshot in runtime-db), a frame revert (`restore-epoch!` / undo / SSR hydration) reverts it perfectly and atomically with the rest of the state:

- **Rewind past a spawn** → the snapshot vanishes with the rest of the frame value; there is no orphaned handler left behind, because none was ever registered. A dispatch to the gone actor is a clean `:rf.error/no-such-handler`.
- **Rewind past a destroy** → the snapshot comes back with the rest of the frame value, and a dispatch to the actor *resolves again* through the lazy resolver — its liveness reverted with its snapshot, with **zero registrar drift**.

[§Where snapshots live](#where-snapshots-live) warns that "a parallel ActorRef registry … would put machine state outside the frame's value and break the goal." A per-instance handler registration IS exactly such a parallel registry — it would hold an actor's liveness outside the frame value. There is no per-instance registrar entry: the lazy-resolver keeps liveness where state lives — inside the revertible frame value.

Symmetry between singleton and spawned:

| | id form | snapshot location | liveness |
|---|---|---|---|
| Singleton | `:drawer/editor` (explicit) | `[:rf.runtime/machines :snapshots :drawer/editor]` | a registrar entry registered at boot via `reg-event`; outlives any one frame's value |
| Spawned actor | `:request/protocol#42` (gensym'd) | `[:rf.runtime/machines :snapshots :request/protocol#42]` | the SNAPSHOT's presence — no per-instance registrar entry; resolved on dispatch from the snapshot's `:rf/machine-type`; reverts with the frame value |

Both are addressable by `dispatch` (`[<id> <event>]`). Both readable through the framework-registered `:rf/machine` sub (per [§Subscribing to machines via the `:rf/machine` sub](#subscribing-to-machines-via-the-rfmachine-sub)) — the actor-id is just the argument: `@(rf/subscribe [:rf/machine actor-id])`. A singleton appears in `(registrations :event)`; a spawned actor does not (its liveness is its snapshot) — enumerate live actors via `[:rf.runtime/machines :snapshots]` instead, per [§Querying machines](#querying-machines).

### Spawn lifecycle — ordering

The spawn surface is composite: `:on-spawn`, `:rf.machine/spawn`, the synthetic `[:rf.machine.spawn/spawned]`, `:start`, and the spawned actor's initial-`:entry` cascade all participate. The individual pieces are spec'd in their own subsections; this section enumerates the **strict ordering** between them — what fires when, against what context, between the moment a parent state with `:spawn` is entered and the moment the spawned child processes its first user event.

Two distinct "spawn" surfaces, easy to conflate:

- **`:on-spawn` — advisory observation hook.** Declared inside the parent's `:spawn` / `:spawn-all` spec (or on a hand-emitted `:rf.machine/spawn`). Runs **inside the transition reducer at allocate-time**, invoked with `{:data <parent's :data> :id <freshly-allocated spawned-id>}` — the unified context-map (per [§Path conventions in machine bodies](#path-conventions-in-machine-bodies)). NOT a child-side event. **Its return value is DROPPED** — the runtime does not patch it back into the snapshot. It exists for side-channel observation (logging, mirroring the id into instrumentation); the runtime tracks the id in the spawn-registry at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` regardless of whether `:on-spawn` is declared. To address the child user-side, use one of the three working mechanisms in [§Recording the spawned id user-side](#recording-the-spawned-id-user-side).
- **`[:rf.machine.spawn/spawned]` — synthetic event dispatched into the new child.** Emitted by the `:rf.machine/spawn` fx handler **only when the spawn args omit `:start`**, so generic child machines have a kick-off event to handle. Reaches the child as its first event, ahead of the initial-`:entry` cascade only in resolution order — see step 6 below. Per [§Synthetic `[:rf.machine.spawn/spawned]` on spawn](#synthetic-rfmachinespawnspawned-on-spawn).

#### Ordering — singleton parent invoking a child

For a parent state with `:spawn {:machine-id :child …}` (or for a hand-emitted `:rf.machine/spawn` from an action), the runtime fires the following steps in order. Steps 1–4 happen inside the **parent's** drain; steps 5–8 happen inside the **child's** drain (a separate event-handler boundary).

1. **Parent enters the `:spawn`-bearing state.** The transition's entry cascade reaches the state-node; the desugared `:rf.spawn/spawn-<state>` action (per [§Desugaring rules](#desugaring-rules)) is appended to the cascade's action queue.
2. **`:on-spawn` advisory hook fires.** The pure spawn-id allocator picks the next `<id-prefix>#<n>` against `:rf/spawn-counter` at the parent's snapshot root, then writes the spawn-registry slot `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` (the authoritative user-readable home of the id). The `:on-spawn` callback (when declared) is then invoked with `{:data <parent's :data> :id <id>}` for side-channel observation; **its return value is dropped** and no fx is emitted by `:on-spawn` itself.
3. **`:rf.machine/spawn` fx is emitted** into the parent transition's `:fx` vector with the allocated spawned-id, the resolved child `:data`, and (for declarative `:spawn`) the stamped `:rf/parent-id` / `:rf/invoke-id` keys.
4. **Parent's drain commits.** The parent's post-action snapshot is written to `[:rf.runtime/machines :snapshots <parent-id>]` and the `:fx` vector drains through the fx pipeline. Up to this point the child does NOT exist.
5. **`:rf.machine/spawn` fx handler runs.** The child's spec is resolved (registered `:machine-id` or inline `:definition`); `synthesise-initial-snapshot` produces the child's initial snapshot with `:rf/bootstrap-pending? true`, the runtime-stamped `:data` keys (`:rf/self-id`, `:rf/parent-id`, `:rf/invoke-id` — per [§Runtime stamps](#runtime-stamps-on-the-spawned-actors-data)), the TYPE reference `:rf/machine-type`, and the user-supplied initial `:data` merged on top; the snapshot is installed at `[:rf.runtime/machines :snapshots <spawned-id>]`. This is a **pure runtime-db write** — NO per-instance handler is registered (the actor's liveness IS the snapshot; per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)). The runtime spawn-registry slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` is written for the declarative-`:spawn` case.
6. **Child's first event is dispatched** — `[<spawned-id> <:start arg>]` when the spawn args carried `:start`, otherwise the synthetic `[<spawned-id> [:rf.machine.spawn/spawned]]`. The two paths are mutually exclusive; the child receives exactly one of the two as its first event, never both.
7. **Child's initial-entry cascade fires** (per [§Initial-state `:entry` fires on machine creation (start)](#initial-state-entry-fires-on-machine-creation-start)). For a flat child the single initial state's `:entry` runs; for a compound child every `:entry` along the initial chain runs shallowest-first. This cascade runs **before** the first event's `:on` lookup, so `:entry`-emitted `:fx` is concatenated ahead of the first event's transition fx. `:rf/bootstrap-pending?` is cleared by the same drain.
8. **Child processes the first event.** The event vector arrived in step 6 is now resolved through the child's `:on` map (deepest-wins per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)). For the synthetic `[:rf.machine.spawn/spawned]` path with no matching handler the snapshot is unchanged — and because the kick-off id lives in the reserved `:rf/*` namespace it is **exempt** from the unhandled-no-op: no `:rf.machine.event/unhandled-no-op` trace fires (the reserved-`:rf/*` lifecycle carve-out per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)). The kick-off is framework init dispatched into every generic child, not an unknown user event the author forgot to handle; severity is benign either way (nothing throws), this is purely about not mislabelling framework lifecycle traffic as a missed user event.

The same skeleton applies to `:spawn-all`'s N children (per [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all)) with steps 2–5 fanning out once per child and a single join-bookkeeping write at `[:rf.runtime/machines :spawned <parent-id> <invoke-all-id>]`.

#### Worked walkthrough

```clojure
;; Parent
{:authenticating
 {:spawn {:machine-id :auth-flow
           :data       (fn [{snap :snapshot}] {:credentials (-> snap :data :form)})
           :system-id  :auth-actor                              ;; address the child by name (working)
           :on-spawn   (fn [{id :id}] (println "spawned auth flow" id))} ;; observation only — return dropped
  :on     {:auth/succeeded :authenticated
           :auth/failed    :idle}}}

;; Child (registered separately)
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states {:running {:entry :fire-request
                      :on    {:server-ok {:target :done}}}
            :done    {:final? true :output-key :token}}
   :actions {:fire-request (fn [{data :data}] {:fx [[:http/post …]]})}})
```

Trace of a `[:submit]` event landing on the parent in `:idle`:

1. Parent transitions `:idle → :authenticating`. Entry cascade reaches `:authenticating`.
2. Allocator picks `:auth-flow#0` and writes the spawn-registry slot `[:rf.runtime/machines :spawned :login [:authenticating]]` ⇒ `:auth-flow#0`; the `:system-id :auth-actor` binding resolves to it via `(rf/machine-by-system-id :auth-actor)`. The `:on-spawn` hook then fires for observation (its return is dropped).
3. Parent's `:fx` accumulates `[:rf.machine/spawn {:machine-id :auth-flow :rf/parent-id :login :rf/invoke-id [:authenticating] …}]`.
4. Parent commits — `[:rf.runtime/machines :snapshots :login]` updated; the spawn fx drains.
5. Spawn fx synthesises `:auth-flow#0`'s initial snapshot at `[:rf.runtime/machines :snapshots :auth-flow#0]` with `:state :running`, `:data {:rf/self-id :auth-flow#0 :rf/parent-id :login :rf/invoke-id [:authenticating] :credentials …}`, `:rf/bootstrap-pending? true`; the spawn-registry slot at `[:rf.runtime/machines :spawned :login [:authenticating]]` is written to `:auth-flow#0`.
6. Spawn-args lacked `:start`, so the synthetic `[:auth-flow#0 [:rf.machine.spawn/spawned]]` is dispatched.
7. The child's drain runs the initial-entry cascade: `:running`'s `:entry :fire-request` action emits the HTTP fx. `:rf/bootstrap-pending?` clears.
8. `[:rf.machine.spawn/spawned]` resolves through `:running`'s `:on` map — no match. The snapshot is unchanged and, because the kick-off id is reserved-`:rf/*` framework lifecycle traffic, **no** `:rf.machine.event/unhandled-no-op` fires (the reserved-`:rf/*` carve-out per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)) — the kick-off is framework init, not an unknown user event.

The contract on the trace stream — every step above corresponds to a distinct externally-observable event:

| Step | Trace |
|---|---|
| 1 | `:rf.machine/transition` (parent) |
| 2 | (no separate trace — `:on-spawn` runs inside the transition reducer) |
| 3 | (the fx is in the parent's `:fx` vector, visible as `:rf/fx` once the drain emits it) |
| 4 | `:rf.machine.lifecycle/commit` (parent) |
| 5 | `:rf.machine.spawn/spawned` (fx-substrate observation — the spawn fx ran) then `:rf.machine.lifecycle/spawned` (child; registrar-substrate observation — the actor's snapshot landed in the registrar) — the two-axis pair per [009 §Two-axis machine observation](009-Instrumentation.md#two-axis-machine-observation--registrar-substrate-vs-fx-substrate) |
| 6 | `:rf/event` (the synthetic kick-off) |
| 7 | `:rf.machine.lifecycle/bootstrap` (child) + per-action traces |
| 8 | (no trace — the `:rf.machine.spawn/spawned` kick-off is reserved-`:rf/*` framework lifecycle traffic, exempt from the unhandled-no-op per the reserved-`:rf/*` carve-out; the snapshot is unchanged) |

#### Why this matters

The ordering is what lets two patterns compose without surprises:

- **Initial-entry `:entry` actions can read the runtime-stamped `:data` keys.** Per step 5, the spawn-fx writes `:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id` into the child's `:data` BEFORE step 7 fires the `:entry` cascade — so an `:entry` action can `(get data :rf/parent-id)` to address its parent without the parent having to thread the id through any other mechanism.
- **`:start` is for handing the child a one-shot event payload.** When the child needs a specific first event (e.g. `[:begin "/some/url"]`), the parent supplies `:start`; when the child knows its job from initial `:data` alone, the parent omits `:start` and the synthetic `[:rf.machine.spawn/spawned]` is just a benign kick-off — the real work happens inside the initial-`:entry` cascade. New code prefers `:entry` over `:on :rf.machine.spawn/spawned` (per the note in the synthetic-event subsection).

### Spawning from inside an action (the common case)

```clojure
:action (fn [{[_ url] :event}]
          {:fx [[:rf.machine/spawn {:machine-id :request/protocol
                                    :id-prefix  :request/protocol
                                    :data       {:url url}
                                    :system-id  :pending-request   ;; address the child by name
                                    :start      [:begin]}]]})
```

After this action the actor is reachable by its `:system-id`. Subsequent transitions can `[:fx [[:rf.machine/dispatch-to-system [:pending-request [:retry]]]]]` (or `[:dispatch [(rf/machine-by-system-id :pending-request) [:retry]]]`). The `:on-spawn` callback is **not** the place to capture the id — its return is dropped (see [§Recording the spawned id user-side](#recording-the-spawned-id-user-side)).

### Spawn-spec keys

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to instantiate (registered id, or inline spec map) | one of these |
| `:id-prefix` | base for the gensym'd actor id (`:request/protocol#42`) | optional; defaults to `:machine-id` |
| `:data` | initial data for the new machine (overrides definition's default) | optional |
| `:on-spawn` | `(fn [{:keys [data id]}] _)` — advisory callback fired with the spawned id; return is ignored (runtime tracks the id at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`). | optional for any spawn; ignored for top-level boot-time spawns |
| `:start` | event vector dispatched to the new actor immediately after spawn | optional |
| `:system-id` | bind the spawned actor to a per-frame name in the `[:rf.runtime/machines :system-ids]` reverse index; lookup with `(rf/machine-by-system-id sid)`. See [§Named addressing via `:system-id`](#named-addressing-via-system-id). | optional |

The spawned actor's snapshot lives at `[:rf.runtime/machines :snapshots <gensym'd-id>]` — the runtime owns the location, the spawn-spec only declares the id-prefix. See [§Where snapshots live](#where-snapshots-live) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#standard-fx-args-schemas).

### Spawn-id allocator — counter location

Spawn-id allocation is **pure** (no global counter atom, no `gensym`), and the integer counter the allocator increments lives in a **different location depending on the spawn surface**. Both locations are pattern contract — a conformant port MUST mirror the split, because it is what keeps [Goal 3 — frame-state revertibility](000-Vision.md#frame-state-revertibility) intact across the two surfaces.

| spawn surface | counter location | revert behaviour |
|---|---|---|
| Declarative `:spawn` / `:spawn-all` from a parent state | inside the parent machine's snapshot at `[:rf/spawn-counter <id-prefix>]` — i.e. **snapshot-local** to the parent | a frame revert that restores the parent's snapshot also restores the parent's counter; re-running the same cascade allocates the same ids |
| Hand-emitted `:rf.machine/spawn` fx (from an event handler's `:fx`, no parent machine) | in the framework's runtime-db at `[:rf.runtime/machines :spawn-counter <id-prefix>]` — i.e. **frame-runtime-db-local** under the `:rf.runtime/machines` container | a frame revert that restores frame-state restores the counter; re-running the same drain allocates the same ids |

> **Two spawn-counters, one rule.** The two rows above are two *locations*, not two *policies* — read them as a single invariant: **the spawn-id counter lives in whatever state reverts atomically with the thing that allocated the id.** A declarative `:spawn` is allocated inside a parent's transition reducer, so its counter rides the parent's snapshot; a hand-emitted `:rf.machine/spawn` is allocated inside a top-level drain, so its counter rides runtime-db. Both choices serve the same goal (re-running a reverted cascade/drain re-allocates the *same* ids); neither is a special case to memorise.

The two-tier split is not implementation discretion. It falls out of the requirement that **revertibility round-trips through the same allocation site that produced the ids in the first place** — declarative spawns are part of a parent's transition reducer (so their counter belongs to the parent's snapshot, which is what reverts atomically with the cascade), whereas hand-emitted spawns are part of a top-level drain (so their counter belongs to runtime-db, which is what reverts atomically with the drain). Putting either counter outside the snapshot it composes with — e.g. a global counter, or an out-of-band atom — would break the round-trip: a revert would restore the snapshot but leave the counter at its post-revert value, and the next spawn would allocate a **different** id than the original, drifting the trace stream and the spawn-registry slot.

Per-prefix integer counts are stamped as `{}` at snapshot synthesis and at runtime-db construction; both maps persist across `pr-str` / `read-string` so the allocator survives a full snapshot round-trip. The id form (`<id-prefix>#<n>` keyword) is fixed per [§Spawn id format](#spawn-id-format---keyword-resolved). See also [Conventions §Reserved app-db keys — `:rf/spawn-counter`](Conventions.md#reserved-app-db-keys) and [Cross-Spec-Interactions §1 — Frame disposal](Cross-Spec-Interactions.md).

### Runtime stamps on the spawned actor's `:data`

Per the runtime stamps three framework-reserved keys into every spawned actor's initial `:data` map so the actor can address its parent and itself at action-call time without the parent having to thread that information through manually:

| key | value | when present |
|---|---|---|
| `:rf/self-id` | the spawned actor's own address (e.g. `:request/protocol#42`) | always |
| `:rf/parent-id` | the parent machine's registration-id | when the spawn carries `:rf/parent-id` (the declarative `:spawn` / `:spawn-all` desugar path) |
| `:rf/invoke-id` | the absolute prefix-path of the parent's `:spawn`-bearing state node | same as `:rf/parent-id` |

Per [§Path conventions in machine bodies](#path-conventions-in-machine-bodies), the `:rf/*` namespace inside `:data` is reserved for runtime-managed keys; user code does not write under it. The actor reads these as ordinary `:data` lookups inside its actions:

```clojure
:dispatch-done (fn [{:keys [data]}]
                 (when-let [parent-id (:rf/parent-id data)]
                   {:fx [[:dispatch [parent-id [:done (:result data)]]]]}))
```

Imperative `:rf.machine/spawn` from a user's `:fx` (the rare boot-time form per [§Top-level boot-time spawn](#top-level-boot-time-spawn-rare)) doesn't carry `:rf/parent-id` / `:rf/invoke-id`, so only `:rf/self-id` is stamped. That's the right shape — there's no parent in that case.

### Synthetic `[:rf.machine.spawn/spawned]` on spawn

Per — when `[:rf.machine/spawn ...]` does NOT carry an explicit `:start` event, the runtime dispatches a synthetic `[<spawned-id> [:rf.machine.spawn/spawned]]` to the new actor as its first event.

> **Note.** Per [§Initial-state `:entry` fires on machine creation (start)](#initial-state-entry-fires-on-machine-creation-start), `:entry` fires on machine creation, so `:entry :fire-request` is the canonical shape. The synthetic `[:rf.machine.spawn/spawned]` event still flows for machines that declare `:on :rf.machine.spawn/spawned ...`, but new code should prefer the `:entry` form.

```clojure
;; Canonical shape:
:requesting {:entry :fire-request}

;; Alternative (still supported, but not the canonical shape):
:requesting {:on {:rf.machine.spawn/spawned {:action :fire-request}}}
```

Machines that don't handle `:rf.machine.spawn/spawned` simply have no clause for it — the event walks the leaf→root resolution chain, finds no match, and the snapshot is unchanged. Because the kick-off id is reserved-`:rf/*` framework lifecycle traffic, it is **exempt** from the unhandled-no-op: no `:rf.machine.event/unhandled-no-op` fires (the reserved-`:rf/*` carve-out; per [§Transition resolution — deepest-wins with parent fallthrough](#transition-resolution--deepest-wins-with-parent-fallthrough)). The kick-off is framework init dispatched into every generic child, not an unknown user event the author forgot to handle.

When the spawn DOES carry `:start`, the runtime dispatches `[<spawned-id> <start>]` instead — the existing behaviour, unchanged. The two paths are mutually exclusive; an actor receives one of `:rf.machine.spawn/spawned` OR the user's `:start`, never both. In both cases the initial-state `:entry` cascade runs BEFORE the first event's `:on` lookup, so `:entry` actions on the initial state fire regardless of which kick-off mode the spawn used.

### `:source` classification — `:machine-spawn`

The spawn fx (`:rf.machine/spawn`) dispatches the spawned actor's first event — either the user-supplied `:start` event or the synthetic `[:rf.machine.spawn/spawned]` — into the new actor. That dispatch stamps **`:source :machine-spawn`** on the dispatch envelope so the Epoch panel's DISPATCH step renders "from machine spawn" rather than `:unknown` (the residual default) or `:fx-dispatch` (which would be the default if the spawn fx routed through `:dispatch`). The naming — `:machine-spawn` — mirrors the spec's term so the operator-facing label greps back to this section. There is no separate `:rf/dispatch-origin` tag; `:source :machine-spawn` is the single functional-origin discriminator.

### Top-level boot-time spawn (rare)

The canonical surface is the `[:rf.machine/spawn ...]` fx — used inside an event handler's `:fx`. From outside a handler (e.g. boot-time), wrap the spawn in a one-shot bootstrap event:

```clojure
(rf/reg-event
  :app/spawn-request-protocol
  (fn [_ [_ url]]
    {:fx [[:rf.machine/spawn
           {:definition request-protocol           ;; or :machine-id if reusing a registered definition
            :id-prefix  :request/protocol           ;; → :request/protocol#42
            :data       {:url url :attempt 0}
            :system-id  :request-id}]]}))            ;; address it later by name (an :on-spawn return would be dropped)

(rf/dispatch-sync [:app/spawn-request-protocol "/foo"])

;; snapshot lives at [:rf.runtime/machines :snapshots :request/protocol#42] in the active frame's runtime-db.

;; address it
(rf/dispatch [actor-id [:retry]])
(rf/dispatch [actor-id [:cancel]])

;; destroy — emit the canonical destroy fx from a handler
(rf/reg-event
  :app/destroy-request-protocol
  (fn [_ [_ actor-id]]
    {:fx [[:rf.machine/destroy actor-id]]}))
;; Internally: run :exit action, dissoc the snapshot at [:rf.runtime/machines :snapshots actor-id],
;; clear-event actor-id. (No per-machine sub to clear — reads go through the
;; framework-registered :rf/machine sub, parameterised on actor-id.)
```

(There are no `spawn-machine` / `destroy-machine` public fns — see [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Destroy is silent-idempotent

**Destroying an already-destroyed actor is a silent no-op** (aligned with the XState convention). The actor's lifecycle has exactly one observable transition (Active → Stopped); subsequent destroy attempts emit NO `:rf.machine/destroyed` trace, perform NO teardown work, and raise NO error. This holds uniformly across the destroy surface:

- **Explicit destroy after auto-destroy.** A child reaches `:final?` and auto-destroys (per [§Final states D4](#final-states-final--on-done--output-key)); a subsequent `[:rf.machine/destroy <id>]` fx on the same id is a silent no-op.
- **Double-explicit destroy in the same cascade.** Two `[:rf.machine/destroy <id>]` entries in the same `:fx` vector — or a user action emitting the fx for an actor whose declarative-`:spawn` exit cascade has already fired — collapse to one observable destroy and one trace.
- **Tracked-form destroy with no spawn-slot entry AND no snapshot.** A `[:rf.machine/destroy {:rf/parent-id … :rf/invoke-id …}]` whose `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot is already cleared AND whose resolved actor has no snapshot is a silent no-op (both have been atomically cleared by an earlier destroy).

The liveness probe distinguishes **already-destroyed** (the actor was alive, the teardown projection ran, the registrar slot was cleared, the spawn-order entry was forgotten) from **alive-but-unmaterialised-snapshot** (the actor IS alive in this drain — a back-to-back spawn-then-destroy in the same `:fx` vector — but its snapshot swap has not yet landed at `[:rf.runtime/machines :snapshots <actor-id>]`). Snapshot-presence alone is **not** the right signal: the spawn-order entry (recorded unconditionally on every spawn) is the reliable "alive" bit while the snapshot swap is still in flight, and its destroy still owns legitimate cleanup work (spawn-order/forget + the observability trace). (An UNREGISTERED `:machine-id` never reaches this probe — it is rejected fail-closed at the spawn fx with `:rf.error/machine-spawn-unregistered-type` and installs nothing; there is no implicit "spec-less spawn" lifecycle — see [§Errors](#errors).) The runtime treats an actor as **live** when ANY of the following holds:
- the actor's event handler is still registered at `<actor-id>`,
- a snapshot exists at `[:rf.runtime/machines :snapshots <actor-id>]`,
- the per-frame spawn-order channel still tracks `<actor-id>`, or
- (tracked-form only) the spawn-slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` still resolves.

An already-destroyed actor has **all** of these gone, atomically — the unified teardown projection (per [§Final states D4](#final-states-final--on-done--output-key)) dissocs the snapshot, clears the spawn-slot, and releases the `[:rf.runtime/machines :system-ids]` reverse-index entry; `registrar/unregister!` clears the handler; `spawn-order/forget!` clears the channel entry. The destroy fx handler probes these liveness signals before any side effect (trace emit, exit cascade, HTTP abort, registrar unregister, spawn-order forget) and returns early when **and only when** the actor was previously alive and is now fully gone.

Observability is unchanged: the **original** `:rf.machine/destroyed` (with `:reason :explicit` for cascade-driven destroys, `:reason :rf.machine/finished` for auto-destroy on `:final?` per [§Final states D6](#final-states-final--on-done--output-key)) IS the destroy signal. No second event fires for the no-op attempts, and no `:rf.warning/destroy-of-finished-actor` is emitted — silence is the contract.

### Spawning multiple, dynamic counts

Multiple `[:rf.machine/spawn ...]` entries in `:fx` work independently; each fires its (advisory) `:on-spawn` hook with the freshly-allocated id. For dynamic-count spawning, build the `:fx` vector with `mapv`:

```clojure
:action (fn [{[_ jobs] :event}]
          {:fx (mapv (fn [job]
                       [:rf.machine/spawn {:machine-id :worker
                                           :data       job}])
                     jobs)})
;; → each worker's id is reachable from the spawn-registry; to collect them
;;   into the parent's :data, emit one :rf.machine/update-snapshot after the
;;   spawns drain (the ids live at [:rf.runtime/machines :spawn-counter ...]
;;   deterministically), or address each worker by a per-job :system-id.
```

To record the ids in the parent's `:data`, do NOT reach for `:on-spawn` — its return is dropped. Use [`:rf.machine/update-snapshot`](#path-conventions-in-machine-bodies) from a regular `:action`'s `:fx`, or give each spawn a distinct `:system-id` and resolve by name. See [§Recording the spawned id user-side](#recording-the-spawned-id-user-side). (These hand-emitted `:rf.machine/spawn` fxs do **not** get the automatic `:rf/spawned` `:data` capture — that first-class slot is written only by the *declarative* `:spawn` / `:spawn-all` reducer, where the runtime owns the invoke-id; a hand-emitted spawn from a user `:fx` has no declarative invoke-id to key it under.)

### What spawning gives for free

- **Inspection.** Live actor INSTANCES are enumerated from the frame's `[:rf.runtime/machines :snapshots]` (a spawned actor has no per-instance registrar entry); registered machine TYPES appear in `(registrations :event)` filtered by `:rf/machine?`.
- **Tracing.** Every message to an actor is a normal `:event` trace. Lifecycle is the `:rf.machine.lifecycle/*` trace family on the snapshot store.
- **Errors.** Sending to a destroyed actor → `:rf.error/no-such-handler`. Already categorised, already recoverable.
- **Hot-reload.** Live spawned instances pick up new table interpretations on next event.
- **Cross-machine messaging.** Parent → child is `[:fx [[:dispatch [child-id [:event]]]]]`. Child → parent is the same. No `sendTo` / `sendParent` distinction — `dispatch` already addresses any id. XState's actor model splits this into four primitives (`sendTo(ref, ev)`, `sendParent(ev)`, the implicit reply via the child ref returned by `spawn`/`invoke`, and `system.get(id)`); re-frame2 subsumes all four under **one** mechanism — `dispatch` by id — with `:system-id` ([§Named addressing via `:system-id`](#named-addressing-via-system-id)) supplying the `system.get` analog. The `sendParent` analog deserves a note because XState lets a child address its parent **without being told who the parent is**:

  - **`sendParent` analog — and its caveat.** A **declaratively-spawned** child knows its parent because the runtime stamps `:rf/parent-id` into its initial `:data`; the child reads it and dispatches back: `(when-let [pid (:rf/parent-id data)] {:fx [[:dispatch [pid [:done result]]]]})` (per [§Runtime stamps on the spawned actor's `:data`](#runtime-stamps-on-the-spawned-actors-data)). **Caveat:** `:rf/parent-id` is stamped **only on the declarative `:spawn` / `:spawn-all` path**. A **hand-emitted** `[:rf.machine/spawn …]` from a user `:fx` stamps only `:rf/self-id` — there is no structural parent to record (see [§Runtime stamps](#runtime-stamps-on-the-spawned-actors-data)). So `sendParent` is **not universally available**: a hand-spawned child has no parent address unless the spawner threads a correspondent address in via the spawn's `:data`, or the two coordinate through a shared `:system-id`. An XState author expecting `sendParent` to always work should know this is the one case where it does not.
- **`:raise` lowers to self-dispatch with atomic semantics.** `[:raise [:event]]` ≡ `[:fx [[:dispatch [<self-id> [:event]]]]]` *with* "processed before commit." The former is sugar.

### Named addressing via `:system-id`

A spawn whose `:system-id` key is supplied **also** binds a name in the per-frame `[:rf.runtime/machines :system-ids]` reverse index. Users (and other machines) can then look up the spawned actor by that name, without having to thread the gensym'd id through their own `:data`. The mechanism is opt-in and orthogonal to gensym'd ids — it sits *alongside* the existing addressing-by-id, never replaces it.

```clojure
;; Imperative spawn (action :fx) with a :system-id binding.
:action (fn [_]
          {:fx [[:rf.machine/spawn {:machine-id :request/protocol
                                    :system-id  :primary-request    ;; bind the name
                                    :data       {:url "/api/foo"}
                                    :start      [:begin]}]]})

;; The same :system-id key works on declarative :spawn:
{:loading
 {:spawn {:machine-id :request/protocol
           :system-id  :primary-request
           :data       (fn [{snap :snapshot}] {:url (-> snap :data :endpoint)})}}}

;; Anywhere in the same frame:
(rf/machine-by-system-id :primary-request)
;; → :request/protocol#42 (the gensym'd id)
```

The mapping lives at `[:rf.runtime/machines :system-ids <name>]` in the spawning frame's **runtime-db** — same place the snapshot lives, so the reverse index inherits frame revertibility for free (the index walks back along with the rest of the frame-state).

The 1-arity `(rf/machine-by-system-id sid)` resolves the frame through the ambient scope/hold chain (a lookup under no scope raises `:rf.error/no-frame-context`, never a `:rf/default` floor). To look up a named frame from outside any scope (async callbacks / tools / cross-frame lookups), pass the trailing `{:frame …}` opts form `(rf/machine-by-system-id sid {:frame target})` — the same public-frame-targeting shape `sub-machine` takes and the family's frame-arg law prescribes (public frame targeting is a trailing opts map, never positional — per the family frame-arg law in [Conventions](Conventions.md)). The 2-arity is shape-discriminated on the second arg: an opts map (a non-frame-value map) is the public `(sid {:frame …})` form; a bare frame target (a frame-id keyword or frame value) is the *internal* frame-last plumbing, retained for implementation / test / tooling reach.

**Lifecycle.**

- On spawn, the runtime writes `[:rf.runtime/machines :system-ids <name>] = <gensym'd-id>` and emits `:rf.machine/system-id-bound`.
- On destroy (whether by `:spawn` exit cascade or hand-emitted `[:rf.machine/destroy actor-id]`), the runtime clears the slot AND emits `:rf.machine/system-id-released`.
- A spawn under an already-bound name **rebinds** (last-write-wins) and emits `:rf.error/system-id-collision` so observers can see the displacement. The previously-bound machine's snapshot is NOT auto-destroyed by the rebind; it stays at its `[:rf.runtime/machines :snapshots <id>]` slot, just unnamed. (Symmetric with `reg-event` re-registration: replacing a handler doesn't cancel any in-flight work that addressed the previous fn; it just means the next *named* dispatch routes to the new one.)

**`:system-id` is orthogonal to `:fixed-actor-id`.**

- `:fixed-actor-id` is a per-state singleton actor-address INPUT — the spawned actor's address is fixed by name (no gensym). It was the overloaded `:spawn-id`.
- `:system-id` is a *frame-level reverse index* that resolves to whichever spawned actor currently owns the name.

A spawn may declare both: `:fixed-actor-id` fixes the actor-id (no gensym), and `:system-id` registers a separate name in the frame's reverse index. Most uses pick one or the other.

### Cross-machine messaging by name

The standard cross-machine pattern remains `[:fx [[:dispatch [<other-id> [:event]]]]]` — `dispatch` already addresses any registered id. With `:system-id` bound, the addressing call site becomes a name lookup:

```clojure
;; Inside a machine action's :fx — dispatch by name
:action (fn [_]
          {:fx [[:dispatch [(rf/machine-by-system-id :primary-request)
                            [:cancel]]]]})

;; Canonical — dispatches via the lookup, no-ops when the name is unbound:
:action (fn [_]
          {:fx [[:rf.machine/dispatch-to-system [:primary-request [:cancel]]]]})
```

The `:rf.machine/dispatch-to-system` fx tuple is the **canonical** cross-machine-by-name surface — the action-side address a machine emits from `:fx`. (It performs the same name-resolve-then-dispatch as the explicit `[:dispatch [(rf/machine-by-system-id ...) ...]]` form above, with no-op-when-unbound semantics folded in.) Its args are the single 2-element pair `[<system-id> <event-vector>]` — the framework fx contract is a `[fx-id args]` pair (the `do-fx` walk drops arity-≥3 entries with `:rf.error/effect-map-shape`), so the system-id and event ride together in the one `args` slot, exactly as `:dispatch`'s args is a single event vector and `:rf.machine/spawn`'s is a single spec map. The fx-id is namespaced under `:rf.machine/*` per [Conventions §Fx-id namespacing rule](Conventions.md#fx-id-namespacing-rule--three-reserved-fx-id-sub-namespaces) (surface-specific machine fx).

The sender doesn't have to capture the gensym'd id at the spawn site, doesn't have to carry it through `:data`, doesn't even have to be the spawning machine — anything in the frame that knows the name can address the actor.

The pattern composes naturally with the standard reply convention ([§Reply patterns](#reply-patterns)): include the reply event in the request, addressed by name on the request side, by id on the reply side (so the reply lands in a specific spawned correlator, not whichever machine currently owns the name).

## Declarative `:spawn`

`:spawn` on a state node is **declarative sugar** for "spawn this child actor on entry; destroy it on exit." The child's lifetime is bound to the state's lifetime: while the machine is in this state, the child runs; when the machine leaves the state (by any transition, including a parent-level cascade), the child is destroyed.

`:spawn` is **registration-time sugar.** `make-machine-handler` walks the spec at construction time and rewrites every `:spawn` slot into entry/exit actions emitting `:rf.machine/spawn` and `:rf.machine/destroy` fx. The runtime sees only the desugared form — no new mechanics, no new lifecycle event, no new error category.

### The pattern

```clojure
{:loading
 {:spawn {:machine-id :request/protocol
           :data       {:url "/api/foo"}
           :system-id  :loader              ;; address the child by name (its id is dropped from :on-spawn)
           :start      [:begin]}
  :on     {:succeeded {:target :loaded}
           :failed    {:target :error}}}}
```

While in `:loading`, an actor of `:request/protocol` exists at `[:rf.runtime/machines :snapshots <gensym'd-id>]`, addressable through the runtime-owned registry at `[:rf.runtime/machines :spawned <parent-machine-id> [:loading]]` and, with the `:system-id :loader` binding above, by name via `(rf/machine-by-system-id :loader)`. On any transition out of `:loading`, the actor is destroyed and its snapshot disappears — the runtime locates it via the registry slot, never requiring the user to have written the id under any specific `:data` key (an `:on-spawn` callback could not do so anyway — its return is dropped).

### Spec-spec keys

The map under `:spawn` accepts the following keys:

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one of these |
| `:data` | initial data for the child — literal map or `(fn [{:keys [snapshot event]}] data)` (unified context-map) | optional |
| `:id-prefix` | base for the gensym'd actor id (`:request/protocol#42`) | optional; defaults to `:machine-id` |
| `:on-spawn` | `(fn [{:keys [data id]}] _)` — advisory callback fired with the spawned id; return is ignored (runtime tracks the id at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`) | optional |
| `:on-done` | `(fn [{:keys [data result]}] new-data)` — fires when the child enters a (non-error) `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil` if no `:output-key` declared) — see [§Final states](#final-states-final--on-done--output-key). Returns the parent's new `:data` map. | optional |
| `:on-error` | an `:on`-shaped **transition** spec `{:target :guard :action}` (or keyword / vector-path target, or guarded candidate vector) — fires when the spawned child **FAILS**: it reaches a designated error `:final?` leaf (`:error? true`), or one of its actions throws an uncaught exception. The parent moves to the transition's `:target` (resolved at the `:spawn`-bearing state's own level — a keyword target is a sibling), running its `:guard` / `:action`. The error payload rides on the transition's `:event` (the child's `:output-key` slot for the error-leaf trigger, or the exception envelope for the action-exception trigger). re-frame2's spelling of XState v5 `invoke onError` — control flow, not just observability. See [§`:on-error`](#on-error--child-failure-control-flow). | optional |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:fixed-actor-id` | explicit actor-address input instead of gensym (useful for tests / per-state singleton actors); was the overloaded `:spawn-id` | optional |

The keys mirror [§Spawn-spec keys](#spawn-spec-keys), with two additions:

- `:data` admits a function form `(fn [{:keys [snapshot event]}] data)` so the initial data can depend on the snapshot + the triggering event at the moment of entry — the snapshot is the *post-action* value (the transition's `:action` has already run, so any `:data` writes the action made are visible). Per the callback receives the unified context-map.
- `:fixed-actor-id` is an explicit alternative to `:id-prefix` + gensym — useful when a state should host exactly one actor with a known id (no need to record the id in the parent's `:data` because it's already a known constant). This names the explicit actor-address INPUT and is distinct from the runtime-stamped invocation path `:rf/invoke-id`.

> **Wall-clock timeouts: use the parent state's `:after` slot.** There is **no** `:timeout-ms` slot on `:spawn` / `:spawn-all` for "the whole spawned actor must terminate within N ms (spanning retries)." Use the canonical `:after` primitive on the parent state — `:after` is one mechanism, not two. Per [§Whichever fires first wins](#whichever-fires-first-wins), an `:after` firing on the parent state exits the state and the standard exit cascade destroys the in-flight `:spawn`d child. The migration recipe is mechanical: lift the `:timeout-ms` value into the `:spawn`-bearing state's `:after` map, with a transition that exits the state to a "timeout" target. See [MIGRATION §M-44](../migration/from-re-frame-v1/README.md#m-44-timeout-ms-removed-from-spawn--spawn-all--use-parent-states-after).

**Path convention.** The `:on-spawn` callback receives the unified context-map `{:data <parent's :data> :id <spawned-id>}` — it reads the parent's `:data` but, unlike `:guard` / `:action`, **its return value is dropped**: `:on-spawn` cannot write the parent's `:data`. The runtime does NOT patch any result back into the snapshot. Use `:on-spawn` for side-channel observation only (logging, mirroring the id into instrumentation). To record the id in the parent's `:data`, see [§Recording the spawned id user-side](#recording-the-spawned-id-user-side).

**`:on-spawn` is purely advisory — a side-channel observation hook.** The runtime tracks each declarative-`:spawn` spawn-id at the reserved runtime-db slot `[:rf.runtime/machines :spawned <parent-machine-id> <invoke-id>]` (where `<invoke-id>` is the absolute prefix-path of the `:spawn`-bearing state node) **regardless of whether `:on-spawn` is declared**. The `:on-spawn` callback runs only so apps can observe the spawn (logging, instrumentation) — it does NOT record the id anywhere itself (its return is dropped), and the runtime never depends on it for destroy-side resolution. Apps can omit `:on-spawn` entirely; the parent's `:exit` cascade still tears down the spawned child via the runtime registry.

> **Use `:on-spawn` ONLY for side-channel observation.** The callback is the canonical place to log spawn events or mirror the new id into instrumentation. It is NOT load-bearing for destroy-side lifecycle, which the runtime owns via the `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` registry. Treat `:on-spawn` as the convenient observer hook for "the spawn just happened, here's the id" — not as a step the framework needs you to perform for correctness, and **not** as a way to write the parent's `:data` (its return is dropped — see below).

`:on-spawn` is **purely advisory**: the runtime invokes the callback with `{:data ... :id <spawned-id>}` (per the unified context-map contract) and DROPS the return value entirely. The parent's `:data` cannot be mutated by `:on-spawn`. The runtime owns destroy-side resolution via the `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` registry.

#### Recording the spawned id user-side

`:on-spawn`'s return is dropped, so a callback like `(assoc data :pending id)` is a no-op — `(:pending data)` stays `nil` forever, and in a dev build the runtime emits `:rf.warning/on-spawn-return-ignored` to flag the dropped value. The framework already hands you the id; there is **no need for an external atom side-channel**. The first-class mechanisms, in order of preference:

1. **Read the parent's `:data :rf/spawned` slot (the first-class idiom — XState-context parity).** On every declarative `:spawn` / `:spawn-all`, the pure transition reducer binds the assigned actor id into the SPAWNING machine's OWN `:data` under the reserved per-invoke map `:rf/spawned` — `{:rf/spawned {<invoke-id> <spawned-id>}}` (for a `:spawn-all`, the value is the `{<child-id> <spawned-id>}` children map). An action reads it directly off the context-map's `:data`:

   ```clojure
   ;; an action that destroys an actor it spawned from the [:working] state:
   {:tear-down (fn [{data :data}]
                 {:fx [[:rf.machine/destroy (get-in data [:rf/spawned [:working]])]]})}
   ```

   This is the re-frame2 spelling of XState v5's `const ref = spawn(child)` captured into `context` — except the id rides the (revertible, SSR-survivable) snapshot rather than a live object. No atom, no runtime-db path coupling. It is the REVERSE direction of the child-lineage stamps (`:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id`) the runtime writes onto the CHILD's `:data`: here the PARENT captures the CHILD's id, keyed by the SAME `<invoke-id>` the child records under `:rf/invoke-id`. See [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys) for the `:rf/spawned` row.
2. **`:system-id`.** Declare `:system-id :my-name` on the `:spawn` / `:rf.machine/spawn` spec; from a machine action's `:fx` emit the canonical effect tuple `[:rf.machine/dispatch-to-system [:my-name [...]]]` to message the bound actor by name, or — from a direct call site outside an action context — resolve the id with `(rf/machine-by-system-id :my-name)` and `(rf/dispatch [(rf/machine-by-system-id :my-name) [...]])`. Best when you want a stable *name* rather than the gensym'd id. See [§Named addressing via `:system-id`](#named-addressing-via-system-id).
3. **Read the runtime registry slot.** The id is also always at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` (`<invoke-id>` is the absolute prefix-path of the `:spawn`-bearing state) — read it directly when you have the parent-id and state path but are *outside* the machine's own action context (where mechanism 1 is unavailable). The `:rf/spawned` `:data` slot (mechanism 1) mirrors this registry slot exactly, in-snapshot.
4. **`:rf.machine/update-snapshot`.** From a regular `:action`'s `:fx` vector, emit `[:rf.machine/update-snapshot {:rf/machine-id <id> :rf/patch {:data {...}}}]` to write a *user-domain* copy of the id into the parent's `:data` under your own key — only when you need it under a domain-specific name distinct from the framework's `:rf/spawned` slot. See the snapshot-level escape hatch in [§Path conventions in machine bodies](#path-conventions-in-machine-bodies).

### Desugaring rules

`make-machine-handler` walks every state node at construction time. For each `:spawn`-bearing state, it:

1. **Composes** an `:rf.spawn/spawn-<state>` registered action that emits a `:rf.machine/spawn` fx whose args are the `:spawn` spec, with `:data` materialised (call the fn if `:data` is a fn, else use the literal). The runtime stamps `:rf/parent-id` (the parent machine's registration-id) and `:rf/invoke-id` (the absolute prefix-path of the `:spawn`-bearing state node) onto the spawn args; the `:rf.machine/spawn` fx handler binds the spawned id at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` in the frame's runtime-db.
2. **Composes** an `:rf.spawn/destroy-<state>` registered action that emits a `:rf.machine/destroy` fx whose args carry the same `{:rf/parent-id ... :rf/invoke-id ...}`. The fx handler reads the spawned id back from `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` at call time and tears down whatever id is currently bound there. (For `:fixed-actor-id` literals — the explicit-address case — the runtime uses that id directly; the registry slot still binds it for symmetry.)
3. **Wires** the composed actions into the state's `:entry` and `:exit` slots, after any user-supplied `:entry` / `:exit` (see [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit)).

The runtime-owned spawn registry at `[:rf.runtime/machines :spawned ...]` is sibling to `[:rf.runtime/machines :system-ids]` (per [§Named addressing via `:system-id`](#named-addressing-via-system-id)) — same lazy-allocation invariant (absent until the first declarative-`:spawn` spawn), same per-frame isolation (each frame's runtime-db carries its own slot), same revertibility (the slot walks back atomically with the rest of the frame-state on a frame revert).

Before / after:

```clojure
;; user writes (declarative :spawn):
{:loading
 {:spawn {:machine-id :request/protocol
           :data       (fn [{snap :snapshot}] {:url (-> snap :data :endpoint)})
           :system-id  :loader
           :start      [:begin]}
  :on     {:succeeded :loaded
           :failed    :error}}}

;; make-machine-handler rewrites to (runtime sees this):
{:loading
 {:entry (fn [{data :data}]
           {:fx [[:rf.machine/spawn {:machine-id   :request/protocol
                                     :id-prefix    :request/protocol
                                     :data         {:url (:endpoint data)}
                                     :system-id    :loader
                                     :start        [:begin]
                                     ;; Stamped by the runtime — addresses the
                                     ;; runtime-owned spawn registry slot at
                                     ;; [:rf.runtime/machines :spawned <parent-id> <invoke-id>].
                                     :rf/parent-id <parent-machine-id>
                                     :rf/invoke-id [:loading]}]]})
           ;; The reducer ALSO binds the assigned id into THIS machine's own
           ;; :data under [:rf/spawned [:loading]] — so a later
           ;; action can read it: (get-in data [:rf/spawned [:loading]]).
  :exit  (fn [_]
           ;; The destroy fx never reads the actor id from
           ;; `:data`. The fx handler resolves
           ;; the id from [:rf.runtime/machines :spawned <parent-id> <invoke-id>] in the
           ;; frame's runtime-db at call time.
           {:fx [[:rf.machine/destroy {:rf/parent-id <parent-machine-id>
                                       :rf/invoke-id [:loading]}]]})
  :on    {:succeeded :loaded
          :failed    :error}}}
```

From outside, a `:spawn`-using machine is indistinguishable from one that wrote the entry/exit by hand — and the runtime never requires the user to record the spawned id under any particular `:data` slot (it could not be recorded from `:on-spawn` anyway — that return is dropped). The pure-factory invariant on `make-machine-handler` is preserved — no global state, no new registry kind, no new lifecycle hook (the `[:rf.runtime/machines :spawned ...]` slot lives in the frame's **runtime-db** partition per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys); not a separate registry).

> **Spec-as-data caveat for `:spawn` / `:spawn-all`.** The [Principles §Data is code](Principles.md#data-is-code) invariant ("what you write IS what runs") holds at the **user-visible** boundary: `machine-meta` returns the user-written spec form (the registrar stores the user-supplied map verbatim — see [§Querying machines](#querying-machines)), and the conformance harness, the migration agent, and tools that read registered specs all see the same shape the author wrote. Where the invariant is **fudged** is the **runtime spec value** threaded through `apply-transition-once`: `make-machine-handler` walks the user spec at construction time and rewrites every `:spawn` slot into the `:entry` / `:exit` action pair shown above. A debugger that prints the *runtime* spec record sees the desugared form, not the literal `:spawn` map. The two surfaces are split: the spec-as-data invariant covers what users wrote and what tools read back via `machine-meta`; the runtime-internal form is an implementation detail of the reducer. Authors writing tools that consume the runtime spec (rather than the registered metadata) should consume `machine-meta` for the user-facing shape; the runtime form is not part of the public contract and may evolve.

### Composition with explicit `:entry` / `:exit`

A state may declare both `:spawn` AND user-supplied `:entry` / `:exit`. The user-supplied actions run **first** in each slot:

- **On enter:** the user's `:entry` action runs, then the auto-spawn fx is emitted.
- **On exit:** the user's `:exit` action runs, then the auto-destroy fx is emitted.

Rationale: the user's `:entry` is for setup work that must happen before the child starts (e.g., normalising data, recording a start timestamp). The spawn happens after that setup completes, so the child sees the post-setup snapshot. On exit, the user's `:exit` action gets to read the actor's final snapshot before the auto-destroy clears it — useful for capturing the child's last reported value. Address the child via the runtime registry — `(get-in db [:rf.runtime/machines :spawned <parent-machine-id> <invoke-id>])` resolves to the gensym'd id and `(get-in db [:rf.runtime/machines :snapshots <id>])` reads the snapshot from there — or by `:system-id` name (per [§Recording the spawned id user-side](#recording-the-spawned-id-user-side)).

The composition is **wire-level concatenation, not nesting** — the action ordering is `[user-entry, auto-spawn]` for entry and `[user-exit, auto-destroy]` for exit. Each runs as a normal action, returning its own `{:data :fx}` effect map; the runtime drains them in order per [§Drain semantics — Level 2](#level-2--across-the-action-slots-in-one-transition).

`:entry` and `:exit` remain **singular slots** ([§State nodes](#state-nodes)) — the user writes one fn or one registered id, and the desugaring of `:spawn` adds exactly one more action to each slot. There is no user-visible vector form.

### Composition with hierarchical states

A `:spawn`-bearing state can sit at any level of a compound hierarchy. The `:spawn` slot produces ordinary `:entry` / `:exit` actions — the existing entry/exit cascading machinery from [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca) handles them naturally.

Concretely:

- A child state's `:spawn` fires its spawn when the child is *entered* (which may happen as part of a deeper cascade — e.g., entering a compound parent for the first time also enters the parent's `:initial` cascade target).
- A parent state's `:spawn` fires its destroy when the parent is *exited* — and the parent is exited only when a transition's LCA is above it. Sibling-leaf transitions inside the parent do NOT destroy a parent-level `:spawn` actor.
- Multiple `:spawn`-bearing ancestors along a deep path each contribute their own spawn/destroy pair, ordered by the cascade direction (entry: outermost first; exit: innermost first).

The desugaring is uniform — no special-casing for hierarchy. Whatever cascading rules the existing entry/exit machinery applies are exactly what `:spawn` inherits.

### Errors

`:spawn` adds **one** new runtime error category — the fail-closed unregistered-type reject below. Other failures route through the existing `:rf.error/*` machinery (and, when the parent declares `:on-error`, *additionally* drive a declarative parent transition — see [§`:on-error`](#on-error--child-failure-control-flow)):

- If `:data` is a function and it throws, the error surfaces as `:rf.error/machine-action-exception` (the standard category for any user-supplied fn that throws during a machine action — see [Cross-Spec-Interactions §11](Cross-Spec-Interactions.md#11-machine-action-throws)). The transition halts; the snapshot does not commit.
- **If `:machine-id` references an UNREGISTERED machine TYPE — and the spawn carries no inline `:definition` — the spawn fx REJECTS the spawn fail-closed** and emits the **always-on** `:rf.error/machine-spawn-unregistered-type` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). The reject is total: **no** snapshot installs, **no** spawned-id is allocated, **no** `[:rf.runtime/machines :system-ids]` binding, **no** `[:rf.runtime/machines :spawned …]` slot, **no** spawn-order record, **no** trace beyond the error, and **no** `:start` (or synthetic `[:rf.machine.spawn/spawned]`) dispatch — the strongest atomicity, because the implicit "spec-less spawn" path (a `:machine-id` resolving to no registered spec, e.g. an SSR / platform-gated build that registered the parent but not the child) is **REMOVED** as a supported lifecycle. Spawning an unregistered type is fail-loud, never silent. The error is **structural-only** (`:machine-id`, `:frame`, `:reason`, `:recovery :no-recovery`) — it carries **no** spawn args, because `:start` payloads / `:data` may hold application data and the always-on record is production-surviving and not privacy-gated. (For `:spawn-all`, an unregistered child TYPE rejects the **whole join** before any join-state is seeded — see [§Spawn-and-join via `:spawn-all` §Errors](#errors_1) — so a never-running child can never deadlock the join.)
- If the user supplies neither `:machine-id` nor `:definition` — **or both** — `make-machine-handler` rejects the spec at registration time with **`:rf.error/machine-spawn-bad-shape`**: the schema makes "exactly one of `:machine-id` or `:definition`" a registration-time **XOR** constraint per [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table). The both-set case is not merely redundant — it is *ambiguous*: the child would initialise from the inline `:definition` while `:rf/machine-type` stamps the registered `:machine-id`, so a later lazy-resolution / restore could materialise a *different* machine type than the one that created the snapshot. (`:spawn-all` children carry the same XOR constraint, rejected with `:rf.error/machine-spawn-all-bad-shape` — see [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all).)

### Deliberate omissions vs xstate

xstate's `invoke` admits several features re-frame2 omits. Each has a substitute that fits re-frame2's existing primitives:

| xstate feature | re-frame2 substitute |
|---|---|
| **`onDone`** — fire a callback when the child reaches a final state | re-frame2 ships first-class final states with parent notification — see [§Final states (`:final?` / `:on-done` / `:output-key`)](#final-states-final--on-done--output-key). A leaf state declares `:final? true` (and optionally `:output-key`); the parent's `:spawn` declares `:on-done (fn [{data :data result :result}] new-data)`. The runtime invokes `:on-done` synchronously when the child enters its final state, then auto-destroys the child. |
| **Compound / parallel `onDone`** (`done.state.<id>` raised *into the same machine* when a compound reaches its `<final>` child — or a parallel reaches all-regions-final — so an enclosing transition advances *without* tearing the machine down) | **Shipped first-class** — see [§The done-state signal](#the-done-state-signal). Declare `:on-done` on the compound (resolved at the compound's level — a keyword target is a sibling) or on the parallel root (action + fx; root-only parallel has no in-machine target). The runtime raises `[:rf.machine/done <node-path>]` into the FIFO `:raise` queue, so the enclosing `:on-done` fires in the same macrostep and the machine keeps running. The depth of the `:final?` leaf disambiguates compound-done (embedded) from whole-machine finality (top-level) — the [§Embedded vs top-level](#embedded-vs-top-level--the-d7-reconciliation) reconciliation. There is no `:raise`-from-non-`:final?`-`:entry` substitute. |
| **`onError`** — child error callback / **transition** | **Shipped first-class** — see [§`:on-error`](#on-error--child-failure-control-flow). Declare `:on-error` on the parent's `:spawn` map as an `:on`-shaped **transition** spec `{:target :guard :action}`. When the spawned child FAILS — it reaches a designated error `:final?` leaf (`:error? true`), or one of its actions throws — the parent **moves to the `:on-error :target`** (resolved at the `:spawn`-bearing state's level), with the error payload on the transition's `:event`. This is XState v5's `invoke onError` semantics: a transition (control flow), not merely an observable. Errors STILL flow through the `:rf.error/*` machinery + trace events (observability is unchanged — `:on-error` is additive), and the explicit dispatch-back-to-parent (`[:fx [[:dispatch [parent-id [:failed err]]]]]`) remains the lower-level escape hatch. |
| **Multiple `:spawn` per state** (xstate admits a vector) | One `:spawn` per state. Multiple actors per state suggests refactoring into a compound state where each substate invokes one of the actors. |
| **`autoForward`** — forward all parent events to the child | Users forward explicitly via `:fx [[:dispatch [child-id ev]]]` from the relevant transitions. Implicit forwarding is invisible at the call site; explicit forwarding is what visualisers and AIs read. |

Each omission is consistent with the spec's broader bias: **prefer one explicit primitive over many implicit conveniences.** The substitutes use mechanisms already required for spawn / destroy / `dispatch` / `:raise`; `:spawn` is the *only* new sugar in this area, and even it is desugared at construction time.

### Worked example — declarative login flow

```clojure
{:initial :idle
 :states
 {:idle  {:on {:submit :authenticating}}

  :authenticating
  {:spawn {:machine-id :http/post
            :data       (fn [{snap :snapshot}]
                          {:url  "/api/login"
                           :body (-> snap :data :credentials)})
            :system-id  :auth-actor}    ;; address the child by name
   :on     {:auth/succeeded :authenticated
            :auth/failed    :idle}}

  :authenticated {...}}}
```

The walk-through:

1. User submits → state moves `:idle` → `:authenticating`.
2. Entering `:authenticating` triggers the desugared entry: spawn an `:http/post` actor with the credentials from `:data`; the runtime binds the spawned id at `[:rf.runtime/machines :spawned :login [:authenticating]]` in the frame's runtime-db and registers the `:system-id :auth-actor` name, so other transitions in the parent can address the child via `(rf/machine-by-system-id :auth-actor)`.
3. The HTTP child runs; on success, it dispatches `[:login [:auth/succeeded ...]]` (where `:login` is the parent machine's id).
4. The login machine handles `:auth/succeeded`; transitions to `:authenticated`.
5. Leaving `:authenticating` triggers the desugared exit: the runtime reads the actor id back from `[:rf.runtime/machines :spawned :login [:authenticating]]`, destroys it, clears the slot, and releases the `:auth-actor` `:system-id` binding. The HTTP child's snapshot is removed from `[:rf.runtime/machines :snapshots]` automatically — no stale id lingers in the parent's `:data`.
6. If the user abandons mid-flight (a different transition fires `:authenticating` → `:idle`), the exit cascade still runs; the in-flight HTTP child is destroyed; no actor leaks.

The key property: the parent does not have to *remember* to destroy the child. The lifecycle binding is declared once at the state level, and the exit cascade enforces it on every code path out of the state — including ones the author hasn't yet thought of.

Per, the framework's `:rf.http/managed` ships as **both** an fx AND a child-invokable machine for exactly this pattern — apps do not hand-roll the HTTP-child wrapper:

```clojure
{:authenticating
 {:spawn {:machine-id :rf.http/managed
           :data       {:request {:method :post :url "/api/login"
                                  :body credentials}}}
  :on     {:succeeded :authenticated
           :failed    :idle}}}
```

See [Spec 014 §Machine-shape wrapper](014-HTTPRequests.md#machine-shape-wrapper) for the wrapper's contract; the wrapper's terminal `:succeeded` / `:failed` events arrive at the parent exactly as the hand-rolled HTTP child machine would emit them.

Cross-references: [§Spawning](#spawning--dynamic-actors) for the imperative-spawn surface that `:spawn` desugars to; [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit) for the auto+manual ordering rule; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) for the `:spawn` schema. [Pattern-WebSocket](Pattern-WebSocket.md) is the canonical worked example exercising hierarchical states, `:after`, `:always`, machine-scoped `:guards` / `:actions`, and `:spawn` together — the connection-lifecycle state machine for long-lived sockets. [Pattern-LongRunningWork](Pattern-LongRunningWork.md) is the canonical worked example for chunked CPU-intensive work — `:always` for batch progression, `:after 0` for browser yielding between chunks, machine-scoped guards for completion / cancellation.

## Final states (`:final?` / `:on-done` / `:output-key`)

re-frame2 ships first-class **final states** with parent notification — the xstate-style "child reaches `done`; parent sees `onDone`" pattern.

### The grammar

A leaf state may declare `:final? true`. Entering that state **terminates the machine**:

- If the machine was spawned by a parent's `:spawn`, the parent's `:spawn :on-done (fn [{data :data result :result}] new-data)` fires (with `result` = the child's `:data` slot named by the final state's `:output-key`, or `nil` when `:output-key` is absent). The child is then **auto-destroyed**.
- If the machine is a **singleton** (registered top-level, no parent `:spawn`), the machine still auto-destroys on entry to `:final?` — "final means final" (D7 below). Apps wanting a persistent terminal state simply **omit `:final?`** and use an ordinary leaf state.

A `:final?` leaf may additionally declare **`:error? true`** — a designated **error terminal** (re-frame2's spelling of XState v5's error final). When a spawned child finishes via an error leaf AND its spawning parent declares `:spawn :on-error`, the runtime routes the failure to the parent's **`:on-error` transition** rather than the `:data`-only `:on-done` callback — see [§`:on-error`](#on-error--child-failure-control-flow). An error leaf with no parent `:on-error` behaves exactly like any other `:final?` leaf (auto-destroy + the `:rf.machine/done` trace, which carries `:error? true`). `:error?` on a non-final state is a registration error (`:rf.error/machine-error-flag-without-final`, symmetric with `:output-key`).

```clojure
;; Child machine — declares its terminal state with :final? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data ev :event}]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})

;; Parent machine — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle
    {:on {:submit :authenticating}}

    :authenticating
    {:spawn {:machine-id :auth-flow
              :on-done    (fn [{data :data result :result}] (assoc data :token result))}
     :on    {:auth/cancelled :idle}}}})
```

When `:auth-flow` enters `:done`, the runtime:

1. Reads the child's `:data` at `:output-key :token` — call it `result`.
2. Looks up the parent's `:spawn` at the `:rf/invoke-id` recorded on the child's `:data` (stamped at spawn time).
3. Runs the parent's `:on-done` against the parent's `:data` with `result` — the returned map replaces the parent's `:data` slot.
4. Emits `:rf.machine/done` (per [§Trace events](#trace-events)) with `:machine-id` (the child), `:output result`, `:parent-id`.
5. Tears down the child via the existing destroy path with `:reason :rf.machine/finished` enriched onto the `:rf.machine/destroyed` trace.
6. Clears the child's `[:rf.runtime/machines :system-ids <sid>]` reverse-index entry (if it had one) **after** step 3 — so `:on-done` can still read the binding.

### Sub-decisions (locked)

| # | Decision |
|---|---|
| D1 | **`:final?` is a first-class key on the state node**, not stashed under `:meta`. Visibility wins — `:final?` is a strong runtime signal and authors / AI agents see it at the state level. |
| D2 | The parent-notification hook is **`:on-done` on the parent's `:spawn` map** (mirrors `:on-spawn`). Signature `(fn [{:keys [data result]}] new-data)` — unified context-map; returns the parent's new `:data` map. |
| D3 | Output is sourced via **`:output-key` on the child's final state** — a designated key into the child's `:data`. There is **no `:output-fn` escape hatch**; one explicit primitive, not two. Apps wanting computed output write a `:action` on the transition INTO the final state that stashes the computed value at `:output-key`. |
| D4 | **Auto-destroy is synchronous** and happens on the same tick the machine entered `:final?`. The standard destroy path runs (in-flight HTTP aborts, registrar unregister, `[:rf.runtime/machines :spawned ...]` slot clear, `[:rf.runtime/machines :snapshots <id>]` snapshot dissoc). |
| D5 | A dispatch arriving at the now-destroyed actor address is handled by the **existing destroyed-frame trace path** — `:rf.error/no-such-handler` (or the per-runtime equivalent). No new `:rf.machine/dispatched-while-done` half-state is introduced. |
| D6 | **New trace event `:rf.machine/done`** carries `:machine-id`, `:output`, `:parent-id` (the parent's registration id, or `nil` for singletons). The existing `:rf.machine/destroyed` trace is **enriched** with a `:reason` tag — one of `:rf.machine/finished`, `:explicit`, or `:parent-unmount-cascade`. |
| D7 | **Singleton symmetry** — a singleton (non-spawned, non-invoked) machine reaching `:final?` ALSO auto-destroys. Footgun note for skill docs: *if you want a persistent terminal state, omit `:final?`*. |
| D8 | **`:system-id` interaction** — the runtime auto-clears `[:rf.runtime/machines :system-ids <system-id>]` reverse-index entry on done. The clear runs **after `:on-done` fires** so the hook can still read the binding. |
| D9 | Specified and implemented in one delivery (no post-v1 deferral). |
| D10 | Capability-matrix axis: **`:fsm/final-states`** (naming consistent with `:fsm/parallel-regions`, `:fsm/tags`). Conformance fixtures `final-state-singleton-auto-destroys` and `final-state-child-fires-on-done` exercise the contract. |

### `:on-error` — child-failure control flow

> **XState term:** `invoke onError` (a **transition** the parent takes when an invoked actor errors; present in both v5 and the v6 direction). `:on-error` is a transition the parent takes when a spawned child errors (XState `invoke onError` parity), not observability-only.

`:spawn :on-done` is the *success* notification — a `:data`-only callback the parent runs when a spawned child reaches a (non-error) `:final?` state. `:spawn :on-error` is its **symmetric failure counterpart**, but a **transition** rather than a callback: when a spawned child FAILS, the parent **changes state** declaratively, at the `:spawn` site. This is XState's `invoke onError` — the parent reacts to the child failing by transitioning, not merely observing an error envelope.

**The grammar — an `:on`-shaped transition on the parent's `:spawn` map.** `:on-error` sits beside `:on-done` on the parent's `:spawn` map. Its value is an `:on`-shaped transition spec — a keyword target, a vector-path target, a single transition map `{:target :guard :action}`, or a guarded candidate vector — resolved **relative to the `:spawn`-bearing state's own level** (a keyword target is a **sibling** of the spawning state, the natural *"child failed → move the parent out of the spawning state"* placement). It is normalised + guard-resolved through the **same candidate machinery as an `:on` clause**, so first-guard-pass-wins and an unguarded candidate is the unconditional fallback.

```clojure
;; Child declares a designated error terminal with :error? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-error {:target :failed
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :reason (second ev))})}
                   :server-ok    {:target :done
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :token (second ev))})}}}
    :done    {:final? true :output-key :token}
    :failed  {:final? true :error? true :output-key :reason}}})  ;; ← error terminal

;; Parent: :on-done (success → :data) AND :on-error (failure → transition).
(rf/reg-machine :login
  {:initial :idle
   :data    {}
   :states
   {:idle {:on {:submit :authenticating}}

    :authenticating
    {:spawn {:machine-id :auth-flow
              :on-done    (fn [{data :data result :result}] (assoc data :token result))
              :on-error   {:target :error                    ;; ← sibling of :authenticating
                           :action (fn [{data :data ev :event}]
                                     ;; ev = [:rf.machine.spawn/error <invoke-id> <error>]
                                     (assoc data :error (nth ev 2)))}}}

    :error {:on {:retry :authenticating}}}})
```

**Two failure triggers** (both route to the same `:on-error` transition):

1. **The child reaches a designated error `:final?` leaf** (`:error? true`). The error payload is the child's `:data` slot named by that leaf's `:output-key` (or `nil`). The child auto-destroys (it reached `:final?`), then the failure routes to the parent.
2. **An uncaught child action exception** (`:rf.error/machine-action-exception`). The exception envelope (`{:rf.error/id :machine-id :action-id :event :exception-message :exception-data :reason}`) is the error payload. (This was *observability-only* before the onError ruling — the action-exception trace fired but nothing drove a parent transition.)

**The mechanism — a dispatch into the parent.** The failure is delivered as the reserved event `[<parent-id> [:rf.machine.spawn/error <invoke-id> <error>]]` dispatched into the parent (a **separate** actor with its own handler / snapshot — so a *dispatch*, symmetric with how the spawn fx dispatches `:start` into the newborn child, not the in-machine `[:raise …]` the compound/parallel `done.state` uses). The parent's macrostep resolves it natively: `:rf.machine.spawn/error` routes to the active `:spawn`-bearing state's `:on-error`, firing the transition with full engine semantics — entry/exit cascade, `:always` / `:raise` drain, traces, and the parent's own finalize. The error payload rides on the transition's `:event` (`(nth ev 2)`), so a guard / action can branch on it.

**Two resolution arms** (priority order — symmetric with the compound `done.state` resolution):

1. **`:on-error` on the parent's `:spawn` map** — the headline spelling above.
2. **An enclosing explicit `:on {:rf.machine.spawn/error {:guard … :target …}}`** — the lower-level escape hatch. When no `:spawn :on-error` is declared (or its candidates all guard-fail), the dispatched event walks the active path leaf→root (then the root `:on`) like any event, so an ancestor can handle `:rf.machine.spawn/error` directly. A guard reads the invoke-id / error off `:event` to disambiguate which spawn failed.

**`:on-error` is additive — the escape hatch is preserved.** Declaring `:on-error` does NOT replace the lower-level forms. The `:rf.machine/done` / `:rf.error/machine-action-exception` traces STILL fire (observability is unchanged). And the explicit dispatch-back-to-parent — a child action emitting `[:fx [[:dispatch [parent-id [:failed err]]]]]` and the parent declaring `:on {:failed :error}` — keeps working. `:on-error` is the *declarative invoke-site* control-flow form; the explicit dispatch is the *lower-level* form. Choose `:on-error` for the canonical XState-shaped failure routing; reach for the explicit dispatch when the child needs to send a richer, app-shaped failure event the parent's normal `:on` table handles.

**Success vs failure are mutually exclusive per finish.** A child reaching a **plain** `:final?` leaf fires `:on-done` (success → `:data` callback). A child reaching an `:error?` `:final?` leaf fires `:on-error` (failure → transition) and SKIPS `:on-done`. A child throwing fires `:on-error`. (`:on-done` and `:on-error` may both be declared on one `:spawn` map — the runtime picks the right one by how the child finished.)

### The done-state signal

> **XState term:** `onDone` (on a compound or `parallel` state; present in both v5 and the v6 direction). **SCXML §3.7:** reaching a `<final>` child — or, for `<parallel>`, all-regions-final — generates `done.state.<id>` into the internal event queue.

The whole-machine final case above (D1–D10) is **actor finality** — the machine *finishes* and is torn down (or notifies a *spawning* parent). XState/SCXML also ship a distinct, **transitionable** completion signal: when a **compound** state reaches its `<final>` child — or a **parallel** state reaches all-regions-final — the processor raises `done.state.<id>` *into the machine*, which an enclosing transition can **take**, advancing the outer flow **while the machine keeps running**. This is the canonical *"do these sub-flows, then continue"* pattern. re-frame2 ships it first-class.

**The grammar — `:on-done` on the compound / parallel node.** You declare `:on-done` **on the compound (or parallel-root) node** — the XState `onDone` placement, reading exactly like `:spawn`'s `:on-done`. Its value is an `:on`-shaped transition spec (a keyword target, vector-path target, single transition map `{:target :guard :action}`, or guarded candidate-vector). For a **compound** it is resolved **relative to the compound's own level** — a keyword target is a **sibling** of the compound (the "advance the outer flow" placement).

```clojure
;; A sub-flow inside a compound. When :flow reaches its :final? child, :flow's
;; :on-done advances the machine to the SIBLING :next — same macrostep, no teardown.
(rf/reg-machine :checkout
  {:initial :flow
   :states
   {:flow {:initial :collecting
           :on-done :next                       ;; ← sibling of :flow (XState onDone)
           :states  {:collecting {:on {:submit :submitting}}
                     :submitting {:on {:ok :paid}}
                     :paid       {:final? true}}} ;; ← embedded final = sub-flow done
    :next {:on {:reset [:flow]}}}})
```

**The mechanism — a raise into the FIFO queue.** The moment a transition's committed configuration makes a compound newly done (its active direct child is a `:final?` leaf), the engine raises the synthetic event **`[:rf.machine/done <compound-path>]`** into the macrostep's **FIFO `:raise` queue** (the same queue `[:raise …]` from an action uses). It is drained FIFO *before the macrostep settles* (per [§Drain semantics](#drain-semantics)), so the enclosing `:on-done` transition fires **in the same macrostep**, deterministically, bounded by `:raise-depth-limit`, committed atomically. re-frame2's `[:rf.machine/done <path>]` is the spelling of XState's `done.state.<id>` / SCXML's `done.state.id`: the node *id* is its declaration path, carried as the event's single arg (so the `:on` table stays keyed on one reserved keyword and the resolver routes by the path).

**Two resolution arms** (priority order):

1. **`:on-done` on the done node** — the headline spelling above.
2. **An enclosing explicit `:on {:rf.machine/done {:guard … :target …}}`** — the lower-level escape hatch. When the done node declares no `:on-done`, the raised event walks the active path leaf→root (then the root `:on`) like any event, so an ancestor can handle `:rf.machine/done` directly. A guard reads the raised path off `:event` (`(fn [{ev :event}] (= [:outer :flow] (second ev)))`) to disambiguate *which* node is done.

**Parallel `:on-done`.** When **every region** of a `:type :parallel` machine reaches its `:final?` leaf, the **parallel root's `:on-done`** fires — the *"do these axes in parallel, then continue"* pattern. Because a `:type :parallel` machine is **root-only** (no nested parallel; the root carries `:regions`, not `:states`), the parallel root has no sibling flat state to land an in-machine `:target` on — so a parallel root's `:on-done` runs its **`:action`** (a `:data` write) and emits its **`:fx`**; the "then continue" is a dispatch / raise in that fx to a coordinator (the re-frame2-idiomatic effects-as-data continuation). Registration rejects a `:target` on a parallel root's `:on-done` (`:rf.error/machine-parallel-on-done-target`). The machine **stays** in the all-final configuration — the natural stable "complete" resting state.

The parallel root's `:on-done` fires **exactly once** — on the macrostep that **enters** the all-regions-final done configuration (the false→true edge), matching XState v5 `onDone` / SCXML `done.state.<parallelId>`, which raise once when the done condition is first satisfied. A subsequent external event delivered **while the machine rests** in the all-final config (every region declines it — `:final?` leaves carry no `:on`) does **not** re-run `:on-done`'s `:action` and does **not** re-emit its `:fx`. The done signal is tied to *entering* the configuration, never to *resting* in it — so a coordinator's "continue" dispatch is not re-issued on every later event and any `:on-done` `:data` accumulation does not drift. (A machine born already all-regions-final fires `:on-done` once at birth — the birth macrostep is the entry edge.)

The parallel root's `:on-done` **is a transition** — XState v5's `onDone` is a transition, and an embedded compound's `:on-done` already flows through the ordinary transition machinery. Because the root-only parallel has no sibling flat state, the root `:on-done` carries **no in-machine `:target`** (a `:target` is a registration error — [§`:final?` constraints](#final-constraints)); it runs the selected candidate's `:action` and collects its `:fx`. That action runs under the action driver **phase `:transition`** (its `:rf.machine/action-ran` trace stamps `:phase :transition`) — the SAME phase an embedded compound's `:on-done` action runs under, and a member of the documented closed [`MachineActionRanTags`](Spec-Schemas.md) `:phase` enum (there is no separate `:on-done` phase). The action's effects are subject to the **`:db` hard-disallow** uniformly: an `:on-done` action that returns `:db` emits [`:rf.error/machine-action-wrote-db`](#strict-encapsulation--actions-only-see-their-own-data) and the `:db` key is stripped, exactly as for any other action phase — the parallel-root path does not bypass the validation.

```clojure
;; Three orthogonal axes run in parallel; when ALL settle final, :on-done's
;; action emits the "continue" dispatch. The machine survives (no teardown).
(rf/reg-machine :ingest
  {:type    :parallel
   :data    {}
   :actions {:announce (fn [{d :data}] {:data d :fx [[:dispatch [:pipeline/ingest-complete]]]})}
   :on-done {:action :announce}                 ;; ← parallel-root onDone (action + fx only)
   :regions
   {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
    :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}
    :index    {:initial :building :states {:building {:on {:built :done}} :done {:final? true}}}}})
```

A **compound region** reaching its own `:final?` child raises a region-local `done.state.<region-compound>` that the region's `:on-done` takes (re-broadcast through the parent's one internal-event queue per [§Parallel-region `:raise` broadcast](#per-region-always--after--spawn-scoping)) — exactly the compound case, scoped to one region; sibling regions are untouched.

### Embedded vs top-level — the D7 reconciliation

The done-state signal and whole-machine finality (D1–D10) are **two distinct concepts keyed on the `:final?` leaf's DEPTH**:

| `:final?` leaf placement | Meaning | Effect |
|---|---|---|
| **Direct child of the machine root** (length-1 path) | whole-machine finality (the actor *finishes*) | **auto-destroy** (singleton, D7) / spawning parent's **`:spawn :on-done`** + teardown |
| **All regions of a `:type :parallel` root final** | the parallel state is done | parallel root's **`:on-done`** fires (machine survives); else whole-machine auto-destroy / spawning-parent `:on-done` (D7) |
| **Embedded inside a compound** (depth ≥ 2) | compound `done.state.<compound>` signal | raise `[:rf.machine/done <compound>]`; enclosing **`:on-done`** advances; **machine keeps running** |

D7 ("singleton symmetry — final means final, auto-destroy") is therefore **preserved unchanged for top-level finals** — a singleton or spawned actor reaching a root-level `:final?` leaf still tears down. An embedded `:final?` leaf does **not** force whole-machine destroy; it signals compound-done. You do not have to *avoid* `:final?` to keep the machine alive after a sub-flow completes. The runtime gates the two paths on `top-level-final?` (length-1 final) vs `compound-done-paths` (embedded final) at the lifecycle-handler boundary; finality stays a pure recompute from `:state` (no `:rf/finished?` snapshot slot, per [§Composition with persistence, SSR, and time-travel](#composition-with-persistence-ssr-and-time-travel)).

The two `:on-done` hooks are **distinct and complementary**: a node's **own `:on-done`** (on the compound / parallel node) is the *transitionable in-machine* signal; the **`:spawn :on-done`** (on a parent's `:spawn` map) is the *actor-teardown notification* a parent receives when a spawned child finishes. A spawned **parallel** child whose root declares its own `:on-done` fires that (and survives) rather than notifying the spawning parent — choose the hook by intent.

### `:final?` constraints

- **Leaf-only.** A state declaring `:final? true` MUST NOT declare `:states` (or `:initial`). Compound states cannot themselves be final — their finality is expressed by a leaf inside them. `make-machine-handler` rejects compound `:final?` declarations at registration with `:rf.error/machine-final-state-compound`.
- **A `:final?` leaf's meaning depends on its DEPTH (the embedded-vs-top-level rule).** A `:final?` leaf that is a **direct child of the machine root** is **whole-machine finality** (singleton auto-destroy per D7, or the spawning parent's `:spawn :on-done`). A `:final?` leaf **embedded inside a compound** instead raises a transitionable in-machine `done.state.<compound>` — the enclosing `:on-done` advances the outer flow **and the machine keeps running**. So to model "a *sub-flow* completes, then the outer flow continues *in the same machine*" you mark the sub-flow's terminal leaf `:final?` and declare `:on-done` on the enclosing compound — the natural XState `onDone` shape. See [§The done-state signal](#the-done-state-signal) and [§Embedded vs top-level](#embedded-vs-top-level--the-d7-reconciliation).
- **No `:on`, `:always`, `:after`, `:spawn`, `:spawn-all` on a `:final?` state.** Final means final — no further transitions. `make-machine-handler` rejects these combinations at registration with `:rf.error/machine-final-state-has-transitions`. `:entry` and `:exit` actions ARE permitted (the final-state's `:entry` runs as part of the entering cascade; `:exit` runs from the auto-destroy teardown).
- **`:output-key` requires `:final?`.** A non-final state declaring `:output-key` is a registration error (`:rf.error/machine-output-key-without-final`). On a final state, `:output-key` is optional — when absent, the `result` passed to `:on-done` is `nil`.
- **`:error?` requires `:final?`.** A `:final?` leaf MAY declare `:error? true` to mark it an **error terminal** (XState v5's error final) — a child finishing via it routes to the spawning parent's `:spawn :on-error` transition (see [§`:on-error`](#on-error--child-failure-control-flow)) instead of `:on-done`. `:error?` on a non-final state is a registration error (`:rf.error/machine-error-flag-without-final`, symmetric with `:output-key`). An error leaf MAY also carry `:output-key` (to carry the error payload) and `:entry` / `:exit`.
- **Parallel regions and `:final?`.** A leaf inside one region of a parallel-region machine may declare `:final? true`; the meaning is "**this region** has reached its final state." That region halts (no further transitions accepted for it; sibling regions continue). The parent machine as a whole is done only when EVERY region's active state is `:final?` — at which point the parallel root's `:on-done` fires if declared (the transitionable parallel completion signal — the machine keeps running, per [§The done-state signal](#the-done-state-signal)), else the whole-machine auto-destroy / spawning-parent `:on-done` cascade fires (D7). A **compound region** reaching its own `:final?` child raises a region-local `done.state.<region-compound>` its region-local `:on-done` takes — exactly the compound case, scoped to that region (re-broadcast through the parent's one internal-event queue).

### Composition with `:entry` / `:exit`

A final state's `:entry` action runs as part of the entering cascade (before the auto-destroy fires). A final state's `:exit` action runs from the auto-destroy teardown — same ordering convention as the user-supplied `:exit` running before the auto-destroy for ordinary `:spawn`-bearing states. The user's `:exit` therefore gets to read the final snapshot (including `:data`'s `:output-key`-designated slot) before auto-destroy clears it.

### Trace events fired on done

Synchronous ordering (per D4):

1. `:rf.machine/done` — emitted with `:machine-id` (the finishing actor), `:output` (the value read at `:output-key`, or `nil`), `:parent-id` (the parent's registration id, or `nil` for singletons), `:error?` (`true` when the actor finished via an `:error?` `:final?` leaf — see [§`:on-error`](#on-error--child-failure-control-flow); `false` otherwise). The done trace fires for EVERY finish — `:on-error` is additive and does not suppress it.
2. `:rf.machine/destroyed` — enriched with `:reason :rf.machine/finished` (the discriminator that distinguishes "the actor finished naturally" from "the parent cascade destroyed it").
3. `:rf.machine/system-id-released` — when the actor was `:system-id`-bound. Fires AFTER `:on-done` ran (so `:on-done` could still look up the binding).

Existing observers that filter `:rf.machine/destroyed` on `:tags` see the new `:reason` tag additively — no breaking change.

### Cross-references

- [§The done-state signal](#the-done-state-signal) — the transitionable compound / parallel `:on-done` (`done.state.<id>`), distinct from the whole-machine finality above.
- [§Embedded vs top-level](#embedded-vs-top-level--the-d7-reconciliation) — how the `:final?` leaf's depth selects compound-done-signal vs whole-machine teardown.
- [§`:on-error`](#on-error--child-failure-control-flow) — the symmetric child-FAILURE control-flow hook (`:spawn :on-error` transition; `:error?` error terminal), distinct from the `:data`-only success notification `:on-done`.
- [§Spec-spec keys](#spec-spec-keys) — `:on-done` and `:on-error` are listed alongside `:on-spawn` on the parent's `:spawn` map.
- [§Deliberate omissions vs xstate](#deliberate-omissions-vs-xstate) — the `onDone` row now records that re-frame2 DOES ship final-state-with-on-done AND first-class compound / parallel `done.state`; the `onError` row records that `:spawn :on-error` ships first-class (control flow, not observability-only).
- [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) — schema for `:final?`, `:output-key`, `:error?`, and the `:on-done` / `:on-error` node keys.
- [Spec 009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) — `:rf.machine/done` registration (the actor-finality trace; the in-machine `[:rf.machine/done <path>]` raise rides the standard `:raise` queue, not a separate trace family).
- Conformance fixtures: `final-state-singleton-auto-destroys.edn`, `final-state-child-fires-on-done.edn`.
- [§Destroy is silent-idempotent](#destroy-is-silent-idempotent) — D4's auto-destroy is followed at most ONCE by an observable `:rf.machine/destroyed` trace; subsequent explicit `[:rf.machine/destroy <id>]` calls on the same finished actor are silent no-ops (aligned with XState convention).
- [§Async completions share the uniform reply envelope](#async-completions-share-the-uniform-reply-envelope) — how this success / error completion lowers onto the framework-wide [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) **internally** (work id `[:rf.work/machine …]`, `:status :ok` / `:error`, late-completion staleness) while preserving the public `:on-done` / `:on-error` semantics described here.

## History states (`:type :history` — shallow / deep / default-target)

re-frame2 ships first-class **history states** — the xstate / SCXML pattern for re-entering a compound state at the substate it was in when control last left it, rather than restarting from `:initial`. History is a declarative grammar node the runtime records and restores; there is no hand-rolled snapshot-as-value substitute (see [§Why first-class history replaces the snapshot-as-value substitute](#why-first-class-history-replaces-the-snapshot-as-value-substitute) below).

### The grammar — a targetable pseudo-state

A history state is a **pseudo-state**: a node declared under a compound state's `:states` map, alongside that compound's real substates, whose role is to be a **transition target** that resolves to a recorded configuration rather than to a state the machine ever occupies. The machine's `:state` path is never `[… :hist]`; a transition *to* `:hist` resolves to the recorded (or default) leaf, and that resolved leaf is what the snapshot records.

```clojure
{:player
 {:initial :stopped
  :states {:stopped {:on {:play [:player :playing :hist]}}  ;; transition targets the pseudo-state to restore
           :playing {:initial :at-start                      ;; the history-OWNING compound — :stop exits it
                     :on      {:stop [:player :stopped]}
                     :states {:hist      {:type :history
                                          :deep? true             ;; omit => SHALLOW history
                                          :default-target :at-start} ;; omit => falls back to :playing's :initial
                              :at-start  {:on {:seek :mid-track}}
                              :mid-track {}}}}}}
```

The history pseudo-state lives **under the compound it remembers** (`:playing`), so that a transition leaving that compound (here `:stop`, exiting `:playing` for its sibling `:stopped`) genuinely puts `:playing` in the exit set and records its last-active leaf — see [§Recording — on compound-state exit](#recording--on-compound-state-exit). Re-entering via `[:player :playing :hist]` restores it.

The pseudo-state node carries exactly three keys, all owned by the history grammar:

| Key | Value | Meaning |
|---|---|---|
| `:type` | `:history` | Marks the node as a history pseudo-state. Required — this is the discriminator that distinguishes the node from a real substate. |
| `:deep?` | `boolean` | `true` => **deep** history (restore the full recorded leaf path beneath the compound); `false` or **absent** => **shallow** history (restore the recorded *direct child* of the compound, then cascade through that child's own `:initial` chain). The default is **shallow** — a missing `:deep?` reads as `:deep? false`. |
| `:default-target` | `<transition-target>` | The target used when the compound state has **never been entered** (so nothing is recorded yet). A keyword (sibling of the compound — i.e. a direct child) or a vector (absolute path). When **absent**, the fallback is the owning compound state's `:initial`. |

A transition resolves to the pseudo-state by naming it the way any other state is named — a vector `[:player :hist]` (absolute path) or, from a sibling inside the compound, a keyword `:hist` (per [§Target resolution — vector vs keyword](#target-resolution--vector-vs-keyword)). The pseudo-state is **resolved at transition time** to a real leaf path; the resolved path is what the entry cascade enters and what the snapshot's `:state` records.

### Recording — on compound-state exit

Whenever the exit cascade (per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)) **exits** a compound state that owns a history pseudo-state — i.e. that compound is in the transition's **exit set** — the runtime **records that compound's last-active configuration** — the active substate path *beneath* the compound at the moment of exit — into the reserved snapshot slot `:rf/history` (per [§The `:rf/history` snapshot slot](#the-rfhistory-snapshot-slot) below). The recording is keyed by the compound's **declaration path** (the absolute prefix-path of the compound state-node in the transition table), so a machine with several history-bearing compounds records each independently.

The trigger is the compound's own **exit**, not merely the teardown of its current child subtree. A compound records iff it lies **strictly below** the transition's LCA (its declaration path is longer than the LCA path) — that is, iff it is genuinely left. A transition that merely moves between two **children** of compound `C` keeps `C` as the LCA, so `C` **survives** and records **nothing**: there is no last-active configuration to remember because the compound was never left. This is the [XState v5](https://stately.ai/docs/history-states) / [W3C SCXML](https://www.w3.org/TR/scxml/#history) gold-standard semantic — a `<history>` value is written only for states in the exit set (SCXML Appendix D writes `historyValue` while iterating `statesToExit`); history means *"where this compound was when we left it"*, so the compound must actually be left.

- A **deep**-history compound records the **full leaf path** the machine occupied beneath itself (e.g. the absolute path `[:player :playing :mid-track]` for `:playing`'s history).
- A **shallow**-history compound records only its **direct child** (e.g. `:at-start`); on restore the runtime cascades from that child through its own `:initial` chain.

Recording happens **as part of the exit cascade's commit**, on the same drain that exits the compound — there is no separate write phase. Because `:rf/history` lives inside the snapshot (a revertible value), the recording rides every persistence and time-axis path that the snapshot itself rides (per [§Composition with persistence, SSR, and time-travel](#composition-with-persistence-ssr-and-time-travel)).

A compound state that owns **no** history pseudo-state records nothing — the slot stays absent for that compound's path. The slot is **fixed-and-additive** and framework-owned; user code MUST NOT write under it.

### Restoring — on transition to the pseudo-state

When a transition resolves to a history pseudo-state declared under compound `C`, the runtime resolves the target leaf as follows:

1. **A recording exists for `C`'s declaration path** (`C` has been entered and exited at least once, and the recorded path is still valid against the current definition):
   - **Deep** — the target leaf is the full recorded path. The entry cascade enters every level from `C` down to that leaf, firing each level's `:entry` shallowest-first (per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)).
   - **Shallow** — the target is the recorded direct child of `C`; the runtime then cascades through that child's `:initial` chain to a leaf (per [§Initial-state cascading](#initial-state-cascading)). If the recorded direct child is itself a leaf, the cascade terminates there.
2. **No recording exists for `C`** (the compound was never entered, so nothing was recorded) — the runtime resolves the pseudo-state's `:default-target`; if `:default-target` is absent, it falls back to `C`'s `:initial` and cascades from there exactly as an ordinary entry to `C` would.
3. **A recording exists but is no longer a valid path** in the current (e.g. hot-reloaded) definition — the runtime treats it as "no recording" and falls back to `:default-target` / `:initial` per (2). See [§Dangling recorded paths after hot reload](#dangling-recorded-paths-after-hot-reload).

In every case the **resolved** leaf path — not the pseudo-state — is what the entry cascade enters and what the post-transition snapshot's `:state` records. The pseudo-state node is never a member of any `:state` configuration; it has no `:entry` / `:exit` / `:on` / `:always` / `:after` of its own (see [§Pseudo-state constraints](#pseudo-state-constraints)).

### Composition with the LCA, entry/exit cascade, and final states

History restoration is **not a new cascade mechanism** — it is a target-resolution step that feeds the existing entry-cascade machinery. Once the pseudo-state resolves to a concrete leaf path, the standard geometry applies unchanged:

- **LCA + cascade ordering.** The transition's LCA is computed (per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)) between the source path and the **resolved** target leaf — exactly as if the author had written the resolved path as a literal `:target`. The exit cascade fires deepest-first from the source leaf back to the LCA; the transition `:action` runs at the LCA boundary; the entry cascade fires shallowest-first from below the LCA down to the resolved leaf, continuing through any `:initial` chain (shallow case). A history-owning compound on the source path records **only if it is in the exit set** — i.e. it lies strictly below the LCA and is therefore left by this transition. The LCA compound itself **survives** and records nothing, even though its current child subtree is torn down (per [§Recording — on compound-state exit](#recording--on-compound-state-exit) — the exit-set rule). Consequently a restore-to-history transition, which *enters* (rather than exits) the history-owning compound, records nothing for that compound — re-entry leaves the recorded slot untouched.
- **Deep nesting.** A deep-history compound nested several levels down records and restores its full subtree path relative to itself; an outer compound's own history (if any) records independently, keyed by its own declaration path. The two never interfere — each compound's recording is a separate entry in the `:rf/history` map.
- **Final states.** Entering a `:final?` leaf inside a history-bearing compound records normally on the way in only if a later exit occurs; in practice a singleton reaching `:final?` auto-destroys (per [§Final states](#final-states-final--on-done--output-key)) and the snapshot is dissoc'd, so its `:rf/history` goes with it. Restoring a recorded path whose leaf was `:final?` is a no-op edge the runtime handles via the dangling-path fallback (a `:final?` leaf the definition still declares restores like any other leaf; one the definition removed falls back to `:default-target` / `:initial`).
- **`:always` / `:after`.** The resolved leaf's `:always` entries are checked after the restoring entry cascade settles (microstep loop, per [§Eventless `:always` transitions](#eventless-always-transitions)); its `:after` timers are scheduled at entry (per [§Delayed `:after` transitions](#delayed-after-transitions)) — identical to entering that leaf by any other path.

### Composition with parallel regions — per-region history

Under a `:type :parallel` machine each region runs an independent state-tree (per [§Parallel regions](#parallel-regions)), so history is **per-region**: a history pseudo-state is declared inside a region's compound state, records that region's last-active configuration on the region's own exit cascade, and restores that region independently of its siblings. The `:rf/history` slot's keys are therefore **region-qualified** — a recorded compound's key is its declaration path *including the region name as the head segment* (`[<region-name> … <compound>]`), so two regions that each declare a history-bearing compound at structurally-identical paths never collide. A transition that restores history in one region leaves sibling regions untouched, matching the per-region scoping of `:spawn` / `:after` / `:always` (per [§Per-region `:always` / `:after` / `:spawn` scoping](#per-region-always--after--spawn-scoping)).

### The `:rf/history` snapshot slot

The recorded history lives in a reserved snapshot-root slot, a sibling of `:rf/spawn-counter` and `:rf/machine-type` (per [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys)):

```clojure
{:state [:player :stopped]
 :data  {…}
 :rf/history {[:player :playing] [:player :playing :mid-track]  ;; deep — absolute leaf path; key = the exited compound
              [:player :other]   :paused}}                      ;; shallow — recorded direct child
```

`:rf/history` is a **map** keyed by **compound declaration path** (a vector of keywords) to that compound's **recorded configuration**. It is NOT a single config — a machine may own several history-bearing compounds, each recorded independently; and under `:type :parallel` the keys are region-qualified (head segment is the region name), so per-region recordings never collide. The recorded value is:

- for a **deep** compound, the absolute leaf path the machine occupied beneath that compound when it last exited;
- for a **shallow** compound, the recorded direct child keyword (the runtime cascades its `:initial` chain on restore).

The slot is **read-only for users** (the runtime owns it and writes it during the exit cascade), **EDN-clean** (vectors and keywords only — round-trips through `pr-str` / `read-string` like the other persisting slots), and **absent until a history-bearing compound is first exited** (allocated lazily; a machine with no history pseudo-states never carries the key). See [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the slot schema and [Conventions §Reserved snapshot-internal keys](Conventions.md#reserved-snapshot-internal-keys-machine-runtime) for the catalogue row.

### Composition with persistence, SSR, and time-travel

Because `:rf/history` lives **inside the snapshot** — and the snapshot is a revertible value at `[:rf.runtime/machines :snapshots <id>]` — recorded history rides every path the snapshot itself rides, with no separate machinery:

- **`pr-str` / `read-string` round-trip** — the slot is vectors-and-keywords only, so it survives the wire (invariant 1 of [§Snapshot shape](#snapshot-shape)).
- **SSR hydration** ([011](011-SSR.md)) — a snapshot serialised on the server arrives on the client with its `:rf/history` intact; a subsequent restore-to-history transition resolves against the server-recorded configuration.
- **Tool-Pair epoch replay** ([Tool-Pair.md §Time-travel](Tool-Pair.md#time-travel)) — restoring an earlier epoch restores the `:rf/history` of that epoch along with the rest of the snapshot, so "rewind, then re-enter the compound" replays deterministically. The epoch-restore precondition keys off `:rf/machine-type` (per [§Liveness is derived from runtime-db](#liveness-is-derived-from-runtime-db)), which `:rf/history` does not affect — a history-bearing snapshot is admitted as a valid restore target exactly like any other.

First-class history gets this property **for free** because the recording is part of the snapshot value rather than a side-table.

**Coverage boundary.** The three round-trips above are all properties of the snapshot **value** — `pr-str` / `read-string` symmetry, and that re-running the engine from an *earlier* captured snapshot value resolves against THAT value's `:rf/history` (which is exactly what `restore-epoch!` and SSR hydration each do — rewind / transport the value, then re-enter). The machines reference therefore proves them at the value level (unit tests `history-slot-is-part-of-the-revertible-snapshot-value` and `history-slot-edn-round-trips`), not by driving a live `restore-epoch!` or `render-to-string` round-trip: those surfaces live in the separate `re-frame2-epoch` / `re-frame2-ssr` artefacts, and depending on them from the machines test layer would introduce a cross-artefact (cyclic) test dependency for no additional signal — the value-level proof is the load-bearing one, because `:rf/history` is opaque to both the epoch-restore precondition (which keys off `:rf/machine-type`) and the SSR serialiser (which round-trips the snapshot as data).

### Dangling recorded paths after hot reload

Snapshot stability invariant 3 (per [§Snapshot shape](#snapshot-shape)) says hot-reloading a definition does not invalidate a snapshot whose `:state` is still a member of the new definition. History adds a parallel edge case: a **recorded** configuration in `:rf/history` may reference a substate that a hot-reloaded definition **removed** — a *dangling recorded path*. The restore policy (the history analogue of invariant 3):

> On a restore-to-history transition, if the recorded configuration for the compound is no longer a valid path in the **current** definition, the runtime discards it and falls back to the pseudo-state's `:default-target` — or, when `:default-target` is absent, to the compound's `:initial` — cascading from there exactly as a first-ever entry would. The runtime never enters a dead recorded path.

The recorded slot itself is left in place (it is overwritten on the next genuine exit); only the *restore* is graceful. This policy is pinned here in the keystone; its engine realisation and round-trip / SSR-hydration test coverage are tracked separately, and the dangling-path edge's engine half is left open. A dangling recorded path is a benign, expected consequence of hot reload — not an error; no `:rf.error/*` is raised for it (it is observable through the history trace events per [Spec 009](009-Instrumentation.md)).

### Pseudo-state constraints

`make-machine-handler` validates the history grammar at **registration time** (the same layer that rejects malformed compound states):

- **A `:type :history` node MUST be declared inside a compound state's `:states`** (it has an owning compound whose configuration it records). A history node at the machine root, or under a `:type :parallel` root that has no enclosing compound region, is a registration error.
- **A history pseudo-state declares only `:type` / `:deep?` / `:default-target`.** It MUST NOT declare `:states`, `:initial`, `:on`, `:always`, `:after`, `:spawn`, `:spawn-all`, `:entry`, `:exit`, `:tags`, or `:final?` — it is never occupied, so transition / lifecycle / projection keys are meaningless on it. Any such key is a registration error.
- **`:default-target`, when present, MUST resolve to a real state** — a direct child of the owning compound (keyword form) or an absolute path (vector form) the definition declares. An unresolvable `:default-target` is a registration error.
- **A compound state may declare at most one history pseudo-state.** Two history children under one compound is a registration error (deep-vs-shallow is a property of the single node's `:deep?`, not a reason for two nodes).

These are the registration-time guarantees; the precise error-id catalogue for history-grammar violations is owned by [Spec 009 §Error contract](009-Instrumentation.md#error-contract).

### Why first-class history replaces the snapshot-as-value substitute

First-class history is the contract; there is no snapshot-as-value substitute (capturing a compound's snapshot on the way out and restoring it on the way back in). The reasons first-class history is the right shape rather than a hand-rolled capture/restore:

- **Declarative beats hand-rolled.** `{:type :history :deep? true}` is one node in the transition table; the substitute was per-compound user wiring (an `:exit` action that copies a sub-path, an entry that restores it, plus the bookkeeping to know *which* compound and — under parallel — *which region*). The declarative form is what an AI agent reads and writes confidently; the hand-rolled form is bespoke per machine.
- **Composition the substitute could not give cheaply.** Per-region history under `:type :parallel`, deep nesting, and shallow-vs-deep depth all fall out of the grammar + the existing LCA / cascade machinery. The substitute had to re-implement each of these by hand, correctly, per machine.
- **Tooling legibility.** A `:type :history` node is visible to the machine inspector, the diagram exporter, and the SCXML / xstate corpus (which both have first-class history); a hand-rolled capture/restore is invisible to all of them — it reads as ordinary `:data` shuffling.
- **Parity.** Both SCXML (`<history>`) and xstate (`{type:'history'}`) ship first-class history; matching the shape keeps the conformance corpus and the AI-trained-on-xstate path aligned (per [§Lessons from xstate](#lessons-from-xstate-deliberate-divergences)).

Revertibility (Goal 3) remains the *substrate* that makes history cheap — the recording rides the snapshot for free — but it is the foundation history is **built on**, not a reason to skip the feature. Authors who only need "remember a single flat axis across a leave/return" can still keep that axis in `:data` like any other state; that is ordinary modelling, not a named substitute pattern.

### Capability gating

History states are claimed as **`:fsm/history`** in the v1 CLJS reference per [§Capability matrix](#capability-matrix). A port that does not claim `:fsm/history` rejects a `:type :history` node at registration time with `:rf.error/machine-grammar-not-in-v1` (the same reject-at-registration disposition every other unclaimed capability uses — per [§Error category for unclaimed grammar](#capability-matrix)). The schema extension (the `:rf/state-node` history-pseudo-state arm; the `:rf/machine-snapshot` `:rf/history` slot) is documented in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot).

### Cross-references

- [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca) — the cascade geometry a resolved history target feeds into.
- [§Initial-state cascading](#initial-state-cascading) — the `:initial` chain a shallow restore (and a default-target fallback) cascades through.
- [§Parallel regions](#parallel-regions) and [§Per-region `:always` / `:after` / `:spawn` scoping](#per-region-always--after--spawn-scoping) — per-region history.
- [§Reserved snapshot-internal keys](#reserved-snapshot-internal-keys) — the `:rf/history` slot among the closed `:rf/*` set.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — the history-pseudo-state node schema; [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) — the `:rf/history` slot schema.
- [Conventions §Reserved snapshot-internal keys (machine runtime)](Conventions.md#reserved-snapshot-internal-keys-machine-runtime) — the catalogue row.
- [Spec 009 §Trace events](009-Instrumentation.md) — history `recorded` / `restored` trace events and the history-grammar error catalogue.

## Wall-clock timeouts on `:spawn` — use parent state's `:after`

> **Spawn wall-clock timeout.** This section is retained at its original anchor for stable cross-references; the spawn wall-clock timeout is declared with the first-class **`:timeout` / `:on-timeout`** grammar below, which lowers onto the same parent-state `:after` timer. One mechanism — `:timeout` is the named-intent spelling, `:after` is the general primitive, and both drive one timer.

A wall-clock timeout on a `:spawn`-bearing state is declared with the spawn-level **`:timeout` / `:on-timeout`** grammar (per [§`:timeout` / `:on-timeout`](#timeout--on-timeout-state--spawn)). The spawn `:timeout` is anchored to the spawn-bearing state's **entry** and bounds the child's whole lifetime (spanning any internal retries); when it fires, the standard exit cascade tears down the in-flight child via `:rf.machine/destroy` and the parent transitions to whichever target `:on-timeout` names. The spawn timeout **lowers onto the same `:after` timer mechanism** — there is still ONE timer primitive; `:timeout` is the named-intent grammar that desugars onto `:after`.

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow
          :timeout    "PT30S"                  ;; wall-clock guard — spans retries inside the child
          :on-timeout {:target :auth-failed}}
  :on    {:auth/succeeded :authenticated}}}
```

When the 30-second timeout fires, the parent's exit cascade destroys the `:auth-flow` child (which itself cascades any in-flight `:rf.http/managed` aborts per the [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) contract), and the machine transitions to `:auth-failed`. The wall-clock spans the child's retries because the timer is anchored to **state entry** of `:authenticating`, not to any individual HTTP attempt; the child's internal retry behaviour does not affect the timeout countdown.

You can equally express the same guard with an explicit `:after` on the parent state — `:timeout` is the named-intent spelling and `:after` is the general primitive; both lower to the same timer, and they may coexist on the node.

For `:spawn-all`, declare the whole-join wall-clock guard with an `:after` (or a state-level `:timeout`) on the `:spawn-all`-bearing state:

```clojure
{:hydrating
 {:spawn-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all
   :on-child-done    :asset/loaded
   :on-child-error   :asset/failed
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/failed]}
  :timeout    "PT1M"                            ;; whole-join wall-clock guard
  :on-timeout {:target :degraded}
  :on   {:hydrate/done   :ready
         :hydrate/failed :error}}}
```

The 60-second timeout fires if the join hasn't resolved by the deadline; the standard exit cascade cancels every surviving child (the `:spawn-all` desugared `:exit` action handles per-child cleanup, same as the sibling cancellation per [§Cancel-on-decision](#cancel-on-decision-default-true)), and the parent transitions to `:degraded`.

### Partial-progress is not preserved

A timeout-driven cascade out of the `:spawn`-bearing state destroys any spawned child and clears the runtime spawn-registry slots; the parent's transition handler may not assume any of the child's partial state has flushed back into the parent's `:data`. For `:spawn-all`, the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` is destroyed alongside the children — the parent cannot read which children had completed at the moment of timeout. Apps that need "take whatever loaded by the deadline" semantics declare a separate `:always` on the parent state that fires `:on-some-complete` when a partial-success guard becomes true, per the `:after` + partial-success idiom documented under [§Spawn-and-join via `:spawn-all` §Composition with hierarchy and `:after`](#composition-with-hierarchy-and-after).

### Cross-references

- [§`:timeout` / `:on-timeout`](#timeout--on-timeout-state--spawn) — the named-intent timeout grammar (state + spawn), its duration rules (integer-ms / ISO-8601 only), and how it lowers onto `:after`.
- [§Whichever fires first wins](#whichever-fires-first-wins) — the cancellation cascade a timeout firing triggers is the same cascade as a parent-destroys-child shutdown.
- [§Delayed `:after` transitions](#delayed-after-transitions) — the underlying timer primitive's full grammar and semantics.
- There is no `:timeout-ms` slot (`:rf.error/spawn-timeout-ms-removed` rejects it); the `:timeout` / `:on-timeout` grammar covers it.

## Cancellation cascade — in-flight `:rf.http/managed` aborts

> **Resolves boot-as-state-machine §M2.** The pre-resolution gap was: when a parent state machine cancels a spawned child mid-execution (parent state exit, parent destroy, `:after` firing, `:spawn-all` join-resolution sibling cancel), what happens to in-flight `:rf.http/managed` requests the child kicked off? Spec 005 + Spec 014 didn't explicitly cover the cross-feature contract. This section is the contract.

### The contract

When the runtime destroys a spawned actor — by **any** trigger — every in-flight `:rf.http/managed` request that was issued from inside that actor's event handlers is aborted. Triggers include:

1. **Parent state exit.** The standard exit cascade emits `:rf.machine/destroy` for the `:spawn`d child (per [§Declarative `:spawn` §Desugaring rules](#desugaring-rules)). The destroy handler aborts the child's in-flight HTTP.
2. **Parent's `:after` firing.** `:after` exit is a state exit; the cascade above runs unchanged (per [§Whichever fires first wins](#whichever-fires-first-wins)).
3. **`:spawn-all` sibling cancellation.** When the join resolves, the runtime unconditionally emits `:rf.machine/destroy` per surviving sibling (per [§Cancel-on-decision](#cancel-on-decision-default-true)). Each siblings' in-flight HTTP aborts.
4. **`:spawn-all` parent state exit.** Symmetric to (1), but the per-child teardown loop (per [§Spawn-id tracking](#spawn-id-tracking)) cascades the abort to every child the `:children` map tracks.
5. **Imperative `[:rf.machine/destroy <actor-id>]`.** A user-authored destroy action emitting the legacy keyword form (per the spawn-fx 5-arity destroy) ALSO aborts that actor's in-flight HTTP. The contract is uniform across triggers — **wherever an actor is destroyed, its HTTP cascades to abort.**
6. **Frame destroy.** `frame.cljc`'s frame-exit walk over surviving machine instances destroys each in turn (per [Spec 002 §Lifecycle](002-Frames.md#frame-lifecycle)); each destroy fires the same abort-on-actor-destroy hook.

The abort surfaces as a normal `:rf.http/aborted` failure on the request's reply path — the `:on-failure` callback (or the merged-reply default) sees `{:kind :rf.http/aborted :reason :actor-destroyed}` per [Spec 014 §Aborts](014-HTTPRequests.md#abort-on-actor-destroy). For most calling code there is no observable difference from a manual `:rf.http/managed-abort`; the `:reason :actor-destroyed` discriminates for callers that care.

### What is "in-flight inside an actor"

A request is "in-flight inside actor `<spawned-id>`" if and only if its originating event vector's first element was `<spawned-id>`. The originating event vector flows to the `:rf.http/managed` fx through the standard fx 5-arity (`:event` on the fx ctx, per [Spec 002 §Routing the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)), and the http fx records the (`request-id`, `actor-id`) tuple in its in-flight registry alongside the abort-handle.

The actor-id is **the spawned actor's own machine address** (e.g., `:http/post#1`), not the parent's address. A request that the parent (`:auth/main`) issued directly is NOT in-flight inside any spawned actor — it is in-flight inside the parent's event-handler context, which has no spawn-registry slot. The parent's request is unaffected by any child-actor destroy. See [§Direct dispatches from event handlers — NOT covered](#direct-dispatches-from-event-handlers--not-covered).

### What about the request's own `:request-id`?

The `:request-id` (per [Spec 014 §Aborts](014-HTTPRequests.md#aborts)) is **orthogonal** to the actor-id. A request can carry both (a stable `:request-id` for app-level abort/supersede AND an actor-id stamped by the runtime); the in-flight registry indexes both ways. A `:rf.http/managed-abort` fx with the request-id aborts the one request; the actor-destroy hook walks every request whose actor-id matches and aborts each. Neither indexing supersedes the other; they coexist.

If a request was issued without `:request-id` from inside a spawned actor, it is still tracked by actor-id and is still aborted on actor-destroy. The `:request-id` is for app-level addressability; actor-id tracking is for runtime cleanup.

### Direct dispatches from event handlers — NOT covered

Events dispatched directly from ordinary `reg-event` handlers — i.e. the originating event vector is for an event-id that is NOT a spawned actor's address — issue `:rf.http/managed` requests that are NOT subject to the actor-destroy cancellation cascade. There is no actor-id to correlate against. This is a resolved scope decision, not an open question — the paragraphs below record why actor-lifetime is the right cancellation scope and what an app does when it wants HTTP tied to a state's lifetime (spawn a child machine). Mirrors [014 §Direct dispatches from event handlers — NOT covered](014-HTTPRequests.md#direct-dispatches-from-event-handlers--not-covered).

Cancellation tied to actor lifetime is semantically the right scope: the child actor exists to run until the parent says "we no longer care"; the parent saying so kills the actor and the actor's outstanding work. An ordinary event handler has no analogous lifecycle peg — its work is launched as a side effect and outlives the handler that fired it; the only way to abort it is via an explicit `:rf.http/managed-abort` keyed on the user-supplied `:request-id`.

If an app wants HTTP requests that are tied to a state's lifetime, the answer is to spawn a child machine that issues them — the `:spawn` or `:spawn-all` declaration is the explicit way to bind HTTP-request lifetime to state-occupancy lifetime. There is no ambient "abort on every state transition out" sugar for direct-dispatch HTTP.

### The hook

The destroy-side abort fires through a late-bind hook (per [`re-frame.late-bind`](../implementation/core/src/re_frame/late_bind.cljc)) — `re-frame.machines` does NOT statically `:require` `re-frame.http.managed`. The hook key is `:http/abort-on-actor-destroy`; the http artefact registers a fn `(fn [actor-id])` at ns-load time; the machines artefact's destroy path looks the fn up at call time and invokes it once per destroyed actor. When the http artefact is not on the classpath the hook resolves to nil and the destroy proceeds without any abort cascade — apps that don't issue managed-HTTP requests pay nothing.

Symmetric to how `re-frame.machines` already publishes `:machines/spawn-fx` / `:machines/destroy-machine-fx` (per [`re-frame.late-bind` hook table](../implementation/core/src/re_frame/late_bind.cljc)) and how `re-frame.flows` and `re-frame.routing` flow up their own seams.

### Trace event

Each individual abort emits `:rf.http/aborted-on-actor-destroy` (registered in [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). One trace per cancelled request. The trace's `:tags` carry `:request-id` (when set on the request), `:actor-id` (the destroyed spawned-actor address), and `:url` (the request's URL).

The reply payload itself is a standard `:rf.http/aborted` failure; tools that subscribe to the http-failure-category trace stream see this category alongside the user-initiated aborts. The `:reason :actor-destroyed` tag is the discriminator.

### What auto-cancels on destroy — exactly three framework-managed resource kinds (divergence from XState)

In XState the lifecycle binding is **total and substrate-agnostic**: stopping an invoked actor on state exit cancels *whatever* it was doing — a pending promise's `.then` is ignored, a callback actor's cleanup fn runs, an observable is unsubscribed, a spawned child machine is stopped. re-frame2's auto-cancel-on-destroy is **narrower** — it covers exactly the **three framework-managed** lifecycle-bound resource kinds the runtime knows how to release:

1. **In-flight `:rf.http/managed` requests** the actor issued — aborted via the hook above.
2. **Armed `:after` timers** the actor scheduled — cancelled by `cancel-actor-timers!` on the same destroy path (per [§Delayed `:after` transitions §Epoch-based stale detection](#epoch-based-stale-detection)).
3. **`:rf.resource/*` owner leases** the actor holds under its `[:machine <actor-id>]` owner — released by dispatching the existing `:rf.resource/release-owner` effect on the same destroy path (per [Spec 016 §Release authority is per owner kind](016-Resources.md#release-authority-is-per-owner-kind)). This is the resources-side mirror of the HTTP-abort hook: a machine that `ensure`d a resource under its `[:machine <actor-id>]` owner has that lease released on destroy, so the entry becomes owner-free (GC-eligible, polling stops) rather than leaking a refetching/polling lease past the actor's death. Like the HTTP abort, it is decoupled — `re-frame.machines` dispatches the effect **by id**, never `:require`-ing `re-frame.resources`; when the resources artefact is not on the classpath the `:rf.resource/release-owner` handler is unregistered and the release is silently skipped (apps that register no resources pay nothing). The owner key is the runtime-derivable `[:machine <actor-id>]` — the registered machine-id for a singleton, the `<type>#<n>` for a spawned actor (the same id the algebra view names as the `:machine-instance` owner per [Derivations §Lifecycle](Derivations.md)); apps mint machine-owned leases under this key so the destroy releases exactly them. The release fires on **every** destroy trigger — explicit `[:rf.machine/destroy <actor-id>]`, declarative-`:spawn` exit cascade, `:spawn-all` per-child teardown, frame destroy, AND the `:final?`-state auto-destroy.

**Any other resource a child actor holds is NOT auto-released** — a `setInterval` / raw `setTimeout` issued via a custom fx, a WebSocket subscription, an IndexedDB or streaming read, a Web Worker, a third-party SDK subscription. The framework cannot know how to cancel an arbitrary fx, so it does not pretend to; this is the principled scope of the guarantee, not a gap to paper over with a generic hook. The three auto-released kinds are exactly those the framework manages and so knows how to release; everything else is the app's, via the `:exit`-action substitute below.

The substitute is **re-frame-native and rides the same destroy cascade**: bind the resource's teardown to the child's **`:exit` action**. On destroy the runtime runs the actor's `:exit` before dissociating its snapshot (per [§Spawning](#spawning--dynamic-actors) and the `:rf.machine/destroy` semantics in [§Action effect map](#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx)), so an `:exit` action that emits the matching cancel fx (close the socket, `clearInterval`, terminate the worker, unsubscribe) releases the resource on *every* code path out of the state — including the `:after`-timeout, join-resolution sibling cancel, and frame-destroy paths, all of which route through the same teardown. The general rule:

> Auto-cancel on destroy covers exactly three framework-managed kinds: `:rf.http/managed` requests, `:after` timers, and `:rf.resource/*` owner leases held under `[:machine <actor-id>]`. For any other lifecycle-bound resource a child actor holds, write an `:exit` action that releases it — it then cancels on the same destroy cascade as the three built-in kinds.

### Why one mechanism, not two

The same hook fires across every destroy trigger — `:spawn` exit, `:spawn-all` exit, join-resolution sibling cancel, `:after` cascade, frame destroy. There is no per-trigger HTTP-abort code path. This means:

- Authors writing a `:spawn`-based child whose body fires `:rf.http/managed` get cleanup automatically — no `:exit` action threading `:rf.http/managed-abort` calls per known `:request-id`.
- The "parent reloads mid-flight" case (boot-as-state-machine §M2) is covered by the frame-destroy walk firing the same hook against every surviving machine.
- The exit cascade from `:after` (per [§Whichever fires first wins](#whichever-fires-first-wins)) reuses the destroy path, so the wall-clock-timeout case is identical to the parent-decides-to-cancel case.

### Cross-references

- [Spec 014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) — the http side of the contract; trace event registration; envelope details.
- [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.http/aborted-on-actor-destroy` category registration.
- [§Declarative `:spawn` §Desugaring rules](#desugaring-rules) — the `:rf.machine/destroy` fx that fires the hook.
- [§Cancel-on-decision](#cancel-on-decision-default-true) — `:spawn-all`'s sibling-cancel cascade routes through the same destroy fx.
- [§Whichever fires first wins](#whichever-fires-first-wins) — the `:after` cascade routes through the same destroy fx.
- Boot-as-state-machine §M2 — the original gap analysis.

## Spawn-and-join via `:spawn-all`

`:spawn-all` is **declarative sugar** for "spawn N children in parallel, fire one of three parent events when the join condition resolves." It is the answer to the boot-as-state-machine pattern: hydrate phases that fan out N requests and join on a `:seen-all-of?` predicate (per boot-as-state-machine §M1).

`:spawn-all` is **registration-time sugar.** `make-machine-handler` walks the spec at construction time and rewrites every `:spawn-all` slot into entry/exit actions emitting N parallel `:rf.machine/spawn` fx (on entry) and per-child `:rf.machine/destroy` fx (on exit), plus an internal join-state hook that watches the parent's events for child-completion signals and fires the parent-level join event when the join condition resolves.

### The pattern

```clojure
{:hydrating
 {:spawn-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all                      ;; or :any (closed two-member enum)
   :on-child-done    :child/done               ;; child → parent event keyword for success
   :on-child-error   :child/error              ;; child → parent event keyword for failure
   :on-all-complete  [:assets-loaded]          ;; fires when join condition is met by completions
   :on-any-failed    [:asset-load-failed]      ;; fires when any child fails (default — see §Join semantics)
   :on-some-complete [:partial-load]}          ;; fires when :any is met
  :on    {:assets-loaded     :ready
          :asset-load-failed :error
          :partial-load      :degraded}}}
```

While in `:hydrating`, four child actors are alive at `[:rf.runtime/machines :snapshots <gensym'd-id>]`. Each child reaches a terminal state and dispatches `[<parent-id> [:child/done :cfg & extra]]` (or `[:child/error :cfg & extra]`) back. The runtime intercepts these events at the parent's machine boundary, updates the join state at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`, evaluates the join condition, and on resolution fires `[:on-all-complete-or-friend ...]` into the parent (an ordinary FSM event the parent's `:on` handles) AND cancels any siblings still in flight.

### Spec-spec keys

The map under `:spawn-all` accepts the following keys:

| key | purpose | required? |
|---|---|---|
| `:children` | a vector of **invoke-spec** maps — same shape as `:spawn` (see [§Spec-spec keys](#spec-spec-keys)) plus a required `:id` keyword for join-state addressing | required, vector of ≥ 1 |
| `:join` | join-condition discriminator — a **closed two-member enum**: `:all` (default), `:any`. Any other value (including the removed `{:n N}` / `{:fn pred}` modes) is rejected at registration as an unknown join spec. Quorum uses the data-only `:after` + `:done-guard` idiom (see [§Composition with hierarchy and `:after`](#composition-with-hierarchy-and-after)); re-adding `{:n}` later is a compatible widening | optional; default `:all` |
| `:on-child-done` | event keyword the parent's children dispatch back on success — runtime intercepts and updates join state | required |
| `:on-child-error` | event keyword the parent's children dispatch back on failure — runtime intercepts and updates join state | required |
| `:on-all-complete` | event vector the runtime dispatches into the parent when `:join :all` resolves with all-complete | required iff `:join :all` |
| `:on-some-complete` | event vector the runtime dispatches into the parent when `:join :any` resolves on the success-side | required iff `:join :any` |
| `:on-any-failed` | event vector the runtime dispatches into the parent when any child fails (sibling cancellation applies) | optional; if absent, child failures are tracked but do not short-circuit the join |

Each child invoke-spec under `:children` accepts the same keys as a single `:spawn` (`:machine-id` xor `:definition`, `:data`, `:id-prefix`, `:on-spawn`, `:start`, `:fixed-actor-id`, `:system-id`) **plus** a required `:id` keyword that names the child for join-state addressing. The `:id` keyword is the user-supplied name the parent's `:on-child-done` / `:on-child-error` events carry as the second-position payload arg (see [§Child completion protocol](#child-completion-protocol)).

> **Wall-clock timeouts: use the parent state's `:after` slot.** `:spawn-all` does not carry a `:timeout-ms` slot; phase-level wall-clock guards on the join are expressed via `:after` on the `:spawn-all`-bearing state. Per [§Wall-clock timeouts on `:spawn` — use parent state's `:after`](#wall-clock-timeouts-on-spawn--use-parent-states-after), an `:after` firing exits the state and the desugared `:exit` action cancels every surviving child via the standard exit cascade.

### Join semantics

The runtime tracks per-state join state at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` — a single map carrying both the per-`:id` child id map and the join-resolution metadata:

```clojure
{:children {:cfg :load-config#1 :flag :load-feature-flags#2 :user :load-user-profile#3 :dash :load-dashboards#4}
 :done      #{:cfg :flag}     ;; ids of children whose :on-child-done fired
 :failed    #{}                ;; ids of children whose :on-child-error fired
 :resolved? false              ;; flips to true when the join condition resolves; subsequent events ignored
 :spec      <invoke-all-spec>} ;; the live spec map (children + :join + :on-*)
```

Where `:children` is the per-`:id` map of user-supplied id → spawned actor id. `:done` and `:failed` are sets of user-supplied ids that have signalled completion. `:resolved?` is the latch that prevents a second join-event firing once the condition has been met. `:spec` is the snapshot of the invoke-all spec at spawn time so resolution is decoupled from later spec edits.

the `[:rf.runtime/machines :spawned <parent> <invoke-id>]` slot is a **direct map** (the `:join` / `:children` keys co-mingle at the root); there is no nested `:join` sub-map. The same slot stores a **keyword** (the spawned-actor id) for a single declarative `:spawn`, and a **map** (the shape above) for `:spawn-all`. Map-vs-keyword disambiguation at the destroy-resolution call site picks the right teardown path. This mirrors `[:rf.runtime/machines :system-ids]`'s single-level shape and keeps the addressing scheme uniform with the rest of the runtime spawn registry.

Join condition discriminators — a **closed two-member enum** (`:all` / `:any`, mirroring the `Promise.all` / `Promise.any` precedent). Any other `:join` value — including the removed `{:n N}` / `{:fn pred}` modes — is rejected at registration with `:rf.error/machine-spawn-all-bad-shape`.

- **`:all` (default)** — fires `:on-all-complete` once `:done` covers every `:id`. If `:on-any-failed` is present and any child errors, it fires immediately and the join short-circuits. If `:on-any-failed` is absent, child failures are tracked but the join waits for `:done` to cover every `:id` (failed children never join the `:done` set, so the join never resolves on success — equivalent to the failure tearing down the parent's surrounding state via a separate transition).
- **`:any`** — fires `:on-some-complete` after the first `:on-child-done`. If `:on-any-failed` is present, the first child error fires it instead.

> **Quorum joins ("N of M") use the documented data-only idiom, not a `:join` mode.** `:join` is a closed two-member enum — `:all` / `:any`; there is no `{:n N}` or `{:fn pred}` mode (quorum is the data-only `:after`/`:always` + `:done-guard` idiom below; re-adding an `{:n}` mode later would be a compatible widening). Express "fire once K of N children have completed" with an `:after` (or `:always`) on the `:spawn-all`-bearing state guarded by a `:done-guard` that reads the live join-state `:done` count (see [§Composition with hierarchy and `:after`](#composition-with-hierarchy-and-after)). Re-adding `{:n}` later is a **compatible widening** — the cheap direction — should real corpus demand appear.

### Cancel-on-decision (default `true`)

> **Sibling cancellation on join resolution is unconditional — there is no opt-out.** Sibling cancellation on the join decision is unconditional.

When the join condition resolves — `:on-all-complete`, `:on-some-complete`, or `:on-any-failed` fires — siblings still in flight are **unconditionally** cancelled. Each cancelled sibling has its `:rf.machine/destroy` fx fired (the same one the exit-cascade would fire), and the runtime emits a `:rf.machine.spawn/cancelled-on-join-resolution` trace event per cancelled actor. **Cancellation is the right behaviour for boot-as-state-machine**: when "all assets loaded" fires, no value is added by letting an in-flight sibling continue to consume bandwidth and dispatch into a parent that has already moved on.

> **No opt-out.** There is no `:cancel-on-decision?` key and no non-cancelling-join protocol (no `:folded?`) that already cost a defect bead, and no corpus consumer used it. A fan-out where each child is independently valuable is modelled as N independent single-`:spawn`s (fire-and-forget) rather than a non-cancelling join. A post-resolution straggler (a completion arriving after the `:resolved?` latch flipped, before the survivor's teardown fully propagates) is a genuinely stale completion: it fires no further parent event, does not fold into the record, and surfaces on the `:rf.machine.spawn-all/late-completion` trace as `:status :stale` (see [§Async completions share the uniform reply envelope](#async-completions-share-the-uniform-reply-envelope)).

The implicit assumption: the parent's surrounding state is exited by the join-event's transition before the cancellation fx runs, so the exit cascade's standard auto-destroy machinery handles the cancellation as part of the same exit (sibling teardown is just "the exit cascade runs while N children are still alive"). This means sibling cancellation is **not a separate cancellation primitive** — it composes with the existing `:spawn` exit-cascade behaviour.

### Child completion protocol

Each child decides when it is "done" or "failed" and dispatches a 2- or 3-element event vector back to the parent:

```clojure
;; In the child machine (e.g. :load-config), at a terminal state's :entry:
{:done
 {:meta  {:terminal? true}
  :entry (fn [{data :data}]
           {:fx [[:dispatch [:hydrate-flow [:child/done :cfg (:result data)]]]]})}}
```

The dispatched event has shape `[<parent-id> [<event-keyword> <child-id> & extra]]` where:

- `<parent-id>` is the parent machine's id (the `:rf.machine/spawn` site stamps `:rf/parent-id` so the child can pick it up if dynamic addressing is needed; for static `:spawn-all` declarations the parent-id is a literal in the parent's source, so the child simply hard-codes it).
- `<event-keyword>` is `:on-child-done` or `:on-child-error` per the parent's spec.
- `<child-id>` is the user-supplied `:id` from the parent's invoke-all entry.
- `& extra` is whatever the child wants to forward to the parent — typically the child's final `:data` slice or an error reason; the parent's join-resolution event handler can read this from the event vector that fired the join.

The runtime intercepts these events at the parent's `make-machine-handler` boundary. Specifically, the parent's handler checks `event[1][0]` (the inner event keyword) against `:on-child-done` / `:on-child-error` declared on the currently-active state's `:spawn-all` entry. On match, the handler:

1. Updates the join state at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` (adds `<child-id>` to the `:done` or `:failed` set inside the same direct map).
2. Evaluates the join condition.
3. If the condition resolves AND `:resolved?` is false: flips `:resolved?` true; emits `:rf.machine/destroy` fx for each in-flight sibling (unconditional); dispatches the join event into the parent (`:on-all-complete` / `:on-some-complete` / `:on-any-failed` per the resolution kind).
4. If the condition does not resolve: the event is treated as handled (no further parent state transition).

The intercepted event is **not** fed into the parent's normal `:on` lookup — the runtime consumes it for join bookkeeping only. Parents that need to see per-child completion separately from the join-event can declare additional `:on` entries for `:child/done` / `:child/error`; those entries fire only on events whose state does NOT have a `:spawn-all` declaring those keywords. This sounds delicate but in practice the ergonomic shape is: the user picks distinct event keywords for `:spawn-all` interception (commonly `:spawn-all/child-done` / `:spawn-all/child-error`) so they don't collide with the parent's own per-child observers.

### Per-child terminal events still fire

The runtime intercepts `:on-child-done` / `:on-child-error` at the **parent's** boundary; the events still fire **inside the child** as ordinary terminal-state events first (the dispatch-back is the child's last act). So per-child traces (`:rf.machine/transition` for the child's terminal transition; `:rf.machine.lifecycle/destroyed` for the child's actor going away) are unchanged. The join layer is *additional*, not a replacement.

### Spawn-id tracking

The runtime spawn registry (per [§Declarative `:spawn`](#declarative-spawn) and) extends naturally for `:spawn-all`: `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` becomes a map with two slot kinds — the per-child id-map under `:children`, and the join-state metadata (`:done`, `:failed`, `:resolved?`) for the live `:spawn-all` instance. Pre-`:spawn-all` declarative-`:spawn` spawns continue to write the keyword `<spawned-id>` directly under that key (their `:rf/invoke-id` resolves a leaf actor address); `:spawn-all` instances write the map shape. Both forms are read-disambiguated by the value type (`map?` vs `keyword?`) at the existing destroy-resolution call site.

The `[:rf.runtime/machines :spawned <parent-id> <invoke-id> :children <child-id>]` slot resolves to the gensym'd actor id for that child. The `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot **is** the join-state map (the join's `:done` / `:failed` / `:resolved?` / `:spec` keys co-mingle with `:children` at the root — no nested `:join` sub-map). On state-exit (whether by normal transition, cancellation, or any other code path), the auto-destroy cascade tears down every entry under `:children` and clears the slot per the lazy-allocation invariant.

### Trace events

The runtime emits four `:spawn-all`-specific trace events:

- `:rf.machine.spawn-all/started` — fires on entry to a `:spawn-all`-bearing state, after all N children have been spawned. `:tags {:actor-id <parent-instance-id> :state <state> :invoke-id <prefix-path> :child-ids #{:cfg :flag :user :dash} :children {:cfg :load-config#1 ...}}`.
- `:rf.machine.spawn-all/all-completed` — fires when `:on-all-complete` resolves. `:tags {:actor-id <parent-instance-id> :invoke-id <prefix-path> :done #{...}}`.
- `:rf.machine.spawn-all/any-failed` — fires when `:on-any-failed` resolves. `:tags {:actor-id <parent-instance-id> :invoke-id <prefix-path> :failed-id <id> :reason <event-payload>}`.
- `:rf.machine.spawn/cancelled-on-join-resolution` — fires once per sibling cancelled when the join resolves. `:tags {:actor-id <parent-instance-id> :invoke-id <prefix-path> :child-id <user-id> :spawned-id <gensym'd-id> :join-event <:on-all-complete | :on-some-complete | :on-any-failed>}`. (parent's live INSTANCE address under `:actor-id`; invocation path under `:invoke-id`.)

A `:rf.machine.spawn-all/some-completed` trace fires for the `:any` resolution kind — symmetric to `:all-completed` but for partial-success join semantics.

### Worked example — auth flow with parallel asset hydration

```clojure
{:initial :authenticating
 :states
 {:authenticating
  {:spawn {:machine-id :http/post
            :data       (fn [{snap :snapshot}]
                          {:url "/api/login"
                           :body (-> snap :data :credentials)})}
   :on     {:auth/succeeded :hydrating
            :auth/failed    :idle}}

  :hydrating
  {:spawn-all
   {:children         [{:id :cfg  :machine-id :load-config}
                       {:id :flag :machine-id :load-feature-flags}
                       {:id :user :machine-id :load-user-profile}
                       {:id :dash :machine-id :load-dashboards}]
    :join             :all
    :on-child-done    :asset/loaded
    :on-child-error   :asset/failed
    :on-all-complete  [:hydrate/done]
    :on-any-failed    [:hydrate/failed]}
   :on    {:hydrate/done   :ready
           :hydrate/failed :error}}

  :ready {}
  :error {}
  :idle  {:on {:submit :authenticating}}}}
```

The walk-through:

1. User submits → `:authenticating` spawns one `:http/post` child.
2. The HTTP child posts; on success dispatches `[<parent-id> [:auth/succeeded ...]]` → state moves to `:hydrating`.
3. Entering `:hydrating` triggers `:spawn-all`'s desugared entry: spawn four children in parallel. Each child is a registered machine that fetches its own asset and dispatches `[<parent-id> [:asset/loaded :cfg ...]]` (or `[:asset/failed :cfg <reason>]`) on completion.
4. As each `:asset/loaded` arrives, the runtime intercepts at the parent boundary, updates `[:rf.runtime/machines :spawned :auth-flow [:hydrating] :done]`, and evaluates `:all`. Once all four `:done`, the runtime fires `[:hydrate/done ...]` into the parent → state moves to `:ready`.
5. If any child fails first, `[:hydrate/failed ...]` fires; the runtime cancels the surviving siblings (their `:rf.machine/destroy` fx is emitted; `:rf.machine.spawn/cancelled-on-join-resolution` traces fire); state moves to `:error`.
6. If the user reloads the page mid-hydration, the standard frame-destroy cascade tears down every actor (the `:hydrating` state's exit fires every `:children` destroy). The `:spawn-all` declaration is correct-on-every-code-path.

The key property: the parent has no per-child bookkeeping in `:data`. The `:done` / `:failed` sets, the `:children` id map, the resolution latch — all runtime-owned at `[:rf.runtime/machines :spawned :auth-flow [:hydrating]]`. The author writes the four child-specs and the three event hooks and the runtime handles everything else.

### Composition with hierarchy and `:after`

`:spawn-all`'s entry/exit actions compose with the standard hierarchical entry/exit cascading machinery just like `:spawn`'s do — the desugar produces ordinary `:entry` / `:exit` actions that the cascade machinery picks up. `:after` on the same state node is the **canonical** way to set a wall-clock timeout on the whole join — `{60000 :hydrate/timed-out}` fires `:hydrate/timed-out` if the join hasn't resolved in 60 s; the parent's `:on` for `:hydrate/timed-out` transitions out, which exits the state and tears down all surviving children via the desugared exit cascade. Per [§Wall-clock timeouts on `:spawn` — use parent state's `:after`](#wall-clock-timeouts-on-spawn--use-parent-states-after), this is the **single** wall-clock-timeout mechanism on `:spawn-all`-bearing states; there is no second `:timeout-ms` surface.

A common partial-success idiom is to declare `:after` for the phase-level timeout and let the timeout transition land in a state whose `:always` checks `[:rf.runtime/machines :spawned <parent> <invoke-id> :done]` against a partial-success guard — the parent reads which children completed before the deadline and decides whether to proceed with degraded data or to fail outright. The cleanest expression is a separate transition out of the `:spawn-all`-bearing state, which the existing `:after` machinery delivers without any `:spawn-all`-specific extension.

### Capability gating

`:spawn-all` is gated under the `:actor/spawn-and-join` capability per [§Capability matrix](#capability-matrix). A port that doesn't claim it rejects `:spawn-all` at registration time with `:rf.error/machine-grammar-not-in-v1`. The v1 CLJS reference claims it.

### Errors

`:spawn-all` introduces three new registration-time error categories on top of the existing `:rf.error/machine-*`:

- `:rf.error/machine-spawn-all-bad-shape` — a child invoke-spec is missing `:id` or both `:machine-id` and `:definition`; or `:spawn-all` is not a vector; or the join-event slots are missing per the required-iff rules above.
- `:rf.error/machine-spawn-all-duplicate-id` — two child invoke-specs share an `:id` keyword. Each `:id` must be unique inside the same `:spawn-all` block.
- `:rf.error/machine-spawn-all-with-spawn` — a state node declares both `:spawn` and `:spawn-all`; the combination is rejected.

**Unregistered child TYPE — the join-hang fail-closed.** A child whose `:machine-id` names an **unregistered** machine TYPE (and carries no inline `:definition`) would never run, so it would never dispatch its `:on-child-done` — blocking an `:all` join **forever**. The runtime fails CLOSED instead: the `:rf.machine/spawn-all-init` fx (which fires FIRST in the entry `:fx` vector, before the per-child `:rf.machine/spawn` fxs) detects any unregistered child up front and **rejects the whole join** — it seeds **no** join-state and emits the always-on `:rf.error/machine-spawn-unregistered-type` (one per offending child, structural-only). With no seeded join-state, a registered sibling's later `:on-child-done` finds no slot and falls through to the documented no-op, so the join **cannot deadlock**; each unregistered child's own `:rf.machine/spawn` fx also rejects independently. This is the same `:rf.error/machine-spawn-unregistered-type` reject the single-`:spawn` path uses (see [§Errors](#errors)).

Cross-references: [§Spawning](#spawning--dynamic-actors) for the imperative-spawn surface; [§Declarative `:spawn`](#declarative-spawn) for the per-child sugar that `:spawn-all` extends; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) for the schema; [Pattern-Boot](Pattern-Boot.md) for boot-flow worked examples leveraging `:spawn-all` for hydrate-as-spawn-and-join.

## Async completions share the uniform reply envelope

> **Machine async completions and cancellation.** Machines have asynchronous completions — a spawned-actor child finishing on a `:final?` leaf, a `:spawn-all` join-child folding into the parent's join, and a fired-or-stale `:after` timer — plus terminal **cancellation** paths (a cancelled `:after` timer, a destroyed actor, a join-survivor cancellation). All are **managed async effects** ([Managed-Effects §9](Managed-Effects.md#9-uniform-reply-envelope-async-completions)), so all lower onto the framework-wide [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) — the same envelope HTTP ([014](014-HTTPRequests.md)), resources / mutations ([016](016-Resources.md)), and route loaders ([012](012-Routing.md)) complete through. **This is internal lowering only.** The public statechart API documented above — the `:on-done` `:data` callback, the `:on-error` parent transition, the `:after` epoch-gated stale-drop, the `:spawn-all` join protocol, and actor-destroy teardown — is **preserved exactly**. What the lowering shares is the *vocabulary*: one `:work/id`, one closed `:status` (including `:cancelled` — cancellation is data, not the absence of a reply), and reply-envelope-shaped trace facts, so the trace stream and any future work-ledger correlate machine completions the same way they correlate every other managed async family.

### Spawned-actor completion

When a `:spawn`-spawned child reaches a `:final?` leaf, the runtime forms a canonical [reply map](Managed-Effects.md#the-reply-map) internally before driving the parent's spawn hook:

- **Work id.** `[:rf.work/machine actor-id work-bearing-path generation]` ([Managed-Effects §Work-id correlation](Managed-Effects.md#work-id-correlation)). `actor-id` is the finishing actor's INSTANCE id; `work-bearing-path` is the `:spawn`-bearing node's declaring path (the child's stamped invocation path `:rf/invoke-id`); `generation` is the spawn discriminator (the `<type>#<n>` instance suffix `n`, or `1` for an explicit `:fixed-actor-id`). One attempt has one work id.
- **Status.** A plain `:final?` leaf is `:status :ok` with the child's `:output-key` result under `:value`; an `:error?` error terminal (per [§`:on-error`](#on-error--child-failure-control-flow)) is `:status :error`. The **public `:on-done` callback** is then driven with `(:value reply)` as its `:result` (the value flows through the one canonical reply); the **public `:on-error` transition** still receives the **raw** error payload on its `:event` (`(nth ev 2)`) — the reply map's family-`:kind`-wrapped `:error` slot is internal vocabulary, not what the parent transition observes.
- **Stale suppression — the correctness boundary** ([Managed-Effects §Stale suppression](Managed-Effects.md#stale-suppression)). A **late** child completion whose `actor-id` / spawn correlation no longer names a live actor (the actor was already destroyed, or the spawn slot was reused) is `:status :stale` / `:work/status :suppressed`; its app target (the `:on-done` / `:on-error` routing) MUST NOT run and it produces no `:data` / snapshot mutation. Cancellation (actor-destroy) remains an optimisation; staleness is the safety rule.
- **Trace.** The `:rf.machine/done` trace carries the reply-envelope facts (`:work/kind`, and `:rf.reply/status` / `:rf.reply/work-id` / `:rf.reply/work-status`) **additively** alongside its preserved public `:output` / `:parent-id` / `:error?` shape; the canonical `:rf.reply/work-id` is the join key the uniform work/reply view groups on. Per [Conventions §The naming rules](Conventions.md#the-naming-rules-one-name-per-fact) the work identity rides ONLY under `:rf.reply/work-id` on this reply-envelope row — the bare `:work/id` duplicate was dropped (rf2-o6c2jr); the bare `:work/id` remains the durable ledger identity on the reply map itself. Wire-bearing slots route through the shared `rf/elide-wire-value` walker.

### `:after` timers — the existing specialized stale-gated instance

The machine `:after` timer is the **existing** stale-gated instance of the reply pattern. Its [epoch-based stale detection](#epoch-based-stale-detection) — the synthetic timer-elapsed event validated against the scheduling node's declaring path + per-path `:rf/after-epoch` — **is** the envelope's [stale-suppression gate](Managed-Effects.md#stale-suppression): the **declaring path + epoch are the data-only suppression gate**. A timer is *live* iff its declaring path is still active **and** its carried epoch equals the node's current per-path epoch; otherwise it is *stale*, its transition does **not** fire, and the `:rf.machine.timer/stale-after` trace carries the reply vocabulary (the canonical `:work/id` `[:rf.work/timer <declaring-path> <scheduled-epoch>]` + `:work/kind :timer`, `:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`, and the carried-vs-current `{:path … :rf/after-epoch …}` gate) **additively** on its preserved public shape. A **fired** (live) timer is the closed completion counterpart: its `:rf.machine.timer/fired` trace carries the same canonical `:work/id` with `:rf.reply/status :ok` / `:rf.reply/work-status :completed` (a guard-suppressed fired timer stays `:ok`/`:completed` — the no-transition decision is app-level, recorded under `:correlation`, not the stale-suppression boundary). The timer `:work/id`'s `epoch` is the **scheduled** epoch (the timer's attempt identity), so a re-armed timer on node re-entry lands on a distinct work id (one attempt, one work id). This names the epoch-mismatch drop in the shared vocabulary.

**Causal completion time on the timer reply.** The synthetic `:after`-elapsed dispatch is a causal token in its own right (per [Spec 002 §The recordable-coeffect rule](002-Frames.md#the-recordable-coeffect-rule-the-durable-write-rule), it carries a *fresh* router-stamped `:rf/time-ms` at fire time — the same token the firing guard / action read off `:rf.cofx`). When that token is present, the **fired** and **stale-after** timer traces carry the causal completion timestamp under the reply-envelope `:rf.reply/completed-at` spelling — mirroring the spawned-actor `:rf.machine/done` reply. Per [Conventions §The naming rules](Conventions.md#the-naming-rules-one-name-per-fact) it rides ONLY under `:rf.reply/completed-at` on these reply-envelope rows (the bare `:completed-at` duplicate was dropped — rf2-o6c2jr; `:completed-at` remains the canonical spelling on the reply map itself). This satisfies [Managed-Effects §Causal completion metadata](Managed-Effects.md#causal-completion-metadata) for a fired timer whose transition mutates durable snapshot `:data`; it is omitted (not nil-filled) on a pure-fn / no-cofx fire path.

### `:spawn-all` join-child completion

A `:spawn-all` join-child completion — a child dispatching `[parent [:on-child-done child-id …]]` / `:on-child-error` into the parent's join — lowers through the **same** uniform reply vocabulary the single-`:spawn` path uses. **This is internal trace-stream lowering only**; the public join protocol (the parent dispatch, the resolution events `:on-all-complete` / `:on-some-complete` / `:on-any-failed`, the sibling-cancellation cascade) is preserved exactly.

- **Work id.** A join child reuses the machine head keyed on the child's SPAWNED instance address (the `<type>#<n>` actor id in the join-state `:children` map) and the `:spawn-all`-bearing node's declaring path (the parent's `invoke-id`) as the work-bearing path: `[:rf.work/machine spawned-id invoke-id generation]`. One child attempt has one work id.
- **Status — the decisive child.** The child completion that *drives* a join resolution is the managed-async completion that resolved the join, so its reply-envelope facts ride **additively** on the resolution trace: the success-side resolutions (`:rf.machine.spawn-all/all-completed` / `*/some-completed`) carry `:rf.reply/status :ok` / `:rf.reply/work-status :completed`; the `:on-any-failed` resolution (`:rf.machine.spawn-all/any-failed`) carries `:rf.reply/status :error` / `:rf.reply/work-status :failed`. The decisive child's forwarded payload rides `:value` for a `:done` reply (and a family-`:kind`-wrapped `:error` for a `:failed` reply).
- **Stale suppression — the post-resolution late completion** ([Managed-Effects §Stale suppression](Managed-Effects.md#stale-suppression)). Surviving siblings are **unconditionally destroyed** at resolution (rf2-w8gxxz cut the `:cancel-on-decision? false` protocol), so a completion arriving *after* the join already latched `:resolved?` is a genuinely stale straggler (its teardown had not yet fully propagated). It is **suppressed from RE-RESOLVING** the join — it fires **no further parent event**, triggers **no cancellation**, and **does not fold into the join-state record** (the record stays frozen at resolution); the `:resolved?` latch already flipped, exactly the §Stale-suppression "fires no further app effect" rule. The `:rf.machine.spawn-all/late-completion` trace carries the canonical `:status :stale` / `:work/status :suppressed` reply facts (`:stale/reason :rf.machine.spawn-all/join-resolved`) **additively** on its preserved public `:child-id` / `:kind` shape — the spawn-all analogue of the single-`:spawn` stale path.
- Both the decisive-child and late-completion replies thread the causal `:completed-at` off the firing dispatch's `:rf.cofx` when present.

### Terminal cancellation completes through the envelope

Per [Managed-Effects §Cancellation](Managed-Effects.md#cancellation) / [EP-0011 §Cancellation](../docs/EP/EP-0011-uniform-async-reply-envelope.md), **cancellation is represented as data, not as the absence of a reply.** The machine's terminal cancellation paths close the work attempt the reply-envelope way — a `:status :cancelled` reply (`:cancelled? true`, `:cancel/reason …`, `:work/status :cancelled`) carrying the canonical `:work/id` so the cancelled completion joins the same uniform work/reply row its scheduling / spawn started. The reply facts ride **additively** on the existing traces; the public trace shapes are preserved.

- **Cancelled `:after` timer.** `:rf.machine.timer/cancelled` (on state exit / actor destroy / subscription re-resolution / in-place supersede / frame destroy) carries `:rf.reply/status :cancelled` / `:rf.reply/work-status :cancelled` / `:rf.reply/cancelled?` / `:rf.reply/cancel-reason` plus the canonical timer `:work/id` `[:rf.work/timer <declaring-path> <epoch>]` (matching the fired / stale reply's work id). The `:cancel/reason` carries the closed `:rf.machine.timer/cancelled` `:reason` set (`:on-exit` / `:on-destroy` / `:on-resolution` / `:on-supersede` / `:on-frame-destroy`). A cancelled timer never fired, so it carries no `:value`.
- **Destroyed actor.** An `:explicit` `:rf.machine/destroyed` (the actor was torn down — direct destroy, parent state-exit cascade, imperative `[:rf.machine/destroy <id>]` — before reaching a `:final?` leaf) carries the canonical machine `:work/id` with `:rf.reply/status :cancelled` / `:rf.reply/work-status :cancelled` / `:rf.reply/cancel-reason :explicit`. A `:reason :rf.machine/finished` destroy is **not** a cancellation — the actor already closed its attempt through the `:rf.machine/done` reply — so it carries **no** cancelled reply facts.
- **Join-survivor cancellation.** `:rf.machine.spawn/cancelled-on-join-resolution` (a surviving sibling torn down when a `:spawn-all` join resolves — unconditional) carries the survivor's canonical machine `:work/id` with `:rf.reply/status :cancelled` / `:rf.reply/cancel-reason :on-join-resolution`. The survivor's own `:rf.machine/destroy` fx additionally closes it through the `:rf.machine/destroyed` cancelled reply; this trace carries the join-resolution attribution.

### Cross-references

- [Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) — the canonical normative home (reply map, reply target, closed status taxonomy, work-id correlation, mandatory stale suppression, the reply-mapping functor law).
- [EP-0011 §Machine Completion / §Timer Reply](../docs/EP/EP-0011-uniform-async-reply-envelope.md) — the rationale record for lowering the two machine async completions.
- [§Final states](#final-states-final--on-done--output-key) / [§`:on-error`](#on-error--child-failure-control-flow) — the **public** spawned-actor completion contract this lowers (`:on-done` / `:on-error` semantics preserved).
- [§Epoch-based stale detection](#epoch-based-stale-detection) — the **public** `:after` staleness contract whose epoch + declaring path are the suppression gate.
- [014 §HTTP lowering](014-HTTPRequests.md) — the sibling family completing through the same envelope (`:on-success` / `:on-failure` lowering).

## Cross-spec interactions

### Retry-ownership boundary with `:rf.http/managed`

State machines own **semantic retry**; `:rf.http/managed` owns **transport-level retry**. Per [Spec 014 §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry), the test for which owner applies is whether the retry decision is a function of attempt count + failure category alone (transport — owned by `:retry`) or depends on response body / app state / another request's outcome (semantic — owned by the machine). A state spec's `:spawn` of `:rf.http/managed` configures transport retry on the request itself; the machine's transition on the resulting `:succeeded` / `:failed` reply expresses the semantic retry — re-target to a refresh state, delay via `:after` before re-issuing, route to a different state on a different failure category. The two layers compose without overlap. See [Pattern-Boot §Worked example — auth-machine and the retry-ownership boundary](Pattern-Boot.md#worked-example--auth-machine-and-the-retry-ownership-boundary) for the canonical illustration.

### Privacy — redacting machine `:data` at trace egress

A machine **is** an event handler (per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler)), so its privacy contract is path-based, like every other handler's. There is **no whole-machine sensitivity flag**: the `reg-machine` / `reg-machine*` arities (including the optional event-`:schema` arity, per [§Surface signature](#registration--the-machine-is-the-event-handler)) carry **no sensitivity-bearing `opts` — `opts` validates only the dispatched event vector — and no top-level `:sensitive?` (boolean) flag on the spec map**; the framework-wide handler-metadata `:sensitive?` annotation that once stamped a whole cascade has been removed (sensitivity is now a property of the data value at a path, not of the handler that touched it — per [009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)).

> **A machine declares its durable `:data` classification PROJECTION-RELATIVE, lowered per actor instance.** A machine's durable `:data` classification travels with the **machine definition**, declared as projection-relative `:sensitive` / `:large` paths (rooted at one actor snapshot's `:data`), and the runtime **lowers** it per actor instance at spawn / first-boot into the per-frame elision registry — dropping it on destroy. There is no schema→marks redaction bridge: a `:sensitive?` / `:large?` Malli property on a `:data` slot does **not** classify durable `:data` for snapshot egress (the `[:schemas :data]` schema still **validates** `:data`, and its `:sensitive?` props still drive validation-FAILURE-trace redaction — a different axis, the validator's own egress product).

The supported surface for redacting a machine's durable `:data` at snapshot egress is the **machine definition**. A machine declares which `:data` slots are sensitive / large via top-level `:sensitive` / `:large` keys on the `reg-machine` spec, **projection-relative to one actor snapshot's `:data`** (the matrix projection root) — e.g. `{:sensitive [[:data :payment :token]] :large [[:data :payment :receipt-pdf]]}`. The classification is **value-independent** and applies to **every instance**: the runtime lowers each declared path per actor by re-rooting it to the instance's absolute snapshot path `[:rf.runtime/machines :snapshots <actor-id> :data …]` in the per-frame elision registry (`:source :machine`) **at spawn / first-boot**, and **drops** it on destroy (by any cause — explicit `[:rf.machine/destroy …]`, exit-cascade, `:spawn-all` teardown, frame-destroy, `:final?`-state auto-destroy). A `:spawn`-generated `<type>#n` is therefore classified with zero per-instance author code, exactly as XState v5 carries `context` shape on the machine definition and applies it per actor. The trace-egress chokepoint reads that lowered registry declaration (`re-frame.classification/frame-snapshot-classification` re-roots it snapshot-relative) and renders the marked slot as `:rf/redacted` (sensitive) or the `:rf.size/large-elided` marker (large) in the `:before` / `:after` / `:snapshot` slots of `:rf.machine/transition` and `:rf.machine/snapshot-updated` (and the `:data` / `:input :data` / `:cascade` `:data-delta` slots) before the event crosses the trace bus / epoch-capture / AI-MCP boundary or reaches a log sink. A thrown action's `:exception-data` is whole-slot scrubbed when the machine declares any sensitive `:data` path. A malformed `:sensitive` / `:large` declaration (a non-vector axis, a non-path entry) is rejected **fail-loud at registration** with `:rf.error/invalid-machine-classification`. The observable contract — a machine-declared `:data` slot redacting to `:rf/redacted` in the `:rf.machine/transition` / `:rf.machine/snapshot-updated` `:after` snapshot while an unmarked sibling rides verbatim — is pinned by the reference-implementation regression at [`implementation/machines/test/re_frame/machine_data_schema_redaction_test.clj`](../implementation/machines/test/re_frame/machine_data_schema_redaction_test.clj) (Spec 015 §Machine-owned durable classification).

The lowered declaration lives in the SAME per-frame elision registry every other durable classification source writes to (`:source :effect` from the four commit-plane effects, `:source :flow` from flow outputs, `:source :route` from route activation) — the registry read unions every source at egress lookup. A frame does **not** declare a machine snapshot's absolute `:data` path via a frame-owned `:sensitive {:app-db …}` annotation: the **machine declaration is the sole, canonical, projection-relative, per-instance-applied surface** for a machine's durable `:data` classification.

Two cases are worth naming. **(1) Spawned child.** A spawned actor's traces are keyed under the actor's own (instance) id; the child machine's own declaration is lowered onto the spawned instance's snapshot path at spawn — a sensitive parent's declaration does not transitively widen onto a child of a different type. **(2) `:spawn`-target machine.** The invoked child's snapshot egress is governed by the child machine's own projection-relative declaration, lowered per spawned instance; the join events fired on the parent (`:rf.machine.spawn-all/all-completed`, `:any-failed`, `:some-completed`) carry no child `:data` and need no inheritance.

## Reply patterns

xstate's `sendTo` + `sender` lets a child reply to a specific request. In re-frame, no new API: include the reply event in the request:

```clojure
(rf/dispatch [:request/get-data
              {:url   "/data"
               :reply [:got-data <correlation-id>]}])
```

The handler dispatches `:got-data` (with the correlation id) when the response arrives. The drain keeps the request and reply in the same atomic unit. This is just convention; document it.

## Querying machines

A machine *is* an event handler — that's the architectural commitment. But callers (tooling, AIs, conformance harnesses, post-v1 visualisers) routinely ask "what machines are registered?" and "what is machine `<id>`'s definition / metadata?" Forcing every caller to reimplement "scan `(registrations :event)`, filter by `:rf/machine? true`" is a tax with no upside.

The framework therefore ships two thin lookup fns — **derived views over the existing event registry**, not a new registry kind:

```clojure
(rf/machines)
;; → seq of registered machine TYPE-ids (singletons + spawnable types)
;; Implementation: every event handler whose registration metadata
;; carries :rf/machine? true.
;;
;; NOTE: this enumerates registered TYPES — singleton
;; machines and the types a `:spawn` names — NOT live spawned INSTANCES.
;; A spawned actor carries no per-instance registrar entry (its liveness
;; is its snapshot; per §Liveness is derived from runtime-db), so it does not
;; appear here. Enumerate live instances from the snapshots map:
;;   (keys (get-in (rf/runtime-db-value frame-id)
;;                 [:rf.runtime/machines :snapshots]))

(rf/machine-meta :drawer/editor)
;; → registration-metadata map (transition table, doc, schemas, ...)
;; Implementation: (handler-meta :event :drawer/editor), with the
;; standard metadata-map shape; machine-specific keys (e.g.
;; :rf/transition-table) are present iff :rf/machine? is true.

(rf/machine-by-system-id :primary-request)
;; → :request/protocol#42 (the gensym'd id), or nil if no spawn
;;   under the active frame is currently bound to that :system-id.
;;   Implementation: (get-in runtime-db [:rf.runtime/machines :system-ids :primary-request])
;;   in the active frame's runtime-db. See [§Named addressing via
;;   :system-id](#named-addressing-via-system-id).
```

Both are pure functions over the registry. Both are JVM-runnable (they touch only the central registry). Both are stable across hot-reload because they re-read on each call.

Why a lens, not a registry kind:

- **Architectural commitment preserved.** Machines remain *event handlers*. There is no `:machine` registry kind, no parallel substrate, no per-machine auto-registration. `(rf/machines)` is a `filter` call, not a separate index.
- **`:rf/machine? true` metadata is the discriminator.** `make-machine-handler` carries this metadata onto the registration; `reg-event` records it as part of the standard metadata map (per [001 §Metadata-map shape](001-Registration.md)). User-written event handlers do not set this key.
- **One-line implementation.** `(rf/machines)` is `(registrations :event #(:rf/machine? %))`-shaped; `(rf/machine-meta id)` is `(handler-meta :event id)`. Both reuse the public registrar query API ([API.md §Public registrar query API](API.md#public-registrar-query-api)).
- **Discovery is a first-class operation.** Visualisers can iterate every live machine without knowing where else to look; conformance harnesses can enumerate the suite under test; AI agents can answer "show me the machines in this app."

User-facing call sites:

```clojure
(rf/machines)
;; → (:auth.login/flow :checkout/flow :request/protocol ...)
;;   registered TYPES (incl. spawnable types like :request/protocol),
;;   NOT live instances like :request/protocol#42.

(for [id (rf/machines)]
  [id (-> (rf/machine-meta id) :doc)])
;; → ([:auth.login/flow "Login flow: idle → submitting → ..."]
;;    [:checkout/flow "Checkout wizard."]
;;    ...)

;; Live spawned INSTANCES — read the snapshots map (their liveness is
;; their snapshot, per §Liveness is derived from runtime-db):
(keys (get-in (rf/runtime-db-value :rf/default)
              [:rf.runtime/machines :snapshots]))
;; → (:auth.login/flow :request/protocol#42 :request/protocol#43 ...)
```

See also [API.md §Machines](API.md#machines).

## Subscribing to machines via the `:rf/machine` sub

Machines are read like any other slice of frame-state — through a registered subscription (the snapshot lives in the runtime-db partition, per [§Where snapshots live](#where-snapshots-live)). The framework ships **`:rf/machine`** as standard infrastructure (alongside `:dispatch` fx, the standard `[:rf.interceptor/path <path-vector>]` interceptor, and the rest of the framework-supplied registry entries):

```clojure
(rf/reg-sub :rf/machine
  (fn [db [_ machine-id]]
    (get-in db [:rf.runtime/machines :snapshots machine-id])))
```

Returns the whole snapshot `{:state <kw> :data <map>}` for the named machine, or `nil` if the machine is not yet initialised. The argument is **just the machine-id** — no varargs, no path-drilling. Granularity is the user's job via derived subs.

### The canonical surface is the subscription vector

The canonical user-facing read is the ordinary subscription vector `[:rf/machine <machine-id>]` — there is no machine-special call site to learn:

```clojure
;; usage in a view:
@(rf/subscribe [:rf/machine :drawer/editor])
;; → {:state :idle :data {:circle-id nil ...}}      (or nil before initialisation)
```

The `:rf/machine` sub is in `(registrations :sub)`, traceable and introspectable like every other registered subscription; both the sub and any derived subs that chain off it resolve on the surrounding frame, reading that frame's `[:rf.runtime/machines :snapshots :drawer/editor]`. Reading a machine is *exactly* "subscribe to a registered sub" — the same shape the rest of your signal graph already uses — so the substrate stays one mechanism, not two.

> **Reading a machine's snapshot is not the same as a child machine.** A subscription on `[:rf/machine <id>]` is a **reactive read** of the named machine's state. Declarative child-machine binding — spawning a child actor of a state — uses `:spawn`, an entirely separate surface. If you're looking for "spawn a child actor of this state," see [§Declarative `:spawn`](#declarative-spawn); if you're looking for "read this machine's snapshot reactively from a view," subscribe to `[:rf/machine <id>]`.

### The `sub-machine` named read sugar

For the common top-level read — a view or handler that wants the whole snapshot — the framework ships **`sub-machine`** on the `re-frame.core` façade (re-exported from `re-frame.core-machines`) as thin sugar over the canonical vector:

```clojure
;; these two are equivalent:
@(rf/sub-machine :drawer/editor)
@(rf/subscribe [:rf/machine :drawer/editor])
;; → {:state :idle :data {...} :tags #{...}}   (or nil before the machine's first event)
```

`sub-machine` returns a reaction over the snapshot map `{:state :data :tags}` — it *is* `(subscribe [:rf/machine machine-id])`, with nothing added to the read path. Two arities:

- `(sub-machine machine-id)` — the primary form; resolves the ambient frame through the carried scope/hold chain, exactly like the bare subscription.
- `(sub-machine machine-id opts)` — the `opts` map carries `{:frame <target>}` (a frame-id keyword or a live frame object), lowered to `subscribe`'s frame-first 2-arity, so a read can target an explicit frame from outside an established scope (async callbacks, tools, tests).

**The vector form stays canonical.** `[:rf/machine <machine-id>]` remains the registered sub; `sub-machine` is ergonomic read sugar that *coexists* with it. The literal vector is what `:<-` chained inputs (per [§Granularity is via derived subs](#granularity-is-via-derived-subs)) and the machine-selector recognizer continue to use — there is no sugar form inside a sub's `:<-` vector. `sub-machine` is for the top-level read in a view or handler body.

**Named sugar is reserved for runtime-db reads.** A machine snapshot lives in the **runtime-db** partition (per [§Where snapshots live](#where-snapshots-live)), and the convention is uniform: runtime-db subs carry named read sugar (`sub-machine`, plus `sub-route` for the route slice), while ordinary app-db content — including flow output — is read with the plain `subscribe`. See [Conventions §Reserved sub-ids](Conventions.md#reserved-sub-ids) for the discriminator.

> **`sub-` is the subscription-family verb, not a child-machine relationship.** The `sub-` prefix marks `sub-machine` as a sibling of `subscribe` / `subscribe-once` — it *reads* a machine. It does **not** denote a "sub-machine" (a child actor of a state); declarative child-machine binding uses `:spawn` (see [§Declarative `:spawn`](#declarative-spawn)).

### The `:rf/machine-has-tag?` predicate sub

Alongside `:rf/machine` the framework ships **`:rf/machine-has-tag?`** — a predicate sub that answers the containment question for one tag without forcing the view to read (and depend on) the whole snapshot:

```clojure
(rf/reg-sub :rf/machine-has-tag?
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf.runtime/machines :snapshots machine-id :tags]) tag)))
```

**Arguments.** Two: the `machine-id` keyword and the `tag` keyword. Both are required; neither varies — there is no varargs form, no path-drilling, no default. The sub vector is `[:rf/machine-has-tag? <machine-id> <tag>]`.

**Return contract.** Strictly `true` | `false`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`. Returns `false` for every other case — `tag` absent, snapshot present but `:tags` elided (no active state declares tags), or no snapshot at all (unknown or not-yet-initialised machine). Never returns `nil`; the predicate shape is total over `(machine-id, tag)` pairs.

**Re-render granularity.** The sub is **derived** — it reads the snapshot via `get-in` rather than chaining off `:rf/machine` — so the reaction emits only when *this tag's containment-bit* flips. A view that subscribes to `[:rf/machine-has-tag? :ui/nine-states :data/loading]` does not re-render when `:state`, `:data`, `:meta`, or *other* tags change; only when `:data/loading` is added to or removed from `:tags`. Reagent's built-in equality dedup gates the boolean return.

```clojure
;; canonical surface — the subscription vector
@(rf/subscribe [:rf/machine-has-tag? :ui/nine-states :data/loading])
;; => true | false
```

For the full tag-set narrative — what `:tags` is, how the runtime computes it at every transition, what the user-vs-runtime ownership boundary looks like — see [§State tags](#state-tags). This section catalogues only the subscription surface.

### Granularity is via derived subs

The framework provides the entry point — `:rf/machine` returns the whole snapshot. Users write Layer-3 (signal-graph chained) subs for fine-grained reactivity, multi-source combinations, or computed projections:

```clojure
;; project just :state
(rf/reg-sub :drawer/editor-state
  :<- [:rf/machine :drawer/editor]
  (fn [{:keys [state]} _] state))

;; a boolean over :state
(rf/reg-sub :drawer/editing?
  :<- [:rf/machine :drawer/editor]
  (fn [{:keys [state]} _] (= state :editing)))

;; combine with other subs
(rf/reg-sub :drawer/editor-and-circles
  :<- [:rf/machine :drawer/editor]
  :<- [:drawer/circles]
  (fn [[ed circles] _] {:editor ed :circles circles}))
```

The framework provides the entry point; users write the derivations. Same pattern as every other `:<-` chain in re-frame.

### Pure-factory invariant preserved

`make-machine-handler` registers nothing — it returns a pure handler fn. Registration of the *machine's event handler* happens at the `reg-event` call site; reading the snapshot happens through the framework-registered `:rf/machine` sub. There is no auto-registration tied to the machine's id, no self-id capture, no registration side effects in the factory.

## Testing

Three test levels fall out naturally from the pure-factory contract on `make-machine-handler`. No new primitive needed.

### Level 1 — pure `machine-transition`

```clojure
(require '[re-frame.machines.result :as result])

(let [r (rf/machine-transition definition snapshot event)]
  (when (result/ok? r)
    [(result/snap r) (result/fx r)]))    ;; post-transition snapshot + emitted fx
```

No `:db`, no `[:rf.runtime/machines :snapshots]` plumbing, no fx interpretation — just the FSM. Best for property-based testing, table-driven assertions, fastest tests. Definition + snapshot in, a `result` value out (`ok?` / `snap` / `fx` / `info` are the public reads).

### Level 2 — unregistered handler fn

```clojure
(def handler (rf/make-machine-handler {:initial :idle :states {...}}))

;; The runtime stores snapshots at [:rf.runtime/machines :snapshots <id>], where <id> is the
;; surrounding registration's id (in the runtime-db partition). A Level-2 test calls the
;; handler against the canonical db-arg shape directly:
(handler {:rf.db/runtime {:rf.runtime/machines {:snapshots {:drawer/editor {:state :idle :data {}}}}}}
         [:drawer/editor [:right-click-circle some-id 30]])
;; → {:rf.db/runtime ... :fx ...}   ;; snapshots are runtime-db, so the cofx + effect are :rf.db/runtime
```

Tests handler-level integration (snapshot read/write at `[:rf.runtime/machines :snapshots <id>]`, `:data`-to-`:db` lowering, fx composition) without going near the dispatch pipeline. **Possible only because `make-machine-handler` is a pure factory** — no registration, no test frame.

The handler resolves its id from the inbound event vector's first element (`:drawer/editor`), reads `(get-in db [:rf.runtime/machines :snapshots :drawer/editor])` for the current snapshot, and writes the next snapshot back at the same location.

### Level 3 — registered in a test frame

```clojure
(rf/with-new-frame [f (rf/make-frame {})]
  (rf/reg-event :my/editor {} (rf/make-machine-handler {...}))
  (rf/dispatch-sync [:my/init] {:frame f})   ;; seed via a setup dispatch
  (rf/dispatch-sync [:my/editor [:event]] {:frame f})
  (assert ...))
```

Full integration — error categories, trace events, drain semantics. **Required for spawned-actor patterns**, because the whole point of spawn is "a new handler gets registered dynamically and the parent can `dispatch` to it" — bypassing the registry tests something else.

### The pyramid

| level | what you test | speed | can't test |
|---|---|---|---|
| 1 — `machine-transition` | FSM logic, guards, action effect shapes | fastest | snapshot/db plumbing, fx integration |
| 2 — unregistered handler fn | handler-level wiring, `:data` lowering | fast | dispatch pipeline, spawn lifecycle |
| 3 — registered in test frame | full integration, spawn/destroy, cross-actor messaging | slowest | nothing |

## Worked example — Circle Drawer

The 7GUIs circle-drawer in this style. The modal-edit flow is a registered machine; canvas-add and undo/redo stay as ordinary handlers (orthogonal concerns).

```clojure
(ns circle-drawer.machine
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]))

;; ----------------------------------------------------------------------------
;; SCHEMA + UNDO INTERCEPTOR
;; ----------------------------------------------------------------------------

(def Circle [:map [:id :uuid] [:x :double] [:y :double] [:radius pos-int?]])
;; The :drawer/editor machine's snapshot lives at [:rf.runtime/machines :snapshots :drawer/editor]
;; — runtime-managed; not part of the :drawer schema. The runtime composes
;; [:rf.runtime/machines :snapshots]'s schema from registered machines' :data shapes; this slice
;; describes only the :drawer-owned domain state.
(def DrawerState
  [:map [:circles [:vector Circle]]
        [:undo [:vector :any]] [:redo [:vector :any]]])
(rf/reg-app-schema [:drawer] {:schema DrawerState})

;; EP-0022: interceptors are registered image members. Author with
;; `reg-interceptor` (the one public form) and reference by id in chains —
;; never an inline interceptor map/Var.
(rf/reg-interceptor :drawer/undoable
  {:doc "Push a circles snapshot onto :undo when the drawer mutates."}
  {:before (fn [ctx]
             (assoc-in ctx [:coeffects :prior-circles]
                       (get-in ctx [:coeffects :db :drawer :circles])))
   :after  (fn [ctx]
             (let [prior    (get-in ctx [:coeffects :prior-circles])
                   db-after (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})

;; ----------------------------------------------------------------------------
;; DOMAIN EVENT — the actual mutation lives here, not in the machine
;; ----------------------------------------------------------------------------

(rf/reg-event :drawer/apply-radius
  {:doc          "Persist a circle's new radius. Called by the editor machine on commit."
   :interceptors [:drawer/undoable]}
  (fn [{:keys [db]} [_ circle-id new-radius]]
    {:db (update-in db [:drawer :circles]
                    (fn [cs]
                      (mapv #(if (= circle-id (:id %)) (assoc % :radius new-radius) %)
                            cs)))}))

;; ----------------------------------------------------------------------------
;; MACHINE — event handler IS the machine
;;
;; Inspectability bias (§Inspectability bias): non-trivial actions are named
;; in the machine's :actions map. The right-click action seeds three keys
;; derived from the event — compound enough to deserve a name. The
;; close-dialog action both emits an :fx and clears :data — also compound.
;; The drag-slider and cancel-dialog actions are single-expression :data
;; updates, so they stay inline (the escape hatch).
;; ----------------------------------------------------------------------------

(rf/reg-event :drawer/editor
  {:doc "Modal-edit flow."}
  (rf/make-machine-handler
    {:initial :idle
     :data    {:circle-id nil :initial-radius nil :preview-radius nil}
     :actions
     {:begin-edit
      ;; Seed circle-id, initial-radius, and preview-radius from the right-click event.
      (fn [{[_ id radius] :event}]
        {:data {:circle-id      id
                :initial-radius radius
                :preview-radius radius}})

      :commit
      ;; Persist the previewed radius via :drawer/apply-radius and clear :data.
      (fn [{data :data}]
        {:fx   [[:dispatch [:drawer/apply-radius
                            (:circle-id data)
                            (:preview-radius data)]]]
         :data {:circle-id      nil
                :initial-radius nil
                :preview-radius nil}})}
     :states
     {:idle
      {:on
       ;; Note the event shape — the view passes the radius in the payload
       ;; rather than the machine reaching into app-db. Strict encapsulation:
       ;; cross-cutting data flows via the event vector, not via :db.
       {:right-click-circle
        {:target :editing
         :action :begin-edit}}}                              ;; resolves to :actions :begin-edit

      :editing
      {:on
       {:drag-slider
        ;; internal self-transition — no :target, so no exit/entry.
        ;; Single-key :data update, single non-branching expression — inline OK
        ;; per the inspectability-bias escape hatch.
        {:action (fn [{[_ new-r] :event}]
                   {:data {:preview-radius new-r}})}

        :close-dialog
        {:target :idle
         :action :commit}                                    ;; resolves to :actions :commit

        :cancel-dialog
        ;; Single :data clear, single non-branching expression — inline OK.
        ;; Nothing to apply — preview was never persisted.
        {:target :idle
         :action (fn [_]
                   {:data {:circle-id      nil
                           :initial-radius nil
                           :preview-radius nil}})}}}}}))

;; ----------------------------------------------------------------------------
;; DOMAIN EVENTS (orthogonal to the machine)
;; ----------------------------------------------------------------------------

(rf/reg-event :drawer/initialise
  (fn [_ _]
    ;; Domain state under :drawer; the editor machine's snapshot lives at
    ;; [:rf.runtime/machines :snapshots :drawer/editor] — runtime-managed; not seeded here.
    {:db {:drawer {:circles [] :undo [] :redo []}}}))

(rf/reg-event :drawer/add-circle
  {:interceptors [:drawer/undoable]}
  (fn [{:keys [db]} [_ x y]]
    {:db (update-in db [:drawer :circles] conj
                    {:id (random-uuid) :x x :y y :radius 30})}))

(rf/reg-event :drawer/undo
  (fn [{:keys [db]} _]
    {:db (let [{:keys [undo circles]} (:drawer db)]
           (if (empty? undo) db
               (-> db (assoc-in  [:drawer :circles] (peek undo))
                      (update-in [:drawer :undo]    pop)
                      (update-in [:drawer :redo]    (fnil conj []) circles))))}))

(rf/reg-event :drawer/redo
  (fn [{:keys [db]} _]
    {:db (let [{:keys [redo circles]} (:drawer db)]
           (if (empty? redo) db
               (-> db (assoc-in  [:drawer :circles] (peek redo))
                      (update-in [:drawer :redo]    pop)
                      (update-in [:drawer :undo]    (fnil conj []) circles))))}))

;; ----------------------------------------------------------------------------
;; SUBS — preview state is *display* state, not domain state
;; ----------------------------------------------------------------------------

(rf/reg-sub :drawer/circles      (fn [db _] (get-in db [:drawer :circles])))
;; The framework-registered :rf/machine sub returns the snapshot {:state :data}
;; for any machine — we parameterise it on :drawer/editor and compose against
;; it via :<-. (Read directly with @(rf/subscribe [:rf/machine :drawer/editor]).)
(rf/reg-sub :drawer/editor-state :<- [:rf/machine :drawer/editor] (fn [snap _] (:state snap)))
(rf/reg-sub :drawer/editor-data  :<- [:rf/machine :drawer/editor] (fn [snap _] (:data snap)))
(rf/reg-sub :drawer/editing? :<- [:drawer/editor-state] (fn [s _] (= s :editing)))
(rf/reg-sub :drawer/can-undo? (fn [db _] (seq (get-in db [:drawer :undo]))))
(rf/reg-sub :drawer/can-redo? (fn [db _] (seq (get-in db [:drawer :redo]))))

(rf/reg-sub :drawer/circles-with-preview
  :<- [:drawer/circles]
  :<- [:drawer/editor-data]
  :<- [:drawer/editing?]
  (fn [[circles ed editing?] _]
    (if editing?
      (mapv #(if (= (:id %) (:circle-id ed))
               (assoc % :radius (:preview-radius ed)) %)
            circles)
      circles)))

;; ----------------------------------------------------------------------------
;; VIEW
;; ----------------------------------------------------------------------------

(rf/reg-view main []
  (let [circles                @(rf/subscribe [:drawer/circles-with-preview])
        ;; the :rf/machine sub returns the whole snapshot; inline-destructure it.
        {state :state ed :data} @(rf/sub-machine :drawer/editor)
        editing?               (= state :editing)
        can-undo?              @(rf/subscribe [:drawer/can-undo?])
        can-redo?              @(rf/subscribe [:drawer/can-redo?])]
    [:div.drawer
     [:div.row
      [:button {:on-click #(rf/dispatch [:drawer/undo]) :disabled (not can-undo?)} "Undo"]
      [:button {:on-click #(rf/dispatch [:drawer/redo]) :disabled (not can-redo?)} "Redo"]]
     [:svg {:width 600 :height 400 :style {:border "1px solid #999"}
            :on-click (fn [e]
                        (when-not editing?
                          (let [r (.. e -currentTarget getBoundingClientRect)
                                x (- (.. e -clientX) (.-left r))
                                y (- (.. e -clientY) (.-top r))]
                            (rf/dispatch [:drawer/add-circle x y]))))}
      (for [{:keys [id x y radius]} circles]
        ^{:key id}
        [:circle {:cx x :cy y :r radius :fill "transparent" :stroke "black"
                  :on-context-menu (fn [e] (.preventDefault e)
                                     ;; pass radius in the event payload — machine cannot read :db
                                     (rf/dispatch [:drawer/editor [:right-click-circle id radius]]))}])]
     (when editing?
       [:div.dialog {:style {:border "1px solid #999" :padding "10px" :margin-top "5px"}}
        [:p (str "Adjust diameter of circle " (:circle-id ed))]
        [:input {:type "range" :min 5 :max 100 :step 1
                 :value (:preview-radius ed)
                 :on-change #(rf/dispatch [:drawer/editor [:drag-slider
                                                           (js/parseInt (.. % -target -value))]])}]
        [:div.row
         [:button {:on-click #(rf/dispatch [:drawer/editor [:close-dialog]])}  "Commit"]
         [:button {:on-click #(rf/dispatch [:drawer/editor [:cancel-dialog]])} "Cancel"]]])]))
```

**Modeling rule the example illustrates:** preview is *display* state, not domain state. The drag never persists into `:circles`; instead the `:drawer/circles-with-preview` sub merges `:preview-radius` from the editor's `:data` into the rendered circles at read time. Cancel is therefore a no-op on domain state — there is nothing to revert because nothing was persisted.

## Capability matrix

Per [000-Vision §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities), implementations declare which capabilities they support; **conformance is graded against the claimed capability list** rather than an all-or-nothing pass/fail. The matrix names each capability, what coverage it requires (prose / schema / fixture), and the v1 CLJS reference's claim for each.

### FSM-richness axis

| Capability | Coverage required | v1 CLJS reference | Notes |
|---|---|---|---|
| **Flat FSM** — states, transitions, guards, actions, `:entry` / `:exit`, wildcard `:*` | Prose: §Transition table grammar, §Action effect map; Schema: `:rf/transition-table` (flat); Fixtures: `machine-transition.edn` and the flat-FSM family | ✓ claimed | Already specced; the foundation. |
| **Hierarchical compound states** — nested `:states` in a state node; entry/exit cascading along the LCA path; vector / keyword target resolution; deepest-wins transition resolution with parent fallthrough | Prose: [§Hierarchical compound states](#hierarchical-compound-states); Schema: `:rf/state-node` (recursive) + `:rf/transition-target`; Fixtures: `hierarchical-compound-transition`, `hierarchical-cross-level-transition`, `hierarchical-parent-fallthrough` | ✓ claimed (specified) | Snapshot dual-form, LCA-based cascading, and deepest-wins resolution are locked. |
| **Eventless `:always` transitions** — fire as soon as a guard becomes true | Prose: [§Eventless `:always` transitions](#eventless-always-transitions); Schema: `:rf/state-node` extended for `:always` (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `always-single-microstep`, `always-depth-exceeded` | ✓ claimed (specified) | Microstep loop inside drain Level 3; bounded depth (default 16); self-loop forbidden at registration; trace events at both per-microstep and macrostep granularity. |
| **`:type :choice` transient / choice states** — a routing node that resolves immediately on entry to the first guard-passing candidate | Prose: [§`:type :choice` (transient / choice states)](#type-choice-transient--choice-states); Schema: `:rf/state-node` extended for `:type :choice` + `:choice` (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `machine-choice-resolve`, `machine-reg-error-choice` | ✓ claimed (specified) | Sugar over `:always` — desugars at registration / normalisation into an ordinary state carrying the candidate vector under `:always`, so the candidate walk, the macrostep microstep loop, and the birth-time settle all drive it unchanged. DIVERGENCE: a declarative guarded-candidate ARRAY, NOT XState's `choice`-function (a function-valued `:choice` is rejected). A choice state only routes (no `:entry` / `:exit` / `:on` / `:after` / `:timeout` / `:spawn` / …) and must include an unguarded default candidate; a self-targeting candidate is rejected at registration. |
| **Delayed `:after` transitions** — fire after a time delay | Prose: [§Delayed `:after` transitions](#delayed-after-transitions); Schema: `:rf/state-node` extended for `:after` (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `after-single-delay`, `after-stale-detection`, `after-hierarchy` | ✓ claimed (specified) | Epoch-based stale detection — no `:cancel-dispatch-later` fx; clock primitives live in `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`); SSR-mode no-ops timer scheduling; trace events at `:scheduled` / `:fired` / `:stale-after` granularity. |
| **State tags** — `:tags <set-of-keywords>` on a state node; snapshot carries the active-configuration tag union | Prose: [§State tags](#state-tags); Schema: `:rf/state-node` extended for `:tags`, `:rf/machine-snapshot` extended for `:tags` (see [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) and [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)); Fixtures: `tags-flat-machine`, `tags-compound-active-path-union`, `tags-empty-when-no-declaration`, `tags-round-trip-pr-str` | ✓ claimed (specified) | Strictly additive — the snapshot's `:tags` slot is elided when the union is empty. Framework sub `:rf/machine-has-tag?` plus the `(rf/machine-has-tag? id tag)` sugar covers the predicate query. Composes with hierarchical compound states (union along the active path) and will compose with parallel regions (union across every active region). |
| **Parallel regions** — `:type :parallel` with multiple concurrent regions | Prose: [§Parallel regions](#parallel-regions); Schema: `:rf/transition-table` extended for `:type` + `:regions`, `:rf/state-node` extended for the parallel-region body, `:rf/machine-snapshot`'s `:state` widened to the third arm (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)); Fixtures: `parallel-flat-two-regions`, `parallel-compound-region`, `parallel-tags-union-across-regions`, `parallel-broadcast-event-both-regions`, `parallel-spawn-scoped-to-region`, `parallel-after-scoped-to-region`, `parallel-always-cascade-per-region`, `parallel-initial-state-per-region`, `parallel-snapshot-round-trip`, `parallel-ssr-hydration` | ✓ claimed (specified) | The third `:state` arm — a map of region-name → keyword-or-vector-path. Shared `:data` across regions per §9.4 (per-region encapsulation is a signal to use the N-machine substitute pattern from [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features)). Composes with `:fsm/tags` (union across every active state in every region) and with `:fsm/eventless-always` / `:fsm/delayed-after` / `:actor/declarative-spawn` (per-region scoping; one region's `:after` timer doesn't fire transitions in sibling regions). |
| **History states** — `:type :history` pseudo-state re-entering a compound's last-active substate; shallow / deep / default-target | Prose: [§History states](#history-states-type-history--shallow--deep--default-target); Schema: `:rf/state-node` extended for the history-pseudo-state arm, `:rf/machine-snapshot` extended for the `:rf/history` slot (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)); Fixtures: `history-shallow-restores-direct-child`, `history-deep-restores-leaf-path`, `history-default-target-on-first-entry`, `history-per-region-parallel`, `history-dangling-path-falls-back` | ✓ claimed (specified) | A targetable pseudo-state (`{:type :history :deep? bool :default-target <target>}`) under a compound's `:states`. Records last-active configuration on the compound's exit cascade into the revertible `:rf/history` snapshot slot (map keyed by compound declaration path, region-qualified under `:type :parallel`); restores via the existing LCA / entry-cascade machinery. Missing `:deep?` => shallow; missing `:default-target` => the compound's `:initial`; a dangling recorded path after hot reload falls back to `:default-target` / `:initial`. |
| **Final states** — `:final?` on a leaf state terminates the machine; a `:spawn`d child's `:final?` fires the parent's `:on-done` with the child's `:output-key`-designated `:data` slot, then auto-destroys the child | Prose: [§Final states (`:final?` / `:on-done` / `:output-key`)](#final-states-final--on-done--output-key); Schema: `:rf/state-node` extended for `:final?` + `:output-key`; `:rf/invoke-spec` extended for `:on-done`; Fixtures: `final-state-singleton-auto-destroys`, `final-state-child-fires-on-done` | ✓ claimed (specified) | First-class `:final?` flag (loud, not `:meta`-buried). Auto-destroy is synchronous on entry to the final state. Singleton symmetry: a standalone machine reaching `:final?` also auto-destroys ("final means final"). |

### Actor-model axis

| Capability | Coverage required | v1 CLJS reference | Notes |
|---|---|---|---|
| **Own state + message ports** — actor identity is the registered event id; the state lives in runtime-db at `[:rf.runtime/machines :snapshots <id>]` | Prose: §Where snapshots live, §Strict encapsulation; Schema: `:rf/machine-snapshot`, `:rf/runtime-db`; Fixtures: machine-transition, machine-actor-isolation | ✓ claimed | Already specced. |
| **Imperative spawn / destroy** — `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` fx (the canonical actor-lifecycle fx-ids; emitted by `:spawn` desugar and authored by hand inside a machine action's `:fx` or any user event handler's `:fx`) | Prose: §Spawning; Schema: `:rf.fx/spawn-args`; Fixtures: spawn-from-action, destroy-clears-snapshot, spawn-on-spawn-callback | ✓ claimed | Already specced. |
| **Cross-actor send via `:fx`** — `[:dispatch [other-actor-id [:event]]]` | Prose: §Spawning §What spawning gives for free; Fixtures: cross-actor-send | ✓ claimed | Falls out of standard `:dispatch` fx; no new mechanism. |
| **Declarative `:spawn`** (sugar over spawn) — a state's `:spawn` translates to entry/exit actions that spawn / destroy a child actor | Prose: [§Declarative `:spawn`](#declarative-spawn); Schema: `:rf/state-node` extended for `:spawn` (per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `spawn-on-entry-destroy-on-exit`, `spawn-tracked-without-data-pending` | ✓ claimed (specified) | No new mechanics; pure sugar. `make-machine-handler` translates `:spawn` to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` at registration time. Composes with user-supplied `:entry` / `:exit` (user runs first). Per (Option A revised): the runtime tracks spawned ids at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` so `:on-spawn` is purely advisory user-side bookkeeping — the destroy cascade does not read the user's `:data`. |
| **Spawn-and-join via `:spawn-all`** — first-class parallel-region state-machines: a state node declares N child actors and a join condition (a closed two-member enum: `:all` / `:any`), the runtime fires one of three parent events when the join resolves and unconditionally cancels surviving siblings | Prose: [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all); Schema: `:rf/state-node` extended for `:spawn-all` (per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `spawn-all-join-all-completes`, `spawn-all-join-any-fails-cancels` | ✓ claimed (specified) | Sugar over N parallel `:spawn`s plus a runtime-owned join-state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` (the direct map shape — `:children` / `:done` / `:failed` / `:resolved?` / `:spec` co-mingle at the root). Sibling cancellation on the join decision is unconditional (matches Dash8/rf8 boot-page-reload semantics). |
| **`:system-id` named-machine addressing** — a `:rf.machine/spawn` whose args carry `:system-id` binds the actor in the per-frame `[:rf.runtime/machines :system-ids]` reverse index; `(rf/machine-by-system-id sid)` resolves the binding | Prose: [§Named addressing via `:system-id`](#named-addressing-via-system-id), [§Cross-machine messaging by name](#cross-machine-messaging-by-name); Schema: `:rf.fx/spawn-args` extended for `:system-id`; Fixtures: `spawn-with-system-id-then-lookup-resolves`, `spawn-without-system-id-leaves-index-empty`, `destroy-machine-clears-system-id-index`, `system-id-collision-warns-and-rebinds` | ✓ claimed (specified) | Opt-in. The reverse index lives in runtime-db so it inherits frame revertibility. Collisions emit `:rf.error/system-id-collision` and rebind (last-write-wins). |
| ~~**Wall-clock `:timeout-ms` on `:spawn` / `:spawn-all`**~~ | DROPPED in favour of state-level `:after`. See [§Wall-clock timeouts on `:spawn` — use parent state's `:after`](#wall-clock-timeouts-on-spawn--use-parent-states-after) and [MIGRATION §M-44](../migration/from-re-frame-v1/README.md#m-44-timeout-ms-removed-from-spawn--spawn-all--use-parent-states-after). | n/a | The `:after` capability subsumes this; one canonical primitive, not two. The `:fsm/delayed-after` capability above covers wall-clock-on-state semantics for both pure timed-transition states and `:spawn`-bearing states. |
| **SCXML / Stately / XState JSON interop** — bidirectional schema parity or paste-and-render compatibility | SCXML: shipped tool-side; XState JSON / Stately: deferred | ◑ partial (tool-side) | The **SCXML** half shipped as a pure-data round-trip in the machines-viz tool (`day8.re-frame2-machines-viz.scxml` — `spec->scxml` / `scxml->spec`, exact round-trip over the supported topology subset, rf2-6urjd), not in the runtime `machines` artefact. The **XState-JSON** (`machine->xstate-json`) and **Stately Inspector wire-format** halves remain deferred to v1.1+; tracked alongside as the interop family. |

### How conformance is graded

A re-frame2 port declares its capability list in its conformance harness manifest:

```clojure
{:port-id    :re-frame-cljs
 :capabilities #{:fsm/flat
                 :fsm/hierarchical
                 :fsm/eventless-always
                 :fsm/delayed-after
                 :fsm/tags
                 :fsm/parallel-regions
                 :fsm/final-states                    ;; :final? + :on-done + :output-key
                 :fsm/history                          ;; :type :history pseudo-state — shallow / deep / default-target
                 :actor/own-state
                 :actor/spawn-destroy
                 :actor/cross-actor-fx
                 :actor/declarative-spawn  ;; declarative spawn-on-entry / destroy-on-exit (the "Declarative :spawn" row) — see note below
                 :actor/spawn-and-join
                 :actor/system-id}}    ;; :actor/timeout retired per  — :fsm/delayed-after subsumes it
```

> **`:actor/declarative-spawn` names the declarative-`:spawn` capability; the user-facing grammar keys are `:spawn` / `:spawn-all`.** The capability id is spawn-rooted to mirror the `:spawn` / `:spawn-all` grammar and its imperative sibling `:actor/spawn-destroy`; the `declarative-` qualifier disambiguates it from that imperative sibling.

The harness runs every fixture whose `:fixture/capabilities` is a subset of the port's claimed list; fixtures requiring un-claimed capabilities are skipped (and reported as "not exercised"). The aggregate score is "passes / claimed-applicable" rather than "passes / total." A port that only claims `:fsm/flat` + `:actor/own-state` + `:actor/spawn-destroy` is fully conformant for that subset — there is no penalty for not claiming hierarchical-states, just an honest accounting of what works.

**Error category for unclaimed grammar:** when `make-machine-handler` encounters a key whose capability is not in the host's claimed capability list, it **rejects the registration** — the registration does not proceed, no handler is installed, and a single structured error trace event is emitted:

```clojure
{:operation :rf.error/machine-grammar-not-in-v1
 :op-type   :error
 :tags      {:category   :rf.error/machine-grammar-not-in-v1
             :failing-id <machine-id>
             :feature    <unsupported-key>             ;; e.g. :after, :type :history
             :reason     "Transition-table feature `<X>` is not in this implementation's claimed capability list. See [§Capability matrix](#capability-matrix)."}
 :recovery  :no-recovery}                              ;; registration is rejected — the handler is not installed
```

The disposition is **reject-at-registration** (`:no-recovery`): an unsupported key is a hard error raised at the only construction site (registration), not a trace-and-continue. This matches the neighbouring capability rows above — parallel regions, `:tags`, and `:spawn-all` all "raise at registration" when unclaimed — and is the disposition the [009 §Error contract](009-Instrumentation.md#error-contract) catalogue (the error-id SSOT) records as canonical. The error is registered as a category in [009 §Error contract](009-Instrumentation.md#error-contract).

Cross-references: [000 §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) for the goal text; [conformance/README.md](conformance/README.md) for the fixture-tagging convention.

## Substitutes for skipped features

**Parallel regions** are a first-class capability — see [§Parallel regions](#parallel-regions). The N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) remains valid and is the right answer when the regions are conceptually independent features (multiple tabs with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event). Parallel regions are the right answer when the regions are orthogonal axes of *one* feature that share a single `:data` blob (one form with three orthogonal axes, one widget with display + interaction, one page's render-mode predicates).

**History states** are a first-class capability — see [§History states](#history-states-type-history--shallow--deep--default-target). There is no snapshot-as-value substitute pattern to reach for. (The N-machines-per-region substitute for the *parallel* case above remains valid for conceptually-independent features.)

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): the Globally-registered guards/actions `(RESOLVED)` entry and the "Auto-cleanup of orphaned actors" entry that previously lived here have both been migrated to `## Resolved decisions` per SA-4's migration rule. Stately.ai / XState JSON interop is out of scope for v1; see (SCXML) for the v1.1+ interop family.

## Resolved decisions

### Globally-registered guards/actions vs machine-scoped (RESOLVED)

Resolved: machine-scoped. Guards and actions live in the machine's `:guards` / `:actions` maps inside the `make-machine-handler` spec; transition-table keyword references resolve **machine-locally** at registration time. There is no `reg-machine-guard` / `reg-machine-action` API and no `:machine-guard` / `:machine-action` registry kind. Cross-machine reuse is via Clojure vars (define a var; reference it from each machine's `:guards` / `:actions` map) — no framework support needed beyond ordinary var resolution. See [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and [§Inspectability bias](#inspectability-bias).

### Library packaging — in-tree or separate? (RESOLVED)

Resolved: **separate artefact**, `day8/re-frame-2-machines`. Per (executing Strategy B), the machine substrate ships as a per-feature artefact split out of core — `implementation/machines/src/re_frame/machines*` with its own `deps.edn` and shadow-cljs build target, and core-side cross-references late-bound via the hook registry so apps not using machines pay zero bundle cost. The earlier "prototype as separate, promote to in-tree once API stabilises" recommendation is superseded: the per-feature artefact pattern (machines, schemas, routing, flows, http, ssr, epoch) is now the standard packaging shape — see [Conventions.md §Packaging conventions](Conventions.md#packaging-conventions) for the catalogued split.

### Eventless `:always` transitions — microstep loop inside drain (RESOLVED)

Resolved: `:always` is a state-node key holding a vector of guarded transitions; the drain extends Level 3 with a unified microstep loop (prefer enabled `:always` → else dequeue one FIFO `:raise` → loop; the `:always`-before-raise order aligns to XState v5 / SCXML §3.13) that settles to a fixed point before commit. Default depth limit 16, error category `:rf.error/machine-always-depth-exceeded`. Any `:always` entry whose `:target` resolves to the declaring state (guarded or not) is rejected at registration with `:rf.error/machine-always-self-loop`; the canonical fixed-point loop is a targetless guarded `:always` with an `:action`. Trace events emitted at both per-microstep and outer-macrostep granularity. See [§Eventless `:always` transitions](#eventless-always-transitions).

### Sub-event call-site shape (RESOLVED)

Resolved: the dispatch shape for events targeting a machine is the sub-event form `[:machine-id [:inner-event-keyword & payload]]` — the machine handler resolves the second-position inner keyword as the FSM event. The flat form (`[:machine-id/inner-event payload]` with one `reg-event` registration per event) is **not** how machines are addressed. Why: fewer registry entries (one per machine, not one per event); call-site labels show "this is going to the editor machine"; works uniformly for spawned actors whose ids are gensym'd. See the worked examples in [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and the Circle Drawer.

### Multiple machine instances at one path

Snapshots live at the runtime-managed path `[:rf.runtime/machines :snapshots <id>]`, keyed by the registered id. Two registrations sharing an id collide at the registry layer (last-write-wins per the standard registration semantics, with a re-registration trace event); a single id never has two snapshot locations. The earlier "two machines at one `:path`" scenario cannot arise because users no longer pick a path. Per-frame isolation falls out of each frame having its own runtime-db and thus its own `[:rf.runtime/machines :snapshots]` map. See [§Where snapshots live](#where-snapshots-live).

### Auto-cleanup of orphaned actors — explicit `:rf.machine/destroy` for v1 (RESOLVED)

Resolved: v1 requires **explicit teardown** via `[:rf.machine/destroy <actor-id>]`. Auto-cleanup via an opt-in `:owned-by` ownership relation (whereby an actor whose owner is destroyed is itself destroyed) is a v1.1+ direction. The explicit-destroy surface matches `make-frame` / `destroy-frame!` and keeps the v1 lifecycle model uniform: every actor's life ends at a named, traceable site. Tooling and conformance fixtures can rely on the absence of implicit-destroy cascades; ports do not need to model an ownership graph to be v1-conformant. See [§Spawning — dynamic actors](#spawning--dynamic-actors) for the spawn / destroy surface; the `:rf.http/managed`-abort cascade per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) is the one composed-with cascade that fires off a `:rf.machine/destroy`.

### Spawn id format — `<id-prefix>#<n>` keyword (RESOLVED)

Resolved: a declarative-`:spawn` spawn allocates a keyword id of the form `<id-prefix>#<n>`, preserving any namespace on the prefix — e.g. an `:id-prefix :request/protocol` produces `:request/protocol#1`, `:request/protocol#2`, … The `#` separator is the instance-id marker and is unambiguous (Clojure keyword readers tolerate `#` in the name part, and no user-facing keyword convention uses it). `<n>` is a per-`<id-prefix>` monotonic integer starting at 1; the counter lives in the snapshot at `[:rf/spawn-counter <id-prefix>]` so allocation is deterministic from `(definition, snapshot, event)` (`machine-transition` is a pure function). `:id-prefix` defaults to the parent's `:machine-id`; an explicit `:fixed-actor-id` bypasses allocation entirely (the actor is bound under that literal). The slash-with-numeric-tail alternative (`:request.protocol/42`) is rejected — it collides with the namespace/name convention every other re-frame2 keyword follows, and a trailing numeric segment is not idiomatic Clojure. The format is shared by the imperative `[:rf.machine/spawn ...]` fx-id allocator (whose counter lives at `[:rf.runtime/machines :spawn-counter <machine-id>]` in the spawning frame's runtime-db) and the declarative-`:spawn` allocator (whose counter lives in-snapshot); both produce identically-shaped ids. See [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the `:rf/spawn-counter` slot schema and [§Declarative `:spawn`](#declarative-spawn) for the allocation call sites.

## Lessons from xstate (deliberate divergences)

> **Parity reference: the XState v6 direction.** re-frame2's machine parity reference is the **XState v6** design direction, not XState v5. XState v6 is still on the `alpha` dist-tag, but its alpha.1/alpha.2 surface is settled enough to act on, so re-frame2 tracks the v6 *direction* — plain guard/action functions over a declarative topology, a broader optional `:schemas` vocabulary, explicit state/spawn timeouts, choice states, private internal events, sharper parent/child ordering, and event-shaped completion. v5 is **not** the live behavioural baseline. The posture is **directional, not exact-upstream-alpha-chasing**: re-frame2 *rejects* exact compatibility with an alpha XState release and aims at v6's design principles, recording each intentional divergence rather than copying JavaScript runtime shapes (no v5/v6 helper-creator compatibility, no actor objects, no function-valued transitions). re-frame2 will re-confirm against v6 `latest` when it ships, but does not re-litigate the target on each alpha patch. This section records the *posture*, not new grammar.

For readers familiar with xstate, the explicit list of where re-frame2 differs — `ActorRef` vs snapshots, mailboxes vs the per-frame router, `raise` vs `:raise`, three-creation-modes vs one, hierarchy as data, `:context` vs `:data`, compound guards, action vectors, `setup({...})` vs machine-scoped `:guards` / `:actions`, `[:assign {...}]` vs `:data` returns, **`enqueueActions` vs the `{:data :fx}` effect map**, **`emit` vs domain-notification `dispatch`**, and **the v5 actor-logic creators (`fromPromise` / `fromCallback` / `fromObservable` / `fromTransition` / `fromEventObservable`) vs fx + flows** — lives in [CP-5-MachineGuide §Lessons from xstate](CP-5-MachineGuide.md#lessons-from-xstate-deliberate-divergences). The three v5-surface rows share one substrate root: re-frame2 is data-first with a single per-frame router/bus and models async as managed effects (Spec 014) and flows (Spec 013), not as actors — so the imperative action-list builder, the isolated-mailbox side-channel, and the non-machine actor abstractions all collapse into mechanisms re-frame2 already has.

Convergences: machines-as-actors, run-to-completion, encapsulated state, snapshots, definition/implementation split, transition tables as data.

### Three non-substrate divergences — ruled and recorded

re-frame2 is at very high parity with XState — the deep execution model (macrostep/microstep fixed-point loop, `:always` eventless transitions, `:raise` FIFO, exit→action→entry ordering with `:data` threading, `:reenter?` = the internal-by-default self-transition rule, history shallow+deep, final states + `:on-done`, `:after` cancel-on-exit via epoch) is all present and aligned; the v6 direction retains this execution model. Three capabilities re-frame2 omits are **not** substrate-constrained. Per the standing rule (the XState v6 direction is the parity reference; align unless substrate-constrained), each is recorded here:

1. **Multiple `:spawn` per state — ALIGNED IN SPIRIT, different spelling.** XState admits a *vector* of `invoke` per state. re-frame2 forbids a vector `:spawn` and ships the first-class multi-child surface: **`:spawn-all`** (declarative spawn-and-join of N parallel child actors, with named children and a closed two-member join condition — see [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all)). XState's vector-of-invoke maps cleanly onto `:spawn-all` — behavioural parity via a different expression (named children + explicit join, with unconditional sibling cancellation on the join decision). The independent / fire-and-forget N-children case (XState's fire-and-forget multi-invoke) is modelled as **N independent single-`:spawn`s** — each runs to completion and stays independently valuable, with no join to cancel them (a non-cancelling join is deliberately not offered; the `:cancel-on-decision? false` protocol was cut per rf2-w8gxxz).

2. **Nested parallel regions — DEFERRED post-v1.** XState supports a region whose own tree is `:type :parallel`; re-frame2 rejects it loudly at registration with `:rf.error/machine-parallel-nested-not-supported` (per [§Parallel regions](#parallel-regions)). This is **not** a permanent divergence — it is deferred. Supporting it requires generalizing re-frame2's **flat** state-value model (a region-name → leaf/path map) into a **recursive statechart tree**, which touches the snapshot shape, target resolution, the root fallback, done semantics, tags, history, and the Xray projection (all of which assume root-only parallelism). It is not frame/queue substrate-constrained.
   - **SA-4 record (post-v1, untracked note — no bead filed yet).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md), this is a `:post-v1 tracked` candidate carried as an untracked note until its trigger fires. **Reconsideration trigger:** a real machine presents a genuine **two-level parallel cross-product** — two independent axes *inside* one already-parallel region — that **cannot** be flattened to top-level sibling regions (per the flattening idiom below) and **cannot** decompose into N colocated top-level parallel machines without losing a coordination the app actually needs (e.g. a shared `:data` slot both sub-axes read/write within the nested boundary). Until such a case is reported, the flat model plus the two escape hatches (flatten to more top-level regions, or register N machines) is sufficient and the fail-closed rejection stands. Filing the tracking bead is what converts this note to `:post-v1 tracked`.

3. **`:after` at the parallel root — ALIGNED.** XState allows `after` at any level, including a `<parallel>` node. re-frame2 ships a **root-owned root `:after`** (per [§Root parallel `:after`](#root-parallel-on--the-ancestor-fallback)). A root `:after` is owned by the root: scheduled at machine birth, alive for the whole machine, stale-gated by the root's own per-path epoch. It reuses the parallel-root `:on` transition grammar (region-qualified targets, leaves untargeted regions unchanged, action/fx-only timeouts, traced as a root-owned delayed transition). There is no `:rf.error/machine-parallel-root-after-not-supported` rejection.

### Deliberate name divergence — `:spawn` (NOT `:invoke`)

re-frame2's declarative child-actor key is **`:spawn`**, not xstate's `:invoke`. This is a divergence on the single most semantically-loaded machine surface. Names converge elsewhere (`:final?`, `:on-done`, `:on-error`, `:guard`, `:action`, `:entry`, `:exit`, `:after`, `:always`, `:tags`, `:type :parallel`, `:regions`, `:system-id`); the distinct `:spawn` name marks the spawn-on-entry / destroy-on-exit slot as the surface where the semantics diverge the most:

- re-frame2 ships `:on-error` as a first-class `:spawn` sibling — a **transition** the parent takes when the spawned child fails (XState `invoke onError` parity; see [§`:on-error`](#on-error--child-failure-control-flow)). The `:rf.error/*` machinery + the `:on-child-error` join hook (on `:spawn-all`) remain; `:on-error` is the single-child declarative control-flow form.
- re-frame2 has no `:onSnapshot` — the snapshot lives in `runtime-db` and is read by subscribing to `:rf/machine`.
- re-frame2 has no per-actor mailbox — events route through the per-frame queue.
- re-frame2's `:spawn` IS state-bound (destroyed on exit) by construction; xstate's `:invoke` shares the property but the surface invites the assumption that other lifetime patterns are also supported (they aren't in v1).

The declarative key **aligns with the imperative fx-id** `:rf.machine/spawn` — one verb for "make a child actor", whether declarative-via-state-node or imperative-via-fx. The runtime-stamped reserved keys match: `:rf/invoke-id` (the prefix-path of the `:spawn`-bearing state node), `:rf/spawn-all-id`, `:rf/spawn-all-child-id`. Trace ops match: `:rf.machine.spawn-all/started`, `:rf.machine.spawn-all/all-completed`, `:rf.machine.spawn/cancelled-on-join-resolution`, …

The v6 direction adds no public `:invoke` alias; `:spawn` / `:spawn-all` are the sole public spelling. See [§Declarative `:spawn`](#declarative-spawn), [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all), and [migration/from-re-frame-v1/README.md §M-56](../migration/from-re-frame-v1/README.md#m-56-machine-vocabulary-divergence--invoke--spawn--invoke-all--spawn-all).

## Future

Post-v1 work that is in scope conceptually but does not ship in v1.

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): the items in this section are **post-v1, untracked notes** — design directions in scope for re-frame2 beyond v1 but with no concrete tracking bead filed yet (so none yet qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`). Each carries a `#### Post-v1 Tracking` block recording its v1 foundation and a concrete **Reconsideration trigger** — the checkable condition that un-defers it; a tracking bead is filed for an item **only when its trigger fires**. Items already shipped tool-side (Mermaid, SCXML round-trip) are noted inline as done.

### Diagram export from transition tables

The transition table is data; rendering it as a diagram is straightforward. The runtime `machines` artefact ships **no** exporter (diagram export is a tool-side code-gen concern, so the runtime stays pure-engine — Mike-ruled (b), rf2-sqhqu); a `(rf/machine->…)` runtime surface is intentionally not shipped. Status of the candidates:

- **Mermaid — shipped tool-side.** `day8.re-frame2-machines-viz.mermaid/emit` (in the machines-viz tool) emits a fenced Mermaid `stateDiagram-v2` block from a normalised machine definition, with the ergonomic `chart-as-mermaid` / `copy-mermaid-to-clipboard!` wrappers in `…machines-viz.export`. Lossy by design (`:after` rings + `:spawn-all` rows omitted; flagged inline). Renders inline in GitHub markdown, VS Code preview, AI-agent prompts. Apps that want Mermaid require the machines-viz jar; apps that don't pay nothing.
- **D2 — not yet shipped.** A `machine->d2` emitter (parallel to the Mermaid lane) remains a post-v1 candidate.

XState/Stately JSON export (`machine->xstate-json`) is part of the v1.1+ interop family, not a v1 deliverable; tracked alongside (SCXML — whose tool-side round-trip has shipped in machines-viz, per the capability matrix above).

Mermaid/D2 are AI-fluent — LLMs read and write them confidently — which makes diagram export the cheapest way to extend AI-amenability of machine code.

### Inspector wire-format

Stately Inspector is a documented event protocol that any tool can subscribe to. re-frame2's machine traces (`:op-type :rf.machine`, `:operation :rf.machine/transition`, machine-emitted dispatches carrying `:source :machine-action`, `:tags` carrying state/event/snapshot) are already very close in shape. A mapping document — *re-frame2 trace ↔ Stately Inspector event* — lets external xstate-aware tools watch a re-frame2 app for free, and lets AIs reuse vocabulary they already know.

See [Tool-Pair.md](Tool-Pair.md) for the tooling story; the Stately mapping is one consumer.

#### Post-v1 Tracking

- **Foundation in v1.** The trace stream is the substrate: machine traces already emit the state/event/snapshot facts Stately Inspector's protocol carries (per [009 §Trace events](009-Instrumentation.md)), and the [Tool-Pair contract](Tool-Pair.md) is the subscription seam any external tool attaches through. No runtime change is needed to write the mapping — it is a pure data transform over an existing stream.
- **Scope deferred.** The mapping document itself: the field-by-field *re-frame2 trace op ↔ Stately Inspector event* table, the transport shim that emits Inspector-shaped events from the trace subscription, and the round-trip fidelity notes (which re-frame2 facts have no Inspector counterpart and vice-versa).
- **Reconsideration trigger.** A concrete external **xstate-aware tool asks to attach** to a running re-frame2 app — i.e. someone wants to point Stately Inspector (or an Inspector-protocol consumer) at a re-frame2 machine and needs the wire-format bridge to do it. That request is the case that makes the mapping worth writing.
- **Out of scope for this note.** Full XState/Stately **JSON machine-definition** interop (`machine->xstate-json`, paste-and-render parity) — that is the separate v1.1+ interop family tracked under [§Post-v1 — the `re-frame.machines` library](#post-v1--the-re-framemachines-library); this note is only the *live-trace ↔ Inspector-event* observability bridge, not definition import/export.

### Model-based testing harness — `re-frame.machines.test`

A post-v1 library, planned as `re-frame.machines.test`, treats the transition table as a graph and generates test cases automatically. xstate's `@xstate/test` is the reference; re-frame2's pure `machine-transition` and machine-scoped `:guards` make the analogue cheap.

The substrate guarantees needed by the harness — all already locked in v1:

- **Pure transition function.** `(machine-transition definition snapshot event)` is deterministic; the harness can simulate any path without running the full runtime.
- **Data-only transition tables.** `:states` / `:on` / `:always` / `:after` / `:spawn` / `:spawn-all` are all readable as data; no instrumentation, reflection, or special build steps required.
- **Machine-scoped guards as functions.** The harness can call `:guards` directly with synthesised snapshots to find inputs that make each guard `true` and `false` — generating test data, not just paths.
- **Machine-scoped actions as functions.** Same property; the harness can compose action effects without runtime side effects.
- **Conformance corpus shape.** Generated test cases land as EDN fixtures in the existing corpus format; same fixture exercises both the user's machine logic and any conformant implementation's machine substrate.

The harness's locked design (per the model-based-testing follow-up):

- **Default coverage model:** transition coverage (every transition fires at least once). State coverage and guard coverage are opt-in; path coverage (n-step combinations) for advanced cases.
- **`:after` timers** are included with explicit time-advance steps using the test-clock pattern from [`re-frame.interop`](002-Frames.md#interop-layer--clock-primitives--see-spec-005).
- **Action / spawn stubbing** is auto-installed by the harness (registered fxs become no-ops in test mode); recursive coverage of spawned children is opt-in.
- **Output:** EDN fixtures in the corpus shape so generated tests are trace-comparable across implementations.

The harness is post-v1 because v1's substrate is sufficient — the harness builds on top without runtime changes. Two consumers will benefit:

1. **AI-implementability story** — when an AI implements re-frame in a new language (per [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and [Implementor-Checklist](Implementor-Checklist.md)), the harness produces a coverage corpus the implementation must pass.
2. **AI scaffolding of new machines** — an AI scaffolding a new application's machine generates its test corpus before writing any test by hand; reduces missed-edge-case bugs.

See [008-Testing.md §Future](008-Testing.md) for the testing-side forward-pointer.

### Declarative state-scoped child machines

The post-v1 `re-frame.machines` library may surface a `:child-machine` slot on a state node that desugars to entry/exit actions which spawn / destroy a child via the standard `:rf.machine/spawn` / `:rf.machine/destroy` mechanism. No new substrate; pure sugar over the v1 surface.

#### Post-v1 Tracking

- **Foundation in v1.** The desugaring target is already shipped: entry/exit actions plus the `:rf.machine/spawn` / `:rf.machine/destroy` fx are v1 (per [§Spawning — dynamic actors](#spawning--dynamic-actors)). A state-scoped child today is written by hand as an `:entry` `[:rf.machine/spawn …]` paired with an `:exit` `[:rf.machine/destroy …]`; `:child-machine` would only fold that pair into one declarative slot.
- **Scope deferred.** The sugar itself: the `:child-machine` slot grammar, its lowering to the entry/exit spawn/destroy pair, the id-binding convention for the state-scoped child, and the fail-loud categories for a malformed `:child-machine` value.
- **Reconsideration trigger.** **Repeated hand-rolled entry/exit spawn boilerplate in real apps** — the same `[:rf.machine/spawn …]`-on-`:entry` / `[:rf.machine/destroy …]`-on-`:exit` pairing recurs across enough real machines that folding it into one `:child-machine` slot removes meaningful ceremony (and the error-prone risk of forgetting the paired `:exit` destroy). Until that pattern is observed recurring, the manual entry/exit pair is the idiom and no sugar is added.
- **Out of scope for this note.** Any *new lifetime semantics* — `:child-machine` is state-bound (spawn-on-entry / destroy-on-exit) by construction, exactly like the v1 `:spawn` slot; a child that must outlive its declaring state is an imperative `[:rf.machine/spawn …]`, not this sugar.

## Disposition

Post-v1 per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap). The split is on **what's a foundation** vs **what's scaffolding on top of the foundation**.

The v1 ship-list and the post-v1 follow-up are itemised below.

### v1 ships the machine-as-event-handler foundation

- `(make-machine-handler spec)` — pure factory returning an `reg-event`-compatible handler fn that reads/writes the snapshot at `[:rf.runtime/machines :snapshots <id>]`, calls `machine-transition`, lowers `:data` / `:fx` / `:raise` / `:rf.machine/spawn` into a standard effect map. Registers nothing, closes over no global state, does not know its own id. Spec keys: `:initial`, `:data`, `:guards`, `:actions`, `:states`, `:on`, `:meta` — no `:path` (the location is runtime-managed; see [§Where snapshots live](#where-snapshots-live)). The `:guards` and `:actions` maps declare the machine's named guard / action implementations; transition-table keyword references resolve **machine-locally**, validated at registration time.
- `(machine-transition definition snapshot event)` → `[next-snapshot effects]` — pure function. JVM-runnable. No re-frame dependencies; guard/action references resolve against the definition's own `:guards` / `:actions` maps.
- The `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` fx for dynamic actor lifecycle (canonical surface; the v1 public fns `spawn-machine` / `destroy-machine` are dropped per [MIGRATION.md §M-26](../migration/from-re-frame-v1/README.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces)).
- The `:raise` reserved fx-id inside `:fx` (machine-internal); the `:rf.machine/spawn` and `:rf.machine/destroy` fx-ids registered globally for actor lifecycle.
- `[:rf.runtime/machines :snapshots <id>]` as the reserved runtime-db storage scheme; `:rf/machine?` registration-metadata flag.
- `(rf/machines)` and `(rf/machine-meta id)` — discovery lens over the event registry per [§Querying machines](#querying-machines).
- The framework-registered `:rf/machine` parametric sub — the canonical `[:rf/machine <id>]` read surface.
- Four-level drain semantics per [§Drain semantics](#drain-semantics) — including the gotchas listed in [§Drain semantics gotchas](#drain-semantics-gotchas).
- The v1 transition-table grammar subset per [§Capability matrix](#capability-matrix) and [§Transition table grammar](#transition-table-grammar).
- The snapshot shape (`{:state :data :meta?}`) and the persist/restore stability invariants per [§Snapshot shape](#snapshot-shape).
- Inspection trace events (`:rf.machine.lifecycle/created`, `:rf.machine/event-received`, `:rf.machine/started`, `:rf.machine/transition`, `:rf.machine/snapshot-updated`, `:rf.machine.spawn/spawned`, `:rf.machine/destroyed`, `:rf.machine/guard-evaluated`, `:rf.machine/action-ran`, etc. — see [009 §Trace events](009-Instrumentation.md) for the canonical emit-site list and [§Trace events — guard evaluations and action runs](#trace-events--guard-evaluations-and-action-runs) for the guard/action payload contract).
- The `:rf.error/machine-grammar-not-in-v1`, `:rf.error/machine-action-exception`, `:rf.error/machine-action-wrote-db`, `:rf.error/machine-raise-depth-exceeded`, `:rf.error/machine-always-depth-exceeded`, `:rf.error/machine-always-self-loop`, `:rf.error/machine-unresolved-guard`, `:rf.error/machine-unresolved-action`, `:rf.error/machine-bad-target`, `:rf.error/machine-unresolved-target`, `:rf.error/machine-spawn-all-bad-shape`, `:rf.error/machine-spawn-all-duplicate-id`, and `:rf.error/machine-spawn-all-with-spawn` error categories. (The `:timeout` / `:on-timeout` registration categories — `:rf.error/machine-timeout-without-on-timeout`, `:rf.error/machine-on-timeout-without-timeout`, `:rf.error/machine-bad-timeout-duration`, `:rf.error/machine-timeout-after-collision` — are catalogued under [§`:timeout` / `:on-timeout`](#timeout--on-timeout-state--spawn); there is no `:timeout-ms` slot — `:rf.error/spawn-timeout-ms-removed` rejects it.) Per [Spec-Schemas §TransitionTarget](Spec-Schemas.md#rfstate-node), every transition slot's `:target` (`:on` / `:after` / `:always` / a compound's `:on-done` / a `:spawn`-bearing state's `:spawn :on-error`) is validated at registration: a malformed-shape target (not a keyword / non-empty vector / the `:same-state` sentinel) raises `:rf.error/machine-bad-target`, and a keyword / vector target that resolves to no declared state (or `:type :history` pseudo-state) raises `:rf.error/machine-unresolved-target`. A keyword target names a SIBLING (a direct child of the declaring state's parent compound); a vector target is an absolute path from the (region) root.
- The `:rf.warning/no-clock-configured` warning category (advisory; emitted when `:after` is exercised on a host whose `re-frame.interop` clock layer hasn't been wired).
- The eventless `:always` capability per [§Eventless `:always` transitions](#eventless-always-transitions): state-node `:always` slot, microstep loop within Level 3 drain, default depth-16 limit, self-loop guard at registration time, dual-granularity trace events.
- The delayed `:after` capability per [§Delayed `:after` transitions](#delayed-after-transitions): state-node `:after` slot accepting `{<delay> → <transition-spec>}` where `<delay>` is `pos-int?`, a subscription vector (`[:sub-id & args]` resolved through `subscribe`'s machinery; re-resolves on subscription change per [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)), or `(fn [snapshot] ms)`. Epoch-based stale detection (no `:cancel-dispatch-later` fx), SSR no-op rule, clock primitives in `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`), and the `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` / `:rf.machine.timer/cancelled` (with `:reason` closed set) / `:rf.machine.timer/skipped-on-server` trace events. The whichever-fires-first cancellation cascade (per [§Whichever fires first wins](#whichever-fires-first-wins)) composes with the in-flight `:rf.http/managed` abort contract per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts).
- The state-tags capability per [§State tags](#state-tags): state-node `:tags <set-of-keywords>` slot; runtime maintains the active-configuration tag union at `[:rf.runtime/machines :snapshots <id> :tags]` recomputed on every transition (including `:always` microsteps); framework sub `:rf/machine-has-tag?` plus the `(rf/machine-has-tag? id tag)` sugar; empty-union elision per snapshot-size optimisation; reserved framework namespace (`:rf/*` / `:rf.*/*`).
- The spawn-and-join `:spawn-all` capability per [§Spawn-and-join via `:spawn-all`](#spawn-and-join-via-spawn-all): state-node `:spawn-all` slot accepting a vector of child invoke-specs plus `:join` (a closed two-member enum `:all` / `:any`) / `:on-child-done` / `:on-child-error` / `:on-all-complete` / `:on-some-complete` / `:on-any-failed` keys, runtime join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]` (direct-map shape — `:children` + `:done` + `:failed` + `:resolved?` + `:spec` co-mingled at the root, NO nested `:join` sub-map), unconditional sibling cancellation on the join decision, and the `:rf.machine.spawn-all/started` / `:rf.machine.spawn-all/all-completed` / `:rf.machine.spawn-all/some-completed` / `:rf.machine.spawn-all/any-failed` / `:rf.machine.spawn/cancelled-on-join-resolution` trace events. New error categories `:rf.error/machine-spawn-all-bad-shape`, `:rf.error/machine-spawn-all-duplicate-id`, `:rf.error/machine-spawn-all-with-spawn`.
- The history-states capability per [§History states](#history-states-type-history--shallow--deep--default-target): a `:type :history` pseudo-state under a compound's `:states` carrying `:deep?` (default shallow) and optional `:default-target` (default the compound's `:initial`); record-on-exit of the compound's last-active configuration into the revertible snapshot-root slot `:rf/history` (a map keyed by compound declaration path, region-qualified under `:type :parallel`); restore-on-re-entry via the existing LCA / entry-cascade machinery (deep = full leaf path, shallow = recorded direct child then `:initial` descent, never-entered = `:default-target` / `:initial`); dangling-recorded-path fallback after hot reload. Capability axis `:fsm/history`.
- The `:timeout` / `:on-timeout` capability per [§`:timeout` / `:on-timeout`](#timeout--on-timeout-state--spawn): state-level and spawn-level `:timeout` (a positive-integer ms or an ISO-8601 duration string — the XState `"5s"` / `"10ms"` shorthand is REJECTED) + `:on-timeout` transition, lowering onto the `:after` timer mechanism (distinct authoring intent, one mechanism — `:timeout` and `:after` coexist). Registration fail-loud categories `:rf.error/machine-timeout-without-on-timeout`, `:rf.error/machine-on-timeout-without-timeout`, `:rf.error/machine-bad-timeout-duration`, `:rf.error/machine-timeout-after-collision`. Capability axis `:fsm/timeout`. (There is no `:timeout-ms` slot; `:rf.error/spawn-timeout-ms-removed` rejects it.)
- The cancellation cascade for in-flight `:rf.http/managed` requests per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts): the `:rf.machine/destroy` path aborts every in-flight `:rf.http/managed` request the destroyed actor had issued, via the `:http/abort-on-actor-destroy` late-bind hook. Triggers include parent state exit, parent's `:after` firing, `:spawn-all` join-resolution sibling cancel, frame destroy, and imperative `[:rf.machine/destroy <actor-id>]`. Each abort emits `:rf.http/aborted-on-actor-destroy` per [Spec 009 §Trace events](009-Instrumentation.md). Direct dispatches from event handlers (no spawned-actor envelope) are NOT subject to the cascade — apps that want HTTP-tied-to-state-occupancy lifetimes spawn child machines.

### Post-v1 — the `re-frame.machines` library

Richer scaffolding on top of the v1 foundation. None of the items below add a new substrate — each desugars into the v1 surface:

- **Sugar in transition tables:** `:child-machine` declarative state-scoped child binding (desugars to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy`). (Hierarchical state nodes, `:always`, `:after`, `:spawn`, parallel state nodes, **final states with `:on-done`**, and **history states** are all v1; see the v1 ship list above.)
- **XState/Stately JSON interop (v1.1+):** `machine->xstate-json` converter, paste-and-render parity, Stately-Inspector wire-format mapping — still deferred. (The **SCXML** half of this family has already shipped tool-side as a pure-data round-trip in machines-viz, per the capability matrix above.)
- **Visualisation tooling:** the **Mermaid** exporter has shipped tool-side (`machines-viz.mermaid/emit`, rf2-sqhqu); a `machine->d2` exporter remains a post-v1 candidate.
- **Model-based testing harness:** `@xstate/test`-style graph traversal over the transition table.
- **Recurring timers, wall-clock delays, pause/resume on `:after`** — explicitly out of scope for v1; see [§What `:after` does *not* include](#what-after-does-not-include).
