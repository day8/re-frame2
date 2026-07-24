# Run to completion

The idea — and the live demo — live on
[Effects: run to completion](effects.md#run-to-completion). Read that first: it is
where follow-up events make the drain visible.

This page is **operational detail**: what happens when a drain runs away, how
`dispatch-sync` differs from `dispatch`, and how destroy cuts the queue off.

> **Update and commit run per event; nothing renders until the queue settles — and
> then it renders once.**

## When the drain won't stop

What if a handler dispatches an event whose handler dispatches the first one again?
A cycle that never finishes. The runtime won't let it. Each frame carries a
**`:drain-depth`** — the maximum number of events one drain may process (default
`100`). When a drain reaches it, the runtime stops with a loud, machine-readable
[error record](glossary.md#error-record):

```clojure
{:operation :rf.error/drain-depth-exceeded
 :frame     :main
 :tags      {:depth      100                    ; events already settled this drain
             :queue-size 7                      ; events dropped, unrun
             :last-event [:the-last-event-that-ran]}}
```

Atomicity in re-frame2 is per [**event**](glossary.md#commit), not per drain — every
event the drain already settled *keeps* its app-db write and its history row. The
runtime discards the remaining queued events, traces
`:rf.error/drain-depth-exceeded`, and leaves the frame at the last settled state. In
Xray you'll see the settled rows followed by a single `:halted-depth` marker.

!!! note "The bound is per-frame and tunable"

    Each frame can set its own. Story pins live-demo frames to `16` (fail fast); the
    `:test` frame preset pins the default `100` explicitly. Raise it only when a
    frame legitimately fans out wide — a drain that needs hundreds of synchronous
    events is usually a cycle in disguise.

## Destroy is a terminal cutoff

A successful `destroy-frame!` claim does not forcibly interrupt an authored callback
already on the stack: that callback may return and already-entered interceptor
`:after` callbacks may unwind. Its returned context is inert, however — no
framework-owned commit, flow, effect, child dispatch, ordinary diagnostic, normal
epoch settlement, or render follows. The claim atomically discards pending ordinary
work. No read/render phase is inserted at the cutoff.

## `dispatch-sync`

Inside a handler, you never call `dispatch` directly — you return
`:fx [[:dispatch …]]` and let the runtime queue it. Outside any handler there is
nothing to return effects to, so you call directly — every `:on-click` does that
fire-and-forget. Sometimes the caller needs the drain settled before its next line
runs (app startup, a test fixture, the REPL). That's
[**`dispatch-sync`**](glossary.md#dispatch-sync):

```clojure
(rf/dispatch-sync [:app/initialise])
;; By the time this line returns, the whole drain has settled.
```

`dispatch-sync` runs the same run-to-completion drain as `dispatch`, but blocks until
the drain settles.

Call it from inside a handler and you'll get
`:rf.error/dispatch-sync-in-handler` — under run-to-completion the drain is already
running synchronously. The in-handler shape for a follow-up is always
`:fx [[:dispatch event]]`.

!!! warning "Gotcha — a dispatch needs a frame in scope"

    Both `dispatch` and `dispatch-sync` resolve which [frame](glossary.md#frame) to
    target from scope — a provider, a running handler, or a
    [capture-frame](glossary.md#capture-frame). A rootless async callback raises
    `:rf.error/no-frame-context`. Fix: grab a `capture-frame` while the frame is in
    scope, or pass `{:frame <id>}` in the dispatch opts. Full story:
    [Frames](frames.md).

??? info "From re-frame v1"

    There is no `^:flush-dom` and no queue-pause-for-render — the drain never stops
    mid-run to let a paint through. The v1 use case — "show this, *then* run the
    heavy block" — is served by a `dispatch-later` with `{:ms 0}`, which lets one
    paint land before the next event runs. See
    [From re-frame v1](25-from-re-frame-v1.md).
