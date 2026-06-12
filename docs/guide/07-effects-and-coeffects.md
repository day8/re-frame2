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
| **Built in for free** | `:db`, `:event`, `:rf.world/inputs` | `:db` |
| **You register more with** | `reg-cofx` | `reg-fx` |
| **Identified by** | a keyword id | a keyword id |
| **The impure work happens in** | the cofx handler | the fx handler |

The handler reads from `:coeffects`, writes to `:effects`, and stays pure in the middle. The runtime fills `:coeffects` *before* the handler runs and drains `:effects` *after* it returns. You've actually been using coeffects all along without noticing — that `{:keys [db]}` you destructure in every `reg-event-fx` handler? That first argument *is* the coeffects map. `:db`, `:event`, and `:rf.world/inputs` are coeffects the runtime stages automatically, every time. Everything past those three is opt-in.

That third one is the load-bearing newcomer, and it's the answer to the most common impurity of all — *what time is it?* — so we'll take it head-on before the general cofx machinery. The current wall-clock time isn't something you `reg-cofx` for; the runtime already hands it to every handler, recorded so replay reproduces it. The [§Causal world inputs](#causal-world-inputs--where-the-clock-and-fresh-ids-come-from) section below is where that lives. The general `reg-cofx` / `inject-cofx` pair you're about to meet is for *everything else* the world hands you — a `localStorage` read, a subscription's value, a host-transient measurement.

### Causal world inputs — where the clock and fresh ids come from

The handler above wanted three things from the world: the current time, a fresh UUID, and a `localStorage` read. The first two have something in common the third doesn't — *they decide what gets written into durable state*. The todo's `:created-at` is the clock; the todo's `:id` is the UUID; both end up persisted in `app-db` and folded into the running total the ledger reproduces. And [chapter 04](04-events-and-the-cascade.md#the-ledger-view) was firm: for the same log to reproduce the same state, every fact a durable write consults has to be a fact the ledger recorded. A clock the handler reaches out and grabs is a fact the ledger never wrote down — so replay lies.

re-frame2's answer is **causal world inputs**: a small recorded map the runtime stamps onto *every* dispatch and hands to *every* handler as the `:rf.world/inputs` coeffect. It has one always-present key, `:time-ms` — wall-clock epoch milliseconds, read **once** at the moment the event entered the fold and then frozen into the recorded token. Durable writes read the clock from there, never from `(js/Date.)`:

```clojure
(rf/reg-event-fx :todo/add
  (fn [{:keys [db] :rf.world/keys [inputs]} [_ {:keys [id text]}]]
    {:db (assoc-in db [:todos id]
                   {:id id :text text :created-at (:time-ms inputs)})}))
```

No `reg-cofx`, no `inject-cofx` — `:rf.world/inputs` is staged automatically, like `:db` and `:event`. The handler is pure: call it twice with the same event *and the same world inputs* and you get the same `:created-at` both times. And because the runtime recorded the `:time-ms` it stamped, replaying the ledger re-presents the same value — `:created-at` reproduces exactly instead of drifting to whatever the wall clock reads on replay day. `:rf.world/inputs` is the canonical home for **every durable wall-clock fact** in the framework: entity `:created-at` / `:updated-at`, resource `:loaded-at` / `:stale-at`, work-ledger and mutation timestamps, durable routing times — they all read `:time-ms`. (The normative contract is [`spec/002-Frames.md` §The `:rf.world/inputs` envelope field](../../spec/002-Frames.md#the-rfworldinputs-envelope-field).)

The fresh **UUID** is the same story — a generated id is a durable fact, so it can't be a `(random-uuid)` the handler grabs mid-body either. Two honest sources: generate it at the dispatch site and put it in the event (`[:todo/add {:id (random-uuid) :text "…"}]` — the id rides the recorded event vector, so replay reproduces it), or, for ids minted inside the fold, read them from world inputs under `:uuid` (a map of domain id-names to generated values: `(get-in inputs [:uuid :todo/id])`). Either way the id lands in the ledger as data, never as an unrecorded host call. The example above takes the dispatch-site route — note the `:id` arrives *in* the event.

> **Tests and replay supply world inputs directly.** Because the clock is a recorded input rather than an ambient read, a test fixes it by *supplying* it on the dispatch — `(rf/dispatch-sync [:todo/add {:id …}] {:rf.world/inputs {:time-ms 1781078400000}})` — and the handler is deterministic, no clock to monkey-patch. SSR hydration, replay fixtures, and host integrations use the same opts key to hand the runtime exact world facts. [Chapter 13](13-testing.md#freezing-the-clock-with-world-inputs) is where that idiom lands.

> **What about `:dispatched-at`?** Earlier drafts of re-frame2 carried an optional `:dispatched-at` field for "when was this dispatched." It's **gone** — durable causal time is now `(:time-ms (:rf.world/inputs …))`, and the *diagnostic* "when did this dispatch fire" need is served by the trace event's own ambient `:time` stamp ([chapter 16](16-observability.md)). One fact, one name.

### `reg-cofx` and `inject-cofx` — the registry pair

World inputs cover the clock and generated ids. *Everything else* the world hands you instantly — a `localStorage` read, a subscription's current value, a host-transient measurement — still arrives through the general coeffect machinery, and that's what `reg-cofx` / `inject-cofx` are for. Quarantine each impurity inside a named, registered cofx:

```clojure
(rf/reg-cofx :local-store
  (fn [ctx storage-key]
    (assoc-in ctx [:coeffects :local-store]
              (some-> (.-localStorage js/globalThis) (.getItem storage-key)))))

(rf/reg-event-fx :prefs/apply-theme
  [(rf/inject-cofx :local-store "ui-theme")]
  (fn [{:keys [db local-store]} _]
    ;; A display preference — diagnostic/transient, not a fact replay must reproduce.
    {:db (assoc db :ui/theme (or local-store "system"))}))
```

The handler is pure again. The impure read lives inside a named cofx handler, addressable by id. Two things to read carefully here.

> **One caveat that decides which mechanism you reach for.** A plain `reg-cofx` read is *ambient and unrecorded* — the ledger never wrote it down, so replay re-runs the cofx against whatever the host says at replay time. That's exactly right for a **non-durable / diagnostic** fact like the display theme above: if replay re-reads it, nothing important drifts. It is **wrong** for a storage value that feeds a *durable* write — a session token or saved document you `assoc` into `:db` and expect to reproduce. A durable storage read has to enter the fold as **recordable** data: capture it as a causal world input (`:storage` under `:rf.world/inputs`), ride it in on the event payload, or use a cofx whose value is captured into the replay record rather than re-read from the host. The rule is the same one the clock follows — *durable writes have no ambients* ([§Why handlers never read the clock](#why-handlers-never-read-the-clock)). `reg-cofx` stays the tool for the instant, non-durable reads; the recordable path is for storage that decides durable state.

A **cofx handler** is a function from context to context. It receives the full context map (the same one interceptors thread, [chapter 09](09-interceptors.md)) and returns it with the value `assoc-in`'d under `[:coeffects <id>]`. The convention is to inject under the same keyword you registered with — cofx id and coeffect key match. There are two arities: the **unary** `(fn [ctx] ...)` for parameterless cofxes, and the **binary** `(fn [ctx value] ...)` for cofxes parameterised at the call site. The `:local-store` cofx above is the classic binary one — it takes the storage key to read at the call site (`(rf/inject-cofx :local-store "ui-theme")`). One `:local-store` handler serves every event that needs a different key — the *what* stays generic, the *which* lives at the call site.

`inject-cofx` is the use-site. It's the small interceptor that, on the way in, looks up the registered cofx fn, calls it (with the second value too, if you called it binary), and lets the now-enriched context flow on toward the handler. List several to compose them — they run in declaration order, and by the time the handler runs, every key is present in its coeffects map. It's a `:before`-only interceptor: a cofx is an input, so there's nothing to do on the way out.

> **`reg-event-db` can't see injected cofx values.** Its signature is `(fn [db event] ...)` — only `:db` is handed over. If you need a cofx, register the event with `reg-event-fx` (or, rarely, `reg-event-ctx` for the whole context map). The interceptors slot still works on `reg-event-db` for *other* purposes — a logger, an undo wrapper — but `inject-cofx` is wasted on it.

### See it run

Time to stop reading and start poking. Here's a todo-adder, live, in your browser. The durable facts — *when* each todo was created, *what* its id is — ride causal world inputs and the event, exactly as the section above prescribed; the one genuine `reg-cofx` reads a non-durable display preference. Click into the cell, hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate it, then add some todos. First run takes a beat while the engine wakes; after that it's instant.

(Live cells are functions-only — the view is a plain `defn` with explicit `rf/dispatch` / `rf/subscribe`; `reg-view` is sugar over exactly this. See [chapter 06](06-views.md#defn-views-and-the-reg-view-equivalence).)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; ---- Event: a PURE handler. The clock comes from :rf.world/inputs (recorded,
;;      replay-safe); the fresh id rides the event from the dispatch site.
;;      Both facts are durable — both are recorded. No ambient read. ----
(rf/reg-event-fx :demo.todo/add
  (fn [{:keys [db] :rf.world/keys [inputs]} [_ {:keys [id]}]]
    {:db (assoc-in db [:demo.todo/items id]
                   {:id id
                    :title (str "Todo #" (inc (count (:demo.todo/items db))))
                    :created-at (:time-ms inputs)})}))

(rf/reg-event-db :demo.todo/initialise
  (fn [_db _event] {:demo.todo/items {}}))

;; ---- Subscription: derive the list of todos from app-db ----
(rf/reg-sub :demo.todo/items
  (fn [db _query] (vals (:demo.todo/items db))))

;; ---- View: plain defn, explicit rf/ verbs. The id is minted at the dispatch
;;      site and rides the event, so it lands in the ledger as recorded data.
;;      The locale used to format the time is a *display-time*, non-durable
;;      choice — it lives at the view, never written into app-db. ----
(defn todo-list []
  [:div
   [:button {:on-click #(rf/dispatch [:demo.todo/add {:id (random-uuid)}])} "Add a todo"]
   [:ul
    (for [{:keys [id title created-at]} @(rf/subscribe [:demo.todo/items])]
      ^{:key id}
      [:li title
       [:span {:style {:color "#888" :margin-left "1em" :font-size "0.85em"}}
        (.toLocaleTimeString (js/Date. created-at) "en-US")]])]])

;; ---- Seed app-db, then hand back the view ----
(rf/dispatch-sync [:demo.todo/initialise])
[todo-list]
```

Read the handler again now that it's running: `:demo.todo/add` never calls `js/Date.` or `random-uuid`. The timestamp arrives as `(:time-ms inputs)` — the recorded world input the runtime stamped at the causal boundary — and the id arrives *in the event*, minted at the click site. The only ambient host read left — the `"en-US"` locale that formats the displayed time — lives at the *view*, a transient render-time choice that never touches durable state. The impurity is real — each todo gets a real timestamp and a real id — but every fact that touches durable state is something the ledger recorded, so replay reproduces it exactly.

> **Try it.** Because the clock is a recorded input rather than an ambient read, you fix it by *supplying* it on the dispatch — not by re-registering a cofx. Change the button to `#(rf/dispatch [:demo.todo/add {:id (random-uuid)}] {:rf.world/inputs {:time-ms 1735732800000}})`, re-evaluate, and add a few todos: every one is now stamped that exact instant (noon UTC, New Year's Day 2025), because you handed the runtime the world fact instead of letting it read the wall clock. That's the testing story in miniature — the next section is the same move with a `deftest` around it.

### Testing is just supplying the inputs

This is the payoff that makes the discipline worth it. A handler that reads `:rf.world/inputs` is testable without faking `js/Date`, mocking a clock, or threading a `Clock` argument through every call site — the test *supplies* the world inputs on the dispatch and the handler becomes deterministic. And a handler that reads a `reg-cofx`-injected value (like `:demo.todo/locale` above) is made deterministic the other way — by re-registering the cofx against the same id, which the framework's id-redirect picks up with no special test-mode flag:

```clojure
(deftest todo-add-stamps-created-at
  (rf/with-new-frame [f (rf/make-frame {})]
    ;; Supply the world inputs on the dispatch — the clock is a recorded fact,
    ;; not a cofx to monkey-patch. The id rides the event, as in production.
    (rf/dispatch-sync [:todo/add {:id   #uuid "00000000-0000-0000-0000-000000000001"
                                  :text "buy milk"}]
                      {:rf.world/inputs {:time-ms 1735732800000}})
    (let [todo (-> (rf/app-db-value f) :todos
                   (get #uuid "00000000-0000-0000-0000-000000000001"))]
      (is (= "buy milk" (:text todo)))
      (is (= 1735732800000 (:created-at todo))))))
```

There's no stub here at all — the test simply *hands the runtime the world fact* it would otherwise read from the wall clock, on the same `:rf.world/inputs` opts key SSR and replay use. The handler-under-test never knew it was being tested: same shape in production and in the test, only the supplied `:time-ms` differs. A handler that reads a `reg-cofx`-injected value (a `localStorage` read, a sub's value) is made deterministic the complementary way — re-register the cofx against the same id under `ts/with-fresh-registrar`, which snapshots the registrar and restores it on exit so production wiring is intact for the next test. [Chapter 13](13-testing.md) walks the full testing surface; this is the shape it's built on.

### Why handlers never read the clock

You now know the *mechanism* — durable facts ride `:rf.world/inputs` and the event, while `reg-cofx` quarantines every other impure read. What's worth pinning down is the *reason*, because it's bigger than "purity is nice," and it reaches back into [chapter 04](04-events-and-the-cascade.md#the-ledger-view).

That chapter made a promise: app-db is the running total of an event ledger, and **two fresh apps fed the same event log finish in identical states.** Re-read that and notice what it quietly requires. For the same log to reproduce the same state, *the only thing a handler is allowed to consult is its recorded inputs* — the db, the event, and the world inputs the ledger recorded. The moment a handler calls `(js/Date.now)` in its body, it has consulted something the ledger *never wrote down*. The state it produces now depends on the wall clock, which isn't in the log. Replay the log tomorrow and you get a different answer. **The handler smuggled in an input the ledger never recorded, and so replaying the ledger lies.** The clock is the most common smuggler — random ids and `localStorage` reads are the same crime in a different coat.

The fix is causal world inputs, seen now through the ledger lens: the clock stops being something the handler *reaches out and grabs* and becomes a **recorded input** — a value the runtime stamps on the way in and the ledger writes down, exactly as if it had ridden the event. Here is the same handler before and after, read for honesty rather than for testability:

```clojure
;; ❌ BROKEN REPLAY — the clock is an ambient read the ledger never recorded.
;;    Replay this event tomorrow and :created-at is tomorrow's date. The log lies.
(rf/reg-event-db :todo/add
  (fn [db [_ title]]
    (assoc-in db [:todos] {:title title :created-at (js/Date.)})))

;; ✅ HONEST REPLAY — the clock is a recorded world input the runtime supplies.
;;    Replay re-presents the same :time-ms, so the same log reproduces the same state.
(rf/reg-event-fx :todo/add
  (fn [{:keys [db] :rf.world/keys [inputs]} [_ title]]
    {:db (assoc-in db [:todos] {:title title :created-at (:time-ms inputs)})}))
```

The difference isn't style. The broken version *cannot* be replayed, restored, or reliably tested; the honest version can, because every fact it used to compute its output is a fact the runtime recorded and can re-supply. Two concrete things this buys, one each side of the seam:

- **Time-travel actually travels.** Restore the app to an earlier point ([chapter 16](16-observability.md#epochs-as-state-over-time)) and re-run the log forward, and a handler that read the *ambient* clock makes a decision keyed to whatever the wall clock happened to read the first time — a decision the restored run can't reproduce, because that instant is gone. The world-input handler re-runs against the *same recorded* `:time-ms` and lands in the *same* state. Restore stops being a best-effort approximation and becomes exact.
- **The 23:59:59 test stops flaking.** A handler that reads `(js/Date.)` to decide "is this due today?" is green every afternoon you run it and red the one time CI happens to run it as the date rolls over at midnight — a failure nobody can reproduce because it depends on the second the suite ran. Read the clock from world inputs and the test *supplies* it; "due today" is computed against a fixed `:time-ms` you chose, and the test means the same thing at 14:00 and at 23:59:59. ([Chapter 13 — Replay is a test](13-testing.md#replay-is-a-test) tells that war story in full.)

<details markdown="1">
<summary>For the categorically curious</summary>

A handler is a **pure function of an explicit world**: every input it depends on is a named argument it was handed, never a value it reached out and read. The clock is part of that world *value* — it arrives as the recorded `:rf.world/inputs` field the runtime supplies — never an *ambient* the function dips into behind the caller's back. "Ambient" is the word for a value read from outside the argument list; the world-input discipline is the rule that durable writes have no ambients, only recorded inputs. (`reg-cofx` extends the same discipline to every *other* world fact a handler needs.)
</details>

### When the ceremony isn't worth it — the inline escape hatch

`reg-cofx` + `inject-cofx` is the canonical path, and most of the time it's right. But it isn't the *only* legal way to put a value in the coeffects map. `inject-cofx` is just an interceptor, and the interceptor primitive ([chapter 09](09-interceptors.md)) is open — any map of the shape `{:id <id> :before (fn [ctx] ...)}` is a legal participant in an event's interceptor vector, and if its `:before` happens to `assoc-in` something under `[:coeffects k]`, the handler reads it identically.

So why ever pay the registry hop? Because the id buys you real things. A cofx-with-an-id is **addressable**: it's the surface test code re-registers against, the surface a REPL re-binds to hot-swap behaviour on the next dispatch, the surface Xray enumerates to draw the cofx graph, and the surface that lets one handler serve many call sites with different parameters. For maybe 5–10% of cofxes — `:local-store`, a sub-value cofx, anything you'll stub or surface to a tool — those benefits are load-bearing and `reg-cofx` is the obvious reach. For the other 90% — a cofx defined once, used in two events in the same module, never stubbed, never enumerated, never hot-rebound — the registry indirection is ceremony for benefits you'll never claim. There, reach for the inline interceptor instead:

```clojure
(def ^:private inject-viewport
  {:id     ::inject-viewport
   :before (fn [ctx] (assoc-in ctx [:coeffects :viewport-w]
                               (.-innerWidth js/window)))})

(rf/reg-event-fx :layout/measure
  [inject-viewport]
  (fn [{:keys [db viewport-w]} _] {:db (assoc db :layout/breakpoint? (> viewport-w 960))}))
```

(Note this measures a *transient* host fact — viewport width — not a durable one. If the value it read decided a durable write, the inline-vs-registry choice would be moot: the clock and generated ids ride world inputs and the event, never a hand-rolled `assoc-in` of `(js/Date.)`.)

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

This breaks the same purity property coeffects exist to protect, for the same reason reading the ambient clock does: the handler's output now depends on whatever `:user/current` computes at drain time, the test framework can't fix it for one handler without globally re-registering the sub, and a recorded epoch won't carry the sub's value, only its id, so replay goes brittle.

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

You'll reach for this often enough that "wrap as cofx" becomes muscle memory — and you may notice re-frame2 deliberately ships *no* `cofx-from-sub` shortcut to collapse those five lines into a helper. That's on purpose. The five lines aren't friction to be papered over; they're the surface area that says *"this is a coeffect, register it like one."* A helper would whisper that subscribing-inside-handlers is the rule and the cofx is the workaround. It's the reverse. The cofx is the rule, and the wrap is the price of admission for any handler reading anything beyond `:db`, `:event`, and `:rf.world/inputs`. Sub-values aren't special; they pay the same toll as any other world fact.

## One firm rule: coeffects must be synchronous

Before you go off and write your own, one hard line: **a cofx handler MUST resolve synchronously.** No Promise, no `core.async` channel, no callback that fills the coeffect later. The value has to be in hand by the time the cofx fn returns.

The reason is the shape of the cascade. `inject-cofx` runs as a `:before` *before* the handler, and the handler then runs as a pure function of `[coeffects event]` — every key it destructures is assumed materialised. An async cofx breaks that two ways: either the runtime blocks the whole drain loop waiting on the promise (which defeats async and stalls everything), or the handler runs against an unresolved placeholder (which is just a bug). Neither is acceptable, so the runtime doesn't try.

If the world can only hand you the data asynchronously — a fetch, a websocket round-trip — that work belongs on the *output* side, as a managed effect with a follow-on event. The interaction dispatches an event; the handler returns an effect that includes the async work; the effect runs it and dispatches a follow-on event when the result lands; the follow-on arrives synchronously like any other event and reads the now-materialised value. The cascade stays pure end to end:

```clojure
;; ❌ Async cofx — `profile` is a Promise, the handler is broken
(rf/reg-cofx :user/profile
  (fn [ctx] (assoc-in ctx [:coeffects :user/profile] (js/fetch "/api/me"))))

;; ✅ Dispatch event → managed effect → reply event
(rf/reg-event-fx :profile/show
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:url "/api/me"}
            :on-success [:profile/replied]}]]}))

(rf/reg-event-fx :profile/replied
  (fn [{:keys [db]} [_ {:keys [kind value]}]]
    (if (= :success kind) {:db (assoc db :profile value)} {})))
```

The completion comes back as an *event* — that's the general async model. `:rf.http/managed` is the shipped HTTP surface, so here you write its public `:on-success` / `:on-failure` sugar and read the reply it appends: `{:kind :success :value v}` on success ([chapter 10](10-http.md#failures-are-a-closed-set-and-thats-the-gift) is the full tour). That sugar lowers internally onto the framework-wide **uniform reply envelope** — the one shape every managed async surface completes through ([chapter 10's note](10-http.md#on-success--on-failure--co-located-rfreply-are-lowering-sugar)). HTTP doesn't take a bare `:rf/reply-to`; surfaces that ship no sugar accept that key directly and complete with a `{:status …}` reply map, but the move is the same — the impure work goes out as a managed effect and the answer arrives back as an event.

The rule of thumb, then: **cofx for values the world hands back instantly** — a `localStorage.getItem`, a sub's current value, a transient host measurement. **The clock and generated ids ride world inputs and the event, not a cofx.** **Managed effects for values the world has to go fetch.** If you catch yourself wanting `await` inside a `reg-cofx`, that's the tell: it was never a coeffect. It's an event chain, and [chapter 10](10-http.md) is where it goes.

## The whole trade, said once more

Effects-as-data costs you verbosity, and there's no point pretending otherwise — a fetch in idiomatic React is one `async`/`await` function; here it's two handlers and a registered fx. You're paying for it, and the bill is real. Here's what the money buys, and it's the same four things every time:

**Tests don't need a network.** The effect-producing handler is a pure-function test of its returned map. The fx is tested by stubbing the call. Coeffect-reading handlers are tested by re-registering the cofx. None of it touches React, JSDOM, or a server.

**You can swap any implementation.** Effects and coeffects are looked up by id, so a test redirects the id — not a mock, a registry redirect — and the same dispatch shape the real one produces lands in the test handler.

**You can record what happened.** Because it's all data, the runtime logs it, replays it, ships it across the wire, stores it in a fixture. The trace stream surfaces every effect that fired, with args, in order. Debugging an async flow stops being archaeology.

**The whole state is one value.** State lives in one place and updates atomically, so the app's entire state at any instant is a single value you can capture, compare, and restore as a pointer swap. Undo is a thin interceptor. Time-travel records values, not events. AI experimentation tries a change, observes, reverts — no registry pollution. Every one of those is a downstream consequence of "the handler describes; the runtime does."

That's the deal. You gave up the freedom to do whatever you want wherever you want, and in exchange you got an app whose every impure act is named, addressable, swappable, recordable, and replayable. Less freedom, more inspectability. It's the same trade [chapter 01](01-introduction.md) called the whole game — and effects and coeffects are where you actually sign for it.
