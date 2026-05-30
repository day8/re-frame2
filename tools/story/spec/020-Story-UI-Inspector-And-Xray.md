# Story UI — Inspector and Xray

> The Story Inspector region: the canonical Story/Xray ownership boundary
> (stated once, here), the Xray per-panel embed, the Story-to-Xray focus
> API consumption (`rf2-crtmq`), the evidence-spine **display**, the
> Explain panel over `story/explain` data, and the Story/Xray visual seam.
> Inspector is a composition region, not a competing diagnostics engine.

## Builds on

- [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) — the
  product contract, the top-level ownership split (§5), and the visual
  hierarchy / evidence visual model (§12). This spec carries the deep
  Inspector contract.
- [`003-Render-Shell.md`](003-Render-Shell.md) — owns the right-hand
  per-panel Xray embed (mount lifecycle, chip-row picker, pop-out). This
  spec composes that embed; it does not redefine the mount contract.
- [`017-Testing-Story.md`](017-Testing-Story.md) — the `story/explain`
  data API, the epoch-tape evidence projection, and the run-result
  coordinates the focus links consume. **Source of truth for the
  substrate.**
- [`../../xray/spec/007-UX-IA.md`](../../xray/spec/007-UX-IA.md) — Xray's
  panel inventory and the event spine.
- [`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
  — the full-shell embedding/state-isolation contract **and** the
  host-facing focus API (`rf2-crtmq`).

## Supersedes

- The "embedded inspector" language scattered across earlier Story shell
  docs ([`003-Render-Shell.md`](003-Render-Shell.md),
  [`005-SOTA-Features.md`](005-SOTA-Features.md)) is consolidated here:
  the Story/Xray boundary is now stated **once** in §1 and referenced
  elsewhere. No Xray-owned behaviour is superseded — Xray owns its panel
  interiors unchanged.

## Depends on

- The Xray host-facing focus API (`rf2-crtmq`, CURRENT) for the
  Story-to-Xray focused links; the **consumption** of that API is
  Story-owned (explicitly so per
  [`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
  §"Host-facing focus API" §Status).
- The substrate's two-level narrative projection (script spans over epoch
  beats) for the evidence-spine display — BLOCKED until
  [`017-Testing-Story.md`](017-Testing-Story.md) lands that projection.
- The `story/explain` base data contract (CURRENT where 017 is present)
  for the Explain panel.

## Out of scope

- Xray's panel interiors, diff engine, and time-travel — owned by Xray
  (see [`../../xray/spec/`](../../xray/spec/README.md)). Story embeds;
  it does not rebuild.
- Evidence-spine **result linkage** (which run-result row drives which
  span) — owned by
  [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md)
  §2. This spec owns evidence-spine **display**.
- Controls and view-state fidelity —
  [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md).
- Docs-mode evidence excerpts and sharing —
  [`022-Story-UI-Docs-And-Share.md`](022-Story-UI-Docs-And-Share.md).

## Status labels

This spec uses the Story UI status labels (CURRENT / TARGET / BLOCKED /
SUPERSEDES / FUTURE / OUT) defined in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md)
§"Normative language".

## 1. The Story/Xray ownership boundary (stated once)

Story pressure: S4, S9, S10.

This is the single canonical statement of the Story/Xray boundary. The
other Story UI specs reference this section rather than restating it.

### 1.1 Story owns

- Story tree, variants, workspaces, matrices, saved failure entries.
- Canvas composition around the selected variant.
- Controls for args, view state, setup summaries, route, network,
  effects, and runner policy.
- Fidelity badges and lower-fidelity warnings.
- Docs mode as curated variant documentation.
- Test mode as proof/results UX.
- Evidence spine: script spans over epoch beats (display: §3).
- Explain panel: source chain, merges, substitutions, runner
  requirements, and lowering decisions (§4).
- Visual and a11y assertion presentation.
- Promotion from run artifact to named variant.

### 1.2 Xray owns

- App-db diffing and the diff engine.
- Views/subscription invalidation panels.
- Trace and epoch detail panels.
- Machine, routing, and issues inspectors.
- Time-travel and full diagnostic shell behaviour.
- Xray diagnostic source chips, redaction markers, and panel-local state.

Story MUST embed/share Xray for detailed diagnostics. Story MUST NOT
build a separate detailed inspector with State/Effects/Subs/Renders/
Schemas/Trace tabs that duplicates Xray's panels. Two independent
debugging UIs over the same tape would drift in diff semantics, redaction
markers, source chips, and attribution rules.

### 1.3 Human UI, MCP, and skill mirroring

Story pressure: S9.

Human-visible Story operations are the product source of truth. Story MCP
and the Story-related skills MAY expose gated, structured, or lower-level
versions, but they MUST NOT invent a second artifact model.

The exact MCP tool names are owned by
[`006-MCP-Surface.md`](006-MCP-Surface.md) and
[`../../story-mcp/spec/002-Tool-Registry.md`](../../story-mcp/spec/002-Tool-Registry.md).
The names below are current examples or the intended equivalent
operation; the official MCP specs reconcile them against the registry.

| Human operation | Agent/MCP equivalent | Notes |
|---|---|---|
| Preview/render selected variant | `preview-variant` / render equivalent | Uses the same selected-artifact/render path where available. |
| Run selected variant | `run-variant` | Returns the same status/result/evidence model the UI presents. |
| Inspect failures/results | `read-failures` / result-read APIs | Structured, redacted, linkable to evidence/Xray. |
| Explain variant | `story/explain` / explain tool | Same source-chain/merge/lowering vocabulary as the Explain panel. |
| Save current state as variant | gated register/update tool | Allowed only when the state projection is representable and write gates pass. |
| Promote run artifact/failure | gated promote/register tool | Distinct from save-current-state. |
| Open/focus Xray context | open/focus reference | Precise focus uses the Story-to-Xray focus API (§2.1). |
| Share/export/copy artifact | egress-gated read/copy tool | Must use the same redaction/elision policy as human egress. |

Asymmetries are allowed only when explicit: write operations are gated,
attached-frame operations require frame binding, redaction MAY remove data
an agent asks for, and internal diagnostic reads MAY remain non-product
surfaces.

## 2. Inspector composition and the Xray embed

Story pressure: S4, S6, S8, S9, S10.

Inspector is a composition region. It MUST contain: the Xray panel embed;
the evidence spine (§3); the Explain panel (§4); test/result detail focus
when in Test mode; and optional raw EDN/projection panes for copying and
agent use.

The Story right-hand Xray embed is owned by
[`003-Render-Shell.md`](003-Render-Shell.md) and uses Xray's panel
inventory from
[`../../xray/spec/007-UX-IA.md`](../../xray/spec/007-UX-IA.md).
[`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
is the full-shell/state-isolation contract and explicitly limits
host-facing props; it is **not** the sole owner of per-panel embedding.

The Xray embed MUST preserve the locked Story panel list:

- `:epoch`
- `:app-db`
- `:views`
- `:trace`
- `:machines`
- `:routing`
- `:issues`

The embed MUST:

- mount one Xray panel at a time;
- expose a chip-row picker;
- expose pop-out to the full Xray shell;
- keep Xray keybindings from swallowing Story command-palette keys;
- use the Xray mount lifecycle and state-isolation frame;
- show explicit empty states when no variant is selected or Xray is
  unavailable.

Story MAY add context around the embed: the selected variant, the focused
script span, a fidelity badge, and "open this beat in Xray" commands.

### 2.1 Story-to-Xray focus (the `rf2-crtmq` focus API)

Opening / popping out the full Xray shell is CURRENT. **Focusing** a
specific Xray panel, epoch, cascade, or app-db path is TARGET StoryUI
wiring over the existing host-facing focus API.

The focus API is closed and CURRENT (`rf2-crtmq`): the entry point is
`day8.re-frame2-xray.core/focus!`, a small one-way focus command
documented in
[`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md)
§"Host-facing focus API". Story builds the command (which panel, which
epoch/cascade, which path) plus opaque `:source` provenance and calls
`focus!`; Xray owns what each field means and routes it to the canonical
`:rf.xray/*` write surfaces. The **consumption** of this API — wiring
narrative beats, assertion rows, canvas inspect commands, and docs/test
links to call `focus!` — is Story-owned (explicitly so per that spec's
§Status) and is the TARGET work this section locks.

Story MUST NOT introduce a second Xray runtime model; every focus is a
thin composer over Xray's existing write surfaces.

## 3. Evidence spine (display)

Story pressure: S4, S6, S8, S9.

The evidence spine is the answer to T1
([`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §4), not a
decorative panel. It is TARGET/BLOCKED by the substrate's narrative
projection.

This section owns evidence-spine **display**; result linkage (which
run-result row drives the selected span) is owned by
[`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md)
§2.

The spine MUST be reachable from failed assertions, result rows, docs
excerpts, promotion flows, agent handoffs, and explicit diagnostic
commands. If those entry points are slow or obscure, the no-fourth-mode
decision SHOULD be re-reviewed before alpha.

When a selected run has failures, Test mode SHOULD make the evidence spine
the main body of the failure investigation, with result rows and assertion
detail driving the selected span. This is how evidence can be primary
during debugging without becoming a fourth top-level mode.

It SHOULD render:

- the script-span list;
- epoch beats under each span;
- compact summaries for db, effects, schemas, sub-runs, renders, and
  trace;
- source links;
- links that open Xray and focus the matching Xray panel (§2.1) once
  StoryUI has the result/evidence coordinates for that row.

The spine MUST label evidence strength:

- **direct epoch evidence**: `:db-before`, `:db-after`, effects, trace
  events;
- **attributed evidence**: post-settle/back-filled `:sub-runs` and
  `:renders` where applicable.

The spine MUST define non-dispatch step spans before rendering them as
causally equivalent to dispatch spans. `[:wait-until ...]`,
`[:click ...]`, `[:focus ...]`, and `[:assert ...]` MAY have spans without
committed epochs.

The spine MUST NOT require users to read raw trace data to answer normal
questions (what event caused this, what changed in app-db, what effects
were emitted, which subscription recomputed, which view rendered, which
schema failed, why an assertion failed). Raw trace remains available in
Xray for expert debugging.

## 4. Explain panel

Story pressure: S4, S8.

The `story/explain` data API is CURRENT where
[`017-Testing-Story.md`](017-Testing-Story.md) and the Story plan
compiler are present. The Explain panel UI is Story-owned and TARGET. It
is a net-new Story surface over explain data and does **not** depend on
Xray.

It MUST show:

- source chain;
- parent chain;
- composed fragments/checks;
- merge order;
- strict-conflict winners and losers;
- final `:world`, `:script`, `:expect`;
- arg substitutions;
- network lowering;
- sub-override lowering;
- runner requirements and the selected runner.

It MAY later show plan-hash inputs and evidence projections used by the
result, but those are beyond the current `story/explain` base contract and
MUST NOT be treated as shipped explain data.

It SHOULD provide a raw-EDN view and copy-to-clipboard for human reading
and agent use.

## 5. Story/Xray visual seam

Story pressure: S1, S7, S10.

The full visual quality bar for the seam lives in
[`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) §12.9; this
section locks the ownership rules.

- Story owns the surrounding card, chip row, panel title, and pop-out
  affordance.
- Xray owns the panel interior and diagnostic colour semantics.
- Shared statuses (pass/fail/cannot-run/error) use one tool-wide colour
  vocabulary.
- Redaction markers and source chips SHOULD come from shared tool tokens,
  not per-panel styling.
- The embed seam MUST NOT read as two unrelated products jammed together.
  Do not restyle Xray panel interiors to look like Story — align tokens
  and spacing instead.

## 6. Acceptance criteria

The Inspector and Xray contract is satisfied when:

- the Story/Xray boundary is stated once (§1) and the other Story UI
  specs reference it without restating;
- Xray is embedded for detailed diagnostics and not duplicated — no second
  app-db/views/trace/schema/epoch inspector competes with Xray;
- the Xray embed preserves the locked seven-panel list, mounts one panel
  at a time, exposes the chip-row picker and pop-out, and shows explicit
  empty states;
- Story-to-Xray focus is wired over the closed `rf2-crtmq` focus API and
  is presented as TARGET wiring, not as already-shipped behaviour;
- the evidence spine is reachable from failed assertions, result rows,
  docs excerpts, promotion flows, and agent handoffs, and labels direct
  vs attributed evidence honestly;
- the Explain panel renders the full `story/explain` source-chain / merge
  / lowering vocabulary over CURRENT explain data, with a raw-EDN escape
  hatch, and is not conflated with the `story/explain` data API itself;
- the Story/Xray seam reads as one product.

## Cross-references

| Concern | Source |
|---|---|
| Product contract + ownership split + visual seam | [`018-Story-UI-North-Star.md`](018-Story-UI-North-Star.md) |
| Right-hand Xray per-panel embed | [`003-Render-Shell.md`](003-Render-Shell.md) |
| `story/explain` + evidence projection | [`017-Testing-Story.md`](017-Testing-Story.md) |
| Xray panel inventory + event spine | [`../../xray/spec/007-UX-IA.md`](../../xray/spec/007-UX-IA.md) |
| Xray embedding + focus API (`rf2-crtmq`) | [`../../xray/spec/008-Embedding-Contract.md`](../../xray/spec/008-Embedding-Contract.md) |
| Story MCP boundary | [`006-MCP-Surface.md`](006-MCP-Surface.md), [`../../story-mcp/spec/002-Tool-Registry.md`](../../story-mcp/spec/002-Tool-Registry.md) |
| Evidence-spine result linkage | [`021-Story-UI-Test-And-Evidence.md`](021-Story-UI-Test-And-Evidence.md) |
| Controls + view-state fidelity | [`019-Story-UI-Controls-And-View-States.md`](019-Story-UI-Controls-And-View-States.md) |
