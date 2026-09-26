# Testing

Most of a re-frame2 app is pure functions, so most of its tests are function calls. A handler is a function you call with maps, a subscription is a function of a db value, and a view returns hiccup you walk as data. A whole pipeline run replays deterministically once its inputs are pinned. None of these needs a browser, a DOM or a mocking library, and they run on the JVM in milliseconds.

The pages test the todo app: `:todo/add` and `:todo/toggle` as handlers, `:todo/visible` as a subscription, a todo list as a view.

| Test… | How | Page |
|---|---|---|
| an **event handler** | pull it from the registrar, call it with a literal coeffects map, check the returned map | [Event handlers](event-handlers.md) |
| a **subscription** | `compute-sub` against a db value | [Subscriptions](subscriptions.md) |
| a **view** | call it, walk the returned hiccup with `re-frame.test-helpers` | [Views](views.md) |
| a **whole pipeline run** | `dispatch-sync` into a fresh frame with supplied facts and canned replies | [Pipeline runs](pipeline-runs.md) |

One habit runs through every page: setup goes into the frame's construction, and the test body dispatches only the action under test. `make-frame` takes `:initial-events`, the same ordered event list a production [frame](../frames.md#seeding-initial-state) boots with, so the state a test needs is declared where the frame is made. A `dispatch-sync` in a test body is the event being tested.

## What about `reg-fx` and `reg-cofx`?

A `reg-fx` body touches the host and a `reg-cofx` supplier reads it, so they can't be tested as pure functions. Test what surrounds them instead:

- **Supply coeffects as data.** `{:rf.cofx {:rf/time-ms …}}` on the dispatch pins any fact a handler declared, and no supplier runs. [Pipeline runs](pipeline-runs.md) covers it.
- **Redirect effects as data.** `:fx-overrides` captures or stubs an effect for one dispatch (or a whole frame), so you assert on the args your handler built without performing anything. A few framework effects can't be overridden; [Pipeline runs](pipeline-runs.md#asserting-on-what-would-dispatch) lists them.
- **Call an effect handler directly.** An fx handler is a two-argument function `(fn [ctx args] …)`, so you can call it with a stub context when its body has logic worth testing. Keep those bodies thin.

## Other capabilities

Each capability has its own testing page built on the same techniques: [Machines](../../machines/inspecting-machines.md), [Routing](../../routing/testing.md), [Resources](../../resources/testing.md) and [SSR](../../ssr/testing.md). Setting up the test runner (the `deps.edn` `:test` alias, and `.cljc` files so your registrations load on the JVM) is covered in [the tutorial's Part 6: test it, ship it](../../resources/tutorial/06-test-and-ship.md).
