# Events and the pipeline

A click happened. Now what? Between the user's finger leaving the button and the screen showing a new number, an event runs a fixed sequence of stages. They run in order, every time, and none of them is magic. This page slows one click down so you can watch each stage go by. It also answers a question every architecture has to answer somewhere: how does a handler — the function that decides what an event *means* — do something impure, like fetch from a server, without giving up the purity that made it testable in the first place?

We'll build up gently: first what an [event](../glossary.md#event) *is*, then one click in slow motion through the [**pipeline**](../glossary.md#event-pipeline), then the moment a handler needs to reach outside the app — and the single move re-frame2 makes to keep that reach honest.

??? info "Coming from Redux?"

    You already have the skeleton. An [event](../glossary.md#event) is an action. An [event handler](../glossary.md#event-handler) is a reducer. [app-db](../glossary.md#app-db), your app's single state map, is the store. Three deliberate differences earn their keep, and this page walks each one: (1) there is no middleware layer — side effects come out of the handler *as data* and the runtime performs them (no thunks, no sagas, no `applyMiddleware`); (2) the dispatch queue drains to completion before the view renders, so the UI paints settled states, never intermediate ones; (3) the action log is not a devtools add-on — the dev runtime records every event natively, so the "replayable history" promise at the heart of this page is checkable, not aspirational.

!!! note "Production builds drop the recording"

    Production builds [elide](../glossary.md#elide) the recording machinery to zero code. The replayability promise itself holds either way — it's a property of the architecture, not of the dev tooling.

## An event is a fact

An [event](../glossary.md#event) — a value announcing that something happened — is a plain data vector: a keyword id, then any arguments.

```clojure
[:counter/inc]
[:article/load {:slug "how-it-works"}]
```

That's the whole thing. Not a function call. Not a callback. Not an object with methods. It's a value you could print, store, or send over a wire. The verb matches the shape: [`dispatch`](../glossary.md#dispatch), the call that puts an event on the queue, announces that something happened and then returns immediately.

```clojure
[:button {:on-click #(rf/dispatch [:counter/inc])} "+"]
```

When the click handler returns, **nothing has happened yet**. No state changed. No event handler ran. The event joined a queue, and the click handler's job is over.

This is the first load-bearing idea, and it's the one that trips people up coming from imperative event handlers: an event is a declaration of *what happened*, not an instruction packet. The button doesn't know how the counter works or what effects might fire. It records the fact "the user asked to increment" and walks away. The [event handler](../glossary.md#event-handler), registered elsewhere under that id, decides what the fact means. And because events are inert data, you can log them, assert on them in tests, replay them into a fresh app, and view them in an inspector. The rest of this page leans on all four.

??? info "For JavaScript developers"

    A DOM `on-click` usually *does* the work — mutate state, kick off a fetch, maybe both. Here the `on-click` does exactly one thing: it hands a value to `dispatch` and returns. Think of it less like calling a function and more like posting a message to a queue. Whatever the increment *means* lives somewhere else entirely, and the button neither knows nor cares.

!!! note "The canonical shape"

    Best practice is `[<id>]` for a trivial event, `[<id> <scalar>]` for one argument, and `[<id> {<k> <v>}]` when you have several — a single map payload rather than positional args. The runtime still *tolerates* variadic `[<id> a b c]` for migration and convenience, but the linter nudges new code toward the map form, because a named map is easier to read, grow, and destructure than a positional tail.

## One click, in slow motion

[The model](index.md) names the stages that every event runs through — its [**pipeline**](../glossary.md#event-pipeline). Here we watch one click travel all of them. The setup is the counter from the [quick start](../quickstart.md): app-db is `{:counter/value 5}`, the screen shows `[-] 5 [+]`, and the user clicks `+`.

The pipeline has two sides, split at a single seam. The **write side** runs per event — `assemble → transform → commit → perform` — and computes and commits the next state. The **read side** runs once the queue settles — `derive → render` — and brings the screen up to date. The seam between them is the [**commit**](../glossary.md#commit): nothing crosses from write side to read side except the one value it commits.

**1 — Dispatch.** The button's `on-click` runs `(rf/dispatch [:counter/inc])`. The vector goes onto the [frame](../glossary.md#frame)'s queue. `dispatch` returns. The browser's event loop is free again. The pipeline hasn't started yet — dispatch only enqueues.

**2 — Assemble.** The runtime dequeues `[:counter/inc]`, looks up its registered handler, and **assembles the [world](../glossary.md#world)** the handler will see — the map of declared facts it's allowed to read. app-db (`:db`) is always one of them; a handler that declared other facts (the clock, a fresh id) gets those gathered in too. This assembled world is the handler's first argument, its [coeffects](../glossary.md#coeffect).

**3 — Transform.** The runtime runs the handler:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
```

It runs as a pure function: the assembled world in (the facts it's handed — `:db` among them) and the event, an [effect map](../glossary.md#effect-map) out. No I/O, no DOM, no clock. It **transforms** the world into a *description* of what should change. You can test it in one line, because given a world with `:db {:counter/value 5}` it returns `{:db {:counter/value 6}}` and nothing else ([Test an event handler](../testing/event-handlers.md) is exactly this).

!!! note "New to Clojure?"

    A few bits of syntax to read that handler. The first argument `{:keys [db]}` is *destructuring* — it pulls the `:db` entry out of the incoming coeffects map and binds it to a local named `db`, so you don't write `(:db cofx)` by hand. The second argument is named `_event`: a leading underscore is the Clojure convention for "an argument I'm required to accept but don't use here." And `(update db :counter/value inc)` returns a *new* map — a copy of `db` with `:counter/value` run through `inc` (increment) — it never mutates the original. Returning a fresh value rather than editing in place is the move that keeps the handler pure.

!!! warning "Gotcha — dispatching an event with no handler fails loud"

    If the runtime dequeues an event whose id was never registered — a typo (`:counter/inc` vs `:counter/incr`), or a dispatch that ran before its `reg-event` loaded — the pipeline for that event simply doesn't run, and the runtime emits an [error record](../glossary.md#error-record) keyed `:rf.error/no-such-handler` carrying the offending event. It does *not* fall through to a silent no-op ([fail loud, not silent](../glossary.md#fail-loud-not-silent)). This is one of the few error categories that survives production — it fans out to your always-on error listeners — because a dispatch to a missing handler is a real bug whether you're in dev or live.

That transform returned an [effect map](../glossary.md#effect-map) — a description of what should happen, expressed as data:

```clojure
{:db {:counter/value 6}}
```

Pause here, because it reframes what a handler is. Even our trivially pure handler caused a side effect: somebody has to swap app-db. The trick is that the handler *described* the change and the runtime *performed* it — the transform ends at a description, and the two stages after the seam carry it out. Read the map as *"the next state, and anything else to do."* The "anything else" rides in an `:fx` vector of `[effect-id args]` rows — an HTTP request, a navigation, a follow-up dispatch. The counter's map has only `:db`, so there's nothing else to do; but it's the same map a handler that fires three effects returns, just with the other keys empty. One shape, no second spelling: a db update is the effect `{:db …}`, stated as plainly as every other effect.

**4 — Commit.** The runtime sees `:db` and [commits](../glossary.md#commit) app-db to `{:counter/value 6}` in one atomic step — which means no observer ever sees a half-written state. This is the **seam**: everything before it (assemble, transform) is transactional — a throwing handler installs nothing — and it's the one point the new value crosses from the write side to the read side. Nothing else crosses.

**5 — Perform.** With the commit landed, the runtime **performs** the `:fx` rows, in source order, after the `:db` commit. This is where the impure work happens — the HTTP call, the navigation, the follow-up dispatch. Past the seam the pipeline is best-effort, not transactional: an effect that throws doesn't un-commit the state. For this click there are no `:fx` rows, so perform has nothing to do.

That's the whole **write side** — `assemble → transform → commit → perform`. It runs once per event. Now the **read side** brings the screen up to date.

**6 — Derive.** app-db changed, so the read side **derives**: the [subscriptions](../glossary.md#subscription) — the derived values that watch app-db and recompute when it changes — re-run:

```clojure
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

It computes `6`, which differs from its previous `5`, so everything watching it is marked for re-render. Had the value come out unchanged, nothing downstream would run at all. That economy is [the subscription graph's](subscriptions.md) whole subject.

**7 — Render.** The [view](../glossary.md#view) reads its subscription with `@(rf/subscribe [:counter/value])` and re-runs. (`@` is Clojure's *deref*: a subscription is a live reference to a derived value, and `@` reads its current value. When that value changes, the view re-runs.) It produces fresh [hiccup](../glossary.md#hiccup) — now with a `6` where the `5` was — and the [substrate](../glossary.md#substrate) diffs it against the old and patches just that one DOM node. The screen shows `6`.

One event, one **pipeline run**. The same stages run under every event your app will ever process: a login, a websocket message, a route change. The machine doesn't grow new paths as the app grows. You just register more handlers.

**Now watch it for real.** Run your app with [Xray](../glossary.md#xray) attached ([Debug with Xray](../how-to/debug-with-xray.md)) and click `+` a few times. Each click appears as one event row: the event vector, the handler that ran, app-db before and after, what recomputed. That's not a log statement someone remembered to add. It's the runtime's own record of the run, and every claim on this page is checkable against it.

!!! note "The record is a dev-build surface"

    Production builds [elide](../glossary.md#elide) the whole recording machinery to zero code. [Observability](observability.md) covers that split — and which streams (the always-on `:errors` and `:events`) survive it.

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

This fails three ways, and the failures *are* the reasons for the architecture — not style points:

- **The handler isn't pure anymore.** It calls `js/fetch`. Testing it now means mocking the network. You took the most testable function in the codebase and made it the least.
- **The async path is a trap.** The `.then` callback fires *after* the handler returned. The `db` it closed over is the previous state, and the callback has no legal way to produce a new one. You've written a function that is half pure, half effectful, by accident.
- **The history goes dark.** The fetch never appears in the event record. Reading the handler no longer tells you what the app will look like when the response lands. Replaying the app's events no longer reproduces its state. The inline fetch is a side effect the [ledger](#the-ledger) never recorded — and the ledger, as a later section shows, is the asset this framework most refuses to give up.

!!! warning "Gotcha — never call `js/fetch` (or any I/O) from a handler body"

    A handler that performs I/O directly stops being pure, captures a stale `db` in its async callback, and vanishes from the event record. The next section shows the pure rewrite. The rule is one line: *describe the effect, don't perform it.*

## Effects are data

Here is the same load, written so the handler stays pure. An [effect](../glossary.md#effect), here, is a description of something the runtime should do to the outside world:

```clojure
;; cf. examples/real-apps/realworld_http/articles.cljs
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

;; On failure the reply's :error is a map carrying a :kind that names what went wrong.
(rf/reg-event :article/load-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (-> db
             (assoc :article/loading? false)
             (assoc :article/load-error error))}))
```

(One more piece of syntax in those last two handlers: `(-> db (assoc :a 1) (assoc :b 2))` is the *thread-first* macro. It reads top-to-bottom — take `db`, hand it to the first `assoc`, hand *that* result to the next — so it's a pipeline of "return a copy of the map with this key set," each step feeding the next. Same purity rule as before: every `assoc` produces a new map; nothing is mutated.)

The handler still returns nothing but a Clojure map: strings, keywords, vectors. No promise, no callback, no `js/fetch`. The map describes everything that should happen: "set app-db to this, fire a [managed HTTP request](../../resources/glossary.md#managed-http), on success dispatch `[:article/loaded ...]`, on failure dispatch `[:article/load-failed ...]`." The runtime reads the `:fx` row, looks up the `:rf.http/managed` [effect handler](../glossary.md#effect-handler), and performs the request. When the reply arrives, it enters the system the only way anything enters the system: as a fresh event on the queue, with its own trip through the pipeline and its own row in Xray.

That reply rides as the event's last argument in [the uniform reply](../glossary.md#the-uniform-reply) shape — success carries `:value`, failure carries `:failure` — and every managed async surface answers the same way. [Managed HTTP](../../async/http.md) is its home.

Read what that bought you. The entire fetch flow is three pure handlers you read top to bottom. No `.then` chains, no stale-`db` trap, and the failure path has a *name* instead of being a branch you forgot to write. Each handler tests as a plain function. The request tests as data: assert on the map, no network required.

??? info "Coming from Redux?"

    The `:fx` vector is where thunks, sagas, and middleware used to live — except the handler stays a pure function returning data, and the "middleware" is the runtime's effect interpreter. The async reply doesn't resolve a promise the reducer is awaiting; it arrives as a brand-new action dispatched onto the same queue.

??? info "Coming from TanStack Query?"

    A bare `:rf.http/managed` fx is the low-level move — you're hand-wiring one request and its two reply events. Most real screens want caching, staleness, and dedup, and for those you reach one level higher: [resources](../../resources/concepts.md) manage the request lifecycle for you, the way a `useQuery` hook does. The `:rf.http/managed` fx above is the mechanism underneath that convenience.

The `:rf.http/managed` args map carries far more than the four keys above — `:decode`, `:retry`, dropping a reply with `:on-failure nil`, the co-located single-handler form, the closed set of failure categories — but all of that is [Managed HTTP](../../async/http.md)'s subject. Here it earns its place purely as the `:fx` row that proves the pipeline point.

Two notes before moving on:

- **The first argument is the coeffects map** — a [coeffect](../glossary.md#coeffect) being an input fact the handler needs from the world, gathered with everything else into one value. `:db` and `:event` arrive for free. A handler that needs more (the current time, a storage read) declares those facts at registration with `:rf.cofx/requires` and receives them as plain values in that map — no change to the handler's shape, just a line of metadata. That declaration is [the coeffects page's](effects-and-coeffects.md) subject.
- **Follow-up events from inside a handler are effects too.** Never call `dispatch` from a handler body. Return `:fx [[:dispatch [:next-thing]]]` and the runtime queues it. Same rule, same reason: *describe, don't do.*

!!! note "An optional middle slot"

    `reg-event` takes an optional metadata map between the id and the handler — `(rf/reg-event :id {:doc "..." :interceptors [:my-app/audit]} (fn [cofx ev] ...))`. It carries reflection metadata (`:doc`, `:schema`, `:tags`, …) and the reserved `:interceptors` key: a vector of registered [interceptors](../glossary.md#interceptor) (the cross-cutting "wrap every handler" work) referenced by id. The chain *must* live under that key — a bare interceptor or a loose positional vector in the slot is a loud registration error (`:rf.error/reg-event-bare-interceptor` / `:rf.error/reg-event-bad-interceptors`), because the runtime refuses to let a chain hide in a slot it reads as metadata. Authoring them is [Interceptors](interceptors.md)' subject.

## The shape of an effect map

An app handler returns a small, **closed** [effect map](../glossary.md#effect-map) of exactly `:db` (the next app-db value) and `:fx` (a vector of `[effect-id args]` rows); any other top-level key fails loud, and you register your own effect-ids with `reg-fx`. The grammar is [Effects and coeffects](effects-and-coeffects.md)' subject. One of its rows matters to the pipeline specifically: a `[:dispatch [:event-id ...]]` row queues a follow-up event that **drains as part of this same [drain](../glossary.md#drain--run-to-completion)** (more below), so a handler chains the next step by returning it rather than calling `dispatch`.

### Ordering and atomicity — what you can rely on

When a handler returns `{:db new-db :fx [[a 1] [b 2] [c 3]]}`, four rules hold, and you can build on them:

1. **`:db` commits first, atomically.** The whole swap lands in one step, before any `:fx` row runs. No observer — no subscription, no concurrent reader — ever sees a half-written app-db.
2. **`:fx` rows run in source order.** `[a 1]` before `[b 2]` before `[c 3]`. The order you wrote them is the order they fire.
3. **Each row runs to (synchronous) completion before the next.** No interleaving. *Async* work a row kicks off — an outbound request, a `dispatch-later` timer — isn't awaited; "complete" means the effect handler returned.
4. **Effects see the post-`:db` state.** Because `:db` committed first, a `[:dispatch [:react-to-new-state]]` row dispatches an event whose handler reads the *new* app-db. This is the legitimate way to chain: write state, then dispatch the event that builds on it.

!!! warning "Gotcha — an effect throwing does NOT halt the others"

    If the handler for `[a 1]` throws, `[b 2]` and `[c 3]` **still run**, and each error is traced independently as `:rf.error/fx-handler-exception`. The `:db` commit (which happened first) is kept. This is deliberate: `:fx` rows are *independent* by design — "order" means order, not dependency. If one fx genuinely needs another to have succeeded first, don't express that as two sibling rows; lift the dependent step into a `:dispatch` chain so it observes the result via the queue.

## The ledger

Here's a reframing that reorganises how you think about the whole app, once it clicks. The reflex picture of state is a whiteboard: there's a current drawing, each event erases a bit and draws something new, and the old drawing is gone. The right picture is a **ledger**: each event is a line appended to the lines before it, and the app-db you see at any moment is the running total — the result of starting from the initial state and applying every event since, in order. The transform stage isn't "erase and redraw." It's "add the next line and re-total."

That picture comes with a promise precise enough to test:

!!! note

    **Two fresh apps, fed the same sequence of events, finish in identical states.** Start two copies from the same initial app-db, replay the same event log into each, and they land on the same value. The events *are* the state; the current app-db carries no information the log didn't put there.

The promise has one precondition: handlers must be honest about their inputs. A handler that secretly reads the clock or mints a random id mid-fold smuggles in a value the ledger never recorded, and replay diverges. re-frame2 closes that hole structurally. World facts enter handlers as [recordable coeffects](../glossary.md#recordable-vs-ambient-coeffects), declared at registration and recorded with the event, so replay re-presents the very values the original run consumed. [Effects and coeffects](effects-and-coeffects.md) is the full story. The rule of thumb is: *durable state folds facts, never reads.*

Hold the promise and a cluster of features stops looking like separate tricks:

- **[Time travel](../glossary.md#time-travel) is re-totalling fewer lines.** "Go back five events" isn't an undo system reversing five mutations — there were no mutations. It's the sum up to line *n−5*, recomputed on demand.
- **A bug report is a ledger excerpt.** "It broke after I did these things" becomes the literal event list that produces the bad state — in a fresh app, on demand, as a regression test ([Test a full cascade](../testing/cascades.md)).
- **Xray's event rows *are* the ledger, drawn.** The inspector showing "every event, in order, with app-db after each" isn't building a clever visualisation — it's rendering the [epoch](../glossary.md#epoch) record the runtime keeps anyway.

??? note "Going deeper"

    The whole app is a **left fold** — Clojure's `reduce` — over the event stream: a step function `state' = step(state, event)` applied once per event, each result threaded into the next call. Your handlers are the step function; the runtime is the `reduce`; "two apps, same events, same state" is just the observation that `reduce` is deterministic when its step function is pure. Everything else on this page is a consequence of that one algebraic fact: time travel is partial sums, a bug report is an input slice, and recordable coeffects are the rule that keeps the step function honest so the fold stays deterministic.

## Run to completion

One scheduling rule deserves a hard stop, because most frameworks choose the other way. When the runtime starts processing events, it [**drains the queue to completion**](../glossary.md#drain--run-to-completion) before any view re-renders. The dequeued event runs its full write side. Then any events its handler `:fx`-dispatched run theirs. And so on until the queue is empty. Only then does the read side run — once — and the render boundary arrives. So the write side runs *per event*; the read side runs *once per drain*, at settle. This is the dispatch semantics, not a mode; there is no opt-out.

What it buys is coherence. If submitting a form dispatches three follow-up events, the view does not glimpse the state after each one. It sees one settled state, once. Either the form is submitting or it's failed, never both in one paint. The flicker-of-intermediate-state bug, familiar from systems where any update can interleave with any render, is structurally absent.

Watch it happen. One click below dispatches a single `:submit` whose handler fans out three follow-up events — four pipeline runs in one drain. Click into the cell and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then click **submit**:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

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

    Make `:drain.demo/validate` fan out its *own* follow-up — add `:fx [[:dispatch [:drain.demo/notify]]]` to its handler — and re-evaluate. Five steps now settle in one drain, still one paint. However deep the chain goes, the screen only ever sees the end of it.

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

    Story frames set a tighter `:drain-depth` (`16` — a live demo should fail fast), while the `:test` preset pins the framework default (`100`) explicitly onto the frame's metadata; you can raise it for a frame that legitimately fans out wide. But reaching for a higher limit is usually the wrong move — a drain that needs hundreds of synchronous events is generally a cycle in disguise.

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

The trade is the framework's signature move, made for the third time on this page. Give up a little flexibility — interleaved renders, inline effects, ambient reads — and get back inspectability: a recorded, replayable, coherent history.
