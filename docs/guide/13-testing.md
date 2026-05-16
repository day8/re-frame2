# 13 — Testing

re-frame2's pattern makes the full dynamic story of your app testable on the **JVM, in milliseconds per case** — no JSDOM, no fetch mocks, no timer wrestling, no `act()`. Pure event handlers, pure machine transitions, sub bodies that compute against an `app-db` value, an effect map that's just data — every load-bearing piece is a function from values to values, evaluable without a browser, a network, or a clock. Tests are short, fast, and stable, which means you write more of them and trust them more.

This chapter is about how you write that down as test code: the `re-frame.test-support` artefact, the per-test fixture patterns, the JVM-vs-CLJS boundary, and the conformance harness that grades implementations against the same fixtures used by the framework's own tests.

You'll know how to:

- Spin up an isolated frame for a test and tear it down cleanly.
- Test event handlers, fxs, and subs without a browser.
- Test state machines as pure transitions, no frame required.
- Use `dispatch-sequence` and `assert-state` to keep test bodies short.
- Decide what runs on the JVM and what needs CLJS.

## The artefact

Testing helpers ship in `re-frame.test-support`, alongside re-exports of the foundation primitives (`make-frame`, `dispatch-sync`, `with-frame`, etc.) so a test file needs one require:

```clojure
(ns my-app.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]))
```

The artefact is dev-only and cleanly separated from the runtime — the testing surface is built entirely from foundation primitives. Nothing in `re-frame.test-support` is a special-case mechanism; it's all sugar over `make-frame`/`destroy-frame!`/`reset-frame!`/`dispatch-sync`/`compute-sub`.

## Fixtures: getting a fresh frame for each test

The hardest part of testing imperative code is **isolation** — making sure test 1 doesn't leak state into test 2. re-frame v1 papered over this with global `app-db`-resetting helpers; re-frame2 makes it explicit through frames. If the per-test-frame mental model is new — what a frame is, why each test wants its own — read [chapter 06a — Frames](06a-frames.md) first; this chapter assumes the vocabulary.

Three patterns, ranked roughly by frequency:

### Pattern 1 — `with-frame` for tighter blocks

```clojure
(deftest auth-flow
  (rf/with-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/login-pressed])
    (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))))
```

`with-frame` binds `f` to the freshly-created frame for the body's duration, sets it as the implicit `*current-frame*` (so `dispatch-sync` and `subscribe` inside the body resolve to it without a `{:frame ...}` opt), and on exit (success or exception) destroys the frame. One block; no try/finally.

### Pattern 2 — anonymous fixture per test

The shape `with-frame` desugars into, useful when you want explicit teardown logic:

```clojure
(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame! f)))))
```

Functionally equivalent to Pattern 1; reach for it when the body is doing something the macro can't see — running a generator over many frames, threading the frame into a helper that takes its own teardown, etc.

### Pattern 3 — named fixture across many tests

For a test group sharing setup, register a named test frame once and reset between tests:

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

`reset-frame!` clears `app-db` to `{}` and re-fires `:on-create`. State is fresh between tests; the registration cost is paid once.

### Registrar isolation: `with-fresh-registrar`

The patterns above isolate `app-db`. They don't isolate the **registrar** — the global registry of events, fxs, subs, machines. If one test registers `:my-feature/event` and another test registers a different version of the same id, the second wins, regardless of test order. That's a recipe for tests that pass alone and fail together.

`re-frame.test-support/with-fresh-registrar` snapshots the registrar around the body and restores on exit:

```clojure
(use-fixtures :each
  (fn [test-fn]
    (ts/with-fresh-registrar (test-fn))))
```

The companion fn `reset-runtime-fixture` is the same shape extended to per-process state — frames, flows, schemas, trace listeners — and is the standard `:each` fixture for re-frame2 test suites. Use `with-fresh-registrar` when only the registrar might change; use `reset-runtime-fixture` when tests also touch trace listeners, schemas, or other process-wide state.

## What you actually test

### Event handlers

A `reg-event-db` handler is a pure function of `(db, event) → db`. Test it as a pure function:

```clojure
(deftest counter-inc-test
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:count 5}
        after   (handler before [:counter/inc])]
    (is (= 6 (:count after)))))
```

For `reg-event-fx` handlers, you test the **effect map shape**:

```clojure
(deftest login-handler-produces-http-fx
  (let [handler (:handler-fn (rf/handler-meta :event :user/login))
        result  (handler {:db {}} [:user/login {:email "a@b.c" :password "x"}])]
    (is (= true (get-in result [:db :auth/loading?])))
    (is (= :rf.http/managed (ffirst (:fx result))))))
```

The handler doesn't fire the HTTP request. The runtime would. The test doesn't need a runtime — it asserts that the handler **describes** the right request.

### End-to-end through a frame

For tests that exercise multiple events in sequence and want to assert at the end, drive the whole flow through `dispatch-sync`:

```clojure
(deftest auth-happy-path
  (rf/with-frame [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (rf/dispatch-sync [:auth/email-changed "alice@example.com"])
    (rf/dispatch-sync [:auth/password-changed "hunter2"])
    (rf/dispatch-sync [:auth/login-pressed]
                      {:fx-overrides {:rf.http/managed
                                      (fn [_ _] {:status 200 :body {:user/id 42}})}})
    (is (= :authed (get-in (rf/get-frame-db f) [:auth :state])))))
```

Two pieces are doing work here:

- **`dispatch-sync` drains synchronously to fixed point.** The whole event cascade — including any follow-up `:dispatch` fxs — settles before `dispatch-sync` returns. Assertions immediately after see committed state, no race.
- **`:fx-overrides`** redirects a registered fx for one dispatch (or one frame, if specified on `reg-frame`). The override is an id-redirect, not a mock — the same dispatch shape the real `:rf.http/managed` produces lands in the test handler. No JSDOM, no fake fetch.

### Subscriptions

Two ways to test a sub:

```clojure
;; 1. compute-sub against a literal app-db value
(deftest pending-todos-sub-pure
  (is (= 2 (count (rf/compute-sub [:pending-todos]
                                  {:items [{:id 1 :status :pending}
                                           {:id 2 :status :done}
                                           {:id 3 :status :pending}]})))))

;; 2. compute-sub against a frame's app-db, after dispatching events
(deftest pending-todos-sub-via-events
  (rf/with-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todos/add {:id 1 :status :pending}])
    (rf/dispatch-sync [:todos/add {:id 2 :status :done}])
    (rf/dispatch-sync [:todos/add {:id 3 :status :pending}])
    (is (= 2 (count (rf/compute-sub [:pending-todos] (rf/get-frame-db f)))))))
```

The dispatch-driven form is preferred — it tests the sub against state produced by the same code paths the application uses, and it survives `app-db` schema changes. If `:items` becomes `:todos`, the events update, the sub updates, the test keeps working unmodified.

`compute-sub` is **pure** — same `(query-v, db)` always returns the same value. Composed subs (`:<-`) are computed transitively: inputs first, then outputs, all without spinning up the reactive cache.

### State machines

Three levels of machine testing, in order of unit-cost:

**Level 1 — pure `machine-transition`.** No frame, no `app-db`, no router. Test the transition rules in isolation:

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

**Level 2 — unregistered handler fn.** `(rf/create-machine-handler login-flow)` returns a regular event-handler fn. Call it directly with a synthetic cofx map:

```clojure
(let [handler (rf/create-machine-handler login-flow)
      result  (handler {:db {:rf/machines {:auth.login/flow {:state :idle :data {}}}}}
                       [:auth.login/flow [:auth.login/submit {...}]])]
  (is (= :submitting (get-in result [:db :rf/machines :auth.login/flow :state]))))
```

**Level 3 — registered in test frame.** Register the machine in a frame, dispatch events, assert against the frame's `app-db`:

```clojure
(rf/with-frame [f (rf/make-frame {})]
  (rf/reg-machine :auth.login/flow login-flow)
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit {...}]])
  (is (= :submitting (get-in (rf/get-frame-db f)
                              [:rf/machines :auth.login/flow :state]))))
```

Use Level 1 for transition logic. Use Level 2 when you want to verify the handler boundary's effect-lifting. Use Level 3 for the integration shape — that the machine wires correctly into a frame's dispatch loop. Most machine bugs are caught at Level 1; the levels exist so each layer's failure mode is unambiguous.

## Test-flavoured helpers

`re-frame.test-support` ships a few small helpers that read better than hand-rolling the same shape:

### `dispatch-sequence`

Fires each event via `dispatch-sync` in order. Returns the final `app-db`:

```clojure
(deftest counter-walk
  (rf/dispatch-sync [:counter/init])
  (let [final (ts/dispatch-sequence [[:counter/inc] [:counter/inc] [:counter/dec]])]
    (is (= 1 (:n final)))))
```

Capturing intermediate states between dispatches:

```clojure
(let [seen (atom [])]
  (ts/dispatch-sequence [[:counter/inc] [:counter/inc]]
                        {:after-each (fn [db ev] (swap! seen conj [ev db]))}))
```

Equivalent to a `doseq` of `dispatch-sync` calls; reads better in tests.

### `assert-state`

A `clojure.test`-aware assertion that doubles as the path-or-full-db check:

```clojure
(rf/dispatch-sync [:auth/login-pressed])
(ts/assert-state [:auth :state] :validating)
;; or full-db form:
(ts/assert-state {:auth {:state :validating}})
;; with a non-default frame:
(ts/assert-state [:auth :state] :validating {:frame :test/auth-flow})
```

Mismatch fires a `clojure.test/is`-style failure via `do-report`. The path form is the common case; the full-db form is for "the whole thing should equal this" assertions in small fixtures.

## JVM vs CLJS — what runs where

A defining property of re-frame2's testing surface: **almost everything runs on the JVM**. You don't need a browser, JSDOM, a CLJS test runner, or a headless Chrome to test event handlers, fxs, subs, machine transitions, or whole event cascades.

What's JVM-runnable:

- ✓ `make-frame` / `destroy-frame!` / `reset-frame!` / `with-frame`
- ✓ `dispatch-sync` and the entire dispatch pipeline (router, drain, interceptors)
- ✓ All `reg-event-*` handler invocation
- ✓ Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`)
- ✓ Cofx injection
- ✓ `machine-transition` (pure function)
- ✓ `compute-sub` (sub computation against an `app-db` value)
- ✓ Public registrar queries (`registrations`, `frame-meta`, `sub-topology`, etc.)
- ✓ **Hiccup → HTML emission** via the SSR renderer — pure function, JVM-runnable. Snapshot tests, SSR conformance tests, and visual-regression diffs all run headlessly.

What's CLJS-only:

- ✗ React-actually-mounting (the mount lifecycle, `:on-click` firing into the real DOM, scroll events).
- ✗ Reactive subscription **tracking** (auto-subscribe-on-deref, Reagent's dispose lifecycle). Subscription **computation** is JVM-runnable via `compute-sub`.

The split is clean: every business-logic test runs on the JVM. View **content** tests — does the rendered hiccup contain the expected text? does the structure match the schema? — also run on the JVM via `render-to-string`. Only tests that exercise actual React mounting or scroll-position-style interactive DOM behaviour need CLJS.

In practice, a typical re-frame2 codebase ends up with hundreds of JVM tests for handlers/fxs/subs/machines, and a small handful of CLJS-or-Playwright tests for actual click-through-DOM behaviour. The proportion is opposite to what most React codebases get used to.

### When even a view test isn't worth writing

`render-to-string` makes view-content testing cheap. That doesn't mean every view should have one. Here's the honest answer about where I still don't write them.

If the view is form-1 and renders pure data — a label, a list, a status pill — the hiccup is the test. Reading the function tells you what it produces. A test that asserts "the label contains `'Submitting...'` when state is `:submitting`" is restating the function body in another language. It will go out of date with the function; it won't catch the bugs that actually ship.

The bugs that actually ship in views are about *interaction*: the click handler that doesn't fire on the second mount, the focus that lands on the wrong field, the scroll position that resets after a re-render, the keyboard-shortcut that conflicts with another component, the modal that traps Tab in the wrong direction. `render-to-string` gives you the markup; it doesn't give you any of those. The answer for those is Playwright (or your CLJS-mounted equivalent), running against the real app — slower, fewer, focused.

So the rule I use:

- **Logic the view contains** — a derivation, a formatter, a non-trivial conditional — lift it out into a fn or a sub, and test *that*. The sub test runs on the JVM; the view becomes a thin shaping function over the result.
- **Content the view emits** — straightforward hiccup from inputs — let the function body speak for itself. Don't double-write it as an assertion.
- **Behaviour the view embodies** — clicks, focus, keyboard, scroll, mount lifecycle — Playwright. Few of these. They earn their slowness.

The point isn't that view tests are bad. It's that every test is a ball-and-chain you drag forward forever, and the cheap headless hiccup test is the one most likely to ratchet up over years without catching a single real bug. Write the ones that pay.

### Per-artefact `clojure -M:test`

Each per-feature artefact in re-frame2's Strategy B layout — core, machines, routing, flows, http, ssr, schemas, epoch — has its own test alias:

```bash
cd implementation/core      && clojure -M:test    # JVM tests for core
cd implementation/machines  && clojure -M:test    # JVM tests for machines
cd implementation/routing   && clojure -M:test
# ... and so on
```

The alias runs `clojure.test` against the artefact's `test/` directory. The CI matrix runs each in parallel; if you've changed core, run core's suite plus any consumer artefact's suite to catch downstream regressions.

The CLJS-side tests (browser-runner integration tests) run separately — `npm run test:cljs` from the repo root drives a shadow-cljs build into a headless browser.

## Stubbing fxs, recording events, replacing interceptors

Three more shapes show up frequently enough to be worth naming.

### Stubbing an fx for a whole frame

Per-frame `:fx-overrides`:

```clojure
(rf/reg-frame :test/auth-flow
  {:on-create   [:auth/init-idle]
   :fx-overrides {:rf.http/managed (fn [_m _args] {:status 200 :body {:user/id 42}})}})
;; every event handled in :test/auth-flow uses the stub :rf.http/managed
```

Or a one-shot per-call override:

```clojure
(rf/dispatch-sync [:auth/load-user]
                  {:frame :test/auth-flow
                   :fx-overrides {:rf.http/managed (fn [_ _] {:status 401})}})
```

The override is a **redirect**, not a mock. The same dispatch shape the real fx produces lands in the test handler.

### Stubbing managed HTTP

The generic `:fx-overrides` shape above redirects any fx to a test fn. For `:rf.http/managed` specifically, the framework ships two canonical stub fxs plus a higher-level helper, so tests get the canonical reply envelope without hand-rolling one.

The two canned-stub fxs (`:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`) register from a sibling test-support namespace — opt in once per test ns by `:require`-ing it alongside `re-frame.http-managed`:

```clojure
(ns my-app.tests
  (:require [re-frame.http-managed]        ;; production fx surface
            [re-frame.http-test-support])) ;; canned-stub fx registrations
```

Per-request stub via `:fx-overrides` redirect — the stub fx synthesises a success or failure reply with the same shape the live fx would produce:

```clojure
(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})

(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
```

The args map is the same shape the call site already uses; the canned-success stub takes a `:value` key (the payload to put under `:rf/reply :value`); the canned-failure stub takes `:kind` and `:tags` for the failure shape.

For test suites that exercise many requests against many endpoints, `with-managed-request-stubs` routes each `:rf.http/managed` invocation by `:request :method` + `:request :url` — and, unlike the canned-stub fxs, does NOT need the `re-frame.http-test-support` require (it registers its own per-call stub fx at user invocation time):

```clojure
(rf/with-managed-request-stubs
  {[:get  "/api/counter"]        {:reply {:ok {:count 5}}}
   [:get  "/api/does-not-exist"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}
   [:post "/api/counter"]        {:reply {:ok {:count 6}}}}
  (rf/dispatch-sync [:counter/load])
  (rf/dispatch-sync [:counter/load-bad])
  (rf/dispatch-sync [:counter/save]))
```

Wrap a test, run dispatches, assert against the resulting `app-db`. No browser, no network. Production / SSR app code MUST NOT `:require` `re-frame.http-test-support` — under that constraint the canned-stub fx ids are unregistered on every host (classpath absence on JVM, DCE on CLJS `:advanced + goog.DEBUG=false`); tests pay no production cost.

### Recording dispatched events without firing them

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

After running a test sequence, `@recorded` carries the events that fired, in order. Useful for verifying control flow without checking every state transition.

The `->interceptor` primitive used above, the sandwich shape, and the per-frame `:interceptors` slot are all covered in [chapter 07 — Interceptors](07-interceptors.md). If `:interceptors` on a frame is unfamiliar, read that first.

### Disabling a logging interceptor

```clojure
(rf/reg-frame :test/silent
  {:on-create             [:test/init]
   :interceptor-overrides {:my-app/logger nil}})       ;; nil removes the interceptor
```

Same shape as `:fx-overrides`; same per-frame / per-call duality. The `nil` value removes the interceptor entirely; a non-nil value substitutes it.

### Stubbing a cofx — deterministic `:now`, predictable ids

A handler that depends on the outside world via `inject-cofx` becomes deterministic by re-registering the cofx against the same id. The stub lives in the same registry the production cofx does; `inject-cofx` finds the override with no special test-mode flag:

```clojure
(deftest todo-add-stamps-created-at
  (ts/with-fresh-registrar
    (rf/reg-cofx :now
      (fn [ctx] (assoc-in ctx [:coeffects :now] #inst "2026-01-01T12:00:00.000Z")))
    (rf/with-frame [f (rf/make-frame {})]
      (rf/dispatch-sync [:todo/add "buy milk"])
      (is (= #inst "2026-01-01T12:00:00.000Z"
             (-> (rf/get-frame-db f) :todos first val :created-at))))))
```

`with-fresh-registrar` scopes the stub to the test body — the production `:now` is intact for the next test. The full cofx surface (`reg-cofx`, `inject-cofx`, common cofxes, the registration shape) is covered in [chapter 05 — Coeffects](05-coeffects.md); this entry locates the testing idiom within the broader stubbing story.

---

## Reference and advanced topics

The topics that follow sit outside the day-to-day flow of "write a test for my app" and have homes elsewhere. Conformance fixtures are an implementor concern — they grade new-host ports against the framework's spec. Bundle-isolation is a framework-internal CI gate that polices core's requires. Property-based testing is one further extension of the cheap-fixture story; the foundation primitives admit it, but it isn't the canonical shape.

- **Conformance fixtures** — the host-agnostic EDN corpus that grades a port's behaviour against the spec. You don't write these for your own app; they exist to pin contracts and catch drift across implementations.
- **Bundle-isolation CI gate** — the framework's PR-time check that core's release build carries zero per-feature namespace markers. The gate runs on the framework, not on your app; it's the reason core's requires stay spartan. The check script lives at [`implementation/scripts/check-bundle-isolation.cjs`](../../implementation/scripts/check-bundle-isolation.cjs).
- **Property-based testing** — `test.check` composes with `make-frame` + `dispatch-sync` naturally: generators produce event sequences, the property asserts an invariant, thousands of trials run in milliseconds. It isn't the canonical re-frame2 testing shape, but the cheap fixture admits it.

## What you should expect

Here's the change you can actually feel.

Tests stop being a tax. You write more of them, because each one is three lines and runs in milliseconds. You write them as you go, not three sprints later under duress. The setup is `with-frame`; the act is `dispatch-sync`; the assert is a `get-in` against the resulting `app-db`. There's no JSDOM to configure, no fetch to mock, no `act()` to wrap, no timer to advance. The whole dynamic story of your app — every event, every state transition, every machine path, every sub computation — becomes assertable, line by line, in the language the app was written in.

The suite stays fast. A thousand JVM tests run in a couple of seconds. You leave the watcher on. Failures show up while the change is still in your head. The CLJS-and-Playwright tier — the parts that genuinely need a real DOM — stays small and stays focused, because almost nothing actually needs it.

The split is the inverse of what most React-side test suites accumulate over time, and the reason is structural: re-frame2's primitives are functions, and functions test cheaply. The cost of getting it right was paid once, in the design. Every test you write afterwards collects the interest.

## Next

- [14 — Errors and how to handle them](14-errors.md) — the trace-listener test pattern in this chapter generalises into a full surface for asserting error contracts; that chapter walks the `:rf.error/*` taxonomy end-to-end.
- [Causa](../causa/index.md) — the trace bus, epochs, time-travel, source-coords, and the devtool that paints them.
