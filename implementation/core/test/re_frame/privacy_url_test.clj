(ns re-frame.privacy-url-test
  "rf2-6l2nc — the ONE Spec 015 URL-carrier scrub, pinned where it lives.

  `re-frame.privacy.url` is core's home for a policy two artefacts need on
  egress boundaries that must fail closed: routing's route-miss `:url` /
  blocked-navigation `:requested-url` (rf2-n1f4rh / rf2-jfaucw / rf2-ov56u)
  and SSR's rejected safe-redirect `:location` (rf2-6jqa8). It stood as two
  byte-identical copies until rf2-6l2nc, because `implementation/ssr` depends
  on core alone and a late-bind to routing's copy would have FAILED OPEN on a
  routing-free SSR host. Core is the artefact both already depend on, so the
  shared implementation needs no new dependency edge and no late-bind — which
  is what makes the scrub unconditional.

  These cases came from `re-frame.routing-egress-test`, where the policy's
  unit coverage lived while routing owned the code. They are moved rather than
  copied: the fn moved, so its unit tests moved with it. Routing keeps every
  case that is about ROUTING (which emit sites reach the scrub, what the trace
  copy looks like); SSR keeps its own totality cases over `:location`.

  POSTURE-INDEPENDENT by construction: `redact-url-carriers` is a pure string
  function with no `interop/debug-enabled?` anywhere near it, so this namespace
  runs identically in `clojure -M:test` and in
  `scripts/test-core-prod-gate.sh`. That is the point of it — the scrub is
  production-real, and a suite that only proved it in a dev build would prove
  nothing about the boundary it defends."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.privacy :as privacy]
            [re-frame.privacy.url :as url-egress]))

(def ^:private sentinel-str (subs (str privacy/redacted-sentinel) 1))  ;; "rf/redacted"

;; ===========================================================================
;; The pure URL-carrier scrub (rf2-n1f4rh) — fast, host-symmetric.
;; ===========================================================================

(deftest redact-url-carriers-keeps-path-redacts-query-values
  (testing "rf2-n1f4rh: query KEYS are preserved (shape), VALUES redacted"
    (is (= (str "/oauth/callback?code=" sentinel-str "&state=" sentinel-str)
           (url-egress/redact-url-carriers "/oauth/callback?code=secret123&state=xyz"))
        "each query value → rf/redacted; keys + path intact")))

(deftest redact-url-carriers-redacts-fragment-whole
  (testing "rf2-n1f4rh: the whole #fragment is opaque → redacted wholesale"
    (is (= (str "/login#" sentinel-str)
           (url-egress/redact-url-carriers "/login#access_token=abc.def.ghi"))
        "the fragment carrier is redacted entirely")
    (is (= (str "/search?q=" sentinel-str "#" sentinel-str)
           (url-egress/redact-url-carriers "/search?q=ssn-123#tok"))
        "both query values and fragment redacted")))

(deftest redact-url-carriers-bare-path-rides-verbatim
  (testing "rf2-n1f4rh: a path with no query/fragment is not a carrier target"
    (is (= "/admin/users/42" (url-egress/redact-url-carriers "/admin/users/42"))
        "bare path verbatim")
    (is (= "/" (url-egress/redact-url-carriers "/")) "root verbatim")))

(deftest redact-url-carriers-value-less-flag-key-kept
  (testing "rf2-n1f4rh: a value-less flag query key is kept (no = → no secret)"
    (is (= (str "/x?debug&token=" sentinel-str)
           (url-egress/redact-url-carriers "/x?debug&token=abc"))
        "the bare `debug` flag rides; `token=abc` value is redacted")))

(deftest redact-url-carriers-nil-safe
  (testing "a non-string input rides back unchanged"
    (is (nil? (url-egress/redact-url-carriers nil)))))

;; ===========================================================================
;; rf2-vh4lbf — ADVERSARIAL-INPUT battery for redact-url-carriers (review F5).
;;
;; The core cases above cover the happy path; these pin the wrong-SHAPED edge
;; inputs. Every one is redaction-SAFE today (no secret leaks), but each
;; produces a cosmetically odd output a future refactor could turn LEAKY — so
;; they are refactor-fragility guards. The two load-bearing ones (a
;; parsing-order regression COULD expose a value):
;;   - trailing `&`: the empty trailing pair must not resurrect a raw value;
;;   - fragment-before-query ordering (`#a=1?b=2`): the `?` lives INSIDE the
;;     fragment, so the whole fragment must redact wholesale — the query-split
;;     must NOT reach across the `#` boundary and treat `b=2` as a live query.
;; ===========================================================================

(deftest redact-url-carriers-empty-query-rides-bare-question-mark
  (testing "rf2-vh4lbf: `/x?` (empty query) keeps the bare `?` — no pair to
            redact, nothing leaks (shape is cosmetic, not a carrier)"
    (is (= "/x?" (url-egress/redact-url-carriers "/x?"))
        "an empty query string rides as a bare `?` (no `key=value` to scrub)")))

(deftest redact-url-carriers-trailing-ampersand-drops-empty-pair
  (testing "rf2-vh4lbf: `/x?a=1&` (trailing &) redacts the real pair and drops
            the empty trailing pair — no raw value survives the split/rejoin"
    (let [out (url-egress/redact-url-carriers "/x?a=1&")]
      (is (= (str "/x?a=" sentinel-str) out)
          "the real value redacts; the empty trailing pair is dropped (no `&` tail)")
      (is (not (re-find #"=1" out))
          "GUARD: the raw value `1` never survives the trailing-& split")
      ;; Two trailing ampersands collapse the same way — still no raw value.
      (is (not (re-find #"=1" (url-egress/redact-url-carriers "/x?a=1&&")))
          "GUARD: doubled trailing `&` still drops the raw value"))))

(deftest redact-url-carriers-empty-fragment-synthesizes-sentinel
  (testing "rf2-vh4lbf: `/x#` (empty fragment) synthesizes `/x#rf/redacted` —
            cosmetic noise on an empty fragment, but never a leak"
    (is (= (str "/x#" sentinel-str) (url-egress/redact-url-carriers "/x#"))
        "an empty fragment still redacts to the sentinel (the whole fragment is opaque)")))

(deftest redact-url-carriers-question-mark-inside-fragment-redacts-whole
  (testing "rf2-vh4lbf: `/p#a=1?b=2` — the `?` lives INSIDE the fragment, so the
            WHOLE fragment redacts and the query-split never crosses the `#`"
    (let [out (url-egress/redact-url-carriers "/p#a=1?b=2")]
      (is (= (str "/p#" sentinel-str) out)
          "the fragment (incl. its embedded `?b=2`) redacts wholesale; no live query")
      ;; The crucial ordering guard: a parsing-order regression that split on
      ;; `?` BEFORE `#` would treat `b=2` as a live query and could expose a
      ;; fragment value as a raw query value.
      (is (not (re-find #"=2" out))
          "GUARD: the fragment-internal `?b=2` value never escapes as a raw query")
      (is (not (re-find #"a=1" out))
          "GUARD: the fragment-internal `a=1` never escapes raw")))
  (testing "rf2-vh4lbf: a REAL query BEFORE a `?`-bearing fragment scrubs both
            sides correctly (the `#` split precedes the `?` split)"
    (let [out (url-egress/redact-url-carriers "/p?q=secret#frag?x=y")]
      (is (= (str "/p?q=" sentinel-str "#" sentinel-str) out)
          "the real query value redacts; the whole fragment (with its `?x=y`) redacts")
      (is (not (re-find #"secret" out)) "GUARD: the real query secret never rides raw")
      (is (not (re-find #"x=y" out)) "GUARD: the fragment-internal query never escapes"))))

;; ===========================================================================
;; rf2-6l2nc — the tag-slot arity. ONE arity, and the slot is REQUIRED.
;; ===========================================================================

(deftest redact-url-tag-scrubs-the-named-slot-only
  (testing "the named slot is scrubbed; every other slot rides untouched —
            this fn speaks for one slot, and the caller says which"
    (let [out (url-egress/redact-url-tag
                {:url "/cb?code=secret123" :kind :route :reason :malformed-url}
                :url)]
      (is (= (str "/cb?code=" sentinel-str) (:url out)))
      (is (= :route (:kind out)))
      (is (= :malformed-url (:reason out)))))
  (testing "each caller names its own slot — routing spells it `:url` /
            `:requested-url`, SSR spells it `:location`, and one fn serves all
            three because none of them is a default"
    (is (= (str "/x?t=" sentinel-str)
           (:requested-url (url-egress/redact-url-tag {:requested-url "/x?t=s"} :requested-url))))
    (is (= (str "/x?t=" sentinel-str)
           (:location (url-egress/redact-url-tag {:location "/x?t=s"} :location))))))

(deftest redact-url-tag-is-a-no-op-when-the-slot-is-absent
  (testing "an absent slot leaves the map alone — the emit arms differ in which
            slots they populate, and a missing slot is not a nil slot"
    (let [tags {:kind :route :recovery :replaced-with-default}]
      (is (identical? tags (url-egress/redact-url-tag tags :url))
          "reference-preserved: nothing to scrub, nothing rebuilt")))
  (testing "a nil / non-string value under a PRESENT slot rides back unchanged
            — the scrub is total, so the parse-failure arm cannot throw on it"
    (is (= {:location nil} (url-egress/redact-url-tag {:location nil} :location)))
    (is (= {:location 42} (url-egress/redact-url-tag {:location 42} :location)))))

;; ===========================================================================
;; rf2-6l2nc — the policy's DELIBERATE limits, so nobody mistakes it for a
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
           (url-egress/redact-url-carriers "https://alice:pw@host/reset/tok-abc"))
        "credentials in userinfo and a path-borne reset token both survive —
         which is precisely why the always-on safe-redirect record is an
         allow-list over parsed components and not this scrub")
    (is (= "javascript:alert(1)"
           (url-egress/redact-url-carriers "javascript:alert(1)"))
        "the attack string survives intact when it carries no query / fragment
         — the common case, and the one a responder needs to see")))
