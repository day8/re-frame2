(ns re-frame.http-jvm-headers-shape-test
  "JVM response headers shape pin.

  The JVM transport's `jvm-headers->map` flattens
  `java.net.http.HttpHeaders` into a Clojure map at the
  response-decode boundary. Per Spec 014 §Request envelope the headers
  map is `string → string (or string → vector of strings for
  multi-valued)`. Comma-joining every multi-
  valued header (`(str/join \",\" vs)`) would break `Set-Cookie`
  because cookie attribute values legally contain commas
  (`Expires=Wed, 21 Oct 2026 ...`), so comma-joining N lines produces a
  single unparseable string.

  Cookie shape: single-valued headers stay
  `string`; multi-valued headers (every header where the JDK saw more
  than one wire instance) become `vector-of-strings`, preserving the
  original lines verbatim. RFC 6265 §3 forbids comma-folding
  `Set-Cookie`; RFC 7230 §3.2.2 generalises the rule. The vector shape
  is uniform across header names — we do NOT special-case `Set-Cookie`
  — because any header the JDK reports with multiple values is, by
  definition, multi-valued on the wire and the consumer needs the
  unfolded form to roundtrip correctly. The string-only fast path
  serves the 99% case so the common shape stays cheap.

  Tests below construct `HttpHeaders` via the public
  `HttpHeaders/of` factory and exercise the helper through its var so
  the `defn-` stays private to the namespace.

  `jvm-headers->map` is JVM-platform-transport internal and
  lives in the per-platform adapter ns `re-frame.http.transport-jvm`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm])
  (:import [java.net.http HttpHeaders]
           [java.util Map]
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

(def ^:private jvm-headers->map @#'rf.http.transport-jvm/jvm-headers->map)

;; ---- multi-valued path becomes vector (core pin) ---------------------------

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
