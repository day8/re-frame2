(ns re-frame.http-managed-cljs-test
  "CLJS smoke for Spec 014 — `:rf.http/managed` under the Reagent
  reactive substrate.

  The CLJS impl uses Fetch under the hood; this test covers the
  framework-shipped surfaces using the canned-stub fxs and the
  `with-managed-request-stubs` helper — no real network IO.

  Surfaces exercised:

  - `:rf.http/managed-canned-success` — synthesised success reply
  - `:rf.http/managed-canned-failure` — synthesised failure reply
  - default reply addressing (originator id with `:rf/reply` merged)
  - explicit `:on-success` target
  - explicit `:on-failure` target
  - silenced `:on-success nil`
  - decode-pipeline shapes (`:json`, `:text`, fn, Malli — only static
    shape exercised; the live transport runs through them when fetch is
    available, which the JVM smoke and the conformance fixtures cover
    end-to-end)
  - `:rf.http/decode-schemas` reflection metadata via `handler-meta`
  - `with-managed-request-stubs*` — install/run/uninstall

  Per Spec 014 §Implementation status — CLJS is the reference target;
  this smoke locks that the canned-stub fxs and the public test seam
  resolve under the Reagent adapter the same way they do on the JVM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ;; Per rf2-t0hq — the canonical CLJS opt-in for Malli
            ;; validation. Publishes :schemas/malli-validate /
            ;; :schemas/malli-explain into the late-bind hook table so
            ;; the default validator delegates to Malli on CLJS. The
            ;; http-managed CLJS smoke exercises `:rf.http/decode-
            ;; schemas` shapes that route through the registered
            ;; validator; without this require they'd soft-pass.
            [re-frame.schemas.malli]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) gate on explicit
            ;; test-support require. This file uses :fx-overrides into
            ;; both fx ids throughout, so we opt in here.
            [re-frame.http.test-support]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). The
;; framework-shipped :rf.http/managed family registers at ns-load and
;; survives the snapshot; per-test reg-event / reg-sub registrations roll
;; back on the way out.
(use-fixtures :each
  (fn [test-fn]
    ;; Drop any in-flight registry leaks between tests.
    (http-managed/clear-all-in-flight!)
    ((test-support/make-reset-runtime-fixture
       {:adapter reagent-adapter/adapter})
      test-fn)
    (http-managed/clear-all-in-flight!)))

;; ---- 1. canned-success: default reply addressing --------------------------

(deftest canned-success-default-reply-addressing-cljs
  (testing "the canned-success stub dispatches a default reply (originating event-id with :rf/reply)"
    (rf/reg-event :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:status reply)
            :ok    {:db {:article (:value reply)}}
            :error {:db {:error (:error reply)}})
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:article/load {:slug "hello"}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= {:stubbed true} (:article db))
          "default-reply addressing routed the synthesised reply back to :article/load"))))

;; ---- 2. canned-failure: explicit on-failure -------------------------------

(deftest canned-failure-explicit-on-failure-cljs
  (testing "explicit :on-failure routes the failure reply to the named handler"
    (rf/reg-event :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/auth/login"}
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event :auth/login-error
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :auth-error payload)}))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= :error (get-in db [:auth-error :status])))
      (is (= :rf.http/transport (get-in db [:auth-error :error :kind]))
          "default canned-failure classifies as :rf.http/transport under :error"))))

;; ---- 3. canned-success: explicit on-success -------------------------------

(deftest canned-success-explicit-on-success-cljs
  (testing "explicit :on-success routes the success reply to the named handler"
    (rf/reg-event :article/load
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url "/articles/hello"}
                :on-success [:article/loaded]}]]}))
    (rf/reg-event :article/loaded
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :article payload)}))
    (rf/dispatch-sync [:article/load]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (rf/app-db-value :rf/default)]
      (is (= :ok (get-in db [:article :status])))
      (is (= {:stubbed true} (get-in db [:article :value]))))))

;; ---- 4. silenced reply ----------------------------------------------------

(deftest silenced-reply-on-success-nil-cljs
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed
                 {:request    {:url "/ping"}
                  :on-success nil}]]}))
      (rf/dispatch-sync [:ping]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      ;; Only the initial dispatch fired :ping; no reply re-entered.
      (is (= 1 @seen) "no reply was dispatched when :on-success is nil"))))

;; ---- 5. decode reflection metadata ----------------------------------------

(deftest decode-reflection-metadata-cljs
  (testing ":rf.http/decode-schemas declared on the handler is queryable via handler-meta"
    (rf/reg-event :article/load
      {:doc                    "Load an article."
       :rf.http/decode-schemas [::ArticleResponse ::ArticleSummary]}
      (fn [_ _] {}))
    (let [m (rf/handler-meta :event :article/load)]
      (is (= [::ArticleResponse ::ArticleSummary]
             (:rf.http/decode-schemas m))
          "decode-schemas metadata round-trips through the registrar"))))

;; ---- 6. with-managed-request-stubs* helper --------------------------------

(deftest with-managed-request-stubs-cljs
  (testing "rf2-rzqan — with-managed-request-stubs* routes [method url] → reply
            with NO per-call :fx-overrides (the helper installs the
            :rf.http/managed override for the thunk's dynamic extent)"
    (rf/reg-event :articles/list
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs*
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      (fn []
        ;; NO manual :fx-overrides — the documented auto-routing form.
        (rf/dispatch-sync [:articles/list])
        (let [db (rf/app-db-value :rf/default)]
          (is (= :ok (get-in db [:result :status])))
          (is (= [:hello :world] (get-in db [:result :value]))))))))

;; ---- 6a. rf2-rzqan — bare thunk INTERCEPTS, never reaching the real fx ----
;;
;; CLJS counterpart of the JVM interception regression. The documented
;; `with-managed-request-stubs*` form must route `:rf.http/managed` through
;; the stub by ITSELF; pre-fix the thunk's bare dispatch reached the real
;; production Fetch transport. We shadow `:rf.http/managed` with a sentinel
;; — reaching it proves the override was absent.

(deftest with-managed-request-stubs-intercepts-without-manual-override-cljs-rf2-rzqan
  (testing "rf2-rzqan — inside with-managed-request-stubs*, a plain dispatch-sync
            (NO per-call :fx-overrides) is intercepted by the stub and the real
            :rf.http/managed fx slot is NEVER invoked"
    (let [real-fx-invoked? (atom false)]
      (rf/reg-fx :rf.http/managed
                 (fn [_frame-ctx _args] (reset! real-fx-invoked? true) nil))
      (rf/reg-event :rzqan/load
        (fn [_ [_ msg]]
          (if-let [reply (:rf/reply msg)]
            {:db {:result reply}}
            {:fx [[:rf.http/managed
                   {:request {:method :get :url "/rzqan"}
                    :decode  :json}]]})))
      (rf/with-managed-request-stubs*
        {[:get "/rzqan"] {:reply {:ok {:stubbed true}}}}
        (fn []
          (rf/dispatch-sync [:rzqan/load])
          (let [db (rf/app-db-value :rf/default)]
            (is (= :ok (get-in db [:result :status]))
                "the stubbed reply landed via the route-map stub")
            (is (= {:stubbed true} (get-in db [:result :value])))
            (is (false? @real-fx-invoked?)
                "the real :rf.http/managed fx was NEVER invoked — the helper
                 intercepted (pre-fix: this fired the real Fetch transport)")))))))

;; ---- 7. with-managed-request-stubs* — failure mapping --------------------

(deftest with-managed-request-stubs-failure-cljs
  (testing "with-managed-request-stubs* synthesises a failure reply when {:reply {:failure ...}}"
    (rf/reg-event :articles/list
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs*
      {[:get "/articles"] {:reply {:failure {:kind   :rf.http/http-4xx
                                             :status 404}}}}
      (fn []
        ;; Auto-routing — no manual :fx-overrides (rf2-rzqan).
        (rf/dispatch-sync [:articles/list])
        (let [db (rf/app-db-value :rf/default)]
          (is (= :error (get-in db [:result :status])))
          (is (= :rf.http/http-4xx (get-in db [:result :error :kind])))
          (is (= 404 (get-in db [:result :error :status]))))))))

;; ---- 8. unmatched-stub falls through to a transport failure --------------

(deftest with-managed-request-stubs-unmatched-cljs
  (testing "an unmatched [method url] under stubs synthesises a :rf.http/transport failure"
    (rf/reg-event :unmatched/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/never"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs*
      ;; Configure stubs that do NOT match the request URL.
      {[:get "/articles"] {:reply {:ok []}}}
      (fn []
        ;; Auto-routing — no manual :fx-overrides (rf2-rzqan). The unmatched
        ;; route still routes THROUGH the stub (which then synthesises the
        ;; no-match transport failure), never reaching the real client.
        (rf/dispatch-sync [:unmatched/load])
        (let [db (rf/app-db-value :rf/default)]
          (is (= :error (get-in db [:result :status])))
          (is (= :rf.http/transport (get-in db [:result :error :kind]))))))))

;; ---- 9. canned-failure: explicit :kind / :tags shape ---------------------

(deftest canned-failure-custom-kind-cljs
  (testing ":rf.http/managed-canned-failure honours :kind and :tags args"
    (rf/reg-event :flaky/load
      (fn [_ _]
        {:fx [[:rf.http/managed-canned-failure
               {:on-failure [:flaky/load-error]
                :kind       :rf.http/http-5xx
                :tags       {:status      503
                             :status-text "Service Unavailable"
                             :body        {:err true}
                             :headers     {}}}]]}))
    (rf/reg-event :flaky/load-error
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :error payload)}))
    (rf/dispatch-sync [:flaky/load])
    (let [db (rf/app-db-value :rf/default)]
      (is (= :error (get-in db [:error :status])))
      (is (= :rf.http/http-5xx (get-in db [:error :error :kind])))
      (is (= 503 (get-in db [:error :error :status]))))))

;; ---- 10. multi-frame reply isolation -------------------------------------

(deftest multi-frame-reply-isolation-cljs
  (testing "managed requests issued from frame A reply into frame A's app-db"
    (rf/reg-event :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:article (:value reply)}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (let [left  (frame/make-anon-frame-record! {:doc "left"
                                :fx-overrides
                                {:rf.http/managed :rf.http/managed-canned-success}})
          right (frame/make-anon-frame-record! {:doc "right"
                                :fx-overrides
                                {:rf.http/managed :rf.http/managed-canned-success}})]
      (rf/dispatch-sync [:article/load] {:frame left})
      (rf/dispatch-sync [:article/load] {:frame right})
      (is (= {:stubbed true} (:article (rf/app-db-value left))))
      (is (= {:stubbed true} (:article (rf/app-db-value right))))
      ;; The default frame stays empty — no cross-frame leakage.
      (is (nil? (:article (rf/app-db-value :rf/default)))))))
