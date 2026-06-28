# shadow-cljs build & index.html

The minimal `shadow-cljs.edn` build for a greenfield re-frame2 Reagent single-page app, and the matching `index.html`.

## Contents

- The day-one `shadow-cljs.edn`
- The `index.html` that loads the bundle
- `:devtools` block (the Xray preload + the `:init-fn` hot-reload lifecycle)
- `.gitignore` — what the build generates
- Production build (`release`)
- nREPL — only if you'll use `re-frame2-pair`
- What re-frame2 does NOT need

---

## The day-one `shadow-cljs.edn`

The complete default greenfield build, matching the generator template. The `:devtools {:preloads [day8.re-frame2-xray.preload]}` entry is **part of the day-one shape** — Xray is a day-one dep (`deps-versions.md`) and the `index.html` below ships the `[data-rf-xray-host]` column it opens into. Omitting the preload while keeping the dep + host markup is a false-green scaffold (compiling app, permanently-empty right-side host). The dedicated `:devtools` section below explains the block and the `:init-fn` hot-reload lifecycle.

```clojure
{:deps   {:aliases [:shadow]}           ;; pull classpath from deps.edn's :shadow alias
 :source-paths ["src"]

 :dev-http {8280 "resources/public"}    ;; dev server: serve resources/public on :8280

 :builds
 {:app
  {:target     :browser
   :output-dir "resources/public/js"
   :asset-path "/js"
   :modules    {:main {:init-fn your-app.core/init}}
   :devtools   {:preloads [day8.re-frame2-xray.preload]}}}}  ;; day-one Xray panel — fills [data-rf-xray-host]
```

Five things matter for re-frame2:

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
3. **`:init-fn your-app.core/init`.** shadow-cljs calls this symbol at bundle-init time; it must be exported (`(defn ^:export init [] ...)`) and is the entry point that calls `(rf/init! reagent-adapter/adapter)`. `init` matches the generator template; the symbol name is yours, as long as `:init-fn` points at it — see `entry-namespace.md`.
4. **One module.** A single-page re-frame2 app needs exactly one `:modules` entry. Code-splitting is possible later but not part of greenfield.
5. **`:devtools {:preloads [day8.re-frame2-xray.preload]}`.** The day-one Xray devtools preload — auto-mounts Xray into `index.html`'s `[data-rf-xray-host]` column once `rf/init!` seats the adapter. Dev-only: shadow cuts `:devtools` (preloads and all) from `release` builds automatically, so Xray never ships to production. The dedicated [`:devtools` section](#devtools-block-the-xray-preload--the-init-fn-hot-reload-lifecycle) below covers this block and the `:init-fn` hot-reload lifecycle.

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
  <!-- Development-flavoured CSP, tuned to the RUNTIME this scaffold
       actually produces — NOT an aspirational strict baseline. This
       meta tag matches the generator template's index.html exactly.
       Three deliberate choices:

       - `style-src 'self' 'unsafe-inline'` — re-frame2 views routinely
         use inline `:style` props (the counter's [:span {:style ...}]
         below is one), and the default-on Xray devtools surface injects
         <style> blocks and inline styles. A strict `style-src 'self'`
         would emit CSP violations on the first page and block Xray's
         styling. Keep `'unsafe-inline'` for dev; tighten it in
         production only after externalising every inline style (see
         "Production hardening" below).
       - `connect-src 'self' ws: wss:` admits shadow-cljs's dev
         hot-reload websocket (it can route to a different port than the
         page, which 'self' alone would block).
       - NO `frame-ancestors` here. Browsers IGNORE `frame-ancestors`
         delivered via a <meta> tag — it only takes effect from a
         response header. Putting it in the meta tag would imply
         anti-clickjacking protection the page does not actually have.
         The anti-clickjacking contract is a production response-header
         concern (see "Production hardening" below).

       `script-src 'self'` stays strict in both dev and production — the
       scaffold has no inline <script>. -->
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'">
  <title>your-app — re-frame2</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
  <div class="rf2-app-shell">
    <main id="app"></main>
    <aside class="rf2-xray-host" data-rf-xray-host></aside>
  </div>
  <script src="/js/main.js"></script>
</body>
</html>
```

`resources/public/css/app.css` (the minimal layout — the four rules below carry the right-side host-column contract, plus the `:empty` collapse that keeps release builds gutter-free):

```css
:root { --rf-xray-accent: #539bf5; } /* Xray brand-accent var (published default — host chrome may read it) */
body { font: 16px/1.4 system-ui, sans-serif; margin: 0; }
.rf2-app-shell { display: flex; min-height: 100vh; }
.rf2-xray-host {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;            /* the 1px border-left lives inside the width */
  border-left: 1px solid #2a2a2a;    /* visual separator on the app side */
}
.rf2-xray-host:empty { display: none; }
#app { flex: 1; min-width: 0; padding: 2em; }
```

Six contractual bits:

- **`<main id="app"></main>`** — the app mount point. Whatever id you use here, the entry ns must call `(js/document.getElementById "<same-id>")`. By convention it's `"app"`. Order it **first** in the DOM (`<main>` then `<aside>`) so flex flow places the app on the left and Xray on the right.
- **`<aside class="rf2-xray-host" data-rf-xray-host></aside>`** — Xray's default true-inline devtools host, the **right** layout column beside `#app` (DOM order: `<main>` first, `<aside>` second — flex flow lays the aside to the right of the app). When `day8.re-frame2-xray.preload` is enabled Xray auto-opens into this host; if it's missing, Xray logs an actionable diagnostic, also reachable via `window.day8.re_frame2_xray.status()`. This matches Xray's published layout-host contract ([`tools/xray/spec/011-Launch-Modes.md`](../../../tools/xray/spec/011-Launch-Modes.md) §Layout host contract).
- **`.rf2-xray-host` flex CSS** — the host app owns sizing. The contract reads a single CSS custom property for its width — `flex: 0 0 var(--rf-xray-inline-width, 560px)` — with `min-width: 320px`, `box-sizing: border-box` (so the 1px `border-left` lives *inside* the documented width rather than 1px beyond it), and `border-left: 1px solid #2a2a2a` as the visual separator on the app side; the app region shrinks to fill the rest (`#app { flex: 1; min-width: 0 }`). Xray injects its own drag handle on the panel's outer edge (persisted across reloads, double-click to reset), so the consumer's CSS stays this minimal. **To change the default width, do NOT hard-code a different `flex-basis` — override the `--rf-xray-inline-width` property anywhere up the cascade** (e.g. `:root { --rf-xray-inline-width: 720px; }` or a per-route `.debug-route { --rf-xray-inline-width: 960px; }`). A literal `flex-basis` would ignore the property the drag handle and any cascade override write. The published default (`560px`) and the property/accent names are surfaced as `day8.re-frame2-xray.config/default-layout-host-width` / `default-layout-host-css-var` / `default-accent-css-var`. The full resize-affordance / theming surface is the `re-frame2-xray` skill's territory, not greenfield's.
- **`--rf-xray-inline-width` is the resize knob** — the contract is **JS-free and host-owned**: Xray never *sets* this property from CLJS, so the host's stylesheet is the single source of truth for the initial width and any cascade override. (The drag handle, per the bullet above, writes the same flex-basis slot for user resize gestures.)
- **`.rf2-xray-host:empty { display: none; }`** — load-bearing in **release** builds. The preload that mounts Xray into the aside is dev-only (cut from `shadow-cljs release` and `goog.DEBUG`-gated), so in production the `<aside>` stays empty. Without this rule the empty aside still reserves its `flex-basis` column above and ships a permanent blank gutter beside `#app`. The `:empty` collapse makes the column disappear when Xray isn't there, so `#app` spans the full viewport. Keep it.
- **CSP — dev meta tag, `'unsafe-inline'` styles.** Full rationale is in the meta-tag comment above. In brief: `style-src 'self' 'unsafe-inline'` is **deliberate and load-bearing in dev** — re-frame2 views use inline `:style` props (the counter's `[:span {:style {:margin "0 1em"}}]`) and Xray injects `<style>` blocks, so strict `style-src 'self'` would break both. The meta tag **omits `frame-ancestors`** (browsers ignore it from `<meta>` — response-header-only). Both tightenings (drop `'unsafe-inline'`, add `frame-ancestors`) belong to the production response header — see **Production hardening** below.
- **`<script src="/js/main.js">`** — `/js/` comes from `:asset-path "/js"`; `main.js` comes from the module name `:main`. If you rename either, this path follows. The absolute path from site root is correct for shadow-cljs's dev server.

## `:devtools` block (the Xray preload + the `:init-fn` hot-reload lifecycle)

The day-one build already carries `:devtools {:preloads [day8.re-frame2-xray.preload]}` (canonical block at the top of this file). This section explains that preload and the `:init-fn` hot-reload lifecycle, shown in context:

```clojure
:builds
{:app
 {:target     :browser
  :output-dir "resources/public/js"
  :asset-path "/js"
  :modules    {:main {:init-fn your-app.core/init}}
  :devtools   {:preloads [day8.re-frame2-xray.preload]}}}
```

- **`:init-fn` runs at startup AND re-runs after each hot reload.** shadow-cljs's `:browser` target wires the module `:init-fn` as both the initial entry (called once at module load / page load) and the default after-load hook — so a plain code reload (you save a `.cljs` file, shadow recompiles and ships the new code) **re-invokes** `:init-fn` automatically. You do **not** need a separate `^:dev/after-load` hook to get a clean re-render on save; the module init re-running is what re-renders the tree. (See the shadow-cljs [User's Guide §Lifecycle hooks](https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks): the `:browser` module `:init-fn` is the module entry, called on the initial load and again after every hot reload.) This is exactly why the scaffold's React root is held in a `defonce` (see [`entry-namespace.md` §Reagent root](entry-namespace.md)) — `init` re-runs on every save, so `create-root` must be created once and reused, not re-created each reload.

  **What re-runs vs what survives.** Because `init` re-runs on every reload, anything it does runs again each save: `rf/init!` (idempotent — re-installs the adapter config only when none is seated, creates no frame), `reg-frame` on the same id (an idempotent in-place frame update), and crucially the explicit `(rf/dispatch-sync [:counter/initialise])` seed. That `dispatch-sync` seed **is the reset boundary**: it re-writes the starter app-db on every reload, so editing the `:counter/initialise` handler (or removing the `dispatch-sync` call) is what changes what a reload re-seeds. If you want demo state to *persist* across reloads instead, move the seed out of the per-reload init path (e.g. guard it so it only fires on first boot) — leaving it in init means a deliberate reset every save, which is the right default for greenfield iteration. (Keep this wording in lockstep with the generator template's own README §Hot reload, which teaches the same `:init-fn` = startup-and-after-load / `dispatch-sync` = reset-boundary lifecycle.)
- `:preloads [day8.re-frame2-xray.preload]` — loads the Xray in-app devtools panel in dev/watch builds. `:preloads` (and the whole `:devtools` block) are cut from `release` builds automatically, so Xray never ships to production.
- The dev server itself comes from the top-level `:dev-http {8280 "resources/public"}` — not from a `:http-port`/`:http-root` inside `:devtools`. Use the top-level `:dev-http` form, matching the template.

With this block in place, `npx shadow-cljs watch app` starts the dev server (use `npx` — or `npm run watch` — so the locally-installed `shadow-cljs` from `node_modules/.bin` resolves even with no global binary on PATH). Visit `http://localhost:8280/` and the browser auto-refreshes on every recompile.

re-frame2's *core* does not need a preload for hot-reload — shadow-cljs's default behaviour is enough. **Xray is the one default preload**, wired here because it is a day-one dep; it auto-opens into the `index.html` `[data-rf-xray-host]` column after `rf/init!` seats the adapter (no lazy/manual-only launch step). The default day-one scaffold keeps it.

**Explicit Xray opt-out (the only no-Xray path).** If you genuinely want a Xray-free build, opt out by removing **all three** pieces together — they are one contract:

1. the `:devtools {:preloads [day8.re-frame2-xray.preload]}` entry from the build (above);
2. the `day8/re-frame2-xray` dep from `deps.edn` (`deps-versions.md`); and
3. the `<aside ... data-rf-xray-host>` host column from `index.html` (and its `.rf2-xray-host` CSS rules).

There is no "keep the dep, drop the preload" half-state — that is the false-green the default block avoids (compiling app, permanently-empty Xray host). Xray is in or out as a unit.

## `.gitignore` — what the build generates

A brand-new project needs a `.gitignore`, or the first commit drags in every generated artefact: the compiled bundle (`:output-dir "resources/public/js"`), shadow's caches, `node_modules/`, the clojure CLI's classpath cache. All regenerable. Drop this at the project root (a subset of the generator template's `.gitignore`, scoped to what the manual route emits):

```gitignore
# Generated build outputs
/.shadow-cljs/
/resources/public/js/
/out/
/target/

# Node
/node_modules/
npm-debug.log

# clojure CLI
/.cpcache/

# Editors / OS
/.idea/
/.lsp/
/.clj-kondo/.cache/
.DS_Store
Thumbs.db
```

`/resources/public/js/` is ignored but the rest of `resources/public/` (your `index.html` + `css/app.css`) is committed — the ignore is scoped to the compiled-JS subdir, not the whole serve root. If you later add the lint aliases from the template's `deps.edn`, the full template `.gitignore` adds a couple more entries; this minimal set covers the seven-step scaffold.

## Production build (`release`)

`shadow-cljs release app` produces an optimised bundle. No re-frame2-specific config needed.

re-frame2's `:advanced`-compile elision contract (Spec 009) automatically strips dev-only diagnostics (`trace`, `epoch-history`, schema validation at boundary) when `goog.DEBUG` is false — which it is under `:advanced`. The author gets the elision for free; nothing to configure.

### Production hardening — serve CSP as a response header

The `index.html` meta CSP above is **development-flavoured**. For production, **prefer setting CSP as a real response header** on your server, not via the meta tag: meta-tag CSPs are evaluated late, some directives (`frame-ancestors`) are ignored entirely when delivered via `<meta>`, and an upstream proxy can strip a meta CSP.

The stricter production header tightens three things relative to the dev meta tag — **drops `'unsafe-inline'`** (only after you have externalised every inline `:style` prop into `css/app.css` classes; the dev-only Xray preload is already cut from `release` builds, so it isn't a concern there), **drops `ws: wss:`** (no hot-reload in production), and **adds `frame-ancestors 'none'`** (this is where anti-clickjacking actually takes effect — *not* the meta tag):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
```

If you call a cross-origin API, widen `connect-src` to that origin (`connect-src 'self' https://api.example.com`) rather than dropping it back to `'unsafe-inline'`/`*`. If you have not externalised inline styles, keep `style-src 'self' 'unsafe-inline'` in the production header too — but prefer a nonce/hash over wholesale `'unsafe-inline'` for a public deployment. The generator template's `README.md` §Production hardening carries the full nginx / Caddy server-block examples and the rationale; this skill's job is just to flag that the dev meta tag is not the production policy.

## nREPL — only if you'll use `re-frame2-pair`

If the author plans to attach `re-frame2-pair` (the live-inspection skill) to the running app, shadow-cljs's dev build needs nREPL enabled. shadow-cljs **enables nREPL by default** when you run `shadow-cljs watch <build>` — no config change required. The port is written to `target/shadow-cljs/nrepl.port` (or `.shadow-cljs/nrepl.port` depending on version), which is where `re-frame2-pair`'s `discover-app.sh` looks for it.

If you want to pin the port explicitly (e.g. for editor integrations), add a top-level `:nrepl {:port 7002}` to `shadow-cljs.edn`. Not required for greenfield.

**Dev-only — bind to localhost.** nREPL is a remote-evaluation surface; in development always leave it bound to `localhost` (the shadow-cljs default). Never expose the nREPL port on `0.0.0.0` or a public interface — anything that can connect can evaluate arbitrary code in the running JVM.

## What re-frame2 does NOT need

Things you might pull in by reflex from other CLJS framework setups that re-frame2 does not require:

- **No *framework* preload required** — re-frame2's core has no preload analogue to re-frame v1. The one default preload is Xray's devtools (`day8.re-frame2-xray.preload`, in `:devtools/preloads`).
- **No `:closure-defines`** for re-frame2 itself in dev. The single exception is opting into the performance-API instrumentation (Spec 009 §Performance instrumentation) — add `:compiler-options {:closure-defines {re-frame.performance/enabled? true}}` to the build only if the author asks for it explicitly. Default dev is fine.
- **No special compiler options** for dev. `{:compiler-options {:warnings {...}}}` is up to the author.
- **No SSR build entry** unless the author wants SSR. SSR is opt-in via `day8/re-frame2-ssr` (separate per-feature artefact); the SSR build is a separate `:target :node-script` (or `:target :browser` running in a static-render harness). Out of scope for greenfield.
