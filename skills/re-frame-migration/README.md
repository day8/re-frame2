# re-frame-migration

> ↑ [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` migrate an existing re-frame v1.x ClojureScript codebase to [re-frame2](https://github.com/day8/re-frame2) — from `re-frame/re-frame` deps to `day8/re-frame2`, mechanical rewrites applied automatically, judgment-call call sites flagged for human review.

This is the migration companion to the main [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) skill (which writes new application code) and [`re-frame2-setup`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-setup) (which bootstraps a fresh re-frame2 project). The 3 skills cover the 3 orthogonal v2 authoring situations:

- greenfield — `re-frame2-setup`
- already on v2 and writing application code — `re-frame2`
- existing v1 codebase, moving to v2 — this skill

## What it covers

2 pre-flight phases plus a 6-phase migration workflow:

0a. Pre-flight — inventory-and-plan — before everything, inventory the v1 re-frame add-on libraries (including transitives / git-source / vendored) and app features used, scan each add-on's source for removed/moved v2 surfaces (off-contract `re-frame.*`, the removed `re-frame.core/console`, a removed `re-frame.core/unwrap` `:refer` — v2 destination is handler destructuring or a project-registered interceptor, React-19 coupling, classpath collision), and produce a per-item migration plan (rule(s) + forced-vs-optional + disposition + replacement target + ordering). Collapses the "march the wall" whack-a-mole into one planned sweep; the umbrella that drives the floor gate, the off-contract-namespace principle, the add-on compile-gate, and the classpath-clean verification.
0b. Pre-flight — the React-19 / Reagent-2 floor gate — before any dep edit, audit downstream React-coupled deps, confirm the UI component library supports React 19 (Reagent 2 if Reagent-based), scan for legacy `ReactDOM.render` call sites, check the CLJS/shadow-cljs/Closure toolchain for version skew (an older shadow-cljs breaks on the newer Closure with a cryptic `NoSuchFieldError` that looks like a migration bug — a known mechanical bump carried into the M-0 pass, not a stop), and make an explicit go/no-go. A component library with no declared React-19 release is not an automatic stop — it routes to a 4-option decision (wait / replace / vendor-or-patch / force React 19 and verify empirically at runtime); an empirical pass is a valid GO, and only a library with neither a declared release nor an empirical pass is a hard blocker, surfaced here rather than mid-compile.
1. Orient — read the project's dep file; identify the substrate; skim `migration/from-re-frame-v1/README.md` for the rule index.
2. Bump (M-0) — swap `re-frame/re-frame` for `day8/re-frame2` + a substrate-adapter artefact, carrying every Phase-0b GO-state bump into the same pass (React/Reagent, any component-lib, and the shadow-cljs/CLJS toolchain bump — an older shadow-cljs left behind detonates the first compile with a cryptic `NoSuchFieldError`). The skill makes the dep-file + `package.json` edits, runs the install, and compiles. Most codebases require nothing more.
3. Apply the planned sweep — always carry forward the Phase-0a plan (forced blockers, silent-fail rules, M-70) whether or not Phase 2 compiled cleanly; if the compile also surfaced failures, additionally walk those M-rules in order. Apply Type A (mechanical) without asking; flag Type B (judgment-call) for the author.
4. Verify — the skill runs the project's own compile + tests, then the booted-app smoke-test (live `app-db` / machine-snapshot introspection) — driven through a connected runtime, or handed to the programmer as one checklist and reported pending. "Compiles" is not the done-bar: v2 moves a large class of v1 failures to runtime, so the planned silent-fail fixes apply whether or not the compile failed, and the smoke-test must come back clean. Iterate per surfaced failures.
5. Opt-in modernisations (only if requested) — walk the O-rules.
6. Report — produce the migration summary per `migration/from-re-frame-v1/README.md` Part 2.

## What it deliberately does not cover

- the re-frame2 API itself (`reg-event-*`, `reg-sub`, `reg-machine`, frames, schemas, ...) — that's the main `re-frame2` skill
- greenfield setup — that's `re-frame2-setup`
- live-runtime inspection of the running v2 app — that's `re-frame2-pair`
- substrate migration (Reagent → UIx) — never part of a v1→v2 migration; opt-in via O-13
- migrating Reagent **views** to Hicasso — that is the optional, second step after this one, [`reagent-migration`](https://github.com/day8/re-frame2/blob/main/skills/reagent-migration/SKILL.md); staying on Reagent views is a first-class, fully-supported choice
- stylistic refactoring, naming changes, or any rewriting the author didn't ask for
- interactive / visual confirmation when no drivable runtime is connected — the skill hands over one checklist and reports the smoke as pending rather than claiming completion

## How the skill works

The skill is structured around `migration/from-re-frame-v1/README.md` in this repo, which is the authoritative breaking-change list for re-frame v1.x → re-frame2. The skill:

- routes the workflow (two pre-flight phases plus six)
- sequences the rules (which to apply first, what depends on what)
- operationalises Type A vs Type B (mechanical vs judgment-call)
- produces the final migration summary

It does not duplicate `migration/from-re-frame-v1/README.md` content. Each rule reference in the skill leaves cites an `M-N` or `O-N` rule id; the full rule text is read directly from the migration corpus.

## Status

Pre-alpha. The skill has been driven end to end against one substantial real v1 codebase; that run's friction harvest — roughly two dozen generic improvement candidates, the most valuable of them found only by reaching the live runtime — is the field evidence recorded in the skill's `spec/improving.md` meta-doc (repo-only; not shipped in the package), and its friction loop is the one every later improvement follows. The structure mirrors the `re-frame2-setup` skill in this same repo. The content is grounded against `migration/from-re-frame-v1/README.md`, `docs/core/25-from-re-frame-v1.md`, and `docs/the-mayor-method/`'s paste-prompt pattern.

## Layout

```
skills/re-frame-migration/
├── SKILL.md
├── README.md
├── LICENSE
├── package.json
├── .claude-plugin/
│   └── plugin.json
├── references/
│   ├── kickoff-prompt.md          # Paste-ready prompt for a fresh session
│   ├── inventory-and-plan.md      # Phase 0a: inventory add-ons + features, scan source, per-item plan
│   ├── floor-gate.md              # Phase 0b: the React-19 / Reagent-2 floor gate (six checks + go/no-go)
│   ├── setup.md                   # M-0 detail: dep-coord swap, substrate adapter picker
│   ├── release-compile-gate.md    # Phase 4: the optimized/release compile gate (-Xss for the StackOverflow class)
│   ├── xray-replaces-10x.md       # Devtools swap: re-frame-10x → Xray (preload, host, keybindings, parity)
│   ├── breaking-changes.md        # v1→v2 rule index keyed to v1 trigger surfaces
│   ├── pre-rename-upgrades.md     # v2-pre-rename-only rule index (never loaded on a v1→v2 migration)
│   ├── async-flow-to-machines.md  # O-16: async-flow-fx async sequences → reg-machine state machines
│   ├── http-fx-to-managed-http.md # O-17: http-fx / :http-xhrio → :rf.http/managed
│   ├── sequencing.md              # Recommended rule order
│   ├── orchestrating-a-large-migration.md # Opt-in: large-migration partition + wave sequencing
│   ├── auto-call-site-rewrites.md # Type A: per-call-site mechanical rewrites
│   ├── auto-cross-cutting.md      # Type A: cross-cutting renames, views, init, artefacts
│   ├── guided-handlers-state.md   # Type B: handler / db-seeding / error-handler walkthroughs
│   ├── guided-views-m11.md        # Type B: the M-11 sweep — subscribing plain fns → reg-view (+ the async listener class)
│   ├── guided-interceptors-subs.md# Type B: interceptor / sub / payload walkthroughs
│   ├── error-events.md            # Pointer to Spec 009's error-event catalogue (single source)
│   ├── causal-world-inputs.md     # EP-0010 recording rule + EP-0017 reshape (M-72): ambient durable host reads → declared coeffects
│   ├── runtime-smoke-test.md      # Phase 4: "compiles" isn't done — the live-app-db boot smoke-test loop + done-bar gates
│   ├── silent-runtime-failures.md # Phase 4: the nine silent-runtime-failure modes as a checklist
│   └── output-format.md           # The migration-summary shape
└── spec/
    ├── design.md                  # Locked design decisions
    ├── inputs.md                  # Canonical inputs the skill leans on
    ├── improving.md               # How to find + fold in skill improvements (friction loop + quality bar)
    └── authoring-prompt.md        # One-shot reauthor prompt
```

## Install

`re-frame-migration` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. The supported install is to link the skill from a full monorepo clone into `~/.claude/skills/` (the repo-root `scripts/install-skills.sh` / `scripts/install-skills.ps1` link every skill at once). Link, never copy — a `cp -r` snapshot drifts from the maintained source. The `package.json` + `.claude-plugin/plugin.json` packaging metadata names no live channel: the `package.json` is marked `private`, and the repo carries no plugin marketplace manifest (`plugin.json` carries `"status": "pre-alpha"`). The skill is self-contained: every instruction its normal operation loads — the shell-safe filing recipe included ([`references/issue-filing.md`](references/issue-filing.md)) — ships in its own `package.json` `files` allow-list. This matches every skill in this repo — they all carry the same staged, unpublished packaging metadata behind the same install contract ([`skills/README.md` §Installing](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy) is the single source for which channels exist).

## Source of truth

`migration/from-re-frame-v1/README.md` at the repo root. Every rule the skill applies cites an `M-N` or `O-N` rule id from that doc. If the skill and the migration corpus disagree, the corpus wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
