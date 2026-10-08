(ns re-frame.path-laws-cljs-test
  "Law tests for the `:rf/path` algebra (EP-0012, Conventions §Path laws):
  put-lookup family, compose associativity, prefix?/overlap?, root-path laws,
  missing vs present-nil, template normalization, and the shared segment
  domain.

  The property checks sample a self-contained seeded LCG rather than the host
  RNG (no test.check on the classpath), so CLJ and CLJS draw the same values."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.error :as rf.error]
            [re-frame.identity :as rf.identity]
            [re-frame.path :as rf.path]))

;; ---- a deterministic, host-portable PRNG ---------------------------------

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

(def ^:private seg-pool
  [:a :b :c :x :y 0 1 2 "k" 'sym true false nil])

(defn- gen-seg [state]
  (nth seg-pool (rnd state (count seg-pool))))

(defn- gen-path
  "A path of 0..max-len segments (nil segments allowed). Returns [path next-state]."
  [state max-len]
  (let [len (rnd state (inc max-len))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i len)
        [acc s]
        (recur (inc i) (lcg-next s) (conj acc (gen-seg s)))))))

(defn- gen-edn
  "A small EDN value (maps/vectors of scalars) to `depth`. Returns [value next-state]."
  [state depth]
  (let [k (rnd state (if (zero? depth) 4 7))
        s (lcg-next state)]
    (case k
      0 [(nth seg-pool (rnd s (count seg-pool))) (lcg-next s)]
      1 [(rnd s 1000) (lcg-next s)]
      2 [(str "v" (rnd s 50)) (lcg-next s)]
      3 [nil (lcg-next s)]
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
  "Run `f` over `n` deterministic draws of [value path x]; nil when all pass,
  else the first failing draw."
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

(defn- error-id [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ---- root-path laws ------------------------------------------------------

(deftest root-path-laws
  (is (= [{:a 1} 42] [(rf.path/get {:a 1} []) (rf.path/get 42 [])]) "get(s, []) = s")
  (is (= [{:present? true :value {:a 1}} {:present? true :value nil}]
         [(rf.path/lookup {:a 1} []) (rf.path/lookup nil [])])
      "lookup(s, []) = present s")
  (is (= {:b 2} (rf.path/put {:a 1} [] {:b 2})) "put(s, [], x) = x (raw assoc-in breaks this)")
  (is (= {:a 2} (rf.path/over {:a 1} [] #(update % :a inc))) "over(s, [], f) = f(s)")
  (is (= [true true true] (map #(apply rf.path/overlap? %) [[[] []] [[] [:anything :deep 3]] [[:anything] []]]))
      "overlap?([], p) = true for every p"))

;; ---- put-lookup family ---------------------------------------------------

(deftest put-lookup-law
  (testing "lookup(put(s, p, x), p) = {:present? true :value x} for all p,x"
    (is (nil? (samples 400
                (fn [v p x]
                  (= {:present? true :value x}
                     (rf.path/lookup (rf.path/put v p x) p)))))))
  (testing "explicitly at x=nil (the nil-write trap)"
    (is (= {:present? true :value nil}
           (rf.path/lookup (rf.path/put {:page 1} [:page] nil) [:page])))
    (is (= {:present? true :value nil}
           (rf.path/lookup (rf.path/put {:a 1} [] nil) [])))))

(deftest lookup-put-law
  (testing "if lookup(s,p) present with x then put(s,p,x) = s"
    (is (nil? (samples 400
                (fn [v p _x]
                  (let [{:keys [present? value]} (rf.path/lookup v p)]
                    (or (not present?) (= v (rf.path/put v p value))))))))))

(deftest put-put-law
  (testing "put(put(s,p,x),p,y) = put(s,p,y)"
    (is (nil? (samples 400
                (fn [v p x]
                  (= (rf.path/put v p x)
                     (rf.path/put (rf.path/put v p :first) p x))))))))

;; ---- compose laws --------------------------------------------------------

(deftest compose-laws
  (is (= [[:a :b] [:a :b]] [(rf.path/compose [:a :b] []) (rf.path/compose [] [:a :b])])
      "compose(p,[]) = p = compose([],p)")
  (testing "compose associativity over generated paths"
    (is (nil?
          (loop [i 0, s 999]
            (if (= i 300)
              nil
              (let [[p s1] (gen-path s 3)
                    [q s2] (gen-path s1 3)
                    [r s3] (gen-path s2 3)]
                (if (= (rf.path/compose (rf.path/compose p q) r)
                       (rf.path/compose p (rf.path/compose q r)))
                  (recur (inc i) (lcg-next s3))
                  [p q r])))))))
  (testing "get(s, compose(p,q)) = get(get(s,p), q)"
    (let [s {:cart {:items {42 {:qty 2}}}}]
      (is (= [2 2] [(rf.path/get s (rf.path/compose [:cart :items] [42 :qty]))
                    (rf.path/get (rf.path/get s [:cart :items]) [42 :qty])])))))

;; ---- over law ------------------------------------------------------------

(deftest over-law
  (testing "over(s,p,identity) = s when present"
    (is (nil? (samples 300
                (fn [v p _x]
                  (or (not (:present? (rf.path/lookup v p)))
                      (= v (rf.path/over v p identity))))))))
  (testing "over(s,p,f) = put(s,p,f(get(s,p))) (nil-on-missing get semantics)"
    (is (nil? (samples 300
                (fn [v p _x]
                  (= (rf.path/over v p (fn [x] [:wrapped x]))
                     (rf.path/put v p [:wrapped (rf.path/get v p)]))))))))

;; ---- prefix? / overlap? --------------------------------------------------

(deftest prefix-and-overlap
  (is (= [true false true true]
         (map #(apply rf.path/prefix? %)
              [[[:cart] [:cart :items 42]] [[:cart :items 42] [:cart]]
               [[] [:anything]] [[:a] [:a]]])))
  (is (= [true true false false]
         (map #(apply rf.path/overlap? %)
              [[[:cart :items] [:cart :items 42 :qty]] [[:cart :items 42 :qty] [:cart :items 42]]
               [[:cart :items 42] [:cart :items 43]] [[:cart :items] [:profile :display-name]]]))
      "the spec's overlap? examples")
  (testing "overlap? is symmetric over generated path pairs"
    (is (nil?
          (loop [i 0, s 7777]
            (if (= i 400)
              nil
              (let [[p s1] (gen-path s 4)
                    [q s2] (gen-path s1 4)]
                (if (= (rf.path/overlap? p q) (rf.path/overlap? q p))
                  (recur (inc i) (lcg-next s2))
                  [p q]))))))))

;; ---- missing vs present nil ----------------------------------------------

(deftest missing-versus-present-nil
  (is (= [{:present? false} {:present? true :value nil}]
         [(rf.path/lookup {} [:page]) (rf.path/lookup {:page nil} [:page])]))
  (is (= [::nf nil] [(rf.path/get {} [:page] ::nf) (rf.path/get {:page nil} [:page] ::nf)])
      "get returns not-found only when missing, never for present-nil"))

;; ---- template normalization ----------------------------------------------

(deftest template-normalization
  (is (= [:billing :invoices :by-id [:rf.path/param :invoice-id] :email]
         (rf.path/normalize-template [:billing :invoices :by-id '?invoice-id :email]))
      "'?name sugar normalizes to the data form")
  (let [canon [:billing [:rf.path/param :invoice-id] :email]]
    (is (= canon (rf.path/normalize-template canon)) "the data form passes through"))
  (is (= [:a 'plain :b] (rf.path/normalize-template [:a 'plain :b])) "a non-marker symbol passes through")
  (is (= ['?] (rf.path/normalize-template ['?])) "a bare ? is not a marker")
  (is (= [true true false]
         (map rf.path/template? [[:a '?x] [:a [:rf.path/param :x]] [:a :b 3]])))
  (is (= [true false false false]
         (map rf.path/param-segment?
              [[:rf.path/param :id] '?id [:rf.path/param :id :extra] [:rf.path/param "id"]]))
      "param-segment? recognises only the data form"))

;; ---- instantiate ---------------------------------------------------------

(deftest instantiate-template
  (is (= [:billing :invoices :by-id "iid" :email]
         (rf.path/instantiate [:billing :invoices :by-id '?invoice-id :email] {:invoice-id "iid"})))
  (is (= [:profile :display-name]
         (rf.path/instantiate {:id :p :rf/path [:profile :display-name]} {}))
      "accepts a named-path-declaration map")
  (is (= [:a nil :c] (rf.path/instantiate [:a '?x :c] {:x nil})) "present-nil is a legal binding")
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
               (rf.path/instantiate [:a '?x] {}))
      "an unbound param fails closed"))

(deftest instantiate-rejects-non-concrete-binding
  ;; instantiate produces a CONCRETE path, so the substituted result goes
  ;; through normalize-concrete: a composite binding fails closed rather than
  ;; smuggling a non-portable segment into a path presented as concrete
  (let [data (try (rf.path/instantiate [:by-id [:rf.path/param :id]] {:id [:nested]}) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))]
    (is (= [:rf.error/bad-path [:nested]] [(:rf.error/id data) (:bad-segment data)]))))

;; ---- vector-index container policy ---------------------------------------
;;
;; A vector holds an entry only at an in-range non-negative integer index; an
;; out-of-range, negative or non-integer segment REPLACES it with a fresh map
;; (never throws, never grows), keeping `put` total.

(deftest vector-index-container-policy
  (testing "in-range index: the vector is preserved and updated in place"
    (is (= [10 99 30] (rf.path/put [10 20 30] [1] 99)))
    (is (= [{:present? true :value 20} 20] [(rf.path/lookup [10 20 30] [1]) (rf.path/get [10 20 30] [1])]))
    (is (= [30 {:xs [10 99 30]}] [(rf.path/get {:xs [10 20 30]} [:xs 2])
                                  (rf.path/put {:xs [10 20 30]} [:xs 1] 99)])))
  (testing "index = count, a negative index, or a non-integer segment replaces the vector"
    (is (= [{2 99} {-1 99} {:k 99}]
           [(rf.path/put [10 20] [2] 99) (rf.path/put [10 20] [-1] 99) (rf.path/put [10 20] [:k] 99)]))
    (is (= [{:present? false} {:present? false} {:present? false}]
           [(rf.path/lookup [10 20] [2]) (rf.path/lookup [10 20] [-1]) (rf.path/lookup [10 20] [:k])]))
    (is (= ::nf (rf.path/get [10 20] [:k] ::nf))))
  (is (= {:a {:b 99}} (rf.path/put [10 20] [:a :b] 99)) "a fresh map nests under a replaced vector")
  (is (= [10 nil 30] (rf.path/put [10 20 30] [1] nil)) "x=nil stores present-nil at the index"))

;; ---- concrete paths + explicit root --------------------------------------

(deftest concrete-path-shape-and-root
  (is (= [[:a :b 3] true] ((juxt identity vector?) (rf.path/normalize (list :a :b 3))))
      "normalize coerces a sequential container to the canonical vector")
  (is (= [] (rf.path/normalize (list))) "the empty vector is the canonical root")
  (is (= [:rf.error/bad-path :rf.error/bad-path]
         [(error-id #(rf.path/normalize nil)) (error-id #(rf.path/normalize 42))])
      "nil is not the root path, and a non-sequential path fails closed"))

;; ---- the SHARED concrete-segment domain predicate ------------------------
;;
;; `segment?` is the upper bound a consumer (flows / resources / routing)
;; narrows from but never widens past (Conventions §Segment domain).

(deftest segment-domain-predicate
  (is (= (repeat 9 true)
         (map rf.path/segment? [:kw "str" 'sym true false 42 nil
                                #uuid "00000000-0000-0000-0000-000000000001"
                                #inst "2026-06-12T00:00:00.000-00:00"])))
  (is (= (repeat 6 false)
         (map rf.path/segment? [[:nested] {:k 1} #{:a} (list :a) 1.5 identity]))
      "composites, floats and host handles are not segments")
  (is (= [:a 1 nil "x"] (rf.path/normalize-concrete [:a 1 nil "x"]))))

;; ---- segment? shares the CEDN-1 safe-integer range -----------------------

(deftest segment-integer-shares-cedn1-safe-range
  (is (= [true true false false]
         (map rf.path/segment? [9007199254740991 -9007199254740991
                                9007199254740992 -9007199254740992]))
      "both safe boundaries are segments; one past either is not")
  (is (= [[:a 9007199254740991 :b] :rf.error/bad-path]
         [(rf.path/normalize-concrete [:a 9007199254740991 :b])
          (error-id #(rf.path/normalize-concrete [:a 9007199254740992 :b]))]))
  (is (= :rf.error/non-edn-identity (error-id #(rf.identity/canonical-bytes 9007199254740992)))
      "canonical-bytes rejects the same out-of-range integer: one domain, not two"))

;; ---- thrown-error shape conformance for bad-path! ------------------------

(deftest bad-path-thrown-error-shape
  ;; Spec 009 §The thrown-error shape: a human sentence trailed by the
  ;; [:rf.error/bad-path] token, with the canonical :where / :recovery slots
  (let [thrown (try (rf.path/normalize nil) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
        msg    (ex-message thrown)]
    (is (= [true false] [(boolean (rf.error/message-has-id-token? msg))
                         (boolean (rf.error/keyword-only-message? msg))]))
    (is (= [:rf.error/bad-path 'rf.path/normalize :fix-path nil]
           ((juxt :rf.error/id :where :recovery :bad-path) (ex-data thrown))))))
