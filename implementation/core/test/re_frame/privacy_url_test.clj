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
      "/x#"                                      (str "/x#" sentinel-str))))

;; ===========================================================================
;; ADVERSARIAL-INPUT battery for redact-url-carriers.
;;
;; The table above ends with the two cosmetic edges; these pin the
;; wrong-SHAPED edge inputs a refactor could turn LEAKY — refactor-fragility
;; guards, where a parsing-order regression COULD expose a value:
;;   - trailing `&`: the empty trailing pair must not resurrect a raw value;
;;   - fragment-before-query ordering (`#a=1?b=2`): the `?` lives INSIDE the
;;     fragment, so the whole fragment must redact wholesale — the query-split
;;     must NOT reach across the `#` boundary and treat `b=2` as a live query.
;; ===========================================================================

(deftest redact-url-carriers-trailing-ampersand-drops-empty-pair
  (testing "`/x?a=1&` (trailing &) redacts the real pair and drops
            the empty trailing pair — no raw value survives the split/rejoin"
    (let [out (rf.privacy.url/redact-url-carriers "/x?a=1&")]
      (is (= (str "/x?a=" sentinel-str) out)
          "the real value redacts; the empty trailing pair is dropped (no `&` tail)")
      (is (not (re-find #"=1" out))
          "GUARD: the raw value `1` never survives the trailing-& split")
      ;; Two trailing ampersands collapse the same way — still no raw value.
      (is (not (re-find #"=1" (rf.privacy.url/redact-url-carriers "/x?a=1&&")))
          "GUARD: doubled trailing `&` still drops the raw value"))))

(deftest redact-url-carriers-question-mark-inside-fragment-redacts-whole
  (testing "`/p#a=1?b=2` — the `?` lives INSIDE the fragment, so the
            WHOLE fragment redacts and the query-split never crosses the `#`"
    (let [out (rf.privacy.url/redact-url-carriers "/p#a=1?b=2")]
      (is (= (str "/p#" sentinel-str) out)
          "the fragment (incl. its embedded `?b=2`) redacts wholesale; no live query")
      ;; The crucial ordering guard: a parsing-order regression that split on
      ;; `?` BEFORE `#` would treat `b=2` as a live query and could expose a
      ;; fragment value as a raw query value.
      (is (not (re-find #"=2" out))
          "GUARD: the fragment-internal `?b=2` value never escapes as a raw query")
      (is (not (re-find #"a=1" out))
          "GUARD: the fragment-internal `a=1` never escapes raw")))
  (testing "a REAL query BEFORE a `?`-bearing fragment scrubs both
            sides correctly (the `#` split precedes the `?` split)"
    (let [out (rf.privacy.url/redact-url-carriers "/p?q=secret#frag?x=y")]
      (is (= (str "/p?q=" sentinel-str "#" sentinel-str) out)
          "the real query value redacts; the whole fragment (with its `?x=y`) redacts")
      (is (not (re-find #"secret" out)) "GUARD: the real query secret never rides raw")
      (is (not (re-find #"x=y" out)) "GUARD: the fragment-internal query never escapes"))))

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

;; ===========================================================================
;; The policy's DELIBERATE limits, so nobody mistakes it for a
;; projection.
;;
;; This is a carrier DENY-list: redact the query values and the fragment, keep
;; everything else. That is right over the app's OWN URL space — the path is a
;; route the app authored, the host is the app's own, and a query KEY names the
;; shape rather than the secret. It is NOT a fail-closed projection of an
;; arbitrary FOREIGN URL, and the assertions below pin exactly what it leaves
;; standing so a reader cannot reach for it on the wrong path. A record that
;; ships an attacker-authored URL off-box needs the closed ALLOW-list instead
;; (`re-frame.ssr.egress/safe-redirect-record-slots`, built FROM its slot set
;; rather than filtered down to it).
;; ===========================================================================

(deftest the-carrier-policy-does-not-reach-left-of-the-first-question-mark
  (testing "userinfo, the path and the host all ride VERBATIM — string surgery
            starts at the first `?` or `#` and this fn makes no claim about
            what is left of it"
    (is (= "https://alice:pw@host/reset/tok-abc"
           (rf.privacy.url/redact-url-carriers "https://alice:pw@host/reset/tok-abc"))
        "credentials in userinfo and a path-borne reset token both survive —
         which is precisely why the always-on safe-redirect record is an
         allow-list over parsed components and not this scrub")
    (is (= "javascript:alert(1)"
           (rf.privacy.url/redact-url-carriers "javascript:alert(1)"))
        "the attack string survives intact when it carries no query / fragment
         — the common case, and the one a responder needs to see")))
