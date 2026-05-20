# Pattern-WebSocket in re-frame2

> **Canonical worked example.** This is the runnable example for [`spec/Pattern-WebSocket.md`](../../../spec/Pattern-WebSocket.md). It exercises the canonical connection-machine shape — hierarchical compound `:active` parenting `:connecting` / `:authenticating` / `:connected`, a `:spawn`d socket actor whose lifetime is bound to `:active`, `:after` exponential backoff, `:always` queue-flush on `:connected` entry, `:fsm/tags` for queryable connection-state predicates, and connection-epoch staleness via `:current-socket?` against the live `:socket-id`. Pattern-WebSocket §The connection state machine is the normative description; this example is its runnable form.

## What this example demonstrates

- **Hierarchical compound `:active`** parenting `:connecting`, `:authenticating`, `:connected`. The socket-actor `:spawn` is anchored on `:active` so it survives the success-path leaf transitions (`:connecting` → `:authenticating` → `:connected`) without re-spawning.
- **`:spawn` socket actor (`:websocket/socket`)**, whose lifetime is bound to `:active`. Exit-from-`:active` (to `:reconnecting` or `:failed` or `:disconnected`) destroys the actor; re-entry spawns a fresh one. The actor owns the host-side `WebSocket`-shaped reference via a private store keyed by `:rf/self-id`; only the id ever appears in `:data`.
- **`:after` exponential backoff** on `:reconnecting` — `(fn [snap] (min (* base-ms (Math/pow 2 retries)) max-backoff-ms))`. The `:after`-epoch invariant handles stale timers from prior `:reconnecting` visits.
- **`:always` cascades** — `:reconnecting`'s `:max-retries-exceeded?` guard transitions straight to `:failed`; `:connected`'s `:has-queued-messages?` guard fires `:flush-queue` on entry.
- **`:fsm/tags`** — `:websocket/connected`, `:websocket/reconnecting`, `:websocket/failed`, `:websocket/active`, `:websocket/connecting`, `:websocket/authenticating`. The view reads `rf/machine-has-tag?` rather than unfolding the snapshot's hierarchical `:state` vector.
- **Connection-epoch staleness** — Pattern-StaleDetection composed against the live `:socket-id` (the gensym'd actor address). The `:current-socket?` guard rejects `:ws/received` from a socket that has since been replaced, and similarly suppresses stale `:ws/request-timeout` events.
- **Request/reply correlation** — `:in-flight` map keyed by request-id; `:register-request` stamps the id onto the body, schedules a `:dispatch-later` timeout, and routes the body to the actor; the inbound `:ws/received` correlated by id clears the slot + dispatches the registered reply event.
- **Reconnect-cascade** — `:exit` on `:active` clears `:socket-id`; the runtime destroys the actor automatically (declarative `:spawn`'s desugar emits `:rf.machine/destroy` on exit). Re-entering `:active` after the `:after` backoff spawns a fresh actor; the `:spawn`'s `:data` fn re-reads URL + token from `:data` at each entry, so a `:ws/refresh-token` between reconnects flows in without extra wiring.
- **Offline queue + drain on reconnect** — `:ws/send` while disconnected enqueues in `:data :queue`; the `:connected` state's `:always` cascade flushes the queue on entry.
- **Subscription tracking** — `:ws/subscribe` records the topic in `:data :subscriptions` (a set); on every `:connected` entry the `:flush-queue-and-resubscribe` action re-issues subscribe messages for every tracked topic, so subscriptions survive reconnects.

## Files

| File | Notes |
|---|---|
| `core.cljs` | Entry point — mounts the React root, runs `:app/initialise`. |
| `connection.cljs` | The `:ws/connection` machine — the heart of the example. Read alongside `spec/Pattern-WebSocket.md` §Worked example — the shapes are identical. |
| `messages.cljs` | The `:websocket/socket` actor (the spawned child) + an in-process mock WebSocket server + `:ws/handle-message` + the app-level send/request/subscribe events. |
| `views.cljs` | UI — status pill driven by tags, lifecycle buttons, send form, request/subscribe/server-push demo trio, inbox. |
| `schema.cljs` | Malli schemas for the connection-machine snapshot, the `:data` slice, and the `[:messages]` slice. |
| `index.html` | Minimal harness. |
| `websocket.spec.cjs` | Playwright smoke — drives the connect / request-reply / server-push / drop-and-reconnect path. |
| `test/websocket/connection_test.cljs` | Headless tests: initial state, happy-path lifecycle, offline-queue + drain, reconnect cascade, max-retries → `:failed`, connection-epoch staleness, `:ws/refresh-token`, clean `:ws/disconnect`. |
| `test/websocket/messages_test.cljs` | Headless tests: request-reply correlation, server-push routing, subscription tracking, `[:messages :received]` newest-first invariant. |

## Mock WebSocket server

The example ships with a tiny in-process `WebSocket`-shaped stub in `messages.cljs`. It supports:

- **Auto-echo for `:request` messages** — every outbound `{:type :request ...}` immediately echoes back as `{:type :reply :request-id ... :ok true :echo ...}`, so the request-reply correlation slot lights up.
- **Auth ack** — `{:type :auth :token ...}` produces `{:type :auth-ok}` for any non-empty token and `{:type :auth-failed :reason "Empty token"}` otherwise.
- **Subscribe ack** — every `{:type :subscribe :topic ...}` is acked with one synthetic `{:type :push :topic ... :note "subscribed"}` so the example demonstrates the subscribe-then-push shape end-to-end.
- **`messages/send-server-push!`** — used by the "Trigger server push" button (and the Playwright spec) to deliver a manual server-pushed event.
- **`messages/simulate-disconnect!`** — used by the "Drop connection" button to force every live mock socket closed, triggering the reconnect cascade.

The mock has two delivery modes — async via `setTimeout(_, 0)` (default, used by the browser) and sync (used by the headless tests via `messages/set-mock-sync!`) so `dispatch-sync` observes the full request/reply round-trip without yielding to the JS event loop.

**Production swap-out:** replace `mock-socket-for-actor` with a real `(js/WebSocket. url)` and wire its `onopen` / `onmessage` / `onerror` / `onclose` to the same actor-level dispatches. The connection machine — and every test against it — does not change.

## Architecture references

- [`spec/Pattern-WebSocket.md`](../../../spec/Pattern-WebSocket.md) — the normative pattern (this example's spec).
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — hierarchical states, `:after`, `:always`, `:spawn`, `:fsm/tags`.
- [`spec/Pattern-StaleDetection.md`](../../../spec/Pattern-StaleDetection.md) — composed twice (backoff timer + connection epoch).
- [`spec/Pattern-AsyncEffect.md`](../../../spec/Pattern-AsyncEffect.md) — distinct but adjacent; individual request-reply messages over the open socket fit Pattern-AsyncEffect (the open connection acts as the fx).

## How to run

The example is wired into the canonical examples harness. From `implementation/`:

```bash
npm run test:examples
```

That compiles every example (this one builds under shadow-cljs id `examples/websocket`), stages its `index.html` into `out/examples/websocket/`, serves the lot on port 8030, and runs [`websocket.spec.cjs`](websocket.spec.cjs) against it.

To iterate on the source alone, watch the build directly from `implementation/`:

```bash
shadow-cljs watch examples/websocket
# then visit http://127.0.0.1:8030/websocket/ once the harness is running
```

## Headless tests

The headless tests are pure-CLJS browserless fixtures. They live alongside the sources at `test/websocket/<feature>_test.cljs`, mirroring the realworld layout.

| Source ns | Test ns |
|---|---|
| `websocket.connection` | `websocket.connection-test` |
| `websocket.messages`   | `websocket.messages-test`   |

The tests are wired into the framework's CLJS test run via `re-frame.websocket-cljs-test` under `implementation/adapters/reagent/test/re_frame/`, which wraps each fixture in a `testing` block under a single `deftest`.

```bash
cd implementation
npm run test:cljs
```
