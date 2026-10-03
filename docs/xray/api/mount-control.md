# Mount control

These are the functions that put Xray on screen and take it away. There are three ways to open it: `open!` mounts it in the page's layout host, `open-overlay!` floats it over the page, and `popout!` opens it in a second window. `close!`, `toggle!` and `status` control and report visibility, `target-frame` and `set-target-frame!` choose which frame Xray observes, `focus!` points it at an epoch and a tab, and `init!` installs Xray by hand when you do not use the preload.

The preload also installs six of these functions as browser globals under `window.day8.re_frame2_xray.*`, for a devtools console, a JavaScript host, or a browser automation script. [The browser-global JS mirror](#the-browser-global-js-mirror) lists them.

## The three open verbs

### `open!`

- **Signature**:
  ```clojure
  (xray/open!) → mount-state map or missing-host diagnostic map
  ```
- **Description**: Mounts and shows Xray inside the page's layout host (`[data-rf-xray-host]` by default), as part of the page's layout. This is the usual way to open it. The first call creates `#rf-xray-root` inside the host and renders the shell; later calls show the existing shell again. Returns `nil`, and does nothing, when no substrate adapter is installed.

### `open-overlay!`

- **Signature**:
  ```clojure
  (xray/open-overlay!)
  ```
- **Description**: Mounts Xray as a fixed overlay under `<body>`, floating above the page rather than taking part in its layout. Use it when the page has no room for a right-hand column: a full-screen canvas tool, a story-only tool page, a prototype with no layout host.
- **Returns**: the mount-state map, or `nil` when no substrate adapter is installed. A shell already mounted inline moves out to `<body>`.

### `popout!`

- **Signature**:
  ```clojure
  (xray/popout!)
  ```
- **Description**: Opens Xray in a second window on the same origin. The host page renders the shell into the new window's document, through Xray's own React root there. The pop-out shares the host page's runtime and Xray's own frame directly, so the trace stream, epoch history and registrations all work as in the page. If the host page closes or reloads, the pop-out shows a notice asking you to close it. Useful when Xray and the app are competing for screen space.
- **Returns**: `{:ok? true :window … :mode :popout …}` once the window is open, and the same state on a second call while it stays open. On failure it returns `{:ok? false :reason :popup-blocked}` when the browser blocked the window, or `{:ok? false :reason :no-substrate-adapter}`.

There is no `open-inline!`: inline is what `open!` does.

## Visibility control

### `close!`

- **Signature**:
  ```clojure
  (xray/close!)
  ```
- **Description**: Hides the shell (`display: none`) and collapses the layout host, so the app takes back its width. The shell stays mounted, so opening it again is immediate. Use it when the host wants to dismiss the panel from code.

### `toggle!`

- **Signature**:
  ```clojure
  (xray/toggle!)
  ```
- **Description**: Flips visibility. The first call mounts and shows the shell; later calls hide and show it. The `Ctrl+Shift+C` shortcut calls this.

### `status`

- **Signature**:
  ```clojure
  (xray/status) → map
  ```
- **Description**: Returns the shell's state: `{:mounted? :visible? :mode :diagnostic :host-selector :auto-open?}`. Use it from tests, from the console, or for a host's own "is Xray open?" indicator. The browser global `window.day8.re_frame2_xray.status()` returns the same value.

`:diagnostic` says why a launch did not happen. A healthy mount reads `{:ok? true :reason nil}`; otherwise `:reason` is one of four values. The set is stable: a value that stops being produced stays reserved rather than being removed or reused.

| `:reason` | `:ok?` | Meaning |
| --- | --- | --- |
| `:missing-layout-host` | `false` | No element matched the layout-host selector, so nothing mounted. Also logged through `console.error`, with the selector and a host snippet. |
| `:no-substrate-adapter` | `false` | The preload waited about 6 s for a substrate adapter and none arrived: the host never called `rf/init!`. Recorded only while auto-open is on. |
| `:auto-open-disabled` | `true` | Auto-open is switched off (`:rf.xray/auto-open? false`). Health, not failure. |
| `:unsupported-substrate` | — | Reserved and never produced. Xray paints through its own React root, so no installed adapter is refused; the id stays so a consumer keying on it keeps working. |

A failed diagnostic also carries `:selector`, a `:message` saying what to do, and the `:snippet` of host markup to paste. `popout!` reports its failures in its own return value, not here.

## Installing without the preload

The usual way to install Xray is to list `day8.re-frame2-xray.preload` in shadow-cljs's `:devtools :preloads`. On app boot the preload does eight things: loads saved Settings, registers Xray's handlers, the trace collector and the epoch collector, installs the browser globals and the keyboard listener, applies the Settings, and opens Xray in the layout host. All of it sits inside a `(when rf.interop/debug-enabled? …)` block, and all of it is safe to run again when shadow-cljs reloads code.

`init!` is for hosts that want to control when Xray installs: a custom boot sequence with steps between installing the adapter and attaching Xray, a test harness that needs precise ordering, or a host with its own preload bundle.

**`init!` is not behind that `debug-enabled?` block.** If you install Xray this way, keeping it out of your release build is your job: keep the `:require` **and** the calls in a namespace only your dev entry point loads, as the [dev-only namespace sample](#dev-only-install-namespace) below does. Guarding only the calls is not enough; read [Production: what keeps Xray out](#production-what-keeps-xray-out) before shipping.

### `init!`

- **Signature**:
  ```clojure
  (init!) → nil
  (init! opts) → nil
  ```
- **Description**: Installs Xray by hand, as an alternative to the preload. It loads saved Settings and registers Xray's handlers, the trace and epoch collectors, the browser globals and the keyboard listener, then applies `opts`. It does **not** mount the shell: call `open!` (or `toggle!`) when you want it on screen. The install happens once, however many times you call it, but `opts` apply on every call.

The `opts` map accepts these keys, all optional:

```clojure
{:target-frame  :app/main          ;; the inspected HOST frame
 :theme         :dark              ;; or :light
 :density       :compact           ;; or :cosy
 :buffer-depths {:epoch 50}}       ;; epochs kept per frame
```

`:target-frame` is the frame Xray inspects in your app, not Xray's own state frame (`:rf/xray`). Without it, no frame is selected until Xray observes one (see [Frame picker](#frame-picker)); Xray never falls back to `:rf/default`. `:theme`, `:density` and the epoch depth are written into Settings, so they persist like a choice made in the Settings popup.

## Frame picker

Xray observes one frame of your app at a time, and starts with none selected. Two things select one. An explicit choice: `init! {:target-frame …}`, `set-target-frame!`, `focus!`, the frame picker in the ribbon or the command palette, or focusing an event from another frame. Or what Xray observes while nothing is selected: the frame of the app's events when Xray mounts, or the frame that records the first epoch Xray receives. Xray never selects `:rf/default` just because it exists. Hosts with several frames, such as Story or a page with parallel frames, tell Xray which one to observe.

### `target-frame`

- **Signature**:
  ```clojure
  (xray/target-frame) → keyword | nil
  ```
- **Description**: Returns the selected frame, or `nil` when none has been selected. It never defaults to `:rf/default`. It reads the value once and does not re-render anything when it changes; a view that should follow it subscribes to `:rf.xray/target-frame`.

### `set-target-frame!`

- **Signature**:
  ```clojure
  (xray/set-target-frame! frame-id) → nil
  ```
- **Description**: Sets the frame Xray observes. It dispatches `:rf.xray/set-target-frame` into Xray's `:rf/xray` frame, so every tab updates. `(set-target-frame! nil)` clears the selection: the tabs show their no-frame-selected state and the picker asks for a choice.

The frame picker in the ribbon does the same. A host can make the same change from code: from a route effect, its own settings UI, or a test.

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

[The tab ids](reference.md#the-dynamic-tabs) lists each `:panel` value with the tab it opens.

## Runtime theme override

Xray takes its colours from `--rf-xray-*` CSS custom properties, and a host can redefine them at runtime, for example to match an editor's theme.

### `load-theme!`

- **Signature**:
  ```clojure
  (xray/load-theme! css-string) → nil
  ```
- **Description**: Replaces Xray's colours with a CSS string, typically a block redefining the `--rf-xray-*` custom properties. The CSS goes in one dedicated `<style>` element appended **last** to `<head>`, so its rules win over the built-in theme. Calling it again replaces the previous override rather than adding to it, and a `nil` or blank string removes the override. It does nothing where there is no DOM (server render, JVM). Returns `nil`.

## The browser-global JS mirror

The preload installs these functions under `window.day8.re_frame2_xray.*`, so JavaScript hosts, console one-liners and browser automation scripts can call them without compiling ClojureScript. ClojureScript compiles a `!` in a function name to `_BANG_`, so that is how the names are spelled.

```javascript
window.day8.re_frame2_xray.open_BANG_()         // (xray/open!)
window.day8.re_frame2_xray.open_overlay_BANG_() // (xray/open-overlay!)
window.day8.re_frame2_xray.close_BANG_()        // (xray/close!)
window.day8.re_frame2_xray.toggle_BANG_()       // (xray/toggle!)
window.day8.re_frame2_xray.popout_BANG_()       // (xray/popout!)
window.day8.re_frame2_xray.status()             // (xray/status) → map
```

The **preload's** install of these globals sits inside its `(when rf.interop/debug-enabled? …)` block, so an `:advanced` + `goog.DEBUG=false` build folds it away. `init!` installs the same globals with no such gate, so a release build that loads your `init!` call gets `window.day8` — see [Production: what keeps Xray out](#production-what-keeps-xray-out).

Once `core.cljs` has loaded, the same six functions are also reachable under `window.day8.re_frame2_xray.core.*`. Both spellings are supported.

## A manual boot

With the preload wired, the only code your app needs is its ordinary boot. The page also needs the layout host from [Installation](../01-installation.md#reserve-the-host).

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [my.app.views :as views]))

(defonce app-root (reagent-adapter/client-root))   ;; inert until the first render!

(defn ^:export main []
  (rf/init! reagent-adapter/adapter)               ;; install the substrate adapter
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app/main} [views/root]]
    (js/document.getElementById "app")))
```

The preload registers Xray's listeners and opens it in `[data-rf-xray-host]` once `rf/init!` has installed the adapter. Your app code never requires `day8.re-frame2-xray.core` or calls `init!`.

### Dev-only install namespace

A host that wants to decide when Xray opens, such as a Story tool page that suppresses auto-open, uses the functions above. The sample below keeps the preload and only suppresses its automatic open; a host without the preload calls `(xray/init!)` first. Put it in a namespace **only your dev entry point loads**, and keep the `:require` there too:

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

The release build's entry point never requires `my.app.xray-install`, so none of it reaches the release build. Where the namespace is loaded, not a flag, is what keeps Xray out.

## Production: what keeps Xray out

Xray stays out of a release build because the release build does not load it. Three facts matter:

**1. The preload path is dev-only build configuration.** `:devtools :preloads` belongs to the dev build, so a release build never loads `day8.re-frame2-xray.preload`. Its boot block is also wrapped in `(when rf.interop/debug-enabled? …)`, which Closure removes under `:advanced` + `goog.DEBUG=false`, as a second line of defence for that path. The trace collector guards its own entry point the same way. Separately, the framework's own instrumentation is removed under that flag: trace listeners registered with `register-listener!` and the source-coord stamping (`data-rf2-source-coord`) are gone from a `goog.DEBUG=false` build whatever else is in it.

**2. `init!` and the mount functions have no `goog.DEBUG` gate.** `init!` loads Settings and registers Xray's `:rf.xray/*` handlers, the trace and epoch collectors, the browser globals and the keyboard listener unconditionally. `open!` checks only that a substrate adapter is installed, which is true in production exactly as in dev. And requiring `day8.re-frame2-xray.core` at all runs registrations when the namespace loads, so wrapping `(xray/init!)` in `(when ^boolean goog.DEBUG …)` inside a namespace your release build still requires does not help. Guard the `:require`, not just the call — see [Dev-only install namespace](#dev-only-install-namespace) above.

**3. Nothing checks your build for you.** This repository's own elision checks cover the framework's namespaces, not a bundle that installed Xray. To check your own build, search the release output for `rf-xray-root` or `rf.xray`, both of which survive Closure as string literals, and search the dev build too, as [Keep it out of production](../01-installation.md#keep-it-out-of-production) shows, so a zero means something. A zero is evidence that Xray is absent, not proof that no byte of it remains.
