# Story-MCP — Design Rationale

> WHY each major design call was made. Why Cheshire over data.json;
> why the stage marker is independent from Story's; why the protocol
> version is pinned; why the write gate is opt-in.

## §separate-jar — why a separate artefact from Story

This rationale lives in detail at
[`tools/story/spec/DESIGN-RATIONALE.md`](../../story/spec/DESIGN-RATIONALE.md)
§separate-mcp-jar. The short version:

- Story's runtime is consumed by every Story user; the MCP
  server's stdio + JSON-RPC dependencies are consumed by none of
  them except agent hosts. Splitting at the jar boundary keeps the
  Story core lean.
- The MCP surface evolves on its own cadence — see
  §independent-stage-marker below.
- The pattern mirrors `tools/machines-viz/` vs.
  `tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md)).

## §cheshire-over-data.json — why Cheshire

JSON wire format. Cheshire is the project's existing JSON library
(`implementation/http` uses it via `requiring-resolve`). Declaring
it as a direct runtime dep here keeps the codec consistent across
the repo.

Alternatives considered:

- **`org.clojure/data.json`.** Slower; less feature-complete; would
  add a second JSON library to the project. Rejected for
  consistency.
- **Jsonista.** Faster than Cheshire on micro-benchmarks but adds a
  Jackson dependency the project doesn't yet have. The performance
  gap doesn't matter for an MCP server's frame rate (one JSON object
  per agent tool call; not a throughput-bound workload). Rejected on
  simplicity.

## §independent-stage-marker — why stage is independent from Story's

Story's own `re-frame.story/stage` advances when Story's runtime
extends (e.g. `:sota-features` after Stage 6 lands the v1 panels).
This jar carries its own `re-frame.story-mcp.config/stage = :mcp`
that advances when *its* surface extends.

The two artefacts have **independent stage progression** per
[`tools/README.md`](../../README.md)'s per-tool jar convention. A
release of Story does not force a release of Story-MCP, and vice
versa. The MCP server can ship `:mcp` at v1 while Story is still at
`:sota-features` at v1 — the constants serve different runtimes.

## §protocol-version-pin — why pin the MCP protocol version

The MCP protocol revision is pinned at `2025-06-18` (the version that
shipped at the time of Stage 7 implementation).

Rationale:

- **Predictability for agents.** An agent that successfully connects
  to one Story-MCP instance can rely on the protocol shape being
  identical at every other instance of the same version. The
  pin-via-constant makes that contract explicit.
- **Upgrade auditing.** Bumping the pin is a deliberate change with
  a corresponding commit message; the diff makes clear what the
  protocol change is. Floating-version semantics ("whatever the
  client speaks") would let drift accumulate silently.
- **Tested surface.** The protocol tests target the pinned version.
  Floating would expand the test matrix.

Future MCP versions land as a deliberate bump of the constant,
accompanied by updates to whichever methods/shapes the new version
changes. See
[`001-Wire-Protocol.md`](001-Wire-Protocol.md) §Protocol version pin.

## §write-gate-opt-in — why the write surface is gated off by default

This rationale lives in
[`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) §Why
opt-in, not default. The headlines:

1. **CI safety.** A CI run accidentally opening the write surface
   could mutate the registry mid-test. Default-off keeps CI safe.
2. **Host trust.** Some dev hosts want read-only pair-coding. Default-on
   would force-impose the wrong shape.
3. **Audit clarity.** Opening the gate is a deliberate operator
   action (CLI flag, sysprop, or env var); the presence of the flag
   in the launch command is auditable.

The clean-error-on-gated-call shape (`isError: true` with a
documented hint, not `-32601`) is documented in
[`001-Wire-Protocol.md`](001-Wire-Protocol.md) §Error codes.

## §inline-tools-string — why `get-story-instructions` is an inline string

The agent-onboarding text returned by `get-story-instructions` lives
inline as a single string in `re-frame.story-mcp.tools/story-instructions-text`.
It is not read from an external resource file at boot.

Rationale:

- **Self-contained jar.** No `io/resource` lookup at boot means
  fewer failure modes (missing resource, wrong classpath, native-image
  packaging quirks).
- **One thing to update.** The text is a single string in the source
  file; editing it is a normal source edit, reviewed in PR.
- **Small.** The string is on the order of a few KB; embedding it
  inline costs nothing.

## §no-register-story-tool — why no v1.1 `register-story` tool

Per [`002-Tool-Registry.md`](002-Tool-Registry.md) §What's NOT in the
registry. The agent can implicitly land a parent story by ordering
its variant registrations correctly (first variant carries `:doc`
etc.). A separate `register-story` tool would duplicate that surface
and add a registration ceremony agents seldom need.

When the self-healing loop matures and `register-story` proves load-bearing,
it lands. Until then, the smaller surface is preferred.

## §no-decorator-mcp — why decorators are not MCP-registrable

Decorators carry closures in the `:wrap` slot (per
[`tools/story/spec/001-Authoring.md`](../../story/spec/001-Authoring.md)
§reg-decorator §Closure caveat). JSON-RPC can't transport closures.
A workaround would be "the agent invokes a registered decorator-helper
fn by id" — but that just moves the closure to *some other dev
artefact* that has to land via human-authored code.

For v1 + v1.1, decorators are author-defined; the agent's value-add is
variant-body generation (which is pure data). A future shape might
add a `:wrap-via-event` decorator kind that's transportable; if so,
that lands in spec/007 first and Story-MCP picks it up.

## §write-helpers-shared-across-callers — why Story's `*`-suffix helpers are
not MCP-only

The `*`-suffix runtime helpers (`reg-variant*`, `reg-story*`, etc.)
that Story-MCP routes through are *also* used by:

- Hot-reload tooling that synthesises registrations.
- Fixture loaders.
- Test scaffolding that needs to land variants programmatically.

Story-MCP is one consumer of the helpers; it isn't a special
caller. The gate that distinguishes MCP from these other callers is
in this jar (`allow-writes?`), not in Story.

## Cross-references

- [`000-Vision.md`](000-Vision.md) — the orientation read.
- [`001-Wire-Protocol.md`](001-Wire-Protocol.md) — protocol details.
- [`002-Tool-Registry.md`](002-Tool-Registry.md) — the 16 tools.
- [`003-Write-Surface-Gating.md`](003-Write-Surface-Gating.md) —
  gate behaviour.
- [`API.md`](API.md) — per-tool schemas.
- [`tools/story/spec/006-MCP-Surface.md`](../../story/spec/006-MCP-Surface.md) —
  Story's side of the boundary.
- [`tools/story/spec/DESIGN-RATIONALE.md`](../../story/spec/DESIGN-RATIONALE.md)
  §separate-mcp-jar — the canonical rationale for the jar split.
