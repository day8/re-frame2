# Diagnostic values

Use these tables to interpret a value you found in Epoch or Trace. They
document the runtime's recovery, so a failed check can be distinguished
from an installed write or a delivered reply.

## Schema validation

These `:where` values accompany `:rf.error/schema-validation-failure`.

| `:where` | Value checked | Recovery |
| --- | --- | --- |
| `:event` | Dispatched event vector | Reject the event before its handler runs |
| `:app-db` | Candidate app-db | Reject the candidate transaction; previous installed state remains |
| `:fx-args` | Arguments to an effect | Skip that effect; other eligible effects can run |
| `:sub-return` | A subscription's computed return | Return `nil` |
| `:sub-override` | A test/frame subscription override against the registered schema | Substitute `nil` for the invalid override |
| `:flow-output` | Computed flow output | Report the failure; the output is still written (`:no-recovery`) |
| `:machine-data` | Machine `:data` | Depends on `:phase`, below |
| `:machine-output` | Final-state completion output | Report the failure after completion; the machine has already finished (`:no-recovery`) |

Machine-data validation also carries the lifecycle phase:

| `:phase` | Effect of an invalid value |
| --- | --- |
| `:macrostep` | Reject the candidate event transaction; `:rollback? true` |
| `:bootstrap` | Reject the initial candidate transaction; `:rollback? true` |
| `:spawn` | Skip installation of the child actor; `:rollback? false` |
| `:update-snapshot` | Skip the invalid snapshot patch; `:rollback? false` |

These checks normally run in development builds. An event's `:boundary? true`
keeps its event check in production. A recorded coeffect's value check is
the separate `:rf.error/cofx-value-invalid` and runs in every build.
The [schema guide](../../core/how-to/validate-with-schemas.md) explains declaring schemas.

## Managed HTTP outcomes

An HTTP card joins the request with retained completion and reply evidence.
Its status is distinct from the application's own loading status.

| Display | Meaning | Useful next action |
| --- | --- | --- |
| **OK** | Successful completion was retained | Follow **→ reply ↗** to inspect the state it installed |
| **ERROR** | Failed completion was retained | Expand Response for the failure category and HTTP status, when present |
| **CANCELLED** | Cancellation was retained | Inspect the cancellation cause and owning actor |
| **STALE** | The completed work was no longer eligible to deliver | Check supersession or actor lifetime; no reply was delivered |
| **ISSUED** | No terminal completion joined in this capture | Check the active-work state and reproduce; this alone does not prove it is still running |

**ISSUED · no completion in this capture** means the issuing trace is present
but a matching terminal trace is absent. Eviction and still-pending work can
produce that same evidence. A configured **Reply target** is the caller's
declaration; **→ reply ↗** appears only for a delivered reply whose event
bundle is retained.

## Launch and focus

[`status`](mount-control.md#status) enumerates launch diagnostics, and
[`focus!`](mount-control.md#focusing-a-panel-from-a-host) documents the command
and its `:unknown-panel` result. A browser-blocked pop-out returns
`{:ok? false :reason :popup-blocked}` from `popout!`.

An unknown `:rf.xray/egress-profile` passed to `configure!` raises
`:rf.error/unknown-egress-profile`. Use one of the
[accepted profile values](config-keys.md#egress-profile-values).
