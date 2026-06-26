# Long-running work — Pattern-LongRunningWork worked example

The hard part of long-running work isn't running it — it's *stopping* it.
Anyone can kick off a five-second job and watch a progress bar fill. The
bug that ships is the one where the user hits **Hide**, navigates away,
or closes the tab while three workers are still grinding, and the
in-flight timers keep firing into a view that no longer exists. This
example is about making cancellation **reliable on every exit path** —
user `:cancel`, natural completion, *and* the React component unmounting
out from under the work — and it gets there by leaning on the
[machine](../../../docs/machines/glossary.md#machine) model rather than
hand-rolling teardown.

The shape it demonstrates is
[`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md)'s
`:spawn-all`: when a job decomposes into independent shards, you model it
as **one parent coordinator and N child workers**, spawned in parallel,
joined on "all done." The parent's busy state owns the spawn declaration;
exiting that state — by *any* route — tears every surviving child down in
one move. Cancellation stops being a thing you remember to wire and
becomes a thing the state graph does for you.

This example complements the single-worker chunked-machine shape
Pattern-LongRunningWork also describes. When the work *doesn't* shard —
one long computation, processed in chunks — you reach for that. When it
*does* shard into independent pieces, the same cooperative-yield idiom
composes naturally over `:spawn-all`: one parent, N children, one
declarative spawn-and-join.

## What this example demonstrates

- **Cancellation that fires on every exit path.** This is the load-bearing
  idea. The parent's `:working` state holds the `:spawn-all` declaration,
  and *leaving* `:working` — whether by user `:cancel`, by the join
  resolving (`:on-all-complete`), or by the frame being destroyed — fires
  exactly one `:rf.machine/destroy` effect whose handler tears down every
  child still standing. You don't write three teardown paths and hope
  they stay in sync; you write one [state](../../../docs/machines/glossary.md#state)
  exit, and the machine guarantees it runs no matter how you left. Each
  torn-down child takes its in-flight `:after` timers with it; per Spec
  005 §Cancellation cascade, a real worker's in-flight `:rf.http/managed`
  requests would abort the same way (not exercised here).

- **Declarative spawn-and-join** via [`:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all).
  The parent `:work/flow` machine spawns three `:work/processor`
  children, one per shard, and the runtime owns the join bookkeeping in
  runtime-db at `[:rf.runtime/machines :spawned :work/flow [:working]]`. The parent's
  `:data` carries *no* per-child accounting — just the aggregated
  `:progress` map the view wants to render. The runtime watches the
  children report in and fires `:on-all-complete` when the last one
  finishes. You declare three children and a join policy (`:join :all`);
  the framework does the counting.

- **Cooperative browser yielding.** A worker that processes 100 items in a
  tight loop freezes the tab. Each `:work/processor` child instead does
  one item per `:processing` entry, lets an eventless `:always` advance it
  to `:checking-done`, and — if there's more work — drops into `:yielding`,
  whose `:after` schedules the next chunk after a runtime-clock delay. That
  gap is a render tick handed back to the browser, so the progress bar
  actually animates and the UI stays responsive. The same `:after` timer
  is what gets cancelled cleanly when the parent leaves `:working` — and a
  stale timer that fires *after* cancel does **not** drive a transition,
  because the runtime tags each visit to the state and ignores the
  straggler (see [§Epoch-based stale detection](../../../spec/005-StateMachines.md#epoch-based-stale-detection)).

- **Per-step progress reporting, the right way.** Each child dispatches
  `[:work/flow [:progress shard-id processed total]]` after every chunk.
  The parent's `:working` state handles `:progress` as an **internal
  self-transition** — note there's no `:target` (see [§Self-transitions](../../../spec/005-StateMachines.md#self-transitions--internal-default-vs-external-reenter)) —
  so the action records the new count *without* re-firing the `:spawn-all`
  entry cascade. Give it a target and you'd re-enter `:working`, which
  would tear down and re-spawn all three children on every progress tick:
  a subtle, expensive bug that "internal by default" quietly prevents.

- **The unmount cascade — one dispatch, the machine does the rest.** The
  work-bench view is wrapped in Reagent's `r/with-let`, whose `finally`
  cleanup runs on unmount and dispatches a single `[:work/flow [:cancel]]`.
  That's the *only* place the React lifecycle touches the machine. The
  workers themselves are completely host-agnostic — they don't import
  React, don't know they're on a page, don't care who unmounted them. So
  when the user clicks **Hide** mid-job, the component unmounts → the
  cleanup dispatches `:cancel` → the parent exits `:working` → the cascade
  destroys every child. The pattern drops cleanly into any hosting React
  tree precisely *because* the cancellation logic lives in the machine,
  not in the view.

## Why this shape — one parent, N children

You could model a three-shard job as one big machine that loops over all
the work itself. The reason this example splits it into a parent
coordinator plus N child workers is that the split buys you two distinct
contracts the demo wants to put on display at once:

- **the join** — "run these in parallel, tell me when *all* of them are
  done" — which is `:spawn-all` with `:join :all`, with the runtime
  counting the children in; and
- **the cascade teardown** — "when the coordinator leaves its busy state,
  every child it spawned dies with it" — which falls out of `:spawn-all`'s
  desugared `:exit` firing one `:rf.machine/destroy`.

A single monolithic machine could fake the join with a counter in `:data`,
but it couldn't show you the teardown contract, because there'd be no
children to tear down. Two machines, cleanly separated — a coordinator
that knows about shards but not about items, and a worker that knows about
items but not about siblings — is what makes both halves legible. Each
shard is genuinely independent and parallelisable, which is exactly the
real-world situation (a slice of a dataset, a region of an image, a batch
of files) where you'd reach for this in the first place.

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

The example tree is test-free. The headless fixtures that
exercise the parent + child flows were folded into the integration test
in the framework test tree (see below).

Per the test-free examples policy there is no per-example
Playwright spec; real-regression coverage lives in the substrate
contract tests (`npm run test:cljs`) and the framework gates (see
[`examples/README.md`](../../README.md)).

The headless fixtures that exercise the parent + child flows live in the
integration test at
[`implementation/adapters/reagent/test/re_frame/long_running_work_cljs_test.cljs`](../../../implementation/adapters/reagent/test/re_frame/long_running_work_cljs_test.cljs)
— the helper fns and their `deftest` bodies are folded into that one ns
(folded inline), so the example source stays test-free. Same pattern as
`nine-states-cljs-test` and `realworld-cljs-test`.

## How to run

From `implementation/`:

```bash
# Headless cljs-test (runs all the *-cljs-test under
# implementation/adapters/reagent/test/, including the long-running-work
# integration).
npm run test:browser
```

To iterate against a live browser:

```bash
npx shadow-cljs watch examples/long-running-work
# Then open the URL the watch command prints.
```

The headless coverage runs from the integration test
(`re-frame.long-running-work-cljs-test`) under `npm run test:cljs`. The
spawn-cascade / happy-path-join / cancel-cascade / parent-unmount /
reset-round-trip scenarios each have a `deftest` there.

## Cross-references

- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — the pattern.
- [`spec/005-StateMachines.md` §Spawn-and-join via `:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all) — the substrate.
- [`spec/005-StateMachines.md` §Cancellation cascade](../../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) — the cancel contract.
- [`spec/conformance/fixtures/invoke-all-*.edn`](../../../spec/conformance/fixtures/) — the runtime contract these examples sit on.
- [`examples/reagent/realworld/`](../realworld/) — the layout convention this example mirrors.
- [`examples/reagent/nine_states/`](../nine_states/) — single-machine sibling example using `:fsm/tags`.
