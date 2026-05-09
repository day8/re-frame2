# re-frame2 — API

> **Type:** Reference
> Reference for the CLJS implementation's API: signatures, status, cross-references. No rationale — per-Spec docs own the *why*. Pattern-level contracts live in [000-Vision §The pattern](000-Vision.md#the-pattern-language-agnostic) and the per-Spec docs. **`:fx-overrides` asymmetry:** id-valued at the pattern level; CLJS reference also accepts fn values — see [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers).

## Conventions

- **Status** — exactly one base value, optionally combined with a single qualifier:
  - Base values: `v1` (ships in v1), `v1 (preserved)` (exists in current re-frame; preserved unchanged), `v1 (preserved + extended)` (exists today; v1 adds new arity or behaviour), `post-v1 lib` (design spec in v1 Specs but ships in a post-v1 library).
  - Qualifier: `dev-only` (elided in production builds — the macro emit site or runtime body, depending on the API).
  - Examples: `v1`, `v1 (preserved)`, `v1 (dev-only)`, `v1 (preserved, dev-only)`, `post-v1 lib`.
  - The `re-frame.alpha` namespace is dissolved (rf2-7cb2 / rf2-s9dn) — no APIs in this reference live outside `re-frame.core` (with the documented per-namespace exceptions: `re-frame.test-support`, `re-frame.views-macros`).
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- **Spec column** — names exactly the **canonical owning Spec** (the per-Spec doc whose contract this API implements). Migration rules and other cross-references are NOT in the Spec column; they appear in the Notes column when relevant.
- **Configure keys** — runtime configuration is uniformly via `(rf/configure <key> <opts>)`. Every `<key>` is enumerated in [§Configure keys](#configure-keys) below; per-area tables call out which keys their APIs read but do not redefine the key's vocabulary.
- All APIs live in `re-frame.core` unless otherwise noted (`re-frame.test-support`, `re-frame.views-macros`).

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
| `reg-view` | M | `(reg-view sym [args] body+)` / `(reg-view sym docstring [args] body+)` / `(reg-view ^{:rf/id :explicit/id} sym [args] body+)` | v1 | 004 | Defn-shape; auto-defs the symbol; auto-derives id from `(keyword *ns* sym)`; auto-injects `dispatch` / `subscribe` as lexical bindings; rejects non-defn-shape bodies at macroexpand. |
| `reg-view*` | Fn | `(reg-view* id render-fn)` / `(reg-view* id metadata render-fn)` | v1 | 004 | Plain-fn surface beneath `reg-view`. No auto-def, no auto-inject, no compile check. Use for computed ids, library-generated views, Reagent Form-3 (`create-class`), or registration without a Var. The `*` follows Clojure's `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). |
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` | v1 | 005 | Walks the literal spec form at expansion time; stamps per-element source coords under `:rf.machine/source-coords` (rf2-8bp3). Top-level call-site coords land on `handler-meta`. |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` | v1 | 005 | Plain-fn surface beneath `reg-machine`. No source-coord walking. Use for code-gen pipelines, REPL workflows, or conformance harnesses that synthesise specs from data. |
| `reg-app-schema` | M | `(reg-app-schema path schema)` | v1 | 010 | |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow id flow)` | v1 | 013 | |
| `reg-route` | M | `(reg-route id metadata)` | v1 | 012 | |
| `reg-head` | M | `(reg-head id ?metadata head-fn)` | v1 | 011 | New registry kind `:head`; routes name a registered head via `:head` route metadata. |
| `reg-error-projector` | M | `(reg-error-projector id ?metadata projector-fn)` | v1 | 011 | New registry kind `:error-projector`; named via `(rf/configure :ssr {:public-error-id ...})`. |

### Clearing registrations

| API | M/Fn | Signature | Status |
|---|---|---|---|
| `clear-event` | Fn | `(clear-event)` / `(clear-event id)` | v1 (preserved) |
| `clear-sub` | Fn | `(clear-sub)` / `(clear-sub id)` | v1 (preserved) |
| `clear-fx` | Fn | `(clear-fx)` / `(clear-fx id)` | v1 (preserved) |
| `clear-flow` | Fn | `(clear-flow)` / `(clear-flow id)` | v1 |
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
| `view` | Fn | `(view view-id)` → render-fn (runtime-lookup handle) | v1 | 001, 004 |

`with-frame`'s two shapes (bare keyword vs let-binding) are documented in [002 §with-frame](002-Frames.md#with-frame).

`bound-fn` lives in `re-frame.views-macros`, one of the documented per-namespace exceptions to "all APIs live in `re-frame.core`" (see [Conventions](#conventions)). Users `:require-macros [re-frame.views-macros :refer [bound-fn]]`.

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

## HTTP requests (Spec 014)

`:rf.http/managed` is the canonical, optional HTTP-request fx — `v1 (optional capability)`. CLJS reference ships it on Fetch (browser) and `java.net.http.HttpClient` (JVM). Args, behaviours, decode pipeline, retry semantics, abort surface, failure taxonomy, and reply addressing are normatively defined in [014-HTTPRequests.md](014-HTTPRequests.md); the surface below is the API-level summary.

| API | Kind | Signature / shape | Status | Spec |
|---|---|---|---|---|
| `:rf.http/managed` | fx | `[:rf.http/managed args-map]` — args per [014 §The args map](014-HTTPRequests.md#the-args-map) and `:rf.fx/managed-args` | v1 (optional capability) | 014 |
| `:rf.http/managed-abort` | fx | `[:rf.http/managed-abort request-id]` — abort the in-flight request with the given `:request-id` | v1 (optional capability) | 014 |
| `:rf.http/managed-canned-success` | fx | `[:rf.http/managed-canned-success {:value v}]` — synthesises the canonical success reply (per [014 §Testing](014-HTTPRequests.md#testing)) | v1 (optional capability, dev/test) | 014 |
| `:rf.http/managed-canned-failure` | fx | `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]` — synthesises the canonical failure reply | v1 (optional capability, dev/test) | 014 |
| `with-managed-request-stubs` | M | `(with-managed-request-stubs route-map body+)` — route-map `{[<method> <url>] {:reply ...}}` per [014 §Testing](014-HTTPRequests.md#testing) | v1 (optional capability, dev/test) | 014 |

Public API surface in `re-frame.core` for ports that ship Spec 014. Ports that omit it MUST NOT register `:rf.http/*` for any other purpose (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).

### Reply-payload shape

Every reply lands as `{:rf/reply {:kind :success :value v}}` or `{:rf/reply {:kind :failure :failure {:kind <:rf.http/*> ...}}}`. Default reply addressing dispatches `[<originating-event-id> (assoc original-msg :rf/reply ...)]` back to the same handler; explicit `:on-success` / `:on-failure` targets append the reply payload as the last event-vector arg. Both shapes detailed in [014 §Reply addressing](014-HTTPRequests.md#reply-addressing).

### Failure categories (closed set)

The eight `:kind` values inside a failure reply, all reserved under `:rf.http/*` (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). See [014 §Failure categories](014-HTTPRequests.md#failure-categories-closed-set) for tags-by-kind:

| `:kind` | Meaning |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error pre-HTTP |
| `:rf.http/cors` | CORS preflight rejected (CLJS-only) |
| `:rf.http/timeout` | Per-attempt timeout fired |
| `:rf.http/http-4xx` | Non-2xx 4xx response |
| `:rf.http/http-5xx` | Non-2xx 5xx response |
| `:rf.http/decode-failure` | 2xx response but decode rejected the body |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}` |
| `:rf.http/aborted` | Request aborted via `:request-id` or `:abort-signal` |

### Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`; carries `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms` |
| `:rf.warning/decode-defaulted` | `:warning` | The request relied on `:decode :auto` (default); informational, not an error |

### Schema-reflection metadata

Handlers may declare `:rf.http/decode-schemas [<schema> ...]` in their `reg-event-fx` metadata-map; pair tools and generators read it via `(rf/handler-meta :event id)`. Optional, never enforced — see [014 §Schema reflection](014-HTTPRequests.md#schema-reflection-optional-ergonomic).

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
| `[:rf.http/managed args-map]` | args per [014 §The args map](014-HTTPRequests.md#the-args-map) | v1 (optional capability) | 014 | Framework-provided when the implementation ships Spec 014. CLJS reference: ships on Fetch + JVM `HttpClient`. See also `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`. |
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
| `get-frame-db` | Fn | `(get-frame-db frame-id)` → app-db value (plain map) | v1 | ✓ | 002 |
| `snapshot-of` | Fn | `(snapshot-of path)` / `(snapshot-of path opts)` | v1 | ✓ | 002 |
| `sub-topology` | Fn | `(sub-topology)` → `{sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}` — static dependency graph from `:<-` declarations. Pure data over the registrar; `:inputs` always present (empty for layer-1); the per-entry `:doc` / `:ns` / `:line` / `:file` keys are present when registration carries them. | v1 | ✓ | 002 |
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
| `:spec/validate-at-boundary` (interceptor) | — | Add to a `reg-event-*`'s positional interceptor vector for production-boundary validation | v1 | 010 |

See [010 §Schemas](010-Schemas.md) for `:spec` metadata, validation timing, and dev/prod elision.

---

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics and synchronous listener delivery.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `register-trace-cb!` | Fn | `(register-trace-cb! key callback-fn)` — `callback-fn` receives one trace event per call | v1 (dev-only) | 009 |
| `remove-trace-cb!` | Fn | `(remove-trace-cb! key)` → nil | v1 (dev-only) | 009 |
| `emit-trace!` | Fn | `(emit-trace! op-type operation tags)` → nil | v1 (dev-only) | 009 |
| `re-frame.interop/debug-enabled?` | Var | `^boolean` (alias of `goog.DEBUG` on CLJS; `true` on JVM) | v1 | 009 |
| `re-frame.performance/enabled?` | Var | `^boolean` `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op. See [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation) and [Tool-Pair §Performance API consumption](Tool-Pair.md#performance-api-consumption) | v1 | 009 |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | 009 |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | 009 |
| `(rf/configure :trace-buffer {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | 009 |

### Epoch history (per Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records | v1 (dev-only) | Tool-Pair |
| `restore-epoch` | Fn | `(restore-epoch frame-id epoch-id)` → boolean (true on success) | v1 (dev-only) | Tool-Pair |
| `register-epoch-cb` | Fn | `(register-epoch-cb key callback-fn)` — assembled-epoch listener | v1 (dev-only) | Tool-Pair, 009 |
| `remove-epoch-cb` | Fn | `(remove-epoch-cb key)` | v1 (dev-only) | Tool-Pair, 009 |
| `(rf/configure :epoch-history {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | Tool-Pair |

Trace events emitted by epoch-history machinery:

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:epoch-id`, `:event-id` |
| `:rf.epoch/restored` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:epoch-id`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:epoch-id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:frame`, `:epoch-id` |

### DOM source-coord annotations (mandatory; rf2-z7f7 / rf2-z9n1)

Per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) and [Tool-Pair §Source-mapping](Tool-Pair.md), every substrate adapter whose host has a DOM-attribute concept MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions (Fragments, non-DOM roots) are documented in Spec 006 §Source-coord annotation. Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the same contract per [Spec 011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr).

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
| `:rf.fx/managed-args` | Args of `:rf.http/managed` fx (request envelope, decode, accept, retry, timeout-ms, on-success/on-failure, request-id, abort-signal) | 014 |
| `:rf.fx/managed-abort-args` | Args of `:rf.http/managed-abort` fx (request-id) | 014 |
| `:rf.http/reply` | Reply-payload envelope `{:kind :success :value v}` / `{:kind :failure :failure {:kind <:rf.http/*> ...}}` lands under `:rf/reply` | 014 |
| `:rf/route-rank` | Structural-rank tuple for route-precedence sorting | 012 |
| `:rf/pending-navigation` | Pending-navigation slot when `:can-leave` guard rejects | 012 |

Schemas are **open** by default (consumers tolerate unknown keys; producers grow shapes additively); `:closed true` is opt-in at boundary-validation sites and on the effect-map.

---

## Testing

`re-frame.test-support` ships the test-flavoured helpers below alongside re-exports of `make-frame`/`destroy-frame`/`with-frame`/`dispatch-sync` for one-stop import. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | Spec | Notes |
|---|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` | v1 | 008 | `opts`: `:after-each (fn [db ev] ...)`, `:frame`. Returns final `app-db`. Lives in `re-frame.test-support`. |
| `assert-state` | Fn | `(assert-state expected-db)` / `(assert-state path expected-val)` / either form `+ {:frame ...}` opts | v1 | 008 | Mismatch fires `clojure.test/is`-style failure via `do-report`. Lives in `re-frame.test-support`. |
| `run-test-sync` | M | `(run-test-sync body...)` | v1 | 008 | v1 compatibility shim. Snapshots/restores the registrar around `body`; v2's `dispatch-sync` is already synchronous so synchronicity is not added. Lives in `re-frame.test-support`. |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | 008 | Pure sub computation against an `app-db` value. Lives in `re-frame.core`. |
| `snapshot-registrar` / `restore-registrar!` / `with-fresh-registrar` / `reset-runtime-fixture` | Fn | per docstring | v1 | 008 | Fixture machinery. Lives in `re-frame.test-support`. |

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

---

## Lifecycle / utility

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `make-restore-fn` | Fn | `(make-restore-fn)` / `(make-restore-fn frame-id)` → restore-fn | v1 (preserved + extended) | 002 |
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
| `:sub-cache` | `{:grace-period-ms N}` — non-negative integer; 0 selects synchronous disposal | `{:grace-period-ms 50}` | v1 | 006 |
| `:dom-source-annotations?` | boolean — historical opt-in flag; superseded by mandatory injection per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1). Setting this false has no effect — the gate is `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via DCE | n/a (always-on under dev) | v1 (dev-only) | 006, Tool-Pair |
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

## Machines

Split between the v1 machine-as-event-handler foundation and the post-v1 `re-frame.machines` scaffolding library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition). The machine *is* the event handler: a machine is registered as one `reg-event-fx` whose body comes from `create-machine-handler`.

| API | M/Fn | Signature | Status | Spec |
|---|---|---|---|---|
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` — registers a machine as an event handler. Walks the literal spec form at expansion time and stamps per-element source coords under `:rf.machine/source-coords` (rf2-8bp3). | v1 | 005 |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` — plain-fn surface beneath the macro. No source-coord walking. | v1 | 005 |
| `create-machine-handler` | Fn | `(create-machine-handler spec)` → event-handler fn | v1 | 005 |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | 005 |
| `sub-machine` | Fn | `(sub-machine machine-id)` → reaction over snapshot | v1 | 005 |
| `machines` | Fn | `(machines)` → seq of registered machine-ids | v1 | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration metadata; carries `:rf.machine/source-coords` index when registered via the macro | v1 | 005 |
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

---

## Removed / not shipped

| API | What to do | Reference |
|---|---|---|
| `dispatch-with` (master) | Use `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | Use `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | Use `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | Use `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` (proposed earlier) | Renamed to `bound-dispatcher` | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Performance-API instrumentation is gated on the compile-time `re-frame.performance/enabled?` `goog-define`, not a runtime toggle (see [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation)) | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | Use `register-trace-cb!` / `remove-trace-cb!` | 009 |
| `register-trace-cb` / `remove-trace-cb` (no-bang, proposed earlier) | Renamed to `register-trace-cb!` / `remove-trace-cb!` (bang form matches the side-effecting nature of listener registration) | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Use the Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id | 004 |
| `h` macro (proposed earlier) | Removed (rf2-n4um). Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]` | 004 |
| `reg-global-interceptor` | Use `reg-frame :interceptors` (frame-level is the canonical "global within this frame"). For cross-frame observation use `register-trace-cb!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | Use `reg-sub` (app-db reads), Pattern-AsyncEffect (non-app-db sources), state machines (lifecycle), or the [006](006-ReactiveSubstrate.md) adapter contract (bridging external reactivity). | MIGRATION M-18 |
| `re-frame.alpha/reg` | Per-kind macros: `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — deferred ref-counting with grace-period, per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). For specific edge cases file a follow-up bead. | MIGRATION M-23 |

---

## Cross-references

- [000-Vision.md](000-Vision.md) — principles and design decisions
- [002-Frames.md](002-Frames.md) — frames, dispatch envelope, drain semantics, overrides, machine foundations
- [004-Views.md](004-Views.md) — view registration, hiccup forms
- [005-StateMachines.md](005-StateMachines.md) — machine library design (post-v1)
- [007-Stories.md](007-Stories.md) — story/variant/workspace library design (post-v1)
- [008-Testing.md](008-Testing.md) — testing API and patterns
- [009-Instrumentation.md](009-Instrumentation.md) — trace event stream, listeners, error contract
- [010-Schemas.md](010-Schemas.md) — Malli schemas
- [014-HTTPRequests.md](014-HTTPRequests.md) — `:rf.http/managed` request fx (optional capability)
- [MIGRATION.md](MIGRATION.md) — AI-driven migration spec
