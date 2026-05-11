# Pattern — Long-Running Work

Cancellable spawn-and-join coordination via `:invoke-all` — one parent coordinates N parallel children that yield to the browser between chunks.

## When to load this leaf

Load when the task is:

- Processing a CPU-bound workload that can be split into independent shards (slice of dataset, region of image, batch of records).
- The user mentions "process in chunks", "parallel workers", "progress bar that updates while it runs", or "must be cancellable".
- Wiring cancellation to a React unmount or a route change so an in-flight job stops cleanly.

Do NOT load for:

- I/O-bound work — HTTP, IndexedDB, file system. Those already yield; use Pattern-AsyncEffect.
- A single chunked computation with no parallelism — the chunked-state-machine variant (one machine, `:processing` → `:yielding` → `:processing`) covers it; see *Variation: single-machine chunked* below.
- Heavy CPU work that can be offloaded — prefer a Web Worker via Pattern-AsyncEffect. The pattern below is the *fallback* for work that must stay on the main thread or decomposes into parallel shards.

## The shape

One parent coordinator machine spawns N children declaratively via `:invoke-all`. Each child processes its own shard in chunks, yielding via `:after` between chunks. Children dispatch `:progress` back to the parent. When all N report done, the runtime fires the parent's `:on-all-complete` keyword. Cancellation is a transition out of the `:working` state — the standard exit cascade tears down every surviving child.

## re-frame2 features this pattern uses

| Feature | Role here |
|---|---|
| `reg-machine` | Both the parent coordinator and the child processor. |
| `:invoke-all` | The parent's spawn-and-join declaration on the `:working` state. The runtime owns the join-state map at `[:rf/spawned <parent-id> [<state>]]`. |
| `:after` | The child's browser-yield seam between chunks. The runtime clock-driven timer is torn down automatically when the child is destroyed. |
| `:always` | The child's `:processing → :checking-done` advance; first-match-wins guards branch to `:done` or `:yielding`. |
| Cancellation cascade | Exiting `:working` (by user `:cancel`, by `:on-all-complete`, by frame destroy) fires one `:rf.machine/destroy` fx whose handler tears down every surviving child. |
| Internal self-transitions | `:progress` is handled with no `:target` — the action runs but `:exit` / `:entry` don't fire, so the join cascade isn't disturbed. |

## Canonical declaration

The parent + child pair. The parent spawns one child per shard; each child processes its shard in chunks; the parent records progress; the runtime resolves the join.

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
      (let [shard         (:shard data)
            new-processed (inc (:processed data))]
        {:data (assoc data :processed new-processed)
         :fx   [[:dispatch [:work/flow [:progress shard new-processed (:total data)]]]]}))

    :dispatch-done
    (fn [data _]
      {:fx [[:dispatch [:work/flow [:work/child-done (:shard data)]]]]})}

   :states
   {:idle           {:on    {:rf.machine/spawned :processing}}
    :processing     {:entry  :process-one
                     :always [{:target :checking-done}]}
    :checking-done  {:always [{:guard :done?      :target :done}
                              {:guard :more-work? :target :yielding}]}
    :yielding       {:after  {(fn [snap] (-> snap :data :tick-ms)) :processing}}
    :done           {:meta {:terminal? true} :entry :dispatch-done}}})

;; THE PARENT COORDINATOR
(rf/reg-machine :work/flow
  {:initial :idle
   :data    {:shards [:s1 :s2 :s3] :total 100 :progress {} :outcome nil}

   :actions
   {:reset-progress  (fn [data _] {:data (assoc data :progress (zipmap (:shards data) (repeat 0)))})
    :record-progress (fn [data [_ shard processed _total]]
                       {:data (assoc-in data [:progress shard] processed)})
    :stamp-outcome   (fn [data [ev]] {:data (assoc data :outcome (case ev
                                                                   :work/all-done :complete
                                                                   :cancel        :cancelled
                                                                   :work/any-failed :error
                                                                   (:outcome data)))})}

   :states
   {:idle
    {:on {:start {:target :working :action :reset-progress}}}

    :working
    {:invoke-all
     {:children [{:id :s1 :machine-id :work/processor
                  :data {:shard :s1 :total 100 :processed 0 :tick-ms 50}}
                 {:id :s2 :machine-id :work/processor
                  :data {:shard :s2 :total 100 :processed 0 :tick-ms 50}}
                 {:id :s3 :machine-id :work/processor
                  :data {:shard :s3 :total 100 :processed 0 :tick-ms 50}}]
      :join             :all
      :on-child-done    :work/child-done
      :on-child-error   :work/child-error
      :on-all-complete  [:work/all-done]
      :on-any-failed    [:work/any-failed]}
     :on {:progress        {:action :record-progress}            ;; internal self-transition
          :work/all-done   {:target :complete  :action :stamp-outcome}
          :work/any-failed {:target :error     :action :stamp-outcome}
          :cancel          {:target :cancelled :action :stamp-outcome}}}

    :complete   {:on {:reset {:target :idle :action :reset-progress}}}
    :cancelled  {:on {:reset {:target :idle :action :reset-progress}
                      :start {:target :working :action :reset-progress}}}
    :error      {:on {:reset {:target :idle :action :reset-progress}}}}})
```

The child's `:on {:rf.machine/spawned :processing}` is the auto-kick: when no explicit `:start` action is supplied in the invoke-spec, the runtime synthesises `[:rf.machine/spawned]` on spawn, and the child transitions itself into the work loop.

The parent's `:progress` entry under `:on` omits `:target` — that is an internal self-transition. The action runs but the `:invoke-all` exit cascade does *not* fire, so children stay alive between progress reports.

## Cancellation contract

Cancellation is a state transition; the substrate does the rest. Three exit paths into `:cancelled` / `:complete` / `:error` all trigger the same cascade:

```clojure
;; User clicks Cancel:
(rf/dispatch [:work/flow [:cancel]])

;; React unmount via reagent's r/with-let cleanup:
(r/with-let [_ nil]
  [work-bench-ui]
  (finally
    (rf/dispatch [:work/flow [:cancel]])))
```

When the parent transitions out of `:working`, the desugared `:invoke-all` `:exit` fires one `:rf.machine/destroy` fx carrying `:rf/invoke-all true`; the destroy fx handler reads `[:rf/spawned :work/flow [:working] :children]` and tears down every surviving child. Each torn-down child's pending `:after` timer is cancelled automatically — the worker does NOT resume after the delay elapses.

The worker machines themselves are lifecycle-agnostic — they do not know React exists. The one seam where UI lifecycle touches the machine is the single `:cancel` dispatch in the unmount cleanup.

## Variations

**Single-machine chunked (no parallelism).** When the workload is one stream rather than N shards, drop `:invoke-all` and run `:processing` / `:checking-done` / `:yielding` on the parent itself:

```clojure
:states
{:idle           {:on {:start {:target :processing :action :init-job}}}
 :processing     {:entry  :process-chunk
                  :always [{:target :checking-done}]}
 :checking-done  {:always [{:guard :done?      :target :complete}
                           {:guard :more-work? :target :yielding}]}
 :yielding       {:after {0 :processing}
                  :on    {:cancel :cancelled}}
 :complete       {:on {:reset :idle}}
 :cancelled      {:on {:reset :idle}}}
```

`:after {0 :processing}` schedules the next chunk after one browser tick — enough for repaint, not enough for perceptible delay. `:cancel` only needs to be declared on `:yielding`; the user cannot click cancel while the JS thread is in `:processing` anyway.

**Worker offload.** Genuinely heavy work (image processing, search indexing, simulations) belongs in a Web Worker via Pattern-AsyncEffect. The worker dispatches progress events back; cancellation is still epoch-based (Pattern-StaleDetection). The chunked-main-thread pattern is for cases where worker offload isn't feasible — DOM access required, awkward-to-serialise data, framework state that can't cross the boundary cheaply.

**One-shot heavy block (replaces v1's `^:flush-dom`).** When you want a modal to render *before* a one-shot heavy computation, `:dispatch-later {:ms 0 :dispatch ...}` gives the browser a render tick between the modal-show event and the work event. No state machine needed.

```clojure
(rf/reg-event-fx :process/start
  (fn [{:keys [db]} _]
    {:db (assoc db :processing? true)
     :fx [[:dispatch-later {:ms 0 :dispatch [:process/run]}]]}))

(rf/reg-event-fx :process/run
  (fn [{:keys [db]} _]
    {:db (assoc db :processing? false :result (heavy-block-fn db))}))
```

**Progress UI from the machine.** Register subs on `[:rf/machine <id>]` and project `:data` fields into the view. Each chunk advances `:data.processed` and the next browser tick renders the new value.

## Anti-patterns

- **Computing in subscriptions.** Subs should be cheap and pure; long compute belongs in event handlers. A sub that takes seconds slows every render that touches it.
- **Multiple `assoc`s in one handler expecting interleaved renders.** Re-frame2 batches per drain — only one render per drain regardless of how many `:db` updates within. Splitting into chunks via the state-machine pattern is the only way to get intermediate renders.
- **Manual chunk-state in `app-db` with `setTimeout`.** A state machine fits the chunked-progression structure cleanly: explicit states, named transitions, encapsulated `:data`, automatic timer teardown on cancel. Ad-hoc `setTimeout` loops re-derive everything `:after` already provides.
- **Forgetting cancellation.** Long jobs need a cancel path. The exit cascade makes it trivial; not declaring `:cancel` on `:working` leaves the user with a runaway loop.
- **`:always` cycles without `:after 0` between batches.** A pure `:always` chain hits the `:rf.error/machine-always-depth-exceeded` cap (default 16). The `:yielding` state's `:after` resets the depth and yields to the browser.
- **Per-child bookkeeping in the parent's `:data`.** The `:invoke-all` runtime owns the join-state at `[:rf/spawned <parent-id> [<state>]]`. Re-implementing `:children` / `:done` / `:resolved?` in the parent's own `:data` re-derives what's already there.

## Worked example

`examples/reagent/long_running_work/` — three parallel `:work/processor` children coordinated by `:work/flow`. The Show / Hide button mounts and unmounts a wrapper component whose `r/with-let` cleanup dispatches `[:work/flow [:cancel]]`; this is the canonical wire-up of React unmount to the cancellation cascade. The example carries a Playwright smoke and a headless `cljs-test`.

## Pointer to the spec

Full rationale — including the `:invoke-all` runtime contract, the join-state map layout, alternative `:join` modes (`:any`, `:n-of`), and the migration table from v1's self-redispatch / `:abandonment-required` flag / `^:flush-dom` — lives in *Pattern — Long-running work* and Spec 005 *State machines* (see `SKILL-REDIRECT.md` at the repo root).

---

*Derived from Pattern-LongRunningWork in the spec and the in-flight `examples/reagent/long_running_work/` (rf2-o9fg) @ main `89bd9c3`. Re-verify after `:invoke-all` join-engine changes or once the example merges.*
