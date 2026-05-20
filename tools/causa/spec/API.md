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
   via `re-frame.core/register-listener!` (sentinel-guarded).
3. Registers the epoch-settle pump under `:rf.causa/epoch-collector`
   via `re-frame.core/register-epoch-listener!` (sentinel-guarded; no-op
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
   set `(causa-config/configure! {:rf.causa/auto-open? false})`
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
;;   {:rf.causa/layout-host-selector "#devtools-causa"}).

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
through `(causa-config/configure! {:rf.causa/layout-host-selector ...})`;
overriding the CSS custom property happens in the host's stylesheet
(per [`011-Launch-Modes.md`](./011-Launch-Modes.md) §Resizing the
inline host).

### Force-disable

```clojure
:closure-defines {re-frame.interop/debug-enabled? false}
```

When set false (or in a production build via `goog.DEBUG=false`), the
preload's entry point is a no-op; no DOM root, no listeners, zero
bytes after elision.

## Public CLJS API

Causa's user-facing surface is split into a **canonical entry point**
(the `day8.re-frame2-causa.core` facade — the surface most hosts ever
touch) and a **wider public surface** of supporting namespaces (config
setters, panel components, the keybinding lifecycle escape hatch, the
preload-installed browser-global API, and the MCP read-and-mutate
seam). The canonical entry point is the one to reach for by default;
the wider surface is documented for embedding hosts, test harnesses,
Settings UIs, and tool integrators that need finer-grained access. Per
rf2-te1gu (reconcile the previous "small handful" framing with
implementation reality — ~40 symbols across 6 namespaces).

### Canonical: `day8.re-frame2-causa.core`

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

The facade also re-exports the four highest-traffic config setters
(`configure!`, `set-auto-open!`, `set-editor!`, `set-show-sensitive!`)
so the common boot-time wiring lands in one require. The full setter
inventory lives in `day8.re-frame2-causa.config` — see §Wider public
surface below.

### Wider public surface

Beyond the canonical facade, Causa exposes additional public surfaces
for embedding hosts, test harnesses, Settings UIs, and tool
integrators. Each is in scope of the same versioning discipline as
the facade (no breaking changes within a minor release; deprecations
announced one minor ahead). The canonical entry point above carries
the day-to-day surface; this section is the **complete** index so a
reader scanning for "what's publicly callable?" has a single
authoritative list.

| Namespace | Source | Public surfaces |
|---|---|---|
| `day8.re-frame2-causa.core` | `core.cljs` | The 12 canonical re-exports above (`init!`, `open!`, `open-overlay!`, `close!`, `toggle!`, `popout!`, `status`, `target-frame`, `set-target-frame!`, `load-theme`, plus the four highest-traffic config setters re-exported for boot-time convenience: `configure!`, `set-auto-open!`, `set-editor!`, `set-show-sensitive!`). |
| `day8.re-frame2-causa.panels.*` | `panels/*.cljs` | The 9 `Panel` reg-views — `event-detail/Panel`, `app-db-diff/Panel`, `reactive-panel/Panel`, `trace/Panel`, `machine-inspector/Panel`, `machines-canvas.panel/Panel`, `routing/Panel`, `issues-ribbon/Panel`, `chrome-a11y.panel/Panel` (per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) + [`018-Event-Spine.md`](./018-Event-Spine.md) §The 9 tabs). |
| `day8.re-frame2-causa.config` | `config.cljc` | The `configure!` map dispatcher, the per-key setters (`set-editor!`, `set-project-root!`, `set-layout-host-selector!`, `set-auto-open!`, `set-keybinding-enabled!`, `set-static-mode-enabled!`, `set-show-sensitive!`, `set-filter-seed!`, `set-filters-storage-key!`, `update-setting!`, `reset-settings!`, `reset-suppressed-count!`) and the published constants enumerated in §Published layout-host constants above. The full normative key inventory lives in [`015-Configuration.md`](./015-Configuration.md); the **key-naming axis** (how authors navigate the 10-now-30-planned key surface by topical cluster prefix — editor / launch / keybinding / static-mode / settings / filters / render / trace / logging) is documented at [`015-Configuration.md` §Key-naming axis](./015-Configuration.md#key-naming-axis--navigation-map-rf2-dz35f--audit-of-audits-16) per `rf2-dz35f`. |
| `day8.re-frame2-causa.keybinding` | `keybinding.cljs` | `attach!` / `detach!` — the symmetric, idempotent lifecycle pair for the `Ctrl+Shift+C` global listener. `detach!` is the embed-host escape hatch documented at [`015-Configuration.md`](./015-Configuration.md) §`keybinding/detach!` and [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Full-shell embed contract — needed when an embed host's mount lifecycle runs after Causa's preload and wants to take the chord back. |
| `day8.re-frame2-causa.runtime` | `runtime.cljs` | The Causa ↔ MCP read-and-mutate seam. The accessor surface this namespace exposes is enumerated normatively in §Runtime accessor surface below. Tool clients (`tools/re-frame2-pair-mcp/` today) evaluate forms addressed at this namespace via `eval-cljs`. |
| `window.day8.re_frame2_causa.*` | `preload.cljs` | The browser-global JS API the preload installs (`interop/debug-enabled?`-gated). The exact Closure-name-mangled spellings: `open_BANG_`, `open_overlay_BANG_`, `close_BANG_`, `toggle_BANG_`, `popout_BANG_`, `status`. Mirrored under `window.day8.re_frame2_causa.core.*` once `core.cljs` has loaded so JS-console users see the canonical facade names. Production builds elide the install entirely via the `interop/debug-enabled?` gate. |

Three surfaces deliberately not re-exported through the canonical
facade:

- **The per-key setters in `config.cljc`** beyond the four
  highest-traffic ones (`configure!`, `set-auto-open!`, `set-editor!`,
  `set-show-sensitive!`). Hosts that want to flip an experimental knob
  or a less-common setter (`set-project-root!`,
  `set-keybinding-enabled!`, `set-static-mode-enabled!`,
  `set-filter-seed!`, etc.) require `day8.re-frame2-causa.config`
  directly. The split keeps the facade narrow without hiding the
  setters; reads cleanly from boot code that's already going through
  `configure!`.

- **`keybinding/attach!` and `keybinding/detach!`.** The lifecycle
  pair lives in its own namespace because the preload's keybinding
  install is one of the six side-effects (per §Installation API
  above) and `detach!` is the embed-host escape hatch — both surfaces
  are tightly coupled to the preload's listener contract rather than
  to the mount facade. Re-exporting through `core` would imply
  symmetry with `open!`/`close!` (mount-side) that the surfaces
  don't have.

- **`runtime.cljs`.** The Causa ↔ MCP seam is a parallel public
  surface — public-for-tools, not public-for-host-apps — and the
  read/mutate accessors are documented under §Runtime accessor
  surface below as their own contract surface (the same Tool-Pair
  discipline that governs `:trace-bus` and `epoch-history` per
  [`Principles.md`](./Principles.md) §Observation only). Re-exporting
  through `core` would conflate the host-facing facade with the
  tool-facing read seam.

### Panel reg-views

Each panel namespace exports a single public `Panel` component. The
canonical symbol list:

```clojure
day8.re-frame2-causa.panels.event-detail/Panel
day8.re-frame2-causa.panels.app-db-diff/Panel
day8.re-frame2-causa.panels.reactive-panel/Panel
day8.re-frame2-causa.panels.trace/Panel
day8.re-frame2-causa.panels.machine-inspector/Panel
day8.re-frame2-causa.panels.machines-canvas.panel/Panel
day8.re-frame2-causa.panels.routing/Panel
day8.re-frame2-causa.panels.issues-ribbon/Panel
day8.re-frame2-causa.panels.chrome-a11y.panel/Panel
```

(rf2-qy0nu — the 8-panel dead-code sweep removed `causality-graph`,
`time-travel`, `effects`, `flows`, `routes`, `performance`, `schema-
violation-timeline`, `hydration-debugger`, and `mcp-server`. The
4-layer shell switches over the L3 tab ids in
[`018-Event-Spine.md`](./018-Event-Spine.md) §The 9 tabs — these
nine are the surviving `Panel` exports. The L4 display label for
`reactive-panel/Panel` is **Reactive** (per `spec/021 §11.5`); the
panel-registry key stays `:views` for the smaller diff — the
namespace `panels.reactive-panel` is the post-rf2-wyvf2 spelling
(rf2-yxw57 corrected the stale `panels.views/Panel` symbol).
`routing/Panel` is the **Runtime** routing tab — the topology-plus-
overlay verb per `spec/021 §7`; the Static-mode browse-all +
Simulate-URL verb lives at `static.routes.panel/Panel` (not part of
the Runtime canonical nine — Static-mode L4 sub-tabs live under
`day8.re-frame2-causa.static.*` and are enumerated separately in
§Static-mode Panel reg-views below per the canonical 9 framing of
`spec/018-Event-Spine.md` §The 9 tabs).
`chrome-a11y.panel/Panel` is the **dogfood** L4 tab that runs
axe-core scoped to `#rf-causa-root` — Causa's own chrome — mirroring
Story's `chrome-a11y` panel (PR #1695); added per rf2-yxw57.)

These `Panel` components are the leaves the shell composes — they
are NOT a host-facing single-panel embed surface. Hosts that want to
mount Causa embed the **full shell** per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Full-shell
embed contract. The `mount-<panel>!` aggregator surface enumerated in
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract is
internal-but-stable (used by shell composition and tests); it accepts
one `opts` key — `:frame` — defaulting to `:rf/causa`.

### Static-mode Panel reg-views

The canonical nine above are the **Runtime-mode** L4 tabs (the
event-coupled spine — every panel narrates against the focused
event). Causa's Static mode (per §Static mode above and
[`007-UX-IA.md`](./007-UX-IA.md) §Static mode) ships a parallel set
of L4 sub-tabs that browse the **registrar** rather than the event
spine — flat catalogues of registered events / flows / interceptors
/ routes / schemas / views with optional hermetic Simulate inputs
(per Lock #15 — two-verbs-two-homes — browse-all lives in Static).
Each Static sub-tab is its own namespace under
`day8.re-frame2-causa.static.*` and exports a single public `Panel`
component:

```clojure
day8.re-frame2-causa.static.events.panel/Panel
day8.re-frame2-causa.static.flows.panel/Panel
day8.re-frame2-causa.static.interceptors.panel/Panel
day8.re-frame2-causa.static.routes.panel/Panel
day8.re-frame2-causa.static.schemas.panel/Panel
day8.re-frame2-causa.static.views.panel/Panel
```

These six Static-mode `Panel` exports are a **sibling inventory** to
the canonical nine — they do NOT extend the Runtime list. The
Runtime panel-registry (per [`018-Event-Spine.md`](./018-Event-Spine.md)
§The 9 tabs) and the Static panel-registry are disjoint dispatch
tables keyed by L3 tab id; the surface composer renders one or the
other under the mode flag (`:rf.causa/mode` — `:runtime` /
`:static`). Naming convention is the same as Runtime (bare `Panel`
per `rf2-qiek0`); reg-view registration uses `rf/reg-view` per
`rf2-in6l2` so subscribes resolve to `:rf/causa`.

The Static-mode Routes sub-tab is the **browse-all + Simulate-URL**
verb (the flat catalogue + hermetic Simulate-navigation preview);
the Runtime-mode Routes tab at `panels.routing/Panel` is the
**focused-event lens** (FROM/TO markers when the focused event
triggered navigation). Two surfaces, two verbs, two homes per
Mike's 2026-05-19 decision (Lock #15).

## Public JS API

For React-only hosts (a JS Story consumer, a non-Reagent app):

```javascript
import {init, open, close, toggle, popout} from '@day8/re-frame2-causa';

init({defaultFrame: ':app/main', theme: 'dark', density: 'cosy'});

open();
close();
toggle();
popout();
```

The JS surface is a thin adapter over the canonical CLJS facade
(§Canonical: `day8.re-frame2-causa.core`); props camelCase what the
CLJS surface kebab-cases. There is no host-facing single-panel embed
surface — full-shell embedding via
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) is the
canonical shape.

## Trace / epoch surfaces (consumed, not exposed)

Causa **consumes** the framework's surfaces. It does not expose
analogues; users go to the framework for these. Listed here for
reference:

| Surface | Spec | What Causa reads |
|---|---|---|
| `(rf/register-listener! key callback)` | Spec 009 | The trace bus (every operation). |
| `(rf/register-epoch-listener! key callback)` | Tool-Pair | The per-cascade epoch records. |
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
(causa-config/configure! {:rf.causa/editor :cursor})
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
(causa-config/configure! {:rf.causa/static-mode? true})
```

| Surface | Spelling | Notes |
|---|---|---|
| Configure key | `:rf.causa/static-mode?` | Default `false`. Pre-alpha experimental flag; the default flips to `true` once the placeholder Static sub-tabs ship. Full key contract in [`015-Configuration.md`](./015-Configuration.md) §`:rf.causa/static-mode?`. |
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

### Cascade rule — three `--rf-causa-*` size / motion vars

Causa publishes three same-prefix CSS custom properties that govern the
shell's type scale and motion budget. A fresh reader sees three
`--rf-causa-*` vars and asks "which one wins?". The cascade is **fixed
and independent** — the three vars drive disjoint surfaces and do not
compete:

| CSS var | Knob axis | Surface | Origin |
|---|---|---|---|
| `--rf-causa-font-size` | Host-overridable density anchor (also driven by the Settings → General Density radio: `:compact 12px` / `:cosy 13px` / `:comfy 14px`) | The whole `theme/tokens.cljc :type-scale` — every typographic size resolves through `calc(var(--rf-causa-font-size, 13px) * <multiplier>)`. Flipping it rescales every typographic surface in lockstep. | rf2-n8i2c |
| `--rf-causa-text-size` | User-side Settings → General Text-size slider (10–18 px; default 13) | Causa surfaces that opt-in read `var(--rf-causa-text-size, 13px)` directly — primarily the event-list rows and a small set of inline-style call sites. | rf2-9poxq |
| `--rf-causa-motion-scale` | Reduced-motion gate (`1` = full motion; `0` = motion off; `:cycle-reduced-motion` palette verb cycles `:os → :always → :never`) | Every Causa transition / animation reads `calc(<duration> * var(--rf-causa-motion-scale, 1))`. Setting to `0` collapses motion to zero duration without losing the end-state geometry. | rf2-5kfxe |

**The rule:** host overrides density via `--rf-causa-font-size` (the
density anchor); the user fine-tunes per-row text via the slider's
`--rf-causa-text-size` (the row knob); motion gates collapse to 0
under `--rf-causa-motion-scale: 0` (the motion knob). Each var has
its own write path
(`settings/effects/apply-density-font-size!` for `--rf-causa-font-size`;
`settings/effects/apply-text-size!` for `--rf-causa-text-size`;
`theme/global-styles/motion-css` for `--rf-causa-motion-scale`) and
its own persistence slot — they do NOT cascade onto each other. A
host stylesheet writing `--rf-causa-font-size: 14px` is unaffected
by a user moving the text-size slider, and vice-versa. The vars
share a prefix and a publishing site (the shell root); they do not
share a domain.

The full Settings-popup contract enumerating both `--rf-causa-*-size`
vars side-by-side lives in [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md)
§Two CSS custom properties — `--rf-causa-text-size` vs
`--rf-causa-font-size`.

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
§`command-items` and enumerated normatively in §Command palette
verbs (catalogue) below. There is no public verb-registration API at
v1.0 (consistent with §What this doesn't expose); the catalogue is
internal-but-stable — the chord + recents key + reduced-motion
override are the public surfaces hosts may rely on, and the
catalogue's per-verb dispatch shape is stable across patch releases.

See [`007-UX-IA.md`](./007-UX-IA.md) §Command palette for the full
indexed-sources list, fuzzy-match algorithm, recents-boost decay
shape, and close behaviour.

### Command palette verbs (catalogue)

The palette ships ten command verbs in total at v1; six are mode-agnostic
(surface in both Runtime and Static modes) and four are Runtime-only
(scoped to the event-coupled spine). Plus one palette-internal verb
(`:close-palette` — `Esc` keybind echo). Each row below mirrors the
literal map shape in
`tools/causa/src/day8/re_frame2_causa/palette/sources.cljc`
§`command-items`; the spec is normative, the source is the load-bearing
catalogue.

#### Mode-agnostic verbs (Runtime + Static — the six "chord-reachable" verbs from rf2-ybjkx)

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:toggle-theme` | Toggle theme (dark ↔ light) | `Settings · Theme` | `[:palette/toggle-theme]` | Dark ↔ Light cycle of the theme class. Popup radio is the canonical UI; this is the keyboard-first ergonomic shortcut. |
| `:cycle-reduced-motion` | Cycle reduced-motion override (OS → always → never) | `user override of prefers-reduced-motion` | `[:palette/cycle-reduced-motion]` | Three-state cycle `:os → :always → :never → :os`. Overrides `prefers-reduced-motion: reduce` via the `--rf-causa-motion-scale` seam. Persists across reloads. |
| `:snapshot-app-db` | Snapshot app-db | `→ console.log + clipboard` | `[:palette/snapshot-app-db]` | Drops the focused frame's app-db onto the JS console + clipboard for share-with-teammate capture. |
| `:jump-to-settings` | Jump to Settings | `,` | `[:palette/jump-to-settings]` | Opens the Settings popup at the General tab. Equivalent to the `,` bare-key shortcut. |
| `:toggle-mode` | Toggle mode (Runtime ↔ Static) | `Cmd/Ctrl+Shift+M` | `[:palette/toggle-mode]` | Flip Runtime ↔ Static. Chord parity with `Cmd/Ctrl+Shift+M` in `keybinding.cljs`. |
| `:open-popout` | Open Causa in a pop-out window | `rf-causa-popout` | `[:palette/open-popout]` | Opens the same-origin pop-out window via `popout!`. |

#### Runtime-only verbs (event-coupled spine)

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:clear-trace-buffer` | Clear trace buffer | `drops Causa's ring buffer` | `[:palette/clear-trace-buffer]` | Drops Causa's trace ring buffer. Runtime-only — Static mode has no spine. |
| `:clear-epoch-history` | Clear epoch history | `drops Causa's epoch snapshots` | `[:palette/clear-epoch-history]` | Drops Causa's per-frame epoch history. Runtime-only. |
| `:reset-suppressed-counters` | Reset redacted-events counter | `clears the REDACTED N indicator` | `[:palette/reset-suppressed-counters]` | Clears the `REDACTED N` overlay counter that surfaces when filters elide events. Runtime-only. |

#### Palette-internal verb

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:close-palette` | Close command palette | `ESC` | `[:palette/close]` | Echo of the `Esc` keybind. Mode-agnostic; not a public-API verb hosts target externally, but listed for completeness because it ships in the same catalogue. |

#### Catalogue invariants

1. **Pure-data items.** Every entry is a map (`{:source :command :id <kw>
   :label <str> :hint <str> :icon <str> :boost <int> :action <vec>
   :modes <set> :popout? <bool>}`); the aggregator filters by
   `:modes` membership against the active `:rf.causa/mode`.

2. **Static catalogue.** No `reg-command-verb` is exposed at v1.0 —
   the catalogue is closed (consistent with §What this doesn't expose
   "No plugin registration API"). Hosts that need a new verb file
   a bead against `tools/causa/spec/API.md` §Command palette verbs.

3. **Stable dispatch shape.** Each verb's `:action` vector
   (`[:palette/<verb>]`) is a re-frame event dispatched into
   `:rf/causa`; the event id is part of the public catalogue contract
   and stable across patch releases. The handler implementations live
   under `palette/events.cljs`.

4. **Boost weighting.** Every command-source item carries `:boost 40`
   (the `boost-table :command` value); recently-invoked commands
   receive an additional position-decayed bonus (`recents-boost-max
   60` / `recents-boost-step 20` per `palette/sources.cljc`). The
   weight tunings are internal and may evolve across minor releases.

## MCP API

Per rf2-hvl1g (closure 2026-05-19) there is no dedicated `causa-mcp`
jar. AI agent access to Causa's surfaces flows via
`tools/re-frame2-pair-mcp/` against the framework-published Causa
runtime API (`day8.re-frame2-causa.runtime`) — agents read the same
trace bus + epoch history + registrar Causa itself reads, via
re-frame2-pair-mcp's `eval-cljs` and the runtime accessors. See
DESIGN-RATIONALE.md Lock #6 (superseded) for the reasoning.

## Runtime accessor surface (Causa ↔ MCP read contract)

`day8.re-frame2-causa.runtime` is the **public read-and-mutate seam**
between Causa's browser-side runtime and any out-of-process tool
that drives a re-frame2 app via Causa (today: `tools/re-frame2-pair-mcp/`;
tomorrow: any future MCP server / IDE plugin / record-replay harness).
The `re-frame2-pair-mcp/eval-cljs` channel evaluates forms addressed
at `day8.re-frame2-causa.runtime/<accessor>` against the browser's
shadow-cljs nREPL; the return value comes back over the bencode-framed
channel. The accessor signatures below are the **stable contract** —
the same Tool-Pair-style discipline (the framework emits; the tool
consumes) that governs `:trace-bus` and `epoch-history` per
[`Principles.md`](./Principles.md) §Observation only.

### Discovery sentinel

Two markers prove the runtime landed in the host browser process:

| Marker | Spelling | Lifetime |
|---|---|---|
| CLJS var | `day8.re-frame2-causa.runtime/session-id` | Random UUID set once per preload load; survives `:after-load`; wiped by full page refresh. |
| JS global mirror | `js/globalThis.__day8_re_frame2_causa_runtime` | JS object carrying `session-id` + `installed` ms-timestamp. The MCP-server-side probe reads this without a CLJS compile round-trip. |

The install side-effect is gated on `re-frame.interop/debug-enabled?`
— a stray production load is a no-op (no `js/globalThis` pollution).
A page-refresh-cleared sentinel surfaces as
`{:reason :runtime-not-preloaded}` on the next `discover-app` tool
call with a setup hint.

### Origin tag (`*current-origin*`)

Every mutation the runtime performs on behalf of a tool client
carries `:tags :origin <tool-name>`. The runtime exposes a
`^:dynamic` var:

```clojure
(def ^:dynamic *current-origin*
  "Default :causa-mcp. Tool clients (re-frame2-pair-mcp et al.) rebind for
   the synchronous extent of an eval'd form to their own origin."
  :causa-mcp)
```

Tool clients re-bind via `(binding [runtime/*current-origin* :my-tool] ...)`
for the synchronous extent of an eval'd form. The async-tagging gap
(per Lock #4 / I6 — a dispatched event's downstream cascade carries
the origin only through the synchronous handler frame) is documented;
later cascades pick up the framework's natural origin tagging. A
read-only `(runtime/current-origin)` accessor lets tests pin the
rebind contract without `#'`-piercing the dynamic var.

### Frame resolution

Every accessor that operates on a frame resolves it via the same
fallback ladder:

1. Caller-supplied `:frame <id>` arg.
2. The sole registered frame (when exactly one is registered).
3. `nil` → accessor returns `{:ok? false :reason :no-frame-resolved
   :hint "Pass :frame :foo or register at least one frame."}`.

Multi-frame apps without an explicit `:frame` pick are surfaced
via `discover-app`'s `:ambiguous-frame? true` flag rather than
silently picking one. The MCP server's tool-arg layer is the right
place to refuse mutations against an ambiguous resolution; reads
degrade through the documented `:no-frame-resolved` fallback.

### Privacy egress (single emission site)

Every direct-read accessor routes returned values through
`re-frame.core/elide-wire-value` before egress. The single normative
emission site lives in the framework; the runtime's job is to call
it with `:include-sensitive?` and `:include-large?` defaulting
`false` and to honour the caller's opt-in (per MUST-inventory rows
#2 / #15 / #17 / #19). Callers pass plain `:include-sensitive?` /
`:include-large?` opts; the runtime translates to the framework's
`:rf.size/*` namespaced opt keys.

### Inspection band (9 accessors — read-only)

| Accessor (fn) | Tool name | Returns | Reads |
|---|---|---|---|
| `get-trace-buffer` | `get-trace-buffer` | `{:ok? true :events <vec> :count <n>}` | `trace-tooling/trace-buffer` — filtered slice of the trace stream. Filter keys are the canonical Spec 009 vocabulary (`:operation` / `:op-type` / `:since` / `:frame` / `:severity` / `:event-id` / `:handler-id` / `:source` / `:origin` / `:dispatch-id` / `:since-ms` / `:between` / `:pred`). |
| `get-epoch-history` | `get-epoch-history` | `{:ok? true :frame <id> :epochs <vec> :count <n>}` | `rf/epoch-history` per-frame vector of `:rf/epoch-record`. |
| `get-app-db` | `get-app-db` | `{:ok? true :frame <id> :path <vec> :value <edn>}` | `rf/get-frame-db` (optionally scoped by `:path`). |
| `get-app-db-diff` | `get-app-db-diff` | `{:ok? true :frame <id> :epoch-id <uuid> :diff {:before … :after …}}` | Reads `:db-before` + `:db-after` off a named epoch record. Heavier nested-diff projection lives MCP-side. |
| `get-machine-state` | `get-machine-state` | `{:ok? true :frame <id> :machine-id <kw> :state <edn>}` | `rf/machine-meta` for the registered machine spec. |
| `get-machine-list` | `get-machine-list` | `{:ok? true :machines <map> :count <n>}` | `rf/machines` — map keyed by machine-id. |
| `get-issues` | `get-issues` | `{:ok? true :issues <vec> :count <n>}` | Projection over the trace buffer filtered to issue-tier op-types (`:error` / `:warning` / `:rf.schema/violation` / `:rf.hydration/mismatch`). |
| `get-handlers` | `get-handlers` | `{:ok? true :handlers <vec> :count <n>}` | `rf/registrations` per-kind. Optional `:kind` narrows to one of `:event :sub :fx :cofx :machine :flow :reg-machine :frame :view`. |
| `get-source-coord` | `get-source-coord` | `{:ok? true :kind <kw> :id <any> :source-coord <map>}` | `rf/handler-meta` projected to `:source-coord`. |

### Mutation band (3 accessors — write)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `dispatch!` | `dispatch` | `{:ok? true :event-id <kw> :frame <id> :origin <kw> :mode :queued/:sync}` | Fire `event-vec` tagged `:origin *current-origin*`. Modes: `:queued` (default — non-blocking `rf/dispatch`) or `:sync` (`rf/dispatch-sync`). Frame resolution mirrors the read-side accessors. |
| `restore-epoch!` | `restore-epoch` | `{:ok? true/false :frame <id> :epoch-id <uuid> :origin <kw>}` | Rewinds a frame's `app-db` to the named epoch's `:db-after` via `rf/restore-epoch`. Failures (per Tool-Pair §Time-travel — Restore, six documented failure modes) emit a structured `:rf.epoch/*` trace and leave `app-db` unchanged; the accessor surfaces `:reason :rf.epoch/restore-failed` + a hint pointing to the trace bus. |
| `reset-frame-db!` | `reset-frame-db` | `{:ok? true/false :frame <id> :origin <kw>}` | Inject `:value` into a frame's `app-db`. Schema-validates via `rf/reset-frame-db!`; the three failure rows (`:rf.error/no-such-handler` / `:rf.epoch/reset-frame-db-during-drain` / `:rf.epoch/reset-frame-db-schema-mismatch`) surface on the trace bus; the accessor projects `:reason :rf.epoch/reset-failed` + a hint. |

### Streaming band (3 accessors — subscription bookkeeping)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `subscribe!` | `subscribe` | `{:ok? true :sub-id <uuid> :topic <kw> :filter <map>}` | Open a streaming subscription for `:topic` ∈ `#{:trace :epoch :fx :error}` with `:filter`. Runtime records metadata; the MCP server owns the per-tick drain pump + queue overflow bookkeeping. |
| `unsubscribe!` | `unsubscribe` | `{:ok? true :sub-id <id> :existed? <bool>}` | Idempotent close per the catalogue entry. |
| `list-subscriptions` | `list-subscriptions` | `{:ok? true :subs <vec> :count <n>}` | Diagnostic enumerating active runtime-side subscription metadata. Per-tick `:queue-depth` / `:queue-bytes` / `:dropped-events` fields live MCP-side. |

### Escape hatch (1 accessor)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `eval-form-result` | `eval-cljs` (runtime-side companion) | `{:ok? true :value <elided>}` | The MCP server renders the user's CLJS form inside a `(binding [*current-origin* …] …)` wrapper, then `cljs-eval`s the wrapped form directly. This fn is the runtime-side **result shaper** — privacy + size scrubbing applied to the eval'd value before egress with caller's `:include-sensitive?` / `:include-large?` opt-in. |

### Meta band (2 accessors)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `health` | `discover-app` (runtime-side companion) | `{:ok? true :session-id <uuid> :debug-enabled? <bool> :frames <vec> :ambiguous-frame? <bool> :coord-annotation-enabled? <bool> :origin <kw>}` | One-call summary of the runtime's view of the world. Side-effect-free — Causa-the-panel's preload owns the trace + epoch listeners; this accessor installs no listeners of its own. |
| `tail-build-probe` | `tail-build` (runtime-side companion) | `{:ok? true :probe <int> :session-id <uuid> :build-tick <int>}` | Returns a fresh monotonic counter every call. MCP servers poll until the value changes — proving a hot-reload landed and the runtime re-evaluated. The counter survives `:after-load` (defonce) and resets only on full page refresh (same lifetime as `session-id`). Change-detect lives MCP-side. |

### Test support

`reset-for-test!` clears `subscriptions` + `probe-counter` for
fixture isolation. Does NOT touch `session-id` (per-preload constant
by design) or the JS-global sentinel. Test-only — never call from
production code.

### Cross-side coupling — one-way

The MCP server depends on the accessor signatures above (the
contract). The runtime is independent of any server — Causa-the-panel
loads `runtime.cljs` without an MCP server running, and any future
MCP consumer can attach later without the runtime needing to know.
Adding an accessor is an additive change at the Causa layer; removing
or renaming one is a breaking change to the Tool-Pair contract and
requires a major-version bump per §Versioning.

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
  Removed per `rf2-sbfb7`; full-shell embedding lives at
  [`008-Embedding-Contract.md`](./008-Embedding-Contract.md).

## `open!` / `open-overlay!` / `popout!` — distinct verbs by design (rf2-sa4fr)

> **The three open verbs name distinct surfaces. Do NOT rename
> for mode-symmetry.**

Causa's mount facade exposes three open verbs (per §Canonical:
`day8.re-frame2-causa.core` above):

| Verb | Surface | Notes |
|---|---|---|
| `open!` | Inline mount (default) | Mounts the full shell true-inline into the host's normal-flow layout host (`[data-rf-causa-host]` per [`011-Launch-Modes.md`](./011-Launch-Modes.md)). The default landing posture per rf2-eehov. |
| `open-overlay!` | Modal overlay | Transient, ESC-closeable; mounts above the host without affecting layout. The legacy overlay path retained for hosts that explicitly prefer it. |
| `popout!` | New window | Same-browser pop-out; the shell mounts into its own document context (own React root, own theme cascade, own keybinding). |

A reader audit (`ai/findings/2026-05-20-tools-causa-api-review.md`
Finding #13) flagged the name pair `open!` / `open-overlay!` as
suggesting "default vs overlay" while `popout!` reads as its own
verb, and asked whether a mode-symmetric triplet
(`open-inline!` / `open-overlay!` / `open-popout!`) would read
better. The decision keeps the current names.

**Why distinct verbs win.**

1. **The three verbs convey distinct surfaces, not modal variants of
   one shape.** Inline-vs-overlay-vs-window is a kind-of-mount axis,
   not a mode axis. A mode-symmetric triplet would imply the three
   landed equivalently in the host's layout — they do not. `open!`
   participates in the host's flex layout; `open-overlay!` floats
   above it; `popout!` leaves the host's document entirely.

2. **Bare `open!` IS the canonical default.** The asymmetry telegraphs
   the rank — `open!` is what 95% of host code reaches for; the
   prefixed siblings are the explicit opt-ins. Renaming `open!` to
   `open-inline!` would flatten the rank signal and force every host
   onto the longer spelling for the common case.

3. **Mode-symmetric renames double the vocabulary without reducing
   surface.** Causa ships a Static-mode chrome alongside the default
   Runtime chrome (per §Static mode above and
   [`007-UX-IA.md`](./007-UX-IA.md) §Mode bifurcation rule). A
   mode-symmetric naming pass would require parallel triplets per
   mode (or a mode arg threaded through every open verb); the
   current shape avoids this entirely — mode is orthogonal to which
   surface the shell mounts into.

4. **The browser-global JS mirror already uses the same spellings.**
   `window.day8.re_frame2_causa.{open_BANG_, open_overlay_BANG_,
   popout_BANG_}` (per §Wider public surface above); renaming the
   CLJS surface would force a parallel JS-side rename and a deprecation
   shim Pre-alpha posture forbids.

**Consequence.** The three verbs are stable across patch and minor
releases; no `open-inline!` alias is shipped, and the bare `open!` is
not deprecated. Future surfaces that mount differently (a hypothetical
new-tab launcher, an `iframe!`-style host embed) MUST follow the same
pattern — pick a distinct verb that names its surface, not a
mode-symmetric variant of an existing one.

## Resolved decisions

| Decision | Bead | Outcome |
|---|---|---|
| Keep or delete `dock!` / `undock!` / `mount-inline-panel!` / `unmount-inline-panel!` debug surfaces | `rf2-sbfb7` | "A — delete both" (Mike, 2026-05-17). Pre-alpha posture: no back-compat shims; the true-inline default + `popout!` cover the dock use case, full-shell embedding (008-Embedding-Contract) covers Causa-as-Story-RHS. |
| `configure!` vs `init!` vs persisted Settings — ownership rule | `rf2-g2a5v` | "`configure!` = static boot config; `init!` = lifecycle hook; persisted Settings = user-mutable overrides. Merge order: defaults < `configure!` < Settings. `init!` receives the merged config." Full rule in [`015-Configuration.md`](./015-Configuration.md) §`configure!` vs `init!` vs persisted Settings — ownership rule (rf2-g2a5v). |
| Panel naming — bare `Panel` vs `EventDetailPanel`-style | `rf2-qiek0` | "Keep bare `Panel`." Panels are addressed by tab-key per `018-Event-Spine.md` §5, not by class name; the namespace already establishes context; host-side collision is a non-issue (full-shell embedding, no host-facing single-panel embed surface). Full rule in [`Conventions.md`](./Conventions.md) §Panel naming — generic `Panel` is the convention (rf2-qiek0). |
| Rename `open!` / `open-overlay!` / `popout!` for mode-symmetry | `rf2-sa4fr` | "Keep current names — distinct verbs ARE the convention." The three verbs name distinct surfaces (inline · modal · window), not modal variants of one shape; bare `open!` IS the canonical default; mode-symmetric renames would double the vocabulary without reducing surface. Full rule in §`open!` / `open-overlay!` / `popout!` — distinct verbs by design (rf2-sa4fr) above. |
