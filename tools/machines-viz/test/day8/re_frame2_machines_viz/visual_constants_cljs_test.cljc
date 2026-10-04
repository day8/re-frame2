(ns day8.re-frame2-machines-viz.visual-constants-cljs-test
  "Shape + density-contract pin for the chart visual constants.

  This file pins the SHAPE of the density maps: the expected key set,
  the types each value resolves to, and the no-nil invariant, with
  `vc/chart` pinned as the regular-density alias. It doesn't re-test
  every numeric value (that's `visual_constants.cljc` itself,
  deliberately literal so a code review can spot drift).

  The pins cover the three density variants
  (`chart-compact / chart-regular / chart-cosy`): each density has
  the SAME key set as the others, the corner-radius lock
  holds across every density, and `chart-for-density` resolves the
  closed catalogue + throws on unknown densities.

  What `visual-constants` (and therefore this suite) guards is the
  **`:density` contract specified in `tools/machines-viz/spec/`
  `API.md` §Density** — `MachineChart`'s `:density` prop resolves
  through `visual-constants/chart-for-density`, the three named maps
  share a key set, and the corner-radius lock is density-invariant.

  `visual-constants` is the SINGLE SOURCE of the chart's render
  geometry/typography: the xyflow `MachineChart` (`chart.nodes` /
  `chart.edges` / `chart.cljs`) reads every constant off the resolved
  density map (threaded through the projector's per-node/per-edge
  `:data`) instead of hardcoding.

  The visible state-tag pill row sits BELOW the state name (Stately
  graph view convention), so every density map carries the
  `:tag-pill-*` key family, joined by an `:action-pill-*` family that
  parameterises the entry/exit + edge action pills (`+ <action>` /
  `- <action>`) the same convention uses. The `:data-tags` / `:title`
  attr surface carries the tags in parallel for host introspection."
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
    :compound-radius                ;; the compound-node chrome radius
    ;; typography
    :edge-label-px
    ;; edge arrowheads (ride the density so the head scales with the
    ;; stroke; quiet `__in` < primary `__out`)
    :arrow-width
    :arrow-width-quiet
    :arrow-width-entry
    ;; The catalogue carries only keys a renderer reads — no final-state
    ;; glyph size (the quiet doubled border is the unambiguous final-state
    ;; signal), no caption strip (the chart has none), and no separate
    ;; state-label / compound padding / dash / title / edge-label backplate
    ;; opacity / dot-grid alpha keys: the state label rides
    ;; :state-title-px, the compound chrome reads the :container-* keys,
    ;; the dot grid is xyflow's <Background> :gap/:size with no alpha, and
    ;; the edge-label backplate paints the opaque :event-chip-bg theme token.
    ;; state-tag pills (under the state name)
    :tag-pill-height
    :tag-pill-pad-x
    :tag-pill-px
    :tag-pill-radius
    :tag-pill-gap
    :tag-pill-row-gap
    ;; action pills (entry/exit + edge actions render as `+ <action>`
    ;; pills per Stately graph view convention)
    :action-pill-height
    :action-pill-pad-x
    :action-pill-px
    :action-pill-radius
    :action-pill-row-gap
    ;; structured topology grammar — state title/body box, compound
    ;; container, parallel region, event route chip, pseudo-state markers,
    ;; action-section caption. The root chrome lives in the root-container
    ;; frame and reads the `:container-title-*` keys, and there is no
    ;; action-pill gap key (an action row paints ONE chip, so nothing
    ;; spaces two).
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
    ;; dot-grid background
    :dot-grid-spacing-px
    :dot-grid-radius-px})

;; ---- shape pins ---------------------------------------------------------

(deftest chart-numeric-keys-are-numbers
  (testing "every chart key resolves to a number (geometry, typography
            sizes, padding, arrowhead widths) — the catalogue carries
            no string-valued key."
    (doseq [k expected-chart-keys]
      (is (number? (get vc/chart k))
          (str "vc/chart key " k " resolves to a number")))))

(deftest chart-typography-meets-chart-floor
  (testing "state-title-px + edge-label-px sit at a chart-
            appropriate 13 / 11, above the spec/007-UX-IA refused-floor
            (11 / 9). That floor is for dense data-grid surfaces;
            applying it to a chart that competes with xstate-stately's
            typography would be a category error. Pin so a
            future layout-fit win that walks back under the chart
            floor fails CI. (The state label rides `:state-title-px`.)"
    (is (= 13 (:state-title-px vc/chart)))
    (is (= 11 (:edge-label-px vc/chart)))))

(deftest chart-arrowhead-quiet-smaller-than-primary
  (testing "the QUIET `__in` arrowhead is smaller than the
            PRIMARY `__out` arrowhead so the primary head reads as the
            route's terminus and the source→event→target pair reads as
            ONE transition. The entry-marker head sits between."
    (is (< (:arrow-width-quiet vc/chart)
           (:arrow-width-entry vc/chart)
           (:arrow-width vc/chart)))
    (is (= 12 (:arrow-width vc/chart)))
    (is (= 8 (:arrow-width-quiet vc/chart)))
    (is (= 10 (:arrow-width-entry vc/chart)))))

;; ---- density variants --------------------------------------------------
;;
;; The three density variants share the SAME key set as the regular map;
;; a typo / accidental dissoc in one variant would surface as a runtime
;; nil through the renderer's destructure. Pin the equality directly.

(deftest chart-default-is-regular
  (testing "`vc/chart` is the regular-density alias. A
            consumer reaching for `vc/chart` gets exactly the
            regular map; the chart floor is set at the regular
            density and that's the default identity."
    (is (= vc/chart vc/chart-regular))))

(deftest density-variants-share-key-set
  (testing "every density carries the same key set as
            `chart-regular`. A key in one is a key in all; a density
            that diverged would surface as a runtime nil from a
            helper destructure that's perfectly correct for the
            regular density."
    (is (= expected-chart-keys (set (keys vc/chart-compact))))
    (is (= expected-chart-keys (set (keys vc/chart-regular))))
    (is (= expected-chart-keys (set (keys vc/chart-cosy))))))

(deftest density-variants-have-no-nil-values
  (testing "no entry in any density resolves to nil"
    (is (every? some? (vals vc/chart-compact)))
    (is (every? some? (vals vc/chart-regular)))
    (is (every? some? (vals vc/chart-cosy)))))

(deftest density-variants-respect-corner-radius-lock
  (testing "corner-radius is locked at 6 across every
            density. Density scales QUANTITY (type size, padding,
            stroke); it must not alter the chart's visual IDENTITY.
            The rounded-rect 'data, not product' character holds."
    (is (= 6 (:corner-radius vc/chart-compact)))
    (is (= 6 (:corner-radius vc/chart-regular)))
    (is (= 6 (:corner-radius vc/chart-cosy)))))

(deftest density-scaled-keys-are-monotonic
  (testing "every quantity that tracks density is monotonic across the axis,
            compact < regular < cosy: typography (`:state-title-px` carries
            the state label), the dot grid, the arrowheads (so the head scales
            with the stroke density instead of sitting at a baked literal),
            the compound radius the renderer reads off the resolved density,
            and the structured-topology grammar geometry (state title strip,
            container title, event chip, region title). Density is ONE knob:
            a key that walked back or up would ship a 'cosy' value tighter
            than 'regular', a mislabelled density. The corner-radius lock (6)
            is the only geometry that does not scale."
    (doseq [k [:state-title-px :edge-label-px
               :dot-grid-spacing-px
               :arrow-width :arrow-width-quiet :arrow-width-entry
               :compound-radius
               :state-title-height :container-title-height
               :event-chip-min-w :event-chip-min-h :event-chip-px
               :region-title-height :pseudo-size]]
      (is (< (get vc/chart-compact k)
             (get vc/chart-regular k)
             (get vc/chart-cosy k))
          (str k " is monotonic compact < regular < cosy")))))

(deftest structured-grammar-regular-targets
  (testing "pin the regular-density targets the structured
            grammar sets as approximate floors (state title
            ~24px, container title ~26px, region title ~24px, event chip
            ~92x32, event text ~11px, action text ~9px). Pinning so a
            future tweak that drops below that grammar geometry fails CI
            rather than silently weakening the structure-first read."
    (is (= 24 (:state-title-height vc/chart-regular)))
    (is (= 26 (:container-title-height vc/chart-regular)))
    (is (= 24 (:region-title-height vc/chart-regular)))
    (is (= 92 (:event-chip-min-w vc/chart-regular)))
    (is (= 32 (:event-chip-min-h vc/chart-regular)))
    (is (= 11 (:event-chip-px vc/chart-regular)))
    (is (= 9  (:event-chip-action-px vc/chart-regular)))))

(deftest densities-catalogue-is-closed
  (testing "the `densities` Var enumerates the closed
            choice set hosts pick from. Three entries, no more, no
            less. New densities require a deliberate spec amendment."
    (is (= [:compact :regular :cosy] vc/densities))))

(deftest chart-for-density-resolves-named-densities
  (testing "every named density resolves to its map"
    (is (= vc/chart-compact (vc/chart-for-density :compact)))
    (is (= vc/chart-regular (vc/chart-for-density :regular)))
    (is (= vc/chart-cosy    (vc/chart-for-density :cosy)))))

(deftest chart-for-density-nil-is-regular
  (testing "nil (the implicit default; `:density` prop
            omitted) resolves to `chart-regular`. Hosts that never
            pass `:density` get exactly the regular chart."
    (is (= vc/chart-regular (vc/chart-for-density nil)))))

(deftest chart-for-density-unknown-throws
  (testing "an unrecognised density is a programmer
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
                 (vc/chart-for-density "regular"))) ;; string, not kw
    (is (= :rf.error/machines-viz-unknown-chart-density
           (try (vc/chart-for-density :spacious) nil
                (catch #?(:clj Exception :cljs :default) e
                  (:rf.error/id (ex-data e)))))
        "the throw carries its canonical :rf.error/id discriminator")))
