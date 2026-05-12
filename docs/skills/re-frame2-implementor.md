# re-frame2-implementor

> Guides an engineer building a new re-frame2 implementation in a different host language or substrate. Two-phase workflow: Phase 1 locks the load-bearing decisions, Phase 2 walks the spec corpus in dependency order with the conformance corpus as the acceptance test.

## What it does

The `re-frame2-implementor` skill is for engineers **building re-frame2 itself**, not building applications with it. It drives a two-phase workflow that takes an engineer from "I want to port re-frame2 to TypeScript / F# / Rust / native UI / a terminal substrate" to "my port passes the claimed-applicable subset of the conformance corpus."

**Phase 1 — Lock the decisions.** Before any code is written: target host language; substrate / view layer; scope (which optional EPs ship); identity primitive, persistent data structures, reactive substrate, concurrency model, hot-reload, schema mechanism, integration story; the conformance capability tag set the port claims. Captured in a written decision record the engineer commits to the port's repo.

**Phase 2 — Walk the spec corpus.** Implement in dependency order: [001 Registration](../../spec/001-Registration.md) → [002 Frames + events + effects + subs](../../spec/002-Frames.md) → [006 Reactive substrate](../../spec/006-ReactiveSubstrate.md) → [004 Views](../../spec/004-Views.md) → [009 Instrumentation](../../spec/009-Instrumentation.md). Acceptance gate 1 runs the `:core/*` conformance fixtures. Then optional EPs per the Phase 1 claim. Acceptance gate 2 runs the full claimed-capability fixture set.

The authoritative contract is the [spec corpus](../../spec/000-Vision.md), with the [Implementor Checklist](../../spec/Implementor-Checklist.md) as the decision-ordered companion and the [conformance corpus](../../spec/conformance/README.md) as the acceptance test. The CLJS reference at `implementation/` is one worked example, never normative — the skill is explicit about this throughout.

## When to reach for it

Load this skill when **any** of these are true:

- The engineer is starting a port of re-frame2 to a new host language (TypeScript, Fable F#, Kotlin/JS, Squint, Scala.js, PureScript, Reason / ReScript / Melange, or one of the broader hosts in the Implementor Checklist).
- The engineer is implementing re-frame2 against a non-React substrate, a native UI toolkit, a terminal UI, or any rendering surface that isn't React-on-the-browser.
- The engineer wants to claim "this is a re-frame2 implementation" and needs to know what the claim requires.
- The engineer is consuming the [Implementor Checklist](../../spec/Implementor-Checklist.md) and the [conformance corpus](../../spec/conformance/README.md) to verify their work.

Do **not** use this skill for:

- Writing application code on the CLJS reference → use [re-frame2](re-frame2.md).
- Bootstrapping a greenfield app on the CLJS reference → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 codebase → use [re-frame-migration](re-frame-migration.md).
- Inspecting / debugging a running v2 app → use [re-frame-pair2](re-frame-pair2.md).

## Kickoff

A paste-ready kickoff prompt ships with the skill at [`skills/re-frame2-implementor/reference/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/reference/kickoff-prompt.md). The engineer opens a fresh Claude Code session in the root of the port's repo and pastes it verbatim. The session loads the skill on its own and walks Phase 1 first, then Phase 2 EP-by-EP, surfacing decisions back to the engineer at every block.

Excerpted shape (full text in the kickoff file):

> *I'm implementing a new port of re-frame2 in this repo. Walk the implementation workflow end-to-end per the `re-frame2-implementor` skill. The spec corpus is at `<path-to-re-frame2>/spec/`. Phase 1 — walk me through the decision blocks, capture each choice in `DECISIONS.md`. Phase 2 — walk the EP corpus in dependency order (001 → 002 → 006 → 004 → 009 → optional). The spec is the contract; the CLJS reference is one worked example, not normative. No core.async. JVM-runnability for the test surface. Spec gaps file `bd create` beads; never silent extrapolation. Begin with Phase 1.*

Two common amendments — *"minimum viable port"* (declare every optional capability `no` and ship the core only) and *"reference impl tour first"* (read the CLJS tour before locking decisions, treating the tour as descriptive).

## Where the skill lives

- Source: [`skills/re-frame2-implementor/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor)
- `SKILL.md`: [`skills/re-frame2-implementor/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md)
- Kickoff prompt: [`skills/re-frame2-implementor/reference/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/reference/kickoff-prompt.md)
- Reference leaves: [`skills/re-frame2-implementor/reference/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor/reference) — `phase-1-decisions.md` (the Phase 1 walkthrough), `decision-record.md` (the fill-in template), `phase-2-impl-order.md` (EP-by-EP order), `reference-impl-tour.md` (descriptive tour of the CLJS reference), `conformance.md` (harness shape + diagnosis), `output-format.md` (agent-output shapes).
- Authoritative contract: the [spec corpus](../../spec/000-Vision.md) + [Implementor Checklist](../../spec/Implementor-Checklist.md) + [conformance corpus](../../spec/conformance/README.md).
- One worked example: the CLJS reference at [`implementation/`](https://github.com/day8/re-frame2/tree/main/implementation) (descriptive, not normative).
