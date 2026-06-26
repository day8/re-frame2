# Server-Side Rendering

Ship HTML before the JavaScript loads, then let the client take over — without writing your app twice. That's the promise SSR usually breaks: you end up maintaining a server render path and a client render path that drift, plus a tangle of `typeof window` checks. re-frame2's rule is **"one app, runs twice."** The same events, subscriptions, and views run on the JVM (server) and in the browser (client); only genuinely one-sided code is fenced off with `:platforms`.

On the server, [`render-to-string`](glossary.md#render-to-string) runs your real views in a per-request [frame](../guide/glossary.md#frame) and emits HTML plus a serialized state payload. On the client, [hydration](glossary.md#hydration) adopts that HTML and installs the state instead of re-rendering from scratch — and a [hydration mismatch](glossary.md#hydration-mismatch) is *located and traced*, not silently patched.

```clojure
;; server (JVM): your real views, in a per-request frame, no DOM
(rf.ssr/render-to-string [app-view] {:db initial-db})

;; client: adopt the server's HTML instead of re-rendering it
(rf/dispatch [:rf/hydrate payload])
```

## In this section

- **[Concepts](concepts.md)** — one app runs twice, `:platforms`, render-to-string, hydration, and locating mismatches.
- **[API](api.md)** — `render-to-string`, the hydration payload contract, `:rf/hydrate`, strict-mode config.
- **[Glossary](glossary.md)** — the SSR vocabulary in one place.
