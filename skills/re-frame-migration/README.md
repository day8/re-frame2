# re-frame-migration

> ↑ [`skills/`](../) — index of all re-frame2 skills.

A `Skill` that helps `Claude Code` **migrate an existing re-frame v1.x ClojureScript codebase to [re-frame2](https://github.com/day8/re-frame2)** — from `re-frame/re-frame` deps to `day8/re-frame2`, mechanical rewrites applied automatically, judgment-call call sites flagged for human review.

This is the **migration** companion to the main [`re-frame2`](../re-frame2/) skill (which writes new application code) and [`re-frame2-setup`](../re-frame2-setup/) (which bootstraps a fresh re-frame2 project). The three skills cover the three orthogonal v2 authoring situations:

- **Greenfield** — `re-frame2-setup`.
- **Already on v2; writing application code** — `re-frame2`.
- **Existing v1 codebase; moving to v2** — *this skill*.

## What it covers

Two pre-flight phases plus a six-phase migration workflow:

0a. **Pre-flight — inventory-and-plan** — before everything, inventory the v1 re-frame add-on libraries (incl. transitives / git-source / vendored) and app features used, scan each add-on's **source** for removed/moved v2 surfaces (off-contract `re-frame.*`, the removed `re-frame.core/console`, `unwrap`→`unwrap-interceptor`, React-19 coupling, classpath collision), and produce a per-item migration plan (rule(s) + forced-vs-optional + disposition + replacement target + ordering). Collapses the "march the wall" whack-a-mole into one planned sweep; the umbrella that drives the floor gate, the off-contract-namespace principle, the add-on compile-gate, and the classpath-clean verification.
0b. **Pre-flight — the React-19 / Reagent-2 floor gate** — before any dep edit, audit downstream React-coupled deps, confirm the UI component library has a React-19 (Reagent-2 if Reagent-based) release, scan for legacy `ReactDOM.render` call sites, check the CLJS/shadow-cljs/Closure toolchain for version skew (an older shadow-cljs breaks on the newer Closure with a cryptic `NoSuchFieldError` that looks like a migration bug), and make an explicit go/no-go. A component library with no React-19 build is a hard blocker surfaced here, not mid-compile.
1. **Orient** — read the project's dep file; identify the substrate; skim `migration/from-re-frame-v1/README.md` for the rule index.
2. **Bump (M-0)** — swap `re-frame/re-frame` for `day8/re-frame2` + a substrate-adapter artefact. **Try a compile.** Most codebases require nothing more.
3. **Sweep** — if Phase 2 surfaced failures, walk the M-rules in order. Apply Type A (mechanical) without asking; flag Type B (judgment-call) for the author.
4. **Verify** — author runs compile + tests **plus a booted-app smoke-test** (live `app-db` / machine-snapshot introspection). "Compiles" is not the done-bar: v2 moves a large class of v1 failures to runtime, so the planned silent-fail fixes apply whether or not the compile failed, and the smoke-test must come back clean. Iterate per surfaced failures.
5. **Opt-in modernisations** (only if requested) — walk the O-rules.
6. **Report** — produce the migration summary per `migration/from-re-frame-v1/README.md` Part 2.

## What it deliberately does NOT cover

- The re-frame2 API itself (`reg-event-*`, `reg-sub`, `reg-machine`, frames, schemas, ...) — that's the main `re-frame2` skill.
- Greenfield setup — that's `re-frame2-setup`.
- Live-runtime inspection of the running v2 app — that's `re-frame2-pair`.
- Substrate migration (Reagent → UIx / Helix) — never part of a v1→v2 migration; opt-in via O-13 / O-14.
- Stylistic refactoring, naming changes, or any rewriting the author didn't ask for.
- Running the author's tests — that's general software practice, not migration-specific (per the Q14 lock from the `re-frame2` skill design).

## How the skill works

The skill is structured around `migration/from-re-frame-v1/README.md` in this repo, which is the **authoritative breaking-change list** for re-frame v1.x → re-frame2. The skill:

- Routes the workflow (6 phases).
- Sequences the rules (which to apply first, what depends on what).
- Operationalises Type A vs Type B (mechanical vs judgment-call).
- Produces the final migration summary.

It does **not** duplicate `migration/from-re-frame-v1/README.md` content. Each rule reference in the skill leaves cites an `M-N` or `O-N` rule id; the full rule text is read directly from the migration corpus.

## Status

Pre-alpha. The skill is authored; it has not yet been exercised end-to-end against a real v1 codebase migration. The structure mirrors the `re-frame2-setup` skill in this same repo. The content is grounded against `migration/from-re-frame-v1/README.md`, `docs/guide/25-from-re-frame-v1.md`, and `docs/the-mayor-method.md`'s paste-prompt pattern.

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
│   ├── setup.md                   # M-0 detail: dep-coord swap, substrate adapter picker
│   ├── xray-replaces-10x.md       # Devtools swap: re-frame-10x → Xray (preload, host, keybindings, parity)
│   ├── breaking-changes.md        # Rule index keyed to v1 trigger surfaces
│   ├── sequencing.md              # Recommended rule order
│   ├── auto-call-site-rewrites.md # Type A: per-call-site mechanical rewrites
│   ├── auto-cross-cutting.md      # Type A: cross-cutting renames, views, init, artefacts
│   ├── guided-handlers-state.md   # Type B: handler / view / db-seeding walkthroughs
│   ├── guided-interceptors-subs.md# Type B: interceptor / sub / payload walkthroughs
│   ├── output-format.md           # The migration-summary shape
│   └── error-events.md            # Pointer to Spec 009's error-event catalogue (single source)
└── spec/
    ├── design.md                  # Locked design decisions
    ├── inputs.md                  # Canonical inputs the skill leans on
    ├── improving.md               # How to find + fold in skill improvements (friction loop + quality bar)
    └── authoring-prompt.md        # One-shot reauthor prompt
```

## Install

`re-frame-migration` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` and `.claude-plugin/plugin.json` packaging metadata for eventual Agent-Skill / Claude-Code-Plugin distribution, but the supported install today is to **link** the skill from a full monorepo clone into `~/.claude/skills/` (the repo-root `scripts/install-skills.sh` / `scripts/install-skills.ps1` link every skill at once). Link, never copy — a `cp -r` snapshot drifts from the maintained source.

> **The supported install is a link from a full monorepo clone — not a standalone copy.** This skill loads the shared shell-safe filing recipe from a **sibling** directory, [`../shared/issue-filing.md`](../shared/issue-filing.md), when it files an ambiguous-rule GitHub issue. That `../shared/` path resolves **only** when the skill sits inside a full re-frame2 checkout. The npm tarball and the plugin bundle deliberately do **not** carry `shared/` (`npm pack` ships only this skill's own directory; a `files` allow-list cannot reach a sibling) — the shared leaf is a single boundary owned once under `skills/shared/`, not duplicated into each consumer package. A standalone tarball / plugin / copied install that does not also bring `skills/shared/` alongside the skill will hit a broken `../shared/issue-filing.md` link and silently lose the shell-safe `gh issue create` filing/redaction protocol. Link from a clone, or vendor `skills/shared/` as a peer.

## Source of truth

`migration/from-re-frame-v1/README.md` at the repo root. Every rule the skill applies cites an `M-N` or `O-N` rule id from that doc. If the skill and the migration corpus disagree, the corpus wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
