(ns re-frame.realworld-shared-contract-cljs-test
  "Contract tests for the shared Conduit WIRE contract
   (`realworld-shared.schema` + `realworld-shared.http` — rf2-fhxwhj), the
   transport-neutral definitions both RealWorld examples (`realworld_http/` and
   `realworld_resources/`) build on.

   The contract source is example code (`examples/real-apps/realworld_shared/…`),
   but the regression suite lives HERE in the adapter test tree per the
   test-free-examples policy (rf2-8cevm) — the same posture the shared markdown
   renderer uses (`realworld_markdown_cljs_test.cljs`). Runs under the always-on
   `:node-test` gate (`cljs-test$` matches); these are pure functions + Malli
   data, so no DOM is needed.

   This is the ONE place the transport-neutral contract is pinned. It replaces
   the per-app copies that previously duplicated the same branch matrices twice
   (`realworld_cljs_test.cljs` + `realworld_resources_cljs_test.cljs`). Each app
   keeps only its own INTEGRATION assertions — that its request builders /
   resource registrations actually thread the shared contract through.

   Four contracts:

   1. WIRE SCHEMA EXAMPLES — a canonical sample of each wire shape
      (User / Profile / Article / Comment) and each of the seven response
      envelopes validates, and an adversarial malformed sample does not.
   2. QUERY ENCODING — `query-string` drops nils, URL-encodes reserved
      characters, and never emits a bare \"?\".
   3. PAGINATION ARITHMETIC — `page->limit-offset` clamps a nil / sub-1 page to
      page 1, and `page-count` is `ceil(count / page-size)` floored at 1.
   4. RETRY DATA + FAILURE TAXONOMY — `data-fetch-retry` retries transport / 5xx
      / timeout but never 4xx, and `failure->message` projects every branch of
      the closed `:rf.http/*` taxonomy (preferring the server's own words)."
  (:require [cljs.test :refer-macros [deftest testing is]]
            [malli.core :as m]
            [realworld-shared.schema :as ws]
            [realworld-shared.http :as wh]))

;; ============================================================================
;; 1. WIRE SCHEMA EXAMPLES
;; ============================================================================

(def ^:private sample-profile
  {:username "eve" :bio "Writes things" :image nil :following false})

(def ^:private sample-user
  {:email "alice@example.com" :token "jwt-abc" :username "alice"
   :bio nil :image nil})

(def ^:private sample-article
  {:slug "hello-conduit" :title "Hello, Conduit" :description "An intro"
   :body "# Hello" :tagList ["intro" "demo"]
   :createdAt "2026-01-01T00:00:00Z" :updatedAt "2026-01-01T00:00:00Z"
   :favorited false :favoritesCount 0 :author sample-profile})

(def ^:private sample-comment
  {:id 1 :createdAt "2026-05-01T00:00:00Z" :updatedAt "2026-05-01T00:00:00Z"
   :body "First!" :author sample-profile})

(deftest wire-shapes-validate-a-canonical-example
  (testing "each canonical wire shape validates its example (Malli)"
    (is (m/validate ws/User sample-user)          "User")
    (is (m/validate ws/Profile sample-profile)    "Profile")
    (is (m/validate ws/Article sample-article)    "Article")
    (is (m/validate ws/Comment sample-comment)    "Comment"))
  (testing "the nil-able string slots accept a present string too"
    (is (m/validate ws/User (assoc sample-user :bio "A bio" :image "http://x/a.png")))
    (is (m/validate ws/Profile (assoc sample-profile :bio "B" :image "http://x/b.png")))))

(deftest wire-shapes-reject-adversarial-examples
  (testing "a malformed sample fails the schema"
    (is (not (m/validate ws/User (dissoc sample-user :token)))
        "User without the JWT token is invalid")
    (is (not (m/validate ws/Article (assoc sample-article :favoritesCount "0")))
        "Article with a string :favoritesCount is invalid (:int expected)")
    (is (not (m/validate ws/Article (assoc sample-article :tagList "intro")))
        "Article with a bare-string :tagList is invalid ([:vector :string] expected)")
    (is (not (m/validate ws/Comment (assoc sample-comment :id "1")))
        "Comment with a string :id is invalid (:int expected)")
    (is (not (m/validate ws/Profile (dissoc sample-profile :following)))
        "Profile without :following is invalid")))

(deftest the-seven-response-envelopes-validate
  (testing "each of the seven Conduit response envelopes validates its example"
    (is (m/validate ws/UserResponse     {:user sample-user})           "UserResponse")
    (is (m/validate ws/ProfileResponse  {:profile sample-profile})     "ProfileResponse")
    (is (m/validate ws/ArticleResponse  {:article sample-article})     "ArticleResponse")
    (is (m/validate ws/ArticlesResponse {:articles [sample-article]
                                         :articlesCount 1})            "ArticlesResponse")
    (is (m/validate ws/ArticlesResponse {:articles []})
        "ArticlesResponse :articlesCount is optional")
    (is (m/validate ws/CommentResponse  {:comment sample-comment})     "CommentResponse")
    (is (m/validate ws/CommentsResponse {:comments [sample-comment]})  "CommentsResponse")
    (is (m/validate ws/TagsResponse     {:tags ["intro" "demo"]})      "TagsResponse"))
  (testing "an envelope wrapping a malformed body is invalid"
    (is (not (m/validate ws/ArticleResponse
                         {:article (dissoc sample-article :slug)}))
        "ArticleResponse over a slug-less Article is invalid")
    (is (not (m/validate ws/ArticlesResponse
                         {:articles [(assoc sample-article :favorited "no")]}))
        "ArticlesResponse over an invalid Article element is invalid")
    (is (not (m/validate ws/UserResponse {:user (dissoc sample-user :email)}))
        "UserResponse over an email-less User is invalid")))

;; ============================================================================
;; 2. QUERY ENCODING
;; ============================================================================

(deftest query-string-drops-nils-and-encodes-reserved
  (is (= "" (wh/query-string {})) "empty map yields \"\", not \"?\"")
  (is (= "" (wh/query-string {:tag nil})) "an all-nil map still yields \"\"")
  (is (= "?author=jake" (wh/query-string {:tag nil :author "jake"}))
      "nil-valued params are dropped; the surviving one is emitted")
  (is (= "?tag=a%20b" (wh/query-string {:tag "a b"}))
      "a space is URL-encoded (not left raw)")
  (is (= "?tag=a%26b%3Dc%23d" (wh/query-string {:tag "a&b=c#d"}))
      "reserved query characters (& = #) are percent-encoded so they can't corrupt the query"))

;; ============================================================================
;; 3. PAGINATION ARITHMETIC
;; ============================================================================

(deftest page->limit-offset-clamps-to-page-1
  (is (= {:limit 10 :offset 0}  (wh/page->limit-offset nil)) "nil page → page 1 → offset 0")
  (is (= {:limit 10 :offset 0}  (wh/page->limit-offset 0))   "page 0 is not a thing → offset 0")
  (is (= {:limit 10 :offset 0}  (wh/page->limit-offset -5))  "a negative page clamps up to page 1")
  (is (= {:limit 10 :offset 0}  (wh/page->limit-offset 1))   "page 1 → offset 0")
  (is (= {:limit 10 :offset 10} (wh/page->limit-offset 2))   "page 2 → offset one page-size in")
  (is (= {:limit 10 :offset 20} (wh/page->limit-offset 3))   "page 3 → offset two page-sizes in"))

(deftest page-count-is-ceil-floored-at-one
  (is (= 10 wh/page-size) "the fixed Conduit page size is 10")
  (is (= 1 (wh/page-count nil)) "nil count → 1 page")
  (is (= 1 (wh/page-count 0))   "empty list is still one (empty) page")
  (is (= 1 (wh/page-count 10))  "exactly one full page → 1")
  (is (= 2 (wh/page-count 11))  "one over a full page → 2 pages")
  (is (= 3 (wh/page-count 25))  "25 items at page-size 10 → 3 pages"))

;; ============================================================================
;; 4. RETRY DATA + FAILURE TAXONOMY
;; ============================================================================

(deftest data-fetch-retry-covers-transient-failures-only
  (is (= #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
         (:on wh/data-fetch-retry))
      "reads retry the transient failures a second try might fix")
  (is (not (contains? (:on wh/data-fetch-retry) :rf.http/http-4xx))
      "a 4xx is never retried — the request shape was valid")
  (is (= 3 (:max-attempts wh/data-fetch-retry)) "three attempts total")
  (is (true? (get-in wh/data-fetch-retry [:backoff :jitter]))
      "backoff carries jitter so a herd doesn't retry on the same beat"))

(deftest failure->message-projects-every-taxonomy-branch
  (testing "the server's own words win when present"
    (is (= "email or password is invalid"
           (wh/failure->message {:kind :rf.http/http-4xx :status 422
                                 :body {:errors {:body ["email or password is invalid"]}}}))
        "{:errors {:body [...]}} surfaces the server's first body message")
    (is (= "username: has already been taken"
           (wh/failure->message {:kind :rf.http/http-4xx :status 422
                                 :body {:errors {:username ["has already been taken"]}}}))
        "a keyed {:errors {field [...]}} projects as \"field: message\"")
    (is (= "raw upstream text"
           (wh/failure->message {:kind :rf.http/http-5xx :status 502 :body "raw upstream text"}))
        "a string body is surfaced verbatim"))
  (testing "otherwise a category message keyed off the closed :rf.http/* taxonomy"
    (is (= "Network error — please try again." (wh/failure->message {:kind :rf.http/transport})))
    (is (= "Request timed out." (wh/failure->message {:kind :rf.http/timeout})))
    (is (= "Request rejected (status 404)." (wh/failure->message {:kind :rf.http/http-4xx :status 404})))
    (is (= "Server error (status 503)." (wh/failure->message {:kind :rf.http/http-5xx :status 503})))
    (is (= "Couldn't parse server response." (wh/failure->message {:kind :rf.http/decode-failure})))
    (is (= "custom detail" (wh/failure->message {:kind :rf.http/accept-failure :detail {:message "custom detail"}})))
    (is (= "Unexpected response shape." (wh/failure->message {:kind :rf.http/accept-failure})))
    (is (= "Request cancelled." (wh/failure->message {:kind :rf.http/aborted}))))
  (testing "fallbacks"
    (is (= "spelled-out message" (wh/failure->message {:kind :some/unknown :message "spelled-out message"}))
        "an unknown kind falls back to the failure's own :message")
    (is (= "Request failed." (wh/failure->message {}))
        "a shapeless failure falls back to the final catch-all")))
