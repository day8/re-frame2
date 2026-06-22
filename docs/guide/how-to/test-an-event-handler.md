# Test an event handler

You wrote an event handler — the function that decides how your app's state changes when something happens — and now you want a unit test for it. The good news is that this test should run in a millisecond, with no browser, no DOM, and no test double for the network or the clock. The recipe is short: pull the handler out of the registry, call it with literal values, and assert on what it returns.

> **Coming from Vitest or Jest?** This is the Redux-reducer test — call the function, check the return — except it never hits the ceiling where you'd reach for `vi.mock` or fake timers. A handler's world arrives as declared data and its side effects leave as data, so the plain function call covers the ground mocks cover in JS.

Here's the one idea to hold on to as you read:

> **The handler returned a map. You checked the map.**

## 1. Pluck the handler and call it

When you register a handler, it lands in a registry — a process-wide table that maps an event id to the function you wrote. `handler-meta` reads those registrations back, and its `:handler-fn` is your function, exactly as you wrote it. Your test namespace needs three requires: `clojure.test`, `re-frame.core`, and the app namespace whose load performs the registrations. That last one matters, because requiring the namespace is what runs the `reg-event` calls and puts your handler in the registry in the first place.

```clojure
(ns my-app.articles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))   ;; loading the ns registers the handlers
```

Start with the simplest handler — one that only touches state. It takes the **coeffects** (the facts it's handed; `:db`, the current app-db, is one) and returns an effects map whose `:db` is the next state:

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

`dispatch-sync` drains the entire cascade before returning, which is why the assertion on the next line can read fully committed state — there's nothing to flush and nothing to await.

## 4. The trap: frames don't isolate registrations

There's a footgun here worth slowing down for. `with-new-frame` gives each test its own app-db, but it does **not** give each test its own registry. `reg-event` and its siblings register into a process-global registrar — one table shared across the whole test run.

> **Gotcha — same id, last load wins.** If two test namespaces register different handlers under the same id, the later load silently wins. That's how you get the classic flake-hunt horror: every test passes alone, the suite fails together, and the failure jumps around as test order changes. It's maddening to chase, so guard against it before it bites.

If your tests — or helpers they load — register anything themselves, bracket each test with a registrar snapshot/restore so the registry is put back the way it was:

```clojure
(use-fixtures :each (fn [test-fn] (ts/with-fresh-registrar test-fn)))
```

`ts/with-fresh-registrar` rolls the registry back on the way out, while keeping the ns-load registrations it captured at the start. Its bigger sibling `ts/make-reset-runtime-fixture` resets the rest of the process state too — frames, flows, schemas, trace listeners — and that one is the right default `:each` fixture for suites that exercise more than the registrar.

---

**You can now:**

- pluck any registered handler with `handler-meta` and call it as the function it is
- read a handler's `:rf.cofx/requires` as the checklist of facts a test must supply
- assert on the effects a handler asks for without performing any of them
- run a handler through a throwaway frame with the clock pinned via `:rf.cofx`
- guard a suite against registrar bleed with `with-fresh-registrar`
