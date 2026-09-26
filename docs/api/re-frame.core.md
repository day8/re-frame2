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

;; reg-view binds `dispatch` and `subscribe` to the frame the view renders in.
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

The `reg-*` forms register a handler under an id (for `reg-app-schema`, a path).

Most take a metadata map before the handler. It is optional for `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-interceptor`, `reg-view*`, `reg-machine`, `reg-app-schema`, `reg-head` and `reg-error-projector`, and required for `reg-route`, `reg-flow`, `reg-resource`, `reg-mutation` and `reg-resource-scope`. The others differ: `reg-view` takes a docstring and `^{:rf/id …}` metadata on its symbol, `reg-http-interceptor` carries its metadata in its one interceptor map, and `reg-app-schemas` takes its options after the schema map.

On CLJS, the `reg-*` names are macros in call position and plain functions in value position, except `reg-view` and `reg-machine`, which are macros only; their function forms are `reg-view*` and `re-frame.machines/reg-machine*`. A call such as `(rf/reg-event :counter/inc handler)` records its source coordinates, so tools like Xray can go from a registration back to that expression. Passed as a value, as in `(apply rf/reg-event args)`, the name is a function that skips the recording: the registration has no namespace, so an image's `:select-ns` cannot select it and only the default image runs it, unless its metadata carries `:ns` (see [`image`](#image)). On the JVM the `reg-*` forms are macros only.

`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx` and `reg-interceptor` accept these bare metadata keys:

| Kind | Bare metadata keys |
|---|---|
| all five | `:doc`, `:schema`, `:tags` |
| `reg-event` | adds `:interceptors`, `:boundary?`, `:sensitive`, `:large`, `:large?` |
| `reg-sub` | adds `:inputs`, `:sensitive`, `:large`, `:large?` |
| `reg-fx` | adds `:platforms`, `:sensitive`, `:large`, `:large?` |
| `reg-cofx` | adds `:platforms`, `:recordable?`, `:provided?`, `:sensitive`, `:large`, `:large?` |

`reg-event`, `reg-sub` and `reg-interceptor` also accept `:platforms` and store it, and nothing reads it. Namespaced keys (`:rf.cofx/requires`, `:myapp/owner`) are always accepted and stored. See [What is validated](re-frame.schemas.md#what-is-validated) for `:schema`, and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) for `:sensitive` and `:large`. Registering one of the five can raise these (see [Errors](README.md#errors)):

- `:rf.error/retired-registration-key`: the map carries re-frame v1's `:spec`; use `:schema`. Thrown in every build.
- `:rf.error/bad-classification`: `:sensitive` or `:large` is not a vector of path vectors (`[[]]` for the whole value).
- `:rf.warning/unknown-registration-key` (development builds): a bare key outside the kind's set, usually a typo. The key is stored but never read.
- `:rf.warning/missing-doc` (development builds): a macro registration without `:doc`, once per id.
- `:rf.warning/registration-collision` (development builds): the id was already registered from a different source location. The new registration replaces the old one; re-evaluating the same file (hot reload) is silent.

### `reg-event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-event id ?metadata handler) → id
  ```
- **Description**: Registers an event handler; dispatching `[id …]` runs it. The handler is `(fn [coeffects event-vec] effect-map)`: it receives the coeffects map (`:db`, `:event`, `:rf.frame/id` and any coeffects it declares) and returns a closed effect map such as `{:db … :fx […]}`, or `nil` for no effects. `reg-event` is the only public event-registration form.
    - Return an effect map even when only `app-db` changes. Returning the new db itself, or putting an fx id such as `:dispatch` beside `:db`, rejects the event with `:rf.error/effect-map-shape`, and nothing commits (see [The effect map](#the-effect-map)). A non-map return emits `:rf.error/effect-handler-bad-return` and the event does nothing.
    - A handler that throws emits `:rf.error/handler-exception`, and the event commits nothing: `app-db` is unchanged and no `:fx` run. The rest of the queue still runs.
    - The optional metadata map carries reflection keys (`:doc`, `:schema`, `:tags`, …) and the `:interceptors` vector. Interceptors always go in the metadata map, never in a positional middle vector: `(reg-event :id {:interceptors [:my/audit]} handler)`.
    - To read or rewrite the interceptor context, register a named interceptor with [`reg-interceptor`](#reg-interceptor) and reference it by id from `:interceptors`. Its `:before` / `:after` fns receive and return the context map. There is no separate registration for raw-context handlers.
    - `{:boundary? true}` makes this handler's own `:schema` run in every build, including `:advanced` with `goog.DEBUG=false`, where development validation is otherwise removed. It adds no second schema. Set it on handlers that take data from outside the app: HTTP replies, websocket frames, `postMessage`.
        - The check runs before the interceptor chain, against the event vector as dispatched, so development and production validate the same value at the same point, and `:interceptor-overrides` cannot remove it.
        - A failed check skips the handler, sets `:outcome :rejected`, and emits the always-on `:rf.error/schema-validation-failure` record with `:source :boundary`. See [Validate with schemas](../core/how-to/validate-with-schemas.md).
    - See [Handlers return descriptions](../core/events.md#handlers-return-descriptions).
- **Errors**:
    - At registration:
        - `:rf.error/reg-event-db-removed`, `:rf.error/reg-event-fx-removed`: calling re-frame v1's `reg-event-db` or `reg-event-fx`; use `reg-event`.
        - `:rf.error/reg-event-ctx-removed`: calling v1's `reg-event-ctx`; register the context work with `reg-interceptor` and reference it from `:interceptors`.
        - `:rf.error/at-boundary-missing-schema`: `{:boundary? true}` without a `:schema` key. The key must be present: `{:schema nil :boundary? true}` registers.
        - `:rf.error/reg-event-bad-arity`: neither `(reg-event id handler)` nor `(reg-event id metadata handler)`.
        - `:rf.error/reg-event-bad-middle-slot`: the middle argument is not a map, such as re-frame v1's positional interceptor vector.
        - `:rf.error/reg-event-bare-interceptor`: an interceptor map passed as the middle argument instead of under `:interceptors`.
        - `:rf.error/reg-event-bad-interceptors`: `:interceptors` is not a vector of interceptor ids (`:my/ic` or `[id arg]`); an inline interceptor map in it raises `:rf.error/inline-interceptor-removed`.
        - `:rf.error/reserved-event-id`: the id is one of the framework-owned events `:rf/set-db`, `:rf/install-frame-state` or `:rf/settle-flows`.
        - `:rf.error/unregistered-interceptor`: an `:interceptors` entry names an id with no `reg-interceptor`. References are checked when `reg-event` runs, and a frame's `:interceptors` when `make-frame` runs, so register interceptors first.
        - `:rf.error/interceptor-factory-arity`: an entry's shape does not match the registered interceptor (see [`reg-interceptor`](#reg-interceptor)). Factories run at registration, so `[:rf.interceptor/path :cart]` throws `:rf.error/path-interceptor-bad-path` here.
        - `:rf.error/cofx-request-invalid`: `:rf.cofx/requires` is not a vector, or an entry is neither an id nor `[id arg]`.
        - `:rf.error/cofx-name-collision`: `:rf.cofx/requires` declares the same id twice.
    - When the event runs:
        - `:rf.error/effect-map-shape`: the return breaks the [effect map](#the-effect-map) rules, for example the new db returned bare, which rejects the event.
        - `:rf.error/effect-handler-bad-return`: the handler returned something other than a map or `nil`; the event does nothing.
        - `:rf.error/handler-exception`: the handler threw; nothing commits.
        - `:rf.error/schema-validation-failure` with `:source :boundary`: under `{:boundary? true}`, the event vector failed the handler's `:schema`; the handler does not run.
- **Example**:
  ```clojure
  ;; Don't do this: the new db is returned bare, so its keys sit at the top
  ;; level of the effect map and the event is rejected (:rf.error/effect-map-shape).
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] (update db :counter/value inc)))

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
  (reg-sub id ?metadata computation-fn) → id
  ```
- **Description**: Registers a subscription: a value computed from `app-db`, from other subscriptions, or both, which views read with `subscribe`. Dependencies go under `:inputs` in the metadata map, in one of three forms:

    | Mode | Form | Where the inputs come from |
    |---|---|---|
    | App-db reader | `(reg-sub id computation-fn)` | `:inputs` omitted. No upstream subscriptions; the computation fn receives `app-db` and the outer `query-v` (layer 1). |
    | Static inputs | `(reg-sub id {:inputs [q1 q2]} computation-fn)` | A literal list of query vectors, known and shape-checked at registration. |
    | Parametric inputs | `(reg-sub id {:inputs producer-fn} computation-fn)` | Computed from the outer `query-v` when the subscription is first created for that query vector. |

    - Use a subscription for a derived value that views read. When an event handler, a schema or another flow must read the derived value as plain `app-db` data, keep it in `app-db` with a flow ([`reg-flow`](#reg-flow)) instead.
    - Declared inputs always arrive as a vector, in declaration order, whether there are zero, one or many. Moving a dependency between the literal and the fn form never changes the body, and adding a second input never turns a scalar argument into a vector. `{:inputs []}` declares no dependencies and delivers `[]`; omitting `:inputs` delivers `app-db` itself.
    - An `:inputs` producer fn is a pure function from the outer `query-v` to a vector of query vectors. It must not call `subscribe`, deref `app-db`, dispatch, perform IO or return reactions. It never runs at registration; the runtime calls it when it creates the subscription for a query vector, and resolves each returned query vector in the same frame as the outer subscription.
    - There is no `reg-sub-raw`; `reg-sub` is the only subscription registration form. See [When the inputs depend on the arguments](../core/subscriptions.md#when-the-inputs-depend-on-the-arguments) for choosing between a literal vector and a fn.
- **Errors**:
    - At registration:
        - `:rf.error/reg-sub-bad-args`: the arguments are not `(reg-sub id ?metadata computation-fn)`. This includes re-frame v1's `:<-` sugar, a leading input fn, and an `:inputs` that is `nil`, not a vector of query vectors, and not a fn.
    - When the subscription computes (the subscription yields `nil`):
        - `:rf.error/sub-input-fn-bad-return`: an `:inputs` fn returned something other than a vector of query vectors, such as `[:a :b]` for `[[:a :b]]`.
        - `:rf.error/sub-input-fn-exception`: an `:inputs` fn threw.
        - `:rf.error/sub-exception`: the computation fn threw.
        - `:rf.error/sub-cycle` (development builds): the subscription depends on itself through its inputs.
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
  (reg-fx id ?metadata handler) → id
  ```
- **Description**: Registers an effect handler: the code that performs a side effect (network, storage, timers, the DOM), so that event handlers stay pure and only describe it. When an event handler returns `[id args]` in its `:fx` vector, the runtime calls the handler after `app-db` is committed. The handler takes the context first: `(fn [ctx args] ...)`. A one-argument `(fn [args] …)` handler, the re-frame v1 shape, does not receive `args`: on CLJS it receives `ctx` in its place, and on the JVM the call fails with `:rf.error/fx-handler-exception`.
    - `ctx` is a small map of `:frame` (the active frame id), `:event` (the originating event vector) and `:envelope` (the parent dispatch envelope).
    - `args` is the value the event handler placed beside the fx id in its `:fx` vector.
    - A handler that throws emits `:rf.error/fx-handler-exception`: that effect is skipped, the other effects still run, and the `:db` write stands. An `:fx` entry naming an unregistered id emits `:rf.error/no-such-fx` and is skipped the same way.
    - The `:platforms` metadata key (a set of `:server` / `:client`) runs the effect only on those platforms; it defaults to `#{:client :server}`. On a platform outside the set, the effect is skipped. See [re-frame.ssr](re-frame.ssr.md).
    - See [The effect handler's two arguments](../core/effects.md#the-effect-handlers-two-arguments).
- **Errors**:
    - At registration:
        - `:rf.error/fx-registration-invalid`: no handler fn, as in the metadata-only `(reg-fx :id {…})`.
    - When the effect runs:
        - `:rf.error/fx-handler-exception`: the handler threw; that effect is skipped, and the other effects still run.
        - `:rf.error/no-such-fx`: an `:fx` entry names an unregistered id; that entry is skipped.
- **Example**:
  ```clojure
  (rf/reg-fx :app/scroll-to-top
    (fn [_ctx _args] (js/window.scrollTo 0 0)))

  ;; An event handler asks for it by id in :fx.
  (rf/reg-event :page/open
    (fn [{:keys [db]} [_ page]]
      {:db (assoc db :page page)
       :fx [[:app/scroll-to-top]]}))
  ```

### `reg-cofx`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-cofx id ?metadata supplier) → id
  ```
- **Description**: Registers a coeffect: a named fact about the world (the time, a stored preference, a browser setting) that an event handler can ask for instead of reading it itself, so the handler stays pure. The supplier is a plain function that returns the value synchronously, `(fn [] value)`, or `(fn [arg] value)` for an id parameterised at the call site. The runtime calls it and puts the result in the coeffects map under the cofx id.
    - A handler asks for coeffects with `:rf.cofx/requires` in its registration metadata: `[:my/fact]`, or `[[:my/fact arg]]` for a parameterised id. The values arrive flat in the coeffects map, beside `:db`. There is no `inject-cofx` interceptor; calling it throws `:rf.error/inject-cofx-removed`.
    - For the current time, declare the built-in `:rf/time-ms` (epoch milliseconds, stamped when the event is queued). It needs no registration.
    - The metadata map sets the fact's grade, which decides what happens on replay:

        | Grade | Metadata | Where the value comes from | On replay |
        |---|---|---|---|
        | Ambient | neither key | The supplier, each time a declaring handler runs; not recorded. | The supplier runs again and may answer differently. |
        | Recordable | `{:recordable? true}` | The supplier, or the dispatch's `:rf.cofx` opt; recorded with the event. | The recorded value is presented. |
        | Provided | `{:recordable? true :provided? true}`, no supplier | The event: stamped by the framework or a feature artefact, or passed in `:rf.cofx`. `:rf/time-ms` is one. | The recorded value is presented. |

        Use ambient only for values no `app-db` write depends on, such as a display preference, and recordable for a world fact that ends up in `app-db`. A handler that declares a provided fact the event does not carry fails with `:rf.error/missing-required-cofx`.
    - A `{:recordable? true}` coeffect's value must be EDN (not a `js/Date` or a DOM node), and must match the registration's `:schema` when the schemas artefact is loaded. Both are checked in every build as the value is recorded, and a failure throws `:rf.error/cofx-value-invalid`. See [What is validated](re-frame.schemas.md#what-is-validated).
    - A handler that declares an unregistered id throws `:rf.error/unregistered-cofx` when its event runs; registration does not check it. A supplier that throws emits `:rf.error/coeffect-exception`, and the handler does not run.
    - Under the `:strict` mint policy, a `{:recordable? true}` fact the dispatch did not supply is not generated, and the handler fails with `:rf.error/missing-required-cofx`. Frames made with `:preset :test` default to `:strict`, so tests pass the value in the `:rf.cofx` dispatch opt, or opt back into generation with `{:rf.cofx/mint-policy :explicit-live}`.
    - `:platforms` (a set of `:client` / `:server`) limits where the supplier runs. Elsewhere an ambient fact is absent from the coeffects map, and a recordable one fails with `:rf.error/missing-required-cofx`.
    - To read a subscription from a handler, wrap `subscribe-once` in a cofx and declare it.
    - See [Coeffects](../core/coeffects.md).
- **Errors**:
    - At registration:
        - `:rf.error/cofx-registration-invalid`: `:provided? true` without `:recordable? true`, a supplier passed with `:provided? true`, or no supplier on a fact that is not provided.
        - `:rf.error/cofx-name-collision`: the id is `:db` or `:event`, which are the handler's own arguments.
    - When a declaring event runs:
        - `:rf.error/unregistered-cofx`: the handler declares an id with no `reg-cofx`.
        - `:rf.error/coeffect-exception`: the supplier threw; the handler does not run.
        - `:rf.error/missing-required-cofx`: a declared fact has no value: a provided fact the event does not carry, a recordable fact the dispatch did not supply under the `:strict` mint policy, or a recordable fact on a platform outside its `:platforms`.
        - `:rf.error/cofx-value-invalid`: a recordable value is not EDN, or fails the registration's `:schema`.
- **Example**:
  ```clojure
  ;; The built-in clock: declare it and read it flat.
  (rf/reg-event :todo/add
    {:rf.cofx/requires [:rf/time-ms]}
    (fn [{:keys [db rf/time-ms]} [_ text]]
      {:db (update db :todos conj {:text text :added-at time-ms})}))

  ;; An ambient supplier, parameterised by the storage key. The value only
  ;; styles the page and never reaches app-db, which is what makes ambient right.
  (rf/reg-cofx :ui/local-theme
    {:doc "Ambient localStorage read for the display theme."}
    (fn [storage-key]
      (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

  (rf/reg-fx :ui/set-theme-attr
    (fn [_ctx theme] (.setAttribute js/document.documentElement "data-theme" theme)))

  (rf/reg-event :prefs/apply-theme
    {:rf.cofx/requires [[:ui/local-theme "ui-theme"]]}
    (fn [{:keys [ui/local-theme]} _]
      {:fx [[:ui/set-theme-attr (or local-theme "system")]]}))
  ```

### `clear`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear kind id) → id
  (clear kind id {:frame f}) → id
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

On CLJS, `dispatch`, `dispatch-sync` and `subscribe` are macros in call position and plain functions in value position, as most `reg-*` forms are (see [Registration](#registration)). A call such as `(rf/dispatch [:counter/inc])` records its source coordinates, so tools like Xray can go from a trace event back to that expression. Passed as a value, as in `(map rf/dispatch events)` or `(or dispatch-fn rf/dispatch)`, the name is a function that skips the recording. Both reach the same dispatcher, and there is no `*`-suffixed variant.

On the JVM the three are macros only. JVM code that needs a function calls `re-frame.router/dispatch!`, `re-frame.router/dispatch-sync!` or `re-frame.subs/subscribe`.

A `:frame` that names no live frame (a typo, or a destroyed frame) does not throw: `dispatch` does nothing, `subscribe` returns `nil`, and the runtime emits `:rf.error/frame-destroyed`. With no `:frame` and no frame in scope, the call throws `:rf.error/no-frame-context`.

### Dispatch options

`dispatch` and `dispatch-sync` take an optional opts map:

| Key | What it does |
|---|---|
| `:frame` | The frame to target: a frame-id keyword or a live frame value, as in `(rf/dispatch [::save x] {:frame :todo})`. |
| `:fx-overrides` | `{fx-id override}` for this dispatch; see [`with-fx-overrides`](#with-fx-overrides) for override values and precedence. |
| `:interceptor-overrides` | `{interceptor-ref replacement}` for this dispatch: replaces or removes interceptors in the chain. See [Interceptor overrides](#interceptor-overrides). |
| `:rf.cofx` | Recordable coeffect values to supply, such as `{:rf/time-ms 0}`, for tests, replay and SSR hydration. The runtime adds `:rf/time-ms` when it is absent. A value that is not a map, or a non-integer `:rf/time-ms`, makes the call throw `:rf.error/invalid-cofx`; a value that is not plain EDN (a `js/Date`, a fn, a DOM node) throws `:rf.error/cofx-value-invalid`. Both are checked in every build. |
| `:rf.cofx/mint-policy` | Whether a declared recordable coeffect the dispatch did not supply may be generated. `:live` and `:explicit-live` run its supplier; `:strict` fails the handler with `:rf.error/missing-required-cofx`, and any other value behaves as `:strict`. Unset, the frame's `:rf.cofx/mint-policy` applies (`:strict` under `:preset :test`), then `:live`. A test on a `:strict` frame opts back into generation with `:explicit-live`. See [Coeffects](../core/coeffects.md). |
| `:source` | What triggered the dispatch, recorded on traces. Application code passes `:ui`, `:repl`, `:tool`, `:test`, `:websocket` or `:other`; unset, it is `:unknown`. The framework stamps the other values itself: `:frame-init`, `:fx-dispatch`, `:fx-dispatch-later`, `:machine-spawn`, `:machine-action`, `:always`, `:after-timer`, `:http`, `:router`, `:ssr-hydration`. |
| `:origin` | Who dispatched: an open keyword, `:app` by default. |
| `:trace-id` | A correlation id that tools attach to the dispatch's traces. |

An unrecognised key does nothing, and development builds emit `:rf.warning/unknown-dispatch-opt`. `subscribe` and `subscribe-once` read only `:frame`.

Events a handler queues with `[:dispatch …]` or `[:dispatch-later …]` inherit `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:rf.cofx/mint-policy`, `:origin` and `:trace-id` from the dispatch that ran it. Each queued event gets its own `:rf.cofx` and `:rf/time-ms`, and its `:source` is `:fx-dispatch` or `:fx-dispatch-later`.

### Interceptor overrides

`:interceptor-overrides` replaces or removes interceptors in an event's chain without re-registering the event, usually to stub or drop an interceptor such as logging or analytics in a test. It is a dispatch option, applying to that dispatch and the events it queues, and a [frame config key](#frame-config-keys), applying to every event in the frame.

The value is a map `{interceptor-ref replacement}`:

- A key is an interceptor reference: a keyword id, or an `[id arg]` 2-vector. A keyword key matches every reference to that id, `:my/ic` and `[:my/ic arg]` alike, because it matches the resolved interceptor's `:id`. An `[id arg]` key matches only that exact reference, so `{[:rf.interceptor/path [:cart]] nil}` removes that one and leaves `[:rf.interceptor/path [:cart :items]]` in the chain.
- A replacement is `nil`, which removes the interceptor, or an interceptor reference, resolved through the registrar like any chain entry; an unregistered id throws `:rf.error/unregistered-interceptor`.
- A malformed key, or a replacement that is neither `nil` nor a reference (such as an inline interceptor map), throws `:rf.error/interceptor-override-invalid`, with `:key` and `:replacement` in its ex-data and the recovery `:fix-overrides`. Keys are checked on every dispatch that has overrides; a replacement is resolved only when its key matches an entry in the chain.
- The frame's `:interceptor-overrides` and the dispatch's merge, and the dispatch's wins on a shared key. The frame's `:interceptors` are prepended before overrides apply, so an override reaches them too.
- Events queued with `[:dispatch …]` or `[:dispatch-later …]` inherit the dispatch's map.
- The `{:boundary? true}` schema check of [`reg-event`](#reg-event) runs before the chain, so no override can remove it.
- In development builds the run's `:rf.event/run-start` trace event carries `:rf.interceptor/override-summary`: `{:matched […] :replaced […] :removed […] :count n}`, naming the keys that matched an entry. A key that matched nothing is not listed.
- An epoch record carries the dispatch's map as `:interceptor-overrides`, never the frame's, so [`replay-epoch!`](#replay-epoch) supplies the recorded map and the frame's own config applies as it stands at replay.

```clojure
(rf/reg-interceptor :app/audit
  {:after (fn [ctx] (send-audit! (get-in ctx [:coeffects :event])) ctx)})
(rf/reg-interceptor :test/audit-stub {:after identity})

(rf/reg-event :cart/add
  {:interceptors [:app/audit]}
  (fn [{:keys [db]} [_ item]] {:db (update db :items (fnil conj []) item)}))

;; In a test: swap the audit interceptor for a stub, or drop it.
(rf/dispatch-sync [:cart/add :apple] {:interceptor-overrides {:app/audit :test/audit-stub}})
(rf/dispatch-sync [:cart/add :pear]  {:interceptor-overrides {:app/audit nil}})

;; Don't do this: a replacement is a registered id or nil, never an inline
;; interceptor map. This throws :rf.error/interceptor-override-invalid.
(rf/dispatch-sync [:cart/add :fig] {:interceptor-overrides {:app/audit {:after identity}}})
```

### `dispatch`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch event) → nil
  (dispatch event opts) → nil
  ```
- **Description**: Queues the event on the frame and returns `nil` immediately; the handler runs later. This is the default way to send an event.
    - `opts` is the map described under [Dispatch options](#dispatch-options).
    - `rf/dispatch` finds its frame when it is called: during a view's render, inside an event handler (child dispatches go to the handler's frame), or inside `with-frame`. A callback that fires later, such as an `:on-click`, a timeout or a promise, runs after that scope has gone, so `rf/dispatch` there throws `:rf.error/no-frame-context`.
    - Inside a `reg-view` body, use the injected `dispatch`: it is bound to the view's frame when the view renders, so it still targets that frame from any callback. Elsewhere, capture the frame with [`capture-frame`](#capture-frame), or pass `{:frame …}`. See [The async boundary: capture the frame](../core/frames.md#the-async-boundary-capture-the-frame).
    - An event id with no registered handler emits `:rf.error/no-such-handler`, and the event does nothing.
    - One drain runs at most 100 events (the frame's `:drain-depth`; 16 under `:preset :story`). If more are waiting, the rest of the queue is dropped, the events that ran stay committed, and the always-on `:rf.error/drain-depth-exceeded` lists the last event ids it ran (`:tail-event-ids`), which usually show the dispatch loop. The limit counts every event one drain processes, so more than 100 events queued before the drain starts also reach it. See [When the drain won't stop](../core/run-to-completion.md#when-the-drain-wont-stop).
    - Development builds emit `:rf.warning/non-serialisable-event-payload` when the event holds a fn, Promise, AbortController, DOM node, `js/Date` or RegExp. The event still runs, but replay and SSR hydration expect plain data.
- **Example**:
  ```clojure
  ;; Don't do this: when the click fires there is no frame in scope,
  ;; so rf/dispatch throws :rf.error/no-frame-context.
  (rf/reg-view counter-button []
    [:button {:on-click #(rf/dispatch [:counter/inc])} "+"])

  ;; The injected `dispatch` was bound to the view's frame at render.
  (rf/reg-view counter-button []
    [:button {:on-click #(dispatch [:counter/inc])} "+"])
  ```

### `dispatch-sync`

- **Kind**: macro
- **Signature**:
  ```clojure
  (dispatch-sync event) → nil
  (dispatch-sync event opts) → nil
  ```
- **Description**: Processes the event and drains the queue to completion, then returns `nil`. Use it in tests, at the REPL and for one-shot boot events, where the next line needs the new state; views and handlers use `dispatch`. See [Run to completion](../core/run-to-completion.md#dispatch-sync).
    - `opts` is the map described under [Dispatch options](#dispatch-options).
    - Handler, interceptor, coeffect-supplier and effect failures are reported under their error ids, not thrown. Problems found while preparing the event do throw: no frame in scope, an invalid `:rf.cofx`, an unregistered or missing declared coeffect.
    - A call that targets a frame while that frame is processing events (from one of its handlers, interceptors or effect handlers) is rejected: the event does not run, and development builds emit `:rf.error/dispatch-sync-in-handler`. Return a `[:dispatch …]` effect instead.
    - A call that targets a different frame from inside a drain runs, and development builds emit `:rf.warning/cross-frame-dispatch-sync-during-drain`.
- **Example**:
  ```clojure
  ;; A one-shot boot event: app-db is initialised before the next line runs.
  (rf/make-frame {:id :app/main})
  (rf/dispatch-sync [:counter/initialise] {:frame :app/main})

  ;; Drive a sequence of events synchronously from a test or script.
  (doseq [evec events]
    (rf/dispatch-sync evec {:frame :app/main}))
  ```

### `subscribe`

- **Kind**: macro
- **Signature**:
  ```clojure
  (subscribe query-v) → reaction
  (subscribe query-v opts) → reaction
  ```
- **Description**: Returns a reaction whose value is the subscription's current output, recomputed when its inputs change; deref it to read. Use it in a view's render body, where the deref also re-renders the view when the value changes. An event handler reads a subscription through a coeffect (see [`reg-cofx`](#reg-cofx)); a one-off read outside a view uses [`subscribe-once`](#subscribe-once).
    - It finds its frame the way `dispatch` does, so call it while the view renders, not from a callback that runs later. Inside a `reg-view` body, the injected `subscribe` is bound to the view's frame.
    - `(rf/subscribe [:counter/value] {:frame :other})` targets another frame; `:other` may be a frame-id keyword or a live frame value.
    - A query id with no registered subscription emits `:rf.error/no-such-sub`, and the reaction derefs to `nil`. The miss is not cached, so a later registration takes effect on the next `subscribe`.
- **Example**:
  ```clojure
  (rf/reg-view counter-label []
    [:span @(subscribe [:counter/value])])
  ```

### `subscribe-once`

- **Kind**: function
- **Signature**:
  ```clojure
  (subscribe-once query-v) → value
  (subscribe-once query-v opts) → value
  ```
- **Description**: Reads a subscription's current value once: subscribes, derefs and unsubscribes, keeping no reactive handle. Use it at the REPL, in tests, in SSR code and in tools. Not in views, which need `subscribe` to re-render.
    - An event handler that needs a derived value should declare a cofx that calls `subscribe-once` (see [`reg-cofx`](#reg-cofx)) rather than call it in the handler body. If handlers need the value routinely, a flow is the better fit.
    - Machine `:guard`, `:action`, `:entry` and `:exit` fns must not call it. A machine takes host facts as recorded coeffects, including the machines-only `{:rf/sub …}` source (see [re-frame.machines](re-frame.machines.md)).
    - `(subscribe-once query-v {:frame f})` targets another frame, as for `subscribe`. With no `:frame` and no frame in scope, it throws `:rf.error/no-frame-context`.
- **Example**:
  ```clojure
  ;; At the REPL: read the current value, retaining nothing.
  (rf/subscribe-once [:counter/value] {:frame :app/main})   ;; => 3
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
  ;; In a test or at the REPL: balance an explicit subscribe.
  (let [r (rf/subscribe [:counter/value] {:frame :app/main})]
    @r
    (rf/unsubscribe :app/main [:counter/value]))
  ```

### `clear-sub-cache!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-sub-cache!) → nil
  (clear-sub-cache! frame-id) → nil
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
- **Description**: Computes a subscription against an `app-db` value and returns the result, with no cache, no reactivity and no frame. Use it to test subscriptions ([Test a subscription](../core/testing/subscriptions.md)). The query vector comes first, the db second.
    - `db` is a plain `app-db` map, or a full frame-state value (`{:rf.db/app … :rf.db/runtime …}`) when the graph mixes `app-db` and `runtime-db` subscriptions.
    - Runs on the JVM.
    - An unregistered query id computes to `nil`, and an unregistered input delivers `nil` to the computation fn, both with no error, so check the ids when a test reads an unexpected `nil`.
    - A computation fn that throws emits `:rf.error/sub-exception` and computes `nil`. A subscription that depends on itself emits `:rf.error/sub-cycle`, and the cyclic input delivers `nil` to the computation fn.
- **Example**:
  ```clojure
  ;; Evaluate :counter/doubled against a literal app-db value (no frame, no cache).
  (rf/compute-sub [:counter/doubled] {:counter/value 21})   ;; => 42
  ```

## Standard events

The framework registers a few `:rf/*` events that you dispatch like any other. Registering `:rf/set-db` or `:rf/install-frame-state` yourself with `reg-event` throws `:rf.error/reserved-event-id`. Keep your own ids out of the `:rf/*` namespace.

### `:rf/set-db`

- **Kind**: event
- **Payload**:
  ```clojure
  [:rf/set-db new-db-map]
  ```
- **Description**: Replaces the whole `app-db` with the given map; it does not merge. Use it to seed `app-db` when a frame is created, or to reset it. It goes through the normal event pipeline (schema validation, rollback, trace emission, epoch recording) like any other event. Its handler returns `{:db new-db}`, so it changes `app-db` only, never `runtime-db`.
    - It takes exactly one map. A missing, `nil` or non-map argument, or any extra argument (`[:rf/set-db {} :junk]`), fails the handler with `:rf.error/set-db-bad-value`. A dispatch reports it as `:rf.error/handler-exception` and leaves `app-db` unchanged; as an `:initial-events` step it aborts frame creation with `:rf.error/initial-events-step-failed`. `[:rf/set-db {}]` empties `app-db`.
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

### `:rf/install-frame-state`

- **Kind**: event
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

Register views with `reg-view`. Give a subtree its frame with `frame-root` when the subtree should bring the frame into being, or `frame-provider` when the frame already exists; [Frames](#frames) covers when you need more than one.

Frames, subscriptions, dispatch, source metadata and registry ids work the same under every adapter, and `capture-frame` works under all of them. Adapter-specific functions are on each adapter's page: [re-frame.adapter.reagent](re-frame.adapter.reagent.md) (full and slim Reagent) and [re-frame.adapter.uix](re-frame.adapter.uix.md), whose components read and dispatch through the `use-sub` and `use-frame` hooks.

### `reg-view`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-view sym [args] body+) → id
  (reg-view sym docstring [args] body+) → id
  (reg-view ^{:rf/id :explicit/id} sym [args] body+) → id
  ```
- **Description**: Defines and registers a view with a `defn`-like form. This is the form application code uses, for any component that subscribes or dispatches.
    - It `def`s the symbol, so sibling code renders the view as `[my-view item]`.
    - The id is the current namespace plus the symbol (`my.app.views/counter-buttons` registers `:my.app.views/counter-buttons`), unless `^{:rf/id …}` metadata names one.
    - `dispatch` and `subscribe` are bound inside the body to the frame the view renders in. They are bound at render, so they keep that frame in callbacks such as `:on-click`, where `rf/dispatch` has none (see [`dispatch`](#dispatch)).
        - The injected `dispatch` takes `[event]` or `[event opts]`, always targets that frame (a `:frame` in `opts` is ignored), and records `:source :ui`.
        - The injected `subscribe` takes only the query vector. To read another frame, call `rf/subscribe` with `{:frame …}` during render.
    - A docstring becomes the view's `:doc`.
    - A body that is not `defn`-shaped throws `:rf.error/reg-view-bad-args` at macroexpansion.
    - Render a view by Var reference or with `(rf/view id)`. A keyword head such as `[:my-view "args"]` is an HTML element, never a registered view.
    - See [What `reg-view` adds](../core/views.md#what-reg-view-adds).
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
  (reg-view* id render-fn) → id
  (reg-view* id metadata render-fn) → id
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
  (view view-id) → component
  ```
- **Description**: Looks up a registered view by id and returns what renders it, or `nil` when the id is not registered. On CLJS that is the installed adapter's component head for the view, rendered as `[(rf/view :id) args...]` under Reagent and `($ (rf/view :id) props)` under UIx; on the JVM it is the registered render fn. It does not return hiccup. Use it as `[(rf/view :id) args...]` when you hold an id rather than the symbol: a stored view id, plugin-style dispatch, dynamic chrome. Otherwise render by Var reference (`[my-view args]`).
    - Under UIx, register a `defui` component with `reg-view*` and mount the head with `$`; see [Registry-keyed views](re-frame.adapter.uix.md#registry-keyed-views) for how the head passes props and children.
- **Example**:
  ```clojure
  [(rf/view :app/header) {:title "Cart"}]   ;; looks the view up by id at render time
  ```

### `frame-provider`

- **Kind**: component (Reagent)
- **Signature**:
  ```clojure
  [rf/frame-provider {:frame :todo} & children]
  ```
- **Description**: Scopes a subtree to a frame that already exists; it creates, refreshes and destroys nothing. `dispatch` and `subscribe` inside the subtree target that frame. To create the frame if it is absent, use [`frame-root`](#frame-root). It is the React counterpart of `with-frame`. See [frame-provider and frame-root](../core/frames.md#frame-provider-and-frame-root).
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
- **Description**: Creates the named frame if it is absent, or reuses it without re-seeding if it is live, and provides it to the subtree. It takes `make-frame`'s options; see [Frame config keys](#frame-config-keys). `:id` is required and must be a keyword; otherwise it raises `:rf.error/frame-root-missing-id`.
    - The frame is created at commit, in a client `useLayoutEffect`, not during render. The first render emits no children; once the frame exists, the children render against it. A render React discards before commit (a Suspense abort, a discarded concurrent render) creates and seeds nothing.
    - A remount reuses the frame without re-seeding it. Hot reload, StrictMode's development double-invoke, Story re-evaluation and a keyed remount all keep durable state; `:initial-events` run once, when the frame is first created.
    - Unmounting does not destroy the frame. To own a frame's lifetime explicitly, call `make-frame` and `destroy-frame!` yourself, for example in a `create-class`.
    - Changing `:id` or other options on a mounted `frame-root` raises `:rf.error/frame-root-reconfigured`; to switch frames, give the `frame-root` a React `key` that changes. Passing `:frame` (the `frame-provider` key) raises `:rf.error/frame-root-given-frame`, naming `frame-provider`.
    - See [frame-provider and frame-root](../core/frames.md#frame-provider-and-frame-root).
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
- **Description**: Captures a frame and returns a bundle of operations bound to it. Use it for code that runs after the view body or handler has returned (`Promise.then`, `setTimeout`, WebSocket `onmessage`, observer callbacks), where the frame is no longer in scope.
    - A `reg-view` body rarely needs it: the injected `dispatch` and `subscribe` are already capture-frame operations. Reach for it in `reg-view*` bodies, effect handlers and other code that hands a callback to a JavaScript API.
    - `(capture-frame)` captures the current frame at the moment of the call and raises `:rf.error/no-frame-context` outside a frame scope. `(capture-frame frame-id)` binds the bundle to a named frame and works anywhere.
    - `:dispatch` and `:dispatch-sync` take `[event]` or `[event opts]`; `:subscribe` takes only `[query-v]`. They always target the captured frame, and a `:frame` in `opts` is ignored.
    - The bundle is bound to the frame instance that was live when it was captured. Once that frame is destroyed, even if a new frame is later made under the same id, the dispatch ops do nothing and `:subscribe` returns `nil`, each emitting `:rf.error/frame-destroyed`. `(capture-frame frame-id)` for an id with no live frame follows whichever frame holds the id when an op runs.
    - The bundle holds operations, not state. Read the frame's `app-db` with `(rf/app-db-value (:frame handle))`.
    - See [Frames](../core/frames.md) for the async-boundary rules.
- **Example**:
  ```clojure
  ;; An effect that opens a socket: each message arrives long after the
  ;; handler returned, so capture the frame the effect ran for.
  (rf/reg-fx :chat/connect
    (fn [{:keys [frame]} url]
      (let [{:keys [dispatch]} (rf/capture-frame frame)
            socket             (js/WebSocket. url)]
        (set! (.-onmessage socket)
              (fn [e] (dispatch [:chat/received (.-data e)]))))))
  ```

<a id="with-frame--with-new-frame"></a>

### `with-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-frame frame body+) → value of body
  ```
- **Description**: Makes an existing frame the current frame for the dynamic extent of `body`, in code that is not a view tree: tests, the REPL, SSR. It is the block-scoped counterpart of `frame-provider`.
    - `frame` is a frame-id keyword or a live frame value.
    - It creates and destroys nothing.
    - For a callback that runs after `body` returns, use `capture-frame`.
    - It does not check that the frame exists: calls inside `body` follow the missing-frame rule under [Dispatch and subscribe](#dispatch-and-subscribe).
    - Passing `with-new-frame`'s binding vector throws `:rf.error/with-frame-vector-form` at macroexpansion.
    - See [Scoping a frame in a test or at the REPL](../core/frames.md#scoping-a-frame-in-a-test-or-at-the-repl).
- **Example**:
  ```clojure
  (rf/with-frame :todo
    (rf/dispatch-sync [:todo/add {:text "milk"}]))
  ```

### `with-new-frame`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-new-frame [sym expr] body+) → value of body
  ```
- **Description**: Evaluates `expr` (usually a `make-frame` call), binds the resulting frame to `sym`, runs `body` with it as the current frame, and destroys the frame on exit, whether `body` returns or throws. Use it for throwaway frames in tests and one-off harnesses. It returns the value of `body`. Passing a keyword, the `with-frame` shape, throws `:rf.error/with-new-frame-keyword-form`, and any other binding that is not `[sym expr]` throws `:rf.error/with-new-frame-bad-binding`, both at macroexpansion. See [Scoping a frame in a test or at the REPL](../core/frames.md#scoping-a-frame-in-a-test-or-at-the-repl).
- **Example**:
  ```clojure
  (rf/with-new-frame [f (rf/make-frame {:images [todo-image]})]
    (rf/dispatch-sync [:rf/set-db {:todos []}])
    (rf/dispatch-sync [:todo/add {:text "milk"}])
    (is (= 1 (count (:todos (rf/app-db-value f))))))
  ```

## Effects and interceptors

An event handler returns an effect map describing what should happen; the runtime applies it after the handler returns, so the handler stays pure. The interceptor chain runs before and after the handler.

### The effect map

The effect map is closed. It has seven top-level keys:

| Key | Value | Rules |
|---|---|---|
| `:db` | The new `app-db`, installed at commit. | `{:db nil}` installs `{}`, and development builds emit `:rf.warning/db-nil-coerced`, since a nil is usually a bug; return `{:db {}}` to clear `app-db` on purpose. |
| `:fx` | A vector of `[fx-id args]` entries (`[fx-id]` with no args), each run by the handler registered with `reg-fx`. | Must be a vector or `nil`; any other value, such as a bare map, rejects the event with `:rf.error/effect-map-shape`. `nil` and `[]` entries are skipped, so `(when saving? [:app/save doc])` works as a conditional effect. An entry that is not a vector, has a non-keyword fx id or has more than two elements emits `:rf.error/effect-map-shape` and is dropped; the other entries run and the `:db` write stands. |
| `:sensitive`, `:large`, `:clear-sensitive`, `:clear-large` | Classify `app-db` paths in the same commit (see [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md)). | A malformed value rejects the event with `:rf.error/classification-effect-shape`. |
| `:rf.db/runtime` | Reserved for the framework and runtime extensions. | An application handler that returns it still has it applied, and development builds emit `:rf.warning/app-handler-runtime-effect`. |

Any other top-level key rejects the event before anything commits, so a valid `:db` beside it does not land either, and the runtime emits `:rf.error/effect-map-shape`. These checks apply to the final effect map, including keys an interceptor's `:after` adds. See [The effect map](../core/effects.md#the-effect-map).

### `reg-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-interceptor id descriptor) → id
  (reg-interceptor id metadata descriptor) → id
  ```
- **Description**: Registers a named interceptor, which event handlers and frames then reference by id in their `:interceptors` vector. Use it for work that wraps many handlers, such as logging, analytics or validation, and for any handler that must read or rewrite the interceptor context.
    - `descriptor` is `{:before f}`, `{:after f}`, `{:before f :after g}`, or `{:factory f}` for a parameterized family. A malformed descriptor raises `:rf.error/invalid-interceptor`.
    - A factory's `f` takes one argument and returns a descriptor, and a chain references it as `[id arg]`. The standard `[:rf.interceptor/path …]` is a factory. A factory referenced as a bare keyword, a static interceptor referenced as `[id arg]`, or a factory that throws or returns no descriptor raises `:rf.error/interceptor-factory-arity`.
    - Each `:before` and `:after` fn receives and returns the interceptor context. The context has `:coeffects` (`:db`, `:event`, `:rf.frame/id` and the declared coeffects) and `:effects` (the handler's effect map). `:before` fns run in chain order before the handler, and `:after` fns in reverse order after it. A fn that returns `nil` leaves the context unchanged.
    - A fn that throws emits `:rf.error/interceptor-exception`, naming the interceptor and its `:phase`. Later `:before` fns and the handler are skipped, every `:after` still runs, and the event commits nothing.
    - The optional middle slot is the standard registration-metadata map (`:doc`, `:schema`, `:tags`, …).
    - A chain carries ids only. An inline interceptor map in an `:interceptors` vector raises `:rf.error/inline-interceptor-removed`, and an id that is not registered raises `:rf.error/unregistered-interceptor`.
    - On CLJS the name is also a plain function in value position, as for `dispatch`; JVM code that needs a function calls `re-frame.interceptor-registry/reg-interceptor*`. There is no public constructor for interceptor values.
    - See [Interceptors](../core/interceptors.md).
- **Errors**:
    - At registration:
        - `:rf.error/invalid-interceptor`: the descriptor is malformed.
    - When a `reg-event` registers a chain that references it:
        - `:rf.error/unregistered-interceptor`: the chain names an id with no `reg-interceptor`.
        - `:rf.error/interceptor-factory-arity`: a factory referenced as a bare keyword, a static interceptor referenced as `[id arg]`, or a factory that throws or returns no descriptor.
        - `:rf.error/inline-interceptor-removed`: the chain holds an inline interceptor map instead of an id.
    - When the event runs:
        - `:rf.error/interceptor-exception`: a `:before` or `:after` fn threw; the event commits nothing.
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
  (with-fx-overrides overrides body+) → value of body
  ```
- **Description**: `overrides` is a map `{fx-id override}`. Replaces effect handlers for every `dispatch` and `dispatch-sync` inside `body`, usually to stub an effect in a test. Each such call merges the map under its own `:fx-overrides`, so a per-call key wins. The overrides apply for the dynamic extent of `body` and compose with `with-frame`.
    - Precedence, highest first: per call (`(rf/dispatch event {:fx-overrides {...}})`), then scoped (`with-fx-overrides`), then per frame (`(rf/make-frame {:id :todo :fx-overrides {...}})`).
    - An override value is the id of another registered fx, which runs in place of the original. An override may also be a function, called like a `reg-fx` handler with `ctx` and `args`, for test wiring; an epoch recorded under a function override cannot be replayed with [`replay-epoch!`](#replay-epoch).
    - `nil` or `false` means no override, so `{:app/save (when stub? :app/save-stub)}` is safe. An unregistered id or any other value emits `:rf.error/override-fallthrough`, and the original effect runs.
    - `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow` and `:rf.route/with-nav-token` cannot be overridden: the override is ignored, the real effect runs, and `:rf.error/reserved-fx-override` is emitted.
    - Events queued with `:dispatch` or `:dispatch-later` from inside the scope keep the overrides.
    - See [Asserting on what would dispatch](../core/testing/pipeline-runs.md#asserting-on-what-would-dispatch).
- **Example**:
  ```clojure
  ;; Swap the real managed-HTTP fx for a canned-failure stub for the test body —
  ;; every dispatch inside inherits the override; it unwinds when the body exits.
  (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrong"}]))
  ```

## Standard effects and interceptor

The framework reserves a few `:fx` ids and one standard interceptor reference; register your own fx ids with `reg-fx`. Feature fx (`[:rf.http/managed …]`, `[:rf.nav/push-url …]`, `[:rf.machine/spawn …]`, `[:rf.fx/reg-flow …]`, `[:rf.server/* …]`, …) are documented on each feature's page.

### `[:dispatch event-vec]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `[:dispatch event-vec]`
- **Description**: Queues `event-vec` on the same frame's queue. It runs after the current pipeline run completes. This is how a handler triggers another event; it cannot call `dispatch-sync`.
    - The queued event inherits opts from the event that queued it; see [Dispatch options](#dispatch-options).
- **Example**:
  ```clojure
  (rf/reg-event :cart/checkout
    (fn [{:keys [db]} _]
      {:db (assoc db :cart/status :submitting)
       :fx [[:dispatch [:analytics/track :checkout]]]}))
  ```

### `[:dispatch-later {…}]`

- **Kind**: effect (reserved fx-id)
- **Payload**: `[:dispatch-later {:ms ms :event event-vec}]`
- **Description**: Queues `event-vec` on the same frame after `ms` milliseconds. Use it instead of a `setTimeout` in a handler, which would have no frame when it fires.
    - `:ms` is the delay in milliseconds. A missing or non-numeric `:ms` is refused: nothing is queued, the always-on `:rf.error/fx-handler-exception` is emitted carrying the offending `:ms`, and the rest of the `:fx` vector still runs.
    - A pending timer is cancelled when its frame is destroyed, so the event never runs.
    - The queued event inherits opts from the event that queued it; see [Dispatch options](#dispatch-options).
- **Example**:
  ```clojure
  (rf/reg-event :toast/show
    (fn [{:keys [db]} [_ text]]
      {:db (assoc db :toast text)
       :fx [[:dispatch-later {:ms 3000 :event [:toast/hide]}]]}))
  ```

### `[:rf.interceptor/path path-vector]`

- **Kind**: interceptor reference
- **Payload**: `[:rf.interceptor/path path-vector]`, inside an `:interceptors` vector
- **Description**: Focuses a handler on one slice of `app-db`: `:before` puts `(get-in db path)` in the coeffects as `:db`, the handler returns a new slice, and `:after` writes it back into the full `app-db`. It is the only standard interceptor.
    - If the handler returns the slice unchanged, `:after` returns the original `app-db` object instead of a fresh `assoc-in`, so the commit sees no change.
    - A path that is not a vector raises `:rf.error/path-interceptor-bad-path` when the event registers.
    - It is referenced by id; there is no `path` function, and calling `rf/path` raises `:rf.error/path-removed`.
    - See [The one standard interceptor: `path`](../core/interceptors.md#the-one-standard-interceptor-path).
- **Example**:
  ```clojure
  ;; The handler sees and returns only the [:cart :items] slice.
  (rf/reg-event :cart/add-item
    {:interceptors [[:rf.interceptor/path [:cart :items]]]}
    (fn [{:keys [db]} [_ item]] {:db (conj db item)}))
  ```

## Frames

A frame holds one running instance of your app: its `app-db`, event queue and subscription cache. Registrations are shared: unless a frame is built from [images](#image), every frame runs the same handlers against its own state. Most apps have one frame, created at the root with `rf/frame-root`. `init!` does not create one, and an operation outside any frame scope throws `:rf.error/no-frame-context` rather than falling back to a default frame (see [Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found)).

Create more frames when the same app must run more than once side by side, each copy isolated: the same widget twice on a page, Story canvases, a fresh frame per test, a frame per SSR request. Two parts of one app that ever share state belong in one frame: frames do not split an app into subsystems, and a subscription never reads another frame. Target a frame from outside its scope with `{:frame :other}`.

Which function makes the frame depends on who owns its lifetime:

- A view subtree that should bring its frame into being: [`frame-root`](#frame-root). It creates the frame on first mount and keeps it on unmount.
- A subtree rendering a frame that already exists: [`frame-provider`](#frame-provider).
- Code that creates and tears down the frame itself (tests, SSR requests, a modal discarded on close): [`make-frame`](#make-frame) with [`destroy-frame!`](#destroy-frame), or [`with-new-frame`](#with-new-frame) for a body of code.
- Code outside a view tree that runs against an existing frame (tests, the REPL, SSR): [`with-frame`](#with-frame).

### `make-frame`

- **Kind**: function
- **Signature**:
  ```clojure
  (make-frame opts) → frame value
  (make-frame opts descriptors) → frame value
  ```
- **Description**: Creates a frame and returns the live frame value. Use it when your code owns the frame's lifetime: devcards, modal stacks, several live instances of a widget, dynamic tabs, tests, and one frame per SSR request. For a frame tied to a view subtree, use `frame-root`. There is no `reg-frame`: a frame is a live runtime object, not a registration.
    - `opts` must be a map. Every key is optional; the keys are listed under [Frame config keys](#frame-config-keys).
    - Creating a frame under an id that is already live replaces it idempotently, keeping durable state across a re-mount: `app-db`, `runtime-db`, the queue and the subscription cache survive. The new config replaces the old one whole, so a key you leave out is dropped rather than kept from the earlier call, and a new `:initial-events` is recorded but not run.
    - The two-argument form resolves `:images` against an explicit descriptor pool, for tests and harnesses.
    - Pass the returned value wherever a frame is expected. `dispatch`, `subscribe`, `app-db-value` and `frame-provider` accept the value or its id and treat them the same, so there is no value-to-id accessor. `destroy-frame!` accepts both too, but treats them differently; see [`destroy-frame!`](#destroy-frame).
    - Pair each `make-frame` with a `destroy-frame!`, or use `frame-root` for frames tied to a view. See [Frames](../core/frames.md).
- **Errors**: each one throws, and no frame is left behind. See [Assembly errors](../core/images.md#assembly-errors) for the `image-*` errors.
    - `:rf.error/make-frame-bad-opts`: `opts` is not a map, including `nil`.
    - `:rf.error/make-frame-bad-images`: `:images` is not a vector, or is `[]`.
    - `:rf.error/unknown-preset`: `:preset` is not `:default`, `:test` or `:story`.
    - `:rf.error/image-duplicate-id`: two namespaces register the same `[kind id]` in one image's selection, including the default image. Rename one, narrow `:select-ns`, or put the intended override in a later image.
    - `:rf.error/image-zero-match`: an image's `:select-ns :include` pattern matches no loaded registration (a typo, or a namespace not required).
    - `:rf.error/image-duplicate-image-id`: images in a multi-image `:images` vector share an `:id`, or one has no `:id`.
    - `:rf.error/image-within-image-collision`: an inline registration collides with a selected one in the same image.
    - `:rf.error/image-standard-replacement-forbidden`: an app registration collides with a protected framework standard such as `:rf/set-db`.
    - `:rf.error/image-missing-reference`: an event names an interceptor, or a resource names a scope resolver, that the image does not provide.
    - `:rf.error/unregistered-interceptor`: an entry in the frame's own `:interceptors` names an id the frame's image does not register. The chain is resolved when the frame is made, so a typo fails here rather than at the first dispatch.
    - `:rf.error/frame-construction-in-handler`: a new frame was created inside an event handler. Create frames at top level or with `frame-root`.
    - `:rf.error/frame-construction-in-progress`: the id's construction or destruction is still under way (re-entry from its own setup, or another JVM thread). Retry once it settles.
    - `:rf.error/bad-frame-classification`: a `:sensitive` or `:large` key, or a malformed `:observability` entry.
    - `:rf.error/routing-artefact-missing`: `:url-strategy` without `re-frame.routing` loaded. `:url-bound?` alone is accepted before routing loads.
    - `:rf.error/resources-artefact-missing`: `:revalidate-on` without `re-frame.resources` loaded.
    - `:rf.error/initial-db-retired`, `:rf.error/on-create-retired`: `:initial-db` and `:on-create` are not config keys. Use `:initial-events`, seeding with `[:rf/set-db {…}]`.
    - `:rf.error/initial-events-bare-event`: `:initial-events` is one bare event vector (`[:app/boot]`) instead of a vector of steps.
    - `:rf.error/initial-events-bad-step`, `:rf.error/initial-events-bad-event`, `:rf.error/initial-events-bad-opts`: a malformed step.
    - `:rf.error/initial-events-step-failed`: a step's handler, an interceptor, a coeffect or a flow threw. The frame is destroyed, and the error names `:step-index` and `:event`.
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

### Frame config keys

The keys of the map `make-frame` and `frame-root` take. Every key is optional, except that `frame-root` requires `:id`.

| Key | Value | Default | Meaning |
|---|---|---|---|
| `:id` | A keyword. | A generated `:rf.frame/<n>` id. | The frame's id. A frame made without one is listed by `frame-ids` under its generated id; address it through the value `make-frame` returns. |
| `:images` | A non-empty vector of [images](#image). | The default image. | The registrations the frame runs. The default image runs every registration; a frame with no app registrations passes `[(rf/image {:id :my/empty})]`. |
| `:adapter` | Any value. | None. | Recorded on the frame for tools. Rendering always uses the adapter installed by `init!`. |
| `:doc`, `:tags` | A string; a set of keywords. | None. | Registration metadata, read back with [`frame-meta`](#frame-meta). |
| `:initial-events` | A vector of setup steps. | None. | Events dispatched into the new frame when it is created; see below. |
| `:on-destroy` | One event vector. | None. | The event `destroy-frame!` runs before the frame is marked dead. |
| `:fx-overrides` | `{fx-id replacement}` | None. | Replaces effect handlers for every event in this frame. The replacement is an fx id or a function; see [`with-fx-overrides`](#with-fx-overrides). A per-dispatch override wins. |
| `:interceptor-overrides` | `{interceptor-ref replacement}` | None. | Replaces or removes interceptors in every event's chain in this frame; see [Interceptor overrides](#interceptor-overrides). A per-dispatch override wins. |
| `:interceptors` | A vector of interceptor references (an id, or `[id arg]`). | None. | Prepended to every event's chain in this frame. |
| `:drain-depth` | An integer. | `100` | The most events one drain runs. Past it the drain halts, the rest of the queue is dropped, and `:rf.error/drain-depth-exceeded` is emitted; see [`dispatch`](#dispatch). |
| `:platform` | `:client` or `:server`. | `:client` on CLJS, `:server` on the JVM. | Decides which `:platforms`-restricted effects and coeffects run. |
| `:preset` | `:default`, `:test` or `:story`. | `:default`, which sets nothing. | A bundle of the keys above; see below. |
| `:rf.cofx/mint-policy` | `:live`, `:strict` or `:explicit-live`. | `:live` | This frame's default [mint policy](#dispatch-options). |
| `:rf.trace/events-retained` | An integer. | `configure!`'s `:trace-buffer` (`50`). | This frame's trace ring size in development builds. |
| `:observability` | `{:handled-events [<entry>…] :errors [<entry>…]}` | `configure!`'s `:observability`. | The sink policy; see [`register-observability-sink!`](#register-observability-sink). |
| `:ssr` | A map. | None. | The frame's SSR settings; see [Frame `:ssr` config](re-frame.ssr.md#frame-ssr-config). |
| `:url-bound?` | `true` or `false`. | `false` | Whether this frame owns the browser URL; see [Browser URL listener](re-frame.routing.md#browser-url-listener). |
| `:url-strategy` | A URL strategy. | The history strategy. | How routes map to URLs; see [URL strategies](re-frame.routing.md#url-strategies). |
| `:revalidate-on` | A subset of `#{:focus :reconnect}`. | None. | The host signals that revalidate the frame's stale resources; see [Revalidation is a frame property](re-frame.resources.md#revalidation-is-a-frame-property). |

- `:initial-events` is a vector of steps. A step is an event vector, or `{:event [...] :opts {...}}` where `:opts` are `dispatch-sync` opts without `:frame`. Each step is dispatched synchronously and drained before the next, and `make-frame` returns once they settle. Asynchronous effects they start are not awaited. A single step is still wrapped: `[[:app/boot]]`, not `[:app/boot]`. See [Seeding initial state](../core/frames.md#seeding-initial-state).
- Setup is strict. If a step's handler, an interceptor, a coeffect or a flow throws, the frame is destroyed and `make-frame` throws. An effect that throws after its step's `:db` committed does not fail construction.
- `:preset :test` sets `:drain-depth 100` and `:rf.cofx/mint-policy :strict` and redirects `:rf.http/managed` to `:rf.http/managed-canned-success`; `:preset :story` sets `:drain-depth 16` and the same redirect. Keys you pass win over the preset's. The redirect needs `re-frame.http.test-support` loaded.
- An `:fx-overrides` entry for `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow` or `:rf.route/with-nav-token` is refused with `:rf.error/reserved-fx-override`, and the real effect runs.
- A frame config cannot classify `app-db` data: a config carrying `:sensitive` or `:large` is rejected. To classify paths, return the classification effects from a `reg-event` alongside `:db` and run it at creation through `:initial-events`, as in the [`make-frame`](#make-frame) example. See [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).
- Any other key is stored with the frame and returned by `frame-meta`; nothing reads it, and nothing warns.
- The errors a malformed config raises are listed under [`make-frame`](#make-frame).

### Resetting a frame

Calling `rf/reset-frame!` throws `:rf.error/reset-frame-removed`. To replace a frame, destroy it with `destroy-frame!`, which runs `:on-destroy` and releases per-feature resources, then create it again from the same config:

```clojure
;; Destroy, then re-create from the same config (which carries :id :app/main).
;; Run this outside any event handler, for example from a restart button's :on-click.
(rf/destroy-frame! :app/main)
(rf/make-frame config)
```

- The new frame starts with an empty `app-db` and `runtime-db` and runs its `:initial-events` again; a machine's snapshot stays `nil` until the machine next handles an event.
- For a frame built from images, pass the same `:images` vector again; otherwise the new frame gets the default image, which runs every registration.
- Flows are not part of the config. `destroy-frame!` removes the frame's flows, so register them again after `make-frame`, or register them from `:initial-events` with [`:rf.fx/reg-flow`](re-frame.flows.md#rffxreg-flow).
- To empty `app-db` and keep `runtime-db`, dispatch `[:rf/set-db {}]` instead. Development tools can also call `(rf/replace-frame-state! frame-id {:rf.db/app {}})`.
- The pair is not atomic. `destroy-frame!` has no handler-scope guard, so called from inside a handler it destroys the frame, and the following `make-frame` throws because frames cannot be created inside a handler, leaving the frame destroyed. Frame creation and destruction belong outside handlers anyway.
- See [Ending and resetting a frame](../core/frames.md#ending-and-resetting-a-frame).

### `destroy-frame!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-frame! frame-target) → nil
  ```
- **Description**: Tears down a frame and releases everything it holds. `frame-target` is a frame-id keyword or a live frame value. Calling it again is a no-op.
    - Queued events that have not started are dropped at once. A handler already running may return, and interceptor `:after` fns already entered may unwind, but their results are discarded: no later framework step or render runs.
    - A configured `:on-destroy` event, and the events it dispatches to the same frame, run before the frame is marked dead.
    - A dispatch from elsewhere during teardown may reach the queue, but it is dropped before its handler runs. Afterwards, `dispatch` and `subscribe` against the dead or absent frame do nothing and emit `:rf.error/frame-destroyed`.
    - Teardown also releases the frame's feature state: it aborts in-flight managed HTTP requests (their replies are never delivered), cancels pending `:dispatch-later` and machine `:after` timers, and drops flows, machines, schemas, routing and resource caches, SSR state and epoch history. It clears the subscription cache and removes the frame.
    - A throwing `:on-destroy` handler does not stop teardown: it emits `:rf.error/on-destroy-handler-exception` and the frame is still destroyed. Guard the call to `destroy-frame!` if destruction must be prevented.
    - If any cleanup step fails, one `:rf.error/frame-teardown-failed` record lists the failures (`:hook-failures`), and the frame is still removed.
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
- **Example**:
  ```clojure
  (rf/with-frame :app/main
    (rf/current-frame-id))   ;; => :app/main
  ```

### `frame-ids`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-ids) → #{frame-id …}
  (frame-ids ns-prefix) → #{frame-id …}
  ```
- **Description**: Returns the set of live (not destroyed) frame ids. With a string prefix, it keeps the ids whose keyword namespace starts with the prefix; ids with no namespace are left out.
- **Example**:
  ```clojure
  (rf/frame-ids)              ;; => #{:app/main :tenants/acme :tenants/globex}
  (rf/frame-ids "tenants")   ;; => #{:tenants/acme :tenants/globex}
  ```

### `frame-meta`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-meta frame-id) → metadata map or nil
  ```
- **Description**: Returns the metadata `make-frame` recorded for a frame: `:id` plus the supplied config after preset expansion (`:fx-overrides`, `:interceptors`, `:ssr`, `:initial-events`, …), merged with lifecycle fields (`:created-at`, …). Returns `nil` for an unknown or destroyed frame.
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
- **Description**: Returns the frame's current `app-db` as a plain map, not the container, or `nil` for an unknown or destroyed frame. Accepts a frame-id keyword or a live frame value. Read a path with `(get-in (rf/app-db-value frame-id) path)`.
- **Example**:
  ```clojure
  (rf/app-db-value :app/main)
  ;; => {:user {:id 7} :counts {:hits 3}}
  ```

### `frame-state-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-state-value frame-id) → {:rf.db/app … :rf.db/runtime …}
  ```
- **Description**: Returns the frame's whole state, `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`, or `nil` for an unknown or destroyed frame. Use it for SSR, epochs, time travel and Xray, and to save state for [`:rf/install-frame-state`](#rfinstall-frame-state). A fresh frame's state is `{:rf.db/app {} :rf.db/runtime {}}`. Read `runtime-db` with `(:rf.db/runtime (rf/frame-state-value frame-id))`.
- **Example**:
  ```clojure
  (:rf.db/runtime (rf/frame-state-value :app/main))
  ;; => {:rf.runtime/machines {...}}
  ```

### `frame-generation`

- **Kind**: function
- **Signature**:
  ```clojure
  (frame-generation frame-target) → generation map
  ```
- **Description**: Returns the frame's generation: the table of registrations the frame is running, resolved from its images, which says which handler answers each `[kind id]`. It is fixed ("sealed") as a value, and replaced whole when the frame is created, when its images change, and, in every build, when a registration it selects is added or re-evaluated. Use it to check what a frame composed from several images ended up running; tools such as Pair MCP's `describe-image` and Xray read it too.
    - `frame-target` is a frame id or a live frame value. One that does not resolve to a live frame carrying a generation raises `:rf.error/frame-no-generation`.
    - The map's public keys are `:rf.gen/resolver` (the sealed `[kind id]` map), `:rf.gen/images`, `:rf.gen/kinds`, and `:rf.gen/shadows`.
    - `:rf.gen/shadows` is the cross-image shadow report: what a later image overrode in an earlier one, as `[{:registration [kind id] :image <defined-in> :shadowed-by <winner>} …]`, or `[]` when nothing was shadowed.
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
    - `:id` (optional). Every image in a multi-image `:images` vector needs a distinct `:id`.
    - `:select-ns`, `{:include [globs] :exclude [globs]}`, selects registrations by the namespace they were written in. `:include` is a required non-empty vector of strings, and `:exclude` is optional. In a glob, `*` is one namespace segment, `**` is zero or more segments, and `*` inside a segment matches characters within it (`"my-app.**.*-test"`). Matching is case-sensitive over the whole namespace. An `:include` pattern that matches nothing raises `:rf.error/image-zero-match` at `make-frame`; an `:exclude` pattern that matches nothing is ignored.
    - `:registrations`, inline registrations in `:reg-event`, `:reg-sub`, `:reg-fx` and `:reg-cofx` sections. Each entry is `[id body]` or `[id metadata body]`.
    - In `:images`, a later image wins, and `(:rf.gen/shadows (rf/frame-generation f))` reports what it overrode.
    - A registration made through a `reg-*` function rather than its macro records no namespace, so `:select-ns` cannot select it unless its metadata carries `:ns`.
    - `reg-flow`, `reg-app-schema`, `reg-app-schemas` and `reg-http-interceptor` register against a frame, and no image selects them.
    - See [Images](../core/images.md).
- **Errors**:
    - When the image is built:
        - `:rf.error/invalid-image`: any other key, an unsupported section, a malformed entry or a malformed `:select-ns`.
    - At `make-frame`:
        - `:rf.error/image-zero-match`: an `:include` pattern matches no loaded registration.
- **Example**:
  ```clojure
  (def counter-image
    (rf/image {:id        :counter/app
               :select-ns {:include ["my-app.counter.*"]}}))

  (rf/make-frame {:id :counter/main :images [counter-image]})
  ```

### Hot-reloading a frame's images

Re-evaluating a `reg-*` form during development updates the live frames that select it; you call nothing. Calling `rf/reload-images!` throws `:rf.error/reload-images-removed`. To change which images a frame runs, call `make-frame` again with the same `:id` and a new `:images` vector. Frame memory is kept: `app-db`, `runtime-db`, caches and lifecycle continue unchanged, and the frame's generation is replaced:

```clojure
;; config is the map the frame was created with; change only :images.
(rf/make-frame (assoc config :images [new-image]))
```

- The call replaces the frame's whole config, not only `:images`, so pass the rest of it again; a key left out, such as `:fx-overrides`, is dropped.
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

See [Hot reload swaps the image, keeps the state](../core/images.md#hot-reload-swaps-the-image-keeps-the-state).

### `generation-diff`

- **Kind**: function
- **Signature**:
  ```clojure
  (generation-diff before after) → diff map
  ```
- **Description**: Compares two sealed image generations and returns `{:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}`. It is pure. Use it on `frame-generation` values read before and after re-calling `make-frame`, as shown above. `:changed` and `:retained` hold keys present in both; a key is `:changed` when its resolved descriptor is not `=`, so an unchanged registration selected through a different image is `:retained`.

## Lifecycle and configure

These functions start and stop a re-frame2 process and set process-wide options. An application calls `init!` once at boot, then creates its frames; it calls `configure!` only to change a process option. The other functions here are for tests, tools and adapter authors.

### `init!`

- **Kind**: function
- **Signature**:
  ```clojure
  (init! adapter-map) → nil
  ```
- **Description**: Installs the adapter for your view library, and the runtime capabilities that come with it. Each adapter namespace exports an `adapter` Var; require the namespace and pass the Var: `(rf/init! reagent-adapter/adapter)`. It creates no frame: mount one with [`frame-root`](#frame-root) or create one with [`make-frame`](#make-frame).
    - `(init!)` with no argument is an arity error: an `ArityException` on the JVM, a compiler warning on ClojureScript. `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified`.
    - Calling it again with the installed adapter is a no-op. Calling it with a different adapter raises `:rf.error/adapter-already-installed` and leaves the installed one in place; call `destroy-adapter!` first to switch. Two adapter maps are the same adapter when they carry the same canonical `:rf.adapter/*` `:kind`, or when they are the identical map.
    - Until `init!` runs, `make-frame`, `frame-root` and anything else that needs the adapter raise `:rf.error/no-adapter-installed`. Returns `nil`.
    - The shipped adapter Vars: `re-frame.adapter.reagent/adapter`, `re-frame.adapter.reagent-slim/adapter`, `re-frame.adapter.uix/adapter`, `re-frame.fresco.substrate/adapter`, `re-frame.ssr/adapter` for server rendering, and `re-frame.substrate.plain-atom/adapter`, the headless adapter for tests and scripts on the JVM or Node.
    - Hot reload is safe with the shipped adapters. Reloading re-evaluates the adapter map with new function identities, but its canonical kind is unchanged, so a `^:dev/after-load` call stays a no-op. See [Hot reload](../core/how-to/boot-and-mount-an-app.md#hot-reload).
    - A custom adapter with no canonical kind is compared by identity, so re-evaluating its Var and calling `init!` again raises. Hold it in a `defonce`, or call [`destroy-adapter!`](#destroy-adapter) in the after-load fn.
    - See [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md).
- **Example**:
  ```clojure
  (:require [re-frame.adapter.reagent :as reagent-adapter])

  (rf/init! reagent-adapter/adapter)
  ```

### `destroy-adapter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (destroy-adapter!) → nil
  ```
- **Description**: Tears down the installed adapter so another can be installed; the counterpart of `init!`. Returns `nil`.
    - It releases every Fresco root on the page, whatever adapter is installed, and then runs the adapter's own cleanup, which releases every React root the adapter created. A root whose owner does not render again stays empty until the page reloads.
    - A cleanup step that throws does not stop the steps after it. The first failure is rethrown with any later one attached: among its suppressed exceptions on the JVM, and in its `rfAdapterTeardownSecondaryErrors` array on CLJS. The adapter counts as disposed even when cleanup throws.
    - It clears only the adapter it tore down, so a late cleanup from the old adapter never clears a new one. With no adapter installed it does nothing.
    - Until `init!` installs another adapter, anything that needs one raises `:rf.error/adapter-disposed`.
- **Example**:
  ```clojure
  ;; In a test fixture or a hot-reload swap: remove the adapter, then install one.
  (rf/destroy-adapter!)
  (rf/init! reagent-adapter/adapter)
  ```

### `current-adapter`

- **Kind**: function
- **Signature**:
  ```clojure
  (current-adapter) → adapter map or nil
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
  (configure! config-map) → nil
  ```
- **Description**: Sets process-wide options, usually once at boot. Per-frame settings go in the frame config, and swappable implementations have their own setters; see [Where settings live](../core/how-to/configure-dev-and-prod.md#4-where-settings-live). The key set is closed, and four keys exist:

    | Key | Opts | Default | What it tunes |
    |---|---|---|---|
    | `:epoch-history` | `{:depth N :trace-events-keep N}` | `{:depth 50, :trace-events-keep 50}` | Development only; ignored without the epoch artefact. `:depth` is the records kept per frame; `0` turns recording off, and lowering it trims existing history at once. `:trace-events-keep` is how many of the newest records keep their raw `:trace-events`. An invalid value is dropped silently. See [Configuration](re-frame.epoch.md#configuration). |
    | `:trace-buffer` | `{:events-retained N}` | `{:events-retained 50}` | Development only. Event slots in each frame's trace ring: one slot per event, however many trace events its run emitted. `0` disables retention; tracing itself stays on. It applies to every frame without its own `:rf.trace/events-retained`. Opts without a usable `:events-retained` (negative, non-numeric, or `{:depth N}`) change nothing and emit `:rf.warning/trace-buffer-unrecognised-opts`. |
    | `:elision` | `{:rf.egress/threshold-bytes N}` | `{:rf.egress/threshold-bytes 16384}` | Size above which an undeclared large string triggers the `:rf.warning/large-value-unschema'd` advisory. The value is still forwarded unchanged; replacing it with the `:rf.size/large-elided` marker requires declaring the path `:large`. `0` turns the detection off. |
    | `:observability` | `{:handled-events [<entry>…] :errors [<entry>…]}` | none declared | The process default for production observation sinks. See below. |

    - `:observability` takes the same grammar as a frame's `:observability`. Precedence is per stream: a frame that declares a stream uses its own entries, a frame that omits it inherits this default's, and `{:errors []}` on a frame opts that frame out. Each record goes to exactly one source per stream.
    - A frame that inherits the default's sinks still projects its records under its own classification. A record that belongs to no live frame (emitted outside any frame, or naming a frame that no longer exists) goes to this default's sinks alone, projected with no frame's classification, so its data is redacted as [`project-egress`](#project-egress) describes for an unknown frame. Unlike the other keys, an explicit `nil` clears it, and it is validated when you call `configure!` (`:rf.error/bad-frame-classification`, `:where 'rf/configure!`).
    - An unknown top-level key is ignored, and the known keys in the same map are still applied. The known keys are bare, so a bare or `rf`-namespaced unknown key such as `:epoch-histroy` is treated as a typo: development builds emit `:rf.warning/unknown-configure-key`, naming every offending key and the known set, and production builds remove the warning. A key in your own namespace (`:myapp/thing`) passes silently, so a wrapper can hand `configure!` a composed config without filtering it.
    - The argument must be one map. Anything else, including `nil`, raises `:rf.error/configure-bad-arg` in every build and applies nothing. The keyed form `(rf/configure! :trace-buffer {…})` raises it on CLJS and is an `ArityException` on the JVM.
    - SSR error-projection options (`:public-error-id`, `:dev-error-detail?`) are per-frame, on the frame's `:ssr` map.
    - See [The `configure!` bucket](../core/how-to/configure-dev-and-prod.md#the-configure-bucket-process-wide-data).
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
    - `:epoch-history` is present only when the `day8/re-frame2-epoch` artefact is loaded, and `:trace-buffer` only when the development trace tooling is (a production bundle omits it). The function never fills in a default for missing code.
    - `:observability` is present only after a process default is declared, and shows it as declared, not a frame's effective policy.
    - Keys are read one at a time, so the map is not a consistent snapshot across keys, and it may hold values that do not serialise. User-namespaced keys that `configure!` accepted are not reported.
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
  ;; => {:epoch {:maven "day8/re-frame2-epoch" :require "re-frame.epoch" :spec "Tool-Pair (Time-travel / epoch)" :loaded? true} …}

  (get-in (rf/features) [:routing :loaded?])   ;; => true once re-frame.routing has loaded

  (when-not (get-in (rf/features) [:epoch :loaded?])
    (throw (ex-info "re-frame.epoch is not loaded"
                    (get (rf/features) :epoch))))
  ```

## Feature registration (re-exports)

Each optional feature's registration macros are on this facade, so one require covers every registration. The entries here are pointers: the feature's page documents each macro in full, along with the feature's `:fx` ids, standard events and standard subscriptions.

The feature itself ships in its own artefact, which must be loaded as its page describes; without it, the macro throws that feature's missing-artefact error: `:rf.error/machines-artefact-missing`, `:rf.error/routing-artefact-missing`, `:rf.error/flows-artefact-missing`, `:rf.error/schemas-artefact-missing`, `:rf.error/ssr-artefact-missing`, `:rf.error/http-artefact-missing` or `:rf.error/resources-artefact-missing`.

Most of these register process-wide, like `reg-event`. Four register against one frame: `reg-flow`, `reg-app-schema`, `reg-app-schemas` and `reg-http-interceptor`. Name the frame with `:frame`, or call them inside [`with-frame`](#with-frame); with neither, they throw `:rf.error/no-frame-context`.

### Machines → [re-frame.machines.md](re-frame.machines.md)

A state machine is registered with one call and is itself an event handler; its transition table is data. Read a machine's `{:state :data :tags}` snapshot with `@(rf/subscribe [:rf/machine machine-id])`, and test a tag reactively with `@(rf/subscribe [:rf.machine/has-tag? machine-id tag])`. The `[:rf.machine/spawn …]`, `[:rf.machine/destroy …]` and `[:raise …]` fx are on the machines page, as is the plain-function `reg-machine*`.

#### `reg-machine`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-machine machine-id machine-spec) → machine-id
  (reg-machine machine-id opts machine-spec) → machine-id
  ```
- **Description**: Registers a state machine as the event handler for `machine-id`, keeping per-element source coordinates for Xray. The optional `opts` map is registration metadata; its `:schema` validates the dispatched outer event vector. See [`reg-machine`](re-frame.machines.md#reg-machine).

#### `defmachine`

- **Kind**: macro
- **Signature**: `(defmachine name ?docstring machine-spec)`
- **Description**: Defines a machine spec as a Var, capturing its source at the definition site so a later `(rf/reg-machine :id name)` keeps it. See [`defmachine`](re-frame.machines.md#defmachine).

### Routing → [re-frame.routing.md](re-frame.routing.md)

Routes are data. The current route lives in `runtime-db` and is read with the `:rf/route` sub. The routing events (`:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf.route/url-requested`), fx (`[:rf.nav/push-url …]`, `[:rf.nav/replace-url …]`, `[:rf.nav/scroll …]`) and the `:rf/route` sub family are on the routing page. The browser URL listener comes with the frame's `:url-bound?` lifecycle, installed at creation and removed at destroy.

#### `reg-route`

- **Kind**: macro
- **Signature**: `(reg-route id metadata path) → id`
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
  (reg-flow flow-id metadata derive-fn) → flow-id
  ```
- **Description**: Registers a flow, a derived value kept in `app-db` so handlers can read it. `metadata` carries `:inputs` and `:output-path` (both required) and optionally `:doc`, `:schema` and the `:frame` to mount on; without `:frame` it uses the frame in scope, and with neither it throws `:rf.error/no-frame-context`. `derive-fn` is pure. Returns `flow-id`. Throws `:rf.error/flows-artefact-missing` when `re-frame.flows` is not loaded. See [`reg-flow`](re-frame.flows.md#reg-flow).

### Schemas → [re-frame.schemas.md](re-frame.schemas.md)

Malli schemas attached to `app-db` paths, validated on writes in development builds and not in production. The introspection functions (`app-schemas`, `app-schema-meta`, …) and validator hooks are on the schemas page. To validate an untrusted event payload in every build, set `:boundary? true` on [`reg-event`](#reg-event).

#### `reg-app-schema`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schema path schema) → path
  (reg-app-schema path metadata schema) → path
  ```
- **Description**: Attaches a Malli schema to an `app-db` path. The path is the registration id, which makes this the only path-keyed `reg-*`; the optional metadata map carries the `:frame` target. Without `:frame` it registers against the frame in scope, and with neither it throws `:rf.error/no-frame-context`. Production builds register the schema but never check it. See [`reg-app-schema`](re-frame.schemas.md#reg-app-schema).

#### `reg-app-schemas`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-app-schemas {path-1 schema-1, path-2 schema-2, ...}) → [path …]
  (reg-app-schemas {path-1 schema-1, ...} opts-or-frame-id) → [path …]
  ```
- **Description**: Registers many path-to-schema entries in one call, and returns the vector of registered paths. The optional second argument names the frame for every entry: an opts map (`{:frame target}`), a frame-id keyword, or a frame value. Without it the entries register against the frame in scope, and with neither it throws `:rf.error/no-frame-context`. Checked in development builds only, as for `reg-app-schema`. See [`reg-app-schemas`](re-frame.schemas.md#reg-app-schemas).

### SSR → [re-frame.ssr.md](re-frame.ssr.md)

Only the two SSR registration macros, `reg-head` and `reg-error-projector`, are on this facade. They resolve to `re-frame.ssr` when the `day8/re-frame2-ssr` artefact is on the classpath and throw `:rf.error/ssr-artefact-missing` otherwise.

Everything else is called on `re-frame.ssr`: `render-to-string`, `render-tree-hash`, `project-error`, `head-model`, `head-model->html` and `hydrate!` (the head functions are also on `re-frame.ssr.head`). Requiring `re-frame.ssr` is what installs the SSR runtime, so an app that renders on the server already names it. The SSR page also documents the `:rf.server/*` fx and the `:rf/server-init` and `:rf/hydrate` events; the Ring adapter is on [re-frame.ssr.ring](re-frame.ssr.ring.md).

SSR registers no subscriptions: `:rf/head` and `:rf/public-error` are data shapes, read with `head-model` and `project-error` ([details](re-frame.ssr.md#subscriptions--there-are-none)).

#### `reg-head`

- **Kind**: macro
- **Signature**: `(reg-head id ?metadata head-fn) → id`
- **Description**: Registers a head function `(fn [db route] head-model)` under `id`; a route uses it through its `:head` metadata. See [`reg-head`](re-frame.ssr.md#reg-head).

#### `reg-error-projector`

- **Kind**: macro
- **Signature**: `(reg-error-projector id ?metadata projector-fn) → id`
- **Description**: Registers a projector `(fn [trace-event] :rf/public-error)` that turns an error trace into the public error a server response shows. A frame selects one with its `:ssr {:public-error-id …}` metadata. See [`reg-error-projector`](re-frame.ssr.md#reg-error-projector).

### HTTP → [re-frame.http.md](re-frame.http.md)

Managed HTTP is one fx, `[:rf.http/managed …]`, with one args map and a closed failure taxonomy. The fx, `[:rf.http/managed-abort …]` and the failure taxonomy are on the HTTP page. Request stubbing (`with-request-stubs` and the raw install/uninstall pair) is on `re-frame.http.test-support`, not this facade.

#### `reg-http-interceptor`

- **Kind**: macro
- **Signature**: `(reg-http-interceptor id interceptor-map) → id`
- **Description**: Adds an interceptor to a frame's `:rf.http/managed` middleware chain, for work every request needs, such as an auth header. The frame is the `:frame` key in `interceptor-map`, or the frame in scope; with neither it throws `:rf.error/no-frame-context`. `:before (fn [ctx] ctx')` runs on the request side, in registration order; `:after (fn [ctx response] response')` runs on the response side, in reverse order. Remove one with `(rf/clear :http-interceptor id)`, or `(rf/clear :http-interceptor id {:frame target})` to name the frame. See [`reg-http-interceptor`](re-frame.http.md#reg-http-interceptor).

### Resources → [re-frame.resources.md](re-frame.resources.md)

Resources are cached server-state reads, and mutations are the writes that update them. Their keyword-addressed events and subscriptions (`[:rf.resource/ensure …]`, `[:rf.resource/refetch …]`, `[:rf.mutation/execute …]`, the passive `:rf.resource/*` and `:rf.mutation/*` subs, …) are on the resources page.

- Focus and reconnect revalidation is the frame's `:revalidate-on #{:focus :reconnect}` config key; the frame lifecycle installs and removes the host listeners. See [Revalidation is a frame property](re-frame.resources.md#revalidation-is-a-frame-property).
- `(rf/clear :resource id)`, `(rf/clear :mutation id)` and `(rf/clear :resource-scope id)` remove a registration and dispose its per-frame runtime state. That is not cache invalidation, nor the `[:rf.mutation/clear …]` reset of a mutation instance. See [Clearing a registration](re-frame.resources.md#clearing-a-registration).

#### `reg-resource`

- **Kind**: macro
- **Signature**: `(reg-resource resource-id metadata request-fn) → resource-id`
- **Description**: Registers a resource, a cached read, as data. `metadata` carries the required, fail-closed `:scope` policy and `:params-schema`; `request-fn` returns a managed-HTTP args map. See [`reg-resource`](re-frame.resources.md#reg-resource).

#### `reg-mutation`

- **Kind**: macro
- **Signature**: `(reg-mutation mutation-id metadata request-fn) → mutation-id`
- **Description**: Registers a mutation: a named write that, on success, invalidates, patches or populates cached reads. See [`reg-mutation`](re-frame.resources.md#reg-mutation).

#### `reg-resource-scope`

- **Kind**: macro
- **Signature**: `(reg-resource-scope scope-id metadata resolve-fn) → scope-id`
- **Description**: Registers a named scope resolver that a resource's `:scope` policy refers to. This is the only arity: the resolve fn is the last argument, and `metadata` must declare `:inputs` (to read the whole db, declare the root path: `{:inputs {:db [:db []]}}`). Returns `scope-id`. See [`reg-resource-scope`](re-frame.resources.md#reg-resource-scope).

#### `resolve-resource-scope`

- **Kind**: function
- **Signature**: `(resolve-resource-scope db scope-id) → scope or nil`
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

Read it with `rf/handler-meta` and the kind's inner key, as [Reading registrations](re-frame.resources.md#reading-registrations) shows.

#### Enumerating resources and mutations

List registered ids with `rf/registrations` and read the live tables at their reserved `runtime-db` paths, as [Enumerating the whole live table](re-frame.resources.md#enumerating-the-whole-live-table) shows.

## Instrumentation and listeners

There are two ways to observe a running app, with different functions:

- The trace and epoch streams are for development tools. The runtime emits a trace event for each step of a pipeline run (the event handled, each subscription run, each effect, each error) and, per processed event, an epoch record: the event with the frame's state before and after it. It keeps the most recent in per-frame rings (fixed-size buffers that drop the oldest entry), read with [`trace-buffer`](#trace-buffer) and [`epoch-history`](#epoch-history), and delivers each one synchronously to listeners registered with `register-listener!`. Production builds (`:advanced` with `goog.DEBUG=false`) remove all of it. The epoch (time-travel) functions are also on [re-frame.epoch](re-frame.epoch.md).
- Observability sinks work in every build. They receive one record per processed event and one per `:rf.error/*` error. Register a sink with [`register-observability-sink!`](#register-observability-sink) and name it in a frame's `:observability` policy or the `(rf/configure! {:observability …})` process default.

See [Observability](../core/observability.md).

### `register-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-listener! stream id callback-fn) → id or nil
  ```
- **Description**: Registers `callback-fn` under `id` to receive every record the runtime emits on `stream`, for development tools. `stream` is `:trace` (development only, removed from production builds) or `:epoch` (development only, from the optional epoch artefact). `:trace` events arrive as emitted, with paths the frame classifies already `:rf/redacted` or elided and no egress profile applied; `:epoch` records hold the frame's state unprojected.
    - Delivery is synchronous: the callback returns before the next record. Records emitted while a frame is draining its event queue are held and delivered when the drain ends, before the call that ran it returns, so a listener sees settled state and never runs mid-drain. On the JVM, where emits can race across threads, each listener is called serially and never concurrently with itself, so tool appenders and stateful folds need no locking of their own.
    - Registering an id again on the same stream replaces the listener. Returns `id`, or `nil` on the `:epoch` stream when the `day8/re-frame2-epoch` artefact is absent. An unknown `stream` throws `:rf.error/unknown-listener-stream`.
    - A callback that throws does not stop delivery to other listeners. On `:trace` the exception is discarded without a report; on `:epoch` it emits `:rf.epoch.cb/listener-exception`.
    - The `:epoch` stream's publication rules are under [Epoch listeners](re-frame.epoch.md#epoch-listeners).
    - Production code registers a sink with [`register-observability-sink!`](#register-observability-sink) instead; see [Report errors in production](../core/how-to/report-errors-in-production.md).
    - See [Write a listener](../core/observability.md#write-a-listener).
- **Example**:
  ```clojure
  ;; Dev-only: tap every trace event the runtime emits (removed from production builds).
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
- **Description**: Removes the listener registered under `id` on `stream`. The test fixtures in [`re-frame.test-support`](re-frame.test-support.md) reset every listener between tests.
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
- **Description**: Emits a custom trace event into the same stream the framework emits to, for milestones of your own that a tool should see beside the framework's. The framework already emits the events that matter, so use it sparingly.
    - `op-type` is the event's category, `operation` names this particular event, and `tags` is a map of its data. Keep all three in your own namespace: the `:rf.*` namespaces belong to the framework.
    - The runtime stamps `:id` and `:time`, adds the event to the current frame's trace history and delivers it to every `:trace` listener, as for a framework event.
    - Development only: production builds remove the call.
    - See [Emitting your own trace events](../core/observability.md#emitting-your-own-trace-events).
- **Example**:
  ```clojure
  (rf/emit-trace-event! :my-app/cache :my-app.cache/hit {:my-app.cache/key [:user 7]})
  ```

### `trace-buffer`

- **Kind**: function
- **Signature**:
  ```clojure
  (trace-buffer frame-id) → vector of event bundles, oldest-first
  (trace-buffer frame-id opts) → vector; {:flat true} yields raw trace events
  ```
- **Description**: Returns the named frame's development trace ring without clearing it: by default one bundle per retained pipeline run; with `{:flat true}`, the raw trace events.
    - Each bundle has the shape [`group-by-event`](re-frame.trace.projection.md#group-by-event) returns, plus `:trace-events`.
    - Returns `[]` for a destroyed or unknown frame, and always in production, where the ring is never allocated.
    - The number of retained events is the `(rf/configure! {:trace-buffer {:events-retained N}})` setting, or the frame's own `:rf.trace/events-retained`.
    - `group-by-event` and `domino-bucket`, which group trace events into bundles, are on [`re-frame.trace.projection`](re-frame.trace.projection.md), not this facade.
    - See [The trace buffer](../core/observability.md#the-trace-buffer).
- **Options**: the `opts` keys filter what is returned.

    | Key | Matches | Works on |
    |---|---|---|
    | `:flat` | `true` returns the raw trace events instead of event bundles. | Both |
    | `:event-id` | The bundle's event id, or a flat event's `:tags :rf.trace/event-id`. | Both |
    | `:origin` | The dispatch's `:origin` (`:tags :rf.event/origin`). | Both |
    | `:dispatch-id` | The pipeline run's dispatch id (a flat event's `:tags :rf.trace/dispatch-id`). | Both |
    | `:since-ms` | Later than this time; a bundle's time is its earliest event's. | Both |
    | `:between [t0 t1]` | Within this time window, inclusive. | Both |
    | `:pred` | A predicate over the bundle or event. | Both |
    | `:operation`, `:op-type` | An exact match on the event's `:operation` or `:op-type`. | Flat |
    | `:severity` | `:op-type` equal to this value: `:error`, `:warning` or `:info`. | Flat |
    | `:since` | A trace `:id` greater than this. | Flat |
    | `:handler-id` | `:tags :handler-id`. | Flat |
    | `:source` | The event's `:source`. | Flat |
    | `:sensitive?` | The event's `:sensitive?` stamp: `true` keeps sensitive events, `false` keeps the others. | Flat |

    Keys combine with AND; an unrecognised key is ignored.
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
- **Description**: Empties the retained trace events of the named frame, or of every frame. It clears data, not settings: each ring is re-emptied at its own `:rf.trace/events-retained` cap, so the `:trace-buffer` default and every per-frame override survive. A no-op for an unknown frame and in production.
- **Example**:
  ```clojure
  (rf/clear-trace-buffer! :app/main)   ;; empty one frame's ring (e.g. between tool sessions)
  (rf/clear-trace-buffer!)             ;; empty every ring; retention policy stays put
  ```

### `sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (sensitive? trace-event) → boolean
  ```
- **Description**: Returns true when `trace-event` is a map with a truthy `:sensitive?` at the top level (not under `:tags`). Use it to drop sensitive events before forwarding them. Call it rather than testing `:sensitive?` yourself, so a malformed value is caught too.
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

### `project-egress`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-egress record-or-value) → projected value
  (project-egress record-or-value opts) → projected value
  ```
- **Description**: Returns a record or value prepared for sending off-box: the paths the frame classifies as sensitive are redacted and large values elided, as the chosen egress profile (a named policy for one kind of destination) directs. Call it before handing anything to an off-box destination yourself, or to check what a sink will receive; sinks registered with [`register-observability-sink!`](#register-observability-sink) are handed records already projected. It is the only record-level egress function.
    - It recognises four record kinds by `:kind`: `:rf.observe/handled-event`, `:rf.observe/error`, `:rf.observe/derived-tree` and `:rf/epoch-record`. An input without a recognised `:kind` is walked as a plain value against the frame's classification.
    - Projecting an `:rf/epoch-record` needs the optional `day8/re-frame2-epoch` artefact. Without it the call throws `:rf.error/epoch-artefact-missing`, naming the kind, rather than walking the record as a plain value, which would ship its `app-db` slots raw.
    - To project a whole epoch ring, map over it: `(mapv #(rf/project-egress % opts) (rf/epoch-history frame-id))`. The ring and its listeners always hold the raw record, so projecting never affects `restore-epoch!`. See [re-frame.epoch](re-frame.epoch.md).
    - See [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md), and [Choosing a profile](../core/how-to/keep-secrets-out-of-traces.md#choosing-a-profile).
- **Options**: `opts` is closed; an unknown key throws `:rf.error/bad-egress-opts`, naming it.

    | Key | Meaning |
    |---|---|
    | `:rf.egress/profile` | The destination, one of the six profiles below. Unset: sensitive values redacted, large values elided. An unknown profile throws `:rf.error/unknown-egress-profile`. |
    | `:frame` | The frame whose classification applies. Chosen by key presence: an explicit `:frame` (even `nil`), else a recognised record's own `:frame` (even `nil`), else the current frame. A plain value that happens to carry a `:frame` key contributes no frame. With no frame known, and `:rf.egress/include-sensitive?` not `true`, the value becomes `:rf/redacted`; there is no fallback to `:rf/default`. |
    | `:path` | The `app-db` path a plain value was read from, so declarations at or above that path apply inside it: `(rf/project-egress (get-in db [:auth]) {:frame :app/main :path [:auth]})`. Without it the value is classified as if it were the whole `app-db`, and `[:auth :token]` does not match. |
    | `:query-v` | For a value read from a framework subscription such as `[:rf/machine id]` or `[:rf/route]`, that query vector; classification declared on the machine or route then applies. Ignored for other queries. |
    | `:rf.egress/include-sensitive?`, `:rf.egress/include-large?`, `:rf.egress/include-digests?` | Override the profile's choice for this call; an explicit value wins over the profile. |
    | `:rf.egress/threshold-bytes` | The large-value size threshold for this call (see `configure!`'s `:elision`). |
    | `:rf.egress/include-fx-args?`, `:rf.egress/include-runtime-db?`, `:rf.egress/include-event-args?` | `:rf/epoch-record` only: trusted-local opt-ins for effect `:args`, the `:rf.db/runtime` partition, and trigger and trace event args. Each defaults to false and enables only its own data; `:rf.egress/include-sensitive? true` implies none of them. On other kinds they are accepted and ignored. |

    The six profiles:

    | Profile | For | Sensitive values | Large values |
    |---|---|---|---|
    | `:rf.egress/off-box-observability` | Hosted monitoring | Redacted | Elided |
    | `:rf.egress/off-box-tool` | MCP, AI and other tool connections | Redacted | Elided |
    | `:rf.egress/local-redacted` | On-box development UI | Redacted | Elided |
    | `:rf.egress/local-raw` | A trusted local operator | Sent | Sent |
    | `:rf.egress/ssr-hydration` | State the server sends to the browser | Redacted | Sent, since the page needs them |
    | `:rf.egress/public-error` | Errors shown to a client | Redacted | Elided |

    No profile includes digests; pass `:rf.egress/include-digests? true` for them. `:rf.egress/public-error` also drops an error record's `:exception`. Otherwise, profiles with the same settings differ only in the destination they name.
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
  (register-observability-sink! sink-id f) → sink-id
  ```
- **Description**: Registers a production observability sink `f` under the keyword `sink-id`, the id a frame's `:observability {:handled-events [{:sink <sink-id> :rf.egress/profile …}]}` entry names. Use it to send handled events and errors to a monitoring service. A frame's `:observability` has two streams, `:handled-events` (one record per processed event) and `:errors` (one per `:rf.error/*` error), and each lists the sinks it goes to.
    - `f` receives one record at a time, already projected under the owning frame's classification and the entry's egress profile, which defaults to `:rf.egress/off-box-observability`; it does no redaction of its own. A handled-event record carries `:kind`, `:frame`, `:event-id`, `:status` and `:elapsed-ms`, plus `:effects` and `:correlation` when present, and the `:event` vector only under a profile that includes sensitive values, such as `:rf.egress/local-raw`.
    - A sink that throws is isolated: the event and the other sinks are unaffected.
    - Registering the id again replaces the sink. Returns `sink-id`.
    - It works in every build, including CLJS `:advanced` with `goog.DEBUG=false`.
    - See [Report errors in production](../core/how-to/report-errors-in-production.md) and [Declare a sink](../core/observability.md#consuming-production-telemetry-declare-a-sink).
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

### `epoch-history`

- **Kind**: function
- **Signature**:
  ```clojure
  (epoch-history frame-target) → vector of epoch records
  ```
- **Description**: Returns the retained epoch records of `frame-target`, a frame id or a live frame value, oldest first, in development builds. See [`epoch-history`](re-frame.epoch.md#epoch-history) for the full contract.

### `restore-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-epoch! frame-target epoch-id) → boolean
  ```
- **Description**: Development builds only: rewinds `frame-target`, a frame id or a live frame value, to the state the named epoch recorded, `app-db` and `runtime-db` together, and returns `true`, or `false` when it refuses. See [`restore-epoch!`](re-frame.epoch.md#restore-epoch) for the full contract.

### `replay-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replay-epoch! frame-target epoch-id)      → envelope map (false when elided / artefact absent)
  (replay-epoch! frame-target epoch-id opts) → envelope map (false when elided / artefact absent)
  ```
- **Description**: Development builds only: runs a retained epoch's event again in `frame-target`, a frame id or a live frame value, with the coeffect values and overrides it recorded, and returns a `{:ok? …}` envelope. See [`replay-epoch!`](re-frame.epoch.md#replay-epoch) for the full contract.

### `replace-frame-state!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-frame-state! frame-target frame-state) → boolean
  ```
- **Description**: Development builds only: writes one or both partitions of the state of `frame-target`, a frame id or a live frame value, outside the event pipeline, and records a synthetic epoch so `restore-epoch!` can rewind it. See [`replace-frame-state!`](re-frame.epoch.md#replace-frame-state) for the full contract.

### `epoch-silence-current?`

- **Kind**: function
- **Signature**:
  ```clojure
  (epoch-silence-current? tags) → boolean
  ```
- **Description**: Development builds only. Tells a trace listener whether a `:rf.epoch.cb/silenced-on-frame-destroy` signal still describes a current fact. Pass the signal's `:tags` map (`:frame`, `:cb-id`, `:observed-gen`). Returns true when the signal still applies: the callback registered under `:cb-id` is the same registration the signal was about (`:observed-gen`), not replaced or unregistered since, and it is not receiving records from a new frame under the id `:frame`. Both facts are read at the same moment. Returns false otherwise, including for a `nil` or absent `:observed-gen` and when the `day8/re-frame2-epoch` artefact is absent.
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

`registrations` and `handler-meta` take a map naming their source: `{:source :store …}` asks what the process has registered, and `{:frame f …}` asks what one frame runs. The two answer differently only when a frame is built from images that select part of the registrar.

### `registrations`

- **Kind**: function
- **Signature**:
  ```clojure
  (registrations {:source :store :kind k}) → {id metadata-map}
  (registrations {:frame f      :kind k}) → {id metadata-map}
  ```
- **Description**: Returns every registration of one kind, with the full metadata map per id: source coordinates, `:sensitive`, `:rf/machine?`, `:platforms`, the doc string. The argument is always a map, and it names exactly one source.
    - `{:source :store …}` reads the process-wide registrar and never consults a frame's image, so it answers the same inside any frame; this is what a tool inspecting a host application wants. `:store` is the only `:source` value.
    - `{:frame f …}` returns only the ids that frame's image carries, resolved through its generation (see [`frame-generation`](#frame-generation)). `f` is a frame-id keyword or a live frame value; one that does not resolve to a live frame carrying a generation raises `:rf.error/frame-no-generation`.
    - Naming both `:source` and `:frame`, naming neither, a `:source` other than `:store`, or a non-map argument (usually a leftover positional call) raises `:rf.error/registrar-query-needs-source`. There is no default source.
    - `:kind :flow` and `:kind :frame` raise `:rf.error/registrar-kind-not-queryable`, naming the function to use instead (`re-frame.flows/flows`, `flow-meta`, `flows-snapshot`; `frame-ids`, `frame-meta`), rather than returning a misleading `{}`.
    - Any other `:kind` outside the registrar kinds raises `:rf.error/unknown-registry-kind`, naming the queryable set.
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
    - The source grammar and the errors are those of `registrations` (`:rf.error/registrar-query-needs-source`, `:rf.error/frame-no-generation`, `:rf.error/registrar-kind-not-queryable`, `:rf.error/unknown-registry-kind`).
    - App-db schemas are not a registrar kind; look them up with `(app-schema-meta {:frame f :path p})` in [re-frame.schemas](re-frame.schemas.md).
- **Example**:
  ```clojure
  (rf/handler-meta {:source :store :kind :sub :id :counter/value})
  ;; => {:ns my-app.subs :line 8 :column 1 :file "my_app/subs.cljs"}
  ```

## See also

- [Events](../core/events.md), [Subscriptions](../core/subscriptions.md), [Views](../core/views.md), [Frames](../core/frames.md), [Effects](../core/effects.md), [Coeffects](../core/coeffects.md), [Interceptors](../core/interceptors.md), [Run to completion](../core/run-to-completion.md) and [Observability](../core/observability.md): the concept guides behind these functions.
- [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md): the task guides.
- [Core glossary](../core/glossary.md).
