# phase-2-impl-order

The EP-by-EP implementation walk for Phase 2. Each section names: what to read first, the contract the port must expose, what the CLJS reference did (as **one** worked example, not normative), what the conformance fixtures check, and common spec-gap traps.

The walking order is dependency-driven. Earlier EPs are foundations for later ones; do not skip ahead.

**Three cross-cutting anchors to keep open for every EP below:**

- [`spec/Ownership.md`](https://day8.github.io/re-frame2/spec/Ownership/) — the canonical "where does X live" contract-surface map. When an EP touches a surface and you're unsure which spec owns it, this is the index. Read it once before EP 001 and consult it per-EP.
- [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) — the reserved `:rf/*` single-root namespace scheme, reserved fx-ids, reserved app-db keys, the `reg-*` macro inventory. Almost every framework id your port emits lands under `:rf/*`; honour the scheme from EP 001 onward (cardinal rule 10). **The one carve-out:** the three reserved fx-ids `:dispatch`, `:dispatch-later`, and `:raise` ship **unqualified** (bare, not under `:rf/*`) as frozen pre-consolidation legacy — register and recognise them exactly as-is; do not namespace or reject them (per [`cardinal-rules.md` §10](cardinal-rules.md) and [`spec/Conventions.md` §Reserved fx-ids](https://day8.github.io/re-frame2/spec/Conventions/#reserved-fx-ids)). Conformance fixtures assert `:rf.*` operation ids on the trace stream and emit the bare `:dispatch` / `:dispatch-later` fx from handler `:fx`.
- [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) — the consolidated public signature list. Read the relevant entries first whenever an EP's "The contract" names a public surface (`reg-*`, `dispatch`, `subscribe`, the fx/cofx surface, …).

## Walking order

1. EP 001 — Registration
2. EP 002 — Frames + events + effects + subscriptions
3. EP 006 — Reactive substrate
4. EP 004 — Views
5. EP 009 — Instrumentation
6. EP 015 — Data Classification (v1-required; overlays the 009 emission boundary)
7. **Acceptance gate 1**: run `:core/*` conformance fixtures
8. Optional EPs per Phase 1's D3 scope (suggested order below)
9. **Acceptance gate 2**: run the full claimed-capability fixture set

Each EP is multi-day work. Plan one focused session per EP; don't try to land two in one sitting.

---

## EP 001 — Registration

**Read first.** [`spec/001-Registration.md`](https://day8.github.io/re-frame2/spec/001-Registration/). Plus [Implementor-Checklist §F6 Hot-reload primitive](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#f6-hot-reload-primitive) for the re-registration contract, [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) for the reserved `:rf/*` scheme the registrar must honour, and the `reg-*` entries in [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) for the public registration signatures.

**The contract.**

- A single registrar — a `(kind, id) → metadata-bearing entry` map.
- Closed kinds (the v1 set): `:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:frame`, `:route`, `:head`, `:error-projector`, `:flow`. Always implement the core five (`:event`, `:sub`, `:fx`, `:cofx`, `:view`) plus `:frame`; the rest (`:route`, `:head`, `:error-projector`, `:flow`) are written only as their optional EPs come into scope (Routing 012, SSR 011, error projection, Flows 013). Adding a kind outside this set is a spec change, not a port choice. There is **no** `:machine`, `:machine-action`, or `:machine-guard` kind — a machine registers as an ordinary `:event` entry; its guards/actions are machine-scoped, not registry kinds. App-db schemas (per [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/)) are **not** a registrar kind either; they live in the schemas artefact's per-frame side-table. Per [`spec/001-Registration.md` §Registry model](https://day8.github.io/re-frame2/spec/001-Registration/#registry-model--the-canonical-kind-keyword-set) and the CLJS reference's closed set in `implementation/core/src/re_frame/registrar.cljc` (`kinds`).
- Public `reg-*` surface — one per kind. Each `reg-*` takes id + metadata + handler-or-spec.
- Registration metadata — `:doc`, source coords (`:ns`/`:line`/`:file` if the host supports source-coord capture), `:tags`, kind-specific keys.
- Hot-reload semantics: re-registration replaces the entry atomically and emits `:rf.registry/handler-replaced`.
- Introspection: `(registrations kind)`, `(handler-meta kind id)` per kind.

**What the CLJS reference did (example).** A single atom holding a nested map: `{:event {:id metadata} :sub {...} ...}`. `reg-*` are macros that capture source coords at compile time. Re-registration is `swap!` on the atom. The macros call into functional `register-*` for advanced cases. None of this is normative — your port's mechanics will differ.

**Conformance fixtures.** `:core/registration` family. Verify: register an event → query handlers → handler-meta returns the registered metadata. Re-register → handler-replaced trace fires. Register conflicting id of same kind → policy per the spec.

**Common spec-gap traps.**

- **Source-coord capture.** Hosts without macros (TS at runtime, OCaml-family) capture source coords from stack frames at `reg-*` call time, or via build-time codegen, or omit. The CLJS reference uses macros — that's a choice, not a requirement. Per [`spec/000-Vision.md` §What the pattern does NOT over-commit to](https://day8.github.io/re-frame2/spec/000-Vision/#what-the-pattern-does-not-over-commit-to).
- **Metadata propagation.** The metadata on a `reg-event-*` registration must be reachable from the trace events fired during the event's drain. The CLJS reference threads `:doc` and source coords through the dispatch envelope; check that your design surfaces the same.
- **`:machine-action` in the conformance fixtures is NOT a runtime registry kind.** The Mode-B FSM fixtures (`:machine-transition` / `:reg-machine`) use `:machine-action` as a top-level key under `:fixture/registry` / `:fixture/handlers` — a *harness-local* binding for attaching pure transition-action bodies in a frameless fixture (see [`spec/conformance/README.md`](https://day8.github.io/re-frame2/spec/conformance/)). The runtime closed kind set above is unchanged; do not wire `:machine-action` into your registrar or reject the fixture when you encounter it.

---

## EP 002 — Frames + events + effects + subscriptions

**Read first.** [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/) — this is the spec's biggest chapter and most load-bearing. Plan two reads: one for the frame contract, one for the drain semantics. Keep the `reg-event-*` / `reg-sub` / `reg-fx` / `reg-cofx` / `dispatch` / `subscribe` / `reg-frame` entries in [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) open for the exact public signatures.

**The contract.**

- **Frame** is a `{frame-state, queue, sub-cache, id}` boundary — an isolated runtime context. Multi-instance (per-test, per-request, per-session, default). `frame-state` is the **two-partition** container value `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` — see [EP-0001's two-partition frame contract](#the-two-partition-frame-contract) below; `app-db` and `runtime-db` are read-only derived projections over that one container, not separately-stored cells.
- **Dispatch envelope** is `{event, frame, overrides, trace-id, source}` — an open map.
- **Event handler contract.** `(app-db, event) → effects-map`. Pure. An **ordinary app handler** returns `{:db <new-app-db>, :fx [[<fx-id> <args>] ...]}` — `:db` replaces only the **app-db** partition. (`:db`'s coeffect/effect value is the app-db projection, NOT the whole frame-state value — see the two-partition note below.)
- **Closed effect-map shape — a *policed* contract.** Three top-level keys: **`#{:db :rf.db/runtime :fx}`**, and each `:fx` entry is a `[fx-id args]` tuple. Per [`spec/Spec-Schemas.md`](https://day8.github.io/re-frame2/spec/Spec-Schemas/) §`:rf/effect-map`. `:db` is the app-db partition write; `:fx` carries everything else; `:rf.db/runtime` is the **runtime-db** partition write, reserved **by convention** for framework / runtime-extension authority — ordinary app handlers return only `:db` + `:fx`. The fx interpreter does NOT trust the handler's return: validate the shape *before* routing (a top-level key **outside `#{:db :rf.db/runtime :fx}`**, a non-sequential `:fx` value, or a malformed `:fx` entry are each dropped-but-continue with an `:rf.error/effect-map-shape` trace; a non-map/non-`nil` return is a no-op with an `:rf.error/effect-handler-bad-return` trace). `:rf.db/runtime` is **NOT** a shape error: an ordinary app handler emitting it is surfaced by a dev diagnostic (`:rf.warning/app-handler-runtime-effect`), not shape-rejected. These never throw — see EP 009's proactive shape-policing note; don't lower a lenient guard that accepts any non-empty entry vector, and don't shape-reject `:rf.db/runtime`.
- **Six-step pipeline.** Per the EP — coeffect injection → event handler → effects map → fx routing → side-effects → trace.
- **Run-to-completion drain.** Per frame; an event's cascade settles before the next event is processed. Async fx schedule and re-enter via `:dispatch`.
- **Subscription system.** Query → value-from-state with stable composition. Layer-1 app subs read the **app-db** projection; layer-1 framework subs read the **runtime-db** projection; layer-2+ compose via `:<-`. Cache invalidates by `=`-equality, **partition-aware** — a runtime-only commit does not invalidate app subs, and an app-only commit does not invalidate framework subs (see the two-partition note below).
- **`reg-frame` is atomic** create-and-register. Frame state (both partitions) is preserved across `reg-frame` re-registration for hot-reload.

#### The two-partition frame contract

**EP-0001 shipped a two-partition frame.** A frame's durable state is **one physical `frame-state` container value** holding two partitions:

```clojure
{:rf.db/app     <app-db>      ; the application owns this — user data and NOTHING else
 :rf.db/runtime <runtime-db>} ; the framework owns this — machine snapshots, route slice,
                              ;   elision declarations, SSR metadata; addressed by :rf.runtime/* children
```

- **`app-db` and `runtime-db` are read-only derived projections** over that one container (`app-db = (:rf.db/app frame-state)`, `runtime-db = (:rf.db/runtime frame-state)`), not separately-stored cells. This is **pattern contract**, not just one acceptable shape — a port MAY differ only if it preserves the projection-equality semantics (per [`spec/002-Frames.md` §One physical container, two projection reactions](https://day8.github.io/re-frame2/spec/002-Frames/) and [`spec/006-ReactiveSubstrate.md` §Frame-state container and partition projections](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/)).
- **Write authority is by convention.** An ordinary `:db` effect replaces **only** app-db; a `:rf.db/runtime` effect replaces **only** runtime-db. The two commit coherently as one atomic frame-state transition when a cascade emits both. Ordinary app code writes only `:db`; `:rf.db/runtime` is reserved for framework / runtime-extension authority (diagnosed via `:rf.warning/app-handler-runtime-effect`, not enforced as a capability — per [`spec/002-Frames.md` §Write authority is by convention](https://day8.github.io/re-frame2/spec/002-Frames/)).
- **Partition-aware invalidation comes free** from projection equality: a runtime-only commit leaves the `app-db` projection `identical?`, so app subs neither recompute nor re-render; an app-only commit is symmetric for framework subs. Restore / hydration / time-travel reinstall the coherent `frame-state` (both partitions together).
- **`:rf/runtime` as an app-db root is RETIRED.** EP-0001 removed the former app-db root `:rf/runtime`; a stray `:rf/runtime` root in a `:db` effect now HARD-ERRORS (`:rf.error/legacy-runtime-root`) at the event-commit boundary. Framework runtime state lives in the runtime-db partition under `:rf.runtime/*`, never under an app-db root. (Do not teach or emit `:rf/runtime` app-db paths.)

**What the CLJS reference did (example).** A frame is a deftype holding **one** `r/atom` (Reagent ratom) for the `frame-state` value plus mutable fields for the queue and sub-cache; `app-db` and `runtime-db` are projection reactions (`r/reaction`) layered over that one container. Dispatch drains via a synchronous loop that flushes after each event. Subscriptions are Reagent reactions cached in the sub-cache; the partition projections give equality-invalidation for free. The reference uses interceptors — chained transforms over the dispatch envelope — as the implementation strategy for the six-step pipeline; per [`spec/Cross-Spec-Interactions.md`](https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/) interceptors are an implementation detail, not a public contract. The one-container + two-projection shape is pattern-required; the Reagent realisation of it is not.

**Conformance fixtures.** `:core/event-handler`, `:core/sub`, `:core/fx`, `:core/frame`, `:core/drain`, `:core/trace`. Verify: dispatch increments a counter → app-db updates; subs over the counter return the new value; `:dispatch` fx re-enters; the drain settles before the next external dispatch.

**Common spec-gap traps.**

- **Closed effect-map shape.** The closed top-level set is **`#{:db :rf.db/runtime :fx}`**. App-level effects do NOT go in `:db` or `:fx` peer position — they go inside `:fx`. The one framework-reserved peer is `:rf.db/runtime` (the runtime-db partition write); ordinary app handlers still emit only `:db` + `:fx`, and a port must **diagnose, not shape-reject**, an app handler that emits `:rf.db/runtime` (`:rf.warning/app-handler-runtime-effect`). The v1→v2 migration walks the closed-shape rule under M-8; your port enforces it from the start. Per [`spec/002-Frames.md`](https://day8.github.io/re-frame2/spec/002-Frames/), [`spec/Spec-Schemas.md` §`:rf/effect-map`](https://day8.github.io/re-frame2/spec/Spec-Schemas/#rfeffect-map), and the M-8 entry in [`migration/from-re-frame-v1/README.md`](https://day8.github.io/re-frame2/migration/from-re-frame-v1/).
- **Run-to-completion vs sync vs async fx.** Sync fx run inline; async fx schedule via host's promise/timeout and re-enter through `:dispatch` after the side effect completes. Async fx must NOT call back into the runtime during the current drain — that would violate run-to-completion.
- **Sub cache invalidation.** The cache invalidates by value equality on inputs. Identity-only equality (`===` in JS without deep compare; reference equality in general) breaks the contract. Your persistent data structure choice (F2) must provide cheap value-equality.
- **Frame revertibility.** The frame's full state — the whole `frame-state` value (`{:rf.db/app … :rf.db/runtime …}`), both partitions together, plus any per-frame registry tier — must be revertible by one value swap. Restore / hydration / time-travel reinstall a coherent frame-state, never one partition in isolation. This propagates the F2 (persistent data structures) requirement.

---

## EP 006 — Reactive substrate

**Read first.** [`spec/006-ReactiveSubstrate.md`](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/). All ~990 lines — this EP is the load-bearing contract between the runtime and the view layer.

**The contract.** Ten entries total — verbatim from [`spec/006-ReactiveSubstrate.md` §The adapter API contract](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#the-adapter-api-contract). The function set is **closed for v1**.

- **Six required functions** the adapter must provide:
  - `make-state-container` — create a reactive container holding an `app-db` value.
  - `read-container` — read the current value (pure).
  - `replace-container!` — mutate the container with a new value (the only mutation primitive; invalidation rides this).
  - `make-derived-value` — construct a derived (memoised) container from one or more sources.
  - `render` — render a render-tree onto the substrate's surface; return an unmount fn.
  - `render-to-string` — pure render to an HTML string. **JVM-runnable and required even for Q3=no (no-SSR) ports** — do not treat it as optional.
- **Three optional functions** (the core falls back, or no-ops, when absent):
  - `subscribe-container` — register a change-listener for invalidation. Fallback: core runs invalidation inline within `replace-container!`.
  - `register-context-provider` — return a context-provider component that scopes a frame to a subtree. Fallback: explicit-frame-as-argument threaded by the user's view code.
  - `flush-render!` — synchronously commit the substrate's pending renders to the surface (NOT a `requestAnimationFrame`-style tick), so headless tooling can drive a `dispatch → flush-render! → observe-settled-DOM` loop deterministically. Fallback: core no-ops (an adapter that renders without a live commit — plain-atom / SSR — has nothing to flush).
- **One lifecycle slot:** `:dispose-adapter!` — the adapter-spec map key the adapter implements: tear down, release listeners, caches, host resources. This is an **internal adapter-contract slot**, not a public function. (Lifecycle wiring is the core's public `install-adapter!` / `destroy-adapter!` pair — `install-adapter!` binds the adapter at boot, `destroy-adapter!` is the public teardown verb that calls the adapter's `:dispose-adapter!` slot. Neither is part of the adapter's own surface; there is no "start" function.)
- **Single-adapter-per-process.** A process binds one adapter at boot via `install-adapter!`; multi-adapter coexistence is post-v1.
- **Revertibility constraint on adapters.** Adapter-internal state must be derivable from the frame value. No "shadow state" inside the adapter that the frame value can't reproduce on revert. Per [`spec/006-ReactiveSubstrate.md` §Revertibility constraints on adapters](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#revertibility-constraints-on-adapters).
- **Subscription cache invalidation contract.** Per [`spec/006-ReactiveSubstrate.md` §Subscription cache — contract and operational semantics](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/#subscription-cache--contract-and-operational-semantics).

**What the CLJS reference did (example).** The substrate contract lives inside core (`implementation/core/src/re_frame/substrate/`): `adapter.cljc` defines the contract, `plain_atom.cljc` provides a dependency-free reference substrate (a plain-atom signal graph, used for JVM / non-browser / SSR paths). On top of that, the React-binding adapters ship as sibling artefacts under `implementation/adapters/` — `reagent`, `reagent-slim`, `uix`, `helix` (plus `test-react` for the test harness). The Reagent-family adapters use Reagent's reaction primitive for subs and hook mount/unmount into React's component lifecycle; the in-core plain-atom substrate uses a hand-rolled signal graph. None of this layout is normative — the split between an in-core reference substrate and per-binding adapters is one worked example.

**Conformance fixtures.** `:core/substrate` family. Verify: replace-container fires the invalidation hook; subscription cache evicts on input change; revert by container swap restores prior view state.

**Common spec-gap traps.**

- **Revertibility constraint.** Easy to violate inadvertently by stashing per-component state in the adapter that the frame can't reproduce. Audit every adapter-internal cache during EP 006 against the constraint.
- **Render-trigger semantics.** The trigger must be observably equivalent to "change in `app-db` → recompute affected subs → re-render dependent views". Your host's React binding must plug subscription reads into React's render cycle (Reagent's auto-tracking; UIx / Helix `use-subscribe` over `useSyncExternalStore`) — not subvert it.
- **Sub lifecycle.** When a view stops reading a sub, the sub should eventually dispose. The mechanism is substrate-dependent (Reagent uses last-deref-disposes-after-a-delay); pick a policy and document it.

---

## EP 004 — Views

**Read first.** [`spec/004-Views.md`](https://day8.github.io/re-frame2/spec/004-Views/).

**The contract.**

- **`reg-view`** registers a view with the registrar. Public surface for declarative view registration.
- **Pure `(state, props) → render-tree`.** Views are pure functions of their inputs.
- **Render-tree is serialisable data.** Not opaque host objects with closures. The render-tree must serialise for SSR + view-tree tooling.
- **Frame-provider.** Views run in the context of an explicitly-established frame (a root `frame-provider`); there is no ambient default (EP-0002). A `reg-view` reads the surrounding provider's frame via React context; a view rendered with no provider in scope has no frame and its ambient `dispatch`/`subscribe` raise `:rf.error/no-frame-context`. Per [`spec/004-Views.md`](https://day8.github.io/re-frame2/spec/004-Views/) §Frame propagation.
- **Source-coord stamping.** Where the host supports it, registered views carry source coords for tooling.

**What the CLJS reference did (example).** `reg-view` is a macro that captures source coords, returns a Reagent component, and registers the metadata. Render-tree is hiccup. Frame propagation uses Reagent's component context. Substrate-specific.

**Conformance fixtures.** `:core/view` family. Verify: a registered view reads a sub → mounts → updates when the sub's input changes; the render-tree of a known view matches an expected serialisable shape.

**Common spec-gap traps.**

- **Closed component trees.** A render-tree that includes raw substrate elements with closures (e.g. raw React elements with `useState`) is not serialisable, and breaks SSR + tooling. Keep the render-tree pure data; let the substrate adapter realise it.
- **Frame propagation.** Views rendered under a non-default frame is a common need (test fixtures, story workspaces, embedded sub-apps). Every in-scope host targets React, so the propagation mechanism is React context (the host's React binding supplies the provider); explicit-frame-id remains the underlying contract.

---

## EP 009 — Instrumentation

**Read first.** [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/).

**The contract.** EP 009 has **two distinct surfaces with opposite production postures** — conflating them is the single most expensive 009 mistake (see the trap below). Read [`spec/009-Instrumentation.md` §The three always-on substrates](https://day8.github.io/re-frame2/spec/009-Instrumentation/) and §What is available in production before designing the emit path.

- **Dev-only trace surface (production-ELIDED).** The full structured trace stream — `register-listener!` / `unregister-listener!`, the rich per-emit trace events (event-handler entry/exit, fx invocations, sub computations, the dev-only `:rf.event/*` enrichments), and the retain-N ring buffer — is **dev-only by construction**. Synchronous, in-order, per-emit listener invocation. It DCEs out entirely in a default production build (no listener, no allocation, no overhead). Xray / Story / re-frame-10x consume it in dev. This is what "production elision" governs.
- **Always-on emit surfaces (production-SURVIVABLE).** Two substrates **survive `:advanced` + `goog.DEBUG=false`** and are NOT part of the dev trace surface — they exist precisely for the production / SSR observability posture and would defeat their purpose if a debug-gate flip silenced them:
  - **Event-emit listener** — `register-event-listener!` / `unregister-event-listener!`. Fires one tight, fixed-shape event-record per processed event (`{:event :event-id :frame :time :outcome :elapsed-ms}`) for direct hosted-backend forwarding. The `:event` vector rides `re-frame.elision/elide-wire-value` once before fan-out (post-elision: large → `:rf.size/large-elided`, sensitive → `:rf/redacted`).
  - **Error-emit listener** — `register-error-listener!` / `unregister-error-listener!`, plus the per-frame `:on-error` policy fn (`:default` / `:swallow` / `:replacement`). Fires one error-record per `:rf.error/*` cascade error (handler / coeffect / interceptor / flow-eval exceptions, fx errors). This is the production error-reporting fan-out (Sentry / Rollbar / hosted monitors) and the SSR fail-closed status path — it is **NOT trace-only** and MUST keep firing post-elision.
  - Both substrates deliver **identical record shapes in dev and prod**, run post-elision (marked values already substituted), and **isolate per-listener exceptions** (one listener throwing must not abort the cascade or the other listeners).
- **Error contract.** Structured records for runtime failures — handler exceptions, schema validation, drain depth, no-such-handler. `:operation :rf.error/<category>`, `:op-type :error`. The same `:rf.error/*` event flows on BOTH surfaces: the dev trace stream (when the gate is true) AND the always-on error-emit substrate (always).
- **Production elision applies to the dev trace surface only.** Every dev trace emit site, the `register-listener!` registry, the ring buffer, and the perf bridge elide in production builds. Mechanism is host-discretion (Closure DCE for CLJS; Vite `define` + tree-shake for JS/TS and Squint; `#if !DEBUG` + tree-shake for Fable; link-time-`if` for Scala.js; release-variant module omission for Kotlin/JS). **The two always-on substrates above are explicitly OUT of the elision scope** — they must survive the same production build that DCEs the trace surface, or hosted observability and error reporting silently vanish.

**What the CLJS reference did (example).** A single atom holds the dev-only `register-listener!` registry; emit walks it inline behind the `re-frame.interop/debug-enabled?` gate. A separate ring-buffer atom holds the dev-only history. The two always-on substrates (`register-event-listener!` / `register-error-listener!`) ride a *separate*, ungated emit path that survives `:advanced` + `goog.DEBUG=false`. Dev-trace production elision via `goog-define` + Closure DCE; a CI script verifies dev-only sentinel strings are absent from production bundles — while asserting the always-on substrates remain present.

**Conformance fixtures.** `:core/trace`, `:core/error` families. Verify: dispatching an event emits the expected trace sequence; a handler that throws produces an `:rf.error/handler-exception` trace event; the ring buffer holds the last N events when no listener was registered at emit time.

**Common spec-gap traps.**

- **Listener invocation order.** The spec says "synchronous, in-order, event-at-a-time, exactly once per registered listener". It does NOT specify which listener fires first when multiple are registered. Don't over-commit; don't rely on order in your tests.
- **Production elision — scope it to the DEV TRACE surface only.** The single most expensive 009 mistake is DCE-ing the whole instrumentation module, taking the always-on event/error-emit substrates with it. The `register-event-listener!` / `register-error-listener!` substrates and the per-frame `:on-error` policy fn **must survive `:advanced` + `goog.DEBUG=false`** — a production build that elides them kills hosted observability, kills production error reporting (Sentry / Rollbar fan-out), and breaks the SSR fail-closed status path. Wire the dev trace surface behind the debug gate; wire the two always-on substrates on a separate, ungated path. The CLJS reference's CI verifier (sentinel-string scan) is a useful pattern to copy — emit a known string at every *dev-only* call site, scan production bundles for any occurrence, fail the build if found — but pair it with a positive assertion that the always-on substrates are still present in the production bundle, so an over-aggressive DCE pass is caught.
- **Error category coverage.** Don't miss categories — and don't assume "category" means "exception." The spec's `:rf.error/*` taxonomy splits into two kinds, and a port that audits only the first ships silent bugs:
  - **Exception-driven (you `catch`).** Every catch must fire a trace event, no silent swallow: `:rf.error/handler-exception`, `:rf.error/fx-handler-exception`, `:rf.error/sub-exception`, `:rf.error/schema-validation-failure`, `:rf.error/drain-depth-exceeded`, `:rf.error/no-such-handler`, and more (spec/009 enumerates ~90 `:rf.error/*` categories; this is a sample, not the whole list). Use the fully-qualified `:rf.error/` form — a bare `fx-exception` is not a category (the real one is `:rf.error/fx-handler-exception`), and consumers/fixtures match on the exact keyword.
  - **Proactive shape-policing (you POLICE, nothing throws).** Two load-bearing fail-closed categories never raise an exception, so "audit each catch" misses them entirely — you must validate the effect-map shape *before* the fx interpreter runs:
    - `:rf.error/effect-map-shape` — a malformed effect-map from a `reg-event-fx` handler (per spec/009 §Error contract, three cases: (a) a top-level key **outside the closed set `#{:db :rf.db/runtime :fx}`**; (b) a non-`nil`, non-sequential `:fx` value, e.g. `{:fx :oops}`; (c) a single `:fx` entry that is not a `[fx-id args]` tuple, e.g. `{:fx [[:good a] :oops]}`). Recovery is `:logged-and-skipped` — one trace per offending key/value/entry, the offender is **dropped while sibling `:fx` entries still run** (a `nil`/empty entry is the legal conditional-fx no-op, NOT traced). **`:rf.db/runtime` is inside the closed set — it is NOT a shape error.** An ordinary app handler that emits it is surfaced by the dev diagnostic `:rf.warning/app-handler-runtime-effect` (a by-convention nudge, not a drop): police it as a *warning*, never as `:rf.error/effect-map-shape`. The `:fx` entry shape is `[:vector [:tuple :keyword :any]]` per [`spec/Spec-Schemas.md` §`:rf/effect-map`](https://day8.github.io/re-frame2/spec/Spec-Schemas/#rfeffect-map).
    - `:rf.error/effect-handler-bad-return` — a `reg-event-fx` handler that returns a value that is neither a map nor `nil`; the dispatch is a no-op + trace (`nil` stays the legal no-op).
  Audit each catch **AND each proactive shape gate** in your port against the spec/009 error contract. A lenient fx-entry guard that accepts any non-empty vector — instead of policing the `[fx-id args]` tuple shape — silently truncates malformed effect maps with no diagnostic (a real bug class the reference impl hit and fixed). The conformance corpus **now backstops this class** (`effect-map-shape-bad-top-level-key.edn`, `effect-map-shape-bad-fx-value.edn`, `effect-map-shape-bad-fx-entry.edn`, `effect-map-shape-surplus-entry-field.edn`, `effect-handler-bad-return.edn`), so a lenient port now FAILS green conformance here rather than shipping the bug undetected. Still police it from the spec first — the corpus confirms, it does not teach.

---

## EP 015 — Data Classification (Sensitive + Large)

**v1-required, not optional.** Spec 015 marks itself v1-required, and [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/) exposes `add-marks` / `set-marks` as v1 surface. A port that ships 001–009 but omits 015 ships a privacy hole: marked values leak through every observation surface (trace bus, event/error emit records, Xray, MCP wire, third-party log sinks). It lands here — right after 009 — because the classification machinery is a leak-prevention overlay on the 009 emission boundary, so it needs 009's emit path in place first.

**Read first.** [`spec/015-Data-Classification.md`](https://day8.github.io/re-frame2/spec/015-Data-Classification/) end-to-end. Keep open: the `add-marks` / `set-marks` entries in [`spec/API.md`](https://day8.github.io/re-frame2/spec/API/), [`spec/Conventions.md` §Reserved namespaces](https://day8.github.io/re-frame2/spec/Conventions/) (the `:rf/redacted` / `:rf/large` sentinels are framework-reserved), and [`spec/009-Instrumentation.md`](https://day8.github.io/re-frame2/spec/009-Instrumentation/) §The trace event model (where emission-time substitution hooks in).

**The contract.**

- **Opt-in, path-marked, two parallel axes.** Nothing is auto-detected. The author declares *paths* (vectors of keywords/indices, `get-in` grain) inside well-known data shapes as `:sensitive` and/or `:large`. The two axes are independent and compose.
- **Six metadata-bearing marking sites** — each accepts an optional `{:sensitive [paths] :large [paths]}` on its registration map: `reg-event-{db,fx,ctx}`, subscriptions (`reg-sub`), effects (`reg-fx`), coeffects (`reg-cofx`), flows (`reg-flow`), plus **app-db marks per frame** via `add-marks` (additive merge — paths not mentioned keep prior state) and `set-marks` (wholesale replace — unmentioned paths CLEARED; schema-attached marks preserved either way). Both `add-marks` / `set-marks` are pure declarations that return `frame-id` and do NOT mutate `app-db`. **State machines are the schema-first exception:** `reg-machine` is two-arity `(machine-id machine-map)` and accepts NO `:sensitive` / `:large` metadata keys — a machine's `:data` slots are marked via `:sensitive?` / `:large?` props on its `:data-schema` (rooted under `[:data …]` to match the snapshot shape), and, for the schema-less / coarse case, via the runtime `add-marks` / `set-marks` snapshot path-list; the two sources union (Spec 015 §6 / Spec 005 §Privacy).
- **`:sensitive` / `:large` registration metadata + whole-output overrides.** Per-path `:sensitive [paths]` / `:large [paths]`; whole-output `:sensitive? true/false` / `:large? true/false` force-mark or opt out and win over per-path on conflict. The `false` opt-out is the author's explicit assertion that a derived value is safe to surface.
- **Marks propagate across the dataflow** (footgun prevention, NOT a security-grade taint system). Seven propagation boundaries: event-args → app-db (a sensitive event-arg written to app-db widens the destination path's mark transitively), app-db → subs, sub → sub, app-db → flows, cofx → handler, machine `:data`, fx inputs. The framework trusts author overrides.
- **Two contracts at the emission boundary — keep the wire marker and the display sentinel distinct.** The spec runs two layered vocabularies and they are NOT interchangeable; a port that emits the wrong one breaks tool consumers:
  - **Wire marker (Spec 009 §Size elision).** What the shared wire-elision walker substitutes for a large value at *every off-box egress* — the trace bus, the 009 always-on event/error-emit records, and the MCP wire. The marker is **`:rf.size/large-elided {:bytes N :head "…" :handle [:rf.elision/at <path>]}`** (sensitive substitutes to `:rf/redacted`). This is the shape MCP/Xray/log consumers parse and the shape the `:elided-large` count and the `[:rf.elision/at …]` re-fetch handle key off — emit anything else and the fetch-handle/counting semantics are lost.
  - **Display sentinel (Spec 015 §The display contract).** What a tool *renders* for a large value: `:rf/large {:bytes N :head "…"}` (large-only — MAY surface a size-confirmed click-to-expand), `:rf/redacted` (sensitive — opaque, MUST NOT be revealable), `:rf/redacted {:bytes N}` (both — size visible, content not, no `:head`). All keywords are framework-reserved; apps MUST NOT use them as payload values. This is a presentation contract for the panel, layered on top of the wire marker — it does not replace what the walker puts on the wire.
- **Substitution happens at EMISSION time, never mid-handler.** Real values flow through events → cofx → handler → fx → app-db → subs → views **unchanged** — handlers, sub-fns, fx-handlers ALWAYS see real values. The framework substitutes the wire marker only at the five observation surfaces marks MUST guard: (1) trace-bus emit, (2) Xray panel rendering, (3) MCP wire transport, (4) AI/LLM context handed off by tools, (5) third-party log sinks consuming the trace bus. The shared wire-elision walker is `re-frame.elision/elide-wire-value` — `:rf/redacted` for sensitive, **`:rf.size/large-elided` for large** — the **same walker the 009 always-on event/error-emit substrates run before fan-out**, so production observability records are also mark-respecting.
- **No runtime cost on the happy path.** Mark lookups happen only at emission time. The trace-bus emit path is compile-time elided in production per [009 §Production elision] — but the always-on event/error-emit substrates still run `elide-wire-value` in production, so production records never leak a marked value either.

**What the CLJS reference did (example).** Marks live in a per-frame side-table (alongside the schemas side-table, NOT a registrar kind), unioned with schema-attached `:sensitive?` / `:large?` marks at lookup time. Propagation is computed as a path-graph union at emit time (the spec also permits write-time taint-tracking — both conform). The sentinel substitution rides the same `goog.DEBUG` gate as the trace surface for trace-bus emit, and rides `elide-wire-value` for the always-on substrates and MCP wire. None of this layout is normative.

**Conformance fixtures.** Data-classification fixtures assert that a marked path is elided to the correct **wire marker** at the observation boundary while the real value still flows through the runtime. Verify: a `:sensitive`-marked app-db path yields `:rf/redacted` in trace / emit records but the real value in the handler; a `:large`-marked path yields the wire marker `:rf.size/large-elided {:bytes N …}` (the `:rf/large {:bytes N :head}` form is the Xray/display *rendering* of that elided value, not what the walker writes to the wire); propagation widens a sub's output mark from a sensitive input; `:sensitive? false` opts a sanitised sub out; `add-marks` merges and `set-marks` clears-then-sets as specified.

**Common spec-gap traps.**

- **Redacting mid-handler.** The framework must NOT redact before the handler runs — handlers need real values. Substitution is emission-time only.
- **Forgetting a consumer.** All five observation surfaces must consult marks. A port that guards the trace bus but not the always-on event/error-emit records, the MCP wire, or a log-sink listener still leaks. Route every observer through the one `elide-wire-value` walker.
- **`add-marks` vs `set-marks` semantics.** `add-marks` merges; `set-marks` replaces wholesale and CLEARS unmentioned paths. Both preserve schema-attached marks. Getting the merge-vs-replace backwards silently widens or drops privacy coverage.
- **Treating 015 as optional.** It is v1-required. Do not gate it behind a D3 question.

---

## Acceptance gate 1 — `:core/*` conformance

At this point a port with `{Q1=no, Q2=no, Q3=no, Q4=via-host-types-or-no, Q5=no, Q6=no, Q7=no, Q8=no, Q9=no}` is feature-complete against its claim. The harness runs the `:core/*` fixtures, which should all pass.

**Who runs it.** Two tiers (Q14 / L3 — see [`spec/design.md` §L3](../spec/design.md) and [`output-format.md` §Discipline](output-format.md)):

- **Per-EP slice gate (agent-run when it can).** When the agent wrote an EP's code and has local tool access, before calling that EP landed it runs the **smallest relevant slice it can determine from the port's own scripts** — the port's unit-test command for the EP's module, or a targeted `:core/*`-subset conformance run for the EP's capability tags. NOT the full suite, NOT an invented build mechanic. If it can't determine or run a slice (no local tooling, no port script yet), it reports that explicitly with the reason. This keeps a tight feedback loop on the runtime code the agent just wrote.
- **The full gate stays engineer-owned.** The complete `:core/*` gate-1 pass (and the gate-2 full-claim pass) is the engineer's to run; the agent runs the full harness only **when the engineer asks**, then reports/diagnoses the score. The agent does not drive the engineer's full toolchain or any release-sized suite unbidden.

Either way the agent's job at the gate is to surface the score and diagnose failures — and to record exact commands/results (or a clear not-run reason), not a bare prose claim.

See `conformance.md` for the harness shape, the EDN-handler-body DSL, and what to do when a fixture won't pass.

**If anything fails:** is the failure a spec gap or an implementation bug? The leaf `conformance.md` covers the diagnosis.

---

## Optional EPs (per Phase 1's D3 scope)

For each capability the port declared `yes` for in D3, walk the matching EP. Suggested order if multiple are in scope (each can be done in isolation; the order minimises rework):

### EP 010 — Schemas (if D5 ≠ no)

**Read.** [`spec/010-Schemas.md`](https://day8.github.io/re-frame2/spec/010-Schemas/) and [Implementor-Checklist §Schemas](https://day8.github.io/re-frame2/spec/Implementor-Checklist/#schemas-if-q4-is-yes).

**Contract.** `:schema` registration metadata (the unified vocabulary term across every surface — v1's `:spec` metadata key was renamed to `:schema` with no back-compat alias, per [`spec/010-Schemas.md` §Vocabulary unified](https://day8.github.io/re-frame2/spec/010-Schemas/)); `reg-app-schema`; validation at boundaries (handler entry, sub return, fx args, app-db at registered paths); validation-failure trace events; production elision per EP 009.

**Common trap.** Open vs closed shapes — open by default is non-negotiable; opt-in `:closed true` per registration.

### EP 008 — Testing (always recommended)

**Read.** [`spec/008-Testing.md`](https://day8.github.io/re-frame2/spec/008-Testing/).

**Contract.** `with-frame`, `dispatch-sync`, `compute-sub`, epoch surface (`epoch/restore-epoch` + `epoch/replace-app-db!`) for snapshot/restore, `:fx-overrides`, `:interceptor-overrides`, framework adapter (cljs.test → vitest/pytest/etc.). JVM-runnable for the pure-function surface.

### EP 005 — State machines (if D3 Q1 = yes)

**Read.** [`spec/005-StateMachines.md`](https://day8.github.io/re-frame2/spec/005-StateMachines/) — the spec's largest EP (~2,900 lines). Plan one full session on the read alone before implementing.

**Contract.** `reg-machine`, transition tables, `make-machine-handler`, `[:rf.runtime/machines :snapshots <id>]` reserved **runtime-db** storage (the runtime-db partition, NOT app-db — per EP-0001's two-partition contract), drain extensions for `:raise`/`:always`/`:after`, hierarchy support per D3 Q1's sub-capability list, declarative `:spawn`.

**Capability sub-decisions.** D3 Q1 declared yes/no for each of: `:fsm/flat`, `:fsm/hierarchical`, `:fsm/eventless-always`, `:fsm/delayed-after`, `:fsm/tags`, `:fsm/parallel-regions`, `:fsm/final-states`, `:fsm/history`, `:fsm/registration-validation`, `:actor/own-state`, `:actor/spawn-destroy`, `:actor/cross-actor-fx`, `:actor/invoke`, `:actor/spawn-and-join`, `:actor/system-id`. Implement only the claimed sub-capabilities; the conformance corpus runs the matching fixture subset. **`:fsm/history`** is a first-class v1 capability (`:type :history` pseudo-states — shallow / deep / default-target, recorded in the revertible `:rf/history` snapshot slot; [`spec/005-StateMachines.md` §History states](https://day8.github.io/re-frame2/spec/005-StateMachines/)) backed by 10 `:fsm/history`-tagged corpus fixtures (9 named `history-*.edn` / `scxml-history-*.edn`, plus `machine-reg-error-grammar-not-in-v1.edn` which also carries the tag). A port claiming `:fsm/history` records/restores history and validates placement at registration (a misplaced node — machine root or flat top-level state, no owning compound — throws `:rf.error/machine-history-misplaced`; verify the exact category against `implementation/machines/src/re_frame/machines/lifecycle_fx/validation.cljc` and the `machine-reg-error-grammar-not-in-v1.edn` fixture's `:expect-error`). A port that doesn't implement it puts `:fsm/history` on `known-skipped-capabilities`. Enumerate the live `:fsm/*` sub-tag set from the fixtures at the pinned commit (`grep -rho ':fsm/[a-z-]*' spec/conformance/fixtures/ | sort -u`) rather than from a prose list — for `:fsm/*` the fixtures lead the README (corpus-ahead). **`:actor/*` is the exception — there the README + Spec 005 lead the fixtures (corpus-behind):** the README and Spec 005 declare all six actor tags, but the fixtures back only four — `:actor/own-state` and `:actor/cross-actor-fx` are spec-mandated yet fixture-less today, so `grep`-the-fixtures *under-claims* the actor axis. Enumerate `:actor/*` from `spec/conformance/README.md` + Spec 005 (cross-checked against the fixtures), and a fixture-less spec capability lands on `known-skipped-capabilities` only if you don't implement it. See [`conformance.md` §Capability tagging](conformance.md#capability-tagging) for the general "fixtures score, the README+Spec define the vocabulary, the two can diverge either way" rule.

**Common trap.** Drain extensions interact with EP 002's run-to-completion drain. Plan the integration carefully — `:always` and `:after` are subtle.

### EP 012 — Routing (if D3 Q2 = yes)

**Read.** [`spec/012-Routing.md`](https://day8.github.io/re-frame2/spec/012-Routing/).

**Contract.** `reg-route`, `match-url`, `route-link`, `:rf.nav/push-url` fx, `[:rf.runtime/routing :current]` + `[:rf.runtime/routing :pending-navigation]` reserved **runtime-db** storage (the runtime-db partition, NOT app-db — per EP-0001's two-partition contract), navigation tokens, fragment handling, `:can-leave` guard (a sub-id whose boolean value gates navigation away).

### EP 011 — SSR (if D3 Q3 = yes)

**Read.** [`spec/011-SSR.md`](https://day8.github.io/re-frame2/spec/011-SSR/) — including §Streaming SSR (shipped in v1).

**Contract.** `:platforms` metadata on `reg-fx`, `render-to-string`, `:rf/hydrate`, hydration-mismatch detection, `init-platform`. **Streaming SSR is shipped** (`:rf/suspense-boundary` markers → shell-with-template-fallbacks → per-continuation resolved chunks → final `__rf_payload`): the `re-frame.ssr.streaming` surface (`render-shell!` / `render-continuation!` / `build-final-payload`) drives the `:ssr/suspense-boundary`, `:ssr/hydration-payload`, `:ssr/chunked-response` capabilities. The conformance corpus exercises it via the Mode-B `:call` ops `:ssr.streaming/render-shell` / `:ssr.streaming/render-continuation` / `:ssr.streaming/build-final-payload` (see [`conformance.md`](conformance.md) §Mode B); a Q3=yes port either implements streaming or puts those capability tags on `known-skipped-capabilities`.

### EP 013 — Flows (if D3 Q8 = yes)

**Read.** [`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/).

**Partition-aware flow inputs (EP-0001).** Now that frame-state is two partitions (see [EP 002 §The two-partition frame contract](#the-two-partition-frame-contract)), a flow's `:inputs` resolve against the pending **frame-state**, not app-db alone:

- A **bare** input path (e.g. `[:width]`) reads the **app-db** partition — the default.
- A **partition-qualified** input `[:rf.db/runtime :rf.runtime/...]` (e.g. `[:rf.db/runtime :rf.runtime/routing :current]`) reads the **runtime-db** partition. Binary syntax only — there is NO redundant `[:rf.db/app …]` form (bare already means app-db).
- **Any** flow (user or framework) MAY *read* runtime-db via qualified inputs; only the *write* is reserved. **Flow OUTPUTS write only app-db** — a flow's `:path` lands in app-db; runtime-db writes stay framework-reserved.
- The dirty-check / recompute trigger is **dual-partition**: a runtime-only commit must still re-fire a flow whose inputs read runtime-db (tying the trigger only to app-db publication is a SILENT regression). A flow throw aborts BOTH partitions of the pending frame-state.

Per the EP-0001 normative text (flows-over-runtime-db inputs) and [`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/).

**Conformance.** Claims the `:flow/*` family. Enumerate the sub-tags from the fixtures at the pinned commit (`grep -rho ':flow/[a-z-]*' spec/conformance/fixtures/ | sort -u`) — at corpus HEAD ~19 sub-behaviours (basic / trace / init / reg-v / poke / toggle / topo / multi-input-topo / dirty-check / recompute-on-input-change / frame-scoped / frame-destroy-teardown / hot-reload / lifecycle-emits-traces / …). Sub-behaviours not implemented go on `known-skipped-capabilities`.

### EP 014 — HTTP (if D3 Q9 = yes)

**Read.** [`spec/014-HTTPRequests.md`](https://day8.github.io/re-frame2/spec/014-HTTPRequests/) plus [Pattern-RemoteData](https://day8.github.io/re-frame2/spec/Pattern-RemoteData/) and [Managed-Effects](https://day8.github.io/re-frame2/spec/Managed-Effects/) (the managed-fx lifecycle that HTTP rides on).

### EP 007 — Stories (if D3 Q5 = yes)

**Read.** [`spec/007-Stories.md`](https://day8.github.io/re-frame2/spec/007-Stories/). Note: post-v1 in the CLJS reference too; expect spec churn.

---

## Acceptance gate 2 — full claimed-capability conformance pass

When every claimed capability is implemented, the full conformance harness runs with the capability filter set to D7's claim list (the engineer runs it, or asks the agent to — same who-runs-it rule as gate 1: the per-EP slice the agent already ran is its own, but this full-claim pass is engineer-owned and the agent runs it only when asked). Score must be `claimed-applicable / claimed-applicable`. Any failure that's not a spec gap is a port bug; any spec gap is drafted as a `day8/re-frame2` GitHub issue and filed only after engineer OK (per [`cardinal-rules.md` §§8–9](cardinal-rules.md)).

When the gate passes, the port is v1-complete against its claim.
