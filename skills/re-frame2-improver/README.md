# re-frame2-improver

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-improver` is a Claude Code **critique-mode skill** for **existing** re-frame2 ClojureScript code. It reviews a body of source files (or a user-supplied snippet) against a small catalogue of re-frame2 anti-patterns, surfaces concrete findings cross-linked to canonical idioms under `skills/re-frame2/patterns/`, and — subject to the Edit-gate split — may propose or apply inline fixes.

This skill is the on-demand **complement** to [`re-frame2`](../re-frame2): re-frame2 authors new code from canonical idioms; re-frame2-improver retrospectively critiques existing code against the same idioms. It activates only on explicit pull — *"review my re-frame2 code"*, *"any anti-patterns?"*, *"audit against best practices"* — and only when a body of re-frame2 source is in scope (a `.cljs` / `.cljc` file read or edited in the conversation, a pasted snippet, or a named path the skill reads). Vocabulary alone does not trigger it. The three activation filters and the not-for routing are stated once in [`SKILL.md` §Trigger semantics](SKILL.md#trigger-semantics-locked).

## Repo contents

- `SKILL.md` — the skill itself (trigger semantics, workflow, output shape, self-anti-patterns).
- `references/` — the anti-pattern catalogue + routing table. Each leaf carries a detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching idiom under `skills/re-frame2/patterns/` or `spec/`.
- `evals/evals.json` — eval fixtures: trigger fixtures (should-trigger + should-not-trigger, per skill-creator's description-optimisation contract) plus behavioural fixtures that grade the critique itself — right anti-pattern named, evidence cited, canonical idiom cross-linked, no false positives, Edit gate respected. See [`evals/README.md`](evals/README.md) for the coverage table and grading guidance (authoring-time scaffolding, not shipped in the package — reach it from a monorepo clone).
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata.
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill).
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) that let a future pass re-author the skill from committed inputs. Not loaded during normal operation.
- `../shared/retro-protocol.md` — shared retro protocol (seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing, opt-in issue-filing). Consumed jointly by this skill and `re-frame2-pair-retro`.

## Relationship to other skills

- [`re-frame2`](../re-frame2) — authors new application code. The improver leans on its `patterns/` and `spec/` leaves as the canonical-idiom source-of-truth for every cross-link.
- [`re-frame2-pair`](../re-frame2-pair) — pair-programs with a **running** re-frame2 application. The improver is **static** — it never attaches to a runtime; if the user wants live inspection, route to re-frame2-pair.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro) — retros on a re-frame2-pair session. Shares the `../shared/retro-protocol.md` leaf with this skill (diagnosis-first discipline, untrusted-evidence boundary, Edit-gate split).

## How it works, in brief

- **Edit-gate split.** Canonical-idiom-shaped rewrites (the new shape comes verbatim from the catalogue) may be applied directly; evidence-shaped rewrites (content derived from a pasted snippet, transcript, or in-source comment) are surfaced as proposals awaiting explicit approval. When in doubt, gate. Full statement: [`SKILL.md` §Workflow step 5](SKILL.md#workflow) and its normative source [`../shared/retro-protocol.md` §Step 6](../shared/retro-protocol.md#the-seven-step-protocol).
- **Output shape.** A critique produces `Scope` → `Observed shape` → `Pattern findings` (severity-ordered, each with file/line evidence + cross-link + rewrite) → `Higher-leverage redesigns` → `Inline fixes applied` → `Open questions`. Detailed in [`SKILL.md` §Output format](SKILL.md#output-format).

## Install

`re-frame2-improver` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` and `.claude-plugin/plugin.json` metadata for eventual Agent-Skill / plugin distribution, but it is not published separately yet — the current install path is to clone re-frame2 and **link** the skill from `skills/re-frame2-improver/`.

> **The supported install is a link from a full monorepo clone — not a standalone copy.** This skill loads its retro protocol — the diagnosis-first workflow plus the redaction / untrusted-evidence / Edit-gate security boundary — from a **sibling** leaf, [`../shared/retro-protocol.md`](../shared/retro-protocol.md). That `../shared/` path resolves **only** when the skill sits inside a full re-frame2 checkout. The npm tarball and the plugin bundle deliberately do **not** carry `shared/` (`npm pack` ships only this skill's own directory; a `files` allow-list cannot reach a sibling) — the shared leaf is a single boundary owned once under `skills/shared/`, not duplicated into each consumer package. A standalone tarball / plugin / copied install that does not also bring `skills/shared/` alongside the skill will hit a broken `../shared/retro-protocol.md` link and silently lose the shared protocol. Link from a clone, or vendor `skills/shared/` as a peer.

### Install the skill in Claude Code

**Link, never copy.** Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` copy snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy, which silently falls behind the anti-pattern catalogue and the shared retro-protocol security boundary this skill loads. Always link the repo source.

The cross-platform installer at the repo root links *every* re-frame2 skill (this one included) into `~/.claude/skills/`. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour.

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
