(ns re-frame.routing-plus-decode-cljs-test
  "Per rf2-9a9ix (Mike-ruled 2026-06-01: `+` stays LITERAL everywhere).

  Two regressions, both cross-host:

  1. `url-decode` was host-DIVERGENT for `+`. On JVM `java.net.URLDecoder/
     decode` is the `application/x-www-form-urlencoded` decoder, which
     turns a bare `+` into a SPACE; on CLJS `js/decodeURIComponent` leaves
     `+` LITERAL. The same URL therefore produced a different `:params` /
     `:query` slice on JVM (SSR) vs CLJS (browser) — the Spec 011
     hydration-mismatch class. The fix makes JVM match CLJS: `+` is a
     literal on BOTH hosts. `%2B` still decodes to `+`, `%20` (and a real
     space) still decode to a space, on both hosts.

  2. A spurious empty query-key `{\"\" \"\"}` was injected by a trailing
     `?`, a leading `&`, or a doubled `&&`. The query parser now skips
     blank pairs.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the cognitect
  JVM runner (`.*-test$`) and the shadow-cljs `:node-test` build
  (`cljs-test$`). The assertions are the SAME on both hosts — that
  host-symmetry IS the contract (Spec 012 §`+` is a literal)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as routing]
   [re-frame.routing.url :as url]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; The cross-host runtime-reset fixture (rf2-... test-support seam):
;; snapshot/restore the registrar around each test (so routing.cljc's
;; ns-load-time `:rf.route/*` registrations survive), install the
;; per-host substrate adapter, and reset the routing id-counters so the
;; nav-token / pending-nav allocators are deterministic.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn routing/reset-counters!}))

;; ---- url-decode: `+` is a literal on BOTH hosts --------------------------

(deftest url-decode-plus-is-literal-host-symmetric
  (testing "url-decode leaves a bare `+` as a LITERAL `+` on every host
            (NOT a space) — JVM matches decodeURIComponent (rf2-9a9ix)"
    (is (= "a+b" (url/url-decode "a+b"))
        "a bare `+` decodes to a literal `+`, not a space")
    (is (= "+" (url/url-decode "+"))
        "a lone `+` decodes to a literal `+`")
    (is (= "1+2=3" (url/url-decode "1%2B2%3D3"))
        "`%2B` decodes to `+` and `%3D` to `=` (the percent-escaped forms)"))
  (testing "real spaces still decode from %20 (and a literal space) on
            every host — only the `+`→space form-urlencoded swap is removed"
    (is (= "a b" (url/url-decode "a%20b"))
        "`%20` decodes to a space")
    (is (= "a b" (url/url-decode "a b"))
        "a literal space stays a space")
    (is (= "a + b" (url/url-decode "a%20%2B%20b"))
        "mixed: %20 → space, %2B → literal +")))

;; ---- match-url: `+` literal in path captures + query ---------------------

(deftest match-url-plus-literal-in-path-capture
  (testing "a `+` in a path-capture segment decodes to a literal `+`
            on every host (RFC-3986 path semantics) — rf2-9a9ix"
    (rf/reg-route :route/files {} "/files/:name")
    (let [m (routing/match-url "/files/a+b")]
      (is (some? m) "the route matches")
      (is (= "a+b" (get-in m [:params :name]))
          "the `+` is preserved as a literal in the captured param"))))

(deftest match-url-plus-literal-in-query
  (testing "a `+` in a query value decodes to a literal `+` on every
            host (NOT a space) — rf2-9a9ix"
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?q=a+b")]
      (is (some? m) "the route matches")
      (is (= "a+b" (get-in m [:query "q"]))
          "the `+` is preserved as a literal in the query value"))))

(deftest match-url-percent-20-is-space-in-query
  (testing "`%20` still decodes to a real space in a query value on every
            host — the fix removes only the `+`→space swap, not %20 decode"
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?q=a%20b")]
      (is (some? m) "the route matches")
      (is (= "a b" (get-in m [:query "q"]))
          "`%20` decodes to a space"))))

;; ---- empty query-key filter ----------------------------------------------

(deftest match-url-trailing-question-mark-no-empty-key
  (testing "a trailing `?` yields an EMPTY :query, not `{\"\" \"\"}`
            (rf2-9a9ix finding 2)"
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?")]
      (is (some? m) "the route matches")
      (is (= {} (:query m))
          "a trailing `?` produces an empty query map, no spurious key"))))

(deftest match-url-doubled-ampersand-no-empty-key
  (testing "a doubled `&&` / leading `&` does not inject a `{\"\" \"\"}`
            pair (rf2-9a9ix finding 2)"
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?a=1&&b=2")]
      (is (some? m) "the route matches")
      (is (= {"a" "1" "b" "2"} (:query m))
          "the empty pair from `&&` is dropped — only real pairs survive"))
    (let [m (routing/match-url "/search?&a=1")]
      (is (= {"a" "1"} (:query m))
          "a leading `&` empty pair is dropped"))))

(deftest match-url-empty-value-still-kept
  (testing "an explicit empty VALUE (`?foo=`) is NOT a blank pair — the
            key survives with an empty-string value (distinct from the
            blank-pair filter above) — rf2-9a9ix regression guard"
    (rf/reg-route :route/search {} "/search")
    (let [m (routing/match-url "/search?foo=")]
      (is (some? m) "the route matches")
      (is (= {"foo" ""} (:query m))
          "`?foo=` keeps the key with an empty-string value"))))
