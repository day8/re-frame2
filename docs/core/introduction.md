# Introduction

On this page, a sketch of how re-frame2 does computation. 

## Events

We start with **events** because they are the language of your re-frame2 application. You design a set of events, and together they become its vocabulary.

The general shape of an event is:

```clojure
[:event-id & facts]
```

So, an event is a vector. The first element is an identifier, typically a namespaced keyword. Optionally, further facts are carried as vector elements — typically just one: a payload map.

Some examples:

```clojure
[:counter/inc]
[:article/loaded {:id 42}]
[:route/changed {:page :about :params {...}}]
```

Nothing "happens" in a re-frame2 app without an event. The app moves forward through time like this:

```text
event1 → event2 → event3 → event4 → ...
```

**Most events represent user intent**: the user clicked a button, dragged something somewhere else, opened a route, chose a tab. But while the user is the principal actor in a web app, other actors speak in events too: timers, browser APIs, route loaders, to name a few.

## Dispatch

You announce that an event has happened via the function `dispatch`:

```clojure
(require '[re-frame.core :refer [dispatch]])

(dispatch [:article/loaded {:id 42}])
```

Typically, such dispatches happen in a DOM event handler (the DOM being the browser's live tree of page elements):

```clojure
[:div {:on-click #(dispatch [:it :happened])}]
```

(That `[:div ...]` vector is not an event — it's a *view*, DOM written as data, explained below in The read side. The `#( ... )` is Clojure shorthand for an anonymous function.)

## The event queue

`dispatch` does not process the event then and there. Instead, the event is put into a FIFO (first-in, first-out) queue, to be **processed later**.

Shortly after, re-frame2 takes the next event from this queue and **processes it from beginning to end**.

```text
happens → enqueue → dequeue → event pipeline
```

## The event pipeline

re-frame2 never pauses halfway through processing an event to come back to it later. This is called **run-to-completion**.

Then, if there is another event in the queue, it processes that one too.

So the overall control flow in a re-frame2 app is simple. If events happen like this:

```text
event1 → event2 → event3 → event4 → ...
```

then computation happens like this — one run of the **event pipeline** (an *ep*) per event:

```text
ep1 → ep2 → ep3 → ep4 → ...
```

## Pipeline stages

The event pipeline itself decomposes into three phases:

1. **write side** — event handling: what should change?
2. **commit** — the world changes (including application state)
3. **read side** — the UI changes to match the new state of the application

## The write side

Event handling is conceptually this: `(world, event) → world'`

1. The `event handler` for the event is looked up in a registry. If the event was `[:thing1 {...}]`, then the event handler registered for `:thing1` is used.
2. That event handler declares which facts *about the world* it needs to do its job. These are called its `coeffects`.
3. Those facts are assembled into a map — the `world` shown above. It always includes the current application state (re-frame2 calls it `app-db`), and it may include other facts: a value from local storage, a fresh UUID, the current datetime.
4. The event handler function is called with `world` and the `event`. It computes and returns the set of changes which must be made — its `effects`. New application state? A new HTTP GET? Something else?

So an event handler does not change the world — it returns a *description* of the changes to be made: the effects. The `world'` of the formula only comes into being when those effects are applied — and that happens next, at commit.

How it looks in code (properly explained on the next page):

```clojure
(reg-event            ;; the re-frame2 API which allows you to register event handlers
  :event-id           ;; <- the event for which we are providing a handler
  {...}               ;; a map of metadata, including what facts the handler requires
  (fn [world event]   ;; the function to compute the effects of the event
    the-effects))
```

## Commit

The `effects` returned by the event handler have to be actioned. They have to be *done*. This part is impure: for example, new application state is committed to `app-db`, the HTTP request actually leaves the building, there's a call to `postMessage`.

How it looks in code (properly explained later, on the Effects page):

```clojure
(reg-fx               ;; the re-frame2 API which allows you to register side-effect handlers
  :fx-id
  (fn [ctx effect]   ;; ignore ctx for now
    ;; do something impure to make `effect` happen
    ))
```

## The read side

Back in 2014, when React (the JavaScript library re-frame2 renders through) wasn't trying to do too much, this part was written as the formula `v = f(s)`. Views are a function of state.

While that formula is true, there's a layered mechanism to it in re-frame2:

```text
view-model = subscribe(state)   ;; derive a projection of state suitable for use in a view (renderer)
vdom       = views(view-model)  ;; render a data representation of DOM (hiccup) using the view-model as input
dom        = reconcile(vdom)    ;; React does this part
```

The read side is reactive: when committed state changes, only the affected derivations recompute, and only the affected views re-render. `view-model` is computed as a graph of cached derivations (a memoised DAG, sometimes called a signal graph). You supply these derivations via `reg-sub`.

How it looks in code (properly explained on the next page):

```clojure
(reg-view greet []    ;; the re-frame2 API for creating views — a defn-like macro
  [:div "Hello, " @(subscribe [:name])])   ;; return DOM as data; subscribe returns a derefable projection of state — hence the @
```

## Handlers

When you program re-frame2, your job is to write handlers which slot into the event pipeline:

- `reg-event` — to supply event handlers (the write side)
- `reg-cofx` — to supply world facts (coeffects)
- `reg-fx` — to supply effect handlers (the impure commit step)
- `reg-sub` — to supply the derivations that turn state into `view-model`
- `reg-view` — to supply the views that turn `view-model` into vdom

## Programs

A program is a set of registrations. Yes, really — your handlers, registered by id, *are* the program.

## Frames

A `frame` is an isolated execution context:

- it manages its own event queue, application state, view-model DAG, and views, and it runs the event pipeline
- you have to give it handlers

Frames are cheap to create and tear down, which makes them ideal for unit tests — and for running several isolated instances of your app on one page.

## And that's a wrap

There are a few white lies of omission above, but that's all the concepts. We're now ready to write code.
