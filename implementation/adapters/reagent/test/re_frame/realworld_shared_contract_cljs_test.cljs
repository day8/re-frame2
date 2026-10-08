(ns re-frame.realworld-shared-contract-cljs-test
  "Contract tests for the shared Conduit WIRE contract
   (`realworld-shared.schema` + `realworld-shared.http`), the
   transport-neutral definitions both RealWorld examples (`realworld_http/` and
   `realworld_resources/`) build on.

   The contract source is example code (`examples/real-apps/realworld_shared/…`),
   but the suite lives HERE in the adapter test tree per the
   test-free-examples policy — the same posture the shared markdown
   renderer uses (`realworld_markdown_cljs_test.cljs`). Runs under the always-on
   `:node-test` gate (`cljs-test$` matches); these are pure functions + Malli
   data, so no DOM is needed.

   This is the ONE place the transport-neutral contract is pinned. Each app's
   suite (`realworld_cljs_test.cljs`, `realworld_resources_cljs_test.cljs`)
   carries only its own INTEGRATION assertions — that its request builders /
   resource registrations actually thread the shared contract through.

   Four contracts:

   1. WIRE SCHEMA EXAMPLES — each of the seven response envelopes validates
      over a canonical sample of each wire shape (User / Profile / Article /
      Comment), and an adversarial malformed sample does not.
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

(defn- validating
  "The labels of the `[label schema value]` rows whose value validates."
  [rows]
  (keep (fn [[label schema v]] (when (m/validate schema v) label)) rows))

(deftest wire-shapes-reject-adversarial-examples
  (testing "a malformed sample fails the schema"
    (is (= [] (validating
               [["User without the JWT token" ws/User (dissoc sample-user :token)]
                ["Article with a string :favoritesCount (:int expected)"
                 ws/Article (assoc sample-article :favoritesCount "0")]
                ["Article with a bare-string :tagList ([:vector :string] expected)"
                 ws/Article (assoc sample-article :tagList "intro")]
                ["Comment with a string :id (:int expected)" ws/Comment (assoc sample-comment :id "1")]
                ["Profile without :following" ws/Profile (dissoc sample-profile :following)]]))
        "none of these malformed samples validates")))

(deftest the-seven-response-envelopes-validate
  (testing "each of the seven Conduit response envelopes validates its example"
    (let [rows [["UserResponse"     ws/UserResponse     {:user sample-user}]
                ["ProfileResponse"  ws/ProfileResponse  {:profile sample-profile}]
                ["ArticleResponse"  ws/ArticleResponse  {:article sample-article}]
                ["ArticlesResponse" ws/ArticlesResponse {:articles [sample-article] :articlesCount 1}]
                ["ArticlesResponse, :articlesCount optional" ws/ArticlesResponse {:articles []}]
                ["CommentResponse"  ws/CommentResponse  {:comment sample-comment}]
                ["CommentsResponse" ws/CommentsResponse {:comments [sample-comment]}]
                ["TagsResponse"     ws/TagsResponse     {:tags ["intro" "demo"]}]]]
      (is (= (map first rows) (validating rows)) "every envelope validates")))
  (testing "an envelope wrapping a malformed body is invalid"
    (is (= [] (validating
               [["ArticleResponse over a slug-less Article"
                 ws/ArticleResponse {:article (dissoc sample-article :slug)}]
                ["ArticlesResponse over an invalid Article element"
                 ws/ArticlesResponse {:articles [(assoc sample-article :favorited "no")]}]
                ["UserResponse over an email-less User"
                 ws/UserResponse {:user (dissoc sample-user :email)}]]))
        "none of these envelopes validates")))

;; ============================================================================
;; 2. QUERY ENCODING
;; ============================================================================

(deftest query-string-drops-nils-and-encodes-reserved
  (is (= ["" "?author=jake" "?tag=a%20b" "?tag=a%26b%3Dc%23d"]
         (map wh/query-string [{} {:tag nil :author "jake"} {:tag "a b"} {:tag "a&b=c#d"}]))
      "an empty map yields an empty string, not a bare ?; nil-valued params are dropped; a space and the reserved & = # are percent-encoded so they can't corrupt the query"))

;; ============================================================================
;; 3. PAGINATION ARITHMETIC
;; ============================================================================

(deftest page->limit-offset-clamps-to-page-1
  (is (= [{:limit 10 :offset 0} {:limit 10 :offset 0} {:limit 10 :offset 0} {:limit 10 :offset 10}]
         (map wh/page->limit-offset [nil 0 1 2]))
      "nil and 0 clamp to page 1 (offset 0); page 2 is one page-size in"))

(deftest page-count-is-ceil-floored-at-one
  (is (= [1 1 1 2] (map wh/page-count [nil 0 10 11]))
      "nil and empty counts are still one (empty) page, a full page is 1, one over is 2"))

;; ============================================================================
;; 4. RETRY DATA + FAILURE TAXONOMY
;; ============================================================================

(deftest data-fetch-retry-covers-transient-failures-only
  (is (= [#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout} 3 true]
         [(:on wh/data-fetch-retry) (:max-attempts wh/data-fetch-retry)
          (get-in wh/data-fetch-retry [:backoff :jitter])])
      "reads retry the transient failures a second try might fix (never a 4xx,
       whose request shape was valid), three attempts total, with jittered
       backoff so a herd doesn't retry on the same beat"))

(deftest failure->message-projects-every-taxonomy-branch
  (testing "the server's own words win when present: the first {:errors {:body [...]}}
            message, a keyed {:errors {field [...]}} as field: message, or a string
            body verbatim"
    (is (= ["email or password is invalid" "username: has already been taken" "raw upstream text"]
           (map wh/failure->message
                [{:kind :rf.http/http-4xx :status 422
                  :body {:errors {:body ["email or password is invalid"]}}}
                 {:kind :rf.http/http-4xx :status 422
                  :body {:errors {:username ["has already been taken"]}}}
                 {:kind :rf.http/http-5xx :status 502 :body "raw upstream text"}]))))
  (testing "otherwise a category message keyed off the closed :rf.http/* taxonomy;
            the CORS arm wins over the raw browser TypeError text the failure carries"
    (is (= ["Network error — please try again."
            "Blocked by the browser's cross-origin policy — check the API's CORS configuration."
            "Request timed out."
            "Request rejected (status 404)."
            "Server error (status 503)."
            "Couldn't parse server response."
            "custom detail"
            "Unexpected response shape."
            "Request cancelled."]
           (map wh/failure->message
                [{:kind :rf.http/transport}
                 {:kind :rf.http/cors :message "Failed to fetch"}
                 {:kind :rf.http/timeout}
                 {:kind :rf.http/http-4xx :status 404}
                 {:kind :rf.http/http-5xx :status 503}
                 {:kind :rf.http/decode-failure}
                 {:kind :rf.http/accept-failure :detail {:message "custom detail"}}
                 {:kind :rf.http/accept-failure}
                 {:kind :rf.http/aborted}]))))
  (testing "fallbacks: an unknown kind's own :message, then the final catch-all"
    (is (= ["spelled-out message" "Request failed."]
           (map wh/failure->message [{:kind :some/unknown :message "spelled-out message"} {}])))))
