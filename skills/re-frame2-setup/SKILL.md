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
  (use `re-frame-migration`), live-app inspection (use `re-frame-pair2`),
  or porting re-frame2 itself (use `re-frame2-implementor`). For the
  full disqualifier list and routing to sibling skills, see
  `skills/README.md` §Skill routing — single source.
allowed-tools:
  - Bash(clojure *)
  - Bash(npm *)
  - Bash(npx *)
  - Bash(shadow-cljs *)
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

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for adding re-frame2 to an existing non-trivial app (that's authoring), writing application code on a working v2 project, live-app inspection, v1→v2 migration, or spec / architecture / porting questions about re-frame2 itself.

Any non-setup question → route to the right skill; don't improvise here.

## Cardinal rules

1. **Never hardcode artefact versions in suggestions written to disk.** Ten artefacts ship in lockstep; look up the current VERSION first (see [`reference/deps-versions.md`](reference/deps-versions.md)).
2. **All ten artefacts ship at the same VERSION.** Mixing versions across `day8/re-frame2-*` artefacts is unsupported.
3. **Only add per-feature artefacts the author actually uses.** Core + adapter are mandatory; `-schemas` / `-machines` / `-routing` / `-flows` / `-http` / `-ssr` / `-epoch` are pay-as-you-go.
4. **Reagent adapter is the default reference substrate.** Unless the author explicitly says UIx or Helix, scaffold against Reagent.
5. **Don't write tests for the author.** This skill stops at "the counter mounts."

## Canonical greenfield path (seven steps)

1. **Discover the current artefact VERSION.** → [`reference/deps-versions.md`](reference/deps-versions.md). Day-one deps: `day8/re-frame2` + `day8/re-frame2-reagent` only.
2. **Add the two artefacts to `deps.edn`.** → `reference/deps-versions.md` §`deps.edn`.
3. **Add `react`, `react-dom`, `shadow-cljs` to `package.json`; run `npm install`.** → `reference/deps-versions.md` §`package.json`.
4. **Write `shadow-cljs.edn`.** One `:target :browser` build, `:init-fn your-app.core/run`, `:source-paths` including `src/`. → [`reference/shadow-cljs.md`](reference/shadow-cljs.md) for the exact shape, the `:devtools` block, and the `index.html`.
5. **Write the entry namespace.** `your-app/core.cljs` requires `[re-frame.core :as rf]` and `[re-frame.adapter.reagent :as reagent-adapter]`, then calls `(rf/init! reagent-adapter/adapter)` **before any dispatch or render**. → [`reference/entry-namespace.md`](reference/entry-namespace.md) for the canonical shape, the React-root `defonce` pattern, and the order-of-operations contract.
6. **Write the first counter.** A registered event, sub, view (`reg-view`), and mount — end-to-end in one file. → [`reference/first-counter.md`](reference/first-counter.md).
7. **Run and verify.** `npx shadow-cljs watch app` → open `http://localhost:<port>/`. Counter visible, `+`/`-` flips the number. **Done.**

## Done checklist

- [ ] `deps.edn` lists `day8/re-frame2` and `day8/re-frame2-reagent` at the same VERSION.
- [ ] `package.json` lists `react`, `react-dom`, `shadow-cljs`.
- [ ] `npm install` completes without errors.
- [ ] `shadow-cljs.edn` has one `:builds` entry for the app, `:init-fn` pointing at the entry ns.
- [ ] Entry ns calls `(rf/init! reagent-adapter/adapter)` before any render.
- [ ] `shadow-cljs watch <build-id>` compiles cleanly.
- [ ] Browser shows the counter and `+`/`-` updates it.

Hand off: *"Setup is done. Switch to **`re-frame2`** for events/subs/machines/schemas/frames/fx. For live REPL inspection, install **`re-frame-pair2`**."*

## Troubleshooting (common build failures)

- **`Could not locate re-frame/core.cljs`** — artefact not on classpath. Check `deps.edn` and that `shadow-cljs.edn` reads it (`clj -Stree | grep re-frame2`).
- **`Could not locate reagent/dom/client.cljs`** — `react` / `react-dom` not installed. `npm install react react-dom`. Reagent 2.x needs React 18+.
- **Counter doesn't update, no errors** — `(rf/init! reagent-adapter/adapter)` not called, or called after `rdc/render`. Move it to the top of `run`.
- **Blank page, no console errors** — `index.html` missing `<div id="app">`, or entry ns looking up a different id.
- **`Uncaught ReferenceError: re_frame is not defined`** — `:init-fn` in `shadow-cljs.edn` doesn't match the entry-ns `run` symbol. Check `(defn ^:export run [] ...)` matches `:init-fn your-app.core/run`.

Anything else: point at `re-frame2` or `SKILL-REDIRECT.md`.

## Reference files (all one level deep)

- [`reference/deps-versions.md`](reference/deps-versions.md) — discover the current re-frame2 VERSION; lockstep contract; per-feature artefact decisions; `deps.edn` + `package.json` shapes.
- [`reference/shadow-cljs.md`](reference/shadow-cljs.md) — minimal `shadow-cljs.edn`; `index.html`; `:asset-path` + `:devtools`.
- [`reference/entry-namespace.md`](reference/entry-namespace.md) — canonical `core.cljs`; `rf/init!` before render; the React-root `defonce` pattern.
- [`reference/first-counter.md`](reference/first-counter.md) — end-to-end worked example mirroring `examples/reagent/counter/core.cljs`.
