# Run to completion

The runtime processes dispatched events until the queue is empty, and only then
renders, once. [Effects](effects.md#run-to-completion) introduces the idea with a
live demo. This page covers the operational details: what happens when a drain runs
away, how `dispatch-sync` differs from `dispatch`, and what `destroy-frame!` does to
a running drain.

## When the drain won't stop

If a handler dispatches an event whose handler dispatches the first one again, the
drain would never finish. Each frame has a **`:drain-depth`**, the maximum number of
events one drain may process (default `100`). When a drain reaches it, the runtime
stops and emits an always-on [error record](glossary.md#error-record):

```clojure
{:error             :rf.error/drain-depth-exceeded
 :frame             :app
 :depth             100                        ; events already settled this drain
 :queue-size        7                          ; events dropped, unrun
 :last-event-id     :todo/sync                 ; id of the last event that ran
 :tail-event-ids    [:todo/sync :todo/save …]  ; recent ids; the repeating run names the cycle
 :dropped-event-ids [:todo/save …]
 :rollback?         false
 …}
```

The record carries event ids only, never event arguments. In dev builds the trace
stream also gets a `:rf.error/drain-depth-exceeded` trace with the full last event and
a readable `:reason`.

The [commit](glossary.md#commit) is per event, not per drain: every event the drain
already settled keeps its app-db write and its epoch. The runtime discards the
remaining queued events and leaves the frame at the last settled state. In Xray you'll
see the settled rows followed by a single `:halted-depth` marker.

!!! note "The bound is per-frame and tunable"

    Set `:drain-depth` in the frame config. The `:story` frame preset uses `16`, so
    a runaway demo fails fast; the `:test` preset sets the default `100` explicitly.
    Raise it only when a frame legitimately fans out wide — a drain that needs
    hundreds of synchronous events is usually a cycle.

## Destroy ends the drain

`destroy-frame!` discards the frame's queued events immediately. It does not
interrupt code already running: a handler on the stack may return, and interceptor
`:after` functions already entered still run. But nothing that code produced takes
effect — no commit, no flows, no effects, no child dispatches, no render. The frame's
`:on-destroy` event, if it has one, then runs ([Frames](frames.md#ending-and-resetting-a-frame)).

## `dispatch-sync`

Inside a handler you never call `dispatch`; you return `:fx [[:dispatch …]]`.
Outside a handler you call `dispatch` directly, as every `:on-click` does, and it
returns at once. When the caller needs the drain settled before its next line runs
(a test, the REPL), use [**`dispatch-sync`**](glossary.md#dispatch-sync):

```clojure
(rf/dispatch-sync [:todo/add "Buy milk"] {:frame :app})   ;; at the REPL, name the frame
@(rf/subscribe [:todo/all] {:frame :app})                  ;; already includes "Buy milk"
```

`dispatch-sync` runs the same drain as `dispatch`, but returns only after it
settles. It does not rethrow a handler's exception: it returns normally, and the
failure becomes an error record (`:rf.error/handler-exception`, nothing committed).
A test asserts on the resulting state or on that record.

Called from inside a handler, it drops the event, because a drain is already
running; the handler carries on, and a dev build reports
`:rf.error/dispatch-sync-in-handler`. Return `:fx [[:dispatch event]]` instead. (A
`dispatch-sync` aimed at a *different* frame is allowed, with a warning; see
[Frames](frames.md#cross-frame-dispatch-sync-during-a-drain).)

!!! warning "Gotcha — a dispatch needs a frame in scope"

    Both `dispatch` and `dispatch-sync` get their [frame](glossary.md#frame) from
    scope: a `frame-root` during render, a running handler, `with-frame`, or a
    [`capture-frame`](glossary.md#capture-frame) frame api. From an async callback
    with none of these they raise `:rf.error/no-frame-context`. Capture the frame
    while it is in scope, or pass `{:frame <id>}` in the dispatch options. See
    [Frames](frames.md#the-async-boundary-capture-the-frame).

??? info "From re-frame v1"

    There is no `^:flush-dom`: the drain never pauses mid-run to let a paint
    through. For "show this, *then* run the heavy work", return a
    `[:dispatch-later {:ms 0 :event [...]}]` row. The current drain ends and views
    re-render before the next event runs. See [From re-frame v1](25-from-re-frame-v1.md).
