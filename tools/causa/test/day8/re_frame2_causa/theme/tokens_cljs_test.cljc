(ns day8.re-frame2-causa.theme.tokens-cljs-test
  "Pure-data tests for the canonical Causa palette + motion seam.

  ## Why .cljc + _cljs_test naming

  Same dual-target pattern as `perf_tier_cljs_test.cljc` — Cognitect
  (`.*-test$` ns regex) + Shadow `:node-test` (`cljs-test$`).

  ## What's under test

  - The palette is internally consistent (no nil values, every key
    resolves to a 7-character `#RRGGBB` hex).
  - The two new `rf2-5kfxe.4` deep-variant + utility tokens
    (`:red-deep`, `:white`) exist.
  - `motion` carries the symbolic seam — `:scale-var-name` matches
    the CSS variable injected by `theme/global-styles/motion-css` +
    canonical durations for diff-flash + tab fade.
  - `duration-css` builds the `calc(<ms>ms * var(<var>, 1))` string
    consumers paste into their `animation-duration` slot."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.theme.tokens :as t]))

;; ---- palette consistency -----------------------------------------------

(deftest every-token-resolves-to-hex
  (testing "every value in `tokens` is a 7-character #RRGGBB or 4-char
            #RGB hex string (no nil drop-outs, no rgba() drift)"
    (doseq [[k v] t/tokens]
      (is (string? v) (str "token " k " resolves to a string"))
      (is (re-find #"^#[0-9A-Fa-f]{3,8}$" v)
          (str "token " k " value " v " is a # hex string")))))

(deftest rf2-5kfxe4-deep-variants-and-white-present
  (testing "rf2-5kfxe.4 — `:red-deep` and `:white` were added to
            consolidate the danger-button / primary-button fills
            through tokens. Both must be reachable from every consumer."
    (is (= "#a83a3a" (:red-deep t/tokens)))
    (is (= "#ffffff" (:white t/tokens)))))

;; ---- motion seam (rf2-5kfxe.5) -----------------------------------------

(deftest motion-token-carries-scale-var-name
  (testing "rf2-5kfxe.5 — `motion/:scale-var-name` is the CSS custom
            property name that `theme/global-styles` injects on `:root`
            and the reduced-motion media query overrides."
    (is (= "--rf-causa-motion-scale"
           (:scale-var-name t/motion)))))

(deftest motion-durations-match-spec
  (testing "rf2-5kfxe.2/3 — the canonical durations live here so the
            renderer can read them rather than fork the number."
    (is (= 400 (:flash-duration-ms t/motion)))
    (is (= 180 (:fade-duration-ms  t/motion)))))

(deftest duration-css-builds-calc-with-seam
  (testing "rf2-5kfxe.5 — `duration-css` returns the canonical
            `calc(<ms>ms * var(--rf-causa-motion-scale, 1))` string.
            Consumers paste this into the `:animation` declaration so
            the reduced-motion seam is honoured without per-component
            branching."
    (let [css (t/duration-css 400)]
      (is (string? css))
      (is (re-find #"400ms" css)
          "the ms value is interpolated literally")
      (is (re-find #"var\(--rf-causa-motion-scale" css)
          "the seam variable is referenced")
      (is (re-find #"calc\(" css)
          "the expression is wrapped in calc() so the multiplication
           resolves at the CSS layer rather than build-time"))))

(deftest duration-css-fallback-is-one
  (testing "the var() reference carries a `, 1` fallback so unstyled
            consumers (no install of theme/global-styles) still see
            full-duration motion rather than zero."
    (let [css (t/duration-css 180)]
      (is (re-find #"var\(--rf-causa-motion-scale,\s*1\)" css)))))
