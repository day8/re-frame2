# re-frame.core

`re-frame.core` is the single namespace every re-frame2 app requires. It is the facade: the registration verbs (`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`), the two cascade verbs (`dispatch`, `subscribe`), the view surface (`reg-view`, `frame-provider`, `capture-frame`), the effect/interceptor surface, the frame primitive, the boot/configure lane, the instrumentation and registrar-query reads, and single-import re-exports of every optional feature's registration macro. Genuine-core surfaces are documented in full below; feature surfaces (machines, routing, flows, schemas, SSR, HTTP, resources) are re-exported here for ergonomics and carry their deep contract in their own namespace doc — those entries are brief, with a pointer.

```clojure
(:require [re-frame.core :as rf])
```

## Registration

This is the surface every re-frame2 app touches. Every entry registers a named handler into the frame's registrar.

**Return value.** Every `reg-*` returns its **primary id** — the keyword (or path, for `reg-app-schema`) you registered with — so you can thread the id through your code without retyping it.

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

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata input-fn? computation-fn)
  ```
- **Description**: "Computed view over `app-db` and other subs." `reg-sub` supports **three input-production modes** — every subscription has an *input query-vector producer*: a layer-1 app-db reader has no producer, `:<-` is the literal producer, and a parametric `input-fn` is the query-parametric producer. The optional first fn is a v2 **`input-fn`** — a *pure* function from the outer `query-v` to a **vector of query vectors**; it is **not** a v1 reaction-returning signal fn (it must not call `subscribe`, deref `app-db`, dispatch, or perform IO, and it must not return live reactions). The runtime resolves each returned query vector in the *same frame* as the outer subscription. This is the only sub-registration form in v2 — `reg-sub-raw` is gone (see the [migration reference](../../migration/from-re-frame-v1/README.md) for the replacement guidance). The full input grammar, the three input-production modes, and the error ids live in the [Subscriptions concept guide](../core/concepts/subscriptions.md).

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
- **Description**: "Define a named side effect." The handler runs against the args the effect map carries; unary `(fn [args] ...)` is the canonical shape, binary `(fn [args ctx] ...)` is available when you need the originating context. `reg-fx` also accepts a `:platforms` metadata key (a set of `:server` / `:client`) that gates fx execution by active platform — see [re-frame.ssr.md](re-frame.ssr.md).
- **Example**:
  ```clojure
  (rf/reg-fx :app/scroll-to-top
    (fn [_args] (js/window.scrollTo 0 0)))
  ```

### `reg-cofx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-cofx id ?metadata supplier)
  ```
- **Description**: "Register a named supplier for a world fact a handler can ask for." The supplier is a plain **value-returning** function — `(fn [] value)`, or `(fn [arg] value)` for ids parameterised at the call site — *not* a context-mutating fn. The runtime calls it and puts the result into the coeffects map under the cofx's id. A handler opts in with `:rf.cofx/requires` registration metadata; v2 has **no `inject-cofx` interceptor**. The middle slot carries the fact's grade: `{:recordable? true}` for a replayable fact, `{:recordable? true :provided? true}` for a recordable fact stamped by an owner boundary (no generator), a bare registration for an ambient (unrecorded) read. Reading a sub from a handler is done the same way — wrap `subscribe-once` in a cofx and declare it. Full model: [Effects and coeffects](../core/concepts/effects-and-coeffects.md).
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
- **Description**: Atomic create-and-register. A frame is the scoping unit — one `app-db`, one event queue, one cascade — and `reg-frame` mints it with metadata you can later read via `frame-meta`. The frame owns the `:observability` sink policy. Durable `app-db` data classification is **not** a frame annotation: a `reg-frame` config carrying `:sensitive` / `:large` **fails loud at registration**. Classify durable `app-db` paths by returning the four commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) from a `reg-event` alongside `:db`, wired to run at frame creation via `:initial-events`. See [re-frame.http.md](re-frame.http.md), [re-frame.schemas.md](re-frame.schemas.md) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
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

Each `clear-*` removes an entry from the registrar; the no-arg form clears the whole kind. Use clearing in tests (the `with-fresh-registrar` fixture relies on these), in REPL workflows, and during teardown.

### `clear-event`

- **Kind**: function
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

### `clear-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sub)
  (clear-sub id)
  ```
- **Description**: "Forget this sub." Note: this is the registrar-side clear (the inverse of `reg-sub`). The runtime cache decrement is `unsubscribe`.
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
- **Description**: "Forget this fx."
- **Example**:
  ```clojure
  (rf/clear-fx :app/scroll-to-top)   ;; forget one fx
  (rf/clear-fx)                      ;; forget every registered :fx
  ```

## Dispatch and subscribe

These are the two verbs that drive the cascade. `dispatch` says "an event happened, run it through the cascade"; `subscribe` says "give me a reactive handle on this query's value."

`dispatch` and `dispatch-sync` come in macro + fn pairs. The **macro** form (`dispatch`, `dispatch-sync`, `subscribe`) captures the call-site source coords so tools like Xray can navigate from a trace event back to the originating expression. The **`*` fn** form (`dispatch*`, `dispatch-sync*`) skips the stamping — needed when you compose dispatch through a higher-order function (`(map dispatch* events)`) where a macro can't sit. Both route through the same dispatcher; only the trace stamping differs.

**The `opts` map.** `dispatch` and `subscribe` accept a uniform opts map: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. The most common pattern is `(rf/dispatch [::save x] {:frame :todo})` to target a non-default frame; the frame **id** is the public routing address.

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
- **Description**: The reactive handle. Returns a reaction whose value is the registered sub's current output; recomputes when upstreams change. Use inside views, inside other subs, and (carefully) inside event handlers via the cofx wrapper. Target a non-ambient frame via the `{:frame …}` opt — `(rf/subscribe [:counter/value] {:frame :other})`; the frame **id** is the public routing address. The frame-first `(subscribe frame-id query-v)` arity and the `subscribe*` fn form are **internal**, not app-facing.
- **Example**:
  ```clojure
  [:span @(rf/subscribe [:counter/value])]
  ```

### `subscribe*`

- **Kind**: function (internal — `:tier :implementation`, EP-0024)
- **Signature**:
  ```clojure
  (subscribe* query-v)
  (subscribe* frame-id query-v)
  ```
- **Description**: **Not an app-facing surface.** The runtime-callable fn form of the `subscribe` macro; the macro's expansion reaches it fully-qualified across a namespace boundary. The public read shapes are `subscribe`, `subscribe-once`, or the `:subscribe` op from a `capture-frame`.

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once query-v opts) → value
  ```
- **Description**: One-shot read: subscribe, deref, immediately unsubscribe. Use in handler bodies, machine actions, REPL — anywhere you want the *current* value without the reactive plumbing. Not for views. Target a non-ambient frame via the `{:frame …}` opts form `(subscribe-once query-v {:frame f})`, mirroring `subscribe` — the same call shape carries over.
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
- **Description**: Decrement the cache ref-count for a query. When the count hits zero, the entry is disposed **synchronously** — see [Subscriptions](../core/concepts/subscriptions.md). Most callers don't reach for this directly — Reagent / UIx / Helix adapters wire it on unmount. Target a non-ambient frame with the frame-first `(unsubscribe frame-id query-v)` form — unlike `subscribe` / `subscribe-once`, `unsubscribe` has no `{:frame …}` opts form (it's pure teardown, never a hot in-view call).
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
  (clear-sub-cache! frame-id?)
  ```
- **Description**: Force-clear the sub-cache for a frame (or all frames). Tests; rarely needed in app code.
- **Example**:
  ```clojure
  (rf/clear-sub-cache! :app/main)   ;; evict one frame's cached subs
  (rf/clear-sub-cache!)             ;; current frame (test / REPL teardown)
  ```

### `compute-sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (compute-sub db query-v) → value
  ```
- **Description**: The test-friendly companion to `subscribe`. Runs the sub graph against a **value** of `app-db` — no cache, no reactivity, no frame — and returns the value. JVM-runnable; the cache-bypassing pure sub evaluator that unit tests reach for to assert a sub's output without standing up a live frame.
- **Example**:
  ```clojure
  ;; Evaluate :counter/doubled against a literal app-db value (no frame, no cache).
  (rf/compute-sub {:counter/value 21} [:counter/doubled])   ;; => 42
  ```

### Standard events (keyword surface)

The framework ships a small, fixed set of standard `:rf/*` events you can dispatch like any other. They are framework-owned: the `:rf/*` single-root namespace is reserved, so re-registering one with `reg-event` is a loud reserved-id collision (`:rf.error/reserved-event-id`).

#### `:rf/set-db`

- **Kind**: standard event
- **Shape**:
  ```clojure
  [:rf/set-db new-db-map]
  ```
- **Description**: The framework-standard `app-db` seeding event. `[:rf/set-db {…}]` **replaces** the whole `app-db` partition with the supplied map (it is a replace, not a merge) and rides the **normal** post-commit path — schema validation, rollback, trace emission, epoch recording — so seeding `app-db` is an ordinary, traceable event rather than a privileged direct write. It returns `{:db new-db}` from a pure handler, so it touches **only** the `app-db` partition and never runtime-db.
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

Views are where the cascade ends and pixels begin. The view layer is **substrate-agnostic** — the shared dataflow (frames, subscriptions, dispatch, source metadata, registry ids) is uniform across Reagent, UIx, and Helix, and the same `capture-frame` carry primitive composes across all three. The substrate-specific hooks (`use-subscribe`, `wrap-view`, …) live in each adapter's namespace doc ([re-frame.adapter.reagent.md](re-frame.adapter.reagent.md) / [re-frame.adapter.uix.md](re-frame.adapter.uix.md) / [re-frame.adapter.helix.md](re-frame.adapter.helix.md)).

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+)
  (reg-view sym docstring [args] body+)
  (reg-view ^{:rf/id :explicit/id} sym [args] body+)
  ```
- **Description**: The `defn`-shape view registration — the app-facing lane. Auto-defs the symbol, auto-derives the id from `(keyword *ns* sym)`, auto-injects `dispatch` / `subscribe` as lexical bindings, and rejects non-defn-shape bodies at macroexpand. The 80% of registrations want this form. In all shapes the symbol is `def`-ed so you can write `[my-view item]` from sibling code; render an app-facing view by **Var reference** (bare keyword-tagged hiccup `[:my-view "args"]` is removed in v2).
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
- **Description**: The plain-fn surface beneath `reg-view` — the tooling / host lane. No auto-def (the caller manages the Var or computed id), no auto-inject, no compile check. Reach for it when the id is **computed** (code-gen pipelines, plugin systems, story scaffolding), when you **don't want a Var** (inside a `let` or closure), when writing a **Form-3 component** (`(rf/reg-view* :id (r/create-class {...}))` — the one app-facing reason to touch the starred form), or when writing **consumer-side library code** that registers without imposing a `def`. Inside a `reg-view*` body there's no auto-injected `dispatch` / `subscribe`; capture a `(rf/capture-frame)` at render and use its ops if the view needs frame-bound dispatch.
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
- **Description**: Runtime lookup handle. Returns the **registered render-fn**, not hiccup. Use in hiccup as `[(rf/view :id) args...]` when you need to late-bind a view by id — a host that resolves a stored view id, plugin-style dispatch, dynamic chrome. The Var form (`[my-view args]`) is the app-facing default; this lookup form is for the tooling/host case where the id, not the symbol, is what the caller holds.
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
  - **`{:frame existing-id}` — SCOPE.** "Children inside this provider see `:todo` as their current frame." Scopes a React subtree to a frame that **already exists**; creates / refreshes / destroys nothing. **Fails loud** (`:rf.error/frame-provider-frame-absent`) when the named frame is absent. `:frame` must be a keyword. The scope-into-React counterpart to `with-frame`.
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
- **Description**: The keystone affordance. Captures the active frame at CREATION time and returns an **operation bundle** whose `:dispatch` / `:dispatch-sync` / `:subscribe` ops always target the captured frame — they survive async boundaries (`Promise.then`, `setTimeout`, WebSocket `onmessage`, observer callbacks) where the ambient frame lookup would have unwound. The handle is *locked* to one frame: a per-call `:frame` opt MUST NOT override it. It's an operation bundle, not a container — read the frame's app-db value via `(rf/app-db-value (:frame handle))`, not the handle itself. The full async-boundary contract is in [Frames — the async boundary](../core/concepts/frames.md).
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
- **Description**: The two **lexical** (non-React) frame-scoping macros — the regions that aren't a view tree, chiefly tests, the REPL, and SSR. `with-frame` pins `*current-frame*` to an **existing** frame-id for the dynamic extent of `body`, creating and destroying nothing; it is the lexical counterpart to the `rf/frame-provider` `{:frame …}` SCOPE shape. `with-new-frame` evaluates `expr`, binds the resulting frame-id to `sym`, runs `body` in that frame's dynamic context, and **destroys the frame on exit** — the throwaway-frame form for one-off harnesses. Each rejects the other's argument shape at compile time.
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

The effect map is what an event handler returns. The interceptor chain is what runs before and after the handler. Handlers stay pure (they return descriptions of effects, not the effects themselves), and the runtime actions those descriptions at exactly one point.

The effect map is **closed**: `:db` + `:fx` only. `:db` is the new `app-db` value (replaced in the commit phase); `:fx` is a vector of `[fx-id args]` pairs, each run by the runtime's fx walker against the registered `reg-fx` handler. The [effect map](../core/glossary.md#effect-map) glossary entry covers both reserved keys.

### `reg-interceptor`

- **Kind**: macro (with `reg-interceptor*` as the programmatic `*`-twin)
- **Signature**:
  ```clojure
  (reg-interceptor id {:keys [before after]})
  ```
- **Description**: The public custom-interceptor authoring form. Register a named interceptor with `:before` and / or `:after`, then **reference it by id** from a `reg-event` / `reg-frame` `:interceptors` vector. **Use this for any work not covered by the standard interceptors** — analytics, logging, validation, ad-hoc context manipulation. The interceptor is named, addressable, and queryable like any other artefact. (`->interceptor` is the framework-internal lowering constructor that turns a descriptor into an executable chain entry; it is not the application-authoring form and must not appear directly in a public chain.)
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

### `reg-interceptor*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-interceptor* id descriptor)
  (reg-interceptor* id metadata descriptor)
  ```
- **Description**: The programmatic `*`-twin of `reg-interceptor` (EP-0022). Same registration, minus the macro's definition-site source-coord capture. Use from HoF / code-gen / REPL callers where a macro can't sit; the `reg-interceptor` macro is the ergonomic surface.

### `->interceptor`

- **Kind**: macro (internal lowering constructor, EP-0022)
- **Signature**:
  ```clojure
  (->interceptor & {:keys [id before after]})
  ```
- **Description**: **INTERNAL — not the public application-authoring form.** The framework-internal lowering constructor that turns a `{:before … :after …}` descriptor into an executable chain entry (capturing the definition-site `:source-coord` from `(meta &form)`). Application code registers interceptors with `reg-interceptor` and references them by id; `->interceptor` must not appear directly in a public `:interceptors` chain.

### `->interceptor*`

- **Kind**: function (internal, EP-0022)
- **Signature**:
  ```clojure
  (->interceptor* & {:keys [id before after]})
  ```
- **Description**: **INTERNAL.** The plain, runtime-callable fn form of the `->interceptor` macro (per Conventions `*`-suffix naming). HoF / programmatic / REPL callers reach this directly; it captures no source-coord. Not an application-authoring surface — use `reg-interceptor`.

### `with-fx-overrides`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-fx-overrides {fx-id -> override, …} body+)
  ```
- **Description**: "For the duration of this body, every `dispatch` / `dispatch-sync` merges this fx-overrides map into its envelope." Lexical scope; composes with `with-frame`. The three override scopes compose with a clear precedence: **per-call** (`(rf/dispatch event {:fx-overrides {...}})`) wins, then **lexical** (`with-fx-overrides`), then **per-frame** (`(rf/reg-frame :todo {:fx-overrides {...}})`). At the pattern level the override value is an **id**; the CLJS reference implementation also accepts a **fn** value for ergonomic test wiring (the asymmetry is deliberate — ports that don't ship fn-valued overrides remain pattern-conformant).
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
- **Description**: A **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`). Add it to a `reg-event` metadata map's `:interceptors` vector for production-boundary validation (handlers that ingest data from outside the app's trust boundary — HTTP replies, websocket frames, postMessage). **Do not call it as a fn** — invoking it raises `ArityException`. Full contract in [re-frame.schemas.md](re-frame.schemas.md).
- **Example**:
  ```clojure
  (rf/reg-event ::receive-from-server
    {:interceptors [rf/validate-at-boundary-interceptor]}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :data payload)}))
  ```

### Standard fx and interceptor (keyword surface)

The framework reserves a few `:fx` entries and one standard interceptor reference. User code registers its own fx-ids via `reg-fx`; the core-owned reserved entries are below. Feature-owned fx (`[:rf.http/managed …]`, `[:rf.nav/push-url …]`, `[:rf.machine/spawn …]`, `[:rf.fx/reg-flow …]`, `[:rf.server/* …]`, …) live in their feature namespace docs.

| `[fx-id args]` | Args | Status | Intuition |
|---|---|---|---|
| `[:dispatch event-vec]` | event vector | v1 | "Schedule this event on the same queue." Async — runs after the current cascade completes. |
| `[:dispatch-later {:ms ms :event event-vec}]` | options map | v1 | "Schedule this event after N ms." |

#### `[:rf.interceptor/path <path-vector>]`

- **Kind**: interceptor **reference** (the one standard interceptor)
- **Form**:
  ```clojure
  {:interceptors [[:rf.interceptor/path [:cart :items]]]}
  ```
- **Description**: Focus the handler on an `app-db` sub-slice. `:before` stages the focused slice as `:db` — `(get-in db path)`; `:after` widens the returned slice back into full app-db. The handler sees and returns a sub-tree, not the full db. Preserves the frame-commit `identical?` no-op (an unchanged focused slice widens back to the original app-db object, not an `assoc-in` allocation). A non-vector / malformed path arg raises `:rf.error/path-interceptor-bad-path`. It is a **reference**, not a constructed value — there is no public `path` fn (a stale `rf/path` call raises `:rf.error/path-removed`).

## Frames

A frame is the scoping unit for `app-db`, the event queue, and the cascade. Most apps have exactly one frame, established at the root with `rf/frame-provider`. `init!` does **not** create one for you — frame identity is carried, not synthesised from absence (see [Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found)). Apps that need isolation between subsystems register additional frames and dispatch / subscribe against them via `{:frame :other}`.

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) ; → live frame value
  ```
- **Description**: The **single public constructor** for a live frame. It accepts image-selection options *and* frame-configuration options in **one** call and **returns the live frame value** — one frame value backed by one registry. Useful for per-mount lifecycles — devcards, modal stacks, multiple live instances of a widget, dynamic tabs, tests, and the SSR per-request frame pattern. Opts: the image-selection keys `:images` (always a vector), `:id` (optional — registers the frame in the one process-local live-frame registry; a duplicate live id is **idempotent replacement** that preserves durable state on re-mount), `:capabilities`, `:adapter` — **and**, in the same call, the frame-configuration keys `:initial-events` (a vector of event vectors dispatched into the new frame at creation), `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`. **Route by id, not by value:** read its id via `rf/frame-value->id` and pass the **id** to `dispatch` / `subscribe` / providers / tools. Lifecycle is the caller's responsibility — pair a direct `make-frame` with a `destroy-frame!`, or use the UI-owned `rf/frame-provider` boundary. See the [Frames concept guide](../core/concepts/frames.md) and [EP-0024](../EP/EP-0024-unified-frame-identity-and-lifecycle.md).
- **Example**:
  ```clojure
  ;; A component that OWNS a frame lifetime uses the UI-owned provider,
  ;; which creates-on-mount and destroys-on-unmount for you (EP-0024):
  (defn counter-widget [label]
    [rf/frame-provider {:images         [counter-image]
                        :initial-events [[:rf/set-db {:count 0}]]}
     [counter-view label]])
  ```

### `reset-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (reset-frame! frame-id)
  ```
- **Description**: Atomic `destroy-frame!` + `reg-frame` with the **same config** — a full frame replace (opt-in). It tears the frame down through the normative `destroy-frame!` boundary (running `:on-destroy`, releasing per-feature resources) and re-registers it fresh, so machine snapshots, the route slice, flows, and `app-db` are all rebuilt from the registered config. Use sparingly. To wipe just the `app-db` partition while keeping live runtime-db, reach for `reset-app-db!` instead. There is **no** `:initial-db` config key — seeding `app-db` is itself an ordinary, traceable event, `[:rf/set-db {…}]`.
- **Example**:
  ```clojure
  ;; Full frame replace (destroy + re-reg with the SAME config).
  ;; Must run OUTSIDE any handler cascade — e.g. a restart button's :on-click.
  (rf/reset-frame! :app/main)
  ```

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
- **Description**: Return the active frame id the in-effect scope carries — the dynamic `*current-frame*` stamp or a React-context `frame-provider` scope (CLJS only). The context **reader** form; it is frame-scoped and requires a scope. There is **no `:rf/default` floor** — called under no established scope it raises `:rf.error/no-frame-context` rather than reporting an invented default (EP-0002).

### `frame-value->id`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-value->id frame-value-or-id) → keyword
  ```
- **Description**: The single public accessor from a frame **value** to its frame id (EP-0024). Returns the frame id a frame value routes to (its `:rf.frame/id` when created with one, else its private runnable id). Passing a frame-id keyword returns it unchanged, so callers can always pass a value or an id. Pure. The frame value's representation is not an app-facing contract — route by the id this returns.

### `frame-generation`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (frame-generation frame-target) → generation map
  ```
- **Description**: Return the SEALED, resolved image **generation** a live frame is running — the inert image-assembly generation it resolves `(kind, id)` lookups through. The raw read over the EP-0023 frame→generation model, for tools (Pair MCP `describe-image`, Xray). `frame-target` is a registered frame id **or** a direct live frame object. Returns the generation map with stable public keys: `:rf.gen/resolver` (the sealed `[kind id]` map), `:rf.gen/images`, `:rf.gen/kinds`. **Fails loud** (`:rf.error/frame-no-generation`) when `frame-target` does not resolve to a live frame carrying a generation.

### `frame-shadows`

- **Kind**: function (EP-0026)
- **Signature**:
  ```clojure
  (frame-shadows frame-target) → vector of shadow entries
  ```
- **Description**: Return the cross-image **shadow report** for the image composition a live frame is running — the data you read to see exactly what a LATER image overrode in an EARLIER one. The public accessor for the report a frame's resolved generation carries at `:rf.gen/shadows`. `frame-target` is a registered frame id or a direct live frame value. Returns a flat vector, one entry per cross-image shadow (`{:registration [kind id] :image <defined-in> :shadowed-by <winner>}`); an **empty** vector when no later image shadowed an earlier one.

### `image`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (image spec) → image value
  ```
- **Description**: Construct an **image** value — a selected registration-set value, as inert data (EP-0023 / EP-0026). `spec` carries exactly three public source keys (`:id`, plus the selection keys); the result is the assembled registration set a frame resolves against (passed to `make-frame` / `frame-provider` under `:images`).

### `reload-images!`

- **Kind**: function (EP-0023)
- **Signature**:
  ```clojure
  (reload-images! target)
  ```
- **Description**: Re-resolve a live frame's image generation against the current registrations — the hot-reload seam that re-seals a frame's `(kind, id)` resolver after handler/sub/view source changes. `target` is a frame id **or** a frame value (`make-frame`'s return token). Frame-targeted (reload affects only the named frame's generation, not siblings sharing an image).

### `frame-bound-fn`

- **Kind**: macro (internal carry helper, EP-0024)
- **Signature**:
  ```clojure
  (frame-bound-fn fn-form)
  ```
- **Description**: **INTERNAL — not app API.** A carry helper that binds a fn to the captured frame. Author async / tooling paths with `capture-frame` (or an explicit `{:frame …}` opt), which expresses the real use cases.

### `frame-bound-fn*`

- **Kind**: function (internal carry helper, EP-0024)
- **Signature**:
  ```clojure
  (frame-bound-fn* f)
  ```
- **Description**: **INTERNAL — not app API.** The fn form behind `frame-bound-fn` (captures only when a real scope exists at capture time). Use `capture-frame` instead.

## Lifecycle and configure

The surfaces that bring a re-frame2 process up and take it down. An app author learns one boot sentence — **install an adapter with `init!`, then create your frame(s) explicitly** — and nothing else. The adapter-author surfaces (install / dispose / inspect) sit one layer down; an ordinary app never touches them.

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map)
  ```
- **Description**: The idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; consumers require the ns and pass the Var, e.g. `(rf/init! reagent-adapter/adapter)`. Calling `(init!)` with no args raises an `ArityException` at compile / load time; `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime. `init!` installs adapters and runtime capabilities only; it does **not** create or guarantee any frame — you register your app frame explicitly (`reg-frame`) and establish it at your root.
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
- **Description**: Set the host-wide active-platform marker. The runtime tracks the active platform so `reg-fx` / `reg-cofx` `:platforms` metadata can gate execution. CLJS hosts default to `:client`, JVM hosts to `:server`; call this at boot to override (e.g. a CLJS-on-Node SSR runtime sets `:server`; a JVM-runnable browser-simulating test sets `:client`). Per-frame `:config :platform` (set by the `:ssr-server` preset) is the finer-grained alternative.
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
- **Description**: Must be called before any frame is created. **Lower-level than `init!`**; ordinary apps call `init!` instead. Use it when you're writing a custom boot pipeline that has additional steps between adapter-install and first-frame creation.
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
- **Description**: "What substrate am I on?" Answers `:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom` — or `nil` when no adapter is installed. For predicate / branch code.
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
- **Description**: "Give me the adapter fns to call." The value passed to `(rf/init! ...)`, or `nil` when no adapter is installed. Use for tools / routing / identity checks across the install / dispose lifecycle. For the discriminator keyword, use `current-adapter`.
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
- **Description**: "Was the adapter torn down?" Returns `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) AND after a fresh install. Read-only. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down).
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
- **Description**: Process-level data knobs you typically set once at boot. One of three orthogonal configuration surfaces — `configure!` for process-level data knobs; the `set-!` / `install-!` setters for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides. The vocabulary of keys is closed-and-additive — existing keys cannot be renamed; new keys are added by extending the table. Three keys ship:

  | Key | Opts | Default | Status | What it tunes |
  |---|---|---|---|---|
  | `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn fn}` | `{:depth 50, :trace-events-keep 50, :redact-fn nil}` | v1 (dev-only) | Per-frame epoch ring depth, trace-event retention cap per record, and an optional projection-side redactor applied only at off-box egress (inside `projected-record`). |
  | `:trace-buffer` | `{:cascades-retained N}` | `{:cascades-retained 50}` | v1 (dev-only) | The dev-only per-frame trace ring's cascade-slot count. 0 disables retention (the surface stays live). |
  | `:elision` | `{:rf.size/threshold-bytes N}` | `{:rf.size/threshold-bytes 16384}` | v1 | The size threshold above which `elide-wire-value` substitutes a `:rf.size/large-elided` marker. 0 disables runtime auto-detect. |

  There is **no `:sub-cache` knob** — sub-cache disposal is synchronous on derefer-count → 0. SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure!` key — it's per-frame metadata on the frame's `:ssr` map. Framework-owned semantic sub-keys use a namespaced keyword (`:rf.size/threshold-bytes`); ergonomic per-knob sub-keys are unqualified (`:depth`, `:trace-events-keep`, `:redact-fn`).
- **Example**:
  ```clojure
  (rf/configure! {:epoch-history {:depth 100}
                  :trace-buffer  {:cascades-retained 25}
                  :elision       {:rf.size/threshold-bytes 8192}})
  ```

### `feature-loaded?`

- **Kind**: function
- **Signature**:
  ```clojure
  (feature-loaded? feature) → boolean
  ```
- **Description**: Is the named optional feature's implementation artefact on the classpath? Detection is a pure keyword lookup in the always-loaded feature registry (no exception, no classpath probe). Code may legitimately probe `(feature-loaded? :routing)` before taking a feature-dependent path.
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
- **Description**: Assert the optional feature is loaded. Returns `true` when its implementation artefact is on the classpath; throws a structured `:rf.error/feature-not-loaded` ex-info carrying the EXACT copy-pasteable Maven coordinate + require form when it is not. Use as an early, self-explaining guard at the top of code that depends on an optional feature.
- **Example**:
  ```clojure
  (rf/require-feature! :epoch)   ;; absent => :rf.error/feature-not-loaded with copy-paste deps + require
  ```

## Instrumentation and listeners

Two surfaces stacked. The first is **dev-only**: a trace bus that emits one richly-tagged record per noteworthy event, buffered into a ring, fanned out to registered listeners synchronously, and elided entirely under `:advanced` + `goog.DEBUG=false`. The second is **always-on**: tight, production-survivable substrates (event-emit, error-emit) that deliver one record per processed event and one per `:rf.error/*` event. The epoch (time-travel) surfaces are dev-only and are also available natively as [re-frame.epoch.md](re-frame.epoch.md). The complete error catalogue is normative in Spec 009.

### `register-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-listener! stream callback-fn)
  ```
- **Description**: Receive every record the runtime emits on `stream`. **Stream-parameterized**: `:trace` (dev-only, DCE'd in production), `:events` and `:errors` (**always-on** — survive CLJS `:advanced` + `goog.DEBUG=false`), and `:epoch` (optional artefact). Synchronous delivery; the callback returns before the next record. Re-registering the same id on a stream replaces.
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
- **Description**: "Emit a custom trace event." Use sparingly — the framework emits the load-bearing events; custom emission is for app-specific cross-cutting concerns the framework can't know about.
- **Example**:
  ```clojure
  (rf/emit-trace-event! :event :rf.probe/touched {:source :probe})
  ```

### `trace-buffer`

- **Kind**: function
- **Signature**:
  ```clojure
  (trace-buffer) → vector of trace events, oldest-first
  (trace-buffer opts) → vector of trace events, oldest-first
  ```
- **Description**: "What's in the ring right now?" Reads the dev-only buffer non-destructively. Pair tools and Xray use this for post-mortem inspection. The retained cascade-slot count is the `(rf/configure! {:trace-buffer {:cascades-retained N}})` knob.
- **Example**:
  ```clojure
  (rf/trace-buffer :app/main)               ;; cascade-keyed ring (oldest-first)
  (rf/trace-buffer :app/main {:flat true})  ;; raw trace events instead of cascade bundles
  ```

### `clear-trace-buffer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-trace-buffer!) → nil
  ```
- **Description**: Empty the ring.
- **Example**:
  ```clojure
  (rf/clear-trace-buffer! :app/main)   ;; empty one frame's ring (e.g. between tool sessions)
  ```

### `group-cascades`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-cascades events) → vector of cascade records
  ```
- **Description**: Pure data projection of a list of trace events into per-cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. JVM-runnable.
- **Example**:
  ```clojure
  (rf/group-cascades (rf/trace-buffer :app/main {:flat true}))
  ```

### `group-cascades-with-events`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-cascades-with-events events) → vector of cascade records
  ```
- **Description**: Like `group-cascades`, but each record additionally carries a `:trace-events` slot holding the **vector** of raw trace events that composed that cascade. The same `[frame dispatch-id]` grouping is reused verbatim; the `:trace-events` slot is the exact set of events the record was reduced from, in input order.

### `domino-bucket`

- **Kind**: function
- **Signature**:
  ```clojure
  (domino-bucket trace-event) → #{:event :handler :fx :effect :sub :render :other}
  ```
- **Description**: Classify a raw trace event into the six-domino slot used by `group-cascades`. Pure.
- **Example**:
  ```clojure
  (rf/domino-bucket {:op-type :rf.view :operation :rf.view/render})  ;; => :render
  ```

### `elide-wire-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (elide-wire-value v opts) → v or an elision-marker substitution
  ```
- **Description**: The framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots — the **single normative emission site** for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker. Walks `v` consulting the named frame's runtime-db classification declarations. Redaction is strictly **path-based** — a secret re-keyed off its classified path ships raw (the **fail-open** default; to redact it, classify the destination path). When the frame carries no declarations the value passes through unchanged. This is the low-level *value* walker; the record-level boundary primitive is `project-egress`.
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
- **Description**: The public, record-level boundary primitive — **the required step before any off-box sink**. Dispatches on a record's `:kind` (`:rf.observe/handled-event` / `:rf.observe/error`) to a per-kind projector, falling back to walking a kindless input as a tree-shaped value; each tree-shaped slot is delegated to `elide-wire-value` against the frame's classification. `opts` carries `:rf.egress/profile` (the closed six-member enum), `:frame`, `:path`, and advanced `:rf.size/*` overrides. An unknown profile throws `:rf.error/unknown-egress-profile`. **Fail-closed**: a tree slot projects only when the frame is known — no `:rf/default` synthesis. Full model: [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
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
- **Description**: Register an observability sink fn `f` under the keyword `sink-id` — the id a frame's `:observability {:handled-events [{:sink <sink-id> :rf.egress/profile …}]}` entry names. `f` receives a single **already-projected** record (projected under the owning frame's classification and the entry's egress profile); it does **no** sink-local redaction. Re-registering the same id replaces. **Always-on** — survives CLJS `:advanced` + `goog.DEBUG=false`. This is the production-normal observability seam, parallel to the corpus-wide `register-listener!` surface (frame-scoped + profile-projected, where the corpus-wide listeners are cross-frame and raw).
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
- **Description**: True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same check.
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

### `replace-app-db!`

- **Kind**: function (dev-only; also `re-frame.epoch/replace-app-db!`)
- **Signature**:
  ```clojure
  (replace-app-db! frame-id new-db) → boolean
  ```
- **Description**: Pair-tool write surface (state injection). Direct write to `app-db` — bypasses the cascade. Records a synthetic epoch so `restore-epoch!` can rewind. Returns `true` on success.
- **Example**:
  ```clojure
  (rf/replace-app-db! :app/main {:counter 0})
  ```

### `reset-app-db!`

- **Kind**: function (dev-only; also `re-frame.epoch/reset-app-db!`)
- **Signature**:
  ```clojure
  (reset-app-db! frame-id) → boolean
  ```
- **Description**: Reset `frame-id`'s `app-db` partition to `{}`, bypassing the dispatch loop, while preserving live runtime-db (machines / routes / elision / SSR survive). The app-db-only sibling of the whole-frame `reset-frame!`. Equivalent to `(replace-app-db! frame-id {})` — same synthetic-epoch recording, gating, and failure modes. Returns `true` on success, `false` on a documented failure. Raises `:rf.error/epoch-artefact-missing` when the epoch artefact is absent.

### `replace-runtime-db!`

- **Kind**: function (dev-only; also `re-frame.epoch/replace-runtime-db!`)
- **Signature**:
  ```clojure
  (replace-runtime-db! frame-id runtime-db) → boolean
  ```
- **Description**: Replace ONLY `frame-id`'s `runtime-db` partition (the framework-owned subsystem state — machine snapshots, route slice, …); app-db is untouched. Privileged runtime / Tool-Pair injection surface. Records a synthetic epoch so `restore-epoch!` can rewind. Returns `true` on success, `false` on a documented failure (unknown frame, drain in flight, runtime-db schema mismatch).

### `replace-frame-state!`

- **Kind**: function (dev-only; also `re-frame.epoch/replace-frame-state!`)
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id frame-state) → boolean
  ```
- **Description**: Replace BOTH partitions of `frame-id` atomically with `frame-state` (`{:rf.db/app … :rf.db/runtime …}`) — the explicit full-frame install for tool-driven replay / fixture install (epoch restore, time travel, SSR hydration, frame reset). A db-shaped name never silently replaces runtime-db; this is the explicit full-frame surface. Records a synthetic epoch so `restore-epoch!` can rewind. Returns `true` on success, `false` on a documented failure.

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
- **Description**: Convenience: return the projected vector of records for a frame (each member projected as by `projected-record`). The off-box-safe companion to `epoch-history`.

## Registrar queries

The registrar is the data structure that holds every registered handler — events, subs, fx, cofx, flows, machines, views, schemas. Treating it as a queryable data structure is what makes the framework's tools possible. This is the read-side surface; the write-side is `reg-*` / `clear-*` above. Everything here is JVM-runnable.

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations kind) → {id metadata-map}
  (registrations kind pred-fn) → {id metadata-map}
  ```
- **Description**: **Use when you want metadata.** Walk the registrar with the full metadata map per id — source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, the doc string. Optional `pred-fn` filters by the metadata map. The frame-targeted form `(registrations {:frame :tenants/acme :kind :sub})` returns only the ids that frame's image carries (EP-0023).
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
- **Description**: **Use when you only need to enumerate.** Canonical alias for `(-> (registrations kind) keys set)`. Saves both the metadata-map allocations and the `keys` walk — meaningful at scale (completion lists, existence checks, set-shaped intersections). The frame-targeted form `(handler-ids {:frame :blue/main :kind :event})` scopes to one frame's image.
- **Example**:
  ```clojure
  (rf/handler-ids :event)                            ;; => #{:counter/inc :counter/reset}
  (contains? (rf/handler-ids :event) :counter/inc)   ;; => true
  ```

### `handler-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-meta kind id) → registration-metadata map
  ```
- **Description**: "What did `reg-*` stamp at this id?" View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`); pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup. `kind` is one of `:event`, `:sub`, `:fx`, `:cofx`, `:view`, `:flow`, `:route`, `:head`, `:error-projector`. App-db schemas are **not** a registrar kind — look them up via `(app-schema-meta-at path)` in [re-frame.schemas.md](re-frame.schemas.md).
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
- **Description**: "What frames exist?" Returns the set of all live (non-destroyed) frame ids. The optional prefix (a string) filters by namespace.
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
- **Description**: "What did `reg-frame` / `make-frame` stamp at this frame?" Returns the (post-preset-expansion) metadata map: `:fx-overrides`, `:interceptors`, `:ssr`, `:on-error`, schema bindings.
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
- **Description**: "What's the current `app-db` value for this frame?" Returns the deref'd `app-db` map (a plain value, not the container) — `nil` for an unknown / destroyed frame. Accepts a frame-id keyword or a live frame object.
- **Example**:
  ```clojure
  (rf/app-db-value :rf/default)
  ;; => {:user {:id 7} :counts {:hits 3}}
  ```

### `runtime-db-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (runtime-db-value frame-id) → runtime-db value (plain map)
  ```
- **Description**: Return the current `runtime-db` partition value for the named frame — the framework-owned subsystem state (the `:rf.runtime/*` children), or `nil` for an unknown / destroyed frame. The tool / privileged-runtime read of the framework partition. A fresh frame's runtime-db starts `{}`. Accepts a frame-id keyword or a live frame object.

### `frame-state-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-state-value frame-id) → {:rf.db/app … :rf.db/runtime …}
  ```
- **Description**: Return the coherent frame-state projection for the named frame — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an unknown / destroyed frame. The full-frame read for SSR / epoch / time-travel / Xray. A fresh frame's state is `{:rf.db/app {} :rf.db/runtime {}}`; the `:rf.db/runtime` slot equals `runtime-db-value`.

### `snapshot-of`

- **Kind**: function
- **Signature**:
  ```clojure
  (snapshot-of path)
  (snapshot-of path opts)
  ```
- **Description**: "What's at this path in `app-db` right now?" Convenience over `app-db-value` + `get-in`. Frame resolution: an explicit `(:frame opts)` override wins, else the scope/hold stamp.
- **Example**:
  ```clojure
  (rf/snapshot-of [:user :id])          ;; => 7
  (rf/snapshot-of [:n] {:frame :left})  ;; => 11
  ```

## Feature registration (re-exports)

These surfaces are re-exported on the `re-frame.core` facade for single-import ergonomics, but each is **defined in full — signature, metadata grammar, examples — in its feature namespace doc**. Each is a brief entry here; author against the linked doc. (Each feature's keyword surfaces — its `:fx` ids, standard events, and standard subs — also live in the feature doc.)

### Machines → [re-frame.machines.md](re-frame.machines.md)

A state machine is registered with one call and *is* an event handler; the transition table is data. Keyword surfaces (`[:rf/machine machine-id]` sub, `[:rf.machine/spawn …]` / `[:rf.machine/destroy …]` / `[:rf.machine/dispatch-to-system …]` / `[:raise …]` fx) live in the machines doc.

#### `reg-machine`

- **Kind**: macro
- **Signature**: `(reg-machine machine-id machine-spec)`
- The canonical machine-registration macro — compiles a transition-table spec into a `reg-event` handler, co-locating per-element source coords for Xray navigation. Full contract in [re-frame.machines.md](re-frame.machines.md).

#### `defmachine`

- **Kind**: macro
- **Signature**: `(defmachine name machine-spec)`
- The `def`-shape companion to `reg-machine` — defines and registers a machine, binding `name`. (The plain-fn `reg-machine*` is **not** on the facade — it lives in `re-frame.machines`.) Full contract in [re-frame.machines.md](re-frame.machines.md).

#### `machine-has-tag?`

- **Kind**: function
- **Signature**: `(machine-has-tag? machine-id tag) → reaction`
- Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])` — a reactive predicate over the machine snapshot's `:tags` set, for rendering on state-tag membership. Full contract in [re-frame.machines.md](re-frame.machines.md).

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

#### `install-history-listener!`

- **Kind**: function (CLJS-only)
- **Signature**: `(install-history-listener!)`
- Install a `window` `popstate` listener that dispatches `:rf.route/handle-url-change` to the current URL-owning frame — the inbound (browser → app) counterpart of the outbound `:rf.nav/push-url` gate. Idempotent (hot-reload safe). Full contract in [re-frame.routing.md](re-frame.routing.md).

#### `remove-history-listener!`

- **Kind**: function (CLJS-only)
- **Signature**: `(remove-history-listener!)`
- Tear down the `popstate` listener installed by `install-history-listener!`. No-op when none is installed. Full contract in [re-frame.routing.md](re-frame.routing.md).

### Flows → [re-frame.flows.md](re-frame.flows.md)

A flow is derived state: declared inputs (frame-state paths), a pure `:derive`, and an `app-db` `:output-path` the runtime recomputes on input change. The runtime-registration fx (`[:rf.fx/reg-flow …]` / `[:rf.fx/clear-flow …]`) and `clear-flow` live in the flows doc.

#### `reg-flow`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-flow flow)
  (reg-flow flow opts)
  ```
- Register a flow. The flow map carries `:id`, `:inputs`, `:derive`, `:output-path`; `opts` (currently `{:frame frame-id}`) selects the owning frame. Returns the flow's `:id`. Full contract in [re-frame.flows.md](re-frame.flows.md).

### Schemas → [re-frame.schemas.md](re-frame.schemas.md)

Malli schemas attached to `app-db` paths; validated on writes in dev, elided in production. The introspection surface (`app-schemas`, `app-schema-at`, …) and validator-extension seams live in the schemas doc. (`validate-at-boundary-interceptor` is rowed above under Effects and interceptors.)

#### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path {:schema schema})
  (reg-app-schema path {:schema schema :frame frame})
  ```
- "Attach this Malli schema to this `app-db` path." **Path is the registration id** — the only `reg-*` that is path-keyed rather than id-keyed. Full contract in [re-frame.schemas.md](re-frame.schemas.md).

#### `reg-app-schemas`

- **Kind**: macro
- **Signature**: `(reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})`
- The bulk plural form — registers many path→schema entries in one call, each stamped with this call's source coords. Returns the vector of paths registered. Full contract in [re-frame.schemas.md](re-frame.schemas.md).

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
- **Signature**: `(render-to-string view-or-hiccup opts) → HTML string`
- The canonical server-side render — walks the hiccup tree once, emits a string. JVM-runnable; pure. Full contract in [re-frame.ssr.md](re-frame.ssr.md).

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

Resources are an optional, post-v1 capability (TanStack/RTK/SWR-style cached server-state reads, plus mutations) on the `re-frame.core` facade. The events and subscriptions are keyword-addressed (`[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.mutation/execute …]`, the passive `:rf.resource/*` / `:rf.mutation/*` subs, …) and live in the resources doc.

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
- **Signature**: `(reg-resource-scope scope-id resolver)`
- Register a named resource-scope resolver under `scope-id` (referenced by a resource's `:scope` policy). Returns `scope-id`. Full contract in [re-frame.resources.md](re-frame.resources.md).

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

- [Subscriptions](../core/concepts/subscriptions.md), [Frames](../core/concepts/frames.md), [Effects and coeffects](../core/concepts/effects-and-coeffects.md), [Observability](../core/concepts/observability.md) — the concept guides behind these surfaces.
- [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md), [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — the working guides.
- [Core glossary](../core/glossary.md) — the surface vocabulary in one place.
- The feature namespace docs linked under [Feature registration (re-exports)](#feature-registration-re-exports) carry the deep contract for every re-exported macro and its keyword surfaces.
