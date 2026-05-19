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

;; ---- install! idempotence ----------------------------------------------

(deftest install-bang-is-safe-without-document
  (testing "under node-test `js/document` is absent; install! must
            no-op rather than throw. The defonce guard is the surface
            for repeated calls — confirms install! returns nil for
            the caller-chained idiom."
    (is (nil? (gs/install!)))
    (is (nil? (gs/install!))
        "second call is also a no-op")))
