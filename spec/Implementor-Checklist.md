# Implementor's Checklist

> **Type:** Reference / Companion
> A consolidated decision list for porting re-frame2 to a new host language. The checklist is the structured form of [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone): an AI armed with `/spec/` plus this checklist plus the [conformance corpus](conformance/README.md) should be able to produce a working v1 implementation in any in-scope host without consulting outside sources.

> **Scope.** "Host" here means one of the eight in-scope JS-cross-compile-to-React+VDOM languages defined in [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic): ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, Fable (F#), Squint, Scala.js, PureScript, Kotlin/JS. Non-React substrates (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit) and non-cross-compile-to-JS host languages (server-side Python, Ruby, Rust, Go, Kotlin, Swift, Java) are **out of scope** as first-class implementation targets. Where this doc refers to those non-target hosts (e.g. mentioning `pytest` or `tokio`), treat the mention as **non-normative background** — illustrative shape, not an implementation track this checklist sequences.

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

**Declaring yes implies** picking an FSM-richness capability list and an actor-model capability list per [005 §Capability matrix](005-StateMachines.md#capability-matrix). The CLJS reference claims flat-FSM + hierarchical compound + `:always` + `:after` + `:fsm/tags` + `:fsm/parallel-regions`, plus own-state + spawn/destroy + cross-actor `:fx` + declarative `:invoke` + spawn-and-join (`:invoke-all`) + `:system-id`. Smaller ports can claim less; conformance grades against the claimed list.

**Gate:** does the application surface include state-bearing flows that the events+subs+fx triad makes awkward (auth flows, multi-step wizards, drag-and-drop, timer-driven transitions)?

#### Q2. Routing?

The URL ↔ frame state contract from [012](012-Routing.md) — `reg-route`, `match-url`, `route-link`, `:rf.nav/push-url` fx, `:rf/pending-navigation`, navigation tokens, fragment handling.

**Gate:** is the application a routed SPA? Component libraries, devtools, single-screen apps don't need routing.

#### Q3. SSR?

The server-side render + hydration contract from [011](011-SSR.md) — `:platforms` metadata, `render-to-string`, `:rf/hydrate`, hydration-mismatch detection.

**Gate:** does the deployment require server rendering (SEO, time-to-first-byte, social previews) or full-stack hydration?

#### Q4. Schemas?

Boundary validation + introspection from [010](010-Schemas.md) — `:spec` registration metadata, `reg-app-schema`, validation-failure trace events.

In **dynamically typed in-scope hosts** (CLJS, Squint) this is a runtime schema layer — Malli (CLJS reference) or Zod (Squint). In **statically typed in-scope hosts** (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS) the host's type system covers much of the territory at compile time. A static-host port may also ship a runtime validation library (Zod alongside TS types) or rely on types alone.

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
- **Options by host** — canonical choices for the eight in-scope JS-cross-compile hosts (CLJS, TS, Melange / ReScript / Reason, Fable, Squint, Scala.js, PureScript, Kotlin/JS). Non-target hosts (Python, Rust, Swift, Java) may appear as **non-normative background** to illustrate shape; they are not implementation tracks this checklist sequences.
- **Reference-impl picks** — what re-frame-cljs uses; what other claimed reference implementations would pick.
- **Trade-offs** — criteria for choosing.

### Foundation (always required)

#### F1. Identity primitive

- **Why it matters.** Every queryable, every override, every trace event, every error category is identified by an id. The runtime looks up, compares, ships, and reflects on ids cheaply. See [000 §The identity primitive](000-Vision.md#the-identity-primitive--required-properties) for the seven required properties.
- **Options by host.**
  - **CLJS** — Clojure keywords (`:foo/bar`). Native; satisfies all properties.
  - **TypeScript** — Branded string types (`type EventId = string & { readonly __id: 'EventId' }`) with a naming convention (`'cart.item/remove'`). Use a small `id()` helper with interning + namespace parsing. ES `Symbol.for(...)` is *not* a fit — symbols don't serialise.
  - **Melange / ReScript / Reason** — Polymorphic variants (`` `Cart_item_remove ``) for closed sets, or strings wrapped in an opaque `Id.t` with namespace parsing for open sets.
  - **Fable (F#)** — Discriminated unions for closed id sets, or a single-case DU wrapping `string` (`type EventId = EventId of string`) with namespace-parsing helpers.
  - **Squint** — Same as ClojureScript — Squint preserves Clojure keywords.
  - **Scala.js** — Sealed `case object` hierarchies for closed sets, or value classes (`final class EventId(val s: String) extends AnyVal`) with namespace-parsing helpers.
  - **PureScript** — Sum types for closed sets, or a `newtype EventId = EventId String` with `Eq`/`Ord` instances and namespace-parsing helpers.
  - **Kotlin/JS** — Sealed-class hierarchies of `data object` ids, or value classes wrapping `String` with namespace parsing.
- **Reference-impl picks.** CLJS uses keywords. A TypeScript reference would use branded strings + interning.
- **Trade-offs.** The seven properties (stable, namespaceable, value-equal, cheap, serialisable, human-readable, reflective) are non-negotiable. If a host's natural choice violates any property, pick a different mechanism — UUIDs, integer ids, and reference-equality classes are all rejected upfront.

#### F2. Persistent data structures

- **Why it matters.** Pulled from "encouraged" to **pattern-required** by [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility). Structural sharing makes "reverting" cheap (a pointer swap, not a deep copy). Without persistent structures the goal is unaffordable.
- **Options by host.**
  - **CLJS** — Clojure persistent collections (native).
  - **TypeScript** — Immer (copy-on-write) or [mori](https://swannodette.github.io/mori) or Immutable.js.
  - **Squint** — Same as ClojureScript — Squint preserves Clojure persistent collections.
  - **Melange / ReScript / Reason** — Native immutable records + `Belt.Map` / `Belt.Set`; or [bs-immutable](https://github.com/MoOx/bs-immutable)-style libraries for richer structural sharing.
  - **Fable (F#)** — Native F# records + `Map` / `Set` (immutable by default); structural sharing via the .NET-mapped persistent collections.
  - **Scala.js** — Scala's immutable `Map` / `Vector` / `Set` (native, persistent with structural sharing).
  - **PureScript** — `purescript-maps` / `purescript-ordered-collections` — native persistent maps with O(log n) updates.
  - **Kotlin/JS** — [im.kt](https://github.com/im-co/im.kt) or `kotlinx.collections.immutable`.
- **Reference-impl picks.** CLJS uses Clojure persistent collections.
- **Trade-offs.** Hosts without a mainstream persistent-collection library face a real cost. Defaulting to deep-copy snapshots is technically correct but performance-prohibitive at any scale. Pick a library and budget time to verify its sharing characteristics under the JS-engine GC.

#### F3. Reactive substrate

- **Why it matters.** The runtime's reactive container for `app-db`, the change-tracking that drives view re-renders, and the render-tree → surface step. Substrate-decoupled per [006](006-ReactiveSubstrate.md). Adapter contract is locked at six required + two optional + one lifecycle function, with a [§Revertibility constraint](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters) that adapter-internal state must be derivable from the frame value.
- **Options by host.** Every in-scope host targets React, so the substrate is the host's React-binding's state-and-reactivity bridge over the framework's container.
  - **CLJS** — Reagent (default; atop React) or plain-atom (JVM/headless/SSR). Other CLJS adapters (UIx, Helix) plug in via the same contract.
  - **TypeScript** — `useSyncExternalStore` against a hand-rolled atom-shaped store, or a signal library bridged through it (Solid `createSignal` + `createMemo`, MobX, Zustand, Jotai).
  - **Melange / ReScript / Reason** — Melange-React / ReasonReact `useState` + `useSyncExternalStore` bridge against a Belt/Map-shaped container.
  - **Fable (F#)** — Fable.React / Feliz `useState` + `useSyncExternalStore` against an F# `IObservable` or hand-rolled store.
  - **Squint** — Same shape as the CLJS reference — Squint preserves the Reagent / atom-shape contract.
  - **Scala.js** — scalajs-react / Slinky `useState` + `useSyncExternalStore` against a Scala `Var`/`Signal` (slinky-state, Laminar's `Var` if dropping into Laminar adapter territory).
  - **PureScript** — React.Basic / Halogen-React `useState` + `useSyncExternalStore` against a `Ref` or signal-shaped abstraction.
  - **Kotlin/JS** — kotlin-react `useState` + `useSyncExternalStore` against a `MutableStateFlow`-shaped container.
- **Reference-impl picks.** CLJS uses Reagent (browser) and plain-atom (JVM).
- **Trade-offs.** Pick one signal library per port; the spec is single-adapter-per-process (per [006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)). Multi-adapter coexistence is post-v1.

#### F4. Effect-handling primitive

- **Why it matters.** How `:fx` dispatches; sync vs async handling; effect resolution against the registry.
- **Options by host.** All eight in-scope hosts compile to JS, so the underlying primitives are uniform — `setTimeout` / `Promise` / `queueMicrotask` for async, sync-by-default for the registered handler invocation.
  - **CLJS** — `reg-fx` registered handlers; sync effects run inline, async effects schedule via host setTimeout/Promise; `:dispatch` and `:dispatch-later` ship as standard fx.
  - **TypeScript / Melange / ReScript / Reason / Fable / Squint / Scala.js / PureScript / Kotlin/JS** — Same shape; each host calls the host's mapping over `setTimeout` / `Promise` / `queueMicrotask`. The dispatch primitive is host-data ([event-vector] → effects-map) and the effect resolver is registry-lookup at the JS layer — uniform across the eight.
- **Reference-impl picks.** CLJS uses `reg-fx` with sync default; standard `:dispatch` / `:dispatch-later` / `:http` (per [Pattern-RemoteData](Pattern-RemoteData.md)).
- **Trade-offs.** Sync-by-default keeps the drain semantics simple (per [002 §Run-to-completion](002-Frames.md)). Async fx must NOT escape the drain — they re-enter via `:dispatch` after their underlying side effect completes.

#### F5. Concurrency model

- **Why it matters.** Run-to-completion drain semantics ([002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)) are the spine of [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility). The implementation must guarantee no async mutation escapes the dispatch loop.
- **Options by host.** Every in-scope host runs on the single-threaded JS event loop in production; the run-to-completion guarantee comes for free at the runtime layer.
  - **CLJS** — Single-threaded JS event loop guarantees this for free in browsers; on JVM (the CLJS reference test harness), the harness runs sync.
  - **TypeScript / Melange / ReScript / Reason / Fable / Squint / Scala.js / PureScript / Kotlin/JS** — Single-threaded JS event loop (browser, Node main thread). All eight share the same concurrency-model guarantee.
- **Reference-impl picks.** CLJS relies on the JS event loop.
- **Trade-offs.** **No core.async** — the CLJS reference does not use core.async, and ports inherit this directive. Async fx are scheduled via host primitives (Promise, setTimeout); cross-frame dispatch is serialised per frame.

#### F6. Hot-reload primitive

- **Why it matters.** Re-registration replaces; emits `:rf.registry/handler-replaced` (per [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics)). Pair-tool hot-swap depends on this.
- **Options by host.** Every in-scope host has a working hot-reload story via its source-build pipeline; the registrar-update pattern is uniform.
  - **CLJS** — figwheel/shadow-cljs reload; `reg-*` calls surgically update the registrar; frames preserve runtime state via `reg-frame`'s update path (per [002](002-Frames.md)).
  - **TypeScript** — Vite HMR + module-replacement boundary; same registrar update pattern.
  - **Melange / ReScript / Reason / Fable / Scala.js / PureScript / Kotlin/JS** — Each has its own Vite-HMR-compatible source-build pipeline (`@melange/runtime`, `vite-plugin-fable`, `@scala-js/vite-plugin`, `purescript-vite`, `kotlin-react-vite`) that routes module-replacement notifications into the registrar.
  - **Squint** — Squint's `vite-squint` plugin gives the same dev experience as TypeScript; `reg-*` calls re-bind in the registrar atom on module replacement.
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
  - **TypeScript** — Immer's `produce` for `update-in`-style; `lodash.get` / `lodash.set` immutably wrapped; or hand-rolled.
  - **Squint** — Same as CLJS — Squint compiles `assoc-in` / `update-in` / `get-in` to JS persistent-map operations.
  - **Melange / ReScript / Reason** — Hand-rolled lens helpers over `Belt.Map`; functional update is idiomatic.
  - **Fable (F#)** — F# `Map` lens helpers; pattern-matching makes hand-rolled path ops cheap.
  - **Scala.js** — Monocle (or quicklens) for lens-shaped path access over Scala immutable collections.
  - **PureScript** — `purescript-profunctor-lenses` (`Optics`-style); cleanly composable.
  - **Kotlin/JS** — Arrow Optics, or hand-rolled per-shape functions over `kotlinx.collections.immutable`.
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
- **Options by host.** Every in-scope host targets React + VDOM, so the render-tree shape is the host's idiomatic data-form over `createElement`.
  - **CLJS** — Hiccup (`[:div {:class "foo"} child]`).
  - **TypeScript** — JSX-as-data (with TSX transform, JSX literally is `React.createElement(...)` calls; for pure-data SSR use snabbdom-style vnodes or a hiccup port).
  - **Melange / ReScript / Reason** — JSX-PPX → `React.createElement` calls; the JSX syntax is data-shaped at the AST layer.
  - **Fable (F#)** — Feliz DSL (`Html.div [...]`) over `React.createElement`; or Fable.React's plain `div [] [...]` shape.
  - **Squint** — Hiccup (Squint's CLJS-style render tree).
  - **Scala.js** — slinky JSX-DSL or scalajs-react's `<.div(...)` DSL — both are data trees over `createElement`.
  - **PureScript** — React.Basic's `R.div [] [...]` shape, or Halogen-React JSX-DSL.
  - **Kotlin/JS** — kotlin-react's `div { ... }` HTML-DSL — data-shaped through Kotlin DSL builders into `createElement`.
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

- **Why it matters.** Trace events flow into a single per-application stream; subscribers listen. Synchronous, in-order, event-at-a-time delivery per [009 §Listener invocation rules](009-Instrumentation.md#listener-invocation-rules). Plus a retain-N ring buffer (per [009 §Retain-N trace ring buffer](009-Instrumentation.md#retain-n-trace-ring-buffer-dev-only)) for tools that attach after events have fired.
- **Options by host.** Hand-rolled per host; the contract is just "deliver each emitted trace map to every registered callback synchronously, on the runtime's emit call stack." Listener-invocation order is not contract — implementations may use any registry shape (sorted map, hash map, vector) that delivers each event to every registered listener exactly once.
- **Reference-impl picks.** CLJS uses a single atom (the listener registry) plus a separate ring-buffer atom; each emit walks the registry inline.
- **Trade-offs.** Hot path: trace allocation must be cheap; listener invocation must short-circuit when no listeners are registered.

#### T2. Performance API equivalent

- **Why it matters.** Browser DevTools cross-correlation. The CLJS reference ships a Chrome Performance API bridge (per [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation)). Optional in other hosts.
- **Options by host.** Every in-scope host targets the browser; the Performance API is uniformly available.
  - **All eight (browser)** — `performance.mark` / `performance.measure` via the host's JS-interop primitive (CLJS `js/performance.mark`, TS direct, Fable `Browser.Dom.window.performance`, etc.).
  - **Server-side runtimes that the CLJS reference also serves (JVM)** — `clj-async-profiler`, JFR, or omit. Non-normative for the eight in-scope hosts since the SSR target is also JS / Node.
- **Reference-impl picks.** CLJS uses the Chrome Performance API bridge.
- **Trade-offs.** Optional; the underlying trace surface is the contract.

#### T3. Production elision

- **Why it matters.** All tracing is dev-only. Production builds must elide every emit call site, the listener registry, the trace buffer, the Performance bridge (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).
- **Options by host.** Every in-scope host has a compile-time-elision story via its JS-build pipeline.
  - **CLJS** — `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) + Closure compiler dead-code elimination, with a CI verifier (`scripts/check-elision.cjs`) that asserts dev-only sentinel strings are absent from `:advanced` `goog.DEBUG=false` bundles. See [009 §Production-elision verification](009-Instrumentation.md#production-elision-verification).
  - **TypeScript** — Build-time constant + tree-shake (Vite/Rollup with `define`); or `process.env.NODE_ENV` checks elided by the bundler.
  - **Melange / ReScript / Reason** — Conditional compilation via `#if RELEASE` or build-flag-gated module replacement; downstream Vite/Rollup tree-shaking eliminates the dev branch.
  - **Fable (F#)** — `#if !DEBUG` conditional compilation; Vite/Rollup tree-shakes the dev path post-compile.
  - **Squint** — Same shape as TypeScript — build-time constant + Vite tree-shake.
  - **Scala.js** — Scala.js's link-time-`if`-folding + Vite tree-shake; `js.constructorOf` + dead-code-elimination at link.
  - **PureScript** — `purescript-debug`-style guards + Vite tree-shake.
  - **Kotlin/JS** — Multi-module setup; release variant omits the tracing module + downstream Vite tree-shake.
- **Reference-impl picks.** CLJS uses Closure dead-code elimination.
- **Trade-offs.** Hosts without compile-time elision pay a runtime boolean check; CLJS pays nothing at all in production.

### Errors (always required)

#### E1. Error capture / recover

- **Why it matters.** Handler exceptions, fx exceptions, sub exceptions, schema-validation failures, drain depth exceeded — all must be caught, classified, and reported. Recovery contract per [009 §Recovery contract](009-Instrumentation.md#recovery-contract).
- **Options by host.** Try/catch around handler bodies, fx invocations, sub computations.
- **Reference-impl picks.** CLJS uses try/catch in `events.cljc` / `fx.cljc` / `subs.cljc`.
- **Trade-offs.** Capture must not swallow errors silently — every catch fires a structured trace event with `:operation :rf.error/<category>` and `:op-type :error`.

#### E2. Error reporting to tools

- **Why it matters.** Errors are emitted as structured trace events (per [009 §Error contract](009-Instrumentation.md#error-contract)) — tools branch on `:op-type :error` and `:operation` prefix. The per-frame `:on-error` slot in `reg-frame` metadata is the policy mechanism.
- **Options by host.** Falls out of **T1** (trace delivery).
- **Reference-impl picks.** CLJS routes errors through the trace stream.
- **Trade-offs.** Strings as errors are out — every error has an `:operation` namespaced keyword and a `:tags` map per the error category.

### Testing (recommended; required for conformance)

#### Test1. Test runner conventions

- **Why it matters.** Per-test frames (`make-frame` / `destroy-frame`), synchronous trigger (`dispatch-sync`), per-test stubbing (`:fx-overrides`, `:interceptor-overrides`), framework adapter (per [008](008-Testing.md)).
- **Options by host.**
  - **CLJS** — `cljs.test` / `clojure.test` re-exports plus `re-frame.test` helpers.
  - **TypeScript** — Vitest, Jest, or Playwright (for browser + DOM).
  - **Melange / ReScript / Reason** — Jest via the `bs-jest` or `melange-jest` bindings; Vitest also works.
  - **Fable (F#)** — Fable.Mocha or Fable.Jester.
  - **Squint** — Vitest (Squint runs on Node + browser via the same JS substrate).
  - **Scala.js** — utest, ScalaTest with the Scala.js runner, or Vitest via the JS-output classpath.
  - **PureScript** — `spec` (`purescript-spec`) or Jest via FFI bindings.
  - **Kotlin/JS** — Kotest-JS or `kotlin.test` with the Kotlin-React test runner.
- **Reference-impl picks.** CLJS uses cljs.test/clojure.test.
- **Trade-offs.** Headless evaluation must work — tests run on Node or the host's non-browser target (the CLJS reference also targets JVM per [000 §C2 Cross-platform](000-Vision.md#c2-cross-platform-jvm-interop-preserved); other in-scope hosts typically reach the same outcome via Node).

#### Test2. Headless evaluation

- **Why it matters.** `compute-sub` (pure sub computation against an `app-db` value) and `machine-transition` (pure transition function) must run without a browser / DOM (Node is acceptable). Tests use these for fast iteration.
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
  - **TypeScript** — `path-to-regexp` (the routing primitive react-router uses), or a hand-rolled matcher.
  - **Other in-scope hosts (Melange / ReScript / Reason, Fable, Squint, Scala.js, PureScript, Kotlin/JS)** — Hand-rolled matcher from registered route metadata using the host's native pattern-matching primitives, or a binding to `path-to-regexp` via the host's JS-FFI.
- **Reference-impl picks.** CLJS hand-rolls the matcher with a 6-rule precedence cascade.
- **Trade-offs.** Routes are registry entries (per [012](012-Routing.md)) — the routing table is data, queryable via `(handlers :route)`.

#### R2. Navigation observer

- **Why it matters.** URL changes (popstate, pushState, hash changes) need to translate into `:rf.route/navigate` events. Per [012 §Fragments](012-Routing.md#fragments) and §Navigation blocking.
- **Options by host.** All eight in-scope hosts target browsers; the navigation primitives are uniform.
  - **All eight (browser)** — `popstate` + `hashchange` listeners; `history.pushState` / `replaceState` for fx. Each host calls these via its JS-interop primitive.
  - **Server-side (SSR)** — Initial URL from the request; no observer needed (per [011](011-SSR.md)).
- **Reference-impl picks.** CLJS uses browser history API + `:rf.nav/push-url` fx.
- **Trade-offs.** Navigation tokens (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)) are required for stale-result suppression — make sure the implementation threads them through.

### SSR (if Q3 is yes)

#### SSR1. Render-to-string

- **Why it matters.** Pure render-tree → HTML string, JVM-runnable in the CLJS reference, host-pure in any port (per [011](011-SSR.md) and [006 §`render-to-string`](006-ReactiveSubstrate.md#render-to-string-render-tree-opts--string)).
- **Options by host.** Every in-scope host has React-DOM's `renderToString` available via its React binding; or can ship a hand-rolled render-tree → HTML emitter (the CLJS-reference choice).
  - **CLJS** — Hand-rolled hiccup → HTML emitter (~200 lines).
  - **TypeScript / Melange / ReScript / Reason / Fable / Squint / Scala.js / PureScript / Kotlin/JS** — `renderToString` from React-DOM via the host's React binding; or a hand-rolled emitter over the host's render-tree shape.
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
  - **Dynamically typed in-scope hosts (CLJS, Squint)** — Malli (CLJS reference), or Zod (Squint) via JS-FFI.
  - **Statically typed in-scope hosts (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS)** — Host type system covers most territory; a runtime validation layer (Zod-style) is optional at system edges (incoming JSON, hydration payload).
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

- **Why it matters.** `:rf.machine/spawn` fx creates a new machine instance under a generated id; `:rf.machine/destroy` fx tears it down (per [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)).
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
- **Options by host.** Per host's REPL or live-attach surface: nREPL+CIDER (CLJS / Squint); Node-attached debugger over a dev-build module-replacement boundary for the JS-cross-compile hosts (TypeScript, Melange / ReScript / Reason, Fable, Scala.js, PureScript, Kotlin/JS); or a host-idiomatic REPL the build pipeline exposes. The framework primitives are host-agnostic across the eight.
- **Reference-impl picks.** CLJS reference ships the trace surface, epoch history, and registrar query API in-tree (per [rf2-icil audit](Tool-Pair.md#how-ai-tools-attach)). re-frame-pair is a separate library that consumes these.
- **Trade-offs.** **No 10x dependency required** — re-frame2 is infrastructure-complete for AI-tool consumption. 10x and pair share the substrate.

### Security obligations for implementation tooling

The following obligations apply to any port that ships **tooling** (test fixtures, scaffolding, conformance-corpus emit, pair-tool source-mapping). They are spec-pinned defaults the implementor MUST honour — pre-alpha; no back-compat hedges. Full rationale + audit trail in [Security.md](Security.md); the section headings here are the implementor's todo-list.

#### File-path boundaries for writing tools (rf2-21rfv)

- **Why it matters.** Implementation tooling that writes to the filesystem (test-fixture emit, conformance-corpus generation, scaffolding scripts, story-recorder dumps, pair-tool log writers) typically accepts env-var path overrides for output roots. A misconfigured env var pointing at `$HOME`, `/`, or the user's source tree would let a tool's "rm temporary scratch" / "regenerate fixtures" step delete or overwrite the wrong tree. The defense is a path-policy check that constrains every writing tool to a closed allowlist of repo-rooted prefixes.
- **What to ship.** Every writing tool that accepts an env-var path override MUST resolve the path (collapsing `..` segments and following symlinks) and check it against an **allowed-prefix list** before any write. The CLJS reference allows `implementation/` and `examples/` as the two prefixes; other ports MUST pick the equivalent repo-rooted prefixes for their tree.
- **Failing-escape behaviour.** An escape attempt — a `..` that exits the allowed roots, an absolute path outside the allowed prefixes, a symlink that resolves out — surfaces a clear error with the resolved path and the configured allowed list. The error is fail-fast (no fall-through to a default location, no strip-and-pass, no silent home-directory write). Per rf2-21rfv and [Security.md §File-path boundaries](Security.md#file-path-boundaries).
- **CI-internal knob, not stable public interface.** The env-var override is documented for CI-internal use (regenerate fixtures into a per-build scratch directory); it is not a stable public API for production deployment. The check is a **safety net against accidents**, not a security boundary against a hostile attacker — see [Security.md §Pragmatic stance](Security.md#pragmatic-stance) proposition 2.

#### Editor URI scheme allowlist for source-map clickthrough (rf2-vwcsq)

- **Why it matters.** Pair-tool source-map surfaces produce clickable links in dev tooling — IDE protocol URIs that open a file at a line. If the port ships source-map clickthrough (or accepts custom editor templates), an attacker-controllable scheme — `javascript:`, `data:`, `vbscript:` — that landed in the editor template would be clicked, opening an attack surface in the dev's browser.
- **What to ship.** The editor-template surface MUST reject URIs whose scheme is `javascript:`, `data:`, or `vbscript:` (case-insensitive). Everything else passes — `vim:`, `idea:`, `subl:`, `org:`, `vscode:`, `cursor:`, and future editor schemes — with no dev burden.
- **Where the check fires.** At editor-template registration time **and** at click-resolution time, so a template that interpolates user input into the scheme position is also caught at the click. Per rf2-vwcsq and [Tool-Pair §Editor URI scheme allowlist](Tool-Pair.md#editor-uri-scheme-allowlist-rf2-vwcsq) (the canonical contract) + [Security.md §Editor URI scheme allowlist](Security.md#editor-uri-scheme-allowlist).

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
- `:fsm/*` — FSM-richness axis (`:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`). Run if Q1 yes and the matching capability is claimed.
- `:actor/*` — actor-model axis (`:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`). Run if Q1 yes and the matching capability is claimed.
- `:routing/*` — run if Q2 yes.
- `:ssr/*` — run if Q3 yes.
- `:schemas/*` — run if Q4 yes (regardless of mechanism).

See [conformance/README §Capability tagging worked example](conformance/README.md#capability-tagging-worked-example) for a five-fixture cross-section showing the tag conventions in practice on real corpus entries — useful as a copy-from reference when authoring the implementation's harness manifest.

The corpus is the **acceptance test** for [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone). A fixture an AI cannot reproduce without consulting outside sources is a **spec gap**, not an implementation gap; remediation is to fix the spec corpus, not the implementation.

---

## Cross-references

- [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) — single-source row-by-row breakdown of pattern-required vs. host-discretion vs. CLJS-only.
- [000 §AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) — the goal this checklist operationalises.
- [000 §Hierarchical FSM substrate with implementor-chosen capabilities](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) — the capability-list mechanism Part 1 is shaped by.
- [conformance/README](conformance/README.md) — the corpus the implementation runs against its claimed capability list.
- [API.md](API.md) — consolidated signatures for the surfaces named in Part 2.
- [Spec-Schemas](Spec-Schemas.md) — runtime shapes the implementation must produce / consume.
