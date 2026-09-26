# Examples

Runnable SSR apps in the repo. Walk the lifecycle first
([tutorial](tutorial.md) or [the model](concepts.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [ssr](../../examples/capabilities/ssr/ssr) | Hand-rolled per-request frame, payload script, `hydrate!`, `:platforms #{:client}` skip; ships a frozen `index.html` so you can watch hydration without a JVM | [Tutorial](tutorial.md) |
| [resources_ssr](../../examples/capabilities/ssr/resources_ssr) | Blocking resource wait before render; allowed cache projection in the payload; no double-fetch on hydrate | [The model](concepts.md), [Resources](../resources/concepts.md) |
| [ssr_streaming](../../examples/capabilities/ssr/ssr_streaming) | `ssr/boundary` shell + chunks; canonical final payload as correctness lock | [Streaming](streaming.md) |
| [substrates/fresco/login](../../examples/substrates/fresco/login) | A Fresco app whose body renders on a Node sidecar: one `ssr-handler` with `re-frame.ssr.ring.node/renderer`, a `:render-state` policy beside `:payload`, and a JVM host (`host.clj`) that serves the page and the browser bundle | [Render on Node](concepts.md#render-on-node) |
