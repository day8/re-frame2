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
two modes: a **6-tab Dynamic detail panel** (event-coupled) and a
**5-tab Static mode** (registry browse). The tabs are *presentation* of
an already-structured runtime.

AI agent access to Xray's surfaces flows through
`tools/re-frame2-pair-mcp/` against the framework-published Xray
runtime API at `day8.re-frame2-xray.runtime` — per rf2-hvl1g a
dedicated xray-mcp jar is unnecessary; agents read the same trace bus
+ epoch history + registrar Xray itself reads.

## Headline experiences

| Surface | What it does |
|---|---|
| **Event-detail panel** (hero) | Lands on every open. The event vector, the diff, the inline mini-graph, fx fired, subs recomputed, renders, duration. Answers the canonical questions on first paint. |
| **Time-travel scrubber** | Bottom rail. Passive scrubbing rebases the view of history; explicit rewind calls `restore-epoch` with the six failure modes surfaced. |
| **Slice-centric app-db panel** | The slices that changed, the slices the user pinned. Read-only. |
| **Machine inspector** | Stately-quality state-chart per machine. Embeds `tools/machines-viz/`. |
| **Schema-violation timeline** | One row per registered schema; coloured dot per failure with recovery mode. |
| **Hydration debugger** | Server vs client render-tree side-by-side. Only visible when SSR hydration runs. |
| **Issues ribbon** | Unified feed: errors + warnings + schema violations + hydration mismatches. |

Full panel inventory in [`spec/000-Vision.md`](./spec/000-Vision.md).

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
:root { --rf-xray-accent: #7C5CFF; }
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
  reloads via `configure! :rf.xray/settings :general :panel-width-px`.
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
| Pop out to second window | Programmatic `(xray/popout!)`; same-runtime/in-process where same-origin `window.opener` is available |

One keybinding ships today (`Ctrl+Shift+C`). The pop-out and
command-palette keys some early drafts named are not wired pre-alpha —
use `(xray/popout!)` for pop-out, and reach the palette through the
top-strip control once it lands.

### Disable

Remove the `:preloads` entry, or set
`:closure-defines {re-frame.interop/debug-enabled? false}` to
force-disable in dev.

### MCP (Xray as an agent surface)

Per rf2-hvl1g there is no dedicated `xray-mcp` jar. AI agents reach
Xray's surfaces through `tools/re-frame2-pair-mcp/`, which can read
the framework-published Xray runtime API on
`day8.re-frame2-xray.runtime` (the same trace bus + epoch history +
registrar Xray's chrome reads).

## Spec

The contract lives in [`spec/`](./spec/). The folder is complete enough
that the tool could be one-shotted from it.

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | Why Xray exists; the two-mode chrome (6-tab Dynamic + 5-tab Static); the bar it sets. |
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
├── deps.edn                                   ; declares day8/re-frame2-xray
├── shadow-cljs.edn                            ; build config
├── spec/                                      ; normative contract (see above)
├── src/day8/re_frame2_xray/
│   ├── preload.cljs                           ; registers listeners, mounts DOM
│   ├── core.cljs                              ; user-facing facade (init!, open!, target-frame, ...)
│   ├── panels/                                ; one ns per panel
│   └── theme/                                 ; design tokens, theming
└── test/...
```

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

Pre-alpha. Running shell with the full two-mode chrome (6-tab Dynamic
detail panel + 5-tab Static mode), one wired keybinding
(`Ctrl+Shift+C` toggle), default true-inline mount under
`[data-rf-xray-host]`, programmatic pop-out via `(xray/popout!)`,
and a frame-isolated `:rf/xray` registrar.
Spec corpus landed via rf2-1lls (2026-05-12); the 17-row test
coverage matrix at [`spec/017-Test-Coverage-Matrix.md`](./spec/017-Test-Coverage-Matrix.md)
reports `covered` across every row at the unit/helper/view tier.

Browser testbeds live under `tools/xray/testbeds/` —
`two_frame_isolation`, `standard_epochs`, `routes_epochs`, `machine_epochs`,
`edn_inspector`, `feature_matrix`, `panel_gallery` — covering the canonical
multi-frame isolation surface
(`two_frame_isolation`: one app · two frames · Counter / Machine
(websocket) / Routing / Async&errors tabs navigated as routes ·
per-frame trace / events / issues / cascades · Xray target-frame
round-trip; built from the shared `testdeck/` modules), the
deliberately-simple single-frame driving surface (`standard_epochs`,
rf2-gsr6z: one frame · a tall column of numbered buttons, each bumping
a shared baseline counter + exercising exactly one more feature, so
clicking top-to-bottom completely exercises any one Xray panel —
Epoch / App-db / Views / Trace / Issues; no tabs, no routing, no
machines/SSR; supersedes the old `step_deck`), the routing sibling
(`routes_epochs`, rf2-5crg4: the same numbered-button shape — one frame ·
baseline-bump per press · one more ROUTING feature per rung — aimed
squarely at the Routing panel, so clicking top-to-bottom completely
exercises its Current route · Navigation this epoch · Route table
sections; served on port 8032), the state-machine sibling
(`machine_epochs`, rf2-w06op: the same numbered-button shape — one frame ·
baseline-bump per press · one more MACHINE feature per rung (start ·
transition · entry/exit actions · guard allowed/blocked · transition-
with-effect · ignored event · parallel regions · transition history ·
multiple machines) — aimed squarely at the Machine Inspector
(`rf-xray-machine-inspector`), so clicking top-to-bottom completely
exercises its topology highlight · focused-transition lens · guards /
actions · snapshot drill-in · transition history · parallel-region
surfaces; owns its own `:door/main` + `:traffic/light` machines and does
NOT touch the `deep_machine` gate substrate; served on port 8033), the
edn-inspector sibling (`edn_inspector`, rf2-74u2s → rf2-1niob: the same
numbered-button shape — one frame · baseline-bump per press — where each
button DISPATCHES a real app-db change at a meaningful path, and the Xray
sidecar mounted INLINE on the right shows it via the EPOCH (db-before /
after) + APP-DB (the diff) panels, both of which render their CLJS values
through the edn-inspector
(`day8.re-frame2-xray.views.edn-inspector`) — so the inspector is
demonstrated through its PRIMARY use case, the panels, not a standalone
widget. An 8-rung ladder, each rung writing one inspector-stressing shape:
large collection → elision · deeply nested → path render/collapse · the
three App-db diff ops (added / removed-to-empty per rf2-8pfkk / changed
diff-mode-3) · :rf/redacted sentinel · :rf.size/large-elided sentinel
(Spec 015) · mixed types + #uuid/#inst tagged literals. The inline shell
mounts from the deck's `run` via the public
`day8.re-frame2-xray.core/init!` + `open!` (the manual alternative to the
`:preloads` wiring) so no shadow-cljs.edn edit is needed; served on port
8034), the deterministic
feature-matrix sweep across panels + shell + launch modes + redaction
+ 20-event load, the Panel-view gallery, and the performance probe.

Agent access to Xray's surfaces flows through
`tools/re-frame2-pair-mcp/` against the framework-published Xray
runtime API on `day8.re-frame2-xray.runtime` (per rf2-hvl1g — no
dedicated xray-mcp jar).

Next: alpha cut once the browser feature gate from
[`spec/017-Test-Coverage-Matrix.md`](./spec/017-Test-Coverage-Matrix.md)
lands as default CI and the `0.0.1.alpha` Clojars publish is wired.
