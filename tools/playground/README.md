# docs/cljs playground (rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover)

The roll-your-own live-ClojureScript-cell playground for the `docs/cljs` page —
the production replacement for Klipse. It turns ` ```cljs ` fenced blocks in
mkdocs prose into CodeMirror 6 editors that evaluate plain CLJS in the browser
via Scittle (SCI), instant-nav-safe.

This is **option B** from the findings doc
(`ai/findings/2026-05-21-roll-your-own-cljs-playground.md` §6) realised as a
self-contained `tools/` artefact. The cells are plain CLJS, so the artefact is
mostly JS bundled by **esbuild** — not shadow-cljs. (The shadow-cljs SCI config
that exposes re-frame2's own API to cells is a Phase 3 concern; see the findings
doc. `tools/shadow-cljs.edn` is **untouched** by this artefact.)

## Stack (pinned)

| Dependency | Version | Role |
|---|---|---|
| `@nextjournal/clojure-mode` | 0.3.3 | Lezer CLJS mode: syntax, brackets, paredit, `default_extensions` + `complete_keymap` |
| `@codemirror/state` | ^6.6.0 | CM6 editor state (`EditorState`, `Prec`) |
| `@codemirror/view` | ^6.43.0 | CM6 view (`EditorView`, `keymap`, `lineNumbers`) |
| `@codemirror/commands` | ^6.10.3 | history + default keymaps |
| Scittle | 0.8.31 | SCI eval engine — loaded as a classic `<script>` global from jsDelivr (NOT bundled, NOT an ES module) |
| esbuild | ^0.28.0 | bundler (IIFE) |
| playwright | ^1.60.0 | smoke harness (chromium) |

## Build

```bash
cd tools/playground
npm install
npm run build          # esbuild --minify -> ../../docs/cljs/playground.js + copies playground.css
# npm run build:dev    # unminified, for debugging
```

`npm run build` produces two committed, deployed assets:

- `docs/cljs/playground.js` — the esbuild IIFE bundle (CM6 + clojure-mode + the
  instant-nav bootstrap). **Committed** (vendored prebuilt; bump = re-bundle).
- `docs/cljs/playground.css` — hand-authored cell styles, copied verbatim.

Scittle is **not** bundled — the bootstrap injects its CDN `<script>` at eval
time (only on pages that have ` ```cljs ` cells), the same guarded, lazy-load
pattern the deleted Klipse bootstrap used for its plugin.

## Test

```bash
npm run browsers       # one-time: playwright install chromium
npm run smoke          # headless chromium drives 3 cells against docs/cljs/playground.js
```

The smoke loads the **production** `docs/cljs/playground.js` against a page that
mimics the mkdocs-emitted DOM (`<pre class="language-cljs">`), proves the
bootstrap auto-injects Scittle, then asserts: `(+ 1 2 3) => 6`; a
`defn`/`println`/nested-coll cell captures `*out*` and renders the value; an
error cell renders `ERROR` without crashing (and cell 1 still evals after).

## mkdocs wiring

`mkdocs.yml` declares a `cljs` custom fence (`pymdownx.superfences` →
`<pre class="language-cljs">`) and loads `playground.js` / `playground.css` via
`extra_javascript` / `extra_css`. Material re-runs `extra_javascript` on every
instant nav; the bootstrap subscribes once to `window.document$` and re-scans
the swapped DOM (idempotent via `data-cljs-mounted`). Sub-path (`/re-frame2/`)
asset resolution uses `document.currentScript.src`.

## Cutover (Phase 1b, rf2-j06sy)

Phase 1 shipped behind a **new** fence class (`language-cljs`) so it could
coexist with Klipse during the transition. **Phase 1b cut over**: the
`docs/cljs/index.md` cells are now `cljs` fences rendered here, Klipse's
`extra_javascript` line + `klipse` custom fence were removed from `mkdocs.yml`,
and the vendored Klipse assets (`docs/klipse/klipse_plugin.js` ~7.4 MB,
`klipse-bootstrap.js`, `codemirror.css`) were deleted. All ~87 cells were
spot-checked under the playground first (eval-result + error fidelity vs
Klipse — Risk #1). One fidelity fix landed in the cutover: a top-level
`def`/`defn` returns a var, so the renderer derefs it to show the bound value
(matching Klipse's friendlier display) rather than `#'user/x`.

## Three gotchas honoured (from the Phase 0 spike, rf2-qk3sh)

1. SCI has no JVM classes — capture `*out*` via `with-out-str`, not
   `java.io.StringWriter`.
2. A CLJS vector returned to JS is a `PersistentVector` object, not a JS Array —
   wrap the eval return in `(clj->js ...)`.
3. The Mod-Enter eval keymap is swallowed unless wrapped in
   `Prec.highest(keymap.of([...]))`.
