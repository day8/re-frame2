# Pattern — WebSocket

Long-lived bidirectional connection lifecycle (WebSocket / SSE / WebRTC peer) modelled as a state machine that owns the socket actor.

`:rf.ws/*` is one instance of the **managed external effect** umbrella — alongside `:rf.http/managed`, state-machine `:invoke`, `:rf.server/*`, and `:rf.flow/*`. The connection's lifecycle (issuance, reconnect, abort, teardown, structured failures under `:rf.ws/*`, trace-bus observability, wire-value elision) is framework-owned. See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) for the eight-property shared contract; the rest of this leaf is WebSocket-specific.

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

## Credential discipline (load-bearing — read before the snippet)

Bearer tokens, cookies, refresh tokens, and similar credentials **must never live in machine `:data`**. Machine state is framework-inspectable (app-db snapshots, trace emissions, recorder fixtures, pair tooling), so anything in `:data` is liable to be serialised into a place the dev never inspects character-by-character. The canonical declaration below holds **only a credential reference** (`:cred-ref`) in `:data` — an opaque key that the host-side socket actor exchanges for the real bearer at spawn time via a client-only cofx (`:rf.cred/fetch`, registered with `:platforms #{:client}` so SSR never sees it). The actor uses the resolved bearer inside its own JS context and discards it; the bearer never re-enters the dispatch stream.

For events that genuinely must carry a secret across the dispatch boundary (e.g. `:ws/refresh-token` propagating a freshly minted bearer), use the privacy primitives in [`../reference/cross-cutting/privacy-and-elision.md`](../reference/cross-cutting/privacy-and-elision.md): mark the handler `:sensitive? true` and scrub the payload with `(rf/with-redacted ...)`. That gates the trace, recorder, and listener fan-out at one normative seam.

The pattern below uses `:cred-ref` as the placeholder; substitute whatever opaque key your auth slice already issues (a UUID, a `(random-uuid)` index into a host-side credential vault, a session id, etc.). The crucial property: the value in `:data` is **not** the bearer itself.

## Canonical declaration

```clojure
(rf/reg-event-fx :ws/connection
  (rf/create-machine-handler
    {:initial :disconnected
     ;; NOTE :cred-ref is an opaque pointer; the bearer is fetched
     ;; client-side at actor spawn via the :rf.cred/fetch cofx.
     :data    {:url nil :cred-ref nil :retries 0 :max-retries 8
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
     {:record-connection-opts (fn [data [_ {:keys [url cred-ref]}]]
                                {:data (assoc data :url url :cred-ref cred-ref)})
      :rotate-cred     (fn [data [_ new-cred-ref]]
                         {:data (assoc data :cred-ref new-cred-ref)})
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
       ;; The actor receives only :url + :cred-ref; it resolves the bearer
       ;; via a client-only cofx inside its own JS context, then opens the
       ;; socket. The bearer never re-enters dispatch.
       :invoke  {:machine-id :websocket/socket
                 :data       (fn [snap _] {:url      (-> snap :data :url)
                                           :cred-ref (-> snap :data :cred-ref)})
                 :on-spawn   (fn [data id] (assoc data :socket-id id))}
       :exit    :clear-socket-id
       :on      {:ws/closed        {:target :reconnecting :action :bump-retry}
                 :ws/fatal         {:target :failed}
                 :ws/send          {:action :enqueue-message}
                 :ws/rotate-cred   {:action :rotate-cred}}
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
       :on     {:ws/connect     {:target [:active] :action :record-connection-opts}
                :ws/rotate-cred {:action :rotate-cred}
                :ws/send        {:action :enqueue-message}}}

      :failed
      {:on {:ws/connect     {:target [:active] :action :record-connection-opts}
            :ws/rotate-cred {:action :rotate-cred}}}}}))
```

Caller: `(rf/dispatch [:ws/connection [:ws/connect {:url "wss://api.example.com/ws" :cred-ref (current-session-cred-ref)}]])` — `current-session-cred-ref` returns an opaque pointer into the host-side credential vault. The bearer itself never crosses the dispatch boundary; the actor's `:rf.cred/fetch` cofx resolves the pointer to a bearer at spawn time.

If the credential genuinely must move via dispatch (e.g. an out-of-band rotation event), mark the event handler `:sensitive? true` and scrub the payload — see [`../reference/cross-cutting/privacy-and-elision.md`](../reference/cross-cutting/privacy-and-elision.md):

```clojure
(rf/reg-event-fx :ws/rotate-cred-from-bearer
  {:sensitive? true}
  [(rf/with-redacted [[:bearer]])]
  (fn [{:keys [db]} [_ {:keys [bearer]}]]
    ;; Bearer is :rf/redacted in every trace / recorder / listener emit;
    ;; the handler body sees the real value via the unredacted :event coeffect.
    {:fx [[:rf.cred/store {:bearer bearer :on-stored [:ws/connection [:ws/rotate-cred ::new-ref]]}]]}))
```

The `:rf.cred/*` family is the recommended sketch — your app's auth slice provides the real shape. The contract this leaf locks: **opaque ref in `:data`; bearer never in `:data`; if bearer must move via dispatch, it rides through `:sensitive?` + `with-redacted`**.

## Variations

**Request-reply correlation over the open socket.** Each request gets a `(:request-id (random-uuid))` stamped in. `:in-flight` map in `:data` holds `{request-id {:reply-event ... :timeout-ms ...}}`. The `:connected` `:ws/received` handler branches on `:request-id`. A `:dispatch-later` per request handles timeout. Each request is a Pattern-AsyncEffect interaction; the connection machine performs correlation.

**Heartbeat / keepalive.** `:after` on `:connected` re-arms a periodic ping; a missed pong transitions to `:reconnecting`. Non-trivial cases use a child heartbeat machine.

**Subscription protocol.** Topics live in `:data :subscriptions`. `:on-connected` re-issues subscribes on entry — subscriptions survive reconnects automatically.

**Re-authentication on reconnect.** *Proactive*: auth machine refreshes the bearer (storing it host-side), then dispatches `[:ws/connection [:ws/rotate-cred new-cred-ref]]` carrying only the opaque ref — the bearer itself does not cross the dispatch boundary. Next `:active` entry's `:invoke :data` fn picks up the fresh ref and the spawning actor re-resolves via the client-only cofx. *Reactive*: reconnect into `:authenticating` fails with `:ws/auth-failed`, lands in `:failed`; auth machine observes via `sub-machine`, refreshes, dispatches a fresh `:ws/connect` carrying the new `:cred-ref`. Either way, no bearer in machine `:data`, no bearer in dispatch payloads.

**SSR.** No-ops server-side: `:invoke` spawn fx is `:platforms #{:client}`; `:after` timers don't schedule under SSR.

**Final-state termination (`:final?` / `:on-done`).** Restricted to the *child* role. When the WS machine is `:invoke`'d by an outer session machine, mark a terminal-failed branch (e.g. `:permanently-failed`, distinct from recoverable `:failed`) `:final? true` — parent receives clean unrecoverable signal via `:on-done` with optional `:output-key`. Child heartbeat / handshake machines reaching `:expired` / `:handshake-failed` can similarly use `:final?` instead of dispatching custom outbound events. The top-level connection machine itself stays recoverable. See `../reference/state-machines/invoke.md` §Final states.

## Anti-patterns

- **Anchoring `:invoke` on `:connecting` instead of `:active`.** Destroys the socket on transition to `:authenticating`. Lifetime MUST span all three leaves.
- **Storing the `WebSocket` JS object in `app-db`.** Not a value, not serialisable, won't survive snapshot replay. Actor owns it host-side; only the actor id appears in `:data`.
- **Storing a raw bearer / `auth-token` / cookie / refresh token in machine `:data`.** Same reasoning as the WebSocket JS object plus a privacy one: `:data` is framework-inspectable, so anything held there is liable to land in app-db snapshots, trace emissions, recorder fixtures, and pair tooling — places the dev does not inspect character-by-character. Use the opaque-`:cred-ref` shape above; the bearer lives host-side, resolved at actor spawn via a client-only cofx, and never re-enters dispatch.
- **Routing a refresh bearer through dispatch without `:sensitive?` + `with-redacted`.** If a credential genuinely must move via dispatch (e.g. an out-of-band rotation), gate the handler at the privacy seam in [`../reference/cross-cutting/privacy-and-elision.md`](../reference/cross-cutting/privacy-and-elision.md). The handler body sees the bearer via the unredacted `:event` coeffect; every downstream emit sees `:rf/redacted`.
- **Reconnect via `setTimeout` from inside fx-handler.** Bypasses the machine, tracing, stale-detection. Use `:after`.
- **Skipping `:current-socket?` on `:ws/received`.** A slow `:message` from a torn-down socket lands in the new connection's `:in-flight` — wrong-reply at best.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across messages is state-machine-shaped.
- **Skipping schema validation on `:ws/received` payloads.** Inbound socket frames are an untrusted boundary. Validate against the agreed wire schema at the route-message seam before mutating app-db or branching downstream dispatches. See [`../reference/fundamentals/schemas.md`](../reference/fundamentals/schemas.md) §`validate-at-boundary`.

## Worked example

`examples/reagent/websocket/` (pending — in flight via rf2-yf97). Until merged, treat the canonical declaration above as the source of truth.

## Pointers

- Full pattern doc, request-reply correlation worked example, SSR composition → SKILL-REDIRECT.md → *Pattern — WebSocket*.
- State-machine substrate (`:invoke`, `:after`, `:always`, hierarchical) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- `:final?` / `:on-done` / `:output-key` → `../reference/state-machines/invoke.md` §Final states.
- Connection-epoch idiom → SKILL-REDIRECT.md → *Pattern — Stale detection*.

---

*Derived from Pattern-WebSocket @ main `89bd9c3`. The worked example `examples/reagent/websocket/` is in flight via rf2-yf97; re-verify and link once it merges.*
