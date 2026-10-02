# re-frame2-setup

Scaffolds a fresh re-frame2 ClojureScript project from nothing — deps, npm packages, `shadow-cljs.edn`, entry namespace, first counter — and stops when the counter mounts.

## Kickoff

> Using re-frame2-setup, scaffold a re-frame2 app for me.

Use an empty project directory with Node/npm, **Java 21+ and the Clojure CLI** available. The skill either writes the files directly or, if you ask for it, runs the deps-new generator; both routes need the CLI, because the scaffold uses shadow-cljs's `:deps` mode, which delegates to the CLI even when launched through npm. The skill checks `java -version` and `clojure -Sdescribe` before writing anything. The deps-new tool itself is needed only for the generator route.

## Project choices

An unqualified request needs no clarification round. You get project `acme/my-app` (namespace `acme.my-app`, build `:app`, dev port `8280`) on the Reagent adapter, at the versions the project template pins. Name the project, a version, "latest", UIx, or the deps-new generator in your request to override the matching default. A name with no `/` is doubled: `my-app` becomes `my-app/my-app`, namespace `my-app.my-app`.

An explicit framework revision also controls dependency resolution: the skill uses its local checkout only when it matches your requested pin. Otherwise it uses the requested full `:git/sha` for core, the adapter and Story, without moving the checkout.

## From scaffold to running app

The skill runs the setup commands itself:

1. Writes the scaffold from the project template. Before a Clojars release, it resolves the framework and Story through the matching checkout or pinned git dependency described above.
2. Installs, then runs a terminating `npx shadow-cljs compile app` and the included starter test (`npm test` on the default scaffold).
3. Starts the watch and reports the URL it printed. If 8280 is taken, it moves the port and reports the one the watch actually used. The watch keeps running in the background; stop it when you are finished.

A successful compile proves the build; the starter-test result proves the tested event and subscription behaviour. Open the reported URL and click `+1` to confirm the count advances from 0 to 1. Also check that saving a view repaints without losing the count and that `#/stories` opens the counter story. The handoff separates the verified build, test and server results from these browser checks, which remain unverified until observed.

Nothing else is set up on day one: schemas, Xray and the rest attach later, on request, and writing further tests, schemas or features is the [`re-frame2`](re-frame2.md) skill's job. UIx instead of Reagent is a swap of a few files, on explicit request. Fresco is not a scaffold option: a new project starts on an adapter and can move its views later with [reagent-migration](reagent-migration.md).

## Existing build configuration

In a project that already has build tooling, it merges into your `deps.edn`, `package.json` and `shadow-cljs.edn` rather than overwriting them, and keeps your other builds, mount point and dev port. A shadow-cljs project with no `deps.edn` gets one, because until re-frame2 is on Clojars it resolves only through `deps.edn` coordinates; your existing dependencies and source paths move into it.

Every `day8/re-frame2*` framework artefact ships at one version, and the skill keeps them in lockstep; mixing versions is unsupported. Story and Xray, the tools, carry that same version but release on their own `story-v*` / `xray-v*` tags, so a tool release can lag the framework's.

## When to reach for it

Use it when you are starting from nothing: a new directory, or a ClojureScript project that has build tooling but no re-frame2 wiring yet. It also picks up a freshly scaffolded project whose counter, event or subscription fails to compile because the build does not yet know re-frame2.

For related work:

- A project already on re-frame2, or an existing app with substantial code or other state management → [re-frame2](re-frame2.md). Setup is for a project with no app code yet.
- A re-frame v1 project → [re-frame-migration](re-frame-migration.md).

## When it stops

- **Java or the Clojure CLI is missing or too old** — it reports the failing check and the tool to install, and writes nothing.
- **The project name is not a legal npm package name** (for example `acme/_private`) — it writes nothing, names the rule, and asks for another name.
- **The deps-new `-Tnew` tool is missing on the generator route** — it hands you the install line rather than installing it, or falls back to writing the files directly.
- **The directory already holds substantial app code or other state management** — that is not greenfield; it routes you to [re-frame2](re-frame2.md).

## Troubleshooting

| Symptom | Check |
|---|---|
| `Could not find artifact day8/re-frame2` | Replace unresolved Maven coordinates with the chosen local or git route, including Story on the `:dev` alias. |
| A missing ClojureScript namespace, such as `reagent.dom.client` | Check the Clojure dependency and the active `:shadow` / `:dev` aliases; `npm install` cannot supply a ClojureScript namespace. |
| `Cannot find module` for React or another JavaScript package | Restore the template's package pins and run `npm install`. |
| `:rf.error/no-adapter-installed` | Call `rf/init!` with the adapter before mounting the root. |
| Blank page or `main.js` 404 | Check the mount id, `:output-dir`, `:asset-path` and the HTML script path together. |

The full symptom list and fixes are in [`SKILL.md` §Troubleshooting](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md#troubleshooting-common-build-failures).

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md) contains the full workflow and links to its [reference notes](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup/references).
