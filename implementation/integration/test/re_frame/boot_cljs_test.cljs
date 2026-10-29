(ns re-frame.boot-cljs-test
  "Drives the boot example (`examples/patterns/boot/`, whose source stays
   test-free) through its Pattern-Boot trajectory: each test makes a fresh
   frame that fires `:boot/initialise` inside a `with-transport` scope, so
   the boot's managed requests are answered by a stub transport (Spec 014
   §Testing). The ns requires `boot.core` so the example's machine, loader,
   subs and fxs are registered, and the snapshot/restore fixture keeps those
   ns-load registrations intact for later namespaces."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.integration-support :as rf.integration-support]
            [re-frame.test-support :as rf.test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; boot.core
            ;; pulls it transitively (via boot.schema), require explicitly so
            ;; this ns is self-sufficient.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [boot.schema :as boot-schema]
            [re-frame.views]
            [boot.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

;; ============================================================================
;; STUB TRANSPORTS
;; ============================================================================

(defn- boot-frame!
  "A fresh frame that fires `:boot/initialise`, its managed requests answered
   by `transport`. The boot issues every request from the frame's synchronous
   initial drain, so a `with-transport` scope around the construction reaches
   them all."
  [transport]
  (rf.http.stub-transport/with-transport transport
    #(rf.frame/make-anon-frame-record! {:initial-events [[:boot/initialise]]})))

(defn- answering-by-url
  "A route transport answering each request 200 with `(url->value url)`."
  [url->value]
  (rf.http.stub-transport/route-transport (fn [{:keys [url]}] {:body (url->value url)})))

(defn- join-failure-transport
  "The JOIN-child failure path. /config.json answers `config` and /user.json
   answers 503. /routes.json and /flags.json are held on `held`, a pending
   transport, so those two siblings are still in flight whenever the failure
   lands. That makes the cancellation deterministic without depending on
   which reply arrives first."
  [config held]
  (let [answer (rf.http.stub-transport/route-transport
                 (fn [{:keys [url]}]
                   (if (re-find #"/user\.json$" url)
                     {:status 503 :body "user.json outage"}
                     {:body config})))]
    (fn [wire callbacks]
      (if (re-find #"/(routes|flags)\.json$" (:url wire))
        ((:transport held) wire callbacks)
        (answer wire callbacks)))))

;; ============================================================================
;; DEMO PAYLOADS
;; ============================================================================
;;
;; Matched against the URL substring per the same routing the
;; demo stub in core.cljs uses.

(def ^:private test-config
  {:api-base "/api"
   :env      "dev"
   :build    "test-build"
   :title    "Boot test app"})

(def ^:private test-routes
  [{:id "home"  :path "/"}
   {:id "about" :path "/about"}])

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

;; A tiny event used only by the app-db slice test below to write a malformed
;; [:config] app-db slice, so the app-db slice schema's :where :app-db boundary
;; is exercised distinctly from the machine :data boundary. It sits above the
;; fixture so the fixture's ns-load baseline holds it.
(rf/reg-event ::write-bad-config
  (fn [{:keys [db]} _] {:db (assoc db :config {:api-base "/api"})}))   ;; missing required Config keys

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
    {:adapter       rf.integration-adapter/adapter
     :ambient-frame nil}))

;; ============================================================================
;; TESTS
;; ============================================================================


(deftest boot-machine-progression
  (testing "happy path: boot machine traverses :configuring → :loading-deps → :hydrating → :ready and all slices land"
    (with-new-frame [f (boot-frame! (answering-by-url payload-for))]
      ;; A route answers inline, so the synchronous drain runs all four
      ;; requests to completion.
      (let [db       (rf/frame-state-value f)
            payloads [test-config test-routes test-flags test-user]]
        (is (= :ready (rf/compute-sub [:app.boot/state] db)))
        (is (= payloads
               (mapv #(rf/compute-sub [%] db) [:app/config :app/routes :app/flags :app/user]))
            "the top-level slices hydrated from the machine's :data")))))

(deftest boot-failure-path
  (testing "a failure during the parallel phase routes the boot to :failed and records the error"
    (with-new-frame [f (boot-frame! (rf.http.stub-transport/route-transport
                                      (constantly {:status 500 :body "boot dependency unreachable"})))]
      ;; The blanket 500 fails every child; the first failure routes the
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
    (let [traces (atom [])
          held   (rf.http.stub-transport/pending-transport)]
      (rf/register-listener! :trace ::join-failure (fn [ev] (swap! traces conj ev)))
      (try
        (with-new-frame [f (boot-frame! (join-failure-transport test-config held))]
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
            (is (= [:failed 503 nil [] #{:routes :flags} true [true true]]
                   [(rf/compute-sub [:app.boot/state] db)
                    (:http-status (rf/compute-sub [:app.boot/error] db))
                    (:user boot-data)
                    rejected
                    cancelled
                    (not-any? live? (map #(-> % :tags :spawned-id) cancels))
                    (mapv #((:cancelled? %)) @(:exchanges held))])
                (str ":on-any-failed took the boot to :failed recording the /user.json 503, which "
                     "never reached the :user fold or a :machine-data rejection, and cancelled and "
                     "tore down both in-flight siblings, aborting their held requests"))))
        (finally (rf/unregister-listener! :trace ::join-failure))))))

;; ============================================================================
;; PAYLOAD AND SCHEMA BOUNDARIES
;; ============================================================================
;;
;; A payload is checked where it enters. Each loader decodes its reply
;; against its asset's schema (`boot.schema/Config` and siblings), so a
;; malformed payload fails the fetch, in every build, and the boot reaches
;; `:failed` — it never reaches the `:app/boot` machine's `:data`, whose
;; `[:schemas :data]` (`BootData`) would only refuse the fold and leave the
;; boot waiting. The app-db slice schemas (`reg-app-schema [:config]` etc.)
;; validate the app-db partition independently.

(deftest boot-malformed-payloads-fail-at-their-boundary
  (testing "a malformed /config.json fails its loader's decode, so the boot reaches :failed and nothing malformed reaches the boot machine's :data"
    ;; The backend answers a structurally-WRONG config (missing
    ;; :env / :build / :title) for /config.json; the other URLs are
    ;; never reached because the config load fails first.
    (let [bad-config (answering-by-url
                       (fn [url]
                         (if (re-find #"/config\.json$" (str url))
                           {:api-base "/api"}     ;; missing :env :build :title → violates Config
                           {})))
          frame      (atom nil)
          traces     (rf.integration-support/collect-machine-data-traces!
                       #(reset! frame (boot-frame! bad-config)))]
      (try
        (let [db (rf/frame-state-value @frame)]
          (is (= [:failed :rf.http/decode-failure nil []]
                 [(rf/compute-sub [:app.boot/state] db)
                  (:kind (rf/compute-sub [:app.boot/error] db))
                  (rf/compute-sub [:app/config] db)
                  (filterv #(= :app/boot (-> % :tags :machine-id)) traces)])
              "the decode failure took the boot to :failed, recorded as its error, with no :machine-data rejection on the way"))
        (finally (when @frame (rf/destroy-frame! @frame))))))

  (testing "the app-db slice schema validates the app-db partition independently of the machine :data boundary"
    ;; The example registers `[:config] Config` as an APP schema
    ;; (validates the app-db partition only). Register the example's own
    ;; `Config` from this test, so this frame resolves it whatever the
    ;; bundle's baseline holds, then write
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
          (rf/reg-app-schema [:config] [:maybe boot-schema/Config])
          (rf/dispatch-sync [::write-bad-config] {:frame f}))
        (finally (rf/unregister-listener! :trace ::app)))
      (is (pos? (count @app-traces))
          "a malformed [:config] app-db slice fails at :where :app-db (slice schema validates app-db only)"))))
