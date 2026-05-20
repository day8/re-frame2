(ns day8.re-frame2-causa.theme.global-styles-cljs-test
  "Tests for the Causa global-styles injection — fonts, motion +
  reduced-motion seam, per-theme CSS variables, atmospheric grain.

  The injection paths are guarded against `js/document` being absent
  (node-test runs without a DOM). Under shadow-cljs `:node-test` the
  `exists? js/document` probe is `false` so `install!` is a no-op and
  every test here is a smoke probe over the *string* surface — the
  pure-data parts of the injection (`font-faces-css`, `motion-css`,
  `themes-css`, `grain-css`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.global-styles :as gs]))

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
      (is (re-find #"@keyframes\s+rf-causa-diff-flash" css)
          "keyframes block named rf-causa-diff-flash exists"))))

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
      (is (re-find #"@keyframes\s+rf-causa-fade-in" css))
      (is (re-find #"from\s*\{[^}]*opacity:\s*0" css))
      (is (re-find #"to\s*\{[^}]*opacity:\s*1" css))
      (is (re-find #"translateY\(2px\)" css)
          "the initial state lifts 2px below final → the new tab rises
           into place rather than appearing statically"))))

;; ---- rf2-5kfxe.5 — prefers-reduced-motion seam --------------------------

(deftest motion-css-declares-root-motion-scale-default
  (testing "rf2-5kfxe.5 — the `:root` rule sets
            --rf-causa-motion-scale: 1 so the calc()'d duration-css
            consumers run at full duration by default."
    (let [css @#'gs/motion-css]
      (is (re-find #":root\s*\{[^}]*--rf-causa-motion-scale:\s*1" css)))))

;; ---- rf2-n8i2c — font-size CSS var on :root ----------------------------

(deftest motion-css-publishes-font-size-default-on-root
  (testing "rf2-n8i2c — `:root` carries `--rf-causa-font-size: 13px`
            as the type-scale anchor. Every entry in `tokens/type-
            scale` resolves as `calc(var(--rf-causa-font-size, 13px)
            * <multiplier>)` so overriding this one variable rescales
            the entire shell in lockstep — same single-knob discipline
            TanStack Query Devtools uses (`--tsqd-font-size`)."
    (let [css @#'gs/motion-css]
      (is (re-find #":root\s*\{[^}]*--rf-causa-font-size:\s*13px" css)
          "root block carries the --rf-causa-font-size default"))))

(deftest motion-css-declares-prefers-reduced-motion-override
  (testing "rf2-5kfxe.5 — under `prefers-reduced-motion: reduce` the
            `:root` motion-scale is overridden so every downstream
            animation collapses to its end state in a single frame.
            A vanishingly small value (rather than 0) is used so
            older Chrome treats the keyframes as 'animate to
            completion in zero time' rather than 'never animate'."
    (let [css @#'gs/motion-css]
      (is (re-find #"@media\s*\(prefers-reduced-motion:\s*reduce\)" css))
      (is (re-find #"--rf-causa-motion-scale:\s*0\.001" css)
          "the override value is a hair above zero — runs to completion
           in a single frame so the end state is reached immediately"))))

;; ---- rf2-fxde5 — global :focus-visible ring ----------------------------

(deftest motion-css-declares-focus-visible-ring
  (testing "rf2-fxde5 — Causa ships a global `:focus-visible` focus ring
            scoped to the shell roots so keyboard-only users get a
            visible focus indicator. Many interactive elements set
            `:border \"none\"` and the palette input explicitly sets
            `outline: none` (palette/view line 107). Without this rule
            keyboard-only users had no reliable focus indicator anywhere
            in Causa. Sister-pattern to Story (`theme/motion.cljc:173`)."
    (let [css @#'gs/motion-css]
      (is (re-find #"\[data-testid=\"rf-causa-shell\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the Runtime shell root")
      (is (re-find #"\[data-testid=\"rf-causa-static-shell\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the Static shell root")
      (is (re-find #"\[data-testid=\"rf-causa-palette-backdrop\"\][^,]*:focus-visible" css)
          "focus-visible rule scoped to the palette backdrop (palette
           mounts outside the shell roots so it needs its own scope)"))))

(deftest motion-css-focus-visible-uses-warm-amber-token
  (testing "rf2-fxde5 — the ring colour is `#FBBF24` (token
            `:yellow` from `theme/tokens.cljc`) — warm amber matching
            Causa's design language and Story's amber focus-ring
            convention. 2px outline + 2px offset is the documented
            high-contrast hit threshold."
    (let [css @#'gs/motion-css]
      (is (re-find #"outline:\s*2px\s+solid\s+#FBBF24" css)
          "2px solid amber outline")
      (is (re-find #"outline-offset:\s*2px" css)
          "2px outline-offset so the ring doesn't graze the element"))))

;; ---- rf2-5kfxe.6 — light theme CSS variables ---------------------------

(deftest themes-css-publishes-root-defaults
  (testing "rf2-5kfxe.6 — the :root block publishes the dark palette
            as the default so any descendant that reads
            `var(--rf-causa-bg-1)` resolves to the dark hex even
            before any theme class is attached."
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#15171B" :accent-violet "#7C5CFF"}
                                  :light {:bg-1 "#F1F3F6" :accent-violet "#5538D8"}})]
      (is (re-find #":root\s*\{[^}]*--rf-causa-bg-1:\s*#15171B" css)
          "root block carries the dark bg-1")
      (is (re-find #":root\s*\{[^}]*--rf-causa-accent-violet:\s*#7C5CFF" css)
          "root block carries the dark accent-violet"))))

(deftest themes-css-emits-per-theme-class-blocks
  (testing "rf2-5kfxe.6 — `.rf-causa-theme-dark` and
            `.rf-causa-theme-light` each declare the full palette.
            settings/effects/apply-theme! toggles which class is on
            the shell root, switching every `var(--rf-causa-…)`
            descendant in one assignment."
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#15171B"}
                                  :light {:bg-1 "#F1F3F6"}})]
      (is (re-find #"\.rf-causa-theme-dark\s*\{[^}]*--rf-causa-bg-1:\s*#15171B" css))
      (is (re-find #"\.rf-causa-theme-light\s*\{[^}]*--rf-causa-bg-1:\s*#F1F3F6" css)))))

(deftest themes-css-uses-rf-causa-prefix
  (testing "every variable name is namespaced under `--rf-causa-` so
            host stylesheets can't accidentally collide with Causa's
            tokens."
    (let [css (@#'gs/themes-css {:dark {:bg-1 "x" :red-deep "y" :accent-violet "z"}
                                  :light {:bg-1 "x" :red-deep "y" :accent-violet "z"}})]
      (is (re-find #"--rf-causa-bg-1" css))
      (is (re-find #"--rf-causa-red-deep" css))
      (is (re-find #"--rf-causa-accent-violet" css))
      (is (not (re-find #"(?<!--rf-causa-)bg-1:" css))
          "no unprefixed `bg-1:` declarations leaked into the CSS"))))

;; ---- rf2-5kfxe.7 — atmospheric grain overlay ---------------------------

(deftest grain-css-targets-shell-root-pseudo
  (testing "rf2-5kfxe.7 — the grain rule is scoped to the shell's
            `data-testid='rf-causa-shell'` via a `::before` pseudo-
            element. No global page-level effect; no host-app
            stylesheet contamination."
    (is (re-find #"\[data-testid=\"rf-causa-shell\"\]::before"
                 @#'gs/grain-css))))

(deftest grain-css-lifts-direct-children-above-pseudo
  (testing "rf2-5kfxe.7 — the companion rule lifts every direct child
            of the shell root to `position: relative; z-index: 1` so
            their content paints on top of the textured backdrop."
    (let [css @#'gs/grain-css]
      (is (re-find #"\[data-testid=\"rf-causa-shell\"\]\s*>\s*\*\s*\{[^}]*z-index:\s*1"
                   css))
      (is (re-find #"\[data-testid=\"rf-causa-shell\"\]\s*>\s*\*\s*\{[^}]*position:\s*relative"
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

;; ---- install! idempotence ----------------------------------------------

(deftest install-bang-is-safe-without-document
  (testing "under node-test `js/document` is absent; install! must
            no-op rather than throw. The defonce guard is the surface
            for repeated calls — confirms install! returns nil for
            the caller-chained idiom."
    (is (nil? (gs/install!)))
    (is (nil? (gs/install!))
        "second call is also a no-op")))
