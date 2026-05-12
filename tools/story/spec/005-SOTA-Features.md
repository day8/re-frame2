# Story — SOTA Features

> The layout-debug overlay trio; a11y axe-core panel; per-variant QR
> share; multi-substrate side-by-side rendering; the 10x epoch panel
> embed contract (stub); the v1.1 deferrals (perf ribbon, design
> tokens); production elision under `:advanced` (`:rf.story/enabled?`
> sentinel pattern). The contract Stage 6 implements + the production
> hygiene rules that apply across stages.

## v1 panels (must-ship)

### a11y (axe-core) panel

axe-core integration runs against the rendered DOM:
- Inline in the canvas — violations list appears as a sidebar panel.
- CI hook — variants tagged `:a11y` (or just `:test`) run axe under
  `run-variant`; violations append to `:assertions` as
  `:rf.assert/a11y` failures.

Phase 1 §3.1 #3 names this "the cheapest win in the space."

### Six-domino trace panel

Per [`003-Render-Shell.md`](003-Render-Shell.md) §Trace bus, every
variant's frame gets a `register-trace-cb!` registration at mount.
The trace panel renders the six dominoes (event → handler → fx →
effect → subscription → re-render) live, side-by-side with the variant
pane.

This is the debugging UX no JS tool can match — re-frame's structured
trace has no JS analogue.

### Layout-debug overlay trio

DOM-mutating utilities, framework-agnostic, default-on as an opt-in
panel:

- **Measure.** Rulers + gap visualisation on hover (Storybook's
  addon-measure equivalent).
- **Outline.** Pesticide-style outline of every DOM node.
- **Pseudo-state forcing.** `:hover`, `:focus`, `:active`, `:visited`
  via class-swap; pairs with visual-regression for state-coverage
  snapshots.

Per Phase 2 §5.2 #2. Cheap to ship; framework-agnostic; all three
together are best-in-class. See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §phase-2-SOTA-additions.

### Multi-substrate side-by-side rendering

A variant declares `:substrates #{:reagent :uix :helix :reagent-slim}`
(subset, default = the host frame's adapter); the multi-substrate
pane renders each substrate side-by-side. Substrate-specific failures
render inline (per [`003-Render-Shell.md`](003-Render-Shell.md)
§Multi-substrate).

This is unique to re-frame2 — the multi-substrate goal in the EPs
makes the side-by-side rendering a natural fit; no JS tool can ship
this.

### `reg-mode` saved-tuple primitive

The Chromatic-style mode primitive (saved tuples of global args)
lands in v1 — not v1.1 — because the implementation cost is small
(it's a saved `args` map plus a snapshot-identity contribution) and
the agent-integration benefit is large (MCP can iterate variants ×
modes without combinatorial registration).

See [`001-Authoring.md`](001-Authoring.md) §reg-mode for the macro
surface and [`002-Runtime.md`](002-Runtime.md) §Args resolution
precedence for the merge order.

### `:variants-grid` workspace layout

Per Phase 2 §5.2 #4. devcards-style multi-variant viewing has no JS
competitor; the implementation cost is layout-only. See
[`003-Render-Shell.md`](003-Render-Shell.md) §Workspace layouts.

### QR code in share menu

Per Phase 2 §5.2 #6. Tiny implementation, high signal. Adds `qr-code`
dep. Surfaces the share affordance: tap the QR on a phone, get the
current variant URL with all cell-local overrides encoded.

### 10x epoch panel embed (stub + contract)

Story registers re-frame-10x's existing epoch panel as a story panel:

```clojure
(rf/reg-story-panel :rf.story/10x-epoch
  {:doc       "re-frame-10x's epoch buffer for the active variant."
   :title     "Epochs (10x)"
   :placement :bottom
   :render    :re-frame-10x.epoch-panel/view})
```

The 10x epoch view is consumed from `day8/re-frame2-causa` (per the
`tools/causa/` alpha-phase line in
[`tools/README.md`](../../README.md)). Story's panel is the
**adapter**; Causa stays its own artefact, on its own release cadence.

Story's `:rf.story/10x-epoch` registration ships with v1 but the panel
only activates if `day8/re-frame2-10x` is on the classpath (per the
late-bind hook in spec/002). If 10x is absent, the sidebar entry
hides. The 10x artefact owns the actual view; Story owns the
*integration*. See [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§10x-embed.

## v1.1 deferrals

### Live performance ribbon

FPS / INP / long tasks / CLS / memory pressure / Reagent-render
profiling at 50ms refresh; non-trivial implementation (requires
`PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Deferred to first follow-up release. Per Phase 2 §5.2 #1.

### Design-token panel

Style-Dictionary-shaped tokens emitted by upstream (re-com or host
design system) surfaced as a `reg-story-panel`. Iff upstream emits
tokens. Per Phase 2 §5.2 #5. Stage 6 ships the panel shell;
activation is conditional on token emission upstream.

### Per-variant autodocs panel polish

Autodocs panel from `:doc` + schemas + variant table — the basic
panel ships at v1; the polish (cross-link navigation, hover preview,
prose-injection points) is v1.1.

### "Open in editor" per variant

Reads the `:source` slot stamped at registration time and opens the
variant's defining file:line in the configured editor (via the editor
MCP, when available).

## Production elision under `:advanced`

### Sentinel pattern

Story uses the same PRESENT-in-control / ABSENT-in-release pattern
[Spec 009](../../../spec/009-Instrumentation.md) locks for
instrumentation:

```clojure
;; compile-time flag (CLJS goog-define)
(goog-define ^:dynamic *enabled?* true)

;; macros expand conditionally
(defmacro reg-story [id metadata]
  (if *enabled?*
    `(re-frame.story.impl/reg-story* ~id ~metadata)
    `nil))
```

Under `:advanced` compile with
`closure-defines {'re-frame.story.config/*enabled?*' false}`,
every Story registration form expands to `nil` and the entire
`re-frame.story.impl` ns becomes unreachable from the main namespace
graph — Closure DCE removes it wholesale.

### What gets DCE'd

- All variant / story / workspace / mode / panel registrations.
- The `tools.story.registry` side-table.
- The render shell (`tools.story.ui.*`).
- The trace / a11y / perf panels.
- The play-runner.
- The control-derivation logic.

### What survives

- The fn body of `re-frame.story/run-variant` (and friends), but with
  no registrations to act on it returns an empty result map. This is
  a feature: production code that *accidentally* calls `run-variant`
  does not throw, it returns empty.
- Public Var stubs (so `(:require [re-frame.story :as story])`
  doesn't break a prod build that imports a stories ns
  conditionally).

### Cross-library `:extends` under elision

All registrations are dev-only. In a production build:

- Lib A's `reg-variant` calls expand to `nil`.
- Lib B's `reg-variant :extends :A/x` calls expand to `nil` too.
- No `:extends` resolution happens at all.

There is therefore no production-build failure mode for cross-library
`:extends`. Dev builds (with `*enabled?*` true) resolve `:extends` at
registration time; an unresolved parent raises
`:rf.error/extends-unknown` synchronously.

### Verification

`scripts/check-bundle-isolation.cjs` (per
[`tools/README.md`](../../README.md)) gets a Story sentinel: any
`re-frame.story.impl.*` symbol surviving in a `:advanced` build
output is a leak. Stage 8 (examples + guide) wires the example
builds against this sentinel.

## v1 ship list (high-confidence + must-ships)

Per Phase 2 §5.1's converged ship list (12 items) + additional
phase-2 SOTA adds that are cheap.

| Item | Stage |
|---|---|
| 1. MCP server + component manifest | Stage 7 |
| 2. a11y (axe-core) panel inline + CI hook | Stage 6 |
| 3. Play-style scripted interactions + `:rf.assert/*` vocabulary | Stages 2, 5 |
| 4. External visual-regression integration via `snapshot-identity` hook | Stage 3 |
| 5. Three-level args + auto-derived controls from Spec 010 schemas | Stages 2, 4 |
| 6. MSW-shaped effect mocking via `force-fx-stub` decorator | Stage 2 |
| 7. Six-domino trace panel per variant via `register-trace-cb!` | Stage 6 |
| 8. re-frame-10x epoch panel embedded as `reg-story-panel` | Stage 6 |
| 9. Story portability — `run-variant` returns `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms}` | Stage 3 |
| 10. EDN-first variant artefact (no `:render` fn-slot, round-trippable) | Stage 2 |
| 11. Inclusion tags (seven canonical + `!`-prefix removal) | Stage 2 |
| 12. Workspace grid + transit-shareable layouts | Stage 4 |
| Layout-debug overlay trio (measure / outline / pseudo) | Stage 6 |
| `reg-mode` saved-tuple primitive | Stage 2 |
| `:variants-grid` workspace layout | Stage 4 |
| Per-variant QR code in share menu | Stage 6 |
| Multi-substrate side-by-side pane (substrate-failures inline) | Stage 6 |
| 10x epoch panel embed (stub + contract) | Stage 6 |

## v1.1 ship list (first follow-up)

| Item | Stage |
|---|---|
| Live performance ribbon (FPS, INP, long tasks, CLS, memory, Reagent profiling) | Stage 6 (deferred) |
| Design-token panel (conditional on upstream token emission) | Stage 6 (deferred) |
| MCP write surface (`register-variant`, `unregister-variant`) | Stage 7 (deferred) |
| "Open in editor" per variant | Stage 8 (post-Stage-1) |
| Per-variant autodocs panel polish | Stage 6 (deferred) |

## v2 ship list (post-1.0 deferred)

| Item | Source |
|---|---|
| Subscription topology visualiser (using `sub-topology`) | Phase 1 Tier 3 |
| Per-substrate variant filtering (deep substrate-divergence audit) | Phase 1 Tier 3 |
| Static export (render the whole story tool to a static site) | Phase 1 Tier 3 |
| Custom story panels by third parties (the v1 panels exhaust the v1 set) | Phase 1 Tier 3 |
| Remote Storybook federation (multi-host composition) | Phase 1 §2.2 |
| App-db snapshot diff (data-space visual regression) | Phase 2 §5.2 #7 |
| oEmbed URLs for Notion / static-doc inlining | Phase 2 §5.2 #6 (deferred) |
| BackstopJS-style pixel scrubber UI | Phase 2 §2.1 (out of scope; data-space scrubber via 10x suffices) |

## What we deliberately don't ship

Phase 1 §5.7 + Phase 2 §5 + the non-goals section of
[`000-Vision.md`](000-Vision.md). Each is named with rationale so
contributors have a clear "no" list. See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §Rejected for the full
list with reasoning; the short version:

- **CSF Factories (JS) — we use EDN-first.** EDN-first variant
  bodies are strictly stronger than CSF Factories.
- **First-party visual-regression service — we use the
  `snapshot-identity` hook.** Pixel capture is downstream.
- **Component-co-located fixtures (React Cosmos `.fixture.tsx`
  files).** re-frame2's registered artefacts are the canonical
  structure mechanism.
- **Statechart visualisation engine.** Deferred to
  `day8/re-frame2-machines-viz`.
- **Pixel-scrubber UI (BackstopJS slider).** Data-space scrubber via
  10x's epoch panel covers the same UX better.
- **BackstopJS-style baseline storage.** Services handle baselines.
- **First-party SSR rendering pipeline.** Owned by Spec 011 +
  `day8/re-frame2-ssr`.
- **MCP server in-process.** Separate-jar split — see
  [`006-MCP-Surface.md`](006-MCP-Surface.md).
- **Built-in pixel diff under `:test` tag.** Stories-as-tests are
  state-space tests, not pixel-space tests.
- **Full re-frame-10x reimplementation.** Story embeds 10x's epoch
  panel; does not own a parallel implementation.
