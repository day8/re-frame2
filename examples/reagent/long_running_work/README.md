# Long-running work — Pattern-LongRunningWork worked example

A re-frame2 worked example demonstrating
[`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md)'s
`:invoke-all` shape: cancellable long-running operations decomposed
into N parallel worker machines, with progress reporting and a
cooperative cancellation cascade that fires on **every** exit path —
including the user navigating away mid-flight.

This example complements the chunked-state-machine shape Pattern-LongRunningWork
describes for single-worker work. When the work decomposes into
independent shards, the same cooperative-yield idiom composes
naturally over `:invoke-all` — one parent coordinator, N children, one
declarative spawn-and-join.

## What this example demonstrates

- **Declarative spawn-and-join** via [`:invoke-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-invoke-all)
  (rf2-6vmw). The parent `:work/flow` machine spawns 3 `:work/processor`
  children in parallel; the runtime owns the join state at
  `[:rf/spawned :work/flow [:working]]`. No per-child bookkeeping in
  the parent's `:data` — the runtime fires
  `:on-all-complete` when the last child reports done.

- **Cooperative cancellation cascade**. Exiting the parent's `:working`
  state — by user `:cancel`, by `:on-all-complete`, by frame destroy,
  by `:after` (not exercised here, but the same machinery applies) —
  fires one `:rf.machine/destroy` fx whose handler tears down every
  surviving child. Each torn-down child's in-flight `:after` timers
  go with it; per Spec 005 §Cancellation cascade, in-flight
  `:rf.http/managed` requests would also abort (not used here).

- **Cooperative browser yielding**. Each child processes one item per
  `:processing` entry; `:always` advances to `:checking-done`;
  `:yielding`'s `:after` schedules the next chunk after a runtime-clock
  delay so the browser gets a render tick between chunks. The same
  `:after` is what gets cancelled cleanly when the parent transitions
  out of `:working` — a stale timer firing after cancel does NOT
  drive a transition (per [§Epoch-based stale detection](../../../spec/005-StateMachines.md#epoch-based-stale-detection)).

- **Per-step progress reporting**. The child dispatches
  `[:work/flow [:progress shard-id processed total]]` on every chunk;
  the parent's `:working` state handles `:progress` as an
  **internal self-transition** (no `:target` per [§Internal vs external
  self-transitions](../../../spec/005-StateMachines.md#self-transitions)) so
  the action runs without re-firing the `:invoke-all` entry-cascade
  (which would otherwise re-spawn the children — anti-pattern).

- **Parent-unmount cascade**. The view wrapper's `r/with-let` cleanup
  dispatches `[:work/flow [:cancel]]`. This is the **only** point
  where the UI lifecycle peeks through into the machine — the
  workers are React-agnostic. The pattern composes cleanly with any
  hosting React tree.

## The machine shape

```
:work/flow                                   (parent coordinator)
  :idle           ──[:start]──> :working
  :working
    :invoke-all  three children (one per shard)
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
  :idle           ──[:rf.machine/spawned]──> :processing
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
  long_running_work.spec.cjs   Playwright smoke
  test/long_running_work/
    worker_test.cljs           headless fixtures
  README.md       this file
```

The integration test that wraps the headless fixtures lives at
[`implementation/adapters/reagent/test/re_frame/long_running_work_cljs_test.cljs`](../../../implementation/adapters/reagent/test/re_frame/long_running_work_cljs_test.cljs).
Same wrapping pattern as `nine-states-cljs-test` and `realworld-cljs-test`.

## How to run

From `implementation/`:

```bash
# Playwright smoke (compiles, stages, serves, runs the .spec.cjs).
npm run test:examples

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

Direct fixture invocation from a CLJS REPL:

```clojure
(require '[long-running-work.core])             ;; loads the example
(require '[long-running-work.worker-test :as t])
(t/test-spawn-cascade)
(t/test-happy-path-join)
(t/test-cancel-cascade)
(t/test-parent-unmount-cascade)
(t/test-reset-after-cancel)
```

## Cross-references

- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — the pattern.
- [`spec/005-StateMachines.md` §Spawn-and-join via `:invoke-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-invoke-all) — the substrate.
- [`spec/005-StateMachines.md` §Cancellation cascade](../../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) — the cancel contract.
- [`spec/conformance/fixtures/invoke-all-*.edn`](../../../spec/conformance/fixtures/) — the runtime contract these examples sit on.
- [`examples/reagent/realworld/`](../realworld/) — the layout convention this example mirrors.
- [`examples/reagent/nine_states/`](../nine_states/) — single-machine sibling example using `:fsm/tags`.
