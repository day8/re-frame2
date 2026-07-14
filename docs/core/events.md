# Events

The [introduction](introduction.md) closed the loop once. This page is the
**language** of that loop: what events look like, how you announce them, and what
handlers are allowed to do.

> **Nothing moves without an event. Design the event set and you design the app.**

## Event shape

An event is a **vector**. The first element is the id — almost always a namespaced
keyword. Further elements are optional facts; the common case is one payload map:

```clojure
[:inc]
[:cart/add {:sku "A" :qty 1}]
[:article/loaded {:id 42 :title "…"}]
[:route/changed {:page :about :params {}}]
```

Ids are the vocabulary of the application. Prefer names that say what *happened*
(`:cart/item-added`) or what was *intended* (`:cart/add`), not how a view is
implemented.

There is no second channel. Timers, HTTP replies, route loaders, and button clicks
all speak this shape. That uniformity is why tools can show one timeline for the
whole app.

## Dispatch

You announce an event with `dispatch`:

```clojure
(dispatch [:inc])
(dispatch [:cart/add {:sku "A" :qty 1}])
```

Inside a `reg-view`, `dispatch` is injected for you (same for `subscribe`):

```clojure
[:button {:on-click #(dispatch [:inc])} "+"]
```

**Dispatch does not run the handler.** It enqueues the event on the frame's FIFO
queue and returns immediately. The runtime dequeues later and runs the full
pipeline for that event. That separation keeps UI handlers thin and keeps the
write path single-threaded.

```text
happens → enqueue → dequeue → event pipeline
```

Need the pipeline to finish before your next line of code? Reach for
`dispatch-sync` only when you must (boot, some interop). Prefer ordinary
`dispatch` in views. (The full drain story lives with
[Effects](effects.md#run-to-completion).)

!!! warning "No ambient frame"

    `dispatch` must know which [frame](glossary.md#frame) owns the queue. Inside a
    view under `frame-root` / `frame-provider`, that is automatic. From a bare
    `setTimeout` or a foreign callback with no frame in scope, bare `dispatch`
    raises `:rf.error/no-frame-context`. Carry a frame (or a `capture-frame`
    bundle) out of the tree — [Frames](frames.md) covers the pattern.

## Handlers return descriptions

Register a handler with `reg-event`. The handler receives the **world** (coeffects
map — at minimum `{:db current-app-db}`) and the event vector, and returns an
**effect map**:

```clojure
(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value inc)}))
```

Rules that matter from day one:

1. **Pure.** Same inputs, same returned map. No `js/fetch`, no `swap!`, no reading
   the clock. Impurity is *described* and performed later ([Effects](effects.md);
   recorded inputs are [Coeffects](coeffects.md)).
2. **`:db` is the next app-db value**, not a patch instruction. Use `assoc`,
   `update`, `update-in` — functions that return a *new* map.
3. **The runtime commits.** Your function proposes; the pipeline applies.

A handler may return other effect keys (`:fx`, …) alongside or instead of `:db`.
It may return no `:db` and leave state alone. The state doctrine lives on
[app-db](app-db.md); the to-do list beyond `:db` lives on [Effects](effects.md).

### Metadata when you need it

`reg-event` accepts an optional metadata map between the id and the function —
schemas, required coeffects, interceptors. Until you need that, the two-argument
form is enough. The first useful metadata form is on
[Coeffects](coeffects.md).

## What events are not

| Not this | Why |
|---|---|
| A place to put view logic | Views stay pure; they dispatch and subscribe |
| A free-form message bus between components | Components don't address each other — they write app-db through events |
| Something you `await` | Async replies arrive as **later** events ([Effects](effects.md), [Async](../async/index.md)) |
