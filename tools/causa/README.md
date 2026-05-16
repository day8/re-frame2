# tools/causa/

`day8/re-frame2-causa` — **Causa**, the re-frame2 devtools panel.
*The cascade you can see.*

Causa is the structural successor to [re-frame-10x](https://github.com/day8/re-frame-10x).
Where v1 organised debugging around the *epoch panel*, Causa organises it
around the *story a cascade tells* — every dispatch is a node in a graph
of causes, every state delta is a slice you can scrub, every machine
transition lands on a chart you can read, every schema violation
surfaces as an issue you cannot miss.

## What it is

An in-app true-inline devtools panel for re-frame2 applications,
preloaded into dev builds via `:preloads`. The host app provides a
right-side `[data-rf-causa-host]` column in its normal layout; Causa
auto-opens there once the substrate adapter is ready. Production builds elide the entire surface
through the universal `interop/debug-enabled?` gate — zero bytes
shipped to consumers.

Causa consumes the re-frame2 instrumentation surface (Spec 009 trace
bus, Tool-Pair epoch history, the registrar query API) — it adds
nothing the framework didn't already expose. The 16 panels are
*presentation* of an already-structured runtime.

A separate jar `tools/causa-mcp/` is planned to expose Causa's surfaces
as MCP tools for AI agents — same architecture as `tools/pair2-mcp/`,
different tool catalogue. The artefact does not yet exist on disk; the
contract is being designed in [`spec/010-MCP-Server.md`](./spec/010-MCP-Server.md).

## Headline experiences

| Surface | What it does |
|---|---|
| **Event-detail panel** (hero) | Lands on every open. The event vector, the diff, the inline mini-graph, fx fired, subs recomputed, renders, duration. Answers the five canonical questions on first paint. |
| **Causality graph** (peer) | Vertical directed graph keyed by `:dispatch-id` / `:parent-dispatch-id`. The deeper-walk view when the cascade is > 2 hops or spans frames. |
| **Time-travel scrubber** | Bottom rail. Passive scrubbing rebases the view of history; explicit rewind calls `restore-epoch` with the six failure modes surfaced. |
| **Slice-centric app-db panel** | The slices that changed, the slices the user pinned. Read-only. |
| **Machine inspector** | Stately-quality state-chart per machine. Embeds `tools/machines-viz/`. |
| **Schema-violation timeline** | One row per registered schema; coloured dot per failure with recovery mode. |
| **Hydration debugger** | Server vs client render-tree side-by-side. Only visible when SSR hydration runs. |
| **Issues ribbon** | Unified feed: errors + warnings + schema violations + hydration mismatches. |
| **AI co-pilot** (rail) | Pull-only Q&A and slash commands. Collapsed by default; expand on demand. Ephemeral (no persistence). |

Full panel inventory in [`spec/000-Vision.md`](./spec/000-Vision.md).

## Quick start

### Install

Until the alpha publish lands on Clojars, use the `:local/root` route
from a checkout of this repo:

```clojure
;; deps.edn (dev alias only)
{:aliases {:dev {:extra-deps {day8/re-frame2-causa {:local/root "tools/causa"}}}}}
```

Once published, the dev-deps coord will be
`day8/re-frame2-causa {:mvn/version "0.0.1.alpha"}` (tracking the repo
`VERSION`).

### Add The Layout Host

Causa's default launch mode is true inline, not an overlay. Add a
right-side host to the app layout (DOM order: `<main>` first, host
`<aside>` second — flex puts the aside on the right):

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-causa-host></aside>
</div>
```

```css
.app-shell { display: flex; min-height: 100vh; }
[data-rf-causa-host] {
  flex: 0 0 var(--rf-causa-inline-width, 420px);
  min-width: 320px;
  border-left: 1px solid #2a2a2a;
  resize: horizontal;
  overflow: auto;
}
#app { flex: 1; min-width: 0; }
```

Two complementary resize mechanisms ship together — both
browser-native, both JS-free:

- **CSS variable** (host-owned). Override `--rf-causa-inline-width`
  anywhere up the cascade (e.g.
  `:root { --rf-causa-inline-width: 560px; }`) to set the initial
  width.
- **Browser-native drag** (user-controlled). `resize: horizontal` +
  `overflow: auto` give the host a drag-handle in the bottom corner;
  the variable seeds the initial size, a drag overrides it for the
  page lifetime.

If the host is missing, Causa logs an actionable `console.error` and
exposes the same state through `window.day8.re_frame2_causa.status()`.

Override the selector before auto-open if needed:

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:layout/host-selector "#devtools-causa"})
```

### Enable

```clojure
;; shadow-cljs.edn dev build
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload registers Causa's listeners under `register-trace-cb!` and
`register-epoch-cb!`, installs the browser API/keybinding, and
auto-opens into the layout host after `rf/init!`.

Tool-owned pages that intentionally do not reserve app layout space for
Causa can suppress only the default page-load open before `rf/init!`:

```clojure
(causa-config/configure! {:launch/auto-open? false})
```

Explicit opens still use the normal host contract and emit the same
missing-host diagnostic when no host exists.

### Launch

| Action | How |
|---|---|
| Auto-open | Page load after `rf/init!`, when `[data-rf-causa-host]` exists |
| Suppress auto-open on tool-only pages | `(causa-config/configure! {:launch/auto-open? false})` before `rf/init!` |
| Hide/show | `Ctrl+Shift+C` |
| Close | `Esc` or `Ctrl+Shift+C` again |
| Pop out to second window | Programmatic `(causa/popout!)`; same-runtime/in-process where same-origin `window.opener` is available |
| Open AI co-pilot rail | `Ctrl+Shift+/` |
| Command palette | `Ctrl+K` |

### Disable

Remove the `:preloads` entry, or set
`:closure-defines {day8.re-frame2-causa.config/enabled? false}` to
force-disable in dev.

### MCP (Causa as an agent surface) — planned

A separate `tools/causa-mcp/` artefact is in design — it will expose
Causa's surfaces as MCP tools so AI agents can drive the same
observations through a tool catalogue. Neither the jar nor the npm
wrapper ships yet.

When the artefact lands, the consumer-side wiring will look roughly
like this (the exact coord names are not final):

```bash
# planned — not yet published
npm install -g @day8/re-frame2-causa-mcp
```

```json
// planned — ~/.claude/settings.json
{ "mcpServers": { "causa": { "command": "re-frame2-causa-mcp" } } }
```

Tool catalogue under design in [`spec/010-MCP-Server.md`](./spec/010-MCP-Server.md).

## Spec

The contract lives in [`spec/`](./spec/). The folder is complete enough
that the tool could be one-shotted from it.

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | Why Causa exists; the 16-panel inventory; the bar it sets. |
| [`spec/001-Causality-Graph.md`](./spec/001-Causality-Graph.md) | The (peer) causality graph; data model; rendering rules. |
| [`spec/002-Time-Travel.md`](./spec/002-Time-Travel.md) | Epoch scrubber; replay semantics; read-only posture. |
| [`spec/003-Machine-Inspector.md`](./spec/003-Machine-Inspector.md) | Embeds `tools/machines-viz/`; transition history; source jumps. |
| [`spec/004-App-DB-Diff.md`](./spec/004-App-DB-Diff.md) | Slice-centric diff; pinned slices; full-tree escape hatch. |
| [`spec/005-Schema-Timeline.md`](./spec/005-Schema-Timeline.md) | Per-schema timeline; recovery-mode colouring. |
| [`spec/006-Hydration-Debugger.md`](./spec/006-Hydration-Debugger.md) | SSR render-tree diff; divergent-node surfacing. |
| [`spec/007-UX-IA.md`](./spec/007-UX-IA.md) | Layout, interaction, visual language (typography, colour, motion). |
| [`spec/008-Embedding-Contract.md`](./spec/008-Embedding-Contract.md) | Story-embed contract; the `Panel` component shape. |
| [`spec/009-AI-CoPilot.md`](./spec/009-AI-CoPilot.md) | Pull-only Q&A; slash commands; ephemeral conversation. |
| [`spec/010-MCP-Server.md`](./spec/010-MCP-Server.md) | `tools/causa-mcp/`; the Causa MCP tool catalogue. |
| [`spec/011-Launch-Modes.md`](./spec/011-Launch-Modes.md) | In-app true-inline host + standalone-via-MCP for remote-attach. |
| [`spec/Principles.md`](./spec/Principles.md) | Load-bearing principles (read-only, observation-only, etc.). |
| [`spec/API.md`](./spec/API.md) | User-facing surface (`init!`, panel mount, MCP tool list). |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | The 13 locked decisions: question, options, pick, why. |
| [`spec/findings/`](./spec/findings/) | The original research docs that anchor the design. |

## File layout

```
tools/causa/
├── README.md                                  ; this file
├── deps.edn                                   ; declares day8/re-frame2-causa
├── shadow-cljs.edn                            ; build config
├── spec/                                      ; normative contract (see above)
├── src/day8/re_frame2_causa/
│   ├── preload.cljs                           ; registers listeners, mounts DOM
│   ├── core.cljs                              ; user-facing facade (init!, open!, target-frame, ...)
│   ├── panels/                                ; one ns per panel
│   ├── causality/                             ; graph layout + rendering
│   ├── ai/                                    ; co-pilot panel, provider abstraction
│   └── theme/                                 ; design tokens, theming
└── test/...
```

## Bundle isolation

Causa lives under `tools/` so the bundle-isolation contract (per
[`tools/README.md`](../README.md)) holds: nothing in `implementation/`
may `:require` from Causa. The preload pulls only when shadow-cljs's
`:devtools` config asks for it; production builds (`goog.DEBUG=false`)
elide every surface Causa consumes (per Spec 009 §Production builds).

## Status

In design. Spec corpus landed via rf2-1lls (2026-05-12). Implementation
work begins after the spec ratifies.
