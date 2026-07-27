(ns re-frame.mcp-base.dedup-test
  "Canonical cross-host tests for the shared wire-boundary dedup encode
  step. This is the ONE suite that pins dedup behaviour — both MCP
  servers now require `re-frame.mcp-base.dedup` DIRECTLY (no pass-through
  facade), so the behaviour is asserted here once rather than duplicated
  in each consumer's suite.

  `.cljc` so it runs on BOTH hosts: the JVM `:test` alias
  (cognitect-labs test-runner) exercises the story-mcp runtime, and the
  shadow-cljs `cljs-test` build exercises the re-frame2-pair-mcp (Node)
  runtime — `dedup.cljc` compiles on both arms, so the codec, the encode
  step and its idempotence guards are proven on the exact runtimes the
  consumers ship on.

  Pins the byte-identical forward direction — the `empty-payload?`
  short-circuit, the `no-substitutions?` cache-shape guard, the
  cross-MCP wrap shape, the opt-out — and round-trip exactness via
  `dedup/expand`, the inverse an agent-side Clojure consumer calls. The
  codec itself was vendored from `day8/de-dupe` v0.3.0 under rf2-2ii52;
  this is the canonical suite that pins it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.dedup :as dedup]
            [re-frame.mcp-base.vocab :as vocab]))

(deftest empty-payload?-true-for-no-win-values
  (testing "nil, empty colls, and scalars yield no dedup win"
    (is (true? (boolean (dedup/empty-payload? nil))))
    (is (true? (boolean (dedup/empty-payload? []))))
    (is (true? (boolean (dedup/empty-payload? {}))))
    (is (true? (boolean (dedup/empty-payload? #{}))))
    (is (true? (boolean (dedup/empty-payload? ""))))
    (is (true? (boolean (dedup/empty-payload? 42))))
    (is (true? (boolean (dedup/empty-payload? :a-keyword))))))

(deftest empty-payload?-false-for-non-empty-colls
  (is (false? (boolean (dedup/empty-payload? [1 2 3]))))
  (is (false? (boolean (dedup/empty-payload? {:a 1}))))
  (is (false? (boolean (dedup/empty-payload? #{:x})))))

(deftest dedup-value-disabled-returns-input-verbatim
  (let [v [{:a 1} {:a 1}]]
    (is (identical? v (dedup/dedup-value v false))
        "enabled? false is a verbatim passthrough")))

(deftest dedup-value-empty-payload-returns-input-verbatim
  ;; No dedup opportunity ⇒ skip the wrap (the one-entry cache would be
  ;; slightly LARGER than the input).
  (is (= [] (dedup/dedup-value [] true)))
  (is (= {} (dedup/dedup-value {} true)))
  (is (nil? (dedup/dedup-value nil true)))
  (is (= 42 (dedup/dedup-value 42 true))))

(deftest no-substitutions?-detects-one-entry-root-only-cache
  ;; `de-dupe-eq` always emits `cache-0` for the root; every substituted
  ;; subtree adds a further `cache-N`. A one-entry cache therefore made
  ;; NO substitutions.
  (testing "a no-repeat non-empty collection ⇒ one-entry root-only cache"
    (is (true? (boolean (dedup/no-substitutions? (dedup/de-dupe-eq {:a 1 :b 2})))))
    (is (true? (boolean (dedup/no-substitutions? (dedup/de-dupe-eq [1 2 3])))))
    (is (true? (boolean (dedup/no-substitutions? (dedup/de-dupe-eq {:a {:x 1} :b {:y 2}}))))))
  (testing "a repeated-subtree cache has >1 entry ⇒ substitutions happened"
    (is (false? (boolean (dedup/no-substitutions? (dedup/de-dupe-eq [{:x 1} {:x 1}]))))))
  (testing "non-map / empty inputs are not a root-only cache"
    (is (false? (boolean (dedup/no-substitutions? nil))))
    (is (false? (boolean (dedup/no-substitutions? {}))))))

(deftest dedup-value-no-repeats-non-empty-returns-input-verbatim
  ;; A non-empty collection with no repeated subtrees produces only the
  ;; root cache entry; wrapping it would grow the wire value.
  (testing "a flat no-repeat map is returned identical, NOT wrapped"
    (let [v {:a 1 :b 2 :c 3}
          out (dedup/dedup-value v true)]
      (is (identical? v out)
          "no dedup opportunity ⇒ verbatim passthrough, not a dedup-table wrap")
      (is (not (contains? out vocab/dedup-table-key)))))
  (testing "a nested no-repeat structure is returned identical"
    (let [v {:user {:name "ada"} :session {:state :idle} :items [1 2 3]}]
      (is (identical? v (dedup/dedup-value v true)))))
  (testing "a no-repeat vector is returned identical"
    (let [v [{:id 1} {:id 2} {:id 3}]]      ; all distinct ⇒ no substitution
      (is (identical? v (dedup/dedup-value v true))))))

(deftest dedup-value-repeated-subtree-still-wraps
  ;; The adversarial complement to the no-op skip: a payload that DOES
  ;; carry a repeated subtree must STILL wrap (the skip must not swallow a
  ;; genuine dedup win). Its cache has >1 entry, so `no-substitutions?` is
  ;; false and the wrap fires.
  (let [shared {:big [:repeated :subtree]}
        v      [shared shared shared]
        out    (dedup/dedup-value v true)]
    (is (map? out))
    (is (contains? out vocab/dedup-table-key)
        "a repeated subtree is a real dedup win — the wrap must fire")
    (is (= v (dedup/expand (get out vocab/dedup-table-key)))
        "and the wrap still round-trips")))

(deftest dedup-value-wraps-in-cross-mcp-marker
  (let [shared {:repeated [:big :subtree :here]}
        v      {:a shared :b shared :c shared}
        out    (dedup/dedup-value v true)]
    (testing "the wire shape is the cross-MCP dedup-table marker"
      (is (map? out))
      (is (contains? out vocab/dedup-table-key))
      (is (= #{vocab/dedup-table-key} (set (keys out)))
          "exactly one top-level slot, the dedup-table marker"))))

(deftest dedup-value-round-trips-exactly
  ;; The agent host reconstructs by calling dedup/expand on the
  ;; cache-map value — pin that the wrap is losslessly reversible.
  (let [shared {:repeated (vec (range 50))}
        v      {:a shared :b shared :c shared :scalars [1 2 3]}
        out    (dedup/dedup-value v true)
        cache  (get out vocab/dedup-table-key)]
    (is (= v (dedup/expand cache))
        "expand on the cache-map reconstructs the original structure")))

(deftest dedup-value-uses-equality-not-identity
  ;; Values reconstructed from EDN (re-frame2-pair-mcp) or synthesised
  ;; fresh per call (story-mcp) are equality-shared, not identity-shared.
  ;; `de-dupe-eq` must pool them; build two EQUAL-but-not-IDENTICAL
  ;; subtrees and assert the round-trip still holds (the share fires).
  (let [a   {:k (vec (range 30))}
        b   {:k (vec (range 30))}            ; equal to a, distinct object
        _   (is (not (identical? a b)))
        v   {:left a :right b}
        out (dedup/dedup-value v true)]
    (is (contains? out vocab/dedup-table-key))
    (is (= v (dedup/expand (get out vocab/dedup-table-key)))
        "equality-shared subtrees round-trip through the wrap")))

;; ---------------------------------------------------------------------------
;; The vendored codec's own contract (rf2-2ii52). Before the absorb these
;; lived upstream in day8/de-dupe; the wire shape and the cache-id allocation
;; are re-frame2's to pin now that the code is.
;; ---------------------------------------------------------------------------

(deftest cache-keys-are-de-dupe-cache-namespaced-symbols
  ;; A WIRE pin, not an implementation detail. The Node conformance decoder
  ;; (tools/mcp-conformance/lib/dedup-envelope.cjs) hard-codes
  ;; `de-dupe.cache/cache-0` as the root it starts reconstruction from, and
  ;; the wire-vocab DedupTable schema rejects a cache without it. Absorbing
  ;; the codec deliberately did NOT rename the namespace; this is the test
  ;; that makes renaming it a red build rather than a silent wire break.
  (is (= "de-dupe.cache" dedup/cache-element-ns))
  (is (= 'de-dupe.cache/cache-0 (dedup/make-cache-element 0)))
  (let [shared {:big [:repeated :subtree]}
        cache  (dedup/de-dupe-eq [shared shared])]
    (is (contains? cache 'de-dupe.cache/cache-0)
        "every cache carries the cache-0 root the agent host expands from")
    (is (every? #(and (symbol? %) (= "de-dupe.cache" (namespace %)))
                (keys cache))
        "every slot is keyed by a de-dupe.cache/cache-N SYMBOL")))

(deftest cache-ids-are-allocated-per-call-not-globally
  ;; The defect the absorb fixed. Upstream the id counter was a
  ;; namespace-global atom that each call `reset!` to 1, so two encodes in
  ;; flight at once could interleave one call's reset with the other's
  ;; allocation and hand out the same `cache-N` twice inside one cache. The
  ;; counter is now call-local: the same input must produce the same slot
  ;; ids no matter what else has been encoded.
  (let [shared {:big [:repeated :subtree]}
        v      [shared shared]
        first-cache (dedup/de-dupe-eq v)]
    (dotimes [_ 5]
      (dedup/de-dupe-eq {:unrelated [{:x 1} {:x 1} {:y 2} {:y 2}]}))
    (is (= first-cache (dedup/de-dupe-eq v))
        "an intervening encode cannot shift this one's cache-id allocation")
    (is (= #{'de-dupe.cache/cache-0 'de-dupe.cache/cache-1} (set (keys first-cache)))
        "ids start at cache-1 for every call, never continue a global run")))

#?(:clj
   (deftest concurrent-encodes-do-not-corrupt-each-other
     ;; The JVM arm of the same defect: with a namespace-global counter,
     ;; parallel encodes could reuse an id inside one cache and the payload
     ;; would expand to the WRONG value. Round-tripping every result is the
     ;; assertion that matters — a duplicated id shows up as a mismatch.
     (let [payloads (mapv (fn [n]
                            (let [shared {:n n :body (vec (range 40))}]
                              {:a shared :b shared :c [shared shared]}))
                          (range 32))
           results  (doall (pmap #(dedup/expand (dedup/de-dupe-eq %)) payloads))]
       (is (= payloads results)
           "32 concurrent encodes each round-trip to their own payload"))))

(deftest expand-round-trips-every-collection-kind
  ;; The codec's structure-preserving guarantee, now ours to keep: lists,
  ;; seqs, sets, nested maps and map entries all rebuild as themselves.
  (let [shared {:s #{:a :b} :v [1 2 3]}
        v      {:list  (list shared shared)
                :seq   (map identity [shared shared])
                :set   #{[:x shared]}
                :nest  {:deep {:deeper [shared shared]}}}
        out    (dedup/expand (dedup/de-dupe-eq v))]
    (is (= v out))
    (is (list? (:list out)) "a list rebuilds as a list")
    (is (set? (:set out))   "a set rebuilds as a set")))
