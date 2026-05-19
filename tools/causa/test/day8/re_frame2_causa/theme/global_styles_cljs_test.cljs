(ns day8.re-frame2-causa.theme.global-styles-cljs-test
  "Tests for the Causa global-styles injection — fonts today; motion +
  reduced-motion seam + grain + display-face land in later cluster
  commits and add cases here.

  The injection paths are guarded against `js/document` being absent
  (node-test runs without a DOM). Under shadow-cljs `:node-test` the
  `exists? js/document` probe is `false` so `install!` is a no-op and
  every test here is a smoke probe over the *string* surface — the
  pure-data parts of the injection (`fonts-href`, `global-css`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.global-styles :as gs]))

;; ---- font href ----------------------------------------------------------

(deftest fonts-href-loads-inter-and-jetbrains-mono
  (testing "the Google Fonts URL requests both brand faces"
    (let [href @#'gs/fonts-href]
      (is (string? href))
      (is (re-find #"family=Inter" href)
          "Inter is requested")
      (is (re-find #"family=JetBrains\+Mono" href)
          "JetBrains Mono is requested"))))

(deftest fonts-href-includes-all-spec-weights
  (testing "spec/007 §Typography lists 400/500/600/700 across both
            stacks (the variable WOFF2 weights). The Google Fonts URL
            requests every weight so none silently fall back to the
            nearest-installed weight."
    (let [href @#'gs/fonts-href]
      (doseq [w ["400" "500" "600" "700"]]
        (is (re-find (re-pattern w) href)
            (str "weight " w " requested"))))))

(deftest fonts-href-uses-display-swap
  (testing "`display=swap` keeps the fallback rendering immediately
            and swaps to the brand face when the WOFF2 lands (no FOIT,
            no perceived layout shift). Spec/007 §Typography is silent
            on the loading-display policy; `swap` is the lowest-UX-
            disruption default for an info-dense devtool."
    (let [href @#'gs/fonts-href]
      (is (re-find #"display=swap" href)))))

(deftest fonts-href-loads-fraunces-display-face
  (testing "rf2-5kfxe.9 — Fraunces (the variable serif display face)
            is requested alongside Inter + JetBrains Mono. Variable
            axes opsz (optical size) + wght so the renderer can pick
            a display-tuned glyph shape at panel-title sizes."
    (let [href @#'gs/fonts-href]
      (is (re-find #"family=Fraunces" href))
      (is (re-find #"opsz,wght@" href)
          "variable axes are requested (opsz + wght)"))))

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
