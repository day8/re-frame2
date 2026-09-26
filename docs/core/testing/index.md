# Testing

Most of a re-frame2 app is pure functions, so most of its tests are function calls: a handler is a function you call with maps, a subscription is a function you call with a db value, and a view returns hiccup you walk as data. A whole pipeline run replays deterministically once its inputs are pinned. None of these needs a browser, a DOM or a mocking library, and they run on the JVM in milliseconds.

Pick the page that matches what you are testing:

| Test… | The move | Page |
|---|---|---|
| an **event handler** | pluck it from the registrar, call it with literal coeffects, check the returned map | [Event handlers](event-handlers.md) |
| a **subscription** | `compute-sub` against a db value — the whole declared-`:inputs` chain resolves for you | [Subscriptions](subscriptions.md) |
| a **view** | call it, walk the returned hiccup with `re-frame.test-helpers` | [Views](views.md) |
| a **whole pipeline run** | `dispatch-sync` into a fresh frame with supplied facts and canned replies | [Pipeline runs](pipeline-runs.md) |

One habit runs through every page: setup goes into the frame's construction, and the test body dispatches only the action under test. `make-frame` takes `:initial-events`, the same ordered event list a production [frame](../frames.md#seeding-initial-state) boots with, so the state a test needs before its action is declared where the frame is made. A setup step that needs pinned facts takes the map form, `{:event [...] :opts {:rf.cofx {...}}}`, and a step that fails makes `make-frame` throw `:rf.error/initial-events-step-failed` instead of handing back a half-seeded frame. When you see a `dispatch-sync` in a test body on these pages, it's the event being tested.

## What about `reg-fx` and `reg-cofx`?

They don't get pages here. A `reg-fx` body touches the host and a `reg-cofx` supplier reads it, so they can't be unit-tested as pure functions. Test what surrounds them instead:

- **Supply coeffects as data.** `{:rf.cofx {:rf/time-ms …}}` on the dispatch pins any fact a handler declared, and no supplier runs. [Pipeline runs](pipeline-runs.md) covers it.
- **Redirect effects as data.** `:fx-overrides` captures or stubs an app fx-id for one dispatch (or a whole frame), so you assert on the args map your handler built without performing anything. A handful of state-installing reserved fxs can't be overridden; [Pipeline runs](pipeline-runs.md#asserting-on-what-would-dispatch) lists them.
- **Call an edge with real logic directly.** An fx handler is a two-arg function `(fn [ctx args] …)`, so you can call it with a stub context when its body earns a test. Keep those bodies thin: the less an effect does beyond reading its args, the more of it the tests above already cover.

## Other capabilities

Each capability has its own testing page built on the same techniques: [Machines](../../machines/inspecting-machines.md) (a transition is a pure function call), [Routing](../../routing/testing.md) (a URL codec you call, a guard flow you drive with zero DOM), [Resources](../../resources/testing.md) (canned replies in, cache projections out), and [SSR](../../ssr/testing.md) (server tests are JVM tests). Setting up the runner itself — the `deps.edn` `:test` alias, and the `.cljc` discipline that lets your registration namespaces load on the JVM — is walked in [the tutorial's Part 5: test it, ship it](../../resources/tutorial/05-test-and-ship.md).
