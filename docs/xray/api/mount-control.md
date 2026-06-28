# Mount control

This chapter is about the surfaces that bring Xray's shell into view and take it away again. The core of it is **three distinct mount verbs** — `open!`, `open-overlay!`, `popout!` — that name three distinct surfaces, not modal variants of one shape. `open!` is the default; you reach for the other two when the host's layout can't accommodate the inline column, or when the user wants the panel in its own window. Around the open verbs sit `close!`, `toggle!`, and `status` for state inspection, plus a frame picker (`target-frame` / `set-target-frame!`) for telling Xray which host frame to observe, plus `init!` for the manual-install path that bypasses shadow-cljs preloads.

There's also a small JS-side mirror — the same six verbs exposed on `window.day8.re_frame2_xray.*` so a devtools console, a JS host that doesn't `:require` CLJS, or a `puppeteer` script can reach the same shell. Production builds elide the install entirely via the `:advanced` + `goog.DEBUG=false` gate; you never have to scrub the global manually.

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

### `popout!`

- **Signature**:
  ```clojure
  (xray/popout!)
  ```
- **Description**: Open Xray in a same-origin second window. The shell mounts into its own document context — own React root, own theme cascade, own keybinding listener. The popped window uses `window.opener` to reach the host's runtime, so all observation surfaces (trace bus, epoch history, registrar) work unchanged. Useful when the panel is competing with the app for screen space.

The three verbs are **not** a mode-symmetric triplet — there is no `open-inline!` alias. Inline-vs-overlay-vs-window is a kind-of-mount axis, not a mode axis. Bare `open!` *is* the canonical default; `open-overlay!` and `popout!` each name their own surface.

## Visibility control

### `close!`

- **Signature**:
  ```clojure
  (xray/close!)
  ```
- **Description**: Hide the shell — flip the container to `display: none`. The DOM tree and substrate render tree stay in place so re-opening is a CSS-only toggle (sub-80ms first paint). Use when the host wants to programmatically dismiss the panel without unmounting it.

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
- **Description**: Inspectable shell state. Returns `{:mounted? :visible? :last-host-diagnostic ...}`. Reach for this from tests, from a debug-console one-liner, or when wiring a host's "is the panel up?" indicator. The browser-global mirror exposes the same value as `window.day8.re_frame2_xray.status()`.

## Manual install — the alternative to `:preloads`

The canonical install path is wiring `day8.re-frame2-xray.preload` into shadow-cljs's `:devtools/preloads`. The preload runs six side-effects (registry, trace collector, epoch collector, browser-global install, keybinding listener, auto-open into the inline host) on app boot, all gated on `re-frame.interop/debug-enabled?` and all idempotent so shadow-cljs's `:after-load` cycle re-runs them safely.

For hosts that want to control the install timing — a custom boot pipeline with steps between adapter install and Xray attach, a test harness needing fine-grained sequencing, a host that ships its own preload bundle — `init!` is the alternative.

### `init!`

- **Signature**:
  ```clojure
  (init!) → nil
  (init! opts) → nil
  ```
- **Description**: Mount Xray manually. Idempotent: a second call is a no-op (each underlying side-effect is `defonce`-guarded). Bypasses the preload path. Use when the `:preloads` wiring isn't available — test harnesses, host-controlled boot pipelines, dev builds with a custom preload bundle.

The `opts` map accepts these keys today (pre-alpha — additional keys land under follow-on work):

```clojure
{:target-frame  :app/main          ;; the inspected HOST frame for the scrubber
 :theme         :dark              ;; / :light / :high-contrast (TBD-impl)
 :density       :compact           ;; / :cosy (TBD-impl)
 :ai-provider   {:provider :claude ;; ...} (TBD-impl)
 :buffer-depths {:trace 200 :epoch 50}}
```

Pre-alpha posture wires the four foundation side-effects (registry, trace collector, epoch collector, keybinding listener) and threads `:target-frame` through to `:rf.xray/set-target-frame`. The vocabulary split: `:target-frame` is the **inspected host frame**, distinct from Xray's **own** state frame (`:rf/xray`). There is no `:default-frame` key — supplying it has no effect. **Omitting `:target-frame` leaves the target unselected** (the frame picker / mount discovery policy chooses); Xray never falls back to `:rf/default`. The other keys (`:theme`, `:density`, `:ai-provider`, `:buffer-depths`) are accepted today but ignored at runtime — passing them now keeps host code forward-compatible.

## Frame picker

A host running a single app frame selects it explicitly — via `init! {:target-frame …}`, `set-target-frame!`, or the picker chip — because Xray inspects a **carried** target, never an inferred default. Multi-frame hosts — Story, parallel-frames testbeds, story-mode chrome wrapping a tool surface — likewise tell Xray which frame the scrubber and panels are observing.

### `target-frame`

- **Signature**:
  ```clojure
  (xray/target-frame) → keyword | nil
  ```
- **Description**: Read the currently-selected inspected-host frame, or `nil` when no target has been selected yet (host config, the picker, or `set-target-frame!` selects it). It is **not** defaulted to `:rf/default` — `:rf/default` is an ordinary id, never an Xray fallback. One-shot read (does NOT register for reactive re-render). Reactive consumers subscribe to `:rf.xray/target-frame` directly via the framework's sub surface.

### `set-target-frame!`

- **Signature**:
  ```clojure
  (xray/set-target-frame! frame-id) → nil
  ```
- **Description**: Set the inspected-host frame Xray targets. Dispatches `:rf.xray/set-target-frame` into the `:rf/xray` frame so the sub and every dependent panel re-fire on the standard reactive path. `set-target-frame! nil` resets to the **unselected** state (panels render their no-frame-selected state and the picker prompts a choice) — it does not reset *through* a synthesised `:rf/default`.

The L1 frame picker chip in the shell's top strip is wired to this — clicking flips `set-target-frame!`, and every panel in view (Trace, Views, Machines, App-DB Diff) rescopes to the new frame. Hosts can drive the same flip programmatically from a per-route effect, a Settings-popup wire-up, or a test harness assertion.

## The TBD-impl stub

One surface declared by the spec ships as a stub that emits a `:rf.warning/*` trace and otherwise no-ops.

### `load-theme`

- **Signature**:
  ```clojure
  (xray/load-theme css-string) → nil
  ```
- **Description**: Programmatically swap the Xray shell's CSS theme. The theme module exists (`day8.re-frame2-xray.theme/*`) but the runtime CSS-swap surface is not yet wired. Calling emits `:rf.warning/xray-load-theme-not-yet-implemented` so the gap is visible in the trace stream. Forward-compatible — host code may call with a CSS string today and expect the impl to land later.

## The browser-global JS mirror

The preload installs a JS-side mirror under `window.day8.re_frame2_xray.*` so JS hosts, devtools-console one-liners, and `puppeteer` automation scripts can reach the same surfaces without a CLJS compile. The exact spellings carry Closure's `_BANG_` suffix for ClojureScript-style mutating fns.

```javascript
window.day8.re_frame2_xray.open_BANG_()         // (xray/open!)
window.day8.re_frame2_xray.open_overlay_BANG_() // (xray/open-overlay!)
window.day8.re_frame2_xray.close_BANG_()        // (xray/close!)
window.day8.re_frame2_xray.toggle_BANG_()       // (xray/toggle!)
window.day8.re_frame2_xray.popout_BANG_()       // (xray/popout!)
window.day8.re_frame2_xray.status()             // (xray/status) → map
```

The mirror is gated on `re-frame.interop/debug-enabled?` — production builds (`:advanced` + `goog.DEBUG=false`) elide the install entirely; there is no `window.day8` pollution in shipped binaries.

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

Hosts that want explicit control — a Story tool page that suppresses auto-open, an embed host that needs to bypass the preload — reach for the imperative facade above:

```clojure
(ns my.app.tool-page
  (:require [day8.re-frame2-xray.core :as xray]
            [day8.re-frame2-xray.config :as xray-config]))

;; Suppress the preload's auto-open path.
(xray-config/configure! {:rf.xray/auto-open? false})

;; Later — from a button, a route handler, a test harness:
(xray/open!)
(xray/set-target-frame! :app/tool-canvas)
```

## Production: nothing ships

Xray is **dev-only** by construction. Under `:advanced` compilation with `goog.DEBUG=false`:

- The preload entry is reachable only through dev-build paths; production builds don't `:require` it.
- The framework's trace bus is gated on `re-frame.interop/debug-enabled?`. Every `register-listener!` registration elides at the source.
- The browser-global JS mirror's install is gated the same way; production bundles carry no `window.day8` pollution.
- Source-coord stamping (`data-rf2-source-coord` on every rendered DOM element) elides under the same gate; rendered HTML in production carries no source-coord bytes.

A CI gate ([`implementation/scripts/check-bundle-isolation.cjs`](https://github.com/day8/re-frame2/blob/main/implementation/scripts/check-bundle-isolation.cjs)) greps the plain production bundle for Xray-internal sentinel strings; any hit is a PR-failing regression. The elision holds whether you remember to remove the preload or not.

## See also

- [Configuration keys](config-keys.md) — `configure!` and the per-key setters that flip the auto-open posture, the inline-host selector, the editor preference, the privacy gate.
- [Runtime seam](runtime-seam.md) — the read-and-mutate accessors that tools compose against Xray's mount.
- [Xray tutorial — Installation](../01-installation.md) — the five-minute, three-edits walk-through.
- [Framework API — Lifecycle](../../core/api/13-lifecycle.md) — `rf/init!` and the adapter install pair. The adapter must land before Xray's auto-open path resolves the host.
- [Framework API — Instrumentation](../../core/api/11-instrumentation.md) — the trace bus and epoch buffer Xray renders.
