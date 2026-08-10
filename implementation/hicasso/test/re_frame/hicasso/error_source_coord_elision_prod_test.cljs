(ns re-frame.hicasso.error-source-coord-elision-prod-test
  "PRODUCTION ERASURE OF THE SOURCE COORDINATE (rf2-hic-007).

  `defview` and `defhost` capture `:ns` / `:file` / `:line` / `:column` at
  macro-expansion time and hand them to
  `re-frame.hicasso.impl.error/declaring!`. Both halves sit inside
  `(when re-frame.interop/debug-enabled? …)`, and `debug-enabled?` is
  `^boolean goog.DEBUG` — so under `:advanced` + `goog.DEBUG=false` the
  Closure compiler removes the call AND the map literal it would have
  been given, absolute file path included.

  This file compiles under `:browser-test-prod-elision`, the dedicated
  build with `goog.DEBUG=false` + `:advanced`, so what is asserted below
  is a genuine constant-fold rather than a `with-redefs`. Every assertion
  here would FAIL under `goog.DEBUG=true`: they all assert ABSENCE, which
  is only true in a production build.

  Naming convention: `-elision-prod-test$` is the `:ns-regexp` of that one
  build. `:node-test` (`cljs-test$`) and `:browser-test` (`-cljs-test$`)
  do not reach this file, which is why it can assert the opposite of what
  `re-frame.hicasso.error-shape-cljs-test` asserts about the same macros.

  ## The runtime half of a two-part proof

  This file proves the coordinate is not REACHABLE in a production build.
  `implementation/hicasso/scripts/check_source_coord_elision.cjs` proves
  the stronger thing — that the file-path string is not PRESENT in the
  release bundle at all — by scanning the artefact this build produces.
  A behavioural assertion cannot see a string Closure kept but nothing
  reads, and a bundle scan cannot see a coordinate assembled at runtime,
  so the two are not redundant.

  ## The declarations are next door, and that is not tidiness

  They live in `re-frame.hicasso.coord-sentinel-source` because
  `cljs.test` stamps `:file` into the report map of every `deftest` and
  every `is` — so a test namespace's own file name is in the release
  bundle dozens of times over, and a scan for it reds on a build whose
  erasure is perfectly correct. That namespace carries no `deftest`, so
  the only thing that can put its file name in an artefact is the
  coordinate this bead erases."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.coord-sentinel-source :as sentinel]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :as error]))

;; ---------------------------------------------------------------------------
;; The ledger was never written
;; ---------------------------------------------------------------------------

(deftest no-coordinate-is-registered-for-a-view-declared-under-prod
  (testing "the `(when debug-enabled? (declaring! …))` the `defview`
            expansion emits DCEs whole, so the ledger has no entry and the
            absolute file path the macro read never reached the bundle"
    (is (nil? (error/source-of sentinel/view-name)))))

(deftest no-coordinate-is-registered-for-a-host-declared-under-prod
  (testing "`defhost` opens the same extent under the same gate"
    (is (nil? (error/source-of sentinel/host-name)))))

;; ---------------------------------------------------------------------------
;; A refusal carries neither ambient field
;; ---------------------------------------------------------------------------

(deftest a-refusal-under-prod-carries-no-view-and-no-source
  (let [data (try
               (error/fail! :rf.error/hicasso-empty-vector
                            'front.codec/vec->element
                            "A hiccup vector must have a head."
                            :supply-a-hiccup-head
                            {})
               (catch :default e (ex-data e)))]

    (testing "the four required fields survive — production loses the
              coordinate, never the diagnostic"
      (is (= {:rf.error/id :rf.error/hicasso-empty-vector
              :where       'front.codec/vec->element
              :reason      "A hiccup vector must have a head."
              :recovery    :supply-a-hiccup-head}
             data)))

    (testing "and the ambient pair is ABSENT rather than nil — the whole
              `with-origin` body folded away, so there is no key to be nil"
      (is (not (contains? data :view)))
      (is (not (contains? data :source))))

    (testing "defensive cross-check: nothing that reads like a source file
              appears anywhere in the ex-data"
      (let [flat (pr-str data)]
        (is (not (re-find #"\.clj[sc]?" flat)))
        (is (not (re-find #"coord_sentinel_source" flat)))))))

;; ---------------------------------------------------------------------------
;; What elided is the diagnostic, not the feature
;; ---------------------------------------------------------------------------

(deftest the-boundary-and-the-host-are-still-minted-under-prod
  (testing "a positive control, and the reason this file cannot pass by the
            macros having compiled to nothing at all"
    (is (true? (codec/boundary-head? sentinel/sentinel-row)))
    (is (true? (codec/host-head? sentinel/sentinel-host)))
    (is (= sentinel/view-name (.-displayName sentinel/sentinel-row))
        "the view name is NOT elided — it is the measure id and the
         React DevTools label, and it is a name rather than a coordinate")))
