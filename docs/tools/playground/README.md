# Live code cells

The docs site turns ` ```cljs ` and ` ```cljs-rf2 ` fenced blocks into editable
cells that run in the reader's browser. This folder builds the code behind
them.

## Writing a cell

A plain ` ```cljs ` cell evaluates ClojureScript and prints the last value.
The reader presses Mod-Enter (Ctrl-Enter or Cmd-Enter) to run it.

```cljs
(defn greet [n] (str "Hello, " n))
(greet "re-frame2")
```

```text
=> "Hello, re-frame2"
```

A ` ```cljs-rf2 ` cell runs against re-frame2's public API and renders its last
form as a component. It runs when the page loads, and again on Mod-Enter after
an edit.

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
(rf/reg-sub   :n   (fn [db _] (:n db 0)))

(rf/reg-view counter []
  [:button {:on-click #(dispatch [:inc])}
   "Clicked " @(subscribe [:n]) " times"])

[rf/frame-root {:id :counter}
 [counter]]
```

The same fence takes Fresco. A cell whose last form uses a Fresco view
renders through Fresco:

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.fresco :as h])

(rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
(rf/reg-sub   :n   (fn [db _] (:n db 0)))

(h/defview counter [_]
  [:button {:on-click [:inc]}
   "Clicked " (h/sub [:n]) " times"])

[h/frame-root {:id :counter}
 [counter {}]]
```

| | ` ```cljs ` | ` ```cljs-rf2 ` |
|---|---|---|
| Runs on | [Scittle](https://github.com/babashka/scittle) 0.8.31, from jsDelivr | `docs/cljs/playground-rf2.js`, built from `sci/` |
| Shows | Printed output and `=> <value>` | The last form, rendered |
| Runs | On Mod-Enter | On page load and on Mod-Enter |
| Can `require` | No | Yes |

### What a re-frame2 cell can use

The bundle carries core, both view layers and every optional artefact:

| Require | For |
|---|---|
| `re-frame.core` | Everything on the façade, including `reg-view`, `reg-machine`, `reg-flow`, `reg-app-schema`, `reg-resource` and `reg-route` |
| `re-frame.fresco`, `.forms`, `.overlay`, `.motion` | Fresco views, `h/defview`, `h/sub`, `h/event` |
| `reagent2.core` (also as `reagent.core`), `reagent2.ratom`, `reagent2.dom.client` | reagent2 |
| `re-frame.schemas` | Reading registered schemas |
| `re-frame.http.managed`, `re-frame.http.test-support` | `:rf.http/managed`, and stubs for it |
| `re-frame.resources` | Resource events and subs |
| `re-frame.routing`, `re-frame.epoch`, `re-frame.ssr` | Routing, epoch history, `render-to-string` |

Fresco's `defhost` is not available, because a cell has no foreign React
component to wrap.

### Which renderer a cell uses

The last form renders through **Fresco** when it contains a Fresco view
(`h/defview`), `h/frame-root` or `h/frame-provider`, or a handler written as
data (`:on-click [:inc]`). Otherwise it renders through **reagent2**. Plain
hiccup such as `[:p "Hello"]` looks the same either way.

One page can mix reagent2 and Fresco cells, and they share frames and
registrations. One tree cannot mix them directly: Fresco refuses a plain
function as a head, and a Fresco view inside a reagent2 tree needs
`[:> (h/as-component view)]`.

### HTTP in a cell

A real request works if the endpoint is `https` and allows CORS. For a demo
that must not depend on the network, stub `:rf.http/managed`:

```clojure
(http-test-support/install-managed-request-stubs!
  {[:get "https://api.example.com/articles/intro"] {:reply {:ok {:title "Welcome"}}}})

[rf/frame-root {:id :demo :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [article-view]]
```

Install stubs and never uninstall them: the stub stack is global, so an
uninstall can restore a previous page's routes.

### Rules

- **A plain cell cannot `require`.** Its body is wrapped in one `(do ...)` so
  the cell can capture `*out*`, and SCI applies a `require`'s aliases only to
  the top-level forms after it. Use a ` ```cljs-rf2 ` cell for anything that
  needs a library.
- **End a re-frame2 cell with a vector.** Hiccup (`[:p "hi"]`) and component
  vectors (`[counter]`) render. Any other value is shown with `pr-str`, so a
  cell that ends in a `reg-*` call shows the registered id.
- **Frames are optional.** A cell that never creates a frame uses
  `:rf/default`, and `dispatch` and `subscribe` default to it. A cell that
  creates its own frame with `frame-root` uses that frame, as a real app does.
- **Cells on one page share state.** Every cell registers into the same
  registrar, so a later cell can render a view an earlier one registered.
- **Leaving a page clears it.** On navigation the outgoing page's components
  unmount, its frames are destroyed, and its registrations and HTTP
  interceptors are removed. Destroying a frame also stops its requests and
  timers. Each page registers what it uses.
- **Never create a `:url-bound? true` frame.** It would take over the docs
  site's address bar. Routing works on in-memory frames.

## Build

```bash
cd docs/tools/playground
npm install
npm run build
```

`npm run build` writes three files:

| File | Built from | Committed |
|---|---|---|
| `docs/cljs/playground.js` | `src/playground.mjs`, bundled by esbuild | Yes |
| `docs/cljs/playground.css` | `src/playground.css`, copied | Yes |
| `docs/cljs/playground-rf2.js` | `sci/`, built by shadow-cljs | No |

The other scripts:

- `npm run build:bootstrap` builds the two committed files. It needs only
  Node.
- `npm run build:rf2` builds the re-frame2 engine. It needs Java and the
  Clojure CLI, and it runs shadow-cljs, so in this repo it uses the
  machine-wide build lane (see `AGENTS.md`).
- `npm run build:dev` builds an unminified `playground.js` for debugging.

`playground-rf2.js` is not committed. It bundles re-frame2 core, Fresco and
every optional artefact, so committing it would mean every PR touching any of
them rebuilds the same multi-megabyte file, and two such PRs always
conflict. CI builds it
where it is used. `mkdocs build` works without it; re-frame2 cells then show
an error in place of their output.

## Test

```bash
npm run browsers   # once: installs Playwright's Chromium
npm run smoke
```

The smoke serves both built bundles to headless Chromium on a page shaped
like mkdocs output. It checks that the bootstrap loads each engine on demand,
that plain cells evaluate and report errors, that re-frame2 cells render and
re-render on dispatch (including machine, flow, schema and multi-frame
cells), that Fresco cells render through the same fence, that stubbed HTTP
and resource cells load, that routing, epoch and SSR are present, and that
navigating away releases the outgoing page's React roots, frames and
registrations. Build both bundles first.

## How it works

`mkdocs.yml` declares the two fences under `pymdownx.superfences`, which emit
`<pre class="language-cljs">` and `<pre class="language-cljs-rf2">`, and
loads `playground.js` and `playground.css` on every page through
`extra_javascript` and `extra_css`. It also excludes this folder from the
site.

**The bootstrap** (`src/playground.mjs`) runs on every page. On each Material
`document$` emission, the first load and every instant navigation, it:

1. calls `window.rf2sci.disposePage()` to release the previous page's cells;
2. injects the engine for each cell kind on the page, once per document;
3. replaces each `<pre>` with a CodeMirror 6 editor and a result area.

The re-frame2 engine's URL resolves relative to `playground.js`, so the site
works both at a domain root and under `/re-frame2/`. Plain-cell pages never load the
re-frame2 engine, and re-frame2-only pages never load Scittle.

**The re-frame2 engine** (`sci/src/rf2_playground/sci.cljs`) is an SCI
interpreter with re-frame2 compiled in. It exposes each namespace with
`sci/copy-ns`, and replaces the JVM-only macros `reg-view`, `reg-machine`,
`reg-flow`, `h/defview` and `h/event` with SCI macros and runtime fns, so
cells write the same calls as real code. It installs `window.rf2sci` with
`renderLast`, `disposePage` and `release`. A reagent2 cell re-renders into
its existing React root; a Fresco cell gets a fresh root on every run,
because a mounted `h/frame-root` refuses new options. Two build settings
matter:

- React 19 has no UMD build, so `react` and `react-dom` are bundled in from
  `sci/package.json`.
- `goog.DEBUG` stays true. Cells register handlers and dispatch in the same
  eval, which needs the dev-mode live registrar.

## Adding an artefact to re-frame2 cells

1. Add it to `sci/deps.edn` as a `:local/root` dep.
2. Require it in `sci.cljs`, so its late-bind hooks install at load. If cells
   should `require` one of its namespaces, add an SCI namespace for it the
   way `re-frame.schemas` is added. Give each JVM-only macro an SCI stand-in.
3. Add its `src` and `deps.edn` to the roster in
   `scripts/playground-sci-input-digest.mjs`, to the `playground` surface in
   `.github/scripts/report-changed-surfaces.sh`, and to the per-class table
   in `implementation/scripts/_playground-sci-inputs.test.cjs`. That test
   fails if a roster entry has no table row or does not trigger the
   `playground` job.
4. Add a cell to the smoke.

## CI

- **`tools-playground` in `.github/workflows/test.yml`** runs on PRs that touch
  this folder or any artefact the engine bundles. It builds both bundles, runs
  the smoke, requires the committed `playground.js` and `playground.css` to
  match a fresh build byte for byte, and checks that `playground-rf2.js` is a
  plausible bundle.
- **The `build` job in `.github/workflows/docs.yml`** rebuilds both bundles
  before `mkdocs build`. Outside PRs it also runs the smoke, so the deployed
  engine is the one that was tested.

`sci/scripts/copy-bundle.mjs` appends
`//# rf2-sci-input-digest=<hex>` to `playground-rf2.js`: a hash of the inputs
listed in `scripts/playground-sci-input-digest.mjs`. It records which source
produced a deployed bundle. Nothing gates on it.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A re-frame2 cell shows `ERROR: re-frame2 SCI bundle not loaded` | `docs/cljs/playground-rf2.js` was not built | `npm run build:rf2` |
| CI: `Committed docs/cljs/playground.{js,css} are stale` | `src/` changed without a rebuild | `npm run build:bootstrap`, then commit both files |
| A plain cell cannot resolve an alias it just required | Plain cells cannot `require` | Make it a ` ```cljs-rf2 ` cell |
| A cell raises `:rf.error/no-such-handler` for an id another page registered | Navigation clears the previous page's registrations | Register the handler on this page |
| A re-frame2 cell shows a printed value instead of a component | Its last form is not a vector | End the cell with hiccup or a component vector |
| A cell raises `:rf.error/fresco-bad-head` | The tree has a Fresco view, so it renders through Fresco, and it also has a plain fn or `reg-view` head | Make the head an `h/defview`, or keep the cell all reagent2 |
| An HTTP cell's `:on-failure` gets `no stub matched` | The request's method and URL are not in the stub map | Key the stub on the exact `[method url]` the request sends |
| The smoke fails with `playground-rf2.js not found` | The engine was not built | `npm run build` |
