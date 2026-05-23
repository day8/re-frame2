# shadow-cljs

The minimal `shadow-cljs.edn` build for a greenfield re-frame2 Reagent single-page app, and the matching `index.html`.

## Contents

- Minimal `shadow-cljs.edn`
- The `index.html` that loads the bundle
- `:devtools` block (optional, for hot-reload)
- Production build (`release`)
- nREPL — only if you'll use `re-frame2-pair`
- What re-frame2 does NOT need

---

## Minimal `shadow-cljs.edn`

```clojure
{:deps   {:aliases [:shadow]}           ;; pull classpath from deps.edn's :shadow alias
 :source-paths ["src"]

 :dev-http {8280 "resources/public"}    ;; dev server: serve resources/public on :8280

 :builds
 {:app
  {:target     :browser
   :output-dir "resources/public/js"
   :asset-path "/js"
   :modules    {:main {:init-fn your-app.core/init}}}}}
```

The greenfield build entry, matching the generator template. Four things matter for re-frame2:

1. **`:deps {:aliases [:shadow]}`.** shadow-cljs reads the classpath from `deps.edn` via the `:shadow` alias — don't duplicate the `day8/re-frame2-*` coordinates here. The alias is where the JVM-side build deps live: it supplies `thheller/shadow-cljs` + `org.clojure/tools.namespace` and `:extra-paths ["test" "dev"]`. The template defines it in `deps.edn`:
   ```clojure
   :aliases
   {:shadow
    {:extra-paths ["test" "dev"]
     :extra-deps  {thheller/shadow-cljs        {:mvn/version "<shadow-version>"}
                   org.clojure/tools.namespace {:mvn/version "1.5.0"}}
     :main-opts   ["-m" "shadow.cljs.devtools.cli"]}}
   ```
2. **`:dev-http {8280 "resources/public"}`.** The top-level dev-server form (current shadow-cljs idiom). It serves `resources/public/` on port `8280` — `index.html` lives at `resources/public/index.html`. The template uses `8280`; reuse it so a teammate on the template route sees the same number.
3. **`:init-fn your-app.core/init`.** shadow-cljs calls this symbol at bundle-init time. The function must be exported (`(defn ^:export init [] ...)`). This is the entry point that calls `(rf/init! reagent-adapter/adapter)` — see `entry-namespace.md`. (`init` matches the generator template; the symbol name is yours to choose, as long as `:init-fn` points at it — see `entry-namespace.md`.)
4. **One module.** A single-page re-frame2 app needs exactly one `:modules` entry. Code-splitting is possible later but not part of greenfield.

The template also ships a second `:test {:target :node-test ...}` build under `:builds` (for `cljs.test` runners) and `:source-paths ["src" "test" "dev"]`. That test build is out of scope for this skill (it stops at "the counter mounts"), but it's there in the scaffold — don't be surprised comparing the two.

The build id (`:app` above) is the name you give the build for `shadow-cljs watch <build-id>`. Use anything that reads naturally; `:app` is convention.

## The `index.html` that loads the bundle

A re-frame2 app needs an HTML page that loads the compiled JS and has a mount point. Drop this at `resources/public/index.html` (matching `:dev-http`'s serve root), with the styles in an external `resources/public/css/app.css`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <!-- Strict default-safe CSP: same-origin JS/CSS only, no inline
       script/style. The template ships this; serve the same policy
       via a response header in production. -->
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'">
  <title>your-app — re-frame2</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
  <div class="rf2-app-shell">
    <aside class="rf2-causa-host" data-rf-causa-host></aside>
    <main id="app"></main>
  </div>
  <script src="/js/main.js"></script>
</body>
</html>
```

`resources/public/css/app.css` (the minimal layout — three rules carry the host-column contract):

```css
body { font: 16px/1.4 system-ui, sans-serif; margin: 0; }
.rf2-app-shell { display: flex; min-height: 100vh; }
.rf2-causa-host { flex: 0 0 420px; min-width: 320px; }
#app { flex: 1; min-width: 0; padding: 2em; }
```

Five contractual bits:

- **`<main id="app"></main>`** — the app mount point. Whatever id you use here, the entry ns must call `(js/document.getElementById "<same-id>")`. By convention it's `"app"`.
- **`<aside class="rf2-causa-host" data-rf-causa-host></aside>`** — Causa's default true-inline devtools host, the **left** layout column beside `#app`. Order it **first** in the DOM (`<aside>` then `<main>`) so flex flow places Causa on the left, matching the template. When `day8.re-frame2-causa.preload` is enabled Causa auto-opens into this host; if it's missing, Causa logs an actionable diagnostic, also reachable via `window.day8.re_frame2_causa.status()`.
- **`.rf2-causa-host` flex CSS** — the host app owns sizing. The contract is a fixed-width column (`flex: 0 0 420px; min-width: 320px`) and an app region that can shrink (`#app { flex: 1; min-width: 0 }`). Causa injects its own drag handle on the panel's outer edge (persisted across reloads, double-click to reset), so the consumer's CSS stays this minimal. To change the default width, edit `flex-basis` here; the full resize-affordance / theming surface is the `re-frame2-causa` skill's territory, not greenfield's.
- **CSP + external stylesheet.** The CSP meta tag declares `style-src 'self'`, which blocks inline `<style>`/`style=` attributes — so styles live in `css/app.css`, not inline. Keep them out of the HTML to stay CSP-clean (and matching the template).
- **`<script src="/js/main.js">`** — `/js/` comes from `:asset-path "/js"`; `main.js` comes from the module name `:main`. If you rename either, this path follows. The absolute path from site root is correct for shadow-cljs's dev server.

## `:devtools` block (hot-reload + Causa)

The top-level `:dev-http` (above) already starts the dev server. Add a `:devtools` block per build for hot-reload + the Causa devtools panel — both wired this way in the generator template:

```clojure
:builds
{:app
 {:target     :browser
  :output-dir "resources/public/js"
  :asset-path "/js"
  :modules    {:main {:init-fn your-app.core/init}}
  :devtools   {:after-load your-app.core/init
               :preloads   [day8.re-frame2-causa.preload]}}}
```

- `:after-load your-app.core/init` — re-run the entry fn after each hot reload so the freshly-loaded code re-installs the adapter and re-renders. **Note:** if `init` also runs `(rf/dispatch-sync [:your-app/initialise])` (the seed event — see `entry-namespace.md`), `:after-load` re-runs that seed on **every** hot reload, resetting `app-db` to its initial state each save. For a counter that means the count jumps back to 0 on every reload. If preserving in-progress state across reloads matters, point `:after-load` at a separate fn that re-renders **without** re-seeding (calls `rdc/render` but not `dispatch-sync`).
- `:preloads [day8.re-frame2-causa.preload]` — loads the Causa in-app devtools panel in dev/watch builds. `:preloads` (and the whole `:devtools` block) are cut from `release` builds automatically, so Causa never ships to production.
- The dev server itself comes from the top-level `:dev-http {8280 "resources/public"}` (not from a `:http-port`/`:http-root` inside `:devtools` — that's the older style; the template uses the top-level `:dev-http` form).

With this block in place, `shadow-cljs watch app` starts the dev server. Visit `http://localhost:8280/` and the browser auto-refreshes on every recompile.

re-frame2's *core* does not need a preload for hot-reload — shadow-cljs's default behaviour is enough. **Causa is the one default preload:** because Causa is a day-one dep (see `deps-versions.md`), the template wires `day8.re-frame2-causa.preload` here. It auto-opens into the `[data-rf-causa-host]` column from the `index.html` above after `rf/init!` installs the substrate adapter (there is no lazy/manual-only launch step). If you genuinely want a Causa-free build, drop the `:preloads` entry and the `day8/re-frame2-causa` dep together.

## Production build (`release`)

`shadow-cljs release app` produces an optimised bundle. No re-frame2-specific config needed.

re-frame2's `:advanced`-compile elision contract (Spec 009) automatically strips dev-only diagnostics (`trace`, `epoch-history`, schema validation at boundary) when `goog.DEBUG` is false — which it is under `:advanced`. The author gets the elision for free; nothing to configure.

## nREPL — only if you'll use `re-frame2-pair`

If the author plans to attach `re-frame2-pair` (the live-inspection skill) to the running app, shadow-cljs's dev build needs nREPL enabled. shadow-cljs **enables nREPL by default** when you run `shadow-cljs watch <build>` — no config change required. The port is written to `target/shadow-cljs/nrepl.port` (or `.shadow-cljs/nrepl.port` depending on version), which is where `re-frame2-pair`'s `discover-app.sh` looks for it.

If you want to pin the port explicitly (e.g. for editor integrations), add a top-level `:nrepl {:port 7002}` to `shadow-cljs.edn`. Not required for greenfield.

**Dev-only — bind to localhost.** nREPL is a remote-evaluation surface; in development always leave it bound to `localhost` (the shadow-cljs default). Never expose the nREPL port on `0.0.0.0` or a public interface — anything that can connect can evaluate arbitrary code in the running JVM.

## What re-frame2 does NOT need

A few things you might pull in by reflex from other CLJS framework setups that re-frame2 specifically does not require:

- **No *framework* preload required** — re-frame2's core has no preload analogue to re-frame v1. The one default preload is Causa's devtools (`day8.re-frame2-causa.preload`, wired in `:devtools/preloads` — see the `index.html` section above for the host column it opens into).
- **No `:closure-defines`** for re-frame2 itself in dev. The single exception is opting into the performance-API instrumentation (Spec 009 §Performance instrumentation) — add `:compiler-options {:closure-defines {re-frame.performance/enabled? true}}` to the build only if the author asks for it explicitly. Default dev is fine.
- **No special compiler options** for dev. `{:compiler-options {:warnings {...}}}` is up to the author.
- **No SSR build entry** unless the author wants SSR. SSR is opt-in via `day8/re-frame2-ssr` (separate per-feature artefact); the SSR build is a separate `:target :node-script` (or `:target :browser` running in a static-render harness). Out of scope for greenfield.
