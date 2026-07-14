# Routing

A URL is just another input. Most stacks bolt on a parallel router — its own
components, lifecycle, and store. re-frame2 folds routing into the pipeline you
already have: the **URL is an input**, the active route is ordinary state via a
subscription, and **navigation is an event**. Traceable, time-travelling, and
testable like everything else.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.routing])   ;; day8/re-frame2-routing — forget this → :rf.error/routing-artefact-missing

(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")                    ;; path is the third slot, not a metadata key

@(rf/subscribe [:rf.route/params])    ;; => {:id "hello"}
(rf/dispatch [:rf.route/navigate :app/article {:id "hello"}])
```

Route [loaders](glossary.md#loader) run on the server too — one data-fetch story
with [SSR](../ssr/index.md), no separate server router.

## Start here

1. **[Tutorial](tutorial.md)** — three-page app grown step by step (routes, links,
   params, loaders, 404, Back button, layout). Best first hour.
2. **[The model](concepts.md)** — three moves (registry row, navigate event, route
   sub), then loaders, guards, not-found, URL binding.
3. **Task recipes** when you have one job:
   [unsaved changes](how-to/guard-unsaved-changes.md) (leave guard),
   [require sign-in](how-to/require-sign-in-on-a-route.md) (multi-route interceptor —
   prefer [`:can-enter`](concepts.md#guarding-entry--can-enter) for one page).

**Prerequisites.** [Core introduction](../core/introduction.md) — events, app-db,
subscriptions, views. Routing plugs into those; it does not replace them.

Optional later: [testing](testing.md), [examples](examples.md),
[React Router mapping](coming-from-react-router.md), [glossary](glossary.md).

## When *not* to use routing

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing artefact (zero cost) |
| In-memory UI steps with no URL | app-db flags / a [machine](../machines/index.md) |
| Server-only redirects | Host middleware or [SSR](../ssr/concepts.md) response effects |

Reach for routing when **the address bar is part of the product** — deep links,
shareable state, Back/Forward, SEO/SSR entry.
