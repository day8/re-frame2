# Test an event handler

You've written an event handler and you want a unit test for it — one that runs in a millisecond, with no browser, no DOM, and no test double standing in for the network or the clock. This page is that recipe: pluck the handler out of the registry, call it with literal values, assert on what it returns.

> **Coming from Vitest or Jest?** This is the Redux-reducer test — call the function, check the return — except it never hits the ceiling where you'd reach for `vi.mock` or fake timers. A handler's world arrives as declared data and its side effects leave as data, so the plain function call covers the ground mocks cover in JS.

The takeaway, up front:

> **The handler returned a map. You checked the map.**

## 1. Pluck the handler and call it

Registrations land in a registry, and `handler-meta` reads them back: `:handler-fn` is the function you registered, exactly as you wrote it. A test namespace needs `clojure.test`, `re-frame.core`, and the app namespace whose load performs the registrations:

```clojure
(ns my-app.articles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))   ;; loading the ns registers the handlers
```

Given a plain `reg-event-db` handler:

```clojure
;; my-app/articles.cljs
(rf/reg-event-db :articles/page-changed
  (fn [db [_ page]]
    (assoc-in db [:articles :page] page)))
```

the test plucks it, calls it with a db value and an event vector, and asserts on the db it returns:

```clojure
(deftest page-changed-sets-page
  (let [handler (:handler-fn (rf/handler-meta :event :articles/page-changed))
        after   (handler {:articles {:page 1}} [:articles/page-changed 3])]
    (is (= 3 (get-in after [:articles :page])))))
```

No frame, no dispatch, no runtime. These tests run wherever your test runner runs — including the JVM, where most re-frame2 suites live — because nothing in them touches a browser.

## 2. A handler that needs the world

Handlers that consume world facts declare them. This one stamps *when* the refresh was asked for, then asks for an HTTP request:

```clojure
;; my-app/articles.cljs — adapted from examples/reagent/realworld/articles.cljs
(rf/reg-event-fx :articles/refresh
  {:doc "User asked for a fresh feed: stamp when, issue the request."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (assoc-in db [:articles :refreshing-since] time-ms)
     :fx [[:rf.http/managed {:request    {:method :get :url "/articles"}
                             :on-success [:articles/loaded]
                             :on-failure [:articles/load-failed]}]]}))
```

`:rf.cofx/requires` lists the facts the handler consumes — here the clock — and they arrive **flat** in its first argument, the coeffects map, alongside `:db`. (Only the fx form can declare requires; a `reg-event-db` handler receives the db and nothing else — needing the world is what graduates a handler to `reg-event-fx`.)

That declaration is your **fixture checklist**. Read it straight off the registry:

```clojure
(:rf.cofx/requires (rf/handler-meta :event :articles/refresh))
;; => [:rf/time-ms]
```

Whatever that vector lists, the test supplies as literal entries in the coeffects map. Nothing else is needed, and nothing undeclared is ever delivered:

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

Look at what didn't happen. The handler did not fire an HTTP request — its job is to *describe* one, and the runtime (absent here) would perform it. So the test asserts on the description. No fetch was mocked because no fetch was involved. The clock wasn't frozen with fake timers; the clock was an entry in a map you wrote. The handler returned a map. You checked the map. Why the world only ever appears at this boundary is [Effects and coeffects](../concepts/effects-and-coeffects.md).

> **Coming from re-frame v1?** Nothing is injected by interceptor anymore — the requires vector in the metadata is the whole mechanism, and the facts arrive flat in the coeffects map, not nested. [From re-frame v1](../25-from-re-frame-v1.md) has the full delta.

## 3. When you want the runtime: a fresh frame per test

The pure call tests the handler's logic. One notch up — prove the registration wires in, drive a real dispatch, read committed state — give the test its own **frame**: an isolated runtime context with its own app-db ([Frames](../concepts/frames.md)). `with-new-frame` creates it, makes it current for the body, and destroys it on the way out, success or exception:

```clojure
(deftest refresh-stamps-through-the-runtime
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:articles/refresh]
                      {:rf.cofx      {:rf/time-ms 1781078400123}
                       :fx-overrides {:rf.http/managed (fn [_ _req] nil)}})
    (is (= 1781078400123
           (get-in (rf/app-db-value f) [:articles :refreshing-since])))))
```

Two dispatch options do the work the literal coeffects map did in step 2:

- **`:rf.cofx`** supplies recordable facts on the dispatch — the same surface SSR hydration and replay use. Supplied values win; the runtime fills in only what's missing. Without it, the enqueue stamp hands the handler the real wall clock and your assertion chases the wall.
- **`:fx-overrides`** redirects an effect for this one dispatch. Here it swallows the HTTP request, because this test only cares about the stamp. Answering the request with a canned reply and asserting the whole chain is the next page's job: [Test a full cascade](test-a-cascade.md).

`dispatch-sync` drains the entire cascade before returning, so the assertion on the next line reads fully committed state — nothing to flush, nothing to await.

## 4. The trap: frames don't isolate registrations

`with-new-frame` gives each test its own app-db. It does **not** give each test its own registry — `reg-event-fx` and friends register into a process-global registrar. If two test namespaces register different handlers under the same id, the later load wins, and you get the classic horror: every test passes alone, the suite fails together, and the failure moves with test order.

If your tests (or helpers they load) register anything themselves, bracket each test with a registrar snapshot/restore:

```clojure
(use-fixtures :each (fn [test-fn] (ts/with-fresh-registrar test-fn)))
```

`ts/with-fresh-registrar` rolls the registry back on the way out while keeping the ns-load registrations it captured. Its bigger sibling `ts/make-reset-runtime-fixture` resets the rest of the process state too — frames, flows, schemas, trace listeners — and is the right default `:each` fixture for suites that exercise more than the registrar.

---

**You can now:**

- pluck any registered handler with `handler-meta` and call it as the function it is
- read a handler's `:rf.cofx/requires` as the checklist of facts a test must supply
- assert on the effects a handler asks for without performing any of them
- run a handler through a throwaway frame with the clock pinned via `:rf.cofx`
- guard a suite against registrar bleed with `with-fresh-registrar`

**Next:** [Test a full cascade](test-a-cascade.md) — multi-event chains, HTTP replies, and machine flows through one frame · [Effects and coeffects](../concepts/effects-and-coeffects.md) — why handlers describe the world instead of touching it
