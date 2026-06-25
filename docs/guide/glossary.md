# Glossary

A catalog of re-frame2's nouns, verbs, and concepts.

## The Nouns

### **adapter**

re-frame2 renders through one of several React-family rendering libraries — Reagent, UIx, or Helix (its [substrate](#substrate)). An **adapter** is the small map of "glue" functions that wires re-frame2's core to the substrate you picked. It's a *value*, not the library itself.

You install it once, at boot, by passing it to `init!`:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

To switch substrate you change that one line — require `re-frame.adapter.uix` or `re-frame.adapter.helix` and pass *its* `adapter`. Nothing else moves: your events, subscriptions, and app-db are substrate-agnostic.

Related: [Views](concepts/views.md), [Adapters](../api/14-adapters.md). Don't confuse it with the [substrate](#substrate) — the substrate is the rendering library; the adapter is the value that binds re-frame2 to it.

### **app-db**

The single, immutable map that holds your application's state — one per [frame](#frame), and the one source of truth your code owns. 

You design the map's shape to fit your app, and can optionally guard it with a schema (Malli by default). You never mutate it directly: an [event handler](#event-handler) returns a *new* app-db, the framework commits it atomically, and your [subscriptions](#subscription) re-derive the [views](#view) from the new value. State flows **in** through events and **out** through subscriptions — never the reverse.

An example shape:
```clojure
{:cart  {:items [{:sku "BK-1" :qty 2}]
         :open? false}
 :user  {:name "Ada"}
 :route {:page :checkout}}
```

Note: The framework keeps *its* own state separately in [runtime-db](#runtime-db) within a [frame](#frame); see [the two partitions](#the-two-partitions).

Related: [app-db](concepts/app-db.md), [Validate with schemas](how-to/validate-with-schemas.md).

### **coeffect**

A fact about the world — the current time, a fresh UUID, a value from local storage — that the framework supplies to an [event handler](#event-handler) as data, so the handler stays a pure function and never reaches out to the world itself. A handler is a pure function from coeffects to an [effect map](#effect-map): the world flows *in* as coeffects and *out* as [effects](#effect).

A handler's first argument is its coeffects map. It always carries `:db` (the current value of [app-db](#app-db)). Any *other* fact must be declared up front in the event's metadata under `:rf.cofx/requires`, and the framework supplies it:

```clojure
(rf/reg-event :order/place
  {:rf.cofx/requires [:today]}           ;; :today is a required coeffect
  (fn [{:keys [db today]} _]             ;; the key :today will appear in the coeffects map
    {:db (assoc db :order/date today)}))
```

You make a coeffect available by registering a supplier for it with [`reg-cofx`](../api/01-core.md#reg-cofx):

```clojure
(rf/reg-cofx :today
  (fn []
    (subs (.toISOString (js/Date.)) 0 10)))
```

Declaring the world this way — rather than reading it inside the handler — is what makes events pure, testable, and replayable.

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **effect**

A single side effect, described as data for the framework to perform — an HTTP request, a navigation, a delayed dispatch, a write to local storage. An effect is a `[effect-id config]` pair, and effects ride in the `:fx` vector of an [effect map](#effect-map):

```clojure
[:dispatch-later {:ms 500 :event [:cart/saved]}]   ;; one effect
```

The [event handler](#event-handler) only *describes* the effect; the runtime performs it, via the [effect handler](#effect-handler) you registered for that `effect-id` with [`reg-fx`](../api/01-core.md#reg-fx). Effects are the output side of an event — the dual of its input [coeffects](#coeffect) — and keeping them as data, rather than doing them inline, is what makes events pure and testable.

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **effect handler**

The function — registered with `reg-fx` for a given `effect-id` — that actually *performs* an [effect](#effect). The runtime calls it once for each effect in a handler's `:fx` vector, passing that effect's config. This is where the real, impure work happens (the HTTP call, the navigation, the `localStorage` write) — which is exactly why it's kept *out* of the pure [event handler](#event-handler): the event handler describes effects as data; the effect handler carries them out.

```clojure
(rf/reg-fx :cart/save          ;; teach the runtime a new effect
  (fn [{:keys [items]}]        ;; the handler receives the effect's config
    (save-to-server! items)))  ;; and does the impure work
```

re-frame2 ships handlers for the common effects (`:dispatch`, `:dispatch-later`, `:rf.http/managed`, …); you register your own with `reg-fx`.

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **effect map**

The map an [event handler](#event-handler) — or a [machine](#machine) action — returns to describe how the world should change. The handler never makes the change itself; it's a pure function that returns this *description*, and the framework carries it out.

It has two reserved keys. `:db` is the new [app-db](#app-db) value — "replace app-db with this":

```clojure
{:db new-db}
```

`:fx` is a vector of [effects](#effect) — each a `[effect-id config]` pair describing one side effect to perform (an HTTP call, a delayed dispatch, navigation, local storage, …):

```clojure
{:db new-db
 :fx [[:http {:url "/api/cart" :method :post}]
      [:dispatch-later {:ms 500 :event [:cart/saved]}]]}
```

`:db` and `:fx` are the only top-level keys application code may return; an unknown key fails loud. Each effect is performed by an [effect handler](#effect-handler); register your own with [`reg-fx`](../api/01-core.md#reg-fx).

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md). The `:fx` key is why effects are often just called "fx".

### **epoch**

The record one [event cascade](#event-cascade) leaves behind — its trigger event, the before/after [app-db](#app-db), and the cascade's trace events. The epoch is re-frame2's **unit of time-travel**: [Xray](#xray) rewinds, replays, and inspects history one epoch at a time.

```clojure
;; one dispatch = one cascade = one epoch (the record)
```

Epochs live on the dev-only observability surface — they're [elided](#elide) from production builds.

Related: [Observability](concepts/observability.md).

### **error record / `:rf.error/*` category**

A failure the framework surfaces as a structured map rather than a thrown, silent, or swallowed error — it [fails loud](#fail-loud-not-silent), but as *data*. Every record is keyed by a reserved **`:rf.error/*` category** drawn from a fixed catalogue; **branch on the category**, never on the human-readable `:reason` (which is prose for people and can change).

```clojure
{:rf.error/category :rf.error/no-frame-context
 :reason "no frame in scope"}
```

Error records fan out to your always-on error listeners (Sentry, Datadog, …), so — unlike the dev-only trace surface — they **survive production**. Build your monitoring on them.

Related: [Errors](concepts/errors.md).

### **event**

An inert data vector recording a fact: *something happened*. You [dispatch](#dispatch) an event; a registered [event handler](#event-handler) decides what to do in response — the event itself does nothing.

Most events are user intent — a click, a drag, a route change, a tab switch — but timers, browser APIs, WebSockets, and route loaders raise them too. The first element is an identifier (a namespaced keyword is the norm), optionally carrying facts:

```clojure
[:event-id & facts]
```

Prefer a single map payload over positional arguments — it's self-describing and stays stable as the event grows:

```clojure
[:cart/add-item {:sku "BK-1" :qty 1}]    ;; good — a named payload
[:cart/add-item "BK-1" 1]                ;; placeful and fragile
```

Because an event is just data, it can be logged, recorded, and replayed — the basis of re-frame2's testability and time-travel.

Related: [Events & the cascade](concepts/events-and-the-cascade.md).

### **event cascade**

The fixed, ordered run one dispatched [event](#event) sets off — re-frame2's name for a single turn of the loop. The [event handler](#event-handler) runs and returns an [effect map](#effect-map); the new [app-db](#app-db) is committed atomically; effects fire; [subscriptions](#subscription) re-derive; and [views](#view) render from the new state.

```clojure
;; dispatch → handler → effect map → commit → effects → derivations → render
```

The view renders once, at the end, from the committed state — never mid-cascade.

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

A **pure** function that computes how a dispatched [event](#event) should change the world. It takes two arguments — a map of [coeffects](#coeffect) (the facts it needs, `:db` among them) and the event vector — and returns an [effect map](#effect-map):

```text
(coeffects, event-vector) -> effect-map
```

It *describes* changes, it doesn't perform them: typically a `:db` value ("replace app-db with this") plus any `:fx` for other side effects. Because it's pure — no IO, no clock, no reading subscriptions; the world arrives only through declared coeffects — it's trivially testable and replayable.

Register one with `reg-event`:

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md).

### **flow**

A pure derivation that re-frame2 keeps materialised at a path in [app-db](#app-db). The point: because the derived value lives *in* app-db, your [event handlers](#event-handler) can read it as plain state — whereas a [subscription](#subscription)'s value is only available to [views](#view).

You declare the `:inputs` to watch, a pure `:derive` function, and the `:output-path` to maintain; whenever an input changes, the framework re-runs `:derive` and writes the result, in step with the [event cascade](#event-cascade):

```clojure
(rf/reg-flow
  {:id :cart/total :inputs [[:cart :items]]
   :derive (fn [items] (reduce + (map :price items)))
   :output-path [:cart :total]})
```

Reach for a flow to collate or collapse many facts into one — e.g. folding several error flags into a single `:any-errors?`. Inputs may also read framework state (a path under `:rf.db/runtime`), and flows can be [added and removed dynamically](../api/05-flows.md#runtime-registration-via-fx) via effects.

Related: [Flows](concepts/flows.md), [toggling a derivation at runtime](concepts/flows.md#toggling-a-derivation-at-runtime).

### **frame**

An isolated, running instance of an app. A frame owns the state and runtime machinery for that instance — its [app-db](#app-db), [runtime-db](#runtime-db), event queue, subscription cache, and lifecycle.

A frame supplies *state*; its *behaviour* comes from an [image](#image). Crucially, **a frame isolates state, not registrations** — the registry is process-global, so the *same* event handlers, subscriptions, views, effects, flows, and machines run in every frame, each against that frame's own state. Most frames use the default image (all registrations); advanced setups hand a frame a *selected* one.

Most apps create one frame at boot and then forget about it. But because frames are independent, you can run several app instances on one page: short-lived frames power tests, stories, and per-request server rendering, and a sidecar tool like [Xray](#xray) is just a separate app in its own frame. A frame's [identity is carried, not found](#frame-identity-is-carried-not-found) — each operation reads its frame from scope.

```clojure
(rf/reg-frame :app
  {:initial-events [[:app/initialise]]
   :images [image1 image2]})    ;; optional: a selected composition of registrations
```

Related: [Frames](concepts/frames.md).

### **image**

The selected set of registrations a [frame](#frame) resolves its behaviour against — [event handlers](#event-handler), [subscriptions](#subscription), [views](#view), [effect handlers](#effect-handler), [flows](#flow), [machines](#machine), and the rest. An image is a *value*: it's not a running app and holds no state.

Most apps use the **default image** automatically — "all the registrations already loaded." You name an image explicitly only when different frames need different behaviour: a test frame with fake effects, two examples that reuse the same event ids, or a sidecar tool like [Xray](#xray).

The rule that ties it to a frame: **the image supplies behaviour; the [frame](#frame) supplies state.** When a frame starts, its image is resolved into a sealed registration set (its *generation*) — but most of the time that rule is all you need.

```clojure
(def checkout-image
  (rf/image {:select-ns {:include ["app.checkout.*"]}}))

(rf/reg-frame :checkout/story
  {:images [checkout-image]})
```

Related: [Images](concepts/images.md).

### **interceptor**

A named, reusable wrapper around an [event handler](#event-handler) — a pair of `:before` / `:after` functions for cross-cutting concerns (logging, validation, tracing, undo, injecting effects). Each is a `context → context` function:

- `:before` runs before the handler and can read or adjust its [coeffects](#coeffect);
- `:after` runs after and can read or adjust the [effect map](#effect-map) it returned.

Interceptors are registered by id with `reg-interceptor` (never written inline), and an event opts into them by id:

```clojure
(rf/reg-interceptor :my-app/logger
  {:before (fn [ctx] ctx)
   :after  (fn [ctx] ctx)})

(rf/reg-event :cart/add
  {:interceptors [:my-app/logger]}    ;; opt in by id
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Related: [Interceptors](concepts/interceptors.md).

### **machine**

A statechart-capable state machine, registered as an [event handler](#event-handler) with `reg-machine`. It models a feature's lifecycle as explicit **states** and **transitions** — driven by dispatched [events](#event), with guards, actions, timeouts, and child machines — instead of a scatter of boolean flags in [app-db](#app-db).

Its live value is a [snapshot](#snapshot) (the current state plus its `:data`), held in [runtime-db](#runtime-db) and read like any other derived state.

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Related: [Machines](concepts/machines.md).

### **mutation**

A declared server-state **write** — the write-side partner to a [resource](#resource)'s read. Its cache consequences (which cached reads it [invalidates](#invalidate)) are declared *once, on the registration*, never imperatively at the call site.

```clojure
(rf/reg-mutation :article/favorite {:scope :rf.scope/global} request-fn)
```

Related: [Server state](concepts/server-state.md). The reply field is `:value`; the instance sub field is `:result`.

### **resource**

A declared, cached server-state **read** (the read-side partner to a [mutation](#mutation)'s write), registered with `reg-resource`. Its `:scope` is a required, fail-closed leak boundary — part of the read's identity (with its `:params`) — so one user's data can't surface in another's cache.

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
- `reg-fx` registers an [effect handler](#effect-handler).
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

The framework-owned half of a [frame](#frame)'s state — the other side of [the two partitions](#the-two-partitions), sitting beside the [app-db](#app-db) you own.

re-frame2 keeps its own durable state here: machine [snapshots](#snapshot), the current route, [resource](#resource) caches, [mutation](#mutation) status, and similar machinery. App code reads it through subscriptions or accessors — never by editing `:rf.db/runtime` paths directly.

```clojure
[:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow]
```

Related: [app-db](#app-db), [frame](#frame). Paths: `:rf.db/runtime`, children `:rf.runtime/*`.

### **snapshot**

A [machine](#machine)'s live value at any moment — which state it's in, plus its `:data`. It lives in [runtime-db](#runtime-db), and you read it through a [subscription](#subscription) addressed by the machine's id.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

Related: [Machines](concepts/machines.md).

### **subscription**

A named, registered, pure, **cached** derivation of state — how a [view](#view) reads what it needs. You declare it with `reg-sub`; the framework recomputes it only when its inputs actually change (by `=`), so a view never re-renders for a value that didn't move. Subscriptions compose in layers: some read [app-db](#app-db) directly, others combine other subscriptions into a derivation graph.

```clojure
(rf/reg-sub :cart/count (fn [db _] (count (:items (:cart db)))))
```

Related: [Subscriptions](concepts/subscriptions.md). Casual "sub" is fine; not as a headword. (Need the value inside an [event handler](#event-handler)? Materialise it with a [flow](#flow).)

### **substrate**

The React-family rendering layer your app runs on — Reagent, UIx, Helix, or reagent-slim. You pick one and wire re-frame2 to it with an [adapter](#adapter). Because the core is substrate-agnostic, your events, subscriptions, and app-db are identical whichever you choose — only the rendering differs.

```clojure
;; substrate = the rendering library; the adapter is the value you pass to init!
```

Related: [Use UIx, Helix, or slim](how-to/use-uix-helix-or-slim.md).

### **view**

A pure render function from [subscription](#subscription) values to **hiccup** — the Clojure data that describes your UI. Views read derived state and [dispatch](#dispatch) [events](#event) on interaction; they hold no business logic. The [substrate](#substrate) turns the hiccup into real React elements.

```clojure
(rf/reg-view :cart-badge (fn [] [:span.badge @(rf/subscribe [:cart/count])]))
```

Related: [Views](concepts/views.md). Use "component" for React-analogy callouts only.

## The Verbs

### **commit**

The single, deferred, all-or-nothing write of the new [app-db](#app-db) at the end of an [event](#event) — no observer ever sees a half-written app-db. The `:db` your [event handler](#event-handler) returns is *staged* and lands once, atomically, after flows run, so a throwing handler or flow installs *nothing*.

```clojure
;; the :db you return is staged; it's committed once, at the end, atomically
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md).

### **dispatch**

Enqueue an [event](#event) for a [frame](#frame) to process.

`dispatch` wraps the event in an [event envelope](#event-envelope) and returns immediately; the [event handler](#event-handler) runs later, during the [event cascade](#event-cascade). (Its synchronous sibling `dispatch-sync` runs the cascade *now* — mainly for tests and boot.)

```clojure
(rf/dispatch [:cart/add-item {:sku "BK-1"}]
  {:frame :checkout})
```

Related: [event](#event), [event envelope](#event-envelope), [event cascade](#event-cascade).

### **drain / run-to-completion**

The runtime drains the *whole* event queue to a fixed point — running every queued [event](#event) to completion — before [subscriptions](#subscription) recompute and [views](#view) render. So the UI updates once, from a settled state, never mid-flight.

```clojure
;; all queued events run to completion, THEN subs recompute, THEN views render
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md). Hyphenate **run-to-completion** consistently.

### **elide**

Compile dev-only code out of production via one flag (`goog.DEBUG` or `-Dre-frame.debug`). It removes the dev trace surface, the [epoch](#epoch) buffer, and schema *checks* — but the always-on `:errors` and `:events` streams survive, so production observability keeps working.

```clojure
;; goog.DEBUG=false removes the dev trace surface and schema checks
```

Related: [Observability](concepts/observability.md). Name DCE once, then use **elide**.

### **invalidate**

A [mutation](#mutation) declares — as data on its registration, never imperatively — which cached [resource](#resource) reads it makes stale, so they refetch.

```clojure
{:invalidates (fn [{:keys [slug]} _result]
                {:tags #{[:article slug]}})}     ;; declared once, on the mutation
```

Related: [Server state](concepts/server-state.md).

### **navigate**

Change the route by dispatching navigation — the URL is an input, the route a [subscription](#subscription) you read like any other.

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

Name your handlers and machinery at boot time with [registration](#registration) forms — `reg-event` for an [event handler](#event-handler), `reg-sub` for a [subscription](#subscription), and so on.

```clojure
(rf/reg-event :cart/clear (fn [{:keys [db]} _] {:db (dissoc db :cart)}))
```

Related: [Events & the cascade](concepts/events-and-the-cascade.md). There is **one** `reg-event`: `reg-event-db`/`-fx`/`-ctx` are gone.

### **subscribe / derive**

Read derived state by name through a [subscription](#subscription); `@(subscribe …)` both reads the current value *and* subscribes, so the [view](#view) re-renders when it changes.

```clojure
@(rf/subscribe [:cart/count])
```

Related: [Subscriptions](concepts/subscriptions.md).

## The Concepts

### **Effects are data**

An [event handler](#event-handler) returns a *description* of side-effects — an [effect map](#effect-map) of data — and the runtime performs them; a pure handler plus data effects is what makes replay, test, and trace possible.

```clojure
{:fx [[:http {:url "/api/login"}] [:dispatch [:ui/spinner true]]]}
```

Related: [Effects & Coeffects](concepts/effects-and-coeffects.md).

### **Fail loud, not silent**

A recognised input that can't be honoured raises a structured [error record](#error-record) (`:rf.error/*`), never a nil or no-op. Keep **fail-loud** (raise instead of swallow) distinct from **fail-closed** (deny by default at a boundary).

```clojure
;; unregistered id, missing cofx, unknown fx → raises :rf.error/*, never returns nil
```

Related: [Errors](concepts/errors.md).

### **Frame identity is carried, not found**

An operation reads its [frame](#frame) from its scope (provider / running handler / captured handle); the runtime never invents one. A rootless call is `:rf.error/no-frame-context`.

```clojure
[rf/frame-provider-existing {:frame :app} [app-root]]   ;; scope carries the frame downward
```

Related: [Frames](concepts/frames.md).

### **The four homes (where state lives)**

[Subscription](#subscription) → [flow](#flow) → [resource](#resource) → [machine](#machine): pick the cheapest that fits, decided by the where-state-lives router. Every other concept defers here for "which one do I use?".

```clojure
;; cart total → sub (or flow); the article → resource; checkout → machine
```

Related: [Where state lives](where-state-lives.md).

### **The two partitions**

A [frame](#frame) holds [app-db](#app-db) (yours) and [runtime-db](#runtime-db) (the framework's), addressed by the projection paths `:rf.db/app` and `:rf.db/runtime`; runtime subsystems live under `:rf.runtime/*`.

```clojure
[:rf.db/runtime :rf.runtime/resources]   ;; the resource cache IS a runtime-db subsystem
```

Related: [app-db](concepts/app-db.md).

### **The uniform reply**

Every managed async surface (HTTP, resources, mutations, route loaders, machine async) completes by [dispatching](#dispatch) an [event](#event) carrying a reply map, never by an awaited value. The HTTP envelope's discriminator is `:kind` (`:success`/`:failure`); the resource view-model's `:status` (`:ok/:partial/:error/:cancelled/:stale`) is a different field, with `:kind :success` ≡ `:status :ok`.

```clojure
[:auth/login-reply {:kind :success :value {:token "…"}}]
```

Related: [Managed HTTP](concepts/http.md).

### **Xray**

The dev inspector: an in-app panel that reads the trace stream and per-frame [epoch](#epoch) history so you debug the loop, not the DOM.

```clojure
;; open Xray to step through epochs, inspect app-db, and read the cascade
```

Related: [Debug with Xray](how-to/debug-with-xray.md).
