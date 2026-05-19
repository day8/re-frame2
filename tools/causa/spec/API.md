# API

The consolidated user-facing surface. Implementer-readable: every
symbol a consumer of Causa might reach for.

This doc is a **reference**; the normative descriptions live in the
per-area specs (000–011). Where the two drift, the per-area spec
wins.

## Installation API

### Preload-style enablement (browser)

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

Loading the preload runs the foundation's six side-effects — all
gated on `interop/debug-enabled?` so production bundles strip them
via Closure DCE, and all idempotent so shadow-cljs's `:after-load`
cycle re-runs without double-registration:

1. Registers Causa's `:rf.causa/*` subs / events / fxs via
   `registry/register-causa-handlers!`.
2. Registers the trace collector under `:rf.causa/trace-collector`
   via `re-frame.core/register-trace-cb!` (sentinel-guarded).
3. Registers the epoch-settle pump under `:rf.causa/epoch-collector`
   via `re-frame.core/register-epoch-cb!` (sentinel-guarded; no-op
   when the `day8/re-frame2-epoch` artefact is absent).
4. Installs the dev-only browser API on `window.day8.re_frame2_causa.*`
   (`open!`, `toggle!`, `popout!`, `status`, …).
5. Attaches the global keydown listener — `Ctrl+Shift+C` (toggle
   shell).
6. Auto-opens the shell **true-inline** into the host app's
   normal-flow layout host (`[data-rf-causa-host]` by default) once
   the substrate adapter is ready — per rf2-eehov, this is the
   default landing posture. There is no floating pill, no body
   mount, no closed-by-default panel state, and no viewport overlay
   in the default path. Auto-open is suppressed when the host has
   set `(causa-config/configure! {:launch/auto-open? false})`
   before adapter readiness (e.g. Story-only tool pages).

The layout host is sized by the host's stylesheet; the recommended
rule reads `var(--rf-causa-inline-width, 560px)` for its
`flex-basis` so developers can resize the inline panel by
overriding a single CSS custom property anywhere up the cascade
(rf2-um813; default bumped 420 → 560 under rf2-9ovfb). Causa itself
does not read or set the property — the host's stylesheet is the
single source of truth for inline width.

The recommended snippet also publishes `--rf-causa-accent` (default
`#7C5CFF` — the brand violet) on `:root` so host stylesheets can
read it to colour their own dev chrome (resize handles, dock
separators, story chips) without forking the hex (rf2-9ovfb). See
`spec/011-Launch-Modes.md` §Brand-accent CSS variable.

If the layout host selector cannot be found after adapter
readiness, the preload reports the missing host via
`console.error` and `window.day8.re_frame2_causa.status()` and
leaves host startup unblocked.

For the full mount lifecycle, layout-host contract, launch matrix,
suppression knob, and legacy overlay / popout postures, see
[`011-Launch-Modes.md`](./011-Launch-Modes.md) and the repo-root
`README.md`'s install section.

### Published layout-host constants

`day8.re-frame2-causa.config` publishes the layout-host wiring as
named CLJS vars so tooling (story-mode chrome, docs generators) can
refer to the exact spelling without forking the string:

```clojure
day8.re-frame2-causa.config/default-layout-host-selector
;; "[data-rf-causa-host]"
;; — the CSS selector the preload's auto-open path queries on adapter
;;   readiness. Override via (causa-config/configure!
;;   {:layout/host-selector "#devtools-causa"}).

day8.re-frame2-causa.config/default-layout-host-css-var
;; "--rf-causa-inline-width"
;; — the CSS custom property the recommended host snippet reads for
;;   its flex-basis (rf2-um813). Causa never reads this property —
;;   sizing is owned by the host's layout rule. Published so callers
;;   can re-emit the canonical name in their own diagnostics / docs
;;   generators / snippet helpers.

day8.re-frame2-causa.config/default-layout-host-width
;; "560px"
;; — the default value Causa recommends for --rf-causa-inline-width
;;   when the host does not override. Bumped 420 → 560 under rf2-9ovfb
;;   (Pitch8 field feedback: event vectors with map payloads wrap
;;   awkwardly at 420; 560 reads much better for the Event Detail
;;   panel).

day8.re-frame2-causa.config/default-accent-css-var
;; "--rf-causa-accent"
;; — the CSS custom property the recommended host snippet publishes
;;   on :root for Causa's brand-accent colour (rf2-9ovfb). Host
;;   stylesheets read var(--rf-causa-accent) to colour their own dev
;;   chrome to match Causa (resize handles, dock separators, story
;;   chips) without forking the hex. Causa never SETS this property
;;   from CLJS — the host's stylesheet is the single source of truth.

day8.re-frame2-causa.config/default-accent
;; "#7C5CFF"
;; — the default value Causa publishes for --rf-causa-accent. Matches
;;   theme/tokens.cljc's :accent-violet and spec/007-UX-IA.md
;;   §Colour system.

day8.re-frame2-causa.config/default-layout-host-snippet
;; A copy-pasteable HTML + CSS block carrying the recommended host
;; markup, flex-basis read through var(--rf-causa-inline-width, 560px),
;; the :root --rf-causa-accent publish, and the min-width: 320px floor.
;; Reported back to the user in the missing-host diagnostic so the
;; actionable console.error already carries the fix.
```

These are constants, not setters — overriding the selector goes
through `(causa-config/configure! {:layout/host-selector ...})`;
overriding the CSS custom property happens in the host's stylesheet
(per [`011-Launch-Modes.md`](./011-Launch-Modes.md) §Resizing the
inline host).

### Force-disable

```clojure
:closure-defines {day8.re-frame2-causa.config/enabled? false}
```

When set false (or in a production build via `goog.DEBUG=false`), the
preload's entry point is a no-op; no DOM root, no listeners, zero
bytes after elision.

## Public CLJS API

Causa exposes a small handful of programmatic entry points. Users
typically interact via the panel; these are for embedding hosts,
test harnesses, and Settings UIs.

### `day8.re-frame2-causa.core`

```clojure
(causa/init!)
;; Mount Causa manually (alternative to :preloads). Idempotent.

(causa/init! opts)
;; opts: {:default-frame :app/main
;;        :theme         :dark / :light / :high-contrast
;;        :density       :compact / :cosy
;;        :ai-provider   {:provider :claude / :openai / :gemini / :local / :custom
;;                        :api-key  "sk-..."     ;; localStorage only; never sent to Day8
;;                        :model    "claude-3-5-sonnet"
;;                        :system-prompt "..."}
;;        :buffer-depths {:trace 200 :epoch 50}}

(causa/open!)        ;; Show the panel programmatically.
(causa/close!)       ;; Hide the panel programmatically.
(causa/toggle!)      ;; Toggle.
(causa/popout!)      ;; Open the same-browser pop-out window.

(causa/target-frame)        ;; Return the host frame Causa is currently targeting.
(causa/set-target-frame! :app/main)

(causa/load-theme css-string)
;; Programmatically swap the theme (useful for editor-driven palette sync).
```

### `day8.re-frame2-causa.panels.*`

Each panel namespace exports a single public `Panel` component for
embedding (per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)).
The canonical symbol list:

```clojure
day8.re-frame2-causa.panels.event-detail/Panel
day8.re-frame2-causa.panels.app-db-diff/Panel
day8.re-frame2-causa.panels.views/Panel
day8.re-frame2-causa.panels.trace/Panel
day8.re-frame2-causa.panels.machine-inspector/Panel
day8.re-frame2-causa.panels.issues-ribbon/Panel
```

(rf2-qy0nu — the 8-panel dead-code sweep removed `causality-graph`,
`time-travel`, `effects`, `flows`, `routes`, `performance`, `schema-
violation-timeline`, `hydration-debugger`, and `mcp-server`. The
4-layer shell switches over the 6 L3 tab ids in `spec/018-Event-
Spine.md` §5 — these six are the surviving `Panel` exports.)

Each accepts the props map specified in
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md):

```clojure
{:frame    :app/main          ;; frame to observe (required)
 :compact? false              ;; reduced chrome (optional)
 :height   nil                ;; pixels (optional)
 :scope    {...}              ;; filter (optional)
 :on-event #(...)}            ;; emit-event callback (optional)
```

## Public JS API

For React-only hosts (a JS Story consumer, a non-Reagent app):

```javascript
import {init, open, close, toggle, popout} from '@day8/re-frame2-causa';
import {EventDetailPanel, /* ... */} from '@day8/re-frame2-causa/panels';

init({defaultFrame: ':app/main', theme: 'dark', density: 'cosy'});

open();
close();
toggle();
popout();

// Embedded panel
<EventDetailPanel
  frame=":app/main"
  compact
  height={320}
  scope={{dispatchIdPrefix: 'story-variant-7c2-', includeChildren: true}}
  onEvent={(evt) => console.log(evt)}
/>
```

The JS surface is a thin adapter over the Reagent components; props
camelCase what the Reagent surface kebab-cases.

## Trace / epoch surfaces (consumed, not exposed)

Causa **consumes** the framework's surfaces. It does not expose
analogues; users go to the framework for these. Listed here for
reference:

| Surface | Spec | What Causa reads |
|---|---|---|
| `(rf/register-trace-cb! key callback)` | Spec 009 | The trace bus (every operation). |
| `(rf/register-epoch-cb! key callback)` | Tool-Pair | The per-cascade epoch records. |
| `(rf/trace-buffer)` / `(rf/trace-buffer filter)` | Spec 009 | The bounded trace buffer (default 200). |
| `(rf/epoch-history frame-id)` | Tool-Pair | The per-frame epoch ring buffer (default 50). |
| `(rf/restore-epoch frame-id epoch-id)` | Tool-Pair | Used for confirmed rewinds. |
| `(rf/reset-frame-db! frame-id value)` | Tool-Pair | Used for "try anyway" recovery. |
| `(rf/get-frame-db frame-id)` | Spec 002 | The app-db panel's live read. |
| `(rf/compute-sub query-v db)` | Spec 008 | The sub-graph panel's value display. |
| `(rf/registrations kind)` / `(rf/handler-meta kind id)` | Spec 001 | Registry-browser metadata. |
| `(rf/frame-ids)` / `(rf/frame-meta id)` | Spec 002 | The frame picker. |
| `(rf/machines)` | Spec 005 | The machine inspector dropdown — 0-ary; returns the seq of machine-ids registered in the active frame. |
| `(rf/app-schemas frame-id)` | Spec 010 | The schema-violation timeline rows. |
| `(rf/sub-cache frame-id)` (CLJS only) | Tool-Pair | The subscription graph. |
| `:dispatch-id` / `:parent-dispatch-id` (in `:tags`) | Spec 009 | The cascade lineage tags read by event-detail and trace surfaces. |
| `:origin` (in `:tags`) | Spec 009 | The colour-coding axis. |
| Source-coord metadata (`:ns` / `:line` / `:column` / `:file`) | Spec 001 / 006 | Click-to-source — see `Open in editor` below. |
| `data-rf2-source-coord` DOM attribute | Spec 006 | DOM-level source-coord (for the rare cases where DOM event → source is needed). |

## Open in editor (rf2-evgf5)

Every panel that surfaces a source-coord (the event-detail hero, the
machine inspector's state / edge / guard / action chips, the hydration
debugger's render-tree rows, the trace panel's per-event rows, etc.)
wraps the coord in a clickable `open` chip. Click sets
`window.location.href` to a URI-scheme handler the OS
dispatches to the configured editor:

| Editor (config key) | URI scheme |
|---|---|
| `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| `:cursor`           | `cursor://file/<path>:<line>:<column>` |
| `:windsurf`         | `windsurf://file/<path>:<line>:<column>` |
| `:zed`              | `zed://file/<path>:<line>:<column>` |
| `:idea`             | `idea://open?file=<path>&line=<line>&column=<column>` |
| `{:custom <tpl>}`   | user template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

Host applications set the preference at boot via the `configure!`
entry point (full key surface normatively enumerated in
[`015-Configuration.md`](./015-Configuration.md)):

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:editor :cursor})
```

Causa's editor preference is **independent** of Story's
`:rf.story/editor` (hosts that run both tools can route each tool to a
different editor). The shared URI builder lives at
`re-frame.source-coords.editor-uri` (core artefact, CLJC); Causa's
mirror chip (`day8.re-frame2-causa.open-in-editor/open-chip`) consumes
it. Unknown editor keywords fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op; source-coords without
`:file` hide the chip entirely.

## Static mode (rf2-o5f5f.1)

Static mode is an opt-in dual-mode chrome: the surface composer
mounts a 3-layer Static silhouette (no L2 event list) alongside the
default 4-layer Runtime silhouette, with a mode pill at ribbon-left
and a `Cmd-Shift-M` / `Ctrl-Shift-M` chord wired to
`:rf.causa/toggle-mode`. Hosts that want it today opt in via
`configure!`:

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:experimental/static-mode? true})
```

| Surface | Spelling | Notes |
|---|---|---|
| Configure key | `:experimental/static-mode?` | Default `false`. Pre-alpha experimental flag; the default flips to `true` once the placeholder Static sub-tabs ship. Full key contract in [`015-Configuration.md`](./015-Configuration.md) §`:experimental/static-mode?`. |
| Toggle chord | `Cmd-Shift-M` / `Ctrl-Shift-M` | Global keydown listener; fires `:rf.causa/toggle-mode`. Falls through to host/browser shortcuts when the flag is OFF. |
| Mode pill | `data-testid="rf-causa-mode-pill"` | Mounts at ribbon-left under the flag. Click flips mode; `aria-checked` + `data-active-mode` reflect state for stylesheet/automation hooks. |
| Persistence | `causa.mode` (localStorage) | Bare string `"runtime"` / `"static"`. Hydrates on boot; missing/corrupt → `"runtime"` fallback. The flag itself is per-load (not persisted). |
| Toggle event | `:rf.causa/toggle-mode` | Public dispatch surface (chord parity + the palette's `:toggle-mode` verb). |

With the flag OFF the surface composer renders Runtime byte-identical
to the pre-Static chrome — no pill, the chord falls through, and the
persisted mode value is not consulted. See [`007-UX-IA.md`](./007-UX-IA.md)
§Static mode (visual-language treatment) and
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface
(architectural contract).

## Density — one CSS-var, whole scale (rf2-n8i2c)

Causa's type scale resolves through a single host-overridable CSS
custom property, **`--rf-causa-font-size`** (modelled on TanStack
Query Devtools' `--tsqd-font-size`). Each typographic token is
`calc(var(--rf-causa-font-size, 13px) * <multiplier>)` so one variable
rescales the entire shell on the next style flush — no re-render
required.

| Surface | Spelling | Notes |
|---|---|---|
| CSS variable | `--rf-causa-font-size` | The anchor for every type-scale entry. Default `13px`, published on `:root` by `theme/global-styles/motion-css`. Below `10px`: refused (the `:micro` token sits at the floor). |
| Host override | `:root { --rf-causa-font-size: 14px }` | A single stylesheet rule rescales every typographic surface ~1.08× without a code change. |
| Density Settings consumer | `:density` (`:compact` / `:cosy` / `:comfy`) | The Settings → General Density radio is the in-shell consumer of the same var. Mapping: `:compact 12px`, `:cosy 13px` (default), `:comfy 14px` (catalogued; not surfaced in v1's radio). Persisted under `:density` in the Settings localStorage slot. |
| Writer | `effects/apply-density-font-size!` | Idempotent; writes the resolved px value into `--rf-causa-font-size` on both the Causa shell root AND `<html>` (so popout/fullscreen mounts inherit). Re-runs on boot from `apply-all!` so a persisted density survives reload before first paint. |

`--rf-causa-font-size` is **distinct** from `--rf-causa-text-size`
(the Settings → General Text-size slider's user-knob, rf2-9poxq):
two CSS vars, two knobs, one shell. Hosts that want a single density
knob target `--rf-causa-font-size` and leave the slider's var alone.
See [`007-UX-IA.md`](./007-UX-IA.md) §Sizes — one knob, whole scale.

## Command palette (rf2-ybjkx)

The palette is a centred 560px modal opened via the global
`Cmd-K` / `Ctrl-K` chord (also reachable from the top-strip control).
Closes on `Esc`, click-outside, or invocation of any item.

| Surface | Spelling | Notes |
|---|---|---|
| Open chord | `Cmd-K` / `Ctrl-K` | Global keydown listener; mounts the palette dialog (`data-testid="rf-causa-palette-dialog"`). |
| Recents localStorage key | `re-frame2.causa.palette.recents.v1` | Top-3 ring of command-ids only (verbs, tab-jumps) — never event-ids, handler-ids, or host-app data. Best-effort persistence; quota/availability failures swallowed. |
| Recents app-db slot | `:rf.causa.palette/recents` | Hydrates on first palette open via `recents/load`; the reducer (`recents/record`) is pure `update + distinct + take 3`. |
| Reduced-motion override | `:cycle-reduced-motion` verb | Three-state cycle `:os → :always → :never` that overrides `prefers-reduced-motion: reduce` via the `--rf-causa-motion-scale` seam in `theme/global-styles/motion-css`. Persists across reloads. |
| Mode-aware filter | `:modes` set per item | Every palette item carries `#{:runtime}` / `#{:static}` / `#{:runtime :static}`; the aggregator (`palette/sources/by-mode-pred`) filters by membership against the active `:rf.causa/mode`. Items missing `:modes` fall through to both modes. |

The six chord-reachable command verbs that ship post-rf2-ybjkx —
`:toggle-theme`, `:cycle-reduced-motion`, `:snapshot-app-db`,
`:jump-to-settings`, `:toggle-mode`, `:clear-epoch-history` — are
catalogued at
`tools/causa/src/day8/re_frame2_causa/palette/sources.cljc`
§`command-items`. There is no public verb-registration API at v1.0
(consistent with §What this doesn't expose); the catalogue is
internal but the chord + recents key + reduced-motion override are
public surfaces hosts may rely on.

See [`007-UX-IA.md`](./007-UX-IA.md) §Command palette for the full
indexed-sources list, fuzzy-match algorithm, recents-boost decay
shape, and close behaviour.

## MCP API

Per rf2-hvl1g (closure 2026-05-19) there is no dedicated `causa-mcp`
jar. AI agent access to Causa's surfaces flows via
`tools/re-frame2-pair-mcp/` against the framework-published Causa
runtime API (`day8.re-frame2-causa.runtime`) — agents read the same
trace bus + epoch history + registrar Causa itself reads, via
re-frame2-pair-mcp's `eval-cljs` and the runtime accessors. See
DESIGN-RATIONALE.md Lock #6 (superseded) for the reasoning.

## Settings keys

Settings persist in `localStorage` under the key
`day8.re-frame2-causa/settings/v1`. Distinct from the boot-time
`configure!` surface enumerated in
[`015-Configuration.md`](./015-Configuration.md) (which writes
process-global atoms) and from `(causa/init! opts)` above (which
wires per-instance panel state). Shape (validated by Malli):

```clojure
{:theme         :dark / :light / :high-contrast
 :density       :compact / :cosy
 :ai-provider   {:provider :claude / :openai / :gemini / :local / :custom
                 :api-key      "sk-..."
                 :model        "claude-3-5-sonnet"
                 :system-prompt "..."
                 :custom-url   "https://..."     ;; only when :provider = :custom
                 :custom-headers {"X-..." "..."}}
 :buffer-depths {:trace 200 :epoch 50}
 :default-frame :app/main
 :sidebar-mode  :grouped / :show-all
 :launcher-pill {:hidden? false}
 :keybindings   {:toggle ["Ctrl+Shift+C"]   ;; vector for multiple binds
                 ...}}
```

Corruption (schema fails) → Causa wipes the slot and writes the
default shape, surfacing a one-time toast: "Settings were corrupted
and have been reset to defaults."

> **rf2-e9tb0 — pinned-slices localStorage slot deprecated.** The
> per-frame-app-db pinned-slices key (`day8.re-frame2-causa/pinned-
> slices/<frame-id>/v1`) is no longer written; the pinned-watches
> strip was superseded by the App-DB Diff segment-inspector popup
> (per `004-App-DB-Diff.md` §Clickable path segments). Legacy slots
> are ignored on read — Causa never resurrects them.

## Trace-event tags Causa emits

When Causa mutates the runtime (rewind, reset, re-dispatch), it
emits trace events tagged `:origin :causa` so its actions are visible
in the trace stream. (Origins from MCP servers carry their own
server-name tag — re-frame2-pair-mcp uses `:origin :re-frame2-pair-mcp`.)

These ride the framework's existing `:event/dispatched`,
`:rf.epoch/restored`, etc. operations — no new operation kinds
invented (per [`Principles.md`](./Principles.md) §Observation only).

## Versioning

`day8/re-frame2-causa` follows semver. Major (1.x → 2.x) changes
break the public API or the embed contract. Minor (1.0 → 1.1) adds
panels or surfaces. Patch (1.0.0 → 1.0.1) fixes bugs without
contract changes.

The framework dep is `~> 1.0` (compatible with re-frame2's first
stable release). When the framework moves to 2.0, Causa's matching
major bumps with it.

## What this doesn't expose

- **No plugin registration API.** First-party panels only at v1.0.
- **No middleware injection.** Causa does not intercept dispatches;
  it only observes them.
- **No "private" surfaces** (`/-` namespaces, internal helpers) —
  callers must not reach for `day8.re-frame2-causa.internal/*`.
- **No global state mutators** beyond `init!` / `open!` / `close!`
  / `toggle!` / `set-target-frame!`. The panel's internal state is
  encapsulated.
- **No `dock!` / `undock!` body-padding surface.** Removed per
  `rf2-sbfb7` (Mike's pre-alpha decision "A — delete both"); the
  true-inline default and `popout!` cover the dock use case.
- **No imperative `mount-inline-panel!` / `unmount-inline-panel!`.**
  Removed per `rf2-sbfb7`; declarative panel embedding lives at
  [`008-Embedding-Contract.md`](./008-Embedding-Contract.md).

## Resolved decisions

| Decision | Bead | Outcome |
|---|---|---|
| Keep or delete `dock!` / `undock!` / `mount-inline-panel!` / `unmount-inline-panel!` debug surfaces | `rf2-sbfb7` | "A — delete both" (Mike, 2026-05-17). Pre-alpha posture: no back-compat shims; the true-inline default + `popout!` cover the dock use case, declarative `Panel` (008-Embedding-Contract) covers the embedded-panel use case. |
