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

No `await`, no callback nesting, no resumed stack frame. Success and failure each
have a name on the same wire as everything else.

<a id="in-this-section"></a>

Managed HTTP plugs into the event pipeline. It does not replace events or app-db.

## Scope

| This section | Elsewhere |
|---|---|
| One request → one reply event (transport) | [Resources](../resources/index.md) — cache, stale, invalidate over this transport |
| Continuations as **named events** | [Effects](../core/effects.md) — `dispatch-later`, drain / run-to-completion |
| Custom promise/callback SDKs | [Your own async effect](custom-effects.md) |

## When *not* to use managed HTTP alone

| Situation | Prefer |
|---|---|
| Same read on many screens, cache, invalidate | [Resources](../resources/concepts.md) |
| Multi-step lifecycle (login, websocket) | [Machines](../machines/index.md) |
| Non-HTTP async (Stripe, IndexedDB, worker) | [Your own fx](custom-effects.md) |
| No server yet | app-db + events |

Reach for managed HTTP when **one wire call needs a typed, testable reply** — not when
the load-bearing concept is a cache or a named stage machine.
