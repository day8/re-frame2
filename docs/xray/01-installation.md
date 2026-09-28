# 1. Installation

You want Xray in your dev build and nowhere else. Setup is four steps: add the dev dependency, give Xray a place in the page, wire the preload, and check that the panel opens.

## Add the dev dependency

While re-frame2 is pre-alpha, use a checkout-local dependency from a dev alias. `:local/root` is relative to *your* `deps.edn`, so the path below assumes the convention the rest of the docs use: a re-frame2 clone sitting **beside** your project directory.

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:aliases
 {:dev
  {:extra-deps
   {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

When Xray is published, this becomes a normal Maven coordinate. Keep it in a dev-only alias either way.

## Reserve the host

Xray opens as a column on the right of your page. Your page owns the layout; Xray renders inside the element you give it.

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

The CSS variable sets the starting width. Xray adds its own drag handle, remembers the width you drag it to, and leaves the host alone if you give it native `resize:` behaviour yourself.

If your layout cannot use `[data-rf-xray-host]`, configure another selector before `rf/init!`:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])

(xray-config/configure!
  {:rf.xray/layout-host-selector "#devtools-xray"})
```

## Wire the preload

For a shadow-cljs browser build:

```clojure
;; shadow-cljs.edn
{:builds
 {:app
  {:devtools
   {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload loads your saved Xray settings, registers Xray's handlers and its trace and epoch collectors, installs the browser API and Xray's keyboard shortcuts, `Ctrl+Shift+C` among them, and opens Xray in the host once `rf/init!` has installed the substrate adapter.

With the preload wired you do not call `init!`. `day8.re-frame2-xray.core/init!` exists for hosts that install Xray by hand; see [Mount control](api/mount-control.md#installing-without-the-preload).

## Launch and close

In a normal dev page, Xray opens automatically once the app starts. The everyday controls are:

| Action | How |
|---|---|
| Hide or show Xray | `Ctrl+Shift+C` |
| Close from the shell | the close icon in the ribbon |
| Open from code | `(day8.re-frame2-xray.core/open!)` |
| Toggle from code | `(day8.re-frame2-xray.core/toggle!)` |
| Pop out to a second same-origin window | the ⛶ icon in the ribbon, or `(day8.re-frame2-xray.core/popout!)` |
| Cover the page instead of sitting beside it | Settings → General → Panel position → Fullscreen overlay |

A page that should not open Xray on load can turn off only the automatic open:

```clojure
(xray-config/configure! {:rf.xray/auto-open? false})
```

Xray stays installed: `open!`, `toggle!` and `Ctrl+Shift+C` still work.

Xray listens for its shortcuts on the whole page, including Ctrl+K (⌘K) for its command palette. When those collide with your app's own keys, set `{:rf.xray/keybinding-enabled? false}` in `configure!`, or turn off **Handle keys?** on Settings' Keybindings tab.

## Check it

From a re-frame2 checkout, the quickest way to see Xray working is the standard-epochs testbed, which the rest of this guide uses as its running example:

```powershell
cd implementation
npx shadow-cljs watch :examples/standard-epochs
```

Open `http://localhost:8031` and press **⏭ Step** on the left a few times. Xray is open on the right, and its event list fills with rows.

![The standard-epochs testbed on the left after five steps, with Xray open on the right](../images/xray/xray-tutorial-shell.png)

## Troubleshooting

If Xray does not appear in your own app, check that:

- the host element exists in the page;
- the preload is on the dev build, not the release build;
- `rf/init!` has run with a substrate adapter.

Then run `window.day8.re_frame2_xray.status()` in the browser console. Its `:diagnostic` says why a launch did not happen:

| `:reason` | Meaning | Fix |
| --- | --- | --- |
| `:missing-layout-host` | No element matches the host selector. Xray also logs a `console.error` naming the selector, with a host snippet to paste. | Add the host element, or set `:rf.xray/layout-host-selector` |
| `:no-substrate-adapter` | Xray waited about 6 seconds and no substrate adapter was installed | Call `rf/init!` with an adapter |
| `:auto-open-disabled` | Auto-open is switched off. This is not a failure. | Open Xray with `Ctrl+Shift+C` or `open!` |

## Clickable jump-to-source

Every panel that surfaces a source-coord renders it as a link, such as `reg-event ↗` in Epoch or a ↗ after a Trace row's target. Clicking jumps to that line in your editor — but only once Xray knows which editor to open. On a plain host app wiring just the preload, no editor is configured: the link targets the `:vscode` default scheme, but if that is not your editor the OS has no handler for it and the click would silently go nowhere. So an unconfigured click shows a **"No editor configured" hint** instead: a small bottom-corner toast with an **Open Settings** button that lands you on the editor picker. Once an editor is configured, in Settings or at boot, the click navigates straight to source and the hint never fires.

Set your editor either in **Xray Settings** (the "Click-to-source links open in" picker on the General tab, stored per developer in localStorage, so each teammate can pick their own), or once at boot in code:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])
(xray-config/configure! {:rf.xray/editor :cursor})
;; :vscode (default) | :cursor | :windsurf | :zed | :idea | {:custom "<uri-template>"}
```

The Settings picker overrides the boot-time `configure!` value on that machine, so a mixed-editor team sets a project default in code and each developer overrides it locally.

`:rf.xray/project-root` is **only** needed when your stamped source-coords are classpath-*relative* (an editor cannot resolve a relative path). The normal `reg-*` / `reg-machine` registration path stamps **absolute** coords, which Xray ships verbatim — leave `:rf.xray/project-root` unset in that case. Do not hardcode a machine-specific path "to make Open work"; if absolute coords already open, you do not need it.

## Keep it out of production

Xray stays out of a release build because the release build never loads it. Put it in dev build configuration, not in application code.

- **The preload.** `:devtools :preloads` belongs to the dev build, so a release build never loads `day8.re-frame2-xray.preload`.
- **`init!`.** If you install Xray by hand, nothing inside Xray stops it reaching production: requiring `day8.re-frame2-xray.core` is enough to register it. Keep the `:require` and the calls in a namespace only your dev entry point loads. [Production: what keeps Xray out](api/mount-control.md#production-what-keeps-xray-out) has the details and a sample namespace.

Nothing checks this for you. To confirm, search both builds' output for a string Xray always carries:

```bash
npx shadow-cljs compile app
grep -c "rf-xray-root" public/js/main.js    # expect more than 0

npx shadow-cljs release app
grep -c "rf-xray-root" public/js/main.js    # expect 0
```

The dev build must show a count above 0. Zero in both means the search is broken, not that Xray is gone.
