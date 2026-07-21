(ns re-frame.ui.fingerprint-cljs-test
  "template-fingerprint / hook-signature-hash / config-fingerprint — the named
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

;; rf2-59car — REAL records for the type-preservation tests below. `RecA` and
;; `RecB` are structurally identical (one field `x`) and differ ONLY by record
;; type, which is exactly the pair the pre-fix leading `map?` branch could not
;; separate.

(defrecord RecA [x])
(defrecord RecB [x])

(deftest canonical-string-preserves-record-type
  ;; rf2-59car: a record ALSO satisfies `map?`, so `-write`'s leading `(map? x)`
  ;; branch discarded the record's TYPE and encoded `(->RecA 1)`, `(->RecB 1)`,
  ;; and `{:x 1}` all as the plain map `cfp2:m9:t2::xt1:1`. Those three values
  ;; are pairwise UNEQUAL, so collapsing them was a GUARANTEED pre-hash
  ;; collision of the same class as the pre-#5745 set flattening — and it lands
  ;; on the worse failure mode: `config-fingerprint`
  ;; plan-conflict detection reads a false digest MATCH as "nothing changed", so
  ;; a genuine plan conflict is silently treated as an idempotent no-op.
  (is (map? (->RecA 1)) "premise — a record IS `map?`, which is why it collapsed")
  (is (record? (->RecA 1)) "premise — and it is a record")
  (is (not= (->RecA 1) (->RecB 1)) "premise — the two record types are unequal")
  (is (not= (->RecA 1) {:x 1}) "premise — record and plain map are unequal")
  (is (apply distinct? [(fp/canonical-string (->RecA 1))
                        (fp/canonical-string (->RecB 1))
                        (fp/canonical-string {:x 1})])
      "RecA, RecB, and the plain map of the same entries all canonicalize
       DISTINCTLY — the record type is preserved, not flattened into entries")
  ;; the digests that actually feed plan-conflict detection must separate too.
  (is (apply distinct? [(fp/config-fingerprint :frame/f (->RecA 1))
                        (fp/config-fingerprint :frame/f (->RecB 1))
                        (fp/config-fingerprint :frame/f {:x 1})])
      "and so must their config fingerprints — a record type swap is a REAL
       plan change, never an idempotent no-op")
  ;; the record tag can collide with no other token type.
  (is (apply distinct? [(fp/canonical-string (->RecA 1))
                        (fp/canonical-string #{[:x 1]})
                        (fp/canonical-string [[:x 1]])
                        (fp/canonical-string (list [:x 1]))])
      "a record stays distinct from a set/vector/list of its flattened entries"))

(deftest canonical-string-record-extension-order-is-canonical
  ;; rf2-59car ADVERSARIAL — type preservation must not cost order-canonicality.
  ;; A record's EXTENSION entries (assoc'd beyond its declared fields) live in
  ;; `__extmap`, whose small-map representation preserves INSERTION order, so
  ;; the two values below iterate differently while being `=`. They must digest
  ;; identically or preflight raises a SPURIOUS plan conflict.
  (is (= (assoc (->RecA 1) :b 2 :c 3) (assoc (->RecA 1) :c 3 :b 2))
      "premise — the two extension orders are value-equal")
  (is (= (fp/canonical-string (assoc (->RecA 1) :b 2 :c 3))
         (fp/canonical-string (assoc (->RecA 1) :c 3 :b 2)))
      "a record's extension entries are order-normalized exactly like a map's")
  (is (= (fp/config-fingerprint :frame/f (assoc (->RecA 1) :b 2 :c 3))
         (fp/config-fingerprint :frame/f (assoc (->RecA 1) :c 3 :b 2)))
      "so two plans identical up to extension authoring order fingerprint EQUAL")
  ;; order-invariance is not value-collapse: a genuinely different extension
  ;; value still separates.
  (is (not= (fp/canonical-string (assoc (->RecA 1) :b 2 :c 3))
            (fp/canonical-string (assoc (->RecA 1) :b 2 :c 4)))
      "a real extension difference still canonicalizes differently")
  ;; nor may an extension entry be confusable with a declared field.
  (is (not= (fp/canonical-string (assoc (->RecA 1) :y 2))
            (fp/canonical-string (assoc (->RecA 2) :y 1)))
      "declared field and extension entry are not interchangeable"))

(deftest canonical-string-preserves-record-type-at-depth
  ;; rf2-59car ADVERSARIAL — the record tag must survive RECURSION, not just a
  ;; top-level value. `-write` recurses through map values, set elements, and
  ;; vector slots, and every one of those paths must keep the type.
  (is (apply distinct? [(fp/canonical-string {:k (->RecA 1)})
                        (fp/canonical-string {:k (->RecB 1)})
                        (fp/canonical-string {:k {:x 1}})])
      "nested as a map VALUE, RecA / RecB / plain map stay distinct")
  (is (apply distinct? [(fp/canonical-string {(->RecA 1) :v})
                        (fp/canonical-string {(->RecB 1) :v})
                        (fp/canonical-string {{:x 1} :v})])
      "and as a map KEY")
  (is (apply distinct? [(fp/canonical-string [(->RecA 1)])
                        (fp/canonical-string [(->RecB 1)])
                        (fp/canonical-string [{:x 1}])])
      "and inside a vector")
  (is (apply distinct? [(fp/canonical-string #{(->RecA 1)})
                        (fp/canonical-string #{(->RecB 1)})
                        (fp/canonical-string #{{:x 1}})])
      "and inside a set")
  (is (not= (fp/config-fingerprint :frame/f {:routes {:home (->RecA 1)}})
            (fp/config-fingerprint :frame/f {:routes {:home {:x 1}}}))
      "a nested record vs plain map is a REAL plan difference at any depth")
  ;; order-canonicality still holds around a nested record.
  (is (= (fp/canonical-string {:k (assoc (->RecA 1) :b 2 :c 3) :j 1})
         (fp/canonical-string {:j 1 :k (assoc (->RecA 1) :c 3 :b 2)}))
      "nesting composes: outer map order and inner extension order both
       normalize"))

(deftest record-tag-cross-host-golden
  ;; The record TAG is the one part of the encoding whose natural host
  ;; renderings DIVERGE — the JVM has a munged, dotted class name
  ;; (`re_frame.ui.fingerprint_cljs_test.RecA`) where CLJS has the
  ;; `cljs$lang$ctorPrWriter` literal (`re-frame.ui.fingerprint-cljs-test/RecA`).
  ;; `record-type-name` normalizes the JVM side onto the CLJS form, so these
  ;; JVM-computed literals must reproduce EXACTLY under the CLJS run. Without
  ;; that normalization a build-time (JVM) digest and a client (CLJS) digest of
  ;; the SAME plan would differ — a spurious plan conflict, the mirror-image
  ;; failure of the collapse this bead fixed.
  (is (= "cf1-b812168c861e3c0c" (fp/config-fingerprint :frame/f (->RecA 1)))
      "cross-host golden — JVM-computed literal; the CLJS run must match")
  (is (= "cf1-d5f464cf44df9810" (fp/config-fingerprint :frame/f {:k (->RecA 1)}))
      "cross-host golden — record nested as a map value")
  ;; The record branch is CONSERVATIVE: it changes the encoding of nothing but
  ;; records, so no fingerprint over the record-free value space moves and the
  ;; `cfp2` version marker does not need to rotate. The two pre-existing goldens
  ;; in `fnv1a-64-cross-host-golden` are unchanged by this bead, which is the
  ;; standing proof of that.
  (is (= "tf1-c783a8a97ba1ec42" (fp/digest "tf1-" "a"))
      "the record branch perturbs no record-free digest — `cfp2` still applies"))

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

