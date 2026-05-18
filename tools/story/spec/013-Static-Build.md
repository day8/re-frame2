# Story — Build-Time Static Export

> `story:build` — Story's equivalent of Storybook 8's `storybook build` /
> Histoire's `histoire build` / Ladle's `ladle build`. Compiles the
> playground to a static HTML directory the user publishes to GitHub
> Pages, Netlify, Vercel, or raw S3. Implements rf2-8wgpm.

## Why

The dev-mode story shell mounts under `shadow-cljs watch` and expects a
live JVM-backed compiler on the other end of the websocket — fine for
development, useless for sharing the playground with a non-CLJS
audience. SOTA peers (Storybook 8, Histoire, Ladle) all ship a
build-time invocation that produces a self-contained, deployable
HTML directory:

- Bundled JS (advanced compile, gzip-friendly).
- A static `index.html` that loads the bundle.
- Optional per-story HTML deep-links / sitemap.

`story:build` is the re-frame2 equivalent. It does **not** invent a new
chrome — the published bundle renders the same shell `mount-shell!`
already produces, minus the dev-time affordances that would either
break or feel out of place on a static site.

## Scope (v1.0)

- **Single-page application.** The published bundle is an SPA — one
  `index.html`, one `main.js`, one set of cljs-runtime siblings. The
  story shell's existing in-memory selection state (sidebar, tag
  filter, mode toolbar) drives navigation; visitors deep-link via the
  share URL (per [`005-SOTA-Features.md`](005-SOTA-Features.md) §QR
  code in share menu) rather than per-variant routes.
- **Per-story HTML files deferred.** A future iteration may emit one
  HTML file per variant for SEO / link-preview embedding (Storybook's
  `--docs` mode equivalent). v1 keeps the surface narrow — the SPA
  works under any static host without server-side rewrite rules.

## Static-mode runtime semantics

The shell honours a second compile-time flag —
`re-frame.story.config/static-mode?` — alongside the existing
`enabled?` gate from [`000-Vision.md`](000-Vision.md). When
`static-mode?` is `true`:

- **No registrar-fingerprint poll.** The shell's 500ms `setInterval`
  that watches for `reg-*` mutations (per
  [`003-Render-Shell.md`](003-Render-Shell.md) §Hot-reload trigger)
  short-circuits at `start-hot-reload-poll!`. Under `:advanced` the
  whole branch DCEs; the bundle never schedules the interval.
- **First-visit help overlay suppressed.** The dev-time onboarding
  modal that auto-opens for first-time visitors (per rf2-381i)
  short-circuits its `component-did-mount` auto-open path. The
  manual `?` chip still renders so on-demand help is reachable; the
  modal just doesn't pop unprompted. Visitors arriving at a
  published docs site already arrived with intent.
- **Causa preload omitted.** The Causa devtools preload
  (`day8.re-frame2-causa.preload`) is wired via shadow-cljs's
  `:devtools/preloads` slot — which the documentation specifies
  applies to `watch`/`compile` only, NOT `release`. The static-export
  build is a `release`, so Causa rides out of the bundle by
  construction; no flag-side gate is required.
- **Shadow-cljs hot-reload connection elided.** `release` builds
  don't include the websocket bridge to the dev server, so the bundle
  is self-contained the moment it leaves the compiler. The
  `static-mode?` flag is orthogonal — it gates Story-specific
  dev-time behaviour, not shadow-cljs's infrastructure.

The flag defaults to `false`; only the `story:build` invocation (or a
custom downstream build mirroring its `:closure-defines`) sets it to
`true`. On the JVM the flag is a plain `const` def of `false` so JVM
tests always operate against the dev-flavoured branch.

## Build target

A new shadow-cljs build, mirroring the per-example pattern under
[`implementation/shadow-cljs.edn`](../../../implementation/shadow-cljs.edn):

```edn
:story-static/counter-with-stories
{:target           :browser
 :output-dir       "out/story-static/counter-with-stories"
 :asset-path       "."
 :compiler-options {:closure-defines {re-frame.story.config/static-mode? true}}
 :modules          {:main {:init-fn counter-with-stories.story-static/run}}}
```

The build target name uses the `story-static/<app-name>` prefix so
shadow's output-dir convention nests the bundle under
`out/story-static/<app-name>/`, separated from `out/examples/` where
the dev-flavoured per-example builds land. Downstream consumers
mirror the same shape for their own apps.

The entry-point ns
(`counter-with-stories.story-static`) is a small static-export
companion to the example's `core.cljs` — it requires the same
`stories.cljs` registrations, installs the canonical vocabulary, and
mounts the shell directly via `(story/mount-shell! ...)`. No hash
routing, no live-counter view; the published bundle exists to render
the Story playground.

## Invocation

```bash
# From implementation/
npm run story:build
```

Driven by [`implementation/scripts/story-build.cjs`](../../../implementation/scripts/story-build.cjs).
The script:

1. Runs `shadow-cljs release story-static/counter-with-stories`.
2. Stages
   `tools/story/testbeds/counter_with_stories/story_static.index.html` as
   `index.html` next to the compiled `main.js`.
3. Writes a small `manifest.json` next to the bundle declaring the
   build target, the source HTML, the active closure-defines, and the
   build timestamp — so downstream tooling can introspect the
   artefact without re-parsing shadow's output.

Overridable via environment variables for downstream apps with their
own static-export build:

| Env var | Default | Purpose |
|---|---|---|
| `STORY_BUILD_TARGET` | `story-static/counter-with-stories` | shadow-cljs build id |
| `STORY_BUILD_OUTPUT_DIR` | `implementation/out/<target>/` | output directory |
| `STORY_BUILD_INDEX_HTML` | `tools/story/testbeds/counter_with_stories/story_static.index.html` | HTML to stage |

These are **CI-internal knobs**, not a stable public configuration
surface — re-frame2 reserves the right to rename / drop them between
releases. Per rf2-21rfv (pragmatic stance, 2026-05-14): path-policy
constrains `STORY_BUILD_OUTPUT_DIR` to `implementation/out/` and
`STORY_BUILD_INDEX_HTML` to `<repo>/implementation/`, `<repo>/examples/`,
or `<repo>/tools/` (rf2-p8f2s — tool-owned testbeds). Out-of-tree paths
require the explicit opt-in
`RE_FRAME_ALLOW_OUT_OF_TREE_WRITES=1`. The check is a safety net
against accidents (env unset / mistyped path turning a build into a
`rm -rf` outside the repo), not a hardened sandbox — devs running this
script already control the process. Downstream consumers publishing
into a sibling docs-site staging area set the opt-in flag explicitly.

## Output shape

After `npm run story:build`:

```
implementation/out/story-static/counter-with-stories/
├── index.html               # the host page (staged from story_static.index.html)
├── main.js                  # the advanced-compiled bundle
├── manifest.json            # build metadata
├── cljs-runtime/            # shadow-cljs runtime siblings (advanced compile only)
│   └── ...
└── (assets staged by the shadow build, if any)
```

The directory is deployable as-is. Every relative `<script>` / asset
reference resolves correctly under any URL prefix because the build
sets `:asset-path "."` — the bundle's Closure runtime resolves siblings
relative to the page, not the document root.

## Sanity test

```bash
# From implementation/
npm run test:story-static
```

Driven by [`implementation/scripts/check-story-static.cjs`](../../../implementation/scripts/check-story-static.cjs).
The script:

1. Runs `story-build.cjs` (compile + stage + manifest).
2. Asserts `index.html`, `main.js`, `manifest.json` are all present.
3. Spawns `http-server` over the output directory on port 8040.
4. Drives headless Chromium against `http://127.0.0.1:8040/` and
   verifies:
   - The Story shell mounted (the placeholder "Select a variant or
     workspace from the sidebar" text is rendered).
   - The three landmarks (`<nav>` / `<main>` / `<aside>`) are present
     and reachable by role.
   - The chrome-level toolbar (`[data-test="story-toolbar"]`) is
     rendered.
   - The first-visit help overlay is **suppressed** (no
     `role="dialog"` element appears within 1s of first paint).
   - Clicking a variant in the sidebar updates the canvas with the
     selected variant's title — proves the registry survived
     `:advanced` compilation and dispatch / subscription paths work.
5. Tears the server down.

A single PASS / FAIL line is logged; non-zero exit on smoke failure.

## Deploy targets

The output directory is a vanilla static-asset bundle, so any
host that serves arbitrary directories will do:

- **GitHub Pages** — push the directory to a `gh-pages` branch (or
  point Pages at `/docs/`). The `index.html` is the entry; everything
  else resolves relative.
- **Netlify / Vercel** — point at the output directory as the publish
  root. No build command needed; the directory is already built.
- **Cloudflare Pages** / **AWS Amplify** — same shape.
- **Raw S3 + CloudFront** — `aws s3 sync out/story-static/<app>/
  s3://<bucket>/ --delete`; set the default root object to
  `index.html` on CloudFront so deep-links resolve.
- **Local preview** — `npx http-server <output-dir> -p 8040 -c-1 -s`.

No HTML preview hardening (no `<meta name="robots">` tweak, no
CSP header injection, no asset versioning rewrite) is performed by
`story:build`; the static export is the raw playground bundle and
deploys verbatim. Consumers who want CSP / robots / cache rules layer
those at the hosting layer (their `_headers` file under Netlify,
their `staticwebapp.config.json` under Azure, etc.).

## What gets bundled

| Surface | Status |
|---|---|
| Story registrations (the four counter variants, two workspaces, three modes, one decorator, one panel) | bundled |
| The seven canonical `:rf.assert/*` event handlers | bundled |
| The canonical force-fx-stub decorator | bundled |
| The layout-debug overlay trio | bundled |
| The chrome-level toolbar + mode-tabs strip | bundled |
| The a11y panel (axe-core lazy-load endpoint stays the same) | bundled |
| The per-variant trace-buffer infra (feeds the schema-validation panel) | bundled |
| Causa (when the host build includes its preload) | bundled |

## What gets stripped

| Surface | Why |
|---|---|
| `:devtools/preloads` (Causa preload) | `release` builds ignore the slot — Causa rides out by construction |
| `shadow-cljs` websocket bridge | `release` builds don't include the dev-server connection |
| Registrar-fingerprint poll (the 500ms `setInterval`) | gated on `(not static-mode?)`; DCEs under `:advanced` |
| First-visit help overlay auto-open | gated on `(not static-mode?)` |
| `goog.DEBUG`-keyed branches (trace listener emit, source-coord DOM stamp) | the rest of the framework's standard release-build elision still applies |

## Downstream pattern

A consumer publishing their own app's playground:

1. Add a `<app>.story-static` ns mirroring
   `counter_with_stories/story_static.cljs`. The body installs the
   canonical vocabulary, applies any `configure!` defaults, and calls
   `(story/mount-shell! (js/document.getElementById "app"))`.
2. Add a `:story-static/<app>` shadow-cljs build mirroring
   `:story-static/counter-with-stories`, pointing
   `:init-fn` at `<app>.story-static/run` and setting
   `:closure-defines {re-frame.story.config/static-mode? true}`.
3. Add an `<app>.story_static.index.html` host page (a 12-line shim
   mirroring `counter_with_stories/story_static.index.html`).
4. Run `STORY_BUILD_TARGET=story-static/<app>
   STORY_BUILD_INDEX_HTML=<path-to-shim>.html npm run story:build`.
5. Publish `implementation/out/story-static/<app>/` to the host of
   choice.

## Cross-references

- [`000-Vision.md`](000-Vision.md) §What re-frame2-story is — the
  scope-setting read.
- [`003-Render-Shell.md`](003-Render-Shell.md) §Hot-reload trigger —
  the registrar-fingerprint poll the static-mode flag suppresses.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision
  — the `enabled?` flag (production-mode strip) this flag stacks
  alongside.
- [`010-Toolbar.md`](010-Toolbar.md) §URL deep-link — the
  share-URL mechanism `:story-static` consumers rely on for
  deep-linking variants without a per-variant HTML file.
- [`tools/story/testbeds/counter_with_stories/`](../testbeds/counter_with_stories/)
  — the canonical worked example / testbed (rf2-p8f2s).
- [`implementation/scripts/story-build.cjs`](../../../implementation/scripts/story-build.cjs)
  — the build driver.
- [`implementation/scripts/check-story-static.cjs`](../../../implementation/scripts/check-story-static.cjs)
  — the sanity-test rig.
