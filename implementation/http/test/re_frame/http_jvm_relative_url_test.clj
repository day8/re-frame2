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
            [re-frame.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

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

(deftest a-url-quoted-in-the-message-is-redacted-on-the-trace
  (testing "the message quotes the url, so on every trace row its denylisted
            query values take the redacted form the :url slot carries; the
            app's own reply keeps the url it sent"
    (let [traces (atom [])]
      (rf.trace.tooling/register-listener! ::traces #(swap! traces conj %))
      (try
        (rf/reg-event :jvm-relative/secret
          (fn [{:keys [db]} [_ reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:jvm-relative/secret]
                      :request  {:url "/api/items?api_key=SECRET-K&page=2"}}]]})))
        (rf/dispatch-sync [:jvm-relative/secret])
        (let [error     (:error (rf.test-support/poll-until
                                  #(:reply (rf/app-db-value :rf/default))
                                  {:timeout-ms 5000 :label "jvm relative-url secret reply"}))
              rows      (filter #(#{:rf.http/transport :rf.http/replied} (:operation %)) @traces)
              transport (some #(when (= :rf.http/transport (:operation %)) %) rows)]
          (is (= #{:rf.http/transport :rf.http/replied} (set (map :operation rows))))
          (is (str/includes? (str (get-in transport [:tags :message])) "api_key=:rf/redacted&page=2")
              "the trace's message still names the url, redacted")
          (is (not-any? #(str/includes? (pr-str %) "SECRET-K") rows))
          (is (str/includes? (:message error) "SECRET-K")
              "the reply is the app's own data"))
        (finally (rf.trace.tooling/unregister-listener! ::traces))))))
