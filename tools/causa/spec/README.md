# Causa — Spec

This folder is the **vision-of-record** for Causa. It describes the full
destination: the surfaces Causa will eventually offer, the bug-classes
each surface answers, the chrome they live in. Where v1 ships only part
of a surface, the spec says "v1 ships X; **future:** Y" — and **Y is the
main read**.

## Files

### Read these first (the architectural spine)

- **[000-Vision.md](000-Vision.md)** — The claim. Causa shows you what
  happens when an event fires. The five canonical questions; the audience;
  the "two doors" split (Causa = human; re-frame2-pair-mcp = AI); the 7-tab
  inventory.
- **[018-Event-Spine.md](018-Event-Spine.md)** — The architectural core:
  the 4-layer chrome (ribbon · event list · tab bar · detail panel), the
  spine sub `:rf.causa/focus`, the 7-tab inventory, the popover invocation
  contract, the data-classification rendering contract. Reading order:
  read THIS after 000-Vision, then per-tab specs.
- **[019-Cross-Cutting-Insight.md](019-Cross-Cutting-Insight.md)** — The
  **5 idioms × 4 areas** matrix. How Causa accommodates SSR, Machines,
  Routes, Managed-Effects without growing tabs. The bug-class catalogue
  for each area. Sequenced PR plan (Phase 1–5).

### Per-tab content specs

- **[002-Time-Travel.md](002-Time-Travel.md)** — Time-travel scrubber:
  passive scrubbing rebases panels; explicit `r` rewinds the runtime; six
  named restore failures surface as a modal. Pins survive ring-buffer
  age-out. Future: branch-and-explore; "find me when path P last changed"
  walker.
- **[003-Machine-Inspector.md](003-Machine-Inspector.md)** — The Machines
  tab. **Event-driven Runtime panel** (rf2-y9xmf): BLANK when the focused
  event has no machine activity; per-machine section when it did
  (topology + transition highlight + guards + actions + cancellation
  cascade + `:after` rings). Cross-cutting Causa surfaces:
  `:after`-timer countdown rings, `:invoke-all` join inspector,
  cancellation-cascade visualiser, per-instance "why am I stuck" trace.
  UC1 Sim + UC2 Mode A/B/C dynamic-instance UI preserved as Static
  re-host reference below the §STATIC RE-HOST REFERENCE divider
  (rf2-r4nao — deferred). ELK+SVG primitive Causa-internal. **The bug
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
  embed contract so Story (and others) can mount the entire Causa
  shell as a right-hand-side observability surface; state isolation
  via the `:rf/causa` frame-provider.
- **[011-Launch-Modes.md](011-Launch-Modes.md)** — In-app true-inline
  host and standalone-via-MCP remote-attach.
- **[012-Views.md](012-Views.md)** — Views tab: three-group layout
  (mounted / re-rendered / unmounted); subs nested under each view row;
  cluster-large-grids; per-component inline drilldown. Replaces the
  legacy Subscriptions panel.
- **[013-Trace-Bus.md](013-Trace-Bus.md)** — The trace-bus + collector
  contract: the ring-buffer data plane every panel reads from, the
  consumer-side filter algebra, the `:sensitive?` privacy gate. Future:
  trace fattening to enable context-at-position (Phase 5 prereq for
  per-instance replay).
- **[014-Registry-Catalogue.md](014-Registry-Catalogue.md)** — Normative
  enumeration of every `:rf.causa/*` subscription, event, effect, and
  instrumentation callback Causa registers (~155 ids), grouped by owning
  panel.
- **[015-Configuration.md](015-Configuration.md)** — `configure!`
  entry-point contract. v1 ships ~5 keys; future: full 30+ keys
  (auto-hide filters, theme, retained-epochs, keybindings, factory-reset,
  ns-aliases, etc.).
- **[016-Auxiliary-Panels.md](016-Auxiliary-Panels.md)** — Per-tab
  content contract for the Event tab, Issues ribbon, Routes content,
  Flows content. Future: wire-boundary diff per managed fx;
  `:on-match` event chain; pending-navigation card; route-chain
  visualiser; head model inspector; retry timeline; full 6-section
  Settings popup (Keybindings, Buffer, Popout, Actions in addition to
  v1's 4).
- **[017-Test-Coverage-Matrix.md](017-Test-Coverage-Matrix.md)** —
  Browser-feature coverage matrix. Future: bug-class coverage column
  ensures every bug-class in spec has at least one test-row.

### Reference

- **[API.md](API.md)** — Consolidated user-facing reference: installation,
  configuration, public surface.
- **[Principles.md](Principles.md)** — Causa-specific load-bearing
  principles (read-only-by-default, etc.).
- **[Conventions.md](Conventions.md)** — Causa's reserved namespaces,
  IDs, etc.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The direction-setting
  decisions: question, options, pick, why, date locked.
- **[findings/](findings/)** — Exploratory working substrate; audit
  lineage, not normative.

## How to use this spec

1. **Read [`000-Vision.md`](000-Vision.md) first.** Anchors the claim
   ("Causa shows you what happens when an event fires") and the five
   canonical questions.
2. **Read [`018-Event-Spine.md`](018-Event-Spine.md) next** for the
   chrome architecture — the 4-layer + spine + 7 tabs + popovers.
3. **Read [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md)
   third** for the matrix of features across the four cross-cutting areas
   (SSR · Machines · Routes · Managed-Fx).
4. **Then per-tab specs (003 Machines, 004 App-db, 006 Hydration, 012
   Views, 013 Trace, 016 Event/Issues)** for the specific surfaces. Each
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
> **Insight Causa provides:** what the user SEES that resolves the mystery.
> **Affordance:** the UI surface + interaction model.

When in doubt, add the bug; don't add the feature. The spec is auditable
against this rule.
