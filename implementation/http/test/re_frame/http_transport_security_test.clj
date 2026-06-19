(ns re-frame.http-transport-security-test
  "Unit tests for `re-frame.http.transport` security-relevant guards.

  Beads:
   - rf2-9lun0 — invalid headers surface as `:rf.warning/http-header-invalid`
                 trace events rather than being silently dropped.
   - rf2-it1cd — the JVM request builder honours the args-map timeout-ms
                 (defaulted to 30000 at the handler) so the JDK
                 HttpClient applies a wall-clock read timeout."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http.handlers]
            [re-frame.http.privacy :as privacy]
            [re-frame.http.transport]
            [re-frame.http.transport-jvm]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace])
  (:import [java.net.http HttpClient HttpClient$Redirect HttpRequest]
           [java.time Duration]
           [java.util Optional]))

;; rf2-hp772l — the JVM platform transport (request building, client
;; memoisation, classification, CLJS-only-key degradation tracing) lives
;; in the per-platform adapter ns `re-frame.http.transport-jvm`. The
;; public seams are reached directly; the genuinely-internal helpers
;; (`redirect->policy`) via `#'` against that named adapter.
(def ^:private jvm-build-request
  re-frame.http.transport-jvm/jvm-build-request)

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::transport-security-cap]
    (try
      (trace/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (trace/unregister-listener! cb-id)))))

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

(deftest jvm-build-request-zero-timeout-ms-omits-jdk-timeout
  (testing "rf2-ee38b.7 — `:timeout-ms 0` is the SECOND opt-out per Spec
  014 §`:timeout-ms` security defaults (semantically identical to nil:
  no per-attempt timeout). Pre-fix this was broken: `0` is truthy in
  Clojure, so `(when timeout-ms …)` armed `(Duration/ofMillis 0)`, which
  throws `IllegalArgumentException` on the JDK and surfaced the opt-out
  as a spurious `:rf.http/transport` failure. The `(pos? timeout-ms)`
  guard now collapses `0` to no-timeout. This builds the request to
  prove it neither throws nor stamps a per-request deadline."
    (let [req (jvm-build-request
                {:method     :get
                 :url        "https://example.invalid/"
                 :timeout-ms 0})]
      (is (nil? (request-timeout-ms req))
          "zero timeout-ms produces an HttpRequest with no per-request timeout (and does not throw)"))))

;; ---- rf2-it1cd — normalise-args applies the 30000 default ---------------

(def ^:private normalise-args @#'re-frame.http.handlers/normalise-args)

(deftest normalise-args-defaults-timeout-ms-to-30000
  (testing "rf2-it1cd — when the args map omits `:timeout-ms`, the
  normalised ctx carries the 30000 security default. Defending the
  contract end-to-end: a partner-API caller who forgets to set a
  read timeout still has the JDK HttpClient enforce a 30s wall-clock
  bound."
    (let [ctx (normalise-args {:request {:url "/x"}}
                              ;; EP-0002 (rf2-nn0jqa): normalise-args reads
                              ;; the carried frame stamp off the fx-ctx; this
                              ;; direct-call unit test supplies it explicitly.
                              {:event [:some/event] :frame :rf/default})]
      (is (= 30000 (:timeout-ms ctx))
          "absent :timeout-ms must default to 30000"))))

(deftest normalise-args-honours-explicit-timeout-ms
  (testing "rf2-it1cd — an explicit `:timeout-ms 5000` overrides the default"
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms 5000}
                              ;; EP-0002 (rf2-nn0jqa): normalise-args reads
                              ;; the carried frame stamp off the fx-ctx; this
                              ;; direct-call unit test supplies it explicitly.
                              {:event [:some/event] :frame :rf/default})]
      (is (= 5000 (:timeout-ms ctx))))))

(deftest normalise-args-passes-explicit-nil-timeout-ms-through
  (testing "rf2-it1cd — `:timeout-ms nil` is an explicit opt-out and
  threads through normalisation unchanged (the JVM transport then
  omits the JDK timeout). The opt-out is deliberate and intentional —
  documented in Spec 014 §`:timeout-ms` security defaults."
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms nil}
                              ;; EP-0002 (rf2-nn0jqa): normalise-args reads
                              ;; the carried frame stamp off the fx-ctx; this
                              ;; direct-call unit test supplies it explicitly.
                              {:event [:some/event] :frame :rf/default})]
      (is (nil? (:timeout-ms ctx))
          "nil opt-out threads through unchanged"))))

(deftest normalise-args-passes-explicit-zero-timeout-ms-through
  (testing "rf2-it1cd — `:timeout-ms 0` is also an explicit opt-out and
  threads through normalisation unchanged. The transport then collapses
  it to no-timeout via a `(pos? timeout-ms)` guard — NOT a bare
  truthiness check, since `0` is truthy in Clojure. (The transport-level
  opt-out is pinned by `jvm-build-request-zero-timeout-ms-omits-jdk-timeout`
  below.)"
    (let [ctx (normalise-args {:request    {:url "/x"}
                               :timeout-ms 0}
                              ;; EP-0002 (rf2-nn0jqa): normalise-args reads
                              ;; the carried frame stamp off the fx-ctx; this
                              ;; direct-call unit test supplies it explicitly.
                              {:event [:some/event] :frame :rf/default})]
      (is (zero? (:timeout-ms ctx))
          "zero opt-out threads through unchanged"))))

;; ---- rf2-1jcpm — security audit round-2 regression coverage --------------

(deftest invalid-header-warning-redacts-denylisted-query-params
  (testing "rf2-1jcpm — when the request URL carries a denylisted query
  param (`?api_key=…`), the JVM header-validation warning trace MUST
  scrub the value and stamp `:sensitive?` on the event. The previous
  shape leaked the secret because the trace bypassed
  `privacy/prepare-emit-tags`."
    (with-trace-capture
      (fn [captured]
        (let [_req (jvm-build-request
                     {:method  :get
                      :url     "https://example.invalid/v1?api_key=SECRET&page=2"
                      :headers {"" "anything"}})
              w    (first (filter #(= :rf.warning/http-header-invalid
                                       (:operation %))
                                  @captured))]
          (is (some? w) "warning event should have been captured")
          (let [tags (:tags w)]
            (is (= "https://example.invalid/v1?api_key=:rf/redacted&page=2"
                   (:url tags))
                "denylisted query-param value MUST be scrubbed in trace URL")
            (is (true? (:sensitive? w))
                ":sensitive? MUST be stamped at top level — a denylisted
                param name is itself a signal that the request carries
                a secret (Spec 009 §Privacy)")))))))

(deftest invalid-header-warning-redacts-on-sensitive-request
  (testing "rf2-1jcpm — when the request is declared per-call :sensitive?,
  ALL query-param values in the warning trace URL are scrubbed (broader
  rule than the denylist)."
    (with-trace-capture
      (fn [captured]
        (let [_req (jvm-build-request
                     {:method     :get
                      :url        "https://example.invalid/v1?q=foo&page=2"
                      :headers    {"" "anything"}
                      :sensitive? true})
              w    (first (filter #(= :rf.warning/http-header-invalid
                                       (:operation %))
                                  @captured))]
          (is (some? w))
          (let [tags (:tags w)]
            (is (= "https://example.invalid/v1?q=:rf/redacted&page=:rf/redacted"
                   (:url tags))
                "sensitive request scrubs EVERY param value")
            (is (true? (:sensitive? w)))))))))

;; ---- rf2-1jcpm — CLJS-only-key warning redaction (JVM) -------------------

(def ^:private check-cljs-only-keys! re-frame.http.transport-jvm/check-cljs-only-keys!)

(deftest cljs-only-key-warning-redacts-denylisted-query-params
  (testing "rf2-1jcpm — the JVM warning for an ignored CLJS-only key
  (`:rf.http/cljs-only-key-ignored-on-jvm`) MUST redact denylisted
  query params in `:url`. Previously the raw URL rode the warning."
    (with-trace-capture
      (fn [captured]
        (check-cljs-only-keys!
          {:request {:url  "https://example.invalid/v1?token=SECRET&page=2"
                     :mode :cors}}
          false)
        (let [w (first (filter #(= :rf.http/cljs-only-key-ignored-on-jvm
                                    (:operation %))
                                @captured))]
          (is (some? w) "expected the ignored-key warning to be emitted")
          (let [tags (:tags w)]
            (is (= "https://example.invalid/v1?token=:rf/redacted&page=2"
                   (:url tags))
                "denylisted query-param value MUST be scrubbed")
            (is (true? (:sensitive? w))
                ":sensitive? stamped (denylist hit alone is a signal)")))))))

(deftest cljs-only-key-warning-redacts-on-sensitive-request
  (testing "rf2-1jcpm — sensitive flag also scrubs every param value"
    (with-trace-capture
      (fn [captured]
        (check-cljs-only-keys!
          {:request {:url      "https://example.invalid/v1?q=foo&page=2"
                     :referrer "https://internal/"}}
          true)
        (let [w (first (filter #(= :rf.http/cljs-only-key-ignored-on-jvm
                                    (:operation %))
                                @captured))]
          (is (some? w))
          (let [tags (:tags w)]
            (is (= "https://example.invalid/v1?q=:rf/redacted&page=:rf/redacted"
                   (:url tags)))
            (is (true? (:sensitive? w)))))))))

;; ---- rf2-ee38b.7 — JVM honours the spec's `:redirect` envelope key -------

(def ^:private redirect->policy
  @#'re-frame.http.transport-jvm/redirect->policy)

(def ^:private jvm-http-client-for
  re-frame.http.transport-jvm/jvm-http-client-for)

(deftest jvm-redirect-policy-maps-spec-values
  (testing "rf2-ee38b.7 — `:redirect` maps onto the JDK redirect policy.
  Spec 014 §Request envelope defaults `:redirect` to `:follow`; the JVM
  transport previously dropped the key entirely (JDK default NEVER), so a
  request relying on the spec default did NOT follow redirects on JVM/SSR.
  `:follow` → NORMAL, `:error`/`:manual` → NEVER, and the default (nil /
  unknown) → NORMAL to honour the spec default."
    (is (= HttpClient$Redirect/NORMAL (redirect->policy :follow))
        ":follow → NORMAL")
    (is (= HttpClient$Redirect/NEVER  (redirect->policy :error))
        ":error → NEVER")
    (is (= HttpClient$Redirect/NEVER  (redirect->policy :manual))
        ":manual → NEVER (no JDK manual analogue)")
    (is (= HttpClient$Redirect/NORMAL (redirect->policy nil))
        "absent :redirect → NORMAL (the spec default :follow)")
    (is (= HttpClient$Redirect/NORMAL (redirect->policy :unknown))
        "unknown value → NORMAL (the spec default :follow)")))

(deftest jvm-http-client-honours-follow-by-default
  (testing "rf2-ee38b.7 — the per-policy memoised client carries the right
  `followRedirects` setting. The spec default (`:follow`/nil) yields a
  client set to NORMAL (NOT the JDK's bare-builder default NEVER)."
    (let [^HttpClient default-client (jvm-http-client-for nil)
          ^HttpClient follow-client  (jvm-http-client-for :follow)
          ^HttpClient never-client   (jvm-http-client-for :error)]
      (is (= HttpClient$Redirect/NORMAL (.followRedirects default-client))
          "default (nil :redirect) client follows redirects per the spec default")
      (is (= HttpClient$Redirect/NORMAL (.followRedirects follow-client))
          ":follow client follows redirects")
      (is (= HttpClient$Redirect/NEVER (.followRedirects never-client))
          ":error/:manual client does not auto-follow")
      (is (identical? follow-client (jvm-http-client-for :follow))
          "clients are memoised per policy — connection pool is preserved"))))

;; ---- rf2-ee38b.7 — JVM timeout failure carries :limit-ms -----------------

(deftest jvm-timeout-failure-carries-limit-ms
  (testing "rf2-ee38b.7 — a JVM `:rf.http/timeout` failure now carries the
  configured `:limit-ms` (Spec 014 §Failure categories types
  `:rf.http/timeout` with `:elapsed-ms` / `:limit-ms`). The CLJS path
  always populated both; the JVM path emitted nil for both. `:elapsed-ms`
  legitimately stays nil on JVM (the JDK exposes no elapsed value)."
    (let [classify re-frame.http.transport-jvm/classify-jvm-error
          t   (java.net.http.HttpTimeoutException. "request timed out")
          out (classify t 5000 nil)]
      (is (= :rf.http/timeout (:kind out)))
      (is (= 5000 (:limit-ms out)) ":limit-ms is threaded from the configured timeout-ms")
      (is (nil? (:elapsed-ms out)) ":elapsed-ms stays nil on JVM"))))

;; ---- rf2-q3ts4 — classify-jvm-error no longer string-matches "abort"/"timed out" ----

;; Reach the private fn via #' so the public surface stays unchanged.
(def ^:private classify-jvm-error
  re-frame.http.transport-jvm/classify-jvm-error)

(deftest classify-jvm-error-uses-instance-checks-only
  (testing "rf2-q3ts4 — HttpTimeoutException → :rf.http/timeout (instance match)"
    (let [t (java.net.http.HttpTimeoutException. "request timed out after 30s")
          out (classify-jvm-error t nil nil)]
      (is (= :rf.http/timeout (:kind out)))
      (is (string? (:message out)))))

  (testing "rf2-q3ts4 — CancellationException → :rf.http/aborted (instance match)"
    (let [t (java.util.concurrent.CancellationException. "cancelled")
          out (classify-jvm-error t nil nil)]
      (is (= :rf.http/aborted (:kind out)))
      (is (= :user (:reason out)))))

  (testing "rf2-q3ts4 — a downstream service's error whose message contains
            \"timed out\" or \"abort\" must NOT misclassify. Pre-fix the
            substring fallback would route these to :rf.http/timeout /
            :rf.http/aborted; post-fix they correctly stay at
            :rf.http/transport (the catch-all for unknown JDK failures)."
    (let [timed-out-substring-trap
          (java.io.IOException. "upstream service reported: gateway timed out at edge")
          abort-substring-trap
          (java.lang.RuntimeException. "user clicked abort on 3rd-party retry-wrapper")
          out-1 (classify-jvm-error timed-out-substring-trap nil nil)
          out-2 (classify-jvm-error abort-substring-trap nil nil)]
      (is (= :rf.http/transport (:kind out-1))
          "an IOException whose message says \"timed out\" is NOT a JDK timeout — stays at :rf.http/transport")
      (is (= :rf.http/transport (:kind out-2))
          "a RuntimeException whose message says \"abort\" is NOT a JDK cancellation — stays at :rf.http/transport")
      (is (= "java.io.IOException" (:cause out-1)))
      (is (= "java.lang.RuntimeException" (:cause out-2)))))

  (testing "rf2-q3ts4 — wrapped causes still resolve through (.getCause t)"
    (let [inner (java.net.http.HttpTimeoutException. "inner timeout")
          outer (java.util.concurrent.CompletionException. inner)
          out (classify-jvm-error outer nil nil)]
      (is (= :rf.http/timeout (:kind out))
          "the JDK HttpClient wraps in CompletionException; classify- still resolves the underlying HttpTimeoutException via (.getCause t)"))))

;; ---- rf2-sixs3 — shared emit-and-dispatch-failure! tail -------------------
;;
;; The abort-precedence machinery previously duplicated the rf2-bma05
;; redact → trace/emit-error! → supersede-suppressed dispatch block across
;; BOTH finalise-failure! and finalise-success!'s sample-(2) abort path.
;; rf2-sixs3 extracts that tail into the private `emit-and-dispatch-failure!`
;; so the redaction shape + supersede-suppression guard live in ONE place.
;; These unit tests pin the helper's two-fold contract directly so a future
;; drift (a privacy slot added to one site but not the other can no longer
;; happen — there is only one site) is caught.

(def ^:private emit-and-dispatch-failure!
  @#'re-frame.http.transport/emit-and-dispatch-failure!)

(defn- with-router-capture
  "Stub the late-bind `:router/dispatch!` hook to capture dispatched
  events, restoring the original in finally. Returns the body-fn's value."
  [body-fn]
  (let [dispatched (atom [])
        original   (late-bind/get-fn :router/dispatch!)]
    (late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatched conj [ev opts])))
    (try (body-fn dispatched)
         (finally (late-bind/set-fn! :router/dispatch! original)))))

(deftest emit-and-dispatch-failure-emits-and-dispatches-non-supersede
  (testing "rf2-sixs3 — for a NON-supersede failure the helper both emits
            the redacted :rf.http/* trace AND dispatches the reply (the
            shared tail finalise-failure! and finalise-success! sample-(2)
            both route through)"
    (with-router-capture
      (fn [dispatched]
        (with-trace-capture
          (fn [captured]
            (let [ctx     {:request-id :rid
                           :url        "https://api.example.invalid/v1?api_key=SECRET&page=2"
                           :origin-event [:some/event]
                           :explicit-on-failure {:supplied? false :value nil}}
                  failure {:kind :rf.http/aborted :request-id :rid :reason :user}]
              (emit-and-dispatch-failure! ctx failure)
              ;; (a) emit fired with the failure kind, URL redacted.
              (let [ev (first (filter #(= :rf.http/aborted (:operation %)) @captured))]
                (is (some? ev) "the helper emitted a :rf.http/aborted trace")
                (is (= "https://api.example.invalid/v1?api_key=:rf/redacted&page=2"
                       (:url (:tags ev)))
                    "the rf2-bma05 redaction shape ran — denylisted query param scrubbed")
                (is (= :rid (:request-id (:tags ev)))
                    ":request-id stamped on the emitted trace"))
              ;; (b) the reply dispatched (non-supersede → not suppressed).
              (is (= 1 (count @dispatched))
                  "a non-supersede failure dispatches exactly one reply")
              (is (= :failure (-> @dispatched first first (nth 1) :rf/reply :kind))
                  "the dispatched reply is a :failure envelope"))))))))

(deftest emit-and-dispatch-failure-suppresses-supersede-dispatch
  (testing "rf2-sixs3 — for a supersede (:rf.http/aborted with
            :reason :request-id-superseded) the helper STILL emits the
            trace but SUPPRESSES the reply dispatch (rf2-lxd3 — the new
            request replaces the old; the prior :on-failure must not fire)"
    (with-router-capture
      (fn [dispatched]
        (with-trace-capture
          (fn [captured]
            (let [ctx     {:request-id :rid
                           :url        "https://api.example.invalid/q"
                           :origin-event [:some/event]
                           :explicit-on-failure {:supplied? false :value nil}}
                  failure {:kind :rf.http/aborted :request-id :rid
                           :reason :request-id-superseded}]
              (emit-and-dispatch-failure! ctx failure)
              ;; emit STILL fires (consumers keep visibility via the trace bus).
              (is (some (fn [ev] (and (= :rf.http/aborted (:operation ev))
                                      (= :request-id-superseded (:reason (:tags ev)))))
                        @captured)
                  "supersede still emits :rf.http/aborted :reason :request-id-superseded")
              ;; dispatch SUPPRESSED.
              (is (empty? @dispatched)
                  "supersede suppresses the reply dispatch (no :on-failure fires)"))))))))
