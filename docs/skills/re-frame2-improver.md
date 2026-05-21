# re-frame2-improver

> Critique-mode for **existing** re-frame2 ClojureScript code. Reviews a body of source files (or a supplied snippet) against a catalogue of re-frame2 anti-patterns, surfaces concrete findings cross-linked to canonical idioms, and may propose inline fixes. Explicit-pull-only.

## What it does

The `re-frame2-improver` skill is a focused code reviewer for **already-written** re-frame2 code. It reads a body of source files (or a user-supplied snippet), detects anti-patterns from a small catalogue, surfaces findings with concrete file/line evidence, cross-links each one to the canonical idiom under `skills/re-frame2/patterns/`, and — subject to an Edit-gate — may propose or apply an inline fix via `Edit`.

It is **explicit-pull-only**: the user asks for a review, the skill activates, and it exits once findings have been presented (and optional fixes applied). Vocabulary alone ("review", "audit", "any improvements?") is not enough — a body of re-frame2 source must be in scope.

## When to reach for it

Load this skill on an explicit review request — "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns" — **and** a body of re-frame2 source is in scope (read, edited, or supplied as a snippet).

Do **not** use this skill for:

- Writing new application code from scratch → use [re-frame2](re-frame2.md).
- Operating on a live runtime → use [re-frame2-pair](re-frame2-pair.md).
- Retrospecting on a pair session → use [re-frame2-pair-retro](re-frame2-pair-retro.md).
- Greenfield bootstrap or v1 migration → use [re-frame2-setup](re-frame2-setup.md) or [re-frame-migration](re-frame-migration.md).

## Kickoff

The skill activates on explicit pull. To force-load:

```
/skill re-frame2-improver
```

If no source files have been read, edited, or supplied as snippets, the skill declines and asks for a snippet rather than fabricate evidence.

## Where the skill lives

- Source: [`skills/re-frame2-improver/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver)
- `SKILL.md`: [`skills/re-frame2-improver/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-improver/SKILL.md)
- Canonical idioms it cross-links to: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns).
- Authoring companion skill: [`re-frame2`](re-frame2.md).
