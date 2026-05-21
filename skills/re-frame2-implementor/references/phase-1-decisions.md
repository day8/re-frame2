# phase-1-decisions

The Phase 1 walkthrough. Before writing any implementation code, the engineer walks each decision block below and captures the answer in the **decision record** (template at `decision-record.md`).

Every decision in Phase 1 propagates through every line of Phase 2 code. Spending one focused session locking the decisions saves weeks of rewrites later.

## Contents

- Spec pin (load-bearing preamble — record before D1)
- D1. Target host language
- D2. Substrate / view layer
- D3. Scope — which EPs ship now
- D4. Always-required realisation decisions (the checklist's Part 2 always-required blocks: Foundation F1–F6, State storage S1–S3, Subscriptions Sub1–Sub2, Views V1–V3, Tracing T1–T3, Errors E1–E2)
- D5. Schema mechanism
- D6. Integration story
- D7. Conformance capability tag set

For each block: the question, what's at stake, options, how to choose, where the spec speaks to it.

**Id scheme — this skill mirrors the checklist's.** D4 below is sub-numbered with the **exact** Part 2 ids from [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) — F1–F6, S1–S3, Sub1–Sub2, V1–V3, T1–T3, E1–E2 — so the decision record cross-walks 1:1 with the checklist. (Earlier drafts numbered these D4.1–D4.6 and silently dropped the State storage / extra Views / extra Tracing blocks; the ids and the missing blocks are now reconciled to the spec.)

---

## Spec pin (preamble — record before D1)

**The question.** Which `day8/re-frame2` commit or tag is the contract for this port?

**What's at stake.** Every spec citation in this record (and in subsequent code) is against the pinned hash. A floating HEAD is not a contract — it's whatever happens to be on the filesystem the moment the agent reads it. Pinning makes the contract reproducible and pins the conformance score to a known corpus state.

**How to choose.** Pick the latest stable tag, or the HEAD the engineer cloned at kickoff. Either way, record the specific SHA.

**Verify before reading.** Before reading any file under `<path-to-re-frame2>/spec/`:

```bash
# Origin check — confirm the checkout is the real day8/re-frame2 repo
git -C <path-to-re-frame2> remote get-url origin
# expect: https://github.com/day8/re-frame2(.git) or git@github.com:day8/re-frame2(.git)

# Pin check — confirm HEAD matches the chosen pin
git -C <path-to-re-frame2> rev-parse HEAD
# expect: <SHA-or-tag>
```

Record the pinned SHA, the verification date, and both confirmations in the `Spec pin` block of `DECISIONS.md` (template at [`decision-record.md`](decision-record.md)).

**Retarget event.** If the engineer later pulls a newer `day8/re-frame2` HEAD, that's a deliberate retarget: append a Revision log entry to `DECISIONS.md` naming the new pin, and re-walk the affected decisions.

---

## D1. Target host language

**The question.** Which host language and runtime does the port target?

**What's at stake.** Every Phase 1 sub-decision (identity primitive, persistent data structures, concurrency model, render-tree shape) is constrained by what the host provides. The choice of host fixes the "shape of the space" the port operates in.

**Options.** [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) names eight first-class **JS-cross-compile + React+VDOM** hosts:

- ClojureScript (the reference)
- TypeScript / JavaScript
- Squint
- Melange / ReScript / Reason
- Fable (F#)
- Scala.js
- PureScript
- Kotlin/JS

The [Implementor-Checklist](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) covers more (Python, Rust, Kotlin/native, Swift, Java), but ports outside the eight above will not target React+VDOM by default — D2 picks an alternative substrate.

**How to choose.** Usually pre-decided by why the engineer started the port. Capture the host and the runtime (e.g. "TypeScript targeting browser + Node 20", "Fable F# targeting .NET 9 and browser", "Rust targeting tokio").

**Where the spec speaks.** [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) §"The pattern (JS-cross-compile-language-agnostic)" and the host-profile matrix. [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) Part 2 enumerates options per host for every foundation decision.

---

## D2. Substrate / view layer

**The question.** What renders the view? What is the reactive container that holds `app-db`?

**What's at stake.** The reactive substrate decision propagates into EP 006 (the adapter contract), EP 004 (the render-tree shape), and the view-rerender trigger.

**Options.** The default for the eight in-scope hosts is **React + VDOM** (per [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) §scope footnote). Other substrates a port may target:

- React + VDOM (the spec's default; the CLJS reference uses Reagent atop React)
- A native UI toolkit (SwiftUI, Compose, Qt, GTK)
- Raw DOM (no virtual-DOM library)
- Terminal UI (TUI)
- Server-render only (no client view layer)
- No UI at all (a re-frame2 runtime used as a state-management substrate for a non-UI process)

**How to choose.** Driven by the engineer's deployment target. If targeting one of the eight JS-cross-compile hosts and the deployment is browser/React-Native, React+VDOM is the default. Otherwise pick the substrate that fits the target.

**Trade-offs.** Substrates without auto-tracked reactivity (raw DOM, terminal) require an explicit subscription-binding mechanism that's outside the CLJS reference's shape — the engineer writes more of the wiring by hand. The contract is still six required + two optional + one lifecycle function from EP 006.

**Where the spec speaks.** [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/) — the adapter contract. [Implementor-Checklist §F3 Reactive substrate](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f3-reactive-substrate) — options per host.

---

## D3. Scope — which EPs ship now

**The question.** Which optional EPs does this port include in v1?

**What's at stake.** The required core is non-negotiable. Optional EPs are declared yes/no per the [Implementor-Checklist Part 1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-1--how-complete). Each "yes" gates a substantial chunk of additional implementation work.

**Required (every port ships these).** Identity primitive, persistent data structures, registry by `(kind, id)`, event handler contract, closed effect-map shape, subscription system, frame as runtime boundary, run-to-completion drain, view contract, trace event stream, error contract, conformance corpus consumption. Per [Implementor-Checklist Part 1 §Required](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#required-not-gated-every-implementation-ships-these).

**Optional (declare yes/no for each).**

- **Q1 — State machines** ([EP 005](https://day8.github.io/re-frame2/spec/005-StateMachines/)). Substantial. The CLJS reference claims hierarchical compound + `:always` + `:after` + `:fsm/tags` + `:fsm/parallel-regions` + own-state + spawn/destroy + cross-actor `:fx` + declarative `:spawn` + spawn-and-join + `:system-id`. Smaller ports claim less.
- **Q2 — Routing** ([EP 012](https://day8.github.io/re-frame2/spec/012-Routing/)). `reg-route`, `match-url`, navigation tokens.
- **Q3 — SSR** ([EP 011](https://day8.github.io/re-frame2/spec/011-SSR/)). `:platforms` metadata, `render-to-string`, hydration-mismatch detection.
- **Q4 — Schemas** ([EP 010](https://day8.github.io/re-frame2/spec/010-Schemas/)). Three answers, not two: *yes-runtime-schema*, *yes-via-host-types*, *no*.
- **Q5 — Stories** ([EP 007](https://day8.github.io/re-frame2/spec/007-Stories/)). Storybook/devcards-class tooling. Post-v1 in the CLJS reference too.
- **Q6 — Tool-Pair adapters** ([Tool-Pair.md](https://day8.github.io/re-frame2/spec/Tool-Pair/)). REPL-attached AI inspection surface.
- **Q7 — AI-Audit grading** ([AI-Audit.md](https://day8.github.io/re-frame2/spec/AI-Audit/)). Self-grading discipline doc.

**How to choose.** Default to "no" on every optional capability unless the engineer has a concrete consumer that needs it. Smaller v1 surface = faster ship + earlier feedback. Add capabilities post-v1 when consumers ask.

**Where the spec speaks.** [Implementor-Checklist Part 1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-1--how-complete) — the canonical decision table. [Host-profile matrix in 000](https://day8.github.io/re-frame2/spec/000-Vision/#host-profile-matrix) — which capabilities are pattern-required vs CLJS-only vs host-discretion.

---

## D4. Always-required realisation decisions

The checklist's Part 2 splits the always-required realisation decisions into six groups: **Foundation (F1–F6)**, **State storage (S1–S3)**, **Subscriptions (Sub1–Sub2)**, **Views (V1–V3)**, **Tracing & instrumentation (T1–T3)**, and **Errors (E1–E2)**. Every block below is **always required** (T2 Performance API is required-but-may-omit-the-bridge; see its note) and each propagates through Phase 2 code. The sub-ids match [Implementor-Checklist Part 2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-2--how-achieved) exactly.

### Foundation (F1–F6)

From [Implementor-Checklist §Foundation](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#foundation-always-required).

#### F1 Identity primitive

**The question.** What represents an id?

**What's at stake.** Every queryable, override, trace event, and error category is identified by an id. The runtime looks up, compares, ships, and reflects on ids cheaply.

**Required properties.** Stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective. Per [`spec/000-Vision.md` §The identity primitive](https://day8.github.io/re-frame2/spec/000-Vision/#the-identity-primitive--required-properties).

**Options.** CLJS keywords (the reference); TS branded strings with interning; Fable polymorphic variants or single-case DUs; Kotlin sealed-class hierarchies or value classes; PureScript newtypes; Scala.js sealed objects or value classes; Python wrapped strings; Rust newtype `Id(&'static str)`.

**Rejected upfront.** UUIDs, integer ids, Java reference-equality classes — all violate one or more required properties.

**Where the spec speaks.** [`spec/000-Vision.md` §The identity primitive — required properties](https://day8.github.io/re-frame2/spec/000-Vision/#the-identity-primitive--required-properties) and the per-host realisation table.

#### F2 Persistent data structures

**The question.** What does `app-db` (and every snapshot of it) physically live in?

**What's at stake.** Frame state revertibility ([Goal 3 in 000](https://day8.github.io/re-frame2/spec/000-Vision/#frame-state-revertibility)) requires structural sharing. Without persistent structures, snapshot is deep-copy and revert is expensive.

**Options.** Clojure persistent collections (CLJS, Squint); Immer / mori / Immutable.js (JS/TS); pyrsistent (Python); im (Rust); native persistent collections in Fable, PureScript, Scala.js; im.kt or kotlinx.collections.immutable (Kotlin); Swift's COW value types.

**Where the spec speaks.** [Implementor-Checklist §F2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f2-persistent-data-structures).

#### F3 Reactive substrate

Already locked in D2 (the substrate / view-layer choice). Recorded here for cross-walk completeness with the checklist; no separate answer needed.

#### F4 Effect-handling primitive

**The question.** How does the runtime invoke registered effects? Sync vs async?

**Options.** Sync-by-default registered handlers; async effects schedule via host's promise/timeout primitive and re-enter via `:dispatch` after their side effect completes.

**Constraint.** Async effects must NOT escape the run-to-completion drain. Per [`spec/002-Frames.md` §Run-to-completion](https://day8.github.io/re-frame2/spec/002-Frames/).

**Where the spec speaks.** [Implementor-Checklist §F4](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f4-effect-handling-primitive).

#### F5 Concurrency model

**The question.** Single-threaded event loop vs multi-threaded vs actor-shaped?

**Constraint.** **No core.async.** Per the standing directive — the CLJS reference does not use core.async, ports inherit the directive. Async fx schedule via host primitives. Cross-frame dispatch is serialised per frame.

**Options.** Single-threaded JS event loop (CLJS / JS / TS); single-threaded `asyncio` loop (Python); single-threaded `tokio` executor (Rust); single coroutine context per frame (Kotlin). Multi-threaded ports must serialise dispatch per frame.

**Where the spec speaks.** [`spec/002-Frames.md` §Run-to-completion dispatch drain semantics](https://day8.github.io/re-frame2/spec/002-Frames/). [Implementor-Checklist §F5](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f5-concurrency-model).

#### F6 Hot-reload primitive

**The question.** How does re-registration surgically replace registry entries without restarting the runtime?

**Options.** figwheel/shadow-cljs (CLJS); Vite HMR (JS/TS) — and the analogous Vite-HMR-compatible source-build pipeline for each in-scope host (Squint, Fable, Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason).

**Constraint.** Re-registration emits `:rf.registry/handler-replaced` per [`spec/001-Registration.md` §Hot-reload semantics](https://day8.github.io/re-frame2/spec/001-Registration/). Frame state preserved across re-registration of `reg-frame`.

**Where the spec speaks.** [Implementor-Checklist §F6](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f6-hot-reload-primitive).

### State storage (S1–S3)

From [Implementor-Checklist §State storage](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#state-storage-always-required). All three are always required.

#### S1 App-db container

**The question.** What container physically holds the frame's `app-db` value?

**What's at stake.** The container's *value* is the frame's `app-db`; all reads/writes go through `read-container` / `replace-container!`. The container's value is what's restored on revert; its identity is stable.

**Options.** Usually the same library that supplies F3's reactive substrate (Reagent ratom / `clojure.core/atom` in the CLJS reference; a `useSyncExternalStore`-backed atom-shaped store, a signal-library cell, or a `MutableStateFlow`-shaped cell per host).

**Constraint.** Adapters MUST NOT hold non-derivable state outside the container (per [`spec/006-ReactiveSubstrate.md` §Revertibility constraints on adapters](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#revertibility-constraints-on-adapters)).

**Where the spec speaks.** [Implementor-Checklist §S1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#s1-app-db-container).

#### S2 Snapshot/restore mechanism

**The question.** How is full-frame-state captured and restored as a value swap?

**What's at stake.** Test fixtures, epoch history (`epoch/restore-epoch` + `epoch/reset-frame-db!`), and time-travel all depend on snapshot/restore being a value swap. With persistent collections (F2) a snapshot is a pointer and restore is `replace-container!`; without them, snapshot is deep-copy and expensive — this is why F2 is pattern-required.

**Where the spec speaks.** [Implementor-Checklist §S2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#s2-snapshotrestore-mechanism).

#### S3 Path-access primitive

**The question.** What provides `assoc-in` / `update-in` / `get-in` over the frame's app-db?

**What's at stake.** Used by handlers, the `path` standard interceptor, registered subs that read paths, and `(rf/snapshot-of path)`. Path operations are hot — choose a fast implementation.

**Options.** Native `assoc-in` / `update-in` / `get-in` (CLJS, Squint); Immer `produce` / `lodash.set`-immutably (TS); lens helpers over the host's immutable map (Belt / F# `Map` / Monocle / `purescript-profunctor-lenses` / Arrow Optics) per host.

**Where the spec speaks.** [Implementor-Checklist §S3](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#s3-path-access-primitive).

### Subscriptions (Sub1–Sub2)

From [Implementor-Checklist §Subscriptions](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#subscriptions-always-required). Both fall out of F3 + S1, but record them explicitly.

#### Sub1 Signal graph + caching

**The question.** What backs the subscription DAG, and how is the per-query cache keyed?

**What's at stake.** Subscriptions form a DAG over `app-db`; values cache per `=`-equality. Layer-1 subs read `app-db` directly; layer-2+ compose via `:<-`. **Equality-by-value is required** for cache invalidation — identity-only equality breaks the contract.

**Where the spec speaks.** [Implementor-Checklist §Sub1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#sub1-signal-graph--caching). [`spec/006-ReactiveSubstrate.md` §Subscription cache](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#subscription-cache--contract-and-operational-semantics).

#### Sub2 Lifecycle (when to dispose)

**The question.** When is a sub no view is reading torn down?

**What's at stake.** Subs unread by any view should dispose to release resources; the mechanism varies by reactive substrate (Reagent: last-deref-disposes after a delay). Pick a policy and document it.

**Where the spec speaks.** [Implementor-Checklist §Sub2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#sub2-lifecycle-when-to-dispose).

### Views (V1–V3)

From [Implementor-Checklist §Views](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#views-always-required). All three always required.

#### V1 Render-tree shape

**The question.** What is the serialisable data form of the render-tree?

**What's at stake.** Pure `(state, props) → render-tree`; the render-tree must be serialisable data for SSR (011) and inspectable for view-tree tooling. Closed component trees that don't serialise (raw React elements with closures) break SSR + inspection.

**Options.** Hiccup (CLJS, Squint); JSX-as-data / snabbdom-style vnodes (TS); Feliz `Html.div [...]` (Fable); `R.div [] [...]` (PureScript); `<.div(...)` (Scala.js); `div { ... }` (Kotlin/JS) — every in-scope host targets React + VDOM, so the shape is the host's idiomatic data-form over `createElement`.

**Where the spec speaks.** [Implementor-Checklist §V1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#v1-render-tree-shape). [`spec/004-Views.md`](https://day8.github.io/re-frame2/spec/004-Views/).

#### V2 Render trigger

**The question.** When does the view re-render?

**What's at stake.** Falls out of F3; signal libraries trigger re-render on subscribed-value change. The trigger must be observably equivalent to "change in `app-db` → recompute affected subs → re-render dependent views".

**Where the spec speaks.** [Implementor-Checklist §V2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#v2-render-trigger).

#### V3 Mount/unmount

**The question.** How do component lifecycle hooks fire frame events?

**What's at stake.** Lifecycle should fire `:on-create` (mount-time) and `:on-destroy` (unmount-time) events on the surrounding frame, integrating with the run-to-completion drain.

**Where the spec speaks.** [Implementor-Checklist §V3](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#v3-mountunmount).

### Tracing & instrumentation (T1–T3)

From [Implementor-Checklist §Tracing & instrumentation](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#tracing--instrumentation-always-required).

#### T1 Trace-event delivery

**The question.** What delivers each emitted trace map to every registered listener?

**What's at stake.** Synchronous, in-order, event-at-a-time delivery on the runtime's emit call stack, plus a retain-N ring buffer for tools that attach after events have fired. Listener-invocation **order is not** contract; "every listener, exactly once, synchronously" is. Hot path — listener invocation must short-circuit when no listeners are registered.

**Where the spec speaks.** [Implementor-Checklist §T1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#t1-trace-event-delivery). [`spec/009-Instrumentation.md` §Listener invocation rules](https://day8.github.io/re-frame2/spec/009-Instrumentation/#listener-invocation-rules).

#### T2 Performance API equivalent

**The question.** Does the port bridge to a profiling/perf-marking API?

**What's at stake.** The CLJS reference ships a Chrome Performance API bridge (`performance.mark` / `performance.measure`) for DevTools cross-correlation. Every in-scope host targets the browser, so the Performance API is uniformly available; the **bridge itself is optional** — the underlying trace surface (T1) is the contract. Record explicitly whether the port ships the bridge or omits it.

**Where the spec speaks.** [Implementor-Checklist §T2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#t2-performance-api-equivalent). [`spec/009-Instrumentation.md` §Performance instrumentation](https://day8.github.io/re-frame2/spec/009-Instrumentation/#performance-instrumentation).

#### T3 Production elision

**The question.** How does every emit site, the listener registry, the trace buffer, and the perf bridge elide in production?

**What's at stake.** All tracing is dev-only; production builds must elide it entirely. Mechanism is host-discretion (Closure DCE for CLJS via `re-frame.interop/debug-enabled?`; Vite `define` + tree-shake for TS/Squint; `#if !DEBUG` + tree-shake for Fable; link-time-`if` for Scala.js; release-variant module omission for Kotlin/JS). Copy the CLJS reference's CI sentinel-string verifier pattern.

**Where the spec speaks.** [Implementor-Checklist §T3](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#t3-production-elision). [`spec/009-Instrumentation.md` §Production builds](https://day8.github.io/re-frame2/spec/009-Instrumentation/#production-builds-zero-overhead-zero-code).

### Errors (E1–E2)

From [Implementor-Checklist §Errors](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#errors-always-required).

#### E1 Error capture / recover

**The question.** How are handler / fx / sub exceptions, schema-validation failures, and drain-depth-exceeded caught, classified, and reported?

**What's at stake.** Try/catch around handler bodies, fx invocations, sub computations. Capture must NOT swallow errors silently — every catch fires a structured trace event with `:operation :rf.error/<category>` and `:op-type :error`.

**Where the spec speaks.** [Implementor-Checklist §E1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#e1-error-capture--recover). [`spec/009-Instrumentation.md` §Recovery contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#recovery-contract).

#### E2 Error reporting to tools

**The question.** How do tools consume errors, and where does error policy live?

**What's at stake.** Errors are emitted as structured trace events (falls out of T1); tools branch on `:op-type :error` and `:operation` prefix. The per-frame `:on-error` slot in `reg-frame` metadata is the policy mechanism. Strings-as-errors are out — every error has an `:operation` namespaced keyword and a `:tags` map.

**Where the spec speaks.** [Implementor-Checklist §E2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#e2-error-reporting-to-tools). [`spec/009-Instrumentation.md` §Error contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#error-contract).

---

## D5. Schema mechanism

**The question.** How does the port describe the shapes flowing through the runtime?

**Three answers, not two.**

- **Yes-runtime-schema.** Use a host-native schema library — Malli (CLJS), Zod (JS/TS), Pydantic (Python), dry-rb (Ruby). Validation runs at boundaries in dev; elided in production.
- **Yes-via-host-types.** Use the host's type system — TypeScript types, F# discriminated unions, Kotlin sealed classes, Rust enums + `serde`, Scala case classes. The compiler enforces shapes at build time; runtime validation is optional.
- **No.** Skip schemas entirely. Permitted by the spec but rarely the right call — the schema layer is what AI agents read to learn the runtime's shapes.

**Constraint.** **Open shapes** are non-negotiable. Consumers tolerate unknown keys; producers grow shapes additively. Closed records/structs at the runtime-data layer are out per [Goal 5 — Clojure ethos](https://day8.github.io/re-frame2/spec/000-Vision/#goals).

**Where the spec speaks.** [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/). [Implementor-Checklist §Schemas](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#schemas-if-q4-is-yes).

---

## D6. Integration story

**The question.** Is the port a standalone library? Or does it integrate with a larger framework?

**Options.**

- **Standalone library.** Drop-in for an existing app; the consumer wires the runtime, the substrate adapter, and any per-feature artefacts.
- **Framework integration.** The port plugs into an existing framework's lifecycle — e.g. React Native, a Phoenix LiveView-like server, a Compose Multiplatform app, a SwiftUI app. The framework's render cycle is the trigger for `re-frame2`'s view recompute.
- **Embedded.** The runtime is embedded inside a larger non-UI process (a worker, a server, a game loop). No view layer; the port consumes events + subs + fx + app-db as a state-management substrate.

**How to choose.** Driven by the engineer's downstream consumer. Standalone is the lowest-friction starting point.

---

## D7. Conformance capability tag set

**The question.** Given Phase 1's scope, which capability tags from the conformance corpus does the port claim?

**The capability tag families** ([`spec/conformance/README.md` §Capability tagging](https://day8.github.io/re-frame2/spec/conformance/)):

- `:core/*` — pattern-required basics. Every conformant port claims these.
- `:fsm/*` — FSM-richness axis (claim if D3 Q1 = yes; pick which sub-capabilities — `:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`).
- `:actor/*` — actor-model axis (claim if D3 Q1 = yes; pick which — `:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`).
- `:routing/*` — claim if D3 Q2 = yes.
- `:ssr/*` — claim if D3 Q3 = yes.
- `:schemas/*` — claim if D3 Q4 ≠ no (regardless of mechanism).

The harness runs every fixture whose `:fixture/capabilities` is a subset of the claimed set and skips the rest. The score is `passed / claimed-applicable`.

**Where the spec speaks.** [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/) — full capability tagging table and the harness contract.

---

## After Phase 1

When every decision above has been answered and captured in the decision record:

1. Commit `DECISIONS.md` (the filled-in record) to the port's repo.
2. Move to Phase 2 — `phase-2-impl-order.md` walks the EP corpus in dependency order, with the Phase 1 record as the source of truth for every host-specific binding.
