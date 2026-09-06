(ns re-frame.source-coord-parity-cljs-test
  "Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) + §View
  tagging contract (rf2-01il5): the CLJS-side Reagent adapter's
  `format-source-coord` / `format-view-id` and the JVM-side
  registration-boundary formatters (in
  `re-frame.views.jvm-source-coord-annotation`) MUST produce byte-
  identical attribute VALUES for the same input — same id, same captured
  `:line` / `:column`. Pair tools that consume `data-rf2-source-coord` /
  `data-rf-view` parse the same shape whether the HTML came from server-
  side rendering or client-side Reagent — divergent formats would
  silently break the source-mapping contract.

  rf2-8vi4q moved server-side annotation to the reg-view registration
  boundary and added `data-rf-view` to the SSR side, so both hosts now
  emit BOTH attributes. This file pins BOTH formatters from the CLJS side
  (rf2-d4v7 sub-gap 3 / rf2-o423 audit); the JVM-side counterpart lives at
  `implementation/ssr/test/re_frame/source_coord_parity_test.clj` and pins
  the same canonical literals.

  Strategy: this CLJS test exercises `re-frame.views/format-source-coord`
  and `re-frame.views.source-coord-annotation/format-view-id` against
  fixture inputs and asserts single canonical literals. The companion JVM
  test exercises the JVM formatters against the SAME fixtures and asserts
  the SAME literals. If either host's formatter drifts, its test fails.
  The literals ARE the byte-comparison point — both sides pin independently."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.views]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]))

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

;; `data-rf-view` is `(str id)` — a printed keyword, leading colon included.
(def expected-view-id ":rf.parity-test/sample-view")

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

;; ---- CLJS side: format-view-id pins the canonical data-rf-view value -----

(deftest cljs-format-view-id-byte-identical-to-canonical
  (testing "rf2-8vi4q — CLJS `format-view-id` produces `(str id)`, the same
            `data-rf-view` value the JVM host now stamps. Both hosts emit
            this attribute; before rf2-8vi4q only the client did."
    (is (= expected-view-id (rf.views.source-coord-annotation/format-view-id fixture-id))
        (str "CLJS `format-view-id` MUST produce `(str id)`. Expected: "
             (pr-str expected-view-id) " — got: "
             (pr-str (rf.views.source-coord-annotation/format-view-id fixture-id))))))

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

;; ---- convergence: source-coords is the single cross-host owner (rf2-5q0jv) -
;;
;; Before rf2-5q0jv the CLJS formatters (in `re-frame.adapter.context`) and the
;; JVM formatters (in `re-frame.views.jvm-source-coord-annotation`) were two
;; hand-kept copies; a canonical-literal test could only catch a drift AFTER it
;; shipped. They now alias one `.cljc` implementation in `re-frame.source-coords`,
;; so a CLJS copy can no longer drift from the JVM host. Prove it: the neutral
;; owner emits the canonical literals directly, and the adapter.context vars ARE
;; that same fn object (`identical?` on fn references, not a keyword literal).

(deftest neutral-owner-is-the-single-cljs-formatter-implementation
  (testing "rf2-5q0jv — the CLJS adapter.context vars (and the re-frame.views /
            spine re-exports built on them) alias the one cross-host
            implementation in re-frame.source-coords; the neutral owner emits
            the canonical literals and the adapter.context var is the identical
            fn object."
    (is (= expected-attr
           (rf.source-coords/format-source-coord fixture-id fixture-meta))
        "neutral owner must emit the canonical data-rf2-source-coord literal")
    (is (= expected-view-id
           (rf.source-coords/format-view-id fixture-id))
        "neutral owner must emit the canonical data-rf-view literal")
    (is (identical? rf.source-coords/format-source-coord
                    rf.adapter.context/format-source-coord)
        "CLJS format-source-coord must be an alias of the neutral owner")
    (is (identical? rf.source-coords/format-view-id
                    rf.adapter.context/format-view-id)
        "CLJS format-view-id must be an alias of the neutral owner")))
