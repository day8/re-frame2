(ns re-frame.privacy-url-test
  "The ONE Spec 015 URL-carrier scrub, pinned beside the fn. Routing's
  route-miss `:url` / blocked-navigation `:requested-url` and SSR's rejected
  safe-redirect `:location` both route through it, and it lives in core so the
  scrub is unconditional on a routing-free SSR host. Pure string surgery, so
  this namespace runs identically in the dev lane and the production gate."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.privacy.url :as rf.privacy.url]))

(deftest redact-url-carriers-scrubs-query-values-and-the-fragment
  (testing "the path and the query KEYS ride; each query VALUE and the whole
            opaque #fragment redact; a bare path, a value-less flag key and a
            non-string pass through"
    (are [url expected] (= expected (rf.privacy.url/redact-url-carriers url))
      "/oauth/callback?code=secret123&state=xyz" "/oauth/callback?code=rf/redacted&state=rf/redacted"
      "/login#access_token=abc.def.ghi"          "/login#rf/redacted"
      "/search?q=ssn-123#tok"                    "/search?q=rf/redacted#rf/redacted"
      "/admin/users/42"                          "/admin/users/42"
      "/x?debug&token=abc"                       "/x?debug&token=rf/redacted"
      nil                                        nil
      42                                         42
      "/x?"                                      "/x?"
      "/x#"                                      "/x#rf/redacted"
      ;; Edges a parsing-order refactor could turn LEAKY: a trailing `&` must
      ;; not resurrect a raw value, and a `?` inside the fragment must not
      ;; start a live query.
      "/x?a=1&"                                  "/x?a=rf/redacted"
      "/p#a=1?b=2"                               "/p#rf/redacted")))

(deftest redact-url-tag-scrubs-the-named-slot-only
  (testing "the named slot is scrubbed and every other slot rides untouched"
    (is (= {:url "/cb?code=rf/redacted" :kind :route :reason :malformed-url}
           (rf.privacy.url/redact-url-tag
             {:url "/cb?code=secret123" :kind :route :reason :malformed-url}
             :url))))
  (testing "an absent slot leaves the map itself alone"
    (let [tags {:kind :route :recovery :replaced-with-default}]
      (is (identical? tags (rf.privacy.url/redact-url-tag tags :url))))))
