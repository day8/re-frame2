(ns re-frame.http-jvm-relative-url-test
  "JVM HTTP transport: relative `:url` contract (Spec 014 §JVM transport —
  absolute URLs required).

  The browser Fetch transport resolves a relative `:url` against the page's
  document base; the JDK `HttpClient` has no such base and would reject it as
  `URI with undefined scheme`, a message naming neither the url nor the fix.
  `jvm-build-request` rejects it first with a message that names both, and the
  request resolves to an ordinary `:rf.http/transport` failure carrying it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest real-dispatch-with-relative-url-fails-clearly
  (testing "a real (non-stub) :rf.http/managed dispatch with a relative :url
            resolves to a :rf.http/transport failure whose message names the
            url and the absolute-url requirement"
    (rf/reg-event :jvm-relative/load
      (fn [{:keys [db]} [_ msg reply]]
        (if reply
          {:db (assoc db :reply reply)}
          {:fx [[:rf.http/managed
                 {:reply-to [:jvm-relative/load msg] :request {:method :get :url "/api/items"}
                  :decode  :json}]]})))
    (rf/dispatch-sync [:jvm-relative/load])
    (let [error (-> (rf.test-support/poll-until
                      #(:reply (rf/app-db-value :rf/default))
                      {:timeout-ms 5000 :label "jvm relative-url reply"})
                    :error)]
      (is (= :rf.http/transport (:kind error)))
      (is (str/includes? (:message error) "absolute")
          "the message explains the absolute-url requirement")
      (is (str/includes? (:message error) "/api/items")
          "the message names the offending url"))))
