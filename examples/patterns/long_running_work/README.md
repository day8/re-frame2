# Long-running work that always stops cleanly

This example runs a job split across three workers at once. Click **Start
work** and three progress bars race to the finish, each worker grinding
through its own 100 items in parallel. While they run, you can stop the job
three ways: click **Cancel**, let it finish on its own, or click **Hide** to
make the whole panel vanish — the demo's stand-in for navigating away or
closing the tab. However it ends, every worker shuts down cleanly, with no
stray timers left firing into a screen that's gone.

Starting a five-second job is easy. The bug that ships is in *stopping* it —
and that's the idea worth taking away:

> **Stopping the work isn't cleanup you run — it's a state you leave.**

There are three ways the job can end, and the hand-written version writes
three separate teardown paths that all have to stay in sync. This one writes
none. Every worker lives inside the parent's single `:working` state. Leaving
that state — by any of the three routes — tears every worker down in one
move. You don't wire cancellation by hand; the
[machine](../../../docs/machines/glossary.md#machine) does.

This is the runnable companion to
[`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md).
That spec describes the single-worker shape: one chunked machine for one long
computation that *doesn't* split into pieces. When a job *does* split into
independent shards, you model it as **one parent coordinator and N child
workers**: the children run in parallel, and the parent waits for them all.
(Why split it up at all, instead of one big machine? See **Why this shape**,
below.)

That composition is what this example shows. Each `:work/processor` child
walks the spec's chunk-by-chunk loop, and the loop composes straight over
`:spawn-all` (from Spec 005): one parent, N children, one declarative
spawn-and-join.

## What this example demonstrates

- **Cancellation that fires on every exit path.** This is the main idea. The
  parent's `:working` state holds the `:spawn-all` declaration. Leaving
  `:working` — by user `:cancel`, by the join resolving (`:on-all-complete`),
  or by the frame being destroyed — runs one desugared `:exit`, which fires
  `:rf.machine/destroy` at every child still running. You write one
  [state](../../../docs/machines/glossary.md#state) exit, not three teardown
  paths to keep in sync, and the machine runs it however you left. Each
  torn-down child takes its in-flight `:after` timers with it. (Per Spec 005
  §Cancellation cascade, a real worker's in-flight `:rf.http/managed`
  requests would abort the same way — not exercised here.)

- **Declarative spawn-and-join** via [`:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all).
  The parent `:work/flow` machine spawns three `:work/processor` children,
  one per shard. The runtime tracks the join in runtime-db at
  `[:rf.runtime/machines :spawned :work/flow [:working]]`. The parent's
  `:data` holds no join bookkeeping — just the `:progress` map the view
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
  entry. Only an external re-entry (`:reenter? true`) would exit and re-enter
  `:working`, tearing down and re-spawning all three children on every
  progress tick — a subtle, expensive bug that "internal by default" quietly
  prevents.

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
  desugared `:exit` firing `:rf.machine/destroy` per surviving child.

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
                 :on-child-done  :work/child-done
                 :on-child-error :work/child-error
                 :on-all-complete [:work/all-done]
                 :on-any-failed  [:work/any-failed]
    :on
      :progress         (internal self-transition; updates :data)
      :work/all-done    → :complete
      :work/any-failed  → :error
      :cancel           → :cancelled
  :complete       ──[:reset]──> :idle
  :cancelled      ──[:reset]──> :idle   (or [:start] ──> :working)
  :error          ──[:reset]──> :idle   (or [:start] ──> :working)
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
examples/patterns/long_running_work/
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
# From implementation/:
npm run dev:example -- examples/long-running-work
```

Edits recompile live; the command prints a local URL to open.

Click **Hide** mid-job to watch the unmount cascade tear every child down on
its own.
