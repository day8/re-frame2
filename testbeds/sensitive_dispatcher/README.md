# `testbeds/sensitive-dispatcher`

Three handlers marked `:sensitive? true` in their registration
metadata, each driving a distinct privacy contract on a different
boundary. A consumer (Causa, Story, pair2-mcp) reads the surface to
verify the always-on event-emit substrate, the always-on error-emit
substrate, the dev trace surface, the recorder, and the MCP wire
each enforce their slice of the contract.

## The three privacy boundaries

| Button | `data-testid` | Registration | Expected wire shape |
|---|---|---|---|
| A | `sign-in-plain` | `:sensitive? true` (plain) | **Event-emit substrate DROPS the record entirely** (per rf2-6hklf). Dev trace surface stamps `:sensitive? true` at the top level of every trace event; payload rides through verbatim — downstream consumers filter via the stamp. |
| B | `sign-in-redacted` | `:sensitive? true` + `[(rf/with-redacted [[:password] [:totp-code]])]` | The `:password` and `:totp-code` slots of the event vector become `:rf/redacted` before the trace emits. Recorder, MCP wire, and trace stream all see the scrubbed shape. Event-emit substrate still DROPS the record. The handler body sees the unredacted payload (its work depends on the real values). |
| C | `sign-in-throw` | `:sensitive? true` + throws on dispatch | **Error-emit substrate REDACTS `:event` to `:rf/redacted`** before fan-out (per rf2-vnjfg). Operators see exception class + frame + failing event-id + `:elapsed-ms`; secret payload does not leak. |

The canonical credentials payload (`example-credentials` in
`core.cljs`) literal-carries the strings `"shhh-this-is-secret"` and
`"123456"` so a Playwright spec can assert these strings DO NOT
appear on the wire shape under any of the three buttons.

## DOM mirrors

| Element | What it asserts |
|---|---|
| `plain-count` | Advances on Button A — proves substrate-drop ≠ skip-handler. The handler body runs even though the event-emit listener never sees the record. |
| `redacted-count` | Advances on Button B — proves the `with-redacted` interceptor scrubs only the trace surface, NOT the handler body. The cofx slot the handler reads is the unredacted payload (per [spec/009 §The `with-redacted` interceptor]). |
| `throw-count` | Always 0 — the throw fires before any `:db` commit. The error-emit listener fires with the redacted `:event`. |
| `meta-plain` / `meta-redacted` / `meta-throw` | `"true"` after boot — the registrar's stored handler-meta carries `:sensitive? true` for all three handlers. A spec calling `(rf/handler-meta :event id)` directly reads the same. |

## Why three buttons

Three is the smallest count that exercises all four privacy
boundaries the spec separates:

- **Event-emit substrate drop** (buttons A + B + C; A and B don't
  throw, C does — both paths drop on the event-emit substrate).
- **Trace-surface payload visibility under `:sensitive?` declarative
  stamp** (button A — `:sensitive? true` at top level on every
  trace event, payload rides through; consumer filters).
- **Trace-surface payload redaction under `with-redacted`** (button
  B — `:rf/redacted` sentinel replaces the named keys at trace
  emission).
- **Error-emit substrate redaction** (button C — `:event` slot of
  the error record substituted with `:rf/redacted` at the wire
  boundary).

A consumer that conflates any pair (e.g. event-emit drop with
error-emit drop, or `with-redacted` scope with `:sensitive?` top-
level stamp) fails on the missing distinction.

## What's deliberately *missing*

- **No `:large?` declaration.** The privacy markers compose with
  size markers per Spec 009 (sensitive drop wins on conflict); that
  composition is the `large_dispatcher/` testbed's job.
- **No multi-frame routing.** Sensitive cascades that cross frames
  retain the stamp per-handler scope; this surface stays on the
  default frame so each button's outcome is observable in isolation.
- **No HTTP fx.** The `:rf.http/managed` fx carries its own
  `:sensitive?` posture (per [spec/014 §Sensitive HTTP] / Security
  decision log); conflating it with handler-level `:sensitive?`
  would dilute the contract under test.
- **No `:rf.warning/sensitive-without-redaction` warning trigger.**
  Per [spec/009 §Production-elision behaviour] that warning fires
  when `:sensitive?` is declared WITHOUT `with-redacted`; Button A
  triggers it on dev builds. The surface deliberately does not
  capture or display the warning — it's a dev-only advisory and
  the consumer's panel surfaces it via the warning channel.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- **`:sensitive?` event arrives with `:event` redacted to
  `:rf/redacted`** — the load-bearing scenario this surface
  unblocks. Button B fires; Causa's trace panel must show the
  `:event` payload's `:password` and `:totp-code` slots replaced
  with the sentinel, not the original strings.
- Handler exception surfaces as `:effects` outcome `:error` per
  epoch record — Button C fires; the epoch record carries
  `:outcome :error` with the redacted event-payload slot.

**Story (18)**:
- **Recorder redacts secret-bearing events (rf2-hdadz)** — the
  load-bearing scenario. Button B's click should be captured by
  the recorder with the redacted event vector, not the raw one.
  A recorder that captures the raw vector ships secrets out of
  the box.

**Cross-cutting (6)**:
- `:rf.warning/sensitive-without-redaction` dev advisory — Button A
  emits the warning at registration time (since `:sensitive?` is
  declared without `with-redacted`); consumer panels surface this
  as a registration-time advisory.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/sensitive-dispatcher
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/sensitive-dispatcher`; output
lands in `implementation/out/testbeds/sensitive-dispatcher/`.

## Cross-references

- [`spec/009-Instrumentation.md` §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md) — the contract for the `:sensitive?` registration meta key, the `with-redacted` interceptor, and the top-level trace-event stamp.
- [`spec/009-Instrumentation.md` §Substrate-level enforcement on the always-on surfaces](../../spec/009-Instrumentation.md) — the event-emit drop (rf2-6hklf) and error-emit redact (rf2-vnjfg) contracts buttons A/B and C exercise.
- [`spec/Security.md` §Privacy / secret handling](../../spec/Security.md) — the privacy decisions log keying every behaviour above to a bead.
- [`spec/Conventions.md` §Reserved namespaces](../../spec/Conventions.md) — the `:rf/redacted` sentinel keyword owned by the framework.
