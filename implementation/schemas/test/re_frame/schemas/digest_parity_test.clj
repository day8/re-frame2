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
    (let [literals (mapv :expected rf.schemas.digest-parity-fixtures/all-fixtures)]
      (is (= (count literals) (count (set literals)))
          (str "Fixture literals must be pairwise distinct — got "
               (pr-str literals))))))

;; ---- UTF-8 byte-order line sort (Spec 010 step 4) ------------------------
;;
;; Spec 010 §Digest algorithm step 4 mandates the lines be sorted
;; "lexicographically as byte sequences (UTF-8) … identical across
;; hosts." Host-native string compare (JVM String.compareTo / JS string
;; compare) is UTF-16 code-unit order, which diverges from UTF-8 byte
;; order for supplementary-plane (> U+FFFF) characters. For ASCII the
;; two coincide (so every pinned fixture above sorts the same either way). These
;; pins lock the comparator to the normative UTF-8 byte order so a
;; non-CLJS/JVM port that byte-sorts agrees with the reference.

(def ^:private compare-utf8-bytes
  #'re-frame.schemas.digest/compare-utf8-bytes)

(deftest utf8-byte-sort-diverges-from-utf16-on-supplementary-plane
  (testing "the comparator orders by UTF-8 bytes, not
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
          "host-native String.compareTo is UTF-16 order (the order the comparator must not use)"))))

(deftest utf8-byte-sort-agrees-with-string-compare-on-ascii
  (testing "for ASCII the UTF-8 byte order and native
            string order coincide, so the pinned ASCII fixtures above
            sort identically under either comparator."
    (doseq [[a b] [["[:a]" "[:b]"] ["[:auth]" "[:user]"]
                   ["abc" "abd"] ["[:n]" "[:n]"]]]
      (is (= (Integer/signum (compare-utf8-bytes a b))
             (Integer/signum (compare a b)))
          (str "ASCII order coincides for " (pr-str [a b]))))))

;; ---- host-divergent printer cases -----------------------------------------
;;
;; The `whole-number-double` fixture rides in `all-fixtures` above, so
;; its cross-host literal is already asserted by
;; `jvm-digest-matches-canonical-literal`. What that assertion cannot
;; check is that the fixture still CARRIES a double — on the JVM it does,
;; and this is the side that can tell, so the precondition lives here.

(deftest jvm-whole-number-double-fixture-really-carries-a-double
  (testing "precondition — the `whole-number-double` fixture's
            `:min` prop is genuinely a floating-point value on this host.
            Without this, editing the fixture's `1.0` to `1` would leave
            `jvm-digest-matches-canonical-literal` green while exercising
            none of the divergence the fixture exists for: the JVM would
            print `1` either way. CLJS cannot make this assertion — it
            has one numeric type and the reader has already collapsed the
            literal — which is why the guard is JVM-side only."
    (let [m (rf.schemas.digest-parity-fixtures/whole-number-double-min)]
      (is (float? m)
          (str "the fixture's :min must still be a double, got "
               (pr-str m) " of type " (pr-str (type m))))
      (is (== 1 m)
          "and must still denote the whole number the pinned literal was taken over"))))

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

(deftest jvm-fn-bearing-schema-digest-is-process-stable
  (testing "a schema carrying a bare predicate must serialise
            to a NAME-derived token, not to the host's `#object[… 0x… ]`
            print. That address is `System/identityHashCode`, fresh in
            every process, so printed raw a JVM server and a client
            would disagree on every hydrate and the SSR handshake would report
            `:rf.ssr/schema-digest-mismatch` — 'Deploy drift' — against
            byte-identical code."
    (is (rf.schemas.digest-parity-fixtures/fn-bearing-carries-fn?)
        "precondition: the fixture schema must still carry a function")
    (let [{:keys [bytes other-predicate-bytes address-free? object-print-free?
                  carries-fn-token? stable-across-reads? discriminates-predicates?]}
          (rf.schemas.digest-parity-fixtures/fn-bearing-observations)]
      (is address-free?
          (str "no per-process identity hash may ride in the digest bytes — got " (pr-str bytes)))
      (is object-print-free?
          (str "the canonicaliser, not `pr-str`, must produce these bytes — got " (pr-str bytes)))
      (is carries-fn-token?
          (str "a function must canonicalise to its `#fn[…]` token — got " (pr-str bytes)))
      (is stable-across-reads?
          "the bytes must not move between serialisations of the same schema")
      (is discriminates-predicates?
          (str "two different predicates must still digest differently — "
               (pr-str bytes) " vs " (pr-str other-predicate-bytes))))))

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
