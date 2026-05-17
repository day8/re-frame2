# Causa — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — Causa, the re-frame2 devtools panel; the five canonical questions an app's programmer should answer in seconds.
- **[001-Causality-Graph.md](001-Causality-Graph.md)** — Causality graph as a peer panel — the deeper-walk view when a cascade spans frames or 30+ events.
- **[002-Time-Travel.md](002-Time-Travel.md)** — Bottom-rail time-travel scrubber: walk history without disturbing the live app; rewind is explicit and confirmed.
- **[003-Machine-Inspector.md](003-Machine-Inspector.md)** — Stately-quality state-chart per registered machine, embedded via `tools/machines-viz/`.
- **[004-App-DB-Diff.md](004-App-DB-Diff.md)** — Slice-centric (not tree-centric) `app-db` panel: only the slices that changed this epoch, plus pinned watches.
- **[005-Schema-Timeline.md](005-Schema-Timeline.md)** — Temporal surface for silent schema-violation traces from Spec 010 + Spec 009.
- **[006-Hydration-Debugger.md](006-Hydration-Debugger.md)** — SSR hydration-mismatch debugger: renders the structured Spec 011 emissions no other JS devtool surfaces.
- **[007-UX-IA.md](007-UX-IA.md)** — Typography, colour tokens, animation timings, keyboard maps, density gradients — the pixels-that-feel-right reference.
- **[008-Embedding-Contract.md](008-Embedding-Contract.md)** — Per-panel `Panel` component contract so Story (and others) can embed Causa surfaces outside Causa's own chrome.
- **[011-Launch-Modes.md](011-Launch-Modes.md)** — In-app true-inline host and standalone-via-MCP remote-attach.
- **[012-Views.md](012-Views.md)** — Views tab: three-group layout (mounted / re-rendered / unmounted); subs nested under each view row with return values; cluster-large-grids (≥ 50 same-identity-key); heatmap mode; per-component inline drilldown with props-diff headline. Replaces the pre-rewrite Subscriptions panel — subs are nested under their views, not a separate tab.
- **[013-Trace-Bus.md](013-Trace-Bus.md)** — The trace-bus + collector contract: the ring-buffer data plane every panel reads from, the consumer-side filter algebra, and the `:sensitive?` privacy gate.
- **[014-Registry-Catalogue.md](014-Registry-Catalogue.md)** — Normative enumeration of every `:rf.causa/*` subscription, event, effect, and instrumentation callback Causa registers (~155 ids), grouped by owning panel.
- **[015-Configuration.md](015-Configuration.md)** — `configure!` entry-point contract: every accepted key, default, and the `:rf/causa` app-db slot it drives.
- **[016-Auxiliary-Panels.md](016-Auxiliary-Panels.md)** — Per-tab content contract for tabs beyond the hero 4-layer chrome (event-detail, issues-ribbon, routes content, flows content): inputs (subs / events consumed), main interactions, observable outputs (rf2-3lduz).
- **[017-Test-Coverage-Matrix.md](017-Test-Coverage-Matrix.md)** — Browser-feature coverage matrix for every Causa panel/feature: user-visible contract, required testbed affordance, direct and failure paths, 20-event/load re-check, diagnostics, owning gate, and current status.
- **[018-Event-Spine.md](018-Event-Spine.md)** — The architectural core: 4-layer chrome (ribbon · event list · tab bar · detail panel), spine sub `:rf.causa/focus`, 6-tab inventory, ribbon anatomy, IN/OUT filter pills, frame-observation isolation invariants, Settings modal popup, Causality popover, keyboard map, data-classification rendering contract. Reading order: read this FIRST for the architectural shape, then the per-tab specs (003 Machines, 004 App-db, 012 Views, 016 Event/Issues, 013 Trace) for content.
- **[API.md](API.md)** — Consolidated user-facing reference: installation, configuration, public surface; per-area specs are normative.
- **[Principles.md](Principles.md)** — Causa-specific load-bearing principles (read-only-by-default, etc.); cites framework `Principles.md` where they overlap.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The 13 direction-setting decisions: question, options, pick, why, date locked.
- **[findings/](findings/)** — Exploratory working substrate (Causa UX/UI design, 10x-v2 blank-slate design); audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor *why*; the capability docs (001–016) are normative — each owns its surface and is independent of the others bar explicit cross-references. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated reference. `findings/` preserves the audit lineage and is never normative.
