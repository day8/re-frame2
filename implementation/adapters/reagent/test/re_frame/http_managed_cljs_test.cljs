(ns re-frame.http-managed-cljs-test
  "CLJS smoke for Spec 014 — `:rf.http/managed` under the Reagent
  reactive substrate, answered by a stub transport
  (`re-frame.http.stub-transport`): the real pipeline runs, and nothing
  reaches the network.

  Surfaces exercised:

  - unified `:reply-to` addressing (envelope appended as the last arg)
  - explicit `:on-success` and `:on-failure` targets
  - a silenced reply (`:on-success nil` / `:on-failure nil`)
  - `with-transport` answering a frame made before the scope, including
    one that sealed an image generation then
  - 4xx / 5xx statuses classified from the stub's response
  - an unmatched route failing as `:rf.http/transport`

  Per Spec 014 §Implementation status — CLJS is the reference target;
  this smoke locks that a stub transport answers under the Reagent adapter
  the same way it does on the JVM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Snapshot/restore the registrar around each test. The
;; framework-shipped :rf.http/managed family registers at ns-load and
;; survives the snapshot; per-test reg-event / reg-sub registrations roll
;; back on the way out.
(use-fixtures :each
  (fn [test-fn]
    ;; Drop any in-flight registry leaks between tests.
    (rf.http.managed/clear-all-in-flight!)
    ((rf.test-support/make-reset-runtime-fixture
       {:adapter rf.adapter.reagent/adapter})
      test-fn)
    (rf.http.managed/clear-all-in-flight!)))

(defn- answering
  "Run `thunk` with every managed request answered from `routes`. A route
  answers inline, so each reply has landed when the issuing `dispatch-sync`
  returns."
  [routes thunk]
  (rf.http.stub-transport/with-transport
    (rf.http.stub-transport/route-transport routes)
    thunk))

;; ---- 1. unified :reply-to addressing ---------------------------------------

(deftest reply-to-addressing-cljs
  (testing "the reply is dispatched to the unified :reply-to target"
    (rf/reg-event :article/load
      (fn [_ [_ msg reply]]
        (if reply
          (case (:status reply)
            :ok    {:db {:article (:value reply)}}
            :error {:db {:error (:error reply)}})
          {:fx [[:rf.http/managed
                 {:reply-to [:article/load msg] :request {:method :get :url "/articles/hello"}
                  :decode  :json}]]})))
    (answering {[:get "/articles/hello"] {:body {:slug "hello"}}}
      #(rf/dispatch-sync [:article/load {:slug "hello"}]))
    (is (= {:slug "hello"} (:article (rf/app-db-value :rf/default)))
        ":reply-to routed the decoded reply back to :article/load")))

;; ---- 2. explicit on-failure -------------------------------------------------

(deftest explicit-on-failure-cljs
  (testing "explicit :on-failure routes the failure reply to the named handler"
    (rf/reg-event :auth/login
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :post :url "/auth/login"}
                :on-success nil
                :on-failure [:auth/login-error]}]]}))
    (rf/reg-event :auth/login-error
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :auth-error payload)}))
    (answering {[:post "/auth/login"] {:failure {:kind :rf.http/transport}}}
      #(rf/dispatch-sync [:auth/login]))
    (let [db (rf/app-db-value :rf/default)]
      (is (= [:error :rf.http/transport]
             [(get-in db [:auth-error :status]) (get-in db [:auth-error :error :kind])])
          "a network failure classifies as :rf.http/transport under :error"))))

;; ---- 3. explicit on-success -------------------------------------------------

(deftest explicit-on-success-cljs
  (testing "explicit :on-success routes the success reply to the named handler"
    (rf/reg-event :article/load
      (fn [_ _]
        {:fx [[:rf.http/managed
               {:request    {:method :get :url "/articles/hello"}
                :decode     :json
                :on-success [:article/loaded]
                :on-failure nil}]]}))
    (rf/reg-event :article/loaded
      (fn [{:keys [db]} [_ payload]]
        {:db (assoc db :article payload)}))
    (answering {[:get "/articles/hello"] {:body {:id 1}}}
      #(rf/dispatch-sync [:article/load]))
    (is (= {:status :ok :value {:id 1}}
           (select-keys (:article (rf/app-db-value :rf/default)) [:status :value])))))

;; ---- 4. silenced reply ------------------------------------------------------

(deftest silenced-reply-on-success-nil-cljs
  (testing "explicit :on-success nil swallows the reply silently"
    (let [seen (atom 0)]
      (rf/reg-event :ping
        (fn [_ _]
          (swap! seen inc)
          {:fx [[:rf.http/managed
                 {:request    {:url "/ping"}
                  :on-success nil
                  :on-failure nil}]]}))
      (answering {[:get "/ping"] {:body {}}}
        #(rf/dispatch-sync [:ping]))
      ;; Only the initial dispatch fired :ping; no reply re-entered.
      (is (= 1 @seen) "no reply was dispatched when :on-success is nil"))))

;; ---- 5. a frame made before the scope ---------------------------------------
;;
;; The transport is found when the request issues, below `:rf.http/managed`,
;; so a frame made before the scope is answered by it whether it resolves the
;; registration store live (`make-frame {}`) or sealed an image generation
;; when it was made. The dispatch targets the frame VALUE, so the frame's own
;; resolution path is the one exercised.

(deftest a-frame-made-before-the-scope-is-answered-cljs
  (rf/reg-event :bxc8kf/load
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :result reply)}
        {:fx [[:rf.http/managed
               {:reply-to [:bxc8kf/load msg] :request {:method :get :url "/x"}
                :decode  :json}]]})))
  (doseq [[label f] [["make-frame {}" (rf/make-frame {})]
                     ["a frame with :images"
                      (rf/make-frame {:images [(rf/image {:select-ns {:include ["re-frame.http-managed-cljs-test"]}})]})]]]
    (testing label
      (answering {[:get "/x"] {:body {:n 1}}}
        #(rf/dispatch-sync [:bxc8kf/load] {:frame f}))
      (is (= [:ok {:n 1}]
             ((juxt :status :value) (:result (rf/app-db-value f))))))))

;; ---- 6. statuses outside 2xx ------------------------------------------------

(deftest error-statuses-are-classified-cljs
  (testing "a 4xx and a 5xx answer classify as :rf.http/http-4xx / -5xx"
    (rf/reg-event :articles/list
      (fn [{:keys [db]} [_ url reply]]
        (if reply
          {:db (assoc-in db [:result url] reply)}
          {:fx [[:rf.http/managed
                 {:reply-to [:articles/list url] :request {:method :get :url url}
                  :decode  :json}]]})))
    (answering {[:get "/missing"] {:status 404}
                [:get "/busy"]    {:status 503 :status-text "Service Unavailable" :body {:err true}}}
      #(doseq [url ["/missing" "/busy"]] (rf/dispatch-sync [:articles/list url])))
    (let [classified (fn [reply] [(:status reply) (get-in reply [:error :kind]) (get-in reply [:error :status])])
          result     (:result (rf/app-db-value :rf/default))]
      (is (= {"/missing" [:error :rf.http/http-4xx 404]
              "/busy"    [:error :rf.http/http-5xx 503]}
             (into {} (map (fn [[url reply]] [url (classified reply)])) result))))))

;; ---- 7. an unmatched route is a transport failure ---------------------------

(deftest an-unmatched-route-fails-as-transport-cljs
  (testing "a request no route matches fails as :rf.http/transport"
    (rf/reg-event :unmatched/load
      (fn [_ [_ msg reply]]
        (if reply
          {:db {:result reply}}
          {:fx [[:rf.http/managed
                 {:reply-to [:unmatched/load msg] :request {:method :get :url "/never"}
                  :decode  :json}]]})))
    (answering {[:get "/articles"] {:body []}}
      #(rf/dispatch-sync [:unmatched/load]))
    (let [db (rf/app-db-value :rf/default)]
      (is (= [:error :rf.http/transport]
             [(get-in db [:result :status]) (get-in db [:result :error :kind])])))))
