(ns re-frame.boot-cljs-test
  "Drives the boot example (`examples/patterns/boot/`, whose source stays
   test-free) through its Pattern-Boot trajectory: each test makes a fresh
   frame that fires `:boot/initialise`, with `:rf.http/managed` routed by
   `:fx-overrides` to per-URL wrappers over the framework's canned stubs
   (Spec 014 §Testing). The ns requires `boot.core` so the example's
   machine, loader, subs and fxs are registered, and the snapshot/restore
   fixture keeps those ns-load registrations intact for later namespaces."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; boot.core
            ;; pulls it transitively (via boot.schema), require explicitly so
            ;; this ns is self-sufficient.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [boot.schema :as boot-schema]
            [re-frame.views]
            ;; The canned-stub helpers below resolve
            ;; :rf.http/managed-canned-success / failure via registrar
            ;; lookup. Those fx ids register from
            ;; re-frame.http.test-support, NOT re-frame.http.managed.
            ;; boot.core already requires re-frame.http.test-support via
            ;; the boot.schema / boot.boot graph; require explicitly here
            ;; too so this test ns is self-sufficient if a future refactor
            ;; unhooks the transitive load.
            [re-frame.http.test-support]
            [boot.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; PER-URL CANNED STUBS
;; ============================================================================
;;
;; A local copy of the realworld helper, so this ns need not load realworld.

(defn- reg-canned-success-by-url!
  "Register an fx-id that delegates to :rf.http/managed-canned-success,
   choosing `:value` per the request URL. `url->value` is a 1-arity fn
   receiving the URL string and returning the synthesised :value."
  [fx-id url->value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub  (rf.registrar/handler :fx :rf.http/managed-canned-success)
            url   (-> args :request :url)
            value (url->value url)]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-failure!
  "Register an fx-id that delegates to :rf.http/managed-canned-failure."
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (rf.registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind kind :tags tags))))))

(defn- reg-join-failure-stub!
  "Register an fx-id for the JOIN-child failure path. /config.json answers
   `config` and /user.json fails. /routes.json and /flags.json are NEVER
   answered, so those two siblings are still in flight whenever the failure
   lands. That makes the cancellation deterministic without depending on
   which reply arrives first."
  [fx-id config]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [url (str (-> args :request :url))]
        (cond
          (re-find #"/config\.json$" url)
          ((rf.registrar/handler :fx :rf.http/managed-canned-success)
           frame-ctx (assoc args :value config))

          (re-find #"/user\.json$" url)
          ((rf.registrar/handler :fx :rf.http/managed-canned-failure)
           frame-ctx (assoc args :kind :rf.http/http-5xx
                                 :tags {:status 503 :message "user.json outage"}))

          ;; routes / flags: held in flight — no reply at all.
          :else nil)))))

;; ============================================================================
;; DEMO PAYLOADS
;; ============================================================================
;;
;; Matched against the URL substring per the same routing the
;; demo stub in core.cljs uses.

(def ^:private test-config
  {:api-base "/api"
   :env      :dev
   :build    "test-build"
   :title    "Boot test app"})

(def ^:private test-routes
  [{:id :boot.demo/home  :path "/"}
   {:id :boot.demo/about :path "/about"}])

(def ^:private test-flags
  {:dark-mode?       true
   :beta-channel?    false
   :onboarding-skip? true})

(def ^:private test-user
  {:id "u1" :username "alice" :email "alice@example.com"})

(defn- payload-for [url]
  (let [u (str url)]
    (cond
      (re-find #"/config\.json$" u) test-config
      (re-find #"/routes\.json$" u) test-routes
      (re-find #"/flags\.json$"  u) test-flags
      (re-find #"/user\.json$"   u) test-user
      :else                         {})))

;; ============================================================================
;; FIXTURE
;; ============================================================================

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    ;; EP-0002: each test models a TOP-LEVEL boot in its own frame, so opt
    ;; out of the ambient `:rf/default` scope: the new frame's
    ;; `:initial-events` then drain synchronously instead of being treated
    ;; as a mid-cascade child-frame creation, and the post-boot state is
    ;; observable.
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

;; ============================================================================
;; TESTS
;; ============================================================================


(deftest boot-machine-progression
  (testing "happy path: boot machine traverses :configuring → :loading-deps → :hydrating → :ready and all slices land"
    (reg-canned-success-by-url! :boot.test/canned-boot-success payload-for)

    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:initial-events [[:boot/initialise]]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-success}})]
      ;; The synchronous drain runs all four canned-success stubs to completion.
      (let [db       (rf/frame-state-value f)
            payloads [test-config test-routes test-flags test-user]]
        (is (= :ready (rf/compute-sub [:app.boot/state] db)))
        ;; A child completes by reaching a `:final?` state, and the parent's
        ;; `:on-done` fold is the only writer of the machine's own :data.
        (is (= payloads
               ((juxt :config :routes :flags :user)
                (get-in db [:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :data])))
            "each payload folded into its own slot of the boot machine's :data")
        (is (= payloads
               (mapv #(rf/compute-sub [%] db) [:app/config :app/routes :app/flags :app/user]))
            "the top-level slices hydrated from the machine's :data")))))

(deftest boot-failure-path
  (testing "a failure during the parallel phase routes the boot to :failed and records the error"
    (reg-canned-failure! :boot.test/canned-boot-fail
                         :rf.http/http-5xx
                         {:status 500
                          :body   "boot dependency unreachable"})

    (with-new-frame [f (rf.frame/make-anon-frame-record!
                         {:initial-events [[:boot/initialise]]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-fail}})]
      ;; The blanket stub fails every child; the first failure routes the
      ;; boot to :failed.
      (let [db (rf/frame-state-value f)]
        (is (= [:failed true]
               [(rf/compute-sub [:app.boot/state] db) (some? (rf/compute-sub [:app.boot/error] db))])
            "the boot reached :failed with :app.boot/error populated")))))

(deftest boot-join-child-failure-path
  (testing "/user.json alone fails inside the :spawn-all: the boot reaches :failed, the failure never lands in :user, and the in-flight siblings are cancelled"
    ;; `boot-failure-path` above reaches `:failed` through the single-`:spawn`
    ;; config loader's `:on-error`, because its blanket stub fails /config.json
    ;; first. This one lets config succeed and fails a JOIN child, with the
    ;; real `BootData` schema attached. Were the failed child's per-child
    ;; `:on-done` to fold the 503 map into `:user` (`[:maybe User]`), the
    ;; schema rollback would swallow the carrier and the boot would hang at
    ;; `:loading-deps` for ever.
    (reg-join-failure-stub! :boot.test/fail-user-json test-config)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::join-failure (fn [ev] (swap! traces conj ev)))
      (try
        (with-new-frame [f (rf.frame/make-anon-frame-record!
                             {:initial-events [[:boot/initialise]]
                              :fx-overrides {:rf.http/managed
                                             :boot.test/fail-user-json}})]
          (let [db        (rf/frame-state-value f)
                boot-data (get-in db [:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :data])
                cancels   (filterv #(= :rf.machine.spawn/cancelled-on-join-resolution
                                       (:operation %))
                                   @traces)
                cancelled (into #{} (map #(-> % :tags :child-id)) cancels)
                live?     (fn [actor-id]
                            (some? (get-in db [:rf.db/runtime :rf.runtime/machines
                                               :snapshots actor-id])))
                rejected  (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                                         (= :machine-data (-> % :tags :where)))
                                   @traces)]
            (is (= [:failed 503 nil [] #{:routes :flags} true]
                   [(rf/compute-sub [:app.boot/state] db)
                    (:status (rf/compute-sub [:app.boot/error] db))
                    (:user boot-data)
                    rejected
                    cancelled
                    (not-any? live? (map #(-> % :tags :spawned-id) cancels))])
                (str ":on-any-failed took the boot to :failed recording the /user.json 503, which "
                     "never reached the :user fold or a :machine-data rejection, and cancelled and "
                     "tore down both in-flight siblings"))))
        (finally (rf/unregister-listener! :trace ::join-failure))))))

;; ============================================================================
;; MACHINE :data SCHEMA BOUNDARY
;; ============================================================================
;;
;; The singleton `:app/boot` machine attaches a `[:schemas :data]` schema
;; (`boot.schema/BootData`) that validates the snapshot's `:data` slot at the
;; `:where :machine-data` boundary (Spec 005 §Schema validation). These
;; tests prove the schema is attached, rejects malformed `:data`, fires the
;; boundary trace + rolls back on a real violating macrostep, and that the
;; app-db slice schemas (`reg-app-schema [:config]` etc.) keep validating the
;; app-db partition independently.

(defn- collect-machine-data-traces!
  "Run `thunk` while collecting `:rf.error/schema-validation-failure` traces
   with `:where :machine-data`. Returns the captured (filtered) vector."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
    (try (thunk)
         (finally (rf/unregister-listener! :trace ::collect)))
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))

(deftest boot-malformed-data-fails-boundary
  (testing "a config child returning a malformed Config drives :promote-staged to write bad :data, failing the :machine-data boundary"
    ;; canned-success returns a structurally-WRONG config (missing
    ;; :env / :build / :title) for /config.json; the other URLs are
    ;; never reached because the config macrostep fails first.
    (reg-canned-success-by-url!
      :boot.test/canned-bad-config
      (fn [url]
        (if (re-find #"/config\.json$" (str url))
          {:api-base "/api"}     ;; missing :env :build :title → violates Config
          {})))
    (let [frame  (atom nil)
          traces (collect-machine-data-traces!
                   #(reset! frame
                      (rf.frame/make-anon-frame-record!
                        {:initial-events [[:boot/initialise]]
                         :fx-overrides {:rf.http/managed
                                        :boot.test/canned-bad-config}})))]
      (try
        ;; `:recovery` rides the trace ENVELOPE, not :tags, as on the
        ;; :where :app-db projection.
        (let [boot-trace (some #(when (= :app/boot (-> % :tags :machine-id)) %) traces)]
          (is (= :no-recovery (:recovery boot-trace))
              (str "a :where :machine-data trace naming :app/boot fires, :no-recovery; got "
                   (pr-str traces))))
        (finally (when @frame (rf/destroy-frame! @frame))))))

  (testing "the app-db slice schema validates the app-db partition independently of the machine :data boundary"
    ;; The example registers `[:config] [:maybe Config]` as an APP schema
    ;; (validates the app-db partition only). App schemas are frame-scoped,
    ;; so register the example's own `Config` on this test frame, then write
    ;; a structurally-wrong slice: the post-commit app-db validator must
    ;; reject it at `:where :app-db` — a DIFFERENT boundary from the
    ;; machine `:data` one above, proving the two surfaces are distinct.
    (let [app-traces (atom [])]
      (rf/register-listener! :trace ::app
                             (fn [ev]
                               (when (and (= :rf.error/schema-validation-failure (:operation ev))
                                          (= :app-db (-> ev :tags :where)))
                                 (swap! app-traces conj ev))))
      (try
        (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
          (rf/reg-app-schema [:config] {:frame f} [:maybe boot-schema/Config])
          (rf/dispatch-sync [::write-bad-config] {:frame f}))
        (finally (rf/unregister-listener! :trace ::app)))
      (is (pos? (count @app-traces))
          "a malformed [:config] app-db slice fails at :where :app-db (slice schema validates app-db only)"))))

;; A tiny event used only by the test above to write a malformed [:config]
;; app-db slice, so the app-db slice schema's :where :app-db boundary is
;; exercised distinctly from the machine :data boundary.
(rf/reg-event ::write-bad-config
  (fn [{:keys [db]} _] {:db (assoc db :config {:api-base "/api"})}))   ;; missing required Config keys
