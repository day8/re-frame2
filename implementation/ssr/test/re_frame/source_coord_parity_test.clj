(ns re-frame.source-coord-parity-test
  "Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) and Spec
  011 §Source-coord annotation under SSR: the JVM-side SSR emitter's
  `format-view-source-coord` (in `re-frame.ssr`) and the CLJS-side
  Reagent adapter's `format-source-coord` (in `re-frame.views`) MUST
  produce byte-identical attribute values for the same input — same
  id, same captured `:line` / `:column`. Pair tools that consume the
  `data-rf2-source-coord` attribute parse the same shape regardless
  of whether the HTML came from server-side rendering or client-side
  Reagent — divergent formats would silently break the source-mapping
  contract.

  The parser fix in rf2-7g2q's pair2 work assumed both sides produce
  the same `<ns>:<sym>:<line>:<col>` string for the same registration.
  No test exercises both paths against a single fixture to confirm.
  This file pins parity from the JVM side (rf2-d4v7 sub-gap 3 /
  rf2-o423 audit); the CLJS-side counterpart lives at
  `implementation/reagent/test/re_frame/source_coord_parity_cljs_test.cljs`
  and pins the same canonical literal against `format-source-coord`.

  Strategy: each helper is `defn-` (private), but Clojure exposes
  private vars via `#'ns/sym`. This JVM test exercises
  `re-frame.ssr/format-view-source-coord` against fixture inputs and
  asserts it produces a single canonical literal. The companion CLJS
  test exercises `re-frame.views/format-source-coord` against the
  SAME fixture and asserts the SAME canonical literal. If either
  helper drifts from the canonical shape, the corresponding host's
  test fails. The literal IS the byte-comparison point — both sides
  pin it independently."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr]))

;; ---- the canonical attribute-value shape (shared spec) -------------------
;;
;; Per Spec 006 §Source-coord annotation: `<ns>:<sym>:<line>:<col>`.
;; <ns> is the registry id keyword's namespace; <sym> is its name;
;; <line> / <col> come from `(meta &form)` at reg-view macro-expansion
;; time. The fixture below is the same "stamped meta" shape both
;; helpers consume; the expected string is the canonical literal.

(def fixture-id :rf.parity-test/sample-view)

(def fixture-meta {:ns         'rf.parity-test
                   :line       42
                   :column     7
                   :file       "rf/parity_test.cljs"
                   ;; :handler-id is the slot's id key in the registrar
                   ;; — neither helper consumes it, but the audit's
                   ;; bead lists it among the stamped-meta keys so we
                   ;; carry it for shape-fidelity.
                   :handler-id fixture-id})

;; The byte-string both helpers MUST produce for the fixture above.
;; <ns>=rf.parity-test, <sym>=sample-view, <line>=42, <col>=7.
(def expected-attr "rf.parity-test:sample-view:42:7")

;; The degraded-shape canonical literal — a programmatic registration
;; that bypassed the macro path (no :line / :column captured) but
;; still carried :ns. Per Spec 006 §Source-coord annotation: 'A
;; registration that bypassed the macro path … still annotates with
;; <ns>:<sym>:?:? — degrading gracefully so pair tools can still
;; resolve <ns>/<sym> via the registrar's :rf/id lookup.'
(def fixture-meta-no-line-no-col
  {:ns         'rf.parity-test
   :file       "rf/parity_test.cljs"
   ;; :line and :column intentionally absent.
   :handler-id fixture-id})

(def expected-attr-no-line-no-col
  "rf.parity-test:sample-view:?:?")

;; ---- JVM SSR side: format-view-source-coord pins the canonical literal ---

(deftest ssr-format-view-source-coord-byte-identical-to-canonical
  (testing "JVM SSR `format-view-source-coord` consumes the fixture
            (id + line + column + file) and produces the canonical
            <ns>:<sym>:<line>:<col> string — bytes match the literal
            the CLJS-side companion test pins. The literal IS the
            cross-host byte-comparison point."
    (let [ssr-format #'re-frame.ssr/format-view-source-coord
          ssr-output (ssr-format fixture-id fixture-meta)]
      (is (= expected-attr ssr-output)
          (str "JVM SSR `format-view-source-coord` MUST produce the "
               "canonical <ns>:<sym>:<line>:<col> shape. Expected: "
               (pr-str expected-attr) " — got: " (pr-str ssr-output))))))

;; ---- JVM SSR side: degraded shape (no line / col) pins the canonical -----

(deftest ssr-format-view-source-coord-degraded-shape-byte-identical
  (testing "When :line / :column are absent (programmatic reg-view*
            with :ns stamped from a sibling macro registration in the
            same compilation unit), the SSR helper degrades to
            <ns>:<sym>:?:? — byte-identical to the CLJS-side helper's
            degraded shape. Per Spec 006 §Source-coord annotation."
    (let [ssr-format #'re-frame.ssr/format-view-source-coord
          ssr-output (ssr-format fixture-id fixture-meta-no-line-no-col)]
      (is (= expected-attr-no-line-no-col ssr-output)
          (str "SSR degraded shape: expected "
               (pr-str expected-attr-no-line-no-col)
               " — got: " (pr-str ssr-output))))))

;; ---- end-to-end byte parity: SSR-rendered HTML carries the canonical ----

(deftest ssr-rendered-html-attribute-byte-identical-to-canonical
  (testing "The SSR-emitted HTML for a fixture-shape registered view
            carries the canonical attribute value — verifying parity
            extends through the full render path, not just the helper.

            Note: this test cannot use `reg-view` (the macro), because
            macro-captured :line / :column are call-site-dependent.
            We use `reg-view*` and stamp the slot meta directly — the
            registry slot has the same shape it would have under the
            macro path."
    (require '[re-frame.registrar :as registrar]
             '[re-frame.ssr      :as ssr])
    (let [registrar         (resolve 're-frame.registrar/register!)
          ssr-render        (resolve 're-frame.ssr/render-to-string)
          ;; Programmatically register with the fixture meta — same
          ;; slot shape the reg-view macro would produce, but with
          ;; control over the :line / :column values so the canonical
          ;; literal is reachable.
          _ (registrar :view fixture-id
                       (assoc fixture-meta
                              :handler-fn (fn [] [:p "body"])))
          html (ssr-render [fixture-id] {})]
      (is (.contains html (str "data-rf2-source-coord=\""
                               expected-attr
                               "\""))
          (str "SSR-emitted HTML must carry the canonical attribute "
               "value `" expected-attr "`; got: " (pr-str html))))))
