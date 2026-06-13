# How-to guides

You have a working app and a specific task. Each page on this shelf is a recipe for one task: the goal, the steps, complete code, the working result — in that order, no detours through theory. Recipes assume you've built something already (the [quick start](../quickstart.md) is enough) and can read the loop at sight ([the model](../concepts/index.md), one page). Every recipe ends with links *down*: to the concept page for why it's shaped this way, and to the spec when you need every option.

> **Find the task, follow the recipe, link down for the contract.**

## Build it

| I want to… | Recipe |
|---|---|
| add login and keep the user logged in | [Add authentication](add-auth.md) |
| build a form — local edits, validation, clean submit | [Build a form](build-a-form.md) |
| load a feed one page at a time | [Paginate a feed](paginate-a-feed.md) |
| refetch the right server data after a write | [Invalidate after a mutation](invalidate-after-a-mutation.md) |
| catch bad state and malformed events early | [Validate with schemas](validate-with-schemas.md) |

## Test it

| I want to… | Recipe |
|---|---|
| unit-test a handler as the pure function it is | [Test an event handler](test-an-event-handler.md) |
| test a whole dispatch — state, effects, follow-ups | [Test a full cascade](test-a-cascade.md) |

## Debug it

| I want to… | Recipe |
|---|---|
| see exactly why the app just did that | [Debug with Xray](debug-with-xray.md) |
| find the view that re-renders too much, and stop it | [Find and fix a slow view](fix-a-slow-view.md) |

## Ship it

| I want to… | Recipe |
|---|---|
| keep tokens, passwords, and large blobs out of traces | [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) |
| hear about production errors with their full context | [Report errors in production](report-errors-in-production.md) |
| set up dev and production builds — tools in, tools out | [Configure dev and production builds](configure-dev-and-prod.md) |
| swap the React layer — the loop is identical on all of them | [Use UIx, Helix, or reagent-slim](use-uix-helix-or-slim.md) |

## Can't find your task?

- If the question is "where should this value live — db, sub, flow, resource, or machine?", that's a design decision, not a recipe: [Where should this value live?](../where-state-lives.md)
- If the task spans several features and you'd rather watch one app grow through them, the [RealWorld tutorial](../tutorial/index.md) builds auth, feeds, forms, and invalidation end to end.
- For everything else, [the reference map](../reference.md) indexes the complete surface.

---

**You can now:**

- go from "I want to X" to the right recipe without reading a concept chapter first
- say what every recipe promises: goal, steps, working result, then links down for the why and the contract

**Next:** no app yet? Start with the [five-minute quick start](../quickstart.md). Want the model behind the recipes? [The model: six dominoes, one loop](../concepts/index.md).
