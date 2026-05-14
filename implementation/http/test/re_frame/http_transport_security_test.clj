(ns re-frame.http-transport-security-test
  "Unit tests for `re-frame.http-transport` security-relevant guards.

  Beads:
   - rf2-9lun0 — invalid headers surface as `:rf.warning/http-header-invalid`
                 trace events rather than being silently dropped.
   - rf2-it1cd — the JVM request builder honours the args-map timeout-ms
                 (defaulted to 30000 at the handler) so the JDK
                 HttpClient applies a wall-clock read timeout."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http-handlers]
            [re-frame.http-transport]
            [re-frame.trace :as trace])
  (:import [java.net.http HttpRequest]
           [java.time Duration]
           [java.util Optional]))

;; Reach the private fn `jvm-build-request` via #' so we don't widen
;; the public surface.
(def ^:private jvm-build-request
  @#'re-frame.http-transport/jvm-build-request)

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::transport-security-cap]
    (try
      (trace/register-trace-cb! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (trace/remove-trace-cb! cb-id)))))

;; ---- rf2-9lun0 — header validation surfaces a trace ----------------------

(deftest invalid-header-value-emits-warning-not-silent-drop
  (testing "rf2-9lun0 — a stray \\r in a header value fires
  `:rf.warning/http-header-invalid` rather than silently dropping
  the header. Security-relevant middleware (auth-header attachment)
  depends on the signal."
    (with-trace-capture
      (fn [captured]
        ;; The JDK HttpClient rejects a header value containing CR/LF
        ;; (it would otherwise enable response-splitting attacks).
        (let [_req (jvm-build-request
                     {:method  :get
                      :url     "https://example.invalid/"
                      :headers {"X-Bad" "value-with-\rCR"}})
              warns (filter #(= :rf.warning/http-header-invalid
                                (:operation %))
                            @captured)]
          (is (seq warns)
              (str "expected a :rf.warning/http-header-invalid trace; "
                   "captured operations: "
                   (pr-str (mapv :operation @captured))))
          (let [w (first warns)]
            (is (= :warning (:op-type w)))
            (let [tags (:tags w)]
              (is (= "X-Bad" (:header tags))
                  "trace carries header NAME (value omitted — values may carry secrets)")
              (is (= "https://example.invalid/" (:url tags))
                  "trace carries the request URL for correlation")
              (is (some? (:cause tags))
                  "trace carries the JDK's validation message at :cause")
              (is (not (contains? tags :value))
                  "trace MUST NOT carry the rejected value — values can be secrets"))))))))

(deftest invalid-header-name-emits-warning
  (testing "rf2-9lun0 — an empty header name also fires the warning"
    (with-trace-capture
      (fn [captured]
        (let [_req (jvm-build-request
                     {:method  :get
                      :url     "https://example.invalid/"
                      :headers {"" "anything"}})
              warns (filter #(= :rf.warning/http-header-invalid
                                (:operation %))
                            @captured)]
          (is (seq warns)
              "expected :rf.warning/http-header-invalid for empty header name"))))))

(deftest valid-headers-do-not-emit-warning
  (testing "rf2-9lun0 — a valid header does NOT emit the warning"
    (with-trace-capture
      (fn [captured]
        (let [_req (jvm-build-request
                     {:method  :get
                      :url     "https://example.invalid/"
                      :headers {"Authorization" "Bearer xyz"
                                "Content-Type"  "application/json"}})
              warns (filter #(= :rf.warning/http-header-invalid
                                (:operation %))
                            @captured)]
          (is (empty? warns)
              (str "no warning expected for valid headers; saw: "
                   (mapv :tags warns))))))))

;; ---- rf2-it1cd — JVM request honours per-request timeout-ms --------------

(defn- request-timeout-ms ^Long [^HttpRequest req]
  (let [^Optional o (.timeout req)]
    (when (.isPresent o)
      (.toMillis ^Duration (.get o)))))

(deftest jvm-build-request-honours-timeout-ms
  (testing "rf2-it1cd — `:timeout-ms` is stamped onto the HttpRequest"
    (let [req (jvm-build-request
                {:method     :get
                 :url        "https://example.invalid/"
                 :timeout-ms 5000})]
      (is (= 5000 (request-timeout-ms req))
          "JDK HttpRequest carries the per-request read timeout"))))

(deftest jvm-build-request-without-timeout-ms-omits-jdk-timeout
  (testing "rf2-it1cd — when `:timeout-ms` is nil (opt-out), the
  HttpRequest does NOT carry a per-request timeout. The handler's
  `:or {timeout-ms 30000}` is the load-bearing default; this test
  asserts the opt-out semantic at the transport boundary so callers
  who DO want unbounded requests can pass `:timeout-ms nil`."
    (let [req (jvm-build-request
                {:method     :get
                 :url        "https://example.invalid/"
                 :timeout-ms nil})]
      (is (nil? (request-timeout-ms req))
          "nil timeout-ms produces an HttpRequest with no per-request timeout"))))

;; ---- rf2-it1cd — normalise-args applies the 30000 default ---------------

(def ^:private normalise-args @#'re-frame.http-handlers/normalise-args)

(deftest normalise-args-defaults-timeout-ms-to-30000
  (testing "rf2-it1cd — when the args map omits `:timeout-ms`, the
  normalised ctx carries the 30000 security default. Defending the
  contract end-to-end: a partner-API caller who forgets to set a
  read timeout still has the JDK HttpClient enforce a 30s wall-clock
  bound."
    (let [ctx (normalise-args {:request {:url "/x"}}
                              {:event [:some/event]})]
      (is (= 30000 (:timeout-ms ctx))
          "absent :timeout-ms must default to 30000"))))

(deftest normalise-args-honours-explicit-timeout-ms
  (testing "rf2-it1cd — an explicit `:timeout-ms 5000` overrides the default"
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms 5000}
                              {:event [:some/event]})]
      (is (= 5000 (:timeout-ms ctx))))))

(deftest normalise-args-passes-explicit-nil-timeout-ms-through
  (testing "rf2-it1cd — `:timeout-ms nil` is an explicit opt-out and
  threads through normalisation unchanged (the JVM transport then
  omits the JDK timeout). The opt-out is deliberate and intentional —
  documented in Spec 014 §`:timeout-ms` security defaults."
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms nil}
                              {:event [:some/event]})]
      (is (nil? (:timeout-ms ctx))
          "nil opt-out threads through unchanged"))))

(deftest normalise-args-passes-explicit-zero-timeout-ms-through
  (testing "rf2-it1cd — `:timeout-ms 0` is also an explicit opt-out
  (the JVM transport's `(when timeout-ms ...)` skips on falsy)"
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms 0}
                              {:event [:some/event]})]
      (is (zero? (:timeout-ms ctx))
          "zero opt-out threads through unchanged"))))
