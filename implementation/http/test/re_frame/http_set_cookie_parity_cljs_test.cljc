(ns re-frame.http-set-cookie-parity-cljs-test
  "Host-symmetric response-header shape for `Set-Cookie` (Spec 014 §Request
  envelope: a multi-valued header is a vector of strings), asserted on both
  hosts from one source.

  A bare `Headers.forEach` on CLJS yields only the LAST `Set-Cookie` line, so
  the CLJS transport recovers the unfolded lines through
  `Headers.getSetCookie()`; the JVM rides every wire line as a vector element.
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

(deftest no-set-cookie-leaves-map-untouched-cross-host
  (is (= {"content-type" "application/json"}
         (decode-headers {"Content-Type" ["application/json"]}))
      "no set-cookie key is synthesised"))
