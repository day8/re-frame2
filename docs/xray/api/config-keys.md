# Configuration keys

This chapter is about the surfaces that tell Xray *how* to behave — which editor to open source-coords in, where the inline-host element lives in the DOM, whether to auto-open on boot, whether to surface sensitive trace events. The core of it is **one bulk-set entry point** — `configure!` — that takes a single map keyed by namespaced keywords, plus a parallel set of per-key setters for hosts that prefer to flip one knob at a time. The two surfaces are equivalent — `(configure! {:rf.xray/editor :cursor})` is identical to `(set-editor! :cursor)` — and you choose between them by ergonomics.

Boot-time configuration is **process-global**: the setters write to `defonce` atoms inside `config.cljc`, and Xray's own subs / events read those atoms via getters. This means hosts call `configure!` once at boot, before Xray's preload auto-opens, and the resulting posture is fixed for the session. Settings persisted through the in-shell Settings popup live in a parallel slot in `localStorage`; the relationship between the two is documented at the end of this chapter under [§Boot-time config vs persisted Settings](#boot-time-config-vs-persisted-settings).

## The bulk-set entry point

### `configure!`

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Description**: Top-level Xray configuration. Accepts a map keyed by `:rf.xray/*` (Xray-specific) and `:rf.privacy/*` (cross-tool) keys. Unknown keys are silently ignored (forward-compat — newer hosts passing older-Xray-unaware keys MUST NOT break). Hosts typically call once at boot, before Xray auto-opens.

`configure!` is re-exported from `core` for boot-time ergonomics. The two require paths are interchangeable:

```clojure
;; via the core facade — already on your require list for open!
(require '[day8.re-frame2-xray.core :as xray])
(xray/configure! {:rf.xray/editor :cursor})

;; via the config namespace — when boot code is routing all knobs through configure!
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor})
```

The full v1 key surface, grouped by topical cluster:

```clojure
(xray-config/configure!
  {;; Editor cluster — Open in editor preference
   :rf.xray/editor                :cursor   ; / :vscode (default) / :windsurf / :zed / :idea / {:custom <uri-template>}
   :rf.xray/project-root          "C:/Users/me/code/my-app"

   ;; Launch cluster — boot-time mount posture
   :rf.xray/auto-open?            true      ; default — preload auto-opens into the inline host
   :rf.xray/layout-host-selector  "#xray"  ; default "[data-rf-xray-host]"

   ;; Keybinding cluster — global listener install
   :rf.xray/keybinding-enabled?   true      ; default — set false from embed hosts that own the chord

   ;; Privacy cluster — cross-tool sensitive-event gate
   :rf.privacy/show-sensitive?     false     ; default — drop :sensitive? true events from the trace buffer

   ;; Settings cluster — bulk-replace the Settings popup state
   :rf.xray/settings              {:theme :dark :general {:density :cosy}}

   ;; Filters cluster — host-supplied seed pill set on first install
   :rf.xray/filters               {:out [{:pattern ":mouse-move"}]}
   :rf.xray/filters-storage-key   "re-frame2.xray.filters.v1"})
```

Every key lives under a reserved namespace — `:rf.xray/*` for Xray-specific knobs, `:rf.privacy/*` for cross-tool slots Story and any other re-frame2 tool also reads. Unknown keys are silently ignored so newer hosts (passing keys an older Xray hasn't shipped yet) don't break, and newer Xray releases shipping additional keys don't break older hosts.

## Editor cluster

Every panel that surfaces a source-coord wraps the coord in a clickable `open` chip. Clicking sets `window.location.href` to a URI-scheme handler the OS dispatches to the configured editor.

### `set-editor!`

- **Signature**:
  ```clojure
  (set-editor! editor) → nil
  ```
- **Description**: Set the editor preference. Accepts `:vscode` (default), `:cursor`, `:windsurf`, `:zed`, `:idea`, `{:custom <uri-template>}`. `nil` resets to `:vscode`. Re-exported from `core`.

### `set-project-root!`

- **Signature**:
  ```clojure
  (set-project-root! path) → nil
  ```
- **Description**: On-disk root prepended to the source-coord's classpath-relative `:file` slot before the editor URI ships. Default `nil` — hosts whose source paths are already absolute leave this unset. Nil / blank clears the slot; an absent key in `configure!` leaves the current value untouched.

The URI schemes:

| Editor key | URI scheme |
|---|---|
| `:vscode` (default) | `vscode://file/<path>:<line>:<column>` |
| `:cursor` | `cursor://file/<path>:<line>:<column>` |
| `:windsurf` | `windsurf://file/<path>:<line>:<column>` |
| `:zed` | `zed://file/<path>:<line>:<column>` |
| `:idea` | `idea://open?file=<path>&line=<line>&column=<column>` |
| `{:custom <tpl>}` | User template with `{path}` / `{file}` / `{line}` / `{column}` placeholders |

Unknown keywords fall back to `:vscode` so a typo still yields a clickable URI rather than a no-op. Source-coords without `:file` hide the chip entirely.

Xray's editor preference is **independent** of Story's `:rf.story/editor` (hosts that run both tools can route each to a different editor). The shared URI builder lives at `re-frame.source-coords.editor-uri` in the framework core; Xray's chip is a thin wrapper that consumes it.

## Launch cluster

The launch cluster controls the auto-open posture and the layout-host wiring. Set these *before* the preload runs (before `rf/init!` returns) so the first auto-open path reads the right values.

### `set-auto-open!`

- **Signature**:
  ```clojure
  (set-auto-open! bool) → nil
  ```
- **Description**: Whether the preload auto-opens the shell into the inline host on adapter readiness. Default `true`. Set `false` from tool-owned pages that deliberately don't reserve app real estate for Xray (Story-only canvases, internal dev tools whose layout can't host a right column). Explicit `(xray/open!)` calls still mount after suppression. Re-exported from `core`.

### `set-layout-host-selector!`

- **Signature**:
  ```clojure
  (set-layout-host-selector! css-selector) → nil
  ```
- **Description**: The CSS selector the auto-open path queries on adapter readiness. Default `[data-rf-xray-host]`. Override when your host's preferred selector differs (e.g. `#devtools-xray`).

The default selector `[data-rf-xray-host]` is published as a CLJS constant — `day8.re-frame2-xray.config/default-layout-host-selector` — so docs generators and tool chrome can re-emit the canonical spelling without forking the string.

Three more published constants name the inline-host CSS contract. These are constants (values, not setters) — overriding the CSS custom property happens in the host's stylesheet, not through CLJS.

| Constant | Value | Use |
|---|---|---|
| `default-layout-host-css-var` | `"--rf-xray-inline-width"` | The CSS custom property the recommended host snippet reads for its `flex-basis`. Xray never reads this property; the host's stylesheet is the single source of truth for inline width. |
| `default-layout-host-width` | `"560px"` | Xray's recommended default value for `--rf-xray-inline-width`. |
| `default-accent-css-var` | `"--rf-xray-accent"` | The CSS custom property the recommended host snippet publishes on `:root` for Xray's brand-accent colour. Host stylesheets read `var(--rf-xray-accent)` to colour their own dev chrome (resize handles, dock separators, story chips). |
| `default-accent` | `"#539bf5"` | Xray's default brand-accent hex (matches `theme/tokens.cljc :accent`, GitHub blue). |
| `default-layout-host-snippet` | HTML+CSS block | A copy-pasteable host snippet carrying the recommended markup, `flex-basis` rule, `:root` accent publish, and `min-width: 320px` floor. Reported back to the user in the missing-host diagnostic so the actionable `console.error` already carries the fix. |

## Keybinding cluster

Xray installs a global `Ctrl+Shift+C` keydown listener as one of the preload's six side-effects. Standalone Xray always needs the listener; embed hosts (Story mounts Xray as a right-hand-side panel) sometimes need to take the chord back.

### `set-keybinding-enabled!`

- **Signature**:
  ```clojure
  (set-keybinding-enabled! bool) → nil
  ```
- **Description**: Whether `keybinding/attach!` installs the global window-level keydown listener. Default `true` — standalone Xray needs the listener. Embed hosts set `false` so their own global keybindings (typically `Cmd/Ctrl+K` for the host's command palette) are not swallowed by Xray's capture-phase listener. MUST be set BEFORE the Xray preload runs.

The setter suppresses the install at attach time; embed hosts whose mount lifecycle runs AFTER Xray's preload has already attached use the imperative escape hatch instead:

```clojure
(require '[day8.re-frame2-xray.keybinding :as xray-keybinding])

;; Take the chord back.
(xray-keybinding/detach!)
```

`detach!` is symmetric and idempotent. See [the runtime-seam chapter §Keybinding lifecycle](runtime-seam.md#keybinding-lifecycle) for the full attach / detach contract.

## Privacy cluster

The privacy cluster carries one cross-tool gate that Xray, Story, and any other re-frame2 tool consuming the trace bus all read. The key lives under `:rf.privacy/*` (not `:rf.xray/*`) because the slot is shared.

### `set-show-sensitive!`

- **Signature**:
  ```clojure
  (set-show-sensitive! bool) → nil
  ```
- **Description**: The cross-tool `:rf.privacy/show-sensitive?` flag. When `false` (default), Xray's trace collector drops `:sensitive? true` events and the shell surfaces a redaction hint near the always-on issue/status signals. Set to `true` while debugging redaction policy to see the raw cascade. `nil` resets to the default. Re-exported from `core`.

The single normative emission site for `:sensitive?` redaction is the framework's `elide-wire-value` (see [framework API instrumentation §The wire-boundary walker](../../api/11-instrumentation.md#the-wire-boundary-walker)). Xray's gate just decides whether the redacted-out events reach the buffer at all.

## Settings cluster

The Settings popup carries the user-mutable knobs — theme, density, buffer depths, AI provider config, filter persistence settings. The bulk-set escape hatch lets a host ship its own default Settings shape; the per-knob writes flow through the popup's normal `:rf.xray/settings-update` event.

### `update-setting!`

- **Signature**:
  ```clojure
  (update-setting! path value) → nil
  ```
- **Description**: Set one Settings slot. `path` is a vector into the settings map (e.g. `[:general :density]`); `value` is the new value. Persists through localStorage. The popup's event surface is the canonical write path; reach for this only from REPL / test contexts.

### `reset-settings!`

- **Signature**:
  ```clojure
  (reset-settings!) → nil
  ```
- **Description**: Reset every Settings slot to its default shape. Wipes the localStorage slot. Mostly a test-isolation helper; hosts that want to ship a non-default shape use `configure! {:rf.xray/settings ...}` instead.

### `reset-suppressed-count!`

- **Signature**:
  ```clojure
  (reset-suppressed-count!) → nil
  ```
- **Description**: Clear the redaction/suppression counter that surfaces near Xray's shell status signals when filters elide events. Reach for this when wiring a Settings-popup "Clear suppression counter" button or a test-harness fixture-reset.

The Settings shape (validated by Malli):

```clojure
{:theme         :dark / :light / :high-contrast
 :density       :compact / :cosy / :comfy
 :ai-provider   {:provider :claude / :openai / :gemini / :local / :custom
                 :api-key      "sk-..."
                 :model        "claude-3-5-sonnet"
                 :system-prompt "..."
                 :custom-url   "https://..."   ;; only when :provider = :custom
                 :custom-headers {"X-..." "..."}}
 :buffer-depths {:trace 200 :epoch 50}
 :sidebar-mode  :grouped / :show-all
 :launcher-pill {:hidden? false}
 :keybindings   {:toggle ["Ctrl+Shift+C"] ...}}
```

Corruption (schema fails) → Xray wipes the slot and writes the default shape, surfacing a one-time toast: "Settings were corrupted and have been reset to defaults."

The settings persist under the localStorage key `day8.re-frame2-xray/settings/v1` (also published as the CLJS constant `day8.re-frame2-xray.config/settings-storage-key`).

## Filters cluster

The Trace panel ships filter pills (`+ pattern`, `- pattern`, `+ :origin`, `+ frame`) that drive in / out filtering of the displayed events. Filter state persists in localStorage across reloads; hosts that want to seed a starting filter set on first install reach for the filters cluster.

### `set-filter-seed!`

- **Signature**:
  ```clojure
  (set-filter-seed! seed-map) → nil
  ```
- **Description**: Host-supplied seed pill set the registry hydrates `:active-filters` with on FIRST install (when localStorage is empty). Shape: `{:in [{...}] :out [{...}]}`. Default `nil` — first session boots with no filters (first-session honesty beats first-session quietness). Story testbeds use this to inject a known starting point for reproducibility.

### `set-filters-storage-key!`

- **Signature**:
  ```clojure
  (set-filters-storage-key! key) → nil
  ```
- **Description**: The localStorage key the filter persistence layer reads / writes. Default `"re-frame2.xray.filters.v1"`. Hosts that run multiple Xray instances (Story testbeds, multi-mode tool pages) override for isolation between instances.

Set both *before* the preload runs so the first registry-handlers registration reads the right values.

## Boot-time config vs persisted Settings

Xray carries three orthogonal configuration surfaces. The split is principled — each answers a different question — and the merge order is fixed.

| Surface | Where | Lifetime | Examples |
|---|---|---|---|
| **Defaults** | Hardcoded in `config.cljc` | Compile-time constants | Editor `:vscode`, auto-open `true`, layout host `[data-rf-xray-host]` |
| **Boot-time `configure!`** | Host's app boot | Process-global, fixed for session | `(configure! {:rf.xray/editor :cursor})` — flips the editor for this dev session |
| **Persisted Settings** | The Settings popup | User-mutable, localStorage | User picks `:density :comfy` from the popup — sticks across reloads |

**Merge order: defaults < `configure!` < persisted Settings.** A host config knob is the *default* from the user's perspective; the user's Settings overrides win at the per-knob level. `(xray/init! opts)` receives the merged config.

The three answer different questions: `configure!` is the boot-time data knob (set once, don't change at runtime); the in-shell Settings popup is the user-mutable preference layer (user changes density from `:cosy` to `:comfy`, sticks across reloads); per-frame metadata (not in scope here) is the frame-scoped override.

## See also

- [Mount control](mount-control.md) — `open!` / `close!` / `toggle!` / `popout!` and the lifecycle the auto-open setting drives.
- [Runtime seam](runtime-seam.md) — the keybinding `attach!` / `detach!` lifecycle pair the keybinding cluster setters control.
- [Xray tutorial — Installation](../01-installation.md) — the five-minute wiring walkthrough with the recommended host snippet.
- [Framework API — Instrumentation](../../api/11-instrumentation.md#the-wire-boundary-walker) — `elide-wire-value`, the single normative emission site for `:sensitive?` redaction that the privacy cluster gates.
