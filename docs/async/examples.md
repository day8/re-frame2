# Examples

<a id="async-http-examples"></a>

Runnable apps on managed HTTP. Build something yourself first
([tutorial](tutorial.md) or [Managed HTTP](http.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [managed_http_counter](../../examples/core/managed_http_counter) | Smallest end-to-end: fetch + update over `:rf.http/managed`, loading/error, manual abort | [Tutorial](tutorial.md) |
| [realworld_http](../../examples/real-apps/realworld_http) | Full Conduit on managed HTTP only (no resources cache): request builders, retry, auth, machines | [Managed HTTP](http.md) |
| [login](../../examples/core/login) | Custom non-HTTP async `fx` + reply into a state machine | [Your own async effect](custom-effects.md) |
