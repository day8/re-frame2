# xray-replaces-10x

The devtools swap. v1 ships `day8.re-frame-10x`; v2 ships **Xray** (`day8/re-frame2-xray`). **Xray IS the v2 devtools replacement for re-frame-10x** — a from-scratch reimplementation against re-frame2's own trace bus and epoch-history surfaces, not a port of 10x. The mental model (events, subs, app-db diff, time-travel) carries over; the wiring underneath does not. See [`docs/core/25-from-re-frame-v1.md` §The devtools moved house](../../../docs/core/25-from-re-frame-v1.md#the-devtools-moved-house) for the narrative version of this swap.

**This is a STANDARD, first-class, EXPECTED migration step — not an optional adjunct.** **The rule: 10x present ⇒ swap to Xray (standard); no 10x ⇒ Xray optional.** The swap is triggered, and carried to completion, whenever the dep file holds a `day8.re-frame-10x` coord or a `day8.re-frame-10x.preload` `:preloads` entry; its **done-state is the app ON Xray**, not merely the dead preload removed — dropping 10x without restoring Xray leaves the author worse off than they started. If the app **never** had re-frame-10x, do **not** force devtools on it — Xray is then a genuine offer the author can decline.

**"Not an M-rule" ≠ "optional."** No *application code* triggers this swap (M-rules key off application-code surfaces), so it carries no `M-N` id — a *taxonomy* fact, not a priority signal. It runs as a **two-stage swap straddling the M-rule sweep**:

1. **M-0 — neutralize the dead preload (now).** Excluding v1 `re-frame` at M-0 makes `day8.re-frame-10x.preload` uncompilable, so the dead preload blocks the post-M-0 "stop and compile" gate. Remove the 10x dep coord + its `:preloads` entry (+ any 10x `closure-defines` flag) in the same M-0 dep-file edit so the gate is reachable. → [`setup.md` §Neutralize the re-frame-10x preload as part of M-0](setup.md#neutralize-the-re-frame-10x-preload-as-part-of-m-0).
2. **Post-M-40 — mount Xray (the restore).** The Xray preload auto-opens *after* `(rf/init!)` runs, so it can't mount until boot wiring is in place. Once the M-rule sweep reaches M-40 and a clean reload is verified, add the Xray dep + preload + `[data-rf-xray-host]` layout host — completing the swap and landing the app on Xray.

Stage 1 unblocks the compile gate; stage 2 is the point of the whole swap.

> **Track the two stages as ONE deliverable, end to end.** For a 10x app this swap is a **REQUIRED deliverable** detected in the Phase-0a inventory ([`inventory-and-plan.md` §Step 1](inventory-and-plan.md#step-1--inventory-the-v1-add-on-libraries-on-the-classpath)) — it has no `M-N` id, so the M-rule sweep never reminds you of it, and the two halves sit on opposite sides of the sweep (M-0, then post-M-40). That split is exactly what makes it easy to do stage 1 and lose stage 2 — **dropping 10x without restoring Xray leaves the author worse off than they started.** The plan tracks both halves as a **single item** and the done-state is **the app ON Xray** (stage 2 landed), never "the dead preload removed" (stage 1 only). The SKILL.md main-flow checklist line and the Done checklist both carry this both-halves done-state.

## Contents

- The swap (dep + preload)
- The npm peer-deps the swap requires (`@xyflow/react`, `elkjs`)
- The layout host (true-inline default)
- Resizing the inline host (`--rf-xray-inline-width`)
- Keybindings (what's actually wired)
- Behaviour parity (10x → Xray)
- Where to read more
- Reporting

---

## The swap (dep + preload)

### Prerequisites — apply BEFORE this swap

**M-40 (`(rf/init!)` call) must already be applied.** Xray's preload auto-opens the panel into `[data-rf-xray-host]` *after* `rf/init!` runs (per `preload.cljs`); without an explicit init call the panel silently fails to mount — the host element is in the DOM, the preload loaded, no console error fires, and the panel never appears. This is the most confusing failure mode in the migration. A **second** mount failure presents the same way but has a different cause — the Xray dep sitting in a `:dev` alias the dev build was **not** started with, so the dep is off the classpath; see [§2. Add Xray](#2-add-xray-dev-deps-only) for the `-A :dev` fix (and the top-level-`:deps` alternative that removes the coupling). When the panel is absent, rule out all three together: M-40 applied? host element present? dev build started with `-A :dev` (or Xray in top-level `:deps`)? If the codebase is mid-M-rule sweep and M-40 hasn't been applied yet, do M-40 first and verify a clean reload, then come back here.

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
;; <path-to-re-frame2> is the local pinned re-frame2 checkout the kickoff
;; prompt names — an ABSOLUTE path (or one relative to THIS project's deps.edn).
{:aliases {:dev {:extra-deps {day8/re-frame2-xray {:local/root "<path-to-re-frame2>/tools/xray"}}}}}
```

While re-frame2 is in alpha, use the `:local/root` route into the same local pinned `day8/re-frame2` checkout the kickoff prompt already names as `<path-to-re-frame2>` (the one holding `MIGRATION.md`). **Do not write `:local/root "tools/xray"`** — a bare relative `tools/xray` resolves against the *target* project's directory, where no such path exists; the migration target is not the re-frame2 repo. Use `<path-to-re-frame2>/tools/xray` (absolute, or relative to this project's `deps.edn`), or whatever path the author supplies. Once Xray publishes to Clojars, the coord becomes `day8/re-frame2-xray {:mvn/version "<VERSION>"}` (tracking re-frame2's lockstep `<VERSION>`) and the `<path-to-re-frame2>` indirection drops away. The skill prints the `<path-to-re-frame2>/tools/xray` form when the author hasn't told it otherwise; if the author wants the published coord, they say so in the kickoff prompt.

> **Start the dev build WITH the `:dev` alias.** shadow-cljs only puts a `:dev`-alias `:extra-deps` coord on the classpath when the build is **invoked with that alias** — `npx shadow-cljs watch app -A :dev`. A bare `npx shadow-cljs watch app` (no alias) leaves the Xray dep off the classpath, so the `day8.re-frame2-xray.preload` `:preloads` entry can't resolve and the panel never mounts. This presents **identically** to the M-40-not-applied and missing-host failures (the preload "loaded", no panel, no console error pointing at the alias as the cause). If the panel is absent yet M-40 *is* applied and the host element *is* present, suspect the alias next — restart with `-A :dev`.
>
> **Simpler alternative — put Xray in top-level `:deps`.** Moving the Xray coord out of the `:dev` alias and into the project's top-level `:deps` (always on the classpath) sidesteps the alias-invocation coupling entirely, so a bare `npx shadow-cljs watch app` mounts Xray with no `-A :dev` to remember. This stays production-safe because Xray is dev-only **by construction**: its preload lives only in the dev build's `:devtools/preloads` (release builds never run it), and the framework instrumentation Xray hooks elides every byte under `goog.DEBUG false` via the universal `re-frame.interop/debug-enabled?` gate (an alias of `goog.DEBUG` — see §2a), with the CI bundle-isolation gate as the backstop. A coord merely on the classpath never reaches the production bundle on its own.

`day8/re-frame2-xray` declares `day8/re-frame2-epoch` as a hard dep — no separate add is required. Xray's epoch-aware panels (the time-travel scrubber, the event-detail panel) read from `re-frame.epoch`'s seed table via `rf/epoch-history` / `(rf/register-listener! :epoch …)`; without the epoch artefact those panels render empty even when events have fired. The dep is pulled in transitively by adding Xray.

### 2a. Add the npm peer-deps Xray pulls at compile time (`@xyflow/react`, `elkjs`)

Xray also depends (transitively, via `day8/re-frame2-machines-viz`) on **two npm packages** its Machine-inspector chart panel imports — `@xyflow/react` (React Flow) and `elkjs` (ELK graph layout). These are *JS* deps, not CLJS coords, so the `:local/root` add above does **not** pull them. An app that follows the steps so far and rebuilds hits a hard **BUILD FAILURE** (shadow-cljs, `:deps true`):

> `The required JS dependency "@xyflow/react" is not available, it was required by ...`

(then `elkjs/lib/elk.bundled.js` next). This is a **LOUD-fail** (it stops the compile, so it is at least self-revealing) — but install the packages up front to save a failed-build / restart cycle:

```bash
# In the target project's npm root (where package.json lives).
# Pin in lockstep with re-frame2's implementation/package.json.
npm install --save-dev @xyflow/react@12.4.2 elkjs@^0.11.1
```

Keep the versions **in lockstep** with the re-frame2 checkout's `implementation/package.json` (`<path-to-re-frame2>/implementation/package.json`) — read the current `@xyflow/react` / `elkjs` versions there rather than trusting the literals above, which track a point-in-time pin (at this writing `@xyflow/react 12.4.2`, `elkjs ^0.11.1`). They are dev-only (the Machine inspector is dev-only chrome) and elide with the rest of Xray in production builds. If the project's shadow-cljs build is `:js-options {:resolve ...}`-customised or uses a non-default `node_modules` location, install into whichever root that build resolves from.

Xray is **dev-only by construction** — production builds elide every byte of it through the framework's `re-frame.interop/debug-enabled?` gate (`goog.DEBUG=false`). A CI gate at `implementation/scripts/check-bundle-isolation.cjs` greps production bundles for Xray-internal sentinels; any hit is a release blocker. See [`tools/xray/README.md` §Bundle isolation](../../../tools/xray/README.md#bundle-isolation).

### 3. Wire the preload

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's listeners under `register-listener!` (across the streams it needs, including `:epoch`), attaches the global keydown listener (`Ctrl+Shift+C` and the rest — see [Keybindings](#keybindings-whats-actually-wired)), and auto-opens the panel into the layout host after `rf/init!`. No `(require '[day8.re-frame2-xray.core])`. No `init!` call. The preload plus the host element are the full integration surface.

### 4. Set your editor for clickable jump-to-source

For Xray's `open` chips to jump to source on click, Xray must know which editor to open — the bare preload defaults to the `:vscode` scheme. Until you tell Xray your editor, a click does not navigate; instead it surfaces a **"No editor configured" hint** (a bottom-corner toast with an **Open Settings** button) so the dead click is never silent. Set your editor in **Xray Settings** ("Click-to-source links open in" on the General tab — persisted per-dev) or once at boot, and clicks navigate straight to source:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor}) ; / :vscode (default) / :windsurf / :zed / :idea / {:custom "<uri-template>"}
```

`:rf.xray/project-root` is **only** needed when stamped source-coords are classpath-*relative*. The normal `reg-*` / `reg-machine` path stamps **absolute** coords (shipped verbatim), so leave it unset — do not hardcode a machine-specific path to "make Open work" when absolute coords already resolve. See [`docs/xray/01-installation.md` §Clickable Jump-To-Source](../../../docs/xray/01-installation.md#clickable-jump-to-source).

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

The global keydown listener in `tools/xray/src/day8/re_frame2_xray/keybinding.cljs` wires three modifier chords (always live whenever the listener is attached) plus a set of focus-gated bare keys (live only inside the Xray shell).

**Global chords** — fire anywhere on the page:

| Action | Keys | Notes |
|---|---|---|
| Toggle the Xray panel (show / hide) | `Ctrl+Shift+C` | Toggles visibility of the mounted shell; does not unmount. `Ctrl` only — see Cross-OS below. |
| Toggle Dynamic ↔ Static mode | `Cmd+Shift+M` / `Ctrl+Shift+M` | Dispatches `:rf.xray/toggle-mode` on the `:rf/xray` frame. Accepts either Cmd (macOS) or Ctrl. |
| Toggle the command palette | `Cmd+K` / `Ctrl+K` | Dispatches `:rf.xray/palette-toggle` on the `:rf/xray` frame; opens the shell first if it isn't visible. Accepts either Cmd (macOS muscle-memory) or Ctrl (Windows/Linux). |

**Spine / shell bare keys** — fire ONLY when the shell is visible, the keydown target is inside the Xray shell DOM (`[data-testid="rf-xray-shell"]`), the target is not an editable element (`<input>` / `<textarea>` / `<select>` / `contenteditable`), and the target is not inside an Xray modal (Settings popup or command palette). No modifier:

| Action | Keys | Dispatches |
|---|---|---|
| Pause / resume the LIVE feed | `Space` | `:rf.xray/toggle-live-pause` |
| Snap to LIVE (follow head) | `L` | `:rf.xray/follow-head` |
| Fast-forward to head ("Go to head") | `Shift+G` | `:rf.xray/follow-head` |
| Step backward through events | `j` | `:rf.xray/focus-event-prev` |
| Step forward through events | `k` | `:rf.xray/focus-event-next` |
| Toggle the Settings popup | `,` or `s` | `:rf.xray/settings-toggle` |

**Cross-OS:** the shell-toggle is `Ctrl+Shift+C` (not `Cmd`) on every host OS. macOS Safari sometimes maps `Cmd+Shift+C` to dev-tools' Inspect; the `Ctrl` modifier avoids that collision. macOS users who prefer `Cmd+Shift+C` can rebind in their browser's keyboard-shortcut UI. The mode-toggle and command-palette chords intentionally accept *either* Cmd or Ctrl, so macOS users get the muscle-memory Cmd form and Windows/Linux users get Ctrl.

**Pop-out is NOT a keybinding.** Pop-out to a second window is reachable programmatically via `window.day8.re_frame2_xray.popout_BANG_()` (or `(xray/popout!)` from CLJS); the keydown listener does not handle a pop-out chord. The command palette, by contrast, IS wired (`Cmd/Ctrl+K`, above). Do not claim a pop-out keybinding exists.

The earlier `Ctrl+Shift+/` co-pilot keybinding has been removed —
the AI co-pilot rail no longer ships; AI integration lives in
`tools/re-frame2-pair-mcp/`.

---

## Behaviour parity (10x → Xray)

| 10x feature | Xray equivalent | Notes |
|---|---|---|
| Epoch panel (per-event detail) | **Epoch panel** (the numbered cascade) | The hero, lands on every open (`:order -1`). Renders the focused epoch's whole computational timeline as a numbered vertical cascade — DISPATCH → COEFFECT(s) → INTERCEPTOR (conditional) → HANDLER → FLOW(s) → SIDE EFFECTS → SUBSCRIPTIONS → VIEWS — with per-step ✓/✗ status + inline exceptions. Supersedes the earlier Event-detail panel (now retired). |
| Event-history list | **L2 event list (the spine timeline)** | Single-line rows decorated by gutter glyph + badges (+ a pink-wash on issue-bearing rows); cascade lineage tags (`:dispatch-id` / `:parent-dispatch-id`) are exposed via the Trace + Epoch tabs rather than a dedicated graph. |
| App-DB inspector + diff | **Slice-centric app-db panel** | Shows the slices that changed plus user-pinned slices. Read-only; mutations go through normal dispatch. Full-tree view is an escape hatch, not the default. |
| Subs panel | Absorbed into the Epoch + Views panels (Views = the renamed Reactive panel; tab key stays `:views`) | Sub recomputation is a property of an event, not its own panel. The Views panel renders the focused epoch's reactive cascade as a DAG (subs + views, with `← :sub-id` / `← props` render-cause chips). The static sub-graph is exposed via the framework's `(rf/sub-topology)` (O-12), not a Xray surface. |
| Trace panel | **Trace-stream panel** | One row per trace event from the Spec 009 trace bus, as a single flat oldest-first list (no bands) with a stage column + colour-coded left edge. Focused-epoch-scoped; **no category filtering UI** — the focused epoch IS the scope. |
| Time-travel (10x's "back / forward") | **Time-travel scrubber** | Bottom rail. Passive scrubbing rebases the view of history; explicit rewind calls `restore-epoch` with the six failure modes surfaced. |
| Settings / persistence | **Persisted operator preferences + filters (localStorage)** | Xray persists **operator preferences** (the in-shell Settings popup — density, sidebar mode, keybindings, buffer depths, …) under the localStorage key `day8.re-frame2-xray/settings/v1`, and **Trace filter pills** under `re-frame2.xray.filters.v1` (override via `set-filters-storage-key!`). Boot-time `(xray-config/configure! ...)` is the *default* layer; persisted Settings override per-knob (merge order: defaults < `configure!` < persisted Settings). **App-db / epoch / trace payloads stay runtime/dev-only** — they are not persisted unless explicitly exported. See [`docs/xray/api/config-keys.md` §Boot-time config vs persisted Settings](../../../docs/xray/api/config-keys.md#boot-time-config-vs-persisted-settings). |
| **(new in Xray)** Machine inspector | — | Stately-quality state-chart per registered machine. No 10x equivalent (machines are a v2 addition). |
| **(new in Xray)** Inline issue surfacing | — | There is no separate Issues / schema-violation tab. Schema violations, exceptions, hydration mismatches surface inline in the Epoch cascade (per-step ✓/✗ + Exception card), via the L2 pink-wash, and via the always-on issues-ribbon signal. Schemas / SSR are v2 additions. |
| **(new in Xray)** Hydration debugger | — | Server vs client render-tree side-by-side. Only visible when SSR hydration runs. SSR is a v2 addition. |
| **(new in Xray)** Click-to-source | — | Every rendered DOM element carries `data-rf2-source-coord` in dev builds; clicking jumps to source in the editor. Requires the framework's source-coord stamping, which is dev-only. |

What's intentionally different:

- **Read-only posture.** 10x allowed direct app-db edits from its inspector; Xray does not. State mutations must go through `dispatch` so the cascade is observable through the same surfaces as production code.
- **Persistence is scoped to operator preferences.** Like 10x, Xray persists some operator state to localStorage — the Settings popup preferences (`day8.re-frame2-xray/settings/v1`) and the Trace filter pills (`re-frame2.xray.filters.v1`) survive reloads. What it does NOT persist is **app state**: app-db, epoch history, and the trace stream are runtime/dev-only and vanish on reload unless explicitly exported. Boot-time `configure!` supplies defaults; persisted Settings override per-knob.

> **Privacy / security migration note.** Because Xray writes namespaced localStorage keys (`day8.re-frame2-xray/settings/v1`, `re-frame2.xray.filters.v1`), do **not** report a 10x→Xray migration as "leaves no persisted browser state." Teams with devtools-storage privacy/security rules must audit, override (`set-filters-storage-key!`), or clear those namespaced keys where required. The persisted data is operator *preferences/filters*, not app/user data — but the keys still exist on disk.
- **In-app, not sidecar.** 10x was a sidecar panel that occupied a fixed portion of the viewport via body-padding. Xray is true-inline — the app reserves layout space the same way it reserves space for any other UI region.

Full panel inventory: [`tools/xray/spec/000-Vision.md`](../../../tools/xray/spec/000-Vision.md). Per-panel reference: [`docs/xray/02-panel-tour.md`](../../../docs/xray/02-panel-tour.md) onward.

---

## Where to read more

- [`docs/xray/01-installation.md`](../../../docs/xray/01-installation.md) — the canonical install walkthrough (five minutes, three edits).
- [`tools/xray/README.md`](../../../tools/xray/README.md) — entry-point summary, spec index, file layout.
- [`tools/xray/spec/API.md`](../../../tools/xray/spec/API.md) — the full user-facing surface (`configure!`, `popout!`, programmatic open/close, the layout-host contract, `--rf-xray-inline-width`).
- [`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md) — true-inline default + standalone-via-MCP for remote-attach scenarios.
- [`docs/core/25-from-re-frame-v1.md` §The devtools moved house](../../../docs/core/25-from-re-frame-v1.md#the-devtools-moved-house) — the narrative version of the 10x → Xray swap.

---

## Reporting

Report the swap as completed work in the report's **Verification** section (no `M-N` id, so not the M-rule list). Example line:

> *"Devtools (Xray replaces 10x — standard for a 10x app): dropped `day8.re-frame/re-frame-10x` + its `:preloads` entry at M-0; post-M-40 added `day8/re-frame2-xray {:local/root "..."}` + its preload + a `[data-rf-xray-host]` host in `resources/public/index.html` + the npm peer-deps `@xyflow/react@12.4.2` and `elkjs@^0.11.1` (compile-time deps of the Machine-inspector chart). `Ctrl+Shift+C` toggles the panel — app is on Xray."*

(No re-frame-10x in the project ⇒ nothing to report; don't add devtools the app never had.)
