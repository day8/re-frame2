# Examples

<a id="async-http-examples"></a>

Runnable apps on managed HTTP. Build something yourself first
([tutorial](tutorial.md) or [Managed HTTP](http.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [managed_http_counter](../../examples/core/managed_http_counter) | Smallest end-to-end: fetch + update over `:rf.http/managed`, loading/error, manual abort | [Tutorial](tutorial.md) |
| [realworld_http](../../examples/real-apps/realworld_http) | Full Conduit on managed HTTP only (no resources cache): request builders, retry, auth, machines | [Managed HTTP](http.md) |
| [login](../../examples/core/login) | `:rf.http/managed` answered by an in-page demo stub (an `:fx-overrides` remap); the failure reply rides the state machine's event, the success reply lands on a token-owning event that then signals the machine | [Managed HTTP](http.md) |
