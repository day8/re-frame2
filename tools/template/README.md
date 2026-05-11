# re-frame2-template

> `day8/clj-template.re-frame2` — scaffolding tool for new re-frame2 apps
> (rf2-lrtc). The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).

This tool generates a fresh re-frame2 application skeleton. It is the
front door for new users: one command and you have a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

## Quick start

Once published to Clojars (per the standard release workflow), invoke
the template via clj-new. The substrate selector rides on `:edn-args`
because clj-new's `create` strips unknown top-level args:

```bash
# Reagent — the canonical substrate (default)
clojure -X:project/new :template re-frame2 :name acme/my-app

# UIx
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'

# Helix
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :helix]'
```

That assumes the standard `:project/new` alias is wired in
`~/.clojure/deps.edn` per
[clj-new's README](https://github.com/seancorfield/clj-new):

```clojure
{:aliases
 {:project/new {:extra-deps {com.github.seancorfield/clj-new
                             {:mvn/version "1.3.415"}}
                :exec-fn    clj-new/create
                :exec-args  {:template "app"}}}}
```

If you don't have that alias, the equivalent inline form is:

```bash
clojure -Sdeps '{:deps {com.github.seancorfield/clj-new
                        {:mvn/version "1.3.415"}}}' \
        -X clj-new/create :template re-frame2 :name acme/my-app
```

Until the alpha publish lands on Clojars, use the `:local/root` route to
exercise the template from a checkout of this repo:

```bash
clojure -Sdeps '{:deps {day8/clj-template.re-frame2
                        {:local/root "tools/template"}}}' \
        -X clj-new/create :template re-frame2 :name acme/my-app
```

## What you get

For substrate `:reagent` (default):

```
my-app/
├── deps.edn                  re-frame2 + Reagent adapter + shadow-cljs
├── shadow-cljs.edn           :app (watch/release) + :test builds
├── package.json              react + react-dom + shadow-cljs
├── README.md                 "run shadow-cljs watch app; open localhost:8280"
├── .gitignore
├── resources/public/
│   └── index.html            host page; loads /js/main.js
└── src/my_app/
    ├── core.cljs             entry point — mounts the view
    ├── events.cljs           :counter/initialise, :counter/increment
    ├── subs.cljs             :counter/value
    └── views.cljs            the counter view
```

The UIx and Helix variants follow the same shape; only `core.cljs`,
`views.cljs`, `deps.edn`, and the substrate adapter coord change.

The generated counter mirrors the canonical counter examples in this
repo at `examples/<substrate>/counter*/core.cljs` — the user who runs
the template sees the same shape they read about in
[the guide](../../docs/guide/).

## Implementation choice

This template is a **`clj-new` template**.

`clj-new` is the modern successor to lein-template; consumed via
`clojure -X:project/new`. It is mature, declarative (resource templates
with Mustache substitution), and widely understood by the Clojure
community. The v1 `re-frame-template` was lein-template; clj-new is its
direct lineage.

Two alternatives were considered:

- **`deps-new`** (option B) — newer, similar shape but with a different
  config style. Equally viable. clj-new wins on familiarity: more
  prior art, the v1 template's audience knows the workflow.
- **Small CLI / `bb` script with interactive prompts** (option C) —
  more flexible for branching choices ("include Story? include 10x?")
  but heavier-weight for the user (extra runtime, extra prompts) and
  outside Clojure norms. Reserved for the day branching choices
  materially exceed clj-new's substitution capability — see also
  the "future toggles" comment in `src/clj/new/re_frame2.clj`.

## How it works

clj-new resolves a template by name. For `:template re-frame2` it
searches Clojars/Maven Central for `<group>/clj-template.re-frame2` —
we publish as `day8/clj-template.re-frame2`. Once resolved, clj-new
classloads the template, calls its entry function, and writes the
generated tree to the user's current directory.

### Layout

```
tools/template/
├── deps.edn                              ; this artefact's deps
├── README.md                             ; this file
└── src/clj/new/
    ├── re_frame2.clj                     ; the entry function
    └── re_frame2/                        ; resource tree (Mustache templates)
        ├── shared/                       ; substrate-agnostic files
        │   ├── README.md
        │   ├── gitignore
        │   ├── events.cljs
        │   ├── subs.cljs
        │   └── index.html
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

clj-new's `renderer "re-frame2"` discovers files by classpath path
`clj/new/re_frame2/<file>` (hyphens get sanitised to underscores; nested
paths are preserved). The entry fn assembles the file list, choosing
the substrate-specific subdirectory based on the `:substrate` arg.

### Substitution variables

clj-new's `project-data` gives us the standard set:

| Variable | Meaning | Example |
|---|---|---|
| `{{name}}` | Project name (un-grouped) | `my-app` |
| `{{namespace}}` | Project's main ns | `acme.my-app` |
| `{{nested-dirs}}` | The ns as a directory path | `acme/my_app` |
| `{{group}}` | The group portion of the coord | `acme` |
| `{{year}}`, `{{date}}` | Calendar values | `2026`, `2026-05-11` |

Plus template-specific:

| Variable | Meaning | Example |
|---|---|---|
| `{{substrate}}` | The chosen substrate name | `reagent`, `uix`, `helix` |
| `{{rf2-version}}` | re-frame2 coord version | `0.0.1.alpha` |
| `{{shadow-version}}` | shadow-cljs pin | `2.28.20` |
| `{{react-version}}` | react & react-dom pin | `18.3.1` |

## Bundle isolation / elision contract

Per [`tools/README.md`](../README.md), tools must not be reachable from
a consumer's production build. This template satisfies the contract
trivially: **the template jar is never on a consumer's runtime
classpath**. It is a build-time scaffold; consumers invoke it via
`clojure -X:project/new` and what lands in their project is the
generated source tree, not this jar. The dependency flow is
unidirectional: user → clj-new → this template → generated files; the
generated app has no compile-time or runtime knowledge of the template.

## Testing

A JVM test under `test/clj/new/re_frame2/core_test.clj` exercises the
template end-to-end for each of the three substrates:

1. Generate a tmp app via the template's main fn.
2. Walk the produced file tree and assert the expected shape.
3. Run `clojure -P` against the generated `deps.edn` to confirm a
   successful deps-parse.

Run it from this directory:

```bash
clojure -M:test
```

The `clojure -P` step requires a clojure CLI on PATH; if it isn't
available the test logs and skips that assertion (deps-parse is a
nice-to-have signal — the file-tree shape is the harder contract).

## Cross-references

- [`tools/README.md`](../README.md) — the tools/ convention and the
  bundle-isolation contract.
- [`spec/Construction-Prompts.md`](../../spec/Construction-Prompts.md) —
  AI-driven scaffolding prompts; the template is for human-driven
  scaffolding, the prompts cover the agent-driven path.
- [`examples/reagent/counter/`](../../examples/reagent/counter/) — the
  canonical counter the Reagent variant mirrors.
- [`examples/uix/counter_uix/`](../../examples/uix/counter_uix/) — UIx
  counter.
- [`examples/helix/counter_helix/`](../../examples/helix/counter_helix/) —
  Helix counter.
