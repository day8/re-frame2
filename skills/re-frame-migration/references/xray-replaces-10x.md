# xray-replaces-10x

The devtools swap. v1 ships `day8.re-frame-10x`; v2 ships **Xray** (`day8/re-frame2-xray`). Xray is a from-scratch reimplementation against re-frame2's own trace bus and epoch-history surfaces — not a port of 10x. The mental model (events, subs, app-db diff, time-travel) carries over; the wiring underneath does not. See [`docs/guide/25-from-re-frame-v1.md` §The devtools moved house](../../../docs/guide/25-from-re-frame-v1.md#the-devtools-moved-house) for the narrative version of this swap.

**This is not an M-rule.** No application code triggers it; it's a dev-build hygiene step the author runs alongside the M-0 coord swap. The skill performs it when the codebase's dep file holds a `day8.re-frame-10x` coord or a `day8.re-frame-10x.preload` `:preloads` entry.

## Contents

- The swap (dep + preload)
- The layout host (true-inline default)
- Resizing the inline host (`--rf-xray-inline-width`)
- Keybindings (what's actually wired)
- Behaviour parity (10x → Xray)
- Where to read more

---

## The swap (dep + preload)

### Prerequisites — apply BEFORE this swap

**M-40 (`(rf/init!)` call) must already be applied.** Xray's preload auto-opens the panel into `[data-rf-xray-host]` *after* `rf/init!` runs (per `preload.cljs`); without an explicit init call the panel silently fails to mount — the host element is in the DOM, the preload loaded, no console error fires, and the panel never appears. This is the most confusing failure mode in the migration. If the codebase is mid-M-rule sweep and M-40 hasn't been applied yet, do M-40 first and verify a clean reload, then come back here.

### 1. Remove the v1-era dep + preload

```clojure
;; SEARCH — dev alias / dev profile
{:aliases {:dev {:extra-deps {day8.re-frame/re-frame-10x {:mvn/version "..."}}}}}

;; SEARCH — shadow-cljs.edn dev build
{:builds {:app {:devtools {:preloads [day8.re-frame-10x.preload]}}}}
```

Drop both. The `day8.re-frame/re-frame-10x` Maven coord and the `day8.re-frame-10x.preload` `:preloads` entry are v1-only and have no replacement at the same coord. If the project also pinned a `closure-defines` flag for 10x (e.g. `day8.re-frame-10x.preload.show-fps?`), drop those too.

### 2. Add Xray (dev-deps only)

```clojure
;; deps.edn — dev alias only. Xray MUST NEVER appear in production deps.
{:aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "tools/xray"}}}}}
```

While re-frame2 is in alpha, use the `:local/root` route from a clone of the `day8/re-frame2` repo. Once Xray publishes to Clojars, the coord will be `day8/re-frame2-xray {:mvn/version "<VERSION>"}` (tracking re-frame2's lockstep `<VERSION>`). The skill prints the `:local/root` form when the author hasn't told it otherwise; if the author wants the published coord, they say so in the kickoff prompt.

`day8/re-frame2-xray` declares `day8/re-frame2-epoch` as a hard dep — no separate add is required. Xray's epoch-aware panels (the time-travel scrubber, the event-detail panel) read from `re-frame.epoch`'s seed table via `rf/epoch-history` / `rf/register-epoch-listener!`; without the epoch artefact those panels render empty even when events have fired. The dep is pulled in transitively by adding Xray.

Xray is **dev-only by construction** — production builds elide every byte of it through the framework's `re-frame.interop/debug-enabled?` gate (`goog.DEBUG=false`). A CI gate at `implementation/scripts/check-bundle-isolation.cjs` greps production bundles for Xray-internal sentinels; any hit is a release blocker. See [`tools/xray/README.md` §Bundle isolation](../../../tools/xray/README.md#bundle-isolation).

### 3. Wire the preload

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's listeners under `register-listener!` and `register-epoch-listener!`, attaches the `Ctrl+Shift+C` keybinding, and auto-opens the panel into the layout host after `rf/init!`. No `(require '[day8.re-frame2-xray.core])`. No `init!` call. The preload plus the host element are the full integration surface.

---

## The layout host (true-inline default)

Xray's default launch is **true inline**: it mounts into a host element the app provides, sharing the layout. No overlay, no body-padding dock, no popup. The app reserves space; Xray fills it.

Add a right-side host to the app's HTML and CSS (DOM order: `<main>` first, `<aside>` second — flex flow puts the aside on the right):

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #7C5CFF; } /* brand-accent var — host stylesheets read var(--rf-xray-accent) to tint dev chrome */
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
}
#app { flex: 1; min-width: 0; }
```

If the host element is missing, Xray logs an actionable `console.error` and exposes the same diagnostic through `window.day8.re_frame2_xray.status()`. It does not silently overlay or dock as a fallback — true-inline is the contract.

To suppress the default page-load auto-open on tool-only pages (e.g. story chrome that reserves no app layout space):

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/auto-open? false})
```

Explicit opens still use the normal host contract.

---

## Resizing the inline host (`--rf-xray-inline-width`)

The recommended host CSS reads its `flex-basis` from a single CSS custom property — `--rf-xray-inline-width` (default `560px`, with a `min-width: 320px` floor) — so the panel resizes without forking the host rule or falling back to overlay modes. The custom property is the **only** supported resize knob.

The resize *handle* (a vertical splitter that writes the property), the per-route / per-user width overrides, and the `position: fixed`-overlay containment guidance are **project-side UX detail, not migration content** — they live in the canonical Xray docs. For the full install + resize-handle recipe + containment guidance see [`docs/xray/01-installation.md`](../../../docs/xray/01-installation.md) and [`tools/xray/spec/API.md` §Resizing the inline host](../../../tools/xray/spec/API.md). The migration step is just: drop the 10x dep+preload, add the Xray dep+preload+host, set `--rf-xray-inline-width` if the default doesn't fit.

---

## Keybindings (what's actually wired)

One keybinding ships in `tools/xray/src/day8/re_frame2_xray/keybinding.cljs` today:

| Action | Keys | Notes |
|---|---|---|
| Toggle the Xray panel (show / hide) | `Ctrl+Shift+C` | Toggles visibility of the mounted shell; does not unmount. |

**Cross-OS:** Xray uses `Ctrl` (not `Cmd`) on every host OS. macOS Safari sometimes maps `Cmd+Shift+C` to dev-tools' Inspect; the `Ctrl` modifier avoids that collision. macOS users who prefer `Cmd+Shift+C` can rebind in their browser's keyboard-shortcut UI.

**Not currently wired as keybindings:** `Ctrl+Shift+P` (pop-out to second window) and `Ctrl+K` (command palette) appear in some docs and spec tables, but the keydown listener in `keybinding.cljs` does not currently handle them. Pop-out is reachable programmatically via `window.day8.re_frame2_xray.popout_BANG_()` (or `(xray/popout!)` from CLJS); the command palette is shell-internal. If the author asks specifically about either, point them at the programmatic surface and note the keybinding gap. Do not claim Xray supports a keybinding it doesn't.

The earlier `Ctrl+Shift+/` co-pilot keybinding has been removed —
the AI co-pilot rail no longer ships; AI integration lives in
`tools/re-frame2-pair-mcp/`.

---

## Behaviour parity (10x → Xray)

| 10x feature | Xray equivalent | Notes |
|---|---|---|
| Epoch panel (per-event detail) | **Epoch panel** (the numbered cascade) | The hero, lands on every open (`:order -1`). Renders the focused epoch's whole computational timeline as a numbered vertical cascade — DISPATCH → COEFFECT(s) → INTERCEPTOR (conditional) → HANDLER → FLOW(s) → SIDE EFFECTS → SUBSCRIPTIONS → VIEWS — with per-step ✓/✗ status + inline exceptions. Supersedes the earlier Event-detail panel (retired rf2-5gl5r). |
| Event-history list | **L2 event list (the spine timeline)** | Single-line rows decorated by gutter glyph + badges (+ a pink-wash on issue-bearing rows); cascade lineage tags (`:dispatch-id` / `:parent-dispatch-id`) are exposed via the Trace + Epoch tabs rather than a dedicated graph. |
| App-DB inspector + diff | **Slice-centric app-db panel** | Shows the slices that changed plus user-pinned slices. Read-only; mutations go through normal dispatch. Full-tree view is an escape hatch, not the default. |
| Subs panel | Absorbed into the Epoch + Views panels (Views = the renamed Reactive panel; tab key stays `:views`) | Sub recomputation is a property of an event, not its own panel. The Views panel renders the focused epoch's reactive cascade as a DAG (subs + views, with `← :sub-id` / `← props` render-cause chips). The static sub-graph is exposed via the framework's `(rf/sub-topology)` (O-12), not a Xray surface. |
| Trace panel | **Trace-stream panel** | One row per trace event from the Spec 009 trace bus, as a single flat oldest-first list (no bands) with a stage column + colour-coded left edge. Focused-epoch-scoped; **no category filtering UI** (rf2-gkczt) — the focused epoch IS the scope. |
| Time-travel (10x's "back / forward") | **Time-travel scrubber** | Bottom rail. Passive scrubbing rebases the view of history; explicit rewind calls `restore-epoch` with the six failure modes surfaced. |
| Settings / persistence | Not present | Xray is ephemeral by design — no localStorage, no per-user preferences. Configuration lives in `(xray-config/configure! ...)` at preload time. |
| **(new in Xray)** Machine inspector | — | Stately-quality state-chart per registered machine. No 10x equivalent (machines are a v2 addition). |
| **(new in Xray)** Inline issue surfacing | — | There is no separate Issues / schema-violation tab (rf2-gbz39). Schema violations, exceptions, hydration mismatches surface inline in the Epoch cascade (per-step ✓/✗ + Exception card), via the L2 pink-wash, and via the always-on issues-ribbon signal. Schemas / SSR are v2 additions. |
| **(new in Xray)** Hydration debugger | — | Server vs client render-tree side-by-side. Only visible when SSR hydration runs. SSR is a v2 addition. |
| **(new in Xray)** Click-to-source | — | Every rendered DOM element carries `data-rf2-source-coord` in dev builds; clicking jumps to source in the editor. Requires the framework's source-coord stamping, which is dev-only. |

What's intentionally different:

- **Read-only posture.** 10x allowed direct app-db edits from its inspector; Xray does not. State mutations must go through `dispatch` so the cascade is observable through the same surfaces as production code.
- **No persistence.** 10x persisted some panel state to localStorage; Xray keeps nothing across reloads. Configuration is preload-time only.
- **In-app, not sidecar.** 10x was a sidecar panel that occupied a fixed portion of the viewport via body-padding. Xray is true-inline — the app reserves layout space the same way it reserves space for any other UI region.

Full panel inventory: [`tools/xray/spec/000-Vision.md`](../../../tools/xray/spec/000-Vision.md). Per-panel reference: [`docs/xray/02-panel-tour.md`](../../../docs/xray/02-panel-tour.md) onward.

---

## Where to read more

- [`docs/xray/01-installation.md`](../../../docs/xray/01-installation.md) — the canonical install walkthrough (five minutes, three edits).
- [`tools/xray/README.md`](../../../tools/xray/README.md) — entry-point summary, spec index, file layout.
- [`tools/xray/spec/API.md`](../../../tools/xray/spec/API.md) — the full user-facing surface (`configure!`, `popout!`, programmatic open/close, the layout-host contract, `--rf-xray-inline-width`).
- [`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md) — true-inline default + standalone-via-MCP for remote-attach scenarios.
- [`docs/guide/25-from-re-frame-v1.md` §The devtools moved house](../../../docs/guide/25-from-re-frame-v1.md#the-devtools-moved-house) — the narrative version of the 10x → Xray swap.

---

## Reporting

Mention the devtools swap in the migration report's **Anything unexpected** or **Verification** section — not in the M-rule list (it is not an M-rule). Example line:

> *"Dev-deps: dropped `day8.re-frame/re-frame-10x` + its `:preloads` entry; added `day8/re-frame2-xray {:local/root "..."}` + its preload. Added `[data-rf-xray-host]` host to `resources/public/index.html`. `Ctrl+Shift+C` toggles the panel."*

That's it. No rule id; the v1 devtools were never part of the application contract.
