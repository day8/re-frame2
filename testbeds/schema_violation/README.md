# `testbeds/schema-violation`

A four-button Reagent app. Buttons A, B, D each trigger a
`:rf.error/schema-validation-failure` trace at a distinct `:where`
check-point in the per-event validation order; **Button C triggers the
separate, halting `:rf.error/cofx-value-invalid` hard error** (a
recordable coeffect's value failing its `reg-cofx` `:schema`). Each
surface carries a different `:recovery` keyword.

The `:where :cofx` schema-validation surface was **retired in EP-0017**
(rf2-nkf4l3): a recordable coeffect rides the durable causal record
(epoch ledger, replay, SSR payload, Xray), so a bad value is not a
recoverable dev-only trace — it is `:rf.error/cofx-value-invalid`, a
production hard error that **throws** and halts the run, and it does not
emit `:rf.error/schema-validation-failure` at all (per [spec/010
§Validation order](../../spec/010-Schemas.md) step 2).

| Button | `data-testid` | Category / `:where` | Recovery (per [spec/010 §Per-step recovery](../../spec/010-Schemas.md)) | Observable diff in app-db |
|---|---|---|---|---|
| A · :where :app-db | `violate-app-db` | `:rf.error/schema-validation-failure` `:where :app-db` | `:rollback? true`, `:recovery :no-recovery`. The `:db` effect is rolled back to its pre-handler value; flows do NOT evaluate and `:fx` does NOT walk for this dispatch. Downstream queued events still drain. | `[:auth :token]` stays at `"seed-token"`. The handler tried to commit `42` (an int) at a slot whose registered schema demands `:string`. |
| B · :where :event | `violate-event` | `:rf.error/schema-validation-failure` `:where :event` | `:no-recovery` — handler not invoked; downstream queue continues. | `[:click-count :event]` stays at `0`. The handler's `:schema` is `[:cat [:= ::violate-event] pos-int?]`; the button dispatches it with the string `"not-a-number"`. |
| C · cofx-value-invalid | `violate-cofx` | `:rf.error/cofx-value-invalid` (**throws**) | `:recovery :no-recovery` — a **halting production hard error**. The value is validated as the cofx is satisfied, before it folds into the handler context; on mismatch the runtime emits the error record and THROWS. The handler NEVER runs and the run halts (not the "skip handler, queue continues" recovery of the `:where` surfaces). The button uses `dispatch-sync*` + a `try`/`catch` so the throw is caught at the click boundary. | `[:click-count :cofx]` stays at `0`. The cofx's `:schema` is `pos-int?`; the value-returning supplier (declared via `:rf.cofx/requires`, EP-0017) deliberately returns `-1`. |
| D · :where :fx-args | `violate-fx-args` | `:rf.error/schema-validation-failure` `:where :fx-args` | `:recovery :skipped` — the offending fx is skipped; sibling fx continue. The handler's `:db` already committed. | `[:click-count :fx]` increments per click (the handler's `:db` ran). The fx body never ran — its `:schema` (`[:map [:url :string]]`) rejected the vector args. |

## Trace shape per click (per [spec/009 §Error contract](../../spec/009-Instrumentation.md))

Buttons A, B, D each emit one (and only one) trace of the form:

```clojure
{:operation :rf.error/schema-validation-failure
 :op-type   :error
 :tags      {:where      <:event | :app-db | :fx-args>
             :path       [...]      ;; structural — the failing slot's path
             :value      <bad>      ;; the rejected value (redacted if :sensitive?)
             :explain    <Malli explanation>
             :failing-id <reg-* id> ;; the handler/fx that owns the failure
             :rollback?  <true only for :app-db>
             :recovery   <:no-recovery | :skipped>}}
```

Button C emits the separate hard-error record instead — it does NOT ride
`:rf.error/schema-validation-failure`:

```clojure
{:operation :rf.error/cofx-value-invalid
 :op-type   :error
 :tags      {:rf.cofx/id  ::bad-counter   ;; the failing recordable cofx
             :failing-id  ::violate-cofx  ;; the event whose satisfy step threw
             :recovery    :no-recovery}}  ;; halting — the run does not settle
```

For `:where :app-db`, the trace additionally carries `:registered-path`
(the `reg-app-schema` root) and the `:path` is the **failing leaf**
(the registered root concat'd with the Malli explainer's value-navigation
suffix). Consumers that want the registration anchor reach
`(:registered-path tags)`; consumers that want the failing slot reach
`(:path tags)`.

## What's deliberately *missing*

- No app-steering recovery policy (the per-frame `:on-error` recovery
  policy was removed per rf2-hiqtk8) — the framework's default per-`:where`
  recovery is what consumers verify against.
- No `:rf.schema/at-boundary` interceptor — that interceptor is for
  production-mode schema enforcement on untrusted-input handlers, not
  for the dev-mode validation surfaces this testbed exercises.
- No `:sensitive?` slots in the schemas. A `:sensitive?` slot prop would
  redact the offending `:value` in this very trace (validation-failure-trace
  redaction is the one schema-prop axis that survives EP-0025); this testbed
  keeps the values visible so consumers can assert the `:value` tag carries
  the verbatim offending value.

## Test scenarios from rf2-fe84r this surface enables

**Xray (26)**:
- Schema-validation-failure trace + `:rollback?` flag visible — Button A surfaces the rollback path; Buttons B/D verify the trace shape without `:rollback?`. Button C surfaces the distinct `:rf.error/cofx-value-invalid` hard-error record instead.
- `:rf.error/*` events highlighted in trace stream — `:rf.error/schema-validation-failure` (A/B/D) and `:rf.error/cofx-value-invalid` (C) are both surfaced in the dev-mode trace stream.
- Click-to-source from trace event lands on source-coord line — each `reg-*` registration captures source coords; the failing-id in the trace links back to its declaration.

**Cross-cutting (6)**:
- Schema-validation-failure produces app-db rollback + `:rollback?` flag (rf2-hrqvg covers the flow analogue; this surface is the app-db analogue).
- Subscribe → re-render → trace ordering preserved — Button A's `[:auth :token]` sub stays at the pre-handler value across the failed dispatch, which is the load-bearing rollback observability claim.

**Story (18)**:
- Recorder captures click → records `:play` → replays identically. Schema violation should replay deterministically (the inputs are pure).
- `:rf.assert/*` pass/fail with structured output — assertions over the four `:where` discriminators live in tool-side testbeds.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/schema-violation
# Or full orchestrator:
npm run test:adapter-smokes
```

The shadow-cljs build id is `testbeds/schema-violation`; output lands
in `implementation/out/testbeds/schema-violation/`.

## Cross-references

- [`spec/010-Schemas.md` §Per-step recovery](../../spec/010-Schemas.md) — the table this testbed walks one button per row from.
- [`spec/010-Schemas.md` §Validation order](../../spec/010-Schemas.md) — the six-step order; this testbed exercises steps 1, 2, 4, 5.
- [`spec/009-Instrumentation.md` §Error contract](../../spec/009-Instrumentation.md) — the `:rf.error/schema-validation-failure` row in the catalogue.
- [`spec/Spec-Schemas.md` §Per-category `:tags` schemas](../../spec/Spec-Schemas.md) — the per-`:where` `:tags` map shape consumers parse.
