# Install Xray in a development build

Add Xray to your development classpath and preload it in your browser build.
The app supplies an inline host element; Xray renders beside the app once
`rf/init!` installs its adapter.

## Add the dev dependency

```clojure
;; deps.edn: the re-frame2 checkout is beside your app directory
{:aliases
 {:dev
  {:extra-deps
   {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

`:local/root` is relative to your `deps.edn`. Adjust it for your checkout.
Keep the dependency in the development alias.

```clojure
;; shadow-cljs.edn: add these to your existing configuration
{:deps {:aliases [:dev]}
 :builds
 {:app
  {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The fragments above add Xray to an existing app; retain that app's target,
modules and other dependencies. The `:deps` alias must include the alias
containing the Xray dependency.

### Add the npm packages

Xray's machine chart imports two npm packages, `@xyflow/react` and `elkjs`,
through `day8/re-frame2-machines-viz`. A `:local/root` dependency does not
install JavaScript packages, so add both to your app's npm project beside
`react` and `react-dom`:

```bash
npm install --save-dev @xyflow/react@<version> elkjs@<version>
```

Xray ships no npm manifest of its own, so take the versions from the
re-frame2 checkout your `:local/root` points at. Its
[`implementation/package.json`](../../implementation/package.json) lists
both, but `elkjs` there is a range; for an exact pin, use the version
[`implementation/package-lock.json`](../../implementation/package-lock.json)
resolves, which is the one re-frame2 tests against. Keep an existing
compatible pin.

## Reserve the host

```html
<div class="rf-xray-layout">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

```css
:root { --rf-xray-accent: #539bf5; }
body { margin: 0; }
.rf-xray-layout { display: flex; width: fit-content; min-width: 100%; min-height: 100vh; }
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  position: sticky;
  top: 0;
  right: 0;
  height: 100vh;
  overflow: hidden;
  border-left: 1px solid #2a2a2a;
}
#app { flex: 1; min-width: 0; }
@media print { [data-rf-xray-host] { display: none; } }
```

Keep the window as the app's scroller: routing's scroll-to-top and Back
restoration use it. Xray stays beside the app and scrolls its own contents.
Drag its left edge to change its width; Xray remembers the chosen width.
A printed page hides Xray and prints the app at full width.

An app that already scrolls an inner pane, with `html, body, #app` at
`height: 100%`, gives the wrapper `height: 100vh` instead of
`min-height: 100vh`, so that chain resolves against a fixed height and the
pane keeps scrolling. The window then does not scroll, so use this only
where the app's own router, not re-frame2 routing, manages the pane.

With the preload installed, start your usual dev build and interact with
the app. Xray opens automatically. **Ctrl+Shift+C** hides or shows it.
Choose the app's frame if the picker has not selected one, then check that
an application event appears in **Event History**.

## Configure the initial behaviour

Call `configure!` before your app calls `rf/init!`:

```clojure
(require '[day8.re-frame2-xray.core :as xray])

(xray/configure! {:rf.xray/editor :cursor
                 :rf.xray/auto-open? false})
```

This chooses Cursor as the project's editor and waits for an explicit open.
`open!`, `toggle!` and **Ctrl+Shift+C** still work. A different host selector
is `:rf.xray/layout-host-selector "#devtools-xray"`. Each developer can choose
a local editor override in Settings.

The **⛶** button opens a second same-origin window. Settings → General →
**Panel position** offers **Right rail (inline)** and **Fullscreen overlay**.
For a page that intentionally has no inline host, install manually and call
`open-overlay!`; the [mount reference](api/mount-control.md#installing-without-the-preload)
shows that sequence.

## Try the example without wiring an app

From a re-frame2 checkout:

```powershell
cd implementation
npm ci
npm run dev -- :examples/standard-epochs
```

Open `http://localhost:8031`. The page supplies an app, buttons and the
inline Xray host. Run a numbered step and select its event in Xray.

## Clickable jump to source

Xray uses registration coordinates and view attributes to open code in your
editor. Configure its project root and editor when the development server
cannot resolve them automatically. The [source walkthrough](05-click-to-source.md)
shows both paths and how to diagnose a missing coordinate.

## Troubleshooting

Run this in the browser console when the panel does not open:

```javascript
window.day8.re_frame2_xray.status()
```

It returns a ClojureScript map. Expand its `:diagnostic` in the console.

| Symptom or diagnostic | Cause | Fix |
| --- | --- | --- |
| The build fails with `The required JS dependency "@xyflow/react" is not available` (or `"elkjs/lib/elk.bundled.js"`) | The chart's npm packages are not installed | [Add the npm packages](#add-the-npm-packages); `status()` cannot help, because the preload never compiled |
| The browser-global API is undefined | The Xray preload did not load | Check the dev alias and build's `:devtools :preloads`; restart after a classpath change |
| `:missing-layout-host` | No element matches the configured selector | Add the host markup or correct the selector; the console also prints a host snippet |
| `:no-substrate-adapter` | The preload waited about six seconds without an installed adapter | Check that the app calls `rf/init!` and that boot did not fail |
| `:auto-open-disabled` | Automatic opening was turned off | Open explicitly; this diagnostic is healthy |
| The panel opens but has no app events | No app frame is selected, or events are filtered | Select the frame and clear filters/mutes |
| A pop-out does not open | The browser blocked it | Allow popups for this dev origin or use the inline panel |
| App keyboard shortcuts stop working | Xray handles the same chord | Disable **Handle keys?**, or configure `:rf.xray/keybinding-enabled? false` |

## Keep it out of production

Use `:devtools :preloads` and a dev-only dependency alias. If you install
manually, keep both the Xray `:require` and the install calls in a dev-only
namespace. Guarding a call does not remove an unconditional dependency.
The [release setup](api/mount-control.md#production-what-keeps-xray-out)
explains the distinction.
