# Events

Every state change in a re-frame2 app starts with an event. This page covers what an
event looks like, how you dispatch one, and what its handler may do.

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

Timers, HTTP replies, route loaders, and button clicks all use this same shape, so
tools can show everything that happened to the app as one list of events.

## Dispatch

You send an event with `dispatch`. Inside a `reg-view`, `dispatch` (and
`subscribe`) are provided for you:

```clojure
[:button {:on-click #(dispatch [:inc])} "+"]
```

Outside a view, for example at the REPL, call `rf/dispatch` and name the frame:

```clojure
(rf/dispatch [:inc] {:frame :app})
```

Dispatch does not run the handler. It enqueues the event on the frame's FIFO
queue and returns immediately. The runtime dequeues it shortly after and runs the
[event pipeline](glossary.md#event-pipeline) for it. Because only the runtime runs
handlers, UI callbacks stay thin and state is written one event at a time.

```text
happens → enqueue → dequeue → event pipeline
```

If the pipeline must finish before your next line of code runs, use `dispatch-sync`.
It belongs at boot, in tests, and at the REPL; calling it from inside a running
handler raises `:rf.error/dispatch-sync-in-handler`. Use ordinary `dispatch` in
views. Queue draining is covered in
[Effects](effects.md#run-to-completion).

!!! warning "No ambient frame"

    `dispatch` must know which [frame](glossary.md#frame) owns the queue. Inside a
    view under `frame-root` / `frame-provider`, that is automatic: the `dispatch` a
    `reg-view` provides has already captured its frame, so it still works when
    called later from a timeout. A bare `rf/dispatch` from a `setTimeout`, a
    promise or another callback with no frame in scope raises
    `:rf.error/no-frame-context`.

    ```clojure
    ;; Inside a reg-view: the provided dispatch carries the frame
    [:button {:on-click #(js/setTimeout (fn [] (dispatch [:inc])) 1000)} "+ later"]

    ;; Outside any view: name the frame
    (js/setTimeout #(rf/dispatch [:inc] {:frame :app}) 1000)
    ```

    [Frames](frames.md) covers carrying a frame with `rf/capture-frame`.

## Handlers return descriptions

Register a handler with `reg-event`. The handler receives the **world** map (at
minimum `{:db current-app-db}`) and the event vector, and returns an **effect map**:

```clojure
(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value inc)}))
```

The second argument is the whole event vector, so a handler that needs the payload
destructures it:

```clojure
(rf/reg-event :inc-by
  (fn [{:keys [db]} [_ {:keys [n]}]]
    {:db (update db :value + n)}))

;; dispatched as [:inc-by {:n 5}]
```

The handler rules:

1. **Pure.** Same inputs, same returned map. No `js/fetch`, no `swap!`, no reading
   the clock. Impurity is *described* and performed later ([Effects](effects.md);
   recorded inputs are [Coeffects](coeffects.md)).
2. **`:db` is the next app-db value**, not a patch instruction. Use `assoc`,
   `update`, `update-in` — functions that return a *new* map.
3. **The runtime commits.** Your function returns the next value; the pipeline
   writes it.

A handler may return other effect keys (`:fx`, …) alongside or instead of `:db`.
It may return no `:db` and leave state alone. The state rules live on
[app-db](app-db.md); effects other than `:db` are covered in [Effects](effects.md).

### Metadata when you need it

`reg-event` accepts an optional metadata map between the id and the function —
a `:doc` string, a payload `:schema`, required coeffects, interceptors. Until you
need that, the two-argument form is enough. [Coeffects](coeffects.md) shows the first
metadata you are likely to use.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Button "does nothing" | You dispatched an id nobody registered | The runtime reports `:rf.error/no-such-handler` naming the id and skips the event. Register the handler or fix the typo |
| Callback throws from a timer / fetch | Bare `dispatch` outside a frame | `:rf.error/no-frame-context`. Capture the frame ([Frames](frames.md)) |
| Handler can't be unit-tested | You called `js/fetch` / read the clock inside the body | Return the request as an effect ([Effects](effects.md)); declare the clock as a coeffect ([Coeffects](coeffects.md)) |

An unregistered id is reported rather than thrown so that one missing handler, for
example after a failed feature load, does not crash the whole app.

## What events are not

| Not this | Why |
|---|---|
| A place to put view logic | Views stay pure; they dispatch and subscribe |
| A message bus between components | Components don't address each other; they change app-db through events |
| Something you `await` | Async replies arrive as **later** events ([Effects](effects.md), [Async](../async/index.md)) |
