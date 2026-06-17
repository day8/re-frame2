(ns websocket.messages
  "The `:websocket/socket` actor + outbound/inbound message handling
   for the Pattern-WebSocket example.

   This file plays two roles:

   1. **Socket actor (`:websocket/socket`).** The spawned child the
      connection machine `:spawn`s on entry to `:active`. The actor
      is itself a state machine — its `:open` state translates `:send`
      events into wire-level writes and forwards inbound server
      messages back to the parent connection machine. Pattern-WebSocket
      §The connection state machine names this as a child actor
      explicitly so the JS `WebSocket` (which is a host-side reference,
      not a value) lives outside `app-db`.

   2. **Mock-server bridge.** A small in-process JS `WebSocket`-shaped
      stub the example uses when no real WebSocket endpoint is
      available — keeps the example standalone for the headless tests
      (in the framework test tree at
      `implementation/adapters/reagent/test/re_frame/websocket_cljs_test.cljs`;
      the example tree is test-free, rf2-8cevm). The stub state lives in
      the `mock-server-state` atom; plain exported fns are the delivery
      seams — `set-mock-sync!` flips the stub between async (microtask)
      and sync (immediate `dispatch-sync`) delivery, `send-server-push!`
      and `simulate-disconnect!` push inbound `:received` / disconnect
      events from view click handlers, and `reset-mock-server!` clears
      the side-table between tests. Sync mode lets the headless tests
      observe a full request/reply round-trip without yielding to the
      JS event loop.

   The split is intentional: production apps swap the bridge for a real
   `(js/WebSocket. url)` and leave the actor machine untouched. The
   pattern (machine-owns-the-actor; actor-owns-the-host-side-reference)
   does not change with the transport."
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [websocket.schema]))

;; ============================================================================
;; HOST-SIDE SOCKET STORE
;; ============================================================================
;;
;; The JS `WebSocket` (or its mock stand-in) is a stateful reference. It
;; cannot live in `app-db` — it doesn't serialise, it doesn't survive a
;; Tool-Pair epoch replay, and writing it would defeat re-frame's value
;; semantics. The canonical answer is a host-side weak store keyed by
;; the actor's `:rf/self-id`, owned by the actor's machine handler. The
;; actor's `:on-spawn` writes; the actor's `:on-destroy` clears.

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
;; Implements a tiny `js/WebSocket`-shaped object for the headless tests
;; and the example's own buttons. Supports two interaction shapes:
;;
;;   (1) Auto-echo replies for outbound `{:type :request ...}`. The
;;       server immediately echoes a reply on the same socket carrying
;;       the original `:request-id` so the connection machine's
;;       request-reply correlation slot lights up.
;;
;;   (2) Manual `(send-server-push! body)` and `(simulate-disconnect!)`
;;       seams the views call from button handlers.
;;
;; The mock is deliberately synchronous-ish: `connect` resolves on the
;; next microtask (via js/Promise.resolve) so the `:open` event lands
;; after the dispatch returns, not inside it.

(defonce ^:private mock-server-state (atom {:sockets {} :sync? false}))

(defn set-mock-sync!
  "Toggle the mock server between async (default, microtask-delivered)
   and sync (immediate-delivery) modes. Sync mode is used by the
   headless tests so `rf/dispatch-sync` observes the full
   request/reply round-trip without yielding to the JS event loop."
  [sync?]
  (swap! mock-server-state assoc :sync? sync?))

(defn reset-mock-server!
  "Clear every stored mock socket. Used by the headless test fixture
   to ensure each test starts with a fresh mock-server side-table —
   without this, the `:sockets` map accumulates across tests and
   `send-server-push!` delivers N copies of every push."
  []
  (swap! mock-server-state assoc :sockets {}))

(defn- later
  "Run `f` synchronously in mock-sync mode; via `setTimeout` otherwise.
   This is the only knob that separates headless-test mode (sync) from
   the browser-driven example (async, microtask-delivered)."
  [f]
  (if (:sync? @mock-server-state)
    (f)
    (js/setTimeout f 0)))

(defn- next-mock-socket-id []
  (str "mock-socket-" (random-uuid)))

(defn- deliver-to-actor!
  "Dispatch an inbound transport event into the spawned actor. The
   actor's machine handler translates the event into a parent-bound
   `[:ws/connection [:ws/<kind> ...]]` dispatch.

   EP-0002 (rf2-9o48ih): inbound deliveries fire from the mock transport's
   `later` callback — a detached `setTimeout` in async (browser) mode that
   carries NO ambient frame, so a bare `rf/dispatch` here would raise
   `:rf.error/no-frame-context`. The caller (`mock-socket-for-actor`) is
   created inside the `:open-socket` action — i.e. under the actor's drain
   frame — so it captures that frame's `dispatch` operation and threads it in
   here. Passing the captured `dispatch` honours the carried invariant across
   the async boundary (Spec 002 §frame-handle)."
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
        ;; EP-0002 (rf2-9o48ih): this fn runs inside the `:open-socket` action
        ;; — i.e. under the actor's drain frame — so capture that frame's
        ;; `dispatch` now and thread it into every (async, `later`-deferred)
        ;; inbound delivery below. The detached `setTimeout` callback carries
        ;; no ambient frame; the captured operation does (Spec 002
        ;; §frame-handle).
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
                  ;; channel after a microtask.
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

;; Exposed seams the views (and the headless tests) use to drive the
;; mock without dispatching through the actor.

(defn- deliver-external!
  "Sync-mode-aware variant of `deliver-to-actor!` for callers OUTSIDE
   a running drain (i.e. test bodies, view click handlers). In sync
   mode it uses `dispatch-sync` so the chain runs to fixed point
   before returning; in async mode it falls back to the queued
   `dispatch`.

   EP-0002 (rf2-9o48ih): a view click handler fires outside React render
   scope and outside any drain, so the caller must supply a frame-bound
   `dispatch` / `dispatch-sync` pair (the operations a `reg-view`-injected
   `dispatch` / a captured `(rf/frame-handle)` provides). A bare ambient
   `rf/dispatch` here would raise `:rf.error/no-frame-context`."
  [{:keys [dispatch dispatch-sync]} actor-id kind payload]
  (when actor-id
    (if (:sync? @mock-server-state)
      (dispatch-sync [actor-id [kind payload]])
      (dispatch      [actor-id [kind payload]]))))

(defn send-server-push!
  "Deliver a synthetic server push to every live mock socket. Used by
   the headless tests and the example's 'Trigger server push' button
   to demonstrate the inbound translation path.

   `handle` is a `(rf/frame-handle)` operation bundle carrying the caller's
   frame (the view passes its injected handle; tests pass an explicit one) —
   EP-0002 requires the deferred dispatch carry a frame across the
   click-handler / async boundary."
  [handle body]
  (doseq [[_ {:keys [actor-id open?]}] (:sockets @mock-server-state)]
    (when @open?
      (deliver-external! handle actor-id :received body))))

(defn simulate-disconnect!
  "Force every live mock socket closed, triggering the reconnect
   cascade in the parent. Used by the 'Drop connection' button. `handle`
   is the caller's `(rf/frame-handle)` operation bundle (see
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
;; A small two-state machine. `:opening` opens the host-side socket on
;; entry, transitions to `:open` once the transport reports ready, and
;; stays there for the lifetime of the connection. The parent's
;; `:spawn` destroys this actor on any exit from `:active`, which
;; takes care of cleanup.
;;
;; The actor uses the runtime-stamped `:rf/self-id` to address
;; dispatches back to the parent's `:rf/parent-id`. The framework
;; reserves both keys under `:data :rf/*` for spawned actors.

(def socket-actor-machine
  "Spec for the `:websocket/socket` actor machine — held in a `def` so
   the spec reads cleanly and the `reg-machine` registration below can
   reference it."
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
          ;; Report `:opened` to the parent as a `:dispatch` fx (NOT
          ;; via `(rf/dispatch ...)` from inside the action body) so
          ;; the framework routes through the running drain's frame.
          ;; Important under the EP-0002 carried invariant (rf2-9o48ih):
          ;; a `:dispatch` fx inherits the cascade's frame, whereas a bare
          ;; `(rf/dispatch ...)` from inside an action body runs outside
          ;; any frame scope and raises `:rf.error/no-frame-context` — it
          ;; does NOT silently fall back to `:rf/default`.
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
      ;; The mock server's reply arrives via `[<actor-id> [:received body]]`.
      ;; Forward into the parent with the connection-epoch stamp so
      ;; `:current-socket?` can drop messages from a torn-down socket.
      (fn action-forward-received [{data :data [_ body] :event}]
        (let [self-id   (:rf/self-id data)
              parent-id (:rf/parent-id data)
              ;; Branch on body's :type. :auth-ok / :auth-failed land
              ;; on the parent as their own events; everything else
              ;; lands as :ws/received with the body in tow.
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
      ;; Per Spec 005 §Initial-state `:entry` fires on machine bootstrap:
      ;; the initial-state's `:entry` action runs once as part of
      ;; bringing the actor to life. We open the host-side socket on
      ;; bootstrap and transition immediately to `:open` via the
      ;; `:always` slot once the socket is stored.
      ;;
      ;; The actor may also receive `:send` from the parent's
      ;; `:send-auth` entry-action before its own bootstrap entry has
      ;; settled — the `:send` override here picks up the just-stored
      ;; host-side socket as soon as it lands.
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
      ;; Terminal: the parent's exit-from-:active cascade destroys this
      ;; actor; we don't need to do anything except absorb late events.
      {}}})

;; ============================================================================
;; REGISTRATIONS  (ns-load — the production-app idiom)
;; ============================================================================

;; The socket actor — Pattern-WebSocket §The connection state machine
;; names this as the child actor the parent invokes. Use `rf/reg-machine`
;; so the registration metadata carries `:rf/machine? true` (required
;; by the spawn-fx's `resolve-spawn-machine` lookup).
(rf/reg-machine :websocket/socket socket-actor-machine)

;; Server-pushed events — Pattern-WebSocket §Server-pushed events.
;; The connection machine dispatches [:ws/handle-message body] for
;; every received message (correlated or server-pushed); this
;; handler folds it into app-db for the views.
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

;; EP-0017: the request-id is a DURABLE correlation fact — it is written into
;; the connection machine's :in-flight slot and the eventual reply is matched
;; against it. A durable id must be a FOLDED FACT, never an ambient
;; `(random-uuid)` read at the handler write site — otherwise replay mints a
;; different id and the correlation no longer matches the recorded request.
;; The app-owned generator is a RECORDABLE `reg-cofx`: the generator runs at
;; context-assembly, the minted id is recorded onto the causal token, and
;; replay re-presents it verbatim. `:ws.app/request` DECLARES it via
;; `:rf.cofx/requires` and reads it flat from the coeffects map.
(rf/reg-cofx :ws.app/request-id
  {:recordable? true
   :doc "Replayable correlation id for an outbound request-reply (EP-0017)."}
  (fn [] (random-uuid)))

(rf/reg-event :ws.app/request
  {:doc "Issue a request-reply via the connection machine's
         correlation slot. The reply lands at [:messages :last-reply]
         once the mock server echoes back. The correlation id is folded
         from the `:ws.app/request-id` recordable coeffect (EP-0017),
         never minted ambiently — replay re-presents the same id.

         APP-LEVEL correlation, NOT the EP-0011 uniform reply envelope
         (rf2-eygytk). re-frame2 deliberately does not ship a managed
         WebSocket (Pattern-WebSocket / Managed-Effects §WebSocket), so a
         per-message request/reply over the open socket is a
         Pattern-AsyncEffect interaction whose correlation the app owns:
         a per-message `:request-id`, a registered `:reply` event target,
         and the connection machine's `:in-flight` map. This is the
         spec-blessed app/library convention — it is intentionally NOT the
         framework's `:rf/reply-to` + reply-map vocabulary (property 9 is
         exempt for the WebSocket surface). The wire `:request-id` is
         app-level protocol correlation, not the EP-0011 `:work/id`."
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
         reply BODY (the server echo), not an EP-0011 reply map — this is
         the app-level Pattern-WebSocket correlation shape, outside the
         uniform reply envelope (rf2-eygytk; see :ws.app/request)."}
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
