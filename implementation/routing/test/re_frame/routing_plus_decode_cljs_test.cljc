(ns re-frame.routing-plus-decode-cljs-test
  "`+` stays LITERAL everywhere.

  Two properties, both cross-host:

  1. `url-decode` is host-SYMMETRIC for `+`. On JVM `java.net.URLDecoder/
     decode` is the `application/x-www-form-urlencoded` decoder, which
     turns a bare `+` into a SPACE; on CLJS `js/decodeURIComponent` leaves
     `+` LITERAL. Decoding with it would make the same URL produce a different `:params` /
     `:query` slice on JVM (SSR) vs CLJS (browser) — the Spec 011
     hydration-mismatch class. So JVM matches CLJS: `+` is a
     literal on BOTH hosts. `%2B` decodes to `+`, `%20` (and a real
     space) decode to a space, on both hosts.

  2. A trailing `?`, a leading `&`, or a doubled `&&` injects no spurious
     empty query-key `{\"\" \"\"}`: the query parser skips
     blank pairs.

  Named `*-cljs-test.cljc` so it is discovered by BOTH the cognitect
  JVM runner (`.*-test$`) and the shadow-cljs `:node-test` build
  (`cljs-test$`). The assertions are the SAME on both hosts — that
  host-symmetry IS the contract (Spec 012 §`+` is a literal)."
  (:require
   #?(:clj  [clojure.test :refer [are deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [are deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   [re-frame.routing.url :as rf.routing.url]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; The cross-host runtime-reset fixture (the test-support seam):
;; snapshot/restore the registrar around each test (so routing.cljc's
;; ns-load-time `:rf.route/*` registrations survive), install the
;; per-host substrate adapter, and reset the routing id-counters so the
;; nav-token / pending-nav allocators are deterministic.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---- url-decode: `+` is a literal on BOTH hosts --------------------------

(deftest url-decode-plus-is-literal-host-symmetric
  (testing "url-decode leaves a bare `+` as a LITERAL `+` on every host
            (NOT a space) — JVM matches decodeURIComponent"
    (is (= "a+b" (rf.routing.url/url-decode "a+b"))
        "a bare `+` decodes to a literal `+`, not a space")
    (is (= "+" (rf.routing.url/url-decode "+"))
        "a lone `+` decodes to a literal `+`")
    (is (= "1+2=3" (rf.routing.url/url-decode "1%2B2%3D3"))
        "`%2B` decodes to `+` and `%3D` to `=` (the percent-escaped forms)"))
  (testing "real spaces decode from %20 (and a literal space) on
            every host — only the `+`→space form-urlencoded swap is not applied"
    (is (= "a b" (rf.routing.url/url-decode "a%20b"))
        "`%20` decodes to a space")
    (is (= "a b" (rf.routing.url/url-decode "a b"))
        "a literal space stays a space")
    (is (= "a + b" (rf.routing.url/url-decode "a%20%2B%20b"))
        "mixed: %20 → space, %2B → literal +")))

;; ---- match-url: `+`, `%20` and empty query pairs --------------------------

(deftest match-url-decodes-plus-space-and-empty-pairs-alike-on-every-host
  (testing "match-url reads the same path capture and query map on every host"
    (rf/reg-route :route/files {} "/files/:name")
    (rf/reg-route :route/search {} "/search")
    (are [url path expected] (= expected (get-in (rf.routing/match-url url) path))
      ;; RFC-3986 path semantics: `+` is a literal in a path capture
      "/files/a+b"       [:params :name] "a+b"
      ;; `+` is a literal in a query value too, NOT a space
      "/search?q=a+b"    [:query "q"]    "a+b"
      ;; only `+` is exempt from space-decoding, not `%20`
      "/search?q=a%20b"  [:query "q"]    "a b"
      ;; a trailing `?` yields an EMPTY :query, not `{"" ""}`
      "/search?"         [:query]        {}
      ;; the empty pair from `&&`, or a leading `&`, is dropped
      "/search?a=1&&b=2" [:query]        {"a" "1" "b" "2"}
      "/search?&a=1"     [:query]        {"a" "1"}
      ;; an explicit empty VALUE is not a blank pair — the key survives
      "/search?foo="     [:query]        {"foo" ""})))
