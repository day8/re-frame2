# Introduction

To understand re-frame2, you need to understand how it does computation.

Which is an odd opening for a Single Page Application (SPA) library, I know. But bear with me.

## A working app

To assist explanations, here's a tiny application — a counter app:

- an increment button
- a displayed value that gets incremented on each button click

```cljs-rf2
(require '[re-frame.core :as rf])

;; calls to four registration functions
(rf/reg-event :initialise (fn [_ [_ v]] {:db {:value v}}))
(rf/reg-event :inc        (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-sub   :value      (fn [db _] (:value db 0)))

(rf/reg-view counter [background]
  [:div {:style {:background background}}
   [:span "Count: " @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]])
```

None of this code has been explained yet, but all you need
to grasp at the moment is that this entire application is simply a series of function calls,
starting with `reg-`. Four function calls:

- To start with, `reg-event` is called twice (to register two event handlers)
- then, `reg-sub` is called once (to register a subscription handler)
- and finally, `reg-view` is called once (to register a view)

**A re-frame2 app is a set of registrations**. Each registration associates:

- an `id` (like `:inc` or `:initialise` above)
- with a function like `(fn [_ _] {:db {:value 3}})`.

We don't **yet** need to understand what these functions are doing, but we do need to latch on to the idea that programming re-frame2 involves:

- writing functions
- and registering them with an `id`

Later, when the application is running, and the user is clicking buttons etc., these functions will be called by the re-frame2 computational engine to perform certain calculations.

## Running our app

We might have created the Counter app (via those 4 registrations), but we haven't run it yet. To do that, we need to create a `frame`, which provides an isolated execution context.

In fact, to double the excitement, let's create two `frames` and have two instances of our Counter app on the page at the same time: one with a blue background and one with a lavender background:

```cljs-rf2
[:div
   [rf/frame-root
     {:id :app1
      :initial-events [[:initialise 3]]}
     [counter "LightBlue"]]
   [rf/frame-root
     {:id :app2
      :initial-events [[:initialise 10]]}
     [counter "LavenderBlush"]]]
```

This code is `hiccup` (a data structure representing DOM). And in this in-browser dev environment, hiccup at the end of an interactive block is rendered, which is why we see the application running.

That hiccup is:

- a `[:div ...]`
- containing two child `frame-root` nodes
- each `frame-root` wraps the previously registered `counter` view, like this: `[counter "a colour"]`

When you see `frame-root` think of **Provider** in the **React Context** sense. A `frame-root` is like a wrapping view node which provides context to its child views — in this case a `frame` (an isolated execution context) is ambiently available to each of the two `counter` views, which are in different branches of the DOM.

Experiment/edit this code:

1. Change `"LavenderBlush"` to `"green"`
2. Change `[:initialise 3]` to `[:initialise 4]`

Press **`Ctrl-Enter`** / **`Cmd-Enter`** after doing your edits.

## Isolated execution context

The isolated execution context provided by a `frame` reifies:

- state — an immutable map which starts off as `{}`
- a queue of events
- some caches for performance

In our Counter app, state starts as `{}`, and then immediately after frame creation becomes `{:value 3}` (in `:app1`) once `:initialise` runs. After the "+" button gets clicked the first time it is `{:value 4}`. Then `{:value 5}`, etc. re-frame2 calls this state `app-db` for reasons explained later.

## The event pipeline in one pass

Click **+**. What actually happens is one trip through the
[**event pipeline**](glossary.md#event-pipeline) — a fixed sequence of stages, not
a free-form cycle of callbacks:

1. **Dispatch.** `#(dispatch [:inc])` puts the event vector `[:inc]` on a FIFO
   queue and returns immediately. Dispatch does not run the handler.
2. **Dequeue.** Shortly after, the runtime takes the next event from the queue.
3. **Update phase.** It looks up the handler for `:inc`, builds a small **world** map
   (at minimum today's [app-db](glossary.md#app-db) under `:db`), and calls the
   handler: `(handler world event)`.
4. **Effects as data.** The handler returns `{:db next-map}` — a *description* of
   the next state. It does not mutate the old map.
5. **Commit phase.** The runtime executes those effects: the `:db` effect runs first,
   and the frame's app-db reference becomes the new map, atomically.
6. **Render phase.** Subscriptions whose inputs changed recompute. Views that
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
| Pipeline | Fixed fold: update → commit → render |

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
an app grows — isolating worlds, and moving derivations into event handlers (the update phase).
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