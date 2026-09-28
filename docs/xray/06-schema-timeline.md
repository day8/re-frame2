# 6. Schema violations

A schema check failed while your app ran. Xray shows the failure at the event that caused it, inside the step where it happened, with the raw trace record behind it.

## Where a violation appears

Xray shows a schema violation in three places:

- the event's row in the event list is washed pink;
- the Epoch tab shows the failure inside the step it belongs to;
- the Trace tab holds the underlying trace record.

There is no separate list of violations: each one is shown with the event that produced it. To have Xray open itself when one happens, turn on **Auto-open Xray when an issue is observed** in Settings → General ([Settings and the command palette](02-panel-tour.md#settings-and-the-command-palette)).

## A small example

The standard-epochs testbed has two steps that fail a schema check on purpose. Click **18. Bad event args**, and the event `[:standard-epochs/bad-event-args "not-a-number"]` fails its `pos-int?` argument schema: the row turns pink, and Epoch shows the failure under DISPATCH with the **rejected** chip. Click **19. Bad app-db write** for the `:app-db` case, where the handler's write is aborted and app-db keeps its value from before the event. Open Trace if you need the raw validation record.

![The Epoch tab showing a Schema Violation Error block under DISPATCH for an event whose argument failed its schema](../images/xray/xray-tutorial-schema.png)

## What the Epoch tab shows

The failure appears as a **Schema Violation Error** block inside the step it belongs to: under DISPATCH for the event vector, under the `:db` row for app-db, under the effect row for effect arguments, and under the subscription row for a return value. The block carries a chip saying what the runtime did, one sentence explaining it with a **schema check** link to the schema's source, the `expected:` and `got:` values, and the validator's explanation. The chip is red when app-db was rolled back.

## What the runtime does

Every failure is the error `:rf.error/schema-validation-failure`, and its `:where` names what was checked. What happens next depends on it:

| `:where` | Checked | What happens | Chip in Epoch |
| --- | --- | --- | --- |
| `:event` | the event vector, before the handler | the handler does not run | rejected |
| `:app-db` | the new app-db, before it is installed | nothing installs; app-db keeps its value from before the event | Aborted |
| `:machine-data` | a machine's `:data` after a transition | the whole event is rejected, as for `:app-db` | Aborted |
| `:fx-args` | an effect's arguments | that effect is skipped; the others run | skipped |
| `:sub-return` | a subscription's return value | the subscription returns `nil` | returned nil |
| `:flow-output` | a flow's output | the value is still written | no-recovery |

These checks run in development builds only; an event registered with `:boundary? true` keeps its event check in production. A coeffect whose recorded value fails its schema is a different error, `:rf.error/cofx-value-invalid`, and it stops the event in every build.

## Violations in tests

Treat a schema violation as a failure. When the runtime rolls back an invalid write, app-db looks clean afterwards, so a test that only checks the final app-db can pass over the bug. Xray keeps the violation with its epoch, so you can still see it.

Story holds a variant to the same rule. It counts a schema failure as part of the run, so the run does not pass unless an `:rf.assert/schema-error` assertion declared that violation as expected. [Schema failures](../story/06-xray-earned-at-failure.md#schema-failures) in the Story guide covers it from that side.

## Browsing schemas

Static mode's Schemas tab lists what is registered: the app-db schemas for the selected frame, and the event and subscription schemas. Use it to answer "what schemas exist in this app?", and the Dynamic Epoch and Trace tabs to answer "which event violated one just now?"
