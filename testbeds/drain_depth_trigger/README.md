# `testbeds/drain-depth-trigger`

A single Reagent-mounted handler whose `:fx` recursively dispatches
itself. The runtime's run-to-completion drain halts the cascade when
the frame's `:drain-depth` ceiling is reached and rolls the frame's
`app-db` back atomically (per [spec/002 §Run-to-completion rule 3]).
A consumer (Causa, Story, pair2-mcp) observes the
`:rf.error/drain-depth-exceeded` shape, the rollback, and the
`:halted-depth` epoch outcome (rf2-v0jwt).

## The cascade

```
click Start
  → dispatch [::recurse]
       :db  (update db :depth-reached inc)
       :fx  [[:dispatch [::recurse]]]   ← always
            ...
```

`::recurse` has no termination branch on purpose. The cascade only
halts via the runtime's ceiling. After the halt:

- `:depth-reached` reads back to `0` — rule 3 rollback evidence.
- The error-emit substrate (per [spec/009 §What IS available in
  production]) fires `:rf.error/drain-depth-exceeded`; the surface's
  in-app listener flips `:halted?` to `true` via a second dispatch
  (which runs on a fresh drain post-rollback).
- The frame's epoch record for the failed cascade carries outcome
  `:halted-depth` (per [Spec-Schemas §`:rf/epoch-record` Outcomes],
  rf2-v0jwt).

## Controls

| Control | `data-testid` | What it does |
|---|---|---|
| `Drain depth ceiling` | `drain-depth` | The frame's `:drain-depth`. Re-registers the default frame with the new value (a surgical update per [spec/002 §Surgical update] — only the ceiling changes; in-flight events and app-db are not reset). Default 25. |
| `Start (recurse — halts at depth)` | `start` | Dispatches `[::recurse]`. The handler recurses; the drain halts. |
| `Reset` | `reset` | Restores `:depth-reached` and `:halted?` to their initial values for re-runs. |

## DOM mirrors

| Element | What it tells a spec |
|---|---|
| `depth-reached` | `0` after a halt — the atomic rollback restored the pre-drain snapshot. |
| `halted` | `true` after the error-emit substrate fires the `:rf.error/drain-depth-exceeded` category. Allows a Playwright spec to assert on the halt without scraping the ring buffer. |
| `drain-depth-mirror` | The current ceiling — useful for confirming the input edit propagated to the frame's meta. |

## Why a configurable ceiling

The framework default is 100. A spec asserting on the halt observability
shape (single error event, rollback, second drain runs cleanly) only
needs enough depth to prove the runtime ran the cascade more than once
before halting. Default 25 keeps the trace stream legible while still
producing 25 `:event/dispatched` traces before the halt fires; dialling
to 5 keeps specs sub-second.

## What's deliberately *missing*

- **No `:on-error` policy.** The default `:no-recovery` recovery is the
  contract under test; an `:on-error` override would mask the halt.
- **No partial-cascade error injection.** The cascade goes from clean
  start to depth-exceeded halt with no other errors — keeps the trace
  stream's halt event identifiable in one slot.
- **No `dispatch-later` on the recursion site.** A `:dispatch-later`
  recursion would put each child on a fresh drain (timer fires after
  the parent settles); only synchronous `[:dispatch ...]` inside `:fx`
  exercises the depth ceiling within a single drain.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- **Partial epoch record (drain-halt) shows up with non-`:ok` outcome
  (rf2-v0jwt)** — the load-bearing scenario this surface unblocks. The
  halt produces an epoch record with `:outcome :halted-depth`; Causa's
  trace panel surfaces the partial cascade with the halt category
  highlighted.
- `:rf.error/*` events highlighted in trace stream — the
  `:rf.error/drain-depth-exceeded` row fires once per Start click.
- Trace panel grows on subsequent dispatch — the cascade produces N
  `:event/dispatched` traces (N = ceiling) before halting.

**Cross-cutting (6)**:
- **Drain-depth-exceeded produces app-db rollback + `:halted-depth`
  epoch record (rf2-v0jwt)** — the load-bearing scenario. The DOM
  mirror at `depth-reached=0` after Start is positive rollback
  evidence; the `:halted-depth` epoch outcome is the contract on the
  ring-buffer side.

**Story (18)**:
- Recorder captures click → records `:play` → replays identically —
  the Start click is deterministic (the handler is pure; the ceiling
  is data). Replay reproduces the same halt shape.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/drain-depth-trigger
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/drain-depth-trigger`; output
lands in `implementation/out/testbeds/drain-depth-trigger/`.

## Cross-references

- [`spec/002-Frames.md` §Run-to-completion rule 3](../../spec/002-Frames.md) — the depth-bounded drain contract this surface exercises.
- [`spec/002-Frames.md` §`:drain-depth`](../../spec/002-Frames.md) — the per-frame ceiling knob this surface re-registers on change.
- [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md) — the `:rf.error/drain-depth-exceeded` row this surface fires.
- [`spec/009-Instrumentation.md` §What IS available in production](../../spec/009-Instrumentation.md) — the always-on error-emit substrate the in-app listener attaches to.
- [`spec/Spec-Schemas.md` §`:rf/epoch-record` Outcomes](../../spec/Spec-Schemas.md) — the `:halted-depth` outcome key consumers assert against.
