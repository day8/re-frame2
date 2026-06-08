# 5. Click-To-Source

You found the bad row or the wrong DOM node. Now you want the source line, not a philosophical seminar. This chapter shows how Xray and re-frame2 source coordinates get you from runtime evidence back to code.

## Source Coordinates Everywhere Useful

In dev mode, re-frame2 stamps source coordinates onto registrations and rendered views. Xray consumes those coordinates in panels and turns them into editor links where possible.

You will see source chips on things like:

- event handlers;
- subscriptions;
- views;
- effects and coeffects;
- machine definitions;
- route registrations;
- schema registrations;
- trace rows with a known origin.

The shape is the same idea everywhere: namespace, symbol or id, line, and column.

## DOM Back To View

Rendered elements can carry `data-rf2-source-coord` in dev mode. That attribute points at the view code that produced the DOM.

In browser DevTools, inspect or copy an element and look for:

```html
<button data-rf2-source-coord="standard_epochs.core:control-button:412:4">
  ...
</button>
```

That is not for production. It is a dev-only bridge from pixels back to the view function.

![A DOM node carrying a source coordinate](../images/xray/05-dom-attribute.png)

## Xray Back To Editor

Xray's source chips wrap each coordinate in an `open` chip. Clicking resolves the coordinate to your editor's URI scheme — `vscode://file/...`, `cursor://...`, `idea://open?...` — and hands it to the OS so your editor jumps to the line.

### Tell Xray Which Editor

The catch: Xray cannot guess your editor. A host that wires only the preload never sets one, so the chip falls back to the framework default `:vscode` scheme. If VS Code is not your editor, the OS has no handler for that scheme and the navigation goes nowhere — and the browser cannot observe an OS-level handler miss, so the click would be a silent dead end.

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

Source coordinates are dev-only. Production HTML does not carry `data-rf2-source-coord`, and production bundles should not include Xray.

Sensitive values are a separate concern. Xray follows the framework's redaction and elision rules when rendering runtime evidence. A source coordinate tells you where a value came from; it should not force the value itself to leak.
