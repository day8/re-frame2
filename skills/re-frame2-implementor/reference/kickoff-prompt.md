# kickoff-prompt

The paste-ready prompt for kicking off a re-frame2 implementation walkthrough in a fresh Claude Code session.

## When to use this

The engineer is starting a new port of re-frame2 to a different host language or substrate. They want to delegate the workflow to a focused Claude Code session — keeping their own primary session free — and they're sitting in the root of the port's new repository (or an empty workspace).

Steps:

1. The engineer installs this skill — project-level `.claude/skills/re-frame2-implementor/` or globally at `~/.claude/skills/re-frame2-implementor/`.
2. The engineer clones the re-frame2 repo somewhere reachable so the skill can read `spec/`, `spec/Implementor-Checklist.md`, and `spec/conformance/`. Either alongside their port repo or as a git submodule — whichever fits their workflow.
3. The engineer opens a fresh Claude Code session in the port's repo root.
4. The engineer pastes the prompt below verbatim. The session loads the `re-frame2-implementor` skill on its own (the description triggers it) and walks the two phases.

---

## The prompt (paste this verbatim)

> *I'm implementing a new port of re-frame2 in this repo. Walk the implementation workflow end-to-end per the `re-frame2-implementor` skill in this session.*
>
> *The re-frame2 spec corpus is at `<path-to-re-frame2>/spec/` (clone https://github.com/day8/re-frame2 locally if you don't already have it). Treat `spec/` as the contract and `<path-to-re-frame2>/implementation/` as one worked example — not as a contract.*
>
> *Phase 1 — Lock the decisions. Walk me through `reference/phase-1-decisions.md`. For each decision block, surface the options (citing `spec/Implementor-Checklist.md` Part 2 where it has them), name the trade-offs, and ask me for my choice. Capture every choice in a `DECISIONS.md` at the root of this repo using the template at `reference/decision-record.md`. Do not write any implementation code in Phase 1.*
>
> *Phase 2 — Walk the spec corpus. Once Phase 1 is locked, walk `reference/phase-2-impl-order.md` EP by EP in this order: 001 Registration → 002 Frames → 006 Reactive substrate → 004 Views → 009 Instrumentation → then any optional EPs I declared yes for in Phase 1, in the leaf's suggested order. For each EP: read the spec section first, surface the contract the port must expose, surface what the CLJS reference did (as one worked example, not normative), and write the corresponding code in this repo. After 001 / 002 / 006 / 004 / 009 are in place, run the `:core/*` conformance fixtures as the first acceptance gate — this is per `reference/conformance.md`.*
>
> *Standing rules for this port:*
>
> *- The spec at `<path-to-re-frame2>/spec/` is the contract. The CLJS reference at `<path-to-re-frame2>/implementation/` is one realisation — never normative. When the spec and the reference disagree, the spec wins; file a `bd create` bead against the re-frame2 repo.*
> *- Substrate-agnostic phrasing. Do not assume "hiccup", "Reagent", or "keyword" universally; use "render-tree", "reactive container", "identity primitive" per the host's choice from my Phase 1 record.*
> *- No core.async. Async effects schedule via the host's native primitives (Promise, setTimeout, asyncio, tokio, whatever); cross-frame work is serialised per frame via the run-to-completion drain.*
> *- JVM-runnability for the test surface. `compute-sub` and `machine-transition` analogues must be callable from a non-substrate harness.*
> *- If you hit a spec gap — a missing surface, an inconsistency, an undocumented decision — `bd create` a bead in the re-frame2 repo and reference it in my port's `DECISIONS.md`. Do not silently extrapolate from the CLJS reference.*
> *- I run my own builds and tests; you do not run them for me. Run the conformance harness against my port when I ask, and report the score.*
>
> *Begin with Phase 1.*

---

## Variations

Two common amendments the engineer may add:

**"Minimum viable port."** Append: *"Declare Q1 (state machines) = no, Q2 (routing) = no, Q3 (SSR) = no, Q4 = via-host-types, Q5 (stories) = no, Q6 (Tool-Pair) = no, Q7 (AI-Audit) = no. I'm shipping the core only — events / subs / fx / views. Re-evaluate the optional capabilities after the `:core/*` corpus passes."*

**"Reference impl tour first."** Append: *"Before Phase 1, walk me through `reference/reference-impl-tour.md`. I want to see how the CLJS reference is organised before locking my own decisions — but treat the tour as descriptive, not normative."*

---

## Why a kickoff prompt at all

The skill description triggers on a wide range of "porting re-frame2" phrasings, but the **opening shape of the implementation** is identical regardless of phrasing — Phase 1 then Phase 2. Giving the engineer a paste-ready prompt:

- Locks the workflow shape the first time, so the session doesn't drift mid-implementation.
- Makes the "Phase 1 before Phase 2" rule explicit upfront — the session can't skip ahead to coding.
- Frames the spec as the contract and the reference impl as one example — so the session doesn't mistake the CLJS choices for pattern requirements.
- Names the standing rules (substrate-agnostic phrasing, no core.async, JVM-runnability, bead-files-not-silent-extrapolation) so they're in the session's context from the first turn.

The prompt is short (under 500 words) so the engineer doesn't lose anything by pasting it verbatim, but rigid enough that a fresh session executing it walks the implementation the same way every time.
