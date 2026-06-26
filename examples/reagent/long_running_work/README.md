# Long-running work — Pattern-LongRunningWork worked example

This example runs a long job and, more importantly, stops it cleanly.

Starting a five-second job is easy. The bug that ships is in *stopping* it.
The user hits **Hide**, navigates away, or closes the tab while workers are
still running — and their timers keep firing into a view that is gone. This
example makes cancellation reliable on **every** exit path: the user clicks
`:cancel`, the work finishes on its own, *or* the React component unmounts.
It gets there with a
[machine](../../../docs/machines/glossary.md#machine) instead of hand-written
teardown.

It demonstrates the `:spawn-all` shape from
[`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md).
When a job splits into independent shards, you model it as **one parent
coordinator and N child workers**. The children run in parallel; the parent
waits for them all to finish. The parent's busy state owns the spawn, so
leaving that state — by any route — tears every surviving child down in one
move. You never wire cancellation by hand; the state graph does it.

Pattern-LongRunningWork also describes a single-worker shape that processes
one long computation in chunks. Reach for that when the work *doesn't* split.
When it *does* split into independent pieces, the same chunk-by-chunk idiom
composes over `:spawn-all`: one parent, N children, one declarative
spawn-and-join.

## What this example demonstrates

- **Cancellation that fires on every exit path.** This is the main idea. The
  parent's `:working` state holds the `:spawn-all` declaration. Leaving
  `:working` — by user `:cancel`, by the join resolving (`:on-all-complete`),
  or by the frame being destroyed — fires one `:rf.machine/destroy` effect,
  and its handler tears down every child still running. You write one
  [state](../../../docs/machines/glossary.md#state) exit, not three teardown
  paths to keep in sync, and the machine runs it however you left. Each
  torn-down child takes its in-flight `:after` timers with it. (Per Spec 005
  §Cancellation cascade, a real worker's in-flight `:rf.http/managed`
  requests would abort the same way — not exercised here.)

- **Declarative spawn-and-join** via [`:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all).
  The parent `:work/flow` machine spawns three `:work/processor` children,
  one per shard. The runtime tracks the join in runtime-db at
  `[:rf.runtime/machines :spawned :work/flow [:working]]`. The parent's
  `:data` holds no per-child bookkeeping — just the `:progress` map the view
  renders. The runtime watches the children report in and fires
  `:on-all-complete` when the last one finishes. You declare three children
  and a join policy (`:join :all`); the framework counts.

- **Cooperative browser yielding.** A worker that processes 100 items in a
  tight loop freezes the tab. Each `:work/processor` child does one item per
  `:processing` visit instead. An eventless `:always` moves it to
  `:checking-done`, and if work remains it drops into `:yielding`, whose
  `:after` schedules the next chunk after a short delay. That gap hands a
  render tick back to the browser, so the progress bar animates and the UI
  stays responsive. The same `:after` timer is the one cancelled when the
  parent leaves `:working`. A stale timer that fires *after* cancel does
  **not** drive a transition: the runtime tags each visit to the state and
  ignores the straggler (see [§Epoch-based stale detection](../../../spec/005-StateMachines.md#epoch-based-stale-detection)).

- **Per-step progress, done right.** Each child dispatches
  `[:work/flow [:progress shard-id processed total]]` after every chunk. The
  parent's `:working` state handles `:progress` as an **internal
  self-transition** — note there's no `:target` (see [§Self-transitions](../../../spec/005-StateMachines.md#self-transitions--internal-default-vs-external-reenter)).
  So the action records the new count without re-firing the `:spawn-all`
  entry. Give it a target and you'd re-enter `:working`, tearing down and
  re-spawning all three children on every progress tick — a subtle, expensive
  bug that "internal by default" quietly prevents.

- **The unmount cascade — one dispatch, the machine does the rest.** The
  work-bench view is wrapped in Reagent's `r/with-let`. Its `finally` cleanup
  runs on unmount and dispatches a single `[:work/flow [:cancel]]`. That is
  the only place the React lifecycle touches the machine. The workers
  themselves are host-agnostic: they don't import React, don't know they're
  on a page, don't care who unmounted them. So when the user clicks **Hide**
  mid-job, the component unmounts, the cleanup dispatches `:cancel`, the
  parent exits `:working`, and the cascade destroys every child. The pattern
  drops into any React tree precisely *because* the cancellation logic lives
  in the machine, not the view.

## Why this shape — one parent, N children

You could model a three-shard job as one big machine that loops over all the
work itself. This example splits it into a parent coordinator plus N child
workers because the split puts two contracts on display at once:

- **the join** — "run these in parallel, tell me when *all* are done" — which
  is `:spawn-all` with `:join :all`, with the runtime counting the children
  in; and
- **the cascade teardown** — "when the coordinator leaves its busy state,
  every child it spawned dies with it" — which falls out of `:spawn-all`'s
  desugared `:exit` firing one `:rf.machine/destroy`.

A single machine could fake the join with a counter in `:data`, but it
couldn't show the teardown — there'd be no children to tear down. Two
machines, cleanly split — a coordinator that knows about shards but not
items, and a worker that knows about items but not siblings — is what makes
both halves legible. Each shard is genuinely independent and parallelisable.
That's the real-world case — a slice of a dataset, a region of an image, a
batch of files — where you'd reach for this.

## The machine shape

```
:work/flow                                   (parent coordinator)
  :idle           ──[:start]──> :working
  :working
    :spawn-all  three children (one per shard)
                 :join :all
                 :on-child-done :work/child-done
                 :on-all-complete [:work/all-done]
                 :on-any-failed  [:work/any-failed]
    :on
      :progress         (internal self-transition; updates :data)
      :work/all-done    → :complete
      :work/any-failed  → :error
      :cancel           → :cancelled
  :complete       ──[:reset]──> :idle
  :cancelled      ──[:reset]──> :idle
  :error          ──[:reset]──> :idle
```

```
:work/processor                              (child worker; one per shard)
  :idle           ──[:rf.machine.spawn/spawned]──> :processing
  :processing     :entry :process-one  (dispatches :progress to parent)
                  :always  → :checking-done
  :checking-done  :always  → :done | :yielding   (guarded)
  :yielding       :after   → :processing  (browser-tick yield)
  :done           :meta {:terminal? true}
                  :entry :dispatch-done  (dispatches :work/child-done)
  :cancelled      :meta {:terminal? true}
                  (never reached via transition — cancellation
                   cascades via :rf.machine/destroy fx)
```

## File layout

```
examples/reagent/long_running_work/
  core.cljs       app entry point + :app/initialise
  worker.cljs     :work/processor + :work/flow registrations
  views.cljs      controls, progress bar, shard breakdown,
                  work-bench wrapper (the with-let cleanup
                  fires the unmount cascade)
  schema.cljs     malli schemas for the parent + child snapshots
  index.html      static host page (mounted by core/run)
  README.md       this file
```

## How to run

```bash
npx shadow-cljs watch examples/long-running-work
# Then open the URL the watch command prints.
```

Click **Hide** mid-job to watch the unmount cascade tear every child down on
its own.
