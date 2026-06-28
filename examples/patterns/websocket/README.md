# A WebSocket connection that reconnects itself

This example opens a connection to a server, keeps it alive, and — when it drops — reconnects on its own. You can watch the whole thing happen in your browser. There's no real server to set up: a small fake one runs right in the page, so you just start it and click.

The connection is modelled as a [machine](../../../docs/machines/glossary.md#machine), and that's the idea worth taking away:

> **A connection is a situation you're in, not a value you hold.**

You rarely care what's "in the connection variable". You care which state you're in — connecting, authenticating, connected, dropped, reconnecting, or given up — and which [events](../../../docs/core/glossary.md#event) move you from one to the next. A [machine](../../../docs/machines/glossary.md#machine) describes exactly that, so that's what the connection is here.

This is the runnable companion to [Pattern-WebSocket](../../../spec/Pattern-WebSocket.md), which describes the same state machine in spec form. Here it's wired up so the [views](../../../docs/core/glossary.md#view) can drive it.

A real connection is harder than the textbook on/off examples, in three specific ways. Each one has a clean answer, and those three answers are the heart of the example. (New to machines? Read the [machines guide](../../../docs/machines/concepts.md) first — this example assumes the basics.)

## The three hard parts

**1. The connecting steps belong together.** The happy path has three steps: `:connecting`, then `:authenticating`, then `:connected`. All three live inside one parent [state](../../../docs/machines/glossary.md#state) called `:active`. Why group them? Because they're the *same* connection at different stages — they share one socket, one offline queue, one set of in-flight requests (a single `:data` map). Nesting them says exactly that. The siblings of `:active` — `:disconnected`, `:reconnecting`, `:failed` — are the connection *not* up. (When stages don't share data, you'd use parallel regions instead, as [`nine_states`](../nine_states/) does. Here they share, so nesting is the right shape.)

**2. The real socket can't live in app-db, so a helper holds it.** A live `WebSocket` is a browser object, not a value. It won't serialise, and storing it in [app-db](../../../docs/core/glossary.md#app-db) would break time-travel replay. So a child machine holds it instead. When `:active` begins, it [`:spawn`](../../../docs/machines/glossary.md#spawn)s a `:websocket/socket` actor, and that actor keeps the real socket in a private table, off to the side. The connection itself only ever remembers the socket's **id**, never the socket. The actor lives as long as `:active` does, so it carries across all three steps above; leave `:active` and the runtime tears it down, then re-entering spawns a fresh one.

**3. A late message from an old socket must be ignored.** After a reconnect, a slow message from the socket you just replaced can still arrive — and acting on it would be a bug. The fix is cheap: every incoming message carries the id of the socket it came from, and a [guard](../../../docs/machines/glossary.md#guard) called `:current-socket?` drops it unless that id is the one currently live. The live socket id *is* the connection's version number — no separate counter to keep. (That's [Pattern-StaleDetection](../../../spec/Pattern-StaleDetection.md), reusing a value you already had.)

Everything below builds on those three. The rest is ordinary machine grammar — `:after` timers, `:always` cascades, [state tags](../../../docs/machines/glossary.md#state-tag) — doing the routine wiring.

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
  [event](../../../docs/core/glossary.md#event). The correlation id is a folded
  fact from a *recordable* [coeffect](../../../docs/core/glossary.md#coeffect)
  (`:ws.app/request-id`), not an ambient `(random-uuid)` — so a replay re-presents
  the same id and the reply still matches the recorded request. (This is
  deliberately the app-level Pattern-WebSocket convention, **not** the framework's
  [uniform reply](../../../docs/core/glossary.md#the-uniform-reply) envelope —
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
| `core.cljs` | Entry point — installs the adapter, registers the [frame](../../../docs/core/glossary.md#frame), mounts the React root, runs `:ws.app/initialise`. |
| `connection.cljs` | The `:ws/connection` machine — the heart of the example. Read alongside `spec/Pattern-WebSocket.md` §Worked example; the shapes are identical. |
| `messages.cljs` | The `:websocket/socket` actor (the spawned child) + an in-process mock WebSocket server + `:ws/handle-message` + the app-level send/request/subscribe events. |
| `views.cljs` | UI — status pill driven by tags, lifecycle buttons, send form, request/subscribe/server-push demo trio, inbox. |
| `schema.cljs` | Malli [schemas](../../../docs/core/glossary.md#schema) for the connection machine's `:data` slice and the `[:messages]` app-db slice. |
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
