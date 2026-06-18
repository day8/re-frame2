# docs/cljs playground (rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover; rf2-00zvt Phase 3)

The roll-your-own live-ClojureScript-cell playground for the `docs/cljs` page —
the production replacement for Klipse. It turns ` ```cljs ` fenced blocks in
mkdocs prose into CodeMirror 6 editors that evaluate plain CLJS in the browser
via Scittle (SCI), instant-nav-safe.

**Phase 3 (rf2-00zvt)** adds a second cell kind for **live re-frame2
components** that evaluate re-frame2's OWN public API — see
[Cell kinds](#cell-kinds) below.

**rf2-ldgpd** extends the Phase-3 bundle with the optional
`day8/re-frame2-machines` artefact: `re-frame.machines` is `:require`d
by the SCI bundle namespace, which fires the artefact's `:machines/*`
late-bind hook installs at bundle init and registers the
`:rf/machine` / `:rf/machine-has-tag?` framework subs +
`:rf.machine/spawn` / `:rf.machine/destroy` (etc.) reserved fxs from
its top-level forms. The `re-frame.core` aliases `sci/copy-ns` already
exposes (`reg-machine*` / `make-machine-handler` / `machine-transition`
/ `machine-has-tag?` / `machines` / `machine-meta` /
`machine-by-system-id` / `dispatch-to-system`) become live, and the SCI
namespace also binds `reg-machine` to the `reg-machine*` fn-alias so
cells write the same `(rf/reg-machine ...)` they would in real code.
Used by ch12 of the guide to demo a real `reg-machine` +
`subscribe [:rf/machine …]` turnstile.

> A short-lived Phase 2 (rf2-bujlr) shipped a third `cljs-render` cell kind for
> live **stock** reagent/re-frame demos via the Scittle plugins. It was removed:
> the guide teaches re-frame2's own API, so no docs page ever used it, and it
> carried a React-18 CDN surface that exists nowhere else in the repo.

## Cell kinds

| Fence | Class emitted | Behaviour |
|---|---|---|
| ` ```cljs ` | `language-cljs` | **plain-eval cell** — evaluates the source and `pr-str`s the last form's value into the result div (Phase 1). |
| ` ```cljs-rf2 ` | `language-cljs-rf2` | **re-frame2 render cell** — evaluates the source against **re-frame2's OWN public API** (`re-frame.core` v2) and **mounts the last form's value as a reagent2 component** into the result div (Phase 3). Backed by a self-contained SCI bundle (`sci/` → `docs/cljs/playground-rf2.js`), NOT Scittle. |

### re-frame2 cells (` ```cljs-rf2 `, Phase 3)

A `cljs-rf2` cell evaluates against **re-frame2's own public API** — the v2
`re-frame.core` (`reg-event` / `reg-sub` / `dispatch` / `subscribe`), rendered
via **reagent2** (the reagent-slim rewrite re-frame2 actually renders through),
NOT stock re-frame. The **last form** must be a reagent renderable (a hiccup
vector `[:div ...]` or a component vector `[my-component]`); the cell
auto-renders on load and re-renders on Mod-Enter after edits. Its source is
evaluated at the SCI **top level** (NOT wrapped in `(do ...)`) so a leading
`(require ...)`'s aliases reach its sibling top-level forms.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
(rf/reg-event :init (fn [_ _] {:db {:n 0}}))
(rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
(rf/reg-sub      :n    (fn [db _] (:n db)))
(rf/dispatch-sync [:init])
(defn counter []
  [:div
   [:span "count: " @(rf/subscribe [:n])]
   [:button {:on-click #(rf/dispatch [:inc])} "inc"]])
[counter]
```

**Why this is NOT a Scittle plugin.** Scittle's `scittle.reagent.js` /
`scittle.re-frame.js` plugins ship STOCK reagent + re-frame, and there is no
published `scittle.core` artefact a standalone plugin build could `:require`
(Scittle is a monorepo module-graph build). So Phase 3 is a **self-contained SCI
eval bundle** (findings doc §6 option B): the `sci/` sub-project is a shadow-cljs
`:browser` `:advanced` build that depends on `org.babashka/sci` + re-frame2 core
+ reagent-slim, builds an SCI context via `sci/copy-ns` over `re-frame.core`, and
installs `window.rf2sci.renderLast`. The bootstrap loads it as a classic
`<script>` **only on pages with a ` ```cljs-rf2 ` cell**.

How re-frame2's API reaches a cell:

- In compiled CLJS, `re-frame.core` carries plain-fn **aliases** for every
  `reg-*` registration (the macro forms are JVM-only and only add source-coord
  capture, which a browser cell does not need), so `sci/copy-ns` exposes them
  under their plain names.
- `dispatch` / `dispatch-sync` / `subscribe` are macro-only on the public
  surface (the fns are `dispatch*` / … / `subscribe*`), so the SCI config adds
  those names explicitly, bound to the `*`-fns.

**React 19 is bundled, not global.** reagent2 targets React 19, which **dropped
its UMD build** — so the Phase-2 global-`React`-from-CDN trick is unavailable.
The `sci/` bundle therefore bundles `react`/`react-dom`@19 (the impl-pinned
versions) directly: `playground-rf2.js` is one fully self-contained file (no
external React, no CDN, no version-mismatch risk). ~1.31 MB raw / ~342 KB
gzipped (the +5.7% raw / +7.5% gzipped uptick over the Phase-3 baseline is
rf2-ldgpd folding `re-frame.machines` into the bundle).

This is **option B** from the findings doc
(`ai/findings/2026-05-21-roll-your-own-cljs-playground.md` §6) realised as a
self-contained `tools/` artefact. The bootstrap + CM6 editor are plain JS bundled
by **esbuild**; the Phase-3 re-frame2 eval engine is the **CLJS + shadow-cljs**
sub-build under `sci/`. (This artefact ships its own `sci/shadow-cljs.edn` +
`sci/deps.edn` — the top-level `tools/shadow-cljs.edn` is **untouched**.)

## Stack (pinned)

| Dependency | Version | Role |
|---|---|---|
| `@nextjournal/clojure-mode` | 0.3.3 | Lezer CLJS mode: syntax, brackets, paredit, `default_extensions` + `complete_keymap` |
| `@codemirror/state` | ^6.6.0 | CM6 editor state (`EditorState`, `Prec`) |
| `@codemirror/view` | ^6.43.0 | CM6 view (`EditorView`, `keymap`, `lineNumbers`) |
| `@codemirror/commands` | ^6.10.3 | history + default keymaps |
| `@codemirror/language` + `@lezer/highlight` | ^6.12.3 / ^1.2.3 | the `HighlightStyle` + Lezer tags that paint clojure-mode's syntax (rf2-wj623) |
| Scittle | 0.8.31 | plain-cell SCI eval engine — loaded as a classic `<script>` global from jsDelivr (NOT bundled, NOT an ES module) |
| esbuild | ^0.28.0 | bundler (IIFE) for the bootstrap |
| playwright | ^1.60.0 | smoke harness (chromium) |

The Phase-3 re-frame2 eval bundle (`sci/`) pins:

| Dependency | Version | Role |
|---|---|---|
| `org.babashka/sci` | 0.11.51 (git) | the SCI interpreter — the re-frame2 cells' eval engine |
| `day8/re-frame2` (core) | `:local/root` | the public API exposed to cells (`re-frame.core` v2) |
| `day8/reagent-slim` | `:local/root` | reagent2 (the render substrate) + the `reagent-slim` adapter |
| `day8/re-frame2-machines` | `:local/root` | Spec 005 state-machine artefact (rf2-ldgpd) — activates `reg-machine` / `subscribe [:rf/machine …]` / `machine-has-tag?` for ch12 live cells |
| `react` + `react-dom` | 19.2.0 | **bundled** into `playground-rf2.js` (React 19 has no UMD) |
| `shadow-cljs` | 3.4.10 | the CLJS → `:advanced` browser bundler |

## Build

```bash
cd docs/tools/playground
npm install
npm run build          # builds BOTH bundles (bootstrap + re-frame2 SCI)
# npm run build:bootstrap   # just the esbuild bootstrap
# npm run build:rf2         # just the sci/ shadow-cljs re-frame2 bundle
# npm run build:dev         # unminified bootstrap, for debugging
```

`npm run build` produces three committed, deployed assets:

- `docs/cljs/playground.js` — the esbuild IIFE bundle (CM6 + clojure-mode + the
  instant-nav bootstrap). **Committed** (vendored prebuilt; bump = re-bundle).
- `docs/cljs/playground.css` — hand-authored cell styles, copied verbatim.
- `docs/cljs/playground-rf2.js` — the shadow-cljs `:advanced` re-frame2 SCI
  bundle (Phase 3). Built from `sci/` (`shadow-cljs release rf2` → copied from
  `sci/out/`). **Committed** (vendored prebuilt; bump = re-bundle).

Neither Scittle nor the re-frame2 bundle is loaded eagerly — the bootstrap
injects each `<script>` at eval time, only on pages that have the relevant cell
kind (Scittle for ` ```cljs `; `playground-rf2.js` for ` ```cljs-rf2 `), the
same guarded, lazy-load pattern the deleted Klipse bootstrap used for its plugin.

## Test

```bash
npm run browsers       # one-time: playwright install chromium
npm run smoke          # headless chromium drives all cells against the built bundles
```

The smoke loads BOTH **production** bundles (`docs/cljs/playground.js` +
`docs/cljs/playground-rf2.js`) against a page that mimics the mkdocs-emitted DOM
(`<pre class="language-cljs">` + `<pre class="language-cljs-rf2">`), proves the
bootstrap auto-injects each engine on demand, then asserts:

- **Phase 1:** `(+ 1 2 3) => 6`; a `defn`/`println`/nested-coll cell captures
  `*out*` and renders the value; an error cell renders `ERROR` without crashing
  (and cell 1 still evals after).
- **Phase 3:** a ` ```cljs-rf2 ` cell makes the bootstrap auto-load the
  self-contained re-frame2 SCI bundle (`window.rf2sci`); a reagent2 component
  using re-frame2's OWN `subscribe` renders live; clicking its button
  `dispatch`es a re-frame2 event and the v2 subscription updates (count 0 → 2);
  the Phase-1 plain cell on the same page still works alongside it.
- **rf2-ldgpd (machines):** a second ` ```cljs-rf2 ` cell calls real
  `rf/reg-machine` against a two-state toggle machine, renders the state name
  via `rf/subscribe [:rf/machine …]`, and flips `:on` → `:off` on a button click — proving
  the machines artefact's `:machines/*` late-bind hooks (`reg-machine*`,
  `make-machine-handler`, `machine-transition`) and the `:rf/machine`
  framework sub all activate at bundle init.

Build both bundles first: `npm run build` (or `npm run build:rf2` for just the
re-frame2 one).

### CI

The smoke is gated in CI (`.github/workflows/test.yml`, `tools-playground`
job, fired by the `playground` changed-surface in
`.github/scripts/report-changed-surfaces.sh`). The job builds both bundles,
runs the smoke against them, **and** verifies the committed
`docs/cljs/playground.js` + `.css` are byte-identical to a fresh build
(`git diff --exit-code`), plus that the committed `playground-rf2.js` is a
valid, fresh re-frame2 prebuilt (structural + freshness checks — see the
SCI-bundle freshness guard note below) — so a stale vendored bundle fails the
PR. See the row in `TESTING.md`.

**Rebuild-on-publish (rf2-ssxvg1).** The committed `playground-rf2.js` is still
a vendored prebuilt for PR gating and local convenience, but it is no longer
the source of the *deployed* live-cell engine. `.github/workflows/docs.yml`
now rebuilds **both** bundles fresh from the checked-out `main` (`npm ci` +
`npm run build`, with a JVM + Clojure + Node toolchain) **before**
`mkdocs build` stages `site/`, so the **deployed** bundle is always current
with `main` regardless of how stale the committed snapshot is. This eliminates
the committed-stale-binary drift class structurally: a wave of
`implementation/` changes (e.g. the EP-0003 resources wave) can no longer
leave the live cells running an old re-frame2 just because the committed
bundle predates them. The build-in-pipeline approach was chosen over a
commit-back bot push: it keeps the deployed site authoritative without a
write-back to `main` (the committed snapshot may briefly lag a docs publish,
which is acceptable since the deploy always rebuilds).

**SCI-bundle freshness guard (rf2-2h1yhk).** `playground-rf2.js` is a Closure
`:advanced` build whose minified output is **not** cross-machine reproducible,
so it is NOT byte-diffed (see the gate steps' rationale). That leaves a hole: a
reserved-keyword rename in `implementation/machines/**` (e.g. the machine
lifecycle creation marker `:rf.machine/bootstrap` → `:rf.machine/start`)
re-bundles into a structurally-valid-but-stale artefact, and **no
changed-surface fired the `playground` gate** because only
`docs/tools/playground/**` and the committed bundles did. Two changes close it:

- `report-changed-surfaces.sh` now sets `playground=true` for any
  `implementation/machines/*` change, so the `tools-playground` job runs even
  when the machines source — not the playground tree — is what changed.
- `scripts/check-playground-sci-freshness.sh` (run as a `tools-playground`
  step) asserts the **committed** `docs/cljs/playground-rf2.js` carries the
  current creation marker (derived from `transition.cljc`'s `start-marker`
  def, so it auto-tracks a future rename) and does **not** carry the retired
  `:rf.machine/bootstrap`. It is a deterministic content check — it rebuilds
  nothing — and is the static counterpart to the smoke's eager
  `[:rf.machine/start]` cell (the runtime half). Run it locally from the repo
  root: `sh scripts/check-playground-sci-freshness.sh`.

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

## Plain `cljs` cells cannot `(require ...)`

A plain ` ```cljs ` cell wraps its whole body in one `(do ...)` form (so the
`*out*` capture + last-form return work), and SCI only propagates a
`require`'s aliases to its *sibling* top-level forms — so a leading
`(require '[x :as y]) … (y/foo)` inside a plain cell fails to resolve `y/foo`.
Plain cells are for framework-free ClojureScript (data literals, evaluation
rules, builtins); a cell that needs `require` + reagent/re-frame is a
` ```cljs-rf2 ` cell, whose source is NOT do-wrapped precisely so its
`require` aliases reach sibling forms. (No plain cell in the docs uses
`require` today — this note is for future authors.)
