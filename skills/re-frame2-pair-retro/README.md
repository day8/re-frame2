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
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) for re-authoring the skill; not loaded during normal operation
- `evals/evals.json` — trigger-accuracy fixtures (which prompts should and should not activate the skill); a repo-maintenance artifact, deliberately **excluded** from the npm package `files` array (see below)
- `.claude-plugin/plugin.json` — plugin packaging metadata
- `agents/openai.yaml` — UI metadata for skill lists and invocation
- `package.json`, `LICENSE` — npm packaging metadata and the MIT licence

`spec/` carries skill-internal design/authoring meta-docs (not loaded during normal operation), and `evals/` holds the trigger-accuracy fixtures. Both are **excluded from the npm `files` array by design** — they are repo-maintenance artifacts that run from a full re-frame2 clone (where the description-optimisation loop can reach `evals/evals.json`), not material a packaged-skill consumer re-runs. This mirrors the sibling `re-frame2`, `re-frame2-setup`, and `re-frame2-pair` skills; `npm pack --dry-run` from this directory lists no `spec/` or `evals/` files.

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

## Status

Pre-alpha. Ports the v1 `re-frame-pair-improver` skill structure to target re-frame2. Expected to evolve as `re-frame2-pair` matures and as more re-frame2 sessions surface novel friction patterns.

## Install

`re-frame2-pair-retro` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` (`@day8/re-frame2-pair-retro`) and `.claude-plugin/plugin.json` packaging metadata for eventual Agent-Skill (`npx skills add`) and Claude-Code-Plugin distribution, but it is **not published to npm or any plugin registry yet** — the current install path is to clone re-frame2 and link the skill from `skills/re-frame2-pair-retro/` (see below).

> **The supported install is a link from a full monorepo clone — not a standalone copy.** This skill loads three normal-operation reference leaves from a **sibling** directory: [`../shared/retro-protocol.md`](../shared/retro-protocol.md) (the diagnosis-first workflow + the redaction / untrusted-evidence security boundary), [`../shared/issue-filing.md`](../shared/issue-filing.md) (the shell-safe `gh issue create` filing recipe), and [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md). Those `../shared/` paths resolve **only** when the skill sits inside a full re-frame2 checkout (linking from a clone preserves the sibling). The npm tarball and the plugin bundle deliberately **do not** carry `shared/` (`npm pack --dry-run` from this directory lists no `shared/` files): the three leaves are a single shared boundary owned once under `skills/shared/`, not duplicated into each consumer package. A standalone tarball / plugin / copied install that does **not** also bring `skills/shared/` alongside the skill will hit broken `../shared/` links and silently lose the redaction, untrusted-evidence, and shell-safe issue-filing protocol — so until Agent-Skill / plugin distribution learns to ship `skills/shared/` as a peer, **link from a clone; do not depend on the tarball or plugin bundle being self-contained.**

### Install the skill in Claude Code

**Link, never copy.** Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` copy snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy. For this skill that drift is a security concern, not just polish: the shared redaction / issue-filing protocol it loads is an active AI/off-box boundary, so a stale copy can miss later redaction, prompt-injection, or shell-safety hardening while still filing GitHub issues from sensitive pair-session recaps. Always link the repo source so the active skill is the maintained source by construction.

The cross-platform installer at the repo root links *every* re-frame2 skill (this one included) into `~/.claude/skills/`. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour, and [`CONTRIBUTING.md`](../../CONTRIBUTING.md#skills--link-dont-copy) for the full setup.

To link just this one skill:

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

> **Project-local copying is an explicit pinned-vendoring choice, not the default.** If your team deliberately wants a frozen snapshot committed into a project's `.claude/skills/`, you own the update burden: re-vendor on every re-frame2 release or you will silently run a skill that has fallen behind the shared security hardening above. **Vendoring must also copy `skills/shared/` as a peer** (so `../shared/retro-protocol.md`, `../shared/issue-filing.md`, and `../shared/tool-pair-surfaces.md` still resolve) — vendoring this skill's directory alone drops the redaction / untrusted-evidence / shell-safe-filing boundary and leaves the `../shared/` links broken. Prefer linking unless you have a specific reason to pin.
