(ns re-frame.http-url-percent-encoded-redaction-cljs-test
  "Percent-encoded query-param names must still match privacy policy.

  This is a cross-host `*-cljs-test.cljc` so both
  cognitect.test-runner (JVM `clojure -M:test`) and shadow-cljs
  (`npm run test:cljs`, `cljs-test$` ns-regexp) discover it — the
  percent-decode-for-comparison logic is reader-conditional
  (`java.net.URLDecoder` on JVM, `js/decodeURIComponent` on CLJS) and is
  host-symmetric by design. The redactor consults
  `sensitive-query-param-name?`, which
  compares both the RAW spelling AND the percent-DECODED spelling
  (case-insensitively). Decode is comparison-only — the rebuilt URL
  preserves the original raw param spelling; only the value becomes
  `:rf/redacted`. A malformed escape decodes to nil and falls back to
  the raw-name match path — redaction is total and never throws."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.http.url :as rf.http.url]))

;; App-specific carrier policy is passed explicitly to this leaf API, so no
;; registration or process cleanup is needed here.

;; ---------------------------------------------------------------------------
;; sensitive-query-param-name? — raw + percent-decoded comparison
;; ---------------------------------------------------------------------------

(deftest encoded-default-denylist-name-matches
  (testing "a percent-encoded DEFAULT-denylist name matches via the decoded form"
    ;; api%5Fkey decodes to api_key; %61ccess_token decodes to access_token.
    (is (rf.http.url/sensitive-query-param-name? "api%5Fkey"))
    (is (rf.http.url/sensitive-query-param-name? "%61ccess_token"))
    ;; case-insensitive on the decoded form too (%41PI%5FKEY → API_KEY).
    (is (rf.http.url/sensitive-query-param-name? "%41PI%5FKEY"))
    ;; raw match path — plain names match too.
    (is (rf.http.url/sensitive-query-param-name? "api_key"))
    (is (rf.http.url/sensitive-query-param-name? "access_token"))))

(deftest non-sensitive-encoded-name-does-not-match
  (testing "an encoded NON-denylisted name stays non-sensitive"
    ;; page%5Fnum decodes to page_num — not denylisted.
    (is (not (rf.http.url/sensitive-query-param-name? "page%5Fnum")))
    (is (not (rf.http.url/sensitive-query-param-name? "user%5Fid")))))

(deftest malformed-escape-falls-back-to-raw-never-throws
  (testing "a malformed percent-escape decodes to nil; matching falls back to the raw name and never throws"
    ;; Truncated / invalid escapes — must not throw on either host.
    (is (false? (rf.http.url/sensitive-query-param-name? "api%5")))
    (is (false? (rf.http.url/sensitive-query-param-name? "%zz")))
    (is (false? (rf.http.url/sensitive-query-param-name? "page%")))
    ;; A denylisted RAW name carrying a trailing malformed escape STILL
    ;; matches via the raw path (the decode bails to nil; raw is consulted
    ;; first and matches the plain segment only if it IS the denylisted
    ;; name). Here `token` is denylisted raw; `token%` is not the raw name
    ;; nor a valid decode, so it correctly does not match — proving no
    ;; crash and a sane fallback.
    (is (false? (rf.http.url/sensitive-query-param-name? "token%")))
    ;; But a valid-raw denylisted name is unaffected by the decode attempt.
    (is (true? (rf.http.url/sensitive-query-param-name? "token")))))

(deftest sensitive-query-param-name-tolerates-non-string
  (testing "nil / non-string is not sensitive and never throws"
    (is (false? (rf.http.url/sensitive-query-param-name? nil)))
    (is (false? (rf.http.url/sensitive-query-param-name? :keyword)))
    (is (false? (rf.http.url/sensitive-query-param-name? 42)))))

;; ---------------------------------------------------------------------------
;; redact-url-query-string — end-to-end: encoded name's VALUE is redacted
;; ---------------------------------------------------------------------------

(deftest redact-url-encoded-frame-extra-name-value-redacted
  (testing "a registration-declared carrier name, percent-encoded, has its value redacted"
    (let [extras #{"shop_token"}
          [redacted any?] (rf.http.url/redact-url-query-string
                            "https://api.example.com/x?shop%5Ftoken=SECRET&page=2"
                            false extras)]
      (is (= "https://api.example.com/x?shop%5Ftoken=:rf/redacted&page=2" redacted))
      (is (true? any?)))))

(deftest redact-url-malformed-escape-on-other-param-still-redacts-real-denylist-hit
  (testing "a malformed escape elsewhere in the URL does not stop a real (encoded) denylisted name from being redacted"
    (let [[redacted any?] (rf.http.url/redact-url-query-string
                            "https://api.example.com/x?weird%5=v&api%5Fkey=SECRET"
                            false)]
      (is (= "https://api.example.com/x?weird%5=v&api%5Fkey=:rf/redacted" redacted))
      (is (true? any?)
          "redaction is total — the malformed-escape param is left alone and the encoded denylist hit is still redacted"))))
