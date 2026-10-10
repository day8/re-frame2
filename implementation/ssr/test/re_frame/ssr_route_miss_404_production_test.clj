(ns re-frame.ssr-route-miss-404-production-test
  "An SSR request for an unroutable URL answers 404 under the real
  production gate — not a soft-404 200.

  The route miss's dev trace is elided under `-Dre-frame.debug=false`, so
  the same emit site also rides the always-on error axis, and that record is
  what a production server projects from. Every assertion here holds in both
  postures, so the namespace runs under `scripts/test-ssr-prod-gate.sh` for
  real — a `with-redefs` of the load-time gate would prove nothing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; `re-frame.ssr`'s always-on capture hook (`:ssr/capture-error-record`) IS
;; the production projection path under test here.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- server-frame []
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}}))

(defn- miss!
  "Register the routes, dispatch a URL change for `url` on frame `f`, and
  return the always-on records a shipper saw."
  [f url]
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/not-found")
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::shipper #(swap! seen conj %))
    (rf/dispatch-sync [:rf.route/handle-url-change url] {:frame f})
    (rf.error-emit/unregister-error-listener! ::shipper)
    @seen))

(deftest unroutable-url-projects-404-under-the-production-gate
  (let [f (server-frame)]
    (miss! f "/no-such-page")
    (let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
      (is (= [404 {:status 404 :code :not-found :message "Page not found" :retryable? false}]
             [(:status response) public-error])))))

(deftest the-always-on-record-redacts-url-carrier-values
  (testing "exactly one record, closed to the enumerated slots, carrying the
            `:kind :route` the default projector's 404 gates on and the
            emitting frame; its `:url` keeps the path and the query KEY but
            never a query value or a `#fragment` (EP-0015)"
    (let [f       (server-frame)
          records (miss! f "/no-such-page?token=s3cr3t-query-value&flag#s3cr3t-fragment-value")
          url     (:url (first records))]
      (is (= [{:error :rf.error/no-such-handler :kind :route :frame f
               :time true :recovery :replaced-with-default :url true}]
             (mapv #(-> % (update :time integer?) (update :url string?)) records)))
      (is (str/starts-with? url "/no-such-page?token="))
      (is (not-any? #(str/includes? url %) ["s3cr3t-query-value" "s3cr3t-fragment-value"])))))

(deftest a-malformed-miss-carries-its-structured-reason
  (let [f      (server-frame)
        record (first (miss! f "/bad%ZZ-encoding"))]
    (is (= [:rf.error/no-such-handler :malformed-url 404]
           [(:error record) (:reason record) (:status (:response (rf.ssr/flush-response-result! f)))]))))

(deftest a-client-frame-route-miss-stamps-no-status
  (testing "the record still fans, but a client frame has no response to stamp"
    (let [client (rf.frame/make-anon-frame-record! {:platform :client})]
      (is (= [[:rf.error/no-such-handler] 200]
             [(mapv :error (miss! client "/no-such-page"))
              (:status (:response (rf.ssr/flush-response-result! client)))])))))
