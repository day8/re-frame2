# re-frame.core

`re-frame.core` is the namespace application code calls. You use it to register event handlers, subscriptions, effects and views, to dispatch events and read subscriptions, and to boot the app and create frames.

```clojure
(:require [re-frame.core :as rf])
```

A counter, from registration to mount:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db 0)))

(rf/reg-view counter []
  [:button {:on-click #(dispatch [:counter/inc])}
   "Clicked " @(subscribe [:counter/value]) " times"])

(defonce app-root (reagent-adapter/client-root))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app/main} [counter]]
    (js/document.getElementById "app")))
```

Optional features (machines, routing, flows, schemas, SSR, HTTP, resources) register through macros on this facade, listed under [Feature registration](#feature-registration-re-exports), so one require covers every registration. Each feature's other functions and its keyword events and effects are documented on that feature's page. The [Core guide](../core/introduction.md) teaches the model these functions implement.

## Registration

The `reg-*` forms register a handler under an id. Each returns the id it registered (the path, for `reg-app-schema`).

### `reg-event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event id ?metadata handler)
  ```
- **Description**: Registers an event handler; dispatching `[id …]` runs it. The handler is `(fn [coeffects event-vec] effect-map)`: it receives the coeffects map and returns a closed effect map such as `{:db … :fx […]}`, or `nil` for no effects. Writing `app-db` is always an explicit `:db` effect; there is no db-only return shape. `reg-event` is the only public event-registration form.
    - The optional metadata map carries reflection keys (`:doc`, `:schema`, `:tags`, …) and the `:interceptors` vector. Interceptors always go in the metadata map, never in a positional middle vector: `(reg-event :id {:interceptors [:my/audit]} handler)`.
    - To read or rewrite the interceptor context, register a named interceptor with [`reg-interceptor`](#reg-interceptor) and reference it by id from `:interceptors`. Its `:before` / `:after` fns receive and return the context map. There is no separate registration for raw-context handlers.
    - `{:boundary? true}` makes this handler's own `:schema` run in every build, including `:advanced` with `goog.DEBUG=false`, where development validation is otherwise removed. It adds no second schema. Set it on handlers that take data from outside the app: HTTP replies, websocket frames, `postMessage`.
        - The check runs before the interceptor chain, against the event vector as dispatched, so development and production validate the same value at the same point, and `:interceptor-overrides` cannot remove it.
        - `{:boundary? true}` without a `:schema` key throws `:rf.error/at-boundary-missing-schema` at registration. The key must be present: `{:schema nil :boundary? true}` registers.
        - A failed check skips the handler, sets `:outcome :rejected`, and emits the always-on `:rf.error/schema-validation-failure` record with `:source :boundary`. See [Validate with schemas](../core/how-to/validate-with-schemas.md).
- **Example**:
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

  ;; Metadata: a doc string and an interceptor referenced by id.
  (rf/reg-event :cart/add
    {:doc "Add an item." :interceptors [:cart/undoable]}
    (fn [{:keys [db]} [_ item]] {:db (update db :items conj item)}))

  ;; Validate an untrusted payload in every build.
  (rf/reg-event ::receive-from-server
    {:schema    [:cat [:= ::receive-from-server] PayloadSchema]
     :boundary? true}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :data payload)}))
  ```

### `reg-sub`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-sub id ?metadata computation-fn)
  ```
- **Description**: Registers a subscription: a value computed from `app-db`, from other subscriptions, or both, which views read with `subscribe`. Dependencies go under `:inputs` in the metadata map (the key `reg-flow` also uses), in one of three forms:

    | Mode | Form | Where the inputs come from |
    |---|---|---|
    | App-db reader | `(reg-sub id computation-fn)` | `:inputs` omitted. No upstream subscriptions; the computation fn receives `app-db` and the outer `query-v` (layer 1). |
    | Static inputs | `(reg-sub id {:inputs [q1 q2]} computation-fn)` | A literal list of query vectors, known and shape-checked at registration. |
    | Parametric inputs | `(reg-sub id {:inputs producer-fn} computation-fn)` | Computed from the outer `query-v` when a cache entry is first materialized. |

    - Declared inputs always arrive as a vector, in declaration order, whether there are zero, one or many. Moving a dependency between the literal and the fn form never changes the body, and adding a second input never turns a scalar argument into a vector. `{:inputs []}` declares no dependencies and delivers `[]`; omitting `:inputs` delivers `app-db` itself.
    - An `:inputs` producer fn is a pure function from the outer `query-v` to a vector of query vectors. It must not call `subscribe`, deref `app-db`, dispatch, perform IO or return reactions. It never runs at registration; the runtime calls it at materialization and resolves each returned query vector in the same frame as the outer subscription.
    - There is no `reg-sub-raw`; `reg-sub` is the only subscription registration form. The [Subscriptions guide](../core/subscriptions.md) covers the full input grammar and its error ids.
- **Example**:
  ```clojure
  ;; Layer-1 — read straight off app-db (no :inputs, no producer)
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))

  ;; Layer-2 — declare the upstream sub; the body destructures the vector
  (rf/reg-sub :counter/doubled {:inputs [[:counter/value]]}
    (fn [[value] _query] (* 2 value)))

  ;; Parametric — the producer returns a vector of query vectors, computed
  ;; from the outer query-v; the runtime resolves each in the outer sub's
  ;; frame and hands the resolved values to the computation-fn.
  (rf/reg-sub :article/page
    {:inputs (fn [[_ article-id]]
               [[:article/by-id article-id]
                [:comments/for-article article-id]
                [:viewer/current]])}
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
- **Description**: Registers an effect handler. When an event handler returns `[id args]` in its `:fx` vector, the runtime calls it. The handler takes the context first: `(fn [ctx args] ...)`.
    - `ctx` is a small map of `:frame` (the active frame id), `:event` (the originating event vector) and `:envelope` (the parent dispatch envelope).
    - `args` is the value the event handler placed beside the fx id in its `:fx` vector.
    - The `:platforms` metadata key (a set of `:server` / `:client`) runs the effect only on those platforms. See [re-frame.ssr](re-frame.ssr.md).
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
- **Description**: Registers a coeffect: a named fact about the world that an event handler can ask for. The supplier is a plain function that returns the value, `(fn [] value)`, or `(fn [arg] value)` for an id parameterised at the call site. The runtime calls it and puts the result in the coeffects map under the cofx id.
    - A handler asks for coeffects with `:rf.cofx/requires` in its registration metadata. There is no `inject-cofx` interceptor.
    - The metadata map sets the fact's grade:
        - `{:recordable? true}`: a replayable fact.
        - `{:recordable? true :provided? true}`: a recordable fact with no supplier; its owner stamps the value onto the event.
        - Neither key: an ambient read that is not recorded.
    - To read a subscription from a handler, wrap `subscribe-once` in a cofx and declare it.
    - See [Coeffects](../core/coeffects.md).
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

### `clear`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear kind id)
  (clear kind id {:frame f})
  ```
- **Description**: Removes one registration and returns its `id`. It is the inverse of the `reg-*` forms, keyed by kind, for tests, REPL work and teardown. There are no per-kind names such as `clear-event` or `clear-resource`.
    - `kind` is one of `:event`, `:sub`, `:fx`, `:cofx`, `:interceptor`, `:view`, `:route`, `:head`, `:error-projector`, `:flow`, `:resource`, `:mutation`, `:resource-scope` or `:http-interceptor`. A frame is not a registration; tear it down with [`destroy-frame!`](#destroy-frame).
    - Kinds that hold runtime state clean it up: `:flow` vacates its output path and settles dependents, `:route` emits `:rf.route/cleared`, `:http-interceptor` rebuilds the frame's chain, and the resource kinds dispose per-frame runtime state. The other kinds only remove the registrar entry.
    - The `{:frame f}` map is accepted only for the per-frame kinds `:flow` and `:http-interceptor`. It must be exactly `{:frame f}`, with `f` a frame-id keyword or a live frame value. Anything else, including opts on another kind, throws `:rf.error/registrar-clear-bad-request` before any frame is resolved, so a typo such as `{:fram :session}` throws instead of clearing the ambient frame's registration. An unknown kind throws the same error, naming the valid kinds.
    - Clearing a kind whose optional artefact is not loaded throws that artefact's missing-artefact error, for example `:rf.error/flows-artefact-missing` for `:flow`.
    - There is no clear-all arity. Bulk clearing belongs to the test fixtures in [`re-frame.test-support`](re-frame.test-support.md).
- **Example**:
  ```clojure
  (rf/clear :event :counter/inc)                 ;; => :counter/inc
  (rf/clear :sub   :counter/value)               ;; the registrar side; `unsubscribe` decrements the cache
  (rf/clear :cofx  :now)                         ;; any registrar kind
  (rf/clear :flow  :cart/total)                  ;; ambient frame
  (rf/clear :flow  :cart/total {:frame :session}) ;; explicit frame
  ```

## Dispatch and subscribe

`dispatch` sends an event to a frame; `subscribe` reads a subscription.

On CLJS, `dispatch`, `dispatch-sync` and `subscribe` are macros in call position and plain functions in value position, as the `reg-*` forms are. A call such as `(rf/dispatch [:counter/inc])` records its source coordinates, so tools like Xray can go from a trace event back to that expression. Passed as a value, as in `(map rf/dispatch events)` or `(or dispatch-fn rf/dispatch)`, the name is a function that skips the recording. Both reach the same dispatcher, and there is no `*`-suffixed variant.

On the JVM the three are macros only. JVM code that needs a function calls `re-frame.router/dispatch!`, `re-frame.router/dispatch-sync!` or `re-frame.subs/subscribe`.

`dispatch` and `dispatch-sync` take an optional opts map with `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id` and `:source`. `subscribe` and `subscribe-once` read only `:frame`. `:frame` is a frame-id keyword or a live frame value: `(rf/dispatch [::save x] {:frame :todo})`.

### `dispatch`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch event)
  (dispatch event opts)
  ```
- **Description**: Queues the event on the frame and returns `nil` immediately; the handler runs later. This is the default way to send an event.
    - Inside a `reg-view` body, the injected `dispatch` targets the view's frame. A callback that runs later (a timeout, a promise) needs a frame captured with [`capture-frame`](#capture-frame).
- **Example**:
  ```clojure
  ;; inside a reg-view: the injected `dispatch` captured the frame at render
  [:button {:on-click #(dispatch [:counter/inc])} "+"]
  ```

### `dispatch-sync`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch-sync event)
  (dispatch-sync event opts)
  ```
- **Description**: Processes the event and drains the queue to completion before returning. Use it in tests, at the REPL and for one-shot boot events. Calling it from inside a running event handler raises `:rf.error/dispatch-sync-in-handler`.
- **Example**:
  ```clojure
  (rf/dispatch-sync [:counter/initialise])   ;; one-shot app-boot event

  ;; Fn-form — drive a sequence of events synchronously from runner / test code.
  (doseq [evec events]
    (rf/dispatch-sync evec {:frame :app/main}))
  ```

### `subscribe`

- **Kind**: macro
- **Signature**:
  ```clojure
  (subscribe query-v)
  (subscribe query-v opts)
  ```
- **Description**: Returns a reaction whose value is the subscription's current output, recomputed when its inputs change; deref it to read. Use it inside views and other subscriptions. An event handler reads a subscription through a coeffect (see [`reg-cofx`](#reg-cofx)).
    - `(rf/subscribe [:counter/value] {:frame :other})` targets another frame; `:other` may be a frame-id keyword or a live frame value.
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
- **Description**: Reads a subscription's current value once: subscribes, derefs and unsubscribes, keeping no reactive handle. Use it in handler bodies and at the REPL, not in views.
    - Machine `:guard`, `:action`, `:entry` and `:exit` fns must not call it. A machine takes host facts as recorded coeffects, including the machines-only `{:rf/sub …}` source (see [re-frame.machines](re-frame.machines.md)).
    - `(subscribe-once query-v {:frame f})` targets another frame, as for `subscribe`.
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
- **Description**: Decrements a query's cache reference count. When the count reaches zero, the entry is disposed synchronously (see [Subscriptions](../core/subscriptions.md)). The Reagent and UIx adapters call it on unmount, so most code never does.
    - `(unsubscribe frame-id query-v)` targets another frame. There is no `{:frame …}` opts form: this is a teardown call, not one views make.
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
- **Description**: Disposes every entry in a frame's subscription cache, synchronously and unconditionally. The no-argument form uses the current frame and raises `:rf.error/no-frame-context` outside a frame scope; the one-argument form names the frame. Mostly for tests.
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
- **Description**: Computes a subscription against an `app-db` value and returns the result, with no cache, no reactivity and no frame. Use it to test subscriptions. The query vector comes first, the db second.
    - `db` is a plain `app-db` map, or a full frame-state value (`{:rf.db/app … :rf.db/runtime …}`) when the graph mixes `app-db` and `runtime-db` subscriptions.
    - Runs on the JVM.
- **Example**:
  ```clojure
  ;; Evaluate :counter/doubled against a literal app-db value (no frame, no cache).
  (rf/compute-sub [:counter/doubled] {:counter/value 21})   ;; => 42
  ```

### Standard events (keyword surface)

The framework registers a few `:rf/*` events that you dispatch like any other. The `:rf/*` namespace belongs to the framework: registering `:rf/set-db` with `reg-event` throws `:rf.error/reserved-event-id`.

#### `:rf/set-db`

- **Kind**: standard event
- **Payload**:
  ```clojure
  [:rf/set-db new-db-map]
  ```
- **Description**: Replaces the whole `app-db` with the given map; it does not merge. Use it to seed `app-db` when a frame is created, or to reset it. It goes through the normal event pipeline (schema validation, rollback, trace emission, epoch recording) like any other event. Its handler returns `{:db new-db}`, so it changes `app-db` only, never `runtime-db`.
    - It takes exactly one map. A missing, `nil` or non-map argument, or any extra argument (`[:rf/set-db {} :junk]`), throws `:rf.error/set-db-bad-value`. `[:rf/set-db {}]` empties `app-db`.
- **Example**:
  ```clojure
  ;; seed app-db at frame creation (frame-root requires :id)
  [rf/frame-root {:id             :counter
                  :images         [counter-image]
                  :initial-events [[:rf/set-db {:count 0}]]}
   [counter-view]]

  ;; or dispatch it directly to reset app-db to a known shape
  (rf/dispatch [:rf/set-db {:count 0 :user nil}])
  ```

#### `:rf/install-frame-state`

- **Kind**: standard event
- **Payload**:
  ```clojure
  [:rf/install-frame-state {:rf.db/app <map>? :rf.db/runtime <map>?}]
  ```
- **Description**: Installs saved frame state, for app-authored persistence. [`frame-state-value`](#frame-state-value) reads the state to save. A present `:rf.db/app` replaces `app-db`; a present `:rf.db/runtime` replaces each `runtime-db` subtree it carries and keeps the rest; an absent partition is left alone.
    - When the payload carries `:rf.runtime/machines` and the machines artefact is loaded, the restored machines' `:after` timers are re-armed after the commit.
    - The whole payload is classified `:sensitive`, so traces and egress records show `:rf/redacted` in its place.
    - A non-map payload, a present non-map partition, or a resource runtime subtree (`:rf.runtime/resources`, `:rf.runtime/work-ledger`, `:rf.runtime/mutations`) throws; the router reports it as `:rf.error/handler-exception` and nothing is installed.
- **Example**:
  ```clojure
  ;; at boot, re-install a value saved earlier from (rf/frame-state-value :app/main)
  (rf/dispatch-sync [:rf/install-frame-state saved] {:frame :app/main})
  ```

## Views

Frames, subscriptions, dispatch, source metadata and registry ids work the same under every adapter, and `capture-frame` works under all of them. Adapter-specific functions are on each adapter's page: [re-frame.adapter.reagent](re-frame.adapter.reagent.md) (full and slim Reagent) and [re-frame.adapter.uix](re-frame.adapter.uix.md), whose components read and dispatch through the `use-sub` and `use-frame` hooks.

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+)
  (reg-view sym docstring [args] body+)
  (reg-view ^{:rf/id :explicit/id} sym [args] body+)
  ```
- **Description**: Defines and registers a view with a `defn`-like form. This is the form application code uses.
    - It `def`s the symbol, so sibling code renders the view as `[my-view item]`.
    - The id is the current namespace plus the symbol (`my.app.views/counter-buttons` registers `:my.app.views/counter-buttons`), unless `^{:rf/id …}` metadata names one.
    - `dispatch` and `subscribe` are bound inside the body and target the frame the view renders in.
    - A body that is not `defn`-shaped is rejected at macroexpansion.
    - Render a view by Var reference or with `(rf/view id)`. Keyword-tagged hiccup such as `[:my-view "args"]` is rejected.
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
- **Description**: Registers a view from a plain function, with no `def`, no injected `dispatch` / `subscribe` and no compile-time shape check. Use it when:
    - the id is computed (code generation, plugin systems, story scaffolding);
    - you don't want a Var (inside a `let` or a closure);
    - you are writing a Form-3 component, `(rf/reg-view* :id (r/create-class {...}))`, the one reason application code reaches for it;
    - you are writing library code that registers views without imposing a `def`.

    For frame-bound dispatch in a `reg-view*` body, call `(rf/capture-frame)` at render and use its ops.
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
- **Description**: Looks up a registered view by id and returns its render fn, or `nil` when the id is not registered. It does not return hiccup. Use it as `[(rf/view :id) args...]` when you hold an id rather than the symbol: a stored view id, plugin-style dispatch, dynamic chrome. Otherwise render by Var reference (`[my-view args]`).
- **Example**:
  ```clojure
  [(rf/view :app/header) {:title "Cart"}]   ;; resolves the registered render-fn at render time
  ```

### `frame-provider`

- **Kind**: component (Reagent)
- **Signature**:
  ```clojure
  [rf/frame-provider {:frame :todo} & children]
  ```
- **Description**: Scopes a subtree to a frame that already exists; it creates, refreshes and destroys nothing. `dispatch` and `subscribe` inside the subtree target that frame. To create the frame if it is absent, use [`frame-root`](#frame-root). It is the React counterpart of `with-frame`. See the [frame-provider glossary entry](../core/glossary.md#frame-provider).
    - `:frame` is a frame-id keyword or a live frame value. An absent frame raises `:rf.error/frame-provider-frame-absent`, a `nil` `:frame` raises `:rf.error/no-frame-context`, and any other value raises `:rf.error/bad-frame-provider-arg`.
    - Passing `:id` (the `frame-root` key) raises `:rf.error/frame-provider-given-id`, naming `frame-root`.
- **Example**:
  ```clojure
  ;; :todo already exists; this subtree just renders against it.
  (rf/reg-view todo-page []
    [rf/frame-provider {:frame :todo}
     [todo-list]])
  ```

### `frame-root`

- **Kind**: component (Reagent)
- **Signature**:
  ```clojure
  [rf/frame-root {:id :todo :images [todo-image]} & children]
  ```
- **Description**: Creates the named frame if it is absent, or reuses it without re-seeding if it is live, and provides it to the subtree. It takes `make-frame`'s options (`:id`, `:images`, frame configuration including `:initial-events`). `:id` is required and must be a keyword; otherwise it raises `:rf.error/frame-root-missing-id`.
    - The frame is created at commit, in a client `useLayoutEffect`, not during render. The first render emits no children; once the frame exists, the children render against it. A render React discards before commit (a Suspense abort, a discarded concurrent render) creates and seeds nothing.
    - A remount reuses the frame without re-seeding it. Hot reload, StrictMode's development double-invoke, Story re-evaluation and a keyed remount all keep durable state; `:initial-events` run once, when the frame is first created.
    - Unmounting does not destroy the frame. To own a frame's lifetime explicitly, call `make-frame` and `destroy-frame!` yourself, for example in a `create-class`.
    - Changing `:id` or other options on a mounted `frame-root` raises `:rf.error/frame-root-reconfigured`; to switch frames, give the `frame-root` a React `key` that changes. Passing `:frame` (the `frame-provider` key) raises `:rf.error/frame-root-given-frame`, naming `frame-provider`.
- **Example**:
  ```clojure
  ;; Bring the :todo frame into being for as long as this subtree is
  ;; mounted: created on first mount, reused without re-seeding on remount.
  (rf/reg-view todo-widget []
    [rf/frame-root {:id :todo :images [todo-image]}
     [todo-list]])
  ```

### `capture-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (capture-frame)          → {:frame :dispatch :dispatch-sync :subscribe}
  (capture-frame frame-id) → {:frame :dispatch :dispatch-sync :subscribe}
  ```
- **Description**: Captures a frame and returns a bundle of operations bound to it. Use it for code that runs after the view body has returned (`Promise.then`, `setTimeout`, WebSocket `onmessage`, observer callbacks), where the ambient frame is no longer in scope.
    - `(capture-frame)` captures the current frame at the moment of the call and raises `:rf.error/no-frame-context` outside a frame scope. `(capture-frame frame-id)` binds the bundle to a named frame and works anywhere.
    - The `:dispatch`, `:dispatch-sync` and `:subscribe` ops always target the captured frame; a `:frame` in their opts is ignored.
    - The bundle holds operations, not state. Read the frame's `app-db` with `(rf/app-db-value (:frame handle))`.
    - See [Frames](../core/frames.md) for the async-boundary rules.
- **Example**:
  ```clojure
  (rf/reg-view stream-view []
    (let [{:keys [dispatch]} (rf/capture-frame)]          ;; captures the render frame
      (ws/subscribe! (fn [msg] (dispatch [::incoming msg]))) ;; fires LATER, but bound
      [:div "streaming…"]))
  ```

<a id="with-frame--with-new-frame"></a>

### `with-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-frame :keyword body)
  ```
- **Description**: Makes an existing frame the current frame for the dynamic extent of `body`, in code that is not a view tree: tests, the REPL, SSR. It creates and destroys nothing; it is the lexical counterpart of `frame-provider`. For a callback that runs after `body` returns, use `capture-frame`. Passing `with-new-frame`'s binding vector is a compile-time error.
- **Example**:
  ```clojure
  (rf/with-frame :todo
    (rf/dispatch-sync [:todo/add {:text "milk"}]))
  ```

### `with-new-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-new-frame [sym expr] body)
  ```
- **Description**: Evaluates `expr` (usually a `make-frame` call), binds the resulting frame to `sym`, runs `body` with it as the current frame, and destroys the frame on exit, whether `body` returns or throws. Use it for throwaway frames in tests and one-off harnesses. Passing a keyword, the `with-frame` shape, is a compile-time error.
- **Example**:
  ```clojure
  (rf/with-new-frame [f (rf/make-frame {:images [todo-image]})]
    (rf/dispatch-sync [:rf/set-db {:todos []}])
    (rf/dispatch-sync [:todo/add {:text "milk"}])
    (is (= 1 (count (:todos (rf/app-db-value f))))))
  ```

## Effects and interceptors

An event handler returns an effect map describing what should happen; the runtime applies it after the handler returns, so the handler stays pure. The interceptor chain runs before and after the handler.

The effect map is closed. It has seven top-level keys:

- `:db`: the new `app-db` value, installed at commit.
- `:fx`: a vector of `[fx-id args]` pairs (`[fx-id]` when there are no args). The runtime runs each against the handler registered with `reg-fx`.
- `:sensitive`, `:large`, `:clear-sensitive`, `:clear-large`: classify `app-db` paths in the same commit (see [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md)).
- `:rf.db/runtime`: reserved for the framework and runtime extensions; application handlers do not return it.

Any other top-level key rejects the event before anything commits, so a valid `:db` beside it does not land either, and the runtime emits `:rf.error/effect-map-shape`. See the [effect map](../core/glossary.md#effect-map) glossary entry.

### `reg-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-interceptor id {:keys [before after]})
  (reg-interceptor id metadata descriptor)
  ```
- **Description**: Registers a named interceptor, which event handlers and frames then reference by id in their `:interceptors` vector. Use it for work the standard interceptor does not cover: analytics, logging, validation, context manipulation.
    - `{:before f}`, `{:after f}` or `{:before f :after g}`: each fn receives and returns the interceptor context.
    - `{:factory f}`: a parameterized family. `f` takes one argument and returns a descriptor, and a chain references it as `[id arg]`. The standard `[:rf.interceptor/path …]` is a factory.
    - The optional middle slot is the standard registration-metadata map (`:doc`, `:schema`, `:tags`, …).
    - On CLJS the name is also a plain function in value position, as for `dispatch`; JVM code that needs a function calls `re-frame.interceptor-registry/reg-interceptor*`. There is no public constructor for interceptor values.
    - See [Interceptors](../core/interceptors.md).
- **Example**:
  ```clojure
  (rf/reg-interceptor :log-event
    {:after (fn [ctx]
              (js/console.log "handled" (pr-str (get-in ctx [:coeffects :event])))
              ctx)})

  (rf/reg-event ::save-cart
    {:interceptors [:log-event]}                   ;; reference by id
    (fn [cofx _]
      {:db (assoc (:db cofx) :cart/saving? true)}))
  ```

### `with-fx-overrides`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-fx-overrides {fx-id -> override, …} body+)
  ```
- **Description**: Replaces effect handlers for every `dispatch` and `dispatch-sync` inside `body`, usually to stub an effect in a test. The map is merged into each dispatch's envelope. The scope is lexical and composes with `with-frame`.
    - Precedence, highest first: per call (`(rf/dispatch event {:fx-overrides {...}})`), then lexical (`with-fx-overrides`), then per frame (`(rf/make-frame {:id :todo :fx-overrides {...}})`).
    - An override value is the id of another registered fx. This implementation also accepts a function, for test wiring.
- **Example**:
  ```clojure
  ;; Swap the real managed-HTTP fx for a canned-failure stub for the test body —
  ;; every dispatch inside inherits the override; it unwinds when the body exits.
  (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrong"}]))
  ```

### Standard fx and interceptor (keyword surface)

The framework reserves a few `:fx` ids and one standard interceptor reference; register your own fx ids with `reg-fx`. Feature fx (`[:rf.http/managed …]`, `[:rf.nav/push-url …]`, `[:rf.machine/spawn …]`, `[:rf.fx/reg-flow …]`, `[:rf.server/* …]`, …) are documented on each feature's page.

#### `[:dispatch event-vec]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `[:dispatch event-vec]`
- **Description**: Queues `event-vec` on the same queue. It runs after the current pipeline run completes.

#### `[:dispatch-later {:ms ms :event event-vec}]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `[:dispatch-later {:ms ms :event event-vec}]`
- **Description**: Queues `event-vec` after `ms` milliseconds.
- **Example**:
  ```clojure
  (rf/reg-event :toast/show
    (fn [{:keys [db]} [_ text]]
      {:db (assoc db :toast text)
       :fx [[:dispatch-later {:ms 3000 :event [:toast/hide]}]]}))
  ```

#### `[:rf.interceptor/path <path-vector>]`

- **Kind**: interceptor reference
- **Payload**: `[:rf.interceptor/path path-vector]`, inside an `:interceptors` vector
- **Description**: Focuses a handler on one slice of `app-db`: `:before` puts `(get-in db path)` in the coeffects as `:db`, the handler returns a new slice, and `:after` writes it back into the full `app-db`. It is the only standard interceptor.
    - A slice returned unchanged widens back to the original `app-db` object rather than a fresh `assoc-in`, so the commit's `identical?` no-op still holds.
    - A non-vector or malformed path raises `:rf.error/path-interceptor-bad-path`.
    - It is referenced by id; there is no `path` function, and calling `rf/path` raises `:rf.error/path-removed`.
- **Example**:
  ```clojure
  ;; The handler sees and returns only the [:cart :items] slice.
  (rf/reg-event :cart/add-item
    {:interceptors [[:rf.interceptor/path [:cart :items]]]}
    (fn [{:keys [db]} [_ item]] {:db (conj db item)}))
  ```

## Frames

A frame holds one running instance of your app: its `app-db`, event queue and event pipeline. Most apps have one frame, created at the root with `rf/frame-root`. `init!` does not create one, and an operation outside any frame scope does not fall back to a default frame (see [Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found)). Apps that need isolation between subsystems create more frames and target them with `{:frame :other}`.

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts)             ; → live frame value
  (make-frame opts descriptors) ; → live frame value (explicit descriptor pool — tests / harnesses)
  ```
- **Description**: Creates a frame and returns the live frame value. Use it when your code owns the frame's lifetime: devcards, modal stacks, several live instances of a widget, dynamic tabs, tests, and one frame per SSR request. For a frame tied to a view subtree, use `frame-root`. There is no `reg-frame`: a frame is a live runtime object, not a registration.
    - `opts` must be a map; a non-map, including `nil`, raises `:rf.error/make-frame-bad-opts`.
    - Image selection: `:images` (always a vector; otherwise `:rf.error/make-frame-bad-images`), `:id` and `:adapter`. `:id` is optional. With it, the frame is registered in the process-wide live-frame registry, and creating a frame under an id that is already live replaces it idempotently, keeping durable state across a re-mount.
    - Frame configuration: `:initial-events` (event vectors dispatched into the new frame at creation), `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`, and the `:observability` sink policy.
    - The two-argument form resolves `:images` against an explicit descriptor pool, for tests and harnesses.
    - Pass the returned value wherever a frame is expected. `dispatch`, `subscribe`, `app-db-value` and `frame-provider` accept the value or its id and treat them the same, so there is no value-to-id accessor. `destroy-frame!` accepts both too, but treats them differently; see [`destroy-frame!`](#destroy-frame).
    - A frame config cannot classify `app-db` data: a config carrying `:sensitive` or `:large` is rejected. To classify paths, return the classification effects from a `reg-event` alongside `:db` and run it at creation through `:initial-events`, as in the example. See [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
    - Pair each `make-frame` with a `destroy-frame!`, or use `frame-root` for frames tied to a view. See [Frames](../core/frames.md).
- **Example**:
  ```clojure
  ;; Classify app-db paths with a commit-plane effect, run at frame creation
  ;; through :initial-events. A frame config cannot classify data.
  (rf/reg-event :app/init
    (fn [{:keys [db]} _]
      {:db        (assoc db :auth {})
       :sensitive [[:auth :token]]}))   ;; classify before any value lands

  (rf/make-frame
    {:id             :app/main
     :doc            "App demo frame."
     :initial-events [[:app/init]]      ;; classifies [:auth :token] at creation
     :fx-overrides   {:rf.http/managed :auth.login.demo/managed-stub}})
  ```

### Resetting a frame

There is no `reset-frame!`. To replace a frame, destroy it with `destroy-frame!`, which runs `:on-destroy` and releases per-feature resources, then create it again from the same config. Machine snapshots, the route slice, flows and `app-db` are all rebuilt from the config:

```clojure
;; Full frame replace (destroy + re-create with the SAME config).
;; Must run OUTSIDE any handler run — e.g. a restart button's :on-click.
(rf/destroy-frame! :app/main)
(rf/make-frame config)   ;; re-supply the SAME config (it carries :id :app/main)
```

- For a frame built from images, pass the same `:images` vector again; otherwise the new frame gets the default image.
- To empty `app-db` and keep `runtime-db`, call `(rf/replace-frame-state! frame-id {:rf.db/app {}})` instead.
- There is no `:initial-db` config key. Seed `app-db` with the ordinary `[:rf/set-db {…}]` event.
- The pair is not atomic. `destroy-frame!` has no handler-scope guard, so called from inside a handler it destroys the frame, and the following `make-frame` throws because frames cannot be created inside a handler, leaving the frame destroyed. Frame creation and destruction belong outside handlers anyway.

### `destroy-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-frame! frame-target) ; frame id keyword, or the live frame value
  ```
- **Description**: Tears down a frame and releases everything it holds. Calling it again is a no-op.
    - Queued events that have not started are dropped at once. A handler already running may return, and interceptor `:after` fns already entered may unwind, but their results are discarded: no later framework step or render runs.
    - A configured `:on-destroy` event, and the events it dispatches to the same frame, run before the frame is marked dead.
    - A dispatch from elsewhere during teardown may reach the queue, but it is dropped before its handler runs. Afterwards, `dispatch` and `subscribe` against the dead or absent frame do nothing and emit `:rf.error/frame-destroyed`.
    - Teardown then releases every frame-scoped feature resource (flows, machines, schemas, SSR, epoch), clears the subscription cache and removes the frame.
    - A frame value destroys only that frame: if a new frame has since been created under the same id, the old value does nothing. A frame-id keyword destroys whichever frame is live under that id.
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
- **Description**: Returns the id of the current frame: the one bound by `with-frame` or `with-new-frame`, or on CLJS the nearest enclosing [`frame-provider`](#frame-provider) or [`frame-root`](#frame-root). Outside any frame scope it raises `:rf.error/no-frame-context`; there is no fallback to `:rf/default`.

### `frame-generation`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-generation frame-target) → generation map
  ```
- **Description**: Returns the image generation a live frame is running: the sealed map through which it resolves `(kind, id)` lookups. Tools such as Pair MCP's `describe-image` and Xray read it.
    - `frame-target` is a frame id or a live frame value. One that does not resolve to a live frame carrying a generation raises `:rf.error/frame-no-generation`.
    - The map's public keys are `:rf.gen/resolver` (the sealed `[kind id]` map), `:rf.gen/images`, `:rf.gen/kinds`, and `:rf.gen/shadows`.
    - `:rf.gen/shadows` is the cross-image shadow report: what a later image overrode in an earlier one, as `[{:registration [kind id] :image <defined-in> :shadowed-by <winner>} …]`, or `[]` when nothing was shadowed. There is no separate `frame-shadows`.
    - See [Reading what a frame is running](../core/images.md#reading-what-a-frame-is-running).
- **Example**:
  ```clojure
  (:rf.gen/shadows (rf/frame-generation :app/main))   ;; => [] when no image overrode another
  ```

### `image`

- **Kind**: macro
- **Signature**:
  ```clojure
  (image spec) → image value
  ```
- **Description**: Builds an image: a selection of registrations, as inert data, that a frame resolves against. Pass it to `make-frame` or `frame-root` under `:images`. Building an image registers and runs nothing. `spec` has three public keys:
    - `:id` (optional);
    - `:select-ns`, an `{:include [globs] :exclude [globs]}` map that selects registrations by the namespace they were written in;
    - `:registrations`, inline registrations in sections keyed by registrar kind.

    An unknown key raises `:rf.error/invalid-image`. See [Images](../core/images.md).
- **Example**:
  ```clojure
  (def counter-image
    (rf/image {:id        :counter/app
               :select-ns {:include ["my-app.counter.*"]}}))

  (rf/make-frame {:id :counter/main :images [counter-image]})
  ```

### Hot-reloading a frame's images

Re-evaluating a `reg-*` form during development updates the live frames that select it; you call nothing. To change which images a frame runs, call `make-frame` again with the same `:id` and a new `:images` vector. There is no `reload-images!`. Frame memory is kept: only the image generation is replaced, while `app-db`, `runtime-db`, caches and lifecycle continue unchanged:

```clojure
(rf/make-frame {:id :my/frame :images [new-image]})
```

- It replaces the whole `:images` vector, not one member; a non-vector raises `:rf.error/make-frame-bad-images`.
- It changes only the named frame, not other frames sharing an image.
- To see what changed, read `frame-generation` before and after and compare the two with `generation-diff`:

```clojure
(let [before (rf/frame-generation :my/frame)
      _      (rf/make-frame {:id :my/frame :images [new-image]})
      after  (rf/frame-generation :my/frame)]
  (rf/generation-diff before after))
  ;; => {:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}
```

### `generation-diff`

- **Kind**: function
- **Signature**:
  ```clojure
  (generation-diff before after) → diff map
  ```
- **Description**: Compares two sealed image generations and returns `{:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}`. It is pure. Use it on `frame-generation` values read before and after re-calling `make-frame`, as shown above.

## Lifecycle and configure

These functions start and stop a re-frame2 process and set process-wide options. An application calls `init!` once at boot, then creates its frames; it calls `configure!` only to change a process option. The other functions here are for tests, tools and adapter authors.

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map)
  ```
- **Description**: Installs the adapter for your view library, and the runtime capabilities that come with it. Each adapter namespace exports an `adapter` Var; require the namespace and pass the Var: `(rf/init! reagent-adapter/adapter)`. It creates no frame: mount one with [`frame-root`](#frame-root) or create one with [`make-frame`](#make-frame).
    - `(init!)` with no argument is an `ArityException` at compile or load time; `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified`.
    - Calling it again with the installed adapter is a no-op. Calling it with a different adapter raises `:rf.error/adapter-already-installed` and leaves the installed one in place; call `destroy-adapter!` first to switch. Two adapter maps are the same adapter when they carry the same canonical `:rf.adapter/*` `:kind`, or when they are the identical map.
    - Hot reload is safe with the shipped adapters. Reloading re-evaluates the adapter map with new function identities, but its canonical kind is unchanged, so a `^:dev/after-load` call stays a no-op.
    - A custom adapter with no canonical kind is compared by identity, so re-evaluating its Var and calling `init!` again raises. Hold it in a `defonce`, or call `destroy-adapter!` in the after-load fn. That also releases every Fresco root on the page, whatever adapter is installed, so a root whose owner does not render again stays empty until the page reloads.
- **Example**:
  ```clojure
  (:require [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)
  ```

### `destroy-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-adapter!)
  ```
- **Description**: Tears down the installed adapter so another can be installed; it is the counterpart of `init!`. It attempts every adapter cleanup step, then rethrows the first failure, with later failures kept as diagnostic evidence. The adapter counts as disposed even when cleanup throws. It clears only the adapter it tore down: a new adapter can be installed afterwards, and a late cleanup from the old one never clears its replacement.
- **Example**:
  ```clojure
  (rf/destroy-adapter!)                 ;; tear down the current substrate, clear the install slot
  (rf/init! reagent-adapter/adapter)    ;; …then install a fresh adapter (test fixture / hot-reload swap)
  ```

### `current-adapter`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter) → installed adapter spec map
  ```
- **Description**: Returns the installed adapter map, exactly as passed to `rf/init!`, or `nil` when no adapter is installed. The map carries the adapter contract fns (`:make-state-container`, `:replace-container!`, `:make-derived-value`, …) and a `:kind`.
    - To branch on the substrate, read `(:kind (rf/current-adapter))`: `:rf.adapter/reagent`, `:rf.adapter/reagent-slim`, `:rf.adapter/uix`, `:rf.adapter/fresco`, `:rf.adapter/plain-atom` or `:rf.adapter/ssr`, or `nil` for a custom adapter map with no canonical kind. `:rf.adapter/helix`, `:rf.adapter/ui` and `:rf.adapter/freehand` are reserved, and nothing produces them.
    - To check whether an adapter is installed, test the map, not `:kind`: a custom adapter without a kind is installed while `(:kind (rf/current-adapter))` reads `nil`.
    - In a Fresco app, `:kind` names the substrate the app booted on (normally `:rf.adapter/fresco`), not the view layer; see [re-frame.fresco](re-frame.fresco.md).
- **Example**:
  ```clojure
  (rf/current-adapter)          ;; => the adapter spec map passed to (rf/init! …), or nil
  (:kind (rf/current-adapter))  ;; => :rf.adapter/reagent
  ```

### `configure!`

- **Kind**: function
- **Signature**:
  ```clojure
  (configure! config-map)
  ```
- **Description**: Sets process-wide options, usually once at boot. It is one of three configuration surfaces: `configure!` for process-level data, the `set-*!` / `install-*!` setters for pluggable hooks, and frame metadata for per-frame settings. The key set is closed, and four keys exist:

    | Key | Opts | Default | What it tunes |
    |---|---|---|---|
    | `:epoch-history` | `{:depth N :trace-events-keep N}` | `{:depth 50, :trace-events-keep 50}` | Development only. Per-frame epoch ring depth, and the cap on trace events kept per record. |
    | `:trace-buffer` | `{:events-retained N}` | `{:events-retained 50}` | Development only. Event slots in each frame's trace ring: one slot per event, however many trace events its run emitted. `0` disables retention; tracing itself stays on. |
    | `:elision` | `{:rf.egress/threshold-bytes N}` | `{:rf.egress/threshold-bytes 16384}` | Size above which an undeclared large string triggers the `:rf.warning/large-value-unschema'd` advisory. The value is still forwarded unchanged; replacing it with the `:rf.size/large-elided` marker requires declaring the path `:large`. `0` turns the detection off. |
    | `:observability` | `{:handled-events [<entry>…] :errors [<entry>…]}` | none declared | The process default for production observation sinks. See below. |

    - `:observability` takes the same grammar as a frame's `:observability`. Precedence is per stream: a frame that declares a stream uses its own entries, a frame that omits it inherits this default's, and `{:errors []}` on a frame opts that frame out. Each record goes to exactly one source per stream.
    - Inheritance passes on the sink list, not the redaction authority. Records with no frame authority (frameless producers, or a `:frame` that no longer resolves) reach this default alone, projected with no governing frame. Unlike the other keys, an explicit `nil` clears it, and it is validated when you call `configure!` (`:rf.error/bad-frame-classification`, `:where 'rf/configure!`).
    - An unknown top-level key applies nothing. The known keys are bare, so a bare or `rf`-namespaced unknown key such as `:epoch-histroy` is treated as a typo: development builds emit `:rf.warning/unknown-configure-key`, naming every offending key and the known set. The call still returns `nil` and applies nothing (`:recovery :ignored`), and production builds remove the warning. A key in your own namespace (`:myapp/thing`) passes silently, so a wrapper can hand `configure!` a composed config without filtering it.
    - There is no `:sub-cache` key: a cached subscription is disposed synchronously when its reader count reaches 0. SSR error-projection options (`:public-error-id`, `:dev-error-detail?`) are per-frame, on the frame's `:ssr` map. Framework-defined sub-keys are namespaced (`:rf.egress/threshold-bytes`); per-knob sub-keys are not (`:depth`, `:trace-events-keep`).
- **Example**:
  ```clojure
  (rf/configure! {:epoch-history {:depth 100}
                  :trace-buffer  {:events-retained 25}
                  :elision       {:rf.egress/threshold-bytes 8192}
                  :observability {:errors [{:sink :my-app.sinks/sentry}]}})
  ```

### `current-config`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-config) → config-map
  ```
- **Description**: Returns the process-level config in effect, in `configure!`'s nested shape. Use it to answer "what is this process running with?" from a tool, a health check or a diagnostic panel.
    - It reports process values only. A frame's own settings, such as `:rf.trace/events-retained` in its metadata, are not reflected.
    - A key is absent, not `nil` and never a made-up default, when the code that owns it is not loaded. The two optional keys are independent: `:epoch-history` comes from the optional `day8/re-frame2-epoch` artefact and `:trace-buffer` from the development trace tooling, so a production bundle without the trace tooling omits `:trace-buffer` alone. `(get-in (rf/current-config) [:epoch-history :depth])` therefore reads `nil` only when the epoch artefact is absent, and you never resolve optional-artefact vars yourself.
    - `:observability` is absent when no process default has been declared; its code is always loaded, so the absence is about configuration, not the build. It reports the declared default as written, never a frame's effective policy, which is resolved per stream and per record.
    - The result is a key-by-key snapshot, not a transactional one, and is not promised to be wire-serialisable. User-namespaced keys that `configure!` accepted (`:myapp/thing`) are not reported, because the runtime reads nothing from them.
- **Example**:
  ```clojure
  (rf/configure! {:epoch-history {:depth 100}})

  (rf/current-config)
  ;; => {:epoch-history {:depth 100 :trace-events-keep 50}
  ;;     :trace-buffer  {:events-retained 50}
  ;;     :elision       {:rf.egress/threshold-bytes 16384}}

  (get-in (rf/current-config) [:epoch-history :depth])   ;; => 100
  ```

### `features`

- **Kind**: function
- **Signature**:
  ```clojure
  (features) → {feature-keyword inspection-entry}
  ```
- **Description**: Returns every optional feature with its status: a map from feature keyword to the feature's coordinates (`:maven`, `:require`, `:spec`) merged with its live `:loaded?` flag. It reads an always-loaded feature registry, with no exception and no classpath probe. The features are `:schemas`, `:machines`, `:routing`, `:flows`, `:http`, `:ssr`, `:epoch` and `:resources`.
    - It is the only feature-inspection function. Read the flag you need out of the map; an unknown feature keyword has no entry, so the lookup returns `nil`.
    - To fail at boot rather than on first use, write the check yourself, as in the example. It is one line; don't use `assert`, which can be compiled out.
- **Example**:
  ```clojure
  (rf/features)
  ;; => {:epoch {:maven "day8/re-frame2-epoch" :require "re-frame.epoch" :loaded? true} …}

  (get-in (rf/features) [:routing :loaded?])   ;; => true when day8/re-frame2-routing is on the classpath

  (when-not (get-in (rf/features) [:epoch :loaded?])
    (throw (ex-info "re-frame.epoch is not on the classpath"
                    (get (rf/features) :epoch))))
  ```

## Feature registration (re-exports)

Each optional feature's registration macros are on this facade, so one require covers every registration. The entries here are pointers: the feature's page documents each macro in full, along with the feature's `:fx` ids, standard events and standard subscriptions.

### Machines → [re-frame.machines.md](re-frame.machines.md)

A state machine is registered with one call and is itself an event handler; its transition table is data. Read a machine's `{:state :data :tags}` snapshot with `@(rf/subscribe [:rf/machine machine-id])`, and test a tag reactively with `@(rf/subscribe [:rf.machine/has-tag? machine-id tag])`. The `[:rf.machine/spawn …]`, `[:rf.machine/destroy …]` and `[:raise …]` fx are on the machines page, as is the plain-function `reg-machine*`.

#### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec)
  (reg-machine machine-id opts machine-spec)
  ```
- **Description**: Registers a state machine as the event handler for `machine-id`, keeping per-element source coordinates for Xray. The optional `opts` map is registration metadata; its `:schema` validates the dispatched outer event vector. See [`reg-machine`](re-frame.machines.md#reg-machine).

#### `defmachine`

- **Kind**: macro
- **Signature**: `(defmachine name ?docstring machine-spec)`
- **Description**: Defines a machine spec as a Var, capturing its source at the definition site so a later `(rf/reg-machine :id name)` keeps it. See [`defmachine`](re-frame.machines.md#defmachine).

### Routing → [re-frame.routing.md](re-frame.routing.md)

Routes are data. The current route lives in `runtime-db` and is read with the `:rf/route` sub. The routing events (`:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf.route/url-requested`), fx (`[:rf.nav/push-url …]`, `[:rf.nav/replace-url …]`, `[:rf.nav/scroll …]`) and the `:rf/route` sub family are on the routing page. The browser URL listener comes with the frame's `:url-bound?` lifecycle, installed at creation and removed at destroy, so there is no `install-url-listener!` or `install-history-listener!` here.

#### `reg-route`

- **Kind**: macro
- **Signature**: `(reg-route id metadata path)`
- **Description**: Registers a route: an id to navigate to, a URL path, and metadata carrying match events and guards (`:on-match`, `:can-leave`, `:params`, `:query`, …). See [`reg-route`](re-frame.routing.md#reg-route).

#### `route-link`

- **Kind**: component
- **Signature**: `[rf/route-link {:to :route-id :params {...} :query {...} :fragment "..."} & children]`
- **Description**: Renders an `<a href=...>` for a route id and dispatches `:rf.route/url-requested` on a plain primary-button click. Modifier-key and middle clicks, and `:target` / `:download` anchors, are left to the browser. It is registered as the view `:route/link`. See [`route-link`](re-frame.routing.md#route-link).

### Flows → [re-frame.flows.md](re-frame.flows.md)

A flow is derived state: it reads declared frame-state inputs, computes a value with a pure function, and writes it to an `app-db` output path, recomputing when the inputs change. The runtime-registration fx `[:rf.fx/reg-flow …]` and `[:rf.fx/clear-flow …]` are on the flows page; `(rf/clear :flow id)` removes a flow.

#### `reg-flow`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-flow flow-id metadata derive-fn)
  ```
- **Description**: Registers a flow. `metadata` carries `:inputs` and `:output-path` (both required) and optionally `:doc`, `:schema` and the `:frame` to mount on; `derive-fn` is pure. Returns `flow-id`. Throws `:rf.error/flows-artefact-missing` when `re-frame.flows` is not loaded. See [`reg-flow`](re-frame.flows.md#reg-flow).

### Schemas → [re-frame.schemas.md](re-frame.schemas.md)

Malli schemas attached to `app-db` paths, validated on writes in development builds and not in production. The introspection functions (`app-schemas`, `app-schema-meta`, …) and validator hooks are on the schemas page. To validate an untrusted event payload in every build, set `:boundary? true` on [`reg-event`](#reg-event).

#### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema)
  (reg-app-schema path metadata schema)
  ```
- **Description**: Attaches a Malli schema to an `app-db` path. The path is the registration id, which makes this the only path-keyed `reg-*`; the optional metadata map carries the `:frame` target. Production builds register the schema but never check it. See [`reg-app-schema`](re-frame.schemas.md#reg-app-schema).

#### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})
  (reg-app-schemas {path-1 schema-1, ...} opts)
  ```
- **Description**: Registers many path-to-schema entries against the current frame (or the `:frame` in `opts`) in one call, and returns the vector of registered paths. Checked in development builds only, as for `reg-app-schema`. See [`reg-app-schemas`](re-frame.schemas.md#reg-app-schemas).

### SSR → [re-frame.ssr.md](re-frame.ssr.md)

Only the two SSR registration macros, `reg-head` and `reg-error-projector`, are on this facade. They resolve to `re-frame.ssr` when the `day8/re-frame2-ssr` artefact is on the classpath and throw `:rf.error/ssr-artefact-missing` otherwise.

Everything else is called on `re-frame.ssr`: `render-to-string`, `render-tree-hash`, `project-error`, `head-model`, `head-model->html` and `hydrate!` (the head functions are also on `re-frame.ssr.head`). Requiring `re-frame.ssr` is what installs the SSR runtime, so an app that renders on the server already names it. The SSR page also documents the `:rf.server/*` fx and the `:rf/server-init` and `:rf/hydrate` events; the Ring adapter is on [re-frame.ssr.ring](re-frame.ssr.ring.md).

SSR registers no subscriptions: `:rf/head` and `:rf/public-error` are data shapes, read with `head-model` and `project-error` ([details](re-frame.ssr.md#subscriptions--there-are-none)).

#### `reg-head`

- **Kind**: macro
- **Signature**: `(reg-head id ?metadata head-fn)`
- **Description**: Registers a head function `(fn [db route] head-model)` under `id`; a route uses it through its `:head` metadata. See [`reg-head`](re-frame.ssr.md#reg-head).

#### `reg-error-projector`

- **Kind**: macro
- **Signature**: `(reg-error-projector id ?metadata projector-fn)`
- **Description**: Registers a projector `(fn [trace-event] :rf/public-error)` that turns an error trace into the public error a server response shows. A frame selects one with its `:ssr {:public-error-id …}` metadata. See [`reg-error-projector`](re-frame.ssr.md#reg-error-projector).

### HTTP → [re-frame.http.md](re-frame.http.md)

Managed HTTP is one fx, `[:rf.http/managed …]`, with one args map and a closed failure taxonomy. The fx, `[:rf.http/managed-abort …]` and the failure taxonomy are on the HTTP page. Request stubbing (`with-request-stubs` and the raw install/uninstall pair) is on `re-frame.http.test-support`, not this facade.

#### `reg-http-interceptor`

- **Kind**: macro
- **Signature**: `(reg-http-interceptor id interceptor-map)`
- **Description**: Adds an interceptor to a frame's `:rf.http/managed` middleware chain. `:before (fn [ctx] ctx')` runs on the request side, in registration order; `:after (fn [ctx response] response')` runs on the response side, in reverse order. Remove one with `(rf/clear :http-interceptor id)`, or `(rf/clear :http-interceptor id {:frame target})` to name the frame. See [`reg-http-interceptor`](re-frame.http.md#reg-http-interceptor).

### Resources → [re-frame.resources.md](re-frame.resources.md)

Resources are cached server-state reads, and mutations are the writes that update them. Their keyword-addressed events and subscriptions (`[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.mutation/execute …]`, the passive `:rf.resource/*` and `:rf.mutation/*` subs, …) are on the resources page.

- Focus and reconnect revalidation is the frame's `:revalidate-on #{:focus :reconnect}` config key; the frame lifecycle installs and removes the host listeners, so there is no `install-revalidation-listeners!` here. See [Revalidation is a frame property](re-frame.resources.md#revalidation-is-a-frame-property).
- `(rf/clear :resource id)`, `(rf/clear :mutation id)` and `(rf/clear :resource-scope id)` remove a registration and dispose its per-frame runtime state. That is not cache invalidation, nor the `[:rf.mutation/clear …]` reset of a mutation instance. See [Clearing a registration](re-frame.resources.md#clearing-a-registration).

#### `reg-resource`

- **Kind**: macro
- **Signature**: `(reg-resource resource-id metadata request-fn)`
- **Description**: Registers a resource, a cached read, as data. `metadata` carries the required, fail-closed `:scope` policy and `:params-schema`; `request-fn` returns a managed-HTTP args map. See [`reg-resource`](re-frame.resources.md#reg-resource).

#### `reg-mutation`

- **Kind**: macro
- **Signature**: `(reg-mutation mutation-id metadata request-fn)`
- **Description**: Registers a mutation: a named write that, on success, invalidates, patches or populates cached reads. See [`reg-mutation`](re-frame.resources.md#reg-mutation).

#### `reg-resource-scope`

- **Kind**: macro
- **Signature**: `(reg-resource-scope scope-id metadata resolve-fn)`
- **Description**: Registers a named scope resolver that a resource's `:scope` policy refers to. This is the only arity: the resolve fn is the last argument, and `metadata` must declare `:inputs` (to read the whole db, declare the root path: `{:inputs {:db [:db []]}}`). Returns `scope-id`. See [`reg-resource-scope`](re-frame.resources.md#reg-resource-scope).

#### `resolve-resource-scope`

- **Kind**: function
- **Signature**: `(resolve-resource-scope db scope-id)`
- **Description**: Resolves the named scope resolver against `db`. See [`resolve-resource-scope`](re-frame.resources.md#resolve-resource-scope).

#### `resource-state`

- **Kind**: function
- **Signature**: `(resource-state {:resource … :scope … :params … :frame …}) → entry or nil`
- **Description**: Returns a resource instance's durable runtime entry in an explicit frame, resolving the scoped key as a subscription would. For tools and tests. See [`resource-state`](re-frame.resources.md#resource-state).

#### `mutation-state`

- **Kind**: function
- **Signature**: `(mutation-state {:instance … :frame …}) → row or nil`
- **Description**: Returns a mutation instance's durable runtime row (`{:status :result :error …}`) in an explicit frame. For tools and tests. See [`mutation-state`](re-frame.resources.md#mutation-state).

#### Reading a registered resource's or mutation's spec

There is no `resource-meta` or `mutation-meta`. Read a registration's spec with the generic registrar query and its inner key, which needs no artefact (see [Reading registrations](re-frame.resources.md#reading-registrations)):

```clojure
(:rf/resource (rf/handler-meta {:source :store :kind :resource :id :article/by-slug}))
(:rf/mutation (rf/handler-meta {:source :store :kind :mutation :id :article/save}))
```

#### Enumerating resources and mutations

There is no `resources` or `mutations` function. Three reads answer three different questions:

```clojure
;; 1. REGISTRY — what is registered? Process-global registrar; no frame.
(keys (rf/registrations {:source :store :kind :resource}))  ;; => (:article/by-slug :feed/timeline)
(keys (rf/registrations {:source :store :kind :mutation}))  ;; => (:article/save)

;; 2. WHOLE LIVE TABLE — the reserved runtime-db path, off the frame-state projection.
(get-in (rf/frame-state-value :app/main) [:rf.db/runtime :rf.runtime/resources :entries])
;; => {<key-id> <entry> …}
(get-in (rf/frame-state-value :app/main) [:rf.db/runtime :rf.runtime/mutations])
;; => {<key-id> {:mutation/id … :instance/id … :status … :result … :error …} …}

;; 3. ONE ENTRY / ONE INSTANCE — the per-target live-state reads above.
(rf/resource-state {:resource :article/by-slug :scope :rf.scope/global
                    :params {:slug "welcome"} :frame :app/main})
(rf/mutation-state {:instance :form/save-1 :frame :app/main})
```

Read the tables as they are keyed. `:entries` is keyed by each entry's CEDN-1 byte `key-id`, and each entry carries its own `:resource/key` tuple. `:rf.runtime/mutations` is keyed by the CEDN-1 byte `key-id` of each mutation instance id, never by mutation id, and each row carries its own `:instance/id`. Re-keying either table on its human-readable field can collapse distinct rows. Both subtrees are created on first use, so either read can return `nil` before then. See [Enumerating the whole live table](re-frame.resources.md#enumerating-the-whole-live-table).

## Instrumentation and listeners

There are two ways to observe a running app, with different functions:

- The trace and epoch streams are for development. The runtime emits a tagged trace record for each noteworthy step and an assembled epoch record per processed event, keeps them in per-frame rings, and delivers them synchronously to listeners registered with `register-listener!`. All of it is removed under `:advanced` with `goog.DEBUG=false`. The epoch (time-travel) functions are also on [re-frame.epoch](re-frame.epoch.md).
- Observability sinks work in every build. They receive one record per processed event and one per `:rf.error/*` error. Register a sink with [`register-observability-sink!`](#register-observability-sink) and name it in a frame's `:observability` policy or the `(rf/configure! {:observability …})` process default.

See [Observability](../core/observability.md).

### `register-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-listener! stream id callback-fn)
  ```
- **Description**: Registers `callback-fn` under `id` to receive every record the runtime emits on `stream`, for development tools. `stream` is `:trace` (development only, removed from production builds) or `:epoch` (development only, from the optional epoch artefact); both deliver raw records.
    - Delivery is synchronous: the callback returns before the next record. On the JVM, where emits can race across threads, each listener is called serially and never concurrently with itself, so tool appenders and stateful folds need no locking of their own.
    - Registering an id again on the same stream replaces the listener. Returns `id`, or `nil` on the `:epoch` stream when the `day8/re-frame2-epoch` artefact is absent. An unknown `stream` throws `:rf.error/unknown-listener-stream`.
    - The `:epoch` stream's publication rules are under [Epoch-settled listeners](#epoch-settled-listeners).
    - It is not a production API. To ship telemetry off-box, register a sink with [`register-observability-sink!`](#register-observability-sink) and name it in a frame's `:observability` policy, or in `(rf/configure! {:observability …})`, which also receives records with no resolvable frame. Sinks receive records already projected under the governing classification. See [Report errors in production](../core/how-to/report-errors-in-production.md).
- **Example**:
  ```clojure
  ;; Dev-only: tap every trace event the runtime emits (DCE'd in production).
  (rf/register-listener! :trace :my-app/trace-tap
    (fn [trace-event]
      (js/console.log (:op-type trace-event) (:operation trace-event))))

  ;; Dev-only: one assembled :rf/epoch-record per dequeued event. Returns nil
  ;; (and registers nothing) when day8/re-frame2-epoch is absent.
  (rf/register-listener! :epoch :my-app/epoch-tap
    (fn [record]
      (js/console.log (:frame record) (:epoch-id record))))
  ```

### `unregister-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-listener! stream id) → nil
  ```
- **Description**: Removes the listener registered under `id` on `stream`. There is no `clear-listeners!`; the test fixtures in [`re-frame.test-support`](re-frame.test-support.md) reset every listener between tests.
- **Example**:
  ```clojure
  (rf/unregister-listener! :trace :my-app/trace-tap)
  ```

### `emit-trace-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (emit-trace-event! op-type operation tags) → nil
  ```
- **Description**: Emits a custom trace event. The framework already emits the events that matter; use this sparingly, for application-specific concerns the framework cannot know about.
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
- **Description**: Returns the named frame's development trace ring without clearing it: by default one bundle per retained pipeline run; with `{:flat true}`, the raw trace events.
    - Returns `[]` for a destroyed or unknown frame, and always in production, where the ring is never allocated.
    - The number of retained events is the `(rf/configure! {:trace-buffer {:events-retained N}})` setting.
    - `group-by-event` and `domino-bucket`, which group trace events into bundles, are on [`re-frame.trace.projection`](re-frame.trace.projection.md), not this facade.
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
  (clear-trace-buffer!) → nil
  ```
- **Description**: Empties the retained trace events of the named frame, or of every frame. It clears data, not settings: each ring is re-emptied at its own `:rf.trace/events-retained` cap, so the `:trace-buffer` default and every per-frame override survive, and the hot-reload dedup table is untouched. A no-op for an unknown frame and in production.
- **Example**:
  ```clojure
  (rf/clear-trace-buffer! :app/main)   ;; empty one frame's ring (e.g. between tool sessions)
  (rf/clear-trace-buffer!)             ;; empty every ring; retention policy stays put
  ```

### `project-egress`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-egress record-or-value)
  (project-egress record-or-value opts)
  ```
- **Description**: Projects a record or value for sending off-box, applying the frame's data classification under an egress profile. Call it before handing anything to an off-box sink; it is the only record-level egress function.
    - It dispatches on a record's `:kind`, `:rf.observe/handled-event`, `:rf.observe/error`, `:rf.observe/derived-tree` or `:rf/epoch-record`, to a projector that is not itself public. An input without a recognised `:kind` is walked as a plain value against the frame's classification.
    - Projecting an `:rf/epoch-record` needs the optional `day8/re-frame2-epoch` artefact. Without it the call throws `:rf.error/epoch-artefact-missing`, naming the kind, rather than walking the record as a plain value, which would ship its `app-db` slots raw.
    - `opts` is a closed map of eleven keys: `:rf.egress/profile` (one of six profiles), `:frame`, `:path`, `:query-v`, the four overrides `:rf.egress/include-sensitive?`, `:rf.egress/include-large?`, `:rf.egress/include-digests?` and `:rf.egress/threshold-bytes`, and three epoch-only keys. An unknown key throws `:rf.error/bad-egress-opts`, naming it; an unknown profile throws `:rf.error/unknown-egress-profile`.
    - The epoch-only keys `:rf.egress/include-fx-args?`, `:rf.egress/include-runtime-db?` and `:rf.egress/include-event-args?` are trusted-local opt-ins for data only an `:rf/epoch-record` has: effect `:args`, the `:rf.db/runtime` partition, and trigger and trace event args. Each defaults to false and enables only its own data; `:rf.egress/include-sensitive? true` implies none of them. On other kinds they are accepted and ignored.
    - The frame whose classification applies is chosen by key presence: an explicit `:frame` in `opts` (even `nil`), else a recognised record's own `:frame` (even `nil`), else the current frame scope. A plain value that happens to carry a `:frame` key is a value and contributes no frame.
    - When no frame is known, an explicit `nil` included, and `:rf.egress/include-sensitive?` is not `true`, the value is redacted to `:rf/redacted`. There is no fallback to `:rf/default`.
    - To project a whole epoch ring, map over it: `(mapv #(rf/project-egress % opts) (rf/epoch-history frame-id))`. The ring and its listeners always hold the raw record, so projecting never affects `restore-epoch!`. See [re-frame.epoch](re-frame.epoch.md).
    - See [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
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
- **Description**: Registers a production observability sink `f` under the keyword `sink-id`, the id a frame's `:observability {:handled-events [{:sink <sink-id> :rf.egress/profile …}]}` entry names. Use it to send handled events and errors to a monitoring service.
    - `f` receives one record at a time, already projected under the owning frame's classification and the entry's egress profile; it does no redaction of its own.
    - Registering the id again replaces the sink. Returns `sink-id`.
    - It works in every build, including CLJS `:advanced` with `goog.DEBUG=false`.
    - A sink is frame-scoped and receives profile-projected records; `register-listener!` is cross-frame and delivers raw records.
- **Example**:
  ```clojure
  (rf/make-frame
    {:id :app/main
     :observability {:handled-events
                     [{:sink :my-app.sinks/datadog
                       :rf.egress/profile :rf.egress/off-box-observability}]}})

  (rf/register-observability-sink! :my-app.sinks/datadog
    (fn [record]                      ;; already projected — no sink-local redaction
      (datadog/send record)))
  ```

### `unregister-observability-sink!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-observability-sink! sink-id) → nil
  ```
- **Description**: Removes the observability sink registered under `sink-id`. Returns `nil`.
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
- **Description**: Returns true when `trace-event` is a map with a truthy `:sensitive?` at the top level (not under `:tags`). Use it to drop sensitive events before forwarding them; call it directly rather than wrapping it.
    - It fails closed: `true` is sensitive; `false`, `nil` and absent are not; and any other truthy value is also sensitive. The trace-event schema types `:sensitive?` as a boolean, so a string, keyword or number is a contract violation, and forwarding on a violation is the one outcome that cannot be undone.
- **Example**:
  ```clojure
  (rf/sensitive? {:sensitive? true})     ;; => true
  (rf/sensitive? {:sensitive? false})    ;; => false
  (rf/sensitive? {})                     ;; => false
  ;; A malformed stamp is treated as sensitive, not ignored.
  (rf/sensitive? {:sensitive? "true"})   ;; => true
  (rf/sensitive? {:sensitive? :yes})     ;; => true
  ;; Drop sensitive events when forwarding from a flat trace read.
  (remove rf/sensitive? (rf/trace-buffer :app/main {:flat true}))
  ```

### `epoch-history`

- **Kind**: function (dev-only)
- **Signature**:
  ```clojure
  (epoch-history frame-id) → vector of epoch records
  ```
- **Description**: Returns the frame's retained epoch records in development builds. Tools use it for time travel and post-mortem analysis. Production builds remove it. See [re-frame.epoch](re-frame.epoch.md).
    - Ordinary processing records one epoch per dequeued event, not one per drain: a parent event and a `:fx [[:dispatch …]]` child it queues are separate records, while a machine macrostep is one.
    - `replace-frame-state!` writes and `:halted-depth` halts also add synthetic records. A `:halted-destroy` halt goes to listeners only and is never retained (see [Epoch-settled listeners](#epoch-settled-listeners)).
    - Returns `[]` for an unknown or destroyed frame, and when the `day8/re-frame2-epoch` artefact is absent.
- **Example**:
  ```clojure
  (rf/epoch-history :app/main)
  (last (rf/epoch-history :app/main))   ;; peek the latest
  ```

### `restore-epoch!`

- **Kind**: function (dev-only)
- **Signature**:
  ```clojure
  (restore-epoch! frame-id epoch-id) → boolean
  ```
- **Description**: Rewinds the frame's whole state, `app-db` and `runtime-db`, to the named epoch's `:frame-state-after`, in one atomic write, so machine snapshots, the route slice and other `runtime-db` data rewind with `app-db`. Returns `true` on success. For an unknown or destroyed frame it returns `false` and emits `:rf.error/no-such-handler` of kind `:frame`; it also returns `false` when the `day8/re-frame2-epoch` artefact is absent.
- **Example**:
  ```clojure
  ;; Time-travel: rewind a frame's whole frame-state to a recorded epoch.
  (let [target (last (rf/epoch-history :app/main))]
    (rf/restore-epoch! :app/main (:epoch-id target)))
  ```

### `replay-epoch!`

- **Kind**: function (dev-only)
- **Signature**:
  ```clojure
  (replay-epoch! frame-id epoch-id)      → envelope map (false when elided / artefact absent)
  (replay-epoch! frame-id epoch-id opts) → envelope map (false when elided / artefact absent)
  ```
- **Description**: Runs the named retained epoch's recorded event again through the frame's own handlers, as a strict replay. It resolves the raw `:trigger-event`, the recorded `:rf.cofx` under `:rf.cofx/mint-policy :strict`, and the record's own `:fx-overrides` and `:interceptor-overrides` in-process, so nothing is copied by hand.
    - It replays into the same frame and does not restore state first; compose it with `restore-epoch!` for that. Effects run again, and the replay records a new ordinary epoch.
    - Returns a `{:ok? …}` envelope, or `false` when the call is elided or the `day8/re-frame2-epoch` artefact is absent. Refusals are decided before anything dispatches: an unknown frame, a drain in flight, an unknown or aged-out id, a halted, synthetic or incomplete record, or a recorded `:rf/fn-override`.
    - A declared coeffect missing from the record is the hard error `:rf.error/missing-required-cofx`; the generator is never called in its place.
    - See [`replay-epoch!`](re-frame.epoch.md#replay-epoch).
- **Example**:
  ```clojure
  ;; Strict replay of the latest epoch — the generator is never consulted.
  (let [source (last (rf/epoch-history :app/main))]
    (rf/replay-epoch! :app/main (:epoch-id source)))
  ```

### `replace-frame-state!`

- **Kind**: function (dev-only)
- **Signature**:
  ```clojure
  (replace-frame-state! frame-id frame-state) → boolean
  ```
- **Description**: Writes one or both partitions of a frame's state directly, outside the event pipeline. `frame-state` is a partial map, any subset of `{:rf.db/app … :rf.db/runtime …}`: a present key replaces that partition and an absent key is left unchanged. It is the only frame-state write function; there is no `replace-app-db!`, `reset-app-db!` or `replace-runtime-db!`.
    - It records a synthetic epoch, so `restore-epoch!` can rewind it.
    - A map with no recognised partition key, or with any other key, is rejected with `:rf.error/replace-frame-state-bad-keys`, checked before the frame is resolved.
    - Returns `true` on success, `false` on a documented failure. Throws `:rf.error/epoch-artefact-missing` when the `day8/re-frame2-epoch` artefact is absent.
- **Example**:
  ```clojure
  ;; App-only state injection.
  (rf/replace-frame-state! :app/main {:rf.db/app {:counter 0}})

  ;; App-only reset to {} while runtime-db survives.
  (rf/replace-frame-state! :app/main {:rf.db/app {}})

  ;; Runtime-only injection — app-db untouched.
  (rf/replace-frame-state! :app/main {:rf.db/runtime new-runtime-db})

  ;; Full-frame install — both partitions atomically.
  (rf/replace-frame-state! :app/main {:rf.db/app new-db :rf.db/runtime new-runtime-db})
  ```

### Epoch-settled listeners

Epoch listeners register on the `:epoch` stream of `register-listener!`, exactly like `:trace`. There is no separate `register-epoch-listener!`.

- **Signature**: `(rf/register-listener! :epoch key callback-fn)` / `(rf/unregister-listener! :epoch key)`
- **Description**: Registers a process-wide listener for assembled epoch records, in development builds. The callback runs each time a record is published, which is not once per event:
    - when an epoch first commits, and again with the same `:epoch-id` when a late render, sub run or unmount attributed to that epoch corrects the record, so a consumer caching `epoch-history` picks up the fixed record;
    - for a `:rf.epoch/db-replaced` record on each `replace-frame-state!` write, and a `:halted-depth` record when a drain hits the depth ceiling (both retained in the ring when depth permits);
    - for the terminal `:halted-destroy` record, an already-started event interrupted by frame destruction, which goes to listeners only and is never retained.

    The listener is process-wide while `:epoch-id` is unique only within one frame, so key cached records on `[(:frame record) (:epoch-id record)]` and replace an entry on republication rather than counting callbacks; `:outcome` is record state, not identity. Registering the same `key` again replaces the listener. When a frame the callback observed is destroyed, the callback gets a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace (see [`epoch-silence-current?`](#epoch-silence-current)). Returns `key`, or `nil` when the `day8/re-frame2-epoch` artefact is absent.
- **Example**:
  ```clojure
  ;; Key by [frame epoch-id]; a re-published record replaces its entry.
  (def epochs (atom {}))
  (rf/register-listener! :epoch :my-app/epoch-watch
    (fn [record]
      (swap! epochs assoc [(:frame record) (:epoch-id record)] record)))

  (rf/unregister-listener! :epoch :my-app/epoch-watch)
  ```

### `epoch-silence-current?`

- **Kind**: function (dev-only)
- **Signature**:
  ```clojure
  (epoch-silence-current? tags) → boolean
  ```
- **Description**: Tells a trace listener whether a `:rf.epoch.cb/silenced-on-frame-destroy` signal still describes a current fact. Pass the signal's `:tags` map (`:frame`, `:cb-id`, `:observed-gen`). Returns true when `:observed-gen` is still the generation registered under `:cb-id` and that registration is not observing `:frame` right now, decided from one consistent snapshot of the listener ledger. Returns false otherwise, including for a `nil` or absent `:observed-gen` and when the `day8/re-frame2-epoch` artefact is absent.
- **Example**:
  ```clojure
  (rf/register-listener! :trace :my-app/silence-watch
    (fn [ev]
      (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                 (rf/epoch-silence-current? (:tags ev)))
        (js/console.log "epoch callback silenced" (:cb-id (:tags ev))))))
  ```

## Registrar queries

The registrar holds every registration (events, subs, fx, cofx, interceptors, machines, views, routes, resources) as queryable data, which is what the framework's tools are built on. These are the reads; the writes are the `reg-*` forms and [`clear`](#clear). All of them run on the JVM.

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations {:source :store :kind k}) → {id metadata-map}
  (registrations {:frame f      :kind k}) → {id metadata-map}
  ```
- **Description**: Returns every registration of one kind, with the full metadata map per id: source coordinates, `:sensitive`, `:rf/machine?`, `:platforms`, the doc string. The argument is always a map, and it names exactly one source.
    - `{:source :store …}` reads the process-wide registrar and never consults a frame's image, so it answers the same inside any frame; this is what a tool inspecting a host application wants. `:store` is the only `:source` value.
    - `{:frame f …}` returns only the ids that frame's image carries, resolved through its sealed image generation. `f` is a frame-id keyword or a live frame value; one that does not resolve to a live frame carrying a generation raises `:rf.error/frame-no-generation`.
    - Naming both `:source` and `:frame`, naming neither, a `:source` other than `:store`, or a non-map argument (usually a leftover positional call) raises `:rf.error/registrar-query-needs-source`. There is no default source.
    - `:kind :flow` and `:kind :frame` raise `:rf.error/registrar-kind-not-queryable`, naming the function to use instead (`re-frame.flows/flows`, `flow-meta`, `flows-snapshot`; `frame-ids`, `frame-meta`), rather than returning a misleading `{}`.
    - To filter, use `filter` on the result; there is no predicate arity.
- **Example**:
  ```clojure
  (rf/registrations {:source :store :kind :event})
  ;; => {:counter/inc {:ns my-app.events :line 12 :file "my_app/events.cljs"} ...}
  (rf/registrations {:frame :tenants/acme :kind :sub})
  ```

### `handler-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (handler-meta {:source :store :kind k :id id}) → registration-metadata map (or nil)
  (handler-meta {:frame f      :kind k :id id}) → metadata resolved through frame f's image (or nil)
  ```
- **Description**: Returns the metadata a `reg-*` form recorded for one id, or `nil`. View registrations include source coordinates (`:ns`, `:line`, `:column`, `:file`); Pair tools resolve `data-rf2-source-coord` DOM annotations to a `:file` through this lookup.
    - Registrar kinds: `:event`, `:sub`, `:fx`, `:cofx`, `:interceptor`, `:view`, `:route`, `:head`, `:error-projector`, `:resource`, `:mutation`, `:resource-scope`. `:flow` and `:frame` raise, as for `registrations`.
    - The two machine kinds `:machine-guard` and `:machine-action` take a 2-vector id: `(handler-meta {:source :store :kind :machine-guard :id [machine-id guard-id]})`. They are derived on demand from the machine's registration and exist only in development builds; they are not frame-targetable, so `:frame` returns `nil` for them.
    - The source grammar and the errors are those of `registrations` (`:rf.error/registrar-query-needs-source`, `:rf.error/frame-no-generation`, `:rf.error/registrar-kind-not-queryable`).
    - App-db schemas are not a registrar kind; look them up with `(app-schema-meta {:frame f :path p})` in [re-frame.schemas](re-frame.schemas.md).
- **Example**:
  ```clojure
  (rf/handler-meta {:source :store :kind :sub :id :counter/value})
  ;; => {:ns my-app.subs :line 8 :column 1 :file "my_app/subs.cljs"}
  ```

### `frame-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-ids)
  (frame-ids ns-prefix)
  ```
- **Description**: Returns the set of live (not destroyed) frame ids. The optional string prefix filters by namespace.
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
- **Description**: Returns the metadata `make-frame` recorded for a frame: `:id` plus the supplied config after preset expansion (`:fx-overrides`, `:interceptors`, `:ssr`, `:initial-events`, …), merged with lifecycle fields (`:created-at`, …).
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
- **Description**: Returns the frame's current `app-db` as a plain map, not the container, or `nil` for an unknown or destroyed frame. Accepts a frame-id keyword or a live frame value. There is no `snapshot-of`; read a path with `(get-in (rf/app-db-value frame-id) path)`.
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
- **Description**: Returns the frame's whole state, `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an unknown or destroyed frame. Use it for SSR, epochs, time travel and Xray, and to save state for [`:rf/install-frame-state`](#rfinstall-frame-state). A fresh frame's state is `{:rf.db/app {} :rf.db/runtime {}}`. There is no `runtime-db-value`; read `(:rf.db/runtime (rf/frame-state-value frame-id))`.
- **Example**:
  ```clojure
  (:rf.db/runtime (rf/frame-state-value :rf/default))
  ;; => {:rf.runtime/machines {...}}
  ```

## See also

- [Subscriptions](../core/subscriptions.md), [Frames](../core/frames.md), [Effects](../core/effects.md), [Coeffects](../core/coeffects.md) and [Observability](../core/observability.md): the concept guides behind these functions.
- [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md): the task guides.
- [Core glossary](../core/glossary.md).
