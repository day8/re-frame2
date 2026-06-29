# 01 — Core

The Core chapter is what you `:require` from `re-frame.core` to make an app exist at all. Five clusters live in here, and they're the surfaces you'll see in every app you ever write: **registration** (`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`), **dispatch and subscribe** (the two verbs that drive the cascade), **frames** (the scoping primitive — `reg-frame` / `make-frame`), **runtime configuration** (`configure`), and **clearing** (the inverse of registration).

If you read only one chapter of this reference, this is the one to read. Everything in the other chapters builds on these five clusters.

The surfaces in this chapter live in `re-frame.core`:

```clojure
(:require [re-frame.core :as rf])
```

## Registration

This is the surface every re-frame2 app touches. You're answering "what events can my app handle, what data can it subscribe to, what side effects can it action, what state can it inject as coeffects?" Every entry is a registration of a named handler into the frame's registrar.

**Return value.** Every `reg-*` returns its **primary id** — the keyword (or path, for `reg-app-schema`) you registered with. This lets you write `(let [sub-id (rf/reg-sub ::foo ...)] ...)` to thread the id through your code without retyping it. The convention is uniform across the surface.

### `reg-event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event id ?metadata handler)
  ```
- **Description**: The **one** public event-registration form. The handler is two-arg `(fn [coeffects event-vec] effect-map)`: a coeffects map in, a **closed** effects map `{:db ... :fx [...]}` out (or `nil` for a no-op). The db write is an explicit `:db` effect — there is **no** db-only return shape. Use it for both the 80% of handlers that just update state and the richer handlers that dispatch follow-ups, fire HTTP, navigate, or read coeffects.
- **Metadata-map — the extended form**: the optional middle slot is a metadata-map carrying reflection keys (`:doc`, `:schema`, `:tags`, …) **and** a reserved `:interceptors` vector — one superset shape for everything a registration declares:
  ```clojure
  (rf/reg-event :cart/add
    {:doc "Add an item." :interceptors [undoable]}
    (fn [{:keys [db]} [_ item]] {:db (update db :items conj item)}))
  ```
  > **Migration note.** The historical bare/positional interceptor vector middle slot — `(reg-event :id [undoable] handler)` — has been removed. Put event interceptor chains in the metadata map: `(reg-event :id {:interceptors [undoable]} handler)`.
- **Full interceptor-context work**: there is no separate registrar for raw-context handlers. When you need to read or rewrite the interceptor context itself, register a named interceptor with `(rf/reg-interceptor :my/audit {:before ... :after ...})` and reference it **by id** from the `:interceptors` vector above (`{:interceptors [:my/audit]}`). The `:before` / `:after` fns receive and return the context map directly. (`->interceptor` is the framework-internal lowering constructor, not the application-authoring form.)
- **Example** — a pure state update and an effectful handler:
  ```clojure
  ;; State-only: the db write is an explicit :db effect.
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))

  ;; Effectful: an explicit :db write plus an :fx vector.
  (rf/reg-event :counter/load
    (fn [{:keys [db]} _event]
      {:db (assoc db :status :loading)
       :fx [[:rf.http/managed {:request    {:method :get :url "/api/count"}
                               :on-success [:counter/loaded]
                               :on-failure [:counter/load-failed]}]]}))
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter), [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/core/managed_http_counter)

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata input-fn? computation-fn)
  ```
- **Description**: "Computed view over `app-db` and other subs." `reg-sub` supports **three input-production modes** — every subscription has an *input query-vector producer*: a layer-1 app-db reader has no producer, `:<-` is the literal producer, and a parametric `input-fn` is the query-parametric producer. The optional first fn is a v2 **`input-fn`** — a *pure* function from the outer `query-v` to a **vector of query vectors**; it is **not** a v1 reaction-returning signal fn (it must not call `subscribe`, deref `app-db`, dispatch, or perform IO, and it must not return live reactions). The runtime resolves each returned query vector in the *same frame* as the outer subscription. This is the only sub-registration form in v2 — `reg-sub-raw` is gone (see the [migration reference](../../../migration/from-re-frame-v1/README.md) for the replacement guidance). The full input grammar, the three input-production modes, and the error ids live in the [Subscriptions concept guide](../concepts/subscriptions.md). The teaching walkthrough is [Guide ch.05 §Three ways a sub names its inputs](../concepts/subscriptions.md).

| Mode | Form | Where the inputs come from |
|---|---|---|
| App-db reader | `(reg-sub id computation-fn)` | No upstream subs; the computation fn receives `app-db` and the outer `query-v` (layer 1). |
| Static inputs | `(reg-sub id :<- q1 :<- q2 computation-fn)` | A literal, fixed list of query vectors known at registration (`:<-` sugar). |
| Parametric inputs | `(reg-sub id input-fn computation-fn)` | Computed from the outer `query-v` by an `input-fn` when a concrete cache entry is first materialized. |

- **Examples**:
  ```clojure
  ;; Layer-1 — read straight off app-db (no producer)
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))

  ;; Layer-2 — compose an upstream sub via the :<- sugar (static inputs)
  (rf/reg-sub :counter/doubled
    :<- [:counter/value]
    (fn [value _query] (* 2 value)))

  ;; Parametric — the input-fn returns a vector of query vectors,
  ;; computed from the outer query-v; the runtime resolves each in the
  ;; outer sub's frame and hands the resolved values to the computation-fn.
  (rf/reg-sub :article/page
    (fn input-fn [[_ article-id]]
      [[:article/by-id article-id]
       [:comments/for-article article-id]
       [:viewer/current]])
    (fn computation-fn [[article comments viewer] [_ article-id]]
      {:id article-id :article article :comments comments
       :can-edit? (:edit? viewer)}))
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter)

### `reg-fx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-fx id ?metadata handler)
  ```
- **Description**: "Define a named side effect." The handler runs against the args the effect map carries; unary `(fn [args] ...)` is the canonical shape, binary `(fn [args ctx] ...)` is available when you need the originating context.
- **Example**:
  ```clojure
  (rf/reg-fx :app/scroll-to-top
    (fn [_args] (js/window.scrollTo 0 0)))
  ```
- **In the wild**: [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/core/managed_http_counter)

### `reg-cofx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-cofx id ?metadata supplier)
  ```
- **Description**: "Register a named supplier for a world fact a handler can ask for." The supplier is a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the call site — *not* a context-mutating fn. The runtime calls it and puts the result into the coeffects map under the cofx's id. A handler opts in with `:rf.cofx/requires` registration metadata; v2 has **no `inject-cofx` interceptor**. The middle slot carries the fact's grade: `{:recordable? true}` for a replayable fact, `{:recordable? true :provided? true}` for a recordable fact stamped by an owner boundary (no generator), a bare registration for an ambient (unrecorded) read. Reading a sub from a handler is done the same way — wrap `subscribe-once` in a cofx and declare it (see [Guide — Reading a subscription from a handler](../concepts/effects-and-coeffects.md)). Full model: [Guide — Effects and coeffects](../concepts/effects-and-coeffects.md).
- **Example**:
  ```clojure
  ;; A value-returning supplier — quarantines an impure read behind a named id.
  (rf/reg-cofx :ui/local-theme
    {:doc "Ambient localStorage read for the display theme."}
    (fn [storage-key]
      (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

  ;; The handler declares the fact; the runtime supplies it flat in the coeffects map.
  (rf/reg-event :prefs/apply-theme
    {:rf.cofx/requires [[:ui/local-theme "ui-theme"]]}
    (fn [{:keys [db ui/local-theme]} _]
      {:db (assoc db :ui/theme (or local-theme "system"))}))
  ```
- **In the wild**: [todomvc](https://github.com/day8/re-frame2/tree/main/examples/core/todomvc)

### `reg-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-frame id metadata)
  ```
- **Description**: Atomic create-and-register. A frame is the scoping unit — one `app-db`, one event queue, one cascade — and `reg-frame` mints it with metadata you can later read via `frame-meta`. The frame owns the `:observability` sink policy. Durable `app-db` data classification is **not** a frame annotation: a `reg-frame` config carrying `:sensitive` / `:large` **fails loud at registration**. Classify durable `app-db` paths by returning the four commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) from a `reg-event` alongside `:db`, wired to run at frame creation via `:initial-events`. See [07 — HTTP §Privacy](../../resources/http-api.md), [08 — Schemas §Data classification](08-schemas.md#data-classification) and [Guide ch.23 — Privacy and large things](../how-to/keep-secrets-out-of-traces.md).
- **Example**:
  ```clojure
  ;; User-defined fxs sit under a user-feature prefix per
  ;; spec/Conventions.md §Reserved namespaces — never under `:rf.<feature>/…`,
  ;; which is reserved for framework-owned surfaces.
  ;;
  ;; Durable app-db classification rides a commit-plane effect (EP-0025):
  ;; a reg-event returns :sensitive / :large alongside :db, run at frame
  ;; creation via :initial-events — NOT a frame annotation.
  (rf/reg-event :app/init
    (fn [{:keys [db]} _]
      {:db        (assoc db :auth {})
       :sensitive [[:auth :token]]}))   ;; classify before any value lands

  (rf/reg-frame :app/main
    {:doc            "App demo frame."
     :initial-events [[:app/init]]      ;; classifies [:auth :token] at creation
     :fx-overrides   {:rf.http/managed :auth.login.demo/managed-stub}})
  ```
- **In the wild**: [boot](https://github.com/day8/re-frame2/tree/main/examples/patterns/boot)

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) ; → live frame value
  ```
- **Description**: The **single public constructor** for a live frame. It accepts image-selection options *and* frame-configuration options in **one** call and **returns the live frame value** — one frame value backed by one registry. Useful for per-mount lifecycles — devcards, modal stacks, multiple live instances of a widget, dynamic tabs, tests, and the SSR per-request frame pattern. The `reg-frame` named path is the front-porch surface; `make-frame` is the advanced per-instance one. Opts: the image-selection keys `:images` (always a vector — the assembled registration set the frame resolves against), `:id` (optional — registers the frame in the one process-local live-frame registry; a duplicate live id is **idempotent replacement** that preserves durable state on re-mount, not a blanket fail-loud — irreconcilable conflicts still fail loud), `:capabilities`, `:adapter` — **and**, in the same call, the frame-configuration keys `:initial-events` (a vector of event vectors dispatched into the new frame at creation — seed `app-db` with `[[:rf/set-db {…}]]`), `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`. A frame created **without** an `:id` bypasses the registry — a direct local reference for tests and harnesses. **Route by id, not by value:** the frame value's representation is not an app-facing contract — read its id via the one accessor `rf/frame-value->id` and pass the **id** to `dispatch` / `subscribe` / providers / tools. Lifecycle is the caller's responsibility — pair a direct `make-frame` with a `destroy-frame!` in the `:finally` of `r/with-let`, or use the UI-owned `rf/frame-provider` boundary (below) for view-owned lifetimes. See the [Frames concept guide](../concepts/frames.md#when-you-want-more-than-one) and [EP-0024](../../EP/EP-0024-unified-frame-identity-and-lifecycle.md).
- **Example**:
  ```clojure
  ;; A component that OWNS a frame lifetime uses the UI-owned provider,
  ;; which creates-on-mount and destroys-on-unmount for you (EP-0024):
  (defn counter-widget [label]
    [rf/frame-provider {:images         [counter-image]
                        :initial-events [[:rf/set-db {:count 0}]]}
     [counter-view label]])
  ```
- **In the wild**: [7GUIs](https://github.com/day8/re-frame2/tree/main/examples/core/seven_guis)

### Registrars owned by other chapters

These registrars are re-exported on the `re-frame.core` facade for single-import ergonomics, but each is **defined in full — signature, metadata grammar, examples — in its domain chapter**. This table is the index; author against the chapter.

| Registrar | What it registers | Defined in |
|---|---|---|
| `reg-view` / `reg-view*` | Views — the `defn`-shape macro, and the plain-fn / computed-id form | [02 — Views](02-views.md) |
| `reg-machine` | A state machine as an event handler | [04 — Machines](../../machines/api.md) |
| `reg-app-schema` / `reg-app-schemas` | Malli schema for an `app-db` path (and the bulk plural form) | [08 — Schemas](08-schemas.md) |
| `reg-flow` | A derived flow that auto-recomputes into an `app-db` path | [05 — Flows](05-flows.md) |
| `reg-route` | A route as data | [06 — Routing](../../routing/api.md) |
| `reg-head` / `reg-error-projector` | SSR head-model and trace-event → public-error projector | [09 — SSR](../../ssr/api.md) |

> **Facade status of the starred forms.** `reg-view*` is on the `re-frame.core` facade (the plain-fn / computed-id view surface). `reg-machine*` is **not** — the plain-fn machine-registration surface lives in `re-frame.machines/reg-machine*`, and only the `reg-machine` / `defmachine` macros are on the facade.

### Clearing registrations

The inverse surface. Each `clear-*` removes an entry from the registrar; the no-arg form clears the whole kind. Use clearing in tests (the `with-fresh-registrar` fixture relies on these), in REPL workflows, and during teardown.

#### `clear-event`

- **Signature**:
  ```clojure
  (clear-event)
  (clear-event id)
  ```
- **Description**: "Forget this event-handler." No-arg clears the whole `:event` registry.
- **Example**:
  ```clojure
  (rf/clear-event :counter/inc)   ;; forget one handler
  (rf/clear-event)                ;; forget every registered :event
  ```

#### `clear-sub`

- **Signature**:
  ```clojure
  (clear-sub)
  (clear-sub id)
  ```
- **Description**: "Forget this sub." Note: this is the registrar-side clear (the inverse of `reg-sub`). The runtime cache decrement is `unsubscribe` (see below).
- **Example**:
  ```clojure
  (rf/clear-sub :counter/value)   ;; forget one sub registration
  (rf/clear-sub)                  ;; forget every registered :sub
  ```

#### `clear-fx`

- **Signature**:
  ```clojure
  (clear-fx)
  (clear-fx id)
  ```
- **Description**: "Forget this fx."
- **Example**:
  ```clojure
  (rf/clear-fx :app/scroll-to-top)   ;; forget one fx
  (rf/clear-fx)                      ;; forget every registered :fx
  ```

#### `destroy-frame!`

- **Signature**:
  ```clojure
  (destroy-frame! frame-id)
  ```
- **Description**: The normative teardown boundary. Per-feature artefacts (flows, machines, schemas, SSR, epoch) hang their frame-scoped cleanup off this single call.
- **Example**:
  ```clojure
  ;; SSR per-request frame — torn down in a finally, success or exception.
  (try
    (render-request fid)
    (finally
      (rf/destroy-frame! fid)))
  ```

#### `reset-frame!`

- **Signature**:
  ```clojure
  (reset-frame! frame-id)
  ```
- **Description**: Atomic `destroy-frame!` + `reg-frame` with the **same config** — a full frame replace (opt-in). It tears the frame down through the normative `destroy-frame!` boundary (running `:on-destroy`, releasing per-feature resources) and re-registers it fresh, so machine snapshots, the route slice, flows, and `app-db` are all rebuilt from the registered config. Use sparingly. To wipe just the `app-db` partition while keeping live runtime-db (machines / routes / SSR survive), reach for `reset-app-db!` ([11 — Instrumentation](11-instrumentation.md)) instead. There is **no** `:initial-db` config key to restore from — seeding `app-db` is itself an ordinary, traceable event, `[:rf/set-db {…}]` (see [Standard events](#standard-events) below).
- **Example**:
  ```clojure
  ;; Full frame replace (destroy + re-reg with the SAME config).
  ;; Must run OUTSIDE any handler cascade — e.g. a restart button's :on-click.
  (rf/reset-frame! :app/main)
  ```

#### `clear-sub-cache!`

- **Signature**:
  ```clojure
  (clear-sub-cache! frame-id?)
  ```
- **Description**: Force-clear the sub-cache for a frame (or all frames). Tests; rarely needed in app code.
- **Example**:
  ```clojure
  (rf/clear-sub-cache! :app/main)   ;; evict one frame's cached subs
  (rf/clear-sub-cache!)             ;; current frame (test / REPL teardown)
  ```

### See also

- [02 — Views](02-views.md) for `reg-view*` in detail, the `view` lookup form, and the substrate-agnostic ergonomic surface (`capture-frame`, `frame-provider`).
- [03 — Effects and interceptors](03-effects.md) for what the `reg-event` handler's return value can carry.
- [12 — Registrar](12-registrar.md) for the read-side of the registrar — `registrations`, `handler-ids`, `handler-meta`.

## Dispatch and subscribe

These are the two verbs that drive the cascade. `dispatch` says "an event happened, run it through the cascade"; `subscribe` says "give me a reactive handle on this query's value." Every other surface in re-frame2 either composes them or sits beside them.

`dispatch` and `dispatch-sync` come in macro + fn pairs. The **macro** form (`dispatch`, `dispatch-sync`, `subscribe`) captures the call-site source coords so tools like re-frame-10x and Xray can navigate from a trace event back to the originating expression. The **`*` fn** form (`dispatch*`, `dispatch-sync*`) skips the stamping — needed when you compose dispatch through a higher-order function (`(map dispatch* events)`) where a macro can't sit. Both route through the same dispatcher; only the trace stamping differs. (There is no app-facing `subscribe*` twin: the `subscribe`-with-explicit-frame fn form is internal — subscribe with `{:frame …}` opts instead. See [EP-0024](../../EP/EP-0024-unified-frame-identity-and-lifecycle.md).)

### `dispatch`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch event)
  (dispatch event opts)
  ```
- **Description**: Async dispatch — drops the event onto the frame's queue, returns immediately. The default; use it for everything that isn't a synchronous test setup.
- **Example**:
  ```clojure
  [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter)

### `dispatch*`

- **Kind**: function
- **Signature**:
  ```clojure
  (dispatch* event)
  (dispatch* event opts)
  ```
- **Description**: Fn variant of `dispatch`. Compose through `map` / `comp` / `partial`; skips call-site stamping.
- **Example**:
  ```clojure
  ;; A plain fn value — pass it through a HoF where the dispatch macro can't sit.
  (run! rf/dispatch* events)
  ```

### `dispatch-sync`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch-sync event)
  (dispatch-sync event opts)
  ```
- **Description**: Synchronous dispatch — runs the cascade to completion before returning. Tests, REPL workflows, and one-shot app-boot events live here. Do not use in handlers (it'll deadlock the queue).
- **Example**:
  ```clojure
  (rf/dispatch-sync [:counter/initialise])   ;; one-shot app-boot event
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter)

### `dispatch-sync*`

- **Kind**: function
- **Signature**:
  ```clojure
  (dispatch-sync* event)
  (dispatch-sync* event opts)
  ```
- **Description**: Fn variant of `dispatch-sync`.
- **Example**:
  ```clojure
  ;; Fn-form — drive a sequence of events synchronously from runner / test code.
  (doseq [evec events]
    (rf/dispatch-sync* evec {:frame :app/main}))
  ```

### `subscribe`

- **Kind**: macro
- **Signature**:
  ```clojure
  (subscribe query-v)
  (subscribe query-v opts)
  ```
- **Description**: The reactive handle. Returns a reaction whose value is the registered sub's current output; recomputes when upstreams change. Use inside views, inside other subs, and (carefully) inside event handlers via the cofx wrapper. Target a non-ambient frame via the `{:frame …}` opt — `(rf/subscribe [:counter/value] {:frame :other})`; the frame **id** is the public routing address (see [EP-0024](../../EP/EP-0024-unified-frame-identity-and-lifecycle.md)). The frame-first `(subscribe frame-id query-v)` arity and the `subscribe*` fn form are **internal**, not app-facing.
- **Example**:
  ```clojure
  [:span @(rf/subscribe [:counter/value])]
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/core/counter)

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once query-v opts) → value
  ```
- **Description**: One-shot read: subscribe, deref, immediately unsubscribe. Use in handler bodies, machine actions, REPL — anywhere you want the *current* value without the reactive plumbing. Not for views. Target a non-ambient frame via `{:frame …}`.
- **Example**:
  ```clojure
  ;; One-shot read of the current value — no reactive handle retained.
  (let [articles (rf/subscribe-once [:articles])]
    (count articles))
  ```

### `unsubscribe`

- **Kind**: function
- **Signature**:
  ```clojure
  (unsubscribe query-v) → nil
  (unsubscribe query-v opts) → nil
  ```
- **Description**: Decrement the cache ref-count for a query. When the count hits zero, the entry is disposed **synchronously** — see [Subscriptions](../concepts/subscriptions.md). Most callers don't reach for this directly — Reagent / UIx / Helix adapters wire it on unmount. Target a non-ambient frame via `{:frame …}`.
- **Example**:
  ```clojure
  ;; Manual ref-count pairing (tests / REPL) — balances an explicit subscribe.
  (let [r (rf/subscribe [:counter/value])]
    @r
    (rf/unsubscribe [:counter/value]))
  ```

### Reading a machine's snapshot

Subscribe to `[:rf/machine machine-id]` for a reaction over the machine's `{:state :data}` snapshot. See [04 — Machines](../../machines/api.md).

**The `opts` map.** `dispatch` and `subscribe` accept a uniform opts map: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics live in the [event-envelope glossary entry](../glossary.md#event-envelope). The most common pattern is `(rf/dispatch [::save x] {:frame :todo})` to target a non-default frame.

### Canonical event-vector shape

The runtime tolerates several shapes; the linter nudges new code toward one:

- `[<id>]` — trivial events
- `[<id> <single-scalar>]` — single-arg events
- `[<id> {<k> <v>}]` — multi-arg events as a single map payload (the canonical form for two-or-more args)

Variadic `[<id> a b c]` is tolerated, but the map form is the one to reach for in new code — it survives field-additions without breaking callers and reads at the call site. See [Events and the cascade — an event is a fact](../concepts/events-and-the-cascade.md#an-event-is-a-fact).

### Standard events

The framework ships a small, fixed set of standard `:rf/*` events you can dispatch like any other. They are framework-owned: the `:rf/*` single-root namespace is reserved for the framework, so re-registering one with `reg-event` is a loud reserved-id collision (`:rf.error/reserved-event-id`) rather than a silent shadow.

#### `:rf/set-db`

- **Kind**: standard event
- **Shape**:
  ```clojure
  [:rf/set-db new-db-map]
  ```
- **Description**: The framework-standard `app-db` seeding event. `[:rf/set-db {…}]` **replaces** the whole `app-db` partition with the supplied map (it is a replace, not a merge) and rides the **normal** post-commit path — schema validation, rollback, trace emission, epoch recording — so seeding `app-db` is an ordinary, traceable event rather than a privileged direct write. It returns `{:db new-db}` from a pure handler, so it touches **only** the `app-db` partition and never runtime-db.
- **Validation**: takes **exactly one map argument**. A missing / `nil` / non-map argument, or any extra trailing arg (`[:rf/set-db {} :junk]`), throws `:rf.error/set-db-bad-value`. Empty `app-db` is `[:rf/set-db {}]`.
- **In the wild**: the canonical boot-seed shape — `:initial-events [[:rf/set-db {:count 0}]]` on `frame-provider` / `make-frame`. (There is no `:initial-db` data key.)

```clojure
;; seed app-db at frame creation
[rf/frame-provider {:images         [counter-image]
                    :initial-events [[:rf/set-db {:count 0}]]}
 [counter-view]]

;; or dispatch it directly to reset app-db to a known shape
(rf/dispatch [:rf/set-db {:count 0 :user nil}])
```

### The `dispatch-*` family: two sub-shapes

The family has two sub-shapes that look alike on first read but answer different questions.

**Stamping pair** (`dispatch` / `dispatch*` and `dispatch-sync` / `dispatch-sync*`). The pair-shape question is "do you want call-site stamping or not?" The macro captures source coords for `:rf.trace/call-site`; the `*` fn-form skips the stamping for HoF composition. Both route through the same dispatcher.

**Named-target addressing** (the `[:rf.machine/dispatch-to-system [system-id event]]` fx). The question is "do you have a `:system-id` instead of a target machine-id?" It's *not* a different kind of dispatch — it's named-addressing on top of the same dispatcher. (This is **not** a `re-frame.core` facade verb: the direct-call fn `re-frame.machines/dispatch-to-system` was demoted to an implementation-tier helper in the machines artefact — the fx tuple is the canonical surface.) For `:system-id` resolution, see [04 — Machines](../../machines/api.md).

The two compose: the named-target fx ultimately dispatches, so the same trace stamping fires on the resulting event.

### See also

- [02 — Views](02-views.md) — `capture-frame` captures the current frame at creation time and returns frame-bound ops that survive callbacks where the dynamic-var binding has unwound.
- [03 — Effects and interceptors](03-effects.md) — the effect map's `:fx` vector is how event handlers schedule more dispatches.

## Frames: the scoping primitive

A frame is the scoping unit for `app-db`, the event queue, and the cascade. Most apps have exactly one frame. You establish it at your root with the merged `rf/frame-provider`, which takes one of two config shapes (see the [frame-provider glossary entry](../glossary.md#frame-provider)): scope an already-registered frame into the React tree with `[rf/frame-provider {:frame :app} …]` (or, for non-React lexical regions, `(rf/with-frame :app …)`), or let `[rf/frame-provider {:id :app …} …]` **ensure** the frame — it creates it on first mount, reuses it without re-seeding on remount, and provides its id to descendants (no destroy-on-unmount). `init!` does **not** create one for you — frame identity is carried, not synthesised from absence (see [Frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found)). Apps that need isolation between subsystems — embedded widgets, multi-tab pair tools, the SSR per-request runtime — register additional frames and dispatch / subscribe against them via `{:frame :other}` (the frame **id** is the public routing address).

`reg-frame` and `make-frame` are rowed in **Registration** above. The two read-side surfaces — `frame-ids` and `frame-meta` — are defined in [12 — Registrar](12-registrar.md) alongside the rest of the registrar-query surface (`registrations`, `handler-meta`).

### `with-frame` / `with-new-frame`

- **Kind**: macros (a sibling pair)
- **Signatures**:
  ```clojure
  (with-frame :keyword body)        ;; pin *current-frame* to an existing frame-id
  (with-new-frame [sym expr] body)  ;; eval expr, bind id to sym, run, destroy on exit
  ```
- **Description**: The two **lexical** (non-React) frame-scoping macros — the regions that aren't a view tree, chiefly tests, the REPL, and SSR. `with-frame` pins `*current-frame*` to an **existing** frame-id for the dynamic extent of `body`, creating and destroying nothing; it is the lexical counterpart to the `rf/frame-provider` `{:frame …}` SCOPE shape (a dynamic var cannot cross React's render boundary, which is why the provider exists for the view tree). `with-new-frame` evaluates `expr`, binds the resulting frame-id to `sym`, runs `body` in that frame's dynamic context, and **destroys the frame on exit** — the throwaway-frame form for one-off harnesses. Each rejects the other's argument shape at compile time: a vector binding handed to `with-frame`, or a bare keyword handed to `with-new-frame`, fails at macroexpand.
- **Example**:
  ```clojure
  ;; Pin form — bind *current-frame* to an existing id for the body (most common)
  (rf/with-frame :todo
    (rf/dispatch-sync [:todo/add {:text "milk"}]))

  ;; Pin to a computed id — pass the keyword directly, no extra binding
  (let [chosen (pick-frame-id route)]
    (rf/with-frame chosen
      (rf/dispatch-sync [:todo/clear-completed])))

  ;; Eval-bind-run-destroy — a throwaway frame for one test, torn down on exit.
  ;; Body runs in f's dynamic context, so bare dispatch-sync targets it.
  (rf/with-new-frame [f (rf/make-frame {:images [todo-image]})]
    (rf/dispatch-sync [:rf/set-db {:todos []}])         ;; seed via a setup dispatch
    (rf/dispatch-sync [:todo/add {:text "milk"}])
    (is (= 1 (count (:todos (rf/app-db-value f))))))    ;; frame destroyed on exit
  ```

## Runtime configuration: `configure`

Process-level data knobs live behind `(rf/configure! {<key> <opts>})`. The vocabulary of keys is closed-and-additive — existing keys cannot be renamed; new keys are added by extending the table. Currently three keys ship:

| Key | Opts | Default | Status | What it tunes |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn fn}` | `{:depth 50, :trace-events-keep 50, :redact-fn nil}` | v1 (dev-only) | Per-frame epoch ring depth (the time-travel buffer), trace-event retention cap per record (defaults to `:depth` so each retained epoch keeps its trace), and an optional projection-side redactor applied only at off-box egress (inside `projected-record`) — the ring and listeners always deliver the raw record, so the redactor never affects `restore-epoch!` fidelity. |
| `:trace-buffer` | `{:cascades-retained N}` | `{:cascades-retained 50}` | v1 (dev-only) | The dev-only per-frame trace ring's cascade-slot count. 0 disables retention (the surface stays live). An opts map without a usable `:cascades-retained` (e.g. the retired `{:depth N}` shape) is a no-op that emits a `:rf.warning/trace-buffer-unrecognised-opts` trace. |
| `:elision` | `{:rf.size/threshold-bytes N}` | `{:rf.size/threshold-bytes 16384}` | v1 | The size threshold above which `elide-wire-value` substitutes a `:rf.size/large-elided` marker. 0 disables runtime auto-detect (only declared / schema entries elide). See [11 — Instrumentation](11-instrumentation.md). |

> **No `:sub-cache` knob.** There is no `:sub-cache {:grace-period-ms N}` key — sub-cache disposal is synchronous on derefer-count → 0 (see [Subscriptions — lifecycle](../concepts/subscriptions.md#lifecycle-a-sub-exists-only-while-something-watches-it)).

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it's per-frame metadata on the frame's `:ssr` map, because different frames in the same process can carry different projector settings.

### Opts-key naming rule

The opts map mixes two key shapes:

- **Framework-owned semantic sub-keys use a namespaced keyword** — `:rf.size/threshold-bytes`. The namespace identifies the cross-spec policy area; the same key shape appears verbatim wherever that policy is consumed (here under `:elision`, but also as a per-call opt to `elide-wire-value`).
- **Ergonomic per-knob sub-keys are unqualified bare keywords** — `:depth`, `:trace-events-keep`, `:redact-fn`. Local to a single `configure` key; no cross-surface identity to encode.

The discriminator is whether the sub-key names a cross-surface contract or a one-off knob. The rule is closed — there's no third shape.

### See also

- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` and the per-call `:fx-overrides` envelope are the *other* configuration surfaces (per-frame metadata is the third).
- [13 — Lifecycle](13-lifecycle.md) — `init!` / `install-adapter!` / `destroy-adapter!` set up and tear down the running process.
