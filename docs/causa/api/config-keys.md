# Configuration keys

This chapter is about the surfaces that tell Causa *how* to behave — which editor to open source-coords in, where the inline-host element lives in the DOM, whether to auto-open on boot, whether to surface sensitive trace events. The core of it is **one bulk-set entry point** — `configure!` — that takes a single map keyed by namespaced keywords, plus a parallel set of per-key setters for hosts that prefer to flip one knob at a time. The two surfaces are equivalent — `(configure! {:rf.causa/editor :cursor})` is identical to `(set-editor! :cursor)` — and you choose between them by ergonomics.

Boot-time configuration is **process-global**: the setters write to `defonce` atoms inside `config.cljc`, and Causa's own subs / events read those atoms via getters. This means hosts call `configure!` once at boot, before Causa's preload auto-opens, and the resulting posture is fixed for the session. Settings persisted through the in-shell Settings popup live in a parallel slot in `localStorage`; the relationship between the two is documented at the end of this chapter under [§Boot-time config vs persisted Settings](#boot-time-config-vs-persisted-settings).

## The bulk-set entry point

### `configure!`

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Top-level Causa configuration. Accepts a map keyed by `:rf.causa/*` (Causa-specific) and `:rf.privacy/*` (cross-tool) keys. Unknown keys are silently ignored (forward-compat — newer hosts passing older-Causa-unaware keys MUST NOT break). Hosts typically call once at boot, before Causa auto-opens.

`configure!` is re-exported from `core` for boot-time ergonomics. The two require paths are interchangeable:

```clojure
;; via the core facade — already on your require list for open!
(require '[day8.re-frame2-causa.core :as causa])
(causa/configure! {:rf.causa/editor :cursor})

;; via the config namespace — when boot code is routing all knobs through configure!
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:rf.causa/editor :cursor})
```

The full v1 key surface, grouped by topical cluster:

```clojure
(causa-config/configure!
  {;; Editor cluster — Open in editor preference
   :rf.causa/editor                :cursor   ; / :vscode (default) / :windsurf / :zed / :idea / {:custom <uri-template>}
   :rf.causa/project-root          "C:/Users/me/code/my-app"

   ;; Launch cluster — boot-time mount posture
   :rf.causa/auto-open?            true      ; default — preload auto-opens into the inline host
   :rf.causa/layout-host-selector  "#causa"  ; default "[data-rf-causa-host]"

   ;; Keybinding cluster — global listener install
   :rf.causa/keybinding-enabled?   true      ; default — set false from embed hosts that own the chord

   ;; Privacy cluster — cross-tool sensitive-event gate
   :rf.privacy/show-sensitive?     false     ; default — drop :sensitive? true events from the trace buffer

   ;; Settings cluster — bulk-replace the Settings popup state
   :rf.causa/settings              {:theme :dark :general {:density :cosy}}

   ;; Filters cluster — host-supplied seed pill set on first install
   :rf.causa/filters               {:out [{:pattern ":mouse-move"}]}
   :rf.causa/filters-storage-key   "re-frame2.causa.filters.v1"})
```

Every key lives under a reserved namespace — `:rf.causa/*` for Causa-specific knobs, `:rf.privacy/*` for cross-tool slots Story and any other re-frame2 tool also reads. Unknown keys are silently ignored so newer hosts (passing keys an older Causa hasn't shipped yet) don't break, and newer Causa releases shipping additional keys don't break older hosts.

## Editor cluster

Every panel that surfaces a source-coord wraps the coord in a clickable `open` chip. Clicking sets `window.location.href` to a URI-scheme handler the OS dispatches to the configured editor.

### `set-editor!`

- **Signature**:
  ```clojure
  (set-editor! editor) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Set the editor preference. Accepts `:vscode` (default), `:cursor`, `:windsurf`, `:zed`, `:idea`, `{:custom <uri-template>}`. `nil` resets to `:vscode`. Re-exported from `core`.

### `set-project-root!`

- **Signature**:
  ```clojure
  (set-project-root! path) → nil
  ```
- **Status**: v1 (dev-only)
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

Causa's editor preference is **independent** of Story's `:rf.story/editor` (hosts that run both tools can route each to a different editor). The shared URI builder lives at `re-frame.source-coords.editor-uri` in the framework core; Causa's chip is a thin wrapper that consumes it.

## Launch cluster

The launch cluster controls the auto-open posture and the layout-host wiring. Set these *before* the preload runs (before `rf/init!` returns) so the first auto-open path reads the right values.

### `set-auto-open!`

- **Signature**:
  ```clojure
  (set-auto-open! bool) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Whether the preload auto-opens the shell into the inline host on adapter readiness. Default `true`. Set `false` from tool-owned pages that deliberately don't reserve app real estate for Causa (Story-only canvases, internal dev tools whose layout can't host a right column). Explicit `(causa/open!)` calls still mount after suppression. Re-exported from `core`.

### `set-layout-host-selector!`

- **Signature**:
  ```clojure
  (set-layout-host-selector! css-selector) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: The CSS selector the auto-open path queries on adapter readiness. Default `[data-rf-causa-host]`. Override when your host's preferred selector differs (e.g. `#devtools-causa`).

The default selector `[data-rf-causa-host]` is published as a CLJS constant — `day8.re-frame2-causa.config/default-layout-host-selector` — so docs generators and tool chrome can re-emit the canonical spelling without forking the string.

Three more published constants name the inline-host CSS contract. These are constants (values, not setters) — overriding the CSS custom property happens in the host's stylesheet, not through CLJS.

| Constant | Value | Use |
|---|---|---|
| `default-layout-host-css-var` | `"--rf-causa-inline-width"` | The CSS custom property the recommended host snippet reads for its `flex-basis`. Causa never reads this property; the host's stylesheet is the single source of truth for inline width. |
| `default-layout-host-width` | `"560px"` | Causa's recommended default value for `--rf-causa-inline-width`. |
| `default-accent-css-var` | `"--rf-causa-accent"` | The CSS custom property the recommended host snippet publishes on `:root` for Causa's brand-accent colour. Host stylesheets read `var(--rf-causa-accent)` to colour their own dev chrome (resize handles, dock separators, story chips). |
| `default-accent` | `"#7C5CFF"` | Causa's default brand-accent hex (matches `theme/tokens.cljc :accent-violet`). |
| `default-layout-host-snippet` | HTML+CSS block | A copy-pasteable host snippet carrying the recommended markup, `flex-basis` rule, `:root` accent publish, and `min-width: 320px` floor. Reported back to the user in the missing-host diagnostic so the actionable `console.error` already carries the fix. |

## Keybinding cluster

Causa installs a global `Ctrl+Shift+C` keydown listener as one of the preload's six side-effects. Standalone Causa always needs the listener; embed hosts (Story mounts Causa as a right-hand-side panel) sometimes need to take the chord back.

### `set-keybinding-enabled!`

- **Signature**:
  ```clojure
  (set-keybinding-enabled! bool) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Whether `keybinding/attach!` installs the global window-level keydown listener. Default `true` — standalone Causa needs the listener. Embed hosts set `false` so their own global keybindings (typically `Cmd/Ctrl+K` for the host's command palette) are not swallowed by Causa's capture-phase listener. MUST be set BEFORE the Causa preload runs.

The setter suppresses the install at attach time; embed hosts whose mount lifecycle runs AFTER Causa's preload has already attached use the imperative escape hatch instead:

```clojure
(require '[day8.re-frame2-causa.keybinding :as causa-keybinding])

;; Take the chord back.
(causa-keybinding/detach!)
```

`detach!` is symmetric and idempotent. See [the runtime-seam chapter §Keybinding lifecycle](runtime-seam.md#keybinding-lifecycle) for the full attach / detach contract.

## Privacy cluster

The privacy cluster carries one cross-tool gate that Causa, Story, and any other re-frame2 tool consuming the trace bus all read. The key lives under `:rf.privacy/*` (not `:rf.causa/*`) because the slot is shared.

### `set-show-sensitive!`

- **Signature**:
  ```clojure
  (set-show-sensitive! bool) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: The cross-tool `:rf.privacy/show-sensitive?` flag. When `false` (default), Causa's trace collector drops `:sensitive? true` events and the bottom rail surfaces a `[● REDACTED N]` hint. Set to `true` while debugging redaction policy to see the raw cascade. `nil` resets to the default. Re-exported from `core`.

The single normative emission site for `:sensitive?` redaction is the framework's `elide-wire-value` (see [framework API instrumentation §The wire-boundary walker](../../api/11-instrumentation.md#the-wire-boundary-walker)). Causa's gate just decides whether the redacted-out events reach the buffer at all.

## Settings cluster

The Settings popup carries the user-mutable knobs — theme, density, buffer depths, AI provider config, filter persistence settings. The bulk-set escape hatch lets a host ship its own default Settings shape; the per-knob writes flow through the popup's normal `:rf.causa/settings-update` event.

### `update-setting!`

- **Signature**:
  ```clojure
  (update-setting! path value) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Set one Settings slot. `path` is a vector into the settings map (e.g. `[:general :density]`); `value` is the new value. Persists through localStorage. The popup's event surface is the canonical write path; reach for this only from REPL / test contexts.

### `reset-settings!`

- **Signature**:
  ```clojure
  (reset-settings!) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Reset every Settings slot to its default shape. Wipes the localStorage slot. Mostly a test-isolation helper; hosts that want to ship a non-default shape use `configure! {:rf.causa/settings ...}` instead.

### `reset-suppressed-count!`

- **Signature**:
  ```clojure
  (reset-suppressed-count!) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Clear the `[● REDACTED N]` bottom-rail counter that surfaces when filters elide events. Reach for this when wiring a Settings-popup "Clear suppression counter" button or a test-harness fixture-reset.

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
 :default-frame :app/main
 :sidebar-mode  :grouped / :show-all
 :launcher-pill {:hidden? false}
 :keybindings   {:toggle ["Ctrl+Shift+C"] ...}}
```

Corruption (schema fails) → Causa wipes the slot and writes the default shape, surfacing a one-time toast: "Settings were corrupted and have been reset to defaults."

The settings persist under the localStorage key `day8.re-frame2-causa/settings/v1` (also published as the CLJS constant `day8.re-frame2-causa.config/settings-storage-key`).

## Filters cluster

The Trace panel ships filter pills (`+ pattern`, `- pattern`, `+ :origin`, `+ frame`) that drive in / out filtering of the displayed events. Filter state persists in localStorage across reloads; hosts that want to seed a starting filter set on first install reach for the filters cluster.

### `set-filter-seed!`

- **Signature**:
  ```clojure
  (set-filter-seed! seed-map) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: Host-supplied seed pill set the registry hydrates `:active-filters` with on FIRST install (when localStorage is empty). Shape: `{:in [{...}] :out [{...}]}`. Default `nil` — first session boots with no filters (first-session honesty beats first-session quietness). Story testbeds use this to inject a known starting point for reproducibility.

### `set-filters-storage-key!`

- **Signature**:
  ```clojure
  (set-filters-storage-key! key) → nil
  ```
- **Status**: v1 (dev-only)
- **Description**: The localStorage key the filter persistence layer reads / writes. Default `"re-frame2.causa.filters.v1"`. Hosts that run multiple Causa instances (Story testbeds, multi-mode tool pages) override for isolation between instances.

Set both *before* the preload runs so the first registry-handlers registration reads the right values.

## Boot-time config vs persisted Settings

Causa carries three orthogonal configuration surfaces. The split is principled — each answers a different question — and the merge order is fixed.

| Surface | Where | Lifetime | Examples |
|---|---|---|---|
| **Defaults** | Hardcoded in `config.cljc` | Compile-time constants | Editor `:vscode`, auto-open `true`, layout host `[data-rf-causa-host]` |
| **Boot-time `configure!`** | Host's app boot | Process-global, fixed for session | `(configure! {:rf.causa/editor :cursor})` — flips the editor for this dev session |
| **Persisted Settings** | The Settings popup | User-mutable, localStorage | User picks `:density :comfy` from the popup — sticks across reloads |

**Merge order: defaults < `configure!` < persisted Settings.** A host config knob is the *default* from the user's perspective; the user's Settings overrides win at the per-knob level. `(causa/init! opts)` receives the merged config.

The three answer different questions: `configure!` is the boot-time data knob (set once, don't change at runtime); the in-shell Settings popup is the user-mutable preference layer (user changes density from `:cosy` to `:comfy`, sticks across reloads); per-frame metadata (not in scope here) is the frame-scoped override.

## See also

- [Mount control](mount-control.md) — `open!` / `close!` / `toggle!` / `popout!` and the lifecycle the auto-open setting drives.
- [Runtime seam](runtime-seam.md) — the keybinding `attach!` / `detach!` lifecycle pair the keybinding cluster setters control.
- [Causa tutorial — Installation](../01-installation.md) — the five-minute wiring walkthrough with the recommended host snippet.
- [Framework API — Instrumentation](../../api/11-instrumentation.md#the-wire-boundary-walker) — `elide-wire-value`, the single normative emission site for `:sensitive?` redaction that the privacy cluster gates.
