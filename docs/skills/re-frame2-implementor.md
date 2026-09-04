# re-frame2-implementor

> Guides an engineer building a new re-frame2 implementation in a different host language. Two-phase workflow: Phase 1 records a compact port profile of the choices actually made; Phase 2 is one repeatable EP loop driven by the pinned spec and live conformance fixtures, with the conformance corpus as the acceptance test.

## What it does

The `re-frame2-implementor` skill is for engineers **building re-frame2 itself**, not building applications with it. It takes an engineer from "I want to port re-frame2 to TypeScript / F# (Fable) / one of the other in-scope JS-cross-compile hosts" to "my port passes the claimed-applicable subset of the conformance corpus." The in-scope targets are exactly the eight JS-cross-compile-to-React+VDOM host languages — ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, F# (Fable), Squint, Scala.js, PureScript, Kotlin/JS — per the [spec/000-Vision.md](../../spec/000-Vision.md) scope footnote.

**Phase 1 — record the port profile.** One compact record of the spec pin and the choices the implementor actually made: host + toolchain; host mechanisms (identity primitive, persistent data, React binding and reactive container, render-tree shape, schema mechanism, hot-reload); the claimed capability set with explicit known-skips; the current conformance score. Fixed spec obligations are links to their owning Specs, never fields to reconfirm. An unqualified minimum-port request takes **no interview**: optional capabilities — the Implementor Checklist's nine numbered scope questions — default to no, mechanisms default to the host's idiom, and the skill asks only about a missing choice that materially changes the implementation — then begins the first implementation slice in the same run. The v1-required capabilities are fixed obligations rather than scope choices, so "minimum" never defaults one of them away.

**Phase 2 — the EP loop.** Implement in dependency order — [001 Registration](../../spec/001-Registration.md) → [002 Frames + events + effects + subs](../../spec/002-Frames.md) → [006 Reactive substrate](../../spec/006-ReactiveSubstrate.md) → views → [009 Instrumentation](../../spec/009-Instrumentation.md) → [015 Data Classification](../../spec/015-Data-Classification.md) (v1-required; it overlays the 009 emission boundary) → [013 Flows](../../spec/013-Flows.md) (v1-required; it stands on every step before it, and closes the foundation). Each EP is one pass of a uniform loop: read the owning Spec at the recorded pin; enumerate the applicable fixtures, capability tags, and operators from that same pin; implement the smallest vertical slice; run the narrowest gate that covers it; repair or diagnose (implementation bug vs spec gap); update the profile only if a real choice changed. The smallest useful conformance-harness seam bootstraps before or alongside the first foundation slice — feedback starts early, and the harness fails loud on unknown spec versions, capabilities, DSL ops, and call ops. Acceptance gate 1 — the **required-foundation gate** — runs every fixture applicable to the four v1-required families (`:core/*` + `:identity/*` + `:flow/*` + `:data-classification/*`), none of which a port may decline; then optional EPs per the profile's claim; acceptance gate 2 runs the full claimed-capability set.

The authoritative contract is the [spec corpus](../../spec/000-Vision.md), with the [Implementor Checklist](../../spec/Implementor-Checklist.md) as the decision-ordered companion and the [conformance corpus](../../spec/conformance/README.md) as the acceptance test. The CLJS reference at `implementation/` is one worked example, never normative — the skill is explicit about this throughout.

**Verification ownership.** The agent runs the port's *discovered* noninteractive gates itself when it has tool access — the per-EP slice at every loop pass, and the full gate-1 / gate-2 conformance passes when the engineer asked for an end-to-end implementation — reporting exact commands, exit codes, and `passed / claimed-applicable`. Only genuinely interactive/visual evidence (a rendered surface with no drivable runtime) is handed off to the engineer, and completion is never claimed while required evidence is pending.

## When to reach for it

Load this skill when **any** of these are true:

- The engineer is starting a port of re-frame2 to one of the eight in-scope host languages (TypeScript, Fable F#, Kotlin/JS, Squint, Scala.js, PureScript, Reason / ReScript / Melange — plus ClojureScript, the reference).
- The engineer wants to claim "this is a re-frame2 implementation" and needs to know what the claim requires.
- The engineer is consuming the [Implementor Checklist](../../spec/Implementor-Checklist.md) and the [conformance corpus](../../spec/conformance/README.md) to verify their work.

Do **not** use this skill for:

- Writing application code on the CLJS reference → use [re-frame2](re-frame2.md).
- Bootstrapping a greenfield app on the CLJS reference → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 codebase → use [re-frame-migration](re-frame-migration.md).
- Inspecting / debugging a running v2 app → use [re-frame2-pair](re-frame2-pair.md).
- Implementing re-frame2 against a **non-React substrate** (Vue, Solid, Svelte, vanilla DOM, native UI, a terminal UI) or a **non-cross-compile-to-JS host** (Python, Ruby, native Rust, Go, server-side Kotlin / Java) → these are **out of scope** by deliberate spec choice ([spec/000-Vision.md](../../spec/000-Vision.md) scope footnote), not an oversight. There is no implementation track to sequence; the skill surfaces the scope footnote and stops.

## Kickoff

A short paste-ready kickoff prompt ships in the skill router itself, at [`skills/re-frame2-implementor/SKILL.md` §Kickoff](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md). The engineer opens a fresh Claude Code session in the root of the port's repo and pastes it, filling in the spec-checkout path and pin. The session loads the skill on its own, records the port profile (no interview for a minimum port), and walks the EP loop from the foundation — bootstrapping the conformance seam alongside the first slice and reporting exact commands and results as it goes.

## Where the skill lives

- Source: [`skills/re-frame2-implementor/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor)
- `SKILL.md`: [`skills/re-frame2-implementor/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-implementor/SKILL.md)
- Reference leaves: [`skills/re-frame2-implementor/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-implementor/references) — `cardinal-rules.md` (the eleven rules + anti-pattern corollaries), `phase-1-decisions.md` (the port profile: defaults, spec pin, template), `phase-2-impl-order.md` (the EP loop + the EP index), `conformance.md` (harness, capability derivation, scoring, diagnosis).
- Authoritative contract: the [spec corpus](../../spec/000-Vision.md) + [Implementor Checklist](../../spec/Implementor-Checklist.md) + [conformance corpus](../../spec/conformance/README.md).
- One worked example: the CLJS reference at [`implementation/`](https://github.com/day8/re-frame2/tree/main/implementation) (descriptive, not normative).
