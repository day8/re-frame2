# `testbeds/drain-depth-trigger`

A single Reagent-mounted handler whose `:fx` recursively dispatches
itself. The runtime's run-to-completion drain halts the cascade when
the frame's `:drain-depth` ceiling is reached. The atomicity unit is
the **event, not the drain** (per [spec/002 §Run-to-completion rule 3]
/ [spec/002 §Drain versus event — the epoch unit]): every settled
`::recurse` event kept its own durable `:db` write and `:ok` epoch —
there is **no whole-drain rollback** and no pre-drain snapshot. A
consumer (Xray, Story, re-frame2-pair-mcp) observes the
`:rf.error/drain-depth-exceeded` shape (carrying `:rollback? false`),
the durable per-event writes, and the `:halted-depth` epoch outcome
(rf2-v0jwt).

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

- `:depth-reached` reads back to the **ceiling** (the count of settled
  `::recurse` events), NOT `0` — per-event durability evidence. Each
  event kept its own `:db` write; there is no whole-drain rollback.
- The frame's epoch record for the **halting** event carries outcome
  `:halted-depth` (per [Spec-Schemas §`:rf/epoch-record` Outcomes],
  rf2-v0jwt). Because that event never ran, its `:db-before` and
  `:db-after` both equal the durable last-settled `app-db`. Consumers
  read this record off `rf/epoch-history`.

## Controls

| Control | `data-testid` | What it does |
|---|---|---|
| `Drain depth ceiling` | `drain-depth` | The frame's `:drain-depth`. Re-registers the default frame with the new value (a surgical update per [spec/002 §Surgical update] — only the ceiling changes; in-flight events and app-db are not reset). Default 25. |
| `Start (recurse — halts at depth)` | `start` | Dispatches `[::recurse]`. The handler recurses; the drain halts. |
| `Reset` | `reset` | Restores `:depth-reached` to `0` for re-runs. |

## DOM mirrors

| Element | What it tells a spec |
|---|---|
| `depth-reached` | Reads back to the **ceiling** after a halt (the count of settled events) — the durable per-event writes are kept; there is no whole-drain rollback. |
| `drain-depth-mirror` | The current ceiling — useful for confirming the input edit propagated to the frame's meta. |

The halt itself is observable on the framework side via `rf/epoch-history`
(the trailing `:halted-depth` epoch record for the halting event, see
[Spec-Schemas §`:rf/epoch-record` Outcomes]) — no DOM mirror is required
for the halt observable.

## Why a configurable ceiling

The framework default is 100. A spec asserting on the halt observability
shape (single error event with `:rollback? false`, durable per-event
writes kept, second drain runs cleanly) only needs enough depth to prove
the runtime ran the cascade more than once before halting. Default 25
keeps the trace stream legible while still producing 25 `:event/dispatched`
traces before the halt fires; dialling to 5 keeps specs sub-second.

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

**Xray (26)**:
- **Partial epoch record (drain-halt) shows up with non-`:ok` outcome
  (rf2-v0jwt)** — the load-bearing scenario this surface unblocks. The
  halt produces an epoch record with `:outcome :halted-depth`; Xray's
  trace panel surfaces the partial cascade with the halt category
  highlighted.
- `:rf.error/*` events highlighted in trace stream — the
  `:rf.error/drain-depth-exceeded` row fires once per Start click.
- Trace panel grows on subsequent dispatch — the cascade produces N
  `:event/dispatched` traces (N = ceiling) before halting.

**Cross-cutting (6)**:
- **Drain-depth-exceeded keeps durable per-event writes + emits a
  `:halted-depth` epoch record (rf2-v0jwt)** — the load-bearing
  scenario. The DOM mirror at `depth-reached=<ceiling>` after Start is
  positive per-event-durability evidence (there is no whole-drain
  rollback); the `:halted-depth` epoch outcome is the contract on the
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
npm run test:adapter-smokes
```

The shadow-cljs build id is `testbeds/drain-depth-trigger`; output
lands in `implementation/out/testbeds/drain-depth-trigger/`.

## Cross-references

- [`spec/002-Frames.md` §Run-to-completion rule 3](../../spec/002-Frames.md) — the depth-bounded drain contract this surface exercises.
- [`spec/002-Frames.md` §`:drain-depth`](../../spec/002-Frames.md) — the per-frame ceiling knob this surface re-registers on change.
- [`spec/009-Instrumentation.md` §Error event catalogue](../../spec/009-Instrumentation.md) — the `:rf.error/drain-depth-exceeded` row this surface fires.
- [`spec/009-Instrumentation.md` §What IS available in production](../../spec/009-Instrumentation.md) — the always-on error-emit substrate the in-app listener attaches to.
- [`spec/Spec-Schemas.md` §`:rf/epoch-record` Outcomes](../../spec/Spec-Schemas.md) — the `:halted-depth` outcome key consumers assert against.
