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
            [re-frame.schemas.digest]
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

;; ---- UTF-8 byte-order line sort (rf2-ee38b.6, Spec 010 step 4) ------------
;;
;; Spec 010 §Digest algorithm step 4 mandates the lines be sorted
;; "lexicographically as byte sequences (UTF-8) … identical across
;; hosts." Host-native string compare (JVM String.compareTo / JS string
;; compare) is UTF-16 code-unit order, which diverges from UTF-8 byte
;; order for supplementary-plane (> U+FFFF) characters. For ASCII the
;; two coincide (so every pinned fixture above is unaffected). These
;; pins lock the comparator to the normative UTF-8 byte order so a
;; non-CLJS/JVM port that byte-sorts agrees with the reference.

(def ^:private compare-utf8-bytes
  #'re-frame.schemas.digest/compare-utf8-bytes)

(deftest utf8-byte-sort-diverges-from-utf16-on-supplementary-plane
  (testing "rf2-ee38b.6 — the comparator orders by UTF-8 bytes, not
            UTF-16 code units. U+1F600 (UTF-8 F0 9F 98 80; UTF-16
            surrogate D83D DE00) vs U+FFFD (replacement, UTF-8 EF BF BD;
            UTF-16 FFFD): UTF-16 order says U+1F600 < U+FFFD (D83D <
            FFFD) but UTF-8 byte order says U+1F600 > U+FFFD (F0 > EF).
            The comparator MUST follow UTF-8. Code points are built via
            `Character/toChars` to avoid source-encoding fragility."
    (let [astral (String. (Character/toChars 0x1F600))  ;; supplementary plane
          bmp    (String. (Character/toChars 0xFFFD))]   ;; BMP replacement char
      (is (pos? (compare-utf8-bytes astral bmp))
          "UTF-8 byte order: astral (F0…) sorts AFTER bmp (EF…)")
      (is (neg? (compare-utf8-bytes bmp astral)))
      ;; Sanity: host-native compare gives the OPPOSITE (UTF-16) answer,
      ;; confirming the comparator is genuinely doing byte-order work.
      (is (neg? (compare astral bmp))
          "host-native String.compareTo is UTF-16 order (the bug we fixed)"))))

(deftest utf8-byte-sort-agrees-with-string-compare-on-ascii
  (testing "rf2-ee38b.6 — for ASCII the UTF-8 byte order and native
            string order coincide, so the pinned ASCII fixtures above
            are byte-for-byte unaffected by the comparator switch."
    (doseq [[a b] [["[:a]" "[:b]"] ["[:auth]" "[:user]"]
                   ["abc" "abd"] ["[:n]" "[:n]"]]]
      (is (= (Integer/signum (compare-utf8-bytes a b))
             (Integer/signum (compare a b)))
          (str "ASCII order coincides for " (pr-str [a b]))))))
