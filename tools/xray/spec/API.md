# API

The consolidated user-facing surface. Implementer-readable: every
symbol a consumer of Xray might reach for.

This doc is a **reference**; the normative descriptions live in the
per-area specs (000–011). Where the two drift, the per-area spec
wins.

## Installation API

### Preload-style enablement (browser)

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

Loading the preload runs the foundation's six side-effects — all
gated on `interop/debug-enabled?` so production bundles strip them
via Closure DCE, and all idempotent so shadow-cljs's `:after-load`
cycle re-runs without double-registration:

1. Registers Xray's `:rf.xray/*` subs / events / fxs via
   `registry/register-xray-handlers!`.
2. Registers the trace collector under `:rf.xray/trace-collector`
   via `re-frame.core/register-listener!` (sentinel-guarded).
3. Registers the epoch-settle pump under `:rf.xray/epoch-collector`
   via `re-frame.core/register-listener!` on the `:epoch` stream (sentinel-guarded; no-op
   when the `day8/re-frame2-epoch` artefact is absent).
4. Installs the dev-only browser API on `window.day8.re_frame2_xray.*`
   (`open!`, `toggle!`, `popout!`, `status`, …).
5. Attaches the global keydown listener. The shipped chords (the
   `keybinding.cljs` predicates are the source of truth; UX rationale
   in `spec/007-UX-IA.md` §Global shortcuts): `Ctrl+Shift+C` (toggle
   shell), `Ctrl/Cmd+K` (command palette), `Ctrl/Cmd+Shift+M`
   (Dynamic ↔ Static mode toggle), and `Esc` (dismiss the
   open-in-editor hint). Inside the shell the LIVE-feed spine binds
   bare `Space` / `L` / `j` / `k` / `G`.
6. Auto-opens the shell **true-inline** into the host app's
   normal-flow layout host (`[data-rf-xray-host]` by default) once
   the substrate adapter is ready — per rf2-eehov, this is the
   default landing posture. There is no floating pill, no body
   mount, no closed-by-default panel state, and no viewport overlay
   in the default path. Auto-open is suppressed when the host has
   set `(xray-config/configure! {:rf.xray/auto-open? false})`
   before adapter readiness (e.g. Story-only tool pages).

The layout host is sized by the host's stylesheet; the recommended
rule reads `var(--rf-xray-inline-width, 560px)` for its
`flex-basis` so developers can resize the inline panel by
overriding a single CSS custom property anywhere up the cascade
(rf2-um813; default bumped 420 → 560 under rf2-9ovfb). Xray itself
does not read or set the property — the host's stylesheet is the
single source of truth for inline width.

The recommended snippet also publishes `--rf-xray-accent` (default
`#539bf5` — the GitHub-blue brand accent) on `:root` so host stylesheets can
read it to colour their own dev chrome (resize handles, dock
separators, story chips) without forking the hex (rf2-9ovfb). See
`spec/011-Launch-Modes.md` §Brand-accent CSS variable.

If the layout host selector cannot be found after adapter
readiness, the preload reports the missing host via
`console.error` and `window.day8.re_frame2_xray.status()` and
leaves host startup unblocked.

For the full mount lifecycle, layout-host contract, launch matrix,
suppression knob, and legacy overlay / popout postures, see
[`011-Launch-Modes.md`](./011-Launch-Modes.md) and the repo-root
`README.md`'s install section.

### Published layout-host constants

`day8.re-frame2-xray.config` publishes the layout-host wiring as
named CLJS vars so tooling (story-mode chrome, docs generators) can
refer to the exact spelling without forking the string:

```clojure
day8.re-frame2-xray.config/default-layout-host-selector
;; "[data-rf-xray-host]"
;; — the CSS selector the preload's auto-open path queries on adapter
;;   readiness. Override via (xray-config/configure!
;;   {:rf.xray/layout-host-selector "#devtools-xray"}).

day8.re-frame2-xray.config/default-layout-host-css-var
;; "--rf-xray-inline-width"
;; — the CSS custom property the recommended host snippet reads for
;;   its flex-basis (rf2-um813). Xray never reads this property —
;;   sizing is owned by the host's layout rule. Published so callers
;;   can re-emit the canonical name in their own diagnostics / docs
;;   generators / snippet helpers.

day8.re-frame2-xray.config/default-layout-host-width
;; "560px"
;; — the default value Xray recommends for --rf-xray-inline-width
;;   when the host does not override. Bumped 420 → 560 under rf2-9ovfb
;;   (Pitch8 field feedback: event vectors with map payloads wrap
;;   awkwardly at 420; 560 reads much better for the Event Detail
;;   panel).

day8.re-frame2-xray.config/default-accent-css-var
;; "--rf-xray-accent"
;; — the CSS custom property the recommended host snippet publishes
;;   on :root for Xray's brand-accent colour (rf2-9ovfb). Host
;;   stylesheets read var(--rf-xray-accent) to colour their own dev
;;   chrome to match Xray (resize handles, dock separators, story
;;   chips) without forking the hex. Xray never SETS this property
;;   from CLJS — the host's stylesheet is the single source of truth.

day8.re-frame2-xray.config/default-accent
;; "#539bf5"
;; — the default value Xray publishes for --rf-xray-accent. Matches
;;   theme/tokens.cljc's :accent (GitHub blue) and spec/007-UX-IA.md
;;   §Colour system.

day8.re-frame2-xray.config/default-layout-host-snippet
;; A copy-pasteable HTML + CSS block carrying the recommended host
;; markup, flex-basis read through var(--rf-xray-inline-width, 560px),
;; the :root --rf-xray-accent publish, and the min-width: 320px floor.
;; Reported back to the user in the missing-host diagnostic so the
;; actionable console.error already carries the fix.
```

These are constants, not setters — overriding the selector goes
through `(xray-config/configure! {:rf.xray/layout-host-selector ...})`;
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

Xray's user-facing surface is split into a **canonical entry point**
(the `day8.re-frame2-xray.core` facade — the surface most hosts ever
touch) and a **wider public surface** of supporting namespaces (config
setters, panel components, the keybinding lifecycle escape hatch, the
preload-installed browser-global API, and the MCP read-and-mutate
seam). The canonical entry point is the one to reach for by default;
the wider surface is documented for embedding hosts, test harnesses,
Settings UIs, and tool integrators that need finer-grained access. Per
rf2-te1gu (reconcile the previous "small handful" framing with
implementation reality — ~40 symbols across 6 namespaces).

### Canonical: `day8.re-frame2-xray.core`

```clojure
(xray/init!)
;; Mount Xray manually (alternative to :preloads). Idempotent.

(xray/init! opts)
;; opts: {:target-frame  :app/main         ;; inspected HOST frame (EP-0002 rf2-bd4div)
;;        :theme         :dark / :light    ;; persisted Settings :theme
;;        :density       :compact / :cosy  ;; persisted Settings :general/:density
;;        :buffer-depths {:epoch 50}}      ;; per-frame ring depth (drives both
;;                                         ;; :depth + :trace-events-keep per
;;                                         ;; the rf2-3g9nw D1=a ruling)
;; EP-0002 (rf2-bd4div): the inspected-host opt is :target-frame, distinct from
;; Xray's OWN frame (:rf/xray). The legacy :default-frame opt is RETIRED — it
;; conflated own-frame and target-frame and read like the ambient :rf/default
;; fallback EP-0002 removes. Omit :target-frame to leave the inspected target
;; UNSELECTED (the picker / mount discovery policy selects one); Xray does NOT
;; default the target to :rf/default.
;; rf2-2thl2: each opt is wired end-to-end (no accept-but-ignore stubs).
;; Unknown keys are silently ignored for forward-compatibility.
;; `:ai-provider` is documented in the persisted Settings shape (see
;; §Settings keys below) but the backing infrastructure lands in a
;; follow-on bead; until then `init!` does not accept it on the opts
;; map (host hand-edits of the Settings shape via `(configure!
;; {:rf.xray/settings ...})` remain forward-compatible).

(xray/open!)        ;; Show the panel programmatically.
(xray/close!)       ;; Hide the panel programmatically.
(xray/toggle!)      ;; Toggle.
(xray/popout!)      ;; Open the same-browser pop-out window.

(xray/target-frame)        ;; Return the host frame Xray is currently targeting.
(xray/set-target-frame! :app/main)

;; Story → Xray focus (rf2-crtmq). A host (Story) directs an already-embedded
;; Xray surface to focus a specific panel + epoch + cascade + app-db path from
;; a narrative beat / failed assertion / inspect command. Separate from
;; open-full-Xray (above). One-way command; Story owns the action, Xray owns
;; panel semantics. Full contract: 008-Embedding-Contract.md §Host-facing focus API.
(xray/focus! {:frame :checkout :panel :app-db :epoch-id 42
              :path [:checkout :state]
              :source {:kind :story/assertion}})
(xray/focus! :checkout {:panel :trace})   ;; positional host-frame form
xray/valid-focus-panels                   ;; the 9 valid :panel ids (see 008 §Host-facing focus API; :issues removed rf2-gbz39)

(xray/load-theme css-string)
;; Programmatically swap the palette: injects `css-string` as a dedicated
;; <style> override appended last to <head> (so it wins on authoring order).
;; Idempotent — successive calls replace the override; nil/blank clears it.
;; Useful for editor-driven palette sync. No-op outside a DOM.
```

The facade also re-exports the four highest-traffic config setters
(`configure!`, `set-auto-open!`, `set-editor!`, `set-egress-profile!`)
so the common boot-time wiring lands in one require. The full setter
inventory lives in `day8.re-frame2-xray.config` — see §Wider public
surface below.

**Requiring the facade is side-effect-free (rf2-5w06uu).** The two
install paths are strictly separated:

- The **zero-config preload path** — wiring
  `day8.re-frame2-xray.preload` into shadow-cljs's `:devtools/preloads`
  — auto-installs on namespace load (the six side-effects above): it is
  the canonical convenience path and SHOULD self-install.
- The **manual facade path** — `(require '[day8.re-frame2-xray.core])`
  then `configure!` → `init!`/`open!` — is INERT until the host calls
  `init!` (or `open!`). Requiring `core`, and calling its boot-time
  config setters (`configure!`, `set-auto-open!`, …), registers NO trace
  / epoch collectors, attaches NO keybinding, installs NO browser globals,
  and schedules NO auto-open. The side-effecting work fires only inside
  `init!`. This is what lets a host's `configure!` win deterministically
  *before* any auto-open — boot flags like `:rf.xray/auto-open? false` /
  `:rf.xray/keybinding-enabled? false` set via `configure!` before `init!`
  are honoured because `init!`'s `keybinding/attach!` reads the
  already-set config slot.

  The facade reaches its install primitives through the inert-on-load
  `day8.re-frame2-xray.install` namespace, NOT `day8.re-frame2-xray.preload`
  (whose top-level boot block is what makes the preload path self-install).
  `init!` itself performs the manual install (registry + trace collector +
  epoch collector + keybinding attach) explicitly.

### Wider public surface

Beyond the canonical facade, Xray exposes additional public surfaces
for embedding hosts, test harnesses, Settings UIs, and tool
integrators. Each is in scope of the same versioning discipline as
the facade (no breaking changes within a minor release; deprecations
announced one minor ahead). The canonical entry point above carries
the day-to-day surface; this section is the **complete** index so a
reader scanning for "what's publicly callable?" has a single
authoritative list.

| Namespace | Source | Public surfaces |
|---|---|---|
| `day8.re-frame2-xray.core` | `core.cljs` | The canonical re-exports above (`init!`, `open!`, `open-overlay!`, `close!`, `toggle!`, `popout!`, `status`, `target-frame`, `set-target-frame!`, `focus!` + `valid-focus-panels` (the Story→Xray focus entry point, rf2-crtmq), `load-theme`, plus the four highest-traffic config setters re-exported for boot-time convenience: `configure!`, `set-auto-open!`, `set-editor!`, `set-egress-profile!`). |
| `day8.re-frame2-xray.focus` | `focus.cljc` | The host-facing **focus command** API (rf2-crtmq): `focus!` (the entry point, re-exported through `core`), `focus-command->dispatches` (pure command→`:rf.xray/*`-events translation; JVM-runnable), `valid-panels` + `panel-aliases` + `normalize-panel`. **`valid-panels` mirrors the LIVE Dynamic L4 tab registry** (`#{:epoch :app-db :views :trace :machines :routing :resources :derivation-graph :module-view}` — one per shipped tab; rf2-1sddi6 / rf2-7ed9ms aligned it to the registry so a host can no longer focus `:routes` onto an unknown-tab stub or be denied the shipped `:resources` / `:derivation-graph` / `:module-view` tabs; `:routes` is accepted as a host-friendly alias normalising to `:routing`; rf2-gbz39 removed `:issues` with the Issues tab per Option (c)). The channel Story uses to focus an embedded Xray panel/epoch/path from a beat or assertion. Full contract in [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Host-facing focus API. |
| `day8.re-frame2-xray.panels.*` | `panels/*.cljs` | The 7 standalone-mountable Dynamic `Panel` reg-views — `epoch-panel/Panel`, `app-db-diff/Panel`, `reactive-panel/Panel`, `trace/Panel`, `machine-inspector/Panel`, `routing/Panel`, `resources/Panel` (per [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) + [`018-Event-Spine.md`](./018-Event-Spine.md) §The 9 tabs). The two remaining Dynamic tabs — `derivation_graph/Panel` (Graph, EP-0014) and `module_view/Panel` (Modules, EP-0013) — are **L4-only registry tabs**: focusable via `focus!` but with no standalone `mount-*!` facade (shell-internal). rf2-gbz39 removed `issues-ribbon/Panel` + `mount-issues-ribbon!` per Mike's Option (c) ruling — the Issues tab + its aggregate panel were removed; issues surface inline in the Epoch panel + the L2 event-row pink-wash + the always-on issues ribbon signal (the `:rf.xray/issues-ribbon` projection survives in `registry.cljs` as the ribbon signal's data source). rf2-5gl5r removed `event-detail/Panel` — the Epoch panel supersedes the Event/Handler design as the canonical "what happened in this epoch" surface. rf2-4v67l removed `chrome-a11y.panel/Panel` — a11y dogfooding is Story's domain (rf2-18t6p · `tools/story/src/re_frame/story/ui/chrome_a11y.cljs`). rf2-ga16q removed `machines-canvas.panel/Panel` — its spine-INDEPENDENT browse-all canvas relocated to the Static Machines sub-tab (the Runtime Machines tab is the event-driven lens per rf2-y9xmf). |
| `day8.re-frame2-xray.config` | `config.cljc` | The `configure!` map dispatcher, the per-key setters (`set-editor!`, `set-project-root!`, `set-layout-host-selector!`, `set-auto-open!`, `set-keybinding-enabled!`, `set-egress-profile!`, `set-filter-seed!`, `set-filters-storage-key!`, `update-setting!`, `reset-settings!`, `reset-suppressed-count!`) and the published constants enumerated in §Published layout-host constants above. The full normative key inventory lives in [`015-Configuration.md`](./015-Configuration.md); the **key-naming axis** (how authors navigate the key surface by topical cluster prefix — editor / launch / keybinding / settings / filters / render / trace / logging) is documented at [`015-Configuration.md` §Key-naming axis](./015-Configuration.md#key-naming-axis--navigation-map-rf2-dz35f--audit-of-audits-16) per `rf2-dz35f`. |
| `day8.re-frame2-xray.keybinding` | `keybinding.cljs` | `attach!` / `detach!` — the symmetric, idempotent lifecycle pair for the `Ctrl+Shift+C` global listener. `detach!` is the embed-host escape hatch documented at [`015-Configuration.md`](./015-Configuration.md) §`keybinding/detach!` and [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Full-shell embed contract — needed when an embed host's mount lifecycle runs after Xray's preload and wants to take the chord back. |
| `day8.re-frame2-xray.runtime` | `runtime.cljs` | The Xray ↔ MCP read-and-mutate seam. The accessor surface this namespace exposes is enumerated normatively in §Runtime accessor surface below. Tool clients (`tools/re-frame2-pair-mcp/` today) evaluate forms addressed at this namespace via `eval-cljs`. |
| `window.day8.re_frame2_xray.*` | `preload.cljs` | The browser-global JS API the preload installs (`interop/debug-enabled?`-gated). The exact Closure-name-mangled spellings: `open_BANG_`, `open_overlay_BANG_`, `close_BANG_`, `toggle_BANG_`, `popout_BANG_`, `status`. Mirrored under `window.day8.re_frame2_xray.core.*` once `core.cljs` has loaded so JS-console users see the canonical facade names. Production builds elide the install entirely via the `interop/debug-enabled?` gate. |

Three surfaces deliberately not re-exported through the canonical
facade:

- **The per-key setters in `config.cljc`** beyond the four
  highest-traffic ones (`configure!`, `set-auto-open!`, `set-editor!`,
  `set-egress-profile!`). Hosts that want to flip an experimental knob
  or a less-common setter (`set-project-root!`,
  `set-keybinding-enabled!`, `set-filter-seed!`, etc.) require
  `day8.re-frame2-xray.config`
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

- **`runtime.cljs`.** The Xray ↔ MCP seam is a parallel public
  surface — public-for-tools, not public-for-host-apps — and the
  read/mutate accessors are documented under §Dynamic accessor
  surface below as their own contract surface (the same Tool-Pair
  discipline that governs `:trace-bus` and `epoch-history` per
  [`Principles.md`](./Principles.md) §Observation only). Re-exporting
  through `core` would conflate the host-facing facade with the
  tool-facing read seam.

### Panel reg-views

Each panel namespace exports a single public `Panel` component. The
canonical symbol list:

```clojure
day8.re-frame2-xray.panels.epoch-panel/Panel
day8.re-frame2-xray.panels.app-db-diff/Panel
day8.re-frame2-xray.panels.reactive-panel/Panel
day8.re-frame2-xray.panels.trace/Panel
day8.re-frame2-xray.panels.machine-inspector/Panel
day8.re-frame2-xray.panels.routing/Panel
day8.re-frame2-xray.panels.resources/Panel
;; (rf2-gbz39 — issues-ribbon/Panel removed with the Issues tab per
;; Mike's Option (c) ruling; issues surface inline in the Epoch panel +
;; the L2 event-row pink-wash + the always-on issues ribbon signal.)
;; (Resources/Panel — the declarative-server-state lens, Spec 016 §Xray
;; and AI tooling — is the Dynamic L3 tab after Routing; read-only.)
;; The remaining two Dynamic tabs — derivation_graph/Panel (Graph,
;; EP-0014) and module_view/Panel (Modules, EP-0013) — are L4-only
;; registry tabs: focusable via focus!, but NOT independently mountable
;; (no mount-*! facade), so they are not part of this mountable list.
```

(rf2-qy0nu — the 8-panel dead-code sweep removed `causality-graph`,
`time-travel`, `effects`, `flows`, `routes`, `performance`, `schema-
violation-timeline`, `hydration-debugger`, and `mcp-server`.
rf2-5gl5r removed the `event-detail` panel — the Epoch panel
supersedes it. rf2-gbz39 removed `issues-ribbon/Panel` with the Issues
tab per Mike's Option (c) ruling. The 4-layer shell switches over the
L3 tab ids in
[`018-Event-Spine.md`](./018-Event-Spine.md) §The 9 tabs — these
seven are the surviving standalone-mountable `Panel` exports (the
ninth/eighth Dynamic tabs, Graph + Modules, are L4-only registry tabs
with no `mount-*!` facade — see the code comment above). The L4 display label for
`reactive-panel/Panel` is **Views** (per `spec/021 §11.5`); the
panel-registry key stays `:views` for the smaller diff — the
namespace `panels.reactive-panel` is the post-rf2-wyvf2 spelling
(rf2-yxw57 corrected the stale `panels.views/Panel` symbol).
`routing/Panel` is the **Dynamic** routing tab — the topology-plus-
overlay verb per `spec/021 §7`; the Static-mode browse-all +
Simulate-URL verb lives at `static.routes.panel/Panel` (not part of
the Dynamic mountable set — Static-mode L4 sub-tabs live under
`day8.re-frame2-xray.static.*` and are enumerated separately in
§Static-mode Panel reg-views below per the Dynamic-vs-Static framing of
`spec/018-Event-Spine.md` §The 9 tabs).
(rf2-4v67l — `chrome-a11y.panel/Panel` was removed. A11y dogfooding
is properly Story's domain, where it already lives as
`re-frame.story.ui.chrome-a11y` (rf2-18t6p) — a sibling to the
variant a11y scanner `re-frame.story.ui.a11y` (rf2-qgms1). A
duplicate Xray panel was noise that flagged the Xray events-list
as a problem.))

These `Panel` components are the leaves the shell composes — they
are NOT a host-facing single-panel embed surface. Hosts that want to
mount Xray embed the **full shell** per
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §Full-shell
embed contract. The `mount-<panel>!` aggregator surface enumerated in
[`007-UX-IA.md`](./007-UX-IA.md) §Mountable panel contract is
internal-but-stable (used by shell composition and tests); it accepts
one `opts` key — `:frame` — defaulting to `:rf/xray`.

### Static-mode Panel reg-views

The nine Dynamic tabs above are the **Dynamic-mode** L4 tabs (the
event-coupled spine — every panel narrates against the focused
event). Xray's Static mode (per §Static mode above and
[`007-UX-IA.md`](./007-UX-IA.md) §Static mode) ships a parallel set
of **five** L4 sub-tabs that browse the **registrar** rather than the
event spine — flat catalogues of registered machines / routes /
schemas / flows / interceptors (per Lock #15 — two-verbs-two-homes —
browse-all lives in Static). Each Static sub-tab is its own namespace
under `day8.re-frame2-xray.static.*` and exports a single public panel
reg-view:

```clojure
day8.re-frame2-xray.static.machines.panel/panel        ; symbol is lowercase `panel`
day8.re-frame2-xray.static.routes.panel/Panel
day8.re-frame2-xray.static.schemas.panel/Panel
day8.re-frame2-xray.static.flows.panel/Panel
day8.re-frame2-xray.static.interceptors.panel/Panel
```

These five Static-mode panel exports are a **sibling inventory** to
the Dynamic tabs — they do NOT extend the Dynamic list. rf2-b2fif
dropped the Static Events + Views sub-tabs (the info those tabs
surfaced is already in the source code; the tabs were not pulling
their weight). The Dynamic panel-registry (per
[`018-Event-Spine.md`](./018-Event-Spine.md) §The 9 tabs) and the
Static panel-registry are disjoint dispatch tables keyed by L3 tab
id; the surface composer renders one or the other under the mode
flag (`:rf.xray/mode` — `:dynamic` / `:static`). Naming convention
is the same as Dynamic (bare `Panel` per `rf2-qiek0`); reg-view
registration uses `rf/reg-view` per `rf2-in6l2` so subscribes
resolve to `:rf/xray`.

The Static-mode Routes sub-tab is the **browse-all + Simulate-URL**
verb (the flat catalogue + hermetic Simulate-navigation preview);
the Dynamic-mode Routes tab at `panels.routing/Panel` is the
**focused-event lens** (FROM/TO markers when the focused event
triggered navigation). Two surfaces, two verbs, two homes per
Mike's 2026-05-19 decision (Lock #15).

## Public JS API — the browser globals (no npm adapter)

Xray ships **no npm package and no ESM/CJS adapter** — there is no
`@day8/re-frame2-xray` JS entry point, no `package.json` under
`tools/xray`, and no `init({defaultFrame})` JS surface. A JS-console
user (or a JS host) drives Xray through the **dev-only browser globals**
the preload installs on `window`, gated by `interop/debug-enabled?` so
they are absent from production builds:

```javascript
// Closure-name-mangled spellings (preload-only bundles):
window.day8.re_frame2_xray.open_BANG_();          // mount + show inline
window.day8.re_frame2_xray.open_overlay_BANG_();  // fallback fixed overlay
window.day8.re_frame2_xray.close_BANG_();          // hide (CSS toggle)
window.day8.re_frame2_xray.toggle_BANG_();         // toggle visibility
window.day8.re_frame2_xray.popout_BANG_();         // same-browser pop-out window
window.day8.re_frame2_xray.status();               // mount diagnostic map

// Once core.cljs has loaded, the same fns are mirrored under the
// canonical facade names so JS-console users see kebab-spelled names:
window.day8.re_frame2_xray.core.open_BANG_();      // etc.
```

These mirror the canonical CLJS facade fns (`open!` / `open-overlay!` /
`close!` / `toggle!` / `popout!` / `status` — §Public CLJS API); the
exact spellings + the `interop/debug-enabled?` gate are catalogued in
§Wider public surface (`window.day8.re_frame2_xray.*`). The host-side
**install + boot config** is the CLJS `init!` (kebab opts
`:target-frame` / `:theme` / `:density` / `:buffer-depths`) or the
`:devtools/preloads` path — NOT a JS `init`, and there is **no**
`defaultFrame` opt (the inspected-host opt is `:target-frame`, EP-0002).

> **Future — npm/ESM adapter (not shipped).** A thin React-host npm
> adapter (`import {open, …} from '@day8/re-frame2-xray'`, camelCasing
> the CLJS surface) is a plausible future surface but has no package,
> no JS index, and no tests today. It is documented as future-only here
> so JS hosts are not pointed at an import that does not resolve.

There is no host-facing single-panel embed surface — full-shell
embedding via [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
is the canonical shape.

## Trace / epoch surfaces (consumed, not exposed)

Xray **consumes** the framework's surfaces. It does not expose
analogues; users go to the framework for these. Listed here for
reference:

| Surface | Spec | What Xray reads |
|---|---|---|
| `(rf/register-listener! :trace key callback)` | Spec 009 | The trace bus (every operation). |
| `(rf/register-listener! :epoch key callback)` | Tool-Pair | The per-cascade epoch records (the `:epoch` stream of the one listener verb). |
| `(rf/trace-buffer)` / `(rf/trace-buffer filter)` | Spec 009 | The bounded trace buffer (default 200). |
| `(rf/epoch-history frame-id)` | Tool-Pair | The per-frame epoch ring buffer (default 50). |
| `(rf/restore-epoch! frame-id epoch-id)` | Tool-Pair | Used for confirmed rewinds. |
| `(rf/replace-frame-state! frame-id {:rf.db/app value})` | Tool-Pair | Used for "try anyway" recovery. |
| `(rf/app-db-value frame-id)` | Spec 002 | The app-db panel's live read (returns the app-db VALUE). |
| `(rf/compute-sub query-v db)` | Spec 008 | The sub-graph panel's value display. |
| `(rf/registrations kind)` / `(rf/handler-meta kind id)` | Spec 001 | Registry-browser metadata. |
| `(rf/frame-ids)` / `(rf/frame-meta id)` | Spec 002 | The frame picker. |
| `(rf/machines)` | Spec 005 | The machine inspector dropdown — 0-ary; returns the seq of machine-ids registered in the active frame. |
| `(rf/app-schemas frame-id)` | Spec 010 | The schema-violation timeline rows. |
| `(rf/sub-cache frame-id)` (CLJS only) | Tool-Pair | The subscription graph. |
| `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` (in `:tags`) | Spec 009 | The cascade lineage tags read by event-detail and trace surfaces (`:rf.*` single-root names per rf2-y4qpy). |
| `:rf.event/origin` (in `:tags`) | Spec 009 | The colour-coding axis. |
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
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor})
```

Xray's editor preference is **independent** of Story's
`:rf.story/editor` (hosts that run both tools can route each tool to a
different editor). The shared URI builder lives at
`re-frame.source-coords.editor-uri` (core artefact, CLJC); Xray's
mirror chip (`day8.re-frame2-xray.open-in-editor/open-chip`) consumes
it. Unknown editor keywords fall back to `:vscode` so a typo still
yields a clickable URI rather than a no-op; source-coords without
`:file` hide the chip entirely.

## Static mode (rf2-o5f5f.1 + rf2-8l3uk)

Static mode is unconditionally available: the surface composer
mounts a 3-layer Static silhouette (no L2 event list) alongside the
default 4-layer Dynamic silhouette, with a Dynamic/Static **mode
dropdown** at chrome-ribbon-left (rf2-4vp5j — a compact `<select>`,
not the earlier two-segment pill) and a `Cmd-Shift-M` /
`Ctrl-Shift-M` chord wired to `:rf.xray/toggle-mode`. Per rf2-8l3uk the prior
`:rf.xray/static-mode?` opt-in feature gate was removed (pre-alpha
posture — back-compat shims are out of scope; if Static mode is
useful, expose it unconditionally).

| Surface | Spelling | Notes |
|---|---|---|
| Toggle chord | `Cmd-Shift-M` / `Ctrl-Shift-M` | Global keydown listener; fires `:rf.xray/toggle-mode`. |
| Mode pill | `data-testid="rf-xray-mode-pill"` | Mounts at ribbon-left in every host. Click flips mode; `aria-checked` + `data-active-mode` reflect state for stylesheet/automation hooks. |
| Persistence | `xray.mode` (localStorage) | Bare string `"dynamic"` / `"static"`. Hydrates on boot; missing/corrupt → `"dynamic"` fallback. |
| Toggle event | `:rf.xray/toggle-mode` | Public dispatch surface (chord parity + the palette's `:toggle-mode` verb). |

See [`007-UX-IA.md`](./007-UX-IA.md) §Static mode (visual-language
treatment) and [`018-Event-Spine.md`](./018-Event-Spine.md) §Static
surface (architectural contract).

## Density — one CSS-var, whole scale (rf2-n8i2c)

Xray's type scale resolves through a single host-overridable CSS
custom property, **`--rf-xray-font-size`** (modelled on TanStack
Query Devtools' `--tsqd-font-size`). Each typographic token is
`calc(var(--rf-xray-font-size, 13px) * <multiplier>)` so one variable
rescales the entire shell on the next style flush — no re-render
required.

| Surface | Spelling | Notes |
|---|---|---|
| CSS variable | `--rf-xray-font-size` | The anchor for every type-scale entry. Default `13px`, published on `:root` by `theme/global-styles/motion-css`. Below `10px`: refused (the `:micro` token sits at the floor). |
| Host override | `:root { --rf-xray-font-size: 14px }` | A single stylesheet rule rescales every typographic surface ~1.08× without a code change. |
| Density Settings consumer | `:density` (`:compact` / `:cosy` / `:comfy`) | The Settings → General Density radio is the in-shell consumer of the same var. Mapping: `:compact 12px`, `:cosy 13px` (default), `:comfy 14px` (catalogued; not surfaced in v1's radio). Persisted under `:density` in the Settings localStorage slot. |
| Writer | `effects/apply-density-font-size!` | Idempotent; writes the resolved px value into `--rf-xray-font-size` on both the Xray shell root AND `<html>` (so popout/fullscreen mounts inherit). Re-runs on boot from `apply-all!` so a persisted density survives reload before first paint. |

`--rf-xray-font-size` is **distinct** from `--rf-xray-text-size`
(the Settings → General Text-size slider's user-knob, rf2-9poxq):
two CSS vars, two knobs, one shell. Hosts that want a single density
knob target `--rf-xray-font-size` and leave the slider's var alone.
See [`007-UX-IA.md`](./007-UX-IA.md) §Sizes — one knob, whole scale.

### Cascade rule — three `--rf-xray-*` size / motion vars

Xray publishes three same-prefix CSS custom properties that govern the
shell's type scale and motion budget. A fresh reader sees three
`--rf-xray-*` vars and asks "which one wins?". The cascade is **fixed
and independent** — the three vars drive disjoint surfaces and do not
compete:

| CSS var | Knob axis | Surface | Origin |
|---|---|---|---|
| `--rf-xray-font-size` | Host-overridable density anchor (also driven by the Settings → General Density radio: `:compact 12px` / `:cosy 13px` / `:comfy 14px`) | The whole `theme/tokens.cljc :type-scale` — every typographic size resolves through `calc(var(--rf-xray-font-size, 13px) * <multiplier>)`. Flipping it rescales every typographic surface in lockstep. | rf2-n8i2c |
| `--rf-xray-text-size` | User-side Settings → General Text-size slider (10–18 px; default 13) | Xray surfaces that opt-in read `var(--rf-xray-text-size, 13px)` directly — primarily the event-list rows and a small set of inline-style call sites. | rf2-9poxq |
| `--rf-xray-motion-scale` | Reduced-motion gate (`1` = full motion; `0` = motion off; `:cycle-reduced-motion` palette verb cycles `:os → :always → :never`) | Every Xray transition / animation reads `calc(<duration> * var(--rf-xray-motion-scale, 1))`. Setting to `0` collapses motion to zero duration without losing the end-state geometry. | rf2-5kfxe |

**The rule:** host overrides density via `--rf-xray-font-size` (the
density anchor); the user fine-tunes per-row text via the slider's
`--rf-xray-text-size` (the row knob); motion gates collapse to 0
under `--rf-xray-motion-scale: 0` (the motion knob). Each var has
its own write path
(`settings/effects/apply-density-font-size!` for `--rf-xray-font-size`;
`settings/effects/apply-text-size!` for `--rf-xray-text-size`;
`theme/global-styles/motion-css` for `--rf-xray-motion-scale`) and
its own persistence slot — they do NOT cascade onto each other. A
host stylesheet writing `--rf-xray-font-size: 14px` is unaffected
by a user moving the text-size slider, and vice-versa. The vars
share a prefix and a publishing site (the shell root); they do not
share a domain.

The full Settings-popup contract enumerating both `--rf-xray-*-size`
vars side-by-side lives in [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md)
§Two CSS custom properties — `--rf-xray-text-size` vs
`--rf-xray-font-size`.

## Command palette (rf2-ybjkx)

The palette is a centred 560px modal opened via the global
`Cmd-K` / `Ctrl-K` chord (also reachable from the top-strip control).
Closes on `Esc`, click-outside, or invocation of any item.

| Surface | Spelling | Notes |
|---|---|---|
| Open chord | `Cmd-K` / `Ctrl-K` | Global keydown listener; mounts the palette dialog (`data-testid="rf-xray-palette-dialog"`). |
| Recents localStorage key | `re-frame2.xray.palette.recents.v1` | Top-3 ring of command-ids only (verbs, tab-jumps) — never event-ids, handler-ids, or host-app data. Best-effort persistence; quota/availability failures swallowed. |
| Recents app-db slot | `:rf.xray.palette/recents` | Hydrates on first palette open via `recents/load`; the reducer (`recents/record`) is pure `update + distinct + take 3`. |
| Reduced-motion override | `:cycle-reduced-motion` verb | Three-state cycle `:os → :always → :never` that overrides `prefers-reduced-motion: reduce` via the `--rf-xray-motion-scale` seam in `theme/global-styles/motion-css`. Persists across reloads. |
| Mode-aware filter | `:modes` set per item | Every palette item carries `#{:dynamic}` / `#{:static}` / `#{:dynamic :static}`; the aggregator (`palette/sources/by-mode-pred`) filters by membership against the active `:rf.xray/mode`. Items missing `:modes` fall through to both modes. |

The six chord-reachable command verbs that ship post-rf2-ybjkx —
`:toggle-theme`, `:cycle-reduced-motion`, `:snapshot-app-db`,
`:jump-to-settings`, `:toggle-mode`, `:clear-epoch-history` — are
catalogued at
`tools/xray/src/day8/re_frame2_xray/palette/sources.cljc`
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
(surface in both Dynamic and Static modes) and four are Dynamic-only
(scoped to the event-coupled spine). Plus one palette-internal verb
(`:close-palette` — `Esc` keybind echo). Each row below mirrors the
literal map shape in
`tools/xray/src/day8/re_frame2_xray/palette/sources.cljc`
§`command-items`; the spec is normative, the source is the load-bearing
catalogue.

#### Mode-agnostic verbs (Dynamic + Static — the six "chord-reachable" verbs from rf2-ybjkx)

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:toggle-theme` | Toggle theme (dark ↔ light) | `Settings · Theme` | `[:palette/toggle-theme]` | Dark ↔ Light cycle of the theme class. Popup radio is the canonical UI; this is the keyboard-first ergonomic shortcut. |
| `:cycle-reduced-motion` | Cycle reduced-motion override (OS → always → never) | `user override of prefers-reduced-motion` | `[:palette/cycle-reduced-motion]` | Three-state cycle `:os → :always → :never → :os`. Overrides `prefers-reduced-motion: reduce` via the `--rf-xray-motion-scale` seam. Persists across reloads. |
| `:snapshot-app-db` | Snapshot app-db | `→ console.log + clipboard` | `[:palette/snapshot-app-db]` | Drops the focused frame's app-db onto the JS console + clipboard for share-with-teammate capture. The console + clipboard are **off-box egress sinks**, so the payload routes through `runtime/egress-value` FIRST (pinned to the focused frame via `with-frame` so that frame's own schema declarations govern) — sensitive slots ⇒ `:rf/redacted`, large slots ⇒ `:rf.size/large-elided`, fail-closed, exactly as the `get-app-db` accessor. The command surfaces no opt-in arg, so the command default is always the redacted/size-elided projection — never the raw db (rf2-mxzgg / rf2-6fgob). |
| `:jump-to-settings` | Jump to Settings | `,` | `[:palette/jump-to-settings]` | Opens the Settings popup at the General tab. Equivalent to the `,` bare-key shortcut. |
| `:toggle-mode` | Toggle mode (Dynamic ↔ Static) | `Cmd/Ctrl+Shift+M` | `[:palette/toggle-mode]` | Flip Dynamic ↔ Static. Chord parity with `Cmd/Ctrl+Shift+M` in `keybinding.cljs`. |
| `:open-popout` | Open Xray in a pop-out window | `rf-xray-popout` | `[:palette/open-popout]` | Opens the same-origin pop-out window via `popout!`. |

#### Dynamic-only verbs (event-coupled spine)

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:clear-trace-buffer` | Clear trace buffer | `drops Xray's ring buffer` | `[:palette/clear-trace-buffer]` | Drops Xray's trace ring buffer. Dynamic-only — Static mode has no spine. |
| `:clear-epoch-history` | Clear epoch history | `drops Xray's epoch snapshots` | `[:palette/clear-epoch-history]` | Drops Xray's per-frame epoch history. Dynamic-only. |
| `:reset-suppressed-counters` | Reset redacted-events counter | `clears the REDACTED N indicator` | `[:palette/reset-suppressed-counters]` | Clears the `REDACTED N` overlay counter that surfaces when filters elide events. Dynamic-only. |

#### Palette-internal verb

| Verb id | Label | Hint | Action | Notes |
|---|---|---|---|---|
| `:close-palette` | Close command palette | `ESC` | `[:palette/close]` | Echo of the `Esc` keybind. Mode-agnostic; not a public-API verb hosts target externally, but listed for completeness because it ships in the same catalogue. |

#### Catalogue invariants

1. **Pure-data items.** Every entry is a map (`{:source :command :id <kw>
   :label <str> :hint <str> :icon <str> :boost <int> :action <vec>
   :modes <set> :popout? <bool>}`); the aggregator filters by
   `:modes` membership against the active `:rf.xray/mode`.

2. **Static catalogue.** No `reg-command-verb` is exposed at v1.0 —
   the catalogue is closed (consistent with §What this doesn't expose
   "No plugin registration API"). Hosts that need a new verb file
   a bead against `tools/xray/spec/API.md` §Command palette verbs.

3. **Stable dispatch shape.** Each verb's `:action` vector
   (`[:palette/<verb>]`) is a re-frame event dispatched into
   `:rf/xray`; the event id is part of the public catalogue contract
   and stable across patch releases. The handler implementations live
   under `palette/events.cljs`.

4. **Boost weighting.** Every command-source item carries `:boost 40`
   (the `boost-table :command` value); recently-invoked commands
   receive an additional position-decayed bonus (`recents-boost-max
   60` / `recents-boost-step 20` per `palette/sources.cljc`). The
   weight tunings are internal and may evolve across minor releases.

## MCP API

Per rf2-hvl1g (closure 2026-05-19) there is no dedicated `xray-mcp`
jar. AI agent access to Xray's surfaces flows via
`tools/re-frame2-pair-mcp/` against the framework-published Xray
runtime API (`day8.re-frame2-xray.runtime`) — agents read the same
trace bus + epoch history + registrar Xray itself reads, via
re-frame2-pair-mcp's `eval-cljs` and the runtime accessors. See
DESIGN-RATIONALE.md Lock #6 (superseded) for the reasoning.

## Runtime accessor surface (Xray ↔ MCP read contract)

`day8.re-frame2-xray.runtime` is the **public read-and-mutate seam**
between Xray's browser-side runtime and any out-of-process tool
that drives a re-frame2 app via Xray (today: `tools/re-frame2-pair-mcp/`;
tomorrow: any future MCP server / IDE plugin / record-replay harness).
The `re-frame2-pair-mcp/eval-cljs` channel evaluates forms addressed
at `day8.re-frame2-xray.runtime/<accessor>` against the browser's
shadow-cljs nREPL; the return value comes back over the bencode-framed
channel. The accessor signatures below are the **stable contract** —
the same Tool-Pair-style discipline (the framework emits; the tool
consumes) that governs `:trace-bus` and `epoch-history` per
[`Principles.md`](./Principles.md) §Observation only.

### Discovery sentinel

Two markers prove the runtime landed in the host browser process:

| Marker | Spelling | Lifetime |
|---|---|---|
| CLJS var | `day8.re-frame2-xray.runtime/session-id` | Random UUID set once per preload load; survives `:after-load`; wiped by full page refresh. |
| JS global mirror | `js/globalThis.__day8_re_frame2_xray_runtime` | JS object carrying `session-id` + `installed` ms-timestamp. The MCP-server-side probe reads this without a CLJS compile round-trip. |

The install side-effect is gated on `re-frame.interop/debug-enabled?`
— a stray production load is a no-op (no `js/globalThis` pollution).
A page-refresh-cleared sentinel surfaces as
`{:reason :runtime-not-preloaded}` on the next `discover-app` tool
call with a setup hint.

### Origin tag (`*current-origin*`)

Every mutation the runtime performs on behalf of a tool client
carries `:tags :rf.event/origin <tool-name>` (the `:rf.*` single-root
tag-key per rf2-y4qpy). The runtime exposes a `^:dynamic` var:

```clojure
(def ^:dynamic *current-origin*
  "Default :xray-mcp. Tool clients (re-frame2-pair-mcp et al.) rebind for
   the synchronous extent of an eval'd form to their own origin."
  :xray-mcp)
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

### Privacy egress — the single named safe-egress entry point

Every direct-read accessor routes returned values through one named
off-box safe-egress fn before egress, so **the safe path is the short
path** (rf2-rcogp). The framework owns the normative walker
(`re-frame.core/elide-wire-value`) and the normative epoch projection
(`re-frame.core/projected-record`); the runtime wraps each with the
off-box defaults BAKED IN so a forwarder author never re-derives the
opt-juggling per call site:

```clojure
(day8.re-frame2-xray.runtime/egress-value value)             ;; arbitrary app-db-partition value (slice, sub, trace event, …)
(day8.re-frame2-xray.runtime/egress-record record)           ;; one :rf/epoch-record
(day8.re-frame2-xray.runtime/egress-runtime-db-value value)  ;; a RUNTIME-DB-partition value (machine snapshot, route slice, …)
```

**Partition-aware runtime-db egress (EP-0001 rf2-jj1xer · Mike ruling
#14).** A frame-state projection has two partitions — the user `app-db`
(`:rf.db/app`) and the framework `runtime-db` (`:rf.db/runtime`: machine
snapshots, route slice, spawn registry, SSR/hydration metadata, the
elision registry). Per [Spec 011 §Off-box redaction](../../../spec/011-SSR.md)
+ [Spec 009 §Privacy](../../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces),
off-box egress (this runtime is the AI/MCP + log boundary) DEFAULT-REDACTS
the runtime-db partition: only the app-db partition (subject to its own
`egress-value` elision) and explicitly allowlisted serializable runtime-db
facts cross the wire. `egress-runtime-db-value` is the partition-
distinguishing peer of `egress-value` — it substitutes the framework
`:rf/redacted` sentinel for a runtime-db value on the safe default path. A
**trusted-local** caller (the developer inspecting their own running app)
opts in to the live runtime-db value with `{:include-runtime-db? true}`;
the value then routes through `egress-value` so the partition opt-in
COMPOSES with — does not override — the per-slot sensitive / large off-box
defaults. This mirrors the framework's normative
`re-frame.epoch.tool-pair/elide-frame-state-slot`, which default-redacts
the `:rf.db/runtime` partition of an epoch's `:frame-state-*` slots. The
`get-machine-state` accessor uses it for its live `:state` / `:snapshot`
reads (the registered `:spec` egresses through `egress-value` — it is not
runtime-db state).

Off-box defaults: `:include-sensitive?` and `:include-large?` both
default `false` — sensitive slots become `:rf/redacted`, large slots
become the `:rf.size/large-elided` marker. A caller that is itself the
trust boundary opts back in per call
(`{:include-sensitive? true}` / `{:include-large? true}`). `egress-record`
routes through `projected-record` on **every** path — both the safe
default and the opt-in — threading the opts into the normative projection
(rf2-5w06uu). It NEVER walks the raw record through `egress-value`: doing
so on opt-in (the former shape) both lifted the orthogonal `:rf.db/runtime`
partition of the frame-state slots off-box just because the caller asked
for sensitive / large APP-DB values, AND mis-rooted the app-db path
tracker so frame-state app-db sensitive declarations never matched.
Per [Security.md §Off-box egress](../../../spec/Security.md) the projection
is the single normative emission site and per-tool reimplementation is
prohibited — so the framework's `projected-record` was extended to accept
the egress opts rather than Xray re-deriving the partition logic. The
`:rf.db/runtime` frame-state partition stays `:rf/redacted` under
`:include-sensitive?` / `:include-large?`; a trusted-local caller reveals
it only with `{:include-runtime-db? true}` (the runtime-db value then
rides the same value walk, where its own per-slot declarations apply) —
the same partition-opt-in `egress-runtime-db-value` exposes for live reads.
This is the runtime's half of MUST-inventory rows #2 / #15 / #17 / #19;
callers pass plain `:include-sensitive?` / `:include-large?` /
`:include-runtime-db?` opts.

**Event-level default-suppress (the envelope, not just the value).**
`egress-value` scrubs the VALUES carried inside a trace event, but it does
not drop a whole event that is itself marked `:sensitive? true`. The
framework's per-frame rings RETAIN every emitted event with no
`:sensitive?` check (a faithful record of what the runtime emitted), so a
sensitive event's ENVELOPE — its existence, `:op-type`, timing, source,
handler/event ids, and any non-elided `:tags` — would survive
value-scrubbing and cross the off-box boundary. Per Spec 009 §Privacy +
[013-Trace-Consumer.md](013-Trace-Consumer.md) a framework-published trace
consumer default-SUPPRESSES whole `:sensitive? true` events. So the two
flat-trace-event accessors — `get-trace-buffer` and `get-issues` — drop
every event for which `re-frame.core/sensitive?` is true BEFORE
value-scrubbing, unless the caller explicitly opts in with
`{:include-sensitive? true}` (the per-call opt-back-in — distinct from the
panel's local-render egress profile (`:rf.xray/egress-profile`), since the
seam is per-call). This is the runtime/MCP-seam half of the same contract the
panel-side trace collector + `snapshot-from-rings` honour via
`config/suppress-sensitive?` (rf2-to36uj).

`egress-value` also takes an optional `:path` — the **absolute** app-db
path the value sits at. The framework's app-db sensitive / large
declarations (classified via the EP-0025 commit-plane `:sensitive` / `:large`
effects — a `reg-event` returns them alongside `:db`, written `:source :effect`;
Spec 015 §Data classification) are
keyed by absolute path, so a slice egress'd in isolation
(a `:path`-scoped `get-app-db` read, or one changed-path slice from
`get-app-db-diff`) MUST tell the walker where the slice lives or the
declaration won't match and the raw leaf would cross the boundary
(rf2-a96xq). A whole-db read passes no `:path` (the value IS the walked
root, `[]`).

The accessors are not the only off-box egress sites: the command-palette
`:snapshot-app-db` verb ships the focused frame's app-db to the JS console
**and** the system clipboard — both off-box sinks — so it routes the
captured value through `egress-value` before either sink, pinned to the
focused frame (`with-frame tf`) so that frame's own declarations govern
the redaction (rf2-mxzgg). The whole-db snapshot passes no `:path` (the
value IS the walked root). Because the verb exposes no opt-in arg, the
snapshot is always the redacted / size-elided projection — the raw-egress
opt-in (`{:include-sensitive? true}` / `{:include-large? true}`) is
reachable only through the runtime accessors, never the palette command.

The **universal copy-to-clipboard affordance** is the same shape of
off-box sink and routes the same way (rf2-uo0rc.2). The `⎘` copy button
that rides every value inspector dispatches `:rf.xray/copy-value-to-clipboard`,
which writes the copied value to the system clipboard via the
`:rf.xray.fx/copy-to-clipboard` fx — an off-box sink. The event routes the
value through `egress-value` **before** the clipboard write, pinned to the
**observed** frame (`[:focus :frame]`, falling back to `:target-frame`) so
that frame's own classification governs — a slot the frame declared
`:sensitive` copies as `:rf/redacted`, a `:large` slot as `:rf.size/large-elided`,
fail-closed (mirroring the snapshot's `with-frame` pinning, rf2-mxzgg).
The sibling `:rf.xray/copy-path-to-clipboard` copies only the path vector
(key names, no values), so it is not a value-egress site and is not
elided. Like the snapshot, the value-copy event exposes no raw opt-in —
the operator-controlled `{:include-sensitive? true}` / `{:include-large? true}`
gate is reachable only through the runtime accessors.

### Inspection band (9 accessors — read-only)

| Accessor (fn) | Tool name | Returns | Reads |
|---|---|---|---|
| `get-trace-buffer` | `get-trace-buffer` | `{:ok? true :events <vec> :count <n>}` | `trace-tooling/trace-buffer` — filtered slice of the trace stream. Filter keys are the canonical Spec 009 vocabulary (`:operation` / `:op-type` / `:since` / `:frame` / `:severity` / `:event-id` / `:handler-id` / `:source` / `:origin` / `:dispatch-id` / `:since-ms` / `:between` / `:pred`). Whole `:sensitive? true` events are default-SUPPRESSED before value-scrubbing — the envelope is dropped unless `{:include-sensitive? true}` (rf2-to36uj). |
| `get-epoch-history` | `get-epoch-history` | `{:ok? true :frame <id> :epochs <vec> :count <n>}` | `rf/epoch-history` per-frame vector of `:rf/epoch-record`, each routed through `egress-record` → `projected-record`. `:include-sensitive?` / `:include-large?` opt the **app-db** partition's privacy / size posture back in; the frame-state `:rf.db/runtime` partition stays `:rf/redacted` unless a trusted-local caller also passes `:include-runtime-db? true` (rf2-5w06uu). |
| `get-app-db` | `get-app-db` | `{:ok? true :frame <id> :path <vec> :value <edn>}` | `rf/app-db-value` (optionally scoped by `:path`). The `:value` routes through `egress-value`; when `:path` is supplied the absolute path is threaded into the walker so a scoped slice elides against the frame-owned `:sensitive` / `:large` app-db declarations — fail-closed, symmetric with the whole-db read and the `get-app-db-diff` slices (rf2-a96xq). |
| `get-app-db-diff` | `get-app-db-diff` | `{:ok? true :frame <id> :epoch-id <uuid> :diff {:added [{:path :value}] :removed [{:path :value}] :changed [{:path :before :after}]}}` | Projects the changed-paths slice diff between a named epoch's `:db-before` + `:db-after` via the canonical Editscript-A* engine (`diff.engine/project`). Only the changed paths' slices egress — each `:value` / `:before` / `:after` routed through `egress-value`, never two whole app-db snapshots (rf2-uv2q2). Heavier nested-diff projection lives MCP-side. |
| `get-machine-state` | `get-machine-state` | `{:ok? true :frame <id> :machine-id <kw> :state <live-state-path> :snapshot {:state :data :tags …} :spec <registered-definition>}` | The LIVE FSM position — reads the machine's snapshot from the frame's **runtime-db partition** at `[:rf.runtime/machines :snapshots <machine-id>]` (EP-0001 rf2-vzld77 — machine snapshots are durable runtime-db state; the same slot the Machine Inspector's `:rf.xray/machine-snapshots` sub + the framework resolver read). `:state` is the running state-path (a region→state map for a `:parallel` machine — Spec 005), NOT derived from the static spec; the registered definition is returned separately under `:spec`. A registered-but-not-yet-started machine has no live snapshot — `:state` / `:snapshot` are `nil` and `:reason` is `:not-yet-started` (still `:ok? true`). **Partition-aware off-box redaction (rf2-jj1xer · Mike ruling #14):** `:state` / `:snapshot` are RUNTIME-DB state, so they egress through `egress-runtime-db-value` and are REDACTED to `:rf/redacted` off-box by default; a trusted-local caller opts in with `:include-runtime-db? true` (the inner walk then honours per-slot sensitive / large declarations). `:spec` is a static registry value (not runtime-db), so it egresses through `egress-value` regardless of the runtime-db opt-in. |
| `get-machine-list` | `get-machine-list` | `{:ok? true :machines <map> :count <n>}` | `rf/machines` — map keyed by machine-id. |
| `get-issues` | `get-issues` | `{:ok? true :issues <vec> :count <n>}` | Projection over the trace buffer filtered to the SEVERITY `:op-type`s (`:error` / `:warning`) only (rf2-wd1pgb — `:rf.schema/violation` / `:rf.hydration/mismatch` are `:operation` values, never `:op-type` values; a real schema violation / hydration mismatch already rides `:op-type :warning`/`:error`). Whole `:sensitive? true` issue events are default-SUPPRESSED before the op-type filter — the envelope is dropped unless `{:include-sensitive? true}` (rf2-to36uj, symmetric with `get-trace-buffer`). |
| `get-handlers` | `get-handlers` | `{:ok? true :handlers <vec> :count <n>}` | `rf/registrations` per-kind. Optional `:kind` narrows to one of the framework's closed `re-frame.registrar/kinds` set — `:event :sub :fx :cofx :interceptor :view :frame :route :head :error-projector :flow :resource :mutation :resource-scope` (rf2-ku6j74 — machines are NOT a registrar kind; see `get-machine-list` / `get-machine-state`). |
| `get-source-coord` | `get-source-coord` | `{:ok? true :kind <kw> :id <any> :source-coord <map>}` | `rf/handler-meta` projected to `:source-coord`, routed through `egress-value` (rf2-j8b0u — Spec 009's user-supplied `:rf.handler/source` override can stamp arbitrary values into the slot, so the accessor egresses unconditionally rather than judging per-read; `:include-sensitive?` / `:include-large?` opt back in). |

### Resources read band (5 accessors — read-only)

The Resources panel's AI / MCP read API (rf2-dh0y8o). Five read-only
accessors on `runtime.cljs` that project the resource registry + the
live per-frame resource-instance cache + the `:rf.resource/*` trace
family. They apply the two-layer privacy elision documented in
[`024-Resources-Panel.md`](./024-Resources-Panel.md) §Tool accessors and
Spec 016 §Xray and AI tooling: the projection always summarizes
scope/params/data (type + bounded size + redaction-aware preview, never
the raw value); on top, the payload slots (`:data` / `:error` /
`:refresh-error` + the key's scope/params) route through the off-box
`resource-egress-fn` walker so a `:sensitive?` declaration egresses as
`:rf/redacted` and a `:large?` slot as `:rf.size/large-elided`. The
non-PII metadata (status / generation / attempt / request-id / owners /
tags / timestamps) is NEVER redacted, so the status / tag / owner /
request-id filters work without an opt-in; `:include-runtime-db? true`
(trusted-local) lifts even a non-declared payload to its raw value.
`:scope` / `:resource-id` / `:params` filter against the raw cache key
**before** projection; the remaining axes filter the already-projected
rows. Per EP-0002 the frame target is carried explicitly — a frameless
call with no resolvable context fails closed (`{:ok? false :reason
:no-frame-resolved}`).

| Accessor (fn) | Tool name | Returns | Reads |
|---|---|---|---|
| `list-resources` | `list-resources` | `{:ok? true :resources <vec> :count <n>}` | The STATIC resource registry (`rf/registrations :resource` projected via `project-registry`, joined with `:route` registrations): id, source coords, summarized param/data schemas, request summary, stale/GC policy, tag producer, scope policy, sensitivity/large class, declaring routes. Rows carry only static registry facts (no live values), so they egress freely. Filter axis: `:resource-id` (nil lists all). |
| `list-resource-instances` | `list-resource-instances` | `{:ok? true :frame <id> :instances <vec> :count <n>}` | The LIVE per-frame resource-instance table from the frame's runtime-db at `[:rf.runtime/resources :entries]` (EP-0001 — framework-owned runtime-db state): resource key, scope, status, timestamps, generation, request id, attempt, active owners, tags, data summary, GC eligibility. Filter axes: `:frame` `:scope` `:resource-id` `:params` `:status` `:stale?` `:tag` `:owner` `:request-id`. Payload slots egress per the band's per-slot redaction (rf2-tgm1xu); `:include-sensitive?` / `:include-large?` / `:include-runtime-db?` opt the raw values back in. |
| `get-resource-state` | `get-resource-state` | `{:ok? true :frame <id> :state <instance-row>}` | The LIVE durable state of ONE resource instance addressed by its scoped key at `[:rf.runtime/resources :entries [scope resource-id params]]` (Spec 016 §Introspection / §Status semantics). Required: `:resource-id` AND `:scope` AND `:params` (the full scoped-key triple); any missing part fails closed with `:reason :missing-key`. `:stale?` / `:has-data?` are derived, not stored. Payload slots egress per the band's per-slot redaction; `:include-runtime-db? true` opts in to the raw payload. |
| `get-resource-history` | `get-resource-history` | `{:ok? true :frame <id> :history <vec> :count <n>}` | The BOUNDED lifecycle timeline — projects the `:rf.resource/*` trace family out of the frame's trace ring into ordered lifecycle rows (Spec 016 §Xray and AI tooling; history MUST be bounded). Filter axes: `:resource-id` `:nav-token` `:limit` (default 50 — the bound). Whole `:sensitive? true` trace events are default-suppressed at the off-box seam unless `:include-sensitive? true`. |
| `list-resource-invalidations` | `list-resource-invalidations` | `{:ok? true :frame <id> :invalidations <vec> :count <n>}` | The invalidation / mutation graph — projects the `:rf.resource/invalidated` trace rows: summarized scope, invalidated tags, summarized cause, matched scoped keys, refetch count (distinguishes a broad-tag storm by high `:match-count` from a zero-match invalidation, `:match-count 0`). Filter axis: `:tag` (only invalidations touching the tag). Whole `:sensitive? true` events default-suppressed unless `:include-sensitive? true`. |

### Mutation band (3 accessors — write)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `dispatch!` | `dispatch` | `{:ok? true :event-id <kw> :frame <id> :origin <kw> :mode :queued/:sync}` | Fire `event-vec` tagged `:origin *current-origin*`. Modes: `:queued` (default — non-blocking `rf/dispatch`) or `:sync` (`rf/dispatch-sync`). Frame resolution mirrors the read-side accessors. |
| `restore-epoch!` | `restore-epoch` | `{:ok? true/false :frame <id> :epoch-id <uuid> :origin <kw>}` | Rewinds a frame to the named epoch's `:frame-state-after` via `rf/restore-epoch!` — the WHOLE frame-state (app-db AND runtime-db: machine snapshots, route slice, elision declarations, SSR metadata) in one atomic write, NOT app-db alone. `:frame-state-after` is the only restore source; the retained `:db-after` is an app-db projection used for diffs, never the restore source. Failures (per Tool-Pair §Time-travel — Restore, seven documented failure modes) emit a structured `:rf.epoch/*` trace and leave the frame-state unchanged; the accessor surfaces `:reason :rf.epoch/restore-failed` + a hint pointing to the trace bus. |
| `replace-app-db!` | `replace-app-db` | `{:ok? true/false :frame <id> :origin <kw>}` | Inject `:value` into a frame's `app-db`. Schema-validates via `(rf/replace-frame-state! frame-id {:rf.db/app value})`; the failure rows (`:rf.error/replace-frame-state-bad-keys` / `:rf.error/no-such-handler` / `:rf.epoch/replace-during-drain` / `:rf.epoch/replace-schema-mismatch`) surface on the trace bus; the accessor projects `:reason :rf.epoch/reset-failed` + a hint. |

### Streaming band (3 accessors — subscription bookkeeping)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `subscribe!` | `subscribe` | `{:ok? true :sub-id <uuid> :topic <kw> :filter <map>}` | Open a streaming subscription for `:topic` ∈ `#{:trace :epoch :fx :error}` with `:filter`. Dynamic records metadata; the MCP server owns the per-tick drain pump + queue overflow bookkeeping. |
| `unsubscribe!` | `unsubscribe` | `{:ok? true :sub-id <id> :existed? <bool>}` | Idempotent close per the catalogue entry. |
| `list-subscriptions` | `list-subscriptions` | `{:ok? true :subs <vec> :count <n>}` | Diagnostic enumerating active runtime-side subscription metadata. Per-tick `:queue-depth` / `:queue-bytes` / `:dropped-events` fields live MCP-side. |

### Escape hatch (1 accessor)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `eval-form-result` | `eval-cljs` (runtime-side companion) | `{:ok? true :value <elided>}` | The MCP server renders the user's CLJS form inside a `(binding [*current-origin* …] …)` wrapper, then `cljs-eval`s the wrapped form directly. This fn is the runtime-side **result shaper** — privacy + size scrubbing applied to the eval'd value before egress with caller's `:include-sensitive?` / `:include-large?` opt-in. |

### Meta band (2 accessors)

| Accessor (fn) | Tool name | Returns | Behaviour |
|---|---|---|---|
| `health` | `discover-app` (runtime-side companion) | `{:ok? true :session-id <uuid> :debug-enabled? <bool> :frames <vec> :ambiguous-frame? <bool> :coord-annotation-enabled? <bool> :origin <kw>}` | One-call summary of the runtime's view of the world. Side-effect-free — Xray-the-panel's preload owns the trace + epoch listeners; this accessor installs no listeners of its own. |
| `tail-build-probe` | `tail-build` (runtime-side companion) | `{:ok? true :probe <int> :session-id <uuid> :build-tick <int>}` | Returns a fresh monotonic counter every call. MCP servers poll until the value changes — proving a hot-reload landed and the runtime re-evaluated. The counter survives `:after-load` (defonce) and resets only on full page refresh (same lifetime as `session-id`). Change-detect lives MCP-side. |

### Test support

`reset-for-test!` clears `subscriptions` + `probe-counter` for
fixture isolation. Does NOT touch `session-id` (per-preload constant
by design) or the JS-global sentinel. Test-only — never call from
production code.

### Cross-side coupling — one-way

The MCP server depends on the accessor signatures above (the
contract). The runtime is independent of any server — Xray-the-panel
loads `runtime.cljs` without an MCP server running, and any future
MCP consumer can attach later without the runtime needing to know.
Adding an accessor is an additive change at the Xray layer; removing
or renaming one is a breaking change to the Tool-Pair contract and
requires a major-version bump per §Versioning.

## Settings keys

Settings persist in `localStorage` under the key
`day8.re-frame2-xray/settings/v1`. Distinct from the boot-time
`configure!` surface enumerated in
[`015-Configuration.md`](./015-Configuration.md) (which writes
process-global atoms) and from `(xray/init! opts)` above (which
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
 :target-frame  :app/main                ;; EP-0002 (rf2-bd4div): inspected HOST
                                         ;; frame; renamed from :default-frame
                                         ;; (own-frame vs target-frame split)
 :sidebar-mode  :grouped / :show-all
 :launcher-pill {:hidden? false}
 :keybindings   {:toggle ["Ctrl+Shift+C"]   ;; vector for multiple binds
                 ...}}
```

Corruption (schema fails) → Xray wipes the slot and writes the
default shape, surfacing a one-time toast: "Settings were corrupted
and have been reset to defaults."

> **rf2-e9tb0 — pinned-slices localStorage slot deprecated.** The
> per-frame-app-db pinned-slices key (`day8.re-frame2-xray/pinned-
> slices/<frame-id>/v1`) is no longer written; the pinned-watches
> strip was superseded by the App-DB Diff segment-inspector popup
> (per `004-App-DB-Diff.md` §Clickable path segments). Legacy slots
> are ignored on read — Xray never resurrects them.

## Trace-event tags Xray emits

When Xray mutates the runtime (rewind, reset, re-dispatch), it
emits trace events tagged `:rf.event/origin :xray` so its actions are
visible in the trace stream. (Origins from MCP servers carry their own
server-name tag — re-frame2-pair-mcp uses
`:rf.event/origin :re-frame2-pair-mcp`.)

These ride the framework's existing `:rf.event/dispatched`,
`:rf.epoch/restored`, etc. operations — no new operation kinds
invented (per [`Principles.md`](./Principles.md) §Observation only).

## Versioning

`day8/re-frame2-xray` follows semver. Major (1.x → 2.x) changes
break the public API or the embed contract. Minor (1.0 → 1.1) adds
panels or surfaces. Patch (1.0.0 → 1.0.1) fixes bugs without
contract changes.

The framework dep is `~> 1.0` (compatible with re-frame2's first
stable release). When the framework moves to 2.0, Xray's matching
major bumps with it.

## What this doesn't expose

- **No plugin registration API.** First-party panels only at v1.0.
- **No middleware injection.** Xray does not intercept dispatches;
  it only observes them.
- **No "private" surfaces** (`/-` namespaces, internal helpers) —
  callers must not reach for `day8.re-frame2-xray.internal/*`.
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

Xray's mount facade exposes three open verbs (per §Canonical:
`day8.re-frame2-xray.core` above):

| Verb | Surface | Notes |
|---|---|---|
| `open!` | Inline mount (default) | Mounts the full shell true-inline into the host's normal-flow layout host (`[data-rf-xray-host]` per [`011-Launch-Modes.md`](./011-Launch-Modes.md)). The default landing posture per rf2-eehov. |
| `open-overlay!` | Modal overlay | Transient, ESC-closeable; mounts above the host without affecting layout. The legacy overlay path retained for hosts that explicitly prefer it. |
| `popout!` | New window | Same-browser pop-out; the shell mounts into its own document context (own React root, own theme cascade, own keybinding). |

A reader audit (`ai/findings/2026-05-20-tools-xray-api-review.md`
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
   surface.** Xray ships a Static-mode chrome alongside the default
   Dynamic chrome (per §Static mode above and
   [`007-UX-IA.md`](./007-UX-IA.md) §Mode bifurcation rule). A
   mode-symmetric naming pass would require parallel triplets per
   mode (or a mode arg threaded through every open verb); the
   current shape avoids this entirely — mode is orthogonal to which
   surface the shell mounts into.

4. **The browser-global JS mirror already uses the same spellings.**
   `window.day8.re_frame2_xray.{open_BANG_, open_overlay_BANG_,
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
| Keep or delete `dock!` / `undock!` / `mount-inline-panel!` / `unmount-inline-panel!` debug surfaces | `rf2-sbfb7` | "A — delete both" (Mike, 2026-05-17). Pre-alpha posture: no back-compat shims; the true-inline default + `popout!` cover the dock use case, full-shell embedding (008-Embedding-Contract) covers Xray-as-Story-RHS. |
| `configure!` vs `init!` vs persisted Settings — ownership rule | `rf2-g2a5v` | "`configure!` = static boot config; `init!` = lifecycle hook; persisted Settings = user-mutable overrides. Merge order: defaults < `configure!` < Settings. `init!` receives the merged config." Full rule in [`015-Configuration.md`](./015-Configuration.md) §`configure!` vs `init!` vs persisted Settings — ownership rule (rf2-g2a5v). |
| Panel naming — bare `Panel` vs `EventDetailPanel`-style | `rf2-qiek0` | "Keep bare `Panel`." Panels are addressed by tab-key per `018-Event-Spine.md` §5, not by class name; the namespace already establishes context; host-side collision is a non-issue (full-shell embedding, no host-facing single-panel embed surface). Full rule in [`Conventions.md`](./Conventions.md) §Panel naming — generic `Panel` is the convention (rf2-qiek0). |
| Rename `open!` / `open-overlay!` / `popout!` for mode-symmetry | `rf2-sa4fr` | "Keep current names — distinct verbs ARE the convention." The three verbs name distinct surfaces (inline · modal · window), not modal variants of one shape; bare `open!` IS the canonical default; mode-symmetric renames would double the vocabulary without reducing surface. Full rule in §`open!` / `open-overlay!` / `popout!` — distinct verbs by design (rf2-sa4fr) above. |
