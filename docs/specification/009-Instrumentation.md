# Spec 009 — Instrumentation, Tracing, and Performance Integration

> The trace event stream is a **pattern-level** primitive — every implementation supplies a structured trace stream from well-defined points in the runtime. Trace events are open maps with stable required keys, consistent with the open-maps-with-schemas principle. The CLJS-specific bits — `goog-define` for production elision, the Chrome Performance API bridge, `register-trace-cb` as the listener API — are reference-implementation details. Other-language implementations resolve elision and listener delivery differently.
>
> For where the trace bus sits in relation to the runtime's other components (registrar, drain loop, sub-cache, substrate adapter), see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

re-frame2 emits a stream of **trace events** describing what's happening at runtime — dispatches, interceptor steps, effect handler calls, subscription updates, frame lifecycle, machine transitions. Tools subscribe to this stream.

The tracing surface is designed to be **stable** (required fields don't change), **extensible** (open maps; new fields are additive), **cheap on the hot path** (near-zero overhead with no listeners), and **cross-platform** (JVM-runnable for the data; Chrome Performance integration is CLJS-side).

**All tracing — including the Performance API bridge — is compile-time eliminated in production builds.** No exceptions. Production binaries contain zero trace code. Tracing is a dev-time concern only.

## The trace event model

A trace event is an immutable map describing one *span* of work in the runtime — an event's processing, a sub computation, a render, an effect. Events flow into a single per-application trace stream; subscribers listen.

The shape is documented below.

### Core fields (required on every event)

```clojure
{:id        <int>            ;; auto-incrementing trace id; unique per process
 :operation <kw-or-vec>      ;; what's being traced — typically the event-id, sub-id, etc.
 :op-type   <kw>             ;; discriminator: :event, :sub/run, :sub/create, :render, :raf,
                              ;;   :event/do-fx, :reagent/quiescent, :rf.machine/transition, etc.
 :start     <ms>             ;; start timestamp (host clock)
 :end       <ms>             ;; end timestamp (set by finish-trace)
 :duration  <ms>             ;; end - start
 :child-of  <id>             ;; parent trace id, for cascade correlation
 :tags      {...}}           ;; open-ended bag for op-type-specific fields
```

### Re-frame2 additions (additive, optional)

```clojure
{:frame  :todo            ;; frame keyword — multi-frame disambiguation
 :source :ui}             ;; :ui, :timer, :http, :machine, :repl — origin of the trigger
```

These are top-level fields on every event for re-frame2-aware traces; tools written against pre-v2 traces ignore them.

### Dispatch correlation: `:dispatch-id` / `:parent-dispatch-id`

Pair-shaped tools and per-event diagnostics need to correlate the *cascade* a dispatch belongs to — "this `:event/dispatched` was emitted by an effect that ran inside the cascade started by *that* dispatch." The runtime stamps two fields onto every `:event/dispatched` trace event under the top-level `:tags`:

```clojure
{:tags {:dispatch-id        <uuid-or-counter>   ;; this dispatch's identity
        :parent-dispatch-id <uuid-or-counter>}  ;; the dispatch that caused this one (if any)
 ...}
```

Semantics:

- **`:dispatch-id`** is allocated by the runtime when a dispatch is enqueued, before routing. Implementations may use a process-monotonic counter, a UUID, or any opaque value with the same uniqueness contract: distinct within a single process for the lifetime of the trace surface. Tools treat it as opaque.
- **`:parent-dispatch-id`** is set when the dispatch was emitted as a side-effect of another event's processing — typically inside an `fx` handler. Concretely: when an `fx` handler running inside the do-fx phase of dispatch *D₁* invokes `(rf/dispatch ...)`, the runtime records the new dispatch's `:parent-dispatch-id` as *D₁*'s `:dispatch-id`. If the dispatch was initiated outside any in-flight event (a timer, a UI handler, the REPL, the SSR boot path), `:parent-dispatch-id` is absent.
- **Top-level dispatch.** A dispatch with no `:parent-dispatch-id` is a *root* of a cascade. Pair-shaped tools draw cascade trees by following `:parent-dispatch-id` upward.
- **Distinct from `:child-of`.** The existing `:child-of` field on every trace event correlates *spans* (an `:event` span's children include its `:event/do-fx` span, which in turn parents each fx call). `:dispatch-id` / `:parent-dispatch-id` correlate *dispatches* — events queued through the router. The two are orthogonal: `:child-of` chains span the lifecycle of one event's processing; `:parent-dispatch-id` chains cascading dispatches across events.
- **Production elision.** Both fields ride the trace stream and are elided in production with the rest of the trace surface.

Tools consume these two fields to build dispatch-cascade views: "show me all dispatches descended from `[:user/login ...]`" is a transitive walk over `:parent-dispatch-id`.

### Origin tagging: `:origin`

When a tool (the pair tool, a story runner, the REPL, the SSR boot path) needs its own dispatches distinguishable from application dispatches, it can tag them with an `:origin` opt at dispatch time (per [002 §Dispatch origin tagging](002-Frames.md#dispatch-origin-tagging)). The runtime lifts the value onto every `:event/dispatched` trace event under `:tags :origin`:

```clojure
{:tags {:origin :pair        ;; tag set by the dispatching tool; default :app
        :dispatch-id ...
        ...}
 ...}
```

`:origin` is unconstrained at the framework level — tools and applications agree on values (`:pair`, `:claude`, `:story`, `:test`, etc.). The default is `:app`. User application code typically omits the opt; tool surfaces set it so post-mortem filters like "show me only the dispatches I (the pair tool) issued during this session" become a one-key filter on the trace stream.

`:origin` is **distinct from `:source`**: `:source` describes the *trigger kind* (`:ui` / `:timer` / `:http` / `:machine` / `:repl` / `:ssr-hydration`) and is essentially a "what woke the runtime?" axis; `:origin` describes the *actor identity* (which tool or app subsystem emitted the dispatch) and is used for filtering. Tools may set both.

### `:op-type` vocabulary

Core values: `:event`, `:sub/run`, `:sub/create`, `:render`, `:raf`, `:event/do-fx`, `:reagent/quiescent`, `:sync`, `::fsm-trigger`.

Additional values for re-frame2 concerns:

- `:frame/created` / `:frame/reset` / `:frame/destroyed` — frame lifecycle.
- `:rf.machine.lifecycle/created` / `:rf.machine.lifecycle/destroyed` — machine instance lifecycle.
- `:rf.machine/event-received` / `:rf.machine/transition` / `:rf.machine/snapshot-updated` — machine activity.
- `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` — state-machine `:after` timer lifecycle (per [005 §Trace events](005-StateMachines.md#trace-events)). The `*/stale-*` form is the canonical naming for [§stale-detection trace events](Pattern-StaleDetection.md) — see also `:route.nav-token/stale-suppressed` below.
- `:route.nav-token/allocated` / `:route.nav-token/stale-suppressed` — navigation-token lifecycle (per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)). `*-allocated` fires when a navigation cascade begins; `*-stale-suppressed` fires when an async result arrives carrying a now-superseded token. Same epoch idiom as the machine-`:after` timer events.
- `:route.url/fragment-changed` / `:rf.route/navigation-blocked` — fragment-only URL change emission (per [012 §Fragments](012-Routing.md#fragments); distinct from the runtime event `:rf/url-changed` which fires on every URL transition) and pending-nav protocol blockage (per [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol)).
- `:rf.registry/handler-registered` / `:rf.registry/handler-cleared` / `:rf.registry/handler-replaced` — registration changes (hot reload). The canonical trio: `-registered` for a fresh id, `-cleared` for an explicit removal, `-replaced` when re-registration overwrote an existing id (the typical hot-reload case).
- `:error` / `:warning` — universal severity discriminators for failure events. The category-specific identity lives in `:operation` (e.g. `:rf.error/handler-exception`); see [§Error contract](#error-contract) for the authoritative model.

Consumers filter by `:op-type` (or `:frame`, or `:source`) to get the slice they care about. Adding new `:op-type` values is non-breaking — tools ignore what they don't understand.

### `:tags` is the open-ended bag

Variable per-event data goes in `:tags`. Existing examples: `:app-db-before`, `:app-db-after`, `:input-signals`, `:cached?`, `:value`, `:error`, `:reaction`. New tags can be added without breaking consumers. Use `:tags` for op-type-specific data; reserve top-level keys for fields universal across all events.

### Open shape; new fields are additive

The map is open. New fields can be added by future versions without breaking consumers — listeners read what they understand and ignore the rest. The forward-compat commitments:

- **Required top-level fields** (`:id`, `:operation`, `:op-type`, `:start`, `:end`, `:duration`, `:child-of`, `:tags`) are stable. Removing or renaming any is a breaking change.
- **Re-frame2 additions** (`:frame`, `:source`) are stable once shipped; they are present on every re-frame2 trace event.
- **Op-type-specific fields inside `:tags`** are stable within their op-type. New optional tag keys are additive; existing keys don't change shape.
- **New `:op-type` values** can be added without breaking existing tools — tools filter the values they recognise.

## Subscription / consumption

re-frame2's trace API uses **batched, debounced delivery** — listeners receive collections of trace events at a regular cadence (default 50ms). This is essential for performance: per-event delivery on a hot dispatch loop would slow the host application; batching amortises cost and gives consumers a chance to coalesce updates.

### The listener API

The canonical listener API has one shape:

```clojure
(rf/register-trace-cb key callback-fn)
(rf/register-trace-cb key callback-fn opts)
;; Subscribes callback-fn to receive trace events. Same key replaces any
;; previously-registered listener under that key.
;;
;; Arguments:
;;   key         — keyword identifying the listener (replaces same-key registration)
;;   callback-fn — invoked with the trace payload (see :batched? below for shape)
;;   opts        — optional map. Recognised keys:
;;                   :batched? — boolean (default true).
;;                     true  → callback-fn receives a collection of trace events
;;                             (the debounced batch); signature (fn [traces] ...)
;;                     false → callback-fn receives one trace event per call;
;;                             signature (fn [trace] ...). Use sparingly.

(rf/remove-trace-cb key)
;; Unsubscribes the listener registered under key.
```

Conventional keys: `:my-app/recorder`, `:my-app/timing-monitor`, etc. The two-arity form is the typical case; the three-arity form opts out of batching (per [§Per-event delivery](#per-event-delivery--opt-out-of-batching) below).

#### `register-epoch-cb` — assembled-epoch listener

Alongside the raw trace stream, the framework exposes a parallel **assembled-epoch listener** API. Where `register-trace-cb` delivers a debounced batch of raw spans, `register-epoch-cb` delivers one fully-assembled `:rf/epoch-record` (per [Spec-Schemas](Spec-Schemas.md#rfepoch-record)) per drain-settle:

```clojure
(rf/register-epoch-cb key callback-fn)
;; Subscribes callback-fn to receive assembled epoch records.
;;
;; Arguments:
;;   key         — keyword identifying the listener (replaces same-key registration)
;;   callback-fn — invoked with one :rf/epoch-record per drain-settle.
;;                 Signature: (fn [epoch-record] ...)
;;
;; The record is the same shape the runtime appends to (rf/epoch-history frame-id):
;; assembled :event-id / :trigger-event / :db-before / :db-after, plus the structured
;; :sub-runs / :renders / :effects projections derived from the cascade's traces.

(rf/remove-epoch-cb key)
;; Unsubscribes the listener registered under key.
```

**Invocation rules** (mirrors `register-trace-cb`):

- **Per drain-settle, not per event.** The callback fires once when the run-to-completion drain reaches an empty queue and the epoch record is committed; multi-event cascades produce one record (and one callback invocation), not one per event.
- **Not batched.** Unlike `register-trace-cb`'s default debounce window, epoch records are delivered as they commit — a drain settling produces an immediate callback. The drain itself coalesces; no further batching is added on top.
- **After commit.** The callback receives a fully-formed record with `:db-after`, `:sub-runs`, `:renders`, `:effects`, and any optional `:trace-events` populated. The record has already been appended to the frame's `epoch-history` ring buffer when the callback runs.
- **Exception isolation.** An exception thrown by an epoch callback is caught, logged via `re-frame.loggers`, and does not propagate. One broken epoch listener cannot break the app or block other listeners (raw-trace or epoch).
- **Listener ordering** is not contract.
- **Production elision.** The epoch listener machinery is gated on the same `re-frame.interop/debug-enabled?` flag (alias of `goog.DEBUG`) as the raw-trace surface — see [§Production builds](#production-builds-zero-overhead-zero-code). Production builds elide registration, dispatch, and the epoch ring-buffer all together.

**When to use which.** `register-trace-cb` is the right shape for tools that need raw fine-grained spans (the Chrome Performance bridge, custom timing displays). `register-epoch-cb` is the right shape for tools that route diagnostics off "what just happened in this cascade" — pair-shaped tools, post-mortem dashboards, anything that wants the structured `:sub-runs` / `:renders` / `:effects` projection without re-folding the raw trace stream.

The two listener APIs are independent: tools may register either, both, or neither. They share the production-elision gate but have separate listener registries; no listener of one kind can interfere with the other.

### Listener invocation rules

- **Batched delivery.** Listeners receive a collection of trace events accumulated over the debounce window (default 50ms). Tools that want to react to a specific event can scan the batch.
- **Debounced.** A 25ms grace window after the most recent emit before delivery, capped at 50ms total. Avoids constant timeout setting/cancelling on busy dispatch loops.
- **After settle.** A trace event lands in the batch only after its underlying span completes (`finish-trace` runs). Listeners see fully-formed events with `:end` and `:duration` populated.
- **Exception isolation.** An exception thrown by a listener is caught, logged via `re-frame.loggers`, and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools.
- **Buffer flush after delivery.** After a batch is delivered to all listeners, the framework's internal *delivery* buffer is reset. The delivery buffer is the short-lived collection that accumulates events between debounce windows; it is distinct from the retain-N ring buffer described next.

### Retain-N trace ring buffer (dev-only)

In dev builds, the framework maintains a **retain-N trace ring buffer** alongside the debounced delivery path. The ring buffer holds the most recent N completed trace events and is queryable. This lets pair-shaped AI tools, REPL-attached debuggers, and post-mortem dashboards read recent activity without having to be registered as a `register-trace-cb` listener at the time the events fire.

Contract:

| API | Signature | Notes |
|---|---|---|
| `(rf/trace-buffer)` | `() → vector` | Returns the buffer's current contents, oldest-first. Empty when no events have been recorded. |
| `(rf/trace-buffer opts)` | `(opts) → vector` | Optional filter: `{:frame frame-id}`, `{:op-type kw}`, `{:since trace-id}`. Filters compose. |
| `(rf/clear-trace-buffer!)` | `() → nil` | Empties the buffer. Tooling uses this between sessions. |
| `(rf/configure :trace-buffer {:depth N})` | `(N) → nil` | Configure depth; default 200 events. `0` disables the ring buffer (delivery path still works). |

Semantics:

- **Ring discipline.** When the buffer is full, the oldest event is evicted as a new one is pushed. No allocation churn beyond the slot count.
- **Same events as delivery.** Every event that lands in the delivery buffer also lands in the ring buffer. Ring-buffer events are fully-formed (`:end` and `:duration` populated, per [§Listener invocation rules](#listener-invocation-rules)).
- **Independent of listeners.** A tool that attaches *after* events have fired can read the most-recent N from the ring buffer to bootstrap its view; a tool that wants a continuous live feed registers a `register-trace-cb` listener as well.
- **Production elision.** The ring buffer, like the rest of the trace surface, is compile-time eliminated in production builds (per [§Production builds](#production-builds-zero-overhead-zero-code)). `(rf/trace-buffer)` returns an empty vector in production, and the buffer itself is not allocated.

Why this is a framework primitive (not a 10x-specific concern): pair-shaped tools, REPL companions, and any non-10x consumer needs recent-history access. Locating the buffer in the framework means external tools depend on a stable framework primitive rather than on 10x's internal data structures. See [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) for the full consumption pattern.

### Per-event delivery — opt out of batching

A listener that genuinely needs per-event delivery (rare) opts out via the three-arity form documented in [§The listener API](#the-listener-api) above:

```clojure
(rf/register-trace-cb :my/realtime callback-fn {:batched? false})
;; callback-fn receives one trace event per call instead of a batch.
```

Use sparingly — most tools should batch.

## Emitting trace events — the macro suite

re-frame2 emits traces using a small macro suite:

```clojure
(trace/with-trace {:operation event-id
                   :op-type   :event
                   :tags      {:event event-v}}
  ;; ... do work ...
  (trace/merge-trace! {:tags {:app-db-before @db}})
  (run-handlers!)
  (trace/merge-trace! {:tags {:app-db-after @db}}))
```

### `with-trace`

Opens a trace span, runs the body, automatically closes. The trace's `:start` is set on entry; `:end` and `:duration` are computed on exit (via `finish-trace`).

Nested `with-trace` calls are linked: each inner trace's `:child-of` is the enclosing trace's `:id`. The mechanism is a dynamic var (`*current-trace*`) holding the innermost open trace; `merge-trace!` uses it to know which trace to update.

### `merge-trace!`

Adds tags or top-level fields to the *currently-open* trace span. Useful for capturing data that's only available partway through the span (e.g., the new `app-db` value after a handler runs). Tags merge into the existing tags; top-level keys overwrite.

### `finish-trace`

Closes the open trace, computes its duration, pushes it onto the trace buffer, and triggers the debounce-scheduler. Called automatically by `with-trace` on exit (including exceptional exit).

### Compile-time elision

All three macros expand to `(when re-frame.interop/debug-enabled? ...emit...)` gates. `debug-enabled?` is an alias of `goog.DEBUG` on CLJS (default `true` in dev, `false` in `:advanced` production builds); when the constant is `false` the closure compiler eliminates the gated branch and the macros become no-ops. See "Production builds" below for the full mechanism.

### Where trace emission lives

The framework emits traces from these call sites:

- `events.cljc` — `:event` traces wrap each handler's interceptor pipeline.
- `subs.cljc` — `:sub/create` and `:sub/run` traces wrap subscription materialisation and recompute.
- `fx.cljc` — `:event/do-fx` traces wrap effect handler iteration; `:reagent/quiescent` for the post-render moment.
- `interceptor.cljc` — `merge-trace!` adds `:interceptors` tag to the surrounding event trace.
- `router.cljc` — `:sync` for `dispatch-sync`; FSM-trigger traces for the router state machine.
- `std_interceptors.cljc` — `path`, `inject-cofx`, `unwrap` interceptors emit their own traces. (`debug`, `trim-v`, `on-changes`, `enrich`, `after` removed in v2 per [MIGRATION §M-21](MIGRATION.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors).)

re-frame2 adds emit sites for new concerns:

- Frame lifecycle (`reg-frame`, `make-frame`, `reset-frame`, `destroy-frame`).
- Machine handlers (the fn returned by `create-machine-handler` emits transition / lifecycle / snapshot-update traces).
- Run-to-completion drain (drain-start / drain-end / drain-depth-exceeded).
- Per-frame override application (which fx was overridden, by what).

User code can also emit traces — `with-trace` and `merge-trace!` are public.

## Production builds: zero overhead, zero code

**All dev-side instrumentation is a development-only concern.** In production builds, the trace surface, the schema validation surface (Spec 010), and the registrar's hot-reload trace emit (`:rf.registry/handler-{registered,replaced,cleared}`) are *all* compile-time eliminated through a single shared gate. The closure compiler's dead-code elimination removes the gated branches; production binaries contain no instrumentation machinery at all.

### The mechanism: `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`)

The CLJS implementation uses one shared flag — an alias of the standard `goog.DEBUG` closure-define — for every dev-only branch:

```clojure
;; src/re_frame/interop.cljs
(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)
```

Every framework-internal dev branch — `trace/emit!`, `trace/emit-error!`, `schemas/validate-app-db!`, `schemas/validate-event!`, `schemas/validate-cofx!`, `schemas/validate-sub-return!`, and the `registrar/{register!,unregister!,clear-kind!}` trace emits — wraps its body in `(when interop/debug-enabled? ...)`. With `:advanced` compilation and `:closure-defines {goog.DEBUG false}`, Closure constant-folds the gate and DCEs every dependent allocation: trace maps, listener iteration, malli calls, error reason strings, the Performance API bridge.

```edn
;; user's shadow-cljs.edn — production build
{:builds {:app {:target           :browser
                :output-dir       "..."
                :compiler-options {:closure-defines {goog.DEBUG false}}}}}
```

(Most production CLJS builds already set `goog.DEBUG=false`; re-frame2 piggybacks on the canonical CLJS production flag rather than introducing its own.)

The gate must be the **outermost** form of the body. `(when interop/debug-enabled? ...)` and `(if interop/debug-enabled? <body> <else>)` constant-fold reliably; `(when (and X interop/debug-enabled?) ...)` does NOT — Closure can't statically rule out `X`, and the dead branch survives into the bundle. The verifier (see [§Production-elision verification](#production-elision-verification)) catches that mistake.

A reachable but dead branch in a production bundle:

- Allocates no trace event maps.
- Holds no listener registry beyond the (small) `defonce` cells (which carry `{}` and `0`).
- Never invokes listener predicates.
- Excludes the trace buffer payload.
- Excludes the Performance API bridge.
- Excludes the schema validation entry points and their malli/explanation calls.

### How users opt in (dev builds)

CLJS dev builds default to `goog.DEBUG=true` — every gate stays live with no extra configuration. A user who wants trace machinery in a `:advanced` artefact (rare) can flip the flag explicitly:

```edn
;; shadow-cljs.edn — :advanced build with trace kept in
{:closure-defines {goog.DEBUG true}}
```

### User-side listener registration

User-side `(rf/register-trace-cb! ...)` calls should also elide in production. Wrap them with the same predicate the framework uses:

```clojure
(when ^boolean re-frame.interop/debug-enabled?
  (rf/register-trace-cb! :my/listener callback-fn))
```

In production (`goog.DEBUG=false`), `re-frame.interop/debug-enabled?` is the constant `false`, the `when` is dead, and the entire registration is elided.

### JVM builds

JVM has no `:advanced` and no compile-time DCE. The JVM half of the interop layer:

```clojure
;; src/re_frame/interop.clj
(def debug-enabled? true)
```

…hardcodes `debug-enabled?` to `true`. JVM artefacts always run the dev-side branches. Two reasons:

1. The JVM is used in re-frame2 only for headless tests, SSR, and tooling-attached REPLs (per Spec 011 §JVM-runnable view rendering and Spec 008 §JVM-runnable test suites). None of those is a production-latency hot path.
2. Trace events are how SSR errors, schema-validation failures, and hot-reload notifications surface — turning them off on the JVM would silently drop the only data channel those flows carry.

Apps that ship a production JVM artefact (a Pedestal/ring service that uses re-frame2's runtime for state) and want to disable instrumentation should rebuild the framework with `interop.clj`'s `debug-enabled?` switched to `false` (or an `alter-var-root!` at boot) — but the canonical re-frame2 dev surface is CLJS. The compile-time-elision guarantee is **CLJS-only**; on JVM the contract is "code is present, runs cheaply when its inner predicate is also off."

### Production-elision verification

The contract above is enforced by an automated test in CI:

1. `implementation/test/re_frame/elision_probe.cljs` is a probe namespace that exercises every gated surface — `register-trace-cb!`, `emit-trace!`, `validate-{app-db,event,sub-return,cofx}!`, `register!` / `unregister!` / `clear-kind!`, plus a representative `dispatch-sync` flow. The probe roots the dead-code-elimination graph at every surface so a leak surfaces in the bundle.
2. `implementation/shadow-cljs.edn` declares two `:advanced` builds with `re-frame.elision-probe/run` as the entry point:
   - `:elision-probe` — `:closure-defines {goog.DEBUG false}` (production)
   - `:elision-probe-control` — `:closure-defines {goog.DEBUG true}` (control)
3. `implementation/scripts/check-elision.cjs` greps both bundles for sentinel strings drawn from the gated branches (schema reason fragments and `:rf.registry/*` trace operation keywords). The contract:
   - Production bundle: every sentinel MUST be ABSENT.
   - Control bundle: every sentinel MUST be PRESENT.
4. The CI workflow runs `npm run test:elision` (`shadow-cljs release elision-probe elision-probe-control && node scripts/check-elision.cjs`) on every push/PR.

The control build is what gives the test teeth: without it, a refactor that *moved* a sentinel string out of a gated branch would silently turn the negative assertion into a vacuous pass. With both bundles checked, any change that either breaks elision *or* loses methodology signal fails CI loudly.

When a future surface is added (e.g. epoch history per [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach)), it follows the same pattern:

- Wrap its dev-only body in `(when interop/debug-enabled? ...)`, outermost.
- Touch the surface from `re-frame.elision-probe` so the DCE graph reaches it.
- Add a sentinel to `DEV_ONLY_SENTINELS` in `check-elision.cjs` (a string literal or keyword name that only the gated branch contains).

## Hot path in dev builds

Dev iteration matters; you don't want trace machinery to slow ordinary feedback loops. Two hot-path costs are present in dev:

1. **Trace-event allocation** — building the trace map per emit. Always paid in dev (the Performance API bridge consumes every trace), so the framework should keep this cheap.
2. **User-listener invocation** — invoking `register-trace-cb` callbacks per batch. Zero when no user listeners are registered; non-zero when 10x or other tools attach.

### Cheap-path discipline (dev builds only)

- **Listener registry is a single atom.** Reading it is one deref.
- **No string formatting or other expensive work** happens in framework emit code; tools format if they want to.
- **Debounce avoids per-event listener invocation** — accumulating into a single collection per batch amortises the cost across many events.
- **User-listener path is empty when no user listeners are registered.** The Performance API bridge does *not* count as a user listener (see below); it's hardcoded into the emit path with negligible browser-optimised overhead, so user-listener invocation stays at zero until a tool attaches.

## Chrome Performance API integration

The Chrome Performance API ([User Timing](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing)) lets dev tools see custom timing alongside React renders, network, paint, etc. re-frame2 ships a built-in bridge between trace emission and `performance.mark` / `performance.measure`. The bridge is **separately enable-able from core tracing**: trace emission is the core contract; the browser-timeline bridge is an explicit convenience layer that can be turned off without disabling the rest of trace.

### Configuration

```clojure
(rf/configure :performance-api {:enabled? true})    ;; force-on
(rf/configure :performance-api {:enabled? false})   ;; force-off
```

Default: enabled when `re-frame.interop/debug-enabled?` is true; disabled otherwise. So in a dev build with tracing on, the bridge is on out of the box; turning it off with `:enabled? false` keeps trace events flowing to listeners (10x, re-frame-pair, custom recorders) but stops the `performance.mark` / `performance.measure` calls. Useful when:

- The Performance panel marks are noisy and the user wants the trace stream without the timeline annotations.
- A custom profiling integration owns the Performance API and re-frame2's marks would conflict.
- The host environment exposes a partial Performance API and the bridge's negligible-by-default cost would not be negligible.

Production builds elide the bridge code along with the rest of tracing (per "Production builds" above) regardless of the configuration value.

### Implementation: hardcoded into emit, gated separately

The bridge sits inside the trace-emit path itself, **inside the `(when interop/debug-enabled? ...)` compile-time gate**, with an additional `(when (performance-api-enabled?) ...)` runtime gate that reads the `:performance-api` config. So in production builds, the outer compile-time gate is dead, the entire emit path is elided, and the bridge calls disappear with everything else. In dev builds, the outer gate is live; the inner config gate decides whether the bridge fires.

Two reasons for hardcoding the bridge into emit rather than registering it as a `register-trace-cb` listener:

- **Performance.** `performance.mark` / `performance.measure` calls are heavily optimised in browsers and add negligible overhead per emit; routing them through the user-listener pipeline would require running them on every batch even when no user listeners exist.
- **The "no user listeners" cheap path stays meaningful.** With the bridge hardcoded, a dev build with no `register-trace-cb` callbacks attached still pays only the bridge's negligible per-emit cost (or zero, if the user has set `:performance-api {:enabled? false}`); the user-listener invocation path remains empty until a tool attaches.

### How it maps

For each trace event:
- `performance.mark("re-frame:<op-type>:<id>:start")` is emitted at the trace's `:start` time.
- `performance.mark("re-frame:<op-type>:<id>:end")` is emitted at the trace's `:end` time.
- `performance.measure("re-frame: <op-type> <operation>", start-mark, end-mark)` ties them together with the trace's `:duration`.

The `:id` and `:child-of` fields let the Performance panel render the cascade hierarchy: child measures nest visually under their parent.

The naming convention: `re-frame:<op-type>:<id>:<phase>` for marks; `re-frame: <op-type> <operation>` for measures (human-readable in the DevTools UI).

Result in Chrome DevTools:

- Performance panel shows re-frame events as bars in the timeline, alongside React renders.
- Hover shows the human-readable measure name.
- Custom events can be cross-referenced with paint, layout, network.

### Activation

By default, the bridge activates whenever tracing is active (dev builds). Users who want the trace stream without timeline marks turn the bridge off via `(rf/configure :performance-api {:enabled? false})`; the rest of trace continues to flow. Production builds elide tracing entirely (per "Production builds" above), and with it the bridge — the configuration value has no effect on production code paths.

### CLJS-only

The Chrome Performance API is browser-specific. JVM-side profiling uses the host's own tools (clj-async-profiler, JFR). The CLJS-only nature is documented; users running tests on JVM see all the trace events but not Performance marks.

### Forward compat

If Chrome's Performance API changes (e.g., adds new measure types), the bridge implementation updates internally; user code is unaffected because the bridge has no user-facing API — it's a hardcoded part of the trace-emit path. Browsers that don't expose `performance.mark`/`measure` (or expose only a subset) see the bridge no-op gracefully.

## Forward compatibility for tools

External tools consume re-frame2 through stable surfaces. Production builds elide the entire trace surface; everything in this section is dev-only.

### Stable surfaces consumed by every tool

| Surface | Stability |
|---|---|
| `register-trace-cb` / `remove-trace-cb` | Preserved |
| Batched delivery with debounce (50ms default) | Preserved |
| Trace event shape (`:id`, `:operation`, `:op-type`, `:start`, `:end`, `:duration`, `:child-of`, `:tags`) | Preserved exactly |
| `:op-type` discriminator vocabulary (`:event`, `:sub/run`, `:sub/create`, `:render`, `:raf`, `:event/do-fx`, ...) | Preserved; new values additive |
| `:tags` for op-type-specific data (`:app-db-before`, `:app-db-after`, `:input-signals`, `:cached?`, `:value`, `:error`) | Preserved |
| `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`) | Preserved |
| Compile-time elision via `goog.DEBUG=false` + `:advanced` | Preserved |
| `(trace-api-version)` integer (bumps on contract revisions) | Provided |
| Public registrar query API (`handlers`/`handler-meta`/`frame-ids`/`frame-meta`/`get-frame-db`/`snapshot-of`/`sub-topology`/`sub-cache`) | See [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Hot-reload notifications (`:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced`, `:frame/created`, `:frame/destroyed`) | Trace events |

`(trace-api-version)` is the version gate:

```clojure
(when (and (rf/loaded?) (>= (rf/trace-api-version) 1))
  ;; safe to use re-frame2's trace surface
  ...)
```

### Capabilities tools depend on

- **Multi-frame UI** — frame selector; per-frame trace slicing via `:frame`; per-frame app-db via `(get-frame-db id)`.
- **Drain semantics in epochs** — multiple events drain into a single epoch (run-to-completion); `:child-of` chain expresses the cascade.
- **Machine trace types** — `:op-type` values (`:rf.machine/transition`, etc.) for state-machine activity.
- **Per-frame override visibility** — `:fx-overrides`/`:interceptor-overrides` are inspectable via `(frame-meta id)`.

### Programmatic interaction surfaces

- Generate test cases from trace history.
- Suggest refactors based on registry inspection.
- Drive interactions via `dispatch-sync`.
- Snapshot state, modify, restore.
- Read state in any frame; `frame-ids` enumerates them.

## JVM vs. CLJS scope

All trace functionality is **dev-build only** — production builds elide the entire trace surface on both platforms.

| Capability (dev builds) | JVM | CLJS |
|---|---|---|
| Trace event emission | ✓ | ✓ |
| `register-trace-cb` / `remove-trace-cb` | ✓ | ✓ |
| Hot-reload trace events | ✓ | ✓ |
| Chrome Performance API integration | ✗ | ✓ |
| 10x panel itself | ✗ | ✓ |
| re-frame-pair attachment | ✓ | ✓ |

Trace data is just data; both platforms emit it during dev. The Performance API bridge is browser-specific; everything else works headless.

## Error contract

Errors that occur during runtime execution are emitted as **structured trace events**, with a defined `:op-type` and a Malli-schemed `:tags` payload. This satisfies AI-first property P7 (machine-readable errors) and gives every consumer of the trace stream a consistent error surface.

This section is the **authoritative model** for re-frame2's error taxonomy. Per-feature specs (010, 011, 012, etc.) reference categories defined here; the `Error categories` table below is the single source of truth for category names, payload shapes, and recovery defaults. Two axes carry the structured information:

- **`:op-type`** — universal severity discriminator (`:error` or `:warning`). Consumers branch on severity without parsing the prefix.
- **`:operation`** — namespaced category keyword (`:rf.error/<category>`, `:rf.fx/<category>`, `:rf.ssr/<category>`, `:rf.warning/<category>`, `:rf.epoch/<category>`). The prefix carries domain provenance; the suffix names the specific category.

### The error event shape

All error trace events are open maps with these required keys:

```clojure
{:id        any                                  ;; unique trace id
 :operation :rf.error/<category>                 ;; specific category, see below
 :op-type   :error                               ;; the universal discriminator for errors
 :frame     keyword                              ;; the frame the failure happened in
 :source    keyword?                             ;; the trigger source (:ui, :timer, :http, ...)
 :start     timestamp
 :end       timestamp
 :duration  number-ms
 :tags      {:category    :rf.error/<category>   ;; same as :operation, for consumer convenience
             :failing-id  any                    ;; the registered id that failed (event id, fx id, sub id, view id, etc.)
             :reason      string                 ;; one-sentence human description
             ...}                                ;; category-specific keys
 :child-of  any?                                 ;; parent trace id if nested
 :recovery  keyword?}                            ;; :no-recovery, :replaced-with-default, :retried, :skipped, ...
```

`:frame` and `:source` are the same top-level fields documented in [§Re-frame2 additions](#re-frame2-additions-additive-optional) — they live at the top level on **every** trace event, errors included; they are not duplicated inside `:tags`. The `:tags` payload's category-specific keys are documented per category below, and each category has a registered Malli schema so consumers can validate / branch on the payload safely.

### Error namespace convention — five prefix shapes

Error categories use **five distinct namespace prefixes**:

| Prefix | Meaning | Example |
|---|---|---|
| `:rf.error/<category>` | A genuine runtime error: a contract was violated. | `:rf.error/handler-exception`, `:rf.error/no-such-sub` |
| `:rf.fx/<category>` | An fx-substrate event that rides the error envelope but is not necessarily a failure. | `:rf.fx/skipped-on-platform` |
| `:rf.ssr/<category>` | An SSR-substrate event with its own diagnostic shape (server-vs-client divergence, hash mismatches). | `:rf.ssr/hydration-mismatch` |
| `:rf.warning/<category>` | A misuse the runtime can recover from but wants surfaced. | `:rf.warning/plain-fn-under-non-default-frame` |
| `:rf.epoch/<category>` | Time-axis tooling (epoch buffer, time-travel) diagnostics. | `:rf.epoch/replay-conflict` |

The prefix carries domain provenance that consumers branch on. `:rf.fx/` marks "fx substrate emitted this"; `:rf.ssr/` marks "SSR substrate emitted this"; `:rf.warning/` marks "this is recoverable." Routing on the prefix is cheap (string-prefix dispatch) and matches how tools consume the stream.

The `:op-type` field carries the universal severity discriminator (`:error` / `:warning`) so consumers that want severity branching get it without parsing the prefix. `:op-type` answers *how serious is this?*; the prefix answers *which subsystem owns it?*.

This convention is **stable**: new error categories adopt one of the five existing prefixes. New ad-hoc prefixes are not part of the contract.

### Error categories (initial set)

| `:operation` / category | Meaning | Category-specific `:tags` |
|---|---|---|
| `:rf.error/handler-exception` | An event handler threw | `:event` (vector), `:handler-id`, `:exception-message`, `:exception-data?` |
| `:rf.error/fx-handler-exception` | A registered fx threw during effect resolution | `:fx-id`, `:fx-args`, `:exception-message` |
| `:rf.error/sub-exception` | A subscription's computation threw | `:sub-query`, `:sub-id`, `:exception-message` |
| `:rf.error/no-such-sub` | A subscription's `:<-` input refers to an unregistered sub | `:sub-id`, `:unresolved-input`, `:resolved-inputs` |
| `:rf.error/schema-validation-failure` | A `:spec`-validated value failed validation | `:where` (`:event`/`:sub-return`/`:app-db`/`:fx-args`/...), `:path`, `:value`, `:explanation` (Malli explanation map) |
| `:rf.error/drain-depth-exceeded` | The run-to-completion drain hit its depth limit | `:depth`, `:queue-size`, `:last-event` |
| `:rf.error/no-such-handler` | A dispatch arrived with no registered handler | `:event`, `:kind` |
| `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside an event handler's interceptor pipeline (use `:fx [[:dispatch event]]` instead — see [002 §dispatch-sync](002-Frames.md#dispatch-sync)) | `:event`, `:enclosing-event`, `:enclosing-frame` |
| `:rf.error/effect-map-shape` | A `reg-event-fx` handler returned a top-level effect-map key other than `:db` / `:fx` (per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level)). The runtime drops the offending key and emits one trace per offending key; legal `:db` / `:fx` keys still apply | `:failing-id` (event-id), `:event-id`, `:event` (vector), `:offending-key`, `:value`, `:reason` |
| `:rf.error/override-fallthrough` | An override was specified but no matching id existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/skipped-on-platform` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:fx-id`, `:platform`, `:registered-platforms` |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree (per [011](011-SSR.md)) | `:server-hash`, `:client-hash`, `:first-diff-path?` |
| `:rf.warning/plain-fn-under-non-default-frame-once` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:rf/default`. Emitted at most once per `(component-id, non-default-frame-id)` pair — see [004 §Plain Reagent fns](004-Views.md) | `:fn-name`, `:rendered-under`, `:routed-to` |
| `:rf.warning/no-clock-configured` | A timing-sensitive substrate feature (e.g. state-machine `:after` per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) was exercised on a host whose `re-frame.interop` clock primitives (`now-ms` / `schedule-after!` / `cancel-scheduled!`) weren't wired up. The runtime falls back to the host-native clock if available; this advisory surfaces so tests / agents can spot the missing wiring | `:feature` (e.g. `:rf.machine/after`), `:fallback` (the host-native clock used) |
| `:rf.error/duplicate-url-binding` | A second frame attempted `:url-bound? true` while another already owns the URL. Per [012 §Multi-frame routing](012-Routing.md#multi-frame-routing) | `:existing-frame`, `:offending-frame` |
| `:rf.warning/multiple-status-set` | Two or more `:rf.server/set-status` calls in the same request drain. Last-write-wins; advisory for finding the conflicting handlers. Per [011 §Multiple-status policy](011-SSR.md#multiple-status-policy) | `:writes` (vector of `{:status :handler-id :event}` per write), `:final-status` |
| `:rf.warning/multiple-redirects` | Two or more `:rf.server/redirect` calls in the same request drain. Last-write-wins. Per [011 §Redirect precedence](011-SSR.md#redirect-precedence) | `:writes` (vector), `:final-redirect` |
| `:rf.warning/head-mismatch` | Client-computed head model differs from server-supplied head; client re-renders and replaces. Per [011 §Mismatch detection — head](011-SSR.md#mismatch-detectionhead) | `:server-hash`, `:client-hash`, `:head-id` |
| `:rf.error/sanitised-on-projection` | The active error projector threw or returned a non-`:rf/public-error` shape; the runtime fell back to the locked generic-500 public shape. Per [011 §Where sanitisation happens — before render](011-SSR.md#where-sanitisation-happensbefore-render) | `:projector-id`, `:original-operation`, `:projection-failure-reason` |

`:rf.fx/skipped-on-platform` is technically a *warning* not an error, but it rides the same envelope and routes through the same listener path; consumers can branch on `:op-type` (`:warning` vs `:error`) if they want to distinguish.

### Schemas

Each category's `:tags` shape is registered as a Malli schema so consumers can validate without ad-hoc parsing:

```clojure
;; Conceptual; the actual registration mechanism is implementation-specific.

;; :frame is the top-level field on the trace event itself (per the error event
;; shape above), not a :tags key — it appears on every trace event, error or
;; otherwise. The schemas below describe the :tags payload only.

(def HandlerExceptionTags
  [:map
   [:category          [:= :rf.error/handler-exception]]
   [:failing-id        :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:handler-id        :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def SchemaValidationTags
  [:map
   [:category    [:= :rf.error/schema-validation-failure]]
   [:failing-id  :keyword]
   [:reason      :string]
   [:where       [:enum :event :sub-return :app-db :fx-args :cofx-args :on-create]]
   [:path        [:vector :any]]
   [:value       :any]
   [:explanation :any]])                         ;; Malli explanation shape

;; ... and so on for each category.
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is stable and additive — new categories can be added but existing ones cannot be renamed or removed.

### Server error projection — public boundary

For SSR specifically, the structured trace event is the **internal** record (rich, full detail, monitor-bound) and a separate **public projection** is written to the HTTP response (sanitised, client-safe). The internal trace event is **never** serialised to the client. The projection mechanism is owned by [011 §Server error projection](011-SSR.md#server-error-projection); the trace stream is unchanged by it. Tools that want full error detail subscribe via `register-trace-cb` as usual; the response carries only the locked `:rf/public-error` shape.

The runtime emits `:rf.error/sanitised-on-projection` (above) when the projector itself fails, so monitor dashboards see when the public boundary fell back to the generic-500 shape.

### Recovery contract

The `:recovery` field on the trace event tells consumers (dev panels, error-monitor integrations, tooling) what the runtime did:

- `:no-recovery` — the error propagated; the event was not handled.
- `:replaced-with-default` — the runtime used a default value (e.g., `:no-such-handler` falling through to a no-op).
- `:retried` — the runtime retried (with an upper bound) and surfaces the result.
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`).
- `:warned-and-replaced` — the runtime emitted the warning and did its default action anyway (e.g., `:rf.ssr/hydration-mismatch` warn-and-replace mode).
- `:logged-and-skipped` — the runtime emitted the trace and dropped the offending input; sibling inputs still apply (e.g., `:rf.error/effect-map-shape` drops the offending top-level effect-map key while `:db` / `:fx` still apply).

A registered error-handler (per `reg-event-error-handler`) can intercept any error category and decide policy. The default error-handler routes everything to the trace stream and proceeds with the documented per-category recovery.

### `reg-event-error-handler`

```clojure
(rf/reg-event-error-handler
  (fn handle-error [error-event]
    ;; error-event conforms to :rf/trace-event with :op-type :error.
    ;; Return a map with :recovery telling the runtime how to proceed.
    ;; Returning nil = no policy override; the runtime falls back to its default.
    (case (:operation error-event)
      :rf.error/handler-exception
      (do (log-to-monitoring error-event)
          {:recovery :no-recovery})

      :rf.error/schema-validation-failure
      (do (log-to-monitoring error-event)
          {:recovery :replaced-with-default
           :replacement (:default-value (:tags error-event))})

      :rf.error/no-such-handler
      ;; ignore — the default :replaced-with-default behaviour is fine
      nil

      ;; default: trust the runtime's per-category recovery
      nil)))
```

The error-handler:

- Receives the structured error event (an `:rf/trace-event` with `:op-type :error`).
- Returns either `nil` (no policy override; runtime applies its default per-category recovery) or a map with at least `:recovery` set to one of the documented recovery keywords.
- Optional keys in the return map: `:replacement` (a value to use when `:recovery` is `:replaced-with-default`) and `:notes` (a string surfaced in the resulting trace). The framework does not ship a `:retried` recovery — handlers cannot ask the runtime to re-execute the failed operation. If retry semantics are wanted, the user dispatches a fresh event from inside the error-handler (see "Composition with libraries" below).

Only **one** error-handler is registered at a time per process. Replacing it replaces the policy. The default error-handler is registered automatically; user calls to `reg-event-error-handler` override it.

#### Default behaviour by category

| Category | Default `:recovery` | Notes |
|---|---|---|
| `:rf.error/handler-exception` | `:no-recovery` | The exception propagates; the cascade halts. |
| `:rf.error/fx-handler-exception` | `:no-recovery` | The fx is skipped; cascade continues if other fx independent. |
| `:rf.error/sub-exception` | `:replaced-with-default` | The sub returns `nil`; views see no value. |
| `:rf.error/no-such-sub` | `:replaced-with-default` | The unresolved input is substituted with `nil`; the sub's body still runs. Strict mode (`:strict-subs` config) escalates to a thrown exception. |
| `:rf.error/schema-validation-failure` | `:no-recovery` (dev) / `:replaced-with-default` (prod) | Dev: hard-fail to surface bugs early. Prod: log and proceed with the offending value (validation at boundaries should already have rejected it). |
| `:rf.error/drain-depth-exceeded` | `:no-recovery` | Always indicates a bug; halt the cascade. |
| `:rf.error/no-such-handler` | `:replaced-with-default` | No-op; emit the trace. |
| `:rf.error/dispatch-sync-in-handler` | `:no-recovery` | The call is rejected. Use `:fx [[:dispatch event]]` in the effect map. |
| `:rf.error/effect-map-shape` | `:logged-and-skipped` | The offending top-level key is dropped; `:db` and `:fx` still apply. One trace per offending key. Per [MIGRATION §M-8](MIGRATION.md#m-8-effect-map-keys-consolidated--only-db-and-fx-at-the-top-level). |
| `:rf.error/override-fallthrough` | `:replaced-with-default` | Use the registered fx as if no override existed. |
| `:rf.fx/skipped-on-platform` | `:skipped` | Documented; not really an error. |
| `:rf.ssr/hydration-mismatch` | `:warned-and-replaced` | Re-render client-side; the server's HTML is replaced. |
| `:rf.warning/multiple-status-set` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/multiple-redirects` | `:warned-and-replaced` | Last-write wins; advisory only. |
| `:rf.warning/head-mismatch` | `:warned-and-replaced` | Client renders its head; server's is replaced. |
| `:rf.error/sanitised-on-projection` | `:replaced-with-default` | Runtime falls back to the locked generic-500 public-error shape. |

#### Style rubric for `:reason` strings (non-normative)

The structured fields of an error trace event (`:operation`, `:failing-id`, `:frame`, category-specific `:tags`) are the *contract* — tools branch on those. The `:reason` string is the **one-sentence human-facing accompaniment** that error-monitor dashboards, dev panels, and tooling surface to a reader. The voice matters. The goal is wording that helps the reader fix the problem in one read.

A good `:reason` string:

1. **Names the failing thing** — the registered id, in backticks. `'Event handler `:cart/add-item` threw an exception.'` not `'A handler threw.'`
2. **Names the broken contract** — what was expected. `'... expected to return an effects-map; got a vector.'` not `'... bad return value.'`
3. **Suggests the fix in one clause** when the fix is unambiguous from the structured payload. `'... did you mean to wrap it in `{:fx [...]}` ?'` not `'See docs.'`
4. **Stays under ~20 words.** The structured `:tags` payload carries the detail; `:reason` is the headline.
5. **Is mechanically composable from the `:tags` payload.** Implementations build `:reason` from a category-specific template plus `:tags` substitutions; nothing in `:reason` is information not also present in structured form.

Example pairs (acceptable → preferred):

```
:rf.error/handler-exception
  acceptable: "Handler threw."
  preferred:  "Event handler `:cart/add-item` threw: TypeError: Cannot read property 'price' of undefined."

:rf.error/no-such-sub
  acceptable: "Subscription input not found."
  preferred:  "Subscription `:cart/total` depends on `:cart/items` which is not registered. Did you forget to require the cart namespace?"

:rf.error/schema-validation-failure
  acceptable: "Schema validation failed."
  preferred:  "Event vector for `:cart/add-item` failed schema at path [1 :id]: expected :uuid, got \"abc\"."

:rf.error/drain-depth-exceeded
  acceptable: "Drain depth exceeded."
  preferred:  "Drain depth limit (100) exceeded — likely a dispatch loop. Last event in queue: `[:cart/recompute]`."

:rf.error/effect-map-shape
  acceptable: "Effect-map returned a disallowed top-level key."
  preferred:  "Effect-map for `:cart/save` returned top-level key `:dispatch`; only `:db` and `:fx` are allowed at the top level — wrap as `:fx [[:dispatch event]]`."
```

Implementations that omit a `:reason` (returning the empty string) are conformant — the structured payload is the contract — but the rubric is the recommended voice for the reference implementation and for ports.

#### Composition with libraries (Sentry, Honeybadger, etc.)

Error-monitoring libraries integrate by registering an error-handler that forwards the structured error event to the monitoring service AND returns `nil` to delegate recovery:

```clojure
(rf/reg-event-error-handler
  (fn forward-and-defer [error-event]
    (sentry/capture-event (sentry-shape error-event))
    nil))                                          ;; runtime applies default recovery
```

Multiple monitoring concerns compose in user code (one error-handler that fans out to several services). The runtime exposes one slot.

## Notes

### Why this is its own Spec

Tracing is the connective tissue between the runtime and every tool that observes it. Splitting it into its own Spec:

- Locks the data shape independently of any specific tool.
- Documents the forward-compat commitments tools depend on.
- Separates "framework emits events" (002 territory) from "framework provides a tap surface" (this Spec).
- Makes the Chrome Performance API integration explicit rather than implicit.

## Open questions

### Trace allocation cost in dev when no listeners

When a listener is *never* registered, the macros' compile-time gate doesn't help — `interop/debug-enabled?` is true (dev), so the body runs. The body should fast-fail when `(empty? @trace-cbs)`. The emit macros perform this check before allocating the trace map.

### Privacy / sensitive data in traces

Trace events contain dispatched event vectors, which may include user input (passwords, PII). Tools that ship traces (e.g., to monitoring services) must redact. Recommendation: provide a `:sensitive?` tag that handlers can set; a `with-redacted` interceptor pattern that strips sensitive args before emit.

## Resolved decisions

### Listener ordering

Multiple listeners may register concurrently. Order is by registration time (FIFO map iteration). Tools must not depend on order, since batched delivery gives each listener the same collection independently.

### Trace correlation across the cascade

Use the `:child-of` field (parent trace id). Epoch grouping relies on this; the framework preserves it.

### Trace event for app-db changes

`:db` mutations happen inside `do-fx`. The runtime captures `:app-db-before` and `:app-db-after` in the `:event` trace's `:tags`. No separate `:app-db/changed` event is emitted.
