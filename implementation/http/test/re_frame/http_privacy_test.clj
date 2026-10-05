(ns re-frame.http-privacy-test
  "Unit tests for `re-frame.http.privacy` and its sibling leaves —
  Spec 014 §Privacy.

  Covers:
   - Header denylist (default set, case-insensitive, app-extensible) —
     `re-frame.http.privacy-headers`.
   - `redact-headers` walks a map and replaces sensitive header values —
     `re-frame.http.privacy-headers`.
   - Query-param denylist + URL redaction — `re-frame.http.url`.
   - `request-sensitive?` reads per-call and per-request flags
     (there is no handler-meta `:sensitive?`) —
     `re-frame.http.privacy`.
   - `redact-request-tags` / `redact-failure` / `stamp-sensitive` /
     `prepare-emit-tags` / `prepare-emit-failure` compose correctly —
     `re-frame.http.privacy`.

  Integration with the trace surface (sensitive HTTP requests emitting
  redacted trace events end-to-end) is covered in
  `re-frame.http-privacy-integration-test`."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.http.privacy-headers :as rf.http.privacy-headers]
            [re-frame.http.url :as rf.http.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]))

;; The privacy suite is pure-fn (no dispatch), but registers events / fx
;; directly and asserts against a DELIBERATELY clean event + fx registry
;; (e.g. `managed-carriers` resolves nil until a test installs
;; `:rf.http/managed`). `:clear-kinds [:event :fx]` gives that clean slate for
;; each test body, while the canonical registrar snapshot/restore rolls the
;; per-test registrations back on the way out. No adapter is installed — the
;; suite needs no app-db / frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:clear-kinds [:event :fx]}))

;; ---- 1. header denylist ---------------------------------------------------

(deftest default-header-denylist-covers-canonical-set
  (testing "the default denylist contains the canonical bearer / auth surface"
    (is (contains? rf.http.privacy-headers/default-header-denylist "authorization"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "cookie"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "set-cookie"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "x-api-key"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "x-auth-token"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "x-csrf-token"))
    (is (contains? rf.http.privacy-headers/default-header-denylist "proxy-authorization"))))

(deftest sensitive-header-matches-built-in-names-ignoring-case
  (testing "a header name matches the built-in denylist whatever its case;
            ordinary headers and nil / non-string names do not match"
    (are [header-name expected]
         (= expected (rf.http.privacy-headers/sensitive-header? header-name))
      "Authorization" true
      "AUTHORIZATION" true
      "authorization" true
      "Cookie"        true
      "X-API-Key"     true
      "Content-Type"  false
      "Accept"        false
      "User-Agent"    false
      "X-Request-Id"  false
      nil             false
      :keyword        false
      42              false)))

(deftest carrier-cannot-remove-a-built-in-header-default
  (testing "EP-0025 — the built-in header denylist is IMMUTABLE: an app's
            carrier policy is a union-EXTEND of the defaults, never a remove.
            There is no removal API; the only carrier surface is the additive
            `:rf.http/managed` `:carriers {:headers [..]}` extras set.
            Adversarially: a carrier that tries to 'declare' a built-in default
            itself (a no-op redeclaration) cannot DROP it, and extras for
            unrelated headers leave every default intact — a built-in carrier
            stays denylisted regardless of what the app supplies"
    ;; an extras set that is non-empty (even if it names a default, which is a
    ;; harmless redeclaration) cannot turn a default OFF.
    (let [extras-redeclaring-default #{"authorization"}
          extras-unrelated          #{"x-honeycomb-team"}
          extras-empty              #{}]
      (doseq [extras [extras-redeclaring-default extras-unrelated extras-empty nil]]
        (is (rf.http.privacy-headers/sensitive-header? "Authorization" extras)
            "the built-in Authorization default survives every carrier extras shape")
        (is (rf.http.privacy-headers/sensitive-header? "Cookie" extras)
            "the built-in Cookie default survives every carrier extras shape")
        (is (rf.http.privacy-headers/sensitive-header? "Set-Cookie" extras)
            "the built-in Set-Cookie default survives every carrier extras shape"))
      ;; redact-headers (the egress chokepoint) honours the same immutability:
      ;; a built-in default value is scrubbed even when the app supplies
      ;; unrelated extras.
      (let [r (rf.http.privacy-headers/redact-headers
                {"Authorization" "Bearer abc" "X-Honeycomb-Team" "team-1" "Accept" "json"}
                extras-unrelated)]
        (is (= :rf/redacted (get r "Authorization")) "built-in default still redacted")
        (is (= :rf/redacted (get r "X-Honeycomb-Team")) "carrier extra also redacted (extend)")
        (is (= "json" (get r "Accept")) "an unlisted header rides verbatim")))))

;; ---- 2. redact-headers ----------------------------------------------------

(deftest redact-headers-replaces-sensitive-values
  (testing "denylisted header values are replaced with :rf/redacted"
    (let [m {"Authorization" "Bearer abc123"
             "Content-Type"  "application/json"
             "Cookie"        "session=secret"
             "X-Request-Id"  "req-42"}
          r (rf.http.privacy-headers/redact-headers m)]
      (is (= :rf/redacted (get r "Authorization")))
      (is (= :rf/redacted (get r "Cookie")))
      (is (= "application/json" (get r "Content-Type")))
      (is (= "req-42" (get r "X-Request-Id"))))))

(deftest redact-headers-handles-empty-and-nil
  (is (nil? (rf.http.privacy-headers/redact-headers nil)))
  (is (= {} (rf.http.privacy-headers/redact-headers {}))))

(deftest redact-headers-redacts-vector-valued-sensitive-header
  (testing "a denylisted header whose value is a VECTOR (the
            documented multi-valued request-header shape, string → vector
            of strings) is redacted WHOLE to the sentinel, never per-element
            and never leaked. The redactor matches by header NAME, so the
            value shape (scalar vs vector) is irrelevant to whether it is
            scrubbed — a multi-valued `Authorization` / `Cookie` must not
            survive in trace output just because it arrived as a vector."
    (let [m {"Authorization" ["Bearer one" "Bearer two"]
             "Cookie"        ["a=1" "b=2"]
             "X-Multi"       ["keep-1" "keep-2"]}
          r (rf.http.privacy-headers/redact-headers m)]
      (is (= :rf/redacted (get r "Authorization"))
          "the entire vector value is replaced by the sentinel — no element leaks")
      (is (= :rf/redacted (get r "Cookie")))
      (is (= ["keep-1" "keep-2"] (get r "X-Multi"))
          "a non-denylisted multi-valued header keeps its vector value intact"))))

;; ---- 3. request-sensitive? -------------------------------------------------

(deftest request-sensitive-reads-the-per-call-and-per-request-flags
  (testing "a per-call `:sensitive?` on the args map, or a per-request one
            on the `:request` map, opts in; with neither the request is not
            sensitive, because sensitivity is a per-call decision and the fn
            has no other input to read"
    (are [args expected] (= expected (rf.http.privacy/request-sensitive? args))
      {:sensitive? true :request {:url "/x"}} true
      {:request {:url "/x" :sensitive? true}} true
      {:request {:url "/x"}}                  false
      {}                                      false)))

;; ---- 4. redact-request-tags ----------------------------------------------

(deftest redact-request-tags-redacts-the-payload-when-sensitive
  (testing "a sensitive request's :body and :params become the sentinel and
            EVERY :url query value is scrubbed; a request that is not
            sensitive keeps its :body"
    (are [tags sensitive? slot expected]
         (= expected (slot (rf.http.privacy/redact-request-tags tags sensitive?)))
      {:url "/x" :body "{user: ada}"}       true  :body   :rf/redacted
      {:url "/x" :body "regular payload"}   false :body   "regular payload"
      {:url "/x" :params {:token "abc123"}} true  :params :rf/redacted
      {:url "https://api.example.com/x?user_id=42&page=2"}
      true :url "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted")))

;; ---- 5. redact-failure ---------------------------------------------------

;; An interceptor-failure trace carries the interceptor's
;; thrown message at :cause; it is author-controlled free text and can
;; echo a secret the interceptor was handling. It must ride the same
;; sensitive redaction as the response-side slots.
(deftest redact-failure-redacts-payload-slots-only-when-sensitive
  (testing "on a sensitive request each payload slot becomes the sentinel:
            a 4xx :body, a decode-failure :body-text, an accept-failure
            :detail and an interceptor's free-text :cause"
    (are [failure slot]
         (= :rf/redacted (slot (rf.http.privacy/redact-failure failure true)))
      {:kind :rf.http/http-4xx :status 401 :body "{password: shhh}"}        :body
      {:kind :rf.http/decode-failure :body-text "raw secret"}               :body-text
      {:kind :rf.http/accept-failure :detail {:user-id 1 :pii "..."}}       :detail
      {:kind           :rf.error/http-interceptor-failed
       :interceptor-id :auth/check
       :cause          "token validation failed for Bearer sk-live-abc123"} :cause))
  (testing "on a request that is not sensitive the same slots ride verbatim"
    (are [failure slot expected]
         (= expected (slot (rf.http.privacy/redact-failure failure false)))
      {:kind :rf.http/http-4xx :status 401 :body "{password: shhh}"}
      :body "{password: shhh}"

      {:kind           :rf.error/http-interceptor-failed
       :interceptor-id :auth/check
       :cause          "interceptor :auth/check :before threw"}
      :cause "interceptor :auth/check :before threw")))

(deftest redact-failure-always-redacts-headers
  (testing "denylisted headers redacted even when not sensitive"
    (let [f {:kind :rf.http/http-4xx
             :status 401
             :headers {"Set-Cookie" "id=secret"
                       "Content-Type" "text/plain"}}
          r (rf.http.privacy/redact-failure f false)]
      (is (= :rf/redacted (get-in r [:headers "Set-Cookie"])))
      (is (= "text/plain" (get-in r [:headers "Content-Type"]))))))

(deftest redact-failure-tolerates-nil
  (is (nil? (rf.http.privacy/redact-failure nil true))))

(deftest redact-failure-preserves-keyword-cause-discriminator
  (testing "a keyword :cause (e.g. decode-failure's :too-many-keys) is a
            security-relevant signal, NOT secret payload — preserved even
            when sensitive"
    (let [f {:kind :rf.http/decode-failure :cause :too-many-keys :body-text "raw"}
          r (rf.http.privacy/redact-failure f true)]
      (is (= :too-many-keys (:cause r))
          "the keyword discriminator must survive — it is the signal, not the secret")
      (is (= :rf/redacted (:body-text r))
          "the response body still redacts when sensitive"))))

;; ---- 6. project-managed-fx-args -------------------------------------------
;;
;; The fx-args projection core consults through the
;; `:http/project-managed-fx-args` late-bind hook — the DYNAMIC per-call
;; `:sensitive?` redaction for the generic fx-arg-bearing trace slots (the
;; `:rf.event/fx` aggregate + `:rf.fx/handled`-shaped slots). End-to-end
;; trace coverage lives in core's fx_aggregate_classification_cljs_test.

(deftest project-managed-fx-args-redacts-sensitive-request
  (testing "a :sensitive? true args map redacts its request :body (and always
            the denylisted headers), preserving the non-secret shape"
    (let [args {:request    {:method  :post
                             :url     "https://api.example.test/login"
                             :headers {"Authorization" "Bearer t"
                                       "Accept"        "application/json"}
                             :body    {:password "hunter2" :email "a@b.c"}}
                :sensitive? true
                :decode     :json}
          r    (rf.http.privacy/project-managed-fx-args args)]
      (is (= :rf/redacted (get-in r [:request :body]))
          "the whole body redacts on a sensitive request")
      (is (= :rf/redacted (get-in r [:request :headers "Authorization"]))
          "denylisted headers redact")
      (is (= "application/json" (get-in r [:request :headers "Accept"]))
          "non-denylisted headers survive")
      (is (= "https://api.example.test/login" (get-in r [:request :url]))
          "the (query-free) url survives")
      (is (= :json (:decode r))
          "non-request slots ride through"))))

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

;; The query-param denylist is always-on for BOTH spellings of
;; a query parameter. `:params` is merged onto `:url` only at attempt time, so
;; the fx-args projection sees it as a structured map; unredacted there, a
;; denylisted name would ride the `:rf.fx/handled` / `:rf.event/fx` slots raw
;; while the identical name in `:url` is redacted. The expected answer is
;; DERIVED from the producer: merge the params onto the URL exactly as the
;; transport does (`merge-params`), redact that URL, and read which names the
;; URL redactor scrubbed — the `:params` projection must scrub the same names.
(def ^:private denylist-params-fixture
  {:api_key     "SECRET-P"
   "Access_Token" "SECRET-S"
   :user/token  "SECRET-N"
   :page        2
   :q           "x"})

(defn- url-redacted-param-names
  "The wire names the URL redactor scrubs once `params` is merged onto `url`."
  [url params]
  (let [redacted (rf.http.url/redact-url (rf.http.encoding/merge-params url params) false)]
    (into #{} (map second) (re-seq #"[?&]([^?&=#]+)=:rf/redacted" redacted))))

(deftest project-managed-fx-args-redacts-denylisted-params-like-the-url
  (testing "a non-sensitive request's denylisted :params values
            redact in the fx-args projection exactly as the same names do once
            merged into :url; ordinary params ride verbatim"
    (let [url      "https://api.example.test/x"
          expected (url-redacted-param-names url denylist-params-fixture)
          r        (rf.http.privacy/project-managed-fx-args
                     {:request {:url url :params denylist-params-fixture}})
          params   (get-in r [:request :params])
          wire     (fn [k] (if (keyword? k) (name k) (str k)))]
      (is (= #{"api_key" "Access_Token" "token"} expected)
          "control: the URL redactor scrubs the three denylisted wire names")
      (doseq [[k v] denylist-params-fixture]
        (if (contains? expected (wire k))
          (is (= :rf/redacted (get params k))
              (str k " is redacted in :params, as its merged :url spelling is"))
          (is (= v (get params k))
              (str k " is not denylisted and rides verbatim"))))
      (is (= url (get-in r [:request :url])) "the params-free url survives"))))

(deftest prepare-emit-tags-stamps-sensitive-on-params-denylist-hit
  (testing "a denylisted :params name alone stamps :sensitive?,
            exactly as a denylisted :url name does; carriers apply to :params"
    (let [r (rf.http.privacy/prepare-emit-tags
              {:url "https://api.example.test/x" :params {:api_key "S" :page 2}} false)]
      (is (= {:api_key :rf/redacted :page 2} (:params r)))
      (is (true? (:sensitive? r)) "the denylisted name is the signal"))
    (let [r (rf.http.privacy/prepare-emit-tags
              {:url "https://api.example.test/x" :params {:page 2}} false)]
      (is (= {:page 2} (:params r)))
      (is (not (contains? r :sensitive?)) "no denylisted name, no stamp"))
    (rf.fx/reg-fx :rf.http/managed
                  {:carriers {:query-params {:include ["shop_token"] :except ["token"]}}}
                  rf.http.managed/managed-handler)
    (let [r (rf.http.privacy/prepare-emit-tags
              {:url "https://api.example.test/x" :params {:shop_token "S" :token "T"}} false)]
      (is (= {:shop_token :rf/redacted :token "T"} (:params r))
          "an :include carrier redacts, an :except default rides verbatim"))))

;; The cases below are spelled out LITERALLY rather than read from
;; `rf.http.encoding/reply-address-keys`, because that roster is the very
;; thing under test: `project-managed-fx-args` reduces over it, so a test that
;; also iterates it cannot see a key DROPPED from it: delete `:reply-to` from
;; the roster and the projection stops classifying it, while a test iterating
;; the roster would walk the two survivors and pass — an unclassified
;; reply-address payload behind a green suite.
(def ^:private reply-address-keys-literal [:reply-to :on-success :on-failure])

(deftest project-managed-fx-args-classifies-every-reply-address-key
  (testing "the artefact-level fn applies the target registration's
            classification to ALL THREE reply-address keys. Core's
            fx_aggregate_classification_cljs_test drives the same contract
            through the trace projector; this pins the fn the
            `:http/project-managed-fx-args` hook actually resolves to, so the
            two doors cannot drift apart"
    (rf.registrar/register! :event ::reply-target {:sensitive [[:password]]})
    (doseq [k reply-address-keys-literal]
      (let [r (rf.http.privacy/project-managed-fx-args
                {:request {:method :post :url "https://api.example.test/save"}
                 k        [::reply-target {:password "hunter2" :user "ann"}]})]
        (is (= :rf/redacted (get-in r [k 1 :password]))
            (str k " reply-address payload rides the target's :sensitive declaration"))
        (is (= "ann" (get-in r [k 1 :user]))
            (str k " keeps unmarked fields"))
        (is (= ::reply-target (get-in r [k 0]))
            (str k " keeps the event id"))))))

(deftest project-managed-fx-args-preserves-nil-reply-addresses
  (testing "an explicit nil (fire-and-forget) survives as nil on
            every reply-address key, never a redaction sentinel"
    (doseq [k reply-address-keys-literal]
      (let [r (rf.http.privacy/project-managed-fx-args
                {:request {:method :get :url "/x"} k nil})]
        (is (contains? r k) (str k " stays present"))
        (is (nil? (get r k)) (str "an explicit nil " k " survives as nil"))))))

(deftest project-managed-fx-args-tolerates-non-map
  (testing "a non-map args value passes through untouched"
    (is (= :not-a-map (rf.http.privacy/project-managed-fx-args :not-a-map)))
    (is (nil? (rf.http.privacy/project-managed-fx-args nil)))))

;; ---- 7. query-param denylist ----------------------------------------------

(deftest default-query-param-denylist-covers-canonical-set
  (testing "the default denylist contains the canonical query-string-auth surface"
    (is (contains? rf.http.url/default-query-param-denylist "api_key"))
    (is (contains? rf.http.url/default-query-param-denylist "access_token"))
    (is (contains? rf.http.url/default-query-param-denylist "token"))
    (is (contains? rf.http.url/default-query-param-denylist "auth"))
    (is (contains? rf.http.url/default-query-param-denylist "key"))
    (is (contains? rf.http.url/default-query-param-denylist "secret"))
    (is (contains? rf.http.url/default-query-param-denylist "password"))
    (is (contains? rf.http.url/default-query-param-denylist "signature"))
    (is (contains? rf.http.url/default-query-param-denylist "session"))))

(deftest sensitive-query-param-matches-built-in-names-ignoring-case
  (testing "a query-param name matches the built-in denylist whatever its
            case; ordinary params do not match"
    (are [param-name expected]
         (= expected (rf.http.url/sensitive-query-param? param-name))
      "api_key"      true
      "API_KEY"      true
      "Api_Key"      true
      "access_token" true
      "ACCESS_TOKEN" true
      "page"         false
      "limit"        false
      "q"            false
      "id"           false
      "user_id"      false)))

(deftest carrier-extras-extend-query-param-denylist
  (testing "app-declared query-param carriers (EP-0025) compose with defaults"
    (let [extras #{"shop_token"}]
      (is (rf.http.url/sensitive-query-param? "shop_token" extras))
      (is (rf.http.url/sensitive-query-param? "SHOP_TOKEN" extras))
      ;; defaults still apply with extras present
      (is (rf.http.url/sensitive-query-param? "api_key" extras))
      ;; absent extras → only the built-in defaults apply
      (is (not (rf.http.url/sensitive-query-param? "shop_token")))
      (is (not (rf.http.url/sensitive-query-param? "shop_token" nil))))))

;; Query-param policy MAP {:include :except}: an app can SUBTRACT a
;; built-in default (relaxing its OWN dev-trace friction over a harmless
;; routing/pagination key) while still extending with :include. The effective
;; policy is (defaults − except) ∪ include; :include wins over :except.

(deftest query-param-policy-is-defaults-minus-except-plus-include
  (testing ":except removes a built-in default for this app only (without the
            policy the default still matches), :include extends the
            defaults, a name in BOTH stays sensitive (declaring a name
            sensitive is never undone by also excepting it), and an empty
            policy map is defaults-only"
    (are [policy param-name expected]
         (= expected (rf.http.url/sensitive-query-param? param-name policy))
      {:except #{"token"}}                          "token"      false
      {:except #{"token"}}                          "TOKEN"      false
      {:except #{"token"}}                          "api_key"    true
      {:except #{"token"}}                          "signature"  true
      nil                                           "token"      true
      {:include #{"shop_token"} :except #{"token"}} "shop_token" true
      {:include #{"shop_token"} :except #{"token"}} "token"      false
      {:include #{"shop_token"} :except #{"token"}} "api_key"    true
      {:include #{"shop_token"} :except #{"token"}} "page"       false
      {:include #{"token"} :except #{"token"}}      "token"      true
      {}                                            "api_key"    true
      {}                                            "page"       false)))

(deftest redact-url-policy-except-leaves-default-param-visible
  (testing "end-to-end: :except keeps a default param's value
            visible in the app's own dev trace"
    (let [policy {:except #{"token"}}
          [url any?] (rf.http.url/redact-url-query-string
                       "https://api.example.com/list?token=abc&api_key=SECRET&page=2"
                       false policy)]
      ;; token is excepted → visible; api_key still a default → redacted
      (is (= "https://api.example.com/list?token=abc&api_key=:rf/redacted&page=2" url))
      (is (true? any?)))
    (testing "a sensitive? request still redacts EVERY param regardless of :except"
      (let [policy {:except #{"token"}}
            [url _] (rf.http.url/redact-url-query-string
                      "https://api.example.com/list?token=abc&page=2"
                      true policy)]
        (is (= "https://api.example.com/list?token=:rf/redacted&page=:rf/redacted" url))))))

;; ---- 8. redact-url-query-string -------------------------------------------

(deftest redact-url-with-nothing-to-redact-is-unchanged
  (testing "with nothing to redact — no query string, no denylisted name, a
            fragment but no query, a bare `?`, or a nil / non-string value —
            the URL comes back unchanged and the any-redacted? flag is false"
    (are [url] (= [url false] (rf.http.url/redact-url-query-string url false))
      "https://api.example.com/users/42"
      "https://api.example.com/users?page=2&limit=10"
      "https://api.example.com/x#section-3"
      "https://api.example.com/x?"
      nil
      42)))

(deftest redact-url-handles-url-encoded-values
  (testing "URL-encoded special chars in values are replaced wholesale, not parsed"
    (let [[url _] (rf.http.url/redact-url-query-string
                    "https://api.example.com/x?api_key=a%20b%26c&page=2" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" url)))))

(deftest redact-url-multiple-denylist-hits
  (testing "all denylisted params in a URL are redacted"
    (let [[url any?] (rf.http.url/redact-url-query-string
                       "https://api.example.com/x?api_key=A&token=B&page=2&secret=C" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&token=:rf/redacted&page=2&secret=:rf/redacted"
             url))
      (is (true? any?)))))

(deftest redact-url-handles-malformed-pair
  (testing "param without `=` is not crashed on"
    (let [[url _] (rf.http.url/redact-url-query-string
                    "https://api.example.com/x?orphan&api_key=SECRET" false)]
      ;; The orphan is not in the denylist and is preserved; api_key is redacted.
      (is (= "https://api.example.com/x?orphan&api_key=:rf/redacted" url)))))

;; ---- 8b. redact-url-query-string — parser edge cases ----------------------
;;
;; Hand-written split/walk parser territory: a denylisted param with an empty
;; value, and fragments beside a query, with sensitive-true and with `=` / `&`
;; inside the fragment. Fragment-only and bare-`?` URLs are rows of
;; `redact-url-with-nothing-to-redact-is-unchanged`. These cases are
;; precisely where coverage matters most for a hand-rolled splitter.

(deftest redact-url-empty-value-denylisted-param
  (testing "denylisted param with empty value still has value slot replaced"
    (let [[url any?] (rf.http.url/redact-url-query-string
                       "https://api.example.com/x?api_key=&page=2" false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" url))
      (is (true? any?)
          "the denylisted param itself is the signal — flag still set even when value is empty"))))

(deftest redact-url-sensitive-true-preserves-fragment
  (testing "sensitive? true redacts ALL params and still preserves the fragment"
    (let [[url _] (rf.http.url/redact-url-query-string
                    "https://api.example.com/x?user_id=42&page=2#section-3" true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted#section-3" url)))))

(deftest redact-url-fragment-containing-equals-and-ampersand
  (testing "characters inside a fragment that look like query separators are not parsed"
    ;; The fragment is verbatim everything after the first `#` — even if it
    ;; contains `=` or `&` that would look like query syntax. The splitter
    ;; uses index-of `#`, not regex; this asserts the round-trip is clean.
    (let [[url _] (rf.http.url/redact-url-query-string
                    "https://api.example.com/x?token=abc#k=v&also=x" false)]
      (is (= "https://api.example.com/x?token=:rf/redacted#k=v&also=x" url)))))

;; ---- 9. prepare-emit-tags / prepare-emit-failure --------------------------
;;
;; The composers redact and stamp `:sensitive?` together; a denylisted
;; query-param name alone (no per-call `:sensitive?`) is enough to stamp.

(deftest prepare-emit-tags-composes-correctly
  (testing "redaction + sensitivity stamp compose"
    (let [tags {:request-id :r/x
                :url "/x"
                :headers {"Authorization" "Bearer t"}
                :failure {:kind :rf.http/http-5xx
                          :body "secret"}}
          r    (rf.http.privacy/prepare-emit-tags tags true)]
      (is (= :rf/redacted (get-in r [:headers "Authorization"])))
      (is (= :rf/redacted (get-in r [:failure :body])))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-tags-stamps-sensitive-on-denylist-hit
  (testing "denylisted query-param alone (no per-call :sensitive?) stamps :sensitive?"
    (let [tags {:url "https://api.example.com/x?api_key=SECRET&page=2"}
          r    (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" (:url r)))
      (is (true? (:sensitive? r))
          "denylist hit alone is signal — :sensitive? stamped"))))

(deftest prepare-emit-tags-stamps-sensitive-on-failure-url-denylist-hit
  (testing "denylisted URL inside a failure map stamps :sensitive? at tags top level"
    (let [tags {:url "/x"
                :failure {:kind :rf.http/http-5xx
                          :url "https://api.example.com/x?token=abc&page=2"}}
          r    (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?token=:rf/redacted&page=2"
             (get-in r [:failure :url])))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-tags-no-stamp-when-no-denylist-and-not-sensitive
  (testing "URL with no denylisted params and no per-call :sensitive? does NOT stamp"
    (let [tags {:url "https://api.example.com/x?page=2&limit=10"}
          r    (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?page=2&limit=10" (:url r)))
      (is (not (contains? r :sensitive?))))))

(deftest prepare-emit-failure-stamps-sensitive-on-denylist-hit
  (testing "denylisted URL on failure stamps :sensitive? even when not declared sensitive"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?api_key=SECRET&page=2"}
          r (rf.http.privacy/prepare-emit-failure f false)]
      (is (= "https://api.example.com/x?api_key=:rf/redacted&page=2" (:url r)))
      (is (true? (:sensitive? r))))))

(deftest prepare-emit-failure-no-stamp-when-no-denylist
  (testing "non-denylisted URL on failure with sensitive? false does NOT stamp"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?page=2"}
          r (rf.http.privacy/prepare-emit-failure f false)]
      (is (not (contains? r :sensitive?))))))

(deftest prepare-emit-failure-sensitive-redacts-all-url-params
  (testing "the sensitive? flag forces ALL URL params redacted even non-denylisted"
    (let [f {:kind :rf.http/http-5xx
             :status 500
             :url "https://api.example.com/x?user_id=42&page=2"}
          r (rf.http.privacy/prepare-emit-failure f true)]
      (is (= "https://api.example.com/x?user_id=:rf/redacted&page=:rf/redacted" (:url r)))
      (is (true? (:sensitive? r))))))

;; End-to-end: a sensitive request whose interceptor throws.
;; The composed failure trace (the shape `run-interceptor-chain!` hands to
;; `prepare-emit-failure`) must surface :cause redacted, not verbatim.
(deftest prepare-emit-failure-redacts-interceptor-cause-when-sensitive
  (testing "interceptor-failure :cause is redacted on a sensitive request"
    (let [failure {:where          'run-http-interceptor-chain!
                   :recovery       :no-recovery
                   :frame          :app
                   :interceptor-id :auth/check
                   :url            "https://api.example.com/login"
                   :cause          "validation failed: token sk-live-abc123 rejected"}
          r       (rf.http.privacy/prepare-emit-failure failure true)]
      (is (= :rf/redacted (:cause r))
          "the interceptor's thrown message rides redacted, not verbatim")
      (is (true? (:sensitive? r)))
      ;; non-secret locators are still useful for debugging — kept.
      (is (= :auth/check (:interceptor-id r)))
      (is (= :app (:frame r))))))

;; ---- managed-HTTP carriers (EP-0025 §HTTP carriers) ----------------------
;;
;; App-specific carrier names ride the `:rf.http/managed` `reg-fx`
;; registration metadata (`:carriers` block, EP-0025 §HTTP carriers). The
;; `prepare-emit-*` composers resolve them ONCE per emit
;; (`privacy/managed-carriers`) and thread them to the header / URL redactors
;; so app-specific carriers redact alongside the built-in defaults. Carriers
;; are process-global (one registration), so resolution needs no frame id.

(defn- reg-managed-carriers!
  "Re-register `:rf.http/managed` with a `:carriers` block (the app-extension
  shape). The reset fixture's `:clear-kinds [:event :fx]` clears the framework's
  own registration, so each carrier test installs the carriers it needs."
  [carriers]
  (rf.fx/reg-fx :rf.http/managed {:carriers carriers} rf.http.managed/managed-handler))

(deftest managed-carriers-resolves-registration-carriers
  (testing "managed-carriers reads the :rf.http/managed :carriers block"
    (reg-managed-carriers! {:headers      ["X-Honeycomb-Team"]
                            :query-params ["shop_token"]})
    (let [carriers (rf.http.privacy/managed-carriers)]
      ;; names lower-cased for the case-insensitive wire match.
      (is (= #{"x-honeycomb-team"} (:headers carriers)))
      (is (= #{"shop_token"} (:query-params carriers))))
    (testing "a registration with no :carriers block resolves to nil"
      (rf.fx/reg-fx :rf.http/managed {} rf.http.managed/managed-handler)
      (is (nil? (rf.http.privacy/managed-carriers))))
    (testing "an unregistered :rf.http/managed resolves to nil"
      (rf.registrar/clear-all!)
      (is (nil? (rf.http.privacy/managed-carriers))))))

(deftest prepare-emit-tags-honours-managed-header-carrier
  (testing "a managed-HTTP header carrier redacts on a non-sensitive request"
    (reg-managed-carriers! {:headers ["X-Honeycomb-Team"]})
    (let [tags {:url     "https://api.example.com/x"
                :headers {"X-Honeycomb-Team" "hc-secret"
                          "Authorization"    "Bearer abc"
                          "Content-Type"     "application/json"}}
          out  (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= :rf/redacted (get-in out [:headers "X-Honeycomb-Team"]))
          "app-declared carrier redacted")
      (is (= :rf/redacted (get-in out [:headers "Authorization"]))
          "built-in default still redacted")
      (is (= "application/json" (get-in out [:headers "Content-Type"]))
          "ordinary header preserved"))
    (testing "without a :carriers block, only the built-in defaults redact"
      (rf.fx/reg-fx :rf.http/managed {} rf.http.managed/managed-handler)
      (let [tags {:headers {"X-Honeycomb-Team" "hc-secret"
                            "Authorization"    "Bearer abc"}}
            out  (rf.http.privacy/prepare-emit-tags tags false)]
        (is (= "hc-secret" (get-in out [:headers "X-Honeycomb-Team"]))
            "no carrier block → app carrier not consulted")
        (is (= :rf/redacted (get-in out [:headers "Authorization"]))
            "built-in default always applies")))))

(deftest prepare-emit-tags-honours-managed-query-param-carrier
  (testing "a managed-HTTP query-param carrier redacts the URL value + stamps sensitive"
    (reg-managed-carriers! {:query-params ["shop_token"]})
    (let [tags {:url "https://api.example.com/x?shop_token=abc&page=2"}
          out  (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?shop_token=:rf/redacted&page=2" (:url out)))
      (is (true? (:sensitive? out))
          "a carrier query-param hit stamps :sensitive? (the name is the signal)"))
    (testing "without a :carriers block the same param rides unredacted (built-in only)"
      (rf.fx/reg-fx :rf.http/managed {} rf.http.managed/managed-handler)
      (let [tags {:url "https://api.example.com/x?shop_token=abc&page=2"}
            out  (rf.http.privacy/prepare-emit-tags tags false)]
        (is (= "https://api.example.com/x?shop_token=abc&page=2" (:url out)))))))

;; :query-params {:include :except} policy map (carried verbatim
;; on the :rf.http/managed registration).

(deftest managed-carriers-resolves-policy-map
  (testing "the :query-params {:include :except} map form lowers
            to a {:include #{..} :except #{..}} policy (sub-sets lower-cased)"
    (reg-managed-carriers! {:query-params {:include ["Shop_Token"]
                                           :except  ["Token" "Sig"]}})
    (let [carriers (rf.http.privacy/managed-carriers)
          qp       (:query-params carriers)]
      (is (= #{"shop_token"} (:include qp)))
      (is (= #{"token" "sig"} (:except qp))))
    (testing "a policy map of all-empty vectors resolves :query-params to nil"
      (reg-managed-carriers! {:query-params {:include [] :except []}})
      (is (nil? (:query-params (rf.http.privacy/managed-carriers)))))))

(deftest prepare-emit-tags-honours-managed-query-param-except
  (testing "a managed-HTTP :except keeps a built-in default param
            VISIBLE in the app's own dev trace (subtraction is app-local)"
    (reg-managed-carriers! {:query-params {:except ["token"]}})
    (let [tags {:url "https://api.example.com/x?token=abc&api_key=SECRET&page=2"}
          out  (rf.http.privacy/prepare-emit-tags tags false)]
      (is (= "https://api.example.com/x?token=abc&api_key=:rf/redacted&page=2" (:url out))
          "excepted default visible; non-excepted default still redacted"))
    (testing "without a :carriers block the default param redacts as usual"
      (rf.fx/reg-fx :rf.http/managed {} rf.http.managed/managed-handler)
      (let [tags {:url "https://api.example.com/x?token=abc&page=2"}
            out  (rf.http.privacy/prepare-emit-tags tags false)]
        (is (= "https://api.example.com/x?token=:rf/redacted&page=2" (:url out)))))))

;; ---- managed-HTTP carrier shape validation (fail-loud) -------------------

(deftest managed-carriers-fail-loud-on-malformed-block
  (testing "a non-string carrier name fails loud (:rf.error/bad-classification)"
    (reg-managed-carriers! {:headers [:X-Honeycomb-Team]}) ;; keyword, not string
    (let [data (try (rf.http.privacy/managed-carriers) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/bad-classification (:rf.error/id data)))
      (is (= [:carriers :headers] (:bad-key data)))
      (is (= :X-Honeycomb-Team (:bad-carrier data)))))
  (testing "an unknown :carriers key fails loud"
    (reg-managed-carriers! {:cookies ["x"]})
    (let [data (try (rf.http.privacy/managed-carriers) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/bad-classification (:rf.error/id data)))
      (is (= [:carriers :cookies] (:bad-key data)))))
  (testing "the header denylist stays vector-only (no policy-map form)"
    (reg-managed-carriers! {:headers {:include ["X-Foo"]}})
    (let [data (try (rf.http.privacy/managed-carriers) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= [:carriers :headers] (:bad-key data)))))
  (testing "an unknown key inside the :query-params policy map fails loud"
    (reg-managed-carriers! {:query-params {:include ["x"] :bogus ["y"]}})
    (let [data (try (rf.http.privacy/managed-carriers) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= [:carriers :query-params :bogus] (:bad-key data))))))
