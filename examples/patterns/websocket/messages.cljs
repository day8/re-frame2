(ns websocket.messages
  "The `:websocket/socket` actor, plus the messages flowing in and out.

   Two jobs share this file:

   1. **The socket actor (`:websocket/socket`).** The child machine the
      connection machine spawns on entry to `:active`. Its whole purpose
      is to own the JS `WebSocket` — a live host-side handle, not a value,
      and emphatically not something you want sitting in app-db. The actor
      turns outbound `:send` events into wire writes and relays inbound
      server messages back up to the parent. See docs/machines/glossary.md#spawn.

   2. **A pretend server.** No real endpoint here — a tiny in-process
      `WebSocket`-shaped stub stands in, so the example runs anywhere with
      zero network. Its state lives in the `mock-server-state` atom, and a
      handful of exported fns are the seams you reach for: `set-mock-sync!`
      flips it between async (`setTimeout`-deferred) and sync
      (`dispatch-sync`) delivery; `send-server-push!` and
      `simulate-disconnect!` let view buttons shove events in from outside;
      `reset-mock-server!` wipes the slate between tests. Sync mode is the
      tester's friend — it lets a full request/reply round-trip complete
      without ever yielding to the JS event loop.

   The two halves are kept apart on purpose. Swap the pretend server for a
   real `(js/WebSocket. url)` and the actor machine doesn't even notice.
   The shape stays put no matter the transport: the machine owns the actor,
   the actor owns the host-side handle.

   Two trust boundaries also live here, because this is the host seam:
   `resolve-credential` exchanges the machine's opaque `:cred-ref` for the
   real bearer at the instant of the auth write (credentials leaving the
   app), and `:ws/handle-message` / `:ws.app/request-reply` hold inbound
   frames to the closed wire schemas with `:rf.schema/at-boundary` (frames
   entering it). The connection machine's `:trusted-frame?` guard applies
   the same inbound contract before it touches correlation state — see
   `websocket.connection`."
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [websocket.schema :as schema]))

;; ============================================================================
;; HOST-SIDE SOCKET STORE
;; ============================================================================
;;
;; Where do you keep a live socket? Not in app-db. A JS `WebSocket` (or our
;; mock stand-in) is a mutable handle: it won't serialise, it won't survive
;; a time-travel replay, and slipping it into app-db would quietly undo
;; everything re-frame's value semantics buy you. So it lives off to the
;; side, in this little store keyed by the actor's `:rf/self-id`. The actor
;; owns it: writes on open, clears on close. app-db only ever sees the id.

(defonce ^:private sockets-by-actor (atom {}))

(defn- store-socket! [actor-id socket]
  (swap! sockets-by-actor assoc actor-id socket))

(defn- get-socket [actor-id]
  (get @sockets-by-actor actor-id))

(defn- clear-socket! [actor-id]
  (swap! sockets-by-actor dissoc actor-id))

;; ============================================================================
;; CREDENTIAL SEAM
;; ============================================================================
;;
;; The connection machine carries only an OPAQUE `:cred-ref` — never the
;; bearer. This host-side, client-only seam is where the reference becomes
;; the real credential, at the one moment it's needed: socket
;; authentication. `mock-socket-for-actor`'s `:auth` send calls it, writes
;; the result to the wire frame, and lets it go out of scope in the same
;; expression — that is the bearer's whole life. It never enters machine
;; `:data`, a dispatch payload, app-db, anything the framework can
;; serialise, or any closure that outlives the write.
;; See skills/re-frame2/patterns/websocket.md §Credential discipline.

(defn resolve-credential
  "Exchange an opaque credential reference for the real bearer. App-owned
   and app-prefixed — re-frame2 ships no credential surface. The demo
   recognises two references (so credential rotation has somewhere to
   rotate TO); swap the body for your auth slice's real lookup. Returns
   nil for an unknown reference, which the mock server then refuses —
   authentication genuinely depends on resolution."
  [cred-ref]
  (case cred-ref
    :ws.demo/cred-a "demo-bearer-secret-a"
    :ws.demo/cred-b "demo-bearer-secret-b"
    nil))

;; ============================================================================
;; MOCK WEBSOCKET SERVER
;; ============================================================================
;;
;; Just enough `js/WebSocket`-shaped server to make the example feel alive,
;; for both the tests and the buttons in the UI. It answers in two ways:
;;
;;   (1) It echoes. Send it an outbound `{:type :request ...}` and it
;;       replies on the same socket with the original `:request-id` intact,
;;       which is exactly what the connection machine's request-reply
;;       correlation needs to find its match.
;;
;;   (2) It can be poked from the outside, via `(send-server-push! body)`
;;       and `(simulate-disconnect!)` — the seams the view buttons call.
;;
;; In async mode, replies don't land in the same breath as the send: the
;; `later` helper below defers them a tick (`js/setTimeout _ 0`), so
;; transport events arrive after the dispatch returns, the way a real
;; network would.

(defonce ^:private mock-server-state (atom {:sockets {} :sync? false}))

(defn set-mock-sync!
  "Flip the mock server between async (the default — `setTimeout`-deferred,
   like a real network) and sync (replies land immediately). Tests want
   sync: it lets a single `rf/dispatch-sync` watch a whole request/reply
   round-trip finish, with no event-loop tick to wait on."
  [sync?]
  (swap! mock-server-state assoc :sync? sync?))

(defn reset-mock-server!
  "Forget every stored mock socket, so each test opens on a clean server.
   Skip this and the `:sockets` map quietly piles up across tests, until
   `send-server-push!` is cheerfully delivering N copies of every push."
  []
  (swap! mock-server-state assoc :sockets {}))

(defn- later
  "Run `f` now (sync mode) or on the next tick via `setTimeout` (async).
   This one tiny fork is the whole difference between the tests' world and
   the browser's: sync for tests, deferred-like-a-network for the example."
  [f]
  (if (:sync? @mock-server-state)
    (f)
    (js/setTimeout f 0)))

(defn- next-mock-socket-id []
  (str "mock-socket-" (random-uuid)))

(defn- deliver-to-actor!
  "Hand an inbound transport event to the spawned actor, which will turn it
   into a parent-bound `[:ws/connection [:ws/<kind> ...]]` dispatch.

   There's a subtlety in async mode: this runs from a detached
   `setTimeout` callback, which has no ambient frame, so a bare
   `rf/dispatch` would throw `:rf.error/no-frame-context`. The fix is to
   capture the frame's `dispatch` back where a frame *was* in scope and
   thread it in here — a captured `dispatch` carries its frame across the
   async gap. See docs/core/glossary.md#capture-frame."
  [dispatch actor-id kind payload]
  (when actor-id
    (dispatch [actor-id [kind payload]])))

(defn- mock-encode-auth-reply [token]
  ;; A real server would actually verify the JWT. Our mock has lower
  ;; standards: any non-empty string is welcome, everything else is shown
  ;; the door.
  (if (and (string? token) (pos? (count token)))
    {:type :auth-ok}
    {:type :auth-failed :reason "Empty token"}))

(defn mock-socket-for-actor
  "Open a fresh mock socket bound to this actor's id, and return its handle.
   The handle's `:send` fn fields every outbound message the actor makes;
   the mock knows how to answer two of them — `:auth` (→ `:auth-ok`) and
   `:request` (→ a correlated `:type :reply` echo).

   This is also where the opaque `:cred-ref` becomes a real credential —
   inside the `:auth` branch of `:send` below, not out here. The handle
   this fn returns is stored for the socket's lifetime, so anything the
   enclosing scope resolves is retained for that lifetime too; the bearer
   is resolved at the moment of the auth write and is gone when that
   branch returns. Only the opaque reference crosses into the closure."
  [actor-id _url cred-ref]
  (let [id     (next-mock-socket-id)
        open?  (atom true)
        ;; We're inside the actor's `:open-socket` action right now, which
        ;; means a frame is in scope — but the deferred deliveries below
        ;; fire from a bare `setTimeout` callback, long after it's gone.
        ;; So grab the frame's `dispatch` here, while we still can, and
        ;; carry it into each delivery. See docs/core/glossary.md#capture-frame.
        dispatch (:dispatch (rf/capture-frame))]
    (swap! mock-server-state assoc-in [:sockets id]
           {:actor-id actor-id
            :open?    open?})
    {:id    id
     :open? open?
     :send  (fn mock-send [msg]
              (when @open?
                (case (:type msg)
                  :auth
                  ;; Auth: the machine's `:send-auth` sends a bare
                  ;; `{:type :auth}` — no credential in the dispatch. THIS
                  ;; is where the bearer joins, and this `let` is its
                  ;; entire life: resolved from the opaque `:cred-ref` at
                  ;; the one moment it is needed, written to the wire
                  ;; frame, and unreachable the moment the `let` returns.
                  ;; A real client writes the same line as
                  ;; `(.send socket (encode (assoc msg :token bearer)))`.
                  ;;
                  ;; Resolving HERE rather than beside the socket handle
                  ;; is the difference between "read once, then gone" and
                  ;; merely saying so. The handle this fn returns is kept
                  ;; in `sockets-by-actor` for the socket's whole life, so
                  ;; a bearer resolved into the enclosing scope would be
                  ;; retained by this very closure for just as long — a
                  ;; live credential parked in a module-level atom, which
                  ;; no snapshot sweep can see and no test can catch.
                  ;;
                  ;; The mock 'server' reads the wire frame's `:token`
                  ;; synchronously, the way a real endpoint reads bytes
                  ;; off the socket, and only the ANSWER is deferred by
                  ;; `later`. An unresolvable `:cred-ref` puts a nil
                  ;; `:token` on the wire — refused, so authentication
                  ;; genuinely depends on the credential seam.
                  (let [wire-frame (assoc msg :token (resolve-credential cred-ref))
                        reply      (mock-encode-auth-reply (:token wire-frame))]
                    (later
                      #(deliver-to-actor! dispatch actor-id :received reply)))

                  :request
                  ;; Every :request gets the same treatment: "here's your
                  ;; stuff back, plus :ok". BOTH correlation fields ride
                  ;; back unchanged, which is the whole point — that's how
                  ;; the connection machine pairs this reply with its
                  ;; request. The :request-id names the slot; the
                  ;; :request-token names the REGISTRATION that sent this
                  ;; particular request, and a real server owes the client
                  ;; the same echo (see schema/ReplyMessage, which requires
                  ;; it). Both are protocol envelope, not payload, so
                  ;; neither appears in the :echo of what was sent.
                  (later
                    #(deliver-to-actor! dispatch actor-id :received
                                        {:type          :reply
                                         :request-id    (:request-id msg)
                                         :request-token (:request-token msg)
                                         :ok            true
                                         :echo          (dissoc msg :request-id :request-token)}))

                  :subscribe
                  ;; Ack a subscribe with one synthetic push, so you can
                  ;; watch a server-pushed event show up right after the
                  ;; subscribe round-trip — the shape a real feed would use.
                  (later
                    #(deliver-to-actor! dispatch actor-id :received
                                        {:type :push
                                         :topic (:topic msg)
                                         :note  "subscribed"}))

                  ;; Anything else: shrug. The example doesn't bother with
                  ;; fire-and-forget sends beyond the cases above.
                  nil)))
     ;; Close the host-side socket, the way a real `WebSocket`'s `.close()`
     ;; would: mark it shut and drop its `mock-server-state` entry so nothing
     ;; keeps trying to reach it. This is the seam the actor's `:exit`
     ;; reaches for on teardown. It deliberately does NOT re-echo a `:closed`
     ;; back to the actor — the actor is on its way out when this runs, so
     ;; dispatching an event into a dying machine would fire mid-teardown
     ;; (in sync mode, `later` runs it right now). The one place that WANTS a
     ;; `:closed` delivered — an unexpected drop — is `simulate-disconnect!`,
     ;; which dispatches it itself. Swap in a real socket and this line
     ;; becomes `(.close socket)`.
     :close (fn mock-close []
              (reset! open? false)
              (swap! mock-server-state update :sockets dissoc id))}))

;; The seams below let the views (and tests) poke the mock server from
;; outside, without pretending to be the actor.

(defn- deliver-external!
  "Like `deliver-to-actor!`, but for callers standing outside a running
   cascade — test bodies and view click handlers. In sync mode it reaches
   for `dispatch-sync`, so the whole chain settles before it returns; in
   async mode the plain queued `dispatch` does.

   A click handler fires with no frame in scope, so the caller passes in a
   frame-bound `dispatch` / `dispatch-sync` pair (from a captured
   `(rf/capture-frame)`) — a bare `rf/dispatch` here would raise
   `:rf.error/no-frame-context`. See docs/core/glossary.md#capture-frame."
  [{:keys [dispatch dispatch-sync]} actor-id kind payload]
  (when actor-id
    (if (:sync? @mock-server-state)
      (dispatch-sync [actor-id [kind payload]])
      (dispatch      [actor-id [kind payload]]))))

(defn send-server-push!
  "Push a made-up server message to every live mock socket — the inbound
   path the 'Trigger server push' button (and the tests) exercise.

   `handle` is a `(rf/capture-frame)` frame api holding the caller's frame, so
   the deferred dispatch can carry it across the click-handler / async
   boundary. See docs/core/glossary.md#capture-frame."
  [handle body]
  (doseq [[_ {:keys [actor-id open?]}] (:sockets @mock-server-state)]
    (when @open?
      (deliver-external! handle actor-id :received body))))

(defn simulate-disconnect!
  "Yank every live mock socket closed and let the parent's reconnect
   cascade take it from there — this is what the 'Drop connection' button
   does. `handle` is the caller's `(rf/capture-frame)` frame api (see
   `send-server-push!`)."
  [handle]
  (doseq [[_ {:keys [actor-id open?]}] (:sockets @mock-server-state)]
    (when @open?
      (reset! open? false)
      (deliver-external! handle actor-id :closed {:code 1006 :reason "simulated"}))))

;; ============================================================================
;; THE SOCKET ACTOR — :websocket/socket
;; ============================================================================
;;
;; A small machine with a simple life. `:opening` opens the host-side
;; socket on entry, then immediately steps to `:open`, where it spends the
;; rest of its days. The one thing it MUST do on the way out is close that
;; host socket — an `:exit :close-socket` on `:open` does exactly that. The
;; runtime clears the `:rf/spawned` id the parent tracks all on its own, but
;; the JS `WebSocket` (or our mock stand-in) is a live handle the runtime
;; knows nothing about, so a clean Disconnect / `:ws/fatal` / `:ws/auth-failed`
;; that destroys the actor would leak it. The actor's `:exit` is the one bit
;; of teardown that IS its problem.
;;
;; Two ids do the talking. The runtime stamps `:rf/self-id` and
;; `:rf/parent-id` into a spawned actor's `:data`; the actor tags its own
;; messages with the first and addresses the parent with the second.

(rf/defmachine socket-actor-machine
  "The `:websocket/socket` actor spec, in its own `defmachine` so `reg-machine`
   below can point at it with per-element source captured."
    {:initial :opening
     ;; Like the parent, the actor's `:data` carries only the URL and the
     ;; OPAQUE `:cred-ref` — the real bearer exists solely inside the
     ;; socket closure `mock-socket-for-actor` builds. Validated on every
     ;; transition, same as the parent's `:data`.
     :schemas {:data schema/SocketActorData}
     :data    {:url      nil
               :cred-ref nil}

     :actions
     {:open-socket
      ;; On entry: stand up the host-side mock socket, stash it, and tell
      ;; the parent we're `:opened`.
      (fn action-open-socket [{data :data}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)
              socket    (mock-socket-for-actor self-id
                                               (:url data)
                                               (:cred-ref data))]
          (store-socket! self-id socket)
          ;; Notice we report `:opened` as a `:dispatch` *fx*, not a bare
          ;; `(rf/dispatch ...)` in the action body. The fx inherits the
          ;; pipeline run's frame; a raw dispatch from in here would run with no
          ;; frame at all and raise `:rf.error/no-frame-context`. Effects,
          ;; not side effects.
          {:fx [[:dispatch [parent-id [:ws/opened {:source-socket-id self-id}]]]]}))

      :close-socket
      ;; On exit from `:open`: close the host-side socket the actor opened.
      ;; Letting go of the `:rf/spawned` id (which the runtime does for us on
      ;; teardown) frees the ACTOR, not the socket — so without this the JS
      ;; `WebSocket` leaks. `((:close socket))` marks the mock shut and prunes
      ;; its `mock-server-state` entry (a real socket would take a `.close()`
      ;; there); `clear-socket!` drops our host-store reference. No `:closed`
      ;; is re-dispatched — the actor is already on its way out.
      (fn action-close-socket [{data :data}]
        (let [self-id (:rf/self-id data)]
          (when-let [socket (get-socket self-id)]
            ((:close socket)))
          (clear-socket! self-id))
        nil)

      :send-via-socket
      ;; The parent sends us `[<actor-id> [:send body]]` for each outbound
      ;; message; we just hand the body to the host-side socket's `:send`.
      (fn action-send-via-socket [{data :data [_ body] :event}]
        (let [self-id (:rf/self-id data)]
          (when-let [socket (get-socket self-id)]
            ((:send socket) body)))
        nil)

      :forward-received
      ;; Something came in from the server, via `[<actor-id> [:received
      ;; body]]`. Pass it up to the parent, stamped with our socket id —
      ;; that stamp is what lets `:current-socket?` reject leftovers from a
      ;; socket that's since been torn down.
      (fn action-forward-received [{data :data [_ body] :event}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)
              ;; Sort by `:type`: auth results (`:auth-ok` / `:auth-failed`)
              ;; reach the parent as their own dedicated events; everything
              ;; else goes up as `:ws/received` with the body riding along.
              ev        (case (:type body)
                          :auth-ok     [:ws/auth-ok {:source-socket-id self-id}]
                          :auth-failed [:ws/auth-failed
                                        {:source-socket-id self-id
                                         :error            (:reason body)}]
                          [:ws/received {:source-socket-id self-id
                                         :body             body}])]
          {:fx [[:dispatch [parent-id ev]]]}))

      :forward-closed
      ;; A `:closed` event reached us — an unexpected drop that
      ;; `simulate-disconnect!` delivered, or (with a real socket) its
      ;; `onclose`. Forward it up to the parent, stamped with our socket id so
      ;; `:current-socket?` can vet it. Host-socket cleanup is NOT done here:
      ;; walking to `:closed` exits `:open`, and `:open`'s `:exit :close-socket`
      ;; has already shut the host socket down. This action is pure messaging.
      (fn action-forward-closed [{data :data [_ {:keys [code reason]}] :event}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)]
          {:fx [[:dispatch [parent-id
                            [:ws/closed {:source-socket-id self-id
                                         :code             code
                                         :reason           reason}]]]]}))}

     :states
     {:opening
      ;; The first moment of the actor's life. `:entry` runs once: open the
      ;; host-side socket, store it, and then `:always` carries us straight
      ;; on to `:open`.
      ;;
      ;; There's a small race to cover. The parent's `:send-auth` can fire a
      ;; `:send` at us before we've even settled into `:open`, so `:opening`
      ;; handles `:send` too — by then the socket is stored, so it just works.
      {:entry :open-socket
       :always [{:target :open}]
       :on    {:send       {:target :open
                            :action :send-via-socket}
               :received   {:target :open
                            :action :forward-received}
               :closed     {:target :closed
                            :action :forward-closed}}}

      :open
      ;; Where the actor spends its life — and where `:exit :close-socket`
      ;; lives, NOT on `:opening`: `:opening` `:always`-steps straight to
      ;; `:open`, so an exit there would slam shut the socket we just opened.
      ;; `:open` is the only state the actor rests in, so its exit is the
      ;; reliable teardown seam. It fires on BOTH doors out: destroy (the
      ;; parent leaving `:active` tears the actor down) and the `:open` ->
      ;; `:closed` walk a `:closed` event triggers.
      {:exit :close-socket
       :on   {:send     {:action :send-via-socket}
              :received {:action :forward-received}
              :closed   {:target :closed
                         :action :forward-closed}}}

      :closed
      ;; The end of the line. Reaching here means we exited `:open`, so
      ;; `:close-socket` has already shut the host socket down; the parent's
      ;; exit-from-`:active` will destroy this actor shortly. Until it does,
      ;; this state just quietly soaks up any stragglers.
      {}}})

;; ============================================================================
;; REGISTRATIONS
;; ============================================================================

(defn register!
  "Install every `reg-*` this namespace owns, in one re-invocable place.

   It is called once at ns-load below, so loading the namespace still IS
   the registration — the production-app idiom the rest of this example
   assumes, and `core/run` does nothing extra. Naming the block buys one
   thing: a test fixture that has to recover from an upstream
   `registrar/clear-all!` re-fires THESE definitions rather than keeping a
   hand-mirrored copy, so the handler bodies and the boundary metadata
   below are the only ones that exist anywhere and cannot drift out from
   under the tests that certify them."
  []
  ;; Register the actor the connection machine spawns. As before,
  ;; `reg-machine` is what flips on `:rf/machine? true` — the flag the spawn
  ;; needs to find its target.
  (rf/reg-machine :websocket/socket socket-actor-machine)

  ;; For every message that comes in — a correlated reply or a server push —
  ;; the connection machine dispatches `[:ws/handle-message body]`, and this
  ;; handler is where it lands in app-db for the views to read.
  ;;
  ;; This is a SYSTEM BOUNDARY: the body is the server's bytes, and on a
  ;; compromised or hostile server it is whatever the sender chose.
  ;; `:rf.schema/at-boundary` is what keeps the check in the RELEASE build
  ;; (a bare `:schema` is a dev diagnostic and elides under :advanced). A
  ;; frame that fails the closed `InboundMessage` union is dropped whole:
  ;; the handler never runs, nothing reaches app-db, and the always-on
  ;; `:rf.error/schema-validation-failure` record is the counter to alert
  ;; on. See spec/Pattern-WebSocket.md §Inbound frames are untrusted.
  ;;
  ;; The connection machine holds the frame to this SAME contract one step
  ;; earlier, in its `:trusted-frame?` guard — it has to, because it clears
  ;; correlation slots the frame names. That is not a reason to relax the
  ;; check here: this is the ingress app-db is behind, and every refused
  ;; frame the machine forwards arrives here precisely so this handler
  ;; produces the one canonical rejection record.
  (rf/reg-event :ws/handle-message
    {:doc "Fold one inbound frame into app-db. UNTRUSTED INGRESS — the body
           is validated against the closed `InboundMessage` wire union, in
           every build, before this handler runs. It joins the
           [:messages :received] log, and if it's a correlated reply it also
           lands at [:messages :last-reply]. Each one gets a monotonic
           `:rx-seq` stamp on the way in — that's the inbox's stable React
           `:key`. (Server pushes carry no `:request-id`, so we can't lean
           on identity from the wire, and list position shifts as new
           messages arrive at the top.)"
     :schema       [:cat [:= :ws/handle-message] schema/InboundMessage]
     :interceptors [:rf.schema/at-boundary]}
    (fn handler-ws-handle-message [{:keys [db]} [_ body]]
      {:db (let [rx-seq (get-in db [:messages :rx-count] 0)]
        (-> db
            (update-in [:messages :received]
                       (fn [received]
                         (vec (cons (assoc body :rx-seq rx-seq) (or received [])))))
            (assoc-in [:messages :rx-count] (inc rx-seq))
            (cond-> (:request-id body)
              (assoc-in [:messages :last-reply] body))))}))

  ;; --- app-level events -------------------------------------------------
  (rf/reg-event :ws.app/send
    {:doc "Send the form's current draft, and clear the input."}
    (fn handler-app-send [{:keys [db]} [_ body]]
      {:db (assoc-in db [:messages :draft] "")
       :fx [[:dispatch [:ws/connection [:ws/send {:type :note :body body}]]]]}))

  ;; The request-id is a fact the system has to *remember*: it's written into
  ;; the machine's :in-flight slot, and the reply that eventually comes back
  ;; is matched against it. So it can't be conjured with a bare
  ;; `(random-uuid)` at the handler — replay would mint a fresh one, it
  ;; wouldn't match the recorded request, and the correlation would silently
  ;; fall apart. The fix is to make the id a *recordable* coeffect: a
  ;; `reg-cofx` that runs at context-assembly, gets written onto the event's
  ;; causal token, and is replayed back verbatim. `:ws.app/request` asks for
  ;; it via `:rf.cofx/requires` and reads it from the coeffects map.
  ;; See docs/core/glossary.md#recordable-vs-ambient-coeffects.
  (rf/reg-cofx :ws.app/request-id
    {:recordable? true
     :doc "A replayable correlation id for one request-reply round-trip."}
    (fn [] (random-uuid)))

  (rf/reg-event :ws.app/request
    {:doc "Fire off a request and expect a reply back. It goes through the
           connection machine's correlation slot; when the mock server
           echoes, the reply turns up at [:messages :last-reply]. The
           correlation id comes from the `:ws.app/request-id` recordable
           coeffect (never minted on the spot), so a replay reuses the same
           id and the reply still finds its request.

           Worth being clear: this is *the app's* request/reply, not a
           framework feature. re-frame2 ships no managed WebSocket, so
           per-message correlation over an open socket is yours to build —
           here, from a `:request-id`, a registered `:reply` target, and the
           machine's `:in-flight` map. The wire `:request-id` is your own
           protocol detail, kept deliberately separate from anything the
           framework does."
     :rf.cofx/requires [:ws.app/request-id]}
    (fn handler-app-request [{rid :ws.app/request-id} [_ body]]
      {:fx [[:dispatch [:ws/connection
                        [:ws/request {:request-id rid
                                      :body       {:type :request
                                                   :body body}
                                      :reply      [:ws.app/request-reply]
                                      :timeout-ms 5000}]]]]}))

  (rf/reg-event :ws.app/request-reply
    {:doc "The request's outcome arrived. Two producers reach this event,
           and the closed `RequestOutcome` union admits exactly those two —
           discriminating on `:origin`, which the connection machine stamps
           after receipt and no sender can forge. (1) `:ws/server` — the
           correlated WIRE reply, and it reached this event only because the
           `:request-token` it echoed still identified the registration in
           the slot: a reply under a REUSED id that answers a registration
           since displaced never gets here. This is the SECOND untrusted
           ingress: a reply arriving under a known :request-id is still the
           server's bytes, held to the closed `ReplyMessage` fields in every
           build.
           (2) `:ws/local` — a failure the machine minted itself when the
           socket dropped or the deadline elapsed. Local truth about the
           connection, and unreachable from the wire.

           Both are checked here even though the machine already held the
           frame to the same wire contract upstream. The two checks answer
           different questions: the machine's guards protect its own
           correlation state, this one protects app-db, and a boundary that
           only holds because something upstream is careful is not a
           boundary. Either way the outcome files at [:messages :last-reply].
           See :ws.app/request for the correlation it completes."
     :schema       [:cat [:= :ws.app/request-reply] schema/RequestOutcome]
     :interceptors [:rf.schema/at-boundary]}
    (fn handler-app-request-reply [{:keys [db]} [_ body]]
      {:db (assoc-in db [:messages :last-reply] body)}))

  (rf/reg-event :ws.app/subscribe-demo
    {:doc "Subscribe to a demo topic. The mock server acks with a synthetic
           push, so you get to watch the subscribe-then-push shape play out
           end to end."}
    (fn handler-app-subscribe-demo [_ _]
      {:fx [[:dispatch [:ws/connection [:ws/subscribe :demo-topic]]]]}))

  (rf/reg-event :ws.app/edit-draft
    (fn handler-app-edit-draft [{:keys [db]} [_ text]]
      {:db (assoc-in db [:messages :draft] text)}))

  (rf/reg-event :ws.messages/initialise
    (fn handler-messages-initialise [{:keys [db]} _]
      {:db (assoc db :messages {:draft "" :received [] :last-reply nil :rx-count 0})}))

  ;; --- subs -------------------------------------------------------------
  (rf/reg-sub :messages/slice
    (fn [db _] (:messages db)))

  (rf/reg-sub :messages/draft
    {:inputs [[:messages/slice]]}
    (fn [[m] _] (:draft m)))

  (rf/reg-sub :messages/received
    {:inputs [[:messages/slice]]}
    (fn [[m] _] (:received m)))

  (rf/reg-sub :messages/last-reply
    {:inputs [[:messages/slice]]}
    (fn [[m] _] (:last-reply m))))

;; Loading this namespace registers it — the production-app idiom.
(register!)
