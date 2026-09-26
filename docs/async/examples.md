# Examples

<a id="async-http-examples"></a>

Runnable apps on managed HTTP, and one on an effect of its own. Build something yourself first
([tutorial](tutorial.md) or [Managed HTTP](http.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [managed_http_counter](../../examples/core/managed_http_counter) | Smallest end-to-end: fetch + update over `:rf.http/managed`, loading/error, manual abort | [Tutorial](tutorial.md) |
| [realworld_http](../../examples/real-apps/realworld_http) | Full Conduit on managed HTTP only (no resources cache): request builders, retry, auth, machines | [Managed HTTP](http.md) |
| [login](../../examples/core/login) | `:rf.http/managed` answered by an in-page demo stub (an `:fx-overrides` remap); the failure reply rides the state machine's event, the success reply lands on a token-owning event that then signals the machine | [Managed HTTP](http.md) |
| [nine_states](../../examples/patterns/nine_states) | Every state a list screen can be in, with a managed-HTTP load whose shared `:request-id` lets a newer load supersede an older one | [Cancellation](http.md#cancellation-supersession-and-abort) |
| [boot](../../examples/patterns/boot) | App startup as a machine: config first, then three loads in parallel under `:spawn-all`, and a Retry screen if any fails | [From a state machine](http.md#from-a-state-machine) |
| [websocket](../../examples/patterns/websocket) | A long-lived connection as a machine: a spawned actor's own effects open, send on and close the socket, and a guard drops messages from a replaced one | [Your own async effect](custom-effects.md) |
