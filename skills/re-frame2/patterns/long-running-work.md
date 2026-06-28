# Pattern — Long-Running Work

Cancellable spawn-and-join coordination via `:spawn-all` — one parent coordinates N parallel children that yield to the browser between chunks.

State-machine `:spawn` / `:spawn-all` is one of the four shipped instances of the **managed external effect** umbrella — alongside `:rf.http/managed`, `:rf.server/*`, `:rf.flow/*` (a WebSocket connection is the app/library-built case; re-frame2 ships **no** `:rf.ws/*`). The runtime owns child lifetime (spawn on entry, teardown on exit, abort on parent transition), failure classification under `:rf.machine/*`, and trace-bus observability. Machine async work is also a **property-9** async-reply-envelope family (alongside managed HTTP, resources, mutations, route loaders — *not* the synchronous `:rf.server/*` / `:rf.flow/*`): a child reports completion through `:on-child-done` / `:on-done`, which lower to the framework reply target, with a late completion for a superseded actor suppressed as `:status :stale` (correlated by the child's `:work/id`) — which makes the spawn-and-join shape below correctness-by-construction. See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md); this leaf names the *coordination* shape on top.

## When to load

Load when the task is:

- CPU-bound work splittable into independent shards (dataset slice, image region, record batch).
- Mentions "process in chunks", "parallel workers", "progress bar that updates while it runs", "must be cancellable".
- Wiring cancellation to a React unmount or route change so an in-flight job stops cleanly.

Do NOT load for:

- I/O-bound work — HTTP, IndexedDB, file system. Those already yield; use Pattern-AsyncEffect.
- A single chunked computation with no parallelism — the chunked-state-machine variant covers it; see *Variation: single-machine chunked* below.
- Heavy CPU that can be offloaded — prefer a Web Worker via Pattern-AsyncEffect. The pattern below is the *fallback* for work that must stay on the main thread or decomposes into parallel shards.

## The shape

One parent coordinator spawns N children declaratively via `:spawn-all`. Each child processes its shard in chunks, yielding via `:after` between chunks, and dispatches `:progress` back. When all N report done, the runtime fires `:on-all-complete`. Cancellation is a transition out of `:working` — the standard exit cascade tears down every surviving child.

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| `reg-machine` | Parent coordinator and child processor. |
| `:spawn-all` | Parent's spawn-and-join on `:working`. Runtime owns the join-state map at `[:rf.runtime/machines :spawned <parent-id> [<state>]]`. |
| `:after` | Child's browser-yield seam between chunks. Timer torn down automatically on destroy. |
| `:always` | Child's `:processing → :checking-done` advance; first-match-wins guards branch to `:done` or `:yielding`. |
| Cancellation cascade | Exiting `:working` fires one `:rf.machine/destroy` fx that tears down every surviving child. |
| Internal self-transitions | `:progress` with no `:target` runs the action without firing `:exit` / `:entry`. |

## Canonical declaration

```clojure
;; THE CHILD
(rf/reg-machine :work/processor
  {:initial :idle
   :data    {:shard nil :total 0 :processed 0 :tick-ms 50}
   :guards  {:done?      (fn [{:keys [data]}] (>= (:processed data) (:total data)))
             :more-work? (fn [{:keys [data]}] (<  (:processed data) (:total data)))}
   :actions
   {:process-one
    (fn [{:keys [data]}]
      (let [new-processed (inc (:processed data))]
        {:data (assoc data :processed new-processed)
         :fx   [[:dispatch [:work/flow [:progress (:shard data) new-processed (:total data)]]]]}))
    :dispatch-done
    (fn [{:keys [data]}] {:fx [[:dispatch [:work/flow [:work/child-done (:shard data)]]]]})}
   :states
   {:idle           {:on    {:rf.machine.spawn/spawned :processing}}
    :processing     {:entry :process-one :always [{:target :checking-done}]}
    :checking-done  {:always [{:guard :done?      :target :done}
                              {:guard :more-work? :target :yielding}]}
    :yielding       {:after  {(fn [{:keys [snapshot]}] (-> snapshot :data :tick-ms)) :processing}}
    :done           {:meta {:terminal? true} :entry :dispatch-done}}})

;; THE PARENT COORDINATOR
(rf/reg-machine :work/flow
  {:initial :idle
   :data    {:shards [:s1 :s2 :s3] :progress {} :outcome nil}
   :actions
   {:reset-progress  (fn [{:keys [data]}] {:data (assoc data :progress (zipmap (:shards data) (repeat 0)))})
    :record-progress (fn [{data :data [_ shard processed _] :event}] {:data (assoc-in data [:progress shard] processed)})
    :stamp-outcome   (fn [{data :data [ev] :event}] {:data (assoc data :outcome
                                                  (case ev :work/all-done :complete
                                                           :cancel        :cancelled
                                                           :work/any-failed :error
                                                           (:outcome data)))})}
   :states
   {:idle    {:on {:start {:target :working :action :reset-progress}}}
    :working {:spawn-all
              {:children [{:id :s1 :machine-id :work/processor :data {:shard :s1 :total 100 :processed 0 :tick-ms 50}}
                          {:id :s2 :machine-id :work/processor :data {:shard :s2 :total 100 :processed 0 :tick-ms 50}}
                          {:id :s3 :machine-id :work/processor :data {:shard :s3 :total 100 :processed 0 :tick-ms 50}}]
               :join :all
               :on-child-done   :work/child-done   ;; child-keyword children dispatch on success (REQUIRED)
               :on-child-error  :work/child-error   ;; child-keyword children dispatch on failure (REQUIRED)
               :on-all-complete [:work/all-done]
               :on-any-failed   [:work/any-failed]}
              :on {:progress        {:action :record-progress}      ;; internal self-transition
                   :work/all-done   {:target :complete  :action :stamp-outcome}
                   :work/any-failed {:target :error     :action :stamp-outcome}
                   :cancel          {:target :cancelled :action :stamp-outcome}}}
    :complete  {:on {:reset {:target :idle :action :reset-progress}}}
    :cancelled {:on {:reset {:target :idle :action :reset-progress}}}
    :error     {:on {:reset {:target :idle :action :reset-progress}}}}})
```

Child auto-kick: `:on {:rf.machine.spawn/spawned :processing}` — runtime synthesises `[:rf.machine.spawn/spawned]` on spawn. Parent's `:progress` omits `:target` (internal self-transition); the `:spawn-all` exit cascade does NOT fire, so children stay alive between progress reports.

## Cancellation contract

Cancellation is a state transition; the substrate does the rest. All three exits (`:cancelled` / `:complete` / `:error`) trigger the same cascade.

```clojure
(rf/dispatch [:work/flow [:cancel]])                          ;; user click

(r/with-let [_ nil] [work-bench-ui]                           ;; React unmount
  (finally (rf/dispatch [:work/flow [:cancel]])))
```

Exiting `:working` fires one `:rf.machine/destroy` fx carrying `:rf/spawn-all true`; the handler reads `[:rf.runtime/machines :spawned :work/flow [:working] :children]` and tears down every surviving child. Each torn-down child's pending `:after` timer cancels automatically.

## Variations

**Single-machine chunked (no parallelism).** Drop `:spawn-all` and run the chunk loop on the parent itself:

```clojure
:states
{:idle          {:on {:start {:target :processing :action :init-job}}}
 :processing    {:entry :process-chunk :always [{:target :checking-done}]}
 :checking-done {:always [{:guard :done? :target :complete}
                          {:guard :more-work? :target :yielding}]}
 :yielding      {:after {1 :processing} :on {:cancel :cancelled}}
 :complete      {:on {:reset :idle}}
 :cancelled     {:on {:reset :idle}}}
```

`:after {1 :processing}` schedules the next chunk after one browser tick — long enough to yield the JS thread, short enough to feel instant. **Do not use `:after {0 …}` for a machine timer:** a non-positive `:after` delay never schedules (the runtime emits `:rf.warning/no-clock-configured` and skips), so a zero-delay chunk loop silently stalls. Use the smallest positive delay (`1`) for the browser yield. `:cancel` need only be declared on `:yielding` — the user can't click while the JS thread is in `:processing`.

**Worker offload.** Genuinely heavy work belongs in a Web Worker via Pattern-AsyncEffect; cancellation stays epoch-based (Pattern-StaleDetection). The chunked-main-thread pattern is the fallback when worker offload isn't feasible (DOM access required, awkward-to-serialise data).

**One-shot heavy block (replaces v1's `^:flush-dom`).** Render a modal before a one-shot heavy computation: `:dispatch-later {:ms 0 :event [:do-heavy-block]}` gives the browser a render tick. No machine needed. (The fx key is `:event`, not v1's `:dispatch`; `:ms 0` is fine here — it is a host `setTimeout` yield, not a machine `:after` timer.)

**Progress UI from the machine.** Register subs on `[:rf/machine <id>]` and project `:data` fields into the view.

**Final-state child completion (`:final?` / `:output-key`).** Cleaner than hand-rolling `:dispatch-done`: mark the child's `:done` as `:final? true` with `:output-key :shard-result`; `:spawn-all` recognises completion natively, parent receives the result via `:on-child-done`. Singletons supporting `:reset` back to `:idle` must NOT use `:final?` (auto-destroy fires first). See `../references/state-machines/spawn.md` §Final states.

## Anti-patterns

- **Computing in subscriptions.** Subs are cheap; compute belongs in event handlers.
- **Multiple `assoc`s expecting interleaved renders.** Re-frame2 batches per drain — one render. Chunking is the only way to get intermediate renders.
- **Manual chunk-state with `setTimeout`.** Re-derives what `:after` already provides; loses tracing and automatic teardown.
- **Forgetting cancellation.** The exit cascade makes it trivial; omitting `:cancel` on `:working` leaves a runaway loop.
- **`:always` cycles without a yielding `:after` between batches.** Hits `:rf.error/machine-always-depth-exceeded` (default 16). A `:yielding` state with a small positive `:after` delay (e.g. `:after {1 …}` — **not** `:after {0 …}`, which never schedules) resets depth between batches.
- **Per-child bookkeeping in the parent's `:data`.** The runtime owns join-state at `[:rf.runtime/machines :spawned ...]`; re-implementing re-derives.

## Worked example

`examples/patterns/long_running_work/` — three parallel `:work/processor` children coordinated by `:work/flow` via `:spawn-all`. The Show / Hide wrapper's `r/with-let` cleanup dispatches `[:work/flow [:cancel]]`. The machines live in `worker.cljs`; `core.cljs` / `schema.cljs` / `views.cljs` complete it; `test/long_running_work/worker_test.cljs` is the CLJS unit test.

## Pointers

Full rationale — `:spawn-all` runtime, join-state layout, `:join` modes (`:all` / `:any` / `{:n N}` / `{:fn pred}`), v1 migration — lives in *Pattern — Long-running work* and Spec 005. `:final?` surface: `../references/state-machines/spawn.md` §Final states. (Partial joins use `{:n N}`, NOT `:n-of` — and require `:on-some-complete`; `:all` requires `:on-all-complete`, per `re-frame.machines.lifecycle-fx.validation`.)

---

*Derived from Pattern-LongRunningWork and the worked example `examples/patterns/long_running_work/` @ main `89bd9c3`.*
