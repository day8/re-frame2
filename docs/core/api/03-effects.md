# 03 — Effects and interceptors

The effect map is what an event handler returns. The interceptor chain is what runs before and after the handler. Together they're the load-bearing trick that makes re-frame2 a *pattern* — handlers stay pure (they return descriptions of effects, not the effects themselves), and the runtime actions those descriptions against the real world at exactly one point. That separation is why the trace bus, time-travel, and effect-overrides all work; if handlers fired effects directly, none of those would compose.

This chapter covers what an `:fx` map can carry (`:db`, `:fx`, the standard fx-ids), what an interceptor is and the public authoring surface (`reg-interceptor`), the one framework-standard interceptor reference (`[:rf.interceptor/path <path-vector>]`), the pre-built `validate-at-boundary-interceptor`, and the override surfaces that let tests and tools swap fx behaviour at runtime (`with-fx-overrides`, the per-call `:fx-overrides` opt). Coeffects are *not* delivered by an interceptor in v2 — a handler declares the world facts it needs with `:rf.cofx/requires` registration metadata and the runtime supplies them (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx) and [Guide — Effects and coeffects](../concepts/effects-and-coeffects.md)). v1's `inject-cofx` interceptor is removed; so are the v1 `path` / `unwrap` value constructors (see the [migration reference](../../../migration/from-re-frame-v1/README.md)).

The surfaces in this chapter live in `re-frame.core`:

```clojure
(:require [re-frame.core :as rf])
```

## The effect map: closed shape

Closed: **`:db` + `:fx` only**. That's the entire effect-map vocabulary in v2.

| Key | Notes |
|---|---|
| `:db` | The new `app-db` value. Replaces the current value in the cascade's commit phase. |
| `:fx` | A vector of `[fx-id args]` pairs. Each is run by the runtime's fx walker against the registered `reg-fx` handler. |

If you remember v1's `:dispatch` / `:dispatch-later` / `:dispatch-n` at the top level of the effect map, those don't exist any more — they're inside `:fx`. The migration is mechanical; see [MIGRATION §M-8](../../../migration/from-re-frame-v1/README.md). The closed shape is what lets the conformance harness validate handler outputs across implementations.

The closed `:db` + `:fx` shape above is the entire `:rf/effect-map` schema — see the [effect map](../glossary.md#effect-map) glossary entry for the two reserved keys in plain language.

## Standard `:fx` entries

Anything in `:fx` is a `[fx-id args]` pair. The runtime looks up `fx-id` in the `:fx` registrar and runs the registered handler against `args`. User code registers its own fx-ids via `reg-fx`; a small set of fx-ids is framework-reserved.

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:dispatch event-vec]` | event vector | v1 | "Schedule this event on the same queue." Async — runs after the current cascade completes. |
| `[:dispatch-later {:ms ms :event event-vec}]` | options map | v1 | "Schedule this event after N ms." |
| `[:rf.http/managed args-map]` | per `:rf.fx/managed-args` | v1 (optional) | The canonical managed-HTTP fx. See [07 — HTTP](../../resources/http-api.md). |
| `[:rf.nav/push-url url-string]` | URL string | v1 | Navigate. See [06 — Routing](../../routing/api.md). |
| `[:raise event-vec]` | event vector | v1 | **Machine-only.** Inside a machine action's `:fx`, routes the event back into the same machine atomically and pre-commit. Unbound outside machine actions. |
| `[:rf.machine/spawn spawn-spec]` | per `:rf.fx/spawn-args` | v1 | Spawn a dynamic machine actor — see [04 — Machines](../../machines/api.md). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | Destroy a dynamic machine actor — see [04 — Machines](../../machines/api.md). |
| `[:rf.fx/reg-flow flow-map]` | flow map | v1 | Register a flow at runtime via `:fx`. See [05 — Flows](05-flows.md). |
| `[:rf.fx/clear-flow id]` | id | v1 | Clear a registered flow at runtime via `:fx`. |
| `[:http args]` | impl-specific | — | User-registered via `reg-fx`. The legacy un-managed shape; new code uses `:rf.http/managed`. |

SSR-side server-only fx (`:rf.server/set-status`, `:rf.server/set-header`, `:rf.server/redirect`, etc.) are rowed in [09 — SSR](../../ssr/api.md). Their `:platforms` metadata gates them off the client.

## Standard interceptors

The interceptor chain wraps the handler. Every interceptor has a `:before` (runs before the handler) and / or `:after` (runs after the handler). The runtime threads a context map — the **ctx** — through the chain, and the chain composes deterministically. Interceptors are how you add cross-cutting behaviour (validation, focus-on-path, logging) without writing it into every handler.

The public interceptor-authoring surface is **`reg-interceptor`**, and event / frame `:interceptors` chains carry interceptor **references** (a bare keyword id, or `[id arg]` for a parameterised factory) — never inline interceptor values (see [EP-0022](../../EP/EP-0022-registered-interceptors.md)). The framework ships exactly **one** standard interceptor — `[:rf.interceptor/path <path-vector>]` — referenced, not constructed. There is **no** public `path` value constructor and **no** standard `unwrap`: a stale `rf/path` call raises the always-loud `:rf.error/path-removed`, and a stale `rf/unwrap-interceptor` reference raises `:rf.error/unwrap-removed`; the replacements are the `:rf.interceptor/path` reference and handler-payload destructuring (the map-payload form). The interceptors `debug`, `trim-v`, `on-changes`, `enrich`, and `after` do not exist; neither does `inject-cofx` — coeffect delivery is declared with `:rf.cofx/requires`, not injected by an interceptor (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx)).

### `[:rf.interceptor/path <path-vector>]`

- **Kind**: interceptor **reference** (the one standard interceptor; the canonical `:factory` consumer)
- **Form**:
  ```clojure
  {:interceptors [[:rf.interceptor/path [:cart :items]]]}
  ```
- **Description**: Focus the handler on an `app-db` sub-slice. `:before` stages the focused slice as `:db` — `(get-in db path)`; `:after` widens the returned slice back into full app-db. The handler sees and returns a sub-tree, not the full db. Preserves the frame-commit `identical?` no-op (an unchanged focused slice widens back to the original app-db object, not an `assoc-in` allocation). A non-vector / malformed path arg raises `:rf.error/path-interceptor-bad-path`.

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
- Pre-built schema-validation interceptor value for the production boundary — full contract in [08 — Schemas](08-schemas.md).

### The `:rf.interceptor/path` reference: focus on a slice

```clojure
(rf/reg-event :cart/add-item
  {:interceptors [[:rf.interceptor/path [:cart :items]]]}   ;; a REFERENCE, not a value
  (fn [{:keys [db]} {:keys [item]}]
    {:db (conj db item)}))                     ;; the handler sees and returns the slice (as :db)
```

The chain entry is the **reference** `[:rf.interceptor/path [:cart :items]]`, not a constructed value — there is no public `path` fn (it's removed; a stale `rf/path` call raises `:rf.error/path-removed`). The `:before` stages `(:db cofx)` as `(get-in db [:cart :items])`. The handler returns the new slice. The `:after` widens it back with `(assoc-in db [:cart :items] result)`, preserving the commit `identical?` no-op when the slice is unchanged. A handler that focuses on a slice and also needs auxiliary world facts declares those facts with `:rf.cofx/requires` — the slice arrives via the interceptor reference, the facts via the coeffect declaration (see [01 — Core §`reg-cofx`](01-core.md#reg-cofx)).

### Payload destructuring: the replacement for v1 `unwrap`

There is no standard `unwrap` interceptor in v2 (the v1 value is removed; a stale `rf/unwrap-interceptor` reference raises `:rf.error/unwrap-removed`). The canonical way to receive a payload map directly is the **map-payload call shape** — destructure the handler's payload argument:

```clojure
(rf/reg-event :foo/update
  (fn [cofx {:keys [id new-value]}]           ;; second arg IS the payload map
    {:db (assoc-in (:db cofx) [:foo id] new-value)}))

;; You wrote: (rf/dispatch [:foo/update {:id 1 :new-value "x"}])
```

The handler's second argument is the payload map directly — no interceptor required (per [MIGRATION §M-19](../../../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)). For genuine chain-wide event reshaping, register a project-specific interceptor with `reg-interceptor` and reference it by id.

### Building custom interceptors with `reg-interceptor`

```clojure
(rf/reg-interceptor :log-on-error
  {:after (fn [ctx]
            (when-let [err (:rf.error/last-event ctx)]
              (js/console.error err))
            ctx)})

(rf/reg-event ::save-cart
  {:interceptors [:log-on-error]}                ;; reference by id
  (fn [cofx _]
    {:db (assoc (:db cofx) :cart/saving? true)}))
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

> **No façade context accessors.** `get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` are not exported. Work the context map directly as shown above.

## Override surfaces

The runtime supports three ways to swap fx behaviour without touching the handler. They differ in scope: per-frame (lexical to the frame), lexical (around a body of code), and per-call (on a single dispatch).

### `with-fx-overrides`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-fx-overrides {fx-id -> override, …} body+)
  ```
- **Description**: "For the duration of this body, every `dispatch` / `dispatch-sync` merges this fx-overrides map into its envelope." Lexical scope; composes with `with-frame`.
- **Example**:
  ```clojure
  ;; Swap the real managed-HTTP fx for a canned-failure stub for the test body —
  ;; every dispatch inside inherits the override; it unwinds when the body exits.
  (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrong"}])
    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrong"}]))
  ```

The three scopes compose with a clear precedence:

1. **Per-call** — `(rf/dispatch event {:fx-overrides {...}})` wins.
2. **Lexical** — `with-fx-overrides` wraps the body.
3. **Per-frame** — `(rf/reg-frame :todo {:fx-overrides {...}})` is the baseline.

Most tests reach for `with-fx-overrides` because it scopes the swap to the test body without polluting the frame. Pair tools and Story variants reach for per-call overrides because the swap is specific to a single recorded dispatch.

### `:fx-overrides` asymmetry

At the pattern level (`(rf/dispatch event {:fx-overrides {:my/fx :other-fx-id}})`) the override value is an **id** — the registry name of another fx handler. The CLJS reference implementation **also** accepts a **fn** value (`{:my/fx (fn [args] ...)}`) for ergonomic test wiring. The asymmetry is deliberate: ports that don't ship fn-valued overrides remain pattern-conformant.

## See also

- [01 — Core](01-core.md) — `reg-event`, `reg-fx`, `reg-cofx`, `dispatch` rowed in the registration and dispatch sections.
- [10 — Testing](10-testing.md) — `with-fx-overrides` and the testing fixtures that use it.
- [09 — SSR](../../ssr/api.md) — `:platforms` metadata on `reg-fx` for client vs server gating.
