# Examples

Runnable SSR apps in the repo. Walk the lifecycle first
([tutorial](tutorial.md) or [the model](concepts.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [ssr](../../examples/capabilities/ssr/ssr) | Hand-rolled per-request frame, payload script, `hydrate!`, `:platforms #{:client}` skip; ships a frozen `index.html` so you can watch hydration without a JVM | [Tutorial](tutorial.md) |
| [resources_ssr](../../examples/capabilities/ssr/resources_ssr) | Blocking resource wait before render; allowed cache projection in the payload; no double-fetch on hydrate | [The model](concepts.md), [Resources](../resources/concepts.md) |
| [ssr_streaming](../../examples/capabilities/ssr/ssr_streaming) | `ssr/boundary` shell + chunks; canonical final payload as correctness lock | [Streaming](streaming.md) |
