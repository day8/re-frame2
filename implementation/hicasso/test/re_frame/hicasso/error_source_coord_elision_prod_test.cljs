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
  so the two are not redundant."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.error :as error]))

;; ---------------------------------------------------------------------------
;; The sentinel declarations — a view and a host declared through the public
;; door, so what elides is the real macro capture and not a stand-in
;; ---------------------------------------------------------------------------

(h/defview prod-sentinel-row
  "Declared for its COORDINATE rather than its markup. Under a dev build
  the macro registers this file's absolute path, line and column against
  the name below; under this build it registers nothing."
  [_]
  [:li "sentinel"])

(h/defhost prod-sentinel-host
  "The declaration channel's half of the same proof — `defhost` opens the
  same extent and captures the same coordinate."
  (fn ProdSentinel [_props] nil)
  {:callbacks {:on-change :event}})

(def ^:private view-name
  "re-frame.hicasso.error-source-coord-elision-prod-test/prod-sentinel-row")

(def ^:private host-name
  "re-frame.hicasso.error-source-coord-elision-prod-test/prod-sentinel-host")

;; ---------------------------------------------------------------------------
;; The ledger was never written
;; ---------------------------------------------------------------------------

(deftest no-coordinate-is-registered-for-a-view-declared-under-prod
  (testing "the `(when debug-enabled? (declaring! …))` the `defview`
            expansion emits DCEs whole, so the ledger has no entry and the
            absolute file path the macro read never reached the bundle"
    (is (nil? (error/source-of view-name)))))

(deftest no-coordinate-is-registered-for-a-host-declared-under-prod
  (testing "`defhost` opens the same extent under the same gate"
    (is (nil? (error/source-of host-name)))))

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
        (is (not (re-find #"error_source_coord" flat)))))))

;; ---------------------------------------------------------------------------
;; What elided is the diagnostic, not the feature
;; ---------------------------------------------------------------------------

(deftest the-boundary-and-the-host-are-still-minted-under-prod
  (testing "a positive control, and the reason this file cannot pass by the
            macros having compiled to nothing at all"
    (is (true? (codec/boundary-head? prod-sentinel-row)))
    (is (true? (codec/host-head? prod-sentinel-host)))
    (is (= view-name (.-displayName prod-sentinel-row))
        "the view name is NOT elided — it is the measure id and the
         React DevTools label, and it is a name rather than a coordinate")))
