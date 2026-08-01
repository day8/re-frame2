# Spec 013 — Flows

> Status: Drafting. **v1-required.** Builds on the registration grammar in [001-Registration](001-Registration.md), the drain in [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** flows are *registered, runtime-toggleable computed-state declarations that materialise their output into `app-db`*. They are the v2 incarnation of v1's `on-changes` interceptor — same compute-on-input-change semantics — but registered in the runtime (not on individual events) and toggleable via two reserved fx-ids.
>
> **Restraint.** Flows are a **convenience** for a small number of small use-cases. They are not a replacement for subscriptions, not a new dataflow paradigm, not a substitute for state machines, and not the default for derived state. The architecture's main load-bearing pieces are events, subs, machines, and effects; flows sit alongside them as a focused tool for a narrow set of problems. **When in doubt, use a subscription** — flows pay an `app-db` write per recomputation and add a small piece of registered runtime; that cost is only worthwhile when the [reasons in §When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) below apply.
>
> `:rf.flow/*` is the **internal-effect cousin** of the managed external effects — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties applied to derived computation (effect-as-data via the registration map, framework-owned scheduler, structured failure taxonomy under `:rf.flow/*`, trace-bus observability, `:sensitive?` / `:large?` composition on flow outputs, runtime-toggleable retry / abort / teardown via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`, in-flight flow registry, per-frame scoping).

## Abstract

A **flow** is a registered rule that says: "when these input paths change, run this pure function and write the result to that `app-db` path." Inputs read the pending frame-state — a bare path reads **app-db** (the common case), a `[:rf.db/runtime …]` path reads **runtime-db** (per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)); the output is always written to **app-db** (flows never write runtime-db). Flows are evaluated automatically on every event, **immediately after the handler's interceptor chain** (as the outermost `:after` interceptor — after the rest of the `:after` chain has reshaped the db effect, and before the `:db` effect installs), in topological order over their static input/output dependency graph. The flow walk transforms the handler's *pending* `:db` effect; see [§Drain integration](#drain-integration).

Flows differ from subscriptions in *where the value lives*. A sub's value lives in the per-frame sub-cache and is consumed by views. A flow's value lives in `app-db` at a known path, where it survives SSR / hydration / time-travel revert, is visible in the app-db inspector, can be read by downstream event handlers and other flows, and is covered by registered schemas. When the derived value is part of the application's *state* (as opposed to part of a view's render input), use a flow.

**Flows are frame-scoped.** A flow belongs to one frame: its registration, evaluation, output `app-db` path, and undo / time-travel boundaries are all frame-local. The same flow id can register against two different frames with two different `:derive` functions and two different `:output-path` slots; clearing the flow on one frame leaves the other untouched. See [§Frame-scoping](#frame-scoping) for the rationale and API.

## When (and when not) to use a flow

Flows are the right tool when **all** of the following apply:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers or schemas need to read the value as plain `app-db` data. (A machine callback never reads it ambiently — a machine that needs the value takes it by **payload threading** or a **declared recordable coeffect** on `:rf.cofx`, per [005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017); an in-callback ambient read is unrecorded and breaks replay.)
- The value should **survive** SSR hydration, time-travel revert, or app-db serialisation.
- The derivation is **stable enough to be worth registering** — it isn't a one-off computation inside a single handler.

Flows are the **wrong** tool when:

- The derived value is consumed only by views → use a **subscription** (lighter, sub-cache native, no `app-db` write).
- The derivation has discrete states or lifecycle (entry/exit, transitions) → use a **state machine** (per [005](005-StateMachines.md)).
- The value is only relevant inside one event handler → just compute it inline; no registration needed.
- "I want a reactive value somewhere" → almost always a sub.

The expected v1 deployment volume is **small** — a typical app has dozens of subscriptions and one to perhaps a handful of flows. If a codebase grows tens of flows, that is a smell that subscriptions or machines are being misused.

## Why flows

Three use cases the reference implementation has hit repeatedly:

- **Materialised computed state.** `:area` from `:width × :height`. `:total` from `:items`. `:can-submit?` from form validity, network state, and feature flags. The derived value is part of the app's state and downstream code reads it as plain `app-db`.
- **State that survives the wire.** SSR hydration carries `app-db`; computed values written by flows arrive on the client without re-computation. Sub-cache contents do not survive hydration.
- **Toggleable derivation.** A wizard step, a feature gate, an "advanced mode" — a derivation that should only run while a feature is engaged. v1's `on-changes` interceptor cannot do this because interceptors are wired into specific events at registration time. Flows are runtime-registered and runtime-clearable.

## The registration shape

Per the canonical [Spec 001 §Registration grammar](001-Registration.md#registration-grammar) 3-slot shape, `reg-flow` is `(reg-flow flow-id metadata derive-fn)`: the pure `:derive` fn — the flow's HANDLER — is the third VALUE slot, and the middle slot is the reflection-config metadata map.

```clojure
(rf/reg-flow :rectangle/area
  {:inputs      [[:width] [:height]]         ;; vector of frame-state paths (bare = app-db)
   :output-path [:area]                       ;; where the result is written
   :doc         "Rectangle area computed from :width and :height."}
  (fn [w h] (* w h)))                          ;; pure: (in-1, in-2, ...) → output
```

Slot 1 — `flow-id`: the unique flow identifier. Per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention), namespace by feature.

Slot 3 — `derive-fn`: the pure derivation. Receives input values positionally. Must be deterministic (same inputs → same output).

Slot 2 — `metadata`, the reflection-config map. Required keys:

| Key | Meaning |
|---|---|
| `:inputs` | Vector of frame-state paths read against the pending frame-state's two partitions (binary syntax — a bare path reads app-db; a `[:rf.db/runtime …]` path reads runtime-db; see [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)). The order matches positional args to the `derive-fn`. |
| `:output-path` | App-db path to write the output to. Writes are **app-db only** — a flow never writes runtime-db. |

Optional metadata keys (per the [001-Registration §Registration grammar](001-Registration.md#registration-grammar) standard):

| Key | Meaning |
|---|---|
| `:doc` | One-sentence what-and-why; surfaces in tooling. |
| `:schema` | Malli schema for the output value, validated on every recompute in dev (see [§Flow output validation](#flow-output-validation)). |
| `:frame` | The frame the flow registers against (the *override* — see [§Frame-scoping](#frame-scoping)). The mounting concern, so it rides the metadata map like every other 3-slot `reg-*` surface (per [Conventions §`reg-*` frame-binding convention](Conventions.md#reg--frame-binding-convention--opts-kwarg-not-main-arg)). |
| `:sensitive` / `:large` / `:large?` | EP-0025 output data-classification declarations (see [§Flow output data classification](#flow-output-data-classification-ep-0025) and [015-Data-Classification](015-Data-Classification.md)). |
| `:ns`, `:line`, `:file` | Source coordinates (auto-captured by the registration macro per [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). |

The `derive-fn` sits in the third (value) slot and the middle slot is a pure metadata map — documentation-DCE stays clean, and flows align with `reg-resource` / `reg-mutation` / `reg-route`. A `:derive` left INSIDE the metadata map is rejected loudly as a mislocated key (`:rf.error/invalid-flow-metadata`) — the third slot is `:derive`'s one home. Likewise a non-map metadata slot throws the same error before any reconstruction runs.

`:inputs` is a positional vector matching `on-changes`. The vector form is short for the common 2–4-input case and the destructure-by-position is straightforward. Each path is read against the pending frame-state, whose two partitions select on the path's leading element — see [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db). (A map-keyed alternative was considered — see [§Open questions](#open-questions).)

## Input partition — bare = app-db, `[:rf.db/runtime …]` = runtime-db

An `:inputs` path is read against the pending **frame-state**, which has two partitions (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract) — the app-db / runtime-db split). The syntax is **binary** — a bare path reads app-db; a `[:rf.db/runtime …]`-rooted path reads runtime-db. There is no third explicit-app form:

```clojure
[:cart :items]                                    ;; bare path → app-db (the common case)
[:rf.db/runtime :rf.runtime/routing :current :route-id] ;; :rf.db/runtime-rooted → runtime-db
```

- A **bare** path (any leading element other than `:rf.db/runtime`) reads the pending **app-db** partition, verbatim.
- A path whose **first element is `:rf.db/runtime`** reads the pending **runtime-db** partition — the partition key is stripped before the `get-in`. There is **no** `[:rf.db/app …]` explicit-app form: bare *is* app-db.

**Any** flow — user or framework — may read runtime-db this way, so a flow can derive a materialised value from route or machine state (`[:rf.db/runtime :rf.runtime/routing :current :route-id]`, `[:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :state]`). But the **write side is reserved**: a flow's `:output-path` and its `:derive` always write **app-db only** — a flow never writes runtime-db. Because flow outputs are always app-db paths, a `[:rf.db/runtime …]` input can never prefix-match another flow's output `:output-path`, so a qualified runtime input never creates a spurious topological dependency edge ([§Topological sort and cycle detection](#topological-sort-and-cycle-detection)). The dirty-check keys on **both** partitions: a runtime-only event (e.g. a pure route transition with no app-db change) still re-fires a flow that reads the changed runtime-db value, because the resolved runtime value is part of the cached input vector ([§Dirty-check semantics](#dirty-check-semantics)).

**There is no `[:rf.runtime/…]` *app-db* path — runtime state is not an app-db root.** Read runtime state through the `[:rf.db/runtime …]` partition-qualified input above.

`reg-flow` returns its `flow-id` — the primary id under which the flow registers — per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention). Under the 3-slot grammar the id is the first positional argument, exactly like the rest of the `reg-*` family.

`reg-flow` reads the frame the flow registers against from the `:frame` metadata key — the frame is the mounting concern, so it rides the metadata map like every other 3-slot `reg-*` surface (per [Conventions §`reg-*` frame-binding convention](Conventions.md#reg--frame-binding-convention--opts-kwarg-not-main-arg)). The frame is resolved by the EP-0002 carried invariant (per [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant)): the runtime reads the frame from the carried token — an explicit `:frame` metadata key (*override*) or a surrounding *scope* — a `with-frame`, or the closest enclosing frame boundary (a `frame-provider` (SCOPE) or a `frame-root` (ENSURE)) — and **never synthesises one from absence**. There is no `:rf/default` fall-through. A `reg-flow` outside any scope with no `:frame` metadata key is the registration-time case and fails with `:rf.error/no-frame-context`:

```clojure
(rf/reg-flow :rectangle/area
  {:inputs [[:width] [:height]] :output-path [:area]}   ;; raises :rf.error/no-frame-context outside any frame scope
  (fn [w h] (* w h)))
(rf/with-frame :scratch
  (rf/reg-flow :rectangle/area
    {:inputs [[:width] [:height]] :output-path [:area]}  ;; registers against :scratch via the surrounding scope
    (fn [w h] (* w h))))
(rf/reg-flow :rectangle/area
  {:inputs [[:width] [:height]] :output-path [:area]
   :frame  :scratch}                                     ;; explicit frame (override) — a metadata key
  (fn [w h] (* w h)))
```

`clear-flow` — whose primary arg IS the flow-id it removes — keeps its trailing `opts` map for the `:frame` override (there is no metadata slot on a teardown surface):

```clojure
(rf/clear-flow :rectangle/area)
(rf/clear-flow :rectangle/area {:frame :scratch})
```

## Frame-scoping

Flows are **frame-scoped**: registration, evaluation, and `clear-flow`'s `dissoc-in` all belong to one frame. The runtime registry shape is:

```
{frame-id {flow-id flow-map}}
```

Three consequences follow:

1. **Per-frame undo / time-travel boundaries.** Time-travel is a frame-local primitive (per [002 §Frames](002-Frames.md)). A flow's `:output-path` write is part of the owning frame's `app-db` history; reverting frame `:left` does not disturb flow outputs in frame `:right`.
2. **Same flow-id, multiple frames, independent definitions.** Registering `:compute` against `:left` with `(fn [x] (* 2 x))` and against `:right` with `(fn [x] (* 100 x))` produces two independent flows. Each frame's flow walk (`run-flows-on-db`) visits only its own slot of the registry.
3. **`clear-flow` is frame-local.** `(clear-flow :compute {:frame :left})` removes the flow's definition from frame `:left` and `dissoc-in`s its `:output-path` from `:left`'s `app-db` only. Frame `:right`'s `:compute` and its output keep working — `:right`'s per-frame entry is authoritative in place, untouched by the clear on `:left`.

**Single-store: the per-frame registry is the SOLE source of truth.** The per-frame runtime registry `{frame-id {flow-id flow-map}}` is the **single authoritative store** for flows — both for evaluation AND for introspection (matching the [schemas precedent](010-Schemas.md)). The `:flow` registrar kind (per [001-Registration §Registry model](001-Registration.md#registry-model--the-canonical-kind-keyword-set)) is **RESERVED but the registrar slot is empty** — `reg-flow` does NOT write it. Flows are **frame-divergent-per-id** (consequence 2 above — the same flow id can carry different `:derive` / `:inputs` / `:output-path` per frame), so a frame-blind `{flow-id metadata}` registrar slot could only ever hold ONE frame's view; there is no such slot.

**Introspecting flows.** Tools and source-coord readers introspect flows through the **frame-scoped** `re-frame.flows/flow-meta-at` (the flows analogue of `schemas/app-schema-meta-at`) and the whole-registry snapshot `re-frame.flows/flows-snapshot` (returning the `{frame-id {flow-id flow-map}}` shape), **not** `handler-meta :flow` / `handlers :flow` (always nil / empty) and **not** the private `@re-frame.flows/flows` atom. `flow-meta-at` returns each `(frame-id, flow-id)` entry's OWN definition — including the source-coords `reg-flow` stamps into the store — so the same flow id on two frames yields each frame's divergent flow-map, the very thing a frame-blind slot could never represent. The `flows-snapshot` / `flow-meta-at` accessors are the encapsulation boundary: the per-frame registry atom is private (the facade re-exports the read accessors and the reset fns), so consumers depending on them survive any future change to the atom's internal representation. (The raw `last-inputs-snapshot` re-export is retained `^:no-doc` as an internal / test / rollback seam only — it returns owner-local cached input values verbatim and is **not** an egress boundary. The raw dirty-check cache is never shipped off-box; tools and direct reads that cross a trust boundary ride the elided trace path — `:rf.flow/computed` `:input-values` / `:rf.flow/failed` `:inputs`, which elide each cached input under the owning frame's policy and fail closed for an unresolvable frame.)

**Hot-reload trace surface.** A same-frame re-registration still emits `:rf.registry/handler-replaced` (carrying `:frame` alongside `:kind :flow` / `:id` / `:different-fn?`) so devtools refresh their per-flow view — emitted **directly by `reg-flow`** under single-store rather than as a side effect of a registrar `:flow` write. Because a flow is authoritative at `(frame-id, flow-id)`, the dedup-by-shape decision is taken from **this frame's** authoritative slot — comparing the prior and new stored flow values of the same `(frame-id, flow-id)` — **not** the frame-blind process-global registrar dedup table ([009 §Hot-reload dedup](009-Instrumentation.md), which keys generic `:kind`/`:id` registrar kinds). Two live frames replacing the same flow id each emit their own frame-attributed event, an identical reload suppresses independently within each frame, and a destroy/recreate of a frame id never inherits its predecessor incarnation's emitted shape. A first-time per-frame registration emits `:rf.flow/registered` instead (per-frame gated).

Frame resolution matches the rest of the API and obeys the EP-0002 carried invariant (per [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant)): a `reg-flow` with no `:frame` metadata key resolves the frame only from a surrounding `with-frame` scope or an enclosing frame boundary — a `frame-provider` (SCOPE) or a `frame-root` (ENSURE) — there is **no** `:rf/default` fall-through, so a frameless registration outside any scope fails with `:rf.error/no-frame-context`. Tests and per-tenant runtimes pass an explicit `:frame` metadata key (the middle slot), or wrap the registration in `with-frame`.

## Frame-destroy teardown

Flows are frame-scoped, so `destroy-frame!` is the boundary at which every per-frame piece of flow state MUST clear. This is a **normative requirement** — the frame-isolation contract from [002 §Destroy](002-Frames.md#destroy) is only honoured if flow registrations and the dirty-check `last-inputs` cache release in lockstep with the frame. Without lockstep teardown a long-running JVM SSR host (per-request frame churn), a pair-tool time-travel cycle, or any `make-frame` ephemeral usage leaks flow definitions and cached input vectors indefinitely.

Two teardown invariants apply on `(destroy-frame! frame-id)`:

1. **Per-frame flow registry.** `(get @flows frame-id)` clears in full — every flow registered against `frame-id` is dropped from the SOLE authoritative store. Sibling frames' entries are unaffected (per [§Frame-scoping](#frame-scoping)): a surviving frame registering the same flow id keeps its OWN authoritative entry in place.
2. **Dirty-check cache.** Every `last-inputs[flow-id][frame-id]` row for the destroyed frame is dissoc'd. The whole `last-inputs[flow-id]` key is dropped when no other frame still holds an entry. Sibling frames' rows for the same flow id are preserved (a flow id registered against frames `:left` and `:right`, with frame `:left` destroyed, keeps `last-inputs[flow-id][:right]` intact).

**No registrar-slot prune (single-store).** There is no frame-blind `:flow` registrar slot to prune or realign on destroy. The per-frame `flows` atom is the sole store, so a surviving frame's entry is authoritative in place and there is nothing to realign. The frame-attribution + `:different-fn?` hot-reload signals are driven directly by `reg-flow` (per [§Frame-scoping](#frame-scoping)).

Teardown is idempotent against a frame the registry never recorded — a `destroy-frame!` on a freshly-constructed frame with no flows ever registered against it leaves the flow registry and `last-inputs` unchanged.

**A `reg-flow` against a non-live frame is rejected (`:rf.error/flow-frame-not-live`).** The teardown contract above is only honoured if the MUTATING registration path refuses to seat new flow state on a frame that is absent (never registered, or already torn down by `destroy-frame!`). Without the guard, `reg-flow` would unconditionally install a `flows[frame-id flow-id]` row and an elision declaration stamped with the dead frame-id — and a later `make-frame` reusing that id would inherit the resurrected flow, breaking the frame-isolation contract this section enforces. So `reg-flow` against a non-live frame throws the registration-time hard error `:rf.error/flow-frame-not-live` (carrying `:frame` / `:flow`, recovery `:fix-registration` — register against a live frame; per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) rather than creating dormant state for a typo'd or destroyed frame id. `clear-flow` keeps its permissive absent-frame no-op (teardown must stay idempotent); only this mutating path rejects.

The teardown contract is symmetric with the machines artefact's `:machines/teardown-on-frame-destroy!` hook and the schemas artefact's `:schemas/on-frame-destroyed!` hook — every per-feature artefact that holds frame-scoped state hangs its cleanup off the single normative `destroy-frame!` teardown boundary documented at [002 §Destroy](002-Frames.md#destroy). A new feature artefact MUST add its hook to the destroy cascade; a feature that holds frame-scoped state without one leaks on every `destroy-frame!`.

## Drain integration

Flow evaluation happens **immediately after the event handler's interceptor chain, transforming the pending `:db` effect — before the `:db` effect installs into `app-db` and before `:fx` walks**. The flow walk runs the registered flows over the chain's final `:db` effect *value* and rewrites that pending effect; it does NOT mutate the already-installed `app-db`. Per [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode), the runtime realises this as the **outermost `:after` interceptor** — the flow transform fires after the rest of the `:after` chain has finished reshaping the `:db` effect into the complete `app-db` form:

```
process-event! (with flows as the outermost :after):
  1. Resolve handler.
  2. Run interceptor chain — :before steps in order, then handler,
     then :after steps in REVERSE. The framework's flow-transform
     :after is the OUTERMOST :after, so it fires LAST — after every
     other :after (incl. a `(path :slice)` interceptor's :after that
     splices the handler's slice back into the FULL db):

       run-flows-on-db ctx                                   ← OUTERMOST :after
         ;; (the reference fn is `run-flows-on-db`, which takes the pending
         ;;  db VALUE; shown here against `ctx` for the surrounding flow)
         pending-db ← (:db (:effects ctx))   ;; the FULL, fully-reshaped
                                             ;; :db effect, or the current
                                             ;; app-db value when no :db
                                             ;; effect was produced
         Walk THIS FRAME'S registered flows in topologically-
         sorted order — i.e. (get @flows frame-id) only;
         sibling frames' flows are not visited.
         For each flow in this frame's slot:
           new-inputs ← read input paths from pending-db
           if new-inputs ≠ last-inputs[[frame-id flow-id]]:
             new-output ← (apply :derive new-inputs)   ;; MAY throw
             pending-db ← (assoc-in pending-db (:output-path flow) new-output)
             last-inputs[[frame-id flow-id]] ← new-inputs
         (:effects ctx) ← assoc :db pending-db   ;; flow-augmented :db effect

         ;; FAILURE — a flow's :derive threw (atomicity contract):
         ;; DISCARD the pending :db effect (drop it from (:effects ctx))
         ;; and stash the throw under :rf/flow-error. The event ABORTS:
         ;; the single deferred install at step 3 installs nothing, so
         ;; no :rf.event/db-changed fires and :fx is skipped. NO partial
         ;; commit — neither the handler's :db nor any prior flow's write
         ;; lands. (See §Failure semantics.)

  3. Install :db (the flow-augmented value) — ONLY when a :db effect is
     present. Sub-cache invalidates. `:rf.event/db-changed` fires HERE —
     after flows (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)).
     On any pre-install throw (handler / interceptor :after / flow), no
     :db effect is present (the flow-throw path discarded it; a handler /
     interceptor throw never produced one), so this step installs nothing
     and emits no :rf.event/db-changed.
  4. Walk :fx in source order — every `:fx` entry reads the
     flow-augmented app-db. SKIPPED when the event aborted (any
     pre-install throw). :fx is the only post-install stage; an fx throw
     does NOT wind back the installed :db.
```

Five properties this gives:

1. **Flows run on the FULL, fully-reshaped db.** Because the flow transform is the outermost `:after`, it runs after every db-reshaping `:after` in the chain — in particular after a `(path :slice)` interceptor splices the handler's slice back into the complete `app-db`. A flow's bare (app-db) `:inputs` paths address the full `app-db`, so they MUST see the reshaped full db; running them outermost is what guarantees that. (Running flows innermost would expose them to the unspliced path slice and mis-read their inputs.) Qualified `[:rf.db/runtime …]` inputs read the pending runtime-db partition, which the `path` interceptor never touches (per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)).
2. **`:fx` entries see flow outputs.** An `:fx` entry that reads `app-db` after install sees flow-computed values (flows transform the effect at step 2; install at step 3; `:fx` at step 4). This is what makes `[:dispatch [:react-to-area-change]]` work cleanly. (Preserved from the prior design.)
3. **Single pass per event.** Each flow runs at most once per drain. The topological order ensures multi-layer flows settle in one walk.
4. **Run-to-completion is preserved, with exactly one `app-db` write.** The flow transform rewrites the *pending* `:db` effect; the single `:db` install at step 3 is the only `app-db` write the run makes — the flow output is part of that one install, not a second mutation after it. Views never observe an intermediate state.
5. **Frame isolation.** An event dispatched on frame `:left` only walks flows registered against `:left`. Flows on frame `:right` are dormant from `:left`'s perspective — they walk only when `:right`'s drain runs its own flow transform. This is what makes multi-tenant frames safe to colocate without cross-talk in derived state.

**Why a pending-`:db`-effect transform and not a post-install drain step.** The prior design ran flows as a post-commit step: the full interceptor chain ran, `:db` committed to `app-db`, then `run-flows!` mutated the live `app-db`, then `:fx` walked. That split the `app-db` write into two mutations (handler commit, then a separate flow mutation), made the run install the db twice, and fired the `:rf.event/db-changed` trace *before* flows — so the trace did not reflect the flow output and tools could not place flows on the trace timeline correctly. Moving flows to transform the pending `:db` effect (as the outermost `:after`) makes (a) the run perform exactly one `app-db` install — of the flow-augmented value, (b) the `:rf.event/db-changed` trace reflect the flow-augmented db and fire *after* `:rf.flow/computed`, and (c) the whole flow position observable on the trace stream (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)). The `:fx`-sees-flow-output guarantee is preserved unchanged.

**The (t1, t2) pending-`:db` snapshot pair — dev-only observability hook.** The same outermost flows-after-interceptor that runs the flow transform also stamps two trace events bracketing the walk: `:rf.event/db-pending` (t1) BEFORE the walk and `:rf.event/db-pending-post-flow` (t2) AFTER (only when flows changed the value). Both carry the FULL `:db` value under `:tags :rf.event/db` — same posture as `:rf.event/fx` on `:rf.fx/do-fx`, no diff, no DEBUG gate; PDS structural sharing keeps the cost pointer-sized and the `day8/de-dupe` wire layer collapses repeated subtrees on egress. The pair lets the Xray Handler panel render the handler-returned `:db` value AND the t1→t2 flow reshape without a framework-precomputed diff. **No production cost**: both emits sit inside the shared `interop/debug-enabled?` gate so CLJS `:advanced` + `goog.DEBUG=false` DCEs the whole surface. See [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence) for the position in the trace stream.

**Position note — outermost `:after`, not innermost.** Conceptually flows run "right after the handler"; mechanically they run as the *outermost* `:after` so they observe the fully-reshaped `:db` effect. The distinction matters only when the chain contains a db-reshaping `:after` (the `path` std-interceptor): flows must run after that reshape. A consequence is that **user `:after` interceptors run before the flow transform and therefore see the handler's pre-flow `:db` effect, not the flow-augmented one** — observational `:after` interceptors that need flow outputs should read them from `app-db` (post-install) via a sub or a follow-up event, exactly as `:fx` does. (Reshaping the db effect from within an arbitrary user `:after` and then re-running flows would require interleaving the flow walk through the whole `:after` chain, which is neither necessary for any real use case nor compatible with the single-pass guarantee.)

### Trace stream ordering on a flow throw

When a flow's `:derive` fn throws during the flow-transform `:after` (step 2 of the drain integration pseudocode above), the runtime emits a strict trace sequence — observable contract for off-box monitors, Xray, Story, and any consumer that lifts a flow failure off the trace stream. A flow throw is a **pre-install throw**: the event ABORTS before the `:db` install, so the failure stream carries **NO `:rf.event/db-changed`** (per [§Failure semantics](#failure-semantics) — the atomicity contract). A conformant port MUST emit these events in this order:

1. **`:rf.flow/computed`** for each flow that successfully computed before the throwing one — fires per prior flow as it rewrites the pending `:db` effect. (See §Failure semantics for the per-flow detail; these may be absent when the first flow throws.) Note: these traces fire even though the prior flows' writes are ultimately **discarded** by the abort — the trace records that the `:derive` ran, not that the write was committed.
2. **`:rf.flow/failed`** — the per-flow failure trace for the throwing flow, carrying `:flow-id`, a **structured, EDN-safe** exception summary (`:exception-message` + `:exception-data` — NOT a raw `Throwable`), and the elided `:inputs` read just before the throw. The `:exception-data` slot is redacted fail-closed when the flow's frame declares sensitivity (per [009 §Flow trace events](009-Instrumentation.md#flow-trace-events)). Rides the dev-only trace surface; DCEs in CLJS production.
3. **`:rf.error/flow-eval-exception`** — the run-level error, emitted onto the **always-on production error-emit substrate** per [§Failure semantics](#failure-semantics). Carries `:where :flow-eval` (distinguishing this path from `:rf.error/handler-exception`), the originating event under `:rf.event/v`, and `:flow-id` attribution stamped from the throwing flow's wrapped `ex-data`. Attribution is the flow id alone — there is no real flow *value* to carry, so the contract carries `:flow-id` and nothing more. The dev-only trace surface emits the same op concurrently; the production-substrate path survives CLJS `:advanced` + `goog.DEBUG=false` elision.
4. **NO `:rf.event/db-changed`.** The event aborted before the install — neither the handler's `:db` nor any prior flow's write committed (no partial commit). `app-db` is unchanged, and the trace stream carries no `:rf.event/db-changed` for this event. This is the same abort signature every other pre-install throw (cofx / handler / interceptor `:after`) produces.
5. **`:fx` is skipped — no `:rf.fx/handled` from this drain.** The event aborted (per [§Failure semantics](#failure-semantics)); no `:fx` entry runs, no `:dispatch`-issued child events queue. The drain proceeds to the **next** event in the router queue on its normal cycle.

The contract is the ordering AND the gap — consumers can rely on "the flow failure (`:rf.flow/failed` → `:rf.error/flow-eval-exception`) fires, and NO `:rf.event/db-changed` follows: the event aborted, `app-db` is unchanged, and no `:fx` of this drain reached the outside world." Cascading work that *would* have run via `:fx` re-attempts naturally on a later drain — once a drain completes without the flow throwing.

## Topological sort and cycle detection

Flows form a static dependency graph derivable from their `:output-path` and `:inputs` declarations. The graph is **per-frame** — flows in different frames cannot depend on each other (their inputs read different `app-db`s). Each frame's topsort is computed independently over `(get @flows frame-id)`.

**Dependency rule.** Flow B depends on flow A iff A's `:output-path` and any of B's `:inputs` share a path prefix in either direction:

- Exact match: `A.path = [:foo]`, `B.inputs = [[:foo]]` — B reads exactly what A writes.
- A's path is a prefix of B's input: `A.path = [:foo]`, `B.inputs = [[:foo :bar]]` — B reads inside A's value.
- B's input is a prefix of A's path: `A.path = [:foo :bar]`, `B.inputs = [[:foo]]` — A's write is part of B's input map.

The runtime topologically sorts the registry by this dependency relation. The sort is **not memoised** in v1 — the per-frame flow map is tiny (a handful of nodes) and Kahn's algorithm over it is cheaper than the bookkeeping a memo would need. An earlier sketch carried a memoised topsort with explicit invalidation on every `reg-flow` / `clear-flow`; the memo was removed once measurement confirmed the unmemoised call is the cheapest correct option at the per-frame node counts v1 targets. Implementations that observe a real bottleneck in topsort cost MAY add a `core.memoize`-style cache keyed on the flow-registry identity, but the contract is just: deterministic order over the dependency graph each drain.

**Cycle detection.** If A depends on B and B depends on A (any indirection), `reg-flow` throws `:rf.error/flow-cycle` at registration time. The thrown `ex-info`'s `ex-data` carries `:cycle` — an ordered vector of flow ids with a **closing repeat** that names the offending chain (e.g. `[:a :b :a]` for the two-flow cycle `:a → :b → :a`). The error fires before any snapshot is created — caught at registration, not at runtime.

```clojure
;; This will throw at registration:
(rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
(rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)
;; → ex-info ":rf.error/flow-cycle" {:cycle [:a :b :a]}
```

The closing repeat is the contract: tools rendering the cycle (e.g. Xray) display the path verbatim. For an n-flow cycle the `:cycle` vector has `(inc n)` elements, with `(first cycle) = (last cycle)`. The starting node is implementation-defined (deterministic but unspecified) — multiple cycles can yield any one of them as the reported chain.

Cycles can also form *during* flow registration if the new flow completes a cycle that was incomplete before it was registered. The detection runs every `reg-flow` call.

**Disjoint output paths.** Flows in the same frame **MUST** write to pairwise-disjoint output `:output-path`s — no two flows' `:output-path`s may stand in a prefix relationship (identical paths included). `reg-flow` throws `:rf.error/flow-path-overlap` at registration time when a new flow's `:output-path` overlaps an already-registered sibling's. This is checked on every `reg-flow` call, inside the same atomic check-and-insert as cycle detection — the error fires before any state mutates, and the prior registration survives.

The reason this is an error and not merely a topological edge: the dependency rule above compares one flow's `:output-path` against another's **`:inputs`**, never `:output-path` against `:output-path`. Two flows whose *outputs* overlap but whose *inputs* are disjoint therefore get **no edge** between them — both are ready at topsort start, and their relative order falls out of map-iteration order, which is not a contract. The flow that runs second silently wins the shared slot under last-write-wins, with no detection and no documented ordering. Rejecting at registration removes the footgun rather than papering over it with a tie-break that would leave the collision silent.

```clojure
;; All of these throw at registration — the second flow's :output-path
;; overlaps the first's (one is a prefix of the other, identical included):
(rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
(rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
;; → ex-info ":rf.error/flow-path-overlap"  (identical paths)

(rf/reg-flow :c {:inputs [[:w]] :output-path [:x]}    identity)
(rf/reg-flow :d {:inputs [[:h]] :output-path [:x :y]} identity)
;; → ex-info ":rf.error/flow-path-overlap"  (:x is a prefix of [:x :y])
```

The thrown `ex-info`'s `ex-data` carries `:overlap` — `{:flow-ids [id-a id-b] :paths [path-a path-b]}` naming the colliding pair (deterministically ordered so the report is stable across runs) — alongside the canonical `:rf.error/id` / `:where` `'rf/reg-flow` / `:recovery :fix-registration` slots ([009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract)). The caller gives one of the two flows a disjoint `:output-path` and retries. Sibling paths that merely share a non-prefix element are **not** an overlap: `[:x :y]` and `[:x :z]` are disjoint (each writes its own leaf under a shared parent), and only the prefix relationship — where one write would clobber or be subsumed by the other — is rejected.

## Dirty-check semantics

A flow recomputes only when its inputs change by **`=`-equality** since its last evaluation:

```
new-inputs ← (mapv #(get-in app-db %) (:inputs flow))
if new-inputs ≠ last-inputs[[frame-id flow-id]]:
  recompute and write
```

The `last-inputs` table is keyed by `[frame-id flow-id]` so the same flow id registered against two frames maintains two independent dirty-check windows.

Three implications:

1. **No-op `app-db` writes don't trigger.** A handler that writes the same value back to `:width` does not re-fire flows that depend on `:width`.
2. **Path-overlap is sufficient, not necessary, for re-firing.** A flow whose inputs sit at `[:user :profile :name]` does not re-fire when an unrelated path like `[:cart :items]` changes. The dirty-check is per-flow, not per-app-db-change.
3. **First evaluation always fires.** A newly-registered flow's `last-inputs` is uninitialised; its first walk recomputes unconditionally and produces the initial output value.

## Failure semantics

**The event-handling pipeline is atomic up to and including the frame-state install.** The install is the single, deferred, all-or-nothing commit boundary. ANY throw *before* it — in cofx, the handler body, an interceptor `:after`, or the flow transform — aborts the **entire** event across **both partitions**: the pending `:db` (app-db) effect AND the pending `:rf.db/runtime` (runtime-db) effect are both discarded, so neither partition installs, **app-db and runtime-db are left unchanged**, **no `:rf.event/db-changed`** (and no `:rf.event/frame-state-changed`) is emitted, and **no `:fx`** run. A flow throw is just one of these pre-install throws, and behaves identically to every other — even though a flow may *read* runtime-db via a `[:rf.db/runtime …]` input ([§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)), it writes app-db only, so the runtime-db partition is discarded on abort purely as part of the whole-event roll-back, not because a flow wrote it. `:fx` is the only post-install stage; an fx throw surfaces an error but does NOT wind back the installed frame-state (its side effects — http / nav / dispatch — may already have fired and are irreversible).

**An app-db schema REJECTION at the commit boundary aborts the same way (rf2-uhk9ko).** After the flow transform completes cleanly, the flow-augmented CANDIDATE `:db` is schema-validated BEFORE the install ([010 §Per-step recovery row 4](010-Schemas.md#per-step-recovery)). A rejection discards the whole candidate — the handler's write and every flow's write ride out together, nothing installs, no change trace fires — and the flow dirty-check bookkeeping (`last-inputs` + the in-drain abandoned-path vacations) is restored exactly as rule 2 below requires, so every flow re-attempts on the next clean drain. The two failure signatures differ only in their error op: a flow THROW surfaces `:rf.error/flow-eval-exception` (the flow itself failed), a schema rejection surfaces `:rf.error/schema-validation-failure` (the flow computed cleanly; the VALUE failed its declared shape); both emit ZERO `db-changed`.

When a flow's `:derive` fn throws during the flow-transform `:after`, the runtime applies these rules atomically:

1. **No install — both partitions are unchanged.** There is **no partial commit**. The pending `:db` effect (the handler's write plus any prior successful flows' writes) is **discarded**: the flow-transform `:after` drops the `:db` effect from the chain context, so the single deferred install installs nothing — and the pending `:rf.db/runtime` effect is discarded in the same abort, so **runtime-db is left unchanged too**. Neither the handler's `:db`/`:rf.db/runtime` nor any earlier flow's output lands. The atomicity is **free**: because install was already deferred to one write, "wind back on a pre-install throw" is just "don't perform the one write" — no rollback machinery.
2. **The failing flow's own output is not written, and `last-inputs` is rolled back.** The exception happened during `:derive`; there is no usable new-output. The whole drain's dirty-check bookkeeping rolls back too: the `last-inputs` snapshot taken before the walk is restored, so **every** flow — prior-successful and failing alike — re-attempts on the next drain. (Without this, a prior flow whose `last-inputs` advanced would wrongly suppress its recompute next drain even though its output never reached `app-db` — silently losing the write.) **The rollback is scoped to the draining frame's own dirty-check bookkeeping.** Only `last-inputs` rows for `[frame-id …]` (the frame being drained) are snapshotted and restored — a sibling frame draining concurrently on another thread (frames have independent drain-locks per [002 §Rules rule 1](002-Frames.md#rules); there is no global cross-frame serialization) has its dirty-check rows left untouched. A throwing-flow rollback can no more revert a sibling frame's just-advanced `last-inputs` than it can revert a sibling frame's `app-db`; the per-frame dirty-check window from [§Dirty-check semantics](#dirty-check-semantics) holds under concurrent drains by construction.
3. **The drain halts.** Downstream flows scheduled later in topo order do NOT run on this drain. They re-attempt naturally on a later drain — one that completes without the flow throwing. The `:db` install and the `:fx` walk do NOT run for this drain.
4. **The exception surfaces at the router as** `:rf.error/flow-eval-exception` (per [009 §Error contract](009-Instrumentation.md#error-contract)). The run-level error is emitted onto the **always-on production error-emit substrate** ([009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)) — every error-emit (the `:errors` stream of `register-listener!`) callback fires. The substrate is NOT gated by `re-frame.interop/debug-enabled?`, so `:rf.error/flow-eval-exception` survives CLJS `:advanced` + `goog.DEBUG=false` elision: a flow-eval failure in a production build reaches every registered off-box error monitor (Sentry / Honeybadger / Rollbar / hosted observability). The tight listener record carries the failing event-id and frame; the per-flow `:rf.flow/failed` trace event fires first with the full flow-attributed detail (including `:flow-id`), but that trace rides the dev-only trace surface and DCEs in production.

Worked example. Three flows in topo order — `:A`, `:B`, `:C`. Inputs change for all three. `:B` throws. After the drain:

- `app-db` is **unchanged** — `:A`'s output is NOT written (rule 1, no partial commit), the handler's `:db` did NOT land, `:B`'s `:output-path` is unchanged, `:C` did not run.
- **All** `last-inputs` are unchanged from before the drain (rule 2): `:A`'s advance was rolled back, `:B`'s never advanced (it threw), `:C` never ran. All three re-attempt next drain.
- Two flow traces fired in order: `:rf.flow/computed` for `:A` (it *ran* — the trace records the `:derive` call, not a committed write), then `:rf.flow/failed` for `:B`. Then the router emitted `:rf.error/flow-eval-exception` (rule 4). **No `:rf.event/db-changed` fired** (rule 1 — the event aborted before install; per [§Trace stream ordering on a flow throw](#trace-stream-ordering-on-a-flow-throw)).

**Rationale.** The `:db` install is the atomic commit boundary, and atomicity is uniform: a flow throw can no more leave a half-committed `app-db` than a handler throw or an interceptor-`:after` throw can. Discarding the whole pending write keeps one invariant true everywhere: **an event either commits in full or not at all.** Work that *would* have completed re-attempts on a later, clean drain; nothing half-done is ever observable in `app-db`.

### Why this asymmetry? — `:db` is atomic, `:fx` is best-effort

The atomicity contract is **asymmetric across the commit boundary**: a *pre*-install throw aborts cleanly (no `:db` install, no `:fx`, `app-db` unchanged), but a *post*-install throw inside `:fx` does **not** wind `app-db` back — the install already happened, and any fx that already fired (an HTTP POST, a `:dispatch`, a `:local-storage-set`, a navigation) is **not** rolled back. A natural question: shouldn't the commit boundary sit *past* `:fx`, so the whole event is all-or-nothing? The answer rests on three load-bearing constraints.

1. **Most `:fx` are irreversible by construction.** `:http-xhrio` POST mutates a server. `:dispatch` of a downstream event enters the router queue and may already have settled by the time a sibling fx throws. `:local-storage-set` writes through to disk. DOM mutations have been observed. Navigation has fired. The set of side effects that motivates putting work in `:fx` in the first place IS the set that cannot be undone after the fact. If the runtime ran the fx walk and then *skipped* the `:db` install on a later throw, the world's state (the server, the URL bar, local storage, the dispatched-children queue) and `app-db` would **diverge** — strictly worse than today, where both reflect what actually happened, even when one fx blew up. "All-or-nothing for `:fx`" is unimplementable for fx whose effects, by design, escape the runtime.

2. **Knowing "all `:fx` succeeded" requires going async — which breaks composition.** Most `:fx` return immediately and report success/failure asynchronously (`:http-xhrio` fires the request and resolves later via `:on-success` / `:on-failure`; `:dispatch` queues an event whose drain hasn't run yet; a `:dispatch-later` timer hasn't fired). To gate the `:db` install on "every fx succeeded," the event itself would have to become asynchronous — which breaks `dispatch-sync`, breaks the run-to-completion event-queue composition (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)), and would force *every* fx-id to declare sync-vs-async success semantics so the runtime knows when to commit. The whole programming model collapses into a distributed-transaction coordinator.

3. **The model is optimistic local commit, not a transaction coordinator.** This is the classic distributed-transaction problem: a local commit (`app-db`) needs to compose with N external commits (`:fx`) that the local node doesn't fully control. Of the three available answers — (a) **optimistic local commit + compensating-action sagas** (commit `app-db` immediately; on async failure, dispatch a compensating event that reverts the slice and surfaces the failure to the user); (b) **two-phase commit** (every external fx exposes synchronous `prepare` / `commit` phases; the framework runs `prepare` on all of them, then `commit` on all, rolling back on any `prepare` failure — feasible only where the external system supports it, which most HTTP / DOM / navigation surfaces do not); (c) **accept the asymmetry** (commit `app-db` at the boundary; `:fx` is best-effort; surface failures as ordinary error events; let application code compose compensating sagas where rollback semantics matter) — re-frame2's model is **(c)**: `app-db` commits at the boundary, `:fx` is best-effort, failures surface as ordinary error events on the trace bus + the always-on error-emit substrate per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code), and there is no transaction coordinator.

**Escape valve — application-level sagas.** Apps that *do* need rollback semantics across an `:fx` boundary get them at the application layer, not from the framework. The pattern: an event handler writes its optimistic state into `app-db` (the local commit), dispatches the external fx with `:on-failure` pointing at a **compensating event** that reverts the slice and surfaces the failure to the UI, and (optionally) tracks the in-flight epoch in a state machine ([Spec 005](005-StateMachines.md)) so the UI can render "saving…" / "saved" / "save failed — reverting" states explicitly. Two pieces compose: re-frame2's atomic `:db` write gives the local commit; the framework's `:on-failure` reply addressing on managed fxs (per [Managed-Effects §3 Structured failure taxonomy](Managed-Effects.md#3-structured-failure-taxonomy-under-rf), [014 §Failure taxonomy](014-HTTPRequests.md)) gives the compensating-event seam. The framework provides the primitives; the saga IS the application.

**Worked example.** The favorite-toggle in [`examples/real-apps/realworld_http/favorites.cljs`](../examples/real-apps/realworld_http/favorites.cljs) — `:article/toggle-favorite` optimistically flips `:favorited` and bumps `:favoritesCount` in `app-db`, dispatches `:rf.http/managed` with `:on-failure [:article/favorite-rollback slug prior]`, and the rollback handler restores the prior slice if the POST/DELETE fails. The `examples/real-apps/realworld_http/` README catalogues three further compensating-event patterns (favorite toggle, comment delete, follow/unfollow). See [README §Optimistic updates](../examples/real-apps/realworld_http/README.md) and the `realworld-favorites` deftest in [`realworld_cljs_test.cljs`](../implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs) for the headless test that exercises the rollback path.

## Flow tracing

Every flow lifecycle event emits a structured trace event under op-type `:flow`. The full taxonomy lives in [009 §Flow trace events](009-Instrumentation.md#flow-trace-events); the summary:

| `:operation` | Fires when |
|---|---|
| `:rf.flow/registered` | `reg-flow` (or `:rf.fx/reg-flow`) successfully registers a flow against a frame, after cycle detection passes. |
| `:rf.flow/computed` | A flow's `:derive` fn ran and the result was written to its `:output-path` (dirty-check observed input value-difference). Carries `:before` (the value at `:path` immediately before this drain's write — `nil` when the slot had never been written) alongside `:result`, so consumers render the wrote-line "wrote `[:path]` `<before>` → `<after>`" without walking the surrounding epoch's `:db-before` snapshot. Both ride through `elide-wire-value` against the flow's `:path`. |
| `:rf.flow/skip` | The dirty-check found inputs `=`-equal to the previous run; the recompute was suppressed (§[Dirty-check semantics](#dirty-check-semantics) above; value-equal recompute suppression). |
| `:rf.flow/cleared` | `clear-flow` (or `:rf.fx/clear-flow`) removed the flow from the per-frame registry and dissoc-in'd its output path. |
| `:rf.flow/failed` | A flow's `:derive` fn threw during recompute. The exception is re-thrown after the trace fires; see [§Failure semantics](#failure-semantics) for the atomicity contract (the event aborts — no install, `app-db` unchanged, no `:rf.event/db-changed`, `:fx` skipped; `last-inputs` rolled back so every flow re-attempts; router emits `:rf.error/flow-eval-exception` per [009 §Error contract](009-Instrumentation.md#error-contract)). |

Every event carries `:flow-id` and `:frame` under `:tags`. Pair-shaped tools, Xray's flow panel, and custom dashboards filter `op-type :flow` to subscribe to the whole flow stream — see [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) and [009 §Flow trace events](009-Instrumentation.md#flow-trace-events) for the consumer-side pattern.

<a id="flow-output-data-classification-ep-0025"></a>

**Flow classification — TWO distinct mechanisms.** Two classification mechanisms touch a flow, at **different granularities**; they COEXIST and compose. Do not conflate them:

- **(i) Handler-scope `:sensitive?` run stamp (coarse — whole trace event).** A flow's `:derive` fn runs inside the after-interceptor of the surrounding handler scope; the dirty-check write and any thrown exception are framework-owned but the resolved input values and computed output ride from the **handler whose event triggered the drain**. The runtime stamps `:sensitive? true` at the top level of every `:rf.flow/*` trace event when the in-scope handler's registration meta carries `:sensitive? true` — per the inheritance rule at [009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key). At THIS mechanism the flow does not declare `:sensitive?` directly; the *whole-event* marker rides the run. An auth-handler dispatching `[:auth/signed-in token]` whose drain re-evaluates the `:auth/derived-user` flow emits a `:rf.flow/computed` carrying `:sensitive? true`, and the framework-published forwarders (Sentry / Honeybadger / re-frame2-pair / Xray-MCP) default-drop it.

- **(ii) Registration-owned output classification (fine — per output path; NO propagation).** Independently, a flow classifies its **own output** sub-paths with registration-layer `:sensitive` / `:large` keys (each a vector of `:rf/path` vectors into the output shape; `[[]]` classifies the whole output) — redacted at the `:output-path` write and on the `:rf.flow/computed` `:result` slot. **Sensitivity does not propagate**: a flow reading a sensitive `app-db` (or runtime-db-qualified `[:rf.db/runtime …]`, per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)) input does **not** auto-classify its output — classification does not flow input → output. A derived secret is just a new path; the author classifies it directly with the flow's own `:sensitive` / `:large`. There is **no** `:rf.egress/output-sensitivity` declassification claim (and no `:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public` value set) — the key is absent and silently ignored if present, per [015 §No propagation, no taint](015-Data-Classification.md#no-propagation-no-taint). A malformed `:sensitive` / `:large` declaration is rejected fail-closed at registration with `:rf.error/flow-bad-marks` (e.g. a non-vector axis or a non-path subpath entry, or the boolean `:sensitive?` spelling — `:sensitive` is "a collection of sensitive paths" at the registration layer, per the [015 §`:sensitive` / `:sensitive?` cross-layer distinction](015-Data-Classification.md#the-sensitive--sensitive-cross-layer-distinction-ep-0007-rule-3)).

Apps that need finer-grained per-flow privacy classify the surrounding handler's event payload with registration-owned `:sensitive` (on `reg-event` — per [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification), scrubbed on the trace surface by the router's internal redaction plumbing), declare the flow's own output classification per (ii), or scrub the `:derive` fn's return value at the source.

The whole flow trace surface, like the rest of trace, is compile-time eliminated in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).

## Flow output validation

A flow's optional `:schema` key (per [§The registration shape](#the-registration-shape)) declares a Malli schema for the **output value**. When present, the runtime validates the flow's computed `:derive` output against it on every recompute, during the flow-transform `:after` (after the handler body, before the `:db` install — per [§Drain integration](#drain-integration)). This is the same dev-time, pluggable-validator mechanism the rest of [010 §Schemas](010-Schemas.md) uses; the flows artefact reaches the registered validator/explainer through the `:schemas/validate-with-registered-fn` / `:schemas/explain-with-registered-fn` late-bind hooks, so an app that omits the schemas artefact (or registers no validator) pays nothing and the check soft-passes.

**Observational, not a rollback.** A flow `:schema` violation does **not** throw and does **not** unwind the write. This is distinct from a flow `:derive` *throw* (which aborts the whole event — [§Failure semantics](#failure-semantics)): a schema violation is a soft, dev-time diagnostic. The flow computed a value successfully; the value simply fails its declared shape. By the time a violation could be observed, downstream flows in the same drain may already have read the value as their input, so retroactively unwinding one flow's write mid-walk would leave an inconsistent pending `:db` effect. So the output **is** written into the pending effect and the run proceeds normally (the event still commits if no flow throws); the failure surfaces as a diagnostic `:rf.error/schema-validation-failure` error event with `:where :flow-output` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)), carrying the failing `:rf.flow/id`, the flow's `:output-path`, the failing `:value` (size/sensitivity-elided like every wire-bearing trace slot), and the registered explainer's `:explain` output. `:recovery` is `:no-recovery`, matching the category's documented disposition — the check exists to surface a producer bug early, not to repair state.

```clojure
(rf/reg-flow :cart/total
  {:inputs      [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]
   :schema      [:int {:min 0}]}     ;; output must be a non-negative integer
  (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0))))))
```

Flow output validation is dev-only: it sits behind `re-frame.interop/debug-enabled?` and is compile-time eliminated in production builds. That is a fact about this surface, not about the validation surface at large. [C-000.35](000-Vision.md#contract--pattern-obligations) settles what may be elided by what the check is for rather than by who declared the schema it reads, and a `reg-flow` `:schema` is an ordinary registration diagnostic over the programmer's own flow — so it goes, while the checks the framework relies on to keep its own promises stay ([010 §Production builds](010-Schemas.md#production-builds) names them).

## Sub integration

Flows write to `app-db`; subs read `app-db`. **Flows therefore publish zero framework subscriptions.** This is a deliberate posture, not an oversight. The contract is: a flow's output value lives at its `:output-path` in the dispatching frame's `app-db`, and consumers read it through whatever sub registration they prefer — either a user-registered `(rf/reg-sub :my-app/area (fn [db _] (get-in db [:my-app/area])))` over the path, or a derived sub that closes over it. Because the flow transform rewrites the *pending* `:db` effect (per [§Drain integration](#drain-integration)), the flow-derived value reaches `app-db` through the run's single `:db` install — the one `replace-container!` + sub-cache invalidation the drain already performs — so reactivity is automatic and the run performs exactly one `app-db` write per event regardless of how many flows fired; there is no separate flow-output cache the substrate needs to track.

### What this means

A flow named `:my-app/derived-area` with `:output-path [:my-app/area]` is **observable through any of these patterns**, none of them special-cased for flows:

```clojure
;; (a) plain app-db read inside another handler
(rf/reg-event :event/use-area
  (fn [{:keys [db]} _]
    (let [area (get-in db [:my-app/area])] ...)))

;; (b) user-registered sub over the flow's :output-path
(rf/reg-sub :my-app/area (fn [db _] (get-in db [:my-app/area])))
@(rf/subscribe [:my-app/area])

;; (c) derived sub that closes over the path implicitly
(rf/reg-sub :my-app/area-doubled
  :<- [:my-app/area]
  (fn [area _] (* 2 area)))
```

The flow's `:output-path` IS the contract surface. Consumers depend on the path, not on a `:rf.flow/<flow-id>` sub-id. This is the same shape as any other `app-db` value: a flow's output is "ordinary application state with a known producer" — exactly the framing at [§When (and when not) to use a flow](#when-and-when-not-to-use-a-flow).

### Asymmetry with routing

Routing publishes **nine framework subs** over its `:rf/route` slice (`:rf/route`, `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/fragment`, `:rf.route/transition`, `:rf.route/error`, `:rf.route/chain`, `:rf/pending-navigation` — per [012 §Subscriptions](012-Routing.md#subscriptions)). Flows publish zero. The asymmetry is real but principled:

| Surface | Where output lives | Framework subs | Why |
|---|---|---|---|
| Routing | `:rf/route` slice in `app-db` | nine (`:rf/route` + eight derived) | The slice is **a single named map with a fixed shape** — every consumer wants `:id`, `:params`, `:query`, `:transition` as common destructures. Publishing the per-key subs once means every consumer reads the same canonical sub-id (`:rf.route/id`) rather than re-registering eight identically-shaped getters. |
| Flows | An arbitrary path in `app-db` per flow | zero | Each flow's `:output-path` is **user-chosen** and **shape-arbitrary** (could be a number, a vector, a map of any shape, …). There is no canonical "every flow has these eight derived views" to publish. A consumer wanting `(:items @(subscribe [:my-app/cart]))` writes one sub over their cart's `:path`; the framework cannot do this for the user without knowing every flow's output shape. |

The asymmetry follows from the **shape-uniformity** difference: routing's slice has a fixed shape locked by [§The `:rf/route` slice](012-Routing.md#the-rfroute-slice); a flow's output shape is whatever the `:derive` fn returns. Routing's shape uniformity makes framework subs cheap (one registration table, every app sees the same sub-ids); flows' shape arbitrariness makes them impossible (the framework would need a sub-id per flow with a layer-1 fn parameterised on each flow's `:output-path`, doubling the per-flow sub-cache footprint and polluting the registered-sub namespace).

### Observability consequence

A tool wanting to enumerate "which views/handlers depend on this route's id" reads the sub-graph via `(sub-cache-consumers :rf.route/id)` — the standard sub-topology surface gives the answer for free. A tool wanting to enumerate "which views/handlers depend on this flow's output" reads **`app-db` path consumers**, not flow-output consumers: the framework's sub-topology query surface (per [006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal)) returns subs whose layer-1 fn reads the flow's `:output-path`, not subs whose layer-1 fn reads "the flow with this id".

This is an **observability asymmetry** the doc names but does not paper over. Tools rendering "flow consumer" panels (Xray's flow tab, post-v1 dashboards) compute the answer from `:output-path` overlap, not from a framework sub-id. The two enumerations — `(rf/registrations :flow)` (which flows exist) plus `(sub-cache-consumers-of-path [:my-app/area])` (which subs read this path) — together provide the full picture; neither is a framework sub family.

There is **no** framework sub family `:rf.flow/<flow-id>` whose layer-1 fn is `(fn [db _] (get-in db (:output-path flow)))`. Flows write app-db and subs read app-db; the two surfaces stay separate (per the [§When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) framing). The zero-framework-sub posture is the v1 contract.

## Dynamic toggle via fx

Two reserved fx-ids let event handlers register and clear flows during normal event processing:

| Fx-id | Args | Effect |
|---|---|---|
| `:rf.fx/reg-flow` | The 3-slot triple `[flow-id metadata derive-fn]` (the same shape `reg-flow` takes) | Register the flow against the dispatching frame. Next drain's topsort observes the new node (no cache to invalidate; per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection)). |
| `:rf.fx/clear-flow` | A flow id | Clear the flow from the dispatching frame. `dissoc-in` on its `:output-path` in that frame's `app-db`. Next drain's topsort observes the removal. |

```clojure
(rf/reg-event :wizard/enter-step-2
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow [:step-2/computed
                            {:inputs      [[:step-2 :foo] [:step-2 :bar]]
                             :output-path [:step-2 :result]}
                            (fn [foo bar] (compute foo bar))]]]}))

(rf/reg-event :wizard/leave-step-2
  (fn [_ _]
    {:fx [[:rf.fx/clear-flow :step-2/computed]]}))
```

**Frame routing.** Both fx run inside the standard `:fx` walk and receive the `{:frame frame-id}` cofx from the dispatching frame. They thread the frame through to `reg-flow` / `clear-flow` as the `:frame` metadata key — there is no explicit `:frame` to set in the fx args. A flow registered via `:rf.fx/reg-flow` from an event dispatched on frame `:left` is registered against `:left`; the same fx invoked from a `:right` dispatch routes to `:right`. This makes fx-driven flow lifecycle (wizard step in / out, feature gating) automatically frame-correct without ceremony.

### Sequencing — the one-event lag

> **This is the single least-obvious thing about flows. Read it before you reach for `:rf.fx/reg-flow`.** A flow registered mid-event does **not** compute its initial output during *that* event — it first fires on the **next** drain on the same frame.

`:rf.fx/reg-flow` and `:rf.fx/clear-flow` run during the standard `:fx` walk (per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees)) — and the `:fx` walk is the *last* drain stage, **after** the flow-transform `:after` has already evaluated for the current event (per [§Drain integration](#drain-integration), step 4 runs after step 2). The newly-registered flow was not in the per-frame registry when the flow transform walked it, so it cannot have computed. Its initial output therefore appears **one event after registration**, on the next drain on the same frame.

This lag is a **structural consequence of the [§Drain integration](#drain-integration) contract**. The flow transform rewrites the handler's *pending* `:db` effect as the outermost `:after` (step 2); the single deferred `:db` install (step 3) is the run's only `app-db` write; `:fx` walks last (step 4). Re-running the flow transform after `:fx` registered a new flow would require a *second* `app-db` install in the same event — breaking the "exactly one `:db` install per event" invariant ([§Drain integration](#drain-integration) property 4), the pending-effect-transform model ([§Resolved decisions §Flows transform the pending `:db` effect](#flows-transform-the-pending-db-effect-as-the-outermost-after-resolved)), and the atomic-commit contract ([§Failure semantics](#failure-semantics)). An async mid-event re-walk that would close the lag is deferred — see [§Open questions §Synchronous re-walk after `:rf.fx/reg-flow`](#synchronous-re-walk-after-rffxreg-flow).

**Working with the lag.** In the common case the lag is invisible: you register a flow in `:enter` and the user's *next* interaction (which dispatches an event) materialises the output. When you genuinely need the initial value *now*, dispatch a follow-up event from the same handler whose only job is to re-trigger the drain — the flow computes on that drain:

```clojure
(rf/reg-event :wizard/enter-step-2
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow [:step-2/computed
                            {:inputs      [[:step-2 :foo] [:step-2 :bar]]
                             :output-path [:step-2 :result]}
                            (fn [foo bar] (compute foo bar))]]
          ;; The flow is in the registry by the time THIS dispatched event
          ;; drains — so the flow transform on :wizard/settle computes the
          ;; initial output. Without this, :step-2/result stays unset until
          ;; the user's next interaction.
          [:dispatch [:wizard/settle]]]}))

(rf/reg-event :wizard/settle (fn [{:keys [db]} _] {:db db}))   ;; no-op; exists only to drain
```

This is a deliberate, explicit step — not a hidden one. Most apps never need it.

**`clear-flow` cleanup.** Default behaviour is `dissoc-in` on the flow's `:output-path` in the owning frame's `app-db` — the slot is vacated when the flow goes away. Stale derived values left behind would confuse downstream consumers. Apps that want to preserve the value should copy it elsewhere before clearing. Sibling frames are unaffected.

## Re-registration

`reg-flow` with an already-registered `:id` (against the same frame) performs a **surgical update** — same semantics as every other `reg-*` per [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics). The new flow's definition replaces the old in `(get @flows frame-id)`; `last-inputs` for `[frame-id flow-id]` is reset (the new flow re-evaluates on the next event regardless of input change); the next drain's topsort observes the new dependency edges automatically (per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection); v1 does not memoise the sort). In-flight events finish against the resolved handler at the time they entered the drain. Re-registering the same flow id against a *different* frame is not a replacement — it adds an independent definition to the second frame's slot.

When the same-frame replacement also **moves the output `:output-path`** (the new definition declares a different `:output-path` than the old), the *old* path is vacated from the frame's `app-db` — the same `dissoc-in` cleanup `clear-flow` performs (per [§clear-flow cleanup](#frame-scoping) above). Otherwise the previous definition's last write would linger at the abandoned slot and downstream reads would see stale derived state that no live flow maintains. A same-frame replacement that keeps the `:output-path` does not vacate anything — the next recompute overwrites the slot in place.

## What flows are NOT

Three near-neighbours flows are *not*:

| Concept | Difference |
|---|---|
| **Subscription** ([006](006-ReactiveSubstrate.md)) | Subs live in the sub-cache; consumed by views. Flows live in `app-db`; consumed by everything (handlers, other flows, schemas, SSR payload). When the value is part of the application's *state*, use a flow; when it's part of view rendering only, use a sub. |
| **State machine** ([005](005-StateMachines.md)) | Machines have transitions, hierarchical states, `:always`/`:after`/`:spawn`, snapshots at `[:rf.runtime/machines :snapshots <id>]` in runtime-db. Flows have one pure function and one output path. Use a machine when there are discrete states; use a flow when the value is a pure function of inputs. |
| **`on-changes` interceptor** (v1) | `on-changes` is wired into specific events' interceptor chains. Flows are registered against a frame and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. The compute-on-change semantics are identical; the registration shape and lifecycle are different. |

Flows are also explicitly *not*:

- **A second runtime.** Flows participate in the standard event drain via one after-interceptor implicit on every event; there is no parallel scheduler. Compare the v1 alpha bardo state machine, lifecycle policies, and per-flow `:fx` mechanism — all gone.
- **A side-effect mechanism.** Flows compute values; they don't fire fx. If a derived value's change should trigger an effect, dispatch a follow-up event whose handler reads the flow's output and emits the effect.
- **A subscription replacement.** Most derived values are still subs. Flows pay an `app-db` write per recomputation; the value is more visible but slightly more expensive than a sub-cache hit.

## Migration from v1 alpha flows

| v1 alpha | v2 |
|---|---|
| `:id` | `:id` (unchanged) |
| `:inputs` (map of keyword → path-or-`flow<-`) | `:inputs` (vector of paths). Map-keyed inputs that referenced other flows via `flow<-` collapse to plain paths — the topological sort handles dependency ordering automatically. |
| `:output` (function of resolved-inputs map) | `:derive` (function of positional inputs) |
| `:path` | `:output-path` |
| `:live?`, `:live-inputs` | Dropped. Use `:rf.fx/clear-flow` to toggle off; `:rf.fx/reg-flow` to toggle on. |
| `:cleanup` | Dropped. Default is `dissoc-in` on `:output-path`; opt-out is not provided. |
| Per-flow `:fx` | Dropped. Dispatch an event from a handler if you need fx on flow output change. |
| Lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Not applicable. Lifecycle policies are a sub-cache concern; flows have one cache state (registered-or-not). |
| `flow<-` reified flow-to-flow input | Dropped. Flow B reads flow A by listing A's `:output-path` in its `:inputs`. |
| `:reg-flow` / `:clear-flow` (unprefixed fx-ids) | Renamed to `:rf.fx/reg-flow` / `:rf.fx/clear-flow` per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned). |

The migration agent rewrites mechanically; flow definitions that used `:live?` lift to a wrapping event-handler that calls `:rf.fx/clear-flow` when the predicate flips false.

## Conformance coverage

The behaviours below are exercised by **realized** `spec/conformance/fixtures/flow-*.edn` fixtures (driven through the live flows runtime by `implementation/flows/test/re_frame/flows_conformance_test.clj` — the flows artefact's own conformance gate) and, for the cases a fixture cannot express ergonomically, by the flows artefact's unit tests under `implementation/flows/test/re_frame/`. The materialized fixture set is broader than this list; the rows here record where each behaviour lands:

| Behaviour | Realized coverage |
|---|---|
| Single flow, multiple inputs, one output; dirty-check re-fires on input change | `flow-recompute-on-input-change.edn` |
| `:rf.fx/reg-flow` / `:rf.fx/clear-flow` from event handlers (lifecycle) | `flow-toggle-via-fx.edn`; `flow-lifecycle-emits-traces.edn` (trace lifecycle) |
| Multi-layer flows — A runs before B when B depends on A's output (topological order) | `flow-multi-input-topo.edn`; topo-sort unit tests `flows_topo_test.clj` |
| Registering a cycle throws `:rf.error/flow-cycle` | unit tests `flows_topo_test.clj` / `flows_test.clj` (a registration-time throw is asserted directly, not via a corpus outcome) |
| An `app-db` write producing a `=`-equal value does not re-fire dependent flows | `flow-noop-on-value-equal-input.edn` |
| Same flow id registered against two frames with different `:derive` fns yields two independent results; `clear-flow` on one frame leaves the other intact | `flow-frame-scoped.edn`; `flow-frame-destroy-teardown.edn` (per-frame teardown) |

## Open questions

> **SA-4 classification.** Both items below are **post-v1, untracked notes** per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md) — deferred design work the corpus tolerates shipping without resolving (the v1 design is settled — vector `:inputs`, lag-on-register), marking candidate enhancements rather than blocking gaps. Neither has a tracking bead filed yet, so **neither qualifies as `:post-v1 tracked`** (which requires a `rf2-<id>`); each records the concrete reconsideration trigger that files its bead when it fires. (The earlier "both classify as `:post-v1 tracked` … track a real bead once one is filed" framing was self-contradictory — a `:post-v1 tracked` item by definition already has the bead.)

### Map-keyed `:inputs` instead of vector

The vector form (`:inputs [[:width] [:height]] :derive (fn [w h] ...)`) matches `on-changes` and is short. A map-keyed alternative (`:inputs {:w [:width] :h [:height]} :derive (fn [{:keys [w h]}] ...)`) matches [Principles §Name over place](Principles.md#name-over-place). The vector is the v1 default; the map is the principled default. v2 ships the vector form for migration ergonomics. (This is purely a keying question — orthogonal to the per-path partition rule of [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db): either form's individual paths are still bare-for-app-db / `[:rf.db/runtime …]`-for-runtime-db.)

- **Reconsideration trigger (falsifiable).** Not "the map form proves preferable in practice" (not checkable) — instead: **N ≥ 3 real flows across the corpus / consumer apps carry 4-or-more `:inputs` and get bitten by positional-destructure fragility** (a `:derive` arg reordered or dropped without the `:inputs` vector kept in lockstep, or a review comment flagging the positional binding as unreadable at that arity). At that point the map form's name-over-place ergonomics have a measured cost to weigh against the migration break. Until then the vector form stands.
- **Status.** Post-v1, **untracked note** — no bead filed yet; the trigger above files one when it fires.

### Synchronous re-walk after `:rf.fx/reg-flow`

A flow registered mid-event first fires on the next event drain (one-event lag for the initial value — the structural consequence documented at [§Sequencing — the one-event lag](#sequencing--the-one-event-lag)). An opt-in "register and run immediately" effect could close the lag at the cost of a *second* mid-event `app-db` install, which would break the one-install-per-event invariant ([§Drain integration](#drain-integration) property 4) and the atomic-commit contract ([§Failure semantics](#failure-semantics)) — so it is genuinely deferred design work, not a quick toggle. Until then the lag is loudly signposted (spec §Sequencing, the `:rf.fx/reg-flow` fx-handler docstring, and [docs/api/re-frame.flows.md §The one-event lag](../docs/api/re-frame.flows.md)) and worked around with an explicit follow-up `:dispatch`. **Reconsideration trigger (falsifiable).** A concrete app hits a case where the one-event lag is a genuine obstacle that the explicit follow-up `:dispatch` workaround cannot cover cleanly — at which point the "register and run immediately" effect is designed against the one-install-per-event and atomic-commit constraints above. **Status:** post-v1, **untracked note** — no bead filed yet; the trigger files one when it fires.

## Resolved decisions

### Topological sort over registration order (RESOLVED)

Earlier sketch leaned on registration order; topological sort selected because dynamic registration via `:rf.fx/reg-flow` makes registration order dispatch-time-dependent and an unreliable contract. The dependency graph is statically derivable from each flow's `:output-path` and `:inputs`; recomputing the sort once per drain is cheap at v1's per-frame node counts (a handful of flows, Kahn's algorithm over them is cheaper than memo bookkeeping). The sort is not memoised — per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection); an earlier memoised variant was removed under after measurement.

### One-pass evaluation, not fixed-point iteration (RESOLVED)

Topological sort lets every flow settle in one walk. Fixed-point iteration was considered as an alternative for cases where flows form mutual dependencies — but mutual dependencies are exactly cycles, which the topsort rejects at registration. With cycles forbidden, one pass suffices.

### Vector `:inputs`, not map (RESOLVED for v1; revisit later)

Per [§Open questions §Map-keyed `:inputs`](#map-keyed-inputs-instead-of-vector), the vector form ships in v1 for migration ergonomics. The map-keyed alternative remains a design option for a future iteration.

### `clear-flow` always `dissoc-in`s the output path (RESOLVED)

No opt-out. Stale derived values are confusing; vacating the slot is the natural toggle-off semantics. Apps that want to preserve the value should copy it elsewhere before clearing.

### Frame-destroy teardown is mandatory (RESOLVED)

`destroy-frame!` MUST release every per-frame piece of flow state — the per-frame flow-registry entry and all `last-inputs` rows for the destroyed frame. Sibling frames' entries and rows are preserved (single-store: a surviving frame keeps its own authoritative entry in place — there is no frame-blind registrar `:flow` slot to prune or realign). Per [§Frame-destroy teardown](#frame-destroy-teardown). Without this, long-running SSR JVM hosts (per-request frame churn), pair-tool time-travel, and `make-frame` ephemeral usage leak flow definitions and cached input vectors indefinitely. Symmetric with the machines / schemas / SSR teardown hooks the per-feature artefacts publish off the single normative `destroy-frame!` boundary at [002 §Destroy](002-Frames.md#destroy).

### Flows transform the pending `:db` effect, as the outermost `:after` (RESOLVED)

The flow walk runs **immediately after the handler's interceptor chain — as the outermost `:after` interceptor — and transforms the handler's pending `:db` effect in the chain context**, before the single `:db` install and before `:fx`. This replaces the prior design (full interceptor chain → `:db` commits to `app-db` → `run-flows!` mutates the live `app-db` → `:fx` walks). Per [§Drain integration](#drain-integration) and [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode).

The change makes three things true that the post-install design could not: (a) the run performs exactly one `app-db` install — of the flow-augmented value — rather than a handler commit followed by a separate flow mutation; (b) the `:rf.event/db-changed` trace fires AFTER flows, so it reflects the flow-augmented db, and `:rf.flow/computed` precedes `:rf.event/db-changed` on the trace stream (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)) — making the flow position observable for Xray's Trace panel; (c) flows transform the *pending effect* rather than the live container, so the write is part of the run's single install. The `:fx`-sees-flow-output guarantee is **preserved** — `:fx` still walks after the install, so it reads the flow-derived `app-db`.

**Outermost, not innermost.** Flows run as the *outermost* `:after` (fired last) — NOT the innermost — because the `path` std-interceptor's `:after` reshapes the `:db` effect (splicing a slice back into the full db), and a flow's bare (app-db) `:inputs` paths address the full `app-db`, so they must run after that reshape. (Qualified `[:rf.db/runtime …]` inputs read the pending runtime-db partition, which `path` never touches.) The consequence is that user `:after` interceptors run before the flow transform and see the handler's pre-flow `:db` effect; observational interceptors that need flow output read it from `app-db` post-install (sub / follow-up event), as `:fx` does. This is the pre-alpha masterpiece choice: no back-compat shim, the prior post-install drain step is removed outright; correctness (flows read the full db) is the load-bearing constraint that fixes the placement.

### A flow throw aborts the event — atomic commit boundary (RESOLVED, Mike 2026-05-24)

The `:db` install is the single, deferred, **all-or-nothing** commit boundary. ANY throw before it — cofx, handler, interceptor `:after`, or the flow transform — aborts the entire event: no install, `app-db` unchanged, no `:rf.event/db-changed`, no `:fx`. A flow throw is just one such pre-install throw and MUST behave identically to a handler / interceptor-`:after` throw. `:fx` is the only post-install stage; an fx throw does NOT wind back the installed `:db` (side effects may already have fired).

This **replaces** the earlier "prior-flow writes still commit on a flow throw" rule. That rule committed a partial `app-db` — making flow throws behave differently from every other pre-install throw, and committing state from an event the runtime simultaneously reported as failed. The atomicity rule is also **free**: because the install is already deferred to a single write, winding back on a pre-install throw is just *not performing that write* (the flow-transform `:after` discards the pending `:db` effect) — there is no rollback machinery, no partial-commit special-case. The dirty-check bookkeeping rolls back in lockstep: the `last-inputs` snapshot is restored on a throw so every flow re-attempts cleanly on a later, clean drain. The invariant the whole design now upholds: **an event either commits in full or not at all.** Per [§Failure semantics](#failure-semantics).

### `:rf.error/flow-eval-exception` rides the always-on error substrate (RESOLVED)

Flow evaluation failures MUST surface on the always-on production error-emit substrate (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)), NOT on the dev-only trace surface alone. The corpus-wide error-emit registry (the `:errors` stream of `register-listener!`) fires under CLJS `:advanced` + `goog.DEBUG=false`, delivering the tight record (failing event-id + frame). The per-flow `:rf.flow/failed` trace still fires first with full flow-attributed detail (including `:flow-id`), but it rides the dev-only trace surface and DCEs in production. Without this routing, a production-build flow-eval failure was silently dropped — no off-box monitor record. Per [§Failure semantics](#failure-semantics) rule 4.

## Cross-references

- [001-Registration](001-Registration.md) — registration grammar (`reg-flow` is a kind under `:flow`).
- [002-Frames §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode) — where the flow after-interceptor sits.
- [002-Frames §Destroy](002-Frames.md#destroy) — the normative teardown boundary `:rf.flow/*` state hangs off; cross-referenced from [§Frame-destroy teardown](#frame-destroy-teardown).
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — sub-cache invalidation; flows trigger sub-cache invalidation when they write.
- [Derivations](Derivations.md) — the derivation/process algebra: a flow is a **materialized `:after-event` derivation** (`:storage :app-db`, `:lifecycle :frame`) — the same whole-value function as the equivalent subscription, differing only in storage/evaluation/lifecycle policy (see [Derivations §Worked equivalence](Derivations.md#worked-equivalence--one-function-two-policies)). The whole-value law every derivation obeys is owned there; the `:after-event` sequencing it summarizes is owned here ([§Drain integration](#drain-integration)).
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/flow-cycle` and `:rf.error/flow-eval-exception` namespaces.
- [009-Instrumentation §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) — the always-on error-emit substrate `:rf.error/flow-eval-exception` rides; cross-referenced from [§Failure semantics](#failure-semantics) rule 4.
- [009-Instrumentation §Flow trace events](009-Instrumentation.md#flow-trace-events) — full taxonomy and payloads for the `:rf.flow/*` event vocabulary; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [009-Instrumentation §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key) — `:rf.flow/*` trace events inherit `:sensitive?` from the in-scope handler at drain time; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [Conventions](Conventions.md) — `:rf.fx/reg-flow` and `:rf.fx/clear-flow` reserved fx-ids.
- [MIGRATION §M-19](../migration/from-re-frame-v1/README.md) — generic call-shape migration; `:inputs` is positional vector matching the v1 `on-changes` form.
