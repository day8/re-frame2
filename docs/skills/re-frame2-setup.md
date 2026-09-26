# re-frame2-setup

> Scaffolds a fresh re-frame2 ClojureScript project from nothing — deps, npm packages, `shadow-cljs.edn`, entry namespace, first counter — and stops when the counter mounts.

## What it does

You start with an empty directory, or close to it: a `deps.edn` you mean to fill in, an empty `package.json`, no source. You finish with a project that compiles under `shadow-cljs watch` and mounts a working counter in the browser, ready for the [`re-frame2`](re-frame2.md) skill.

It covers only the re-frame2-specific wiring: which artefacts to add, the canonical `(rf/init! rf.adapter.reagent/adapter)` entry namespace, and a counter that exercises every layer (event → handler → app-db change → sub recompute → view re-render). It assumes you know `deps.edn`, npm and shadow-cljs themselves.

In a project that already has build tooling, it merges into your `deps.edn`, `package.json` and `shadow-cljs.edn` rather than overwriting them, and keeps your other builds, mount point and dev port. A shadow-cljs project with no `deps.edn` gets one, because until re-frame2 is on Clojars it resolves only through `deps.edn` coordinates; your existing dependencies and source paths move into it.

Every `day8/re-frame2*` framework artefact ships at one version, and the skill keeps them in lockstep; mixing versions is unsupported. Story and Xray, the tools, carry that same version but release on their own `story-v*` / `xray-v*` tags, so a tool release can lag the framework's.

## When to reach for it

Use it when **any** of these are true:

- You have just created a new directory and want re-frame2 set up in it.
- You have an empty CLJS project with build tooling but no re-frame2 wiring yet.
- You say *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"how do I set up re-frame2"*, *"give me a hello-world re-frame2 app"*.
- A freshly scaffolded project's counter, event or sub fails to compile because the build doesn't yet know `re-frame.core` or `re-frame.adapter.reagent`.

Use a different skill for:

- Writing code in a project already on re-frame2 → [re-frame2](re-frame2.md).
- Adding re-frame2 to an existing app with substantial code or other state management → [re-frame2](re-frame2.md).
- Migrating a re-frame v1 project → [re-frame-migration](re-frame-migration.md).
- Inspecting or debugging a running app → [re-frame2-pair](re-frame2-pair.md).

## Kickoff

You need **Java 21+ and the Clojure CLI**. The skill either writes the files directly or, if you ask for it, runs the deps-new generator; both routes need the CLI, because the scaffold uses shadow-cljs's `:deps` mode, which delegates to the CLI even when launched through npm. The skill checks `java -version` and `clojure -Sdescribe` before writing anything. The deps-new tool itself is needed only for the generator route.

Ask in your own words — *"scaffold a re-frame2 app for me"* — or type `/re-frame2-setup`.

An unqualified request needs no clarification round. You get project `acme/my-app` (namespace `acme.my-app`, build `:app`, dev port `8280`) on the Reagent adapter, at the versions the project template pins. Name the project, a version, "latest", UIx, or the deps-new generator in your request to override the matching default. A name with no `/` is doubled: `my-app` becomes `my-app/my-app`, namespace `my-app.my-app`.

The skill runs every command itself:

1. Writes the scaffold from the project template. re-frame2 is not on Clojars yet, so it points the re-frame2 dependencies at the re-frame2 checkout the skill was installed from, or at a `:git/sha` when there is no checkout.
2. Installs, then runs a terminating `npx shadow-cljs compile app`.
3. Starts the watch and reports the URL it printed. If 8280 is taken, it moves the port and reports the one the watch actually used. The watch keeps running in the background; stop it when you are finished.

You open the URL and click `+1` to confirm the count advances. Story, the component playground, comes wired at `#/stories`, and the scaffold carries one starter test, which `npm test` runs.

Nothing else is set up on day one: schemas, Xray and the rest attach later, on request, and writing further tests, schemas or features is the [`re-frame2`](re-frame2.md) skill's job. UIx instead of Reagent is a swap of a few files, on explicit request. Fresco is not a scaffold option: a new project starts on an adapter and can move its views later with [reagent-migration](reagent-migration.md).

## When it stops

- **Java or the Clojure CLI is missing or too old** — it reports the failing check and the tool to install, and writes nothing.
- **The project name is not a legal npm package name** (for example `acme/_private`) — it writes nothing, names the rule, and asks for another name.
- **The deps-new `-Tnew` tool is missing on the generator route** — it hands you the install line rather than installing it, or falls back to writing the files directly.
- **The directory already holds substantial app code or other state management** — that is not greenfield; it routes you to [re-frame2](re-frame2.md).

For build errors a fresh scaffold can hit — `Could not find artifact day8/re-frame2`, a missing `re-frame.core` or `reagent.dom.client` namespace, `:rf.error/no-adapter-installed`, a blank page, a `main.js` 404 — the causes and fixes are in [`SKILL.md` §Troubleshooting](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md#troubleshooting-common-build-failures).

## Where the skill lives

- Source: [`skills/re-frame2-setup/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup)
- `SKILL.md`: [`skills/re-frame2-setup/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md) — its §Reference files section says which reference note each route reads.
- Reference notes: [`skills/re-frame2-setup/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup/references)
