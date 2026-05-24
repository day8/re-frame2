(ns day8.re-frame2-xray.theme.global-styles-cljs-test
  "Tests for the Xray global-styles injection — fonts, motion +
  reduced-motion seam, per-theme CSS variables, atmospheric grain.

  The injection paths are guarded against `js/document` being absent
  (node-test runs without a DOM). Under shadow-cljs `:node-test` the
  `exists? js/document` probe is `false` so `install!` is a no-op and
  every test here is a smoke probe over the *string* surface — the
  pure-data parts of the injection (`font-faces-css`, `motion-css`,
  `themes-css`, `grain-css`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.theme.global-styles :as gs]))

;; ---- font faces (rf2-5kfxe.1 + rf2-5kfxe.1 follow-up) ------------------
;;
;; The auto-injected `@font-face` rules ship `local()`-only `src:`
;; candidates. No `url()` entry, no third-party HTTP fetch — the
;; re-frame2 testbed enforces a 'no third-party egress by default'
;; gate. Consuming projects opt-in to webfont URLs by layering their
;; own `@font-face` rules.

(deftest font-faces-css-declares-inter-and-jetbrains-mono
  (testing "both brand faces have `@font-face` declarations"
    (let [css @#'gs/font-faces-css]
      (is (string? css))
      (is (re-find #"font-family:'Inter'" css)
          "Inter is declared")
      (is (re-find #"font-family:'JetBrains Mono'" css)
          "JetBrains Mono is declared"))))

(deftest font-faces-css-includes-all-spec-weights
  (testing "spec/007 §Typography lists 400/500/600/700 across both
            sans + mono stacks. The auto-injected `@font-face` rules
            declare every weight so the `:semibold` (600) and
            `:bold` (700) tokens have explicit landings."
    (let [css @#'gs/font-faces-css]
      (doseq [w ["400" "500" "600" "700"]]
        (is (re-find (re-pattern (str "font-weight:" w)) css)
            (str "weight " w " declared"))))))

(deftest font-faces-css-uses-display-swap
  (testing "`font-display: swap` keeps the fallback rendering
            immediately and (when a consumer opt-in `url()` rule is
            layered on top) swaps to the brand face when the WOFF2
            lands — no FOIT, no perceived layout shift."
    (let [css @#'gs/font-faces-css]
      (is (re-find #"font-display:swap" css)))))

(deftest font-faces-css-declares-fraunces-display-face
  (testing "rf2-5kfxe.9 — Fraunces (the variable serif display face)
            is declared alongside Inter + JetBrains Mono. The variable
            optical-size axis isn't expressible via `local()` so the
            per-weight Fraunces family names are used (500/600/700/900
            cover the L4 panel <h1> sizing weights)."
    (let [css @#'gs/font-faces-css]
      (is (re-find #"font-family:'Fraunces'" css)
          "Fraunces is declared")
      (doseq [w ["500" "600" "700" "900"]]
        (is (re-find (re-pattern (str "font-family:'Fraunces';"
                                      "font-style:normal;"
                                      "font-weight:" w))
                     css)
            (str "Fraunces weight " w " declared"))))))

(deftest font-faces-css-is-local-only-no-third-party-egress
  (testing "rf2-5kfxe.1 follow-up — the auto-injected rules ship
            `local()`-only `src:` candidates. No `url()` entries, no
            references to fonts.googleapis.com / fonts.gstatic.com or
            any other third-party host. The re-frame2 testbed's 'no
            third-party egress by default' gate stays green; consumer
            projects opt-in to webfont URLs by layering their own
            `@font-face` rules with `url()` entries."
    (let [css @#'gs/font-faces-css]
      (is (not (re-find #"url\(" css))
          "no url() candidate in any @font-face rule")
      (is (not (re-find #"fonts\.googleapis\.com" css))
          "no Google Fonts CSS host reference")
      (is (not (re-find #"fonts\.gstatic\.com" css))
          "no Google Fonts file host reference")
      (is (re-find #"src:local\(" css)
          "local() candidates are present"))))

;; ---- motion css ---------------------------------------------------------

(deftest motion-css-declares-diff-flash-keyframes
  (testing "rf2-5kfxe.2 — diff-flash keyframes are present in the
            injected stylesheet; the animation name matches the one
            referenced by the diff renderer."
    (let [css @#'gs/motion-css]
      (is (string? css))
      (is (re-find #"@keyframes\s+rf-xray-diff-flash" css)
          "keyframes block named rf-xray-diff-flash exists"))))

(deftest motion-css-flash-decays-to-transparent
  (testing "the keyframes geometry: yellow alpha hold at the front,
            ease to transparent by 100%. The brief plateau (12%) gives
            the wash a beat instead of an aimless linear fade."
    (let [css @#'gs/motion-css]
      ;; 20% alpha at 0% + 12%, alpha 0 at 100%.
      (is (re-find #"0%\s*\{\s*background-color:\s*rgba\(251, 191, 36, 0\.20\)" css))
      (is (re-find #"12%\s*\{\s*background-color:\s*rgba\(251, 191, 36, 0\.20\)" css))
      (is (re-find #"100%\s*\{\s*background-color:\s*rgba\(251, 191, 36, 0\)" css)))))

(deftest motion-css-declares-fade-in-keyframes
  (testing "rf2-5kfxe.3 — L4 tab cross-fade keyframes are present.
            opacity 0 → 1 + a 2px translateY for the 'settle' feel."
    (let [css @#'gs/motion-css]
      (is (re-find #"@keyframes\s+rf-xray-fade-in" css))
      (is (re-find #"from\s*\{[^}]*opacity:\s*0" css))
      (is (re-find #"to\s*\{[^}]*opacity:\s*1" css))
      (is (re-find #"translateY\(2px\)" css)
          "the initial state lifts 2px below final → the new tab rises
           into place rather than appearing statically"))))

;; ---- rf2-ezx8w — machine-state pulse keyframe --------------------------

(deftest motion-css-declares-machine-pulse-keyframes
  (testing "rf2-ezx8w — the xyflow `:current` node references
            `rf-xray-machine-pulse` (see panels/machines/xyflow_style
            `:current` kind). The keyframe MUST be declared in the
            injected motion stylesheet so the animation resolves at
            paint time rather than no-op'ing."
    (let [css @#'gs/motion-css]
      (is (re-find #"@keyframes\s+rf-xray-machine-pulse" css)
          "keyframes block named rf-xray-machine-pulse exists"))))

(deftest motion-css-machine-pulse-uses-green-halo
  (testing "rf2-ezx8w / spec/021 §17.4 — the pulse halo rides the
            `:green` token (rgba 74, 222, 128) — Xray's 'final / live
            state' hue. The 0% / 100% resting frame is the inner ring;
            the midpoint expands the box-shadow halo + dips alpha so
            the green ring gently breathes. Subtle box-shadow pulse
            rather than a scale or full-opacity flicker so multi-
            machine canvases don't strobe."
    (let [css @#'gs/motion-css]
      ;; Resting frame — the halo at rest is solid green at moderate alpha.
      (is (re-find #"0%,\s*100%\s*\{\s*box-shadow:\s*0\s+0\s+0\s+0\s+rgba\(74,\s*222,\s*128"
                   css)
          "resting box-shadow uses the :green token rgba")
      ;; Midpoint — the halo expands + alpha drops toward zero.
      (is (re-find #"50%\s*\{\s*box-shadow:\s*0\s+0\s+0\s+4px\s+rgba\(74,\s*222,\s*128,\s*0\)"
                   css)
          "midpoint box-shadow expands to 4px halo with 0 alpha"))))

;; ---- rf2-ad7zx.10 — active double-circle pulse keyframe ----------------

(deftest motion-css-declares-active-double-circle-pulse
  (testing "rf2-ad7zx.10 / spec/021 §6.2 Case C — the Figma TO/current
            state is a DOUBLE-CIRCLE in the mode accent. The
            `:current` xyflow node references
            `rf-xray-machine-pulse-active`; the keyframe MUST exist so
            the animation resolves rather than no-op'ing, AND it must
            re-state the static concentric rings on every stop (since
            box-shadow sets the whole property each frame) plus add the
            breathing accent halo."
    (let [css @#'gs/motion-css]
      (is (re-find #"@keyframes\s+rf-xray-machine-pulse-active" css)
          "keyframes block named rf-xray-machine-pulse-active exists")
      ;; Concentric rings restated on the resting frame.
      (is (re-find #"inset 0 0 0 3px var\(--rf-xray-bg-1\)" css)
          "inner gap ring painted in --rf-xray-bg-1")
      (is (re-find #"inset 0 0 0 5px var\(--rf-xray-accent\)" css)
          "inner accent ring painted in --rf-xray-accent (mode-tracking)")
      ;; Breathing outer halo rides the accent var (orange Dynamic / cyan
      ;; Static) — NOT the green token the legacy pulse uses.
      (is (re-find #"color-mix\(in srgb, var\(--rf-xray-accent\)" css)
          "outer halo color-mixes the mode accent, not :green"))))

;; ---- rf2-5kfxe.5 — prefers-reduced-motion seam --------------------------

(deftest motion-css-declares-root-motion-scale-default
  (testing "rf2-5kfxe.5 — the `:root` rule sets
            --rf-xray-motion-scale: 1 so the calc()'d duration-css
            consumers run at full duration by default."
    (let [css @#'gs/motion-css]
      (is (re-find #":root\s*\{[^}]*--rf-xray-motion-scale:\s*1" css)))))

;; ---- rf2-n8i2c — font-size CSS var on :root ----------------------------

(deftest motion-css-publishes-font-size-default-on-root
  (testing "rf2-n8i2c — `:root` carries `--rf-xray-font-size: 13px`
            as the type-scale anchor. Every entry in `tokens/type-
            scale` resolves as `calc(var(--rf-xray-font-size, 13px)
            * <multiplier>)` so overriding this one variable rescales
            the entire shell in lockstep — same single-knob discipline
            TanStack Query Devtools uses (`--tsqd-font-size`)."
    (let [css @#'gs/motion-css]
      (is (re-find #":root\s*\{[^}]*--rf-xray-font-size:\s*13px" css)
          "root block carries the --rf-xray-font-size default"))))

(deftest motion-css-declares-prefers-reduced-motion-override
  (testing "rf2-5kfxe.5 — under `prefers-reduced-motion: reduce` the
            `:root` motion-scale is overridden so every downstream
            animation collapses to its end state in a single frame.
            A vanishingly small value (rather than 0) is used so
            older Chrome treats the keyframes as 'animate to
            completion in zero time' rather than 'never animate'."
    (let [css @#'gs/motion-css]
      (is (re-find #"@media\s*\(prefers-reduced-motion:\s*reduce\)" css))
      (is (re-find #"--rf-xray-motion-scale:\s*0\.001" css)
          "the override value is a hair above zero — runs to completion
           in a single frame so the end state is reached immediately"))))

;; ---- rf2-fxde5 — global :focus-visible ring ----------------------------

(deftest motion-css-declares-focus-visible-ring
  (testing "rf2-fxde5 — Xray ships a global `:focus-visible` focus ring
            scoped to the shell roots so keyboard-only users get a
            visible focus indicator. Many interactive elements set
            `:border \"none\"` and the palette input explicitly sets
            `outline: none` (palette/view line 107). Without this rule
            keyboard-only users had no reliable focus indicator anywhere
            in Xray. Sister-pattern to Story (`theme/motion.cljc:173`)."
    (let [css @#'gs/motion-css]
      (is (re-find #"\[data-testid=\"rf-xray-shell\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the Dynamic shell root")
      (is (re-find #"\[data-testid=\"rf-xray-static-shell\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the Static shell root")
      (is (re-find #"\[data-testid=\"rf-xray-palette-backdrop\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the palette backdrop (palette
           mounts outside the shell roots so it needs its own scope)"))))

(deftest motion-css-focus-visible-uses-warm-amber-token
  (testing "rf2-fxde5 — the ring colour is `#FBBF24` (token
            `:yellow` from `theme/tokens.cljc`) — warm amber matching
            Xray's design language and Story's amber focus-ring
            convention. 2px outline + 2px offset is the documented
            high-contrast hit threshold."
    (let [css @#'gs/motion-css]
      (is (re-find #"outline:\s*2px\s+solid\s+#FBBF24" css)
          "2px solid amber outline")
      (is (re-find #"outline-offset:\s*2px" css)
          "2px outline-offset so the ring doesn't graze the element"))))

;; ---- rf2-wxepo — forced-colors (Windows High Contrast Mode) ------------
;;
;; Windows HCM forces the UA palette onto every element — inline
;; `:background` + `:color` declarations are overridden and box-shadow
;; is dropped, which collapses every author-encoded signal across the
;; Xray chrome. `@media (forced-colors: active)` re-introduces the
;; signals using CSS system colour keywords (Canvas / CanvasText /
;; Highlight / Mark / GrayText / ButtonText / LinkText) which the UA
;; accepts and honours.
;;
;; Signal-preservation criterion: under HCM the operator must still
;; distinguish focused-vs-not, error-vs-success, in-flight-vs-stale,
;; primary-vs-secondary text. Each test below asserts one of those
;; signals has a system-token landing inside the forced-colors block.

(deftest motion-css-declares-forced-colors-block
  (testing "rf2-wxepo — the motion stylesheet ships a
            `@media (forced-colors: active)` block so HCM users get a
            chrome that preserves the author-encoded signals via
            system colour tokens."
    (let [css @#'gs/motion-css]
      (is (re-find #"@media\s*\(forced-colors:\s*active\)" css)
          "forced-colors media query is present"))))

(deftest motion-css-forced-colors-maps-focus-ring-to-highlight
  (testing "rf2-wxepo — the global :focus-visible amber outline
            (#FBBF24) overrides to `Highlight` under HCM so keyboard-
            only users see the user's selected-emphasis hue rather
            than the UA's forced override of the amber hex."
    (let [css @#'gs/motion-css]
      (is (re-find #"outline-color:\s*Highlight" css)
          "focus-visible outline-color is Highlight inside the block"))))

(deftest motion-css-forced-colors-maps-ribbon-stripe-to-highlight
  (testing "rf2-wxepo — the L1 ribbon's 2px left-edge mode stripe
            (runtime violet / static cyan) maps to `Highlight` so the
            mode signal is preserved under HCM."
    (let [css @#'gs/motion-css]
      (is (re-find #"\[data-testid=\"rf-xray-ribbon\"\]\s*\{[^}]*border-left-color:\s*Highlight"
                   css)
          "ribbon border-left-color is Highlight"))))

(deftest motion-css-forced-colors-distinguishes-status-accents
  (testing "rf2-wxepo — the four lifecycle-status accents map onto
            DISTINCT system tokens so the operator still tells error
            from success from in-flight from stale/paused under HCM.
            Highlight (in-flight = active), Mark (error = important
            emphasis), CanvasText (success = quiet ink), GrayText
            (stale / paused = muted)."
    (let [css @#'gs/motion-css]
      (is (re-find #"data-rf-xray-status=\"settled-error\"[^}]*Mark" css)
          "settled-error → Mark")
      (is (re-find #"data-rf-xray-status=\"in-flight\"[^}]*Highlight" css)
          "in-flight → Highlight")
      (is (re-find #"data-rf-xray-status=\"settled-success\"[^}]*CanvasText" css)
          "settled-success → CanvasText")
      (is (re-find #"data-rf-xray-status=\"stale\"" css)
          "stale rule present")
      (is (re-find #"data-rf-xray-status=\"paused-by-tool\"" css)
          "paused-by-tool rule present")
      (is (re-find #"data-rf-xray-status=\"stale\"[^{]*\{[^}]*GrayText"
                   (or (re-find #"data-rf-xray-status=\"stale\"[\s\S]*?\}" css)
                       ""))
          "stale → GrayText"))))

(deftest motion-css-forced-colors-maps-focused-row-to-highlight
  (testing "rf2-wxepo — the focused L2 event row (aria-pressed=\"true\")
            picks up a Highlight outline under HCM so the selection
            signal is preserved when the cyan border is stripped."
    (let [css @#'gs/motion-css]
      (is (re-find #"aria-pressed=\"true\"[^}]*outline:[^}]*Highlight" css)
          "focused row outline uses Highlight"))))

(deftest motion-css-forced-colors-maps-gutter-thread-to-highlight
  (testing "rf2-wxepo — the L2 row gutter's 1px causal-chain thread
            (violet inset box-shadow) and focus markers map to
            Highlight under HCM so the spine's vertical thread + the
            focus-set anchors stay visible."
    (let [css @#'gs/motion-css]
      (is (re-find #"data-testid\^=\"rf-xray-row-gutter-\"[^}]*Highlight"
                   css)
          "gutter rule uses Highlight"))))

(deftest motion-css-forced-colors-maps-panel-accent-stripe-to-canvastext
  (testing "rf2-wxepo — every L4 panel-domain accent stripe
            (violet/cyan/orange/green/yellow/red) collapses to
            CanvasText under HCM. Panels remain distinguishable by
            their tab label + content; the stripe keeps its presence
            as a rhythm marker without colour information."
    (let [css @#'gs/motion-css]
      (is (re-find #"data-testid\^=\"rf-xray-detail-panel-\"[^}]*border-left-color:\s*CanvasText"
                   css)
          "panel <h1> border-left-color is CanvasText"))))

(deftest motion-css-forced-colors-maps-interactive-icons-to-buttontext
  (testing "rf2-wxepo — the ribbon icons (settings ✕, close ✕,
            nav chevrons, focus-chip clear) map to ButtonText under
            HCM so they read as actionable controls in the HCM
            theme's interactive-ink hue."
    (let [css @#'gs/motion-css]
      (is (re-find #"data-testid=\"rf-xray-icon-settings\"" css))
      (is (re-find #"data-testid=\"rf-xray-icon-close\"" css))
      (is (re-find #"data-testid=\"rf-xray-nav-prev\"" css))
      (is (re-find #"data-testid=\"rf-xray-nav-next\"" css))
      (is (re-find #"data-testid=\"rf-xray-focus-chip-clear\"" css))
      (is (re-find #"ButtonText" css)
          "ButtonText system token is used"))))

(deftest motion-css-forced-colors-maps-anchors-to-linktext
  (testing "rf2-wxepo — hyperlinks inside the Xray shell roots
            map to LinkText under HCM so the hyperlink-ink hue is
            picked up from the HCM theme."
    (let [css @#'gs/motion-css]
      (is (re-find #"\[data-testid=\"rf-xray-shell\"\]\s*a[^}]*LinkText"
                   css)
          "anchor rule under runtime shell uses LinkText")
      (is (re-find #"\[data-testid=\"rf-xray-static-shell\"\]\s*a[^}]*LinkText"
                   css)
          "anchor rule under static shell uses LinkText"))))

(deftest motion-css-forced-colors-uses-important-to-beat-inline-styles
  (testing "rf2-wxepo — every rule inside the forced-colors block
            uses `!important` so the per-element inline-style
            declarations (the 357 call sites that paint background /
            color / border directly) are beaten on specificity.
            Inline style normally wins over external CSS; !important
            in the external CSS reverses that. A spot-check on a
            handful of declarations is enough — the block author
            convention is uniform."
    (let [css @#'gs/motion-css
          ;; Extract just the forced-colors block so we don't pick up
          ;; !important from elsewhere (there shouldn't be any, but
          ;; defensive).
          block (re-find #"@media\s*\(forced-colors:\s*active\)[\s\S]*?\n\}\n"
                        css)]
      (is (some? block)
          "forced-colors block found")
      (when block
        (is (re-find #"Highlight\s*!important" block)
            "Highlight rule carries !important")
        (is (re-find #"CanvasText\s*!important" block)
            "CanvasText rule carries !important")
        (is (re-find #"Mark\s*!important" block)
            "Mark rule carries !important")))))

;; ---- rf2-8l03l — view hover-highlight class ----------------------------
;;
;; The view-row hover-highlight (panels/reactive_panel_view
;; apply-highlight! / clear-highlight!) toggles the
;; `.rf-xray-view-highlight` class onto the hovered view's
;; `data-rf-view` DOM node. The class rule lives in `motion-css` and
;; paints a translucent PINK DIAGONAL-STRIPE barber-pole via
;; `background-image` — pink-on-fainter-pink so it reads on both light
;; and dark app surfaces. Layout-safe: background-only (no border /
;; outline / box-shadow) so hovering shifts ZERO surrounding pixels.

(deftest motion-css-declares-view-highlight-class
  (testing "rf2-8l03l — the motion stylesheet ships a
            `.rf-xray-view-highlight` rule (toggled by apply-highlight!
            / clear-highlight! in reactive_panel_view) that supersedes
            the old flat grey :bg-3 inline tint."
    (let [css @#'gs/motion-css]
      (is (re-find #"\.rf-xray-view-highlight\s*\{" css)
          "the Xray-namespaced highlight class rule is present"))))

(deftest motion-css-view-highlight-is-pink-diagonal-stripe
  (testing "rf2-8l03l — the highlight paints a translucent PINK
            DIAGONAL-STRIPE barber-pole: a 45deg repeating-linear-
            gradient of Tailwind pink-500 (rgb 236,72,153) at two
            alphas (0.30 / 0.10). Pink-on-fainter-pink (NOT white) so
            the signal reads on BOTH light and dark app backgrounds,
            translucent so the view's own content shows through."
    (let [css @#'gs/motion-css
          rule (re-find #"\.rf-xray-view-highlight\s*\{[\s\S]*?\}" css)]
      (is (some? rule) "highlight rule block found")
      (when rule
        (is (re-find #"repeating-linear-gradient" rule)
            "uses a repeating-linear-gradient (barber-pole stripe)")
        (is (re-find #"45deg" rule)
            "the stripes run at 45deg (diagonal)")
        (is (re-find #"rgba\(236,\s*72,\s*153,\s*0\.30\)" rule)
            "the brighter pink band is pink-500 at 0.30 alpha")
        (is (re-find #"rgba\(236,\s*72,\s*153,\s*0\.10\)" rule)
            "the fainter pink band is pink-500 at 0.10 alpha")
        (is (not (re-find #"255,\s*255,\s*255" rule))
            "NOT white — white would vanish on a light app background")))))

(deftest motion-css-view-highlight-is-layout-safe
  (testing "rf2-8l03l / rf2-e33ad (Mike-direction 2026-05-21) — the
            highlight is background-ONLY so hovering shifts ZERO
            surrounding pixels. A `background-image` (gradient) paints
            inside the existing box without reflow; there must be NO
            border / outline / box-shadow / box-model property in the
            rule that could perturb layout."
    (let [css @#'gs/motion-css
          rule (re-find #"\.rf-xray-view-highlight\s*\{[\s\S]*?\}" css)]
      (is (some? rule) "highlight rule block found")
      (when rule
        (is (re-find #"background-image:" rule)
            "paints via background-image (layout-safe)")
        (is (not (re-find #"(?i)\bborder\b\s*:" rule))
            "no border declaration (would shift the box)")
        (is (not (re-find #"(?i)\boutline\b\s*:" rule))
            "no outline declaration")
        (is (not (re-find #"(?i)box-shadow\s*:" rule))
            "no box-shadow declaration")
        (is (not (re-find #"(?i)\b(margin|padding|width|height)\s*:" rule))
            "no box-model property that could shift surrounding pixels")))))

;; ---- rf2-5kfxe.6 — light theme CSS variables ---------------------------

(deftest themes-css-publishes-root-defaults
  (testing "rf2-3f2di B2 — the :root block publishes the LIGHT palette
            as the default (flipped from dark) so any descendant that
            reads `var(--rf-xray-bg-1)` resolves to the light hex even
            before any theme class is attached, matching the
            authoritative reference's light-by-default render."
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#15171B" :accent-violet "#7C5CFF"}
                                  :light {:bg-1 "#F1F3F6" :accent-violet "#5538D8"}})]
      (is (re-find #":root\s*\{[^}]*--rf-xray-bg-1:\s*#F1F3F6" css)
          "root block carries the light bg-1 default")
      (is (re-find #":root\s*\{[^}]*--rf-xray-accent-violet:\s*#5538D8" css)
          "root block carries the light accent-violet default"))))

(deftest themes-css-emits-per-theme-class-blocks
  (testing "rf2-5kfxe.6 — `.rf-xray-theme-dark` and
            `.rf-xray-theme-light` each declare the full palette.
            settings/effects/apply-theme! toggles which class is on
            the shell root, switching every `var(--rf-xray-…)`
            descendant in one assignment."
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#15171B"}
                                  :light {:bg-1 "#F1F3F6"}})]
      (is (re-find #"\.rf-xray-theme-dark\s*\{[^}]*--rf-xray-bg-1:\s*#15171B" css))
      (is (re-find #"\.rf-xray-theme-light\s*\{[^}]*--rf-xray-bg-1:\s*#F1F3F6" css)))))

(deftest themes-css-has-no-per-mode-accent-swap
  (testing "rf2-ad7zx.13 — the Figma export carries a SINGLE accent
            (GitHub blue), so the themes-css block no longer emits a
            per-mode accent re-point. There must be NO `.mode-dynamic` /
            `.mode-static` rule that re-points `--rf-xray-accent` at a
            removed `accent-dynamic` / `accent-static` variable."
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#1c1c1c" :accent "#539bf5"}
                                  :light {:bg-1 "#f5f5f5" :accent "#0969da"}})]
      (is (not (re-find #"--rf-xray-accent-dynamic" css))
          "no reference to the removed accent-dynamic variable")
      (is (not (re-find #"--rf-xray-accent-static" css))
          "no reference to the removed accent-static variable")
      (is (not (re-find #"\.mode-dynamic" css))
          "no .mode-dynamic accent re-point rule")
      (is (not (re-find #"\.mode-static" css))
          "no .mode-static accent re-point rule")
      ;; The single accent is published in each theme's palette block.
      (is (re-find #"\.rf-xray-theme-dark\s*\{[^}]*--rf-xray-accent:\s*#539bf5" css))
      (is (re-find #"\.rf-xray-theme-light\s*\{[^}]*--rf-xray-accent:\s*#0969da" css)))))

(deftest themes-css-uses-rf-xray-prefix
  (testing "every variable name is namespaced under `--rf-xray-` so
            host stylesheets can't accidentally collide with Xray's
            tokens."
    (let [css (@#'gs/themes-css {:dark {:bg-1 "x" :red-deep "y" :accent-violet "z"}
                                  :light {:bg-1 "x" :red-deep "y" :accent-violet "z"}})]
      (is (re-find #"--rf-xray-bg-1" css))
      (is (re-find #"--rf-xray-red-deep" css))
      (is (re-find #"--rf-xray-accent-violet" css))
      (is (not (re-find #"(?<!--rf-xray-)bg-1:" css))
          "no unprefixed `bg-1:` declarations leaked into the CSS"))))

;; ---- rf2-5kfxe.7 — atmospheric grain overlay ---------------------------

(deftest grain-css-targets-shell-root-pseudo
  (testing "rf2-5kfxe.7 — the grain rule is scoped to the shell's
            `data-testid='rf-xray-shell'` via a `::before` pseudo-
            element. No global page-level effect; no host-app
            stylesheet contamination."
    (is (re-find #"\[data-testid=\"rf-xray-shell\"\]::before"
                 @#'gs/grain-css))))

(deftest grain-css-lifts-direct-children-above-pseudo
  (testing "rf2-5kfxe.7 — the companion rule lifts every direct child
            of the shell root to `position: relative; z-index: 1` so
            their content paints on top of the textured backdrop."
    (let [css @#'gs/grain-css]
      (is (re-find #"\[data-testid=\"rf-xray-shell\"\]\s*>\s*\*\s*\{[^}]*z-index:\s*1"
                   css))
      (is (re-find #"\[data-testid=\"rf-xray-shell\"\]\s*>\s*\*\s*\{[^}]*position:\s*relative"
                   css)))))

(deftest grain-css-embeds-svg-noise-data-uri
  (testing "rf2-5kfxe.7 — the background-image is an inline SVG
            data-URI carrying a `feTurbulence` filter (no external
            asset). The browser tiles the small SVG via
            `background-repeat: repeat` so the GPU handles the
            painting; perf cost is negligible."
    (let [css @#'gs/grain-css]
      (is (re-find #"background-image:\s*url\(\"data:image/svg\+xml" css))
      (is (re-find #"feTurbulence" css)
          "the SVG filter primitive is the noise generator")
      (is (re-find #"background-repeat:\s*repeat" css)))))

(deftest grain-css-is-subtle
  (testing "rf2-5kfxe.7 — the overlay sits at low opacity
            (between 0.02 and 0.06) so it reads as 'texture' rather
            than a visible pattern. Above 0.06 it competes with
            content; below 0.02 the browser won't render it at all
            on some displays."
    (let [css @#'gs/grain-css]
      (is (re-find #"opacity:\s*0\.03[0-9]?" css)
          "opacity is around 0.035 — texture, not pattern"))))

;; ---- rf2-846h2 — 'Use system colors' attribute selectors --------------
;;
;; The Settings popup's Theme tab stamps `data-rf-force-colors="active"`
;; on the shell root + `<html>` when the operator opts in. The motion
;; stylesheet pairs the OS-HCM `@media (forced-colors: active)` block
;; (landing under rf2-wxepo / #1700) with a sibling block whose
;; selectors carry the attribute predicate so the same system-token
;; chrome paints under operator opt-in too.

(deftest motion-css-declares-force-colors-attribute-block
  (testing "rf2-846h2 — the motion stylesheet ships a sibling block
            keyed on `[data-rf-force-colors=\"active\"]` so the
            operator opt-in path activates the same system-token
            chrome the OS HCM media query paints."
    (let [css @#'gs/motion-css]
      (is (re-find #"\[data-rf-force-colors=\"active\"\]" css)
          "attribute selector is present"))))

(deftest motion-css-force-colors-attribute-maps-focus-ring-to-highlight
  (testing "rf2-846h2 — the focus-visible ring under operator opt-in
            maps to `Highlight` (the system selection token) — mirrors
            the OS-HCM rule shape."
    (let [css @#'gs/motion-css]
      ;; The attribute-selector arm includes the focus-visible rule
      ;; pointing at outline-color: Highlight.
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*\*:focus-visible[^}]*outline-color:\s*Highlight"
            css)
          "focus ring outline-color is Highlight under attribute opt-in"))))

(deftest motion-css-force-colors-attribute-maps-status-accents
  (testing "rf2-846h2 — the four lifecycle-status accents (settled-
            error / in-flight / settled-success / stale | paused-by-
            tool) each have a system-token landing under the operator
            opt-in path so the signal survives the inline-style remap."
    (let [css @#'gs/motion-css]
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*data-rf-xray-status=\"settled-error\"[^}]*Mark"
            css)
          "error → Mark")
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*data-rf-xray-status=\"in-flight\"[^}]*Highlight"
            css)
          "in-flight → Highlight")
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*data-rf-xray-status=\"settled-success\"[^}]*CanvasText"
            css)
          "success → CanvasText")
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*data-rf-xray-status=\"stale\""
            css)
          "stale status carries a rule")
      (is (re-find
            #"data-rf-force-colors=\"active\"[^{]*data-rf-xray-status=\"paused-by-tool\""
            css)
          "paused-by-tool status carries a rule"))))

;; ---- React Flow base stylesheet (rf2-5qsxo) ----------------------------
;;
;; The Machines topology charts render via `@xyflow/react`'s
;; `<ReactFlow>`, which needs xyflow's STRUCTURAL base stylesheet
;; (`@xyflow/react/dist/style.css`) to render at all — without it nodes
;; stack full-width, edges have no path/arrowheads, the Controls are
;; bare. shadow-cljs's npm resolver won't load a `.css` via `:require`,
;; so the verbatim contents are bundled as a string and injected on the
;; Xray preload path. These tests pin the load-bearing selectors so an
;; `@xyflow/react` bump that drops/renames a structural rule is caught.

(deftest react-flow-base-css-carries-structural-rules
  (testing "rf2-5qsxo — the bundled base stylesheet carries the rules
            React Flow needs to render: absolute-positioned nodes, the
            edge path, the Controls chrome, and the dot-grid background."
    (let [css @#'gs/react-flow-base-css]
      (is (re-find #"\.react-flow__node\s*\{" css)
          "node rule present")
      (is (re-find #"\.react-flow__node\s*\{[^}]*position:\s*absolute" css)
          "nodes are absolutely positioned (the stacked-box fix)")
      (is (re-find #"\.react-flow__edge-path\s*\{" css)
          "edge path rule present")
      (is (re-find #"\.react-flow__controls\b" css)
          "Controls chrome present")
      (is (re-find #"\.react-flow__container\s*\{" css)
          "viewport container rule present")
      (is (re-find #"\.react-flow__viewport\s*\{" css)
          "viewport transform rule present"))))

(deftest react-flow-xray-theme-css-remaps-xy-vars-to-tokens
  (testing "rf2-5qsxo — the Xray override layer remaps xyflow's `--xy-*`
            custom properties to Xray tokens (dark surface) so the
            chrome xyflow paints itself reads as part of Xray, layered
            AFTER the base sheet so it wins on equal specificity."
    (let [css @#'gs/react-flow-xray-theme-css]
      (is (re-find #"--xy-node-background-color-default:" css)
          "node fill remapped")
      (is (re-find #"--xy-controls-button-background-color-default:" css)
          "Controls button background remapped")
      (is (re-find #"--xy-edge-stroke-default:" css)
          "edge stroke remapped")
      (is (re-find #"\.react-flow__attribution\s*\{\s*display:\s*none" css)
          "attribution backplate hidden against the dark canvas"))))

;; ---- install! idempotence ----------------------------------------------

(deftest install-bang-is-safe-without-document
  (testing "under node-test `js/document` is absent; install! must
            no-op rather than throw. The defonce guard is the surface
            for repeated calls — confirms install! returns nil for
            the caller-chained idiom."
    (is (nil? (gs/install!)))
    (is (nil? (gs/install!))
        "second call is also a no-op")))

;; ---- host theme override (rf2-ee38b.2) ---------------------------------

(deftest set-host-theme-css-is-safe-without-document
  (testing "rf2-ee38b.2 — under node-test `js/document` is absent;
            `set-host-theme-css!` (the impl behind the public
            `core/load-theme`) must no-op rather than throw, for any
            input — a CSS string, an empty string, or nil. DOM-bearing
            behaviour (replace-in-place, clear-on-blank) is exercised by
            the browser target; here we pin the no-DOM safety contract."
    (is (nil? (gs/set-host-theme-css! ":root { --rf-xray-bg-1: #000 }")))
    (is (nil? (gs/set-host-theme-css! "")))
    (is (nil? (gs/set-host-theme-css! nil)))))
