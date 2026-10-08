(ns re-frame.http-privacy-test
  "Unit tests for `re-frame.http.privacy` and its leaves
  `re-frame.http.privacy-headers` and `re-frame.http.url` — Spec 014 §Privacy.
  End-to-end trace redaction is in `re-frame.http-privacy-integration-test`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.fx :as rf.fx]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.http.privacy-headers :as rf.http.privacy-headers]
            [re-frame.http.url :as rf.http.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]))

;; A clean :event / :fx registry per test: `managed-carriers` resolves nil until
;; a test registers `:rf.http/managed` with a `:carriers` block.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:clear-kinds [:event :fx]}))

(defn- reg-managed-carriers! [carriers]
  (rf.fx/reg-fx :rf.http/managed {:carriers carriers} rf.http.managed/managed-handler))

;; ---- the built-in denylists (Spec 014 §Privacy's closed v1 tables) ----------

(deftest default-header-denylist-covers-canonical-set
  (is (= #{"authorization" "proxy-authorization" "cookie" "set-cookie" "x-api-key"
           "x-auth-token" "x-session-token" "x-csrf-token" "x-xsrf-token"
           "authentication" "www-authenticate" "proxy-authenticate"}
         rf.http.privacy-headers/default-header-denylist)))

(deftest default-query-param-denylist-covers-canonical-set
  (is (= #{"api_key" "apikey" "api-key" "access_token" "accesstoken" "auth" "auth_token"
           "authtoken" "token" "key" "secret" "password" "passwd" "session" "session_id"
           "sessionid" "signature" "sig" "hmac"}
         rf.http.url/default-query-param-denylist)))

;; ---- redact-headers ---------------------------------------------------------

(deftest redact-headers-replaces-sensitive-values
  (testing "names match case-insensitively, and a multi-valued (vector) value
            redacts whole"
    (is (= {"Authorization" :rf/redacted
            "Cookie"        :rf/redacted
            "Content-Type"  "application/json"
            "X-Multi"       ["keep-1" "keep-2"]}
           (rf.http.privacy-headers/redact-headers
             {"Authorization" "Bearer abc123"
              "Cookie"        ["a=1" "b=2"]
              "Content-Type"  "application/json"
              "X-Multi"       ["keep-1" "keep-2"]})))))

;; ---- redact-failure ---------------------------------------------------------

;; A string :cause is an interceptor's free-text throw message and can echo a
;; secret; a keyword :cause is a discriminator, never payload.
(deftest redact-failure-redacts-payload-slots-only-when-sensitive
  (are [failure sensitive? slot expected]
       (= expected (slot (rf.http.privacy/redact-failure failure sensitive?)))
    {:kind :rf.http/http-4xx :body "{password: shhh}"}      true  :body      :rf/redacted
    {:kind :rf.http/decode-failure :body-text "raw secret"} true  :body-text :rf/redacted
    {:kind :rf.http/accept-failure :detail {:pii "..."}}    true  :detail    :rf/redacted
    {:kind  :rf.error/http-interceptor-failed
     :cause "token validation failed for Bearer sk-live-abc123"}
    true :cause :rf/redacted
    {:kind :rf.http/decode-failure :cause :too-many-keys}   true  :cause     :too-many-keys
    {:kind :rf.http/http-4xx :body "{password: shhh}"}      false :body      "{password: shhh}"
    {:kind  :rf.error/http-interceptor-failed
     :cause "interceptor :auth/check :before threw"}
    false :cause "interceptor :auth/check :before threw"))

;; ---- project-managed-fx-args ------------------------------------------------
;;
;; The fn core's `:http/project-managed-fx-args` late-bind hook resolves to: it
;; redacts a managed fx's args for the generic fx-arg-bearing trace slots.

(deftest project-managed-fx-args-redacts-sensitive-request
  (testing "a sensitive request's :body and :params become the sentinel,
            denylisted headers redact, and the rest rides through"
    (is (= {:request    {:method  :post
                         :url     "https://api.example.test/login"
                         :headers {"Authorization" :rf/redacted
                                   "Accept"        "application/json"}
                         :body    :rf/redacted
                         :params  :rf/redacted}
            :sensitive? true
            :decode     :json}
           (rf.http.privacy/project-managed-fx-args
             {:request    {:method  :post
                           :url     "https://api.example.test/login"
                           :headers {"Authorization" "Bearer t"
                                     "Accept"        "application/json"}
                           :body    {:password "hunter2"}
                           :params  {:token "abc123"}}
              :sensitive? true
              :decode     :json})))))

(deftest project-managed-fx-args-respects-in-request-flag
  (testing "the per-request `[:request :sensitive?]` sugar redacts too"
    (let [r (rf.http.privacy/project-managed-fx-args
              {:request {:url "/login" :body {:pw "raw"} :sensitive? true}})]
      (is (= :rf/redacted (get-in r [:request :body]))))))

(deftest project-managed-fx-args-not-sensitive-is-fail-open
  (testing "an unflagged request's body rides raw (per-call :sensitive? is the
            signal) while the denylisted header still redacts"
    (let [r (rf.http.privacy/project-managed-fx-args
              {:request {:url     "/user"
                         :headers {"Cookie" "id=42"}
                         :body    {:note "plain"}}})]
      (is (= {:note "plain"} (get-in r [:request :body])))
      (is (= :rf/redacted (get-in r [:request :headers "Cookie"]))))))

;; `:params` is merged onto `:url` only at attempt time, so the projection sees
;; it as a map; each key's wire name (`name` of a keyword, else `str`) meets the
;; same denylist the merged URL would.
(deftest project-managed-fx-args-redacts-denylisted-params-like-the-url
  (is (= {:request {:url    "https://api.example.test/x"
                    :params {:api_key       :rf/redacted
                             "Access_Token" :rf/redacted
                             :user/token    :rf/redacted
                             :page          2
                             :q             "x"}}}
         (rf.http.privacy/project-managed-fx-args
           {:request {:url    "https://api.example.test/x"
                      :params {:api_key       "SECRET-P"
                               "Access_Token" "SECRET-S"
                               :user/token    "SECRET-N"
                               :page          2
                               :q             "x"}}}))))

(deftest prepare-emit-tags-stamps-sensitive-on-params-denylist-hit
  (testing "a denylisted :params name alone redacts and stamps :sensitive?;
            no hit, no stamp"
    (are [params expected]
         (= expected (rf.http.privacy/prepare-emit-tags
                       {:url "https://api.example.test/x" :params params} false))
      {:api_key "S" :page 2}
      {:url "https://api.example.test/x" :params {:api_key :rf/redacted :page 2} :sensitive? true}

      {:page 2}
      {:url "https://api.example.test/x" :params {:page 2}}))
  (testing "carriers apply to :params: an :include redacts, an :except default
            rides verbatim, and carrier names match case-insensitively"
    (reg-managed-carriers! {:query-params {:include ["Shop_Token"] :except ["Token"]}})
    (is (= {:shop_token :rf/redacted :token "T"}
           (:params (rf.http.privacy/prepare-emit-tags
                      {:url "https://api.example.test/x" :params {:shop_token "S" :token "T"}}
                      false))))))

;; The roster is spelled out literally rather than read from
;; `rf.http.encoding/reply-address-keys`: the projection reduces over that
;; roster, so a test iterating it could not see a key dropped from it.
(def ^:private reply-address-keys-literal [:reply-to :on-success :on-failure])

(deftest project-managed-fx-args-classifies-every-reply-address-key
  (testing "every reply-address payload rides its target registration's
            :sensitive declaration"
    (rf.registrar/register! :event ::reply-target {:sensitive [[:password]]})
    (doseq [k reply-address-keys-literal]
      (is (= [::reply-target {:password :rf/redacted :user "ann"}]
             (get (rf.http.privacy/project-managed-fx-args
                    {:request {:method :post :url "https://api.example.test/save"}
                     k        [::reply-target {:password "hunter2" :user "ann"}]})
                  k))
          (str k)))))

(deftest project-managed-fx-args-preserves-nil-reply-addresses
  (testing "an explicit nil (fire-and-forget) survives as nil on
            every reply-address key, never a redaction sentinel"
    (doseq [k reply-address-keys-literal]
      (let [r (rf.http.privacy/project-managed-fx-args
                {:request {:method :get :url "/x"} k nil})]
        (is (contains? r k) (str k " stays present"))
        (is (nil? (get r k)) (str "an explicit nil " k " survives as nil"))))))

;; It runs inside core trace emission, where a throw would drop the event's
;; remaining effects.
(deftest project-managed-fx-args-tolerates-non-map
  (is (= :not-a-map (rf.http.privacy/project-managed-fx-args :not-a-map))))

;; ---- query-param policy and URL redaction -----------------------------------

;; The effective policy is (defaults − :except) ∪ :include, and :include wins
;; over :except; a set is the include-only form.
(deftest query-param-policy-is-defaults-minus-except-plus-include
  (are [policy param-name expected]
       (= expected (rf.http.url/sensitive-query-param? param-name policy))
    nil                                           "shop_token" false
    #{"shop_token"}                               "SHOP_TOKEN" true
    #{"shop_token"}                               "api_key"    true
    {:except #{"token"}}                          "token"      false
    {:except #{"token"}}                          "api_key"    true
    {:include #{"shop_token"} :except #{"token"}} "shop_token" true
    {:include #{"token"} :except #{"token"}}      "token"      true))

;; The splitter is hand-written: only values change, the flag says whether any
;; did, a pair without `=` survives, and the fragment rides verbatim.
(deftest redact-url-query-string-replaces-only-values
  (are [url sensitive? expected]
       (= expected (rf.http.url/redact-url-query-string url sensitive?))
    "https://api.example.com/users/42" false
    ["https://api.example.com/users/42" false]

    "https://api.example.com/x?" false
    ["https://api.example.com/x?" false]

    "https://api.example.com/x?api_key=A&token=B&page=2&secret=C" false
    ["https://api.example.com/x?api_key=:rf/redacted&token=:rf/redacted&page=2&secret=:rf/redacted" true]

    "https://api.example.com/x?orphan&api_key=" false
    ["https://api.example.com/x?orphan&api_key=:rf/redacted" true]

    "https://api.example.com/x?token=abc#k=v&also=x" false
    ["https://api.example.com/x?token=:rf/redacted#k=v&also=x" true]

    "https://api.example.com/x?user_id=42&page=2#section-3" true
    ["https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted#section-3" true]))

;; ---- prepare-emit-tags --------------------------------------------------------

(deftest prepare-emit-tags-composes-correctly
  (testing "a sensitive request's tags redact headers and the nested failure's
            payload, and stamp :sensitive?"
    (is (= {:request-id :r/x
            :url        "/x"
            :headers    {"Authorization" :rf/redacted}
            :failure    {:kind :rf.http/http-5xx :body :rf/redacted}
            :sensitive? true}
           (rf.http.privacy/prepare-emit-tags
             {:request-id :r/x
              :url        "/x"
              :headers    {"Authorization" "Bearer t"}
              :failure    {:kind :rf.http/http-5xx :body "secret"}}
             true)))))

(deftest prepare-emit-tags-stamps-sensitive-on-failure-url-denylist-hit
  (is (= {:url        "/x"
          :failure    {:kind :rf.http/http-5xx
                       :url  "https://api.example.com/x?token=:rf/redacted&page=2"}
          :sensitive? true}
         (rf.http.privacy/prepare-emit-tags
           {:url     "/x"
            :failure {:kind :rf.http/http-5xx
                      :url  "https://api.example.com/x?token=abc&page=2"}}
           false))))

;; ---- managed-HTTP carriers (the `:rf.http/managed` `:carriers` block) --------

(deftest prepare-emit-tags-honours-managed-header-carrier
  (testing "an app-declared header carrier redacts beside the built-in defaults"
    (reg-managed-carriers! {:headers ["X-Honeycomb-Team"]})
    (is (= {"X-Honeycomb-Team" :rf/redacted
            "Authorization"    :rf/redacted
            "Content-Type"     "application/json"}
           (:headers (rf.http.privacy/prepare-emit-tags
                       {:url     "https://api.example.com/x"
                        :headers {"X-Honeycomb-Team" "hc-secret"
                                  "Authorization"    "Bearer abc"
                                  "Content-Type"     "application/json"}}
                       false))))))

(deftest prepare-emit-tags-honours-managed-query-param-carrier
  (testing "an app-declared query-param carrier redacts the value and stamps
            :sensitive? (the name is the signal)"
    (reg-managed-carriers! {:query-params ["shop_token"]})
    (is (= {:url "https://api.example.com/x?shop_token=:rf/redacted&page=2" :sensitive? true}
           (rf.http.privacy/prepare-emit-tags
             {:url "https://api.example.com/x?shop_token=abc&page=2"} false)))))

(deftest prepare-emit-tags-honours-managed-query-param-except
  (testing "an :except keeps that built-in default visible in the app's own
            trace; other defaults still redact"
    (reg-managed-carriers! {:query-params {:except ["token"]}})
    (is (= "https://api.example.com/x?token=abc&api_key=:rf/redacted&page=2"
           (:url (rf.http.privacy/prepare-emit-tags
                   {:url "https://api.example.com/x?token=abc&api_key=SECRET&page=2"}
                   false))))))

(deftest managed-carriers-fail-loud-on-malformed-block
  (testing "a non-string name, an unknown key, a header policy map (headers are
            vector-only) and an unknown policy-map key each fail loud"
    (are [carriers bad-key]
         (= {:rf.error/id :rf.error/bad-classification :bad-key bad-key}
            (do (reg-managed-carriers! carriers)
                (select-keys (try (rf.http.privacy/managed-carriers) nil
                                  (catch clojure.lang.ExceptionInfo e (ex-data e)))
                             [:rf.error/id :bad-key])))
      {:headers [:X-Honeycomb-Team]}                [:carriers :headers]
      {:cookies ["x"]}                              [:carriers :cookies]
      {:headers {:include ["X-Foo"]}}               [:carriers :headers]
      {:query-params {:include ["x"] :bogus ["y"]}} [:carriers :query-params :bogus])))
