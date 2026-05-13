# Pattern — WebSocket

Long-lived bidirectional connection lifecycle (WebSocket / SSE / WebRTC peer) modelled as a state machine that owns the socket actor.

> **Status of the worked example:** `examples/reagent/websocket/` is in flight via rf2-yf97. Until it lands, the canonical declaration below is the source of truth.

## When to use this pattern

Reach for it when the connection itself has phases (`:connecting` → `:authenticating` → `:connected` → `:reconnecting` → `:failed`), survives across message boundaries, retries with backoff, manages subscriptions across reconnects, or carries correlation ids for request-reply messages over the open socket.

Do **not** reach for it when the interaction is one request, one reply — that is `Pattern-AsyncEffect`, even when the wire is a short-lived WebSocket. The discriminator: "does the connection outlive any one message?".

SSE (`EventSource`) and WebRTC peer connections share the same lifecycle shape — same pattern, different wire format inside the actor.

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| Hierarchical compound state | `:active` parents `:connecting` / `:authenticating` / `:connected`; **the socket actor's lifetime is anchored on the parent**, so it outlives leaf transitions. |
| `:invoke` (declarative spawn) | `:active` invokes a `:websocket/socket` child owning the JS `WebSocket`. Exiting `:active` destroys it; re-entering spawns a fresh one. |
| `:after` (fn-form delay) | Exponential backoff timer in `:reconnecting`, computed at entry from `:retries` and `:base-ms`. |
| `:always` | Max-retries guard on `:reconnecting` entry; queue-flush guard on `:connected` entry. |
| Parent-level `:on` | `:ws/closed`, `:ws/fatal`, `:ws/send`, `:ws/refresh-token` declared once on `:active`, inherited by every leaf. |
| Connection-epoch staleness check | Live socket-actor's `:rf/self-id` is the epoch. Replies carry `:source-socket-id`; `:current-socket?` guard rejects events from a torn-down prior socket. |

## Canonical declaration

```clojure
(rf/reg-event-fx :ws/connection
  (rf/create-machine-handler
    {:initial :disconnected
     :data    {:url nil :auth-token nil :retries 0 :max-retries 8
               :base-ms 1000 :max-backoff-ms 30000
               :socket-id nil :subscriptions #{}
               :queue [] :in-flight {} :error nil}

     :guards
     {:max-retries-exceeded? (fn [data _] (>= (:retries data) (:max-retries data)))
      :has-queued-messages?  (fn [data _] (seq (:queue data)))
      :current-socket?
      ;; Connection-epoch check: reject events from a prior socket actor
      ;; that may dispatch in flight while the cascade tears it down.
      (fn [data [_ {:keys [source-socket-id]}]]
        (= source-socket-id (:socket-id data)))}

     :actions
     {:record-connection-opts (fn [data [_ {:keys [url auth-token]}]]
                                {:data (assoc data :url url :auth-token auth-token)})
      :refresh-token   (fn [data [_ token]] {:data (assoc data :auth-token token)})
      :bump-retry      (fn [data _] {:data (update data :retries inc)})
      :clear-socket-id (fn [data _] {:data (assoc data :socket-id nil)})
      :on-connected
      ;; :entry takes one fn / id, never a vector — consolidate.
      (fn [data _]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [t] [:dispatch [(:socket-id data) [:send {:type :subscribe :topic t}]]])
                     (:subscriptions data))})
      :flush-queue (fn [data _] {:data (assoc data :queue [])
                                 :fx (mapv (fn [m] [:dispatch [(:socket-id data) [:send m]]])
                                           (:queue data))})
      :enqueue-message (fn [data [_ m]] {:data (update data :queue conj m)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target [:active] :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}}}

      :active
      {;; Socket actor anchored on the PARENT — lifetime spans all three leaves.
       :invoke  {:machine-id :websocket/socket
                 :data       (fn [snap _] {:url        (-> snap :data :url)
                                           :auth-token (-> snap :data :auth-token)})
                 :on-spawn   (fn [data id] (assoc data :socket-id id))}
       :exit    :clear-socket-id
       :on      {:ws/closed        {:target :reconnecting :action :bump-retry}
                 :ws/fatal         {:target :failed}
                 :ws/send          {:action :enqueue-message}
                 :ws/refresh-token {:action :refresh-token}}
       :initial :connecting
       :states
       {:connecting     {:on {:ws/opened {:target :authenticating}}}
        :authenticating {:entry :send-auth
                         :on    {:ws/auth-ok     {:target :connected}
                                 :ws/auth-failed {:target [:failed]}}}
        :connected      {:entry  :on-connected
                         :always [{:guard :has-queued-messages? :action :flush-queue}]
                         :on     {:ws/received {:guard :current-socket? :action :route-message}
                                  :ws/send     {:action :send-now}}}}}

      :reconnecting
      {:always [{:guard :max-retries-exceeded? :target :failed}]
       ;; fn-form delay — re-evaluated each :reconnecting entry against the entering snapshot.
       :after  {(fn [snap] (let [{:keys [retries base-ms max-backoff-ms]} (:data snap)]
                             (min (* base-ms (Math/pow 2 retries)) max-backoff-ms)))
                {:target [:active]}}
       :on     {:ws/connect       {:target [:active] :action :record-connection-opts}
                :ws/refresh-token {:action :refresh-token}
                :ws/send          {:action :enqueue-message}}}

      :failed
      {:on {:ws/connect       {:target [:active] :action :record-connection-opts}
            :ws/refresh-token {:action :refresh-token}}}}}))
```

Caller: `(rf/dispatch [:ws/connection [:ws/connect {:url "wss://api.example.com/ws" :auth-token (some-token)}]])`.

## Variations

**Request-reply correlation over the open socket.** Each request gets a `(:request-id (random-uuid))` stamped in. `:in-flight` map in `:data` holds `{request-id {:reply-event ... :timeout-ms ...}}`. The `:connected` `:ws/received` handler branches on `:request-id`. A `:dispatch-later` per request handles timeout. Each request is a Pattern-AsyncEffect interaction; the connection machine performs correlation.

**Heartbeat / keepalive.** `:after` on `:connected` re-arms a periodic ping; a missed pong transitions to `:reconnecting`. Non-trivial cases use a child heartbeat machine.

**Subscription protocol.** Topics live in `:data :subscriptions`. `:on-connected` re-issues subscribes on entry — subscriptions survive reconnects automatically.

**Re-authentication on reconnect.** *Proactive*: auth machine dispatches `[:ws/connection [:ws/refresh-token tok]]`; next `:active` entry's `:invoke :data` fn picks up the fresh value. *Reactive*: reconnect into `:authenticating` fails with `:ws/auth-failed`, lands in `:failed`; auth machine observes via `sub-machine`, refreshes, dispatches fresh `:ws/connect`.

**SSR.** No-ops server-side: `:invoke` spawn fx is `:platforms #{:client}`; `:after` timers don't schedule under SSR.

**Final-state termination (`:final?` / `:on-done`).** Restricted to the *child* role. When the WS machine is `:invoke`'d by an outer session machine, mark a terminal-failed branch (e.g. `:permanently-failed`, distinct from recoverable `:failed`) `:final? true` — parent receives clean unrecoverable signal via `:on-done` with optional `:output-key`. Child heartbeat / handshake machines reaching `:expired` / `:handshake-failed` can similarly use `:final?` instead of dispatching custom outbound events. The top-level connection machine itself stays recoverable. See `../reference/state-machines/invoke.md` §Final states.

## Anti-patterns

- **Anchoring `:invoke` on `:connecting` instead of `:active`.** Destroys the socket on transition to `:authenticating`. Lifetime MUST span all three leaves.
- **Storing the `WebSocket` JS object in `app-db`.** Not a value, not serialisable, won't survive snapshot replay. Actor owns it host-side; only the actor id appears in `:data`.
- **Reconnect via `setTimeout` from inside fx-handler.** Bypasses the machine, tracing, stale-detection. Use `:after`.
- **Skipping `:current-socket?` on `:ws/received`.** A slow `:message` from a torn-down socket lands in the new connection's `:in-flight` — wrong-reply at best.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across messages is state-machine-shaped.

## Worked example

`examples/reagent/websocket/` (pending — in flight via rf2-yf97). Until merged, treat the canonical declaration above as the source of truth.

## Pointers

- Full pattern doc, request-reply correlation worked example, SSR composition → SKILL-REDIRECT.md → *Pattern — WebSocket*.
- State-machine substrate (`:invoke`, `:after`, `:always`, hierarchical) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- `:final?` / `:on-done` / `:output-key` → `../reference/state-machines/invoke.md` §Final states.
- Connection-epoch idiom → SKILL-REDIRECT.md → *Pattern — Stale detection*.

---

*Derived from Pattern-WebSocket @ main `89bd9c3`. The worked example `examples/reagent/websocket/` is in flight via rf2-yf97; re-verify and link once it merges.*
