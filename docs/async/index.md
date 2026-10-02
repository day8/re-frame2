# Async (HTTP)

Sooner or later the app talks to a server — and inherits errors, timeouts, retries,
loading states, and stale replies racing each other.

Use a **managed request** when your [event handler](../core/effects.md) should
receive the result as another [event](../core/events.md). Describe the request
as data; the runtime sends it, decodes the response and delivers the reply:

```clojure
(ns app.article-http
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))   ;; day8/re-frame2-http

{:fx [[:rf.http/managed
       {:request    {:url "/api/articles/intro"}
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The handler never waits for the server. Success and failure each arrive as an event you
named, handled like any other event.

<a id="in-this-section"></a>

The [article tutorial](tutorial.md) adds a view, error messages, validation and
cancellation to this request. This page helps you choose whether managed HTTP
is the right mechanism for your app.

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

Runnable examples show these patterns in complete applications.

| Example | What it shows |
|---|---|
| [managed_http_counter](../../examples/core/managed_http_counter) | Smallest end-to-end: fetch + update over `:rf.http/managed`, loading/error, manual abort |
| [realworld_http](../../examples/real-apps/realworld_http) | Full Conduit on managed HTTP only (no resources cache): request builders, retry, auth, machines |
| [login](../../examples/core/login) | `:rf.http/managed` answered by an in-page demo stub (an `:fx-overrides` remap); the failure reply rides the state machine's event, the success reply lands on a token-owning event that then signals the machine |
| [nine_states](../../examples/patterns/nine_states) | Every state a list screen can be in, with load events that share one `:request-id` so a newer load supersedes an older one. An in-page stub answers them, and supersession engages once the real transport replaces it |
| [boot](../../examples/patterns/boot) | App startup as a machine: config first, then three loads in parallel under `:spawn-all`, and a Retry screen if any fails |
| [websocket](../../examples/patterns/websocket) | A long-lived connection as a machine: a spawned actor's own effects open, send on and close the socket, and a guard drops messages from a replaced one |
