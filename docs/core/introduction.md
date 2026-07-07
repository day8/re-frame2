# Introduction

To understand re-frame2, you need to understand how it does computation.  Which is an odd opening for an SPA library, I know. Bear with me.

To assist explanations, we'll use a small application: a counter app — a plus button, and a value that starts at 0. 

## The counter app

And here's the re-frame2 code for that app:

```cljs-rf2
(ns first-app.counter
  (:require [re-frame.core :as rf]))

;; call reg-event to register an event handler 
(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value (fnil inc 0))}))

;; call reg-sub to register a `subscription`
(rf/reg-sub :value
  (fn [db _query]
    (:value db 0)))

;; call reg-view to register a view 
(rf/reg-view counter [background]
  [:div {:style {:background background}} "Counter: "
   [:span @(subscribe [:value])]
   [:button {:on-click #(dispatch [:inc])} "+"]])
```

At this early point, all you need to note is that this application is a series of function calls, starting with `reg-`: 

  - `reg-event` is called once to register an `event handler`
  - `reg-sub` is called once to register a `subscription` 
  - `reg-view` is called once to register a `view`

**A re-frame2 app is a set of registrations.**   These registered functions each have an `id` and they are looked up and called by the re-frame2 computational runtime.  

Aside: re-frame2 uses the term `image` for a collection of registrations. But more on that soon.

## Running an app

We might have created the Counter app (via those mysterious registrations), but we haven't run it yet.  To do that, we need to create a `frame`, which provides an isolated execution context. 

In fact, to double the excitement, let's create two `frames` and have two instances of our Counter app on the page at the same time: one with a yellow background and one with a green background:

```cljs-rf2
(ns first-app.main
  (:require [re-frame.core :as rf]
            [first-app.counter :refer [counter]]))

[:div
  [rf/frame-provider {:id :app1}
    [counter "yellow"]]
  [rf/frame-provider {:id :app2}
    [counter "green"]]]
```

This code block ends with some `hiccup` (a data structure representing DOM).  In this in-browser dev environment, trailing hiccup gets rendered. That hiccup is: 

  - a `[:div ...]`
  - containing two child `frame-providers` 
  - each `frame-provider` wraps the previously registered view `counter`, like this: `[counter "a colour"]` 
  
When you see `frame-provider` think of **Provider** in the **React Context** sense. It is like a view node which provides context to its child views — in this case a `frame` (an isolated execution context) is ambiently available to each of the two `counter` views. 

And so, we have two early points to grasp:

  - an app is a set of registrations  (an `image`)
  - apps run inside `frames`, which provide the execution context.

Let's backtrack a bit now, and build up from the basics. 

## Events

We start with **events** because they are the language of your re-frame2 application. You design a set of events, and together they become its vocabulary.

The general shape of an event is:

```clojure
[:event-id & facts]
```

So, an event is a vector. The first element is an identifier, typically a namespaced keyword. Optionally, further facts are carried as vector elements — typically just one element: a payload map.

Some examples:

```clojure
[:some-id]
[:article/loaded {:id 42}]
[:route/changed {:page :about :params {...}}]
```

Nothing "happens" in a re-frame2 app without an event. The app moves forward through time like this:

```text
event1 → event2 → event3 → event4 → ...
```

**Events might be inert facts, but they usually represent user intent**: the user clicked a button, dragged something somewhere else, opened a route, chose a tab. But while the user is the principal actor in a web app, other actors speak in events too: timers, browser APIs, route loaders, to name a few.

In our Counter app there is only one event: `[:inc]`. It represents the fact that the user clicked the "+" button. Their intent is for the value to be incremented.

## Dispatch

You announce that an event has happened using the function `dispatch`. 

In the case of `Counter`:

```clojure
(dispatch [:inc])
```

Typically, a dispatch happens in a DOM event handler (but not always). In our Counter app, the view has this for the "+" button: 

```clojure
[:button {:on-click #(dispatch [:inc])} "+"]
```

(The `#( ... )` is Clojure shorthand for an anonymous function.)

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

In the Counter app:

```clojure
(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value (fnil inc 0))}))
```

You'll notice that `world` (the first argument) is destructured: `{:keys [db]}` pulls out the `:db` entry, which is `app-db` — the map holding the app's current state. That's the only fact this handler needs, so it's the only one it asks for.

It doesn't change `db` in place. `(fnil inc 0)` is a function that increments `:value` — or starts it at 0 the first time, before `:value` exists — and `update` uses it to produce a *new* map. The handler wraps that new map up as `{:db <new value>}` and returns it.

That returned map is the handler's `effects` — a description of what should change. Its `:db` key says "app-db should become this new value." The handler computes the new value but doesn't commit it. That happens next.

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

In the Counter app, you won't find a `reg-fx` anywhere — and that's the common case. The `:db` effect our `:inc` handler returned is *built in*: re-frame2 ships a handler for it that commits the new value to `app-db` for you. You write your own `reg-fx` only when the app needs an impure effect of its own — a write to local storage, a `postMessage`, an HTTP request. Counter needs none, so its commit step is entirely re-frame2's.

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

In the Counter app:

```clojure
(rf/reg-sub :value
  (fn [db _query]
    (:value db 0)))

(rf/reg-view counter [background]
  [:div {:style {:background background}} "Counter: "
   [:span @(subscribe [:value])]
   [:button {:on-click #(dispatch [:inc])} "+"]])
```

`reg-sub :value` is the *derive* step: given `app-db`, it returns `:value` — the one number the view needs. Here that's a trivial lookup, but subscriptions earn their keep as apps grow.

`reg-view counter` is the *views* step. It returns hiccup, and `@(subscribe [:value])` reads the derived value — that's the job of the `@`. When `:value` changes, this subscription recomputes and the view re-renders with the new number.

And that closes the loop. Click "+": `dispatch` queues `[:inc]`, the pipeline runs the handler (write side), the new `app-db` is committed, the `:value` subscription recomputes, and the view shows one more. Every re-frame2 app is that loop, running over and over.

## Handlers

When you program re-frame2, your job is to register handlers which slot into the event pipeline:

- `reg-event` — to supply event handlers (the write side)
- `reg-cofx` — to supply world facts (coeffects)
- `reg-fx` — to supply effect handlers (the impure commit step)
- `reg-sub` — to supply the derivations that turn state into `view-model`
- `reg-view` — to supply the views that turn `view-model` into vdom

## Programs and images

A `program` is a set of registrations. Yes, really — your handlers, registered by id, *are* the program.

And now that earlier aside can pay off. Take that set of registrations as a *value* — a thing you can name and pass around, holding no state of its own — and you have what re-frame2 calls an `image`. Most apps have exactly one, assembled for you from everything you've registered: the *default image*, "all the handlers already loaded." You only name an image yourself when different frames need different behaviour — a test frame with fake effects, say, or two examples that reuse the same event ids. So: `program` is the idea, `image` is that idea reified as a value. For the rest of this guide, "the default image" is all you need.

## Frames

A `frame` is an isolated execution context:

- it manages its own event queue, application state, and view-model DAG, and it runs the event pipeline
- it resolves its behaviour from an `image` — the default one, unless you name another

That's the rule worth keeping: **the image supplies behaviour; the frame supplies state.** It's why the two counters at the top of this page could share one program and still count independently — same image, two frames, two separate app-dbs.

Frames are cheap to create and tear down, which makes them ideal for unit tests — and, as you've seen, for running several isolated instances of your app on one page.

## In summary

re-frame2 computes in a particular way: it is an event-sourced fold. 

It is pluggable by functions. Your registrations are the plugins. 

At the top of this page, I said you needed to know how re-frame2 computes. Now you do. 

But I have kept one important thing from you.  I wonder if you noticed it?  The next page will reveal all. 
