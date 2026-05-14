# `testbeds/long-flow-w-failure`

A multi-second cascade of `app-db` writes that drive a three-flow
topology, with a configurable mid-flow failure injection. The
single Start click produces a visible ~5-second stream of
`:rf.flow/computed` / `:rf.flow/failed` / `:rf.error/flow-eval-exception`
traces that a consumer (Causa, Story, pair2-mcp) reads to verify the
four-rule flow-failure contract from
[`spec/013-Flows.md` §Failure semantics](../../spec/013-Flows.md)
(rf2-hrqvg).

## The cascade

`Start` schedules N ticks at 250ms intervals via `:dispatch-later`.
Each tick bumps `:input` by one; every bump fires a flows pass that
recomputes all three flows in topo order. Total cascade time =
`:total-ticks × 250ms` (default 5 seconds).

```
:flow-a  :inputs [[:input]]                  :output (* 2 input)        :path [:a-result]
:flow-b  :inputs [[:input]]                  :output (* 3 input)        :path [:b-result]   ← throws when input ≥ :fail-at
:flow-c  :inputs [[:a-result] [:b-result]]   :output (+ a b)            :path [:c-result]
```

## The four-rule contract this surface exercises

Per [spec/013 §Failure semantics](../../spec/013-Flows.md), when
`:flow-b`'s `:output` throws on a recompute, the runtime applies
four rules atomically. The DOM mirror at the bottom of the surface
makes each rule's effect human-readable:

| Rule | What it says | Where to look |
|---|---|---|
| 1 — prior writes preserved | `:flow-a` computes first; its output IS flushed to `[:a-result]` BEFORE `:flow-b`'s throw propagates. | `a-result` advances on every tick, including the failing one. |
| 2 — failing flow's output is not written | `:flow-b`'s recompute throws; `:b-result` is not updated; `:flow-b`'s `last-inputs` is NOT advanced — the flow re-attempts on the next tick. | `b-result` stops advancing at `(* 3 (dec :fail-at))`; subsequent ticks re-throw without writing. |
| 3 — cascade halts at the failing flow | `:flow-c` does NOT run on the drain where `:flow-b` throws. | `c-result` stops advancing at the tick that lands `:input == :fail-at`. |
| 4 — exception surfaces as `:rf.error/flow-eval-exception` | The per-flow `:rf.flow/failed` trace fires first (with `:flow-id ::flow-b` under `:tags`); the cascade-level `:rf.error/flow-eval-exception` fires when the router catches the propagated throw. | The trace stream after `:fail-at` shows pairs of `[:rf.flow/failed, :rf.error/flow-eval-exception]` on every subsequent tick. |

## Controls

| Control | `data-testid` | What it does |
|---|---|---|
| `Fail at tick` | `fail-at` | The tick index at which `:flow-b` begins throwing. Default 5; the cascade is intact for ticks 1..4, throws on ticks 5..N. |
| `Total ticks` | `total-ticks` | How many ticks `Start` schedules. Default 20 (5 seconds at 250ms/tick). A spec testing just the rules can dial to 6 for a faster run. |
| `Start cascade` | `start` | Pre-schedules every tick at boot via `:dispatch-later`. The cascade is data, not a recursive timer chain. |
| `Reset` | `reset` | Restores the surface to its initial state for re-runs. |

## DOM mirrors

The surface mirrors `:a-result` / `:b-result` / `:c-result` in the
DOM so a Playwright spec can assert against the rules without
needing a recorder. After Start with defaults (fail-at=5,
total-ticks=20):

- `a-result`: keeps advancing 0, 2, 4, 6, … 40. (Rule 1.)
- `b-result`: advances 0, 3, 6, 9, 12, then stops. (Rule 2.)
- `c-result`: advances 0, 5, 10, 15, 20, then stops. (Rule 3.)

A consumer that reads the trace stream can assert:
- 20 `:rf.flow/computed` traces for `::flow-a` (one per tick).
- 4 `:rf.flow/computed` traces for `::flow-b` (ticks 1..4).
- 16 `:rf.flow/failed` traces for `::flow-b` (ticks 5..20).
- 16 `:rf.error/flow-eval-exception` traces (one per `:rf.flow/failed`).
- 4 `:rf.flow/computed` traces for `::flow-c` (ticks 1..4).
- 0 `:rf.flow/computed` traces for `::flow-c` after tick 5.

The exact trace order per drain: `:flow-a` computed → `:flow-b`
failed → `:flow-error/flow-eval-exception`.

## Why a multi-second window

The four-rule contract is a per-drain property — observable on any
single throw. But a consumer's UI (trace panel, recorder, MCP wire)
under stress tests differently when 60+ flow traces stream past in
five seconds vs. when one click produces 3 traces. The default
total-ticks=20 keeps the trace volume realistic without blowing
ring-buffer budgets (the Spec 009 200-row default holds 60 per-tick
traces fine).

A consumer that tests the rules at minimum cost dials total-ticks
down to 6 and fail-at to 4 — the cascade completes in 1.5 seconds
and produces enough trace shape to assert every rule.

## What's deliberately *missing*

- **No `:on-error` policy.** The four-rule contract is what the
  default recovery does; an `:on-error` override would mask it.
- **No `:rf.fx/clear-flow` on the failing flow.** Clearing
  `::flow-b` mid-cascade is a separate contract; this surface
  stays on the canonical "let it keep re-throwing" path so the
  rule-2 evidence accumulates over multiple ticks.
- **No retry logic on `:tick`.** Every tick fires unconditionally;
  the failing flow's re-throw is what produces rule 2's
  evidence — not retry orchestration in user code.
- **No `:flow-b` recovery on `Fail at tick > total-ticks`.** Setting
  fail-at to a value higher than total-ticks gives a clean cascade
  with zero failures — useful as a control case for verifying the
  trace shape when no failure injection is active.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- Trace panel grows on subsequent dispatch (rf2-1barg regression
  — gold standard) — exercised 20× in one Start click.
- `:rf.error/*` events highlighted in trace stream — exercised
  16× per default run (one per failing tick).
- ≤200-row budget enforced under 1000-event ring saturation —
  dial `total-ticks` up to 333+ to produce 1000+ flow traces in
  one cascade; verify ring-buffer truncation against the budget.

**Cross-cutting (6)**:
- **Flow `:rf.flow/failed` shows four-rule failure semantics
  (rf2-hrqvg)** — the load-bearing scenario this surface unblocks.
  Each of the four rules has a DOM mirror + a deterministic trace
  shape a spec can assert against; the 5-second cascade gives the
  rules time to accumulate evidence beyond a single drain.

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
npm run test:examples
```

The shadow-cljs build id is `testbeds/long-flow-w-failure`; output
lands in `implementation/out/testbeds/long-flow-w-failure/`.

## Cross-references

- [`spec/013-Flows.md` §Failure semantics](../../spec/013-Flows.md) — the four-rule contract this surface exists to exercise.
- [`spec/013-Flows.md` §Flow tracing](../../spec/013-Flows.md) — the `:rf.flow/*` op taxonomy a consumer's trace panel filters against.
- [`spec/009-Instrumentation.md` §Error contract](../../spec/009-Instrumentation.md) — the `:rf.error/flow-eval-exception` shape rule 4 produces.
- [`spec/002-Frames.md` §Cascade propagation](../../spec/002-Frames.md) — the `:dispatch-later` frame-capture contract every scheduled tick relies on.
