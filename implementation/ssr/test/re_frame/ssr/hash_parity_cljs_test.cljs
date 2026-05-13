(ns re-frame.ssr.hash-parity-cljs-test
  "CLJS side of the render-tree-hash cross-runtime byte-identity parity
  smoke (rf2-1q9de).

  Spec 011 §Hydration-mismatch detection pins the hash as byte-identical
  between CLJS and JVM runtimes. The CLJS pipeline:
  `goog.crypt`-less — uses a plain `TextEncoder` (UTF-8) + per-byte
  `Math.imul` FNV-1a loop. The JVM pipeline:
  `String.getBytes(StandardCharsets/UTF_8)` + long-multiply-then-mask
  FNV-1a loop. Both MUST emit the same 8-hex string for the same input
  render tree; this file locks the CLJS side to the literals the JVM
  side pins.

  Pattern mirrors `re-frame.schemas.digest-parity-cljs-test` (rf2-xssfv)
  — both runtimes consume the SAME fixture map (loaded from a shared
  `.cljc` fixtures namespace) and pin the SAME canonical literal. The
  literal IS the cross-host byte-comparison point. The companion JVM
  test lives at `re-frame.hash-parity-test` and pins the same literals
  against the same fixtures.

  Note on namespace: the file lives at `implementation/ssr/test/re_frame/ssr/`
  and the ns suffix is `-cljs-test` (not `cljs-test`) so it matches
  both the shadow-cljs `:node-test` build's `cljs-test$` regex and the
  `:browser-test` build's `-cljs-test$` regex. The hash-pipeline code
  is platform-neutral — no DOM, no React — so the node-test gate is
  sufficient."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ssr.hash :as h]
            [re-frame.ssr.hash-parity-fixtures :as fixtures]))

;; ---- pinned-literal vectors -----------------------------------------------

(deftest cljs-render-tree-hash-matches-canonical-literal
  (testing "Per Spec 011 §Hydration-mismatch detection — every canonical
            fixture hashes to its pinned 8-hex literal under the CLJS
            pipeline. Byte-identity with the JVM-side literal locks
            the cross-runtime invariant."
    (doseq [{:keys [label input expected rationale]} fixtures/all-fixtures]
      (let [actual (h/render-tree-hash input)]
        (is (= expected actual)
            (str "CLJS render-tree-hash for fixture " (pr-str label)
                 " — " rationale
                 " — expected " (pr-str expected)
                 ", got " (pr-str actual)
                 " (canonical: " (pr-str (h/canonical-edn input)) ")"))))))

;; ---- nil-pruning equivalence pairs ---------------------------------------

(deftest cljs-render-tree-hash-prunes-nil-to-canonical-literal
  (testing "Spec 011 + rf2-6djjl — both the with-nil and without-nil
            inputs MUST hash to the pinned literal under the CLJS
            pipeline."
    (doseq [{:keys [label input-with-nil input-without-nil expected rationale]}
            fixtures/nil-prune-pairs]
      (let [h-with    (h/render-tree-hash input-with-nil)
            h-without (h/render-tree-hash input-without-nil)]
        (is (= expected h-without)
            (str "CLJS no-nil canonical hash for " (pr-str label) " — "
                 rationale " — expected " (pr-str expected)
                 ", got " (pr-str h-without)))
        (is (= expected h-with)
            (str "CLJS with-nil hash MUST equal the no-nil canonical for "
                 (pr-str label) " (pruning equivalence) — expected "
                 (pr-str expected) ", got " (pr-str h-with)))))))

;; ---- structural-invariant pairs ------------------------------------------

(deftest cljs-render-tree-hash-honours-key-order-invariants
  (testing "Spec 011 §Hydration-mismatch detection — attribute maps
            emit in sorted-key order. Fixture pairs MUST produce
            byte-identical hashes under the CLJS pipeline."
    (doseq [{:keys [label input-a input-b rationale]} fixtures/equality-pairs]
      (let [ha (h/render-tree-hash input-a)
            hb (h/render-tree-hash input-b)]
        (is (= ha hb)
            (str "CLJS key-order pair " (pr-str label) " — " rationale
                 " — input-a → " (pr-str ha)
                 ", input-b → " (pr-str hb)))))))

;; ---- corpus distinctness sanity ------------------------------------------

(deftest cljs-fixture-literals-pairwise-distinct
  (testing "The pinned-literal corpus is pairwise distinct — the CLJS-side
            check is redundant with the JVM-side check (they read the
            same `def`s) but cheap; an accidental edit that introduced
            a duplicate would fail both."
    (let [literals (mapv :expected fixtures/all-fixtures)]
      (is (= (count literals) (count (set literals)))
          (str "Fixture literals must be pairwise distinct — got "
               (pr-str literals))))))
