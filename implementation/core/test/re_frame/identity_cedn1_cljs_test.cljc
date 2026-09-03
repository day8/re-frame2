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

;; ---- map-order invariance ------------------------------------------------

(deftest map-order-invariance
  (testing "permuted map insertion order -> identical canonical bytes"
    (is (= (rf.identity/canonical-bytes {:page 1 :tag "cljs"})
           (rf.identity/canonical-bytes {:tag "cljs" :page 1})))
    (is (= (rf.identity/canonical-bytes {:filter {:archived? false :tag "cljs"}})
           (rf.identity/canonical-bytes {:filter {:tag "cljs" :archived? false}}))))
  (testing "generated nested maps are order-invariant under reshuffle"
    (is (nil?
          (loop [i 0, s 31337]
            (if (= i 400)
              nil
              (let [[v s1] (gen-edn s 3)]
                (if (and (map? v) (seq v))
                  (let [v' (shuffle-coll v s1)]
                    (if (= (rf.identity/canonical-bytes v) (rf.identity/canonical-bytes v'))
                      (recur (inc i) (lcg-next s1))
                      [v v']))
                  (recur (inc i) (lcg-next s1))))))))))

;; ---- scope / params identity (the resource-key motivation) ---------------

(deftest scope-and-params-identity
  (let [scope-a [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}]
        scope-b [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}]
        params-a {:slug "welcome" :include-comments? true}
        params-b {:include-comments? true :slug "welcome"}]
    (is (rf.identity/identical-identity? scope-a scope-b))
    (is (rf.identity/identical-identity? params-a params-b))
    (testing "different scopes with same params do NOT collide"
      (is (not (rf.identity/identical-identity?
                 [:rf.scope/session {:user-id "u-1"}]
                 [:rf.scope/session {:user-id "u-2"}]))))
    (testing "work id embeds the canonical scoped resource key + generation"
      (let [rkey [(rf.identity/canonical scope-a) :article/by-slug (rf.identity/canonical params-a)]
            work-id [:rf.work/resource rkey 4]]
        (is (= (rf.identity/canonical-bytes work-id)
               (rf.identity/canonical-bytes [:rf.work/resource
                                    [(rf.identity/canonical scope-b) :article/by-slug (rf.identity/canonical params-b)]
                                    4])))))))

;; ---- set ordering --------------------------------------------------------

(deftest set-ordering
  (testing "sets are order-invariant by canonical element bytes"
    (is (= (rf.identity/canonical-bytes #{3 1 2}) (rf.identity/canonical-bytes #{2 3 1})))
    (is (= (rf.identity/canonical-bytes #{:b :a :c}) (rf.identity/canonical-bytes #{:c :a :b}))))
  (testing "generated sets are order-invariant under reshuffle"
    (is (nil?
          (loop [i 0, s 4242]
            (if (= i 300)
              nil
              (let [[v s1] (gen-edn s 3)]
                (if (and (set? v) (seq v))
                  (if (= (rf.identity/canonical-bytes v) (rf.identity/canonical-bytes (shuffle-coll v s1)))
                    (recur (inc i) (lcg-next s1))
                    [v])
                  (recur (inc i) (lcg-next s1))))))))))

;; ---- vector/list/set distinctness + type tags ----------------------------

(deftest edn-kind-distinctness
  (testing "vector vs list are distinct EDN facts"
    (is (not= (rf.identity/canonical-bytes [1 2]) (rf.identity/canonical-bytes (list 1 2)))))
  (testing "vector vs set are distinct"
    (is (not= (rf.identity/canonical-bytes [1 2 3]) (rf.identity/canonical-bytes #{1 2 3}))))
  (testing "the type tag keeps scalar kinds distinct"
    (let [bs [(rf.identity/canonical-bytes "42")
              (rf.identity/canonical-bytes 42)
              (rf.identity/canonical-bytes :42)
              (rf.identity/canonical-bytes [1 2])
              (rf.identity/canonical-bytes (list 1 2))]]
      (is (= (count bs) (count (distinct bs))))))
  (testing "heterogeneous map keys are legal and ordered by key bytes"
    (is (string? (rf.identity/canonical-bytes {:a 1 "a" 2 0 3 true 4})))
    (is (= (rf.identity/canonical-bytes {:a 1 "a" 2 0 3})
           (rf.identity/canonical-bytes {0 3 "a" 2 :a 1}))))
  (testing "vectors preserve order (not sorted)"
    (is (not= (rf.identity/canonical-bytes [:a :b]) (rf.identity/canonical-bytes [:b :a])))))

;; ---- nil vs missing ------------------------------------------------------

(deftest nil-vs-missing
  (testing "present-nil and absent key are distinct identities"
    (is (not (rf.identity/identical-identity? {} {:page nil})))
    (is (not= (rf.identity/canonical-bytes {}) (rf.identity/canonical-bytes {:page nil}))))
  (testing "canonical preserves present-nil values"
    (is (= {:page nil} (rf.identity/canonical {:page nil})))
    (is (= nil (rf.identity/canonical nil)))))

;; ---- canonical returns an = value, order-normalized ----------------------

(deftest canonical-normalized-value
  (testing "canonical of map is =-equal but key-order-normalized"
    (is (= {:a 1 :b 2} (rf.identity/canonical {:b 2 :a 1}))))
  (testing "canonical recurses, preserving vector order"
    (is (= {:xs [3 1 2]} (rf.identity/canonical {:xs [3 1 2]}))))
  (testing "canonical of a value equals the value for in-domain scalars"
    (is (= "x" (rf.identity/canonical "x")))
    (is (= 7 (rf.identity/canonical 7)))
    (is (= :k (rf.identity/canonical :k)))))

;; ---- instant + uuid ------------------------------------------------------

(deftest instant-and-uuid
  (testing "uuid encodes lower-case RFC 4122 text"
    (is (= "u:11111111-1111-1111-1111-111111111111"
           (rf.identity/canonical-bytes #uuid "11111111-1111-1111-1111-111111111111"))))
  #?(:clj
     (testing "equivalent instants in different source timezones normalize to one UTC identity"
       (is (rf.identity/identical-identity?
             #inst "2026-06-10T10:00:00.000+10:00"
             #inst "2026-06-10T00:00:00.000-00:00"))
       (is (= "t:2026-06-10T00:00:00.000Z"
              (rf.identity/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00")))))
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
              (rf.identity/canonical-bytes (js/Date. 1781049600000))))
       (testing "a whole-second instant still renders the millisecond .000 suffix"
         (is (= "t:2026-06-10T00:00:00.000Z"
                (rf.identity/canonical-bytes (js/Date. "2026-06-10T00:00:00Z")))))
       (testing "sub-second precision is preserved in the token"
         (is (= "t:2026-06-10T00:00:00.123Z"
                (rf.identity/canonical-bytes (js/Date. 1781049600123)))))
       (testing "two js/Date objects built for the same instant in different
                 timezone literals collapse to one identity"
         (is (rf.identity/identical-identity?
               (js/Date. "2026-06-10T10:00:00+10:00")
               (js/Date. "2026-06-10T00:00:00Z"))))
       (testing "a js/Date is identity-equal to its EDN #inst counterpart for the
                 same instant (host-date and EDN-instant are one fact)"
         (is (= (rf.identity/canonical-bytes (js/Date. 1781049600000))
                (rf.identity/canonical-bytes #inst "2026-06-10T00:00:00.000-00:00")))))))

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

(defn- non-edn-id-reason
  "Run `thunk`; return the `:reason` of a caught `:rf.error/non-edn-identity`
  ex-info, or `:no-throw` / `:other-error` so a test can pin the exact
  fail-closed reason keyword."
  [thunk]
  (try
    (thunk)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (let [d (ex-data e)]
        (if (= :rf.error/non-edn-identity (:rf.error/id d))
          (:reason d)
          :other-error)))))

(deftest rejection-cases
  (testing "floats / NaN / infinities / ratios fail closed"
    (is (non-edn-id-error? #(rf.identity/canonical-bytes 1.5)))
    #?(:clj (is (non-edn-id-error? #(rf.identity/canonical-bytes (/ 1 3)))))
    #?(:cljs (is (non-edn-id-error? #(rf.identity/canonical-bytes js/NaN))))
    #?(:cljs (is (non-edn-id-error? #(rf.identity/canonical-bytes js/Infinity)))))
  (testing "integers outside the safe range fail closed"
    (is (non-edn-id-error? #(rf.identity/canonical-bytes 9007199254740992)))
    (is (non-edn-id-error? #(rf.identity/canonical-bytes -9007199254740992))))
  (testing "the safe-range boundaries are admitted"
    (is (= "i:9007199254740991"  (rf.identity/canonical-bytes 9007199254740991)))
    (is (= "i:-9007199254740991" (rf.identity/canonical-bytes -9007199254740991))))
  (testing "functions fail closed"
    (is (non-edn-id-error? #(rf.identity/canonical-bytes inc))))
  (testing "a nested host value fails the WHOLE identity closed (no host fallback)"
    (is (non-edn-id-error? #(rf.identity/canonical {:a {:b inc}})))
    (is (non-edn-id-error? #(rf.identity/canonical-bytes [1 2 inc]))))
  #?(:cljs
     (testing "a raw JS object fails closed"
       (is (non-edn-id-error? #(rf.identity/canonical-bytes #js {:tenant "acme"})))
       (testing "but its explicit EDN encoding is accepted"
         (is (string? (rf.identity/canonical-bytes {:tenant "acme"}))))))
  #?(:clj
     (testing "an arbitrary host object fails closed"
       (is (non-edn-id-error? #(rf.identity/canonical-bytes (java.lang.Object.)))))))

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
    (is (= (rf.identity/canonical-bytes {:z 1 :a 2 :m 3})
           (rf.identity/canonical-bytes {:a 2 :m 3 :z 1})
           (rf.identity/canonical-bytes {:m 3 :z 1 :a 2})))
    ;; Repeated calls are byte-identical (determinism).
    (is (= (rf.identity/canonical-bytes {:z 1 :a 2})
           (rf.identity/canonical-bytes {:z 1 :a 2})))
    ;; Sets order by element bytes regardless of construction order.
    (is (= (rf.identity/canonical-bytes #{:gamma :alpha :beta})
           (rf.identity/canonical-bytes #{:beta :gamma :alpha}))))
  (testing "canonical returns an =-equal value but ordering is NOT its contract"
    ;; Equal as a value, key-order normalized away.
    (is (= (rf.identity/canonical {:z 1 :a 2 :m 3})
           (rf.identity/canonical {:a 2 :m 3 :z 1})))
    (is (= {:a 2 :m 3 :z 1} (rf.identity/canonical {:z 1 :a 2 :m 3})))
    ;; The projection recurses but preserves vector order (vectors are
    ;; order-significant EDN facts).
    (is (= {:xs [3 1 2]} (rf.identity/canonical {:xs [3 1 2]})))
    ;; A set projection is =-equal to the source set.
    (is (= #{:alpha :beta :gamma} (rf.identity/canonical #{:gamma :alpha :beta}))))
  (testing "two values are equal-as-identity iff their canonical-bytes are ="
    ;; The normative equality contract (Conventions §Canonical EDN identity):
    ;; canonical-bytes equality is the identity, not canonical-value =.
    (is (= (rf.identity/identical-identity? {:z 1 :a 2} {:a 2 :z 1})
           (= (rf.identity/canonical-bytes {:z 1 :a 2}) (rf.identity/canonical-bytes {:a 2 :z 1}))
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
;; HARDENED (rf2-w9x5fv item 3) — the encoder now DETECTS the JVM collision:
;; `encode-map` (and the value-form `canonical`) compare the entries' canonical
;; key tokens and FAIL CLOSED with `:rf.error/non-edn-identity` on a duplicate,
;; rather than sorting by key bytes and emitting both colliding tokens. Both
;; surfaces share the one fail-closed rule, so `canonical` and `canonical-bytes`
;; never disagree. The tests below assert the fail-closed behaviour on the JVM
;; (the only host where Date and Instant are distinct keys for one instant).

(deftest duplicate-canonical-keys
  (testing "two distinct host instants for the same moment encode to identical key bytes"
    ;; Holds on both hosts: a host date and its EDN-instant counterpart are
    ;; one identity fact (the cross-host instant-collapse property).
    (let [d #?(:clj (java.util.Date. 1781049600000) :cljs (js/Date. 1781049600000))
          i #inst "2026-06-10T00:00:00.000-00:00"]
      (is (= (rf.identity/canonical-bytes d) (rf.identity/canonical-bytes i))
          "distinct host representations, identical CEDN-1 bytes")
      (is (rf.identity/identical-identity? d i))))
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
       (testing "rf2-w9x5fv item 3: a duplicate canonical key FAILS CLOSED
                 (Conventions §Map key canonicalization — duplicate canonical
                 keys are invalid and MUST be rejected before the value becomes
                 a cache key / route id / work id)"
         ;; BOTH surfaces reject the whole identity closed — no silent
         ;; serialization of colliding key tokens, and canonical + canonical-bytes
         ;; agree (one canonical form).
         (is (non-edn-id-error? #(rf.identity/canonical-bytes dup-map))
             "canonical-bytes rejects the duplicate-canonical-key map")
         (is (non-edn-id-error? #(rf.identity/canonical dup-map))
             "canonical rejects it too — the two surfaces share one fail-closed rule"))))
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
                                 (rf.identity/canonical-bytes m)))))))))

;; ---- rf2-eynsfe: the reserved tagged-instant canonical form --------------
;;
;; The canonical form of an instant is the reserved tagged tuple
;; [:rf.identity/instant "<RFC-3339 UTC millisecond text>"]. `canonical-bytes`
;; is UNCHANGED — a host instant AND the tuple both emit `t:<text>`, a plain
;; string stays `s:` — so the byte contract (and the frozen conformance
;; fixture) is untouched, while `canonical` no longer collapses an instant to a
;; bare string that aliases a look-alike string one level down. Laws + the
;; fail-closed cases run on BOTH hosts via the `.cljc`; the JVM-only
;; Date-vs-Instant and sub-millisecond legs ride `#?(:clj …)`.

(def ^:private sample-instant-text "2026-06-10T00:00:00.000Z")
(def ^:private sample-instant #inst "2026-06-10T00:00:00.000-00:00")
(def ^:private sample-tuple [:rf.identity/instant "2026-06-10T00:00:00.000Z"])

(deftest instant-tagged-canonical-form
  (testing "canonical returns the reserved tagged tuple; canonical-bytes emits t:<text>"
    (is (= :rf.identity/instant rf.identity/instant-marker))
    (is (= sample-tuple (rf.identity/canonical sample-instant)))
    (is (= "t:2026-06-10T00:00:00.000Z" (rf.identity/canonical-bytes sample-instant)))
    (is (= "t:2026-06-10T00:00:00.000Z" (rf.identity/canonical-bytes sample-tuple))))
  (testing "law 1 — canonical is idempotent; an already-tagged tuple is returned unchanged"
    (is (= (rf.identity/canonical (rf.identity/canonical sample-instant)) (rf.identity/canonical sample-instant)))
    (is (= sample-tuple (rf.identity/canonical sample-tuple))))
  (testing "law 2 — canonical-bytes agrees across the canonical projection"
    (is (= (rf.identity/canonical-bytes (rf.identity/canonical sample-instant))
           (rf.identity/canonical-bytes sample-instant)))
    (is (= (rf.identity/canonical-bytes (rf.identity/canonical sample-tuple))
           (rf.identity/canonical-bytes sample-tuple))))
  (testing "laws 3/4 — an instant is DISTINCT from a look-alike string on both surfaces"
    (is (not= (rf.identity/canonical sample-instant) (rf.identity/canonical sample-instant-text)))
    (is (not= (rf.identity/canonical-bytes sample-instant) (rf.identity/canonical-bytes sample-instant-text)))
    (is (not (rf.identity/identical-identity? sample-instant sample-instant-text))))
  (testing "a heterogeneous instant+string-keyed map is a LEGAL two-entry map on BOTH surfaces"
    (let [m {sample-instant :via-instant sample-instant-text :via-string}]
      (is (= 2 (count m)) "the instant key and the string key are distinct host keys")
      ;; canonical-bytes accepts it (no duplicate-canonical-key rejection) and
      ;; carries both a t: and an s: key token.
      (is (string? (rf.identity/canonical-bytes m)))
      (is (re-find #"t:2026-06-10T00:00:00\.000Z" (rf.identity/canonical-bytes m)))
      (is (re-find #"s:\"2026-06-10T00:00:00\.000Z\"" (rf.identity/canonical-bytes m)))
      ;; canonical accepts it too and keeps two entries, keyed distinctly.
      (let [c (rf.identity/canonical m)]
        (is (= 2 (count c)))
        (is (= :via-instant (get c sample-tuple)))
        (is (= :via-string (get c sample-instant-text)))))))

(deftest instant-tagged-fail-closed
  (testing "a vector under the reserved marker is validated STRICTLY — never a generic vector"
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant])))
        "wrong arity (1)")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical [:rf.identity/instant "2026-06-10T00:00:00.000Z" :extra])))
        "wrong arity (3)")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant 123])))
        "non-string payload")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant "2026-06-10T00:00:00Z"])))
        "not millisecond precision")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant "2026-13-01T00:00:00.000Z"])))
        "impossible month")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant "2026-02-30T00:00:00.000Z"])))
        "impossible day (Feb 30) — a host clock silently rolls it, the round-trip rejects it")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical [:rf.identity/instant "2026-06-12T24:00:00.000Z"])))
        "hour 24 folds to the next day — rejected by the round-trip")
    (is (= :invalid-canonical-instant
           (non-edn-id-reason #(rf.identity/canonical-bytes [:rf.identity/instant "10000-01-01T00:00:00.000Z"])))
        "5-digit year is outside the fixed-width portable window"))
  (testing "the portable-range boundaries are admitted, inclusive"
    (is (= "t:0000-01-01T00:00:00.000Z"
           (rf.identity/canonical-bytes [:rf.identity/instant "0000-01-01T00:00:00.000Z"])))
    (is (= "t:9999-12-31T23:59:59.999Z"
           (rf.identity/canonical-bytes [:rf.identity/instant "9999-12-31T23:59:59.999Z"])))
    (is (= [:rf.identity/instant "0000-01-01T00:00:00.000Z"]
           (rf.identity/canonical [:rf.identity/instant "0000-01-01T00:00:00.000Z"]))))
  (testing "the reserved marker as a PLAIN keyword is still an ordinary encodable value"
    (is (= "k::rf.identity/instant" (rf.identity/canonical-bytes :rf.identity/instant)))
    (is (= :rf.identity/instant (rf.identity/canonical :rf.identity/instant))))
  (testing "a host instant and its tagged tuple for one moment are a DUPLICATE canonical key"
    ;; both encode to the identical t:<text> token, so a map keyed by both fails
    ;; closed — holds on both hosts (js/Date and a vector are distinct keys).
    (is (= :duplicate-canonical-map-key
           (non-edn-id-reason
             #(rf.identity/canonical-bytes {sample-instant :a sample-tuple :b}))))
    (is (= :duplicate-canonical-map-key
           (non-edn-id-reason
             #(rf.identity/canonical {sample-instant :a sample-tuple :b})))))
  #?(:cljs
     (testing "an invalid js/Date (NaN time value) fails closed with :invalid-instant"
       (is (= :invalid-instant
              (non-edn-id-reason #(rf.identity/canonical-bytes (js/Date. "not-a-date")))))
       (is (= :invalid-instant
              (non-edn-id-reason #(rf.identity/canonical (js/Date. "not-a-date")))))))
  #?(:clj
     (testing "sub-millisecond JVM precision truncates to the millisecond text"
       (is (= "t:2026-06-10T00:00:00.123Z"
              (rf.identity/canonical-bytes (java.time.Instant/ofEpochSecond 1781049600 123456789))))
       (is (= [:rf.identity/instant "2026-06-10T00:00:00.123Z"]
              (rf.identity/canonical (java.time.Instant/ofEpochSecond 1781049600 123456789)))))))
