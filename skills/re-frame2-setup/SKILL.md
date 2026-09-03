---
name: re-frame2-setup
description: >
  Greenfield-only bootstrap for re-frame2 ClojureScript projects. Scope:
  brand-new apps from nothing, or empty CLJS projects (shadow-cljs / Clojure
  already present but zero re-frame2 wiring). Writes the canonical twelve-file
  counter SPA the generator template emits — core + the Reagent adapter,
  `shadow-cljs.edn`, the entry namespace with `rf/init!`, events / subs /
  views — then installs, compiles, serves it and reports the URL, exiting once
  the counter mounts. **Do not use** for writing app code on an
  already-bootstrapped project (use `re-frame2`), v1→v2 migration
  (`re-frame-migration`), live-app inspection (`re-frame2-pair`), or porting
  re-frame2 itself (`re-frame2-implementor`); the full disqualifier list is
  `skills/README.md` §Skill routing. Trigger on "start a re-frame2 project",
  "scaffold re-frame2", "hello-world re-frame2 app", or a build failure on a
  freshly-scaffolded project that traces to missing `re-frame.core` /
  `re-frame.adapter.reagent` wiring.
allowed-tools:
  - Bash(clojure -Stree)
  - Bash(clojure -Stree:*)
  - Bash(clojure -Tnew create *)
  - Bash(clojure -Sdeps * -Tnew create *)
  - Bash(java -version)
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

**One prompt produces one served SPA — zero-interview, skill-executed.** An unqualified greenfield request ("scaffold a re-frame2 app for me") takes **no clarification round**: the Reagent substrate, the generator template's reviewed baseline as the version pin, the template's reference project identity (`acme/my-app` — namespace `acme.my-app`, build id `:app`, dev port `8280`), and the smallest runnable counter. An author-supplied project name, pin, explicit "latest", or explicit UIx request overrides the matching default; the absence of any of them is never a reason to stop and ask. And the skill is the **executor**: it writes the files, runs `npm install`, runs a terminating `npx shadow-cljs compile app`, starts the watch, and reports the URL — it does not hand the author a to-do list.

This skill teaches **only re-frame2-specific wiring**. Assume the author knows `deps.edn`, `npm`, `shadow-cljs`. It does not teach re-frame2's API — that's `re-frame2`'s job.

## When NOT to use

Not for: adding re-frame2 to an existing non-trivial app (authoring), writing application code on a working v2 project, live-app inspection, v1→v2 migration, or spec / architecture / porting questions about re-frame2 itself. Route any non-setup question to the right skill; don't improvise here. Full disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).

## Cardinal rules

1. **The default scaffold is the generator's emission, and it is small.** [`references/first-counter.md`](references/first-counter.md) carries the twelve files the template emits — `deps.edn`, `package.json`, `shadow-cljs.edn`, `.gitignore`, `index.html`, `app.css`, `core.cljs`, `events.cljs`, `subs.cljs`, `views.cljs`, `events_test.cljs`, `README.md` — with direct dependencies limited to Clojure, ClojureScript, `day8/re-frame2`, the adapter, the view library, shadow-cljs, React and ReactDOM. **Nothing else is day-one**: no schemas, no Xray, no Story, no HTTP, no CSP or hosting policy. Each of those is a later, explicit step the generated README's *Next steps* links; install or explain one only when the author asks for it. The file bodies are derived from `tools/template/` and drift-locked — never hand-maintain a second template.
2. **Pins come from the template's baseline; never silently chase "latest from npm".** The leaf's `deps.edn` / `package.json` already carry the reviewed pins: `day8/re-frame2*` at one VERSION (all ten artefacts ship in lockstep; mixing versions is unsupported) plus `reagent/reagent`, `shadow-cljs`, `react`, `react-dom`. When the author supplies no pin, those are the pins — no lookup, no question. An author-supplied pin overrides them on every `day8/re-frame2*` line; "latest" is explicit opt-in only. → [`references/deps-versions.md`](references/deps-versions.md).
3. **Reagent is the default substrate; UIx is a three-file swap on explicit request.** The UIx project is the same twelve files with `deps.edn`, `core.cljs` and `views.cljs` replaced by the trio in [`references/entry-namespace.md` §UIx greenfield](references/entry-namespace.md#uix-greenfield). **UIx does NOT use `reg-view`** — its views read subs via `use-subscribe` and take `dispatch` off `use-frame` — so never combine the Reagent `views.cljs` with a UIx entry ns. **Hicasso** (re-frame2's re-frame-native view layer) is not a scaffold option: it is pre-publication with no released coordinate, so a greenfield project starts on an adapter and moves later if it wants to (the `reagent-migration` skill).
4. **Two routes, both the skill's to run.** An unqualified request writes the leaf's files — the manual route: deterministic, nothing beyond the skill's grants. When the author asks for the generator, the skill runs `clojure -Tnew create …` itself; pre-publish that is the `:local/root` invocation with the **absolute** path of `tools/template` inside the reviewed checkout the skill was installed from, run from the directory that should *contain* the project — exact command, the `-Tnew` prerequisite and the two-coordinates caveat in [`README.md` §Running the generator pre-publish](README.md#running-the-generator-pre-publish). `-Tnew` is deps-new's globally-installed tool; the skill's grants cover `-Tnew create` but not `clojure -Ttools`, so if it is missing, hand the author the install line rather than installing it, or fall back to the manual route. Both routes land on the same files, and both continue at step 2 below.
5. **Don't write tests for the author.** The scaffold's `events_test.cljs` is the template's starter (`npm test` runs it); beyond it, this skill stops at "the counter mounts."
6. **nREPL is dev-only and bound to localhost.** Anywhere this skill mentions nREPL (shadow-cljs's default REPL, `re-frame2-pair` attachment), it must remind the author: nREPL is a remote-evaluation surface — never expose it on `0.0.0.0` or a public interface. shadow-cljs binds to localhost by default; do not override that without an isolated, trusted-network reason.

## Canonical greenfield path (six steps)

**Two in-scope starting points, one path.** From a *brand-new* directory you write every file fresh. On an *empty CLJS project* (shadow-cljs / Clojure already present but zero re-frame2 wiring — the second in-scope situation) you **merge into** the files that exist rather than overwriting them: add the two `day8/re-frame2*` coords + the view-library pin + the `:shadow` alias to the existing `deps.edn` (don't replace it), add the three npm deps to the existing `package.json`, and fold the `:app` build (and the `:test` build if you keep the starter test) into the existing `shadow-cljs.edn` — keep the author's other `:builds`, mount-point id, dev-http port and `:output-dir` / `:asset-path` if set; the leaf's shapes are the *target*, not a literal overwrite. If the existing project already has substantial app code or other state management, it isn't greenfield — route to `re-frame2` (see When NOT to use).

**The shadow-only project has no `deps.edn`, and pre-publish it needs one.** An ordinary empty shadow-cljs project resolves its classpath from `shadow-cljs.edn`'s own `:dependencies [[...]]` vector, which shadow feeds to its Maven resolver — Maven coordinates only, so it can express neither `:local/root` nor `:git/url`. Those are exactly the two shapes that work before re-frame2 is on Clojars (step 2), so on this input there is no coordinate to add and writing `[day8/re-frame2 "0.0.1.alpha"]` into `:dependencies` 404s at resolution. Convert instead: write a `deps.edn` whose `:paths` carries the author's existing source dirs, whose `:deps` carries whatever was in `:dependencies` plus the day-one coords, and whose `:shadow` alias carries `thheller/shadow-cljs` at the version the project already uses; then replace `shadow-cljs.edn`'s `:dependencies` with `{:deps {:aliases [:shadow]}}`, keeping the author's `:builds`. **Move the source dirs in the same edit** — once the classpath comes from `deps.edn`, shadow warns about and ignores `shadow-cljs.edn`'s `:source-paths`, so any dir left only there drops off the classpath. The leaf's split is the model: app dirs on `deps.edn` `:paths`, test dirs on the `:shadow` alias's `:extra-paths`. Post-publish the Maven-only `:dependencies` form becomes workable again and the conversion is unnecessary; it is the pre-publish arm of the same publication-state branch as step 2.

**Before you run anything, check `java -version` succeeds.** shadow-cljs is a JVM program and `npx shadow-cljs` only wraps it, so with no JDK on `PATH` every command below — and `clojure -Tnew` on the generator route — dies before compiling anything, wearing an error that says nothing about re-frame2. If it fails, report that the project needs a JDK and stop; don't debug the wrapper.

1. **Write the twelve files** from [`references/first-counter.md`](references/first-counter.md), each at the path its heading names, byte for byte. That leaf is the whole default; nothing else needs reading on this route. (Author-supplied name → rename `acme` / `my-app` consistently, as the leaf describes. Explicit UIx → swap the three files, cardinal rule 3.)
2. **Point the two framework coordinates at something that resolves.** The leaf's `deps.edn` carries `day8/re-frame2` and `day8/re-frame2-reagent` as `{:mvn/version "…"}` — forward-correct, but re-frame2 is not on Clojars yet, so today those lines fail resolution (`Could not find artifact day8/re-frame2`). Pre-publish, replace the two maps with `:local/root` paths into the reviewed checkout this skill was installed from — `{:local/root "<RE_FRAME2>/implementation/core"}` and `{:local/root "<RE_FRAME2>/implementation/adapters/reagent"}` (UIx: `…/adapters/uix`) — where `<RE_FRAME2>` is the absolute path the skill resolves itself (forward slashes on every OS; `SKILL.md`'s own location is `<RE_FRAME2>/skills/re-frame2-setup/SKILL.md`). The same step follows the generator route. Post-publish, leave the `:mvn/version` lines alone. An author without a checkout, or who wants a self-contained `deps.edn`, takes the `:git/sha` form → [`references/deps-versions.md`](references/deps-versions.md) §Choosing the coordinate.
3. **`npm install`.** The three npm deps are already pinned in `package.json`; nothing to confirm.
4. **`npx shadow-cljs compile app`** — the terminating check; it must exit 0 (no missing-namespace or classpath errors). Never hand this command to the author: run it.
5. **`npx shadow-cljs watch app`** — start the dev server. **Read the URL off the watch's own dev-http line, never out of `shadow-cljs.edn`.** The scaffold asks for `http://localhost:8280/`, but `:dev-http` binds a fixed port and a second `watch` in another project is the ordinary way for it to be taken: shadow then reports the bind failure and that address serves whoever got there first, so a URL read from config sends the author to someone else's app rather than to an error. If 8280 is bound, change the port, restart the watch, and report the one it actually printed.
6. **Report and hand off.** The skill runs both commands itself. Compile success proves the build, **not the mount**: hand off with *"compiled and serving at `<the URL the watch printed>` — open it and click `+1`; the counter should advance 0 → 1"* rather than claiming the browser mounted. **Done.**

## Done checklist

You're done when all of these hold:

- [ ] The twelve files exist at their paths, and `clojure -Stree` resolves `day8/re-frame2` + `day8/re-frame2-reagent` at one VERSION (step 2 pointed them at a checkout or a commit) plus `reagent/reagent`, and the build classpath resolves (the `:shadow` alias `shadow-cljs.edn` names via `{:deps {:aliases [:shadow]}}` is defined; omitting it is the most common first-`watch` failure).
- [ ] `npm install` completes and `react` / `react-dom` / `shadow-cljs` are present.
- [ ] `npx shadow-cljs compile app` exits 0 — the skill ran it; no missing-namespace or classpath errors — and `npx shadow-cljs watch app` is serving the URL it printed (`http://localhost:8280/` unless the port was already taken and you moved it).
- [ ] The browser shows the heading `acme/my-app`, a `+1` button and `0`, and the button advances the number — the author confirms this in the open page; compile success alone does not prove the mount.

On the **UIx route** the same criteria apply with `day8/re-frame2-uix` + `com.pitch/uix.core` / `uix.dom` in place of the Reagent pair; npm is still just `shadow-cljs` / `react` / `react-dom` (cardinal rule 3).

Hand off with the facts first: the files written, the verification command that succeeded (`npx shadow-cljs compile app`), and the URL being served — the one the watch printed, not the one in `shadow-cljs.edn` — asking the author to open it and click `+1` (don't claim the mount from the compile). Then: *"Setup is done. Switch to **`re-frame2`** for events / subs / machines / schemas / frames / fx. The generated `README.md`'s Next steps names the optional attachments — Xray, the in-app devtools panel (`re-frame2-xray` tours it once it is in), and Story — and for live REPL inspection install **`re-frame2-pair`**."*

## Troubleshooting (common build failures)

- **`Could not find artifact day8/re-frame2 … in central` (or `-reagent`)** — the framework is not on Clojars; step 2 was skipped or a coordinate was left as `:mvn/version`. Point both `day8/re-frame2*` lines at the checkout (`:local/root`) or a commit (`:git/sha`) — [`references/deps-versions.md`](references/deps-versions.md).
- **`Could not locate re-frame/core.cljs`** — artefact not on classpath. Check `deps.edn` and that `shadow-cljs.edn` reads it. Run `clojure -Stree` and search the output for `re-frame2` (the agent reads/filters the tree; no shell `grep` — keeps it Windows/PowerShell-friendly and inside the skill's `clojure -Stree` grant).
- **`Could not locate reagent/dom/client.cljs`** (or any missing **CLJS namespace**) — a Maven/classpath problem, **not** an npm one (`npm install` won't fix it). `reagent.dom.client` ships in the `reagent/reagent` Maven coordinate. Confirm `reagent/reagent` is in `deps.edn` (the explicit day-one pin the leaf carries), that `shadow-cljs.edn` reads the build classpath (`{:deps {:aliases [:shadow]}}` naming a real `:shadow` alias), and that the dep resolves (run `clojure -Stree`, look for `reagent/reagent`).
- **`Cannot find module 'react'` / `react-dom/client` / shadow "The required JS dependency … is not available"** — **JS module-resolution** failures (npm side): `react` / `react-dom` not installed or wrong version. A missing npm package always surfaces as a *JS module* error, never a missing `.cljs` namespace (distinct from the row above). **Recover on the pinned baseline:** restore the entries in `package.json` to the pinned versions the leaf carries ([`references/deps-versions.md`](references/deps-versions.md) §`package.json`), then plain `npm install`. Don't run bare `npm install react react-dom` — it writes `latest`, breaking reproducibility (cardinal rule 2) and risking a React/Reagent mismatch. Latest is explicit opt-in only: install with explicit pins (e.g. `npm install react@19 react-dom@19`) and confirm before writing. Reagent 2.x needs React 19.
- **Counter doesn't update, no errors** — `(rf/init! reagent-adapter/adapter)` not called, or called after `reagent-adapter/render!`. Move it to the top of `init`.
- **Blank page, no console errors** — `index.html` missing `<main id="app">`, or entry ns looking up a different id.
- **Build error `Use of undeclared Var acme.my-app.core/init`, or a runtime `acme.my_app.core.init is not a function`** — `:init-fn` in `shadow-cljs.edn` doesn't match the entry-ns's exported symbol. Check `(defn ^:export init [] ...)` matches `:init-fn acme.my-app.core/init`.

Anything else: point at `re-frame2` or `SKILL-REDIRECT.md`.

## Reference files (all one level deep)

- [`references/first-counter.md`](references/first-counter.md) — the default scaffold: the twelve files, derived from the generator template, with their verification and first-run failures. The only leaf the default route reads.
- [`references/deps-versions.md`](references/deps-versions.md) — the lockstep contract; the default pins and how to override them; the pre-publish `:local/root` / `:git/sha` coordinate shapes; the pay-as-you-go per-feature artefacts.
- [`references/shadow-cljs.md`](references/shadow-cljs.md) — the build config and page explained key by key; hot reload; the `:test` build; `release`; nREPL.
- [`references/entry-namespace.md`](references/entry-namespace.md) — the entry-namespace boot lifecycle (`rf/init!` before render, the React-root `defonce`, `frame-root`'s ENSURE seed); the UIx three-file swap.
