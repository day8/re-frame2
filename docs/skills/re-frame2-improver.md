# re-frame2-improver

> Critique-mode for **existing** re-frame2 ClojureScript code. Reviews a body of source files (or a supplied snippet) against a catalogue of re-frame2 anti-patterns, surfaces concrete findings cross-linked to canonical idioms, and may propose inline fixes. Explicit-pull-only.

## What it does

The `re-frame2-improver` skill is a focused code reviewer for **already-written** re-frame2 code. It reads a body of source files (or a user-supplied snippet), detects anti-patterns from a small catalogue, and returns one complete, severity-ordered critique in the same turn — each finding with concrete file/line evidence, its consequence, the smallest safe correction, and a cross-link to the canonical idiom under `skills/re-frame2/patterns/`.

Whether it edits depends on what you asked for. *"Review this"* is read-only: each finding states its smallest safe correction and nothing is applied. *"Review and fix"* authorises those corrections inside the named scope, with no second approval round. A redesign that reaches beyond the scope stays a proposal either way, and an instruction written into the source under review (a comment addressed to the agent) is treated as data, never obeyed. The reply carries only the sections that have content — the scope reviewed, the findings, the fixes applied, open questions — and a clean result is one short verdict naming what was reviewed. A finding that is really a gap in re-frame2 itself is described for you to file against [`day8/re-frame2`](https://github.com/day8/re-frame2/issues); the skill does not rewrite your code around it and has no way to file.

The catalogue is the set of leaves under [`references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver/references); [`SKILL.md` §Routing](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-improver/SKILL.md#routing--load-only-the-leaves-whose-signals-appear) lists each one with the source signals that make the skill load it.

It is **explicit-pull-only**: the user asks for a review, the skill activates, delivers the complete critique in that turn, and exits. Vocabulary alone ("review", "audit", "any improvements?") is not enough — a body of re-frame2 source must be in scope.

## When to reach for it

Load this skill on an explicit review request — "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns in `cart/handlers.cljs`" — **and** a body of re-frame2 source is in scope: read or edited in the conversation, supplied as a snippet, or named as a concrete, resolvable `.cljs` / `.cljc` file or directory path the skill can read (it reads the named path before critiquing). A path that does not resolve does not establish scope.

Do **not** use this skill for:

- Writing new application code from scratch → use [re-frame2](re-frame2.md).
- Operating on a live runtime → use [re-frame2-pair](re-frame2-pair.md).
- Retrospecting on a pair session → use [re-frame2-pair-retro](re-frame2-pair-retro.md).
- Greenfield bootstrap or v1 migration → use [re-frame2-setup](re-frame2-setup.md) or [re-frame-migration](re-frame-migration.md).
- *Porting* Reagent views to Fresco → use [reagent-migration](reagent-migration.md). Critiquing existing Reagent-view code against the catalogue stays here.

## Kickoff

The skill activates on explicit pull — ask for a review with the code in scope, or type `/re-frame2-improver`.

If no source files have been read, edited, supplied as snippets, or named as a resolvable `.cljs` / `.cljc` path, the skill declines and asks for a snippet rather than fabricate evidence.

## Where the skill lives

- Source: [`skills/re-frame2-improver/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver)
- `SKILL.md`: [`skills/re-frame2-improver/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-improver/SKILL.md)
- Canonical idioms it cross-links to: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns).
- Authoring companion skill: [`re-frame2`](re-frame2.md).
