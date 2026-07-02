# Server-Side Rendering

Ship HTML before the JavaScript loads, then let the client take over — without writing your app twice. That's the promise SSR usually breaks: you end up maintaining a server render path and a client render path that drift, plus a tangle of `typeof window` checks. re-frame2's rule is **"one app, runs twice."** The same events, subscriptions, and views run on the JVM (server) and in the browser (client); only genuinely one-sided code is fenced off with `:platforms`.

On the server, [`render-to-string`](glossary.md#render-to-string) runs your real views in a per-request [frame](../core/glossary.md#frame) and emits HTML plus a serialized state payload. On the client, [hydration](glossary.md#hydration) adopts that HTML and installs the state instead of re-rendering from scratch — and a [hydration mismatch](glossary.md#hydration-mismatch) is *located and traced*, not silently patched.

## In this section

- **[Concepts](concepts.md)** — the whole story, built to read straight down: why the same code runs on a JVM, the smallest server, the client that hydrates it, then the richer pieces — the payload allowlist, response control, head metadata, error projection, and streaming.
- **[Examples](examples.md)** — runnable end-to-end SSR apps in the repo.
- **[Glossary](glossary.md)** — the section's vocabulary, one definition each.
- **[Coming from Next.js](coming-from-nextjs.md)** — the translation table and the deliberate divergences.

The API-level surface lives in [re-frame.ssr](../api/re-frame.ssr.md) and [re-frame.ssr.ring](../api/re-frame.ssr.ring.md): the guide teaches, the reference is where you look things up.

```clojure
;; server (JVM): your real views, in a per-request frame, no DOM
(rf.ssr/render-to-string [app-view] {:db initial-db})

;; client: adopt the server's HTML instead of re-rendering it
(rf/dispatch [:rf/hydrate payload])
```
