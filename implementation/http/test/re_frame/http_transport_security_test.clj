(ns re-frame.http-transport-security-test
  "Security-relevant JVM transport guards: invalid-header warnings,
  privacy composition, timeout defaults, redirect policy, failure
  classification, and supersede suppression on the shared failure tail."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [re-frame.http.handlers]
            [re-frame.http.transport]
            [re-frame.http.transport-jvm]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.io IOException]
           [java.net.http HttpClient HttpClient$Redirect HttpTimeoutException]
           [java.util.concurrent CancellationException CompletionException]))

;; Public seams live in the JVM adapter; tests reach genuinely private helpers
;; there with `#'` rather than widening production API.
(def ^:private jvm-build-request
  re-frame.http.transport-jvm/jvm-build-request)

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::transport-security-cap]
    (try
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (rf.trace.tooling/unregister-listener! cb-id)))))

(defn- first-op [captured op]
  (first (filter #(= op (:operation %)) @captured)))

;; ---- invalid-header warning ------------------------------------------------

(deftest invalid-header-warning-carries-no-part-of-the-rejected-value
  (testing "the rejected header VALUE reaches no trace event, `:cause`
  included. The JDK's own rejection message echoes the value, and a header
  is where credentials live, so the warning names only the header
  (Spec 014 §Request envelope: value omitted)."
    (with-trace-capture
      (fn [captured]
        (let [sentinel "SECRETVALUE"]
          (jvm-build-request
            {:method  :get
             :url     "https://example.invalid/"
             :headers {"Authorization" (str "tok\n" sentinel)}})
          (is (= "Authorization"
                 (get-in (first-op captured :rf.warning/http-header-invalid) [:tags :header]))
              "the CR/LF-bearing value is rejected and the warning names its header")
          (is (not (str/includes? (pr-str @captured) sentinel))
              "no captured trace event carries any part of the rejected value"))))))

(deftest invalid-header-warning-redacts-on-sensitive-request
  (testing "on a per-call :sensitive? request, every query-param value in the
  warning's URL is scrubbed and the event is stamped sensitive"
    (with-trace-capture
      (fn [captured]
        (jvm-build-request
          {:method     :get
           :url        "https://example.invalid/v1?q=foo&page=2"
           :headers    {"" "anything"}
           :sensitive? true})
        (let [w (first-op captured :rf.warning/http-header-invalid)]
          (is (= "https://example.invalid/v1?q=:rf/redacted&page=:rf/redacted"
                 (:url (:tags w))))
          (is (true? (:sensitive? w))))))))

;; ---- normalise-args applies the 30000 default ---------------------------

(def ^:private normalise-args @#'re-frame.http.handlers/normalise-args)

(deftest normalise-args-defaults-timeout-ms-and-keeps-the-opt-outs
  (testing "an absent `:timeout-ms` normalises to the 30000 security
  default; the two explicit opt-outs, nil and 0, thread through unchanged
  for the transport to collapse to no-timeout."
    (are [args expected]
         (= expected (:timeout-ms
                       (normalise-args (merge {:request {:url "/x"}} args)
                                       {:event [:some/event] :frame :rf/default})))
      {}                30000
      {:timeout-ms nil} nil
      {:timeout-ms 0}   0)))

;; ---- CLJS-only-key warning redaction (JVM) -------------------------------

(deftest cljs-only-key-warning-redacts-on-sensitive-request
  (testing "the ignored-CLJS-only-key warning scrubs every query-param value
  of a sensitive request and is stamped sensitive"
    (with-trace-capture
      (fn [captured]
        (re-frame.http.transport-jvm/check-cljs-only-keys!
          {:request {:url      "https://example.invalid/v1?q=foo&page=2"
                     :referrer "https://internal/"}}
          true)
        (let [w (first-op captured :rf.http/cljs-only-key-ignored-on-jvm)]
          (is (= "https://example.invalid/v1?q=:rf/redacted&page=:rf/redacted"
                 (:url (:tags w))))
          (is (true? (:sensitive? w))))))))

;; ---- JVM honours the spec's `:redirect` envelope key ---------------------

(def ^:private jvm-http-client-for
  re-frame.http.transport-jvm/jvm-http-client-for)

(deftest jvm-http-client-honours-follow-by-default
  (testing "`:redirect` selects the client's redirect policy. The spec default
  (absent, or `:follow`) follows, unlike the JDK builder's own default NEVER;
  `:error` and `:manual` have no JDK analogue beyond NEVER. Clients are
  memoised per policy so each keeps its connection pool."
    (are [redirect policy]
         (= policy (.followRedirects ^HttpClient (jvm-http-client-for redirect)))
      nil     HttpClient$Redirect/NORMAL
      :follow HttpClient$Redirect/NORMAL
      :error  HttpClient$Redirect/NEVER
      :manual HttpClient$Redirect/NEVER)
    (is (identical? (jvm-http-client-for :follow) (jvm-http-client-for :follow)))))

;; ---- classify-jvm-error ----------------------------------------------------

(deftest classify-jvm-error-uses-instance-checks-only
  (testing "classification matches exception TYPES (unwrapping a
  CompletionException), never message text: a downstream error whose message
  says \"timed out\" or \"abort\" stays :rf.http/transport. A timeout carries
  the configured :limit-ms and the measured :elapsed-ms."
    (are [t expected] (= expected (re-frame.http.transport-jvm/classify-jvm-error t 5000 5012))
      (HttpTimeoutException. "request timed out")
      {:kind :rf.http/timeout :elapsed-ms 5012 :limit-ms 5000 :message "request timed out"}

      (CompletionException. (HttpTimeoutException. "inner timeout"))
      {:kind :rf.http/timeout :elapsed-ms 5012 :limit-ms 5000 :message "inner timeout"}

      (CancellationException. "cancelled")
      {:kind :rf.http/aborted :reason :user :message "cancelled"}

      (IOException. "upstream service reported: gateway timed out at edge")
      {:kind    :rf.http/transport
       :message "upstream service reported: gateway timed out at edge"
       :cause   "java.io.IOException"}

      (RuntimeException. "user clicked abort on 3rd-party retry-wrapper")
      {:kind    :rf.http/transport
       :message "user clicked abort on 3rd-party retry-wrapper"
       :cause   "java.lang.RuntimeException"})))

;; ---- shared emit-and-dispatch-failure! tail -------------------------------
;;
;; A completion that wins the once-only CAS after a supersede flipped the
;; handle's abort cell is reclassified onto this tail, which must suppress the
;; reply the superseded request would otherwise deliver.

(def ^:private emit-and-dispatch-failure!
  @#'re-frame.http.transport/emit-and-dispatch-failure!)

(deftest emit-and-dispatch-failure-suppresses-supersede-dispatch
  (testing "for a supersede (:rf.http/aborted with :reason
            :request-id-superseded) the helper still emits the trace but
            dispatches no reply"
    (let [dispatched (atom [])
          original   (rf.late-bind/get-fn :router/dispatch!)]
      (rf.late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatched conj [ev opts])))
      (try
        (with-trace-capture
          (fn [captured]
            (emit-and-dispatch-failure!
              {:request-id          :rid
               :url                 "https://api.example.invalid/q"
               :origin-event        [:some/event]
               :explicit-on-failure {:supplied? false :value nil}}
              {:kind :rf.http/aborted :request-id :rid :reason :request-id-superseded})
            (is (= :request-id-superseded
                   (:reason (:tags (first-op captured :rf.http/aborted))))
                "supersede still emits :rf.http/aborted")
            (is (empty? @dispatched) "no :on-failure reply is dispatched")))
        (finally (rf.late-bind/set-fn! :router/dispatch! original))))))
