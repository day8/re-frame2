# Routing

Routing maps the browser URL to a page of your app. In re-frame2 it uses the parts
you already have: routes are registrations, the active route is read with a
subscription, and navigation is an event. There is no separate router component or
store, so routing shows up in traces and tests like any other event.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.routing]))  ;; day8/re-frame2-routing — forget this → :rf.error/routing-artefact-missing

(rf/reg-route :app/article
  {:params [:map [:slug :string]]}
  "/articles/:slug")                  ;; id, metadata map, path

;; Outside a view, name the frame (here the app's :app frame).
(rf/dispatch-sync [:rf.route/navigate {:to :app/article :params {:slug "hello"}}]
                  {:frame :app})
@(rf/subscribe [:rf.route/params] {:frame :app})   ;; => {:slug "hello"}
```

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
