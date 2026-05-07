# re-frame2 — API

> **Type:** Reference
> Reference for the CLJS implementation's API: signatures, status, cross-references. No rationale — per-Spec docs own the *why*. Pattern-level contracts live in [000-Vision §The pattern](000-Vision.md#the-pattern-language-agnostic) and the per-Spec docs. **`:fx-overrides` asymmetry:** id-valued at the pattern level; CLJS reference also accepts fn values — see [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers).

## Conventions

- **Status** — exactly one base value, optionally combined with a single qualifier:
  - Base values: `v1` (ships in v1), `v1 (preserved)` (exists in current re-frame; preserved unchanged), `v1 (preserved + extended)` (exists today; v1 adds new arity or behaviour), `post-v1 lib` (design spec in v1 Specs but ships in a post-v1 library).
  - Qualifiers (combined as `<base>, <qualifier>` — comma-separated): `alpha` (lives in `re-frame.alpha`), `dev-only` (elided in production builds — the macro emit site or runtime body, depending on the API).
  - Examples: `v1`, `v1 (preserved)`, `v1 (preserved, alpha)`, `v1 (dev-only)`, `v1 (preserved, dev-only)`, `post-v1 lib`.
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- **Spec column** — names exactly the **canonical owning Spec** (the per-Spec doc whose contract this API implements). Migration rules and other cross-references are NOT in the Spec column; they appear in the Notes column when relevant.
- **Configure keys** — runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every `<key>` is enumerated in [§Configure keys](#configure-keys) below; per-area tables call out which keys their APIs read but do not redefine the key's vocabulary.
- All APIs live in `re-frame.core` unless otherwise noted. APIs in `re-frame.alpha` carry the `, alpha` qualifier and are also catalogued in [§Alpha namespace](#alpha-namespace-re-framealpha).

---

## Registration

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `reg-event-db` | M | `(reg-event-db id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Macro for source-coord capture. See [MIGRATION §M-5](MIGRATION.md) for higher-order-use migration. |
| `reg-event-fx` | M | `(reg-event-fx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | Handler accepts `(fn [m] ...)` or `(fn [m event-vec] ...)`. |
| `reg-event-ctx` | M | `(reg-event-ctx id ?metadata-or-interceptors handler)` | v1 (preserved + extended) | 002 | |
| `reg-sub` | M | `(reg-sub id ?metadata signal-fn? computation-fn)` | v1 (preserved + extended) | 002 | `:<-` sugar preserved. The only sub-registration form in v2. |
| `reg-fx` | M | `(reg-fx id ?metadata handler)` | v1 (preserved + extended) | 002 | Unary or binary handler. |
| `reg-cofx` | M | `(reg-cofx id ?metadata handler)` | v1 (preserved) | 002 | |
| `reg-frame` | M | `(reg-frame id metadata)` | v1 | 002 | Atomic create + register. |
| `make-frame` | Fn | `(make-frame opts) → :rf.frame/<id>` | v1 | 002 | Anonymous frame; gensym'd id. |
| `reg-view` | M | `(reg-view id ?metadata render-fn) → wrapped-fn` | v1 | 004 | |
| `reg-app-schema` | M | `(reg-app-schema path schema)` | v1 | 010 | |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow id flow)` | v1 (preserved, alpha) | — | |
| `reg-route` | M | `(reg-route id metadata)` | v1 | 012 | |
| `reg-event-error-handler` | Fn | `(reg-event-error-handler handler-fn)` | v1 (preserved + extended) | 009 | Single-slot policy mechanism. |
| `reg-head` | M | `(reg-head id ?metadata head-fn)` | v1 | 011 | New registry kind `:head`; routes name a registered head via `:head` route metadata. |
| `reg-error-projector` | M | `(reg-error-projector id ?metadata projector-fn)` | v1 | 011 | New registry kind `:error-projector`; named via `(rf/configure :ssr {:public-error-id ...})`. |

### Clearing registrations

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `clear-event` | Fn | `(clear-event)` / `(clear-event id)` | v1 (preserved) |
| `clear-sub` | Fn | `(clear-sub)` / `(clear-sub id)` | v1 (preserved) |
| `clear-fx` | Fn | `(clear-fx)` / `(clear-fx id)` | v1 (preserved) |
| `clear-cofx` | Fn | `(clear-cofx)` / `(clear-cofx id)` | v1 (preserved) |
| `clear-flow` | Fn | `(clear-flow)` / `(clear-flow id)` | v1 (preserved, alpha) |
| `destroy-frame` | Fn | `(destroy-frame frame-id)` | v1 |
| `reset-frame` | Fn | `(reset-frame frame-id)` | v1 |
| `clear-subscription-cache!` | Fn | `(clear-subscription-cache! frame-id?)` | v1 (preserved) |

---

## Dispatch and subscribe

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `dispatch` | Fn | `(dispatch event)` / `(dispatch event opts)` | v1 (preserved + extended) | 002 |
| `dispatch-sync` | Fn | `(dispatch-sync event)` / `(dispatch-sync event opts)` | v1 (preserved + extended) | 002 |
| `subscribe` | Fn | `(subscribe query-v)` / `(subscribe query-v opts)` | v1 (preserved + extended) | 002 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot. Sugar over `(subscribe [:rf/machine machine-id])`. | v1 | 005 |
| `dispatch-and-settle` | Fn | `(dispatch-and-settle event opts?)` | v1 (preserved) | 002 |

`opts` map keys: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics: see [002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope).

---

## View ergonomics

| API | M/Fn | Signature | Status | Spec |
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

## Routing (Spec 012)

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-route` | M | `(reg-route id metadata)` | v1 | 012 |
| `match-url` | Fn | `(match-url url)` → `{:route-id :params :query :validation-failed?}` or `nil` | v1 | 012 |
| `route-url` | Fn | `(route-url route-id path-params)` / `(route-url route-id path-params query-params)` → URL string | v1 | 012 |
| `route-link` | Fn (registered view) | `[rf/route-link {:to :route-id :params {...} :query {...}} & children]` | v1 | 012 |

`reg-route` metadata reserved keys: `:doc`, `:path`, `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`, `:on-match`, `:on-error`, `:can-leave`, `:scroll`. Canonical detail in [012-Routing.md](012-Routing.md); shape in [Spec-Schemas §`:rf/route-metadata`](Spec-Schemas.md#rfroute-metadata).

Standard route-related events:

| Event | Notes | Spec |
|---|---|---|
| `:rf.route/navigate` | Navigate to a registered route. | 012 |
| `:rf.route/handle-url-change` | Default handler for `:rf/url-changed`. | 012 |
| `:rf/url-changed` | The browser URL changed. | 012 |
| `:rf/url-requested` | The user clicked a framework-owned link. | 012 |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation. | 012 |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation. | 012 |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation. | 012 |

Standard route-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:route` | The full `:route` slice `{:id :params :query :transition :error}` | 012 |
| `:rf.route/id` | Current route id | 012 |
| `:rf.route/params` | Current path params | 012 |
| `:rf.route/query` | Current query params | 012 |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` | 012 |
| `:rf.route/error` | Current error map (when `:transition = :error`) | 012 |
| `:rf.route/fragment` | Current URL fragment (string or nil) | 012 |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) | 012 |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise | 012 |

Standard route-related fx (canonical detail in [012-Routing.md](012-Routing.md)):

| Fx | Args | Platforms |
|---|---|---|
| `:rf.nav/push-url` | URL string | `:client` |
| `:rf.nav/replace-url` | URL string | `:client` |
| `:rf.nav/scroll` | scroll-spec map | `:client` |
| `:rf.route/with-nav-token` | `{:do <fx-entry> :nav-token <token>}` | universal |

---

## SSR (Spec 011)

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `render-to-string` | Fn | `(render-to-string view-or-hiccup opts)` → HTML string | v1 | 011 |
| `reg-head` | M | `(reg-head id ?metadata head-fn)` — head-fn signature `(fn [db route] head-model)` | v1 | 011 |
| `render-head` | Fn | `(render-head head-id opts)` → `:rf/head-model` | v1 | 011 |
| `active-head` | Fn | `(active-head)` / `(active-head frame-id)` → `:rf/head-model` | v1 | 011 |
| `reg-error-projector` | M | `(reg-error-projector id ?metadata projector-fn)` — projector-fn signature `(fn [trace-event] :rf/public-error)` | v1 | 011 |

Standard SSR-related events:

| Event | What it does | Spec |
|---|---|---|
| `:rf/server-init` | Per-request server-side initialisation. Reads request cofx; dispatches setup events. `:platforms #{:server}`. | 011 |
| `:rf/hydrate` | Seed the client-side `app-db` from the server-supplied payload. Runs once on client bootstrap. | 011 |

Standard SSR-related fx (server-only; `:platforms #{:server}`):

| Fx | Args | Spec |
|---|---|---|
| `:rf.server/set-status` | `:int` (per `:rf.fx.server/set-status-args`) | 011 |
| `:rf.server/set-header` | `{:name :value}` (per `:rf.fx.server/set-header-args`) | 011 |
| `:rf.server/append-header` | `{:name :value}` (per `:rf.fx.server/append-header-args`) | 011 |
| `:rf.server/set-cookie` | `:rf.server/cookie` map | 011 |
| `:rf.server/delete-cookie` | `{:name ?:path ?:domain}` | 011 |
| `:rf.server/redirect` | `{:location ?:status}` (default `:status 302`); truncates HTML | 011 |

Standard SSR-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:rf/response` | The current request's response accumulator (status / headers / cookies / redirect) | 011 |
| `:rf/head` | The head model for the active route (resolved via `(active-head)`) | 011 |
| `:rf/public-error` | The sanitised public-error projection when an error page is being rendered; `nil` otherwise | 011 |

Standard cofx (server-only):

| Cofx | Returns | Spec |
|---|---|---|
| `:rf.server/request` | The active HTTP request map | 011 |

`reg-fx`'s `:platforms` metadata key (a set containing `:server` and/or `:client`) gates fx execution by active platform; **default `#{:server :client}` (universal)** when the key is absent. Skipped fx emit a `:rf.fx/skipped-on-platform` trace event. Detail in [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx).

`(rf/configure :ssr {...})` keys are catalogued in [§Configure keys](#configure-keys) below.

---

## Effect-map shape

Closed: `:db` + `:fx` only. See [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map). Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` from v1 migrate via [MIGRATION.md §M-8](MIGRATION.md).

| Key | Notes |
|---|---|
| `:db` | New `app-db` (replaces). |
| `:fx` | Vector of `[fx-id args]` pairs. |

Standard `:fx` entries:

| `[fx-id args]` | Args | Status | Spec | Notes |
|---|---|---|---|---|
| `[:dispatch [event-id ...]]` | event vector | v1 | 002 | |
| `[:dispatch-later {:ms ms :dispatch event-vec}]` | options map | v1 | 002 | |
| `[:http args]` | impl-specific | — | — | user-registered via `reg-fx`. |
| `[:rf.nav/push-url url-string]` | URL string | v1 | 012 | |
| `[:raise event-vec]` | event vector | v1 | 005 | *machine-only*: reserved fx-id recognised by the machine handler; routes the event back into the same machine, atomic and pre-commit. Outside a machine action's `:fx`, this fx-id is unbound. |
| `[:spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`: `:machine-id`/`:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`) | v1 | 005 | *machine-only*: reserved fx-id recognised by the machine handler; registers a new dynamic actor (whose snapshot lives at `[:rf/machines <gensym'd-id>]`) and (via `:on-spawn`) records its id into the parent's `:data`. Outside a machine action's `:fx`, this fx-id is unbound. |

---

## Public registrar query API

For tooling, agents, story tools, 10x.

| API | M/Fn | Signature | Status | JVM-runnable? | Spec |
|---|---|---|---|---|---|
| `handlers` | Fn | `(handlers kind)` / `(handlers kind pred-fn)` | v1 | ✓ | 002 |
| `handler-meta` | Fn | `(handler-meta kind id)` | v1 | ✓ | 002 |
| `machines` | Fn | `(machines)` → seq of machine-ids. Derived view over `(handlers :event)` filtered by `:rf/machine? true`. | v1 | ✓ | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration-metadata map (transition table, doc, schemas). Equivalent to `(handler-meta :event machine-id)`. | v1 | ✓ | 005 |
| `frame-ids` | Fn | `(frame-ids)` / `(frame-ids ns-prefix)` | v1 | ✓ | 002 |
| `frame-meta` | Fn | `(frame-meta frame-id)` | v1 | ✓ | 002 |
| `get-frame-db` | Fn | `(get-frame-db frame-id)` → atom | v1 | ✓ | 002 |
| `snapshot-of` | Fn | `(snapshot-of path)` / `(snapshot-of path opts)` | v1 | ✓ | 002 |
| `sub-topology` | Fn | `(sub-topology)` → static dependency graph | v1 | ✓ | 002 |
| `sub-cache` | Fn | `(sub-cache frame-id)` → live cache state | v1 | ✗ (CLJS-only) | 002 |
| `app-schemas` | Fn | `(app-schemas)` / `(app-schemas {:frame frame-id})` → map of path → schema | v1 | ✓ | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` / `(app-schema-at path {:frame frame-id})` | v1 | ✓ | 010 |
| `app-schemas-digest` | Fn | `(app-schemas-digest)` / `(app-schemas-digest {:frame frame-id})` → string | v1 | ✓ | 010 |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | ✓ | 008 |

---

## Schemas

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-app-schema` | M | `(reg-app-schema path schema)` | v1 | 010 |
| `app-schemas` | Fn | `(app-schemas)` / `(app-schemas {:frame frame-id})` | v1 | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` / `(app-schema-at path {:frame frame-id})` | v1 | 010 |
| `app-schemas-digest` | Fn | `(app-schemas-digest)` / `(app-schemas-digest {:frame frame-id})` → string | v1 | 010 |
| `:spec/validate-at-boundary` (interceptor) | — | Add to `:interceptors` for production-boundary validation | v1 | 010 |

See [010 §Schemas](010-Schemas.md) for `:spec` metadata, validation timing, and dev/prod elision.

---

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics, listener delivery, and Performance API bridge.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-trace-cb` | Fn | `(register-trace-cb key callback)` / `(register-trace-cb key callback opts)` | v1 (preserved) | 009 |
| `remove-trace-cb` | Fn | `(remove-trace-cb key)` | v1 (preserved) | 009 |
| `is-trace-enabled?` | Fn | `(is-trace-enabled?)` | v1 (preserved) | 009 |
| `trace-api-version` | Fn | `(trace-api-version)` → integer | v1 | 009 |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | 009 |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | 009 |
| `(rf/configure :trace-buffer {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | 009 |
| `with-trace` | M | `(with-trace opts body)` | v1 (preserved, dev-only) | 009 |
| `merge-trace!` | M | `(merge-trace! m)` | v1 (preserved, dev-only) | 009 |
| `finish-trace` | M | `(finish-trace trace)` | v1 (preserved, dev-only) | 009 |

### Epoch history (per Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records | v1 (dev-only) | Tool-Pair |
| `restore-epoch` | Fn | `(restore-epoch frame-id epoch-id)` → nil | v1 (dev-only) | Tool-Pair |
| `(rf/configure :epoch-history {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | Tool-Pair |

Trace events emitted by epoch-history machinery:

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:epoch-id`, `:event-id` |
| `:rf.epoch/restored` | `:frame`, `:from-epoch-id`, `:to-epoch-id` |

### DOM source-coord annotations (CLJS reference, opt-in)

Per [Tool-Pair §Source-mapping](Tool-Pair.md), the CLJS reference can annotate rendered DOM with a `data-rf2-source-coord` attribute pointing back to the registration that produced it. Configured via `(rf/configure :dom-source-annotations? true)` (per [§Configure keys](#configure-keys)). Dev-only. Off by default.

### Error contract

Errors are emitted as structured trace events with `:op-type :error` and a per-category `:operation` keyword. See [009 §Error contract](009-Instrumentation.md#error-contract) for the full category list.

Pattern-level error categories:

| `:operation` | Meaning |
|---|---|
| `:rf.error/handler-exception` | An event handler threw |
| `:rf.error/fx-handler-exception` | A registered fx threw |
| `:rf.error/sub-exception` | A subscription's computation threw |
| `:rf.error/no-such-sub` | A subscription's `:<-` input refers to an unregistered sub |
| `:rf.error/schema-validation-failure` | A `:spec`-validated value failed validation |
| `:rf.error/drain-depth-exceeded` | Run-to-completion drain hit its depth limit |
| `:rf.error/no-such-handler` | Dispatch arrived with no registered handler |
| `:rf.error/dispatch-sync-in-handler` | `dispatch-sync` was called from inside a running event handler (use `:fx [[:dispatch event]]`) |
| `:rf.error/machine-action-wrote-db` | A machine action's effect map contained `:db` |
| `:rf.error/machine-raise-depth-exceeded` | A machine event's `:raise` cascade exceeded its depth limit |
| `:rf.error/machine-always-depth-exceeded` | A machine event's `:always` microstep loop exceeded its depth limit |
| `:rf.error/machine-always-self-loop` | A state's `:always` vector declares a same-state same-guard self-loop |
| `:rf.error/machine-grammar-not-in-v1` | A v1 helper encountered a post-v1 transition-table feature |
| `:rf.error/machine-unresolved-guard` | Transition table references an unknown `:guard` keyword |
| `:rf.error/machine-unresolved-action` | Transition table references an unknown `:action` keyword |
| `:rf.error/unknown-preset` | `reg-frame` / `make-frame` was called with a `:preset` value not in the closed v1 set (`:default`, `:test`, `:story`, `:ssr-server`) |
| `:rf.error/override-fallthrough` | An override targeted an unregistered id |
| `:rf.error/duplicate-url-binding` | Two frames declared `:url-bound? true` simultaneously (per Spec 012 R-4) |
| `:rf.error/adapter-already-installed` | `install-adapter!` called after frames exist (per Spec 006 S-1) |
| `:rf.error/derived-container-replaced` | `replace-container!` called on a derived container (per Spec 006 §make-derived-value) |
| `:rf.error/adapter-disposed` | An adapter function was called after `dispose-adapter!` ran |
| `:rf.fx/skipped-on-platform` | Fx was skipped because `:platforms` excluded the active platform |
| `:rf.ssr/hydration-mismatch` | First client render diverges from server-supplied render-tree |
| `:rf.warning/plain-fn-under-non-default-frame-once` | Plain Reagent fn rendered under a non-default frame; emitted once per `(component-id, non-default-frame-id)` pair |
| `:rf.warning/no-clock-configured` | A timing-sensitive substrate feature was exercised on a host whose interop clock layer is unwired |
| `:rf.warning/route-shadowed-by-equal-score` | Two registered routes have an equal structural rank |
| `:rf.warning/multiple-status-set` | Two or more `:rf.server/set-status` calls in the same request drain (last-write-wins) |
| `:rf.warning/multiple-redirects` | Two or more `:rf.server/redirect` calls in the same request drain (last-write-wins) |
| `:rf.warning/head-mismatch` | Client-computed head model differs from server-supplied head; client re-renders and replaces |
| `:rf.error/sanitised-on-projection` | Error projector threw or returned a non-conforming shape; runtime fell back to the locked generic-500 public-error |

---

## Spec-internal schemas

Per [Spec-Schemas.md](Spec-Schemas.md), the spec's own runtime shapes are described as Malli schemas registered at runtime. These are the **conformance contract** an implementation validates against.

| Schema | Describes | Spec |
|---|---|---|
| `:rf/dispatch-envelope` | Internal envelope wrapping every dispatch | 002 |
| `:rf/dispatch-opts` | The user-facing opts map for `dispatch` / `dispatch-sync` / `subscribe` | 002 |
| `:rf/registration-metadata` | Common metadata-map shape across `reg-*` | 001 / 010 |
| `:rf/effect-map` | Return value of `reg-event-fx` handlers — **closed**: only `:db` and `:fx` | 002 |
| `:rf/trace-event` | Universal trace event shape | 009 |
| `:rf/error-event` | Refinement of `:rf/trace-event` for `:op-type :error` / `:warning` (unified error/warning envelope) | 009 |
| `:rf/handler-body-dsl` | Conformance corpus handler-body DSL (host-agnostic event/sub bodies; small-DSL grammar) | 008 / Spec-Schemas |
| `:rf/transition-table` | State-machine transition table grammar | 005 |
| `:rf/machine-snapshot` | Runtime snapshot of a machine instance | 005 |
| `:rf/hydration-payload` | Wire format for SSR hydration | 011 |
| `:rf/response` | HTTP-response accumulator owned by the request frame during SSR | 011 |
| `:rf.server/cookie` | Structured-cookie shape for `:rf.server/set-cookie` / `:rf.server/delete-cookie` | 011 |
| `:rf/head-model` | SSR head/meta data model (title, meta, link, json-ld, html/body attrs) | 011 |
| `:rf/public-error` | Sanitised, client-safe projection of an internal error trace event | 011 |
| `:rf.fx.server/set-status-args` / `:rf.fx.server/set-header-args` / `:rf.fx.server/append-header-args` / `:rf.fx.server/set-cookie-args` / `:rf.fx.server/delete-cookie-args` / `:rf.fx.server/redirect-args` | Args of standard `:rf.server/*` SSR fx | 011 |
| `:rf/frame-meta` | Returned by `(frame-meta frame-id)` | 002 |
| `:rf/variant` | Story-variant artefact contract (post-v1 lib) — variants are data, no fn-valued slots | 007 |
| `:rf/epoch-record` | Per-frame epoch snapshot record (Tool-Pair) | Tool-Pair |
| `:rf.fx/dispatch-args` | Args of standard `:dispatch` fx (and `:raise`, same shape) | 002 / 005 |
| `:rf.fx/dispatch-later-args` | Args of standard `:dispatch-later` fx | 002 |
| `:rf.fx/http-args` | Args of `:http` fx (user-owned recommendation) | Pattern-RemoteData |
| `:rf.fx/nav/push-url-args` | Args of `:rf.nav/push-url` fx | 012 |
| `:rf.fx/nav/replace-url-args` | Args of `:rf.nav/replace-url` fx | 012 |
| `:rf.fx/nav/scroll-args` | Args of `:rf.nav/scroll` fx | 012 |
| `:rf.fx/with-nav-token-args` | Args of `:rf.route/with-nav-token` fx wrapper | 012 |
| `:rf.fx/spawn-args` | Args of `:spawn` fx (reserved fx-id inside a machine action's `:fx`) | 005 |
| `:rf/route-rank` | Structural-rank tuple for route-precedence sorting | 012 |
| `:rf/pending-navigation` | Pending-navigation slot when `:can-leave` guard rejects | 012 |

Schemas are **open** by default (consumers tolerate unknown keys; producers grow shapes additively); `:closed true` is opt-in at boundary-validation sites and on the effect-map.

---

## Testing

`re-frame.test` re-exports `make-frame`/`destroy-frame`/`with-frame`/`dispatch-sync`. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence frame events)` | v1 | 008 |
| `assert-state` | M | `(assert-state frame path expected-value)` | v1 | 008 |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | 008 |

---

## Standard interceptors

The v2 std-interceptor surface is **three specific helpers** plus the `->interceptor` primitive. The principled line: keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` or `(->interceptor :after f)` with no other logic. Five interceptors removed: `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [MIGRATION §M-21](MIGRATION.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).

| API | M/Fn | Signature | Purpose |
|---|---|---|---|
| `inject-cofx` | Fn | `(inject-cofx id)` / `(inject-cofx id value)` | Inject a registered cofx into the handler's coeffect map. Specific work — `:cofx` registry lookup, not subsumable by `->interceptor`. |
| `path` | Fn | `(path & path)` | Focus a handler on an `app-db` sub-slice. Specific work — `:before` focuses, `:after` splices the result back into parent db. |
| `unwrap` | (val) | `unwrap` | Assert `[id payload-map]` event shape; replace `:event` coeffect with the payload map; restore on `:after`. Sugar over the M-19 canonical map-payload form. |
| `->interceptor` | Fn | `(->interceptor & {:keys [id before after]})` | The primitive. Build a custom interceptor with `:before` and/or `:after` slots. **Use this for any work not covered by the three specific helpers above** — analytics, logging, validation, ad-hoc context manipulation. The resulting interceptor is named, addressable, and queryable like any other artefact. |

Removed in v2 (see [MIGRATION §M-21](MIGRATION.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)):

| Removed API | Replaced by |
|---|---|
| `debug` | Trace surface ([009](009-Instrumentation.md)) + 10x / re-frame-pair |
| `trim-v` | Canonical map-payload call shape ([M-19](MIGRATION.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)) |
| `on-changes` | Flows ([Spec 013](013-Flows.md)) |
| `enrich` | Flows (derived state) / `:spec` (validation) / custom `->interceptor` (escape hatch) |
| `after` | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility |

### `reg-flow` / `clear-flow` (Spec 013)

| Name | Kind | Signature | Status |
|---|---|---|---|
| `reg-flow` | Fn | `(reg-flow flow-map)` — required keys `:id`, `:inputs`, `:output`, `:path` | v2 |
| `clear-flow` | Fn | `(clear-flow id)` — deregister; `dissoc-in` on `:path` | v2 |
| `:rf.fx/reg-flow` | Reserved fx-id | `[:rf.fx/reg-flow flow-map]` — register a flow at runtime via `:fx` | v2 |
| `:rf.fx/clear-flow` | Reserved fx-id | `[:rf.fx/clear-flow id]` — clear a registered flow via `:fx` | v2 |

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

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `make-restore-fn` | Fn | `(make-restore-fn)` / `(make-restore-fn frame-id)` → restore-fn | v1 (preserved + extended) | 002 |
| `add-post-event-callback` | Fn | `(add-post-event-callback f)` / `(add-post-event-callback id f)` / `(add-post-event-callback frame-id id f)` | v1 (preserved + extended) | 002 |
| `remove-post-event-callback` | Fn | `(remove-post-event-callback id)` / `(remove-post-event-callback frame-id id)` | v1 (preserved + extended) | 002 |
| `purge-event-queue` | Fn | `(purge-event-queue)` / `(purge-event-queue frame-id)` | v1 (preserved + extended) | 002 |
| `set-loggers!` | Fn | `(set-loggers! new-loggers)` | v1 (preserved) | — |
| `console` | Fn | `(console level & args)` | v1 (preserved) | — |
| `install-adapter!` | Fn | `(install-adapter! adapter-or-keyword)` — must be called before any frame is created | v1 | 006 |
| `current-adapter` | Fn | `(current-adapter)` → `:reagent` / `:plain-atom` / `:custom` | v1 | 006 |
| `init-platform` | Fn | `(init-platform :server \| :client)` — sets the active platform; defaults from build target | v1 | 011 |
| `configure` | Fn | `(configure key opts)` — runtime config; key vocabulary in [§Configure keys](#configure-keys) | v1 | — |

---

## Configure keys

Runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every framework-owned key is enumerated here. Keys are plural-noun-shaped; opts are an open map of per-key settings.

| Key | Opts shape | Default | Status | Spec |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N}` — non-negative integer; 0 disables | `{:depth 50}` | v1 (dev-only) | Tool-Pair |
| `:trace-buffer` | `{:depth N}` — non-negative integer; 0 disables | `{:depth 200}` | v1 (dev-only) | 009 |
| `:dom-source-annotations?` | boolean — emit `data-rf2-source-coord` on rendered DOM | `false` | v1 (dev-only) | Tool-Pair |
| `:performance-api` | boolean — bridge trace events to the host's Performance API | `true` (when tracing is on) | v1 (dev-only) | 009 |
| `:strict-subs` | boolean — reject sub-registration shapes that don't have a registered schema | `false` | v1 | 010 |
| `:ssr` | `{:public-error-id :detect-mismatch? :on-mismatch :on-view-exception :dev-error-detail?}` (see [011](011-SSR.md) for each) | per [011](011-SSR.md) | v1 | 011 |

Detail for `:ssr`:

| Sub-key | Values | Meaning |
|---|---|---|
| `:public-error-id` | registered error-projector id | Selects the projector that produces `:rf/public-error` from internal error trace events. |
| `:dev-error-detail?` | boolean | Include `:details` in `:rf/public-error`. Defaults to the dev-build flag. |
| `:on-mismatch` | `:warned-and-replaced` / `:hard-error` | Hydration-mismatch policy. |
| `:detect-mismatch?` | boolean | Run hydration-mismatch detection on first client render. |
| `:on-view-exception` | `:project` / `:throw` | What to do when a view body throws during SSR. |

The configure-keys vocabulary is fixed-and-additive (Spec-ulation): existing keys cannot be renamed or removed; new keys are added by extending the table. User code that wraps `configure` should pattern-match on known keys and ignore unknown ones.

---

## Alpha namespace (`re-frame.alpha`)

Preserved with existing semantics. Alpha APIs are catalogued with the same five-column shape used for the main reference (a `Status` column carrying the `, alpha` qualifier; a `Spec` column when applicable).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg :sub` / `reg :legacy-sub` / `reg :sub-lifecycle` | Fn | `(reg :sub id metadata? handler)` and the lifecycle/legacy variants | v1 (preserved, alpha) | 002 |
| `sub` | Fn | `(sub query-map)` / `(sub query-v)` | v1 (preserved, alpha) | 002 |
| `subscribe` | Fn | Alias for `sub` | v1 (preserved, alpha) | 002 |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow id flow)` | v1 (preserved, alpha) | — |
| `flow<-` | Fn | `(flow<- id)` — sub that reads a flow's current value | v1 (preserved, alpha) | — |
| `clear-flow` | Fn | `(clear-flow)` / `(clear-flow id)` | v1 (preserved, alpha) | — |
| `get-flow` | Fn | `(get-flow id)` — direct value read | v1 (preserved, alpha) | — |
| `:flow` (registered sub) | — | `(subscribe [:flow id])` reads a flow value | v1 (preserved, alpha) | — |
| `:live?` (registered sub) | — | `(subscribe [:live? id])` reads a flow's liveness | v1 (preserved, alpha) | — |

---

## Machines

Split between the v1 machine-as-event-handler foundation and the post-v1 `re-frame.machines` scaffolding library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition). The machine *is* the event handler: a machine is registered as one `reg-event-fx` whose body comes from `create-machine-handler`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `create-machine-handler` | Fn | `(create-machine-handler spec)` → event-handler fn | v1 | 005 |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | 005 |
| `spawn-machine` | Fn | `(spawn-machine spec)` → actor-id | v1 | 005 |
| `destroy-machine` | Fn | `(destroy-machine actor-id)` | v1 | 005 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot | v1 | 005 |
| `machines` | Fn | `(machines)` → seq of registered machine-ids | v1 | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration metadata | v1 | 005 |
| `:spawn` (fx) | — | Reserved fx-id inside a machine action's `:fx`. Args per `:rf.fx/spawn-args`. | v1 | 005 |
| `:raise` (fx) | — | Reserved fx-id inside a machine action's `:fx`. Args: an event vector. | v1 | 005 |
| `:child-machine` (transition-table key) | — | Declarative state-scoped child-machine binding. | post-v1 lib | 005 |
| `machine->xstate-json` | Fn | `(machine->xstate-json definition)` → JSON | post-v1 lib | 005 |
| `machine->mermaid` | Fn | `(machine->mermaid definition)` → string | post-v1 lib | 005 |

Canonical descriptions (factory purity, spec keys, snapshot location, registration-time validation, etc.) in [005-StateMachines.md](005-StateMachines.md) and [Spec-Schemas](Spec-Schemas.md).

v1 transition-table grammar subset is enumerated in [005 §Capability matrix](005-StateMachines.md#capability-matrix); shape in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table).

### Standard registered subs (machines)

| Standard sub | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The machine's snapshot `{:state :data}` (or `nil` if not yet initialised) | 005 |

`sub-machine` is sugar over the registered `:rf/machine` sub — see [005 §Subscribing to machines](005-StateMachines.md#subscribing-to-machines-via-sub-machine).

---

## Story / variant / workspace library (post-v1)

See [007-Stories.md](007-Stories.md).

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-story` | M | `(reg-story id metadata)` | post-v1 lib | 007 |
| `reg-variant` | M | `(reg-variant id metadata)` | post-v1 lib | 007 |
| `reg-workspace` | M | `(reg-workspace id metadata)` | post-v1 lib | 007 |
| `reg-tag` | M | `(reg-tag id metadata)` | post-v1 lib | 007 |
| `reg-decorator` | M | `(reg-decorator id metadata)` | post-v1 lib | 007 |
| `reg-story-panel` | M | `(reg-story-panel id metadata)` | post-v1 lib | 007 |
| `run-variant` | Fn | `(run-variant variant-id)` → result map | post-v1 lib | 007 |
| `watch-variant` | Fn | `(watch-variant variant-id)` → live-updating result map | post-v1 lib | 007 |
| `reset-variant` | Fn | `(reset-variant variant-id)` | post-v1 lib | 007 |
| `variants-with-tags` | Fn | `(variants-with-tags tag-set)` → seq of variant ids | post-v1 lib | 007 |
| `snapshot-identity` | Fn | `(snapshot-identity variant-id)` → `{:variant-id ... :content-hash "..."}` | post-v1 lib | 007 |
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
| `reg-global-interceptor` | Use `reg-frame :interceptors` (frame-level is the canonical "global within this frame"). For cross-frame observation use `register-trace-cb`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | Use `reg-sub` (app-db reads), Pattern-AsyncEffect (non-app-db sources), state machines (lifecycle), or the [006](006-ReactiveSubstrate.md) adapter contract (bridging external reactivity). | MIGRATION M-18 |

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
