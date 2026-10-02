# re-frame2-implementor

Guides an engineer building a new re-frame2 implementation in another host language, with the conformance corpus as the acceptance test.

## Kickoff

In the root of your port's repository, name the host and the local contract to implement:

> Using re-frame2-implementor, build a minimum TypeScript port. Read the spec from `<checkout>/spec/` at `<commit-or-tag>`. Verify the pin, origin and clean spec tree, record the port profile, then implement and test the first foundation slice. Default optional capabilities to no.

Replace the placeholders with your checkout and revision. You can resume in the same session; there is no one-spec-per-session or per-spec-commit requirement. Name any optional capabilities you want in the request or add them to the profile later. The longer [kickoff prompt](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md#kickoff-optional-paste-ready-prompt) includes the full implementation loop.

When resuming, the skill reads your existing profile, re-verifies the pinned checkout and continues the first unfinished slice. Defaults fill missing choices; they do not erase your existing claim. Scores come from actual full harness runs. A narrow slice's result remains labelled separately, and a previous score is marked stale when the code, claim or pin changes.

## What it does

The skill is for engineers **building re-frame2 itself**, not applications with it. It takes you from "I want to port re-frame2 to TypeScript" to "my port passes every conformance fixture that applies to the capabilities it claims." The in-scope hosts are the eight languages that compile to JavaScript and render through React: ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, F# (Fable), Squint, Scala.js, PureScript and Kotlin/JS, as set by the scope footnote in the [Vision spec](../../spec/000-Vision.md).

It works in two phases:

- **Phase 1 records a port profile** — one compact record of the spec pin and the choices you actually made: host and toolchain, the host mechanisms for identity, data, the React binding and the rest, the capabilities you claim with their known skips, and the current conformance score. For a minimum port there is **no interview**: optional capabilities default to no, mechanisms default to the host's idiom, and the first slice starts in the same run. The four v1-required capability families — `:core/*`, `:identity/*`, `:flow/*` and `:data-classification/*` — are not options, and nothing defaults them away. The profile's shape is [`references/phase-1-decisions.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/references/phase-1-decisions.md).
- **Phase 2 repeats one loop** in spec dependency order: read the owning spec at the pinned commit, list the applicable conformance fixtures, implement the smallest vertical slice, run the narrowest check that covers it, diagnose, and update the profile only if a real choice changed. The conformance harness is wired up early, so feedback starts with the first slice. The loop, the order and the acceptance gates are in [`references/phase-2-impl-order.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/references/phase-2-impl-order.md).

The authority is the [spec corpus](../../spec/000-Vision.md), with the [Implementor Checklist](../../spec/Implementor-Checklist.md) as its decision-ordered companion and the [conformance corpus](../../spec/conformance/README.md) as the acceptance test. The CLJS reference in `implementation/` is one worked example, never normative.

When it has tool access, the agent runs your port's noninteractive checks itself — the current slice's check on every pass of the loop, and the full conformance runs when you asked for an end-to-end implementation — and reports exact commands, exit codes and the conformance score (`passed / claimed-applicable`). It hands you only genuinely interactive or visual checks, and never claims completion while required evidence is pending.

## When to reach for it

Use it when you are building a re-frame2 implementation rather than an application: deciding where a port starts, what claiming to be a re-frame2 implementation requires, and how the port is checked against the conformance corpus.

For related work:

- Writing application code on the CLJS reference → [re-frame2](re-frame2.md).
- Moving a re-frame v1 app to re-frame2, which is also called porting → [re-frame-migration](re-frame-migration.md).

A **non-React view layer** (Vue, Solid, Svelte, vanilla DOM, native UI, a terminal UI) or a **host that does not compile to JavaScript** (Python, Ruby, native Rust, Go, server-side Kotlin / Java) is out of scope by spec decision, not by oversight; the skill cites the scope footnote and stops.

## What completion means

A port is complete against its declared capabilities when every applicable fixture passes, all four required capability families are present, and the port exposes the specified public API in its host's idiom. The committed port profile and README record the claim, score and corpus pin. Optional capabilities may be left out with reasons; a required capability cannot be skipped to raise the score.

A port whose subscription cache does not inherently use re-frame2 equality must also demonstrate that two distinct host allocations of an equal query share one live cache entry, dispose exactly once, and remain distinct from an unequal query. This live-cache check is reported beside the fixture score: passing the corpus alone does not prove that property. The [implementation loop](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/references/phase-2-impl-order.md#the-ep-006-live-sub-cache-witness-port-owned) describes the witness.

## When it stops

- **The spec checkout does not match its pin.** Before reading anything, the skill checks that the checkout's `HEAD` and `origin` match the pin in the port profile and that its `spec/` tree has no uncommitted changes or extra untracked/ignored files, which would otherwise pass as the recorded commit. On a mismatch it reports the paths and stops. It never resets, checks out or stashes to fix this; committing or setting aside those edits, or pinning a commit that includes them, is up to you.
- **A spec gap** — a fixture that cannot pass without sources outside the spec. The skill does not paper over it or copy the reference implementation's behaviour. It searches the upstream `day8/re-frame2` issues (pointing you at an existing one if it matches), drafts one, shows you the full draft, and runs `gh issue create` only after an explicit yes.
- **A choice that materially changes the port** and cannot be defaulted — the one kind of question it asks during Phase 1.

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md) contains the full workflow and links to its reference notes.
