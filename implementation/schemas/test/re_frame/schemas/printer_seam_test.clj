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
    - Bundle setter `(set-schema-fns! {:print fn})` swaps the
      printer atomically alongside `:validate` / `:explain` (rf2-13meg).
    - `set-schema-printer! nil` falls back to the default (the
      digest is never undefined for a present schema set).
    - `reset-schema-validator!` restores the default printer
      alongside the validator/explainer defaults.

  These contracts are what a non-Malli port (a Zod-port, a
  clojure.spec port) needs to be able to plug into the digest
  pipeline without re-implementing it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.validator :as validator]))

(defn- reset [test-fn]
  ;; Per rf2-froe / rf2-wla45 — the validator/explainer/printer atoms
  ;; are framework-wide; restore the defaults around each test so
  ;; sibling tests are not poisoned.
  (schemas/reset-schema-validator!)
  ;; EP-0002 (rf2-5q7um6): the digest-seam tests register schemas via
  ;; reg-app-schema, which is now context-required frame-local. Pin
  ;; :rf/default as the established scope so those ambient registrations
  ;; carry a frame stamp (no :rf/default floor). No `ensure-default-frame!`
  ;; here — these tests install no adapter; `reg-app-schema` only needs the
  ;; carried STAMP (it writes the plain `schemas-by-frame` atom), and the
  ;; schema-derived elision-populate step no-ops when the frame has no live
  ;; runtime-db container.
  (try (binding [frame/*current-frame* :rf/default]
         (test-fn))
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
    (is (= "sha256:e7939756d704eaab"
           (schemas/app-schemas-digest))
        "set-schema-printer! nil restores the default — the digest matches
         the rf2-xssfv `single-prim` literal byte-for-byte (the path key is
         CEDN-1 `canonical-bytes`, not pr-str — rf2-ujmc3u)")))

(deftest set-schema-fns!-installs-printer
  (testing "`(set-schema-fns! {:print fn})` swaps the printer atom
            atomically alongside `:validate` / `:explain` — the bundle
            entry point is symmetrical across all three fns (rf2-froe +
            rf2-wla45 + rf2-13meg)."
    (let [marker (fn [_] "::FROM-BUNDLE::")]
      (schemas/set-schema-fns! {:print marker})
      (is (= "::FROM-BUNDLE::" (validator/run-printer :int))
          "the registered printer reaches the hot path"))))

(deftest set-schema-fns!-nil-print-coerces-to-default
  (testing "rf2-ee38b.6 — `(set-schema-fns! {:print nil})` coerces to
            the default EDN canonicaliser, identically to
            `(set-schema-printer! nil)`. Reconciles the two printer-
            setter paths so `printer-fn` is never nil (the read-site
            guard in `run-printer` was dropped) — the bundle setter's
            'falls back to the default' promise is true at the write
            site (rf2-13meg)."
    ;; Poison first so a no-op would be observable.
    (schemas/set-schema-printer! (fn [_] "::POISONED::"))
    (is (= "::POISONED::" (validator/run-printer :int)))
    (schemas/set-schema-fns! {:print nil})
    (is (some? @validator/printer-fn)
        "printer-fn is never nil after a {:print nil} bundle swap")
    (is (= ":int" (validator/run-printer :int))
        "{:print nil} falls back to default-edn-print, not 'no printer'")))

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

;; ---- set-schema-fns! return contract (rf2-qdtcx2) -------------------------
;;
;; The bundle setter returns the INSTALLED BUNDLE as a map
;; `{:validate … :explain … :print …}` reflecting the live state of all
;; three fns after the call — not just the validator. These tests pin the
;; return value WITHOUT dereferencing the raw `schemas/*` atoms: the return
;; is the public observation seam for what is now installed.

(deftest set-schema-fns!-returns-full-installed-bundle
  (testing "rf2-qdtcx2 — a full `{:validate :explain :print}` bundle call
            returns the installed bundle map carrying exactly the three fns
            supplied. A bundle setter returns its bundle (not just the
            validator); the caller reads the return rather than the atoms."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:explained true})
          p-fn (fn [_] "::RET-PRINTER::")
          ret  (schemas/set-schema-fns! {:validate v-fn :explain e-fn :print p-fn})]
      (is (map? ret) "the return is a bundle map, not a single fn")
      (is (= #{:validate :explain :print} (set (keys ret)))
          "the bundle map always carries all three keys")
      (is (= v-fn (:validate ret)) ":validate in the return is the installed validator")
      (is (= e-fn (:explain ret))  ":explain in the return is the installed explainer")
      (is (= p-fn (:print ret))    ":print in the return is the installed printer")
      ;; The returned printer is the one the hot path now uses — observed
      ;; through the public run-printer seam, not a raw atom deref.
      (is (= "::RET-PRINTER::" (validator/run-printer :int))
          "the returned :print fn is live on the digest hot path"))))

(deftest set-schema-fns!-print-only-returns-whole-bundle-incl-untouched
  (testing "rf2-qdtcx2 — a partial `{:print marker}` call returns the live
            state of ALL THREE fns, including the validator/explainer it did
            NOT touch (which keep their prior registrations). The old
            validator-only return handed a `:print`-only caller a value
            unrelated to what it set; now the return reflects the printer it
            installed AND the untouched fns."
    (let [marker (fn [_] "::PRINT-ONLY::")
          ret    (schemas/set-schema-fns! {:print marker})]
      (is (= #{:validate :explain :print} (set (keys ret)))
          "the bundle map carries all three keys even for a partial update")
      (is (= marker (:print ret))
          "the return's :print is the printer this call installed")
      (is (= "::PRINT-ONLY::" (validator/run-printer :int))
          "the installed printer reaches the hot path")
      ;; Untouched fns keep their prior (default) registrations and appear
      ;; in the return — the reset fixture restored defaults before this test.
      (is (some? (:validate ret)) ":validate is the untouched (default) validator")
      (is (some? (:explain ret))  ":explain is the untouched (default) explainer"))))

(deftest set-schema-fns!-nil-print-returns-non-nil-coerced-printer
  (testing "rf2-qdtcx2 + rf2-ee38b.6 — `{:print nil}` coerces to the default
            EDN canonicaliser, and the RETURNED `:print` reflects that
            coercion: never nil. A caller observing the return sees the
            actual printer that will be hashed, not the literal nil it passed."
    ;; Poison first so a no-op would be observable.
    (schemas/set-schema-printer! (fn [_] "::POISONED::"))
    (is (= "::POISONED::" (validator/run-printer :int)))
    (let [ret (schemas/set-schema-fns! {:print nil})]
      (is (some? (:print ret))
          "the returned :print is the coerced default, never the nil passed")
      ;; The returned printer IS the live default — verified via run-printer
      ;; rather than a raw atom deref.
      (is (= ":int" (validator/run-printer :int))
          "{:print nil} falls back to default-edn-print on the hot path")
      (is (= ":int" ((:print ret) :int))
          "calling the returned :print fn directly yields the default bytes"))))

(deftest single-purpose-setters-keep-single-value-returns
  (testing "rf2-qdtcx2 — only the BUNDLE setter returns the bundle map; the
            single-purpose setters keep their own single-value returns (the
            one fn each installs). The bundle-vs-single distinction is in the
            return shape, not just the name."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:e true})
          p-fn (fn [_] "::P::")]
      (is (= v-fn (schemas/set-schema-validator! v-fn))
          "set-schema-validator! returns the single validator it installed")
      (is (= e-fn (schemas/set-schema-explainer! e-fn))
          "set-schema-explainer! returns the single explainer it installed")
      (is (= p-fn (schemas/set-schema-printer! p-fn))
          "set-schema-printer! returns the single printer it installed"))))
