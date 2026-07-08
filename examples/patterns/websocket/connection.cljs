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
   `:rf/machine-has-tag?` sub). It doesn't care which leaf carries the
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
   what makes it a safe connection clock — no `:exit` action nulling
   anything, nothing to remember to clean up."
  (:require [re-frame.core :as rf]
            ;; `re-frame.machines` ships in day8/re-frame2-machines.
            ;; Requiring it is what wires up the machine vocabulary: the
            ;; late-bind hook behind `re-frame.machines/make-machine-handler`,
            ;; the `:rf.machine/spawn` / `:rf.machine/destroy` fx, and the
            ;; `:rf/machine` / `:rf/machine-has-tag?` subs you'll see used
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

(def connection-machine
  "The `:ws/connection` machine spec, kept in its own `def` so we can hand
   it to `reg-machine` below."
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
     :data    {:url            nil
               :auth-token     nil
               :retries        0
               :max-retries    8
               :base-ms        100
               :max-backoff-ms 5000
               :subscriptions  #{}
               :queue          []
               :in-flight      {}
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
        (let [live (socket-id data)]
          (and (some? live)
               (= source-socket-id live))))}

     :actions
     {:record-connection-opts
      (fn action-record-connection-opts [{data :data [_ {:keys [url auth-token]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :error nil))})

      :record-and-reset
      ;; Record fresh connection opts and zero the retry counter in one go.
      ;; This runs on a *manual* `:ws/connect` out of `:reconnecting` or
      ;; `:failed` — the user is asking for a clean slate, so we give them
      ;; one and forget the failed attempts.
      (fn action-record-and-reset [{data :data [_ {:keys [url auth-token]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :retries 0)
                   (assoc :error nil))})

      :refresh-token
      (fn action-refresh-token [{data :data [_ token] :event}]
        {:data (assoc data :auth-token token)})

      :on-socket-lost
      ;; The live socket just dropped (a `:ws/closed` that passed
      ;; `:current-socket?`). Two things happen as we head to `:reconnecting`:
      ;;   1. Bump the retry counter — it drives the `:after` backoff and the
      ;;      `:max-retries-exceeded?` guard.
      ;;   2. FAIL every in-flight request. Each was already put on the wire
      ;;      that just died, so its reply can never arrive on this
      ;;      connection; left in `:in-flight` it would leak forever — its
      ;;      timeout is stamped with the now-dead socket id, so the
      ;;      `:current-socket?` guard drops that timeout after we reconnect
      ;;      and the slot never clears. Loss semantics are FAIL, not replay:
      ;;      the server may already have processed the request before the
      ;;      drop, so a blind re-send risks double execution — failing is the
      ;;      safe, explicit default. Each waiting `:reply-event` is fired
      ;;      with a `{:ok false :error :ws/connection-lost}` body so the
      ;;      caller learns the outcome instead of hanging forever.
      (fn action-on-socket-lost [{data :data}]
        {:data (-> data
                   (update :retries inc)
                   (assoc :in-flight {}))
         :fx   (into []
                     (keep (fn [[rid {:keys [reply-event]}]]
                             (when reply-event
                               [:dispatch (conj reply-event
                                                {:request-id rid
                                                 :ok         false
                                                 :error      :ws/connection-lost})])))
                     (:in-flight data))})

      :reset-retries
      (fn action-reset-retries [{data :data}]
        {:data (assoc data :retries 0)})

      :record-error
      (fn action-record-error [{data :data [_ {:keys [error]}] :event}]
        {:data (assoc data :error error)})

      :send-auth
      ;; We've just entered `:authenticating`, so send the credentials:
      ;; route an `:auth` message into the live socket actor and wait for
      ;; the server to bless us.
      (fn action-send-auth [{data :data}]
        {:fx [[:dispatch [(socket-id data)
                          [:send {:type  :auth
                                  :token (:auth-token data)}]]]]})

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
      ;; The `:always` step on `:connected`: if anything piled up in the
      ;; queue while we were offline, drain it onto the wire now and clear
      ;; it. Everything the user typed during the outage finally goes out.
      (fn action-flush-queue [{data :data}]
        (let [q (:queue data)]
          {:data (assoc data :queue [])
           :fx   (mapv (fn [msg]
                         [:dispatch [(socket-id data) [:send msg]]])
                       q)}))

      :enqueue-message
      (fn action-enqueue-message [{data :data [_ msg] :event}]
        {:data (update data :queue conj msg)})

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
      (fn action-register-request [{data :data [_ {:keys [request-id body reply timeout-ms]
                                            :or   {timeout-ms 30000}}] :event}]
        {:data (assoc-in data [:in-flight request-id]
                         {:reply-event reply :timeout-ms timeout-ms})
         :fx   [[:dispatch [(socket-id data)
                            [:send (assoc body :request-id request-id)]]]
                ;; The timeout event carries the live socket-id too, so the
                ;; same `:current-socket?` guard quietly discards a timeout
                ;; left over from a connection we've already moved past.
                [:dispatch-later
                 {:ms    timeout-ms
                  :event [:ws/connection
                          [:ws/request-timeout
                           {:request-id       request-id
                            :source-socket-id (socket-id data)}]]}]]})

      :clear-request
      (fn action-clear-request [{data :data [_ {:keys [request-id]}] :event}]
        {:data (update data :in-flight dissoc request-id)})

      :receive-message
      ;; A message arrived and the `:current-socket?` guard has already
      ;; vouched for it. Now, is it a reply we were waiting for, or an
      ;; out-of-the-blue server push? A `:request-id` in the body tells us:
      ;; if it's there, fire the reply event we stashed and clear the
      ;; in-flight slot; if not, it's a push — hand it to
      ;; `[:ws/handle-message body]`.
      (fn action-receive-message [{data :data [_ {:keys [body]}] :event}]
        (if-let [rid (:request-id body)]
          (let [{:keys [reply-event]} (get-in data [:in-flight rid])]
            {:data (update data :in-flight dissoc rid)
             :fx   (cond-> [[:dispatch [:ws/handle-message body]]]
                     reply-event (conj [:dispatch (conj reply-event body)]))})
          {:fx [[:dispatch [:ws/handle-message body]]]}))}

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
                ;; Hand the child the URL and token from our `:data` as it's
                ;; born. Every fresh entry to :active re-reads whatever's
                ;; current — so a token refreshed mid-reconnect just rides
                ;; into the next socket, no extra plumbing.
                :data       (fn [{snap :snapshot}]
                              {:url        (-> snap :data :url)
                               :auth-token (-> snap :data :auth-token)})}

       ;; Transitions every leaf inherits. A leaf can override any of these
       ;; (deepest state wins); anything it doesn't handle falls through to
       ;; here. See docs/machines/glossary.md#transition.
       :on    {:ws/closed   {:guard  :current-socket?
                             :target :reconnecting
                             :action :on-socket-lost}
               :ws/fatal    {:target :failed
                             :action :record-error}
               :ws/send     {:action :enqueue-message}
               :ws/request  {:action :enqueue-message}
               :ws/refresh-token {:action :refresh-token}
               :ws/disconnect {:target :disconnected}
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
                 :ws/auth-failed {:guard  :current-socket?
                                  :target [:failed]
                                  :action :record-error}}}

        :connected
        {:tags   #{:websocket/active :websocket/connected}
         :entry  :flush-queue-and-resubscribe
         :always [{:guard :has-queued-messages? :action :flush-queue}]
         :on     {:ws/received {:guard  :current-socket?
                                :action :receive-message}
                  ;; Here we override the parent's :ws/send: connected
                  ;; means no queue, send it now.
                  :ws/send     {:action :send-now}
                  :ws/request  {:action :register-request}
                  :ws/subscribe {:action :register-subscription}
                  :ws/request-timeout {:guard  :current-socket?
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
                :ws/refresh-token {:action :refresh-token}
                :ws/disconnect    {:target :disconnected
                                   :action :reset-retries}}}

      :failed
      {:tags #{:websocket/failed}
       :on   {:ws/connect       {:target :active
                                 :action :record-and-reset}
              :ws/refresh-token {:action :refresh-token}
              :ws/disconnect    {:target :disconnected
                                 :action :reset-retries}}}}})

;; ============================================================================
;; MACHINE HANDLER + SUBSCRIPTIONS + INIT EVENT
;; ============================================================================

;; `reg-machine` marks this registration `:rf/machine? true` — the flag a
;; declarative `:spawn` looks for when resolving its target. Forget it and
;; the spawn quietly does nothing, which is a fun afternoon to debug.
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
;; `:rf/machine-has-tag?` (sugar: `(rf/machine-has-tag? :ws/connection
;; tag)`), which returns the snapshot's tag-containment bit directly — so a
;; view can ask "connected?" and get a boolean without unpacking the
;; hierarchical `:state` vector or re-reading the snapshot itself.
;; See docs/machines/glossary.md#state-tag.
(rf/reg-sub :ws/connecting?
  :<- [:rf/machine-has-tag? :ws/connection :websocket/connecting]
  (fn [has-tag? _] has-tag?))

(rf/reg-sub :ws/authenticating?
  :<- [:rf/machine-has-tag? :ws/connection :websocket/authenticating]
  (fn [has-tag? _] has-tag?))

(rf/reg-sub :ws/connected?
  :<- [:rf/machine-has-tag? :ws/connection :websocket/connected]
  (fn [has-tag? _] has-tag?))

(rf/reg-sub :ws/reconnecting?
  :<- [:rf/machine-has-tag? :ws/connection :websocket/reconnecting]
  (fn [has-tag? _] has-tag?))

(rf/reg-sub :ws/failed?
  :<- [:rf/machine-has-tag? :ws/connection :websocket/failed]
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
    {:fx [[:dispatch [:ws/connection [:rf.machine/start]]]]}))
