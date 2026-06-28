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

**1. Dispatch.** `(rf/dispatch [:event-id args])` builds an envelope `{:event ... :frame ... :dispatch-id ...}` and appends it to the target frame's router queue (`dispatch!` / `enqueue-envelope!` in `router.cljc`). Returns `nil` immediately — non-blocking.

**2. Drain scheduled.** The router schedules a drain via the substrate's microtask hook. `dispatch-sync` (`dispatch-sync!` in `router.cljc`) bypasses the queue and drains immediately — outside-the-runtime callers only (tests, REPL, `:initial-events`).

**3. Drain pops envelope.** For each event, the runtime looks up the registered handler's interceptor chain.

**4. Coeffect assembly.** The runtime stages the **base framework coeffects** unconditionally — `:db`, `:event`, `:rf.db/runtime`, `:rf.frame/id`, `:rf.cofx` (the whole flat recordable-coeffect map) — then delivers the handler's **declared** coeffects (`:rf.cofx/requires`) flat: recordable values read from the token's `:rf.cofx`, ambient values from running their suppliers now (`deliver-declared-cofx` in `cofx.cljc`). Declared-only delivery governs *user* leaves; an undeclared user leaf is not staged. (EP-0017 — `inject-cofx` is removed; no separate injector pass, so no cofx ordering question.)

**5. Validation (dev only).** If the handler carries a `:schema`, the runtime validates the event vector against it. Failure sets `:rf/skip-handler?` on the context and the handler short-circuits (the schema check + skip-handler honouring in `spec.cljc` / `events.cljc`). `validate-at-boundary-interceptor` runs this same check in production.

**6. User handler.** The wrapped `reg-event` handler-fn fires: it receives `(cofx, event)` → returns `{:db ... :rf.db/runtime ... :fx [...]}` (or `nil` for a no-op); the runtime stashes the returned effects under `(:effects ctx ...)`. A handler whose only effect is a db write returns `{:db new-db}` — the db write is one effect, not a special return shape. Full-context manipulation is done by a **registered interceptor** (authored with `reg-interceptor`, the public `context -> context` primitive; referenced by id from the chain — `->interceptor` is internal-only), not a distinct handler form.

**7. Effect-shape policing.** Top-level keys outside the closed set `#{:db :rf.db/runtime :fx}` emit `:rf.error/effect-map-shape` and are dropped (`police-effect-map-shape!` in `events.cljc`). v2's effect map is closed. `:rf.db/runtime` is inside the set — the framework-authority runtime-db partition (EP-0001); a non-framework handler emitting it gets a `:rf.warning/app-handler-runtime-effect` dev diagnostic, not a drop.

**8. State partitions commit.** `re-frame.substrate.adapter/replace-container!` writes the new app-db (and runtime-db, when present) into the frame's container. **Atomic.**

**9. `:fx` walks.** `do-fx` (in `fx.cljc`) iterates the `[fx-id args]` pairs in source order, synchronously. Each fx-handler runs to completion before the next begins. Errors and unknown ids trace independently — the walk continues.

**10. Subs recompute.** The substrate's reaction graph fires off the container change. Layer-1 subs that read changed paths recompute by `=`; layer-2+ subs cascade topologically. Unchanged-by-`=` values short-circuit cascades.

**11. Views re-render.** Reagent components subscribed to dirty subs re-render. Source-coord metadata captured by `reg-view` lets Xray / re-frame2-pair point at the originating Var.

**12. Drain continues.** Any `[:dispatch ...]` entries from step 9 are now on the queue. Drain loops until the queue is empty (`router.cljc`). Run-to-completion: one dispatch fully settles before the next outside event starts.

## Per-step reference

| Step | Where it lives | Surface |
|---|---|---|
| Dispatch / queue | `router.cljc` (`dispatch!` / `dispatch-sync!`) | `rf/dispatch`, `rf/dispatch-sync` |
| Interceptor chain | `events.cljc` (handler-wrapping fns) | metadata `:schema`, interceptors vector |
| Coeffect assembly | `cofx.cljc` (`deliver-declared-cofx`) | `:rf.cofx/requires` metadata |
| Handler invocation | `events.cljc` (handler-wrapping fn) | `reg-event` (one form) |
| Effect-map policing | `events.cljc` (`police-effect-map-shape!`) | `:rf.error/effect-map-shape` trace |
| `:db` commit | `subs.cljc` + substrate adapter | atomic via `replace-container!` |
| `:fx` walk | `fx.cljc` (`do-fx` / `handle-one-fx`) | `reg-fx`, `:fx-overrides` |
| Sub recompute | `subs.cljc` | reaction graph + synchronous ref-count cache |
| Render | adapter (`reagent.cljs`, ...) | `reg-view` |

## Canonical mini-example

From `examples/core/todomvc/events.cljs`, one dispatch exercises the whole cycle — `:db` commits, an fx persists, and a sub recomputes:

```clojure
;; (1) dispatch
(rf/dispatch [:todo/delete 3])

;; (6) handler returns the effects map  (commits :db AND fires an fx)
(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    (let [next-db (update db :todos dissoc id)]
      {:db next-db
       :fx [[:todo.storage/save (:todos next-db)]]})))

;; (9) the fx walks; :todo.storage/save is user-registered
(rf/reg-fx :todo.storage/save
  (fn [_ctx todos] (persist-to-local-storage! todos)))

;; (10) the sub recomputes; view re-renders
(rf/reg-sub :todo/todos
  :<- [:todo/sorted-todos]
  (fn [sorted-todos _] (vals sorted-todos)))
```

After this single dispatch:

- `app-db`'s `:todos` no longer contains id `3` (step 8).
- localStorage has the persisted remaining items (step 9).
- Any view subscribed to `[:todo/todos]` re-renders without the deleted item (steps 10-11).

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

`:kind` is the registry kind (`:event` / `:sub` / `:fx` / `:cofx` / `:view` / `:interceptor` / `:late-bind`); `:id` is the registered id; `:source-coord` comes from the `reg-*` macro's capture. The field is **present** whenever a handler is currently executing and **absent** for dispatch-time errors like `:rf.error/no-such-handler`, where no handler is yet in scope.

**`:rf.trace/trigger-handler` rides the trace surface, so it is production-elided** — the whole trace emit compiles out under `:advanced` + `goog.DEBUG=false`. Production-surviving source coordinates come from a **separate always-on channel**: the error-emit record carries a tight `:source-coord` from the always-on `error-coords-by-id` registry (via `rf/register-listener!` on `:errors` — see `cross-cutting/production-observability.md`). So in dev read the coord off `:rf.trace/trigger-handler`; in production off the error-emit record's `:source-coord` (macro-registered handlers only). Do not expect the trace field in production. (Spec 009 §What is NOT available in production.)

Tooling (Xray, re-frame2-pair) renders click-to-jump links straight to the offending handler off this field in dev; in tests / REPL the same field surfaces in `(rf/trace-buffer :rf/default {:flat true :op-type :error})`. `trace-buffer` is **per-frame** — the first arg is a `frame-id` (use `:rf/default`, or your explicit frame id), and the filter map is the *second* arg; `{:flat true}` returns raw trace events (filterable by `:op-type`) rather than the default cascade bundles. Passing the opts map as the first arg reads it as a frame id and returns an empty buffer.

## Common gotchas

- **`dispatch` is queued, `dispatch-sync` is immediate.** Calling `dispatch-sync` from inside a handler raises `:rf.error/dispatch-sync-in-handler` — sync drains can't nest.
- **`:db` always commits before any `:fx` runs.** Within `:fx`, ordering is source order, run-to-completion. You can read the new `app-db` from a fx handler safely.
- **Subs observe the post-`:db` state.** If a fx dispatches another event, that nested event's coeffects see the already-committed value.
- **One bad fx does not abort the walk.** Don't write fx handlers that depend on a sibling fx having already succeeded — use a chained dispatch instead.
- **Run-to-completion is per outer dispatch.** All synchronously-enqueued events from one drain cycle settle before the next outside `dispatch` starts. `dispatch-later` re-enters via the timer; it is not part of the original run-to-completion bracket.

## Deeper material

Drain-depth bounds, the `:rf.epoch/*` projection of one full cycle for re-frame2-pair, microtask scheduling, the interceptor model in full: `SKILL-REDIRECT.md` → **EP — Frames (002)**, **EP — Instrumentation (009)**, **Runtime architecture**, **Tool-Pair contract**.

---

*Derived from `implementation/core/src/re_frame/{router,events,cofx,fx,subs}.cljc` and the substrate adapters under `implementation/core/src/re_frame/substrate/` @ main `89bd9c3`. Citations are symbol-level; re-verify symbol homes after router or interceptor-chain changes.*
