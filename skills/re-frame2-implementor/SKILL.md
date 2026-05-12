---
name: re-frame2-implementor
description: >
  Guides an engineer building a NEW re-frame2 implementation in a different
  host language or substrate — TypeScript, F# (Fable), Kotlin/JS, Squint,
  Scala.js, PureScript, ReScript, Python, Rust, native UI, terminal, or any
  host where re-frame2's pattern can be realised. Drives a two-phase workflow:
  Phase 1 locks the load-bearing decisions (target language, substrate,
  scope, identity primitive, persistent data structures, concurrency model,
  schema mechanism, hot-reload story), and Phase 2 walks the spec corpus in
  dependency order (001 Registration → 002 Frames → 006 Reactive substrate →
  004 Views → optional EPs) with the conformance corpus as the acceptance
  test. Use this skill whenever the user mentions "porting re-frame2",
  "implementing re-frame2 in <language>", "writing a re-frame2 runtime",
  "second re-frame2 implementation", "TypeScript port of re-frame2",
  "implementor checklist", "conformance corpus", or any phrasing about
  building re-frame2 itself rather than using it. For building applications
  ON TOP OF the CLJS reference, use the re-frame2 skill instead; for
  migrating a v1 codebase, use re-frame-migration.
---

# re-frame2-implementor

You are helping an engineer **build a new implementation of the re-frame2 pattern** — not a re-frame2 application, but a runtime that other people will then write applications against. The CLJS reference implementation in this repo is a working example of one realisation; this skill walks the engineer through realising another.

The skill is **guidance + workflow** layered on top of the spec corpus at `spec/`. The spec is the contract. The reference impl is one worked example, not normative.

---

## When to use this skill

Use this skill when **any** of these are true:

- The engineer is starting a port of re-frame2 to a new host language (TypeScript, Fable F#, Kotlin/JS, Squint, Scala.js, PureScript, Reason / ReScript / Melange, or any of the other hosts named in `spec/000-Vision.md` — or a host outside that list).
- The engineer is implementing re-frame2 against a non-React substrate, a native UI toolkit, a terminal UI, or any rendering surface that isn't React-on-the-browser.
- The engineer wants to claim "this is a re-frame2 implementation" and needs to know what the claim requires.
- The engineer is consuming `spec/Implementor-Checklist.md` and the [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) corpus to verify their work.

## When NOT to use this skill

Route elsewhere when:

| Engineer intent | Route to |
|---|---|
| Write event handlers, subs, machines, views in a project using the CLJS reference | `skills/re-frame2/` |
| Bootstrap a fresh greenfield app on the CLJS reference | `skills/re-frame2-setup/` |
| Migrate a re-frame v1 codebase to the CLJS reference | `skills/re-frame-migration/` |
| Inspect / dispatch / debug a running v2 app | `skills/re-frame-pair2/` |
| Understand the pattern's rationale (EP narrative, principles) | [`SKILL-REDIRECT.md`](https://github.com/day8/re-frame2/blob/main/SKILL-REDIRECT.md) at repo root |

If the engineer is building **applications** on the reference, hand off to `re-frame2`. This skill is exclusively for engineers building the runtime itself.

---

## Cardinal rules

These hold across every phase of the implementation.

1. **The spec is the contract.** `spec/` is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; file a `bd create` bead against the spec or the reference impl as appropriate.
2. **Phase 1 before Phase 2.** Decisions made under Phase 1 (host language, substrate, scope, identity primitive, concurrency model) are load-bearing for every line of code written in Phase 2. Lock them in writing before opening an editor. If a Phase 2 step forces a Phase 1 decision to change, *stop*, revise the decision record, and restart that Phase 2 step.
3. **Implement in dependency order.** EP 001 (Registration) → 002 (Frames + events + effects + subs) → 006 (Reactive substrate) → 004 (Views) are the foundation. The optional EPs (005, 007–014) sit downstream and can be deferred or skipped per Phase 1 scope.
4. **Substrate-agnostic phrasing in code and docs.** The reference impl is a CLJS+Reagent realisation — keywords as ids, hiccup as render-tree, Reagent ratoms as the reactive container. These are *choices the reference made*. Your port chooses differently and re-frame2 is still re-frame2. Do not write code that assumes "hiccup" or "Reagent" or "keyword" universally; write code that assumes "the identity primitive", "the render-tree", "the reactive container".
5. **No core.async equivalents.** The CLJS reference does not use core.async, and ports inherit this directive. Async effects schedule via host primitives (Promise / setTimeout / setImmediate / asyncio / tokio); cross-frame work is serialised per frame via the run-to-completion drain. If your host's idiomatic concurrency model is channel-shaped, you may use channels *internally* — but the public dispatch contract is still the run-to-completion drain.
6. **JVM-runnability for the testing surface.** Where the CLJS reference makes `compute-sub` and `machine-transition` pure-function-callable from JVM, your port's analogue must be callable from a non-substrate test harness. Pure transitions and pure sub computations are the bedrock of the test story.
7. **Conformance corpus is the acceptance test.** [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of what you've claimed; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — file a `bd create` bead against the spec corpus.
8. **If you find a spec gap, file a bead. Do not paper.** When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. Surface it. Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

---

## The two-phase workflow

### Phase 1 — Lock the decisions

Before any code is written, the engineer walks the **decision walkthrough** at `reference/phase-1-decisions.md` and produces a **decision record** (template: `reference/decision-record.md`). The decisions are:

1. **Target host language** — which language and runtime. The spec scope under `spec/000-Vision.md` names eight first-class JS-cross-compile hosts; the [Implementor-Checklist](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) covers more (Python, Rust, Kotlin/native, Swift) — but only the in-scope eight target React+VDOM substrates by default. Any other host language picks its own substrate (Q2).
2. **Substrate / view layer** — React+VDOM (the spec's default), a native UI toolkit, raw DOM, terminal UI, server-render only, no UI at all.
3. **Scope — which EPs ship now** — the core EPs (001 / 002 / 006 / 004) are mandatory for any conformance claim. The optional EPs (005 state machines, 007 stories, 008 testing, 009 instrumentation, 010 schemas, 011 SSR, 012 routing, 013 flows, 014 HTTP) are declared yes/no individually per [Implementor-Checklist Part 1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-1--how-complete).
4. **Foundation choices** — identity primitive, persistent data structures, reactive substrate, effect-handling primitive, concurrency model, hot-reload primitive ([Implementor-Checklist Part 2 §Foundation](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#foundation-always-required)).
5. **Schema mechanism** — runtime schema layer (Malli / Zod / Pydantic / dry-rb), host-type-system, or none ([Implementor-Checklist §Schemas](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#schemas-if-q4-is-yes)).
6. **Integration story** — standalone library, framework integration (React Native, SwiftUI, …), or embedded inside a larger runtime.
7. **Conformance capability tag set** — the union of `:core/*` (mandatory) plus the `:fsm/*` / `:actor/*` / `:routing/*` / `:ssr/*` / `:schemas/*` capability families that match Phase 1 scope.

The output of Phase 1 is a single locked-decision record. It is referenced from every Phase 2 step. The leaf `reference/phase-1-decisions.md` walks the engineer through each decision; `reference/decision-record.md` is the fill-in template.

### Phase 2 — Walk the spec corpus

With Phase 1 locked, the engineer implements in dependency order. Leaf: `reference/phase-2-impl-order.md`.

The walking order:

1. **EP 001 — Registration** ([spec](https://day8.github.io/re-frame2/spec/001-Registration/)). The registrar; closed-kinds discipline; the `reg-*` surface; metadata propagation; hot-reload semantics.
2. **EP 002 — Frames + events + effects + subscriptions** ([spec](https://day8.github.io/re-frame2/spec/002-Frames/)). Frame as runtime boundary `{state, queue, sub-cache, id}`; per-frame app-db; dispatch envelope; the six-step pipeline; run-to-completion drain; `:db` and `:fx` semantics; sub-cache invalidation.
3. **EP 006 — Reactive substrate** ([spec](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/)). The adapter contract: six required + two optional + one lifecycle function. Single-adapter-per-process. Revertibility constraints on adapters.
4. **EP 004 — Views** ([spec](https://day8.github.io/re-frame2/spec/004-Views/)). `reg-view`; render-tree shape (host-chosen, serialisable); frame-provider; source-coord stamping.
5. **EP 009 — Instrumentation** ([spec](https://day8.github.io/re-frame2/spec/009-Instrumentation/)). Trace event stream; error contract; retain-N ring buffer; production elision. Pattern-required.
6. **Conformance corpus pass #1** — at this point a port that's claimed `{Q1=no, Q2=no, Q3=no, Q4=via-types-or-no, Q5=no, Q6=no, Q7=no}` can pass the `:core/*` fixtures. This is the first acceptance gate.
7. **Optional EPs in the order Phase 1 declared yes for** — for each declared-yes capability, implement the matching EP and re-run the matching capability-tagged fixtures. Suggested order if multiple are in scope: 010 Schemas → 008 Testing → 005 State machines → 012 Routing → 011 SSR → 013 Flows → 014 HTTP → 007 Stories.

The leaf `reference/phase-2-impl-order.md` walks each EP with: what to read first, the contract to expose, how the CLJS reference realised it (as **one** example, not normative), what the conformance fixtures check, common spec-gap traps.

---

## Source discipline

Three tiers of input, in this priority:

1. **`spec/`** — the contract. Read in numeric order. Every implementation decision must trace to a normative claim here.
2. **[`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/)** — the decision-ordered companion. Part 1 (which capabilities ship), Part 2 (per-capability mechanism choices), Part 3 (how to consume the conformance corpus).
3. **`implementation/`** (the CLJS reference) — a worked example. Useful for "how did *someone* solve X?" Never useful as a contract claim. The reference's specific bindings (keywords, hiccup, Reagent ratoms, Closure dead-code elimination for production elision) are **choices**, not requirements.

If `implementation/` and `spec/` disagree, the spec wins. File a `bd create` bead so the inconsistency is tracked.

---

## Conformance — the acceptance test

The conformance corpus at `spec/conformance/` is host-agnostic data. The harness is ~300 lines per host (per [`spec/conformance/README.md` §How an implementation runs the corpus](https://day8.github.io/re-frame2/spec/conformance/#how-an-implementation-runs-the-corpus)):

1. Read all `.edn` fixtures in `fixtures/`.
2. For each fixture, check whether `:fixture/capabilities` is a subset of the port's claimed list.
3. If yes, bootstrap the runtime, realise handler bodies via the small EDN-handler-body DSL, create a frame, run the dispatches, capture observables, compare.
4. If no, report as "not exercised."
5. Aggregate score: `passed / claimed-applicable`.

Hosts without EDN parsing either ship a small reader (~200 lines for any host with hash-maps and vectors) or translate the corpus locally as part of harness bootstrap. The leaf `reference/conformance.md` walks the harness shape and the EDN-handler-body DSL.

The corpus is the **acceptance test for [Goal 2 — AI-implementable from the spec alone](https://day8.github.io/re-frame2/spec/000-Vision/#ai-implementable-from-the-spec-alone)**. A fixture that an implementation cannot reproduce without consulting outside sources is a **spec gap**, not an implementation gap; remediation is to fix the spec corpus, not the implementation.

---

## Kickoff prompt

If the engineer wants to *delegate* the implementation walkthrough to a fresh Claude Code session, there's a paste-ready prompt at `reference/kickoff-prompt.md`. The engineer copies it into a new Claude Code session opened in the root of a fresh repo for their port; the session loads this skill and walks Phase 1 + Phase 2 with the engineer.

This is the typical shape: the implementor wants a focused session to drive the work, asks decisions as they arise, references the spec for every contract claim.

---

## Done checklist

Tell the engineer the implementation is "v1-complete" against the claimed scope when **all** of these are true:

- [ ] Phase 1 decision record is written down, dated, and committed to the port's own repo (as a `DECISIONS.md` or equivalent).
- [ ] All EPs in scope (per Phase 1) have a working implementation in the port's source tree.
- [ ] The port's runtime exposes the API contract at [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/), adapted to the host's idiom.
- [ ] The conformance corpus has been run against the port; the score is `(claimed-applicable) / (claimed-applicable)` — all fixtures whose capabilities are a subset of the port's claim pass.
- [ ] Any conformance failures that were *not* spec gaps have been fixed in the port.
- [ ] Any conformance failures that *were* spec gaps have been filed as beads against the spec corpus (`bd create` against the re-frame2 repo).
- [ ] The port's own README states which capability tags it claims and the conformance score against those tags.

When the checklist passes, the port can claim "re-frame2 implementation, v1-complete against `<capability tag set>`."

---

## Deep dives — reference files

For depth on each step, read the matching leaf — every leaf is one level deep from this SKILL.md:

- **`reference/kickoff-prompt.md`** — A paste-ready prompt to drop into a fresh Claude Code session in the root of the engineer's port repo. ~80 lines.
- **`reference/phase-1-decisions.md`** — The Phase 1 walkthrough: seven decision blocks, options per host, trade-offs, links into [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) for the canonical option matrices. ~200 lines.
- **`reference/decision-record.md`** — The fill-in template for the locked-decision record. Reproducible across ports — the engineer copies the template into their own repo, fills in each block. ~120 lines.
- **`reference/phase-2-impl-order.md`** — EP-by-EP implementation order. For each EP: what to read, the contract to expose, what the CLJS reference did (as an example), what the conformance fixtures check, common spec-gap traps. ~250 lines.
- **`reference/reference-impl-tour.md`** — A guided tour of the CLJS reference under `implementation/` — what's where, what was a substrate-specific choice, what's pattern-required. Read this when you want to see how *someone* solved a problem, not to find out what re-frame2 requires. ~150 lines.
- **`reference/conformance.md`** — How to consume the conformance corpus. The harness shape, the EDN-handler-body DSL, capability tagging, how to score, when a failure is a spec gap vs an implementation bug. ~140 lines.
- **`reference/output-format.md`** — The standard agent-output shape: implementation summary, capability tags claimed, conformance score, decisions made, spec gaps filed. ~120 lines.

---

## Anti-patterns to avoid

A few traps that come up often enough to flag at the top level.

- **Don't copy the reference impl line-by-line and translate.** The reference uses macros for source-coord capture; your host may not have macros. The reference uses Reagent's automatic dependency tracking; your host may need explicit subscriptions. The reference uses keywords; your host may use branded strings. *Copy the contract, not the realisation.*
- **Don't skip Phase 1.** The decisions made under Phase 1 (especially F2 persistent data structures and F5 concurrency model) propagate through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" end up rewriting the foundation halfway through. The decision walkthrough takes one focused session; the rewrite takes weeks.
- **Don't declare Q1 (state machines) `yes` unless the FSM substrate is genuinely required.** EP 005 is substantial work and gates a large block of code. Smaller ports ship `Q1=no` and add the FSM substrate later — the events/subs/fx/views triad is self-sufficient for many use cases.
- **Don't ship without conformance.** The corpus is the only objective measure of "is this re-frame2?" Without the corpus passing, the port is "inspired by re-frame2" but not a re-frame2 implementation. Run the harness early; the corpus is also useful as your test suite during development.
- **Don't invent surfaces the spec is silent on.** If the spec says nothing about hierarchical FSM exit-order semantics in a specific edge case, *file a bead* — don't extrapolate from the CLJS reference. The reference's choice is one realisation; the spec's silence is a gap.
- **Don't promise the engineer "this skill will write the port for you".** The skill walks the workflow and surfaces the decisions; the engineer (or their Claude session) writes the code. Phase 2 is multi-week work in any host; the skill is the map, not the vehicle.

If you hit anything else surprising, surface it to the engineer rather than guessing. The implementor's agent's job is to walk the spec corpus cleanly and surface every decision back to the engineer — not to make the engineering choices for them.

---

*Authoritative contract: `spec/` corpus (in this repo). Decision-ordered companion: [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/). Conformance corpus: [`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance). CLJS reference (worked example): `implementation/`. For application authoring on the reference: `skills/re-frame2/`. For greenfield bootstrap: `skills/re-frame2-setup/`. For v1→v2 migration: `skills/re-frame-migration/`. For live-app inspection: `skills/re-frame-pair2/`.*
