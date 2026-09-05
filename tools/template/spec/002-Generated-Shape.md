# Template — Generated Shape

> Capability doc. The file layout the template emits, the resource
> tree it reads from, and the substitution variables threaded
> through.

## What lands in the user's directory

For `:name acme/my-app` on either substrate — twelve files:

```
my-app/
├── .gitignore
├── README.md                 run / test / release / what is here / next steps
├── deps.edn                  re-frame2 + the substrate adapter + view library; :shadow alias
├── package.json              react + react-dom + shadow-cljs; watch / release / test scripts
├── shadow-cljs.edn           :app (watch / release) + :test (:node-test) builds
├── resources/public/
│   ├── index.html            host page; loads /js/main.js + /css/app.css
│   └── css/app.css           a handful of rules
├── src/acme/my_app/
│   ├── core.cljs             installs the adapter; mount! is the ^:dev/after-load hook
│   ├── events.cljs           :counter/initialise, :counter/increment
│   ├── subs.cljs             :counter/value
│   └── views.cljs            the counter view (substrate-specific)
└── test/acme/my_app/
    └── events_test.cljs      cljs.test over the events + sub, under the plain-atom adapter
```

The set is the same for `:reagent` and `:uix`; `deps.edn`, `core.cljs`
and `views.cljs` differ in content
([001-Substrate-Variants.md](001-Substrate-Variants.md) §What each
variant emits). The suite pins this manifest as a set EQUALITY per
substrate, so a file reappearing is as red as one going missing.

`deps.edn` carries two framework coordinates — `day8/re-frame2` and the
adapter — on one `{{rf2-version}}` pin, the substrate's view library, and
a deps-only `:shadow` alias (shadow-cljs on the classpath, `test/` on
`:extra-paths`). `shadow-cljs.edn` reads its classpath from that alias.
`package.json` declares `shadow-cljs`, `react` and `react-dom` at the
lockstep pins and nothing else. Nothing in the tree names Xray, Story,
SSR, schemas, machines, a linter, a formatter, a hook manager or a CI
provider.

## The counter

- An init event `:counter/initialise` seeds `app-db` with
  `{:counter/value 0}`. `core.cljs` passes it as the frame's
  `:initial-events`, so it runs once, when the frame is created.
- An action event `:counter/increment` adds `1` to `:counter/value`.
- A sub `:counter/value` reads the current count.
- A view subscribes to `:counter/value` and renders the value plus
  an increment button that dispatches `:counter/increment`.

The app-db slice and subscription id are feature-scoped
(`:counter/value`, not a bare `:count`) so generated applications start
with AI-readable, non-colliding state keys. This is the same counter
shape the developer reads about in [the Guide — app-db](../../../docs/core/app-db.md)
and the canonical
[`examples/core/counter`](../../../examples/core/counter/)
example.

## Hot-reload contract

shadow-cljs calls a `:browser` build's module `:init-fn` exactly once, at
bundle load, and thereafter invokes only `^:dev/after-load` hooks
(measured on shadow-cljs 3.4.10, rf2-r0kk7). Every emitted `core.cljs`
therefore carries such a hook — `mount!` — and that hook, not `init`, is
what a save re-runs:

1. **One retained root.** The React root lives in a `defonce` cell and
   is created once, then rendered into on every later call rather than
   a second root going over a live DOM node. Reagent puts an
   adapter-owned `client-root` handle in that cell and lets the first
   `rf.adapter.reagent/render!` create the underlying Root; UIx puts the
   `uix.dom/create-root` Root there itself.
2. **The frame is created by the view, and reused.** `rf/frame-root`
   (or `rf.adapter.uix/frame-root`) creates `:rf/default` the first time,
   running `:initial-events [[:counter/initialise]]` synchronously so
   the first render sees the seeded app-db, and reuses the live frame
   without re-seeding on every later render. A reload never touches
   app-db.
3. **`init` delegates to `mount!`.** `init` installs the adapter with
   `rf/init!` — which does not create a frame — and calls `mount!`, so
   boot and reload traverse one render path.

`template_emission_test.clj` pins these facts on the emitted
`core.cljs`: exactly one `^:dev/after-load` hook, its body renders,
`init` calls it, exactly one root allocation
(`rf.adapter.reagent/client-root` or `uix-dom/create-root`), a `defonce`
cell holding it, and the
`:initial-events` seed. The behavioural tier then proves the page a
newcomer opens actually mounts and moves the counter 0 → 1 in Chromium.

## Resource tree (template-side)

The template's own resource tree mirrors the generated tree's
substrate split, with deps-new's `root/` + underscore-prefixed
sub-dirs convention:

```
tools/template/
├── deps.edn                              ; this artefact's deps + :test alias
├── VERSION                               ; template's own version (template-v…)
├── README.md                             ; install + invoke
├── spec/                                 ; this folder
├── src/day8/re_frame2_template/
│   └── hooks.clj                         ; data-fn / template-fn / post-process-fn
├── test/day8/re_frame2_template/         ; the suite (see Principles §P7)
├── test-support/page-boot-proof.cjs      ; the Chromium driver the behavioural tier runs (dev + release)
└── resources/day8/re_frame2_template/
    ├── template.edn                      ; declarative entrypoint
    ├── root/                             ; bulk-copied, default placement
    │   ├── README.md
    │   └── resources/public/{index.html, css/app.css}
    ├── _shared/                          ; substrate-agnostic; renamed into place at emit
    │   ├── gitignore                     ; emitted as .gitignore
    │   ├── shadow-cljs.edn
    │   ├── package.json
    │   ├── events.cljs                   ; emitted under src/<nested-dirs>/
    │   ├── subs.cljs
    │   └── events_test.cljs              ; emitted under test/<nested-dirs>/
    ├── _reagent/                         ; deps.edn / core.cljs / views.cljs
    └── _uix/                             ; deps.edn / core.cljs / views.cljs
```

`root/` files are bulk-copied by deps-new's `:root` mechanism with
default placement (substitution applies, no rename).

`_shared/` files emit only when listed in `template-fn`'s shared
`:transform` entry (`:only` flag set), with rename rules that attach the
dotfile prefix and re-home the source files under the user's namespace
path (`src/{{nested-dirs}}/`, `test/{{nested-dirs}}/`).

`_<substrate>/` files emit only for the chosen substrate, via the
per-substrate `:transform` entry. Adding a substrate is a registry
entry, a sibling sub-tree and one `case` arm
([001 §Adding a substrate](001-Substrate-Variants.md#adding-a-substrate)).

`_shared/events.cljs` and `_shared/subs.cljs` stay at those paths and
keep registering `:counter/initialise`, `:counter/increment` and
`:counter/value`: `scripts/check_skill_setup_counter_drift.py` reads
them by path and requires every `:counter/*` id the setup skill's
snippets use to be registered there.

## How deps-new finds the resources

deps-new's `find-root` resolves the `:template` argument by:

1. Looking up `<sanitized-template-name>/template.edn` on the
   classpath (for `:template day8/re-frame2-template`, the lookup
   is `day8/re_frame2_template/template.edn`). The classpath is
   set up by either a `:local/root` dep on `tools/template` or the
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
| `{{substrate-label}}` | The chosen substrate's display name (the README, the `package.json` description) | `Reagent`, `UIx` |
| `{{npm-name}}` | The `package.json` `name`: the artefact segment of `:name`, lowercased — unscoped, npm-valid. A qualified name copied verbatim (`acme/my-app`) is what npm rejects. | `my-app` |
| `{{rf2-version}}` | re-frame2 framework coord version | `0.0.1.alpha` |
| `{{shadow-version}}` | shadow-cljs pin | `3.4.10` |
| `{{react-version}}` | react & react-dom pin | `19.2.0` |

`{{namespace}}` and `{{nested-dirs}}` are explicitly derived in
`data-fn` rather than relying on the `->subst-map` later stage —
because file-map rename targets in `template-fn` resolve as plain
Clojure strings before `->subst-map` runs, so the derived values
need to exist on the data map at `template-fn` call time.

There is **no conditional-section syntax** (Mustache's
`{{#flag}}…{{/flag}}` is unavailable under deps-new), and the template
needs none: the only conditional emission is the per-substrate file
selection in `template-fn`.

## Pin lockstep

`:rf2-version`, `:shadow-version`, and `:react-version` are defined
inline in `data-fn` (see
[`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)).
They are bumped in lockstep with the repo-root `VERSION` and
`implementation/package.json` per
[Principles.md §P5](Principles.md#p5--pins-in-lockstep-with-the-reference-implementation):

- `:rf2-version` tracks the repo-root `VERSION`.
- `:shadow-version` matches `implementation/package.json`
  :devDependencies/shadow-cljs.
- `:react-version` matches `implementation/package.json`
  :devDependencies/react (and react-dom — those two are kept
  pinned together).

The view-library pins (`reagent/reagent`, `com.pitch/uix.core`,
`com.pitch/uix.dom`) and the Clojure / ClojureScript pins live in the
per-substrate `deps.edn` resources and are guarded against the adapter
and core `deps.edn` files in `implementation/`.

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
- [DESIGN-RATIONALE.md §1](DESIGN-RATIONALE.md#1--deps-new-over-clj-new)
  — why deps-new + git-coord over clj-new + Clojars.
- [Guide — app-db](../../../docs/core/app-db.md)
  — the counter walkthrough the generated app aligns with.
