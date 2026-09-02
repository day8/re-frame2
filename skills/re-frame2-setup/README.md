# re-frame2-setup

> ↑ [`skills/`](..) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` scaffold a fresh [re-frame2](https://github.com/day8/re-frame2) ClojureScript project — from an empty directory to a working, mounted counter.

This is the greenfield bootstrap companion to the main [`re-frame2`](../re-frame2) skill. The 2 split the work:

- the main `re-frame2` skill teaches the re-frame2 API — events, subs, machines, schemas, frames, fx, flows, routing, SSR
- `re-frame2-setup` owns the bootstrap and the version-sensitive wiring — the artefact, shadow-cljs, and React pins that move release to release

Once the counter mounts, the author switches to the main `re-frame2` skill (for writing application code) or [`re-frame2-pair`](../re-frame2-pair) (for live-runtime pair-programming).

## Relationship to the generator template

re-frame2 also ships a one-command project generator —
`day8/re-frame2-template`, a [deps-new](https://github.com/seancorfield/deps-new)
template living under [`tools/template/`](../../tools/template) in
the monorepo today (planned external home
`github.com/day8/re-frame2-template` — see
[`tools/template/spec/005-Repo-Split.md`](../../tools/template/spec/005-Repo-Split.md)).
Invoke as `clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`.

> Pre-split / pre-release caveat: the standalone `day8/re-frame2-template`
> repo isn't published yet (see [`005-Repo-Split.md`](../../tools/template/spec/005-Repo-Split.md) §4),
> so the `io.github.day8/…` invocation above can't auto-resolve against a
> released template today. Until the split lands, scaffold via the working
> `:local/root` dev route against a checkout of this repo — see
> [Running the generator pre-publish](#running-the-generator-pre-publish) below for the
> exact command (or just follow this skill's manual 6-step path). The published
> invocation is forward-correct and will work once the repo split and first
> release land. See [`tools/template/README.md`](../../tools/template/README.md) for both routes.

The 2 routes are complementary, not redundant — and both are the skill's to execute: an unqualified request runs the manual scaffold (it writes the exact files), and when you ask for the generator route the skill runs the `clojure -Tnew create …` command itself (its `allowed-tools` cover it) — see [`SKILL.md` cardinal rule 4](SKILL.md). Both routes land on the same canonical scaffold: the manual route's twelve files are the template's own emission for its reference project `acme/my-app`, rendered into [`references/first-counter.md`](references/first-counter.md) by `tests/first_counter_derivation.clj` and drift-locked against the template in two test tiers.

| Use the **template** when… | Use this **skill** when… |
|---|---|
| You're starting from an empty directory and want a working app in one command. | You're starting greenfield — a brand-new app, or an **empty** CLJS project (shadow-cljs / Clojure present but **zero re-frame2 wiring**) — and want each step explained. (Adding re-frame2 to a **non-trivial** existing app — one with its own state management or substantial code — is an authoring task: route to [`re-frame2`](../re-frame2), not here. See [`skills/README.md` §Skill routing](../README.md#disqualifiers-vocabulary-alone-is-not-enough).) |
| You want canonical defaults baked in (Reagent + shadow-cljs + counter sample). | You want to understand each step the template performs, or deviate from it. |
| You don't care to learn the wiring. | You want the wiring explained as you go, with citations into `spec/` and worked examples. |

Either way you end up with the same twelve files — core + the Reagent
adapter + `reagent/reagent`, a two-build `shadow-cljs.edn`, the
`init` / `^:dev/after-load mount!` entry namespace, split events / subs /
views, a starter `events_test.cljs`, the page, its stylesheet, a
`.gitignore` and a README; no schemas, Xray, Story or CSP on day one.
The skill walks the 6-step path and writes them; the template emits
them in one command. After the counter mounts, the same handoff to
`re-frame2` / `re-frame2-pair` applies.

### Running the generator pre-publish

The pre-publish command has **two independent coordinates**, and one shared working
directory cannot carry both — conflating them is what made the earlier documented form
fail on its first use:

- **Where the template comes from** is the `:local/root`. It must be the **absolute** path
  to `tools/template` inside your reviewed re-frame2 checkout. `:local/root` is resolved
  against the *command's* working directory, so a relative `"tools/template"` means
  `<the directory you are standing in>/tools/template` — which, from a fresh project
  directory, does not exist, and the command dies with
  `Local lib day8/re-frame2-template not found` before deps-new loads the template.
- **Where the project lands** is the working directory. deps-new creates the project in a
  new child directory named after `:name`'s artefact, so run the command from the
  directory that should *contain* the new project folder.

Write the absolute path with **forward slashes on every OS** — Java accepts them on
Windows too, and it keeps the EDN string free of hand-authored `\\` escaping:

```bash
# Standing in the directory that should CONTAIN the new project.
# <RE_FRAME2> = absolute path of your reviewed re-frame2 checkout,
# e.g. /home/you/code/re-frame2 or C:/Users/you/code/re-frame2
clojure -Sdeps '{:deps {day8/re-frame2-template {:local/root "<RE_FRAME2>/tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app
```

That emits `./my-app/`. Add `:substrate :uix` for the UIx variant. The emitted
`deps.edn` carries the two `day8/re-frame2*` coordinates as `:mvn/version`, which
does not resolve until the framework is on Clojars — so the generator route continues
exactly where the manual one does, at [`SKILL.md`](SKILL.md) step 2: point those two
coordinates at the reviewed checkout with `:local/root` (`<RE_FRAME2>/implementation/core`
and `<RE_FRAME2>/implementation/adapters/reagent`, or `…/adapters/uix`), then
`cd my-app && npm install && npx shadow-cljs compile app && npx shadow-cljs watch app`.

Already standing *inside* the empty directory you want the app generated into? Add
deps-new's own target options — `:target-dir . :overwrite true`. The `:overwrite` is
required because deps-new refuses an existing target directory
(`. already exists (and :overwrite was not true)`), and `.` always exists.

**The skill resolves `<RE_FRAME2>` itself** — it is installed by link from a reviewed
checkout ([Install the skill in Claude Code](#install-the-skill-in-claude-code)), so
`SKILL.md`'s own resolved location is `<RE_FRAME2>/skills/re-frame2-setup/SKILL.md` and
the template is that path's grandparent plus `tools/template`. If the skill was reached
some other way and no such checkout is on disk, say so and fall back to the manual 6-step
path rather than guessing a path.

## What it covers

The canonical 6-step greenfield path:

1. Write the twelve files from `references/first-counter.md` — the generator template's own emission for `acme/my-app`, with the template's reviewed pins already in `deps.edn` / `package.json` (no question asked; an author-supplied pin, name or UIx request overrides). Day-one deps are `day8/re-frame2` + `day8/re-frame2-reagent` + `reagent/reagent`, and `react` / `react-dom` / `shadow-cljs` on the npm side — nothing else.
2. Point the two `day8/re-frame2*` coordinates at something that resolves — pre-publish, `:local/root` into the reviewed checkout the skill was installed from (the same step after the generator route).
3. Run `npm install`.
4. Run the terminating `npx shadow-cljs compile app` — it must exit 0.
5. Start `npx shadow-cljs watch app` — the skill runs both commands itself.
6. Report the files, the command that succeeded and the URL (`http://localhost:8280/`); you open the page and click `+1`. Done.

## What it deliberately does not cover

- re-frame2's API surface (events, subs, machines, schemas, ...) — that's the main `re-frame2` skill
- live REPL inspection of the running app — that's [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- migrating an existing re-frame v1 codebase to v2 — that's a different problem; see [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md)
- test infrastructure, CI, deployment — out of scope. The author chooses their own.
- schemas, Xray, Story, HTTP, SSR, CSP or hosting on day one. The scaffold is the counter and nothing else; each of those attaches later by its own recipe (the generated README's *Next steps* links Xray and Story), and only when you ask.
- anything beyond Reagent + shadow-cljs. The canonical path is Reagent + shadow-cljs. A UIx greenfield is the same twelve files with three swapped — `deps.edn`, `core.cljs`, `views.cljs` — carried in `references/entry-namespace.md` §UIx greenfield and derived from the template's `_uix/` tree; the generator produces the same project with `clojure -Tnew create ... :substrate :uix`, which the skill runs on request (see "Relationship to the generator template" above).

## Status

Pre-alpha. The default scaffold is proven end to end, not just compiled: a black-box fixture (`setup-skill-default-scaffold-mounts-test` in `tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj`, behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`) materialises the twelve files straight out of the shipped `references/first-counter.md` into a fresh temp project, applies the documented pre-publish coordinate step, compiles the `:app` and `:test` builds, loads the real page in Chromium and clicks the counter 0 → 1, runs the starter test under Node, and proves its own teeth by breaking the mount node and the click path and requiring the same proof to go red. A sibling ungated test in that file asserts the leaf's files equal a real deps-new emission byte for byte. Three cheap structural guards run on every relevant change — `scripts/check_skill_setup_counter_drift.py` (counter vocabulary + `:init-fn` hot-reload lifecycle wording), `tests/setup_drift_test.clj` (the derivation lock, the day-one-set locks — no schemas, Xray, devtools preload or CSP on the default route — the build-discipline, UIx template-pin, publication-state and executor contracts; run locally with `bb tests/setup_drift_test.clj`), and `tests/generator_route_test.clj`, which resolves the `:local/root` out of the documented generator command exactly as `tools.deps` would — against a freshly created target directory — and fails unless it lands on the reviewed `tools/template` (`bb tests/generator_route_test.clj`; set `RF2_SETUP_RUN_GENERATOR=1` to additionally shell the real `clojure … -Tnew create …` out of a clean directory and assert the emitted manifest). A published-coordinate buildability gate stays deferred to publication. Fuller test-infra notes: [`spec/design.md` §Testing & drift guards](spec/design.md) (authoring-time meta-doc, not shipped in the package — reach it from a monorepo clone).

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
│   ├── shadow-cljs.md
│   ├── entry-namespace.md
│   └── first-counter.md
├── spec/
│   ├── design.md
│   ├── inputs.md
│   └── authoring-prompt.md
├── tests/
│   ├── first_counter_derivation.clj
│   ├── setup_drift_test.clj
│   └── generator_route_test.clj
└── evals/
    └── evals.json
```

`SKILL.md` is the router: it walks the 6-step canonical path, and the default route reads exactly one leaf — `first-counter.md`, the twelve files. The other 3 reference files are each one level deep and optional: `deps-versions.md` to override a pin or change the coordinate shape, `shadow-cljs.md` and `entry-namespace.md` to have the build and the boot explained; the UIx route reads `entry-namespace.md` for its three swapped files. No leaf depends on another. `spec/` carries skill-internal design/authoring meta-docs; `tests/` holds the derivation script that renders the two generated regions from the template (`bb tests/first_counter_derivation.clj`), the structural drift guard (`bb tests/setup_drift_test.clj`) and the generator-route command fixture (`bb tests/generator_route_test.clj`); `evals/` holds the eval fixture (trigger accuracy for all 17, plus graded outcome expectations on the start-from-nothing prompts and the generator route) — all repo-maintenance surfaces, not shipped with the skill (the `files` allow-list omits them).

## Install the skill in Claude Code

`re-frame2-setup` is distributed with the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. The supported install is a repo checkout (git clone + link) or a Claude Code marketplace plugin — it is not published to npm (the `package.json` is marked `private`). Clone re-frame2, check out a specific release tag or commit, and review the skill's `SKILL.md` and reference leaves before installing (the skill grants `Bash(...)` access to a small set of build/install commands; you should know what you're authorising). Then link the skill from `skills/re-frame2-setup/`.

Skills under `~/.claude/skills/` are agent instructions with shell access. Treat installation the same way you would treat installing any other plugin — pin the checkout you link from to a reviewed tag, read the code, install deliberately.

Link, never copy (the repo-wide policy). Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy. Clone the monorepo, check out a release tag you've reviewed, then run the cross-platform installer, which links every skill in the monorepo into `~/.claude/skills/` so the active skill is the reviewed checkout by construction:

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2
git checkout <release-tag-or-commit>     # pin to a version you've reviewed
# Review skills/re-frame2-setup/SKILL.md and references/*.md before the next line
```

Then run the cross-platform installer. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour.

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

Claude reads `SKILL.md` and `references/first-counter.md`, writes the twelve files, points the two framework coordinates at the reviewed checkout, runs `npm install` and the terminating compile, starts the watch, and reports the URL. It reads another leaf only when a step needs depth — an overridden pin, an explanation of the build or the boot, the UIx swap.

When all 6 steps are done and the counter is visible, Claude says so and points you at the main `re-frame2` skill for everything after that.

## Cross-link

- [re-frame2](https://github.com/day8/re-frame2) — the framework itself.
- [re-frame2 main skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) — the API-writing companion skill that takes over once setup is done.
- [re-frame2-pair](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — live-runtime pair-programming skill for an already-running re-frame2 app.
- [Examples directory](https://github.com/day8/re-frame2/tree/main/examples) — worked re-frame2 apps (counter, login, todomvc, 7GUIs, realworld, ssr, routing).
- [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) — canonical pointer table to the full spec corpus, guide, API reference, migration guide.

## License

MIT
