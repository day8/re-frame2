# Configuration keys

This chapter is about the surfaces that tell Xray *how* to behave — which editor to open source-coords in, where the inline-host element lives in the DOM, whether to auto-open on boot, whether to surface sensitive trace events. The core of it is **one bulk-set entry point** — `configure!` — that takes a single map keyed by namespaced keywords, plus a parallel set of per-key setters for hosts that prefer to flip one knob at a time. The two surfaces are equivalent — `(configure! {:rf.xray/editor :cursor})` is identical to `(set-editor! :cursor)` — and you choose between them by ergonomics.

Boot-time configuration is **process-global**: the setters write to `defonce` atoms inside `config.cljc`, and Xray's own subs / events read those atoms via getters. Hosts usually call `configure!` once at boot, before Xray's preload auto-opens. Settings persisted through the in-shell Settings popup live in a parallel slot in `localStorage`; the relationship between the two is documented at the end of this chapter under [§Boot-time config vs persisted Settings](#boot-time-config-vs-persisted-settings).

## The bulk-set entry point

### `configure!`

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Description**: Top-level Xray configuration. Accepts a map keyed by `:rf.xray/*` keys. Unknown keys are silently ignored (forward-compat — newer hosts passing older-Xray-unaware keys MUST NOT break). Hosts typically call once at boot, before Xray auto-opens.

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

Every key lives under the reserved `:rf.xray/*` namespace — Xray owns its whole `configure!` surface, including its privacy gate. (There is no cross-tool shared slot: on-box sensitive visibility is resolved per `(tool, frame)` pair, so Story reads its own `:rf.story/egress-profile`.) Unknown keys are silently ignored so newer hosts (passing keys an older Xray hasn't shipped yet) don't break, and newer Xray releases shipping additional keys don't break older hosts.

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

Xray's editor preference is **independent** of Story's `:rf.story/editor` (hosts that run both tools can route each to a different editor). The shared URI builder lives at `re-frame.source-coords.editor-uri` in the framework core; Xray's link is a thin wrapper that consumes it.

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

The default selector `[data-rf-xray-host]` is published as a CLJS constant — `day8.re-frame2-xray.config/default-layout-host-selector` — so docs generators and tool chrome can re-emit the canonical spelling without forking the string.

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

A host that wants the listener gone without setting the key, or from a mount hook it does not own, uses the imperative escape hatch instead:

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
- **Description**: Replace Xray's on-box `:rf.egress/*` profile. `:rf.egress/local-redacted` (the default) makes the trace collector drop `:sensitive? true` events before any buffer push and bump the suppressed-events counter, so the shell can surface a `[● REDACTED N]` indicator. `:rf.egress/local-raw` is the trusted-local operator opt-in: every event flows through unchanged. `nil` resets to the default; an unknown keyword is rejected by `configure!` (the enum is closed). Re-exported from `core`.
- **Narrowing is retroactive**: moving from `:rf.egress/local-raw` back to a redacting profile clears the trace buffer, so a reveal is not a one-way trapdoor. Widening and same-class transitions do not clear.

The single normative emission site for `:sensitive?` redaction is the framework's [`project-egress`](../../api/re-frame.core.md#project-egress). Xray's gate just decides whether the redacted-out events reach the buffer at all — the "is this suppressed?" decision derives from the profile's `:rf.egress/include-sensitive?` resolution through the framework projection table, never a re-implemented policy.

## Settings cluster

The Settings popup carries the user-mutable knobs — theme, density, panel position and width, text size, epoch history, trace buffer size, the editor override, and a few display switches. The bulk-set escape hatch lets a host ship its own default Settings shape; the per-knob writes flow through the popup's normal `:rf.xray/settings-update` event.

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

The settings persist under the localStorage key `re-frame2.xray.settings.v2` (also published as the CLJS constant `day8.re-frame2-xray.config/settings-storage-key`).

## Filters cluster

The event list's **+ filter** control adds pattern pills that include (IN) or exclude (OUT) events; [Filters keep the spine useful](../03-time-travel.md#filters-keep-the-spine-useful) describes the patterns. A user's own pills are **transient** — nothing stores them, so a reload starts unfiltered and a stale filter never silently hides rows. Hosts that want a known starting set reach for the filters cluster's seed: an explicit **boot baseline**, re-applied on every load, not durable user-filter persistence.

### `set-filter-seed!`

- **Signature**:
  ```clojure
  (set-filter-seed! seed-map) → nil
  ```
- **Description**: Host-supplied seed pill set applied to `:active-filters` as the explicit **boot baseline**. A non-empty seed lands on **every** load via the first-mount `::seed-configured-filters` hook, which runs *after* the transient-filter reset — so the host's baseline always wins over a user's stale session pills. Xray's IN/OUT pills have **no localStorage layer at all**, so a user's pill edits live and die with the page and the configured baseline is re-derived from `configure!` on every load. Shape: `{:in [{...}] :out [{...}]}`. Default `nil` — a fully-unfiltered first paint (first-session honesty beats first-session quietness). Story testbeds use this to inject a known, reproducible starting posture. To change filters *live* (mid-session), use the filter pill events / Story path — not a post-mount `configure!`, since the seed is read once per frame at first mount, not on every use.

Set the seed *before* the preload runs so the first registry-handlers registration reads the right value.

### `set-filters-auto-hide-error-overrides!`

- **Signature**:
  ```clojure
  (set-filters-auto-hide-error-overrides! bool) → nil
  ```
- **Description**: Whether an errored event stays in the event list when a filter would hide it: an OUT pill matching it, an IN pill not matching it, or a mute. Default `true`, which keeps errors visible; the row's tooltip then reads "⚠ shown because it errored — a filter would normally hide it". `false` lets filters hide errored events too. `nil` resets to the default. The frame picker is a view scope rather than a filter, so an errored event in another frame stays out of the list either way. The `configure!` key is `:rf.xray/filters-auto-hide-error-overrides?`.
## Boot-time config vs persisted Settings

Xray carries three orthogonal configuration surfaces. The split is principled — each answers a different question — and the merge order is fixed.

| Surface | Where | Lifetime | Examples |
|---|---|---|---|
| **Defaults** | Hardcoded in `config.cljc` | Compile-time constants | Editor `:vscode`, auto-open `true`, layout host `[data-rf-xray-host]` |
| **Boot-time `configure!`** | Host's app boot | Process-global, set at boot | `(configure! {:rf.xray/editor :cursor})` — flips the editor for this dev session |
| **Persisted Settings** | The Settings popup | User-mutable, localStorage | User switches `:density` to `:compact` with the palette's *Cycle display density* command — sticks across reloads |

**Merge order: defaults < `configure!` < persisted Settings.** A host config knob is the *default* from the user's perspective; the user's Settings overrides win at the per-knob level. `(xray/init! opts)` is applied after all three, so its `:theme`, `:density` and `:buffer-depths` win, and are written into the persisted Settings as though the user had chosen them.

The three answer different questions: `configure!` is the boot-time data knob; the in-shell Settings popup is the user-mutable preference layer (user changes density from `:cosy` to `:compact`, sticks across reloads); per-frame metadata (not in scope here) is the frame-scoped override.

## See also

- [Mount control](mount-control.md) — `open!` / `close!` / `toggle!` / `popout!` and the lifecycle the auto-open setting drives.
- [Reference](reference.md#day8re-frame2-xraykeybinding) — the keybinding `attach!` / `detach!` lifecycle pair the keybinding cluster setters control.
- [Xray tutorial — Installation](../01-installation.md) — the five-minute wiring walkthrough with the recommended host snippet.
- [Framework API — `project-egress`](../../api/re-frame.core.md#project-egress) — the single normative emission site for `:sensitive?` redaction that the privacy cluster gates.
