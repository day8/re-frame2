(ns re-frame.install-payload-trace-redaction-test
  "The two framework installers, `[:rf/install-frame-state saved]` and
  `[:rf/hydrate payload]`, carry a whole frame-state (secrets included) as their
  payload, and declare it `:sensitive` as a whole. So every egress door that
  projects the event vector (the trace stream, the frame's `:errors` sink) shows
  `[<event-id> :rf/redacted]`, while the state itself is installed as sent.

  Traces are dev-only, so the trace assertions are written to hold in both
  postures (no captured trace carries a secret, which a production build
  satisfies by emitting none); the assertions that a trace EXISTS sit in
  `(when rf.interop/debug-enabled? ...)`. The `:errors` sink record, the shared
  declaration and the installed state are always-on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop :as rf.interop]
            ;; Both artefacts register an installer's collaborators at load:
            ;; machines the snapshot runtime, SSR the `:rf/hydrate` event.
            [re-frame.machines]
            [re-frame.observability :as rf.observability]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn rf.observability/clear-observability-sinks!}))

(def ^:private machine-secret "saved-machine-secret")
(def ^:private app-secret "saved-app-password")
(def ^:private wire-secret "permitted-csrf-token")

(defn- carries? [v s] (str/includes? (pr-str v) s))

(defn- captured
  "Run `f` and return every trace event a listener received while it ran —
  the events as tools and the trace buffer see them."
  [f]
  (let [seen (atom [])]
    (rf.trace.tooling/register-listener! ::capture #(swap! seen conj %))
    (try (f)
         (finally (rf.trace.tooling/unregister-listener! ::capture)))
    @seen))

(defn- event-v-of
  "The `:rf.event/v` of the first captured trace with `operation`."
  [traces operation]
  (some #(when (= operation (:operation %)) (get-in % [:tags :rf.event/v])) traces))

(def ^:private event-v-carriers
  "The per-event traces that echo the event vector under `:rf.event/v`."
  [:rf.event/dispatched :rf.event/run-start :rf.event/db-pending
   :rf.event/db-changed :rf.event/frame-state-changed :rf.event/run-end])

(defn- assert-installer-traces-redacted
  "The trace assertions for an installer run as `event-id`, split by posture.
  In both postures: no trace in `traces` carries any of `secrets`, and every
  trace echoing `event-id`'s vector reads `[event-id :rf/redacted]`. In a dev
  build: each of `event-v-carriers` is present."
  [traces event-id secrets]
  (doseq [s secrets]
    (is (not-any? #(carries? % s) traces) (str "no trace carries " s)))
  (doseq [t traces
          :let [v (get-in t [:tags :rf.event/v])]
          :when (and (vector? v) (= event-id (first v)))]
    (is (= [event-id :rf/redacted] v)
        (str (:operation t) " carries the event id and the payload redacted as a whole")))
  (when rf.interop/debug-enabled?
    (doseq [op event-v-carriers]
      (is (= [event-id :rf/redacted] (event-v-of traces op))
          (str op " is emitted, redacted")))))

(defn- reg-fixtures! []
  (rf/reg-machine :trc/vault
    {:sensitive [[:data :token]]
     :initial   :idle
     :data      {:token machine-secret :label "public"}
     :states    {:idle {}}})
  (rf/reg-event :trc/sign-in
    (fn [{:keys [db]} _]
      {:db        (assoc-in db [:account :password] app-secret)
       :sensitive [[:account :password]]}))
  (rf/reg-event :trc/classify
    (fn [{:keys [db]} [_ paths]]
      {:db db :sensitive paths}))
  (rf/reg-event :trc/note
    (fn [{:keys [db]} [_ m]]
      {:db (assoc db :note (:note m))})))

(defn- saved-frame-state!
  "Run a classified machine and a classified app-db value on a live source
  frame, and return what the persistence guide saves: app-db plus the
  machines subtree."
  []
  (rf/make-frame {:id :trc/source})
  (rf/dispatch-sync [:trc/vault [:rf.machine/noop]] {:frame :trc/source})
  (rf/dispatch-sync [:trc/sign-in] {:frame :trc/source})
  (let [{app :rf.db/app runtime :rf.db/runtime} (rf/frame-state-value :trc/source)]
    (is (= machine-secret (get-in runtime [:rf.runtime/machines :snapshots :trc/vault :data :token]))
        "precondition: the saved machines subtree carries the machine secret")
    (is (= app-secret (get-in app [:account :password]))
        "precondition: the saved app-db carries the app secret")
    {:rf.db/app     app
     :rf.db/runtime (select-keys runtime [:rf.runtime/machines])}))

(deftest an-install-traces-no-secret-it-carried
  (reg-fixtures!)
  (let [saved  (saved-frame-state!)
        _      (rf/make-frame {:id :trc/dest})
        ;; the destination classifies its own app-db path, as an app's events do
        _      (rf/dispatch-sync [:trc/classify [[:account :password]]] {:frame :trc/dest})
        traces (captured #(rf/dispatch-sync [:rf/install-frame-state saved] {:frame :trc/dest}))]
    (assert-installer-traces-redacted traces :rf/install-frame-state [machine-secret app-secret])
    (is (= app-secret (get-in (rf/app-db-value :trc/dest) [:account :password]))
        "the installed state itself is not redacted")
    (is (= machine-secret
           (get-in (rf/frame-state-value :trc/dest)
                   [:rf.db/runtime :rf.runtime/machines :snapshots :trc/vault :data :token])))
    (when rf.interop/debug-enabled?
      (let [ordinary (captured #(rf/dispatch-sync [:trc/note {:note "visible-note"}] {:frame :trc/dest}))]
        (is (= [:trc/note {:note "visible-note"}] (event-v-of ordinary :rf.event/dispatched))
            "control: the capture sees an ordinary event's payload")))))

(deftest the-registrar-and-the-image-standard-carry-one-declaration
  (let [standard (some #(when (= [:event :rf/install-frame-state] [(:kind %) (:id %)]) %)
                       (rf.image-assembly/standard-descriptors))]
    (is (= [[]] (:sensitive (rf.registrar/handler-meta :event :rf/install-frame-state))))
    (is (= [[]] (:sensitive standard)))))

(deftest a-refused-install-egresses-no-secret-it-carried
  (reg-fixtures!)
  (let [saved   (saved-frame-state!)
        records (atom [])
        refused (assoc-in saved [:rf.db/runtime :rf.runtime/resources] {})
        secret? (fn [v] (or (carries? v machine-secret) (carries? v app-secret)))]
    (rf/register-observability-sink! ::errors #(swap! records conj %))
    (rf/make-frame {:id :trc/refusing
                    :observability {:errors [{:sink ::errors
                                              :rf.egress/profile :rf.egress/off-box-observability}]}})
    (let [traces (captured #(rf/dispatch-sync [:rf/install-frame-state refused] {:frame :trc/refusing}))]
      (is (some #(= :rf.error/handler-exception (:error %)) @records)
          "precondition: the refusal reached the frame's :errors sink")
      (is (some #(contains? % :event) @records)
          "precondition: the sink's record carries the event slot")
      (is (not-any? secret? @records) "no sink record carries a secret")
      (is (not-any? secret? traces) "no trace carries a secret"))))

(deftest a-hydration-traces-no-secret-it-carried
  (reg-fixtures!)
  (rf/make-frame {:id :trc/client :platform :client})
  (rf/dispatch-sync [:trc/classify [[:csrf]]] {:frame :trc/client})
  (let [payload {:rf/version 1 :rf/app-db {:csrf wire-secret :greeting "hello"}}
        traces  (captured #(rf/dispatch-sync [:rf/hydrate payload] {:frame :trc/client}))]
    (assert-installer-traces-redacted traces :rf/hydrate [wire-secret])
    (is (= wire-secret (:csrf (rf/app-db-value :trc/client))))))
