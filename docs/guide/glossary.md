# Glossary

A catalog of re-frame2's nouns, verbs, and concepts.

## The Nouns

### **adapter**

re-frame2 works with various rendering libraries including Uix, Reagent and Helix. An `adaptor` is a map of "glue" functions that connect re-frame2's core to one of these libraries.

You install an `adapter` at application boot time with `init!`.

For example, if you are using reagent:
```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

Or Uix:
```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]))

(rf/init! uix-adapter/adapter)
```

Related: [Views](concepts/views.md), [Adapters](../api/14-adapters.md).

### **app-db**

The map holding application state in a [frame](#frame).

You design the map's structure to fit the app, and optionally you can provide a Malli schema. The `event handlers` you write will build and maintain this application state, and the `subscriptions` you write will provide projections of it to `views`.

An example structure:
```clojure
{:cart {:items [{:sku "BK-1" :qty 2}]} :user/name "Ada"}
```

Related: [app-db](concepts/app-db.md), [Validate with schemas](how-to/validate-with-schemas.md).

### **coeffect**

The first argument to an event handler is a map of facts about the world: a map of coeffects.

This map always contains the key `:db` which provides the current value of [app-db](#app-db). If a handler needs other facts, such as the current date, a UUID, or local storage, they must be specified in the event handler's metadata using the key `:rf.cofx/requires` like this.

```clojure
(rf/reg-event :order/place
  {:rf.cofx/requires [:today]}           ;; :today is a required coeffect
  (fn [{:keys [db today]} _]             ;; the key :today will appear in the coeffects map
    {:db (assoc db :order/date today)}))
```


For this to work, you must register a coeffects handler via [`reg-cofx`](../api/01-core.md#reg-cofx) for `:today`. For example:

```clojure
(rf/reg-cofx :today
  (fn []
    (subs (.toISOString (js/Date.)) 0 10)))
```


Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **effects map**

The map returned by an `event handler` or `machine action`.

Given an `event` and `coeffects`, an `event handler` computes and returns an `effects map` describing how "the world" should be changed: the side effects of the event.

The `event handler` does not make changes itself; it computes a description of the necessary changes.

An `event handler` often returns only one `:db` effect, meaning "replace app-db with this new value", like this:

```clojure
{:db new-app-db-value}
```

When an `event handler` also needs HTTP, delayed events, post messages, local storage, email, or other side effects, it typically returns them under `:fx`.

```clojure
{:db new-app-db-value
 :fx [[:http {:url "/api/cart" :method :post}]
      [:dispatch-later {:ms 500 :event [:cart/saved]}]]}
```

Use [`reg-fx`](../api/01-core.md#reg-fx) to register effect handlers.

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md). **Effects** are often shortened to `fx`.

### **epoch**

In re-frame2 observability, an epoch is the record left behind by one completed event cascade.

It captures the event, before/after frame state, and trace evidence used by Xray, time-travel, and debugging tools.

```clojure
;; one dispatch = one cascade = one epoch (the record)
```

Related: [Observability](concepts/observability.md).

### **error record / `:rf.error/*` category**

A failure surfaced as a structured map keyed by a reserved category keyword; branch on the category, never the human-readable `:reason`.

```clojure
{:rf.error/category :rf.error/no-frame-context :reason "no frame in scope"}
```

Related: [Errors](concepts/errors.md).

### **event**

An inert data vector that captures the fact that something happened.

Events often represent user intent: the user clicked a button, dragged something somewhere else, opened a route, or chose a tab. Other application actors can also cause events, such as timers, browser APIs, WebSockets, and route loaders.

The first element is an identifier, typically a namespaced keyword, optionally followed by further facts.

```clojure
[:event-id & facts]
```

So, this is possible:
```clojure
[:event-id "hello" :world 1]
```

But that structure is quite "placeful" and fragile. A better shape is where `facts` is a single map payload.
```clojure
[:cart/add-item {:sku "BK-1" :qty 1}]
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md).

### **event cascade**

The fixed, ordered run started by one dispatched [event](#event).

A cascade runs the event handler, commits any state change, performs effects, updates derivations, and lets views render from the new state.

```clojure
;; dispatch → handler → effect map → commit/effects → derivations → render
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md). One dispatched event leaves behind one [epoch](#epoch), the before/after record of that cascade.

### **event envelope**

The runtime package created when an [event](#event) is dispatched. This is a low level concept tied to the runtime.

App code `dispatches` an `event` (vector), sometimes with extra dispatch options. Before the event is queued, re-frame2 wraps those values into an event envelope. The envelope carries the event vector plus the runtime facts needed to process it: the target [frame](#frame), where the event came from, tracing ids, per-dispatch overrides, and the durable `:rf.cofx` record used for replayable coeffects such as `:rf/time-ms`.

Most application code never reads an event envelope directly. It is the router's internal work item. Event handlers receive the envelope's event vector as their second argument, and receive an assembled map of [coeffects](#coeffect) as their first argument.

```clojure
(rf/dispatch [:cart/add {:sku "BK-1"}]
  {:frame :checkout
   :source :ui
   :trace-id :cart/add-click})

;; The router queues an envelope shaped roughly like:
{:event    [:cart/add {:sku "BK-1"}]
 :frame    :checkout
 :source   :ui
 :origin   :app
 :trace-id :cart/add-click
 :rf.cofx  {:rf/time-ms 1781078400123}}
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md), [Frames](concepts/frames.md), [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **event handler**

A function that computes how an [event](#event) changes the world. It receives two arguments:

- a map of [coeffects](#coeffect): selected facts about the world needed to process the event
- the event vector

It returns an [effects map](#effects-map): data describing what about the world should be changed, and how.

```text
(coeffects, event-vector) -> effect-map
```

An event handler often returns only the `:db` effect, meaning "replace app-db with this new value." It can also return multiple `:fx` entries for other side effects.

You register an `event handler` with `reg-event` like this:

```clojure
(rf/reg-event :your-event-id  your-handler-fn)
```

In practice, something like this:
```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

### **flow**

A flow automatically maintains a derived value in [app-db](#app-db).

You specify the input paths to watch, the `:output-path` to maintain, and a pure `:derive` function. When any input value changes, re-frame2 runs `:derive` with the input values and stores the result at `:output-path`.

Most flows watch ordinary app-db paths. A flow can also watch runtime state by using an input path that starts with `:rf.db/runtime`; the result is still written to app-db.

Flows are often useful for collating or collapsing multiple facts into a single piece of state; for example, collapsing many pieces of error state into one `:any-errors?` value.

Here is a static flow definition:
```clojure
(rf/reg-flow
  {:id :cart/total :inputs [[:cart :items]]
   :derive (fn [items] (reduce + (map :price items)))
   :output-path [:cart :total]})
```

Flows can also be [created and removed dynamically](../api/05-flows.md#runtime-registration-via-fx) via effects.

Related: [Flows](concepts/flows.md) and [toggling a derivation at runtime](concepts/flows.md#toggling-a-derivation-at-runtime).

### **frame**

A frame is an isolated execution context: one running instance of an app.

It owns the state and runtime machinery for that instance: its [app-db](#app-db), [runtime-db](#runtime-db), event queue, subscription cache, and lifecycle.

A frame gets its behaviour from registrations, for example `reg-event` and `reg-view`. Usually it uses the default registration set, so the same event handlers, subscriptions, views, effects, flows, and machines can run in many frames, each against that frame's own state. Advanced setups can use [images](#image) to give a frame a selected registration set.

Most apps create one frame at boot and then stop thinking about it. Short-lived frames are useful for tests, stories, and server-rendered requests. Multiple independent app instances can run on the same page by using different frames; a sidecar tool such as Xray is just a separate app in its own frame.

```clojure
(rf/reg-frame :app
  {:initial-events [[:some-initialise-event]
                    [:another-event]]
   :images [image1 image2]})    ;; an optional composition of registrations
```

Related: [Frames](concepts/frames.md).

### **image**

An image is a value that selects registrations: event handlers, subscriptions, views, effect handlers, flows, machines, and other app behaviour. It is not a running app and it does not hold state.

Most apps use the default image automatically, which means "all the registrations already loaded." You name images explicitly when different frames need different behaviour: a test frame with fake effects, two examples that reuse the same event ids, or a sidecar tool such as Xray.

When a frame starts, its image is resolved into a sealed registration set. That resolved set is sometimes called an **image generation**, but the simple rule is enough most of the time: the image supplies behaviour; the frame supplies state.

```clojure
(def checkout-image
  (rf/image {:select-ns {:include ["app.checkout.*"]}}))

(rf/reg-frame :checkout/story
  {:images [checkout-image]})
```

Related: [Images](concepts/images.md).

### **interceptor**

An interceptor wraps around an event handler by providing one or both these functions:

- a `:before` function that runs before the event handler and can inspect or change the handler's coeffects.
- an `:after` function that runs after the event handler and can inspect or change the [effects map](#effects-map) the handler returned.

Both functions receive a context map and return an updated context map.

Use interceptors for cross-cutting concerns such as logging, validation, undo, tracing, or adding effect rows.

Register an interceptor with `reg-interceptor`:

```clojure
(rf/reg-interceptor :my-app/logger
  {:before (fn [ctx] ctx)
   :after  (fn [ctx] ctx)})
```

An event handler opts in by listing interceptor ids in its registration metadata.

```clojure
(rf/reg-event :cart/add
  {:interceptors [:my-app/logger]}    ;; interceptor ids
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Related: [Interceptors](concepts/interceptors.md).

### **machine**

A re-frame2 machine is a statechart-capable state machine, registered as an event handler via `reg-machine`; its live value is a [snapshot](#snapshot).

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Related: [Machines](concepts/machines.md).

### **mutation**

A declared server-state write whose cache consequences (what it invalidates) are declared once on the registration.

```clojure
(rf/reg-mutation :article/favorite {:scope :rf.scope/global} request-fn)
```

Related: [Server state](concepts/server-state.md). The reply field is `:value`; the instance sub field is `:result`.

### **resource**

A declared, cached server-state read registered with `reg-resource`; its `:scope` is a required leak boundary.

```clojure
(rf/reg-resource :article {:scope :rf.scope/global} request-fn)
```

Related: [Server state](concepts/server-state.md). "Server state" is the category, not the noun.

### **registration**

An app's behaviour is defined by the registrations you provide.

A single registration maps an `id`, usually a namespaced keyword, to a function or configuration the runtime can look up later.

At runtime:

- a [frame](#frame) supplies isolated state and execution context
- an [image](#image) supplies the selected registrations
- a stream of [events](#event) drives the runtime, which uses those registrations to decide what to do

For example, this registration says: when the runtime sees the event id `:cart/add`, use this [event handler](#event-handler) function.
```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

[Core](../api/01-core.md) registration:

- `reg-event` registers an [event handler](#event-handler).
- `reg-sub` registers a [subscription](#subscription).
- `reg-fx` registers an effect handler.
- `reg-cofx` registers a [coeffect](#coeffect) supplier.
- `reg-interceptor` registers an event-handler wrapper.
- `reg-frame` registers a named [frame](#frame).
- `reg-view` and `reg-view*` register [views](#view).

Flows registration:

- `reg-flow` registers a [flow](#flow).

[Machines](concepts/machines.md) registration:

- `reg-machine` and `reg-machine*` register [machines](#machine).

Routing registration:

- `reg-route` registers a route.

Schema registration:

- `reg-app-schema` and `reg-app-schemas` register Malli schemas for app-db paths.

SSR registration:

- `reg-head` registers an SSR head producer.
- `reg-error-projector` registers an SSR error projector.

HTTP registration:

- `reg-http-interceptor` registers managed-HTTP middleware.

[Resource](../api/16-resources.md) registration:

- `reg-resource` registers a [resource](#resource).
- `reg-mutation` registers a [mutation](#mutation).
- `reg-resource-scope` registers a named resource-scope resolver.


### **runtime-db**

The framework-owned part of a [frame](#frame)'s state.

It sits beside [app-db](#app-db), which is yours. re-frame2 uses runtime-db for its own durable state: machine snapshots, the current route, resource caches, mutation status, and similar runtime machinery. App code normally reads this state through subscriptions or accessors, not by editing runtime-db paths directly.

```clojure
[:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow]
```

Related: [app-db](#app-db), [frame](#frame). Paths: `:rf.db/runtime`, children `:rf.runtime/*`.

### **snapshot**

A machine's live value at any moment: which state it's in plus its `:data`, read through a subscription addressed by the machine's id.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

Related: [Machines](concepts/machines.md).

### **subscription**

A named, registered, pure, cached, read-only derivation of state.

```clojure
(rf/reg-sub :cart/count (fn [db _] (count (:items (:cart db)))))
```

Related: [Subscriptions](concepts/subscriptions.md). Casual "sub" is fine; not as a headword.

### **substrate**

The React-family rendering layer your app runs on, such as Reagent, UIx, Helix, or reagent-slim.

```clojure
;; "substrate" = the layer; the value you pass to init! is the adapter
```

Related: [Views](concepts/views.md).

### **view**

A pure render function from subscription values to hiccup.

```clojure
(rf/reg-view :cart-badge (fn [] [:span.badge @(rf/subscribe [:cart/count])]))
```

Related: [Views](concepts/views.md). Use "component" for React-analogy callouts only.

## The Verbs

### **commit**

The single, deferred, all-or-nothing app-db write at the end of an event; no observer ever sees a half-written app-db.

```clojure
;; the :db you return is staged; it's committed once, at the end, atomically
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md).

### **dispatch**

Enqueue an [event](#event) for a [frame](#frame) to process.

`dispatch` wraps the event in an [event envelope](#event-envelope) and returns immediately. The event handler runs later, during the [event cascade](#event-cascade).

```clojure
(rf/dispatch [:cart/add-item {:sku "BK-1"}]
  {:frame :checkout})
```

Related: [event](#event), [event envelope](#event-envelope), [event cascade](#event-cascade).

### **drain / run-to-completion**

The runtime drains the whole event queue to a fixed point before subscriptions recompute and views render.

```clojure
;; all queued events run to completion, THEN subs recompute, THEN views render
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md). Hyphenate **run-to-completion** consistently.

### **elide**

Compile dev-only code out of production via one flag, either `goog.DEBUG` or `-Dre-frame.debug`.

```clojure
;; goog.DEBUG=false removes the dev trace surface and schema checks
```

Related: [Observability](concepts/observability.md). Name DCE once, then use **elide**.

### **invalidate**

A mutation declares, as data on its registration, which cached reads it makes stale.

```clojure
{:invalidates (fn [{:keys [slug]} _result]
                {:tags #{[:article slug]}})}     ;; declared once, on the mutation
```

Related: [Server state](concepts/server-state.md).

### **navigate**

Change the route by dispatching navigation; the URL is an input, the route is a sub.

```clojure
(rf/dispatch [:rf.route/navigate :article {:id "abc"}])
```

Related: [Routing](concepts/routing.md).

### **project (egress)**

Run a value through redaction before it leaves the app, via `project-egress`; direct reads are not auto-projected.

```clojure
(rf/project-egress value {:frame :app/main :path [:auth]})  ;; redacts at the sink
```

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **register**

Name your handlers and machinery at boot time with [registration](#registration) forms.

```clojure
(rf/reg-event :cart/clear (fn [{:keys [db]} _] {:db (dissoc db :cart)}))
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md). There is **one** `reg-event`: `reg-event-db`/`-fx`/`-ctx` are gone.

### **subscribe / derive**

Read derived state by name through a subscription; `@(subscribe …)` both reads and subscribes.

```clojure
@(rf/subscribe [:cart/count])
```

Related: [Subscriptions](concepts/subscriptions.md).

## The Concepts

### **Effects are data**

A handler returns a *description* of side-effects and the runtime performs them; a pure handler plus data effects is what makes replay, test, and trace possible.

```clojure
{:fx [[:http {:url "/api/login"}] [:dispatch [:ui/spinner true]]]}
```

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **Fail loud, not silent**

A recognised input that can't be honoured raises a structured `:rf.error/*`, never a nil or no-op. Keep **fail-loud** (raise instead of swallow) distinct from **fail-closed** (deny by default at a boundary).

```clojure
;; unregistered id, missing cofx, unknown fx → raises :rf.error/*, never returns nil
```

Related: [Errors](concepts/errors.md).

### **Frame identity is carried, not found**

An operation reads its frame from its scope (provider / running handler / captured handle); the runtime never invents one. A rootless call is `:rf.error/no-frame-context`.

```clojure
[rf/frame-provider-existing {:frame :app} [app-root]]   ;; scope carries the frame downward
```

Related: [Frames](concepts/frames.md).

### **The four homes (where state lives)**

Subscription → flow → resource → machine: pick the cheapest that fits, decided by the where-state-lives router. Every other concept defers here for "which one do I use?".

```clojure
;; cart total → sub (or flow); the article → resource; checkout → machine
```

Related: [Where state lives](where-state-lives.md).

### **The two partitions**

A frame holds **app-db** (yours) and **runtime-db** (the framework's), addressed by the projection paths `:rf.db/app` and `:rf.db/runtime`; runtime subsystems live under `:rf.runtime/*`.

```clojure
[:rf.db/runtime :rf.runtime/resources]   ;; the resource cache IS a runtime-db subsystem
```

Related: [app-db](concepts/app-db.md).

### **The uniform reply**

Every managed async surface (HTTP, resources, mutations, route loaders, machine async) completes by *dispatching an event carrying a reply map*, never by an awaited value. The HTTP envelope's discriminator is `:kind` (`:success`/`:failure`); the resource view-model's `:status` (`:ok/:partial/:error/:cancelled/:stale`) is a different field, with `:kind :success` ≡ `:status :ok`.

```clojure
[:auth/login-reply {:kind :success :value {:token "…"}}]
```

Related: [Managed HTTP](concepts/http.md).

### **Xray**

The dev inspector: an in-app panel that reads the trace stream and per-frame epoch history so you debug the loop, not the DOM.

```clojure
;; open Xray to step through epochs, inspect app-db, and read the cascade
```

Related: [Debug with Xray](how-to/debug-with-xray.md).
