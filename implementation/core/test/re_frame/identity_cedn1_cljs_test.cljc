(ns re-frame.identity-cedn1-cljs-test
  "Law tests for canonical EDN identity and the CEDN-1 byte encoding (EP-0012,
  Conventions §Canonical EDN identity / §Canonical byte encoding): order
  invariance, UTF-8 byte ordering, kind distinctness, nil vs missing,
  instants, and the fail-closed rejections. Same seeded-PRNG approach as
  `re-frame.path-laws-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [re-frame.identity :as rf.identity]))

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

;; ---- order invariance ----------------------------------------------------

(defn- order-invariant?
  "Over `n` generated values satisfying `pred`, a PRNG reshuffle never changes
  the canonical bytes; nil when all pass, else the first failing value."
  [seed n pred]
  (loop [i 0, s seed]
    (if (= i n)
      nil
      (let [[v s1] (gen-edn s 3)]
        (if (and (pred v) (seq v)
                 (not= (rf.identity/canonical-bytes v) (rf.identity/canonical-bytes (shuffle-coll v s1))))
          [v]
          (recur (inc i) (lcg-next s1)))))))

(deftest map-order-invariance
  (is (= (rf.identity/canonical-bytes {:page 1 :tag "cljs"})
         (rf.identity/canonical-bytes {:tag "cljs" :page 1})))
  (is (nil? (order-invariant? 31337 400 map?)) "generated nested maps"))

(deftest set-ordering
  (is (= (rf.identity/canonical-bytes #{3 1 2}) (rf.identity/canonical-bytes #{2 3 1})))
  (is (nil? (order-invariant? 4242 300 set?)) "generated sets"))

;; ---- UTF-8 byte order, not UTF-16 code-unit order -------------------------
;;
;; CEDN-1 sorts map keys and set elements by their UTF-8 BYTES. The host string
;; comparators order UTF-16 code units, and the two orders disagree in exactly
;; one place: a supplementary character (a surrogate pair) against a BMP
;; character at or above U+E000. The discriminating pair is therefore
;;
;;   U+E000   BMP, private use   1 code unit  E000        UTF-8 EE 80 80
;;   U+10000  supplementary      2 code units D800 DC00   UTF-8 F0 90 80 80
;;
;; Code-unit order puts U+10000 first (D800 < E000); byte order puts U+E000
;; first (EE < F0). An ASCII or low-BMP payload cannot tell the two apart, so
;; the control deftest below pins that ordinary ordering did not move. The
;; strings are built from explicit code points so the source stays pure ASCII
;; (a literal surrogate pair is easily mangled into two lone halves).

(def ^:private high-bmp (str (char 0xE000)))
(def ^:private astral (str (char 0xD800) (char 0xDC00)))

(defn- utf8-bytes
  "The unsigned UTF-8 bytes of `s` — an oracle that shares nothing with the
  encoder's comparator."
  [s]
  #?(:clj  (mapv #(bit-and % 0xFF) (.getBytes ^String s "UTF-8"))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) s)))))

(defn- compare-bytes
  "Lexicographic order over two byte vectors, a proper prefix first. (`compare`
  on vectors orders by length first, so it is not this.)"
  [xs ys]
  (or (first (remove zero? (map compare xs ys)))
      (compare (count xs) (count ys))))

(defn- byte-sorted [tokens]
  (sort-by utf8-bytes compare-bytes tokens))

(defn- set-bytes-by [sort-fn xs]
  (str "q#{" (str/join " " (sort-fn (map rf.identity/canonical-bytes xs))) "}"))

(defn- map-bytes-by [sort-fn m]
  (let [vtok (into {} (map (fn [[k v]] [(rf.identity/canonical-bytes k)
                                        (rf.identity/canonical-bytes v)]))
                   m)]
    (str "m{" (str/join " " (mapcat (fn [kt] [kt (vtok kt)]) (sort-fn (keys vtok)))) "}")))

(def ^:private boundary-pool
  "Strings spanning every UTF-8 length boundary and both sides of the
  surrogate block, plus a proper-prefix pair."
  ["" "a" "ab" "z"
   (str (char 0x7F)) (str (char 0x80)) (str (char 0x7FF)) (str (char 0x800))
   (str (char 0xD7FF)) high-bmp (str (char 0xFFFD)) (str (char 0xFFFF))
   astral
   (str (char 0xD83D) (char 0xDE00))   ; U+1F600
   (str (char 0xDBFF) (char 0xDFFF))   ; U+10FFFF, the last code point
   (str "a" high-bmp) (str "a" astral)])

(deftest utf8-byte-order-of-map-keys-and-set-elements
  (testing "the pair is discriminating: code-unit order and byte order disagree on it"
    (is (neg? (compare astral high-bmp)) "code-unit order puts U+10000 first")
    (is (pos? (compare-bytes (utf8-bytes astral) (utf8-bytes high-bmp)))
        "UTF-8 byte order puts U+E000 first"))
  (testing "a mixed map orders its keys by UTF-8 bytes: U+E000 before U+10000"
    (is (= (str "m{s:\"a\" k::ascii s:\"" high-bmp "\" k::high-bmp s:\"" astral "\" k::astral}")
           (rf.identity/canonical-bytes {astral :astral, high-bmp :high-bmp, "a" :ascii}))))
  (testing "a mixed set orders its elements by UTF-8 bytes: U+E000 before U+10000"
    (is (= (str "q#{s:\"a\" s:\"" high-bmp "\" s:\"" astral "\"}")
           (rf.identity/canonical-bytes #{astral high-bmp "a"}))))
  (testing "keyword and composite tokens follow the same byte order"
    (is (= (str "q#{k::" high-bmp " k::" astral "}")
           (rf.identity/canonical-bytes #{(keyword astral) (keyword high-bmp)})))
    (is (= (str "m{v[s:\"" high-bmp "\"] i:2 v[s:\"" astral "\"] i:1}")
           (rf.identity/canonical-bytes {[astral] 1, [high-bmp] 2}))))
  (testing "set and map order agree with a UTF-8 byte sort across every boundary"
    (is (= (set-bytes-by byte-sorted boundary-pool)
           (rf.identity/canonical-bytes (set boundary-pool))))
    (is (= (map-bytes-by byte-sorted (zipmap boundary-pool (range)))
           (rf.identity/canonical-bytes (zipmap boundary-pool (range)))))))

(deftest ordinary-ordering-is-unchanged
  ;; The control. Everywhere except a surrogate meeting U+E000-U+FFFF, UTF-8
  ;; byte order IS code-unit order, so these bytes are the host-sorted bytes.
  (testing "ASCII and BMP below the surrogate block: a pinned literal"
    (is (= (str "m{k::a i:1 k::b i:2 s:\"a\" i:3 s:\"" (char 0xE9) "\" i:4 s:\""
                (char 0x4E2D) "\" i:5}")
           (rf.identity/canonical-bytes {(str (char 0x4E2D)) 5, :b 2, (str (char 0xE9)) 4,
                                         "a" 3, :a 1}))))
  (testing "pools that never mix a surrogate with U+E000-U+FFFF sort exactly as the host does"
    (doseq [pool [["b" "a" "ab" "" "z" (str (char 0xE9)) (str (char 0x4E2D)) (str (char 0xD7FF))]
                  [high-bmp (str (char 0xFFFD)) (str (char 0xFFFF)) (str (char 0xF900))]
                  [astral (str (char 0xD83D) (char 0xDE00)) (str (char 0xDBFF) (char 0xDFFF))]]]
      (is (= (set-bytes-by sort pool) (rf.identity/canonical-bytes (set pool))))
      (is (= (map-bytes-by sort (zipmap pool (range)))
             (rf.identity/canonical-bytes (zipmap pool (range))))))))

;; ---- kind distinctness ---------------------------------------------------

(deftest edn-kind-distinctness
  (let [bs (map rf.identity/canonical-bytes ["42" 42 :42 [1 2] (list 1 2) #{1 2}])]
    (is (= (count bs) (count (distinct bs))) "the type tag keeps every kind distinct"))
  (is (= (rf.identity/canonical-bytes {:a 1 "a" 2 0 3 true 4})
         (rf.identity/canonical-bytes {true 4 0 3 "a" 2 :a 1}))
      "heterogeneous map keys are legal and ordered by key bytes")
  (is (not= (rf.identity/canonical-bytes [:a :b]) (rf.identity/canonical-bytes [:b :a]))
      "vectors preserve order"))

(deftest nil-vs-missing
  (is (not= (rf.identity/canonical-bytes {}) (rf.identity/canonical-bytes {:page nil})))
  (is (= [{:page nil} nil] [(rf.identity/canonical {:page nil}) (rf.identity/canonical nil)])
      "canonical preserves present-nil values"))

;; ---- instant + uuid ------------------------------------------------------

(deftest instant-and-uuid
  (is (= "u:11111111-1111-1111-1111-111111111111"
         (rf.identity/canonical-bytes #uuid "11111111-1111-1111-1111-111111111111"))
      "uuid encodes lower-case RFC 4122 text")
  #?(:clj
     (testing "equivalent instants in different source timezones normalize to one UTC identity"
       (is (rf.identity/identical-identity?
             #inst "2026-06-10T10:00:00.000+10:00"
             #inst "2026-06-10T00:00:00.000-00:00"))
       (is (= "t:2026-06-10T00:00:00.000Z"
              (rf.identity/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00")))))
  ;; js/Date encoding rides its own `.toISOString` branch
  #?(:cljs
     (testing "a js/Date encodes to the exact millis-UTC t:...Z token"
       (is (= ["t:2026-06-10T00:00:00.000Z" "t:2026-06-10T00:00:00.123Z"]
              (map #(rf.identity/canonical-bytes (js/Date. %)) [1781049600000 1781049600123])))
       (is (rf.identity/identical-identity?
             (js/Date. "2026-06-10T10:00:00+10:00")
             (js/Date. "2026-06-10T00:00:00Z"))
           "timezone literals for one instant collapse to one identity"))))

;; ---- fail-closed rejection -----------------------------------------------

(defn- non-edn-id-reason
  "The `:reason` of a caught `:rf.error/non-edn-identity`, or :no-throw /
  :other-error."
  [thunk]
  (try
    (thunk)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (let [d (ex-data e)]
        (if (= :rf.error/non-edn-identity (:rf.error/id d))
          (:reason d)
          :other-error)))))

(defn- non-edn-id-error? [thunk]
  (not (#{:no-throw :other-error} (non-edn-id-reason thunk))))

(deftest rejection-cases
  (testing "floats / NaN / infinities / ratios, out-of-range integers, functions
            and nested host values fail the whole identity closed"
    (is (every? non-edn-id-error?
                [#(rf.identity/canonical-bytes 1.5)
                 #(rf.identity/canonical-bytes 9007199254740992)
                 #(rf.identity/canonical-bytes -9007199254740992)
                 #(rf.identity/canonical-bytes inc)
                 #(rf.identity/canonical {:a {:b inc}})
                 #(rf.identity/canonical-bytes [1 2 inc])]))
    #?(:clj (is (non-edn-id-error? #(rf.identity/canonical-bytes (/ 1 3)))))
    #?(:clj (is (non-edn-id-error? #(rf.identity/canonical-bytes (java.lang.Object.)))))
    #?(:cljs (is (every? non-edn-id-error?
                         [#(rf.identity/canonical-bytes js/NaN)
                          #(rf.identity/canonical-bytes js/Infinity)
                          #(rf.identity/canonical-bytes #js {:tenant "acme"})]))))
  (is (= ["i:9007199254740991" "i:-9007199254740991"]
         (map rf.identity/canonical-bytes [9007199254740991 -9007199254740991]))
      "the safe-range boundaries are admitted"))

;; ---- canonical projection: ordering is owned by canonical-bytes ----------
;;
;; `canonical` returns an =-equal, recursively normalized value but does NOT
;; promise an entry order; ordering belongs to `canonical-bytes` alone.

(deftest canonical-projection-ordering-contract
  (is (= [{:a 2 :m 3 :z 1} {:xs [3 1 2]} #{:alpha :beta :gamma}]
         (map rf.identity/canonical [{:z 1 :a 2 :m 3} {:xs [3 1 2]} #{:gamma :alpha :beta}]))))

;; ---- duplicate canonical map keys (the host-value collision) -------------
;;
;; Two distinct host values can encode to the same key bytes: on the JVM a
;; java.util.Date and a java.time.Instant for one instant are distinct map
;; keys with one `t:` token. Conventions §Canonical byte encoding: duplicate
;; canonical keys MUST be rejected. (CLJS has one host date type, and `=`
;; equates same-instant dates, so the collision is JVM-only.)

(deftest duplicate-canonical-keys
  (let [d #?(:clj (java.util.Date. 1781049600000) :cljs (js/Date. 1781049600000))
        i #inst "2026-06-10T00:00:00.000-00:00"]
    (is (= (rf.identity/canonical-bytes d) (rf.identity/canonical-bytes i))
        "a host date and its EDN instant are one identity on both hosts"))
  #?(:clj
     (let [dup-map {(java.util.Date. 1781049600000)               :via-date
                    (java.time.Instant/ofEpochMilli 1781049600000) :via-instant}]
       (is (= [true true] [(non-edn-id-error? #(rf.identity/canonical-bytes dup-map))
                           (non-edn-id-error? #(rf.identity/canonical dup-map))])
           "both surfaces reject the duplicate canonical key under one rule"))))

;; ---- the reserved tagged-instant canonical form -------------------------
;;
;; An instant canonicalizes to [:rf.identity/instant "<RFC-3339 UTC millis>"];
;; `canonical-bytes` emits `t:<text>` for a host instant and the tuple alike,
;; so `canonical` never collapses an instant into a look-alike string.

(def ^:private sample-instant-text "2026-06-10T00:00:00.000Z")
(def ^:private sample-instant #inst "2026-06-10T00:00:00.000-00:00")
(def ^:private sample-tuple [:rf.identity/instant "2026-06-10T00:00:00.000Z"])

(deftest instant-tagged-canonical-form
  (is (= [sample-tuple sample-tuple]
         [(rf.identity/canonical sample-instant) (rf.identity/canonical sample-tuple)])
      "canonical returns the tagged tuple, and is idempotent on it")
  (is (= ["t:2026-06-10T00:00:00.000Z" "t:2026-06-10T00:00:00.000Z"]
         [(rf.identity/canonical-bytes sample-instant) (rf.identity/canonical-bytes sample-tuple)]))
  (is (= [true true] [(not= (rf.identity/canonical sample-instant) (rf.identity/canonical sample-instant-text))
                      (not= (rf.identity/canonical-bytes sample-instant)
                            (rf.identity/canonical-bytes sample-instant-text))])
      "an instant is distinct from a look-alike string on both surfaces")
  (testing "an instant+string-keyed map is a legal two-entry map on both surfaces"
    (let [m  {sample-instant :via-instant sample-instant-text :via-string}
          bs (rf.identity/canonical-bytes m)]
      (is (= [true true] [(boolean (re-find #"t:2026-06-10T00:00:00\.000Z" bs))
                          (boolean (re-find #"s:\"2026-06-10T00:00:00\.000Z\"" bs))]))
      (is (= {sample-tuple :via-instant sample-instant-text :via-string}
             (rf.identity/canonical m))))))

(deftest instant-tagged-fail-closed
  (testing "a vector under the reserved marker is validated strictly, never as a generic vector"
    (is (= (repeat 8 :invalid-canonical-instant)
           (map (fn [[f v]] (non-edn-id-reason #(f v)))
                [[rf.identity/canonical-bytes [:rf.identity/instant]]                                ; arity 1
                 [rf.identity/canonical [:rf.identity/instant "2026-06-10T00:00:00.000Z" :extra]]    ; arity 3
                 [rf.identity/canonical-bytes [:rf.identity/instant 123]]                            ; non-string
                 [rf.identity/canonical-bytes [:rf.identity/instant "2026-06-10T00:00:00Z"]]         ; not millis
                 [rf.identity/canonical-bytes [:rf.identity/instant "2026-13-01T00:00:00.000Z"]]     ; month 13
                 [rf.identity/canonical-bytes [:rf.identity/instant "2026-02-30T00:00:00.000Z"]]     ; Feb 30 rolls
                 [rf.identity/canonical [:rf.identity/instant "2026-06-12T24:00:00.000Z"]]           ; hour 24 folds
                 [rf.identity/canonical-bytes [:rf.identity/instant "10000-01-01T00:00:00.000Z"]]])))) ; 5-digit year
  (is (= ["t:0000-01-01T00:00:00.000Z" "t:9999-12-31T23:59:59.999Z"
          [:rf.identity/instant "0000-01-01T00:00:00.000Z"]]
         [(rf.identity/canonical-bytes [:rf.identity/instant "0000-01-01T00:00:00.000Z"])
          (rf.identity/canonical-bytes [:rf.identity/instant "9999-12-31T23:59:59.999Z"])
          (rf.identity/canonical [:rf.identity/instant "0000-01-01T00:00:00.000Z"])])
      "the portable-range boundaries are admitted, inclusive")
  (is (= ["k::rf.identity/instant" :rf.identity/instant]
         [(rf.identity/canonical-bytes :rf.identity/instant) (rf.identity/canonical :rf.identity/instant)])
      "the marker as a plain keyword is an ordinary value")
  (is (= [:duplicate-canonical-map-key :duplicate-canonical-map-key]
         [(non-edn-id-reason #(rf.identity/canonical-bytes {sample-instant :a sample-tuple :b}))
          (non-edn-id-reason #(rf.identity/canonical {sample-instant :a sample-tuple :b}))])
      "a host instant and its tuple for one moment are a duplicate canonical key")
  #?(:cljs
     (is (= [:invalid-instant :invalid-instant]
            [(non-edn-id-reason #(rf.identity/canonical-bytes (js/Date. "not-a-date")))
             (non-edn-id-reason #(rf.identity/canonical (js/Date. "not-a-date")))])
         "an invalid js/Date fails closed"))
  #?(:clj
     (is (= ["t:2026-06-10T00:00:00.123Z" [:rf.identity/instant "2026-06-10T00:00:00.123Z"]]
            (let [inst (java.time.Instant/ofEpochSecond 1781049600 123456789)]
              [(rf.identity/canonical-bytes inst) (rf.identity/canonical inst)]))
         "sub-millisecond JVM precision truncates to the millisecond text")))
