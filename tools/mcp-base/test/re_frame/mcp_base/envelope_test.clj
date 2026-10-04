(ns re-frame.mcp-base.envelope-test
  "Tests for the shared response-envelope helpers —
  the indicator-field 'omit when zero' splice and the wire-bounded
  marker detection."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.envelope :as rf.mcp-base.envelope]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]))

;; ---------------------------------------------------------------------------
;; with-indicators — the MUST-level 'omit when zero' rule.
;; ---------------------------------------------------------------------------

(deftest with-indicators-omits-zero-counts
  (testing "both zero ⇒ envelope unchanged (identity-preserving)"
    (let [env {:trace [1 2 3]}]
      (is (identical? env (rf.mcp-base.envelope/with-indicators env {:dropped 0 :elided 0})))
      (is (identical? env (rf.mcp-base.envelope/with-indicators env {})))
      (is (identical? env (rf.mcp-base.envelope/with-indicators env {:dropped nil :elided nil})))))
  (testing "only dropped positive ⇒ only :dropped-sensitive slot"
    (is (= {:trace [1] :dropped-sensitive 3}
           (rf.mcp-base.envelope/with-indicators {:trace [1]} {:dropped 3 :elided 0}))))
  (testing "only elided positive ⇒ only :elided-large slot"
    (is (= {:db {} :elided-large 2}
           (rf.mcp-base.envelope/with-indicators {:db {}} {:dropped 0 :elided 2}))))
  (testing "both positive ⇒ both slots"
    (is (= {:trace [1] :dropped-sensitive 3 :elided-large 2}
           (rf.mcp-base.envelope/with-indicators {:trace [1]} {:dropped 3 :elided 2})))))

;; ---------------------------------------------------------------------------
;; marker-text? — wire-bounded :rf.mcp/* marker detection.
;; ---------------------------------------------------------------------------

(defn- overflow-fixture []
  {rf.mcp-base.vocab/overflow-key {:limit :reached :token-count 9000 :cap-tokens 5000
                       :tool "snapshot" :hint "narrow"}})

(deftest marker-text?-recognises-boundary-markers
  (testing "overflow marker text"
    (is (true? (rf.mcp-base.envelope/marker-text? (pr-str (overflow-fixture))))))
  (testing "cache-hit marker text"
    (is (true? (rf.mcp-base.envelope/marker-text? (pr-str {rf.mcp-base.vocab/cache-hit-key {:tool "x"}})))))
  (testing "an ordinary tool payload is not a marker"
    (is (false? (rf.mcp-base.envelope/marker-text? (pr-str {:trace [1 2 3]}))))
    (is (false? (rf.mcp-base.envelope/marker-text? (pr-str {:db {:a 1}})))))
  (testing "nil-safe / non-string"
    (is (false? (rf.mcp-base.envelope/marker-text? nil)))
    (is (false? (rf.mcp-base.envelope/marker-text? 42)))))

(deftest marker-text?-only-matches-leading-marker-not-embedded-key
  ;; The marker key must be the LEADING and ONLY top-level key for the
  ;; text to count as a boundary marker: an ordinary payload that merely
  ;; CONTAINS `:rf.mcp/overflow`, as a nested value or as a later key,
  ;; must NOT be mistaken for a boundary-step marker (which would make a
  ;; later boundary step skip re-walking a real payload). The
  ;; leading-token pre-filter and the closed-wrapper read each reject
  ;; these on their own, so loosening either one alone keeps this green.
  (testing "marker key as a nested value ⇒ NOT a marker"
    (is (false? (rf.mcp-base.envelope/marker-text?
                  (pr-str {:trace [{:note "saw :rf.mcp/overflow once"}]})))
        "the key appearing as string content is not a leading marker")
    (is (false? (rf.mcp-base.envelope/marker-text?
                  (pr-str {:result :ok :detail {rf.mcp-base.vocab/overflow-key {:limit :reached}}})))
        "an overflow marker nested under :detail is not a LEADING marker"))
  (testing "marker key as a non-first top-level key ⇒ NOT a marker"
    ;; pr-str of an array-map preserves insertion order, so :a prints
    ;; first; the overflow key is present but not leading.
    (let [s (pr-str (array-map :a 1 rf.mcp-base.vocab/overflow-key {:limit :reached}))]
      (is (false? (rf.mcp-base.envelope/marker-text? s))
          "overflow key present but not the leading key ⇒ not a marker"))))

(deftest marker-text?-handles-both-print-forms
  ;; JVM `pr-str` emits the namespaced-map shorthand for a single-ns
  ;; map; CLJS emits the flat form. Both MUST be detected so the cap /
  ;; cache boundary steps recognise a marker regardless of host.
  (testing "namespaced-map form (JVM default)"
    (is (true? (rf.mcp-base.envelope/marker-text? "#:rf.mcp{:overflow {:limit :reached}}")))
    (is (true? (rf.mcp-base.envelope/marker-text? "#:rf.mcp{:cache-hit {:tool \"x\"}}"))))
  (testing "flat form (CLJS / *print-namespace-maps* false)"
    (is (true? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}}")))
    (is (true? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/cache-hit {:tool \"x\"}}")))))

(deftest marker-text?-requires-exact-marker-key-not-prefix
  ;; A bare `starts-with?` of the marker-key PREFIX would classify a
  ;; LOOKALIKE leading key whose name merely begins with a real marker
  ;; key (`:rf.mcp/overflowed`, `:rf.mcp/cache-hit-extra`) as an
  ;; already-bounded marker — letting an over-budget payload bypass cap
  ;; enforcement. The detector requires the marker key to END exactly at
  ;; the prefix (terminated by an EDN token terminator), so a strict
  ;; prefix-superset key is NOT a marker.
  (testing "strict prefix-superset of a marker key ⇒ NOT a marker (flat form)"
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflowed {:x 1}}"))
        "':rf.mcp/overflowed' merely starts with ':rf.mcp/overflow'")
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/cache-hit-extra {:x 1}}"))
        "':rf.mcp/cache-hit-extra' merely starts with ':rf.mcp/cache-hit'")
    ;; A real payload over budget whose first key is a lookalike: the
    ;; realistic cap-bypass shape this guard prevents.
    (is (false? (rf.mcp-base.envelope/marker-text?
                  (pr-str (array-map :rf.mcp/overflowed {:limit :reached :token-count 9000}))))))
  (testing "strict prefix-superset of a marker key ⇒ NOT a marker (namespaced-map form)"
    (is (false? (rf.mcp-base.envelope/marker-text? "#:rf.mcp{:overflowed {:x 1}}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "#:rf.mcp{:cache-hit-extra {:x 1}}"))))
  (testing "the EXACT marker key still matches, rendered by pr-str under both print settings"
    (is (true? (rf.mcp-base.envelope/marker-text?
                 (binding [*print-namespace-maps* false]
                   (pr-str {rf.mcp-base.vocab/overflow-key {:limit :reached}})))))
    (is (true? (rf.mcp-base.envelope/marker-text?
                 (binding [*print-namespace-maps* true]
                   (pr-str {rf.mcp-base.vocab/cache-hit-key {:tool "x"}})))))))

(deftest marker-text?-requires-closed-single-key-wrapper-not-just-first-key
  ;; The leading-token match proves only the FIRST key. The
  ;; invariant the fast-path skip relies on — "a marker is sub-cap BY
  ;; CONSTRUCTION" — holds only for a COMPLETE, CLOSED, single-key marker
  ;; map. A mixed wrapper whose FIRST key is a real marker key but which
  ;; carries an unexpected top-level SIBLING (or a trailing form / tagged
  ;; literal / non-map body) is NOT a marker: it must fall through to cap
  ;; measurement, never inherit the exemption. A prefix-only recogniser
  ;; would return true for every case below (the cap-bypass hole).
  (testing "over-budget mixed wrapper with a top-level sibling ⇒ NOT a marker"
    (let [big (apply str (repeat 8000 "x"))]
      ;; The cap-bypass shape: reserved key FIRST, huge sibling.
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (pr-str (array-map rf.mcp-base.vocab/overflow-key {:limit :reached}
                                       :unexpected big))))
          "an 8K sibling under an overflow-keyed wrapper must be capped, not skipped")
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (pr-str (array-map rf.mcp-base.vocab/cache-hit-key {:tool "x"}
                                       :unexpected big))))
          "the same hole for cache-hit")))
  (testing "extra top-level sibling ⇒ NOT a marker (both print forms)"
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached} :unexpected 1}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "#:rf.mcp{:overflow {:limit :reached}, :other/k 2}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/cache-hit {:tool \"x\"} :extra \"y\"}"))))
  (testing "trailing EDN form after the closed marker ⇒ NOT a marker"
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}} 42")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}} {:junk 1}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/cache-hit {:tool \"x\"}} :trailing")))
    ;; `]`-injection cannot truncate the read early past the EOF sentinel.
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}}] {:junk 1}"))))
  (testing "tagged literal in the body ⇒ NOT a marker (built-in and custom)"
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:at #inst \"2024-01-01T00:00:00.000-00:00\"}}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow #js {:limit :reached}}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:id #uuid \"00000000-0000-0000-0000-000000000000\"}}"))))
  (testing "missing / non-map body ⇒ NOT a marker"
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow \"a string body\"}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow 42}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow nil}")))
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/cache-hit [:not :a :map]}")))
    ;; Truncated text (no closing brace) ⇒ read fails ⇒ NOT a marker.
    (is (false? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached}"))))
  (testing "non-map root that merely starts with a marker-key token ⇒ NOT a marker"
    ;; A vector/list literal whose printed head could look marker-ish.
    (is (false? (rf.mcp-base.envelope/marker-text? "[:rf.mcp/overflow {:limit :reached}]"))))
  (testing "additive fields inside a closed marker's body still match"
    (is (true? (rf.mcp-base.envelope/marker-text? "{:rf.mcp/overflow {:limit :reached :extra :ok}}")))))

(deftest marker-text?-bounds-body-size-not-just-closure
  ;; Closure alone does NOT bound the marker BODY size. A
  ;; COMPLETE, CLOSED, single-key {:rf.mcp/overflow {…huge…}} passes the
  ;; leading-token + closed-wrapper gates yet its rendered text is
  ;; arbitrarily large — over-budget by construction. A closure-only
  ;; recogniser would return true and let the fast-path skip egress it
  ;; un-capped (100 KB ≈ 25k tokens ≫ the 5k default cap). The size gate makes "a marker is
  ;; sub-cap BY CONSTRUCTION" TRUE for the recogniser: an over-default-cap
  ;; single-key marker is NOT skip-eligible and continues through cap
  ;; enforcement. This is the BODY-SIZE dimension of the same threat the
  ;; sibling-key test above covers for the extra-sibling dimension.
  (let [over-budget (apply str (repeat (* 8 rf.mcp-base.overflow/default-max-tokens) "x"))] ;; ~2× the default cap
    (testing "the injected body really is over the default cap (non-vacuous)"
      (is (> (rf.mcp-base.overflow/token-estimate over-budget) rf.mcp-base.overflow/default-max-tokens)))
    (testing "an over-budget-BODY single-key overflow marker is NOT a marker"
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (pr-str (array-map rf.mcp-base.vocab/overflow-key {:limit :reached :blob over-budget}))))
          "an over-default-cap overflow BODY must be capped, not skipped")
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (pr-str (array-map rf.mcp-base.vocab/cache-hit-key {:tool "x" :blob over-budget}))))
          "the same hole for cache-hit"))
    (testing "both print forms of the over-budget marker are rejected (host-agnostic)"
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (binding [*print-namespace-maps* false]
                      (pr-str (array-map rf.mcp-base.vocab/overflow-key {:blob over-budget}))))))
      (is (false? (rf.mcp-base.envelope/marker-text?
                    (binding [*print-namespace-maps* true]
                      (pr-str (array-map rf.mcp-base.vocab/overflow-key {:blob over-budget})))))))))
