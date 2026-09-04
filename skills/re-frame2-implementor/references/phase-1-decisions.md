# phase-1-decisions — the port profile

Phase 1 produces one small file — the **port profile** — and nothing else. The profile records only what the implementor actually **chose**. Everything the spec fixes is a link to its owner, never a field to copy back and reconfirm.

## No interview by default

For an unqualified minimum-port request ("build a minimum re-frame2 port in TypeScript"):

- **Default every optional capability to no** — the checklist's nine numbered scope questions (below). Fixed v1-required obligations are not among them and are never defaulted: flows (Spec 013) in particular is a Required row, not a question.
- **Choose ordinary host-idiomatic mechanisms** where the host or repository context makes them clear (for TypeScript, say: Immer or Immutable.js for persistent data, a `useSyncExternalStore`-backed store, the repo's existing build tool and test runner).
- **Ask only about a missing choice that materially changes the implementation** — never a question per decision block or per optional EP.
- Record the profile and **begin the first implementation/verification slice in the same run** ([`phase-2-impl-order.md` §Step 0](phase-2-impl-order.md#step-0--bootstrap-the-feedback-seam)).

## Spec pin (record first)

The contract is `spec/` at a **specific commit**, verified before anything is read (cardinal rule 1):

```bash
git -C <path-to-re-frame2> remote get-url origin   # expect: https://github.com/day8/re-frame2(.git)
git -C <path-to-re-frame2> rev-parse HEAD          # expect: the chosen <SHA-or-tag>
```

Record the pin **and the checkout path** in the profile: every contract read in both phases — EP owners, fixed obligations, the conformance README, the fixture corpus — resolves through that verified checkout at the pin (`<path-to-re-frame2>/spec/…`, cardinal rule 1). The `day8.github.io` links in this skill are citations of live main for browsing, never the reading route. Retargeting to a newer upstream HEAD later is a deliberate event: update the pin line, re-derive the capability claim at the new pin, and re-run the harness.

## The profile

It lives in the port's repo (as `PORT-PROFILE.md`, or a section of the README). Persistence is a currentness concern, not a pre-code gate: the done checklist expects the profile committed and current, but no commit has to land before implementation starts. Shape, not ceremony — no fill-every-placeholder rule, no per-session log, no commit-hash chain:

```markdown
# Port profile — <port name>

- Spec pin: day8/re-frame2 @ <SHA-or-tag>, read from the verified checkout at <path-to-re-frame2> (origin + pin verified <YYYY-MM-DD>)
- Host: <language + version; runtime targets; build tool; test runner>
- Mechanisms (actual choices only):
  - identity primitive: <e.g. branded strings with interning>
  - persistent data: <e.g. Immer>
  - React binding + reactive container: <e.g. React 19 + a useSyncExternalStore-backed store>
  - render-tree shape: <e.g. JSX-as-data vnodes>
  - schema mechanism: <yes-runtime-schema <lib> / yes-via-host-types / no>
  - hot-reload: <e.g. Vite HMR boundary at reg-* call sites>
  - integration story: <standalone, unless a concrete consumer says otherwise>
- Capability claim (derived at the pin — see conformance.md §Capability tagging):
  - claimed: :core/* :identity/* :flow/* :data-classification/* <+ any optional families claimed>
  - known-skipped: {<capability> "<why unclaimed>"} …   ; optional capabilities only — never a v1-required family
  - fixture-less self-tested: [<spec-mandated capabilities with no corpus fixture yet>]
- Score: <passed> / <claimed-applicable> @ corpus <pin>
```

The claim and score lines change as the port grows; edit them in place.

## What is NOT in the profile

Fixed obligations — decided by the spec, not selected by an implementor. Read them at their owners — from the verified checkout at the pin, per cardinal rule 1 (the links below are citations); do not transcribe them into fields:

- React + VDOM substrate and the eight-host scope — [`spec/000-Vision.md` §The pattern + scope footnote](https://day8.github.io/re-frame2/spec/000-Vision/#the-pattern-js-cross-compile-language-agnostic).
- The JS event loop, no core.async, run-to-completion drain — [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) (cardinal rule 5).
- One frame-state container, two partitions, projection-equality invalidation — [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) and [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/).
- Closed effect-map shape + runtime shape policing — [`spec/Implementor-Checklist.md` §Required](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#required-not-gated-every-implementation-ships-these).
- Structured errors + the always-on emit substrates — [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/).
- Data classification and the `project-egress` boundary (v1-required) — [`spec/015-Data-Classification.md`](https://day8.github.io/re-frame2/spec/015-Data-Classification/).
- Flows — registered, runtime-toggleable computed-state declarations materialising into `app-db` (v1-required) — [`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/). **Not a scope choice.** Spec 013's restraint (flows are a narrow convenience, not a second dataflow paradigm — when in doubt, use a subscription) scopes how much flow API to build, never whether to build it: a minimum port ships the minimum claim stated in that Spec's opening block and no more.
- One path algebra + canonical identity, CEDN-1 (v1-required) — [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) (cardinal rule 11).
- JVM-runnability of pure transitions / sub computations — cardinal rule 6.

## The choices (the option matrices live in the checklist)

- **Host + toolchain.** One of the eight in-scope hosts per the [scope footnote](https://day8.github.io/re-frame2/spec/000-Vision/#the-pattern-js-cross-compile-language-agnostic); outside the eight, surface the footnote and stop. [`spec/Implementor-Checklist.md` Part 2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-2--how-achieved) carries options-by-host for every mechanism below — read them there.
- **Mechanisms.** Identity primitive (F1), persistent data (F2), React binding + reactive container (F3), render-tree shape (V1), hot-reload (F6), schema mechanism (three answers, not two: *yes-runtime-schema* / *yes-via-host-types* / *no* — [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/)). The checklist's remaining Part 2 blocks (S1–S3, Sub1–Sub2, V2–V3, T1–T3, E1–E2) are realised during the owning EP's slice — add a profile line only where the host offers a real alternative.
- **Scope.** The checklist's nine numbered questions — Q1 machines (005) / Q2 routing (012) / Q3 SSR (011) / Q4 schemas (010) / Q5 stories (007) / Q6 Tool-Pair / Q7 AI-Audit / Q8 managed HTTP (014) / Q9 resources (016, implies Q8) — for which [`spec/Implementor-Checklist.md` Part 1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-1--how-complete) is the canonical table. Those nine are the whole of the optional surface: default **no**, claim yes only for a concrete consumer. **Flows is not among them** — it has no Q-number because Spec 013 is v1-required and sits in the checklist's Required table, so it is a fixed obligation above, not a decision here.
- **Capability claim.** Derived from the fixtures at the pin. The derivation procedure, the family → scope-decision map, and the corpus/spec divergence rule are owned once by [`conformance.md` §Capability tagging](conformance.md#capability-tagging) — record only the result.

## Revising

A Phase 2 slice that overturns a choice: stop the slice, edit the profile line (a dated note suffices), resume. No revision-log schema.
