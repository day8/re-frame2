# rf2-pisq6: sensitive-handler redaction on error paths

## Scope

Report-only security/spec review for handler metadata `:sensitive?`, `with-redacted`, trace/error listeners, frame `:on-error`, and relevant tests/specs. No implementation change was made in this worktree.

## Question 1: should `:sensitive? true` be a hard privacy guarantee?

Yes, but only at egress/trust-boundary surfaces.

Handler metadata `:sensitive? true` is too prominent to remain a convention on production-survivable thrown-event/error paths. If an app author marks a handler sensitive and the handler throws, the raw event vector must not reach:

- `register-error-emit-listener!` corpus-wide listeners.
- The frame `:on-error` policy event.
- Any framework-published off-box forwarder built on those records.

The guarantee should not mean "the handler body never sees secrets" or "in-process dev trace callbacks never receive sensitive trace events." Handlers need raw inputs to do work. The dev trace surface is explicitly on-box and callback-oriented; it should continue to stamp `:sensitive? true` and require trace consumers to default-drop/scrub before off-box egress. The hard guarantee belongs at the always-on error/event substrates and other wire egresses.

## Current behavior observed

The current branch already contains an rf2-vnjfg implementation for the always-on handler-exception path:

- `implementation/core/src/re_frame/error_emit.cljc` looks up the failing event handler metadata, treats `:sensitive? true` as enforcing, and replaces the tight error-record `:event` with `:rf/redacted`.
- The same function redacts the structured policy event by replacing `[:tags :event]`, and defensively `[:tags :emit-event]` if present, with `:rf/redacted`.
- `implementation/core/src/re_frame/router.cljc` builds the handler-exception `error-event` once, passes it through `error-emit/dispatch-on-error!` for always-on listeners and `:on-error`, then separately emits the dev trace event with `trace/emit-error!`.
- `implementation/core/src/re_frame/event_emit.cljc` already takes the stricter success-path posture: handler-meta `:sensitive? true` drops the event-emit record entirely.
- `implementation/core/src/re_frame/trace.cljc` remains declarative for dev trace callbacks: trace events are stamped with top-level `:sensitive? true` but listeners still receive them.
- `implementation/core/src/re_frame/privacy.cljc` supports schema-derived redaction for path-scoped handlers via `:rf/redacted-event`; handler metadata remains the whole-handler escape hatch.
- `implementation/core/src/re_frame/elision.cljc` redacts schema-declared sensitive paths to `:rf/redacted`, and sensitive wins over large elision.

Relevant tests already present:

- `implementation/core/test/re_frame/on_error_test.cljc` has rf2-vnjfg tests proving sensitive handler throws redact the error listener record and the frame `:on-error` event, while non-sensitive handler errors still include the event vector.
- `implementation/core/test/re_frame/on_error_elision_prod_test.cljs` proves the same redaction holds under `:advanced` plus `goog.DEBUG=false`.
- `implementation/core/test/re_frame/event_emit_test.cljc` proves sensitive handler metadata drops event-emit records.
- `implementation/core/test/re_frame/sensitive_stamping_test.clj` proves trace stamping and schema-auto-redaction for path-scoped handlers, including handler exceptions.

Spec/docs are mostly aligned in `spec/009-Instrumentation.md`: the "Substrate-level enforcement on the always-on surfaces" section says event-emit drops and error-emit redacts for handler-meta `:sensitive? true`. `spec/Security.md` also says the always-on handler-exception path redacts. There is minor drift in `spec/API.md`: the Event-emit and Error-emit summaries say the `:event` slot passes through `elide-wire-value`, but do not explicitly mention the handler-meta override where event-emit drops the whole record and error-emit redacts the whole `:event` slot.

## Risks

- Without source-level enforcement on error surfaces, handler-meta `:sensitive? true` is a false sense of privacy: the exact failure path most likely to be shipped to Sentry/Honeybadger/Rollbar would carry the raw event payload.
- Dropping error records entirely would protect privacy but degrade production triage and frame recovery policy. `:on-error` needs the category, event id, frame, exception, and timing to make useful decisions.
- Passing exception objects through remains a residual leak risk. The current implementation redacts the event payload, not exception messages or ex-data. User exceptions can embed PII in `ex-message` or `ex-data`. The current spec appears to accept exception pass-through for operator triage, but this should be called out explicitly.
- Dev trace callbacks still receive stamped sensitive events. That is consistent with the current contract, but only safe if framework-published trace consumers default-drop and custom off-box consumers follow the same rule.
- Documentation drift can cause reimplementation bugs in other hosts. A port that reads only `API.md` may call `elide-wire-value` and miss the handler-meta whole-event redaction/drop rule.

## Options

### Option A: convention only

Treat handler-meta `:sensitive? true` as a trace stamp only. Require app authors to also use `with-redacted` or schema `:sensitive?` for thrown-event payload privacy.

Reject. This is the unsafe state described by the bead. It creates a footgun exactly on production error monitoring surfaces.

### Option B: drop every sensitive error record

If a sensitive handler throws, do not call error listeners or `:on-error`.

Reject as canonical behavior. It protects payloads, but it makes production error monitoring and per-frame recovery blind. Errors are different from successful event telemetry: the runtime needs to expose that something failed.

### Option C: redact event payload, preserve diagnostic envelope

For handler-meta `:sensitive? true`, error-emit and `:on-error` preserve category, event id, frame, exception, elapsed time, and recovery metadata, but replace the event payload with `:rf/redacted`.

Recommend. This is the current implementation direction and the right canonical contract.

## Recommended canonical behavior

Canonical behavior should be:

- Schema-slot `{:sensitive? true}` is the primary per-path privacy declaration. At wire egress, sensitive path values become `:rf/redacted`; if a value is both sensitive and large, sensitive wins and no large marker is emitted.
- Handler metadata `{:sensitive? true}` is the whole-handler escape hatch.
- Dev trace bus: do not gate delivery. Stamp top-level `:sensitive? true`; framework-published trace consumers and off-box forwarders must default-drop or redact. The trace buffer filter should continue to support `{:sensitive? false}`.
- Event-emit substrate: drop the entire per-event record for handler-meta `:sensitive? true`.
- Error-emit substrate: do not drop the error. Redact the event payload to `:rf/redacted` in the listener record and the frame `:on-error` structured event.
- Frame `:on-error`: receive the same redacted event view as error-emit listeners, not a privileged raw copy.
- `with-redacted`: remain a precise event-payload scrubber for cases where retaining non-sensitive payload fields is useful. It should not be required as belt-and-braces for handler-meta error privacy.
- Exception object/message/ex-data: current behavior preserves them. If the intended privacy guarantee includes exception data, that is a separate stricter contract and should be decided explicitly.

## Tests that prove it without killing diagnostics

The useful tests are the ones that assert absence of secrets and presence of stable triage fields:

- Sensitive handler throws with event `[:login {:password "p" :totp "123"}]`; error listener fires exactly once; `:event` is `:rf/redacted`; `:event-id`, `:frame`, `:error`, `:exception`, `:elapsed-ms`, and `:time` remain present.
- Same dispatch with frame `:on-error`; policy fires exactly once; `[:tags :event]` is `:rf/redacted`; `[:tags :event-id]`, `[:tags :frame]`, `:operation`, `:op-type`, `:recovery`, and `[:tags :exception]` remain present.
- The same two assertions run under CLJS prod-elision (`:advanced`, `goog.DEBUG=false`), because this is the high-risk deployment surface.
- Non-sensitive handler throws with a benign payload; error listener and `:on-error` still receive the event vector, subject only to path-level `elide-wire-value`. This prevents over-redaction from making diagnostics useless.
- Schema-sensitive path-scoped handler throws; only the sensitive payload paths are `:rf/redacted`, non-sensitive sibling fields remain visible, and the handler body still sees raw input.
- Event-emit success/error record for a sensitive handler is dropped entirely; event-emit for a non-sensitive handler still fires.
- Dev trace listener receives stamped `:sensitive? true` events, and framework-published consumers default-drop them at egress. This proves the split between on-box trace fidelity and off-box privacy.
- Regression assertion for exception residual risk: either document/pass current behavior by asserting exception is preserved, or, if operators decide exception data is in scope, add a separate test that exception message/ex-data are sanitized.

## Implementation implications

The current CLJS reference implementation appears to already implement the recommended always-on error behavior for `:rf.error/handler-exception`. No implementation patch is recommended from this report.

The remaining implementation risk is breadth:

- `error_emit/dispatch-on-error!` redacts handler exceptions because it can look up `event-id`. Other error categories that include raw event-like payloads need category-by-category review before claiming a universal `:sensitive? true` hard guarantee across all thrown/error surfaces.
- Flow evaluation, fx-handler exceptions, schema failures, routing errors, and SSR projections have adjacent privacy concerns but different available context. Some already have schema-sensitive redaction; they should not be assumed covered by the handler-exception fix.
- Other host-language ports should not derive this behavior solely from `elide-wire-value`; handler-meta whole-event drop/redact is a substrate policy layered above the path walker.

## Spec/doc implications

Recommended doc/spec cleanup:

- Update `spec/API.md` Event-emit summary to say handler-meta `:sensitive? true` drops the record before listener fan-out, while path-level `elide-wire-value` still applies to non-dropped records.
- Update `spec/API.md` Error-emit summary to say handler-meta `:sensitive? true` redacts the whole `:event` slot to `:rf/redacted`, while path-level `elide-wire-value` applies otherwise.
- Consider clarifying `docs/guide/23a-privacy-secrets.md` where it says handler-meta `:sensitive?` "propagates the flag through dispatched cascades"; `spec/009` says innermost scope wins and tools OR-reduce by `:dispatch-id` if they want cascade-level sensitivity.
- Add a conformance fixture for sensitive handler exception redaction so non-CLJS implementations cannot miss the hard guarantee.
- Add a spec note that exception objects/messages/ex-data are not covered by handler-meta event redaction unless a future bead changes that contract.

## Follow-up beads needed

Recommended follow-up beads to file:

- `spec(api): align Event-emit/Error-emit docs with handler-meta sensitive drop/redact`
- `conformance: add sensitive-handler exception redaction fixture`
- `security(spec): decide exception message/ex-data privacy scope for sensitive handler errors`
- `docs(privacy): clarify handler-meta sensitivity scope vs cascade propagation wording`

No bead should be closed from this report-only task.
