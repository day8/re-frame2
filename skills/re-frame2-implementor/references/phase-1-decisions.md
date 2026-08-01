# phase-1-decisions

The Phase 1 walkthrough. Before writing any implementation code, walk each decision block below and capture the answer in the **decision record** (template at `decision-record.md`). Every Phase 1 decision propagates through every line of Phase 2 code; one focused session locking them saves weeks of rewrites.

## Contents

- Spec pin (load-bearing preamble — record before D1)
- D1. Target host language
- D2. Substrate / React binding
- D3. Scope — which EPs ship now (Q1–Q10)
- D4. Always-required realisation decisions (the checklist's Part 2 always-required blocks: Foundation F1–F6, State storage S1–S3, Subscriptions Sub1–Sub2, Views V1–V3, Tracing T1–T3, Errors E1–E2)
- D5. Schema mechanism
- D5b. Data classification (Sensitive + Large) — v1-required
- D6. Integration story
- D7. Conformance capability tag set

For each block: the question, what's at stake, options, how to choose, where the spec speaks to it.

**Id scheme — this skill mirrors the checklist's.** D4 below is sub-numbered with the **exact** Part 2 ids from [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) — F1–F6, S1–S3, Sub1–Sub2, V1–V3, T1–T3, E1–E2 — so the decision record cross-walks 1:1 with the checklist.

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

**What's at stake.** Every Phase 1 sub-decision (identity primitive, persistent data structures, concurrency model, render-tree shape) is constrained by what the host provides — the host fixes the shape of the space the port operates in.

**Options.** [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) §scope footnote locks the host set to exactly eight **JS-cross-compile-to-React+VDOM** languages — these are the *only* in-scope implementation targets:

- ClojureScript (the reference)
- TypeScript / JavaScript
- Squint
- Melange / ReScript / Reason
- Fable (F#)
- Scala.js
- PureScript
- Kotlin/JS

Non-React substrates (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit) and non-cross-compile-to-JS hosts (Python, Ruby, native Rust, Go, server-side Kotlin / Java / Swift) are **out of scope** — a deliberate scope choice, not an oversight. Where the [Implementor-Checklist](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) mentions a non-target host (e.g. `pytest`, `tokio`), treat it as **non-normative background** — illustrative shape, never an implementation track this skill sequences.

**How to choose.** Usually pre-decided by why the engineer started the port. Capture the host and the runtime (e.g. "TypeScript targeting browser + Node 20", "Fable F# compiling to JS for the browser", "Squint targeting browser"). If the engineer's target is outside the eight, the answer isn't "implement re-frame2 there anyway" — it's "the spec does not commit to that host." Surface the scope footnote and stop.

**Where the spec speaks.** [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) §"The pattern (JS-cross-compile-language-agnostic)" and the host-profile matrix. [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) Part 2 enumerates options per host for every foundation decision.

---

## D2. Substrate / React binding

**The question.** Which React binding renders the view, and what is the reactive container that holds `app-db`?

**What's at stake.** The reactive substrate decision propagates into EP 006 (the adapter contract), EP 004 (the render-tree shape), and the view-rerender trigger.

**The substrate is fixed: React + VDOM.** re-frame2 commits to React + VDOM at the render side (per [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/) and the [`spec/000-Vision.md`](https://day8.github.io/re-frame2/spec/000-Vision/) §scope footnote). Every in-scope host cross-compiles to JS and binds against React; non-React substrates are out of scope. So D2 is *not* "which substrate" — it is "which React binding does your host use, and what is the reactive container."

**Options (the host's React binding).**

- ClojureScript — Reagent atop React (the reference); UIx as an alternative React binding.
- TypeScript / JavaScript — React directly, or a `useSyncExternalStore`-backed store.
- Fable (F#) — Feliz / Fable.React over React.
- Squint — a thin React binding (Squint preserves the CLJS shape).
- Scala.js — Slinky / a `scalajs-react` binding.
- PureScript — `purescript-react-basic` / Halogen-over-React.
- Kotlin/JS — `kotlin-wrappers` React.
- Melange / ReScript / Reason — ReasonReact / `rescript-react`.

**How to choose.** Pick the host's idiomatic React binding. The reactive container is usually the same library that supplies the binding's reactivity (a ratom, a `useSyncExternalStore`-backed atom-shaped store, a signal cell, a `MutableStateFlow`-shaped cell).

**Trade-offs.** Bindings differ on how subscriptions auto-track (Reagent's deref-during-render vs UIx `use-subscribe` over `useSyncExternalStore`), but every in-scope binding plugs into the same six required + three optional + one lifecycle function contract from EP 006. The render trigger is uniformly "React re-renders on subscribed-value change."

**Where the spec speaks.** [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/) — the adapter contract + the React+VDOM commitment. [Implementor-Checklist §F3 Reactive substrate](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f3-reactive-substrate) — options per host. [Host-profile matrix in 000](https://day8.github.io/re-frame2/spec/000-Vision/#host-profile-matrix) — the reactive-tracking row names each host's React binding.

---

## D3. Scope — which EPs ship now

**The question.** Which optional EPs does this port include in v1?

**What's at stake.** The required core is non-negotiable. Optional EPs are declared yes/no per the [Implementor-Checklist Part 1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#part-1--how-complete). Each "yes" gates a substantial chunk of additional implementation work. (Checklist Part 1 enumerates **Q1–Q7**; **Q8 Flows / Q9 Managed HTTP / Q10 Resources** below extend that scheme to the post-checklist optional EPs 013 / 014 / 016 — gate them off the owning EP spec, not the checklist's Q-list.)

**Required (every port ships these).** Identity primitive, persistent data structures, registry by `(kind, id)`, event handler contract, closed effect-map shape, subscription system, frame as runtime boundary, run-to-completion drain, view contract, trace event stream, error contract, conformance corpus consumption. Per [Implementor-Checklist Part 1 §Required](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#required-not-gated-every-implementation-ships-these).

**Optional (declare yes/no for each).**

- **Q1 — State machines** ([EP 005](https://day8.github.io/re-frame2/spec/005-StateMachines/)). Substantial. The CLJS reference claims hierarchical compound + `:always` + `:after` + `:fsm/tags` + `:fsm/parallel-regions` + `:fsm/choice` + `:fsm/internal-events` + `:fsm/timeout` (EP-0029) + own-state + spawn/destroy + cross-actor `:fx` + declarative `:spawn` + spawn-and-join + `:system-id`. Smaller ports claim less.
- **Q2 — Routing** ([EP 012](https://day8.github.io/re-frame2/spec/012-Routing/)). `reg-route`, `match-url`, navigation tokens.
- **Q3 — SSR** ([EP 011](https://day8.github.io/re-frame2/spec/011-SSR/)). `:platforms` metadata, `render-to-string`, hydration-mismatch detection.
- **Q4 — Schemas** ([EP 010](https://day8.github.io/re-frame2/spec/010-Schemas/)). Three answers, not two: *yes-runtime-schema*, *yes-via-host-types*, *no*.
- **Q5 — Stories** ([EP 007](https://day8.github.io/re-frame2/spec/007-Stories/)). Storybook/devcards-class tooling. Post-v1 in the CLJS reference too.
- **Q6 — Tool-Pair adapters** ([Tool-Pair.md](https://day8.github.io/re-frame2/spec/Tool-Pair/)). REPL-attached AI inspection surface. **Mostly reproduction of EP surfaces you already build** (registrar query, trace stream, hot-swap, `:fx-overrides`, source coords, `compute-sub`, `flush-render!`) — the genuinely-new obligations a `yes` adds are the **time-travel / epoch surface** (`epoch-history` / `restore-epoch!` rewinding the whole frame-state / `replace-frame-state!` injection), **direct-read egress through `project-egress`** (tool reads bypass the EP 015 trace-stamp boundary), the optional **derivation graph-inspection** capability, and the **tooling-security obligations** (editor-URI allowlist, file-path boundary). See [`phase-2-impl-order.md` §Tool-Pair attachment surface](phase-2-impl-order.md#tool-pair-attachment-surface-if-d3-q6-is-yes).
- **Q7 — AI-Audit grading** ([AI-Audit.md](https://day8.github.io/re-frame2/spec/AI-Audit/)). Self-grading discipline doc.
- **Q8 — Flows** ([EP 013](https://day8.github.io/re-frame2/spec/013-Flows/)). Declarative derived-state cells (`reg-flow`) recomputed topologically off `app-db`, with their own trace stream and frame-scoped lifecycle. Gates the `:flow/*` conformance family.
- **Q9 — Managed HTTP** ([EP 014](https://day8.github.io/re-frame2/spec/014-HTTPRequests/)). The `:rf.http/managed` fx — transport, decode, retry-with-backoff, abort, reply addressing — riding the [Managed-Effects](https://day8.github.io/re-frame2/spec/Managed-Effects/) lifecycle. Gates the `:rf.http/managed` conformance family.
- **Q10 — Resources** ([EP 016](https://day8.github.io/re-frame2/spec/016-Resources/)). Post-v1. Declarative cached server-state — re-frame2's answer to TanStack/RTK-Query. Adds the late-bound registrar kinds `:resource` / `:mutation` / `:resource-scope` (`reg-resource`, `reg-mutation`, `reg-resource-scope`), the keyword-addressed `:rf.resource/*` / `:rf.mutation/*` events, subs, and accessors, scoped + fail-closed cache identity, ownership/staleness/GC, scoped tag invalidation (per-target descriptors), call-site mutation-completion `:reply-to` continuations, and SSR preload/hydration. **Depends on Q9 Managed HTTP** — a resource/mutation `:request` lowers onto `:rf.http/managed`, and request decoration (auth/retry) reuses the `reg-http-interceptor` seam rather than a new resources surface. Substantial; do not attempt before the 014 family passes. Gates the `:resources/*` conformance family (reads — six `resources-*.edn` fixtures; the mutation half is spec-mandated but corpus-behind).

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

**Options.** CLJS keywords (the reference); Squint keywords (Squint preserves the CLJS shape); TS branded strings with interning; Fable polymorphic variants or single-case DUs; Kotlin/JS sealed-class hierarchies or value classes; PureScript newtypes; Scala.js sealed objects or value classes; Melange / ReScript / Reason polymorphic variants or an opaque `Id.t`.

**Rejected upfront.** UUIDs, integer ids, reference-equality classes — all violate one or more required properties.

**Where the spec speaks.** [`spec/000-Vision.md` §The identity primitive — required properties](https://day8.github.io/re-frame2/spec/000-Vision/#the-identity-primitive--required-properties) and the per-host realisation table.

#### F2 Persistent data structures

**The question.** What does `app-db` (and every snapshot of it) physically live in?

**What's at stake.** Frame state revertibility ([Goal 3 in 000](https://day8.github.io/re-frame2/spec/000-Vision/#frame-state-revertibility)) requires structural sharing. Without persistent structures, snapshot is deep-copy and revert is expensive.

**Options.** Clojure persistent collections (CLJS, Squint); Immer / mori / Immutable.js (JS/TS); native persistent collections in Fable, PureScript, Scala.js, Melange / ReScript / Reason; im.kt or kotlinx.collections.immutable (Kotlin/JS).

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

**Options.** The single-threaded JS event loop — every in-scope host cross-compiles to JS, so this is the shared concurrency model (CLJS, Squint, TS / JS, Fable, Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason). Async fx ride the host's Promise / microtask primitive; cross-frame dispatch is serialised per frame by the run-to-completion drain.

**Where the spec speaks.** [`spec/002-Frames.md` §Run-to-completion dispatch drain semantics](https://day8.github.io/re-frame2/spec/002-Frames/). [Implementor-Checklist §F5](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f5-concurrency-model).

#### F6 Hot-reload primitive

**The question.** How does re-registration surgically replace registry entries without restarting the runtime?

**Options.** figwheel/shadow-cljs (CLJS); Vite HMR (JS/TS) — and the analogous Vite-HMR-compatible source-build pipeline for each in-scope host (Squint, Fable, Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason).

**Constraint.** Re-registration emits `:rf.registry/handler-replaced` per [`spec/001-Registration.md` §Hot-reload semantics](https://day8.github.io/re-frame2/spec/001-Registration/). Frame state preserved across re-construction via `make-frame`.

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

**What's at stake.** Test fixtures, epoch history (`epoch/restore-epoch!` + `epoch/replace-frame-state!`), and time-travel all depend on snapshot/restore being a value swap. With persistent collections (F2) a snapshot is a pointer and restore is `replace-container!`; without them, snapshot is deep-copy and expensive — this is why F2 is pattern-required.

**Where the spec speaks.** [Implementor-Checklist §S2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#s2-snapshotrestore-mechanism).

#### S3 Path-access primitive

**The question.** What provides `assoc-in` / `update-in` / `get-in` over the frame's app-db?

**What's at stake.** Used by handlers, the `path` standard interceptor, registered subs that read paths, and a path-scoped read over `(rf/app-db-value frame-id)`. Path operations are hot — choose a fast implementation.

**Options.** Native `assoc-in` / `update-in` / `get-in` (CLJS, Squint); Immer `produce` / `lodash.set`-immutably (TS / JS); lens helpers over the host's immutable map (`Belt` for Melange / ReScript / Reason; F# `Map`; Monocle for Scala.js; `purescript-profunctor-lenses`; Arrow Optics for Kotlin/JS) per host.

**Where the spec speaks.** [Implementor-Checklist §S3](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#s3-path-access-primitive).

### Subscriptions (Sub1–Sub2)

From [Implementor-Checklist §Subscriptions](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#subscriptions-always-required). Both fall out of F3 + S1, but record them explicitly.

#### Sub1 Signal graph + caching

**The question.** What backs the subscription DAG, and how is the per-query cache keyed?

**What's at stake.** Subscriptions form a DAG over `app-db`; values cache per `=`-equality. Every sub is one of three input-fn producers: `:db` (layer-1 — reads `app-db` directly, no producer), `:static` (the literal `:<-` producer), or `:parametric` (an `input-fn` producer that computes its inputs from the outer `query-v`). **Equality-by-value is required** for cache invalidation — identity-only equality breaks the contract.

**Where the spec speaks.** [Implementor-Checklist §Sub1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#sub1-signal-graph--caching). [`spec/006-ReactiveSubstrate.md` §Subscription input producers](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#subscription-input-producers--app-db-reader-static-parametric-input-fn) and §Subscription cache. [`spec/API.md` §`reg-sub` input-production modes](https://day8.github.io/re-frame2/spec/API/#reg-sub-input-production-modes).

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

**What's at stake.** Lifecycle should run the frame's `:initial-events` (mount-time) and fire `:on-destroy` (unmount-time) on the surrounding frame, integrating with the run-to-completion drain.

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

**The question.** How does the **dev trace surface** — the `register-listener!` registry, the rich trace-event emit sites, the retain-N ring buffer, and the perf bridge — elide in production, while the **always-on event/error-emit substrates survive** the same production build?

**What's at stake.** Only the *dev trace surface* is dev-only and elided; production builds must DCE it entirely (no listener, no allocation, no overhead). Mechanism is host-discretion (Closure DCE for CLJS via `re-frame.interop/debug-enabled?`; Vite `define` + tree-shake for TS/Squint; `#if !DEBUG` + tree-shake for Fable; link-time-`if` for Scala.js; release-variant module omission for Kotlin/JS). **But the always-on event-emit / error-emit substrates are explicitly NOT elided** — they survive `:advanced` + `goog.DEBUG=false` (and JVM `-Dre-frame.debug=false`) because they back the production observation path (see E2 below). At the **public** facade these two substrates are reached through the single stream-parameterized listener verb — `register-listener! :events` and `register-listener! :errors` (closed stream vocabulary `:trace`/`:events`/`:errors`/`:epoch`); the production off-box default is a separate verb again, the frame-owned `:observability` sink wired with `register-observability-sink!` (see E2 + D5b). Decide explicitly how your host's DCE scopes to the trace surface alone, leaving the always-on substrates on a separate ungated path. Copy the CLJS reference's CI sentinel-string verifier pattern — and pair it with a positive assertion that the always-on substrates remain present in the production bundle (an over-aggressive DCE that takes them out is the expensive failure mode).

**Where the spec speaks.** [Implementor-Checklist §T3](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#t3-production-elision). [`spec/009-Instrumentation.md` §Production builds](https://day8.github.io/re-frame2/spec/009-Instrumentation/#production-builds-zero-overhead-zero-code) and §The three always-on substrates / §What is available in production.

### Errors (E1–E2)

From [Implementor-Checklist §Errors](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#errors-always-required).

#### E1 Error capture / recover

**The question.** How are handler / fx / sub exceptions, schema-validation failures, and drain-depth-exceeded caught, classified, and reported?

**What's at stake.** Try/catch around handler bodies, fx invocations, sub computations. Capture must NOT swallow errors silently — every catch fires a structured trace event with `:operation :rf.error/<category>` and `:op-type :error`.

**Where the spec speaks.** [Implementor-Checklist §E1](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#e1-error-capture--recover). [`spec/009-Instrumentation.md` §Recovery contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#recovery-contract).

#### E2 Error reporting to tools

**The question.** How do tools consume errors, and where does error policy live — **in dev AND in production**?

**What's at stake.** Errors flow on **two surfaces** (T1's dev trace stream gates off in production; the always-on error-emit substrate does not). In dev, tools branch on `:op-type :error` and `:operation` prefix over the trace stream. In **production**, the always-on error-emit substrate keeps firing one error-record per `:rf.error/*` cascade error (handler / coeffect / interceptor / flow-eval exceptions, fx errors) post-elision, and it is **NOT trace-only**.

There are **two distinct public ways** to consume that production error stream — keep them straight:

- **The NORMAL off-box error path is the frame-owned `:observability :errors` sink.** Declare the policy on the frame config and wire the concrete sink fn with `register-observability-sink!`. The sink receives an **already-projected** record (sensitive paths redacted, the host `:exception` dropped under `:rf.egress/public-error`) — this is the privacy/egress-respecting Sentry / Rollbar / hosted-monitor path, and the one a port should reach for first.
- **`register-listener! :errors` is the ADVANCED corpus-wide hook**, NOT the off-box default. It fans the record across EVERY frame UNPROJECTED (`:event` wire-elided, but `:exception` rides RAW under no frame egress policy), for an intentionally cross-frame post-mortem shipper that needs the raw throwable + stack. Reach for it only when the frame-sink routing does not carry the record.

This is the single error-observability surface; recovery is the framework's typed per-category default — there is **no app-steering recovery policy**. Per-listener exceptions are isolated (one listener / sink throwing must not abort the cascade). Strings-as-errors are out — every error has an `:operation` namespaced keyword and a `:tags` map. Design the error path so it does NOT ride only the dev-elided trace surface, or production error reporting silently vanishes. The public listener surface is the stream-parameterized `register-listener!` / `unregister-listener!` verb; there are no `register-(event|error)-listener!` / `unregister-(event|error)-listener!` facade functions — those bare names are internal namespace functions only (`re-frame.event-emit` / `re-frame.error-emit`), never on the public facade.

**Where the spec speaks.** [Implementor-Checklist §E2](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#e2-error-reporting-to-tools). [`spec/009-Instrumentation.md` §Error contract](https://day8.github.io/re-frame2/spec/009-Instrumentation/#error-contract) and §What is available in production (the always-on error-emit substrate).

---

## D5. Schema mechanism

**The question.** How does the port describe the shapes flowing through the runtime?

**Three answers, not two.**

- **Yes-runtime-schema.** Use a host-native schema library — Malli (CLJS), Zod (JS/TS, Squint). The bulk of that validation is a development tool: the validator family sits behind the host's debug gate and is eliminated from a release build. **Two checks are not, and eliding them makes the port non-conforming** — see the second constraint below. This is the natural answer for the dynamically-typed in-scope hosts (CLJS, Squint).
- **Yes-via-host-types.** Use the host's type system — TypeScript types, F# discriminated unions, Kotlin/JS sealed classes, Scala case classes, PureScript sum types, Melange / ReScript / Reason variants. The compiler enforces shapes at build time; runtime boundary validation (e.g. Zod for TS) is optional. The natural answer for the statically-typed in-scope hosts.
- **No.** Skip schemas entirely. Permitted by the spec but rarely the right call — the schema layer is what AI agents read to learn the runtime's shapes.

**Constraint.** **Open shapes** are non-negotiable. Consumers tolerate unknown keys; producers grow shapes additively. Closed records/structs at the runtime-data layer are out per [Goal 5 — Clojure ethos](https://day8.github.io/re-frame2/spec/000-Vision/#goals).

**Constraint.** Under `yes-runtime-schema`, **do not gate every check on the debug flag.** The bulk validators — event vectors, sub returns, fx arguments, `app-db` paths — do sit behind the host's debug-enabled gate and elide from a release build; that is the designed posture, and production trusts the programmer. Two checks in that core path deliberately sit *outside* it (subsystems decided elsewhere — managed HTTP decoding, route-shape validation — carry their own always-on checks too). The boundary interceptor (`:rf.schema/at-boundary`) is the important one: it exists precisely so a handler taking untrusted input still validates in production, so its check must be ungated, and a rejection there must skip the handler on every build. Recordable-coeffect value validation is the other, and it is a hard error rather than a trace, because an out-of-contract value folded into the durable causal record is corrupt state. A port that gates these with the rest ships no always-on validation at all — read [`spec/010-Schemas.md` §Production builds](https://day8.github.io/re-frame2/spec/010-Schemas/#production-builds) before wiring the gate.

**Constraint.** A boundary rejection **declines and reports**, and the second half is an obligation rather than a choice. A check that refuses silently in a release build is an opt-in security gate its operator cannot observe — the port ships the gate and then hides every trip of it, so nothing counts the rejections, nothing alerts on a probe, and an SSR endpoint answers `200` over a request it refused. So wire both axes. The rejection fans **one always-on error record** on the ungated error substrate (D3 / E2 above), and the always-on event record for that dispatch settles an outcome meaning *rejected* rather than the clean settle a handler-less run would otherwise report. Where the port also renders server-side, that record is what an error projector turns into a `400`; it needs no separate SSR code path. **Build the record from identifiers, never from the rejected payload.** A boundary payload is attacker-controlled or user-private by definition and can carry secrets under keys the declared schema never named, so no schema-aware redactor can be trusted to have seen them: a structural-only record — the failing id, the schema id, the frame, the recovery — is *stricter* than a scrub, not weaker. The rich diagnosis that names the offending value belongs on the dev trace surface and elides with it. Emit both axes from **one** site so a single rejection can never produce two records. Per [`spec/Security.md` §Input validation / boundary parsing](https://day8.github.io/re-frame2/spec/Security/#input-validation--boundary-parsing) and [`spec/009-Instrumentation.md` §Error event catalogue](https://day8.github.io/re-frame2/spec/009-Instrumentation/#error-event-catalogue). (The CLJS reference fans the record from its router's pipeline tail, off a marker the interceptor stamps.)

The general rule behind both constraints is [C-000.35](https://day8.github.io/re-frame2/spec/000-Vision/#contract--pattern-obligations): what may be elided is settled by **what the check is for**, not by who declared the schema it reads. An ordinary registration diagnostic goes; a check the framework relies on to keep a promise of its own stays, whoever wrote the schema it consults — and surviving is not the same as reporting, which is why this one surface owes both.

**Where the spec speaks.** [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/). [Implementor-Checklist §Schemas](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#schemas-if-q4-is-yes).

---

## D5b. Data classification (Sensitive + Large) — v1-required

**The question.** How does the port realise **owner-owned** data classification — frame-owned `:sensitive` / `:large` for durable app-db, registration-owned `:sensitive` / `:large` for transient payloads, per-slot `:sensitive?` / `:large?` schema props for owner-local schema'd data (machines / resources / HTTP bodies) — and the single boundary projection (`project-egress` + the `:rf.egress/*` profiles) that elides marked values before any off-box sink?

**This is NOT D3-gated.** Spec 015 marks itself **v1-required**. Unlike schemas (D5, which has a `no` answer), there is no opt-out: a port that ships without 015 leaks classified values through trace, observability sink records, Xray, MCP, and log sinks. Decide the *mechanism*, not whether to ship it.

**The owner rule (the whole model in one line).** Classification is attached to *whoever owns the data's shape* and is always a **path** declaration; there is one declaration surface per owner, and the contract is **fail-open** — a path you never classify ships raw (the one-name-per-fact discipline of EP-0007, hygiene not security):

- **Durable app-db facts → commit-plane effects from the writing event.** A handler returns one or more of `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` (each a vector of `:rf/path` vectors) alongside `:db`; known paths in the frame's init event, discovered ones in the handler that writes them. There is **no** frame `:sensitive {:app-db …}` annotation (rejected fail-loud), **no** schema-prop route to durable app-db, and **no** imperative `add-marks` / `set-marks` / `clear-app-db-marks!` API.
- **Subsystem instance data → projection-relative declaration on the subsystem.** Machine `:data` and resource `:data` / params classify via top-level `:sensitive` / `:large` keys on the `reg-machine` / `reg-resource` **definition**, projection-relative to one instance's shape (e.g. `[:data :token]`), lowered per instance at spawn/fetch. Machine `:data` SNAPSHOT egress is this declaration, **not** the machine's `[:schemas :data]` schema. The machine data schema is declared at `[:schemas :data]` (there is no `:data-schema` key — EP-0029). `reg-machine` is `(reg-machine machine-id machine-spec)` / `(reg-machine machine-id opts machine-spec)`; the `:sensitive` / `:large` keys ride the machine-spec map.
- **Schema-owned transient products → per-slot schema props.** An HTTP request's decoded body classifies via `:sensitive?` / `:large?` Malli props on its `:decode` schema; the same props on a machine's `[:schemas :data]` (and a resource's `:data-schema` / `:params-schema`) drive **only** the schema's own validation-FAILURE-trace redaction. Schemas own *transient* and *validation-failure* products, never durable state.
- **Transient registration payloads → registration metadata.** `reg-event` (the one public event registrar — EP-0018), `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow` accept `{:sensitive [path…] :large [path…]}` on their registration map (paths index into that registration's primary shape — the event arg-map, fx-input map, sub output, flow output; `[[]]` marks the whole shape). A secret carried positionally has no named path — prefer the map payload.

**What's at stake.** The full contract (commit-plane fold, no-propagation, `project-egress` + the closed six `:rf.egress/*` profiles, wire-marker-vs-display-sentinel, frame-owned observability, fail-open classification + fail-closed projection) is the canonical statement in [`phase-2-impl-order.md` §EP 015 — Data Classification](phase-2-impl-order.md#ep-015--data-classification-sensitive--large); don't re-derive it. The four facts a Phase-1 decision turns on:

- **Commit-plane fold.** The four effects apply **with the `:db` write** into `[:rf.runtime/elision …]` (NOT a post-commit `:fx`); a malformed payload fails loud pre-commit (`:rf.error/classification-effect-shape`).
- **No propagation.** No input→output inheritance / taint engine; a re-keyed/unclassified path ships raw (fail-open).
- **One boundary — `project-egress`.** The public record-level primitive over the closed six `:rf.egress/*` profiles; substitution to the wire markers happens only at projection; **sensitive wins over large**.
- **Frame-owned observability, fail-closed.** `register-observability-sink!` sinks consume already-projected records; a frameless projection redacts rather than leaks (no `:rf/default` synthesis, EP-0002).

**Where the spec speaks.** [`spec/015-Data-Classification.md`](https://day8.github.io/re-frame2/spec/015-Data-Classification/) (the full contract — the commit-plane effects, the subsystem projection-relative declarations, registration-owned transient classification, the schema-owned transient products, no-propagation, the `:rf.egress/*` enum, `project-egress`, fail-open classification + fail-closed projection). [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) `project-egress` / `elide-wire-value` / `register-observability-sink!` (none of `add-marks` / `set-marks`, the frame `:sensitive {:app-db …}` annotation, `redact-interceptor`, `declare-sensitive-*`, or value propagation is public). [`spec/Conventions.md` §Reserved namespaces / §Reserved commit-plane classification effects](https://day8.github.io/re-frame2/spec/Conventions/) (the four effects, the `:rf.runtime/elision` registry, the reserved `:rf/redacted` / `:rf/large` sentinels). [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/) §The trace event model (the emission hook the overlay rides). Per EP-0025.

---

## D6. Integration story

**The question.** Is the port a standalone library? Or does it integrate with a larger framework?

**Options.**

- **Standalone library.** Drop-in for an existing app; the consumer wires the runtime, the substrate adapter, and any per-feature artefacts.
- **Framework integration.** The port plugs into an existing React-based framework's lifecycle — e.g. React Native, Next.js, Remix, or any host-specific React meta-framework. The framework's React render cycle is the trigger for `re-frame2`'s view recompute.
- **Embedded.** The runtime is embedded inside a larger process that drives a React surface (a micro-frontend shell, an SSR server feeding the same React tree). The port consumes events + subs + fx + app-db as a state-management substrate beneath that React surface.

**How to choose.** Driven by the engineer's downstream consumer. Standalone is the lowest-friction starting point.

---

## D7. Conformance capability tag set

**The question.** Given Phase 1's scope, which capability tags from the conformance corpus does the port claim?

**What's at stake.** The claim is the harness's filter — the corpus runs exactly the fixtures whose `:fixture/capabilities` are a subset of the claim, and the score (`passed / claimed-applicable`) is measured against it. Claim too little and you under-test a surface you shipped; claim a tag with no runtime backing and the suite fails. The claim is also the port's public conformance statement, captured in the decision record's D7 and copied into the port README.

**How to choose.** Three families are **always claimed** — `:core/*`, `:identity/*`, `:data-classification/*` (the last two are v1-required, not D3-gated). Every other family maps to a **D3 scope question** — claim it iff you answered that question `yes` — and then you **derive the exact tags from the fixtures at the pinned corpus commit**, because the fixtures (not any prose list) are authoritative for what runs. A deliberately-unclaimed capability goes on the harness's `known-skipped-capabilities` allowlist; a fixture whose capability is in neither the claim nor the allowlist must FAIL the suite, never skip silently (see [`conformance.md` §The two out-of-claim flavours](conformance.md#the-two-out-of-claim-flavours)).

**One owner for the mechanics — don't re-derive here.** The family → D3-scope map, the grep-the-fixtures derivation per family, the static-host schema refinement, and the corpus/spec divergence rule (the common corpus-ahead case plus the corpus-behind `:actor/*` exception) all live in one place: [`conformance.md` §Capability tagging](conformance.md#capability-tagging). Read it once from here; this walkthrough deliberately does not repeat the tag catalog. Record the *result* — claimed / known-skipped / fixture-less self-tested / score — in the decision record's D7 fields ([`decision-record.md` §D7](decision-record.md)).

**Where the spec speaks.** [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/) — the full capability-tagging table and the harness contract.

---

## After Phase 1

When every decision above has been answered and captured in the decision record:

1. Commit `DECISIONS.md` (the filled-in record) to the port's repo.
2. Move to Phase 2 — `phase-2-impl-order.md` walks the EP corpus in dependency order, with the Phase 1 record as the source of truth for every host-specific binding.
