# Template — Generated Shape

> Capability doc. The file layout the template emits, the resource
> tree it reads from, and the substitution variables threaded
> through.

## What lands in the user's directory

For substrate `:reagent` (the default):

```
my-app/
├── deps.edn                  re-frame2 + Reagent adapter + shadow-cljs
├── shadow-cljs.edn           :app (watch/release) + :test builds
├── package.json              react + react-dom + shadow-cljs
├── README.md                 "run shadow-cljs watch app; open localhost:8280"
├── .gitignore
├── .editorconfig             2-space, LF, trim trailing, final newline
├── .clj-kondo/
│   └── config.edn            empty default; lint rules slot in here
├── resources/public/
│   ├── index.html            host page; loads /js/main.js + /css/app.css
│   └── css/app.css           three-rule plain stylesheet
├── src/my_app/
│   ├── core.cljs             entry point — mounts the view
│   ├── events.cljs           :counter/initialise, :counter/increment
│   ├── subs.cljs             :counter/value
│   └── views.cljs            the counter view
├── test/my_app/
│   └── events_test.cljs      cljs.test deftest hitting the events ns
│                             under the plain-atom adapter
└── dev/
    ├── user.clj              JVM-side (user/refresh) workflow entry
    └── scratch.cljs          REPL scratch ns for (rf/dispatch …)
                              experiments against the running app
```

`shadow-cljs.edn` ships `:source-paths ["src" "test"]` so the `:test`
build (`:target :node-test`, `:ns-regexp "-test$"`) picks the file
up out of the box.

The UIx and Helix variants follow the same shape; only `core.cljs`,
`views.cljs`, `deps.edn`, and the substrate-adapter coord change.
See [001-Substrate-Variants.md](001-Substrate-Variants.md) §What
each variant emits.

## The counter

Every variant emits a working counter:

- An init event `:counter/initialise` seeds `app-db` with `{:counter
  0}`.
- An action event `:counter/increment` adds `1` to `[:counter]`.
- A sub `:counter/value` reads the current count.
- A view subscribes to `:counter/value` and renders the value plus
  an increment button that dispatches `:counter/increment`.

This is the same shape the developer reads about in [Guide chapter
03 — Your first app](../../../docs/guide/03-your-first-app.md) and
in the [`examples/<substrate>/counter*/`](../../../examples/)
trees. The template's generated counter and the guide's worked
example align.

## Resource tree (template-side)

The template's own resource tree mirrors the generated tree's
substrate-axis split:

```
tools/template/
├── deps.edn                              ; this artefact's deps
├── README.md                             ; install + invoke
├── spec/                                 ; this folder
└── src/clj/new/
    ├── re_frame2.clj                     ; the entry function
    └── re_frame2/                        ; resource tree (Mustache templates)
        ├── shared/                       ; substrate-agnostic files
        │   ├── README.md
        │   ├── gitignore                 ; emitted as .gitignore
        │   ├── editorconfig              ; emitted as .editorconfig
        │   ├── clj-kondo/
        │   │   └── config.edn            ; emitted under .clj-kondo/
        │   ├── events.cljs
        │   ├── events_test.cljs
        │   ├── subs.cljs
        │   ├── index.html
        │   ├── dev/
        │   │   ├── user.clj
        │   │   └── scratch.cljs
        │   └── resources/public/css/app.css
        ├── reagent/                      ; Reagent-specific
        │   ├── deps.edn
        │   ├── shadow-cljs.edn
        │   ├── package.json
        │   ├── core.cljs
        │   └── views.cljs
        ├── uix/                          ; UIx-specific
        │   └── (same shape)
        └── helix/                        ; Helix-specific
            └── (same shape)
```

`shared/` files are emitted into every generated app unchanged.
`<substrate>/` files are emitted only when that substrate is
selected. Adding a substrate is "drop a new sub-tree at
`re_frame2/<substrate>/`" plus a one-line change to the
`valid-substrates` set.

## How clj-new finds the resources

clj-new's `renderer` and `raw-resourcer` build their lookup path as
`clj/new/<sanitized-template-name>/<file>`. The template's entry fn
keys its renderer off the top-level template name `re-frame2` and
passes the relative resource path (e.g. `shared/README.md` or
`reagent/core.cljs`) as the file:

```clojure
(let [render     (renderer "re-frame2")
      raw        (raw-resourcer "re-frame2")
      sub-render (fn [path] (render path data))
      sub-raw    (fn [path] (raw path))]
  (->files data
           ["deps.edn"
            (sub-render (str substrate-name "/deps.edn"))]
           ["src/{{nested-dirs}}/core.cljs"
            (sub-render (str substrate-name "/core.cljs"))]
           ["src/{{nested-dirs}}/events.cljs"
            (sub-render "shared/events.cljs")]
           ;; ...
           ))
```

The `renderer` reads each resource through clj-new's Mustache
renderer, applying the `data` map's substitutions. `raw-resourcer`
copies bytes through without substitution — used today only for
`.gitignore` (clj-new's `->files` strips leading dots from generated
filenames so the source is committed as `gitignore`, sans-dot, and
gets renamed at emit time).

Internal slashes in the path are preserved on the classpath lookup;
hyphens in the top-level template name get sanitised to
underscores (`re-frame2` → `re_frame2/`). That's why the resource
tree lives at `src/clj/new/re_frame2/...`.

## Substitution variables

Two layers thread through every Mustache-rendered file: clj-new's
standard set and the template's own additions.

### From clj-new's `project-data`

| Variable | Meaning | Example |
|---|---|---|
| `{{name}}` | Project name (un-grouped) | `my-app` |
| `{{namespace}}` | Project's main ns | `acme.my-app` |
| `{{nested-dirs}}` | The ns as a directory path | `acme/my_app` |
| `{{group}}` | The group portion of the coord | `acme` |
| `{{artifact}}` | The artefact portion | `my-app` |
| `{{year}}`, `{{date}}` | Calendar values | `2026`, `2026-05-11` |

### Template-specific

| Variable | Meaning | Example |
|---|---|---|
| `{{substrate}}` | The chosen substrate name | `reagent`, `uix`, `helix` |
| `{{reagent?}}` | Conditional section flag | `true` / `false` |
| `{{uix?}}` | Conditional section flag | `true` / `false` |
| `{{helix?}}` | Conditional section flag | `true` / `false` |
| `{{rf2-version}}` | re-frame2 coord version | `0.0.1.alpha` |
| `{{shadow-version}}` | shadow-cljs pin | `3.4.10` |
| `{{react-version}}` | react & react-dom pin | `18.3.1` |

The `?`-suffixed flags exist because Mustache sections key on a
truthy value; `{{#reagent?}}...{{/reagent?}}` lets a shared file
gate small substrate-specific snippets without splitting the
template into more sub-trees. Today most substrate-specific
divergence lives at the file level (see [the resource
tree](#resource-tree-template-side)); the section flags are
reserved for future shared-file substrate gating.

## Pin lockstep

`{{rf2-version}}`, `{{shadow-version}}`, and `{{react-version}}` are
defined inline in `src/clj/new/re_frame2.clj`. They are bumped in
lockstep with `implementation/package.json` and the runtime
coords' release cadence:

- `:rf2-version` tracks the re-frame2 alpha-channel release at the
  time of template publish.
- `:shadow-version` and `:react-version` match
  `implementation/package.json` so the combination the template
  emits is the combination CI has smoke-tested.

Bumping these is part of the per-release checklist when the
template's own version cuts.

## Cross-references

- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the
  per-variant detail (what `core.cljs` / `views.cljs` look like).
- [API.md](API.md) — the full invocation surface.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) §clj-new-vs-deps-new
  — why this Mustache-resource shape was chosen.
- [Guide chapter 03 — Your first app](../../../docs/guide/03-your-first-app.md)
  — the counter walkthrough the generated app aligns with.
