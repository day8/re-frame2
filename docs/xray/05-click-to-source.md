# 5. Click-To-Source

You found the bad row or the wrong DOM node. Now you want the source line, not a philosophical seminar. This chapter shows how Xray and re-frame2 source coordinates get you from runtime evidence back to code.

## Source Coordinates Everywhere Useful

In dev mode, re-frame2 stamps source coordinates onto registrations and rendered views. Xray consumes those coordinates in panels and turns them into editor links where possible.

You will see source links on things like:

- event handlers;
- subscriptions;
- views;
- effects and coeffects;
- machine definitions;
- route registrations;
- schema registrations;
- trace rows with a known origin.

A registration records its coordinate as a map of `:ns`, `:file`, `:line` and `:column`. A rendered view carries the same facts packed into one string attribute, described next.

## DOM Back To View

In dev mode, each registered view's root element carries `data-rf2-source-coord`, which points at the view code that produced it, and `data-rf-view`, which names the view. Only the root element carries them; the elements inside it do not.

In browser DevTools, inspect an element and look for the nearest ancestor carrying:

```html
<div data-rf2-source-coord="runner.core:step-row:310:1"
     data-rf-view=":runner.core/step-row">
  ...
</div>
```

The value is the namespace, the view's name, the line and the column. It is a dev-only bridge from pixels back to the view function.

Going the other way, hover a view in the Epoch or Views tab and Xray highlights that view's element on the page.

![A step row of the standard-epochs testbed outlined in red, labelled as the element carrying data-rf2-source-coord](../images/xray/xray-tutorial-source-coord.png)

## Xray Back To Editor

Xray renders each coordinate as a link. Its look varies by panel: in Epoch it is the registration kind, such as `reg-event`, followed by ↗, and in Trace it is a ↗ after the row's target. Clicking resolves the coordinate to your editor's URI scheme — `vscode://file/...`, `cursor://...`, `idea://open?...` — and hands it to the OS so your editor jumps to the line.

### Tell Xray Which Editor

The catch: Xray cannot guess your editor. A host that wires only the preload never sets one, so the link falls back to the framework default `:vscode` scheme. If VS Code is not your editor, the OS has no handler for that scheme and the navigation goes nowhere — and the browser cannot observe an OS-level handler miss, so the click would be a silent dead end.

Rather than navigate into the void, an unconfigured click surfaces a **"No editor configured" hint**: a small, non-intrusive toast in the bottom corner of the panel with an **Open Settings** button that lands you on the editor picker. Once an editor is configured, the hint never fires and the click navigates straight to source.

There are two ways to configure the editor:

- **Xray Settings (per-dev).** The General tab's "Click-to-source links open in" picker. The choice persists per-developer in `localStorage`, so each teammate picks their own editor on their own machine — and the Open-Settings button on the hint toast lands you right here.
- **`configure!` at boot (project default).** Set it once in your app's boot code:

    ```clojure
    (require '[day8.re-frame2-xray.config :as xray-config])
    (xray-config/configure! {:rf.xray/editor :cursor})
    ;; :vscode (default) | :cursor | :windsurf | :zed | :idea | {:custom "<uri-template>"}
    ```

    The `{:custom "<uri-template>"}` form supports a team's own editor bridge via `{path}` / `{file}` / `{line}` / `{column}` placeholders.

The two compose: **the Settings picker overrides the boot-time `configure!` value, per machine.** So a mixed-editor team sets a sensible project default in code and individuals override locally without touching the host's boot config — the override is purely client-side and never mutates the shared default.

### What A Click Does

With an editor configured, a click first asks the page's dev server to open the file, by posting to `/__rf-open-in-editor`. The testbeds in this repository are served by a dev server that answers it. When no server answers, Xray hands the editor URI to the operating system. Each click logs that URI to the browser console, so when nothing opens you can see what was attempted. A `{:custom …}` template that produces a `javascript:`, `data:` or `vbscript:` URI is never opened.

### Relative Coordinates And `:rf.xray/project-root`

`:rf.xray/project-root` is **only** for classpath-*relative* source coordinates. Editor URI handlers resolve paths against the filesystem, so a relative path fails with "Path does not exist" — when your stamped coords are relative, set the on-disk root so Xray can prefix it into an absolute URI:

```clojure
(xray-config/configure! {:rf.xray/project-root "/abs/path/to/project/src"})
```

The normal `reg-*` / `reg-machine` registration path stamps **absolute** coordinates, which Xray ships verbatim — in that (common) case leave `:rf.xray/project-root` unset. Do not hardcode a machine-specific path "to make Open work"; if absolute coords already open, you do not need it.

## A Practical Loop

When a view is wrong:

1. Inspect the DOM node or open Xray's Views tab.
2. Follow the view source coordinate.
3. Check which subscriptions the view read.
4. Jump to the subscription source coordinate.
5. Open app-db or Trace for the focused epoch.

That loop is short because every hop is data-shaped. You are not searching the repository for a string you hope is unique; you are following the runtime's own registration facts.

## Privacy And Production

In a production build, a registration's metadata carries no source coordinate, and the HTML carries no `data-rf2-source-coord` or `data-rf-view`. The runtime keeps each registration's `:ns`, `:file` and `:line` for one purpose only, so that error reports can still name where a handler lives. Production bundles should not include Xray.

Sensitive values are a separate concern. Xray follows the framework's redaction and elision rules when rendering runtime evidence. A source coordinate tells you where a value came from; it should not force the value itself to leak.
