# Test an event handler

You wrote an event handler — the function that decides how your app's state changes when something happens — and now you want a unit test for it. The good news is that this test should run in a millisecond, with no browser, no DOM, and no test double for the network or the clock. The recipe is short: pull the handler out of the registry, call it with literal values, and assert on what it returns.

> **Coming from Vitest or Jest?** This is the Redux-reducer test — call the function, check the return — except it never hits the ceiling where you'd reach for `vi.mock` or fake timers. A handler's world arrives as declared data and its side effects leave as data, so the plain function call covers the ground mocks cover in JS.

Here's the one idea to hold on to as you read:

> **The handler returned a map. You checked the map.**

## 1. Pluck the handler and call it

When you register a handler, it lands in a registry — a process-wide table that maps an event id to the function you wrote. `handler-meta` reads those registrations back. You ask it for a *kind* and an *id* — for an event that's `(rf/handler-meta :event :some/id)` — and the map it hands back carries the registration's metadata plus its `:handler-fn`, which is your function, exactly as you wrote it. Your test namespace needs three requires: `clojure.test`, `re-frame.core`, and the app namespace whose load performs the registrations. That last one matters, because requiring the namespace is what runs the `reg-event` calls and puts your handler in the registry in the first place.

```clojure
(ns my-app.articles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))   ;; loading the ns registers the handlers
```

Start with the simplest handler — one that only touches state. Under the one event form, every handler is a plain **two-arg function**: it takes the **coeffects** (the facts it's handed; `:db`, the current app-db, is one) and the event vector, and returns an effects map whose `:db` is the next state:

```clojure
;; my-app/articles.cljs
(rf/reg-event :articles/page-changed
  (fn [{:keys [db]} [_ page]]
    {:db (assoc-in db [:articles :page] page)}))
```

The test plucks it, calls it with a coeffects map and an event vector, and asserts on the `:db` it returns:

```clojure
(deftest page-changed-sets-page
  (let [handler (:handler-fn (rf/handler-meta :event :articles/page-changed))
        result  (handler {:db {:articles {:page 1}}} [:articles/page-changed 3])]
    (is (= 3 (get-in result [:db :articles :page])))))
```

There's no frame, no dispatch, no runtime here — it's just a function call. Which means these tests run wherever your test runner runs. That includes the JVM, where most re-frame2 suites live, because nothing in them touches a browser.

> **Gotcha — `handler-meta` returns `nil` for an unregistered id.** If you typo the id, or forget to require the app namespace so the registration never ran, `(rf/handler-meta :event :articels/page-changed)` returns `nil`, and `(:handler-fn nil)` is `nil` — so the next line tries to *call* `nil` and you get a "nil is not a function" blow-up rather than a clear "no such handler". The two usual causes are a misspelled id and a missing `:require`. If a handler you *know* you registered comes back `nil`, check the require list first.

> **A handler may legitimately return `nil`.** A handler that performs only side effects — say it dispatches a follow-up but changes no state — returns `nil` (or an effects map with no `:db`), and that is valid (per [Events and the cascade](../concepts/events-and-the-cascade.md)). Test it by asserting on `:fx` rather than `:db`; don't read `nil` as a failure.

## 2. A handler that needs the world

Some handlers need to know things about the outside world — the current time, a random number, a value from local storage. In re-frame2, a handler that consumes one of these world facts has to declare it up front. The one below stamps *when* the refresh was asked for, then asks for an HTTP request:

```clojure
;; my-app/articles.cljs — adapted from examples/reagent/realworld/articles.cljs
(rf/reg-event :articles/refresh
  {:doc "User asked for a fresh feed: stamp when, issue the request."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (assoc-in db [:articles :refreshing-since] time-ms)
     :fx [[:rf.http/managed {:request    {:method :get :url "/articles"}
                             :on-success [:articles/loaded]
                             :on-failure [:articles/load-failed]}]]}))
```

`:rf.cofx/requires` lists the coeffects — the world facts the handler reads in — that this handler consumes. Here that's just the clock. They arrive **flat** in its first argument, the coeffects map, alongside `:db`. Note that this handler is registered with the very same `reg-event` as the one above — declaring a world fact is just a line of metadata and an `:fx` vector when there's an effect to issue, not a different registration form.

That declaration doubles as your fixture checklist — the list of facts the test must hand in. You can read it straight off the registry:

```clojure
(:rf.cofx/requires (rf/handler-meta :event :articles/refresh))
;; => [:rf/time-ms]
```

The test supplies whatever that vector lists, as literal entries in the coeffects map. Nothing else is needed, and nothing undeclared is ever delivered — so the checklist is complete by construction.

```clojure
(deftest refresh-stamps-and-asks
  (let [handler (:handler-fn (rf/handler-meta :event :articles/refresh))
        result  (handler {:db {} :rf/time-ms 1781078400123}
                         [:articles/refresh])]
    ;; the state change it computed
    (is (= 1781078400123 (get-in result [:db :articles :refreshing-since])))
    ;; the request it asked for — as data
    (is (= [:rf.http/managed {:request    {:method :get :url "/articles"}
                              :on-success [:articles/loaded]
                              :on-failure [:articles/load-failed]}]
           (first (:fx result))))))
```

Look at what didn't happen here, because this is the part that trips people up. The handler did not fire an HTTP request. Its job is to *describe* one — an effect is just a piece of data saying "please do this" — and the runtime, which is absent in this test, would be the thing that actually performs it. So the test asserts on the description. No fetch was mocked because no fetch was involved. The clock wasn't frozen with fake timers; the clock was simply an entry in a map you wrote. The handler returned a map. You checked the map. For why the world only ever appears at this boundary, see [Effects and coeffects](../concepts/effects-and-coeffects.md).

> **The coeffects map carries exactly what the handler declared — plus `:db` and `:event`.** A handler receives `:db`, the leaves it named in `:rf.cofx/requires`, and — if its destructuring reads it — the whole event vector under `:event`. (Most handlers destructure the event vector as the second argument, as above, and never need `:event`.) Nothing *undeclared* is ever delivered, which is the whole point: the coeffects map is a closed, hand-buildable input. If your handler reaches for a key you didn't supply, that's a clear signal the test's input map is incomplete — read the `:rf.cofx/requires` vector off the registry and supply each leaf.

> **Coming from re-frame v1?** Nothing is injected by interceptor anymore — the requires vector in the metadata is the whole mechanism, and the facts arrive flat in the coeffects map, not nested. [From re-frame v1](../25-from-re-frame-v1.md) has the full delta.

## 3. When you want the runtime: a fresh frame per test

The pure call from the last section tests the handler's logic, and most of the time that's all you need. But sometimes you want to go one notch up: to prove the registration actually wires in, drive a real `dispatch` — the call that sends an event into the system — and read the state that gets committed as a result. For that you give the test its own **frame**: an isolated runtime context with its own app-db, so tests can't leak state into each other ([Frames](../concepts/frames.md)). `with-new-frame` creates one, makes it current for the body, and tears it down on the way out — whether the body succeeds or throws.

```clojure
(deftest refresh-stamps-through-the-runtime
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:articles/refresh]
                      {:rf.cofx      {:rf/time-ms 1781078400123}
                       :fx-overrides {:rf.http/managed (fn [_ _req] nil)}})
    (is (= 1781078400123
           (get-in (rf/app-db-value f) [:articles :refreshing-since])))))
```

Two dispatch options do the work that the literal coeffects map did back in step 2:

- **`:rf.cofx`** supplies recordable facts on the dispatch — the same surface SSR hydration and replay use. Supplied values win; the runtime fills in only what's missing. Without it, the enqueue stamp hands the handler the real wall clock and your assertion chases the wall.
- **`:fx-overrides`** redirects an effect for this one dispatch. Here it swallows the HTTP request, because this test only cares about the stamp. Answering the request with a canned reply and asserting the whole chain is the next page's job: [Test a full cascade](test-a-cascade.md).

A third option, `{:frame f}`, says *which* frame to dispatch into. Inside a `with-new-frame` body you can skip it — the macro pins `f` as the current frame for the body — but outside one (e.g. when you keep a frame in a `let` and tear it down yourself), pass `:frame` explicitly. There is no ambient default frame; the target is always either carried by scope or named on the opts map.

`dispatch-sync` drains the entire cascade before returning, which is why the assertion on the next line can read fully committed state — there's nothing to flush and nothing to await.

### When a required coeffect is missing: a loud failure, by design

There's a failure mode here that catches people, and it's a *feature*. If a handler **declares** a recordable coeffect that the framework can't mint for you, and the dispatch doesn't supply it, the dispatch fails loudly with `:rf.error/missing-required-cofx` rather than quietly minting a value your test didn't choose.

`:rf/time-ms` is the exception that always succeeds — the router stamps every event with an enqueue time, so the clock is never *missing*. The strict failure fires for a *generator-backed* fact you declared but didn't supply — say a fresh id from a `reg-cofx` generator:

```clojure
(rf/reg-event :todo/create
  {:rf.cofx/requires [:app/new-id]}     ;; a reg-cofx with a value-returning generator
  (fn [{:keys [db app/new-id]} [_ text]]
    {:db (assoc-in db [:todos new-id] {:text text})}))

;; A :test-preset frame is strict-mint by default. Supply the id, or the
;; dispatch fails with :rf.error/missing-required-cofx — it will NOT silently
;; mint a different id than production would.
(rf/dispatch-sync [:todo/create "milk"]
                  {:frame f :rf.cofx {:app/new-id "id-123"}})
```

This is exactly the trap you want sprung: a silently-minted random value would make the test green against a state production will never produce. The fix is always one of two moves — supply the fact in `:rf.cofx` (the deterministic path, almost always what you want), or, when you genuinely want a fresh value per run, opt back into live minting with `{:rf.cofx/mint-policy :explicit-live}` as a dispatch opt.

> **The `:test` frame preset bundles the deterministic defaults.** `(rf/reg-frame :my-test {:preset :test})` (or `:preset :test` on the advanced `re-frame.frame/make-frame`) expands to three fixed entries: it redirects `:rf.http/managed` to its canned-success stub so a test frame never reaches the network, surfaces the default `:drain-depth 100` so tooling can read "this is a test frame", and sets `:rf.cofx/mint-policy :strict` — the strict-mint behaviour above. Reach for it when you want those defaults without spelling each one out.

### Seeding multiple events, and ergonomic state assertions

When a test needs several setup dispatches before the one under test, `re-frame.test-support/dispatch-sequence` fires a vector of events in order and returns the final committed app-db — tidier than a stack of `dispatch-sync` calls:

```clojure
(deftest feed-after-a-sequence
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [final (ts/dispatch-sequence [[:articles/init]
                                       [:articles/page-changed 2]
                                       [:articles/page-changed 3]])]
      (is (= 3 (get-in final [:articles :page]))))))
```

`dispatch-sequence` takes an optional opts map: `:frame` (dispatch into a named frame instead of the current one) and `:after-each` (a `(fn [db event] …)` called once per event with the state committed after it — handy for asserting on each intermediate step, not just the final state).

For the assertion itself, `test-support` ships two `clojure.test`-aware helpers that read the *current* (or a named) frame's app-db so you don't have to thread `app-db-value` through `get-in` by hand: `(ts/assert-path-equals path expected)` checks one path, and `(ts/assert-db-equals expected-db)` checks the whole map. Both take an optional `{:frame …}` opt and report a `:pass` / `:fail` through `clojure.test`, so they slot straight into a `deftest`:

```clojure
(deftest page-committed
  (rf/with-new-frame [_ (rf/make-frame {})]
    (rf/dispatch-sync [:articles/init])
    (rf/dispatch-sync [:articles/page-changed 3])
    (ts/assert-path-equals [:articles :page] 3)))
```

## 4. The trap: frames don't isolate registrations

There's a footgun here worth slowing down for. `with-new-frame` gives each test its own app-db, but it does **not** give each test its own registry. `reg-event` and its siblings register into a process-global registrar — one table shared across the whole test run.

> **Gotcha — same id, last load wins.** If two test namespaces register different handlers under the same id, the later load silently wins. That's how you get the classic flake-hunt horror: every test passes alone, the suite fails together, and the failure jumps around as test order changes. It's maddening to chase, so guard against it before it bites.

If your tests — or helpers they load — register anything themselves, bracket each test with a registrar snapshot/restore so the registry is put back the way it was:

```clojure
(use-fixtures :each (fn [test-fn] (ts/with-fresh-registrar test-fn)))
```

`ts/with-fresh-registrar` rolls the registry back on the way out, while keeping the ns-load registrations it captured at the start.

### Picking the right reset fixture

`with-fresh-registrar` resets exactly the registry — and nothing else. `re-frame.test-support` offers a small ladder of reset fixtures so you can match the cleanup to what your suite actually touches:

| Reach for | When |
|---|---|
| `ts/snapshot-registrar` + `ts/restore-registrar!` | You're hand-rolling a fixture and want the raw snapshot/restore primitives — capture the registrar map, restore it later. |
| `ts/with-fresh-registrar` | An ad-hoc `deftest` or REPL block whose only shared state is the registry — no frames, no flows, no schemas left to clean up. |
| `ts/make-reset-runtime-fixture` | The **default for any real suite**. It snapshots/restores the registrar *and* resets the rest of per-process state — frames, flows, schemas, machine timers, routing counters, in-flight HTTP, epoch history, trace listeners. It's a *factory*: call it to get the fixture fn. |

```clojure
;; The standard shape for a suite that exercises more than the registrar:
(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

`make-reset-runtime-fixture` is the right default because its resets are no-ops when an artefact is absent — a plain JVM event-handler suite that never pulls flows or schemas doesn't pay for resetting them. Note the call-shape difference: `with-fresh-registrar` *takes a thunk and runs it* (`(ts/with-fresh-registrar test-fn)`), while `make-reset-runtime-fixture` *returns a fixture fn* you hand to `use-fixtures` (`(ts/make-reset-runtime-fixture {})`). Mixing the two shapes up is the usual stumble.

---

**You can now:**

- pluck any registered handler with `handler-meta` and call it as the function it is
- read a handler's `:rf.cofx/requires` as the checklist of facts a test must supply
- assert on the effects a handler asks for without performing any of them
- run a handler through a throwaway frame with the clock pinned via `:rf.cofx`
- recognise a missing-coeffect failure (`:rf.error/missing-required-cofx`) as the deterministic guard it is, and supply the fact (or opt into live minting)
- seed setup with `dispatch-sequence` and assert with `assert-path-equals` / `assert-db-equals`
- guard a suite against registrar bleed with `with-fresh-registrar`, and reach for `make-reset-runtime-fixture` when the suite touches more than the registry
