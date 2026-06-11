(ns re-frame.identity-cedn1-cljs-test
  "Law / property tests for canonical EDN identity and the CEDN-1 byte
  encoding (EP-0012, Conventions §Canonical EDN identity / §Canonical byte
  encoding). Covers map-order invariance, set ordering, vector/list
  distinctness, the nil-vs-missing distinction, heterogeneous keys, the
  type-tag-keeps-distinct-kinds property, instant timezone normalization,
  and the fail-closed rejection cases (floats / NaN / infinities / ratios /
  out-of-range integers / host objects / functions / nested host values).

  Same seeded-PRNG approach as `re-frame.path-laws-cljs-test` (no
  `clojure.test.check` on the classpath). Named `*-cljs-test` so both the
  shadow-cljs `:node-test` build and the JVM cognitect runner discover it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.identity :as id]))

;; ---- deterministic PRNG (mirrors path-laws-cljs-test) --------------------

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

(def ^:private key-pool [:a :b :c :x "k1" "k2" 0 1 'sym true false])
(def ^:private scalar-pool [:kw "str" 0 1 2 42 -7 true false nil 'sy])

(defn- gen-key [s] (nth key-pool (rnd s (count key-pool))))
(defn- gen-scalar [s] (nth scalar-pool (rnd s (count scalar-pool))))

(defn- gen-edn
  "Generate a small canonical-domain EDN value to `depth`. Returns
  `[value next-state]`. Uses only CEDN-1-admissible scalars."
  [state depth]
  (let [k (rnd state (if (zero? depth) 3 6))
        s (lcg-next state)]
    (case k
      0 [(gen-scalar s) (lcg-next s)]
      1 [(rnd s 1000) (lcg-next s)]
      2 [(str "v" (rnd s 50)) (lcg-next s)]
      3 (let [n (rnd s 4)]                ;; map
          (loop [i 0, st (lcg-next s), acc {}]
            (if (= i n)
              [acc st]
              (let [kk (gen-key st)
                    [vv st'] (gen-edn (lcg-next st) (dec depth))]
                (recur (inc i) st' (assoc acc kk vv))))))
      4 (let [n (rnd s 3)]                ;; vector
          (loop [i 0, st (lcg-next s), acc []]
            (if (= i n)
              [acc st]
              (let [[vv st'] (gen-edn (lcg-next st) (dec depth))]
                (recur (inc i) st' (conj acc vv))))))
      5 (let [n (rnd s 3)]                ;; set
          (loop [i 0, st (lcg-next s), acc #{}]
            (if (= i n)
              [acc st]
              (let [[vv st'] (gen-edn (lcg-next st) (dec depth))]
                (recur (inc i) st' (conj acc vv)))))))) )

(defn- shuffle-coll
  "Deterministically reorder a map/set/vector's entries using the PRNG —
  enough to disturb insertion order for the order-invariance property.
  Maps and sets are rebuilt entry-by-entry in a permuted order; the
  resulting value is `=` to the input but has different internal insertion
  order, which is exactly what canonical identity must collapse."
  [v state]
  (cond
    (map? v) (let [ks (vec (keys v))
                   perm (sort-by (fn [k] (rnd (+ state (hash k)) 1000000)) ks)]
               (reduce (fn [m k] (assoc m k (get v k))) {} perm))
    (set? v) (let [es (vec v)
                   perm (sort-by (fn [e] (rnd (+ state (hash e)) 1000000)) es)]
               (reduce conj #{} perm))
    :else v))

;; ---- map-order invariance ------------------------------------------------

(deftest map-order-invariance
  (testing "permuted map insertion order -> identical canonical bytes"
    (is (= (id/canonical-bytes {:page 1 :tag "cljs"})
           (id/canonical-bytes {:tag "cljs" :page 1})))
    (is (= (id/canonical-bytes {:filter {:archived? false :tag "cljs"}})
           (id/canonical-bytes {:filter {:tag "cljs" :archived? false}}))))
  (testing "generated nested maps are order-invariant under reshuffle"
    (is (nil?
          (loop [i 0, s 31337]
            (if (= i 400)
              nil
              (let [[v s1] (gen-edn s 3)]
                (if (and (map? v) (seq v))
                  (let [v' (shuffle-coll v s1)]
                    (if (= (id/canonical-bytes v) (id/canonical-bytes v'))
                      (recur (inc i) (lcg-next s1))
                      [v v']))
                  (recur (inc i) (lcg-next s1))))))))))

;; ---- scope / params identity (the resource-key motivation) ---------------

(deftest scope-and-params-identity
  (let [scope-a [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
        scope-b [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]
        params-a {:slug "welcome" :include-comments? true}
        params-b {:include-comments? true :slug "welcome"}]
    (is (id/identical-identity? scope-a scope-b))
    (is (id/identical-identity? params-a params-b))
    (testing "different scopes with same params do NOT collide"
      (is (not (id/identical-identity?
                 [:rf.scope/session {:user-id "u-1"}]
                 [:rf.scope/session {:user-id "u-2"}]))))
    (testing "work id embeds the canonical scoped resource key + generation"
      (let [rkey [(id/canonical scope-a) :article/by-slug (id/canonical params-a)]
            work-id [:rf.work/resource rkey 4]]
        (is (= (id/canonical-bytes work-id)
               (id/canonical-bytes [:rf.work/resource
                                    [(id/canonical scope-b) :article/by-slug (id/canonical params-b)]
                                    4])))))))

;; ---- set ordering --------------------------------------------------------

(deftest set-ordering
  (testing "sets are order-invariant by canonical element bytes"
    (is (= (id/canonical-bytes #{3 1 2}) (id/canonical-bytes #{2 3 1})))
    (is (= (id/canonical-bytes #{:b :a :c}) (id/canonical-bytes #{:c :a :b}))))
  (testing "generated sets are order-invariant under reshuffle"
    (is (nil?
          (loop [i 0, s 4242]
            (if (= i 300)
              nil
              (let [[v s1] (gen-edn s 3)]
                (if (and (set? v) (seq v))
                  (if (= (id/canonical-bytes v) (id/canonical-bytes (shuffle-coll v s1)))
                    (recur (inc i) (lcg-next s1))
                    [v])
                  (recur (inc i) (lcg-next s1))))))))))

;; ---- vector/list/set distinctness + type tags ----------------------------

(deftest edn-kind-distinctness
  (testing "vector vs list are distinct EDN facts"
    (is (not= (id/canonical-bytes [1 2]) (id/canonical-bytes (list 1 2)))))
  (testing "vector vs set are distinct"
    (is (not= (id/canonical-bytes [1 2 3]) (id/canonical-bytes #{1 2 3}))))
  (testing "the type tag keeps scalar kinds distinct"
    (let [bs [(id/canonical-bytes "42")
              (id/canonical-bytes 42)
              (id/canonical-bytes :42)
              (id/canonical-bytes [1 2])
              (id/canonical-bytes (list 1 2))]]
      (is (= (count bs) (count (distinct bs))))))
  (testing "heterogeneous map keys are legal and ordered by key bytes"
    (is (string? (id/canonical-bytes {:a 1 "a" 2 0 3 true 4})))
    (is (= (id/canonical-bytes {:a 1 "a" 2 0 3})
           (id/canonical-bytes {0 3 "a" 2 :a 1}))))
  (testing "vectors preserve order (not sorted)"
    (is (not= (id/canonical-bytes [:a :b]) (id/canonical-bytes [:b :a])))))

;; ---- nil vs missing ------------------------------------------------------

(deftest nil-vs-missing
  (testing "present-nil and absent key are distinct identities"
    (is (not (id/identical-identity? {} {:page nil})))
    (is (not= (id/canonical-bytes {}) (id/canonical-bytes {:page nil}))))
  (testing "canonical preserves present-nil values"
    (is (= {:page nil} (id/canonical {:page nil})))
    (is (= nil (id/canonical nil)))))

;; ---- canonical returns an = value, order-normalized ----------------------

(deftest canonical-normalized-value
  (testing "canonical of map is =-equal but key-order-normalized"
    (is (= {:a 1 :b 2} (id/canonical {:b 2 :a 1}))))
  (testing "canonical recurses, preserving vector order"
    (is (= {:xs [3 1 2]} (id/canonical {:xs [3 1 2]}))))
  (testing "canonical of a value equals the value for in-domain scalars"
    (is (= "x" (id/canonical "x")))
    (is (= 7 (id/canonical 7)))
    (is (= :k (id/canonical :k)))))

;; ---- instant + uuid ------------------------------------------------------

(deftest instant-and-uuid
  (testing "uuid encodes lower-case RFC 4122 text"
    (is (= "u:11111111-1111-1111-1111-111111111111"
           (id/canonical-bytes #uuid "11111111-1111-1111-1111-111111111111"))))
  #?(:clj
     (testing "equivalent instants in different source timezones normalize to one UTC identity"
       (is (id/identical-identity?
             #inst "2026-06-10T10:00:00.000+10:00"
             #inst "2026-06-10T00:00:00.000-00:00"))
       (is (= "t:2026-06-10T00:00:00.000Z"
              (id/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00"))))))

;; ---- fail-closed rejection -----------------------------------------------

(defn- non-edn-id-error?
  "Run `thunk`; true iff it threw with `:rf.error/id
  :rf.error/non-edn-identity` ex-data."
  [thunk]
  (try
    (thunk)
    false
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (= :rf.error/non-edn-identity (:rf.error/id (ex-data e))))))

(deftest rejection-cases
  (testing "floats / NaN / infinities / ratios fail closed"
    (is (non-edn-id-error? #(id/canonical-bytes 1.5)))
    #?(:clj (is (non-edn-id-error? #(id/canonical-bytes (/ 1 3)))))
    #?(:cljs (is (non-edn-id-error? #(id/canonical-bytes js/NaN))))
    #?(:cljs (is (non-edn-id-error? #(id/canonical-bytes js/Infinity)))))
  (testing "integers outside the safe range fail closed"
    (is (non-edn-id-error? #(id/canonical-bytes 9007199254740992)))
    (is (non-edn-id-error? #(id/canonical-bytes -9007199254740992))))
  (testing "the safe-range boundaries are admitted"
    (is (= "i:9007199254740991"  (id/canonical-bytes 9007199254740991)))
    (is (= "i:-9007199254740991" (id/canonical-bytes -9007199254740991))))
  (testing "functions fail closed"
    (is (non-edn-id-error? #(id/canonical-bytes inc))))
  (testing "a nested host value fails the WHOLE identity closed (no host fallback)"
    (is (non-edn-id-error? #(id/canonical {:a {:b inc}})))
    (is (non-edn-id-error? #(id/canonical-bytes [1 2 inc]))))
  #?(:cljs
     (testing "a raw JS object fails closed"
       (is (non-edn-id-error? #(id/canonical-bytes #js {:tenant "acme"})))
       (testing "but its explicit EDN encoding is accepted"
         (is (string? (id/canonical-bytes {:tenant "acme"}))))))
  #?(:clj
     (testing "an arbitrary host object fails closed"
       (is (non-edn-id-error? #(id/canonical-bytes (java.lang.Object.)))))))
