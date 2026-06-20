# 01 — Core

The Core chapter is what you `:require` from `re-frame.core` to make an app exist at all. Five clusters live in here, and they're the surfaces you'll see in every app you ever write: **registration** (`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`), **dispatch and subscribe** (the two verbs that drive the cascade), **frames** (the scoping primitive — `reg-frame` / `make-frame`), **runtime configuration** (`configure`), and **clearing** (the inverse of registration).

If you read only one chapter of this reference, this is the one to read. Everything in the other chapters builds on these five clusters.

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
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter), [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter)

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata input-fn? computation-fn)
  ```
- **Description**: "Computed view over `app-db` and other subs." `reg-sub` supports **three input-production modes** — every subscription has an *input query-vector producer*: a layer-1 app-db reader has no producer, `:<-` is the literal producer, and a parametric `input-fn` is the query-parametric producer. The optional first fn is a v2 **`input-fn`** — a *pure* function from the outer `query-v` to a **vector of query vectors**; it is **not** a v1 reaction-returning signal fn (it must not call `subscribe`, deref `app-db`, dispatch, or perform IO, and it must not return live reactions). The runtime resolves each returned query vector in the *same frame* as the outer subscription. This is the only sub-registration form in v2 — `reg-sub-raw` is gone (see [15 — Removed](15-removed.md) for the replacement guidance). Full contract, input grammar, and error ids: [spec API §`reg-sub` input-production modes](../../spec/API.md#reg-sub-input-production-modes) and [spec 006 — Reactive Substrate](../../spec/006-ReactiveSubstrate.md). The teaching walkthrough is [Guide ch.05 §Three ways a sub names its inputs](../guide/concepts/subscriptions.md).

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
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

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
- **In the wild**: [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter)

### `reg-cofx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-cofx id ?metadata supplier)
  ```
- **Description**: "Register a named supplier for a world fact a handler can ask for." The supplier is a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the call site — *not* a context-mutating fn. The runtime calls it and puts the result into the coeffects map under the cofx's id. A handler opts in with `:rf.cofx/requires` registration metadata; v2 has **no `inject-cofx` interceptor**. The middle slot carries the fact's grade: `{:recordable? true}` for a replayable fact, `{:recordable? true :provided? true}` for a recordable fact stamped by an owner boundary (no generator), a bare registration for an ambient (unrecorded) read. Reading a sub from a handler is done the same way — wrap `subscribe-once` in a cofx and declare it (see [Guide — Reading a subscription from a handler](../guide/concepts/effects-and-coeffects.md)). Full model: [Guide — Effects and coeffects](../guide/concepts/effects-and-coeffects.md).
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
- **In the wild**: [todomvc](https://github.com/day8/re-frame2/tree/main/examples/reagent/todomvc)

### `reg-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-frame id metadata)
  ```
- **Description**: Atomic create-and-register. A frame is the scoping unit — one `app-db`, one event queue, one cascade — and `reg-frame` minted it with metadata you can later read via `frame-meta`. The frame is also where **durable `app-db` data classification** lives (EP-0015): `:sensitive` / `:large` `:app-db` path maps, frame-local HTTP carrier names, and `:observability` sink policy are declared here and projected at every wire boundary. See [08 — Schemas §Data classification](08-schemas.md#data-classification) and [Guide ch.23 — Privacy and large things](../guide/how-to/keep-secrets-out-of-traces.md).
- **Example**:
  ```clojure
  ;; User-defined fxs sit under a user-feature prefix per
  ;; spec/Conventions.md §Reserved namespaces — never under `:rf.<feature>/…`,
  ;; which is reserved for framework-owned surfaces.
  (rf/reg-frame :app/main
    {:doc          "App demo frame."
     :sensitive    {:app-db [[:auth :token]]}
     :fx-overrides {:rf.http/managed :auth.login.demo/managed-stub}})
  ```
- **In the wild**: [boot](https://github.com/day8/re-frame2/tree/main/examples/reagent/boot)

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) ; → live frame value
  ```
- **Description**: The **single public constructor** for a live frame (per [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md)). It accepts image-selection options *and* frame-configuration options in **one** call and **returns the live frame value** — one frame value backed by one registry. There is no separate object-vs-record constructor split, and no caller has to create a backing frame first and then an image-loaded object for the same id. Useful for per-mount lifecycles — devcards, modal stacks, multiple live instances of a widget, dynamic tabs, tests, and the SSR per-request frame pattern. The `reg-frame` named path is the front-porch surface; `make-frame` is the advanced per-instance one. Opts: the image-selection keys `:images` (always a vector — the assembled registration set the frame resolves against), `:id` (optional — registers the frame in the one process-local live-frame registry; a duplicate live id is **idempotent replacement** that preserves durable state on re-mount, not a blanket fail-loud — irreconcilable conflicts still fail loud), `:initial-db` (seed `app-db` state), `:capabilities`, `:adapter` — **and**, in the same call, the frame-configuration keys `:on-create`, `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`. A frame created **without** an `:id` bypasses the registry — a direct local reference for tests and harnesses. **Route by id, not by value:** the frame value's representation is not an app-facing contract — read its id via the one accessor `rf/frame-value->id` and pass the **id** to `dispatch` / `subscribe` / providers / tools. Lifecycle is the caller's responsibility — pair a direct `make-frame` with a `destroy-frame!` in the `:finally` of `r/with-let`, or use the UI-owned `rf/frame-provider` boundary (below) for view-owned lifetimes. See [spec/002 §Per-instance frames](../../spec/002-Frames.md#per-instance-frames--make-frame-the-ep-0023-object-constructor) and [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md).
- **Example**:
  ```clojure
  ;; A component that OWNS a frame lifetime uses the UI-owned provider,
  ;; which creates-on-mount and destroys-on-unmount for you (EP-0024):
  (defn counter-widget [label]
    [rf/frame-provider {:images     [counter-image]
                        :initial-db {:count 0}}
     [counter-view label]])
  ```
- **In the wild**: [7GUIs](https://github.com/day8/re-frame2/tree/main/examples/reagent/seven_guis)

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+)
  ```
  (plus shape-variants — see [02 — Views](02-views.md))
- **Description**: `defn`-shape view registration. Auto-defs the symbol; auto-derives an id from `(keyword *ns* sym)`; auto-injects `dispatch` / `subscribe` as lexical bindings; rejects non-defn-shape bodies at macroexpand. See [02 — Views](02-views.md).
- **Example**:
  ```clojure
  (rf/reg-view counter-buttons []
    [:div
     [:button {:on-click #(dispatch [:counter/dec])} "-"]
     [:span @(subscribe [:counter/value])]
     [:button {:on-click #(dispatch [:counter/inc])} "+"]])
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `reg-view*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-view* id render-fn)
  (reg-view* id metadata render-fn)
  ```
- **Description**: Plain-fn surface beneath `reg-view`. No auto-def, no auto-inject, no compile check. Use for computed ids, library-generated views, Reagent Form-3 (`create-class`), or registration without a Var.

### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  ```
- **Description**: Registers a state machine as an event handler (the machine *is* the handler — the body comes from `make-machine-handler`). Walks the literal spec form at expansion time and stamps per-element source coords for click-to-source navigation. See [04 — Machines](04-machines.md).
- **Example**:
  ```clojure
  (rf/reg-machine :auth.login/flow
    {:initial :idle
     :states  {:idle      {:on {:submit :submitting}}
               :submitting {:on {:ok :done :err :idle}}
               :done      {}}})
  ```
- **In the wild**: [state_machine_walkthrough](https://github.com/day8/re-frame2/tree/main/examples/reagent/state_machine_walkthrough)

> **`reg-machine*` is not a core facade export.** The plain-fn machine-registration surface lives in `re-frame.machines` (`re-frame.machines/reg-machine*`), not `re-frame.core` (rf2-wad2fl — front-porch shrink). Only the `reg-machine` / `defmachine` macros are on the `re-frame.core` facade. See [04 — Machines](04-machines.md#re-framemachinesreg-machine).

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path {:schema schema})
  (reg-app-schema path {:schema schema :frame frame})
  ```
- **Description**: "Declare the Malli schema for this `app-db` path." **Path is the registration id** — the only `reg-*` keyed by path rather than keyword, because schemas-at-paths matches the dataflow grain. The schema rides the metadata map under `:schema`; the optional frame target rides `:frame`. See [08 — Schemas](08-schemas.md).
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    {:schema [:map [:cells/grid [:map-of :keyword :string]]]})
  ```
- **In the wild**: [7GUIs](https://github.com/day8/re-frame2/tree/main/examples/reagent/seven_guis)

### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, ...})
  ```
- **Description**: Bulk plural form for feature-modular apps that register 5–20 paths together. Each entry routes through the singular form and is stamped with this call's source-coords.
- **Example**:
  ```clojure
  (rf/reg-app-schemas
    {[:auth]     AuthState
     [:articles] ArticlesState
     [:profile]  ProfileState})
  ```
- **In the wild**: [realworld](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld)

### `reg-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-flow flow)
  (reg-flow flow opts)
  ```
- **Description**: Register a derived flow — `{:id :inputs :derive :output-path}` — that auto-recomputes when its inputs change and writes the result into `:output-path` in `app-db`. `:inputs` is a positional vector of `app-db` paths; the values arrive as positional args to `:derive`. See [05 — Flows](05-flows.md).
- **Example**:
  ```clojure
  (rf/reg-flow
    {:id     :cart/total
     :inputs [[:cart :items]]
     :derive (fn [items] (reduce + (map :price items)))
     :output-path [:cart :total]})
  ```

### `reg-route`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-route id metadata path)
  ```
- **Description**: Register a route as data: the path is the third positional arg; the metadata map carries `:params`, `:query`, `:on-match`, `:on-error`, `:can-leave`. See [06 — Routing](06-routing.md).
- **Example**:
  ```clojure
  (rf/reg-route :route/home
    {:on-match [[:home/load]]}
    "/")
  ```
- **In the wild**: [routing](https://github.com/day8/re-frame2/tree/main/examples/reagent/routing)

### `reg-head`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-head id ?metadata head-fn)
  ```
- **Description**: SSR: register a `(fn [db route] head-model)` keyed by id; routes opt-in via `:head` metadata. See [09 — SSR](09-ssr.md).
- **Example**:
  ```clojure
  (rf/reg-head :app/head
    (fn [db _route]
      {:title (str "MyApp — " (:page-title db))}))
  ```

### `reg-error-projector`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-error-projector id ?metadata projector-fn)
  ```
- **Description**: SSR: register a `(fn [trace-event] :rf/public-error)`; named per-frame via the frame's `:ssr {:public-error-id ...}` metadata.

### Clearing registrations

The inverse surface. Each `clear-*` removes an entry from the registrar; the no-arg form clears the whole kind. Use clearing in tests (the `with-fresh-registrar` fixture relies on these), in REPL workflows, and during teardown.

#### `clear-event`

- **Signature**:
  ```clojure
  (clear-event)
  (clear-event id)
  ```
- **Description**: "Forget this event-handler." No-arg clears the whole `:event` registry.

#### `clear-sub`

- **Signature**:
  ```clojure
  (clear-sub)
  (clear-sub id)
  ```
- **Description**: "Forget this sub." Note: this is the registrar-side clear (the inverse of `reg-sub`). The runtime cache decrement is `unsubscribe` (see below).

#### `clear-fx`

- **Signature**:
  ```clojure
  (clear-fx)
  (clear-fx id)
  ```
- **Description**: "Forget this fx."

#### `clear-flow`

- **Signature**:
  ```clojure
  (clear-flow id)
  (clear-flow id opts)
  ```
- **Description**: Deregisters the flow from the named frame and `dissoc-in`s its `:output-path` from that frame's `app-db` only. See [05 — Flows](05-flows.md).

#### `destroy-frame!`

- **Signature**:
  ```clojure
  (destroy-frame! frame-id)
  ```
- **Description**: The normative teardown boundary. Per-feature artefacts (flows, machines, schemas, SSR, epoch) hang their frame-scoped cleanup off this single call.

#### `reset-frame!`

- **Signature**:
  ```clojure
  (reset-frame! frame-id)
  ```
- **Description**: Reset the frame's `app-db` to its initial value without destroying the frame itself.

#### `clear-sub-cache!`

- **Signature**:
  ```clojure
  (clear-sub-cache! frame-id?)
  ```
- **Description**: Force-clear the sub-cache for a frame (or all frames). Tests; rarely needed in app code.

### See also

- [02 — Views](02-views.md) for `reg-view*` in detail, the `view` lookup form, and the substrate-agnostic ergonomic surface (`frame-handle`, `with-frame`, `frame-provider`).
- [03 — Effects and interceptors](03-effects.md) for what the `reg-event` handler's return value can carry.
- [12 — Registrar](12-registrar.md) for the read-side of the registrar — `registrations`, `handler-ids`, `handler-meta`.

## Dispatch and subscribe

These are the two verbs that drive the cascade. `dispatch` says "an event happened, run it through the cascade"; `subscribe` says "give me a reactive handle on this query's value." Every other surface in re-frame2 either composes them or sits beside them.

`dispatch` and `dispatch-sync` come in macro + fn pairs. The **macro** form (`dispatch`, `dispatch-sync`, `subscribe`) captures the call-site source coords so tools like re-frame-10x and Xray can navigate from a trace event back to the originating expression. The **`*` fn** form (`dispatch*`, `dispatch-sync*`) skips the stamping — needed when you compose dispatch through a higher-order function (`(map dispatch* events)`) where a macro can't sit. Both route through the same dispatcher; only the trace stamping differs. (There is no app-facing `subscribe*` twin: the `subscribe`-with-explicit-frame fn form is internal under [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md); subscribe with `{:frame …}` opts instead.)

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
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `dispatch*`

- **Kind**: function
- **Signature**:
  ```clojure
  (dispatch* event)
  (dispatch* event opts)
  ```
- **Description**: Fn variant of `dispatch`. Compose through `map` / `comp` / `partial`; skips call-site stamping.

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
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `dispatch-sync*`

- **Kind**: function
- **Signature**:
  ```clojure
  (dispatch-sync* event)
  (dispatch-sync* event opts)
  ```
- **Description**: Fn variant of `dispatch-sync`.

### `subscribe`

- **Kind**: macro
- **Signature**:
  ```clojure
  (subscribe query-v)
  (subscribe query-v opts)
  ```
- **Description**: The reactive handle. Returns a reaction whose value is the registered sub's current output; recomputes when upstreams change. Use inside views, inside other subs, and (carefully) inside event handlers via the cofx wrapper. Target a non-ambient frame via the `{:frame …}` opt — `(rf/subscribe [:counter/value] {:frame :other})`; the frame **id** is the public routing address (per [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md)). The frame-first `(subscribe frame-id query-v)` arity and the `subscribe*` fn form are **internal**, not app-facing.
- **Example**:
  ```clojure
  [:span @(rf/subscribe [:counter/value])]
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once query-v opts) → value
  ```
- **Description**: One-shot read: subscribe, deref, immediately unsubscribe. Use in handler bodies, machine actions, REPL — anywhere you want the *current* value without the reactive plumbing. Not for views. Target a non-ambient frame via `{:frame …}`.

### `unsubscribe`

- **Kind**: function
- **Signature**:
  ```clojure
  (unsubscribe query-v) → nil
  (unsubscribe query-v opts) → nil
  ```
- **Description**: Decrement the cache ref-count for a query. When the count hits zero, the entry is disposed **synchronously** (per Spec 006 §Reference counting and disposal). Most callers don't reach for this directly — Reagent / UIx / Helix adapters wire it on unmount. Target a non-ambient frame via `{:frame …}`.

### Reading a machine's snapshot

To read a machine's snapshot, subscribe to the canonical `[:rf/machine machine-id]` vector — `@(rf/subscribe [:rf/machine machine-id])` yields a reaction over `{:state :data}` (or `nil` if uninitialised). See [04 — Machines](04-machines.md).

**The `opts` map.** `dispatch` and `subscribe` accept a uniform opts map: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics live in [002 §Routing: the dispatch envelope](../../spec/002-Frames.md#routing-the-dispatch-envelope). The most common pattern is `(rf/dispatch [::save x] {:frame :todo})` to target a non-default frame.

### Canonical event-vector shape

The runtime tolerates several shapes; the linter nudges new code toward one:

- `[<id>]` — trivial events
- `[<id> <single-scalar>]` — single-arg events
- `[<id> {<k> <v>}]` — multi-arg events as a single map payload (the canonical form for two-or-more args)

Variadic `[<id> a b c]` is tolerated for v1-migration and caller convenience, but the map form is the one to reach for in new code — it survives field-additions without breaking callers and reads at the call site. Full rationale: [Conventions §Canonical event-vector shape](../../spec/Conventions.md#canonical-event-vector-shape-best-practice).

### The `dispatch-*` family: two sub-shapes

The family has two sub-shapes that look alike on first read but answer different questions.

**Stamping pair** (`dispatch` / `dispatch*` and `dispatch-sync` / `dispatch-sync*`). The pair-shape question is "do you want call-site stamping or not?" The macro captures source coords for `:rf.trace/call-site`; the `*` fn-form skips the stamping for HoF composition. Both route through the same dispatcher.

**Named-target addressing** (the `[:rf.machine/dispatch-to-system [system-id event]]` fx, per [04 — Machines](04-machines.md)). The question is "do you have a `:system-id` instead of a target machine-id?" The fx resolves through the per-frame `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db) and then dispatches. It's *not* a different kind of dispatch — it's named-addressing on top of the same dispatcher. (This is **not** a `re-frame.core` facade verb: the direct-call fn `re-frame.machines/dispatch-to-system` was demoted to an implementation-tier helper in the machines artefact — the fx tuple is the canonical surface.)

The two compose: the named-target fx ultimately dispatches, so the same trace stamping fires on the resulting event.

### See also

- [02 — Views](02-views.md) — `frame-handle` captures the current frame at creation time and returns frame-bound ops that survive callbacks where the dynamic-var binding has unwound.
- [03 — Effects and interceptors](03-effects.md) — the effect map's `:fx` vector is how event handlers schedule more dispatches.

## Frames: the scoping primitive

A frame is the scoping unit for `app-db`, the event queue, and the cascade. Most apps have exactly one frame. You establish it at your root one of two ways (per [EP-0024](../../spec/002-Frames.md#the-multi-frame-surface--choose-by-intent)): scope an already-registered frame into the React tree with `[rf/frame-provider-existing {:frame :app} …]` (or, for non-React lexical regions, `(rf/with-frame :app …)`), or let a `rf/frame-provider` **own** the lifetime — it creates the frame on mount, provides its id to descendants, and destroys it on unmount. `init!` does **not** create one for you (per [EP-0002](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant), frame identity is carried, not synthesised from absence). Apps that need isolation between subsystems — embedded widgets, multi-tab pair tools, the SSR per-request runtime — register additional frames and dispatch / subscribe against them via `{:frame :other}` (the frame **id** is the public routing address).

`reg-frame` and `make-frame` are rowed in **Registration** above. The two read-side surfaces:

### `frame-ids`

- **Signature**:
  ```clojure
  (frame-ids)
  (frame-ids ns-prefix)
  ```
- **Description**: "What frames currently exist?" Returns the set of registered ids. The optional prefix filters by namespace — `(rf/frame-ids :rf.story/)` for tool-owned frames.

### `frame-meta`

- **Signature**:
  ```clojure
  (frame-meta frame-id)
  ```
- **Description**: "What did the frame declare at registration?" Returns the metadata map: `:fx-overrides`, `:interceptors`, `:ssr`, `:on-error`, schema bindings.

See [12 — Registrar](12-registrar.md) for the rest of the registrar-query surface.

## Runtime configuration: `configure`

Process-level data knobs live behind `(rf/configure! {<key> <opts>})`. The vocabulary of keys is closed-and-additive — existing keys cannot be renamed; new keys are added by extending the table. Currently three keys ship:

| Key | Opts | Default | Status | What it tunes |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn fn}` | `{:depth 50, :trace-events-keep 50, :redact-fn nil}` | v1 (dev-only) | Per-frame epoch ring depth (the time-travel buffer), trace-event retention cap per record (defaults to `:depth` so each retained epoch keeps its trace), and an optional projection-side redactor applied only at off-box egress (inside `projected-record`) — the ring and listeners always deliver the raw record, so the redactor never affects `restore-epoch!` fidelity. |
| `:trace-buffer` | `{:cascades-retained N}` | `{:cascades-retained 50}` | v1 (dev-only) | The dev-only per-frame trace ring's cascade-slot count. 0 disables retention (the surface stays live). An opts map without a usable `:cascades-retained` (e.g. the retired `{:depth N}` shape) is a no-op that emits a `:rf.warning/trace-buffer-unrecognised-opts` trace. |
| `:elision` | `{:rf.size/threshold-bytes N}` | `{:rf.size/threshold-bytes 16384}` | v1 | The size threshold above which `elide-wire-value` substitutes a `:rf.size/large-elided` marker. 0 disables runtime auto-detect (only declared / schema entries elide). See [11 — Instrumentation](11-instrumentation.md). |

> **Retired knob (rf2-cmfln).** Earlier docs listed `:sub-cache {:grace-period-ms N}`. That knob is gone — sub-cache disposal is now synchronous on derefer-count → 0 (per [Spec 006 §Reference counting and disposal](../../spec/006-ReactiveSubstrate.md#reference-counting-and-disposal)).

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it's per-frame metadata on the frame's `:ssr` map, because different frames in the same process can carry different projector settings.

### Opts-key naming rule

The opts map deliberately mixes two key shapes:

- **Framework-owned semantic sub-keys use a namespaced keyword** — `:rf.size/threshold-bytes`. The namespace identifies the cross-spec policy area; the same key shape appears verbatim wherever that policy is consumed (here under `:elision`, but also as a per-call opt to `elide-wire-value`).
- **Ergonomic per-knob sub-keys are unqualified bare keywords** — `:depth`, `:trace-events-keep`, `:redact-fn`. Local to a single `configure` key; no cross-surface identity to encode.

The discriminator is whether the sub-key names a cross-surface contract or a one-off knob. The rule is closed — there's no third shape.

### See also

- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` and the per-call `:fx-overrides` envelope are the *other* configuration surfaces (per-frame metadata is the third).
- [13 — Lifecycle](13-lifecycle.md) — `init!` / `install-adapter!` / `destroy-adapter!` set up and tear down the running process.
