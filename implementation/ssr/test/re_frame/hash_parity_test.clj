(ns re-frame.hash-parity-test
  "JVM side of the render-tree-hash cross-runtime byte-identity parity
  smoke (rf2-1q9de).

  Spec 011 §Hydration-mismatch detection pins the hash as byte-identical
  between CLJS and JVM runtimes: the server hashes the render-tree at
  SSR time and the client recomputes the hash on first render — a
  mismatch produces `:rf.ssr/hydration-mismatch`. Before this file the
  only JVM↔CLJS hash literals pinned anywhere were one ASCII smoke
  (`9d7457ef` for `[:div {:class \"x\"} [:p \"hi\"]]`) and one
  bare-string non-ASCII pin (`a82b5049` for `\"café\"`) at
  `re-frame.hash-check-cljs-test` (rf2-t7ktb). The audit at rf2-asmj1
  called this `the under-appreciated win` and rf2-1q9de extends to a
  representative corpus spanning empty containers, scalars, hiccup
  trees of growing depth, namespaced-keyword keys/values, multi-byte
  UTF-8 (Latin-1 supplement, Cyrillic, CJK), UTF-16 surrogate-pair
  codepoints, set ordering, list-vs-vector branching, and a 20-child
  tree exercising the FNV multiply-accumulate loop.

  Pattern mirrors `re-frame.schemas.digest-parity-test` (rf2-xssfv) —
  both runtimes consume the SAME fixture map (loaded from a shared
  `.cljc` fixtures namespace) and pin the SAME canonical literal. The
  literal IS the cross-host byte-comparison point. The companion CLJS
  test lives at `re-frame.ssr.hash-parity-cljs-test` and pins the same
  literals against the same fixtures.

  The hash-stability and nil-pruning equivalence rules are already
  pinned in `re-frame.ssr-hash-test` (rf2-6djjl); this namespace focuses
  on byte-identity literals, not structural equivalence."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.hash :as h]
            [re-frame.ssr.hash-parity-fixtures :as fixtures]))

;; ---- pinned-literal vectors -----------------------------------------------
;;
;; Each fixture in `fixtures/all-fixtures` carries a canonical literal —
;; the 8-character hex form the hash MUST produce. Both runtimes pin
;; the same literal; if either drifts, that runtime's test fails on
;; the specific fixture.

(deftest jvm-render-tree-hash-matches-canonical-literal
  (testing "Per Spec 011 §Hydration-mismatch detection — every canonical
            fixture hashes to its pinned 8-hex literal under the JVM
            pipeline. Byte-identity with the CLJS-side literal locks
            the cross-runtime invariant."
    (doseq [{:keys [label input expected rationale]} fixtures/all-fixtures]
      (let [actual (h/render-tree-hash input)]
        (is (= expected actual)
            (str "JVM render-tree-hash for fixture " (pr-str label)
                 " — " rationale
                 " — expected " (pr-str expected)
                 ", got " (pr-str actual)
                 " (canonical: " (pr-str (h/canonical-edn input)) ")"))))))

;; ---- nil-pruning equivalence pairs ---------------------------------------
;;
;; Per Spec 011 §Hydration-mismatch detection + rf2-6djjl: nil values
;; in attribute maps and nil children in sequences are pruned. The
;; with-nil and without-nil inputs MUST hash to the SAME pinned literal
;; on both runtimes. The pre-existing `re-frame.ssr-hash-test` (JVM-only)
;; asserts the equivalence but pins no literal; this file pins it.

(deftest jvm-render-tree-hash-prunes-nil-to-canonical-literal
  (testing "Spec 011 + rf2-6djjl — both the with-nil and without-nil
            inputs MUST hash to the pinned literal. A drift in either
            the pruning step OR the FNV byte stream would fail one of
            the two assertions."
    (doseq [{:keys [label input-with-nil input-without-nil expected rationale]}
            fixtures/nil-prune-pairs]
      (let [h-with    (h/render-tree-hash input-with-nil)
            h-without (h/render-tree-hash input-without-nil)]
        (is (= expected h-without)
            (str "JVM no-nil canonical hash for " (pr-str label) " — "
                 rationale " — expected " (pr-str expected)
                 ", got " (pr-str h-without)))
        (is (= expected h-with)
            (str "JVM with-nil hash MUST equal the no-nil canonical for "
                 (pr-str label) " (pruning equivalence) — expected "
                 (pr-str expected) ", got " (pr-str h-with)))))))

;; ---- structural-invariant pairs ------------------------------------------
;;
;; Attribute-map key-order independence — the canonical form sorts
;; keys before emitting. The two inputs MUST hash identically; we
;; don't pin which hash, only that they agree.

(deftest jvm-render-tree-hash-honours-key-order-invariants
  (testing "Spec 011 §Hydration-mismatch detection — attribute maps
            emit in sorted-key order. Fixture pairs MUST produce
            byte-identical hashes under the JVM pipeline."
    (doseq [{:keys [label input-a input-b rationale]} fixtures/equality-pairs]
      (let [ha (h/render-tree-hash input-a)
            hb (h/render-tree-hash input-b)]
        (is (= ha hb)
            (str "JVM key-order pair " (pr-str label) " — " rationale
                 " — input-a → " (pr-str ha)
                 ", input-b → " (pr-str hb)))))))

;; ---- corpus distinctness sanity ------------------------------------------
;;
;; If two fixtures hashed to the same literal, the pinned-literal
;; assertions would still pass (per-fixture) but the corpus would
;; have lost discriminating power. Confirm the literals are pairwise
;; distinct.

(deftest jvm-fixture-literals-pairwise-distinct
  (testing "The pinned-literal corpus is pairwise distinct — no two
            fixtures collide on the 8-hex output. A collision wouldn't
            be wrong (FNV-1a 32-bit doesn't forbid collisions) but it
            would mean the corpus failed to discriminate between the
            render trees, defeating the point of pinning."
    (let [literals (mapv :expected fixtures/all-fixtures)]
      (is (= (count literals) (count (set literals)))
          (str "Fixture literals must be pairwise distinct — got "
               (pr-str literals))))))
