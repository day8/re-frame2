# DESIGN-RATIONALE

Direction-setting decisions for Machines-Viz-MCP, captured as
locks: **question, options, pick, why, date locked.** Locks here
are decisions that survived the design discussion and shouldn't
be re-asked without a fresh signal (a runtime gap surfaces, a
sibling MCP server flips its posture, the upstream Machines-Viz
schema relaxes).

Inheritance from upstream
[Machines-Viz DESIGN-RATIONALE](../../machines-viz/spec/DESIGN-RATIONALE.md)
and sibling
[Causa-MCP DESIGN-RATIONALE](../../causa-mcp/spec/DESIGN-RATIONALE.md)
+ [Pair2-MCP DESIGN-RATIONALE](../../pair2-mcp/spec/) is cited
rather than duplicated. The principles below are what
Machines-Viz-MCP adds on top.

## Summary table

| # | Lock | Date | Source |
|---|---|---|---|
| 1 | Separate jar from the upstream component | 2026-05-13 | rf2-x50eu (Machines-Viz scaffold), pinned for the MCP side by the umbrella `tools/README.md` sketch. |
| 2 | Architecture template inherited from pair2-mcp + causa-mcp | 2026-05-13 | Cross-MCP symmetry posture — same shadow-cljs / Node / persistent-nREPL / bencode / port-discovery stack. |
| 3 | Closed-set read-only catalogue; no `eval-cljs` escape valve in v1 | 2026-05-13 | Sibling causa-mcp ships `eval-cljs` to escape the catalogue; Machines-Viz-MCP deliberately omits it — the read-only surface is small enough to enumerate fully. |
| 4 | Share/export inherits the tightened upstream schema with no relaxation | 2026-05-14 | rf2-li3o4 (upstream schema tightening), rf2-epiao (pinned here pre-impl). |
| 5 | Wire-pipeline contract pinned pre-implementation | 2026-05-14 | rf2-epiao — privacy default-drop + `elide-wire-value` walker + four reserved indicator slots baked into the spec before the first tool lands so impl is born compliant. |
| 6 | Two-namespace split: `day8.re-frame2-machines-viz-mcp.*` (Node) vs `day8.re-frame2-machines-viz.runtime` (browser) | 2026-05-13 | Mirrors causa-mcp Lock #11 and the pair2-mcp `re-frame-pair2-mcp.*` / `re-frame-pair2.runtime` split. |
| 7 | `:origin :machines-viz-mcp` tag on every effect that rides the trace bus | 2026-05-13 | Cross-MCP convention — same shape pair2-mcp and causa-mcp use. |

## Lock #1 — Separate jar from the upstream component

**Question.** Ship the agent-facing MCP surface inside
`day8/re-frame2-machines-viz` or as a separate
`day8/re-frame2-machines-viz-mcp` jar?

**Options.**

- (a) Single jar — `MachineChart` component + MCP server in one
  artefact. One less coord to install; agent loaders pull the
  React / charting runtime they'll never render.
- (b) Two jars — component in `day8/re-frame2-machines-viz`, MCP
  in `day8/re-frame2-machines-viz-mcp`. Matches the Causa /
  Causa-MCP and Story / Story-MCP shape. Agents install only what
  they need; classpath stays clean.

**Pick.** (b). Two jars.

**Why.** The MCP server is a Node-side artefact attached over
nREPL; pulling Reagent / React / charting into the agent's
classpath is dead weight. The separation is the same pattern
sibling MCP servers use; agents that learn one install workflow
recognise the next. Bundle-isolation also makes downstream `tools/`
audits trivial — nothing under `implementation/` may `:require`
from `tools/machines-viz-mcp/` (umbrella `tools/README.md`).

**Locked.** 2026-05-13 (scaffold-time, rf2-x50eu).

## Lock #2 — Architecture template inherited from pair2-mcp + causa-mcp

**Question.** Reinvent the MCP server architecture, or inherit
the baseline pair2-mcp + causa-mcp already pin?

**Options.**

- (a) Reinvent — different transport, different framing, different
  port-discovery strategy. Tailored to Machines-Viz's query
  shape.
- (b) Inherit — same `@modelcontextprotocol/sdk`
  `StdioServerTransport`, same bencode-pinned nREPL bridge, same
  port-discovery walk, same degraded-boot policy, same MCP
  verb-naming conformance.

**Pick.** (b). Inherit.

**Why.** The architecture is the boring infrastructure; the
catalogue is the interesting part. Reinventing transport for a
third MCP server burns the cross-MCP symmetry budget — an
implementer reading three servers' source should see the same
shape three times. The catalogue (list / fetch / encode-share-url
/ export / subscribe / meta) is what differs.

**Locked.** 2026-05-13.

## Lock #3 — Closed-set read-only catalogue; no `eval-cljs` escape valve in v1

**Question.** Ship `eval-cljs` parity with causa-mcp, or leave the
catalogue closed-set?

**Options.**

- (a) Closed-set only — every tool enumerated in the catalogue;
  no escape valve. Agents that need a query not yet catalogued
  file a `bd` bead.
- (b) Closed-set + `eval-cljs` escape valve — the catalogue covers
  90% of workflows; `eval-cljs` is the deliberate escape for the
  remaining 10%, with side-effects tagged `:origin
  :machines-viz-mcp` on the trace bus.

**Pick.** (a) for v1. Re-evaluate at v1.1.

**Why.** Machines-Viz-MCP's read surface is narrow:
machine-registry enumeration + snapshot reads + share-URL
encoding + export. The escape-valve pressure that justified
`eval-cljs` for causa-mcp (a deep debugging surface) is absent
here — the catalogue is the surface. Keeping the v1 closed-set
also forces explicit `bd` beads when an agent hits a gap, which
surfaces the gap to the maintainer in a way an `eval-cljs`
workaround silently buries.

**Locked.** 2026-05-13.

## Lock #4 — Share/export inherits the tightened upstream schema with no relaxation

**Question.** Should the MCP wrapper around the share-URL
encoder add an opt-in flag that re-introduces runtime `:data` or
`:source-coords` for agent workflows that "would benefit from the
full snapshot"?

**Options.**

- (a) Allow opt-in flags — `:include-snapshot-data? true`,
  `:include-source-coords? true`. Agent caller decides per call.
- (b) No relaxation — the MCP wrapper inherits the upstream
  tightened schema verbatim. If an agent needs `:data` off-box,
  it goes through a *separate* MCP tool subject to the
  wire-pipeline contract (`elide-wire-value` walker,
  `:include-large?` opt-in).

**Pick.** (b). No relaxation.

**Why.** The upstream schema tightening (PR #1086 / rf2-li3o4)
was deliberate — share URLs are PR-paste-able artefacts; runtime
`:data` and absolute source paths are not. An MCP opt-in that
re-introduces them would silently exfiltrate session data through
the share boundary, defeating the upstream lock. The principle
sits in
[`Principles.md` §Share / export inherits the upstream tightened schema](Principles.md);
the enforcement lives in
[`004-Wire-Pipeline.md` §Share-URL + export](004-Wire-Pipeline.md).

**Locked.** 2026-05-14 (rf2-epiao pinned this pre-impl).

## Lock #5 — Wire-pipeline contract pinned pre-implementation

**Question.** Bake the off-box egress contract (privacy
default-drop, `elide-wire-value` walker, four reserved indicator
slots, conformance tests) into the spec before any tool ships, or
let the first tool implementation discover the right shape?

**Options.**

- (a) Discover at impl time — write the first tool, see what shape
  the wire contract takes, codify post-hoc.
- (b) Pin pre-impl — write the wire contract now in
  `004-Wire-Pipeline.md`, including the conformance-test list, so
  the first tool implementation is **born compliant** with no
  retrofit cost.

**Pick.** (b). Pin pre-impl.

**Why.** The wire-contract shape is already known — pair2-mcp and
causa-mcp pinned it; reinventing it here risks an inadvertent
gap. Worse, the cost of a *missed* gap is asymmetric — a tool
that ships forwarding raw snapshots leaks session data on every
agent call. Pinning the contract now means a future impl bead
can't accidentally cut a corner the wire pipeline forbids.

**Locked.** 2026-05-14 (rf2-epiao).

## Lock #6 — Two-namespace split: `day8.re-frame2-machines-viz-mcp.*` (Node) vs `day8.re-frame2-machines-viz.runtime` (browser)

**Question.** Single namespace root for everything, or split the
Node-side server from the browser-side injected runtime?

**Options.**

- (a) Single root — one ns hierarchy spans both sides; the build
  system decides what ships where.
- (b) Two roots — Node-side concerns under
  `day8.re-frame2-machines-viz-mcp.*`; browser-side injected
  runtime under `day8.re-frame2-machines-viz.runtime` (parented
  by the upstream component's root because the runtime rides its
  preload).

**Pick.** (b). Two roots.

**Why.** Mirrors causa-mcp Lock #11 and the pair2-mcp
`re-frame-pair2-mcp.*` / `re-frame-pair2.runtime` split. The
split makes "which side does this code go?" a one-token answer
for reviewers — the namespace root is the tie-breaker. It also
makes bundle isolation a simple `:require`-graph audit: nothing
under `implementation/` may `:require` from
`day8.re-frame2-machines-viz-mcp.*`, and the browser-side runtime
is the only ns Closure DCE needs to keep when production builds
elide the MCP surface.

**Locked.** 2026-05-13.

## Lock #7 — `:origin :machines-viz-mcp` tag on every effect that rides the trace bus

**Question.** Tag MCP-initiated effects, leave them untagged, or
roll a per-tool sub-tag?

**Options.**

- (a) Untagged — agent-initiated effects look identical to
  in-app effects in trace consumers.
- (b) Single tag — every MCP-initiated effect carries `:origin
  :machines-viz-mcp`. Trace consumers (Causa, Story, forwarders)
  can filter / colour / count them.
- (c) Per-tool sub-tags — `:origin :machines-viz-mcp/subscribe`,
  `:origin :machines-viz-mcp/discover-app`, etc.

**Pick.** (b). Single tag.

**Why.** Matches the convention pair2-mcp and causa-mcp use; an
agent that learned to filter `:origin :causa-mcp` in Causa
recognises `:origin :machines-viz-mcp` immediately. Per-tool
sub-tags would add cardinality without changing what consumers
do with the axis — the binary "is this from an agent?" is the
load-bearing question, and a single tag answers it.

**Locked.** 2026-05-13.

## See also

- [Causa-MCP DESIGN-RATIONALE](../../causa-mcp/spec/DESIGN-RATIONALE.md) — twelve sibling locks; the architecture template several of the locks above inherit from.
- [Pair2-MCP DESIGN-RATIONALE](../../pair2-mcp/spec/) — the original MCP-server lock set.
- [Machines-Viz DESIGN-RATIONALE](../../machines-viz/spec/DESIGN-RATIONALE.md) — the upstream component's locks (share-URL tightening is the most consequential here).
