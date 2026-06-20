(ns re-frame.boot-cljs-test
  "Integration test: drives the boot example (rf2-dsm2) through a
   canonical Pattern-Boot trajectory. Each test spins a fresh frame
   via `make-frame`, fires the `:boot/initialise` event, and asserts
   the :app/boot state machine and the four loaded slices end up in
   the expected shape. Managed-HTTP is stubbed via `:fx-overrides`
   routing every `:rf.http/managed` call to a per-URL
   canned-success / canned-failure wrapper that delegates to the
   framework-shipped stubs (Spec 014 §Testing).

   The fixture fns + canned-stub helpers live HERE (the adapter test
   tree), not under examples/reagent/boot/ — the example source stays
   test-free per the locked test-free-examples policy (rf2-8cevm). The
   ns requires the example's production source (`boot.core`, which
   chains in `boot.boot` / `boot.schema`) so the boot machine, loader,
   subs and demo fxs are registered, then exercises them directly.
   (rf2-m2lol folded the former `boot.boot-test` fixture ns in here and
   retired the example test/ dir.)

   Per rf2-am9d this ns uses snapshot/restore via re-frame.test-support
   so the contract is uniform across CLJS fixtures: the snapshot
   captures the boot example's ns-load registrations
   (`:app/boot`, `:boot/loader`, `:app/initialise`, the subs and the
   demo fxs), and the restore on the way out leaves them intact for
   any subsequent test ns.

   Coverage:
     - boot-machine-progression   — the boot machine traverses
       :configuring → :loading-deps → :hydrating → :ready, and all
       four loaded slices land in app-db.
     - boot-dependency-resolution — the per-child :data fns thread
       the spawn-spec identity correctly so each child writes its
       payload to the matching staging key (no cross-talk).
     - boot-failure-path          — a failure during the parallel
       phase routes the boot to :failed and records the error in
       :data."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; boot.core
            ;; pulls it transitively (via boot.schema), require explicitly so
            ;; this ns is self-sufficient.
            [re-frame.schemas :as schemas]
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
;; The realworld test-helpers provide reg-canned-success-by-url! which
;; we reproduce locally so the boot tests don't have to require the
;; realworld ns just for one helper.

(defn- reg-canned-success-by-url!
  "Register an fx-id that delegates to :rf.http/managed-canned-success,
   choosing `:value` per the request URL. `url->value` is a 1-arity fn
   receiving the URL string and returning the synthesised :value."
  [fx-id url->value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub  (registrar/handler :fx :rf.http/managed-canned-success)
            url   (-> args :request :url)
            value (url->value url)]
        (stub frame-ctx (assoc args :value value))))))

(defn- reg-canned-failure!
  "Register an fx-id that delegates to :rf.http/managed-canned-failure."
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind kind :tags tags))))))

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
  (test-support/make-reset-runtime-fixture
    ;; EP-0002 (rf2-9o48ih): each test spins its OWN top-level frame via
    ;; `make-frame` (inside `with-new-frame`); opt out of the ambient
    ;; `:rf/default` scope so the new frame's `:on-create` drains
    ;; synchronously (top-level boot) rather than being treated as a
    ;; mid-cascade child-frame creation. In-body dispatches run inside the
    ;; `with-new-frame` scope, so they do not rely on the ambient frame.
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ============================================================================
;; TESTS
;; ============================================================================

;; EP-0002 (rf2-9o48ih): these tests model a TOP-LEVEL boot. The fixture
;; above opts out of the ambient `:rf/default` scope (`:ambient-frame nil`)
;; so each `make-frame`'s `:on-create` drains synchronously (rather than
;; being treated as a mid-cascade child-frame creation, rf2-cufbh) and the
;; post-boot state is observable, as a real top-level `make-frame` boot is.

(deftest boot-machine-progression
  (testing "happy path: boot machine traverses :configuring → :loading-deps → :hydrating → :ready and all slices land"
    (reg-canned-success-by-url! :boot.test/canned-boot-success payload-for)

    (with-new-frame [f (frame/make-anon-frame-record!
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-success}})]
      ;; The :on-create cofx fires :boot/initialise during make-frame,
      ;; which dispatches [:app/boot [:rf.machine/start]]. The synchronous
      ;; drain runs all four canned-success stubs to completion.
      (let [db    (rf/frame-state-value f)
            state (rf/compute-sub [:app.boot/state] db)]
        (assert (= :ready state)
                (str "expected boot machine state :ready, got " state))

        ;; Staging slots all populated.
        (let [staging (get-in db [:rf.db/app :boot/staging])]
          (assert (= test-config (:config staging)))
          (assert (= test-routes (:routes staging)))
          (assert (= test-flags  (:flags staging)))
          (assert (= test-user   (:user staging))))

        ;; Top-level slices hydrated from staging.
        (assert (= test-config (rf/compute-sub [:app/config] db)))
        (assert (= test-flags  (rf/compute-sub [:app/flags]  db)))
        (assert (= test-user   (rf/compute-sub [:app/user]   db)))
        (assert (= test-routes (rf/compute-sub [:app/routes] db)))))))

(deftest boot-dependency-resolution
  (testing "per-child :data fns thread spawn-spec identity; no cross-talk between siblings"
    (reg-canned-success-by-url! :boot.test/canned-boot-success payload-for)

    (with-new-frame [f (frame/make-anon-frame-record!
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-success}})]
      (let [db      (rf/frame-state-value f)
            staging (get-in db [:rf.db/app :boot/staging])]
        ;; Each staging-key holds the payload that came back from the
        ;; matching URL. Cross-talk (e.g. :flags staging holding the
        ;; routes payload) would mean the :spawn-all :data fns are
        ;; not threading identity correctly.
        (assert (contains? (:config staging) :api-base))
        (assert (sequential? (:routes staging)))
        (assert (contains? (:flags staging) :dark-mode?))
        (assert (contains? (:user staging) :username))
        ;; The boot machine's :data mirrors the staged values once
        ;; :enter-hydrating runs (so the snapshot is self-describing
        ;; for SSR / tools).
        (let [boot-data (get-in db [:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :data])]
          (assert (= test-config (:config boot-data)))
          (assert (= test-routes (:routes boot-data)))
          (assert (= test-flags  (:flags boot-data)))
          (assert (= test-user   (:user boot-data))))))))

(deftest boot-failure-path
  (testing "a failure during the parallel phase routes the boot to :failed and records the error"
    (reg-canned-failure! :boot.test/canned-boot-fail
                         :rf.http/http-5xx
                         {:status 500
                          :body   "boot dependency unreachable"})

    (with-new-frame [f (frame/make-anon-frame-record!
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-fail}})]
      (let [db    (rf/frame-state-value f)
            state (rf/compute-sub [:app.boot/state] db)]
        ;; Every child fails (the canned-failure stub is blanket); the
        ;; first failure routes the boot to :failed via :on-any-failed.
        (assert (= :failed state)
                (str "expected boot machine state :failed, got " state))
        (let [err (rf/compute-sub [:app.boot/error] db)]
          (assert (some? err)
                  "expected :app.boot/error to be populated on the failure path"))))))

;; ============================================================================
;; MACHINE :data SCHEMA BOUNDARY  (rf2-t5ky67 issue 2)
;; ============================================================================
;;
;; The singleton `:app/boot` machine attaches a top-level `:data-schema`
;; (`boot.schema/BootData`) that validates the snapshot's `:data` slot at the
;; `:where :machine-data` boundary (Spec 010 §Machine data schema). These
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

(deftest boot-data-schema-attached
  (testing "the :app/boot machine carries BootData on its :data-schema slot"
    (let [meta (machines/machine-meta :app/boot)]
      (is (some? meta) "machine-meta resolves the registered :app/boot machine")
      (is (= boot-schema/BootData (:data-schema meta))
          "the :data-schema round-trips as boot.schema/BootData")))
  (testing "BootData validates the :data slot only (rejects a malformed :config)"
    (is (true?  (schemas/validate-with-registered-fn
                  boot-schema/BootData
                  {:phase :configuring :config nil :flags nil
                   :user nil :routes nil :error nil}))
        "the initial all-nil :data conforms")
    (is (true?  (schemas/validate-with-registered-fn
                  boot-schema/BootData
                  {:phase :hydrating :config test-config :flags nil
                   :user nil :routes nil :error nil}))
        "a well-formed promoted :config conforms")
    (is (false? (schemas/validate-with-registered-fn
                  boot-schema/BootData
                  {:phase :loading-deps :config {:api-base "/api"} :flags nil
                   :user nil :routes nil :error nil}))
        "a :config missing required keys (:env/:build/:title) fails the data-slot schema")))

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
                      (frame/make-anon-frame-record!
                        {:on-create    [:boot/initialise]
                         :fx-overrides {:rf.http/managed
                                        :boot.test/canned-bad-config}})))]
      (try
        (is (<= 1 (count traces))
            "at least one :where :machine-data trace fires when the boot machine's :data goes malformed")
        (is (some #(= :app/boot (-> % :tags :machine-id)) traces)
            "a trace names the :app/boot machine")
        ;; `:recovery` rides the trace ENVELOPE, not :tags (rf2-twt7m) —
        ;; mirrors the :where :app-db projection.
        (let [boot-trace (some #(when (= :app/boot (-> % :tags :machine-id)) %) traces)]
          (is (= :no-recovery (:recovery boot-trace))))
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
        (with-new-frame [f (frame/make-anon-frame-record! {})]
          (rf/reg-app-schema [:config] {:schema [:maybe boot-schema/Config] :frame f})
          (rf/dispatch-sync [::write-bad-config] {:frame f}))
        (finally (rf/unregister-listener! :trace ::app)))
      (is (pos? (count @app-traces))
          "a malformed [:config] app-db slice fails at :where :app-db (slice schema validates app-db only)"))))

;; A tiny event used only by the test above to write a malformed [:config]
;; app-db slice, so the app-db slice schema's :where :app-db boundary is
;; exercised distinctly from the machine :data boundary.
(rf/reg-event ::write-bad-config
  (fn [{:keys [db]} _] {:db (assoc db :config {:api-base "/api"})}))   ;; missing required Config keys
