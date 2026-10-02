# Configuration keys

These settings tell Xray how to behave: which editor source links open in, where the layout host is, whether to open on boot, and whether sensitive trace events are shown. `configure!` takes one map of `:rf.xray/*` keys, and each key also has its own setter for a host that prefers to change one at a time. The two are equivalent: `(configure! {:rf.xray/editor :cursor})` does the same as `(set-editor! :cursor)`.

This configuration is global to the page. Hosts usually call `configure!` once at boot, before the preload opens Xray. The choices a developer makes in Xray's Settings popup are stored separately in `localStorage`, and override these; [Boot-time config vs persisted Settings](#boot-time-config-vs-persisted-settings) at the end of this page explains how the two combine.

## The bulk-set entry point

### `configure!`

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Description**: Sets Xray's configuration from a map of `:rf.xray/*` keys. Unknown keys are ignored, so a host written for a newer Xray still works with an older one. Hosts typically call it once at boot, before Xray opens.

`configure!` is re-exported from `core` for boot-time ergonomics. The two require paths are interchangeable:

```clojure
;; via the core facade — already on your require list for open!
(require '[day8.re-frame2-xray.core :as xray])
(xray/configure! {:rf.xray/editor :cursor})

;; via the config namespace — when boot code is routing all knobs through configure!
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor})
```

The full key surface, grouped by topical cluster:

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

   ;; Egress-profile cluster — Xray's on-box privacy gate
   :rf.xray/egress-profile        :rf.egress/local-redacted  ; default — drop :sensitive? true events from the trace buffer

   ;; Settings cluster — the host's default Settings, beneath the user's own choices
   :rf.xray/settings              {:theme :dark :general {:density :cosy}}

   ;; Filters cluster — host-supplied boot-baseline seed (re-applied every load)
   :rf.xray/filters               {:out [{:pattern ":mouse-move"}]}
   :rf.xray/filters-auto-hide-error-overrides? true ; default — an errored event a filter would hide stays visible
   })
```

Every key is in the `:rf.xray/*` namespace, including the privacy setting. Story has its own settings, such as `:rf.story/egress-profile`; setting one tool's key does not change the other's.

## Editor cluster

Every panel that surfaces a source-coord renders it as a link. A click first asks the page's dev server to open the file, by posting to `/__rf-open-in-editor`; when no server answers, Xray hands an editor URI to the OS, built with the scheme of the configured editor. [What a click does](../05-click-to-source.md#what-a-click-does) walks through it.

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

Unknown keywords fall back to `:vscode` so a typo still yields a clickable URI rather than a no-op. Source-coords without `:file` hide the link entirely. A missing `:line` or `:column` becomes 1, and a `{:custom …}` template that produces a `javascript:`, `data:` or `vbscript:` URI yields no link.

Each developer can override the editor on their own machine: the **Click-to-source links open in** picker on Settings' General tab stores its choice in localStorage and wins over `:rf.xray/editor`. Choosing **(project default)** there, or **Reset to project default**, goes back to the host's setting. The override never changes the host's value.

Xray's editor preference is separate from Story's `:rf.story/editor`, so a host running both tools can send each to a different editor.

## Launch cluster

The launch cluster controls the auto-open posture and the layout-host wiring. Set these *before* your app calls `rf/init!`: the preload's auto-open waits for the substrate adapter `rf/init!` installs, so it then reads the right values.

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

The default selector `[data-rf-xray-host]` is also available as the constant `day8.re-frame2-xray.config/default-layout-host-selector`, for tools that need to print or match it.

Five more published constants name the inline-host CSS contract. These are constants (values, not setters) — overriding the CSS custom property happens in the host's stylesheet, not through CLJS.

| Constant | Value | Use |
|---|---|---|
| `default-layout-host-css-var` | `"--rf-xray-inline-width"` | The CSS custom property the recommended host snippet reads for its `flex-basis`. Your stylesheet sets its starting value; when the user drags Xray's left edge, Xray writes the new width to this property and remembers it in Settings. |
| `default-layout-host-width` | `"560px"` | Xray's recommended default value for `--rf-xray-inline-width`. |
| `default-accent-css-var` | `"--rf-xray-accent"` | The CSS custom property the recommended host snippet publishes on `:root` for Xray's brand-accent colour. Host stylesheets read `var(--rf-xray-accent)` to colour their own dev chrome (resize handles, dock separators, story chips). |
| `default-accent` | `"#539bf5"` | Xray's default brand-accent hex (matches `theme/tokens.cljc :accent`, GitHub blue). |
| `default-layout-host-snippet` | HTML+CSS block | A copy-pasteable host snippet carrying the recommended markup, `flex-basis` rule, `:root` accent publish, and `min-width: 320px` floor. Reported back to the user in the missing-host diagnostic so the actionable `console.error` already carries the fix. |

## Keybinding cluster

Xray installs one keydown listener on the document, in the capture phase, for `Ctrl+Shift+C` and its other shortcuts; the preload installs it at boot. Standalone Xray always needs the listener; embed hosts (Story mounts Xray as a right-hand-side panel) sometimes need to take the keys back.

### `set-keybinding-enabled!`

- **Signature**:
  ```clojure
  (set-keybinding-enabled! bool) → nil
  ```
- **Description**: Whether Xray's keydown listener is installed. Default `true` — standalone Xray needs the listener. Embed hosts set `false` so their own global keybindings (typically `Cmd/Ctrl+K` for the host's command palette) are not swallowed by Xray's capture-phase listener. It takes effect whenever it is set: turning it off removes an attached listener, and turning it on attaches one.

A host that wants the listener gone without setting the key, or from a mount hook it does not own, calls `detach!` directly:

```clojure
(require '[day8.re-frame2-xray.keybinding :as xray-keybinding])

;; Take the chord back.
(xray-keybinding/detach!)
```

`detach!` is symmetric and idempotent. See [the symbol table](reference.md#day8re-frame2-xraykeybinding) for the full attach / detach contract.

## Egress-profile cluster (privacy gate)

Xray's on-box sensitive-event gate is a **named egress profile**, resolved per `(tool, frame)` pair. There is no process-global `show-sensitive?` boolean and no cross-tool shared slot — each tool owns its own knob (Xray's `:rf.xray/egress-profile`, Story's `:rf.story/egress-profile`).

### `set-egress-profile!`

- **Signature**:
  ```clojure
  (set-egress-profile! profile) → nil
  ```
- **Description**: Replace Xray's on-box `:rf.egress/*` profile. `:rf.egress/local-redacted` (the default) makes the trace collector drop `:sensitive? true` events before any buffer push and bump the suppressed-events counter, so the shell can surface a `[● REDACTED N]` indicator. `:rf.egress/local-raw` is the trusted-local operator opt-in: every event flows through unchanged. `nil` resets to the default; an unknown keyword raises `:rf.error/unknown-egress-profile` from `configure!`. Re-exported from `core`.
- **Narrowing clears the buffer**: moving from `:rf.egress/local-raw` back to a redacting profile clears the trace buffer, so sensitive events shown while raw do not stay on screen. Widening, or moving to another profile of the same kind, does not clear it.

Redaction itself is the framework's job, done by [`project-egress`](../../api/re-frame.core.md#project-egress). Xray's profile decides only whether events marked sensitive reach Xray's trace buffer at all, and it reads that decision from the framework's own profile definitions.

### Egress profile values

The closed enum accepts all six profiles below. For Xray on your own machine,
choose `local-redacted` or `local-raw`; the others describe export boundaries.

| Value | Intended boundary |
| --- | --- |
| `:rf.egress/local-redacted` | Local inspection with classified sensitive values hidden; Xray's default |
| `:rf.egress/local-raw` | Trusted local inspection including sensitive and large values |
| `:rf.egress/off-box-observability` | Evidence sent to an observability service |
| `:rf.egress/off-box-tool` | Evidence sent to an external tool |
| `:rf.egress/ssr-hydration` | Serialized state used to hydrate a client |
| `:rf.egress/public-error` | Public error payloads |

The frame's classification and the profile determine value projection.
Changing Xray's key does not change another tool's profile.

## Settings cluster

The Settings popup holds the choices each developer makes: theme, density, panel position and width, text size, epoch history, trace buffer size, the editor override, and a few display switches. A host can supply its own defaults for these with `configure! {:rf.xray/settings …}`; the functions below change them from code.

### `update-setting!`

- **Signature**:
  ```clojure
  (update-setting! section key value) → nil
  ```
- **Description**: Set one Settings slot, the `key` under `section` (e.g. `(update-setting! :general :density :compact)`); the theme, which has no section, is `(update-setting! :theme nil :dark)`. Records the value as the user's own choice in localStorage, so it survives a reload, while every slot nobody set keeps following `configure!` and the defaults. An unknown slot is ignored. The popup's event surface is the canonical write path; reach for this only from REPL / test contexts.

### `reset-settings!`

- **Signature**:
  ```clojure
  (reset-settings!) → nil
  ```
- **Description**: Reset every Settings slot to its default. Wipes the localStorage slot and also clears the `:rf.xray/settings` seed a `configure!` call supplied. Mostly a test-isolation helper; hosts that want to ship a non-default shape use `configure! {:rf.xray/settings ...}` instead.

### `reset-suppressed-count!`

- **Signature**:
  ```clojure
  (reset-suppressed-count!) → nil
  (reset-suppressed-count! frame-id) → nil
  ```
- **Description**: Clear the count of sensitive trace events the egress profile has held back, which the ribbon shows as **● REDACTED N**. With a `frame-id`, clears only that frame's count. Reach for this from a test-harness fixture reset.

The Settings shape, with its defaults:

```clojure
{:theme   :light                       ;; or :dark
 :general {:text-size               13
           :panel-position          :right-rail   ;; or :fullscreen
           :panel-width-px          560
           :events-list-height-px   200
           :auto-open-on-error?     false
           :density                 :cosy         ;; or :compact
           :show-unchanged-subs?    false
           :show-ungrouped?         false
           :epoch-history           50
           :long-keyword-threshold  24
           :reduced-motion-override :os
           :use-system-colors?      false
           :event-list-col-widths   {:source 52 :timestamp 76 :duration 60}
           :editor-override         nil}
 :diff    {:highlight-fn-ref-changes? false}
 :buffer  {:events-retained 50}}
```

The enum-valued settings are:

| Slot | Values | Meaning |
| --- | --- | --- |
| `:theme` | `:light`, `:dark` | Shell theme |
| `[:general :panel-position]` | `:right-rail`, `:fullscreen` | Inline host or page overlay; pop-out is an action, not a position value |
| `[:general :density]` | `:cosy`, `:compact` | Spacing; changed through the command palette or host config |
| `[:general :reduced-motion-override]` | `:os`, `:always`, `:never` | Follow the OS, force reduced motion, or force full motion |
| `[:general :editor-override]` | `nil`, `:vscode`, `:cursor`, `:windsurf`, `:zed`, `:idea`, or `{:custom "..."}` | Local editor choice; `nil` uses the project default |

General's **Epoch history** slider accepts 5–200 in steps of 5. Buffer's
**Events retained** input has a minimum of 1. The layout is resized by dragging;
there is no width or density input in General settings. The defaults map also
contains host-configurable display slots that the popup does not expose.

The settings persist under the localStorage key `re-frame2.xray.settings.v2` (also published as the CLJS constant `day8.re-frame2-xray.config/settings-storage-key`).

## Filters cluster

The event list's **+ filter** control adds pattern pills that include (IN) or exclude (OUT) events; [Filters](../manage-evidence.md#filters) in the session guide describes the patterns. Nothing stores a developer's own pills, so a reload starts unfiltered. A host that wants every load to start from a known set of filters supplies a seed.

### `set-filter-seed!`

- **Signature**:
  ```clojure
  (set-filter-seed! seed-map) → nil
  ```
- **Description**: Sets the filter pills Xray starts with on every page load. Pills a developer adds are never stored, so each load starts from this set and a stale filter from an earlier session cannot hide events. Shape: `{:in [{...}] :out [{...}]}`. Default `nil`: no filters, so every event is visible. Story testbeds use it to start from a known, reproducible set. Xray reads the seed once per frame, when it first mounts, so calling `configure!` later does not change the pills already showing; to change filters mid-session, use the filter pills.

Set the seed before Xray first mounts, at boot beside your other `configure!` keys.

### `set-filters-auto-hide-error-overrides!`

- **Signature**:
  ```clojure
  (set-filters-auto-hide-error-overrides! bool) → nil
  ```
- **Description**: Whether an errored event stays in the event list when a filter would hide it: an OUT pill matching it, an IN pill not matching it, or a mute. Default `true`, which keeps errors visible; the row's tooltip then reads "⚠ shown because it errored — a filter would normally hide it". `false` lets filters hide errored events too. `nil` resets to the default. The frame picker is a view scope rather than a filter, so an errored event in another frame stays out of the list either way. The `configure!` key is `:rf.xray/filters-auto-hide-error-overrides?`.

## Boot-time config vs persisted Settings

A setting's value comes from one of three places, and they combine in a fixed order.

| Surface | Where | Lifetime | Examples |
|---|---|---|---|
| **Defaults** | Hardcoded in `config.cljc` | Compile-time constants | Editor `:vscode`, auto-open `true`, layout host `[data-rf-xray-host]` |
| **Boot-time `configure!`** | Host's app boot | Process-global, set at boot | `(configure! {:rf.xray/editor :cursor})` — flips the editor for this dev session |
| **Persisted Settings** | The Settings popup | User-mutable, localStorage | User switches `:density` to `:compact` with the palette's *Cycle display density* command — sticks across reloads |

**Merge order: defaults < `configure!` < persisted Settings.** What the host sets with `configure!` is the default a developer starts from; a choice the developer makes in Settings wins, setting by setting. `(xray/init! opts)` is applied after all three, so its `:theme`, `:density` and `:buffer-depths` win, and are written into the persisted Settings as though the developer had chosen them.
