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

- **[Tutorial: build a routed app](tutorial.md)** — start here. Build a small three-page app one piece at a time: routes, links, dynamic segments, loaders, the 404, the Back button, and a shared layout.
- **[Concepts](concepts.md)** — the whole model in three moves, then everything a growing app reaches for: query strings, the loading/error transition, navigation blocking, data classification, and routing on the server.
- **[Coming from React Router](coming-from-react-router.md)** — the mapping from `createBrowserRouter`, loaders, and the hooks, and where re-frame2 deliberately diverges.
- **How-to** — task recipes: [guard against unsaved changes](how-to/guard-unsaved-changes.md), and [require sign-in on a route](how-to/require-sign-in-on-a-route.md).
- **[API](../api/re-frame.routing.md)** — `reg-route`, the `:rf.route/*` events and subscriptions, the URL helpers, the guard protocol.
- **[Glossary](glossary.md)** — the routing vocabulary in one place.
- **[Examples](examples.md)** — worked routing apps you can read end to end.
