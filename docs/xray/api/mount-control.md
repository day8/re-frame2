# Mount control

This chapter is about the surfaces that bring Xray's shell into view and take it away again. The core of it is **three distinct mount verbs** — `open!`, `open-overlay!`, `popout!` — that name three distinct surfaces, not modal variants of one shape. `open!` is the default; you reach for the other two when the host's layout can't accommodate the inline column, or when the user wants the panel in its own window. Around the open verbs sit `close!`, `toggle!`, and `status` for state inspection, plus a frame picker (`target-frame` / `set-target-frame!`) for telling Xray which host frame to observe, plus `init!` for the manual-install path that bypasses shadow-cljs preloads.

There's also a small JS-side mirror — the same six verbs exposed on `window.day8.re_frame2_xray.*` so a devtools console, a JS host that doesn't `:require` CLJS, or a `puppeteer` script can reach the same shell. The **preload's** install of that mirror sits inside its `(when rf.interop/debug-enabled? …)` block, so an `:advanced` + `goog.DEBUG=false` build folds it away; `init!` installs the same globals with no such gate — see [Production: what keeps Xray out](#production-what-keeps-xray-out).

## The three open verbs

### `open!`

- **Signature**:
  ```clojure
  (xray/open!) → mount-state map or missing-host diagnostic map
  ```
- **Description**: Mount + show the shell true-inline into the host's normal-flow layout host (`[data-rf-xray-host]` by default). The canonical default. On first call, creates `#rf-xray-root` inside the host and renders the shell. On subsequent calls (already mounted), flips the container to `display: block`. No-op (returns `nil`) when no substrate adapter is installed.

### `open-overlay!`

- **Signature**:
  ```clojure
  (xray/open-overlay!)
  ```
- **Description**: Debug / fallback path: mount Xray as a fixed overlay under `<body>`. Floats above the host layout without participating in it. Reach for this when the host's normal-flow layout cannot accommodate a right column — a full-screen canvas tool, a story-only tool page, a prototype with no layout host.
- **Returns**: the mount-state map, or `nil` when no substrate adapter is installed. A shell already mounted inline moves out to `<body>`.

### `popout!`

- **Signature**:
  ```clojure
  (xray/popout!)
  ```
- **Description**: Open Xray in a same-origin second window. The host page renders the shell into the new window's document, through Xray's own React root there. The pop-out shares the host page's runtime and Xray frame, with no serialisation between them, so all observation surfaces (trace bus, epoch history, registrar) work unchanged. If the host page closes or reloads, the pop-out shows a notice asking you to close it. Useful when the panel is competing with the app for screen space.
- **Returns**: `{:ok? true :window … :mode :popout …}` once the window is open, and the same state on a second call while it stays open. On failure it returns `{:ok? false :reason :popup-blocked}` when the browser blocked the window, or `{:ok? false :reason :no-substrate-adapter}`.

The three verbs are **not** a mode-symmetric triplet — there is no `open-inline!` alias. Inline-vs-overlay-vs-window is a kind-of-mount axis, not a mode axis. Bare `open!` *is* the canonical default; `open-overlay!` and `popout!` each name their own surface.

## Visibility control

### `close!`

- **Signature**:
  ```clojure
  (xray/close!)
  ```
- **Description**: Hide the shell — flip the container to `display: none`, and collapse the layout host too, so the app takes back its width. The DOM tree and substrate render tree stay in place so re-opening is a CSS-only toggle (sub-80ms first paint). Use when the host wants to programmatically dismiss the panel without unmounting it.

### `toggle!`

- **Signature**:
  ```clojure
  (xray/toggle!)
  ```
- **Description**: Flip visibility. First call mounts + shows; subsequent calls toggle between `display: block` and `display: none`. The `Ctrl+Shift+C` global keybinding is wired to this.

### `status`

- **Signature**:
  ```clojure
  (xray/status) → map
  ```
- **Description**: Inspectable shell state. Returns `{:mounted? :visible? :mode :diagnostic :host-selector :auto-open?}`. Reach for this from tests, from a debug-console one-liner, or when wiring a host's "is the panel up?" indicator. The browser-global mirror exposes the same value as `window.day8.re_frame2_xray.status()`.

`:diagnostic` is where a launch that did not happen says why. A healthy mount reads `{:ok? true :reason nil}`; otherwise `:reason` is one of four public values, and the set is a contract — a member is retired by reservation, never deleted or reused:

| `:reason` | `:ok?` | Meaning |
| --- | --- | --- |
| `:missing-layout-host` | `false` | No element matched the layout-host selector, so nothing mounted. Also logged through `console.error`, with the selector and a host snippet. |
| `:no-substrate-adapter` | `false` | The preload waited about 6 s for a substrate adapter and none arrived: the host never called `rf/init!`. Recorded only while auto-open is on. |
| `:auto-open-disabled` | `true` | Auto-open is switched off (`:rf.xray/auto-open? false`). Health, not failure. |
| `:unsupported-substrate` | — | Reserved and never produced. Xray paints through its own React root, so no installed adapter is refused; the id stays so a consumer keying on it keeps working. |

A failed diagnostic also carries `:selector`, a `:message` saying what to do, and the `:snippet` of host markup to paste. `popout!` reports its failures in its own return value, not here.

## Manual install — the alternative to `:preloads`

The canonical install path is wiring `day8.re-frame2-xray.preload` into shadow-cljs's `:devtools/preloads`. The preload runs eight side-effects on app boot (load saved Settings, register Xray's handlers, the trace collector, the epoch collector, the browser globals, the keybinding listener, apply the Settings, and auto-open into the inline host), all inside a `(when rf.interop/debug-enabled? …)` block and all idempotent so shadow-cljs's `:after-load` cycle re-runs them safely.

For hosts that want to control the install timing — a custom boot pipeline with steps between adapter install and Xray attach, a test harness needing fine-grained sequencing, a host that ships its own preload bundle — `init!` is the alternative.

**`init!` is not behind that gate.** If you take this path, keeping Xray out of your release build is your job, and the way to discharge it is placement: keep the `:require` **and** the calls in a namespace only your dev entry point loads, exactly as the [dev-only namespace sample](#dev-only-install-namespace) below does. Guarding only the calls is not enough — read [Production: what keeps Xray out](#production-what-keeps-xray-out) before shipping.

### `init!`

- **Signature**:
  ```clojure
  (init!) → nil
  (init! opts) → nil
  ```
- **Description**: Install Xray manually, the alternative to the preload. It loads saved Settings and registers Xray's handlers, the trace and epoch collectors, the browser globals and the keybinding listener, then applies `opts`. It does **not** mount the shell: call `open!` (or `toggle!`) when you want it on screen. The install happens once, however many times you call it, but `opts` apply on every call. Use when the `:preloads` wiring isn't available — test harnesses, host-controlled boot pipelines, dev builds with a custom preload bundle.

The `opts` map accepts these keys, all optional:

```clojure
{:target-frame  :app/main          ;; the inspected HOST frame
 :theme         :dark              ;; or :light
 :density       :compact           ;; or :cosy
 :buffer-depths {:epoch 50}}       ;; epochs kept per frame
```

`:target-frame` is the **inspected host frame**, distinct from Xray's **own** state frame (`:rf/xray`). Omitting it leaves the target unselected until Xray observes one (see [Frame picker](#frame-picker)); Xray never falls back to `:rf/default`. `:theme`, `:density` and the epoch depth are written into Settings, so they persist like a choice made in the Settings popup.

## Frame picker

Xray observes one host frame at a time, and the target starts unselected. Two things select it. An explicit choice: `init! {:target-frame …}`, `set-target-frame!`, `focus!`, the frame picker in the ribbon or the command palette, or focusing an event from another frame. Or evidence Xray observes while the target is still unselected: the frame of the app's events when Xray mounts, or the frame that records the first epoch Xray receives. Xray never selects `:rf/default` just because it exists. Multi-frame hosts — Story, parallel-frames testbeds, story-mode chrome wrapping a tool surface — tell Xray which frame to observe.

### `target-frame`

- **Signature**:
  ```clojure
  (xray/target-frame) → keyword | nil
  ```
- **Description**: Read the currently-selected inspected-host frame, or `nil` when no target has been selected yet. It is **not** defaulted to `:rf/default` — `:rf/default` is an ordinary id, never an Xray fallback. One-shot read (does NOT register for reactive re-render). Reactive consumers subscribe to `:rf.xray/target-frame` directly via the framework's sub surface.

### `set-target-frame!`

- **Signature**:
  ```clojure
  (xray/set-target-frame! frame-id) → nil
  ```
- **Description**: Set the inspected-host frame Xray targets. Dispatches `:rf.xray/set-target-frame` into the `:rf/xray` frame so the sub and every dependent panel re-fire on the standard reactive path. `set-target-frame! nil` resets to the **unselected** state (panels render their no-frame-selected state and the picker prompts a choice) — it does not reset *through* a synthesised `:rf/default`.

The frame picker in the ribbon does the same: choosing a frame there rescopes the event list and every panel to it. Hosts can drive the same flip programmatically from a per-route effect, a Settings-popup wire-up, or a test harness assertion.

## Focusing a panel from a host

A host that embeds Xray can point it at a frame, an epoch and a tab in one call. Story's Evidence links use this to open Xray on the epoch behind a beat.

### `focus!`

- **Signature**:
  ```clojure
  (xray/focus! command) → result map
  (xray/focus! host-frame command) → result map
  ```
- **Description**: Focuses Xray as the command says. In the two-argument form, `host-frame` fills `:frame` unless the command names one.
- **Returns**: `{:ok? true :applied [<the Xray events it dispatched>] :source …}`. An unknown `:panel` changes nothing and returns `{:ok? false :reason :unknown-panel :given … :valid … :hint …}`.

Every key of the command is optional, and other keys are ignored:

| Key | Does |
| --- | --- |
| `:frame` | selects the frame Xray observes |
| `:panel` | selects the tab, one of `valid-focus-panels`; `:routes` is accepted for `:routing` |
| `:epoch-id` | focuses that epoch |
| `:dispatch-id` | focuses the event with that dispatch id; it wins over `:epoch-id` |
| `:source` | anything you like, returned untouched in the result, for your own diagnostics |

The frame is selected first, then the epoch, then the tab.

```clojure
(xray/focus! {:frame :app/main :panel :app-db :epoch-id epoch-id})
```

## Runtime theme override

The shell reads its palette from `--rf-xray-*` CSS custom properties, and a host can re-declare them at runtime — editor-driven palette sync is the motivating case.

### `load-theme!`

- **Signature**:
  ```clojure
  (xray/load-theme! css-string) → nil
  ```
- **Description**: Swap the Xray shell's palette by handing in a CSS string — typically a block re-declaring the `--rf-xray-*` custom properties the shell reads. The CSS rides in a single dedicated `<style>` block appended **last** to `<head>`, so its rules win on authoring order against the built-in per-theme block. Idempotent: successive calls **replace** the override in place rather than stacking it, and a `nil` or blank string clears the override and restores the built-in palette. A safe no-op where there is no DOM (server render, JVM). Returns `nil`.

## The browser-global JS mirror

The preload installs a JS-side mirror under `window.day8.re_frame2_xray.*` so JS hosts, devtools-console one-liners, and `puppeteer` automation scripts can reach the same surfaces without a CLJS compile. The exact spellings carry the `_BANG_` suffix that ClojureScript's name munging gives a `!` in a function name.

```javascript
window.day8.re_frame2_xray.open_BANG_()         // (xray/open!)
window.day8.re_frame2_xray.open_overlay_BANG_() // (xray/open-overlay!)
window.day8.re_frame2_xray.close_BANG_()        // (xray/close!)
window.day8.re_frame2_xray.toggle_BANG_()       // (xray/toggle!)
window.day8.re_frame2_xray.popout_BANG_()       // (xray/popout!)
window.day8.re_frame2_xray.status()             // (xray/status) → map
```

The **preload's** install of the mirror sits inside its `(when rf.interop/debug-enabled? …)` block, so an `:advanced` + `goog.DEBUG=false` build folds that install away. `init!` installs the same globals with no such gate, so a release build that loads your `init!` call gets `window.day8` — see [Production: what keeps Xray out](#production-what-keeps-xray-out).

Once `core.cljs` has loaded, the same six fns are also reachable under `window.day8.re_frame2_xray.core.*` so JS-console users see the canonical facade names. Both spellings are stable contracts.

## The mount lifecycle, end-to-end

A complete boot, from preload-wiring through to the first scrubber tick:

```clojure
;; shadow-cljs.edn — dev build only
{:builds
 {:app
  {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

```html
<!-- index.html -->
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
.app-shell { display: flex; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
}
```

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent]
            [reagent.dom :as rdom]
            [my.app.views :as views]))

(defn ^:export main []
  (rf/init! reagent/adapter)             ;; install the substrate adapter
  (rdom/render [views/root]
               (js/document.getElementById "app")))
```

That's the full boot. The preload registers Xray's listeners and auto-opens into `[data-rf-xray-host]` once `rf/init!` has installed the substrate. No `(require '[day8.re-frame2-xray.core])`, no `init!` call. The preload plus the host element are the integration surface.

### Dev-only install namespace

Hosts that want explicit control — a Story tool page that suppresses auto-open, an embed host that needs to bypass the preload — reach for the imperative facade above. The sample below keeps the preload and only suppresses its auto-open; a host without the preload calls `(xray/init!)` first. Put it in a namespace **only your dev entry point loads**, and keep the `:require` there too:

```clojure
;; src-dev/my/app/xray_install.cljs — on the dev build's source path only.
(ns my.app.xray-install
  (:require [day8.re-frame2-xray.core :as xray]
            [day8.re-frame2-xray.config :as xray-config]))

;; Suppress the preload's auto-open path.
(xray-config/configure! {:rf.xray/auto-open? false})

;; Later — from a button, a route handler, a test harness:
(xray/open!)
(xray/set-target-frame! :app/tool-canvas)
```

The release build's entry point never requires `my.app.xray-install`, so nothing above ever reaches it. That placement — not a flag — is what keeps Xray out.

## Production: what keeps Xray out

**Build placement, not construction.** Xray stays out of a release build because the host doesn't load it. There are three facts, and it is worth having all three:

**1. The preload path is dev-only build configuration.** `:devtools/preloads` belongs to the dev build, so a release build never loads `day8.re-frame2-xray.preload`. Its boot block is additionally wrapped in `(when rf.interop/debug-enabled? …)`, which Closure folds away under `:advanced` + `goog.DEBUG=false` — a second line of defence for that path. The trace collector also gates its own entry point the same way. Separately, the framework's own instrumentation elides under that flag: the trace bus's `register-listener!` registrations and source-coord stamping (`data-rf2-source-coord`) are gone from a `goog.DEBUG=false` build whatever else is in it.

**2. `init!` and the mount verbs carry no `goog.DEBUG` gate.** `init!` loads Settings and registers Xray's `:rf.xray/*` handlers, the trace and epoch collectors, the browser-global exports and the keybinding listener unconditionally. `open!` gates only on a substrate adapter being installed — which every app that called `rf/init!` has, in production exactly as in dev; adapter presence is not a production discriminator. And requiring `day8.re-frame2-xray.core` at all runs load-time registrations, so wrapping `(xray/init!)` in `(when ^boolean goog.DEBUG …)` inside a namespace your release build still requires does not help. Guard the `:require`, not just the call — see [Dev-only install namespace](#dev-only-install-namespace) above.

**3. No CI gate proves Xray's absence from a release bundle.** `npm run test:elision` compiles `re-frame.elision-probe` under `:advanced` twice and greps sentinels drawn from `re-frame.*` namespaces; it roots no Xray namespace, so a green run attests the *framework's* elision and says nothing about whether Xray reached your bundle. [`implementation/scripts/check-bundle-isolation.cjs`](https://github.com/day8/re-frame2/blob/main/implementation/scripts/check-bundle-isolation.cjs) pins that the counter example's *no-feature* production bundle carries no tooling-sibling or Xray-only-dependency sentinels — a leak check on a bundle that never installed Xray. If you want certainty about your own build, grep your release output for `rf-xray-root` or `rf.xray`; both survive Closure as string literals. That is a leak detector, not proof of zero retained bytes.

## See also

- [Configuration keys](config-keys.md) — `configure!` and the per-key setters that flip the auto-open posture, the inline-host selector, the editor preference, the privacy gate.
- [Reference](reference.md) — the complete symbol table across every Xray namespace.
- [Xray tutorial — Installation](../01-installation.md) — the five-minute, three-edits walk-through.
- [Framework API — Lifecycle](../../api/re-frame.core.md#lifecycle-and-configure) — `rf/init!` and the adapter install pair. The adapter must land before Xray's auto-open path resolves the host.
- [Framework API — Instrumentation](../../api/re-frame.core.md#instrumentation-and-listeners) — the trace bus and epoch buffer Xray renders.
