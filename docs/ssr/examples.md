# Examples

Runnable SSR apps in the repository. Each one contains both the server half and
the client half of its page.

| Example | What it shows | Guide page |
|---|---|---|
| [ssr](../../examples/capabilities/ssr/ssr) | The tutorial's app: a hand-rolled per-request frame, the payload script, `hydrate!`, and a `:platforms #{:client}` effect skipped on the server. Ships a frozen `index.html`, so you can watch hydration without a JVM. | [Tutorial](tutorial.md) |
| [resources_ssr](../../examples/capabilities/ssr/resources_ssr) | A resource preloaded before the render, with only the cache's durable entries projected into the payload, so the client renders without fetching again. The page has no route, so it waits for the resource by hand; a routed page declares it `:blocking? true` instead. | [The model](concepts.md), [Resources](../resources/concepts.md) |
| [ssr_streaming](../../examples/capabilities/ssr/ssr_streaming) | `ssr/boundary` regions streamed after the shell, one failing on purpose, and the final payload, which the client trusts over the per-region deltas. | [Streaming](streaming.md) |
| [substrates/fresco/login](../../examples/substrates/fresco/login) | A Fresco app whose body renders on a Node sidecar: one `ssr-handler` with `re-frame.ssr.ring.node/renderer`, a `:render-state` policy beside `:payload`, and a JVM host (`host.clj`) that serves the page and the browser bundle. | [Render on Node](concepts.md#render-on-node) |
