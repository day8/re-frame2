(ns re-frame.mcp-base.envelope-test
  "Tests for the shared response-envelope helpers —
  the indicator-field 'omit when zero' splice and the wire-bounded
  marker detection."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.envelope :as envelope]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---------------------------------------------------------------------------
;; with-indicators — the MUST-level 'omit when zero' rule.
;; ---------------------------------------------------------------------------

(deftest with-indicators-omits-zero-counts
  (testing "both zero ⇒ envelope unchanged (identity-preserving)"
    (let [env {:trace [1 2 3]}]
      (is (identical? env (envelope/with-indicators env {:dropped 0 :elided 0})))
      (is (identical? env (envelope/with-indicators env {})))
      (is (identical? env (envelope/with-indicators env {:dropped nil :elided nil})))))
  (testing "only dropped positive ⇒ only :dropped-sensitive slot"
    (is (= {:trace [1] :dropped-sensitive 3}
           (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 0}))))
  (testing "only elided positive ⇒ only :elided-large slot"
    (is (= {:db {} :elided-large 2}
           (envelope/with-indicators {:db {}} {:dropped 0 :elided 2}))))
  (testing "both positive ⇒ both slots"
    (is (= {:trace [1] :dropped-sensitive 3 :elided-large 2}
           (envelope/with-indicators {:trace [1]} {:dropped 3 :elided 2})))))

(deftest with-indicators-uses-vocab-keys
  ;; The slots MUST be the canonical vocab keys, not literal keywords —
  ;; pin the dependency so a vocab rename propagates here.
  (let [r (envelope/with-indicators {} {:dropped 1 :elided 1})]
    (is (contains? r vocab/dropped-sensitive-key))
    (is (contains? r vocab/elided-large-key))))

;; ---------------------------------------------------------------------------
;; marker-text? — wire-bounded :rf.mcp/* marker detection.
;; ---------------------------------------------------------------------------

(defn- overflow-fixture []
  {vocab/overflow-key {:limit :reached :token-count 9000 :cap-tokens 5000
                       :tool "snapshot" :hint "narrow"}})

(deftest marker-text?-recognises-boundary-markers
  (testing "overflow marker text"
    (is (true? (envelope/marker-text? (pr-str (overflow-fixture))))))
  (testing "cache-hit marker text"
    (is (true? (envelope/marker-text? (pr-str {vocab/cache-hit-key {:tool "x"}})))))
  (testing "an ordinary tool payload is not a marker"
    (is (false? (envelope/marker-text? (pr-str {:trace [1 2 3]}))))
    (is (false? (envelope/marker-text? (pr-str {:db {:a 1}})))))
  (testing "nil-safe / non-string"
    (is (false? (envelope/marker-text? nil)))
    (is (false? (envelope/marker-text? 42)))))

(deftest marker-prefixes-are-derived-from-vocab
  ;; The prefixes MUST track the vocab keys so a key rename can't
  ;; silently desync the detector from the emitter. Both the flat and
  ;; the namespaced-map print forms are present.
  (is (some #(= % (str "{" vocab/overflow-key)) envelope/marker-prefixes))
  (is (some #(= % (str "{" vocab/cache-hit-key)) envelope/marker-prefixes))
  (is (some #(= % "#:rf.mcp{:overflow") envelope/marker-prefixes))
  (is (some #(= % "#:rf.mcp{:cache-hit") envelope/marker-prefixes)))

(deftest marker-text?-only-matches-leading-marker-not-embedded-key
  ;; `marker-text?` is `starts-with?`, not
  ;; `includes?` — the marker key must be the LEADING top-level key for
  ;; the text to count as a boundary marker. The comment block in
  ;; envelope.cljc justifies the prefix-match precisely on this ground:
  ;; an ordinary payload that merely CONTAINS `:rf.mcp/overflow` as a
  ;; nested value must NOT be mistaken for a boundary-step marker (which
  ;; would make a later boundary step skip re-walking a real payload).
  ;; A regression that loosened `starts-with?` to `includes?` would
  ;; trip this test.
  (testing "marker key as a nested value ⇒ NOT a marker"
    (is (false? (envelope/marker-text?
                  (pr-str {:trace [{:note "saw :rf.mcp/overflow once"}]})))
        "the key appearing as string content is not a leading marker")
    (is (false? (envelope/marker-text?
                  (pr-str {:result :ok :detail {vocab/overflow-key {:limit :reached}}})))
        "an overflow marker nested under :detail is not a LEADING marker"))
  (testing "marker key as a non-first top-level key ⇒ NOT a marker"
    ;; pr-str of an array-map preserves insertion order, so :a prints
    ;; first; the overflow key is present but not leading.
    (let [s (pr-str (array-map :a 1 vocab/overflow-key {:limit :reached}))]
      (is (false? (envelope/marker-text? s))
          "overflow key present but not the leading key ⇒ not a marker"))))

(deftest marker-text?-empty-and-blank-strings-are-not-markers
  ;; Boundary pin: the empty string and whitespace are
  ;; strings (so they pass the `string?` guard) but match no prefix.
  (is (false? (envelope/marker-text? "")))
  (is (false? (envelope/marker-text? "   ")))
  (is (false? (envelope/marker-text? "{")))
  (is (false? (envelope/marker-text? "#:rf.mcp"))
      "the namespaced-map prefix STEM alone (no key) is not a complete marker prefix"))

(deftest marker-text?-handles-both-print-forms
  ;; JVM `pr-str` emits the namespaced-map shorthand for a single-ns
  ;; map; CLJS emits the flat form. Both MUST be detected so the cap /
  ;; cache boundary steps recognise a marker regardless of host.
  (testing "namespaced-map form (JVM default)"
    (is (true? (envelope/marker-text? "#:rf.mcp{:overflow {:limit :reached}}")))
    (is (true? (envelope/marker-text? "#:rf.mcp{:cache-hit {:tool \"x\"}}"))))
  (testing "flat form (CLJS / *print-namespace-maps* false)"
    (is (true? (envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}}")))
    (is (true? (envelope/marker-text? "{:rf.mcp/cache-hit {:tool \"x\"}}")))))

(deftest marker-text?-requires-exact-marker-key-not-prefix
  ;; A bare `starts-with?` of the marker-key PREFIX would classify a
  ;; LOOKALIKE leading key whose name merely begins with a real marker
  ;; key (`:rf.mcp/overflowed`, `:rf.mcp/cache-hit-extra`) as an
  ;; already-bounded marker — letting an over-budget payload bypass cap
  ;; enforcement. The detector requires the marker key to END exactly at
  ;; the prefix (terminated by an EDN token terminator), so a strict
  ;; prefix-superset key is NOT a marker.
  (testing "strict prefix-superset of a marker key ⇒ NOT a marker (flat form)"
    (is (false? (envelope/marker-text? "{:rf.mcp/overflowed {:x 1}}"))
        "':rf.mcp/overflowed' merely starts with ':rf.mcp/overflow'")
    (is (false? (envelope/marker-text? "{:rf.mcp/cache-hit-extra {:x 1}}"))
        "':rf.mcp/cache-hit-extra' merely starts with ':rf.mcp/cache-hit'")
    ;; A real payload over budget whose first key is a lookalike: the
    ;; realistic cap-bypass shape this guard prevents.
    (is (false? (envelope/marker-text?
                  (pr-str (array-map :rf.mcp/overflowed {:limit :reached :token-count 9000}))))))
  (testing "strict prefix-superset of a marker key ⇒ NOT a marker (namespaced-map form)"
    (is (false? (envelope/marker-text? "#:rf.mcp{:overflowed {:x 1}}")))
    (is (false? (envelope/marker-text? "#:rf.mcp{:cache-hit-extra {:x 1}}"))))
  (testing "the EXACT marker key still matches (both print forms)"
    (is (true? (envelope/marker-text? "{:rf.mcp/overflow {:x 1}}")))
    (is (true? (envelope/marker-text? "{:rf.mcp/cache-hit {:x 1}}")))
    (is (true? (envelope/marker-text? "#:rf.mcp{:overflow {:x 1}}")))
    (is (true? (envelope/marker-text? "#:rf.mcp{:cache-hit {:x 1}}")))
    ;; Round-trip through pr-str under both *print-namespace-maps* settings.
    (is (true? (envelope/marker-text?
                 (binding [*print-namespace-maps* false]
                   (pr-str {vocab/overflow-key {:limit :reached}})))))
    (is (true? (envelope/marker-text?
                 (binding [*print-namespace-maps* true]
                   (pr-str {vocab/cache-hit-key {:tool "x"}})))))))
