# 01 — Core

The Core chapter is what you `:require` from `re-frame.core` to make an app exist at all. Five clusters live in here, and they're the surfaces you'll see in every app you ever write: **registration** (`reg-event-*`, `reg-sub`, `reg-fx`, `reg-cofx`), **dispatch and subscribe** (the two verbs that drive the cascade), **frames** (the scoping primitive — `reg-frame` / `make-frame`), **runtime configuration** (`configure`), and **clearing** (the inverse of registration).

If you read only one chapter of this reference, this is the one to read. Everything in the other chapters builds on these five clusters.

## Registration

This is the surface every re-frame2 app touches. You're answering "what events can my app handle, what data can it subscribe to, what side effects can it action, what state can it inject as coeffects?" Every entry is a registration of a named handler into the frame's registrar.

**Return value.** Every `reg-*` returns its **primary id** — the keyword (or path, for `reg-app-schema`) you registered with. This lets you write `(let [sub-id (rf/reg-sub ::foo ...)] ...)` to thread the id through your code without retyping it. The convention is uniform across the surface.

### `reg-event-db`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event-db id ?metadata-or-interceptors handler)
  ```
- **Description**: "When this event arrives, transform `app-db` and return the new one." The simplest handler shape — pure `(fn [db event-vec] new-db)`. Use it for the 80% of handlers that just update state.
- **Example**:
  ```clojure
  (rf/reg-event-db :counter/inc
    (fn [db _event] (update db :counter/value inc)))
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `reg-event-fx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event-fx id ?metadata-or-interceptors handler)
  ```
- **Description**: "When this event arrives, return an effect map." The richer shape — `(fn [cofx event-vec] {:db ... :fx [...]})`. Use it when you need to dispatch follow-up events, fire HTTP, navigate, or read cofx.
- **Example**:
  ```clojure
  (rf/reg-event-fx :counter/load
    (fn [{:keys [db]} _event]
      {:db (assoc db :status :loading)
       :fx [[:rf.http/managed {:request    {:method :get :url "/api/count"}
                               :on-success [:counter/loaded]
                               :on-failure [:counter/load-failed]}]]}))
  ```
- **In the wild**: [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter)

### `reg-event-ctx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event-ctx id ?metadata-or-interceptors handler)
  ```
- **Description**: The escape hatch — you get the raw interceptor context and return a modified context. Almost no app needs this; reach for it when you're writing infrastructure.

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata signal-fn? computation-fn)
  ```
- **Description**: "Computed view over `app-db` and other subs." The `:<-` sugar form for declaring upstream subs is preserved from v1. This is the only sub-registration form in v2 — `reg-sub-raw` is gone (see [15 — Removed](15-removed.md) for the replacement guidance).
- **Example**:
  ```clojure
  ;; Layer-2 — read straight off app-db
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))

  ;; Layer-3 — compose upstream subs via the :<- sugar
  (rf/reg-sub :counter/doubled
    :<- [:counter/value]
    (fn [value _query] (* 2 value)))
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
  (reg-cofx id ?metadata handler)
  ```
- **Description**: "Inject something into the handler's coeffect map." A `:now` cofx hands the current time; an `:rf.server/request` cofx hands the active HTTP request. Reading a sub from a handler is also done by cofx-wrapping — see [Guide ch.06 §Reading a sub from a handler](../guide/06-coeffects.md#reading-a-sub-from-a-handler).
- **Example**:
  ```clojure
  (rf/reg-cofx :app/now
    (fn [ctx] (assoc-in ctx [:coeffects :app/now] (js/Date.now))))
  ```
- **In the wild**: [todomvc](https://github.com/day8/re-frame2/tree/main/examples/reagent/todomvc)

### `reg-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-frame id metadata)
  ```
- **Description**: Atomic create-and-register. A frame is the scoping unit — one `app-db`, one event queue, one cascade — and `reg-frame` minted it with metadata you can later read via `frame-meta`.
- **Example**:
  ```clojure
  (rf/reg-frame :rf/default
    {:doc          "App demo frame."
     :fx-overrides {:rf.http/managed :rf.http/managed.demo}})
  ```
- **In the wild**: [boot](https://github.com/day8/re-frame2/tree/main/examples/reagent/boot)

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) → :rf.frame/<id>
  ```
- **Description**: Anonymous-frame shortcut. The id is gensym'd; useful for tests, transient sandboxes, and the SSR per-request frame pattern.
- **Example**:
  ```clojure
  (rf/with-frame [f (rf/make-frame {:on-create [:temp/initialise]})]
    (rf/dispatch [:temp/set-celsius 21]))
  ```
- **In the wild**: [7Guis](https://github.com/day8/re-frame2/tree/main/examples/reagent/7Guis)

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

### `reg-machine*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-machine* machine-id machine-spec)
  ```
- **Description**: Plain-fn surface beneath the macro. No source-coord walking. Use for code-gen pipelines or REPL workflows.

### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema)
  (reg-app-schema path schema opts)
  ```
- **Description**: "Declare the Malli schema for this `app-db` path." **Path is the registration id** — the only `reg-*` keyed by path rather than keyword, because schemas-at-paths matches the dataflow grain. See [08 — Schemas](08-schemas.md).
- **Example**:
  ```clojure
  (rf/reg-app-schema [:cells]
    [:map [:cells/grid [:map-of :keyword :string]]])
  ```
- **In the wild**: [7Guis](https://github.com/day8/re-frame2/tree/main/examples/reagent/7Guis)

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

### `add-marks`

- **Kind**: function
- **Signature**:
  ```clojure
  (add-marks frame-id {path mark, ...})
  ```
- **Description**: Frame-scoped path-marks for data classification — `:sensitive` paths render as `:rf/redacted` at egress; `:large` paths surface as `:rf/large` summaries. **Additively merges** with existing marks. See [08 — Schemas](08-schemas.md#data-classification).

### `set-marks`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-marks frame-id {path mark, ...})
  ```
- **Description**: Frame-scoped path-marks. **Wholesale replaces** the frame's prior mark-set (paths not mentioned are cleared). Schema-attached marks are preserved either way.

### `reg-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-flow flow)
  (reg-flow flow opts)
  ```
- **Description**: Register a derived flow — `{:id :inputs :output :path}` — that auto-recomputes when its inputs change and writes the result into `:path` in `app-db`. See [05 — Flows](05-flows.md).
- **Example**:
  ```clojure
  (rf/reg-flow
    {:id     :cart/total
     :inputs {:items [:cart :items]}
     :output (fn [{:keys [items]}] (reduce + (map :price items)))
     :path   [:cart :total]})
  ```

### `reg-route`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-route id metadata)
  ```
- **Description**: Register a route as data: `:path`, `:params`, `:query`, `:on-match`, `:on-error`, `:can-leave`. See [06 — Routing](06-routing.md).
- **Example**:
  ```clojure
  (rf/reg-route :route/home
    {:path     "/"
     :on-match [[:home/load]]})
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
- **Description**: Deregisters the flow from the named frame and `dissoc-in`s its `:path` from that frame's `app-db` only. See [05 — Flows](05-flows.md).

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

- [02 — Views](02-views.md) for `reg-view*` in detail, the `view` lookup form, and the substrate-agnostic ergonomic surface (`dispatcher`, `subscriber`, `with-frame`).
- [03 — Effects and interceptors](03-effects.md) for what the `reg-event-fx` handler's return value can carry.
- [12 — Registrar](12-registrar.md) for the read-side of the registrar — `registrations`, `handler-ids`, `handler-meta`.

## Dispatch and subscribe

These are the two verbs that drive the cascade. `dispatch` says "an event happened, run it through the cascade"; `subscribe` says "give me a reactive handle on this query's value." Every other surface in re-frame2 either composes them or sits beside them.

Both come in macro + fn pairs. The **macro** form (`dispatch`, `dispatch-sync`, `subscribe`) captures the call-site source coords so tools like re-frame-10x and Causa can navigate from a trace event back to the originating expression. The **`*` fn** form (`dispatch*`, `dispatch-sync*`, `subscribe*`) skips the stamping — needed when you compose dispatch through a higher-order function (`(map dispatch* events)`) where a macro can't sit. Both route through the same dispatcher; only the trace stamping differs.

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
  (subscribe frame-id query-v)
  ```
- **Description**: The reactive handle. Returns a reaction whose value is the registered sub's current output; recomputes when upstreams change. Use inside views, inside other subs, and (carefully) inside event handlers via the cofx wrapper.
- **Example**:
  ```clojure
  [:span @(rf/subscribe [:counter/value])]
  ```
- **In the wild**: [counter](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter)

### `subscribe*`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe* query-v)
  (subscribe* frame-id query-v)
  ```
- **Description**: Fn variant of `subscribe`.

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once frame-id query-v) → value
  ```
- **Description**: One-shot read: subscribe, deref, immediately unsubscribe. Use in handler bodies, machine actions, REPL — anywhere you want the *current* value without the reactive plumbing. Not for views.

### `unsubscribe`

- **Kind**: function
- **Signature**:
  ```clojure
  (unsubscribe query-v) → nil
  (unsubscribe frame-id query-v) → nil
  ```
- **Description**: Decrement the cache ref-count for a query. When the count hits zero, disposal is scheduled after the configured `:sub-cache` grace-period. Most callers don't reach for this directly — Reagent / UIx / Helix adapters wire it on unmount.

### `sub-machine`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub-machine machine-id) → reaction over snapshot
  ```
- **Description**: Sugar over `(subscribe [:rf/machine machine-id])`. See [04 — Machines](04-machines.md).

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

**Named-target sugar** (`dispatch-to-system`, per [04 — Machines](04-machines.md)). The question is "do you have a `:system-id` instead of a target machine-id?" `dispatch-to-system` resolves through the per-frame `[:rf/system-ids]` reverse index and then calls `dispatch`. It's *not* a different kind of dispatch — it's named-addressing sugar on top of the same dispatcher.

The two sub-families compose: `dispatch-to-system` ultimately calls `dispatch`, so the same trace stamping fires (at the `dispatch-to-system` invocation, since that's the macro you wrote).

### See also

- [02 — Views](02-views.md) — `dispatcher` and `subscriber` capture the current frame at call time and return frame-bound fns that survive callbacks where the dynamic-var binding has unwound.
- [03 — Effects and interceptors](03-effects.md) — the effect map's `:fx` vector is how event handlers schedule more dispatches.

## Frames: the scoping primitive

A frame is the scoping unit for `app-db`, the event queue, and the cascade. Most apps have exactly one frame (the default, `:rf/default`, autocreated on `init!`). Apps that need isolation between subsystems — embedded widgets, multi-tab pair tools, the SSR per-request runtime — declare additional frames and dispatch / subscribe against them via `{:frame :other}`.

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

Process-level data knobs live behind `(rf/configure <key> <opts>)`. The vocabulary of keys is closed-and-additive — existing keys cannot be renamed; new keys are added by extending the table. Currently four keys ship:

| Key | Opts | Default | Status | What it tunes |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn fn}` | `{:depth 50, :redact-fn nil}` | v1 (dev-only) | Per-frame epoch ring depth (the time-travel buffer), trace-event retention cap per record, and an optional redactor invoked once per assembled record so ring and listeners see the same shape. |
| `:trace-buffer` | `{:depth N}` | `{:depth 200}` | v1 (dev-only) | The dev-only trace event ring depth. 0 disables. |
| `:sub-cache` | `{:grace-period-ms N}` | `{:grace-period-ms 50}` | v1 | How long the sub-cache holds a ref-count-zero query before disposing. 0 selects synchronous disposal — usually surprising; the default exists so transient unmount/remount sequences don't thrash. |
| `:elision` | `{:rf.size/threshold-bytes N}` | `{:rf.size/threshold-bytes 16384}` | v1 | The size threshold above which `elide-wire-value` substitutes a `:rf.size/large-elided` marker. 0 disables runtime auto-detect (only declared / schema entries elide). See [11 — Instrumentation](11-instrumentation.md). |

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it's per-frame metadata on the frame's `:ssr` map, because different frames in the same process can carry different projector settings.

### Opts-key naming rule

The opts map deliberately mixes two key shapes:

- **Framework-owned semantic sub-keys use a namespaced keyword** — `:rf.size/threshold-bytes`. The namespace identifies the cross-spec policy area; the same key shape appears verbatim wherever that policy is consumed (here under `:elision`, but also as a per-call opt to `elide-wire-value`).
- **Ergonomic per-knob sub-keys are unqualified bare keywords** — `:depth`, `:grace-period-ms`, `:trace-events-keep`. Local to a single `configure` key; no cross-surface identity to encode.

The discriminator is whether the sub-key names a cross-surface contract or a one-off knob. The rule is closed — there's no third shape.

### See also

- [03 — Effects and interceptors](03-effects.md) — `with-fx-overrides` and the per-call `:fx-overrides` envelope are the *other* configuration surfaces (per-frame metadata is the third).
- [13 — Lifecycle](13-lifecycle.md) — `init!` / `install-adapter!` / `destroy-adapter!` set up and tear down the running process.
