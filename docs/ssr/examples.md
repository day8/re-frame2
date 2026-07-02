# SSR examples

Worked SSR apps in the repo's example tree — each runs end-to-end, not as a sketch.

- **ssr** — the base example, and the source the [tutorial](tutorial.md) is adapted from: one `.cljc` file where the server hand-rolls the whole request lifecycle (per-request frame, `:rf/server-init` boot, `render-to-string`, the escaped `__rf_payload` script, teardown in a `finally`) and the client hydrates and verifies through `ssr/hydrate!`. Includes a `:platforms #{:client}` effect the server render skips. Start here. [→](../../examples/capabilities/ssr/ssr)
- **resources_ssr** — server-side preloads a re-frame2 *resource* (an article list) into a request-local frame, drains the blocking fetch before rendering, ships only the allowed resource projection in the hydration payload, and hydrates the client cache so a fresh entry renders immediately with no double-fetch. [→](../../examples/capabilities/ssr/resources_ssr)
- **ssr_streaming** — the streaming shape: a shell that flushes on the first byte with `:rf/suspense-boundary` fallbacks in place, slow regions streaming in as their data resolves, and the canonical final payload as the correctness lock. [→](../../examples/capabilities/ssr/ssr_streaming)

See [Concepts](concepts.md) for the SSR and hydration model these examples exercise.
