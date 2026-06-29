# Routing

A URL is just another input. In most frameworks the router is a parallel system — its own components, its own lifecycle, its own place to hold state — bolted onto the side of your app. re-frame2 folds it into the loop you already have: the **URL is an input**, the active route is ordinary state you read through a subscription, and **navigation is just an event**. So routing is traceable, time-travels, and tests like everything else.

You register routes (`reg-route`) as a table mapping URL patterns to what they need — param/query schemas, a [loader](glossary.md#loader) for the page's data, a [`:can-leave`](glossary.md#route-guard) guard — and read the active route as state.

```clojure
(rf/reg-route :article
  {:path   "/article/:slug"
   :params {:slug :string}})

@(rf/subscribe [:rf.route/params])     ;; => {:slug "hello"}
(rf/dispatch [:rf.route/navigate :article {:slug "hello"}])
```

Because a route's [loader](glossary.md#loader) runs on the server too, routing and [SSR](../ssr/index.md) share one data-fetch story — there's no separate server fetch to keep in sync.

## In this section

- **[Concepts](concepts.md)** — the route table, navigation as events, route params, loaders, guards, not-found, and url-bound frames.
- **[API](../api/re-frame.routing.md)** — `reg-route`, the `:rf.route/*` events and subscriptions, the guard protocol.
- **[Glossary](glossary.md)** — the routing vocabulary in one place.
