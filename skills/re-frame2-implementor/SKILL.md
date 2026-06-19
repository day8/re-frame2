---
name: re-frame2-implementor
description: >
  Guides an engineer building a NEW re-frame2 implementation in one of the
  eight in-scope JS-cross-compile-to-React+VDOM host languages —
  ClojureScript (the reference), TypeScript, Melange/ReScript/Reason,
  F# (Fable), Squint, Scala.js, PureScript, Kotlin/JS. Drives a two-phase
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
  - Bash(git -C * rev-parse *)
  - Bash(git -C * remote get-url *)
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
2. **Phase 2** — implement EPs in dependency order (001 → 002 → 006 → 004 → 009 → 015, then optional EPs per Phase 1 scope), validated by the conformance corpus.

> **Term: EP.** Throughout this skill, "EP" abbreviates **Extension Point** — a numbered per-area Spec in the corpus at [`spec/`](../../spec/). EP 001 corresponds to [`spec/001-Registration.md`](../../spec/001-Registration.md), EP 002 to [`spec/002-Frames.md`](../../spec/002-Frames.md), and so on. The spec itself calls these "numbered Specs"; this skill uses "EP" as a compact shorthand because the walking order, dependency graph, and conformance-fixture families are all keyed off the numbers.

## When NOT to use this skill

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief: not for authoring on the CLJS reference, greenfield bootstrap, v1→v2 migration, live-app inspection, or pattern-rationale reading.

## Cardinal rules (one-liners; full text in [`references/cardinal-rules.md`](references/cardinal-rules.md))

1. **Spec is the contract — pinned before reading.** When `implementation/` and [`spec/`](../../spec/) disagree, the spec wins. The kickoff prompt names a `day8/re-frame2` commit/tag; verify the checkout's HEAD and origin before reading the spec and record the pin in `DECISIONS.md` (preamble before D1). An unverified checkout is not the contract.
2. **Phase 1 before Phase 2.** Lock decisions in writing before writing code.
3. **Dependency order.** EP 001 → 002 → 006 → 004 → 009 → 015 are the foundation; optional EPs sit downstream. (Spec 015 Data Classification is v1-required — it rides the 009 emission boundary, so it lands right after instrumentation.)
4. **Substrate-agnostic phrasing.** Write to "the identity primitive", "the render-tree", "the reactive container" — not to hiccup / Reagent / keywords.
5. **No core.async equivalents.** Async effects ride host primitives; cross-frame work is run-to-completion-drained.
6. **JVM-runnability for testing.** Pure transitions and pure sub computations must be callable from a non-substrate harness.
7. **Conformance corpus is the acceptance test.** Score is `passed / claimed-applicable`; a fixture you can't make pass without outside sources is a *spec gap*.
8. **Spec gap → search existing upstream issues, then draft a GitHub issue against `day8/re-frame2` and ask before filing.** Don't paper, don't invent, don't extrapolate from the reference — but don't auto-file either. Search first (`gh issue list`) and reference a match instead of duplicating; if none, show the engineer the drafted title + body (public spec-quoted evidence only, no private port source) and wait for explicit OK before `gh issue create`. Author the title + search keywords from the safe alphabet and file the body via `--body-file` — the full shell-safety recipe (temp-path nonce, the no-`--title-file`/no-`--search-file` argv rules, the reviewer pass) is [`references/cardinal-rules.md` §8](references/cardinal-rules.md), kept in sync with `skills/shared/issue-filing.md`. The skill runs in the engineer's port repo; spec gaps reach maintainers via the upstream repo's GitHub issues — never via `bd` (re-frame2's internal tracker, never invoked from a published skill).
9. **Per-issue approval gate for any cross-repo side effect.** Before running `gh issue create` against a repo other than the one the engineer is working in, show the full draft (title, target repo, label set, body) and wait for explicit "yes" / "go" / "file it". Invoking the skill is consent to the workflow, not to each cross-repo write. See [`references/cardinal-rules.md` §9](references/cardinal-rules.md).
10. **Honour the reserved `:rf/*` scheme — with the three-fx carve-out.** Framework ids live under the single root `:rf/*`; user code MUST NOT register there. Framework durable state lives in the **runtime-db** partition `:rf.db/runtime` (addressed by `:rf.runtime/*`), separate from app-db — the former app-db root `:rf/runtime` is retired and now HARD-ERRORS (`:rf.error/legacy-runtime-root`). Carve-out: the fx-ids `:dispatch`, `:dispatch-later`, `:raise` ship **unqualified** as frozen legacy — register/recognise them as-is, don't namespace or reject them. Full text [`references/cardinal-rules.md` §10](references/cardinal-rules.md); per [`spec/Conventions.md`](../../spec/Conventions.md), surface map [`spec/Ownership.md`](../../spec/Ownership.md).
11. **One path algebra, one canonical identity — implement the shared foundation once** (`:rf/path` + CEDN-1, EP-0012). Paths and equality-sensitive identity are a single contract every subsystem inherits, stated once and not reinvented per-feature; the semantics are normative immediately, the helpers internal at this slice (internal-first). Full text [`references/cardinal-rules.md` §11](references/cardinal-rules.md); the canonical statement is [`references/phase-2-impl-order.md` §The shared path + identity foundation](references/phase-2-impl-order.md#the-shared-path--identity-foundation-ep-0012).

## Phase 1 — lock the decisions

Walk [`references/phase-1-decisions.md`](references/phase-1-decisions.md) and produce a locked-decision record using the [`references/decision-record.md`](references/decision-record.md) template. The seven decision blocks (D1 target language, D2 substrate, D3 scope, D4 the always-required realisation decisions — Implementor-Checklist Part 2's F1–F6 / S1–S3 / Sub1–Sub2 / V1–V3 / T1–T3 / E1–E2, sub-numbered 1:1 with the checklist, D5 schema mechanism, D6 integration story, D7 capability tag set) are detailed there; the canonical option matrices live in [`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md).

Output of Phase 1: a single dated decision record committed to the port's own repo.

## Phase 2 — walk the spec corpus

With Phase 1 locked, walk [`references/phase-2-impl-order.md`](references/phase-2-impl-order.md) EP-by-EP. The leaf carries, for each EP: what to read first, the contract to expose, how the CLJS reference realised it (as **one** example, not normative), what the conformance fixtures check, common spec-gap traps.

Dependency order is fixed: **EP 001 Registration → 002 Frames → 006 Reactive substrate → 004 Views → 009 Instrumentation → 015 Data Classification**, then a first conformance pass against the `:core/*` fixtures (the full pass is engineer-owned, or run by the agent when asked — see [`references/conformance.md`](references/conformance.md); the agent does run a per-EP slice gate it can determine from the port's own scripts before calling each EP landed, but never drives the engineer's full toolchain unbidden — L3). **Spec 015 is v1-required, not optional** — it overlays the 009 emission boundary (and the MCP wire / Xray / log-sink consumers) with path-marked data classification, so it lands in the foundation right after 009. Optional EPs (010 Schemas, 008 Testing, 005 State machines, 012 Routing, 011 SSR, 013 Flows, 014 HTTP, 007 Stories, 016 Resources) follow in the order Phase 1 declared `yes` for them — each gated by its D3 question (Q1 machines, Q2 routing, Q3 SSR, Q4 schemas, Q5 stories, Q8 Flows, Q9 HTTP, Q10 Resources; Q6 Tool-Pair and Q7 AI-Audit gate the non-EP surfaces). (Resources is post-v1 and rides on Q9 Managed HTTP — its transport lowers onto `:rf.http/managed`.)

## Source discipline

Three tiers, in priority order:

1. **[`spec/`](../../spec/)** — the contract. Read in numeric order.
2. **[`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md)** — the decision-ordered companion.
3. **`implementation/`** — a worked example. Useful for "how did *someone* solve X?" Never useful as a contract claim.

If `implementation/` and `spec/` disagree, the spec wins.

## Conformance

The corpus at [`spec/conformance/`](../../spec/conformance/) is host-agnostic data. Harness shape, the EDN-handler-body DSL, capability tagging, scoring, and the spec-gap-vs-implementation-bug distinction are all in [`references/conformance.md`](references/conformance.md). The corpus is the acceptance test for [Goal 2 — AI-implementable from the spec alone](https://day8.github.io/re-frame2/spec/000-Vision/#ai-implementable-from-the-spec-alone).

## Kickoff and output

- [`references/kickoff-prompt.md`](references/kickoff-prompt.md) — paste-ready prompt for the engineer to drop into a fresh Claude session opened in the root of their port repo.
- [`references/output-format.md`](references/output-format.md) — the standard agent-output shape: implementation summary, capability tags claimed, conformance score, decisions made, spec gaps filed.

## Done — "v1-complete against `<capability tag set>`"

- [ ] Phase 1 decision record committed to the port's repo.
- [ ] **The shared path + canonical-identity foundation is implemented** (`:rf/path` algebra + CEDN-1, EP-0012), with no subsystem keeping private overlap / canonicalization / round-trip logic. The full conformance checklist (root-path law, symmetric `overlap?`, `[:rf.path/param …]` templates, fail-closed identity, scoped resource keys, canonical route emission) is [`references/phase-2-impl-order.md` §The shared path + identity foundation](references/phase-2-impl-order.md#the-shared-path--identity-foundation-ep-0012).
- [ ] All in-scope EPs have a working implementation.
- [ ] **Spec 015 Data Classification is implemented** (v1-required): durable app-db classification is **frame-owned** — `reg-frame` carries `:sensitive {:app-db […] :http {…}}` / `:large {:app-db […]}` (installed atomically at frame creation; re-registration REPLACES); owner-local schema'd data classifies via per-slot `:sensitive?` / `:large?` props on the owning schema — machine `:data` is the **schema-first exception** (`reg-machine` is `(machine-id machine-spec)` / `(machine-id opts machine-spec)`, the optional `opts` carrying an event-vector `:schema` and NO top-level `:sensitive` / `:large` keys; `:data` marks ride the `:data-schema`), plus resource `:data-schema` / `:params-schema` and HTTP `:decode` bodies; transient payloads classify via `{:sensitive [paths] :large [paths]}` on `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow` (the public registrars; `reg-event` is the **one** public event registrar — EP-0018 — so there is no `reg-event-db/-fx/-ctx` classification split to mirror; any internal effect-shape classifier the port keeps is private, not a public surface). Classification propagates across the dataflow; every off-box record routes through the public `project-egress` boundary primitive (six closed `:rf.egress/*` profiles), which substitutes the Spec 009 wire markers `:rf/redacted` (sensitive) / `:rf.size/large-elided` (large) — `:rf/large {:bytes N :head}` is the Spec 015 *display* rendering layered on top, not the wire shape. Production sinks register with `register-observability-sink!` and consume already-projected records; routing/projection are fail-closed (no `:rf/default` synthesis). No classified value leaks past the trust boundary. (`add-marks` / `set-marks` / `redact-interceptor` / `declare-sensitive-*` are removed from the public façade — EP-0015.)
- [ ] The port exposes [`spec/API.md`](../../spec/API.md), adapted to host idiom.
- [ ] **The composition + installation substrate is built around `image → frame → event stream` (EP-0023).** The **public** composition model is exactly that: a feature namespace registers ordinary `reg-*` forms; an **`rf/image`** selects them by `:include-ns` provenance and is supplied to **`rf/make-frame`** (via `:images`); **`rf/reload-images!`** hot-reloads a frame's image generation in place (preserving frame memory); a frame is addressed by its **process-local frame id** (or a frame value for tests/tools — read its id with `frame-value->id`), with **no realm coordinate**. There is exactly **one default realm** as the backing installation container — the registrar-seating seam, adapter selection, and the `installed-app` projection are the narrowed internal substrate frame resolution routes through; none of it is public `re-frame.core` vocabulary. The **EP-0013 multi-realm substrate was found to have no consumer and collapsed** (EP-0023 addendum): non-default realm construction / routing / query / lifecycle, `dispose-realm!`, `realm-ids`, `frame-realm`, the realm `install!` / `reinstall!` seating path and its host-transient inventory hatch, and multi-adapter-by-N-realms are **retired** — do NOT build or conformance-check them. (A residual realm-*addressing* vestige still threads the single-default-realm frame model and is scheduled for collapse — see the EP-0023 *Addendum — multi-realm substrate retired* in [`docs/EP/EP-0023-image-loaded-frames.md`](../../docs/EP/EP-0023-image-loaded-frames.md).) See [`references/phase-2-impl-order.md` §Installation substrate](references/phase-2-impl-order.md#installation-substrate-image--frame--event-stream-ep-0023).
- [ ] Conformance score is `(claimed-applicable) / (claimed-applicable)`.
- [ ] Non-spec-gap failures fixed in the port; spec-gap failures filed as GitHub issues against `day8/re-frame2`.
- [ ] Port's README states claimed capability tags and conformance score.

## Reference files (all one level deep)

- [`references/cardinal-rules.md`](references/cardinal-rules.md) — the eleven rules in prose + anti-pattern corollaries.
- [`references/phase-1-decisions.md`](references/phase-1-decisions.md) — Phase 1 walkthrough, seven decision blocks.
- [`references/decision-record.md`](references/decision-record.md) — fill-in template for the locked-decision record.
- [`references/phase-2-impl-order.md`](references/phase-2-impl-order.md) — EP-by-EP implementation order.
- [`references/reference-impl-tour.md`](references/reference-impl-tour.md) — guided tour of the CLJS reference; what's substrate-specific vs pattern-required.
- [`references/conformance.md`](references/conformance.md) — corpus harness, DSL, capability tagging, scoring.
- [`references/kickoff-prompt.md`](references/kickoff-prompt.md) — fresh-session kickoff prompt.
- [`references/output-format.md`](references/output-format.md) — agent-output shape.

---

*Authoritative contract: [`spec/`](../../spec/). Decision companion: [`spec/Implementor-Checklist.md`](../../spec/Implementor-Checklist.md). Conformance: [`spec/conformance/`](../../spec/conformance/). CLJS reference (worked example): `implementation/`. Full skill-disambiguation matrix: [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
