# re-frame2-improver

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-improver` is a Claude Code **critique-mode skill** for **existing** re-frame2 ClojureScript code. It reviews a body of source files (or a user-supplied snippet) against a small catalogue of re-frame2 anti-patterns, surfaces concrete findings cross-linked to canonical idioms under `skills/re-frame2/patterns/`, and — subject to the Edit-gate split — may propose or apply inline fixes.

This skill is the on-demand **complement** to [`re-frame2`](../re-frame2): re-frame2 authors new code from canonical idioms; re-frame2-improver retrospectively critiques existing code against the same idioms. Activates only on explicit pull — *"review my re-frame2 code"*, *"any anti-patterns?"*, *"audit against best practices"* — and only when a body of re-frame2 source is in scope.

## Three filters must hold to trigger

1. **Explicit pull.** The user used review / audit / critique / improvements / anti-pattern phrasing about their own re-frame2 code.
2. **Source-in-scope.** At least one `.cljs` / `.cljc` file has been read or edited in this conversation, OR the user supplied a snippet inline, OR the user named a concrete `.cljs` / `.cljc` file or directory to review (the skill reads it before critiquing).
3. **Not a sibling skill's job.** See [`skills/README.md` §Skill routing](../README.md#skill-routing--single-source) for the full disambiguation matrix.

If 1 holds but 2 doesn't — vocabulary with no file, snippet, or named path — the skill declines and asks for a snippet or a path rather than fabricating findings.

## Repo contents

- `SKILL.md` — the skill itself
- `references/` — the anti-pattern catalogue. Each leaf carries detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching idiom under `skills/re-frame2/patterns/` or `spec/`.
- `evals/evals.json` — eval fixtures: 26 total — 16 trigger fixtures (8 should-trigger + 8 should-not-trigger, per skill-creator's description-optimisation contract) plus 10 behavioural fixtures that grade the critique itself — right anti-pattern named, evidence cited, canonical idiom cross-linked, no false positives, Edit gate respected. See [`evals/README.md`](evals/README.md) for the coverage table and grading guidance.
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) that let a future pass re-author the skill from committed inputs. Not loaded during normal operation.
- `../shared/retro-protocol.md` — shared retro protocol (seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in issue-filing protocol). Consumed jointly by this skill and `re-frame2-pair-retro`.

## Relationship to other skills

- [`re-frame2`](../re-frame2) — authors new application code. The improver leans on its `patterns/` and `spec/` leaves as the canonical-idiom source-of-truth for every cross-link.
- [`re-frame2-pair`](../re-frame2-pair) — pair-programs with a **running** re-frame2 application. The improver is **static** — it never attaches to a runtime; if the user wants live inspection, route to re-frame2-pair.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro) — retros on a re-frame2-pair session. Shares the `../shared/retro-protocol.md` leaf with this skill (diagnosis-first discipline, untrusted-evidence boundary, Edit-gate split).

## Edit-gate split

The improver applies `Edit` under a two-tier gate (the normative statement is `../shared/retro-protocol.md` §Step 6):

- **Canonical-idiom-shaped Edit — unrestricted.** When the rewrite is identical to a pattern documented under `skills/re-frame2/patterns/` or `spec/` — the evidence's only role was to identify *where* the anti-pattern occurs, and the new shape comes verbatim from the catalogue — the agent may apply `Edit` when confident.
- **Evidence-shaped Edit — explicit approval first.** When the rewrite's content or motivation is derived from user-supplied evidence (a pasted snippet, transcript, stack trace, recap, or in-source comment), surface the proposed `Edit` as a finding with old/new shape and wait for "go".
- **When in doubt, gate.** If the rewrite quotes the evidence (variable names, strings, structure) more closely than it quotes the canonical idiom, treat it as evidence-shaped.

Higher-leverage redesigns always stay as suggestions.

## Typical output

A good critique produces:

1. `Scope` — the files / namespaces under review
2. `Observed shape` — short structural read of the code
3. `Pattern findings` — numbered list with concrete file/line evidence + canonical idiom cross-link + suggested rewrite
4. `Higher-leverage redesigns` — for credible reshape options worth separating from grounded fixes
5. `Inline fixes applied` — list of `Edit` operations performed (when applicable)
6. `Open questions` — ambiguities needing author input

## Status

Pre-alpha. The references catalogue has 6 leaves; it grows as more anti-patterns surface from real-world re-frame2 sessions.

## Install

`re-frame2-improver` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` and `.claude-plugin/plugin.json` metadata for eventual Agent-Skill / plugin distribution, but it is not published separately yet — the current install path is to clone re-frame2 and **link** the skill from `skills/re-frame2-improver/`.

> **The supported install is a link from a full monorepo clone — not a standalone copy.** This skill loads its retro protocol — the diagnosis-first workflow plus the redaction / untrusted-evidence / Edit-gate security boundary — from a **sibling** leaf, [`../shared/retro-protocol.md`](../shared/retro-protocol.md). That `../shared/` path resolves **only** when the skill sits inside a full re-frame2 checkout. The npm tarball and the plugin bundle deliberately do **not** carry `shared/` (`npm pack` ships only this skill's own directory; a `files` allow-list cannot reach a sibling) — the shared leaf is a single boundary owned once under `skills/shared/`, not duplicated into each consumer package. A standalone tarball / plugin / copied install that does not also bring `skills/shared/` alongside the skill will hit a broken `../shared/retro-protocol.md` link and silently lose the shared protocol. Link from a clone, or vendor `skills/shared/` as a peer.

### Install the skill in Claude Code

**Link, never copy.** Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` copy snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy, which silently falls behind the anti-pattern catalogue and the shared retro-protocol security boundary this skill loads. Always link the repo source.

The cross-platform installer at the repo root links *every* re-frame2 skill (this one included) into `~/.claude/skills/`. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour, and [`CONTRIBUTING.md`](../../CONTRIBUTING.md#skills--link-dont-copy) for the full setup.

To link just this one skill:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-improver ~/.claude/skills/re-frame2-improver   # macOS / Linux
```

On Windows, use a junction instead of `ln -s` (no admin required):

```powershell
New-Item -ItemType Junction -Path "$HOME\.claude\skills\re-frame2-improver" -Target "$HOME\src\re-frame2\skills\re-frame2-improver"
```

> **Project-local copying is an explicit pinned-vendoring choice, not the default.** If your team deliberately wants a frozen snapshot committed into a project's `.claude/skills/`, you own the update burden — re-vendor on every re-frame2 release or you will silently run a skill that has fallen behind the catalogue and shared protocol. **Vendoring must also copy `skills/shared/` as a peer** so `../shared/retro-protocol.md` still resolves — vendoring this skill's directory alone drops the diagnosis / redaction / Edit-gate boundary and leaves the `../shared/` link broken. Prefer linking.

## License

MIT
