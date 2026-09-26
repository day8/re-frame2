# re-frame2-setup

> Scaffolds a fresh re-frame2 ClojureScript project from nothing — deps, npm packages, `shadow-cljs.edn`, entry namespace, first counter — and stops when the counter mounts.

## What it does

The `re-frame2-setup` skill bootstraps a greenfield re-frame2 project. The author starts with nothing (or close to nothing — a `deps.edn` they intend to fill in, an empty `package.json`, no source). When the skill is done, the author has a project that compiles under `shadow-cljs watch`, mounts a working counter in the browser, and is ready to switch to the [`re-frame2`](re-frame2.md) skill for writing application code.

The skill teaches **only** the re-frame2-specific wiring: which artefacts to add, the lockstep VERSION discipline (every `day8/re-frame2*` framework artefact ships at one VERSION, per [Conventions §Lockstep versioning through 1.0](../../spec/Conventions.md#lockstep-versioning-through-10); mixing versions is unsupported; Xray and Story are tools and ship on their own `xray-v*` / `story-v*` tags), the canonical `(rf/init! reagent-adapter/adapter)` entry-namespace shape, and a counter that exercises every layer end-to-end (event → handler → app-db change → sub recompute → view re-render). It does not teach `deps.edn`, `npm`, or `shadow-cljs` themselves — those are assumed.

## When to reach for it

Load this skill when **any** of these are true:

- The author has just created a new directory and wants re-frame2 set up in it.
- The author has an empty CLJS project with build tooling but no re-frame2 wiring yet.
- The author says *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"how do I set up re-frame2"*, *"give me a hello-world re-frame2 app"*.
- A freshly scaffolded project's counter / event / sub fails to compile because the build doesn't yet know what `re-frame.core` or `re-frame.adapter.reagent` is.

Do **not** use this skill for:

- Writing application code in a project that's already on re-frame2 → use [re-frame2](re-frame2.md).
- Adding re-frame2 to an existing app with substantial code or other state management → use [re-frame2](re-frame2.md).
- Migrating a re-frame v1 project → use [re-frame-migration](re-frame-migration.md).
- Inspecting / debugging a running app → use [re-frame2-pair](re-frame2-pair.md).

## Kickoff

Both setup routes require Java 21+ and the Clojure CLI. The skill checks
`java -version` and `clojure -Sdescribe` before writing or converting a project:
the scaffold uses shadow-cljs's `:deps` mode, which delegates to the CLI even
when launched through npm. The deps-new tool is needed only for the generator route.

Ask in your own words — *"scaffold a re-frame2 app for me"* — or type `/re-frame2-setup`. An unqualified request takes no clarification round: the project is `acme/my-app` (namespace `acme.my-app`, build `:app`, dev port `8280`) on the Reagent adapter at the generator template's reviewed pins. Name the project, a version pin, "latest", UIx, or the deps-new generator in the request to override the matching default; a name with no `/` is doubled, so `my-app` becomes `my-app/my-app` and namespace `my-app.my-app`.

The skill writes the canonical scaffold straight from the generator template's own emission, points the framework coordinates at the reviewed checkout while re-frame2 is unpublished (or at a `:git/sha` when there is no checkout), installs, runs a terminating `npx shadow-cljs compile app`, starts the watch and reports the URL — it runs every command itself. It reads that URL off the watch's own output rather than out of `shadow-cljs.edn`: if 8280 is already taken it moves the port and reports the one the watch printed. The Reagent adapter is the default reference substrate and UIx a swap of a few files on explicit request; Story, the component playground, comes wired at `#/stories`, and nothing else beyond the counter is day-one — schemas, Xray and the rest attach later, on request. The skill stops at *"the counter mounts"* — you open the URL and click `+1` to confirm the count advances, and writing tests, schemas, or further features is the next skill's job.

## When it stops

- **Java or the Clojure CLI is missing or too old** — it reports the failing check and the tool to install, and writes nothing.
- **The project name is not a legal npm package name** (for example `acme/_private`) — it writes nothing, names the rule, and asks for another name.
- **The deps-new `-Tnew` tool is missing on the generator route** — it hands you the install line rather than installing it, or falls back to writing the files directly.
- **The directory already holds substantial app code or other state management** — that is not greenfield; it routes you to [re-frame2](re-frame2.md).

For the build errors a fresh scaffold can hit — `Could not find artifact day8/re-frame2`, a missing `re-frame.core` or `reagent.dom.client` namespace, `:rf.error/no-adapter-installed`, a blank page, a `main.js` 404 — the causes and fixes are in [`SKILL.md` §Troubleshooting](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md#troubleshooting-common-build-failures).

## Where the skill lives

- Source: [`skills/re-frame2-setup/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup)
- `SKILL.md`: [`skills/re-frame2-setup/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md)
- Reference leaves: [`skills/re-frame2-setup/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup/references) — [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md) §Reference files says which leaf each route reads.
