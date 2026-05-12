(ns re-frame.http-test
  "Unit tests for the `re-frame.http` call-site helpers (rf2-pf4k).

  These are pure-fn tests — they assert the helpers synthesise the
  canonical `[:rf.http/managed args-map]` fx-vector with the right
  shape. The `re-frame.http-managed-test` suite exercises the
  fx-side contract (transport, decode, retry, abort) end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http :as rf.http]))

;; ---- 1. minimal form -------------------------------------------------------

(deftest minimal-get
  (testing "(get url) — just the URL, no extra args"
    (is (= [:rf.http/managed
            {:request {:method :get :url "/api/items"}}]
           (rf.http/get "/api/items")))))

(deftest minimal-post
  (testing "(post url) — just the URL, no extra args"
    (is (= [:rf.http/managed
            {:request {:method :post :url "/api/items"}}]
           (rf.http/post "/api/items")))))

(deftest minimal-put
  (testing "(put url) — just the URL"
    (is (= [:rf.http/managed
            {:request {:method :put :url "/api/items/1"}}]
           (rf.http/put "/api/items/1")))))

(deftest minimal-delete
  (testing "(delete url) — just the URL"
    (is (= [:rf.http/managed
            {:request {:method :delete :url "/api/items/1"}}]
           (rf.http/delete "/api/items/1")))))

(deftest minimal-patch
  (testing "(patch url) — just the URL"
    (is (= [:rf.http/managed
            {:request {:method :patch :url "/api/items/1"}}]
           (rf.http/patch "/api/items/1")))))

(deftest minimal-head
  (testing "(head url) — just the URL"
    (is (= [:rf.http/managed
            {:request {:method :head :url "/api/items"}}]
           (rf.http/head "/api/items")))))

(deftest minimal-options
  (testing "(options url) — just the URL"
    (is (= [:rf.http/managed
            {:request {:method :options :url "/api/items"}}]
           (rf.http/options "/api/items")))))

;; ---- 2. on-success / on-failure pass through ------------------------------

(deftest on-success-passes-through
  (testing ":on-success at the top level passes through unchanged"
    (is (= [:rf.http/managed
            {:request    {:method :get :url "/api/items"}
             :on-success [:items/loaded]}]
           (rf.http/get "/api/items" {:on-success [:items/loaded]})))))

(deftest on-failure-passes-through
  (testing ":on-failure at the top level passes through unchanged"
    (is (= [:rf.http/managed
            {:request    {:method :post :url "/api/items"}
             :on-failure [:items/create-failed]}]
           (rf.http/post "/api/items" {:on-failure [:items/create-failed]})))))

(deftest both-on-success-and-on-failure
  (testing "both reply handlers, plus a request-id"
    (is (= [:rf.http/managed
            {:request    {:method :get :url "/api/items"}
             :on-success [:items/loaded]
             :on-failure [:items/load-failed]
             :request-id [:items :load]}]
           (rf.http/get "/api/items"
                        {:on-success [:items/loaded]
                         :on-failure [:items/load-failed]
                         :request-id [:items :load]})))))

;; ---- 3. request-envelope merging ------------------------------------------

(deftest request-body-merges-under-request
  (testing "caller-supplied :request keys merge with helper's :method + :url"
    (is (= [:rf.http/managed
            {:request {:method :post
                       :url    "/api/items"
                       :body   {:title "new"}
                       :request-content-type :json}}]
           (rf.http/post "/api/items"
                         {:request {:body {:title "new"}
                                    :request-content-type :json}})))))

(deftest request-headers-merge
  (testing "caller-supplied :headers under :request pass through"
    (is (= [:rf.http/managed
            {:request {:method :get
                       :url    "/api/items"
                       :headers {"X-Trace" "abc"}}}]
           (rf.http/get "/api/items"
                        {:request {:headers {"X-Trace" "abc"}}})))))

(deftest request-params-merge
  (testing "caller-supplied :params under :request pass through"
    (is (= [:rf.http/managed
            {:request {:method :get
                       :url    "/api/search"
                       :params {:q "foo" :page 2}}}]
           (rf.http/get "/api/search"
                        {:request {:params {:q "foo" :page 2}}})))))

;; ---- 4. helper pins :method and :url --------------------------------------

(deftest helper-overrides-caller-method
  (testing "caller's :request :method is overwritten by the helper's verb"
    ;; A user accidentally passes :method :get to (rf.http/post ...).
    ;; The helper's verb wins — POST it is.
    (is (= :post
           (-> (rf.http/post "/api/items"
                             {:request {:method :get :body "x"}})
               second :request :method)))))

(deftest helper-overrides-caller-url
  (testing "caller's :request :url is overwritten by the helper's URL arg"
    (is (= "/api/items"
           (-> (rf.http/get "/api/items"
                            {:request {:url "/somewhere-else"}})
               second :request :url)))))

;; ---- 5. top-level keys (decode, accept, retry, timeout-ms, abort-signal) --

(deftest decode-passes-through
  (testing ":decode at top level"
    (is (= :json
           (-> (rf.http/get "/api/items" {:decode :json})
               second :decode)))))

(deftest accept-passes-through
  (testing ":accept at top level (fn)"
    (let [my-accept (fn [v] {:ok v})]
      (is (= my-accept
             (-> (rf.http/get "/api/items" {:accept my-accept})
                 second :accept))))))

(deftest retry-passes-through
  (testing ":retry at top level"
    (let [retry {:on           #{:rf.http/transport :rf.http/http-5xx}
                 :max-attempts 3
                 :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}]
      (is (= retry
             (-> (rf.http/get "/api/items" {:retry retry})
                 second :retry))))))

(deftest timeout-ms-passes-through
  (testing ":timeout-ms at top level"
    (is (= 5000
           (-> (rf.http/get "/api/items" {:timeout-ms 5000})
               second :timeout-ms)))))

(deftest request-id-passes-through
  (testing ":request-id at top level"
    (is (= :search
           (-> (rf.http/get "/api/search" {:request-id :search})
               second :request-id)))))

(deftest abort-signal-passes-through
  (testing ":abort-signal at top level"
    (let [signal (Object.)]
      (is (identical? signal
                      (-> (rf.http/get "/api/items" {:abort-signal signal})
                          second :abort-signal))))))

;; ---- 6. full-fat example (every common slot at once) ----------------------

(deftest full-fat-shape
  (testing "every common top-level slot, plus :request body, simultaneously"
    (let [retry  {:on #{:rf.http/transport} :max-attempts 2
                  :backoff {:base-ms 100 :factor 2 :max-ms 500 :jitter false}}
          accept (fn [v] {:ok v})]
      (is (= [:rf.http/managed
              {:request    {:method  :post
                            :url     "/api/items"
                            :body    {:title "new"}
                            :headers {"X-Trace" "abc"}
                            :request-content-type :json}
               :decode     :json
               :accept     accept
               :retry      retry
               :timeout-ms 5000
               :on-success [:items/created]
               :on-failure [:items/create-failed]
               :request-id [:items :create]}]
             (rf.http/post "/api/items"
                           {:request    {:body    {:title "new"}
                                         :headers {"X-Trace" "abc"}
                                         :request-content-type :json}
                            :decode     :json
                            :accept     accept
                            :retry      retry
                            :timeout-ms 5000
                            :on-success [:items/created]
                            :on-failure [:items/create-failed]
                            :request-id [:items :create]}))))))

;; ---- 7. shape contract — every helper yields the canonical envelope -------

(deftest every-helper-yields-canonical-shape
  (testing "the fx vector is always [:rf.http/managed <args-map>]"
    (doseq [helper [rf.http/get rf.http/post rf.http/put rf.http/delete
                    rf.http/patch rf.http/head rf.http/options]]
      (let [fx (helper "/x")]
        (is (vector? fx))
        (is (= 2 (count fx)))
        (is (= :rf.http/managed (first fx)))
        (is (map? (second fx)))
        (is (= "/x" (-> fx second :request :url)))))))

(deftest verb-to-method-mapping
  (testing "each helper pins its verb keyword onto :request :method"
    (is (= :get     (-> (rf.http/get     "/x") second :request :method)))
    (is (= :post    (-> (rf.http/post    "/x") second :request :method)))
    (is (= :put     (-> (rf.http/put     "/x") second :request :method)))
    (is (= :delete  (-> (rf.http/delete  "/x") second :request :method)))
    (is (= :patch   (-> (rf.http/patch   "/x") second :request :method)))
    (is (= :head    (-> (rf.http/head    "/x") second :request :method)))
    (is (= :options (-> (rf.http/options "/x") second :request :method)))))
