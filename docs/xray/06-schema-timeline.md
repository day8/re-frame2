# 6. Schema-Violation Timeline

Your app emitted a schema violation. This chapter shows where it appears in current Xray: not in a separate Issues tab, but inline at the event that caused it, with the trace record still available when you need exact details.

## The Old Issues Tab Is Gone

Earlier Xray material described a dedicated Issues tab. That is out of date.

Current Xray surfaces issues in three places:

- the event row is visually marked when the epoch has an issue;
- the Epoch panel shows the failing step in context;
- the Trace tab contains the underlying trace record.

This is a better debugging posture. A schema violation is not a floating notification; it is part of the cascade that produced it.

![Schema validation failure shown inside the Epoch lens](../images/xray/xray-tutorial-schema.png)

## What A Schema Failure Tells You

Schema validation failures identify the boundary that failed. Depending on the surface, that can be:

- event arguments;
- app-db after a handler;
- coeffect data;
- effect arguments;
- subscription return values.

The exact payload lives in the trace record. The useful human question usually lives one level up: which event caused the failure, which step was being validated, and did app-db roll back?

Every failure is the error `:rf.error/schema-validation-failure`, and its `:where` names the boundary. What happens next depends on it:

| `:where` | Checked | What happens | Chip in Epoch |
| --- | --- | --- | --- |
| `:event` | the event vector, before the handler | the handler does not run | Rejected |
| `:app-db` | the new app-db, before it is installed | nothing installs; app-db keeps its value from before the event | Aborted |
| `:machine-data` | a machine's `:data` after a transition | the whole event is rejected, as for `:app-db` | Aborted |
| `:fx-args` | an effect's arguments | that effect is skipped; the others run | Skipped |
| `:sub-return` | a subscription's return value | the subscription returns `nil` | Returned nil |
| `:flow-output` | a flow's output | the value is still written | no-recovery |

These checks run in development builds only; an event registered with `:boundary? true` keeps its event check in production. A coeffect whose recorded value fails its schema is a different error, `:rf.error/cofx-value-invalid`, and it stops the event in every build.

## What The Epoch Tab Shows

The failure appears as a **Schema Violation Error** block inside the step it belongs to: under DISPATCH for the event vector, under the `:db` row for app-db, under the effect row for effect arguments, and under the subscription row for a return value. The block carries the chip above, one sentence saying what happened with a **schema check** link to the schema's source, the `expected:` and `got:` values, and the validator's explanation.

## A Small Example

The standard-epochs testbed includes buttons that deliberately cross invalid schema boundaries. Trigger one and Xray marks the epoch. Open Epoch to see where the cascade failed. Open Trace if you need the raw validation record.

## Why This Matters For Tests

Schema violations should be treated as failures. A final app-db assertion can miss a bug if the runtime rolled back the invalid write and the state looks clean afterward. Xray keeps the violation attached to the epoch so the hidden failure remains visible.

That is the same reason the testing substrate treats schema violations as first-class evidence. The state may recover. The fact that the invalid boundary was crossed still matters.

Story holds a variant to the same rule. It counts a schema failure as part of the run, so the run does not pass unless an `:rf.assert/schema-error` assertion declared that violation as expected. [Schema failures](../story/06-xray-earned-at-failure.md#schema-failures) in the Story guide covers it from that side.

## Static Schemas

Use Static mode's Schemas tab when you want to browse what is registered. That is a different question from "which event violated a schema just now?"

- Dynamic Epoch answers: "where did this failure occur?"
- Dynamic Trace answers: "what was the exact emitted record?"
- Static Schemas answers: "what schemas exist in this app?"
