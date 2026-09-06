# 1. Installation

You want Xray in a dev build without teaching production about it. This chapter gives you the smallest honest setup: add the dev dependency, reserve the right-side host, wire the preload, and check that the panel opens.

## Add The Dev Dependency

While re-frame2 is pre-alpha, use a checkout-local dependency from a dev alias. `:local/root` is relative to *your* `deps.edn`, so the path below assumes the convention the rest of the docs use: a re-frame2 clone sitting **beside** your project directory.

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:aliases
 {:dev
  {:extra-deps
   {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

When Xray is published, this becomes a normal Maven coordinate. Keep it in a dev-only alias. Xray is a tool, not application code.

## Reserve The Host

Xray's normal launch mode is a true inline right rail. Your page owns the layout. Xray owns the content inside the host.

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #539bf5; }

.app-shell {
  display: flex;
  min-height: 100vh;
}

#app {
  flex: 1;
  min-width: 0;
}

[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
}
```

The CSS variable sets the initial width. Xray adds its own drag handle, persists the user-chosen width, and yields if you deliberately put native `resize:` behavior on the host.

If your layout cannot use `[data-rf-xray-host]`, configure another selector before `rf/init!`:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])

(xray-config/configure!
  {:rf.xray/layout-host-selector "#devtools-xray"})
```

## Wire The Preload

For a Shadow CLJS browser build:

```clojure
;; shadow-cljs.edn
{:builds
 {:app
  {:devtools
   {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's trace and epoch collectors, installs the browser API, installs the `Ctrl+Shift+C` keybinding, and auto-opens into the layout host after the re-frame2 substrate adapter is ready.

You do not need to call `init!` when the preload is wired. `day8.re-frame2-xray.core/init!` exists for manual hosts and unusual embedding setups.

## Launch And Close

In a normal dev page, Xray opens automatically once the app starts. The everyday controls are:

| Action | How |
|---|---|
| Hide or show Xray | `Ctrl+Shift+C` |
| Close from the shell | the close icon in the ribbon |
| Open from code | `(day8.re-frame2-xray.core/open!)` |
| Toggle from code | `(day8.re-frame2-xray.core/toggle!)` |
| Pop out to a second same-origin window | `(day8.re-frame2-xray.core/popout!)` |

Tool-owned pages can suppress only the automatic page-load open:

```clojure
(xray-config/configure! {:rf.xray/auto-open? false})
```

That does not disable Xray. Explicit `open!`, `toggle!`, and the keybinding still work.

## Check It

From this repository, the smallest useful Xray driving surface is the standard-epochs testbed:

```powershell
cd implementation
npx shadow-cljs watch :examples/standard-epochs
```

Open `http://localhost:8031`. Click a few numbered buttons on the left. Xray should be visible on the right, and the event spine should fill with rows.

![The standard-epochs app driving Xray](../images/xray/xray-tutorial-shell.png)

If Xray does not appear, check:

- The host element exists in the page.
- The preload is on the dev build, not the release build.
- `rf/init!` has run with a substrate adapter.
- `window.day8.re_frame2_xray.status()` has no missing-host diagnostic.

## Clickable Jump-To-Source

Every panel that surfaces a source-coord wraps it in an `open` chip. Clicking jumps to that line in your editor — but only once Xray knows which editor to open. On a plain host app wiring just the preload, no editor is configured: the chip targets the `:vscode` default scheme, but if that is not your editor the OS has no handler for it and the click would silently go nowhere. So rather than navigate into the void, an unconfigured click surfaces a **"No editor configured" hint** — a small bottom-corner toast with an **Open Settings** button that lands you on the editor picker. Once an editor is configured (in Settings or at boot), the click resolves and navigates straight to source; the hint never fires.

Set your editor either in **Xray Settings** (the "Click-to-source links open in" picker on the General tab — persisted per-dev in localStorage, so each teammate can pick their own), or once at boot in code:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor})
;; :vscode (default) | :cursor | :windsurf | :zed | :idea | {:custom "<uri-template>"}
```

The Settings picker overrides the boot-time `configure!` value per-machine, so a mixed-editor team sets a project default in code and individuals override locally.

`:rf.xray/project-root` is **only** needed when your stamped source-coords are classpath-*relative* (an editor cannot resolve a relative path). The normal `reg-*` / `reg-machine` registration path stamps **absolute** coords, which Xray ships verbatim — leave `:rf.xray/project-root` unset in that case. Do not hardcode a machine-specific path "to make Open work"; if absolute coords already open, you do not need it.

## Production Posture

**Xray is kept out of production by where you put it, not by anything inside Xray.** The practical rule is the whole rule: put Xray in dev build config, not app code.

- **The preload path is dev-only build config.** `:devtools/preloads` belongs to the dev build, so a release build never loads `day8.re-frame2-xray.preload`. Its boot block is additionally wrapped in `(when rf.interop/debug-enabled? …)`, which Closure folds away under `:advanced` + `goog.DEBUG=false` — a second line of defence for that path. The trace and epoch collectors gate their own entry points the same way.
- **The manual `init!` / mount path has no `goog.DEBUG` gate.** `init!` registers Xray's handlers, the collectors, the browser globals and the keybinding listener unconditionally; `open!` gates only on a substrate adapter being installed, which every app that called `rf/init!` has in production exactly as in dev. Requiring `day8.re-frame2-xray.core` at all runs load-time registrations, so guarding the *calls* is not enough — keep the `:require` **and** the calls in a namespace only your dev entry point loads. See [Mount control §Production: what keeps Xray out](api/mount-control.md#production-what-keeps-xray-out).
- **No CI gate proves Xray's absence from a release bundle.** `npm run test:elision` roots `re-frame.*` sentinels only; the bundle-isolation check greps a no-feature bundle that never installed Xray. If you want certainty for your own build, grep your release output for `rf-xray-root` or `rf.xray` — both survive Closure as string literals. That is a leak detector, not proof of zero retained bytes.
