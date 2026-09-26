# How-to guides

Each page here completes one task in an app you already have running: a form, login, a slow view, production error reporting. A recipe states the goal, shows the complete code, and links to the concept page for the *why* instead of re-teaching it. If you are still learning the [event pipeline](../glossary.md#event-pipeline), start with the [introduction](../introduction.md) and the pages from [events](../events.md) through [views](../views.md) first.

Testing has [its own section](../testing/index.md).

!!! note "If your views are Fresco"

    The view code in these recipes is written for a React substrate adapter with `reg-view` views: the adapter's `client-root` / `render!` to mount, `@(subscribe …)` to read, `^{:key}` metadata on list items, and Form-2 views where a value has to stay stable across renders. [Fresco](../fresco/index.md) accepts none of those spellings, so a recipe's view code will not run as written there. Everything below the view carries over unchanged: events, effects, subscriptions and frames. For the view and the mount, read [Installation](../fresco/00-installation.md) and [Views and reads](../fresco/02-views-and-reads.md); for the debugging recipes, [Diagnostics](../fresco/16-diagnostics.md) and [Performance](../fresco/19-performance.md).

## Build it

| I want to… | Recipe |
|---|---|
| boot and mount the app, with hot reload | [Boot and mount an app](boot-and-mount-an-app.md) |
| add login and keep the user logged in | [Add authentication](add-auth.md) |
| build a form: local edits, validation, clean submit | [Build a form](build-a-form.md) |
| load a feed one page at a time | [Paginate a feed](../../resources/how-to/paginate-a-feed.md) |
| refetch the right server data after a write | [Invalidate after a mutation](../../resources/how-to/invalidate-after-a-mutation.md) |
| catch bad state and malformed events early | [Validate with schemas](validate-with-schemas.md) |

## Debug it

Every dispatch records what it did on the [trace stream](../glossary.md#trace-stream), one [epoch](../glossary.md#epoch) per event ([Observability](../observability.md) explains how). These recipes read that record.

| I want to… | Recipe |
|---|---|
| see exactly why the app just did that | [Debug with Xray](../../xray/index.md) |
| find the view that re-renders too much, and stop it | [Find and fix a slow view](fix-a-slow-view.md) |

To inspect a *running* app instead — read a [frame](../glossary.md#frame)'s app-db, dispatch events, hot-swap a handler from your editor — use the [pair skill](../../skills/re-frame2-pair.md).

## Ship it

| I want to… | Recipe |
|---|---|
| keep tokens, passwords, and large blobs out of traces | [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) |
| hear about production errors with their full context | [Report errors in production](report-errors-in-production.md) |
| set up dev and production builds | [Configure dev and production builds](configure-dev-and-prod.md) |
| render with UIx or reagent-slim instead of Reagent | [Use UIx or reagent-slim](use-uix-or-slim.md) |

## When a recipe is the wrong shape

Some questions are design decisions rather than tasks:

- "Should this value be a [subscription](../glossary.md#subscription), a [flow](../glossary.md#flow), a [resource](../../resources/glossary.md#resource), or a [machine](../../machines/glossary.md#machine)?" See [Where should this value live?](../where-state-lives.md)
- To watch one app grow through auth, feeds, forms, and invalidation in order, follow the [RealWorld tutorial](../../resources/tutorial/index.md).
- For the exact shape of a public function, use the [API reference](../../api/README.md).
