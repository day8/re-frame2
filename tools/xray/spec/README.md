# Xray — Spec

This folder is the **vision-of-record** for Xray. It describes the full
destination: the surfaces Xray will eventually offer, the bug-classes
each surface answers, the chrome they live in. Where v1 ships only part
of a surface, the spec says "v1 ships X; **future:** Y" — and **Y is the
main read**.

## Files

### Read these first (the architectural spine)

- **[000-Vision.md](000-Vision.md)** — The claim. Xray shows you what
  happens when an event fires. The five canonical questions; the audience;
  the "two doors" split (Xray = human; re-frame2-pair-mcp = AI); the 9-tab
  Dynamic inventory (the Issues tab was removed per rf2-gbz39 Option (c)).
- **[018-Event-Spine.md](018-Event-Spine.md)** — The architectural core:
  the 4-layer chrome (ribbon · event list · tab bar · detail panel), the
  spine sub `:rf.xray/focus`, the 9-tab Dynamic inventory, the popover
  invocation contract, the data-classification rendering contract. Reading
  order: read THIS after 000-Vision, then per-tab specs.
- **[019-Cross-Cutting-Insight.md](019-Cross-Cutting-Insight.md)** — The
  **5 idioms × 4 areas** matrix. How Xray accommodates SSR, Machines,
  Routes, Managed-Effects without growing tabs. The bug-class catalogue
  for each area. Sequenced PR plan (Phase 1–5).

### Per-tab content specs

- **[002-Time-Travel.md](002-Time-Travel.md)** — Time-travel scrubber:
  passive scrubbing rebases panels; explicit rewind (the Epoch-panel
  button → `:rf.xray/reset-to-epoch`) rewinds the runtime; six named
  restore failures surface as a modal. (The `r` rewind *key* was trimmed
  under rf2-f7748x — see [007-UX-IA.md §Trimmed pending demand](007-UX-IA.md);
  the rewind feature itself ships.) Future: branch-and-explore; "find me
  when path P last changed" walker.
- **[003-Machine-Inspector.md](003-Machine-Inspector.md)** — The Machines
  tab. **Event-driven Dynamic panel** (rf2-y9xmf): BLANK when the focused
  event has no machine activity; per-machine section when it did
  (topology + transition highlight + guards + actions + cancellation
  cascade + `:after` rings). Cross-cutting Xray surfaces:
  `:after`-timer countdown rings, `:spawn-all` join inspector,
  cancellation-cascade visualiser, per-instance "why am I stuck" trace.
  UC1 Sim + UC2 Mode A/B/C dynamic-instance UI preserved as Static
  re-host reference below the §STATIC RE-HOST REFERENCE divider
  (rf2-r4nao — deferred). ELK+SVG primitive Xray-internal. **The bug
  catalogue at the bottom (M.1–M.10) is the per-feature motivation.**
- **[004-App-DB-Diff.md](004-App-DB-Diff.md)** — Slice-centric (not
  tree-centric) app-db panel. Future: branch-aware diff (for Story
  sim-clones); cross-frame diff; pin-two-epochs side-by-side.
- **[005-Schema-Timeline.md](005-Schema-Timeline.md)** — Per-schema
  timeline; empty→non-empty flash; full Malli explanation in detail.
- **[006-Hydration-Debugger.md](006-Hydration-Debugger.md)** — The
  hydration mismatch bisector. Hero SSR feature. Side-by-side server vs
  client with sub-attribution + likely-cause hypothesis. Future
  sections: server error projection trace; payload-policy verdict; head
  model inspector; per-request frame teardown auditor; streaming SSR
  boundary timeline; side-by-side SSR replay (post-v1 dream).
- **[007-UX-IA.md](007-UX-IA.md)** — Typography, colour tokens, animation
  timings, keyboard maps, density gradients — the pixels-that-feel-right
  reference.
- **[008-Embedding-Contract.md](008-Embedding-Contract.md)** — Full-shell
  embed contract so Story (and others) can mount the entire Xray
  shell as a right-hand-side observability surface; state isolation
  via the `:rf/xray` frame-provider.
- **[011-Launch-Modes.md](011-Launch-Modes.md)** — In-app true-inline
  host and standalone-via-MCP remote-attach.
- **[012-Views.md](012-Views.md)** — _Superseded by [021 §3](021-Dynamic-Panel-Designs.md#3-the-view-panel-reactive-perspective--steps-7-8)
  (rf2-ee38b.2)._ The shipped Views panel is 021 §3's lean three-stacked-tables
  design; 012's richer three-temporal-group surface is unimplemented historical
  design exploration. Read **021 §3** for the normative Views design.
- **[013-Trace-Consumer.md](013-Trace-Consumer.md)** — Xray's
  consumer-side contract on top of the framework's per-frame trace
  rings (Spec 009 §Per-frame trace rings): the self-noise filter,
  the privacy gate + suppressed-events counter, the small
  frameless secondary ring backing `:show-ungrouped?`, the
  microtask-coalesced mirror sync, and the retroactive-scrub-on-
  toggle-off behaviour. Renamed from `013-Trace-Bus.md` at
  rf2-43koh when the separate Xray ring was retired in favour of
  the framework's per-frame cascade-keyed rings. Future: trace
  fattening to enable context-at-position (Phase 5 prereq for
  per-instance replay).
- **[014-Registry-Catalogue.md](014-Registry-Catalogue.md)** — Normative
  enumeration of every `:rf.xray/*` subscription, event, effect, and
  instrumentation callback Xray registers (~155 ids), grouped by owning
  panel.
- **[015-Configuration.md](015-Configuration.md)** — `configure!`
  entry-point contract. v1 ships ~5 keys; future: full 30+ keys
  (auto-hide filters, theme, retained-epochs, keybindings, factory-reset,
  ns-aliases, etc.).
- **[016-Auxiliary-Panels.md](016-Auxiliary-Panels.md)** — Per-tab
  content contract for the Epoch panel (numbered cascade, supersedes
  the retired Event/Handler panel per rf2-5gl5r; carries the inline
  issue surfacing post rf2-gbz39 Option (c) — the Issues tab was
  removed), Routes content, Flows content. Future: wire-boundary diff per managed fx;
  `:on-match` event chain; pending-navigation card; route-chain
  visualiser; head model inspector; retry timeline; full 6-section
  Settings popup (Keybindings, Buffer, Popout, Actions in addition to
  v1's 4).
- **[017-Test-Coverage-Matrix.md](017-Test-Coverage-Matrix.md)** —
  Browser-feature coverage matrix. Future: bug-class coverage column
  ensures every bug-class in spec has at least one test-row.
- **[023-Trace-Panel.md](023-Trace-Panel.md)** — The Trace tab's
  dedicated redesign spec and **Figma-handoff target**: the complete
  trace arc of one epoch across 4 phase bands (Dispatch · Event-handling ·
  Effects/Fx · Reactive-rendering), row anatomy, the full Spec-009
  op-handling matrix (Appendix A — the completeness contract), with
  colour/visual encoding delegated to Figma (§8). Supersedes
  [021 §5](021-Dynamic-Panel-Designs.md#5-the-trace-panel-per-epoch-raw-ops)
  (v1-shipped layout) as the direction-setter.
- **[024-Resources-Panel.md](024-Resources-Panel.md)** — The Resources
  tab: the Xray-side consumer contract for declarative server-state
  (framework [`spec/016-Resources.md`](../../../spec/016-Resources.md)).
  The eight panel sections (static registry · live instances · work
  ledger · route/resource graph · lifecycle timeline · invalidation graph
  · cache growth · scope audit + lints), the `:rf.resource/*` trace
  family Xray colours/filters, the five read-only tool accessors, and the
  privacy posture (params/scopes get the SAME summary + size elision as
  data; read-only — observing pins no resource). Decoupled from the
  optional Resources artefact.
- **[025-Derivation-Graph-Panel.md](025-Derivation-Graph-Panel.md)** — The
  Derivation-Graph tab: the Xray-side consumer contract for the EP-0014
  derivation/process algebra graph (framework
  [`spec/Derivations.md`](../../../spec/Derivations.md)). Xray is the EP's
  **named first consumer** of the structured graph accessor: the unified
  `{:mode :nodes :edges}` view composed by `re-frame.derivation.graph`
  across all five algebra-view families, classified by the two closed
  superkinds (`:derivation` / `:process`), static vs live, with the
  contributor seam supplying the families Xray `:require`s. Carries the
  **off-box egress redaction call site** (`redact-graph-for-egress` —
  per-frame, fail-closed, via `rf/elide-wire-value`; redact value, keep
  edge): on-box rendering is raw (Security.md permits on-box), off-box
  egress projects through the frame's elision policy. Read-only.
- **[026-Module-View-Panel.md](026-Module-View-Panel.md)** — The
  Module-view tab: the Xray-side consumer contract for the EP-0023
  **`image -> frame -> event stream`** public model. Renders each live
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

### Reference

- **[API.md](API.md)** — Consolidated user-facing reference: installation,
  configuration, public surface.
- **[Principles.md](Principles.md)** — Xray-specific load-bearing
  principles (read-only-by-default, etc.).
- **[Conventions.md](Conventions.md)** — Xray's reserved namespaces,
  IDs, etc.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The direction-setting
  decisions: question, options, pick, why, date locked.
- **[findings/](findings/)** — Exploratory working substrate; audit
  lineage, not normative.

## How to use this spec

1. **Read [`000-Vision.md`](000-Vision.md) first.** Anchors the claim
   ("Xray shows you what happens when an event fires") and the five
   canonical questions.
2. **Read [`018-Event-Spine.md`](018-Event-Spine.md) next** for the
   chrome architecture — the 4-layer + spine + 9 Dynamic tabs + popovers.
3. **Read [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md)
   third** for the matrix of features across the four cross-cutting areas
   (SSR · Machines · Routes · Managed-Fx).
4. **Then per-tab specs (003 Machines, 004 App-db, 006 Hydration, 012
   Views, 013 Trace, 016 Auxiliary panels)** for the specific surfaces. Each
   is independent of the others bar explicit cross-references.

The 19-doc set is complete enough to one-shot the tool. Where v1's
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
