(ns re-frame.http-url-percent-encoded-redaction-cljs-test
  "rf2-065xo finding 2 — percent-encoded query-param NAMES must be
  redacted (Spec 014 §Privacy). Cross-host `*-cljs-test.cljc` so BOTH
  cognitect.test-runner (JVM `clojure -M:test`) and shadow-cljs
  (`npm run test:cljs`, `cljs-test$` ns-regexp) discover it — the
  percent-decode-for-comparison logic is reader-conditional
  (`java.net.URLDecoder` on JVM, `js/decodeURIComponent` on CLJS) and is
  host-symmetric by design, so one source asserted on both runtimes is
  the right shape.

  Defect (closed by this test): `redact-query-param` compared the RAW
  query-param name to the denylist, so a percent-encoded denylisted name
  — `api%5Fkey` (= `api_key`), `%61ccess_token` (= `access_token`), an
  app-declared `shop%5Ftoken` (= `shop_token`) — read as a
  non-sensitive raw string and leaked its VALUE into `:rf.http/*` trace
  events unless the whole request was marked `:sensitive?`. Encoded auth
  tokens reached logs/tooling — squarely the AI/MCP + logs/traces
  threat model.

  Fix: the redactor consults `sensitive-query-param-name?`, which
  compares both the RAW spelling AND the percent-DECODED spelling
  (case-insensitively). Decode is comparison-only — the rebuilt URL
  preserves the original raw param spelling; only the value becomes
  `:rf/redacted`. A malformed escape decodes to nil and falls back to
  the raw-name match path — redaction is total and never throws."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.http.url :as url]))

;; rf2-ppkh3v — app-specific carriers moved to FRAME policy (EP-0015 §3);
;; the process-global `clear-sensitive-query-params!` fixture is gone. The
;; frame-extension cases below pass the carrier set explicitly as the
;; `frame-extras` arg, so no per-test cleanup is needed.

;; ---------------------------------------------------------------------------
;; sensitive-query-param-name? — raw + percent-decoded comparison
;; ---------------------------------------------------------------------------

(deftest encoded-default-denylist-name-matches
  (testing "rf2-065xo — a percent-encoded DEFAULT-denylist name matches via the decoded form"
    ;; api%5Fkey decodes to api_key; %61ccess_token decodes to access_token.
    (is (url/sensitive-query-param-name? "api%5Fkey"))
    (is (url/sensitive-query-param-name? "%61ccess_token"))
    ;; case-insensitive on the decoded form too (%41PI%5FKEY → API_KEY).
    (is (url/sensitive-query-param-name? "%41PI%5FKEY"))
    ;; raw match path unchanged — plain names still match.
    (is (url/sensitive-query-param-name? "api_key"))
    (is (url/sensitive-query-param-name? "access_token"))))

(deftest encoded-frame-extra-denylist-name-matches
  (testing "rf2-065xo / rf2-ppkh3v — a frame-declared carrier name matches when percent-encoded"
    (let [extras #{"shop_token"}]
      ;; shop%5Ftoken decodes to shop_token.
      (is (url/sensitive-query-param-name? "shop%5Ftoken" extras))
      (is (url/sensitive-query-param-name? "shop_token" extras))
      ;; absent frame-extras → the frame carrier no longer matches (decoded or raw)
      (is (not (url/sensitive-query-param-name? "shop%5Ftoken"))
          "without frame-extras the frame carrier does not match")
      ;; defaults are immutable — they match regardless of frame-extras.
      (is (url/sensitive-query-param-name? "api%5Fkey")))))

(deftest non-sensitive-encoded-name-does-not-match
  (testing "rf2-065xo — an encoded NON-denylisted name stays non-sensitive"
    ;; page%5Fnum decodes to page_num — not denylisted.
    (is (not (url/sensitive-query-param-name? "page%5Fnum")))
    (is (not (url/sensitive-query-param-name? "user%5Fid")))))

(deftest malformed-escape-falls-back-to-raw-never-throws
  (testing "rf2-065xo — a malformed percent-escape decodes to nil; matching falls back to the raw name and never throws"
    ;; Truncated / invalid escapes — must not throw on either host.
    (is (false? (url/sensitive-query-param-name? "api%5")))
    (is (false? (url/sensitive-query-param-name? "%zz")))
    (is (false? (url/sensitive-query-param-name? "page%")))
    ;; A denylisted RAW name carrying a trailing malformed escape STILL
    ;; matches via the raw path (the decode bails to nil; raw is consulted
    ;; first and matches the plain segment only if it IS the denylisted
    ;; name). Here `token` is denylisted raw; `token%` is not the raw name
    ;; nor a valid decode, so it correctly does not match — proving no
    ;; crash and a sane fallback.
    (is (false? (url/sensitive-query-param-name? "token%")))
    ;; But a valid-raw denylisted name is unaffected by the decode attempt.
    (is (true? (url/sensitive-query-param-name? "token")))))

(deftest sensitive-query-param-name-tolerates-non-string
  (testing "rf2-065xo — nil / non-string is not sensitive and never throws"
    (is (false? (url/sensitive-query-param-name? nil)))
    (is (false? (url/sensitive-query-param-name? :keyword)))
    (is (false? (url/sensitive-query-param-name? 42)))))

;; ---------------------------------------------------------------------------
;; redact-url-query-string — end-to-end: encoded name's VALUE is redacted
;; ---------------------------------------------------------------------------

(deftest redact-url-encoded-default-name-value-redacted
  (testing "rf2-065xo — a percent-encoded default-denylist param has its VALUE redacted; the raw name spelling is preserved"
    (let [[redacted any?] (url/redact-url-query-string
                            "https://api.example.com/x?api%5Fkey=SECRET&page=2"
                            false)]
      (is (= "https://api.example.com/x?api%5Fkey=:rf/redacted&page=2" redacted)
          "raw name spelling preserved; only the value replaced; non-denylisted page untouched")
      (is (true? any?)
          "the encoded denylisted name is the signal — sensitivity is stamped"))))

(deftest redact-url-encoded-name-leading-escape-value-redacted
  (testing "rf2-065xo — %61ccess_token (= access_token) value is redacted"
    (let [[redacted any?] (url/redact-url-query-string
                            "https://api.example.com/x?%61ccess_token=SECRET&q=hi"
                            false)]
      (is (= "https://api.example.com/x?%61ccess_token=:rf/redacted&q=hi" redacted))
      (is (true? any?)))))

(deftest redact-url-encoded-frame-extra-name-value-redacted
  (testing "rf2-065xo / rf2-ppkh3v — a frame-declared carrier name, percent-encoded, has its value redacted"
    (let [extras #{"shop_token"}
          [redacted any?] (url/redact-url-query-string
                            "https://api.example.com/x?shop%5Ftoken=SECRET&page=2"
                            false extras)]
      (is (= "https://api.example.com/x?shop%5Ftoken=:rf/redacted&page=2" redacted))
      (is (true? any?)))))

(deftest redact-url-malformed-escape-does-not-throw-and-passes-through
  (testing "rf2-065xo — a malformed percent-escape in a non-denylisted name does not throw and is preserved unchanged"
    (let [[redacted any?] (url/redact-url-query-string
                            "https://api.example.com/x?weird%5=v&page=2"
                            false)]
      (is (= "https://api.example.com/x?weird%5=v&page=2" redacted)
          "malformed escape on a non-denylisted name — value preserved, no crash")
      (is (false? any?)))))

(deftest redact-url-malformed-escape-on-other-param-still-redacts-real-denylist-hit
  (testing "rf2-065xo — a malformed escape elsewhere in the URL does not stop a real (encoded) denylisted name from being redacted"
    (let [[redacted any?] (url/redact-url-query-string
                            "https://api.example.com/x?weird%5=v&api%5Fkey=SECRET"
                            false)]
      (is (= "https://api.example.com/x?weird%5=v&api%5Fkey=:rf/redacted" redacted))
      (is (true? any?)
          "redaction is total — the malformed-escape param is left alone and the encoded denylist hit is still redacted"))))
