# re-frame2-pair-retro

> ↑ [`skills/`](..) — index of all eight re-frame2 skills.

`re-frame2-pair-retro` is a Claude ***meta-skill*** for [`re-frame2-pair`](../re-frame2-pair). It reviews a user's `re-frame2-pair` session, identifies friction and wasted effort, and suggests how `re-frame2-pair` itself could be improved to become a better pair programmer.

It is the re-frame2 sibling of [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver), which targets the v1 [`re-frame-pair`](https://github.com/day8/re-frame-pair) tool against [`re-frame`](https://github.com/day8/re-frame) v1.

It is designed for retrospectives like:

- "What was frustrating about this re-frame2-pair session?"
- "What took longer than it should have?"
- "Which parts of this workflow should re-frame2-pair absorb?"
- "Can you draft or file a GitHub issue for the best improvement idea?"

It focuses on evidence from the session itself: retries, confusion, workarounds, stale or empty results, missing observability, brittle platform behavior, hidden prerequisites, and trust gaps. It then proposes improvements at the right layer: `SKILL.md`, scripts/runtime ops, warnings/results, tests/fixtures, or — when the friction is caused by the framework rather than the pair tool — an upstream GitHub issue against `day8/re-frame2`.

It can draft a GitHub issue against `day8/re-frame2` — the monorepo where the pair tool ships, alongside the framework — but only if the user wants that. Tool-side vs upstream-framework friction is carried in the issue title and body; when the target repo defines them, an optional `pair-mcp` / `upstream-from-re-frame2-pair` label reinforces the distinction. Labels are best-effort taxonomy, never a precondition: filing falls back to a no-label `gh issue create` so the handoff lands even on a repo that has not defined those labels.

It is intentionally diagnosis-first: the default outcome is a better understanding of what went wrong and which improvements would matter most, not pressure to contribute code or file issues.

## Directory contents

- `SKILL.md` — the skill itself
- `README.md` — this human-facing intro
- `references/analysis-lenses.md` — friction taxonomy and prioritization prompts (re-frame2-aware)
- `references/known-frictions.md` — recurring classes of product friction to pattern-match against
- `references/issue-template.md` — GitHub-issue drafting structure (with the shell-safety pattern for transcript-derived bodies)
- `references/working-style.md` — diagnostic-posture rules applied per finding (evidence over vibes, symptom vs cause, direct/indirect friction, positive gaps, creativity after diagnosis)
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) for re-authoring the skill; not loaded during normal operation, and — like `evals/` — **excluded from the npm `files` array by design** (repo-maintenance artifacts that run from a full clone, not material a packaged consumer re-runs; `npm pack --dry-run` lists no `spec/` or `evals/` files, mirroring the sibling `re-frame2` / `re-frame2-setup` / `re-frame2-pair` skills)
- `evals/evals.json` — trigger-accuracy fixtures (which prompts should and should not activate the skill); a repo-maintenance artifact, also excluded from the npm package `files` array
- `.claude-plugin/plugin.json` — plugin packaging metadata
- `agents/openai.yaml` — UI metadata for skill lists and invocation
- `package.json`, `LICENSE` — npm packaging metadata and the MIT licence

## Relationship to other repos

- [`re-frame2-pair`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair) — the pair tool this skill reviews. Sessions with that tool are this skill's input.
- [`re-frame2`](https://github.com/day8/re-frame2) — the framework. When friction is caused by the framework's Tool-Pair contract (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coordinate annotation gaps, schema-reflection shortcomings), GitHub issues route here, not to `re-frame2-pair`.
- [`re-frame-pair-improver`](https://github.com/day8/re-frame-pair-improver) — the v1 sibling that targets v1 `re-frame-pair`.

This skill does **not** depend on or reference `re-frame-10x`. re-frame2's Tool-Pair surfaces replace the v1 reliance on the 10x dev tool — the current surface-family enumeration (trace stream, registrar query API, epoch-history / restore, the four state-injection mutators, schema reflection, source-coord annotation, direct reads, render-driving / dispatch-settle, view-plane reads, the signal recorder, the operating-frame trio) lives in [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md), the single source an upstream finding names a surface from.

## Typical output

A good run of the skill produces:

1. the user's actual goal
2. the main friction points observed in the session
3. likely root causes
4. 2-5 high-leverage improvement ideas
5. optional GitHub-issue candidates (against `day8/re-frame2`; the `pair-mcp` label for tool-side friction is applied only when the repo defines it), with draft text or direct filing only after approval

## Install

`re-frame2-pair-retro` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` (`@day8/re-frame2-pair-retro`) and `.claude-plugin/plugin.json` packaging metadata for eventual Agent-Skill (`npx skills add`) and Claude-Code-Plugin distribution, but it is **not published to npm or any plugin registry yet** — the current install path is to link the skill from a full monorepo clone.

> **Link from a clone; do not copy, and do not depend on a standalone tarball.** Two reasons. (1) A `cp -r` copy silently drifts from the maintained source, and for this skill that drift is a *security* concern — the shared redaction / untrusted-evidence / shell-safe issue-filing boundary it loads is an active AI/off-box surface, so a stale copy can miss later hardening while still filing GitHub issues from sensitive pair-session recaps. (2) The skill loads three normal-operation leaves from a **sibling** directory — [`../shared/retro-protocol.md`](../shared/retro-protocol.md), [`../shared/issue-filing.md`](../shared/issue-filing.md), and [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) — whose `../shared/` paths resolve only inside a full re-frame2 checkout. The npm tarball / plugin bundle deliberately do not carry `skills/shared/` (it is a single boundary owned once, not duplicated per consumer), so any install that does not bring `skills/shared/` alongside the skill hits broken links and silently loses the protocol. If you deliberately vendor a pinned snapshot, you own the update burden and **must copy `skills/shared/` as a peer**.

Claude Code loads skills from `~/.claude/skills/<name>/`. The cross-platform installer at the repo root links *every* re-frame2 skill into place — see [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour. To link just this one skill:

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
