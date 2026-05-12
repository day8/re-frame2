# Causa (re-frame-10x v2) — Spec

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
- **[009-AI-CoPilot.md](009-AI-CoPilot.md)** — Pull-only Q&A and slash-command surface: never narrates, never authors code, always cites verifiable runtime data.
- **[010-MCP-Server.md](010-MCP-Server.md)** — Separate `tools/causa-mcp/` jar exposing Causa's surfaces as MCP tools for AI agents.
- **[011-Launch-Modes.md](011-Launch-Modes.md)** — In-app overlay (`Ctrl+Shift+C`) and standalone-via-MCP remote-attach.
- **[012-Subscriptions.md](012-Subscriptions.md)** — Subscription panel: the canonical sub-status badge taxonomy (fresh / re-running / invalidated / cached-no-watcher / error) and the one-click invalidation-chain affordance ("why did this sub re-run?").
- **[API.md](API.md)** — Consolidated user-facing reference: installation, configuration, public surface; per-area specs are normative.
- **[Principles.md](Principles.md)** — Causa-specific load-bearing principles (read-only-by-default, etc.); cites framework `Principles.md` where they overlap.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — The 13 direction-setting decisions: question, options, pick, why, date locked.
- **[findings/](findings/)** — Exploratory working substrate (Causa UX/UI design, 10x-v2 blank-slate design); audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor *why*; the capability docs (001–011) are normative — each owns its surface and is independent of the others bar explicit cross-references. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated reference. `findings/` preserves the audit lineage and is never normative.
