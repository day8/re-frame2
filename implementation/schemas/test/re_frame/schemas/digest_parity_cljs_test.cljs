(ns re-frame.schemas.digest-parity-cljs-test
  "CLJS side of the app-schemas-digest cross-runtime byte-identity
  parity smoke (rf2-xssfv).

  Spec 010 §Digest algorithm pins the digest as byte-identical between
  CLJS and JVM runtimes: `'cross-runtime reproducible — a CLJS server
  and a CLJS client running the same schema set produce the same
  digest, byte-for-byte'`. The empty-set vector was pinned at
  rf2-0z1z; this file extends the corpus and locks the CLJS pipeline
  (`goog.crypt.Sha256` + `goog.crypt/stringToUtf8ByteArray` +
  `byteArrayToHex`) to the same literals the JVM pipeline
  (`java.security.MessageDigest` + `String#getBytes(UTF_8)`) pins.

  Pattern mirrors `re-frame.source-coord-parity-cljs-test` (rf2-1q9de)
  — both runtimes consume the SAME fixture map (loaded from a shared
  `.cljc` fixtures namespace) and pin the SAME canonical literal. The
  literal IS the cross-host byte-comparison point. The companion JVM
  test lives at `re-frame.schemas.digest-parity-test` and pins the
  same literals against the same fixtures."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.schemas.digest-parity-fixtures :as fixtures]))

;; ---- pinned-literal vectors -----------------------------------------------

(deftest cljs-digest-matches-canonical-literal
  (testing "Per Spec 010 §Digest algorithm — every canonical fixture
            hashes to its pinned `\"sha256:\" + 16-hex` literal under
            the CLJS digest pipeline. Byte-identity with the JVM-side
            literal is what locks the cross-runtime invariant."
    (doseq [{:keys [label input expected rationale]} fixtures/all-fixtures]
      (let [actual (fixtures/compute-digest input)]
        (is (= expected actual)
            (str "CLJS digest for fixture " (pr-str label) " — "
                 rationale
                 " — expected " (pr-str expected) ", got " (pr-str actual)))))))

;; ---- equality-invariant pairs --------------------------------------------

(deftest cljs-digest-honours-equality-invariants
  (testing "Spec 010 §Digest algorithm structural invariants — order-
            independence (paths, props) and metadata stripping — every
            fixture pair MUST produce byte-identical digests under
            the CLJS pipeline."
    (doseq [{:keys [label input-a input-b rationale]} fixtures/invariant-pairs]
      (let [da (fixtures/compute-digest input-a)
            db (fixtures/compute-digest input-b)]
        (is (= da db)
            (str "CLJS invariant pair " (pr-str label) " — "
                 rationale
                 " — input-a → " (pr-str da)
                 ", input-b → " (pr-str db)))))))

;; ---- corpus distinctness sanity ------------------------------------------

(deftest cljs-fixture-literals-pairwise-distinct
  (testing "The pinned-literal corpus is pairwise distinct — no two
            fixtures collide on the 16-hex prefix. The CLJS-side check
            is redundant with the JVM-side check (they read the same
            `def`s) but cheap; an accidental edit that introduced a
            duplicate would fail both."
    (let [literals (mapv :expected fixtures/all-fixtures)]
      (is (= (count literals) (count (set literals)))
          (str "Fixture literals must be pairwise distinct — got "
               (pr-str literals))))))
