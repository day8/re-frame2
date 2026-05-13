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
  (privacy/clear-sensitive-query-params!)
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

;; ---- 8. query-param denylist (rf2-2p8wr) ----------------------------------

(deftest default-query-param-denylist-covers-canonical-set
  (testing "the default denylist contains the canonical query-string-auth surface"
    (is (contains? privacy/default-query-param-denylist "api_key"))
    (is (contains? privacy/default-query-param-denylist "access_token"))
    (is (contains? privacy/default-query-param-denylist "token"))
    (is (contains? privacy/default-query-param-denylist "auth"))
    (is (contains? privacy/default-query-param-denylist "key"))
    (is (contains? privacy/default-query-param-denylist "secret"))
    (is (contains? privacy/default-query-param-denylist "password"))
    (is (contains? privacy/default-query-param-denylist "signature"))
    (is (contains? privacy/default-query-param-denylist "session"))))

(deftest sensitive-query-param-is-case-insensitive
  (testing "param-name match ignores case"
    (is (privacy/sensitive-query-param? "api_key"))
    (is (privacy/sensitive-query-param? "API_KEY"))
    (is (privacy/sensitive-query-param? "Api_Key"))
    (is (privacy/sensitive-query-param? "access_token"))
    (is (privacy/sensitive-query-param? "ACCESS_TOKEN"))))

(deftest non-sensitive-query-params-pass
  (testing "ordinary query params are not in the denylist"
    (is (not (privacy/sensitive-query-param? "page")))
    (is (not (privacy/sensitive-query-param? "limit")))
    (is (not (privacy/sensitive-query-param? "q")))
    (is (not (privacy/sensitive-query-param? "id")))
    (is (not (privacy/sensitive-query-param? "user_id")))))

(deftest declare-sensitive-query-param-extends-denylist
  (testing "app-extended denylist composes with defaults"
    (privacy/declare-sensitive-query-param! "shop_token")
    (is (privacy/sensitive-query-param? "shop_token"))
    (is (privacy/sensitive-query-param? "SHOP_TOKEN"))
    ;; defaults still apply
    (is (privacy/sensitive-query-param? "api_key"))
    (privacy/clear-sensitive-query-params!)
    (is (not (privacy/sensitive-query-param? "shop_token")))
    ;; defaults survive the clear
    (is (privacy/sensitive-query-param? "api_key"))))

(deftest sensitive-query-param-tolerates-non-string
  (testing "nil / non-string is not sensitive"
    (is (not (privacy/sensitive-query-param? nil)))
    (is (not (privacy/sensitive-query-param? :keyword)))
    (is (not (privacy/sensitive-query-param? 42)))))

;; ---- 9. redact-url-query-string (rf2-2p8wr) -------------------------------

(deftest redact-url-denylist-replaces-sensitive-values
  (testing "denylisted query-param values become :rf/redacted; non-denylisted preserved"
    (let [[url any?] (privacy/redact-url-query-string
                       "https://api.example.com/users?api_key=SECRET&page=2"
                       false)]
      (is (= "https://api.example.com/users?api_key=:rf/redacted&page=2" url))
      (is (true? any?)))))

(deftest redact-url-sensitive-true-redacts-everything
  (testing "when sensitive? true, ALL params are redacted (broader rule)"
    (let [[url any?] (privacy/redact-url-query-string
                       "https://api.example.com/users?user_id=42&page=2&sort=asc"
                       true)]
      (is (= "https://api.example.com/users?user_id=:rf/redacted&page=:rf/redacted&sort=:rf/redacted"
             url))
      (is (true? any?)))))

(deftest redact-url-no-query-string-unchanged
  (testing "URL with no query string returns unchanged"
    (let [[url any?] (privacy/redact-url-query-string
                       "https://api.example.com/users/42" false)]
      (is (= "https://api.example.com/users/42" url))
      (is (false? any?)))))

(deftest redact-url-no-denylist-hit-unchanged
  (testing "URL with no denylisted params returns unchanged when not sensitive"
    (let [[url any?] (privacy/redact-url-query-string
                       "https://api.example.com/users?page=2&limit=10" false)]
      (is (= "https://api.example.com/users?page=2&limit=10" url))
      (is (false? any?)))))

(deftest redact-url-preserves-fragment
  (testing "fragment is preserved verbatim after the redacted query string"
    (let [[url _] (privacy/redact-url-query-string
                    "https://api.example.com/x?token=abc&page=2#section-3" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2#section-3" url)))))

(deftest redact-url-empty-value-redacted
  (testing "param with empty value still has the value slot replaced"
    (let [[url _] (privacy/redact-url-query-string
                    "https://api.example.com/x?token=&page=2" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2" url)))))

(deftest redact-url-handles-url-encoded-values
  (testing "URL-encoded special chars in values are replaced wholesale, not parsed"
    (let [[url _] (privacy/redact-url-query-string
                    "https://api.example.com/x?api_key=a%20b%26c&page=2" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" url)))))

(deftest redact-url-multiple-denylist-hits
  (testing "all denylisted params in a URL are redacted"
    (let [[url any?] (privacy/redact-url-query-string
                       "https://api.example.com/x?api_key=A&token=B&page=2&secret=C" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&token=:rf/redacted&page=2&secret=:rf/redacted"
             url))
      (is (true? any?)))))

(deftest redact-url-app-extended-denylist
  (testing "app-extended denylist applies on URL redaction"
    (privacy/declare-sensitive-query-param! "shop_token")
    (let [[url _] (privacy/redact-url-query-string
                    "https://api.example.com/x?shop_token=abc&page=2" false)]
      (is (= "https://api.example.com/x?shop_token=:rf/redacted&page=2" url)))))

(deftest redact-url-tolerates-non-string
  (is (= [nil false] (privacy/redact-url-query-string nil false)))
  (is (= [42 false] (privacy/redact-url-query-string 42 false))))

(deftest redact-url-handles-malformed-pair
  (testing "param without `=` is not crashed on"
    (let [[url _] (privacy/redact-url-query-string
                    "https://api.example.com/x?orphan&api_key=SECRET" false)]
      ;; The orphan is not in the denylist and is preserved; api_key is redacted.
      (is (= "https://api.example.com/x?orphan&api_key=:rf/redacted" url)))))

;; ---- 10. redact-request-tags integrates URL redaction --------------------

(deftest redact-request-tags-redacts-url-denylist-always
  (testing "URL with denylisted query param is redacted regardless of :sensitive?"
    (let [tags {:url "https://api.example.com/x?api_key=SECRET&page=2"}
          r    (privacy/redact-request-tags tags false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" (:url r))))))

(deftest redact-request-tags-redacts-all-params-when-sensitive
  (testing "URL gets ALL params redacted when sensitive? is true"
    (let [tags {:url "https://api.example.com/x?user_id=42&page=2"}
          r    (privacy/redact-request-tags tags true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted" (:url r))))))

;; ---- 11. redact-failure integrates URL redaction -------------------------

(deftest redact-failure-redacts-url-denylist-always
  (testing "URL on failure map redacted regardless of :sensitive?"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?token=abc&page=2"}
          r (privacy/redact-failure f false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2" (:url r))))))

(deftest redact-failure-redacts-all-url-params-when-sensitive
  (testing "all URL params redacted on failure when sensitive? true"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?user_id=42&page=2"}
          r (privacy/redact-failure f true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted" (:url r))))))

;; ---- 12. prepare-emit-* stamps :sensitive? on denylist-only hit ----------

(deftest prepare-emit-tags-stamps-sensitive-on-denylist-hit
  (testing "denylisted query-param alone (no per-call :sensitive?) stamps :sensitive?"
    (let [tags {:url "https://api.example.com/x?api_key=SECRET&page=2"}
          r    (privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" (:url r)))
      (is (true? (:sensitive? r))
          "denylist hit alone is signal — :sensitive? stamped"))))

(deftest prepare-emit-tags-stamps-sensitive-on-failure-url-denylist-hit
  (testing "denylisted URL inside a failure map stamps :sensitive? at tags top level"
    (let [tags {:url "/x"
                :failure {:kind :rf.http/http-5xx
                          :url "https://api.example.com/x?token=abc&page=2"}}
          r    (privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2"
             (get-in r [:failure :url])))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-tags-no-stamp-when-no-denylist-and-not-sensitive
  (testing "URL with no denylisted params and no per-call :sensitive? does NOT stamp"
    (let [tags {:url "https://api.example.com/x?page=2&limit=10"}
          r    (privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?page=2&limit=10" (:url r)))
      (is (not (contains? r :sensitive?))))))

(deftest prepare-emit-failure-stamps-sensitive-on-denylist-hit
  (testing "denylisted URL on failure stamps :sensitive? even when not declared sensitive"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?api_key=SECRET&page=2"}
          r (privacy/prepare-emit-failure f false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" (:url r)))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-failure-no-stamp-when-no-denylist
  (testing "non-denylisted URL on failure with sensitive? false does NOT stamp"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?page=2"}
          r (privacy/prepare-emit-failure f false)]
      (is (not (contains? r :sensitive?))))))

(deftest prepare-emit-failure-handler-sensitive-redacts-all-url-params
  (testing "handler-sensitive flag forces ALL URL params redacted even non-denylisted"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?user_id=42&page=2"}
          r (privacy/prepare-emit-failure f true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted" (:url r)))
      (is (true? (:sensitive? r))))))
