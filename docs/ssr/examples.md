# SSR examples

Worked SSR apps in the repo's example tree — each runs end-to-end, not as a sketch.

- **resources_ssr** — server-side preloads a re-frame2 *resource* (an article list) into a request-local frame, drains the blocking fetch before rendering, ships only the allowed resource projection in the hydration payload, and hydrates the client cache so a fresh entry renders immediately with no double-fetch. The `.cljc` source runs the same code on the JVM and in the browser. [→](../../examples/capabilities/ssr/resources_ssr/)

See [Concepts](concepts.md) for the SSR and hydration model these examples exercise.
