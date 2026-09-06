# Reference

The complete symbol table for Xray's public surface, organised by namespace for `Ctrl-F` use. Every row carries a signature and a one-line intuition — the same shape as the topical chapters, but flat and exhaustive. Reach for the topical chapters when you want context and prose around the contract; reach for this page when you know what you're looking for and just want the row.

Surfaces fall into five namespaces and one browser global. The split is principled — each namespace answers a distinct question — but the row count varies wildly. `core` carries the common host-facing facade; `config` carries the full configuration surface; `keybinding` carries the embed-host lifecycle pair. If you're scanning for a single function and don't remember which namespace owns it, the right move is `Ctrl-F` on this page.

For the topical walk-through with intuition notes and use-when prose, see [Mount control](mount-control.md) and [Configuration keys](config-keys.md). For the index of *what* this reference covers (and what it omits — the Xray-internal panel composers, the static-mode catalogues, the atom handles that mirror state the setters write to), see [the index](index.md#what-canonical-means-here).

## `day8.re-frame2-xray.core`

The canonical facade. The day-to-day require for host integrations: mount control, frame selection, Story-to-Xray focus, theme override, and the high-traffic config re-exports.

| Symbol | Signature | Intuition |
| --- | --- | --- |
| `init!` | `(init!)` / `(init! opts)` → nil | Manual install — the alternative to wiring `:preloads`. Idempotent. |
| `open!` | `(open!)` → mount-state map or missing-host diagnostic | Mount + show the shell true-inline into the host's layout host. The canonical default. |
| `open-overlay!` | `(open-overlay!)` | Mount as a fixed overlay under `<body>`. Floats above host layout. |
| `close!` | `(close!)` | Hide the shell — flip the container to `display: none`. DOM stays in place. |
| `toggle!` | `(toggle!)` | Flip visibility. Wired to `Ctrl+Shift+C`. |
| `popout!` | `(popout!)` | Open Xray in a same-origin second window. Own React root, own keybinding. |
| `status` | `(status)` → map | Inspectable shell state. `{:mounted? :visible? :last-host-diagnostic ...}`. |
| `target-frame` | `(target-frame)` → keyword \| nil | Read the currently-selected inspected-host frame, or `nil` when none is selected (never defaulted to `:rf/default`). One-shot read; not reactive. |
| `set-target-frame!` | `(set-target-frame! frame-id)` → nil | Set the inspected-host frame Xray targets. `nil` resets to the **unselected** state (not `:rf/default`). |
| `focus!` | `(focus! command)` → map | Host-facing focus handoff. Story and other hosts use it to focus a panel, epoch, cascade row, app-db path, or source target without rebuilding Xray's diagnostic UI. |
| `valid-focus-panels` | set value | Canonical focusable panel ids — one per live Dynamic L4 tab: `#{:epoch :app-db :views :trace :machines :routing :resources :derivation-graph :module-view :hicasso}`. The id is the internal registry key, not the visible label: `:routing` renders as "Routes", `:derivation-graph` as "Graph", `:module-view` as "Frames". A host that prefers the display noun may pass `:routes`, which normalises to `:routing`. |
| `load-theme` | `(load-theme css-string)` → nil | Programmatic theme override. Installs or replaces a host CSS block; `nil` or blank clears the override. |
| `configure!` | `(configure! opts)` → nil | Top-level config — re-exported from `config`. See [Configuration keys](config-keys.md). |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | Re-exported from `config`. Whether the preload auto-opens. |
| `set-editor!` | `(set-editor! editor)` → nil | Re-exported from `config`. Sets the "Open in editor" preference. |
| `set-show-sensitive!` | `(set-show-sensitive! bool)` → nil | Re-exported from `config`. Cross-tool `:rf.privacy/show-sensitive?` flag. |

## `day8.re-frame2-xray.config`

The full configuration surface. Reach here when you're flipping a knob the facade doesn't re-export, or when boot code is routing all config through `configure!`. Twelve setter surfaces plus seven published constants — the constants are values (not call-shapes) for docs generators, snippet helpers, and host stylesheet authoring.

### Setters

| Symbol | Signature | Intuition |
| --- | --- | --- |
| `configure!` | `(configure! opts)` → nil | Top-level config. Map keyed by `:rf.xray/*` and `:rf.privacy/*`. |
| `set-editor!` | `(set-editor! editor)` → nil | Editor preference. `:vscode` (default) / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <tpl>}`. |
| `set-project-root!` | `(set-project-root! path)` → nil | On-disk root prepended to classpath-relative `:file` slots before editor URIs ship. |
| `set-layout-host-selector!` | `(set-layout-host-selector! css-selector)` → nil | CSS selector for the auto-open path. Default `[data-rf-xray-host]`. |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | Whether the preload auto-opens on adapter readiness. Default `true`. |
| `set-keybinding-enabled!` | `(set-keybinding-enabled! bool)` → nil | Whether `keybinding/attach!` installs the global listener. Default `true`. |
| `set-show-sensitive!` | `(set-show-sensitive! bool)` → nil | Cross-tool `:rf.privacy/show-sensitive?` flag. Default `false`. |
| `set-filter-seed!` | `(set-filter-seed! seed-map)` → nil | Host-supplied seed pill set applied to `:active-filters` as the boot baseline — reapplied on every load after the transient-filter reset, not a first-install-only value. Shape: `{:in [{...}] :out [{...}]}`. |
| `set-filters-storage-key!` | `(set-filters-storage-key! key)` → nil | localStorage key the filter persistence layer uses. Default `"re-frame2.xray.filters.v1"`. |
| `update-setting!` | `(update-setting! path value)` → nil | Set one Settings slot. `path` is a vector into the settings map. |
| `reset-settings!` | `(reset-settings!)` → nil | Reset every Settings slot to its default. Wipes the localStorage slot. |
| `reset-suppressed-count!` | `(reset-suppressed-count!)` → nil | Clear the redaction/suppression counter. |

### Published constants

| Symbol | Value | Use |
|---|---|---|
| `default-layout-host-selector` | `"[data-rf-xray-host]"` | The default CSS selector. Re-emit in docs generators / diagnostics. |
| `default-layout-host-css-var` | `"--rf-xray-inline-width"` | The CSS custom property the host snippet reads for `flex-basis`. |
| `default-layout-host-width` | `"560px"` | The default value Xray recommends for `--rf-xray-inline-width`. |
| `default-accent-css-var` | `"--rf-xray-accent"` | The CSS custom property the host snippet publishes on `:root`. |
| `default-accent` | `"#539bf5"` | The default brand-accent hex (matches `theme/tokens.cljc :accent`). |
| `default-layout-host-snippet` | HTML + CSS block | Copy-pasteable host snippet. Carried in the missing-host diagnostic. |
| `settings-storage-key` | `"day8.re-frame2-xray/settings/v1"` | localStorage key for the Settings popup state. |

## `day8.re-frame2-xray.keybinding`

The lifecycle pair for the global `Ctrl+Shift+C` keydown listener. Reach here from embed hosts that need to take the chord back after Xray has already attached.

| Symbol | Signature | Intuition |
| --- | --- | --- |
| `attach!` | `(attach!)` → nil | Install the global listener once. Honours `:rf.xray/keybinding-enabled?`. No-op on second + subsequent calls. |
| `detach!` | `(detach!)` → nil | Remove the global listener. Idempotent. Symmetric with `attach!`. |

## `day8.re-frame2-xray.preload`

The dev-only side-effect bundle. You don't call anything here directly — you list the namespace in shadow-cljs's `:devtools/preloads` and the rest happens. The bundle runs six side-effects on load:

1. Register Xray's `:rf.xray/*` subs / events / fxs.
2. Register the trace collector as a `:rf.xray/trace-collector` listener.
3. Register the epoch-settle pump as a `:rf.xray/epoch-collector` listener.
4. Install the browser API on `window.day8.re_frame2_xray.*`.
5. Attach the global `Ctrl+Shift+C` keydown listener.
6. Auto-open the shell true-inline into the host's layout host once the substrate adapter is ready.

All six sit inside the preload's `(when rf.interop/debug-enabled? …)` block, so Closure folds them away under `:advanced` + `goog.DEBUG=false`, and all are idempotent so shadow-cljs's `:after-load` cycle re-runs without double-registration. That block gates the **preload** path only — `init!` runs the same six side-effects with no `goog.DEBUG` gate, and keeping that call out of a release build is build placement (see [Mount control §Production: what keeps Xray out](mount-control.md#production-what-keeps-xray-out)).

## `window.day8.re_frame2_xray.*` (browser-global JS mirror)

The preload installs a JS-side mirror so JS hosts, devtools-console one-liners, and `puppeteer` automation scripts can reach Xray's surfaces without a CLJS compile. Closure-mangled names with `_BANG_` suffixes for mutating fns.

| JS spelling | CLJS equivalent | Intuition |
|---|---|---|
| `window.day8.re_frame2_xray.open_BANG_()` | `(xray/open!)` | Mount + show the shell. |
| `window.day8.re_frame2_xray.open_overlay_BANG_()` | `(xray/open-overlay!)` | Mount as overlay. |
| `window.day8.re_frame2_xray.close_BANG_()` | `(xray/close!)` | Hide. |
| `window.day8.re_frame2_xray.toggle_BANG_()` | `(xray/toggle!)` | Flip visibility. |
| `window.day8.re_frame2_xray.popout_BANG_()` | `(xray/popout!)` | Pop out into a new window. |
| `window.day8.re_frame2_xray.status()` | `(xray/status)` | Inspectable status map. |

Once `core.cljs` has loaded, the same six fns are reachable under `window.day8.re_frame2_xray.core.*` so JS-console users see the canonical facade names. Both spellings are stable contracts.

## Panel reg-views (composed by the shell)

Ten Dynamic tab panels ship in `day8.re-frame2-xray.panels.*`. Hosts normally mount the full shell through `open!`, `open-overlay!`, or `popout!`; advanced tool surfaces can mount a focused panel through the panel facade when they are deliberately composing Xray-owned diagnostics.

Seven of the ten carry a standalone `mount-<panel>!` facade:

| Panel | Namespace | Surface |
|---|---|---|
| Epoch | `day8.re-frame2-xray.panels.epoch-panel` | `Panel` reg-view |
| App-DB Diff | `day8.re-frame2-xray.panels.app-db-diff` | `Panel` reg-view |
| Reactive (Views) | `day8.re-frame2-xray.panels.reactive-panel` | `Panel` reg-view |
| Trace | `day8.re-frame2-xray.panels.trace` | `Panel` reg-view |
| Machine Inspector | `day8.re-frame2-xray.panels.machine-inspector` | `Panel` reg-view |
| Routing | `day8.re-frame2-xray.panels.routing` | `Panel` reg-view |
| Resources | `day8.re-frame2-xray.panels.resources` | `Panel` reg-view |

The remaining three are **L4-only registry tabs** — registered for the tab strip and focusable through `focus!`, but shell-internal and not independently mountable into a host's own layout:

| Panel | Namespace | Surface |
|---|---|---|
| Graph (derivation graph) | `day8.re-frame2-xray.panels.derivation-graph` | `Panel` reg-view, registry only |
| Frames (module view) | `day8.re-frame2-xray.panels.module-view` | `Panel` reg-view, registry only |
| Hicasso | `day8.re-frame2-xray.panels.hicasso` | `Panel` reg-view, registry only |

Focusability and mountability are separate axes: every one of the ten is in `valid-focus-panels`, and only the first seven have a mount facade. See [11. The Hicasso tab](../11-hicasso-tab.md) for what the Hicasso panel shows.

Five parallel Static-mode panels browse the registrar rather than the event spine:

| Panel | Namespace | Surface |
|---|---|---|
| Static Machines | `day8.re-frame2-xray.static.machines.panel` | `Panel` reg-view |
| Static Flows | `day8.re-frame2-xray.static.flows.panel` | `Panel` reg-view |
| Static Interceptors | `day8.re-frame2-xray.static.interceptors.panel` | `Panel` reg-view |
| Static Routes | `day8.re-frame2-xray.static.routes.panel` | `Panel` reg-view |
| Static Schemas | `day8.re-frame2-xray.static.schemas.panel` | `Panel` reg-view |

## What this reference deliberately omits

Several surfaces are **publicly visible** in the CLJS source but explicitly *not part of the contract*. They're documented in the [developer-internal spec](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/API.md) for Xray's maintainers; this reference omits them on purpose.

- **`config.cljc` atom handles.** Every state setter writes to a `defonce` atom (`auto-open?`, `editor`, `keybinding-enabled?`, …); the atoms are reachable as `@day8.re-frame2-xray.config/<atom>` due to CLJS-default-public visibility. The setters are the canonical write path, the getters are the canonical read path. Reaching for the atom directly is reading an internal seam.
- **Internal `mount-<panel>!` aggregators.** The shell composer calls these to mount individual panels; they're not part of the host-facing embed contract. Full-shell embedding lives at [`008-Embedding-Contract.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/008-Embedding-Contract.md).
- **Predicate / mutation helpers.** `suppress-sensitive?`, `note-suppressed!`, `clamp-panel-width-px`, `editor-uri` — thin wrappers Xray's own modules consume. (The `:sensitive?` stamp predicate itself is the framework's `rf/sensitive?`, called directly.)
- **`register-toggle-off-callback!` / `unregister-toggle-off-callback!`.** Internal — Xray modules wire their buffer-clear hooks here. Host applications should NOT register.

If you find yourself reading source for a Xray-internal symbol because the chapters don't list it, the answer is almost always: the spec considers that surface internal, and a future minor release may rename or `^:private`-mark it. Reach for the documented surfaces in the chapters above instead.

## See also

- [Index](index.md) — the navigation map for the three chapters in this folder.
- [Mount control](mount-control.md) — `init!`, `open!`, `close!`, `toggle!`, `popout!`, `status`, the frame picker.
- [Configuration keys](config-keys.md) — `configure!` and the per-key setters.
- [Driving the app from outside](https://github.com/day8/re-frame2/blob/main/tools/re-frame2-pair-mcp/README.md) — Xray has no agent seam; `re-frame2-pair.runtime` + the Pair MCP server is that surface.
- [Normative spec — `tools/xray/spec/API.md`](https://github.com/day8/re-frame2/blob/main/tools/xray/spec/API.md) — the developer-internal source of truth.
