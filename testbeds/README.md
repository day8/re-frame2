# Testbeds — shared framework-behavior surfaces

> **Type:** Test fixture (framework-behavior).
> Not a tutorial. The apps here deliberately error, deliberately leak, deliberately blow budgets — every line exists to give a tool (Causa, Story, pair2-mcp, causa-mcp) something concrete to observe. Read [`examples/`](../examples/) for tutorial-shaped apps; read here when you need to know what a panel/recorder/MCP wire is *supposed* to do when a `:rf.error/*`, `:rf.http/*`, or `:rf.flow/*` event flies past.

The split lives in [`spec/Ownership.md` §examples-split](../spec/Ownership.md) and the umbrella decision sits in **rf2-96nb3**. Top-level structure:

- **`examples/`** — tutorial apps. Each demonstrates one or two specs and reads as exemplary application code.
- **`tools/<tool>/testbeds/`** — tool-specific fixtures. Colocated with the tool that owns them; for example, `tools/causa/testbeds/counter-driven/` exercises Causa's trace panel against a known counter dataflow.
- **`testbeds/`** *(this directory)* — **shared** framework-behavior fixtures. Consumed by multiple tools at once; the surfaces don't know about consumers, and consumers test against them externally.

## Layout

```
testbeds/
  deliberate_throw/        <-- four exception trigger sites (handler / fx / flow / machine)
  schema_violation/        <-- four schema-validation trigger sites (app-db / event / cofx / fx)
  http_toggle/             <-- single button + outcome dropdown for the eight :rf.http/* categories
  multi_frame/             <-- three frames coexisting; cross-frame :dispatch fan-out from one click
  deep_machine/            <-- one machine: parallel regions + 5-deep compound + :always/:after/:invoke/:invoke-all
  long_flow_w_failure/     <-- 5-second cascade driving 3 flows in topo order with configurable mid-flow throw
```

Per-surface conventions (every testbed in this directory follows them):

1. **One subdirectory per surface.** Sources, `index.html`, and a per-surface `README.md` are colocated; the namespace is kebab-cased to match the snake_cased directory name (`deliberate_throw/` → `deliberate-throw.core`).
2. **Stark code.** No tutorial commentary in the bodies; HOT PATH comments only at the trigger sites — the one or two lines that produce the framework event the consumer is watching for.
3. **Minimal app-db.** A testbed is *not* the place to show off a realistic app shape; the `non-trivial-app-db/` testbed (Tier 3) is where that lives.
4. **Reagent canonical.** A surface ships under a single substrate unless cross-substrate behaviour is the point.
5. **Central build wiring.** Shadow-cljs build entries live in [`implementation/shadow-cljs.edn`](../implementation/shadow-cljs.edn) under the `:testbeds/*` build-id prefix; the examples orchestrator at [`examples/scripts/serve-and-run-examples-tests.cjs`](../examples/scripts/serve-and-run-examples-tests.cjs) picks them up via the same source-path + index.html + outDir triple every example uses.

## Running a testbed in dev

From `implementation/`:

```bash
shadow-cljs watch testbeds/deliberate-throw
shadow-cljs watch testbeds/schema-violation
shadow-cljs watch testbeds/http-toggle
```

Then open `http://localhost:9630/build/<build-id>/dashboard` (or the per-testbed index.html served from `implementation/out/testbeds/<name>/`). The Causa preload is wired on every testbed build (mirroring the examples convention) so the trace panel and dev-tools are live on each surface.

## Which tools consume what

| Surface | Causa scenarios | Story scenarios | pair2-mcp scenarios |
|---|---|---|---|
| `deliberate_throw/` | Handler exception surfaces as `:effects` outcome `:error` on epoch record; `:rf.error/handler-exception` highlighted in trace stream; partial epoch on flow throw; structured trace in Story `:play` replay | Recorder captures the click, plays it back, repro is identical; `:rf.assert/*` envelope around the trigger | Trace event arrives over the wire with `:rf.error/*` :op-type intact; `:dispatch` MCP call surfaces the error in the response envelope |
| `schema_violation/` | Schema-validation-failure trace + `:rollback?` flag visible in trace panel; `:where` tag is one of `:event / :app-db / :cofx / :fx-args`; recovery (`:rollback?`, `:skipped`, `:replaced-with-default`) matches the per-step table | Recorder redacts post-`:sensitive?` events; the variant under `app-db` validation triggers the rollback path observable in subscribed views | Schema-validation-failure event arrives with `:path`, `:value`, `:explain` carried verbatim; `:rollback?` flag visible |
| `http_toggle/` | Ordered `:rf.http/*` events visible in trace panel; `:kind` tag matches the picked outcome; category attribution preserved across abort + retry | Recorder captures the outcome-toggle as ordinary event vector; replay reproduces the same `:rf.http/*` category deterministically (stubs are pure) | `:rf.http/managed` envelope visible in the dispatch response; the eight categories enumerate against a fixed outcome dropdown |
| `multi_frame/` | Separate epoch ring buffers per frame; the Cross-bump click produces three distinct epoch records (one per frame) with `:frame` tag intact; time-travel scrubs each frame independently | Time-travel scrub within a Story variant mounting this surface preserves per-frame isolation; the recorder captures the cross-frame fan-out as one envelope-rooted sequence | `:dispatch` MCP call with `{:frame ...}` opt arrives at the addressed frame; the response envelope carries the resolved `:frame` verbatim; cross-frame fan-out visible in the cascade's epoch record |
| `deep_machine/` | `:rf.machine/*` events for every transition / spawn / destroy / invoke-all-init / invoke-all-join visible in the trace stream; the `:state` snapshot reads correctly at every compound rung; click-to-source resolves every named action / guard | Recorder captures the deep cascade deterministically; replay reproduces the same `:rf.machine/*` shape | Snapshot-restore round-trip preserves the full `:state` map for parallel regions and the deep compound path; `:rf/after-epoch-by-region` survives `pr-str` |
| `long_flow_w_failure/` | Trace panel grows on subsequent dispatch over 60+ trace events from one Start click; `:rf.flow/failed → :rf.error/flow-eval-exception` pairs highlighted; ring-buffer budget enforced under cascade saturation when `total-ticks` dialled up | Recorder captures the timed cascade deterministically (timers are data, flows are pure) | The four-rule contract observable over the MCP wire as ordered trace events — `:rf.flow/computed` for `:flow-a` survives every drain; `:rf.flow/failed` for `:flow-b` appears at the threshold |

## Tier roadmap (rf2-kzcim)

Tier 1 — load-bearing trio:

- ✅ `deliberate_throw/`
- ✅ `schema_violation/`
- ✅ `http_toggle/`

Tier 2 — state-machine + frame surfaces (this commit set):

- ✅ `multi_frame/`
- ✅ `deep_machine/`
- ✅ `long_flow_w_failure/`

Tier 3 (devtools edge cases): `drain_depth_trigger/`, `non_trivial_app_db/`, `sensitive_dispatcher/`, `large_dispatcher/`.

Tier 4 (a11y): `known_bad_a11y/` — a Story variant with a deliberate a11y violation; the variant root carries the violation, not the Story chrome (cross-ref rf2-qgms1).

## Cross-references

- [`spec/002-Frames.md` §What lives in a frame](../spec/002-Frames.md) — the per-frame partitioning + cross-frame dispatch contract the `multi_frame/` surface exercises.
- [`spec/005-StateMachines.md` §Hierarchical compound states / §Parallel regions / §Declarative `:invoke` / §Spawn-and-join via `:invoke-all` / §Delayed `:after` / §Eventless `:always`](../spec/005-StateMachines.md) — the six machine-grammar capabilities the `deep_machine/` surface exercises in one declaration.
- [`spec/009-Instrumentation.md` §Error contract](../spec/009-Instrumentation.md) — the `:rf.error/*` taxonomy the `deliberate_throw/` surface fires every member of.
- [`spec/010-Schemas.md` §Per-step recovery](../spec/010-Schemas.md) — the five validation points the `schema_violation/` surface walks one button per.
- [`spec/013-Flows.md` §Failure semantics](../spec/013-Flows.md) — the four-rule flow-failure contract the `long_flow_w_failure/` surface exercises over a multi-second cascade.
- [`spec/014-HTTPRequests.md` §Failure categories](../spec/014-HTTPRequests.md) — the eight `:rf.http/*` categories the `http_toggle/` outcome dropdown enumerates.
- [`spec/Ownership.md`](../spec/Ownership.md) — where every contract surface this directory exercises is owned.
