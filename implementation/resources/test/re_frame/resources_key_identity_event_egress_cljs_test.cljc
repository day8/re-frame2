(ns re-frame.resources-key-identity-event-egress-cljs-test
  "A resource's scope and params are its identity, and off box every copy takes
  one rule: the scope tokenizes, and the params ride unless the owner makes the
  coarse `:sensitive?` claim (Spec 016 §Resource registration spec). Event
  vectors carry that identity too: the `[:rf.resource/ensure …]` request's own
  payload, and a continuation reply's `:resource/key` and
  `:correlation :rf.reply/resource-key` riding its `:reply-to` target. Those
  copies reach off box on the trace's `:rf.event/v` and `:event`, on the epoch
  record's `:trigger-event` and `:effects` args once an opt-in hands them back,
  and on the always-on `:rf.observe/error` record. The reply's own `:params`,
  which the coarse claim tokenizes at the event chokepoint, is the control; the
  on-box trace keeps the raw identity, and a plain owner's params ride."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.epoch :as rf.epoch]
   [re-frame.fx :as rf.fx]
   [re-frame.observability :as rf.observability]
   ;; load-bearing side-effecting requires: register the :rf.resource/* events
   ;; this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; Unique sentinels, so a leak anywhere in a copy is unambiguous.
(def ^:private P "KEY-PARAMS-SENTINEL-q81")
(def ^:private S "KEY-SCOPE-SENTINEL-z42")

(def ^:private frame-id ::app)

(def ^:private sunk (atom []))
(def ^:private managed-args (atom nil))
(def ^:private replied (atom nil))

(defn- request [_params _ctx] {:request {:method :get :url "/x"}})

(defn- init! []
  (reset! sunk [])
  (reset! managed-args nil)
  (reset! replied nil)
  (rf.observability/clear-observability-sinks!)
  (rf/register-observability-sink! ::errors (fn [record] (swap! sunk conj record)))
  (rf/make-frame {:id frame-id :observability {:errors [{:sink ::errors}]}})
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  ;; The ensures pass an explicit `:scope`, so the resolver's slot stays unread.
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (rf/reg-resource :r/coarse {:scope         {:from-db :t/caller-scope}
                              :params-schema [:map [:slug :string]]
                              :sensitive?    true}
                   request)
  (rf/reg-resource :r/plain {:scope         {:from-db :t/caller-scope}
                             :params-schema [:map [:slug :string]]}
                   request)
  (rf/reg-resource :r/paths {:scope         {:from-db :t/caller-scope}
                             :params-schema [:map [:slug :string]]
                             :sensitive     [[:token]]}
                   request)
  (rf/reg-mutation :m/coarse {:params-schema [:map [:slug :string]] :sensitive? true} request)
  (rf/reg-mutation :m/plain {:params-schema [:map [:slug :string]]} request)
  (rf/reg-mutation :m/paths {:params-schema [:map [:slug :string]] :sensitive [[:token]]} request)
  (rf/reg-event :app/replied (fn [_ [_ reply]] (reset! replied reply) {}))
  (rf/reg-event :app/replied-and-failed (fn [_ _] (throw (ex-info "reply target failed" {})))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- redacted-token? [v] (and (map? v) (contains? v :rf/redacted)))

(defn- leak-paths
  "Every path in `x` whose string leaf carries `needle`."
  [x needle]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when #?(:clj  (.contains ^String v needle)
                                       :cljs (not= -1 (.indexOf v needle)))
                                (swap! found conj path))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- drive!
  "Ensure `owner` with a `:reply-to` of the failing target, under a scope
  carrying `S` and params carrying `P`, and settle the read. Returns the trace
  rows of both drains."
  [owner]
  (let [rows (atom [])]
    (rf/register-listener! :trace ::rows (fn [ev] (swap! rows conj ev)))
    (try
      (rf/dispatch-sync [:rf.resource/ensure {:resource owner :params {:slug P}
                                              :scope    [:rf.scope/session {:user S}]
                                              :owner    [:app :reader 1]
                                              :reply-to [:app/replied-and-failed]}]
                        {:frame frame-id})
      (rf/dispatch-sync (conj (:on-success @managed-args) {:status :ok :value {:v "ok"}})
                        {:frame frame-id})
      (finally (rf/unregister-listener! :trace ::rows)))
    @rows))

(defn- off-box-copies
  "Every off-box copy of the request and reply events, by carrier: the epoch
  records projected with each opt-in that hands an event-bearing slot back, and
  the always-on error record."
  []
  (let [records (rf.epoch/epoch-history frame-id)
        project (fn [opts] (mapv #(rf/project-egress % opts) records))
        events  (project {:rf.egress/include-event-args? true})]
    {:trace-event-args (vec (for [r events
                                  ev (:trace-events r)
                                  slot [:rf.event/v :event]
                                  :let [v (get-in ev [:tags slot])]
                                  :when (vector? v)]
                              v))
     :trigger-event    (mapv :trigger-event events)
     :effect-args      (vec (for [r (project {:rf.egress/include-fx-args? true})
                                  row (:effects r)]
                              (:args row)))
     :default-posture  (project {})
     :error-record     (mapv :event @sunk)}))

(defn- leaks-by-copy [copies needle]
  (into {} (keep (fn [[copy v]] (when-let [paths (seq (leak-paths v needle))]
                                  [copy (vec paths)])))
        copies))

(defn- trigger-of [copies event-id]
  (some #(when (= event-id (first %)) %) (:trigger-event copies)))

;; ===========================================================================
;; a coarse claim's params and the scope tokenize on every off-box event copy
;; ===========================================================================

(deftest coarse-claimed-key-params-tokenize-on-every-off-box-event-copy
  (let [rows   (drive! :r/coarse)
        copies (off-box-copies)
        reply  (trigger-of copies :app/replied-and-failed)
        raw    (some #(let [v (get-in % [:tags :rf.event/v])]
                        (when (= :app/replied-and-failed (first v)) v))
                     rows)]
    (testing "fixture: the on-box trace keeps the reply's raw identity"
      (is (= P (get-in raw [1 :resource/key 2 :slug])))
      (is (= P (get-in raw [1 :correlation :rf.reply/resource-key 2 :slug]))))
    (testing "control: the reply's own :params, tokenized by the coarse claim"
      (is (= :app/replied-and-failed (first reply)))
      (is (redacted-token? (get-in reply [1 :params]))))
    (testing "every off-box copy tokenizes the key params and the scope"
      (is (= {} (leaks-by-copy copies P)) "params")
      (is (= {} (leaks-by-copy copies S)) "scope"))
    (testing "the request and the reply keys keep their shape"
      (let [ensure (trigger-of copies :rf.resource/ensure)]
        (is (= :r/coarse (get-in ensure [1 :resource])))
        (is (redacted-token? (get-in ensure [1 :params])))
        (is (redacted-token? (get-in ensure [1 :scope 1]))))
      (is (= :r/coarse (get-in reply [1 :resource/key 1])))
      (is (redacted-token? (get-in reply [1 :resource/key 2])))
      (is (redacted-token? (get-in reply [1 :correlation :rf.reply/resource-key 2]))))))

(deftest a-plain-owners-key-params-ride-and-its-scope-tokenizes
  (drive! :r/plain)
  (let [copies (off-box-copies)
        reply  (trigger-of copies :app/replied-and-failed)]
    (is (= P (get-in reply [1 :resource/key 2 :slug]))
        "a plain owner's params ride inside the reply key")
    (is (= P (get-in (trigger-of copies :rf.resource/ensure) [1 :params :slug]))
        "and on the request")
    (is (= {} (leaks-by-copy copies S)) "the scope tokenizes on every copy")))

;; ===========================================================================
;; a transport reply's result takes its owner's coarse claim
;; ===========================================================================

;; Managed HTTP settles an attempt by dispatching
;; `[<internal-reply-id> <verification-payload> <result>]`, so the decoded
;; response rides that event's own copies. Its owner is the one the payload's
;; work id names.

(def ^:private V "TRANSPORT-VALUE-SENTINEL-m07")

(defn- settle-transport!
  "Start `owner`'s work for `slug`, settle it through the transport `callback` with
  `outcome`, and return the settle epoch's two copies of the transport reply
  event, event args opted in: the trace's `:rf.event/v` and the record's
  `:trigger-event`."
  [kind owner slug callback outcome]
  (rf/dispatch-sync (case kind
                      :resource [:rf.resource/ensure {:resource owner :params {:slug slug}
                                                      :scope    :rf.scope/global
                                                      :owner    [:app :reader 1]}]
                      :mutation [:rf.mutation/execute {:mutation owner :params {:slug slug}
                                                       :instance [owner slug]}])
                    {:frame frame-id})
  (rf/dispatch-sync (conj (get @managed-args callback) outcome) {:frame frame-id})
  (let [record  (rf/project-egress (last (rf.epoch/epoch-history frame-id))
                                   {:rf.egress/include-event-args? true})
        trigger (:trigger-event record)]
    (into [trigger]
          (keep #(let [v (get-in % [:tags :rf.event/v])]
                   (when (= (first trigger) (first v)) v)))
          (:trace-events record))))

(deftest a-transport-replys-result-takes-its-owners-coarse-claim
  (doseq [[kind coarse plain] [[:resource :r/coarse :r/plain] [:mutation :m/coarse :m/plain]]]
    (testing (str kind)
      (let [copies (settle-transport! kind coarse "ok" :on-success {:status :ok :value {:v V}})]
        (is (< 1 (count copies)) "the trigger and the live :rf.event/v copies")
        (is (every? #(redacted-token? (get-in % [2 :value])) copies)
            "a coarse owner's value tokenizes on every copy")
        (is (= [] (leak-paths copies V))))
      (let [copies (settle-transport! kind plain "ok" :on-success {:status :ok :value {:v V}})]
        (is (every? #(= {:v V} (get-in % [2 :value])) copies)
            "control: a plain owner's value rides"))
      (let [copies (settle-transport! kind plain "failed" :on-failure
                                      {:status :error
                                       :error  {:kind :rf.http/http-5xx :body {:echo V}}})]
        (is (every? #(redacted-token? (get-in % [2 :error])) copies)
            "the failure envelope tokenizes whatever the owner")))))

;; ===========================================================================
;; a transport reply's result takes its owner's declared paths
;; ===========================================================================

;; An owner declaring only paths classifies `:serialize`, so its coarse claim
;; leaves the value alone and the declared paths substitute instead, as they do
;; on the continuation reply carrying the same value to its `:reply-to` target.

(defn- event-copies
  "The off-box copies, event args opted in, of every event the frame ran that
  `pred` accepts, by carrier: each epoch record's `:trigger-event` and its
  trace's `:rf.event/v`."
  [pred]
  (let [records  (mapv #(rf/project-egress % {:rf.egress/include-event-args? true})
                       (rf.epoch/epoch-history frame-id))
        accepted (fn [vs] (filterv #(and (vector? %) (pred %)) vs))]
    {:trigger-event (accepted (map :trigger-event records))
     :rf.event/v    (accepted (for [r records ev (:trace-events r)]
                                (get-in ev [:tags :rf.event/v])))}))

(deftest a-transport-replys-result-takes-its-owners-declared-paths
  (doseq [[kind owner transport-id] [[:resource :r/paths :rf.resource.internal/succeeded]
                                     [:mutation :m/paths :rf.mutation.internal/succeeded]]]
    (testing (str kind)
      (let [rows (atom [])]
        (rf/register-listener! :trace ::rows (fn [ev] (swap! rows conj ev)))
        (try
          (rf/dispatch-sync (case kind
                              :resource [:rf.resource/ensure {:resource owner :params {:slug "p"}
                                                              :scope    :rf.scope/global
                                                              :owner    [:app :reader 1]
                                                              :reply-to [:app/replied]}]
                              :mutation [:rf.mutation/execute {:mutation owner :params {:slug "p"}
                                                               :instance [owner "p"]
                                                               :reply-to [:app/replied]}])
                            {:frame frame-id})
          (rf/dispatch-sync (conj (:on-success @managed-args) {:status :ok :value {:token V :ok 1}})
                            {:frame frame-id})
          (finally (rf/unregister-listener! :trace ::rows)))
        (let [transport (event-copies #(= transport-id (first %)))
              reply-to  (event-copies #(and (= :app/replied (first %))
                                            (= kind (get-in % [1 :rf.reply/work-kind]))))
              on-box    (filterv #(and (vector? %) (= transport-id (first %)))
                                 (keep #(get-in % [:tags :rf.event/v]) @rows))]
          (doseq [[copy vs] transport]
            (is (seq vs) (str "the transport event rides " copy))
            (is (every? #(= :rf/redacted (get-in % [2 :value :token])) vs)
                (str "the declared path redacts on " copy))
            (is (every? #(= 1 (get-in % [2 :value :ok])) vs)
                (str "control: the undeclared sibling rides on " copy)))
          (doseq [[copy vs] reply-to]
            (is (seq vs) (str "the :reply-to target event rides " copy))
            (is (every? #(= {:token :rf/redacted :ok 1} (get-in % [1 :value])) vs)
                (str "control: the continuation reply redacts the path on " copy)))
          (is (= [] (leak-paths [transport reply-to] V)) "no off-box copy carries the raw value")
          (is (seq on-box) "the on-box trace carries the transport event")
          (is (every? #(= {:token V :ok 1} (get-in % [2 :value])) on-box)
              "the on-box trace keeps the raw value")
          (is (= {:token V :ok 1} (:value @replied))
              "the app's own :reply-to handler receives the raw value"))))))
