# Story — Design Principles

> The non-negotiables that guided every architectural call in this
> folder. When a contributor sketches a new feature or fix, these
> principles are the test it passes against. The numbered capability
> docs are the *what*; this document is the *why-must*.
> [`Conventions.md`](Conventions.md) catalogues the *how-named* — the
> reserved namespaces, id grammars, macro/`*`-fn split, chrome-installer
> pair shape, and token-banning rules that operationalise these
> principles.

## EDN-first

**Every variant body is plain data; no fn-slots; no closures.**

The variant artefact contract from
[spec/007 §Variant artefact contract](../../../spec/007-Stories.md)
is more restrictive than Storybook's CSF Factories (which still
permit inline JSX in `:render`). The data-only constraint is
load-bearing:

- Variants round-trip across the network — the MCP server reads them
  as EDN, downstream visual-regression services key against the
  canonical form.
- Variants are AI input — agents read the variant body to generate
  similar bodies; closures in the body would defeat that.
- Variants survive process boundaries — the agent surface
  (CLJ-side) reads variants the same way the browser does (CLJS-side).

The one closure permitted at the registration site is
`reg-decorator`'s `:wrap` slot — but it lives at *decorator
registration*, not in the variant body. Variant bodies reference
decorators by id; the closure is registered once and shared. The
"variant body is pure data" rule is preserved.

## No fn-slots in user-facing registration bodies

A corollary of EDN-first, but worth stating explicitly. None of:

- `:render <fn>` (the CSF escape hatch)
- `:setup <fn>` (where a function would replace `:events` /
  `:loaders`)
- `:assertion <fn>` (where a function would replace
  `:rf.assert/*`)

If a user is reaching for a fn-slot, the right answer is "register a
new event, decorator, or assertion id, then reference it from data."
This keeps the registry the single source of truth.

## Production elision strict

**No Story code reaches a `:advanced` build of a production app.**

The sentinel pattern under `:advanced` is documented in
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision.
Compile-time elision via `goog-define :rf.story/enabled?` collapses
every Story `reg-*` macro to `nil` and lets Closure DCE remove the
implementation namespaces wholesale.

This is a hard rule. The bundle-isolation sentinel in
`scripts/check-bundle-isolation.cjs` is the CI gate.

## No new framework registries

Story owns **no new framework primitives.** Every registry it uses
(`:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`,
`:decorator`) registers via the existing `reg-*` machinery, which the
framework's [spec/001](../../../spec/001-Registration.md) describes.

This satisfies the
[downstream-EPs-consume-foundation](../../../AGENTS.md) discipline
and the [`feedback_downstream_eps_consume_foundation`](../../../AGENTS.md)
directive: downstream EPs beyond 000/002 must not add new registries,
dispatch types, effect substrates, or component substrates.

## Reagent for the v1 UI shell

The UI shell that Story renders (sidebar, canvas, controls, trace
panel, embedded Causa panel) is built with Reagent at v1, sourced from
`implementation/adapters/reagent/`. Reasoning lives in
[`003-Render-Shell.md`](003-Render-Shell.md) §UI shell substrate; the
short version is:

- Reagent is stable; reagent-slim is still landing.
- Dogfood-neutrality — the UI exercises the same primitives stories
  exercise.
- Cheap to revisit at Stage 8 once reagent-slim hits GA.

## re-com-scoped surface

The story tool's chrome is built with re-com primitives where they
exist (boxes, splitters, dropdowns) rather than rolling bespoke
widgets. This keeps the surface area small, the styling consistent,
and the design-token integration trivial when the design-token panel
lands at v1.1.

## EDN round-trip = AI input

The same canonical EDN form a human author writes is the form an
agent reads via `variant->edn`. The MCP server's `get-variant` tool
returns exactly this form (plus a structured-content JSON
projection). This shared form is what makes the agent's
write→run→read-failures→fix loop work; if humans and agents read
different shapes, the loop falls apart.

## Tool-Pair, not in-process MCP

The agent surface (MCP) ships as a separate jar; the Story core
carries no stdio / JSON-RPC weight. Story exposes the *read*
primitives; the MCP jar packages them. The boundary is documented in
[`006-MCP-Surface.md`](006-MCP-Surface.md). The two-jar split mirrors
the convention from `tools/machines-viz/` vs.
`tools/machines-viz-mcp/`.

## Embed, don't reimplement

For the epoch panel (Causa — the structural successor to
re-frame-10x, per
[`tools/causa/spec/DESIGN-RATIONALE.md`](../../causa/spec/DESIGN-RATIONALE.md)
Lock #1), the chart visualisation (machines-viz, future), and any
other peer artefact: **embed via `reg-story-panel`; don't
reimplement.** The [`003-Render-Shell.md`](003-Render-Shell.md)
§Panel registration contract — five rules — is the embed protocol.

This keeps the maintenance surface bounded: Causa's UX evolves in
Causa, not in Story; machines-viz evolves in its own jar.

## Record, don't throw

Assertion failures **record** into the variant's `:assertions` list
and continue the play sequence. They do not throw. This aligns with
re-frame's run-to-completion drain semantics, makes failure
collection complete (not "first failure halts everything"), and
mirrors devcards' behaviour. Storybook's choice to throw is
constrained by JavaScript's async-throw mess; we have no such
constraint. See [`004-Assertions.md`](004-Assertions.md) and
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §record-not-throw.

## Additive contract

- Adding a story never breaks an app.
- Removing a story never breaks the harness.
- Renaming a story id is a deprecation, not a breaking change.

This follows from
[spec/007](../../../spec/007-Stories.md)'s library-owned `:story.*`
prefix and the registrar's open-set semantics. The story-tool URL
surface is additive too — it does not co-opt application routes.

## JVM interop preserved

Per the
[`feedback_jvm_interop_must_work`](../../../AGENTS.md)
directive, Story preserves re-frame2's interop layer so tests run on
the JVM. The CLJC files in Story's runtime cover the JVM-side
registrar slice; the MCP server's JVM-standalone host reads only
that slice (CLJS-only state like substrates / a11y violations returns
empty + a documented hint).

## File spec beads, don't silently work around

Per the
[`feedback_file_spec_beads_for_implementation_findings`](../../../AGENTS.md)
directive: when implementation surfaces a spec gap or inconsistency,
file a `bd` bead against the spec — don't silently work around. The
seven open design questions Phase 1 surfaced (§6 of the feature-set
findings) were filed and resolved through this process; future
surface gaps follow the same pattern.

## No core.async

Per the
[`feedback_no_core_async`](../../../AGENTS.md) directive: Story does
not pull `core.async` as a dependency or use it as a building block.
The async return-shape for `run-variant` is either a CLJS Promise or
a `manifold.deferred` (Stage 3 picks). Loaders use re-frame's
existing drain machinery — `dispatch-sync` plus the
`:loaders-complete-when` predicate — not channels.
