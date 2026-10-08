(ns re-frame.http-url-percent-encoded-redaction-cljs-test
  "Percent-encoded query-param names still match the privacy policy.

  A cross-host `*-cljs-test.cljc`, so both the JVM runner and the
  `cljs-test$` node lane run it: the percent-decode is reader-conditional
  (`java.net.URLDecoder` / `js/decodeURIComponent`). A name is compared both
  raw and percent-decoded; the decode is comparison-only (the rebuilt URL keeps
  the raw spelling), and a malformed escape falls back to the raw name and never
  throws."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.http.url :as rf.http.url]))

(deftest redact-url-encoded-frame-extra-name-value-redacted
  (testing "a registration-declared carrier name, percent-encoded, has its value redacted"
    (let [extras #{"shop_token"}
          [redacted any?] (rf.http.url/redact-url-query-string
                            "https://api.example.com/x?shop%5Ftoken=SECRET&page=2"
                            false extras)]
      (is (= "https://api.example.com/x?shop%5Ftoken=:rf/redacted&page=2" redacted))
      (is (true? any?)))))

(deftest redact-url-malformed-escape-on-other-param-still-redacts-real-denylist-hit
  (testing "a malformed escape elsewhere in the URL does not stop an encoded
            default-denylist name from being redacted"
    (let [[redacted any?] (rf.http.url/redact-url-query-string
                            "https://api.example.com/x?weird%5=v&api%5Fkey=SECRET"
                            false)]
      (is (= "https://api.example.com/x?weird%5=v&api%5Fkey=:rf/redacted" redacted))
      (is (true? any?)))))
