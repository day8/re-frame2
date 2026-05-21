> **Type:** Design (post-v1 / v1.1 design pass)
> Status: deferred from v1; design-only pass tracked by rf2-cl8me.

# Design — Transducer-shaped event router (substrate-agnostic)

This document is the **v1.1 design pass** for re-frame2's event-processing pipeline as a transducer.
It is **non-normative** for v1: the v1 runtime ships the existing drain loop owned by
[002-Frames §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics).
This file specifies the *shape* a v1.1 router would take and the reference primitives that
accompany it. A small scaffold ships at `implementation/core/src/re_frame/router_transducer.cljc`
with CLJS/CLJ unit-test coverage so the design can be exercised in the REPL — the runtime
does **not** consume it yet.

The doc has four sections:

1. **Motivation** — why a transducer shape is worth pulling forward.
2. **Contract** — `frame-transducer-factory`, the three reducing-function presets, and the
   substrate-agnostic boundary.
3. **Compatibility with v1's drain loop** — additive-then-replacement story; what stays, what
   moves, what disappears.
4. **Interactions** — with Spec 005 machines, Spec 011 SSR, Spec 012 URL routing, and Spec 009
   instrumentation.

Pointer back: [002-Frames §Transducer-shaped event processing](002-Frames.md#transducer-shaped-event-processing-substrate-agnostic-router)
is the deferred-status anchor in the normative spec; this document is what it points to.

---

## 1. Motivation

The v1 router (`re-frame.router/drain-loop!`) bakes three concerns into one piece of code:

- **Per-event step** — resolve handler, run the interceptor pipeline, produce a new state, apply
  effects. This is *reusable*: SSR runs the same step, test harnesses run the same step, a
  pair-tool replay runs the same step.
- **State accumulation** — how successive new-state values are committed: synchronously into the
  app-db container (v1), into a batched commit at the end of the cascade (an open question
  raised by 005's `:always` transitions), or into a derived list of states (for time-travel
  replay).
- **Scheduling** — when the next event runs: synchronously under `dispatch-sync` (v1), under
  the drain pump on the host's microtask queue (v1's default), or under an externally-driven
  pump (test harness, SSR loader fan-in).

A transducer cleanly separates concern 1 (the transducer `xf`) from concerns 2-and-3 (the
reducing function and the driver). The same transducer pipes through:

- **`sync`** — a reducing function that commits every state synchronously. Equivalent to
  `dispatch-sync`.
- **`queued`** — a reducing function that drains a queue to fixed point per Spec 002's
  run-to-completion rules.
- **`batch`** — a reducing function that accumulates intermediate states and commits *once* at
  cascade-settle. Useful when a cascade fires N events that each touch app-db and the host can
  defer paint to the cascade boundary (Spec 005's `:always` is the motivating case).

The driver (when does the next event arrive?) is a third axis, orthogonal to the
reducing function. v1 ships one driver, the host-microtask pump; a v1.1 transducer formulation
makes the driver a parameter.

---

## 2. Contract

### 2.1 Primitive: `frame-transducer-factory`

```clojure
(frame-transducer-factory frame) → transducer
```

Given a `frame` record (per [002 §What lives in a frame](002-Frames.md#what-lives-in-a-frame)),
return a stateless transducer that maps **dispatch envelopes** (per
[002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)) to
**step-results**. A step-result is the closed map:

```clojure
{:db-before  <value>           ;; app-db before the step
 :db-after   <value>           ;; app-db after the step (= db-before on no-op)
 :event      <event-vector>    ;; the user-facing event
 :envelope   <envelope>        ;; the full envelope (passes through for tracing)
 :effects    <effects-map>     ;; the effects map produced by the handler
 :error      <error-or-nil>    ;; structured error per Spec 009 §Error contract; nil on success
 :rf/step    :ok | :no-handler | :frame-destroyed | :validation | :handler-throw}
```

The transducer itself is a single `comp` of pure mapping/filtering xforms, each owning one phase
of the v1 `process-event*` cascade:

```clojure
(comp
  (xf-resolve-handler  registry)   ;; envelope -> envelope with :handler resolved
  (xf-validate-event   registry)   ;; gated by :schema; tags :validation step on fail
  (xf-run-interceptors)             ;; runs the pipeline; reads cofx, produces effects
  (xf-commit-app-db    container)   ;; computes :db-after; does NOT write
  (xf-apply-fx         fx-registry) ;; pulls non-:db effects out for the reducing fn
  (xf-emit-trace))                  ;; tags trace events per Spec 009; pure side-effect-free
                                    ;; — actual emit happens in the reducing fn's commit step
```

**Substrate-agnostic boundary.** None of these xforms call into the rendering substrate. The
transducer produces a step-result; the **reducing function** decides whether to write
`:db-after` into the substrate container, whether to flush `:effects`, and when to schedule the
next event. This is what makes the transducer reusable across all three substrates (Reagent /
UIx / Helix), under JVM (no substrate), and inside a tool-pair replay (rewrites the reducing
function to capture a trace instead of committing).

### 2.2 Reducing-function presets

Three preset reducing functions ship with v1.1:

**`sync-rf`** — fully synchronous. Each step commits `:db-after` into the substrate container
and runs `:effects` inline. Cascade dispatches are pushed onto a thread-local queue and drained
to fixed point before `sync-rf` returns. Equivalent to v1's `dispatch-sync`.

```clojure
(transduce (frame-transducer-factory frame) sync-rf init-state envelope-seq)
;; => final state; substrate container holds the post-cascade db
```

**`queued-rf`** — the v1 drain shape. Commits `:db-after` on every step; the driver pump runs
the next envelope on the host's microtask queue. Cascade dispatches are appended to the frame's
FIFO. Equivalent to v1's `dispatch`.

**`batch-rf`** — accumulator. Steps update an in-memory `db` value without writing through to
the container. The reducing function commits the final accumulated value in a single
`replace-container!` call at cascade-settle. Effects are deferred to the same boundary. Useful
when:

- Spec 005 `:always` fires N intermediate transitions and the host can pay one commit for the
  whole cascade.
- A test harness wants to inspect every intermediate state without paying a substrate write.
- An SSR loader fan-in waits for all child fetches to settle before any view re-renders.

`batch-rf` is *not* a default — surfaces that need it opt in per-cascade via a per-call hint on
the envelope (`:rf/commit :batch` vs. `:eager` / `:sync`).

### 2.3 The driver

A driver is a function `(driver pump-fn) → driver-handle`. The driver decides *when* `pump-fn`
runs and returns a handle the runtime can use to schedule, pause, and tear down.

- **`microtask-driver`** — v1's behaviour. `pump-fn` runs under `js/queueMicrotask` (CLJS) or
  inline (JVM). Default.
- **`raf-driver`** — `pump-fn` runs under `requestAnimationFrame`. For frame-locked cascades.
- **`manual-driver`** — test harness drives `pump-fn` explicitly via `(tick handle)`. Used by
  the conformance corpus and SSR loaders.
- **`virtual-time-driver`** — a debug driver that advances under a controlled clock. Time-travel
  replay can use this to step through a recorded envelope-seq deterministically.

The driver is per-frame, set at `reg-frame` time via `:driver` (default `:microtask`). Frame
presets (per [002 §Frame presets](002-Frames.md#frame-presets--capability-bundles-for-common-configurations))
fix the driver: `:default` → `:microtask`, `:test` → `:manual`, `:ssr-server` → `:manual`,
`:story` → `:microtask`.

### 2.4 Public surface

Only three functions are exposed at the public substrate API:

```clojure
;; The primitive itself — used by the runtime, the test harness, and tools.
(re-frame.core/frame-transducer-factory frame) → transducer

;; Reducing-function constructors. Each closes over the frame; the transducer is reusable.
(re-frame.core/sync-rf       frame) → reducing-fn
(re-frame.core/queued-rf     frame) → reducing-fn
(re-frame.core/batch-rf      frame) → reducing-fn

;; Driver constructors (opaque handles).
(re-frame.core/microtask-driver) → driver
(re-frame.core/manual-driver)    → driver
```

`dispatch`, `dispatch-sync`, and `subscribe` retain their v1 signatures. They become *thin
wrappers* over the transducer-driver composition: `dispatch-sync` is `(transduce (factory frame)
(sync-rf frame) ...)`; `dispatch` enqueues onto the driver's pump. **No user-facing breakage** —
the transducer is an internal refactor that surfaces three new opt-in primitives for advanced
use.

---

## 3. Compatibility with v1's drain loop

The migration is **two staged**, not a flag-day:

**Stage A (v1.1 minor)** — additive. Ship `frame-transducer-factory`, the three reducing-fn
presets, and the driver primitives **alongside** the existing drain loop. The drain loop still
owns the default code path; the transducer primitives are a new surface for tools, tests, and
SSR loaders to consume. No runtime-internal call changes.

**Stage B (v2.0 major)** — replacement. The drain loop becomes a thin shim that constructs the
transducer + `queued-rf` + `microtask-driver` and runs them. The v1 `drain-loop!` /
`process-event!` decomposition disappears from the supported surface; its internals are
re-expressed in transducer terms. No user-facing API change — `dispatch` / `dispatch-sync`
behaviour is preserved bit-exact against the conformance corpus.

**What disappears.** `re-frame.router/drain-depth-default`, `mark-drainer!`, `clear-drainer!`,
`take-event!`, `run-one-pass!`, `force-release-on-halt!`, `try-release-on-empty!` — all are
specific to the v1 queue-pump shape. Under Stage B they collapse into a single `queued-rf` +
`microtask-driver` definition.

**What stays.** Spec 002's normative behaviour is unchanged: run-to-completion, FIFO ordering
within a frame, depth-exceeded rollback, `:dispatch-sync` reentrancy guard, frame-destroy
mid-drain semantics. Each is a property of the *reducing function* under Stage B, not of
ad-hoc drain code.

**Atomic rollback.** v1's depth-exceeded path (`handle-depth-exceeded!`) restores the
pre-cascade `:db` and emits a `:halted-depth` epoch. Under Stage B this becomes the
**transducer's `completing` arity**: when the reducing function detects depth exhaustion it
short-circuits via `reduced` carrying the rollback marker, and the completing arity emits the
epoch record. Pure, testable, no flag.

---

## 4. Interactions

### 4.1 Spec 005 — State machines

Machines-as-event-handlers (per [005 §Machines as event handlers](005-StateMachines.md)) sit
inside the transducer as just-another-handler. A machine transition that fires N `:always`
events from `:on-entry` enqueues N envelopes which the same transducer drains. `batch-rf` is
the natural home for `:always`-heavy cascades — N intermediate transitions, one commit.

### 4.2 Spec 011 — SSR

SSR's frame-per-request model wants `batch-rf` + `manual-driver`. A loader fan-in dispatches
N `:rf.server/fetch` events, the manual driver pumps them under the SSR runtime's
`render-to-string` orchestration, and `batch-rf` commits the final db once before the hiccup
tree is materialised. This is currently implemented ad-hoc in `re-frame.ssr`; the v1.1 spec
folds the ad-hoc code into the transducer + reducing-fn + driver triple.

### 4.3 Spec 012 — URL routing

Spec 012 governs URL ↔ frame state. Routing dispatches `:rf.route/navigate` and standard
runtime events; those events flow through the transducer like any other. The transducer
formulation does not interact with Spec 012's normative surface — they live on different
axes. The v1 deferral worried about an overlap; on re-examination there is none.

### 4.4 Spec 009 — Instrumentation

Each xform in the transducer's `comp` is the natural seam for a trace event:
`xf-resolve-handler` emits `:rf.handler/resolved`, `xf-run-interceptors` emits per-interceptor
`:rf.interceptor/before` and `:rf.interceptor/after`, `xf-commit-app-db` emits `:rf.db/replaced`
on a real diff, `xf-apply-fx` emits `:rf.fx/handled` per fx. The trace surface stays exactly
what Spec 009 defines; the transducer formulation just makes the emit-sites lexically obvious.

### 4.5 Tool-Pair

A pair-tool replay rewrites the reducing function. The transducer (the xforms) stays bit-exact;
the reducing function commits to a **trace-buffer** instead of to the substrate container. This
is how time-travel replay can stay deterministic across hot-reloads — the per-step xform is
re-evaluated under the new code, but the envelope-seq is replayed verbatim.

---

## 5. Implementation roadmap

**Phase 1 — scaffold (this bead, rf2-cl8me).**
- `implementation/core/src/re_frame/router_transducer.cljc` — pure functions: the
  `frame-transducer-factory` stub, the three reducing-fn presets, the manual-driver shape.
  No wiring into the live runtime. CLJS/CLJ-unit-test-covered.

**Phase 2 — v1.1 additive (separate bead).**
- Wire `manual-driver` + `batch-rf` to the SSR loader fan-in and replace the ad-hoc code in
  `re-frame.ssr`. Single isolated change; no behavioural impact outside SSR.
- Publish the transducer + driver primitives at `re-frame.core`. Document as opt-in.

**Phase 3 — v2.0 replacement (separate bead, post-v1.1).**
- Re-express `re-frame.router/drain-loop!` as `(queued-rf + microtask-driver)`. Run the full
  conformance corpus on the rewrite; require bit-exact equivalence on trace event order,
  app-db value sequence, and epoch record shape. Remove the v1 drain-loop ad-hoc code.

---

## Open questions (deferred to Phase 2)

- **Driver ↔ frame coupling.** Should the driver be per-frame (current sketch) or
  per-process? A per-process driver simplifies multi-frame coordination; per-frame allows
  Story / SSR / Pair drivers to coexist in one process. Lean per-frame — the multi-process
  per-frame story already exists for routers and queues.
- **Step-result vocabulary.** The `:rf/step` enum (`:ok`, `:no-handler`, …) overlaps with
  Spec 009's `:rf.handler/...` trace event family. Reconcile before Phase 2: probably collapse
  `:rf/step` to a flat `:rf.step/*` keyword set under Conventions §Reserved namespaces.
- **Cancellation semantics.** A long-running cascade under `manual-driver` may want
  cancellation. v1's drain has no notion of mid-cascade cancel (it short-circuits only on
  depth-exceeded and frame-destroy). Decision: defer to Phase 2 unless a Tool-Pair surface
  forces it sooner.

---

## Cross-references

- [002-Frames §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics) — v1 normative behaviour preserved across both stages.
- [002-Frames §Transducer-shaped event processing](002-Frames.md#transducer-shaped-event-processing-substrate-agnostic-router) — the deferred-status anchor that points here.
- [005-StateMachines](005-StateMachines.md) — `:always` cascades motivating `batch-rf`.
- [011-SSR](011-SSR.md) — frame-per-request + loader fan-in motivating `manual-driver`.
- [009-Instrumentation](009-Instrumentation.md) — trace-emission sites become the transducer's xform boundaries.
- `implementation/core/src/re_frame/router_transducer.cljc` — Phase-1 reference scaffold.
- `implementation/core/test/re_frame/router_transducer_test.cljc` — scaffold unit tests.
