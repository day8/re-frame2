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
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            ;; The canned-stub helpers below resolve
            ;; :rf.http/managed-canned-success / failure via registrar
            ;; lookup. Those fx ids register from
            ;; re-frame.http-test-support, NOT re-frame.http-managed.
            ;; boot.core already requires re-frame.http-test-support via
            ;; the boot.schema / boot.boot graph; require explicitly here
            ;; too so this test ns is self-sufficient if a future refactor
            ;; unhooks the transitive load.
            [re-frame.http-test-support]
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
    {:adapter reagent-adapter/adapter}))

;; ============================================================================
;; TESTS
;; ============================================================================

(deftest boot-machine-progression
  (testing "happy path: boot machine traverses :configuring → :loading-deps → :hydrating → :ready and all slices land"
    (reg-canned-success-by-url! :boot.test/canned-boot-success payload-for)

    (with-new-frame [f (rf/make-frame
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-success}})]
      ;; The :on-create cofx fires :boot/initialise during make-frame,
      ;; which dispatches [:app/boot [:rf/start]]. The synchronous
      ;; drain runs all four canned-success stubs to completion.
      (let [db    (rf/app-db-value f)
            state (rf/compute-sub [:app.boot/state] db)]
        (assert (= :ready state)
                (str "expected boot machine state :ready, got " state))

        ;; Staging slots all populated.
        (let [staging (:boot/staging db)]
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

    (with-new-frame [f (rf/make-frame
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-success}})]
      (let [db      (rf/app-db-value f)
            staging (:boot/staging db)]
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
        (let [boot-data (get-in db [:rf/runtime :machines :snapshots :app/boot :data])]
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

    (with-new-frame [f (rf/make-frame
                         {:on-create    [:boot/initialise]
                          :fx-overrides {:rf.http/managed
                                         :boot.test/canned-boot-fail}})]
      (let [db    (rf/app-db-value f)
            state (rf/compute-sub [:app.boot/state] db)]
        ;; Every child fails (the canned-failure stub is blanket); the
        ;; first failure routes the boot to :failed via :on-any-failed.
        (assert (= :failed state)
                (str "expected boot machine state :failed, got " state))
        (let [err (rf/compute-sub [:app.boot/error] db)]
          (assert (some? err)
                  "expected :app.boot/error to be populated on the failure path"))))))
