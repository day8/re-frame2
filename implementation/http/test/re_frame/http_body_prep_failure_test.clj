(ns re-frame.http-body-prep-failure-test
  "(JVM) Body realization + body encoding are a MANAGED request-preparation
  phase.

  `prepare-body!` runs the realization + encoding AFTER the handle is
  registered, catches a throw, and routes a canonical `:rf.http/transport`
  failure (`:stage :request-prep`) through the normal `maybe-retry!` path, so
  `:on-failure`, retry policy and registry cleanup all apply. A throw that
  escaped instead would surface as a generic `:rf.error/fx-handler-exception`
  and leave the caller waiting for a reply that never comes.

  These cases throw before any socket is touched, so no test server is needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- prep-failure [reply]
  (select-keys (:error reply) [:kind :stage]))

(deftest unencodable-body-delivers-managed-transport-failure
  (testing "a body that `encode-body` (JSON) rejects delivers ONE :on-failure
            reply with :rf.http/transport, synchronously"
    (let [replies (atom [])]
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue/unencodable
        (fn [_ _]
          {:fx [[:rf.http/managed
                 ;; :request-content-type :json forces a JSON encode of a value
                 ;; that cannot be serialised (a bare function).
                 {:request    {:url    "http://127.0.0.1:0/x"
                               :method :post
                               :request-content-type :json
                               :body   {:f (fn [])}}
                  :request-id :prep-encode
                  :on-failure [:reply/recorder]
                  :on-success [:reply/recorder]}]]}))
      (rf/dispatch-sync [:issue/unencodable])
      (is (= [{:kind :rf.http/transport :stage :request-prep}] (map prep-failure @replies))
          "exactly one reply, carrying the request-prep transport failure"))))

(deftest throwing-body-thunk-retries-when-configured
  (testing "with `:retry {:on #{:rf.http/transport} …}`, a throwing body thunk
            is re-invoked per attempt, then fails :rf.http/transport once the
            retries exhaust, leaving the registry clean"
    (let [replies     (atom [])
          invocations (atom 0)]
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue/retry-thunk
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url    "http://127.0.0.1:0/x"
                               :method :post
                               :body   (fn []
                                         (swap! invocations inc)
                                         (throw (ex-info "boom-retry" {})))}
                  :retry      {:on           #{:rf.http/transport}
                               :max-attempts 3
                               :backoff      {:base-ms 1 :factor 1 :max-ms 1}}
                  :request-id :prep-retry
                  :on-failure [:reply/recorder]
                  :on-success [:reply/recorder]}]]}))
      (rf/dispatch-sync [:issue/retry-thunk])
      (rf.test-support/poll-until #(seq @replies)
                                  {:timeout-ms 4000 :label "final prep-failure reply"})
      (is (= 3 @invocations)
          "the thunk was re-invoked once per attempt (max-attempts 3)")
      (is (= [{:kind :rf.http/transport :stage :request-prep}] (map prep-failure @replies))
          "exactly one FINAL reply, carrying the request-prep transport failure")
      (is (= "boom-retry" (get-in (first @replies) [:error :message]))
          "the thrown message rides the failure for diagnostics")
      (is (empty? (rf.http.registry/in-flight-snapshot))
          "the registry is clean after the retry sequence exhausts"))))
