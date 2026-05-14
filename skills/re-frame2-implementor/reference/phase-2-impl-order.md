# phase-2-impl-order

The EP-by-EP implementation walk for Phase 2. Each section names: what to read first, the contract the port must expose, what the CLJS reference did (as **one** worked example, not normative), what the conformance fixtures check, and common spec-gap traps.

The walking order is dependency-driven. Earlier EPs are foundations for later ones; do not skip ahead.

## Walking order

1. EP 001 — Registration
2. EP 002 — Frames + events + effects + subscriptions
3. EP 006 — Reactive substrate
4. EP 004 — Views
5. EP 009 — Instrumentation
6. **Acceptance gate 1**: run `:core/*` conformance fixtures
7. Optional EPs per Phase 1's D3 scope (suggested order below)
8. **Acceptance gate 2**: run the full claimed-capability fixture set

Each EP is multi-day work. Plan one focused session per EP; don't try to land two in one sitting.

---

## EP 001 — Registration

**Read first.** [`spec/001-Registration.md`](https://day8.github.io/re-frame2/spec/001-Registration/). Plus [Implementor-Checklist §F6 Hot-reload primitive](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f6-hot-reload-primitive) for the re-registration contract.

**The contract.**

- A single registrar — a `(kind, id) → metadata-bearing entry` map.
- Closed kinds: `:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:frame`, `:route`, `:machine-action`, `:machine-guard`, plus any kinds gated by optional EPs (e.g. `:story` if Q5 yes).
- Public `reg-*` surface — one per kind. Each `reg-*` takes id + metadata + handler-or-spec.
- Registration metadata — `:doc`, source coords (`:ns`/`:line`/`:file` if the host supports source-coord capture), `:tags`, kind-specific keys.
- Hot-reload semantics: re-registration replaces the entry atomically and emits `:rf.registry/handler-replaced`.
- Introspection: `(registrations kind)`, `(handler-meta kind id)` per kind.

**What the CLJS reference did (example).** A single atom holding a nested map: `{:event {:id metadata} :sub {...} ...}`. `reg-*` are macros that capture source coords at compile time. Re-registration is `swap!` on the atom. The macros call into functional `register-*` for advanced cases. None of this is normative — your port's mechanics will differ.

**Conformance fixtures.** `:core/registration` family. Verify: register an event → query handlers → handler-meta returns the registered metadata. Re-register → handler-replaced trace fires. Register conflicting id of same kind → policy per the spec.

**Common spec-gap traps.**

- **Source-coord capture.** Hosts without macros (TS at runtime, OCaml-family) capture source coords from stack frames at `reg-*` call time, or via build-time codegen, or omit. The CLJS reference uses macros — that's a choice, not a requirement. Per [`spec/000-Vision.md` §What the pattern does NOT over-commit to](https://day8.github.io/re-frame2/spec/000-Vision/#what-the-pattern-does-not-over-commit-to).
- **Metadata propagation.** The metadata on a `reg-event-*` registration must be reachable from the trace events fired during the event's drain. The CLJS reference threads `:doc` and source coords through the dispatch envelope; check that your design surfaces the same.

---

## EP 002 — Frames + events + effects + subscriptions

**Read first.** [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) — this is the spec's biggest chapter and most load-bearing. Plan two reads: one for the frame contract, one for the drain semantics.

**The contract.**

- **Frame** is `{state, queue, sub-cache, id}` — an isolated runtime boundary. Multi-instance (per-test, per-request, per-session, default).
- **Dispatch envelope** is `{event, frame, overrides, trace-id, source}` — an open map.
- **Event handler contract.** `(state, event) → effects-map`. Pure. The effects map is `{:db <new-state>, :fx [[<fx-id> <args>] ...]}`.
- **Closed effect-map shape.** Only `:db` and `:fx` at the top level. Per [`spec/Spec-Schemas.md`](https://day8.github.io/re-frame2/spec/Spec-Schemas/) §`:rf/effect-map`.
- **Six-step pipeline.** Per the EP — coeffect injection → event handler → effects map → fx routing → side-effects → trace.
- **Run-to-completion drain.** Per frame; an event's cascade settles before the next event is processed. Async fx schedule and re-enter via `:dispatch`.
- **Subscription system.** Query → value-from-state with stable composition. Layer-1 reads `app-db`; layer-2+ compose via `:<-`. Cache invalidates by `=`-equality.
- **`reg-frame` is atomic** create-and-register. Frame state is preserved across `reg-frame` re-registration for hot-reload.

**What the CLJS reference did (example).** A frame is a Reagent `r/atom` wrapping a CLJ map plus a Clojure deftype holding queue + sub-cache. Dispatch drains via a synchronous loop that flushes after each event. Subscriptions are Reagent reactions cached in the sub-cache; equality-invalidation is built into Reagent. The reference uses interceptors — chained transforms over the dispatch envelope — as the implementation strategy for the six-step pipeline; per [`spec/Cross-Spec-Interactions.md`](https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/) interceptors are an implementation detail, not a public contract.

**Conformance fixtures.** `:core/event-handler`, `:core/sub`, `:core/fx`, `:core/frame`, `:core/drain`, `:core/trace`. Verify: dispatch increments a counter → app-db updates; subs over the counter return the new value; `:dispatch` fx re-enters; the drain settles before the next external dispatch.

**Common spec-gap traps.**

- **Closed effect-map shape.** New top-level keys do NOT go in `:db` or `:fx` peer position — they go inside `:fx`. The v1→v2 migration walks this rule under M-8; your port enforces it from the start. Per [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) and the M-8 entry in [`spec/MIGRATION.md`](https://day8.github.io/re-frame2/spec/MIGRATION/).
- **Run-to-completion vs sync vs async fx.** Sync fx run inline; async fx schedule via host's promise/timeout and re-enter through `:dispatch` after the side effect completes. Async fx must NOT call back into the runtime during the current drain — that would violate run-to-completion.
- **Sub cache invalidation.** The cache invalidates by value equality on inputs. Identity-only equality (`===` in JS without deep compare; `eq?` in Lisp; reference equality in Java) breaks the contract. Your persistent data structure choice (D4.2) must provide cheap value-equality.
- **Frame revertibility.** The frame's full state — `app-db` plus any per-frame registry tier — must be revertible by value swap. This propagates the D4.2 (persistent data structures) requirement.

---

## EP 006 — Reactive substrate

**Read first.** [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/). All ~990 lines — this EP is the load-bearing contract between the runtime and the view layer.

**The contract.**

- **Six required functions** the adapter must provide: container creation, container read, container replace, sub-cache invalidation hook, render-tree → surface, mount/unmount.
- **Two optional functions:** server-render-to-string, hydration.
- **One lifecycle function:** start.
- **Single-adapter-per-process.** A process binds one adapter at boot; multi-adapter coexistence is post-v1.
- **Revertibility constraint on adapters.** Adapter-internal state must be derivable from the frame value. No "shadow state" inside the adapter that the frame value can't reproduce on revert. Per [`spec/006-ReactiveSubstrate.md` §Revertibility constraints on adapters](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#revertibility-constraints-on-adapters).
- **Subscription cache invalidation contract.** Per [`spec/006-ReactiveSubstrate.md` §Subscription cache — contract and operational semantics](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#subscription-cache--contract-and-operational-semantics).

**What the CLJS reference did (example).** Two adapters in-tree: `re-frame2-reagent` (browser; atop Reagent + React) and a plain-atom adapter (JVM / SSR). The Reagent adapter uses Reagent's reaction primitive for subs; the plain-atom adapter uses a hand-rolled signal graph. Mount/unmount hook into Reagent's component lifecycle.

**Conformance fixtures.** `:core/substrate` family. Verify: replace-container fires the invalidation hook; subscription cache evicts on input change; revert by container swap restores prior view state.

**Common spec-gap traps.**

- **Revertibility constraint.** Easy to violate inadvertently by stashing per-component state in the adapter that the frame can't reproduce. Audit every adapter-internal cache during EP 006 against the constraint.
- **Render-trigger semantics.** The trigger must be observably equivalent to "change in `app-db` → recompute affected subs → re-render dependent views". Substrates with their own reactivity model (Solid, Vue, SwiftUI) need to plug into this contract, not subvert it.
- **Sub lifecycle.** When a view stops reading a sub, the sub should eventually dispose. The mechanism is substrate-dependent (Reagent uses last-deref-disposes-after-a-delay); pick a policy and document it.

---

## EP 004 — Views

**Read first.** [`spec/004-Views.md`](https://day8.github.io/re-frame2/spec/004-Views/).

**The contract.**

- **`reg-view`** registers a view with the registrar. Public surface for declarative view registration.
- **Pure `(state, props) → render-tree`.** Views are pure functions of their inputs.
- **Render-tree is serialisable data.** Not opaque host objects with closures. The render-tree must serialise for SSR + view-tree tooling.
- **Frame-provider.** Views run in the context of a default frame; a view rendered under a non-default frame must opt in. Per [`spec/004-Views.md`](https://day8.github.io/re-frame2/spec/004-Views/) §Frame propagation.
- **Source-coord stamping.** Where the host supports it, registered views carry source coords for tooling.

**What the CLJS reference did (example).** `reg-view` is a macro that captures source coords, returns a Reagent component, and registers the metadata. Render-tree is hiccup. Frame propagation uses Reagent's component context. Substrate-specific.

**Conformance fixtures.** `:core/view` family. Verify: a registered view reads a sub → mounts → updates when the sub's input changes; the render-tree of a known view matches an expected serialisable shape.

**Common spec-gap traps.**

- **Closed component trees.** A render-tree that includes raw substrate elements with closures (e.g. raw React elements with `useState`) is not serialisable, and breaks SSR + tooling. Keep the render-tree pure data; let the substrate adapter realise it.
- **Frame propagation.** Views rendered under a non-default frame is a common need (test fixtures, story workspaces, embedded sub-apps). The propagation mechanism is substrate-specific — context for React, environment values for SwiftUI — but the contract is uniform.

---

## EP 009 — Instrumentation

**Read first.** [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/).

**The contract.**

- **Trace event stream.** Structured events emitted from well-defined points (event-handler entry/exit, fx invocations, sub computations, errors). Synchronous, in-order, per-emit listener invocation.
- **Listener registry.** `(register-trace-cb! key callback)` / `(deregister-trace-cb! key)`. Multiple listeners; each gets every event.
- **Retain-N ring buffer.** Dev-only; tools that attach after events have fired can read recent history.
- **Error contract.** Structured trace events for runtime failures — handler exceptions, schema validation, drain depth, no-such-handler. `:operation :rf.error/<category>`, `:op-type :error`.
- **Production elision.** Every emit site, the listener registry, the trace buffer must elide in production builds. Mechanism is host-discretion (Closure DCE for CLJS; Vite `define` for JS/TS; Cargo features for Rust; `__debug__` for Python).

**What the CLJS reference did (example).** A single atom holds the listener registry; emit walks it inline. A separate ring-buffer atom holds the dev-only history. Production elision via `goog-define` + Closure DCE; a CI script verifies dev-only sentinel strings are absent from production bundles.

**Conformance fixtures.** `:core/trace`, `:core/error` families. Verify: dispatching an event emits the expected trace sequence; a handler that throws produces an `:rf.error/handler-exception` trace event; the ring buffer holds the last N events when no listener was registered at emit time.

**Common spec-gap traps.**

- **Listener invocation order.** The spec says "synchronous, in-order, event-at-a-time, exactly once per registered listener". It does NOT specify which listener fires first when multiple are registered. Don't over-commit; don't rely on order in your tests.
- **Production elision.** The hardest piece. The CLJS reference's CI verifier (sentinel-string scan) is a useful pattern to copy — emit a known string at every dev-only call site, scan production bundles for any occurrence, fail the build if found.
- **Error category coverage.** Don't miss categories — every catch must fire a trace event, no silent swallow. The spec enumerates the categories (handler-exception, fx-exception, sub-exception, schema-validation, drain-depth, no-such-handler, more). Audit each catch in your port against the list.

---

## Acceptance gate 1 — run `:core/*` conformance

At this point a port with `{Q1=no, Q2=no, Q3=no, Q4=via-host-types-or-no, Q5=no, Q6=no, Q7=no}` is feature-complete against its claim. Run the harness; the `:core/*` fixtures should all pass.

See `conformance.md` for the harness shape, the EDN-handler-body DSL, and what to do when a fixture won't pass.

**If anything fails:** is the failure a spec gap or an implementation bug? The leaf `conformance.md` covers the diagnosis.

---

## Optional EPs (per Phase 1's D3 scope)

For each capability the port declared `yes` for in D3, walk the matching EP. Suggested order if multiple are in scope (each can be done in isolation; the order minimises rework):

### EP 010 — Schemas (if D5 ≠ no)

**Read.** [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/) and [Implementor-Checklist §Schemas](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#schemas-if-q4-is-yes).

**Contract.** `:spec` registration metadata; `reg-app-schema`; validation at boundaries (handler entry, sub return, fx args, app-db at registered paths); validation-failure trace events; production elision per EP 009.

**Common trap.** Open vs closed shapes — open by default is non-negotiable; opt-in `:closed true` per registration.

### EP 008 — Testing (always recommended)

**Read.** [`spec/008-Testing.md`](https://day8.github.io/re-frame2/spec/008-Testing/).

**Contract.** `with-frame`, `dispatch-sync`, `compute-sub`, `make-restore-fn`, `:fx-overrides`, `:interceptor-overrides`, framework adapter (cljs.test → vitest/pytest/etc.). JVM-runnable for the pure-function surface.

### EP 005 — State machines (if D3 Q1 = yes)

**Read.** [`spec/005-StateMachines.md`](https://day8.github.io/re-frame2/spec/005-StateMachines/) — the spec's largest EP (~2,900 lines). Plan one full session on the read alone before implementing.

**Contract.** `reg-machine`, transition tables, `create-machine-handler`, `:rf/machines` reserved app-db storage, drain extensions for `:raise`/`:always`/`:after`, hierarchy support per D3 Q1's sub-capability list, declarative `:invoke`.

**Capability sub-decisions.** D3 Q1 declared yes/no for each of: `:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`, `:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`. Implement only the claimed sub-capabilities; the conformance corpus runs the matching fixture subset.

**Common trap.** Drain extensions interact with EP 002's run-to-completion drain. Plan the integration carefully — `:always` and `:after` are subtle.

### EP 012 — Routing (if D3 Q2 = yes)

**Read.** [`spec/012-Routing.md`](https://day8.github.io/re-frame2/spec/012-Routing/).

**Contract.** `reg-route`, `match-url`, `route-link`, `:rf.nav/push-url` fx, `:rf/pending-navigation`, navigation tokens, fragment handling, `:can-leave?` guard.

### EP 011 — SSR (if D3 Q3 = yes)

**Read.** [`spec/011-SSR.md`](https://day8.github.io/re-frame2/spec/011-SSR/).

**Contract.** `:platforms` metadata on `reg-fx`, `render-to-string`, `:rf/hydrate`, hydration-mismatch detection, `init-platform`.

### EP 013 — Flows (if claimed)

**Read.** [`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/).

### EP 014 — HTTP (if claimed)

**Read.** [`spec/014-HTTPRequests.md`](https://day8.github.io/re-frame2/spec/014-HTTPRequests/) plus [Pattern-RemoteData](https://day8.github.io/re-frame2/spec/Pattern-RemoteData/) and [Pattern-ManagedHTTP](https://day8.github.io/re-frame2/spec/Pattern-Forms/).

### EP 007 — Stories (if D3 Q5 = yes)

**Read.** [`spec/007-Stories.md`](https://day8.github.io/re-frame2/spec/007-Stories/). Note: post-v1 in the CLJS reference too; expect spec churn.

---

## Acceptance gate 2 — full claimed-capability conformance pass

When every claimed capability is implemented, run the full conformance harness with the capability filter set to D7's claim list. Score must be `claimed-applicable / claimed-applicable`. Any failure that's not a spec gap is a port bug; any spec gap is drafted as a `day8/re-frame2` GitHub issue and filed only after engineer OK (per [`cardinal-rules.md` §§8–9](cardinal-rules.md)).

When the gate passes, the port is v1-complete against its claim.
