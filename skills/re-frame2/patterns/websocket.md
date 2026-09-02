# Pattern — WebSocket

Long-lived bidirectional connection lifecycle (WebSocket / SSE / WebRTC peer) modelled as a state machine that owns the socket actor.

**re-frame2 does NOT ship a managed WebSocket** — there is no `:rf.ws/*` fx, and the `:rf/*` root (every sub-namespace, `:rf.ws/*` included) is **framework-reserved** (Cardinal rule 7; `spec/Conventions.md`): app/library code MUST NOT register handlers, fx, cofx, subs, or failure categories under it. You (or a library) build the connection on the state-machine substrate under **your own feature prefix** (`:ws/*`, `:myapp.ws/*`, `:auth.ws/*`). This is the canonical worked example of applying the **managed external effect** umbrella *by hand* — implemented this way the connection satisfies the umbrella's common properties (issuance, reconnect, abort, teardown, structured failures under your namespace, trace-bus observability, wire-value elision), but you own the lifecycle, not the framework. Request-reply messages you correlate over the open socket are **app-level**: a long-lived connection is not a one-shot async request, so property 9 (the uniform reply envelope) is exempt and the correlation is yours to own (see *§Variations → request-reply correlation*). Umbrella recap: [`managed-http.md`](managed-http.md).

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
| Parent-level `:on` | `:ws/closed`, `:ws/fatal`, `:ws/disconnect`, `:ws/send`, `:ws/rotate-cred` declared once on `:active`, inherited by every leaf. The first three are the doors *out*: each destroys the socket actor, so each settles `:in-flight` on the way out. |
| Connection-epoch staleness check | Live socket-actor's `:rf/self-id` is the epoch. Replies carry `:source-socket-id`; `:current-socket?` guard rejects events from a torn-down prior socket. |

## Credential discipline (load-bearing — read before the snippet)

Bearer tokens, cookies, refresh tokens, and similar credentials **must never live in machine `:data`**. Machine state is framework-inspectable (app-db snapshots, trace emissions, recorder fixtures, pair tooling), so anything in `:data` is liable to be serialised somewhere the dev never inspects character-by-character. The canonical declaration below holds **only a credential reference** (`:cred-ref`) — an opaque key the host-side socket actor exchanges for the real bearer **at the auth write** — not when it creates the socket, because anything the socket's enclosing scope resolves is retained by the stored socket handle for the whole connection — via an app-owned, client-only resolver: a plain host-fn seam like the worked example's `resolve-credential` (`examples/patterns/websocket/messages.cljs`), or a client-only cofx (`:platforms #{:client}` so SSR never sees it). The actor uses the resolved bearer inside its own JS context — its private socket closure — for the auth write and discards it; the bearer never re-enters the dispatch stream.

> **The credential cofx/fx live under YOUR app's prefix, not `:rf/*`.** re-frame2 ships **no** credential surface, and the `:rf/*` root is framework-reserved (`spec/Conventions.md` §single-root reserved set). Register the credential cofx/fx under your auth slice's own feature prefix — the `:auth.cred/fetch` / `:auth.cred/store` names in this leaf are illustrative placeholders for *your* registrations, not framework-provided surfaces.

For events that genuinely must carry a secret across the dispatch boundary (e.g. `:ws/rotate-cred-from-bearer` propagating a freshly minted bearer — the escape hatch worked below), classify the secret's path at its owner (the three-owner model — see [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md)). Here the bearer rides the **event payload**, so name it in the registration's `:sensitive` metadata — `(rf/reg-event :ws/rotate-cred-from-bearer {:sensitive [[:bearer]]} handler)`; a bearer that instead lands at a durable app-db slot is classified by the writing event's `:sensitive` **commit-plane effect** — `{:db … :sensitive [[:auth :bearer]]}`.

The pattern below uses `:cred-ref` as the placeholder; substitute whatever opaque key your auth slice already issues (a UUID, a `(random-uuid)` index into a host-side credential vault, a session id, etc.). The crucial property: the value in `:data` is **not** the bearer itself.

## Canonical declaration

`reg-machine` **is** the registration home for the connection machine — it registers the machine as an event handler and stamps the `:rf/machine?` / source-coordinate metadata the `:rf/machine` sub, the declarative `:spawn` resolver, and the tooling rely on, so author it with `reg-machine`, never wrap it by hand. (`reg-machine` / `defmachine` stay on the `rf/` façade; the lower-level `re-frame.machines/make-machine-handler` factory is an advanced schema-less escape hatch — see [`../references/state-machines/reg-machine.md`](../references/state-machines/reg-machine.md) §Driving a machine as a discrete event-driven flow.)

```clojure
;; The socket actor is spawned on the :active parent, so the runtime binds its
;; id into our own :data under the reserved :rf/spawned map, keyed by the
;; spawn-bearing state's path — [:active]. Read it straight back: there is NO
;; :socket-id data key, no :on-spawn self-dispatch, and no :exit cleanup — the
;; runtime CLEARS the slot on teardown, so a torn-down socket reads nil on its
;; own (which is what makes :current-socket? a safe connection clock).
(def socket-invoke-id [:active])
(defn socket-id [data] (get-in data [:rf/spawned socket-invoke-id]))

;; The connection-epoch test both guards share: is this event stamped with the
;; socket we are using RIGHT NOW? A torn-down socket reads nil, so stragglers fail free.
(defn from-live-socket? [data source-socket-id]
  (let [live (socket-id data)]
    (and (some? live) (= source-socket-id live))))

;; YOUR closed inbound wire union (the example's schema/InboundMessage), compiled
;; ONCE — the machine holds a frame to the same contract the app-db ingress enforces.
(def valid-inbound-frame? (m/validator InboundMessage))     ;; [malli.core :as m]

;; The in-flight half of losing the wire, shared by EVERY door out of :active
;; (the :ws/closed drop, the clean :ws/disconnect, the :ws/fatal escape hatch).
;; Each exit destroys the socket actor, so each must settle :in-flight on the
;; way out: a slot left behind leaks forever, because its timeout carries the
;; dead socket-id and :current-socket? drops it after teardown. FAIL, never
;; replay — the server may already have processed the request. Returns an
;; action result the calling action builds on.
(defn fail-in-flight [data]
  {:data (assoc data :in-flight {})
   :fx   (into [] (keep (fn [[rid {:keys [reply-event]}]]
                          (when reply-event
                            [:dispatch (conj reply-event
                                             {:origin :ws/local :request-id rid
                                              :ok false :error :ws/connection-lost})])))
               (:in-flight data))})

(rf/reg-machine :ws/connection
  {:initial :disconnected
   ;; NOTE :cred-ref is an opaque pointer; the bearer is fetched
   ;; client-side at the auth write via your app's :auth.cred/fetch cofx
   ;; (an app-prefixed registration — NOT a framework surface).
   :data    {:url nil :cred-ref nil :retries 0 :max-retries 8
             :base-ms 1000 :max-backoff-ms 30000
             :subscriptions #{}
             :queue [] :in-flight {} :error nil}

   :guards
   {:max-retries-exceeded? (fn [{data :data}] (>= (:retries data) (:max-retries data)))
    :has-queued-messages?  (fn [{data :data}] (seq (:queue data)))
    :current-socket?
    ;; Connection-epoch check: reject events from a prior socket actor that
    ;; may dispatch in flight while the cascade tears it down. Guards EVERY
    ;; socket-sourced event — the lifecycle transitions (:ws/opened,
    ;; :ws/auth-ok, :ws/auth-failed, :ws/closed) as much as :ws/received.
    (fn [{data :data [_ {:keys [source-socket-id]}] :event}]
      (from-live-socket? data source-socket-id))
    :trusted-frame?
    ;; Vouching for the SENDER is not vouching for the BYTES. One named guard,
    ;; not an :and of two — 005 ships no guard combinator data form.
    (fn [{data :data [_ {:keys [source-socket-id body]}] :event}]
      (and (from-live-socket? data source-socket-id)
           (valid-inbound-frame? body)))}

   :actions
   {:record-connection-opts (fn [{data :data [_ {:keys [url cred-ref]}] :event}]
                              {:data (assoc data :url url :cred-ref cred-ref)})
    :rotate-cred     (fn [{data :data [_ new-cred-ref] :event}]
                       {:data (assoc data :cred-ref new-cred-ref)})
    ;; Three doors out of :active, one shared in-flight settlement. The drop
    ;; also bumps the retry counter; the fatal door also records the error; the
    ;; clean door does neither — the app asked for it, so it is not a failure.
    :on-socket-lost  (fn [{data :data}] (-> (fail-in-flight data)
                                            (update :data update :retries inc)))
    :fail-in-flight  (fn [{data :data}] (fail-in-flight data))
    :on-fatal-error  (fn [{data :data [_ {:keys [error]}] :event}]
                       (-> (fail-in-flight data)
                           (update :data assoc :error error)))
    :on-connected
    ;; :entry takes one fn / id, never a vector — consolidate.
    (fn [{data :data}]
      {:data (assoc data :retries 0)
       :fx   (mapv (fn [t] [:dispatch [(socket-id data) [:send {:type :subscribe :topic t}]]])
                   (:subscriptions data))})
    :flush-queue (fn [{data :data}] {:data (assoc data :queue [])
                               :fx (mapv (fn [m] [:dispatch [(socket-id data) [:send m]]])
                                         (:queue data))})
    :enqueue-message (fn [{data :data [_ m] :event}] {:data (update data :queue conj m)})
    ;; Handshake on :authenticating entry. NO token in the payload — the actor
    ;; resolved the bearer from :cred-ref host-side and puts it on the wire itself.
    :send-auth (fn [{data :data}] {:fx [[:dispatch [(socket-id data) [:send {:type :auth}]]]]})
    ;; :connected overrides the parent's :ws/send — straight to the wire, no queue.
    :send-now  (fn [{data :data [_ m] :event}] {:fx [[:dispatch [(socket-id data) [:send m]]]]})
    ;; A vetted frame. A :reply settles the :in-flight slot it names ONLY when the
    ;; echoed :request-token matches the REGISTRATION in it (ids are reusable — see
    ;; §Anti-patterns); every frame goes on to your boundary-checked ingress event.
    :receive-message
    (fn [{data :data [_ {:keys [body]}] :event}]
      (let [rid   (:request-id body)
            entry (when (= :reply (:type body))
                    (when-let [e (get-in data [:in-flight rid])]
                      (when (= (:request-token body) (:token e)) e)))]
        (cond-> {:fx [[:dispatch [:ws/handle-message body]]]}
          entry                (assoc :data (update data :in-flight dissoc rid))
          (:reply-event entry) (update :fx conj [:dispatch (conj (:reply-event entry)
                                                                 (assoc body :origin :ws/server))]))))
    ;; Live socket, failed contract: hand it to the ingress (which owns the
    ;; rejection record) and return NO :data — nothing moves on unvetted bytes.
    :refuse-frame (fn [{[_ {:keys [body]}] :event}] {:fx [[:dispatch [:ws/handle-message body]]]})}

   :states
   {:disconnected
    {:on {:ws/connect {:target [:active] :action :record-connection-opts}
          :ws/send    {:action :enqueue-message}}}

    :active
    {;; Socket actor anchored on the PARENT — lifetime spans all three leaves.
     ;; The actor receives only :url + :cred-ref; it resolves the bearer
     ;; via a client-only cofx inside its own JS context, then opens the
     ;; socket. The bearer never re-enters dispatch. The runtime binds the
     ;; newborn's id into :data under :rf/spawned [:active] — read via the
     ;; socket-id helper above; no :on-spawn, no :exit cleanup (teardown
     ;; clears the slot). See examples/patterns/websocket/connection.cljs.
     :spawn  {:machine-id :websocket/socket
               ;; :data fn takes ONE context-map arg {:keys [snapshot event]}.
               :data       (fn [{snap :snapshot}] {:url      (-> snap :data :url)
                                                   :cred-ref (-> snap :data :cred-ref)})}
     ;; Every door OUT of :active kills the wire, so every one settles
     ;; :in-flight exactly once on the way out — see the three actions above.
     ;; Lifecycle events are epoch-guarded too: a straggler :ws/closed / :ws/opened
     ;; / :ws/auth-* from a REPLACED socket must neither advance nor tear this down.
     :on      {:ws/closed      {:guard :current-socket? :target :reconnecting :action :on-socket-lost}
               :ws/fatal       {:target :failed        :action :on-fatal-error}
               :ws/disconnect  {:target :disconnected  :action :fail-in-flight}
               :ws/send        {:action :enqueue-message}
               :ws/rotate-cred {:action :rotate-cred}}
     :initial :connecting
     :states
     {:connecting     {:on {:ws/opened {:guard :current-socket? :target :authenticating}}}
      :authenticating {:entry :send-auth
                       :on    {:ws/auth-ok     {:guard :current-socket? :target :connected}
                               :ws/auth-failed {:guard :current-socket? :target [:failed]}}}
      :connected      {:entry  :on-connected
                       :always [{:guard :has-queued-messages? :action :flush-queue}]
                       :on     {;; Two candidates, first-match-wins. :trusted-frame? is
                                ;; :current-socket? AND the closed inbound wire contract:
                                ;; :receive-message clears an :in-flight slot the frame
                                ;; NAMES, so vet before the branch, not just before app-db.
                                ;; The refusal arm returns no :data at all; the ingress
                                ;; downstream owns the rejection record.
                                :ws/received [{:guard :trusted-frame?  :action :receive-message}
                                              {:guard :current-socket? :action :refuse-frame}]
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
          :ws/rotate-cred {:action :rotate-cred}}}}})
```

Caller: `(rf/dispatch [:ws/connection [:ws/connect {:url "wss://api.example.com/ws" :cred-ref (current-session-cred-ref)}]])` — `current-session-cred-ref` returns an opaque pointer into the host-side credential vault. The bearer itself never crosses the dispatch boundary; the actor's app-side `:auth.cred/fetch` cofx (your prefix) resolves the pointer to a bearer at the auth write — not when the socket is created. Anything the socket's enclosing scope resolves is retained by the socket handle for the whole connection, which is the opposite of what "resolve, use, discard" promises.

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

The `:connected` `:ws/received` transition vets the frame first — connection epoch **and** the closed inbound wire contract, in one named guard — then branches on the vetted `:type`; a `:dispatch-later` per request handles timeout. Vetting before the branch is not optional here: the handler clears an `:in-flight` slot the frame itself names, so a contract applied only at the app-db ingress downstream leaves that slot consumable by a frame the ingress then refuses — app-db clean, caller waiting forever. And where two producers reach the reply event (a wire reply, and the machine's own loss/timeout failure), tell them apart by a provenance key the machine stamps **after** receipt, never by the body's shape: a server that has seen the wire request id can send whatever shape your "local failure" arm describes. Each request is a Pattern-AsyncEffect interaction; the machine performs correlation. That correlation is **app-level, and deliberately not the uniform reply envelope**: re-frame2 ships no managed WebSocket, so a long-lived connection is exempt from property 9 (`spec/Managed-Effects.md` §WebSocket — an app/library-built surface; `spec/Pattern-WebSocket.md` §Message correlation for request-reply), and a per-message reply here carries no `:rf/reply-to` target, no `:status` taxonomy, no `:work/id` correlation and no `:completed-at` metadata. The reply is **your own message body dispatched to your own `:reply-event`** — the vetted wire body stamped `:origin :ws/server` on the happy path, and the `{:origin :ws/local :ok false :error :ws/connection-lost}` shape the `fail-in-flight` arm mints when the socket goes away. What you inherit from the shipped managed surfaces is the **discipline, not the vocabulary**: make the **live socket epoch (`:current-socket?`) the correctness boundary** and drop a late reply for a torn-down socket outright, no app-db mutation — the same stale-suppression rule `:rf.http/managed` and resources follow, spelled in your own namespace. An app that *wants* envelope-shaped socket replies can build that on top; the recommended worked shape is the app-level `:in-flight` map.

**Heartbeat / keepalive.** `:after` on `:connected` re-arms a periodic ping; a missed pong transitions to `:reconnecting`. Non-trivial cases use a child heartbeat machine.

**Subscription protocol.** Topics live in `:data :subscriptions`. `:on-connected` re-issues subscribes on entry — subscriptions survive reconnects automatically.

**Re-authentication on reconnect.** *Proactive*: auth machine refreshes the bearer (storing it host-side), then dispatches `[:ws/connection [:ws/rotate-cred new-cred-ref]]` carrying only the opaque ref — the bearer itself does not cross the dispatch boundary. Next `:active` entry's `:spawn :data` fn picks up the fresh ref and the new socket re-resolves it at its own auth write. *Reactive*: reconnect into `:authenticating` fails with `:ws/auth-failed`, lands in `:failed`; auth machine observes via the `:rf/machine` sub, refreshes, dispatches a fresh `:ws/connect` carrying the new `:cred-ref`. Either way, no bearer in machine `:data`, no bearer in dispatch payloads.

**SSR.** No-ops server-side: `:spawn` spawn fx is `:platforms #{:client}`; `:after` timers don't schedule under SSR.

**Final-state termination (`:final?` / `:on-done`).** Restricted to the *child* role. When the WS machine is `:spawn`'d by an outer session machine, mark a terminal-failed branch (e.g. `:permanently-failed`, distinct from recoverable `:failed`) `:final? true` — parent receives clean unrecoverable signal via `:on-done` with optional `:output-key`. Child heartbeat / handshake machines reaching `:expired` / `:handshake-failed` can similarly use `:final?` instead of dispatching custom outbound events. The top-level connection machine itself stays recoverable. See `../references/state-machines/spawn.md` §Final states.

## Anti-patterns

- **Anchoring `:spawn` on `:connecting` instead of `:active`.** Destroys the socket on transition to `:authenticating`. Lifetime MUST span all three leaves.
- **Storing the `WebSocket` JS object in `app-db`.** Not a value, not serialisable, won't survive snapshot replay. Actor owns it host-side; only the actor id appears in `:data`.
- **Storing a raw bearer / `auth-token` / cookie / refresh token in machine `:data`.** Same reasoning as the WebSocket JS object plus a privacy one: `:data` is framework-inspectable, so anything held there is liable to land in app-db snapshots, trace emissions, recorder fixtures, and pair tooling — places the dev does not inspect character-by-character. Use the opaque-`:cred-ref` shape above; the bearer lives host-side, resolved by a client-only seam at the auth write, and never re-enters dispatch.
- **Routing a refresh bearer through dispatch without classifying its path at its owner.** If a credential genuinely must move via dispatch (e.g. an out-of-band rotation), classify the path where it lands — a durable app-db secret via the writing event's `:sensitive` commit-plane effect, or the transient dispatch payload key in the dispatching handler's registration `:sensitive` metadata — per the privacy seam in [`../references/cross-cutting/privacy-and-elision.md`](../references/cross-cutting/privacy-and-elision.md). (There is **no** handler-meta `{:sensitive? true}` privacy switch; classification is fail-open and does not propagate.)
- **Reconnect via `setTimeout` from inside fx-handler.** Bypasses the machine, tracing, stale-detection. Use `:after`.
- **Skipping `:current-socket?` on `:ws/received`.** A slow `:message` from a torn-down socket lands in the new connection's `:in-flight` — wrong-reply at best.
- **Settling `:in-flight` on only one door out of `:active`.** The `:ws/closed` drop is the door everyone remembers. A clean `:ws/disconnect` and an app-level `:ws/fatal` destroy the socket just as surely, and a slot stranded by `:ws/fatal` survives even a later manual `:ws/connect` out of `:failed` — its timeout carries the dead socket-id, so `:current-socket?` drops it and nothing else ever clears the slot. Route every exit through one shared fail-and-clear rather than repeating the logic per door.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across messages is state-machine-shaped.
- **Skipping schema validation on `:ws/received` payloads.** Inbound socket frames are an untrusted boundary. Validate against the agreed wire schema at the receive-message seam before mutating app-db or branching downstream dispatches. See [`../references/fundamentals/schemas.md`](../references/fundamentals/schemas.md) §`validate-at-boundary-interceptor`.
- **Validating at the app-db ingress only, when the machine mutates on the frame first.** "Before mutating app-db" is not the whole rule if the connection machine clears an `:in-flight` slot the frame names: the ingress refuses the body, app-db is untouched, and the correlation is already spent — the caller waits forever, and nothing on the rejection counter says so. Put the wire contract in the transition guard as well, ahead of the branch.
- **Recognising a locally minted loss/timeout outcome by its shape.** Whatever fields that arm describes, a server holding the wire request id can send them. Stamp `:origin` on receipt and dispatch the outcome union on the stamp.
- **Correlating a request timeout by `:request-id` alone.** The epoch guard rejects timers from an old *connection*; it says nothing about an old *registration* on the same live socket, and correlation ids are the app's and may legitimately recur (a per-feature `[:feature/load slug]` vector is a normal shape). A completed request's uncancelled timer then arrives naming an id a *later* request now holds, passes the epoch check, deletes that request's slot and hands its caller a timeout its deadline never reached. Stamp each registration with a token from a counter in `:data`, put it on the scheduled timeout, and admit the timeout only while that token still occupies the slot — which is also what lets you ship no timer-cancellation facility at all, since an obsolete timer merely fails the guard. State the duplicate-in-flight policy in the same breath: a second registration under a live id must settle the caller it displaces rather than `assoc-in` over it.
- **Fixing that for the timer and leaving the server's reply correlating by id.** Same gap, one door further out, and the quieter half: reuse an id and two requests ride the wire under one correlation value, so the first reply back settles whichever registration holds the slot — the callback fires exactly once, on time, well-formed, and about the wrong question. Put the registration token **on the wire** beside the id, require it back in the closed reply arm, and settle only on a match; a token mismatch is an unsolicited reply, not an error. A server that cannot echo it fails the wire contract loudly on the first reply, which is the point — the alternative is correlating by id again, silently. It is a correctness discriminator, not a secret: the peer was handed it in the request, and provenance stays `:origin`'s job.
- **Leaving the arms of the inbound union open.** An open `:push` arm accepts a frame carrying a live `:request-id` under a kind that has nothing to do with request-reply. Close each arm (`[:map {:closed true} …]`), not just the union.

## Worked example

`examples/patterns/websocket/` is the canonical worked example. `connection.cljs` holds the lifecycle machine (compound `:active` parenting `:connecting` / `:authenticating` / `:connected`, a `:spawn`d socket actor, `:after` backoff, `:always` offline-queue flush, connection-epoch staleness, request/reply correlation); `messages.cljs`, `schema.cljs`, and `views.cljs` complete it, and both trust boundaries are implemented there: the opaque-`:cred-ref` seam (`resolve-credential`, called in the socket's `:auth` branch so the bearer outlives neither the write nor the stored socket handle) and the closed inbound wire union — enforced with `:rf.schema/at-boundary` on both app-db-writing ingress events, and again in the machine's `:trusted-frame?` guard ahead of any correlation bookkeeping. Read the source first; the declaration above is the leaf-level summary.

## Pointers

- Full pattern doc, request-reply correlation worked example, SSR composition → SKILL-REDIRECT.md → *Pattern — WebSocket*.
- State-machine substrate (`:spawn`, `:after`, `:always`, hierarchical) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- `:final?` / `:on-done` / `:output-key` → `../references/state-machines/spawn.md` §Final states.
- Connection-epoch idiom → SKILL-REDIRECT.md → *Pattern — Stale detection*.

---

*Derived from Pattern-WebSocket and the worked example `examples/patterns/websocket/` @ main `89bd9c3`. Re-verify after `:rf.ws/*` or connection-machine changes.*
