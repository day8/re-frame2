# Routing examples

Worked routing apps you can read end to end in the repo's example tree.

- **routing** — the smallest app that exercises the full Spec 012 surface: a route table via `reg-route`, navigation-as-event (`:rf.route/navigate`), route reads as plain subs (`:rf.route/id` / `:rf.route/params`), and a root view that switches on the route id. Start here. [→ source](../../examples/reagent/routing/)
- **realworld** — routing folded into a full Conduit clone: path params and `?page=N` query params, auth-gated routes via an `auth-guard` interceptor (Spec 012 redirects and guards), route-driven data loads, and navigation blocking for the unsaved-editor form. See routing working under real load. [→ source](../../examples/reagent/realworld/)

For the underlying ideas, see [Concepts](concepts.md).
