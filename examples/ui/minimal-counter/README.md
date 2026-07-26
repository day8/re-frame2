# minimal-counter — the smallest runnable Freehand project

A complete, checked, standalone Freehand app: a counter, its dataflow, and the
mount that stands it up. Copy the directory, install, compile, run.

Unlike the rest of [`examples/`](../../README.md) — which are source-only and
run from `implementation/` with a monorepo build-id — this scaffold is a
**standalone project**. It carries its own `deps.edn`, `package.json`, and
`shadow-cljs.edn`, and it is deliberately *not* on the monorepo classpath.
That is the whole point: it is the tree you would have created yourself.

The prose companion is
[Install and boot](../../../docs/core/freehand/get-running/install.md); this directory is
the same four moves — depend, install an adapter, mount, tear down — as a tree
that builds.

## The complete file manifest

Five files. Not "four and some plumbing" — five, all of them load-bearing.
Delete any one and the project does not build:

<!-- rf2:scaffold-manifest — machine-readable; scaffold_smoke_test.clj
     materializes EXACTLY these paths into a temp dir and builds there, so
     this list cannot drift from what the project actually needs. -->
```text
deps.edn
package.json
shadow-cljs.edn
src/my/app.cljs
resources/public/index.html
```

| File | Why it is load-bearing |
|---|---|
| [`deps.edn`](deps.edn) | The Clojure classpath: the two framework artefacts, ClojureScript, and the `:shadow` alias that puts shadow-cljs's JVM side on the classpath. Also `:paths ["src"]` — shadow ignores `:source-paths` in `shadow-cljs.edn` when `:deps` is used. |
| [`package.json`](package.json) | The npm half: `react` + `react-dom` (resolved from `node_modules` at compile time) and the `shadow-cljs` CLI. Without it there is no `npx shadow-cljs` and no React to compile against. |
| [`shadow-cljs.edn`](shadow-cljs.edn) | The `:app` build and `:dev-http`. No framework setting at all — interpreted views need no build configuration. |
| [`src/my/app.cljs`](src/my/app.cljs) | The app: events, a sub, one `v/defview`, and the `v/mount`. |
| [`resources/public/index.html`](resources/public/index.html) | The host page — supplies the `<div id="root">` that `v/mount` targets and loads the compiled bundle. |

Everything else in a real project (tests, linters, CSS, Xray) is genuinely
optional and deliberately absent here.

## Two artefacts, and no build hook

`deps.edn` names `day8/re-frame2` and `day8/re-frame2-freehand`, and that is
the whole floor: Freehand ships its own reactive-substrate adapter, so nothing
here depends on a wrapper library. `(rf/init! v/adapter)` at boot fills the
reactive-substrate contract; `v/mount` puts the tree on the page.

`shadow-cljs.edn` configures nothing on Freehand's behalf, deliberately. The
**compiled** tier is what needs a build hook — its analyzer and registries run
at build time — and this scaffold declares no `{:compiled true}` view. Opt one
in and the hook comes with it, set once in `:build-defaults`.

## Install and run

Non-interactive, from this directory:

```bash
npm install                  # react, react-dom, shadow-cljs
npx shadow-cljs compile app  # one-shot build (what CI gates)
npx shadow-cljs watch app    # dev loop + http://localhost:8280
```

`npx` matters: shadow-cljs is a local devDependency, so a bare `shadow-cljs`
is not on `PATH`.

`watch` is a real dev loop. `my.app/run` carries `^:dev/after-load`, so every
reload re-enters the mount: the edited view re-renders in place and the counter
keeps the value it was showing, because an equal frame plan does not re-seed
`:initial-events`. One metadata key, no reload framework.

## Why the coordinates are checkout-relative

`deps.edn` resolves both framework artefacts with `:local/root` relative to
this checkout. `day8/re-frame2-freehand` is not published and carries no date
at which it will be, so a monorepo checkout is the only supported way to
resolve it — read the coord as the *shape* of the dependency, not as something
you can paste into a fresh project. `day8/re-frame2` does ship to Clojars with
every release, but this scaffold takes it from the same checkout so that the
two artefacts cannot drift apart.

Copying this directory *out* of the monorepo therefore means repointing both
coords at your own checkout. Nothing else in the tree is checkout-relative.

## What the CI gate proves

[`scaffold_smoke_test.clj`](../../../implementation/ui/scaffold-smoke/test/scaffold_smoke_test.clj)
copies exactly the manifest above into a temporary directory, runs a real
`npm install`, and runs `npx shadow-cljs compile app` there. Then the omission
matrix: the manifest minus each file in turn, each expected to stop building.
The manifest is read from *this file*, so the claim above and the tested
contract are the same list.

What it does **not** do is drive a browser — it is a compile gate, and a
compile cannot observe a click. Freehand's mounted behaviour is proved by its
own artefact suites (the `*-dom-cljs-test` namespaces that mount through
`react-dom/client`), not here. To drive this app's loop by hand, use a REPL
against the running frame:

```clojure
(rf/dispatch <frame> [:count/inc])
```
