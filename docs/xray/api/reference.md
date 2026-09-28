# Reference

Every public Xray function and constant, one row each, grouped by namespace. `core` holds the functions most hosts call, `config` the full configuration surface, and `keybinding` the pair that attaches and detaches Xray's keyboard listener. [Mount control](mount-control.md) and [Configuration keys](config-keys.md) explain the same functions with examples.

## `day8.re-frame2-xray.core`

The namespace most hosts require: opening and closing Xray, choosing the frame, `focus!`, the theme override, and the most-used configuration setters.

| Symbol | Signature | Use |
| --- | --- | --- |
| `init!` | `(init!)` / `(init! opts)` → nil | Manual install — the alternative to wiring `:preloads`. Installs once, applies `opts` on every call, and does not mount: call `open!` next. See [Mount control](mount-control.md#init). |
| `open!` | `(open!)` → mount-state map or missing-host diagnostic | Mount + show the shell true-inline into the host's layout host. The canonical default. |
| `open-overlay!` | `(open-overlay!)` → mount-state map or nil | Mount as a fixed overlay under `<body>`. Floats above host layout. `nil` when no substrate adapter is installed. |
| `close!` | `(close!)` | Hide the shell — flip the container to `display: none` and collapse the layout host. DOM stays in place. |
| `toggle!` | `(toggle!)` | Flip visibility. Wired to `Ctrl+Shift+C`. |
| `popout!` | `(popout!)` → state map | Open Xray in a same-origin second window, through its own React root there. `{:ok? false :reason …}` when the popup is blocked or no substrate adapter is installed. |
| `status` | `(status)` → map | Inspectable shell state. `{:mounted? :visible? :mode :diagnostic ...}`; `:diagnostic :reason` names why a launch failed — see [Mount control](mount-control.md#status). |
| `target-frame` | `(target-frame)` → keyword \| nil | Read the currently-selected inspected-host frame, or `nil` when none is selected (never defaulted to `:rf/default`). One-shot read; not reactive. |
| `set-target-frame!` | `(set-target-frame! frame-id)` → nil | Set the inspected-host frame Xray targets. `nil` resets to the **unselected** state (not `:rf/default`). |
| `focus!` | `(focus! command)` / `(focus! host-frame command)` → map | Host-facing focus handoff. Story and other hosts use it to select a frame, an epoch and a tab in one call. See [Focusing a panel from a host](mount-control.md#focusing-a-panel-from-a-host). |
| `valid-focus-panels` | set value | The tab ids `focus!` accepts as `:panel`, one per Dynamic tab: `#{:epoch :app-db :views :trace :machines :routing :resources :derivation-graph :module-view :fresco}`. [The Dynamic tabs](#the-dynamic-tabs) maps each to its label. |
| `load-theme!` | `(load-theme! css-string)` → nil | Programmatic theme override. Installs or replaces a host CSS block; `nil` or blank clears the override. |
| `configure!` | `(configure! opts)` → nil | Top-level config — re-exported from `config`. See [Configuration keys](config-keys.md). |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | Re-exported from `config`. Whether the preload auto-opens. |
| `set-editor!` | `(set-editor! editor)` → nil | Re-exported from `config`. Sets the "Open in editor" preference. |
| `set-egress-profile!` | `(set-egress-profile! profile)` → nil | Re-exported from `config`. Xray's on-box `:rf.xray/egress-profile` privacy gate. |

## `day8.re-frame2-xray.config`

The full configuration surface: `configure!`, a setter per key, and constants naming the defaults that tools and stylesheets may need.

### Setters

| Symbol | Signature | Use |
| --- | --- | --- |
| `configure!` | `(configure! opts)` → nil | Top-level config. Map keyed by `:rf.xray/*`. |
| `set-editor!` | `(set-editor! editor)` → nil | Editor preference. `:vscode` (default) / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <tpl>}`. |
| `set-project-root!` | `(set-project-root! path)` → nil | On-disk root prepended to classpath-relative `:file` slots before editor URIs ship. |
| `set-layout-host-selector!` | `(set-layout-host-selector! css-selector)` → nil | CSS selector for the auto-open path. Default `[data-rf-xray-host]`. |
| `set-auto-open!` | `(set-auto-open! bool)` → nil | Whether the preload auto-opens on adapter readiness. Default `true`. |
| `set-keybinding-enabled!` | `(set-keybinding-enabled! bool)` → nil | Whether `keybinding/attach!` installs the global listener. Default `true`. |
| `set-egress-profile!` | `(set-egress-profile! profile)` → nil | Xray's on-box `:rf.egress/*` privacy gate, per `(tool, frame)`. Default `:rf.egress/local-redacted`; `:rf.egress/local-raw` is the trusted-local opt-in. Narrowing back clears the trace buffer. |
| `set-filters-auto-hide-error-overrides!` | `(set-filters-auto-hide-error-overrides! bool)` → nil | Whether an errored event stays listed when a filter would hide it. Default `true`. |
| `set-filter-seed!` | `(set-filter-seed! seed-map)` → nil | The filter pills every page load starts with. Shape: `{:in [{...}] :out [{...}]}`. |
| `update-setting!` | `(update-setting! section key value)` → nil | Set one Settings slot and record it as the user's choice. The theme is `(update-setting! :theme nil kw)`. |
| `reset-settings!` | `(reset-settings!)` → nil | Reset every Settings slot to its default. Wipes the localStorage slot and the `configure!` settings seed. |
| `reset-suppressed-count!` | `(reset-suppressed-count!)` / `(reset-suppressed-count! frame-id)` → nil | Clear the count of sensitive trace events the egress profile held back, for every frame or one. |

### Published constants

| Symbol | Value | Use |
|---|---|---|
| `default-layout-host-selector` | `"[data-rf-xray-host]"` | The default CSS selector. Re-emit in docs generators / diagnostics. |
| `default-layout-host-css-var` | `"--rf-xray-inline-width"` | The CSS custom property the host snippet reads for `flex-basis`. |
| `default-layout-host-width` | `"560px"` | The default value Xray recommends for `--rf-xray-inline-width`. |
| `default-accent-css-var` | `"--rf-xray-accent"` | The CSS custom property the host snippet publishes on `:root`. |
| `default-accent` | `"#539bf5"` | The default brand-accent hex (matches `theme/tokens.cljc :accent`). |
| `default-layout-host-snippet` | HTML + CSS block | Copy-pasteable host snippet. Carried in the missing-host diagnostic. |
| `settings-storage-key` | `"re-frame2.xray.settings.v2"` | localStorage key for the Settings popup state. |

## `day8.re-frame2-xray.keybinding`

The lifecycle pair for Xray's document keydown listener, which handles `Ctrl+Shift+C` and Xray's other shortcuts. Reach here from embed hosts that need to take the keys back after Xray has already attached.

| Symbol | Signature | Use |
| --- | --- | --- |
| `attach!` | `(attach!)` → nil | Install the listener once, in the capture phase. Does nothing while `:rf.xray/keybinding-enabled?` is `false`, or when already attached. |
| `detach!` | `(detach!)` → nil | Remove the global listener. Idempotent. Symmetric with `attach!`. |

## `day8.re-frame2-xray.preload`

The dev-only side-effect bundle. You don't call anything here directly — you list the namespace in shadow-cljs's `:devtools/preloads` and the rest happens. The bundle runs eight side-effects on load:

1. Load the saved Settings from localStorage.
2. Register Xray's `:rf.xray/*` subs / events / fxs.
3. Register the trace collector as a `:rf.xray/trace-collector` listener.
4. Register the epoch-settle pump as a `:rf.xray/epoch-collector` listener.
5. Install the browser API on `window.day8.re_frame2_xray.*`.
6. Attach the document keydown listener for `Ctrl+Shift+C` and Xray's other shortcuts.
7. Apply the Settings: theme, density, panel width and the rest.
8. Auto-open the shell true-inline into the host's layout host once the substrate adapter is ready.

All eight sit inside the preload's `(when rf.interop/debug-enabled? …)` block, so Closure folds them away under `:advanced` + `goog.DEBUG=false`, and all are idempotent so shadow-cljs's `:after-load` cycle re-runs without double-registration. That block gates the **preload** path only — `init!` runs the first seven with no `goog.DEBUG` gate, and leaves opening to `open!`, and keeping that call out of a release build is build placement (see [Mount control §Production: what keeps Xray out](mount-control.md#production-what-keeps-xray-out)).

## `window.day8.re_frame2_xray.*` (browser-global JS mirror)

The preload installs a JS-side mirror so JS hosts, devtools-console one-liners, and `puppeteer` automation scripts can reach Xray's surfaces without a CLJS compile. The names are CLJS-munged, so a `!` in a function name becomes `_BANG_`.

| JS spelling | CLJS equivalent | Use |
|---|---|---|
| `window.day8.re_frame2_xray.open_BANG_()` | `(xray/open!)` | Mount + show the shell. |
| `window.day8.re_frame2_xray.open_overlay_BANG_()` | `(xray/open-overlay!)` | Mount as overlay. |
| `window.day8.re_frame2_xray.close_BANG_()` | `(xray/close!)` | Hide. |
| `window.day8.re_frame2_xray.toggle_BANG_()` | `(xray/toggle!)` | Flip visibility. |
| `window.day8.re_frame2_xray.popout_BANG_()` | `(xray/popout!)` | Pop out into a new window. |
| `window.day8.re_frame2_xray.status()` | `(xray/status)` | Inspectable status map. |

Once `core.cljs` has loaded, the same six fns are reachable under `window.day8.re_frame2_xray.core.*` so JS-console users see the canonical facade names. Both spellings are stable contracts.

## The Dynamic tabs

`valid-focus-panels` holds one id per Dynamic tab. Pass it as `focus!`'s `:panel` to open that tab. The id is not always the tab's label:

| Tab | `:panel` |
| --- | --- |
| Epoch | `:epoch` |
| app-db | `:app-db` |
| Views | `:views` |
| Trace | `:trace` |
| Machine | `:machines` |
| Routes | `:routing` (`:routes` is also accepted) |
| Resources | `:resources` |
| Graph | `:derivation-graph` |
| Frames | `:module-view` |
| Fresco | `:fresco` |

The panels themselves live in `day8.re-frame2-xray.panels.*` and `day8.re-frame2-xray.static.*`. Xray's shell composes them; they are not part of the host API.

## What this reference deliberately omits

Several functions and vars are reachable in the ClojureScript source but are internal to Xray, so this reference leaves them out:

- **The atoms behind `config`.** Each setter writes to an atom in `day8.re-frame2-xray.config` (`auto-open?`, `editor`, `keybinding-enabled?`, …). Use the setters; the atoms are internal.
- **The `mount-<panel>!` functions** in `day8.re-frame2-xray.panels`. They mount a single panel, and Story's right-rail inspector is their one consumer. They take hiccup, so they mount only under a Reagent-style adapter. A host that wants Xray on its page uses `open!`, `open-overlay!` or `popout!`.
- **Helpers Xray's own code uses**: `suppress-sensitive?`, `note-suppressed!`, `clamp-panel-width-px` and `editor-uri`. To test whether a value is marked sensitive, call the framework's `rf/sensitive?`.
- **`register-toggle-off-callback!` / `unregister-toggle-off-callback!`.** Xray's own modules register hooks here to clear their buffers. Host applications should not.

These may be renamed or made private in any release.
