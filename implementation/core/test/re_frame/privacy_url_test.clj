(ns re-frame.privacy-url-test
  "The ONE Spec 015 URL-carrier scrub, pinned where it lives.

  `re-frame.privacy.url` is core's home for a policy two artefacts need on
  egress boundaries that must fail closed: routing's route-miss `:url` /
  blocked-navigation `:requested-url`
  and SSR's rejected safe-redirect `:location`. It lives in core because
  `implementation/ssr` depends on core alone, and a late-bind to a
  routing-owned copy would FAIL OPEN on a routing-free SSR host. Core is the
  artefact both depend on, so the shared implementation needs no new
  dependency edge and no late-bind — which is what makes the scrub
  unconditional.

  The policy's unit coverage lives here, beside the fn.
  `re-frame.routing-egress-test` covers every case that is about ROUTING
  (which emit sites reach the scrub, what the trace copy looks like); SSR
  keeps its own totality cases over `:location`.

  POSTURE-INDEPENDENT by construction: `redact-url-carriers` is a pure string
  function with no `interop/debug-enabled?` anywhere near it, so this namespace
  runs identically in `clojure -M:test` and in
  `scripts/test-core-prod-gate.sh`. That is the point of it — the scrub is
  production-real, and a suite that only proved it in a dev build would prove
  nothing about the boundary it defends."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.privacy :as rf.privacy]
            [re-frame.privacy.url :as rf.privacy.url]))

(def ^:private sentinel-str (subs (str rf.privacy/redacted-sentinel) 1))  ;; "rf/redacted"

;; ===========================================================================
;; The pure URL-carrier scrub — fast, host-symmetric.
;; ===========================================================================

(deftest redact-url-carriers-scrubs-query-values-and-the-fragment
  (testing "the path and the query KEYS are shape and ride; each query VALUE
            and the whole opaque #fragment are carriers and redact; a bare
            path, a value-less flag key (no `=`, so no secret) and a
            non-string input pass through"
    (are [url expected] (= expected (rf.privacy.url/redact-url-carriers url))
      "/oauth/callback?code=secret123&state=xyz" (str "/oauth/callback?code=" sentinel-str
                                                      "&state=" sentinel-str)
      "/login#access_token=abc.def.ghi"          (str "/login#" sentinel-str)
      "/search?q=ssn-123#tok"                    (str "/search?q=" sentinel-str "#" sentinel-str)
      "/admin/users/42"                          "/admin/users/42"
      "/"                                        "/"
      "/x?debug&token=abc"                       (str "/x?debug&token=" sentinel-str)
      nil                                        nil
      ;; Wrong-SHAPED edges whose output is cosmetically odd but never a
      ;; leak: an empty query keeps its bare `?`, and an empty fragment
      ;; still redacts to the sentinel.
      "/x?"                                      "/x?"
      "/x#"                                      (str "/x#" sentinel-str)
      ;; Edges a parsing-order refactor could turn LEAKY: a trailing `&`
      ;; must not resurrect a raw value, and a `?` INSIDE the fragment must
      ;; not start a live query, so the whole fragment redacts.
      "/x?a=1&"                                  (str "/x?a=" sentinel-str)
      "/x?a=1&&"                                 (str "/x?a=" sentinel-str)
      "/p#a=1?b=2"                               (str "/p#" sentinel-str)
      "/p?q=secret#frag?x=y"                     (str "/p?q=" sentinel-str "#" sentinel-str)
      ;; The policy's deliberate limit: it is a carrier DENY-list over the
      ;; app's own URL space, not a fail-closed projection of a foreign URL.
      ;; Nothing left of the first `?` or `#` is touched, so userinfo, a
      ;; path-borne token and a script URL ride verbatim. A record that ships
      ;; an attacker-authored URL off-box needs the closed allow-list
      ;; (`re-frame.ssr.egress/safe-redirect-record-slots`) instead.
      "https://alice:pw@host/reset/tok-abc"      "https://alice:pw@host/reset/tok-abc"
      "javascript:alert(1)"                      "javascript:alert(1)")))

;; ===========================================================================
;; The tag-slot arity. ONE arity, and the slot is REQUIRED.
;; ===========================================================================

(deftest redact-url-tag-scrubs-the-named-slot-only
  (testing "the named slot is scrubbed; every other slot rides untouched —
            this fn speaks for one slot, and the caller says which"
    (let [out (rf.privacy.url/redact-url-tag
                {:url "/cb?code=secret123" :kind :route :reason :malformed-url}
                :url)]
      (is (= {:url (str "/cb?code=" sentinel-str) :kind :route :reason :malformed-url}
             out))))
  (testing "each caller names its own slot — routing spells it `:url` /
            `:requested-url`, SSR spells it `:location`, and one fn serves all
            three because none of them is a default"
    (is (= (str "/x?t=" sentinel-str)
           (:requested-url (rf.privacy.url/redact-url-tag {:requested-url "/x?t=s"} :requested-url))))
    (is (= (str "/x?t=" sentinel-str)
           (:location (rf.privacy.url/redact-url-tag {:location "/x?t=s"} :location))))))

(deftest redact-url-tag-is-a-no-op-when-the-slot-is-absent
  (testing "an absent slot leaves the map alone — the emit arms differ in which
            slots they populate, and a missing slot is not a nil slot"
    (let [tags {:kind :route :recovery :replaced-with-default}]
      (is (identical? tags (rf.privacy.url/redact-url-tag tags :url))
          "reference-preserved: nothing to scrub, nothing rebuilt")))
  (testing "a nil / non-string value under a PRESENT slot rides back unchanged
            — the scrub is total, so the parse-failure arm cannot throw on it"
    (is (= {:location nil} (rf.privacy.url/redact-url-tag {:location nil} :location)))
    (is (= {:location 42} (rf.privacy.url/redact-url-tag {:location 42} :location)))))
