# re-frame2-implementor

> ↑ [`skills/`](../) — index of all six re-frame2 skills.

A `Skill` that helps `Claude Code` (or any Anthropic-skill-compatible agent) guide an engineer **building a new re-frame2 implementation** — a port to a different host language or substrate, not an application built on the existing CLJS reference.

This is the **implementor's companion** to the three application-side skills in this repo:

- [`re-frame2`](../re-frame2/) — for writing application code on the CLJS reference.
- [`re-frame2-setup`](../re-frame2-setup/) — for bootstrapping a fresh greenfield project on the CLJS reference.
- [`re-frame-migration`](../re-frame-migration/) — for porting an existing re-frame v1 codebase to the CLJS reference.

Where the three application-side skills are about **using** re-frame2, this skill is about **realising** it.

## What it covers

A two-phase workflow:

1. **Phase 1 — Lock the decisions.** Target host language; substrate / view layer; scope (which EPs ship); identity primitive, persistent data structures, reactive substrate, concurrency model, hot-reload, schema mechanism; conformance capability tag set. The engineer produces a single locked-decision record before any code is written.
2. **Phase 2 — Walk the spec corpus.** Implement in dependency order: EP 001 Registration → 002 Frames (events + effects + subs) → 006 Reactive substrate → 004 Views → 009 Instrumentation → optional EPs per Phase 1 scope. The conformance corpus at `spec/conformance/` is the acceptance test.

## What it deliberately does NOT cover

- **Writing applications** on the existing CLJS reference. That's the main [`re-frame2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2) skill.
- **Designing a new pattern.** This skill realises the existing re-frame2 pattern as specified in `spec/`. Engineers proposing a different pattern need a different conversation.
- **Running tests for the engineer.** Compilation, test runs, conformance harness execution are general software practice — they're the engineer's responsibility, not the skill's.

## How the skill works

The skill is structured around the **spec corpus** at [`spec/`](https://github.com/day8/re-frame2/tree/main/spec) in this repo, and especially around [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) (the decision-ordered companion). The skill:

- Routes the workflow (two phases, ordered).
- Surfaces the Phase 1 decisions with options per host and trade-offs.
- Walks the EP corpus in dependency order during Phase 2.
- Frames the conformance corpus as the acceptance test for "this is a re-frame2 implementation".

It does **not** duplicate spec content. Each reference in the skill cites the spec section or chapter; the full text is read directly from `spec/`.

## Status

Pre-alpha. The skill is authored; it has not yet been exercised end-to-end against a real port. The structure mirrors the other skills in this repo. The content is grounded against `spec/`, `spec/Implementor-Checklist.md`, and `spec/conformance/README.md`.

## Layout

```
skills/re-frame2-implementor/
├── SKILL.md                       # Router (~250 lines)
├── README.md                      # This file
├── LICENSE                        # MIT
├── package.json                   # npm metadata for distribution
├── .claude-plugin/
│   └── plugin.json                # Claude Code plugin metadata
├── references/
│   ├── kickoff-prompt.md          # Paste-ready prompt for a fresh session
│   ├── phase-1-decisions.md       # The Phase 1 walkthrough
│   ├── decision-record.md         # Fill-in template for the locked decisions
│   ├── phase-2-impl-order.md      # EP-by-EP implementation order for Phase 2
│   ├── reference-impl-tour.md     # Guided tour of the CLJS reference impl
│   ├── conformance.md             # How to consume the conformance corpus
│   └── output-format.md           # Standard agent-output shape
└── spec/
    ├── design.md                  # Locked design decisions
    ├── inputs.md                  # Canonical inputs the skill leans on
    └── authoring-prompt.md        # One-shot reauthor prompt
```

## Source of truth

[`spec/`](https://github.com/day8/re-frame2/tree/main/spec) at the repo root, with [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) as the decision-ordered companion and [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) as the acceptance test. If the skill and the spec disagree, the spec wins.

## Licence

MIT. See [`LICENSE`](LICENSE).
