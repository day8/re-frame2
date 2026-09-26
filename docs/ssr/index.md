# Server-Side Rendering

Ship HTML before the JavaScript loads, then let the client take over — without
writing your app twice. Most stacks break that promise with a server path and a
client path that drift, plus `typeof window` checks. re-frame2's rule is **"one
app, runs twice."** The same events, subscriptions, and views run on the JVM and
in the browser; only genuinely one-sided code is fenced — effects and events with
`:platforms`, the server and client entry points with reader conditionals.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ssr  :as ssr])   ;; day8/re-frame2-ssr — loading it installs SSR

;; server (JVM): real views, a fresh frame per request — no DOM
(rf/with-new-frame [f (rf/make-frame {})]
  (ssr/render-to-string ((rf/view :app/root)) {}))

;; client: adopt the server's state, then mount with {:hydrate? true} to adopt its HTML
(ssr/hydrate! {:frame :app :render-tree-fn (fn [] ((rf/view :app/root)))})
```

SSR plugs into [events](../core/introduction.md), app-db, views, and frames.
It does not replace them. This section covers rendering on the JVM (or on a Node
sidecar), shipping state to the client and hydrating it, catching hydration
mismatches, shaping the HTTP response and `<head>`, streaming, and testing. The
`day8/re-frame2-ssr-ring` artefact serves the pages from any Ring server.

## When *not* to use SSR

| Situation | Prefer |
|---|---|
| Fully authenticated SPA with no SEO / first-paint need | Client-only render |
| Static marketing pages only | Static HTML / site generator |
| One-off JVM report PDF | Not this surface |

Reach for SSR when **first-byte HTML from your real app** matters — crawlers, social
unfurls, or fast first paint — and you refuse a second server-only app.
