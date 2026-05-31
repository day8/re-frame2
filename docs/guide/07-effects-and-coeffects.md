# 07 - Effects and coeffects

Your handler needs the current time, a random id, and to fire an HTTP request — but you swore it would stay pure, and you meant it. This chapter is the trick that lets you keep both promises at once: the handler stays a function from values to values, and the impure world still gets poked. Effects are how the impurity leaves on the way out; coeffects are how it arrives on the way in. Same idea, both directions.

## The promise you made, and the bill that's now due

Back in the counter, every handler was a pristine little function. `(fn [db _event] (update db :counter/value inc))` — db in, db out, no clock, no network, no globals, testable in a single line you could write in your sleep. We made a big deal of that purity, and rightly: it's the thing that makes the whole architecture hold together. Pure handlers are why you can test without a browser, replay an epoch, scrub time backwards, let an AI try a change and revert it. The purity isn't decoration. It's structural.

And then you go to build a *real* feature, and reality knocks. The todo you're adding needs a `:created-at` timestamp. New entities need ids. The "save" button needs to actually hit the server. The "remember me" checkbox needs to write to `localStorage`. Every one of those is a side-effect or a side-*cause*, and every one of them wants to live right there in the handler body where the data is, and the moment you let one in, the purity is gone and you've quietly rebuilt the exact mess you came here to escape.

So here's the question this whole chapter answers: **how does a pure function do something impure?** It sounds like a contradiction, and it would be, except for one move. The move is so simple it feels like cheating the first time you see it:

> A pure function can't *perform* a side-effect. But it can *return a description of one* — and let somebody else, downstream, do the dirty work.

That's it. That's the entire trick. The handler doesn't fetch; it returns a value that *says* "an HTTP request should happen." The handler doesn't write `localStorage`; it returns a value that *says* "this key should be written." Computing a description of a thing is pure. Doing the thing is not. re-frame2 splits those two jobs across a line, puts your handler entirely on the pure side, and stations exactly one piece of machinery — the runtime — on the impure side to read the descriptions and act on them.

You already met this once without me labelling it. When a `reg-event-db` handler returns a new `db`, *something* has to mutate `app-db` so the view re-renders. That mutation is a side-effect — and your handler didn't do it. Your handler returned a value; the runtime did the swap. The handler *caused* the new state; the runtime *did* it. We're about to generalise that one trade to every side-effect in the universe.

## Effects: the impurity going out

[Chapter 04](04-events-and-the-cascade.md#effects-as-data) walked the wrong way (inline `js/fetch` in a handler — stale `db`, callback hell, dynamic story gone dark) and the right way (return a map; `reg-event-fx` describes; runtime actions). The shape, recapped because the rest of this section builds on it:

```clojure
(rf/reg-event-fx :counter/inc-from-server
  (fn [{:keys [db]} _event]
    {:db (assoc db :counter/loading? true)
     :fx [[:rf.http/managed
           {:request    {:url "/api/inc.json"}
            :on-success [:counter/inc-loaded]}]]}))

(rf/reg-event-db :counter/inc-loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc :counter/loading? false)
        (update :counter/value + (:delta value)))))
```

Two pure handlers, no `.then` chain, no stale `db`. The runtime fires the request, dispatches the reply back, the cascade picks it up. (`:rf.http/managed` is the framework's one canonical HTTP effect — managed decoding, retry-with-backoff, abort, frame-aware reply routing. You don't write your own. The full surface is [chapter 10](10-http.md); here it's just a convenient something-impure to point at.)

### The standard effect map is narrow on purpose

A `reg-event-fx` handler returns a map with two top-level keys, and that's the whole grammar:

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id args]` pairs. *Every* other effect — a dispatch, an HTTP request, a navigation, a `localStorage` write, one you wrote yourself — rides in here. |

That's all of it. If you came from re-frame v1, you'll notice the top-level `:dispatch`, `:dispatch-later`, `:dispatch-n` keys are gone — they all fold into `:fx` as `[:dispatch ...]` / `[:dispatch-later {...}]` rows. One shape, not two parallel ones, is the load-bearing decision: the runtime, the tooling, your tests, and your tired 2am brain all parse one consistent grammar. (Migrating a v1 app? The migration agent rewrites the old top-level forms for you.)

Here's a handler asking for four effects at once, and staying a pure function the entire time:

```clojure
{:db (assoc db :counter/saved? true)
 :fx [[:rf.http/managed
       {:request {:method :post :url "/api/counter"
                  :body {:count (:counter/value db)} :request-content-type :json}}]
      [:localstorage/set  {:key "counter" :value (:counter/value db)}]
      [:rf.nav/push-url   "/saved"]
      [:dispatch          [:notification/show "Saved!"]]]}
```

State change, HTTP POST, a `localStorage` write, a navigation, and a follow-up dispatch — five effects, one map, zero impurity in the handler. The order is well-defined: the runtime applies `:db` first, then walks `:fx` top to bottom. You test it as a function — call the handler, assert the map it returns equals the one above. Done. No network, no DOM, no mocks.

And by the way: `reg-event-db` is just sugar for `reg-event-fx` where your return value gets auto-wrapped as `{:db ...}`. Same machinery underneath. The bare-db form is for the common case; the fx form is for when the case isn't that common.

### Rolling your own effect

You are emphatically not stuck with the framework's effect set. Need a new kind of side-effect? Register it:

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a value to localStorage."
   :platforms #{:client}}
  (fn [_frame-ctx {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

Three things in that little form earn their keep:

**This `reg-fx` is the *only* place in your entire codebase that calls `js/localStorage`.** Not the handler that triggered the write. Not the handler that reads the value back later. One imperative call, one location, the entire surface of the effect. That's the win: side-effects don't get to scatter.

**The fx receives a runtime context plus the args the handler put in the map.** Most fxs just use the args. Ones that re-dispatch follow-up events thread the context through so the dispatch routes back to the right frame.

**`:platforms #{:client}` says where it's allowed to run.** During SSR (no `localStorage` on the server) the runtime skips it and drops a `:rf.fx/skipped-on-platform` trace event — the handler never has to branch on platform. [Chapter 20](20-server-side.md) is the SSR story.

Now the part that should feel like a small magic trick. A handler returns a map. Some entries fire HTTP, some write storage, some push history. *Why does that work* — what turns a plain Clojure map into "go do these things"? Every `reg-event-fx` handler runs inside an interceptor chain, and the runtime silently slots a built-in interceptor called `do-fx` at the front of it. After your handler returns, `do-fx` walks the effect map and, for each entry, looks up the registered fx by id and invokes it. `:db`, `:fx`, `:dispatch`, `:rf.http/managed`, your `:localstorage/set` — all entries in one registry, executed by one loop. That's the whole reason `reg-fx` is enough to make every handler in the app able to use your new effect: there's one dispatcher reading one registry. The chain itself is [chapter 09's](09-interceptors.md) job; the upshot here is just *that's where effects get actioned.*

> **A registration only runs if its namespace is loaded.** `reg-event-fx`, `reg-fx`, and friends are top-level forms with side-effects — they write into the registry *when the namespace loads*. If nothing `(:require)`s your `events.cljs`, the dep tracker never loads it, the forms never run, and your handler silently doesn't exist. Your dispatch goes to `:counter/inc` and nobody answers. The fix is to have your boot namespace require every namespace that registers anything — the requires look unused, but they're load-bearing: they anchor the graph.

## Coeffects: the impurity coming in

Effects handle impurity on the way *out*. But there's a whole second category of impurity that sneaks in on the way *in*, and it's the one people forget about until it bites.

Your handler needs the current time. Or a fresh UUID. Or a value out of `localStorage`. Or the browser's locale. None of those are in `app-db` — they're *outside* the handler, in the world. The reflex is to just grab them:

```clojure
;; ❌ Don't do this
(rf/reg-event-db :todo/add
  (fn [db [_ title]]
    (let [now (js/Date.)
          id  (random-uuid)]
      (assoc-in db [:todos id] {:id id :title title :created-at now}))))
```

And we're right back in the soup. Same three problems as `js/fetch`, dressed differently. **The handler isn't pure** — call it twice with the same args and you get a different `:created-at` and a different `:id` each time; no test can pin it down without monkey-patching `js/Date` globally. **The boundary leaks into the body** — `js/Date` exists in the browser but not on the JVM where you want to run this handler's tests, even though "stamp a todo with a time" is a perfectly host-neutral idea. **There's no override surface** — you can't ask "what does this do if the clock is fixed at noon on Jan 1st?" for one handler without redefining `js/Date` for the entire test run.

These inputs-from-the-world have a name: **coeffects**, or *side-causes*. They're the exact mirror image of effects. Where an effect is data the handler *outputs* for the runtime to perform, a coeffect is data the runtime *injects* for the handler to read. The symmetry is so clean it's almost suspicious:

| | Inputs (coeffects) | Outputs (effects) |
|---|---|---|
| **Where it lives** | `:coeffects` | `:effects` |
| **Built in for free** | `:db`, `:event` | `:db` |
| **You register more with** | `reg-cofx` | `reg-fx` |
| **Identified by** | a keyword id | a keyword id |
| **The impure work happens in** | the cofx handler | the fx handler |

The handler reads from `:coeffects`, writes to `:effects`, and stays pure in the middle. The runtime fills `:coeffects` *before* the handler runs and drains `:effects` *after* it returns. You've actually been using coeffects all along without noticing — that `{:keys [db]}` you destructure in every `reg-event-fx` handler? That first argument *is* the coeffects map. `:db` and `:event` are coeffects the runtime stages automatically, every time. Everything past those two is opt-in.

### `reg-cofx` and `inject-cofx` — the registry pair

To fix the todo handler, quarantine each impurity inside a named, registered cofx:

```clojure
(rf/reg-cofx :now
  (fn [ctx]
    (assoc-in ctx [:coeffects :now] (js/Date.))))

(rf/reg-cofx :new-id
  (fn [ctx]
    (assoc-in ctx [:coeffects :new-id] (random-uuid))))

(rf/reg-event-fx :todo/add
  [(rf/inject-cofx :now)
   (rf/inject-cofx :new-id)]
  (fn [{:keys [db now new-id]} [_ title]]
    {:db (assoc-in db [:todos new-id]
                   {:id new-id :title title :created-at now})}))
```

The handler is pure again. The two impure calls live inside two named cofx handlers, each addressable by id. Two things to read carefully here.

A **cofx handler** is a function from context to context. It receives the full context map (the same one interceptors thread, [chapter 09](09-interceptors.md)) and returns it with the value `assoc-in`'d under `[:coeffects <id>]`. The convention is to inject under the same keyword you registered with — cofx id and coeffect key match. There are two arities: the **unary** `(fn [ctx] ...)` for parameterless cofxes (`:now`, `:new-id` — there's only ever one answer), and the **binary** `(fn [ctx value] ...)` for cofxes parameterised at the call site. The classic binary one is `:local-store`, which takes the key to read:

```clojure
(rf/reg-cofx :local-store
  (fn [ctx storage-key]
    (assoc-in ctx [:coeffects :local-store]
              (some-> (.-localStorage js/globalThis) (.getItem storage-key)))))

(rf/reg-event-fx :prefs/load
  [(rf/inject-cofx :local-store "user-prefs")]
  (fn [{:keys [local-store]} _]
    {:db (-> (or local-store {}) (parse-prefs))}))
```

One `:local-store` handler serves every event that needs a different key — the *what* stays generic, the *which* lives at the call site.

`inject-cofx` is the use-site. It's the small interceptor that, on the way in, looks up the registered cofx fn, calls it (with the second value too, if you called it binary), and lets the now-enriched context flow on toward the handler. List several to compose them — they run in declaration order, and by the time the handler runs, every key is present in its coeffects map. It's a `:before`-only interceptor: a cofx is an input, so there's nothing to do on the way out.

> **`reg-event-db` can't see injected cofx values.** Its signature is `(fn [db event] ...)` — only `:db` is handed over. If you need a cofx, register the event with `reg-event-fx` (or, rarely, `reg-event-ctx` for the whole context map). The interceptors slot still works on `reg-event-db` for *other* purposes — a logger, an undo wrapper — but `inject-cofx` is wasted on it.

### See it run

Time to stop reading and start poking. Here's a todo-adder, live, with `:now` and `:new-id` injected as coeffects — a real re-frame2 program in your browser. Click into the cell, hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate it, then add some todos. First run takes a beat while the engine wakes; after that it's instant.

(Live cells are functions-only — the view is a plain `defn` with explicit `rf/dispatch` / `rf/subscribe`; `reg-view` is sugar over exactly this. See [chapter 06](06-views.md#defn-views-and-the-reg-view-equivalence).)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; ---- Coeffects: the two impure inputs, each quarantined behind an id ----
(rf/reg-cofx :demo.todo/now
  (fn [ctx] (assoc-in ctx [:coeffects :demo.todo/now] (js/Date.))))

(rf/reg-cofx :demo.todo/new-id
  (fn [ctx] (assoc-in ctx [:coeffects :demo.todo/new-id] (random-uuid))))

;; ---- Event: a PURE handler that reads injected coeffects, never calls them ----
(rf/reg-event-fx :demo.todo/add
  [(rf/inject-cofx :demo.todo/now)
   (rf/inject-cofx :demo.todo/new-id)]
  (fn [{:keys [db demo.todo/now demo.todo/new-id]} _event]
    {:db (assoc-in db [:demo.todo/items new-id]
                   {:id new-id
                    :title (str "Todo #" (inc (count (:demo.todo/items db))))
                    :created-at now})}))

(rf/reg-event-db :demo.todo/initialise
  (fn [_db _event] {:demo.todo/items {}}))

;; ---- Subscription: derive the list of todos from app-db ----
(rf/reg-sub :demo.todo/items
  (fn [db _query] (vals (:demo.todo/items db))))

;; ---- View: plain defn, explicit rf/ verbs ----
(defn todo-list []
  [:div
   [:button {:on-click #(rf/dispatch [:demo.todo/add])} "Add a todo"]
   [:ul
    (for [{:keys [id title created-at]} @(rf/subscribe [:demo.todo/items])]
      ^{:key id}
      [:li title
       [:span {:style {:color "#888" :margin-left "1em" :font-size "0.85em"}}
        (.toLocaleTimeString created-at)]])]])

;; ---- Seed app-db, then hand back the view ----
(rf/dispatch-sync [:demo.todo/initialise])
[todo-list]
```

Read the handler again now that it's running: `:demo.todo/add` never calls `js/Date.` or `random-uuid`. It *destructures* `now` and `new-id` out of its coeffects map, where the two `inject-cofx` interceptors put them on the way in. The impurity is real — each new todo genuinely gets a fresh timestamp and a fresh id — but it lives entirely inside the two cofx handlers, not in the event handler.

> **Try it.** The whole point of a cofx is that you can *swap the impurity out by re-registering the id.* Find `:demo.todo/now` and replace its body with a fixed clock — `(assoc-in ctx [:coeffects :demo.todo/now] (js/Date. "2026-01-01T12:00:00"))` — then re-evaluate and add a few todos. Every one is now stamped noon on New Year's Day. You just stubbed the clock for the whole app by editing five lines, and *the event handler never changed.* That's the testing story, in miniature, and the next section is just this move with a `deftest` around it.

### Testing is just re-registration

This is the payoff that makes the ceremony worth it. A handler that injects `:now` is testable without faking `js/Date`, mocking a clock, or threading a `Clock` argument through every call site. The test re-registers `:now` with a stub, the framework's id-redirect picks it up, and the handler becomes deterministic:

```clojure
(deftest todo-add-stamps-created-at
  (ts/with-fresh-registrar
    (rf/reg-cofx :now
      (fn [ctx] (assoc-in ctx [:coeffects :now] #inst "2026-01-01T12:00:00.000Z")))
    (rf/reg-cofx :new-id
      (fn [ctx] (assoc-in ctx [:coeffects :new-id]
                          #uuid "00000000-0000-0000-0000-000000000001")))
    (let [handler   (:handler-fn (rf/handler-meta :event :todo/add))
          coeffects {:db {} :event [:todo/add "buy milk"]
                     :now #inst "2026-01-01T12:00:00.000Z"
                     :new-id #uuid "00000000-0000-0000-0000-000000000001"}
          {:keys [db]} (handler coeffects [:todo/add "buy milk"])
          todo (get-in db [:todos #uuid "00000000-0000-0000-0000-000000000001"])]
      (is (= "buy milk" (:title todo)))
      (is (= #inst "2026-01-01T12:00:00.000Z" (:created-at todo))))))
```

The stubs aren't mocks — they live in the *same registry* as the production handlers, addressed by the *same keyword id*, and `inject-cofx` finds the re-registered version with no special test-mode flag. `with-fresh-registrar` snapshots the registrar around the body and restores on exit, so production `:now` is intact for the next test (skip it and you've got the classic "passes alone, fails together" bug). And the handler-under-test never knew it was being tested — same shape in production and in the test, only the injected value changed. [Chapter 13](13-testing.md) walks the full testing surface; this is the shape it's built on.

### When the ceremony isn't worth it — the inline escape hatch

`reg-cofx` + `inject-cofx` is the canonical path, and most of the time it's right. But it isn't the *only* legal way to put a value in the coeffects map. `inject-cofx` is just an interceptor, and the interceptor primitive ([chapter 09](09-interceptors.md)) is open — any map of the shape `{:id <id> :before (fn [ctx] ...)}` is a legal participant in an event's interceptor vector, and if its `:before` happens to `assoc-in` something under `[:coeffects k]`, the handler reads it identically.

So why ever pay the registry hop? Because the id buys you real things. A cofx-with-an-id is **addressable**: it's the surface test code re-registers against, the surface a REPL re-binds to hot-swap behaviour on the next dispatch, the surface Xray enumerates to draw the cofx graph, and the surface that lets one handler serve many call sites with different parameters. For maybe 5–10% of cofxes — `:now`, `:new-id`, `:local-store`, anything you'll stub or surface to a tool — those benefits are load-bearing and `reg-cofx` is the obvious reach. For the other 90% — a cofx defined once, used in two events in the same module, never stubbed, never enumerated, never hot-rebound — the registry indirection is ceremony for benefits you'll never claim. There, reach for the inline interceptor instead:

```clojure
(def ^:private inject-now
  {:id     ::inject-now
   :before (fn [ctx] (assoc-in ctx [:coeffects :now] (.getTime (js/Date.))))})

(rf/reg-event-fx :ping
  [inject-now]
  (fn [{:keys [db now]} _] {:db (assoc db :last-ping now)}))
```

Identical runtime behaviour to the registry version; one fewer indirection; no addressability. The rubric writes itself — **use `reg-cofx` if any of these hold**: it might be stubbed in tests by id; you want REPL hot-rebind; devtools should enumerate it; it's parameterised by id. **Use an inline interceptor if all of these hold**: defined once, used in a small set of events in one module; never stubbed; the body is a trivial single `assoc-in`. Default to `reg-cofx` for anything that names a generally-useful input. (Give the inline form a namespaced `::` id anyway, so the runtime can name it in traces and per-frame overrides can target it.) This is design decision **rf2-bku5r** if you want the long version.

## Reading a subscription from a handler

Sooner or later you'll write a handler that needs a *subscription's* current value. An "place order" event wants the logged-in user; an "apply discount" event wants the cart total that `[:cart/total]` already computes. The reflex — and it's the wrong reflex — is to call `rf/subscribe` straight from the handler body:

```clojure
;; Don't do this
(rf/reg-event-fx :order/place
  (fn [{:keys [db]} [_ order]]
    (let [current (rf/subscribe-once [:user/current])]   ;; ← implicit read
      {:db (assoc-in db [:orders (:id order)] (assoc order :placed-by current))})))
```

This breaks the same purity property cofx exist to protect, for the same reasons calling `(js/Date.)` does: the handler's output now depends on whatever `:user/current` computes at drain time, the test framework can't fix it for one handler without globally re-registering the sub, and a recorded epoch won't carry the sub's value, only its id, so replay goes brittle.

The fix is the move you already know — wrap the impure read as a cofx and inject it:

```clojure
(rf/reg-cofx :user/current
  (fn [ctx]
    (assoc-in ctx [:coeffects :user/current] (rf/subscribe-once [:user/current]))))

(rf/reg-event-fx :order/place
  [(rf/inject-cofx :user/current)]
  (fn [{:keys [db user/current]} [_ order]]
    {:db (assoc-in db [:orders (:id order)] (assoc order :placed-by current))}))
```

`rf/subscribe-once` is the right primitive: it materialises the reaction, derefs it, and unsubscribes in one call, so the cofx leaves no live reaction dangling (`@(rf/subscribe ...)` would also give the right value but leak the reaction until GC). When the sub takes arguments, use the binary form and let the call site pick the query.

You'll reach for this often enough that "wrap as cofx" becomes muscle memory — and you may notice re-frame2 deliberately ships *no* `cofx-from-sub` shortcut to collapse those five lines into a helper. That's on purpose. The five lines aren't friction to be papered over; they're the surface area that says *"this is a coeffect, register it like one."* A helper would whisper that subscribing-inside-handlers is the rule and the cofx is the workaround. It's the reverse. The cofx is the rule, and the wrap is the price of admission for any handler reading anything beyond `:db` and `:event`. Sub-values aren't special; they pay the same toll as `:now`.

## One firm rule: coeffects must be synchronous

Before you go off and write your own, one hard line: **a cofx handler MUST resolve synchronously.** No Promise, no `core.async` channel, no callback that fills the coeffect later. The value has to be in hand by the time the cofx fn returns.

The reason is the shape of the cascade. `inject-cofx` runs as a `:before` *before* the handler, and the handler then runs as a pure function of `[coeffects event]` — every key it destructures is assumed materialised. An async cofx breaks that two ways: either the runtime blocks the whole drain loop waiting on the promise (which defeats async and stalls everything), or the handler runs against an unresolved placeholder (which is just a bug). Neither is acceptable, so the runtime doesn't try.

If the world can only hand you the data asynchronously — a fetch, a websocket round-trip — that work belongs on the *output* side, as a managed effect with a follow-on event. The interaction dispatches an event; the handler returns an effect that includes the async work; the effect runs it and dispatches a follow-on event when the result lands; the follow-on arrives synchronously like any other event and reads the now-materialised value. The cascade stays pure end to end:

```clojure
;; ❌ Async cofx — `profile` is a Promise, the handler is broken
(rf/reg-cofx :user/profile
  (fn [ctx] (assoc-in ctx [:coeffects :user/profile] (js/fetch "/api/me"))))

;; ✅ Dispatch event → managed effect → follow-on event
(rf/reg-event-fx :profile/show
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request {:url "/api/me"}
            :on-success [:profile/loaded]
            :on-failure [:profile/load-failed]}]]}))

(rf/reg-event-fx :profile/loaded
  (fn [{:keys [db]} [_ profile]] {:db (assoc db :profile profile)}))
```

The rule of thumb, then: **cofx for values the world hands back instantly** — `js/Date.`, `random-uuid`, `localStorage.getItem`, a sub's current value. **Managed effects for values the world has to go fetch.** If you catch yourself wanting `await` inside a `reg-cofx`, that's the tell: it was never a coeffect. It's an event chain, and [chapter 10](10-http.md) is where it goes.

## The whole trade, said once more

Effects-as-data costs you verbosity, and there's no point pretending otherwise — a fetch in idiomatic React is one `async`/`await` function; here it's two handlers and a registered fx. You're paying for it, and the bill is real. Here's what the money buys, and it's the same four things every time:

**Tests don't need a network.** The effect-producing handler is a pure-function test of its returned map. The fx is tested by stubbing the call. Coeffect-reading handlers are tested by re-registering the cofx. None of it touches React, JSDOM, or a server.

**You can swap any implementation.** Effects and coeffects are looked up by id, so a test redirects the id — not a mock, a registry redirect — and the same dispatch shape the real one produces lands in the test handler.

**You can record what happened.** Because it's all data, the runtime logs it, replays it, ships it across the wire, stores it in a fixture. The trace stream surfaces every effect that fired, with args, in order. Debugging an async flow stops being archaeology.

**The whole state is one value.** State lives in one place and updates atomically, so the app's entire state at any instant is a single value you can capture, compare, and restore as a pointer swap. Undo is a thin interceptor. Time-travel records values, not events. AI experimentation tries a change, observes, reverts — no registry pollution. Every one of those is a downstream consequence of "the handler describes; the runtime does."

That's the deal. You gave up the freedom to do whatever you want wherever you want, and in exchange you got an app whose every impure act is named, addressable, swappable, recordable, and replayable. Less freedom, more inspectability. It's the same trade [chapter 01](01-introduction.md) called the whole game — and effects and coeffects are where you actually sign for it.
