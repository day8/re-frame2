# Pattern — WebSocket

Long-lived bidirectional connection lifecycle (WebSocket / SSE / WebRTC peer) modelled as a state machine that owns the socket actor.

**re-frame2 does NOT ship a managed WebSocket** — there is no `:rf.ws/*` fx, and `:rf/*` (every sub-namespace, `:rf.ws/*` included) is **framework-reserved** (Cardinal rule 7; `spec/Conventions.md`): app/library code MUST NOT register handlers, fx, cofx, subs, or failure categories under it. You (or a library) build the connection on the state-machine substrate under **your own feature prefix** (`:ws/*`, `:myapp.ws/*`, `:auth.ws/*`); the **shipped** managed-effect surfaces are `:rf.http/managed`, state-machine `:spawn`, `:rf.server/*`, `:rf.flow/*`. This is the canonical worked example of applying the **managed external effect** umbrella *by hand*: implemented this way, the connection's lifecycle (issuance, reconnect, abort, teardown, structured failures under your namespace, trace-bus observability, wire-value elision) satisfies the eight common properties — but you own it, not the framework. The ninth property — a **uniform reply envelope** for async completion — applies to any *request-reply* messages you correlate over the open socket (model those on the envelope; see *§Variations → request-reply correlation*). Shared contract: [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md); the rest of this leaf is WebSocket-specific.

> **Worked example:** `examples/patterns/websocket/` ships the canonical Pattern-WebSocket app (`connection.cljs` holds the machine). Read it as ground truth; the canonical declaration below is the leaf-level summary.

## When to load

Reach for it when the connection itself has phases (`:connecting` → `:authenticating` → `:connected` → `:reconnecting` → `:failed`), survives across message boundaries, retries with backoff, manages subscriptions across reconnects, or carries correlation ids for request-reply messages over the open socket.

Do **not** reach for it when the interaction is one request, one reply — that is `Pattern-AsyncEffect`, even when the wire is a short-lived WebSocket. The discriminator: "does the connection outlive any one message?".

SSE (`EventSource`) and WebRTC peer connections share the same lifecycle shape — same pattern, different wire format inside the actor.

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| Hierarchical compound state | `:active` parents `:connecting` / `:authenticating` / `:connected`; **the socket actor's lifetime is anchored on the parent**, so it outlives leaf transitions. |
| `:spawn` (declarative spawn) | `:active` invokes a `:websocket/socket` child owning the JS `WebSocket`. Exiting `:active` destroys it; re-entering spawns a fresh one. |
| `:after` (fn-form delay) | Exponential backoff timer in `:reconnecting`, computed at entry from `:retries` and `:base-ms`. |
| `:always` | Max-retries guard on `:reconnecting` entry; queue-flush guard on `:connected` entry. |
| Parent-level `:on` | `:ws/closed`, `:ws/fatal`, `:ws/send`, `:ws/refresh-token` declared once on `:active`, inherited by every leaf. |
| Connection-epoch staleness check | Live socket-actor's `:rf/self-id` is the epoch. Replies carry `:source-socket-id`; `:current-socket?` guard rejects events from a torn-down prior socket. |

## Credential discipline (load-bearing — read before the snippet)

Bearer tokens, cookies, refresh tokens, and similar credentials **must never live in machine `:data`**. Machine state is framework-inspectable (app-db snapshots, trace emissions, recorder fixtures, pair tooling), so anything in `:data` is liable to be serialised somewhere the dev never inspects character-by-character. The canonical declaration below holds **only a credential reference** (`:cred-ref`) — an opaque key the host-side socket actor exchanges for the real bearer at spawn time via a client-only cofx (`:platforms #{:client}` so SSR never sees it). The actor uses the resolved bearer inside its own JS context and discards it; the bearer never re-enters the dispatch stream.

> **The credential cofx/fx live under YOUR app's prefix, not `:rf/*`.** re-frame2 ships **no** credential surface, and the `:rf/*` root is framework-reserved (`spec/Conventions.md` §single-root reserved set). Register the credential cofx/fx under your auth slice's own feature prefix — the `:auth.cred/fetch` / `:auth.cred/store` names in this leaf are illustrative placeholders for *your* registrations, not framework-provided surfaces.

For events that genuinely must carry a secret across the dispatch boundary (e.g. `:ws/refresh-token` propagating a freshly minted bearer), classify the secret's path at its owner (the three-owner model — see [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md)). Here the bearer rides the **event payload**, so name it in the registration's `:sensitive` metadata — `(rf/reg-event :ws/refresh-token {:sensitive [[:bearer]]} handler)`; a bearer that instead lands at a durable app-db slot is classified by the writing event's `:sensitive` **commit-plane effect** — `{:db … :sensitive [[:auth :bearer]]}`.

The pattern below uses `:cred-ref` as the placeholder; substitute whatever opaque key your auth slice already issues (a UUID, a `(random-uuid)` index into a host-side credential vault, a session id, etc.). The crucial property: the value in `:data` is **not** the bearer itself.

## Canonical declaration

`make-machine-handler` lives on `re-frame.machines` (`(:require [re-frame.machines :as machines])`) — it is no longer re-exported from `re-frame.core` (front-porch shrink). The `reg-machine` / `defmachine` registration macros stay on the `rf/` façade.

```clojure
(rf/reg-event :ws/connection
  (machines/make-machine-handler
    {:initial :disconnected
     ;; NOTE :cred-ref is an opaque pointer; the bearer is fetched
     ;; client-side at actor spawn via your app's :auth.cred/fetch cofx
     ;; (an app-prefixed registration — NOT a framework surface).
     :data    {:url nil :cred-ref nil :retries 0 :max-retries 8
               :base-ms 1000 :max-backoff-ms 30000
               :socket-id nil :subscriptions #{}
               :queue [] :in-flight {} :error nil}

     :guards
     {:max-retries-exceeded? (fn [{data :data}] (>= (:retries data) (:max-retries data)))
      :has-queued-messages?  (fn [{data :data}] (seq (:queue data)))
      :current-socket?
      ;; Connection-epoch check: reject events from a prior socket actor
      ;; that may dispatch in flight while the cascade tears it down.
      (fn [{data :data [_ {:keys [source-socket-id]}] :event}]
        (= source-socket-id (:socket-id data)))}

     :actions
     {:record-connection-opts (fn [{data :data [_ {:keys [url cred-ref]}] :event}]
                                {:data (assoc data :url url :cred-ref cred-ref)})
      :rotate-cred     (fn [{data :data [_ new-cred-ref] :event}]
                         {:data (assoc data :cred-ref new-cred-ref)})
      :bump-retry      (fn [{data :data}] {:data (update data :retries inc)})
      :clear-socket-id (fn [{data :data}] {:data (assoc data :socket-id nil)})
      ;; The freshly-spawned actor id arrives as the payload of the
      ;; self-dispatched :ws/-socket-spawned event (see :on-spawn below);
      ;; an ORDINARY action persists it into :data — :on-spawn's own
      ;; return is dropped, so it cannot write :data itself.
      :record-socket-id (fn [{data :data [_ id] :event}] {:data (assoc data :socket-id id)})
      :on-connected
      ;; :entry takes one fn / id, never a vector — consolidate.
      (fn [{data :data}]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [t] [:dispatch [(:socket-id data) [:send {:type :subscribe :topic t}]]])
                     (:subscriptions data))})
      :flush-queue (fn [{data :data}] {:data (assoc data :queue [])
                                 :fx (mapv (fn [m] [:dispatch [(:socket-id data) [:send m]]])
                                           (:queue data))})
      :enqueue-message (fn [{data :data [_ m] :event}] {:data (update data :queue conj m)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target [:active] :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}}}

      :active
      {;; Socket actor anchored on the PARENT — lifetime spans all three leaves.
       ;; The actor receives only :url + :cred-ref; it resolves the bearer
       ;; via a client-only cofx inside its own JS context, then opens the
       ;; socket. The bearer never re-enters dispatch.
       :spawn  {:machine-id :websocket/socket
                 ;; :data fn takes ONE context-map arg {:keys [snapshot event]}.
                 :data       (fn [{snap :snapshot}] {:url      (-> snap :data :url)
                                                     :cred-ref (-> snap :data :cred-ref)})
                 ;; :on-spawn is ADVISORY — its return is dropped, so it cannot
                 ;; write :data. It dispatches a self-event carrying the actor
                 ;; id; the :ws/-socket-spawned transition's :record-socket-id
                 ;; action persists it. (Worked example:
                 ;; examples/patterns/websocket/connection.cljs.)
                 :on-spawn   (fn [{id :id}]
                               (when-let [dispatch! (re-frame.late-bind/get-fn :router/dispatch!)]
                                 (dispatch! [:ws/connection [:ws/-socket-spawned id]]
                                            {:source :websocket})))}
       :exit    :clear-socket-id
       :on      {:ws/closed        {:target :reconnecting :action :bump-retry}
                 :ws/fatal         {:target :failed}
                 :ws/send          {:action :enqueue-message}
                 :ws/rotate-cred   {:action :rotate-cred}
                 ;; Framework-internal: records the spawned socket id into
                 ;; :data via an ordinary action (:on-spawn's return is dropped).
                 :ws/-socket-spawned {:action :record-socket-id}}
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
       ;; fn-form delay — ONE context-map arg {:keys [snapshot]}; the snapshot's
       ;; :data is at (:data snapshot). Re-evaluated each :reconnecting entry.
       :after  {(fn [{:keys [snapshot]}]
                  (let [{:keys [retries base-ms max-backoff-ms]} (:data snapshot)]
                    (min (* base-ms (Math/pow 2 retries)) max-backoff-ms)))
                {:target [:active]}}
       :on     {:ws/connect     {:target [:active] :action :record-connection-opts}
                :ws/rotate-cred {:action :rotate-cred}
                :ws/send        {:action :enqueue-message}}}

      :failed
      {:on {:ws/connect     {:target [:active] :action :record-connection-opts}
            :ws/rotate-cred {:action :rotate-cred}}}}}))
```

Caller: `(rf/dispatch [:ws/connection [:ws/connect {:url "wss://api.example.com/ws" :cred-ref (current-session-cred-ref)}]])` — `current-session-cred-ref` returns an opaque pointer into the host-side credential vault. The bearer itself never crosses the dispatch boundary; the actor's app-side `:auth.cred/fetch` cofx (your prefix) resolves the pointer to a bearer at spawn time.

If the credential genuinely must move via dispatch (e.g. an out-of-band rotation event), name the payload path in the **registration's** `:sensitive` metadata — the registration owns transient-payload classification (see [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md)):

```clojure
(rf/reg-event :ws/rotate-cred-from-bearer
  {:sensitive [[:bearer]]}                ;; the registration owns this transient payload's classification
  (fn [{:keys [db]} [_ {:keys [bearer]}]]
    ;; the :bearer payload key ships as :rf/redacted on the trace/listener/error
    ;; surface; the handler body still sees the real value via the :event coeffect.
    ;; :auth.cred/store is YOUR app's fx (an app-prefixed registration);
    ;; re-frame2 ships no credential fx and :rf/* is reserved.
    {:fx [[:auth.cred/store {:bearer bearer :on-stored [:ws/connection [:ws/rotate-cred ::new-ref]]}]]}))
```

The `:auth.cred/*` family is an illustrative sketch under a sample app prefix — re-frame2 ships **no** credential cofx/fx, and `:rf/*` (`:rf.cred/*` included) is framework-reserved, so register these under your own auth-slice prefix. The contract this leaf locks: **opaque ref in `:data`; bearer never in `:data`; if a bearer must move via dispatch, classify its path at its owner — a durable app-db secret via the writing event's `:sensitive` commit-plane effect, or a transient payload key in the dispatching handler's registration `:sensitive` metadata** (there is no handler-meta privacy switch).

## Variations

**Request-reply correlation over the open socket.** Each request gets a `:request-id` stamped in; the `:in-flight` map in `:data` holds `{request-id {:reply-event ... :timeout-ms ...}}`. **Because that id lands in machine `:data` (durable runtime state) and becomes the correlation `:work/id`, it must NOT be a bare `(random-uuid)` minted in the fold (EP-0017, Spec 002 §Recordable coeffects)** — a fresh uuid re-rolls on every replay, so in-flight correlation and stale-reply matching diverge under epoch restore / time-travel / SSR. Source it from the **dispatching event payload** (the minting ladder's preferred rung for a caller-owned id — see [`../references/state-machines/reg-machine.md` §Guard / action contract](../references/state-machines/reg-machine.md)), a snapshot-resident counter already in `:data`, or — once app-owned recordable generators ship (EP-0017 slice B) — a **recordable** cofx whose captured value replays. (A purely host-transient handle that never enters `:data` / becomes a work-id — a fire-and-forget frame with no correlation — can stay an ambient mint.)

The `:connected` `:ws/received` handler branches on `:request-id`; a `:dispatch-later` per request handles timeout. Each request is a Pattern-AsyncEffect interaction; the machine performs correlation. Because this is the **completion** of an async request, model it on the **uniform reply envelope** (Managed-Effects property 9), not a bespoke ack: dispatch the `:reply-event` with a reply map carrying a closed **`:status` `{:ok :partial :error :cancelled :stale}`** and the request id as `:work/id`, and make the **live socket epoch (`:current-socket?`) the correctness boundary** — drop a late reply for a torn-down socket as `:status :stale`, no app-db mutation. Same stale-suppression discipline `:rf.http/managed` and resources use, so your socket replies read the same as every other async completion in trace/Xray. (Your `:reply-event` plays the `:rf/reply-to` role — an event-vector prefix the reply map is appended to.)

**Heartbeat / keepalive.** `:after` on `:connected` re-arms a periodic ping; a missed pong transitions to `:reconnecting`. Non-trivial cases use a child heartbeat machine.

**Subscription protocol.** Topics live in `:data :subscriptions`. `:on-connected` re-issues subscribes on entry — subscriptions survive reconnects automatically.

**Re-authentication on reconnect.** *Proactive*: auth machine refreshes the bearer (storing it host-side), then dispatches `[:ws/connection [:ws/rotate-cred new-cred-ref]]` carrying only the opaque ref — the bearer itself does not cross the dispatch boundary. Next `:active` entry's `:spawn :data` fn picks up the fresh ref and the spawning actor re-resolves via the client-only cofx. *Reactive*: reconnect into `:authenticating` fails with `:ws/auth-failed`, lands in `:failed`; auth machine observes via `sub-machine`, refreshes, dispatches a fresh `:ws/connect` carrying the new `:cred-ref`. Either way, no bearer in machine `:data`, no bearer in dispatch payloads.

**SSR.** No-ops server-side: `:spawn` spawn fx is `:platforms #{:client}`; `:after` timers don't schedule under SSR.

**Final-state termination (`:final?` / `:on-done`).** Restricted to the *child* role. When the WS machine is `:spawn`'d by an outer session machine, mark a terminal-failed branch (e.g. `:permanently-failed`, distinct from recoverable `:failed`) `:final? true` — parent receives clean unrecoverable signal via `:on-done` with optional `:output-key`. Child heartbeat / handshake machines reaching `:expired` / `:handshake-failed` can similarly use `:final?` instead of dispatching custom outbound events. The top-level connection machine itself stays recoverable. See `../references/state-machines/spawn.md` §Final states.

## Anti-patterns

- **Anchoring `:spawn` on `:connecting` instead of `:active`.** Destroys the socket on transition to `:authenticating`. Lifetime MUST span all three leaves.
- **Storing the `WebSocket` JS object in `app-db`.** Not a value, not serialisable, won't survive snapshot replay. Actor owns it host-side; only the actor id appears in `:data`.
- **Storing a raw bearer / `auth-token` / cookie / refresh token in machine `:data`.** Same reasoning as the WebSocket JS object plus a privacy one: `:data` is framework-inspectable, so anything held there is liable to land in app-db snapshots, trace emissions, recorder fixtures, and pair tooling — places the dev does not inspect character-by-character. Use the opaque-`:cred-ref` shape above; the bearer lives host-side, resolved at actor spawn via a client-only cofx, and never re-enters dispatch.
- **Routing a refresh bearer through dispatch without classifying its path at its owner.** If a credential genuinely must move via dispatch (e.g. an out-of-band rotation), classify the path where it lands — a durable app-db secret via the writing event's `:sensitive` commit-plane effect, or the transient dispatch payload key in the dispatching handler's registration `:sensitive` metadata — per the privacy seam in [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md). (There is **no** handler-meta `{:sensitive? true}` privacy switch — that annotation was removed from the runtime; classification is fail-open and does not propagate.)
- **Reconnect via `setTimeout` from inside fx-handler.** Bypasses the machine, tracing, stale-detection. Use `:after`.
- **Skipping `:current-socket?` on `:ws/received`.** A slow `:message` from a torn-down socket lands in the new connection's `:in-flight` — wrong-reply at best.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across messages is state-machine-shaped.
- **Skipping schema validation on `:ws/received` payloads.** Inbound socket frames are an untrusted boundary. Validate against the agreed wire schema at the route-message seam before mutating app-db or branching downstream dispatches. See [`../references/fundamentals/schemas.md`](../references/fundamentals/schemas.md) §`validate-at-boundary-interceptor`.

## Worked example

`examples/patterns/websocket/` is the canonical worked example. `connection.cljs` holds the lifecycle machine (compound `:active` parenting `:connecting` / `:authenticating` / `:connected`, a `:spawn`d socket actor, `:after` backoff, `:always` offline-queue flush, connection-epoch staleness, request/reply correlation); `messages.cljs`, `schema.cljs`, and `views.cljs` complete it. Read the source first; the declaration above is the leaf-level summary.

## Pointers

- Full pattern doc, request-reply correlation worked example, SSR composition → SKILL-REDIRECT.md → *Pattern — WebSocket*.
- State-machine substrate (`:spawn`, `:after`, `:always`, hierarchical) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- `:final?` / `:on-done` / `:output-key` → `../references/state-machines/spawn.md` §Final states.
- Connection-epoch idiom → SKILL-REDIRECT.md → *Pattern — Stale detection*.

---

*Derived from Pattern-WebSocket and the worked example `examples/patterns/websocket/` @ main `89bd9c3`. Re-verify after `:rf.ws/*` or connection-machine changes.*
