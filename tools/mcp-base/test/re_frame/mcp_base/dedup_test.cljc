(ns re-frame.mcp-base.dedup-test
  "Canonical cross-host tests for the shared wire-boundary dedup step and
  the codec under it, vendored from `day8/de-dupe`. Both MCP servers
  require `re-frame.mcp-base.dedup` directly, so its behaviour is pinned
  here once. `.cljc` so it runs on the JVM `:test` alias (story-mcp's
  runtime) and in the shadow-cljs `cljs-test` build (re-frame2-pair-mcp's
  Node runtime)."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test :refer-macros [are deftest is]])
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(defn- table
  "The cache `dedup-value` wraps `v` in, or nil when it returns `v` raw."
  [v]
  (get (rf.mcp-base.dedup/dedup-value v true) rf.mcp-base.vocab/dedup-table-key))

(defn- round-trip
  "`v` through `dedup-value` and back through `expand`. A payload that was
  not wrapped comes back nil, so every round-trip below also proves the
  codec really ran."
  [v]
  (rf.mcp-base.dedup/expand (table v)))

(deftest empty-payload?-flags-only-no-win-values
  (are [v expected] (= expected (boolean (rf.mcp-base.dedup/empty-payload? v)))
    nil     true
    []      true
    42      true
    [1 2 3] false))

(deftest dedup-value-disabled-returns-input-verbatim
  (let [v [{:a 1} {:a 1}]]
    (is (identical? v (rf.mcp-base.dedup/dedup-value v false)))))

(deftest dedup-value-no-repeats-non-empty-returns-input-verbatim
  ;; With no repeated subtree the root-only cache would be larger than the
  ;; input, so the payload ships raw.
  (let [v {:user {:name "ada"} :session {:state :idle} :items [1 2 3]}]
    (is (identical? v (rf.mcp-base.dedup/dedup-value v true)))))

(deftest dedup-value-wraps-in-cross-mcp-marker
  (let [shared {:repeated [:big :subtree :here]}]
    (is (= #{rf.mcp-base.vocab/dedup-table-key}
           (set (keys (rf.mcp-base.dedup/dedup-value {:a shared :b shared :c shared} true)))))))

(deftest dedup-value-uses-equality-not-identity
  ;; Payloads rebuilt from EDN or synthesised per call share subtrees by
  ;; equality, not identity; two equal but distinct subtrees still pool.
  (let [v {:left {:k (vec (range 30))} :right {:k (vec (range 30))}}]
    (is (= v (round-trip v)))))

(deftest cache-keys-are-de-dupe-cache-namespaced-symbols
  ;; A wire pin: the Node conformance decoder and the wire-vocab
  ;; DedupTable schema both start from `de-dupe.cache/cache-0`, and every
  ;; call allocates its slots from cache-1.
  (let [shared {:big [:repeated :subtree]}]
    (is (= '#{de-dupe.cache/cache-0 de-dupe.cache/cache-1}
           (set (keys (rf.mcp-base.dedup/de-dupe-eq [shared shared])))))))

(deftest cache-ids-are-allocated-per-call-not-globally
  ;; The id counter is call-local, so an intervening encode cannot shift
  ;; this one's slot ids.
  (let [sub         {:big [:repeated :subtree]}
        first-cache (rf.mcp-base.dedup/de-dupe-eq [sub sub])]
    (rf.mcp-base.dedup/de-dupe-eq {:unrelated [{:x 1} {:x 1} {:y 2} {:y 2}]})
    (is (= first-cache (rf.mcp-base.dedup/de-dupe-eq [sub sub])))))

#?(:clj
   (deftest concurrent-encodes-do-not-corrupt-each-other
     ;; A shared id counter would let parallel encodes reuse an id inside
     ;; one cache, and the payload would expand to the wrong value.
     (let [payloads (mapv (fn [n]
                            (let [shared {:n n :body (vec (range 40))}]
                              {:a shared :b shared :c [shared shared]}))
                          (range 32))
           results  (doall (pmap #(rf.mcp-base.dedup/expand (rf.mcp-base.dedup/de-dupe-eq %)) payloads))]
       (is (= payloads results)))))

;; ---------------------------------------------------------------------------
;; A payload value that OCCUPIES the reference namespace is data, never a
;; reference: a symbol, keyword or string spelled `de-dupe.cache/cache-1`
;; is escaped on the wire and comes back as itself, in its own type. JSON
;; renders all three as the string a real reference arrives under, which
;; is why the keyword and string are escaped too. Each payload carries an
;; unrelated repeated subtree so the codec really runs.
;; ---------------------------------------------------------------------------

(def ^:private shared {:big [:repeat :me]})

(deftest payload-symbols-in-the-reference-namespace-round-trip-as-data
  (let [payload {:literal    'de-dupe.cache/not-a-ref
                 :look-alike 'de-dupe.cache/cache-1
                 :a          shared
                 :b          shared}]
    (is (= payload (round-trip payload)))))

(deftest colliding-payload-tokens-are-escaped-on-the-wire
  ;; The Node conformance decoder's fixtures mirror these spellings.
  (is (= {'de-dupe.cache/cache-0 {:literal    'de-dupe.cache/not-a-ref
                                  :look-alike 'de-dupe.cache/!cache-1
                                  :a          'de-dupe.cache/cache-1
                                  :b          'de-dupe.cache/cache-1}
          'de-dupe.cache/cache-1 shared}
         (rf.mcp-base.dedup/de-dupe-eq {:literal    'de-dupe.cache/not-a-ref
                                        :look-alike 'de-dupe.cache/cache-1
                                        :a          shared
                                        :b          shared}))))

(deftest escaping-is-reversible-under-repetition
  ;; A token already spelled like an escape gains a marker and sheds
  ;; exactly one, so it is never mistaken for an escape on the way back.
  (let [payload {:once  'de-dupe.cache/!cache-1
                 :twice 'de-dupe.cache/!!cache-1
                 :other 'de-dupe.cache/!not-a-ref
                 :a     shared
                 :b     shared}]
    (is (= payload (round-trip payload)))))

(deftest payload-strings-that-spell-a-reference-round-trip-as-strings
  (let [payload {:s     "de-dupe.cache/cache-1"
                 :plain "de-dupe.cache/not-a-ref"
                 :a     shared
                 :b     shared}]
    (is (= payload (round-trip payload)))))

(deftest payload-keywords-in-the-reference-namespace-round-trip-as-keywords
  (let [payload {:literal    :de-dupe.cache/not-a-ref
                 :look-alike :de-dupe.cache/cache-1
                 :a          shared
                 :b          shared}]
    (is (= payload (round-trip payload)))))

(deftest colliding-payload-keywords-are-escaped-on-the-wire
  ;; The round-trip passes for an UNESCAPED keyword too, because the
  ;; Clojure decoder never aliases a keyword; only the wire spelling shows
  ;; whether the JSON projection is safe.
  (is (= {:look-alike :de-dupe.cache/!cache-1
          :literal    :de-dupe.cache/not-a-ref
          :a          'de-dupe.cache/cache-1
          :b          'de-dupe.cache/cache-1}
         (get (rf.mcp-base.dedup/de-dupe-eq {:look-alike :de-dupe.cache/cache-1
                                             :literal    :de-dupe.cache/not-a-ref
                                             :a          shared
                                             :b          shared})
              'de-dupe.cache/cache-0))))

(deftest colliding-payload-keywords-are-escaped-in-map-KEY-position-too
  (let [payload {:de-dupe.cache/cache-1 "keyed" :a shared :b shared}
        cache   (table payload)]
    (is (= {:de-dupe.cache/!cache-1 "keyed"
            :a                      'de-dupe.cache/cache-1
            :b                      'de-dupe.cache/cache-1}
           (get cache 'de-dupe.cache/cache-0)))
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

(deftest keyword-escaping-is-reversible-under-repetition
  (let [payload {:once  :de-dupe.cache/!cache-1
                 :twice :de-dupe.cache/!!cache-1
                 :other :de-dupe.cache/!not-a-ref
                 :a     shared
                 :b     shared}
        cache   (table payload)]
    (is (= :de-dupe.cache/!!cache-1 (:once (get cache 'de-dupe.cache/cache-0))))
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

(deftest all-three-json-flattened-types-collide-and-all-three-are-escaped
  (let [payload {:sym 'de-dupe.cache/cache-1
                 :kw  :de-dupe.cache/cache-1
                 :str "de-dupe.cache/cache-1"
                 :a   shared
                 :b   shared}
        cache   (rf.mcp-base.dedup/de-dupe-eq payload)]
    (is (= {'de-dupe.cache/cache-0 {:sym 'de-dupe.cache/!cache-1
                                    :kw  :de-dupe.cache/!cache-1
                                    :str "de-dupe.cache/!cache-1"
                                    :a   'de-dupe.cache/cache-1
                                    :b   'de-dupe.cache/cache-1}
            'de-dupe.cache/cache-1 shared}
           cache))
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

(deftest colliding-literals-survive-inside-a-pooled-subtree
  ;; The escape runs before the counting pass, so both passes hash the same
  ;; escaped subtree and the pooling still fires.
  (let [inner   {:tag 'de-dupe.cache/cache-1 :body (vec (range 20))}
        payload {:a inner :b inner :c [inner]}]
    (is (= payload (round-trip payload)))))

;; ---------------------------------------------------------------------------
;; Structure: every collection kind rebuilds as itself.
;; ---------------------------------------------------------------------------

(deftest expand-round-trips-every-collection-kind
  ;; `=` cannot tell a list from a vector, so the list is checked by kind.
  ;; Sortedness is not carried (EDN and JSON read a sorted map back
  ;; unsorted anyway), but equality is exact.
  (let [inner {:s #{:a :b} :v [1 2 3]}
        v     {:list   (list inner inner)
               :seq    (map identity [inner inner])
               :set    #{[:x inner]}
               :nest   {:deep {:deeper [inner inner]}}
               :sorted (sorted-map :b inner :a inner)
               :s-set  (sorted-set :one :two)}
        out   (rf.mcp-base.dedup/expand (rf.mcp-base.dedup/de-dupe-eq v))]
    (is (= v out))
    (is (list? (:list out)))))

(deftest sorted-map-with-a-pooled-key-beside-an-unpooled-one-round-trips
  ;; Rebuilding through the map's own comparator would hand a slot symbol
  ;; to a comparator chosen for the data, and `compare` would throw.
  (let [payload {:index (sorted-map [1 2 3] :old [4 5 6] :new) :again [1 2 3]}]
    (is (= payload (round-trip payload)))))

(deftest sorted-set-with-a-pooled-element-beside-an-unpooled-one-round-trips
  (let [payload {:index (sorted-set [1 2 3] [4 5 6]) :again [1 2 3]}]
    (is (= payload (round-trip payload)))))

;; ---------------------------------------------------------------------------
;; Caller metadata is not encoder bookkeeping: a caller's own `:cache-id`
;; metadata must not move the output.
;; ---------------------------------------------------------------------------

(defn- allocator-keys?
  "True when `cache` is keyed by exactly `cache-0` … `cache-(n-1)`."
  [cache]
  (= (set (keys cache))
     (set (map rf.mcp-base.dedup/make-cache-element (range (count cache))))))

(deftest caller-cache-id-metadata-on-a-unique-subtree-changes-nothing
  (let [payload {:user (with-meta {:x 1} {:cache-id 7})}]
    (is (identical? payload (rf.mcp-base.dedup/dedup-value payload true)))))

(deftest caller-cache-id-metadata-beside-a-real-repeat-expands-exactly
  (let [repeated (with-meta {:v [1 2 3]} {:cache-id 'other/id})
        payload  {:user  (with-meta {:x 1} {:cache-id 7})
                  :other (with-meta {:y 2} {:cache-id 'other/id})
                  :a     repeated
                  :b     repeated}
        cache    (table payload)]
    (is (allocator-keys? cache))
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

;; ---------------------------------------------------------------------------
;; A record has no `empty`, so both walks write rebuilt entries back into
;; the original record; an extension key that changes spelling must be
;; replaced, not added beside the old one.
;; ---------------------------------------------------------------------------

(defrecord KeyedRecord [x])

(deftest record-extension-keys-are-replaced-not-duplicated
  (let [rec     (assoc (->KeyedRecord [7 8 9])
                       :de-dupe.cache/cache-1  :kw
                       'de-dupe.cache/cache-1  :sym
                       "de-dupe.cache/cache-1" :str
                       ;; Escapes to `!!cache-1` while `cache-1` escapes to
                       ;; THIS spelling: every original entry must be read
                       ;; before any rebuilt one is written over it.
                       :de-dupe.cache/!cache-1 :already-escaped
                       [4 5 6]                 :pooled-key)
        payload {:record rec :again [7 8 9] :also [4 5 6]}
        cache   (table payload)
        slot-of (fn [v] (some (fn [[k cv]] (when (= v cv) k)) cache))
        encoded (:record (get cache (rf.mcp-base.dedup/make-cache-element 0)))]
    (is (= #{:x
             :de-dupe.cache/!cache-1 'de-dupe.cache/!cache-1 "de-dupe.cache/!cache-1"
             :de-dupe.cache/!!cache-1
             (slot-of [4 5 6])}
           (set (keys encoded)))
        "each key appears once, in its wire spelling")
    (is (= [:kw :already-escaped]
           [(get encoded :de-dupe.cache/!cache-1) (get encoded :de-dupe.cache/!!cache-1)]))
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

;; ---------------------------------------------------------------------------
;; A list and a vector are equal with equal hashes but print differently,
;; so pooling must keep them apart at every depth.
;; ---------------------------------------------------------------------------

(deftest equal-lists-and-vectors-keep-their-kind
  (doseq [payload [(array-map :list '(1 2 3) :vector [1 2 3] :a shared :b shared)
                   (array-map :vector [1 2 3] :list '(1 2 3) :a shared :b shared)]]
    (let [back (round-trip payload)]
      (is (= payload back))
      (is (list? (:list back)) "in either order")
      (is (vector? (:vector back)) "in either order"))))

(deftest same-kind-repeats-still-pool-one-slot-per-kind
  (let [payload (array-map :l1 '(1 2 3) :v1 [1 2 3] :l2 '(1 2 3) :v2 [1 2 3])
        cache   (table payload)
        back    (rf.mcp-base.dedup/expand cache)]
    (is (= 3 (count cache)) "the root plus ONE slot per kind")
    (is (= payload back))
    (is (every? list? [(:l1 back) (:l2 back)]))
    (is (every? vector? [(:v1 back) (:v2 back)]))))

(deftest equal-containers-with-differently-kinded-children-keep-their-kinds
  (let [payload (array-map :in-val-l {:child '(1 2 3)}  :in-val-v {:child [1 2 3]}
                           :in-key-l {'(1 2 3) :child}  :in-key-v {[1 2 3] :child}
                           :in-set-l #{'(1 2 3)}        :in-set-v #{[1 2 3]}
                           :a shared :b shared)
        back    (round-trip payload)]
    (is (= payload back))
    (is (list?   (get-in back [:in-val-l :child])))
    (is (vector? (get-in back [:in-val-v :child])))
    (is (list?   (first (keys (:in-key-l back)))) "a key keeps its kind")
    (is (vector? (first (keys (:in-key-v back)))))
    (is (list?   (first (:in-set-l back))) "a set element keeps its kind")
    (is (vector? (first (:in-set-v back))))))
