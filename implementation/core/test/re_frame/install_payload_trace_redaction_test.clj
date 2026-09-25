(ns re-frame.install-payload-trace-redaction-test
  "The two framework installers carry a whole frame-state as their event
  payload, so their own traces and egress records must not echo it.

  `[:rf/install-frame-state saved]` carries an app's persisted state: its
  app-db and its machine snapshots, secrets included. `[:rf/hydrate payload]`
  carries the SSR hydration payload, which can hold a value the host permits
  onto the wire raw (a CSRF token, say). Both events declare their payload
  `:sensitive` as a whole, so every egress door that projects an event vector
  through its registration — the trace stream, the frame's `:errors` sink —
  shows the event id and `:rf/redacted` in place of the payload. The state
  itself is installed as sent and stays readable in the frame.

  The app-db an installer writes is classified the way any app-db value is,
  by the destination frame's own claims, and the pending-db traces are
  projected against those. So each test classifies its app-db secret's path
  in the destination first, as the app's own events do; a machine secret is
  classified by its machine definition.

  What must hold:

    - no trace emitted while the installer runs carries a secret the payload
      carried, and the installer's own `:rf.event/v` reads
      `[<event-id> :rf/redacted]`;
    - a refused install's `:rf.observe/error` record, as the frame's `:errors`
      sink receives it, carries no secret either;
    - an ordinary event's trace still shows its payload, so the capture can
      see payloads at all;
    - the installed values are in the frame.

  JVM-only: the core test classpath carries the machines and SSR artefacts
  both installers need."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Both artefacts register an installer's collaborators at load:
            ;; machines the snapshot runtime, SSR the `:rf/hydrate` event.
            [re-frame.machines]
            [re-frame.observability :as rf.observability]
            [re-frame.ssr]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn rf.observability/clear-observability-sinks!}))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; :rf/install-frame-state
;; ---------------------------------------------------------------------------

(deftest an-install-traces-no-secret-it-carried
  (testing "the install event's traces redact its whole payload; the state
            it installs is readable in the frame"
    (reg-fixtures!)
    (let [saved  (saved-frame-state!)
          _      (rf/make-frame {:id :trc/dest})
          ;; The destination classifies its own app-db path, as the app's own
          ;; events do; the install payload carries no classification.
          _      (rf/dispatch-sync [:trc/classify [[:account :password]]] {:frame :trc/dest})
          traces (captured #(rf/dispatch-sync [:rf/install-frame-state saved] {:frame :trc/dest}))]
      (is (seq traces) "precondition: the install emitted traces")
      (is (not-any? #(carries? % machine-secret) traces) "no trace carries the machine secret")
      (is (not-any? #(carries? % app-secret) traces) "no trace carries the app-db secret")
      (is (= [:rf/install-frame-state :rf/redacted]
             (event-v-of traces :rf.event/dispatched))
          "the event id survives; the payload is redacted as a whole")

      (testing "the installed state is readable in the frame"
        (is (= app-secret (get-in (rf/app-db-value :trc/dest) [:account :password])))
        (is (= machine-secret
               (get-in (rf/frame-state-value :trc/dest)
                       [:rf.db/runtime :rf.runtime/machines :snapshots :trc/vault :data :token]))))

      (testing "an ordinary event's trace still shows its payload"
        (let [ordinary (captured #(rf/dispatch-sync [:trc/note {:note "visible-note"}] {:frame :trc/dest}))]
          (is (= [:trc/note {:note "visible-note"}]
                 (event-v-of ordinary :rf.event/dispatched))))))))

(deftest a-refused-install-egresses-no-secret-it-carried
  (testing "a refused install's error record, as the frame's `:errors` sink
            receives it, carries no secret the payload carried"
    (reg-fixtures!)
    (let [saved   (saved-frame-state!)
          records (atom [])
          refused (assoc-in saved [:rf.db/runtime :rf.runtime/resources] {})]
      (rf/register-observability-sink! ::errors #(swap! records conj %))
      (rf/make-frame {:id :trc/refusing
                      :observability {:errors [{:sink ::errors
                                                :rf.egress/profile :rf.egress/off-box-observability}]}})
      (let [traces (captured #(rf/dispatch-sync [:rf/install-frame-state refused] {:frame :trc/refusing}))]
        (is (some #(= :rf.error/handler-exception (:error %)) @records)
            "precondition: the refusal reached the frame's :errors sink")
        (is (some #(contains? % :event) @records)
            "precondition: the sink's record carries the event slot")
        (is (not-any? #(carries? % machine-secret) @records) "no sink record carries the machine secret")
        (is (not-any? #(carries? % app-secret) @records) "no sink record carries the app-db secret")
        (is (not-any? #(carries? % machine-secret) traces) "no trace carries the machine secret")
        (is (not-any? #(carries? % app-secret) traces) "no trace carries the app-db secret")
        (is (nil? (get-in (rf/frame-state-value :trc/refusing)
                          [:rf.db/runtime :rf.runtime/machines]))
            "precondition: nothing was installed")))))

;; ---------------------------------------------------------------------------
;; :rf/hydrate
;; ---------------------------------------------------------------------------

(deftest a-hydration-traces-no-secret-it-carried
  (testing "the hydrate event's traces redact its whole payload; the hydrated
            state is readable in the frame"
    (reg-fixtures!)
    (rf/make-frame {:id :trc/client :platform :client})
    (rf/dispatch-sync [:trc/classify [[:csrf]]] {:frame :trc/client})
    (let [payload {:rf/version 1 :rf/app-db {:csrf wire-secret :greeting "hello"}}
          traces  (captured #(rf/dispatch-sync [:rf/hydrate payload] {:frame :trc/client}))]
      (is (seq traces) "precondition: the hydration emitted traces")
      (is (not-any? #(carries? % wire-secret) traces) "no trace carries the permitted secret")
      (is (= [:rf/hydrate :rf/redacted] (event-v-of traces :rf.event/dispatched))
          "the event id survives; the payload is redacted as a whole")
      (is (= wire-secret (:csrf (rf/app-db-value :trc/client)))
          "the hydrated state is readable in the frame"))))
