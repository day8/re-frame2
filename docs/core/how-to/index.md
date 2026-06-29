# How-to guides

You've got a working app and one task in front of you. Each page here is a recipe for exactly that task — and nothing else. Goal first, then the steps, then the complete code, then the working result, in that order, so you never have to hold a half-built picture in your head. No theory in the way: by the time you're reaching for a recipe you don't need it sold to you again — you need it done.

> **Find the task, follow the recipe, link down for the contract.**

These recipes assume you've already built something — the [quick start](../quickstart.md) gets you there — and that the loop is familiar. Every dispatch sets off one [**event cascade**](../glossary.md#event-cascade): the fixed, ordered run — [handler](../glossary.md#event-handler) → [effect map](../glossary.md#effect-map) → effects → derivations → view → DOM — that the *six dominoes* name as a first-contact mnemonic. [The model](../concepts/index.md) walks all six in a single page, and it's worth the few minutes, because every recipe below is one stage of that cascade filled in for a real feature. Each recipe ends with links *down*: to the concept page for *why* it's built this way, and to the spec for *every* option when you need them.

The recipes are grouped by where they sit in the life of an app — **build it**, then **test it**, then, when something's off, **debug it**, and finally **ship it**. You don't read them in order; drop into the group that matches what's in front of you. Each recipe is self-contained, so jumping straight to "Report errors in production" without having read "Build a form" costs you nothing.

## Build it

This is where most of your time goes: turning a feature request into stages of the loop. Each recipe takes one common feature — a form, a paginated feed, a write that has to refresh the right reads — and shows the complete slice: the [events](../glossary.md#event), the [subscriptions](../glossary.md#subscription), the [effects](../glossary.md#effect), and the [view](../glossary.md#view), with nothing left as an exercise.

| I want to… | Recipe |
|---|---|
| boot and mount the app, with hot reload | [Boot and mount an app](boot-and-mount-an-app.md) |
| add login and keep the user logged in | [Add authentication](add-auth.md) |
| build a form — local edits, validation, clean submit | [Build a form](build-a-form.md) |
| load a feed one page at a time | [Paginate a feed](../../resources/how-to/paginate-a-feed.md) |
| refetch the right server data after a write | [Invalidate after a mutation](../../resources/how-to/invalidate-after-a-mutation.md) |
| catch bad state and malformed events early | [Validate with schemas](validate-with-schemas.md) |

## Test it

re-frame2's payoff at test time is that the interesting parts of your app are pure functions, so you test them *as* functions — no browser, no DOM, no mocking framework standing between you and the assertion. An [event handler](../glossary.md#event-handler) is a pure `(coeffects, event) → effect map`; you hand it a map and check the map it returns. The two recipes climb one rung at a time: first the smallest unit (a single handler), then the *whole cascade* — the new state, the effects, and any follow-up events that one dispatch sets off — all checked together.

| I want to… | Recipe |
|---|---|
| unit-test an event handler as the pure function it is | [Test an event handler](test-an-event-handler.md) |
| test a whole dispatch — state, effects, follow-ups | [Test a full cascade](test-a-cascade.md) |

## Debug it

When the app does something you didn't ask for, you don't reach for `println` — you read the [trace](../glossary.md#trace-stream), the timeline every event already wrote down, one [epoch](../glossary.md#epoch) per dispatch ([why it works](../concepts/observability.md)). These recipes are how you read it: replaying the timeline, and tracking down a view that recomputes more than it should.

| I want to… | Recipe |
|---|---|
| see exactly why the app just did that | [Debug with Xray](debug-with-xray.md) |
| find the view that re-renders too much, and stop it | [Find and fix a slow view](fix-a-slow-view.md) |

> **Want to poke at the running app instead of reading a trace after the fact?** Several of these tasks have a *live* counterpart: attach to a running [frame](../glossary.md#frame), read its [app-db](../glossary.md#app-db), dispatch events, and hot-swap a handler from your editor through the [Tool-Pair contract](../../../spec/Tool-Pair.md). That's pairing against a live runtime rather than following a recipe — the recipes here cover the after-the-fact read; the live path is its own surface.

## Ship it

| I want to… | Recipe |
|---|---|
| keep tokens, passwords, and large blobs out of traces | [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) |
| hear about production errors with their full context | [Report errors in production](report-errors-in-production.md) |
| set up dev and production builds — tools in, tools out | [Configure dev and production builds](configure-dev-and-prod.md) |
| swap the substrate (the React-family rendering layer) — the loop is identical on all of them | [Use UIx, Helix, or reagent-slim](use-uix-helix-or-slim.md) |

> **For JavaScript developers.** Treat this section the way you'd treat the "Recipes" or "Guides" part of any framework's docs — [React](https://react.dev)'s "you might not need an effect", the Rails guide's "how do I do file uploads". They're task-shaped, copy-pasteable, and deliberately opinionated: one good way, shown fully, rather than a tour of the option space. The option space lives in the spec links at the bottom of each page.

> **From re-frame v1.** v1's recipes lived in a single flat wiki page — a long scroll you searched with Ctrl-F. Here they're one task per page, grouped by phase, each ending in links *down* to the concept and the spec. The shape of an event handler hasn't changed; what's new is that every recipe has a *contract* underneath it you can read in full, and several have a live pairing counterpart (see the Tool-Pair note above) that v1 never offered.

## Can't find your task?

A recipe answers "how do I do X." Some questions sit a step *before* that — they're design decisions, not tasks, and a recipe is the wrong shape for them. Those live elsewhere:

- "Where should this value live — [subscription](../glossary.md#subscription), [flow](../glossary.md#flow), [resource](../../resources/glossary.md#resource), or [machine](../../machines/glossary.md#machine)?" That's [the four homes](../glossary.md#the-four-homes-where-state-lives) decision, and it earns its own page rather than a recipe: [Where should this value live?](../where-state-lives.md)
- Your task spans several features and you'd rather watch one app grow through them in order? The [RealWorld tutorial](../../resources/tutorial/index.md) builds auth, feeds, forms, and invalidation end to end — the same pieces as these recipes, assembled into one running app.
- For everything else, the [spec](../../../spec/README.md) is the complete catalogue of every surface.

> **Why split recipes from design decisions at all?** Because they fail differently. A recipe you can follow *wrong* — and you'll know, because the result won't match the page; the failure is local and reversible. A design decision you can follow *right* and still regret six months later, when the value you parked in the wrong place is wired into forty subscriptions; the failure is non-local and expensive to unwind. Recipes are reversible, placement isn't — so the two get different pages, different shapes, and different amounts of your attention.
