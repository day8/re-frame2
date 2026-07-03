# Routing

A URL is just another input. In most frameworks the router is a parallel system — its own components, its own lifecycle, its own place to hold state — bolted onto the side of your app. re-frame2 folds it into the event pipeline you already have: the **URL is an input**, the active route is ordinary state you read through a subscription, and **navigation is just an event**. So routing is traceable, time-travels, and tests like everything else.

You register routes (`reg-route`) as a table mapping URL patterns to what they need — param/query schemas, a [loader](glossary.md#loader) for the page's data, a [`:can-leave`](glossary.md#route-guard) guard — and read the active route as state.

```clojure
;; A route is data: an id, a metadata map, and a path (the third slot).
(rf/reg-route :app/article
  {:params [:map [:id :string]]}
  "/articles/:id")

;; Read the active route as state; change it by dispatching an event.
@(rf/subscribe [:rf.route/params])                  ;; => {:id "hello"}
(rf/dispatch [:rf.route/navigate :app/article {:id "hello"}])
```

Because a route's [loader](glossary.md#loader) runs on the server too, routing and [SSR](../ssr/index.md) share one data-fetch story — there's no separate server fetch to keep in sync.

## In this section

- **[Tutorial: build a routed app](tutorial.md)** — a three-page app grown one step at a time: routes, links, dynamic segments, loaders, the 404, the Back button, and a shared layout. Start here.
- **[Concepts](concepts.md)** — the whole model in three moves, then the refinements: query strings, loaders and resources, blocking a navigation, not-found, classification, and running the same handler on the server.
- **How-to** — [Guard against unsaved changes](how-to/guard-unsaved-changes.md) and [Require sign-in on a route](how-to/require-sign-in-on-a-route.md): one task each, complete code.
- **[Testing](testing.md)** — the URL codec as pure function calls, navigation through a test frame, deep links and the 404, and the leave guard — all with zero DOM.
- **[Examples](examples.md)** — runnable routing apps, small and under real load.
- **[Glossary](glossary.md)** — the section's vocabulary, one definition each.
- **[Coming from React Router](coming-from-react-router.md)** — the mapping table and the deliberate divergences.

The routing docs are a **guide** — read top to bottom to learn routing, or dip in to understand one part. Every signature, event, subscription, and keyword has its canonical home in the separate **[API reference](../api/re-frame.routing.md)**: the guide teaches, the reference is where you look things up.
