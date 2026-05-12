# re-frame2-setup

> ↑ [`skills/`](../) — index of all six re-frame2 skills.

A `Skill` that helps `Claude Code` **scaffold a fresh [re-frame2](https://github.com/day8/re-frame2) ClojureScript project** — from an empty directory to a working, mounted counter.

This is the **greenfield bootstrap** companion to the main [`re-frame2`](../re-frame2/) skill. The two are deliberately separate because:

- The main `re-frame2` skill teaches the re-frame2 **API** — events, subs, machines, schemas, frames, fx, flows, routing, SSR. The content there is stable across re-frame2 releases.
- Setup, by contrast, moves: artefact versions change, shadow-cljs versions change, React versions change. Pinning that content in the main skill would stale it. `re-frame2-setup` is the home for the moving target, so the main skill can stay clean.

Once the counter mounts, the author switches to the main `re-frame2` skill (for writing application code) or [`re-frame-pair2`](../re-frame-pair2/) (for live-runtime pair-programming).

## Relationship to the generator template

re-frame2 also ships a one-command project generator under
[`tools/template/`](../../tools/template/) (`clojure -X:project/new
:template re-frame2 :name acme/my-app`). The two routes are complementary,
not redundant:

| Use the **template** when… | Use this **skill** when… |
|---|---|
| You're starting from an empty directory and want a working app in one command. | You're adding re-frame2 to an existing CLJS project that already has its own `deps.edn` / `shadow-cljs.edn`. |
| You want canonical defaults baked in (Reagent + shadow-cljs + counter sample). | You want to understand each step the template performs, or deviate from it. |
| You don't care to learn the wiring. | You want the wiring explained as you go, with citations into `spec/` and worked examples. |

Either way you end up at the same canonical shape — the skill walks the
seven-step path manually; the template performs it for you. After the
counter mounts, the same handoff to `re-frame2` / `re-frame-pair2`
applies.

## What it covers

The canonical seven-step greenfield path:

1. Discover the current re-frame2 VERSION (the ten artefacts ship in lockstep).
2. Add `day8/re-frame2` + `day8/re-frame2-reagent` to `deps.edn`.
3. Add `react`, `react-dom`, `shadow-cljs` to `package.json`. Run `npm install`.
4. Write a minimal `shadow-cljs.edn` for a single-page Reagent app, plus `public/index.html`.
5. Write the entry namespace — `(rf/init! reagent-adapter/adapter)`, the Reagent root, `(defn ^:export run [] ...)`.
6. Write the first counter — registered event, registered sub, `reg-view`-defined view, mount.
7. Run `shadow-cljs watch app`. Visit the dev server. Click the buttons. Done.

## What it deliberately does NOT cover

- Re-frame2's API surface (events, subs, machines, schemas, ...) — that's the main `re-frame2` skill.
- Live REPL inspection of the running app — that's [`re-frame-pair2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2).
- Migrating an existing re-frame v1 codebase to v2 — that's a different problem; see [`MIGRATION.md`](https://github.com/day8/re-frame2/blob/main/spec/MIGRATION.md).
- Test infrastructure, CI, deployment — out of scope. The author chooses their own.
- Anything beyond Reagent + shadow-cljs. UIx, Helix, and other build tools are noted only as "swap the adapter ns / pick a different build tool"; the canonical path is Reagent + shadow-cljs.

## Status

Pre-alpha. The skill is authored; it has not yet been exercised against a fresh project end-to-end. The structure mirrors the `re-frame-pair2` skill in this same repo. The content is grounded against the canonical example in `examples/reagent/counter/core.cljs` and the deps shapes from `implementation/core/deps.edn`, `implementation/adapters/reagent/deps.edn`, and `implementation/shadow-cljs.edn`.

## Layout

```
skills/re-frame2-setup/
├── SKILL.md
├── README.md
├── LICENSE
├── package.json
├── .claude-plugin/
│   └── plugin.json
└── reference/
    ├── deps-versions.md
    ├── shadow-cljs.md
    ├── entry-namespace.md
    └── first-counter.md
```

`SKILL.md` is the router: it walks the seven-step canonical path and links to the leaf in `reference/` whenever depth is useful. The four reference files are each one level deep — Claude reads them in full when the corresponding step needs more detail. No leaf depends on another leaf; they can be read in any order.

## Install the skill in Claude Code

`re-frame2-setup` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. There is no separate npm package or plugin registry entry yet — clone re-frame2 and reference the skill from `skills/re-frame2-setup/`.

### Global — for you, across any project

Symlink (or copy) the skill into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git    # one-time, anywhere
ln -s "$(pwd)/re-frame2/skills/re-frame2-setup" ~/.claude/skills/re-frame2-setup
```

Best when you spin up new re-frame2 projects regularly.

### Project-local — for the new project itself

After step 1 of the canonical path (the project directory exists), copy the skill into `.claude/skills/re-frame2-setup/` and commit it. Useful if you want teammates following along on the same project to share the same setup guidance.

```bash
cd your-new-project
cp -r /path/to/re-frame2/skills/re-frame2-setup .claude/skills/re-frame2-setup
git add .claude/skills/re-frame2-setup
```

## Invoking it in Claude

### Implicit — just ask

The skill's description auto-matches when you talk about starting a new re-frame2 project:

> Start a new re-frame2 project for me in this directory.
>
> How do I add re-frame2 to my repo?
>
> Scaffold the smallest working re-frame2 app I can extend.

### Explicit — slash command

```
/re-frame2-setup
```

…or name it in a prompt:

> Using re-frame2-setup, walk me through bootstrapping a counter app.

### What happens

Claude reads `SKILL.md` and walks the seven-step path. For each step, it reads the matching `reference/` leaf only if the step needs depth (which is most of them, since the leaves carry the actual concrete shapes — `deps.edn` entries, `shadow-cljs.edn`, the entry-ns skeleton, the counter source).

When all seven steps are done and the counter is visible, Claude says so and points you at the main `re-frame2` skill for everything after that.

## Cross-link

- [re-frame2](https://github.com/day8/re-frame2) — the framework itself.
- [re-frame2 main skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) — the API-writing companion skill that takes over once setup is done.
- [re-frame-pair2](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2) — live-runtime pair-programming skill for an already-running re-frame2 app.
- [Examples directory](https://github.com/day8/re-frame2/tree/main/examples/reagent) — worked re-frame2 apps (counter, login, todomvc, 7GUIs, realworld, ssr, routing).
- [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) — canonical pointer table to the full spec corpus, guide, API reference, migration guide.

## License

MIT
