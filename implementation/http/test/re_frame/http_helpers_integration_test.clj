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
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.schemas :as schemas]
            [re-frame.registrar :as registrar]
            [re-frame.http :as rf.http]
            [re-frame.http-managed :as http-managed]
            ;; rf2-cdmle — canned-stub fxs gate on explicit test-support
            ;; require. This file uses :fx-overrides {:rf.http/managed
            ;; :rf.http/managed-canned-success/failure} below.
            [re-frame.http-test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---- per-test reset (mirrors http-managed-test) ---------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http-managed :reload)
  ;; rf2-cdmle — re-fire the test-support load-time registrations after
  ;; clear-all! / http-managed reload so the canned-stub fx ids are
  ;; available for the next test.
  (require 're-frame.http-test-support :reload)
  ((requiring-resolve 're-frame.machines/reset-timers!))
  (http-managed/clear-all-in-flight!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- await-reply!
  "Wait up to `timeout-ms` for `(pred db)` to be truthy against
  `(rf/get-frame-db :rf/default)`. Returns the final db on success;
  throws `:rf.test/poll-timeout` on timeout. Thin alias over
  `test-support/poll-until` (rf2-fun38) — preserves the per-file
  `db`-closing-arity shape that read sites here expect."
  ([pred] (await-reply! pred 5000))
  ([pred timeout-ms]
   (test-support/poll-until
     #(let [db (rf/get-frame-db :rf/default)] (when (pred db) db))
     {:timeout-ms timeout-ms :label "http-helpers reply"})))

;; ---- 1. (rf.http/get url args) dispatches through canned-success ----------

(deftest helper-get-routes-through-managed
  (testing "(rf.http/get ...) in :fx is indistinguishable from a hand-written :rf.http/managed entry"
    (rf/reg-event-fx :items/load
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          (case (:kind reply)
            :success {:db (assoc db :items (:value reply))}
            :failure {:db (assoc db :error (:failure reply))})
          {:fx [(rf.http/get "/api/items" {:decode :json})]})))
    (rf/dispatch-sync [:items/load {}]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (await-reply! #(some? (:items %)))]
      (is (= {:stubbed true} (:items db))))))

;; ---- 2. (rf.http/post url args) with explicit :on-success -----------------

(deftest helper-post-routes-with-explicit-on-success
  (testing "(rf.http/post ...) with explicit :on-success dispatches there"
    (rf/reg-event-fx :item/create
      (fn [_ _]
        {:fx [(rf.http/post "/api/items"
                            {:request    {:body {:title "new"}
                                          :request-content-type :json}
                             :on-success [:item/created]})]}))
    (rf/reg-event-db :item/created
      (fn [db [_ payload]]
        (assoc db :created payload)))
    (rf/dispatch-sync [:item/create]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
    (let [db (await-reply! #(some? (:created %)))]
      (is (= :success (get-in db [:created :kind]))))))

;; ---- 3. (rf.http/delete url args) with explicit :on-failure ---------------

(deftest helper-delete-routes-with-explicit-on-failure
  (testing "(rf.http/delete ...) with explicit :on-failure dispatches there on failure"
    (rf/reg-event-fx :item/delete
      (fn [_ _]
        {:fx [(rf.http/delete "/api/items/42"
                              {:on-failure [:item/delete-failed]})]}))
    (rf/reg-event-db :item/delete-failed
      (fn [db [_ payload]]
        (assoc db :delete-error payload)))
    (rf/dispatch-sync [:item/delete]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
    (let [db (await-reply! #(some? (:delete-error %)))]
      (is (= :failure (get-in db [:delete-error :kind])))
      (is (= :rf.http/transport (get-in db [:delete-error :failure :kind]))))))

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

;; ---- 5. (rf.http/get url) — minimal form, default reply addressing --------

(deftest helper-get-minimal-default-reply-addressing
  (testing "(rf.http/get url) — no extra args; reply lands back at originating handler"
    (rf/reg-event-fx :ping
      (fn [{:keys [db]} [_ msg]]
        (if-let [reply (:rf/reply msg)]
          {:db (assoc db :pong (:value reply))}
          {:fx [(rf.http/get "/api/ping")]})))
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
