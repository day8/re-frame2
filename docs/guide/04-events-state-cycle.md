# 04 — Events, state, and the cycle

The counter had pure state changes. No fetches. No timers. No localStorage. Real apps aren't like that.

Real apps need to *do things* in response to events: ask the server for data, persist a token, navigate, log to an external service. These are **side-effects**, and how re-frame2 handles them is the second-load-bearing decision after "where does state live."

Everything in this chapter follows from one rule:

> **Side-effects are not performed by event handlers. They are described by event handlers, as data, and performed by the runtime.**

That sounds bureaucratic. The payoff is enormous. Let's see why.

## The problem with side-effects in handlers

### Doing vs causing

Consider the counter's increment handler from chapter 03:

```clojure
(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))
```

We can proudly claim that this handler is **pure**: `db` and event in, new `db` out. No I/O, no clock, no globals. Tested in one line.

Notice, though, what made the purity *possible*. Somewhere, *something* has to actually mutate `app-db` so the view re-renders with the new count. That `reset!` is a side-effect — somebody has to do it. The handler stays pure only because the runtime took the side-effect upon itself. The handler **caused** the state change; the runtime **did** it.

Et tu, React? The same trade runs through Reagent. You write a view function that returns a hiccup vector — a pure description of UI. You never imperatively `appendChild`, never `setAttribute`. React (via Reagent) takes the hiccup, diffs against the prior tree, and mutates the DOM on your behalf. The view **caused** the rendering; React **did** it. That's why "your view is a pure function of state" is true at all — somebody else volunteered for the impure work.

re-frame2 generalises this trade to *every* side-effect, not just `app-db` and the DOM. Your handler doesn't fetch HTTP; it returns a value that *says* "an HTTP request should happen." Your handler doesn't write localStorage; it returns a value that says "this key should be written." Your handler doesn't navigate; it returns a value that says "the URL should change." In every case the handler causes; the runtime does.

The handler is the cleanest layer in the system. Every function it calls is pure. Every value it touches is immutable. Every test it has runs in a millisecond, without a browser, without a network, without a clock. The price is that "do X" becomes "return a value describing X." That price is paid once, at one boundary, and the rest of the codebase composes by the rules of pure-function composition — [chapter 13](13-the-dynamic-model.md#v-the-banana-issue) makes the deeper case for why that matters.

### What goes wrong if you don't

The moment the counter wants to reach outside itself — fetch the next increment from a server, persist its value to localStorage, beep — the temptation is to do the obvious thing right there in the handler:

```clojure
;; ❌ Don't do this
(rf/reg-event-db :counter/inc-from-server
  (fn [db _event]
    (.then (js/fetch "/api/inc.json")
           (fn [resp]
             ;; ... what now?
             ))
    (assoc db :counter/loading? true)))
```

There are at least three problems with this.

**The handler isn't pure.** It calls `js/fetch`. That makes it untestable without mocking the network. Worse, the test result depends on whether the network is up, which test framework you're using, and whether fetch has been monkey-patched globally.

**The success/failure path is awkward.** The `.then` callback fires asynchronously, after the handler returns. By then, `db` is gone — it was the *previous* state. The callback can't update `db` directly; it has to do it by dispatching another event. So the function ends up half-pure-half-effectful, which is the worst possible mix.

**The chain of state changes is invisible.** You can't, from reading the handler, predict what the app will look like after this event resolves. You have to trace through the `.then`, see what it dispatches, find that handler, trace its `.then`, and so on. The "dynamic story" we talked about in [chapter 01](01-why-re-frame2.md) gets harder for every async operation.

The solution falls out of the framing above: **don't let the handler do the side-effect. Have it describe the side-effect as data, and let the runtime do it.**

## Effects as data

Here's the same counter-fetch event in re-frame2 idiom:

```clojure
(rf/reg-event-fx :counter/inc-from-server
  (fn [{:keys [db]} _event]
    {:db (assoc db :counter/loading? true)
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    "/api/inc.json"}
            :on-success [:counter/inc-loaded]
            :on-failure [:counter/inc-error]}]]}))
```

Three things changed:

1. The registration is `reg-event-fx`, not `reg-event-db`. The "fx" stands for "effects" — this handler returns a map of effects, not a new db.
2. The handler is **still pure**. It returns a Clojure map. Every value in that map is just data — strings, keywords, vectors. No `js/fetch`, no callbacks, no promises.
3. The map describes everything: "set the db to this; *also*, fire a managed HTTP request with these args, and on success dispatch this event, on failure dispatch this one."

The handler's first argument — `{:keys [db]}` — is the **coeffects map**, the symmetric twin of the effect map on the way out. `:db` is the standard input every handler gets for free; the matching surface for handlers that need *other* inputs (the current time, a freshly-minted UUID, a value from `localStorage`) is `inject-cofx`, covered in [chapter 05](05-coeffects.md). The core path doesn't need cofx yet — the chapters that follow only destructure `:db` and the event — but the side-track is there the moment a handler wants to read something the runtime hasn't already put under its nose.

Then there are two follow-up handlers, each pure:

```clojure
(rf/reg-event-db :counter/inc-loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc :counter/loading? false)
        (update :count + (:delta value)))))

(rf/reg-event-db :counter/inc-error
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc :counter/loading? false)
        (assoc :counter/error failure))))
```

The runtime is what actually *does* the HTTP request. It looks at the effect map, sees `[:rf.http/managed {...}]`, looks up the registered fx, and hands it the args. When the request resolves, the fx dispatches `[:counter/inc-loaded {:kind :success :value v}]` or `[:counter/inc-error {:kind :failure :failure m}]`. Those events go through the queue exactly like any other event. The cycle runs.

`:rf.http/managed` is the canonical HTTP fx — managed decoding, retry-with-backoff, abort, schema-driven decode, frame-aware reply addressing — and it's covered in detail in [chapter 10 — Doing HTTP requests](10-doing-http-requests.md). This chapter uses it to make the effects-as-data shape concrete; the full surface lives there.

This shape makes the dynamic story tractable again. You can trace the counter-fetch flow by reading three handlers, in order, top to bottom. There are no callbacks. There are no `.then` chains. There's data going in, data going out, data going in again.

## Walking one event through every domino

Chapter 03 left the counter mounted: `app-db` is `{:count 5}`, the view shows `5`, the user is staring at `[-] 5 [+]`. They click `[+]`. Six things happen, in order. Each has a name. Each is observable. Each is testable in isolation.

This is the *single-trace*, single-page view of the cycle. Read it once; refer back when something feels mysterious later.

### Domino 1 — Event dispatched

The button's `on-click` fires:

```clojure
[:button {:on-click #(dispatch [:counter/inc])} "+"]
```

`dispatch` puts the event vector `[:counter/inc]` onto the frame's queue and returns immediately. No handler has run yet. The click handler is done. The browser's event loop can move on.

The event is *data*: a vector with a keyword id and (optionally) more args. Nothing more.

### Domino 2 — Event handler runs

The runtime pops `[:counter/inc]` off the queue, looks up its registered handler:

```clojure
(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))
```

`reg-event-db` is the simplest registration shape: take the current `db`, return the new `db`. The handler is a pure function — same `db` and event in, same `db` out, no side-effects, no I/O. Tested with one line: `(handler {:count 5} [:counter/inc]) ;=> {:count 6}`.

### Domino 3 — Effects produced

The handler returned `{:count 6}`. The runtime wraps that into an effect map:

```clojure
{:db {:count 6}}
```

That's the entire effect map for this event. No `:fx` vector — no HTTP, no localStorage, no follow-up dispatch. Just "replace `app-db` with this value." `reg-event-db` is sugar for `reg-event-fx` where the handler's return is automatically wrapped as `{:db ...}`; the runtime sees the same shape either way.

### Domino 4 — Effects executed

The runtime walks the effect map. It sees `:db` and resets the frame's `app-db` to `{:count 6}` as a single atomic swap. No intermediate state is visible. If there were an `:fx` vector, the runtime would walk it next, looking up each registered fx by id and invoking it with its args; for this event there is none.

The queue is now empty. The cycle proceeds to subscriptions.

### Domino 5 — Subscriptions recompute

`app-db` changed. The `:count` subscription is watching:

```clojure
(rf/reg-sub :count
  (fn [db _query] (:count db)))
```

It re-runs against the new `db`, gets `6`, and — because `6` differs from its previous value `5` — marks itself dirty. Any view that derefs this sub is now queued to re-render.

### Domino 6 — Views re-render

The `counter-buttons` view derefs `:count`:

```clojure
(reg-view counter-buttons []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

Reagent re-runs the function body. `@(subscribe [:count])` now returns `6`. The new hiccup tree is `[:div [:button "-"] [:span 6] [:button "+"]]`. Reagent diffs against the previous tree, sees that only the `<span>`'s text content changed, and patches the DOM.

And the view re-renders. One event, six dominoes, frame stays consistent.

## The standard effect map

The shape an `reg-event-fx` handler returns is intentionally narrow: two top-level keys.

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` pairs. Every other effect — dispatch, dispatch-later, HTTP, navigation, your own — goes through here. |

That's all. Top-level `:dispatch`, `:dispatch-later`, `:dispatch-n` from re-frame v1 are gone — they fold into `:fx` as `[:dispatch ...]` / `[:dispatch-later {...}]` rows. The single shape across every effect is the load-bearing piece: tooling, tests, and the runtime each see one consistent grammar instead of two parallel ones. (This consolidation is migration rule M-8 in [`spec/MIGRATION.md`](../../spec/MIGRATION.md).)

```clojure
{:db (assoc db :counter/saved? true)
 :fx [[:rf.http/managed
       {:request {:method :post :url "/api/counter" :body {:count (:count db)}
                  :request-content-type :json}}]
      [:localstorage/set  {:key "counter" :value (:count db)}]
      [:rf.nav/push-url   "/saved"]
      [:dispatch          [:notification/show "Saved!"]]]}
```

Four effects in one handler, including a state change, an HTTP request, a localStorage write, a navigation, and a follow-up event. The order is well-defined (the runtime applies `:db` first, then walks `:fx` top-to-bottom), and crucially, *the handler itself is still pure*. Tested as a function: `(handler {:db {...}} [:event-id])` returns the map above. Done.

## Registering your own effects

You're not limited to the framework-supplied set. Any side-effect you need can be registered:

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

Three things to notice:

1. **`reg-fx` is the *only* place in your codebase that calls `js/localStorage`.** The handler that triggered the write didn't. The handler that reads the value back later doesn't either. The browser-side imperative call appears once. That's the entire surface for the effect.

2. **The fx receives a frame context (carrying `:frame`, `:event`, etc.) and the args the event handler put in the effect map.** Most fxs only use the args; ones that re-dispatch follow-up events thread the frame through so the dispatch lands in the right frame.

3. **`:platforms` says where this effect is allowed to run.** `#{:client}` means it's skipped during SSR with a `:rf.fx/skipped-on-platform` trace event; the handler doesn't have to branch. We'll come back to this in [chapter 11](11-server-side.md).

You'll register a handful of effects in any non-trivial app: `:localstorage/get` and `:localstorage/set` for persistence, `:rf.nav/push-url` for navigation, `:notify` for toasts. Each is registered once; every event handler describes its effects by id.

For HTTP, the framework already ships `:rf.http/managed` — managed decoding, retry-with-backoff, abort, frame-aware reply addressing, schema-driven decode. You don't write your own HTTP fx; you use that one. [Chapter 10 — Doing HTTP requests](10-doing-http-requests.md) walks through it end-to-end.

## Why effects-as-data is worth the verbosity

It is more verbose. The fetch-an-increment flow in idiomatic React is one async function with `await`. In re-frame2 it's three handlers + a registered fx. Six places where you'd have one.

The benefits:

**Tests don't need a network.** The handler that produces the effect map can be tested as a pure function. The success/error handlers similarly. The fx itself can be tested by stubbing the HTTP call. None of these tests need React, JSDOM, or a running server.

**You can swap the implementation.** A test wants `:rf.http/managed` to return a canned response? Override it for that test:

```clojure
(rf/with-managed-request-stubs
  {[:get "/api/inc.json"] {:reply {:ok {:delta 1}}}}
  (rf/with-frame [f (rf/make-frame
                     {:on-create [:counter/initialise]})]
    (rf/dispatch-sync [:counter/inc-from-server] {:frame f})
    (is (= 6 (:count (rf/get-frame-db f))))))
```

The framework ships `with-managed-request-stubs` (and the lower-level `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` fxs) precisely so tests can synthesise managed-HTTP replies without a network. It's a registry redirect, not a mock — the same dispatch shape the real fx produces lands in the test handler.

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
- [Pattern-Forms](../../spec/Pattern-Forms.md) — draft/submitted/status/errors as a standard slice. (Guide: [09 — Forms](09-forms.md).)
- [Pattern-Boot](../../spec/Pattern-Boot.md) — chained init, progress UI, fail-fatal points.
- [Pattern-WebSocket](../../spec/Pattern-WebSocket.md) — long-lived connection as a state machine.
- [Pattern-LongRunningWork](../../spec/Pattern-LongRunningWork.md) — chunked yielding or worker offload for CPU-heavy work.
- [Pattern-StaleDetection](../../spec/Pattern-StaleDetection.md) — the epoch idiom for ignoring superseded async results.
- [Pattern-NineStates](../../spec/Pattern-NineStates.md) — making the nine common UI states (`Nothing` / `Loading` / `Empty` / `One` / `Some` / `Too Many` / `Incorrect` / `Correct` / `Done`) explicit in data so each branch is testable.

The pattern docs are themselves human-readable — closer in voice to this guide than to the Specs. When the shape of a feature you're building matches one of them, read the pattern doc and copy the shape; don't invent a new one.

## A note on revertibility

One consequence of the discipline above worth pausing on: because state lives in one place and updates atomically, **the entire frame's state at any moment is a single value**. That value can be captured, stored, compared, restored. The framework's [Goal 3](../../spec/000-Vision.md#frame-state-revertibility) — *frame state revertibility* — turns this from an implementation detail into a contract: any prior frame value can be restored as a pointer swap, with no out-of-band state left behind. App-level undo is a thin interceptor. Time-travel debugging records values, not events. SSR ships a value. AI experimentation can try a change, observe, revert, retry without registry pollution. Each of these is a consequence of "state is a value"; the architecture commits to that discipline so the consequences are real.

## A note on app-db shape

A frame's `app-db` is "your app's state, in one map." There's no required schema, but a useful convention is one top-level key per *feature* — each feature owning its own slice, accessed through that feature's subs and events:

```clojure
{:auth     {:user nil :loading? false :error nil}
 :cart     {:items [] :checkout-state :idle}
 :articles {:status :loaded :data [...] :loaded-at 1747...}
 :route    {:id :route/home :params {}}
 :ui       {:sidebar-open? true :modal nil}}
```

The same **id-prefix-as-namespace** convention extends to the registry: events for the cart feature live under `:cart/...`, subs under `:cart/items`/`:cart/total`, views under `:cart/summary`. The whole feature is identifiable by its prefix. For complex schemas, [Spec 010](../../spec/010-Schemas.md) lets you attach Malli schemas to `app-db` paths so validation happens automatically in dev — [chapter 11](11-server-side.md) shows this when SSR enters the picture.

## A note on naming

The same prefix convention shapes event ids:

- `:feature/verb-noun` — the past tense or imperative for the user's action: `:auth/log-in`, `:cart.item/remove`.
- `:feature/loaded`, `:feature/load-failed`, `:feature/saved`, `:feature/save-failed` — the result-bearing follow-ups, named after the action they're a result of.
- `:feature/initialise` — the seed-state event; called from `:on-create`.

You don't have to follow this. But every re-frame2 codebase that does looks the same. AIs scaffolding new code use these conventions automatically. Tools that introspect the registry can sort and group by feature trivially. The convention is a small price for a lot of consistency.

## Next

- [06 — Views and frames](06-views-and-frames.md) — what's on the screen and how to keep different parts isolated.
- The same pattern that handles `:rf.http/managed` handles every external-world interaction. Once you've internalised "side-effects are data the runtime interprets," you're ready for everything else.
