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
              (id/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00")))))
  ;; --- CLJS js/Date instant encoding (rf2-orcbow point 4) ---
  ;; The JVM instant tests above exercise the java.time path; CLJS js/Date
  ;; encoding rides a SEPARATE branch (`.toISOString`) that can regress
  ;; independently, so it gets its own dedicated assertions. The exact
  ;; `t:...Z` millisecond-precision token, and the equivalent-instant
  ;; timezone-collapse property, are both pinned for the JS host.
  #?(:cljs
     (testing "CLJS js/Date encodes to the exact RFC 3339 millis-UTC t:...Z token"
       ;; 2026-06-10T00:00:00.000Z == epoch millis 1781049600000.
       (is (= "t:2026-06-10T00:00:00.000Z"
              (id/canonical-bytes (js/Date. 1781049600000))))
       (testing "a whole-second instant still renders the millisecond .000 suffix"
         (is (= "t:2026-06-10T00:00:00.000Z"
                (id/canonical-bytes (js/Date. "2026-06-10T00:00:00Z")))))
       (testing "sub-second precision is preserved in the token"
         (is (= "t:2026-06-10T00:00:00.123Z"
                (id/canonical-bytes (js/Date. 1781049600123)))))
       (testing "two js/Date objects built for the same instant in different
                 timezone literals collapse to one identity"
         (is (id/identical-identity?
               (js/Date. "2026-06-10T10:00:00+10:00")
               (js/Date. "2026-06-10T00:00:00Z"))))
       (testing "a js/Date is identity-equal to its EDN #inst counterpart for the
                 same instant (host-date and EDN-instant are one fact)"
         (is (= (id/canonical-bytes (js/Date. 1781049600000))
                (id/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00")))))))

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

;; ---- canonical projection: ordering is owned by canonical-bytes ----------
;;
;; rf2-orcbow point 3. The bead asks whether `canonical` returns a
;; deterministically-ordered readable projection, or merely an =-equal
;; value. These tests PIN the actual contract: `canonical` returns an
;; =-equal, recursively-normalized value but does NOT reorder map/set entries
;; — ordering is owned EXCLUSIVELY by `canonical-bytes` (the byte-level
;; identity the equality contract is defined over, Conventions §Canonical
;; byte encoding). Two map spellings that differ only in insertion order have
;; identical `canonical-bytes`; their `canonical` projections are `=` but
;; their entry-iteration order is not a contract. A consumer that needs a
;; deterministic readable projection sorts by `canonical-bytes` itself (or a
;; future surface narrows this). Pinning the narrow contract here keeps a
;; later "make canonical ordered" change from silently widening it.

(deftest canonical-projection-ordering-contract
  (testing "canonical-bytes is the deterministically-ordered surface"
    ;; Map key order in the source spelling does not change the bytes.
    (is (= (id/canonical-bytes {:z 1 :a 2 :m 3})
           (id/canonical-bytes {:a 2 :m 3 :z 1})
           (id/canonical-bytes {:m 3 :z 1 :a 2})))
    ;; Repeated calls are byte-identical (determinism).
    (is (= (id/canonical-bytes {:z 1 :a 2})
           (id/canonical-bytes {:z 1 :a 2})))
    ;; Sets order by element bytes regardless of construction order.
    (is (= (id/canonical-bytes #{:gamma :alpha :beta})
           (id/canonical-bytes #{:beta :gamma :alpha}))))
  (testing "canonical returns an =-equal value but ordering is NOT its contract"
    ;; Equal as a value, key-order normalized away.
    (is (= (id/canonical {:z 1 :a 2 :m 3})
           (id/canonical {:a 2 :m 3 :z 1})))
    (is (= {:a 2 :m 3 :z 1} (id/canonical {:z 1 :a 2 :m 3})))
    ;; The projection recurses but preserves vector order (vectors are
    ;; order-significant EDN facts).
    (is (= {:xs [3 1 2]} (id/canonical {:xs [3 1 2]})))
    ;; A set projection is =-equal to the source set.
    (is (= #{:alpha :beta :gamma} (id/canonical #{:gamma :alpha :beta}))))
  (testing "two values are equal-as-identity iff their canonical-bytes are ="
    ;; The normative equality contract (Conventions §Canonical EDN identity):
    ;; canonical-bytes equality is the identity, not canonical-value =.
    (is (= (id/identical-identity? {:z 1 :a 2} {:a 2 :z 1})
           (= (id/canonical-bytes {:z 1 :a 2}) (id/canonical-bytes {:a 2 :z 1}))
           true))))

;; ---- duplicate canonical map keys (the host-value collision) -------------
;;
;; rf2-orcbow point 1. Two DISTINCT host values can encode to the same
;; CEDN-1 key bytes. The canonical adversarial case (the one the bead names)
;; is JVM-only: a `java.util.Date` and a `java.time.Instant` for the SAME
;; instant are distinct host types AND distinct Clojure map keys (`=` does
;; not equate them), yet both render the identical `t:<utc>.SSSZ` token. A
;; map keyed by both keeps BOTH entries, so its CEDN-1 bytes carry two
;; colliding key tokens inside one `m{…}` group. Conventions §Canonical byte
;; encoding: "Duplicate canonical keys are invalid and MUST be rejected
;; before the value becomes a cache key, route identity, or work id."
;;
;; CLJS has a single host date type (`js/Date`), and CLJS `=` equates two
;; `js/Date`s (and an EDN `#inst`, which READS as a `js/Date`) for the same
;; instant — so a map keyed by "two same-instant dates" COLLAPSES to one
;; entry on CLJS. The distinct-host-keys-same-bytes map collision is
;; therefore inherently a JVM phenomenon; the CLJS leg pins the cross-host
;; "same instant → one identity" facts instead (which hold on both hosts).
;;
;; KNOWN GAP — the current encoder does NOT yet detect the JVM collision: it
;; sorts entries by key bytes and emits both, producing colliding key tokens.
;; The fail-closed source fix is owned by the correctness/best-practice
;; review beads rf2-wgutc2 (item 1, canonical alignment) and rf2-w9x5fv
;; (item 3, "rejecting duplicate canonical map keys"), NOT by this coverage
;; bead (tests-only). These tests therefore (a) DOCUMENT + PIN the current
;; observable behaviour so the gap is visible and a regression can't slip in
;; unnoticed, and (b) carry the spec-correct assertion commented inline so
;; the flip to fail-closed is a one-line edit when the source fix lands.

(deftest duplicate-canonical-keys
  (testing "two distinct host instants for the same moment encode to identical key bytes"
    ;; Holds on both hosts: a host date and its EDN-instant counterpart are
    ;; one identity fact (the cross-host instant-collapse property).
    (let [d #?(:clj (java.util.Date. 1781049600000) :cljs (js/Date. 1781049600000))
          i #inst "2026-06-10T00:00:00.000-00:00"]
      (is (= (id/canonical-bytes d) (id/canonical-bytes i))
          "distinct host representations, identical CEDN-1 bytes")
      (is (id/identical-identity? d i))))
  #?(:clj
     ;; The map-collision premise only holds where the two same-instant keys
     ;; are DISTINCT map keys — i.e. on the JVM, where Date and Instant are
     ;; distinct types that `=` does not equate.
     (let [dup-map {(java.util.Date. 1781049600000)               :via-date
                    (java.time.Instant/ofEpochMilli 1781049600000) :via-instant}]
       (testing "a JVM map keyed by Date + Instant for one instant keeps BOTH entries"
         (is (not= (java.util.Date. 1781049600000)
                   (java.time.Instant/ofEpochMilli 1781049600000))
             "Date and Instant are distinct keys (= does not equate them)")
         (is (= 2 (count dup-map))
             "the map carries two entries whose canonical key bytes collide"))
       (testing "KNOWN GAP (rf2-wgutc2 / rf2-w9x5fv): the duplicate canonical
                 key is NOT yet rejected — current behaviour pinned"
         ;; CURRENT behaviour: canonical-bytes emits both colliding key tokens.
         (is (string? (id/canonical-bytes dup-map))
             "current: encodes (does not throw) — collision is silently serialized")
         ;; SPEC-CORRECT behaviour, asserted once the source fix lands — flip to:
         ;;   (is (non-edn-id-error? #(id/canonical-bytes dup-map)))
         ;;   (is (non-edn-id-error? #(id/canonical       dup-map)))
         ;; (Conventions: duplicate canonical keys are invalid and MUST be
         ;; rejected before the value becomes a cache key / route id / work id.)
         (let [bytes (id/canonical-bytes dup-map)]
           ;; The colliding key token appears for BOTH entries inside the
           ;; group — the observable symptom the rejection will eliminate.
           (is (= 2 (count (re-seq #"t:2026-06-10T00:00:00\.000Z" bytes)))
               "the same key token appears twice — the duplicate-key defect")))))
  #?(:cljs
     (testing "CLJS collapses same-instant js/Date keys (no distinct-key
               collision to reject on this host)"
       ;; Documents WHY there is no CLJS map-collision leg: value-equal dates
       ;; are one key, so the map has a single entry and a single key token.
       (let [m {(js/Date. 1781049600000)              :a
                #inst "2026-06-10T00:00:00.000-00:00" :b}]
         (is (= 1 (count m))
             "value-equal js/Date keys collapse to one entry on CLJS")
         (is (= 1 (count (re-seq #"t:2026-06-10T00:00:00\.000Z"
                                 (id/canonical-bytes m)))))))))
