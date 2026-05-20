# The event-state cycle

## When to load

Sanity-checking a mental model: tracing what happens between `(rf/dispatch ...)` and the screen updating; debugging why a sub didn't recompute, or why an fx fired out of order.

## The cycle, end-to-end

```
   dispatch              cofx              handler            do-fx              substrate
      │                   │                  │                  │                   │
   ┌──▼──┐            ┌───▼───┐          ┌───▼───┐         ┌────▼────┐        ┌─────▼─────┐
   │ ev  │  envelope  │ build │  context │  user │ fx-map  │ walk    │ commit │ replace   │ reactions
   │ vec │ ─────────► │ cofx  │ ───────► │  fn   │ ──────► │ :db then│ ─────► │ container │ ────────► render
   │     │            │ map   │          │       │         │ each fx │        │           │
   └─────┘            └───────┘          └───────┘         └─────────┘        └───────────┘
   queue front                                                                  subs
                                                                                recompute
```

## Step by step

**1. Dispatch.** `(rf/dispatch [:event-id args])` builds an envelope `{:event ... :frame ... :dispatch-id ...}` and appends it to the target frame's router queue (`router.cljc:370`). Returns `nil` immediately — non-blocking.

**2. Drain scheduled.** The router schedules a drain via the substrate's microtask hook. `dispatch-sync` (`router.cljc:390`) bypasses the queue and drains immediately — outside-the-runtime callers only (tests, REPL, `:on-create`).

**3. Drain pops envelope.** For each event, the runtime looks up the registered handler's interceptor chain.

**4. Cofx injection.** Each `inject-cofx` interceptor runs in source order; each writes under `[:coeffects :cofx-id]`. The standard `:db` (current `app-db` value) and `:event` (the dispatched vector) are already populated (`cofx.cljc:92-104`).

**5. Validation (dev only).** If the handler carries a `:spec`, the runtime validates the event vector against it. Failure sets `:rf/skip-handler?` on the context and the handler short-circuits (`events.cljc:123-128`). `validate-at-boundary-interceptor` runs this same check in production.

**6. User handler.** The wrapped handler-fn fires:

- `reg-event-db` receives `(db, event)` → returns `new-db`; runtime stashes under `(:effects ctx :db)`.
- `reg-event-fx` receives `(cofx, event)` → returns `{:db ... :fx [...]}`; runtime stashes both under `(:effects ctx ...)`.
- `reg-event-ctx` receives `ctx` → returns `ctx` (advanced).

**7. Effect-shape policing.** Non-`:db`/non-`:fx` top-level keys emit `:rf.error/effect-map-shape` and are dropped (`events.cljc:81`). v2's effect map is closed.

**8. `:db` commits.** `re-frame.substrate.adapter/replace-container!` writes the new value into the frame's app-db container. **Atomic.**

**9. `:fx` walks.** `do-fx` (`fx.cljc:203`) iterates the `[fx-id args]` pairs in source order, synchronously. Each fx-handler runs to completion before the next begins. Errors and unknown ids trace independently — the walk continues.

**10. Subs recompute.** The substrate's reaction graph fires off the container change. Layer-1 subs that read changed paths recompute by `=`; layer-2+ subs cascade topologically. Unchanged-by-`=` values short-circuit cascades.

**11. Views re-render.** Reagent components subscribed to dirty subs re-render. Source-coord metadata captured by `reg-view` lets Causa / re-frame2-pair point at the originating Var.

**12. Drain continues.** Any `[:dispatch ...]` entries from step 9 are now on the queue. Drain loops until queue is empty (`router.cljc`). Run-to-completion: one dispatch fully settles before the next outside event starts.

## Per-step reference

| Step | Where it lives | Surface |
|---|---|---|
| Dispatch / queue | `router.cljc:370-388` | `rf/dispatch`, `rf/dispatch-sync` |
| Interceptor chain | `events.cljc:111-167` | metadata `:spec`, interceptors vector |
| Cofx injection | `cofx.cljc:57-89` | `rf/inject-cofx` |
| Handler invocation | `events.cljc:111-167` | `reg-event-db` / `-fx` / `-ctx` |
| Effect-map policing | `events.cljc:81-105` | `:rf.error/effect-map-shape` trace |
| `:db` commit | `subs.cljc` + substrate adapter | atomic via `replace-container!` |
| `:fx` walk | `fx.cljc:203-225` | `reg-fx`, `:fx-overrides` |
| Sub recompute | `subs.cljc:166-285` | reaction graph + deferred ref-count cache |
| Render | adapter (`reagent.cljs`, ...) | `reg-view` |

## Canonical mini-example

From `examples/reagent/counter/core.cljs`, one dispatch exercises the whole cycle:

```clojure
;; (1) dispatch
(rf/dispatch-sync [:counter/initialise])

;; (6) handler returns the fx-map
(rf/reg-event-fx :counter/initialise
  (fn [_ctx _event]
    {:db {:count 5}
     :fx [[:counter/log :initialised]]}))

;; (9) the fx walks; :counter/log is user-registered
(rf/reg-fx :counter/log
  (fn [_ctx args] (swap! counter-log conj args)))

;; (10) the sub recomputes; view re-renders
(rf/reg-sub :count
  (fn [db _query] (:count db)))
```

After this single `dispatch-sync`:

- `app-db` is `{:count 5}` (step 8).
- `counter-log` holds `[:initialised]` (step 9).
- Any view subscribed to `[:count]` re-renders with `5` (steps 10-11).

## Errors carry the triggering handler's source-coord

Every `:rf.error/*` trace event emitted from inside a running handler — event, sub, fx, cofx, view, interceptor `:before` / `:after`, late-bind hook — carries `:rf.trace/trigger-handler` with the source-coord of *that* handler:

```clojure
{:rf/op :rf.error/no-such-cofx
 :rf.error/data {:cofx-id :user/profile}
 :rf.trace/trigger-handler {:kind         :event
                            :id           :user/save
                            :source-coord {:ns     "myapp.events"
                                           :file   "src/myapp/events.cljs"
                                           :line   142
                                           :column 3}}}
```

`:kind` is the registry kind (`:event` / `:sub` / `:fx` / `:cofx` / `:view` / `:interceptor` / `:late-bind`); `:id` is the registered id; `:source-coord` comes from the `reg-*` macro's capture. The field is **present** whenever a handler is currently executing and **absent** for dispatch-time errors like `:rf.error/no-such-event`, where no handler is yet in scope. It is **not elided in production** — production debugging benefits most.

Tooling (Causa, re-frame2-pair) renders click-to-jump links straight to the offending handler off this field; in tests / REPL the same field surfaces in `(rf/trace-buffer {:op-type :error})`.

## Common gotchas

- **`dispatch` is queued, `dispatch-sync` is immediate.** Calling `dispatch-sync` from inside a handler raises `:rf.error/dispatch-sync-in-handler` — sync drains can't nest.
- **`:db` always commits before any `:fx` runs.** Within `:fx`, ordering is source order, run-to-completion. You can read the new `app-db` from a fx handler safely.
- **Subs observe the post-`:db` state.** If a fx dispatches another event, that nested event's coeffects see the already-committed value.
- **One bad fx does not abort the walk.** Don't write fx handlers that depend on a sibling fx having already succeeded — use a chained dispatch instead.
- **Run-to-completion is per outer dispatch.** All synchronously-enqueued events from one drain cycle settle before the next outside `dispatch` starts. `dispatch-later` re-enters via the timer; it is not part of the original run-to-completion bracket.

## Deeper material

Drain-depth bounds, the `:rf.epoch/*` projection of one full cycle for re-frame2-pair, microtask scheduling, the interceptor model in full: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — Instrumentation (009)**, **Runtime architecture**, **Tool-Pair contract**.

---

*Derived from `implementation/core/src/re_frame/{router,events,cofx,fx,subs}.cljc` and the substrate adapters under `implementation/core/src/re_frame/substrate/` @ main `89bd9c3`. Re-verify line numbers after router or interceptor-chain changes.*
