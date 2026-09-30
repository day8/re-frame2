# Routing

Routing maps the browser URL to a page of your app. In re-frame2 it uses the parts
you already have: routes are registrations, the active route is read with a
subscription, and navigation is an event. There is no separate router component or
store, so routing shows up in traces and tests like any other event.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))  ;; day8/re-frame2-routing

(rf/reg-route :app/article
  {:params [:map [:slug :string]]}
  "/articles/:slug")

(rf/reg-view article-page []
  (let [{:keys [slug]} @(subscribe [:rf.route/params])]
    [:h1 (str "Article " slug)]))

;; Render a link inside a view scoped to the app's frame.
[rf/route-link {:to :app/article :params {:slug "intro"}} "Read intro"]
```

The [tutorial](tutorial.md#step-1--your-first-route-on-screen) mounts the app in a
frame with `:url-bound? true`, so links, deep links and Back update the same route
state. The route id picks the page to render; the path params tell that page which
article to show.

A route's `:on-match` events also run during [server rendering](../ssr/index.md),
so the server needs no second router.

<a id="in-this-section"></a>

## When not to use routing

| Situation | Prefer |
|---|---|
| Single-screen app, no shareable URLs | No routing package at all |
| UI steps that don't need a URL | app-db flags or a [machine](../machines/index.md) |
| Server-only redirects | Host middleware or [SSR](../ssr/concepts.md) response effects |

Use routing when the address bar matters to your users: deep links, shareable
state, Back and Forward, or server-rendered entry pages.
