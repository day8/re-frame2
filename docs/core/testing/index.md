# Testing

Most of a re-frame2 app is pure functions, so most of its tests are function calls. A handler is a function you call with maps, a subscription is a function of a db value, and a view returns hiccup you walk as data. A whole pipeline run replays deterministically once its inputs are pinned. None of these needs a browser, a DOM or a mocking library, and they run on the JVM in milliseconds.

The pages test the todo app: `:todo/add` and `:todo/toggle` as handlers, `:todo/visible` as a subscription, a todo list as a view.

| Test… | How | Page |
|---|---|---|
| an **event handler** | pull it from the registrar, call it with a literal [world](../glossary.md#world) map, check the returned map | [Event handlers](event-handlers.md) |
| a **subscription** | `compute-sub` against a db value | [Subscriptions](subscriptions.md) |
| a **view** | call it, walk the returned hiccup with `re-frame.test-helpers` | [Views](views.md) |
| a **whole pipeline run** | `dispatch-sync` into a fresh frame with supplied facts and canned replies | [Pipeline runs](pipeline-runs.md) |
| an **error path** | register a listener, make the failure happen, assert on the error record's `:operation` and `:tags` | [Errors](../errors.md#test-the-structure-not-the-string) |

One habit runs through every page: setup goes into the frame's construction, and the test body dispatches only the action under test. `make-frame` takes `:initial-events`, the same ordered event list a production [frame](../frames.md#seeding-initial-state) boots with, so the state a test needs is declared where the frame is made. A `dispatch-sync` in a test body is the event being tested.

## Set up the test runner

The tests run on the JVM under any `clojure.test` runner. Add a `:test` alias to `deps.edn`, and run the suite with `clojure -M:test`:

```clojure
;; deps.edn
{:aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
         :main-opts   ["-m" "cognitect.test-runner"]}}}
```

The JVM loads `.clj` and `.cljc` files, never `.cljs`. Write the namespaces that hold your registrations as `.cljc`, with browser-only code inside `#?(:cljs …)` branches, so the tests can require them. re-frame2's core is `.cljc`, so it loads on the JVM too.

Each test namespace installs the reset fixture:

```clojure
;; test/my_app/todos_test.clj
(ns my-app.todos-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]  ;; the headless JVM adapter
            [re-frame.test-support :as ts]
            [my-app.todos]))   ;; loading the ns runs its reg-* calls

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))
```

`make-reset-runtime-fixture` returns the fixture function you hand to `use-fixtures`. Around every test it:

- snapshots the [registrar](../glossary.md#registrar) and restores it afterwards, keeping what was registered before the `use-fixtures` form ran, so a registration one test makes does not leak into the next;
- resets the rest of the per-process runtime: frames, flows, machine timers, in-flight HTTP, resource caches (when the test requires `re-frame.resources.test-support`), epoch history and trace listeners. Resets for artefacts you haven't loaded do nothing;
- installs the adapter you pass and creates the `:rf/default` frame. Every frame runs on an [adapter](../glossary.md#adapter), and the reset removes whatever was installed, so without `:adapter` the next `make-frame` throws `:rf.error/no-adapter-installed`, or `:rf.error/adapter-disposed` when an adapter was installed earlier in the run.

Register in the app namespaces the test requires, or inside a test body. A top-level `reg-*` in the test file below `use-fixtures` is outside the fixture's snapshot, so frames the test makes can't see it; [Test an event handler](event-handlers.md#4-the-trap-frames-dont-isolate-registrations) shows why the registrar needs resetting at all. `:init-fn`, a function the fixture runs after installing the adapter, seeds state for tests that share the `:rf/default` frame, as [Test a view](views.md) does. `:ambient-frame nil` leaves `:rf/default` out of the test body's scope, for suites whose tests each make their own frame.

## What about `reg-fx` and `reg-cofx`?

A `reg-fx` body touches the host and a `reg-cofx` supplier reads it, so they can't be tested as pure functions. Test what surrounds them instead:

- **Supply coeffects as data.** `{:rf.cofx {:rf/time-ms …}}` on the dispatch pins any fact a handler declared, and no supplier runs. [Pipeline runs](pipeline-runs.md) covers it.
- **Redirect effects as data.** `:fx-overrides` captures or stubs an effect for one dispatch (or a whole frame), so you assert on the args your handler built without performing anything. A few framework effects can't be overridden; [Pipeline runs](pipeline-runs.md#troubleshooting) lists them.
- **Call an effect handler directly.** An fx handler is a two-argument function `(fn [ctx args] …)`, so you can call it with a stub context when its body has logic worth testing. Keep those bodies thin.

## Other capabilities

Each capability has its own testing page built on the same techniques: [Machines](../../machines/inspecting-machines.md), [Routing](../../routing/testing.md), [Resources](../../resources/testing.md) and [SSR](../../ssr/testing.md).
