# Runtime Subsystems

> **Type:** Reference
> The unifying conceptual frame for every framework-owned **durable-state** surface in re-frame2 — the children of the runtime-db partition (machines, routing, elision, SSR, and EP-0003's shipped resources / work-ledger / mutations). Names the five clauses every conformant runtime subsystem inherits, so a new subsystem (a 3rd-party `:rf.runtime/<lib>` extension) can be evaluated against a single checklist instead of re-deriving the shape each time. This is the **durable-state analogue of [Managed-Effects.md](Managed-Effects.md)** — Managed-Effects names the eight-property shape every framework-owned *effect* surface satisfies; this doc names the five-clause shape every framework-owned *state* subtree satisfies.
>
> The same doc also owns the **runtime-realm container** ([§Runtime realms](#runtime-realms--the-container), [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md) D1) — the value that owns the *non-durable* operational layer beside the durable subsystems: the registrar, the installed adapter, the capability map, and the **host-transient** subsystem state. The durable subsystems live inside the frames a realm owns; the realm owns the operational environment around them.

## Why this doc exists

re-frame2's frame value has two partitions — `:rf.db/app` (the app's map) and `:rf.db/runtime` (framework-owned durable state). The runtime-db partition is not a single blob: it is a map whose top-level children are each a framework subsystem's reserved subtree, qualified under `:rf.runtime/*` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). The shipped children are the four v1 subsystems — `:rf.runtime/machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr` — plus EP-0003's shipped resource trio, `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and `:rf.runtime/mutations` (the latter present only when the app registers a mutation).

Each child independently re-implements the **same five properties**: it owns a reserved subtree, it is written only by framework code (via a named authority path), it is read by app code only through public subscriptions/accessors, it has a serialization/elision/projection policy for the SSR and off-box wire boundaries, and it has a teardown contract distinguishing durable facts (kept) from transient handles (dropped). Before this doc those five properties lived implicitly across [002 §Write authority](002-Frames.md#write-authority-is-by-convention), [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to), [002 §Durable vs transient](002-Frames.md#durable-vs-transient), [002 §Destroy](002-Frames.md#destroy), and each subsystem's own Spec — never named as one shape.

**A runtime subsystem is a framework-owned, durable, revertible slice of frame-state living under a reserved `:rf.runtime/<name>` child of the runtime-db partition, whose mutation is framework-only, whose reads go through a public sub/accessor API, and whose serialization and teardown obey an explicit per-subsystem policy.** App code never reaches into a subsystem's subtree directly; it dispatches the subsystem's events and reads its public subs.

Naming the shape turns the ad-hoc child list into **gradeable instances**: a new subtree is admitted by answering all five clauses, and the framework-authority question ("who may write runtime-db?") becomes enumerable — *whoever registers a runtime subsystem* — rather than a special-case carve-out per feature.

### One frame value owns every per-frame subsystem — one registry, one teardown path

> **[EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md) (accepted 2026-06-18).** This subsection records the EP-0024 ownership backbone the five-clause contract sits on. The vocabulary it leans on — `frame value`, `frame id`, `resolved image generation` — is defined in [Conventions §Frame vocabulary](Conventions.md#frame-vocabulary--one-name-per-frame-fact).

A live frame is **one value** — the [frame value](Conventions.md#frame-vocabulary--one-name-per-frame-fact), the lifecycle token `make-frame` returns — found in **one live frame registry** that maps a [frame id](Conventions.md#frame-vocabulary--one-name-per-frame-fact) to that value. The frame value is the single conceptual owner of, or reaches through one registry entry, **every** per-frame runtime fact: both durable partitions (`:rf.db/app`, `:rf.db/runtime`), every `:rf.runtime/*` subsystem subtree the five-clause contract grades, the event queue and drain state, the subscription cache and topology, the epoch/history projection state, the host-transient subsystem state keyed beside it, the lifecycle hooks, and the [resolved image generation](Conventions.md#frame-vocabulary--one-name-per-frame-fact) the frame runs against.

There is **no second public "live frame" registry** — no separate object registry kept coherent with a backing-record registry, no two-constructor split that creates one half and then the other in a load-bearing order. The implementation MAY split storage internally for layering or performance, but there is exactly one public and conceptual owner (the frame value found by frame id), reached through `make-frame` as the single public constructor (per [EP-0024 §One constructor](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)). This is the substrate the five-clause subsystem grading assumes: a subsystem's subtree, its host-transient side tables, and its teardown hook all hang off the **same** frame value, so destroying a frame runs **one teardown path** (see [clause 5](#5-teardown)) rather than two registries whose cleanups can succeed or fail independently.

## Scope — this is NOT the generic N-partition design

This doc organizes the runtime-db partition's **own children**. It does **not** add partitions to the frame value, and it does **not** revisit the two-partition app/runtime split.

The frame value stays exactly two partitions: `:rf.db/app` and `:rf.db/runtime` (per [002 §The two partitions](002-Frames.md) and [Conventions §Reserved partition keys](Conventions.md#reserved-app-db-keys)). The earlier "generic N-partition app-db" proposal — letting arbitrary top-level framework partitions sit beside `:db` — was **rejected**; app-db remains a pure application contract an AI agent reads without framework noise. This doc is downstream of that decision: it describes how the *single* framework partition (`:rf.db/runtime`) sub-divides into subsystem children. The two-partition frame is untouched.

## The five-clause contract

Every runtime subsystem MUST answer these five clauses. A subtree that answers fewer is not a runtime subsystem — it is ad-hoc runtime state, outside the contract that tools, SSR projection, and the AI-Audit assume.

### 1. Subtree

The subsystem owns exactly one reserved `:rf.runtime/<name>` child of the runtime-db partition, plus the closed set of slots beneath it. The name is reserved in [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) (the reserved-key table) and detailed in [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue) (the per-slot path catalogue). The reserved set is **fixed-and-additive**: existing children cannot be repurposed; new children are added by Spec change. Each child is allocated **lazily** — absent until the first subsystem write — and per-frame isolated (each frame owns its own runtime-db).

> The reserved `:rf.runtime/*` key table is owned by [Conventions.md](Conventions.md); this doc **references** it and does not duplicate it. When a new subsystem lands, its child key is added to the Conventions table *and* a grading row is added here.

### 2. Write authority

Only framework (and runtime-extension) code mutates the subtree; ordinary application code never writes it directly. The authority is **by convention surfaced through dev diagnostics, not a security boundary** (per [002 §Write authority is by convention](002-Frames.md#write-authority-is-by-convention), Mike ruling #4). A subsystem mints write authority through one of two named paths (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)):

- **Event-handler authority** — the subsystem's `reg-event` registrations stamp the general `:rf/framework-authority? true` registration-meta key (or carry the `:rf/machine? true` stamp, which the runtime folds in). The runtime reads the stamp when assembling the event context; a returned `:rf.db/runtime` effect from a stamped handler is in-bounds, and an *un*stamped (ordinary app) handler returning `:rf.db/runtime` fires the `:rf.warning/app-handler-runtime-effect` dev diagnostic.
- **Privileged-helper authority** — the subsystem writes through `swap-runtime-db!` / `replace-frame-state!` (the privileged frame-state mutators of [002 §Frame-state value accessors and mutators](002-Frames.md#frame-state-value-accessors-and-mutators)), bypassing the event-handler path entirely (and so bypassing the diagnostic).

A subsystem MUST name which path(s) carry its writes. A subsystem that writes runtime-db from an *un*declared handler is the gap the diagnostic catches.

### 3. Read API

App code reads subsystem state only through the subsystem's **public subscriptions or accessor fns** — never raw runtime-db paths. Framework subscriptions read the runtime-db partition (per [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to)); the projection-equality model makes a runtime-only change visible to framework subs and invisible to ordinary app subs. A subsystem MUST name its public read surface. A subsystem with no app-facing read surface (a purely internal registry) states that explicitly — it is read only by framework code and tools, never by app subs.

### 4. Projection / elision policy

The subsystem declares what crosses each wire boundary:

- **SSR / hydration projection** — what serializes onto the `:rf/hydration-payload`'s optional `:rf/runtime-db` slice and rehydrates client-side. The projection is an **allowlist by subsystem child** (symmetric to the `:rf/app-db` fail-closed allowlist): a new transient sub-key under a shipped subsystem does NOT silently leak. (See [011 §The hydration payload](011-SSR.md) and the reference `project-runtime-db` projector.)
- **Off-box redaction** — what is redacted before leaving the box, composed through the single shared `rf/elide-wire-value` walker (per [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) and [Managed-Effects §5](Managed-Effects.md#5-sensitive--large-composition)).
- **Production elision** — what is elided in production builds.

A subsystem MUST name which of its slots are durable-and-shipped vs transient-and-stripped at the SSR boundary.

### 5. Teardown

On `destroy-frame!`, the subsystem distinguishes **durable facts** (which ride frame-state serialization, hydration, restore, and time-travel) from **transient handles** (host handles, in-flight registries, caches — torn down and never serialized), per [002 §Durable vs transient](002-Frames.md#durable-vs-transient). The subsystem hangs its frame-scoped cleanup off the single normative teardown boundary — a published teardown hook the core invokes in the strict order of [002 §Destroy](002-Frames.md#destroy). A subsystem holding frame-scoped state without a teardown hook leaks definitions and cached state on every `destroy-frame!`. A subsystem MUST name its teardown hook and what it releases.

**One teardown path, not two ([EP-0024 §Teardown](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).** Because the frame value is the single owner of every per-frame subsystem (see [§One frame value owns every per-frame subsystem](#one-frame-value-owns-every-per-frame-subsystem--one-registry-one-teardown-path)), destroying a frame removes **one** unified value from **one** live registry and runs each subsystem's teardown hook exactly once. Teardown stays best-effort where individual hooks are host-transient — a host handle that fails to release does not abort the others — but the **ownership path is one path**: there is no separate object registry whose cleanup can succeed or fail independently of a backing frame-record registry. A subsystem's durable subtree, its host-transient side tables, and its teardown hook are released against the same frame value, so the strict destroy order of [002 §Destroy](002-Frames.md#destroy) is the only teardown sequence a grader (or a tool reconstructing teardown) has to reason about.

## Two derived rules

The five clauses imply two cross-cutting rules every conformant subsystem MUST satisfy. They are not a sixth clause — they constrain *how* clauses 4 and 5 are answered — but they are normative in their own right, because each names a hazard a subsystem can pass the bare clause checklist and still get wrong.

### Derived rule 1 — the restore question is mandatory; allocators never rewind

Clause 5 (teardown) is incomplete unless it also answers the **epoch-restore** question: *what does an epoch restore do to every value in this sub-tree?* Restore is not destroy — it overwrites the durable slice with its value as of the restored epoch while the live transient world (host handles, in-flight replies already beyond cancellation) keeps running on the post-restore timeline. A subsystem MUST state, per durable slot, what restore does to it.

The load-bearing case is **monotonic allocators**: any counter that mints a fresh identity (a spawn counter, a generation, a work-id sequence, a nav-token) **MUST NOT rewind on restore**. Rewinding an allocator lets a post-restore allocation collide with a pre-restore identity still carried by an uncancellable in-flight reply or an already-emitted side effect — a stale generation mistaken for a live one. The discipline is the **anti-recycling rule** (the routing ruling, generalized): the canonical resolution is to hold such allocators **outside the revertible frame-state entirely** (the host-side transient cache, as routing does with its nav-token / pending-nav counters — see the `:rf.runtime/routing` grading row clause 1), so an epoch restore physically cannot rewind them.

A subsystem whose durable slice contains a recycled or rewindable identity is a contract violation even if it names a teardown hook (clause 5) and a projection policy (clause 4): the restore question is the part of clause 5 a bare destroy-only reading misses.

### Derived rule 2 — one authoritative home per fact; mirrors are recomputable projections

Where a subsystem holds a denormalized copy of data whose authoritative home is elsewhere — an index, a denormalized owner set, a derived lookup, a cached cross-reference — the copy is a **recomputable projection of its authoritative home, never a second source of truth**. Such a mirror MUST be declared recomputable-from-source, and on both **restore and SSR hydration** it is rebuilt from the authoritative source rather than trusted from (or carried on) the serialized snapshot. The durable wire payload (clause 4) therefore need not carry the mirror at all, and a stale or partial mirror can never outlive the facts it describes.

This rule constrains clauses 4 and 5 together: clause 4's projection policy declares the mirror *out* of the durable payload (it is re-derived, not shipped), and clause 5's restore answer (derived rule 1) recomputes it rather than installing it. It is the durable-state half of the one-name-per-fact discipline — a mirror MUST NOT become a co-equal home that can drift from its source. (The same discipline appears as a *naming* rule for cross-layer spellings; here it is a *state-ownership* rule for denormalized copies.)

## Why the contract matters — the routing write-authority worked example

Routing is the worked example of why naming clause 2 (write authority) pays off. Routing's event handlers (`:rf.route/navigate`, `:rf.route/transitioned`, …) read and return the reserved route slice — they are legitimate framework writers. But before the authority key was generalized, the runtime minted event-handler authority from `:rf/machine?` **only**, so every navigation tripped the `:rf.warning/app-handler-runtime-effect` ownership diagnostic in dev — polluting the Xray Issues lens and training users that the warning is noise (now fixed).

The fix introduced the general `:rf/framework-authority? true` registration-meta key the routing façade stamps on its `reg-event` registrations, which the router folds into the authority check alongside `:rf/machine?`.

**The contract is the policy layer; the registration-meta stamp is the mechanism.** This doc names *who gets framework-write authority* (clause 2: whoever registers a runtime subsystem, via a named path). The `:rf/framework-authority?` stamp is the concrete CLJS-reference mechanism that implements it — owned by [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority), and **decoupled** from this doc. This doc does not redefine or re-touch that mechanism; it names the shape the mechanism serves.

## The plugin / extension seam

A 3rd-party library that needs durable, revertible, framework-owned frame-state becomes a **new graded instance** of this contract. It registers a `:rf.runtime/<lib>` subsystem and answers all five clauses:

1. Reserve the `:rf.runtime/<lib>` child (the `:rf.runtime/*` namespace is the framework-owned durable-state seam; a feature-modular library claims a child under it, per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention)).
2. Mint write authority — stamp `:rf/framework-authority? true` on its event handlers, or write through the privileged frame-state helpers.
3. Ship a public sub/accessor for app reads.
4. Declare its SSR projection (which slots ship) and off-box/production elision.
5. Publish a teardown hook off `destroy-frame!`.

A library that satisfies all five is a runtime subsystem on equal footing with the shipped four; one that satisfies fewer is ad-hoc runtime state, outside the contract tools and SSR projection assume. This is the principled home the framework-authority question ("who may write runtime-db?") now points to — it is **enumerable**: the runtime subsystems are exactly the children that answer the five clauses.

## Grading table — the shipped subsystems

One row per existing runtime-db child, graded against all five clauses. ✅ = the clause is satisfied with a named mechanism; **(implicit)** flags a clause a subsystem leaves implicit rather than spelling out at its own surface. The four v1 subsystems (machines / routing / elision / SSR) are graded first; EP-0003's shipped resource trio (resources / work-ledger / mutations) follows — its rows are lifted from [016 §Runtime-subsystem graduation](016-Resources.md#runtime-subsystem-graduation), which is the canonical source for the resource-trio grading content.

### `:rf.runtime/machines` — state-machine runtime ([Spec 005](005-StateMachines.md))

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/machines` with the closed slot set `:snapshots` / `:system-ids` / `:spawned` / `:spawn-counter` (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); slots [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live)). Each slot allocated lazily. |
| **2 Write authority** | ✅ Event-handler path — machine handlers carry the framework-owned `:rf/machine? true` stamp (minted by the machine registrar), which the runtime folds into the authority check, so a machine implies framework-write authority *without* a separate `:rf/framework-authority?` key (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)). Snapshot commits + the spawn-fx write through this path. |
| **3 Read API** | ✅ `[:rf/machine <id>]` (plus `[:rf/machine-has-tag? …]`, `rf/machine-by-system-id`) — "User code MUST NOT write under `[:rf.runtime/machines …]`; it reads machine state through the `[:rf/machine <id>]` subscription, never raw runtime-db paths" ([005 §where snapshots live](005-StateMachines.md#where-snapshots-live)). |
| **4 Projection / elision** | ✅ Shipped **whole** on the hydration payload — snapshots + spawn registry are durable serializable facts the client re-materialises actors from (the reference `project-runtime-db` ships `:rf.runtime/machines` verbatim; [011](011-SSR.md)). The `:rf/machine-type` snapshot slot is the load-bearing durable fact (liveness rides the revertible snapshot). Per-slot `:sensitive?` on machine `:data` composes through `rf/elide-wire-value`. |
| **5 Teardown** | ✅ Named hook `:machines/teardown-on-frame-destroy!` (step 2 of [002 §Destroy](002-Frames.md#destroy)) — walks active machines in reverse-creation order, runs each `:exit` cascade, applies the unified teardown projection (snapshot + `:rf/system-ids` + spawn-slot prune), unregisters per-actor handlers, emits `:rf.machine.lifecycle/destroyed`. **Durable kept:** the snapshots (they ride restore/SSR). **Transient dropped:** the per-actor handler registrations + the `:after` timer table (`:machines/on-frame-destroyed!`). |

### `:rf.runtime/routing` — routing runtime ([Spec 012](012-Routing.md))

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/routing` with `:current` (the route slice, schema `:rf/route-slice`) + `:pending-navigation` (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [012 §The route slice](012-Routing.md#the-rfroute-slice)). **(implicit, by deliberate exclusion):** the nav-token / pending-nav counters and saved scroll positions are **host-side transient caches, NOT runtime-db** — held outside the frame value so an epoch restore cannot rewind + recycle a token. The split is explicit in Conventions but the subtree's *boundary* (what is deliberately absent) is the subtle part a grader must check. |
| **2 Write authority** | ✅ Event-handler path — the routing façade stamps `:rf/framework-authority? true` on every `reg-event` it registers (`:rf.route/navigate`, `:rf.route/transitioned` / `:rf.route/handle-url-change`, `:rf/url-requested`, `:rf.route/continue` / `:rf.route/cancel`, `:rf.route.internal/settle-transition`) — every one reads and returns the reserved route slice (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)). **This is the clause whose gap was the now-fixed ownership-diagnostic bug** (see the worked example above). |
| **3 Read API** | ✅ The `:rf.route/*` sub family — `[:rf.route/id]`, `[:rf.route/params]`, `[:rf.route/query]`, `[:rf.route/fragment]`, `[:rf.route/transition]`, plus `(rf/sub :rf/route)` ([012 §Subscriptions](012-Routing.md)). App code reads these, never `[:rf.runtime/routing …]`. |
| **4 Projection / elision** | ✅ Allowlist-by-key — only the durable `:current` slice ships (`durable-routing-keys [:current]` in the reference `project-runtime-db`); `:pending-navigation` is a local-subscribable runtime-db key that stays **client-local / SSR-stripped** (fail-closed) ([Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue), [011](011-SSR.md)). The host-side counter/scroll caches are not runtime-db at all, so the allowlist would strip them regardless. |
| **5 Teardown** | ✅ Named hook `:routing/on-frame-destroyed!` ([012 §Scroll restoration](012-Routing.md#scroll-restoration)) — clears the **host-side** routing caches (the saved scroll-position LRU; the nav-token / pending-nav counters are host-side transient state too). **Durable kept:** the runtime-db route state (`:current` / `:pending-navigation`) is durable and rides frame-state, so it is released atomically when the frame value is dropped at destroy — it needs no separate cleanup pass. **Transient dropped:** the host-side scroll-position cache + nav-token counters the hook clears (host-derived, ephemeral, meaningless after a restore — transient per [002 §Durable vs transient](002-Frames.md#durable-vs-transient)). |

### `:rf.runtime/elision` — wire-elision declaration registry ([Spec 009](009-Instrumentation.md))

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/elision` with `:declarations` (size-elision nominations) + `:sensitive-declarations` (privacy nominations), each `{<path-vector> → {…:source :schema}}` (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces)). |
| **2 Write authority** | ✅ Privileged-helper path — elision writes its per-frame declaration registry through privileged frame-state helpers (`swap-runtime-db!`), **not** through event handlers returning a `:rf.db/runtime` effect, so it never reaches the event-handler diagnostic and mints no event-handler authority (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)). **(implicit):** the declarations are populated *additively at boot* by the schema walker from the app-db schema (`rf/app-schema`) — a boot-time framework write, not a per-event one; the "who writes" is the schema-registration machinery, which a grader must trace through the schema walker rather than a handler list. |
| **3 Read API** | ✅ **No app-facing read sub — by design.** The declaration records are framework bookkeeping consumed by the `rf/elide-wire-value` walker at every wire-boundary emit (and the schema-validation redaction path), not read by app subscriptions. The subsystem states its read surface as framework-internal: app code declares `:large?` / `:sensitive?` on its schemas (the *input*) and observes the *effect* (redacted/elided trace values), never the registry itself. |
| **4 Projection / elision** | ✅ The registry **is** the projection/elision policy engine for *other* subsystems — but as a subsystem itself it ships on the hydration payload (the reference `project-runtime-db` ships `:rf.runtime/elision` whenever present) so the client's walker enforces the same declarations server-derived. The registry's own values (paths + hints) are framework metadata, not user wire data, so they carry no `:sensitive?` payload of their own. |
| **5 Teardown** | ✅ **(implicit / lightweight):** elision's frame-scoped cleanup is the per-feature warn-cache reset bundled into step 5 of [002 §Destroy](002-Frames.md#destroy) ("per-feature warn-cache resets (privacy-suppression, elision) which are observability-only and ordering-insensitive"). The declaration registry itself is durable runtime-db (re-derivable from schemas at boot) and rides the frame value; only the observability-only warn caches are explicitly reset. The grader should note elision has *no* heavyweight teardown — its durable facts are re-derivable, its transient state is just dedupe caches. |

### `:rf.runtime/ssr` — SSR / hydration metadata ([Spec 011](011-SSR.md))

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/ssr` with `:hydration` → `{:server-hash <hex-or-absent> :version <int-or-absent>}` (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [011 §The :rf/hydrate event](011-SSR.md#the-rfhydrate-event)). Allocated lazily — absent on frames that never hydrated. |
| **2 Write authority** | ✅ **Both paths.** Event-handler path — the SSR façade stamps `:rf/framework-authority? true` on `:rf/hydrate`, which installs the hydration metadata (`[:rf.runtime/ssr :hydration :server-hash]`) into the runtime-db partition. SSR's **non-event** writes (full-frame install / restore on the server render path) go through the privileged frame-state helpers (`replace-frame-state!`), bypassing the diagnostic (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)). The reference handler exports the write-fn; the `reg-event` lives in the `re-frame.ssr` façade. |
| **3 Read API** | ✅ `verify-hydration!` reads `:server-hash` after the first client render; the `:rf.ssr/check-version` fx reads `:version` for the compatibility check ([011 §The :rf/hydrate event](011-SSR.md#the-rfhydrate-event)). **(implicit):** there is no app-facing `:rf.ssr/*` *subscription* — the metadata is consumed by framework verification fns, not app subs. Apps that override the reference `:rf/hydrate` handler MUST preserve the `:server-hash` write (or pass `:server-hash` to `verify-hydration!`); the read contract is stated as a handler-preservation rule rather than a sub. |
| **4 Projection / elision** | ✅ Ships on the hydration payload (the reference `project-runtime-db` ships `:rf.runtime/ssr` whenever present) — the metadata is small framework state (a render hash + version) the client needs to verify the handoff. It is the durable arm of the SSR surface; the SSR request/response accumulators, head snapshots, streaming continuation registries, and pending-error buffers are **transient** and explicitly MUST NOT ride the wire (per [002 §Durable vs transient](002-Frames.md#durable-vs-transient), [011 §payload policy](011-SSR.md)). |
| **5 Teardown** | ✅ Named hook `:ssr/on-frame-destroyed` (step 5 of [002 §Destroy](002-Frames.md#destroy)) — clears the per-request side-channels and chains `:ssr.head/on-frame-destroyed`. **Durable kept:** the `:hydration` metadata is durable runtime-db that rides the frame value (released atomically when the frame is dropped). **Transient dropped:** the request/response accumulators, head snapshots, streaming continuation registries, pending-error buffers (the side-channels the hook clears). |

### `:rf.runtime/resources` — resource cache ([Spec 016](016-Resources.md))

The runtime-subsystem contract's **first graduating instance outside the v1 four** — shipped with EP-0003. Lifted from [016 §Runtime-subsystem graduation](016-Resources.md#runtime-subsystem-graduation) (the canonical home); see that section for the full prose.

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/resources` with the closed slot set `:entries` / `:tag-index` / `:owner-index` (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [016 §Cache home and write authority](016-Resources.md#cache-home-and-write-authority)). Allocated lazily — absent until the first resource write — and per-frame isolated. |
| **2 Write authority** | ✅ Event-handler path — every resource `reg-event` (`:rf.resource/ensure`, `:rf.resource/refetch`, `:rf.resource/invalidate-tags`, `:rf.resource/release-owner`, `:rf.resource/clear-scope`, `:rf.resource/remove`, plus the internal `:rf.resource.internal/*` replies) stamps `:rf/framework-authority? true` (the framework-authority mechanism), so a returned `:rf.db/runtime` effect is in-bounds (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)). Stale/GC and host-handle bookkeeping done outside the event-handler path writes through the privileged frame-state helpers (`swap-runtime-db!` / `replace-frame-state!`). See [016 §Write authority](016-Resources.md#write-authority). |
| **3 Read API** | ✅ The `:rf.resource/*` sub family (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, `:rf.resource/previous-data`) plus tool accessors (`resource-meta`, `resource-state`, `resources`, the `list-resource-instances` / `get-resource-state` family). App code never reads raw `[:rf.runtime/resources …]` paths. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only the durable resource projection rides the `:rf/hydration-payload` `:rf/runtime-db` slice via the explicit projection hook ([016 §SSR and hydration](016-Resources.md#ssr-and-hydration)); `:tag-index` / `:owner-index` are **recomputable-from-`:entries`** (per [derived rule 2](#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections)) and need not ride the wire. Params, scopes, and data carry `:sensitive?` / `:large?` classification through the shared `rf/elide-wire-value` walker; Xray sees redacted summaries, not raw values. |
| **5 Teardown** | ✅ Named hook `:resources/on-frame-destroyed!` ([016 §Stale and GC scheduling](016-Resources.md#stale-and-gc-scheduling), [016 §Restore and replay](016-Resources.md#restore-and-replay)) — frame destroy cancels all resource timers and clears the per-frame host handles (side tables keyed by frame id and work id). **Durable kept:** `:entries` (cache facts ride restore/SSR). **Transient dropped:** AbortControllers, stale/GC timers, transport promises (never serialized); `:tag-index` / `:owner-index` are recomputed from `:entries` on install (derived rule 2). |

### `:rf.runtime/work-ledger` — frame work ledger ([Spec 016](016-Resources.md))

The neutral, **multi-writer** in-flight-work subsystem — resources (work-kind `:resource`) and mutations (work-kind `:mutation`) are its two landed writers, with later slices extending it to timers, streams, route loaders, spawned actors, and machine async work. Lifted from [016 §Runtime-subsystem graduation](016-Resources.md#runtime-subsystem-graduation).

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/work-ledger` with serializable work records keyed by `:work/id` (`[:rf.work/resource resource-key generation]` for the resource writer; per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [016 §Frame work ledger](016-Resources.md#frame-work-ledger)). Allocated lazily; per-frame isolated. Named **neutrally** by design. |
| **2 Write authority** | ✅ *for the two in-artefact writers* — the ledger is written through the resource and mutation event handlers (both stamp `:rf/framework-authority? true`). ⚠️ **OPEN multi-writer question** ([016 §Work-ledger multi-writer authority](016-Resources.md#work-ledger-multi-writer-authority--still-blocking-for-the-multi-writer-slice-post-v1-tracked-for-v1)): the ledger is a deliberate multi-writer subsystem, and who mints authority for each *additional* future writer **outside the Resources artefact** (timers / streams / route loaders / spawned actors / machine async work) is unresolved, to be settled **per writer** when the first such writer lands — machines already imply authority via `:rf/machine? true`; non-machine writers will each stamp `:rf/framework-authority? true` or write through the privileged helpers. This is a forward-flag, not a v1 blocker (clause 2 is complete for both shipped writers). |
| **3 Read API** | ✅ **No app-facing read sub — by design.** Read by framework code and tools only — Xray's live work-ledger table per frame, SSR's blocking-drain wait point, and the resource runtime's join/dedupe logic. App code observes work indirectly through `:rf.resource/*` subs (`:rf.resource/fetching?` etc.), never the ledger directly. |
| **4 Projection / elision** | ✅ Allowlist-shaped — only **non-terminal rows' summaries** ride the **epoch-restore** wire (for the post-restore dangle reconciler); the **SSR hydration payload carries no work-ledger rows at all** (it ships only the `:rf.runtime/resources` cache projection — in-flight work belongs to the server timeline that owns its host handles, so the client has nothing to reconcile). Terminal rows are pruned to a bounded local Xray tail and are not durable wire payload ([016 §Ledger row retention and identity](016-Resources.md#ledger-row-retention-and-identity)). Causes, owners, and deadlines carry the same privacy/size elision as resource metadata through `rf/elide-wire-value`. |
| **5 Teardown** | ✅ Host handles (AbortControllers, timeout/poll handles, promises) live in side tables keyed by `[frame-id work-id]`, cleared on frame destroy alongside the resource hook. **Durable kept:** the bounded set of non-terminal serializable records. **Transient dropped:** host handles; restored non-terminal rows are immediately reconciled to **dangling** — their `:work/id` can never re-match a live entry because the generation allocator is monotonic and host-side (per [derived rule 1](#derived-rule-1--the-restore-question-is-mandatory-allocators-never-rewind); [016 §Restore and replay](016-Resources.md#restore-and-replay)). |

### `:rf.runtime/mutations` — mutation-instance runtime ([Spec 016](016-Resources.md))

The causal-write counterpart of `:rf.runtime/resources`, shipped with the first public-beta gate. It owns its own subtree (instance rows keyed by mutation **instance** id) but its in-flight attempt **rides the neutral `:rf.runtime/work-ledger`** (work-kind `:mutation`) rather than minting a fourth subtree for it — so clause 1 is the instance map and clause 2 reuses the work-ledger transport. Present only when the app registers a mutation.

| Clause | Grade |
|---|---|
| **1 Subtree** | ✅ `:rf.runtime/mutations` with serializable mutation **instance** rows keyed by instance id (`:mutation/id` / `:instance/id` / `:status` / `:result` / `:error` / `:scope` / `:params` / `:generation` / `:current-work` / `:started-at` / `:settled-at` / `:affected-keys` / `:patch-summary`; schema `MutationInstance` in [Spec-Schemas](Spec-Schemas.md)). Keyed by instance id (NOT mutation id) so concurrent submissions never clobber each other. Allocated lazily — absent in an app that registers no mutations (per [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue); [016 §Mutations](016-Resources.md#mutations-first-public-beta-gate)). |
| **2 Write authority** | ✅ Event-handler path — `:rf.mutation/execute` / `:rf.mutation/clear` (and the internal reply handlers) stamp `:rf/framework-authority? true`; the in-flight write lowers through the **same managed-HTTP transport as resources**, joining a `:rf.runtime/work-ledger` record (work-kind `:mutation`) rather than a private side-table. Generation + work-id stale suppression is the correctness boundary as for resources (per [016 §Mutations](016-Resources.md#mutations-first-public-beta-gate)). |
| **3 Read API** | ✅ The passive `:rf.mutation/*` sub family — `:rf.mutation/state`, `:rf.mutation/status`, `:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error` — keyed by instance id, projecting the instance view-model. App code reads these, never raw `[:rf.runtime/mutations …]` paths. |
| **4 Projection / elision** | ✅ The instance rows store **facts, not derived booleans** (`:pending?` / `:success?` / `:settled?` are computed in the subs layer), so the durable row is a small projectable fact; `:affected-keys` / `:patch-summary` carry the optimistic-rollback trace shape ([016 §Optimistic mutations](016-Resources.md#optimistic-mutations), [EP-0019](../docs/EP/EP-0019-optimistic-mutation-rollback.md)). Params, result, and error carry `:sensitive?` / `:large?` classification through `rf/elide-wire-value`; the `:error` envelope is the closed `:rf.http/*` failure shape. |
| **5 Teardown** | ✅ Host handles live in the shared `[frame-id work-id]` side tables (cleared on frame destroy with the resource hook); `:rf.mutation/clear` is the causal instance reset (clears the runtime instance and best-effort aborts in-flight work). **Durable kept:** the instance rows (facts). **Transient dropped:** host handles; the in-flight work record reconciles to dangling on restore exactly as the resource writer's does (the generation allocator is monotonic and host-side — derived rule 1). |

### Summary — clauses left implicit

Every graded subsystem satisfies all five clauses; the table flags where a clause is satisfied **implicitly** rather than spelled out at the subsystem's own surface — the seams a new subsystem author should make *explicit* when grading their own instance:

- **`:rf.runtime/routing`** — clause 1 (the subtree boundary: which routing facts are deliberately *excluded* and held host-side). Clause 5 *is* spelled out — the named `:routing/on-frame-destroyed!` hook clears the host-side scroll/nav-token caches while the durable route slice rides the frame value — but the split (durable rides frame-value-drop; only the host-side caches need the hook) is the subtle part a grader should note.
- **`:rf.runtime/elision`** — clause 2 (writes are boot-time schema-walker derivation, not a handler list) and clause 5 (no heavyweight teardown; durable facts are re-derivable, transient state is dedupe caches only).
- **`:rf.runtime/ssr`** — clause 3 (read surface is framework verification fns + a handler-preservation rule, not an app sub).
- **`:rf.runtime/work-ledger`** — clause 2 (authority is satisfied *for the resource writer*; the multi-writer authority question is an explicit open forward-flag, not a v1 gap) and clause 3 (no app-facing read sub by design — observed indirectly through `:rf.resource/*`).
- **`:rf.runtime/machines`**, **`:rf.runtime/resources`**, **`:rf.runtime/mutations`** — all five clauses explicit; the cleanest worked instances of the contract.

These are not gaps in the framework — every clause is *satisfied* — but they are the clauses a future subsystem (a 3rd-party `:rf.runtime/<lib>`) should answer **explicitly** at its own surface rather than leaving the grader to trace through. EP-0003's resource trio (resources / work-ledger / mutations) was the contract's first graduating set outside the v1 four, and graded clean against this checklist.

## How a new runtime subsystem inherits the contract

EP-0003's `:rf.runtime/resources`, `:rf.runtime/work-ledger`, and `:rf.runtime/mutations` graduated against this checklist instead of prose — each carries a grading row in the table above, answering all five clauses with named mechanisms (and `:rf.runtime/work-ledger` carrying the one explicit open forward-flag, its multi-writer authority question). They were the first instances to do so outside the v1 four; any future subsystem follows the same path. Concretely, a new subsystem:

1. Reserves its `:rf.runtime/<name>` child in [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) (the reserved-key table MUST be amended) with its closed slot set catalogued in [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue).
2. Mints write authority via `:rf/framework-authority? true` on its event handlers, or via the privileged frame-state helpers, naming the path (per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority)).
3. Ships a public sub/accessor for app reads (or states explicitly that it has none).
4. Declares its SSR projection (which slots ship, allowlist-shaped) and off-box/production elision.
5. Publishes a teardown hook off `destroy-frame!` (per [002 §Destroy](002-Frames.md#destroy)), naming durable-kept vs transient-dropped.

A subtree that answers fewer than five is ad-hoc runtime state, not a runtime subsystem; tools, SSR projection, and the AI-Audit treat it as out-of-contract.

## Runtime realms — the container

> **EP-0013, accepted 2026-06-11 (Mike; D1 = adopt, stands alone).** This section graduates the **realm-container model** from [EP-0013 §D1](../docs/EP/EP-0013-app-values-and-runtime-realms.md). D1 shipped the **shape and the ownership boundary** as an internal record (no public source break), so the rest of the corpus could name the container without inventing one per feature.

> **Superseded at the public surface by [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) (final, graduated 2026-06-16).** The realm machinery this section describes is **retained as the internal installation and ownership substrate** — it still owns the registrar, the installed adapter, the capability map, the frame registry, and the host-transient subsystem state, and realm-routed live resolution still ships (see [§Runtime realms](#runtime-realms--the-container) and the realm-routed-resolution blockquote below). But the **public teaching model is no longer the `(realm, frame)` app/realm surface**. EP-0023 partially supersedes EP-0013's public app/realm surface (EP-0013's status is now `superseded-by EP-0023`) and presents the architecture as **`image → frame → event stream`**: target a frame, and that frame determines the image generation used for resolution; public `dispatch` / `subscribe` / view scope do not require a separate `(realm, frame)` pair. The EP-0013 D2/D3 constructor names below (`rf/module` / `rf/app` / `rf/install!` / `rf/reinstall!` / `rf/realm` / `rf/dispose-realm!`) are described here as the **internal installation substrate** that realms are constructed and seated through, **not** the current front-of-house public model — a reader building a spec-driven implementation or codegen targets the image/frame surface owned by [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) ([API §Registration](API.md#registration), [002 §Per-instance frames](002-Frames.md#per-instance-frames--make-frame-the-ep-0023-object-constructor), [001 §Cross-namespace registration](001-Registration.md#re-registration-of-a-different-function--collision-warning)). A single-realm app still never spells a realm — the default realm carries the program implicitly (the byte-identical single-realm path).

The five-clause contract above organises the **durable** runtime-db partition: the `:rf.runtime/*` children of `:rf.db/runtime`, which ride frame-state serialization, restore, and SSR hydration. But a running app also holds a second framework-owned layer that is **not** durable frame-state — the registrar it dispatches against, the reactive adapter it renders through, the capability map its features depend on, and the **host-transient** side tables (HTTP abort handles, timers, nav counters, scroll caches, flow last-input caches, machine timer handles). Today these are mostly process-global. The **runtime realm** is the value that owns them.

**A runtime realm is the operational environment an app runs in: the value that owns the registrar, the installed adapter (selection), the capability map, the frame registry, and the host-transient subsystem state — with a lifecycle.** The durable runtime-db subsystems live *inside the frames* the realm owns; the realm owns the non-durable operational layer beside them.

This completes the ownership picture for framework-owned state:

| Layer | Owner | Lifecycle | This doc |
|---|---|---|---|
| **Durable frame-state** (`:rf.runtime/*` children) | the **frame** (per-frame, isolated) | rides restore / SSR / time-travel | the five-clause contract + grading table above |
| **Host-transient subsystem state** (abort handles, timers, caches) | the **realm** (often keyed per-frame) | torn down on frame/realm destroy; never serialized | §Host-transient subsystem state below |
| **Operational environment** (registrar, adapter, capabilities) | the **realm** | created at boot; disposed with the realm | §Runtime realms (this section) |

### The default realm preserves today's ergonomics

The process creates one **default realm** at boot. Every existing one-argument, ambient-looking surface — `reg-*`, `init!`/adapter install, the registrar query API, late-bind publish/lookup — resolves through it when no explicit realm is supplied. A single-realm app **never spells a realm**, exactly as a single-frame app never spells a frame outside its root: the plural model exists, and the zero-ceremony path stays zero-ceremony (the EP-0002 refinement pattern).

This is **registrar/operational compatibility, not a frame-target fallback.** The default realm answers "which registrar / adapter / capability map does this operation use?" It does **not** answer "which frame does this dispatch target?" — `dispatch`, `subscribe`, and other frame-scoped operations still require an explicit frame, a carried frame, or an established frame scope, and still fail with `:rf.error/no-frame-context` per [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md) / [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant) when no frame is known. **Absence of a realm means the default realm — an explicit, runtime-created realm — never a synthesised one.**

D1 stands alone. It can hold today's single mutable registrar unchanged behind the default realm; the app-value descriptor model that turns registration into an immutable value is **D2**, sequenced behind D1, and module manifests are **D3**. This doc graduates only the container.

### What a realm owns

A runtime realm MUST own:

- a stable **realm id** (`:rf.realm/id`), unique within the process — the default realm's id is `:rf.realm/default`;
- the **registrar** it dispatches/subscribes/resolves against (the `(kind, id) → registration descriptor` table — owned by [001-Registration](001-Registration.md); under D1 the registrar belongs to a realm, and the default realm's registrar is the surface existing specs call "global");
- the installed **adapter selection** (§Adapter ownership);
- the **capability map** (§Capability maps);
- the **frame registry** for frames in the realm (frame ids are unique *within a realm*, per [002 §Frames reference realms](002-Frames.md#frames-reference-realms));
- the **host-transient subsystem state** (§Host-transient subsystem state);
- **lifecycle / disposal** state.

These resources share one lifecycle question — who owns them, and when are they disposed? — and the realm is the smallest useful answer. Putting them on each frame would duplicate behaviour across the common many-frames-one-app case; keeping them process-global blocks multi-tenant and multi-root operation. The realm sits between: many frames share one installed program; one process can host more than one program. The realm-record shape is [`:rf/realm`](Spec-Schemas.md#rfrealm-runtime-realm-ep-0013) in [Spec-Schemas](Spec-Schemas.md).

> **Container-routed live resolution is shipped (internal substrate).** The container owning "the registrar it dispatches/subscribes/resolves against" is the **settled decision** and is now **live for every container** (EP-0013 staging step 4, recorded in [EP-0013 §Implementation errata](../docs/EP/EP-0013-app-values-and-runtime-realms.md#implementation-errata)). A **constructed** container (the internal `re-frame.realm/construct-realm`) owns the registrar that event / subscription / fx / cofx resolution routes against: the runtime resolves a frame's handlers from the **owning frame's container registrar**, with `:rf.realm/id` carried beside `:rf.frame/id` on the dispatch envelope and internal install binding the container registrar during seating. `reg-frame` (reached through the internal install path under the container binding) **stamps** the frame with the target container and keys live frames internally by `(realm, frame-id)` (a bare id for the default container); the earlier `:frame`-into-non-default-container refusal (`:rf.error/realm-frames-unsupported`) is **lifted**, so the internal install / reinstall seats `:frame` descriptors into the target container. (This routing is internal substrate; the public model addresses a frame by its id — see the EP-0023 supersession blockquote at the head of this section.)

The `:rf.realm/id` slot was reserved early on the [dispatch-envelope](002-Frames.md#routing-the-dispatch-envelope) and the reply-map / continuation record shapes, so the later stamp is non-breaking. The `:rf.realm/*` (and `:rf.capability/*`, §Capability maps) rows are reserved in the [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) table (the record-shape *slots* were reserved earlier; the namespace-table rows landed separately).

### Adapter ownership

The CLJS reference rule "one adapter per process" MAY remain the first implementation strategy. D1 changes the **pattern-level target**: adapter ownership belongs to a realm or render root, not to the process as such.

The boundary the ruling sharpens (issue 4 disposition):

- the **realm owns adapter selection** — *which* reactive substrate (Reagent / UIx / Helix / plain-atom) the realm renders through (the capability);
- **render roots own concrete mount/disposer instances** of that adapter.

The invariant:

```text
Reactive values created under a render subtree use that subtree's realm and
adapter consistently, and are disposed when the subtree, frame, or realm is
disposed.
```

Two roots that need **different adapters mean two realms**, unless a future bridge is explicitly designed — D1 does **not** make mixing two adapters inside one frame's reactive graph legal. The no-mixing-within-one-frame's-graph invariant is the conformance test. The adapter contract itself is unchanged (owned by [006-ReactiveSubstrate §The adapter contract](006-ReactiveSubstrate.md)); D1 changes only adapter *ownership* — process → realm/root.

**Named payoff:** two adapters in one process becomes legal via two realms — a legacy Reagent root beside a new UIx root, or stock Reagent beside reagent-slim, impossible today. Moving the adapter install/test-reset slot behind the realm is D1's **proving slice** (issue 5 disposition): it kills the process-singleton install (`:rf.error/adapter-already-installed` ceases to be a process-wide law) and is the fastest hermetic-test payoff. The HTTP in-flight migration sequences **behind the current EP-0011/0016 runtime waves settling** — those are actively editing that runtime, and its reply seams are freshly tested.

### Capability maps

A realm's **capability map** is the explicit dependency surface for runtime services — the conceptual owner of dependencies a feature needs. Capabilities are ordinary maps or records with documented functions and lifecycle: adapter functions, HTTP execution, schema validation, route integration, clocks (EP-0010's `:rf.capability/clock`), randomness (`:rf.capability/random`), SSR hooks, and test doubles.

```clojure
;; A hermetic test realm — the program and exactly the capabilities it needs,
;; with no process-global state to clear.
{:rf.realm/id  :test/cart
 :adapter      :plain-atom
 :capabilities {:rf.capability/http   fake-http
                :rf.capability/clock  fixed-clock
                :rf.capability/random seeded-random}}
```

A feature (a D3 module, when manifests graduate) declares the capabilities it `:requires`; installation fails before the program becomes visible if a required capability is absent (`:rf.error/missing-capability`, with `:realm` / `:capability` / `:recovery`). Capabilities make test doubles, SSR services, tenant-specific HTTP clients, schema validators, and routing hosts explicit and injectable, rather than process-global state discovered by namespace load order.

The `:rf.capability/*` capability-key namespace is reserved in the [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) table alongside `:rf.realm/*` (see §What a realm owns above).

### Late-bind compatibility

The late-bind hook table (`late-bind/publish!` / `late-bind/lookup`) remains a necessary implementation tool for **optional artifacts and cyclic namespace pressure** — it is not removed. Under D1 it becomes a **bridge into realm capabilities** rather than the conceptual dependency model. The default realm MAY populate its capability map from late-bound hooks so existing artifact load order keeps working; new explicit-realm code SHOULD prefer explicit capabilities.

The smell D1 addresses is not the existence of late-bind hooks. It is using a process-global hook table as a **service locator for app architecture** — a feature should be able to declare that it requires HTTP, routing, schemas, or a clock, and have that dependency be explicit and injectable. Late-bind stays for compatibility and optional loading; the semantic dependency surface moves to capabilities. The late-bind directory and its drift test are unchanged by this graduation.

### Host-transient subsystem state

**Host-transient subsystem state belongs to the realm.** It is the framework-owned operational state that is *not* durable frame-state: HTTP in-flight handles and abort controllers, routing nav counters and scroll caches, machine timers and spawn-order helpers, flow last-input caches, SSR request side channels, and adapter render roots and disposers. Subsystems MAY still key entries by frame, but the **owner of the table is the realm**, not an arbitrary namespace-level singleton.

This is the **non-durable sibling** of the five-clause contract. A durable runtime subsystem (the rows in the grading table above) lives under `:rf.runtime/*` and rides restore/SSR; a host-transient table is dropped on teardown and **MUST NOT ride the wire**. Most shipped subsystems have both halves — `:rf.runtime/routing` keeps its `:current` route slice durable while its nav-token / scroll caches are host-side transient (the routing grading row's clause 1 + clause 5 above already records exactly this split); `:rf.runtime/resources` keeps `:entries` durable while its AbortControllers and stale/GC timers are host-side (the resources grading row's clause 5 above).

A host-transient subsystem descriptor SHOULD declare:

```clojure
{:id             :rf.http/in-flight
 :storage-class  :host-transient
 :scope          :frame                 ;; entries keyed per-frame; :realm for realm-scoped tables
 :durability     :none                  ;; never serialized; never rides restore/SSR
 :teardown       teardown-http-for-frame!  ;; runs on frame destroy
 :test-reset     reset-http!               ;; the hermetic-test reset hook
 :snapshot       nil                       ;; excluded from snapshots explicitly
 :classification {:egress? true            ;; EP-0015 registration-owned classification
                  :sensitive? false}}
```

The `:storage-class :host-transient` value is the host-transient member of the four-class storage axis owned by [Derivations §the storage axis](Derivations.md) (`:ephemeral` / `:app-db` / `:runtime-db` / `:host-transient`). The descriptor shape is [`:rf/host-transient-descriptor`](Spec-Schemas.md#rfhost-transient-descriptor-ep-0013) in [Spec-Schemas](Spec-Schemas.md).

#### The host-transient grading column

D1 adds a **host-transient grading column** to this doc — the realm-ownership-and-lifecycle grade for each subsystem's host-transient half (issue 13 disposition: [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md) owns the durable five-clause contract; EP-0013 contributes this column). One row per shipped subsystem that holds host-transient state, graded on **scope** (frame vs realm), **teardown hook** (the named `destroy-frame!` hook that releases it), and **test-reset** (the hermetic-reset hook). ✅ = a named mechanism; **(none)** = the subsystem holds no host-transient state of that kind.

| Subsystem | Host-transient state | Scope | Teardown hook | Test-reset |
|---|---|---|---|---|
| `:rf.runtime/machines` | per-actor handler registrations; `:after` timer table | frame | ✅ `:machines/on-frame-destroyed!` (bundled in `:machines/teardown-on-frame-destroy!`, step 2 of [002 §Destroy](002-Frames.md#destroy)) | via the reset-runtime fixture |
| `:rf.runtime/routing` | nav-token / pending-nav counters; saved-scroll-position LRU | frame (held **outside** frame-state by design — anti-recycling, [derived rule 1](#derived-rule-1--the-restore-question-is-mandatory-allocators-never-rewind)) | ✅ `:routing/on-frame-destroyed!` | via the reset-runtime fixture |
| `:rf.runtime/elision` | privacy-suppression / elision dedupe (warn) caches | frame | ✅ per-feature warn-cache reset (step 5 of [002 §Destroy](002-Frames.md#destroy)) | reset with the warn caches |
| `:rf.runtime/ssr` | request/response accumulators; head snapshots; streaming continuation registries; pending-error buffers | frame | ✅ `:ssr/on-frame-destroyed` (chains `:ssr.head/on-frame-destroyed`, step 5 of [002 §Destroy](002-Frames.md#destroy)) | via the reset-runtime fixture |
| `:rf.runtime/resources` + `:rf.runtime/work-ledger` + `:rf.runtime/mutations` | AbortControllers; stale/GC + timeout/poll timers; transport promises (side tables keyed by `[frame-id work-id]`) | frame | ✅ `:resources/on-frame-destroyed!` (cancels timers, clears the `[frame-id work-id]` side tables) | via the reset-runtime fixture |
| **adapter** (the realm's reactive substrate) | the install slot; render roots + disposers | **realm** (selection) / **root** (instances) | realm/root disposal (§Adapter ownership) | adapter install/test-reset — D1's proving slice (issue 5) |

The two migrations the ruling sequences first — **adapter install/test-reset** and **HTTP in-flight** — are this column's first evidence (issue 5). The column grows as each host-transient table moves behind the realm; the conformance drift test that keeps the durable grading table honest **grows to cover this column too**, so a host-transient row cannot silently go missing. This is a `Runtime-Subsystems.md` amendment, not a second contract — EP-0006 is final and its spec evolves.

### What graduates vs what is deferred

| Graduates here (D1, internal) | Deferred (D2 / D3, public-last) |
|---|---|
| the realm as the owner of registrar / adapter / capabilities / frame registry / host-transient state | the **app value** — registrations as immutable descriptors (D2) |
| the **default realm** preserving today's ergonomics; absence = default realm | `install!` / `reinstall!` as the specified contract; hot reload as a diff (D2) |
| adapter ownership = realm selection + root instances | **module manifests** with ownership claims + collision detection (D3) |
| capability maps + late-bind-as-bridge; `:rf.capability/*` reserved | the `app` / `module` / `install!` / `reinstall!` constructors (D2 stages 6–7) and the **realm-targeted registrar queries** (map-shaped, issue 11 — D1 stage 8) — built, then **removed from the public facade by EP-0023** (retained-internal) |
| host-transient subsystem state + the grading column | the **container constructor** `realm` / `dispose-realm!` + multi-container/multi-adapter conformance (stage 9) — likewise retained-internal under EP-0023 |
| | **container-routed live-dispatch resolution** — frames key internally by `(realm, frame-id)` and event / sub / fx / cofx resolve through the owning frame's container registrar (step 4, now shipped) |

Stage 8 (D1) built the **container-targeted registrar query surface**: the registrar-query workhorses (`registrations` / `handler-meta` / `handler-ids`) grew a map-shaped form — `(registrations {:realm r :kind k})` etc. — that reads only the specified container's registrar (issue 11). Stage 9 then built the **container constructor** `realm` (+ `dispose-realm!`) and multi-container / multi-adapter-root conformance. Under [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) the whole D2 family — `module` / `app` / `install!` / `reinstall!` / `realm` / `dispose-realm!` plus the map-shaped queries — is **removed from the public `re-frame.core` facade** and retained only as the **internal installation substrate** containers are constructed and seated through (per the EP-0023 supersession blockquote at the head of [§Runtime realms](#runtime-realms--the-container): EP-0023 replaced the public app/realm teaching model with `image → frame → event stream`; these constructors are retained substrate, not the public model). Step 4 — **container-routed live-dispatch resolution** — has shipped: live frames key internally by `(realm, frame-id)`, and event / subscription / fx / cofx resolution routes through the owning frame's container registrar (see the blockquote above and [Spec-Schemas §`:rf/realm`](Spec-Schemas.md#rfrealm-runtime-realm-ep-0013)). The descriptor/manifest D3 surface stays documented as **deferred rationale and examples in [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md)**, not an accepted public surface.

## Cross-references

- [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys) — the reserved `:rf.runtime/*` key table (clause 1's canonical home; this doc references, does not duplicate).
- [Conventions §Runtime-db sub-container catalogue](Conventions.md#runtime-db-sub-container-catalogue) — the per-slot path catalogue for every shipped child.
- [016 §Runtime-subsystem graduation](016-Resources.md#runtime-subsystem-graduation) — the canonical source for the resource-trio grading rows (resources / work-ledger / mutations) mirrored into the grading table above.
- [002 §Write authority is by convention](002-Frames.md#write-authority-is-by-convention) and [§Minting framework-write authority](002-Frames.md#minting-framework-write-authority) — clause 2's canonical home (the `:rf/framework-authority?` mechanism).
- [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to) — clause 3's canonical home.
- [002 §Durable vs transient](002-Frames.md#durable-vs-transient) — clause 4/5's durable-fact-vs-transient-handle split.
- [002 §Destroy](002-Frames.md#destroy) — clause 5's teardown-order contract and per-subsystem hooks.
- [011 §The hydration payload](011-SSR.md) — clause 4's SSR projection (the allowlist-by-subsystem-child `project-runtime-db`).
- [Managed-Effects.md](Managed-Effects.md) — the effect-surface sibling of this doc (names the *effect* shape; this names the *state* shape).
- [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md) — the realm-container model (D1), the deferred app-value (D2) and module-manifest (D3) layers, and the thirteen ruled open-issue dispositions §Runtime realms graduates. **Status: `superseded-by EP-0023` at the public app/realm surface** — the realm machinery is retained as internal installation substrate (see the EP-0023 supersession note at the head of §Runtime realms).
- [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) — establishes `image → frame → event stream` as the **public** model for frame-loaded registration sets; partially supersedes EP-0013's public app/realm surface while retaining the realm machinery this doc describes as the internal installation substrate.
- [EP-0006](../docs/EP/EP-0006-runtime-subsystem-contract.md) — owns the durable five-clause contract; §The host-transient grading column is the EP-0013 realm-ownership amendment, not a second contract.
- [002 §Frames reference realms](002-Frames.md#frames-reference-realms) — a frame belongs to exactly one realm; frame ids are unique within a realm.
- [Spec-Schemas §`:rf/realm`](Spec-Schemas.md#rfrealm-runtime-realm-ep-0013) / [§`:rf/host-transient-descriptor`](Spec-Schemas.md#rfhost-transient-descriptor-ep-0013) — the realm-record and host-transient-descriptor shapes.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the framework-owned `:rf.*` reserved-namespace table (the `:rf.realm/*` + `:rf.capability/*` rows are reserved here; the `:rf.realm/id` record-shape slot was reserved earlier).
- [Ownership](Ownership.md) — the contract-surface → owning-Spec map; consult before naming a new runtime subsystem.
