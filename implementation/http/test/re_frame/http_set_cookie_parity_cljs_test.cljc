(ns re-frame.http-set-cookie-parity-cljs-test
  "Host-symmetric response-header shape (Spec 014 §Successful-response
  metadata: a repeated `Set-Cookie` is a vector of its lines, any other
  repeated header one comma-folded string), asserted on both hosts from one
  source.

  A bare `Headers.forEach` on CLJS yields only the LAST `Set-Cookie` line, so
  the CLJS transport recovers the unfolded lines through
  `Headers.getSetCookie()`; the JVM rides every `Set-Cookie` wire line as a
  vector element and folds every other repeated header as Fetch does.
  Each host's native headers object is built here and flattened through that
  host's transport helper."
  (:require
   #?(:clj  [clojure.test :refer [deftest is]]
      :cljs [cljs.test :refer-macros [deftest is]])
   #?(:clj  [re-frame.http.transport-jvm :as rf.http.transport-jvm]
      :cljs [re-frame.http.transport-cljs :as rf.http.transport-cljs]))
  #?(:clj (:import [java.net.http HttpHeaders]
                   [java.util Map]
                   [java.util.function BiPredicate])))

#?(:clj
   (defn- decode-headers [m]
     (let [java-map (reduce-kv
                      (fn [^java.util.HashMap acc k vs]
                        (.put acc k (java.util.ArrayList. ^java.util.Collection vs))
                        acc)
                      (java.util.HashMap.)
                      m)]
       (@#'rf.http.transport-jvm/jvm-headers->map
        (HttpHeaders/of ^Map java-map ^BiPredicate (reify BiPredicate (test [_ _ _] true)))))))

#?(:cljs
   (defn- decode-headers [m]
     (let [h (js/Headers.)]
       (doseq [[k vs] m
               v vs]
         (.append h k v))
       (@#'rf.http.transport-cljs/fetch-headers->map h))))

(deftest single-set-cookie-is-string-cross-host
  (is (= "only=1; Path=/" (get (decode-headers {"Set-Cookie" ["only=1; Path=/"]}) "set-cookie"))))

(deftest multi-set-cookie-is-vector-of-verbatim-lines-cross-host
  ;; The comma inside Expires must not split a line, and no line may be lost.
  (let [cookies ["session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"
                 "csrf=xyz; Path=/; Expires=Thu, 22 Oct 2026 07:28:00 GMT"]
        v       (get (decode-headers {"Set-Cookie" cookies}) "set-cookie")]
    (is (vector? v))
    (is (= cookies v))))

(deftest repeated-header-folds-except-set-cookie-cross-host
  ;; Fetch delivers a repeated header comma-folded and offers no unfold, so
  ;; the JVM folds it too (RFC 9110 §5.3); Set-Cookie alone stays a vector.
  (let [m (decode-headers {"Vary"       ["Accept" "Origin"]
                           "Set-Cookie" ["a=1; Path=/" "b=2; Path=/"]})]
    (is (= "Accept, Origin" (get m "vary")))
    (is (= ["a=1; Path=/" "b=2; Path=/"] (get m "set-cookie")))))

(deftest no-set-cookie-leaves-map-untouched-cross-host
  (is (= {"content-type" "application/json"}
         (decode-headers {"Content-Type" ["application/json"]}))
      "no set-cookie key is synthesised"))
