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
            [re-frame.http-managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) gate on explicit
            ;; test-support require. This file uses :fx-overrides into
            ;; both fx ids throughout, so we opt in here.
            [re-frame.http-test-support]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). The
;; framework-shipped :rf.http/managed family registers at ns-load and
;; survives the snapshot; per-test reg-event-* / reg-sub-* roll back
;; on the way out.
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
    (rf/reg-event-fx :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:kind reply)
            :success {:db {:article (:value reply)}}
            :failure {:db {:error (:failure reply)}})
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:article/load {:slug "hello"}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (rf/get-frame-db :rf/default)]
      (is (= {:stubbed true} (:article db))
          "default-reply addressing routed the synthesised reply back to :article/load"))))

;; ---- 2. canned-failure: explicit on-failure -------------------------------

(deftest canned-failure-explicit-on-failure-cljs
  (testing "explicit :on-failure routes the failure reply to the named handler"
    (rf/reg-event-fx :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/auth/login"}
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event-db :auth/login-error
      (fn [db [_ payload]]
        (assoc db :auth-error payload)))
    (rf/dispatch-sync [:auth/login]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (rf/get-frame-db :rf/default)]
      (is (= :failure (get-in db [:auth-error :kind])))
      (is (= :rf.http/transport (get-in db [:auth-error :failure :kind]))
          "default canned-failure :kind classifies as :rf.http/transport"))))

;; ---- 3. canned-success: explicit on-success -------------------------------

(deftest canned-success-explicit-on-success-cljs
  (testing "explicit :on-success routes the success reply to the named handler"
    (rf/reg-event-fx :article/load
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url "/articles/hello"}
                :on-success [:article/loaded]}]]}))
    (rf/reg-event-db :article/loaded
      (fn [db [_ payload]]
        (assoc db :article payload)))
    (rf/dispatch-sync [:article/load]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (rf/get-frame-db :rf/default)]
      (is (= :success (get-in db [:article :kind])))
      (is (= {:stubbed true} (get-in db [:article :value]))))))

;; ---- 4. silenced reply ----------------------------------------------------

(deftest silenced-reply-on-success-nil-cljs
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event-fx :ping
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
    (rf/reg-event-fx :article/load
      {:doc                    "Load an article."
       :rf.http/decode-schemas [::ArticleResponse ::ArticleSummary]}
      (fn [_ _] {}))
    (let [m (rf/handler-meta :event :article/load)]
      (is (= [::ArticleResponse ::ArticleSummary]
             (:rf.http/decode-schemas m))
          "decode-schemas metadata round-trips through the registrar"))))

;; ---- 6. with-managed-request-stubs* helper --------------------------------

(deftest with-managed-request-stubs-cljs
  (testing "with-managed-request-stubs* installs a per-call fx that consults [method url] → reply"
    (rf/reg-event-fx :articles/list
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:result reply}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles"}
                  :decode  :json}]]})))
    (rf/with-managed-request-stubs*
      {[:get "/articles"] {:reply {:ok [:hello :world]}}}
      (fn []
        (rf/dispatch-sync [:articles/list]
                          {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
        (let [db (rf/get-frame-db :rf/default)]
          (is (= :success (get-in db [:result :kind])))
          (is (= [:hello :world] (get-in db [:result :value]))))))))

;; ---- 7. with-managed-request-stubs* — failure mapping --------------------

(deftest with-managed-request-stubs-failure-cljs
  (testing "with-managed-request-stubs* synthesises a failure reply when {:reply {:failure ...}}"
    (rf/reg-event-fx :articles/list
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
        (rf/dispatch-sync [:articles/list]
                          {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
        (let [db (rf/get-frame-db :rf/default)]
          (is (= :failure (get-in db [:result :kind])))
          (is (= :rf.http/http-4xx (get-in db [:result :failure :kind])))
          (is (= 404 (get-in db [:result :failure :status]))))))))

;; ---- 8. unmatched-stub falls through to a transport failure --------------

(deftest with-managed-request-stubs-unmatched-cljs
  (testing "an unmatched [method url] under stubs synthesises a :rf.http/transport failure"
    (rf/reg-event-fx :unmatched/load
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
        (rf/dispatch-sync [:unmatched/load]
                          {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
        (let [db (rf/get-frame-db :rf/default)]
          (is (= :failure (get-in db [:result :kind])))
          (is (= :rf.http/transport (get-in db [:result :failure :kind]))))))))

;; ---- 9. canned-failure: explicit :kind / :tags shape ---------------------

(deftest canned-failure-custom-kind-cljs
  (testing ":rf.http/managed-canned-failure honours :kind and :tags args"
    (rf/reg-event-fx :flaky/load
      (fn [_ _]
        {:fx [[:rf.http/managed-canned-failure
               {:on-failure [:flaky/load-error]
                :kind       :rf.http/http-5xx
                :tags       {:status      503
                             :status-text "Service Unavailable"
                             :body        {:err true}
                             :headers     {}}}]]}))
    (rf/reg-event-db :flaky/load-error
      (fn [db [_ payload]]
        (assoc db :error payload)))
    (rf/dispatch-sync [:flaky/load])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= :failure (get-in db [:error :kind])))
      (is (= :rf.http/http-5xx (get-in db [:error :failure :kind])))
      (is (= 503 (get-in db [:error :failure :status]))))))

;; ---- 10. multi-frame reply isolation -------------------------------------

(deftest multi-frame-reply-isolation-cljs
  (testing "managed requests issued from frame A reply into frame A's app-db"
    (rf/reg-event-fx :article/load
      (fn [_ [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db {:article (:value reply)}}
          {:fx [[:rf.http/managed
                 {:request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (let [left  (rf/make-frame {:doc "left"
                                :fx-overrides
                                {:rf.http/managed :rf.http/managed-canned-success}})
          right (rf/make-frame {:doc "right"
                                :fx-overrides
                                {:rf.http/managed :rf.http/managed-canned-success}})]
      (rf/dispatch-sync [:article/load] {:frame left})
      (rf/dispatch-sync [:article/load] {:frame right})
      (is (= {:stubbed true} (:article (rf/get-frame-db left))))
      (is (= {:stubbed true} (:article (rf/get-frame-db right))))
      ;; The default frame stays empty — no cross-frame leakage.
      (is (nil? (:article (rf/get-frame-db :rf/default)))))))
