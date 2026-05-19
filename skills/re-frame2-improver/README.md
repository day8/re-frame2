# re-frame2-improver

> ↑ [`skills/`](../) — index of all re-frame2 skills.

`re-frame2-improver` is a Claude Code **critique-mode skill** for **existing** re-frame2 ClojureScript code. It reviews a body of source files (or a user-supplied snippet) against a small catalogue of re-frame2 anti-patterns, surfaces concrete findings cross-linked to canonical idioms under `skills/re-frame2/patterns/`, and — subject to the Edit-gate split — may propose or apply inline fixes.

This skill is the on-demand **complement** to [`re-frame2`](../re-frame2/): re-frame2 authors new code from canonical idioms; re-frame2-improver retrospectively critiques existing code against the same idioms. Activates only on explicit pull — *"review my re-frame2 code"*, *"any anti-patterns?"*, *"audit against best practices"* — and only when a body of re-frame2 source is in scope.

## Three filters must hold to trigger

1. **Explicit pull.** The user used review / audit / critique / improvements / anti-pattern phrasing about their own re-frame2 code.
2. **Source-in-scope.** At least one `.cljs` / `.cljc` file has been read or edited in this conversation, OR the user supplied a snippet inline.
3. **Not a sibling skill's job.** See [`skills/README.md` §Skill routing](../README.md#skill-routing--single-source) for the full disambiguation matrix.

If 1 holds but 2 doesn't, the skill declines and asks for a snippet rather than fabricating findings.

## Repo contents

- `SKILL.md` — the skill itself
- `references/` — the anti-pattern catalogue. Each leaf carries detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching idiom under `skills/re-frame2/patterns/` or `spec/`.
- `evals/evals.json` — trigger-eval fixtures (8 should-trigger + 8 should-not-trigger entries, per skill-creator's description-optimisation contract)
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill)
- `../shared/retro-protocol.md` — shared retro protocol (seven-step diagnosis-first workflow, evidence-citation discipline, layer-routing rules, opt-in bead protocol). Consumed jointly by this skill and `re-frame2-pair-retro`.

## Relationship to other skills

- [`re-frame2`](../re-frame2/) — authors new application code. The improver leans on its `patterns/` and `spec/` leaves as the canonical-idiom source-of-truth for every cross-link.
- [`re-frame2-pair`](../re-frame2-pair/) — pair-programs with a **running** re-frame2 application. The improver is **static** — it never attaches to a runtime; if the user wants live inspection, route to re-frame2-pair.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro/) — retros on a re-frame2-pair session. Shares the `../shared/retro-protocol.md` leaf with this skill (diagnosis-first discipline, untrusted-evidence boundary, Edit-gate split).

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

Pre-alpha. References catalogue currently has 6 leaves at launch (per rf2-bquci); expected to grow as more anti-patterns surface from real-world re-frame2 sessions.

## Install

`re-frame2-improver` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. Clone re-frame2 and reference the skill from `skills/re-frame2-improver/`.

### Install the skill in Claude Code

#### Global — for you, across any repo

Clone the re-frame2 repo somewhere stable, then symlink the skill subdirectory into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git ~/src/re-frame2
mkdir -p ~/.claude/skills
ln -s ~/src/re-frame2/skills/re-frame2-improver ~/.claude/skills/re-frame2-improver
```

#### Project-local — for your whole team via the repo

Copy the skill into the project's own `.claude/skills/re-frame2-improver/` and commit it.

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame2-improver .claude/skills/re-frame2-improver
git add .claude/skills/re-frame2-improver
```

## License

MIT
