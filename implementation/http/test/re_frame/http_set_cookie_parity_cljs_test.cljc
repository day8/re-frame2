(ns re-frame.http-set-cookie-parity-cljs-test
  "rf2-wmdmou — cross-host response-headers SHAPE parity for `Set-Cookie`
  (Spec 014 §Request envelope: `string → string`, or `string → vector of
  strings` for a multi-valued header).

  Named `*-cljs-test.cljc` so BOTH cognitect.test-runner (JVM
  `clojure -M:test`) and shadow-cljs (`npm run test:cljs`, `cljs-test$`
  ns-regexp) discover it — the contract under test is host-symmetric BY
  DESIGN, so a single source asserted on both runtimes is the right shape.

  The defect (a CONFIRMED MEDIUM from the tap2r6 http correctness review):
  the JVM transport's `jvm-headers->map` (rf2-0xvm1) rides every wire line
  of a multi-valued response header as a vector element — so two
  `Set-Cookie` lines decode to a 2-element vector, each cookie preserved
  verbatim. The CLJS transport's `fetch-headers->map` used a bare
  `Headers.forEach` + `js->clj`, and `forEach` keeps `Set-Cookie` OUT of
  its combined view: it yields ONLY THE LAST `Set-Cookie` line, silently
  DROPPING the earlier one(s). A two-cookie 2xx/4xx response therefore
  decoded to a 2-element vector on the JVM but a single (last-only) string
  on CLJS — an undocumented, untraced cross-host divergence that LOSES a
  cookie and contradicts the JVM's RFC-6265-§3-correct shape.

  rf2-wmdmou recovers the unfolded lines on CLJS via
  `Headers.getSetCookie()` so a multi-valued `Set-Cookie` decodes
  IDENTICALLY on both hosts (single → string; multi → a vector of the
  verbatim wire lines, every line preserved)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   #?(:clj  [re-frame.http-transport-jvm :as transport-jvm]
      :cljs [re-frame.http-transport-cljs :as transport-cljs]))
  #?(:clj (:import [java.net.http HttpHeaders]
                   [java.util Map]
                   [java.util.function BiPredicate])))

;; ---------------------------------------------------------------------------
;; Per-host adapter: build the host's native response-headers object from a
;; Clojure `{header-name [wire-line ...]}` map, then run the host's
;; flatten-to-Clojure-map helper. The ASSERTIONS below are identical across
;; hosts — that symmetry is the contract.
;; ---------------------------------------------------------------------------

#?(:clj
   (def ^:private flatten-headers @#'transport-jvm/jvm-headers->map))

#?(:cljs
   (def ^:private flatten-headers @#'transport-cljs/fetch-headers->map))

#?(:clj
   (defn- decode-headers
     "Build a real `java.net.http.HttpHeaders` from `{name [v ...]}` and
     flatten it through the JVM transport's `jvm-headers->map`."
     [m]
     (let [java-map (reduce-kv
                      (fn [^java.util.HashMap acc k vs]
                        (.put acc k (java.util.ArrayList. ^java.util.Collection vs))
                        acc)
                      (java.util.HashMap.)
                      m)
           accept-all (reify BiPredicate (test [_ _ _] true))]
       (flatten-headers (HttpHeaders/of ^Map java-map ^BiPredicate accept-all)))))

#?(:cljs
   (defn- decode-headers
     "Build a real Fetch `Headers` from `{name [v ...]}` (`.append` per wire
     line, mirroring how a response is parsed) and flatten it through the
     CLJS transport's `fetch-headers->map`."
     [m]
     (let [h (js/Headers.)]
       (doseq [[k vs] m
               v vs]
         (.append h k v))
       (flatten-headers h))))

;; ---------------------------------------------------------------------------
;; The parity assertions (identical on both hosts)
;; ---------------------------------------------------------------------------

(deftest single-set-cookie-is-string-cross-host
  (testing "rf2-wmdmou — a single Set-Cookie line decodes to a plain STRING
            on both hosts (the single-valued fast path; no spurious
            1-element vector)"
    (let [out (decode-headers {"Set-Cookie" ["only=1; Path=/"]})]
      (is (= "only=1; Path=/" (get out "set-cookie"))
          "single cookie preserved as a string")
      (is (string? (get out "set-cookie"))
          "shape is string, not a 1-element vector"))))

(deftest multi-set-cookie-is-vector-of-verbatim-lines-cross-host
  (testing "rf2-wmdmou — TWO Set-Cookie lines decode to a 2-element vector of
            the verbatim wire lines on BOTH hosts. Pre-fix the CLJS host
            dropped the FIRST cookie and returned only the last as a string;
            the JVM already rode both as a vector. This is the headline
            cross-host parity regression."
    (let [cookies ["session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
                   "csrf=xyz; Path=/; Expires=Thu, 22 Oct 2026 07:28:00 GMT"]
          out     (decode-headers {"Set-Cookie" cookies})
          v       (get out "set-cookie")]
      (is (vector? v)
          "multi-valued Set-Cookie MUST be a vector on both hosts — comma-
           folding violates RFC 6265 §3 (cookie values embed commas) and
           forEach-folding loses all but the last line")
      (is (= 2 (count v)) "BOTH cookie lines survive (no dropped first cookie)")
      (is (= cookies v)
          "each line preserved verbatim — the comma inside Expires is intact,
           not interpreted as a separator, and the first line is not lost"))))

(deftest no-set-cookie-leaves-map-untouched-cross-host
  (testing "rf2-wmdmou — a response with no Set-Cookie decodes its other
            headers unchanged (the recovery is surgical to Set-Cookie); a
            single non-cookie header stays a string on both hosts"
    (let [out (decode-headers {"Content-Type" ["application/json"]})]
      (is (= "application/json" (get out "content-type")))
      (is (not (contains? out "set-cookie"))
          "no spurious set-cookie key is synthesised"))))
