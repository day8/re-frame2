(ns re-frame.schemas.digest-parity-test
  "JVM side of the app-schemas-digest cross-runtime byte-identity
  parity smoke.

  Spec 010 §Digest algorithm pins the digest as byte-identical between
  CLJS and JVM runtimes: `'cross-runtime reproducible — a CLJS server
  and a CLJS client running the same schema set produce the same
  digest, byte-for-byte'`. Pinning only the empty-set case
  (`sha256:e3b0c44298fc1c14`) would let a multi-schema regression that
  flips the JVM digest by one byte while leaving the empty set untouched
  (e.g. a sort-order bug in `canonicalise-schema-form`) ship silently, so
  the corpus pins multi-schema vectors too.

  Pattern mirrors `re-frame.source-coord-parity-test` —
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
            [re-frame.schemas.digest-parity-fixtures :as rf.schemas.digest-parity-fixtures]
            [re-frame.schemas.validator :as rf.schemas.validator]))

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
    (doseq [{:keys [label input expected rationale]} rf.schemas.digest-parity-fixtures/all-fixtures]
      (let [actual (rf.schemas.digest-parity-fixtures/compute-digest input)]
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
    (doseq [{:keys [label input-a input-b rationale]} rf.schemas.digest-parity-fixtures/invariant-pairs]
      (let [da (rf.schemas.digest-parity-fixtures/compute-digest input-a)
            db (rf.schemas.digest-parity-fixtures/compute-digest input-b)]
        (is (= da db)
            (str "JVM invariant pair " (pr-str label) " — "
                 rationale
                 " — input-a → " (pr-str da)
                 ", input-b → " (pr-str db)))))))

;; ---- host-divergent printer cases -----------------------------------------
;;
;; The `whole-number-double` fixture rides in `all-fixtures` above, so
;; its cross-host literal is already asserted by
;; `jvm-digest-matches-canonical-literal`. The JVM is the side that can
;; spell the value as a genuine double, so it also pins that the double and
;; integer spellings digest identically.

(deftest jvm-whole-number-double-agrees-with-its-integer-spelling
  (testing "`{:min 1.0}` and `{:min 1}` are indistinguishable
            on CLJS, so the only cross-runtime-reproducible digest is one
            that treats them as the same schema. Pinning the equality on
            the JVM is what pins the normalisation direction: normalising
            towards the JVM's `1.0` spelling instead would keep the
            hosts divergent, because CLJS cannot print a `.0` suffix for a
            value it does not distinguish from an integer."
    (is (= (rf.schemas.digest-parity-fixtures/compute-digest {[:n] [:int {:min 1.0 :max 10.0}]})
           (rf.schemas.digest-parity-fixtures/compute-digest {[:n] [:int {:min 1 :max 10}]}))
        "whole-number double and integer spellings must digest identically")))

(deftest jvm-fn-token-is-the-class-name
  (testing "the JVM token is the function's class name, which
            is fixed by the compiled artefact rather than by the process.
            Pinning the exact bytes here is the strongest available
            statement that no address survives; the CLJS side cannot pin
            its counterpart, because `:advanced` munges the name, and that
            asymmetry is recorded in Spec 010 §Digest
            algorithm."
    (is (= "[:map [:n \"#fn[clojure.core$pos_int_QMARK_]\"]]"
           (rf.schemas.validator/run-printer rf.schemas.digest-parity-fixtures/fn-bearing-schema)))))

;; ---- ambient printer limits -----------------------------------------------

(deftest jvm-printer-limits-never-reach-digest-bytes
  (testing "a bounded *print-length* / *print-level* in the
            calling context changes neither the bytes and digests computed
            under it nor what the memo serves once the binding ends"
    (let [{:keys [baseline results]} (rf.schemas.digest-parity-fixtures/printer-limit-observations)]
      (is (apply distinct? (:digests baseline))
          "the unbounded baseline keeps every schema form distinct")
      (doseq [{:keys [label inside after]} results]
        (is (= baseline inside)
            (str (pr-str label) " — bytes and digests inside the binding"))
        (is (= baseline after)
            (str (pr-str label) " — memoised bytes and digests read after it"))))))
