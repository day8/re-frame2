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
├── .cljfmt.edn               cljfmt formatter config — `clojure -M:cljfmt check` / `fix`
├── lefthook.yml              pre-commit format + lint hook (see lefthook.dev/installation)
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

`shadow-cljs.edn` ships `:source-paths ["src" "test" "dev"]` so the
`:test` build (`:target :node-test`, `:ns-regexp "-test$"`) picks
the emitted test file up out of the box, and `dev/scratch.cljs` +
`dev/user.clj` (the Q8 dev-ergonomics lock — REPL scratch ns and
`(user/refresh)` entry) are reachable from `clojure -M:shadow` and
shadow's nREPL without further classpath plumbing.

The UIx and Helix variants follow the same shape; only `core.cljs`,
`views.cljs`, `deps.edn`, and the substrate-adapter coord change.
See [001-Substrate-Variants.md](001-Substrate-Variants.md) §What
each variant emits.

## The counter

Every variant emits a working counter:

- An init event `:counter/initialise` seeds `app-db` with
  `{:counter/value 0}`.
- An action event `:counter/increment` adds `1` to `:counter/value`.
- A sub `:counter/value` reads the current count.
- A view subscribes to `:counter/value` and renders the value plus
  an increment button that dispatches `:counter/increment`.

The app-db slice and subscription id are intentionally feature-scoped
(`:counter/value`, not a bare `:count`) so generated applications start
with AI-readable, non-colliding state keys. This is the same counter
shape the developer reads about in [Guide chapter 03 — Your first
app](../../../docs/guide/03-your-first-app.md) and the canonical
[`examples/reagent/counter`](../../../examples/reagent/counter/)
example.

## Resource tree (template-side)

The template's own resource tree mirrors the generated tree's
substrate-axis split, with deps-new's `root/` + underscore-prefixed
sub-dirs convention:

```
tools/template/
├── deps.edn                              ; this artefact's deps + :test alias
├── VERSION                               ; template's own version (template-v…)
├── README.md                             ; install + invoke
├── spec/                                 ; this folder
├── src/day8/re_frame2_template/
│   └── hooks.clj                         ; data-fn / template-fn / post-process-fn
└── resources/day8/re_frame2_template/
    ├── template.edn                      ; declarative entrypoint
    ├── root/                             ; bulk-copied, default placement
    │   ├── README.md
    │   ├── lefthook.yml
    │   ├── dev/{user.clj, scratch.cljs}
    │   └── resources/public/{index.html, css/app.css}
    ├── _shared/                          ; substrate-agnostic; renamed at emit
    │   ├── gitignore                     ; emitted as .gitignore
    │   ├── editorconfig                  ; emitted as .editorconfig
    │   ├── cljfmt.edn                    ; emitted as .cljfmt.edn
    │   ├── clj-kondo/config.edn          ; emitted under .clj-kondo/
    │   ├── events.cljs
    │   ├── events_test.cljs
    │   ├── schema.cljs
    │   ├── subs.cljs
    │   └── stories.cljs                  ; only under :include-story? true
    ├── _reagent/                         ; Reagent-specific
    │   ├── deps.edn
    │   ├── deps_with_story.edn           ; picked when :include-story? true
    │   ├── shadow-cljs.edn
    │   ├── package.json
    │   ├── package_with_story.json       ; picked when :include-story? true
    │   ├── core.cljs
    │   ├── core_with_stories.cljs        ; picked when :include-story? true
    │   └── views.cljs
    ├── _uix/                             ; UIx-specific (same shape minus story)
    │   └── ...
    └── _helix/                           ; Helix-specific (same shape minus story)
        └── ...
```

`root/` files are bulk-copied by deps-new's `:root` mechanism with
default placement (substitution applies, no rename).

`_shared/` files emit only when listed in `template-fn`'s shared
`:transform` entry (`:only` flag set), with rename rules that
attach dotfile prefixes and re-home src/test source files under the
user's namespace path (`src/{{nested-dirs}}/`).

`_<substrate>/` files emit only for the chosen substrate, via the
per-substrate `:transform` entry. Adding a substrate is "drop a
new sub-tree at `resources/day8/re_frame2_template/_<substrate>/`"
plus a new `case` clause in `template-fn` plus a one-line entry in
`valid-substrates`.

## How deps-new finds the resources

deps-new's `find-root` resolves the `:template` argument by:

1. Looking up `<sanitized-template-name>/template.edn` on the
   classpath (for `:template day8/re-frame2-template`, the lookup
   is `day8/re_frame2_template/template.edn`). The classpath is
   set up by either a `:local/root "tools/template"` dep or the
   git-coord clone (deps-new's `auto-git-url` for `io.github.*`
   prefixes).
2. Reading the `template.edn`'s `:data-fn` / `:template-fn` /
   `:post-process-fn` keys via `requiring-resolve` against
   `day8.re-frame2-template.hooks`.
3. Running the three hooks in order — `data-fn` augments the data
   map, `template-fn` returns a modified template-edn whose
   `:transform` vector drives file emission, `post-process-fn`
   prints the "Generated …" report.

Hyphens in the template's group + name are sanitised to
underscores on the classpath path (`day8/re-frame2-template` →
`day8/re_frame2_template/`). That's why the resource tree lives
under `resources/day8/re_frame2_template/`.

## Substitution variables

deps-new uses **simple `{{key}}` substitution** (see
`org.corfield.new.impl/->subst-map` + `substitute`) — flat key
lookup, no Mustache-style conditional sections. Two layers thread
through every substituted file: deps-new's auto-derived set and
the template's own additions.

### From deps-new's `preprocess-options` + `->subst-map`

| Variable | Meaning | Example |
|---|---|---|
| `{{name}}` | Qualified raw symbol | `acme/my-app` |
| `{{top}}` | Group portion | `acme` |
| `{{main}}` | Artifact portion | `my-app` |
| `{{top/ns}}` | Namespace-safe top | `acme` |
| `{{main/ns}}` | Namespace-safe main | `my-app` |
| `{{top/file}}` | File-safe top | `acme` |
| `{{main/file}}` | File-safe main | `my_app` |
| `{{now/date}}`, `{{now/year}}` | Calendar values | `2026-05-20`, `2026` |

### Template-specific (added by `data-fn`)

| Variable | Meaning | Example |
|---|---|---|
| `{{namespace}}` | `{{top/ns}}.{{main/ns}}` | `acme.my-app` |
| `{{nested-dirs}}` | `{{top/file}}/{{main/file}}` | `acme/my_app` |
| `{{substrate}}` | The chosen substrate name | `reagent`, `uix`, `helix` |
| `{{substrate-badge-url}}` | shields.io badge URL by substrate | `https://img.shields.io/badge/substrate-Reagent-1abc9c.svg` |
| `{{rf2-version}}` | re-frame2 coord version | `0.0.1.alpha` |
| `{{shadow-version}}` | shadow-cljs pin | `3.4.10` |
| `{{react-version}}` | react & react-dom pin | `19.2.0` |

`{{namespace}}` and `{{nested-dirs}}` are explicitly derived in
`data-fn` rather than relying on the `->subst-map` later stage —
because file-map rename targets in `template-fn` resolve as plain
Clojure strings before `->subst-map` runs, so the derived values
need to exist on the data map at `template-fn` call time.

There is **no conditional-section syntax** (Mustache's
`{{#flag}}…{{/flag}}` is unavailable under deps-new). Conditional
emission is implemented at the file-selection level — `template-fn`
picks `core_with_stories.cljs` over `core.cljs`,
`deps_with_story.edn` over `deps.edn`, etc., per the
`:include-story?` flag. The output filename is the same; the
source-file selection branches.

## Pin lockstep

`:rf2-version`, `:shadow-version`, and `:react-version` are defined
inline in `data-fn` (see
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)).
They are bumped in lockstep with the repo-root `VERSION` and
`implementation/package.json` per
[Principles.md §P5](Principles.md#p5--pins-in-lockstep-with-the-reference-implementation):

- `:rf2-version` tracks the repo-root `VERSION` (the alpha-channel
  re-frame2 release).
- `:shadow-version` matches `implementation/package.json`
  :devDependencies/shadow-cljs.
- `:react-version` matches `implementation/package.json`
  :devDependencies/react (and react-dom — those two are kept
  pinned together).

The
[`version_lockstep_test.clj`](../test/day8/re_frame2_template/version_lockstep_test.clj)
guard enforces every lockstep on every test run. The git-coord
release workflow runs the same gate on every `template-v…` tag
push, so a drifted pin literal blocks the release before the
GitHub Release is cut.

## Cross-references

- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the
  per-variant detail (what `core.cljs` / `views.cljs` look like).
- [API.md](API.md) — the full invocation surface.
- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) — the
  migration plan that established the current resource shape.
- [DESIGN-RATIONALE.md §1](DESIGN-RATIONALE.md#1--deps-new-over-clj-new)
  — why deps-new + git-coord over clj-new + Clojars.
- [Guide chapter 03 — Your first app](../../../docs/guide/03-your-first-app.md)
  — the counter walkthrough the generated app aligns with.
