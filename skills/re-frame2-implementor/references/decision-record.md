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

## Spec pin (load-bearing — record before D1)

- **Upstream:** `https://github.com/day8/re-frame2`
- **Pinned commit / tag:** <SHA-or-tag>
- **Pin verified:** `git -C <path-to-re-frame2> rev-parse HEAD` == `<SHA-or-tag>` on <YYYY-MM-DD>
- **Origin verified:** `git -C <path-to-re-frame2> remote get-url origin` == `https://github.com/day8/re-frame2(.git)`

Every spec citation in this record (and in subsequent code) is against the pinned hash. If the engineer later pulls a newer `day8/re-frame2` HEAD, that's a deliberate retarget event — add a Revision log entry and re-walk the affected decisions.

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

## D4. Always-required realisation decisions

> Sub-ids mirror Implementor-Checklist Part 2 1:1 — Foundation F1–F6, State storage S1–S3, Subscriptions Sub1–Sub2, Views V1–V3, Tracing T1–T3, Errors E1–E2. Fill in every block; all are always required (T2's bridge may be omitted — record the choice).

### Foundation (F1–F6)

#### F1 Identity primitive

- **Mechanism:** <e.g. "branded string types with naming convention" / "polymorphic variants" / "sealed-class hierarchies">
- **Required properties verified:** stable / namespaceable / value-equal / cheap / serialisable / human-readable / reflective — <confirm all seven, name the helper(s) that provide each>

#### F2 Persistent data structures

- **Library:** <e.g. "Immer" / "Immutable.js" / "native Clojure persistent collections" / "native F# Map/Set">
- **Snapshot mechanism:** <e.g. "pointer swap" / "Immer's `produce` drafts" / "deep-copy fallback for X cases">

#### F3 Reactive substrate

- (Locked in D2.)

#### F4 Effect-handling primitive

- **Sync default:** <yes — fx run inline when the handler returns / no — different model>
- **Async re-entry:** <e.g. "Promise.then → :dispatch" / "queueMicrotask → schedule dispatch">
- **Standard fx the port ships:** <e.g. ":dispatch / :dispatch-later / :http">

#### F5 Concurrency model

- **Model:** <e.g. "single-threaded JS event loop (browser / Node main thread)">
- **Cross-frame serialisation:** <how dispatch is serialised per frame in multi-frame setups>
- **No core.async confirmation:** <confirm the directive — no channels in the public dispatch contract>

#### F6 Hot-reload primitive

- **Mechanism:** <e.g. "Vite HMR module boundary at reg-* call sites" / "figwheel/shadow-cljs reload">
- **State-preservation contract:** <how frame state survives re-registration of `reg-frame`>

### State storage (S1–S3)

#### S1 App-db container

- **Container:** <e.g. "Reagent ratom" / "useSyncExternalStore-backed atom store" / "MutableStateFlow-shaped cell">
- **Revertibility check:** <confirm no non-derivable adapter state lives outside the container>

#### S2 Snapshot/restore mechanism

- **Mechanism:** <e.g. "pointer swap (persistent collections)" / "value capture + replace-container!"> — depends on F2.

#### S3 Path-access primitive

- **Mechanism:** <e.g. "native assoc-in/update-in/get-in" / "Immer produce + lodash.get" / "lens helpers over Belt.Map">

### Subscriptions (Sub1–Sub2)

#### Sub1 Signal graph + caching

- **Graph backing:** <e.g. "Reagent reactions" / "Solid createMemo" / "hand-rolled signal DAG">
- **Cache key:** <confirm `=`-by-value equality for invalidation; identity-only equality is out>

#### Sub2 Lifecycle (when to dispose)

- **Disposal policy:** <e.g. "last-deref-disposes after a delay (Reagent)" / "explicit subscribe/unsubscribe ref-count">

### Views (V1–V3)

#### V1 Render-tree shape

- **Shape:** <e.g. "hiccup" / "JSX-as-data / snabbdom vnodes" / "Feliz Html.div DSL"> — must serialise for SSR + tooling.

#### V2 Render trigger

- **Trigger:** <how a subscribed-value change re-renders the view> — falls out of F3.

#### V3 Mount/unmount

- **Lifecycle hooks:** <how mount fires `:on-create` / unmount fires `:on-destroy` on the surrounding frame>

### Tracing & instrumentation (T1–T3)

#### T1 Trace-event delivery

- **Registry shape:** <e.g. "single listener-registry atom + separate ring-buffer atom">
- **Ring buffer:** <retain-N for tools that attach after events fired; N = ?>

#### T2 Performance API equivalent

- **Bridge:** <ships the perf bridge (`performance.mark`/`measure`) / omits it — the bridge is optional; T1 is the contract>

#### T3 Production elision

- **Mechanism:** <e.g. "Closure DCE via debug-enabled?" / "Vite define + tree-shake" / "#if !DEBUG">
- **CI verifier:** <sentinel-string scan asserting dev-only strings absent from production bundles>

### Errors (E1–E2)

#### E1 Error capture / recover

- **Capture sites:** <try/catch around handler bodies / fx invocations / sub computations>
- **No-silent-swallow:** <confirm every catch fires `:operation :rf.error/<category>` + `:op-type :error`>

#### E2 Error reporting to tools

- **Policy slot:** <the per-frame `:on-error` slot in `reg-frame` metadata> — errors flow through the trace stream (T1).

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
