# re-frame2-setup

> ↑ [`skills/`](..) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` **scaffold a fresh [re-frame2](https://github.com/day8/re-frame2) ClojureScript project** — from an empty directory to a working, mounted counter.

This is the **greenfield bootstrap** companion to the main [`re-frame2`](../re-frame2) skill. The two split the work:

- The main `re-frame2` skill teaches the re-frame2 **API** — events, subs, machines, schemas, frames, fx, flows, routing, SSR.
- `re-frame2-setup` owns the bootstrap and the version-sensitive wiring — the artefact, shadow-cljs, and React pins that move release to release.

Once the counter mounts, the author switches to the main `re-frame2` skill (for writing application code) or [`re-frame2-pair`](../re-frame2-pair) (for live-runtime pair-programming).

## Relationship to the generator template

re-frame2 also ships a one-command project generator —
`day8/re-frame2-template`, a [deps-new](https://github.com/seancorfield/deps-new)
template living under [`tools/template/`](../../tools/template) in
the monorepo today (planned external home
`github.com/day8/re-frame2-template` — see
[`tools/template/spec/005-Repo-Split.md`](../../tools/template/spec/005-Repo-Split.md)).
Invoke as `clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`.

> **Pre-split / pre-release caveat.** The standalone `day8/re-frame2-template`
> repo isn't published yet (see [`005-Repo-Split.md`](../../tools/template/spec/005-Repo-Split.md) §4),
> so the `io.github.day8/…` invocation above can't auto-resolve against a
> released template today. Until the split lands, scaffold via the working
> `:local/root` dev route against a checkout of this repo:
> ```bash
> clojure -Sdeps '{:deps {day8/re-frame2-template {:local/root "tools/template"}}}' \
>         -Tnew create :template day8/re-frame2-template :name acme/my-app
> ```
> (or just follow this skill's manual seven-step path). The published
> invocation is forward-correct and will work once the repo split and first
> release land. See [`tools/template/README.md`](../../tools/template/README.md) for both routes.

The two routes are complementary, not redundant, and run by different actors: the **template** is a user-run `clojure -Tnew create …` command; **this skill** executes the manual seven-step scaffold instead (its `allowed-tools` deliberately exclude `-Tnew`). When it steers you toward the generator it hands you the command to run — see [`SKILL.md` cardinal rule 5](SKILL.md). Both routes land on the same canonical scaffold.

| Use the **template** when… | Use this **skill** when… |
|---|---|
| You're starting from an empty directory and want a working app in one command. | You're starting greenfield — a brand-new app, or an **empty** CLJS project (shadow-cljs / Clojure present but **zero re-frame2 wiring**) — and want each step explained. (Adding re-frame2 to a **non-trivial** existing app — one with its own state management or substantial code — is an authoring task: route to [`re-frame2`](../re-frame2), not here. See [`skills/README.md` §Skill routing](../README.md#disqualifiers-vocabulary-alone-is-not-enough).) |
| You want canonical defaults baked in (Reagent + shadow-cljs + counter sample). | You want to understand each step the template performs, or deviate from it. |
| You don't care to learn the wiring. | You want the wiring explained as you go, with citations into `spec/` and worked examples. |

Either way you end up at the same canonical shape — the skill walks the
seven-step path manually and lands on the template's day-one scaffold
(core + Reagent adapter + schemas + Xray, `init` entry symbol); the
template performs the same steps for you in one command. After the
counter mounts, the same handoff to `re-frame2` / `re-frame2-pair`
applies.

## What it covers

The canonical seven-step greenfield path:

1. Discover the current re-frame2 VERSION (the eleven artefacts ship in lockstep; Xray rides the same line).
2. Add the day-one deps to `deps.edn` — `day8/re-frame2` + `day8/re-frame2-reagent` + `day8/re-frame2-schemas` + `day8/re-frame2-xray`, plus an explicit `reagent/reagent`.
3. Add `react`, `react-dom`, `shadow-cljs` to `package.json`. Run `npm install`.
4. Write a minimal `shadow-cljs.edn` for a single-page Reagent app (with the Xray `:devtools/preloads` wiring), plus `resources/public/index.html` carrying the `[data-rf-xray-host]` column.
5. Write the entry namespace — `(rf/init! reagent-adapter/adapter)`, the Reagent root, `(defn ^:export init [] ...)`.
6. Write the first counter — registered event, registered sub, `reg-view`-defined view, mount.
7. Run `npx shadow-cljs watch app`. Visit the dev server. Click the buttons. Done.

## What it deliberately does NOT cover

- Re-frame2's API surface (events, subs, machines, schemas, ...) — that's the main `re-frame2` skill.
- Live REPL inspection of the running app — that's [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair).
- Migrating an existing re-frame v1 codebase to v2 — that's a different problem; see [`migration/from-re-frame-v1/README.md`](https://github.com/day8/re-frame2/blob/main/migration/from-re-frame-v1/README.md).
- Test infrastructure, CI, deployment — out of scope. The author chooses their own.
- Anything beyond Reagent + shadow-cljs. The canonical path is Reagent + shadow-cljs. For a UIx or Helix greenfield, `references/entry-namespace.md` §UIx / Helix greenfield gives the two adapter substitutions this skill hand-wires; the fastest non-Reagent path is the **user-run** generator template's complete `_uix/` / `_helix/` variants (`clojure -Tnew create ... :substrate :uix`, run by the author — see "Relationship to the generator template" above for who runs what).

## Status

Pre-alpha. The skill's load-bearing code snippets are compile-tested and drift-guarded in re-frame2's CI: an opt-in real `compile app` of the skill's own fenced blocks (`setup-skill-scaffold-compiles-test`, behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`), plus two cheap prose/structural guards on every relevant change — `scripts/check_skill_setup_counter_drift.py` (counter vocabulary + `:init-fn` hot-reload lifecycle wording) and `tests/setup_drift_test.clj` (the build-discipline, UIx/Helix template-pin, Xray-host, CSP dev/prod, and publication-state contracts; run locally with `bb tests/setup_drift_test.clj`). The prose guards assert the skill teaches the right shapes; the opt-in compile closes the buildability gap for in-repo coords (a published-coordinate buildability gate stays deferred to publication). The content is grounded against `examples/core/counter/core.cljs` and the deps shapes in `implementation/`. Fuller test-infra notes: [`spec/design.md` §Testing & drift guards](spec/design.md).

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
│   └── setup_drift_test.clj
└── evals/
    └── evals.json
```

`SKILL.md` is the router: it walks the seven-step canonical path and links to the leaf in `references/` whenever depth is useful. The four reference files are each one level deep — Claude reads them in full when the corresponding step needs more detail. No leaf depends on another leaf; they can be read in any order. `spec/` carries skill-internal design/authoring meta-docs, `tests/` holds the structural drift guard (`bb tests/setup_drift_test.clj`), and `evals/` holds the trigger-accuracy fixture — all excluded from the npm `files` array by design.

## Install the skill in Claude Code

`re-frame2-setup` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. There is no separate npm package or plugin registry entry yet — clone re-frame2, **check out a specific release tag or commit**, **review the skill's `SKILL.md` and reference leaves before installing** (the skill grants `Bash(...)` access to a small set of build/install commands; you should know what you're authorising), and then reference the skill from `skills/re-frame2-setup/`.

Skills under `~/.claude/skills/` are agent instructions with shell access. Treat installation the same way you would treat installing any other plugin — pin the checkout you link from to a reviewed tag, read the code, install deliberately.

**Link, never copy** (the repo-wide policy). Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy. Clone the monorepo, check out a release tag you've reviewed, then run the cross-platform installer, which **links** every skill in the monorepo into `~/.claude/skills/` so the active skill is the reviewed checkout by construction:

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

(Adding re-frame2 to a **non-trivial** existing app is authoring, not greenfield setup — see the relationship table above.)

### Explicit — slash command

```
/re-frame2-setup
```

…or name it in a prompt:

> Using re-frame2-setup, walk me through bootstrapping a counter app.

### What happens

Claude reads `SKILL.md` and walks the seven-step path. For each step, it reads the matching `references/` leaf only if the step needs depth (which is most of them, since the leaves carry the actual concrete shapes — `deps.edn` entries, `shadow-cljs.edn`, the entry-ns skeleton, the counter source).

When all seven steps are done and the counter is visible, Claude says so and points you at the main `re-frame2` skill for everything after that.

## Cross-link

- [re-frame2](https://github.com/day8/re-frame2) — the framework itself.
- [re-frame2 main skill](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) — the API-writing companion skill that takes over once setup is done.
- [re-frame2-pair](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — live-runtime pair-programming skill for an already-running re-frame2 app.
- [Examples directory](https://github.com/day8/re-frame2/tree/main/examples) — worked re-frame2 apps (counter, login, todomvc, 7GUIs, realworld, ssr, routing).
- [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) — canonical pointer table to the full spec corpus, guide, API reference, migration guide.

## License

MIT
