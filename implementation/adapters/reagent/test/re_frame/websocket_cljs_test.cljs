(ns re-frame.websocket-cljs-test
  "Drives the websocket example (examples/patterns/websocket/) through the
   spec/Pattern-WebSocket.md lifecycle. The example tree is test-free, so its
   behavioural coverage lives here. The in-process mock server runs in sync
   delivery, so a whole round trip completes inside one dispatch-sync."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.fx :as rf.fx]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [websocket.schema :as ws.schema]
            [websocket.connection :as ws.connection]
            [websocket.messages :as messages]
            [websocket.core :as ws.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(defn- register-all!
  "Re-installs what the suite depends on, because an earlier namespace in the
   shared node-test run may call `re-frame.registrar/clear-all!` without
   restoring. It calls the example's OWN `register!` fns: a test-local copy of
   any example registration would win the last write, and the suite would
   then certify the copy. The only thing installed here directly is the
   framework's `:rf.machine/*` fx, which no example namespace owns.
   `websocket.schema/register!` scopes its app-schema to `:rf/default`
   itself, which matters because `:init-fn` runs outside the fixture's
   ambient frame binding."
  []
  (doseq [[fx-id hook] {:rf.machine/spawn          :machines/spawn-fx
                        :rf.machine/destroy        :machines/destroy-machine-fx
                        :rf.machine/spawn-all-init :machines/spawn-all-init-fx
                        :rf.machine/after-schedule :machines/after-schedule-fx
                        :rf.machine/after-cancel   :machines/after-cancel-fx}]
    (when-let [fx (rf.late-bind/get-fn hook)]
      (rf.fx/reg-fx fx-id fx)))
  (ws.schema/register!)
  (ws.connection/register!)
  (messages/register!)
  (ws.core/register!))

(defn- reg-reply-log!
  "A counting reply target. It logs each outcome, then forwards it to the
   example's boundary-validated `:ws.app/request-reply`, so 'settled exactly
   once' is a direct assertion and every logged outcome also passes the
   closed RequestOutcome contract. The example's own target keeps only the
   last outcome, where a duplicate is invisible."
  []
  (rf/reg-event :ws.test/log-reply
    (fn handler-ws-test-log-reply [{:keys [db]} [_ body]]
      {:db (update-in db [:messages :reply-log] (fnil conj []) body)
       :fx [[:dispatch [:ws.app/request-reply body]]]})))

;; The mock server's socket table resets too: every test's actor is
;; `:websocket/socket#1`, so leftover entries would multiply each push.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (register-all!)
                (reg-reply-log!)
                (messages/reset-mock-server!))}))

;; ============================================================================
;; HELPERS
;; ============================================================================

(defn- new-frame []
  ;; The tests synthesise each request timeout, so the real `:dispatch-later`
  ;; is suppressed.
  (rf.frame/make-anon-frame-record! {:initial-events [[:ws.app/initialise]]
                                     :fx-overrides   {:dispatch-later nil}}))

(defn- run-with-frame
  "Runs `(test-fn f)` on a fresh example frame with the mock server in sync
   delivery."
  [test-fn]
  (messages/set-mock-sync! true)
  (try
    (with-new-frame [f (new-frame)]
      (test-fn f))
    (finally
      (messages/set-mock-sync! false))))

(defn- snap
  "The connection machine's snapshot, which lives in runtime-db."
  [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :ws/connection]))

(defn- socket-id
  "The live socket actor's id, from the `:rf/spawned` slot the runtime keeps
   for the `[:active]` spawn and clears on teardown."
  [f]
  (get-in (snap f) [:data :rf/spawned [:active]]))

(defn- in-flight [f]
  (get-in (snap f) [:data :in-flight]))

(defn- token
  "The registration token `:register-request` minted for `rid`."
  [f rid]
  (get-in (in-flight f) [rid :token]))

(defn- last-reply [f]
  (get-in (rf/app-db-value f) [:messages :last-reply]))

(defn- reply-log [f]
  (get-in (rf/app-db-value f) [:messages :reply-log]))

(defn- conn!
  "Dispatches `event` into the connection machine."
  [f event]
  (rf/dispatch-sync [:ws/connection event] {:frame f}))

(defn- connect! [f]
  (conn! f [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]))

(defn- drop!
  "A transport drop: the mock closes the socket and its actor forwards a live
   `:ws/closed`."
  [f]
  (messages/simulate-disconnect! (rf/capture-frame f)))

(defn- receive!
  "A frame arriving from socket `sid`."
  [f sid body]
  (conn! f [:ws/received {:source-socket-id sid :body body}]))

(defn- timeout!
  "The `:ws/request-timeout` `:register-request` schedules, stamped with the
   socket id and registration token it was armed with."
  [f sid rid tok]
  (conn! f [:ws/request-timeout {:request-id rid :token tok :source-socket-id sid}]))

(defn- silent-request!
  "A request whose wire `:type` the mock server never answers, so it stays in
   flight until something settles it."
  [f rid tag]
  (conn! f [:ws/request {:request-id rid
                         :body       {:type :silent-no-echo :tag tag}
                         :reply      [:ws.test/log-reply]
                         :timeout-ms 5000}]))

(defn- app-request!
  "The example's `:ws.app/request`, supplied its recordable request id."
  [f rid body]
  (rf/dispatch-sync [:ws.app/request body] {:frame f :rf.cofx {:ws.app/request-id rid}}))

(defn- local-outcome [rid error]
  {:origin :ws/local :request-id rid :ok false :error error})

(defn- wire-reply
  "A server reply as the wire contract requires it: the id it answers and the
   registration token it echoes."
  [rid tok tag]
  {:type :reply :request-id rid :request-token tok :ok true :echo {:type :request :tag tag}})

(defn- echoed-outcome
  "The outcome the mock server's echo of the latest registration delivers."
  [f rid body]
  {:type          :reply
   :request-id    rid
   :request-token (dec (get-in (snap f) [:data :next-token]))
   :ok            true
   :echo          {:type :request :body body}
   :origin        :ws/server})

(defn- fire-after-timer!
  "Delivers `:reconnecting`'s elapsed `:after` timer. A fn-form `:after` entry
   is keyed by the fn itself, so the event carries that key (a resolved-ms
   number matches nothing) and the node's current epoch."
  [f]
  (conn! f [:rf.machine.timer/after-elapsed
            (first (keys (get-in ws.connection/connection-machine [:states :reconnecting :after])))
            (get-in (snap f) [:data :rf/after-epoch [:reconnecting]])
            [:reconnecting]]))

(defn- drive-to-failed!
  "Connects, spends the retry budget, then drops: `:reconnecting`'s
   `:max-retries-exceeded?` `:always` steps straight on to `:failed`."
  [f]
  (connect! f)
  (rf.frame/swap-runtime-db! f
    #(update-in % [:rf.runtime/machines :snapshots :ws/connection :data]
                (fn [data] (assoc data :retries (inc (:max-retries data))))))
  (drop! f))

;; ============================================================================
;; CONNECTION LIFECYCLE
;; ============================================================================

(deftest websocket-connect-happy-path
  ;; The machine records the URL and the OPAQUE credential reference, never a
  ;; bearer; the view asks tags, not state names.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [s (snap f)]
        (is (= {:state    [:active :connected]
                :tags     #{:websocket/active :websocket/connected}
                :url      "ws://mock"
                :cred-ref :ws.demo/cred-a}
               {:state    (:state s)
                :tags     (:tags s)
                :url      (get-in s [:data :url])
                :cred-ref (get-in s [:data :cred-ref])}))))))

(deftest websocket-manual-connect-after-active-disconnect-gets-full-budget
  ;; A clean :ws/disconnect taken mid-reconnect, from [:active :connecting],
  ;; leaves :retries where the failed opens left it, so the :ws/connect out of
  ;; :disconnected must zero it. Sync delivery races through :connecting, so
  ;; this walks the registered machine through the pure transition.
  (let [m       ws.connection/connection-machine
        step    #(:snapshot (rf.machines/machine-transition m %1 %2))
        ;; The pure transition only describes the spawn; the runtime binds the id.
        spawned #(assoc-in %1 [:data :rf/spawned [:active]] %2)
        lose    #(step %1 [:ws/closed {:source-socket-id %2 :code 1006}])
        backoff #(step % [:rf.machine.timer/after-elapsed
                          (first (keys (get-in m [:states :reconnecting :after])))
                          (get-in % [:data :rf/after-epoch [:reconnecting]])
                          [:reconnecting]])
        connect [:ws/connect {:url "ws://mock" :cred-ref :ws.demo/cred-a}]
        budget  (juxt :state #(get-in % [:data :retries]))
        spent   (-> (step {:state (:initial m) :data (:data m)} connect)
                    (spawned "sock-1") (lose "sock-1") backoff
                    (spawned "sock-2") (lose "sock-2") backoff
                    (spawned "sock-3"))]
    (is (= [[:active :connecting] 2] (budget spent)))
    (is (= [[:active :connecting] 0]
           (budget (-> spent (step [:ws/disconnect]) (step connect)))))))

(deftest websocket-offline-queue
  ;; Off-connection, :ws/send and :ws/request buffer the WHOLE event in every
  ;; such state (:failed is top-level, so it carries its own enqueue
  ;; transitions). The next :connected entry replays them through the machine,
  ;; so a queued request registers, goes out as its body and correlates its
  ;; reply.
  (doseq [[state reach! reconnect!]
          [[[:disconnected] identity                     connect!]
           [[:reconnecting] #(do (connect! %) (drop! %)) fire-after-timer!]
           [[:failed]       drive-to-failed!             connect!]]]
    (run-with-frame
      (fn [f]
        (reach! f)
        (let [rid (random-uuid)]
          (conn! f [:ws/send {:type :note :body "note"}])
          (app-request! f rid "hello")
          (is (= {:state state
                  :queue [[:ws/send {:type :note :body "note"}]
                          [:ws/request {:request-id rid
                                        :body       {:type :request :body "hello"}
                                        :reply      [:ws.app/request-reply]
                                        :timeout-ms 5000}]]}
                 {:state (:state (snap f))
                  :queue (get-in (snap f) [:data :queue])}))
          (reconnect! f)
          (is (= {:state [:active :connected] :queue [] :in-flight {}}
                 {:state     (:state (snap f))
                  :queue     (get-in (snap f) [:data :queue])
                  :in-flight (in-flight f)}))
          (is (= (echoed-outcome f rid "hello") (last-reply f))))))))

(deftest websocket-every-exit-from-active-fails-in-flight-requests
  ;; Leaving :active by any door destroys the only socket that could reply, so
  ;; each in-flight request settles exactly once with the local
  ;; :ws/connection-lost body (fail, not replay: the server may already have
  ;; acted on it). Its scheduled timeout carries the dead socket's id, so it
  ;; changes nothing, before or after the next connection.
  (doseq [[leave! recover! left]
          [[drop!
            fire-after-timer!
            {:state [:reconnecting] :tags #{:websocket/reconnecting} :retries 1 :error nil}]
           [#(conn! % [:ws/disconnect])
            connect!
            {:state [:disconnected] :tags nil :retries 0 :error nil}]
           [#(conn! % [:ws/fatal {:error :ws/protocol-violation}])
            connect!
            {:state [:failed] :tags #{:websocket/failed} :retries 0 :error :ws/protocol-violation}]]]
    (run-with-frame
      (fn [f]
        (connect! f)
        (let [rid       (random-uuid)
              _         (silent-request! f rid "in-flight")
              old-id    (socket-id f)
              tok       (token f rid)
              lost      (local-outcome rid :ws/connection-lost)
              settled   (assoc left :socket nil :in-flight {} :log [lost] :last-reply lost)
              observe   #(let [s (snap f)]
                           {:state      (:state s)
                            :tags       (:tags s)
                            :retries    (get-in s [:data :retries])
                            :error      (get-in s [:data :error])
                            :socket     (socket-id f)
                            :in-flight  (in-flight f)
                            :log        (reply-log f)
                            :last-reply (last-reply f)})
              straggle! #(timeout! f old-id rid tok)]
          (leave! f)
          (is (= settled (observe)))
          (straggle!)
          (is (= settled (observe)) "the dead socket's timeout changes nothing")
          (recover! f)
          (let [recovered (observe)]
            (is (= [:active :connected] (:state recovered)))
            (is (not= old-id (:socket recovered)) "the next connection has a fresh socket")
            (straggle!)
            (is (= recovered (observe)) "nor does it once the next connection is up")))))))

(deftest websocket-stale-lifecycle-events-dropped
  ;; The live socket's id is the connection's clock: an event stamped with a
  ;; replaced socket's id moves nothing, however well-formed. A stale
  ;; :ws/closed is the dangerous straggler, since unguarded it would tear the
  ;; LIVE connection down.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [live-id  (socket-id f)
            stale-id (str "stale-" (random-uuid))
            observe  #(vector (:state (snap f))
                              (:data (snap f))
                              (get-in (rf/app-db-value f) [:messages :received]))
            before   (observe)]
        (receive! f stale-id {:type :push :note "stale"})
        (conn! f [:ws/closed {:source-socket-id stale-id :code 1006}])
        (is (= before (observe)))
        (conn! f [:ws/closed {:source-socket-id live-id :code 1006}])
        (is (= [:reconnecting] (:state (snap f))) "the live socket's close still passes")))))

(deftest websocket-stale-auth-events-guarded
  ;; Sync delivery races through :connecting and :authenticating inside one
  ;; dispatch, so the straggler guards on those leaves are pinned on the spec.
  (let [guard #(get-in ws.connection/connection-machine [:states :active :states %1 :on %2 :guard])]
    (is (= [:current-socket? :current-socket? :current-socket?]
           [(guard :connecting :ws/opened)
            (guard :authenticating :ws/auth-ok)
            (guard :authenticating :ws/auth-failed)]))))

;; ============================================================================
;; REQUEST / REPLY
;; ============================================================================
;;
;; The request-id is the APP's correlation value, and spec/Pattern-WebSocket.md
;; §Message correlation invites reusing one (a per-feature `[:feature/load
;; slug]`). The connection epoch cannot tell two registrations under one id
;; apart on the same live socket; the registration token does, on both
;; settling paths.

(deftest websocket-request-reply-correlation
  ;; Pins spec/Pattern-WebSocket.md's app-level boundary: the outcome is the
  ;; app's own reply body plus the machine's :origin stamp, carrying none of
  ;; the uniform reply envelope's keys (:rf/reply-to, :status, :work/id,
  ;; :work/kind, :completed-at). The id is the supplied recordable coeffect,
  ;; and the registration token makes the round trip through the server.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [rid (random-uuid)]
        (app-request! f rid "hello")
        (is (= {} (in-flight f)))
        (is (= (echoed-outcome f rid "hello") (last-reply f)))))))

(deftest websocket-stale-timeout-cannot-settle-later-same-id-request
  ;; A is answered, then B re-registers the same id on the same live socket
  ;; inside A's timeout window. A's uncancelled timer passes the epoch check,
  ;; so only the registration token keeps it from settling B early.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [sid (socket-id f)
            rid [:feature/load "alpha"]]
        (silent-request! f rid "A")
        (let [tok-a     (token f rid)
              a-outcome (assoc (wire-reply rid tok-a "A") :origin :ws/server)]
          (receive! f sid (wire-reply rid tok-a "A"))
          (is (= [a-outcome] (reply-log f)))
          (silent-request! f rid "B")
          (let [tok-b (token f rid)]
            (timeout! f sid rid tok-a)
            (is (= tok-b (token f rid)) "A's timer leaves B's slot")
            (is (= [a-outcome] (reply-log f)) "and fires no callback")
            (timeout! f sid rid tok-b)
            (is (= {} (in-flight f)) "B's own timer still settles B")
            (is (= [a-outcome (local-outcome rid :ws/timeout)] (reply-log f)))))))))

(deftest websocket-wire-reply-cannot-settle-a-later-same-id-registration
  ;; A is pending under R when B re-registers R: A is settled locally as
  ;; superseded, but A's request is already on the wire. The token rides out
  ;; as :request-token and must come back, so A's reply can neither settle B
  ;; nor, arriving after B's outcome, replace it. :ws.app/request-reply is the
  ;; only writer of :last-reply; every vetted frame still reaches the inbox.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [sid        (socket-id f)
            rid        [:feature/load "gamma"]
            superseded (local-outcome rid :ws/superseded)
            inbox-tags #(set (keep (comp :tag :echo)
                                   (get-in (rf/app-db-value f) [:messages :received])))]
        (silent-request! f rid "A")
        (let [tok-a (token f rid)]
          (silent-request! f rid "B")
          (let [tok-b     (token f rid)
                b-outcome (assoc (wire-reply rid tok-b "B") :origin :ws/server)]
            (is (= [superseded] (reply-log f)))
            (is (= superseded (last-reply f)))
            (receive! f sid (wire-reply rid tok-a "A"))
            (is (= tok-b (token f rid)) "A's reply leaves B's slot")
            (is (= [superseded] (reply-log f)) "and fires no callback")
            (receive! f sid (wire-reply rid tok-b "B"))
            (is (= {} (in-flight f)))
            (is (= [superseded b-outcome] (reply-log f)))
            (receive! f sid (wire-reply rid tok-a "A"))
            (is (= b-outcome (last-reply f)) "a late uncorrelated reply cannot replace the outcome")
            (is (= #{"A" "B"} (inbox-tags)))))))))

;; ============================================================================
;; SERVER PUSH + SUBSCRIPTIONS
;; ============================================================================

(deftest websocket-server-push
  ;; Pushes reach the inbox newest-first, each stamped with a monotonic
  ;; :rx-seq the view uses as a stable React key.
  (run-with-frame
    (fn [f]
      (connect! f)
      (doseq [note ["1" "2" "3"]]
        (messages/send-server-push! (rf/capture-frame f) {:type :push :note note}))
      (is (= [{:type :push :note "3" :rx-seq 2}
              {:type :push :note "2" :rx-seq 1}
              {:type :push :note "1" :rx-seq 0}]
             (get-in (rf/app-db-value f) [:messages :received]))))))

(deftest websocket-subscription-tracking
  ;; A :ws/subscribe is recorded in every state, never dropped, and sent by the
  ;; next :connected entry (at once when already :connected). The mock server
  ;; acks each subscribe that reaches the wire with one push, so an ack proves
  ;; the subscribe was SENT.
  (run-with-frame
    (fn [f]
      (let [subscribe! #(conn! f [:ws/subscribe %])
            acked      #(set (keep (fn [m] (when (= "subscribed" (:note m)) (:topic m)))
                                   (get-in (rf/app-db-value f) [:messages :received])))]
        (subscribe! :t/before-connect)
        (drive-to-failed! f)
        (subscribe! :t/while-failed)
        (connect! f)
        (drop! f)
        (subscribe! :t/mid-reconnect)
        (fire-after-timer! f)
        (rf/dispatch-sync [:ws.app/subscribe-demo] {:frame f})
        (is (= #{:t/before-connect :t/while-failed :t/mid-reconnect :demo-topic}
               (acked)))))))

;; ============================================================================
;; TRUST BOUNDARIES
;; ============================================================================

(deftest websocket-inbound-boundary-structural
  ;; The release-build half of the inbound contract. In this dev lane step-1
  ;; validation enforces :schema either way, so only :boundary? true shows the
  ;; check survives the production build. The :doc read proves the
  ;; registration under test is the example's, not a fixture twin.
  (let [meta-of #(rf/handler-meta {:source :store :kind :event :id %})]
    (doseq [id [:ws/handle-message :ws.app/request-reply]]
      (is (= [true true] ((juxt (comp some? :schema) :boundary?) (meta-of id))) (str id)))
    (is (str/includes? (str (:doc (meta-of :ws.app/request-reply))) "RequestOutcome"))))

(deftest websocket-inbound-boundary-rejection
  ;; Frames from the LIVE socket that fail the closed InboundMessage contract
  ;; are refused before the machine acts on them: a machine that correlated
  ;; first would keep app-db clean while silently spending a pending request's
  ;; slot. Each refused frame still reaches the ingress, which records it.
  (run-with-frame
    (fn [f]
      (connect! f)
      (let [sid           (socket-id f)
            rid           (random-uuid)
            traces        (atom [])
            inbox+outcome #(select-keys (:messages (rf/app-db-value f)) [:received :last-reply])]
        (silent-request! f rid "parked")
        (rf/register-listener! :trace ::boundary-traces #(swap! traces conj %))
        (try
          (let [before (inbox+outcome)]
            (doseq [body [{:type :evil/exec :cmd "drop tables"}          ;; no arm for this :type
                          {:type :push :note 42}                         ;; malformed push
                          {:type :reply :request-id rid}                 ;; names the live slot, lacks :ok
                          {:request-id rid :ok false :error :ws/timeout} ;; local-outcome-shaped, no :type
                          {:type :push :note "hi" :request-id rid}       ;; smuggles a :request-id past the closed push arm
                          {:type :reply :request-id rid :ok true}]]      ;; lacks :request-token, so names no registration
              (receive! f sid body))
            (is (= before (inbox+outcome))))
          (let [rejections (filter #(= :rf.error/schema-validation-failure (:operation %)) @traces)]
            (is (contains? (set (keep #(-> % :tags :event-id) rejections)) :ws/handle-message))
            (is (<= 6 (count rejections))))
          ;; The slot survived every frame, so its own deadline still answers
          ;; the caller, with the machine's locally minted body.
          (timeout! f sid rid (token f rid))
          (is (= (local-outcome rid :ws/timeout) (last-reply f)))
          (finally
            (rf/unregister-listener! :trace ::boundary-traces)))))))

(deftest websocket-request-reply-ingress-rejection
  ;; :ws.app/request-reply holds its own closed RequestOutcome contract, keyed
  ;; on the :origin the machine stamps after receipt. The machine never
  ;; forwards a bad frame here, so nothing else exercises this boundary.
  ;; Outcomes arrive inside a cascade, as the machine delivers them.
  (run-with-frame
    (fn [f]
      (let [rid    (random-uuid)
            traces (atom [])]
        (rf/register-listener! :trace ::outcome-traces #(swap! traces conj %))
        (try
          (doseq [outcome [{:type :reply :request-id rid :request-token 0 :ok true}                      ;; unstamped
                           {:origin :ws/local :type :reply :request-id rid :ok false :error :ws/timeout} ;; wire fields on the local arm
                           {:origin :ws/somewhere-else :request-id rid :ok false :error :ws/timeout}      ;; unknown :origin
                           {:origin :ws/server :type :reply :request-id rid :ok true}]]                   ;; server arm without :request-token
            (rf/dispatch-sync [:ws.test/log-reply outcome] {:frame f}))
          (is (nil? (last-reply f)))
          (is (contains? (set (keep #(-> % :tags :event-id)
                                    (filter #(= :rf.error/schema-validation-failure (:operation %))
                                            @traces)))
                         :ws.app/request-reply))
          (finally
            (rf/unregister-listener! :trace ::outcome-traces)))))))

(deftest websocket-credential-discipline
  ;; The machine carries only an opaque :cred-ref, and the socket resolves the
  ;; bearer at the auth write. The sweep reads serialisable surfaces, so a
  ;; bearer held by a host closure is ruled out structurally
  ;; (mock-socket-for-actor resolves inside its :auth branch), not here.
  (run-with-frame
    (fn [f]
      (let [traces     (atom [])
            events     (atom [])
            reconnect! (fn [cred-ref]
                         (conn! f [:ws/rotate-cred cred-ref])
                         (drop! f)
                         (fire-after-timer! f))]
        (rf/register-listener! :trace ::cred-traces #(swap! traces conj %))
        (rf.event-emit/register-event-listener! ::cred-events #(swap! events conj %))
        (try
          (connect! f)
          (reconnect! :ws.demo/cred-b)
          (is (= [:active :connected] (:state (snap f)))
              "the next socket authenticates with the rotated reference")
          (reconnect! :ws.demo/revoked)
          (is (= [:failed] (:state (snap f)))
              "an unresolvable reference fails authentication")
          (let [surface (pr-str {:snapshot (snap f)
                                 :app-db   (rf/app-db-value f)
                                 :traces   @traces
                                 :events   @events})]
            ;; The first check is the sweep's control: the opaque reference IS
            ;; on the surface.
            (is (= [true false]
                   (map #(str/includes? surface %) [":ws.demo/cred-b" "demo-bearer-secret"]))))
          (finally
            (rf/unregister-listener! :trace ::cred-traces)
            (rf.event-emit/unregister-event-listener! ::cred-events)))))))
