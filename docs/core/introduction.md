# Introduction

A re-frame2 app is a set of functions you register with the framework. The
framework calls them, in a fixed order, each time something happens. Once you know
that order, you know how every re-frame2 app computes.

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

The whole app is four calls to `reg-xxx` functions, each pairing an id with a
function:

- `reg-event` twice (two event handlers)
- `reg-sub` once (a subscription)
- `reg-view` once (a view; it is shaped like `defn`, and its name becomes the id)

Inside a `reg-view` body, `subscribe` and `dispatch` are provided for you:
`@(subscribe [:value])` reads the current value, and `(dispatch [:inc])` reports a
click.

While the app runs, the re-frame2 runtime looks those functions up by id and calls
them as clicks arrive and the DOM updates. You write the functions; the runtime decides
when they run.

## Running the counter

We've registered the counter; we haven't run it yet. To run it, mount it under a
**frame** — an isolated execution context:

```cljs-rf2
[:div {:style {:background "LavenderBlush"}}
   [rf/frame-root {:id :app :initial-events [[:initialise 3]]}
     [counter]]]
```

This is **hiccup**, a data structure that represents DOM (the next page covers it).
In this in-browser environment, hiccup at the end of an interactive block is
rendered, which is why the app appears above. It describes:

- a `<div>` with a background style, wrapping …
- a `frame-root`, which creates the `:app` frame and makes it available to every view
  beneath it (think of a React Context **Provider**)
- the `counter` view registered above

Edit the code and press **`Ctrl-Enter`** / **`Cmd-Enter`** to re-evaluate:

1. Change `"LavenderBlush"` to `"green"`
2. Change `[:initialise 3]` to `[:initialise 4]`

## What a frame holds

A [**frame**](glossary.md#frame) holds:

- state — one immutable map, which starts as `{}`
- a queue of events
- caches for performance

re-frame2 calls that state map **app-db**. In the counter, app-db starts as `{}`,
becomes `{:value 3}` once `:initialise` runs, then `{:value 4}` after the first click,
and so on.

`frame-root` creates its frame once, runs `:initial-events` once, and scopes the
subtree so `dispatch` and `subscribe` inside the view reach that frame. Until the
[Frames](frames.md) page, that form is the whole boot story. Packaging a real app
(`init!`, hot reload, listeners) is covered in
[Boot and mount an app](how-to/boot-and-mount-an-app.md). Giving frames different
sets of registrations is rare; [Images](images.md) covers it.

## The event pipeline in one pass

Click **+**. What happens is one run of the
[**event pipeline**](glossary.md#event-pipeline), a fixed sequence of stages:

1. **Dispatch.** `#(dispatch [:inc])` puts `[:inc]` on the frame's FIFO queue and
   returns immediately. Dispatch does not run the handler.
2. **Dequeue.** Shortly after, the runtime takes the next event from the queue.
3. **Update phase.** It looks up the handler for `:inc`, builds a small **world** map
   (at minimum the current [app-db](glossary.md#app-db) under `:db`), and calls
   `(handler world event)`.
4. **Effects as data.** The handler returns `{:db next-map}` — a description of the
   next state. It does not mutate the old map.
5. **Commit phase.** The runtime executes those effects: `:db` goes first, and the
   frame's app-db becomes the new map, atomically.
6. **Render phase.** Subscriptions whose inputs changed recompute. Views that depend
   on those values re-render. React updates the DOM.

```text
click → dispatch → queue → handler → {:db …} → commit → subs → views → DOM
```

Nothing changes without an event, and every event (a click, an HTTP reply, a timer)
goes through the same stages in the same order. Your handlers can compute anything,
but the order they are called in is fixed. That fixed order is what makes replay,
time travel, and testing without mocks possible later.

Put another way, app state is a reduction over events:

```text
app-state = reduce(event-pipeline, initial-state, events)
```

## Events are data

An event is a vector. The head is an id, usually a namespaced keyword. Further
elements carry the facts the handler needs:

```clojure
[:inc]
[:inc-by {:n 5}]
[:todo/toggle 1]
```

Events usually record user intent (click, type, navigate), but timers, HTTP replies,
and route loaders dispatch them too. [Events](events.md) covers them in full.

## In summary

| Piece | Job |
|---|---|
| Registrations | The functions you supply (`reg-event`, `reg-sub`, `reg-view`, …) |
| Events | Vectors describing what happened |
| Frame | One running app: app-db + queue + caches |
| Event pipeline | The fixed stages each event goes through: update → commit → render |

The Core pages build this pipeline up one stage at a time. The counter carries the
examples through [app-db](app-db.md); from [Subscriptions](subscriptions.md) on, where
there is more data to derive from, they use a small todo list.
