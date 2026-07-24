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
2. **All ten artefacts ship at the same VERSION** (and the `day8/re-frame2-xray` devtools panel rides the same line). Mixing versions across `day8/re-frame2-*` coordinates is unsupported. Use the single `<VERSION>` the author pinned at kickoff.
3. **The day-one shape matches the generator template.** Core + Reagent adapter + `-schemas` + `-xray` (plus an explicit `reagent/reagent` pin) are the **Reagent-route** day-one deps. The **UIx route ships no Xray pieces** — its day-one framework set is three coords (core + `-uix` + `-schemas`), matching the template's `_uix` emission (rule 4). The remaining per-feature artefacts (`-machines` / `-routing` / `-flows` / `-http` / `-ssr` / `-epoch`) are pay-as-you-go — add them only when the author writes code that uses them.
4. **Reagent adapter is the default reference substrate.** Unless the author explicitly says UIx, scaffold against Reagent. For a UIx greenfield the substrate-neutral dataflow (events + sub + schema) is **single-sourced** in [`references/shared-dataflow.md`](references/shared-dataflow.md), copied verbatim; the substrate-specific layers (deps swap + Xray-free build wiring + entry-ns root API + substrate views) → [`references/entry-namespace.md` §UIx greenfield](references/entry-namespace.md). **Xray is Reagent-route-only today**: its panel shell renders through the ratom-family substrates and cannot mount on element-shaped React substrates, so the UIx route ships no preload, no host column, and no Xray deps. Devtools on that route: Story and the `re-frame2-pair` tooling, which work on every substrate. **Freehand** — re-frame2's re-frame-native view layer — is not a scaffold option here: `day8/re-frame2-freehand` has no published coordinate yet, so a greenfield project starts on an adapter and moves later if it wants to (that move is the `reagent-migration` skill). Neither is the `re-frame.ui` donor substrate, which Freehand absorbs.
5. **The generator template is a USER-RUN route, not one this skill executes.** The skill's executable path is the **manual scaffold** below (six steps); its `allowed-tools` cover `clojure -Stree`, npm, and `shadow-cljs watch/compile`, but *not* `clojure -Tnew create`. When you steer the author toward the one-command generator, hand them the command to run — the skill never invokes `-Tnew` on their behalf. (Pre-publish the standalone template repo is unpublished, so the `io.github.day8/…` coord can't resolve and the author uses the `:local/root` dev route instead; full caveat in [`README.md` §Relationship to the generator template](README.md#relationship-to-the-generator-template).)
6. **Don't write tests for the author.** This skill stops at "the counter mounts."
7. **nREPL is dev-only and bound to localhost.** Anywhere this skill mentions nREPL (shadow-cljs's default REPL, `re-frame2-pair` attachment), it must remind the author: nREPL is a remote-evaluation surface — never expose it on `0.0.0.0` or a public interface. shadow-cljs binds to localhost by default; do not override that without an isolated, trusted-network reason.

## Canonical greenfield path (six steps)

**Two in-scope starting points, one path.** From a *brand-new* directory you write every file below fresh. On an *empty CLJS project* (shadow-cljs / Clojure already present but zero re-frame2 wiring — the second in-scope situation) you **merge into** the files that exist rather than overwriting them: add the day-one coords + `:shadow` alias to the existing `deps.edn` (don't replace it), add the deps to the existing `package.json`, and fold the `:app` build + `:devtools/preloads` into the existing `shadow-cljs.edn` (keep the author's other `:builds`). Reuse their existing mount-point id, dev-http port, and `:output-dir`/`:asset-path` if set — the reference shapes are the *target*, not a literal overwrite. If the existing project already has substantial app code or other state management, it isn't greenfield — route to `re-frame2` (see When NOT to use).

1. **Discover the current artefact VERSION.** → [`references/deps-versions.md`](references/deps-versions.md). Day-one deps match the generator template: `day8/re-frame2` + `day8/re-frame2-reagent` + `day8/re-frame2-schemas` + `day8/re-frame2-xray`, plus an explicit `reagent/reagent`. (Reagent route; the UIx day-one set is core + `-uix` + `-schemas` — no Xray, cardinal rule 3.)
2. **Add the day-one artefacts to `deps.edn`, plus the `:shadow` build alias.** The `:shadow` alias (supplying `thheller/shadow-cljs` + `org.clojure/tools.namespace` and the `test`/`dev` extra-paths) is required — the paired `shadow-cljs.edn` reads its build classpath from it via `{:deps {:aliases [:shadow]}}`. Omitting it is the most common first-`watch` failure. → `references/deps-versions.md` §`deps.edn`.
3. **Add `react`, `react-dom`, `shadow-cljs`, and — on the Reagent route — the Xray npm deps `@xyflow/react` + `elkjs` to `package.json`; run `npm install`.** The Xray pair is day-one on Reagent: the preload's machine canvas imports both at compile time, so omitting them fails the first `watch`. (The UIx route skips the pair — it ships no preload to import them.) → `references/deps-versions.md` §`package.json`.
4. **Write `shadow-cljs.edn` and `index.html`.** One `:target :browser` build, `:init-fn your-app.core/init`, `:source-paths` including `src/`, and — on the Reagent route — the `:devtools/preloads [day8.re-frame2-xray.preload]` Xray wiring. `index.html` carries the true-inline `[data-rf-xray-host]` layout column beside `#app`, where Xray auto-opens on load. → [`references/shadow-cljs.md`](references/shadow-cljs.md) for the exact build shape, the host-column geometry, the `index.html`, and the `.gitignore`. (UIx: use the Xray-free variants in [`references/entry-namespace.md` §UIx greenfield](references/entry-namespace.md) — no preload, no host column.)
5. **Write `src/your_app/core.cljs` — the whole counter in one file.** Schema + event + sub + view + mount, end-to-end, with `(rf/init! reagent-adapter/adapter)` **before any dispatch or render** and the exported `init` entry symbol (matching the template's `:init-fn ...core/init`). → [`references/first-counter.md`](references/first-counter.md) is the **sole copy-complete `core.cljs`** — copy it as the file body; → [`references/entry-namespace.md`](references/entry-namespace.md) explains the boot lifecycle behind it (why `init!` runs before render, the React-root `defonce` pattern, the order-of-operations contract). That counter is Reagent-specific (`reg-view`, auto-injected `dispatch`/`subscribe`); **UIx does NOT use `reg-view`** — its views read subs via `use-subscribe` and get `dispatch` off the `use-frame` hook (capture-frame in hook position). For that substrate the **complete** emitted project is the substrate-neutral `events.cljs` + `subs.cljs` + `schema.cljs` from [`references/shared-dataflow.md`](references/shared-dataflow.md) **plus** the substrate `core.cljs` + `views.cljs` from [`references/entry-namespace.md` §UIx greenfield](references/entry-namespace.md) (or use the generator, cardinal rule 5). Never combine a UIx entry ns with the Reagent `reg-view` counter, and never copy the Reagent-only `first-counter.md` file into a UIx app.
6. **Run and verify.** `npx shadow-cljs watch app` → open `http://localhost:<port>/`. Counter visible, the `+1` button increments the number. **Done.**

## Done checklist

You're done when all of these hold:

- [ ] `clojure -Stree` resolves the day-one artefacts — `day8/re-frame2`, `day8/re-frame2-reagent`, `day8/re-frame2-schemas`, `day8/re-frame2-xray` at one VERSION, plus `reagent/reagent` — and the build classpath resolves (the `:shadow` alias `shadow-cljs.edn` names via `{:deps {:aliases [:shadow]}}` is defined; omitting it is the most common first-`watch` failure).
- [ ] `npm install` completes and `react` / `react-dom` / `shadow-cljs` / `@xyflow/react` / `elkjs` are present.
- [ ] `npx shadow-cljs watch app` compiles cleanly — no missing-namespace or classpath errors.
- [ ] The browser shows the counter and the `+1` button increments the number.
- [ ] The Xray panel auto-opens in the right-side `[data-rf-xray-host]` column beside `#app` (the day-one `:devtools/preloads` wiring).
- [ ] app-db validates against `CounterDb` after each event — `core.cljs` requires **only** `re-frame.schemas` (which self-wires its Malli adapter; **no** separate `re-frame.schemas.malli` require) and attaches the schema at boot via `(rf/reg-app-schema [] {:frame :rf/default} CounterDb)` — the explicit frame target, valid before the `frame-root` mount creates the frame.

The checklist above is the Reagent walk. On the **UIx route** the same criteria apply against its own day-one set (core + `-uix` + `-schemas`, plus `com.pitch/uix.core` / `uix.dom`; npm is just `shadow-cljs` / `react` / `react-dom`) — **skip the Xray bullets**: this route ships no Xray pieces, so there is no panel to auto-open and no `@xyflow/react` / `elkjs` to install (cardinal rule 4).

Hand off: *"Setup is done. Switch to **`re-frame2`** for events/subs/machines/schemas/frames/fx. The Xray panel is already open on the right (day-one preload, Reagent route) — load **`re-frame2-xray`** to tour its tabs. For live REPL inspection, install **`re-frame2-pair`**."* On the UIx route, say instead that Xray rides the ratom-family substrates today, so there is no panel — Story and `re-frame2-pair` cover the devtools story on every substrate.

## Troubleshooting (common build failures)

- **`Could not locate re-frame/core.cljs`** — artefact not on classpath. Check `deps.edn` and that `shadow-cljs.edn` reads it. Run `clojure -Stree` and search the output for `re-frame2` (the agent reads/filters the tree; no shell `grep` — keeps it Windows/PowerShell-friendly and inside the skill's `clojure -Stree` grant).
- **`Could not locate reagent/dom/client.cljs`** (or any missing **CLJS namespace**) — a Maven/classpath problem, **not** an npm one (`npm install` won't fix it). `reagent.dom.client` ships in the `reagent/reagent` Maven coordinate. Confirm `reagent/reagent` is in `deps.edn` (the explicit day-one pin — `references/deps-versions.md`), that `shadow-cljs.edn` reads the build classpath (`{:deps {:aliases [:shadow]}}` naming a real `:shadow` alias), and that the dep resolves (run `clojure -Stree`, look for `reagent/reagent`).
- **`Cannot find module 'react'` / `react-dom/client` / shadow "The required JS dependency … is not available"** — **JS module-resolution** failures (npm side): `react` / `react-dom` not installed or wrong version, or — on the Reagent route — the Xray npm deps `@xyflow/react` / `elkjs` missing (the preload's machine canvas imports both at compile time; the UIx route ships neither). A missing npm package always surfaces as a *JS module* error, never a missing `.cljs` namespace (distinct from the row above). **Recover on the pinned baseline:** restore the missing entries in `package.json` to the pinned `implementation/package.json` versions ([`references/deps-versions.md`](references/deps-versions.md) §`package.json`), then plain `npm install`. Don't run bare `npm install react react-dom` — it writes `latest`, breaking reproducibility (cardinal rule 1) and risking a React/Reagent mismatch. Latest is explicit opt-in only: install with explicit pins (e.g. `npm install react@19 react-dom@19`) and confirm before writing. Reagent 2.x needs React 19.
- **Counter doesn't update, no errors** — `(rf/init! reagent-adapter/adapter)` not called, or called after `rdc/render`. Move it to the top of `init`.
- **Blank page, no console errors** — `index.html` missing `<main id="app">` / `<div id="app">`, or entry ns looking up a different id.
- **Xray logs missing layout host** — add the true-inline host markup/CSS from `references/shadow-cljs.md`, or configure `{:rf.xray/layout-host-selector "..."}` before Xray auto-opens. The same actionable diagnostic is available through `window.day8.re_frame2_xray.status()`.
- **Build error `Use of undeclared Var your-app.core/init`, or a runtime `your_app.core.init is not a function`** — `:init-fn` in `shadow-cljs.edn` doesn't match the entry-ns's exported symbol. Check `(defn ^:export init [] ...)` matches `:init-fn your-app.core/init`.

Anything else: point at `re-frame2` or `SKILL-REDIRECT.md`.

## Reference files (all one level deep)

- [`references/deps-versions.md`](references/deps-versions.md) — discover the current re-frame2 VERSION; lockstep contract; per-feature artefact decisions; `deps.edn` + `package.json` shapes.
- [`references/shadow-cljs.md`](references/shadow-cljs.md) — minimal `shadow-cljs.edn`; `index.html`; `:asset-path` + `:devtools`.
- [`references/entry-namespace.md`](references/entry-namespace.md) — the entry-namespace boot lifecycle; `rf/init!` before render; the React-root `defonce` pattern; the UIx substrate `core.cljs` + `views.cljs` deltas.
- [`references/first-counter.md`](references/first-counter.md) — the sole copy-complete Reagent `core.cljs` (end-to-end worked counter, mirroring `examples/core/counter/core.cljs`).
- [`references/shared-dataflow.md`](references/shared-dataflow.md) — the substrate-neutral counter dataflow (`events.cljs` + `subs.cljs` + `schema.cljs`), single-sourced for the UIx split-file recipe (Reagent inlines the same registrations in `first-counter.md`).
