# Pattern — WebSocket

> **Type:** Pattern
> Long-lived connection lifecycle — WebSocket / SSE / WebRTC peer — modelled as a state machine that owns the socket. Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.
>
> **re-frame2 does NOT ship a managed WebSocket.** There is no `:rf.ws/*` fx and no reserved `:rf.ws/*` namespace — apps and library authors supply their own connection surface appropriate to their needs (or use a community library). This doc is a **convention for app & library authors** building a WebSocket connection on re-frame2's [state-machine substrate](005-StateMachines.md). The recommended shape below satisfies the [nine managed-effect properties](Managed-Effects.md) when an app implements it this way — effect-as-data, app-owned socket-actor lifecycle, a structured failure taxonomy under an app-chosen `:rf.ws/*`-style namespace, trace-bus observability, `:sensitive?` / `:large?` composition, retry / abort / teardown via the connection state machine, an in-flight socket-actor registry, and per-frame interceptor scoping. A WebSocket is a long-lived synchronous connection, not a one-shot async request, so property 9 (the uniform async-reply envelope) is exempt — those eight synchronous properties are the ones it is graded on. They describe what a *good* implementation looks like, not a framework-shipped contract the runtime guarantees.

## Role

A **convention for app & library authors**, not a Spec and not a shipped framework surface. WebSockets do not fit [Pattern-AsyncEffect](Pattern-AsyncEffect.md): they are state-machine-shaped — a long-lived connection with retry, exponential backoff, server-pushed events, heartbeat, subscription management, message correlation, queued sends when disconnected, and re-auth on reconnect. The natural canonical answer is a **state machine that owns the connection lifecycle**.

re-frame2 does not fold a managed WebSocket into the framework; this doc instead names that machine's standard shape so an app or library that builds its own connection cites a single canonical description rather than re-deriving the lifecycle each time. (The same convention applies to **Server-Sent Events** and **WebRTC peer connections** — see below.)

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
- **`:after`** ([005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) — exponential backoff timer in `:reconnecting`, expressed as a **fn-form delay** `(fn [{:keys [snapshot]}] ms)` that reads the current `:retries` and `:base-ms` from the snapshot's `:data`. The `:after`-epoch invariant ([005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) guarantees stale timers from prior `:reconnecting` visits are silently dropped on transitions away.
- **`:always`** ([005 §Eventless `:always` transitions](005-StateMachines.md#eventless-always-transitions)) — max-retries guard fires immediately on entry to `:reconnecting` if `:retries` exceeds the limit, transitioning straight to `:failed`. Also used to flush queued messages on entry to `:connected`.
- **Machine-scoped `:guards` / `:actions`** ([005 §Registration — the machine IS the event handler](005-StateMachines.md#registration--the-machine-is-the-event-handler)) — for `:max-retries-exceeded?`, `:has-queued-messages?`, `:on-socket-lost`, `:flush-queue`, `:current-socket?`, etc.
- **`:spawn`** ([005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)) — `:active` invokes a `:websocket/socket` actor that owns the actual `WebSocket` object; the actor's lifetime is bound to the `:active` parent. Any transition that exits `:active` (to `:reconnecting`, to `:failed`, or to `:disconnected`) destroys the actor; re-entering `:active` after `:after` backoff spawns a fresh socket.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — the **connection epoch** is the socket-actor's own gensym'd id, read from the parent's `:rf/spawned` slot (the runtime keeps it current and clears it on teardown — see [005 §Recording the spawned id user-side](005-StateMachines.md#recording-the-spawned-id-user-side)). Every event the socket actor dispatches into the parent carries its `:source-socket-id`; the `:current-socket?` guard checks that the carried id matches the live id before the transition commits. This guards the **whole socket-sourced surface** — the lifecycle transitions (`:ws/opened`, `:ws/auth-ok`, `:ws/auth-failed`, `:ws/closed`) as much as `:ws/received` and `:ws/request-timeout` — so a straggler from a socket that has since been replaced can neither advance nor tear down the new connection; it is dropped via a `:rf.ws/stale-socket` trace. The same idiom that `:after` already uses internally, applied to socket-actor identity.

## Worked example — connection machine

```clojure
;; The socket actor is spawned on the `:active` parent (below), so the id the
;; runtime binds into this machine's own `:data` is keyed by that state's path,
;; `[:active]`, under the reserved `:rf/spawned` map. `socket-id` reads it back.
;; Because the runtime CLEARS that slot the instant the actor is torn down
;; (leaving `:active` by any door), a torn-down socket reads as nil on its own —
;; no `:exit` action to null anything. That auto-clear is exactly what lets the
;; live id double as the connection's staleness clock. First-class :rf/spawned
;; idiom (005 §Recording the spawned id user-side), preferred over an :on-spawn
;; self-dispatch.
(defn- socket-id [data] (get-in data [:rf/spawned [:active]]))

(rf/reg-event :ws/connection
  {:doc "WebSocket connection lifecycle: disconnected → active{:connecting →
         :authenticating → :connected} → reconnecting (with backoff) → failed."}
  (rf/make-machine-handler
    {:initial :disconnected
     :data    {:url            nil               ;; supplied by :ws/connect; refreshed by :ws/refresh-token
               :auth-token     nil               ;; supplied by :ws/connect (or refreshed at runtime)
               :retries        0
               :max-retries    8
               :base-ms        1000              ;; initial backoff
               :max-backoff-ms 30000
               ;; No :socket-id field: the live socket's id lives in the
               ;; runtime-maintained :rf/spawned slot (read via `socket-id`),
               ;; which the runtime keeps current and clears on teardown.
               :subscriptions  #{}               ;; topics to (re-)subscribe on :connected entry
               :queue          []                ;; whole inbound events buffered while disconnected
               :in-flight      {}                ;; {request-id → {:reply-event ... :timeout-ms ...}}
               :error          nil}

     :guards
     {:max-retries-exceeded?
      (fn [{:keys [data]}]
        (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn [{:keys [data]}]
        (seq (:queue data)))

      :current-socket?
      ;; The connection-epoch check (Pattern-StaleDetection): is this event
      ;; from the socket this machine owns RIGHT NOW? Every inbound socket
      ;; event stamps the id of the socket it came from (`:source-socket-id`,
      ;; the actor's `:rf/self-id`); we let it through only if that matches the
      ;; live id read from `:rf/spawned`. A torn-down socket reads as nil, so
      ;; every straggler from a replaced connection is dropped for free.
      (fn [{:keys [data] [_ {:keys [source-socket-id]}] :event}]
        (let [live (socket-id data)]
          (and (some? live) (= source-socket-id live))))}

     :actions
     {:record-connection-opts
      ;; Caller passes URL + token on :ws/connect; opts land in :data and
      ;; every subsequent reconnect re-reads them via :spawn's :data fn.
      (fn [{:keys [data] [_ {:keys [url auth-token]}] :event}]
        {:data (assoc data :url url :auth-token auth-token)})

      :refresh-token
      ;; The auth machine calls this after an out-of-band refresh; the
      ;; next :active entry's :spawn :data fn picks up the fresh token.
      (fn [{:keys [data] [_ token] :event}]
        {:data (assoc data :auth-token token)})

      :on-socket-lost
      ;; The live socket dropped (a :ws/closed that passed :current-socket?).
      ;; Heading for :reconnecting, two things happen:
      ;;   1. Bump the retry counter — it drives the :after backoff and the
      ;;      :max-retries-exceeded? guard.
      ;;   2. FAIL every in-flight request. Each was already on the wire that
      ;;      just died, so its reply can never arrive on THIS connection; left
      ;;      in :in-flight it would leak forever (its timeout is stamped with
      ;;      the now-dead socket-id, so :current-socket? drops that timeout
      ;;      after we reconnect and the slot never clears). Loss semantics are
      ;;      FAIL, not replay — the server may already have processed the
      ;;      request, so a blind re-send risks double execution. Each waiting
      ;;      :reply-event fires with {:ok false :error :ws/connection-lost} so
      ;;      the caller learns the outcome instead of hanging forever.
      (fn [{:keys [data]}]
        {:data (-> data (update :retries inc) (assoc :in-flight {}))
         :fx   (into []
                     (keep (fn [[rid {:keys [reply-event]}]]
                             (when reply-event
                               [:dispatch (conj reply-event
                                                {:request-id rid
                                                 :ok         false
                                                 :error      :ws/connection-lost})])))
                     (:in-flight data))})

      :send-auth
      ;; Route an :auth message into the live socket actor.
      (fn [{:keys [data]}]
        {:fx [[:dispatch [(socket-id data) [:send {:type  :auth
                                                   :token (:auth-token data)}]]]]})

      :on-connected
      ;; Compound entry action for :connected — reset the retry counter (we
      ;; made it) and re-issue a subscribe for every tracked topic, so
      ;; subscriptions survive the reconnect. The queued-message flush is the
      ;; separate :always step below; this action leaves :queue for it to find.
      ;; (Per [005 §State nodes] :entry takes one fn or one registered id,
      ;; never a vector.)
      (fn [{:keys [data]}]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      ;; The :always step on :connected: replay everything buffered while off
      ;; connection. Each queued item is the ORIGINAL inbound event, so
      ;; re-dispatch it INTO the connection machine — now :connected, so a
      ;; :ws/send takes :send-now and a :ws/request takes :register-request
      ;; (in-flight slot + correlation + timeout), exactly as if issued while
      ;; connected. Clearing :queue first stops the replay re-enqueuing.
      (fn [{:keys [data]}]
        {:data (assoc data :queue [])
         :fx   (mapv (fn [event] [:dispatch [:ws/connection event]])
                     (:queue data))})

      :enqueue-message
      ;; Off connection there's no socket to send on, so buffer the WHOLE
      ;; inbound event ([:ws/send …] or [:ws/request …]), not just its body.
      ;; :flush-queue re-dispatches each verbatim on the next :connected entry,
      ;; so a queued request rejoins :register-request and gets correlated.
      ;; (Buffering a bare body instead would put a request's whole envelope on
      ;; the wire as the payload — uncorrelated, never answered.)
      (fn [{:keys [data] event :event}]
        {:data (update data :queue conj event)})

      :register-request
      ;; Caller: [:ws/request {:request-id ..., :body ..., :reply ...}].
      ;; Record the in-flight entry, forward to the socket, schedule a timeout.
      (fn [{:keys [data] [_ {:keys [request-id body reply timeout-ms]
                             :or   {timeout-ms 30000}}] :event}]
        {:data (assoc-in data [:in-flight request-id]
                         {:reply-event reply :timeout-ms timeout-ms})
         :fx   [[:dispatch [(socket-id data)
                            [:send (assoc body :request-id request-id)]]]
                ;; The timeout event carries the live socket-id too, so the
                ;; same :current-socket? guard quietly discards a timeout left
                ;; over from a connection we've already moved past.
                [:dispatch-later
                 {:ms    timeout-ms
                  :event [:ws/connection
                          [:ws/request-timeout
                           {:request-id       request-id
                            :source-socket-id (socket-id data)}]]}]]})

      :clear-request
      (fn [{:keys [data] [_ {:keys [request-id]}] :event}]
        {:data (update data :in-flight dissoc request-id)})

      :record-and-reset
      ;; Compound action — record fresh opts AND reset the retry counter.
      ;; Used on manual :ws/connect from :reconnecting / :failed (the
      ;; running app has refreshed the token; reconnect immediately).
      (fn [{:keys [data] [_ {:keys [url auth-token]}] :event}]
        {:data (-> data
                   (assoc :url url :auth-token auth-token)
                   (assoc :retries 0))})

      :record-error
      ;; Socket-sourced error events carry a map payload — {:source-socket-id
      ;; … :error …} — so the :error rides alongside the id the guards read.
      (fn [{:keys [data] [_ {:keys [error]}] :event}] {:data (assoc data :error error)})}

     :states
     {:disconnected
      {:on {:ws/connect {:target [:active]
                         :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}
            :ws/request {:action :enqueue-message}}}

      :active
      {;; The socket actor is invoked at the parent level — its lifetime
       ;; spans :connecting, :authenticating, and :connected. Any transition
       ;; that exits :active (to :reconnecting, :failed, or :disconnected)
       ;; destroys it and clears its id from :rf/spawned; re-entering :active
       ;; spawns a fresh one.
       :spawn {:machine-id :websocket/socket
                ;; Mechanism 2 from Pattern-AsyncEffect §Parameter passing
                ;; across the boundary — the child reads URL + auth-token
                ;; from the parent's :data at spawn time. Every re-entry to
                ;; :active picks up whatever the parent's :data currently
                ;; holds, so a :refresh-token between reconnects flows in
                ;; without any extra wiring.
                :data       (fn [{snap :snapshot}]
                              {:url        (-> snap :data :url)
                               :auth-token (-> snap :data :auth-token)})}
                ;; Recording the socket-id needs no :on-spawn write and no
                ;; :exit cleanup: on every declarative :spawn the runtime binds
                ;; the newborn actor's id into THIS machine's :data under
                ;; :rf/spawned, keyed by the :spawn-bearing state's path
                ;; ([:active]) — read it via the `socket-id` helper above — and
                ;; CLEARS it automatically when the actor is torn down. First-
                ;; class :rf/spawned idiom (005 §Recording the spawned id
                ;; user-side), preferred over the older carry-the-id-back
                ;; self-dispatch.

       ;; Parent-level transitions inherited by every leaf
       ;; (per [005 §Transition resolution]). Any transport-level close during
       ;; :connecting / :authenticating / :connected routes through here.
       ;; :ws/closed is epoch-guarded like every socket-sourced event, so a
       ;; close from a socket we've already replaced can't tear down the live
       ;; connection; :on-socket-lost bumps the retry counter AND fails every
       ;; in-flight request (see above).
       :on    {:ws/closed   {:guard  :current-socket?
                             :target :reconnecting
                             :action :on-socket-lost}
               :ws/fatal    {:target :failed
                             :action :record-error}
               :ws/send     {:action :enqueue-message}
               :ws/refresh-token {:action :refresh-token}
               ;; A request issued before the connection is :connected is
               ;; queued like any other send (the whole event is buffered, so
               ;; its :request-id survives); the :connected leaf overrides
               ;; below.
               :ws/request  {:action :enqueue-message}}

       :initial :connecting

       :states
       {:connecting
        ;; Guarded like every socket-sourced lifecycle event: an :ws/opened
        ;; from a socket we've already replaced (a slow open landing after a
        ;; disconnect+reconnect) must not advance THIS connection.
        {:on {:ws/opened {:guard  :current-socket?
                          :target :authenticating}}}

        :authenticating
        {:entry :send-auth
         ;; Both auth outcomes are epoch-guarded: a straggler :ws/auth-ok from
         ;; a replaced socket must not prematurely mark us :connected, and a
         ;; straggler :ws/auth-failed must not tear a fresh attempt to :failed.
         :on    {:ws/auth-ok     {:guard  :current-socket?
                                  :target :connected}
                 :ws/auth-failed {:guard  :current-socket?
                                  :target [:failed]
                                  :action :record-error}}}

        :connected
        {:entry  :on-connected
         :always [{:guard :has-queued-messages? :action :flush-queue}]
         :on     {;; Inbound message — the :current-socket? guard suppresses a
                  ;; straggler dispatched in-flight from a prior socket whose
                  ;; destroy hadn't flushed by the time the dispatch landed. A
                  ;; body carrying a :request-id is a correlated reply; without
                  ;; one it's a server push. Either way the body flows through
                  ;; the generic :ws/handle-message handler.
                  :ws/received {:guard  :current-socket?
                                :action (fn [{:keys [data] [_ {:keys [body]}] :event}]
                                          (if-let [rid (:request-id body)]
                                            ;; Correlated reply — clear the
                                            ;; in-flight slot, hand the body to
                                            ;; :ws/handle-message, and fire the
                                            ;; registered reply event.
                                            (let [{:keys [reply-event]}
                                                  (get-in data [:in-flight rid])]
                                              {:data (update data :in-flight dissoc rid)
                                               :fx   (cond-> [[:dispatch [:ws/handle-message body]]]
                                                       reply-event
                                                       (conj [:dispatch (conj reply-event body)]))})
                                            ;; Server push — translate to a
                                            ;; named running-app event.
                                            {:fx [[:dispatch [:ws/handle-message body]]]}))}

                  ;; Override the parent's :ws/send: while :connected the
                  ;; message goes straight to the wire instead of queueing.
                  :ws/send    {:action (fn [{:keys [data] [_ msg] :event}]
                                         {:fx [[:dispatch [(socket-id data)
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
       :after  {(fn [{:keys [snapshot]}]
                  (let [{:keys [retries base-ms max-backoff-ms]} (:data snapshot)]
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

The `:websocket/socket` invoked actor is itself a small machine (or fx-backed event handler) that owns the JS `WebSocket` instance and translates `:open`, `:message`, `:error`, `:close` events into dispatches back to the parent connection machine. Every outgoing dispatch carries `:source-socket-id` (the actor's `:rf/self-id`, per [005 §Runtime stamps on the spawned actor's `:data`](005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data)) so the parent's `:current-socket?` guard can suppress messages from a prior socket if one happens to dispatch in flight as the cascade tears it down. The actor's lifetime is bound to `:active` — leaving `:active` (whether to `:reconnecting` on error or `:failed` fatally) destroys it; re-entering `:active` creates a fresh socket.

### Parameters

The connection's `:url` and `:auth-token` arrive on the `:ws/connect` event:

```clojure
(rf/dispatch [:ws/connection [:ws/connect {:url        "wss://api.example.com/ws"
                                           :auth-token (some-token)}]])
```

`:record-connection-opts` persists them into `:data`; the `:active` state's `:spawn` `:data` fn reads them out at spawn time and threads them into the child `:websocket/socket` actor. **Every reconnect re-reads `:data` at the new `:active` entry**, so a refreshed token (via `[:ws/connection [:ws/refresh-token new-token]]`) automatically flows into the next socket without re-dispatching `:ws/connect`. A full re-target (different URL) is a fresh `:ws/connect` that records the new opts and forces an `:active` re-entry.

For the canonical menu of mechanisms — event payload (used here for caller-supplied URL/token), spawn-spec `:data` fn (used between this machine and the child socket actor), and boot-time host config (when the URL is fixed by build-time config and threaded in by the boot machine) — see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

### Subscription protocol

The connection machine tracks subscribed topics in `:data :subscriptions` (a set). On entry to `:connected`, the `:on-connected` entry action (the compound `:reset-retry` + resubscribe fn above) re-issues subscribe messages for every topic in `:subscriptions` — guaranteeing subscriptions survive reconnects.

To subscribe / unsubscribe at runtime, the running app dispatches sub/unsub events the connection machine handles by updating `:subscriptions` and forwarding the wire-message:

```clojure
;; Subscribe to a topic — pure :data update + send.
:ws/subscribe
{:action (fn [{:keys [data] [_ topic] :event}]
           {:data (update data :subscriptions conj topic)
            :fx   [[:dispatch [(socket-id data) [:send {:type :subscribe :topic topic}]]]]})}
```

(Wire the slot into `:connected`'s `:on` map alongside `:ws/received` and `:ws/send`.) The exact subscribe-message wire format is application-specific; the pattern is "track in `:data`, re-issue on `:connected` entry."

### Message correlation for request-reply

Request-reply protocols carry a correlation id on every request and matching reply. The pattern, fully implemented in the worked example above:

1. **Caller dispatches `[:ws/connection [:ws/request {:request-id ..., :body ..., :reply [::handler ...], :timeout-ms 10000}]]`.**
2. **`:register-request` action** records the in-flight entry — `(:in-flight data)` gains `{request-id {:reply-event ... :timeout-ms ...}}` — forwards the body (with `:request-id` stamped) to the socket actor, and schedules a `:dispatch-later` for the timeout.
3. **`:ws/received` arrives with `{:body {:request-id ... :result ...}}`.** The `:connected` state's handler checks the connection-epoch guard (`:current-socket?`), hands the body to the generic `[:ws/handle-message body]` handler, and — when the body carries a `:request-id` — also looks up the in-flight entry, clears the slot, and dispatches the registered reply event. A body without `:request-id` is a pure server push that only routes to `:ws/handle-message`.
4. **`:ws/request-timeout` fires** if no reply arrives within the timeout window. The `:clear-request` action removes the in-flight entry; the caller's reply event never fires. (Apps that want to surface "request timed out" to the caller can do so by dispatching a per-feature error event from `:clear-request` instead.)
5. **Connection loss fails the slot.** When the live socket drops (`:ws/closed` → `:reconnecting`), `:on-socket-lost` fails every still-in-flight request — each `:reply-event` fires with `{:ok false :error :ws/connection-lost}` and `:in-flight` is cleared — so no correlation slot leaks across the reconnect and no caller hangs. Loss semantics are FAIL, not silent replay: the server may already have processed the request, so blind re-send risks double execution.

The correlation id can be any `=`-comparable value — a `(random-uuid)` is the canonical default, but per-feature `[:feature/load slug]` vectors compose with [Spec 014 §`:request-id` (internal)](014-HTTPRequests.md#request-id-internal)'s precedent. Each request-reply *over* the open socket is a Pattern-AsyncEffect interaction; the connection machine is the long-lived host that performs the correlation step Pattern-AsyncEffect leaves to the caller.

> **This correlation shape is APP-LEVEL — NOT the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope).** The `:request-id` / `:reply` / `:in-flight` vocabulary above is the *app/library's own* correlation, not the framework's uniform reply envelope. That envelope (property 9) is the lowering target of framework-**shipped** managed async surfaces (HTTP, resources/mutations, machine async work, route loaders, timers); re-frame2 does **not** ship a managed WebSocket, so there is no `:rf/reply-to` target, `:status` taxonomy, `:work/id` correlation, or `:completed-at` metadata on a per-message reply here — the reply is the app's own message body dispatched to the app's own `:reply` event. Pattern-AsyncEffect leaves correlation to the caller; an app that *wants* envelope-shaped replies for its socket messages may build that itself, but the recommended worked shape is the app-level `:in-flight` map. (The Reagent adapter integration scaffold at `implementation/adapters/reagent/test/re_frame/websocket_cljs_test.cljs` pins this boundary.)

### Heartbeat / keepalive

Use `:after` on `:connected` to schedule a periodic ping: `:after {30000 {:target :connected :action :send-ping}}` self-loops externally, re-arming the timer. If the pong does not arrive within a window, transition to `:reconnecting`. A child heartbeat machine invoked from `:connected` is cleaner for non-trivial cases.

### Server-pushed events

Server pushes (`:ws/received` events with no `:request-id`) are translated into named dispatched events the running-app handlers consume. The connection machine's role is mechanical — receive, validate the socket-id, translate, dispatch. The semantic interpretation lives in the per-feature event handlers:

```clojure
(rf/reg-event :ws/handle-message
  (fn [_ [_ {:keys [type] :as msg}]]
    (case type
      :note/created {:fx [[:dispatch [:notes/append msg]]]}
      :user/typing  {:fx [[:dispatch [:chat/typing  msg]]]}
      ...)))
```

### Re-authentication on reconnect

Token expiry across reconnects has two recovery paths, both supported by the worked machine. **Proactive**: the auth machine refreshes the token and dispatches `[:ws/connection [:ws/refresh-token new-token]]`; the `:refresh-token` action updates `:data :auth-token`; the next `:active` entry's `:spawn` `:data` fn picks up the fresh value. **Reactive**: a reconnect into `:authenticating` fails with `:ws/auth-failed` and the machine transitions to `:failed`; the auth machine observes via a `[:rf/machine <id>]` subscription (per [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub)), runs its refresh, and dispatches `[:ws/connection [:ws/connect {:url ... :auth-token new-token}]]` to re-target. Either way, refreshed credentials land in `:data` and the next `:active` entry threads them through `:spawn` `:data`.

## SSR

The connection machine **no-ops in SSR mode** — `:spawn`'s spawn fx is `:platforms #{:client}` (the WebSocket API doesn't exist server-side); `:after` timers do not schedule under SSR (per [011 §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)). The server renders the machine's current state (typically `:disconnected`) statically; the client hydrates and starts the connection on its own.

This mirrors the rule for any client-only fx: the `:platforms` metadata gates execution; the server's fx resolver silently no-ops it.

## Anti-patterns

- **Implementing reconnect logic in `setTimeout` from inside the fx-handler.** Bypasses the machine; bypasses tracing; bypasses stale-detection. Use `:after` for the backoff timer.
- **Mutating `app-db` from the `onmessage` callback directly.** The fx-handler must dispatch a named event; the event handler does the write. Same rule as Pattern-AsyncEffect.
- **Per-message machine-spawn-and-destroy.** The connection machine is long-lived. Spawning a new machine per outgoing message is structural overkill — use a single connection machine with `:in-flight` correlation tracking instead.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across message boundaries is state-machine-shaped. Use this pattern.
- **Storing the `WebSocket` object in `app-db`.** The JS `WebSocket` is not a value; it cannot serialise; it cannot survive Tool-Pair epoch replay. The `:websocket/socket` actor owns it via a host-side reference; only its id appears in `:data` — under the runtime-maintained `:rf/spawned` slot.
- **Leaking in-flight requests when the socket drops.** A request already on the wire when the connection is lost can never be answered on that socket; left in `:in-flight` it dangles forever (its timeout is stamped with the dead socket-id, so `:current-socket?` drops the timeout after reconnect and the slot never clears). Fail each on loss — fire its `:reply-event` with `{:ok false :error :ws/connection-lost}` and clear `:in-flight` (the worked example's `:on-socket-lost`) — so callers learn the outcome. FAIL, not blind replay: the server may already have processed the request, so re-sending risks double execution.
- **Anchoring the `:spawn` on `:connecting` instead of the `:active` parent.** A socket actor scoped to `:connecting` is destroyed the moment the leaf transitions to `:authenticating` — every dispatch from `:authenticating` and `:connected` then addresses a dead actor. The actor's lifetime must outlive every leaf that dispatches through it; the hierarchical parent is the natural anchor.
- **Forgetting to re-thread connection opts on reconnect.** Recording `:url` and `:auth-token` only in `:disconnected`'s `:ws/connect` handler — and never refreshing them on the `:reconnecting` → `:active` path — means a token expiry mid-session can never recover. Either store opts in `:data` (where the `:spawn` `:data` fn re-reads them on every `:active` entry — the worked example's approach) or provide an explicit `:ws/refresh-token` slot at the parent level.
- **Skipping the connection-epoch check on socket-sourced events.** Without `:current-socket?` (or equivalent) on `:ws/received` **and** on the lifecycle transitions `:ws/opened`, `:ws/auth-ok`, `:ws/auth-failed`, `:ws/closed`, a slow event from a torn-down socket can land after a reconnect and act on the fresh connection: a stale `:message` processed against the new `:in-flight` map (wrong-reply dispatch, or a slot cleared by a stale correlation id), or a stale `:ws/closed` tearing the live connection back to `:reconnecting`. The guard is one key; skipping it is the websocket equivalent of [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)'s nav-token bug.
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
