# Cardinal rules — re-frame2 implementor

These hold across every phase of building a new re-frame2 implementation. Each rule expands the one-line form carried in `SKILL.md`.

---

## 1. The spec is the contract

[`spec/`](../../../spec/) is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; file a `bd create` bead against the spec or the reference impl as appropriate.

## 2. Phase 1 before Phase 2

Decisions made under Phase 1 (host language, substrate, scope, identity primitive, concurrency model) are load-bearing for every line of code written in Phase 2. Lock them in writing before opening an editor. If a Phase 2 step forces a Phase 1 decision to change, **stop**, revise the decision record, and restart that Phase 2 step.

## 3. Implement in dependency order

EP 001 (Registration) → 002 (Frames + events + effects + subs) → 006 (Reactive substrate) → 004 (Views) are the foundation. The optional EPs (005, 007–014) sit downstream and can be deferred or skipped per Phase 1 scope.

## 4. Substrate-agnostic phrasing in code and docs

The reference impl is a CLJS+Reagent realisation — keywords as ids, hiccup as render-tree, Reagent ratoms as the reactive container. These are *choices the reference made*. Your port chooses differently and re-frame2 is still re-frame2. Do not write code that assumes "hiccup" or "Reagent" or "keyword" universally; write code that assumes "the identity primitive", "the render-tree", "the reactive container".

## 5. No core.async equivalents

The CLJS reference does not use core.async, and ports inherit this directive. Async effects schedule via host primitives (Promise / setTimeout / setImmediate / asyncio / tokio); cross-frame work is serialised per frame via the run-to-completion drain. If your host's idiomatic concurrency model is channel-shaped, you may use channels *internally* — but the public dispatch contract is still the run-to-completion drain.

## 6. JVM-runnability for the testing surface

Where the CLJS reference makes `compute-sub` and `machine-transition` pure-function-callable from JVM, your port's analogue must be callable from a non-substrate test harness. Pure transitions and pure sub computations are the bedrock of the test story.

## 7. Conformance corpus is the acceptance test

[`spec/conformance/`](../../../spec/conformance/) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of what you've claimed; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — file a `bd create` bead against the spec corpus.

## 8. If you find a spec gap, file a bead. Do not paper.

When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. Surface it. Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

---

## Anti-patterns (rule corollaries)

- **Don't copy the reference impl line-by-line and translate.** The reference uses macros for source-coord capture; your host may not have macros. The reference uses Reagent's automatic dependency tracking; your host may need explicit subscriptions. The reference uses keywords; your host may use branded strings. *Copy the contract, not the realisation.*
- **Don't skip Phase 1.** Decisions made under Phase 1 (especially F2 persistent data structures and F5 concurrency model) propagate through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" end up rewriting the foundation halfway through.
- **Don't declare Q1 (state machines) `yes` unless the FSM substrate is genuinely required.** EP 005 is substantial work and gates a large block of code. Smaller ports ship `Q1=no` and add the FSM substrate later — the events/subs/fx/views triad is self-sufficient for many use cases.
- **Don't ship without conformance.** The corpus is the only objective measure of "is this re-frame2?" Without the corpus passing, the port is "inspired by re-frame2" but not a re-frame2 implementation. Run the harness early; the corpus is also useful as your test suite during development.
- **Don't invent surfaces the spec is silent on.** If the spec says nothing about hierarchical FSM exit-order semantics in a specific edge case, *file a bead* — don't extrapolate from the CLJS reference. The reference's choice is one realisation; the spec's silence is a gap.
- **Don't promise the engineer "this skill will write the port for you".** The skill walks the workflow and surfaces the decisions; the engineer (or their Claude session) writes the code. Phase 2 is multi-week work in any host; the skill is the map, not the vehicle.
