# Introduction

re-frame2 is a ClojureScript framework for single-page apps. It is opinionated
about **how computation runs**: everything interesting happens as a stream of
[events](glossary.md#event) processed one at a time, through a fixed pipeline,
against a single state map per running instance.

That sounds abstract. A tiny counter makes it concrete.

## A working counter

Three registrations and a mount. Click **+** (edit the cell and press
**`Ctrl-Enter`** / **`Cmd-Enter`** if you change the code):

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [_ _] {:db {:value 0}}))

(rf/reg-event :inc
  (fn [{:keys [db]} _]
    {:db (update db :value inc)}))

(rf/reg-sub :value
  (fn [db _] (:value db 0)))

(rf/reg-view counter []
  [:div
   [:span "Count: " @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]])

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [counter]]
```

You just saw the whole model. The rest of this page names the pieces; the rest of
the Core track grows this same counter one concept at a time.

## A re-frame2 app is a set of registrations

Look at the three `reg-*` calls. Each registers a function under an **id**:

| Registration | Role |
|---|---|
| `reg-event` | **Write side** — given an event, return a *description* of what should change |
| `reg-sub` | **Read side** — derive a view-facing value from app state |
| `reg-view` | **Render** — turn derived values into [hiccup](glossary.md#hiccup) (DOM-as-data) |

In most libraries your code drives and the library assists. Here the **runtime**
drives: it looks up handlers by id and runs them at the right stage of a fixed
pipeline. Your registrations are the instruction set.

Two more registration kinds appear later, when you need them:

- `reg-fx` — perform impure effects (HTTP, storage, …) described by handlers
- `reg-cofx` — supply recorded world facts (time, random id, …) *into* handlers

Counter needs neither. Built-in `:db` is enough.

## The event pipeline in one pass

Click **+**. What actually happens is one trip through the
[**event pipeline**](glossary.md#event-pipeline) — a fixed sequence of stages, not
a free-form cycle of callbacks:

1. **Dispatch.** `#(dispatch [:inc])` puts the event vector `[:inc]` on a FIFO
   queue and returns immediately. Dispatch does not run the handler.
2. **Dequeue.** Shortly after, the runtime takes the next event from the queue.
3. **Write side.** It looks up the handler for `:inc`, builds a small **world** map
   (at minimum today's [app-db](glossary.md#app-db) under `:db`), and calls the
   handler: `(handler world event)`.
4. **Effects as data.** The handler returns `{:db next-map}` — a *description* of
   the next state. It does not mutate the old map.
5. **Commit.** The runtime applies those effects: the frame's app-db reference
   becomes the new map, atomically.
6. **Read side.** Subscriptions whose inputs changed recompute. Views that
   depend on those values re-render. React reconciles the DOM.

```text
click → dispatch → queue → handler → {:db …} → commit → subs → views → DOM
```

Every re-frame2 app is that **pipeline**, run once per event. Nothing "happens"
without an event. The app moves through time as:

```text
event₁ → event₂ → event₃ → …
```

and each event is one full pipeline **run**:

```text
pipeline → pipeline → pipeline → …
```

The pipeline is **fixed**. Every event — yours, the framework's, a timer at 3am —
travels the same stages in the same order. Your functions are Turing-complete; the
structure between them deliberately is not. That restriction is what later buys
replay, time travel, and tests without mocks.

## Events are data

An event is a vector. The head is an id (usually a namespaced keyword). Optional
further elements carry facts — typically one payload map:

```clojure
[:inc]
[:article/loaded {:id 42}]
[:route/changed {:page :about :params {...}}]
```

Events are usually **user intent** (click, type, navigate), but anything can
speak them: timers, HTTP replies, route loaders. The next page,
[Events](events.md), is the vocabulary in full.

## One map of state: app-db

Each running instance holds application state as one immutable Clojure map —
**app-db**. In the counter it starts `{}`, then becomes `{:value 0}` after
`:initialise`, then `{:value 1}` after the first `:inc`.

Handlers never "set state" as a side effect. They return the next map inside an
effect description. [app-db](app-db.md) is the dedicated page for that doctrine.

## Frames: one running world

The counter mounts under:

```clojure
[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [counter]]
```

A [**frame**](glossary.md#frame) is an isolated execution context: its own app-db,
event queue, and subscription cache. `frame-root` ensures that frame exists (once),
runs `:initial-events` once, and scopes the subtree so `dispatch` / `subscribe`
inside the view resolve to it.

Until the pure pipeline stages are in place (events through views), treat that form
as the whole boot story. Isolation and carry live on [Frames](frames.md). Packaging
a real app (`init!`, hot reload, listeners) lives on
[Boot and mount an app](how-to/boot-and-mount-an-app.md). Different registration sets
per frame are rare — [Images](images.md).

## In summary

| Piece | Job |
|---|---|
| Registrations | The instruction set (`reg-event`, `reg-sub`, `reg-view`, …) |
| Events | The instructions (vectors of intent) |
| Frame | One running machine (app-db + queue + caches) |
| Pipeline | Fixed fold: write → commit → read |

```text
app-state = reduce(event-pipeline, initial-state, events)
```

One formula, applied one event at a time.

## How the Core track is organised

The left nav is the learning order, and it owns the page roster — this page won't
restate it. What's worth saying is the *shape* of that order, because each stage
leans only on the one before it.

You start with the **pure pipeline** — the whole model, no impurity yet, and enough
to build real screens. Then **impurity** lets handlers reach the world (HTTP,
storage, timers, recorded facts) and stay pure doing it. **Structure** arrives once
an app grows — isolating worlds, and moving derivations to the write side.
**Operations** is for when something breaks. The **advanced** corners come last, and
most apps never reach for them.

Every concept page opens a **day-one** path and marks its optional depth, so you can
stop and ship at any stage. How-to recipes, testing, and the "why it's built this
way" essays sit alongside those concepts — reach for them when you have a task or a
design question, not as a first read-through.

### Happy path and unhappy path

Every stage teaches the **happy path** first (a working counter grown one idea at
a time). Each concept page also names the **loud failures** you will meet early —
missing handler, missing sub, no frame on a callback, effect-map typos — so recovery
is vocabulary, not archaeology.