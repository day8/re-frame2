(ns re-frame.hicasso.error-source-coord-elision-prod-test
  "PRODUCTION ERASURE OF THE SOURCE COORDINATE.

  `defview` and `defhost` capture `:ns` / `:file` / `:line` / `:column` at
  macro-expansion time and hand them to
  `re-frame.hicasso.impl.error/declaring!`. Both halves sit inside
  `(when re-frame.interop/debug-enabled? …)`, and `debug-enabled?` is
  `^boolean goog.DEBUG` — so under `:advanced` + `goog.DEBUG=false` the
  Closure compiler removes the call AND the map literal it would have
  been given, absolute file path included.

  **`defview`'s authoring-time alias rides that same gate**:
  the declaration also publishes a `:view` registrar entry carrying the
  coordinate and the minted head, and it is emitted inside the identical
  `when`. So the same constant-fold that erases the coordinate erases the
  registration, which is what makes Hicasso's *no registry at runtime*
  stance a production fact rather than a claim about the dev build. The
  dev-side shape is
  `re-frame.hicasso.view-alias-registry-cljs-test`'s; only the ABSENCE is
  assertable here.

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
            [re-frame.hicasso.coord-sentinel-source :as rf.hicasso.coord-sentinel-source]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.error :as rf.hicasso.impl.error]
            [re-frame.registrar :as rf.registrar]))

;; ---------------------------------------------------------------------------
;; The ledger was never written
;; ---------------------------------------------------------------------------

(deftest no-coordinate-is-registered-for-a-view-declared-under-prod
  (testing "the `(when debug-enabled? (declaring! …))` the `defview`
            expansion emits DCEs whole, so the ledger has no entry and the
            absolute file path the macro read never reached the bundle"
    (is (nil? (rf.hicasso.impl.error/source-of rf.hicasso.coord-sentinel-source/view-name)))))

(deftest no-coordinate-is-registered-for-a-host-declared-under-prod
  (testing "`defhost` opens the same extent under the same gate"
    (is (nil? (rf.hicasso.impl.error/source-of rf.hicasso.coord-sentinel-source/host-name)))))

;; ---------------------------------------------------------------------------
;; Nor was the authoring-time alias
;; ---------------------------------------------------------------------------

(deftest no-view-registrar-entry-is-published-for-a-view-declared-under-prod
  (testing "`defview`'s registrar alias rides the SAME `(when
            debug-enabled? …)` gate as the coordinate above, so a production
            bundle publishes no entry and Hicasso's *no registry at runtime*
            stance holds where it is claimed. The entry serves tools; there
            are none here"
    (is (nil? (rf.registrar/lookup :view rf.hicasso.coord-sentinel-source/view-id))))

  (testing "and NO entry anywhere in the `:view` kind carries the alias's
            slot — a build-wide absence rather than one id's"
    (is (empty? (filter :hicasso/component
                        (vals (rf.registrar/registrations :view)))))))

(deftest the-view-kind-is-populated-under-prod-so-the-absence-above-is-real
  ;; The positive control the row above needs. This build compiles every
  ;; `-elision-prod-test` namespace in the repository, and the Reagent
  ;; adapter's three register views at load — so `registrations :view`
  ;; answering non-empty is what proves the nil above is the DECLARATION's
  ;; absence and not a registrar that answers nothing for anyone.
  (testing "other substrates' views are registered here"
    (is (seq (rf.registrar/registrations :view)))))

;; ---------------------------------------------------------------------------
;; A refusal carries neither ambient field
;; ---------------------------------------------------------------------------

(deftest a-refusal-under-prod-carries-no-view-and-no-source
  (let [data (try
               (rf.hicasso.impl.error/fail! :rf.error/hicasso-empty-vector
                            're-frame.hicasso.impl.codec/vec->element
                            "A hiccup vector must have a head."
                            {})
               (catch :default e (ex-data e)))]

    (testing "the four required fields survive — production loses the
              coordinate, never the diagnostic"
      (is (= {:rf.error/id :rf.error/hicasso-empty-vector
              :where       're-frame.hicasso.impl.codec/vec->element
              :reason      "A hiccup vector must have a head."
              :recovery    :no-recovery}
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

(deftest a-forged-coordinate-in-the-payload-does-not-reach-a-prod-refusal
  ;; The row above hands `fail!` an EMPTY payload, so it proves the ledger
  ;; is unwritten and nothing more. The absence contract is a claim about
  ;; every refusal a production build emits, and the payload is the one
  ;; place a `.cljs` path can still come from — a call site's own map. It
  ;; used to survive here: `with-origin` folds to its input under
  ;; `goog.DEBUG=false`, so there was no ambient value to overwrite it with
  ;; and the forgery merged through untouched.
  (let [data (try
               (rf.hicasso.impl.error/fail! :rf.error/hicasso-empty-vector
                            're-frame.hicasso.impl.codec/vec->element
                            "A hiccup vector must have a head."
                            {:view   "app.impostor/not-a-view"
                             :source {:ns 'app.impostor :file "app/impostor.cljs"
                                      :line 1 :column 1}
                             :head   :the-class-s-own-slot})
               (catch :default e (ex-data e)))]

    (testing "the class's own slot rides through and the forged ambient pair
              does not — production absence is the constructor's answer, not
              a property of well-behaved call sites"
      (is (= {:rf.error/id :rf.error/hicasso-empty-vector
              :where       're-frame.hicasso.impl.codec/vec->element
              :reason      "A hiccup vector must have a head."
              :recovery    :no-recovery
              :head        :the-class-s-own-slot}
             data)))

    (testing "and the same cross-check the row above runs, now against a
              payload that deliberately carried a source path"
      (is (not (re-find #"impostor" (pr-str data)))))))

;; ---------------------------------------------------------------------------
;; What elided is the diagnostic, not the feature
;; ---------------------------------------------------------------------------

(deftest the-boundary-and-the-host-are-still-minted-under-prod
  (testing "a positive control, and the reason this file cannot pass by the
            macros having compiled to nothing at all"
    (is (true? (rf.hicasso.impl.codec/boundary-head? rf.hicasso.coord-sentinel-source/sentinel-row)))
    (is (true? (rf.hicasso.impl.codec/host-head? rf.hicasso.coord-sentinel-source/sentinel-host)))
    (is (= rf.hicasso.coord-sentinel-source/view-name (.-displayName rf.hicasso.coord-sentinel-source/sentinel-row))
        "the view name is NOT elided — it is the measure id and the
         React DevTools label, and it is a name rather than a coordinate")))
