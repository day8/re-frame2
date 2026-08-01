# Glossary

re-frame2 nouns, verbs, and concepts. One term, definition first; short code when the spelling matters; Related points at the teaching page.

## The Nouns

### **adapter**

A map of functions that binds re-frame2 core to a React-family [substrate](#substrate) (Reagent, UIx, …). A *value*, not the library itself.

Install once at boot via `init!`:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/init! reagent-adapter/adapter)
```

To switch substrate, change that require and pass its `adapter`. Events, subscriptions, and app-db stay the same.

Related: [Views](views.md), [Adapters](../api/re-frame.adapter.reagent.md). Substrate = the rendering library; adapter = the value that binds re-frame2 to it.

### **app-db**

The single immutable map of application state — one per [frame](#frame), the state your code owns.

You choose the shape; optionally guard it with a schema (Malli by default). You never mutate it in place: an [event handler](#event-handler) returns a *new* app-db, the runtime commits it atomically, and [subscriptions](#subscription) re-derive [views](#view) from that value. State enters through events and leaves through subscriptions — never the reverse.

```clojure
{:cart  {:items [{:sku "BK-1" :qty 2}]
         :open? false}
 :user  {:name "Ada"}
 :route {:page :checkout}}
```

Framework state lives separately in [runtime-db](#runtime-db) inside the same frame; see [the two partitions](#the-two-partitions).

Related: [app-db](app-db.md), [Validate with schemas](how-to/validate-with-schemas.md).

### **path**

A vector of keys into [app-db](#app-db), with `get-in` / `assoc-in` semantics — `[:auth :token]` is the `:token` under `:auth`. Paths are the addressing form used by schemas, [data-classification](#data-classification), flow `:output-path`, and the `path` interceptor.

Related: [app-db](app-db.md).

### **coeffect**

A fact about the world (current time, fresh UUID, localStorage value, …) the runtime supplies to an [event handler](#event-handler) as data so the handler stays pure and never reaches out itself. Handler shape: coeffects in → [effect map](#effect-map) out.

The first argument is always a coeffects map with `:db` (current [app-db](#app-db)). Any other fact must be listed under `:rf.cofx/requires`; the runtime fills it in:

```clojure
(rf/reg-event :order/place
  {:rf.cofx/requires [:today]}
  (fn [{:keys [db today]} _]
    {:db (assoc db :order/date today)}))
```

Register a supplier with [`reg-cofx`](../api/re-frame.core.md#reg-cofx):

```clojure
(rf/reg-cofx :today
  (fn []
    (subs (.toISOString (js/Date.)) 0 10)))
```

Declared input is what keeps events pure, testable, and replayable.

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect**

One side effect described as data for the runtime — HTTP, navigation, delayed dispatch, localStorage write. Shape: `[effect-id config]`, listed in the `:fx` vector of an [effect map](#effect-map):

```clojure
[:dispatch-later {:ms 500 :event [:cart/saved]}]
```

The [event handler](#event-handler) only *describes* the effect; the [effect handler](#effect-handler) registered with [`reg-fx`](../api/re-frame.core.md#reg-fx) performs it. Effects are the output dual of input [coeffects](#coeffect).

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect handler**

The function registered with `reg-fx` for an `effect-id`. The runtime calls it once per matching entry in `:fx`, with that effect's config. Impure work lives here so the pure [event handler](#event-handler) stays data-only.

```clojure
(rf/reg-fx :cart/save
  (fn [_ctx {:keys [items]}]
    (save-to-server! items)))
```

Built-ins cover common effects (`:dispatch`, `:dispatch-later`, `:rf.http/managed`, …); add your own with `reg-fx`.

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **effect map**

What an [event handler](#event-handler) — or a [machine](../machines/glossary.md#machine) action — returns: a *description* of change, not the change itself.

Two reserved keys. `:db` is the new [app-db](#app-db):

```clojure
{:db new-db}
```

`:fx` is a vector of [effects](#effect) — each `[effect-id config]`:

```clojure
{:db new-db
 :fx [[:http {:url "/api/cart" :method :post}]
      [:dispatch-later {:ms 500 :event [:cart/saved]}]]}
```

Only `:db` and `:fx` may appear at the top level; an unknown key fails loud. Each effect runs through an [effect handler](#effect-handler) (`reg-fx`).

Related: [Effects](effects.md), [Coeffects](coeffects.md). Casual name for the `:fx` key: "fx".

### **error record**

A failure the runtime surfaces as a structured map — [fails loud](#fail-loud-not-silent), as data. Every record carries a reserved **`:rf.error/*` category**: on traced/listener records under `:operation`; on construction-time throws as `:rf.error/id` in `ex-data`. **Branch on the category**, never on human-readable `:reason` (prose; can change).

```clojure
{:operation :rf.error/no-frame-context
 :reason    "no frame in scope"}
```

Error records reach always-on error listeners (Sentry, Datadog, …) and **survive production** (unlike the dev-only trace surface).

Related: [Errors](errors.md).

### **event**

An inert data vector: *something happened*. You [dispatch](#dispatch) it; a registered [event handler](#event-handler) decides the response. The event itself does nothing.

Most events are user intent (click, drag, route change); timers, browser APIs, WebSockets, and loaders raise them too. First element is the id (namespaced keyword is the norm), then optional facts:

```clojure
[:event-id & facts]
```

Prefer one payload map over positional args:

```clojure
[:cart/add-item {:sku "BK-1" :qty 1}]    ;; good
[:cart/add-item "BK-1" 1]                ;; placeful
```

Data means log, record, and replay.

Related: [Introduction](introduction.md).

<a id="event-cascade"></a>
### **event pipeline**

The fixed stage sequence one dispatched [event](#event) runs through, in three **phases**:

- [**update phase**](#update-phase) — compute a *description* of the change
- [**commit phase**](#commit-phase) — run declared effects; `:db` write first, atomically
- [**render phase**](#render-phase) — bring derivations and the screen up to date

Update and commit run per event — [assemble](#assemble) → [transform](#transform) → [commit](#commit) → [perform](#perform). Render runs once per [render batch](#render-batch) after the queue settles — [derive](#derive) → [render](#render).

```clojure
;; update + commit (per event):  assemble → transform → commit → perform
;; render         (per batch):   derive → render
```

`:db` is the anchor: transactional up to that write, best-effort after. The [view](#view) renders from committed state, never mid-run.

One traversal is a [**run**](#run); the record it leaves is an [**epoch**](#epoch). Triple: **pipeline** (structure) / **run** (one trip) / **epoch** (record). Running the whole queue before render is a [**drain**](#drain--run-to-completion).

Related: [Introduction](introduction.md). Older prose said *event cascade*, *turn of the loop*, or *the loop* for this traversal — prefer **event pipeline** / **pipeline run**. Machines *cancellation cascade* keeps its name.

### **run**

One traversal of the [event pipeline](#event-pipeline) for a single dispatched [event](#event): update and commit for that event, then the shared render of the [render batch](#render-batch) it settles into. [**Pipeline**](#event-pipeline) = structure; **run** = one trip; [**epoch**](#epoch) = the record. One dispatch = one run = one epoch. A [drain](#drain--run-to-completion) is many runs, one render batch.

Related: [Introduction](introduction.md).

<a id="write-side"></a>
### **update phase**

First phase of the [event pipeline](#event-pipeline) — [assemble](#assemble) → [transform](#transform). Pure work once per [event](#event): build the [world](#world), run the handler, produce a description of change (new value + declared effects). Nothing has executed yet. A throwing handler installs nothing.

Related: [Introduction](introduction.md).

### **commit phase**

Second phase — [commit](#commit) → [perform](#perform). Declared effects **execute**, once per [event](#event). The `:db` write always runs *first*: new [app-db](#app-db) lands atomically before other effects; remaining effects then run in source order, best-effort. **Pre-commit** / **post-commit** are positions relative to that write. Only the committed value reaches the [render phase](#render-phase).

Related: [Effects](effects.md), [Introduction](introduction.md).

<a id="read-side"></a>
### **render phase**

Final phase — [derive](#derive) → [render](#render). Brings the screen up to date after the queue settles. Once per [**render batch**](#render-batch), not once per event: reads only the committed value, never a half-written [app-db](#app-db). A [drain](#drain--run-to-completion) is never split across batches.

Related: [Subscriptions](subscriptions.md), [Introduction](introduction.md).

### **render batch**

The window of pending reads and renders the [render phase](#render-phase) finishes in one go. Everything dirty since the last batch renders together. The batch closes at the host's next microtask checkpoint, or at an explicit flush in headless tests.

The boundary is the *host*'s, not the [drain](#drain--run-to-completion)'s: the UI scheduler does not observe the event queue or drain edges.

- **A drain cannot split across batches.** Every event a drain settles renders together; no intermediate state hits the screen.
- **Drains that finish before the same checkpoint may share a batch.** Two back-to-back `dispatch-sync` calls in one JS stack render once; drains separated by a real host yield render separately.

In ordinary app code a yield falls between drains, so "one render per drain" is a fine working model for the common case, not a hard rule.

Related: [render phase](#read-side), [drain](#drain--run-to-completion), [Effects — run to completion](effects.md#run-to-completion).

### **world**

The map of declared facts assembled for an [event handler](#event-handler) — everything outside the handler is allowed to see, as data. [app-db](#app-db) is one entry (always present, under `:db`); clock, fresh id, storage reads are others declared via `:rf.cofx/requires`. The world is the handler's first argument — its [coeffects](#coeffect) map — built in the [assemble](#assemble) stage.

```clojure
{:db {…}  :today "2026-07-04"  :new-id #uuid "…"}
```

A [**frame**](#frame) is a *running* world: that map plus queue, caches, and lifecycle that re-assemble it per event.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [frame](#frame).

### **event envelope**

Runtime package created when an [event](#event) is dispatched. Low-level; mostly router-internal.

App code dispatches an event vector (optionally with dispatch opts). Before queueing, re-frame2 wraps those into an envelope: the event vector plus target [frame](#frame), origin, tracing ids, per-dispatch overrides, and the durable `:rf.cofx` record used for replayable coeffects such as `:rf/time-ms`.

Handlers get the event vector as their second argument and an assembled [coeffects](#coeffect) map as their first — not the envelope itself.

```clojure
(rf/dispatch [:cart/add {:sku "BK-1"}]
  {:frame :checkout
   :source :ui
   :trace-id :cart/add-click})

;; Rough envelope shape:
{:event    [:cart/add {:sku "BK-1"}]
 :frame    :checkout
 :source   :ui
 :origin   :app
 :trace-id :cart/add-click
 :rf.cofx  {:rf/time-ms 1781078400123}}
```

Related: [Introduction](introduction.md), [Frames](frames.md), [Effects](effects.md), [Coeffects](coeffects.md).

### **event handler**

A **pure** function that computes how a dispatched [event](#event) should change the world. Arguments: [coeffects](#coeffect) map (including `:db`) and the event vector; return: [effect map](#effect-map).

```text
(coeffects, event-vector) -> effect-map
```

It *describes* changes (`:db`, `:fx`); it does not perform them. No IO, no clock, no subscription reads inside — world arrives only through declared coeffects.

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Related: [Introduction](introduction.md).

### **flow**

A pure derivation re-frame2 keeps materialised at a path in [app-db](#app-db). Because the value lives *in* app-db, [event handlers](#event-handler) can read it as plain state — unlike a [subscription](#subscription), whose value is for [views](#view).

Declare `:inputs` and an `:output-path` in the metadata map, with the pure derive fn as the third slot. When an input changes, the runtime re-runs that fn and writes the result in step with the [event pipeline](#event-pipeline):

```clojure
(rf/reg-flow :cart/total
  {:inputs      [[:cart :items]]
   :output-path [:cart :total]}
  (fn [items] (reduce + (map :price items))))
```

Use a flow to collate many facts into one (e.g. several error flags → `:any-errors?`). Inputs may read framework state under `:rf.db/runtime`. Flows can be [added and removed dynamically](../api/re-frame.flows.md) via effects.

Related: [Flows](flows.md), [toggling a derivation at runtime](flows.md#toggling-a-derivation-at-runtime).

### **frame**

An isolated running instance of an app. **A frame is a running [world](#world)** — declared facts ([app-db](#app-db) among them) plus runtime machinery: [runtime-db](#runtime-db), event queue, subscription cache, lifecycle.

A frame supplies *state*; *behaviour* comes from an [image](#image). **A frame isolates state, not registrations** — the registry is process-global, so the same handlers, subs, views, effects, flows, and machines run in every frame against that frame's own state. Most frames use the default image (all registrations).

Most apps create one frame at boot. Multiple frames on one page power tests, stories, per-request SSR, and tools like [Xray](#xray). [Identity is carried, not found](#frame-identity-is-carried-not-found) — each operation reads its frame from scope.

```clojure
(rf/make-frame
  {:id :app
   :initial-events [[:app/initialise]]
   :images [image1 image2]})    ;; optional selected registrations
```

Related: [Frames](frames.md).

### **capture-frame**

`(rf/capture-frame)` returns a **frame api**: a small map with that frame's `:dispatch` / `:dispatch-sync` / `:subscribe` plus the captured `:frame` id. Carry it across async — grab while the frame is in scope so a later `setTimeout`, promise, or WebSocket callback can still target it instead of raising `:rf.error/no-frame-context`. (*capture-frame* = verb; *frame api* = value returned.)

Related: [Frames](frames.md).

### **frame-provider**

React component that *scopes* an existing [frame](#frame) to a view subtree so `dispatch`/`subscribe` resolve to it. SCOPE-only — **roots ensure; providers scope**: `{:frame existing-id}` provides an already-created id via React context; creates and destroys nothing; fails loud if the frame is missing. Given `:id` (ENSURE key) it fails loud naming sibling [`frame-root`](#frame-root). Everyday expression of [frame identity is carried, not found](#frame-identity-is-carried-not-found).

Related: [Frames](frames.md).

### **frame-root**

React component that *ensures* a named [frame](#frame) for a subtree's mounted lifetime — ENSURE sibling of [`frame-provider`](#frame-provider). Keyed by `{:id …}` (plus `make-frame` opts): creates if absent (at commit, in a client layout effect — discarded React renders create nothing), reuses without re-seeding if present (hot reload / StrictMode preserve app-db; never replay `:initial-events`), provides id to descendants. Does not destroy on unmount; ownership is explicit `make-frame` + `destroy-frame!`. Given `:frame` it fails loud naming `frame-provider`.

Related: [Frames](frames.md).

### **hiccup**

Clojure data for UI: nested vectors — `[:div.card {:on-click f} "Hi"]` is a `<div>`. Markup as data; a [view](#view) composes it; the [substrate](#substrate) turns it into React elements (or a server string).

```clojure
[:ul.cart (for [item items] [:li {:key (:sku item)} (:name item)])]
```

Related: [Views](views.md).

### **image**

The selected set of registrations a [frame](#frame) resolves behaviour against — [event handlers](#event-handler), [subscriptions](#subscription), [views](#view), [effect handlers](#effect-handler), [flows](#flow), [machines](../machines/glossary.md#machine), and the rest. An image is a *value*: no state, not a running app.

Most apps use the **default image** — all registrations already loaded. Name an image when frames need different behaviour (fake effects in tests, two examples sharing event ids, [Xray](#xray) sidecar).

**Image supplies behaviour; [frame](#frame) supplies state.** On start, the image resolves into a sealed registration set (*generation*).

```clojure
(def checkout-image
  (rf/image {:select-ns {:include ["app.checkout.*"]}}))

(rf/make-frame
  {:id :checkout/story
   :images [checkout-image]})
```

Related: [Images](images.md).

### **generation**

The sealed registration set a [frame](#frame)'s [image](#image) resolves into at construction — the concrete "which handler answers this id" table, frozen as a value. Every `make-frame` frame carries one (default image included). Re-calling `make-frame` with the same `:id` and a new `:images` vector swaps the frame onto a newly resolved generation.

Related: [Images](images.md).

### **interceptor**

A named wrapper around an [event handler](#event-handler) — `:before` / `:after` functions for cross-cutting work (logging, validation, tracing, undo). Each is `context → context`:

- `:before` runs before the handler; can read/adjust [coeffects](#coeffect)
- `:after` runs after; can read/adjust the returned [effect map](#effect-map)

Register by id with `reg-interceptor` (never inline). Events opt in by id:

```clojure
(rf/reg-interceptor :my-app/logger
  {:before (fn [ctx] ctx)
   :after  (fn [ctx] ctx)})

(rf/reg-event :cart/add
  {:interceptors [:my-app/logger]}
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Related: [Interceptors](interceptors.md).

### **query vector**

The vector passed to `subscribe` for a [subscription](#subscription): id plus optional args — `[:article/page "abc"]`. Id selects the sub; the whole vector keys the cache, so equal vectors share one cached value.

```clojure
@(rf/subscribe [:cart/count])
@(rf/subscribe [:article/by-id "BK-1"])
```

Related: [Subscriptions](subscriptions.md).

### **registrar**

The single process-wide table every `reg-*` writes and every lookup reads, keyed by kind + id. Holds all [registrations](#registration). **Every [frame](#frame) shares one registrar** — frames isolate state, not behaviour.

### **registration**

An app's behaviour is the set of registrations you provide.

One registration maps an `id` (usually a namespaced keyword) to a function or config the runtime looks up later.

At runtime:

- a [frame](#frame) supplies isolated state and execution context
- an [image](#image) supplies the selected registrations
- a stream of [events](#event) drives the runtime

Example: event id `:cart/add` → this [event handler](#event-handler):

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

[Core](../api/re-frame.core.md) registration:

- `reg-event` — [event handler](#event-handler)
- `reg-sub` — [subscription](#subscription)
- `reg-fx` — [effect handler](#effect-handler)
- `reg-cofx` — [coeffect](#coeffect) supplier
- `reg-interceptor` — event-handler wrapper
- `reg-view` / `reg-view*` — [views](#view)

Frame construction is not a `reg-*` member — a frame is a live runtime object. `make-frame` creates a named [frame](#frame); `frame-root` is the ENSURE mount recipe.

Flows:

- `reg-flow` — [flow](#flow)

[Machines](../machines/concepts.md):

- `reg-machine` / `reg-machine*` — [machines](../machines/glossary.md#machine)

Routing:

- `reg-route` — route

Schema:

- `reg-app-schema` / `reg-app-schemas` — Malli schemas for app-db paths

SSR:

- `reg-head` — SSR head producer
- `reg-error-projector` — SSR error projector

HTTP:

- `reg-http-interceptor` — managed-HTTP middleware

[Resource](../api/re-frame.resources.md):

- `reg-resource` — [resource](../resources/glossary.md#resource)
- `reg-mutation` — [mutation](../resources/glossary.md#mutation)
- `reg-resource-scope` — named resource-scope resolver

### **runtime-db**

Framework-owned half of a [frame](#frame)'s state — beside [app-db](#app-db) you own; see [the two partitions](#the-two-partitions).

Holds machine [snapshots](../machines/glossary.md#snapshot), current route, [resource](../resources/glossary.md#resource) caches, [mutation](../resources/glossary.md#mutation) status, and similar. App code reads via subscriptions or accessors — never by editing `:rf.db/runtime` paths directly.

```clojure
[:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow]
```

Related: [app-db](#app-db), [frame](#frame). Paths: `:rf.db/runtime`, children `:rf.runtime/*`.

### **schema**

A data description of a value's shape — `[:map [:sku :string] [:qty :int]]` — in **Malli** (default). Attach to an [app-db](#app-db) path (`reg-app-schema`), an event, or an HTTP `:decode` step. Checks run at a named boundary, but whether one survives a production build depends on which boundary: `reg-app-schema`'s app-db check and the plain event check are development assertions and [elide](#elide), while an event handler carrying `:rf.schema/at-boundary` and a managed-HTTP `:decode` schema are checked in **every** build. Schema-as-data supports validate, coerce, and tooling round-trips.

Related: [Validate with schemas](how-to/validate-with-schemas.md).

### **subscription**

A named, registered, pure, **cached** derivation of state — how a [view](#view) reads what it needs. `reg-sub`; recomputes only when inputs change by `=`. Layers: some read [app-db](#app-db) directly; others combine other subscriptions.

```clojure
(rf/reg-sub :cart/count (fn [db _] (count (:items (:cart db)))))
```

Related: [Subscriptions](subscriptions.md). Casual "sub" is fine; not as a headword. Value inside an [event handler](#event-handler)? Materialise with a [flow](#flow).

### **substrate**

The React-family rendering layer — Reagent, UIx, or reagent-slim. Wire re-frame2 to it with an [adapter](#adapter). Core is substrate-agnostic: events, subscriptions, and app-db stay the same; only rendering differs.

Related: [Use UIx or slim](how-to/use-uix-or-slim.md).

### **view**

A pure render function from [subscription](#subscription) values to **hiccup**. Reads derived state; [dispatches](#dispatch) [events](#event) on interaction; no business logic. The [substrate](#substrate) turns hiccup into React elements.

```clojure
(rf/reg-view cart-badge []
  [:span.badge @(subscribe [:cart/count])])
```

Related: [Views](views.md). Use "component" only in React-analogy callouts.

## The Verbs

The six pipeline stages — [**assemble → transform**](#write-side) ([update phase](#write-side), per event), [**commit → perform**](#commit-phase) ([commit phase](#commit-phase), per event), [**derive → render**](#read-side) ([render phase](#read-side), per [render batch](#render-batch)) — in pipeline order.

### **assemble**

First stage: gather the [**world**](#world) the [event handler](#event-handler) will read — [app-db](#app-db) (`:db`) plus every fact listed in `:rf.cofx/requires` — into one [coeffects](#coeffect) map before the handler runs.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [world](#world).

### **transform**

Second stage: run the pure [event handler](#event-handler) — assembled [world](#world) and [event](#event) in, [effect map](#effect-map) out. Transforms world into a description (`{:db … :fx …}`); performs none of it. Later stages after [commit](#commit) execute that description.

Related: [Introduction](introduction.md), [event handler](#event-handler).

### **commit**

The single, deferred, all-or-nothing write of the new [app-db](#app-db) — **first effect of the [commit phase](#commit-phase)**, and its anchor. Special only in that it always runs first; it is the one point the committed value crosses to the [render phase](#read-side). Before it (assemble, transform): transactional — the `:db` the handler returns is *staged* and lands once, after flows run; a throwing handler or flow installs *nothing*. After it ([perform](#perform)): best-effort. No observer sees a half-written app-db.

Related: [Introduction](introduction.md).

### **perform**

Fourth stage; rest of the [commit phase](#commit-phase): run `:fx` rows from [transform](#transform) in source order after [commit](#commit). Only place the system touches the outside world (HTTP, navigation, follow-up dispatch), via each id's [effect handler](#effect-handler). Past the `:db` write: best-effort — a throwing effect does not un-commit state.

Related: [Effects](effects.md), [Coeffects](coeffects.md), [effect](#effect).

### **derive**

Fifth stage; first of the [render phase](#read-side): recompute [subscriptions](#subscription) (and [the derivation graph](#the-derivation-graph)) that watch changed parts of committed [app-db](#app-db). Values equal by `=` to last time prune everything downstream. Once per [render batch](#render-batch), against settled state. Public verb: [`subscribe`](#subscribe--derive); *derive* names the stage.

Related: [Subscriptions](subscriptions.md).

### **render**

Sixth stage: [views](#view) that deref a *changed* [subscription](#subscription) re-run, produce fresh [hiccup](#hiccup); the [substrate](#substrate) patches the DOM that moved. Once per [render batch](#render-batch) from settled state — a [drain](#drain--run-to-completion) is never split across batches — so the screen does not flash intermediate values.

Related: [Views](views.md).

### **dispatch**

Enqueue an [event](#event) for a [frame](#frame).

Wraps the event in an [event envelope](#event-envelope) and returns immediately; the [event handler](#event-handler) runs later in the [pipeline run](#event-pipeline) that event starts. Sibling `dispatch-sync` runs the pipeline *now* (tests, boot).

```clojure
(rf/dispatch [:cart/add-item {:sku "BK-1"}]
  {:frame :checkout})
```

Related: [event](#event), [event envelope](#event-envelope), [event pipeline](#event-pipeline).

### **dispatch-sync**

Like [`dispatch`](#dispatch), but runs the [event](#event) and normally drains the whole queue to completion *before returning*. A drain-depth halt or successful exact-incarnation destruction claim is terminal. At a destroy claim, an authored callback already on the stack may return and entered interceptor `:after` callbacks may unwind, but the returned framework tail is inert and later ordinary events do not begin. Use at boot, in tests, and at the REPL — never from inside a running handler (`:rf.error/dispatch-sync-in-handler`).

```clojure
(rf/dispatch-sync [:app/initialise])   ;; app-db committed before the next line
```

Related: [Introduction](introduction.md).

### **drain / run-to-completion**

The runtime normally drains the *whole* event queue to a fixed point — [update and commit](#write-side) of every queued [event](#event) — before the [render phase](#read-side). Update and commit run *per event*; everything the drain settles lands in one [render batch](#render-batch); the UI updates once from settled state. A drain-depth halt or successful exact-incarnation destruction claim is terminal. At a destroy claim, authored callbacks already on the stack may return and entered interceptor `:after` callbacks may unwind; the returned framework tail is inert; later ordinary events do not begin and no render phase follows the interrupted event. A drain is normally many [pipeline runs](#event-pipeline) sharing one render phase.

```clojure
;; every queued event's update + commit, THEN — at the host's next
;; checkpoint, once — subs recompute and views render
```

Related: [Effects — run to completion](effects.md#run-to-completion) (idea + demo);
[Run to completion (detail)](run-to-completion.md) (drain-depth, `dispatch-sync`).
Hyphenate **run-to-completion** consistently.

### **elide**

Compile dev-only code out of production via one flag (`goog.DEBUG` or `-Dre-frame.debug`). Removes the dev trace surface, the [epoch](#epoch) buffer, and the *ordinary registration diagnostics* among the [schema](#schema) checks. What elides is settled by **what the check is for**, not by who declared the schema it reads: a check the framework relies on to keep a promise of its own — `:rf.schema/at-boundary`, a recordable [coeffect](#coeffect)'s `:schema`, a declared route's shape — holds in every build, and those three all validate against a schema the programmer wrote. Always-on `:errors` and `:events` streams survive.

```clojure
;; goog.DEBUG=false removes the dev trace surface and the schema
;; checks you declared — not the framework's own boundary checks
```

Related: [Observability](observability.md), [Configure dev and production builds](how-to/configure-dev-and-prod.md), [schema](#schema). Name DCE once, then use **elide**.

### **init!**

One-time boot call that installs a [substrate](#substrate) [adapter](#adapter) — `(rf/init! reagent-adapter/adapter)`. Idempotent. Does *not* create a default [frame](#frame) (identity is carried, not found); you establish the root frame explicitly.

Related: [Adapters](../api/re-frame.adapter.reagent.md).

### **project (egress)**

Run a value through redaction before it leaves the app via `project-egress`. Direct reads are not auto-projected.

```clojure
(rf/project-egress value {:frame :app/main :path [:auth]})
```

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **register**

Name handlers and machinery at boot with [registration](#registration) forms — `reg-event`, `reg-sub`, and so on.

```clojure
(rf/reg-event :cart/clear (fn [{:keys [db]} _] {:db (dissoc db :cart)}))
```

Related: [Introduction](introduction.md). There is **one** `reg-event`: `reg-event-db` / `-fx` / `-ctx` are gone.

### **subscribe / derive**

Read derived state by name through a [subscription](#subscription). `@(subscribe …)` reads the current value *and* subscribes so the [view](#view) re-renders on change.

```clojure
@(rf/subscribe [:cart/count])
```

Related: [Subscriptions](subscriptions.md).

## The Concepts

### **Effects are data**

An [event handler](#event-handler) returns a *description* of side effects — an [effect map](#effect-map) of data — and the runtime performs them. Pure handler + data effects enable replay, test, and trace.

```clojure
{:fx [[:http {:url "/api/login"}] [:dispatch [:ui/spinner true]]]}
```

Related: [Effects](effects.md), [Coeffects](coeffects.md).

### **Fail loud, not silent**

A recognised input that cannot be honoured raises a structured [error record](#error-record) (`:rf.error/*`), never a nil or no-op. **Fail-loud** = raise instead of swallow. **Fail-closed** = deny by default at a boundary. Keep them distinct.

```clojure
;; unregistered id, missing cofx, unknown fx → :rf.error/*, never nil
```

Related: [Errors](errors.md).

### **Frame identity is carried, not found**

An operation reads its [frame](#frame) from scope (provider / running handler / captured handle). The runtime never invents one. A rootless call is `:rf.error/no-frame-context`.

```clojure
[rf/frame-provider {:frame :app} [app-root]]
```

Related: [Frames](frames.md).

### **The four homes (where state lives)**

[Subscription](#subscription) → [flow](#flow) → [resource](../resources/glossary.md#resource) → [machine](../machines/glossary.md#machine): pick the cheapest that fits. Full router: [Where state lives](where-state-lives.md).

```clojure
;; cart total → sub (or flow); the article → resource; checkout → machine
```

Related: [Where state lives](where-state-lives.md).

### **The two partitions**

A [frame](#frame) holds [app-db](#app-db) (yours) and [runtime-db](#runtime-db) (framework), addressed by `:rf.db/app` and `:rf.db/runtime`; subsystems under `:rf.runtime/*`.

```clojure
[:rf.db/runtime :rf.runtime/resources]
```

Related: [app-db](app-db.md).

### **The uniform reply**

Every managed async surface (HTTP, resources, mutations, route loaders, machine async) completes by [dispatching](#dispatch) an [event](#event) with one canonical reply map — never an awaited value. Discriminator is closed `:status`: `:ok` (value at `:value`), `:error` (failure at `:error`), `:cancelled`, or `:stale`. Same envelope on every surface, HTTP included. Distinct from the resource *read sub*'s `:status` lifecycle (`:idle` / `:loading` / `:fetching` / `:loaded` / `:error`).

```clojure
[:auth/login-reply {:status :ok :value {:token "…"}}]
```

Related: [Managed HTTP](../async/http.md).

### **The derivation graph**

Directed graph of pure derivations rooted at [app-db](#app-db), [views](#view) at the leaves. [Subscriptions](#subscription), [flows](#flow), resource reads, route facts, and machine selectors are nodes. The runtime recomputes only along edges whose value changed by `=`; unchanged input prunes everything downstream.

Related: [Subscriptions](subscriptions.md).

### **Data classification**

Marking an [app-db](#app-db) path (or payload slot) `:sensitive` or `:large` so the runtime swaps in a redaction/size sentinel wherever that value would cross an egress boundary (trace, [Xray](#xray), SSR payload, off-box log). On-box rendering still sees the real value. Hygiene at the boundary (see [project (egress)](#project-egress)), not security.

Related: [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

### **Recordable vs ambient coeffects**

Two grades of [coeffect](#coeffect). *Recordable* (clock, fresh id) is captured onto the [event envelope](#event-envelope) before the handler runs so durable results [replay](#time-travel) identically. *Ambient* is read live and not recorded — fine for a display hint, never for a durable write.

```clojure
{:rf.cofx/requires [:rf/time-ms]}   ;; recordable; stamped on the envelope
```

Related: [Effects](effects.md), [Coeffects](coeffects.md).

## Observability

Tools read the pipeline through this surface. Trace stream and [epoch](#epoch) history are dev-only (see [elide](#elide)); always-on error and event streams survive production. See [Observability](observability.md).

### **trace stream**

Live in-process feed of [trace events](#trace-event) at every pipeline stage — dispatch, handler, sub recompute, effect. Tools ([Xray](#xray), Story, pair MCP) are readers of it. Dev-only — [elided](#elide) from production.

### **trace event**

One immutable record on the [trace stream](#trace-stream): `:operation`, `:op-type`, timestamp, tags (including the id that correlates a whole [pipeline run](#event-pipeline)). Filter by `:op-type`. Always-on `:errors` / `:events` records are the production-surviving subset.

### **listener**

Callback registered with `register-listener!` on a named stream — `:trace`, `:epoch`, `:events`, or `:errors` — fired on each matching emit. One registration is a tooling integration. Listeners see data in the clear — [project](#project-egress) before sending off-box.

### **epoch**

The record one [pipeline run](#event-pipeline) leaves — trigger event, before/after [app-db](#app-db), run's [trace events](#trace-event). Unit of time-travel: [Xray](#xray) rewinds, replays, and inspects one epoch at a time. Triple: [pipeline](#event-pipeline) / [run](#run) / **epoch**.

```clojure
;; one dispatch = one run = one epoch
```

Dev-only — [elided](#elide) from production.

Related: [Observability](observability.md).

### **time-travel**

Restore a [frame](#frame) to the state it held at an earlier [epoch](#epoch) — both partitions, one atomic write, no handlers re-run. Each epoch holds real before/after immutable values. Powers [Xray](#xray) scrubbing and undo.

### **Xray**

Dev inspector: in-app panel over the [trace stream](#trace-stream) and per-frame [epoch](#epoch) history. Debug the pipeline, not the DOM.

```clojure
;; open Xray to step epochs, inspect app-db, read each pipeline run
```

Related: [the Xray docs](../xray/index.md).

### **Story**

View workbench: render a [view](#view)'s loading, empty, error, and happy states as named variants, each in its own [frame](#frame); promote good examples into tests. Reads the same [trace stream](#trace-stream) as other tools.

Related: [the Story tab](../story/index.md), [Observability](observability.md).
