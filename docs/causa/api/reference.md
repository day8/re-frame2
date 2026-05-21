# Reference

The complete symbol table for Causa's public surface, organised by namespace for `Ctrl-F` use. Every row carries a signature, a status, and a one-line intuition — the same shape as the topical chapters, but flat and exhaustive. Reach for the topical chapters when you want context and prose around the contract; reach for this page when you know what you're looking for and just want the row.

Surfaces fall into six namespaces and one browser global. The split is principled — each namespace answers a distinct question — but the row count varies wildly. `core` carries 12 surfaces; `runtime` carries 20; `keybinding` carries 2. If you're scanning for a single function and don't remember which namespace owns it, the right move is `Ctrl-F` on this page.

For the topical walk-through with intuition notes and use-when prose, see [Mount control](mount-control.md), [Configuration keys](config-keys.md), and [Runtime seam](runtime-seam.md). For the index of *what* this reference covers (and what it deliberately omits — the Causa-internal panel composers, the static-mode catalogues, the atom handles that mirror state the setters write to), see [the index](index.md#what-canonical-means-here).

## `day8.re-frame2-causa.core`

The canonical facade. The day-to-day require for host integrations. Twelve surfaces total — the mount facade, the frame picker, the TBD theme stub, and four high-traffic config re-exports.

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `init!` | `(init!)` / `(init! opts)` → nil | v1 (dev-only) | Manual install — the alternative to wiring `:preloads`. Idempotent. |
| `open!` | `(open!)` → mount-state map or missing-host diagnostic | v1 (dev-only) | Mount + show the shell true-inline into the host's layout host. The canonical default. |
| `open-overlay!` | `(open-overlay!)` | v1 (dev-only) | Mount as a fixed overlay under `<body>`. Floats above host layout. |
| `close!` | `(close!)` | v1 (dev-only) | Hide the shell — flip the container to `display: none`. DOM stays in place. |
| `toggle!` | `(toggle!)` | v1 (dev-only) | Flip visibility. Wired to `Ctrl+Shift+C`. |
| `popout!` | `(popout!)` | v1 (dev-only) | Open Causa in a same-origin second window. Own React root, own keybinding. |
| `status` | `(status)` → map | v1 (dev-only) | Inspectable shell state. `{:mounted? :visible? :last-host-diagnostic ...}`. |
| `target-frame` | `(target-frame)` → keyword | v1 (dev-only) | Read the currently-targeted host frame. One-shot read; not reactive. |
| `set-target-frame!` | `(set-target-frame! frame-id)` → nil | v1 (dev-only) | Set the host frame Causa targets. `nil` resets to default. |
| `load-theme` | `(load-theme css-string)` → nil | TBD-impl | Programmatic theme swap. Stub — emits `:rf.warning/causa-load-theme-not-yet-implemented`. |
| `configure!` | `(configure! opts)` → nil | v1 (dev-only) | Top-level config — re-exported from `config`. See [Configuration keys](config-keys.md). |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | v1 (dev-only) | Re-exported from `config`. Whether the preload auto-opens. |
| `set-editor!` | `(set-editor! editor)` → nil | v1 (dev-only) | Re-exported from `config`. Sets the "Open in editor" preference. |
| `set-show-sensitive!` | `(set-show-sensitive! bool)` → nil | v1 (dev-only) | Re-exported from `config`. Cross-tool `:rf.privacy/show-sensitive?` flag. |

## `day8.re-frame2-causa.config`

The full configuration surface. Reach here when you're flipping a knob the facade doesn't re-export, or when boot code is routing all config through `configure!`. Twelve setter surfaces plus seven published constants — the constants are values (not call-shapes) for docs generators, snippet helpers, and host stylesheet authoring.

### Setters

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `configure!` | `(configure! opts)` → nil | v1 (dev-only) | Top-level config. Map keyed by `:rf.causa/*` and `:rf.privacy/*`. |
| `set-editor!` | `(set-editor! editor)` → nil | v1 (dev-only) | Editor preference. `:vscode` (default) / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <tpl>}`. |
| `set-project-root!` | `(set-project-root! path)` → nil | v1 (dev-only) | On-disk root prepended to classpath-relative `:file` slots before editor URIs ship. |
| `set-layout-host-selector!` | `(set-layout-host-selector! css-selector)` → nil | v1 (dev-only) | CSS selector for the auto-open path. Default `[data-rf-causa-host]`. |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | v1 (dev-only) | Whether the preload auto-opens on adapter readiness. Default `true`. |
| `set-keybinding-enabled!` | `(set-keybinding-enabled! bool)` → nil | v1 (dev-only) | Whether `keybinding/attach!` installs the global listener. Default `true`. |
| `set-show-sensitive!` | `(set-show-sensitive! bool)` → nil | v1 (dev-only) | Cross-tool `:rf.privacy/show-sensitive?` flag. Default `false`. |
| `set-filter-seed!` | `(set-filter-seed! seed-map)` → nil | v1 (dev-only) | Host-supplied seed pill set for first install. Shape: `{:in [{...}] :out [{...}]}`. |
| `set-filters-storage-key!` | `(set-filters-storage-key! key)` → nil | v1 (dev-only) | localStorage key the filter persistence layer uses. Default `"re-frame2.causa.filters.v1"`. |
| `update-setting!` | `(update-setting! path value)` → nil | v1 (dev-only) | Set one Settings slot. `path` is a vector into the settings map. |
| `reset-settings!` | `(reset-settings!)` → nil | v1 (dev-only) | Reset every Settings slot to its default. Wipes the localStorage slot. |
| `reset-suppressed-count!` | `(reset-suppressed-count!)` → nil | v1 (dev-only) | Clear the `[● REDACTED N]` bottom-rail counter. |

### Published constants

| Symbol | Value | Use |
|---|---|---|
| `default-layout-host-selector` | `"[data-rf-causa-host]"` | The default CSS selector. Re-emit in docs generators / diagnostics. |
| `default-layout-host-css-var` | `"--rf-causa-inline-width"` | The CSS custom property the host snippet reads for `flex-basis`. |
| `default-layout-host-width` | `"560px"` | The default value Causa recommends for `--rf-causa-inline-width`. |
| `default-accent-css-var` | `"--rf-causa-accent"` | The CSS custom property the host snippet publishes on `:root`. |
| `default-accent` | `"#7C5CFF"` | The default brand-accent hex (matches `theme/tokens.cljc :accent-violet`). |
| `default-layout-host-snippet` | HTML + CSS block | Copy-pasteable host snippet. Carried in the missing-host diagnostic. |
| `settings-storage-key` | `"day8.re-frame2-causa/settings/v1"` | localStorage key for the Settings popup state. |

## `day8.re-frame2-causa.keybinding`

The lifecycle pair for the global `Ctrl+Shift+C` keydown listener. Reach here from embed hosts that need to take the chord back after Causa has already attached.

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `attach!` | `(attach!)` → nil | v1 (dev-only) | Install the global listener once. Honours `:rf.causa/keybinding-enabled?`. No-op on second + subsequent calls. |
| `detach!` | `(detach!)` → nil | v1 (dev-only) | Remove the global listener. Idempotent. Symmetric with `attach!`. |

## `day8.re-frame2-causa.runtime`

The Causa ↔ tool read-and-mutate seam. Twenty surfaces — discovery, origin tag, eighteen accessors split across inspection / mutation / streaming / escape-hatch / meta / test bands. Reach here when you're writing tool-shaped code: an MCP server, an IDE plugin, a record-replay harness, a custom in-app debug panel.

### Discovery + origin

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `session-id` | Var (string UUID) | v1 (dev-only) | Per-preload random UUID. Survives `:after-load`; wiped on full page refresh. Proves the runtime landed. |
| `*current-origin*` | `^:dynamic` Var | v1 (dev-only) | The `:tags :origin` value the runtime stamps onto every mutation. Default `:causa-mcp`. Tool clients rebind for synchronous extent. |
| `current-origin` | `(current-origin)` → keyword | v1 (dev-only) | Read accessor — answers "what's the current `:origin` tag?". |

### Inspection band (9 read-only)

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `get-trace-buffer` | `(get-trace-buffer opts)` → map | v1 (dev-only) | Filtered slice of the framework's trace stream. Filter keys per Spec 009. |
| `get-epoch-history` | `(get-epoch-history opts)` → map | v1 (dev-only) | Per-frame epoch ring buffer. Default depth 50. |
| `get-app-db` | `(get-app-db opts)` → map | v1 (dev-only) | Live `app-db` for a frame, optionally scoped by `:path`. Routes through `elide-wire-value`. |
| `get-app-db-diff` | `(get-app-db-diff opts)` → map | v1 (dev-only) | `:db-before` + `:db-after` off a named epoch record. |
| `get-machine-state` | `(get-machine-state opts)` → map | v1 (dev-only) | Per-machine state read for the registered machine spec. |
| `get-machine-list` | `(get-machine-list opts)` → map | v1 (dev-only) | Map of every machine in the active frame, keyed by machine-id. |
| `get-issues` | `(get-issues opts)` → map | v1 (dev-only) | Trace events filtered to issue-tier op-types — error / warning / schema violation / hydration mismatch. |
| `get-handlers` | `(get-handlers opts)` → map | v1 (dev-only) | Registrar listing, optionally narrowed by `:kind`. |
| `get-source-coord` | `(get-source-coord opts)` → map | v1 (dev-only) | Per-registration source-coord projection. `{:ns :file :line :column}`. |

### Mutation band (3 write)

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `dispatch!` | `(dispatch! event-vec opts)` → map | v1 (dev-only) | Fire an event tagged with the current origin. Modes `:queued` / `:sync`. |
| `restore-epoch!` | `(restore-epoch! opts)` → map | v1 (dev-only) | Rewind a frame's `app-db` to a named epoch's `:db-after`. |
| `reset-frame-db!` | `(reset-frame-db! opts)` → map | v1 (dev-only) | Inject a value into a frame's `app-db`. Schema-validates. |

### Streaming band (3 subscription)

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `subscribe!` | `(subscribe! opts)` → map | v1 (dev-only) | Open a streaming subscription. `:topic ∈ #{:trace :epoch :fx :error}`. |
| `unsubscribe!` | `(unsubscribe! opts)` → map | v1 (dev-only) | Idempotent close. |
| `list-subscriptions` | `(list-subscriptions)` → map | v1 (dev-only) | Diagnostic enumerating active runtime-side subscription metadata. |

### Escape hatch + meta + test

| Symbol | Signature | Status | Intuition |
|---|---|---|---|
| `eval-form-result` | `(eval-form-result value opts)` → map | v1 (dev-only) | Runtime-side result shaper for the MCP server's `eval-cljs` channel. Privacy + size scrubbing. |
| `health` | `(health)` → map | v1 (dev-only) | One-call summary. `{:session-id :debug-enabled? :frames :ambiguous-frame? :coord-annotation-enabled? :origin}`. |
| `tail-build-probe` | `(tail-build-probe)` → map | v1 (dev-only) | Monotonic counter for hot-reload change-detect. Survives `:after-load`. |
| `reset-for-test!` | `(reset-for-test!)` → nil | v1 (test-only) | Clears subscriptions + probe-counter. Test-only. |

## `day8.re-frame2-causa.preload`

The dev-only side-effect bundle. You don't call anything here directly — you list the namespace in shadow-cljs's `:devtools/preloads` and the rest happens. The bundle runs six side-effects on load:

1. Register Causa's `:rf.causa/*` subs / events / fxs.
2. Register the trace collector as a `:rf.causa/trace-collector` listener.
3. Register the epoch-settle pump as a `:rf.causa/epoch-collector` listener.
4. Install the browser API on `window.day8.re_frame2_causa.*`.
5. Attach the global `Ctrl+Shift+C` keydown listener.
6. Auto-open the shell true-inline into the host's layout host once the substrate adapter is ready.

All gated on `re-frame.interop/debug-enabled?` so production bundles strip them via Closure DCE, and all idempotent so shadow-cljs's `:after-load` cycle re-runs without double-registration.

## `window.day8.re_frame2_causa.*` (browser-global JS mirror)

The preload installs a JS-side mirror so JS hosts, devtools-console one-liners, and `puppeteer` automation scripts can reach Causa's surfaces without a CLJS compile. Closure-mangled names with `_BANG_` suffixes for mutating fns.

| JS spelling | CLJS equivalent | Intuition |
|---|---|---|
| `window.day8.re_frame2_causa.open_BANG_()` | `(causa/open!)` | Mount + show the shell. |
| `window.day8.re_frame2_causa.open_overlay_BANG_()` | `(causa/open-overlay!)` | Mount as overlay. |
| `window.day8.re_frame2_causa.close_BANG_()` | `(causa/close!)` | Hide. |
| `window.day8.re_frame2_causa.toggle_BANG_()` | `(causa/toggle!)` | Flip visibility. |
| `window.day8.re_frame2_causa.popout_BANG_()` | `(causa/popout!)` | Pop out into a new window. |
| `window.day8.re_frame2_causa.status()` | `(causa/status)` | Inspectable status map. |

Once `core.cljs` has loaded, the same six fns are reachable under `window.day8.re_frame2_causa.core.*` so JS-console users see the canonical facade names. Both spellings are stable contracts.

## Panel reg-views (composed by the shell)

Eight `Panel` reg-views ship in `day8.re-frame2-causa.panels.*`. They are **not** a host-facing single-panel embed surface — hosts that want to mount Causa embed the full shell via the [embedding contract](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/008-Embedding-Contract.md). The panel exports are documented here for tool integrators (Story's chip-catalogue, the panel-gallery testbed) that compose against them.

| Panel | Namespace | Surface |
|---|---|---|
| Event Detail | `day8.re-frame2-causa.panels.event-detail` | `Panel` reg-view |
| App-DB Diff | `day8.re-frame2-causa.panels.app-db-diff` | `Panel` reg-view |
| Reactive (Views) | `day8.re-frame2-causa.panels.reactive-panel` | `Panel` reg-view |
| Trace | `day8.re-frame2-causa.panels.trace` | `Panel` reg-view |
| Machine Inspector | `day8.re-frame2-causa.panels.machine-inspector` | `Panel` reg-view |
| Machines Canvas | `day8.re-frame2-causa.panels.machines-canvas.panel` | `Panel` reg-view |
| Routing | `day8.re-frame2-causa.panels.routing` | `Panel` reg-view |
| Issues Ribbon | `day8.re-frame2-causa.panels.issues-ribbon` | `Panel` reg-view |

Four parallel Static-mode panels browse the registrar rather than the event spine:

| Panel | Namespace | Surface |
|---|---|---|
| Static Flows | `day8.re-frame2-causa.static.flows.panel` | `Panel` reg-view |
| Static Interceptors | `day8.re-frame2-causa.static.interceptors.panel` | `Panel` reg-view |
| Static Routes | `day8.re-frame2-causa.static.routes.panel` | `Panel` reg-view |
| Static Schemas | `day8.re-frame2-causa.static.schemas.panel` | `Panel` reg-view |

## What this reference deliberately omits

Several surfaces are **publicly visible** in the CLJS source but explicitly *not part of the contract*. They're documented in the [developer-internal spec](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/API.md) for Causa's maintainers; this reference omits them on purpose.

- **`config.cljc` atom handles.** Every state setter writes to a `defonce` atom (`auto-open?`, `editor`, `keybinding-enabled?`, …); the atoms are reachable as `@day8.re-frame2-causa.config/<atom>` due to CLJS-default-public visibility. The setters are the canonical write path, the getters are the canonical read path. Reaching for the atom directly is reading an internal seam.
- **Internal `mount-<panel>!` aggregators.** The shell composer calls these to mount individual panels; they're not part of the host-facing embed contract. Full-shell embedding lives at [`008-Embedding-Contract.md`](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/008-Embedding-Contract.md).
- **Predicate / mutation helpers.** `sensitive-event?`, `suppress-sensitive?`, `note-suppressed!`, `clamp-panel-width-px`, `editor-uri` — thin wrappers Causa's own modules consume.
- **`register-toggle-off-callback!` / `unregister-toggle-off-callback!`.** Internal — Causa modules wire their buffer-clear hooks here. Host applications should NOT register.

If you find yourself reading source for a Causa-internal symbol because the chapters don't list it, the answer is almost always: the spec considers that surface internal, and a future minor release may rename or `^:private`-mark it. Reach for the documented surfaces in the chapters above instead.

## See also

- [Index](index.md) — the navigation map for the four chapters in this folder.
- [Mount control](mount-control.md) — `init!`, `open!`, `close!`, `toggle!`, `popout!`, `status`, the frame picker.
- [Configuration keys](config-keys.md) — `configure!` and the per-key setters.
- [Runtime seam](runtime-seam.md) — the read-and-mutate accessor surface for tools.
- [Normative spec — `tools/causa/spec/API.md`](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/API.md) — the developer-internal source of truth.
