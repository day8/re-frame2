(ns re-frame.http-helpers-integration-test
  "Integration tests for the `re-frame.http` call-site helpers (rf2-pf4k).

  These exercise the helpers through the actual dispatch path —
  `(rf.http/get ...)` inside an `:fx` vector returned by an
  event-fx handler, dispatched through `rf/dispatch-sync` with a
  canned-stub `:fx-overrides`, and assert the reply lands as
  expected. Companion to `re-frame.http-test` (pure-fn shape tests)
  and `re-frame.http-managed-test` (fx contract end-to-end)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http :as rf.http]
            [re-frame.http.managed :as rf.http.managed]
            ;; rf2-cdmle — canned-stub fxs gate on explicit test-support
            ;; require. This file uses :fx-overrides {:rf.http/managed
            ;; :rf.http/managed-canned-success/failure} below.
            [re-frame.http.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---- per-test reset (mirrors http-managed-test) ---------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- await-reply!
  "Wait up to `timeout-ms` for `(pred db)` to be truthy against
  `(rf/app-db-value :rf/default)`. Returns the final db on success;
  throws `ex-info` carrying `:rf.error/id`
  `:rf.error/poll-until-timeout` on timeout. Thin alias over
  `test-support/poll-until` (rf2-fun38) — preserves the per-file
  `db`-closing-arity shape that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until
     #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-helpers reply"})))

;; ---- 1. (rf.http/get url args) dispatches through canned-success ----------

(deftest helper-get-routes-through-managed
  (testing "(rf.http/get ...) in :fx is indistinguishable from a hand-written :rf.http/managed entry"
    (rf/reg-event :items/load
      (fn [{:keys [db]} [_ msg reply]]
        (if reply
          (case (:status reply)
            :ok    {:db (assoc db :items (:value reply))}
            :error {:db (assoc db :error (:error reply))})
          {:fx [(rf.http/get "/api/items" {:decode :json :reply-to [:items/load msg]})]})))
    (rf/dispatch-sync [:items/load {}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (await-reply! #(some? (:items %)))]
      (is (= {:stubbed true} (:items db))))))

;; ---- 2. (rf.http/post url args) with explicit :on-success -----------------

(deftest helper-post-routes-with-explicit-on-success
  (testing "(rf.http/post ...) with explicit :on-success dispatches there"
    (rf/reg-event :item/create
      (fn [_ _]
        {:fx [(rf.http/post "/api/items"
                            {:request    {:body {:title "new"}
                                          :request-content-type :json}
                             :on-success [:item/created]})]}))
    (rf/reg-event :item/created
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :created payload)}))
    (rf/dispatch-sync [:item/create]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (await-reply! #(some? (:created %)))]
      (is (= :ok (get-in db [:created :status]))))))

;; ---- 3. (rf.http/delete url args) with explicit :on-failure ---------------

(deftest helper-delete-routes-with-explicit-on-failure
  (testing "(rf.http/delete ...) with explicit :on-failure dispatches there on failure"
    (rf/reg-event :item/delete
      (fn [_ _]
        {:fx [(rf.http/delete "/api/items/42"
                              {:on-failure [:item/delete-failed]})]}))
    (rf/reg-event :item/delete-failed
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :delete-error payload)}))
    (rf/dispatch-sync [:item/delete]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (await-reply! #(some? (:delete-error %)))]
      (is (= :error (get-in db [:delete-error :status])))
      (is (= :rf.http/transport (get-in db [:delete-error :error :kind]))))))

;; ---- 4. (rf.http/put url args) with :request-id (abort surface) -----------

(deftest helper-put-carries-request-id
  (testing "(rf.http/put ...) with :request-id flows it through to the args map"
    ;; Hand-write the expected fx vector, then assert the helper produces it.
    ;; This is a pure-fn assertion that supplements the integration tests above
    ;; (the request-id end-to-end behaviour is covered by http-managed-test).
    (is (= [:rf.http/managed
            {:request    {:method :put
                          :url    "/api/items/42"
                          :body   {:title "updated"}
                          :request-content-type :json}
             :request-id [:item :update 42]}]
           (rf.http/put "/api/items/42"
                        {:request    {:body {:title "updated"}
                                      :request-content-type :json}
                         :request-id [:item :update 42]})))))

;; ---- 5. (rf.http/get url args) — minimal form, unified :reply-to ----------

(deftest helper-get-minimal-reply-to
  (testing "(rf.http/get url {:reply-to …}) — the reply lands back at the originating handler"
    (rf/reg-event :ping
      (fn [{:keys [db]} [_ msg reply]]
        (if reply
          {:db (assoc db :pong (:value reply))}
          {:fx [(rf.http/get "/api/ping" {:reply-to [:ping msg]})]})))
    (rf/dispatch-sync [:ping]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (await-reply! #(some? (:pong %)))]
      (is (= {:stubbed true} (:pong db))))))

;; ---- 6. helper + retry policy passes through ------------------------------

(deftest helper-with-retry-policy
  (testing "(rf.http/get ...) carries :retry config through to the args map"
    (let [retry {:on           #{:rf.http/transport :rf.http/http-5xx}
                 :max-attempts 4
                 :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}]
      (is (= [:rf.http/managed
              {:request {:method :get :url "/api/items"}
               :retry   retry
               :decode  :json}]
             (rf.http/get "/api/items" {:retry retry :decode :json}))))))
