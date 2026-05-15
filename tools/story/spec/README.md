# Story (`day8/re-frame2-story`) — Spec

## Files

- **[000-Vision.md](000-Vision.md)** — What re-frame2-story is for, what it deliberately isn't, and how it relates to the framework's normative [`spec/007-Stories.md`](../../../spec/007-Stories.md).
- **[001-Authoring.md](001-Authoring.md)** — Registration surface: the seven `reg-*` macros, EDN-first variant contract, inclusion-tag vocabulary, source-coord stamping, macro-time validation.
- **[002-Runtime.md](002-Runtime.md)** — Per-variant frame allocation; args-precedence resolution; decorator composition; four-phase loader lifecycle; `run-variant` / `reset-variant` / `watch-variant` / `snapshot-identity` / `destroy-variant!`.
- **[003-Render-Shell.md](003-Render-Shell.md)** — The UI: sidebar, canvas, controls, workspaces, scrubber, trace; five workspace layouts; hot-reload decorator fingerprinting; `mount-shell!` / `unmount-shell!` / `active-shell`.
- **[004-Assertions.md](004-Assertions.md)** — The seven canonical `:rf.assert/*` events with record-don't-throw semantics; play-sequence execution; `force-fx-stub` decorator.
- **[005-SOTA-Features.md](005-SOTA-Features.md)** — Layout-debug overlays; a11y axe-core panel; per-variant QR share; multi-substrate side-by-side; Causa epoch embed stub; production elision under `:advanced`.
- **[006-MCP-Surface.md](006-MCP-Surface.md)** — The boundary between Story and `tools/story-mcp/`; surfaces Story exposes for the MCP jar; late-bind `reg-story-panel` for tooling embeds.
- **[007-Mode-Tabs.md](007-Mode-Tabs.md)** — The render-shell's top-of-canvas `:dev` / `:docs` / `:test` switcher; the chrome-level primitive every 2026 component playground ships (rf2-9hc8).
- **[008-Docs-Mode.md](008-Docs-Mode.md)** — The `:docs` mode pane — read-only AutoDocs-equivalent: header, prose, args, decorators, parameters, tags for the active variant (rf2-rodx).
- **[009-Test-Mode.md](009-Test-Mode.md)** — The `:test` mode pane — in-canvas aggregated test runner: status pill, per-row pass/fail, collapsible failure detail, Re-run button (rf2-qmjo).
- **[010-Toolbar.md](010-Toolbar.md)** — The chrome-level toolbar that exposes registered `reg-mode` tuples as toggle chips above the three-pane layout; Storybook-8 theme/viewport/locale parallels via the optional `:axis` slot (rf2-p0mv).
- **[011-Actions-Panel.md](011-Actions-Panel.md)** — The chronological per-variant log of user-action dispatches + dispatch-shaped fx-handled emits; filters the variant's trace buffer to the two channels that answer "what did the user do?" (rf2-5yriz).
- **[012-Trace-Scrubber-Cross-Ref.md](012-Trace-Scrubber-Cross-Ref.md)** — The cross-reference between the scrubber (time-travel) and the trace panel (six-domino cascades); scrubbing to an epoch filters the trace panel to events at-or-before that epoch and highlights the cascade whose post-effects produced it (rf2-sxwvf).
- **[013-Static-Build.md](013-Static-Build.md)** — `story:build` — Story's equivalent of Storybook 8's `storybook build` / Histoire's `histoire build` / Ladle's `ladle build`; compiles the playground to a static HTML directory deployable to GitHub Pages, Netlify, Vercel, or raw S3 (rf2-8wgpm).
- **[014-Chrome-Features.md](014-Chrome-Features.md)** — Normative coverage for three previously code-only shipped chrome surfaces: the schema-validation panel (rf2-dvue), the sidebar tag-as-badge affordance (rf2-nwiwr), and the first-visit help overlay (rf2-381i). Captures vocabulary, registration slots, persistence, and what v1 deliberately leaves to implementation (rf2-pl1zt).
- **[015-Test-Coverage.md](015-Test-Coverage.md)** — Auditable Story feature test-coverage matrix: user-visible contracts, required testbed affordances, direct/failure/20-event paths, diagnostics, owning gates, and current covered/partial/missing status (rf2-wxgwx).
- **[API.md](API.md)** — Consolidated public surface for `day8/re-frame2-story`: every `reg-*`, every fn, every fx-id, every cofx-id.
- **[Principles.md](Principles.md)** — Story-specific non-negotiables — EDN-first first among them — the test new features pass against.
- **[DESIGN-RATIONALE.md](DESIGN-RATIONALE.md)** — WHY each major call was made; the seven rf2-m6tu §6 decisions plus Phase-2 SOTA additions and IMPL-SPEC-emergent calls.
- **[findings/](findings/)** — Feature-set research and SOTA refinement working substrate; audit lineage, not normative.

## How to use

This folder is complete enough to one-shot the tool. Read [`000-Vision.md`](000-Vision.md) first to anchor scope and the relationship to framework [`spec/007-Stories.md`](../../../spec/007-Stories.md); the capability docs (001–015) are normative — Stage 2 implements 001, Stage 3 implements 002, and so on. [`Principles.md`](Principles.md) and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) capture the locks. [`API.md`](API.md) is the consolidated public-surface reference. `findings/` preserves the audit lineage and is never normative.
