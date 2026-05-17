# shadow-cljs

The minimal `shadow-cljs.edn` build for a greenfield re-frame2 Reagent single-page app, and the matching `index.html`.

## Contents

- Minimal `shadow-cljs.edn`
- The `index.html` that loads the bundle
- `:devtools` block (optional, for hot-reload)
- Production build (`release`)
- nREPL — only if you'll use `re-frame-pair2`
- What re-frame2 does NOT need

---

## Minimal `shadow-cljs.edn`

```clojure
{:source-paths ["src"]

 :dependencies []                       ;; deps come from deps.edn

 :builds
 {:app
  {:target     :browser
   :output-dir "public/js"
   :asset-path "/js"
   :modules    {:main {:init-fn your-app.core/run}}}}}
```

The smallest greenfield build entry. Three things matter for re-frame2:

1. **`:dependencies []`.** shadow-cljs reads `deps.edn` automatically. Don't duplicate the `day8/re-frame2-*` coordinates here; that's where they'd shadow each other if their VERSION ever drifted.
2. **`:init-fn your-app.core/run`.** shadow-cljs calls this symbol at bundle-init time. The function must be exported (`(defn ^:export run [] ...)`). This is the entry point that calls `(rf/init! reagent-adapter/adapter)` — see `entry-namespace.md`.
3. **One module.** A single-page re-frame2 app needs exactly one `:modules` entry. Code-splitting is possible later but not part of greenfield.

Substitute `your-app.core/run` with whatever your entry namespace + run symbol actually is. If you call your namespace `myapp.core` and your run fn `start`, this becomes `:init-fn myapp.core/start`.

The build id (`:app` above) is the name you give the build for `shadow-cljs watch <build-id>`. Use anything that reads naturally; `:app` is convention.

## The `index.html` that loads the bundle

A re-frame2 app needs an HTML page that loads the compiled JS and has a mount point. Drop this at `public/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>your-app</title>
  <style>
    :root { --rf-causa-accent: #7C5CFF; } /* brand-accent var (rf2-9ovfb) — host stylesheets read var(--rf-causa-accent) to tint dev chrome */
    body { margin: 0; }
    .app-shell { display: flex; min-height: 100vh; }
    [data-rf-causa-host] {
      flex: 0 0 var(--rf-causa-inline-width, 560px);
      min-width: 320px;
      box-sizing: border-box;            /* the border lives inside the
                                            documented width */
      border-left: 1px solid #2a2a2a;   /* visual separator on the app side */
      resize: horizontal;                /* user-draggable width */
      overflow: auto;
    }
    #app { flex: 1; min-width: 0; }
    /* Resize from anywhere up the cascade: */
    /*   :root { --rf-causa-inline-width: 720px; } */
  </style>
</head>
<body>
  <div class="app-shell">
    <main id="app"></main>
    <aside data-rf-causa-host></aside>
  </div>
  <script src="/js/main.js"></script>
</body>
</html>
```

Four contractual bits:

- **`<main id="app"></main>`** — the app mount point. Whatever id you use here, the entry ns must call `(js/document.getElementById "<same-id>")`. By convention it's `"app"`.
- **`<aside data-rf-causa-host></aside>`** — Causa's default true-inline devtools host. Keep it as a right-side layout column beside `#app` when you enable `day8.re-frame2-causa.preload` (DOM order: `<main>` first, `<aside>` second — flex flow puts the aside on the right); otherwise Causa logs an actionable missing-host diagnostic and exposes the same status through `window.day8.re_frame2_causa.status()`.
- **`.app-shell` flex CSS** — the host app owns sizing and layout. The minimal contract is a right column (`flex: 0 0 var(--rf-causa-inline-width, 560px); min-width: 320px`) and an app region that can shrink (`#app { flex: 1; min-width: 0; }`). Two complementary resize mechanisms ship together — both browser-native, both JS-free: (1) **CSS variable** — override `--rf-causa-inline-width` anywhere up the cascade (e.g. `:root { --rf-causa-inline-width: 720px; }`) to set the initial width (host-owned, per `rf2-um813`; default bumped 420 → 560 under `rf2-9ovfb`); (2) **Browser-native drag** — `resize: horizontal` + `overflow: auto` paint a drag-handle in the host's bottom corner so the user can resize ad-hoc. The variable seeds the initial size; a drag overrides it for the page lifetime. The snippet also publishes `--rf-causa-accent` (default `#7C5CFF`) on `:root` (rf2-9ovfb) so host stylesheets can colour their own dev chrome to match Causa.
- **`<script src="/js/main.js">`** — `/js/` comes from `:asset-path "/js"`; `main.js` comes from the module name `:main`. If you rename either, this path follows.
- **`/js/main.js` is an absolute path from site root.** That's correct for shadow-cljs's dev server.

## `:devtools` block (optional, for hot-reload)

Add `:devtools` to enable shadow-cljs's hot-reload + dev server:

```clojure
:builds
{:app
 {:target     :browser
  :output-dir "public/js"
  :asset-path "/js"
  :modules    {:main {:init-fn your-app.core/run}}
  :devtools   {:http-port 8020
               :http-root "public"
               :watch-dir "public"}}}
```

- `:http-port` — port the dev server listens on. `8020` is convention; pick anything free.
- `:http-root "public"` — the dev server serves files from this directory. Your `index.html` lives at `public/index.html`.
- `:watch-dir "public"` — shadow-cljs reloads the browser when any file under here changes (including the compiled `main.js`).

With this block in place, `shadow-cljs watch app` starts the dev server. Visit `http://localhost:8020/` and the browser auto-refreshes on every recompile.

re-frame2 itself **does not need a preload** for hot-reload. shadow-cljs's default behaviour is enough. Causa is the devtools exception: if you add `day8.re-frame2-causa.preload`, the page must provide `[data-rf-causa-host]` as shown above. The Causa preload registers listeners/keybindings and auto-opens into that app-provided host after `rf/init!` installs the substrate adapter; there is no lazy/manual-only launch step.

## Production build (`release`)

`shadow-cljs release app` produces an optimised bundle. No re-frame2-specific config needed.

re-frame2's `:advanced`-compile elision contract (Spec 009) automatically strips dev-only diagnostics (`trace`, `epoch-history`, schema validation at boundary) when `goog.DEBUG` is false — which it is under `:advanced`. The author gets the elision for free; nothing to configure.

## nREPL — only if you'll use `re-frame-pair2`

If the author plans to attach `re-frame-pair2` (the live-inspection skill) to the running app, shadow-cljs's dev build needs nREPL enabled. shadow-cljs **enables nREPL by default** when you run `shadow-cljs watch <build>` — no config change required. The port is written to `target/shadow-cljs/nrepl.port` (or `.shadow-cljs/nrepl.port` depending on version), which is where `re-frame-pair2`'s `discover-app.sh` looks for it.

If you want to pin the port explicitly (e.g. for editor integrations), add a top-level `:nrepl {:port 7002}` to `shadow-cljs.edn`. Not required for greenfield.

**Dev-only — bind to localhost.** nREPL is a remote-evaluation surface; in development always leave it bound to `localhost` (the shadow-cljs default). Never expose the nREPL port on `0.0.0.0` or a public interface — anything that can connect can evaluate arbitrary code in the running JVM.

## What re-frame2 does NOT need

A few things you might pull in by reflex from other CLJS framework setups that re-frame2 specifically does not require:

- **No framework preload required** — re-frame2 has no preload analogue to re-frame v1. Causa is optional devtools wiring; when enabled, its default is the `[data-rf-causa-host]` true-inline panel on app load. Overlay/body-padding chrome is optional debug tooling, not the default, and pop-out remains available from the mounted panel.
- **No `:closure-defines`** for re-frame2 itself in dev. The single exception is opting into the performance-API instrumentation (Spec 009 §Performance instrumentation) — set `re-frame.performance/enabled? true` only if the author asks for it explicitly. Default dev is fine.
- **No special compiler options** for dev. `{:compiler-options {:warnings {...}}}` is up to the author.
- **No SSR build entry** unless the author wants SSR. SSR is opt-in via `day8/re-frame2-ssr` (separate per-feature artefact); the SSR build is a separate `:target :node-script` (or `:target :browser` running in a static-render harness). Out of scope for greenfield.
