# re-frame2-pair-retro

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-pair-retro` is a Claude meta-skill for [`re-frame2-pair`](../re-frame2-pair). It reviews a user's `re-frame2-pair` session, identifies friction and wasted effort, and suggests how `re-frame2-pair` itself could be improved to become a better pair programmer.

It is the re-frame2 sibling of [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver), which targets the v1 [`re-frame-pair`](https://github.com/day8/re-frame-pair) tool against [`re-frame`](https://github.com/day8/re-frame) v1.

It is designed for retrospectives like:

- "What was frustrating about this re-frame2-pair session?"
- "What took longer than it should have?"
- "Which parts of this workflow should re-frame2-pair absorb?"
- "Can you draft a GitHub issue for the best improvement idea?"

One explicit request over one clear session returns the complete retrospective in one response — the user's actual goal, the friction the session evidenced (retries, confusion, workarounds, stale or empty results, missing observability, brittle platform behaviour, hidden prerequisites, trust gaps), why `re-frame2-pair` was not enough, and the smallest credible change at the right owner: the pair tool itself, or — when the friction is caused by the framework's Tool-Pair contract rather than the tool — upstream `re-frame2`.

The skill is **read-only**. On request it includes one focused, copy-pasteable GitHub issue draft against `day8/re-frame2` — the monorepo where the pair tool ships, alongside the framework — with the tool-vs-framework distinction carried in the draft's title and body. The user owns whether and how to file it: the skill never runs `gh issue create`, never writes files, never edits a repo, and never probes a live runtime (live inspection routes to `re-frame2-pair`). Its only shell surface is optional read-only duplicate search (`gh issue list` / `gh issue view`).

It is intentionally diagnosis-first: the default outcome is a better understanding of what went wrong and which improvements would matter most, not pressure to contribute code or file issues.

## Directory contents

- `SKILL.md` — the skill itself: entry modes, guard rails, the session-evidence invariants, the retrospective and issue-draft contract
- `README.md` — this human-facing intro
- `references/known-frictions.md` — recurring classes of product friction to pattern-match against, loaded on demand when a session smells like a known class
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) for re-authoring the skill; not loaded during normal operation, and — like `evals/` — excluded from the npm `files` array by design (repo-maintenance artifacts that run from a full clone, not material a packaged consumer re-runs)
- `evals/evals.json` — trigger-accuracy fixtures (which prompts should and should not activate the skill); a repo-maintenance artifact, also excluded from the npm package `files` array
- `tests/duplicate_search_test.clj` — the skill's only test: a command-contract pin over the duplicate-search `gh issue list` argv `SKILL.md` prescribes, run by CI when this skill's paths change. Also a repo-maintenance artifact, excluded from the npm `files` array
- `.claude-plugin/plugin.json` — plugin packaging metadata
- `agents/openai.yaml` — UI metadata for skill lists and invocation
- `package.json`, `LICENSE` — npm packaging metadata and the MIT licence

Everything the skill loads during normal operation ships under this directory — `SKILL.md`, whose routing boundary is stated locally rather than linked from the repo-level skills index, plus the one on-demand `references/` leaf. There is no runtime dependency on any sibling directory.

## Relationship to other repos

- [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — the pair tool this skill reviews. Sessions with that tool are this skill's input, and live-runtime work of any kind routes back to it.
- [`re-frame2`](https://github.com/day8/re-frame2) — the framework. When friction is caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings), the issue draft names the framework surface — and still targets this monorepo's GitHub issues, since the pair tool ships here too.
- [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver) — the v1 sibling that targets v1 `re-frame-pair`.

This skill does not depend on or reference `re-frame-10x`. re-frame2's Tool-Pair surfaces replace the v1 reliance on the 10x dev tool; an upstream finding names the specific missing or under-specified surface in the draft.

## Typical output

A good run of the skill produces, in one response:

- the user's actual goal and the friction the session evidenced, each finding tied to a concrete moment
- why `re-frame2-pair` was not enough, and the smallest credible change at the correct owner (pair tool vs upstream framework)
- when asked — one focused, copy-pasteable GitHub issue draft the user can file, edit, combine, or discard

## Install

`re-frame2-pair-retro` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` (`@day8/re-frame2-pair-retro`) and `.claude-plugin/plugin.json` packaging metadata for eventual Agent-Skill (`npx skills add`) and Claude-Code-Plugin distribution, but it is not published to npm or any plugin registry yet — the current install path is to link the skill from a full monorepo clone. Prefer linking over copying in any case: a `cp -r` copy snapshots the skill and silently drifts from the maintained source as the repo moves.

Claude Code loads skills from `~/.claude/skills/<name>/`. The cross-platform installer at the repo root links every re-frame2 skill into place — see [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour. To link just this one skill:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-pair-retro ~/.claude/skills/re-frame2-pair-retro   # macOS / Linux
```

On Windows, use a junction instead of `ln -s` (no admin required):

```powershell
New-Item -ItemType Junction -Path "$HOME\.claude\skills\re-frame2-pair-retro" -Target "$HOME\src\re-frame2\skills\re-frame2-pair-retro"
```

Then `git pull` in the cloned repo to pick up updates everywhere at once.
