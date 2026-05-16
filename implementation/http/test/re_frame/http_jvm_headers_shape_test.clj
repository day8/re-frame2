(ns re-frame.http-jvm-headers-shape-test
  "rf2-0xvm1 — JVM response headers shape pin.

  The JVM transport's `jvm-headers->map` flattens
  `java.net.http.HttpHeaders` into a Clojure map at the
  response-decode boundary. Per Spec 014 §Request envelope the headers
  map is `string → string (or string → vector of strings for
  multi-valued)`. Before rf2-0xvm1 the helper comma-joined every multi-
  valued header (`(str/join \",\" vs)`); that breaks `Set-Cookie`
  because cookie attribute values legally contain commas
  (`Expires=Wed, 21 Oct 2026 ...`), so comma-joining N lines produces a
  single unparseable string.

  Cookie shape decision (Mike — option a): single-valued headers stay
  `string`; multi-valued headers (every header where the JDK saw more
  than one wire instance) become `vector-of-strings`, preserving the
  original lines verbatim. RFC 6265 §3 forbids comma-folding
  `Set-Cookie`; RFC 7230 §3.2.2 generalises the rule. The vector shape
  is uniform across header names — we do NOT special-case `Set-Cookie`
  — because any header the JDK reports with multiple values is, by
  definition, multi-valued on the wire and the consumer needs the
  unfolded form to roundtrip correctly. The string-only fast path
  remains for the 99% case so the common shape stays cheap.

  Tests below construct `HttpHeaders` via the public
  `HttpHeaders/of` factory and exercise the helper through its var so
  the `defn-` stays private to the namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http-transport :as http-transport])
  (:import [java.net.http HttpHeaders]
           [java.util List Map]
           [java.util.function BiPredicate]))

;; ---- helper: build a real HttpHeaders the JDK way -------------------------

(defn- ->http-headers
  "Build a `java.net.http.HttpHeaders` from a Clojure map
  `{header-name [v1 v2 ...]}`. `HttpHeaders/of` accepts the always-true
  filter, mirroring how the JDK constructs HttpHeaders internally when
  parsing a response."
  ^HttpHeaders [m]
  (let [java-map
        (reduce-kv
          (fn [^java.util.HashMap acc k vs]
            (.put acc k (java.util.ArrayList. ^java.util.Collection vs))
            acc)
          (java.util.HashMap.)
          m)
        accept-all (reify BiPredicate
                     (test [_ _ _] true))]
    (HttpHeaders/of ^Map java-map ^BiPredicate accept-all)))

(def ^:private jvm-headers->map @#'http-transport/jvm-headers->map)

;; ---- single-valued path stays string ---------------------------------------

(deftest single-valued-header-is-string
  (testing "a header with one value flattens to a plain string (99% case)"
    (let [hh  (->http-headers {"Content-Type" ["application/json"]})
          out (jvm-headers->map hh)]
      (is (= "application/json" (get out "content-type"))
          "single value preserved as string; key lower-cased at the boundary")
      (is (string? (get out "content-type"))
          "shape is string, NOT a one-element vector — keeps the common
           shape cheap and matches Fetch's `Headers.forEach` behaviour"))))

;; ---- multi-valued path becomes vector (rf2-0xvm1 core pin) -----------------

(deftest set-cookie-multi-valued-is-vector
  (testing "two Set-Cookie lines flatten to a 2-element vector — NOT a comma-joined string"
    (let [hh  (->http-headers {"Set-Cookie"
                               ;; The canonical RFC 6265 break case:
                               ;; Expires=… legally embeds a comma between
                               ;; weekday and day-of-month. The pre-fix
                               ;; `(str/join \",\" vs)` produced a single
                               ;; unparseable string from these two lines.
                               ["session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
                                "csrf=xyz; Path=/; Expires=Thu, 22 Oct 2026 07:28:00 GMT"]})
          out (jvm-headers->map hh)
          v   (get out "set-cookie")]
      (is (vector? v)
          "multi-valued header MUST be a vector — comma-joining
           Set-Cookie violates RFC 6265 §3 because the joined string
           parses as one mangled cookie")
      (is (= 2 (count v)) "every wire line is its own vector element")
      (is (= "session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
             (nth v 0))
          "first cookie line preserved verbatim (commas inside the
           value are intact, not interpreted as a separator)")
      (is (= "csrf=xyz; Path=/; Expires=Thu, 22 Oct 2026 07:28:00 GMT"
             (nth v 1))
          "second cookie line preserved verbatim")))

  (testing "the joined-string anti-shape is NOT produced"
    (let [hh  (->http-headers {"Set-Cookie"
                               ["a=1; Path=/"
                                "b=2; Path=/"]})
          v   (get (jvm-headers->map hh) "set-cookie")]
      (is (not (string? v))
          "MUST NOT be the historical \"a=1; Path=/,b=2; Path=/\"
           comma-joined string — that shape is unparseable")
      (is (= ["a=1; Path=/" "b=2; Path=/"] v)
          "vector-on-multi preserves the original wire lines"))))

(deftest other-multi-valued-headers-also-vector
  (testing "any header the JDK reports with N>1 values flattens to a vector"
    ;; The shape rule is uniform — we do NOT special-case Set-Cookie.
    ;; Vary / WWW-Authenticate can legitimately occur multiple times;
    ;; vector-on-multi gives consumers the unfolded form.
    (let [hh  (->http-headers {"Vary" ["Accept-Encoding" "User-Agent"]
                               "X-Single" ["only-value"]})
          out (jvm-headers->map hh)]
      (is (= ["Accept-Encoding" "User-Agent"] (get out "vary"))
          "Vary: two values → vector")
      (is (= "only-value" (get out "x-single"))
          "single-value header stays as string — uniform rule, no
           special-casing by header name"))))

;; ---- key casing — unchanged from prior contract ----------------------------

(deftest header-keys-stay-lower-cased
  (testing "names lower-cased at the boundary regardless of value shape"
    (let [hh  (->http-headers {"X-Multi" ["a" "b"]
                               "X-Single" ["one"]
                               "MIXED-Case-Header" ["v"]})
          out (jvm-headers->map hh)]
      (is (contains? out "x-multi"))
      (is (contains? out "x-single"))
      (is (contains? out "mixed-case-header"))
      (is (not (contains? out "X-Multi"))
          "no original-case keys leak past the boundary"))))
