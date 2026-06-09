# shadow-cljs build & index.html

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
:root { --rf-xray-accent: #7C5CFF; } /* Xray brand-accent var — host chrome may read it */
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
- **`<aside class="rf2-xray-host" data-rf-xray-host></aside>`** — Xray's default true-inline devtools host, the **right** layout column beside `#app` (DOM order: `<main>` first, `<aside>` second — flex flow lays the aside to the right of the app). When `day8.re-frame2-xray.preload` is enabled Xray auto-opens into this host; if it's missing, Xray logs an actionable diagnostic, also reachable via `window.day8.re_frame2_xray.status()`. This matches Xray's published layout-host contract (`spec/011-Launch-Modes.md` §Layout host contract).
- **`.rf2-xray-host` flex CSS** — the host app owns sizing. The contract reads a single CSS custom property for its width — `flex: 0 0 var(--rf-xray-inline-width, 560px)` — with `min-width: 320px`, `box-sizing: border-box` (so the 1px `border-left` lives *inside* the documented width rather than 1px beyond it), and `border-left: 1px solid #2a2a2a` as the visual separator on the app side; the app region shrinks to fill the rest (`#app { flex: 1; min-width: 0 }`). Xray injects its own drag handle on the panel's outer edge (persisted across reloads, double-click to reset), so the consumer's CSS stays this minimal. **To change the default width, do NOT hard-code a different `flex-basis` — override the `--rf-xray-inline-width` property anywhere up the cascade** (e.g. `:root { --rf-xray-inline-width: 720px; }` or a per-route `.debug-route { --rf-xray-inline-width: 960px; }`). A literal `flex-basis` would ignore the property the drag handle and any cascade override write. The published default (`560px`) and the property/accent names are surfaced as `day8.re-frame2-xray.config/default-layout-host-width` / `default-layout-host-css-var` / `default-accent-css-var`. The full resize-affordance / theming surface is the `re-frame2-xray` skill's territory, not greenfield's.
- **`--rf-xray-inline-width` is the resize knob** — the contract is **JS-free and host-owned**: Xray never *sets* this property from CLJS; the host's stylesheet is the single source of truth for the initial width and any cascade override. Xray's auto-injected drag handle writes the same flex-basis slot for explicit user resize gestures and persists it across reloads. The `560px` default in the `var(...)` fallback matches Xray's published `default-layout-host-width`.
- **`.rf2-xray-host:empty { display: none; }`** — load-bearing in **release** builds. The preload that mounts Xray into the aside is dev-only (cut from `shadow-cljs release` and `goog.DEBUG`-gated), so in production the `<aside>` stays empty. Without this rule the empty aside still reserves its `flex-basis` column above and ships a permanent blank gutter beside `#app`. The `:empty` collapse makes the column disappear when Xray isn't there, so `#app` spans the full viewport. Keep it.
- **CSP — dev meta tag, `'unsafe-inline'` styles.** The CSP meta tag declares `style-src 'self' 'unsafe-inline'` (matching the template). The `'unsafe-inline'` is **deliberate and load-bearing in dev**: re-frame2 views use inline `:style` props (the counter's `[:span {:style {:margin "0 1em"}}]`) and the default-on Xray devtools injects `<style>` blocks and inline styles — a strict `style-src 'self'` would emit CSP violations and break Xray styling on first run. The page chrome (the shell/host layout) lives in `css/app.css` regardless; only the per-component `:style` props need `'unsafe-inline'`. The meta tag deliberately **omits `frame-ancestors`** (browsers ignore it from `<meta>` — it is a response-header-only directive). Both tightenings — dropping `'unsafe-inline'` and adding `frame-ancestors` — belong to the production response header (see **Production hardening** below).
- **`<script src="/js/main.js">`** — `/js/` comes from `:asset-path "/js"`; `main.js` comes from the module name `:main`. If you rename either, this path follows. The absolute path from site root is correct for shadow-cljs's dev server.

## `:devtools` block (hot-reload + Xray)

The top-level `:dev-http` (above) already starts the dev server. Add a `:devtools` block per build for the Xray devtools panel — wired this way in the generator template:

```clojure
:builds
{:app
 {:target     :browser
  :output-dir "resources/public/js"
  :asset-path "/js"
  :modules    {:main {:init-fn your-app.core/init}}
  :devtools   {:preloads [day8.re-frame2-xray.preload]}}}
```

- **`:init-fn` is a one-time startup hook — it is NOT the hot-reload hook.** shadow-cljs's `:browser` target calls the module `:init-fn` **once**, at initial module load / page load. A plain code reload (you save a `.cljs` file, shadow recompiles and ships the new code) does **not** re-run `:init-fn` — by design, so a top-level `(init)` isn't re-executed on every save (this is exactly why `:init-fn` exists rather than a bare top-level call; see the shadow-cljs [User's Guide §Lifecycle hooks](https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks)). The explicit re-render-after-reload hook is **`^:dev/after-load`** (a metadata tag on a zero-arg fn) or, equivalently, a `:devtools {:after-load your-app.core/render!}` entry in the build. If you set *neither*, shadow swaps the freshly-compiled code into the running page but runs no hook — React does not necessarily re-render until the next state change, so edits to view code can look stale until you change state or do a full refresh.

  **The greenfield recipe — the simplest scaffold, with its limit stated.** This minimal counter ships **no** `^:dev/after-load` hook (matching the smallest scaffold). The cost: after editing view code you may need a state change (click `+1`) or a full page refresh to see it; the live tree won't necessarily re-render on a bare code reload. That trade is fine for greenfield. When you want reliable hot reload, add a separate render fn and an after-load hook that re-renders **without** re-seeding app-db:

  ```clojure
  ;; in your-app.core
  (defn ^:dev/after-load render! []
    (rdc/render react-root [counter-app]))   ;; re-render only — no rf/init!, no dispatch-sync
  ```

  Keep `(rf/init! …)` and the `(rf/dispatch-sync [:counter/initialise])` seed in `init` (the one-time startup path); the after-load hook only re-renders, so app-db state survives the reload instead of resetting to its seed on every save. (Coordinate this wording with the generator template's own hot-reload notes — see rf2-8n4s71 — so the template docs and this skill teach the same `:init-fn` = startup / `^:dev/after-load` = reload-hook lifecycle.)
- `:preloads [day8.re-frame2-xray.preload]` — loads the Xray in-app devtools panel in dev/watch builds. `:preloads` (and the whole `:devtools` block) are cut from `release` builds automatically, so Xray never ships to production.
- The dev server itself comes from the top-level `:dev-http {8280 "resources/public"}` (not from a `:http-port`/`:http-root` inside `:devtools` — that's the older style; the template uses the top-level `:dev-http` form).

With this block in place, `npx shadow-cljs watch app` starts the dev server (use `npx` — or `npm run watch` — so the locally-installed `shadow-cljs` from `node_modules/.bin` resolves even with no global binary on PATH). Visit `http://localhost:8280/` and the browser auto-refreshes on every recompile.

re-frame2's *core* does not need a preload for hot-reload — shadow-cljs's default behaviour is enough. **Xray is the one default preload:** because Xray is a day-one dep (see `deps-versions.md`), the template wires `day8.re-frame2-xray.preload` here. It auto-opens into the `[data-rf-xray-host]` column from the `index.html` above after `rf/init!` installs the substrate adapter (there is no lazy/manual-only launch step). If you genuinely want a Xray-free build, drop the `:preloads` entry and the `day8/re-frame2-xray` dep together.

## Production build (`release`)

`shadow-cljs release app` produces an optimised bundle. No re-frame2-specific config needed.

re-frame2's `:advanced`-compile elision contract (Spec 009) automatically strips dev-only diagnostics (`trace`, `epoch-history`, schema validation at boundary) when `goog.DEBUG` is false — which it is under `:advanced`. The author gets the elision for free; nothing to configure.

### Production hardening — serve CSP as a response header

The `index.html` meta CSP above is **development-flavoured** (it allows `'unsafe-inline'` styles for the views' `:style` props + Xray, admits the hot-reload websocket, and omits `frame-ancestors`). For production, **prefer setting CSP as a real response header** on your server, not via the meta tag: meta-tag CSPs are evaluated late, some directives (`frame-ancestors`) are ignored entirely when delivered via `<meta>`, and an upstream proxy can strip a meta CSP.

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

A few things you might pull in by reflex from other CLJS framework setups that re-frame2 specifically does not require:

- **No *framework* preload required** — re-frame2's core has no preload analogue to re-frame v1. The one default preload is Xray's devtools (`day8.re-frame2-xray.preload`, wired in `:devtools/preloads` — see the `index.html` section above for the host column it opens into).
- **No `:closure-defines`** for re-frame2 itself in dev. The single exception is opting into the performance-API instrumentation (Spec 009 §Performance instrumentation) — add `:compiler-options {:closure-defines {re-frame.performance/enabled? true}}` to the build only if the author asks for it explicitly. Default dev is fine.
- **No special compiler options** for dev. `{:compiler-options {:warnings {...}}}` is up to the author.
- **No SSR build entry** unless the author wants SSR. SSR is opt-in via `day8/re-frame2-ssr` (separate per-feature artefact); the SSR build is a separate `:target :node-script` (or `:target :browser` running in a static-render harness). Out of scope for greenfield.
