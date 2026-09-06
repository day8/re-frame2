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
:active / *             --:ws/disconnect-->      :disconnected
:reconnecting           --:after backoff-->      :active / :connecting
:reconnecting           --:always max-retries--> :failed
:failed                 --:ws/connect-->         :active / :connecting
```

Per [005 §Transition resolution — deepest-wins with parent fallthrough](005-StateMachines.md#transition-resolution--deepest-wins-with-parent-fallthrough), the doors *out* of `:active` — the `:ws/closed` drop, the `:ws/fatal` escape hatch, and the clean `:ws/disconnect` — are declared on `:active` once and inherited by every leaf, so every `:connecting`, `:authenticating`, and `:connected` exit path routes through the same parent-level transition. Each of those doors destroys the socket actor, so **each of them also settles the `:in-flight` request set on the way out** — see §Message correlation for request-reply, item 5.

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

;; The connection-epoch test itself, factored out because more than one guard
;; below needs it: is this event stamped with the id of the socket we are
;; actually using right now? A torn-down socket reads nil, so every straggler
;; from a replaced connection fails here for free.
(defn- current-socket? [data source-socket-id]
  (let [live (socket-id data)]
    (and (some? live) (= source-socket-id live))))

;; The body the machine hands a waiting `:reply-event` when IT — not the server
;; — decides a request is over: the wire went away, or the deadline elapsed.
;; `:origin :ws/local` is the machine's own stamp, and it is what makes this
;; outcome distinguishable from server bytes at the callback. A frame off the
;; network cannot reach this shape, because the machine assoc's `:origin` after
;; receipt rather than reading it off the frame. Both local producers — the
;; lost wire and the elapsed deadline — mint through here, so the two cannot
;; drift into two different failure shapes.
(defn- local-failure [request-id error]
  {:origin     :ws/local
   :request-id request-id
   :ok         false
   :error      error})

;; The in-flight half of losing the wire, shared by EVERY door out of `:active`
;; that destroys the socket — the guarded `:ws/closed` drop, the clean
;; `:ws/disconnect`, and the app-level `:ws/fatal` escape hatch. Clear every
;; `:in-flight` slot and fire each waiting `:reply-event` with the documented
;; local-failure body. Each of those requests was already on the wire that is
;; going away, so its reply can never arrive on this connection; left in
;; `:in-flight` the slot leaks forever — its timeout is stamped with the dead
;; socket-id, so `:current-socket?` drops that timeout after teardown and
;; nothing else ever clears the slot. Semantics are FAIL, not replay: the
;; server may already have processed the request, so a blind re-send risks
;; double execution. Returns an action result (`:data` + `:fx`) for the calling
;; action to build on — factoring it out is what keeps the three doors from
;; drifting apart.
(defn- fail-in-flight [data]
  {:data (assoc data :in-flight {})
   :fx   (into []
               (keep (fn [[rid {:keys [reply-event]}]]
                       (when reply-event
                         [:dispatch (conj reply-event
                                          (local-failure rid :ws/connection-lost))])))
               (:in-flight data))})

(rf/reg-machine :ws/connection
  {:doc "WebSocket connection lifecycle: disconnected → active{:connecting →
         :authenticating → :connected} → reconnecting (with backoff) → failed."}
  {:initial :disconnected
   :data    {:url            nil               ;; supplied by :ws/connect
             :cred-ref       nil               ;; OPAQUE credential reference — never the bearer itself (see §Parameters)
             :retries        0
             :max-retries    8
             :base-ms        1000              ;; initial backoff
             :max-backoff-ms 30000
             ;; No :socket-id field: the live socket's id lives in the
             ;; runtime-maintained :rf/spawned slot (read via `socket-id`),
             ;; which the runtime keeps current and clears on teardown.
             :subscriptions  #{}               ;; topics to (re-)subscribe on :connected entry
             :queue          []                ;; whole inbound events buffered while disconnected
             :in-flight      {}                ;; {request-id → {:reply-event ... :timeout-ms ... :token ...}}
             :next-token     0                 ;; ticking source for each registration's :token (see :register-request)
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
      (current-socket? data source-socket-id))

    :own-request-timeout?
    ;; The deadline gate, and like `:trusted-frame?` it asks BOTH questions a
    ;; `:ws/request-timeout` must answer before it may settle anything: is it
    ;; from the socket we own right now, and is it the timer the registration
    ;; CURRENTLY in that slot armed?
    ;;
    ;; The epoch check alone rejects timers from an old CONNECTION; it says
    ;; nothing about an old REGISTRATION on the same live socket, and that gap
    ;; needs no reconnect to open. A `:request-id` is the app's own value and
    ;; the app is invited to reuse one (see §Message correlation), so on one
    ;; long-lived socket the same id can be registered, settled, and
    ;; registered again well inside the first request's timeout window. The
    ;; first timer is still armed and still stamped with the live socket, so
    ;; it passes the epoch check, names an id the SECOND request now holds,
    ;; and settles a request whose deadline has not elapsed.
    ;;
    ;; So `:register-request` stamps each registration with a `:token` and
    ;; schedules the timeout carrying it. This is also what makes shipping no
    ;; timer-cancellation facility safe: an obsolete timer is not cancelled,
    ;; it is inert — it fires, fails this guard, and is dropped like any other
    ;; straggler.
    (fn [{:keys [data] [_ {:keys [source-socket-id request-id token]}] :event}]
      (and (current-socket? data source-socket-id)
           (when-let [entry (get-in data [:in-flight request-id])]
             (= token (:token entry)))))

    :trusted-frame?
    ;; The inbound gate. Vouching for the SENDER is not vouching for the
    ;; BYTES, and `:receive-message` clears an `:in-flight` slot the frame
    ;; itself names — so the frame is held to the closed `InboundMessage`
    ;; contract here, before any branching or state change. See §Vet the
    ;; frame before the machine acts on it. One named guard rather than an
    ;; `:and` of two — 005 ships no guard combinator data form; compound
    ;; logic goes in one guard whose NAME carries the meaning.
    (fn [{:keys [data] [_ {:keys [source-socket-id body]}] :event}]
      (and (current-socket? data source-socket-id)
           (valid-inbound-frame? body)))}      ;; a compiled InboundMessage validator

   :actions
   {:record-connection-opts
    ;; Caller passes the URL + an OPAQUE credential reference on
    ;; :ws/connect; opts land in :data and every subsequent reconnect
    ;; re-reads them via :spawn's :data fn. Never the bearer itself —
    ;; see §Parameters.
    (fn [{:keys [data] [_ {:keys [url cred-ref]}] :event}]
      {:data (assoc data :url url :cred-ref cred-ref)})

    :rotate-cred
    ;; The auth machine calls this after an out-of-band credential
    ;; rotation; only the new opaque reference crosses the dispatch
    ;; boundary, and the next :active entry's :spawn :data fn picks it up.
    (fn [{:keys [data] [_ new-cred-ref] :event}]
      {:data (assoc data :cred-ref new-cred-ref)})

    :on-socket-lost
    ;; The live socket dropped (a :ws/closed that passed :current-socket?).
    ;; Heading for :reconnecting, two things happen:
    ;;   1. Bump the retry counter — it drives the :after backoff and the
    ;;      :max-retries-exceeded? guard.
    ;;   2. FAIL every in-flight request, through the shared `fail-in-flight`
    ;;      helper above — the same half of the job the clean-disconnect and
    ;;      fatal doors do. Each waiting :reply-event fires with
    ;;      {:origin :ws/local :ok false :error :ws/connection-lost} so the
    ;;      caller learns the outcome instead of hanging forever. See the
    ;;      helper for the full leak/replay story.
    (fn [{:keys [data]}]
      (-> (fail-in-flight data)
          (update :data update :retries inc)))

    :fail-in-flight
    ;; The clean door. A :ws/disconnect out of :active destroys the socket
    ;; just as surely as a drop does, so the SAME invariant applies: every
    ;; request accepted into :in-flight settles exactly once on the way out.
    ;; Unlike :on-socket-lost there is no retry bump — the app asked for this
    ;; disconnect, so it is not a connection failure; only the requests still
    ;; riding the wire fail, with the same documented body.
    (fn [{:keys [data]}]
      (fail-in-flight data))

    :on-fatal-error
    ;; :ws/fatal is the app-level escape hatch out of :active — the app
    ;; itself declaring the connection unrecoverable (a protocol violation, a
    ;; server telling us not to come back) and going straight to :failed
    ;; without retrying. It leaves :active, so the runtime destroys the
    ;; socket just as surely as a drop or a clean disconnect does, and
    ;; recording the error is only HALF the job: without the fail-in-flight
    ;; half the slot and its waiting :reply-event survive teardown forever,
    ;; because the scheduled timeout carries the destroyed socket's id and
    ;; :current-socket? rejects it once the socket is gone. That leak
    ;; outlives a later manual :ws/connect out of :failed too, so the caller
    ;; never learns anything. No retry bump: we are giving up, not retrying.
    (fn [{:keys [data] [_ {:keys [error]}] :event}]
      (-> (fail-in-flight data)
          (update :data assoc :error error)))

    :send-auth
    ;; Route an :auth message into the live socket actor. NO token in
    ;; the payload: the actor resolved the bearer from :cred-ref inside
    ;; its own host closure at socket-open and attaches it to the wire
    ;; frame itself — the credential never rides a dispatch.
    (fn [{:keys [data]}]
      {:fx [[:dispatch [(socket-id data) [:send {:type :auth}]]]]})

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
    ;;
    ;; Three details here are about the correlation id being the APP's, and
    ;; therefore reusable. (1) The registration takes the next `:token`, which
    ;; goes into the slot AND onto the scheduled timeout, so that timer can
    ;; only ever settle THIS registration — see `:own-request-timeout?`.
    ;; (2) The same token goes ON THE WIRE as `:request-token`, and the
    ;; `ReplyMessage` arm of the inbound contract requires it back, so a
    ;; server reply can name the registration it answers rather than only the
    ;; slot it was filed under — see item 6 below.
    ;; (3) Re-registering an id that is still in flight SUPERSEDES: the
    ;; displaced caller is settled with `:ws/superseded` before the new
    ;; request goes out, so it is never stranded. See item 6 below.
    (fn [{:keys [data] [_ {:keys [request-id body reply timeout-ms]
                           :or   {timeout-ms 30000}}] :event}]
      (let [token     (:next-token data)
            displaced (:reply-event (get-in data [:in-flight request-id]))
            send+arm  [[:dispatch [(socket-id data)
                                   [:send (assoc body
                                                 :request-id    request-id
                                                 :request-token token)]]]
                       ;; The timeout event carries the live socket-id too, so
                       ;; the same epoch check quietly discards a timeout left
                       ;; over from a connection we've already moved past.
                       [:dispatch-later
                        {:ms    timeout-ms
                         :event [:ws/connection
                                 [:ws/request-timeout
                                  {:request-id       request-id
                                   :token            token
                                   :source-socket-id (socket-id data)}]]}]]]
        {:data (-> data
                   (assoc :next-token (inc token))
                   (assoc-in [:in-flight request-id]
                             {:reply-event reply
                              :timeout-ms  timeout-ms
                              :token       token}))
         ;; Settle the displaced caller FIRST — it is done either way, and it
         ;; should learn that before the wire moves on.
         :fx   (if displaced
                 (into [[:dispatch (conj displaced
                                         (local-failure request-id :ws/superseded))]]
                       send+arm)
                 send+arm)}))

    :clear-request
    ;; A request's deadline fired — a `:ws/request-timeout` that passed
    ;; `:own-request-timeout?`, so the socket is still live, the slot is still
    ;; occupied, and THIS registration is the one that armed the timer, but
    ;; the reply never came. FAIL the request, don't just forget it: clear the
    ;; in-flight slot AND fire the waiting `:reply-event` with a timeout body, so a
    ;; request that times out on a live socket learns its outcome instead of
    ;; hanging forever. This is `fail-in-flight`'s per-request twin — same
    ;; local-failure shape, scoped to the single request whose timer elapsed
    ;; rather than the whole in-flight set. (A request registered without a
    ;; `:reply` has a nil `:reply-event`, so the dispatch is guarded.)
    (fn [{:keys [data] [_ {:keys [request-id]}] :event}]
      (let [{:keys [reply-event]} (get-in data [:in-flight request-id])]
        {:data (update data :in-flight dissoc request-id)
         :fx   (cond-> []
                 reply-event (conj [:dispatch
                                    (conj reply-event
                                          (local-failure request-id
                                                         :ws/timeout))]))}))

    :record-and-reset
    ;; Compound action — record fresh opts AND reset the retry counter.
    ;; Used on manual :ws/connect from :reconnecting / :failed (the
    ;; running app has rotated its credential; reconnect immediately).
    (fn [{:keys [data] [_ {:keys [url cred-ref]}] :event}]
      {:data (-> data
                 (assoc :url url :cred-ref cred-ref)
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
              ;; across the boundary — the child reads URL + the opaque
              ;; :cred-ref from the parent's :data at spawn time (the
              ;; child resolves the reference to the real bearer inside
              ;; its own host closure — see §Parameters). Every re-entry
              ;; to :active picks up whatever the parent's :data currently
              ;; holds, so a :rotate-cred between reconnects flows in
              ;; without any extra wiring.
              :data       (fn [{snap :snapshot}]
                            {:url      (-> snap :data :url)
                             :cred-ref (-> snap :data :cred-ref)})}
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
             ;; The app-level escape hatch. It leaves :active, so it kills
             ;; the wire — and therefore settles the in-flight set on the way
             ;; out as well as recording the error (see :on-fatal-error).
             :ws/fatal    {:target :failed
                           :action :on-fatal-error}
             ;; The clean door out. It kills the wire too, so it settles the
             ;; in-flight set on the way out (see :fail-in-flight). Every
             ;; door out of :active reachable from :connected — this one, the
             ;; guarded :ws/closed drop and :ws/fatal — settles :in-flight
             ;; exactly once.
             :ws/disconnect {:target :disconnected
                             :action :fail-in-flight}
             :ws/send     {:action :enqueue-message}
             :ws/rotate-cred {:action :rotate-cred}
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
               ;; This door leaves :active too, but it needs no in-flight
               ;; settlement — plain :record-error is correct here, not an
               ;; oversight. :register-request is the only writer into
               ;; :in-flight and it is bound only on :connected, so reaching
               ;; :authenticating means :in-flight is provably empty.
               :ws/auth-failed {:guard  :current-socket?
                                :target [:failed]
                                :action :record-error}}}

      :connected
      {:entry  :on-connected
       :always [{:guard :has-queued-messages? :action :flush-queue}]
       :on     {;; Inbound frame. TWO candidates, first-match-wins: a frame
                ;; must clear both halves of the gate — :current-socket?
                ;; (not a straggler from a socket we have replaced) AND the
                ;; closed InboundMessage wire contract — before this machine
                ;; acts on it. See §Vet the frame before the machine acts on
                ;; it for why the app-db ingress check downstream is not
                ;; sufficient on its own.
                :ws/received [{:guard  :trusted-frame?
                               :action (fn [{:keys [data] [_ {:keys [body]}] :event}]
                                         ;; Branch on the VETTED :type, not on
                                         ;; "is there a :request-id?". Then
                                         ;; correlate on the REGISTRATION: the
                                         ;; echoed :request-token must still be
                                         ;; the one in the slot, or this reply
                                         ;; answers a registration that is no
                                         ;; longer there (item 6).
                                         (if (= :reply (:type body))
                                           (let [rid   (:request-id body)
                                                 entry (when-let [e (get-in data [:in-flight rid])]
                                                         (when (= (:request-token body) (:token e)) e))]
                                             (cond-> {:fx [[:dispatch [:ws/handle-message body]]]}
                                               entry
                                               (assoc :data (update data :in-flight dissoc rid))
                                               (:reply-event entry)
                                               ;; :origin is the machine's own
                                               ;; stamp — see §Message correlation.
                                               (update :fx conj
                                                       [:dispatch (conj (:reply-event entry)
                                                                        (assoc body :origin :ws/server))])))
                                           ;; Server push — translate to a
                                           ;; named running-app event.
                                           {:fx [[:dispatch [:ws/handle-message body]]]}))}
                              ;; Failed the wire contract: hand it to the
                              ;; ingress, which owns refusal, and change
                              ;; nothing. Note the absent :data key.
                              {:guard  :current-socket?
                               :action (fn [{[_ {:keys [body]}] :event}]
                                         {:fx [[:dispatch [:ws/handle-message body]]]})}]

                ;; Override the parent's :ws/send: while :connected the
                ;; message goes straight to the wire instead of queueing.
                :ws/send    {:action (fn [{:keys [data] [_ msg] :event}]
                                       {:fx [[:dispatch [(socket-id data)
                                                         [:send msg]]]]})}

                ;; Override the parent's :ws/request: while :connected
                ;; the request is registered + sent immediately.
                :ws/request {:action :register-request}

                ;; A deadline elapsed. Two questions, one guard: is the socket
                ;; still ours, and is this the timer the registration
                ;; currently in the slot armed? See :own-request-timeout?.
                :ws/request-timeout
                {:guard  :own-request-timeout?
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
     :on     {;; Manual reconnect (e.g., after the auth machine rotates
              ;; the credential) — short-circuit the backoff, record fresh
              ;; opts, zero the retry counter, re-enter :active.
              :ws/connect {:target [:active]
                           :action :record-and-reset}
              :ws/send    {:action :enqueue-message}
              :ws/request {:action :enqueue-message}
              :ws/rotate-cred {:action :rotate-cred}}}

    :failed
    {:on {:ws/connect     {:target [:active]
                           :action :record-and-reset}
          :ws/rotate-cred {:action :rotate-cred}}}}})
```

The `:websocket/socket` invoked actor is itself a small machine (or fx-backed event handler) that owns the JS `WebSocket` instance and translates `:open`, `:message`, `:error`, `:close` events into dispatches back to the parent connection machine. It is also where the opaque `:cred-ref` becomes a real credential: the actor resolves the reference inside its own host closure **at the auth write**, attaches the bearer to that wire frame, and lets it go out of scope in the same expression — the bearer never enters machine `:data` or a dispatch (see §Parameters). Resolve it in the enclosing scope instead and the socket handle, which is retained for the socket's whole life, closes over the bearer for just as long: a live credential parked host-side that no snapshot sweep can see. Every outgoing dispatch carries `:source-socket-id` (the actor's `:rf/self-id`, per [005 §Runtime stamps on the spawned actor's `:data`](005-StateMachines.md#runtime-stamps-on-the-spawned-actors-data)) so the parent's `:current-socket?` guard can suppress messages from a prior socket if one happens to dispatch in flight as the cascade tears it down. The actor's lifetime is bound to `:active` — leaving `:active` (whether to `:reconnecting` on error or `:failed` fatally) destroys it; re-entering `:active` creates a fresh socket.

### Parameters

The connection's `:url` and an **opaque credential reference** arrive on the `:ws/connect` event:

```clojure
(rf/dispatch [:ws/connection [:ws/connect {:url      "wss://api.example.com/ws"
                                           :cred-ref (current-session-cred-ref)}]])
```

`:cred-ref` is a REFERENCE — a session id, a vault index, any opaque key your auth slice issues — never the bearer itself. Machine `:data` is framework-inspectable (snapshots, trace emissions, recorder fixtures, pair tooling), so a raw bearer, cookie, or refresh token must never enter `:data` or ride a dispatch payload. The socket actor exchanges the reference for the real credential inside its own host closure at the moment it authenticates the socket — the worked example's `resolve-credential` seam in `examples/patterns/websocket/messages.cljs` — writes it to the auth wire frame, and lets it go out of scope there. "At the write" is narrower than "while opening the socket", and deliberately so: anything the socket's enclosing scope resolves is retained by the socket handle for the connection's lifetime.

`:record-connection-opts` persists URL + reference into `:data`; the `:active` state's `:spawn` `:data` fn reads them out at spawn time and threads them into the child `:websocket/socket` actor. **Every reconnect re-reads `:data` at the new `:active` entry**, so a rotated credential (via `[:ws/connection [:ws/rotate-cred new-cred-ref]]`, carrying only the new reference) automatically flows into the next socket without re-dispatching `:ws/connect`. A full re-target (different URL) is a fresh `:ws/connect` that records the new opts and forces an `:active` re-entry.

For the canonical menu of mechanisms — event payload (used here for the caller-supplied URL and opaque `:cred-ref`), spawn-spec `:data` fn (used between this machine and the child socket actor), and boot-time host config (when the URL is fixed by build-time config and threaded in by the boot machine) — see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

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
2. **`:register-request` action** records the in-flight entry — `(:in-flight data)` gains `{request-id {:reply-event ... :timeout-ms ... :token ...}}` — forwards the body (with **both** `:request-id` and `:request-token` stamped) to the socket actor, and schedules a `:dispatch-later` for the timeout, carrying the same `:token`. Re-registering an id that is still in flight supersedes the earlier registration and settles its caller; every one of those halves is item 6.
3. **`:ws/received` arrives with `{:body {:type :reply :request-id ... :request-token ... :ok ...}}`.** The `:connected` state's `:trusted-frame?` guard checks the connection epoch **and** the closed wire contract; only then does the handler branch on the vetted `:type`. A `:reply` looks up the in-flight entry, checks that the echoed `:request-token` is still the slot's `:token`, clears the slot, and dispatches the registered reply event with the machine's `:origin :ws/server` stamp added; a `:push` only routes to `[:ws/handle-message body]`. Branch on `:type`, not on the presence of a `:request-id` — the frame's declared kind is part of the contract you just checked, whereas presence-of-a-key is a shape test a widened arm re-opens.
4. **`:ws/request-timeout` fires** if no reply arrives within the timeout window. The `:own-request-timeout?` guard admits it only if the socket is still the live one **and** the slot still holds the registration that armed this timer (item 6); the `:clear-request` action then removes the in-flight entry **and fires the caller's `:reply-event`** with `{:origin :ws/local :ok false :error :ws/timeout}` — the per-request twin of the loss body in item 5, minted through the same `local-failure` helper. A timeout is a terminal outcome, so the caller must learn it: clearing the slot silently would leave a request that timed out on a *live* socket waiting forever, with no reply and no timer left to fire. The socket is still up, so this is the one door where nothing else will ever settle the slot.
5. **Losing the wire fails the slot — by every door, not only the drop.** A request accepted into `:in-flight` settles exactly once, and it settles whenever the socket goes away rather than only when it is lost underneath us. Every transition that exits `:active` destroys the socket actor, so all three doors run the shared `fail-in-flight` helper: the guarded `:ws/closed` drop (`:on-socket-lost`, which also bumps the retry counter), the clean `:ws/disconnect` (`:fail-in-flight`), and the app-level `:ws/fatal` escape hatch (`:on-fatal-error`, which also records the error). Each still-in-flight `:reply-event` fires with `{:origin :ws/local :ok false :error :ws/connection-lost}` and `:in-flight` is cleared, so no correlation slot leaks and no caller hangs. Semantics are FAIL, not silent replay: the server may already have processed the request, so blind re-send risks double execution. (`:ws/auth-failed` is the exception that proves the rule — `:register-request` is bound only on `:connected`, so `:in-flight` is provably empty by the time that door can be taken, and plain `:record-error` is correct there.)
6. **Correlate every settlement to the REGISTRATION, not to the id — and say what a duplicate id does.** Items 4 and 5 promise every accepted request a terminal outcome, exactly once, and *about the request that asked*. The correlation id alone cannot keep that promise, because the id is the app's and the app is invited to reuse one (the `[:feature/load slug]` shape below is a *recommendation*, not a hazard). So each registration takes a **`:token`** from `:data`'s `:next-token` counter — a counter rather than a fresh uuid, because the value lands in durable machine state and must replay identically — and **both** things that can settle a slot are held to it.

    **The deadline.** On one long-lived socket, request A under id `R` can complete and request B re-register `R` well inside A's timeout window: A's timer is still armed and still stamped with the live socket, so the epoch check passes it, and it then deletes B's slot and hands B's caller a `:ws/timeout` its deadline never reached. The epoch check rejects timers from an old *connection*; nothing in it speaks to an old *registration* on the same live socket. The scheduled timeout therefore carries the token, and `:own-request-timeout?` admits it only while that token is still the one in the slot. An obsolete timer needs no cancellation facility: it fires, fails the guard, and is dropped like any other straggler.

    **The wire reply — the same gap, one door further out.** Fixing only the deadline leaves the server's reply correlating by id, and the server is the one participant that cannot see the machine's `:data`. A is pending under `R`, B supersedes it, and now *both* requests are on the wire carrying the identical correlation value; when A's reply arrives first it finds B's slot, clears it, and hands B's caller A's body — one callback, on time, about the wrong request, and B's own reply arrives later as unsolicited. A caller told the wrong answer confidently is worse off than one told nothing. So the token goes **on the wire** too, as `:request-token` beside `:request-id`, the `ReplyMessage` arm of the inbound contract **requires it back**, and the `:reply` branch settles the slot only while the echoed token is still the slot's. A mismatch is not an error condition — it is an answer to a registration that is no longer here, which is exactly what an unsolicited reply is, and it is treated as one: nothing is cleared, no `:reply-event` fires, and the frame goes to the inbox like any other vetted frame. **The token is a correctness discriminator, not a secret** — the peer was handed it in the request, and guessing it buys nothing that naming the `:request-id` does not already buy; provenance is `:origin`'s job (see below), stamped after receipt precisely because no wire field can carry that weight.

    **A server that does not echo the token fails the wire contract**, which is where a wire requirement belongs: the frame takes the refusal candidate, nothing in the connection moves, the ingress mints the one canonical `:rf.error/schema-validation-failure` naming the missing key on the very first reply, and the request settles on its own deadline. That is loud and diagnosable on contact, where the alternative — admitting an untokened reply and quietly correlating it by id — restores the ambiguity the field exists to remove. An app whose server genuinely cannot echo the field drops it from `ReplyMessage` **and** from `:register-request`, and then owes its correlation ids uniqueness for the socket's lifetime; what it must not do is reuse ids without a discriminator.

    **The duplicate-in-flight policy is last-write-wins with the displaced caller settled:** re-registering an id that is still pending fires the earlier registration's `:reply-event` with `{:origin :ws/local :ok false :error :ws/superseded}` before the new request goes out. A reusable per-feature id is a *slot*, and re-issuing into it is the app saying the earlier question is obsolete; refusing the newcomer instead would make the recommended per-feature id unusable for the case it is recommended for, and silently overwriting the entry — which is what a plain `assoc-in` does — strands the first caller forever. The displaced registration leaves two things behind, and the token makes both inert: its armed timer, and the reply its request will still draw from the server.

**Two producers reach the reply event, so say which is which — with a key the sender cannot set.** A correlated wire reply and a locally minted loss/timeout failure both arrive at the caller's `:reply-event`, and they are different facts: one is the server's claim, the other is the machine's own truth about the connection. Discriminating between them by *shape* hands the choice to the sender, because a hostile server that has seen the wire request id can send back whatever shape the "local failure" arm describes and have its frame recorded as a connection fact the app minted itself. So the machine **stamps** `:origin` — `:ws/server` on the wire body as it hands it on, `:ws/local` on the bodies it mints — and the outcome schema is a closed union dispatching on that stamp. Stamped after receipt, `:origin` is not forgeable from the wire.

The correlation id can be any `=`-comparable value — a `(random-uuid)` is the canonical default, but per-feature `[:feature/load slug]` vectors compose with [Spec 014 §`:request-id` (internal)](014-HTTPRequests.md#request-id-internal)'s precedent. Uniqueness over a socket's lifetime is explicitly **not** required of it, which is why the machine correlates by registration token rather than by id on *both* settling paths — the local deadline and the server's reply (item 6); a design that quietly depends on unique ids has made the reusable per-feature shape unsafe without saying so. Each request-reply *over* the open socket is a Pattern-AsyncEffect interaction; the connection machine is the long-lived host that performs the correlation step Pattern-AsyncEffect leaves to the caller.

> **This correlation shape is APP-LEVEL — NOT the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope).** The `:request-id` / `:request-token` / `:reply` / `:in-flight` vocabulary above is the *app/library's own* correlation, not the framework's uniform reply envelope. `:request-token` in particular is an ordinary unqualified app key on the app's own wire frame — reach for the envelope's `:work/id` here and you have folded an exempt surface into a contract it was ruled out of. That envelope (property 9) is the lowering target of framework-**shipped** managed async surfaces (HTTP, resources/mutations, machine async work, route loaders, timers); re-frame2 does **not** ship a managed WebSocket, so there is no `:rf/reply-to` target, `:status` taxonomy, `:work/id` correlation, or `:completed-at` metadata on a per-message reply here — the reply is the app's own message body dispatched to the app's own `:reply` event. Pattern-AsyncEffect leaves correlation to the caller; an app that *wants* envelope-shaped replies for its socket messages may build that itself, but the recommended worked shape is the app-level `:in-flight` map. (The Reagent adapter integration scaffold at `implementation/adapters/reagent/test/re_frame/websocket_cljs_test.cljs` pins this boundary.)

### Heartbeat / keepalive

Use `:after` on `:connected` to schedule a periodic ping: `:after {30000 {:target :connected :action :send-ping}}` self-loops externally, re-arming the timer. If the pong does not arrive within a window, transition to `:reconnecting`. A child heartbeat machine invoked from `:connected` is cleaner for non-trivial cases.

### Server-pushed events

Server pushes (`:ws/received` events whose vetted `:type` is a push kind) are translated into named dispatched events the running-app handlers consume. The connection machine's role is mechanical — receive, vet, translate, dispatch. *Semantic* interpretation lives in the receiving event handler, which is where the ingress check that guards `app-db` belongs:

```clojure
;; The wire shape the server is allowed to push. A closed `:multi` with no
;; default arm is what makes an unrecognised `:type` a rejection rather than a
;; `case` fall-through inside the handler body.
(def InboundMessage
  [:multi {:dispatch :type}
   [:note/created [:map [:type [:= :note/created]] [:note-id :uuid] [:body [:string {:max 4096}]]]]
   [:user/typing  [:map [:type [:= :user/typing]]  [:user-id :uuid]]]])

(rf/reg-event :ws/handle-message
  {:doc          "Interpret one inbound frame. UNTRUSTED INGRESS — see below."
   :schema       [:cat [:= :ws/handle-message] InboundMessage]
   :interceptors [:rf.schema/at-boundary]}     ;; forces the check into the release build
  (fn [_ [_ {:keys [type] :as msg}]]
    (case type
      :note/created {:fx [[:dispatch [:notes/append msg]]]}
      :user/typing  {:fx [[:dispatch [:chat/typing  msg]]]})))
```

### Inbound frames are untrusted, and `:schema` alone will not check them

A frame arrives from the network, and on a compromised or hostile server it carries whatever the sender chose. That makes `:ws/handle-message` a system boundary in the same sense an HTTP response is, and the boundary needs a check that is still in the release bundle.

A bare `:schema` is not that check. It is an ordinary registration diagnostic — it asserts that the code *you* wrote produced what you meant — so it elides under `:advanced` + `goog.DEBUG=false` ([010 §Production builds](010-Schemas.md#production-builds)), and a malformed frame flows straight into the handler body on the one build where it matters. `:rf.schema/at-boundary` reads that same declaration at a checkpoint the framework keeps in every build, because refusing a malformed payload at an untrusted ingress is a promise the framework made, and what may be elided is settled by what the check is *for* rather than by who declared the schema it reads ([C-000.35](000-Vision.md#contract--pattern-obligations)). One interceptor reference on one handler is the whole change; there is no second schema to write and nothing to maintain in parallel.

**The interceptor is a better fit here than it is on a form POST.** [Pattern-FormAction §Validation is the handler's job](Pattern-FormAction.md#validation-is-the-handlers-job) reaches the opposite conclusion for an HTML form action, and the difference is what each surface owes the sender. A form owes the user their page back — populated fields, errors beside them — and a skipped handler composes nothing, so there the branch has to be handler code. A frame owes nobody a page. Dropping it *is* the complete answer: the handler never runs so nothing reaches `app-db`, one always-on structural `:rf.error/schema-validation-failure` record is fanned (`:source :boundary`, identifiers only — no offending value, because the payload is attacker-controlled by definition), and the event-emit record settles `:outcome :rejected`, which is the counter to alert on for hostile input ([009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)).

**Put it at the ingress, not on everything downstream.** `:ws/handle-message` is where the wire crosses into the app; `[:notes/append msg]` and `[:chat/typing msg]` carry a value the app has already accepted, so their own `:schema` is an ordinary dev tripwire like any other and should stay one. The correlated request-reply path is the second ingress: a reply body arriving under a known `:request-id` is still the server's bytes, so a `:reply` event that writes it into `app-db` wants the same treatment as the push path.

### Vet the frame before the machine acts on it

`app-db` is not the only state an inbound frame reaches. The connection machine reads the frame to decide what it *is*, and on a correlated reply it **clears an `:in-flight` slot the frame itself named**. That is durable machine state changing on the say-so of bytes nobody has checked yet — and the ingress check downstream cannot undo it. A hostile frame carrying a pending `:request-id` and a malformed body is refused at `:ws/handle-message`, `app-db` never moves, every rejection counter fires as designed — and the caller's request has been silently consumed, with no reply and no timeout left to fire, because its slot is gone. The check that would have caught it ran one step too late.

So the same closed `InboundMessage` contract is applied **in the machine's guard**, ahead of any branching or state change: `:trusted-frame?` above asks both questions — right socket, and a frame the contract admits — and a frame that fails takes a second candidate whose action returns no `:data` at all. Enforcing at both places is not redundancy to trim. They protect different state and they answer to different owners: the guard protects the machine's correlation bookkeeping, the ingress protects `app-db`, and a boundary that holds only because something upstream is careful is not a boundary. Wiring the frame that failed the guard through to the ingress anyway keeps refusal in one place — the ingress owns the `:rf.error/schema-validation-failure` record, so the machine does not mint a second rejection vocabulary beside the framework's.

**Close the arms, too.** A `:multi` with open arms is only half-closed: an open `:push` arm accepts `{:type :push … :request-id <a live one>}`, which passes the wire contract under a kind that has nothing to do with request-reply and then reaches whatever the machine does with a `:request-id`. Closing each arm (`[:map {:closed true} …]`) is what makes "the contract says which fields a push has" mean it.

### Re-authentication on reconnect

Credential expiry across reconnects has two recovery paths, both supported by the worked machine. **Proactive**: the auth machine refreshes the credential host-side and dispatches `[:ws/connection [:ws/rotate-cred new-cred-ref]]` carrying only the opaque reference; the `:rotate-cred` action updates `:data :cred-ref`; the next `:active` entry's `:spawn` `:data` fn picks up the fresh reference and the new socket resolves it to the new bearer. **Reactive**: a reconnect into `:authenticating` fails with `:ws/auth-failed` and the machine transitions to `:failed`; the auth machine observes via a `[:rf/machine <id>]` subscription (per [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub)), runs its refresh, and dispatches `[:ws/connection [:ws/connect {:url ... :cred-ref new-cred-ref}]]` to re-target. Either way, only the opaque reference lands in `:data`; the bearer stays host-side, resolved by the spawning socket's own closure.

## SSR

The connection machine **no-ops in SSR mode** — `:spawn`'s spawn fx is `:platforms #{:client}` (the WebSocket API doesn't exist server-side); `:after` timers do not schedule under SSR (per [011 §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)). The server renders the machine's current state (typically `:disconnected`) statically; the client hydrates and starts the connection on its own.

This mirrors the rule for any client-only fx: the `:platforms` metadata gates execution; the server's fx resolver silently no-ops it.

## Anti-patterns

- **Implementing reconnect logic in `setTimeout` from inside the fx-handler.** Bypasses the machine; bypasses tracing; bypasses stale-detection. Use `:after` for the backoff timer.
- **Mutating `app-db` from the `onmessage` callback directly.** The fx-handler must dispatch a named event; the event handler does the write. Same rule as Pattern-AsyncEffect.
- **Per-message machine-spawn-and-destroy.** The connection machine is long-lived. Spawning a new machine per outgoing message is structural overkill — use a single connection machine with `:in-flight` correlation tracking instead.
- **Treating WebSocket as Pattern-AsyncEffect.** A connection that retries, reconnects, and survives across message boundaries is state-machine-shaped. Use this pattern.
- **Storing the `WebSocket` object in `app-db`.** The JS `WebSocket` is not a value; it cannot serialise; it cannot survive Tool-Pair epoch replay. The `:websocket/socket` actor owns it via a host-side reference; only its id appears in `:data` — under the runtime-maintained `:rf/spawned` slot.
- **Leaking in-flight requests on a door out of `:active` — and fixing only the drop.** A request already on the wire when the socket goes away can never be answered on that socket; left in `:in-flight` it dangles forever (its timeout is stamped with the dead socket-id, so `:current-socket?` drops the timeout after teardown and the slot never clears). The obvious version of the bug is missing it on the `:ws/closed` drop. The version that survives review is repairing that one door and leaving the others: a clean `:ws/disconnect` and an app-level `:ws/fatal` destroy the socket just as surely, and a `:ws/fatal` leak outlives even a later manual `:ws/connect` out of `:failed`, so the caller never learns anything. Route **every** exit from `:active` through one shared fail-and-clear — the worked example's `fail-in-flight`, composed by `:on-socket-lost`, `:fail-in-flight` and `:on-fatal-error` — firing each `:reply-event` with `{:origin :ws/local :ok false :error :ws/connection-lost}` and clearing `:in-flight`, so callers learn the outcome. FAIL, not blind replay: the server may already have processed the request, so re-sending risks double execution.
- **Anchoring the `:spawn` on `:connecting` instead of the `:active` parent.** A socket actor scoped to `:connecting` is destroyed the moment the leaf transitions to `:authenticating` — every dispatch from `:authenticating` and `:connected` then addresses a dead actor. The actor's lifetime must outlive every leaf that dispatches through it; the hierarchical parent is the natural anchor.
- **Storing a raw bearer / cookie / refresh token in machine `:data`, or dispatching one through the machine.** `:data` is framework-inspectable — app-db snapshots, trace emissions, recorder fixtures, pair tooling — so a credential held there is liable to be serialised somewhere nobody inspects character-by-character. Carry an opaque `:cred-ref` and resolve it to the real bearer inside the socket actor's host closure at authentication time (see §Parameters).
- **Forgetting to re-thread connection opts on reconnect.** Recording `:url` and `:cred-ref` only in `:disconnected`'s `:ws/connect` handler — and never refreshing them on the `:reconnecting` → `:active` path — means a credential expiry mid-session can never recover. Either store opts in `:data` (where the `:spawn` `:data` fn re-reads them on every `:active` entry — the worked example's approach) or provide an explicit `:ws/rotate-cred` slot at the parent level.
- **Skipping the connection-epoch check on socket-sourced events.** Without `:current-socket?` (or equivalent) on `:ws/received` **and** on the lifecycle transitions `:ws/opened`, `:ws/auth-ok`, `:ws/auth-failed`, `:ws/closed`, a slow event from a torn-down socket can land after a reconnect and act on the fresh connection: a stale `:message` processed against the new `:in-flight` map (wrong-reply dispatch, or a slot cleared by a stale correlation id), or a stale `:ws/closed` tearing the live connection back to `:reconnecting`. The guard is one key; skipping it is the websocket equivalent of [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)'s nav-token bug.

- **Correlating a request timeout by id alone — and treating the epoch check as if it covered that.** The connection epoch answers "is this from the socket we are using?", which reads like the whole staleness question and is only half of it. A `:request-id` is the app's own value and may be reused; on one live socket a completed request's uncancelled timer can arrive naming an id a *later* request now holds, sail through the epoch check, delete that request's slot and hand its caller a timeout its deadline never reached. Stamp each registration with a token, put it on the scheduled timeout, and admit the timeout only while that token still occupies the slot — see item 6 of §Message correlation for request-reply. The same paragraph is where the duplicate-in-flight policy belongs: a second registration under a live id must not `assoc-in` over the first and strand its caller.
- **Fixing that for the timer and leaving the SERVER's reply correlating by id.** The registration token is easy to read as a fact about deadlines, because that is the door the bug came through — and then the reply path keeps matching on the slot alone. It is the same gap: reuse an id and two requests ride the wire under one correlation value, so the first reply back settles whichever registration currently holds the slot, and the caller of a *different* request is handed that body as its answer. The callback count stays at exactly one, which is what makes this the quieter half — the outcome is on time, well-formed, and about the wrong question. Put the token on the wire as well, require it back in the closed reply arm, and settle only on a match; a mismatch is an unsolicited reply, not an error.
- **Hardcoding the wire format in the pattern.** EDN, JSON, MessagePack, Protobuf — the connection machine doesn't care. The `:websocket/socket` actor serialises on send and deserialises on receive; the machine sees plain Clojure values.
- **Declaring a `:schema` on the receiving handler and calling the ingress guarded.** That schema is a development diagnostic and elides, so on the build facing the real network there is no check at all. Add `:rf.schema/at-boundary` to the handler's `:interceptors` — see [§Inbound frames are untrusted](#inbound-frames-are-untrusted-and-schema-alone-will-not-check-them).
- **Letting the connection machine branch on the frame — or clear a correlation slot — before the frame has been vetted.** Checking only at the `app-db` ingress leaves the machine's own `:in-flight` map reachable by bytes the ingress will go on to refuse: the frame is rejected, `app-db` is untouched, the rejection counter fires, and the caller's request has still been silently consumed with no reply and no surviving timeout. Apply the wire contract in the transition guard, ahead of the branch — see [§Vet the frame before the machine acts on it](#vet-the-frame-before-the-machine-acts-on-it).
- **Discriminating a locally minted outcome from a server reply by shape.** If the "the connection failed" arm of your reply contract is recognised by its fields, a server that has seen the wire request id can send those fields and have its frame recorded as the app's own connection fact. Stamp provenance on receipt (`:origin`) and dispatch the union on the stamp — see [§Message correlation for request-reply](#message-correlation-for-request-reply).

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
- [010-Schemas §Production builds](010-Schemas.md#production-builds) — why the receiving handler's `:schema` needs `:rf.schema/at-boundary` beside it.
- [Pattern-FormAction §Validation is the handler's job](Pattern-FormAction.md#validation-is-the-handlers-job) — the other untrusted-ingress pattern, which reaches the opposite conclusion for the opposite reason.
