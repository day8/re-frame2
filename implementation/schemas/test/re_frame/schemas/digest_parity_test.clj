(ns re-frame.schemas.digest-parity-test
  "JVM side of the app-schemas-digest cross-runtime byte-identity
  parity smoke (rf2-xssfv).

  Spec 010 §Digest algorithm pins the digest as byte-identical between
  CLJS and JVM runtimes: `'cross-runtime reproducible — a CLJS server
  and a CLJS client running the same schema set produce the same
  digest, byte-for-byte'`. Before this file the only digest vector
  pinned to a literal was the empty-set case (`sha256:e3b0c44298fc1c14`,
  rf2-0z1z); a multi-schema regression that flipped the JVM digest by
  one byte while leaving the empty-set untouched (e.g. a sort-order
  bug in `canonicalise-schema-form`) would have shipped silently. The
  audit at rf2-x8x4p (TE6 / S7) called this out and rf2-xssfv pins the
  vectors.

  Pattern mirrors `re-frame.source-coord-parity-test` (rf2-1q9de) —
  both runtimes consume the SAME fixture map (loaded from a shared
  `.cljc` fixtures namespace) and pin the SAME canonical literal. The
  literal IS the cross-host byte-comparison point. The companion CLJS
  test lives at `re-frame.schemas.digest-parity-cljs-test` and pins
  the same literals against the same fixtures.

  Strategy: `compute-digest` is private; both runtimes reach it via
  `#'re-frame.schemas.digest/compute-digest`. The shared fixtures
  namespace dereferences the var once and exposes it as
  `compute-digest`, so per-fixture assertions are runtime-agnostic."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas.digest-parity-fixtures :as fixtures]))

;; ---- pinned-literal vectors -----------------------------------------------
;;
;; Each fixture in `fixtures/all-fixtures` carries a canonical literal
;; — the `\"sha256:\" + 16-hex` wire form the digest MUST produce. Both
;; runtimes pin the same literal; if either runtime drifts, that
;; runtime's test fails on the specific fixture.

(deftest jvm-digest-matches-canonical-literal
  (testing "Per Spec 010 §Digest algorithm — every canonical fixture
            hashes to its pinned `\"sha256:\" + 16-hex` literal under
            the JVM digest pipeline. Byte-identity with the CLJS-side
            literal is what locks the cross-runtime invariant."
    (doseq [{:keys [label input expected rationale]} fixtures/all-fixtures]
      (let [actual (fixtures/compute-digest input)]
        (is (= expected actual)
            (str "JVM digest for fixture " (pr-str label) " — "
                 rationale
                 " — expected " (pr-str expected) ", got " (pr-str actual)))))))

;; ---- equality-invariant pairs --------------------------------------------
;;
;; Order independence (paths, props) and metadata stripping are
;; structural invariants from Spec 010 §Digest algorithm. The two
;; inputs MUST hash to the same digest; we don't pin which digest, only
;; that they agree. The CLJS counterpart pins the same invariants.

(deftest jvm-digest-honours-equality-invariants
  (testing "Spec 010 §Digest algorithm structural invariants — order-
            independence (paths, props) and metadata stripping — every
            fixture pair MUST produce byte-identical digests under
            the JVM pipeline."
    (doseq [{:keys [label input-a input-b rationale]} fixtures/invariant-pairs]
      (let [da (fixtures/compute-digest input-a)
            db (fixtures/compute-digest input-b)]
        (is (= da db)
            (str "JVM invariant pair " (pr-str label) " — "
                 rationale
                 " — input-a → " (pr-str da)
                 ", input-b → " (pr-str db)))))))

;; ---- corpus distinctness sanity ------------------------------------------
;;
;; If two of the canonical fixtures hashed to the same digest, the
;; pinned literals would still match (per-fixture) but the corpus
;; would have lost discriminating power. Confirm the literals are
;; pairwise distinct.

(deftest jvm-fixture-literals-pairwise-distinct
  (testing "The pinned-literal corpus is pairwise distinct — no two
            fixtures collide on the 16-hex prefix. A collision here
            would not be wrong (the spec doesn't forbid hash
            collisions) but it would mean the corpus failed to
            discriminate between the schema sets, defeating the
            point of pinning."
    (let [literals (mapv :expected fixtures/all-fixtures)]
      (is (= (count literals) (count (set literals)))
          (str "Fixture literals must be pairwise distinct — got "
               (pr-str literals))))))
