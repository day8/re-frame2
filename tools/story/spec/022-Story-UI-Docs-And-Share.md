# Story UI — Docs Mode and Share

> The Story `:docs` mode as curated, executable variant documentation
> with evidence excerpts; and the egress surface — share URLs, static
> export, copied EDN / inline plans / run artifacts, screenshots, and
> promoted variants — all flowing through one common redaction/elision
> seam before data leaves the process. This spec **extends**
> [`008-Docs-Mode.md`](008-Docs-Mode.md) with evidence excerpts and the
> egress contract.

## Builds on

- [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) — the
  product contract and the T4 safe-sharing-vs-useful-reproduction tension
  resolution (§4).
- [`008-Docs-Mode.md`](008-Docs-Mode.md) — the current read-only
  AutoDocs-equivalent docs pane (header, prose, args, decorators,
  parameters, tags) and its read-only contract.
- [`013-Static-Build.md`](013-Static-Build.md) — `story:build`, the
  static HTML export; the egress contract here applies to its output.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) — the per-variant share
  URL surface (live address-bar) and embed code; this spec routes those
  through the common egress seam.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the run-result and
  evidence projection that docs excerpts and copied artifacts draw from.
  **Source of truth for the substrate.**
- [`../../../spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md)
  and [`../../../spec/Security.md`](../../../spec/Security.md) — the
  framework's path-level data-classification and privacy posture the
  egress seam consumes.

## Supersedes

- Nothing behavioural at the docs-pane level — this spec **extends**
  [`008-Docs-Mode.md`](008-Docs-Mode.md) with evidence excerpts,
  fidelity/world-input/runner/frame-binding chips, and links into
  Inspector/Xray, rather than replacing its read-only contract. The
  share/static/copy egress requirement is net-new: it makes every egress
  call one common redaction seam, superseding the per-surface ad-hoc
  redaction the current share URL relies on once that seam lands.

## Depends on

- **The common egress redaction/elision seam (`rf2-qarwq`).** Safe share
  URLs, static export, copied EDN / inline plans / run artifacts,
  screenshots, and promoted variants are BLOCKED on this shared substrate
  seam; epoch redaction alone is not enough. This is referenced as the
  dependency, not specified here.
- The evidence-spine display for the docs excerpt — owned by
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §3.

## Out of scope

- The egress seam's internal redaction primitive itself — owned by the
  shared substrate (`rf2-qarwq`) and the framework privacy specs
  ([`../../../spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md),
  [`../../../spec/Security.md`](../../../spec/Security.md)). This spec
  states what Story's egress surfaces MUST route through it.
- Test-mode result presentation and failure promotion —
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md).
- Controls and save-current-state authoring —
  [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md).
- A hosted visual-review service — OUT (see
  [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §6).

## Status labels

This spec uses the Story UI status labels (CURRENT / TARGET / BLOCKED /
SUPERSEDES / FUTURE / OUT) defined in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§"Normative language".

## 1. Docs mode

Story pressure: S1, S8, S11.

Docs mode remains the read-only curated presentation of the variant. It
MUST preserve the current docs-pane contract
([`008-Docs-Mode.md`](008-Docs-Mode.md)) unless superseded through one
converged docs path.

Docs SHOULD add:

- fidelity badges, world-input chips, runner-requirement chips, and
  frame-binding chips;
- a view-arg schema table;
- a current test-status summary;
- a visual/a11y status summary;
- a sparse evidence excerpt where useful (drawn from the evidence-spine
  display,
  [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
  §3);
- links into Inspector/Xray for detailed diagnostics.

Docs MUST NOT become a debugging log. Detailed diagnostics belong in
Inspector/Xray. Docs mode should make a variant feel like a curated
example, not an implementation fixture, and MUST avoid marketing-page
layout patterns — the user is already inside a developer tool.

## 2. Evidence excerpts

Story pressure: S8.

Docs MAY summarize status, fidelity, and one or two narrative beats
inline, but detailed diagnostics MUST link to the Inspector/Xray embed
rather than inlining a full beat tree. The excerpt is a curated pointer
into the evidence spine, not a second evidence renderer — the spine
display contract in
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§3 owns the rendering; docs excerpts select a sparse projection of it.

## 3. Privacy, share, static, and artifacts (the egress seam)

Story pressure: S6, S9, S11.

The UI MUST respect the framework privacy posture across **every** egress:

- share URL;
- static build;
- copied EDN;
- copied inline plan;
- copied run artifact;
- screenshots;
- promoted variants.

This is BLOCKED until the common egress redaction/elision seam
(`rf2-qarwq`) exists. Epoch redaction alone is not enough: a single seam
must apply before data leaves the process so the share URL, the static
build, the copied artifact, and the screenshot all use the same redaction
policy. (This bead is the named dependency; the seam's internals are out
of scope here.)

Share semantics MUST specify:

- what goes into URL params;
- what remains local-only;
- whether controls/sub-overrides/network stubs are encoded;
- whether generated run artifacts are embedded, referenced, or omitted;
- whether a shared link restores view state or replays a run;
- how redaction is applied before data leaves the process.

Per the T4 resolution
([`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4): redaction
MUST be visible and explain what was removed; a shared artifact MAY be
partially reproducible but MUST say so when redaction removes required
data.

Until the seam exists, the UI MUST treat "share current state" as a
privacy-sensitive command, especially when cell overrides or view-state
fixtures contain user data. Existing share-URL state that serializes cell
overrides is a known risk surface until the shared policy lands.

## 4. Agent and share artifacts

Story pressure: S9, S11.

Agent/MCP read, copy, and share operations MUST use the same egress
redaction policy as human egress (§3) — there is no agent-only egress
path that bypasses redaction. The artifact an agent copies (inline plan,
run artifact, EDN projection) is the same redacted artifact a human would
copy, consistent with the no-second-artifact-model rule (T3,
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4). MCP tool
names are owned by [`006-MCP-Surface.md`](006-MCP-Surface.md) and
[`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md);
this spec only requires that their egress flows through the common seam.

## 5. Acceptance criteria

The docs-and-share contract is satisfied when:

- docs mode preserves the read-only curated presentation and adds
  fidelity/world-input/runner/frame-binding chips, a view-arg schema
  table, test and visual/a11y status summaries, and sparse evidence
  excerpts that link into Inspector/Xray;
- docs never becomes a debugging log or a marketing page;
- evidence excerpts are a curated pointer into the evidence spine, not a
  second evidence renderer;
- all share/export/copy flows go through the common egress redaction seam
  before leaving the process, once `rf2-qarwq` lands;
- redacted shared artifacts state whether they remain fully reproducible,
  and redaction is visible and explains what was removed;
- agent/MCP egress uses the same redaction policy as human egress, with no
  bypass and no second artifact model;
- until the egress seam lands, share-current-state is treated as a
  privacy-sensitive command rather than presented as already safe.

## Cross-references

| Concern | Source |
|---|---|
| Product contract + T4 safe-sharing tension | [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) |
| Current docs pane | [`008-Docs-Mode.md`](008-Docs-Mode.md) |
| Static build (`story:build`) | [`013-Static-Build.md`](013-Static-Build.md) |
| Share URL + embed code | [`005-SOTA-Features.md`](005-SOTA-Features.md) |
| Run-result + evidence projection | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Evidence-spine display | [`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md) |
| Data classification + privacy | [`../../../spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md), [`../../../spec/Security.md`](../../../spec/Security.md) |
| Story MCP write-surface gating | [`006-MCP-Surface.md`](006-MCP-Surface.md), [`../../story-mcp/spec/003-Write-Surface-Gating.md`](../../story-mcp/spec/003-Write-Surface-Gating.md) |
