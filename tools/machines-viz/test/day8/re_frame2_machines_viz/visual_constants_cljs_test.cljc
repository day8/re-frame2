(ns day8.re-frame2-machines-viz.visual-constants-cljs-test
  "Shape + density-contract pin for the chart visual constants
  (rf2-hz3tj).

  This file pins the SHAPE of `vc/chart`: the expected key set, the
  types each value resolves to, and the no-nil invariant. Cheap —
  one map, six assertions; doesn't redundantly re-test every numeric
  value (that's `visual_constants.cljc` itself, deliberately literal
  so a code review can spot drift).

  rf2-32gw5 — pins extended to the three density variants
  (`chart-compact / chart-regular / chart-cosy`): each density has
  the SAME key set as the others, the corner-radius lock (rf2-g6cig)
  holds across every density, and `chart-for-density` resolves the
  closed catalogue + throws on unknown densities.

  rf2-0gmwp — what this guards, post-xyflow-migration. The
  pre-migration consumers (`chart/svg`, `chart/controls`) were
  DELETED in rf2-gpzb4; the docstring above used to name them. What
  `visual-constants` (and therefore this suite) actually guards now is
  the **`:density` contract specified in `tools/machines-viz/spec/`
  `API.md` §Density** — `MachineChart`'s `:density` prop resolves
  through `visual-constants/chart-for-density`, the three named maps
  share a key set, and the corner-radius lock is density-invariant.
  That is a normative spec surface in a spec-first project, so the
  contract is real even while the xyflow renderer's `:density`
  consumption is still being re-wired (the xyflow `MachineChart`
  currently hardcodes the regular-density numbers rather than reading
  `chart-for-density`; closing that renderer gap — including
  reconciling the drifted tag-pill values — is tracked separately).
  This suite pins the data contract the renderer rewire must satisfy."
  (:require
    #?(:clj  [clojure.test :refer [deftest is testing]]
       :cljs [cljs.test    :refer-macros [deftest is testing]])
    [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- the expected key catalogue ----------------------------------------
;;
;; The `:density` contract (spec/API.md §Density) requires every named
;; density map to carry exactly this key set; the renderer rewire that
;; threads `chart-for-density` destructures every one. A key
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
    ;; typography (rf2-gg7ws — lifted 11/9 → 13/11)
    :state-label-px
    :edge-label-px
    :edge-label-backplate-opacity
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
    :dot-grid-alpha})

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
            typography sizes, padding, alpha, opacity) resolves to a
            number"
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

(deftest chart-typography-meets-chart-floor
  (testing "rf2-gg7ws — state-label-px + edge-label-px lifted from the
            spec/007-UX-IA refused-floor (11 / 9) to a chart-
            appropriate 13 / 11 per the 2026-05-20 visual-quality
            audit. The refused-floor was set for dense data-grid
            surfaces; applying it to a chart that competes with
            xstate-stately's typography was a category error. Pin so a
            future layout-fit win that walks back under the chart
            floor fails CI."
    (is (= 13 (:state-label-px vc/chart)))
    (is (= 11 (:edge-label-px vc/chart)))))

(deftest chart-edge-label-backplate-opacity-is-fractional
  (testing "rf2-gg7ws — edge-label backplate opacity is in (0, 1).
            At 1 the backplate would be opaque white (overprinted
            chart canvas); at 0 it would be invisible (no
            collision-avoidance). ~0.85 is the sweet spot."
    (let [a (:edge-label-backplate-opacity vc/chart)]
      (is (pos? a))
      (is (< a 1.0)))))

(deftest chart-dot-grid-alpha-is-fractional
  (testing "rf2-m4nj4 — dot-grid is a subtle backdrop; alpha must
            stay in (0, 1) — a value at 1 would make the dots opaque
            and overwhelm chart content"
    (let [a (:dot-grid-alpha vc/chart)]
      (is (pos? a))
      (is (< a 1.0)))))

;; ---- density variants (rf2-32gw5) --------------------------------------
;;
;; The three density variants share the SAME key set as the regular map;
;; a typo / accidental dissoc in one variant would surface as a runtime
;; nil through the renderer's destructure. Pin the equality directly.

(deftest chart-default-is-regular
  (testing "rf2-32gw5 — `vc/chart` is the regular-density alias. A
            consumer reaching for `vc/chart` gets exactly the
            regular map; the chart-floor (rf2-gg7ws) lift was set at
            the regular density and that's the default identity."
    (is (= vc/chart vc/chart-regular))))

(deftest density-variants-share-key-set
  (testing "rf2-32gw5 — every density carries the same key set as
            `chart-regular`. A key in one is a key in all; a density
            that diverged would surface as a runtime nil from a
            helper destructure that's perfectly correct for the
            regular density."
    (is (= expected-chart-keys (set (keys vc/chart-compact))))
    (is (= expected-chart-keys (set (keys vc/chart-regular))))
    (is (= expected-chart-keys (set (keys vc/chart-cosy))))))

(deftest density-variants-have-no-nil-values
  (testing "rf2-32gw5 — no entry in any density resolves to nil"
    (is (every? some? (vals vc/chart-compact)))
    (is (every? some? (vals vc/chart-regular)))
    (is (every? some? (vals vc/chart-cosy)))))

(deftest density-variants-respect-corner-radius-lock
  (testing "rf2-g6cig — corner-radius is locked at 6 across every
            density. Density scales QUANTITY (type size, padding,
            stroke); it must not alter the chart's visual IDENTITY.
            The rounded-rect 'data, not product' character holds."
    (is (= 6 (:corner-radius vc/chart-compact)))
    (is (= 6 (:corner-radius vc/chart-regular)))
    (is (= 6 (:corner-radius vc/chart-cosy)))))

(deftest density-typography-monotonic
  (testing "rf2-32gw5 — `state-label-px` is monotonic across the
            density axis: compact < regular < cosy. If a density's
            type walks back or up unexpectedly the picker UI would
            ship a 'cosy' value that's actually tighter than
            'regular' — a labelling bug. Same monotonicity holds for
            edge labels."
    (is (< (:state-label-px vc/chart-compact)
           (:state-label-px vc/chart-regular)
           (:state-label-px vc/chart-cosy)))
    (is (< (:edge-label-px vc/chart-compact)
           (:edge-label-px vc/chart-regular)
           (:edge-label-px vc/chart-cosy)))))

(deftest density-geometry-monotonic
  (testing "rf2-32gw5 — compound padding + dot-grid spacing are also
            monotonic. Compact tightens; cosy loosens. Density is
            ONE knob — every quantity that should track it does."
    (is (< (:compound-pad-x vc/chart-compact)
           (:compound-pad-x vc/chart-regular)
           (:compound-pad-x vc/chart-cosy)))
    (is (< (:dot-grid-spacing-px vc/chart-compact)
           (:dot-grid-spacing-px vc/chart-regular)
           (:dot-grid-spacing-px vc/chart-cosy)))))

(deftest densities-catalogue-is-closed
  (testing "rf2-32gw5 — the `densities` Var enumerates the closed
            choice set hosts pick from. Three entries, no more, no
            less. New densities require a deliberate spec amendment."
    (is (= [:compact :regular :cosy] vc/densities))))

(deftest chart-for-density-resolves-named-densities
  (testing "rf2-32gw5 — every named density resolves to its map"
    (is (= vc/chart-compact (vc/chart-for-density :compact)))
    (is (= vc/chart-regular (vc/chart-for-density :regular)))
    (is (= vc/chart-cosy    (vc/chart-for-density :cosy)))))

(deftest chart-for-density-nil-is-regular
  (testing "rf2-32gw5 — nil (the implicit default; `:density` prop
            omitted) resolves to `chart-regular`. Hosts that never
            pass `:density` get exactly the pre-rf2-32gw5 chart."
    (is (= vc/chart-regular (vc/chart-for-density nil)))))

(deftest chart-for-density-unknown-throws
  (testing "rf2-32gw5 — an unrecognised density is a programmer
            error, not a silent fallback. The host either picks
            from the closed set or the chart refuses to render."
    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (vc/chart-for-density :spacious)))
    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (vc/chart-for-density :super-compact)))
    (is (thrown? #?(:clj  Exception
                    :cljs js/Error)
                 (vc/chart-for-density "regular"))))) ;; string, not kw

(deftest dynamic-chart-default-is-regular
  (testing "rf2-32gw5 — `vc/*chart*` defaults to `chart-regular`
            outside any `binding`. Helpers that destructure off
            `vc/*chart*` outside the renderer's binding scope see
            the same constants the namespace-level alias provided
            before rf2-32gw5."
    (is (= vc/chart-regular vc/*chart*))))
