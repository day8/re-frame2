(ns re-frame.ui.fingerprint-cljs-test
  "template-fingerprint / hook-signature-hash / build-digest — the named
  home's algorithm pins (FNV-1a 64 over canonical EDN, version-prefixed
  hex). The literal golden pins CROSS-HOST equality: the same input must
  digest identically under `clojure -M:test` and `npm run test:ui`."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [re-frame.ui.fingerprint :as fp]))

(deftest canonical-edn-is-order-insensitive
  (is (= (fp/canonical-edn {:b 2 :a 1})
         (fp/canonical-edn {:a 1 :b 2})))
  (is (= (fp/canonical-edn {:x #{:c :a :b}})
         (fp/canonical-edn {:x #{:b :a :c}})))
  (is (not= (fp/canonical-edn [:a :b]) (fp/canonical-edn [:b :a]))
      "vectors stay order-significant"))

(deftest fnv1a-64-cross-host-golden
  ;; FNV-1a 64 reference vector: "a" -> af63dc4c8601ec8c; our digest input
  ;; is (canonical-edn "a") = "\"a\"" so pin OUR pipeline's value instead:
  (is (= "tf1-d4272417d7c77eea" (fp/digest "tf1-" "a"))
      "cross-host golden — JVM-computed literal; the CLJS run must match")
  (is (= (fp/digest "x-" {:b [2 3] :a #{:s2 :s1}})
         (fp/digest "x-" {:a #{:s1 :s2} :b [2 3]}))))

(deftest template-fingerprint-sensitivity
  (let [ast1 {:op :element :tag :div :children [{:op :text :value "a"}]}
        ast2 {:op :element :tag :div :children [{:op :text :value "b"}]}]
    (is (= (fp/template-fingerprint ast1) (fp/template-fingerprint ast1)))
    (is (not= (fp/template-fingerprint ast1) (fp/template-fingerprint ast2)))
    (is (string/starts-with? (fp/template-fingerprint ast1) "tf1-"))
    (is (= 20 (count (fp/template-fingerprint ast1))) "tf1- + 16 hex chars")))

(deftest hook-signature-v1
  (is (string/starts-with? (fp/hook-signature-hash {}) "hs1-"))
  (is (= (fp/hook-signature-hash {}) (fp/hook-signature-hash {:locals [] :effects []}))
      "S1 signatures are the constant empty plan")
  (is (not= (fp/hook-signature-hash {})
            (fp/hook-signature-hash {:locals [:local]}))
      "a local changes the signature (remount semantics, S2)")
  ;; sub sites are DELIBERATELY excluded from the signature input — dev's
  ;; fixed hook skeleton makes adding your first sub a same-signature edit
  (is (= (fp/hook-signature-hash {}) (fp/hook-signature-hash {:subs [[:q]]}))))

(deftest build-digest-order-independent
  (let [t1 [[:a/v "tf1-x" "hs1-y"] [:b/v "tf1-z" "hs1-w"]]
        t2 [[:b/v "tf1-z" "hs1-w"] [:a/v "tf1-x" "hs1-y"]]]
    (is (= (fp/build-digest t1) (fp/build-digest t2)))
    (is (string/starts-with? (fp/build-digest t1) "bd1-"))
    (is (not= (fp/build-digest t1)
              (fp/build-digest [[:a/v "tf1-x" "hs1-y"]])))))
