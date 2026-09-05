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
  `rf.mcp-base.dedup/expand`, the inverse an agent-side Clojure consumer calls. The
  codec itself was vendored from `day8/de-dupe` v0.3.0 under rf2-2ii52;
  this is the canonical suite that pins it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.mcp-base.dedup :as rf.mcp-base.dedup]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

(deftest empty-payload?-true-for-no-win-values
  (testing "nil, empty colls, and scalars yield no dedup win"
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? nil))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? []))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? {}))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? #{}))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? ""))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? 42))))
    (is (true? (boolean (rf.mcp-base.dedup/empty-payload? :a-keyword))))))

(deftest empty-payload?-false-for-non-empty-colls
  (is (false? (boolean (rf.mcp-base.dedup/empty-payload? [1 2 3]))))
  (is (false? (boolean (rf.mcp-base.dedup/empty-payload? {:a 1}))))
  (is (false? (boolean (rf.mcp-base.dedup/empty-payload? #{:x})))))

(deftest dedup-value-disabled-returns-input-verbatim
  (let [v [{:a 1} {:a 1}]]
    (is (identical? v (rf.mcp-base.dedup/dedup-value v false))
        "enabled? false is a verbatim passthrough")))

(deftest dedup-value-empty-payload-returns-input-verbatim
  ;; No dedup opportunity ⇒ skip the wrap (the one-entry cache would be
  ;; slightly LARGER than the input).
  (is (= [] (rf.mcp-base.dedup/dedup-value [] true)))
  (is (= {} (rf.mcp-base.dedup/dedup-value {} true)))
  (is (nil? (rf.mcp-base.dedup/dedup-value nil true)))
  (is (= 42 (rf.mcp-base.dedup/dedup-value 42 true))))

(deftest no-substitutions?-detects-one-entry-root-only-cache
  ;; `de-dupe-eq` always emits `cache-0` for the root; every substituted
  ;; subtree adds a further `cache-N`. A one-entry cache therefore made
  ;; NO substitutions.
  (testing "a no-repeat non-empty collection ⇒ one-entry root-only cache"
    (is (true? (boolean (rf.mcp-base.dedup/no-substitutions? (rf.mcp-base.dedup/de-dupe-eq {:a 1 :b 2})))))
    (is (true? (boolean (rf.mcp-base.dedup/no-substitutions? (rf.mcp-base.dedup/de-dupe-eq [1 2 3])))))
    (is (true? (boolean (rf.mcp-base.dedup/no-substitutions? (rf.mcp-base.dedup/de-dupe-eq {:a {:x 1} :b {:y 2}}))))))
  (testing "a repeated-subtree cache has >1 entry ⇒ substitutions happened"
    (is (false? (boolean (rf.mcp-base.dedup/no-substitutions? (rf.mcp-base.dedup/de-dupe-eq [{:x 1} {:x 1}]))))))
  (testing "non-map / empty inputs are not a root-only cache"
    (is (false? (boolean (rf.mcp-base.dedup/no-substitutions? nil))))
    (is (false? (boolean (rf.mcp-base.dedup/no-substitutions? {}))))))

(deftest dedup-value-no-repeats-non-empty-returns-input-verbatim
  ;; A non-empty collection with no repeated subtrees produces only the
  ;; root cache entry; wrapping it would grow the wire value.
  (testing "a flat no-repeat map is returned identical, NOT wrapped"
    (let [v {:a 1 :b 2 :c 3}
          out (rf.mcp-base.dedup/dedup-value v true)]
      (is (identical? v out)
          "no dedup opportunity ⇒ verbatim passthrough, not a dedup-table wrap")
      (is (not (contains? out rf.mcp-base.vocab/dedup-table-key)))))
  (testing "a nested no-repeat structure is returned identical"
    (let [v {:user {:name "ada"} :session {:state :idle} :items [1 2 3]}]
      (is (identical? v (rf.mcp-base.dedup/dedup-value v true)))))
  (testing "a no-repeat vector is returned identical"
    (let [v [{:id 1} {:id 2} {:id 3}]]      ; all distinct ⇒ no substitution
      (is (identical? v (rf.mcp-base.dedup/dedup-value v true))))))

(deftest dedup-value-repeated-subtree-still-wraps
  ;; The adversarial complement to the no-op skip: a payload that DOES
  ;; carry a repeated subtree must STILL wrap (the skip must not swallow a
  ;; genuine dedup win). Its cache has >1 entry, so `no-substitutions?` is
  ;; false and the wrap fires.
  (let [shared {:big [:repeated :subtree]}
        v      [shared shared shared]
        out    (rf.mcp-base.dedup/dedup-value v true)]
    (is (map? out))
    (is (contains? out rf.mcp-base.vocab/dedup-table-key)
        "a repeated subtree is a real dedup win — the wrap must fire")
    (is (= v (rf.mcp-base.dedup/expand (get out rf.mcp-base.vocab/dedup-table-key)))
        "and the wrap still round-trips")))

(deftest dedup-value-wraps-in-cross-mcp-marker
  (let [shared {:repeated [:big :subtree :here]}
        v      {:a shared :b shared :c shared}
        out    (rf.mcp-base.dedup/dedup-value v true)]
    (testing "the wire shape is the cross-MCP dedup-table marker"
      (is (map? out))
      (is (contains? out rf.mcp-base.vocab/dedup-table-key))
      (is (= #{rf.mcp-base.vocab/dedup-table-key} (set (keys out)))
          "exactly one top-level slot, the dedup-table marker"))))

(deftest dedup-value-round-trips-exactly
  ;; The agent host reconstructs by calling rf.mcp-base.dedup/expand on the
  ;; cache-map value — pin that the wrap is losslessly reversible.
  (let [shared {:repeated (vec (range 50))}
        v      {:a shared :b shared :c shared :scalars [1 2 3]}
        out    (rf.mcp-base.dedup/dedup-value v true)
        cache  (get out rf.mcp-base.vocab/dedup-table-key)]
    (is (= v (rf.mcp-base.dedup/expand cache))
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
        out (rf.mcp-base.dedup/dedup-value v true)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key))
    (is (= v (rf.mcp-base.dedup/expand (get out rf.mcp-base.vocab/dedup-table-key)))
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
  (is (= "de-dupe.cache" rf.mcp-base.dedup/cache-element-ns))
  (is (= 'de-dupe.cache/cache-0 (rf.mcp-base.dedup/make-cache-element 0)))
  (let [shared {:big [:repeated :subtree]}
        cache  (rf.mcp-base.dedup/de-dupe-eq [shared shared])]
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
        first-cache (rf.mcp-base.dedup/de-dupe-eq v)]
    (dotimes [_ 5]
      (rf.mcp-base.dedup/de-dupe-eq {:unrelated [{:x 1} {:x 1} {:y 2} {:y 2}]}))
    (is (= first-cache (rf.mcp-base.dedup/de-dupe-eq v))
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
           results  (doall (pmap #(rf.mcp-base.dedup/expand (rf.mcp-base.dedup/de-dupe-eq %)) payloads))]
       (is (= payloads results)
           "32 concurrent encodes each round-trip to their own payload"))))

;; ---------------------------------------------------------------------------
;; rf2-kjv05 — a payload value that OCCUPIES the reference namespace is data.
;;
;; The decoder used to classify every symbol namespaced `de-dupe.cache` as a
;; reference to a cache slot, so an ordinary payload symbol was aliased to
;; whatever that slot held: nil when the slot was absent, somebody else's
;; subtree when it was not. `expand` therefore was not the exact inverse the
;; codec promises, for payloads a re-frame app can legitimately hold. Every
;; test below is forced through a REAL wrap by an unrelated repeated subtree —
;; the collision is not itself a dedup opportunity, so without that control
;; `dedup-value` would return the payload verbatim and the assertions would
;; pass vacuously against a codec that never ran.
;; ---------------------------------------------------------------------------

(deftest payload-symbols-in-the-reference-namespace-round-trip-as-data
  (let [shared  {:big [:repeat :me]}
        payload {:literal    'de-dupe.cache/not-a-ref
                 :look-alike 'de-dupe.cache/cache-1
                 :a          shared
                 :b          shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)
        back    (rf.mcp-base.dedup/expand cache)]
    (testing "non-vacuity — the unrelated repeated subtree really did wrap"
      (is (map? out))
      (is (contains? out rf.mcp-base.vocab/dedup-table-key)
          "without a real wrap the round-trip below proves nothing"))
    (testing "expand is the exact inverse"
      (is (= payload back)))
    (testing "both literals come back as SYMBOLS with their exact values"
      (is (symbol? (:literal back)))
      (is (= 'de-dupe.cache/not-a-ref (:literal back)))
      (is (symbol? (:look-alike back)))
      (is (= 'de-dupe.cache/cache-1 (:look-alike back))
          "a generated-LOOKING payload symbol is still payload"))))

(deftest colliding-payload-tokens-are-escaped-on-the-wire
  ;; The wire spelling itself, pinned here because the Node conformance
  ;; decoder's fixtures mirror it (tools/mcp-conformance/test/
  ;; dedup-envelope.test.cjs). In ONE cache, `cache-1` in value position is a
  ;; reference and `!cache-1` is the payload symbol that merely looks like one.
  (let [shared  {:big [:repeat :me]}
        payload {:literal    'de-dupe.cache/not-a-ref
                 :look-alike 'de-dupe.cache/cache-1
                 :a          shared
                 :b          shared}
        cache   (rf.mcp-base.dedup/de-dupe-eq payload)
        root    (get cache 'de-dupe.cache/cache-0)]
    (testing "a look-alike token carries one escape marker"
      (is (= 'de-dupe.cache/!cache-1 (:look-alike root))))
    (testing "an ordinary token in the namespace is left alone — it reads as data already"
      (is (= 'de-dupe.cache/not-a-ref (:literal root))))
    (testing "the genuine reference names a real slot, and both occurrences share it"
      (let [ref (:a root)]
        (is (= 2 (count cache)) "root plus the one pooled subtree")
        (is (= (rf.mcp-base.dedup/make-cache-element 1) ref)
            "the allocated id is cache-1 — the very spelling the payload literal carries")
        (is (= ref (:b root)) "both occurrences point at the one slot")
        (is (= shared (get cache ref)) "and that slot holds the shared subtree")))))

(deftest escaping-is-reversible-under-repetition
  ;; A payload token already spelled like an ESCAPE must not be mistaken for
  ;; one on the way back: it gains a marker on encode and sheds exactly one on
  ;; decode. Without this the escape would be a second aliasing bug wearing the
  ;; first one's clothes.
  (let [shared  {:big [:repeat :me]}
        payload {:once   'de-dupe.cache/!cache-1
                 :twice  'de-dupe.cache/!!cache-1
                 :other  'de-dupe.cache/!not-a-ref
                 :a      shared
                 :b      shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key) "non-vacuity: a real wrap")
    (is (= payload (rf.mcp-base.dedup/expand (get out rf.mcp-base.vocab/dedup-table-key))))))

(deftest payload-strings-that-spell-a-reference-round-trip-as-strings
  ;; JSON erases the symbol/string distinction — Cheshire renders the symbol
  ;; `de-dupe.cache/cache-1` and the string "de-dupe.cache/cache-1" as one JSON
  ;; string — so the codec escapes both, and the escape is TYPE-preserving: a
  ;; string comes back a string, never a symbol.
  (let [shared  {:big [:repeat :me]}
        payload {:s     "de-dupe.cache/cache-1"
                 :plain "de-dupe.cache/not-a-ref"
                 :a     shared
                 :b     shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        back    (rf.mcp-base.dedup/expand (get out rf.mcp-base.vocab/dedup-table-key))]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key) "non-vacuity: a real wrap")
    (is (= payload back))
    (is (string? (:s back)) "an escaped string comes back a string, not a symbol")
    (is (= "de-dupe.cache/cache-1" (:s back)))
    (is (= "de-dupe.cache/not-a-ref" (:plain back)))))

;; ---------------------------------------------------------------------------
;; rf2-kjv05, second cut — the KEYWORD arm.
;;
;; The first cut escaped symbols and strings, and stopped there. That left the
;; Clojure round-trip exact and the JSON projection still corrupt, which is the
;; hardest version of this bug to see: a keyword is not a symbol, so
;; `cache-element?` never aliased it and every JVM/CLJS assertion passed — but
;; Cheshire renders `:de-dupe.cache/cache-1` as the string
;; "de-dupe.cache/cache-1", the identical spelling a real reference arrives
;; under, so the Node decoder read the payload keyword as a slot reference and
;; handed the agent the cached subtree instead. A keyword is the ORDINARY
;; spelling of both a value and a map key in re-frame app-db data, so this was
;; the likeliest of the three collisions to be hit in practice.
;;
;; These tests pin the wire spelling, because that is the seam: the JVM/CLJS
;; round-trip alone cannot see the defect. Their Node counterparts consume the
;; very fixtures pinned here — see `tools/mcp-conformance/test/
;; dedup-envelope.test.cjs`.
;; ---------------------------------------------------------------------------

(deftest payload-keywords-in-the-reference-namespace-round-trip-as-keywords
  (let [shared  {:big [:repeat :me]}
        payload {:literal    :de-dupe.cache/not-a-ref
                 :look-alike :de-dupe.cache/cache-1
                 :a          shared
                 :b          shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)
        back    (rf.mcp-base.dedup/expand cache)]
    (testing "non-vacuity — the unrelated repeated subtree really did wrap"
      (is (contains? out rf.mcp-base.vocab/dedup-table-key)))
    (testing "expand is the exact inverse, and TYPE-preserving"
      (is (= payload back))
      (is (keyword? (:look-alike back))
          "an escaped keyword comes back a keyword, never a symbol or a string")
      (is (= :de-dupe.cache/cache-1 (:look-alike back)))
      (is (keyword? (:literal back)))
      (is (= :de-dupe.cache/not-a-ref (:literal back))))))

(deftest colliding-payload-keywords-are-escaped-on-the-wire
  ;; The spelling the JSON projection depends on. Pinned as its own assertion
  ;; rather than inferred from the round-trip above, because the round-trip
  ;; passed for keywords BEFORE this was fixed — the Clojure decoder never
  ;; aliased a keyword. Only the wire spelling distinguishes the two states.
  (let [shared  {:big [:repeat :me]}
        payload {:look-alike :de-dupe.cache/cache-1
                 :literal    :de-dupe.cache/not-a-ref
                 :a          shared
                 :b          shared}
        cache   (rf.mcp-base.dedup/de-dupe-eq payload)
        root    (get cache 'de-dupe.cache/cache-0)]
    (is (= :de-dupe.cache/!cache-1 (:look-alike root))
        "a look-alike keyword carries one escape marker, as a symbol would")
    (is (= :de-dupe.cache/not-a-ref (:literal root))
        "an ordinary keyword in the namespace already reads as data")
    (is (= (rf.mcp-base.dedup/make-cache-element 1) (:a root))
        "and the genuine reference beside it is untouched")))

(deftest colliding-payload-keywords-are-escaped-in-map-KEY-position-too
  ;; A keyword's commonest position in re-frame data is as a map key, and both
  ;; decoders route keys through the same value walk — so an unescaped
  ;; look-alike KEY resolved to a cached subtree and the entry became
  ;; unreachable under its own name.
  (let [shared  {:big [:repeat :me]}
        payload {:de-dupe.cache/cache-1 "keyed"
                 :a                     shared
                 :b                     shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)
        root    (get cache 'de-dupe.cache/cache-0)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key) "non-vacuity: a real wrap")
    (is (contains? root :de-dupe.cache/!cache-1)
        "the colliding KEY is escaped on the wire")
    (is (= "keyed" (get root :de-dupe.cache/!cache-1)))
    (is (= payload (rf.mcp-base.dedup/expand cache))
        "and the key comes back under its original spelling")))

(deftest keyword-escaping-is-reversible-under-repetition
  ;; Same reversibility the symbol arm has: a payload keyword already spelled
  ;; like an escape gains a marker and sheds exactly one. Without it the
  ;; keyword arm would trade the aliasing bug for a mangling one.
  (let [shared  {:big [:repeat :me]}
        payload {:once   :de-dupe.cache/!cache-1
                 :twice  :de-dupe.cache/!!cache-1
                 :other  :de-dupe.cache/!not-a-ref
                 :a      shared
                 :b      shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key) "non-vacuity: a real wrap")
    (is (= :de-dupe.cache/!!cache-1 (:once (get cache 'de-dupe.cache/cache-0)))
        "one marker added on the way out")
    (is (= payload (rf.mcp-base.dedup/expand cache))
        "and exactly one shed on the way back")))

(deftest all-three-json-flattened-types-collide-and-all-three-are-escaped
  ;; The set closure, in ONE cache. Symbol, keyword and string spelled
  ;; `de-dupe.cache/cache-1` are three distinct Clojure values that a JSON
  ;; encoder renders as one string — the same string a real reference arrives
  ;; under. All three must be escaped, and each must come back its own type.
  (let [shared  {:big [:repeat :me]}
        payload {:sym 'de-dupe.cache/cache-1
                 :kw  :de-dupe.cache/cache-1
                 :str "de-dupe.cache/cache-1"
                 :a   shared
                 :b   shared}
        cache   (rf.mcp-base.dedup/de-dupe-eq payload)
        root    (get cache 'de-dupe.cache/cache-0)
        back    (rf.mcp-base.dedup/expand cache)]
    (testing "every one of the three is escaped on the wire, in its own type"
      (is (= 'de-dupe.cache/!cache-1 (:sym root)))
      (is (= :de-dupe.cache/!cache-1 (:kw root)))
      (is (= "de-dupe.cache/!cache-1" (:str root))))
    (testing "the genuine reference is not escaped and still names a real slot"
      (is (= (rf.mcp-base.dedup/make-cache-element 1) (:a root)))
      (is (= shared (get cache (:a root)))))
    (testing "and all three come back distinct, as themselves"
      (is (= payload back))
      (is (symbol? (:sym back)))
      (is (keyword? (:kw back)))
      (is (string? (:str back)))
      (is (= 3 (count (distinct [(:sym back) (:kw back) (:str back)])))
          "three types, one spelling — the codec must not have merged them"))))

(deftest colliding-literals-survive-inside-a-pooled-subtree
  ;; The collision need not sit at the root. A look-alike token nested inside
  ;; the very subtree that gets pooled must still round-trip — the escape runs
  ;; before the counting pass, so both passes hash the same escaped subtree and
  ;; the pooling still fires.
  (let [shared  {:tag 'de-dupe.cache/cache-1 :body (vec (range 20))}
        payload {:a shared :b shared :c [shared]}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key) "non-vacuity: a real wrap")
    (is (< 1 (count cache))
        "the pooling still fires on a subtree carrying a look-alike token")
    (is (= payload (rf.mcp-base.dedup/expand cache)))))

(deftest a-reference-namespace-payload-alone-is-not-a-dedup-opportunity
  ;; The complement, and the reason the tests above force a wrap: a payload
  ;; whose only remarkable feature is a look-alike token has no repeated
  ;; subtree, so `dedup-value` returns it verbatim and nothing can alias it.
  (let [v {:literal 'de-dupe.cache/cache-1 :n 1}]
    (is (identical? v (rf.mcp-base.dedup/dedup-value v true))
        "no repeats ⇒ verbatim passthrough, escaping and all")))

(deftest expand-round-trips-every-collection-kind
  ;; The codec's structure-preserving guarantee, now ours to keep: lists,
  ;; seqs, sets, nested maps and map entries all rebuild as themselves.
  ;; The name says EVERY kind, so the sorted pair rides along — they are
  ;; the two core collections the walk cannot rebuild as themselves (see
  ;; `sorted-map-with-a-pooled-key-…` below), and a suite that named them
  ;; and then skipped them is how this class stayed unseen.
  (let [shared {:s #{:a :b} :v [1 2 3]}
        v      {:list   (list shared shared)
                :seq    (map identity [shared shared])
                :set    #{[:x shared]}
                :nest   {:deep {:deeper [shared shared]}}
                :sorted (sorted-map :b shared :a shared)
                :s-set  (sorted-set :one :two)}
        out    (rf.mcp-base.dedup/expand (rf.mcp-base.dedup/de-dupe-eq v))]
    (is (= v out))
    (is (list? (:list out)) "a list rebuilds as a list")
    (is (set? (:set out))   "a set rebuilds as a set")
    ;; Sortedness is DELIBERATELY not carried: the cache is EDN/JSON, where
    ;; a sorted map reads back unsorted anyway, and holding the comparator
    ;; is what made a pooled key throw. Clojure equality is exact either way.
    (is (and (map? (:sorted out)) (not (sorted? (:sorted out))))
        "a sorted map rebuilds as an unsorted map")
    (is (and (set? (:s-set out)) (not (sorted? (:s-set out))))
        "a sorted set rebuilds as an unsorted set")))

;; ---- Sorted collections: comparator positions and pooled placeholders ------
;;
;; Substitution replaces a repeated cacheable subtree with a
;; `de-dupe.cache/cache-N` SYMBOL. Rebuild a sorted collection through its
;; own comparator and that symbol is handed to a comparator chosen for the
;; data — `compare` on a Symbol and an IPersistentVector throws. Dedup is
;; DEFAULT-ON, so this turned ordinary persistent app-db state into a
;; boundary failure rather than a read.

(deftest sorted-map-with-a-pooled-key-beside-an-unpooled-one-round-trips
  (let [shared  [1 2 3]
        unique  [4 5 6]
        payload {:index (sorted-map shared :old unique :new)
                 :again shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key)
        "non-vacuity: a real wrap, not the no-substitutions passthrough")
    (is (< 1 (count cache))
        "the repeated key really was pooled — a substitution beyond cache-0")
    (is (= payload (rf.mcp-base.dedup/expand cache))
        "expand is equal to the input")
    (testing "the unordered control takes the same shape"
      (let [control (assoc payload :index {shared :old unique :new})
            c-out   (rf.mcp-base.dedup/dedup-value control true)
            c-cache (get c-out rf.mcp-base.vocab/dedup-table-key)]
        (is (< 1 (count c-cache)))
        (is (= control (rf.mcp-base.dedup/expand c-cache)))))
    (testing "opting out stays a strict passthrough"
      (is (identical? payload (rf.mcp-base.dedup/dedup-value payload false))))))

(deftest sorted-set-with-a-pooled-element-beside-an-unpooled-one-round-trips
  ;; The same root cause reached through the other comparator-bearing core
  ;; collection: here the placeholder lands in ELEMENT position.
  (let [shared  [1 2 3]
        payload {:index (sorted-set shared [4 5 6])
                 :again shared}
        out     (rf.mcp-base.dedup/dedup-value payload true)
        cache   (get out rf.mcp-base.vocab/dedup-table-key)]
    (is (contains? out rf.mcp-base.vocab/dedup-table-key)
        "non-vacuity: a real wrap")
    (is (< 1 (count cache))
        "the repeated element really was pooled")
    (is (= payload (rf.mcp-base.dedup/expand cache))
        "expand is equal to the input")))
