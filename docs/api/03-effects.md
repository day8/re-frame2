# 03 — Effects and interceptors

The effect map is what an event handler returns. The interceptor chain is what runs before and after the handler. Together they're the load-bearing trick that makes re-frame2 a *pattern* — handlers stay pure (they return descriptions of effects, not the effects themselves), and the runtime actions those descriptions against the real world at exactly one point. That separation is why the trace bus, time-travel, and effect-overrides all work; if handlers fired effects directly, none of those would compose.

This chapter covers what an `:fx` map can carry (`:db`, `:fx`, the standard fx-ids), what an interceptor is and the surface for building one (`->interceptor`), the ergonomic interceptors v2 ships (`path`, `unwrap`, the pre-built `validate-at-boundary-interceptor`), and the override surfaces that let tests and tools swap fx behaviour at runtime (`with-fx-overrides`, the per-call `:fx-overrides` opt). Coeffects are *not* delivered by an interceptor in v2 — a handler declares the world facts it needs with `:rf.cofx/requires` registration metadata and the runtime supplies them (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx) and [Guide — Effects and coeffects](../guide/concepts/effects-and-coeffects.md)). v1's `inject-cofx` interceptor is removed; see [15 — Removed](15-removed.md).

## The effect map: closed shape

Closed: **`:db` + `:fx` only**. That's the entire effect-map vocabulary in v2.

| Key | Notes |
|---|---|
| `:db` | The new `app-db` value. Replaces the current value in the cascade's commit phase. |
| `:fx` | A vector of `[fx-id args]` pairs. Each is run by the runtime's fx walker against the registered `reg-fx` handler. |

If you remember v1's `:dispatch` / `:dispatch-later` / `:dispatch-n` at the top level of the effect map, those don't exist any more — they're inside `:fx`. The migration is mechanical; see [MIGRATION §M-8](../../migration/from-re-frame-v1/README.md). The closed shape is what lets the conformance harness validate handler outputs across implementations.

Full schema: [Spec-Schemas §`:rf/effect-map`](../../spec/Spec-Schemas.md#rfeffect-map).

## Standard `:fx` entries

Anything in `:fx` is a `[fx-id args]` pair. The runtime looks up `fx-id` in the `:fx` registrar and runs the registered handler against `args`. User code registers its own fx-ids via `reg-fx`; a small set of fx-ids is framework-reserved.

| `[fx-id args]` | Args | Status | Spec | Intuition |
|---|---|---|---|---|
| `[:dispatch event-vec]` | event vector | v1 | 002 | "Schedule this event on the same queue." Async — runs after the current cascade completes. |
| `[:dispatch-later {:ms ms :event event-vec}]` | options map | v1 | 002 | "Schedule this event after N ms." |
| `[:rf.http/managed args-map]` | per `:rf.fx/managed-args` | v1 (optional) | 014 | The canonical managed-HTTP fx. See [07 — HTTP](07-http.md). |
| `[:rf.nav/push-url url-string]` | URL string | v1 | 012 | Navigate. See [06 — Routing](06-routing.md). |
| `[:raise event-vec]` | event vector | v1 | 005 | **Machine-only.** Inside a machine action's `:fx`, routes the event back into the same machine atomically and pre-commit. Unbound outside machine actions. |
| `[:rf.machine/spawn spawn-spec]` | per `:rf.fx/spawn-args` | v1 | 005 | Spawn a dynamic actor instance whose snapshot lives at `[:rf.runtime/machines :snapshots <gensym'd-id>]` (in runtime-db). See [04 — Machines](04-machines.md). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | 005 | Symmetric counterpart to `:rf.machine/spawn`. Runs the actor's `:exit` action, dissociates `[:rf.runtime/machines :snapshots <id>]` (in runtime-db), clears its event-handler registration. |
| `[:rf.fx/reg-flow flow-map]` | flow map | v1 | 013 | Register a flow at runtime via `:fx`. See [05 — Flows](05-flows.md). |
| `[:rf.fx/clear-flow id]` | id | v1 | 013 | Clear a registered flow at runtime via `:fx`. |
| `[:http args]` | impl-specific | — | — | User-registered via `reg-fx`. The legacy un-managed shape; new code uses `:rf.http/managed`. |

SSR-side server-only fx (`:rf.server/set-status`, `:rf.server/set-header`, `:rf.server/redirect`, etc.) are rowed in [09 — SSR](09-ssr.md). Their `:platforms` metadata gates them off the client.

## Standard interceptors

The interceptor chain wraps the handler. Every interceptor has a `:before` (runs before the handler) and / or `:after` (runs after the handler). The runtime threads a context map — the **ctx** — through the chain, and the chain composes deterministically. Interceptors are how you add cross-cutting behaviour (validation, focus-on-path, logging) without writing it into every handler.

The v2 standard-interceptor surface is **two specific helpers** (`path`, `unwrap`) plus the pre-built `validate-at-boundary-interceptor` and the `->interceptor` primitive. The principle is: keep helpers that do specific, non-trivial work; drop helpers that are just `(->interceptor :before f)` with no other logic. Five v1 interceptors are removed (`debug`, `trim-v`, `on-changes`, `enrich`, `after`); so is `inject-cofx` — coeffect delivery is now declared with `:rf.cofx/requires`, not injected by an interceptor (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx) and [15 — Removed](15-removed.md)).

### `path`

- **Kind**: function
- **Signature**:
  ```clojure
  (path & path)
  ```
- **Description**: Focus the handler on an `app-db` sub-slice. `:before` rewrites the `:db` cofx to `(get-in db path)`; `:after` splices the result back into the parent. The handler sees and returns a sub-tree, not the full db.

### `unwrap`

- **Kind**: Var (interceptor value)
- **Signature**:
  ```clojure
  unwrap
  ```
- **Description**: Assert `[id payload-map]` event shape; replace the `:event` coeffect with just the payload map; restore on `:after`. Sugar over the canonical map-payload form (per [MIGRATION §M-19](../../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)).

### `reg-interceptor`

- **Kind**: macro (with `reg-interceptor*` as the programmatic `*`-twin)
- **Signature**:
  ```clojure
  (reg-interceptor id {:keys [before after]})
  ```
- **Description**: The public custom-interceptor authoring form. Register a named interceptor with `:before` and / or `:after`, then **reference it by id** from a `reg-event` / `reg-frame` `:interceptors` vector. **Use this for any work not covered by the standard interceptors above** — analytics, logging, validation, ad-hoc context manipulation. The interceptor is named, addressable, and queryable like any other artefact. (`->interceptor` is the framework-internal lowering constructor that turns a descriptor into an executable chain entry; it is not the application-authoring form and must not appear directly in a public chain.)

### `validate-at-boundary-interceptor`

- **Kind**: Var (interceptor value)
- **Signature**:
  ```clojure
  validate-at-boundary-interceptor
  ```
- **Description**: A **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`). Add it to a `reg-event` metadata map's `:interceptors` vector for production-boundary schema validation. **Do not call it as a fn** — it has no fn arity; invoking `(rf/validate-at-boundary-interceptor ...)` raises `ArityException`.

### The `path` interceptor: focus on a slice

```clojure
(rf/reg-event :cart/add-item
  {:interceptors [(rf/path [:cart :items])]}
  (fn [{:keys [db]} {:keys [item]}]
    {:db (conj db item)}))                     ;; the handler sees and returns the slice (as :db)
```

The `:before` rewrites `(:db cofx)` to `(get-in db [:cart :items])`. The handler returns the new slice. The `:after` splices it back with `(assoc-in db [:cart :items] result)`. A handler that focuses on a slice with `path` and also needs auxiliary world facts declares those facts with `:rf.cofx/requires` — the slice arrives via the interceptor, the facts via the coeffect declaration (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx)).

### The `unwrap` interceptor

```clojure
(rf/reg-event :foo/update
  {:interceptors [rf/unwrap]}
  (fn [cofx {:keys [id new-value]}]           ;; :event coeffect is the payload map
    ...))
```

You wrote `(rf/dispatch [:foo/update {:id 1 :new-value "x"}])`; the handler receives the payload map directly under `:event` instead of the full vector. Sugar — it's not load-bearing — but it composes cleanly with the canonical map-payload form.

### Building custom interceptors with `reg-interceptor`

```clojure
(rf/reg-interceptor :log-on-error
  {:after (fn [ctx]
            (when-let [err (:rf.error/last-event ctx)]
              (js/console.error err))
            ctx)})

(rf/reg-event ::save-cart
  {:interceptors [:log-on-error]}                ;; reference by id
  (fn [cofx _] ...))
```

Register the interceptor once with `reg-interceptor`, then reference it **by id** from the `:interceptors` vector. The `:before` / `:after` fns receive and return the context map; `{:before :after}` is the entire behaviour vocabulary, and every standard interceptor is just a registered interceptor with specific behaviour baked in.

## Context plumbing

The interceptor context — the ctx — is the value threaded through the chain. It carries `:coeffects` (everything available to the handler before it runs), `:effects` (everything the handler produced), and the queue / stack of remaining interceptors. Most app code never reaches into ctx directly. The rare interceptor body that does works the context map with ordinary Clojure: read coeffects with `(get-in ctx [:coeffects k])`, read effects with `(get-in ctx [:effects k])`, and write either slot with `assoc-in` before returning the updated ctx.

```clojure
(rf/reg-interceptor :inject-now
  {:before (fn [ctx]
             (assoc-in ctx [:coeffects :now] (js/Date.now)))})  ;; handler reads (:now coeffects)

(rf/reg-interceptor :tag-db
  {:after (fn [ctx]
            (let [db (get-in ctx [:effects :db])]               ;; inspect what the handler emitted
              (cond-> ctx
                db (assoc-in [:effects :db] (vary-meta db assoc :tagged? true)))))})
```

> **Removed:** the façade context accessors `get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` are no longer exported — post-EP-0017/EP-0022 they lost their audience. Work the context map directly as shown above.

## Override surfaces

The runtime supports three ways to swap fx behaviour without touching the handler. They differ in scope: per-frame (lexical to the frame), lexical (around a body of code), and per-call (on a single dispatch).

### `with-fx-overrides`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-fx-overrides {fx-id -> override, …} body+)
  ```
- **Description**: "For the duration of this body, every `dispatch` / `dispatch-sync` merges this fx-overrides map into its envelope." Lexical scope; composes with `with-frame`. Renamed from v1's `with-overrides` per [MIGRATION §M-50](../../migration/from-re-frame-v1/README.md#m-50-with-overrides-macro-renamed-to-with-fx-overrides).

The three scopes compose with a clear precedence:

1. **Per-call** — `(rf/dispatch event {:fx-overrides {...}})` wins.
2. **Lexical** — `with-fx-overrides` wraps the body.
3. **Per-frame** — `(rf/reg-frame :todo {:fx-overrides {...}})` is the baseline.

Most tests reach for `with-fx-overrides` because it scopes the swap to the test body without polluting the frame. Pair tools and Story variants reach for per-call overrides because the swap is specific to a single recorded dispatch.

### `:fx-overrides` asymmetry

At the pattern level (`(rf/dispatch event {:fx-overrides {:my/fx :other-fx-id}})`) the override value is an **id** — the registry name of another fx handler. The CLJS reference implementation **also** accepts a **fn** value (`{:my/fx (fn [args] ...)}`) for ergonomic test wiring. The asymmetry is documented: ports that don't ship fn-valued overrides remain pattern-conformant. See [002 §`:fx-overrides`](../../spec/002-Frames.md#fx-overrides--replace-fx-handlers).

## See also

- [01 — Core](01-core.md) — `reg-event`, `reg-fx`, `reg-cofx`, `dispatch` rowed in the registration and dispatch sections.
- [10 — Testing](10-testing.md) — `with-fx-overrides` and the testing fixtures that use it.
- [09 — SSR](09-ssr.md) — `:platforms` metadata on `reg-fx` for client vs server gating.
