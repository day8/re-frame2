(ns day8.re-frame2-machines-viz.visual-constants-cljs-test
  "Roundtrip / shape pin for the chart visual constants (rf2-hz3tj).

  Per audit `ai/findings/2026-05-20-tools-testing-posture-audit.md`
  Gap-4, `visual_constants.cljc` was the only src file in
  `tools/machines-viz/src/` without a test companion. The constants
  themselves don't drift silently — but the dependents (chart/svg,
  chart/layout, chart/controls) destructure specific keys from
  `vc/chart`, and a typo in a key rename would surface as a runtime
  nil through every chart consumer.

  This file pins the SHAPE of `vc/chart`: the expected key set, the
  types each value resolves to, and the no-nil invariant. Cheap —
  one map, six assertions; doesn't redundantly re-test every numeric
  value (that's `visual_constants.cljc` itself, deliberately literal
  so a code review can spot drift).

  Cites audit Gap-4."
  (:require
    #?(:clj  [clojure.test :refer [deftest is testing]]
       :cljs [cljs.test    :refer-macros [deftest is testing]])
    [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- the expected key catalogue ----------------------------------------
;;
;; The chart's renderer destructures every one of these. A key
;; disappearing here (rename, accidental dissoc, late edit) would
;; surface as a runtime nil in the chart hiccup — silently. This set
;; is the load-bearing inventory.

(def ^:private expected-chart-keys
  #{;; geometry
    :corner-radius
    :stroke-width
    :stroke-width-emphasis
    :compound-pad-x
    :compound-pad-y
    :compound-stroke-dash
    ;; typography
    :state-label-px
    :edge-label-px
    :final-glyph-px
    :compound-title-px
    :caption-strip-px
    :caption-text-px
    ;; state-tag pills (rf2-m1b88)
    :tag-pill-height
    :tag-pill-pad-x
    :tag-pill-px
    :tag-pill-gap
    :tag-pill-row-gap
    ;; dot-grid background (rf2-m4nj4)
    :dot-grid-spacing-px
    :dot-grid-radius-px
    :dot-grid-alpha
    ;; motion (rf2-xfx6l)
    :pulse-stroke-width-add})

;; ---- shape pins ---------------------------------------------------------

(deftest chart-is-a-map
  (testing "vc/chart is a map (the chart's destructure points all
            depend on this)"
    (is (map? vc/chart))))

(deftest chart-key-set-matches-expected
  (testing "every documented key is present — drift would manifest as
            a runtime nil through every chart hiccup"
    (is (= expected-chart-keys (set (keys vc/chart)))
        "vc/chart key set matches the documented catalogue")))

(deftest chart-has-no-nil-values
  (testing "no entry in vc/chart is nil — a nil here propagates into
            the SVG hiccup tree as a `nil` attribute value, which
            shadow / browser will render as the literal string 'null'"
    (is (every? some? (vals vc/chart))
        "every vc/chart entry resolves to a non-nil value")))

(deftest chart-numeric-keys-are-numbers
  (testing "every key that the chart code uses as a numeric (geometry,
            typography sizes, padding, alpha) resolves to a number"
    (let [numeric-keys (disj expected-chart-keys :compound-stroke-dash)]
      (doseq [k numeric-keys]
        (is (number? (get vc/chart k))
            (str "vc/chart key " k " resolves to a number"))))))

(deftest chart-compound-stroke-dash-is-string
  (testing ":compound-stroke-dash is the SVG `stroke-dasharray` attr
            string, not a number"
    (is (string? (:compound-stroke-dash vc/chart)))))

(deftest chart-corner-radius-locked-at-six
  (testing "rf2-g6cig — corner-radius is locked at 6px per the
            visual-character lock (the React Flow default of 8 reads
            as 'product chrome'; brutalist 0 reads as 'wireframe'; 6
            is the sweet spot). The lock is documented in the source
            file; this assertion pins it at the test layer too so a
            future drift fails CI rather than landing silently."
    (is (= 6 (:corner-radius vc/chart)))))

(deftest chart-typography-honours-refused-floor
  (testing "rf2-cd053 — state-label-px + edge-label-px restored to the
            spec/007-UX-IA refused-floor (state 11, edge 9). Pin so a
            future layout-fit win that walks under the floor again
            (the previous 9/7 regression) fails CI."
    (is (= 11 (:state-label-px vc/chart)))
    (is (= 9  (:edge-label-px vc/chart)))))

(deftest chart-dot-grid-alpha-is-fractional
  (testing "rf2-m4nj4 — dot-grid is a subtle backdrop; alpha must
            stay in (0, 1) — a value at 1 would make the dots opaque
            and overwhelm chart content"
    (let [a (:dot-grid-alpha vc/chart)]
      (is (pos? a))
      (is (< a 1.0)))))
