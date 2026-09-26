# Server-Side Rendering

Server-side rendering sends a page's HTML before its JavaScript loads, then lets the
client take over that page. In re-frame2 the same events, subscriptions and views run
on the JVM and in the browser, so you do not write a second, server-only version of
the app. Code that belongs to one side is marked: effects and events with
`:platforms`, and the server and client entry points with reader conditionals.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ssr  :as ssr])   ;; day8/re-frame2-ssr; requiring it installs SSR

;; server (JVM): render a real view in a fresh frame, no DOM
(rf/init! ssr/adapter)
(rf/with-new-frame [f (rf/make-frame {})]
  (ssr/render-to-string ((rf/view :app/root)) {}))

;; client: install the server's state, then mount with {:hydrate? true} to adopt its HTML
(ssr/hydrate! {:frame :app :render-tree-fn (fn [] ((rf/view :app/root)))})
```

SSR works with the [events](../core/introduction.md), app-db, views and frames you
already have. This guide shows how to render your app on the JVM (or on a Node
sidecar), ship its state to the browser and hydrate it there, and serve the pages from
any Ring server with the `day8/re-frame2-ssr-ring` artefact.

## When *not* to use SSR

| Situation | Prefer |
|---|---|
| Fully authenticated SPA with no SEO or first-paint need | Client-only render |
| Static marketing pages only | Static HTML or a site generator |
| A one-off JVM report, such as a PDF | A reporting library; SSR renders your app's pages |

Use SSR when the first response must already contain your app's HTML: for crawlers,
link previews, or a fast first paint.
