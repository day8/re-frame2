---
name: re-frame2-setup
description: >
  Greenfield-only bootstrap for re-frame2 ClojureScript projects. Scope:
  brand-new apps from nothing, or empty CLJS projects (shadow-cljs /
  Clojure already present but zero re-frame2 wiring). Adds the artefact
  dependencies, writes a minimal shadow-cljs.edn for a Reagent
  single-page app, lays down the canonical entry namespace (with
  `rf/init!` + the Reagent adapter), and walks the author through their
  first mounted counter. Trigger on phrasing like "start a re-frame2
  project", "scaffold re-frame2", "hello-world re-frame2 app", "new
  re-frame2 app", or a build failure on a freshly-scaffolded project that
  traces to missing `re-frame.core` / `re-frame.adapter.reagent` wiring.
  Exits once the counter mounts. **Do not use** for writing app code
  on an already-bootstrapped project (use `re-frame2`), v1→v2 migration
  (use `re-frame-migration`), live-app inspection (use `re-frame2-pair`),
  or porting re-frame2 itself (use `re-frame2-implementor`). For the
  full disqualifier list and routing to sibling skills, see
  `skills/README.md` §Skill routing — single source.
allowed-tools:
  - Bash(clojure -Stree)
  - Bash(clojure -Stree:*)
  - Bash(npm install)
  - Bash(npm install react@* react-dom@*)
  - Bash(npm view * version)
  - Bash(npx shadow-cljs watch *)
  - Bash(npx shadow-cljs compile *)
  - Bash(shadow-cljs watch *)
  - Bash(shadow-cljs compile *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2-setup

Bootstraps a fresh re-frame2 ClojureScript project. **Greenfield only** — a brand-new app from nothing, or an empty CLJS project (shadow-cljs / Clojure already present but zero re-frame2 wiring). When done: the project compiles under `shadow-cljs watch`, a counter mounts in the browser, and the author can switch to **`re-frame2`** for code-writing.

This skill teaches **only re-frame2-specific wiring**. Assume the author knows `deps.edn`, `npm`, `shadow-cljs`. It does not teach re-frame2's API — that's `re-frame2`'s job.

## When NOT to use

Not for: adding re-frame2 to an existing non-trivial app (authoring), writing application code on a working v2 project, live-app inspection, v1→v2 migration, or spec / architecture / porting questions about re-frame2 itself. Route any non-setup question to the right skill; don't improvise here. Full disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).

## Cardinal rules

1. **Default to the re-frame2 repo's pinned baseline; never silently chase "latest from npm".** The baseline is the known-good set of versions the re-frame2 repo itself builds against (`implementation/package.json`, `implementation/deps.edn`) at the **author-supplied pin** of `day8/re-frame2`. The author may explicitly opt into "latest from npm"; the skill never picks `latest` on its behalf. See [`references/deps-versions.md`](references/deps-versions.md).
2. **All eleven artefacts ship at the same VERSION** (and the `day8/re-frame2-xray` devtools panel rides the same line). Mixing versions across `day8/re-frame2-*` coordinates is unsupported. Use the single `<VERSION>` the author pinned at kickoff.
3. **The day-one shape matches the generator template.** Core + Reagent adapter + `-schemas` + `-xray` (plus an explicit `reagent/reagent` pin) are the day-one deps. The remaining per-feature artefacts (`-machines` / `-routing` / `-flows` / `-http` / `-ssr` / `-epoch`) are pay-as-you-go — add them only when the author writes code that uses them.
4. **Reagent adapter is the default reference substrate.** Unless the author explicitly says UIx or Helix, scaffold against Reagent. UIx/Helix greenfield is a documented two-substitution delta (deps swap + entry-ns root API + substrate views) → [`references/entry-namespace.md` §UIx / Helix greenfield](references/entry-namespace.md).
5. **The generator template is a USER-RUN route, not one this skill executes.** The skill's executable path is the **manual seven-step scaffold** below; its `allowed-tools` cover `clojure -Stree`, npm, and `shadow-cljs watch/compile`, but *not* `clojure -Tnew create`. When you steer the author toward the one-command generator, hand them the command to run — the skill never invokes `-Tnew` on their behalf. (Pre-publish the standalone template repo is unpublished, so the `io.github.day8/…` coord can't resolve and the author uses the `:local/root` dev route instead; full caveat in [`README.md` §Relationship to the generator template](README.md#relationship-to-the-generator-template).)
6. **Don't write tests for the author.** This skill stops at "the counter mounts."
7. **nREPL is dev-only and bound to localhost.** Anywhere this skill mentions nREPL (shadow-cljs's default REPL, `re-frame2-pair` attachment), it must remind the author: nREPL is a remote-evaluation surface — never expose it on `0.0.0.0` or a public interface. shadow-cljs binds to localhost by default; do not override that without an isolated, trusted-network reason.

## Canonical greenfield path (seven steps)

**Two in-scope starting points, one path.** From a *brand-new* directory you write every file below fresh. On an *empty CLJS project* (shadow-cljs / Clojure already present but zero re-frame2 wiring — the second in-scope situation) you **merge into** the files that exist rather than overwriting them: add the day-one coords + `:shadow` alias to the existing `deps.edn` (don't replace it), add the deps to the existing `package.json`, and fold the `:app` build + `:devtools/preloads` into the existing `shadow-cljs.edn` (keep the author's other `:builds`). Reuse their existing mount-point id, dev-http port, and `:output-dir`/`:asset-path` if set — the reference shapes are the *target*, not a literal overwrite. If the existing project already has substantial app code or other state management, it isn't greenfield — route to `re-frame2` (see When NOT to use).

1. **Discover the current artefact VERSION.** → [`references/deps-versions.md`](references/deps-versions.md). Day-one deps match the generator template: `day8/re-frame2` + `day8/re-frame2-reagent` + `day8/re-frame2-schemas` + `day8/re-frame2-xray`, plus an explicit `reagent/reagent`.
2. **Add the day-one artefacts to `deps.edn`, plus the `:shadow` build alias.** The `:shadow` alias (supplying `thheller/shadow-cljs` + `org.clojure/tools.namespace` and the `test`/`dev` extra-paths) is required — the paired `shadow-cljs.edn` reads its build classpath from it via `{:deps {:aliases [:shadow]}}`. Omitting it is the most common first-`watch` failure. → `references/deps-versions.md` §`deps.edn`.
3. **Add `react`, `react-dom`, `shadow-cljs` to `package.json`; run `npm install`.** → `references/deps-versions.md` §`package.json`.
4. **Write `shadow-cljs.edn` and `index.html`.** One `:target :browser` build, `:init-fn your-app.core/init`, `:source-paths` including `src/`, and the `:devtools/preloads [day8.re-frame2-xray.preload]` Xray wiring. `index.html` carries the true-inline `[data-rf-xray-host]` layout column beside `#app`, where Xray auto-opens on load. → [`references/shadow-cljs.md`](references/shadow-cljs.md) for the exact build shape, the host-column geometry, the `index.html`, and the `.gitignore`.
5. **Write the entry namespace.** `your-app/core.cljs` requires `[re-frame.core :as rf]` and `[re-frame.adapter.reagent :as reagent-adapter]`, then calls `(rf/init! reagent-adapter/adapter)` **before any dispatch or render**. The exported entry symbol is `init` (matching the template's `:init-fn ...core/init`). → [`references/entry-namespace.md`](references/entry-namespace.md) for the canonical shape, the React-root `defonce` pattern, and the order-of-operations contract.
6. **Write the first counter** — schema + event + sub + view + mount, end-to-end. → [`references/first-counter.md`](references/first-counter.md). That leaf is Reagent-specific (`reg-view`, auto-injected `dispatch`/`subscribe`); **UIx / Helix do NOT use `reg-view`** — their views read subs via `use-subscribe` and dispatch via `(:dispatch (rf/capture-frame))`, so copy the substrate `views.cljs` from [`references/entry-namespace.md` §UIx / Helix greenfield](references/entry-namespace.md) instead (or use the generator, cardinal rule 5). Never combine a UIx/Helix entry ns with the Reagent `reg-view` counter.
7. **Run and verify.** `npx shadow-cljs watch app` → open `http://localhost:<port>/`. Counter visible, the `+1` button increments the number. **Done.**

## Done checklist

- [ ] `deps.edn` lists `day8/re-frame2`, `day8/re-frame2-reagent`, `day8/re-frame2-schemas`, `day8/re-frame2-xray` at the same VERSION, plus an explicit `reagent/reagent`.
- [ ] `deps.edn` defines the `:shadow` alias (`thheller/shadow-cljs` + `org.clojure/tools.namespace`, `:extra-paths ["test" "dev"]`) that `shadow-cljs.edn`'s `{:deps {:aliases [:shadow]}}` names — without it the build classpath won't resolve.
- [ ] `package.json` lists `react`, `react-dom`, `shadow-cljs`.
- [ ] `npm install` completes without errors.
- [ ] `shadow-cljs.edn` has one `:builds` entry for the app, `:init-fn` pointing at the entry ns's `init`, and `:devtools/preloads [day8.re-frame2-xray.preload]`.
- [ ] `index.html` has the `[data-rf-xray-host]` aside beside `#app`.
- [ ] Entry ns calls `(rf/init! reagent-adapter/adapter)` before any render.
- [ ] Entry ns requires `re-frame.schemas` + `re-frame.schemas.malli` and attaches the app-db schema via `(rf/reg-app-schema [] CounterDb)` inside the boot `(rf/with-frame :rf/default …)` scope.
- [ ] `shadow-cljs watch <build-id>` compiles cleanly.
- [ ] Browser shows the counter and the `+1` button increments it.

Hand off: *"Setup is done. Switch to **`re-frame2`** for events/subs/machines/schemas/frames/fx. The Xray panel is already open on the right (day-one preload) — load **`re-frame2-xray`** to tour its tabs. For live REPL inspection, install **`re-frame2-pair`**."*

## Troubleshooting (common build failures)

- **`Could not locate re-frame/core.cljs`** — artefact not on classpath. Check `deps.edn` and that `shadow-cljs.edn` reads it. Run `clojure -Stree` and search the output for `re-frame2` (the agent reads/filters the tree; no shell `grep` — keeps it Windows/PowerShell-friendly and inside the skill's `clojure -Stree` grant).
- **`Could not locate reagent/dom/client.cljs`** (or any missing **CLJS namespace**) — a Maven/classpath problem, **not** an npm one (`npm install` won't fix it). `reagent.dom.client` ships in the `reagent/reagent` Maven coordinate. Confirm `reagent/reagent` is in `deps.edn` (the explicit day-one pin — `references/deps-versions.md`), that `shadow-cljs.edn` reads the build classpath (`{:deps {:aliases [:shadow]}}` naming a real `:shadow` alias), and that the dep resolves (run `clojure -Stree`, look for `reagent/reagent`).
- **`Cannot find module 'react'` / `react-dom/client` / shadow "The required JS dependency … is not available"** — **JS module-resolution** failures (npm side): `react` / `react-dom` not installed or wrong version. A missing npm package always surfaces as a *JS module* error, never a missing `.cljs` namespace (distinct from the row above). **Recover on the pinned baseline:** restore the `react` / `react-dom` entries in `package.json` to the pinned `implementation/package.json` versions ([`references/deps-versions.md`](references/deps-versions.md) §`package.json`), then plain `npm install`. Don't run bare `npm install react react-dom` — it writes `latest`, breaking reproducibility (cardinal rule 1) and risking a React/Reagent mismatch. Latest is explicit opt-in only: install with explicit pins (e.g. `npm install react@19 react-dom@19`) and confirm before writing. Reagent 2.x needs React 19.
- **Counter doesn't update, no errors** — `(rf/init! reagent-adapter/adapter)` not called, or called after `rdc/render`. Move it to the top of `init`.
- **Blank page, no console errors** — `index.html` missing `<main id="app">` / `<div id="app">`, or entry ns looking up a different id.
- **Xray logs missing layout host** — add the true-inline host markup/CSS from `references/shadow-cljs.md`, or configure `{:rf.xray/layout-host-selector "..."}` before Xray auto-opens. The same actionable diagnostic is available through `window.day8.re_frame2_xray.status()`.
- **Build error `Use of undeclared Var your-app.core/init`, or a runtime `your_app.core.init is not a function`** — `:init-fn` in `shadow-cljs.edn` doesn't match the entry-ns's exported symbol. Check `(defn ^:export init [] ...)` matches `:init-fn your-app.core/init`.

Anything else: point at `re-frame2` or `SKILL-REDIRECT.md`.

## Reference files (all one level deep)

- [`references/deps-versions.md`](references/deps-versions.md) — discover the current re-frame2 VERSION; lockstep contract; per-feature artefact decisions; `deps.edn` + `package.json` shapes.
- [`references/shadow-cljs.md`](references/shadow-cljs.md) — minimal `shadow-cljs.edn`; `index.html`; `:asset-path` + `:devtools`.
- [`references/entry-namespace.md`](references/entry-namespace.md) — canonical `core.cljs`; `rf/init!` before render; the React-root `defonce` pattern.
- [`references/first-counter.md`](references/first-counter.md) — end-to-end worked example mirroring `examples/core/counter/core.cljs`.
