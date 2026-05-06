# re-frame2 — Proposed API

> Status: Drafting. **Reference only.** Signatures, status, and cross-references — no rationale. The per-EP docs are the source of truth for *why* each API is shaped the way it is; this page exists for fast lookup.
>
> **Scope per [reorient.md](reorient.md):** this page documents the **CLJS reference implementation's** API. The pattern-level contracts (event handler shape, dispatch envelope shape, view contract, frame contract, trace stream) are documented in the per-EP docs and in [reorient.md §The pattern](reorient.md). Shape description (whether via schemas in dynamic hosts or types in static hosts) is an opt-in capability per implementation; this page shows the CLJS reference's `:spec` Malli integration. Signatures here may shift as the pattern/implementation split is firmed up — for example, function-valued `:fx-overrides` may move toward id-valued at the pattern level (functions don't serialize for SSR), with function values surviving as a CLJS-only convenience.

## Conventions

- **Status:**
  - `v1` — ships in v1.
  - `v1 (preserved)` — exists in current re-frame; preserved unchanged in v1.
  - `v1 (preserved + extended)` — exists today; v1 adds new arity or behaviour.
  - `post-v1 lib` — design spec in v1 EPs but ships in a post-v1 library.
  - `dev-only` — emit-side macro elided in production builds.
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- All APIs live in `re-frame.core` unless otherwise noted.

---

## Registration

| API | M/Fn | Signature | Status | EP | Notes |
|---|---|---|---|---|---|
| `reg-event-db` | M | `(reg-event-db id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 / MIGRATION M-5 | Middle arg is either a metadata map (re-frame2 form) or interceptor vector (legacy). Becomes a macro in v1 for source-coord capture. |
| `reg-event-fx` | M | `(reg-event-fx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Handler accepts `(fn [m] ...)` or `(fn [m event-vec] ...)` — both first-class. |
| `reg-event-ctx` | M | `(reg-event-ctx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Handler signature unchanged: `(fn [context] ...)`. |
| `reg-sub` | M | `(reg-sub id ?metadata signal-fn? computation-fn)` | v1 (preserved + extended) | 002 | `:<-` sugar preserved. `:spec` accepted in metadata. |
| `reg-sub-raw` | M | `(reg-sub-raw id ?metadata handler-fn)` | v1 (preserved) | 002 | |
| `reg-fx` | M | `(reg-fx id ?metadata handler)` | v1 (preserved + extended) | 002 | Handler can be unary `(fn [args])` or binary `(fn [m args])`. Binary is recommended for multi-frame correctness (MIGRATION O-5). |
| `reg-cofx` | M | `(reg-cofx id ?metadata handler)` | v1 (preserved) | 002 | Binary handler supported similarly to `reg-fx`. |
| `reg-event-error-handler` | Fn | `(reg-event-error-handler handler-fn)` | v1 (preserved) | — | |
| `reg-frame` | M | `(reg-frame id metadata)` | v1 | 002 | Atomic create + register. Metadata: `:doc`, `:on-create`, `:on-destroy`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:drain-depth`. Re-registration is surgical (preserves `app-db`/sub-cache/router). |
| `make-frame` | Fn | `(make-frame opts) → :rf.frame/<id>` | v1 | 002 | Anonymous frame: gensym'd keyword, registered, returned. Caller responsible for `destroy-frame` on unmount. |
| `reg-view` | M | `(reg-view id ?metadata render-fn) → wrapped-fn` | v1 | 004 | Registers a view; returns the wrapped (frame-aware) fn so users can `(def my-view (reg-view ...))` if they want Var-style hiccup. |
| `reg-app-schema` | M | `(reg-app-schema path schema)` | v1 | 010 | Path-based `app-db` schema. `[]` for whole-`app-db` root. |
| `reg-global-interceptor` | Fn | `(reg-global-interceptor interceptor)` | v1 (preserved, alpha) | 002 | |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow id flow)` | v1 (preserved, alpha) | — | Alpha API preserved. |

### Clearing registrations

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `clear-event` | Fn | `(clear-event)` / `(clear-event id)` | v1 (preserved) |
| `clear-sub` | Fn | `(clear-sub)` / `(clear-sub id)` | v1 (preserved) |
| `clear-fx` | Fn | `(clear-fx)` / `(clear-fx id)` | v1 (preserved) |
| `clear-cofx` | Fn | `(clear-cofx)` / `(clear-cofx id)` | v1 (preserved) |
| `clear-global-interceptor` | Fn | `(clear-global-interceptor)` / `(clear-global-interceptor id)` | v1 (preserved) |
| `clear-flow` | Fn | `(clear-flow)` / `(clear-flow id)` | v1 (preserved, alpha) |
| `destroy-frame` | Fn | `(destroy-frame frame-id)` | v1 |
| `reset-frame` | Fn | `(reset-frame frame-id)` | v1 |
| `clear-subscription-cache!` | Fn | `(clear-subscription-cache! frame-id?)` | v1 (preserved) |

---

## Dispatch and subscribe

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `dispatch` | Fn | `(dispatch event)` / `(dispatch event opts)` | v1 (preserved + extended) | 002 |
| `dispatch-sync` | Fn | `(dispatch-sync event)` / `(dispatch-sync event opts)` | v1 (preserved + extended) | 002 |
| `subscribe` | Fn | `(subscribe query-v)` / `(subscribe query-v opts)` | v1 (preserved + extended) | 002 |
| `dispatch-and-settle` | Fn | `(dispatch-and-settle event opts?)` | v1 (preserved) | 002 / MIGRATION |

`opts` map keys: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics: see [002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope).

---

## View ergonomics

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `frame-provider` | Fn (Reagent component) | `[rf/frame-provider {:frame :todo} & children]` | v1 | 002 |
| `with-frame` | M | `(with-frame :keyword body)` *or* `(with-frame [sym expr] body)` | v1 | 002 |
| `bound-fn` | M | `(bound-fn [args] body)` | v1 | 002 |
| `bound-dispatcher` | Fn | `(bound-dispatcher m)` → `(fn [event] ...)` | v1 | 002 |
| `dispatcher` | Fn | `(dispatcher)` (called during render) → frame-bound dispatch fn | v1 | 004 |
| `subscriber` | Fn | `(subscriber)` (called during render) → frame-bound subscribe fn | v1 | 004 |
| `get-view` | Fn | `(get-view view-id)` → render-fn | v1 | 004 |
| `h` | M | `(h hiccup-form)` | v1 | 004 |

`with-frame`'s two shapes (bare keyword vs let-binding) are documented in [002 §with-frame](002-Frames.md#with-frame).

---

## Effect-map shape

The standard effect-map keys an `reg-event-fx` handler can return:

| Key | Status | Notes |
|---|---|---|
| `:db` | v1 (preserved) | Replace `app-db`. |
| `:dispatch` | v1 (preserved) | Single event vector. Drains synchronously per run-to-completion. |
| `:dispatch-later` | v1 (preserved) | Vector of `{:ms :dispatch}` maps. Async. |
| `:fx` | v1 (preserved) | Vector of nested `[fx-id args]` pairs. Recommended for multi-fx. |
| `:dispatch-n` | **deprecated** | Migrate to `:fx` per MIGRATION O-7. Still works in v1. |
| `:spawn-machine` | post-v1 lib | Materialise a child machine instance. EP 005 library. |
| `:destroy-machine` | post-v1 lib | Tear down a machine instance. EP 005 library. |

---

## Public registrar query API

For tooling, agents, story tools, 10x.

| API | M/Fn | Signature | Status | JVM-runnable? | EP |
|---|---|---|---|---|---|
| `handlers` | Fn | `(handlers kind)` / `(handlers kind pred-fn)` | v1 | ✓ | 002 |
| `handler-meta` | Fn | `(handler-meta kind id)` | v1 | ✓ | 002 |
| `frame-ids` | Fn | `(frame-ids)` / `(frame-ids ns-prefix)` | v1 | ✓ | 002 |
| `frame-meta` | Fn | `(frame-meta frame-id)` | v1 | ✓ | 002 |
| `get-frame-db` | Fn | `(get-frame-db frame-id)` → atom | v1 | ✓ | 002 |
| `snapshot-of` | Fn | `(snapshot-of path)` / `(snapshot-of path opts)` | v1 | ✓ | 002 |
| `sub-topology` | Fn | `(sub-topology)` → static dependency graph | v1 | ✓ | 002 |
| `sub-cache` | Fn | `(sub-cache frame-id)` → live cache state | v1 | ✗ (CLJS-only) | 002 |
| `app-schemas` | Fn | `(app-schemas)` → map of path → Malli schema | v1 | ✓ | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` | v1 | ✓ | 010 |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | ✓ | 008 |

---

## Schemas

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `reg-app-schema` | M | `(reg-app-schema path schema)` | v1 | 010 |
| `app-schemas` | Fn | `(app-schemas)` | v1 | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` | v1 | 010 |
| `:spec/validate-at-boundary` (interceptor) | — | Add to `:interceptors` for production-boundary validation | v1 | 010 |

See [010 §Schemas](010-Schemas.md) for `:spec` metadata, validation timing, and dev/prod elision.

---

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics, listener delivery, and Performance API bridge.

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `register-trace-cb` | Fn | `(register-trace-cb key callback)` / `(register-trace-cb key callback opts)` | v1 (preserved) | 009 |
| `remove-trace-cb` | Fn | `(remove-trace-cb key)` | v1 (preserved) | 009 |
| `is-trace-enabled?` | Fn | `(is-trace-enabled?)` | v1 (preserved) | 009 |
| `trace-api-version` | Fn | `(trace-api-version)` → integer | v1 | 009 |
| `with-trace` | M | `(with-trace opts body)` | v1 (preserved) — dev-only emit | 009 |
| `merge-trace!` | M | `(merge-trace! m)` | v1 (preserved) — dev-only emit | 009 |
| `finish-trace` | M | `(finish-trace trace)` | v1 (preserved) — dev-only emit | 009 |

### Error contract

Errors are emitted as structured trace events with `:op-type :error` and a per-category `:operation` keyword. See [009 §Error contract](009-Instrumentation.md#error-contract) for the full category list.

Pattern-level error categories:

| `:operation` | Meaning |
|---|---|
| `:rf.error/handler-exception` | An event handler threw |
| `:rf.error/fx-handler-exception` | A registered fx threw |
| `:rf.error/sub-exception` | A subscription's computation threw |
| `:rf.error/schema-validation-failure` | A `:spec`-validated value failed validation |
| `:rf.error/drain-depth-exceeded` | Run-to-completion drain hit its depth limit |
| `:rf.error/no-such-handler` | Dispatch arrived with no registered handler |
| `:rf.error/override-fallthrough` | An override targeted an unregistered id |
| `:rf.fx/skipped-on-platform` | Fx was skipped because `:platforms` excluded the active platform |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree |
| `:rf.warning/plain-fn-under-non-default-frame` | Plain Reagent fn rendered under a non-default frame |

---

## Spec-internal schemas

Per [Spec-Schemas.md](Spec-Schemas.md), the spec's own runtime shapes are described as Malli schemas registered at runtime. These are the **conformance contract** an implementation validates against.

| Schema | Describes | EP |
|---|---|---|
| `:rf/dispatch-envelope` | Internal envelope wrapping every dispatch | 002 |
| `:rf/registration-metadata` | Common metadata-map shape across `reg-*` | 001 / 010 |
| `:rf/effect-map` | Return value of `reg-event-fx` handlers | 002 |
| `:rf/trace-event` | Universal trace event shape | 009 |
| `:rf/transition-table` | State-machine transition table grammar | 005 |
| `:rf/machine-snapshot` | Runtime snapshot of a machine instance | 005 |
| `:rf/hydration-payload` | Wire format for SSR hydration | 011 |
| `:rf/frame-meta` | Returned by `(frame-meta frame-id)` | 002 |

Schemas open by default; `:closed true` only at boundary-validation sites.

---

## Testing

`re-frame.test` re-exports `make-frame`/`destroy-frame`/`with-frame`/`dispatch-sync`. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence frame events)` | v1 | 008 |
| `assert-state` | M | `(assert-state frame path expected-value)` | v1 | 008 |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | 008 |

---

## Standard interceptors

Preserved from current re-frame.

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `path` | Fn | `(path & path)` | v1 (preserved) |
| `enrich` | Fn | `(enrich f)` | v1 (preserved) |
| `after` | Fn | `(after f)` | v1 (preserved) |
| `on-changes` | Fn | `(on-changes f out-path & in-paths)` | v1 (preserved) |
| `unwrap` | (val) | `unwrap` | v1 (preserved) |
| `trim-v` | (val) | `trim-v` | v1 (preserved) |
| `debug` | (val) | `debug` | v1 (preserved) |
| `inject-cofx` | Fn | `(inject-cofx id)` / `(inject-cofx id value)` | v1 (preserved) |
| `->interceptor` | Fn | `(->interceptor & {:keys [id before after]})` | v1 (preserved) |

---

## Interceptor / context plumbing

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `get-coeffect` | Fn | `(get-coeffect ctx)` / `(get-coeffect ctx key)` / `(get-coeffect ctx key not-found)` | v1 (preserved) |
| `assoc-coeffect` | Fn | `(assoc-coeffect ctx key value)` | v1 (preserved) |
| `get-effect` | Fn | `(get-effect ctx)` / `(get-effect ctx key)` / `(get-effect ctx key not-found)` | v1 (preserved) |
| `assoc-effect` | Fn | `(assoc-effect ctx key value)` | v1 (preserved) |
| `enqueue` | Fn | `(enqueue ctx interceptors)` | v1 (preserved) |

---

## Lifecycle / utility

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `make-restore-fn` | Fn | `(make-restore-fn)` → restore-fn | v1 (preserved) |
| `add-post-event-callback` | Fn | `(add-post-event-callback f)` / `(add-post-event-callback id f)` | v1 (preserved) |
| `remove-post-event-callback` | Fn | `(remove-post-event-callback id)` | v1 (preserved) |
| `purge-event-queue` | Fn | `(purge-event-queue)` | v1 (preserved) |
| `set-loggers!` | Fn | `(set-loggers! new-loggers)` | v1 (preserved) |
| `console` | Fn | `(console level & args)` | v1 (preserved) |

---

## Alpha namespace (`re-frame.alpha`)

Preserved with existing semantics.

| API | Notes |
|---|---|
| `reg :sub` / `reg :legacy-sub` / `reg :sub-lifecycle` | Generalised registration with query-map support. |
| `sub` | Subscribe with query-map shape. |
| `subscribe` | Alias for `sub`. |
| `reg-flow`, `flow<-`, `clear-flow`, `get-flow` | Flow API. |
| `:flow` and `:live?` registered subs | Read flow values. |

---

## Machines

Split between v1 thin helpers and post-v1 library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition).

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | 005 |
| `machine-handler` | Fn | `(machine-handler path definition)` → event-handler fn | v1 | 005 |
| `make-machine-instance` | Fn | `(make-machine-instance opts)` → instance-id | post-v1 lib | 005 |
| `destroy-machine-instance` | Fn | `(destroy-machine-instance path)` | post-v1 lib | 005 |
| `:spawn-machine` (fx) | — | Effect-map key | post-v1 lib | 005 |
| `:destroy-machine` (fx) | — | Effect-map key | post-v1 lib | 005 |
| `:child-machine` (transition-table key) | — | Declarative child-machine binding | post-v1 lib | 005 |
| `machine->xstate-json` | Fn | `(machine->xstate-json definition)` → JSON | post-v1 lib | 005 |

---

## Story / variant / workspace library (post-v1)

See [007-Stories.md](007-Stories.md).

| API | M/Fn | Signature | Status | EP |
|---|---|---|---|---|
| `reg-story` | M | `(reg-story id metadata)` | post-v1 lib | 007 |
| `reg-variant` | M | `(reg-variant id metadata)` | post-v1 lib | 007 |
| `reg-workspace` | M | `(reg-workspace id metadata)` | post-v1 lib | 007 |
| `run-variant` | Fn | `(run-variant variant-id)` → result map | post-v1 lib | 007 |
| `reset-variant` | Fn | `(reset-variant variant-id)` | post-v1 lib | 007 |
| `variants-with-tags` | Fn | `(variants-with-tags tag-set)` → seq of variant ids | post-v1 lib | 007 |
| `story-view` | Fn | `(story-view variant-id)` → hiccup | post-v1 lib | 007 |
| `frame-doc` | Fn | `(frame-doc frame-id)` | v1 | 002 / 007 |

---

## Removed / not shipped

| API | What to do | Reference |
|---|---|---|
| `dispatch-with` (master) | Use `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | Use `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | Use `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | Use `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` (proposed earlier) | Renamed to `bound-dispatcher` | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Bridge always active when tracing is on; production elides everything | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | Use `register-trace-cb` / `remove-trace-cb` | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup (without `h`) | Use `(rf/h [:my-view "args"])` or `[(rf/get-view :my-view) "args"]` | 004 |

---

## Cross-references

- [000-Vision.md](000-Vision.md) — principles and design decisions
- [002-Frames.md](002-Frames.md) — frames, dispatch envelope, drain semantics, overrides, machine foundations
- [004-Views.md](004-Views.md) — view registration, hiccup forms
- [005-StateMachines.md](005-StateMachines.md) — machine library design (post-v1)
- [007-Stories.md](007-Stories.md) — story/variant/workspace library design (post-v1)
- [008-Testing.md](008-Testing.md) — testing API and patterns
- [009-Instrumentation.md](009-Instrumentation.md) — tracing, Performance API, 10x compatibility
- [010-Schemas.md](010-Schemas.md) — Malli schemas
- [MIGRATION.md](MIGRATION.md) — AI-driven migration spec
