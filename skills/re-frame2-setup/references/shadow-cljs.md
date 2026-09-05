# shadow-cljs build & index.html

What the scaffold's build config and page do, key by key. The files themselves — `shadow-cljs.edn`, `package.json`, `.gitignore`, `resources/public/index.html`, `resources/public/css/app.css` — are in [`first-counter.md`](first-counter.md); nothing here is a second copy to paste.

## Contents

- The day-one `shadow-cljs.edn`
- The `index.html` that loads the bundle
- Hot reload (`^:dev/after-load`)
- The `:test` build (`npm test`)
- `.gitignore` — what the build generates
- Production build (`release`)
- nREPL — only if you'll use `re-frame2-pair`
- What re-frame2 does NOT need

---

## The day-one `shadow-cljs.edn`

Two builds, one dev server, and a classpath read from `deps.edn`. Five things matter for re-frame2:

1. **`:deps {:aliases [:shadow]}`.** shadow-cljs reads the build classpath from `deps.edn`'s `:shadow` alias — don't duplicate the `day8/re-frame2-*` coordinates here. That alias supplies the JVM-side `thheller/shadow-cljs` build dep and the `test` extra-path, and it is deps-only, deliberately without `:main-opts`: `npx shadow-cljs` (and every `npm run` script) already appends `-m shadow.cljs.devtools.cli` when it shells out to `clojure`, and a second `-m` dies on shadow's arg parser with `Unknown option: "-m"`. The pure-JVM route, if you want it without the node wrapper: `clojure -M:shadow -m shadow.cljs.devtools.cli watch app`.
2. **`:dev-http {8280 "resources/public"}`.** The top-level dev-server form (current shadow-cljs idiom): serves `resources/public/` on port `8280`, so `index.html` lives at `resources/public/index.html`. Reuse `8280` so a teammate on the generator route sees the same number; if it is already bound, pick another port and report the one the watch actually prints.
3. **`:init-fn acme.my-app.core/init`.** Called once at bundle load; must be exported (`(defn ^:export init [] …)`) and is the fn that calls `(rf/init! rf.adapter.reagent/adapter)`. The symbol is yours as long as `:init-fn` points at it — see [`entry-namespace.md`](entry-namespace.md).
4. **One module.** A single-page app needs exactly one `:modules` entry; `:output-dir "resources/public/js"` + `:asset-path "/js"` put the bundle where `index.html`'s `<script src="/js/main.js">` expects it. Rename one, rename all three.
5. **`:source-paths ["src" "test"]`.** Both trees are on the compile classpath so the `:test` build sees `test/`; `:app` only ever pulls what `core.cljs` requires.

The build id (`:app`) is the name for `shadow-cljs watch <build-id>`; `:app` is convention.

## The `index.html` that loads the bundle

A re-frame2 app needs a page that loads the compiled JS and has a mount point. Three contractual bits:

- **`<main id="app"></main>`** — the mount point. Whatever id you use here, `core.cljs` must call `(js/document.getElementById "<same-id>")`; by convention it is `"app"`. A mismatch is the most common cause of a blank page with no console error.
- **`<script src="/js/main.js">`** — `/js/` comes from `:asset-path "/js"`, `main.js` from the module name `:main`. The absolute path from site root is correct for shadow's dev server. It sits at the bottom of `<body>` so the DOM exists when `init` runs.
- **`<link rel="stylesheet" href="/css/app.css">`** — four starter rules; the counter's `[:span {:style …}]` inline style needs nothing from it.

The scaffold ships **no Content-Security-Policy**. If you add one to the dev page later, `script-src` must admit `'unsafe-eval'` — shadow's dev build loads every namespace through `goog.globalEval`, and without it the first run is a blank page — and `connect-src` must admit `ws:` for the hot-reload socket. A production policy is a response header on your host, out of this skill's scope.

## Hot reload (`^:dev/after-load`)

**`^:dev/after-load` is what makes a reload repaint — `:init-fn` is not.** shadow-cljs calls the module `:init-fn` **once**, when the bundle loads. A hot reload loads the new code and then calls the build's `^:dev/after-load` hooks; it does not call `:init-fn` again. So `core.cljs` splits the entry in two — the one-time boot ceremony in `init`, and a `^:dev/after-load mount!` that re-renders the edited views into the React root held in a `defonce`. Leave the hook out and shadow logs `reloading code but no :after-load hooks are configured!` while the page keeps painting the old view. Full lifecycle → [`entry-namespace.md` §Order of operations](entry-namespace.md#order-of-operations).

re-frame2's core needs no preload and no `:devtools` block for any of this — shadow's own reload pipeline plus the entry ns's hook is the whole mechanism. `npx shadow-cljs watch app` starts the dev server (use `npx` — or `npm run watch` — so the project-local `shadow-cljs` resolves with no global binary on PATH); visit `http://localhost:8280/` and the page repaints on every recompile.

## The `:test` build (`npm test`)

`:test {:target :node-test :output-to "out/node-test.js" :ns-regexp "-test$"}` compiles every `*_test.cljs` under `test/` into one Node bundle; `npm test` runs `shadow-cljs compile test && node out/node-test.js`. The scaffold's `events_test.cljs` exercises the two handlers and the sub on the plain-atom substrate — no DOM, no browser — and is the template's starter, not something this skill authors: writing the author's tests is the `re-frame2` skill's job.

## `.gitignore` — what the build generates

The scaffold's `.gitignore` keeps the first commit free of regenerable output: the compiled bundle (`/resources/public/js/`), shadow's caches (`/.shadow-cljs/`), the Node test bundle (`/out/`), `/target/`, the clojure CLI's classpath cache (`/.cpcache/`), `/node_modules/`, and editor / OS noise. `/resources/public/js/` is ignored but the rest of `resources/public/` (your `index.html` + `css/app.css`) is committed — the ignore is scoped to the compiled-JS subdir, not the whole serve root.

## Production build (`release`)

`npm run release` (`shadow-cljs release app`) produces an optimised `:advanced` bundle at the same `resources/public/js/main.js`; serve `resources/public/` from any static host. No re-frame2-specific config: re-frame2's `:advanced`-compile elision contract (Spec 009) strips the dev-time machinery — `trace`, `epoch-history`, the registration diagnostics — when `goog.DEBUG` is false, which it is under `:advanced`. What the framework relies on to keep a promise of its own (the schema boundary interceptor, a route's declared shape, a recordable coeffect's contract) survives — none of it is in this scaffold yet.

## nREPL — only if you'll use `re-frame2-pair`

If the author plans to attach `re-frame2-pair` (the live-inspection skill) to the running app, shadow-cljs's dev build needs nREPL enabled. shadow-cljs **enables nREPL by default** when you run `shadow-cljs watch <build>` — no config change required. The port is written to `.shadow-cljs/nrepl.port` (or `target/shadow-cljs/nrepl.port` on older versions), which is where `re-frame2-pair`'s `discover-app` MCP tool looks for it. To pin the port explicitly (e.g. for editor integrations), add a top-level `:nrepl {:port 7002}` to `shadow-cljs.edn`. Not required for greenfield.

**Dev-only — bind to localhost.** nREPL is a remote-evaluation surface; in development always leave it bound to `localhost` (the shadow-cljs default). Never expose the nREPL port on `0.0.0.0` or a public interface — anything that can connect can evaluate arbitrary code in the running JVM.

## What re-frame2 does NOT need

Things you might pull in by reflex from other CLJS framework setups that re-frame2 does not require:

- **No preload, no `:devtools` block** — core has no preload analogue to re-frame v1, and hot reload is the `^:dev/after-load` hook above. Xray, the in-app devtools panel, is the one optional preload, attached later by its own recipe (the generated README's *Next steps* links it).
- **No `:closure-defines`** for re-frame2 in dev — the one exception is opting into the performance-API instrumentation (Spec 009), `:compiler-options {:closure-defines {re-frame.performance/enabled? true}}`, only if the author asks.
- **No special compiler options** for dev. `{:compiler-options {:warnings {...}}}` is up to the author.
- **No SSR build entry** unless the author wants SSR — opt-in via `day8/re-frame2-ssr` (a separate `:target :node-script` build). Out of scope for greenfield.
