(ns websocket.connection
  "The `:ws/connection` connection machine.

   This models a WebSocket lifecycle as a state machine. The hierarchy is:

     :disconnected
     :active                              ;; compound parent; owns the socket
       :connecting
       :authenticating
       :connected
     :reconnecting                        ;; :after backoff
     :failed                              ;; terminal until manual :ws/connect

   Why a compound `:active` rather than parallel regions? The three
   success-path leaves share one invariant: the live socket actor must
   outlive all of them. The subscription set, the in-flight map, the queue,
   and the socket id all belong to one domain (this connection) and ride a
   single `:data` map. A compound `:active` over a shared `:data` is the
   right shape. Parallel regions are for axes that do NOT share data.
   See docs/machines/concepts.md#when-the-machine-grows.

   State tags carry the queryable connection-state predicates. The view asks
   `:ws/connected?` / `:ws/reconnecting?` (the per-tag subs below, each a
   `contains?` over the snapshot's `:tags` union) without knowing which leaf
   carries the `:connected` intent. This is what state tags buy: ask, don't
   tell. See docs/machines/glossary.md#state-tag.

   Two staleness defences:

     1. `:after` backoff — the runtime cancels a state's `:after` timer on
        exit and ignores any timer from a prior `:reconnecting` visit that
        fires late. See docs/machines/concepts.md#guards-actions-tags-and-after--the-recognition-kit.

     2. Connection epoch — the live `:socket-id` IS the epoch. The
        `:current-socket?` guard rejects a `:ws/received` from a socket that
        has since been replaced (a slow message landing post-reconnect).

   The socket actor — the thing that owns the JS WebSocket — is
   `:websocket/socket`, declared in `websocket.messages`. The connection
   machine spawns it at the `:active` parent level so the actor's lifetime
   spans `:connecting` -> `:authenticating` -> `:connected` without
   re-spawning."
  (:require [re-frame.core :as rf]
            ;; `re-frame.machines` ships in day8/re-frame2-machines.
            ;; Loading the ns publishes the late-bind hooks for
            ;; `rf/make-machine-handler`, the `:rf.machine/spawn` /
            ;; `:rf.machine/destroy` fx, and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` framework subs.
            [re-frame.late-bind]
            [re-frame.machines]
            [re-frame.fx]
            [websocket.schema :as schema]))

;; ============================================================================
;; CONNECTION MACHINE — :ws/connection
;; ============================================================================

(def connection-machine
  "The `:ws/connection` machine spec. Held in a `def` so the schema below
   (`schema/ConnectionData`) can reference it on `reg-machine`."
    {:initial :disconnected

     ;; `[:schemas :data]` validates the machine's `:data` slot on every
     ;; transition. App-schemas validate app-db; a machine's `:data` is
     ;; validated here instead.
     ;; See docs/machines/concepts.md#validating-a-machines-data.
     :schemas {:data schema/ConnectionData}

     ;; `:data` carries everything that must survive across reconnects.
     :data    {:url            nil
               :auth-token     nil
               :retries        0
               :max-retries    8
               :base-ms        100
               :max-backoff-ms 5000
               :socket-id      nil
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
      ;; The connection-epoch check. The incoming event carries
      ;; `:source-socket-id` (the spawned actor's `:rf/self-id`); we drop
      ;; the event unless that id matches the live socket.
      (fn guard-current-socket? [{data :data [_ {:keys [source-socket-id]}] :event}]
        (and (some? (:socket-id data))
             (= source-socket-id (:socket-id data))))}

     :actions
     {:record-connection-opts
      (fn action-record-connection-opts [{data :data [_ {:keys [url auth-token]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :error nil))})

      :record-and-reset
      ;; Compound action — record fresh opts AND zero the retry counter.
      ;; Used on manual `:ws/connect` from `:reconnecting` / `:failed`.
      (fn action-record-and-reset [{data :data [_ {:keys [url auth-token]}] :event}]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :retries 0)
                   (assoc :error nil))})

      :refresh-token
      (fn action-refresh-token [{data :data [_ token] :event}]
        {:data (assoc data :auth-token token)})

      :bump-retry
      (fn action-bump-retry [{data :data}]
        {:data (update data :retries inc)})

      :reset-retries
      (fn action-reset-retries [{data :data}]
        {:data (assoc data :retries 0)})

      :record-error
      (fn action-record-error [{data :data [_ {:keys [error]}] :event}]
        {:data (assoc data :error error)})

      :record-socket-id
      ;; Writes the spawned actor's id into `:data`. The `:active` spawn's
      ;; `:on-spawn` callback fires `[:ws/connection [:ws/-socket-spawned
      ;; <actor-id>]]` back into this machine; that transition runs this
      ;; action. (`:on-spawn` is advisory and cannot mutate `:data`, so a
      ;; self-event is how the id gets recorded.)
      (fn action-record-socket-id [{data :data [_ id] :event}]
        {:data (assoc data :socket-id id)})

      :send-auth
      ;; Entry action for `:authenticating` — route an `:auth` message
      ;; into the live socket actor.
      (fn action-send-auth [{data :data}]
        {:fx [[:dispatch [(:socket-id data)
                          [:send {:type  :auth
                                  :token (:auth-token data)}]]]]})

      :flush-queue-and-resubscribe
      ;; Entry action for `:connected`. Re-issues subscribe messages for
      ;; every tracked topic (subscriptions survive reconnects) and resets
      ;; the retry counter. The buffered-message flush is the separate
      ;; `:always` transition below, which reads the `:queue` this action
      ;; leaves in place.
      (fn action-on-connected [{data :data}]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(:socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      ;; The `:always` cascade on `:connected`: when entry leaves the
      ;; queue non-empty, walk it onto the wire and clear it.
      (fn action-flush-queue [{data :data}]
        (let [q (:queue data)]
          {:data (assoc data :queue [])
           :fx   (mapv (fn [msg]
                         [:dispatch [(:socket-id data) [:send msg]]])
                       q)}))

      :enqueue-message
      (fn action-enqueue-message [{data :data [_ msg] :event}]
        {:data (update data :queue conj msg)})

      :send-now
      ;; `:connected`-leaf override of the parent's `:ws/send`: the
      ;; message goes straight to the wire instead of queueing.
      (fn action-send-now [{data :data [_ msg] :event}]
        {:fx [[:dispatch [(:socket-id data) [:send msg]]]]})

      :register-subscription
      (fn action-register-subscription [{data :data [_ topic] :event}]
        {:data (update data :subscriptions conj topic)
         :fx   [[:dispatch [(:socket-id data)
                            [:send {:type :subscribe :topic topic}]]]]})

      :register-request
      ;; Caller: `[:ws/connection [:ws/request {:request-id ... :body ...
      ;;                                       :reply ... :timeout-ms ...}]]`
      ;; Record the in-flight entry, forward to the socket, schedule
      ;; a timeout.
      (fn action-register-request [{data :data [_ {:keys [request-id body reply timeout-ms]
                                            :or   {timeout-ms 30000}}] :event}]
        {:data (assoc-in data [:in-flight request-id]
                         {:reply-event reply :timeout-ms timeout-ms})
         :fx   [[:dispatch [(:socket-id data)
                            [:send (assoc body :request-id request-id)]]]
                ;; The timeout event carries the live socket-id; the
                ;; `:current-socket?` guard drops stale timeouts from
                ;; a prior connection epoch.
                [:dispatch-later
                 {:ms    timeout-ms
                  :event [:ws/connection
                          [:ws/request-timeout
                           {:request-id       request-id
                            :source-socket-id (:socket-id data)}]]}]]})

      :clear-request
      (fn action-clear-request [{data :data [_ {:keys [request-id]}] :event}]
        {:data (update data :in-flight dissoc request-id)})

      :receive-message
      ;; `:ws/received` arrived; the `:current-socket?` guard already
      ;; cleared us. Branch on `:request-id` in the body: correlated
      ;; reply → dispatch the registered reply event + clear the slot;
      ;; server push → dispatch `[:ws/handle-message body]`.
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
      {;; The socket actor is spawned at the parent level so its lifetime
       ;; spans :connecting -> :authenticating -> :connected. Any
       ;; transition that leaves :active destroys it.
       ;; See docs/machines/concepts.md#when-the-machine-grows.
       :spawn {:machine-id :websocket/socket
                ;; The child reads URL + auth-token from the parent's
                ;; `:data` at spawn time. Every re-entry to :active picks
                ;; up whatever is current.
                :data       (fn [{snap :snapshot}]
                              {:url        (-> snap :data :url)
                               :auth-token (-> snap :data :auth-token)})
                ;; `:on-spawn` is advisory: its return is dropped, so the
                ;; callback cannot mutate the parent's `:data`. To capture
                ;; the freshly-allocated actor id, the callback fires a
                ;; `:ws/-socket-spawned` self-event; the matching
                ;; transition runs `:record-socket-id`, which writes
                ;; `:socket-id` into `:data` the normal way.
                :on-spawn (fn [{id :id}]
                            (when-let [dispatch! (re-frame.late-bind/get-fn :router/dispatch!)]
                              (dispatch!
                                [:ws/connection [:ws/-socket-spawned id]]
                                ;; `:source :websocket` is the reserved
                                ;; functional-origin slot for
                                ;; websocket-arrived dispatches.
                                {:source :websocket})))}

       ;; Exit: clear the stale :socket-id from :data. The runtime
       ;; destroys the actor automatically on exit; this keeps :data tidy
       ;; so :current-socket? compares against nil correctly.
       :exit (fn action-clear-socket-id [{data :data}]
               {:data (assoc data :socket-id nil)})

       ;; Parent-level transitions inherited by every leaf (deepest state
       ;; wins, falling through to the parent).
       ;; See docs/machines/glossary.md#transition.
       :on    {:ws/closed   {:target :reconnecting
                             :action :bump-retry}
               :ws/fatal    {:target :failed
                             :action :record-error}
               :ws/send     {:action :enqueue-message}
               :ws/request  {:action :enqueue-message}
               :ws/refresh-token {:action :refresh-token}
               :ws/disconnect {:target :disconnected}
               ;; Subscribe-while-connecting just records the intent;
               ;; the next :connected entry will re-issue.
               :ws/subscribe {:action (fn [{data :data [_ topic] :event}]
                                        {:data (update data :subscriptions conj topic)})}
               ;; Internal: the spawn's `:on-spawn` callback fires this so
               ;; the machine can record the spawned socket id via a
               ;; regular action (see `:record-socket-id`).
               :ws/-socket-spawned {:action :record-socket-id}}

       :initial :connecting

       :states
       {:connecting
        {:tags #{:websocket/active :websocket/connecting}
         :on   {:ws/opened {:target :authenticating}}}

        :authenticating
        {:tags  #{:websocket/active :websocket/authenticating}
         :entry :send-auth
         :on    {:ws/auth-ok     {:target :connected}
                 ;; :failed is a top-level state, not a sibling under
                 ;; :active. A bare keyword would resolve to [:active
                 ;; :failed], which does not exist, so the absolute vector
                 ;; target [:failed] is required.
                 :ws/auth-failed {:target [:failed]
                                  :action :record-error}}}

        :connected
        {:tags   #{:websocket/active :websocket/connected}
         :entry  :flush-queue-and-resubscribe
         :always [{:guard :has-queued-messages? :action :flush-queue}]
         :on     {:ws/received {:guard  :current-socket?
                                :action :receive-message}
                  ;; Override the parent's :ws/send — while :connected
                  ;; the message goes straight to the wire.
                  :ws/send     {:action :send-now}
                  :ws/request  {:action :register-request}
                  :ws/subscribe {:action :register-subscription}
                  :ws/request-timeout {:guard  :current-socket?
                                       :action :clear-request}}}}}

      :reconnecting
      {:tags   #{:websocket/reconnecting}
       :always [{:guard :max-retries-exceeded? :target :failed}]
       ;; Exponential backoff. The delay fn is called once at entry and
       ;; reads the current retry count. Leaving the state cancels the
       ;; timer, so a backoff from a prior visit can't fire late.
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

;; `rf/reg-machine` tags the registration `:rf/machine? true`, which is
;; what declarative `:spawn` looks up to resolve a spawn target. Without
;; it the spawn-fx silently no-ops.
(rf/reg-machine :ws/connection connection-machine)

;; --- subs -------------------------------------------------------------
;; A machine snapshot lives in runtime-db; read it through the framework
;; `:rf/machine` sub. See docs/machines/glossary.md#snapshot.
(rf/reg-sub :ws/snapshot
  :<- [:rf/machine :ws/connection]
  (fn [snapshot _] snapshot))

(rf/reg-sub :ws/state
  :<- [:ws/snapshot]
  (fn [snap _] (:state snap)))

;; The per-tag predicate subs: each is a `contains?` over the snapshot's
;; `:tags` union, so a view asks "is it connected?" rather than matching
;; the hierarchical `:state` vector. See docs/machines/glossary.md#state-tag.
(rf/reg-sub :ws/connecting?
  :<- [:ws/snapshot]
  (fn [snap _] (contains? (:tags snap) :websocket/connecting)))

(rf/reg-sub :ws/authenticating?
  :<- [:ws/snapshot]
  (fn [snap _] (contains? (:tags snap) :websocket/authenticating)))

(rf/reg-sub :ws/connected?
  :<- [:ws/snapshot]
  (fn [snap _] (contains? (:tags snap) :websocket/connected)))

(rf/reg-sub :ws/reconnecting?
  :<- [:ws/snapshot]
  (fn [snap _] (contains? (:tags snap) :websocket/reconnecting)))

(rf/reg-sub :ws/failed?
  :<- [:ws/snapshot]
  (fn [snap _] (contains? (:tags snap) :websocket/failed)))

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
  {:doc "Seed the connection machine into its `:disconnected` initial
         state. A machine's initial snapshot materialises on its first
         dispatch."}
  (fn handler-ws-connection-initialise [_ _]
    ;; A no-op dispatch through the machine forces the initial snapshot to
    ;; materialise, so tests can read the `:disconnected` state without
    ;; first calling `:ws/connect`.
    {:fx [[:dispatch [:ws/connection [:ws/noop]]]]}))
