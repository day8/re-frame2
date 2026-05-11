# Pattern — WebSocket

Long-lived bidirectional connection lifecycle (WebSocket / SSE / WebRTC peer) modelled as a state machine that owns the socket actor.

> **Status of the worked example:** `examples/reagent/websocket/` is in flight via rf2-yf97. Until it lands, the canonical declaration below is the source of truth; this leaf will be upgraded to link the example after merge.

## When to use this pattern

Reach for it when the connection itself has phases (`:connecting` → `:authenticating` → `:connected` → `:reconnecting` → `:failed`), survives across message boundaries, retries with backoff, manages subscriptions across reconnects, or carries correlation ids for request-reply messages over the open socket.

Do **not** reach for it when the interaction is one request, one reply. That is `Pattern-AsyncEffect` — even when the wire is a single short-lived WebSocket. The discriminator is "does the connection outlive any one message?".

Server-Sent Events (`EventSource`) and WebRTC peer connections share the same lifecycle shape — same pattern, different wire format inside the actor.

## The re-frame2 features this pattern uses

| Feature | Role here |
|---|---|
| Hierarchical compound state | `:active` is the parent of `:connecting` / `:authenticating` / `:connected`; **the socket actor's lifetime is anchored on the parent**, so it outlives leaf transitions. |
| `:invoke` (declarative spawn) | The `:active` parent invokes a `:websocket/socket` child actor that owns the JS `WebSocket` object. Exiting `:active` destroys it; re-entering spawns a fresh one. |
| `:after` (fn-form delay) | Exponential backoff timer in `:reconnecting`, computed at entry from the current `:retries` and `:base-ms` in `:data`. |
| `:always` | Max-retries guard on `:reconnecting` entry; queue-flush guard on `:connected` entry. |
| Parent-level `:on` | `:ws/closed`, `:ws/fatal`, `:ws/send`, `:ws/refresh-token` declared once on `:active` and inherited by every leaf (deepest-wins resolution). |
| `:guards` / `:actions` (machine-scoped) | `:max-retries-exceeded?`, `:current-socket?` for connection-epoch staleness, `:bump-retry`, `:flush-queue`, etc. |
| Connection-epoch staleness check | The live socket-actor's `:rf/self-id` is the epoch. Replies carry `:source-socket-id`; the `:current-socket?` guard rejects events from a torn-down prior socket. |

## Canonical declaration

```clojure
(rf/reg-event-fx :ws/connection
  (rf/create-machine-handler
    {:initial :disconnected
     :data    {:url nil :auth-token nil
               :retries 0 :max-retries 8
               :base-ms 1000 :max-backoff-ms 30000
               :socket-id nil
               :subscriptions #{}
               :queue [] :in-flight {} :error nil}

     :guards
     {:max-retries-exceeded?
      (fn [data _] (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn [data _] (seq (:queue data)))

      :current-socket?
      ;; Connection-epoch check: reject events from a prior socket actor
      ;; that may dispatch in flight while the cascade tears it down.
      (fn [data [_ {:keys [source-socket-id]}]]
        (= source-socket-id (:socket-id data)))}

     :actions
     {:record-connection-opts
      (fn [data [_ {:keys [url auth-token]}]]
        {:data (assoc data :url url :auth-token auth-token)})

      :refresh-token
      (fn [data [_ token]] {:data (assoc data :auth-token token)})

      :bump-retry      (fn [data _] {:data (update data :retries inc)})
      :clear-socket-id (fn [data _] {:data (assoc data :socket-id nil)})

      :on-connected
      ;; Compound :entry — reset retry counter AND re-subscribe.
      ;; Per Spec 005 §State nodes, :entry takes one fn or one registered
      ;; id, never a vector. Consolidate into one action.
      (fn [data _]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(:socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      (fn [data _]
        {:data (assoc data :queue [])
         :fx   (mapv (fn [msg] [:dispatch [(:socket-id data) [:send msg]]])
                     (:queue data))})

      :enqueue-message
      (fn [data [_ msg]] {:data (update data :queue conj msg)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target [:active] :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}}}

      :active
      {;; Socket actor anchored on the PARENT, not on :connecting.
       ;; Lifetime spans :connecting → :authenticating → :connected.
       :invoke  {:machine-id :websocket/socket
                 :data       (fn [snap _] {:url        (-> snap :data :url)
                                           :auth-token (-> snap :data :auth-token)})
                 :on-spawn   (fn [data id] (assoc data :socket-id id))}
       :exit    :clear-socket-id

       ;; Parent-level transitions inherited by every leaf.
       :on      {:ws/closed       {:target :reconnecting :action :bump-retry}
                 :ws/fatal        {:target :failed}
                 :ws/send         {:action :enqueue-message}
                 :ws/refresh-token {:action :refresh-token}}

       :initial :connecting
       :states
       {:connecting     {:on {:ws/opened {:target :authenticating}}}
        :authenticating {:entry :send-auth
                         :on    {:ws/auth-ok     {:target :connected}
                                 :ws/auth-failed {:target [:failed]}}}
        :connected      {:entry  :on-connected
                         :always [{:guard :has-queued-messages? :action :flush-queue}]
                         :on     {:ws/received {:guard  :current-socket?
                                                :action :route-message}
                                  :ws/send     {:action :send-now}}}}}

      :reconnecting
      {:always [{:guard :max-retries-exceeded? :target :failed}]
       ;; fn-form delay — re-evaluated at every :reconnecting entry against
       ;; the entering snapshot. The :after epoch makes stale timers from
       ;; prior visits silently no-op on transition out.
       :after  {(fn [snap]
                  (let [{:keys [retries base-ms max-backoff-ms]} (:data snap)]
                    (min (* base-ms (Math/pow 2 retries))
                         max-backoff-ms)))
                {:target [:active]}}
       :on     {:ws/connect       {:target [:active] :action :record-connection-opts}
                :ws/refresh-token {:action :refresh-token}
                :ws/send          {:action :enqueue-message}}}

      :failed
      {:on {:ws/connect       {:target [:active] :action :record-connection-opts}
            :ws/refresh-token {:action :refresh-token}}}}}))
```

Caller-side:

```clojure
(rf/dispatch [:ws/connection [:ws/connect {:url        "wss://api.example.com/ws"
                                           :auth-token (some-token)}]])
```

## Variations

**Request-reply correlation over the open socket.** Each request gets a `(:request-id (random-uuid))` stamped into the body; an `:in-flight` map in `:data` holds `{request-id {:reply-event ... :timeout-ms ...}}`. The `:connected` `:ws/received` handler branches: body with `:request-id` → look up the in-flight slot, dispatch its `:reply-event`, clear the slot; body without → server push, dispatch `[:ws/handle-message body]`. A `:dispatch-later` per request handles the timeout. Each request-over-socket is a `Pattern-AsyncEffect` interaction; the connection machine performs the correlation step Pattern-AsyncEffect leaves to the caller.

**Heartbeat / keepalive.** `:after` on `:connected` re-arms a periodic ping (`:after {30000 {:target :connected :action :send-ping}}` self-loops, re-arming the timer). A missed pong transitions to `:reconnecting`. Non-trivial cases use a child heartbeat machine `:invoke`d from `:connected`.

**Subscription protocol.** Topics live in `:data :subscriptions` (a set). `:on-connected` re-issues subscribe messages on entry — subscriptions survive reconnects automatically. Runtime sub/unsub events update `:subscriptions` and forward the wire-message in one action.

**Re-authentication on reconnect.** Two paths, both supported by the canonical machine. *Proactive*: auth machine dispatches `[:ws/connection [:ws/refresh-token tok]]`; `:refresh-token` updates `:data`; the next `:active` entry's `:invoke :data` fn picks up the fresh value automatically — no extra wiring. *Reactive*: a reconnect into `:authenticating` fails with `:ws/auth-failed`, lands in `:failed`; the auth machine observes via `sub-machine`, refreshes, and dispatches a fresh `:ws/connect` with new opts.

**SSR.** The connection machine no-ops server-side: `:invoke`'s spawn fx is `:platforms #{:client}`, `:after` timers don't schedule under SSR. The server renders `:disconnected` statically; the client hydrates and starts the connection.

## Anti-patterns

- **Anchoring `:invoke` on `:connecting` instead of `:active`.** Destroys the socket the moment the leaf transitions to `:authenticating`. Lifetime MUST span all three connection leaves.
- **Storing the `WebSocket` JS object in `app-db`.** Not a value, not serialisable, won't survive snapshot replay. The actor owns it host-side; only the actor id appears in `:data`.
- **Implementing reconnect via `setTimeout` from inside the fx-handler.** Bypasses the machine, tracing, and stale-detection. Use `:after`.
- **Skipping `:current-socket?` on `:ws/received`.** A slow `:message` from a torn-down prior socket lands in the new connection's `:in-flight` map — wrong-reply at best, slot-clearing-by-stale-id at worst. The guard is one line; skipping it is the websocket equivalent of a missing routing nav-token.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across message boundaries is state-machine-shaped, not request-reply-shaped.

## Worked example

`examples/reagent/websocket/` (pending — in flight via rf2-yf97). Until merged, treat the canonical declaration above as the source of truth. The follow-on EX-1 bead upgrades this leaf to link the example once shipped.

## Pointers

- Full pattern doc, including request-reply correlation worked example, heartbeat variations, anti-patterns, and SSR composition → SKILL-REDIRECT.md → *Pattern — WebSocket*.
- The state-machine substrate (`:invoke`, `:after`, `:always`, hierarchical states) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- Connection-epoch idiom (the second use of stale-detection in this pattern) → SKILL-REDIRECT.md → *Pattern — Stale detection*.
