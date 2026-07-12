(ns re-frame.ui.fingerprint-cljs-test
  "template-fingerprint / hook-signature-hash / build-digest — the named
  home's algorithm pins (FNV-1a 64 over canonical EDN, version-prefixed
  hex). The literal golden pins CROSS-HOST equality: the same input must
  digest identically under `clojure -M:test` and `npm run test:ui`."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [re-frame.ui.fingerprint :as fp]))

(deftest canonical-string-is-order-insensitive
  (is (= (fp/canonical-string {:b 2 :a 1})
         (fp/canonical-string {:a 1 :b 2})))
  (is (= (fp/canonical-string {:x #{:c :a :b}})
         (fp/canonical-string {:x #{:b :a :c}})))
  (is (not= (fp/canonical-string [:a :b]) (fp/canonical-string [:b :a]))
      "vectors stay order-significant"))

(deftest canonical-string-is-type-preserving
  ;; rf2-vxgfnd.78: the canonical form must be INJECTIVE across collection
  ;; types and element types. The pre-#5745/#5745 form flattened a set to a
  ;; bare vector of its elements' `pr-str` STRINGS, so distinct configs
  ;; collided BEFORE the hash ran (a set/vector/list-lossiness bug): a real
  ;; config change could read as an idempotent no-op, or two distinct configs
  ;; be wrongly deduped.
  ;; (a) the deterministic collision from the bead: a set and a vector of the
  ;; same-`pr-str` element must NOT collide.
  (is (not= (fp/canonical-string #{:a}) (fp/canonical-string [":a"]))
      "a set and a vector-of-the-stringified-element must not collide")
  (is (not= (fp/config-fingerprint :f #{:a}) (fp/config-fingerprint :f [":a"]))
      "and neither may their config fingerprints")
  ;; a set, vector, and list of the SAME elements are three distinct shapes.
  (is (apply distinct? [(fp/canonical-string #{:a :b})
                        (fp/canonical-string [:a :b])
                        (fp/canonical-string (list :a :b))])
      "set vs vector vs list of the same elements are all distinct")
  ;; empty collections don't collapse together either.
  (is (apply distinct? [(fp/canonical-string #{})
                        (fp/canonical-string [])
                        (fp/canonical-string {})
                        (fp/canonical-string (list))])
      "empty set/vector/map/list are all distinct")
  ;; (b) a set stays order-INSENSITIVE.
  (is (= (fp/canonical-string #{:a :b}) (fp/canonical-string #{:b :a}))
      "a set is order-insensitive")
  ;; (c) a vector stays order-SENSITIVE.
  (is (not= (fp/canonical-string [:a :b]) (fp/canonical-string [:b :a]))
      "a vector is order-sensitive")
  ;; (d) type-distinct scalar elements must not collide: keyword `:a`, string
  ;; `":a"`, symbol `a` are three different values.
  (is (apply distinct? [(fp/canonical-string :a)
                        (fp/canonical-string ":a")
                        (fp/canonical-string 'a)])
      "keyword vs string vs symbol scalars are distinct")
  (is (apply distinct? [(fp/canonical-string #{:a})
                        (fp/canonical-string #{":a"})
                        (fp/canonical-string #{'a})])
      "and they stay distinct nested inside a set")
  ;; nested-collection types are preserved through map keys and values too.
  (is (not= (fp/canonical-string {:k {}}) (fp/canonical-string {:k []}))
      "a map-valued vs vector-valued entry is distinguishable")
  ;; a config map may legally carry BOTH the set key #{:a} and the vector key
  ;; [":a"]; canonicalization must retain BOTH entries (the pre-fix
  ;; sorted-map-by merged them, discarding one before the hash).
  (let [both  (fp/canonical-string {#{:a} 1 [":a"] 2})
        one   (fp/canonical-string {#{:a} 1})]
    (is (not= both one)
        "both distinct keys survive canonicalization — neither is overwritten")))

(deftest canonicalize-recurses-into-map-keys-and-set-elements
  ;; A map (or nested collection) used AS A KEY — or as a set element — must
  ;; canonicalize to a stable form regardless of its authoring order.
  ;; Otherwise the key's/element's authoring order survives into the printed
  ;; digest and two `=`-equal plans fingerprint differently, raising a
  ;; SPURIOUS cross-root `:rf.error/frame-payload-conflict` (rf2-vxgfnd.33).
  (is (= (fp/canonical-string {{:a 1 :b 2} :v})
         (fp/canonical-string {{:b 2 :a 1} :v}))
      "a map-as-key canonicalizes regardless of authoring order")
  (is (= (fp/canonical-string #{{:a 1 :b 2}})
         (fp/canonical-string #{{:b 2 :a 1}}))
      "a map inside a set canonicalizes regardless of authoring order")
  ;; the whole point: two frame plans identical up to map-key authoring order
  ;; MUST fingerprint EQUAL, so preflight sees the idempotent no-op, not a
  ;; spurious conflict.
  (is (= (fp/config-fingerprint :frame/f {:routes {{:a 1 :b 2} :left}})
         (fp/config-fingerprint :frame/f {:routes {{:b 2 :a 1} :left}}))
      "two plans identical up to map-key authoring order fingerprint EQUAL")
  ;; a GENUINELY different config still separates — the fix does not collapse
  ;; distinct plans.
  (is (not= (fp/config-fingerprint :frame/f {:routes {{:a 1 :b 2} :left}})
            (fp/config-fingerprint :frame/f {:routes {{:a 1 :b 2} :right}}))
      "a real config difference still fingerprints differently"))

(deftest fnv1a-64-cross-host-golden
  ;; The digest input is (canonical-string "a") = "cfp2:t3:\"a\"", so pin OUR
  ;; pipeline's value — a JVM-computed literal the CLJS run must reproduce.
  (is (= "tf1-c783a8a97ba1ec42" (fp/digest "tf1-" "a"))
      "cross-host golden — JVM-computed literal; the CLJS run must match")
  ;; a representative nested value (set + vector + nested map inside a map)
  ;; digests identically regardless of authoring order, on BOTH hosts.
  (is (= "cf1-012724d9085f1a27"
         (fp/config-fingerprint :frame/f
                                {:b [2 3] :a #{:s2 :s1} :m {:y 2 :x 1}}))
      "cross-host golden — nested set/vector/map value")
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
