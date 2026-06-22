# Events and the cascade

A click happened. Now what? Between the user's finger leaving the button and the screen showing a new number, six things happen. They run in order, every time, and none of them is magic. This page slows one click down so you can see each step. It also answers a question every architecture has to answer somewhere: how does a handler — the function that decides what an event means — do something impure, like fetch from a server, without giving up the purity that made it testable?

If you know Redux, start there. An **event** is an action. A **handler** is a reducer. **app-db**, your app's single state map, is the store. There are three deliberate differences, and this page earns each one:

1. **There is no middleware layer.** Side effects come out of the handler as data, and the runtime performs them. No thunks, no sagas, no `applyMiddleware`.
2. **The dispatch queue drains to completion before the view renders.** The UI paints settled states, never intermediate ones.
3. **The action log is not a devtools add-on.** The dev runtime records every event natively, and the tooling reads that record. So the "replayable history" promise at the heart of this page is checkable, not aspirational.

!!! note "Production builds drop the recording"

    Production builds compile the recording machinery away entirely. The replayability promise itself holds either way — it's a property of the architecture, not of the dev tooling.

## An event is a fact

An event — a value announcing that something happened — is a plain data vector: a keyword id, then any arguments.

```clojure
[:counter/inc]
[:article/load {:slug "how-it-works"}]
```

That's the whole thing. Not a function call. Not a callback. Not an object with methods. It's a value you could print, store, or send over a wire. The verb matches the shape: `dispatch`, the call that puts an event on the queue, announces that something happened and then returns immediately.

```clojure
[:button {:on-click #(rf/dispatch [:counter/inc])} "+"]
```

When the click handler returns, **nothing has happened yet**. No state changed. No handler ran. The event joined a queue, and the click handler's job is over.

This is the first load-bearing idea, and it's the one that trips people up coming from imperative event handlers: an event is a declaration of what happened, not an instruction packet. The button doesn't know how the counter works or what effects might fire. It records the fact "the user asked to increment" and walks away. The handler, registered elsewhere under that id, decides what the fact means. And because events are inert data, you can log them, assert on them in tests, replay them into a fresh app, and view them in an inspector. The rest of this page leans on all four.

## One click, in slow motion

[The model](index.md) names the six dominoes that every event knocks over. Here we watch one click fall through all of them. The setup is the counter from the [quick start](../quickstart.md): app-db is `{:counter/value 5}`, the screen shows `[-] 5 [+]`, and the user clicks `+`.

**1 — Dispatch.** The button's `on-click` runs `(rf/dispatch [:counter/inc])`. The vector goes onto the runtime's queue. `dispatch` returns. The browser's event loop is free again.

**2 — The handler runs.** The runtime dequeues `[:counter/inc]` and looks up its registered handler:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
```

It runs as a pure function: the coeffects (the facts it's handed — `:db` among them) and the event in, an **effect map** out. No I/O, no DOM, no clock. You can test it in one line, because given a coeffects map with `:db {:counter/value 5}` it returns `{:db {:counter/value 6}}` and nothing else ([Test an event handler](../how-to/test-an-event-handler.md) is exactly this).

**3 — Effects come out, as data.** The handler returned an **effect map** — a description of what should happen, expressed as data:

```clojure
{:db {:counter/value 6}}
```

Pause here, because it reframes what a handler is. Even our trivially pure handler caused a side effect: somebody has to swap app-db. The trick is that the handler *described* the change and the runtime *performed* it. Read the map as *"the next state, and anything else to do."* The "anything else" rides in an `:fx` vector of `[effect-id args]` rows — an HTTP request, a navigation, a follow-up dispatch. The counter's map has only `:db`, so there's nothing else to do; but it's the same map a handler that fires three effects returns, just with the other keys empty. One shape, no second spelling: a db update is the effect `{:db …}`, stated as plainly as every other effect.

**4 — The runtime executes the effects.** It sees `:db` and swaps app-db to `{:counter/value 6}` in one atomic step, which means no observer ever sees a half-written state. If there were `:fx` rows, it would run them next, in source order, after the `:db` commit. For this click there are none.

**5 — Subscriptions recompute.** app-db changed, so the subscriptions — the derived values that watch app-db and recompute when it changes — re-run:

```clojure
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

It computes `6`, which differs from its previous `5`, so everything watching it is marked for re-render. Had the value come out unchanged, nothing downstream would run at all. That economy is [the subscription graph's](subscriptions.md) whole subject.

**6 — The view re-renders.** The view — the function that turns subscribed values into the markup on screen — deref's `@(rf/subscribe [:counter/value])` and re-runs. It produces new hiccup with a `6` where the `5` was. The substrate diffs and patches that one DOM node. The screen shows `6`.

One event, six steps. The same path runs under every event your app will ever process: a login, a websocket message, a route change. The machine doesn't grow new paths as the app grows. You just register more handlers.

**Now watch it for real.** Run your app with Xray attached ([Debug with Xray](../how-to/debug-with-xray.md)) and click `+` a few times. Each click appears as one event row: the event vector, the handler that ran, app-db before and after, what recomputed. That's not a log statement someone remembered to add. It's the runtime's own record of the cascade, and every claim on this page is checkable against it.

!!! note "The record is a dev-build surface"

    Production builds compile the whole recording machinery away to zero code. [Observability](observability.md) covers that split.

## The temptation to do it inline

Real apps reach outside themselves: servers, storage, timers. The counter never needed a server, so it could carry the walk above. The article from the top of the page — `[:article/load {:slug "how-it-works"}]` — does. The obvious move is to just do the fetch right there in the handler:

```clojure
;; Don't do this.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    (.then (js/fetch (str "/api/articles/" slug))
           (fn [response]
             ;; ...and now what? The `db` this closure captured is
             ;; already stale, and returning from here goes nowhere.
             ))
    {:db (assoc db :article/loading? true)}))
```

This fails three ways, and the failures are the reasons for the architecture, not style points:

- **The handler isn't pure anymore.** It calls `js/fetch`. Testing it now means mocking the network. You took the most testable function in the codebase and made it the least.
- **The async path is a trap.** The `.then` callback fires after the handler returned. The `db` it closed over is the previous state, and the callback has no legal way to produce a new one. You've written a function that is half pure, half effectful, by accident.
- **The history goes dark.** The fetch never appears in the event record. Reading the handler no longer tells you what the app will look like when the response lands. Replaying the app's events no longer reproduces its state. The inline fetch is a side effect the ledger never recorded — and the ledger, as the next section shows, is the asset this framework most refuses to give up.

!!! warning "Never call `js/fetch` (or any I/O) from a handler body"

    A handler that performs I/O directly stops being pure, captures a stale `db` in its async callback, and vanishes from the event record. The next section shows the pure rewrite; the rule is: describe the effect, don't perform it.

## Effects are data

Here is the same load, written so the handler stays pure. An effect, here, is a description of something the runtime should do to the outside world:

```clojure
;; cf. examples/reagent/realworld/articles.cljs
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (assoc db :article/loading? true)
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/api/articles/" slug)}
            :decode     :json
            :on-success [:article/loaded]
            :on-failure [:article/load-failed]}]]}))

;; The reply map rides as the last event argument. On success
;; :value is the decoded response body — here {:article {...}}.
(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc :article/loading? false)
             (assoc :article/current (:article value)))}))

;; On failure :error is a map carrying a :kind that names what went wrong.
(rf/reg-event :article/load-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (-> db
             (assoc :article/loading? false)
             (assoc :article/load-error error))}))
```

The handler still returns nothing but a Clojure map: strings, keywords, vectors. No promise, no callback, no `js/fetch`. The map describes everything that should happen: "set app-db to this, fire a managed HTTP request, on success dispatch `[:article/loaded ...]`, on failure dispatch `[:article/load-failed ...]`." The runtime reads the `:fx` row, looks up the `:rf.http/managed` effect handler, and performs the request. When the reply arrives, it enters the system the only way anything enters the system: as a fresh event on the queue, with its own trip through the six steps and its own row in Xray. The runtime appends one uniform **reply map** as the event's last argument — `:value` carries the decoded body on success, `:error` carries a `{:kind …}` map on failure. Every managed async surface (HTTP, resources, route loaders, machine work) replies in that one shape, so you learn it once.

Read what that bought you. The entire fetch flow is three pure handlers you read top to bottom. No `.then` chains, no stale-`db` trap, and the failure path has a name instead of being a branch you forgot to write. Each handler tests as a plain function. The request tests as data: assert on the map, no network required.

Two notes before moving on:

- **The first argument is the coeffects map** — a coeffect being an input fact the handler needs from the world, gathered with everything else into one value. `:db` and `:event` arrive for free. A handler that needs more (the current time, a storage read) declares those facts at registration with `:rf.cofx/requires` and receives them as plain values in that map — no change to the handler's shape, just a line of metadata. That declaration is [the coeffects page's](effects-and-coeffects.md) subject.
- **Follow-up events from inside a handler are effects too.** Never call `dispatch` from a handler body. Return `:fx [[:dispatch [:next-thing]]]` and the runtime queues it. Same rule, same reason: describe, don't do.

> **Coming from Redux?** The `:fx` vector is where thunks, sagas, and middleware used to live — except the handler stays a pure function returning data, and the "middleware" is the runtime's effect interpreter.

For real server data you will usually reach one level higher than raw HTTP — [resources](server-state.md) manage the request, caching, and staleness for you — but the mechanism underneath is exactly this fx.

## The ledger

Here's a reframing that reorganises how you think about the whole app, once it clicks. The reflex picture of state is a whiteboard: there's a current drawing, each event erases a bit and draws something new, and the old drawing is gone. The right picture is a **ledger**: each event is a line appended to the lines before it, and the app-db you see at any moment is the running total. It's the result of starting from the initial state and applying every event since, in order. Step 2 of the cascade isn't "erase and redraw." It's "add the next line and re-total."

That picture comes with a promise precise enough to test:

> **Two fresh apps, fed the same sequence of events, finish in identical states.** Start two copies from the same initial app-db, replay the same event log into each, and they land on the same value. The events *are* the state; the current app-db carries no information the log didn't put there.

The promise has one precondition: handlers must be honest about their inputs. A handler that secretly reads the clock or mints a random id mid-fold smuggles in a value the ledger never recorded, and replay diverges. re-frame2 closes that hole structurally. World facts enter handlers as **recordable coeffects**, declared at registration and recorded with the event, so replay re-presents the very values the original run consumed. [Effects and coeffects](effects-and-coeffects.md) is the full story. The rule of thumb is: durable state folds facts, never reads.

Hold the promise and a cluster of features stops looking like separate tricks:

- **Time travel is re-totalling fewer lines.** "Go back five events" isn't an undo system reversing five mutations — there were no mutations. It's the sum up to line *n−5*, recomputed on demand.
- **A bug report is a ledger excerpt.** "It broke after I did these things" becomes the literal event list that produces the bad state — in a fresh app, on demand, as a regression test ([Test a full cascade](../how-to/test-a-cascade.md)).
- **Xray's event rows *are* the ledger, drawn.** The inspector showing "every event, in order, with app-db after each" isn't building a clever visualisation — it's rendering the record the runtime keeps anyway.

??? note "For the categorically curious"

    The whole app is a **left fold** — Clojure's `reduce` — over the event stream: a step function `state' = step(state, event)` applied once per event, each result threaded into the next call. Your handlers are the step function; the runtime is the `reduce`; "two apps, same events, same state" is just the observation that `reduce` is deterministic when its step function is pure.

## Run to completion

One scheduling rule deserves a hard stop, because most frameworks choose the other way. When the runtime starts processing events, it **drains the queue to completion** before any view re-renders. The dequeued event runs its full cascade. Then any events its handler `:fx`-dispatched run theirs. And so on until the queue is empty. Only then does the render boundary arrive. This is the dispatch semantics, not a mode; there is no opt-out. (The normative drain contract lives in [the frames spec](../../../spec/002-Frames.md#run-to-completion-dispatch-drain-semantics).)

What it buys is coherence. If submitting a form dispatches three follow-up events, the view does not glimpse the state after each one. It sees one settled state, once. Either the form is submitting or it's failed, never both in one paint. The flicker-of-intermediate-state bug, familiar from systems where any update can interleave with any render, is structurally absent.

Two precise details, both visible in Xray:

- **Each dequeued event is its own epoch.** A parent event and the child it `:fx`-dispatched are *two* entries in the record — two event rows, each with its own handler run and its own before/after state — even though they settled inside one drain and produced one paint. The record stays per-event; the rendering stays per-drain.
- **Async effects are not drained.** An HTTP request fired during the drain doesn't hold anything open; its reply arrives later as a fresh event and starts a fresh drain. "Run to completion" bounds the synchronous cascade, not the outside world.

Strictly, the drain is per **frame** — an isolated world with its own app-db and its own queue, and an app can run several ([Frames](frames.md)). But with one frame, which is every app until it isn't, "per frame" and "per app" say the same thing.

> **Coming from re-frame v1?** There is no `^:flush-dom` and no queue-pause-for-render — the drain never stops mid-cascade to let a paint through; post-render needs hang off the render boundary instead ([From re-frame v1](../25-from-re-frame-v1.md) has the rewrite).

The trade is the framework's signature move, made for the third time on this page. Give up a little flexibility (interleaved renders, inline effects, ambient reads) and get back inspectability: a recorded, replayable, coherent history.

---

**You can now:**

- explain what an event is (a recorded fact, not an instruction) and what `dispatch` does (queue it and return),
- trace a click through all six steps of the cascade and point at each one in Xray's event rows,
- say why handlers return effect descriptions instead of performing effects, and rewrite an inline fetch into a pure `reg-event` handler with named success and failure events,
- state the ledger promise — *two fresh apps, fed the same sequence of events, finish in identical states* — and name its precondition (recorded inputs, not ambient reads),
- predict when the screen repaints: once per drain, after the cascade settles.
