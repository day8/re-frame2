# EP-0008: Production Observability Channels

Status: proposal
Type: standards-track

> Deliberately short-lived as a proposal: the destination is Spec 009 (the
> channel section + the catalogue column); on graduation this EP goes `final`
> and Spec 009 is authoritative.

## Abstract

re-frame2 has three observability channels with different production
guarantees, but only two are named and none has a promotion rule:

1. the **causal channel** — effects-as-data, replayable, part of the semantic
   value; never elided;
2. the **diagnostic channel** — `trace/emit!`, ambient by design,
   production-elided (Closure DCE under `goog.DEBUG=false`; JVM-gated on
   `re-frame.debug`);
3. the **always-on error axis** — `error-emit/dispatch-on-error!`, deliberately
   production-survivable, reaching app-registered shippers.

Which failures deserve channel 3 is today decided ad hoc, per call site. The
result is verified inconsistency: a parametric subscription input-fn failure
rides the always-on axis, while a **frame-teardown hook exception is
deliberately DCE'd out of production** — in the exact long-lived SSR / tooling
processes its own docstring names as the risk — so a production cleanup failure
(leaked request data, orphaned timers, cross-request contamination) is silent.

This EP names the channels normatively in Spec 009, states the promotion
criterion, audits the existing diagnostic categories against it, and fixes the
gaps the audit finds (teardown failures first).

## Motivation

The 2026-06-06 design reviews flagged this three ways (C4: teardown
prod-silence; C6: hand-maintained channel coverage; C9: dev-only diagnostics
guarding production-relevant invariants). The 2026-06-10 review confirmed all
three still hold and added the unifying observation: the *pattern* (an
always-on axis) exists and works — `:rf.error/sub-input-fn-*` categories ride
it correctly — but no rule says what must ride it, so each new category is a
fresh judgment call and the calls disagree.

A second motivation is honesty about the JVM: "production-elided" means
*elidable*, not *elided by default* — `debug-enabled?` defaults **true** on the
JVM unless `-Dre-frame.debug=false` is set, so a production JVM SSR process
that doesn't set the flag runs the full dev diagnostic surface. The channel
contract must state this per channel, once, instead of each spec section
hand-waving "moot in production."

## Goals

- Name the three channels and their production guarantees in one Spec 009
  section.
- State the promotion criterion for the always-on axis.
- Audit every existing `:rf.error/*` / `:rf.warning/*` category against the
  criterion; produce the channel-assignment table.
- Fix the audit's gaps — teardown-hook failures are the known first row.
- Record the JVM gate semantics once.

## Non-Goals

- No new channel, no new mechanism: the always-on axis exists and is proven.
- No change to the diagnostic channel's inline-emit contract (the rf2-lq1q21
  ruling: the diagnostic channel is ambient by design, framework-wide).
- Not production *telemetry* (the 06-06 reviews' north-star) — that remains
  gated on its own privacy work; this EP only governs the error axis that
  already ships.

## Specification

### The channel contract (new Spec 009 section)

> The **causal channel** is data and replayable; it is the program. The
> **diagnostic channel** is ambient and production-elided; it is for
> development eyes and tools. The **always-on error axis** is the deliberate,
> criterion-gated exception that survives production builds.
>
> Production guarantees: CLJS `:advanced` + `goog.DEBUG=false` DCEs the
> diagnostic channel entirely. The JVM gate (`re-frame.debug` /
> `RE_FRAME_DEBUG`) defaults **on**; production JVM deployments must set it
> explicitly. The always-on axis survives both.

### The promotion criterion

A failure category MUST ride the always-on error axis when **all three** hold:

1. it can occur in a production build (not exclusively dev-time misuse);
2. it indicates **contract breach or resource leakage** — state the next
   operation cannot see locally (leaked handles, skipped teardown, suppressed
   writes, corrupted invariants) rather than a malformed input the caller can
   observe and fix;
3. silence compounds — the failure's cost grows with process lifetime or
   recurrence (long-lived SSR, tooling hosts, retry loops).

Categories failing any leg stay on the diagnostic channel. Categories on the
always-on axis carry structured data only (error id, ids/keys, frame) — never
raw values; the axis is subject to the same egress redaction posture as all
off-box surfaces.

**Category kind follows the channel.** The always-on axis is contractually
`:rf.error/*`-only (Ownership: "one tight record per production-reachable
`:rf.error/*`"; Spec 009 §What is available in production builds). This EP does
not widen that substrate to warnings. A `:rf.warning/*` category that meets the
criterion was therefore **misclassified as a warning**: promotion includes
recategorization to `:rf.error/*` with a typed per-category default `:recovery`
(e.g. `:reported`), per the existing error-contract shape. Skipped teardown is
an error the process cannot locally observe — not an advisory.

### The audit (initial known rows)

| Category | Today | Under the criterion |
|---|---|---|
| `:rf.warning/teardown-hook-exception` | diagnostic (DCE'd) | **Promote + recategorize** → `:rf.error/teardown-hook-exception`, default `:recovery :reported` (teardown continues best-effort; the failure ships) — production-possible, resource-leakage class, compounds in long-lived processes. The known C4 fix |
| `:rf.error/sub-input-fn-exception` / `-bad-return` | always-on | Correct as-is (the precedent rows) |
| `:rf.error/no-frame-context` | always-on | Correct (frameless errors need the frameless axis — EP-0002 R6) |
| `:rf.warning/app-handler-runtime-effect` | diagnostic | Correct — dev-time teaching diagnostic; leg 2 fails (the write applies; nothing leaks) |
| remaining `:rf.error/*` / `:rf.warning/*` catalogue | mixed | The audit bead grades every row; gaps become fixes |

### Conformance

The channel assignment becomes a column in Spec 009's error catalogue, and a
test pins it: every emitted category appears in the catalogue with a channel,
and always-on categories are exercised through `dispatch-on-error!` in at least
one test (so promotion is real, not documentary).

## Backwards Compatibility

Additive: promotions add production emissions that did not exist; nothing is
removed from the diagnostic channel. Apps with error shippers may see new
(correct) reports after promotion — release-notes material, pre-alpha.

## Bead Plan

1. Spec bead: the channel section + criterion + catalogue column (hot-zone
   Spec 009; sequential).
2. Teardown bead: recategorize `:rf.warning/teardown-hook-exception` →
   `:rf.error/teardown-hook-exception` (catalogue row, `:recovery :reported`)
   and route `safe-call-hook!` failures through the always-on axis (keep the
   dev trace breadcrumb), plus a teardown-report test.
3. Audit bead: grade the full catalogue; file promotion fixes found.
4. Conformance bead: the catalogue/channel pin test.

## Open Issues

1. Should frame-destroy emit a single always-on *teardown report* (one event
   summarizing all hook failures) instead of per-hook emissions?
   Recommendation: yes — one bounded report per destroy, carrying per-hook
   entries; avoids burst noise from a cascading teardown failure.

## Recommendation

Adopt. The axis exists, the precedent rows prove it, and the criterion is the
missing piece that turns per-call-site judgment into policy. The teardown
promotion alone justifies the EP — it is the last 2026-06-06 finding at
"silent-in-production" caliber still standing.
