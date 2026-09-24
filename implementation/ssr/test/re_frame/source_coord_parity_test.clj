(ns re-frame.source-coord-parity-test
  "Per Spec 006 §Source-coord annotation + §View tagging contract: the
  JVM-side registration-boundary
  annotation (`re-frame.views.jvm-source-coord-annotation`) and the
  CLJS-side Reagent adapter's `format-source-coord` / `format-view-id`
  (in `re-frame.adapter.context`, re-exported via `re-frame.views`) MUST
  produce byte-identical attribute VALUES for the same input — same id,
  same captured `:line` / `:column`. Pair tools that consume
  `data-rf2-source-coord` / `data-rf-view` parse the same shape whether
  the HTML came from server-side rendering or client-side Reagent;
  divergent formats would silently break the source-mapping contract.

  Server-side annotation happens at the reg-view registration boundary,
  not in the emitter, and BOTH attributes are emitted on both hosts. This
  test therefore pins BOTH formatters and,
  at the end, drives the FULL render path through a callable head — the
  shape hydratable pages actually use — to prove both attributes reach the
  server markup.

  Strategy: exercise the JVM formatters against fixed fixtures and assert
  the canonical literals. The companion CLJS test
  (`implementation/adapters/reagent/test/re_frame/source_coord_parity_cljs_test.cljs`)
  exercises the CLJS formatters against the SAME fixtures and asserts the
  SAME literals. The literals ARE the cross-host byte-comparison point —
  if either host's formatter drifts, its test fails.

  ## Posture split

  The FORMATTERS are ordinary pure functions and the neutral-owner aliasing
  is ordinary Var identity: neither is gated, so every deftest above the
  end-to-end one runs unchanged in both postures.

  The end-to-end render is the exception. The attributes only reach server
  markup because `reg-view` installs the annotation wrapper, and it installs
  it only under `interop/debug-enabled?` — read once at namespace-load time,
  so under the real `-Dre-frame.debug=false` gate the markup is bare by
  design. Those three assertions sit inside a
  `(when interop/debug-enabled? …)` arm; alongside them sits the
  posture-independent half — that the callable head renders the registered
  view's own `<p>body</p>` root at all — plus a `when-not` arm pinning the
  exact bare bytes the production gate emits."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.views.jvm-source-coord-annotation :as rf.views.jvm-source-coord-annotation]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- the canonical attribute-value shapes (shared spec) ------------------
;;
;; `data-rf2-source-coord` = `<ns>:<sym>:<line>:<col>` (Spec 006
;; §Source-coord annotation). `data-rf-view` = `(str id)` (Spec 006 §View
;; tagging contract). The fixtures below are the same stamped-meta shape
;; both hosts' formatters consume; the expected strings are the canonical
;; literals the CLJS companion pins independently.

(def fixture-id :rf.parity-test/sample-view)

(def fixture-coords {:ns         'rf.parity-test
                     :line       42
                     :column     7
                     :file       "rf/parity_test.cljs"
                     :handler-id fixture-id})

;; <ns>=rf.parity-test, <sym>=sample-view, <line>=42, <col>=7.
(def expected-source-coord "rf.parity-test:sample-view:42:7")

;; `data-rf-view` is `(str id)` — a printed keyword, leading colon included.
(def expected-view-id ":rf.parity-test/sample-view")

;; Degraded canonical: a programmatic registration that bypassed the macro
;; path (no :line / :column) but still carried :ns. Per Spec 006
;; §Source-coord annotation the source-coord degrades to <ns>:<sym>:?:?.
(def fixture-coords-no-line-no-col
  {:ns         'rf.parity-test
   :file       "rf/parity_test.cljs"
   :handler-id fixture-id})

(def expected-source-coord-no-line-no-col "rf.parity-test:sample-view:?:?")

;; ---- JVM side: the formatters pin the canonical literals -----------------

(deftest jvm-format-source-coord-byte-identical-to-canonical
  (testing "the JVM `format-source-coord` consumes the fixture and produces
            the canonical <ns>:<sym>:<line>:<col> string — bytes match the
            literal the CLJS companion pins."
    (is (= expected-source-coord
           (rf.views.jvm-source-coord-annotation/format-source-coord fixture-id fixture-coords))
        "JVM data-rf2-source-coord value must match the canonical literal")))

(deftest jvm-format-view-id-byte-identical-to-canonical
  (testing "the JVM `format-view-id` produces `(str id)`, the
            same `data-rf-view` value the CLJS host stamps."
    (is (= expected-view-id (rf.views.jvm-source-coord-annotation/format-view-id fixture-id))
        "JVM data-rf-view value must match the canonical literal")))

(deftest jvm-format-source-coord-degraded-shape-byte-identical
  (testing "When :line / :column are absent (programmatic reg-view*), the
            JVM helper degrades to <ns>:<sym>:?:? — byte-identical to the
            CLJS helper's degraded shape. Per Spec 006 §Source-coord
            annotation."
    (is (= expected-source-coord-no-line-no-col
           (rf.views.jvm-source-coord-annotation/format-source-coord fixture-id fixture-coords-no-line-no-col))
        "JVM degraded source-coord must match the canonical degraded literal")))

;; ---- convergence: source-coords is the single cross-host owner ------------
;;
;; The JVM formatters (`re-frame.views.jvm-source-coord-annotation`) and the
;; CLJS formatters (in `re-frame.adapter.context`) alias one `.cljc`
;; implementation in `re-frame.source-coords`, co-located with their inverse
;; parsers, so cross-host divergence is structurally impossible — with two
;; hand-kept copies a canonical-literal test could only catch a drift AFTER it
;; shipped. Prove it: the
;; neutral owner emits the canonical literals directly, and the JVM annotation
;; vars ARE that same fn (an alias, not a re-derivable copy). `identical?` here
;; compares fn-object identity — NOT a keyword literal, so it is not the
;; `.cljc` keyword-interning trap (and these are `.clj` / `.cljs` test files).

(deftest neutral-owner-is-the-single-jvm-formatter-implementation
  (testing "`re-frame.source-coords` owns the one cross-host
            implementation; the JVM annotation vars alias it, so the neutral
            owner emits the canonical literals and the JVM vars are the
            identical fn (not a re-derived copy that could drift)."
    (is (= expected-source-coord
           (rf.source-coords/format-source-coord fixture-id fixture-coords))
        "neutral owner must emit the canonical data-rf2-source-coord literal")
    (is (= expected-view-id
           (rf.source-coords/format-view-id fixture-id))
        "neutral owner must emit the canonical data-rf-view literal")
    (is (identical? rf.source-coords/format-source-coord
                    rf.views.jvm-source-coord-annotation/format-source-coord)
        "JVM format-source-coord must be an alias of the neutral owner")
    (is (identical? rf.source-coords/format-view-id
                    rf.views.jvm-source-coord-annotation/format-view-id)
        "JVM format-view-id must be an alias of the neutral owner")))

;; ---- end-to-end byte parity: SSR-rendered HTML carries BOTH attributes ---
;;
;; A registered view reached through its CALLABLE head — `[(rf/view id) …]`
;; or a Var, the shape isomorphic pages actually compose with — renders WITH
;; both annotations, so the server markup byte-matches the dev client render
;; and hydration adopts.

(deftest ssr-rendered-html-carries-both-annotations-through-callable-head
  (testing "a registered view reached through `(rf/view id)`
            renders with BOTH data-rf2-source-coord AND data-rf-view on its
            root DOM element, and their values are exactly the shared
            formatters' output for the slot's stored coords."
    (rf/reg-view* fixture-id fixture-coords (fn [] [:p "body"]))
    (let [html    (rf.ssr/render-to-string [(rf/view fixture-id)] {})
          ;; The values the formatters produce for the coords actually
          ;; stored in the slot (the merge-coords result). Reading them off
          ;; the shared formatters keeps this test honest even if the fixture
          ;; coords are altered — it asserts the render used THE dialect, not
          ;; a hardcoded copy of it.
          coord   (rf.views.jvm-source-coord-annotation/format-source-coord fixture-id fixture-coords)
          view-id (rf.views.jvm-source-coord-annotation/format-view-id fixture-id)]
      ;; SEMANTIC, posture-independent: `(rf/view id)` resolves to
      ;; the registered view and the view renders its own root and body. The
      ;; gate can remove the attributes; it can never remove the element.
      (is (.startsWith html "<p") (pr-str html))
      (is (.endsWith html ">body</p>") (pr-str html))

      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (.contains html (str "data-rf2-source-coord=\"" coord "\""))
            (str "server markup must carry the source-coord attribute; got: "
                 (pr-str html)))
        (is (.contains html (str "data-rf-view=\"" view-id "\""))
            (str "server markup must carry the view-id attribute; "
                 "got: " (pr-str html)))
        (is (= (str "<p data-rf2-source-coord=\"" coord "\""
                    " data-rf-view=\"" view-id "\">body</p>")
               html)
            (str "the full annotated root, both attributes present; got: "
                 (pr-str html))))

      ;; The REAL-gate arm. Under `-Dre-frame.debug=false` the
      ;; wrapper is never installed, so the same callable head renders the
      ;; SAME element with neither attribute. Pinned by `=` rather than by a
      ;; `not contains?` pair, which would pass vacuously.
      (when-not rf.interop/debug-enabled?
        (is (= "<p>body</p>" html)
            (str "under the production gate the callable head must render the "
                 "bare root, both annotations elided; got: " (pr-str html)))))))
