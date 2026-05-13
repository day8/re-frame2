(ns re-frame.adapter.uix-http-managed-cljs-test
  "Adapter-parity port of `re-frame.http-managed-cljs-test` to the
  UIx adapter (rf2-ta4b5).

  Smoke for Spec 014 — `:rf.http/managed` under the UIx reactive
  substrate. The canned-stub fxs and `with-managed-request-stubs*`
  helper resolve under the UIx adapter the same way they do under
  Reagent — confirming that `reg-event-fx` + `:fx` orchestration +
  fx-overrides compose with the UIx late-bind hook stack.

  Parallel to:
    - implementation/adapters/reagent/test/re_frame/http_managed_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.schemas.malli]
            [re-frame.core :as rf]
            [re-frame.http-managed :as http-managed]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (fn [test-fn]
    (http-managed/clear-all-in-flight!)
    ((test-support/reset-runtime-fixture
       {:adapter uix-adapter/adapter})
      test-fn)
    (http-managed/clear-all-in-flight!)))

;; ---- 1. canned-success: default reply addressing --------------------------

(deftest canned-success-default-reply-addressing-uix
  (testing "the canned-success stub dispatches a default reply under the UIx adapter"
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

(deftest canned-failure-explicit-on-failure-uix
  (testing "explicit :on-failure routes the failure reply to the named handler under UIx"
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

(deftest canned-success-explicit-on-success-uix
  (testing "explicit :on-success routes the success reply to the named handler under UIx"
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

(deftest silenced-reply-on-success-nil-uix
  (testing "explicit :on-success nil swallows the reply silently under UIx"
    (let [seen (atom 0)]
      (rf/reg-event-fx :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed
                 {:request    {:url "/ping"}
                  :on-success nil}]]}))
      (rf/dispatch-sync [:ping]
                        {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
      (is (= 1 @seen) "no reply was dispatched when :on-success is nil"))))

;; ---- 5. with-managed-request-stubs* helper --------------------------------

(deftest with-managed-request-stubs-uix
  (testing "with-managed-request-stubs* installs a per-call fx under UIx"
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

;; ---- 6. with-managed-request-stubs* — failure mapping --------------------

(deftest with-managed-request-stubs-failure-uix
  (testing "with-managed-request-stubs* synthesises a failure reply when {:reply {:failure ...}} under UIx"
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

;; ---- 7. multi-frame reply isolation -------------------------------------

(deftest multi-frame-reply-isolation-uix
  (testing "managed requests issued from frame A reply into frame A's app-db under UIx"
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
      (is (nil? (:article (rf/get-frame-db :rf/default)))))))
