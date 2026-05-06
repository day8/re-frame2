# EP 009 — Instrumentation, Tracing, and Performance Integration

> Status: Drafting. **v1-required.** Tracing is the substrate that re-frame-10x consumes; without it, 10x is broken in re-frame2. This EP locks the trace event model, subscription mechanism, hot-path discipline, Chrome Performance API integration, and the forward-compatibility commitments that let 10x and re-frame-pair evolve without re-litigating the framework.
>
> **Per [reorient.md](reorient.md):** the trace event stream is a **pattern-level** primitive — every implementation supplies a structured trace stream from well-defined points in the runtime. Trace events are open maps with stable required keys, consistent with the open-maps-with-schemas principle. The CLJS-specific bits — `goog-define` for production elision, the Chrome Performance API bridge, `register-trace-cb` as the listener API — are reference-implementation details. Other-language implementations would resolve elision and listener delivery differently.

## Abstract

re-frame2 emits a stream of **trace events** describing what's happening at runtime — dispatches, interceptor steps, effect handler calls, subscription updates, frame lifecycle, machine transitions. Tools subscribe to this stream; current tools include re-frame-10x (the visual dev panel) and re-frame-pair (the nREPL-attached AI/REPL agent). Future tools will be different.

The tracing surface is designed to be **stable** (required fields don't change), **extensible** (open maps; new fields are additive), **cheap on the hot path** (near-zero overhead with no listeners), and **cross-platform** (JVM-runnable for the data; Chrome Performance integration is CLJS-side).

**All tracing — including the Performance API bridge — is compile-time eliminated in production builds.** No exceptions. Production binaries contain zero trace code; there is no "leave a small thing on" mode. Tracing is a dev-time concern only.

## Why this is its own EP

Tracing is the connective tissue between the runtime and every tool that observes it. Splitting it into its own EP:

- Locks the data shape independently of any specific tool.
- Documents the forward-compat commitments tools depend on.
- Separates "framework emits events" (002 territory) from "framework provides a tap surface" (this EP).
- Makes the Chrome Performance API integration explicit rather than implicit.

It's v1-required because:

- 10x has to work in re-frame2 from day one; 10x consumes traces.
- re-frame-pair already attaches to running re-frame apps via nREPL; the trace surface is part of what it reads.
- Without explicit forward-compat commitments, future 10x/re-frame-pair work is risky.

## The trace event model

A trace event is an immutable map describing one *span* of work in the runtime — an event's processing, a sub computation, a render, an effect. Events flow into a single per-application trace stream; subscribers listen.

The shape inherits current re-frame's trace convention (preserved for tool compatibility) and adds re-frame2 fields additively.

### Core fields (required on every event)

```clojure
{:id        <int>            ;; auto-incrementing trace id; unique per process
 :operation <kw-or-vec>      ;; what's being traced — typically the event-id, sub-id, etc.
 :op-type   <kw>             ;; discriminator: :event, :sub/run, :sub/create, :render, :raf,
                              ;;   :event/do-fx, :reagent/quiescent, :machine/transition, etc.
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

### `:op-type` vocabulary

Existing values consumed by re-frame-10x today: `:event`, `:sub/run`, `:sub/create`, `:render`, `:raf`, `:event/do-fx`, `:reagent/quiescent`, `:sync`, `::fsm-trigger`.

re-frame2 adds new `:op-type` values for new concerns:

- `:frame/created` / `:frame/reset` / `:frame/destroyed` — frame lifecycle.
- `:machine.lifecycle/created` / `:machine.lifecycle/destroyed` — machine instance lifecycle.
- `:machine/event-received` / `:machine/transition` / `:machine/snapshot-updated` — machine activity.
- `:registry/handler-registered` / `:registry/handler-cleared` — registration changes (hot reload).
- `:error/handler` / `:error/interceptor` / `:error/fx` / `:error/drain-depth-exceeded` — errors.

Consumers filter by `:op-type` (or `:frame`, or `:source`) to get the slice they care about. Adding new `:op-type` values is non-breaking — tools ignore what they don't understand.

### `:tags` is the open-ended bag

Variable per-event data goes in `:tags`. Existing examples: `:app-db-before`, `:app-db-after`, `:input-signals`, `:cached?`, `:value`, `:error`, `:reaction`. New tags can be added without breaking consumers. Use `:tags` for op-type-specific data; reserve top-level keys for fields universal across all events.

### Open shape; new fields are additive

The map is open. New fields can be added by future versions without breaking consumers — listeners read what they understand and ignore the rest. The forward-compat commitments:

- **Required top-level fields** (`:id`, `:operation`, `:op-type`, `:start`, `:end`, `:duration`, `:child-of`, `:tags`) are stable across all re-frame2 versions. Removing or renaming any is a breaking change.
- **Re-frame2 additions** (`:frame`, `:source`) are stable once shipped; they are present on every re-frame2 trace event.
- **Op-type-specific fields inside `:tags`** are stable within their op-type. New optional tag keys are additive; existing keys don't change shape.
- **New `:op-type` values** can be added without breaking existing tools — tools filter the values they recognise.

Tools written against re-frame2 v1 continue to work against v1.x and v2 unless they read fields that haven't been promised stable.

## Subscription / consumption

The current re-frame trace API uses **batched, debounced delivery** — listeners receive collections of trace events at a regular cadence (default 50ms) rather than per-event. This is essential for performance: per-event delivery on a hot dispatch loop would slow the host application; batching amortises cost and gives consumers a chance to coalesce updates.

re-frame2 keeps this model.

### The listener API

```clojure
(rf/register-trace-cb key callback-fn)
;; Subscribes callback-fn to receive batched trace events.
;; callback-fn signature: (fn [traces] ...) — traces is a collection.
;; Same key replaces any previously-registered listener under that key.

(rf/remove-trace-cb key)
;; Unsubscribes.
```

Conventional keys: `:re-frame.10x/main`, `:re-frame.pair/main`, `:my-app/recorder`.

### Listener invocation rules

- **Batched delivery.** Listeners receive a collection of trace events accumulated over the debounce window (default 50ms). Tools that want to react to a specific event can scan the batch.
- **Debounced.** A 25ms grace window after the most recent emit before delivery, capped at 50ms total. Avoids constant timeout setting/cancelling on busy dispatch loops.
- **After settle.** A trace event lands in the batch only after its underlying span completes (`finish-trace` runs). Listeners see fully-formed events with `:end` and `:duration` populated.
- **Exception isolation.** An exception thrown by a listener is caught, logged via `re-frame.loggers`, and does *not* propagate to the framework or other listeners. One broken tool can't break the app or block other tools.
- **Buffer flush after delivery.** After a batch is delivered to all listeners, the framework's internal trace buffer is reset. The framework holds no long-term history; tools that want one (e.g., 10x's epoch buffer) maintain their own.

### Per-event delivery — opt out of batching

A listener that genuinely needs per-event delivery (rare) can opt out:

```clojure
(rf/register-trace-cb :my/realtime callback-fn {:batched? false})
;; callback-fn receives one trace event per call instead of a batch.
```

Use sparingly — most tools should batch.

## Emitting trace events — the macro suite

re-frame2 emits traces using a small macro suite (inherited from current re-frame):

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

All three macros expand to `(when (is-trace-enabled?) ...emit...)` gates. `is-trace-enabled?` reads the `re-frame.trace/trace-enabled?` closure-define (default `false`); when the constant is `false` in a production build, the closure compiler eliminates the gated branch and the macros become no-ops. See "Production builds" below for the full mechanism.

### Where trace emission lives

The framework emits traces from these call sites (inherited from current re-frame, with new ones for re-frame2):

- `events.cljc` — `:event` traces wrap each handler's interceptor pipeline.
- `subs.cljc` — `:sub/create` and `:sub/run` traces wrap subscription materialisation and recompute.
- `fx.cljc` — `:event/do-fx` traces wrap effect handler iteration; `:reagent/quiescent` for the post-render moment.
- `interceptor.cljc` — `merge-trace!` adds `:interceptors` tag to the surrounding event trace.
- `router.cljc` — `:sync` for `dispatch-sync`; FSM-trigger traces for the router state machine.
- `std_interceptors.cljc` — `path`, `enrich`, `after`, `on-changes` interceptors emit their own traces.

re-frame2 adds emit sites for new concerns:

- Frame lifecycle (`reg-frame`, `make-frame`, `reset-frame`, `destroy-frame`).
- Machine handlers (`machine-handler` emits transition / lifecycle / snapshot-update traces).
- Run-to-completion drain (drain-start / drain-end / drain-depth-exceeded).
- Per-frame override application (which fx was overridden, by what).

User code can also emit traces — `with-trace` and `merge-trace!` are public.

## Production builds: zero overhead, zero code

**Trace emission is a development-only concern.** In production builds, all tracing code — every emit call site, the listener registry, the trace buffer, the Performance API bridge — is compile-time eliminated. The closure compiler's dead-code elimination removes everything; production binaries contain no trace machinery at all.

### The mechanism: `goog-define trace-enabled?` + `is-trace-enabled?`

Following the convention already in current re-frame's `re-frame.trace`:

```clojure
(ns re-frame.trace)
#?(:cljs (goog-define trace-enabled? false)
   :clj  (def ^boolean trace-enabled? false))

(defn ^boolean is-trace-enabled? [] trace-enabled?)
```

Every framework-internal trace emit is wrapped in `(when (is-trace-enabled?) ...)`. The macros (`with-trace`, `merge-trace!`, `finish-trace`) all expand to this gate.

When `trace-enabled?` is `false` (the default), the closure compiler's advanced-compilation pass treats the constant as dead and elides the gated branch from the output bundle. Production builds:

- Allocate no trace event maps.
- Hold no listener registry.
- Never invoke listener predicates.
- Don't include the trace buffer in the bundle.
- Don't include the Performance API bridge.

### How users opt in (dev builds)

Tools that consume traces (10x, re-frame-pair) instruct users to set the `trace-enabled?` closure-define to `true` in their dev build:

```edn
;; shadow-cljs.edn (dev build)
{:closure-defines {re-frame.trace/trace-enabled? true}}
```

Closure compiler with the constant set to `true` keeps the gated branches; trace emission runs and listeners receive batches.

### User-side listener registration

User-side `(rf/register-trace-cb ...)` calls should also be elided in production. Wrap them with the same predicate:

```clojure
(when (rf/is-trace-enabled?)
  (rf/register-trace-cb :my/listener callback-fn))
```

In production (closure-define `false`), `is-trace-enabled?` is a constant `false`, the `when` is dead, and the entire registration is elided.

### JVM builds

For JVM builds, `trace-enabled?` is a plain `^boolean` def; production artefacts use a build-time flag (or reader conditional) to set it. JVM doesn't have the closure compiler's elision — the dead code stays in the bytecode — but the runtime cost is one boolean check on each emit, near-zero overhead.

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

The Chrome Performance API ([User Timing](https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/User_timing)) lets dev tools see custom timing alongside React renders, network, paint, etc. re-frame2 wires a built-in bridge between trace emission and `performance.mark` / `performance.measure`. The bridge is part of the trace machinery: it activates whenever tracing is active (dev builds) and is compiled out entirely in production along with the rest of tracing.

### Implementation: hardcoded into emit, not a registered listener

The bridge is *not* a `register-trace-cb` listener — it is hardcoded into the trace-emit path itself, **inside the `(when (is-trace-enabled?) ...)` compile-time gate**. So in production builds, the gate is dead, the entire emit path is elided, and the bridge calls disappear with everything else. In dev builds, the gate is live, every trace emit invokes the bridge, and the marks land in Chrome's Performance panel.

Two reasons for hardcoding into emit rather than registering as a listener:

- **Performance.** `performance.mark` / `performance.measure` calls are heavily optimised in browsers and add negligible overhead per emit; routing them through the user-listener pipeline would require running them on every batch even when no user listeners exist.
- **The "no user listeners" cheap path stays meaningful.** With the bridge hardcoded, a dev build with no `register-trace-cb` callbacks attached still pays only the bridge's negligible per-emit cost; the user-listener invocation path remains empty until a tool attaches.

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

The bridge activates whenever tracing is active (dev builds) — there is no separate opt-in. If you can see traces, you can see them in Chrome's Performance panel. Production builds elide tracing entirely (per "Production builds" above), and with it the bridge.

### CLJS-only

The Chrome Performance API is browser-specific. JVM-side has its own profiling (clj-async-profiler, JFR); we don't bridge to those in v1. The CLJS-only nature is documented; users running tests on JVM see all the trace events but not Performance marks.

### Forward compat

If Chrome's Performance API changes (e.g., adds new measure types), the bridge implementation updates internally; user code is unaffected because the bridge has no user-facing API — it's a hardcoded part of the trace-emit path. Browsers that don't expose `performance.mark`/`measure` (or expose only a subset) see the bridge no-op gracefully.

## Forward compatibility for tools (10x, re-frame-pair)

External tools (re-frame-10x, re-frame-pair, custom dashboards) consume re-frame2 through stable surfaces. Production builds elide the entire trace surface; everything in this section is dev-only.

### Stable surfaces consumed by every tool

| Surface | Stability |
|---|---|
| `register-trace-cb` / `remove-trace-cb` | Preserved |
| Batched delivery with debounce (50ms default) | Preserved |
| Trace event shape (`:id`, `:operation`, `:op-type`, `:start`, `:end`, `:duration`, `:child-of`, `:tags`) | Preserved exactly |
| `:op-type` discriminator vocabulary (`:event`, `:sub/run`, `:sub/create`, `:render`, `:raf`, `:event/do-fx`, ...) | Preserved; new values additive |
| `:tags` for op-type-specific data (`:app-db-before`, `:app-db-after`, `:input-signals`, `:cached?`, `:value`, `:error`) | Preserved |
| `is-trace-enabled?` | Preserved |
| Compile-time elision via `goog-define trace-enabled?` | Preserved |
| `(trace-api-version)` integer (bumps on contract revisions) | New |
| Public registrar query API (`handlers`/`handler-meta`/`frame-ids`/`frame-meta`/`get-frame-db`/`snapshot-of`/`sub-topology`/`sub-cache`) | See [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api) |
| Hot-reload notifications (`:registry/handler-registered`, `:registry/handler-cleared`, `:frame/created`, `:frame/destroyed`) | New trace events |

`(trace-api-version)` is the version gate:

```clojure
(when (and (rf/loaded?) (>= (rf/trace-api-version) 1))
  ;; safe to use re-frame2's trace surface
  ...)
```

10x's existing reads against private `re-frame.db/app-db` must move to the public query API (per MIGRATION M-1).

### What 10x will need to adapt

- **Multi-frame UI** — frame selector; per-frame trace slicing via `:frame`; per-frame app-db via `(get-frame-db id)`.
- **Drain semantics in epochs** — more events drain into a single epoch (run-to-completion); `:child-of` chain works as-is; visual layout may want to mark drain boundaries.
- **Machine trace types** — new `:op-type` values (`:machine/transition`, etc.); additive, can ignore initially.
- **Per-frame override visibility** — surface `:fx-overrides`/`:interceptor-overrides` in the frame inspector via `(frame-meta id)`.

### What re-frame-pair can do without re-frame2 changes

- Generate test cases from trace history.
- Suggest refactors based on registry inspection.
- Drive interactions via `dispatch-sync`.
- Snapshot state, modify, restore.
- Read state in any frame; `frame-ids` enumerates them.

### What still needs design work for pair

- Sandboxed execution ("run this dispatch but undo any side-effects after"). Partially expressible today via per-frame `:fx-overrides` (override every fx with a no-op or recorder).
- Speculative execution (`simulate-dispatch` that doesn't commit).
- Write-then-rollback transactions.

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

### The error event shape

All error trace events are open maps with these required keys:

```clojure
{:id        any                                  ;; unique trace id
 :operation :rf.error/<category>                 ;; specific category, see below
 :op-type   :error                               ;; the universal discriminator for errors
 :start     timestamp
 :end       timestamp
 :duration  number-ms
 :tags      {:category    :rf.error/<category>   ;; same as :operation, for consumer convenience
             :failing-id  any                    ;; the registered id that failed (event id, fx id, sub id, view id, etc.)
             :frame       keyword                ;; the frame the failure happened in
             :reason      string                 ;; one-sentence human description
             ...}                                ;; category-specific keys
 :child-of  any?                                 ;; parent trace id if nested
 :recovery  keyword?}                            ;; :no-recovery, :replaced-with-default, :retried, :skipped, ...
```

The `:tags` payload's category-specific keys are documented per category below, and each category has a registered Malli schema so consumers can validate / branch on the payload safely.

### Error categories (initial set)

| `:operation` / category | Meaning | Category-specific `:tags` |
|---|---|---|
| `:rf.error/handler-exception` | An event handler threw | `:event` (vector), `:handler-id`, `:exception-message`, `:exception-data?` |
| `:rf.error/fx-handler-exception` | A registered fx threw during effect resolution | `:fx-id`, `:fx-args`, `:exception-message` |
| `:rf.error/sub-exception` | A subscription's computation threw | `:sub-query`, `:sub-id`, `:exception-message` |
| `:rf.error/schema-validation-failure` | A `:spec`-validated value failed validation | `:where` (`:event`/`:sub-return`/`:app-db`/`:fx-args`/...), `:path`, `:value`, `:explanation` (Malli explanation map) |
| `:rf.error/drain-depth-exceeded` | The run-to-completion drain hit its depth limit | `:depth`, `:queue-size`, `:last-event` |
| `:rf.error/no-such-handler` | A dispatch arrived with no registered handler | `:event`, `:kind` |
| `:rf.error/override-fallthrough` | An override was specified but no matching id existed | `:overrides-map`, `:looked-up-id` |
| `:rf.fx/skipped-on-platform` | An fx was skipped because its `:platforms` excluded the active platform (per [011](011-SSR.md)) | `:fx-id`, `:platform`, `:registered-platforms` |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree (per [011](011-SSR.md)) | `:server-hash`, `:client-hash`, `:first-diff-path?` |
| `:rf.warning/plain-fn-under-non-default-frame` | A plain (non-`reg-view`) Reagent fn rendered under a non-default frame; routed to `:re-frame/default`. Per [004 §Plain Reagent fns](004-Views.md) | `:fn-name`, `:rendered-under`, `:routed-to` |

`:rf.fx/skipped-on-platform` is technically a *warning* not an error, but it rides the same envelope and routes through the same listener path; consumers can branch on `:op-type` (`:warning` vs `:error`) if they want to distinguish.

### Schemas

Each category's `:tags` shape is registered as a Malli schema so consumers can validate without ad-hoc parsing:

```clojure
;; Conceptual; the actual registration mechanism is implementation-specific.

(def HandlerExceptionTags
  [:map
   [:category          [:= :rf.error/handler-exception]]
   [:failing-id        :keyword]
   [:frame             :keyword]
   [:reason            :string]
   [:event             [:vector :any]]
   [:handler-id        :keyword]
   [:exception-message :string]
   [:exception-data    {:optional true} :any]])

(def SchemaValidationTags
  [:map
   [:category    [:= :rf.error/schema-validation-failure]]
   [:failing-id  :keyword]
   [:frame       :keyword]
   [:reason      :string]
   [:where       [:enum :event :sub-return :app-db :fx-args :cofx-args :on-create]]
   [:path        [:vector :any]]
   [:value       :any]
   [:explanation :any]])                         ;; Malli explanation shape

;; ... and so on for each category.
```

Pattern-level: every implementation registers an equivalent set of schemas. The category vocabulary is stable and additive — new categories can be added but existing ones cannot be renamed or removed (Spec-ulation).

### Recovery contract

The `:recovery` field on the trace event tells consumers (10x, re-frame-pair, error-monitor integrations) what the runtime did:

- `:no-recovery` — the error propagated; the event was not handled.
- `:replaced-with-default` — the runtime used a default value (e.g., `:no-such-handler` falling through to a no-op).
- `:retried` — the runtime retried (with an upper bound) and surfaces the result.
- `:skipped` — the runtime declined to act (`:rf.fx/skipped-on-platform`).
- `:warned-and-replaced` — the runtime emitted the warning and did its default action anyway (e.g., `:rf.ssr/hydration-mismatch` warn-and-replace mode).

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
- Optional keys in the return map: `:replacement` (a value to use when `:recovery` is `:replaced-with-default`), `:retry-count` (when `:recovery` is `:retried`), `:notes` (a string surfaced in the resulting trace).

Only **one** error-handler is registered at a time per process. Replacing it replaces the policy. The default error-handler is registered automatically; user calls to `reg-event-error-handler` override it.

#### Default behaviour by category

| Category | Default `:recovery` | Notes |
|---|---|---|
| `:rf.error/handler-exception` | `:no-recovery` | The exception propagates; the cascade halts. |
| `:rf.error/fx-handler-exception` | `:no-recovery` | The fx is skipped; cascade continues if other fx independent. |
| `:rf.error/sub-exception` | `:replaced-with-default` | The sub returns `nil`; views see no value. |
| `:rf.error/schema-validation-failure` | `:no-recovery` (dev) / `:replaced-with-default` (prod) | Dev: hard-fail to surface bugs early. Prod: log and proceed with the offending value (validation at boundaries should already have rejected it). |
| `:rf.error/drain-depth-exceeded` | `:no-recovery` | Always indicates a bug; halt the cascade. |
| `:rf.error/no-such-handler` | `:replaced-with-default` | No-op; emit the trace. |
| `:rf.error/override-fallthrough` | `:replaced-with-default` | Use the registered fx as if no override existed. |
| `:rf.fx/skipped-on-platform` | `:skipped` | Documented; not really an error. |
| `:rf.ssr/hydration-mismatch` | `:warned-and-replaced` | Re-render client-side; the server's HTML is replaced. |

#### Composition with libraries (Sentry, Honeybadger, etc.)

Error-monitoring libraries integrate by registering an error-handler that forwards the structured error event to the monitoring service AND returns `nil` to delegate recovery:

```clojure
(rf/reg-event-error-handler
  (fn forward-and-defer [error-event]
    (sentry/capture-event (sentry-shape error-event))
    nil))                                          ;; runtime applies default recovery
```

Multiple monitoring concerns compose in user code (one error-handler that fans out to several services). The runtime exposes one slot.

## Open questions

### I-1. Trace allocation cost in dev when no listeners

When a listener is *never* registered, the macros' compile-time gate doesn't help — `is-trace-enabled?` is true (dev), so the body runs. The body should fast-fail when `(empty? @trace-cbs)`. Confirm the emit macros do this check before allocating the trace map; profile against eager allocation if uncertain.

### I-2. Listener ordering

Multiple listeners exist (10x + re-frame-pair + custom). Order is by registration time (FIFO map iteration). Locked: tools should not depend on order, since batched delivery means each listener gets the same collection independently.

### I-3. ~~Trace correlation across the cascade~~ — *resolved.*

Use the existing `:child-of` field (parent trace id). 10x's epoch grouping already relies on this; re-frame2 preserves it.

### I-4. Trace event for app-db changes

`:db` mutations happen inside `do-fx`. Today's re-frame already captures `:app-db-before` and `:app-db-after` in the `:event` trace's `:tags`. Sufficient for 10x's purposes; no separate `:app-db/changed` event needed. Locked.

### I-5. Privacy / sensitive data in traces

Trace events contain dispatched event vectors, which may include user input (passwords, PII). Tools that ship traces (e.g., to Sentry) must redact. Recommendation: provide a `:sensitive?` tag that handlers can set; a `with-redacted` interceptor pattern that strips sensitive args before emit. Worth a documented pattern in v1.x; not blocking for v1.

## Disposition

**v1.** Tracing ships in v1 because 10x depends on it. The Chrome Performance API bridge ships as part of the trace machinery — automatic in dev builds when tracing is active, fully elided in production along with the rest of tracing. The forward-compat commitments are stated now and locked.

The substrate is small — trace events are data, listeners are functions, cheap-path is one atom-deref. Most of the work is in defining the event taxonomy and getting the discipline right.

Post-v1 work this enables:

- 10x evolution (UI changes, new panels, new integrations) without re-frame2 changes.
- re-frame-pair evolution (smarter agent workflows) without re-frame2 changes.
- New tools (custom dev panels, observability platforms, learning agents) using the same trace surface.

The forward-compat commitments turn this from "current tooling support" into "infrastructure for any future tool."
