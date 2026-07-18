# Introduction

To understand re-frame2, you need to understand how it does computation.

Which is an odd opening for a Single Page Application (SPA) library, I know, but bear with me.

## A working app

To assist explanations, here's a tiny "counter" application. It has:

- an increment button
- a displayed value (initially 3), incremented by each button click

```cljs-rf2
;; The re-frame2 core API namespace
(require '[re-frame.core :as rf])

;; calls to four registration functions
(rf/reg-event :initialise (fn [_ [_ v]] {:db {:value v}}))
(rf/reg-event :inc        (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-sub   :value      (fn [db _] (:value db 0)))

(rf/reg-view counter []
  [:div 
   [:span "Count: " @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]])
```

The entire application is 4 calls to functions starting with `reg-xxx` and all four of them have the form:
```clojure
(reg-xxx id function)
```

- `reg-event` is called twice (to register two event handlers, and, yes, more on this soon)
- then, `reg-sub` is called once (to register a subscription handler)
- and finally, `reg-view` is called once (to register a view)

**A re-frame2 app is a set of registrations**.

Later, when your re-frame2 application is running, and the user is clicking buttons and seeing new DOM, etc., the re-frame2 runtime will look up these functions by their `id` and call them, and **THAT** is why you need to understand how re-frame2 does computation (the claim at the top of the page).  

You need to understand how to write functions that slot into the re-frame2 runtime.

## Running Counter

We might have created the Counter app (via those 4 registrations), but we haven't run it yet.

To do that, we need to create a `frame`, which provides an isolated execution context. And, here's how:

```cljs-rf2
[:div {:style {:background "LavenderBlush"}}
   [rf/frame-root {:id :app1 :initial-events [[:initialise 3]]}
     [counter]]]
```

This code is `hiccup` — a data structure representing DOM. And in this in-browser dev environment, hiccup at the end of an interactive block is rendered, which is why we see the DOM for the app above.

That hiccup is:

- a `<div>` which has a background style and which wraps ...
- a `frame-root` node which injects an **ambient frame** into the DOM tree (think Provider)
- a child `counter` view, previously registered above 

When you see `frame-root` think of **Provider** in the **React Context** sense. A `frame-root` is like a wrapping DOM node which provides context to its child views — in this case a `frame` (an isolated execution context) is ambiently available to the `counter` view beneath it.

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
**app-db**. In the counter it starts `{}`, then becomes `{:value 3}` after
`:initialise`, then `{:value 4}` after the first `:inc`.

Handlers never "set state" as a side effect. They return the next map inside an
effect description. [app-db](app-db.md) is the dedicated page for that doctrine.

## Frames: one running world

The counter mounts under:

```clojure
[rf/frame-root {:id :app :initial-events [[:initialise 3]]}
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