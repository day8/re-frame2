# tools/xray/

`day8/re-frame2-xray` — **Xray**, the re-frame2 devtools panel.
*The cascade you can see.*

Xray is the structural successor to [re-frame-10x](https://github.com/day8/re-frame-10x).
Where v1 organised debugging around the *epoch panel*, Xray organises it
around the *story a cascade tells* — every dispatch is a node in a graph
of causes, every state delta is a slice you can scrub, every machine
transition lands on a chart you can read, every schema violation
surfaces as an issue you cannot miss.

## What it is

An in-app true-inline devtools panel for re-frame2 applications,
preloaded into dev builds via `:preloads`. The host app provides a
right-side `[data-rf-xray-host]` column in its normal layout; Xray
auto-opens there once the substrate adapter is ready. Production builds elide the entire surface
through the universal `interop/debug-enabled?` gate — zero bytes
shipped to consumers.

Xray consumes the re-frame2 instrumentation surface (Spec 009 trace
bus, Tool-Pair epoch history, the registrar query API) — it adds
nothing the framework didn't already expose. The chrome is one tool in
two modes: a **9-tab Dynamic detail panel** (event-coupled) and a
**5-tab Static mode** (registry browse). The tabs are *presentation* of
an already-structured runtime.

AI agent access uses `tools/re-frame2-pair-mcp/` and the browser-side
accessors in `day8.re-frame2-xray.runtime`. There is no separate Xray
MCP artefact: agents and the human-facing panel read the same trace,
epoch, and registrar data.

## Headline experiences

The chrome is a **4-layer spine** (chrome ribbon · event list · tab bar ·
detail panel) per [`spec/018-Event-Spine.md`](./spec/018-Event-Spine.md).
Selecting an event in the L2 event list moves a single spine sub
(`:rf.xray/focus`); every Dynamic tab is a *lens on that one focused
event*. Time-travel is the spine itself — the events-ribbon nav cluster
plus the event list are the scrubber; there is no bottom rail. Issues are
not a tab — they surface inline in the Epoch panel, the L2 event-row
pink-wash, and the always-on issues ribbon signal.

### Dynamic mode — the 9 tabs (lenses on the focused event)

The tab-bar render order + the registry `:id` each tab lands on
`:rf.xray/selected-tab` (the host-facing `focus!` panel id; the
`focus.cljc` `valid-panels` set mirrors this exact inventory):

| Tab (label · mnemonic) | Registry id | What it shows for the focused event |
|---|---|---|
| **Epoch** (`e`) | `:epoch` | Lands on every open. The numbered cascade — dispatch · coeffects · handler · flow · fx · effects — plus inline issues (per-step pass/fail + the "Exception Thrown" block). Answers the canonical questions on first paint. |
| **app-db** (`a`) | `:app-db` | The slice-centric `:db-before → :db-after` diff for this event; pinned slices; full-tree escape hatch. Read-only. |
| **Views** (`v`) | `:views` | The subs recomputed because of this event + the components that re-rendered. |
| **Trace** (`t`) | `:trace` | The raw trace stream filtered to this event's cascade, with the wall-clock axis for timers / retries / deferred dispatches. |
| **Machine** (`m`) | `:machines` | The transitions this event triggered + spawn/destroy cascades. Stately-quality state-chart per machine; embeds `tools/machines-viz/`. |
| **Routes** (`r`) | `:routing` | The matched route + params/query/fragment + Simulate-URL, for the focused event. (Host-friendly alias: `focus!` accepts `:routes`, normalised to `:routing`.) |
| **Resources** (`s`) | `:resources` | The server-state / resource cache for this event — registry · instances · in-flight work · invalidations · the route→resource graph. |
| **Graph** | `:derivation-graph` | The unified derivation/process graph across all algebra-view families (EP-0014). L4-only — registry tab, no standalone `mount-*!` facade. |
| **Modules** (`u`) | `:module-view` | The (realm, frame) address space + the demand-trigger surface (EP-0013). L4-only — registry tab, no standalone `mount-*!` facade. |
| **Hicasso** (`h`) | `:hicasso` | Four views over the adapter-neutral Hicasso evidence surface — mounted boundaries · read attribution · the intent stream · explain-render — each stating its own scope, basis, completeness and loss. L4-only — registry tab, no standalone `mount-*!` facade. |

All ten ids are focusable via `focus!`. The standalone-mountable `Panel`
re-views (per [`spec/API.md`](./spec/API.md) §Additional public surfaces)
are the first seven — Epoch, app-db, Views, Trace, Machine, Routes, and
Resources; **Graph**, **Modules** and **Hicasso** are L4-only registry tabs
(shell-internal, focusable but not independently mountable).

### Static mode — the 5 browse surfaces (registry catalogue, event-independent)

A peer 3-layer surface (no spine) for browsing *everything that could
fire*, not just what did — per Lock #14/#15 in
[`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md). Toggle with
`Ctrl+Shift+M`.

| Tab | What it browses |
|---|---|
| **Machines** (`m`) | Every registered machine; topology chart + browse-list with a `→ Dynamic` jump chip. |
| **Routes** (`r`) | Every registered route; substring search + hermetic Simulate-URL / Simulate-navigation preview + `→ Dynamic` jump. |
| **Schemas** (`c`) | Every app-db / event / sub schema; the Malli EDN + jump-to-source. |
| **Flows** (`f`) | Every `reg-flow` registration; inputs, output path, owning frame, doc-string. |
| **Interceptors** (`i`) | Every registered interceptor. |

Full canonical inventory in [`spec/000-Vision.md`](./spec/000-Vision.md)
§The tab table and [`spec/018-Event-Spine.md`](./spec/018-Event-Spine.md).

> **Maintainers — chrome-shape drift guard.** When a Dynamic/Static tab
> is added, retired, folded, or renamed, update this Headline-experiences
> table in the SAME change, alongside the canonical sources
> ([`spec/000-Vision.md`](./spec/000-Vision.md) §tab table,
> [`spec/018-Event-Spine.md`](./spec/018-Event-Spine.md) §5, and
> [`testbeds/panel_gallery/core.cljs`](./testbeds/panel_gallery/core.cljs)).
> The README is the first orientation surface; stale tab vocabulary here
> teaches the wrong mental model. The authoritative tab inventory is the
> `panel-registry/reg-l4-tab!` calls in `src/.../panels/*` (Dynamic) and
> `src/.../static/*/panel.cljs` (Static).

## Quick start

### Install

Until the alpha publish lands on Clojars, use the `:local/root` route
from a checkout of this repo:

```clojure
;; deps.edn (dev alias only)
{:aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "tools/xray"}}}}}
```

Once published, the dev-deps coord will be
`day8/re-frame2-xray {:mvn/version "0.0.1.alpha"}` (tracking the repo
`VERSION`).

### Add The Layout Host

Xray's default launch mode is true inline, not an overlay. Add a
right-side host to the app layout (DOM order: `<main>` first, host
`<aside>` second — flex puts the aside on the right):

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #539bf5; }
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
}
#app { flex: 1; min-width: 0; }
```

That's the whole consumer surface — no `resize: horizontal`, no
`overflow: auto`. Xray auto-injects a polished drag handle on the
panel's left edge as soon as the shell mounts; the handle covers
mouse, touch, and pen via pointer events and is keyboard-navigable
(arrow keys for fine resize, Shift+arrow for coarse, Home/End for
the clamp ends, Enter/Space to reset).

Two complementary resize mechanisms ship together:

- **CSS variable** (host-owned). Override `--rf-xray-inline-width`
  anywhere up the cascade (e.g.
  `:root { --rf-xray-inline-width: 720px; }`) to set the initial
  width.
- **Xray drag handle** (user-controlled, persisted; auto-injected).
  Drag the panel's outer edge (left edge when docked `:right-rail`)
  to resize. Width clamps to `[320px, 90vw]` and persists across
  reloads in the Settings slot `[:general :panel-width-px]` (written at
  runtime by the handle via the `:rf.xray/settings-update` event; a host
  boot default can bulk-set it with the one-arg map `configure!`,
  `{:rf.xray/settings {:general {:panel-width-px <px>}}}`).
  Double-click the handle (or press Enter / Space when focused) to
  reset to default. See
  [`spec/007-UX-IA.md` §Resize affordance](./spec/007-UX-IA.md#resize-affordance).

**Yield-to-consumer.** Some teams prefer the browser-native handle
(`resize: horizontal` + `overflow: auto` on the host). Xray
detects that at render time via `getComputedStyle` and renders no
handle of its own — the consumer wins, no double-handle. The
zero-config path (drop in `<aside>` and let Xray inject) is the
recommended default; the opt-out is the explicit `resize:`
declaration on the host.

If the host is missing, Xray logs an actionable `console.error` and
exposes the same state through `window.day8.re_frame2_xray.status()`.

Override the selector before auto-open if needed:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/layout-host-selector "#devtools-xray"})
```

### Enable

```clojure
;; shadow-cljs.edn dev build
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's listeners under `register-listener!` and
`register-epoch-listener!`, installs the browser API/keybinding, and
auto-opens into the layout host after `rf/init!`.

Tool-owned pages that intentionally do not reserve app layout space for
Xray can suppress only the default page-load open before `rf/init!`:

```clojure
(xray-config/configure! {:rf.xray/auto-open? false})
```

Explicit opens still use the normal host contract and emit the same
missing-host diagnostic when no host exists.

### Launch

| Action | How |
|---|---|
| Auto-open | Page load after `rf/init!`, when `[data-rf-xray-host]` exists |
| Suppress auto-open on tool-only pages | `(xray-config/configure! {:rf.xray/auto-open? false})` before `rf/init!` |
| Hide/show | `Ctrl+Shift+C` |
| Close | `Esc` or `Ctrl+Shift+C` again |
| Open command palette | `Ctrl+K` (`Cmd+K` on macOS) |
| Toggle Dynamic ↔ Static mode | `Ctrl+Shift+M` (`Cmd+Shift+M` on macOS) |
| Pop out to second window | Programmatic `(xray/popout!)`; same-runtime/in-process where same-origin `window.opener` is available |

The global keydown listener ships several chords (the
`keybinding.cljs` predicates are the source of truth; the UX rationale
lives in [`spec/007-UX-IA.md`](spec/007-UX-IA.md) §Global shortcuts):
`Ctrl+Shift+C` (toggle shell), `Ctrl/Cmd+K` (command palette),
`Ctrl/Cmd+Shift+M` (Dynamic ↔ Static mode), and `Esc` (dismiss the
open-in-editor hint). Inside the shell, the LIVE-feed spine binds bare
`Space` / `L` / `j` / `k` / `G`. Pop-out is launched from the chrome's
`⛶` button or programmatically via `(xray/popout!)` — it is not bound
to a global chord.

### Disable

Remove the `:preloads` entry, or set
`:closure-defines {re-frame.interop/debug-enabled? false}` to
force-disable in dev.

### MCP (Xray as an agent surface)

AI agents reach Xray through `tools/re-frame2-pair-mcp/`, which evaluates
the browser-side accessors in `day8.re-frame2-xray.runtime`. See
[`spec/API.md`](./spec/API.md) for the accessor contract.

## Spec

The contract lives in [`spec/`](./spec/). The folder is complete enough
that the tool could be one-shotted from it.

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | Why Xray exists; the two-mode chrome (9-tab Dynamic + 5-tab Static); the bar it sets. |
| [`spec/002-Time-Travel.md`](./spec/002-Time-Travel.md) | Epoch scrubber; replay semantics; read-only posture. |
| [`spec/003-Machine-Inspector.md`](./spec/003-Machine-Inspector.md) | Embeds `tools/machines-viz/`; transition history; source jumps. |
| [`spec/004-App-DB-Diff.md`](./spec/004-App-DB-Diff.md) | Slice-centric diff; pinned slices; full-tree escape hatch. |
| [`spec/005-Schema-Timeline.md`](./spec/005-Schema-Timeline.md) | Per-schema timeline; recovery-mode colouring. |
| [`spec/006-Hydration-Debugger.md`](./spec/006-Hydration-Debugger.md) | SSR render-tree diff; divergent-node surfacing. |
| [`spec/007-UX-IA.md`](./spec/007-UX-IA.md) | Layout, interaction, visual language (typography, colour, motion). |
| [`spec/008-Embedding-Contract.md`](./spec/008-Embedding-Contract.md) | Full-shell embed contract (Xray-as-Story-RHS); state isolation via the `:rf/xray` frame-provider. |
| [`spec/011-Launch-Modes.md`](./spec/011-Launch-Modes.md) | In-app true-inline host + standalone-via-MCP for remote-attach. |
| [`spec/Principles.md`](./spec/Principles.md) | Load-bearing principles (read-only, observation-only, etc.). |
| [`spec/API.md`](./spec/API.md) | User-facing surface (`init!`, panel mount, MCP tool list). |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | The 13 locked decisions: question, options, pick, why. |
| [`spec/findings/`](./spec/findings/) | The original research docs that anchor the design. |

## File layout

```
tools/xray/
├── README.md                                  ; this file
├── deps.edn                                   ; declares day8/re-frame2-xray (artefact deps + :test alias)
├── spec/                                      ; normative contract (see above)
├── src/day8/re_frame2_xray/
│   ├── preload.cljs                           ; registers listeners, mounts DOM
│   ├── core.cljs                              ; user-facing facade (init!, open!, target-frame, ...)
│   ├── panels/                                ; one ns per panel
│   └── theme/                                 ; design tokens, theming
├── testbeds/                                  ; browser feature-gate driving surfaces
└── test/...
```

There is no `tools/xray/shadow-cljs.edn`. The CLJS build is driven from
the cross-artefact [`implementation/shadow-cljs.edn`](../../implementation/shadow-cljs.edn),
which wires `../tools/xray/src` + `../tools/xray/test` onto the build
classpath and registers every `tools/xray/testbeds/*` source dir +
`:dev-http` port. The runnable feature gates are npm scripts in
[`implementation/package.json`](../../implementation/package.json) — see
**Tests** below.

## Tests

| Gate | Command (from `implementation/`) |
|---|---|
| JVM/Node unit/helper/view suite | `clojure -M:test` (per-artefact, from `tools/xray/`) or `npm run test:cljs` (cross-artefact node-runtime CLJS) |
| Browser feature gate | `npm run test:xray-feature-gate` (full) / `npm run test:xray-feature-gate:smoke` (smoke) |

The browser feature gate serves the `tools/xray/testbeds/*` pages from
`implementation/shadow-cljs.edn`'s `:dev-http` config and drives the
Playwright scenarios.

## Bundle isolation

Xray lives under `tools/` so the bundle-isolation contract (per
[`tools/README.md`](../README.md)) holds: nothing in `implementation/`
may `:require` from Xray. The preload pulls only when shadow-cljs's
`:devtools` config asks for it; production builds (`goog.DEBUG=false`)
elide every surface Xray consumes (per Spec 009 §Production builds).

## Publishing

Xray publishes to Clojars as `day8/re-frame2-xray` on a tag push
of the form **`xray-v<VERSION>`** (e.g. `xray-v0.0.1.alpha`). The
workflow lives at
[`.github/workflows/release-xray.yml`](../../.github/workflows/release-xray.yml)
and is triggered automatically — no manual deploy step.

The tag's version segment must equal the repo-root
[`VERSION`](../../VERSION) file (lockstep convention per
[`spec/Conventions.md`](../../spec/Conventions.md) §Packaging
conventions); a mismatched tag is refused before any deploy step
runs. The dep on `day8/re-frame2` + `day8/reagent-slim` is pinned
to the same lockstep version on the throwaway runner checkout
immediately before `clein deploy`.

To cut a release (Mike-only):

```bash
# 1. Ensure VERSION reads the target (e.g. 0.0.1.alpha)
# 2. Tag and push:
git tag xray-v$(cat VERSION)
git push origin xray-v$(cat VERSION)
```

The framework release (the matching `v<VERSION>` tag on
[`.github/workflows/release.yml`](../../.github/workflows/release.yml))
must precede the Xray release: Xray's published pom depends on
`day8/re-frame2 {:mvn/version <VERSION>}` and that artefact must
already be discoverable on Clojars when `clein deploy` runs.

Required GitHub secrets (configured at the repository level):
`CLOJARS_USERNAME`, `CLOJARS_PASSWORD` (Clojars deploy token).

## Status

Pre-alpha. The full 9-tab Dynamic shell, 5-tab Static catalogue,
true-inline and overlay launch modes, same-origin pop-out, command palette,
global shortcuts, and frame-isolated `:rf/xray` state are implemented.

[`spec/017-Test-Coverage-Matrix.md`](./spec/017-Test-Coverage-Matrix.md)
is the live source for per-surface coverage. Browser fixtures under
`testbeds/` exercise multi-frame isolation, epoch/routing/machine ladders,
the EDN inspector, panel gallery, feature matrix, and performance probes.
The matrix, rather than this README, records which paths are covered,
partial, deferred, or intentionally run outside default CI.
