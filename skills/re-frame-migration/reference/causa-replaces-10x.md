# causa-replaces-10x

The devtools swap. v1 ships `day8.re-frame-10x`; v2 ships **Causa** (`day8/re-frame2-causa`). Causa is a from-scratch reimplementation against re-frame2's own trace bus and epoch-history surfaces — not a port of 10x. The mental model (events, subs, app-db diff, time-travel) carries over; the wiring underneath does not. See [`docs/guide/18-from-re-frame-v1.md` §A note on the tooling](../../../docs/guide/18-from-re-frame-v1.md#a-note-on-the-tooling) for the narrative version of this swap.

**This is not an M-rule.** No application code triggers it; it's a dev-build hygiene step the author runs alongside the M-0 coord swap. The skill performs it when the codebase's dep file holds a `day8.re-frame-10x` coord or a `day8.re-frame-10x.preload` `:preloads` entry.

## Contents

- The swap (dep + preload)
- The layout host (true-inline default)
- Resizing the inline host (`--rf-causa-inline-width`)
- Keybindings (what's actually wired)
- Behaviour parity (10x → Causa)
- Where to read more

---

## The swap (dep + preload)

### 1. Remove the v1-era dep + preload

```clojure
;; SEARCH — dev alias / dev profile
{:aliases {:dev {:extra-deps {day8.re-frame/re-frame-10x {:mvn/version "..."}}}}}

;; SEARCH — shadow-cljs.edn dev build
{:builds {:app {:devtools {:preloads [day8.re-frame-10x.preload]}}}}
```

Drop both. The `day8.re-frame/re-frame-10x` Maven coord and the `day8.re-frame-10x.preload` `:preloads` entry are v1-only and have no replacement at the same coord. If the project also pinned a `closure-defines` flag for 10x (e.g. `day8.re-frame-10x.preload.show-fps?`), drop those too.

### 2. Add Causa (dev-deps only)

```clojure
;; deps.edn — dev alias only. Causa MUST NEVER appear in production deps.
{:aliases {:dev {:extra-deps {day8/re-frame2-causa {:local/root "tools/causa"}}}}}
```

While re-frame2 is in alpha, use the `:local/root` route from a clone of the `day8/re-frame2` repo. Once Causa publishes to Clojars, the coord will be `day8/re-frame2-causa {:mvn/version "<VERSION>"}` (tracking re-frame2's lockstep `<VERSION>`). The skill prints the `:local/root` form when the author hasn't told it otherwise; if the author wants the published coord, they say so in the kickoff prompt.

Causa is **dev-only by construction** — production builds elide every byte of it through the framework's `re-frame.interop/debug-enabled?` gate (`goog.DEBUG=false`). A CI gate at `implementation/scripts/check-bundle-isolation.cjs` greps production bundles for Causa-internal sentinels; any hit is a release blocker. See [`tools/causa/README.md` §Bundle isolation](../../../tools/causa/README.md#bundle-isolation).

### 3. Wire the preload

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload registers Causa's listeners under `register-trace-cb!` and `register-epoch-cb!`, attaches the `Ctrl+Shift+C` keybinding, and auto-opens the panel into the layout host after `rf/init!`. No `(require '[day8.re-frame2-causa.core])`. No `init!` call. The preload plus the host element are the full integration surface.

---

## The layout host (true-inline default)

Causa's default launch is **true inline** (per spec `rf2-eehov`): it mounts into a host element the app provides, sharing the layout. No overlay, no body-padding dock, no popup. The app reserves space; Causa fills it.

Add a left-side host to the app's HTML and CSS:

```html
<div class="app-shell">
  <aside data-rf-causa-host></aside>
  <main id="app"></main>
</div>
```

```css
.app-shell { display: flex; min-height: 100vh; }
[data-rf-causa-host] {
  flex: 0 0 var(--rf-causa-inline-width, 420px);
  min-width: 320px;
}
#app { flex: 1; min-width: 0; }
```

If the host element is missing, Causa logs an actionable `console.error` and exposes the same diagnostic through `window.day8.re_frame2_causa.status()`. It does not silently overlay or dock as a fallback — true-inline is the contract.

To suppress the default page-load auto-open on tool-only pages (e.g. story chrome that reserves no app layout space):

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:launch/auto-open? false})
```

Explicit opens still use the normal host contract.

---

## Resizing the inline host (`--rf-causa-inline-width`)

Per `rf2-um813`, the recommended host CSS reads its `flex-basis` from a single CSS custom property — `--rf-causa-inline-width` — so the panel can be resized without forking the host rule or falling back to overlay modes.

```css
/* Global default — every page in the app */
:root { --rf-causa-inline-width: 560px; }

/* Per-route override (e.g. a debugging route that wants more room) */
.debug-route { --rf-causa-inline-width: 720px; }

/* Per-user override via a developer stylesheet */
[data-rf-causa-host] { --rf-causa-inline-width: 380px; }
```

The default (`420px`) is the same one Causa ships in its config defaults. A 320px floor is built into the recommended CSS (`min-width: 320px`) so the panel never collapses below readable.

The custom property is the **only** supported resize knob. Don't fork the `flex-basis` literal; readers (linters, tooling, story-mode chrome) look for the property name. See [`tools/causa/spec/API.md` §Resizing the inline host](../../../tools/causa/spec/API.md) for the full contract.

---

## Keybindings (what's actually wired)

Two keybindings ship in `tools/causa/src/day8/re_frame2_causa/keybinding.cljs` today:

| Action | Keys | Notes |
|---|---|---|
| Toggle the Causa panel (show / hide) | `Ctrl+Shift+C` | Toggles visibility of the mounted shell; does not unmount. |
| Toggle the AI co-pilot rail | `Ctrl+Shift+/` | Routes through Causa's frame; rail state lives on `:rf/causa`. |

**Cross-OS:** Causa uses `Ctrl` (not `Cmd`) on every host OS. macOS Safari sometimes maps `Cmd+Shift+C` to dev-tools' Inspect; the `Ctrl` modifier avoids that collision. macOS users who prefer `Cmd+Shift+C` can rebind in their browser's keyboard-shortcut UI.

**Not currently wired as keybindings:** `Ctrl+Shift+P` (pop-out to second window) and `Ctrl+K` (command palette) appear in some docs and spec tables, but the keydown listener in `keybinding.cljs` does not currently handle them. Pop-out is reachable programmatically via `window.day8.re_frame2_causa.popout_BANG_()` (or `(causa/popout!)` from CLJS); the command palette is shell-internal. If the author asks specifically about either, point them at the programmatic surface and note the keybinding gap. Do not claim Causa supports a keybinding it doesn't.

---

## Behaviour parity (10x → Causa)

| 10x feature | Causa equivalent | Notes |
|---|---|---|
| Epoch panel (per-event detail) | **Event-detail panel** | Lands on every open. Hero panel: event vector, app-db diff, fx fired, subs recomputed, renders, duration. Same mental model, denser layout. |
| Event-history list | **Causality graph** | Vertical directed graph keyed by `:dispatch-id` / `:parent-dispatch-id`. The deeper-walk view when a cascade spans >2 hops or crosses frames. v1's flat list becomes a graph because v2 cascades genuinely branch. |
| App-DB inspector + diff | **Slice-centric app-db panel** | Shows the slices that changed plus user-pinned slices. Read-only; mutations go through normal dispatch. Full-tree view is an escape hatch, not the default. |
| Subs panel | Absorbed into the event-detail and causality panels | Sub recomputation is a property of an event, not its own panel. The static sub-graph is exposed via the framework's `(rf/sub-topology)` (O-12), not a Causa surface. |
| Trace panel | **Trace-stream panel** | One row per trace event from the Spec 009 trace bus. Filterable by category (`:rf.error/*`, `:rf.warning/*`, `:rf.machine/*`, etc.). |
| Time-travel (10x's "back / forward") | **Time-travel scrubber** | Bottom rail. Passive scrubbing rebases the view of history; explicit rewind calls `restore-epoch` with the six failure modes surfaced. |
| Settings / persistence | Not present | Causa is ephemeral by design — no localStorage, no per-user preferences. Configuration lives in `(causa-config/configure! ...)` at preload time. |
| **(new in Causa)** Machine inspector | — | Stately-quality state-chart per registered machine. No 10x equivalent (machines are a v2 addition). |
| **(new in Causa)** Schema-violation timeline | — | One row per registered schema; coloured dot per failure with recovery mode. Schemas are a v2 addition. |
| **(new in Causa)** Hydration debugger | — | Server vs client render-tree side-by-side. Only visible when SSR hydration runs. SSR is a v2 addition. |
| **(new in Causa)** AI co-pilot rail | — | Pull-only Q&A and slash commands. Collapsed by default; `Ctrl+Shift+/` to expand. Ephemeral. |
| **(new in Causa)** Click-to-source | — | Every rendered DOM element carries `data-rf2-source-coord` in dev builds; clicking jumps to source in the editor. Requires the framework's source-coord stamping, which is dev-only. |

What's intentionally different:

- **Read-only posture.** 10x allowed direct app-db edits from its inspector; Causa does not. State mutations must go through `dispatch` so the cascade is observable through the same surfaces as production code.
- **No persistence.** 10x persisted some panel state to localStorage; Causa keeps nothing across reloads. Configuration is preload-time only.
- **In-app, not sidecar.** 10x was a sidecar panel that occupied a fixed portion of the viewport via body-padding. Causa is true-inline — the app reserves layout space the same way it reserves space for any other UI region.

Full panel inventory: [`tools/causa/spec/000-Vision.md`](../../../tools/causa/spec/000-Vision.md). Per-panel reference: [`docs/causa/02-panel-tour.md`](../../../docs/causa/02-panel-tour.md) through [`docs/causa/11-mcp-server.md`](../../../docs/causa/11-mcp-server.md).

---

## Where to read more

- [`docs/causa/01-installation.md`](../../../docs/causa/01-installation.md) — the canonical install walkthrough (five minutes, three edits).
- [`tools/causa/README.md`](../../../tools/causa/README.md) — entry-point summary, spec index, file layout.
- [`tools/causa/spec/API.md`](../../../tools/causa/spec/API.md) — the full user-facing surface (`configure!`, `popout!`, programmatic open/close, the layout-host contract, `--rf-causa-inline-width`).
- [`tools/causa/spec/011-Launch-Modes.md`](../../../tools/causa/spec/011-Launch-Modes.md) — true-inline default + standalone-via-MCP for remote-attach scenarios.
- [`docs/guide/18-from-re-frame-v1.md` §A note on the tooling](../../../docs/guide/18-from-re-frame-v1.md#a-note-on-the-tooling) — the narrative version of the 10x → Causa swap.

---

## Reporting

Mention the devtools swap in the migration report's **Anything unexpected** or **Verification** section — not in the M-rule list (it is not an M-rule). Example line:

> *"Dev-deps: dropped `day8.re-frame/re-frame-10x` + its `:preloads` entry; added `day8/re-frame2-causa {:local/root "..."}` + its preload. Added `[data-rf-causa-host]` host to `resources/public/index.html`. `Ctrl+Shift+C` toggles the panel."*

That's it. No rule id; the v1 devtools were never part of the application contract.
