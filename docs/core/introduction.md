# Introduction

To understand re-frame2, you need to understand how it does computation.

Odd opener for a Single Page Application (SPA) library — hang on.

## A working app

Here's a tiny counter so the rest of this page has something real to point at:

- an increment button
- a displayed value (initially 3), incremented by each click

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

The whole app is four calls to functions that start with `reg-xxx`, each of the form:

```clojure
(reg-xxx id function)
```

- `reg-event` twice (two event handlers — more on those soon)
- `reg-sub` once (a subscription handler)
- `reg-view` once (a view)

**A re-frame2 app is a set of registrations.**

When the app is running — user clicks, DOM updates — the re-frame2 runtime looks those
functions up by `id` and calls them. That is the computation claim at the top of this
page: you write the functions that slot into that runtime.

## Running Counter

We've registered the Counter; we haven't run it yet.

To run it, create a `frame` — an isolated execution context:

```cljs-rf2
[:div {:style {:background "LavenderBlush"}}
   [rf/frame-root {:id :app1 :initial-events [[:initialise 3]]}
     [counter]]]
```

This is `hiccup` — a data structure that represents DOM. In this in-browser dev
environment, hiccup at the end of an interactive block is rendered, which is why the
app appears above.

That hiccup is:

- a `<div>` with a background style wrapping …
- a `frame-root` node that injects an **ambient frame** into the DOM tree (think Provider)
- a child `counter` view, registered above

When you see `frame-root`, think **Provider** in the **React Context** sense: a wrapping
DOM node that makes a `frame` ambiently available to the views beneath it.

Experiment/edit this code:

1. Change `"LavenderBlush"` to `"green"`
2. Change `[:initialise 3]` to `[:initialise 4]`

Press **`Ctrl-Enter`** / **`Cmd-Enter`** after edits.

## Isolated execution context

A `frame` holds:

- state — an immutable map, starts as `{}`
- a queue of events
- caches for performance

In the Counter, state starts as `{}`, then becomes `{:value 3}` in `:app1` once
`:initialise` runs. First "+" click → `{:value 4}`, then `{:value 5}`, and so on.
re-frame2 calls this state `app-db` (why, later).

## The event pipeline in one pass

Click **+**. What actually happens is one trip through the
[**event pipeline**](glossary.md#event-pipeline) — a fixed sequence of stages, not a
pile of free-form callbacks:

1. **Dispatch.** `#(dispatch [:inc])` puts `[:inc]` on a FIFO queue and returns
   immediately. Dispatch does **not** run the handler.
2. **Dequeue.** Shortly after, the runtime takes the next event from the queue.
3. **Update phase.** It looks up the handler for `:inc`, builds a small **world** map
   (at minimum today's [app-db](glossary.md#app-db) under `:db`), and calls
   `(handler world event)`.
4. **Effects as data.** The handler returns `{:db next-map}` — a *description* of the
   next state. It does not mutate the old map.
5. **Commit phase.** The runtime executes those effects: `:db` runs first, and the
   frame's app-db reference becomes the new map, atomically.
6. **Render phase.** Subscriptions whose inputs changed recompute. Views that depend
   on those values re-render. React reconciles the DOM.

```text
click → dispatch → queue → handler → {:db …} → commit → subs → views → DOM
```

Every re-frame2 app is that **pipeline**, once per event. Nothing moves without an
event. Time advances as:

```text
event₁ → event₂ → event₃ → …
```

and each event is one full pipeline **run**:

```text
pipeline → pipeline → pipeline → …
```

The pipeline is **fixed**. Every event — yours, the framework's, a timer at 3am —
travels the same stages in the same order. Your handlers are Turing-complete; the
structure between them is not. That restriction is what later buys replay, time
travel, and tests without mocks.

## Events are data

An event is a vector. The head is an id (usually a namespaced keyword). Optional
further elements carry facts — typically one payload map:

```clojure
[:inc]
[:article/loaded {:id 42}]
[:route/changed {:page :about :params {...}}]
```

Events are usually **user intent** (click, type, navigate), but anything can speak
them: timers, HTTP replies, route loaders. The next page, [Events](events.md), is
the vocabulary in full.

## One map of state: app-db

Each running instance holds application state as one immutable Clojure map —
**app-db**. In the counter it starts `{}`, becomes `{:value 3}` after `:initialise`,
then `{:value 4}` after the first `:inc`.

Handlers never "set state" as a side effect. They return the next map inside an
effect description. [app-db](app-db.md) owns that doctrine.

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

The left nav is the learning order — this page won't restate the roster. What matters
is the *shape* of that order: each stage leans only on the one before it.

You start with the **pure pipeline** — the whole model, no impurity yet, enough to
build real screens. Then **impurity** lets handlers reach the world (HTTP, storage,
timers, recorded facts) while staying pure about it. **Structure** arrives once an
app grows — isolating worlds, moving derivations into event handlers (the update
phase). **Operations** is for when something breaks. **Advanced** corners come last;
most apps never need them.

Every concept page teaches the rules that matter first and marks optional depth under
**Advanced**, so you can stop and ship at any stage. How-to recipes, testing, and
"why it's built this way" essays sit alongside — reach for them when you have a task
or a design question, not as a first read-through.

### Working path and loud failures

Every stage teaches a **working path** first (a counter grown one idea at a time).
Each concept page also names the **loud failures** you'll meet early — missing
handler, missing sub, no frame on a callback, effect-map typos — so recovery is
vocabulary, not a midnight scavenger hunt.
