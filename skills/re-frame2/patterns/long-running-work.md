# Pattern — Long-Running Work

Cancellable spawn-and-join coordination via `:spawn-all` — one parent coordinates N parallel children that yield to the browser between chunks.

State-machine `:spawn` / `:spawn-all` is one instance of the **managed external effect** umbrella — alongside `:rf.http/managed`, `:rf.ws/*`, `:rf.server/*`, and `:rf.flow/*`. The runtime owns child lifetime (spawn on entry, teardown on exit, abort on parent transition), failure classification under `:rf.machine/*`, and trace-bus observability — which is exactly what makes the spawn-and-join shape below correctness-by-construction. See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) for the umbrella; this leaf names the *coordination* shape on top.

## When to load this leaf

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

## re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| `reg-machine` | Parent coordinator and child processor. |
| `:spawn-all` | Parent's spawn-and-join on `:working`. Runtime owns the join-state map at `[:rf/spawned <parent-id> [<state>]]`. |
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
   :guards  {:done?      (fn [data _] (>= (:processed data) (:total data)))
             :more-work? (fn [data _] (<  (:processed data) (:total data)))}
   :actions
   {:process-one
    (fn [data _]
      (let [new-processed (inc (:processed data))]
        {:data (assoc data :processed new-processed)
         :fx   [[:dispatch [:work/flow [:progress (:shard data) new-processed (:total data)]]]]}))
    :dispatch-done
    (fn [data _] {:fx [[:dispatch [:work/flow [:work/child-done (:shard data)]]]]})}
   :states
   {:idle           {:on    {:rf.machine/spawned :processing}}
    :processing     {:entry :process-one :always [{:target :checking-done}]}
    :checking-done  {:always [{:guard :done?      :target :done}
                              {:guard :more-work? :target :yielding}]}
    :yielding       {:after  {(fn [snap] (-> snap :data :tick-ms)) :processing}}
    :done           {:meta {:terminal? true} :entry :dispatch-done}}})

;; THE PARENT COORDINATOR
(rf/reg-machine :work/flow
  {:initial :idle
   :data    {:shards [:s1 :s2 :s3] :progress {} :outcome nil}
   :actions
   {:reset-progress  (fn [data _] {:data (assoc data :progress (zipmap (:shards data) (repeat 0)))})
    :record-progress (fn [data [_ shard processed _]] {:data (assoc-in data [:progress shard] processed)})
    :stamp-outcome   (fn [data [ev]] {:data (assoc data :outcome
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

Child auto-kick: `:on {:rf.machine/spawned :processing}` — runtime synthesises `[:rf.machine/spawned]` on spawn. Parent's `:progress` omits `:target` (internal self-transition); the `:spawn-all` exit cascade does NOT fire, so children stay alive between progress reports.

## Cancellation contract

Cancellation is a state transition; the substrate does the rest. All three exits (`:cancelled` / `:complete` / `:error`) trigger the same cascade.

```clojure
(rf/dispatch [:work/flow [:cancel]])                          ;; user click

(r/with-let [_ nil] [work-bench-ui]                           ;; React unmount
  (finally (rf/dispatch [:work/flow [:cancel]])))
```

Exiting `:working` fires one `:rf.machine/destroy` fx carrying `:rf/spawn-all true`; the handler reads `[:rf/spawned :work/flow [:working] :children]` and tears down every surviving child. Each torn-down child's pending `:after` timer cancels automatically.

## Variations

**Single-machine chunked (no parallelism).** Drop `:spawn-all` and run the chunk loop on the parent itself:

```clojure
:states
{:idle          {:on {:start {:target :processing :action :init-job}}}
 :processing    {:entry :process-chunk :always [{:target :checking-done}]}
 :checking-done {:always [{:guard :done? :target :complete}
                          {:guard :more-work? :target :yielding}]}
 :yielding      {:after {0 :processing} :on {:cancel :cancelled}}
 :complete      {:on {:reset :idle}}
 :cancelled     {:on {:reset :idle}}}
```

`:after {0 :processing}` schedules the next chunk after one browser tick. `:cancel` need only be declared on `:yielding` — the user can't click while the JS thread is in `:processing`.

**Worker offload.** Genuinely heavy work belongs in a Web Worker via Pattern-AsyncEffect; cancellation stays epoch-based (Pattern-StaleDetection). The chunked-main-thread pattern is the fallback when worker offload isn't feasible (DOM access required, awkward-to-serialise data).

**One-shot heavy block (replaces v1's `^:flush-dom`).** Render a modal before a one-shot heavy computation: `:dispatch-later {:ms 0 :dispatch ...}` gives the browser a render tick. No machine needed.

**Progress UI from the machine.** Register subs on `[:rf/machine <id>]` and project `:data` fields into the view.

**Final-state child completion (`:final?` / `:output-key`).** Cleaner than hand-rolling `:dispatch-done`: mark the child's `:done` as `:final? true` with `:output-key :shard-result`; `:spawn-all` recognises completion natively, parent receives the result via `:on-child-done`. Singletons supporting `:reset` back to `:idle` must NOT use `:final?` (auto-destroy fires first). See `../references/state-machines/invoke.md` §Final states.

## Anti-patterns

- **Computing in subscriptions.** Subs are cheap; compute belongs in event handlers.
- **Multiple `assoc`s expecting interleaved renders.** Re-frame2 batches per drain — one render. Chunking is the only way to get intermediate renders.
- **Manual chunk-state with `setTimeout`.** Re-derives what `:after` already provides; loses tracing and automatic teardown.
- **Forgetting cancellation.** The exit cascade makes it trivial; omitting `:cancel` on `:working` leaves a runaway loop.
- **`:always` cycles without `:after 0` between batches.** Hits `:rf.error/machine-always-depth-exceeded` (default 16). `:yielding`'s `:after` resets depth.
- **Per-child bookkeeping in the parent's `:data`.** The runtime owns join-state at `[:rf/spawned ...]`; re-implementing re-derives.

## Worked example

`examples/reagent/long_running_work/` — three parallel `:work/processor` children coordinated by `:work/flow`. The Show / Hide wrapper's `r/with-let` cleanup dispatches `[:work/flow [:cancel]]`. Carries a Playwright smoke and `cljs-test`.

## Pointer to the spec

Full rationale — `:spawn-all` runtime, join-state layout, `:join` modes (`:any`, `:n-of`), v1 migration — lives in *Pattern — Long-running work* and Spec 005. `:final?` surface: `../references/state-machines/invoke.md` §Final states.

---

*Derived from Pattern-LongRunningWork and the in-flight `examples/reagent/long_running_work/` @ main `89bd9c3`.*
