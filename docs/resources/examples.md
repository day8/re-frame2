# Examples

Runnable apps next to the docs. Build something yourself first
([tutorial](tutorial/index.md) or the [model](concepts.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [resources](../../examples/capabilities/resources/resources) | Focused read lifecycle: four *causes* (route, event owner, manual refresh, machine ensure); passive statuses | [The model](concepts.md) |
| [infinite_feed](../../examples/capabilities/resources/infinite_feed) | `:infinite true` load-more; runtime-owned cursor | [Paginate a feed](how-to/paginate-a-feed.md) |
| [linearlite](../../examples/capabilities/resources/linearlite) | The write side on its own: `:optimistic` forward patch, runtime-recorded rollback, `:on-conflict :invalidate`, and a "fail the next write" toggle | [Optimistic writes](how-to/invalidate-after-a-mutation.md#advanced-optimistic-writes) |
| [resources_ssr](../../examples/capabilities/ssr/resources_ssr) | A resource cache rendered on the server and hydrated on the client: first paint has the data, no refetch | [SSR: hydrate, then verify](../ssr/concepts.md#the-client-side-hydrate-then-verify) |
| [realworld_resources](../../examples/real-apps/realworld_resources) | Full Conduit: scope, mutations, optimistic favorite, session | [Tutorial](tutorial/index.md) |
| [realworld_http](../../examples/real-apps/realworld_http) | Same Conduit on raw managed HTTP — the *before* picture | [Async HTTP](../async/http.md) |
| [managed_http_counter](../../examples/core/managed_http_counter) | Bare `:rf.http/managed` substrate resources lower onto | [Async tutorial](../async/tutorial.md) |
