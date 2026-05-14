# `testbeds/multi-frame`

A three-frame Reagent app — two counter frames (`:counter/a`,
`:counter/b`) plus an append-only `:log` frame — with a Cross-bump
button whose handler runs on `:counter/a` and fans out a cross-frame
`:dispatch` to `:counter/b` and `:log` in a single drain.

The point: a consumer (Causa, Story, pair2-mcp) observes that the
framework keeps each frame's `app-db`, signal-graph cache, and epoch
ring buffer cleanly partitioned — even when a single click produces
events that route to three distinct frames.

| Button | `data-testid` | What it does | What a consumer should see |
|---|---|---|---|
| Inc A | `inc-A` | Local dispatch against `:counter/a`. | One epoch in `:counter/a`'s ring buffer; the `:counter/a` `:n` sub recomputes; `:counter/b` and `:log` sub-caches untouched. |
| Inc B | `inc-B` | Local dispatch against `:counter/b`. | Mirror image of the above on `:counter/b`. |
| Cross-bump | `cross-bump` | Dispatches `::cross-bump` against `:counter/a`; the handler returns a `:db` update plus `:fx [[:dispatch ... {:frame :counter/b}] [:dispatch ... {:frame :log}]]`. | Three `:event/dispatched` traces in one drain (one per frame); three distinct epoch records; the log entry's payload carries `:from :counter/a` verbatim — evidence the framework didn't rewrite the source-frame identity on the hop. |

## Why three frames

Three is the smallest count that exhibits all the distinct partitions
a tool needs to distinguish:

- **Two of the same shape** (`:counter/a` and `:counter/b`) — proves
  isolation of two frames that share the *same handler set* and
  *same app-db shape*. A consumer that conflates them by handler-id
  or by sub-key fails here.
- **One distinct shape** (`:log`) — proves the frame plurality
  contract is genuinely independent of handler set; the log frame's
  app-db has no `:n` key at all. A consumer that assumes "all frames
  have a counter" fails here.

## Cross-frame dispatch — the load-bearing pattern

The Cross-bump handler is `reg-event-fx`; its `:fx` carries reserved
`:dispatch` entries with explicit `{:frame ...}` opt maps (per
[spec/002 §Cascade propagation](../../spec/002-Frames.md)). The fx
walker (`do-fx`) calls `dispatch!` with the requested frame, so each
queued event lands on the right router queue without per-frame
plumbing in the handler body.

A consumer that reads the trace stream sees the three dispatches in
**source order** (per [spec/002 §`:fx` ordering and serial
execution](../../spec/002-Frames.md)) — the handler's `:db` commits
first, then `[:dispatch ... :counter/b]` fires, then `[:dispatch
... :log]` fires. The three frames' `app-db` writes interleave in
that order against their respective `app-db`s.

## What's deliberately *missing*

- No `subscribe` directly references another frame's value. Cross-
  frame subscription is supported (per [spec/002 §Cross-frame
  subscription](../../spec/002-Frames.md)) but it's a different
  contract from cross-frame dispatch; this surface exists to
  exercise dispatch isolation. A future testbed can layer cross-
  frame subs.
- No `destroy-frame!` lifecycle. The three frames are created on app
  init and live forever; the destroy contract is exercised by tests
  that own the lifecycle (per [spec/002 §Per-instance frames]).
- No `:fx-overrides` per frame. Per-frame fx replacement is a
  separate contract; conflating it with the cross-dispatch shape
  would dilute what this surface proves.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- **Multi-frame app shows separate epoch ring buffers per frame** —
  the load-bearing scenario this surface unblocks. After one
  Cross-bump click, Causa's trace panel must show three distinct
  epoch records, one per frame, with the correct `:frame` tag on
  each.
- Trace panel populates on first dispatch — exercised three times
  per click on this surface, once per frame.
- Time-travel scrub forward/back mutates visible UI — needs to scrub
  three frames independently; per-frame scrubbing exercises here.

**Story (18)**:
- Time-travel scrub within story preserves frame isolation — Story
  variants that mount this surface verify the scrubber doesn't leak
  state across the three frames.

**Cross-cutting (6)**:
- Subscribe → re-render → trace ordering preserved — exercised
  per-frame on the inc-A / inc-B paths.

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/multi-frame
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/multi-frame`; output lands in
`implementation/out/testbeds/multi-frame/`.

## Cross-references

- [`spec/002-Frames.md` §What lives in a frame](../../spec/002-Frames.md) — the per-frame partitioning contract this surface exercises.
- [`spec/002-Frames.md` §Dispatches issued from inside a handler body](../../spec/002-Frames.md) — the cross-frame `:dispatch` fx contract the Cross-bump button exercises.
- [`spec/009-Instrumentation.md` §Per-frame epoch buffers](../../spec/009-Instrumentation.md) — the ring-buffer-per-frame shape Causa's recorder reads against.
