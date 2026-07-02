# Test an event handler

You wrote an [**event handler**](../glossary.md#event-handler) — the pure function that runs in response to a dispatched [event](../glossary.md#event) and computes how your app's state should change — and now you want a unit test for it. The good news: this test runs in a millisecond, with no browser, no DOM, and no test double for the network or the clock. The recipe is short — pull the handler out of the [registrar](../glossary.md#registrar), call it with literal values, assert on what it returns.

Hold on to one sentence as you read this page, because it's the whole trick:

> **The handler returned a map. You checked the map.**

We start there — the bare function call — and add exactly one idea at a time: handlers that read the world, the full runtime when you actually want it, and the one footgun that takes down a whole suite at once.

## 1. Pluck the handler and call it

An event handler is just a function. When you register one, it lands in the [registrar](../glossary.md#registrar) — the single, process-global table keyed by kind + id that every `reg-*` form writes to. So the simplest possible test is: get the function back, call it, check the answer.

Start with the simplest handler — one that only touches state. Every re-frame2 event handler has the same shape: a plain **two-arg function**. The first argument is the [**coeffects**](../glossary.md#coeffect) map — the declared facts the framework hands the handler so it can stay pure (`:db`, the current [app-db](../glossary.md#app-db), is always one of them). The second is the **event vector** — the `[:some/id ...args]` that was dispatched. And it returns an [**effect map**](../glossary.md#effect-map): a description of what should change, whose `:db` key is the next state.

```clojure
;; my-app/articles.cljs
(rf/reg-event :articles/page-changed
  (fn [{:keys [db]} [_ page]]
    {:db (assoc-in db [:articles :page] page)}))
```

To get that function back in a test, ask the registrar for it. `handler-meta` reads registrations back: you give it a *kind* and an *id* — for an event that's `(rf/handler-meta :event :some/id)` — and the map it returns carries the registration's metadata plus its `:handler-fn`, which is your function, exactly as you wrote it. The test plucks it, calls it with a coeffects map and an event vector, and asserts on the `:db` it returns:

```clojure
(deftest page-changed-sets-page
  (let [handler (:handler-fn (rf/handler-meta :event :articles/page-changed))
        result  (handler {:db {:articles {:page 1}}} [:articles/page-changed 3])]
    (is (= 3 (get-in result [:db :articles :page])))))
```

That's the entire pattern. No [frame](../glossary.md#frame), no [dispatch](../glossary.md#dispatch), no runtime — just a function call. Which means these tests run wherever your test runner runs, including the JVM, where most re-frame2 suites live, because nothing in them touches a browser.

One setup detail makes it work. Your test namespace needs three requires — `clojure.test`, `re-frame.core`, and the app namespace whose *load* performs the registrations. That last one is the easy one to forget, because requiring the namespace is what runs the `reg-event` calls and puts your handler in the registrar in the first place. (Setting up the runner itself — the `deps.edn` `:test` alias, and the `.cljc` discipline that lets your registration namespaces load on the JVM at all — is walked in [the tutorial's Part 5: test it, ship it](../../resources/tutorial/05-test-and-ship.md).)

```clojure
(ns my-app.articles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))   ;; loading the ns registers the handlers
```

> **Coming from Redux?** This is the reducer test — call the function, check the return — except it never hits the ceiling where you'd reach for `vi.mock` or fake timers. A handler's world arrives as declared data and its side effects leave as data, so the plain function call covers the ground that mocks cover in JS. If you're coming from Vitest or Jest, notice what's *absent*: no mock module, no spy, no `beforeEach` wiring up a fake clock.

> **Gotcha — `handler-meta` returns `nil` for an unregistered id.** Typo the id, or forget the app-namespace require so the registration never ran, and `(rf/handler-meta :event :articels/page-changed)` returns `nil` — then `(:handler-fn nil)` is `nil`, so the next line tries to *call* `nil` and you get a "nil is not a function" blow-up rather than a clear "no such handler". The two usual causes are a misspelled id and a missing `:require`. If a handler you *know* you registered comes back `nil`, check the require list first.

> **A handler may legitimately return `nil`.** A handler that performs only side effects — say it dispatches a follow-up but changes no state — returns `nil`, or an effect map with no `:db`, and that's valid (see [Events and the cascade](../concepts/events-and-the-cascade.md)). Test it by asserting on `:fx` rather than `:db`; don't read `nil` as a failure.

## 2. A handler that needs the world

Some handlers need to know things about the outside world — the current time, a random number, a value from local storage. In re-frame2 a handler that consumes one of these has to **declare** it up front as a [**coeffect**](../glossary.md#coeffect). That declaration is exactly what keeps the handler pure: the world arrives as ordinary data in its first argument, so the test can hand it in by hand.

The handler below stamps *when* a refresh was asked for, then asks for an HTTP request:

```clojure
;; my-app/articles.cljs — adapted from examples/real-apps/realworld_http/articles.cljs
(rf/reg-event :articles/refresh
  {:doc "User asked for a fresh feed: stamp when, issue the request."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (assoc-in db [:articles :refreshing-since] time-ms)
     :fx [[:rf.http/managed {:request    {:method :get :url "/articles"}
                             :on-success [:articles/loaded]
                             :on-failure [:articles/load-failed]}]]}))
```

`:rf.cofx/requires` lists the coeffects this handler consumes — here just the clock. They arrive **flat** in the first argument, the coeffects map, alongside `:db`. Note this is the *very same* `reg-event` as section 1: declaring a coeffect is a line of metadata plus an `:fx` vector when there's an effect to issue — not a different registration form.

That `:rf.cofx/requires` declaration doubles as your fixture checklist — the list of facts the test must hand in. You can read it straight off the registrar:

```clojure
(:rf.cofx/requires (rf/handler-meta :event :articles/refresh))
;; => [:rf/time-ms]
```

So the test supplies exactly what that vector lists, as literal entries in the coeffects map — nothing more, nothing less:

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

Look at what *didn't* happen here, because this is the part that trips people up. The handler did not fire an HTTP request. Its job is to *describe* one — an [effect](../glossary.md#effect) is just a piece of data saying "please do this" — and the runtime, which is absent in this test, would be the thing that actually performs it. So the test asserts on the description. No fetch was mocked because no fetch was involved. The clock wasn't frozen with fake timers; the clock was simply an entry in a map you wrote. The handler returned a map. You checked the map. For why the world only ever appears at this boundary, see [Effects and coeffects](../concepts/effects-and-coeffects.md).

> **The coeffects map carries exactly what the handler declared — plus `:db` and `:event`.** A handler receives `:db`, the leaves it named in `:rf.cofx/requires`, and — if its destructuring reads it — the whole event vector under `:event`. (Most handlers destructure the event vector as the second argument, as above, and never need `:event`.) Nothing *undeclared* is ever delivered, which is the whole point: the coeffects map is a closed, hand-buildable input. If your handler reaches for a key you didn't supply, that's a clear signal the test's input map is incomplete — read the `:rf.cofx/requires` vector off the registry and supply each leaf.

> **From re-frame v1.** Nothing is injected by interceptor anymore — the `:rf.cofx/requires` vector in the metadata is the whole mechanism, and the facts arrive flat in the coeffects map, not nested under `:coeffects`. The old "register a `:db` coeffect interceptor, chain it onto the handler" dance is gone. [From re-frame v1](../25-from-re-frame-v1.md) has the full delta.

> **Going deeper.** Declaring the world up front makes the handler a *reader* in the functional sense: a function from an environment (the coeffects map) to a value (the effect map), `cofx -> fx`. The framework is the interpreter that builds the environment and runs the effects; your handler is pure description in between. That's why the test needs no mocks — you're calling a pure function with a literal environment, exactly the way you'd test any `(f input) => output`. The `:rf.cofx/requires` vector is, in effect, the function's *type signature* for its environment — and it's machine-readable, right off the registrar.

## 3. When you want the runtime: a fresh frame per test

The pure call from the last section tests the handler's *logic* — but it skips the runtime entirely. It plucks the function out of the registry and calls it directly, so it never checks that dispatching `[:articles/refresh]` actually finds and runs that handler, nor that the `:db` it returns really lands in app-db. Most of the time you don't need to check that — the wiring is the framework's job, not yours. But sometimes you want the extra confidence. For that you go one notch up: drive a real [dispatch](../glossary.md#dispatch) — the call that sends an event into the system the way your app does — and read the state that ends up committed.

That means giving the test its own [**frame**](../glossary.md#frame): an isolated runtime context with its own app-db, so tests can't leak state into each other (see [Frames](../concepts/frames.md)). `with-new-frame` creates one, makes it current for the body, and tears it down on the way out — whether the body returns or throws.

```clojure
(deftest refresh-stamps-through-the-runtime
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:articles/refresh]
                      {:rf.cofx      {:rf/time-ms 1781078400123}
                       :fx-overrides {:rf.http/managed (fn [_frame-ctx _args] nil)}})
    (is (= 1781078400123
           (get-in (rf/app-db-value f) [:articles :refreshing-since])))))
```

[`dispatch-sync`](../glossary.md#dispatch-sync) drains the entire [cascade](../glossary.md#event-cascade) before returning, which is why the assertion on the next line can read fully committed state — there's nothing to flush and nothing to await.

> **Gotcha — `dispatch-sync` is for the test boundary, not for inside a handler.** Call it from your test (or at boot, or the REPL) — never from inside a running handler. A handler that calls `(rf/dispatch-sync [:other] …)` in its body [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/dispatch-sync-in-handler`: a handler must stay pure and *describe* a follow-up dispatch as data, not synchronously drive one. The in-handler shape is the `:fx` effect `[[:dispatch [:other]]]`, which the runtime drains as part of the same cascade. (A bare `(rf/dispatch [:other])` from a handler body is *not* this error — it queues normally and routes to the handler's frame — but the `:fx` form is the idiom you want.)

Two dispatch options do the work that the literal coeffects map did back in section 2:

- **`:rf.cofx`** supplies coeffects on the dispatch — it plays the role the literal coeffects map played in section 2, except now you hand the values to the dispatch and the runtime threads them into the handler. Supplied values win; the runtime fills in only what's missing. Without it, the clock here would be the real wall clock the runtime stamps on the event, and your assertion would be chasing a moving target.
- **`:fx-overrides`** redirects an effect for this one dispatch. Here it swallows the HTTP request, because this test only cares about the stamp. Answering the request with a canned reply and asserting the whole chain is the next page's job: [Test a cascade](test-a-cascade.md).

> **Going deeper — naming the frame yourself.** A third option, `{:frame f}`, says *which* frame to dispatch into. Inside a `with-new-frame` body you can skip it — the macro pins `f` as the current frame for the body — but outside one (say you keep a frame in a `let` and tear it down yourself) you pass `:frame` explicitly. There is no ambient default frame; [a frame's identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) — the target is always either carried by scope or named on the opts map.

### When a required coeffect is missing: a loud failure, by design

There's a failure mode here that catches people, and it's a *feature*. If a handler **declares** a recordable coeffect that the framework can't safely mint for you, and the dispatch doesn't supply it, the dispatch [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/missing-required-cofx` rather than quietly minting a value your test didn't choose.

`:rf/time-ms` is the exception that always succeeds — the router stamps every event with an enqueue time when it's dispatched, so the clock is never *missing*; the runtime can always mint it. The strict failure fires for a fact the runtime *can't* safely mint on its own — typically one backed by a **generator**: a function you registered with `reg-cofx` that produces a fresh value each time (a new id, a random number). The framework won't run that generator behind your test's back, because the value it makes is one your test didn't choose. So if you declare such a fact and don't supply it, the dispatch refuses to proceed:

```clojure
(rf/reg-event :comment/create
  {:rf.cofx/requires [:app/new-id]}     ;; a reg-cofx with a value-returning generator
  (fn [{:keys [db app/new-id]} [_ body]]
    {:db (assoc-in db [:comments new-id] {:body body})}))

;; A :test-preset frame is strict-mint by default. Supply the id, or the
;; dispatch fails with :rf.error/missing-required-cofx — it will NOT silently
;; mint a different id than production would.
(rf/dispatch-sync [:comment/create "Great article!"]
                  {:frame f :rf.cofx {:app/new-id "id-123"}})
```

This is exactly the trap you want sprung. A silently-minted random value would make the test green against a state production will never produce — green-and-wrong, the worst colour a test can be. The fix is always one of two moves: supply the fact in `:rf.cofx` (the deterministic path, almost always what you want), or, when you genuinely want a fresh value per run, opt back into live minting with `{:rf.cofx/mint-policy :explicit-live}` as a dispatch opt.

> **The `:test` frame preset bundles the deterministic defaults.** Rather than spell out each test-friendly setting on every frame, ask for the bundle: `(rf/reg-frame :my-test {:preset :test})` — or `:preset :test` on the advanced `re-frame.frame/make-frame` — expands to three fixed entries. It redirects `:rf.http/managed` to a canned-success stub, so a test frame never reaches the network; it sets `:rf.cofx/mint-policy :strict`, the strict-mint behaviour above; and it carries `:drain-depth 100`, the framework default, surfaced so tooling can read "this is a test frame" off the frame's metadata. Reach for the preset when you want those defaults without naming them one by one.

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

For the assertion itself, `test-support` ships two `clojure.test`-aware helpers that read the *current* (or a named) frame's app-db, so you don't have to thread `app-db-value` through `get-in` by hand: `(ts/assert-path-equals path expected)` checks one path, and `(ts/assert-db-equals expected-db)` checks the whole map. Both take an optional `{:frame …}` opt and report a `:pass` / `:fail` through `clojure.test`, so they slot straight into a `deftest`:

```clojure
(deftest page-committed
  (rf/with-new-frame [_ (rf/make-frame {})]
    (rf/dispatch-sync [:articles/init])
    (rf/dispatch-sync [:articles/page-changed 3])
    (ts/assert-path-equals [:articles :page] 3)))
```

> **For JavaScript developers.** `assert-path-equals` mirrors the `:rf.assert/path-equals` event you'd write inside a Story play function — the fn-side and the event-side share a name root on purpose, so you don't need a translation table when you move between unit tests and Stories. If you've used Testing Library's `expect(screen…).toHaveTextContent(…)`, think of these as the app-db equivalent: a focused assertion against the one thing this test is about.

## 4. The trap: frames don't isolate registrations

There's a footgun here worth slowing down for. `with-new-frame` gives each test its own app-db, but it does **not** give each test its own registrar. `reg-event` and its siblings register into a process-global [registrar](../glossary.md#registrar) — one table shared across the whole test run. (This is the rule in action: a frame isolates **state, not registrations** — see [Frames](../concepts/frames.md).)

> **Gotcha — same id, last load wins.** If two test namespaces register different handlers under the same id, the later load silently wins. That's how you get the classic flake-hunt horror: every test passes alone, the suite fails together, and the failure jumps around as test order changes. It's maddening to chase, so guard against it before it bites.

If your tests — or any helpers they load — register anything themselves, bracket each test with a registrar snapshot/restore so the table is put back the way it was:

```clojure
(use-fixtures :each (fn [test-fn] (ts/with-fresh-registrar test-fn)))
```

`ts/with-fresh-registrar` rolls the registrar back on the way out, while keeping the ns-load registrations it captured at the start.

### Picking the right reset fixture

`with-fresh-registrar` resets exactly the registrar — and nothing else. `re-frame.test-support` offers a small ladder of reset fixtures, so you can match the cleanup to what your suite actually touches:

| Reach for | When |
|---|---|
| `ts/snapshot-registrar` + `ts/restore-registrar!` | You're hand-rolling a fixture and want the raw snapshot/restore primitives — capture the registrar map, restore it later. |
| `ts/with-fresh-registrar` | An ad-hoc `deftest` or REPL block whose only shared state is the registrar — no frames, no flows, no schemas left to clean up. |
| `ts/make-reset-runtime-fixture` | The **default for any real suite**. It snapshots/restores the registrar *and* resets the rest of per-process state — frames, flows, schemas, machine timers, routing counters, in-flight HTTP, resource caches, epoch history, trace listeners. It's a *factory*: call it to get the fixture fn. |

```clojure
;; The standard shape for a suite that exercises more than the registrar:
(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

`make-reset-runtime-fixture` is the right default because its resets are no-ops when an artefact is absent — a plain JVM event-handler suite that never pulls in flows or schemas doesn't pay for resetting them.

> **Gotcha — the two fixtures have different call shapes.** `with-fresh-registrar` *takes a thunk and runs it* (`(ts/with-fresh-registrar test-fn)`), while `make-reset-runtime-fixture` *returns a fixture fn* you hand to `use-fixtures` (`(ts/make-reset-runtime-fixture {})`). Mixing the two shapes up is the usual stumble — if `use-fixtures` complains, check which side of this line you're on.

> **For JavaScript developers.** The process-global registrar is the same shared-module-state hazard you get when one test file imports and mutates a singleton another file also imports. Jest hands you `jest.resetModules()` / `--isolateModules` to wall it off; here the wall is an explicit `use-fixtures` snapshot/restore. The difference is that re-frame2 makes the shared table — and its reset — a named, visible thing, rather than an invisible module-cache side effect.
