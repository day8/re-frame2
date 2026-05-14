# `testbeds/schema-violation`

A four-button Reagent app where every click triggers a
`:rf.error/schema-validation-failure` trace at a distinct check-point in
the per-event validation order. The `:where` discriminator names which
of the five check-points fired; each `:where` carries a different
`:recovery` keyword.

| Button | `data-testid` | `:where` | Recovery (per [spec/010 §Per-step recovery](../../spec/010-Schemas.md)) | Observable diff in app-db |
|---|---|---|---|---|
| A · :where :app-db | `violate-app-db` | `:app-db` | `:rollback? true`, `:recovery :no-recovery`. The `:db` effect is rolled back to its pre-handler value; flows do NOT evaluate and `:fx` does NOT walk for this dispatch. Downstream queued events still drain. | `[:auth :token]` stays at `"seed-token"`. The handler tried to commit `42` (an int) at a slot whose registered schema demands `:string`. |
| B · :where :event | `violate-event` | `:event` | `:no-recovery` — handler not invoked; downstream queue continues. | `[:click-count :event]` stays at `0`. The handler's `:spec` is `[:cat [:= ::violate-event] pos-int?]`; the button dispatches it with the string `"not-a-number"`. |
| C · :where :cofx | `violate-cofx` | `:cofx` | `:no-recovery` — handler not invoked; downstream queue continues. | `[:click-count :cofx]` stays at `0`. The cofx's `:spec` is `pos-int?`; the cofx body deliberately injects `-1`. |
| D · :where :fx-args | `violate-fx-args` | `:fx-args` | `:recovery :skipped` — the offending fx is skipped; sibling fx continue. The handler's `:db` already committed. | `[:click-count :fx]` increments per click (the handler's `:db` ran). The fx body never ran — its `:spec` (`[:map [:url :string]]`) rejected the vector args. |

## Trace shape per click (per [spec/009 §Error contract](../../spec/009-Instrumentation.md))

Every Button's click emits one (and only one) trace of the form:

```clojure
{:operation :rf.error/schema-validation-failure
 :op-type   :error
 :tags      {:where      <:event | :cofx | :app-db | :fx-args>
             :path       [...]      ;; structural — the failing slot's path
             :value      <bad>      ;; the rejected value (redacted if :sensitive?)
             :explain    <Malli explanation>
             :failing-id <reg-* id> ;; the handler/cofx/fx that owns the failure
             :rollback?  <true only for :app-db>
             :recovery   <:no-recovery | :skipped>}}
```

For `:where :app-db`, the trace additionally carries `:registered-path`
(the `reg-app-schema` root) and the `:path` is the **failing leaf**
(the registered root concat'd with the Malli explainer's value-navigation
suffix). Consumers that want the registration anchor reach
`(:registered-path tags)`; consumers that want the failing slot reach
`(:path tags)`.

## What's deliberately *missing*

- No `:on-error` policy fn — the default per-`:where` recovery is what
  consumers verify against.
- No `:rf/validate-at-boundary` interceptor — that interceptor is for
  production-mode schema enforcement on untrusted-input handlers, not
  for the dev-mode validation surfaces this testbed exercises.
- No `:sensitive?` slots in the schemas. The privacy/redaction surface
  is exercised by the (Tier 3) `sensitive_dispatcher/` testbed; this
  testbed keeps the values visible so consumers can assert the `:value`
  tag carries the verbatim offending value.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- Schema-validation-failure trace + `:rollback?` flag visible — Button A surfaces the rollback path; Buttons B/C/D verify the trace shape without `:rollback?`.
- `:rf.error/*` events highlighted in trace stream — `:rf.error/schema-validation-failure` is one of the most common ops in the dev-mode trace surface.
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
npm run test:examples
```

The shadow-cljs build id is `testbeds/schema-violation`; output lands
in `implementation/out/testbeds/schema-violation/`.

## Cross-references

- [`spec/010-Schemas.md` §Per-step recovery](../../spec/010-Schemas.md) — the table this testbed walks one button per row from.
- [`spec/010-Schemas.md` §Validation order](../../spec/010-Schemas.md) — the six-step order; this testbed exercises steps 1, 2, 4, 5.
- [`spec/009-Instrumentation.md` §Error contract](../../spec/009-Instrumentation.md) — the `:rf.error/schema-validation-failure` row in the catalogue.
- [`spec/Spec-Schemas.md` §Per-category `:tags` schemas](../../spec/Spec-Schemas.md) — the per-`:where` `:tags` map shape consumers parse.
