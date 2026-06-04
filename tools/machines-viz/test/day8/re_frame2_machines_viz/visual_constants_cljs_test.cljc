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

  rf2-k647w — the renderer gap is now CLOSED. `visual-constants` is
  the SINGLE SOURCE of the chart's render geometry/typography: the
  xyflow `MachineChart` (`chart.nodes` / `chart.edges` / `chart.cljs`)
  reads every constant off the resolved density map (threaded through
  the projector's per-node/per-edge `:data`) instead of hardcoding.

  rf2-so5b0 → rf2-a2b55 — the visible state-tag pill row, retired by
  rf2-so5b0, is RESTORED by rf2-a2b55 BELOW the state name (Stately
  graph view convention). The `:tag-pill-*` key family is back on
  every density map, joined by an `:action-pill-*` family that
  parameterises the entry/exit + edge action pills (`+ <action>` /
  `- <action>`) the same convention uses. `:data-tags` / `:title`
  attr surface (rf2-so5b0) persists in parallel for host
  introspection."
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
    :compound-radius                ;; rf2-k647w — render constant promoted
    ;; typography (rf2-gg7ws — edge-label lifted 9 → 11)
    :edge-label-px
    ;; edge arrowheads (rf2-dt5b1 — ride the density so the head scales
    ;; with the stroke; quiet `__in` < primary `__out`)
    :arrow-width
    :arrow-width-quiet
    :arrow-width-entry
    ;; rf2-6d4y3 — :final-glyph-px removed: the final-state ✓ glyph was
    ;; dropped (rf2-az6e2 — the quiet doubled border is the unambiguous
    ;; final-state signal) and no renderer ever read the constant; it
    ;; was carried only to satisfy this parity test.
    ;; rf2-ee38b.21 — :caption-strip-px / :caption-text-px removed:
    ;; no renderer ever read them (there is no caption strip in the
    ;; chart). They were carried only to satisfy this parity test.
    ;; rf2-dt5b1 — :state-label-px / :compound-pad-x / :compound-pad-y /
    ;; :compound-stroke-dash / :compound-title-px / :edge-label-backplate-
    ;; opacity / :dot-grid-alpha removed: no renderer ever read them (the
    ;; state label rides :state-title-px; the compound chrome reads the
    ;; :container-* keys; the dot grid is xyflow's <Background> :gap/:size
    ;; with no alpha; the edge-label backplate paints the opaque
    ;; :event-chip-bg theme token). They were carried only for this test.
    ;; state-tag pills (rf2-m1b88; rf2-a2b55 restored under state-name)
    :tag-pill-height
    :tag-pill-pad-x
    :tag-pill-px
    :tag-pill-radius
    :tag-pill-gap
    :tag-pill-row-gap
    ;; action pills (rf2-a2b55 — entry/exit + edge actions render
    ;; as `+ <action>` pills per Stately graph view convention)
    :action-pill-height
    :action-pill-pad-x
    :action-pill-px
    :action-pill-radius
    :action-pill-gap
    :action-pill-row-gap
    ;; structured topology grammar (rf2-az6e2) — state title/body box,
    ;; compound container, parallel region, event route chip, pseudo-
    ;; state markers, action-section caption, root chrome.
    :state-title-height
    :state-title-pad-x
    :state-title-px
    :state-body-pad-x
    :state-body-pad-y
    :state-body-gap
    :state-divider-width
    :state-shadow-blur
    :container-title-height
    :container-title-pad-x
    :container-body-pad
    :container-divider-width
    :region-title-height
    :region-title-pad-x
    :action-caption-px
    :action-caption-gap
    :event-chip-min-w
    :event-chip-min-h
    :event-chip-pad-x
    :event-chip-pad-y
    :event-chip-radius
    :event-chip-px
    :event-chip-action-px
    :pseudo-size
    :pseudo-radius
    :pseudo-px
    :root-title-height
    :root-title-px
    :root-context-pad
    ;; dot-grid background (rf2-m4nj4)
    :dot-grid-spacing-px
    :dot-grid-radius-px})

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
  (testing "every chart key resolves to a number (geometry, typography
            sizes, padding, arrowhead widths). rf2-dt5b1 — the only
            string-valued key (:compound-stroke-dash) was removed, so
            every remaining key is numeric."
    (doseq [k expected-chart-keys]
      (is (number? (get vc/chart k))
          (str "vc/chart key " k " resolves to a number")))))

(deftest chart-corner-radius-locked-at-six
  (testing "rf2-g6cig — corner-radius is locked at 6px per the
            visual-character lock (the React Flow default of 8 reads
            as 'product chrome'; brutalist 0 reads as 'wireframe'; 6
            is the sweet spot). The lock is documented in the source
            file; this assertion pins it at the test layer too so a
            future drift fails CI rather than landing silently."
    (is (= 6 (:corner-radius vc/chart)))))

(deftest chart-typography-meets-chart-floor
  (testing "rf2-gg7ws — state-title-px + edge-label-px lifted from the
            spec/007-UX-IA refused-floor (11 / 9) to a chart-
            appropriate 13 / 11 per the 2026-05-20 visual-quality
            audit. The refused-floor was set for dense data-grid
            surfaces; applying it to a chart that competes with
            xstate-stately's typography was a category error. Pin so a
            future layout-fit win that walks back under the chart
            floor fails CI. (rf2-dt5b1 — the state label rides
            `:state-title-px`; the unread `:state-label-px` was removed.)"
    (is (= 13 (:state-title-px vc/chart)))
    (is (= 11 (:edge-label-px vc/chart)))))

(deftest chart-regular-compound-radius-matches-shipped-render
  (testing "rf2-k647w — `chart-regular`'s `:compound-radius` equals the
            10px the compound-node chrome shipped. Distinct from the
            state-node `:corner-radius` lock (6); the compound box reads
            as a looser container."
    (is (= 10 (:compound-radius vc/chart)))))

(deftest chart-arrowhead-quiet-smaller-than-primary
  (testing "rf2-dt5b1 — the QUIET `__in` arrowhead is smaller than the
            PRIMARY `__out` arrowhead so the primary head reads as the
            route's terminus and the source→event→target pair reads as
            ONE transition. The entry-marker head sits between."
    (is (< (:arrow-width-quiet vc/chart)
           (:arrow-width-entry vc/chart)
           (:arrow-width vc/chart)))
    (is (= 18 (:arrow-width vc/chart)))
    (is (= 10 (:arrow-width-quiet vc/chart)))
    (is (= 12 (:arrow-width-entry vc/chart)))))

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
  (testing "rf2-32gw5 — `state-title-px` is monotonic across the
            density axis: compact < regular < cosy. If a density's
            type walks back or up unexpectedly the picker UI would
            ship a 'cosy' value that's actually tighter than
            'regular' — a labelling bug. Same monotonicity holds for
            edge labels. (rf2-dt5b1 — `:state-title-px` carries the
            state label; the unread `:state-label-px` was removed.)"
    (is (< (:state-title-px vc/chart-compact)
           (:state-title-px vc/chart-regular)
           (:state-title-px vc/chart-cosy)))
    (is (< (:edge-label-px vc/chart-compact)
           (:edge-label-px vc/chart-regular)
           (:edge-label-px vc/chart-cosy)))))

(deftest density-geometry-monotonic
  (testing "rf2-32gw5 — dot-grid spacing + (rf2-dt5b1) arrowhead widths
            are also monotonic. Compact tightens; cosy loosens. Density
            is ONE knob — every quantity that should track it does. The
            arrowhead scaling is the rf2-dt5b1 fix: the head no longer
            sits at a baked literal regardless of the stroke density."
    (is (< (:dot-grid-spacing-px vc/chart-compact)
           (:dot-grid-spacing-px vc/chart-regular)
           (:dot-grid-spacing-px vc/chart-cosy)))
    (doseq [k [:arrow-width :arrow-width-quiet :arrow-width-entry]]
      (is (< (get vc/chart-compact k)
             (get vc/chart-regular k)
             (get vc/chart-cosy k))
          (str "arrowhead key " k " is monotonic compact < regular < cosy")))))

(deftest density-compound-radius-monotonic
  (testing "rf2-k647w — `:compound-radius` (the render constant the
            renderer now reads off the resolved density) tracks the
            density axis monotonically. The corner-radius lock (6)
            is the ONLY geometry that does not scale; everything the
            renderer paints by quantity does.

            rf2-so5b0 — the historical sibling assertions on
            `:tag-pill-height` / `:tag-pill-radius` / `:tag-pill-px`
            retired with the visible pill row; the chart no longer
            carries pill geometry to assert monotonic over."
    (is (< (:compound-radius vc/chart-compact)
           (:compound-radius vc/chart-regular)
           (:compound-radius vc/chart-cosy)))))

(deftest structured-grammar-geometry-monotonic
  (testing "rf2-az6e2 — the structured-topology grammar geometry
            (state title strip, container title, event chip, region
            title, root title) is monotonic across the density axis:
            compact < regular < cosy. Density scales QUANTITY; a key
            that walked back or up unexpectedly would ship a mislabelled
            density. The corner-radius lock (6) remains the only
            non-scaling geometry."
    (doseq [k [:state-title-height :state-title-px
               :container-title-height :event-chip-min-w
               :event-chip-min-h :event-chip-px
               :region-title-height :pseudo-size :root-title-height
               :root-title-px]]
      (is (< (get vc/chart-compact k)
             (get vc/chart-regular k)
             (get vc/chart-cosy k))
          (str "structured-grammar key " k
               " is monotonic compact < regular < cosy")))))

(deftest structured-grammar-regular-targets
  (testing "rf2-az6e2 — pin the regular-density targets the bead's
            §Visual constants names as approximate floors (state title
            ~24px, container title ~26px, region title ~24px, event chip
            ~92x32, event text ~11px, action text ~9px). Pinning so a
            future tweak that drops below the bead's stated grammar
            geometry fails CI rather than silently regressing the
            structure-first read."
    (is (= 24 (:state-title-height vc/chart-regular)))
    (is (= 26 (:container-title-height vc/chart-regular)))
    (is (= 24 (:region-title-height vc/chart-regular)))
    (is (= 92 (:event-chip-min-w vc/chart-regular)))
    (is (= 32 (:event-chip-min-h vc/chart-regular)))
    (is (= 11 (:event-chip-px vc/chart-regular)))
    (is (= 9  (:event-chip-action-px vc/chart-regular)))))

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
