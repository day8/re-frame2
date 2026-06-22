# How-to guides

You've got a working app and one specific task in front of you. Each page here is a recipe for exactly that task — and nothing else. It gives you the goal, then the steps, then the complete code, then the working result, in that order, so you never have to hold a half-built picture in your head. There's no theory in the way, because by the time you're reaching for a recipe you don't need it sold to you again — you need it done.

These recipes assume you've already built something — the [quick start](../quickstart.md) is enough to get you there — and that you can read the loop at a glance: [the model](../concepts/index.md) covers that in a single page, and it's worth the few minutes, because every recipe leans on the same six-step shape. Each one ends with links *down*: to the concept page for *why* it's built this way, and to the spec for *every* option when you need them.

> **Find the task, follow the recipe, link down for the contract.**

> **Coming from a cookbook mindset?** Think of these the way you'd treat the "Recipes" section of a framework's docs — the [React](https://react.dev) "you might not need an effect", the Rails guide for "how do I do file uploads". They're task-shaped, copy-pasteable, and deliberately opinionated: one good way, shown fully, rather than a tour of the option space. The option space lives in the spec links at the bottom of each page.

The recipes are grouped by where they sit in the life of an app — **build it**, then **test it**, then, when something's off, **debug it**, and finally **ship it** to production. You don't read them in order; you drop into the one group that matches what's in front of you right now. Each recipe is self-contained, so jumping straight to "Report errors in production" without having read "Build a form" costs you nothing.

## Build it

This is where most of your time goes: turning a feature request into the loop's six dominoes. Each recipe takes one common feature — a form, a paginated feed, a write that has to refresh the right reads — and shows the complete slice: the events, the subs, the effects, and the view, with nothing left as an exercise.

| I want to… | Recipe |
|---|---|
| add login and keep the user logged in | [Add authentication](add-auth.md) |
| build a form — local edits, validation, clean submit | [Build a form](build-a-form.md) |
| load a feed one page at a time | [Paginate a feed](paginate-a-feed.md) |
| refetch the right server data after a write | [Invalidate after a mutation](invalidate-after-a-mutation.md) |
| catch bad state and malformed events early | [Validate with schemas](validate-with-schemas.md) |

## Test it

re-frame2's payoff at test time is that the interesting parts of your app are pure functions, so you can test them as functions — no browser, no DOM, no mocking framework standing between you and the assertion. The two recipes here climb one rung at a time: first the smallest unit (a single handler), then the whole cascade (state, effects, and the events one dispatch fans out into).

| I want to… | Recipe |
|---|---|
| unit-test a handler — the function that takes the current state and an event and returns the next state — as the pure function it is | [Test an event handler](test-an-event-handler.md) |
| test a whole dispatch — state, effects, follow-ups | [Test a full cascade](test-a-cascade.md) |

## Debug it

When the app does something you didn't ask for, you don't reach for `println` — you read the one wire every event already crosses. These recipes are how: replaying *why* a change happened, and tracking down a view that recomputes more than it should.

| I want to… | Recipe |
|---|---|
| see exactly why the app just did that | [Debug with Xray](debug-with-xray.md) |
| find the view that re-renders too much, and stop it | [Find and fix a slow view](fix-a-slow-view.md) |

> **Want to poke at the running app instead of reading a trace after the fact?** Several of these tasks have a *live* counterpart: you can attach to a running frame, read its app-db, dispatch events, and hot-swap a handler from your editor through the [Tool-Pair contract](../../../spec/Tool-Pair.md). That's pairing against a live runtime rather than following a recipe — the recipes here cover the after-the-fact read; the live path is its own surface.

## Ship it

| I want to… | Recipe |
|---|---|
| keep tokens, passwords, and large blobs out of traces | [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) |
| hear about production errors with their full context | [Report errors in production](report-errors-in-production.md) |
| set up dev and production builds — tools in, tools out | [Configure dev and production builds](configure-dev-and-prod.md) |
| swap the React layer — the loop is identical on all of them | [Use UIx, Helix, or reagent-slim](use-uix-helix-or-slim.md) |

## Can't find your task?

A recipe answers "how do I do X." Some questions sit a step *before* that — they're design decisions, not tasks, and a recipe is the wrong shape for them. Those live elsewhere:

- Asking "where should this value live — db (your app's single state map), sub (a derived, cached read of that state), flow, resource, or machine?" That's a design decision, which is exactly why it gets its own page rather than a recipe: [Where should this value live?](../where-state-lives.md)
- Your task spans several features, and you'd rather watch one app grow through them in order? The [RealWorld tutorial](../tutorial/index.md) builds auth, feeds, forms, and invalidation end to end — same pieces as these recipes, assembled into one running app.
- For everything else, [the reference map](../reference.md) indexes the complete surface.

> **Why split recipes from design decisions?** Because they fail differently. A recipe you can follow wrong — you'll know, because the result won't match the page. A design decision you can follow *right* and still regret, six months later, when the value you parked in the wrong place is wired into forty subscriptions. Recipes are reversible; placement isn't. So the two get different pages, different shapes, and different amounts of your attention.

---

**You can now:**

- go from "I want to X" to the right recipe without reading a concept chapter first
- say what every recipe promises: goal, steps, working result, then links down for the why and the contract
- tell a recipe (a how-do-I task) apart from a design decision (a where-should-this-live question), and reach for the right kind of page
