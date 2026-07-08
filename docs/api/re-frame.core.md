# re-frame.core

`re-frame.core` is the single namespace every re-frame2 app requires — the facade. It re-exports the registration verbs, the pipeline verbs, the view surface, the effect/interceptor surface, the frame primitive, the boot/configure lane, the instrumentation and registrar-query reads, and every optional feature's registration macro.

```clojure
(:require [re-frame.core :as rf])
```

Core surfaces are documented in full below. Feature surfaces (machines, routing, flows, schemas, SSR, HTTP, resources) are re-exported here for single-import ergonomics; each has a brief entry with a pointer to its own namespace doc, which carries the deep contract. For the mental model, see the [Subscriptions](../core/subscriptions.md), [Frames](../core/frames.md), and [Effects](../core/effects.md) concept guides.

## Registration

Every entry here registers a named handler into the frame's registrar.

**Return value.** Every `reg-*` returns its primary id — the keyword (or path, for `reg-app-schema`) you registered with.

### `reg-event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event id ?metadata handler)
  ```
- **Description**: The one public event-registration form. The handler is `(fn [coeffects event-vec] effect-map)` — a coeffects map in, a **closed** effects map `{:db ... :fx [...]}` out (or `nil` for a no-op). The db write is an explicit `:db` effect; there is no db-only return shape.
- **Metadata map (extended form)**: the optional middle slot carries reflection keys (`:doc`, `:schema`, `:tags`, …) and a reserved `:interceptors` vector:
  ```clojure
  (rf/reg-event :cart/add
    {:doc "Add an item." :interceptors [undoable]}
    (fn [{:keys [db]} [_ item]] {:db (update db :items conj item)}))
  ```
  > **Migration note.** The bare positional interceptor middle slot — `(reg-event :id [undoable] handler)` — has been removed. Put interceptor chains in the metadata map: `(reg-event :id {:interceptors [undoable]} handler)`.
- **Raw-context handlers**: there is no separate registrar for them. To read or rewrite the interceptor context, register a named interceptor with `(rf/reg-interceptor :my/audit {:before ... :after ...})` and reference it by id from the `:interceptors` vector (`{:interceptors [:my/audit]}`). Its `:before` / `:after` fns receive and return the context map directly. (`->interceptor` is the framework-internal lowering constructor, not the authoring form.)
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

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata input-fn? computation-fn)
  ```
- **Description**: A computed view over `app-db` and other subs. Three input-production modes (see table): a layer-1 `app-db` reader has no input producer, `:<-` is a literal producer, and a parametric `input-fn` computes inputs from the outer `query-v`.

  The optional first fn is the v2 **`input-fn`** — a *pure* function from the outer `query-v` to a **vector of query vectors**. It must not call `subscribe`, deref `app-db`, dispatch, or perform IO, and must not return live reactions (it is not a v1 reaction-returning signal fn). The runtime resolves each returned query vector in the *same frame* as the outer subscription.

  This is the only sub-registration form in v2; `reg-sub-raw` is gone (see the [migration reference](../../migration/from-re-frame-v1/README.md)). Full input grammar and error ids: [Subscriptions concept guide](../core/subscriptions.md).

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

### `reg-fx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-fx id ?metadata handler)
  ```
- **Description**: Define a named side effect. The handler is **binary**, context-first: `(fn [ctx args] ...)` (changed from v1's unary shape).
  - `ctx` is a small map carrying `:frame` (the active frame id), `:event` (the originating event vector), and `:envelope` (the parent dispatch envelope).
  - `args` is the value the event handler placed beside the fx-id in its `:fx` vector.
  - Accepts a `:platforms` metadata key (a set of `:server` / `:client`) that gates fx execution by active platform — see [re-frame.ssr.md](re-frame.ssr.md).
- **Example**:
  ```clojure
  (rf/reg-fx :app/scroll-to-top
    (fn [_ctx _args] (js/window.scrollTo 0 0)))
  ```

### `reg-cofx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-cofx id ?metadata supplier)
  ```
- **Description**: Register a named supplier for a world fact a handler can ask for. The supplier is a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the call site — not a context-mutating fn. The runtime calls it and puts the result into the coeffects map under the cofx's id.
  - A handler opts in with `:rf.cofx/requires` registration metadata. v2 has no `inject-cofx` interceptor.
  - The middle slot carries the fact's grade: `{:recordable? true}` for a replayable fact; `{:recordable? true :provided? true}` for a recordable fact stamped by an owner boundary (no generator); a bare registration for an ambient (unrecorded) read.
  - Reading a sub from a handler is done the same way — wrap `subscribe-once` in a cofx and declare it.
  - Full model: [Effects](../core/effects.md), [Coeffects](../core/coeffects.md).
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

### `reg-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-frame id metadata)
  ```
- **Description**: Atomic create-and-register. A frame is the scoping unit — one `app-db`, one event queue, one pipeline — minted with metadata you later read via `frame-meta`. The frame owns the `:observability` sink policy.
  - Durable `app-db` data classification is **not** a frame annotation: a `reg-frame` config carrying `:sensitive` / `:large` **fails loud** at registration.
  - Classify durable `app-db` paths by returning the four commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) from a `reg-event` alongside `:db`, wired to run at frame creation via `:initial-events`.
  - See [re-frame.http.md](re-frame.http.md), [re-frame.schemas.md](re-frame.schemas.md), and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
- **Example**:
  ```clojure
  ;; User-defined fxs sit under a user-feature prefix per Conventions — never
  ;; under `:rf.<feature>/…`, which is reserved for framework-owned surfaces.
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

### Clearing registrations

Each `clear-*` removes an entry from the registrar; the no-arg form clears the whole kind. Used in tests (the `with-fresh-registrar` fixture relies on these), REPL workflows, and teardown.

### `clear-event`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-event)
  (clear-event id)
  ```
- **Description**: Forget one event-handler. No-arg clears the whole `:event` registry.
- **Example**:
  ```clojure
  (rf/clear-event :counter/inc)   ;; forget one handler
  (rf/clear-event)                ;; forget every registered :event
  ```

### `clear-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sub)
  (clear-sub id)
  ```
- **Description**: Forget one sub. This is the registrar-side clear (the inverse of `reg-sub`); the runtime cache decrement is `unsubscribe`.
- **Example**:
  ```clojure
  (rf/clear-sub :counter/value)   ;; forget one sub registration
  (rf/clear-sub)                  ;; forget every registered :sub
  ```

### `clear-fx`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-fx)
  (clear-fx id)
  ```
- **Description**: Forget one fx. No-arg clears the whole `:fx` registry.
- **Example**:
  ```clojure
  (rf/clear-fx :app/scroll-to-top)   ;; forget one fx
  (rf/clear-fx)                      ;; forget every registered :fx
  ```

## Dispatch and subscribe

These are the two verbs that drive the pipeline.

`dispatch`, `dispatch-sync`, and `subscribe` are macro-in-call-position / fn-in-value-position on CLJS (Convention A — the same pattern `reg-event` / `reg-sub` / etc. use). In call position the **macro** form captures the call-site source coords so tools like Xray can navigate from a trace event back to the originating expression. In value position — an argument, a `let`-binding, `(or dispatch-fn rf/dispatch)` — the SAME name resolves to a plain-fn value instead, which composes through a higher-order function (`(map dispatch events)`) where a macro can't sit; that path skips the stamping. Both route through the same dispatcher; only the trace stamping differs. There is no `*`-suffixed twin.

**The `opts` map.** `dispatch` and `subscribe` accept a uniform opts map: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Target a non-default frame via `(rf/dispatch [::save x] {:frame :todo})`; the frame **id** is the public routing address.

### `dispatch`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch event)
  (dispatch event opts)
  ```
- **Description**: Async dispatch — drops the event onto the frame's queue and returns immediately. The default.
- **Example**:
  ```clojure
  [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]
  ```

- **Fn form**: in VALUE position (not called directly — e.g. `(run! rf/dispatch events)`), `dispatch` resolves to the plain-fn value instead of expanding as a macro; it skips call-site stamping, so it composes through `map` / `comp` / `partial`. `opts` may carry `:frame` (a frame-id keyword or a live frame value). Returns `nil`. (A JVM programmatic caller reaches `re-frame.router/dispatch!` directly.)

### `dispatch-sync`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch-sync event)
  (dispatch-sync event opts)
  ```
- **Description**: Synchronous dispatch — drains to completion before returning. For tests, REPL workflows, and one-shot app-boot events. Never call from inside a running event handler — that raises `:rf.error/dispatch-sync-in-handler`.
- **Example**:
  ```clojure
  (rf/dispatch-sync [:counter/initialise])   ;; one-shot app-boot event
  ```

- **Fn form**: in VALUE position, `dispatch-sync` resolves to the plain-fn value. Mirrors `dispatch`'s value-position forms — `opts` may carry `:frame` (a frame-id keyword or a live frame value).
  ```clojure
  ;; Fn-form — drive a sequence of events synchronously from runner / test code.
  (doseq [evec events]
    (rf/dispatch-sync evec {:frame :app/main}))
  ```
  (A JVM programmatic caller reaches `re-frame.router/dispatch-sync!` directly.)

### `subscribe`

- **Kind**: macro
- **Signature**:
  ```clojure
  (subscribe query-v)
  (subscribe query-v opts)
  ```
- **Description**: The reactive handle. Returns a reaction whose value is the registered sub's current output; recomputes when upstreams change. Valid inside views, inside other subs, and (via the cofx wrapper) inside event handlers.
  - Target a non-ambient frame via the `{:frame …}` opt — `(rf/subscribe [:counter/value] {:frame :other})`; `:other` may be a frame-id keyword or a live frame value.
  - In VALUE position, `subscribe` resolves to the plain-fn value (Convention A) for HoF / programmatic reads — no call-site capture. A JVM programmatic caller reaches `re-frame.subs/subscribe` directly.
- **Example**:
  ```clojure
  [:span @(rf/subscribe [:counter/value])]
  ```

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once query-v opts) → value
  ```
- **Description**: One-shot read: subscribe, deref, immediately unsubscribe. Returns the *current* value with no reactive handle retained. For handler bodies and the REPL — not views, and **not** machine callbacks (a machine `:guard` / `:action` / `:entry` / `:exit` MUST NOT call `subscribe-once`; it takes host facts as recorded coeffects, incl. the machines-only `{:rf/sub …}` source — see [re-frame.machines](re-frame.machines.md)). Target a non-ambient frame via `(subscribe-once query-v {:frame f})`, mirroring `subscribe`.
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
  (unsubscribe frame-id query-v) → nil
  ```
- **Description**: Decrement the cache ref-count for a query. When the count hits zero, the entry is disposed **synchronously** — see [Subscriptions](../core/subscriptions.md). The Reagent / UIx / Helix adapters wire it on unmount; most callers never reach for it directly. Target a non-ambient frame with the frame-first `(unsubscribe frame-id query-v)` form — unlike `subscribe` / `subscribe-once`, `unsubscribe` has no `{:frame …}` opts form (it is pure teardown, never a hot in-view call).
- **Example**:
  ```clojure
  ;; Manual ref-count pairing (tests / REPL) — balances an explicit subscribe.
  (let [r (rf/subscribe [:counter/value])]
    @r
    (rf/unsubscribe [:counter/value]))
  ```

### `clear-sub-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sub-cache!)
  (clear-sub-cache! frame-id)
  ```
- **Description**: Dispose every cached entry in a frame's runtime sub-cache and clear the cache; disposal is synchronous and unconditional. The no-arg form targets the **ambient** frame (raising `:rf.error/no-frame-context` under no established scope); the one-arg form names the frame. Tests; rarely needed in app code.
- **Example**:
  ```clojure
  (rf/clear-sub-cache! :app/main)   ;; evict one frame's cached subs
  (rf/clear-sub-cache!)             ;; current frame (test / REPL teardown)
  ```

### `compute-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (compute-sub query-v db) → value
  ```
- **Description**: The test-friendly companion to `subscribe`. Runs the sub graph against a **value** of `app-db` — no cache, no reactivity, no frame — and returns the value. Query-vector first, db second.
  - `db` may be a bare app-db map, or a full frame-state value (`{:rf.db/app … :rf.db/runtime …}`) when the graph mixes app-db and runtime-db subs.
  - JVM-runnable.
- **Example**:
  ```clojure
  ;; Evaluate :counter/doubled against a literal app-db value (no frame, no cache).
  (rf/compute-sub [:counter/doubled] {:counter/value 21})   ;; => 42
  ```

### Standard events (keyword surface)

The framework ships a small, fixed set of standard `:rf/*` events you dispatch like any other. They are framework-owned: the `:rf/*` single-root namespace is reserved, so re-registering one with `reg-event` is a loud reserved-id collision (`:rf.error/reserved-event-id`).

#### `:rf/set-db`

- **Kind**: standard event
- **Shape**:
  ```clojure
  [:rf/set-db new-db-map]
  ```
- **Description**: The framework-standard `app-db` seeding event. **Replaces** the whole `app-db` partition with the supplied map (a replace, not a merge). Rides the normal post-commit path — schema validation, rollback, trace emission, epoch recording — so seeding is an ordinary traceable event, not a privileged direct write. Returns `{:db new-db}` from a pure handler, so it touches only the `app-db` partition, never runtime-db.
- **Validation**: takes **exactly one map argument**. A missing / `nil` / non-map argument, or any extra trailing arg (`[:rf/set-db {} :junk]`), throws `:rf.error/set-db-bad-value`. Empty `app-db` is `[:rf/set-db {}]`.
- **Example**:
  ```clojure
  ;; seed app-db at frame creation
  [rf/frame-provider {:images         [counter-image]
                      :initial-events [[:rf/set-db {:count 0}]]}
   [counter-view]]

  ;; or dispatch it directly to reset app-db to a known shape
  (rf/dispatch [:rf/set-db {:count 0 :user nil}])
  ```

## Views

The view layer is **substrate-agnostic** — the shared dataflow (frames, subscriptions, dispatch, source metadata, registry ids) is uniform across Reagent, UIx, and Helix, and the same `capture-frame` carry primitive composes across all three. The substrate-specific hooks (`use-subscribe`, `wrap-view`, …) live in each adapter's namespace doc ([re-frame.adapter.reagent.md](re-frame.adapter.reagent.md) / [re-frame.adapter.uix.md](re-frame.adapter.uix.md) / [re-frame.adapter.helix.md](re-frame.adapter.helix.md)).

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+)
  (reg-view sym docstring [args] body+)
  (reg-view ^{:rf/id :explicit/id} sym [args] body+)
  ```
- **Description**: The `defn`-shape view registration — the app-facing lane. It auto-defs the symbol, auto-derives the id from `(keyword *ns* sym)`, auto-injects `dispatch` / `subscribe` as lexical bindings, and rejects non-defn-shape bodies at macroexpand. In all shapes the symbol is `def`-ed, so sibling code can write `[my-view item]`. Render an app-facing view by **Var reference** or `(rf/view id)` — bare keyword-tagged hiccup `[:my-view "args"]` is rejected (see [Spec 004 §Resolved decisions](../../spec/004-Views.md#bare-my-view-args-in-raw-hiccup-is-rejected)).
- **Example**:
  ```clojure
  (rf/reg-view counter-buttons []
    [:div
     [:button {:on-click #(dispatch [:counter/dec])} "-"]
     [:span @(subscribe [:counter/value])]
     [:button {:on-click #(dispatch [:counter/inc])} "+"]])
  ```

### `reg-view*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-view* id render-fn)
  (reg-view* id metadata render-fn)
  ```
- **Description**: The plain-fn surface beneath `reg-view` — the tooling / host lane. No auto-def, no auto-inject, no compile check. Reach for it when:
  - the id is **computed** (code-gen pipelines, plugin systems, story scaffolding);
  - you **don't want a Var** (inside a `let` or closure);
  - you are writing a **Form-3 component** (`(rf/reg-view* :id (r/create-class {...}))` — the one app-facing reason to touch the starred form);
  - you are writing **consumer-side library code** that registers without imposing a `def`.

  There is no auto-injected `dispatch` / `subscribe` in a `reg-view*` body; capture a `(rf/capture-frame)` at render and use its ops if the view needs frame-bound dispatch.
- **Example**:
  ```clojure
  ;; Computed id — the id isn't a literal symbol at the call site.
  (defn register-panel! [view-id render-fn]
    (rf/reg-view* view-id render-fn))

  ;; Form-3 — create-class isn't defn-shaped, so it registers through the
  ;; starred form. Capture the frame at render so lifecycle callbacks dispatch
  ;; to the captured frame.
  (rf/reg-view* :editor/page
    (fn [_]
      (let [{:keys [dispatch]} (rf/capture-frame)]
        (r/create-class
          {:component-did-mount (fn [_] (dispatch [:editor/mounted]))
           :reagent-render      (fn [] [editor-form-view])}))))
  ```

### `view`

- **Kind**: function
- **Signature**:
  ```clojure
  (view view-id) → render-fn
  ```
- **Description**: Runtime lookup handle. Returns the **registered render-fn** (or `nil` when the id is not registered), not hiccup. Use in hiccup as `[(rf/view :id) args...]` to late-bind a view by id — a host that resolves a stored view id, plugin-style dispatch, dynamic chrome. The Var form (`[my-view args]`) is the app-facing default; this lookup form is for the case where the caller holds the id, not the symbol.
- **Example**:
  ```clojure
  [(rf/view :app/header) {:title "Cart"}]   ;; resolves the registered render-fn at render time
  ```

### `frame-provider`

- **Kind**: Reagent component (one component, two config shapes)
- **Signatures**:
  ```clojure
  [rf/frame-provider {:frame :todo} & children]                     ;; SCOPE: scope an existing frame
  [rf/frame-provider {:id :todo :images [todo-image]} & children]   ;; ENSURE: create-if-absent / reuse
  ```
- **Description**: One component, two config shapes chosen by the prop map. See the [frame-provider glossary entry](../core/glossary.md#frame-provider).
  - **`{:frame existing-id}` — SCOPE.** Scopes a React subtree to a frame that **already exists**; creates / refreshes / destroys nothing. **Fails loud** (`:rf.error/frame-provider-frame-absent`) when the named frame is absent. `:frame` must be a keyword. The scope-into-React counterpart to `with-frame`.
  - **`{:id the-id …}` — ENSURE.** Creates the frame if absent (via `make-frame`, taking the same constructor opts: `:id` / `:images` / record-config incl. `:initial-events`), **reuses it without re-seeding** if present (an idempotent re-mount preserves durable state and does not replay `:initial-events`), and provides its id to descendants. There is **no destroy-on-unmount**. `:id` is required and must be a keyword. True ownership stays explicit: `make-frame` + `destroy-frame!` inside a `create-class`.
- **Example**:
  ```clojure
  ;; SCOPE — :todo already exists; this subtree just renders against it.
  (rf/reg-view todo-page []
    [rf/frame-provider {:frame :todo}
     [todo-list]])

  ;; ENSURE — bring the :todo frame into being for as long as this subtree is
  ;; mounted: created on first mount, reused without re-seeding on remount.
  (rf/reg-view todo-widget []
    [rf/frame-provider {:id :todo :images [todo-image]}
     [todo-list]])
  ```

### `capture-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (capture-frame)          → {:frame :dispatch :dispatch-sync :subscribe}
  (capture-frame frame-id) → {:frame :dispatch :dispatch-sync :subscribe}
  ```
- **Description**: Captures the active frame at CREATION time and returns an **operation bundle** whose `:dispatch` / `:dispatch-sync` / `:subscribe` ops always target the captured frame — they survive async boundaries (`Promise.then`, `setTimeout`, WebSocket `onmessage`, observer callbacks) where the ambient frame lookup would have unwound.
  - The handle is *locked* to one frame: a per-call `:frame` opt MUST NOT override it.
  - It is an operation bundle, not a container — read the frame's app-db value via `(rf/app-db-value (:frame handle))`, not the handle itself.
  - Full async-boundary contract: [Frames — the async boundary](../core/frames.md).
- **Example**:
  ```clojure
  (rf/reg-view stream-view []
    (let [{:keys [dispatch]} (rf/capture-frame)]          ;; captures the render frame
      (ws/subscribe! (fn [msg] (dispatch [::incoming msg]))) ;; fires LATER, but bound
      [:div "streaming…"]))
  ```

### `make-capture-frame`

- **Kind**: function (internal — `:tier :implementation`, EP-0024)
- **Signature**:
  ```clojure
  (make-capture-frame frame)
  (make-capture-frame frame opts)
  ```
- **Description**: **Not an app-facing surface** — the internal constructor behind `capture-frame` and the `reg-view` injection sugar. It is a public Var only so the `reg-view` macro's emitted body can reference it fully-qualified. Call `capture-frame` instead.

### `with-frame` / `with-new-frame`

- **Kind**: macros (a sibling pair)
- **Signatures**:
  ```clojure
  (with-frame :keyword body)        ;; pin *current-frame* to an existing frame-id
  (with-new-frame [sym expr] body)  ;; eval expr, bind id to sym, run, destroy on exit
  ```
- **Description**: The two **lexical** (non-React) frame-scoping macros — for regions that aren't a view tree (tests, the REPL, SSR). Each rejects the other's argument shape at compile time.
  - `with-frame` pins `*current-frame*` to an **existing** frame-id for the dynamic extent of `body`, creating and destroying nothing. The lexical counterpart to the `rf/frame-provider` `{:frame …}` SCOPE shape.
  - `with-new-frame` evaluates `expr`, binds the resulting frame target to `sym` (a live frame value from `make-frame`, or a keyword id from `reg-frame` — either is accepted downstream), runs `body` in that frame's dynamic context, and **destroys the frame on exit**. The throwaway-frame form for one-off harnesses.
- **Example**:
  ```clojure
  ;; Pin form — bind *current-frame* to an existing id for the body (most common)
  (rf/with-frame :todo
    (rf/dispatch-sync [:todo/add {:text "milk"}]))

  ;; Eval-bind-run-destroy — a throwaway frame for one test, torn down on exit.
  (rf/with-new-frame [f (rf/make-frame {:images [todo-image]})]
    (rf/dispatch-sync [:rf/set-db {:todos []}])         ;; seed via a setup dispatch
    (rf/dispatch-sync [:todo/add {:text "milk"}])
    (is (= 1 (count (:todos (rf/app-db-value f))))))    ;; frame destroyed on exit
  ```

## Effects and interceptors

The effect map is what an event handler returns; the interceptor chain runs before and after the handler. Handlers stay pure (they return descriptions of effects, not the effects themselves), and the runtime actions those descriptions at exactly one point.

The effect map is **closed**: app handlers return `:db` + `:fx` only (a third reserved top-level key, `:rf.db/runtime`, exists for framework / runtime-extension authority — never for app handlers). `:db` is the new `app-db` value (replaced in the commit phase); `:fx` is a vector of `[fx-id args]` pairs (`[fx-id]` is the no-args shorthand), each run by the runtime's fx walker against the registered `reg-fx` handler. Both reserved keys are covered by the [effect map](../core/glossary.md#effect-map) glossary entry.

### `reg-interceptor`

- **Kind**: macro (with a same-name plain-fn value on CLJS, Convention A — no `*`-suffixed twin; a JVM programmatic caller reaches `re-frame.interceptor-registry/reg-interceptor*` directly)
- **Signature**:
  ```clojure
  (reg-interceptor id {:keys [before after]})
  ```
- **Description**: The public custom-interceptor authoring form. Register a named interceptor descriptor, then **reference it by id** from a `reg-event` / `reg-frame` `:interceptors` vector. Descriptor shapes:
  - `{:before f}` / `{:after f}` / `{:before f :after g}`;
  - `{:factory f}` for a parameterized family (`f` receives one arg and returns a descriptor; referenced as `[id arg]` — the standard `[:rf.interceptor/path …]` is the canonical factory consumer).

  An optional middle slot carries the standard registration-metadata map (`:doc`, `:schema`, `:tags`, …). Use this for any work not covered by the standard interceptors — analytics, logging, validation, ad-hoc context manipulation. (`->interceptor` is the framework-internal lowering constructor that turns a descriptor into an executable chain entry; it must not appear directly in a public chain.)
- **Example**:
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

### `->interceptor`

- **Kind**: macro (internal lowering constructor, EP-0022)
- **Signature**:
  ```clojure
  (->interceptor & {:keys [id before after]})
  ```
- **Description**: **INTERNAL — not the public authoring form.** The framework-internal lowering constructor that turns a `{:before … :after …}` descriptor into an executable chain entry (capturing the definition-site `:source-coord` from `(meta &form)`). Application code registers interceptors with `reg-interceptor` and references them by id; `->interceptor` must not appear directly in a public `:interceptors` chain.

### `->interceptor*`

- **Kind**: function (internal, EP-0022)
- **Signature**:
  ```clojure
  (->interceptor* & {:keys [id before after]})
  ```
- **Description**: **INTERNAL.** The plain, runtime-callable fn form of the `->interceptor` macro (per Conventions `*`-suffix naming). HoF / programmatic / REPL callers reach this directly; it captures no source-coord. Not an authoring surface — use `reg-interceptor`.

### `with-fx-overrides`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-fx-overrides {fx-id -> override, …} body+)
  ```
- **Description**: For the duration of `body`, every `dispatch` / `dispatch-sync` merges this fx-overrides map into its envelope. Lexical scope; composes with `with-frame`.
  - The three override scopes compose with a clear precedence: **per-call** (`(rf/dispatch event {:fx-overrides {...}})`) wins, then **lexical** (`with-fx-overrides`), then **per-frame** (`(rf/reg-frame :todo {:fx-overrides {...}})`).
  - At the pattern level the override value is an **id**; the CLJS reference implementation also accepts a **fn** value for test wiring (the asymmetry is deliberate — ports that don't ship fn-valued overrides remain pattern-conformant).
- **Example**:
  ```clojure
  ;; Swap the real managed-HTTP fx for a canned-failure stub for the test body —
  ;; every dispatch inside inherits the override; it unwinds when the body exits.
  (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrong"}]))
  ```

### `validate-at-boundary-interceptor`

- **Kind**: Var (interceptor value) — schemas re-export
- **Signature**:
  ```clojure
  validate-at-boundary-interceptor
  ```
- **Description**: A **pre-built interceptor value**, not a fn, registered under the framework id `:rf.schema/at-boundary`. Reference it **by id** from a `reg-event` metadata map's `:interceptors` vector — `{:interceptors [:rf.schema/at-boundary]}`; chains are reference-only, so the Var itself never appears as an inline chain entry.
  - It forces `:schema` validation of the dispatched event vector even in production builds where dev-time validation is elided — for handlers that ingest data from outside the app's trust boundary (HTTP replies, websocket frames, postMessage).
  - **Do not call it as a fn** — invoking it raises `ArityException`.
  - Full contract: [re-frame.schemas.md](re-frame.schemas.md).
- **Example**:
  ```clojure
  (rf/reg-event ::receive-from-server
    {:interceptors [:rf.schema/at-boundary]}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :data payload)}))
  ```

### Standard fx and interceptor (keyword surface)

The framework reserves a few `:fx` entries and one standard interceptor reference. User code registers its own fx-ids via `reg-fx`. Feature-owned fx (`[:rf.http/managed …]`, `[:rf.nav/push-url …]`, `[:rf.machine/spawn …]`, `[:rf.fx/reg-flow …]`, `[:rf.server/* …]`, …) live in their feature namespace docs.

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:dispatch event-vec]` | event vector | v1 | Schedule this event on the same queue. Async — runs after the current run completes. |
| `[:dispatch-later {:ms ms :event event-vec}]` | options map | v1 | Schedule this event after N ms. |

#### `[:rf.interceptor/path <path-vector>]`

- **Kind**: interceptor **reference** (the one standard interceptor)
- **Form**:
  ```clojure
  {:interceptors [[:rf.interceptor/path [:cart :items]]]}
  ```
- **Description**: Focus the handler on an `app-db` sub-slice. `:before` stages the focused slice as `:db` (`(get-in db path)`); `:after` widens the returned slice back into full app-db. The handler sees and returns a sub-tree, not the full db.
  - Preserves the frame-commit `identical?` no-op (an unchanged focused slice widens back to the original app-db object, not an `assoc-in` allocation).
  - A non-vector / malformed path arg raises `:rf.error/path-interceptor-bad-path`.
  - It is a **reference**, not a constructed value — there is no public `path` fn (a stale `rf/path` call raises `:rf.error/path-removed`).

## Frames

A frame is the scoping unit for `app-db`, the event queue, and the pipeline. Most apps have exactly one frame, established at the root with `rf/frame-provider`. `init!` does **not** create one for you — frame identity is carried, not synthesised from absence (see [Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found)). Apps that need isolation between subsystems register additional frames and dispatch / subscribe against them via `{:frame :other}`.

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) ; → live frame value
  (make-frame opts descriptors) ; → live frame value (explicit descriptor pool — tests / harnesses)
  ```
- **Description**: The single public constructor for a live frame. It accepts image-selection *and* frame-configuration options in one call and **returns the live frame value** — one frame value backed by one registry. For per-mount lifecycles: devcards, modal stacks, multiple live widget instances, dynamic tabs, tests, and the SSR per-request frame pattern.
  - `opts` must be a map — a non-map (including `nil`) raises `:rf.error/make-frame-bad-opts`; a non-vector `:images` raises `:rf.error/make-frame-bad-images`.
  - Image-selection opts: `:images` (always a vector); `:id` (optional — registers the frame in the one process-local live-frame registry; a duplicate live id is **idempotent replacement** that preserves durable state on re-mount); `:adapter`.
  - Frame-configuration opts (same call): `:initial-events` (a vector of event vectors dispatched into the new frame at creation), `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`.
  - **Pass the value directly — no accessor needed:** `dispatch` / `subscribe` / `destroy-frame!` / `app-db-value` / `frame-provider` all accept the frame value OR its id interchangeably (API-shrink #1, rf2-csbbwu — there is no separate value→id accessor to reach for).
  - Lifecycle is the caller's responsibility — pair a direct `make-frame` with a `destroy-frame!`, or use the UI-owned `rf/frame-provider` boundary.
  - See the [Frames concept guide](../core/frames.md) and [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md).
- **Example**:
  ```clojure
  ;; A component that OWNS a frame lifetime uses the UI-owned provider,
  ;; which creates-on-mount and destroys-on-unmount for you (EP-0024):
  (defn counter-widget [label]
    [rf/frame-provider {:images         [counter-image]
                        :initial-events [[:rf/set-db {:count 0}]]}
     [counter-view label]])
  ```

### Resetting a frame — destroy + reg-frame

There is no dedicated "reset" function — `reset-frame!` was retired (rf2-lxwpob): a full replace is reproducible by composing the two lifecycle primitives, not a third verb. To fully replace a frame — tearing it down through the normative `destroy-frame!` boundary (running `:on-destroy`, releasing per-feature resources) and re-registering it fresh, so machine snapshots, the route slice, flows, and `app-db` are all rebuilt from the registered config — compose:

```clojure
;; Full frame replace (destroy + re-reg with the SAME config).
;; Must run OUTSIDE any handler run — e.g. a restart button's :on-click.
(rf/destroy-frame! :app/main)
(rf/reg-frame :app/main config)   ;; re-supply the SAME config you built the frame with
```

- For an image-loaded frame, re-supply the SAME `:images` vector too (via `make-frame`, not bare `reg-frame`) — otherwise the recreated frame degrades to the default image generation.
- To wipe just the `app-db` partition while keeping live runtime-db, reach for `(replace-frame-state! frame-id {:rf.db/app {}})` instead.
- There is **no** `:initial-db` config key — seeding `app-db` is itself an ordinary, traceable event, `[:rf/set-db {…}]`.
- **Not atomic** across a handler-cascade boundary (unlike the retired `reset-frame!`): `destroy-frame!` has no handler-scope guard, so calling it from inside a handler destroys the frame before the following `reg-frame` hits its own construction-in-handler guard and throws, leaving the frame destroyed-and-not-reconstructed. Frame construction/destruction are already top-level/view-only operations, so this only bites a call site that was already violating that rule.

### `destroy-frame!`

- **Kind**: function
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

### `current-frame-id`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-frame-id) → keyword
  ```
- **Description**: Return the active frame id the in-effect scope carries — the dynamic `*current-frame*` stamp or a React-context `frame-provider` scope (CLJS only). The context **reader** form; frame-scoped, requires a scope. There is **no `:rf/default` floor** — called under no established scope it raises `:rf.error/no-frame-context` (EP-0002).

### `frame-generation`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (frame-generation frame-target) → generation map
  ```
- **Description**: Return the SEALED, resolved image **generation** a live frame is running — the inert image-assembly generation it resolves `(kind, id)` lookups through. The raw read over the EP-0023 frame→generation model, for tools (Pair MCP `describe-image`, Xray).
  - `frame-target` is a registered frame id **or** a direct live frame object.
  - Returns the generation map with stable public keys: `:rf.gen/resolver` (the sealed `[kind id]` map), `:rf.gen/images`, `:rf.gen/kinds`.
  - **Fails loud** (`:rf.error/frame-no-generation`) when `frame-target` does not resolve to a live frame carrying a generation.

### `frame-shadows`

- **Kind**: function (EP-0026)
- **Signature**:
  ```clojure
  (frame-shadows frame-target) → vector of shadow entries
  ```
- **Description**: Return the cross-image **shadow report** for the image composition a live frame is running — what a LATER image overrode in an EARLIER one. The public accessor for the report a frame's resolved generation carries at `:rf.gen/shadows`.
  - `frame-target` is a registered frame id or a direct live frame value.
  - Returns a flat vector, one entry per cross-image shadow (`{:registration [kind id] :image <defined-in> :shadowed-by <winner>}`); an **empty** vector when no later image shadowed an earlier one.

### `image`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (image spec) → image value
  ```
- **Description**: Construct an **image** value — a selected registration-set value, as inert data (EP-0023 / EP-0026). Pure — no registrar, no side effect. The result is the assembled registration set a frame resolves against (passed to `make-frame` / `frame-provider` under `:images`). `spec` carries exactly three public keys:
  - `:id` (optional);
  - `:select-ns` (an `{:include [globs] :exclude [globs]}` selection map over registered descriptors' provenance namespaces);
  - `:registrations` (inline registrar-keyed sections).

### Image hot-reload — re-`make-frame`

There is no dedicated "reload" function — `reload-images!` was folded into re-construction (rf2-lxwpob). Re-calling `make-frame` against the SAME `:id` with a NEW `:images` vector re-seals the frame's `(kind, id)` resolver after handler/sub/view source changes, preserving frame memory — `reg-frame`'s surgical-update path swaps only the generation; app-db, runtime-db, caches, and lifecycle continue unchanged:

```clojure
(rf/make-frame {:id :my/frame :images [new-image]})
```

- Composition-replacing: it replaces the whole `:images` vector, not one member; a non-vector raises `:rf.error/make-frame-bad-images`.
- Frame-targeted: the swap affects only the named frame's generation, not siblings sharing an image.
- To read the diff the old `reload-images!` report carried, call `frame-generation` before and after the call and diff the two values with `generation-diff`:

```clojure
(let [before (rf/frame-generation :my/frame)
      _      (rf/make-frame {:id :my/frame :images [new-image]})
      after  (rf/frame-generation :my/frame)]
  (rf/generation-diff before after))
  ;; => {:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}
```

### `generation-diff`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (generation-diff before after) → diff map
  ```
- **Description**: A PURE diff between two sealed image generations. Replaces the report the retired `reload-images!` verb used to return — read `frame-generation` before/after a re-`make-frame` reload and diff the two.
  - Returns `{:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}`.

## Lifecycle and configure

The surfaces that bring a re-frame2 process up and take it down. The one-line boot sentence: **install an adapter with `init!`, then create your frame(s) explicitly.** The adapter-author surfaces (install / dispose / inspect) sit one layer down; an ordinary app never touches them.

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map)
  ```
- **Description**: The idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; require the ns and pass the Var, e.g. `(rf/init! reagent-adapter/adapter)`.
  - `(init!)` with no args raises `ArityException` at compile / load time; `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime.
  - Installs adapters and runtime capabilities only; does **not** create or guarantee any frame — register your app frame explicitly (`reg-frame`) and establish it at your root.
- **Example**:
  ```clojure
  (:require [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)
  ```

### `init-platform`

- **Kind**: function
- **Signature**:
  ```clojure
  (init-platform platform)   ;; :server | :client
  ```
- **Description**: Set the host-wide active-platform marker. The runtime tracks the active platform so `reg-fx` / `reg-cofx` `:platforms` metadata can gate execution.
  - CLJS hosts default to `:client`, JVM hosts to `:server`; call this at boot to override (e.g. a CLJS-on-Node SSR runtime sets `:server`; a JVM-runnable browser-simulating test sets `:client`).
  - Anything other than `:server` / `:client` raises `:rf.error/invalid-platform`. Idempotent / re-callable.
  - Per-frame `:config :platform` (set by the `:ssr-server` preset) is the finer-grained alternative and wins over the host-wide marker.
- **Example**:
  ```clojure
  (rf/init-platform :server)   ;; CLJS-on-Node SSR runtime
  ```

### `install-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-adapter! adapter-map)
  ```
- **Description**: Must be called before any frame is created. **Lower-level than `init!`**; ordinary apps call `init!` instead. Installs once — a second call without an intervening `destroy-adapter!` raises `:rf.error/adapter-already-installed`. Use it for a custom boot pipeline with steps between adapter-install and first-frame creation.
- **Example**:
  ```clojure
  (rf/install-adapter! reagent-adapter/adapter)   ;; seat the substrate (lower-level than init!)
  ;; …custom boot steps between adapter-install and first-frame creation…
  (rf/reg-frame :app/main {:initial-events [[:app/boot]]})
  ```

### `destroy-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-adapter!)
  ```
- **Description**: Tear down the installed adapter. Calls the adapter spec's `:dispose-adapter!` fn (if present), clears the install slot so a new adapter can install, and flips the `adapter-disposed?` breadcrumb. Symmetric with `install-adapter!` and with `destroy-frame!`.
- **Example**:
  ```clojure
  (rf/destroy-adapter!)                 ;; tear down the current substrate, clear the install slot
  (rf/init! reagent-adapter/adapter)    ;; …then install a fresh adapter (test fixture / hot-reload swap)
  ```

### `current-adapter`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter) → discriminator keyword
  ```
- **Description**: Which substrate is installed. Answers `:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom` — or `nil` when no adapter is installed. For predicate / branch code.
- **Example**:
  ```clojure
  (rf/current-adapter)   ;; => :rf.adapter/reagent   (nil when no adapter is installed)
  ```

### `current-adapter-spec`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter-spec) → installed adapter spec map
  ```
- **Description**: The adapter spec map passed to `(rf/init! ...)`, or `nil` when no adapter is installed. For tools / routing / identity checks across the install / dispose lifecycle. For the discriminator keyword, use `current-adapter`.
- **Example**:
  ```clojure
  (rf/current-adapter-spec)   ;; => the adapter spec map passed to (rf/init! …), or nil when none
  ```

### `adapter-disposed?`

- **Kind**: function
- **Signature**:
  ```clojure
  (adapter-disposed?) → boolean
  ```
- **Description**: Returns `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) AND after a fresh install. Read-only. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down).
- **Example**:
  ```clojure
  (rf/adapter-disposed?)               ;; => false  (fresh process — never installed)
  (rf/destroy-adapter!)
  (rf/adapter-disposed?)               ;; => true   (torn down, no reinstall yet)
  (rf/init! reagent-adapter/adapter)
  (rf/adapter-disposed?)               ;; => false  (a fresh install clears the breadcrumb)
  ```

### `configure!`

- **Kind**: function
- **Signature**:
  ```clojure
  (configure! config-map)
  ```
- **Description**: Process-level data knobs, typically set once at boot. One of three orthogonal configuration surfaces — `configure!` for process-level data knobs; the `set-!` / `install-!` setters for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides. The key vocabulary is closed-and-additive (existing keys cannot be renamed; new keys are added by extending the table). Three keys ship:

  | Key | Opts | Default | Status | What it tunes |
  |---|---|---|---|---|
  | `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn fn}` | `{:depth 50, :trace-events-keep 50, :redact-fn nil}` | v1 (dev-only) | Per-frame epoch ring depth, trace-event retention cap per record, and an optional projection-side redactor applied only at off-box egress (inside `projected-record`). |
  | `:trace-buffer` | `{:events-retained N}` | `{:events-retained 50}` | v1 (dev-only) | The dev-only per-frame trace ring's event-slot count — one slot per event, regardless of how many trace events its run emitted. 0 disables retention (the surface stays live). |
  | `:elision` | `{:rf.size/threshold-bytes N}` | `{:rf.size/threshold-bytes 16384}` | v1 | The size threshold above which `elide-wire-value` substitutes a `:rf.size/large-elided` marker. 0 disables runtime auto-detect. |

  There is **no `:sub-cache` knob** — sub-cache disposal is synchronous on derefer-count → 0. SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure!` key — it is per-frame metadata on the frame's `:ssr` map. Framework-owned semantic sub-keys use a namespaced keyword (`:rf.size/threshold-bytes`); ergonomic per-knob sub-keys are unqualified (`:depth`, `:trace-events-keep`, `:redact-fn`).
- **Example**:
  ```clojure
  (rf/configure! {:epoch-history {:depth 100}
                  :trace-buffer  {:events-retained 25}
                  :elision       {:rf.size/threshold-bytes 8192}})
  ```

### `feature-loaded?`

- **Kind**: function
- **Signature**:
  ```clojure
  (feature-loaded? feature) → boolean
  ```
- **Description**: Is the named optional feature's implementation artefact on the classpath? Detection is a pure keyword lookup in the always-loaded feature registry (no exception, no classpath probe). Probe `(feature-loaded? :routing)` before taking a feature-dependent path.
- **Example**:
  ```clojure
  (rf/feature-loaded? :epoch)   ;; => true when day8/re-frame2-epoch is on the classpath
  ```

### `features`

- **Kind**: function
- **Signature**:
  ```clojure
  (features) → {feature-keyword inspection-entry}
  ```
- **Description**: Return a map of every optional feature keyword to its inspection entry: the feature's static coordinate data (`:maven` / `:require` / `:spec`) merged with its live `:loaded?` status.
- **Example**:
  ```clojure
  (rf/features)
  ;; => {:epoch {:maven "day8/re-frame2-epoch" :require "re-frame.epoch" :loaded? true} …}
  ```

### `require-feature!`

- **Kind**: function
- **Signature**:
  ```clojure
  (require-feature! feature) → true (or throws)
  ```
- **Description**: Assert the optional feature is loaded. Returns `true` when its implementation artefact is on the classpath. Throws a structured `:rf.error/feature-not-loaded` ex-info carrying the exact copy-pasteable Maven coordinate + require form when it is not; an unknown feature keyword throws `:rf.error/unknown-feature`. Use as an early guard at the top of code that depends on an optional feature.
- **Example**:
  ```clojure
  (rf/require-feature! :epoch)   ;; absent => :rf.error/feature-not-loaded with copy-paste deps + require
  ```

## Instrumentation and listeners

Two surfaces stacked. The first is **dev-only**: a trace bus that emits one richly-tagged record per noteworthy event, buffered into a ring, fanned out to registered listeners synchronously, and elided entirely under `:advanced` + `goog.DEBUG=false`. The second is **always-on**: tight, production-survivable substrates (event-emit, error-emit) that deliver one record per processed event and one per `:rf.error/*` event. The epoch (time-travel) surfaces are dev-only and also available natively as [re-frame.epoch.md](re-frame.epoch.md). The complete error catalogue is normative in Spec 009.

### `register-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-listener! stream id callback-fn)
  ```
- **Description**: Register `callback-fn` under `id` to receive every record the runtime emits on `stream`. Synchronous delivery — the callback returns before the next record. Re-registering the same id on a stream replaces.
  - Streams: `:trace` (dev-only, DCE'd in production); `:events` and `:errors` (**always-on** — survive CLJS `:advanced` + `goog.DEBUG=false`); `:epoch` (optional artefact).
  - Returns `id` (`nil` on the `:epoch` stream when the `day8/re-frame2-epoch` artefact is absent); an unknown `stream` throws `:rf.error/unknown-listener-stream`.
- **Example**:
  ```clojure
  ;; Dev-only: tap every trace event the runtime emits (DCE'd in production).
  (rf/register-listener! :trace :my-app/trace-tap
    (fn [trace-event]
      (js/console.log (:op-type trace-event) (:operation trace-event))))

  ;; Always-on: one record per processed event → a hosted metrics back-end.
  (rf/register-listener! :events :my-app.monitors/datadog
    (fn [{:keys [event-id frame outcome elapsed-ms]}]
      (datadog/timing "rf.event.elapsed_ms" elapsed-ms
                      {:event (str event-id) :frame (str frame) :outcome (str outcome)})))

  ;; Always-on production error monitoring — the payload is a union; branch on (:error record).
  (rf/register-listener! :errors :my-app.monitors/sentry
    (fn [record]
      (if-let [ex (:exception record)]
        (Sentry/captureException ex)
        (Sentry/captureMessage (str (:error record))))))
  ```

### `unregister-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-listener! stream id) → nil
  ```
- **Description**: The inverse — drop one listener registered under `id` on `stream`.
- **Example**:
  ```clojure
  (rf/unregister-listener! :trace :my-app/trace-tap)
  ```

### `clear-listeners!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-listeners! stream) → nil
  ```
- **Description**: Drop every registered listener on `stream` (`:trace` / `:events` / `:errors` / `:epoch`). **Test-isolation only** — production code should never call this. No-op on `:epoch` when the `day8/re-frame2-epoch` artefact is absent; an unknown `stream` throws `:rf.error/unknown-listener-stream`.

### `emit-trace-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (emit-trace-event! op-type operation tags) → nil
  ```
- **Description**: Emit a custom trace event. Use sparingly — the framework emits the load-bearing events; custom emission is for app-specific cross-cutting concerns the framework can't know about.
- **Example**:
  ```clojure
  (rf/emit-trace-event! :event :rf.probe/touched {:source :probe})
  ```

### `trace-buffer`

- **Kind**: function
- **Signature**:
  ```clojure
  (trace-buffer frame-id) → vector of event bundles, oldest-first
  (trace-buffer frame-id opts) → vector; {:flat true} yields raw trace events
  ```
- **Description**: Read the named frame's dev-only, event-keyed ring non-destructively — one bundle per retained pipeline run by default; `{:flat true}` returns the raw trace events instead.
  - Returns `[]` for a destroyed / never-registered frame, and `[]` in production (the ring is never allocated).
  - On the `re-frame.core` facade this is a **JVM-only** alias — CLJS callers use `re-frame.trace.tooling/trace-buffer` directly.
  - The retained event-slot count is the `(rf/configure! {:trace-buffer {:events-retained N}})` knob.
- **Example**:
  ```clojure
  (rf/trace-buffer :app/main)               ;; event-keyed ring (oldest-first)
  (rf/trace-buffer :app/main {:flat true})  ;; raw trace events instead of event bundles
  ```

### `clear-trace-buffer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-trace-buffer! frame-id) → nil
  ```
- **Description**: Empty the named frame's ring. No-op for an unknown frame, no-op in production. On the `re-frame.core` facade this is a **JVM-only** alias — CLJS callers use `re-frame.trace.tooling/clear-trace-buffer!` directly.
- **Example**:
  ```clojure
  (rf/clear-trace-buffer! :app/main)   ;; empty one frame's ring (e.g. between tool sessions)
  ```

### `group-by-event`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-by-event events) → vector of event records
  ```
- **Description**: Pure data projection of a list of trace events into per-event records `{:dispatch-id :parent-dispatch-id :frame :event :dispatched :handler :fx :effects :subs :renders :other}` (one record per `[frame dispatch-id]` pipeline run), sorted by emission order. JVM-runnable.
- **Example**:
  ```clojure
  (rf/group-by-event (rf/trace-buffer :app/main {:flat true}))
  ```

### `group-by-event-with-events`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-by-event-with-events events) → vector of event records
  ```
- **Description**: Like `group-by-event`, but each record additionally carries a `:trace-events` slot holding the **vector** of raw trace events that composed that event's run. The same `[frame dispatch-id]` grouping is reused verbatim; the `:trace-events` slot is the exact set of events the record was reduced from, in input order.

### `domino-bucket`

- **Kind**: function
- **Signature**:
  ```clojure
  (domino-bucket trace-event) → #{:event :handler :fx :effect :sub :render :other}
  ```
- **Description**: Classify a raw trace event into the pipeline-stage slot used by `group-by-event`. Pure. (The "domino" name is the first-contact mnemonic for those stages.)
- **Example**:
  ```clojure
  (rf/domino-bucket {:op-type :rf.view :operation :rf.view/render})  ;; => :render
  ```

### `elide-wire-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (elide-wire-value v) → v or an elision-marker substitution
  (elide-wire-value v opts) → v or an elision-marker substitution
  ```
- **Description**: The framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots — the **single normative emission site** for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker. Walks `v` consulting the frame's runtime-db classification declarations; the frame resolves from the explicit `:frame` opt, else the carried scope.
  - Redaction is strictly **path-based** — a secret re-keyed off its classified path ships raw (the **fail-open** default; to redact it, classify the destination path).
  - A *live* frame carrying no declarations passes the value through unchanged; a frameless or unresolvable-frame call **fails closed** — the whole value is redacted to `:rf/redacted` (opt out with `:rf.size/include-sensitive? true`).
  - This is the low-level *value* walker; the record-level boundary primitive is `project-egress`.
- **Example**:
  ```clojure
  (rf/elide-wire-value slice {:frame :rf/default})
  ;; Path-scoped: walk one query-vector's value against its declared paths.
  (rf/elide-wire-value query-map {:query-v [:rf.route/query] :frame :rf/default})
  ```

### `project-egress`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-egress record-or-value opts)
  ```
- **Description**: The public, record-level boundary primitive — **the required step before any off-box sink**. Dispatches on a record's `:kind` (`:rf.observe/handled-event` / `:rf.observe/error`) to a per-kind projector, falling back to walking a kindless input as a tree-shaped value; each tree-shaped slot is delegated to `elide-wire-value` against the frame's classification.
  - `opts` carries `:rf.egress/profile` (the closed six-member enum), `:frame`, `:path`, and advanced `:rf.size/*` overrides.
  - An unknown profile throws `:rf.error/unknown-egress-profile`.
  - **Fail-closed**: a tree slot projects only when the frame is known — no `:rf/default` synthesis.
  - Full model: [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
- **Example**:
  ```clojure
  ;; Verify what an off-box sink will receive before wiring it.
  (rf/project-egress
    {:kind     :rf.observe/handled-event
     :frame    :app/main
     :event-id :auth/sign-in
     :event    [:auth/sign-in {:password "hunter2"}]}
    {:rf.egress/profile :rf.egress/off-box-observability})
  ;; => {:kind :rf.observe/handled-event :frame :app/main :event-id :auth/sign-in ...} ;; no :event off-box
  ```

### `register-observability-sink!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-observability-sink! sink-id f)
  ```
- **Description**: Register an observability sink fn `f` under the keyword `sink-id` — the id a frame's `:observability {:handled-events [{:sink <sink-id> :rf.egress/profile …}]}` entry names.
  - `f` receives a single **already-projected** record (projected under the owning frame's classification and the entry's egress profile); it does **no** sink-local redaction.
  - Re-registering the same id replaces. Returns `sink-id`.
  - **Always-on** — survives CLJS `:advanced` + `goog.DEBUG=false`.
  - The production-normal observability seam (frame-scoped + profile-projected), parallel to the corpus-wide `register-listener!` surface (cross-frame and raw).
- **Example**:
  ```clojure
  (rf/reg-frame :app/main
    {:observability {:handled-events
                     [{:sink :my-app.sinks/datadog
                       :rf.egress/profile :rf.egress/off-box-observability}]}})

  (rf/register-observability-sink! :my-app.sinks/datadog
    (fn [projected-record]            ;; already projected — no sink-local redaction
      (datadog/send projected-record)))
  ```

### `unregister-observability-sink!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-observability-sink! sink-id) → nil
  ```
- **Description**: Drop the observability sink registered under `sink-id`. Returns nil.
- **Example**:
  ```clojure
  (rf/unregister-observability-sink! :my-app.sinks/datadog)
  ```

### `sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (sensitive? trace-event) → boolean
  ```
- **Description**: True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against.
- **Example**:
  ```clojure
  (rf/sensitive? {:sensitive? true})   ;; => true
  ;; Drop sensitive events when forwarding from a flat trace read.
  (remove rf/sensitive? (rf/trace-buffer :app/main {:flat true}))
  ```

### `epoch-history`

- **Kind**: function (dev-only; also `re-frame.epoch/epoch-history`)
- **Signature**:
  ```clojure
  (epoch-history frame-id) → vector of epoch records
  ```
- **Description**: Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Returns `[]` for an unknown / destroyed frame. Used by pair-shaped tools for time-travel and post-mortem analysis. Production builds elide entirely. See [re-frame.epoch.md](re-frame.epoch.md).
- **Example**:
  ```clojure
  (rf/epoch-history :app/main)
  (last (rf/epoch-history :app/main))   ;; peek the latest
  ```

### `restore-epoch!`

- **Kind**: function (dev-only; also `re-frame.epoch/restore-epoch!`)
- **Signature**:
  ```clojure
  (restore-epoch! frame-id epoch-id) → boolean
  ```
- **Description**: Restore the frame's whole **frame-state** — both the app-db and runtime-db partitions — to the named epoch's `:frame-state-after`, reinstalled in one atomic write (so machine snapshots, the route slice, and other runtime-db material rewind alongside app-db). Returns `true` on success; `false` for an unknown / destroyed frame (and emits `:rf.error/no-such-handler` of kind `:frame`).
- **Example**:
  ```clojure
  ;; Time-travel: rewind a frame's whole frame-state to a recorded epoch.
  (let [target (last (rf/epoch-history :app/main))]
    (rf/restore-epoch! :app/main (:epoch-id target)))
  ```

### `replace-frame-state!`

- **Kind**: function (dev-only; also `re-frame.epoch/replace-frame-state!`)
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id frame-state) → boolean
  ```
- **Description**: The ONE frame-state write surface (rf2-t3lftq — API-shrink #3 consolidated the former `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` four-mutator family into this). `frame-state` is a PARTIAL frame-state map — any subset of `{:rf.db/app … :rf.db/runtime …}`: a present key replaces that partition, an absent key is preserved unchanged. Bypasses the dispatch loop; records a synthetic epoch so `restore-epoch!` can rewind. A map carrying no recognized partition key, or an unrecognized key, is rejected as `:rf.error/replace-frame-state-bad-keys` (checked before frame resolution). Returns `true` on success, `false` on a documented failure.
- **Examples**:
  ```clojure
  ;; App-only state injection (the former replace-app-db!).
  (rf/replace-frame-state! :app/main {:rf.db/app {:counter 0}})

  ;; App-only reset to {} while runtime-db survives (the former reset-app-db!).
  (rf/replace-frame-state! :app/main {:rf.db/app {}})

  ;; Runtime-only injection — app-db untouched (the former replace-runtime-db!).
  (rf/replace-frame-state! :app/main {:rf.db/runtime new-runtime-db})

  ;; Full-frame install — both partitions atomically.
  (rf/replace-frame-state! :app/main {:rf.db/app new-db :rf.db/runtime new-runtime-db})
  ```

### `register-epoch-listener!`

- **Kind**: function (dev-only; also `re-frame.epoch/register-epoch-listener!`)
- **Signature**:
  ```clojure
  (register-epoch-listener! key callback-fn)
  ```
- **Description**: Process-global assembled-epoch listener. A callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace.
- **Example**:
  ```clojure
  (rf/register-epoch-listener! :my-app/epoch-watch
    (fn [record]
      (js/console.log (:frame record) (:epoch-id record))))
  ```

### `unregister-epoch-listener!`

- **Kind**: function (dev-only; also `re-frame.epoch/unregister-epoch-listener!`)
- **Signature**:
  ```clojure
  (unregister-epoch-listener! key)
  ```
- **Description**: The inverse.
- **Example**:
  ```clojure
  (rf/unregister-epoch-listener! :my-app/epoch-watch)
  ```

### `projected-record`

- **Kind**: function (dev-only; also `re-frame.epoch/projected-record`)
- **Signature**:
  ```clojure
  (projected-record record)
  (projected-record record opts)
  ```
- **Description**: Project an `:rf/epoch-record` for off-box egress — routes the record through the egress projection (applying the optional `(rf/configure! {:epoch-history {:redact-fn …}})` redactor) so an epoch record can be shipped off-box safely. The ring and listeners always deliver the raw record, so projection never affects `restore-epoch!` fidelity. See [re-frame.epoch.md](re-frame.epoch.md).

### `projected-history`

- **Kind**: function (dev-only; also `re-frame.epoch/projected-history`)
- **Signature**:
  ```clojure
  (projected-history frame-id)
  (projected-history frame-id opts)
  ```
- **Description**: Return the projected vector of records for a frame (each member projected as by `projected-record`). The off-box-safe companion to `epoch-history`.

## Registrar queries

The registrar holds every registered handler — events, subs, fx, cofx, flows, machines, views, schemas — as a queryable data structure, which is what makes the framework's tools possible. This is the read-side surface; the write-side is `reg-*` / `clear-*` above. Everything here is JVM-runnable.

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations kind) → {id metadata-map}
  (registrations kind pred-fn) → {id metadata-map}
  ```
- **Description**: Walk the registrar with the full metadata map per id — source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, the doc string. Use when you want metadata; optional `pred-fn` filters by the metadata map.
  - Frame-targeted form `(registrations {:frame :tenants/acme :kind :sub})` (an optional `:pred` filters as above) returns only the ids that frame's image carries, resolved through the frame's sealed image generation (EP-0023).
  - A map argument is always a frame-targeted read: a map without `:frame` raises `:rf.error/registrar-query-needs-frame`; a `:frame` that does not resolve to a live frame carrying a generation raises `:rf.error/frame-no-generation`.
- **Example**:
  ```clojure
  (rf/registrations :event)
  ;; => {:counter/inc {:ns my-app.events :line 12 :file "my_app/events.cljs"} ...}
  (rf/registrations {:frame :tenants/acme :kind :sub})
  ```

### `handler-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-ids kind) → id set
  ```
- **Description**: Canonical alias for `(-> (registrations kind) keys set)`. Use when you only need to enumerate — it saves both the metadata-map allocations and the `keys` walk (meaningful at scale: completion lists, existence checks, set intersections). The frame-targeted form `(handler-ids {:frame :blue/main :kind :event})` scopes to one frame's image; the same map-arity errors as `registrations` apply (`:rf.error/registrar-query-needs-frame`, `:rf.error/frame-no-generation`).
- **Example**:
  ```clojure
  (rf/handler-ids :event)                            ;; => #{:counter/inc :counter/reset}
  (contains? (rf/handler-ids :event) :counter/inc)   ;; => true
  ```

### `handler-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-meta kind id) → registration-metadata map (or nil)
  (handler-meta {:frame f :kind k :id id}) → metadata resolved through frame f's image (or nil)
  ```
- **Description**: What `reg-*` stamped at this id. View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`); pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup.
  - Registrar kinds: `:event`, `:sub`, `:fx`, `:cofx`, `:interceptor`, `:view`, `:frame`, `:route`, `:head`, `:error-projector`, `:flow`, `:resource`.
  - The two machine kinds `:machine-guard` / `:machine-action` are additionally accepted on the positional arity with a 2-vector id — `(handler-meta :machine-guard [machine-id guard-id])` — derived on demand from the machine's registration spec (dev-only source; not frame-targetable).
  - The map arity is the frame-targeted read; the same map-arity errors as `registrations` apply (`:rf.error/registrar-query-needs-frame`, `:rf.error/frame-no-generation`).
  - App-db schemas are **not** a registrar kind — look them up via `(app-schema-meta-at path)` in [re-frame.schemas.md](re-frame.schemas.md).
- **Example**:
  ```clojure
  (rf/handler-meta :sub :counter/value)
  ;; => {:ns my-app.subs :line 8 :column 1 :file "my_app/subs.cljs"}
  ```

### `frame-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-ids)
  (frame-ids ns-prefix)
  ```
- **Description**: Return the set of all live (non-destroyed) frame ids. The optional prefix (a string) filters by namespace.
- **Example**:
  ```clojure
  (rf/frame-ids)              ;; => #{:rf/default :tenants/acme :tenants/globex}
  (rf/frame-ids "tenants")   ;; => #{:tenants/acme :tenants/globex}
  ```

### `frame-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-meta frame-id)
  ```
- **Description**: What `reg-frame` / `make-frame` stamped at this frame. Returns the (post-preset-expansion) metadata map: `:fx-overrides`, `:interceptors`, `:ssr`, `:on-error`, schema bindings.
- **Example**:
  ```clojure
  (rf/frame-meta :tenants/acme)
  ;; => {:id :tenants/acme :doc "..." :fx-overrides {...} :ssr {...}}
  (:fx-overrides (rf/frame-meta :tenants/acme))
  ```

### `app-db-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (app-db-value frame-id) → app-db value (plain map)
  ```
- **Description**: Return the current `app-db` value for this frame — the deref'd `app-db` map (a plain value, not the container), or `nil` for an unknown / destroyed frame. Accepts a frame-id keyword or a live frame object.
- **Example**:
  ```clojure
  (rf/app-db-value :rf/default)
  ;; => {:user {:id 7} :counts {:hits 3}}
  ```

### `frame-state-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-state-value frame-id) → {:rf.db/app … :rf.db/runtime …}
  ```
- **Description**: Return the coherent frame-state projection for the named frame — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an unknown / destroyed frame. The full-frame read for SSR / epoch / time-travel / Xray. A fresh frame's state is `{:rf.db/app {} :rf.db/runtime {}}`. The runtime-db-only read (retired `runtime-db-value`, rf2-t3lftq — API-shrink #3) is `(:rf.db/runtime (frame-state-value frame-id))`.
- **Example**:
  ```clojure
  (:rf.db/runtime (rf/frame-state-value :rf/default))
  ;; => {:rf.runtime/machines {...}}
  ```

> `snapshot-of` was retired by rf2-t3lftq (API-shrink #3) — an empirically zero-caller convenience over `app-db-value` + `get-in`. Use `(get-in (rf/app-db-value frame-id) path)` directly.

## Feature registration (re-exports)

These surfaces are re-exported on the `re-frame.core` facade for single-import ergonomics, but each is **defined in full — signature, metadata grammar, examples — in its feature namespace doc**. Entries here are brief; author against the linked doc. (Each feature's keyword surfaces — its `:fx` ids, standard events, and standard subs — also live in the feature doc.)

### Machines → [re-frame.machines.md](re-frame.machines.md)

A state machine is registered with one call and *is* an event handler; the transition table is data. Keyword surfaces (`[:rf/machine machine-id]` sub, `[:rf.machine/spawn …]` / `[:rf.machine/destroy …]` / `[:rf.machine/dispatch-to-system …]` / `[:raise …]` fx) live in the machines doc.

#### `reg-machine`

- **Kind**: macro
- **Signature**: `(reg-machine machine-id ?metadata machine-spec)`
- The canonical machine-registration macro — compiles a transition-table spec into a `reg-event` handler, co-locating per-element source coords for Xray navigation. The optional middle slot is a registration-metadata map (its `:schema` validates the dispatched outer event vector). Full contract in [re-frame.machines.md](re-frame.machines.md).

#### `defmachine`

- **Kind**: macro
- **Signature**: `(defmachine name ?docstring machine-spec)`
- The `def`-shape companion to `reg-machine` — defines the machine-spec value, binding `name`, with per-element source captured at the definition site so a later `(reg-machine :id name)` retains it. (The plain-fn `reg-machine*` is **not** on the facade — it lives in `re-frame.machines`.) Full contract in [re-frame.machines.md](re-frame.machines.md).

A machine's snapshot is read with the ordinary `subscribe` naming its framework sub vector — `@(rf/subscribe [:rf/machine machine-id])` for the whole `{:state :data :tags}` snapshot, `@(rf/subscribe [:rf/machine-has-tag? machine-id tag])` for a reactive `:tags`-membership predicate. Full contract in [re-frame.machines.md](re-frame.machines.md).

### Routing → [re-frame.routing.md](re-frame.routing.md)

Routes are data; the current route lives in runtime-db (read via the `:rf/route` sub). Keyword surfaces (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf/url-requested` events; `[:rf.nav/push-url …]` / `[:rf.nav/replace-url …]` / `[:rf.nav/scroll …]` fx; the `:rf/route` sub family) live in the routing doc.

#### `reg-route`

- **Kind**: macro
- **Signature**: `(reg-route id metadata path)`
- Register a route as data — the id is the dispatch target, the path is the URL shape, the metadata carries match events and guards (`:on-match`, `:can-leave`, `:params`, `:query`, …). Full contract in [re-frame.routing.md](re-frame.routing.md).

#### `route-link`

- **Kind**: registered view (function)
- **Signature**: `[rf/route-link {:to :route-id :params {...} :query {...} :fragment "..."} & children]`
- A registered view at `:route/link` — renders an `<a href=...>` from a route id and intercepts plain primary-button clicks to dispatch `:rf/url-requested` (modifier-key / middle clicks and `:target`/`:download` anchors defer to the browser). Full contract in [re-frame.routing.md](re-frame.routing.md).

There is no `install-url-listener!` / `remove-url-listener!` (or `install-history-listener!` / `remove-history-listener!`) on this facade — the browser URL-change listener is wired automatically by the `:url-bound?` frame lifecycle (creation installs, destroy removes). See [re-frame.routing.md § Browser URL listener](re-frame.routing.md).

### Flows → [re-frame.flows.md](re-frame.flows.md)

A flow is derived state: declared inputs (frame-state paths), a pure `:derive`, and an `app-db` `:output-path` the runtime recomputes on input change. The runtime-registration fx (`[:rf.fx/reg-flow …]` / `[:rf.fx/clear-flow …]`) and `clear-flow` live in the flows doc.

#### `reg-flow`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-flow flow-id metadata derive-fn)
  ```
- Register a flow in the canonical 3-slot grammar: `flow-id` first, the pure `derive-fn` last, and `metadata` carrying `:inputs` / `:output-path` (both required) plus optional `:doc` / `:schema` / the `:frame` mounting key. Returns `flow-id`. Full contract in [re-frame.flows.md](re-frame.flows.md).

### Schemas → [re-frame.schemas.md](re-frame.schemas.md)

Malli schemas attached to `app-db` paths; validated on writes in dev, elided in production. The introspection surface (`app-schemas`, `app-schema-at`, …) and validator-extension seams live in the schemas doc. (`validate-at-boundary-interceptor` is rowed above under Effects and interceptors.)

#### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path {:schema schema})
  (reg-app-schema path {:schema schema :frame frame})
  ```
- Attach this Malli schema to this `app-db` path. **Path is the registration id** — the only `reg-*` that is path-keyed rather than id-keyed. Full contract in [re-frame.schemas.md](re-frame.schemas.md).

#### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  (reg-app-schemas {path-1 schema-1, ...} opts)
  ```
- The bulk plural form — registers many path→schema entries against the active frame (or the `:frame` opt) in one call, each stamped with this call's source coords. Returns the vector of paths registered. Full contract in [re-frame.schemas.md](re-frame.schemas.md).

### SSR → [re-frame.ssr.md](re-frame.ssr.md)

Server-side rendering is the same framework server-side. A curated set of rendering and head primitives is re-exported here as **late-bound wrappers** that resolve to the `re-frame.ssr` implementation when the `day8/re-frame2-ssr` artefact is on the classpath and throw a clear "SSR not loaded" error otherwise. The host-adapter surface ([re-frame.ssr.ring.md](re-frame.ssr.ring.md)) and the per-request `:rf.server/*` fx are **not** re-exported. Standard SSR events (`:rf/server-init`, `:rf/hydrate`), subs (`:rf/head`, `:rf/public-error`), and the server-only fx live in the SSR doc.

#### `reg-head`

- **Kind**: macro
- **Signature**: `(reg-head id ?metadata head-fn)`
- Register a head-fn `(fn [db route] head-model)` keyed by id; routes opt in via `:head` route metadata. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `reg-error-projector`

- **Kind**: macro
- **Signature**: `(reg-error-projector id ?metadata projector-fn)`
- Register a trace-event → public-error projector `(fn [trace-event] :rf/public-error)`, named per-frame via the frame's `:ssr {:public-error-id …}` metadata. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `render-to-string`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-to-string view-or-hiccup) → HTML string
  (render-to-string view-or-hiccup opts) → HTML string
  ```
- The canonical server-side render — walks the hiccup tree once, emits a string. `opts` may carry `:doctype?` and `:emit-hash?`. JVM-runnable; pure. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `render-tree-hash`

- **Kind**: function
- **Signature**: `(render-tree-hash render-tree) → 32-bit FNV-1a structural hash (lowercase hex)`
- A deterministic structural fingerprint of a render tree (same canonical-EDN → same hash on JVM and CLJS); used by the hydration compatibility check. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `project-error`

- **Kind**: function
- **Signature**: `(project-error frame-id trace-event) → :rf/public-error`
- Apply the active error-projector (selected by the frame's `:ssr {:public-error-id …}` metadata) — the seam between an internal error trace event and a client-safe public-error projection. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `render-head`

- **Kind**: function
- **Signature**: `(render-head head-id opts) → :rf/head-model`
- Evaluate the registered head-fn for `head-id`, returning a head-model. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `active-head`

- **Kind**: function
- **Signature**: `(active-head frame-id) → :rf/head-model`
- Resolve the head-model for the currently active route in the named frame (a frame-scoped read — the frame is carried, not ambient; a `nil` frame-id raises `:rf.error/no-frame-context`). Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `head-model->html`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model->html head-model)
  (head-model->html head-model {:wrap? bool})
  ```
- Render a head-model to its inner-head HTML string (`:wrap?` controls whether `<head>` tags are emitted; default false). Full contract in [re-frame.ssr.md](re-frame.ssr.md).

#### `head-snapshot`

- **Kind**: function
- **Signature**: `(head-snapshot frame-id) → {head-id → :rf/head-model}`
- Read the per-frame snapshot of last-produced head-models (`{}` for a frame that has never seen a `render-head` call). Full contract in [re-frame.ssr.md](re-frame.ssr.md).

### HTTP → [re-frame.http.md](re-frame.http.md)

Managed HTTP is an optional capability: one fx-id (`[:rf.http/managed …]`), one args map, one closed failure taxonomy. The verb helpers (`rf.http/get`, `post`, …), the `[:rf.http/managed …]` / `[:rf.http/managed-abort …]` fx, the failure taxonomy, and the raw `install`/`uninstall` stub pair live in the HTTP doc.

#### `reg-http-interceptor`

- **Kind**: macro
- **Signature**: `(reg-http-interceptor id interceptor-map)`
- Register an HTTP interceptor on a frame's `:rf.http/managed` middleware chain (`:before (fn [ctx] ctx')` request-side, `:after (fn [ctx response] response')` response-side; `:before` in registration order, `:after` in reverse). Full contract in [re-frame.http.md](re-frame.http.md).

#### `clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id)
  (clear-http-interceptor frame id)
  ```
- Unregister an HTTP interceptor by id (single-arity resolves the frame from carried scope; two-arity names it). Full contract in [re-frame.http.md](re-frame.http.md).

#### `with-managed-request-stubs`

- **Kind**: macro
- **Signature**: `(with-managed-request-stubs route-map body+)`
- Lexical-scope HTTP stubbing — `route-map` is `{[<method> <url>] {:reply {:ok v}}}` (or `{:failure …}`); inside the body, matching requests bypass the real client and auto-route by method + URL with no manual `:fx-overrides`. Full contract in [re-frame.http.md](re-frame.http.md).

#### `with-managed-request-stubs*`

- **Kind**: function
- **Signature**: `(with-managed-request-stubs* route-map body-fn)`
- The plain-fn surface beneath the macro — for computed route-maps or non-literal bodies. Full contract in [re-frame.http.md](re-frame.http.md).

### Resources → [re-frame.resources.md](re-frame.resources.md)

Resources are an optional capability (cached server-state reads plus mutations) on the `re-frame.core` facade. The events and subscriptions are keyword-addressed (`[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.mutation/execute …]`, the passive `:rf.resource/*` / `:rf.mutation/*` subs, …) and live in the resources doc.

#### `reg-resource`

- **Kind**: macro
- **Signature**: `(reg-resource resource-id metadata request-fn)`
- Register a resource as data — `metadata` carries the required fail-closed `:scope` policy + `:params-schema`; the `request-fn` returns a managed-HTTP args map. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `reg-mutation`

- **Kind**: macro
- **Signature**: `(reg-mutation mutation-id metadata request-fn)`
- Register a mutation — the causal-write counterpart of a resource: a named write that, on success, invalidates / patches / populates cached reads. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `reg-resource-scope`

- **Kind**: macro
- **Signature**: `(reg-resource-scope scope-id metadata resolve-fn)`
- Register a named resource-scope resolver under `scope-id` (referenced by a resource's `:scope` policy) in the canonical 3-slot grammar: the `:resolve` fn is the value slot, and `metadata` carries the declared `:inputs` (omit `:inputs` for the 2-arg whole-db sugar). Returns `scope-id`. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `clear-resource`

- **Kind**: function
- **Signature**: `(clear-resource resource-id)`
- Remove a registered resource (a registration-lifecycle operation — NOT cache invalidation) and dispose its per-frame runtime state. Returns `resource-id`. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `clear-mutation`

- **Kind**: function
- **Signature**: `(clear-mutation mutation-id)`
- Remove a registered mutation (registration-lifecycle — NOT the `[:rf.mutation/clear …]` runtime-instance reset). Returns `mutation-id`. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `clear-resource-scope`

- **Kind**: function
- **Signature**: `(clear-resource-scope scope-id)`
- Remove a registered resource-scope resolver (registration-lifecycle). Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `resolve-resource-scope`

- **Kind**: function
- **Signature**: `(resolve-resource-scope db scope-id)`
- Resolver helper: resolve the named resource-scope resolver against `db`. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `resource-meta`

- **Kind**: function
- **Signature**: `(resource-meta resource-id) → spec-map or nil`
- Tool/test lane: project the registered resource's spec (`:params-schema`, `:request`, `:scope`, `:stale-after-ms`, `:tags`, source coords). Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `resource-state`

- **Kind**: function
- **Signature**: `(resource-state {:resource … :scope … :params … :frame …}) → entry or nil`
- Tool/test lane: a resource instance's durable runtime entry at an explicit frame (resolves the scoped key as a subscription would). Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `resources`

- **Kind**: function
- **Signature**:
  ```clojure
  (resources)
  (resources {:frame …})
  ```
- Tool/test lane: resource introspection for a frame — the static registry plus, with `:frame`, the live per-frame instance entries. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `mutation-meta`

- **Kind**: function
- **Signature**: `(mutation-meta mutation-id) → spec-map or nil`
- Tool/test lane: the registered mutation's spec (`:request`, `:params-schema`, `:invalidates`, `:patches`, `:populates`, `:scope`, source coords). Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `mutation-state`

- **Kind**: function
- **Signature**: `(mutation-state {:instance … :frame …}) → row or nil`
- Tool/test lane: a mutation **instance**'s durable runtime row (`{:status :result :error …}`) at an explicit frame. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `mutations`

- **Kind**: function
- **Signature**:
  ```clojure
  (mutations)
  (mutations {:frame …})
  ```
- Tool/test lane: mutation introspection for a frame — the registered ids plus, with `:frame`, the live per-frame instance table. Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `install-revalidation-listeners!`

- **Kind**: function (CLJS-only)
- **Signature**: `(install-revalidation-listeners! frame-id) → nil`
- Wire three host `window` listeners for `frame-id` — `focus`/`visibilitychange`→`[:rf.resource/window-focused]` and `online`→`[:rf.resource/network-reconnected]`. Idempotent (hot-reload safe). Full contract in [re-frame.resources.md](re-frame.resources.md).

#### `remove-revalidation-listeners!`

- **Kind**: function (CLJS-only)
- **Signature**: `(remove-revalidation-listeners! frame-id) → nil`
- Tear down the revalidation listeners installed by `install-revalidation-listeners!` (a no-op when none is installed). Full contract in [re-frame.resources.md](re-frame.resources.md).

## See also

- [Subscriptions](../core/subscriptions.md), [Frames](../core/frames.md), [Effects](../core/effects.md), [Coeffects](../core/coeffects.md), [Observability](../core/observability.md) — the concept guides behind these surfaces.
- [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md), [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — the working guides.
- [Core glossary](../core/glossary.md) — the surface vocabulary in one place.
- The feature namespace docs linked under [Feature registration (re-exports)](#feature-registration-re-exports) carry the deep contract for every re-exported macro and its keyword surfaces.
