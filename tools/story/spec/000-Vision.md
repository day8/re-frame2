# Story — Vision

> What re-frame2-story is for, what it deliberately isn't, and how it
> relates to the framework's normative [`spec/007-Stories.md`](../../../spec/007-Stories.md).
> This document is the orientation read; the numbered capability docs
> in this folder are the engineering contract Stages 2–8 commit against.

## Relationship to the framework spec

The framework's normative spec for stories lives at
[`spec/007-Stories.md`](../../../spec/007-Stories.md). That document
locks the registration grammar, the three-way Story / Variant /
Workspace split, the canonical id grammar, the seven `:rf.assert/*`
events, the snapshot-identity contract, and the variant-as-data rule.

`tools/story/spec/` (this folder) sits *below* the framework spec. It
is the implementation-flavoured contract for the
`day8/re-frame2-story` artefact — the engineering decisions the
implementation work commits against. Where Spec 007 is intentionally
open ("Mike to decide"), this folder locks it. Where the framework
spec is normative, this folder is downstream + implementation-flavoured.

When this folder and Spec 007 disagree, **Spec 007 wins**.

## What re-frame2-story is

re-frame2-story is the **component-development tool** for re-frame2
apps. It takes the primitives the rest of re-frame2 already exposes —
frames, events, subscriptions, effects, schemas, traces, epochs — and
arranges them as a Storybook-class interactive playground. The unit of
design is the three-way split locked in
[Spec 007 §The three concepts](../../../spec/007-Stories.md):

- **Story** — a topic / component / slice. Shared fixtures.
- **Variant** — a concrete scenario of a story; renders the story in a
  specific state.
- **Workspace** — a layout that arranges stories and variants on screen
  for browsing, documentation, or comparison.

Story sits on top of [Spec 002 — Frames](../../../spec/002-Frames.md):
each variant *is* a frame. Story sits on top of
[Spec 010 — Schemas](../../../spec/010-Schemas.md): controls
auto-derive from registered view schemas. Story sits on top of
[Spec 009 — Instrumentation](../../../spec/009-Instrumentation.md):
the trace panel consumes `register-trace-cb!` against the variant's
frame. Story sits on top of
[Spec 008 — Testing](../../../spec/008-Testing.md): `run-variant` is
the test-runner ingress for stories used as tests.

Story owns **no new framework primitives.** Every registry it uses
(`:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`,
`:decorator`) registers via existing `reg-*` machinery. This is
required by the
[downstream-EPs-consume-foundation](../../../AGENTS.md) discipline.

## What re-frame2-story is for

- **Visual development.** Iterate on a component in isolation; see
  every state side-by-side; flip between substrates (Reagent, UIx,
  Helix, reagent-slim) when the view is substrate-portable.
- **Test fixtures.** A `:test`-tagged variant *is* a complete component
  test; `(run-variant id)` returns
  `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` —
  exactly what a `deftest` needs.
- **Documentation.** A `:docs`-tagged variant is included in the
  generated docs page for that story; story tool reads `:doc` +
  schemas to emit an auto-docs table.
- **Agent input.** The MCP surface (separate jar; see
  [`006-MCP-Surface.md`](006-MCP-Surface.md)) exposes the registry to
  AI agents so they can discover components, generate variants, run
  tests, and self-correct.
- **Visual-regression keying.** Every variant has a content-hashed
  `snapshot-identity`; downstream pixel-diff services (Chromatic,
  Percy, Argos, BackstopJS) key against `[variant-id content-hash]`
  and skip unchanged variants in O(1).

## What re-frame2-story intentionally isn't

- **A visual-regression service.** Story ships the `snapshot-identity`
  hook and emits stable iframes; pixel capture and diff happen
  downstream.
- **A reimplementation of Causa.** Story embeds Causa's epoch
  panel (Causa is the structural successor to re-frame-10x; see
  [`tools/causa/spec/DESIGN-RATIONALE.md`](../../causa/spec/DESIGN-RATIONALE.md)
  Lock #1) as a registered story panel (see
  [`003-Render-Shell.md`](003-Render-Shell.md) and
  [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §causa-embed). The two
  artefacts share the epoch buffer.
- **A statechart visualisation engine.** Story ships a one-line
  current-state indicator for active machines; full chart rendering
  lives in a future `day8/re-frame2-machines-viz` artefact (per
  Phase 1 §6.8) which exposes a `reg-story-panel` adapter Story
  consumes.
- **An MCP server in-process.** The agent surface is a separate jar
  with its own stdio + JSON-RPC machinery. Story's runtime exposes the
  *data* the MCP server reads; the MCP server is not loaded by app
  code. See [`006-MCP-Surface.md`](006-MCP-Surface.md).
- **A static-site generator.** Deferred to v2.
- **A pixel-scrubbing UI.** BackstopJS-style before-after slider is
  out of scope; the epoch scrubber inside Causa's embedded panel is
  the equivalent in re-frame2's data-space.

## Research lineage

Two pieces of committed research informed the design; both live in
[`findings/`](findings/):

- **Phase 1.** [`findings/re-frame-2-story-feature-set.md`](findings/re-frame-2-story-feature-set.md)
  (~5,200 words). Per-tool survey of the JS workshop ecosystem
  (Storybook, Histoire, Ladle, React Cosmos, Lookbook, devcards,
  workspaces, Pattern Lab); cross-tool pattern synthesis;
  re-frame2-specific extensions; v1 feature spec; seven open design
  questions.
- **Phase 2.** [`findings/re-frame-2-story-sota-refinement.md`](findings/re-frame-2-story-sota-refinement.md)
  (~5,300 words). Independent SOTA refinement against Phase 1;
  identified six additions Phase 1 missed (live perf ribbon,
  layout-debug trio, mode primitive, variants-grid layout,
  design-token panel, QR sharing).

Architectural decisions resolved 2026-05-11 (Mike-delegation); each is
documented in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md).

## Cross-references

| Concern | Source |
|---|---|
| Normative spec | [`spec/007-Stories.md`](../../../spec/007-Stories.md) |
| Frame primitive | [`spec/002-Frames.md`](../../../spec/002-Frames.md) |
| Testing surface | [`spec/008-Testing.md`](../../../spec/008-Testing.md) |
| Trace API | [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) |
| Schemas → argtypes | [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) |
| Snapshot-identity schema digest | [`spec/011-SSR.md`](../../../spec/011-SSR.md) |
| Registration grammar | [`spec/001-Registration.md`](../../../spec/001-Registration.md) |
| Reserved prefixes | [`spec/Conventions.md`](../../../spec/Conventions.md) |
| Tool-Pair primitives | [`spec/Tool-Pair.md`](../../../spec/Tool-Pair.md) |
| Tools layout | [`tools/README.md`](../../README.md) |
| Authoring (this folder) | [`001-Authoring.md`](001-Authoring.md) |
| Runtime (this folder) | [`002-Runtime.md`](002-Runtime.md) |
| Render shell (this folder) | [`003-Render-Shell.md`](003-Render-Shell.md) |
| Assertions (this folder) | [`004-Assertions.md`](004-Assertions.md) |
| SOTA features (this folder) | [`005-SOTA-Features.md`](005-SOTA-Features.md) |
| MCP surface (this folder) | [`006-MCP-Surface.md`](006-MCP-Surface.md) |
| Design principles | [`Principles.md`](Principles.md) |
| Public API | [`API.md`](API.md) |
| Why each call was made | [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) |
