# docs/cljs playground (rf2-y99zt Phase 1; rf2-j06sy Phase 1b cutover; rf2-00zvt Phase 3)

The roll-your-own live-ClojureScript-cell playground for the `docs/cljs` page —
the production replacement for Klipse. It turns ` ```cljs ` fenced blocks in
mkdocs prose into CodeMirror 6 editors that evaluate plain CLJS in the browser
via Scittle (SCI), instant-nav-safe.

Phase 3 (rf2-00zvt) adds a second cell kind for live re-frame2
components that evaluate re-frame2's OWN public API — see
[Cell kinds](#cell-kinds) below.

rf2-ldgpd extends the Phase-3 bundle with the optional
`day8/re-frame2-machines` artefact: `re-frame.machines` is `:require`d
by the SCI bundle namespace, which fires the artefact's `:machines/*`
late-bind hook installs at bundle init and registers the
`:rf/machine` / `:rf.machine/has-tag?` framework subs +
`:rf.machine/spawn` / `:rf.machine/destroy` (and others) reserved fxs from
its top-level forms. Those hook installs are what make the machine
helpers live — `reg-machine*` / `make-machine-handler` /
`machine-transition` / `machines` / `machine-meta` /
`machine-by-system-id`. They sit on `re-frame.machines`, NOT on the
`re-frame.core` façade: the front-porch shrink (rf2-wad2fl) left the
façade's machine surface as the `reg-machine` / `defmachine` macros
alone, and `reg-machine` is a JVM-only macro (per-element source-coord
stamping at expansion time). So the SCI namespace binds `reg-machine` to
the `re-frame.machines/reg-machine*` fn-alias, and cells write the same
`(rf/reg-machine ...)` they would in real code.
Used by ch12 of the guide to demo a real `reg-machine` +
`subscribe [:rf/machine …]` turnstile.

> A short-lived Phase 2 (rf2-bujlr) shipped a third `cljs-render` cell kind for
> live stock reagent/re-frame demos via the Scittle plugins. It was removed:
> the guide teaches re-frame2's own API, so no docs page ever used it, and it
> carried a React-18 CDN surface that exists nowhere else in the repo.

## Cell kinds

| Fence | Class emitted | Behaviour |
|---|---|---|
| ` ```cljs ` | `language-cljs` | **plain-eval cell** — evaluates the source and `pr-str`s the last form's value into the result div (Phase 1). |
| ` ```cljs-rf2 ` | `language-cljs-rf2` | **re-frame2 render cell** — evaluates the source against **re-frame2's OWN public API** (`re-frame.core` v2) and **mounts the last form's value as a reagent2 component** into the result div (Phase 3). Backed by a self-contained SCI bundle (`sci/` → `docs/cljs/playground-rf2.js`), NOT Scittle. |

### re-frame2 cells (` ```cljs-rf2 `, Phase 3)

A `cljs-rf2` cell evaluates against re-frame2's own public API — the v2
`re-frame.core` (`reg-event` / `reg-sub` / `dispatch` / `subscribe`), rendered
via reagent2 (the reagent-slim rewrite re-frame2 actually renders through),
NOT stock re-frame. The last form must be a reagent renderable (a hiccup
vector `[:div ...]` or a component vector `[my-component]`); the cell
auto-renders on load and re-renders on Mod-Enter after edits. Its source is
evaluated at the SCI top level (NOT wrapped in `(do ...)`) so a leading
`(require ...)`'s aliases reach its sibling top-level forms.

```cljs-rf2
(require '[re-frame.core :as rf])
(rf/reg-event :init (fn [_ _] {:db {:n 0}}))
(rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
(rf/reg-sub      :n    (fn [db _] (:n db)))
(rf/reg-view counter []
  [:div
   [:span "count: " @(subscribe [:n])]
   [:button {:on-click #(dispatch [:inc])} "inc"]])
[rf/frame-root {:id :demo :initial-events [[:init]]}
 [counter]]
```

Why this is NOT a Scittle plugin. Scittle's `scittle.reagent.js` /
`scittle.re-frame.js` plugins ship STOCK reagent + re-frame, and there is no
published `scittle.core` artefact a standalone plugin build could `:require`
(Scittle is a monorepo module-graph build). So Phase 3 is a self-contained SCI
eval bundle (findings doc §6 option B): the `sci/` sub-project is a shadow-cljs
`:browser` `:advanced` build that depends on `org.babashka/sci` + re-frame2 core
+ reagent-slim, builds an SCI context via `sci/copy-ns` over `re-frame.core`, and
installs `window.rf2sci.renderLast`. The bootstrap loads it as a classic
`<script>` only on pages with a ` ```cljs-rf2 ` cell.

How re-frame2's API reaches a cell:

- In compiled CLJS, `re-frame.core` carries plain-fn aliases for every
  `reg-*` registration (the macro forms are JVM-only and only add source-coord
  capture, which a browser cell does not need), so `sci/copy-ns` exposes them
  under their plain names.
- `dispatch` / `dispatch-sync` / `subscribe` are macro-in-call-position /
  fn-in-value-position on CLJS (Convention A), so `sci/copy-ns` already
  brings the plain-fn form in under their own names; the SCI config still
  overrides those three entries with playground-local wrappers so a
  frame-less cell's dispatch/subscribe still resolve to the playground's
  single frame. That is a fallback, not the shape to copy: a cell that
  establishes its own frame (`frame-root` + `reg-view`, as the docs' cells do)
  routes through it unchanged, exactly as a real app would — its `:on-*`
  callbacks fire against the frame captured at render, not the harness default.

React 19 is bundled, not global. reagent2 targets React 19, which dropped
its UMD build — so the Phase-2 global-`React`-from-CDN trick is unavailable.
The `sci/` bundle therefore bundles `react`/`react-dom`@19 (the impl-pinned
versions) directly: `playground-rf2.js` is one fully self-contained file (no
external React, no CDN, no version-mismatch risk). ~1.31 MB raw / ~342 KB
gzipped (the +5.7% raw / +7.5% gzipped uptick over the Phase-3 baseline is
rf2-ldgpd folding `re-frame.machines` into the bundle).

This is option B from the findings doc
(`ai/findings/2026-05-21-roll-your-own-cljs-playground.md` §6) realised as a
self-contained `tools/` artefact. The bootstrap + CM6 editor are plain JS bundled
by esbuild; the Phase-3 re-frame2 eval engine is the CLJS + shadow-cljs
sub-build under `sci/`. (This artefact ships its own `sci/shadow-cljs.edn` +
`sci/deps.edn` — the top-level `tools/shadow-cljs.edn` is untouched.)

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
| `day8/re-frame2-machines` | `:local/root` | Spec 005 state-machine artefact (rf2-ldgpd) — activates `reg-machine` / `subscribe [:rf/machine …]` (and the `[:rf.machine/has-tag? …]` sub) for ch12 live cells |
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

`npm run build` produces three deployed assets, under two different
version-control policies:

- `docs/cljs/playground.js` — the esbuild IIFE bundle (CM6 + clojure-mode + the
  instant-nav bootstrap). Committed (vendored prebuilt; bump = re-bundle).
- `docs/cljs/playground.css` — hand-authored cell styles, copied verbatim.
  Committed.
- `docs/cljs/playground-rf2.js` — the shadow-cljs `:advanced` re-frame2 SCI
  bundle (Phase 3). Built from `sci/` (`shadow-cljs release rf2` → copied from
  `sci/out/`). NOT committed — generated output, `.gitignored` (rf2-tzy13).

### Local contract (rf2-tzy13)

A fresh clone has the two bootstrap assets but no `playground-rf2.js` — you
build it:

- `mkdocs build` alone builds the static docs fine. Every page renders; only the
  live ` ```cljs-rf2 ` cells have no engine to load.
- To exercise those cells locally (or to run `npm run smoke`), run `npm run
  build` — or `npm run build:rf2` for just the SCI bundle — in
  `docs/tools/playground` first. The smoke fails with an actionable
  "bundle not found — build it" error if you skip this.
- Pulling the rf2-tzy13 cutover commit deletes your formerly-tracked copy of
  the file. The next build recreates it as ignored output; nothing else is
  needed.

Why it is not committed: the bundle bakes in re-frame2 core + reagent-slim +
machines + flows, so every PR touching any of those had to regenerate the
same ~1.7 MB binary — and two such PRs always conflicted on it. On one day the
file was rewritten nine times on `main` in seven hours, one P2 correctness fix
needed five rebase cycles to land, and workers began scoping real work out of
PRs to dodge the rebuild. Source, config, and locks are the single authority
now; the artefact is generated at each consumption boundary (PR CI, docs
deploy, local build).

Neither Scittle nor the re-frame2 bundle is loaded eagerly — the bootstrap
injects each `<script>` at eval time, only on pages that have the relevant cell
kind (Scittle for ` ```cljs `; `playground-rf2.js` for ` ```cljs-rf2 `), the
same guarded, lazy-load pattern the deleted Klipse bootstrap used for its plugin.

## Test

```bash
npm run browsers       # one-time: playwright install chromium
npm run smoke          # headless chromium drives all cells against the built bundles
```

The smoke loads BOTH production bundles (`docs/cljs/playground.js` +
`docs/cljs/playground-rf2.js`) against a page that mimics the mkdocs-emitted DOM
(`<pre class="language-cljs">` + `<pre class="language-cljs-rf2">`), proves the
bootstrap auto-injects each engine on demand, then asserts:

- Phase 1: `(+ 1 2 3) => 6`; a `defn`/`println`/nested-coll cell captures
  `*out*` and renders the value; an error cell renders `ERROR` without crashing
  (and cell 1 still evals after).
- Phase 3: a ` ```cljs-rf2 ` cell makes the bootstrap auto-load the
  self-contained re-frame2 SCI bundle (`window.rf2sci`); a reagent2 component
  using re-frame2's OWN `subscribe` renders live; clicking its button
  `dispatch`es a re-frame2 event and the v2 subscription updates (count 0 → 2);
  the Phase-1 plain cell on the same page still works alongside it.
- rf2-ldgpd (machines): a second ` ```cljs-rf2 ` cell calls real
  `rf/reg-machine` against a two-state toggle machine, renders the state name
  via `rf/subscribe [:rf/machine …]`, and flips `:on` → `:off` on a button click — proving
  the machines artefact's `:machines/*` late-bind hooks (`reg-machine*`,
  `make-machine-handler`, `machine-transition`) and the `:rf/machine`
  framework sub all activate at bundle init.

Build both bundles first: `npm run build` (or `npm run build:rf2` for just the
re-frame2 one).

### CI

The bundles are gated at two consumption boundaries, and since rf2-tzy13
that is the whole story — there is no committed SCI snapshot to keep honest, so
there is no third, snapshot-shaped gate.

PR time — `tools-playground` in `.github/workflows/test.yml`, fired by the
`playground` changed-surface in `.github/scripts/report-changed-surfaces.sh`.
It builds both bundles, runs the headless-Chromium smoke against them, verifies
the committed `playground.js` + `.css` are byte-identical to that fresh
build (`git diff --exit-code`), and structurally validates the generated
`playground-rf2.js` (non-empty, `shadow$provide`, `rf2sci`, size floor) so a
build that "succeeded" while emitting a wrong or truncated artefact still fails
the PR. The surface that fires it now includes every baked-in tree — `core`,
`adapters/reagent-slim`, `machines`, `flows` — plus the playground itself. That
widening is affordable because of the untracking: firing the heavy job on
every core change used to also mean forcing a bundle rebuild + recommit on
every core PR, which was the write lock rf2-tzy13 removed.

Deploy time — the `build` job in `.github/workflows/docs.yml`. It rebuilds
both bundles fresh from the checked-out `main` (`npm ci` + `npm run build`, JVM
+ Clojure + Node) before `mkdocs build` stages `site/`, then — on non-PR
events only — runs the Chromium smoke against what it just built, and
`git diff --exit-code`s the committed bootstrap. The artifact that ships is
the artifact that was smoked. That matters specifically because PRs merge by
rebase: PR CI validated the pre-rebase tree, not the tree that landed. The
retired post-merge canary used to cover that gap; running the smoke on the
deploy path covers it at the exact point of consumption instead, and keeps
main-push CI to one heavy bundle build rather than two.

Provenance stamp (rf2-i3e3q; rescoped by rf2-tzy13). Every generated bundle
carries an unminified `//# rf2-sci-input-digest=<hex>` marker:

- `scripts/playground-sci-input-digest.mjs` hashes the declared input roster —
  the `core` / `reagent-slim` / `machines` / `flows` source trees + their
  `deps.edn`, the SCI bundle source, the shadow build config, the npm lock, the
  `copy-bundle.mjs` postprocess step, and the digest script itself — into one
  deterministic 64-hex digest (each file via `git hash-object`, so the digest is
  identical on a Windows checkout and a Linux runner). The last two entries are
  there because they change the emitted bytes or the meaning of the marker:
  omitting them would let the stamp attest to a tree that no longer describes
  the file (rf2-nyjml).
- `docs/tools/playground/sci/scripts/copy-bundle.mjs` stamps it into the bundle
  it emits, so any artefact records the input set it was compiled from.

The roster is declared, not derived — deriving it would mean resolving the CLJS
require graph plus the `deps.edn` classpath. Two checks keep the declaration
honest: the digest fails if any entry stops matching tracked files (so a
renamed tree REDs instead of silently leaving the digest), and
`implementation/scripts/_playground-sci-inputs.test.cjs` expands the real roster
to prove every declared input selects the `playground` CI job, so the digest's
inputs and the job that rebuilds the bundle cannot drift apart.

This is diagnostics, not a gate. It answers "which tree produced this
file?" for a deployed or downloaded copy. It used to be a freshness authority —
`check-playground-sci-freshness.sh` compared the marker in the committed
bundle against a fresh digest of the inputs — but a generated-in-run artefact
cannot lag its source, so that verifier was deleted along with the snapshot it
verified. Freshness is now structural: the bundle CI ships is one CI just built
from that same checkout.

## mkdocs wiring

`mkdocs.yml` declares a `cljs` custom fence (`pymdownx.superfences` →
`<pre class="language-cljs">`) and loads `playground.js` / `playground.css` via
`extra_javascript` / `extra_css`. Material re-runs `extra_javascript` on every
instant nav; the bootstrap subscribes once to `window.document$` and re-scans
the swapped DOM (idempotent via `data-cljs-mounted`). Sub-path (`/re-frame2/`)
asset resolution uses `document.currentScript.src`.

## Cutover (Phase 1b, rf2-j06sy)

Phase 1 shipped behind a new fence class (`language-cljs`) so it could
coexist with Klipse during the transition. Phase 1b cut over: the
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
`require`'s aliases to its sibling top-level forms — so a leading
`(require '[x :as y]) … (y/foo)` inside a plain cell fails to resolve `y/foo`.
Plain cells are for framework-free ClojureScript (data literals, evaluation
rules, builtins); a cell that needs `require` + reagent/re-frame is a
` ```cljs-rf2 ` cell, whose source is NOT do-wrapped precisely so its
`require` aliases reach sibling forms. (No plain cell in the docs uses
`require` today — this note is for future authors.)
