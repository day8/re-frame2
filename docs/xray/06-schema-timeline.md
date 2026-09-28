# 6. Schema Violations

Your app emitted a schema violation. This chapter shows where it appears in Xray: inline at the event that caused it, with the trace record available when you need exact details.

## Where A Violation Appears

Xray shows a schema violation in three places:

- the event row is washed pink;
- the Epoch tab shows the failure inside the step it belongs to;
- the Trace tab holds the underlying trace record.

A schema violation is part of the cascade that produced it, so Xray shows it there rather than in a separate list. To have Xray open itself when one happens, turn on **Auto-open Xray when an issue is observed** in Settings → General ([Settings and the command palette](02-panel-tour.md#settings-and-the-command-palette)).

![The Epoch tab showing a Schema Violation Error block under DISPATCH for an event whose argument failed its schema](../images/xray/xray-tutorial-schema.png)

## What A Schema Failure Tells You

A schema validation failure names the boundary that failed. The useful human question usually lives one level up: which event caused the failure, which step was being validated, and did app-db roll back?

Every failure is the error `:rf.error/schema-validation-failure`, and its `:where` names the boundary. What happens next depends on it:

| `:where` | Checked | What happens | Chip in Epoch |
| --- | --- | --- | --- |
| `:event` | the event vector, before the handler | the handler does not run | rejected |
| `:app-db` | the new app-db, before it is installed | nothing installs; app-db keeps its value from before the event | Aborted |
| `:machine-data` | a machine's `:data` after a transition | the whole event is rejected, as for `:app-db` | Aborted |
| `:fx-args` | an effect's arguments | that effect is skipped; the others run | skipped |
| `:sub-return` | a subscription's return value | the subscription returns `nil` | returned nil |
| `:flow-output` | a flow's output | the value is still written | no-recovery |

These checks run in development builds only; an event registered with `:boundary? true` keeps its event check in production. A coeffect whose recorded value fails its schema is a different error, `:rf.error/cofx-value-invalid`, and it stops the event in every build.

## What The Epoch Tab Shows

The failure appears as a **Schema Violation Error** block inside the step it belongs to: under DISPATCH for the event vector, under the `:db` row for app-db, under the effect row for effect arguments, and under the subscription row for a return value. The block carries the chip above, one sentence saying what happened with a **schema check** link to the schema's source, the `expected:` and `got:` values, and the validator's explanation. The chip is red when app-db was rolled back.

## A Small Example

The standard-epochs testbed has two steps that cross a schema boundary on purpose. Click **18. Bad event args**, and the event `[:standard-epochs/bad-event-args "not-a-number"]` fails its `pos-int?` argument schema: the row turns pink, and Epoch shows the block under DISPATCH with the **rejected** chip. Click **19. Bad app-db write** for the `:app-db` case, where the handler's write is aborted and app-db keeps its value from before the event. Open Trace if you need the raw validation record.

## Why This Matters For Tests

Schema violations should be treated as failures. A final app-db assertion can miss a bug if the runtime rolled back the invalid write and the state looks clean afterward. Xray keeps the violation attached to the epoch so the hidden failure remains visible.

Story holds a variant to the same rule. It counts a schema failure as part of the run, so the run does not pass unless an `:rf.assert/schema-error` assertion declared that violation as expected. [Schema failures](../story/06-xray-earned-at-failure.md#schema-failures) in the Story guide covers it from that side.

## Static Schemas

Use Static mode's Schemas tab when you want to browse what is registered. It lists the app-db schemas for the selected frame, and the event and subscription schemas. That is a different question from "which event violated a schema just now?"

- Dynamic Epoch answers: "where did this failure occur?"
- Dynamic Trace answers: "what was the exact emitted record?"
- Static Schemas answers: "what schemas exist in this app?"
