# Run to completion

Every dispatch you've made so far queued an event and walked away, and the pipeline caught up moments later. This page is about the scheduling rule that governs that catch-up — the one guarantee about *when* things run that the whole rendering story leans on.

Most frameworks choose the other way, which is why this deserves a hard stop. When the runtime starts processing events, it [**drains the queue to completion**](../glossary.md#drain--run-to-completion) before any view re-renders. The dequeued event runs its full write side. Then any events its handler `:fx`-dispatched run theirs. And so on until the queue is empty. Only then does the read side run — once — and the render boundary arrives. So the write side runs *per event*; the read side runs *once per drain*, at settle. This is the dispatch semantics, not a mode; there is no opt-out.

What it buys is coherence. If submitting a form dispatches three follow-up events, the view does not glimpse the state after each one. It sees one settled state, once. Either the form is submitting or it's failed, never both in one paint. The flicker-of-intermediate-state bug, familiar from systems where any update can interleave with any render, is structurally absent.

Watch it happen — the counter could carry this too (picture `:inc` dispatching a follow-up, as it did on [Effects](effects.md)), but a form submit is the fan-out you'll actually meet. One click below dispatches a single `:drain.demo/submit` whose handler fans out three follow-up events — four pipeline runs in one drain. Click into the cell and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then click **submit**:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :drain.demo/initialise
  (fn [_cofx _event] {:db {:drain.demo/steps []}}))

;; One click → one event whose handler fans out three follow-ups.
(rf/reg-event :drain.demo/submit
  (fn [{:keys [db]} _event]
    {:db (update db :drain.demo/steps conj :submitted)
     :fx [[:dispatch [:drain.demo/validate]]
          [:dispatch [:drain.demo/save]]
          [:dispatch [:drain.demo/notify]]]}))

(rf/reg-event :drain.demo/validate
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :validated)}))
(rf/reg-event :drain.demo/save
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :saved)}))
(rf/reg-event :drain.demo/notify
  (fn [{:keys [db]} _event] {:db (update db :drain.demo/steps conj :notified)}))

(rf/reg-sub :drain.demo/steps
  (fn [db _query] (:drain.demo/steps db)))

(defn drain-demo []
  [:div
   [:button {:on-click #(rf/dispatch [:drain.demo/submit])} "submit"]
   [:p "steps: " (pr-str @(rf/subscribe [:drain.demo/steps]))]])

(rf/dispatch-sync [:drain.demo/initialise])
[drain-demo]
```

All four steps appear **together**. The view never shows `[:submitted]` alone — by the time the render boundary arrives, the whole drain has settled.

!!! tip "Try it"

    Make `:drain.demo/validate` fan out its *own* follow-up — add `:fx [[:dispatch [:drain.demo/notify]]]` to its handler — re-evaluate, then click **submit** again. Five steps now settle in one drain, still one paint. However deep the chain goes, the screen only ever sees the end of it.

Two precise details, both visible in Xray:

- **Each dequeued event is its own [epoch](../glossary.md#epoch).** A parent event and the child it `:fx`-dispatched are *two* entries in the record — two event rows, each with its own handler run and its own before/after state — even though they settled inside one drain and produced one paint. The record stays per-event; the rendering stays per-drain.
- **Async effects are not drained.** An HTTP request fired during the drain doesn't hold anything open; its reply arrives later as a fresh event and starts a fresh drain. "Run to completion" bounds the synchronous run, not the outside world.

Strictly, the drain is per [**frame**](../glossary.md#frame) — a running [world](../glossary.md#world), with its own app-db and its own queue, and an app can run several ([Frames](frames.md)). But with one frame, which is every app until it isn't, "per frame" and "per app" say the same thing.

??? info "For JavaScript developers"

    React batches state updates within an event handler and paints once at the end — run-to-completion is that idea taken all the way: the batch boundary is the *entire* settled drain, not one handler. You never need `flushSync`, and you never catch the UI mid-update, because no render can interleave with the queue draining.

??? info "From re-frame v1"

    There is no `^:flush-dom` and no queue-pause-for-render — the drain never stops mid-run to let a paint through; post-render needs hang off the render boundary instead ([From re-frame v1](../25-from-re-frame-v1.md) has the rewrite). The v1 use case — "show this, *then* run the heavy block" — is served by a `dispatch-later` with `{:ms 0}`, which lets one paint land before the next event runs.

### When the drain won't stop

Run-to-completion is unconditional, which raises an obvious question: what if a handler dispatches an event whose handler dispatches the first one again? An infinite drain would spin forever. The runtime won't let it. Each frame carries a **`:drain-depth`** — the maximum number of events one drain may process (default `100`). When a drain reaches it, the runtime stops with a loud, machine-readable [error record](../glossary.md#error-record):

```clojure
{:operation :rf.error/drain-depth-exceeded
 :frame     :main
 :tags      {:depth      100                    ; events already settled this drain
             :queue-size 7                      ; events dropped, unrun
             :last-event [:the-last-event-that-ran]}}
```

The important part is what survives. Atomicity in re-frame2 is per [**event**](../glossary.md#commit), not per drain — so every event the drain already settled *keeps* its app-db write and its history row, exactly as if the drain had ended cleanly after each one. There is no whole-drain rollback to undo (and nothing to undo, since each settled event was atomic on its own). The runtime discards the remaining queued events, traces `:rf.error/drain-depth-exceeded`, and leaves the frame at the last settled state. In Xray you'll see the durable rows followed by a single `:halted-depth` marker — "the drain stopped here" — so a runaway drain is diagnosable, not silent.

!!! note "The bound is per-frame and tunable"

    Story — the tool that runs live demo canvases like these cells — gives its frames a tighter `:drain-depth` (`16` — a live demo should fail fast), while the `:test` preset pins the framework default (`100`) explicitly onto the frame's metadata; you can raise it for a frame that legitimately fans out wide. But reaching for a higher limit is usually the wrong move — a drain that needs hundreds of synchronous events is generally a cycle in disguise.

### Dispatching from outside the drain

One more entry point completes the picture. Inside a handler, you never call `dispatch` directly — you return `:fx [[:dispatch ...]]` and let the runtime queue it. But *outside* any handler — at app startup, in a test, at the REPL — there's nothing to return effects to. That's what [**`dispatch-sync`**](../glossary.md#dispatch-sync) is for:

```clojure
;; App bootstrap, or a test fixture: run the drain to completion, synchronously.
(rf/dispatch-sync [:app/initialise])
;; By the time this line returns, the whole drain has settled.
```

`dispatch-sync` runs the event through the same run-to-completion drain as `dispatch`, but it *blocks* until the drain settles, instead of scheduling the drain asynchronously (a later tick) and returning immediately. That's exactly what you want when the next line of a test needs to assert on the settled state, or when boot code must finish initialising before rendering begins.

!!! warning "Gotcha — `dispatch-sync` is an *outside* call only"

    Calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler`. Under run-to-completion the drain is *already* running synchronously, so "sync" would mean nothing there — the in-handler shape for a follow-up is always `:fx [[:dispatch event]]`. Use `dispatch-sync` to *enter* the machine from the outside; use `:fx` `:dispatch` to chain *within* it.

!!! warning "Gotcha — a dispatch needs a frame in scope"

    Both `dispatch` and `dispatch-sync` resolve [which frame](../glossary.md#frame) to target from the scope they're called in — a [provider](../glossary.md#frame-provider), a running handler, or a captured [capture-frame](../glossary.md#capture-frame). The runtime never invents one. A call from a *rootless* async callback — a stray `setTimeout`, a WebSocket `onmessage`, a bare promise `.then` that escaped the view tree — has no frame in scope and raises `:rf.error/no-frame-context` (also production-surviving). The fix is to grab a `capture-frame` while the frame *is* in scope and dispatch through it, or to pass `{:frame <id>}` explicitly in the dispatch opts. (This is the everyday face of [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found); [Frames](frames.md) is the full story.)

The trade is the framework's signature move, made again here. Give up a little flexibility — interleaved renders, inline effects, ambient reads — and get back inspectability: a recorded, replayable, coherent history.
