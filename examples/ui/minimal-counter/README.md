# minimal-counter — the smallest runnable re-frame.ui project

A complete, checked, standalone re-frame.ui app: a counter, its dataflow, and
the mount that stands it up. Copy the directory, install, compile, run.

Unlike the rest of [`examples/`](../../README.md) — which are source-only and
run from `implementation/` with a monorepo build-id — this scaffold is a
**standalone project**. It carries its own `deps.edn`, `package.json`, and
`shadow-cljs.edn`, and it is deliberately *not* on the monorepo classpath.
That is the whole point: it is the tree you would have created yourself.

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
| [`shadow-cljs.edn`](shadow-cljs.edn) | The `:app` build, `:dev-http`, and the one load-bearing re-frame.ui setting — the `:build-defaults` build hook. |
| [`src/my/app.cljs`](src/my/app.cljs) | The app: events, a sub, one `defview`, and the `ui/mount`. |
| [`resources/public/index.html`](resources/public/index.html) | The host page — supplies the `<div id="root">` that `ui/mount` targets and loads the compiled bundle. |

Everything else in a real project (tests, linters, CSS, Xray) is genuinely
optional and deliberately absent here.

## Install and run

Non-interactive, from this directory:

```bash
npm install                  # react, react-dom, shadow-cljs
npx shadow-cljs compile app  # one-shot build (what CI gates)
npx shadow-cljs watch app    # dev loop + http://localhost:8280
```

`npx` matters: shadow-cljs is a local devDependency, so a bare `shadow-cljs`
is not on `PATH`.

## Why the coordinates are checkout-relative

`deps.edn` resolves both framework artefacts with `:local/root` relative to
this checkout, and that is permanent rather than a stopgap. `day8/re-frame2-ui`
is donor-only: the release contract rules out a Clojars coordinate for it, now
and for good, so a monorepo checkout is the only supported way to resolve it.
`day8/re-frame2` does ship to Clojars with every release, but this scaffold
takes it from the same checkout so that the two artefacts cannot drift apart.

Copying this directory *out* of the monorepo therefore means repointing both
coords at your own checkout. Nothing else in the tree is checkout-relative.

## What the CI gate proves

[`scaffold_smoke_test.clj`](../../../implementation/ui/scaffold-smoke/test/scaffold_smoke_test.clj)
copies exactly the manifest above into a temporary directory, runs a real
`npm install`, and runs `npx shadow-cljs compile app` there. The manifest is
read from *this file*, so the claim above and the tested contract are the same
list.

It proves the documented files alone install and compile. It does **not** drive
a browser: DOM handler wiring (`{:on-click [:count/inc]}` actually firing) lands
with re-frame.ui S3 — until then drive the loop from a REPL against the running
frame, per [`re-frame.ui`'s public surface](../../../implementation/ui/src/re_frame/ui.cljc):

```clojure
(rf/dispatch <frame> [:count/inc])
```
