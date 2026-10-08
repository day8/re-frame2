(ns re-frame.source-coord-parity-cljs-test
  "Per Spec 006 §Source-coord annotation + §View
  tagging contract: the CLJS-side Reagent adapter's
  `format-source-coord` / `format-view-id` and the JVM-side
  registration-boundary formatters (in
  `re-frame.views.jvm-source-coord-annotation`) MUST produce byte-
  identical attribute VALUES for the same input — same id, same captured
  `:line` / `:column`. Pair tools that consume `data-rf2-source-coord` /
  `data-rf-view` parse the same shape whether the HTML came from server-
  side rendering or client-side Reagent — divergent formats would
  silently break the source-mapping contract.

  Both hosts emit both attributes. This file pins the CLJS formatters to
  canonical literals; `implementation/ssr/test/re_frame/source_coord_parity_test.clj`
  pins the JVM ones to the same literals, which are the byte-comparison
  point."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.views]))

;; ---- the canonical attribute-value shape (shared with the JVM test) ------

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

;; ---- CLJS side: degraded shape (no line / col) pins the canonical -------

(deftest cljs-format-source-coord-degraded-shape-byte-identical
  (testing "with no :line / :column (a programmatic reg-view*), the CLJS
            helper degrades to <ns>:<sym>:?:?, as the SSR helper does"
    (let [cljs-format #'re-frame.views/format-source-coord
          cljs-output (cljs-format fixture-id fixture-meta-no-line-no-col)]
      (is (= expected-attr-no-line-no-col cljs-output)
          (str "CLJS degraded shape: expected "
               (pr-str expected-attr-no-line-no-col)
               " — got: " (pr-str cljs-output))))))

;; ---- convergence: source-coords is the single cross-host owner -
;;
;; Both hosts' formatters alias one `.cljc` implementation in
;; `re-frame.source-coords`, so a CLJS copy cannot drift from the JVM's.

(deftest neutral-owner-is-the-single-cljs-formatter-implementation
  (testing "the CLJS adapter.context vars (and the re-frame.views /
            spine re-exports built on them) alias the one cross-host
            implementation in re-frame.source-coords; the neutral owner emits
            the canonical literals and the adapter.context var is the identical
            fn object."
    (is (= [expected-attr expected-view-id true true]
           [(rf.source-coords/format-source-coord fixture-id fixture-meta)
            (rf.source-coords/format-view-id fixture-id)
            (identical? rf.source-coords/format-source-coord
                        rf.adapter.context/format-source-coord)
            (identical? rf.source-coords/format-view-id
                        rf.adapter.context/format-view-id)])
        "the neutral owner emits both canonical literals, and the adapter.context formatters are the identical fn objects")))
