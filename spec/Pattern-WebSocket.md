# Pattern — WebSocket

> **Type:** Pattern
> Long-lived connection lifecycle — WebSocket / SSE / WebRTC peer — modelled as a state machine that owns the socket. Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.
>
> `:rf.ws/*` (WebSocket connections) is a **managed external effect** — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties (effect-as-data, framework-owned socket-actor lifecycle, structured failure taxonomy under `:rf.ws/*`, trace-bus observability, `:sensitive?` / `:large?` composition, built-in retry / abort / teardown via the connection state machine, in-flight socket-actor registry, per-frame interceptor scoping).

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

The canonical states form a hierarchical machine. `:connecting`, `:authenticating`, and `:connected` sit under a single compound parent `:active` because they share one critical invariant: **the live socket actor must outlive all three**. Anchoring the `:spawn` on the parent — not on `:connecting` — keeps the actor alive across the success-path transitions (`:connecting` → `:authenticating` → `:connected`) without re-spawning a fresh socket each time the leaf changes.

| State | Meaning |
|---|---|
| `:disconnected` | No socket; not yet attempted, or destroyed cleanly. |
| `:active` | Compound parent; owns the `:websocket/socket` `:spawn`. Leaves: `:connecting`, `:authenticating`, `:connected`. |
| `:reconnecting` | Connection lost; waiting on `:after` backoff before re-attempt. Socket actor has been destroyed. |
| `:failed` | Max retries exceeded; manual recovery only. Terminal until external `[:ws/connect ...]` dispatched. |

### Standard transitions

```text
:disconnected           --:ws/connect-->         :active / :connecting
:active / :connecting   --:ws/opened-->          :active / :authenticating
:active / :authenticating --:ws/auth-ok-->       :active / :connected
:active / :authenticating --:ws/auth-failed-->   :failed
:active / *             --:ws/closed-->          :reconnecting
:active / *             --:ws/fatal-->           :failed
:reconnecting           --:after backoff-->      :active / :connecting
:reconnecting           --:always max-retries--> :failed
:failed                 --:ws/connect-->         :active / :connecting
```

Per [005 §Transition resolution — deepest-wins with parent fallthrough](005-StateMachines.md#transition-resolution--deepest-wins-with-parent-fallthrough), `:ws/closed` and `:ws/fatal` are declared on `:active` once and inherited by every leaf — every `:connecting`-error, `:authenticating`-error, and `:connected`-error path routes through the same parent-level transition.

The connection machine composes the locked substrate:

- **Hierarchical states** ([005 §Hierarchical compound states](005-StateMachines.md#hierarchical-compound-states)) — `:active` is the parent of three connection-leaves; the parent owns the socket actor.
- **`:after`** ([005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) — exponential backoff timer in `:reconnecting`, expressed as a **fn-form delay** `(fn [snap] ms)` that reads the current `:retries` and `:base-ms` from `:data`. The `:after`-epoch invariant ([005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) guarantees stale timers from prior `:reconnecting` visits are silently dropped on transitions away.
- **`:always`** ([005 §Eventless `:always` transitions](005-StateMachines.md#eventless-always-transitions)) — max-retries guard fires immediately on entry to `:reconnecting` if `:retries` exceeds the limit, transitioning straight to `:failed`. Also used to flush queued messages on entry to `:connected`.
- **Machine-scoped `:guards` / `:actions`** ([005 §Registration — the machine IS the event handler](005-StateMachines.md#registration--the-machine-is-the-event-handler)) — for `:max-retries-exceeded?`, `:has-queued-messages?`, `:bump-retry-count`, `:flush-queue`, `:current-socket?`, etc.
- **`:spawn`** ([005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)) — `:active` invokes a `:websocket/socket` actor that owns the actual `WebSocket` object; the actor's lifetime is bound to the `:active` parent. Any transition that exits `:active` (to `:reconnecting`, to `:failed`, or to `:disconnected`) destroys the actor; re-entering `:active` after `:after` backoff spawns a fresh socket.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — the **connection epoch** is the socket-actor's own gensym'd id. Every event the socket actor dispatches into the parent carries its `:socket-id`; the parent's actions check that the carried id matches the live `(:socket-id data)` before committing. Replies from a previous connection epoch — `:ws/received` from a socket that has since been replaced — fail the check and are dropped via a `:rf.ws/stale-socket` trace. The same idiom that `:after` already uses internally, applied to socket-actor identity.

## Worked example — connection machine

```clojure
(rf/reg-event-fx :ws/connection
  {:doc "WebSocket connection lifecycle: disconnected → active{:connecting →
         :authenticating → :connected} → reconnecting (with backoff) → failed."}
  (rf/create-machine-handler
    {:initial :disconnected
     :data    {:url            nil               ;; supplied by :ws/connect; refreshed by :ws/refresh-token
               :auth-token     nil               ;; supplied by :ws/connect (or refreshed at runtime)
               :retries        0
               :max-retries    8
               :base-ms        1000              ;; initial backoff
               :max-backoff-ms 30000
               :socket-id      nil               ;; address of the currently-live socket actor
               :subscriptions  #{}               ;; topics to (re-)subscribe on :connected entry
               :queue          []                ;; messages buffered while disconnected
               :in-flight      {}                ;; {request-id → {:reply-event ... :timeout-ms ...}}
               :error          nil}

     :guards
     {:max-retries-exceeded?
      (fn [data _]
        (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn [data _]
        (seq (:queue data)))

      :current-socket?
      ;; True iff the incoming socket-stamped event came from the actor
      ;; this machine currently owns. Connection-epoch staleness check
      ;; (Pattern-StaleDetection): replies from a prior socket-id are
      ;; suppressed without disturbing the live snapshot.
      (fn [data [_ {:keys [source-socket-id]}]]
        (= source-socket-id (:socket-id data)))}

     :actions
     {:record-connection-opts
      ;; Caller passes URL + token on :ws/connect; opts land in :data and
      ;; every subsequent reconnect re-reads them via :spawn's :data fn.
      (fn [data [_ {:keys [url auth-token]}]]
        {:data (assoc data :url url :auth-token auth-token)})

      :refresh-token
      ;; The auth machine calls this after an out-of-band refresh; the
      ;; next :active entry's :spawn :data fn picks up the fresh token.
      (fn [data [_ token]]
        {:data (assoc data :auth-token token)})

      :bump-retry (fn [data _] {:data (update data :retries inc)})

      :clear-socket-id
      (fn [data _] {:data (assoc data :socket-id nil)})

      :send-auth
      ;; Route an :auth message into the live socket actor.
      (fn [data _]
        {:fx [[:dispatch [(:socket-id data) [:send {:type  :auth
                                                    :token (:auth-token data)}]]]]})

      :on-connected
      ;; Compound entry action for :connected — :reset-retry + :resubscribe
      ;; in one fn. (Per [005 §State nodes] :entry takes one fn or one
      ;; registered id, never a vector.)
      (fn [data _]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(:socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      ;; Send everything buffered while disconnected; clear the queue.
      (fn [data _]
        {:data (assoc data :queue [])
         :fx   (mapv (fn [msg] [:dispatch [(:socket-id data) [:send msg]]])
                     (:queue data))})

      :enqueue-message
      ;; Buffer a send while the connection is not yet :connected.
      (fn [data [_ msg]]
        {:data (update data :queue conj msg)})

      :register-request
      ;; Caller: [:ws/request {:request-id ..., :body ..., :reply ...}].
      ;; Record the in-flight entry, forward to the socket, schedule a timeout.
      (fn [data [_ {:keys [request-id body reply timeout-ms]
                    :or   {timeout-ms 30000}}]]
        {:data (assoc-in data [:in-flight request-id]
                         {:reply-event reply :timeout-ms timeout-ms})
         :fx   [[:dispatch [(:socket-id data)
                            [:send (assoc body :request-id request-id)]]]
                [:dispatch-later
                 {:ms       timeout-ms
                  :dispatch [:ws/connection
                             [:ws/request-timeout
                              {:request-id       request-id
                               :source-socket-id (:socket-id data)}]]}]]})

      :clear-request
      (fn [data [_ {:keys [request-id]}]]
        {:data (update data :in-flight dissoc request-id)})

      :record-and-reset
      ;; Compound action — record fresh opts AND reset the retry counter.
      ;; Used on manual :ws/connect from :reconnecting / :failed (the
      ;; running app has refreshed the token; reconnect immediately).
      (fn [data [_ {:keys [url auth-token]}]]
        {:data (-> data
                   (assoc :url url :auth-token auth-token)
                   (assoc :retries 0))})

      :record-error
      (fn [data [_ err]] {:data (assoc data :error err)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target [:active]
                         :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}}}

      :active
      {;; The socket actor is invoked at the parent level — its lifetime
       ;; spans :connecting, :authenticating, and :connected. Any transition
       ;; that exits :active (to :reconnecting, :failed, or :disconnected)
       ;; destroys it; re-entering :active spawns a fresh one.
       :spawn {:machine-id :websocket/socket
                ;; Mechanism 2 from Pattern-AsyncEffect §Parameter passing
                ;; across the boundary — the child reads URL + auth-token
                ;; from the parent's :data at spawn time. Every re-entry to
                ;; :active picks up whatever the parent's :data currently
                ;; holds, so a :refresh-token between reconnects flows in
                ;; without any extra wiring.
                :data       (fn [snap _] {:url        (-> snap :data :url)
                                          :auth-token (-> snap :data :auth-token)})
                ;; Record the spawned actor id so subsequent dispatches
                ;; and :current-socket? checks have a value to compare.
                :on-spawn   (fn [data id] (assoc data :socket-id id))}

       ;; Exit cascade — on any transition that leaves :active, clear the
       ;; stale socket-id from :data. The runtime destroys the actor
       ;; automatically (per :spawn's desugared exit, [005 §Desugaring rules]);
       ;; this just keeps the parent's :data tidy so :current-socket?'s
       ;; comparison against `nil` correctly rejects late events.
       :exit  :clear-socket-id

       ;; Parent-level transitions inherited by every leaf
       ;; (per [005 §Transition resolution]). Any transport-level error
       ;; or close during :connecting / :authenticating / :connected
       ;; routes through one of these.
       :on    {:ws/closed   {:target :reconnecting
                             :action :bump-retry}
               :ws/error    {:target :reconnecting
                             :action :bump-retry}
               :ws/fatal    {:target :failed
                             :action :record-error}
               :ws/send     {:action :enqueue-message}
               :ws/refresh-token {:action :refresh-token}
               ;; A request issued before the connection is :connected is
               ;; queued like any other send (the request-id is preserved
               ;; in the queued body); the active leaf overrides for the
               ;; :connected case below.
               :ws/request  {:action :enqueue-message}}

       :initial :connecting

       :states
       {:connecting
        {:on {:ws/opened {:target :authenticating}}}

        :authenticating
        {:entry :send-auth
         :on    {:ws/auth-ok     {:target :connected}
                 :ws/auth-failed {:target [:failed]
                                  :action :record-error}}}

        :connected
        {:entry  :on-connected
         :always [{:guard :has-queued-messages? :action :flush-queue}]
         :on     {;; Pushed server event with no correlation id — forward.
                  ;; The :current-socket? guard suppresses messages dispatched
                  ;; in-flight from a prior socket whose destroy hadn't
                  ;; flushed by the time the dispatch landed.
                  :ws/received {:guard  :current-socket?
                                :action (fn [data [_ {:keys [body] :as ev}]]
                                          (if-let [rid (:request-id body)]
                                            ;; Correlated reply — look up
                                            ;; the in-flight entry and
                                            ;; dispatch the registered
                                            ;; reply event; clear the slot.
                                            (let [{:keys [reply-event]}
                                                  (get-in data [:in-flight rid])]
                                              {:data (update data :in-flight dissoc rid)
                                               :fx   (when reply-event
                                                       [[:dispatch (conj reply-event body)]])})
                                            ;; Server push — translate to a
                                            ;; named running-app event.
                                            {:fx [[:dispatch [:ws/handle-message body]]]}))}

                  ;; Override the parent's :ws/send: while :connected the
                  ;; message goes straight to the wire instead of queueing.
                  :ws/send    {:action (fn [data [_ msg]]
                                         {:fx [[:dispatch [(:socket-id data)
                                                           [:send msg]]]]})}

                  ;; Override the parent's :ws/request: while :connected
                  ;; the request is registered + sent immediately.
                  :ws/request {:action :register-request}

                  :ws/request-timeout
                  {:guard  :current-socket?
                   :action :clear-request}}}}}

      :reconnecting
      {:always [{:guard :max-retries-exceeded? :target :failed}]
       ;; Exponential backoff, computed at state entry from the current
       ;; retry count. Per [005 §Value shape] the fn-form delay is called
       ;; once at entry against the entering snapshot; the :after epoch
       ;; carries through the synthetic timer event so a transition out
       ;; of :reconnecting (e.g., a manual :ws/connect) makes the in-flight
       ;; backoff timer stale. Add a jitter term in production.
       :after  {(fn [snap]
                  (let [{:keys [retries base-ms max-backoff-ms]} (:data snap)]
                    (min (* base-ms (Math/pow 2 retries))
                         max-backoff-ms)))
                {:target [:active]}}
       :on     {;; Manual reconnect (e.g., after the auth machine refreshes
                ;; the token) — short-circuit the backoff, record fresh opts,
                ;; zero the retry counter, re-enter :active.
                :ws/connect {:target [:active]
                             :action :record-and-reset}
                :ws/send    {:action :enqueue-message}
                :ws/request {:action :enqueue-message}
                :ws/refresh-token {:action :refresh-token}}}

      :failed
      {:on {:ws/connect       {:target [:active]
                               :action :record-and-reset}
            :ws/refresh-token {:action :refresh-token}}}}}))
```

The `:websocket/socket` invoked actor is itself a small machine (or fx-backed event handler) that owns the JS `WebSocket` instance and translates `:open`, `:message`, `:error`, `:close` events into dispatches back to the parent connection machine. Every outgoing dispatch carries `:source-socket-id` (the actor's `:rf/self-id`, per [005 §Runtime stamps on the spawned actor's `:data`](005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data-rf2-ijm7)) so the parent's `:current-socket?` guard can suppress messages from a prior socket if one happens to dispatch in flight as the cascade tears it down. The actor's lifetime is bound to `:active` — leaving `:active` (whether to `:reconnecting` on error or `:failed` fatally) destroys it; re-entering `:active` creates a fresh socket.

### Parameters

The connection's `:url` and `:auth-token` arrive on the `:ws/connect` event:

```clojure
(rf/dispatch [:ws/connection [:ws/connect {:url        "wss://api.example.com/ws"
                                           :auth-token (some-token)}]])
```

`:record-connection-opts` persists them into `:data`; the `:active` state's `:spawn` `:data` fn reads them out at spawn time and threads them into the child `:websocket/socket` actor. **Every reconnect re-reads `:data` at the new `:active` entry**, so a refreshed token (via `[:ws/connection [:ws/refresh-token new-token]]`) automatically flows into the next socket without re-dispatching `:ws/connect`. A full re-target (different URL) is a fresh `:ws/connect` that records the new opts and forces an `:active` re-entry.

For the canonical menu of mechanisms — event payload (used here for caller-supplied URL/token), spawn-spec `:data` fn (used between this machine and the child socket actor), and boot-time host config (when the URL is fixed by build-time config and threaded in by the boot machine) — see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

### Subscription protocol

The connection machine tracks subscribed topics in `:data :subscriptions` (a set). On entry to `:connected`, the `:resubscribe` action re-issues subscribe messages for every topic — guaranteeing subscriptions survive reconnects.

To subscribe / unsubscribe at runtime, the running app dispatches sub/unsub events the connection machine handles by updating `:subscriptions` and forwarding the wire-message:

```clojure
;; Subscribe to a topic — pure :data update + send.
:ws/subscribe
{:action (fn [data [_ topic]]
           {:data (update data :subscriptions conj topic)
            :fx   [[:dispatch [(:socket-id data) [:send {:type :subscribe :topic topic}]]]]})}
```

(Wire the slot into `:connected`'s `:on` map alongside `:ws/received` and `:ws/send`.) The exact subscribe-message wire format is application-specific; the pattern is "track in `:data`, re-issue on `:connected` entry."

### Message correlation for request-reply

Request-reply protocols carry a correlation id on every request and matching reply. The pattern, fully implemented in the worked example above:

1. **Caller dispatches `[:ws/connection [:ws/request {:request-id ..., :body ..., :reply [::handler ...], :timeout-ms 10000}]]`.**
2. **`:register-request` action** records the in-flight entry — `(:in-flight data)` gains `{request-id {:reply-event ... :timeout-ms ...}}` — forwards the body (with `:request-id` stamped) to the socket actor, and schedules a `:dispatch-later` for the timeout.
3. **`:ws/received` arrives with `{:body {:request-id ... :result ...}}`.** The `:connected` state's handler checks the connection-epoch guard (`:current-socket?`) and then branches: a body carrying `:request-id` looks up the in-flight entry and dispatches the registered reply event; a body without `:request-id` is a server push and routes to `:ws/handle-message`. Either branch clears the in-flight slot when correlated.
4. **`:ws/request-timeout` fires** if no reply arrives within the timeout window. The `:clear-request` action removes the in-flight entry; the caller's reply event never fires. (Apps that want to surface "request timed out" to the caller can do so by dispatching a per-feature error event from `:clear-request` instead.)

The correlation id can be any `=`-comparable value — a `(random-uuid)` is the canonical default, but per-feature `[:feature/load slug]` vectors compose with [Spec 014 §`:request-id` (internal)](014-HTTPRequests.md#request-id-internal)'s precedent. Each request-reply *over* the open socket is a Pattern-AsyncEffect interaction; the connection machine is the long-lived host that performs the correlation step Pattern-AsyncEffect leaves to the caller.

### Heartbeat / keepalive

Use `:after` on `:connected` to schedule a periodic ping: `:after {30000 {:target :connected :action :send-ping}}` self-loops externally, re-arming the timer. If the pong does not arrive within a window, transition to `:reconnecting`. A child heartbeat machine invoked from `:connected` is cleaner for non-trivial cases.

### Server-pushed events

Server pushes (`:ws/received` events with no `:request-id`) are translated into named dispatched events the running-app handlers consume. The connection machine's role is mechanical — receive, validate the socket-id, translate, dispatch. The semantic interpretation lives in the per-feature event handlers:

```clojure
(rf/reg-event-fx :ws/handle-message
  (fn [_ [_ {:keys [type] :as msg}]]
    (case type
      :note/created {:fx [[:dispatch [:notes/append msg]]]}
      :user/typing  {:fx [[:dispatch [:chat/typing  msg]]]}
      ...)))
```

### Re-authentication on reconnect

Token expiry across reconnects has two recovery paths, both supported by the worked machine. **Proactive**: the auth machine refreshes the token and dispatches `[:ws/connection [:ws/refresh-token new-token]]`; the `:refresh-token` action updates `:data :auth-token`; the next `:active` entry's `:spawn` `:data` fn picks up the fresh value. **Reactive**: a reconnect into `:authenticating` fails with `:ws/auth-failed` and the machine transitions to `:failed`; the auth machine observes via `sub-machine` (per [005 §Subscribing to machines via `sub-machine`](005-StateMachines.md#subscribing-to-machines-via-sub-machine)), runs its refresh, and dispatches `[:ws/connection [:ws/connect {:url ... :auth-token new-token}]]` to re-target. Either way, refreshed credentials land in `:data` and the next `:active` entry threads them through `:spawn` `:data`.

## SSR

The connection machine **no-ops in SSR mode** — `:spawn`'s spawn fx is `:platforms #{:client}` (the WebSocket API doesn't exist server-side); `:after` timers do not schedule under SSR (per [011 §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)). The server renders the machine's current state (typically `:disconnected`) statically; the client hydrates and starts the connection on its own.

This mirrors the rule for any client-only fx: the `:platforms` metadata gates execution; the server's fx resolver silently no-ops it.

## Anti-patterns

- **Implementing reconnect logic in `setTimeout` from inside the fx-handler.** Bypasses the machine; bypasses tracing; bypasses stale-detection. Use `:after` for the backoff timer.
- **Mutating `app-db` from the `onmessage` callback directly.** The fx-handler must dispatch a named event; the event handler does the write. Same rule as Pattern-AsyncEffect.
- **Per-message machine-spawn-and-destroy.** The connection machine is long-lived. Spawning a new machine per outgoing message is structural overkill — use a single connection machine with `:in-flight` correlation tracking instead.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across message boundaries is state-machine-shaped. Use this pattern.
- **Storing the `WebSocket` object in `app-db`.** The JS `WebSocket` is not a value; it cannot serialise; it cannot survive Tool-Pair epoch replay. The `:websocket/socket` actor owns it via a host-side reference; only its id appears in `:data`.
- **Anchoring the `:spawn` on `:connecting` instead of the `:active` parent.** A socket actor scoped to `:connecting` is destroyed the moment the leaf transitions to `:authenticating` — every dispatch from `:authenticating` and `:connected` then addresses a dead actor. The actor's lifetime must outlive every leaf that dispatches through it; the hierarchical parent is the natural anchor.
- **Forgetting to re-thread connection opts on reconnect.** Recording `:url` and `:auth-token` only in `:disconnected`'s `:ws/connect` handler — and never refreshing them on the `:reconnecting` → `:active` path — means a token expiry mid-session can never recover. Either store opts in `:data` (where the `:spawn` `:data` fn re-reads them on every `:active` entry — the worked example's approach) or provide an explicit `:ws/refresh-token` slot at the parent level.
- **Skipping the connection-epoch check on `:ws/received`.** Without `:current-socket?` (or equivalent), a slow `:message` event from a torn-down socket can land in `:connected` after a reconnect and be processed against the new connection's `:in-flight` map — at best a wrong-reply dispatch; at worst an in-flight slot cleared by a stale correlation id. The check is one line; skipping it is the websocket equivalent of [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)'s nav-token bug.
- **Hardcoding the wire format in the pattern.** EDN, JSON, MessagePack, Protobuf — the connection machine doesn't care. The `:websocket/socket` actor serialises on send and deserialises on receive; the machine sees plain Clojure values.

## Composition with related patterns

- **[Pattern-AsyncEffect](Pattern-AsyncEffect.md)** — distinct but adjacent. Individual request-reply messages over the open socket fit Pattern-AsyncEffect (the open connection acts as the fx); the connection lifecycle itself does not. The request-reply correlation step the connection machine performs is what Pattern-AsyncEffect leaves to the caller — Pattern-WebSocket's `:in-flight` map is the worked example of that step.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — composes twice. First for the `:after` backoff timer (the runtime's built-in epoch handles it). Second for the connection-epoch: the live socket-id IS the epoch; `:current-socket?` is the guard; `:rf.ws/stale-socket` is the trace.
- **[Pattern-Boot](Pattern-Boot.md)** — "establish real-time connection" is often a late boot phase; the boot machine's `:routing` or a dedicated `:connecting-realtime` state dispatches `[:ws/connection [:ws/connect ...]]` to kick the connection machine into `:active`.
- **`:after` / `:always` / `:spawn` / hierarchical states** ([005](005-StateMachines.md)) — the locked machine substrate. This pattern is the canonical worked example exercising all four together.
- **No Suspense** ([Principles.md](Principles.md)) — connection state is explicit (`:disconnected`, `:active / :connecting`, `:active / :connected`, `:reconnecting`, `:failed`), not implicit "loading"; views render against the snapshot's `:state`.

## Cross-references

- [005-StateMachines.md](005-StateMachines.md) — the substrate; this pattern is a worked example exercising hierarchical states, `:after`, `:always`, and `:spawn` together.
- [Pattern-AsyncEffect.md](Pattern-AsyncEffect.md) — sibling pattern for one-shot async work.
- [Pattern-StaleDetection.md](Pattern-StaleDetection.md) — epoch idiom; this pattern reuses it twice (backoff timer + connection epoch).
- [Pattern-Boot.md](Pattern-Boot.md) — boot may include connection establishment as a phase.
- [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr) — the server-side rule for the connection machine's timers.
