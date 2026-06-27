(ns websocket.messages
  "The `:websocket/socket` actor plus outbound/inbound message handling.

   This file plays two roles:

   1. **Socket actor (`:websocket/socket`).** The child machine the
      connection machine spawns on entry to `:active`. Its `:open` state
      translates `:send` events into wire-level writes and forwards
      inbound server messages back to the parent. Making the actor own the
      socket keeps the JS `WebSocket` (a host-side reference, not a value)
      out of app-db. See docs/machines/glossary.md#spawn.

   2. **Mock-server bridge.** A small in-process JS `WebSocket`-shaped stub
      the example uses in place of a real endpoint, so it runs standalone.
      The stub state lives in the `mock-server-state` atom. The exported
      fns are the delivery seams: `set-mock-sync!` flips the stub between
      async (`setTimeout`-deferred) and sync (immediate `dispatch-sync`)
      delivery; `send-server-push!` and `simulate-disconnect!` push inbound
      `:received` / disconnect events from view click handlers; and
      `reset-mock-server!` clears the side-table between tests. Sync mode
      lets a test observe a full request/reply round-trip without yielding
      to the JS event loop.

   The split is intentional: a real app swaps the bridge for a real
   `(js/WebSocket. url)` and leaves the actor machine untouched. The
   pattern — machine owns the actor; actor owns the host-side reference —
   does not change with the transport."
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [websocket.schema]))

;; ============================================================================
;; HOST-SIDE SOCKET STORE
;; ============================================================================
;;
;; The JS `WebSocket` (or its mock stand-in) is a stateful reference. It
;; cannot live in app-db: it doesn't serialise, it doesn't survive a
;; time-travel replay, and writing it would defeat re-frame's value
;; semantics. So it lives in a host-side store keyed by the actor's
;; `:rf/self-id`, owned by the actor's machine handler. The actor writes
;; on open and clears on close.

(defonce ^:private sockets-by-actor (atom {}))

(defn- store-socket! [actor-id socket]
  (swap! sockets-by-actor assoc actor-id socket))

(defn- get-socket [actor-id]
  (get @sockets-by-actor actor-id))

(defn- clear-socket! [actor-id]
  (swap! sockets-by-actor dissoc actor-id))

;; ============================================================================
;; MOCK WEBSOCKET SERVER
;; ============================================================================
;;
;; A tiny `js/WebSocket`-shaped object for the tests and the example's own
;; buttons. It supports two interaction shapes:
;;
;;   (1) Auto-echo replies for outbound `{:type :request ...}`. The server
;;       echoes a reply on the same socket carrying the original
;;       `:request-id`, so the connection machine's request-reply
;;       correlation slot lights up.
;;
;;   (2) Manual `(send-server-push! body)` and `(simulate-disconnect!)`
;;       seams the views call from button handlers.
;;
;; In async mode, inbound deliveries land on the next task tick (via
;; `js/setTimeout _ 0`, in the `later` helper below) so transport events
;; fire after the dispatch returns, not inside it.

(defonce ^:private mock-server-state (atom {:sockets {} :sync? false}))

(defn set-mock-sync!
  "Toggle the mock server between async (default, `setTimeout`-deferred)
   and sync (immediate-delivery) modes. The tests use sync mode so
   `rf/dispatch-sync` observes the full request/reply round-trip without
   yielding to the JS event loop."
  [sync?]
  (swap! mock-server-state assoc :sync? sync?))

(defn reset-mock-server!
  "Clear every stored mock socket, so each test starts with a fresh
   mock-server side-table. Without this the `:sockets` map accumulates
   across tests and `send-server-push!` delivers N copies of every push."
  []
  (swap! mock-server-state assoc :sockets {}))

(defn- later
  "Run `f` synchronously in sync mode; via `setTimeout` otherwise. This is
   the only knob that separates sync mode (the tests) from the
   browser-driven example (async, `setTimeout`-deferred)."
  [f]
  (if (:sync? @mock-server-state)
    (f)
    (js/setTimeout f 0)))

(defn- next-mock-socket-id []
  (str "mock-socket-" (random-uuid)))

(defn- deliver-to-actor!
  "Dispatch an inbound transport event into the spawned actor. The actor's
   machine handler translates the event into a parent-bound
   `[:ws/connection [:ws/<kind> ...]]` dispatch.

   In async mode this fires from a detached `setTimeout` callback, which
   carries no ambient frame; a bare `rf/dispatch` here would raise
   `:rf.error/no-frame-context`. So the caller captures its frame's
   `dispatch` and threads it in. A captured `dispatch` carries the frame
   across the async boundary.
   See docs/guide/glossary.md#frame-handle."
  [dispatch actor-id kind payload]
  (when actor-id
    (dispatch [actor-id [kind payload]])))

(defn- mock-encode-auth-reply [token]
  ;; A real server would validate the JWT; the mock accepts any
  ;; non-empty token and rejects the rest.
  (if (and (string? token) (pos? (count token)))
    {:type :auth-ok}
    {:type :auth-failed :reason "Empty token"}))

(defn mock-socket-for-actor
  "Returns a function that opens a fresh mock socket bound to the
   actor's id. The returned :send fn handles every outbound message
   the actor produces; the mock auto-routes `:auth` (→ `:auth-ok`)
   and `:request` (→ correlated reply via the `:type :reply` echo)."
  [actor-id _url _auth-token]
  (let [id   (next-mock-socket-id)
        open? (atom true)
        ;; This fn runs inside the actor's `:open-socket` action, so it has
        ;; a frame in scope. Capture that frame's `dispatch` now and thread
        ;; it into every (async, `later`-deferred) inbound delivery below;
        ;; the detached `setTimeout` callback has no frame of its own.
        ;; See docs/guide/glossary.md#frame-handle.
        dispatch (:dispatch (rf/frame-handle))]
    (swap! mock-server-state assoc-in [:sockets id]
           {:actor-id actor-id
            :open?    open?})
    {:id    id
     :open? open?
     :send  (fn mock-send [msg]
              (when @open?
                (case (:type msg)
                  :auth
                  ;; Auth — produce :auth-ok / :auth-failed on the same
                  ;; channel on the next task tick (via `later`).
                  (later
                    #(deliver-to-actor! dispatch actor-id :received
                                        (mock-encode-auth-reply (:token msg))))

                  :request
                  ;; Auto-echo: the mock server treats every :request as
                  ;; a "please reply with what I sent + :ok"; the
                  ;; request-id round-trips so the connection machine's
                  ;; request-reply correlation lights up.
                  (later
                    #(deliver-to-actor! dispatch actor-id :received
                                        {:type       :reply
                                         :request-id (:request-id msg)
                                         :ok         true
                                         :echo       (dissoc msg :request-id)}))

                  :subscribe
                  ;; The mock acks subscribes with one synthetic push so
                  ;; the example demonstrates server-pushed events
                  ;; arriving after the subscribe round-trip.
                  (later
                    #(deliver-to-actor! dispatch actor-id :received
                                        {:type :push
                                         :topic (:topic msg)
                                         :note  "subscribed"}))

                  ;; Default: no-op (the example doesn't model fire-and-
                  ;; forget app-level sends beyond the cases above).
                  nil)))
     :close (fn mock-close []
              (reset! open? false)
              (swap! mock-server-state update :sockets dissoc id)
              (later
                #(deliver-to-actor! dispatch actor-id :closed {:code 1000})))}))

;; Exposed seams the views (and the tests) use to drive the mock without
;; dispatching through the actor.

(defn- deliver-external!
  "Variant of `deliver-to-actor!` for callers outside a running cascade
   (test bodies, view click handlers). In sync mode it uses
   `dispatch-sync` so the chain runs to fixed point before returning; in
   async mode it uses the queued `dispatch`.

   A view click handler fires outside any frame scope, so the caller
   supplies a frame-bound `dispatch` / `dispatch-sync` pair (from a
   captured `(rf/frame-handle)`). A bare `rf/dispatch` here would raise
   `:rf.error/no-frame-context`. See docs/guide/glossary.md#frame-handle."
  [{:keys [dispatch dispatch-sync]} actor-id kind payload]
  (when actor-id
    (if (:sync? @mock-server-state)
      (dispatch-sync [actor-id [kind payload]])
      (dispatch      [actor-id [kind payload]]))))

(defn send-server-push!
  "Deliver a synthetic server push to every live mock socket. Drives the
   inbound translation path from the 'Trigger server push' button and the
   tests.

   `handle` is a `(rf/frame-handle)` bundle carrying the caller's frame, so
   the deferred dispatch carries a frame across the click-handler / async
   boundary. See docs/guide/glossary.md#frame-handle."
  [handle body]
  (doseq [[_ {:keys [actor-id open?]}] (:sockets @mock-server-state)]
    (when @open?
      (deliver-external! handle actor-id :received body))))

(defn simulate-disconnect!
  "Force every live mock socket closed, triggering the reconnect cascade
   in the parent. Drives the 'Drop connection' button. `handle` is the
   caller's `(rf/frame-handle)` bundle (see `send-server-push!`)."
  [handle]
  (doseq [[_ {:keys [actor-id open?]}] (:sockets @mock-server-state)]
    (when @open?
      (reset! open? false)
      (deliver-external! handle actor-id :closed {:code 1006 :reason "simulated"}))))

;; ============================================================================
;; THE SOCKET ACTOR — :websocket/socket
;; ============================================================================
;;
;; A small machine. `:opening` opens the host-side socket on entry and
;; transitions immediately to `:open`, where it stays for the lifetime of
;; the connection. The parent's `:spawn` destroys this actor on any exit
;; from `:active`, which handles cleanup.
;;
;; The actor reads the runtime-stamped `:rf/self-id` to tag its own
;; dispatches and `:rf/parent-id` to address dispatches back to the
;; parent. The runtime stamps both keys into a spawned actor's `:data`.

(def socket-actor-machine
  "The `:websocket/socket` actor machine spec. Held in a `def` so the
   `reg-machine` registration below can reference it."
    {:initial :opening
     :data    {:url        nil
               :auth-token nil}

     :actions
     {:open-socket
      ;; Entry action — instantiate the host-side mock socket and
      ;; report `:opened` back to the parent.
      (fn action-open-socket [{data :data}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)
              socket    (mock-socket-for-actor self-id
                                               (:url data)
                                               (:auth-token data))]
          (store-socket! self-id socket)
          ;; Report `:opened` to the parent as a `:dispatch` fx, not a bare
          ;; `(rf/dispatch ...)` from inside the action body. A `:dispatch`
          ;; fx inherits the cascade's frame; a bare dispatch from an
          ;; action body runs outside any frame scope and raises
          ;; `:rf.error/no-frame-context`.
          {:fx [[:dispatch [parent-id [:ws/opened {:source-socket-id self-id}]]]]}))

      :send-via-socket
      ;; The parent dispatches `[<actor-id> [:send body]]` for every
      ;; outbound message; we route through the host-side `:send`.
      (fn action-send-via-socket [{data :data [_ body] :event}]
        (let [self-id (:rf/self-id data)]
          (when-let [socket (get-socket self-id)]
            ((:send socket) body)))
        nil)

      :forward-received
      ;; The server's reply arrives via `[<actor-id> [:received body]]`.
      ;; Forward it to the parent stamped with the socket id, so
      ;; `:current-socket?` can drop messages from a torn-down socket.
      (fn action-forward-received [{data :data [_ body] :event}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)
              ;; Branch on body's :type: :auth-ok / :auth-failed land on
              ;; the parent as their own events; everything else lands as
              ;; :ws/received with the body in tow.
              ev        (case (:type body)
                          :auth-ok     [:ws/auth-ok {:source-socket-id self-id}]
                          :auth-failed [:ws/auth-failed
                                        {:source-socket-id self-id
                                         :error            (:reason body)}]
                          [:ws/received {:source-socket-id self-id
                                         :body             body}])]
          {:fx [[:dispatch [parent-id ev]]]}))

      :forward-closed
      (fn action-forward-closed [{data :data [_ {:keys [code reason]}] :event}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)]
          (clear-socket! self-id)
          {:fx [[:dispatch [parent-id
                            [:ws/closed {:source-socket-id self-id
                                         :code             code
                                         :reason           reason}]]]]}))}

     :states
     {:opening
      ;; The initial state's `:entry` runs once as the actor comes to
      ;; life. We open the host-side socket here and transition straight
      ;; to `:open` via the `:always` slot once the socket is stored.
      ;;
      ;; The actor may also receive `:send` from the parent's `:send-auth`
      ;; entry action before its own entry has settled, so `:opening`
      ;; handles `:send` too, picking up the just-stored socket.
      {:entry :open-socket
       :always [{:target :open}]
       :on    {:send       {:target :open
                            :action :send-via-socket}
               :received   {:target :open
                            :action :forward-received}
               :closed     {:target :closed
                            :action :forward-closed}}}

      :open
      {:on {:send     {:action :send-via-socket}
            :received {:action :forward-received}
            :closed   {:target :closed
                       :action :forward-closed}}}

      :closed
      ;; Terminal. The parent's exit-from-:active cascade destroys this
      ;; actor; this state just absorbs any late events.
      {}}})

;; ============================================================================
;; REGISTRATIONS
;; ============================================================================

;; The socket actor the connection machine spawns. `rf/reg-machine` tags
;; the registration `:rf/machine? true`, which the spawn-fx needs to
;; resolve the spawn target.
(rf/reg-machine :websocket/socket socket-actor-machine)

;; The connection machine dispatches [:ws/handle-message body] for every
;; received message (correlated reply or server push); this handler folds
;; it into app-db for the views.
(rf/reg-event :ws/handle-message
  {:doc "Translate an inbound `:ws/received` body into an app-db write.
         Records the message in the [:messages :received] log + stashes
         the latest correlated reply at [:messages :last-reply] when
         applicable. Each message is stamped with a monotonic `:rx-seq`
         so the inbox view can give every <li> a stable React :key —
         server pushes carry no `:request-id`, so position can't be used
         as identity once the newest-first list grows."}
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
  {:doc "Submit the form's draft as an outbound message."}
  (fn handler-app-send [{:keys [db]} [_ body]]
    {:db (assoc-in db [:messages :draft] "")
     :fx [[:dispatch [:ws/connection [:ws/send {:type :note :body body}]]]]}))

;; The request-id is a durable correlation fact: it is written into the
;; connection machine's :in-flight slot and the eventual reply is matched
;; against it. A durable id must be a recorded fact, never an ambient
;; `(random-uuid)` read at the handler write site — otherwise replay mints
;; a different id and the correlation no longer matches the recorded
;; request. So the generator is a recordable `reg-cofx`: it runs at
;; context-assembly, its id is recorded on the event's causal token, and
;; replay re-presents it verbatim. `:ws.app/request` declares it via
;; `:rf.cofx/requires` and reads it from the coeffects map.
;; See docs/guide/glossary.md#recordable-vs-ambient-coeffects.
(rf/reg-cofx :ws.app/request-id
  {:recordable? true
   :doc "Replayable correlation id for an outbound request-reply."}
  (fn [] (random-uuid)))

(rf/reg-event :ws.app/request
  {:doc "Issue a request-reply via the connection machine's correlation
         slot. The reply lands at [:messages :last-reply] once the mock
         server echoes back. The correlation id comes from the
         `:ws.app/request-id` recordable coeffect, never minted ambiently,
         so replay re-presents the same id.

         This is app-level correlation, not a framework reply envelope.
         re-frame2 does not ship a managed WebSocket, so a per-message
         request/reply over the open socket is correlation the app owns: a
         per-message `:request-id`, a registered `:reply` event target, and
         the connection machine's `:in-flight` map. The wire `:request-id`
         is app-level protocol correlation, deliberately separate from the
         framework's own reply vocabulary."
   :rf.cofx/requires [:ws.app/request-id]}
  (fn handler-app-request [{rid :ws.app/request-id} [_ body]]
    {:fx [[:dispatch [:ws/connection
                      [:ws/request {:request-id rid
                                    :body       {:type :request
                                                 :body body}
                                    :reply      [:ws.app/request-reply]
                                    :timeout-ms 5000}]]]]}))

(rf/reg-event :ws.app/request-reply
  {:doc "Reply event fired by the connection machine's :register-request
         flow once the correlated reply lands. The arg is the app's own
         reply body (the server echo) — the app-level correlation shape
         (see :ws.app/request)."}
  (fn handler-app-request-reply [{:keys [db]} [_ body]]
    {:db (assoc-in db [:messages :last-reply] body)}))

(rf/reg-event :ws.app/subscribe-demo
  {:doc "Demo subscription — the mock server acks with a synthetic
         server push so the app demonstrates the subscribe-then-push
         shape."}
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
  :<- [:messages/slice]
  (fn [m _] (:draft m)))

(rf/reg-sub :messages/received
  :<- [:messages/slice]
  (fn [m _] (:received m)))

(rf/reg-sub :messages/last-reply
  :<- [:messages/slice]
  (fn [m _] (:last-reply m)))
