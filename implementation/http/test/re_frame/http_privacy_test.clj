(ns re-frame.http-privacy-test
  "Unit tests for `re-frame.http-privacy` and its sibling leaves —
  Spec 014 §Privacy (rf2-bma05).

  Covers:
   - Header denylist (default set, case-insensitive, app-extensible) —
     `re-frame.http-privacy-headers`.
   - `redact-headers` walks a map and replaces sensitive header values —
     `re-frame.http-privacy-headers`.
   - Query-param denylist + URL redaction — `re-frame.http-url`.
   - `request-sensitive?` reads per-call, per-request, and handler-meta —
     `re-frame.http-privacy`.
   - `redact-request-tags` / `redact-failure` / `stamp-sensitive` /
     `prepare-emit-tags` / `prepare-emit-failure` compose correctly —
     `re-frame.http-privacy`.

  Integration with the trace surface (sensitive HTTP requests emitting
  redacted trace events end-to-end) is covered in
  `re-frame.http-privacy-integration-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http-privacy :as privacy]
            [re-frame.http-privacy-headers :as headers]
            [re-frame.http-url :as url]
            [re-frame.registrar :as registrar]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (headers/clear-sensitive-headers!)
  (url/clear-sensitive-query-params!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- 1. header denylist ---------------------------------------------------

(deftest default-header-denylist-covers-canonical-set
  (testing "the default denylist contains the canonical bearer / auth surface"
    (is (contains? headers/default-header-denylist "authorization"))
    (is (contains? headers/default-header-denylist "cookie"))
    (is (contains? headers/default-header-denylist "set-cookie"))
    (is (contains? headers/default-header-denylist "x-api-key"))
    (is (contains? headers/default-header-denylist "x-auth-token"))
    (is (contains? headers/default-header-denylist "x-csrf-token"))
    (is (contains? headers/default-header-denylist "proxy-authorization"))))

(deftest sensitive-header-is-case-insensitive
  (testing "header-name match ignores case"
    (is (headers/sensitive-header? "Authorization"))
    (is (headers/sensitive-header? "AUTHORIZATION"))
    (is (headers/sensitive-header? "authorization"))
    (is (headers/sensitive-header? "Cookie"))
    (is (headers/sensitive-header? "X-API-Key"))))

(deftest non-sensitive-headers-pass
  (testing "ordinary headers are not in the denylist"
    (is (not (headers/sensitive-header? "Content-Type")))
    (is (not (headers/sensitive-header? "Accept")))
    (is (not (headers/sensitive-header? "User-Agent")))
    (is (not (headers/sensitive-header? "X-Request-Id")))))

(deftest declare-sensitive-header-extends-denylist
  (testing "app-extended denylist composes with defaults"
    (headers/declare-sensitive-header! "X-Honeycomb-Team")
    (is (headers/sensitive-header? "X-Honeycomb-Team"))
    (is (headers/sensitive-header? "x-honeycomb-team"))
    ;; defaults still apply
    (is (headers/sensitive-header? "Authorization"))
    (headers/clear-sensitive-headers!)
    (is (not (headers/sensitive-header? "X-Honeycomb-Team")))
    ;; defaults survive the clear
    (is (headers/sensitive-header? "Authorization"))))

(deftest sensitive-header-tolerates-non-string
  (testing "nil / non-string is not sensitive"
    (is (not (headers/sensitive-header? nil)))
    (is (not (headers/sensitive-header? :keyword)))
    (is (not (headers/sensitive-header? 42)))))

;; ---- 2. redact-headers ----------------------------------------------------

(deftest redact-headers-replaces-sensitive-values
  (testing "denylisted header values are replaced with :rf/redacted"
    (let [m {"Authorization" "Bearer abc123"
             "Content-Type"  "application/json"
             "Cookie"        "session=secret"
             "X-Request-Id"  "req-42"}
          r (headers/redact-headers m)]
      (is (= :rf/redacted (get r "Authorization")))
      (is (= :rf/redacted (get r "Cookie")))
      (is (= "application/json" (get r "Content-Type")))
      (is (= "req-42" (get r "X-Request-Id"))))))

(deftest redact-headers-handles-empty-and-nil
  (is (nil? (headers/redact-headers nil)))
  (is (= {} (headers/redact-headers {}))))

(deftest redact-headers-handles-mixed-case-keys
  (testing "header-name match ignores case on the map key"
    (let [m {"AUTHORIZATION" "Bearer xyz"
             "set-cookie"    "id=1"
             "Set-cookie"    "id=2"}
          r (headers/redact-headers m)]
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
    (is (contains? url/default-query-param-denylist "api_key"))
    (is (contains? url/default-query-param-denylist "access_token"))
    (is (contains? url/default-query-param-denylist "token"))
    (is (contains? url/default-query-param-denylist "auth"))
    (is (contains? url/default-query-param-denylist "key"))
    (is (contains? url/default-query-param-denylist "secret"))
    (is (contains? url/default-query-param-denylist "password"))
    (is (contains? url/default-query-param-denylist "signature"))
    (is (contains? url/default-query-param-denylist "session"))))

(deftest sensitive-query-param-is-case-insensitive
  (testing "param-name match ignores case"
    (is (url/sensitive-query-param? "api_key"))
    (is (url/sensitive-query-param? "API_KEY"))
    (is (url/sensitive-query-param? "Api_Key"))
    (is (url/sensitive-query-param? "access_token"))
    (is (url/sensitive-query-param? "ACCESS_TOKEN"))))

(deftest non-sensitive-query-params-pass
  (testing "ordinary query params are not in the denylist"
    (is (not (url/sensitive-query-param? "page")))
    (is (not (url/sensitive-query-param? "limit")))
    (is (not (url/sensitive-query-param? "q")))
    (is (not (url/sensitive-query-param? "id")))
    (is (not (url/sensitive-query-param? "user_id")))))

(deftest declare-sensitive-query-param-extends-denylist
  (testing "app-extended denylist composes with defaults"
    (url/declare-sensitive-query-param! "shop_token")
    (is (url/sensitive-query-param? "shop_token"))
    (is (url/sensitive-query-param? "SHOP_TOKEN"))
    ;; defaults still apply
    (is (url/sensitive-query-param? "api_key"))
    (url/clear-sensitive-query-params!)
    (is (not (url/sensitive-query-param? "shop_token")))
    ;; defaults survive the clear
    (is (url/sensitive-query-param? "api_key"))))

(deftest sensitive-query-param-tolerates-non-string
  (testing "nil / non-string is not sensitive"
    (is (not (url/sensitive-query-param? nil)))
    (is (not (url/sensitive-query-param? :keyword)))
    (is (not (url/sensitive-query-param? 42)))))

;; ---- 9. redact-url-query-string (rf2-2p8wr) -------------------------------

(deftest redact-url-denylist-replaces-sensitive-values
  (testing "denylisted query-param values become :rf/redacted; non-denylisted preserved"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/users?api_key=SECRET&page=2"
                       false)]
      (is (= "https://api.example.com/users?api_key=:rf/redacted&page=2" url))
      (is (true? any?)))))

(deftest redact-url-sensitive-true-redacts-everything
  (testing "when sensitive? true, ALL params are redacted (broader rule)"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/users?user_id=42&page=2&sort=asc"
                       true)]
      (is (= "https://api.example.com/users?user_id=:rf/redacted&page=:rf/redacted&sort=:rf/redacted"
             url))
      (is (true? any?)))))

(deftest redact-url-no-query-string-unchanged
  (testing "URL with no query string returns unchanged"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/users/42" false)]
      (is (= "https://api.example.com/users/42" url))
      (is (false? any?)))))

(deftest redact-url-no-denylist-hit-unchanged
  (testing "URL with no denylisted params returns unchanged when not sensitive"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/users?page=2&limit=10" false)]
      (is (= "https://api.example.com/users?page=2&limit=10" url))
      (is (false? any?)))))

(deftest redact-url-preserves-fragment
  (testing "fragment is preserved verbatim after the redacted query string"
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?token=abc&page=2#section-3" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2#section-3" url)))))

(deftest redact-url-empty-value-redacted
  (testing "param with empty value still has the value slot replaced"
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?token=&page=2" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2" url)))))

(deftest redact-url-handles-url-encoded-values
  (testing "URL-encoded special chars in values are replaced wholesale, not parsed"
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?api_key=a%20b%26c&page=2" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" url)))))

(deftest redact-url-multiple-denylist-hits
  (testing "all denylisted params in a URL are redacted"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/x?api_key=A&token=B&page=2&secret=C" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&token=:rf/redacted&page=2&secret=:rf/redacted"
             url))
      (is (true? any?)))))

(deftest redact-url-app-extended-denylist
  (testing "app-extended denylist applies on URL redaction"
    (url/declare-sensitive-query-param! "shop_token")
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?shop_token=abc&page=2" false)]
      (is (= "https://api.example.com/x?shop_token=:rf/redacted&page=2" url)))))

(deftest redact-url-tolerates-non-string
  (is (= [nil false] (url/redact-url-query-string nil false)))
  (is (= [42 false] (url/redact-url-query-string 42 false))))

(deftest redact-url-handles-malformed-pair
  (testing "param without `=` is not crashed on"
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?orphan&api_key=SECRET" false)]
      ;; The orphan is not in the denylist and is preserved; api_key is redacted.
      (is (= "https://api.example.com/x?orphan&api_key=:rf/redacted" url)))))

;; ---- 9b. redact-url-query-string — round-2 audit edge cases (rf2-e5h1b) --
;;
;; Hand-written split/walk parser territory: fragment-only URLs (no
;; query), empty `?`-only query string, fragments alongside denylisted
;; params, and sensitive-true with a fragment present. These cases are
;; precisely where coverage matters most for a hand-rolled splitter.

(deftest redact-url-fragment-only-no-query
  (testing "URL with a fragment but no query string is returned unchanged"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/x#section-3" false)]
      (is (= "https://api.example.com/x#section-3" url))
      (is (false? any?)))))

(deftest redact-url-empty-query-string
  (testing "URL with `?` but no params is returned unchanged"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/x?" false)]
      (is (= "https://api.example.com/x?" url))
      (is (false? any?)))))

(deftest redact-url-denylisted-param-with-fragment
  (testing "denylisted param value redacted; fragment preserved verbatim"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/x?api_key=SECRET#section-3" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted#section-3" url))
      (is (true? any?)))))

(deftest redact-url-empty-value-denylisted-param
  (testing "denylisted param with empty value still has value slot replaced"
    (let [[url any?] (url/redact-url-query-string
                       "https://api.example.com/x?api_key=&page=2" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" url))
      (is (true? any?)
          "the denylisted param itself is the signal — flag still set even when value is empty"))))

(deftest redact-url-sensitive-true-preserves-fragment
  (testing "sensitive? true redacts ALL params and still preserves the fragment"
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?user_id=42&page=2#section-3" true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted#section-3" url)))))

(deftest redact-url-fragment-containing-equals-and-ampersand
  (testing "characters inside a fragment that look like query separators are not parsed"
    ;; The fragment is verbatim everything after the first `#` — even if it
    ;; contains `=` or `&` that would look like query syntax. The splitter
    ;; uses index-of `#`, not regex; this asserts the round-trip is clean.
    (let [[url _] (url/redact-url-query-string
                    "https://api.example.com/x?token=abc#k=v&also=x" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted#k=v&also=x" url)))))

;; ---- 9c. redact-url convenience wrapper (rf2-e5h1b) ----------------------
;;
;; `redact-url` is the single-value form used inside generic tag walkers
;; (`redact-url-in`) that don't need the any-redacted? flag. Pin the
;; wrapper's shape so a refactor that swaps the underlying impl doesn't
;; silently change the caller's reading.

(deftest redact-url-wrapper-returns-string
  (testing "redact-url returns only the redacted URL string (not the [url flag] tuple)"
    (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2"
           (url/redact-url
             "https://api.example.com/x?api_key=SECRET&page=2" false))
        "denylist hit — value redacted")
    (is (= "https://api.example.com/x?page=2"
           (url/redact-url
             "https://api.example.com/x?page=2" false))
        "no denylist hit — unchanged")
    (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted"
           (url/redact-url
             "https://api.example.com/x?user_id=42&page=2" true))
        "sensitive? true — all params redacted")
    (is (nil? (url/redact-url nil false))
        "nil input passes through")
    (is (= "https://api.example.com/x#frag"
           (url/redact-url "https://api.example.com/x#frag" false))
        "fragment-only URL passes through")))

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
