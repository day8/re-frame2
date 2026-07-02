# Routing examples

Worked routing apps you can read end to end in the repo's example tree.

- **routing** — the smallest app that exercises the whole routing surface: a route table via `reg-route`, navigation-as-event (`:rf.route/navigate`), route reads as plain subs (`:rf.route/id` / `:rf.route/params`), and a root view that switches on the route id. Start here. [→ source](../../examples/capabilities/routing/routing)
- **realworld** — routing folded into a full Conduit clone: path params and `?page=N` query params, auth-gated routes via an `auth-guard` interceptor, route-driven data loads, and navigation blocking for the unsaved-editor form. See routing working under real load. [→ source](../../examples/real-apps/realworld_http)
- **realworld_resources** — the same Conduit clone with its server state declared as *resources*: routes plan blocking and background fetches with the `:resources` key, scope the personalised feed per user with a named scope resolver, and guard the editor with `:can-leave`. The route-integration side of [resources](../resources/concepts.md), under the same real load. [→ source](../../examples/real-apps/realworld_resources)

For the underlying ideas, see [Concepts](concepts.md).
