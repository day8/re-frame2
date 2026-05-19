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

### One primitive beats a parade of single-purpose addons

The discipline above pays off most visibly in `force-fx-stub` (see
[`005-SOTA-Features.md`](005-SOTA-Features.md) §`force-fx-stub`).
Storybook ships a separate addon for each thing you want to fake —
MSW for HTTP, custom decorators for analytics, shim libraries for
storage, more addons for websockets and navigation. Story uses
**one** decorator that stubs any handler you registered with
`reg-fx`. Same shape for HTTP, websockets, analytics, geolocation,
storage. No addon-per-concern.

This is the design lens Story applies wherever a Storybook
integration is really "give me a seam I can hijack." Story has the
seam already — frames, decorators, registered effects — so the
"addon" collapses to a three-line decorator citation in the variant
body.

## What re-frame2-story is for

- **Visual development.** Iterate on a component in isolation; see
  every state side-by-side; flip between substrates (Reagent, UIx,
  Helix) when the view is substrate-portable.
- **Test fixtures.** A `:test`-tagged variant *is* a complete component
  test; `(run-variant id)` returns
  `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` —
  exactly what a `deftest` needs. The `:test` mode pane drives play
  sequences interactively with a Storybook-class **play step-debugger**
  (step / pause / rewind / step-back / breakpoint) — the canvas
  re-renders against each step's app-db, so author and reviewer can
  watch the variant evolve one event at a time (see
  [`009-Test-Mode.md` §Play step-debugger](009-Test-Mode.md#play-step-debugger-rf2-ulw5m)).
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

## Privacy posture (path-level data classification — Spec 015)

Story participates in the framework's path-level data-classification
contract ([spec/015-Data-Classification.md](../../../spec/015-Data-Classification.md)).
Story is **not** a separate observation surface that needs its own
classification machinery — it consumes the framework's existing
sentinels (`:rf/redacted`, `:rf/large`) and propagation graph at the
boundaries it owns.

The posture is normative across Story's surfaces:

1. **Authoring — variant authors may declare path-marks per frame.**
   Each variant *is* a frame (per [§Relationship-with-frames](../../../spec/007-Stories.md))
   and a variant's body MAY include `(re-frame.core/reg-marks
   <variant-id> {:sensitive [[paths]] :large [[paths]]})` to declare
   per-frame `app-db` marks (per
   [spec/015 §2. App-db (per frame) — `reg-marks`](../../../spec/015-Data-Classification.md#2-app-db-per-frame--reg-marks)).
   The `:loaders` / `:events` / `:play` registrations on a variant
   continue to accept `:sensitive` / `:large` on their registration
   maps via the standard registration grammar
   (per [spec/001 §Registration grammar](../../../spec/001-Registration.md)).
   No Story-specific declaration grammar — Story uses the framework's.
2. **Display contract — canvas and Causa-RHS render sentinels.** The
   canvas itself never observes raw `app-db` (the variant view does);
   but the diagnostic surfaces Story embeds — the docs / test mode
   panes, the Causa-RHS chip-row panels (`:app-db`, `:event-detail`,
   `:trace`, `:machines`, `:views`, `:routing`, `:issues`), the
   schema-validation pane — render `:rf/redacted` per spec/015
   §Display contract. Causa is the in-tree consumer; the contract is
   "render the sentinel; do NOT offer a click-to-expand affordance
   that reveals the underlying value" (per
   [spec/015 §The display contract](../../../spec/015-Data-Classification.md#the-display-contract--sentinels)).
3. **Assertion vocabulary — path-marked args resolve to sentinels.**
   The seven `:rf.assert/*` events (per
   [`004-Assertions.md`](004-Assertions.md)) build assertion records
   whose `:actual` / `:expected` / `:payload` slots pass through
   `re-frame.elision/elide-wire-value` at record-build time. An
   assertion of `:rf.assert/path-equals [:auth :token] :rf/redacted`
   against a path-marked-sensitive slot records `:actual :rf/redacted`,
   NOT the raw value — the assertion records ARE an observation
   surface and obey the same redaction contract as the trace bus.
   See [`004-Assertions.md`](004-Assertions.md) §Privacy + the
   `Assertion-with-redaction` row in
   [`015-Test-Coverage.md`](015-Test-Coverage.md) §Assertion
   vocabulary scenarios.
4. **Error-projection records — same posture.** The
   `:rf.error/exception` projection record (per
   [`002-Runtime.md`](002-Runtime.md) §Error projection) honours
   event-level `:sensitive` declarations and passes `ex-data` through
   `re-frame.elision/elide-wire-value` before the record lands in
   `:assertions`. Exception messages are NOT auto-walked — that's the
   spec/Security.md §Author guidance for exceptions under path-level
   `:sensitive?` rule (rf2-dv79m). See
   [`002-Runtime.md`](002-Runtime.md) §Error projection §Privacy.
5. **MCP read surface — Story core returns marks-as-data; MCP jar
   owns wire elision.** Story's core (`re-frame.story` and the
   tool-owned registry side-table) returns the registered bodies and
   per-frame snapshots unchanged — marks travel with the data as
   declarative metadata. The wire-elision substitution to
   `:rf/redacted` happens at the MCP jar's egress boundary
   ([`tools/story-mcp/`](../../story-mcp/)) per
   [spec/015 §3. MCP wire transport](../../../spec/015-Data-Classification.md#in-scope--the-five-observation-points-marks-must-guard)
   and [`tools/mcp-base/spec/elision.md`](../../mcp-base/spec/elision.md).
   This split keeps Story's read surface composable (a future
   in-process consumer needs raw values; an off-box agent gets the
   sentinels).
6. **Recorder + dispatch-console — path-level redaction extends the
   existing `:rf/redacted` placeholder.** The recorder (per
   [`005-SOTA-Features.md`](005-SOTA-Features.md)
   §Recorder / test codegen) already honours event-level
   `:sensitive?` by dropping the event payload (per
   [spec/Security.md §Recorder redact-but-record](../../../spec/Security.md);
   per rf2-hdadz). With path-level marks, the recorder additionally
   substitutes `:rf/redacted` at arg-paths the elision registry
   resolves on the recorded event-vector — narrower than the
   event-level drop, more useful for replay. The dispatch console
   (the `:trace/show-sensitive?` knob in `configure!`) gates whether
   the on-box devtool surfaces the underlying values when set true
   (per [spec/Security.md §`include-sensitive?` vs `show-sensitive?`
   verb split](../../../spec/Security.md)).
7. **Snapshot-identity content-hash computes over real values.** The
   `snapshot-identity` content-hash (per
   [`002-Runtime.md`](002-Runtime.md) §Snapshot-identity computation)
   hashes the canonical form of the variant's render-relevant inputs
   *before* any sentinel substitution. Two reasons: (a) the
   visual-regression services keying on the hash need stability under
   real values — a sentinel-substituted hash would change every time
   the elision registry's path set changed; (b) the snapshot is
   computed in-process where the consumer (Story's identity helper)
   already operates on real values. The hash itself never leaves the
   process unredacted; if a downstream tool emits the hash plus the
   inputs that produced it, the inputs go through the wire-elision
   walker per §5 above.

**Knob — the event-level `:trace/show-sensitive?` flag.** The legacy
event-level `:sensitive?` flag (declared on `reg-event` per
[spec/009 §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md))
remains valid and Story honours it. The
`configure! {:trace/show-sensitive? true}` knob is the on-box dev
override (`show-sensitive?` verb, on-box UI visibility per
[spec/Security.md §`include-sensitive?` vs `show-sensitive?` verb
split](../../../spec/Security.md)). Off-box wire egress (the MCP jar)
gates on the parallel `include-sensitive?` verb and defaults to
suppress. The two knobs do NOT collide — they govern different
surfaces.

**What Story is NOT.** Story does not invent its own classification
vocabulary, its own sentinel set, or its own propagation rules.
Anywhere Story's contract appears silent on privacy, the framework's
spec/015 contract applies — Story is downstream consumer, not an
independent declaration site.

Cross-references:

| Concern | Source |
|---|---|
| Path-marking grammar | [spec/015-Data-Classification.md](../../../spec/015-Data-Classification.md) |
| Propagation rules | [spec/015 §Propagation rules](../../../spec/015-Data-Classification.md#propagation-rules) |
| Exception-path residual | [spec/Security.md §Author guidance for exceptions under path-level `:sensitive?`](../../../spec/Security.md) (rf2-dv79m) |
| Event-level `:sensitive?` (legacy / parallel) | [spec/009 §Privacy / sensitive data in traces](../../../spec/009-Instrumentation.md) |
| Wire-elision walker | [spec/API.md §elide-wire-value](../../../spec/API.md) |
| MCP-side elision | [`tools/mcp-base/spec/elision.md`](../../mcp-base/spec/elision.md) + [`tools/mcp-base/spec/sensitive.md`](../../mcp-base/spec/sensitive.md) |
| Knob verb split | [spec/Conventions.md §Privacy config-knob naming](../../../spec/Conventions.md) |
| Error projection (Story-side) | [`002-Runtime.md`](002-Runtime.md) §Error projection §Privacy |
| Assertion-side redaction | [`004-Assertions.md`](004-Assertions.md) §Privacy |

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
