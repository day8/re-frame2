# re-frame2-setup

> ↑ [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` scaffold a fresh [re-frame2](https://github.com/day8/re-frame2) ClojureScript project — from an empty directory to a working, mounted counter.

This is the greenfield bootstrap companion to the main [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) skill. The 2 split the work:

- the main `re-frame2` skill teaches the re-frame2 API — events, subs, machines, schemas, frames, fx, flows, routing, SSR
- `re-frame2-setup` owns the bootstrap and the version-sensitive wiring — the artefact, shadow-cljs, and React pins that move release to release

Once the counter mounts, the author switches to the main `re-frame2` skill (for writing application code) or [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) (for live-runtime pair-programming).

## Relationship to the generator template

re-frame2 also ships a one-command project generator, `day8/re-frame2-template` — a [deps-new](https://github.com/seancorfield/deps-new) template under [`tools/template/`](https://github.com/day8/re-frame2/tree/main/tools/template). Both routes are this skill's to run: an unqualified request writes the files itself, and a request for the generator has the skill run `clojure -Tnew create …` ([`SKILL.md` cardinal rule 4](SKILL.md)). Both routes land on the same canonical scaffold: the manual route's thirteen files are the template's own emission for its reference project `acme/my-app`, rendered into [`references/first-counter.md`](references/first-counter.md) by `tests/first_counter_derivation.clj` and drift-locked against the template in two test tiers.

That scaffold is core + the Reagent adapter + `reagent/reagent`, a two-build `shadow-cljs.edn`, the `init` / `^:dev/after-load mount!` entry namespace, split events / subs / views, a first story Story serves at `#/stories`, a starter `events_test.cljs`, the page, its stylesheet, a `.gitignore` and a README — no schemas, Xray or CSP on day one.

Until the template has its own repository it resolves only through a `:local/root` into a checkout of this one: the exact command, its `-Tnew` prerequisite and the two coordinates it must keep apart are in [`references/generator-route.md`](references/generator-route.md), and the published `io.github.day8/re-frame2-template` coordinate is in [`tools/template/README.md`](https://github.com/day8/re-frame2/blob/main/tools/template/README.md) and [`005-Repo-Split.md`](https://github.com/day8/re-frame2/blob/main/tools/template/spec/005-Repo-Split.md).

Run the generator yourself when you want the files without an agent; use the skill when you want them written, installed, compiled, tested and served from one prompt, or merged into an empty CLJS project. Adding re-frame2 to a non-trivial existing app — one with its own state management or substantial code — is authoring, not setup: route to [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) ([`skills/README.md` §Disqualifiers](https://github.com/day8/re-frame2/blob/main/skills/README.md#disqualifiers-vocabulary-alone-is-not-enough)).

## What it covers

Both routes require Java 21+ and the Clojure CLI: check `java -version` and
`clojure -Sdescribe` before writing or converting a project. An npm-only shadow-cljs
project may not have the CLI; the scaffold's `:deps` mode requires it. The globally
installed deps-new tool is needed only for the generator route.

The skill runs [`SKILL.md`'s six-step greenfield path](SKILL.md#canonical-greenfield-path-six-steps) itself, with no clarification round: it writes the thirteen files at the template's reviewed pins (or converts and merges into an empty CLJS project), points the `day8/re-frame2*` coordinates, Story's included, at something that resolves, runs `npm install`, the terminating `npx shadow-cljs compile app` and the starter test, starts the watch detached, and reports the URL the watch printed. You open the page and click `+1`, and `#/stories` for Story.

## What it deliberately does not cover

- re-frame2's API surface (events, subs, machines, schemas, ...) — that's the main `re-frame2` skill
- live REPL inspection of the running app — that's [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- migrating an existing re-frame v1 codebase to v2 — that's a different problem; see [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md)
- test infrastructure, CI, deployment — out of scope. The author chooses their own.
- schemas, Xray, HTTP, SSR, CSP or hosting on day one. The scaffold is the counter and its story; each of those attaches later by its own recipe (the generated README's *Next steps* links Xray), and only when you ask.
- build tools other than shadow-cljs, or view layers other than Reagent and UIx. A UIx greenfield is the same thirteen files with four swapped — `deps.edn`, `core.cljs`, `views.cljs`, `stories.cljs` — carried in `references/entry-namespace.md` §UIx greenfield (the generator's `:substrate :uix`); Fresco is a later move with [`reagent-fresco-migration`](https://github.com/day8/re-frame2/tree/main/skills/reagent-fresco-migration), not a scaffold option.

## Status

Pre-alpha. The default scaffold is proven end to end, not just compiled: a black-box fixture in the generator template's suite materialises the thirteen files straight out of `references/first-counter.md`, compiles them against the in-repo source, clicks the counter 0 → 1 in Chromium and runs the starter test, and the skill's own Babashka suites under `tests/` (`bb tests/<name>.clj` from this directory) hold the prose to the template and the scaffold contract. A published-coordinate buildability gate stays deferred to publication. Every guard, what it pins and where it runs: [`spec/design.md` §Testing & drift guards](spec/design.md#9a-testing--drift-guards) (authoring-time meta-doc, not shipped in the package — reach it from a monorepo clone).

## Layout

```
skills/re-frame2-setup/
├── SKILL.md
├── README.md
├── LICENSE
├── package.json
├── .claude-plugin/
│   └── plugin.json
├── references/
│   ├── deps-versions.md
│   ├── post-publish.md
│   ├── generator-route.md
│   ├── shadow-cljs.md
│   ├── entry-namespace.md
│   ├── existing-project.md
│   └── first-counter.md
├── spec/
│   ├── design.md
│   ├── inputs.md
│   └── authoring-prompt.md
├── tests/
│   ├── first_counter_derivation.clj
│   ├── setup_drift_test.clj
│   ├── generator_route_test.clj
│   └── project_identity_test.clj
└── evals/
    └── evals.json
```

`SKILL.md` is the router: the default route reads one leaf, `first-counter.md`, and `SKILL.md` §Reference files says when each of the others loads. `spec/` carries the skill's design and authoring meta-docs; `tests/` holds the derivation script that renders the two generated regions from the template (`bb tests/first_counter_derivation.clj`) and the Babashka drift guards (`bb tests/<name>_test.clj`, each mapped in `spec/design.md` §Testing & drift guards); `evals/` holds the trigger-accuracy and graded-outcome evals. All three are repo-maintenance surfaces, not shipped with the skill (the `files` allow-list omits them).

## Install the skill in Claude Code

`re-frame2-setup` is distributed with the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. The supported install is a repo checkout (git clone + link). It is not published to npm (the `package.json` is marked `private`), and there is no Claude Code marketplace manifest in the repo — the `.claude-plugin/plugin.json` beside each skill is staged packaging metadata, not a live plugin channel. Clone re-frame2, check out a specific release tag or commit, and review the skill's `SKILL.md` and reference leaves before installing (the skill grants `Bash(...)` access to a small set of build/install commands; you should know what you're authorising). Then link the skill from `skills/re-frame2-setup/`.

Skills under `~/.claude/skills/` are agent instructions with shell access. Treat installation the same way you would treat installing any other plugin — pin the checkout you link from to a reviewed tag, read the code, install deliberately.

Link, never copy (the repo-wide policy). Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy. Clone the monorepo, check out a release tag you've reviewed, then run the cross-platform installer, which links every skill in the monorepo into `~/.claude/skills/` so the active skill is the reviewed checkout by construction:

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2
git checkout <release-tag-or-commit>     # pin to a version you've reviewed
# Review skills/re-frame2-setup/SKILL.md and references/*.md before the next line
```

Then run the cross-platform installer. See [`skills/README.md` §Installing (link, never copy)](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour.

To upgrade: `git checkout` a newer reviewed tag in the same clone — the link follows it (so review the diff before bumping). For a team following along on one project, link from a shared reviewed checkout rather than committing a `cp -r` snapshot that will go stale.

## Invoking it in Claude

### Implicit — just ask

The skill's description auto-matches when you talk about starting a new re-frame2 project from nothing (or an empty CLJS project with zero re-frame2 wiring):

> Start a new re-frame2 project for me in this directory.
>
> How do I add re-frame2 to my empty CLJS project (shadow-cljs is set up but there's no re-frame2 wiring yet)?
>
> Scaffold the smallest working re-frame2 app I can extend.

(Adding re-frame2 to a non-trivial existing app is authoring, not greenfield setup — see the relationship table above.)

### Explicit — slash command

```
/re-frame2-setup
```

…or name it in a prompt:

> Using re-frame2-setup, walk me through bootstrapping a counter app.

### What happens

Claude reads `SKILL.md` and `references/first-counter.md` and runs the path in [What it covers](#what-it-covers). It reads another leaf only when a step needs depth — an existing project to merge into, an overridden pin, an explanation of the build or the boot, the UIx swap.

The handoff asks you to open the served page, click `+1`, and check hot reload and Story. Those browser checks remain unverified until observed; compilation alone does not prove them. The main `re-frame2` skill covers code-writing after setup.

## Cross-link

- [re-frame2](https://github.com/day8/re-frame2) — the framework itself.
- [re-frame2 main skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) — the API-writing companion skill that takes over once setup is done.
- [re-frame2-pair](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — live-runtime pair-programming skill for an already-running re-frame2 app.
- [Examples directory](https://github.com/day8/re-frame2/tree/main/examples) — worked re-frame2 apps (counter, login, todomvc, 7GUIs, realworld, ssr, routing).
- [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) — canonical pointer table to the full spec corpus, guide, API reference, migration guide.

## License

MIT
