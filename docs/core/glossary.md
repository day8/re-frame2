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

Related: [Views](views.md), [Adapters](../api/re-frame.adapter.reagent.md). Don't confuse it with the [substrate](#substrate) — the substrate is the rendering library; the adapter is the value that binds re-frame2 to it.

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

Related: [app-db](app-db.md), [Validate with schemas](how-to/validate-with-schemas.md).

### **path**

A vector of keys addressing one location inside [app-db](#app-db), with `get-in` / `assoc-in` semantics — `[:auth :token]` names the `:token` entry inside the `:auth` map. Paths are the framework-wide addressing vocabulary: an app-db [schema](#schema) binds to a path, a [data-classification](#data-classification) mark names one, a [flow](#flow) writes its `:output-path`, and the `path` interceptor narrows a handler to one.

Related: [app-db](app-db.md).

### **coeffect**

A fact about the world — the current time, a fresh UUID, a value from local storage — that the framework supplies to an [event handler](#event-handler) as data, so the handler stays a pure function and never reaches out to the world itself. A handler is a pure function from coeffects to an [effect map](#effect-map): the world flows *in* as coeffects and *out* as [effects](#effect).

A handler's first argument is its coeffects map. It always carries `:db` (the current value of [app-db](#app-db)). Any *other* fact must be declared up front in the event's metadata under `:rf.cofx/requires`, and the framework supplies it:

```clojure
(rf/reg-event :order/place
  {:rf.cofx/requires [:today]}           ;; :today is a required coeffect
  (fn [{:keys [db today]} _]             ;; the key :today will appear in the coeffects map
    {:db (assoc db :order/date today)}))
```

You make a coeffect available by registering a supplier for it with [`reg-cofx`](../api/re-frame.core.md#reg-cofx):

```clojure
(rf/reg-cofx :today
  (fn []
    (subs (.toISOString (js/Date.)) 0 10)))
```

Declaring the world this way — rather than reading it inside the handler — is what makes events pure, testable, and replayable.

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect**

A single side effect, described as data for the framework to perform — an HTTP request, a navigation, a delayed dispatch, a write to local storage. An effect is a `[effect-id config]` pair, and effects ride in the `:fx` vector of an [effect map](#effect-map):

```clojure
[:dispatch-later {:ms 500 :event [:cart/saved]}]   ;; one effect
```

The [event handler](#event-handler) only *describes* the effect; the runtime performs it, via the [effect handler](#effect-handler) you registered for that `effect-id` with [`reg-fx`](../api/re-frame.core.md#reg-fx). Effects are the output side of an event — the dual of its input [coeffects](#coeffect) — and keeping them as data, rather than doing them inline, is what makes events pure and testable.

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect handler**

The function — registered with `reg-fx` for a given `effect-id` — that actually *performs* an [effect](#effect). The runtime calls it once for each effect in a handler's `:fx` vector, passing that effect's config. This is where the real, impure work happens (the HTTP call, the navigation, the `localStorage` write) — which is exactly why it's kept *out* of the pure [event handler](#event-handler): the event handler describes effects as data; the effect handler carries them out.

```clojure
(rf/reg-fx :cart/save             ;; teach the runtime a new effect
  (fn [_ctx {:keys [items]}]      ;; (fn [ctx args]) — args is the effect's config
    (save-to-server! items)))     ;; and does the impure work
```

re-frame2 ships handlers for the common effects (`:dispatch`, `:dispatch-later`, `:rf.http/managed`, …); you register your own with `reg-fx`.

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect map**

The map an [event handler](#event-handler) — or a [machine](../machines/glossary.md#machine) action — returns to describe how the world should change. The handler never makes the change itself; it's a pure function that returns this *description*, and the framework carries it out.

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

`:db` and `:fx` are the only top-level keys application code may return; an unknown key fails loud. Each effect is performed by an [effect handler](#effect-handler); register your own with [`reg-fx`](../api/re-frame.core.md#reg-fx).

Related: [Effects](effects.md), [Coeffects](coeffects.md). The `:fx` key is why effects are often just called "fx".

### **error record**

A failure the framework surfaces as a structured map rather than a thrown, silent, or swallowed error — it [fails loud](#fail-loud-not-silent), but as *data*. Every record is keyed by a reserved **`:rf.error/*` category** drawn from a fixed catalogue: traced/listener records carry it under `:operation`; the few construction-time errors that *throw* carry it as `:rf.error/id` in the exception's `ex-data`. Either way, **branch on the category**, never on the human-readable `:reason` (which is prose for people and can change).

```clojure
{:operation :rf.error/no-frame-context
 :reason    "no frame in scope"}
```

Error records fan out to your always-on error listeners (Sentry, Datadog, …), so — unlike the dev-only trace surface — they **survive production**. Build your monitoring on them.

Related: [Errors](errors.md).

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

Related: [Introduction](introduction.md).

<a id="event-cascade"></a>
### **event pipeline**

The fixed stage sequence one dispatched [event](#event) traverses. It has a [**write side**](#write-side) and a [**read side**](#read-side), split at the [**commit**](#commit) — nothing crosses between them except the committed value. The write side runs per event — [assemble](#assemble) → [transform](#transform) → [commit](#commit) → [perform](#perform); the read side runs once per [drain](#drain--run-to-completion) at settle — [derive](#derive) → [render](#render).

```clojure
;; write side (per event):  assemble → transform → commit → perform
;; read side  (per drain):  derive → render
```

The commit is the seam: the write side is transactional up to it and best-effort after it, and the [view](#view) renders once, from the committed state — never mid-run.

One traversal of the pipeline is a [**run**](#run); the record a run leaves behind is an [**epoch**](#epoch). Read the triple as **pipeline** (the structure) / **run** (one traversal) / **epoch** (the record). The to-fixed-point family — running the whole queue before the read side — is a [**drain**](#drain--run-to-completion).

Related: [Introduction](introduction.md). (Older prose called this the *event cascade* or a *turn of the loop*; those spellings are retired for the event-traversal sense — the machines *cancellation cascade* keeps its name.)

### **run**

One traversal of the [event pipeline](#event-pipeline) — a single dispatched [event](#event) carried through every stage, write side then read side. It's the middle term of the triple: the [**pipeline**](#event-pipeline) is the fixed structure, a **run** is one trip through it, and the [**epoch**](#epoch) is the record that trip leaves. One dispatch = one run = one epoch. (A whole queue run to a fixed point before the read side is a [drain](#drain--run-to-completion), which is many runs but one read side.)

Related: [Introduction](introduction.md).

### **write side**

The first half of the [event pipeline](#event-pipeline) — [assemble](#assemble) → [transform](#transform) → [commit](#commit) → [perform](#perform) — the part that runs *once per [event](#event)* and computes and applies the change. It's transactional up to the [commit](#commit) (a throwing handler installs nothing) and best-effort after it. The [commit](#commit) is the seam that ends it; nothing crosses to the [read side](#read-side) except the value the commit lands.

Related: [Introduction](introduction.md).

### **read side**

The second half of the [event pipeline](#event-pipeline) — [derive](#derive) → [render](#render) — the part that runs *once per [drain](#drain--run-to-completion)*, after the queue settles, and brings the screen up to date. It reads only the value the [commit](#commit) landed; it never sees a half-written [app-db](#app-db), and it runs once no matter how many events the drain settled.

Related: [Subscriptions](subscriptions.md), [Introduction](introduction.md).

### **world**

The map of declared facts assembled for an [event handler](#event-handler) to read — everything from the outside the handler is allowed to see, gathered as data. [app-db](#app-db) is one entry (always present, under `:db`), not otherwise special; the clock, a fresh id, a storage read are other entries a handler declares with `:rf.cofx/requires`. The world is the handler's first argument — its [coeffects](#coeffect) map — assembled by the pipeline's [assemble](#assemble) stage before the handler runs.

```clojure
;; the assembled world a handler receives — app-db is just one fact in it
{:db {…}  :today "2026-07-04"  :new-id #uuid "…"}
```

A [**frame**](#frame) is a *running* world: a world plus the runtime machinery (queue, caches, lifecycle) that keeps it alive and re-assembles it per event.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [frame](#frame).

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

Related: [Introduction](introduction.md), [Frames](frames.md), [Effects](effects.md), [Coeffects](coeffects.md).

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

Related: [Introduction](introduction.md).

### **flow**

A pure derivation that re-frame2 keeps materialised at a path in [app-db](#app-db). The point: because the derived value lives *in* app-db, your [event handlers](#event-handler) can read it as plain state — whereas a [subscription](#subscription)'s value is only available to [views](#view).

You declare the `:inputs` to watch, a pure `:derive` function, and the `:output-path` to maintain; whenever an input changes, the framework re-runs `:derive` and writes the result, in step with the [event pipeline](#event-pipeline):

```clojure
(rf/reg-flow
  {:id :cart/total :inputs [[:cart :items]]
   :derive (fn [items] (reduce + (map :price items)))
   :output-path [:cart :total]})
```

Reach for a flow to collate or collapse many facts into one — e.g. folding several error flags into a single `:any-errors?`. Inputs may also read framework state (a path under `:rf.db/runtime`), and flows can be [added and removed dynamically](../api/re-frame.flows.md) via effects.

Related: [Flows](flows.md), [toggling a derivation at runtime](flows.md#toggling-a-derivation-at-runtime).

### **frame**

An isolated, running instance of an app. **A frame is a running [world](#world)** — a world (the map of declared facts, [app-db](#app-db) among them) plus the runtime machinery that keeps it alive: its [runtime-db](#runtime-db), event queue, subscription cache, and lifecycle.

A frame supplies *state*; its *behaviour* comes from an [image](#image). Crucially, **a frame isolates state, not registrations** — the registry is process-global, so the *same* event handlers, subscriptions, views, effects, flows, and machines run in every frame, each against that frame's own state. Most frames use the default image (all registrations); advanced setups hand a frame a *selected* one.

Most apps create one frame at boot and then forget about it. But because frames are independent, you can run several app instances on one page: short-lived frames power tests, stories, and per-request server rendering, and a sidecar tool like [Xray](#xray) is just a separate app in its own frame. A frame's [identity is carried, not found](#frame-identity-is-carried-not-found) — each operation reads its frame from scope.

```clojure
(rf/reg-frame :app
  {:initial-events [[:app/initialise]]
   :images [image1 image2]})    ;; optional: a selected composition of registrations
```

Related: [Frames](frames.md).

### **capture-frame**

`(rf/capture-frame)` captures a [frame](#frame) as a *value* — a **frame api**: a small bundle of that frame's `:dispatch` / `:dispatch-sync` / `:subscribe` ops, plus the captured `:frame` id. Carry it across an async boundary — grab one while the frame is in scope, and a later `setTimeout`, promise, or WebSocket callback can still dispatch into the right frame instead of raising `:rf.error/no-frame-context`. (`capture-frame` is the verb; the *frame api* is the value it returns — spelled lowercase so it never reads as the public re-frame2 API.)

Related: [Frames](frames.md).

### **frame-provider**

The React component that scopes a [frame](#frame) to a view subtree, so `dispatch`/`subscribe` inside resolve to it. One component, two config shapes chosen by the prop map: `{:frame existing-id}` *scopes* an already-created frame's id down (creating and destroying nothing; fails loud if the frame is absent), while `{:id …}` *ensures* a named frame — creating it if absent, reusing it without re-seeding if present, with no destroy-on-unmount. (The everyday expression of [frame identity is carried, not found](#frame-identity-is-carried-not-found).)

Related: [Frames](frames.md).

### **hiccup**

The plain Clojure data that describes your UI: nested vectors where `[:div.card {:on-click f} "Hi"]` is a `<div>`. Because markup is *data*, not a template language, a [view](#view) composes and diffs cheaply and even renders to a string on the server — and the [substrate](#substrate) turns it into real React elements.

```clojure
[:ul.cart (for [item items] [:li {:key (:sku item)} (:name item)])]
```

Related: [Views](views.md).

### **image**

The selected set of registrations a [frame](#frame) resolves its behaviour against — [event handlers](#event-handler), [subscriptions](#subscription), [views](#view), [effect handlers](#effect-handler), [flows](#flow), [machines](../machines/glossary.md#machine), and the rest. An image is a *value*: it's not a running app and holds no state.

Most apps use the **default image** automatically — "all the registrations already loaded." You name an image explicitly only when different frames need different behaviour: a test frame with fake effects, two examples that reuse the same event ids, or a sidecar tool like [Xray](#xray).

The rule that ties it to a frame: **the image supplies behaviour; the [frame](#frame) supplies state.** When a frame starts, its image is resolved into a sealed registration set (its *generation*) — but most of the time that rule is all you need.

```clojure
(def checkout-image
  (rf/image {:select-ns {:include ["app.checkout.*"]}}))

(rf/reg-frame :checkout/story
  {:images [checkout-image]})
```

Related: [Images](images.md).

### **generation**

The sealed registration set a [frame](#frame)'s [image](#image) selection resolves into when the frame is constructed — the concrete "which handler answers this id" table the frame runs against, frozen as a value. Every `make-frame` frame carries one (the default image seals into a generation too); `reload-images!` swaps a frame onto a freshly resolved generation.

Related: [Images](images.md).

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

Related: [Interceptors](interceptors.md).

### **query vector**

The vector you hand `subscribe` to read a [subscription](#subscription): an id plus any arguments — `[:article/page "abc"]`. The id picks the subscription; the rest are its arguments, and the whole vector keys the subscription cache, so two equal query vectors share one cached value.

```clojure
@(rf/subscribe [:cart/count])             ;; id only
@(rf/subscribe [:article/by-id "BK-1"])   ;; id + argument
```

Related: [Subscriptions](subscriptions.md).

### **registrar**

The single, process-wide table every `reg-*` form writes to and every lookup reads from, keyed by kind + id. It holds *all* your [registrations](#registration) — events, subs, effects, views, machines — and **every [frame](#frame) shares the one registrar** rather than keeping its own copy. That's the mechanism behind "a frame isolates state, not behaviour."

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

[Core](../api/re-frame.core.md) registration:

- `reg-event` registers an [event handler](#event-handler).
- `reg-sub` registers a [subscription](#subscription).
- `reg-fx` registers an [effect handler](#effect-handler).
- `reg-cofx` registers a [coeffect](#coeffect) supplier.
- `reg-interceptor` registers an event-handler wrapper.
- `reg-frame` registers a named [frame](#frame).
- `reg-view` and `reg-view*` register [views](#view).

Flows registration:

- `reg-flow` registers a [flow](#flow).

[Machines](../machines/concepts.md) registration:

- `reg-machine` and `reg-machine*` register [machines](../machines/glossary.md#machine).

Routing registration:

- `reg-route` registers a route.

Schema registration:

- `reg-app-schema` and `reg-app-schemas` register Malli schemas for app-db paths.

SSR registration:

- `reg-head` registers an SSR head producer.
- `reg-error-projector` registers an SSR error projector.

HTTP registration:

- `reg-http-interceptor` registers managed-HTTP middleware.

[Resource](../api/re-frame.resources.md) registration:

- `reg-resource` registers a [resource](../resources/glossary.md#resource).
- `reg-mutation` registers a [mutation](../resources/glossary.md#mutation).
- `reg-resource-scope` registers a named resource-scope resolver.


### **runtime-db**

The framework-owned half of a [frame](#frame)'s state — the other side of [the two partitions](#the-two-partitions), sitting beside the [app-db](#app-db) you own.

re-frame2 keeps its own durable state here: machine [snapshots](../machines/glossary.md#snapshot), the current route, [resource](../resources/glossary.md#resource) caches, [mutation](../resources/glossary.md#mutation) status, and similar machinery. App code reads it through subscriptions or accessors — never by editing `:rf.db/runtime` paths directly.

```clojure
[:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow]
```

Related: [app-db](#app-db), [frame](#frame). Paths: `:rf.db/runtime`, children `:rf.runtime/*`.

### **schema**

A data description of a value's shape — `[:map [:sku :string] [:qty :int]]` — written in **Malli**, the data-driven schema library re-frame2 uses by default. Attach one to an [app-db](#app-db) path (`reg-app-schema`), an event, or an HTTP `:decode` step; the runtime checks it at a named boundary in dev and [elides](#elide) the check in production. Because a schema is itself data, it validates, coerces, and round-trips through tools.

Related: [Validate with schemas](how-to/validate-with-schemas.md).

### **subscription**

A named, registered, pure, **cached** derivation of state — how a [view](#view) reads what it needs. You declare it with `reg-sub`; the framework recomputes it only when its inputs actually change (by `=`), so a view never re-renders for a value that didn't move. Subscriptions compose in layers: some read [app-db](#app-db) directly, others combine other subscriptions into a derivation graph.

```clojure
(rf/reg-sub :cart/count (fn [db _] (count (:items (:cart db)))))
```

Related: [Subscriptions](subscriptions.md). Casual "sub" is fine; not as a headword. (Need the value inside an [event handler](#event-handler)? Materialise it with a [flow](#flow).)

### **substrate**

The React-family rendering layer your app runs on — Reagent, UIx, Helix, or reagent-slim. You pick one and wire re-frame2 to it with an [adapter](#adapter). Because the core is substrate-agnostic, your events, subscriptions, and app-db are identical whichever you choose — only the rendering differs.

```clojure
;; substrate = the rendering library; the adapter is the value you pass to init!
```

Related: [Use UIx, Helix, or slim](how-to/use-uix-helix-or-slim.md).

### **view**

A pure render function from [subscription](#subscription) values to **hiccup** — the Clojure data that describes your UI. Views read derived state and [dispatch](#dispatch) [events](#event) on interaction; they hold no business logic. The [substrate](#substrate) turns the hiccup into real React elements.

```clojure
(rf/reg-view cart-badge []
  [:span.badge @(subscribe [:cart/count])])
```

Related: [Views](views.md). Use "component" for React-analogy callouts only.

## The Verbs

The six pipeline stages — [**assemble → transform → commit → perform**](#write-side) (the [write side](#write-side), per event) and [**derive → render**](#read-side) (the [read side](#read-side), per drain) — are the verbs of the [event pipeline](#event-pipeline); they lead this section, in pipeline order.

### **assemble**

The pipeline's first stage: gather the [**world**](#world) an [event handler](#event-handler) will read — [app-db](#app-db) (`:db`) plus every fact the event declared with `:rf.cofx/requires` — into one [coeffects](#coeffect) map, before the handler runs. This is where declared facts (the clock, a fresh id, a storage read) enter as data, so the handler stays pure.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [world](#world).

### **transform**

The pipeline's second stage: run the pure [event handler](#event-handler) — the assembled [world](#world) and the [event](#event) in, an [effect map](#effect-map) out. It *transforms* the world into a description of the change (`{:db … :fx …}`) and performs none of it; the stages after the [commit](#commit) carry that description out.

Related: [Introduction](introduction.md), [event handler](#event-handler).

### **commit**

The single, deferred, all-or-nothing write of the new [app-db](#app-db) — and the **seam** of the [event pipeline](#event-pipeline). It is the one point the committed value crosses from the [write side](#write-side) to the [read side](#read-side); nothing else crosses. Everything before it (assemble, transform) is transactional — the `:db` your [event handler](#event-handler) returns is *staged* and lands once, atomically, after flows run, so a throwing handler or flow installs *nothing* — and everything after it ([perform](#perform)) is best-effort. No observer ever sees a half-written app-db.

```clojure
;; the :db you return is staged; it's committed once, atomically — the write/read seam
```

Related: [Introduction](introduction.md).

### **perform**

The pipeline's fourth stage and the last of the [write side](#write-side): run the `:fx` rows the [transform](#transform) returned, in source order, after the [commit](#commit). This is the *only* place the system touches the world — the HTTP call, the navigation, the follow-up dispatch — carried out by the [effect handler](#effect-handler) registered for each id. Past the [commit](#commit) seam it's best-effort: an effect that throws doesn't un-commit the state.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [effect](#effect).

### **derive**

The pipeline's fifth stage and the first of the [read side](#read-side): recompute the [subscriptions](#subscription) (and the rest of [the derivation graph](#the-derivation-graph)) that watch the changed parts of the committed [app-db](#app-db). Values that come out equal (by `=`) to last time prune everything downstream. The read side runs once per [drain](#drain--run-to-completion), so derivation happens against settled state, never mid-run. (The public verb you write is [`subscribe`](#subscribe--derive); *derive* names the stage.)

Related: [Subscriptions](subscriptions.md).

### **render**

The pipeline's sixth and final stage: the [views](#view) that deref a *changed* [subscription](#subscription) re-run, producing fresh [hiccup](#hiccup), and the [substrate](#substrate) patches just the DOM that moved. Because it runs once per [drain](#drain--run-to-completion) from settled state, the screen never flickers through an intermediate value.

Related: [Views](views.md).

### **dispatch**

Enqueue an [event](#event) for a [frame](#frame) to process.

`dispatch` wraps the event in an [event envelope](#event-envelope) and returns immediately; the [event handler](#event-handler) runs later, during the [pipeline run](#event-pipeline) the event kicks off. (Its synchronous sibling `dispatch-sync` runs the pipeline *now* — mainly for tests and boot.)

```clojure
(rf/dispatch [:cart/add-item {:sku "BK-1"}]
  {:frame :checkout})
```

Related: [event](#event), [event envelope](#event-envelope), [event pipeline](#event-pipeline).

### **dispatch-sync**

Like [`dispatch`](#dispatch), but it runs the [event](#event) and drains the whole queue to completion *before returning*, instead of queuing for the next tick. The right call at boot, in tests, and at the REPL — never from inside a running handler (which raises `:rf.error/dispatch-sync-in-handler`).

```clojure
(rf/dispatch-sync [:app/initialise])   ;; app-db is committed before the next line
```

Related: [Introduction](introduction.md).

### **drain / run-to-completion**

The runtime drains the *whole* event queue to a fixed point — running the [write side](#write-side) of every queued [event](#event) to completion — before the [read side](#read-side) runs. So the write side runs *per event*, the read side runs *once per drain* at settle, and the UI updates once, from a settled state, never mid-flight. A drain is many [pipeline runs](#event-pipeline) sharing one read side.

```clojure
;; every queued event's write side runs, THEN — once — subs recompute and views render
```

Related: [Run to completion](run-to-completion.md). Hyphenate **run-to-completion** consistently.

### **elide**

Compile dev-only code out of production via one flag (`goog.DEBUG` or `-Dre-frame.debug`). It removes the dev trace surface, the [epoch](#epoch) buffer, and schema *checks* — but the always-on `:errors` and `:events` streams survive, so production observability keeps working.

```clojure
;; goog.DEBUG=false removes the dev trace surface and schema checks
```

Related: [Observability](observability.md). Name DCE once, then use **elide**.

### **init!**

The one-time boot call that installs a [substrate](#substrate) [adapter](#adapter) into the runtime — `(rf/init! reagent-adapter/adapter)`. Idempotent, called once at startup. It does *not* create a default [frame](#frame) (identity is carried, not found); you register your root frame explicitly.

Related: [Adapters](../api/re-frame.adapter.reagent.md).

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

Related: [Introduction](introduction.md). There is **one** `reg-event`: `reg-event-db`/`-fx`/`-ctx` are gone.

### **subscribe / derive**

Read derived state by name through a [subscription](#subscription); `@(subscribe …)` both reads the current value *and* subscribes, so the [view](#view) re-renders when it changes.

```clojure
@(rf/subscribe [:cart/count])
```

Related: [Subscriptions](subscriptions.md).

## The Concepts

### **Effects are data**

An [event handler](#event-handler) returns a *description* of side-effects — an [effect map](#effect-map) of data — and the runtime performs them; a pure handler plus data effects is what makes replay, test, and trace possible.

```clojure
{:fx [[:http {:url "/api/login"}] [:dispatch [:ui/spinner true]]]}
```

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **Fail loud, not silent**

A recognised input that can't be honoured raises a structured [error record](#error-record) (`:rf.error/*`), never a nil or no-op. Keep **fail-loud** (raise instead of swallow) distinct from **fail-closed** (deny by default at a boundary).

```clojure
;; unregistered id, missing cofx, unknown fx → raises :rf.error/*, never returns nil
```

Related: [Errors](errors.md).

### **Frame identity is carried, not found**

An operation reads its [frame](#frame) from its scope (provider / running handler / captured handle); the runtime never invents one. A rootless call is `:rf.error/no-frame-context`.

```clojure
[rf/frame-provider {:frame :app} [app-root]]   ;; scope carries the frame downward
```

Related: [Frames](frames.md).

### **The four homes (where state lives)**

[Subscription](#subscription) → [flow](#flow) → [resource](../resources/glossary.md#resource) → [machine](../machines/glossary.md#machine): pick the cheapest that fits, decided by the where-state-lives router. Every other concept defers here for "which one do I use?".

```clojure
;; cart total → sub (or flow); the article → resource; checkout → machine
```

Related: [Where state lives](where-state-lives.md).

### **The two partitions**

A [frame](#frame) holds [app-db](#app-db) (yours) and [runtime-db](#runtime-db) (the framework's), addressed by the projection paths `:rf.db/app` and `:rf.db/runtime`; runtime subsystems live under `:rf.runtime/*`.

```clojure
[:rf.db/runtime :rf.runtime/resources]   ;; the resource cache IS a runtime-db subsystem
```

Related: [app-db](app-db.md).

### **The uniform reply**

Every managed async surface (HTTP, resources, mutations, route loaders, machine async) completes by [dispatching](#dispatch) an [event](#event) carrying the one canonical reply map, never by an awaited value. Its discriminator is the closed `:status` field — `:ok` (value at `:value`), `:error` (failure at `:error`), `:cancelled`, or `:stale` — the *same* envelope on every surface, HTTP included. (Different from the resource *read sub's* `:status`, whose values are the five lifecycle states `:idle`/`:loading`/`:fetching`/`:loaded`/`:error`.)

```clojure
[:auth/login-reply {:status :ok :value {:token "…"}}]
```

Related: [Managed HTTP](../async/http.md).


### **The derivation graph**

The directed graph of pure derivations rooted at [app-db](#app-db), with [views](#view) at the leaves. [Subscriptions](#subscription), [flows](#flow), resource reads, route facts, and machine selectors are all nodes on this one graph; the runtime recomputes only along edges whose value actually changed (by `=`), so an unchanged input prunes everything downstream of it.

Related: [Subscriptions](subscriptions.md).

### **Data classification**

Marking an [app-db](#app-db) path (or a payload slot) `:sensitive` or `:large` so the framework swaps in a redaction/size sentinel wherever that value would cross an egress boundary — a trace, [Xray](#xray), an SSR payload, an off-box log — while on-box rendering still sees the real value. Hygiene applied at the boundary (see [project (egress)](#project-egress)), not security.

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **Recordable vs ambient coeffects**

Two grades of [coeffect](#coeffect). A *recordable* one (the clock, a fresh id) is captured onto the [event envelope](#event-envelope) before the handler runs, so the durable result depends on a recorded value and [replays](#time-travel) identically. An *ambient* one is read live and isn't recorded — fine for a display hint, never for anything that feeds a durable write.

```clojure
{:rf.cofx/requires [:rf/time-ms]}   ;; :rf/time-ms is recordable — stamped on the envelope
```

Related: [Effects](effects.md), [Coeffects](coeffects.md).

## Observability

re-frame2's observability surface — everything tools read to show you the pipeline. The trace stream and [epoch](#epoch) history are dev-only (see [elide](#elide)); the always-on error and event streams survive production. See [Observability](observability.md).

### **trace stream**

The live, in-process feed of [trace events](#trace-event) the runtime emits at every stage of the pipeline — event dispatched, handler run, sub recomputed, effect fired. Every tool ([Xray](#xray), Story, the pair MCP) is just a reader of it; there's no second source of truth. Dev-only — [elided](#elide) from production.

### **trace event**

One immutable record on the [trace stream](#trace-stream): an `:operation` (what happened), an `:op-type` (its family), a timestamp, and tags — including the id that correlates a whole [pipeline run](#event-pipeline). Filter by `:op-type` to slice the stream; the always-on `:errors`/`:events` records are the production-surviving subset.

### **listener**

A callback you register (`register-listener!`) on a named stream — `:trace`, `:epoch`, `:events`, or `:errors` — fired on each matching emit. One registration is a complete tooling integration; but a listener sees data in the clear, so [project it](#project-egress) before sending anything off-box.

### **epoch**

The record one [pipeline run](#event-pipeline) leaves behind — its trigger event, the before/after [app-db](#app-db), and the run's [trace events](#trace-event). The epoch is re-frame2's **unit of time-travel**: [Xray](#xray) rewinds, replays, and inspects history one epoch at a time. It's the last term of the triple: [pipeline](#event-pipeline) (structure) / [run](#run) (one traversal) / **epoch** (the record).

```clojure
;; one dispatch = one run = one epoch (the record)
```

Epochs live on the dev-only observability surface — they're [elided](#elide) from production builds.

Related: [Observability](observability.md).

### **time-travel**

Restoring a [frame](#frame) to the exact state it held at an earlier [epoch](#epoch) — both partitions, in one atomic write, no handlers re-run. It works because each epoch holds the real before/after immutable value; it's the superpower behind [Xray](#xray)'s scrubbing and undo.

### **Xray**

The dev inspector: an in-app panel that reads the [trace stream](#trace-stream) and per-frame [epoch](#epoch) history so you debug the pipeline, not the DOM.

```clojure
;; open Xray to step through epochs, inspect app-db, and read each pipeline run
```

Related: [the Xray docs](../xray/index.md).

### **Story**

The view workbench: render a [view](#view)'s loading, empty, error, and happy states as named variants, each in its own isolated [frame](#frame), then promote the good examples into tests. Reads the same [trace stream](#trace-stream) as every other tool.

Related: [the Story tab](../story/index.md), [Observability](observability.md).
