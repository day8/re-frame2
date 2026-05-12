(ns re-frame.http-cljs-test
  "CLJS-side smoke for the `re-frame.http` call-site helpers (rf2-pf4k).

  The JVM `re-frame.http-test` covers the full shape contract. This
  smoke just confirms the helpers compile clean under CLJS (the file
  is `.cljc` with no host-specific bits, but a CLJS-side load is the
  fastest way to catch a regression that would otherwise only surface
  in shadow-cljs builds)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.http :as rf.http]))

(deftest get-helper-shape
  (testing "(rf.http/get url) produces [:rf.http/managed {:request {:method :get :url url}}]"
    (is (= [:rf.http/managed
            {:request {:method :get :url "/api/items"}}]
           (rf.http/get "/api/items")))))

(deftest post-helper-shape
  (testing "(rf.http/post url args) merges :request body with helper's verb + url"
    (is (= [:rf.http/managed
            {:request    {:method :post
                          :url    "/api/items"
                          :body   {:title "new"}
                          :request-content-type :json}
             :on-success [:item/created]}]
           (rf.http/post "/api/items"
                         {:request    {:body {:title "new"}
                                       :request-content-type :json}
                          :on-success [:item/created]})))))

(deftest put-delete-patch-head-options-shapes
  (testing "every verb pins the right :method"
    (is (= :put     (-> (rf.http/put     "/x") second :request :method)))
    (is (= :delete  (-> (rf.http/delete  "/x") second :request :method)))
    (is (= :patch   (-> (rf.http/patch   "/x") second :request :method)))
    (is (= :head    (-> (rf.http/head    "/x") second :request :method)))
    (is (= :options (-> (rf.http/options "/x") second :request :method)))))

(deftest top-level-keys-pass-through
  (testing ":decode, :accept, :retry, :timeout-ms, :request-id, :abort-signal pass through"
    (let [accept (fn [v] {:ok v})
          retry  {:on #{:rf.http/transport} :max-attempts 2}
          out    (rf.http/get "/x"
                              {:decode       :json
                               :accept       accept
                               :retry        retry
                               :timeout-ms   5000
                               :on-success   [:loaded]
                               :on-failure   [:errored]
                               :request-id   :search})
          args   (second out)]
      (is (= :json (:decode args)))
      (is (identical? accept (:accept args)))
      (is (= retry (:retry args)))
      (is (= 5000 (:timeout-ms args)))
      (is (= [:loaded] (:on-success args)))
      (is (= [:errored] (:on-failure args)))
      (is (= :search (:request-id args))))))
