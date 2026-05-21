# docs/cljs playground (rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover; rf2-bujlr Phase 2; rf2-00zvt Phase 3)

The roll-your-own live-ClojureScript-cell playground for the `docs/cljs` page —
the production replacement for Klipse. It turns ` ```cljs ` fenced blocks in
mkdocs prose into CodeMirror 6 editors that evaluate plain CLJS in the browser
via Scittle (SCI), instant-nav-safe.

**Phase 2 (rf2-bujlr)** adds a second cell kind for **live reagent / re-frame
components** (stock libs, via Scittle plugins). **Phase 3 (rf2-00zvt)** adds a
third cell kind for **live re-frame2 components** that evaluate re-frame2's OWN
public API — see [Cell kinds](#cell-kinds) below.

## Cell kinds

| Fence | Class emitted | Behaviour |
|---|---|---|
| ` ```cljs ` | `language-cljs` | **plain-eval cell** — evaluates the source and `pr-str`s the last form's value into the result div (Phase 1). |
| ` ```cljs-render ` | `language-cljs-render` | **stock render cell** — evaluates the source and **mounts the last form's value as a reagent component** into the result div (Phase 2). STOCK reagent + re-frame are available, via the Scittle plugins. |
| ` ```cljs-rf2 ` | `language-cljs-rf2` | **re-frame2 render cell** — same as `cljs-render` but evaluated against **re-frame2's OWN public API** (`re-frame.core` v2) rendered via **reagent2** (Phase 3). Backed by a self-contained SCI bundle (`sci/` → `docs/cljs/playground-rf2.js`), NOT Scittle. |

### Render cells (` ```cljs-render `)

A render cell's source may `require` `reagent.core` / `reagent.dom` /
`re-frame.core` and use `reg-event-db` / `reg-sub` / `dispatch` / `subscribe`
(stock re-frame — **not** re-frame2's own API; that is Phase 3). The cell's
**last form** must evaluate to a reagent renderable: a hiccup vector
(`[:div ...]`) or a component vector (`[my-component]`). The bootstrap renders
it via `reagent.dom/render` into the cell's result div. Render cells
**auto-render on load** (the live component is the point) and re-render on
Mod-Enter after edits.

```cljs-render
(require '[re-frame.core :as rf])
(rf/reg-event-db :init (fn [_ _] {:n 0}))
(rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
(rf/reg-sub      :n    (fn [db _] (:n db)))
(rf/dispatch-sync [:init])
(defn counter []
  [:div
   [:span "count: " @(rf/subscribe [:n])]
   [:button {:on-click #(rf/dispatch [:inc])} "inc"]])
[counter]
```

Implementation note: a render cell's source is evaluated at the SCI **top
level** (NOT wrapped in `(do ...)` like a plain cell), because SCI only
propagates a `require`'s aliases to *sibling top-level* forms — wrapping the
body in one `(do ...)` makes the cell's `rf/...` aliases unresolvable.

The reagent + re-frame Scittle plugins (and the `React` + `ReactDOM` globals
they need) are loaded from the CDN **only on pages that contain a
` ```cljs-render ` cell** — plain-CLJS pages never pay that cost.

### re-frame2 cells (` ```cljs-rf2 `, Phase 3)

A `cljs-rf2` cell evaluates against **re-frame2's own public API** — the v2
`re-frame.core` (`reg-event-db` / `reg-sub` / `dispatch` / `subscribe`), rendered
via **reagent2** (the reagent-slim rewrite re-frame2 actually renders through),
NOT stock re-frame. Like a `cljs-render` cell, the **last form** must be a
reagent renderable; the cell auto-renders on load and re-renders on Mod-Enter.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
(rf/reg-event-db :init (fn [_ _] {:n 0}))
(rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
(rf/reg-sub      :n    (fn [db _] (:n db)))
(rf/dispatch-sync [:init])
(defn counter []
  [:div
   [:span "count: " @(rf/subscribe [:n])]
   [:button {:on-click #(rf/dispatch [:inc])} "inc"]])
[counter]
```

**Why this is NOT a Scittle plugin.** Scittle's `scittle.reagent.js` /
`scittle.re-frame.js` plugins ship STOCK reagent + re-frame, and there is no
published `scittle.core` artefact a standalone plugin build could `:require`
(Scittle is a monorepo module-graph build). So Phase 3 is a **self-contained SCI
eval bundle** (findings doc §6 option B): the `sci/` sub-project is a shadow-cljs
`:browser` `:advanced` build that depends on `org.babashka/sci` + re-frame2 core
+ reagent-slim, builds an SCI context via `sci/copy-ns` over `re-frame.core`, and
installs `window.rf2sci.{evalString,renderLast}`. The bootstrap loads it as a
classic `<script>` **only on pages with a ` ```cljs-rf2 ` cell**.

How re-frame2's API reaches a cell:

- In compiled CLJS, `re-frame.core` carries plain-fn **aliases** for every
  `reg-*` registration (the macro forms are JVM-only and only add source-coord
  capture, which a browser cell does not need), so `sci/copy-ns` exposes them
  under their plain names.
- `dispatch` / `dispatch-sync` / `subscribe` / `inject-cofx` are macro-only on
  the public surface (the fns are `dispatch*` / … / `subscribe*`), so the SCI
  config adds those names explicitly, bound to the `*`-fns.

**React 19 is bundled, not global.** reagent2 targets React 19, which **dropped
its UMD build** — so the Phase-2 global-`React`-from-CDN trick is unavailable.
The `sci/` bundle therefore bundles `react`/`react-dom`@19 (the impl-pinned
versions) directly: `playground-rf2.js` is one fully self-contained file (no
external React, no CDN, no version-mismatch risk). ~1.06 MB raw / ~256 KB
gzipped.

This is **option B** from the findings doc
(`ai/findings/2026-05-21-roll-your-own-cljs-playground.md` §6) realised as a
self-contained `tools/` artefact. The bootstrap + CM6 editor are plain JS bundled
by **esbuild**; the Phase-3 re-frame2 eval engine is the **CLJS + shadow-cljs**
sub-build under `sci/`. (This artefact ships its own `sci/shadow-cljs.edn` +
`sci/deps.edn` — the top-level `tools/shadow-cljs.edn` is **untouched**.)

## Stack (pinned)

| Dependency | Version | Role |
|---|---|---|
| `@nextjournal/clojure-mode` | 0.3.3 | Lezer CLJS mode: syntax, brackets, paredit, `default_extensions` + `complete_keymap` |
| `@codemirror/state` | ^6.6.0 | CM6 editor state (`EditorState`, `Prec`) |
| `@codemirror/view` | ^6.43.0 | CM6 view (`EditorView`, `keymap`, `lineNumbers`) |
| `@codemirror/commands` | ^6.10.3 | history + default keymaps |
| Scittle | 0.8.31 | SCI eval engine — loaded as a classic `<script>` global from jsDelivr (NOT bundled, NOT an ES module) |
| `scittle.reagent.js` | 0.8.31 | reagent plugin (Phase 2) — loaded from jsDelivr ONLY on pages with a ` ```cljs-render ` cell |
| `scittle.re-frame.js` | 0.8.31 | re-frame plugin (Phase 2) — same on-demand load |
| React + ReactDOM | 18 (UMD) | globals the Scittle reagent plugin references — loaded from jsDelivr ahead of the plugins, on-demand (Phase 2 only) |
| esbuild | ^0.28.0 | bundler (IIFE) for the bootstrap |
| playwright | ^1.60.0 | smoke harness (chromium) |

The Phase-3 re-frame2 eval bundle (`sci/`) pins:

| Dependency | Version | Role |
|---|---|---|
| `org.babashka/sci` | 0.11.51 (git) | the SCI interpreter — the re-frame2 cells' eval engine |
| `day8/re-frame2` (core) | `:local/root` | the public API exposed to cells (`re-frame.core` v2) |
| `day8/reagent-slim` | `:local/root` | reagent2 (the render substrate) + the `reagent-slim` adapter |
| `react` + `react-dom` | 19.2.0 | **bundled** into `playground-rf2.js` (React 19 has no UMD) |
| `shadow-cljs` | 3.4.10 | the CLJS → `:advanced` browser bundler |

## Build

```bash
cd tools/playground
npm install
npm run build          # builds BOTH bundles (bootstrap + re-frame2 SCI)
# npm run build:bootstrap   # just the esbuild bootstrap
# npm run build:rf2         # just the sci/ shadow-cljs re-frame2 bundle
# npm run build:dev         # unminified bootstrap, for debugging
```

`npm run build` produces three committed, deployed assets:

- `docs/cljs/playground.js` — the esbuild IIFE bundle (CM6 + clojure-mode + the
  instant-nav bootstrap). **Committed** (vendored prebuilt; bump = re-bundle).
- `docs/cljs/playground.css` — hand-authored cell styles, copied verbatim.
- `docs/cljs/playground-rf2.js` — the shadow-cljs `:advanced` re-frame2 SCI
  bundle (Phase 3). Built from `sci/` (`shadow-cljs release rf2` → copied from
  `sci/out/`). **Committed** (vendored prebuilt; bump = re-bundle).

Neither Scittle nor the re-frame2 bundle is loaded eagerly — the bootstrap
injects each `<script>` at eval time, only on pages that have the relevant cell
kind (Scittle for ` ```cljs `/` ```cljs-render `; `playground-rf2.js` for
` ```cljs-rf2 `), the same guarded, lazy-load pattern the deleted Klipse
bootstrap used for its plugin.

## Test

```bash
npm run browsers       # one-time: playwright install chromium
npm run smoke          # headless chromium drives 3 cells against docs/cljs/playground.js
```

The smoke loads BOTH **production** bundles (`docs/cljs/playground.js` +
`docs/cljs/playground-rf2.js`) against a page that mimics the mkdocs-emitted DOM
(`<pre class="language-cljs">` + `<pre class="language-cljs-render">` +
`<pre class="language-cljs-rf2">`), proves the bootstrap auto-injects each engine
on demand, then asserts:

- **Phase 1:** `(+ 1 2 3) => 6`; a `defn`/`println`/nested-coll cell captures
  `*out*` and renders the value; an error cell renders `ERROR` without crashing
  (and cell 1 still evals after).
- **Phase 2:** a ` ```cljs-render ` cell makes the bootstrap auto-load the
  React + ReactDOM + scittle.reagent + scittle.re-frame stack; a reagent
  counter using a re-frame `subscribe` renders live; clicking its button
  `dispatch`es a re-frame event and the subscribed view updates (count 0 → 2);
  a plain ` ```cljs ` cell on the same page still works.
- **Phase 3:** a ` ```cljs-rf2 ` cell makes the bootstrap auto-load the
  self-contained re-frame2 SCI bundle (`window.rf2sci`); a reagent2 component
  using re-frame2's OWN `subscribe` renders live; clicking its button
  `dispatch`es a re-frame2 event and the v2 subscription updates (count 0 → 2);
  the Phase-1 plain cell + Phase-2 stock cell on the same page still work.

Build both bundles first: `npm run build` (or `npm run build:rf2` for just the
re-frame2 one).

## mkdocs wiring

`mkdocs.yml` declares a `cljs` custom fence (`pymdownx.superfences` →
`<pre class="language-cljs">`) and loads `playground.js` / `playground.css` via
`extra_javascript` / `extra_css`. Material re-runs `extra_javascript` on every
instant nav; the bootstrap subscribes once to `window.document$` and re-scans
the swapped DOM (idempotent via `data-cljs-mounted`). Sub-path (`/re-frame2/`)
asset resolution uses `document.currentScript.src`.

## Cutover (Phase 1b, rf2-j06sy)

Phase 1 shipped behind a **new** fence class (`language-cljs`) so it could
coexist with Klipse during the transition. **Phase 1b cut over**: the
`docs/cljs/index.md` cells are now `cljs` fences rendered here, Klipse's
`extra_javascript` line + `klipse` custom fence were removed from `mkdocs.yml`,
and the vendored Klipse assets (`docs/klipse/klipse_plugin.js` ~7.4 MB,
`klipse-bootstrap.js`, `codemirror.css`) were deleted. All ~87 cells were
spot-checked under the playground first (eval-result + error fidelity vs
Klipse — Risk #1). One fidelity fix landed in the cutover: a top-level
`def`/`defn` returns a var, so the renderer derefs it to show the bound value
(matching Klipse's friendlier display) rather than `#'user/x`.

## Three gotchas honoured (from the Phase 0 spike, rf2-qk3sh)

1. SCI has no JVM classes — capture `*out*` via `with-out-str`, not
   `java.io.StringWriter`.
2. A CLJS vector returned to JS is a `PersistentVector` object, not a JS Array —
   wrap the eval return in `(clj->js ...)`.
3. The Mod-Enter eval keymap is swallowed unless wrapped in
   `Prec.highest(keymap.of([...]))`.
