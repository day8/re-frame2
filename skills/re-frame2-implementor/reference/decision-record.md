# decision-record

A fill-in template for the **Phase 1 locked-decision record**. The engineer copies this template into their port's repo (typically as `DECISIONS.md` at the repo root), fills in each block before any implementation code is written, and commits it.

Every Phase 2 step references this record. If a Phase 2 step forces a Phase 1 decision to change, the record is **revised in writing** before Phase 2 resumes — no silent overrides.

---

## Template — copy from here

```markdown
# DECISIONS — <port name>

> Phase 1 decision record for the re-frame2 port at <port-repo-url>.
> Captured before Phase 2 implementation began.
> Locked: <YYYY-MM-DD>.

## D1. Target host language

- **Host:** <language + version, e.g. "TypeScript 5.4">
- **Runtime targets:** <e.g. "browser (Chrome 100+), Node 20+">
- **Build tool:** <e.g. "Vite + tsc">

## D2. Substrate / view layer

- **Substrate:** <e.g. "React 19 + VDOM" / "SwiftUI" / "raw DOM" / "terminal (textual)" / "none — server-only">
- **Reactive container library:** <e.g. "Solid signals" / "MobX" / "Vue refs" / "hand-rolled signals">
- **Render-tree shape:** <e.g. "JSX-as-data" / "hiccup-equivalent tuples" / "SwiftUI ViewBuilder" / "ANSI escape vnodes">

## D3. Scope — which EPs ship in v1

| EP | In v1? | Notes |
|---|---|---|
| **Required core** (000 / 001 / 002 / 004 / 006 / 009) | yes | non-negotiable |
| **Q1 — State machines (005)** | <yes / no> | <which sub-capabilities — flat / hierarchical / always / after / tags / parallel-regions> |
| **Q2 — Routing (012)** | <yes / no> | |
| **Q3 — SSR (011)** | <yes / no> | |
| **Q4 — Schemas (010)** | <yes-runtime-schema / yes-via-host-types / no> | <which library if runtime-schema> |
| **Q5 — Stories (007)** | <yes / no> | usually no for v1 |
| **Q6 — Tool-Pair adapters** | <yes / no> | |
| **Q7 — AI-Audit grading** | <yes / no> | |

## D4. Foundation choices

### D4.1 Identity primitive

- **Mechanism:** <e.g. "branded string types with naming convention" / "polymorphic variants" / "sealed-class hierarchies">
- **Required properties verified:** stable / namespaceable / value-equal / cheap / serialisable / human-readable / reflective — <confirm all seven, name the helper(s) that provide each>

### D4.2 Persistent data structures

- **Library:** <e.g. "Immer" / "im (Rust)" / "pyrsistent" / "native Clojure persistent collections">
- **Snapshot mechanism:** <e.g. "pointer swap" / "Immer's `produce` drafts" / "deep-copy fallback for X cases">

### D4.3 Reactive substrate

- (Locked in D2.)

### D4.4 Effect-handling primitive

- **Sync default:** <yes — fx run inline when the handler returns / no — different model>
- **Async re-entry:** <e.g. "Promise.then → :dispatch" / "asyncio task → schedule_dispatch" / "tokio spawn → mpsc sender">
- **Standard fx the port ships:** <e.g. ":dispatch / :dispatch-later / :http">

### D4.5 Concurrency model

- **Model:** <e.g. "single-threaded JS event loop" / "single-threaded asyncio" / "single-threaded tokio">
- **Cross-frame serialisation:** <how dispatch is serialised per frame in multi-frame setups>
- **No core.async confirmation:** <confirm the directive — no channels in the public dispatch contract>

### D4.6 Hot-reload primitive

- **Mechanism:** <e.g. "Vite HMR module boundary at reg-* call sites" / "watch + reimport via importlib.reload" / "compile-replace via dynamic library swap">
- **State-preservation contract:** <how frame state survives re-registration of `reg-frame`>

## D5. Schema mechanism

- **Answer:** <yes-runtime-schema / yes-via-host-types / no>
- **Library (if runtime-schema):** <e.g. "Zod" / "Pydantic" / "dry-rb">
- **Validation timing:** <e.g. "boundary-only, dev-build only, elided by Vite define" / "boundary-only, JIT-compiled to no-op in release">
- **Open-shape verification:** <how the port enforces open shapes — additive growth, unknown-key tolerance>

## D6. Integration story

- **Model:** <standalone library / framework integration / embedded>
- **Downstream consumer:** <name the framework / app / process the port plugs into>
- **Wiring boundary:** <where the consumer's app code meets the port — e.g. "React provider component" / "asyncio loop integration in main.py" / "library API only">

## D7. Conformance capability tag set

The set of capability tags this port claims:

```
:core/*           (always)
:fsm/flat         <yes / no>
:fsm/hierarchical <yes / no>
:fsm/eventless-always <yes / no>
:fsm/delayed-after <yes / no>
:fsm/tags         <yes / no>
:fsm/parallel-regions <yes / no>
:actor/own-state  <yes / no>
:actor/spawn-destroy <yes / no>
:actor/cross-actor-fx <yes / no>
:actor/invoke     <yes / no>
:actor/spawn-and-join <yes / no>
:actor/system-id  <yes / no>
:routing/*        <yes / no>
:ssr/*            <yes / no>
:schemas/*        <yes / no — pick yes if D5 ≠ no, regardless of mechanism>
```

Score reporting: this port's score is `passed / claimed-applicable` against the above set.

---

## Open questions parked for Phase 2

<For any decision that you can't lock without seeing the implementation play out, note it here. Better to mark a decision "deferred to Phase 2 step N" than to over-commit at lock time. Each deferred decision must be resolved before the matching Phase 2 step starts.>

- D<n> — <description> — *deferred until Phase 2 step <N: implement EP M>*

---

## Revision log

<Append-only. Each entry: date, the decision that changed, the Phase 2 step that surfaced the need, the new lock.>

- <YYYY-MM-DD> — initial lock.
```

---

## How to use the template

1. Copy the block between the `## Template — copy from here` heading and the `---` after it.
2. Paste into `DECISIONS.md` (or equivalent) at the root of the port's repo.
3. Fill in every `<...>` placeholder. Don't leave any blank — if you don't know, mark it "deferred to Phase 2 step N" in the *Open questions* section and call it out explicitly.
4. Commit. The record is now load-bearing.

## When to revise the record

Phase 2 will occasionally surface a foundation decision that won't survive contact with the implementation — typically because a host idiom doesn't fit the chosen mechanism cleanly. When this happens:

1. Stop the Phase 2 step that surfaced the issue.
2. Re-open `DECISIONS.md`.
3. Append to the *Revision log* with the date, the changed decision, and the Phase 2 step that surfaced it.
4. Update the decision body to the new lock.
5. Re-walk the affected portions of Phase 1's downstream decisions if they depended on the changed lock.
6. Resume Phase 2.

This costs ~30 minutes when caught early. Skipping the revise step and patching the code in flight costs days of cleanup later.

## Why the record matters

Three reasons:

1. **Phase 2 context.** Every Phase 2 step asks "given Phase 1, what does EP N look like in this port?" Without the record written down, the engineer (or their Claude session) re-derives the answer every time — and the answer can drift across sessions.
2. **Onboarding context.** Future contributors to the port read `DECISIONS.md` before reading code. The decisions are how the code makes sense.
3. **Conformance reporting.** The port's conformance score is *against the claimed capability set*. The claimed set lives in D7 of this record; the score has no meaning without it.

The record is the contract between Phase 1 and Phase 2.
