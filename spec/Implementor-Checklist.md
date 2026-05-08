# Implementor's Checklist

> **Type:** Reference / Companion
> A consolidated decision list for porting re-frame2 to a new host language. The checklist is the structured form of [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone): an AI armed with `/spec/` plus this checklist plus the [conformance corpus](conformance/README.md) should be able to produce a working v1 implementation in any host without consulting outside sources.

The checklist is in three parts:

- **[Part 1 — How complete?](#part-1--how-complete)** asks which optional capabilities the implementation supports. Each capability gates substantial chunks of the spec; declaring them upfront scopes the work.
- **[Part 2 — How achieved?](#part-2--how-achieved)** asks, for each included capability, what technology and library choices the implementation makes. Each entry names the decision, why it matters, options by host, the reference-impl picks, and the trade-offs.
- **[Part 3 — Conformance](#part-3--conformance)** explains how to consume the conformance corpus given the implementation's claimed capability set.

Cross-reference for orientation: [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) is the single-source-of-truth row-by-row breakdown of pattern-required vs. host-discretion vs. CLJS-only. This checklist is the **decision-ordered companion** — the matrix tells you "must I ship this?"; the checklist tells you "in what order do I make the decisions, and what are the canonical options?"

---

## Part 1 — How complete?

The implementor declares which capabilities the implementation includes. The required core is non-negotiable (every conformant implementation has it); the optional capabilities are declared yes/no, and the conformance corpus runs the matching subset of fixtures.

### Required (not gated; every implementation ships these)

These rows are **pattern-required** in [000 §Host-profile matrix](000-Vision.md#host-profile-matrix). A claim to be "this pattern" requires all of them.

| Capability | What it is | Spec |
|---|---|---|
| **Identity primitive** | Stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective ids | [000 §The identity primitive](000-Vision.md#the-identity-primitive--required-properties) |
| **Persistent data structures** | Structural sharing for `app-db` and frame state — required by [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) | [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) |
| **Registry by `(kind, id)`** | Metadata-bearing lookup for handlers, subs, fx, cofx, views, frames, routes | [001](001-Registration.md) |
| **Event handler contract** | `(state, event) → effects-map` | [002](002-Frames.md) |
| **Closed effect-map shape** | `:db` and `:fx` only at the top level | [002](002-Frames.md), [Spec-Schemas §`:rf/effect-map`](Spec-Schemas.md#rfeffect-map) |
| **Subscription / derivation system** | Query → value-from-state, with stable composition | [002](002-Frames.md) |
| **Frame as isolated runtime boundary** | `{state, queue, sub-cache, id}`; multi-instance | [002](002-Frames.md) |
| **Run-to-completion drain semantics** | Per frame; cascade settles before next event | [002](002-Frames.md) |
| **View contract** | Pure `(state, props) → render-tree`; render-tree is serialisable data | [004](004-Views.md) |
| **Trace event stream** | Structured events from well-defined emit sites | [009](009-Instrumentation.md) |
| **Error contract** | Structured trace events for runtime failures (handler exceptions, schema validation, drain depth, no-such-handler, ...) | [009 §Error contract](009-Instrumentation.md#error-contract) |
| **Conformance corpus consumption** | Run the corpus against the implementation; report passes per claimed capability | [conformance/README](conformance/README.md) |

### Optional (declare yes/no; conformance is graded against the claimed list)

For each row, the implementor declares **yes** (the implementation supports the capability and runs the matching fixtures) or **no** (the implementation skips; matching fixtures are reported as "not exercised," not as failures).

#### Q1. State machines?

The FSM/actor substrate from [005](005-StateMachines.md) — transition tables, `create-machine-handler`, the `:rf/machines` reserved app-db storage, drain extensions for `:raise`/`:always`/`:after`, hierarchy support, declarative `:invoke`. Substantial work. The pattern remains useful without machines (events / subs / fx / app-db / views are self-sufficient); many small frameworks ship without machines initially.

**Declaring yes implies** picking an FSM-richness capability list and an actor-model capability list per [005 §Capability matrix](005-StateMachines.md#capability-matrix). The CLJS reference claims flat-FSM + hierarchical compound + `:always` + `:after`, plus own-state + spawn/destroy + cross-actor `:fx` + declarative `:invoke`. Smaller ports can claim less; conformance grades against the claimed list.

**Gate:** does the application surface include state-bearing flows that the events+subs+fx triad makes awkward (auth flows, multi-step wizards, drag-and-drop, timer-driven transitions)?

#### Q2. Routing?

The URL ↔ frame state contract from [012](012-Routing.md) — `reg-route`, `match-url`, `route-link`, `:rf.nav/push-url` fx, `:rf/pending-navigation`, navigation tokens, fragment handling.

**Gate:** is the application a routed SPA? Component libraries, devtools, single-screen apps don't need routing.

#### Q3. SSR?

The server-side render + hydration contract from [011](011-SSR.md) — `:platforms` metadata, `render-to-string`, `:rf/hydrate`, hydration-mismatch detection.

**Gate:** does the deployment require server rendering (SEO, time-to-first-byte, social previews) or full-stack hydration?

#### Q4. Schemas?

Boundary validation + introspection from [010](010-Schemas.md) — `:spec` registration metadata, `reg-app-schema`, validation-failure trace events.

In **dynamic hosts** (CLJS, JS, Python, Ruby) this is a runtime schema layer — Malli (CLJS reference), Zod (JS), Pydantic (Python), dry-rb (Ruby). In **static hosts** (TypeScript, Kotlin, Rust, F#, Swift) the host's type system covers much of the territory at compile time. A static-host port may also ship a runtime validation library (Zod alongside TS types) or rely on types alone.

**Three answers, not two:** *yes-runtime-schema*, *yes-via-host-types*, or *no*. The pattern requires *shape description*; the mechanism is host-discretion (per [000 §Host-profile matrix](000-Vision.md#host-profile-matrix)).

**Gate:** does the host have a strong static type system that already describes the runtime shapes? If yes, "yes-via-host-types" is usually the right answer.

#### Q5. Stories?

Stories / variants / workspaces from [007](007-Stories.md) — Storybook/Histoire/devcards-class tooling.

**Gate:** does the implementation target a UI library or component-heavy app that needs catalog tooling? Post-v1 in re-frame-cljs; an early-stage port skips entirely.

#### Q6. Tool-Pair adapters?

Pair-shaped AI inspection tools per [Tool-Pair.md](Tool-Pair.md) — REPL-attached AI/REPL companion that inspects, dispatches, hot-swaps, time-travels.

**Gate:** is the implementation targeting AI-driven development workflows (the ones [Goal 1 — AI-first amenability](000-Vision.md#goals) calls out as primary)? Useful for AI-driven dev; non-essential for app delivery.

#### Q7. AI-Audit grading?

Self-grading against the AI-first principles per [AI-Audit.md](AI-Audit.md). The audit is a discipline tool, not a runtime feature — declaring yes means the implementation maintains an audit doc per Spec.

**Gate:** does the implementation aim to claim AI-first conformance certification? Useful for spec-conformance certification; non-essential at impl time.

### How declarations map to conformance

Conformance fixtures are capability-tagged (per [conformance/README §Capability tagging](conformance/README.md#capability-tagging)). The harness runs every fixture whose capabilities are a subset of the implementation's claimed list and skips the rest. The score is `passed / claimed-applicable` — an honest accounting of "what works for what was claimed."

A flat-FSM-only port that declares **Q1 yes (flat FSM only)**, **Q2 no**, **Q3 no**, **Q4 yes-via-host-types**, **Q5 no**, **Q6 yes**, **Q7 no** has a clear scope: the corpus runs all `:core/*` fixtures, the `:fsm/flat` fixtures, and the `:actor/own-state` + `:actor/spawn-destroy` fixtures. Routing, SSR, hierarchical FSM, `:fsm/eventless-always`, `:fsm/delayed-after`, `:invoke`, and stories fixtures are reported as not-exercised.

---

## Part 2 — How achieved?

For each capability included in Part 1, the implementor makes the per-capability technology and library decisions below. Each entry lists:

- **Name** — what the decision is.
- **Why it matters** — which re-frame2 mechanic depends on this; cross-reference the goal/spec it serves.
- **Options by host** — canonical choices for the major host languages (CLJS, JS/TS, Python, Rust, F#/Kotlin, Swift, Java).
- **Reference-impl picks** — what re-frame-cljs uses; what other claimed reference implementations would pick.
- **Trade-offs** — criteria for choosing.

### Foundation (always required)

#### F1. Identity primitive

- **Why it matters.** Every queryable, every override, every trace event, every error category is identified by an id. The runtime looks up, compares, ships, and reflects on ids cheaply. See [000 §The identity primitive](000-Vision.md#the-identity-primitive--required-properties) for the seven required properties.
- **Options by host.**
  - **CLJS** — Clojure keywords (`:foo/bar`). Native; satisfies all properties.
  - **TS / JS** — Branded string types (`type EventId = string & { readonly __id: 'EventId' }`) with a naming convention (`'cart.item/remove'`). Use a small `id()` helper with interning + namespace parsing. ES `Symbol.for(...)` is *not* a fit — symbols don't serialise.
  - **Python** — Strings with naming convention plus a small `Id` class wrapping `str` with `.namespace()` / `.local()` methods, or `enum.Enum` per kind for closed sets.
  - **Rust** — Newtype `struct Id(&'static str)` with conventions, or `phf` interning for closed sets.
  - **Kotlin / F#** — Sealed-class hierarchies of `data object` ids, or value classes wrapping `String` with namespace parsing.
  - **Swift** — Enums conforming to `RawRepresentable<String>` plus a namespace convention.
- **Reference-impl picks.** CLJS uses keywords. A TypeScript reference would use branded strings + interning.
- **Trade-offs.** The seven properties (stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective) are non-negotiable. If a host's natural choice violates any property, pick a different mechanism — UUIDs, integer ids, and Java reference-equality classes are all rejected upfront.

#### F2. Persistent data structures

- **Why it matters.** Pulled from "encouraged" to **pattern-required** by [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility). Structural sharing makes "reverting" cheap (a pointer swap, not a deep copy). Without persistent structures the goal is unaffordable.
- **Options by host.**
  - **CLJS** — Clojure persistent collections (native).
  - **JS / TS** — Immer (copy-on-write) or [mori](https://swannodette.github.io/mori) or Immutable.js.
  - **Python** — [pyrsistent](https://github.com/tobgu/pyrsistent).
  - **Kotlin** — [im.kt](https://github.com/im-co/im.kt) or `kotlinx.collections.immutable`.
  - **Rust** — [im](https://crates.io/crates/im) (immutable.rs) — vector, map, hashmap with structural sharing.
  - **Swift** — Swift's value-typed `Dictionary`/`Array` do copy-on-write; `OrderedDictionary` from swift-collections gives ordered semantics.
- **Reference-impl picks.** CLJS uses Clojure persistent collections.
- **Trade-offs.** Hosts without a mainstream persistent-collection library face a real cost. Defaulting to deep-copy snapshots is technically correct but performance-prohibitive at any scale. Pick a library and budget time to verify its sharing characteristics under the host's GC.

#### F3. Reactive substrate

- **Why it matters.** The runtime's reactive container for `app-db`, the change-tracking that drives view re-renders, and the render-tree → surface step. Substrate-decoupled per [006](006-ReactiveSubstrate.md). Adapter contract is locked at six required + two optional + one lifecycle function, with a [§Revertibility constraint](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters) that adapter-internal state must be derivable from the frame value.
- **Options by host.**
  - **CLJS** — Reagent (default; atop React) or plain-atom (JVM/headless/SSR). Other CLJS adapters (UIx, Helix) plug in via the same contract.
  - **JS / TS** — Solid (`createSignal` + `createMemo`), `useSyncExternalStore`, MobX, or Vue refs.
  - **Python** — RxPy (`BehaviorSubject`), or a hand-rolled signal library — most Python apps don't need reactivity (server-side render only).
  - **Kotlin** — Compose runtime, or coroutines `StateFlow`.
  - **Rust** — Leptos signals, `dioxus-signals`, or `crossbeam` channels for back-end-only ports.
  - **Swift** — SwiftUI's `@Observable` / `Combine` `CurrentValueSubject`.
- **Reference-impl picks.** CLJS uses Reagent (browser) and plain-atom (JVM).
- **Trade-offs.** Pick one signal library per port; the spec is single-adapter-per-process (per [006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)). Multi-adapter coexistence is post-v1.

#### F4. Effect-handling primitive

- **Why it matters.** How `:fx` dispatches; sync vs async handling; effect resolution against the registry.
- **Options by host.**
  - **CLJS** — `reg-fx` registered handlers; sync effects run inline, async effects schedule via host setTimeout/Promise; `:dispatch` and `:dispatch-later` ship as standard fx.
  - **JS / TS** — Same shape; `setTimeout` / `Promise` / `queueMicrotask` for async.
  - **Python** — `asyncio` loop or sync iteration depending on host.
  - **Rust** — `tokio` for async; sync on a single-threaded executor for tests.
  - **Kotlin** — Coroutines (`launch` / `async`).
- **Reference-impl picks.** CLJS uses `reg-fx` with sync default; standard `:dispatch` / `:dispatch-later` / `:http` (per [Pattern-RemoteData](Pattern-RemoteData.md)).
- **Trade-offs.** Sync-by-default keeps the drain semantics simple (per [002 §Run-to-completion](002-Frames.md)). Async fx must NOT escape the drain — they re-enter via `:dispatch` after their underlying side effect completes.

#### F5. Concurrency model

- **Why it matters.** Run-to-completion drain semantics ([002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)) are the spine of [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility). The implementation must guarantee no async mutation escapes the dispatch loop.
- **Options by host.**
  - **CLJS** — Single-threaded JS event loop guarantees this for free in browsers; on JVM, the test harness runs sync.
  - **JS / TS** — Single-threaded JS event loop (browser, Node main thread).
  - **Python** — Single-threaded `asyncio` loop or single-threaded sync; multi-threaded ports must serialize dispatch via a lock or channel.
  - **Rust** — Single-threaded executor for tests; multi-threaded executors must serialize per-frame dispatch.
  - **Kotlin** — Single coroutine context per frame.
- **Reference-impl picks.** CLJS relies on the JS event loop.
- **Trade-offs.** **No core.async** — the CLJS reference does not use core.async, and ports inherit this directive. Async fx are scheduled via host primitives (Promise, setTimeout); cross-frame dispatch is serialised per frame.

#### F6. Hot-reload primitive

- **Why it matters.** Re-registration replaces; emits `:rf.registry/handler-replaced` (per [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics)). Pair-tool hot-swap depends on this.
- **Options by host.**
  - **CLJS** — figwheel/shadow-cljs reload; `reg-*` calls surgically update the registrar; frames preserve runtime state via `reg-frame`'s update path (per [002](002-Frames.md)).
  - **JS / TS** — Vite HMR + module-replacement boundary; same registrar update pattern.
  - **Python** — Watch + reimport; `reg-*` calls re-bind in the registrar.
  - **Rust** — Compile-replace cycle; for dev-only hot-reload, `dlopen`/dynamic-library swap is the route.
- **Reference-impl picks.** CLJS uses figwheel/shadow-cljs.
- **Trade-offs.** The registrar is a single mutable cell; replacing entries is atomic. Frame state is preserved across re-registration of `reg-frame` (per [002 §reg-frame is atomic](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)).

### State storage (always required)

#### S1. App-db container

- **Why it matters.** The frame's persistent value lives in this container. The container's value is the frame's `app-db`; all reads/writes go through `read-container` / `replace-container!` (per [006](006-ReactiveSubstrate.md)).
- **Options by host.** Per **F3** (reactive substrate); the same library typically supplies the container.
- **Reference-impl picks.** CLJS uses Reagent ratom (browser) / `clojure.core/atom` (JVM).
- **Trade-offs.** The container's *value* is what's restored on revert; the *identity* is stable. Adapters MUST NOT hold non-derivable state outside the container (per [006 §Revertibility constraints on adapters](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters)).

#### S2. Snapshot/restore mechanism

- **Why it matters.** Test fixtures (`make-restore-fn`), epoch history, and time-travel all depend on full-frame-state capture-and-restore being a value swap.
- **Options by host.** With persistent collections (per **F2**), a snapshot is a pointer; restore is `replace-container!`. Without persistent collections, snapshot is deep-copy and expensive.
- **Reference-impl picks.** CLJS captures the full app-db value; restore swaps it.
- **Trade-offs.** This is why **F2** is pattern-required — the cost profile of revertibility depends on it.

#### S3. Path-access primitive

- **Why it matters.** `assoc-in` / `update-in` / `get-in` over the frame's app-db. Used by handlers, the `path` standard interceptor, registered subs that read paths, and `(rf/snapshot-of path)`.
- **Options by host.**
  - **CLJS** — Native `assoc-in` / `update-in` / `get-in`.
  - **JS / TS** — Immer's `produce` for `update-in`-style; `lodash.get`/`lodash.set` immutably wrapped; or hand-rolled.
  - **Python** — pyrsistent's path API (`.transform`).
  - **Rust** — Lens libraries or hand-rolled per-shape functions.
- **Reference-impl picks.** CLJS uses native.
- **Trade-offs.** Path operations are hot — choose a fast implementation.

### Subscriptions (always required)

#### Sub1. Signal graph + caching

- **Why it matters.** Subscriptions form a DAG over `app-db`; values are cached per `=`-equality. Layer-1 subs read `app-db` directly; layer-2+ compose via `:<-` (per [002 §Subscriptions](002-Frames.md) and [006 §Subscription cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics)).
- **Options by host.** Falls out of **F3** + **S1**.
- **Reference-impl picks.** CLJS uses Reagent reactions for the graph.
- **Trade-offs.** Equality-by-value is required for cache invalidation; identity-only equality breaks the contract.

#### Sub2. Lifecycle (when to dispose)

- **Why it matters.** Subs that no view is reading should be torn down to release resources. The mechanism varies by reactive substrate.
- **Options by host.** Per **F3**; signal libraries supply their own dispose semantics.
- **Reference-impl picks.** CLJS uses Reagent's reaction lifecycle (last-deref-disposes after a delay).
- **Trade-offs.** Disposal is invisible to handler/sub authors; it's a substrate concern.

### Views (always required)

#### V1. Render-tree shape

- **Why it matters.** Pure `(state, props) → render-tree` is the view contract; the render-tree must be serialisable data (per [004](004-Views.md)).
- **Options by host.**
  - **CLJS** — Hiccup (`[:div {:class "foo"} child]`).
  - **JS / TS** — JSX-as-data (with Babel transform, JSX literally is `React.createElement(...)` calls; for pure-data SSR use snabbdom-style vnodes or hiccup ports).
  - **Python** — Tuple/dict trees per Anthropic-style libraries; SSR is usually the only render target.
  - **Rust** — RSX (Dioxus), or hand-rolled vnode trees.
- **Reference-impl picks.** CLJS uses hiccup.
- **Trade-offs.** Render-tree shape must be serialisable for SSR (per [011](011-SSR.md)) and inspectable for view-tree tooling. Closed component trees that don't serialise (raw React elements with closures) make SSR + inspection hard.

#### V2. Render trigger

- **Why it matters.** When does the view re-render? Tied to the substrate's reactivity (per **F3**).
- **Options by host.** Falls out of **F3**; signal libraries trigger re-render on subscribed-value change.
- **Reference-impl picks.** CLJS triggers re-render via Reagent's auto-tracked deref dependency capture.
- **Trade-offs.** The trigger must be observably equivalent to "change in `app-db` → recompute affected subs → re-render dependent views" (per [006 §Subscription cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics)).

#### V3. Mount/unmount

- **Why it matters.** Component lifecycle hooks; mount-time setup events; unmount-time cleanup.
- **Options by host.** Per **F3**; component libraries supply their own lifecycle.
- **Reference-impl picks.** CLJS uses Reagent's component lifecycle methods.
- **Trade-offs.** Lifecycle should fire `:on-create` (mount-time) and `:on-destroy` (unmount-time) events on the surrounding frame, integrating with the run-to-completion drain.

### Tracing & instrumentation (always required)

#### T1. Trace-event delivery

- **Why it matters.** Trace events flow into a single per-application stream; subscribers listen. Batched, debounced delivery (50ms default) per [009 §Subscription / consumption](009-Instrumentation.md#subscription--consumption). Plus a retain-N ring buffer (per [009 §Retain-N trace ring buffer](009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only)) for tools that attach after events have fired.
- **Options by host.** Hand-rolled per host; the contract is just "deliver collections of trace maps to registered callbacks at a debounced cadence."
- **Reference-impl picks.** CLJS uses a single atom (the listener registry) + a setTimeout-driven debounce + a separate ring-buffer atom.
- **Trade-offs.** Hot path: trace allocation must be cheap; listener invocation must short-circuit when no listeners are registered.

#### T2. Performance API equivalent

- **Why it matters.** Browser DevTools cross-correlation. The CLJS reference ships a Chrome Performance API bridge (per [009 §Chrome Performance API integration](009-Instrumentation.md#chrome-performance-api-integration)). Optional in other hosts.
- **Options by host.**
  - **CLJS / JS / TS (browser)** — `performance.mark` / `performance.measure`.
  - **JVM** — `clj-async-profiler`, JFR, or omit.
  - **Python** — `cProfile` integration, OpenTelemetry spans, or omit.
  - **Rust** — `tracing` crate spans.
- **Reference-impl picks.** CLJS uses the Chrome Performance API bridge.
- **Trade-offs.** Optional; the underlying trace surface is the contract.

#### T3. Production elision

- **Why it matters.** All tracing is dev-only. Production builds must elide every emit call site, the listener registry, the trace buffer, the Performance bridge (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).
- **Options by host.**
  - **CLJS** — `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) + Closure compiler dead-code elimination, with a CI verifier (`scripts/check-elision.cjs`) that asserts dev-only sentinel strings are absent from `:advanced` `goog.DEBUG=false` bundles. See [009 §Production-elision verification](009-Instrumentation.md#production-elision-verification).
  - **JS / TS** — Build-time constant + tree-shake (Vite/Rollup with `define`); or `process.env.NODE_ENV` checks elided by the bundler.
  - **Python** — Module-level constant + `if __debug__:` (Python's `-O` flag elides assert and `__debug__` blocks).
  - **Rust** — Cargo features (`#[cfg(feature = "trace")]`); release builds omit the trace feature.
  - **Kotlin** — Multi-module setup; release variant omits the tracing module.
- **Reference-impl picks.** CLJS uses Closure dead-code elimination.
- **Trade-offs.** Hosts without compile-time elision pay a runtime boolean check; CLJS pays nothing at all in production.

### Errors (always required)

#### E1. Error capture / recover

- **Why it matters.** Handler exceptions, fx exceptions, sub exceptions, schema-validation failures, drain depth exceeded — all must be caught, classified, and reported. Recovery contract per [009 §Recovery contract](009-Instrumentation.md#recovery-contract).
- **Options by host.** Try/catch around handler bodies, fx invocations, sub computations.
- **Reference-impl picks.** CLJS uses try/catch in `events.cljc` / `fx.cljc` / `subs.cljc`.
- **Trade-offs.** Capture must not swallow errors silently — every catch fires a structured trace event with `:operation :rf.error/<category>` and `:op-type :error`.

#### E2. Error reporting to tools

- **Why it matters.** Errors are emitted as structured trace events (per [009 §Error contract](009-Instrumentation.md#error-contract)) — tools branch on `:op-type :error` and `:operation` prefix. `reg-event-error-handler` is the single-slot policy mechanism.
- **Options by host.** Falls out of **T1** (trace delivery).
- **Reference-impl picks.** CLJS routes errors through the trace stream.
- **Trade-offs.** Strings as errors are out — every error has an `:operation` namespaced keyword and a `:tags` map per the error category.

### Testing (recommended; required for conformance)

#### Test1. Test runner conventions

- **Why it matters.** Per-test frames (`make-frame` / `destroy-frame`), synchronous trigger (`dispatch-sync`), per-test stubbing (`:fx-overrides`, `:interceptor-overrides`), framework adapter (per [008](008-Testing.md)).
- **Options by host.**
  - **CLJS** — `cljs.test` / `clojure.test` re-exports plus `re-frame.test` helpers.
  - **JS / TS** — Vitest, Jest, or hand-rolled.
  - **Python** — `pytest` + a small framework adapter.
  - **Rust** — `#[test]` + a per-test frame fixture.
- **Reference-impl picks.** CLJS uses cljs.test/clojure.test.
- **Trade-offs.** Headless evaluation must work — tests run on JVM (per [000 §C2 Cross-platform](000-Vision.md#c2-cross-platform-jvm-interop-preserved)).

#### Test2. Headless evaluation

- **Why it matters.** `compute-sub` (pure sub computation against an `app-db` value) and `machine-transition` (pure transition function) must run without a JS runtime / browser. Tests use these for fast iteration.
- **Options by host.** Pure functions; no host-specific machinery.
- **Reference-impl picks.** CLJS implements these in `.cljc` files; both targets run them.
- **Trade-offs.** Implementations that bake substrate dependencies into sub computation break this — keep `compute-sub` and the transition fn pure.

#### Test3. Conformance fixture consumption

- **Why it matters.** The conformance corpus is the verification mechanism for [Goal 2](000-Vision.md#ai-implementable-from-the-spec-alone). Each fixture is an EDN file describing a canonical interaction; the harness reads fixtures, realises handler bodies via the small DSL, runs the dispatches, captures observables, compares.
- **Options by host.** Per [conformance/README §How an implementation runs the corpus](conformance/README.md#how-an-implementation-runs-the-corpus). The harness is ~300 lines per host.
- **Reference-impl picks.** CLJS reads EDN natively; static-host ports parse EDN with a small reader (or JSON-translated copy of the corpus).
- **Trade-offs.** The corpus is host-agnostic data. Handler bodies in fixtures are a small DSL the host realises into native closures (~50 lines of interpreter per host).

### Routing (if Q2 is yes)

#### R1. URL primitive

- **Why it matters.** `match-url` parses URLs into `{:route-id :params :query}`; `route-url` is the inverse (per [012](012-Routing.md)).
- **Options by host.**
  - **CLJS** — Hand-rolled match/route from registered route metadata, or `bidi`-style libraries.
  - **JS / TS** — `react-router` (extract router internals), `path-to-regexp`, or hand-rolled.
  - **Python** — Werkzeug routing, Starlette router.
  - **Rust** — `axum::routing` patterns or `matchit`.
- **Reference-impl picks.** CLJS hand-rolls the matcher with a 6-rule precedence cascade.
- **Trade-offs.** Routes are registry entries (per [012](012-Routing.md)) — the routing table is data, queryable via `(handlers :route)`.

#### R2. Navigation observer

- **Why it matters.** URL changes (popstate, pushState, hash changes) need to translate into `:rf.route/navigate` events. Per [012 §Fragments](012-Routing.md#fragments) and §Navigation blocking.
- **Options by host.**
  - **Browser CLJS / JS / TS** — `popstate` + `hashchange` listeners; `history.pushState` / `replaceState` for fx.
  - **Server-side** — Initial URL from the request; no observer needed.
  - **Native (mobile)** — Deep-link receivers; navigation is OS-driven.
- **Reference-impl picks.** CLJS uses browser history API + `:rf.nav/push-url` fx.
- **Trade-offs.** Navigation tokens (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)) are required for stale-result suppression — make sure the implementation threads them through.

### SSR (if Q3 is yes)

#### SSR1. Render-to-string

- **Why it matters.** Pure render-tree → HTML string, JVM-runnable in the CLJS reference, host-pure in any port (per [011](011-SSR.md) and [006 §`render-to-string`](006-ReactiveSubstrate.md#render-to-string-render-tree-opts--string)).
- **Options by host.**
  - **CLJS** — Hand-rolled hiccup → HTML emitter.
  - **JS / TS** — `renderToString` from React-DOM, Solid's SSR module, or hand-rolled vnode → HTML.
  - **Python** — Hand-rolled or Jinja2-style.
- **Reference-impl picks.** CLJS uses a pure hiccup → HTML emitter (~200 lines).
- **Trade-offs.** Must escape text and attrs correctly; void elements (`<br>`, `<img>`) need special-case handling.

#### SSR2. Hydration boundary

- **Why it matters.** First client render replaces the server-supplied HTML; mismatch detection emits `:rf.ssr/hydration-mismatch`.
- **Options by host.** Per [011 §Hydration](011-SSR.md). The handshake is `:rf/hydrate` event seeds `app-db` from the server payload.
- **Reference-impl picks.** CLJS uses `reagent.dom.client/hydrate`.
- **Trade-offs.** Hydration payload must be schema'd (per `:rf/hydration-payload` in [Spec-Schemas](Spec-Schemas.md)).

#### SSR3. Platform gating

- **Why it matters.** `reg-fx` carries `:platforms` metadata; effects gated on platform skip on the wrong side and emit `:rf.fx/skipped-on-platform`.
- **Options by host.** Per [011 §`:platforms`](011-SSR.md). `init-platform` sets the active platform per build target.
- **Reference-impl picks.** CLJS calls `(init-platform :server)` or `(init-platform :client)` at boot.
- **Trade-offs.** Without platform gating, shared event handlers can't be reused across server and client — the gate is what makes one handler work both sides.

### Schemas (if Q4 is yes)

#### Sch1. Schema language

- **Why it matters.** Boundary validation + introspection per [010](010-Schemas.md). The pattern requires shape *description*; the *mechanism* is host-discretion.
- **Options by host.**
  - **Dynamic hosts (CLJS, JS, Python, Ruby)** — Malli (CLJS reference), Zod (JS), Pydantic (Python), dry-rb (Ruby).
  - **Static hosts (TS, Kotlin, Rust, F#, Swift)** — Host type system covers most territory; runtime layer is optional.
- **Reference-impl picks.** CLJS uses Malli (open by default; `:closed true` opt-in).
- **Trade-offs.** Open shapes (consumers tolerate unknown keys; producers grow shapes additively) are non-negotiable per [Goal 5 — Clojure ethos](000-Vision.md#goals). Closed records / structs are out at the runtime-data layer.

#### Sch2. Validation timing

- **Why it matters.** Validation runs at boundaries (handler entry, sub return, fx args, app-db at registered paths) in dev; elides in production. Per [010](010-Schemas.md).
- **Options by host.** Boundary validation is wrapped around the standard call sites.
- **Reference-impl picks.** CLJS validates in dev, elides via `goog-define` in prod.
- **Trade-offs.** Per **T3** — production elision must extend to validation.

#### Sch3. Introspection API

- **Why it matters.** `(app-schemas)`, `(app-schema-at path)`, plus per-registration `(handler-meta kind id)` returning `:spec`.
- **Options by host.** Falls out of **F1** + **Sch1**.
- **Reference-impl picks.** CLJS exposes via `re-frame.core`.
- **Trade-offs.** Tooling and AI agents read this — make sure the schema is data, not opaque host objects.

### Machines (if Q1 is yes)

#### M1. Snapshot serialisation format

- **Why it matters.** Machine snapshots ride at `[:rf/machines <id>]` in `app-db` (per [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live)) and must serialise for SSR hydration and persistence.
- **Options by host.** Falls out of **F2** (persistent collections) + **Sch1** (shape description).
- **Reference-impl picks.** CLJS uses a `{:state ... :data ...}` map; serialises trivially as EDN.
- **Trade-offs.** Closures or host-specific objects in `:data` break serialisation — keep snapshot fields pure data.

#### M2. Spawn primitive

- **Why it matters.** `:spawn` fx creates a new machine instance under a generated id; `:destroy-machine` fx tears it down (per [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)).
- **Options by host.** Per [005](005-StateMachines.md). Spawn is a registered fx; the runtime updates frame-local registry.
- **Reference-impl picks.** CLJS uses gensym'd machine ids.
- **Trade-offs.** Spawn registers in the frame-local tier (per [Goal 3](000-Vision.md#frame-state-revertibility)); undo rolls back spawned actors.

#### M3. Drain implementation

- **Why it matters.** Four-level drain — events → microsteps (`:always`) → macrostep settles → `:after` fires — per [005 §Drain](005-StateMachines.md).
- **Options by host.** Recursive depth-bounded loop; depth limits configurable.
- **Reference-impl picks.** CLJS implements the four-level drain in pure Clojure.
- **Trade-offs.** Depth limits prevent infinite loops; emit `:rf.error/machine-always-depth-exceeded` and `:rf.error/machine-raise-depth-exceeded` at the limit.

#### M4. Hierarchy support level

- **Why it matters.** Per [005 §Capability matrix](005-StateMachines.md#capability-matrix). The implementor declares hierarchical compound support yes/no; CLJS reference claims yes.
- **Options by host.** Per [005](005-StateMachines.md).
- **Reference-impl picks.** CLJS supports hierarchical compound, `:always`, `:after`, declarative `:invoke`.
- **Trade-offs.** Hierarchical states add transition-resolution complexity (LCA-based exit cascades). Smaller ports can declare flat-FSM only.

### Tool-Pair (if Q6 is yes)

#### TP1. Tool-Pair adapters

- **Why it matters.** Pair-shaped AI inspection tools (re-frame-pair equivalent) attach to a running re-frame2 application and let an AI agent inspect, dispatch, hot-swap, and time-travel. Per [Tool-Pair.md](Tool-Pair.md).
- **The full attachment surface.** Per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach):
  - **Trace listener** — `(rf/register-trace-cb! key callback)` for live events.
  - **Trace buffer** — `(rf/trace-buffer ...)` for recent events (retain-N ring buffer; default 200).
  - **Epoch history** — `(rf/epoch-history frame-id)`, `(rf/restore-epoch frame-id epoch-id)`, `(rf/configure :epoch-history {:depth N})`.
  - **Registrar query** — `(rf/handlers kind)`, `(rf/handler-meta kind id)`, `(rf/machines)`, `(rf/machine-meta id)`, `(rf/frame-ids)`, `(rf/frame-meta id)`.
  - **App-db query** — `(rf/get-frame-db frame-id)`, `(rf/snapshot-of path opts)`.
  - **Sub-cache (CLJS-only)** — `(rf/sub-cache frame-id)`.
  - **Source coords** — `:ns`/`:line`/`:file` keys on registration metadata.
  - **Dispatch + hot-swap + fx-stub** — `dispatch` opts (`:fx-overrides`), re-`reg-*` for hot-swap.
- **Options by host.** Per host's REPL: nREPL+CIDER (CLJS); IPython (Python); whatever's idiomatic. The framework primitives are host-agnostic.
- **Reference-impl picks.** CLJS reference ships the trace surface, epoch history, and registrar query API in-tree (per [rf2-icil audit](Tool-Pair.md#how-ai-tools-attach)). re-frame-pair is a separate library that consumes these.
- **Trade-offs.** **No 10x dependency required** — re-frame2 is infrastructure-complete for AI-tool consumption. 10x and pair share the substrate.

---

## Part 3 — Conformance

The implementation runs the [conformance corpus](conformance/README.md) to verify it conforms to the pattern.

The harness (per [conformance/README §How an implementation runs the corpus](conformance/README.md#how-an-implementation-runs-the-corpus)) is ~300 lines per host:

1. Read all `.edn` files in `fixtures/`.
2. For each fixture, check whether `:fixture/capabilities` is a subset of the implementation's claimed capability list.
3. If yes, bootstrap the runtime, realise handler bodies via the DSL, create a frame, run the dispatches, capture observables, compare.
4. If no, report as "not exercised."
5. Aggregate score is `passed / claimed-applicable`.

**Capability tags** (per [conformance/README §Capability tagging](conformance/README.md#capability-tagging)) come in family namespaces:

- `:core/*` — pattern-required basics. Every conformant implementation runs these.
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`). Run if Q1 yes and the matching capability is claimed.
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`). Run if Q1 yes and the matching capability is claimed.
- `:routing/*` — run if Q2 yes.
- `:ssr/*` — run if Q3 yes.
- `:schemas/*` — run if Q4 yes (regardless of mechanism).

The corpus is the **acceptance test** for [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone). A fixture an AI cannot reproduce without consulting outside sources is a **spec gap**, not an implementation gap; remediation is to fix the spec corpus, not the implementation.

---

## Cross-references

- [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) — single-source row-by-row breakdown of pattern-required vs. host-discretion vs. CLJS-only.
- [000 §AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) — the goal this checklist operationalises.
- [000 §Hierarchical FSM substrate with implementor-chosen capabilities](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) — the capability-list mechanism Part 1 is shaped by.
- [conformance/README](conformance/README.md) — the corpus the implementation runs against its claimed capability list.
- [API.md](API.md) — consolidated signatures for the surfaces named in Part 2.
- [Spec-Schemas](Spec-Schemas.md) — runtime shapes the implementation must produce / consume.
