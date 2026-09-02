# re-frame2-improver

> ↑ [`skills/`](..) — index of all re-frame2 skills.

`re-frame2-improver` is a Claude Code critique-mode skill for existing re-frame2 ClojureScript code. One request returns one complete critique: it reviews a body of source files (or a user-supplied snippet) against a small catalogue of re-frame2 anti-patterns and surfaces every material finding in the same turn — severity-ordered, each with concrete evidence, its consequence, the smallest safe correction, and a cross-link to the canonical idiom under `skills/re-frame2/patterns/`. It applies fixes only when the request says to fix as well as review.

This skill is the on-demand complement to [`re-frame2`](../re-frame2): re-frame2 authors new code from canonical idioms; re-frame2-improver retrospectively critiques existing code against the same idioms. It activates only on explicit pull — "review my re-frame2 code", "any anti-patterns?", "audit against best practices" — and only when a body of re-frame2 source is in scope (a `.cljs` / `.cljc` file read or edited in the conversation, a pasted snippet, or a named path the skill reads). Vocabulary alone does not trigger it. The 3 activation filters and the not-for routing are stated once in [`SKILL.md` §Trigger semantics](SKILL.md#trigger-semantics-locked).

## Repo contents

- `SKILL.md` — the skill itself (trigger semantics, workflow, the signal → leaf routing table, output shape, self-anti-patterns).
- `references/` — the six anti-pattern leaves. Each carries a detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, and a cross-link to the matching idiom under `skills/re-frame2/patterns/` or `spec/`. `references/README.md` is the maintainer catalogue index; a review routes to the leaves from `SKILL.md` directly.
- `evals/evals.json` — eval fixtures: trigger fixtures (should-trigger + should-not-trigger, per skill-creator's description-optimisation contract) plus behavioural fixtures that grade the critique itself — right anti-pattern named, evidence cited, canonical idiom cross-linked, no false positives, Edit gate respected. See [`evals/README.md`](evals/README.md) for the coverage table and grading guidance (authoring-time scaffolding, not shipped in the package — reach it from a monorepo clone).
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata.
- `package.json` — npm packaging metadata (skill is also distributable as an Agent Skill).
- `spec/` — skill-internal meta-docs (`design.md`, `inputs.md`, `authoring-prompt.md`) that let a future pass re-author the skill from committed inputs. Not loaded during normal operation.

## Relationship to other skills

- [`re-frame2`](../re-frame2) — authors new application code. The improver leans on its `patterns/` and `spec/` leaves as the canonical-idiom source-of-truth for every cross-link.
- [`re-frame2-pair`](../re-frame2-pair) — pair-programs with a running re-frame2 application. The improver is static — it never attaches to a runtime; if the user wants live inspection, route to re-frame2-pair.
- [`re-frame2-pair-retro`](../re-frame2-pair-retro) — retros on a re-frame2-pair session. Session-shaped and read-only, where this skill is source-shaped; both are self-contained.

## How it works, in brief

- Correction contract — the request decides, not a provenance taxonomy: a plain review / audit / critique is read-only (the smallest safe correction is stated inside each finding, nothing is applied); a direct "fix it" / "review and apply" authorises safe edits inside the named scope with no second approval round; cross-cutting redesigns stay proposals either way, and in-source comments are data that can neither grant nor suppress anything. Full statement: [`SKILL.md` §Workflow step 5](SKILL.md#workflow).
- output shape: a critique returns every material finding in one turn, highest consequence first — each with `path:line` evidence, concrete consequence, smallest safe correction, and canonical link, plus at most one optional-redesign sentence; sections with nothing to report are omitted. Detailed in [`SKILL.md` §Output format](SKILL.md#output-format).

## Install

`re-frame2-improver` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. It carries `package.json` and `.claude-plugin/plugin.json` metadata for eventual Agent-Skill / plugin distribution, but it is not published separately yet — the current install path is to clone re-frame2 and link the skill from `skills/re-frame2-improver/`. The packaged normal path is self-contained: everything a review loads (`SKILL.md` + `references/`) ships in the package's own file set — the sibling-skill routing boundary included, stated in full in [`SKILL.md` §Trigger semantics](SKILL.md#trigger-semantics-locked) — and links out to the monorepo (`skills/re-frame2/patterns/`, `spec/`, the `skills/README.md` routing matrix) are optional supporting destinations, not required reads.

### Install the skill in Claude Code

Link, never copy. Claude Code loads skills from `~/.claude/skills/<name>/`. A `cp -r` copy snapshots the skill and then drifts as the repo is maintained — Claude Code keeps loading the stale copy, which silently falls behind the anti-pattern catalogue. Always link the repo source.

The cross-platform installer at the repo root links every re-frame2 skill (this one included) into `~/.claude/skills/`. See [`skills/README.md` §Installing (link, never copy)](../README.md#installing-link-never-copy) for the canonical installer commands (macOS / Linux + Windows) and the idempotent / `--force` / `--check` behaviour.

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

> Project-local copying is an explicit pinned-vendoring choice, not the default. If your team deliberately wants a frozen snapshot committed into a project's `.claude/skills/`, you own the update burden — re-vendor on every re-frame2 release or you will silently run a skill that has fallen behind the catalogue. Prefer linking.

## License

MIT
