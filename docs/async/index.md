# Async (HTTP)

Sooner or later the app talks to a server — and inherits errors, timeouts, retries,
loading states, and stale replies racing each other.

re-frame2's answer is the **managed request**. Describe it as data, return it from a
pure [event handler](../core/effects.md), and finish. The runtime performs it. The
reply arrives later as an **ordinary [event](../core/events.md)**:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.http.managed])   ;; day8/re-frame2-http — forget this → :rf.error/no-such-fx

{:fx [[:rf.http/managed
       {:request    {:url "/api/articles/intro"}
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The handler never waits for the server. Success and failure each arrive as an event you
named, handled like any other event.

<a id="in-this-section"></a>

The [tutorial](tutorial.md) builds this request step by step.

Managed HTTP plugs into the event pipeline. It does not replace events or app-db.

## When *not* to use managed HTTP alone

| Situation | Prefer |
|---|---|
| The same read on many screens, with a cache and invalidation | [Resources](../resources/index.md), which run on this transport |
| A multi-step lifecycle (login) or a long-lived connection (a websocket) | [State machines](../machines/index.md); there is no managed streaming surface |
| Non-HTTP async (a payment SDK, IndexedDB, a worker) | [Your own async effect](custom-effects.md) |
| A delayed or follow-on dispatch | [Effects](../core/effects.md): `:dispatch-later` |
| No server yet | app-db + events |

## Examples

<a id="async-http-examples"></a>

Runnable apps on managed HTTP, and one on an effect of its own, smallest first. The last column names the page each one builds on.

| Example | What it shows | Read first |
|---|---|---|
| [managed_http_counter](../../examples/core/managed_http_counter) | Smallest end-to-end: fetch + update over `:rf.http/managed`, loading/error, manual abort | [Tutorial](tutorial.md) |
| [realworld_http](../../examples/real-apps/realworld_http) | Full Conduit on managed HTTP only (no resources cache): request builders, retry, auth, machines | [Managed HTTP](http.md) |
| [login](../../examples/core/login) | `:rf.http/managed` answered by an in-page demo stub (an `:fx-overrides` remap); the failure reply rides the state machine's event, the success reply lands on a token-owning event that then signals the machine | [Managed HTTP](http.md) |
| [nine_states](../../examples/patterns/nine_states) | Every state a list screen can be in, with load events that share one `:request-id` so a newer load supersedes an older one. An in-page stub answers them, and supersession engages once the real transport replaces it | [Cancellation](http.md#cancellation-supersession-and-abort) |
| [boot](../../examples/patterns/boot) | App startup as a machine: config first, then three loads in parallel under `:spawn-all`, and a Retry screen if any fails | [From a state machine](http.md#from-a-state-machine) |
| [websocket](../../examples/patterns/websocket) | A long-lived connection as a machine: a spawned actor's own effects open, send on and close the socket, and a guard drops messages from a replaced one | [Your own async effect](custom-effects.md) |
