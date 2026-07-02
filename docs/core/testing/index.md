# Testing

re-frame2's payoff at test time is one structural fact: **the interesting parts of your app are pure functions, so you test them as functions** — no browser, no DOM, no mocking framework standing between you and the assertion. A handler is a function you call with maps; a subscription is a function you call with a db value; a view returns hiccup you walk as data; and a whole cascade replays deterministically because everything it consumed was recorded. These tests run on the JVM in milliseconds.

> **Test the pure middle as functions; control the impure edges at their seams.**

The pages here climb one rung at a time, and each stands alone — start wherever your test is:

| Test… | The move | Page |
|---|---|---|
| an **event handler** | pluck it from the registrar, call it with literal coeffects, check the returned map | [Event handlers](event-handlers.md) |
| a **subscription** | `compute-sub` against a db value — the whole `:<-` chain resolves for you | [Subscriptions](subscriptions.md) |
| a **view** | call it, walk the returned hiccup with `re-frame.test-helpers` | [Views](views.md) |
| a **whole cascade** | `dispatch-sync` into a fresh frame with supplied facts and canned replies | [Cascades](cascades.md) |

## What about `reg-fx` and `reg-cofx`?

They don't get pages here, and that's deliberate: they are the app's **designed impure edges** — a `reg-fx` body touches the host, a `reg-cofx` supplier reads it — so "unit-test it as a pure function" doesn't apply. What you test instead is everything *around* them, at the seams the framework gives you:

- **Coeffects are supplied as data** — `{:rf.cofx {:rf/time-ms …}}` on the dispatch pins any world fact a handler declared, no supplier involved. [Cascades](cascades.md) covers it.
- **Effects are redirected as data** — `:fx-overrides` captures or stubs any fx-id for one dispatch (or a whole frame), so you assert on the exact args map your handler built without performing anything. Also [Cascades](cascades.md).
- An edge with **real logic in its body** is still just a two-arg function — call it directly with a stub frame context when it earns a test — but keep those bodies thin on purpose: the thinner the edge, the more of its behaviour the seam tests above already cover.

## The neighbours

Each capability tab carries its own testing page, built on the same moves: [Machines](../../machines/inspecting-machines.md) (a transition is a pure function call), [Routing](../../routing/testing.md) (a URL codec you call, a guard flow you drive with zero DOM), [Resources](../../resources/testing.md) (canned replies in, cache projections out), and [SSR](../../ssr/testing.md) (your server tests are just JVM tests). Setting up the runner itself — the `deps.edn` `:test` alias, and the `.cljc` discipline that lets your registration namespaces load on the JVM — is walked in [the tutorial's Part 5: test it, ship it](../../resources/tutorial/05-test-and-ship.md).
