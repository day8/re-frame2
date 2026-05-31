# 13 - Testing

You like to sleep at night and you want to add unit tests? Good news: there's a seam running straight through your re-frame2 app, and on one side of it is pure code — handlers, subs, machine transitions, effect maps that are nothing but data — and that side is testable with no browser, no mocks, and no patience. This chapter is about that seam, where it runs, and how to write tests that live on the right side of it.

## The seam is the whole story

Let me tell you why testing a React app is miserable, because the misery is the thing re-frame2 is reacting *against*, and naming it is half the lesson.

In a normal React app, the logic and the rendering are married. The decision "should this button be disabled?" lives inside the component that draws the button. The fetch that loads the user lives in a `useEffect` inside the component that shows the user. So when you want to test that logic — does the button disable correctly, does the fetch fire with the right args — you can't get at the logic without the component, and you can't get the component without rendering it, and you can't render it without a DOM, and you don't *have* a DOM in a test, so now you're standing up JSDOM, which is a from-memory reimplementation of the browser written in JavaScript that is wrong in a hundred subtle ways, and you're mocking `fetch`, and you're wrapping everything in `act()` and chanting at it, and you're advancing fake timers by hand, and your "unit test" takes four hundred milliseconds and flakes one run in twenty. That's not testing. That's a hostage negotiation.

Here is the thing that married the logic to the view: the logic was *impure*, and it was *colocated with rendering*. You couldn't extract it because it had grown roots into the component tree. Pull on it and the whole tree comes up.

re-frame2 cut those roots back in [chapter 04](04-events-and-the-cascade.md). The logic isn't in the view. It's in event handlers, which are pure functions of `(db, event) → db`. The derivations aren't in the view either; they're subscriptions, which compute against an `app-db` *value*. The side effects aren't a tangle of `useEffect`s; they're an **effect map** — data, returned from the handler, describing what should happen, without doing it. And the impure part, the one place the messy world actually gets touched, is a single domino the runtime owns, which means in a test *you simply don't run that domino*.

So the seam is this: **everything that decides what your app does is a pure function from values to values, and the one place that touches the world is a domino you can decline to fire.** Testing a re-frame2 app means testing the pure side. And the pure side runs on the JVM, in milliseconds, deterministically, with no DOM and no clock and no network anywhere in sight.

That's the claim. The rest of the chapter is the mechanics of cashing it in.

## The artefact: one require

The test helpers ship in `re-frame.test-support`. It re-exports the foundation primitives you already know — `make-frame`, `dispatch-sync`, the lot — so a test namespace needs exactly one re-frame require beyond `clojure.test`:

```clojure
(ns my-app.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]))
```

One thing worth saying up front so you trust the thing: `test-support` is not a parallel universe of special test-only machinery. There's no "test mode" that makes the runtime behave differently and lies to you about production. Everything in there is thin sugar over the same `make-frame` / `destroy-frame!` / `reset-frame!` / `dispatch-sync` / `compute-sub` primitives your app runs on. The tests exercise the real runtime. That matters more than it sounds like it does — the single worst category of test is the one that passes against a mock and fails against reality, and re-frame2 structurally can't write that test because there's no mock to pass against.

## Isolation, or: why each test wants its own frame

The single hardest problem in testing stateful code is **isolation** — making sure test 1 doesn't quietly poison test 2. You've lived this: a suite that's green when you run one test and red when you run all of them, because test 1 left a flag set and test 2 assumed a clean slate. The bug isn't in the code; it's in the *bleed*.

re-frame v1 papered over this with a global `app-db` and helpers to reset it between tests. It worked, but a global is a global — it's the shared mutable state you spend the whole rest of the framework trying to avoid, sitting right in the middle of your test harness.

re-frame2 has a better answer, and you already met it: the **frame**. A frame is an isolated runtime context — its own `app-db`, its own subscription cache, its own everything. Give each test its own frame and isolation stops being a discipline you have to remember and becomes a property of the setup. If the frame mental model is hazy — what a frame *is*, why each test wants a fresh one — go read [chapter 18 — Frames](18-frames.md) and come back; the rest of this chapter assumes the vocabulary cold.

There are three shapes for getting a fresh frame, and you reach for them in roughly this order of frequency.

**The common case — `with-new-frame`.** It makes a frame, binds it to a name, sets it as the implicit current frame for the body (so `dispatch-sync` and `subscribe` inside resolve to it with no `{:frame ...}` ceremony), and tears it down on the way out — success or exception, doesn't matter, the frame is destroyed. One block, no try/finally:

```clojure
(deftest auth-flow
  (rf/with-new-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/login-pressed])
    (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))))
```

That's the shape you'll write ninety per cent of the time. Make a frame, drive events, assert against its db.

**When you need explicit teardown — the raw form.** This is what `with-new-frame` desugars into. Reach for it when the body is doing something the macro can't see — running a generator across many frames, threading the frame into a helper that brings its own cleanup:

```clojure
(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame! f)))))
```

Functionally identical to the first. Use it only when you genuinely need the seam visible.

**When a group of tests shares setup — a named fixture.** Register a named frame once, reset it between tests:

```clojure
(use-fixtures :each
  (fn [test-fn]
    (rf/reg-frame :test-fixture {:on-create [:auth/init-idle]})
    (try
      (test-fn)
      (finally
        (rf/reset-frame! :test-fixture)))))

(deftest one-thing
  (rf/dispatch-sync [:auth/login-pressed] {:frame :test-fixture})
  (is (= :validating (get-in (rf/get-frame-db :test-fixture) [:auth :state]))))
```

`reset-frame!` wipes `app-db` back to `{}` and re-fires `:on-create`, so every test starts from the same clean seed; the registration cost is paid once for the group.

### The isolation you'll forget about

Here's a trap, because it's the one that bit me and it'll bite you. The three patterns above isolate `app-db`. They do **not** isolate the **registrar** — the global registry of event handlers, fxs, subs, and machines. If test 1 registers `:my-feature/event` and test 2 registers a *different* version of the same id, the second registration wins, regardless of which test ran first. You get the classic horror: each test passes alone, the suite fails together, and the failure moves depending on test order. You will lose an afternoon to this exactly once.

The fix is a fixture that snapshots the registrar around the body and restores it after:

```clojure
(use-fixtures :each
  (fn [test-fn]
    (ts/with-fresh-registrar (test-fn))))
```

`with-fresh-registrar` handles the registry. Its bigger sibling, `make-reset-runtime-fixture`, extends the same idea to the rest of process-wide state — frames, flows, schemas, trace listeners — and that's the one to make your suite's default `:each` fixture. Use the narrow one when only the registrar can change; use the runtime one when tests also touch listeners or schemas. Either way: set it once at the top of the file and stop thinking about cross-test bleed.

## What you actually test

Now the meat. Five things make up a re-frame2 app's behaviour — handlers, subs, machines, the view-to-state seam, and whole cascades — and every one of them tests as a function from values to values.

### Event handlers are just functions

A `reg-event-db` handler is `(db, event) → db`, pure. So you pluck it out of the registry and call it like the function it is:

```clojure
(deftest counter-inc-test
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:count 5}
        after   (handler before [:counter/inc])]
    (is (= 6 (:count after)))))
```

No frame, no dispatch, no runtime. You read a state in, you assert on the state out. That's the whole test.

For a `reg-event-fx` handler — one that returns an effect map instead of a bare db — you test the **shape of the effects it asks for**. This is the part people don't believe at first, so look closely:

```clojure
(deftest login-handler-produces-http-fx
  (let [handler (:handler-fn (rf/handler-meta :event :user/login))
        result  (handler {:db {}} [:user/login {:email "a@b.c" :password "x"}])]
    (is (= true (get-in result [:db :auth/loading?])))
    (is (= :rf.http/managed (ffirst (:fx result))))))
```

The handler does not fire the HTTP request. The runtime would — but the runtime isn't here, and that's the point. The handler's job is to *describe* the request as data, and the test asserts that the description is right: the db got the loading flag, and the effect vector asks for a managed HTTP call. No fetch was mocked because no fetch was involved. The handler returned a map. You checked the map. This is what "effects are data" buys you, redeemed at the test bench: a side effect you can assert on without performing it.

### Subscriptions compute against a value

A sub is a cache over a derivation, but the derivation itself is a pure function of `app-db`, and `compute-sub` runs it without spinning up the reactive cache at all. Two ways to test one:

```clojure
;; 1. Against a literal app-db value — fast, but brittle to schema changes.
(deftest pending-todos-sub-pure
  (is (= 2 (count (rf/compute-sub [:pending-todos]
                                  {:items [{:id 1 :status :pending}
                                           {:id 2 :status :done}
                                           {:id 3 :status :pending}]})))))

;; 2. Against a frame's app-db, after dispatching real events — preferred.
(deftest pending-todos-sub-via-events
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todos/add {:id 1 :status :pending}])
    (rf/dispatch-sync [:todos/add {:id 2 :status :done}])
    (rf/dispatch-sync [:todos/add {:id 3 :status :pending}])
    (is (= 2 (count (rf/compute-sub [:pending-todos] (rf/get-frame-db f)))))))
```

Prefer the second form, and here's the reason it's worth the extra lines: it builds the state through the *same code paths your app uses*. If you later rename `:items` to `:todos` in `app-db`, the events change, the sub changes, and this test keeps passing *unmodified* because it never hard-codes the shape — it just asserts the behaviour. The literal-value form (number 1) hard-codes the shape, so it breaks the day the shape moves, which means it's testing your db layout as much as your logic. Use it for a quick check; reach for the dispatch-driven form for anything you want to live.

`compute-sub` is pure: same `(query-v, db)`, same answer, every time. Composed subs (the `:<-` kind from [chapter 05](05-subscriptions.md)) compute transitively — inputs first, then the layer on top — with no reactive machinery involved at all.

### State machines test at three depths

Machines ([chapter 12](12-machines.md)) are the densest logic in most apps — "what state are we even in, and what's a legal move from here?" — so they get the most testing attention, and re-frame2 gives you three depths to test at, each with a sharper failure signal than the last.

**Depth 1 — the pure transition.** No frame, no `app-db`, no router. Just the transition rules:

```clojure
(deftest login-flow-happy-path
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap} (rf/machine-transition login-flow s0
                                                  [:auth.login/submit {:email "a@b.c"
                                                                       :password "..."}])]
    (is (= :submitting (:state s1)))))

(deftest login-flow-lockout
  (let [snap {:state :submitting :data {:attempts 3}}
        {s ::result/snap} (rf/machine-transition login-flow snap
                                                 [:auth.login/failure {:message "wrong"}])]
    (is (= :locked-out (:state s)))))
```

`machine-transition` is a pure function: feed it a snapshot and an event, get the next snapshot back. This is where the *vast majority* of machine bugs get caught, because this is where the actual logic lives — the transition table.

**Depth 2 — the unregistered handler fn.** `make-machine-handler` turns the machine into a regular event-handler fn you can call directly with a synthetic cofx map. This tests the *boundary* — that the machine lifts its effects correctly into the handler protocol:

```clojure
(let [handler (rf/make-machine-handler login-flow)
      result  (handler {:db {:rf/runtime {:machines {:snapshots {:auth.login/flow {:state :idle :data {}}}}}}}
                       [:auth.login/flow [:auth.login/submit {...}]])]
  (is (= :submitting (get-in result [:db :rf/runtime :machines :snapshots :auth.login/flow :state]))))
```

**Depth 3 — registered in a test frame.** The full integration: register the machine, dispatch through the frame, assert against its `app-db`. This proves the machine wires into a real dispatch loop:

```clojure
(rf/with-new-frame [f (rf/make-frame {})]
  (rf/reg-machine :auth.login/flow login-flow)
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit {...}]])
  (is (= :submitting (get-in (rf/get-frame-db f)
                              [:rf/runtime :machines :snapshots :auth.login/flow :state]))))
```

The three depths exist so that *failure tells you where the bug is*. A red Depth-1 test means the transition logic is wrong. A green Depth-1 but red Depth-2 means the logic's fine and the effect-lifting boundary is broken. A green Depth-2 but red Depth-3 means the boundary's fine and the wiring into the frame is wrong. Most days you write Depth-1 tests and nothing else; the other two are there for when "it works in isolation but not in the app" needs disambiguating fast.

### Driving a whole cascade

For the integration-shaped test — several events in sequence, assert at the end — drive the whole thing through `dispatch-sync` and override the one impure domino:

```clojure
(deftest auth-happy-path
  (rf/with-new-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/email-changed "alice@example.com"])
    (rf/dispatch-sync [:auth/password-changed "hunter2"])
    (rf/dispatch-sync [:auth/login-pressed]
                      {:fx-overrides {:rf.http/managed
                                      (fn [_ _] {:status 200 :body {:user/id 42}})}})
    (is (= :authed (get-in (rf/get-frame-db f) [:auth :state])))))
```

Two things are quietly doing the heavy lifting.

`dispatch-sync` **drains synchronously to fixed point.** The whole cascade — including any follow-up `:dispatch` effects the handlers queue — settles completely before `dispatch-sync` returns. So the assertion on the very next line sees fully-committed state. There is no race to wrap in `act()`, no flush to await, no "wait for the next tick." It's done when the function returns.

`:fx-overrides` **redirects an effect for the length of one dispatch.** And here's the subtle, important part: it is a *redirect*, not a mock. The exact same dispatch shape that the real `:rf.http/managed` would produce lands in your override fn. You're not faking the HTTP layer; you're intercepting the one effect that would have touched the network and answering it inline. No JSDOM, no fake fetch, no service-worker theatre. The seam, used exactly as designed.

## The other seam: state-correct but view-broken

Everything so far drove events and read `app-db`. That's the bulk of what you test, and it's where the bulk of the bugs are. But two species of bug live in the gap *between* a correct `app-db` and the screen, and no amount of state-assertion will catch them:

1. **State-correct, view-broken.** The handler updated the db, the sub computes the right value — and the view reads the wrong path, or formats it wrong, or forgets a branch. Every state assertion stays green. The user sees garbage.
2. **Wrong-frame dispatch.** The view wires `:on-click` to dispatch into the wrong frame, or no frame. State assertions against the host frame stay green; in production the click fires into the void and nothing happens.

Both are caught the same way: **call the view-fn directly and walk the hiccup it returns.** A view-fn is a function. Hiccup is a vector. No React, no JSDOM, no `act()` — and crucially, this runs on the JVM at the same low cost as everything else.

A quick aside on form, since this is the first place it bites in this chapter: the guide's runnable cells write views as plain `defn` functions with explicit `rf/subscribe` / `rf/dispatch`, and so do these tests — you call `(counter-view {...})` and get hiccup. The `reg-view` macro you'll see in some code is sugar over exactly that; underneath, a view is a function returning hiccup, which is precisely why you can call it in a test.

The walker ships in `re-frame.test-helpers`:

```clojure
(:require [re-frame.test-helpers :as h])
```

It's a small, blunt surface: `find-by-testid` / `find-all-by-testid` / `find-by-testid-prefix` to anchor on stable elements, `text-content` to read what the user would actually see, `extract-handler` / `invoke-handler` to read or fire `:on-click` and friends, and `testid` as authoring-side sugar for tagging elements. All of it operates on plain hiccup data and recursively expands nested function components — so a parent view that mounts a child function-component gets fully walked from one call site.

**Assert state and view together.** Catches the "state correct, view broken" class:

```clojure
(deftest counter-view-shows-current-count
  (rf/with-new-frame [f (rf/make-frame {:on-create [:counter/init]})]
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    ;; state assertion — the handler updated the db
    (is (= 2 (:n (rf/get-frame-db f))))
    ;; view assertion — the view actually shows that value
    (let [tree  (counter-view {:n (:n (rf/get-frame-db f))})
          label (h/find-by-testid tree "counter-label")]
      (is (= "Count: 2" (h/text-content label))))))
```

The `:data-testid` is a stable handle that survives layout churn. Search the codebase for `"counter-label"` and you find the view *and* every test that pins it — the testid is the contract between them.

**Drive a click, assert the dispatch.** Catches the "wrong-frame dispatch" class — if the handler fires into the wrong frame, the host-frame assertion fails loudly:

```clojure
(deftest counter-button-fires-inc
  (rf/with-new-frame [f (rf/make-frame {:on-create [:counter/init]})]
    (let [tree (counter-view {:n 0})
          btn  (h/find-by-testid tree "counter-inc")]
      (h/invoke-handler btn :on-click nil)            ;; fires :on-click
      (is (= 1 (:n (rf/get-frame-db f)))))))         ;; state moved
```

`invoke-handler` finds the element, pulls the handler off its attrs map, and calls it. If the view's `:on-click` is `#(rf/dispatch [:counter/inc])`, that dispatch threads through whatever frame `with-new-frame` bound, the cascade drains synchronously, and the next line sees the result. Wrong frame? The host-frame assertion fails — the bug is loud, reproducible, and browserless.

On the authoring side, `h/testid` standardises the attrs fragment so your views carry stable handles without clutter:

```clojure
;; views.cljs
(defn counter-view [{:keys [n]}]
  [:div (h/testid "counter-root")
   [:span (h/testid "counter-label") (str "Count: " n)]
   [:button (h/testid "counter-inc"
                      {:on-click #(rf/dispatch [:counter/inc])})
    "+"]])
```

That's just `{:data-testid "counter-inc" :on-click ...}` written tidily. Use whichever reads better; both work with `find-by-testid`.

There's a second flavour of view test, `render-to-string`, which emits actual HTML rather than walking hiccup — and it lives in [chapter 20 — Server-side](20-server-side.md), because it's the same renderer SSR uses. The split is simple: reach for `render-to-string` when the assertion is about the **markup** ("is the `<button>` disabled?", "does the `<h1>` carry the right class?"); reach for the hiccup-walk when the assertion is about **structure** ("is the testid there?") or **handlers** ("what does the button fire?"), or when you want to drive interaction by invoking `:on-click` directly.

One boundary to respect: these patterns are for a **single application frame** — host frame is the only frame, views are your views, tests assert against the same frame events fire into. That's the canonical shape, and the one you'll use for your own app. A different shape exists for *tool* code — something that runs in one frame and observes another, like Xray or a custom dashboard — which needs two frames in one process with a trace path between them. That's exercised by the framework's own tool suites; it is not the right shape for testing your app's views, and reaching for it because it sounds powerful is how you end up with a test harder to understand than the code it tests.

## Sugar that earns its place

`re-frame.test-support` ships a few helpers that read better than the hand-rolled equivalent. Two are worth knowing by name.

**`dispatch-sequence`** fires a list of events via `dispatch-sync` in order and returns the final `app-db`:

```clojure
(deftest counter-walk
  (rf/dispatch-sync [:counter/init])
  (let [final (ts/dispatch-sequence [[:counter/inc] [:counter/inc] [:counter/dec]])]
    (is (= 1 (:n final)))))
```

It takes an `:after-each` callback if you want to snapshot the intermediate states:

```clojure
(let [seen (atom [])]
  (ts/dispatch-sequence [[:counter/inc] [:counter/inc]]
                        {:after-each (fn [db ev] (swap! seen conj [ev db]))}))
```

It's a `doseq` of `dispatch-sync` calls; it just reads as one intention instead of three lines of boilerplate.

**`assert-path-equals` / `assert-db-equals`** are `clojure.test`-aware assertions in two shapes. The path form is the common case; the full-db form is for "the whole thing should equal this" in small fixtures:

```clojure
(rf/dispatch-sync [:auth/login-pressed])

(ts/assert-path-equals [:auth :state] :validating)     ;; the common case
(ts/assert-db-equals   {:auth {:state :validating}})   ;; full-db form

;; against a non-default frame
(ts/assert-path-equals [:auth :state] :validating {:frame :test/auth-flow})
```

A mismatch fires a `clojure.test`-style failure through `do-report`, so it slots into your test report like any `is`. Story uses the same assertion vocabulary when a variant becomes executable behaviour, but the guide does not teach Story here; the [Story docs](../story/index.md) own that workflow. The point to keep is simple: unit tests and stories should not need separate languages for the same expectation.

## JVM versus CLJS: where things run

This is the part that reorders your instincts, so here it is plainly: **almost everything runs on the JVM.** You do not need a browser, JSDOM, a CLJS test runner, or headless Chrome to test handlers, fxs, subs, machine transitions, or whole cascades.

Runs on the JVM:

- `make-frame` / `destroy-frame!` / `reset-frame!` / `with-new-frame`
- `dispatch-sync` and the entire dispatch pipeline — router, drain, interceptors
- every `reg-event-*` handler invocation
- override application — `:fx-overrides`, `:interceptor-overrides`, `:interceptors`
- cofx injection
- `machine-transition` (a pure function)
- `compute-sub` (sub computation against an `app-db` value)
- public registrar queries — `registrations`, `frame-meta`, `sub-topology`, and friends
- **hiccup → HTML** via the SSR renderer — also a pure function, so snapshot tests and SSR conformance run headlessly too

Needs CLJS:

- React *actually mounting* — the mount lifecycle, `:on-click` firing into a real DOM node, scroll events
- reactive subscription **tracking** — auto-subscribe-on-deref and Reagent's dispose lifecycle. Note the distinction: sub **computation** is JVM-runnable via `compute-sub`; only the reactive *tracking* needs the browser.

The split is clean, and the proportion is the punchline. A typical re-frame2 codebase ends up with *hundreds* of JVM tests for handlers, fxs, subs, and machines, and a *handful* of CLJS-or-Playwright tests for genuine click-through-the-DOM behaviour. That's the exact inverse of what most React codebases drift into, where almost every test drags in a render and the suite takes minutes. Here the heavy work is on the cheap side of the seam.

### When even a view test isn't worth writing

`render-to-string` makes view-content testing cheap, and cheap is dangerous, because cheap tempts you into writing tests that cost you forever and catch nothing. So let me be honest about where I *don't* write them.

If a view is form-1 and renders pure data — a label, a list, a status pill — the hiccup *is* the test. Reading the function tells you exactly what it produces. A test asserting "the label says `'Submitting…'` when state is `:submitting`" is just the function body restated in another language; it'll drift out of date with the function and it won't catch a single bug that actually ships.

The bugs that actually ship in views are about *interaction*: the click handler that misfires on the second mount, the focus that lands on the wrong field, the scroll position that resets after a re-render, the keyboard shortcut that collides with another component, the modal that traps Tab the wrong way. `render-to-string` gives you the markup. It gives you none of those. The answer for those is Playwright — or your CLJS-mounted equivalent — running against the real app: slower, fewer, focused, and worth their slowness.

So the rule I actually use:

- **Logic the view contains** — a derivation, a formatter, a gnarly conditional — lift it into a fn or a sub and test *that*. The sub test runs on the JVM; the view becomes a thin shaper over the result.
- **Content the view emits** — straightforward hiccup from inputs — let the function speak for itself. Don't double-write it as an assertion that goes stale.
- **Behaviour the view embodies** — clicks, focus, keyboard, scroll, mount — Playwright. Few of these. They earn it.

The point isn't that view tests are bad. It's that every test is a ball and chain you drag forward for the life of the project, and the cheap headless hiccup test is the one most likely to accumulate by the dozen while never once catching a real bug. Write the ones that pay.

### Running the suites

Each per-feature artefact — core, machines, routing, flows, http, ssr, schemas, epoch — carries its own JVM test alias:

```bash
cd implementation/core      && clojure -M:test    # JVM tests for core
cd implementation/machines  && clojure -M:test    # JVM tests for machines
cd implementation/routing   && clojure -M:test
# ... and so on
```

CI runs them in parallel. If you changed core, run core's suite plus any consumer artefact that depends on it, to catch downstream breakage. The CLJS-side integration tests — the ones that genuinely need a browser — run separately: `npm run test:cljs` from the repo root drives a shadow-cljs build into headless Chrome.

## Stubbing without mocking

There's a family of recipes for the cases where a test needs to bend the runtime's behaviour — silence a logger, freeze the clock, answer an HTTP call — and they share a philosophy worth naming: **you don't mock, you redirect.** The same id resolves to a different handler for the length of the test, and the same dispatch shape the production code produces lands in your stand-in. The seam, again.

**Stub an fx for a frame or a call.** Per-frame, so every event in that frame uses the stub:

```clojure
(rf/reg-frame :test/auth-flow
  {:on-create   [:auth/init-idle]
   :fx-overrides {:rf.http/managed (fn [_m _args] {:status 200 :body {:user/id 42}})}})
```

Or one-shot, scoped to a single dispatch:

```clojure
(rf/dispatch-sync [:auth/load-user]
                  {:frame :test/auth-flow
                   :fx-overrides {:rf.http/managed (fn [_ _] {:status 401})}})
```

**Stub managed HTTP with canned replies.** For `:rf.http/managed` specifically, the framework ships stubs that synthesise the proper reply envelope so you don't hand-roll one. They live in `re-frame.http-test-support` — opt in once per test ns alongside the production fx surface:

```clojure
(ns my-app.tests
  (:require [re-frame.http-managed]        ;; production fx surface
            [re-frame.http-test-support])) ;; canned-stub fxs + stub macros
```

The per-request shape redirects to a canned success or failure:

```clojure
(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})

(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
```

And for a suite that hits many endpoints, `with-managed-request-stubs` routes by method + URL:

```clojure
(rf/with-managed-request-stubs
  {[:get  "/api/counter"]        {:reply {:ok {:count 5}}}
   [:get  "/api/does-not-exist"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}
   [:post "/api/counter"]        {:reply {:ok {:count 6}}}}
  (rf/dispatch-sync [:counter/load])
  (rf/dispatch-sync [:counter/load-bad])
  (rf/dispatch-sync [:counter/save]))
```

Wrap, dispatch, assert against the resulting `app-db`. No browser, no network. One rule: **production and SSR code must not require `re-frame.http-test-support`** — under that constraint the canned-stub ids are unregistered on every host (absent from the classpath on the JVM, dead-code-eliminated on advanced CLJS) and the re-exports raise `:rf.error/http-artefact-missing` rather than silently shipping test scaffolding to users. The tests cost production exactly nothing. (The full managed-HTTP story is [chapter 10](10-http.md).)

**Record events without checking every transition.** A tiny interceptor that just notes what flowed past:

```clojure
(def recorded (atom []))

(def event-recorder
  (rf/->interceptor
    :id :test/event-recorder
    :before (fn [ctx]
              (swap! recorded conj (-> ctx :coeffects :event))
              ctx)))

(rf/reg-frame :test/recorder-frame
  {:interceptors [event-recorder]})
```

After a test run, `@recorded` holds the events in order — handy for asserting control flow without pinning every intermediate state. (`->interceptor`, the sandwich shape, and per-frame `:interceptors` are all [chapter 09](09-interceptors.md); read it if that slot looks unfamiliar.)

**Silence a logging interceptor.** Same override shape, but `nil` removes:

```clojure
(rf/reg-frame :test/silent
  {:on-create             [:test/init]
   :interceptor-overrides {:my-app/logger nil}})       ;; nil removes the interceptor
```

**Freeze the clock, fix the ids.** A handler that reaches into the world through `inject-cofx` becomes deterministic by re-registering the cofx against the same id. No special test-mode flag — `inject-cofx` finds the override because it's in the same registry:

```clojure
(deftest todo-add-stamps-created-at
  (ts/with-fresh-registrar
    (rf/reg-cofx :now
      (fn [ctx] (assoc-in ctx [:coeffects :now] #inst "2026-01-01T12:00:00.000Z")))
    (rf/with-new-frame [f (rf/make-frame {})]
      (rf/dispatch-sync [:todo/add "buy milk"])
      (is (= #inst "2026-01-01T12:00:00.000Z"
             (-> (rf/get-frame-db f) :todos first val :created-at))))))
```

`with-fresh-registrar` scopes the stub to this test, so production `:now` is intact for the next one. The full cofx story — `reg-cofx`, `inject-cofx`, the common cofxes — is [chapter 07](07-effects-and-coeffects.md); this is just where the testing idiom lands.

## The change you can actually feel

Stand back from the recipes and look at what's structurally different, because that's the part that sticks.

Tests stop being a tax. You write more of them, because each is three lines and runs in milliseconds: the setup is `with-new-frame`, the act is `dispatch-sync`, the assert is a `get-in` against the resulting `app-db`. There's no JSDOM to configure, no fetch to mock, no `act()` to wrap, no fake timer to advance. The whole *dynamic* story of your app — every event, every transition, every machine path, every sub computation — becomes assertable, line by line, in the language the app was written in.

The suite stays fast. A thousand JVM tests run in a couple of seconds, so you leave the watcher on and failures land while the change is still in your head. The CLJS-and-Playwright tier — the parts that genuinely need a real DOM — stays small and focused, because almost nothing actually needs it.

And that inversion isn't luck or discipline. It's structural. re-frame2's primitives are functions, functions test cheaply, and the cost of arranging that was paid once, in the design, [back at the cascade](04-events-and-the-cascade.md). Every test you write afterwards just collects the interest. That's the seam, and it's why you get to sleep at night.
