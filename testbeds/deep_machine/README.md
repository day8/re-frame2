# `testbeds/deep-machine`

One Reagent-mounted state machine that, in a single registration,
exercises every grammar surface from [`spec/005-StateMachines.md`](../../spec/005-StateMachines.md)
a tool (Causa, Story, re-frame2-pair-mcp) needs to discriminate when
visualising hierarchy and transition cascades. The buttons drive the
machine through each capability one at a time.

| Capability | Where it lives | What a consumer should see |
|---|---|---|
| **Parallel regions** | `:type :parallel` at the root; two regions `:work` + `:health` | The snapshot's `:state` is a map of region → path. One event broadcasts to both regions — `:work/go` advances `:work` while `:health` stays at `:cold`. |
| **Hierarchical compound 5+ levels deep** | `:work` region descends `:idle → :phase-a → :sub-a → :nested-a → :deep-a → :leaf-a` | Active leaf path on click of `work-go` is `[:work :phase-a :sub-a :nested-a :deep-a :leaf-a]` — region + five compound levels. Deepest-wins resolution walks back up the path on every event. |
| **`:always`** (eventless transitions) | `:work` region's `:resolving` node | After `work-done`, the runtime fires a microstep cascade through `:resolving` to `:done-a` without an explicit event on the trace stream. The `:phase-a-ran?` guard's truthiness picks the branch. |
| **`:after`** (delayed transitions) | `:health` region's `:warming` node — `{:after {200 :ready}}` | `health-heat` schedules a `:dispatch-later` for the `:rf.machine.timer/after-elapsed` synthetic event; ~200ms later the region transitions to `:ready`. Per-region `:rf/after-epoch-by-region` slot at `[:data :rf/after-epoch-by-region :health]` advances on each entry/exit. |
| **`:spawn`** (single child actor) | `:work/leaf-a` (the deepest leaf) | On entry, the desugared `:rf.machine/spawn` fx mounts `:helper/tick` at `[:rf/spawned :deep/main [:work :phase-a :sub-a :nested-a :deep-a :leaf-a]]`. On exit (via `work-done`), the desugared `:rf.machine/destroy` fires. |
| **`:spawn-all`** (parallel children + join) | `:work/phase-b` | After `work-spawn`, the runtime emits one `:rf.machine.spawn-all/started` plus three `:rf.machine/spawn` fxs (one per child id `:j1` / `:j2` / `:j3`). Each child dispatches `:helper/child-done` to the parent from its terminal `:done` `:entry`; after the third the runtime fires `:rf.machine.spawn-all/all-completed` and dispatches `[:helper/all-finished]` into the parent, transitioning to `:done-b`. |

## Button reference

| Button | `data-testid` | Drives |
|---|---|---|
| `:work/go` | `work-go` | Descend the deep hierarchy into `[:work :phase-a … :leaf-a]`; `:spawn` spawns `:helper/tick`. |
| `:work/done` | `work-done` | Exit the deep leaf; `:spawn`-destroy fires; `:always` cascade settles into `:done-a`. |
| `:work/spawn` | `work-spawn` | `:spawn-all` spawns three `:helper/job` children. |
| finish j1 / j2 / j3 | `helper-finish-j1`, `helper-finish-j2`, `helper-finish-j3` | Each transitions one child to its `:final?` leaf; the child's terminal `:entry` dispatches `:helper/child-done` to the parent; the runtime advances `:spawn-all` join bookkeeping; the third triggers `:on-all-complete` which dispatches `[:helper/all-finished]` into the parent and transitions to `:done-b`. |
| `:work/reset` | `work-reset` | Return `:work` region to `:idle` from any state. |
| `:health/heat` | `health-heat` | Drive `:health` region from `:cold` → `:warming` → (after 200ms) → `:ready`. |
| `:health/cool` | `health-cool` | Return `:health` region to `:cold`. |

## Why one machine, not five

The five capability axes (parallel / hierarchical / `:always` /
`:after` / `:spawn` / `:spawn-all`) compose in the spec; a
visualiser that handles each in isolation may still break when they
interact — e.g. a `:spawn` declared on a deeply nested leaf inside
a region, where the spawn/destroy fx must be emitted with the full
prefix-path resolved at desugar time. A tool that conflates the
spawn-registry path with the leaf-only key fails on this surface.

The two child machines (`:helper/tick`, `:helper/job`) are minimal —
they exist as well-formed `:machine-id`s the parent's `:spawn` /
`:spawn-all` can address. Their bodies are not interesting; the
load-bearing thing this surface shows is the parent's spawn /
destroy / join shape, not what the children compute.

## What's deliberately *missing*

- **No `:final?` on the parent.** The parent never terminates;
  `:work/reset` cycles it. A future surface can layer the final-state
  contract.
- **No nested parallel regions.** Per [spec/005 §Parallel regions]
  v1 explicitly disallows nesting; `create-machine-handler` would
  reject this at registration. The five-deep compound on `:work`
  + the top-level parallel split between `:work` and `:health` is
  the depth ceiling under the v1 grammar.
- **No `:on-error` policy.** Per-frame error overrides are a
  separate contract; this surface stays on the happy path so each
  cascade is observable as a clean transition.
- **No `:after` driven by a snapshot-fn or a subscription.** Per
  [spec/005 §`:after` delay shapes] those are valid; the testbed
  uses a literal `pos-int?` for the deterministic 200ms test
  window. A future surface can layer the fn-delay shape.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- Trace panel populates on first dispatch — each Button produces a
  rich cascade (multiple `:rf.machine/*` traces per click) that
  exercises the trace panel's grouping by `:cascade-id`.
- Click-to-source from trace event lands on source-coord line —
  every `:guards` / `:actions` entry in the deep machine carries
  reader meta; the `:rf.machine/source-coords` index resolves
  every transition's source line.

**Cross-cutting (6)**:
- **State-machine transition cascade shows `:rf.machine/*` events**
  — the load-bearing scenario this surface unblocks. Each of the
  buttons above produces a different `:rf.machine/*` trace shape
  the consumer's panel must surface and discriminate:
  - `work-go`: `:rf.machine/transition` (compound entry cascade) +
    `:rf.machine/spawn` (the `:spawn` desugaring).
  - `work-done`: `:rf.machine/destroy` (the `:spawn` desugaring on
    leaf exit) + `:rf.machine/transition` (the `:always`
    microstep).
  - `work-spawn`: `:rf.machine.spawn-all/started` + three
    `:rf.machine/spawn` in source order.
  - `helper-finish-j3` (after j1 and j2): three intercepted
    `:helper/child-done` dispatches + `:rf.machine.spawn-all/all-completed`
    + `:rf.machine/transition` (`:helper/all-finished` →
    `:done-b`).

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/deep-machine
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/deep-machine`; output lands in
`implementation/out/testbeds/deep-machine/`.

## Cross-references

- [`spec/005-StateMachines.md` §Hierarchical compound states](../../spec/005-StateMachines.md) — the deep-hierarchy contract `:work` exercises.
- [`spec/005-StateMachines.md` §Parallel regions](../../spec/005-StateMachines.md) — the top-level `:type :parallel` shape.
- [`spec/005-StateMachines.md` §Declarative `:spawn`](../../spec/005-StateMachines.md) — the deepest-leaf actor lifecycle.
- [`spec/005-StateMachines.md` §Spawn-and-join via `:spawn-all`](../../spec/005-StateMachines.md) — the `:phase-b` three-child shape.
- [`spec/005-StateMachines.md` §Delayed `:after` transitions](../../spec/005-StateMachines.md) — the `:health/warming` 200ms timer.
- [`spec/005-StateMachines.md` §Eventless `:always` transitions](../../spec/005-StateMachines.md) — the `:work/resolving` decision node.
