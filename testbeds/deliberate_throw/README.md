# `testbeds/deliberate-throw`

A four-button Reagent app that throws on every click. Each button is
wired to a different layer of the six-domino cascade so a consumer
observes exactly one `:rf.error/*` category per click.

| Button | `data-testid` | Trigger | Category emitted | Recovery (per [spec/009](../../spec/009-Instrumentation.md), [spec/010](../../spec/010-Schemas.md)) |
|---|---|---|---|---|
| A · handler-exception | `throw-handler` | `(throw ...)` inside the event handler body. | `:rf.error/handler-exception` | `:no-recovery` — the failing handler's `:db` / `:fx` are suppressed; downstream queue continues. No epoch record is committed under the `:halted-handler-exception` shape — the error surfaces as a trace under `:trace-events` on the drain's `:ok` epoch record (per [Spec-Schemas §`:rf/epoch-record`](../../spec/Spec-Schemas.md)). |
| B · fx-handler-exception | `throw-fx` | `(throw ...)` inside a registered fx body. The handler returned cleanly; the throw lands during the fx walk. | `:rf.error/fx-handler-exception` | `:no-recovery` — the offending fx is skipped; sibling fx in the same `:fx` vector continue. The handler's `:db` already committed. |
| C · flow-eval-exception | `throw-flow` | `(throw ...)` inside a registered flow's `:output` fn. The handler bumps `:flow-input`; the runtime's post-handler flows pass walks the flow and re-throws after `:rf.flow/failed` fires. | `:rf.flow/failed` first, then `:rf.error/flow-eval-exception` | All-or-nothing pre-install abort (per [spec/013 §Failure semantics](../../spec/013-Flows.md)): NO install — `app-db` unchanged, no partial commit (neither the handler's `:db` nor any prior successful flow's write lands), no `:rf.event/db-changed`, `:fx` skipped. The draining frame's `last-inputs` rolls back (every flow re-attempts next drain); cascade halts at the router's outer catch. |
| D · machine-action-exception | `throw-machine` | `(throw ...)` inside a machine action body. The transition is `{:target :idle :action :throw}`; the action body is the throw site. | `:rf.error/machine-action-exception` | `:no-recovery` — snapshot does not commit (pre-action `[:rf/machines <id>]` slice preserved), accumulated `:fx` from earlier slots dropped, `:always` does not fire on the failed cascade. Distinct from `:rf.error/handler-exception` — the machine layer catches and emits the machine-scoped category. |

## Why the four sites are distinct

Each layer's throw produces a different trace shape with different
recovery semantics. A trace panel that conflated them — or a recorder
that captured a flow throw but missed the per-flow `:rf.flow/failed`
attribution preceding it — would fail to surface the load-bearing fact
about where the cascade halted. The four buttons are the minimum that
lets a consumer verify it discriminates.

## What's deliberately *missing*

- No `try` / `catch` around any trigger site. The throw must propagate
  to the framework boundary; that's where the structured error event is
  emitted.
- Recovery is the framework's typed per-category default — the
  `:recovery` consumers test against. There is no app-steering recovery
  policy (the per-frame `:on-error` recovery policy was removed per
  rf2-hiqtk8); observe errors via `register-error-listener!`.
- No epoch recorder wired into the app. The recorder is the consumer's
  concern — `tools/xray/testbeds/deliberate-throw-recorder/spec.cjs`
  (when filed) will register the listener itself.

## Test scenarios from rf2-fe84r this surface enables

**Xray (26)**:
- `:rf.error/*` events highlighted in trace stream
- Handler exception surfaces as `:effects` outcome `:error` per epoch record
- Partial epoch record (drain-halt) shows up with non-`:ok` outcome (rf2-v0jwt) — via Button D (machine action; the snapshot non-commit is the cleanest drain-halt observable)
- Click-to-source from trace event lands on source-coord line — Buttons A/B/C/D each register their handler/fx/flow/machine via the four canonical `reg-*` shapes, so every flavour of source-coord capture exercises here.

**Cross-cutting (6)**:
- Deliberately-throwing handler surfaces structured trace in BOTH Xray + Story `:play`
- Flow `:rf.flow/failed` shows atomicity-contract failure semantics per Spec 013 §Failure semantics (rf2-hrqvg) — Button C
- State-machine transition cascade shows `:rf.machine/*` events — Button D's pre-throw transition fires the `:rf.machine/transition` trace before the action throws

**Story (18)**:
- Recorder captures click → records `:play` → replays identically — Buttons A/B/C/D should all replay deterministically (the throws are pure)
- `:rf.assert/*` pass/fail with structured output — assertions wrapped around each button's expected `:rf.error/*` shape live in `tools/story/testbeds/<scenario>/spec.cjs` (filed after this surface lands)

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/deliberate-throw
# Then open http://localhost:9630 to find the build's dev URL,
# or run the full examples orchestrator:
npm run test:adapter-smokes
```

The shadow-cljs build id is `testbeds/deliberate-throw`; output lands
in `implementation/out/testbeds/deliberate-throw/`. The example
orchestrator stages `index.html` next to `main.js` and serves it under
`/testbeds/deliberate-throw/` on port 8030.

## Cross-references

- [`spec/009-Instrumentation.md` §Error contract](../../spec/009-Instrumentation.md) — the `:rf.error/*` catalogue this surface fires four entries from.
- [`spec/005-StateMachines.md` §Errors](../../spec/005-StateMachines.md) — the machine-action-exception contract.
- [`spec/013-Flows.md` §Failure semantics](../../spec/013-Flows.md) — the flow-failure atomicity contract (rf2-hrqvg).
- [`spec/Spec-Schemas.md` §`:rf/epoch-record`](../../spec/Spec-Schemas.md) — the epoch-record outcome taxonomy a consumer's recorder reads.
