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

## Start here

1. **[Tutorial](tutorial.md)** — build the lifecycle by hand (REPL → frame per request →
   payload → hydrate → mismatch → platforms → Ring).
2. **[The model](concepts.md)** — why the same code runs on a JVM; request lifecycle;
   fail-closed payload; hydrate-then-verify; platform gates; error projection.
3. Open only when a need appears:
   [response](response.md) · [head](head.md) · [streaming](streaming.md).

**Prerequisites.** [Core introduction](../core/introduction.md) — events, app-db, views,
frames. SSR plugs into those; it does not replace them.

Also: [testing](testing.md), [examples](examples.md),
[Next.js mapping](coming-from-nextjs.md), [glossary](glossary.md).

## When *not* to use SSR

| Situation | Prefer |
|---|---|
| Fully authenticated SPA with no SEO / first-paint need | Client-only render |
| Static marketing pages only | Static HTML / site generator |
| One-off JVM report PDF | Not this surface |

Reach for SSR when **first-byte HTML from your real app** matters — crawlers, social
unfurls, or fast first paint — and you refuse a second server-only app.
