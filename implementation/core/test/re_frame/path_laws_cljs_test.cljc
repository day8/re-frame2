(ns re-frame.path-laws-cljs-test
  "Law / property tests for the `:rf/path` algebra (EP-0012, Conventions
  §Path laws). Covers the put-lookup family, compose associativity,
  prefix?/overlap? symmetry, the root-path laws, the missing-vs-present-nil
  distinction, and template normalization (sugar -> data form).

  No `clojure.test.check` on the classpath — these use a small seeded
  generator (`gen-edn` / `gen-path`) sampled over a fixed seed so the
  property checks are deterministic and reproducible across CLJ/CLJS. The
  hand-rolled generator emits the same value stream on both hosts because
  it draws from a self-contained linear-congruential PRNG, not the host
  RNG.

  Named `*-cljs-test` so the shadow-cljs `:node-test` build (ns-regexp
  `cljs-test$`) discovers it; the `-test` suffix also satisfies the JVM
  cognitect test-runner, so this one `.cljc` file runs on both runtimes."
  (:refer-clojure :exclude [])
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.error :as error]
            [re-frame.identity :as identity]
            [re-frame.path :as path]))

;; ---- a deterministic, host-portable PRNG ---------------------------------
;;
;; A 32-bit linear-congruential generator with the Numerical-Recipes
;; constants. Identical sequence on CLJ and CLJS because every op stays in
;; the int32 range under `unchecked` arithmetic emulation via `bit-and`.

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

;; ---- generators ----------------------------------------------------------

(def ^:private seg-pool
  [:a :b :c :x :y 0 1 2 "k" 'sym true false nil])

(defn- gen-seg [state]
  (nth seg-pool (rnd state (count seg-pool))))

(defn- gen-path
  "Generate a path of 0..max-len segments drawn from `seg-pool`. Returns
  `[path next-state]`. nil segments are allowed (a present-nil key path)."
  [state max-len]
  (let [len (rnd state (inc max-len))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i len)
        [acc s]
        (recur (inc i) (lcg-next s) (conj acc (gen-seg s)))))))

(defn- gen-edn
  "Generate a small EDN value (maps/vectors of scalars) to `depth`.
  Returns `[value next-state]`."
  [state depth]
  (let [k (rnd state (if (zero? depth) 4 7))
        s (lcg-next state)]
    (case k
      0 [(nth seg-pool (rnd s (count seg-pool))) (lcg-next s)]
      1 [(rnd s 1000) (lcg-next s)]
      2 [(str "v" (rnd s 50)) (lcg-next s)]
      3 [nil (lcg-next s)]
      ;; composites only when depth remains
      4 (let [n (rnd s 3)]
          (loop [i 0, st (lcg-next s), acc {}]
            (if (= i n)
              [acc st]
              (let [kk (gen-seg st)
                    [vv st'] (gen-edn (lcg-next st) (dec depth))]
                (recur (inc i) st' (assoc acc kk vv))))))
      5 (let [n (rnd s 3)]
          (loop [i 0, st (lcg-next s), acc []]
            (if (= i n)
              [acc st]
              (let [[vv st'] (gen-edn (lcg-next st) (dec depth))]
                (recur (inc i) st' (conj acc vv))))))
      6 [(rnd s 1000) (lcg-next s)])))

(defn- samples
  "Run `f` over `n` deterministic draws of `[value path x]`. `f` returns
  truthy on a passing case; returns the first failing `[value path x]` or
  nil if all pass."
  [n f]
  (loop [i 0, s 12345]
    (if (= i n)
      nil
      (let [[v s1]  (gen-edn s 3)
            [p s2]  (gen-path s1 4)
            [x s3]  (gen-edn s2 2)]
        (if (f v p x)
          (recur (inc i) (lcg-next s3))
          [v p x])))))

;; ---- root-path laws ------------------------------------------------------

(deftest root-path-laws
  (testing "get(s, []) = s"
    (is (= {:a 1} (path/get {:a 1} [])))
    (is (= 42 (path/get 42 []))))
  (testing "lookup(s, []) = present s"
    (is (= {:present? true :value {:a 1}} (path/lookup {:a 1} [])))
    (is (= {:present? true :value nil} (path/lookup nil []))))
  (testing "put(s, [], x) = x  (the law raw assoc-in violates)"
    (is (= {:b 2} (path/put {:a 1} [] {:b 2})))
    ;; raw assoc-in would give {:a 1, nil {:b 2}}
    (is (not= (assoc-in {:a 1} [] {:b 2}) (path/put {:a 1} [] {:b 2}))))
  (testing "over(s, [], f) = f(s)"
    (is (= {:a 2} (path/over {:a 1} [] #(update % :a inc)))))
  (testing "overlap?([], p) = true for every p"
    (is (true? (path/overlap? [] [])))
    (is (true? (path/overlap? [] [:anything :deep 3])))
    (is (true? (path/overlap? [:anything] [])))))

;; ---- put-lookup family ---------------------------------------------------

(deftest put-lookup-law
  (testing "lookup(put(s, p, x), p) = {:present? true :value x} for all p,x"
    (is (nil? (samples 400
                (fn [v p x]
                  (= {:present? true :value x}
                     (path/lookup (path/put v p x) p)))))))
  (testing "explicitly at p=[] and x=nil (the nil-write trap)"
    (is (= {:present? true :value nil}
           (path/lookup (path/put {:page 1} [:page] nil) [:page])))
    (is (= {:present? true :value nil}
           (path/lookup (path/put {:a 1} [] nil) [])))))

(deftest lookup-put-law
  (testing "if lookup(s,p) present with x then put(s,p,x) = s"
    (is (nil? (samples 400
                (fn [v p _x]
                  (let [{:keys [present? value]} (path/lookup v p)]
                    (if present?
                      (= v (path/put v p value))
                      true))))))))

(deftest put-put-law
  (testing "put(put(s,p,x),p,y) = put(s,p,y)"
    (is (nil? (samples 400
                (fn [v p x]
                  (= (path/put v p x)
                     (path/put (path/put v p :first) p x))))))))

;; ---- compose laws --------------------------------------------------------

(deftest compose-laws
  (testing "compose(p,[]) = p and compose([],p) = p"
    (is (= [:a :b] (path/compose [:a :b] [])))
    (is (= [:a :b] (path/compose [] [:a :b]))))
  (testing "compose associativity over generated paths"
    (is (nil?
          (loop [i 0, s 999]
            (if (= i 300)
              nil
              (let [[p s1] (gen-path s 3)
                    [q s2] (gen-path s1 3)
                    [r s3] (gen-path s2 3)]
                (if (= (path/compose (path/compose p q) r)
                       (path/compose p (path/compose q r)))
                  (recur (inc i) (lcg-next s3))
                  [p q r])))))))
  (testing "get-compose: get(s, compose(p,q)) = get(get(s,p), q) when intermediate present"
    (let [s {:cart {:items {42 {:qty 2}}}}
          p [:cart :items]
          q [42 :qty]]
      (is (= (path/get s (path/compose p q))
             (path/get (path/get s p) q)))
      (is (= 2 (path/get s (path/compose p q)))))))

;; ---- over law ------------------------------------------------------------

(deftest over-law
  (testing "over(s,p,identity) = s when present"
    (is (nil? (samples 300
                (fn [v p _x]
                  (let [{:keys [present?]} (path/lookup v p)]
                    (if present?
                      (= v (path/over v p identity))
                      true)))))))
  (testing "over(s,p,f) = put(s,p,f(get(s,p))) (nil-on-missing get semantics)"
    (is (nil? (samples 300
                (fn [v p _x]
                  (= (path/over v p (fn [x] [:wrapped x]))
                     (path/put v p [:wrapped (path/get v p)]))))))))

;; ---- prefix? / overlap? --------------------------------------------------

(deftest prefix-and-overlap
  (testing "prefix? basics"
    (is (true? (path/prefix? [:cart] [:cart :items 42])))
    (is (false? (path/prefix? [:cart :items 42] [:cart])))
    (is (true? (path/prefix? [] [:anything])))
    (is (true? (path/prefix? [:a] [:a]))))
  (testing "overlap? examples from the spec"
    (is (true? (path/overlap? [:cart :items] [:cart :items 42 :qty])))
    (is (true? (path/overlap? [:cart :items 42 :qty] [:cart :items 42])))
    (is (false? (path/overlap? [:cart :items 42] [:cart :items 43])))
    (is (false? (path/overlap? [:cart :items] [:profile :display-name]))))
  (testing "overlap? is symmetric over generated path pairs"
    (is (nil?
          (loop [i 0, s 7777]
            (if (= i 400)
              nil
              (let [[p s1] (gen-path s 4)
                    [q s2] (gen-path s1 4)]
                (if (= (path/overlap? p q) (path/overlap? q p))
                  (recur (inc i) (lcg-next s2))
                  [p q]))))))))

;; ---- missing vs present nil ----------------------------------------------

(deftest missing-versus-present-nil
  (testing "absent key vs present-nil are distinct under lookup"
    (is (= {:present? false} (path/lookup {} [:page])))
    (is (= {:present? true :value nil} (path/lookup {:page nil} [:page]))))
  (testing "get returns not-found only when missing, never for present-nil"
    (is (= ::nf (path/get {} [:page] ::nf)))
    (is (= nil  (path/get {:page nil} [:page] ::nf)))))

;; ---- template normalization (disposition 2) ------------------------------

(deftest template-normalization
  (testing "'?name sugar normalizes to the data form [:rf.path/param :name]"
    (is (= [:billing :invoices :by-id [:rf.path/param :invoice-id] :email]
           (path/normalize-template
             [:billing :invoices :by-id '?invoice-id :email]))))
  (testing "already-canonical data-form passes through unchanged (idempotent)"
    (let [canon [:billing [:rf.path/param :invoice-id] :email]]
      (is (= canon (path/normalize-template canon)))
      (is (= canon (path/normalize-template (path/normalize-template canon))))))
  (testing "'?name NEVER survives into the normalized shape"
    (is (not (some symbol? (path/normalize-template [:a '?x :b '?y])))))
  (testing "a literal symbol that is not a ?-marker passes through"
    (is (= [:a 'plain :b] (path/normalize-template [:a 'plain :b])))
    ;; a bare `?` symbol is not a marker (name length 1)
    (is (= ['?] (path/normalize-template ['?]))))
  (testing "template? detection"
    (is (true? (path/template? [:a '?x])))
    (is (true? (path/template? [:a [:rf.path/param :x]])))
    (is (false? (path/template? [:a :b 3]))))
  (testing "param-segment? recognises only the data form"
    (is (true? (path/param-segment? [:rf.path/param :id])))
    (is (false? (path/param-segment? '?id)))
    (is (false? (path/param-segment? [:rf.path/param :id :extra])))
    (is (false? (path/param-segment? [:rf.path/param "id"])))))

;; ---- instantiate ---------------------------------------------------------

(deftest instantiate-template
  (testing "substitutes bound params, leaves literals"
    (is (= [:billing :invoices :by-id "iid" :email]
           (path/instantiate
             [:billing :invoices :by-id '?invoice-id :email]
             {:invoice-id "iid"}))))
  (testing "accepts a named-path-declaration map"
    (is (= [:profile :display-name]
           (path/instantiate {:id :p :rf/path [:profile :display-name]} {}))))
  (testing "a present-nil binding is a legal substitution"
    (is (= [:a nil :c] (path/instantiate [:a '?x :c] {:x nil}))))
  (testing "an unbound param fails closed"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (path/instantiate [:a '?x] {})))))

;; ---- rf2-ehkut7: instantiate validates the substituted binding ------------
;;
;; `instantiate` is a CONCRETE-path PRODUCER (its result feeds get/put/over),
;; so it routes the substituted result through `normalize-concrete` — the
;; SAME validated boundary frame classification and resource scope use
;; (rf2-w9x5fv). A binding whose VALUE is outside the concrete-segment domain
;; (a fn / host object / composite vector|map|set, or a literal
;; `[:rf.path/param …]` data form) FAILS CLOSED with `:rf.error/bad-path`
;; rather than silently smuggling a non-portable segment into a path
;; presented as concrete. The defect class this closes is exactly the
;; silent-non-portable-segment leak `normalize-concrete` exists to prevent;
;; pre-fix `instantiate` substituted the binding unchecked.

(deftest instantiate-rejects-non-concrete-binding
  (testing "a function-valued binding fails closed with :rf.error/bad-path"
    (is (= :rf.error/bad-path
           (try (path/instantiate [:a [:rf.path/param :x] :b] {:x identity}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e))))
           )
        "a fn binding is a host handle — never a concrete EDN segment"))
  (testing "a composite (vector) binding fails closed — not a concrete segment"
    (is (= :rf.error/bad-path
           (try (path/instantiate [:by-id [:rf.path/param :id]] {:id [:nested]}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))
        "a vector binding is indistinguishable from a template-param data form
         once substituted; it is rejected at the concrete boundary"))
  (testing "a map binding fails closed"
    (is (= :rf.error/bad-path
           (try (path/instantiate [:a '?x] {:x {:k 1}}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))))
  (testing "a set binding fails closed"
    (is (= :rf.error/bad-path
           (try (path/instantiate [:a '?x] {:x #{:a}}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))))
  (testing "the error names the offending substituted segment under :bad-segment"
    (let [data (try (path/instantiate [:a '?x] {:x [:nope]}) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= [:nope] (:bad-segment data))
          ":bad-segment surfaces the non-concrete binding value")))
  (testing "every concrete-domain binding STILL instantiates (no regression)"
    (is (= [:billing :invoices :by-id "iid" :email]
           (path/instantiate [:billing :invoices :by-id '?invoice-id :email]
                             {:invoice-id "iid"}))
        "string binding")
    (is (= [:by-id #uuid "00000000-0000-0000-0000-000000000001"]
           (path/instantiate [:by-id '?id]
                             {:id #uuid "00000000-0000-0000-0000-000000000001"}))
        "uuid binding")
    (is (= [:n 42] (path/instantiate [:n '?i] {:i 42})) "integer binding")
    (is (= [:a nil :c] (path/instantiate [:a '?x :c] {:x nil}))
        "present-nil is a legal concrete segment — must NOT be rejected")))

;; ---- vector-index container policy (rf2-orcbow point 5) ------------------
;;
;; The intermediate-container policy (Conventions §Path laws) defines the
;; vector-index case EXPLICITLY rather than letting a host `assoc` throw.
;; The generative laws above prove put-lookup / put-put hold for every
;; generated path, but the SPECIFIC vector-vs-map behaviour was only inferred
;; from them. These direct examples PIN the intended policy so a change to
;; `container-for` is caught:
;;
;;   - a vector holds an entry ONLY at an in-range non-negative integer index;
;;   - an out-of-range index, a negative index, or a non-integer segment
;;     REPLACES the vector with a fresh map (never throws, never grows the
;;     vector) — keeping `put` total so put-lookup holds for every path;
;;   - an existing compatible vector is preserved (not silently rebuilt).

(deftest vector-index-container-policy
  (testing "in-range non-negative integer index: vector is preserved + updated in place"
    (is (= [10 99 30] (path/put [10 20 30] [1] 99)))
    (is (= {:present? true :value 20} (path/lookup [10 20 30] [1])))
    (is (= 20 (path/get [10 20 30] [1])))
    ;; index 0 and the last in-range index are both legal vector steps
    (is (= [99 20 30] (path/put [10 20 30] [0] 99)))
    (is (= [10 20 99] (path/put [10 20 30] [2] 99)))
    ;; a nested vector under a map key composes through
    (is (= 30 (path/get {:xs [10 20 30]} [:xs 2])))
    (is (= {:xs [10 99 30]} (path/put {:xs [10 20 30]} [:xs 1] 99))))
  (testing "out-of-range index: vector is REPLACED by a fresh map (no growth, no throw)"
    (is (= {5 99} (path/put [10 20] [5] 99)))
    ;; the index equal to count is out of range (a vector has no slot there)
    (is (= {2 99} (path/put [10 20] [2] 99)))
    ;; lookup of an out-of-range index on a vector is simply missing
    (is (= {:present? false} (path/lookup [10 20] [5])))
    (is (= {:present? false} (path/lookup [10 20] [2]))))
  (testing "negative index: vector is REPLACED by a fresh map (not a tail index)"
    (is (= {-1 99} (path/put [10 20] [-1] 99)))
    (is (= {:present? false} (path/lookup [10 20] [-1]))))
  (testing "non-integer segment over a vector: REPLACED by a fresh map"
    (is (= {:k 99} (path/put [10 20] [:k] 99)))
    (is (= {"s" 99} (path/put [10 20] ["s"] 99)))
    (is (= {:present? false} (path/lookup [10 20] [:k])))
    ;; lookup on a vector with a non-integer segment is missing, not a throw
    (is (= ::nf (path/get [10 20] [:k] ::nf))))
  (testing "deeper write that must create a fresh map UNDER a replaced vector"
    ;; [10 20] faced with non-integer :a is replaced by {}, then :b nests
    (is (= {:a {:b 99}} (path/put [10 20] [:a :b] 99)))
    ;; and the put-lookup law still holds across the replacement
    (is (= {:present? true :value 99}
           (path/lookup (path/put [10 20] [:a :b] 99) [:a :b]))))
  (testing "an in-range vector write does NOT clobber sibling indexes"
    (is (= [:a :B :c :d] (path/put [:a :b :c :d] [1] :B))))
  (testing "vector-index put at x=nil stores present-nil at the index (not dissoc)"
    (is (= [10 nil 30] (path/put [10 20 30] [1] nil)))
    (is (= {:present? true :value nil}
           (path/lookup (path/put [10 20 30] [1] nil) [1])))))

;; ---- concrete paths + explicit root (rf2-orcbow concrete-path coverage) ---
;;
;; A concrete `:rf/path` is a vector of EDN segments; the empty vector `[]`
;; is the explicit root path (Conventions §Path shape). normalize coerces any
;; sequential container to the canonical vector form. These pin the
;; container-shape coercion + the root spelling directly (the laws cover root
;; semantics; this covers the SHAPE the declaration boundary stores).

(deftest concrete-path-shape-and-root
  (testing "normalize coerces sequential containers to the canonical vector"
    (is (= [:a :b 3] (path/normalize (list :a :b 3))))
    (is (= [:a :b]   (path/normalize (seq [:a :b]))))
    (is (vector? (path/normalize (list :a :b))))
    ;; an already-vector path passes through
    (is (= [:cart :items 42 :qty] (path/normalize [:cart :items 42 :qty]))))
  (testing "the empty vector is the canonical root path"
    (is (= [] (path/normalize [])))
    (is (= [] (path/normalize (list))))
    ;; rf2-w9x5fv item 1: the root path is the EXPLICIT empty vector [], and a
    ;; nil path fails closed with :rf.error/bad-path — an omitted path is NOT
    ;; silently the whole value. (Hardened from the prior nil->[] coercion.)
    (is (= :rf.error/bad-path
           (try (path/normalize nil) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))
        "nil is not the root path — it fails closed (explicit [] is the root)"))
  (testing "a non-sequential path fails closed with :rf.error/bad-path"
    (is (= :rf.error/bad-path
           (try (path/normalize :not-a-path) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e))))))
    (is (= :rf.error/bad-path
           (try (path/normalize 42) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e))))))))

;; ---- rf2-t3cfil: the SHARED concrete-segment domain predicate ------------
;;
;; `re-frame.path/segment?` is the public membership predicate for the shared
;; concrete-segment domain (Conventions §Segment domain) — the upper bound a
;; consumer (flows / resources / routing) narrows from but never widens past,
;; and the predicate counterpart of `normalize-concrete`'s fail-closed
;; validation. Promoted from the private `valid-segment?` so flows' reg-flow
;; validator delegates to it rather than re-enumerating the domain. These pin
;; the domain so a consumer's narrowing stays anchored to one definition.

(deftest segment-domain-predicate
  (testing "the shared concrete-segment domain is admitted"
    (is (true? (path/segment? :kw)))
    (is (true? (path/segment? "str")))
    (is (true? (path/segment? 'sym)))
    (is (true? (path/segment? true)))
    (is (true? (path/segment? false)))
    (is (true? (path/segment? 42)))
    (is (true? (path/segment? nil)) "nil is a valid associative-key segment")
    (is (true? (path/segment? #uuid "00000000-0000-0000-0000-000000000001")))
    (is (true? (path/segment? #inst "2026-06-12T00:00:00.000-00:00"))))
  (testing "composites and host handles are NOT concrete segments"
    (is (false? (path/segment? [:nested])) "a vector is not a concrete segment")
    (is (false? (path/segment? {:k 1})))
    (is (false? (path/segment? #{:a})))
    (is (false? (path/segment? (list :a))))
    (is (false? (path/segment? 1.5)) "a float is not an EDN identity segment")
    (is (false? (path/segment? identity)) "a fn is not a segment"))
  (testing "segment? agrees with normalize-concrete's fail-closed boundary"
    ;; A path of all-segment? elements passes normalize-concrete; one with a
    ;; composite segment fails closed with :rf.error/bad-path naming it.
    (is (= [:a 1 nil "x"] (path/normalize-concrete [:a 1 nil "x"])))
    (is (= :rf.error/bad-path
           (try (path/normalize-concrete [:a [:nested] :b]) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e))))))))

;; ---- rf2-ujmc3u: segment? shares the CEDN-1 safe-integer range -----------
;;
;; The shared path vocabulary MUST NOT be wider than canonical EDN identity
;; (Conventions §Segment domain limits integer segments to the CEDN-1 safe-
;; integer range). The prior `segment?` admitted EVERY `integer?`, so a
;; consumer keying off it could accept an integer segment the canonicalizer
;; rejects — a path that cannot be portably compared, printed, routed, or
;; digested. `segment?` now composes `re-frame.identity/safe-segment-integer?`,
;; the SAME predicate the CEDN-1 encoder enforces, so the two surfaces agree.

(deftest segment-integer-shares-cedn1-safe-range
  (testing "both safe-integer boundaries are accepted"
    (is (true? (path/segment? 9007199254740991))  "max safe integer is a segment")
    (is (true? (path/segment? -9007199254740991)) "min safe integer is a segment")
    (is (true? (path/segment? 0)))
    (is (true? (path/segment? 1)))
    (is (true? (path/segment? -1))))
  (testing "an integer ONE BEYOND either safe boundary is NOT a segment"
    (is (false? (path/segment? 9007199254740992))
        "2^53 is outside the CEDN-1 safe range — not a portable segment")
    (is (false? (path/segment? -9007199254740992))
        "-2^53 is outside the CEDN-1 safe range — not a portable segment"))
  (testing "normalize-concrete fails closed on an out-of-range integer segment,
            accepting both boundaries (the segment? predicate counterpart)"
    (is (= [:a 9007199254740991 :b]  (path/normalize-concrete [:a 9007199254740991 :b])))
    (is (= [:a -9007199254740991 :b] (path/normalize-concrete [:a -9007199254740991 :b])))
    (is (= :rf.error/bad-path
           (try (path/normalize-concrete [:a 9007199254740992 :b]) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))
        "an unsafe-high integer segment fails closed")
    (is (= :rf.error/bad-path
           (try (path/normalize-concrete [:a -9007199254740992 :b]) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e)))))
        "an unsafe-low integer segment fails closed"))
  (testing "segment? and canonical-bytes AGREE on the integer domain (one
            definition, not two): an out-of-range integer is rejected by BOTH"
    (is (false? (path/segment? 9007199254740992)))
    (is (= :rf.error/non-edn-identity
           (try (identity/canonical-bytes 9007199254740992) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                  (:rf.error/id (ex-data e))))))))

;; ---- thrown-error shape conformance for bad-path! (rf2-krrv87) ------------
;;
;; `bad-path!` routes through `error/throw-error!` rather than hand-rolling
;; the ex-info, so its message LEADS with the human sentence and TRAILS with
;; the `[:rf.error/bad-path]` greppability token (Spec 009 §The thrown-error
;; shape, rules 1+4) and its ex-data carries the canonical `:where` /
;; `:recovery` slots. A bare-keyword message (the retired shape) regressing
;; back in would fail these.

(deftest bad-path-thrown-error-shape
  (testing "a nil path throw carries the canonical thrown-error shape"
    (let [thrown (try (path/normalize nil) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e e))
          msg    (ex-message thrown)
          data   (ex-data thrown)]
      (is (some? thrown) "a nil path throws")
      (is (= :rf.error/bad-path (:rf.error/id data))
          ":rf.error/id is the machine discriminator")
      (is (error/message-has-id-token? msg)
          "the message carries the trailing [:rf.error/bad-path] token (rule 4)")
      (is (not (error/keyword-only-message? msg))
          "the message is a human sentence, NOT a bare keyword (rule 1)")
      (is (= 'rf.path/normalize (:where data))
          ":where names the throwing helper")
      (is (= :fix-path (:recovery data))
          ":recovery carries the canonical disposition")
      (is (= nil (:bad-path data))
          "the offending slot rides in ex-data")))
  (testing "a non-sequential path throw is equally conformant"
    (let [thrown (try (path/normalize 42) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e e))
          data   (ex-data thrown)]
      (is (error/message-has-id-token? (ex-message thrown)))
      (is (= :rf.error/bad-path (:rf.error/id data)))
      (is (= :fix-path (:recovery data)))
      (is (= 42 (:bad-path data))))))
