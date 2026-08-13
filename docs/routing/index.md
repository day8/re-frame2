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
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "hello"}}])
```

Route [loaders](glossary.md#loader) run on the server too — one data-fetch story
with [SSR](../ssr/index.md), no separate server router.

<a id="in-this-section"></a>

Routing plugs into [events](../core/introduction.md), app-db, subscriptions,
and views. It does not replace them.

## When *not* to use routing

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing artefact (zero cost) |
| In-memory UI steps with no URL | app-db flags / a [machine](../machines/index.md) |
| Server-only redirects | Host middleware or [SSR](../ssr/concepts.md) response effects |

Reach for routing when **the address bar is part of the product** — deep links,
shareable state, Back/Forward, SEO/SSR entry.
