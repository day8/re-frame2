(ns websocket.connection
  "The `:ws/connection` machine — the heart of this example.

   A WebSocket lifecycle, drawn as states:

     :disconnected
     :active                              ;; compound parent; owns the socket
       :connecting
       :authenticating
       :connected
     :reconnecting                        ;; backs off, then retries
     :failed                              ;; given up, until a manual :ws/connect

   Why nest the three happy-path leaves inside `:active` instead of laying
   everything out flat? Because they share one thing they can't live
   without: the live socket. The subscription set, the in-flight requests,
   the offline queue — all of it belongs to *one* connection and rides a
   single `:data` map. A compound state is how you say 'these share a
   lifecycle'. (Parallel regions are the opposite tool, for axes that don't
   share data.)
   See docs/machines/concepts.md#when-the-machine-grows.

   The view never matches on state names. Each state carries tags, and the
   view asks tag-shaped questions — `:ws/connected?`, `:ws/reconnecting?`
   (the per-tag subs below, each chaining off the framework
   `:rf.machine/has-tag?` sub). It doesn't care which leaf carries the
   `:connected` intent, only that the tag is present. Ask, don't tell.
   See docs/machines/glossary.md#state-tag.

   Reconnects bring two ways to be fooled by something stale, and we guard
   against both:

     1. A late backoff timer. Leaving `:reconnecting` cancels its `:after`
        timer, so a timer armed on an earlier visit can't wander in and
        fire after we've moved on.
        See docs/machines/concepts.md#guards-actions-tags-and-after--the-recognition-kit.

     2. A late message. The live socket-actor id *is* the connection's
        clock: every inbound event names the socket it came from, and the
        `:current-socket?` guard drops anything from a socket we've since
        replaced — the slow message that lands just after a reconnect.
        This covers the whole socket-sourced surface — the lifecycle
        transitions (`:ws/opened`, `:ws/auth-ok`, `:ws/auth-failed`,
        `:ws/closed`) as much as `:ws/received` and the request timeout — so
        a straggler from a dead socket can neither advance nor tear down the
        new connection.

   Vouching for the SENDER is not vouching for the BYTES, though, and
   `:ws/received` needs both. The `:trusted-frame?` guard asks the second
   question too — does this frame satisfy the closed `InboundMessage` wire
   contract? — because `:receive-message` clears an `:in-flight` slot
   named by the frame itself. Validating only at the app-db ingress
   downstream would leave that slot consumable by a frame the ingress goes
   on to refuse: app-db stays clean, and the caller waits forever for a
   reply whose correlation someone else already spent. A frame that fails
   takes the `:refuse-frame` candidate instead, which moves nothing.

   The thing that actually owns the JS WebSocket is the `:websocket/socket`
   actor, over in `websocket.messages`. We spawn it on the `:active`
   *parent*, so one socket spans `:connecting` -> `:authenticating` ->
   `:connected` without re-spawning on every leaf transition.

   Where does the parent learn the spawned actor's id? It doesn't have to
   ask. Every declarative `:spawn` binds the newborn's id into the SPAWNING
   machine's own `:data` under the reserved `:rf/spawned` map, keyed by the
   `:spawn`-bearing state's path — here `[:active]`. So the id is simply
   `(get-in data [:rf/spawned [:active]])`, and `socket-id` (below) reads it.
   This is re-frame2's spelling of XState v5's `spawn(...)`-into-`context`
   capture — no `:on-spawn` self-dispatch, no side-channel atom.
   See docs/machines/actors.md#recording-the-spawned-id and
   docs/machines/glossary.md#spawn.

   And because the runtime CLEARS that slot the instant the actor is torn
   down (leaving `:active` by any door), a stale read is impossible: the
   moment the socket dies, `(socket-id data)` goes `nil` on its own. That is
   what makes it a safe connection clock — THIS machine needs no `:exit`
   action to null the id out. (The host socket itself is another matter: a
   live `WebSocket` is not a value the runtime can drop, so the SPAWNED actor
   carries an `:exit :close-socket` to close it — see `websocket.messages`.
   The id auto-clears; the socket the id points at does not.)"
  (:require [re-frame.core :as rf]
            ;; `re-frame.machines` ships in day8/re-frame2-machines.
            ;; Requiring it is what wires up the machine vocabulary: the
            ;; late-bind hook behind `re-frame.machines/make-machine-handler`,
            ;; the `:rf.machine/spawn` / `:rf.machine/destroy` fx, and the
            ;; `:rf/machine` / `:rf.machine/has-tag?` subs you'll see used
            ;; below. Skip the require and they simply aren't there.
            [re-frame.machines]
            [re-frame.fx]
            [websocket.schema :as schema]))

;; ============================================================================
;; CONNECTION MACHINE — :ws/connection
;; ============================================================================

;; The socket actor is spawned on the `:active` parent state, so the id the
;; runtime binds into our `:data :rf/spawned` map is keyed by that state's
;; path. Naming the key once keeps the reads below honest.
(def ^:private socket-invoke-id [:active])

(defn- socket-id
  "The live socket-actor id, read straight from the parent's own `:data`
   under the framework-maintained `:rf/spawned` slot. Returns nil whenever
   no socket is spawned — before the first `:active` entry, and (crucially)
   the moment the actor is torn down, because the runtime clears the slot on
   teardown. That auto-clear is why this doubles as the connection's clock."
  [data]
  (get-in data [:rf/spawned socket-invoke-id]))

(defn- from-live-socket?
  "The connection-epoch test, factored out because two guards below need
   it: is this event stamped with the id of the socket we are actually
   using right now? A torn-down socket reads nil, so every straggler from
   a replaced connection fails here for free."
  [data source-socket-id]
  (let [live (socket-id data)]
    (and (some? live)
         (= source-socket-id live))))

(defn- local-failure
  "The body the machine hands a waiting `:reply-event` when IT — not the
   server — decides a request is over: the wire went away, or the deadline
   elapsed. `:origin :ws/local` is the machine's own stamp, and it is what
   makes this outcome distinguishable from server bytes at the callback
   (see `schema/RequestOutcome`). A frame off the network cannot reach
   this shape, because the machine assoc's `:origin` after receipt rather
   than reading it off the frame."
  [request-id error]
  {:origin     :ws/local
   :request-id request-id
   :ok         false
   :error      error})

(defn- fail-in-flight
  "The in-flight half of losing the wire, shared by every door out of
   `:active` that destroys the socket: clear every `:in-flight` slot and
   fire each waiting `:reply-event` with the documented
   `{:ok false :error :ws/connection-lost}` failure body. Each of those
   requests was already on the wire that is going away, so its reply can
   never arrive on this connection; left in `:in-flight` the slot would
   leak forever — its timeout is stamped with the dead socket id, so
   `:current-socket?` drops that timeout after teardown and the slot never
   clears. Termination semantics are FAIL, not replay: the server may
   already have processed the request, so a blind re-send risks double
   execution. Failing is the safe, explicit default, and the caller learns
   the outcome instead of hanging. Returns an action result (`:data` +
   `:fx`) for the calling action to build on."
  [data]
  {:data (assoc data :in-flight {})
   :fx   (into []
               (keep (fn [[rid {:keys [reply-event]}]]
                       (when reply-event
                         [:dispatch (conj reply-event
                                          (local-failure rid :ws/connection-lost))])))
               (:in-flight data))})

(rf/defmachine connection-machine
  "The `:ws/connection` machine spec, kept in its own `defmachine` so we can
   hand it to `reg-machine` below with per-element source captured."
    {:initial :disconnected

     ;; A machine validates its own `:data` here, on every transition.
     ;; (App-schemas guard app-db; a machine's `:data` lives in runtime-db,
     ;; so it gets its own `[:schemas :data]` instead.)
     ;; See docs/machines/concepts.md#validating-a-machines-data.
     :schemas {:data schema/ConnectionData}

     ;; `:data` is the connection's memory — everything that has to survive
     ;; a reconnect, from the URL down to the in-flight requests. Note
     ;; there's no `:socket-id` here: the live socket's id lives in the
     ;; framework's `:rf/spawned` slot (read via `socket-id`), which the
     ;; runtime keeps current for us.
     ;;
     ;; And note `:cred-ref`, not a token. Machine `:data` is
     ;; framework-inspectable — snapshots, traces, recorder fixtures — so a
     ;; raw bearer must never sit here (or ride a dispatch). We carry an
     ;; OPAQUE reference; the socket actor exchanges it for the real bearer
     ;; inside its own host closure at authentication time
     ;; (`websocket.messages/resolve-credential`) and discards it.
     :data    {:url            nil
               :cred-ref       nil
               :retries        0
               :max-retries    8
               :base-ms        100
               :max-backoff-ms 5000
               :subscriptions  #{}
               :queue          []
               :in-flight      {}
               ;; The ticking source for each registration's `:token` — see
               ;; `:register-request`. A counter in `:data` rather than a
               ;; fresh uuid in the fold: the value lands in durable machine
               ;; state, so it has to replay identically, and a
               ;; snapshot-resident counter does by construction.
               :next-token     0
               :error          nil}

     :guards
     {:max-retries-exceeded?
      (fn guard-max-retries-exceeded? [{data :data}]
        (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn guard-has-queued-messages? [{data :data}]
        (seq (:queue data)))

      :current-socket?
      ;; The connection-epoch check: "is this from the socket we're
      ;; actually using right now?" Every inbound event stamps the id of
      ;; the socket it came from (`:source-socket-id`, the actor's
      ;; `:rf/self-id`). We let it through only if that matches the live
      ;; socket — otherwise it's a straggler from a connection we've
      ;; already replaced, and we drop it. `socket-id` reads the live id
      ;; from `:rf/spawned`, so a torn-down socket compares as nil and every
      ;; straggler is dropped for free.
      (fn guard-current-socket? [{data :data [_ {:keys [source-socket-id]}] :event}]
        (from-live-socket? data source-socket-id))

      :own-request-timeout?
      ;; The deadline gate, and like `:trusted-frame?` it asks BOTH questions
      ;; a `:ws/request-timeout` has to answer before it may settle anything:
      ;; is it from the socket we're using (the epoch check above), and is it
      ;; the timer THIS registration armed?
      ;;
      ;; The epoch check alone is not enough, and the gap is easy to miss
      ;; because it needs no reconnect to open. A `:request-id` is the app's
      ;; own correlation value and the app is invited to reuse one — a
      ;; per-feature `[:feature/load slug]` vector is a recommended shape —
      ;; so on one long-lived socket the same id can be registered, settled,
      ;; and registered again well inside the first request's timeout window.
      ;; The first request's timer is still armed and still stamped with the
      ;; live socket, so it sails through the epoch check, names an id the
      ;; SECOND request now holds, and settles a request whose deadline has
      ;; not elapsed — deleting its slot and handing its caller a premature
      ;; `:ws/timeout`.
      ;;
      ;; So `:register-request` stamps each registration with a `:token` and
      ;; schedules the timeout carrying it; a timer settles the slot only
      ;; while the token it carries is still the token in it. That is also
      ;; what makes having no timer-cancellation facility safe: an obsolete
      ;; timer is not cancelled, it is simply inert — it fires, fails this
      ;; guard, and is dropped like any other straggler.
      (fn guard-own-request-timeout? [{data :data
                                       [_ {:keys [source-socket-id request-id token]}] :event}]
        (and (from-live-socket? data source-socket-id)
             (when-let [entry (get-in data [:in-flight request-id])]
               (= token (:token entry)))))

      :trusted-frame?
      ;; The inbound gate, and it asks BOTH questions an inbound frame has
      ;; to answer before this machine will act on it: is it from the
      ;; socket we're using (the epoch check above), and does it satisfy
      ;; the closed `InboundMessage` wire contract?
      ;;
      ;; Why the payload check belongs HERE and not only at the app-db
      ;; ingress: `:receive-message` reads `:type` and `:request-id` off
      ;; the frame and CLEARS an `:in-flight` slot on the strength of
      ;; them. That is machine state changing on the say-so of bytes
      ;; nobody has vetted — so a hostile frame naming a pending
      ;; `:request-id` could consume the correlation slot and leave the
      ;; caller waiting forever, even though the ingress downstream
      ;; refused the body and app-db never moved. Vet first, then act.
      ;;
      ;; One named guard rather than an `:and` of two, per 005 §No
      ;; combinator data form: compound logic is ordinary Clojure inside
      ;; one guard whose NAME carries the meaning.
      (fn guard-trusted-frame? [{data :data [_ {:keys [source-socket-id body]}] :event}]
        (and (from-live-socket? data source-socket-id)
             (schema/valid-inbound-frame? body)))}

     :actions
     {:record-connection-opts
      ;; Caller passes the URL and an OPAQUE credential reference on
      ;; `:ws/connect` — never the bearer itself (see `:data` above).
      (fn action-record-connection-opts [{data :data [_ {:keys [url cred-ref]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :cred-ref cred-ref)
                   (assoc :error nil))})

      :record-and-reset
      ;; Record fresh connection opts and zero the retry counter in one go.
      ;; This runs on a *manual* `:ws/connect` out of `:reconnecting` or
      ;; `:failed` — the user is asking for a clean slate, so we give them
      ;; one and forget the failed attempts.
      (fn action-record-and-reset [{data :data [_ {:keys [url cred-ref]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :cred-ref cred-ref)
                   (assoc :retries 0)
                   (assoc :error nil))})

      :rotate-cred
      ;; The app rotated its credential (say, the auth slice refreshed a
      ;; session). Only the new opaque reference crosses the dispatch
      ;; boundary; the next `:active` entry's spawn re-reads it and the new
      ;; socket resolves it host-side.
      (fn action-rotate-cred [{data :data [_ new-cred-ref] :event}]
        {:data (assoc data :cred-ref new-cred-ref)})

      :on-socket-lost
      ;; The live socket just dropped (a `:ws/closed` that passed
      ;; `:current-socket?`). Two things happen as we head to `:reconnecting`:
      ;;   1. Bump the retry counter — it drives the `:after` backoff and the
      ;;      `:max-retries-exceeded?` guard.
      ;;   2. FAIL every in-flight request (`fail-in-flight`, the helper the
      ;;      clean-disconnect door shares) — each was already put on the
      ;;      wire that just died, so its reply can never arrive on this
      ;;      connection. See the helper for the full leak/replay story.
      (fn action-on-socket-lost [{data :data}]
        (-> (fail-in-flight data)
            (update :data update :retries inc)))

      :fail-in-flight
      ;; A clean `:ws/disconnect` out of `:active` destroys the socket just
      ;; as surely as a drop does, so the SAME invariant applies: every
      ;; request accepted into `:in-flight` settles exactly once. Without
      ;; this, the slot and its waiting `:reply-event` survive teardown
      ;; forever — the scheduled timeout carries the destroyed socket's id,
      ;; so `:current-socket?` rejects it and the only cleanup path is
      ;; gone, including across a later reconnect. Unlike `:on-socket-lost`
      ;; there's no retry bump: the user asked for this disconnect, so it
      ;; isn't a connection failure — only the requests still riding the
      ;; wire fail, with the same documented `:ws/connection-lost` body.
      (fn action-fail-in-flight [{data :data}]
        (fail-in-flight data))

      :on-fatal-error
      ;; `:ws/fatal` is the app-level escape hatch out of `:active` — the
      ;; app itself declaring the connection unrecoverable (a protocol
      ;; violation, a server telling us not to come back) and going
      ;; straight to `:failed` without retrying. It leaves `:active`, so
      ;; the runtime destroys the socket just as surely as a drop or a
      ;; clean disconnect does, and the SAME invariant applies: every
      ;; request accepted into `:in-flight` settles exactly once on the
      ;; way out. Recording the error is only half the job — without the
      ;; `fail-in-flight` half the slot and its waiting `:reply-event`
      ;; survive teardown forever, because the scheduled timeout carries
      ;; the destroyed socket's id and `:current-socket?` rejects it once
      ;; the socket is gone. That leak outlives a later manual
      ;; `:ws/connect` out of `:failed` too, so the caller never learns
      ;; anything. Unlike `:on-socket-lost` there's no retry bump: we are
      ;; giving up, not retrying, so the counter is beside the point.
      (fn action-on-fatal-error [{data :data [_ {:keys [error]}] :event}]
        (-> (fail-in-flight data)
            (update :data assoc :error error)))

      :reset-retries
      (fn action-reset-retries [{data :data}]
        {:data (assoc data :retries 0)})

      :record-error
      (fn action-record-error [{data :data [_ {:keys [error]}] :event}]
        {:data (assoc data :error error)})

      :send-auth
      ;; We've just entered `:authenticating`, so start the handshake:
      ;; route an `:auth` message into the live socket actor and wait for
      ;; the server to bless us. Note there's NO token in this payload —
      ;; the actor resolved the bearer from `:cred-ref` when it opened the
      ;; socket, holds it in its private host closure, and attaches it to
      ;; the wire frame itself. The credential never rides a dispatch.
      (fn action-send-auth [{data :data}]
        {:fx [[:dispatch [(socket-id data)
                          [:send {:type :auth}]]]]})

      :flush-queue-and-resubscribe
      ;; We're connected at last. Two bits of housekeeping on entry: reset
      ;; the retry counter (we made it), and re-issue a subscribe for every
      ;; tracked topic — subscriptions survive reconnects, so the server on
      ;; the other end of a *new* socket needs telling about them again.
      ;; The queued-message flush is a separate `:always` step below; this
      ;; action deliberately leaves the `:queue` untouched for it to find.
      (fn action-on-connected [{data :data}]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      ;; The `:always` step on `:connected`: replay everything buffered while
      ;; we were off-connection. Each queued item is the ORIGINAL inbound
      ;; event, so re-dispatch it INTO the connection machine — which is now
      ;; `:connected`, so a `:ws/send` takes `:send-now` and a `:ws/request`
      ;; takes `:register-request` (in-flight slot + correlation + timeout),
      ;; exactly as if it had been issued while connected. Clearing `:queue`
      ;; first stops the replay from re-enqueuing.
      (fn action-flush-queue [{data :data}]
        (let [q (:queue data)]
          {:data (assoc data :queue [])
           :fx   (mapv (fn [event] [:dispatch [:ws/connection event]]) q)}))

      :enqueue-message
      ;; Off-connection there's no socket to send on, so buffer the WHOLE
      ;; inbound event (`[:ws/send …]` or `[:ws/request …]`), not just its
      ;; body. `:flush-queue` (above) re-dispatches each verbatim on the next
      ;; `:connected` entry, so a queued request rejoins `:register-request`
      ;; and gets correlated. Keeping the event preserves whether the queued
      ;; item was a send or a request; a bare body would lose that routing
      ;; intent.
      (fn action-enqueue-message [{data :data event :event}]
        {:data (update data :queue conj event)})

      :send-now
      ;; While `:connected`, sending is easy: straight to the wire, no
      ;; queue. This is the leaf overriding the parent's `:ws/send` (which
      ;; enqueues) — same event, different answer depending on where we are.
      (fn action-send-now [{data :data [_ msg] :event}]
        {:fx [[:dispatch [(socket-id data) [:send msg]]]]})

      :register-subscription
      (fn action-register-subscription [{data :data [_ topic] :event}]
        {:data (update data :subscriptions conj topic)
         :fx   [[:dispatch [(socket-id data)
                            [:send {:type :subscribe :topic topic}]]]]})

      :register-request
      ;; A request that expects a reply, in three moves: remember it (drop
      ;; an `:in-flight` entry so the eventual reply can find its way home),
      ;; send it (forward the body to the socket), and set a deadline
      ;; (schedule a timeout so a reply that never comes doesn't leave the
      ;; slot dangling forever).
      ;; Caller: `[:ws/connection [:ws/request {:request-id ... :body ...
      ;;                                       :reply ... :timeout-ms ...}]]`
      ;;
      ;; Three things here are about the id being the APP's, and reusable.
      ;;
      ;; The registration takes the next `:token`, which goes into the slot
      ;; AND onto the scheduled timeout, so that timer can only ever settle
      ;; this registration — see the `:own-request-timeout?` guard.
      ;;
      ;; The same token also goes ON THE WIRE, as `:request-token` beside
      ;; `:request-id`, and `schema/ReplyMessage` requires it back. Both
      ;; settling paths are reachable by a value that outlived the
      ;; registration it belongs to, and the deadline was only the nearer of
      ;; the two. Send the id alone and two registrations under it are
      ;; indistinguishable to the SERVER as well: A is pending under `R`, B
      ;; supersedes it, both are on the wire under `R`, and A's reply comes
      ;; back to find B in the slot. Correlating that by id hands B's caller
      ;; A's body — an answer to a question B never asked, delivered as a
      ;; success. The token travels so the reply can name the registration
      ;; rather than the slot.
      ;;
      ;; And re-registering an id that is still in flight SUPERSEDES: the
      ;; displaced caller is settled with `:ws/superseded` before the new
      ;; request goes out. Last write wins, because a reusable per-feature id
      ;; like `[:feature/load slug]` is a SLOT — re-issuing into it is the app
      ;; saying "that question is obsolete, ask this one" — and the caller of
      ;; the obsolete question still gets an answer instead of waiting on a
      ;; reply the new registration will now consume. (Refusing the newcomer
      ;; instead would make the recommended per-feature id unusable for the
      ;; case it is recommended for.) Both of the displaced registration's
      ;; leftovers are already inert: its timer's token is no longer the one
      ;; in the slot, and neither is the token its wire reply will carry.
      (fn action-register-request [{data :data [_ {:keys [request-id body reply timeout-ms]
                                            :or   {timeout-ms 30000}}] :event}]
        (let [token     (:next-token data)
              displaced (:reply-event (get-in data [:in-flight request-id]))
              send+arm  [[:dispatch [(socket-id data)
                                     [:send (assoc body
                                                   :request-id    request-id
                                                   :request-token token)]]]
                         ;; The timeout event carries the live socket-id too,
                         ;; so the same epoch check quietly discards a timeout
                         ;; left over from a connection we've moved past.
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
           ;; Settle the displaced caller FIRST — it is done either way, and
           ;; it should learn that before the wire moves on.
           :fx   (if displaced
                   (into [[:dispatch (conj displaced
                                           (local-failure request-id :ws/superseded))]]
                         send+arm)
                   send+arm)}))

      :clear-request
      ;; A request's deadline fired — a `:ws/request-timeout` that passed
      ;; `:own-request-timeout?`, so the socket is still live, the slot is
      ;; still occupied, and THIS registration is the one that armed the
      ;; timer. The reply never came. FAIL the request, don't just forget it:
      ;; clear the in-flight slot AND fire the waiting `:reply-event` with an
      ;; explicit timeout body, so a request that times out on a live socket
      ;; learns its outcome instead of hanging forever. This is
      ;; `:on-socket-lost`'s per-request twin — same `{:ok false :error …}`
      ;; failure shape, but scoped to the single request whose timer elapsed
      ;; rather than the whole in-flight set. (A request registered without a
      ;; `:reply` has a nil `:reply-event`, so the dispatch is guarded — same
      ;; as `:receive-message`.)
      (fn action-clear-request [{data :data [_ {:keys [request-id]}] :event}]
        (let [{:keys [reply-event]} (get-in data [:in-flight request-id])]
          {:data (update data :in-flight dissoc request-id)
           :fx   (cond-> []
                   reply-event (conj [:dispatch (conj reply-event
                                                      (local-failure request-id
                                                                     :ws/timeout))]))}))

      :receive-message
      ;; A frame arrived and `:trusted-frame?` has vouched for BOTH halves:
      ;; it came from the live socket, and it satisfies the closed
      ;; `InboundMessage` contract. So `:type` is `:reply` or `:push`,
      ;; those arms are closed, and only the `:reply` arm can carry a
      ;; `:request-id` at all.
      ;;
      ;; We branch on `:type` rather than on "is there a `:request-id`?".
      ;; The two agree now, but only one of them keeps agreeing: the
      ;; frame's declared kind is part of the vetted contract, whereas
      ;; presence-of-a-key is a shape test that a widened arm would
      ;; quietly re-open.
      ;;
      ;; And within the `:reply` arm we correlate on the REGISTRATION, not
      ;; on the slot. The id names a slot; the `:request-token` the server
      ;; echoed names the registration that sent the request. Matching the
      ;; slot alone is the same mistake `:own-request-timeout?` fences on
      ;; the deadline side, arriving by the other door: two registrations
      ;; can be outstanding under one id (A pending, B supersedes it, both
      ;; on the wire), and then A's reply finds B's slot and settles B's
      ;; caller with A's body — exactly once, and about the wrong request.
      ;; A token mismatch means "this answers a registration that is no
      ;; longer here", which is an unsolicited reply and treated as one.
      (fn action-receive-message [{data :data [_ {:keys [body]}] :event}]
        (if (= :reply (:type body))
          ;; Correlated reply. Settle the slot ONLY if the registration in
          ;; it is the one that asked — an unsolicited reply, a second copy
          ;; of one we already settled, or an answer to a superseded
          ;; registration has nothing to clear and no `:reply-event` to
          ;; fire. Each still reaches the inbox: it passed the wire
          ;; contract, it just answers no question still outstanding.
          ;;
          ;; `:request-token` is required by `schema/ReplyMessage` and
          ;; `:token` is always an int, so a frame that reached here has
          ;; both — there is no nil-matches-nil case to guard against.
          (let [rid   (:request-id body)
                entry (when-let [e (get-in data [:in-flight rid])]
                        (when (= (:request-token body) (:token e)) e))]
            (cond-> {:fx [[:dispatch [:ws/handle-message body]]]}
              entry
              (assoc :data (update data :in-flight dissoc rid))

              (:reply-event entry)
              ;; `:origin :ws/server` is the machine saying where this
              ;; body came from. Stamped here, after receipt, so the
              ;; sender cannot claim to be the local loss/timeout path
              ;; (see `schema/RequestOutcome`).
              (update :fx conj [:dispatch (conj (:reply-event entry)
                                                (assoc body :origin :ws/server))])))
          ;; Server push — no correlation to touch, straight to the inbox.
          {:fx [[:dispatch [:ws/handle-message body]]]}))

      :refuse-frame
      ;; The frame came from the live socket but failed the wire contract:
      ;; a `:type` the union has no arm for, a malformed body, an extra
      ;; key on a closed arm. Note what this action does NOT return — a
      ;; `:data` key. Nothing about the connection moves on the strength
      ;; of bytes that did not pass, which is the whole point of splitting
      ;; this out from `:receive-message` instead of branching inside it.
      ;;
      ;; The frame is still handed to `[:ws/handle-message body]`, and
      ;; that is deliberate: the ingress owns inbound-frame refusal, so
      ;; its release-resident `:rf.schema/at-boundary` check produces the
      ;; one canonical `:rf.error/schema-validation-failure` record. The
      ;; machine protects its own state; it does not mint a second
      ;; rejection vocabulary alongside the framework's.
      (fn action-refuse-frame [{[_ {:keys [body]}] :event}]
        {:fx [[:dispatch [:ws/handle-message body]]]})}

     :states
     {:disconnected
      {:on {:ws/connect {:target :active
                         :action :record-connection-opts}
            :ws/send    {:action :enqueue-message}
            :ws/request {:action :enqueue-message}}}

      :active
      {;; Spawn the socket actor here, on the parent, so one socket lives
       ;; across :connecting -> :authenticating -> :connected. The instant
       ;; we leave :active — by any door — the runtime tears it down (and
       ;; clears its id from our :rf/spawned slot, so `socket-id` reads nil).
       ;; See docs/machines/concepts.md#when-the-machine-grows.
       :spawn {:machine-id :websocket/socket
                ;; Hand the child the URL and the opaque `:cred-ref` from
                ;; our `:data` as it's born. Every fresh entry to :active
                ;; re-reads whatever's current — so a credential rotated
                ;; mid-reconnect just rides into the next socket, no extra
                ;; plumbing. The reference is all that moves: the child
                ;; resolves it to the real bearer inside its own host
                ;; closure at socket-open.
                :data       (fn [{snap :snapshot}]
                              {:url      (-> snap :data :url)
                               :cred-ref (-> snap :data :cred-ref)})}

       ;; Transitions every leaf inherits. A leaf can override any of these
       ;; (deepest state wins); anything it doesn't handle falls through to
       ;; here. See docs/machines/glossary.md#transition.
       :on    {:ws/closed   {:guard  :current-socket?
                             :target :reconnecting
                             :action :on-socket-lost}
               ;; The app-level escape hatch. It leaves :active, so it kills
               ;; the wire — and therefore settles the in-flight set on the
               ;; way out as well as recording the error (see the
               ;; :on-fatal-error action). Every door out of :active that
               ;; can be taken from :connected now settles :in-flight
               ;; exactly once: this one, the guarded :ws/closed drop
               ;; (:on-socket-lost) and the clean :ws/disconnect
               ;; (:fail-in-flight).
               :ws/fatal    {:target :failed
                             :action :on-fatal-error}
               :ws/send     {:action :enqueue-message}
               :ws/request  {:action :enqueue-message}
               :ws/rotate-cred {:action :rotate-cred}
               ;; Leaving :active by the clean door still kills the wire, so
               ;; it settles the in-flight set on the way out (see the
               ;; :fail-in-flight action). The :reconnecting / :failed
               ;; :ws/disconnect transitions carry no such action — their
               ;; in-flight was already settled when :active was left.
               :ws/disconnect {:target :disconnected
                               :action :fail-in-flight}
               ;; Subscribe before we're fully connected? Just note the
               ;; topic down; the next :connected entry will actually send
               ;; the subscribe.
               :ws/subscribe {:action (fn [{data :data [_ topic] :event}]
                                        {:data (update data :subscriptions conj topic)})}}

       :initial :connecting

       :states
       {:connecting
        {:tags #{:websocket/active :websocket/connecting}
         ;; Guarded like every socket-sourced lifecycle event: an `:ws/opened`
         ;; from a socket we've already replaced (a slow open landing after a
         ;; disconnect+reconnect) must not advance THIS connection.
         :on   {:ws/opened {:guard  :current-socket?
                            :target :authenticating}}}

        :authenticating
        {:tags  #{:websocket/active :websocket/authenticating}
         :entry :send-auth
         ;; Both auth outcomes are epoch-guarded: a straggler `:ws/auth-ok`
         ;; from a replaced socket must not prematurely mark us `:connected`,
         ;; and a straggler `:ws/auth-failed` must not tear a fresh
         ;; authentication attempt down to `:failed`.
         :on    {:ws/auth-ok     {:guard  :current-socket?
                                  :target :connected}
                 ;; Note the vector: `[:failed]`, not bare `:failed`.
                 ;; `:failed` lives at the top level, not under `:active`,
                 ;; and a bare keyword would be read as the sibling
                 ;; `[:active :failed]` — which doesn't exist. The absolute
                 ;; vector says "from the root, please".
                 ;;
                 ;; This door leaves :active too, but it needs no
                 ;; in-flight settlement — plain :record-error is correct
                 ;; here, not an oversight. :register-request is the only
                 ;; writer into :in-flight and it is bound only on
                 ;; :connected; everywhere else a :ws/request enqueues
                 ;; instead. Reaching :authenticating means we have not
                 ;; been :connected since the last :active entry, and
                 ;; every door out of :active from :connected clears
                 ;; :in-flight on the way out. So :in-flight is provably
                 ;; empty whenever this fires.
                 :ws/auth-failed {:guard  :current-socket?
                                  :target [:failed]
                                  :action :record-error}}}

        :connected
        {:tags   #{:websocket/active :websocket/connected}
         :entry  :flush-queue-and-resubscribe
         :always [{:guard :has-queued-messages? :action :flush-queue}]
         :on     {;; Two candidates, first-match-wins (005 §Transition
                  ;; resolution — a guard-blocked candidate is not
                  ;; selected, so the walk falls through to the next).
                  ;; A frame that clears both halves of the inbound gate
                  ;; is received; one that came from the live socket but
                  ;; failed the wire contract is refused without the
                  ;; machine moving. A frame from a socket we have already
                  ;; replaced matches neither and is dropped, exactly as
                  ;; before.
                  :ws/received [{:guard  :trusted-frame?
                                 :action :receive-message}
                                {:guard  :current-socket?
                                 :action :refuse-frame}]
                  ;; Here we override the parent's :ws/send: connected
                  ;; means no queue, send it now.
                  :ws/send     {:action :send-now}
                  :ws/request  {:action :register-request}
                  :ws/subscribe {:action :register-subscription}
                  ;; A deadline elapsed. Two questions, one guard: is the
                  ;; socket still ours, and is this the timer the registration
                  ;; currently in the slot armed? See `:own-request-timeout?`.
                  :ws/request-timeout {:guard  :own-request-timeout?
                                       :action :clear-request}}}}}

      :reconnecting
      {:tags   #{:websocket/reconnecting}
       :always [{:guard :max-retries-exceeded? :target :failed}]
       ;; Exponential backoff: wait a little, then a little more, then a
       ;; lot. The delay fn runs once on entry and reads the current retry
       ;; count, so each retry waits longer than the last. And because
       ;; leaving the state cancels the timer, a backoff armed on an
       ;; earlier visit can never fire late — nothing to remember, nothing
       ;; to clean up.
       ;; See docs/machines/concepts.md#guards-actions-tags-and-after--the-recognition-kit.
       :after  {(fn delay-backoff-ms [{snap :snapshot}]
                  (let [{:keys [retries base-ms max-backoff-ms]} (:data snap)]
                    (min (* base-ms (Math/pow 2 retries))
                         max-backoff-ms)))
                {:target :active}}
       :on     {:ws/connect       {:target :active
                                   :action :record-and-reset}
                :ws/send          {:action :enqueue-message}
                :ws/request       {:action :enqueue-message}
                :ws/rotate-cred   {:action :rotate-cred}
                :ws/disconnect    {:target :disconnected
                                   :action :reset-retries}}}

      :failed
      {:tags #{:websocket/failed}
       :on   {:ws/connect       {:target :active
                                 :action :record-and-reset}
              ;; The offline queue contract is uniform: a send or request
              ;; issued while we've given up still buffers, exactly as it
              ;; does in :disconnected and :reconnecting. :failed is a
              ;; top-level state, so it does NOT inherit :active's parent
              ;; :ws/send / :ws/request enqueue transitions — it has to carry
              ;; its own. :record-and-reset (the :ws/connect action above)
              ;; leaves :queue untouched, so a later manual reconnect reaches
              ;; :connected and the :always :flush-queue drains whatever was
              ;; buffered here. Without these, a send in :failed is
              ;; unhandled and silently dropped — user-visible message loss.
              :ws/send          {:action :enqueue-message}
              :ws/request       {:action :enqueue-message}
              :ws/rotate-cred   {:action :rotate-cred}
              :ws/disconnect    {:target :disconnected
                                 :action :reset-retries}}}}})

;; ============================================================================
;; MACHINE HANDLER + SUBSCRIPTIONS + INIT EVENT
;; ============================================================================

(defn register!
  "Install every `reg-*` this namespace owns, in one re-invocable place.
   Called once at ns-load below — see `websocket.messages/register!` for
   why the block is named rather than left as bare top-level forms."
  []
  ;; `reg-machine` marks this registration `:rf/machine? true` — the flag a
  ;; declarative `:spawn` looks for when resolving its target. An unregistered
  ;; target is rejected with `:rf.error/machine-spawn-unregistered-type`; no
  ;; child snapshot is created.
  (rf/reg-machine :ws/connection connection-machine)

  ;; --- subs -------------------------------------------------------------
  ;; The machine's snapshot lives in runtime-db, not app-db, so we read it
  ;; through the framework `:rf/machine` sub rather than poking at app-db.
  ;; See docs/machines/glossary.md#snapshot.
  (rf/reg-sub :ws/snapshot
    :<- [:rf/machine :ws/connection]
    (fn [snapshot _] snapshot))

  (rf/reg-sub :ws/state
    :<- [:ws/snapshot]
    (fn [snap _] (:state snap)))

  ;; One little yes/no sub per tag. Each chains off the FRAMEWORK sub
  ;; `:rf.machine/has-tag?`, which returns the snapshot's tag-containment bit
  ;; directly — so a view can ask "connected?" and get a boolean without
  ;; unpacking the hierarchical `:state` vector or re-reading the snapshot
  ;; itself.
  ;; See docs/machines/glossary.md#state-tag.
  (rf/reg-sub :ws/connecting?
    :<- [:rf.machine/has-tag? :ws/connection :websocket/connecting]
    (fn [has-tag? _] has-tag?))

  (rf/reg-sub :ws/authenticating?
    :<- [:rf.machine/has-tag? :ws/connection :websocket/authenticating]
    (fn [has-tag? _] has-tag?))

  (rf/reg-sub :ws/connected?
    :<- [:rf.machine/has-tag? :ws/connection :websocket/connected]
    (fn [has-tag? _] has-tag?))

  (rf/reg-sub :ws/reconnecting?
    :<- [:rf.machine/has-tag? :ws/connection :websocket/reconnecting]
    (fn [has-tag? _] has-tag?))

  (rf/reg-sub :ws/failed?
    :<- [:rf.machine/has-tag? :ws/connection :websocket/failed]
    (fn [has-tag? _] has-tag?))

  (rf/reg-sub :ws/queue-depth
    :<- [:ws/snapshot]
    (fn [snap _] (count (get-in snap [:data :queue]))))

  (rf/reg-sub :ws/retries
    :<- [:ws/snapshot]
    (fn [snap _] (get-in snap [:data :retries])))

  (rf/reg-sub :ws/error
    :<- [:ws/snapshot]
    (fn [snap _] (get-in snap [:data :error])))

  ;; --- init event -------------------------------------------------------
  (rf/reg-event :ws.connection/initialise
    {:doc "Wake the connection machine up in its `:disconnected` start
           state. A machine's first snapshot only materialises when it first
           receives an event, so this gives it the canonical eager kick."}
    (fn handler-ws-connection-initialise [_ _]
      ;; `[:rf.machine/start]` is re-frame2's spelling of XState v5's
      ;; `createActor(machine).start()`: it runs the initial-entry cascade
      ;; and then stops, materialising the `:disconnected` snapshot so tests
      ;; (and the UI) can read the state before anyone clicks Connect.
      {:fx [[:dispatch [:ws/connection [:rf.machine/start]]]]})))

;; Loading this namespace registers it — the production-app idiom.
(register!)
