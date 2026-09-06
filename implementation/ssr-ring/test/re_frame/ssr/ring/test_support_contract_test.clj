(ns re-frame.ssr.ring.test-support-contract-test
  "Smoke coverage for the shared SSR/Ring test scaffolding. An ephemeral
  Jetty round-trips a Ring
  handler over real `java.net.http`, the leak detector reports an empty
  set when no writer threads are alive, and `await-no-streaming-threads!`
  returns (rather than throws) the leaked set on timeout."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(deftest with-jetty-round-trips-a-ring-handler
  (testing "ts/with-jetty stands up an ephemeral-port Jetty,
            ts/new-http-client + ts/http-get observe the handler's
            response on the wire (status + body), and the server is torn
            down in the macro's finally"
    (let [handler (fn [_req]
                    {:status  201
                     :headers {"Content-Type" "text/plain"}
                     :body    "ts-smoke-ok"})]
      (rf.ssr.ring.test-support/with-jetty [port handler]
        (let [client (rf.ssr.ring.test-support/new-http-client)
              {:keys [status body]} (rf.ssr.ring.test-support/http-get client port "/" 5)]
          (is (= 201 status) "wire status is the handler's status")
          (is (= "ts-smoke-ok" body) "wire body is the handler's body"))))))

(deftest http-get-with-headers-returns-multimap-headers
  (testing ":with-headers? true adds the {name -> [val ...]}
            header multimap (the shape the CRLF-on-wire scan needs)"
    (let [handler (fn [_req]
                    {:status  200
                     :headers {"X-Ts-Smoke" "yep"}
                     :body    "h"})]
      (rf.ssr.ring.test-support/with-jetty [port handler]
        (let [client (rf.ssr.ring.test-support/new-http-client)
              {:keys [status headers]} (rf.ssr.ring.test-support/http-get client port "/" 5
                                                    :with-headers? true)]
          (is (= 200 status))
          (is (map? headers) "headers present as a map")
          ;; java.net.http lowercases header names; values are vectors.
          (let [h (into {} (map (fn [[k v]] [(str/lower-case k) v]) headers))]
            (is (= ["yep"] (get h "x-ts-smoke"))
                "custom header surfaces as a single-element value vector")))))))

(deftest leak-detector-reports-empty-when-no-writers
  (testing "with no streaming request in flight, the leak
            detector sees no rf2-ssr-streaming-* threads"
    (is (= "rf2-ssr-streaming-" rf.ssr.ring.test-support/daemon-thread-name-prefix)
        "the prefix matches the writer thread's naming contract")
    (is (empty? (rf.ssr.ring.test-support/live-streaming-threads))
        "no streaming-writer daemon thread is alive in a quiescent JVM")))

(deftest await-no-streaming-threads-returns-empty-fast-when-quiescent
  (testing "await-no-streaming-threads! returns [] immediately when nothing
            is leaked and does not throw"
    (let [result (rf.ssr.ring.test-support/await-no-streaming-threads! 1000 10)]
      (is (= [] result)
          "returns an empty vector (no leak) rather than throwing"))))
