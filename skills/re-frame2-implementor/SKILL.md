---
name: re-frame2-implementor
description: >
  Guides an engineer building a NEW re-frame2 implementation in a different
  host language or substrate — TypeScript, F# (Fable), Kotlin/JS, Squint,
  Scala.js, PureScript, ReScript, Python, Rust, native UI, terminal, or any
  host where re-frame2's pattern can be realised. Drives a two-phase
  workflow (Phase 1: lock decisions; Phase 2: walk the spec corpus in
  dependency order with the conformance corpus as the acceptance test).
  Trigger on phrasing like "port re-frame2", "implement re-frame2 in
  <language>", "second re-frame2 implementation", "implementor checklist",
  "conformance corpus", or any prompt about building re-frame2 itself.
  **Do not use** for: writing apps on the CLJS reference (use
  `re-frame2`), greenfield bootstrap (use `re-frame2-setup`), v1→v2
  migration (use `re-frame-migration`), or live-app inspection (use
  `re-frame2-pair`).
allowed-tools:
  - Bash(gh issue *)
  - Bash(gh pr *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2-implementor

This skill is **workflow + guidance** layered on the spec corpus at [`spec/`](../../spec/). The spec is the contract; the reference impl under `implementation/` is one worked example, not normative.

The job is to walk the engineer through two phases:

1. **Phase 1** — lock the load-bearing decisions (target language, substrate, scope, identity primitive, persistent data, concurrency, schema mechanism, hot-reload).
2. **Phase 2** — implement EPs in dependency order (001 → 002 → 006 → 004 → 009, then optional EPs per Phase 1 scope), validated by the conformance corpus.

## When NOT to use this skill

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for authoring on the CLJS reference, greenfield bootstrap, v1→v2 migration, live-app inspection, or pattern-rationale reading.

## Cardinal rules (one-liners; full text in [`reference/cardinal-rules.md`](reference/cardinal-rules.md))

1. **Spec is the contract.** When `implementation/` and [`spec/`](../../spec/) disagree, the spec wins.
2. **Phase 1 before Phase 2.** Lock decisions in writing before writing code.
3. **Dependency order.** EP 001 → 002 → 006 → 004 → 009 are the foundation; optional EPs sit downstream.
4. **Substrate-agnostic phrasing.** Write to "the identity primitive", "the render-tree", "the reactive container" — not to hiccup / Reagent / keywords.
5. **No core.async equivalents.** Async effects ride host primitives; cross-frame work is run-to-completion-drained.
6. **JVM-runnability for testing.** Pure transitions and pure sub computations must be callable from a non-substrate harness.
7. **Conformance corpus is the acceptance test.** Score is `passed / claimed-applicable`; a fixture you can't make pass without outside sources is a *spec gap*.
8. **Spec gap → draft a GitHub issue against `day8/re-frame2` and ask before filing.** Don't paper, don't invent, don't extrapolate from the reference — but don't auto-file either. Show the engineer the drafted title + body, restrict the body to public spec-quoted evidence (no private port source), and wait for explicit OK before running `gh issue create`. The skill runs in the engineer's port repo; spec gaps reach the framework maintainers via the upstream repo's GitHub issues — never via `bd` (re-frame2's internal tracker, never invoked from a published skill).
9. **Per-issue approval gate for any cross-repo side effect.** Before running `gh issue create` against a repo other than the one the engineer is working in, show the full draft (title, target repo, label set, body) and wait for explicit "yes" / "go" / "file it". Invoking the skill is consent to the workflow, not to each cross-repo write. See [`reference/cardinal-rules.md` §9](reference/cardinal-rules.md).
10. **Pin the spec corpus.** The kickoff prompt names a specific `day8/re-frame2` commit/tag; verify the checkout's HEAD and origin before reading the spec, and record the pinned hash in `DECISIONS.md` (preamble before D1). An unverified checkout is not the contract.

## Phase 1 — lock the decisions

Walk [`reference/phase-1-decisions.md`](reference/phase-1-decisions.md) and produce a locked-decision record using the [`reference/decision-record.md`](reference/decision-record.md) template. The seven decision blocks (D1 target language, D2 substrate, D3 scope, D4 foundation choices, D5 schema mechanism, D6 integration story, D7 capability tag set) are detailed there; the canonical option matrices live in [`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md).

Output of Phase 1: a single dated decision record committed to the port's own repo.

## Phase 2 — walk the spec corpus

With Phase 1 locked, walk [`reference/phase-2-impl-order.md`](reference/phase-2-impl-order.md) EP-by-EP. The leaf carries, for each EP: what to read first, the contract to expose, how the CLJS reference realised it (as **one** example, not normative), what the conformance fixtures check, common spec-gap traps.

Dependency order is fixed: **EP 001 Registration → 002 Frames → 006 Reactive substrate → 004 Views → 009 Instrumentation**, then a first conformance pass against the `:core/*` fixtures. Optional EPs (010 Schemas, 008 Testing, 005 State machines, 012 Routing, 011 SSR, 013 Flows, 014 HTTP, 007 Stories) follow in the order Phase 1 declared `yes` for them.

## Source discipline

Three tiers, in priority order:

1. **[`spec/`](../../spec/)** — the contract. Read in numeric order.
2. **[`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md)** — the decision-ordered companion.
3. **`implementation/`** — a worked example. Useful for "how did *someone* solve X?" Never useful as a contract claim.

If `implementation/` and `spec/` disagree, the spec wins.

## Conformance

The corpus at [`spec/conformance/`](../../spec/conformance/) is host-agnostic data. Harness shape, the EDN-handler-body DSL, capability tagging, scoring, and the spec-gap-vs-implementation-bug distinction are all in [`reference/conformance.md`](reference/conformance.md). The corpus is the acceptance test for [Goal 2 — AI-implementable from the spec alone](https://day8.github.io/re-frame2/spec/000-Vision/#ai-implementable-from-the-spec-alone).

## Kickoff and output

- [`reference/kickoff-prompt.md`](reference/kickoff-prompt.md) — paste-ready prompt for the engineer to drop into a fresh Claude session opened in the root of their port repo.
- [`reference/output-format.md`](reference/output-format.md) — the standard agent-output shape: implementation summary, capability tags claimed, conformance score, decisions made, spec gaps filed.

## Done — "v1-complete against `<capability tag set>`"

- [ ] Phase 1 decision record committed to the port's repo.
- [ ] All in-scope EPs have a working implementation.
- [ ] The port exposes [`spec/API.md`](../../spec/API.md), adapted to host idiom.
- [ ] Conformance score is `(claimed-applicable) / (claimed-applicable)`.
- [ ] Non-spec-gap failures fixed in the port; spec-gap failures filed as GitHub issues against `day8/re-frame2`.
- [ ] Port's README states claimed capability tags and conformance score.

## Reference files (all one level deep)

- [`reference/cardinal-rules.md`](reference/cardinal-rules.md) — the eight rules in prose + anti-pattern corollaries.
- [`reference/phase-1-decisions.md`](reference/phase-1-decisions.md) — Phase 1 walkthrough, seven decision blocks.
- [`reference/decision-record.md`](reference/decision-record.md) — fill-in template for the locked-decision record.
- [`reference/phase-2-impl-order.md`](reference/phase-2-impl-order.md) — EP-by-EP implementation order.
- [`reference/reference-impl-tour.md`](reference/reference-impl-tour.md) — guided tour of the CLJS reference; what's substrate-specific vs pattern-required.
- [`reference/conformance.md`](reference/conformance.md) — corpus harness, DSL, capability tagging, scoring.
- [`reference/kickoff-prompt.md`](reference/kickoff-prompt.md) — fresh-session kickoff prompt.
- [`reference/output-format.md`](reference/output-format.md) — agent-output shape.

---

*Authoritative contract: [`spec/`](../../spec/). Decision companion: [`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md). Conformance: [`spec/conformance/`](../../spec/conformance/). CLJS reference (worked example): `implementation/`. Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
