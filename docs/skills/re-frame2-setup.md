# re-frame2-setup

> Scaffolds a fresh re-frame2 ClojureScript project from nothing — deps, npm packages, `shadow-cljs.edn`, entry namespace, first counter — and stops when the counter mounts.

## What it does

The `re-frame2-setup` skill bootstraps a greenfield re-frame2 project. The author starts with nothing (or close to nothing — a `deps.edn` they intend to fill in, an empty `package.json`, no source). When the skill is done, the author has a project that compiles under `shadow-cljs watch`, mounts a working counter in the browser, and is ready to switch to the [`re-frame2`](re-frame2.md) skill for writing application code.

The skill teaches **only** the re-frame2-specific wiring: which artefacts to add, the lockstep VERSION discipline (all ten ship at the same version; mixing is unsupported), the canonical `(rf/init! reagent-adapter/adapter)` entry-namespace shape, and a counter that exercises every layer end-to-end (event → handler → app-db change → sub recompute → view re-render). It does not teach `deps.edn`, `npm`, or `shadow-cljs` themselves — those are assumed.

## When to reach for it

Load this skill when **any** of these are true:

- The author has just created a new directory and wants re-frame2 set up in it.
- The author has an existing CLJS project but no re-frame2 wiring yet.
- The author says *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"how do I set up re-frame2"*, *"add re-frame2 to my repo"*, *"give me a hello-world re-frame2 app"*.
- A counter / event / sub fails to compile because the build doesn't yet know what `re-frame.core` or `re-frame.adapter.reagent` is.

Do **not** use this skill for:

- Writing application code in a project that's already on re-frame2 → use [re-frame2](re-frame2.md).
- Migrating a re-frame v1 project → use [re-frame-migration](re-frame-migration.md).
- Inspecting / debugging a running app → use [re-frame2-pair](re-frame2-pair.md).

## Kickoff

The skill auto-triggers on greenfield-setup phrasings. To force-load:

```
/skill re-frame2-setup
```

The skill walks seven steps in order: discover the current artefact VERSION, add deps to `deps.edn`, add npm deps to `package.json`, write `shadow-cljs.edn`, write the entry namespace, write the first counter, run and verify. The Reagent adapter is the default reference substrate; UIx and Helix are supported but only on explicit request. The skill stops at *"the counter mounts"* — writing tests, schemas, or further features is the next skill's job.

## Where the skill lives

- Source: [`skills/re-frame2-setup/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup)
- `SKILL.md`: [`skills/re-frame2-setup/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-setup/SKILL.md)
- Reference leaves: [`skills/re-frame2-setup/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup/reference) — `deps-versions.md` (how to discover the current VERSION; lockstep contract), `shadow-cljs.md` (the minimal `shadow-cljs.edn` and `index.html` shape), `entry-namespace.md` (the canonical `core.cljs` shape; why `rf/init!` runs first), `first-counter.md` (the worked end-to-end counter).
