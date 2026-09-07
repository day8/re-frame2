# Xray — spec

This folder is the vision-of-record for Xray. It describes the full
destination: the surfaces Xray will eventually offer, the bug-classes
each surface answers, the chrome they live in. Where v1 ships only part
of a surface, the spec says "v1 ships X; future: Y" — and Y is the
main read.

## Files

### Read these first (the architectural spine)

- [000-Vision.md](000-Vision.md) — the claim. Xray shows you what
  happens when an event fires. The 5 canonical questions; the audience;
  the "two doors" split (Xray = human; re-frame2-pair-mcp = AI); the 10-tab
  Dynamic inventory (the Issues tab was removed per rf2-gbz39 Option (c)).
- [018-Event-Spine.md](018-Event-Spine.md) — the architectural core:
  the 4-layer chrome (ribbon · event list · tab bar · detail panel), the
  spine sub `:rf.xray/focus`, the 10-tab Dynamic inventory, the popover
  invocation contract, the data-classification rendering contract. Reading
  order: read this after 000-Vision, then per-tab specs.
- [019-Cross-Cutting-Insight.md](019-Cross-Cutting-Insight.md) — the
  5 idioms × 4 areas matrix. How Xray accommodates SSR, Machines,
  Routes, Managed-Effects without growing tabs. The bug-class catalogue
  for each area. Sequenced PR plan (Phase 1–5).

### Per-tab content specs

- [002-Time-Travel.md](002-Time-Travel.md) — redirect for the removed
  standalone panel. Passive history inspection lives in the event spine;
  explicit confirmed rewind uses `:rf.xray/reset-to-epoch`. The old
  scrubber rail, pin chips and failure-modal flow do not ship.
- [003-Machine-Inspector.md](003-Machine-Inspector.md) — the Machines
  tab. Event-driven Dynamic panel (rf2-y9xmf): blank when the focused
  event has no machine activity; per-machine section when it did
  (topology + transition highlight + guards + actions + cancellation
  cascade + `:after` rings). Cross-cutting Xray surfaces:
  `:after`-timer countdown rings, `:spawn-all` join inspector,
  cancellation-cascade visualiser, per-instance "why am I stuck" trace.
  UC1 Sim + UC2 Mode A/B/C dynamic-instance UI preserved as Static
  re-host reference below the §STATIC RE-HOST REFERENCE divider
  (rf2-r4nao — landed). ELK+SVG primitive Xray-internal. The bug
  catalogue at the bottom (M.1–M.10) is the per-feature motivation.
- [004-App-DB-Diff.md](004-App-DB-Diff.md) — sectioned app-db inspector
  with inline focused-epoch diff. Future: branch-aware diff (for Story
  sim-clones); cross-frame diff; pin-two-epochs side-by-side.
- [005-Schema-Timeline.md](005-Schema-Timeline.md) — historical design
  for the removed standalone schema timeline, not a current tab.
- [006-Hydration-Debugger.md](006-Hydration-Debugger.md) — historical
  design for the removed hydration bisector. Schema and hydration issues
  use the Epoch/Trace and L2 issue surfaces; no dedicated renderer ships.
- [007-UX-IA.md](007-UX-IA.md) — typography, colour tokens, animation
  timings, keyboard maps, density gradients — the pixels-that-feel-right
  reference.
- [008-Embedding-Contract.md](008-Embedding-Contract.md) — full-shell
  embed contract so Story (and others) can mount the entire Xray
  shell as a right-hand-side observability surface; state isolation
  via the `:rf/xray` frame-provider.
- [011-Launch-Modes.md](011-Launch-Modes.md) — in-app true-inline
  host and standalone-via-MCP remote-attach.
- [012-Views.md](012-Views.md) — superseded by [021 §3](021-Dynamic-Panel-Designs.md#3-the-view-panel-reactive-perspective--steps-7-8)
  (rf2-ee38b.2). The shipped Views panel is 021 §3.2's reactive-flow graph
  plus teardown/disclosure lists; 012's three-temporal-group surface is historical
  design exploration. Read 021 §3 for the normative Views design.
- [013-Trace-Consumer.md](013-Trace-Consumer.md) — Xray's
  consumer-side contract on top of the framework's per-frame trace
  rings (Spec 009 §Per-frame trace rings): the self-noise filter,
  the privacy gate + suppressed-events counter, the small
  frameless secondary ring backing `:show-ungrouped?`, the
  task-coalesced mirror sync, and the retroactive-scrub-on-
  toggle-off behaviour. Renamed from `013-Trace-Bus.md` at
  rf2-43koh when the separate Xray ring was retired in favour of
  the framework's per-frame cascade-keyed rings. Future: trace
  fattening to enable context-at-position (Phase 5 prereq for
  per-instance replay).
- [014-Registry-Catalogue.md](014-Registry-Catalogue.md) — registry
  ownership and naming reference, principal live seams, historical
  removals, and the exact-set executable membership contract.
- [015-Configuration.md](015-Configuration.md) — `configure!`
  entry-point contract, its ten shipped keys and complete Settings shape;
  separately labelled future keys
  (auto-hide filters, theme, retained-epochs, keybindings, factory-reset,
  ns-aliases and so on).
- [016-Auxiliary-Panels.md](016-Auxiliary-Panels.md) — per-tab
  content contract for the Epoch panel (numbered cascade, supersedes
  the retired Event/Handler panel per rf2-5gl5r; carries the inline
  issue surfacing post rf2-gbz39 Option (c) — the Issues tab was
  removed), Routes content, Flows content. Future: wire-boundary diff per managed fx;
  `:on-match` event chain; pending-navigation card; route-chain
  visualiser; head model inspector; retry timeline. The shipped Settings
  popup has General, Keybindings, Buffer and Diff; earlier six-tab plans
  are explicitly historical.
- [017-Test-Coverage-Matrix.md](017-Test-Coverage-Matrix.md) —
  browser-feature coverage matrix. Future: bug-class coverage column
  ensures every bug-class in spec has at least one test-row.
- [023-Trace-Panel.md](023-Trace-Panel.md) — the Trace tab's
  dedicated redesign spec and Figma-handoff target: the complete
  trace arc of one epoch as a chronological flat list with stage labels
  (not phase-band nesting), row anatomy, the full Spec-009
  op-handling matrix (Appendix A — the completeness contract), with
  colour/visual encoding delegated to Figma (§8). Supersedes
  [021 §5](021-Dynamic-Panel-Designs.md#5-the-trace-panel-per-epoch-raw-ops)
  (v1-shipped layout) as the direction-setter.
- [024-Resources-Panel.md](024-Resources-Panel.md) — the Resources
  tab: the Xray-side consumer contract for declarative server-state
  (framework [`spec/016-Resources.md`](../../../spec/016-Resources.md)).
  The 8 panel sections (static registry · live instances · work
  ledger · route/resource graph · lifecycle timeline · invalidation graph
  · cache growth · scope audit + lints), the `:rf.resource/*` trace
  family Xray colours/filters, and the privacy posture (params/scopes
  get the same summary + size elision as data; read-only — observing
  pins no resource). No tool accessors: the five read-only resource
  accessors went with the Xray runtime seam (rf2-7htk7), and an
  out-of-process reader uses `re-frame2-pair.runtime` +
  `tools/re-frame2-pair-mcp/` instead. The panel reads registry/runtime
  data without requiring Resources itself; the Xray artifact still
  depends on Resources for its unified graph.
- [025-Derivation-Graph-Panel.md](025-Derivation-Graph-Panel.md) — the
  Derivation-Graph tab: the Xray-side consumer contract for the EP-0014
  derivation/process algebra graph (framework
  [`spec/Derivations.md`](../../../spec/Derivations.md)). Xray is the EP's
  named first consumer of the structured graph accessor: the unified
  `{:mode :nodes :edges}` view composed by `re-frame.derivation.graph`
  across all 5 algebra-view families, classified by the two closed
  superkinds (`:derivation` / `:process`), static vs live, with the
  contributor seam supplying the families Xray `:require`s. Carries the
  off-box egress redaction call site (`redact-graph-for-egress` —
  per-frame, fail-closed, via `rf/elide-wire-value`; redact value, keep
  edge): on-box rendering is raw (Security.md permits on-box), off-box
  egress projects through the frame's elision policy. Read-only.
- [026-Module-View-Panel.md](026-Module-View-Panel.md) — the
  Module-view tab: the Xray-side consumer contract for the EP-0023
  `image -> frame -> event stream` public model. Renders each live
  image-loaded frame as an execution context carrying its resolved image's
  `[kind id]` descriptors (with per-descriptor provenance); the same
  `(kind id)` resolves differently in frames running different images. A
  process not using image-loaded frames shows the honest no-image caption. (The
  retired EP-0013 app-value / runtime-realm substrate this tab once also
  surfaced — the (realm, frame) REALMS section and the per-module MODULES
  section — was deleted in full; there is no `re-frame.realm` namespace, no
  `realm-ids`, and no `re-frame.frame/frame-realm`. See framework
  [`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md).) An
  L4-only Dynamic tab (not in `panel-enum`). Read-only.
- [027-Hicasso-Evidence.md](027-Hicasso-Evidence.md) — the Hicasso tab:
  the Xray-side consumer contract for the adapter-neutral Hicasso evidence
  surface (`re-frame.hicasso.tool`, rf2-hic-023). Four of the tab's 6
  views over one versioned schema — mounted boundaries, read attribution,
  the intent stream, and explain-render — each envelope stating schema,
  producer, scope, basis, completeness and loss. The other two, Advisor
  and Causal, are derivations over those same 4 envelopes and are
  specified in 028 below. Carries the tab's honest-empty
  contract: 3 empties with 3 sentences, 5 absences with 5
  testids, and the rule the producer's door enforces — unknown is never
  encoded as an empty collection. Xray and the AI pair consume one door
  with no consumer discriminator, and the read seam passes each envelope
  through unchanged, which is what makes byte-for-byte structural rather
  than separately asserted. An L4-only Dynamic tab (not in `panel-enum`).
  Read-only, dev-only.
- [028-Hicasso-Advisor.md](028-Hicasso-Advisor.md) — the Hicasso tab's
  two derived sub-views (rf2-hic-037). The hot-view advisor ranks the
  mounted census on 4 axes in 4 units — never a composite score —
  classifies what owns the pressure, and looks the route up on the owner
  rather than on the rank. Two of Spec SN §10's 5 pressure classes have
  an instrument here and 3 do not, every native ladder rung addresses
  one of the 3, and so the advisor refuses the native ladder from
  this evidence and names the tool that settles each candidate instead;
  the refusal is asserted as a property, with a non-vacuity control
  proving the ladder is real. The causal slice walks §10's seven-link
  chain for one dispatch, carrying each link's own basis AND its join to
  the previous link as separate facts — links 2 and 3 are both
  observations and the join between them is `:uncorrelated` — with every
  evidenced link mutation-tested against its own positive control. No
  governance pin moves: sub-views of an existing tab, no new build id and
  no new port.

### Reference

- [API.md](API.md) — consolidated user-facing reference: installation,
  configuration, public surface.
- [Principles.md](Principles.md) — Xray-specific load-bearing
  principles (read-only-by-default and so on).
- [Conventions.md](Conventions.md) — Xray's reserved namespaces,
  IDs and so on.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — the direction-setting
  decisions: question, options, pick, why, date locked.
- [findings/](findings/) — exploratory working substrate; audit
  lineage, not normative.

## How to use this spec

1. Read [`000-Vision.md`](000-Vision.md) first. Anchors the claim
   ("Xray shows you what happens when an event fires") and the 5
   canonical questions.
2. Read [`018-Event-Spine.md`](018-Event-Spine.md) next for the
   chrome architecture — the 4-layer + spine + 10 Dynamic tabs + popovers.
3. Read [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md)
   third for the matrix of features across the 4 cross-cutting areas
   (SSR · Machines · Routes · Managed-Fx).
4. Then per-tab specs (003 Machines, 004 App-db, 006 Hydration, 012
   Views, 013 Trace, 016 Auxiliary panels) for the specific surfaces. Each
   is independent of the others bar explicit cross-references.

Where v1's
shipping surface and the spec's destination differ, the spec wins as the
direction-setter; v1's staged delivery is called out in "v1 ships X;
future: Y" markers in the per-tab specs.

## Bug-driven design discipline

Every feature in this spec is motivated by a concrete bug-class. The
uniform structure:

> **Bug class:** what the user is staring at when the question forms.
> **Example bug:** the vignette — "you dispatched X, expected Y, got Z because W."
> **Insight Xray provides:** what the user SEES that resolves the mystery.
> **Affordance:** the UI surface + interaction model.

When in doubt, add the bug; don't add the feature. The spec is auditable
against this rule.
