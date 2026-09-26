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
