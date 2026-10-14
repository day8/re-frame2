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

(deftest percent-encoded-param-names-meet-the-policy
  (testing "an encoded default-denylist name and an encoded carrier name both
            redact, and a malformed escape elsewhere neither throws nor stops
            them"
    (is (= ["https://api.example.com/x?weird%5=v&api%5Fkey=:rf/redacted&shop%5Ftoken=:rf/redacted&page=2" true]
           (rf.http.url/redact-url-query-string
             "https://api.example.com/x?weird%5=v&api%5Fkey=SECRET&shop%5Ftoken=S&page=2"
             false #{"shop_token"})))))
