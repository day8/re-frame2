(ns re-frame.http-privacy-test
  "Unit tests for `re-frame.http-privacy` — Spec 014 §Privacy (rf2-bma05).

  Covers:
   - Header denylist (default set, case-insensitive, app-extensible).
   - `redact-headers` walks a map and replaces sensitive header values.
   - `request-sensitive?` reads per-call, per-request, and handler-meta.
   - `redact-request-tags` / `redact-failure` / `stamp-sensitive` /
     `prepare-emit-tags` / `prepare-emit-failure` compose correctly.

  Integration with the trace surface (sensitive HTTP requests emitting
  redacted trace events end-to-end) is covered in
  `re-frame.http-privacy-integration-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http-privacy :as privacy]
            [re-frame.registrar :as registrar]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (privacy/clear-sensitive-headers!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- 1. header denylist ---------------------------------------------------

(deftest default-header-denylist-covers-canonical-set
  (testing "the default denylist contains the canonical bearer / auth surface"
    (is (contains? privacy/default-header-denylist "authorization"))
    (is (contains? privacy/default-header-denylist "cookie"))
    (is (contains? privacy/default-header-denylist "set-cookie"))
    (is (contains? privacy/default-header-denylist "x-api-key"))
    (is (contains? privacy/default-header-denylist "x-auth-token"))
    (is (contains? privacy/default-header-denylist "x-csrf-token"))
    (is (contains? privacy/default-header-denylist "proxy-authorization"))))

(deftest sensitive-header-is-case-insensitive
  (testing "header-name match ignores case"
    (is (privacy/sensitive-header? "Authorization"))
    (is (privacy/sensitive-header? "AUTHORIZATION"))
    (is (privacy/sensitive-header? "authorization"))
    (is (privacy/sensitive-header? "Cookie"))
    (is (privacy/sensitive-header? "X-API-Key"))))

(deftest non-sensitive-headers-pass
  (testing "ordinary headers are not in the denylist"
    (is (not (privacy/sensitive-header? "Content-Type")))
    (is (not (privacy/sensitive-header? "Accept")))
    (is (not (privacy/sensitive-header? "User-Agent")))
    (is (not (privacy/sensitive-header? "X-Request-Id")))))

(deftest declare-sensitive-header-extends-denylist
  (testing "app-extended denylist composes with defaults"
    (privacy/declare-sensitive-header! "X-Honeycomb-Team")
    (is (privacy/sensitive-header? "X-Honeycomb-Team"))
    (is (privacy/sensitive-header? "x-honeycomb-team"))
    ;; defaults still apply
    (is (privacy/sensitive-header? "Authorization"))
    (privacy/clear-sensitive-headers!)
    (is (not (privacy/sensitive-header? "X-Honeycomb-Team")))
    ;; defaults survive the clear
    (is (privacy/sensitive-header? "Authorization"))))

(deftest sensitive-header-tolerates-non-string
  (testing "nil / non-string is not sensitive"
    (is (not (privacy/sensitive-header? nil)))
    (is (not (privacy/sensitive-header? :keyword)))
    (is (not (privacy/sensitive-header? 42)))))

;; ---- 2. redact-headers ----------------------------------------------------

(deftest redact-headers-replaces-sensitive-values
  (testing "denylisted header values are replaced with :rf/redacted"
    (let [m {"Authorization" "Bearer abc123"
             "Content-Type"  "application/json"
             "Cookie"        "session=secret"
             "X-Request-Id"  "req-42"}
          r (privacy/redact-headers m)]
      (is (= :rf/redacted (get r "Authorization")))
      (is (= :rf/redacted (get r "Cookie")))
      (is (= "application/json" (get r "Content-Type")))
      (is (= "req-42" (get r "X-Request-Id"))))))

(deftest redact-headers-handles-empty-and-nil
  (is (nil? (privacy/redact-headers nil)))
  (is (= {} (privacy/redact-headers {}))))

(deftest redact-headers-handles-mixed-case-keys
  (testing "header-name match ignores case on the map key"
    (let [m {"AUTHORIZATION" "Bearer xyz"
             "set-cookie"    "id=1"
             "Set-cookie"    "id=2"}
          r (privacy/redact-headers m)]
      (is (= :rf/redacted (get r "AUTHORIZATION")))
      (is (= :rf/redacted (get r "set-cookie")))
      (is (= :rf/redacted (get r "Set-cookie"))))))

;; ---- 3. request-sensitive? -------------------------------------------------

(deftest request-sensitive-per-call-true
  (testing "per-call :sensitive? on the args map opts in"
    (is (true? (privacy/request-sensitive?
                 {:sensitive? true :request {:url "/x"}}
                 [:some/event])))))

(deftest request-sensitive-per-request-true
  (testing "per-request :sensitive? on the :request map opts in"
    (is (true? (privacy/request-sensitive?
                 {:request {:url "/x" :sensitive? true}}
                 [:some/event])))))

(deftest request-sensitive-via-handler-metadata
  (testing "handler-meta :sensitive? true triggers sensitivity"
    (registrar/register! :event :secret/login
                         {:handler-fn (fn [_ctx _ev] nil)
                          :doc        "Secret op"
                          :sensitive? true})
    (is (true? (privacy/request-sensitive?
                 {:request {:url "/x"}}
                 [:secret/login {:user "ada"}])))))

(deftest request-sensitive-default-false
  (testing "no metadata + no per-call flag = not sensitive"
    (registrar/register! :event :ordinary/op
                         {:handler-fn (fn [_ctx _ev] nil)
                          :doc        "Ordinary op"})
    (is (false? (privacy/request-sensitive?
                  {:request {:url "/x"}}
                  [:ordinary/op {}])))))

(deftest request-sensitive-tolerates-missing-handler
  (testing "unknown handler id does not blow up"
    (is (false? (privacy/request-sensitive?
                  {:request {:url "/x"}}
                  [:never/registered])))))

(deftest request-sensitive-tolerates-nil-origin-event
  (is (false? (privacy/request-sensitive? {:request {:url "/x"}} nil)))
  (is (false? (privacy/request-sensitive? {:request {:url "/x"}} []))))

;; ---- 4. redact-request-tags ----------------------------------------------

(deftest redact-request-tags-always-redacts-headers
  (testing "headers are denylist-redacted regardless of :sensitive?"
    (let [tags  {:url "/x"
                 :headers {"Authorization" "Bearer t"
                           "Content-Type"  "application/json"}}
          r     (privacy/redact-request-tags tags false)]
      (is (= :rf/redacted (get-in r [:headers "Authorization"])))
      (is (= "application/json" (get-in r [:headers "Content-Type"]))))))

(deftest redact-request-tags-redacts-body-when-sensitive
  (testing "body redacted when sensitive? is true"
    (let [tags {:url "/x" :body "{user: ada}"}
          r    (privacy/redact-request-tags tags true)]
      (is (= :rf/redacted (:body r))))
    (testing "body preserved when sensitive? is false"
      (let [tags {:url "/x" :body "regular payload"}
            r    (privacy/redact-request-tags tags false)]
        (is (= "regular payload" (:body r)))))))

(deftest redact-request-tags-redacts-params-when-sensitive
  (testing "query-string params redacted when sensitive? is true"
    (let [tags {:url "/x" :params {:token "abc123"}}
          r    (privacy/redact-request-tags tags true)]
      (is (= :rf/redacted (:params r))))))

;; ---- 5. redact-failure ---------------------------------------------------

(deftest redact-failure-redacts-response-body-when-sensitive
  (let [f {:kind :rf.http/http-4xx :status 401 :body "{password: shhh}"}
        r (privacy/redact-failure f true)]
    (is (= :rf/redacted (:body r)))))

(deftest redact-failure-redacts-decode-failure-body-text
  (let [f {:kind :rf.http/decode-failure :body-text "raw secret"}
        r (privacy/redact-failure f true)]
    (is (= :rf/redacted (:body-text r)))))

(deftest redact-failure-redacts-accept-failure-detail
  (let [f {:kind :rf.http/accept-failure :detail {:user-id 1 :pii "..."}}
        r (privacy/redact-failure f true)]
    (is (= :rf/redacted (:detail r)))))

(deftest redact-failure-preserves-when-not-sensitive
  (let [f {:kind :rf.http/http-4xx :status 401 :body "{password: shhh}"}
        r (privacy/redact-failure f false)]
    (is (= "{password: shhh}" (:body r)))))

(deftest redact-failure-always-redacts-headers
  (testing "denylisted headers redacted even when not sensitive"
    (let [f {:kind :rf.http/http-4xx
             :status 401
             :headers {"Set-Cookie" "id=secret"
                       "Content-Type" "text/plain"}}
          r (privacy/redact-failure f false)]
      (is (= :rf/redacted (get-in r [:headers "Set-Cookie"])))
      (is (= "text/plain" (get-in r [:headers "Content-Type"]))))))

(deftest redact-failure-tolerates-nil
  (is (nil? (privacy/redact-failure nil true))))

;; ---- 6. stamp-sensitive --------------------------------------------------

(deftest stamp-sensitive-adds-flag-when-true
  (is (= {:foo 1 :sensitive? true}
         (privacy/stamp-sensitive {:foo 1} true))))

(deftest stamp-sensitive-omits-when-false
  (is (= {:foo 1}
         (privacy/stamp-sensitive {:foo 1} false))))

;; ---- 7. prepare-emit-tags / prepare-emit-failure -------------------------

(deftest prepare-emit-tags-composes-correctly
  (testing "redaction + sensitivity stamp compose"
    (let [tags {:request-id :r/x
                :url "/x"
                :headers {"Authorization" "Bearer t"}
                :failure {:kind :rf.http/http-5xx
                          :body "secret"}}
          r    (privacy/prepare-emit-tags tags true)]
      (is (= :rf/redacted (get-in r [:headers "Authorization"])))
      (is (= :rf/redacted (get-in r [:failure :body])))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-tags-omits-flag-when-not-sensitive
  (let [tags {:request-id :r/x :url "/x"}
        r    (privacy/prepare-emit-tags tags false)]
    (is (not (contains? r :sensitive?)))))

(deftest prepare-emit-failure-composes-correctly
  (let [failure {:kind :rf.http/http-5xx
                 :status 500
                 :body "internal user data"
                 :headers {"Set-Cookie" "id=42"}}
        r       (privacy/prepare-emit-failure failure true)]
    (is (= :rf/redacted (:body r)))
    (is (= :rf/redacted (get-in r [:headers "Set-Cookie"])))
    (is (true? (:sensitive? r)))))
