# Server-Side Rendering

Ship HTML before the JavaScript loads, then let the client take over — without
writing your app twice. Most stacks break that promise with a server path and a
client path that drift, plus `typeof window` checks. re-frame2's rule is **"one
app, runs twice."** The same events, subscriptions, and views run on the JVM and
in the browser; only genuinely one-sided code is fenced with `:platforms`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.ssr  :as ssr])   ;; day8/re-frame2-ssr — forget this → :rf.error/ssr-artefact-missing

;; server (JVM): real views, per-request frame — no DOM
(rf/with-frame f
  (ssr/render-to-string [(rf/view :app/root)] {}))

;; client: adopt the server's HTML + state
(ssr/hydrate! {:frame :app :render-tree-fn (fn [] ((rf/view :app/root)))})
```

SSR plugs into [events](../core/introduction.md), app-db, views, and frames.
It does not replace them.

## When *not* to use SSR

| Situation | Prefer |
|---|---|
| Fully authenticated SPA with no SEO / first-paint need | Client-only render |
| Static marketing pages only | Static HTML / site generator |
| One-off JVM report PDF | Not this surface |

Reach for SSR when **first-byte HTML from your real app** matters — crawlers, social
unfurls, or fast first paint — and you refuse a second server-only app.
