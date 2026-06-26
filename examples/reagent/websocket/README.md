# Pattern-WebSocket in re-frame2

This example models a WebSocket connection as a [machine](../../../docs/machines/glossary.md#machine).

A connection is best understood as a question, not a value. You rarely care "what's in the connection variable". You care **which state you're in** — connecting, authenticating, connected, dropped, reconnecting, or given-up — and which [events](../../../docs/guide/glossary.md#event) move you between them. That is exactly what a machine describes, and modelling the connection lifecycle as one is the whole point here.

It's the runnable companion to [`spec/Pattern-WebSocket.md`](../../../spec/Pattern-WebSocket.md). The spec's §The connection state machine is the normative description; this example is its working form, wired up so the [views](../../../docs/guide/glossary.md#view) can drive it and you can click through the lifecycle in a browser. No network is needed — a tiny in-process mock server stands in for a real endpoint.

If you've read the [machines guide](../../../docs/machines/concepts.md) and want to see the grammar earn its keep on something harder than a turnstile, this is that example. Three things make a real connection tricky, and the machine grammar has a clean answer for each.

## The load-bearing idea, in three moves

**Move one: the lifecycle is one compound state.** The happy path — `:connecting → :authenticating → :connected` — lives *inside* a parent [state](../../../docs/machines/glossary.md#state) called `:active`. Its siblings are `:disconnected`, `:reconnecting`, and `:failed`. Why nest the three success leaves instead of flattening everything? Because the socket, the offline queue, the in-flight requests, and the subscription set all belong to *one* connection — they share a single `:data` map. A compound state is what expresses that shared lifecycle. (When the axes *don't* share data, you reach for parallel regions instead — see the [`nine_states`](../nine_states/) example. Here the data is shared, so a compound state is the right shape.)

**Move two: the JS WebSocket can't live in app-db, so an actor owns it.** A live `WebSocket` is a host-side reference. It doesn't serialise, it won't survive a time-travel replay, and putting it in [app-db](../../../docs/guide/glossary.md#app-db) would quietly defeat re-frame2's value semantics. The answer is to hand it to a child machine. The `:active` parent [`:spawn`](../../../docs/machines/glossary.md#spawn)s a `:websocket/socket` actor on entry, and *that* actor holds the host-side socket in a private side-table keyed by its own id. The connection machine's `:data` never holds the socket — only its **id**. Because the actor is spawned on `:active`, it survives all three success-path leaf transitions without re-spawning. The moment you leave `:active` — to reconnect, fail, or disconnect — the runtime tears the actor down, and re-entering spawns a fresh one.

**Move three: a stale message must not be believed, and the socket id *is* the clock.** Reconnects create a subtle hazard. A slow `:message` from the socket you just replaced can land *after* the new one is live, and acting on it would be a bug. The fix is cheap: every inbound event carries the id of the socket it came from, and a `:current-socket?` [guard](../../../docs/machines/glossary.md#guard) drops it unless that id matches the one currently live. The live socket id *is* the connection's epoch — no separate version counter, no generation field. That's [Pattern-StaleDetection](../../../spec/Pattern-StaleDetection.md) composed against a value you already had.

Everything below is those three moves, plus the day-to-day grammar (`:after` timers, `:always` cascades, [state tags](../../../docs/machines/glossary.md#state-tag)) doing the unglamorous wiring.

## What this example demonstrates

- **A connection lifecycle as a compound machine.** `:active` parents
  `:connecting` / `:authenticating` / `:connected`. The socket
  [`:spawn`](../../../docs/machines/glossary.md#spawn) is anchored on the parent,
  so it spans the success-path leaf [transitions](../../../docs/machines/glossary.md#transition)
  without re-spawning. Leaving `:active` (to `:reconnecting`, `:failed`, or
  `:disconnected`) destroys the actor; re-entry spawns a fresh one.

- **A spawned actor owning the host-side socket.** The `:websocket/socket` actor
  is itself a small [machine](../../../docs/machines/glossary.md#machine). It
  keeps the JS `WebSocket`-shaped reference in a private store keyed by its
  `:rf/self-id`, turns outbound `:send` events into wire writes, and
  forwards inbound server messages back to the parent. Only the id ever appears
  in `:data` — never the socket itself.

- **Stale-message rejection via the connection epoch.** The live `:socket-id` is
  the epoch; the `:current-socket?` [guard](../../../docs/machines/glossary.md#guard)
  rejects a `:ws/received` (or a `:ws/request-timeout`) from a socket that has
  since been replaced. [Pattern-StaleDetection](../../../spec/Pattern-StaleDetection.md),
  composed against a value already in `:data`.

- **`:after` exponential backoff** on `:reconnecting` — a delay computed at state
  entry from the retry count, `(min (* base-ms (Math/pow 2 retries)) max-backoff-ms)`.
  The runtime's `:after`-epoch invariant drops stale timers from earlier
  `:reconnecting` visits for free, so there's no cancellation flag to remember.

- **Eventless `:always` cascades** doing two distinct jobs: `:reconnecting`'s
  `:max-retries-exceeded?` guard falls straight through to `:failed` when the
  retries run out, and `:connected`'s `:has-queued-messages?` guard fires the
  queue-flush the instant the state is entered.

- **[State tags](../../../docs/machines/glossary.md#state-tag), not state-name
  matching.** Each state declares tags (`:websocket/connected`,
  `:websocket/reconnecting`, `:websocket/active`, …); the view asks
  `machine-has-tag?` — *ask, don't tell* — instead of unfolding the
  [snapshot](../../../docs/machines/glossary.md#snapshot)'s hierarchical `:state`
  vector. Add a sixth "connecting-ish" state later and no view changes.

- **App-owned request/reply correlation.** An `:in-flight` map keyed by
  request-id; `:register-request` stamps the id onto the outgoing body, schedules
  a `:dispatch-later` timeout, and routes the body to the actor. The matching
  inbound `:ws/received` clears the slot and dispatches the registered reply
  [event](../../../docs/guide/glossary.md#event). The correlation id is a folded
  fact from a *recordable* [coeffect](../../../docs/guide/glossary.md#coeffect)
  (`:ws.app/request-id`), not an ambient `(random-uuid)` — so a replay re-presents
  the same id and the reply still matches the recorded request. (This is
  deliberately the app-level Pattern-WebSocket convention, **not** the framework's
  [uniform reply](../../../docs/guide/glossary.md#the-uniform-reply) envelope —
  re-frame2 ships no managed WebSocket, so per-message correlation over the open
  socket is the app's to own.)

- **Reconnect cascade with token refresh threaded through.** `:exit` on `:active`
  clears the `:socket-id`; the runtime destroys the actor (the declarative
  `:spawn` desugars to a `:rf.machine/destroy` on exit). After the `:after`
  backoff, re-entering `:active` re-runs the `:spawn`'s `:data` function, which
  re-reads the URL and token from `:data` — so a `:ws/refresh-token` arriving
  *between* reconnects flows into the next socket with no extra wiring.

- **An offline queue and a surviving subscription set.** A `:ws/send` while
  disconnected enqueues into `:data :queue`; the `:connected` entry's `:always`
  cascade flushes it. A `:ws/subscribe` records its topic in `:data :subscriptions`
  (a set), and every `:connected` entry re-issues the subscribe messages — so
  subscriptions ride straight through a reconnect.

## Files

| File | Notes |
|---|---|
| `core.cljs` | Entry point — installs the adapter, registers the [frame](../../../docs/guide/glossary.md#frame), mounts the React root, runs `:ws.app/initialise`. |
| `connection.cljs` | The `:ws/connection` machine — the heart of the example. Read alongside `spec/Pattern-WebSocket.md` §Worked example; the shapes are identical. |
| `messages.cljs` | The `:websocket/socket` actor (the spawned child) + an in-process mock WebSocket server + `:ws/handle-message` + the app-level send/request/subscribe events. |
| `views.cljs` | UI — status pill driven by tags, lifecycle buttons, send form, request/subscribe/server-push demo trio, inbox. |
| `schema.cljs` | Malli [schemas](../../../docs/guide/glossary.md#schema) for the connection machine's `:data` slice and the `[:messages]` app-db slice. |
| `index.html` | Minimal harness. |

## Mock WebSocket server

The example ships with a tiny in-process `WebSocket`-shaped stub in `messages.cljs`. It supports:

- **Auto-echo for `:request` messages** — every outbound `{:type :request ...}` immediately echoes back as `{:type :reply :request-id ... :ok true :echo ...}`, so the request-reply correlation slot lights up.
- **Auth ack** — `{:type :auth :token ...}` produces `{:type :auth-ok}` for any non-empty token and `{:type :auth-failed :reason "Empty token"}` otherwise.
- **Subscribe ack** — every `{:type :subscribe :topic ...}` is acked with one synthetic `{:type :push :topic ... :note "subscribed"}` so the example demonstrates the subscribe-then-push shape end-to-end.
- **`messages/send-server-push!`** — used by the "Trigger server push" button to deliver a manual server-pushed event.
- **`messages/simulate-disconnect!`** — used by the "Drop connection" button to force every live mock socket closed, triggering the reconnect cascade.

**Production swap-out:** replace `mock-socket-for-actor` with a real `(js/WebSocket. url)` and wire its `onopen` / `onmessage` / `onerror` / `onclose` to the same actor-level dispatches. The connection machine — and every test against it — does not change. That's the payoff of the machine-owns-the-actor / actor-owns-the-host-reference split: the transport is a swappable detail the lifecycle never sees.

## How to run

```bash
shadow-cljs watch examples/websocket
```

Once it's up: click **Connect** and watch the status pill cascade
`CONNECTING → AUTHENTICATING → CONNECTED`. Type a message before connecting and
it queues, then drains the instant you connect. Hit **Drop connection** and watch
the pill walk back through `RECONNECTING → CONNECTING → AUTHENTICATING →
CONNECTED` on its own — the whole reconnect cascade, driven by the machine.
