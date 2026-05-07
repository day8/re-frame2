# Pattern — WebSocket

> **Type:** Pattern
> Long-lived connection lifecycle — WebSocket / SSE / WebRTC peer — modelled as a state machine that owns the socket. Convention, not Spec.

## Role

A **named pattern**, not a Spec. WebSockets do not fit [Pattern-AsyncEffect](Pattern-AsyncEffect.md): they are state-machine-shaped — a long-lived connection with retry, exponential backoff, server-pushed events, heartbeat, subscription management, message correlation, queued sends when disconnected, and re-auth on reconnect. The natural canonical answer is a **state machine that owns the connection lifecycle**.

This doc names that machine's standard shape so per-app instances cite a single canonical description rather than re-deriving the lifecycle each time.

## Why WebSocket is not Pattern-AsyncEffect

Pattern-AsyncEffect is "post work, await reply, dispatch result, commit." It is a one-shot interaction. WebSocket has none of those bounds:

- The connection itself has phases — `:disconnected`, `:connecting`, `:authenticating`, `:connected`, `:reconnecting`, `:failed`. Each is a distinct state with distinct allowed transitions.
- The connection lasts longer than any single message; messages flow in both directions while in `:connected`.
- Retry-with-backoff requires a timer mechanism and a counter; the canonical answer is `:after` + `:always` + machine-scoped guards (per [005](005-StateMachines.md)).
- Subscription state — which topics the app is subscribed to — must survive reconnects. The machine carries it in `:data`.
- Server-pushed events arrive without a corresponding request; they are dispatched events landing in the running-app machinery.

Treat individual *messages* over an open WebSocket as Pattern-AsyncEffect interactions when they are request-reply (correlation-id keyed). Treat the *connection* as a state machine.

This pattern applies equally to **Server-Sent Events (EventSource)** and **WebRTC peer connections** — they share the long-lived-connection-with-lifecycle shape. Differences are mostly in the wire format and server-pushed-vs-bidirectional message semantics; the state machine shape is identical.

## The connection state machine

The canonical states form a hierarchical machine. Top-level states cover the lifecycle; `:connected` is a compound state with sub-states because once the connection is up there are still distinct internal modes (idle / sending / receiving). Other top-level states do not need internal hierarchy.

| State | Meaning |
|---|---|
| `:disconnected` | No socket; not yet attempted, or destroyed cleanly. |
| `:connecting` | Socket opening. |
| `:authenticating` | Socket open; auth handshake in flight. |
| `:connected` | Socket open and authenticated; sub-states `:idle / :sending / :receiving`. |
| `:reconnecting` | Connection lost; waiting on `:after` backoff before re-attempt. |
| `:failed` | Max retries exceeded; manual recovery only. (Terminal until external `[:reconnect]` dispatched.) |

### Standard transitions

```text
:disconnected     --:open-->        :connecting
:connecting       --:opened-->      :authenticating
:connecting       --:error-->       :reconnecting
:authenticating   --:auth-ok-->     :connected
:authenticating   --:auth-failed--> :failed
:connected        --:close-->       :reconnecting
:connected        --:fatal-->       :failed
:reconnecting     --:after backoff-->  :connecting
:reconnecting     --:always max-retries-->  :failed
:failed           --[:reconnect]-->  :connecting
```

The connection machine composes the locked substrate:

- **Hierarchical states** ([005 §Hierarchical compound states](005-StateMachines.md)) — `:connected` has internal sub-states.
- **`:after`** ([005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) — exponential backoff timer in `:reconnecting`. The `:after` epoch ensures stale timers from prior reconnect attempts are silently ignored on transitions away.
- **`:always`** ([005 §Eventless `:always` transitions](005-StateMachines.md)) — max-retries guard fires immediately on entry to `:reconnecting` if `:retries` exceeds the limit, transitioning straight to `:failed`. Also used to flush queued messages on entry to `:connected`.
- **Machine-scoped `:guards` / `:actions`** ([005 §Registration — the machine IS the event handler](005-StateMachines.md)) — for `:max-retries-exceeded?`, `:has-queued-messages?`, `:bump-retry-count`, `:flush-queue`, etc.
- **`:invoke`** ([005 §Declarative `:invoke`](005-StateMachines.md#declarative-invoke-sugar-over-spawn)) — `:connecting` invokes a `:websocket/socket` actor that owns the actual `WebSocket` object; `:exit` destroys it. The actor's lifetime is bound to the parent state.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — replies from a previous connection epoch (e.g., a `:received` event from a socket that has since been replaced) are suppressed via the same epoch idiom that `:after` already uses. Each connection epoch advances on `:reconnecting` entry.

## Worked example — connection machine

```clojure
(rf/reg-event-fx :ws/connection
  {:doc "WebSocket connection lifecycle: disconnected → connecting → authenticating
         → connected → reconnecting (with backoff) → failed."}
  (rf/create-machine-handler
    {:initial :disconnected
     :data    {:url           nil                  ;; supplied by :connect; see :connect action below
               :auth-token    nil                  ;; supplied by :connect (or refreshed at runtime)
               :retries       0
               :max-retries   8
               :backoff-ms    1000
               :max-backoff   30000
               :socket-id     nil
               :subscriptions #{}                  ;; topics to (re-)subscribe on connect
               :queue         []                   ;; messages buffered while disconnected
               :in-flight     {}}                  ;; correlation-id → reply-event

     :guards
     {:max-retries-exceeded?
      (fn [{:keys [data]} _]
        (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn [{:keys [data]} _]
        (seq (:queue data)))}

     :actions
     {:record-connection-opts
      ;; Caller supplies :url and :auth-token at connect time:
      ;;   (rf/dispatch [:ws/connection [:ws/connect {:url        "wss://api.example.com/ws"
      ;;                                              :auth-token (some-token)}]])
      ;; The opts land in :data; subsequent reconnect attempts reuse them.
      (fn [{:keys [data]} [_ {:keys [url auth-token]}]]
        {:data (assoc data :url url :auth-token auth-token)})

      :bump-retry
      (fn [{:keys [data]} _]
        {:data (-> data
                   (update :retries inc)
                   (update :backoff-ms #(min (* 2 %) (:max-backoff data))))})

      :reset-retry
      (fn [{:keys [data]} _]
        {:data (assoc data :retries 0 :backoff-ms 1000)})

      :record-token
      ;; Used when the running app refreshes the auth token without a full reconnect.
      (fn [{:keys [data]} [_ token]]
        {:data (assoc data :auth-token token)})

      :send-auth
      ;; The socket actor accepts a [:send msg] event; route into it.
      (fn [{:keys [data]} _]
        {:fx [[:dispatch [(:socket-id data) [:send {:type  :auth
                                                    :token (:auth-token data)}]]]]})

      :resubscribe
      ;; On (re)connect, re-issue subscriptions tracked in :data.
      (fn [{:keys [data]} _]
        {:fx (mapv (fn [topic]
                     [:dispatch [(:socket-id data)
                                 [:send {:type :subscribe :topic topic}]]])
                   (:subscriptions data))})

      :flush-queue
      ;; Send any messages buffered while disconnected; clear the queue.
      (fn [{:keys [data]} _]
        {:data (assoc data :queue [])
         :fx   (mapv (fn [msg] [:dispatch [(:socket-id data) [:send msg]]])
                     (:queue data))})

      :enqueue-message
      ;; Buffer a send while disconnected.
      (fn [{:keys [data]} [_ msg]]
        {:data (update data :queue conj msg)})

      :record-error
      (fn [{:keys [data]} [_ err]]
        {:data (assoc data :error err)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target :connecting
                         :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}}}

      :connecting
      {:invoke {:machine-id :websocket/socket
                ;; Spawn-spec :data fn (mechanism 2 in Pattern-AsyncEffect's
                ;; "Parameter passing across the boundary"): the child socket
                ;; actor reads the URL and auth-token from the parent's :data.
                :data       (fn [snap _] {:url        (-> snap :data :url)
                                          :auth-token (-> snap :data :auth-token)})
                :on-spawn   (fn [d id] (assoc d :socket-id id))}
       :on     {:ws/opened  {:target :authenticating}
                :ws/error   {:target :reconnecting
                             :action :bump-retry}
                :ws/send    {:action :enqueue-message}}}

      :authenticating
      {:entry :send-auth
       :on    {:ws/auth-ok     {:target :connected}
               :ws/auth-failed {:target :failed
                                :action :record-error}
               :ws/error       {:target :reconnecting
                                :action :bump-retry}
               :ws/send        {:action :enqueue-message}}}

      :connected
      {:entry  [:reset-retry :resubscribe]
       :always [{:guard :has-queued-messages? :action :flush-queue}]
       :on     {:ws/closed   {:target :reconnecting
                              :action :bump-retry}
                :ws/fatal    {:target :failed
                              :action :record-error}
                :ws/received {:action (fn [_ [_ msg]]
                                        ;; Translate the server-pushed event into a
                                        ;; named dispatched event; the running-app
                                        ;; handlers commit it.
                                        {:fx [[:dispatch [:ws/handle-message msg]]]})}
                :ws/send     {:action (fn [{:keys [data]} [_ msg]]
                                        {:fx [[:dispatch [(:socket-id data)
                                                          [:send msg]]]]})}}
       :initial :idle
       :states  {:idle      {}
                 :sending   {}
                 :receiving {}}}

      :reconnecting
      {:always [{:guard :max-retries-exceeded? :target :failed}]
       :after  {1000 {:target :connecting}}     ;; the backoff-ms is read from :data
                                                 ;; via a per-snapshot computation in
                                                 ;; richer specs; shown literal here.
       :on     {:ws/connect {:target :connecting :action :reset-retry}
                :ws/send    {:action :enqueue-message}}}

      :failed
      {:on {:ws/connect {:target :connecting :action :reset-retry}}}}}))
```

The `:websocket/socket` invoked actor is itself a small machine (or fx-backed event handler) that owns the JS `WebSocket` instance and translates `:open`, `:message`, `:error`, `:close` events into dispatches back to the parent connection machine. Its lifetime is bound to the parent's `:connecting` state — leaving `:connecting` (whether to `:authenticating` on success or `:reconnecting` on error) destroys it; entering it again creates a fresh socket. (Per [005 §Composition with explicit `:entry` / `:exit`](005-StateMachines.md#composition-with-explicit-entry--exit) the parent can read the actor's last reported state on exit.)

### Parameters

The connection's `:url` and `:auth-token` arrive on the `:ws/connect` event:

```clojure
(rf/dispatch [:ws/connection [:ws/connect {:url        "wss://api.example.com/ws"
                                           :auth-token (some-token)}]])
```

The `:record-connection-opts` action persists them into `:data`; the `:connecting` state's spawn-spec `:data` fn reads them out and threads them into the child `:websocket/socket` actor. A reconnect (after `:closed` or `:error`) reuses whatever is in `:data` — no need to re-pass the URL on every reconnect attempt.

For the canonical menu of mechanisms — event payload (used here for caller-supplied URL/token), spawn-spec `:data` fn (used between this machine and the child socket actor), and boot-time host config (when the URL is fixed by build-time config and threaded in by the boot machine) — see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

### Subscription protocol

The connection machine tracks subscribed topics in `:data :subscriptions` (a set). On entry to `:connected`, the `:resubscribe` action re-issues subscribe messages for every topic — guaranteeing subscriptions survive reconnects.

To subscribe / unsubscribe at runtime, dispatch a sub/unsub event into the connection machine that updates `:subscriptions` and sends the message:

```clojure
;; Subscribe to a topic — pure :data update + send.
:ws/subscribe
{:action (fn [{:keys [data]} [_ topic]]
           {:data (update data :subscriptions conj topic)
            :fx   [[:dispatch [(:socket-id data) [:send {:type :subscribe :topic topic}]]]]})}
```

The exact subscribe-message wire format is application-specific; the pattern is "track in `:data`, re-issue on `:connected` entry."

### Message correlation for request-reply

Some WebSocket protocols are request-reply with a correlation id. The pattern: track in-flight requests in `:data :in-flight` keyed by id; on `:ws/received`, look up the id and dispatch the registered reply event:

```clojure
:ws/request
{:action (fn [{:keys [data]} [_ {:keys [reply] :as msg}]]
           (let [id (random-uuid)]
             {:data (assoc-in data [:in-flight id] reply)
              :fx   [[:dispatch [(:socket-id data) [:send (assoc msg :id id)]]]]}))}
```

When `:ws/received` fires, look up `(:in-flight data) (:id msg)`, dispatch the reply event, and remove it from `:in-flight`. Each request-reply *over* the open socket is a Pattern-AsyncEffect interaction; the connection machine is the long-lived host.

### Heartbeat / keepalive

Use `:after` on `:connected` to schedule a periodic ping; on receiving a pong, the timer resets via state re-entry (or via a dedicated sub-state with its own `:after`):

```clojure
:connected
{:after {30000 {:target :connected :action :send-ping}}     ;; self-loop external; re-arms timer
 ...}
```

If the pong does not arrive within a window, the parent transitions to `:reconnecting`. A child machine for the heartbeat (invoked from `:connected`) is cleaner for non-trivial cases.

### Server-pushed events

Server pushes (`:ws/received` events that are not request-replies) are translated into named dispatched events the running-app handlers consume. The connection machine's role is mechanical — receive, translate, dispatch. The semantic interpretation lives in the per-feature event handlers.

```clojure
:ws/received
{:action (fn [_ [_ msg]]
           {:fx [[:dispatch [:ws/handle-message msg]]]})}

(rf/reg-event-fx :ws/handle-message
  (fn [_ [_ {:keys [type] :as msg}]]
    (case type
      :note/created {:fx [[:dispatch [:notes/append msg]]]}
      :user/typing  {:fx [[:dispatch [:chat/typing  msg]]]}
      ...)))
```

### Re-authentication on reconnect

Token expiry across reconnects: the `:authenticating` state always runs after `:connecting`, so re-auth is the default. If the token has expired, the auth handshake fails, the machine transitions to `:failed`, and the running app dispatches `[:auth/refresh-token]` followed by `[:ws/connection [:ws/connect]]` once the new token is available.

## SSR

The connection machine **no-ops in SSR mode** — `:invoke`'s spawn fx is `:platforms #{:client}` (the WebSocket API doesn't exist server-side); `:after` timers do not schedule under SSR (per [011 §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)). The server renders the machine's current state (typically `:disconnected`) statically; the client hydrates and starts the connection on its own.

This mirrors the rule for any client-only fx: the `:platforms` metadata gates execution; the server's fx resolver silently no-ops it.

## Anti-patterns

- **Implementing reconnect logic in `setTimeout` from inside the fx-handler.** Bypasses the machine; bypasses tracing; bypasses stale-detection. Use `:after` for the backoff timer.
- **Mutating `app-db` from the `onmessage` callback directly.** The fx-handler must dispatch a named event; the event handler does the write. Same rule as Pattern-AsyncEffect.
- **Per-message machine-spawn-and-destroy.** The connection machine is long-lived. Spawning a new machine per outgoing message is structural overkill — use a single connection machine with `:in-flight` correlation tracking instead.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across message boundaries is state-machine-shaped. Use this pattern.
- **Storing the `WebSocket` object in `app-db`.** The JS `WebSocket` is not a value; it cannot serialise; it cannot survive Tool-Pair epoch replay. The `:websocket/socket` actor owns it via a host-side reference; only its id appears in `:data`.
- **Hardcoding the wire format in the pattern.** EDN, JSON, MessagePack, Protobuf — the connection machine doesn't care. The `:websocket/socket` actor serialises on send and deserialises on receive; the machine sees plain Clojure values.

## Composition with related patterns

- **[Pattern-AsyncEffect](Pattern-AsyncEffect.md)** — distinct but adjacent. Individual request-reply messages over the open socket fit Pattern-AsyncEffect (the open connection acts as the fx); the connection lifecycle itself does not.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — composes for stale replies. When the socket has been replaced, replies from the prior socket epoch are suppressed via the standard epoch idiom — the same mechanism `:after` already uses internally.
- **[Pattern-Boot](Pattern-Boot.md)** — "establish real-time connection" is often a late boot phase; the boot machine's `:routing` or a dedicated `:connecting-realtime` state `:invoke`s the connection machine.
- **`:after` / `:always` / `:invoke` / hierarchical states** ([005](005-StateMachines.md)) — the locked machine substrate. This pattern is the canonical worked example exercising all four together.
- **No Suspense** ([Principles.md](Principles.md)) — connection state is explicit (`:connecting`, `:authenticating`, etc.), not implicit "loading"; views render against the snapshot's `:state`.

## Cross-references

- [005-StateMachines.md](005-StateMachines.md) — the substrate; this pattern is a worked example exercising hierarchical states, `:after`, `:always`, and `:invoke` together.
- [Pattern-AsyncEffect.md](Pattern-AsyncEffect.md) — sibling pattern for one-shot async work.
- [Pattern-StaleDetection.md](Pattern-StaleDetection.md) — epoch idiom; this pattern reuses it for connection-epoch staleness.
- [Pattern-Boot.md](Pattern-Boot.md) — boot may include connection establishment as a phase.
- [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr) — the server-side rule for the connection machine's timers.
