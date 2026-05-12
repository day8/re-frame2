# 13 — Testing

re-frame2's pattern is shaped to be tested. Pure event handlers, pure machine transitions, sub bodies that compute against an `app-db` value, an effect map that's just data — every load-bearing piece is a function from values to values. There's nothing in the runtime that requires a browser, a network, or a clock to evaluate.

This chapter is about how you write that down as test code: the `re-frame.test-support` artefact, the per-test fixture patterns, the JVM-vs-CLJS boundary, and the conformance harness that grades implementations against the same fixtures used by the framework's own tests.

You'll know how to:

- Spin up an isolated frame for a test and tear it down cleanly.
- Test event handlers, fxs, and subs without a browser.
- Test state machines as pure transitions, no frame required.
- Use `dispatch-sequence` and `assert-state` to keep test bodies short.
- Decide what runs on the JVM and what needs CLJS.
- Use the host-agnostic conformance fixtures.
- Understand what the bundle-isolation CI gate enforces.

## The artefact

Testing helpers ship in `re-frame.test-support`, alongside re-exports of the foundation primitives (`make-frame`, `dispatch-sync`, `with-frame`, etc.) so a test file needs one require:

```clojure
(ns my-app.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]))
```

The artefact is dev-only and cleanly separated from the runtime — the testing surface is built entirely from foundation primitives in [Spec 002](../../spec/002-Frames.md). Nothing in `re-frame.test-support` is a special-case mechanism; it's all sugar over `make-frame`/`destroy-frame`/`reset-frame`/`dispatch-sync`/`compute-sub`.

## Fixtures: getting a fresh frame for each test

The hardest part of testing imperative code is **isolation** — making sure test 1 doesn't leak state into test 2. re-frame v1 papered over this with global `app-db`-resetting helpers; re-frame2 makes it explicit through frames.

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
        (rf/destroy-frame f)))))
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
        (rf/reset-frame :test-fixture)))))

(deftest one-thing
  (rf/dispatch-sync [:auth/login-pressed] {:frame :test-fixture})
  (is (= :validating (get-in (rf/get-frame-db :test-fixture) [:auth :state]))))
```

`reset-frame` (per [Spec 002](../../spec/002-Frames.md)) clears `app-db` to `{}` and re-fires `:on-create`. State is fresh between tests; the registration cost is paid once.

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
        [s1 _fx] (rf/machine-transition login-flow s0
                                        [:auth.login/submit {:email "a@b.c"
                                                             :password "..."}])]
    (is (= :submitting (:state s1)))))

(deftest login-flow-lockout
  (let [snap {:state :submitting :data {:attempts 3}}
        [s _fx] (rf/machine-transition login-flow snap
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

### `run-test-sync` (compatibility shim)

For mechanical migration of v1 tests written against `day8/re-frame-test`'s `run-test-sync`:

```clojure
(deftest legacy-flow
  (ts/run-test-sync
    (rf/reg-event-db :counter/inc (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:counter/inc])
    (is (= 1 (:n (rf/get-frame-db :rf/default))))))
```

The macro snapshots the registrar before the body and restores on exit (success or exception). v2's `dispatch-sync` already drains synchronously, so the macro is a body wrapper — its job is registrar isolation, not synchronicity. New tests don't need it; existing v1 test suites get a name-rename and keep working.

## JVM vs CLJS — what runs where

A defining property of re-frame2's testing surface: **almost everything runs on the JVM**. You don't need a browser, JSDOM, a CLJS test runner, or a headless Chrome to test event handlers, fxs, subs, machine transitions, or whole event cascades.

What's JVM-runnable:

- ✓ `make-frame` / `destroy-frame` / `reset-frame` / `with-frame`
- ✓ `dispatch-sync` and the entire dispatch pipeline (router, drain, interceptors)
- ✓ All `reg-event-*` handler invocation
- ✓ Override application (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`)
- ✓ Cofx injection
- ✓ `machine-transition` (pure function)
- ✓ `compute-sub` (sub computation against an `app-db` value)
- ✓ Public registrar queries (`handlers`, `frame-meta`, `sub-topology`, etc.)
- ✓ **Hiccup → HTML emission** via `render-to-string` (per [Spec 011](../../spec/011-SSR.md)) — pure function, JVM-runnable. Snapshot tests, SSR conformance tests, and visual-regression diffs all run headlessly.

What's CLJS-only:

- ✗ React-actually-mounting (the mount lifecycle, `:on-click` firing into the real DOM, scroll events).
- ✗ Reactive subscription **tracking** (auto-subscribe-on-deref, Reagent's dispose lifecycle). Subscription **computation** is JVM-runnable via `compute-sub`.

The split is clean: every business-logic test runs on the JVM. View **content** tests — does the rendered hiccup contain the expected text? does the structure match the schema? — also run on the JVM via `render-to-string`. Only tests that exercise actual React mounting or scroll-position-style interactive DOM behaviour need CLJS.

In practice, a typical re-frame2 codebase ends up with hundreds of JVM tests for handlers/fxs/subs/machines, and a small handful of CLJS-or-Playwright tests for actual click-through-DOM behaviour. The proportion is opposite to what most React codebases get used to.

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

Per-request stub via `:fx-overrides` redirect — the stub fx synthesises a success or failure reply with the same shape the live fx would produce:

```clojure
(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})

(rf/dispatch-sync [:counter/load]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
```

The args map is the same shape the call site already uses; the canned-success stub takes a `:value` key (the payload to put under `:rf/reply :value`); the canned-failure stub takes `:kind` and `:tags` for the failure shape.

For test suites that exercise many requests against many endpoints, `with-managed-request-stubs` routes each `:rf.http/managed` invocation by `:request :method` + `:request :url`:

```clojure
(rf/with-managed-request-stubs
  {[:get  "/api/counter"]        {:reply {:ok {:count 5}}}
   [:get  "/api/does-not-exist"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}
   [:post "/api/counter"]        {:reply {:ok {:count 6}}}}
  (rf/dispatch-sync [:counter/load])
  (rf/dispatch-sync [:counter/load-bad])
  (rf/dispatch-sync [:counter/save]))
```

Wrap a test, run dispatches, assert against the resulting `app-db`. No browser, no network. Both canned-stub fxs gate on `interop/debug-enabled?` and elide in production builds — tests pay no production cost. The full contract is in [Spec 014 §Testing](../../spec/014-HTTPRequests.md#testing).

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

## Conformance fixtures

The framework ships a **conformance corpus** — host-agnostic EDN fixtures under `spec/conformance/` — that grades implementations against a single source of truth. Each fixture describes a scenario as data: registrations, an event sequence, expected `app-db` shape, expected trace events. A conformance harness reads the fixture, replays it against an implementation, and asserts the results.

You don't typically write conformance fixtures. They exist to:

- **Pin a contract.** Ranked-route precedence, machine spawn/destroy lifecycle, schema validation failure modes — every behaviour the spec calls "the answer" has a fixture asserting that answer.
- **Grade ports.** A new-language port (TypeScript, Python, Kotlin) doesn't need to re-derive what's correct — it runs the conformance corpus against its own implementation and gets a pass/fail per behaviour.
- **Catch drift.** A spec change that says one thing but an implementation that does another fails conformance — the test surface is structural, not implementation-specific.

When you'd reach for a conformance fixture in your own codebase: when you have a behaviour you want **multiple implementations** to agree on. Most app-side tests are not conformance tests; they verify your app's logic, not the framework's contract. The fixtures are framework infrastructure.

If you want to know what your implementation supports, look at the `:capabilities` declaration in the implementation's manifest and cross-reference against `spec/conformance/README.md`. Each fixture lists the capabilities it exercises; the harness skips fixtures whose capabilities the implementation hasn't claimed.

## Bundle-isolation: the gate that keeps core clean

A subtle but load-bearing testing concern: re-frame2's Strategy B layout puts every optional feature in its own artefact. **Core does not require any per-feature artefact.** That's the whole point of feature opt-in — apps that don't use machines don't bundle machine code; apps that don't use routing don't bundle routing code.

The risk is silent regression: a future change to core that accidentally `(:require [re-frame.machines])` for a default-fallback path would silently re-include the machines artefact in every consumer's bundle. The change wouldn't fail any existing test; it'd just balloon production bundle size by the per-feature payload.

The framework's CI runs a **bundle-isolation gate** on every PR:

1. `shadow-cljs release` the **counter** example (the canonical no-feature app).
2. Grep the produced `main.js` for each per-feature artefact's namespace markers.
3. Assert **0 hits** per artefact (or a stable allow-list of consumer-side preset-map keywords).

If a future PR re-imports a split-out namespace into core, the counter bundle suddenly carries `re-frame.machines`-shaped strings, and the gate fails. The contract is enforced at PR time, not discovered three releases later.

You don't write bundle-isolation tests for your own app. The gate runs on the framework. The mention here is to explain *why* core is so spartan in its requires — the absence is the contract.

## Property-based testing

`test.check` fits cleanly into re-frame2 — `make-frame` is cheap, generators produce event sequences, properties check invariants:

```clojure
(require '[clojure.test.check :as tc]
         '[clojure.test.check.generators :as gen]
         '[clojure.test.check.properties :as prop])

(def counter-never-negative
  (prop/for-all [evs (gen/vector (gen/elements [[:counter/inc] [:counter/dec]]) 0 50)]
    (rf/with-frame [f (rf/make-frame {:on-create [:counter/initialise]})]
      (doseq [ev evs] (rf/dispatch-sync ev))
      (let [{:keys [count]} (rf/get-frame-db f)]
        (or (>= count 0)
            ;; design choice: maybe negative IS allowed; the property is whatever
            ;; we want to assert. The cheap fixture lets us assert it across
            ;; thousands of generated sequences.
            true)))))

(tc/quick-check 1000 counter-never-negative)
```

The cheap fixture is the load-bearing piece — `make-frame` plus `dispatch-sync` lets a property exercise hundreds of full event cascades per second. Generators produce the event sequences; the property asserts whatever invariant matters; a thousand-trial run takes milliseconds. This shape is documented in [Spec 008](../../spec/008-Testing.md) as one of the natural extensions of the foundation primitives.

For machines specifically, the `machine-transition` purity opens a path to `@xstate/test`-style coverage: enumerate paths through the transition graph, generate event sequences that visit every transition, run them as test cases. That's library territory rather than framework, and out of scope for this guide; the spec's [§Future](../../spec/008-Testing.md#open-questions) notes it as an open area.

## What you should expect

A re-frame2 codebase that's been built with the patterns in this guide tends to acquire a particular testing shape:

- **Many small JVM unit tests.** Hundreds of them. Each runs in milliseconds. Together they cover almost all business logic.
- **A handful of CLJS browser tests.** Click-through interaction, focus management, the parts that genuinely need a real DOM. Slow, fewer, focused.
- **Conformance grading**, if you're maintaining a port. Read fixtures, replay, assert.
- **Property-based runs**, if your domain has invariants worth asserting structurally.

The split is the inverse of what most React-side test suites accumulate over time. The reason is structural: re-frame2's primitives are functions, and functions test cheaply.

## Next

- [14 — Errors and how to handle them](14-errors.md) — the trace-listener test pattern in this chapter generalises into a full surface for asserting error contracts; that chapter walks the `:rf.error/*` taxonomy end-to-end.
- [15 — Tooling](15-devtools-and-pair-tools.md) — the trace bus, epochs, time-travel, source-coords, and the tools that attach to them.
- [Spec 008 — Testing](../../spec/008-Testing.md) — the full normative surface, JVM/CLJS boundary, and adapter notes.
