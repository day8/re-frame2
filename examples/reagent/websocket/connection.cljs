(ns websocket.connection
  "The `:ws/connection` connection machine — the canonical Pattern-WebSocket
   worked example.

   This is a faithful realisation of `spec/Pattern-WebSocket.md` §Worked
   example. The hierarchy is:

     :disconnected
     :active                              ;; compound parent; owns the socket
       :connecting
       :authenticating
       :connected
     :reconnecting                        ;; :after backoff
     :failed                              ;; terminal until manual :ws/connect

   Why parallel-regions when this looks linear? Pattern-WebSocket §The
   connection state machine lists three orthogonal axes — the connection
   lifecycle, the subscription/request bookkeeping, and the live-socket
   identity. The subscription set + the in-flight map + the queue all
   share *one* domain (this connection), so the **shared-:data + compound
   `:active`** shape is the right one here. (For a small example with
   independent regions, see `examples/reagent/nine_states/` — Pattern-
   NineStates' canonical worked example.)

   `:fsm/tags` carry the queryable connection-state predicates — the view
   asks `:websocket/connected?` and `:websocket/reconnecting?` via
   `rf/machine-has-tag?` without needing to know which leaf carries the
   `:connected` intent.

   Pattern-StaleDetection composes in twice:

     1. `:after` backoff — the runtime's built-in `:after`-epoch
        invariant drops stale timers from prior `:reconnecting` visits.

     2. Connection epoch — the live `:socket-id` IS the epoch; the
        `:current-socket?` guard rejects `:ws/received` from a
        socket that has since been replaced (a slow `:message` event
        landing post-reconnect).

   The socket actor itself — the thing that owns the JS WebSocket — is
   `:websocket/socket`, declared in `websocket.messages`. The connection
   machine invokes it at the `:active` parent level so the actor's
   lifetime spans `:connecting` → `:authenticating` → `:connected`
   without re-spawning."
  (:require [re-frame.core :as rf]
            ;; `re-frame.machines` ships in day8/re-frame2-machines.
            ;; Loading the ns publishes the late-bind hooks for
            ;; `rf/create-machine-handler`, the `:rf.machine/spawn` /
            ;; `:rf.machine/destroy` fx, and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` framework subs.
            [re-frame.machines :as machines]
            [re-frame.fx]
            [websocket.schema]))

;; ============================================================================
;; CONNECTION MACHINE — :ws/connection
;; ============================================================================
;;
;; Read this side-by-side with spec/Pattern-WebSocket.md §Worked example
;; — connection machine. The structural choices are documented there;
;; this file is a runnable, app-shaped version that the views can
;; drive.

(def connection-machine
  "Spec for the `:ws/connection` machine. Held in a `def` so the handler
   can be re-built via `create-machine-handler` from inside the
   `register-all!` re-registration helper."
    {:initial :disconnected

     ;; Pattern-WebSocket §Worked example — the connection machine's
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
      (fn guard-max-retries-exceeded? [data _event]
        (>= (:retries data) (:max-retries data)))

      :has-queued-messages?
      (fn guard-has-queued-messages? [data _event]
        (seq (:queue data)))

      :current-socket?
      ;; Pattern-WebSocket §The connection state machine — the
      ;; **connection-epoch** check. The incoming event carries
      ;; `:source-socket-id` (the spawned actor's `:rf/self-id`);
      ;; we drop the event unless that id matches the live socket.
      (fn guard-current-socket? [data [_ {:keys [source-socket-id]}]]
        (and (some? (:socket-id data))
             (= source-socket-id (:socket-id data))))}

     :actions
     {:record-connection-opts
      (fn action-record-connection-opts [data [_ {:keys [url auth-token]}]]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :error nil))})

      :record-and-reset
      ;; Compound action — record fresh opts AND zero the retry counter.
      ;; Used on manual `:ws/connect` from `:reconnecting` / `:failed`.
      (fn action-record-and-reset [data [_ {:keys [url auth-token]}]]
        {:data (-> data
                   (assoc :url url)
                   (assoc :auth-token auth-token)
                   (assoc :retries 0)
                   (assoc :error nil))})

      :refresh-token
      (fn action-refresh-token [data [_ token]]
        {:data (assoc data :auth-token token)})

      :bump-retry
      (fn action-bump-retry [data _]
        {:data (update data :retries inc)})

      :reset-retries
      (fn action-reset-retries [data _]
        {:data (assoc data :retries 0)})

      :record-error
      (fn action-record-error [data [_ {:keys [error]}]]
        {:data (assoc data :error error)})

      :send-auth
      ;; Entry action for `:authenticating` — route an `:auth` message
      ;; into the live socket actor.
      (fn action-send-auth [data _]
        {:fx [[:dispatch [(:socket-id data)
                          [:send {:type  :auth
                                  :token (:auth-token data)}]]]]})

      :flush-queue-and-resubscribe
      ;; Entry action for `:connected`. Two jobs:
      ;;   (1) re-issue subscribe messages for every tracked topic
      ;;       (subscriptions survive reconnects);
      ;;   (2) flush every message buffered while disconnected.
      ;; The state machine's `:on-connected` from the spec is split
      ;; this way for readability — the `:always` guarded transition
      ;; reads the post-action `:queue`, so this action only handles
      ;; the resubscribe + retry-reset side; the queue-flush side
      ;; rides on the `:always` cascade below.
      (fn action-on-connected [data _]
        {:data (assoc data :retries 0)
         :fx   (mapv (fn [topic]
                       [:dispatch [(:socket-id data)
                                   [:send {:type :subscribe :topic topic}]]])
                     (:subscriptions data))})

      :flush-queue
      ;; Per the spec's `:always` cascade on `:connected`: when the
      ;; entry leaves the queue non-empty, walk it onto the wire and
      ;; clear it.
      (fn action-flush-queue [data _]
        (let [q (:queue data)]
          {:data (assoc data :queue [])
           :fx   (mapv (fn [msg]
                         [:dispatch [(:socket-id data) [:send msg]]])
                       q)}))

      :enqueue-message
      (fn action-enqueue-message [data [_ msg]]
        {:data (update data :queue conj msg)})

      :send-now
      ;; `:connected`-leaf override of the parent's `:ws/send`: the
      ;; message goes straight to the wire instead of queueing.
      (fn action-send-now [data [_ msg]]
        {:fx [[:dispatch [(:socket-id data) [:send msg]]]]})

      :register-subscription
      (fn action-register-subscription [data [_ topic]]
        {:data (update data :subscriptions conj topic)
         :fx   [[:dispatch [(:socket-id data)
                            [:send {:type :subscribe :topic topic}]]]]})

      :register-request
      ;; Caller: `[:ws/connection [:ws/request {:request-id ... :body ...
      ;;                                       :reply ... :timeout-ms ...}]]`
      ;; Record the in-flight entry, forward to the socket, schedule
      ;; a timeout.
      (fn action-register-request [data [_ {:keys [request-id body reply timeout-ms]
                                            :or   {timeout-ms 30000}}]]
        {:data (assoc-in data [:in-flight request-id]
                         {:reply-event reply :timeout-ms timeout-ms})
         :fx   [[:dispatch [(:socket-id data)
                            [:send (assoc body :request-id request-id)]]]
                ;; The timeout event carries the live socket-id; the
                ;; `:current-socket?` guard drops stale timeouts from
                ;; a prior connection epoch.
                [:dispatch-later
                 {:ms       timeout-ms
                  :dispatch [:ws/connection
                             [:ws/request-timeout
                              {:request-id       request-id
                               :source-socket-id (:socket-id data)}]]}]]})

      :clear-request
      (fn action-clear-request [data [_ {:keys [request-id]}]]
        {:data (update data :in-flight dissoc request-id)})

      :receive-message
      ;; `:ws/received` arrived; the `:current-socket?` guard already
      ;; cleared us. Branch on `:request-id` in the body: correlated
      ;; reply → dispatch the registered reply event + clear the slot;
      ;; server push → dispatch `[:ws/handle-message body]`.
      (fn action-receive-message [data [_ {:keys [body]}]]
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
      {;; The socket actor is invoked at the parent level — its
       ;; lifetime spans :connecting → :authenticating → :connected.
       ;; Any transition that leaves :active destroys it.
       :invoke {:machine-id :websocket/socket
                ;; Mechanism 2 from Pattern-AsyncEffect §Parameter
                ;; passing — the child reads URL + auth-token from
                ;; the parent's :data at spawn time. Every re-entry
                ;; to :active picks up whatever is current.
                :data       (fn [snap _]
                              {:url        (-> snap :data :url)
                               :auth-token (-> snap :data :auth-token)})
                ;; Record the spawned actor id so subsequent dispatches
                ;; and :current-socket? checks have a value to compare.
                :on-spawn   (fn [data id]
                              (assoc data :socket-id id))}

       ;; Exit cascade — clear the stale :socket-id from :data. The
       ;; runtime destroys the actor automatically (declarative :invoke's
       ;; desugar emits :rf.machine/destroy on exit); this keeps :data
       ;; tidy so :current-socket? compares against nil correctly.
       :exit (fn action-clear-socket-id [data _]
               {:data (assoc data :socket-id nil)})

       ;; Parent-level transitions inherited by every leaf, per Spec 005
       ;; §Transition resolution — deepest-wins with parent fallthrough.
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
               :ws/subscribe {:action (fn [data [_ topic]]
                                        {:data (update data :subscriptions conj topic)})}}

       :initial :connecting

       :states
       {:connecting
        {:tags #{:websocket/active :websocket/connecting}
         :on   {:ws/opened {:target :authenticating}}}

        :authenticating
        {:tags  #{:websocket/active :websocket/authenticating}
         :entry :send-auth
         :on    {:ws/auth-ok     {:target :connected}
                 :ws/auth-failed {:target :failed
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
       ;; Exponential backoff, computed at state entry from the current
       ;; retry count. Spec 005 §Value shape — fn-form delay called
       ;; once at entry. The :after epoch invariant guarantees a
       ;; transition out makes the in-flight backoff stale.
       :after  {(fn delay-backoff-ms [snap]
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
;; SUBSCRIPTIONS + INIT EVENT + MACHINE HANDLER
;; ============================================================================
;;
;; All the `reg-*` calls are inside `register-all!` so the test fixture
;; can re-fire them after an upstream `clear-all!` wiped the registrar
;; (see `websocket.core/register-all!` for context). At ns-load the
;; function is called once via the trailing `(register-all!)` form.

(defn register-all!
  "Idempotent re-registration of every event handler / sub this ns
   owns. See `websocket.core/register-all!`."
  []
  ;; Use `rf/reg-machine` (not `reg-event-fx` + `create-machine-handler`)
  ;; so the registration metadata carries `:rf/machine? true` —
  ;; declarative `:invoke` resolves the spawn target via this
  ;; metadata, and without it the spawn-fx silently no-ops (the
  ;; spawned actor's handler never registers, and its snapshot is
  ;; never written).
  (rf/reg-machine :ws/connection connection-machine)

  ;; --- subs ---------------------------------------------------------
  (rf/reg-sub :ws/snapshot
    (fn [db _] (get-in db [:rf/machines :ws/connection])))

  (rf/reg-sub :ws/state
    :<- [:ws/snapshot]
    (fn [snap _] (:state snap)))

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

  ;; --- init event ---------------------------------------------------
  (rf/reg-event-fx :ws.connection/initialise
    {:doc "Seed the connection machine into its `:disconnected` initial
           state — the lazy initial materialises on the first dispatch."}
    (fn handler-ws-connection-initialise [_ _]
      ;; A no-op dispatch through the machine forces snapshot
      ;; materialisation so the headless tests can read the initial
      ;; :disconnected state without first calling `:ws/connect`.
      {:fx [[:dispatch [:ws/connection [:ws/noop]]]]})))

(register-all!)
