# 03 — Events, state, and the cycle

The counter had pure state changes. No fetches. No timers. No localStorage. Real apps aren't like that.

Real apps need to *do things* in response to events: ask the server for data, persist a token, navigate, log to an external service. These are **side-effects**, and how re-frame2 handles them is the second-load-bearing decision after "where does state live."

Everything in this chapter follows from one rule:

> **Side-effects are not performed by event handlers. They are described by event handlers, as data, and performed by the runtime.**

That sounds bureaucratic. The payoff is enormous. Let's see why.

## The problem with side-effects in handlers

Imagine the obvious thing: an event handler that fetches user data on login.

```clojure
;; ❌ Don't do this
(rf/reg-event-db :user/login
  (fn [db [_ creds]]
    (.then (js/fetch "/api/login" #js {:method "POST" :body creds})
           (fn [resp]
             ;; ... what now?
             ))
    (assoc db :auth/loading? true)))
```

There are at least three problems with this.

**The handler isn't pure.** It calls `js/fetch`. That makes it untestable without mocking the network. Worse, the test result depends on whether the network is up, which test framework you're using, and whether fetch has been monkey-patched globally.

**The success/failure path is awkward.** The `.then` callback fires asynchronously, after the handler returns. By then, `db` is gone — it was the *previous* state. The callback can't update `db` directly; it has to do it by dispatching another event. So the function ends up half-pure-half-effectful, which is the worst possible mix.

**The chain of state changes is invisible.** You can't, from reading the handler, predict what the app will look like after this event resolves. You have to trace through the `.then`, see what it dispatches, find that handler, trace its `.then`, and so on. The "dynamic story" we talked about in [chapter 01](01-why-re-frame2.md) gets harder for every async operation.

The solution: **don't let the handler do the side-effect. Have it describe the side-effect as data.**

## Effects as data

Here's the same login event in re-frame2 idiom:

```clojure
(rf/reg-event-fx :user/login
  (fn [{:keys [db]} [_ creds]]
    {:db (assoc db :auth/loading? true)
     :fx [[:http {:method     :post
                  :url        "/api/login"
                  :body       creds
                  :on-success [:user/login-success]
                  :on-error   [:user/login-error]}]]}))
```

Three things changed:

1. The registration is `reg-event-fx`, not `reg-event-db`. The "fx" stands for "effects" — this handler returns a map of effects, not a new db.
2. The handler is **still pure**. It returns a Clojure map. Every value in that map is just data — strings, keywords, vectors. No `js/fetch`, no callbacks, no promises.
3. The map describes everything: "set the db to this; *also*, fire an HTTP effect with these args, and on success dispatch this event, on error dispatch this one."

Then there are two follow-up handlers, each pure:

```clojure
(rf/reg-event-db :user/login-success
  (fn [db [_ resp]]
    (-> db
        (assoc :auth/loading? false)
        (assoc :auth/user (:user resp)))))

(rf/reg-event-db :user/login-error
  (fn [db [_ err]]
    (-> db
        (assoc :auth/loading? false)
        (assoc :auth/error err))))
```

The runtime is what actually *does* the HTTP request. It looks at the effect map, sees `[:http {...}]`, looks up the registered `:http` fx handler, hands it the args. When the request resolves, the registered fx handler dispatches `[:user/login-success {...}]` or `[:user/login-error {...}]`. Those events go through the queue exactly like any other event. The cycle runs.

This shape makes the dynamic story tractable again. You can trace the login flow by reading three handlers, in order, top to bottom. There are no callbacks. There are no `.then` chains. There's data going in, data going out, data going in again.

## The standard effect map

The standard keys an `reg-event-fx` handler can return:

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:dispatch` | Dispatch a single event vector. |
| `:dispatch-later` | After `n` ms, dispatch this event. (Vector of `{:ms ... :dispatch ...}`.) |
| `:fx` | A vector of arbitrary `[fx-id args]` pairs. The recommended form for any non-trivial set of effects. |

You also use `:fx` for user-registered effects. Anything you've registered with `reg-fx` is reachable from here:

```clojure
{:db (assoc db :saved? true)
 :fx [[:http       {:url "/api/save" :method :post}]
      [:localstorage/set {:key "last-saved" :value (now)}]
      [:rf.nav/push-url     "/saved"]
      [:dispatch         [:notification/show "Saved!"]]]}
```

Five effects in one handler, including a state change, an HTTP request, a localStorage write, a navigation, and a follow-up event. The order of these effects is well-defined (the runtime applies `:db` first, then walks the `:fx` vector in order), and crucially, *the handler itself is still pure*. Tested as a function: `(handler {:db {...}} [:event-id])` returns the map above. Done.

## Registering your own effects

You're not limited to the standard set. Any side-effect you need can be registered:

```clojure
(rf/reg-fx :http
  {:doc       "Issue an HTTP request."
   :platforms #{:client :server}}
  (fn [m args]
    ;; This is the one place where the network call actually happens.
    (let [{:keys [method url body on-success on-error]} args]
      (-> (js/fetch url #js {:method (name method) :body (when body (js/JSON.stringify body))})
          (.then  (fn [resp] (rf/dispatch (conj on-success (json->clj resp)) {:frame (:frame m)})))
          (.catch (fn [err]  (rf/dispatch (conj on-error err)               {:frame (:frame m)})))))))
```

Three things to notice:

1. **`reg-fx` is the *only* place in your codebase that actually calls `js/fetch`.** The handler that triggered the request didn't. The success-handler that processes the response doesn't either. The line `js/fetch` appears once. That's the entire surface for the effect.

2. **The fx receives args (the value the event handler put in the effect map) and an envelope `m` (the dispatch envelope, which carries the originating frame).** It uses both to dispatch follow-up events on the right frame.

3. **`:platforms` says where this effect is allowed to run.** `#{:client :server}` means it works in the browser and in server-side rendering. A `:localstorage/set` effect would be `#{:client}` only — the runtime skips it during SSR with a logged trace event. We'll come back to this in [chapter 06](06-server-side.md).

You'll register a handful of effects in any non-trivial app. `:http` for the network. `:localstorage/get` and `:localstorage/set` for persistence. `:rf.nav/push-url` for navigation. `:notify` for toasts. Each is registered once; every event handler describes its effects by id.

## Why effects-as-data is worth the verbosity

It is more verbose. The login flow in idiomatic React is one async function with `await`. In re-frame2 it's three handlers + a registered fx. Six places where you'd have one.

The benefits:

**Tests don't need a network.** The handler that produces the effect map can be tested as a pure function. The success/error handlers similarly. The fx itself can be tested by stubbing the HTTP call. None of these tests need React, JSDOM, or a running server.

**You can swap the implementation.** A test wants the `:http` fx to return a canned response? Override it for that test:

```clojure
(rf/with-frame [f (rf/make-frame
                   {:on-create [:user/login {:email "a@b.c" :password "..."}]
                    :fx-overrides {:http :http.canned-success}})]
  (is (= "test@example.com" (-> @(rf/get-frame-db f) :auth/user :email))))
```

The override is **id-valued** — `:http` is replaced by `:http.canned-success`, another registered fx. No mocking. No interceptors. Just a registry pointer redirected.

**You can record what happened.** Because effects are data, the runtime can log them, replay them, ship them across the wire, store them in a fixture file. re-frame2's trace stream surfaces every effect that fired, with its args, in order. Debugging an asynchronous interaction stops being archaeology.

**You can shape new effects without changing the runtime.** Want to add a `:fluent-bit/log` effect that ships logs to a fluent-bit endpoint? `reg-fx` it. Every event handler in the app can use it immediately. No special-casing in the dispatch loop. No middleware composition order to think about.

**SSR comes for free.** The same handler that fires `:http` in the browser fires `:http` on the server, with the *same code*. The fx handler's implementation has both branches. Effects that don't make sense on the server (`:localstorage/set`) are gated by `:platforms` and skipped quietly. SSR isn't a parallel codebase; it's the same codebase running with a different platform flag.

## The full cycle

Pulling it all together, here's the cycle for one event with effects:

1. **Something dispatches** an event. The event vector lands in the frame's queue.

2. **The runtime pops the event** and looks up the registered handler.

3. **The handler runs as a pure function**, taking the current state and the event, returning an effect map.

4. **The runtime applies the effect map**:
   - If `:db` is present, replaces `app-db`.
   - Walks `:fx`, looking up each registered fx handler by id and invoking it with the args.
   - Each fx may, in turn, dispatch follow-up events. Those events join the queue.

5. **The runtime drains the queue.** It pops the next event and repeats. This continues *until the queue is empty*. Subscriptions only update once at the end — the app moves from one well-defined state to the next, with no intermediate frames visible to the view.

6. **Subscriptions recompute.** The view re-renders. The DOM updates.

That's the whole story. Six steps, every one of which has a name, every one of which is observable, every one of which is testable. There's nothing happening "behind the scenes." There's no schedule of `useEffect` firings to puzzle out. There's no question of "did this set state in time."

## "Run-to-completion drain"

The detail in step 5 is worth a pause. The runtime drains the *whole queue* before subscriptions update. This means if you dispatch an event that dispatches three follow-up events that each dispatch one more, the view sees the state *only* once those eight events have all settled.

This is called **run-to-completion drain semantics**, and it's a fairly opinionated choice. The alternative — letting each event update the view independently — is what most React apps do. It's faster in microbenchmarks. It's also why React apps occasionally show flickers, half-updated states, or out-of-order renders during fast interactions.

Run-to-completion says: *the user sees coherent states, not transitions*. Either the form is in submitting state, or it's in error state, never both for a frame. Either the page has navigated, or it hasn't, never the in-between. The cost is some flexibility for the developer; the gain is dramatically more predictable behaviour for the user.

## Effects as the testable surface

Once you've built a few features in this style, the testing pattern becomes natural:

- For events that just change state (`reg-event-db`): pure-function tests, very fast, very many.
- For events that produce effects (`reg-event-fx`): pure-function tests of the *effect map shape*, plus separate tests of each registered fx handler.
- For end-to-end flows: `dispatch-sync` a sequence of events, override the fx that touch the outside world, check the final state.

Every test runs without a browser. Every test runs without mocks (overrides are id-redirects, not mocks). Every test runs in milliseconds.

This is the testing experience that drove people to re-frame in the first place.

## Patterns: the next layer up

Once you've internalised events-as-data and effects-as-data, you start noticing that real apps have *recurring shapes* on top of those primitives. A feature that loads remote data has a slice and a four-event lifecycle. A form has a draft, a submitted snapshot, a status, per-field errors. An app's boot is a chained sequence with progress UI and fail-fatal points. A WebSocket connection is a state machine that owns a long-lived socket. A handler that posts work to an external system and waits for an asynchronous reply has a six-step shape. CPU-heavy work needs to either yield or move to a worker. Async results that arrive after the user has moved on need to be quietly suppressed.

Each of these has been distilled into a **Pattern** doc — convention, not Spec, but canonical naming so codebases and AI scaffolds converge on one answer rather than re-deriving:

- [Pattern-AsyncEffect](../../spec/Pattern-AsyncEffect.md) — the generic post-work-await-reply shape.
- [Pattern-RemoteData](../../spec/Pattern-RemoteData.md) — HTTP requests with a standard lifecycle slice.
- [Pattern-Forms](../../spec/Pattern-Forms.md) — draft/submitted/status/errors as a standard slice.
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained init, progress UI, fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.

The pattern docs are themselves human-readable — closer in voice to this guide than to the Specs. When the shape of a feature you're building matches one of them, read the pattern doc and copy the shape; don't invent a new one.

## A note on revertibility

One consequence of the discipline above worth pausing on: because state lives in one place and updates atomically, **the entire frame's state at any moment is a single value**. That value can be captured, stored, compared, restored. The framework's [Goal 2](../../spec/000-Vision.md#frame-state-revertibility) — *frame state revertibility* — turns this from an implementation detail into a contract: any prior frame value can be restored as a pointer swap, with no out-of-band state left behind. App-level undo is a thin interceptor. Time-travel debugging records values, not events. SSR ships a value. AI experimentation can try a change, observe, revert, retry without registry pollution. Each of these is a consequence of "state is a value"; the architecture commits to that discipline so the consequences are real.

## A note on naming

The convention in re-frame2 events:

- `:feature/verb-noun` — the past tense or imperative for the user's action: `:auth/log-in`, `:cart.item/remove`.
- `:feature/loaded`, `:feature/load-failed`, `:feature/saved`, `:feature/save-failed` — the result-bearing follow-ups, named after the action they're a result of.
- `:feature/initialise` — the seed-state event; called from `:on-create`.

You don't have to follow this. But every re-frame2 codebase that does looks the same. AIs scaffolding new code use these conventions automatically. Tools that introspect the registry can sort and group by feature trivially. The convention is a small price for a lot of consistency.

## Next

- [04 — Views and frames](04-views-and-frames.md) — what's on the screen and how to keep different parts isolated.
- The same pattern that handles `:http` handles every external-world interaction. Once you've internalised "side-effects are data the runtime interprets," you're ready for everything else.
