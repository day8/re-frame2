(ns re-frame.http-error-body-cljs-test
  "Host-symmetric (JVM + CLJS) contract of a 4xx / 5xx failure's body (Spec 014
  §Classification order): the failure is classified by status, `:body-text`
  holds the raw response text, and `:body` holds that text parsed best-effort
  when the Content-Type is a JSON media type, the raw text otherwise.

  Every case runs the real managed pipeline below the stub transport, and every
  answer is inline, so each reply has landed when the `dispatch-sync` that
  issued it returns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.test-support :as rf.test-support]
            #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
                :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private app :http-error-body/app)

(def ^:private url "https://api.example.test/users")

(defn- failure-for
  "Answer one `:rf.http/managed` POST with `response` (a stub response map) and
  return the failure map the reply carries under `:error`. `extra` merges into
  the args map."
  ([response] (failure-for response {}))
  ([response extra]
   (rf/make-frame {:id app})
   (rf/reg-event ::replied
     {:doc "Records the reply."}
     (fn [{:keys [db]} [_ reply]] {:db (assoc db ::reply reply)}))
   (rf/reg-event ::fire
     {:doc "Issues the request under test."}
     (fn [_ _] {:fx [[:rf.http/managed (merge {:request  {:method :post :url url :body {:email "a@b.c"}}
                                               :decode   :json
                                               :reply-to [::replied]}
                                              extra)]]}))
   (rf.http.stub-transport/with-transport
     (rf.http.stub-transport/route-transport {[:post url] response})
     #(rf/dispatch-sync [::fire] {:frame app}))
   (let [reply (::reply (rf/app-db-value app))]
     (is (= :error (:status reply)) "the request failed")
     (:error reply))))

(deftest a-json-error-body-is-parsed-into-body
  (testing "a 422 whose Content-Type is application/json: :body holds the
            keyword-keyed value, :body-text the raw text"
    (let [failure (failure-for {:status 422 :body {:errors {:email ["is invalid"]}}})]
      (is (= [:rf.http/http-4xx 422] [(:kind failure) (:status failure)]))
      (is (= {:errors {:email ["is invalid"]}} (:body failure)))
      (is (= "{\"errors\":{\"email\":[\"is invalid\"]}}" (:body-text failure)))))
  (testing "a 500 under an RFC 6839 +json media type parses the same way"
    (let [failure (failure-for {:status  500
                                :headers {"content-type" "application/problem+json; charset=utf-8"}
                                :body    "{\"title\":\"Out of stock\",\"status\":500}"})]
      (is (= :rf.http/http-5xx (:kind failure)))
      (is (= {:title "Out of stock" :status 500} (:body failure)))))
  (testing "a top-level JSON array is a vector on both hosts"
    (let [{:keys [body]} (failure-for {:status 400 :body [{:field "email"} {:field "name"}]})]
      (is (vector? body))
      (is (= [{:field "email"} {:field "name"}] body)))))

(deftest a-non-json-error-body-stays-a-string
  (testing "an HTML 500 keeps its text at :body and at :body-text"
    (let [html    "<!doctype html><h1>Service Unavailable</h1>"
          failure (failure-for {:status 500 :headers {"content-type" "text/html"} :body html})]
      (is (= [:rf.http/http-5xx html html]
             [(:kind failure) (:body failure) (:body-text failure)]))))
  (testing "JSON-looking text with no Content-Type is not parsed: the Content-Type decides"
    (let [text "{\"errors\":{}}"]
      (is (= text (:body (failure-for {:status 404 :body text})))))))

(deftest a-json-error-body-that-does-not-parse-stays-a-string
  (testing "malformed JSON under a JSON Content-Type: the raw string at :body, still
            classified by status, never :rf.http/decode-failure"
    (let [text    "{\"errors\": [truncated"
          failure (failure-for {:status 422 :headers {"content-type" "application/json"} :body text})]
      (is (= [:rf.http/http-4xx text text]
             [(:kind failure) (:body failure) (:body-text failure)]))))
  (testing "an empty JSON body is the empty string on both hosts"
    (let [failure (failure-for {:status 503 :headers {"content-type" "application/json"} :body ""})]
      (is (= [:rf.http/http-5xx "" ""]
             [(:kind failure) (:body failure) (:body-text failure)]))))
  (testing "a body past the request's key cap is not interned: the raw string at :body"
    (let [failure (failure-for {:status 422 :body {:a 1 :b 2 :c 3}}
                               {:rf.http/max-decoded-keys 2})]
      (is (= :rf.http/http-4xx (:kind failure)))
      (is (= "{\"a\":1,\"b\":2,\"c\":3}" (:body failure) (:body-text failure))))))
