(ns re-frame.schemas.printer-seam-test
  "Pluggable schema-print companion fn (rf2-wla45).

  Per Spec 010 §Schema digest line 491 — schema digests are computed
  from the schema values as serialised by the registered validator's
  `schema-print` companion fn. This file locks the pluggable surface
  parallel to the rf2-froe `set-schema-validator!` / `set-schema-
  explainer!` tests:

    - The default printer matches the historical Malli-EDN
      canonicaliser (digest values unchanged vs the pre-rf2-wla45
      digest pipeline).
    - `set-schema-printer!` swaps the printer atom; the digest
      pipeline picks up the new bytes on the next call.
    - Map-arity `(set-schema-validator! {:print fn})` swaps the
      printer atomically alongside `:validate` / `:explain`.
    - `set-schema-printer! nil` falls back to the default (the
      digest is never undefined for a present schema set).
    - `reset-schema-validator!` restores the default printer
      alongside the validator/explainer defaults.

  These contracts are what a non-Malli port (a Zod-port, a
  clojure.spec port) needs to be able to plug into the digest
  pipeline without re-implementing it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.validator :as validator]))

(defn- reset [test-fn]
  ;; Per rf2-froe / rf2-wla45 — the validator/explainer/printer atoms
  ;; are framework-wide; restore the defaults around each test so
  ;; sibling tests are not poisoned.
  (schemas/reset-schema-validator!)
  (try (test-fn)
       (finally (schemas/reset-schema-validator!))))

(use-fixtures :each reset)

(deftest default-printer-matches-historical-canonical-form
  (testing "The default printer (`default-edn-print`) produces the pre-
            rf2-wla45 canonical Malli-EDN bytes — sort-by-pr-str map
            keys, metadata stripped, namespaced-map printing off,
            `pr-str` over the canonicalised form. Locks the
            backward-compatible default so existing pinned digest
            literals (rf2-xssfv `digest_parity_fixtures.cljc`) keep
            matching."
    (is (= "[:map [:id :uuid]]"
           (validator/run-printer [:map [:id :uuid]]))
        "vector schema serialises as straightforward pr-str")
    (is (= "[:map {:closed true, :title \"User\"} [:id :uuid]]"
           (validator/run-printer
             [:map (array-map :title "User" :closed true) [:id :uuid]]))
        "map-keys in the props map sort by (compare (pr-str a) (pr-str b))
         — :closed sorts before :title — regardless of insertion order")
    (is (= "[:map [:id :uuid]]"
           (validator/run-printer
             ^{:doc "user-id"} [:map [:id :uuid]]))
        "metadata is stripped (`:doc` does not appear in the printed bytes)")
    (is (= ":int"
           (validator/run-printer :int))
        "primitive keyword schemas pass through pr-str unchanged")))

(deftest set-schema-printer!-swaps-the-printer-atom
  (testing "`set-schema-printer!` reaches the run-printer hot path on
            the next call — the digest pipeline picks up the new bytes
            without a restart."
    (let [marker-printer (fn [_schema] "::SENTINEL::")]
      (schemas/set-schema-printer! marker-printer)
      (is (= "::SENTINEL::" (validator/run-printer [:map [:id :uuid]]))
          "every schema serialises to the sentinel, regardless of shape")
      (is (= "::SENTINEL::" (validator/run-printer :int))
          "primitive keyword schemas route through the registered fn too"))))

(deftest schema-print-swap-flips-the-digest-bytes
  (testing "A printer swap changes the digest for a non-empty schema
            set — the per-schema bytes are fed through the digest
            pipeline so a different printer (returning different
            bytes) MUST produce a different digest. This is the
            cross-port distinction Spec 010 §Locked rules line 491
            describes: 'two ports using *different* schema languages
            produce different digests by construction'."
    (schemas/reg-app-schema [:n] :int)
    (let [default-digest (schemas/app-schemas-digest)]
      (schemas/set-schema-printer! (fn [_schema] "::DIFFERENT::"))
      (let [swapped-digest (schemas/app-schemas-digest)]
        (is (not= default-digest swapped-digest)
            "digest changes once the printer registers different bytes")))
    ;; Restoring the default brings the digest back — the printer
    ;; surface is purely a contract over the serialisation step.
    (schemas/set-schema-printer! nil)
    (is (= "sha256:5d955f1275ab1ae7"
           (schemas/app-schemas-digest))
        "set-schema-printer! nil restores the default — the digest matches
         the rf2-xssfv `single-prim` literal byte-for-byte")))

(deftest set-schema-validator!-map-arity-installs-printer
  (testing "`(set-schema-validator! {:print fn})` swaps the printer
            atom atomically alongside `:validate` / `:explain` — the
            atomic-swap entry point is symmetrical across all three
            fns (rf2-froe + rf2-wla45)."
    (let [marker (fn [_] "::FROM-MAP-ARITY::")]
      (schemas/set-schema-validator! {:print marker})
      (is (= "::FROM-MAP-ARITY::" (validator/run-printer :int))
          "the registered printer reaches the hot path"))))

(deftest set-schema-printer!-nil-falls-back-to-default
  (testing "Passing `nil` to `set-schema-printer!` reinstalls the
            default EDN canonicaliser — the digest is never undefined
            for a present schema set, even when the validator and
            explainer have been nilled out (rf2-wla45 contract)."
    (schemas/set-schema-printer! (fn [_] "::REPLACED::"))
    (is (= "::REPLACED::" (validator/run-printer :int)))
    (schemas/set-schema-printer! nil)
    (is (= ":int" (validator/run-printer :int))
        "nil printer falls back to default-edn-print; not 'no printer'")))

(deftest reset-schema-validator!-restores-default-printer
  (testing "`reset-schema-validator!` restores the framework defaults
            for all three atoms — validator, explainer, AND printer.
            Test-support call sites that previously only had to
            worry about validator/explainer poisoning now also reset
            the printer for free."
    (schemas/set-schema-printer! (fn [_] "::POISONED::"))
    (is (= "::POISONED::" (validator/run-printer :int)))
    (schemas/reset-schema-validator!)
    (is (= ":int" (validator/run-printer :int))
        "reset restores the default EDN canonicaliser")))

(deftest printer-only-affects-per-schema-bytes-not-pipeline-shape
  (testing "The digest pipeline shape (line-sort, SHA-256, '\"sha256:\" +
            16-hex' wire form) is fixed by Spec 010 §Digest algorithm
            and does NOT route through the printer. A custom printer
            that returns a constant still produces a well-formed wire
            form — and the empty-set digest is unaffected because the
            pipeline never invokes the printer (zero entries)."
    (schemas/set-schema-printer! (fn [_] "::CONSTANT::"))
    ;; Empty set — printer never called; the empty-set digest is
    ;; the historical sha256:e3b0c44298fc1c14 (rf2-0z1z).
    (is (= "sha256:e3b0c44298fc1c14"
           (schemas/app-schemas-digest))
        "empty schema set still produces the canonical empty-string SHA")
    (schemas/reg-app-schema [:n] :int)
    (let [d1 (schemas/app-schemas-digest)]
      (is (re-matches #"^sha256:[0-9a-f]{16}$" d1)
          "wire form is still '\"sha256:\" + 16-hex' regardless of printer"))))
