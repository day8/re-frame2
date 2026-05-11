(ns boot.boot-test
  "Headless tests for boot.boot — the Pattern-Boot state machine.

   Each test spins a fresh frame via `make-frame`, fires the
   `:boot/initialise` event, and asserts the boot machine and the
   four loaded slices end up in the expected shape. Managed-HTTP is
   stubbed via `:fx-overrides` routing every `:rf.http/managed`
   call to a per-URL canned-success / canned-failure wrapper that
   delegates to the framework-shipped stubs (Spec 014 §Testing).

   Coverage:
     - machine-progression-test  — the boot machine traverses
       :configuring → :loading-deps → :hydrating → :ready, and all
       four loaded slices land in app-db.
     - dependency-resolution-test — the per-child :data fns thread
       the spawn-spec identity correctly so each child writes its
       payload to the matching staging key (no cross-talk).
     - failure-path-test         — a failure during the parallel
       phase routes the boot to :failed and records the error in
       :data."
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [boot.boot])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

;; ============================================================================
;; PER-URL CANNED STUBS
;; ============================================================================
;;
;; The realworld test-helpers provide reg-canned-success-by-url! which
;; we reproduce locally so the boot example's tests don't have to
;; require the realworld ns just for one helper.

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
  [{:id :route/home  :path "/"}
   {:id :route/about :path "/about"}])

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
;; TESTS
;; ============================================================================

(defn machine-progression-test
  "Drives the boot through the happy path. Asserts the final state
   is :ready, every staging slot is populated, and every top-level
   slice landed in app-db."
  []
  (reg-canned-success-by-url! :rf.http/managed.boot-success payload-for)

  (with-frame [f (rf/make-frame
                   {:on-create    [:boot/initialise]
                    :fx-overrides {:rf.http/managed
                                   :rf.http/managed.boot-success}})]
    ;; The :on-create cofx fires :boot/initialise during make-frame,
    ;; which dispatches [:app/boot [:rf/start]]. The synchronous
    ;; drain runs all four canned-success stubs to completion.
    (let [db    (rf/get-frame-db f)
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
      (assert (= test-routes (rf/compute-sub [:app/routes] db))))))

(defn dependency-resolution-test
  "Asserts the per-child :data fns thread the spawn-spec identity
   correctly so each child writes its payload to the matching
   staging key (no cross-talk between siblings)."
  []
  (reg-canned-success-by-url! :rf.http/managed.boot-success payload-for)

  (with-frame [f (rf/make-frame
                   {:on-create    [:boot/initialise]
                    :fx-overrides {:rf.http/managed
                                   :rf.http/managed.boot-success}})]
    (let [db      (rf/get-frame-db f)
          staging (:boot/staging db)]
      ;; Each staging-key holds the payload that came back from the
      ;; matching URL. Cross-talk (e.g. :flags staging holding the
      ;; routes payload) would mean the :invoke-all :data fns are
      ;; not threading identity correctly.
      (assert (contains? (:config staging) :api-base))
      (assert (sequential? (:routes staging)))
      (assert (contains? (:flags staging) :dark-mode?))
      (assert (contains? (:user staging) :username))
      ;; The boot machine's :data mirrors the staged values once
      ;; :enter-hydrating runs (so the snapshot is self-describing
      ;; for SSR / tools).
      (let [boot-data (get-in db [:rf/machines :app/boot :data])]
        (assert (= test-config (:config boot-data)))
        (assert (= test-routes (:routes boot-data)))
        (assert (= test-flags  (:flags boot-data)))
        (assert (= test-user   (:user boot-data)))))))

(defn failure-path-test
  "A failure during the parallel phase routes the boot machine to
   :failed and records the error in :data."
  []
  (reg-canned-failure! :rf.http/managed.boot-fail
                       :rf.http/http-5xx
                       {:status 500
                        :body   "boot dependency unreachable"})

  (with-frame [f (rf/make-frame
                   {:on-create    [:boot/initialise]
                    :fx-overrides {:rf.http/managed
                                   :rf.http/managed.boot-fail}})]
    (let [db    (rf/get-frame-db f)
          state (rf/compute-sub [:app.boot/state] db)]
      ;; Every child fails (the canned-failure stub is blanket); the
      ;; first failure routes the boot to :failed via :on-any-failed.
      (assert (= :failed state)
              (str "expected boot machine state :failed, got " state))
      (let [err (rf/compute-sub [:app.boot/error] db)]
        (assert (some? err)
                "expected :app.boot/error to be populated on the failure path")))))
