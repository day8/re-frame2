# Async (HTTP) examples

Worked apps that run on managed HTTP, readable end to end in the repo's example tree.

- **managed-http-counter** — the smallest end-to-end demo: a counter whose value is fetched and updated over `:rf.http/managed`, with the loading/error states wired from the reply, plus the manual-abort path. Start here. [→ source](../../examples/core/managed_http_counter)
- **realworld** — a full Conduit clone (auth, articles, comments, profiles, favorites, settings) where **every endpoint goes out via `:rf.http/managed`** — no resources cache, just the transport. See the request-builder + retry policy in `http.cljs`, the auth/session flow, and the lifecycles held in state machines. This is managed HTTP under real load. [→ source](../../examples/real-apps/realworld_http)
- **login** — hand-rolls a non-HTTP async `fx` (the [Your own async effect](custom-effects.md) pattern) and drives its reply into a state machine. [→ source](../../examples/core/login)
