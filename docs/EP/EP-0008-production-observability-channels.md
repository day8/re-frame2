# EP-0008: Production Observability Channels

Status: final
Type: standards-track

> **`final` means the decisions are settled.** The one deferred call — Open
> Issue 1, whether frame-destroy emits a single always-on teardown *report* or
> per-hook always-on emissions — was ruled by Mike on 2026-06-11 (see
> [Resolved Decisions](#resolved-decisions)): a single bounded teardown report,
> `:rf.error/frame-teardown-failed` carrying `:hook-failures`, finally-shaped
> emit-safe. The three-channel contract, the promotion criterion, the JVM gate
> caveat, and the catalogue channel column have graduated into
> [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md), which is now
> authoritative. The design is locked.
>
> `final` does **not**, on its own, assert the *implementation* is gap-free: the
> teardown-report code, the audit sweep, the conformance pin, the guide material,
> and the wave review are tracked separately in the
> [Implementation errata](#implementation-errata) ledger below (the EP-0005
> pattern, per [EP-0009 §Statuses](EP-0009-the-ep-process.md#statuses)).

## Implementation errata

The EP decisions are final (Spec 009 carries the graduated normative text) and
the decision-freeze build has shipped: **every tracked erratum below is closed.**
This section is kept as a closed record of the build-completion work that
followed the freeze; none of it reopens any ruling.

### Resolved errata

The impl / audit / conformance / guide / review errata below are **fixed**; they
are kept here as a closed record and no longer reopen any ruling:

- **Fixed (PR #3860, impl)** — the frame-teardown report: a single
  always-on `:rf.error/frame-teardown-failed` record carrying a `:hook-failures`
  vector, emitted from `destroy-frame!` through a **finally-shaped** boundary so a
  partial teardown (abort after hook 3 of 7) still flushes the collected entries.
  The dev-only per-hook diagnostic (`:rf.warning/teardown-hook-exception`) stays
  at its causal positions inside `safe-call-hook!` and DCE-elides in production.
- **Fixed (audit)** — graded the full `:rf.error/*` /
  `:rf.warning/*` catalogue against the promotion criterion and filed promotion-fix
  beads for the gaps the sweep found. Teardown was the known first row (resolved by
  the report shape, above); the audit covered the rest and spun off the follow-ups
  noted below.
- **Fixed (PR #3872, conformance)** — the catalogue/channel pin:
  every emitted category appears in the Spec 009 catalogue with a channel, and every
  always-on category is exercised through the error-emit listener in at least one
  test (so promotion is real, not documentary).
- **Fixed (PR #3857, docs/guide)** — extended the
  production-observability guide material with the three-channel model, the JVM
  `re-frame.debug` default-on caveat, the promotion criterion, and the
  teardown-report example.
- **Fixed (review)** — correctness + completeness review of the
  whole EP-0008 wave against this EP and Mike's ruling; follow-ups filed.

### Resolved follow-ups

The audit promoted GAP-1 (`write-after-destroy`) and
GAP-3 (`on-destroy-handler-exception`) onto the always-on axis —
both **fixed** and closed. The two items the audit had left for a later call are
now **also closed**; neither reopens any ruling:

- **Fixed (PR #3986, impl/spec)** — the SSR-specific recoverable
  degradation members rode only the DCE'd / JVM-gated `trace/emit-error!`. Mike's
  call was to surface the degraded-200 path off-box: the build promoted **seven**
  SSR error categories (`ssr-render-failed`, `ssr-streaming-writer-failed`,
  `malformed-hydration-payload` — incl. the pre-frame frameless parse path,
  `ssr-head-resolution-failed`, `sanitised-on-projection`,
  `ssr-ring-error-view-failed`) onto the always-on error-emit axis through the
  general non-event `dispatch-error-record!` union helper, and **demoted** two
  (`resource-ssr-blocking-timeout`, `resource-route-blocking`) — kept diagnostic
  with a named-home note (their failure is recorded in observable resource/route
  state, failing the promotion criterion's leg 2). Spec 009 / 011 updated in the
  same PR.
- **Fixed (PR #4022, catalogue, P4)** — the co-edit-invariant
  gap: every emitted-but-uncatalogued `:rf.*` category was catalogued as a
  **diagnostic** Spec 009 row (each cited emit-site verified), the conformance
  scan's `out-of-catalogue-allow-list` was drained to empty, and two categories
  were ruled intentionally-out-of-catalogue with a one-line 009 note
  (`:rf.route/navigation-blocked`, the retired
  `:rf.warning/plain-fn-under-non-default-frame-once`). The catalogued rows all
  fail the promotion criterion and correctly stay diagnostic. The source-side
  retirement of the dead `plain-fn` emit followed and is closed.

## Abstract

For failure categories, re-frame2 has three observability channels with
different production guarantees, but only two are named and none has a
promotion rule:

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

This is not the complete Spec 009 observation-surface matrix. The always-on
event-emit listener and Performance API are production observability surfaces
with their own record shapes and gates; this EP governs the category-routing
question of when a failure/advisory currently on the diagnostic trace surface
must instead become a production-survivable always-on error record.

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
- No change to the diagnostic channel's inline-emit contract (the diagnostic
  channel is ambient by design, framework-wide).
- Not production *telemetry* (the 06-06 reviews' north-star) — that remains
  gated on its own privacy work; this EP only governs the error axis that
  already ships.
- No rename or reclassification of Spec 009's always-on event-emit listener,
  Performance API channel, SSR projector, or browser-native exception surfaces;
  those remain observation surfaces outside this EP's category-promotion rule.

## Relationships

- **Spec 009 (Errors, Warnings, and Diagnostics)** is the target normative
  home: this EP adds the channel contract and catalogue classification there.
- **EP-0002 (frame target resolution)** supplies the fail-closed framing for
  missing causal identity; this EP applies the same production-honesty standard
  to observability channels.
- **EP-0007 (one name per fact)** supplies the vocabulary rule. Causal,
  diagnostic, and always-on error channels name three different facts and must
  not collapse into ad hoc "log" terminology.
- **EP-0009 (EP process)** governs this proposal's status and guide-impact
  obligations before graduation.
- **EP-0010 (causal world inputs)** relies on this split: host facts that affect
  durable state ride the causal channel, while diagnostic timing and performance
  reads remain ambient.
- **EP-0015 (frame-owned egress policy)** is the privacy-policy consumer for
  production-survivable error and trace payloads that leave the frame boundary.

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
not widen that substrate to warnings. A failure fact that meets the criterion
but is surfaced only as a `:rf.warning/*` diagnostic is therefore **on the wrong
channel**: promotion names the production-survivable fact as a new `:rf.error/*`
category with a typed per-category default `:recovery` from the existing recovery
vocabulary. For frame-teardown the recovery is still `:ignored` — teardown
continues best-effort — but the production-survivable fact now rides the
always-on error axis. Skipped teardown is an error the process cannot locally
observe, not an advisory.

Promotion is **not a blind rename of the per-hook warning to a per-hook error.**
Where a single always-on emission would fan out one record per hook (the
frame-destroy case), the criterion is satisfied by a **single bounded report**
naming the destroy-as-fact, with the per-hook detail as `:hook-failures` rows
(Open Issue 1, ruled — see [Resolved Decisions](#resolved-decisions)). The
per-hook diagnostic stays on the diagnostic channel; only the always-on fact
collapses to one report. (R2: the "instead of per-hook emissions" scope is the
always-on axis only — dev per-hook trace rows at their causal positions are
unchanged.)

### The audit (initial known rows)

The teardown row is **resolved to the report-fact shape** (Mike's Open Issue 1
ruling — see [Resolved Decisions](#resolved-decisions)): one always-on
`:rf.error/frame-teardown-failed` record carrying a `:hook-failures` vector, NOT
a per-hook recategorization of `:rf.warning/teardown-hook-exception`. The
per-hook diagnostic stays on the **diagnostic** channel at its causal positions
(dev, DCE-elided in production) — per-hook visibility does not disappear; only
the *always-on* emission is a single report.

| Category | Today | Under the criterion |
|---|---|---|
| frame-teardown hook failures | per-hook `:rf.warning/teardown-hook-exception`, diagnostic (DCE'd) | **Promote to a single always-on report** → `:rf.error/frame-teardown-failed`, default `:recovery :ignored` (teardown continues best-effort; the one bounded record ships through the always-on axis carrying `:hook-failures`), finally-shaped so a partial teardown still flushes. The per-hook `:rf.warning/teardown-hook-exception` **stays diagnostic** (dev, at causal positions inside `safe-call-hook!`). The known C4 fix |
| `:rf.error/sub-input-fn-exception` / `-bad-return` | always-on | Correct as-is (the precedent rows) |
| `:rf.error/no-frame-context` | always-on | Correct (frameless errors need the frameless axis — EP-0002 R6) |
| `:rf.warning/app-handler-runtime-effect` | diagnostic | Correct — dev-time teaching diagnostic; leg 2 fails (the write applies; nothing leaks) |
| remaining `:rf.error/*` / `:rf.warning/*` catalogue | mixed | The audit grades every row; gaps become fixes |

### Conformance

The channel assignment becomes a column in Spec 009's error catalogue, and a
test pins it: every emitted category appears in the catalogue with a channel,
and always-on categories are exercised through `dispatch-on-error!` in at least
one test (so promotion is real, not documentary).

## Backwards Compatibility

Mostly additive, but not a pure no-op for production error-shipper consumers: a
new always-on `:rf.error/frame-teardown-failed` report fires on frame destroys
that had a hook failure (previously these were dev-trace-only
`:rf.warning/teardown-hook-exception` warnings that DCE'd out of production). The
per-hook diagnostic warning is unchanged on the diagnostic channel. Pre-alpha,
naming the production-survivable fact correctly wins over leaving it silent. Apps
with error shippers may see new (correct) reports after the impl bead lands —
release-notes material.

## Bead Plan

1. Spec bead (this graduation): the channel section + criterion +
   catalogue channel column + the `:rf.error/frame-teardown-failed` report row
   (hot-zone Spec 009; sequential). Records the Open Issue 1 ruling and flips this
   EP `final`. **Done** — Spec 009 is authoritative.
2. Teardown bead: emit a **single** always-on
   `:rf.error/frame-teardown-failed` record carrying a `:hook-failures` vector
   from `destroy-frame!`, through a **finally-shaped** boundary so a partial
   teardown still flushes the collected entries (R1). Keep the per-hook
   `:rf.warning/teardown-hook-exception` dev diagnostic at its causal positions
   inside `safe-call-hook!` (R2 — diagnostic channel, DCE'd in production). Plus a
   teardown-report test.
3. Audit bead: grade the full catalogue; file promotion fixes found.
4. Conformance bead: the catalogue/channel pin test.
5. Guide/docs bead: extend the production observability material
   with the three-channel model, the JVM debug gate note, the always-on promotion
   criterion, and the teardown-report example.
6. Review bead: correctness + completeness review of the whole
   wave vs this EP and the ruling.

## Guide Impact

On graduation this EP updates the observability/production guide material with:

- the causal / diagnostic / always-on error channel distinction;
- the JVM `re-frame.debug` default-on caveat for production SSR/tool hosts;
- the criterion for promoting a failure category onto the always-on axis;
- the teardown-failure example as the first concrete promotion.

## Open Issues

*(None open — Open Issue 1 was ruled YES with refinements; see
[Resolved Decisions](#resolved-decisions).)*

1. ~~Should frame-destroy emit a single always-on *teardown report* (one event
   summarizing all hook failures) instead of per-hook emissions?~~ **RESOLVED
   (Mike, 2026-06-11): yes — a single bounded report,
   `:rf.error/frame-teardown-failed` carrying a `:hook-failures` vector,
   finally-shaped emit-safe (R1), with the per-hook diagnostic kept on the
   diagnostic channel (R2). Full ruling + rationale in
   [Resolved Decisions](#resolved-decisions).**

## Resolved Decisions

**Open Issue 1 — single teardown report vs per-hook always-on emissions** was
ruled by Mike on 2026-06-11: **YES — a single always-on teardown report**, with
two refinements (neither changes the answer). The ruling, rationale, and
refinements are recorded here verbatim; the normative shape lives in
[`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) (the
`:rf.error/frame-teardown-failed` catalogue row + the channel contract).

### The ruling

Frame-destroy emits a **single always-on teardown report** — one bounded event
summarizing all hook failures — NOT per-hook always-on emissions. The always-on
category is named for the report-fact: `:rf.error/frame-teardown-failed`,
`:recovery :ignored` (teardown stays best-effort), carrying a `:hook-failures`
vector (one entry per failed hook). It is **not** the per-hook-shaped
`:rf.error/teardown-hook-exception` reused for a multi-hook record —
one-name-per-fact applies to the fact actually emitted (EP-0007).

### Rationale

1. **SSR is the quantitative killer.** Per-request frame destroys × M req/s under
   per-hook emission floods the production error shipper; one report caps it at
   1 × M — the difference between a diagnosable signal and a pipeline flood, in
   the exact deployment the EP protects.
2. **The destroy IS the fact; hooks are detail rows.** One record preserves the
   which-hooks-failed-together correlation that external shippers will not
   reliably re-group, and gives cleaner alert rates than a count that scales with
   how many hooks an app registers.
3. **The corpus already uses this idiom.** The Spec 016 trace family settled the
   same fan-out with single summary rows (`:rf.resource/route-plan`,
   `:rf.resource/revalidate-scan`) plus per-item detail on ordinary diagnostic
   traces.

### Refinements

- **R1 — emit-safe on partial teardown.** The report MUST be emit-safe on a
  PARTIAL teardown: a **finally-shaped emission boundary**, so that if teardown
  aborts after hook 3 of 7 the collected entries still flush. This neutralizes
  the one genuine advantage per-hook emission had (incremental delivery surviving
  a mid-teardown collapse). The contract is stated in Spec 009.
- **R2 — axis scope.** "Instead of per-hook emissions" is scoped to the
  **always-on axis only**. In dev, the per-hook diagnostic trace rows
  (`:rf.warning/teardown-hook-exception`) at their causal positions inside
  `safe-call-hook!` STAY (more useful there; DCE'd in production). Per-hook
  visibility does not disappear — only the always-on emission is a single report.

## Recommendation

Adopt. The axis exists, the precedent rows prove it, and the criterion is the
missing piece that turns per-call-site judgment into policy. The teardown
promotion alone justifies the EP — it is the last 2026-06-06 finding at
"silent-in-production" caliber still standing.
