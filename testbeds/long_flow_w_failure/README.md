# `testbeds/long-flow-w-failure`

A multi-second cascade of `app-db` writes that drive a three-flow
topology, with a configurable mid-flow failure injection. The
single Start click produces a visible ~5-second stream of
`:rf.flow/computed` / `:rf.flow/failed` / `:rf.error/flow-eval-exception`
traces that a consumer (Xray, Story, re-frame2-pair-mcp) reads to verify the
flow-failure **atomicity** contract from
[`spec/013-Flows.md` §Failure semantics](../../spec/013-Flows.md)
(rf2-u0zz5): a flow throw is a pre-install throw, so it **aborts the
whole event** — no `:db` install, `app-db` unchanged, no
`:rf.event/db-changed`, `:fx` skipped, **no partial commit**.

## The cascade

`Start` schedules N ticks at 250ms intervals via `:dispatch-later`.
Each tick bumps `:input` by one; every bump fires a flows pass that
recomputes all three flows in topo order. Total cascade time =
`:total-ticks × 250ms` (default 5 seconds).

```
:flow-a  :inputs [[:input]]                  :output (* 2 input)        :path [:a-result]
:flow-b  :inputs [[:input] [:a-result]]      :output (* 3 input)        :path [:b-result]   ← throws when input ≥ :fail-at
:flow-c  :inputs [[:a-result] [:b-result]]   :output (+ a b)            :path [:c-result]
```

`:flow-b` lists `[:a-result]` as a topology-pin input only — Spec 013
§Topological sort uses path-prefix overlap to fix evaluation order,
so the declaration forces `:flow-a → :flow-b`. Without it the two
flows are independent (both read `[:input]`, write disjoint paths)
and Kahn's algorithm picks an unspecified order between them. Pinning
the order makes `:flow-a` the demonstrable "ran-then-discarded" prior
flow (its `:rf.flow/computed` trace fires before the throw, but its
write is discarded by the abort) and `:flow-c` the "never-ran"
downstream flow. `:flow-b`'s `:output` ignores the `a-result` value;
its math is still `(* 3 input)`.

## The atomicity contract this surface exercises

Per [spec/013 §Failure semantics](../../spec/013-Flows.md), when
`:flow-b`'s `:output` throws on a recompute, the whole event aborts.
The DOM mirror at the bottom of the surface makes the effect
human-readable:

| Behaviour | What it says | Where to look |
|---|---|---|
| No install — `app-db` unchanged | The pending `:db` effect (the handler's `:input` bump AND `:flow-a`'s `:a-result` write) is discarded; nothing commits on the failing tick. No partial commit. | `input`, `a-result`, `b-result`, `c-result` all FREEZE at the last clean tick (tick `:fail-at − 1`). |
| `:flow-a` ran but its write was discarded | `:flow-a` computes first and emits `:rf.flow/computed` — but because a LATER flow throws, the event aborts and `:a-result` never lands. | `a-result` does NOT advance on the failing tick; the trace stream still shows `:rf.flow/computed` for `::flow-a`. |
| Failing flow's output is not written; `last-inputs` rolled back | `:flow-b`'s recompute throws; `:b-result` is not updated; `last-inputs` is rolled back so every flow re-attempts next drain. | `b-result` frozen; subsequent ticks re-throw. |
| Cascade halts at the failing flow | `:flow-c` does NOT run on the drain where `:flow-b` throws. | `c-result` frozen from the tick that lands `:input == :fail-at`. |
| No `:rf.event/db-changed`; exception surfaces as `:rf.error/flow-eval-exception` | The aborted tick emits NO `:rf.event/db-changed`. The per-flow `:rf.flow/failed` trace fires first (`:flow-id ::flow-b` under `:tags`); the cascade-level `:rf.error/flow-eval-exception` fires when the router catches the throw. | The trace stream after `:fail-at` shows `[:rf.flow/failed, :rf.error/flow-eval-exception]` pairs and NO `:rf.event/db-changed` on every subsequent tick. |

## Controls

| Control | `data-testid` | What it does |
|---|---|---|
| `Fail at tick` | `fail-at` | The tick index at which `:flow-b` begins throwing. Default 5; the cascade is intact for ticks 1..4, throws on ticks 5..N. |
| `Total ticks` | `total-ticks` | How many ticks `Start` schedules. Default 20 (5 seconds at 250ms/tick). A spec testing just the rules can dial to 6 for a faster run. |
| `Start cascade` | `start` | Pre-schedules every tick at boot via `:dispatch-later`. The cascade is data, not a recursive timer chain. |
| `Reset` | `reset` | Restores the surface to its initial state for re-runs. |

## DOM mirrors

The surface mirrors `:input` / `:a-result` / `:b-result` / `:c-result`
in the DOM so a consumer can assert against the contract without
needing a recorder. After Start with defaults (fail-at=5,
total-ticks=20):

- `input`: advances 1, 2, 3, 4, then FREEZES at 4 (ticks 5..20 abort
  before install).
- `a-result`: advances 0, 2, 4, 6, 8, then FREEZES at 8. (No install
  on the failing tick — even `:flow-a`'s prior write is discarded.)
- `b-result`: advances 0, 3, 6, 9, 12, then FREEZES at 12.
- `c-result`: advances 0, 5, 10, 15, 20, then FREEZES at 20.

A consumer that reads the trace stream can assert:
- 4 committed ticks → 4 `:rf.event/db-changed` (ticks 1..4); NONE for
  ticks 5..20 (each aborts).
- `:rf.flow/computed` traces for `::flow-a` on every tick it RUNS
  (ticks 1..20 — it runs even on aborted ticks; only its WRITE is
  discarded).
- 4 `:rf.flow/computed` traces for `::flow-b` (ticks 1..4).
- 16 `:rf.flow/failed` traces for `::flow-b` (ticks 5..20).
- 16 `:rf.error/flow-eval-exception` traces (one per `:rf.flow/failed`).
- 4 `:rf.flow/computed` traces for `::flow-c` (ticks 1..4); 0 after.

The exact trace order per failing drain: `:flow-a` computed →
`:flow-b` failed → `:rf.error/flow-eval-exception`, with NO
`:rf.event/db-changed`.

## Why a multi-second window

The atomicity contract is a per-drain property — observable on any
single throw. But a consumer's UI (trace panel, recorder, MCP wire)
under stress tests differently when 60+ flow traces stream past in
five seconds vs. when one click produces 3 traces. The default
total-ticks=20 keeps the trace volume realistic without blowing
ring-buffer budgets (the Spec 009 200-row default holds 60 per-tick
traces fine).

A consumer that tests the contract at minimum cost dials total-ticks
down to 6 and fail-at to 4 — the cascade completes in 1.5 seconds
and produces enough trace shape to assert the whole abort signature.

## What's deliberately *missing*

- **No `:on-error` policy.** The atomicity contract is what the
  default recovery does; an `:on-error` override would mask it.
- **No `:rf.fx/clear-flow` on the failing flow.** Clearing
  `::flow-b` mid-cascade is a separate contract; this surface
  stays on the canonical "let it keep re-throwing" path so the
  abort evidence accumulates over multiple ticks.
- **No retry logic on `:tick`.** Every tick fires unconditionally;
  the failing flow's re-throw is what produces the abort
  evidence — not retry orchestration in user code.
- **No `:flow-b` recovery on `Fail at tick > total-ticks`.** Setting
  fail-at to a value higher than total-ticks gives a clean cascade
  with zero failures — useful as a control case for verifying the
  trace shape when no failure injection is active.

## Test scenarios from rf2-fe84r this surface enables

**Xray (26)**:
- Trace panel grows on subsequent dispatch (rf2-1barg regression
  — gold standard) — exercised 20× in one Start click.
- `:rf.error/*` events highlighted in trace stream — exercised
  16× per default run (one per failing tick).
- ≤200-row budget enforced under 1000-event ring saturation —
  dial `total-ticks` up to 333+ to produce 1000+ flow traces in
  one cascade; verify ring-buffer truncation against the budget.

**Cross-cutting (6)**:
- **Flow `:rf.flow/failed` shows atomicity failure semantics
  (rf2-u0zz5)** — the load-bearing scenario this surface unblocks.
  The abort signature (no install, frozen `app-db`, no
  `:rf.event/db-changed`, `:fx` skipped) has a DOM mirror + a
  deterministic trace shape a spec can assert against; the 5-second
  cascade gives it time to accumulate evidence beyond a single drain.

**Story (18)**:
- Recorder captures click → records `:play` → replays identically —
  the Start click is deterministic (timer schedule is data; flow
  recomputes are pure given the same `:input` sequence). Replay
  reproduces the same per-tick trace stream.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/long-flow-w-failure
# Or via the orchestrator:
npm run test:adapter-smokes
```

The shadow-cljs build id is `testbeds/long-flow-w-failure`; output
lands in `implementation/out/testbeds/long-flow-w-failure/`.

## Cross-references

- [`spec/013-Flows.md` §Failure semantics](../../spec/013-Flows.md) — the atomicity contract this surface exists to exercise.
- [`spec/013-Flows.md` §Flow tracing](../../spec/013-Flows.md) — the `:rf.flow/*` op taxonomy a consumer's trace panel filters against.
- [`spec/009-Instrumentation.md` §Error contract](../../spec/009-Instrumentation.md) — the `:rf.error/flow-eval-exception` shape a flow throw produces.
- [`spec/002-Frames.md` §Cascade propagation](../../spec/002-Frames.md) — the `:dispatch-later` frame-capture contract every scheduled tick relies on.
