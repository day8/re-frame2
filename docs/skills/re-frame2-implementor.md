# re-frame2-implementor

> Guides an engineer building a new re-frame2 implementation in another host language, with the conformance corpus as the acceptance test.

## What it does

The `re-frame2-implementor` skill is for engineers **building re-frame2 itself**, not applications with it. It takes you from "I want to port re-frame2 to TypeScript" to "my port passes the claimed-applicable subset of the conformance corpus." The in-scope hosts are the eight languages that compile to JavaScript and render through React: ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, F# (Fable), Squint, Scala.js, PureScript and Kotlin/JS, per the [spec/000-Vision.md](../../spec/000-Vision.md) scope footnote.

It works in two phases:

- **Phase 1 records a port profile** — one compact record of the spec pin and the choices you actually made: host and toolchain, the host mechanisms for identity, data, the React binding and the rest, the capabilities you claim with their known skips, and the current conformance score. For a minimum port there is **no interview**: optional capabilities default to no, mechanisms default to the host's idiom, and the first slice starts in the same run. The profile's shape is [`references/phase-1-decisions.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/references/phase-1-decisions.md).
- **Phase 2 repeats one loop** in spec dependency order: read the owning spec at the pinned commit, list the applicable conformance fixtures, implement the smallest vertical slice, run the narrowest gate that covers it, diagnose, and update the profile only if a real choice changed. The conformance harness is wired up early, so feedback starts with the first slice. The loop, the order and the acceptance gates are in [`references/phase-2-impl-order.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/references/phase-2-impl-order.md).

The authority is the [spec corpus](../../spec/000-Vision.md), with the [Implementor Checklist](../../spec/Implementor-Checklist.md) as its decision-ordered companion and the [conformance corpus](../../spec/conformance/README.md) as the acceptance test. The CLJS reference in `implementation/` is one worked example, never normative.

When it has tool access, the agent runs your port's noninteractive gates itself — the slice gate on every pass of the loop, and the full conformance passes when you asked for an end-to-end implementation — and reports exact commands, exit codes and `passed / claimed-applicable`. It hands you only genuinely interactive or visual checks, and never claims completion while required evidence is pending.

## When to reach for it

Use it when **any** of these are true:

- You are starting a port of re-frame2 to one of the in-scope host languages.
- You want to claim "this is a re-frame2 implementation" and need to know what the claim requires.
- You are working through the [Implementor Checklist](../../spec/Implementor-Checklist.md) and the [conformance corpus](../../spec/conformance/README.md) to verify your work.

Use a different skill for:

- Writing application code on the CLJS reference → [re-frame2](re-frame2.md).
- Bootstrapping a greenfield app on the CLJS reference → [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 codebase → [re-frame-migration](re-frame-migration.md).
- Inspecting or debugging a running v2 app → [re-frame2-pair](re-frame2-pair.md).

A **non-React substrate** (Vue, Solid, Svelte, vanilla DOM, native UI, a terminal UI) or a **host that does not compile to JavaScript** (Python, Ruby, native Rust, Go, server-side Kotlin / Java) is out of scope by spec decision, not by oversight; the skill says so and stops.

## Kickoff

Open a fresh Claude Code session in the root of your port's repo and paste the short kickoff prompt from [`SKILL.md` §Kickoff](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md), filling in the path to your re-frame2 spec checkout and the commit or tag it is pinned to. The session loads the skill, records the port profile, and starts the loop at the foundation, reporting exact commands and results as it goes.

## When it stops

- **The spec checkout does not verify.** Before reading anything, the skill checks that the checkout's `HEAD` and `origin` match the pin in the port profile and that its `spec/` tree is clean — uncommitted edits would otherwise pass as the recorded commit. On a mismatch it reports the paths and stops before deriving any obligation or score. It never resets, checks out or stashes to clear them; parking those edits, or pinning a commit that includes them, is up to you.
- **An out-of-scope target** — a non-React substrate or a host that does not compile to JavaScript. The skill cites the scope footnote and stops.
- **A spec gap** — a fixture that cannot pass without sources outside the spec. The skill does not paper over it or copy the reference implementation's behaviour. It searches the upstream `day8/re-frame2` issues (pointing you at an existing one if it matches), drafts one, shows you the full draft, and runs `gh issue create` only after an explicit yes.
- **A choice that materially changes the port** and cannot be defaulted — the one kind of question it asks during Phase 1.

## Where the skill lives

- Source: [`skills/re-frame2-implementor/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor)
- `SKILL.md`: [`skills/re-frame2-implementor/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md) — its §Reference files section lists the reference notes.
- Reference notes: [`skills/re-frame2-implementor/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor/references)
- One worked example: the CLJS reference in [`implementation/`](https://github.com/day8/re-frame2/tree/main/implementation) (descriptive, not normative).
