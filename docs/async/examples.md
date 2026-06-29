# Async (HTTP) examples

Worked apps that run on managed HTTP, readable end to end in the repo's example tree.

- **realworld** — a full Conduit clone (auth, articles, comments, profiles, favorites, settings) where **every endpoint goes out via `:rf.http/managed`** — no resources cache, just the transport. See the request-builder + retry policy in `http.cljs`, the auth/session flow, and the lifecycles held in state machines. This is managed HTTP under real load. [→ source](../../examples/real-apps/realworld_http)
- **managed-http-counter** — the smallest end-to-end demo: a counter whose value is fetched and updated over `:rf.http/managed`, with the loading/error states wired from the reply. Start here. [→ source](../../examples/core/managed_http_counter)

Building your own non-HTTP async effect (a promise, a callback, a socket)? The [login example](../../examples/core/login) hand-rolls an async `fx` and drives its reply into a machine — see [Your own async effect](custom-effects.md).

For the underlying model, see [Managed HTTP](http.md).
