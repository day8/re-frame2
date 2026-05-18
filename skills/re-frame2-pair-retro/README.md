# re-frame2-pair-retro

> ↑ [`skills/`](../) — index of all six re-frame2 skills.

`re-frame2-pair-retro` is a Claude ***meta-skill*** for [`re-frame2-pair`](../re-frame2-pair/). It reviews a user's `re-frame2-pair` session, identifies friction and wasted effort, and suggests how `re-frame2-pair` itself could be improved to become a better pair programmer.

It is the re-frame2 sibling of [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver), which targets the v1 [`re-frame-pair`](https://github.com/day8/re-frame-pair) tool against [`re-frame`](https://github.com/day8/re-frame) v1.

It is designed for retrospectives like:

- "What was frustrating about this re-frame2-pair session?"
- "What took longer than it should have?"
- "Which parts of this workflow should re-frame2-pair absorb?"
- "Can you draft or file a GitHub issue for the best improvement idea?"

It focuses on evidence from the session itself: retries, confusion, workarounds, stale or empty results, missing observability, brittle platform behavior, hidden prerequisites, and trust gaps. It then proposes improvements at the right layer: `SKILL.md`, scripts/runtime ops, warnings/results, tests/fixtures, or — when the friction is caused by the framework rather than the pair tool — an upstream GitHub issue against `day8/re-frame2`.

It can draft a GitHub issue (against `day8/re-frame2-pair` for tool-side friction or `day8/re-frame2` for upstream framework friction), but only if the user wants that.

It is intentionally diagnosis-first: the default outcome is a better understanding of what went wrong and which improvements would matter most, not pressure to contribute code or file issues.

## Repo contents

- `SKILL.md` — the skill itself
- `references/analysis-lenses.md` — friction taxonomy and prioritization prompts (re-frame2-aware)
- `references/known-frictions.md` — recurring classes of product friction to pattern-match against
- `references/issue-template.md` — GitHub-issue drafting structure (with the shell-safety pattern for transcript-derived bodies)
- `.claude-plugin/plugin.json` — plugin packaging metadata
- `agents/openai.yaml` — UI metadata for skill lists and invocation

## Relationship to other repos

- [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — the pair tool this skill reviews. Sessions with that tool are this skill's input.
- [`re-frame2`](https://github.com/day8/re-frame2) — the framework. When friction is caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings), GitHub issues route here, not to `re-frame2-pair`.
- [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver) — the v1 sibling that targets v1 `re-frame-pair`.

This skill does **not** depend on or reference `re-frame-10x`. re-frame2's Tool-Pair surfaces (`register-trace-cb!`, `register-epoch-cb!`, `epoch-history`, `restore-epoch`, `app-schemas`, source-coord annotation) replace the v1 reliance on the 10x dev tool.

## Typical output

A good run of the skill produces:

1. the user's actual goal
2. the main friction points observed in the session
3. likely root causes
4. 2-5 high-leverage improvement ideas
5. optional GitHub-issue candidates (against `day8/re-frame2-pair` and/or `day8/re-frame2`), with draft text or direct filing only after approval

## Status

Pre-alpha. Ports the v1 `re-frame-pair-improver` skill structure to target re-frame2. Expected to evolve as `re-frame2-pair` matures and as more re-frame2 sessions surface novel friction patterns.

## Install

`re-frame2-pair-retro` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. There is no separate npm package or plugin registry entry — clone re-frame2 and reference the skill from `skills/re-frame2-pair-retro/`.

### Install the skill in Claude Code

#### Global — for you, across any repo

Clone the re-frame2 repo somewhere stable, then symlink the skill subdirectory into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-pair-retro ~/.claude/skills/re-frame2-pair-retro
```

Best when you want the skill available everywhere and are happy to `git pull` updates in one place.

#### Project-local — for your whole team via the repo

Copy the skill into the project's own `.claude/skills/re-frame2-pair-retro/` and commit it. Teammates who clone the repo and open Claude Code there get the same pinned version.

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame2-pair-retro .claude/skills/re-frame2-pair-retro
git add .claude/skills/re-frame2-pair-retro
```

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you want one install shared across repos.
- **Project-local** if your team wants one pinned, shared version.
- **Both** is fine — the project-local install takes precedence when both are present.
