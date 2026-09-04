---
name: re-frame2-implementor
description: >
  Guides an engineer building a NEW re-frame2 implementation in one of the
  eight in-scope JS-cross-compile-to-React+VDOM host languages — ClojureScript
  (the reference), TypeScript, Melange/ReScript/Reason, F# (Fable), Squint,
  Scala.js, PureScript, Kotlin/JS — over a two-phase workflow (record the port
  profile, then one EP loop driven by the pinned spec and live conformance
  fixtures, with the conformance corpus as the acceptance test). **Do not
  use** for: writing apps on the CLJS reference (`re-frame2`), greenfield
  bootstrap (`re-frame2-setup`), v1→v2 migration (`re-frame-migration`),
  live-app inspection (`re-frame2-pair`), or an out-of-scope target — a
  non-React substrate (Vue, Solid, Svelte, vanilla DOM) or a host that does
  not cross-compile to JS (Python, Ruby, native Rust, Go, server-side JVM):
  out of scope by deliberate spec choice, so surface the `spec/000-Vision.md`
  scope footnote and stop. Trigger on "port re-frame2", "implement re-frame2
  in a language", "conformance corpus".
allowed-tools:
  - Bash(gh issue *)
  - Bash(git -C * rev-parse *)
  - Bash(git -C * remote get-url *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2-implementor

This skill is **workflow + guidance** layered on the pinned spec corpus at [`spec/`](../../spec). The spec is the contract; the conformance corpus is the acceptance test; the reference impl under `implementation/` is one worked example, not normative. The skill's job is to route you **into** the pinned spec and live fixtures — it does not mirror them.

Two phases:

1. **Phase 1 — record the port profile** ([`references/phase-1-decisions.md`](references/phase-1-decisions.md)): one compact record of the spec pin and the choices the implementor actually made — host/toolchain, host mechanisms, capability claim, current score. No interview by default: a minimum-port request defaults every optional capability — the checklist's Q1–Q9 — to no, picks host-idiomatic mechanisms, and asks only about a missing choice that materially changes the implementation. The v1-required capabilities are not in that set and are never defaulted away. Fixed spec obligations are links, never confirmation fields. Persisting the profile is a currentness concern — "committed and current" is a §Done item, not a gate before code.
2. **Phase 2 — the EP loop** ([`references/phase-2-impl-order.md`](references/phase-2-impl-order.md)): per EP — read the owner at the pin, enumerate the applicable fixtures/operators from that same pin, implement the smallest vertical slice, run the narrowest gate that covers it, repair or diagnose, update the profile only if a real choice changed. The feedback seam (the smallest conformance harness) bootstraps before or alongside the first foundation slice, and fails loud on unknown spec versions, capabilities, DSL ops, call ops, and top-level fixture keys.

> **Term: EP** = **Extension Point**, a numbered per-area Spec — EP 001 = [`spec/001-Registration.md`](../../spec/001-Registration.md), EP 002 = [`spec/002-Frames.md`](../../spec/002-Frames.md), etc. The spec calls these "numbered Specs"; this skill uses "EP" because the walking order, dependency graph, and conformance-fixture families are all keyed off the numbers.
>
> **The corpus spells a different thing "EP" too, and you will meet both.** [`docs/EP/`](../../docs/EP/README.md) holds the project's **Enhancement Proposals** — `EP-0012`, `EP-0018`, `EP-0025` and the rest — design records on the Python-PEP model, *not* Specs, and this skill cites them in that four-digit hyphenated form. The two are told apart by shape: **three digits after a space is a Spec** (`EP 012` = [`spec/012-Routing.md`](../../spec/012-Routing.md)); **four digits after a hyphen is a proposal** (`EP-0012` = the path-optics-and-canonical-forms record). A proposal is never the contract you implement against — read its normative Spec, which the citation names.

## When NOT to use this skill

Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for authoring on the CLJS reference, greenfield bootstrap, v1→v2 migration, live-app inspection, or pattern-rationale reading. Non-React substrates and non-cross-compile-to-JS hosts are out of scope by deliberate spec choice — surface the [scope footnote](../../spec/000-Vision.md) and stop.

## Cardinal rules (one-liners; full text in [`references/cardinal-rules.md`](references/cardinal-rules.md))

1. **Spec is the contract — pinned before reading.** Verify the checkout's HEAD and origin against the pin recorded in the port profile, and resolve every contract read through that checkout at the pin — the live-site URLs in this skill are citations, never the reading route; when `implementation/` and `spec/` disagree, the spec wins.
2. **Phase 1 before Phase 2.** Record the profile before implementing; committing it is a done-gate concern, not a pre-code gate.
3. **Dependency order.** EP 001 → 002 → 006 → views → 009 → 015 → 013 are the foundation (015 Data Classification and 013 Flows are both v1-required — 015 rides the 009 emission boundary, and 013 stands on everything before it); optional EPs sit downstream.
4. **Substrate-agnostic phrasing.** "The identity primitive", "the render-tree", "the reactive container" — not hiccup / Reagent / keywords.
5. **No core.async equivalents.** Async effects ride host primitives; cross-frame work is run-to-completion-drained.
6. **JVM-runnability for testing.** Pure transitions and pure sub computations must be callable from a non-substrate harness.
7. **Conformance corpus is the acceptance test.** Score is `passed / claimed-applicable`; a fixture you can't make pass without outside sources is a *spec gap*.
8. **Spec gap → search upstream issues, draft, ask before filing.** Never paper, never extrapolate from the reference, never auto-file; spec gaps reach maintainers via upstream `day8/re-frame2` GitHub issues — never via `bd`. Full shell-safety recipe (search-first, safe-alphabet title/keywords, `--body-file` filing, reviewer pass) in [`references/cardinal-rules.md` §8](references/cardinal-rules.md).
9. **Per-issue approval gate for any cross-repo side effect.** Show the full draft and wait for an explicit "yes" before `gh issue create`. See [`references/cardinal-rules.md` §9](references/cardinal-rules.md).
10. **Honour the reserved `:rf/*` scheme** — with the fixed three-fx unqualified carve-out (`:dispatch`, `:dispatch-later`, `:raise`). See [`references/cardinal-rules.md` §10](references/cardinal-rules.md); catalogue in [`spec/Conventions.md`](../../spec/Conventions.md).
11. **One path algebra, one canonical identity** (`:rf/path` + CEDN-1) — implement the shared foundation once; no subsystem keeps private overlap/canonicalization logic. See [`references/cardinal-rules.md` §11](references/cardinal-rules.md).

## Verification — who runs what

The agent runs the port's **discovered noninteractive gates** itself when it has tool access: the per-EP slice gate at every loop step, and the full required-foundation / claimed-capability conformance passes when the engineer asked for an end-to-end implementation — reporting exact commands, exit codes, and `passed / claimed-applicable`. Only genuinely interactive/visual evidence (a rendered surface with no drivable runtime) is handed off, concisely, to the programmer — and the port is never "complete" while required evidence is pending. The agent uses the session's normal permissions; there is no skill-local engineer/agent relay policy. (The skill's own allow-list covers only what it runs everywhere: `gh issue *` for spec-gap filing, and the read-only spec-pin provenance checks.)

## Checkpoints

Report at whatever granularity fits the work — no fresh-session, one-EP-per-session, per-EP-commit, or report-template mandate. A checkpoint carries: changed profile lines, what was built and what it showed, the exact test command + outcome, the conformance delta, and genuine blockers / spec gaps.

## Kickoff (optional paste-ready prompt)

> *I'm implementing a new port of re-frame2 in this repo. Follow the `re-frame2-implementor` skill. The spec corpus is at `<path-to-re-frame2>/spec/`, pinned at `<sha-or-tag>` — verify the pin and origin per cardinal rule 1 before reading anything. Record the port profile (`references/phase-1-decisions.md`) — this is a minimum port: default every optional capability — the checklist's Q1–Q9 — to no, pick host-idiomatic mechanisms, and ask me only about a choice that materially changes the implementation. The v1-required capabilities are not scope choices, so don't default those away. Then walk the EP loop (`references/phase-2-impl-order.md`) from the foundation in order (001 → 002 → 006 → views → 009 → 015 → 013), bootstrapping the conformance seam alongside the first slice. Run the narrow slice gate after every slice and report exact commands and results. The spec is the contract; the CLJS reference is one worked example. Spec gaps: search upstream issues, draft, and ask me before filing.*

## Done — "v1-complete against the claim"

- [ ] Port profile committed and current (pin, choices, claim, score).
- [ ] Foundation landed in order (001 → 002 → 006 → views → 009 → 015 → 013), on the shared path + identity foundation (`EP-0012`, a [`docs/EP/`](../../docs/EP/README.md) proposal; its normative text is [`spec/Conventions.md`](../../spec/Conventions.md)). The **views** step includes the view-hierarchy walker ([`spec/View-Hierarchy-Capture.md`](../../spec/View-Hierarchy-Capture.md)) — a v1 contract for every React-backed host that no conformance family grades, so gate 1 alone never discharges it.
- [ ] Acceptance gate 1 green: every fixture applicable to `:core/*` + `:identity/*` + `:flow/*` + `:data-classification/*` at the pin — the four v1-required families. None of them may be narrowed out of the claim: the harness refuses a `known-skipped` entry naming a required capability, so this box cannot be ticked by declaring the omission.
- [ ] EP-006 live sub-cache witness green ([`references/phase-2-impl-order.md` §The EP-006 live sub-cache witness](references/phase-2-impl-order.md#the-ep-006-live-sub-cache-witness-port-owned)) — required whenever the cache mechanism does not intrinsically key by `rf=`: one query through two distinct host allocations, one cache-slot, exactly-once disposal, non-`rf=` negative control. Reported beside the corpus score, never folded into it; the fixtures alone cannot see a reference-keyed live cache.
- [ ] Optional EPs per the claim; acceptance gate 2 = `claimed-applicable / claimed-applicable`.
- [ ] The port exposes [`spec/API.md`](../../spec/API.md), adapted to host idiom; tooling-security obligations honoured for any tooling shipped (the conformance harness counts).
- [ ] Spec gaps filed upstream with approval; the port's README states the claimed tags, the score, and the corpus pin.

## Reference files (all one level deep)

- [`references/cardinal-rules.md`](references/cardinal-rules.md) — the eleven rules in prose + anti-pattern corollaries.
- [`references/phase-1-decisions.md`](references/phase-1-decisions.md) — the port profile: defaults, spec pin, template.
- [`references/phase-2-impl-order.md`](references/phase-2-impl-order.md) — the EP loop + the EP index.
- [`references/conformance.md`](references/conformance.md) — harness, capability derivation, scoring, diagnosis.

---

*Authoritative contract: [`spec/`](../../spec). Decision companion: [`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md). Conformance: [`spec/conformance/`](../../spec/conformance). CLJS reference (worked example): `implementation/`. Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
