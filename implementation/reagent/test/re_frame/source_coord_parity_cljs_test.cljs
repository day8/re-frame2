(ns re-frame.source-coord-parity-cljs-test
  "Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) and Spec
  011 §Source-coord annotation under SSR: the CLJS-side Reagent
  adapter's `format-source-coord` (in `re-frame.views`) and the JVM-
  side SSR emitter's `format-view-source-coord` (in `re-frame.ssr`)
  MUST produce byte-identical attribute values for the same input —
  same id, same captured `:line` / `:column`. Pair tools that consume
  the `data-rf2-source-coord` attribute parse the same shape regardless
  of whether the HTML came from server-side rendering or client-side
  Reagent — divergent formats would silently break the source-mapping
  contract.

  The parser fix in rf2-7g2q's pair2 work assumed both sides produce
  the same `<ns>:<sym>:<line>:<col>` string for the same registration.
  No test exercises both paths against a single fixture to confirm.
  This file pins parity from the CLJS side (rf2-d4v7 sub-gap 3 /
  rf2-o423 audit); the JVM-side counterpart lives at
  `implementation/ssr/test/re_frame/source_coord_parity_test.clj`
  and pins the same canonical literal against
  `format-view-source-coord`.

  Strategy: each helper is `defn-` (private), but the var is
  reachable via `#'ns/sym`. This CLJS test exercises
  `re-frame.views/format-source-coord` against fixture inputs and
  asserts it produces a single canonical literal. The companion JVM
  test exercises `re-frame.ssr/format-view-source-coord` against the
  SAME fixture and asserts the SAME canonical literal. If either
  helper drifts from the canonical shape, the corresponding host's
  test fails. The literal IS the byte-comparison point — both sides
  pin it independently."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.views]))

;; ---- the canonical attribute-value shape (shared spec) -------------------
;;
;; These three values mirror the JVM-side parity test exactly. The
;; literal `expected-attr` IS the cross-host byte-comparison point —
;; if either helper diverges from this shape, the corresponding host's
;; test fails.

(def fixture-id :rf.parity-test/sample-view)

(def fixture-meta {:ns         'rf.parity-test
                   :line       42
                   :column     7
                   :file       "rf/parity_test.cljs"
                   :handler-id fixture-id})

;; Canonical: <ns>=rf.parity-test, <sym>=sample-view, <line>=42, <col>=7.
(def expected-attr "rf.parity-test:sample-view:42:7")

;; Degraded canonical: programmatic registration with no macro coords.
(def fixture-meta-no-line-no-col
  {:ns         'rf.parity-test
   :file       "rf/parity_test.cljs"
   :handler-id fixture-id})

(def expected-attr-no-line-no-col
  "rf.parity-test:sample-view:?:?")

;; ---- CLJS side: format-source-coord pins the canonical literal -----------

(deftest cljs-format-source-coord-byte-identical-to-canonical
  (testing "CLJS `format-source-coord` consumes the fixture
            (id + line + column + file) and produces the canonical
            <ns>:<sym>:<line>:<col> string — bytes match the literal
            the JVM-side companion test pins. The literal IS the
            cross-host byte-comparison point."
    (let [cljs-format #'re-frame.views/format-source-coord
          cljs-output (cljs-format fixture-id fixture-meta)]
      (is (= expected-attr cljs-output)
          (str "CLJS `format-source-coord` MUST produce the canonical "
               "<ns>:<sym>:<line>:<col> shape. Expected: "
               (pr-str expected-attr) " — got: " (pr-str cljs-output))))))

;; ---- CLJS side: degraded shape (no line / col) pins the canonical -------

(deftest cljs-format-source-coord-degraded-shape-byte-identical
  (testing "When :line / :column are absent (programmatic reg-view*
            without macro coords), the CLJS helper degrades to
            <ns>:<sym>:?:? — byte-identical to the SSR-side helper's
            degraded shape. Per Spec 006 §Source-coord annotation:
            'A registration that bypassed the macro path … still
            annotates with <ns>:<sym>:?:? — degrading gracefully so
            pair tools can still resolve <ns>/<sym> via the
            registrar's :rf/id lookup.'"
    (let [cljs-format #'re-frame.views/format-source-coord
          cljs-output (cljs-format fixture-id fixture-meta-no-line-no-col)]
      (is (= expected-attr-no-line-no-col cljs-output)
          (str "CLJS degraded shape: expected "
               (pr-str expected-attr-no-line-no-col)
               " — got: " (pr-str cljs-output))))))
