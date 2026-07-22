# {{name}}

[![Built with re-frame2](https://img.shields.io/badge/built%20with-re--frame2-blue.svg)](https://github.com/day8/re-frame2)
[![Substrate]({{substrate-badge-url}})](https://github.com/day8/re-frame2/tree/main/implementation/ui)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A new [re-frame2](https://github.com/day8/re-frame2) application,
scaffolded from `day8/re-frame2-template` (deps-new).

Substrate: **re-frame.ui** — **EXPERIMENTAL**.

## EXPERIMENTAL status

This scaffold runs on `re-frame.ui`, re-frame2's first-party
compiled-view substrate: views are written with `defview`, reads are
plain values (`(sub [:counter/value])` — nothing to deref), and event
handlers are event vectors (`{:on-click [:counter/increment]}`). There
is no Reagent and no UIx — React arrives through npm and the
compiler wires the reactivity.

**Experimental means the surface may change between alpha releases.**
The Reagent (default) and UIx adapter scaffolds are the supported
choices; pick this variant to try the compiled-view substrate and give
feedback, not (yet) to insulate production work from churn.

## Run the development build

```sh
npm install
npx shadow-cljs watch app
```

Then open <http://localhost:8280> in your browser. You should see the
counter.

## The one build setting

`shadow-cljs.edn` carries the single load-bearing `re-frame.ui`
setting:

```clojure
{:build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}}
```

The hook harvests `re-frame.ui`'s whole-build registries from
cache-durable analyzer data at build time; without it the app cannot
resolve its own compiled views and throws on namespace load. It is
already configured — you never call it, wrap it, or touch it again.
There is no `:cache-blockers` line and none is needed: a warm daemon
start reuses Shadow's disk cache. The full contract (including the
supported shadow-cljs range, 3.4.0–3.4.11) is documented in
[How-to — Install re-frame.ui](https://github.com/day8/re-frame2/blob/main/docs/core/how-to/install-re-frame-ui.md).

## Project shape

```text
├── deps.edn                 ; Clojure deps — re-frame2 core + ui + schemas
├── shadow-cljs.edn          ; :app + :test builds; the build hook above
├── package.json             ; react, react-dom, shadow-cljs (npm half)
├── src/{{nested-dirs}}/
│   ├── core.cljs            ; boot: rf/init!, schema attach, ui/mount
│   ├── events.cljs          ; event handlers (pure: cofx -> effects map)
│   ├── subs.cljs            ; subscriptions (derived values over app-db)
│   ├── schema.cljs          ; whole-app-db Malli schema
│   └── views.cljs           ; compiled defview views
├── test/{{nested-dirs}}/
│   └── events_test.cljs     ; substrate-agnostic event-handler tests
└── resources/public/        ; index.html + css
```

The dataflow half (events / subs / schema) is plain re-frame2 —
identical to what the Reagent and UIx scaffolds emit. Only the view
layer and the mount are substrate-specific.

## Hot reload

`shadow-cljs watch` rebuilds on every save and re-invokes
`{{namespace}}.core/init`. That is safe: `rf/init!` is idempotent (it
installs the adapter only when none is seated and does **not** create
the `:rf/default` frame — the `ui/frame-root` element in `core.cljs`
ENSURES the frame at mount and reuses it live without re-seeding), and
`ui/mount` is idempotent per root, so a reload re-renders into the same
root. The seed boundary is `frame-root`'s
`:initial-events [[:counter/initialise]]` declaration — your durable
app state survives edits.

## Tests

```sh
npm test        # shadow-cljs compile test && node out/node-test.js
```

The emitted `events_test.cljs` exercises the event handlers and subs on
the DOM-free plain-atom substrate — the same handlers the compiled
views drive in the browser.

## Privacy / egress classification

The app-db schema in `schema.cljs` validates **shape**; it does NOT
classify egress. A durable app-db path that carries sensitive or large
data is classified by the event that writes it, via the commit-plane
`:sensitive` / `:large` keys returned alongside `:db` (see the worked
comment in `core.cljs`). The normative model is
[Spec 015 — Data Classification](https://github.com/day8/re-frame2/blob/main/spec/015-Data-Classification.md).

## Devtools

This minimal scaffold does not wire Xray (the in-app devtools panel) —
the adapter scaffolds ship it by default; here the `[data-rf-xray-host]`
layout host in `index.html` simply stays empty and collapsed. Drive the
running app from the REPL (`dev/scratch.cljs` is the on-ramp), or add
Xray later per its docs.

## Production build

```sh
npm run release   # shadow-cljs release app (:advanced)
```

The compiled-view manifests and descriptor projections are
`goog.DEBUG`-guarded and vanish from advanced production output — the
build hook does no production work and ships no production bytes.

## Pre-publication coordinates

`day8/re-frame2`, `day8/re-frame2-ui`, and `day8/re-frame2-schemas` are
not on Clojars yet. Until they publish, resolve them with `:local/root`
paths into a re-frame2 monorepo checkout (the way
[`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter)
does) by editing `deps.edn`'s three framework coords.
