(ns re-frame.http-encoding-test
  "The pure request-side helpers in `re-frame.http.encoding`: backoff,
  reply-event building, query and body encoding, `:accept` normalisation and
  header flattening. Each runs on every request or response."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.http.encoding :as rf.http.encoding]))

(deftest compute-backoff-ms-without-jitter
  (are [cfg attempt ms] (= ms (rf.http.encoding/compute-backoff-ms cfg attempt))
    {}                                     1 250
    {}                                     2 500
    {}                                     5 4000
    {}                                     6 5000
    {}                                     0 250
    {:base-ms 100 :factor 3}               3 900
    {:base-ms 1000 :factor 2 :max-ms 3000} 2 2000
    {:base-ms 1000 :factor 2 :max-ms 3000} 3 3000))

(deftest compute-backoff-ms-jitter-respects-clamp
  ;; Raw delay at attempt 10 is 512000, clamped to 2000; jitter must spread
  ;; samples ±25% around the CLAMPED value.
  (let [samples (repeatedly 200 #(rf.http.encoding/compute-backoff-ms
                                   {:base-ms 1000 :factor 2 :max-ms 2000 :jitter true} 10))]
    (is (= [] (remove #(<= 1500 % 2500) samples)))
    (is (some #(> % 2000) samples))
    (is (some #(< % 2000) samples))))

(deftest build-reply-event-per-explicit-on-shape
  (let [payload {:status :ok :value 42}
        build   #(rf.http.encoding/build-reply-event {:explicit-on % :reply-payload payload})]
    (is (nil? (build {:supplied? true :value nil})) "supplied nil silences the branch")
    (is (= [:items/loaded 7 payload] (build {:supplied? true :value [:items/loaded 7]})))
    (is (nil? (build {:supplied? false :value nil})) "an unsupplied branch has no target")
    (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/http-bad-reply-target"
                                   (build {:supplied? true :value :items/loaded})))]
      (is (= {:value :items/loaded :recovery :no-recovery}
             (select-keys (ex-data ex) [:value :recovery]))))))

(deftest params->query-encodes-each-value-shape
  (are [params qs] (= qs (rf.http.encoding/params->query params))
    {:page 2}           "page=2"
    {:q "a b"}          "q=a%20b"
    {:q "a&b"}          "q=a%26b"
    {:q "a+b"}          "q=a%2Bb"
    {:status :active}   "status=active"
    {:sort :sort/asc}   "sort=sort%2Fasc"
    {:tag [:a :b/c]}    "tag=a&tag=b%2Fc"
    {:tag []}           ""))

(deftest merge-params-splices-the-query-before-any-fragment
  (are [url params out] (= out (rf.http.encoding/merge-params url params))
    "/items"               {:page 2} "/items?page=2"
    "/items#frag"          {:page 2} "/items?page=2#frag"
    "/items?sort=asc#frag" {:page 2} "/items?sort=asc&page=2#frag"
    "/items"               nil       "/items"
    "/items?sort=asc"      {:tag []} "/items?sort=asc"))

(deftest encode-body-per-content-type
  (are [body ct out] (= out (rf.http.encoding/encode-body body ct))
    nil               :json             [nil nil]
    [1 2]             :json             ["[1,2]" "application/json"]
    {:q "a b"}        :form             ["q=a%20b" "application/x-www-form-urlencoded"]
    42                :text             ["42" "text/plain"]
    "<x/>"            "application/xml" ["<x/>" "application/xml"]
    [1 2]             nil               ["[1,2]" "application/json"]
    "already-encoded" nil               ["already-encoded" nil]))

(deftest run-accept-default-never-produces-http-status-rf2-xmp74u
  ;; The default accept only ever sees a decoded 2xx body, so it is
  ;; unconditionally {:ok decoded}, even for a body that looks like an error.
  (are [decoded] (= {:ok decoded} (rf.http.encoding/run-accept nil decoded))
    nil
    {:error "server blew up" :status 500}))

(deftest run-accept-user-fn-overrides-default
  (is (= {:ok 42} (rf.http.encoding/run-accept (fn [d] {:ok (:data d)}) {:data 42}))))

(deftest valid-accept-return-requires-exactly-one-of-ok-or-failure
  (are [v ok?] (= ok? (rf.http.encoding/valid-accept-return? v))
    {:ok nil}                 true
    {:failure {:kind :domain}} true
    {:ok 1 :extra :ignored}   true
    {}                        false
    {:ok 1 :failure {}}       false
    nil                       false
    :ok                       false))

(deftest normalize-header-pairs-repeats-the-name-per-value
  (testing "values are stringified; a sequential value yields one pair per element; an empty one yields none"
    (are [headers pairs] (= pairs (rf.http.encoding/normalize-header-pairs headers))
      {"X-Count" 42}                       [["X-Count" "42"]]
      {"X-Tag" [1 2]}                      [["X-Tag" "1"] ["X-Tag" "2"]]
      {"X-Empty" [] "Accept" "text/plain"} [["Accept" "text/plain"]])))
