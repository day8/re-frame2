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

;; ---- light theme (rf2-5kfxe.6) -----------------------------------------

(deftest themes-map-carries-dark-and-light
  (testing "rf2-5kfxe.6 — the `themes` registry exposes both palettes."
    (is (contains? t/themes :dark))
    (is (contains? t/themes :light))
    (is (= t/dark-palette  (:dark  t/themes)))
    (is (= t/light-palette (:light t/themes)))))

(deftest light-palette-has-same-keys-as-dark
  (testing "every dark token has a light counterpart — no nil lookups
            when the theme flips at runtime."
    (is (= (set (keys t/dark-palette))
           (set (keys t/light-palette))))))

(deftest light-palette-inverts-surface-lightness
  (testing "spec/007 §Light theme — bg-0 #FAFBFC / bg-1 #F1F3F6 /
            bg-2 #FFFFFF. The light theme inverts the lightness of
            the dark surfaces."
    (is (= "#FAFBFC" (:bg-0 t/light-palette)))
    (is (= "#F1F3F6" (:bg-1 t/light-palette)))
    (is (= "#FFFFFF" (:bg-2 t/light-palette)))))

(deftest light-palette-darkens-accents
  (testing "spec/007 — 'accents darken slightly to maintain contrast'.
            Each accent in the light palette is a darker variant of
            the corresponding dark-palette accent (sanity-check: the
            light hexes are not the same string as their dark
            counterparts)."
    (doseq [k [:accent-violet :cyan :green :yellow :orange :red :magenta]]
      (is (not= (get t/dark-palette k)
                (get t/light-palette k))
          (str k " differs between the two palettes")))))

(deftest tokens-alias-points-at-dark-palette
  (testing "`tokens` stays a backward-compatible alias for the dark
            palette — the 357 inline-style call sites that read
            `(:bg-1 tokens)` continue to resolve to the dark hex. The
            light-theme surface is the CSS-variable layer until the
            v1.0 sweep migrates inline styles through to it."
    (is (= t/dark-palette t/tokens))))

;; ---- panel domain colours (rf2-5kfxe.8) --------------------------------

(deftest panel-domain-map-covers-every-l4-tab
  (testing "rf2-5kfxe.8 — the 7 L4 tabs each get a domain colour, so
            panels are distinguishable at a glance via the 3px left
            border on their header."
    (let [tabs #{:event :app-db :views :trace :machines :routing :issues}]
      (is (= tabs (set (keys t/panel-domain->token)))))))

(deftest panel-accent-resolves-through-tokens
  (testing "`panel-accent` is a thin wrapper:
            (get tokens (panel-domain->token tab))."
    (doseq [[tab token-kw] t/panel-domain->token]
      (is (= (get t/tokens token-kw)
             (t/panel-accent tab))
          (str "tab " tab " resolves via token " token-kw)))))

(deftest panel-accent-falls-back-for-unknown-tab
  (testing "unknown tab → :accent-violet (the brand fallback). The
            stripe always renders rather than disappearing."
    (is (= (:accent-violet t/tokens)
           (t/panel-accent :unknown-tab-kw)))
    (is (= (:accent-violet t/tokens)
           (t/panel-accent nil)))))

(deftest accent-stripe-style-emits-3px-left-border
  (testing "`accent-stripe-style` returns a merge-able style map
            carrying the 3px left border + matching padding."
    (let [s (t/accent-stripe-style :issues)]
      (is (re-find #"3px solid" (:border-left s)))
      (is (re-find #"#" (:border-left s))
          "the border ends with a hex colour")
      (is (string? (:padding-left s))
          "padding-left compensates for the border so text doesn't shift"))))

;; ---- display face (rf2-5kfxe.9) ----------------------------------------

(deftest display-stack-is-fraunces-first
  (testing "rf2-5kfxe.9 — the L4 panel title face is Fraunces (the
            variable serif). NOT Inter (already the body face) so
            the title font is a deliberate hierarchy signal rather
            than a weight bump."
    (is (string? t/display-stack))
    (is (re-find #"^Fraunces" t/display-stack)
        "Fraunces is the first face in the stack")))

(deftest display-stack-falls-back-to-system-serif
  (testing "the stack falls through to `ui-serif` (modern system
            serif) then Georgia/Cambria/Times — never a sans. The
            hierarchy contrast survives even if the WOFF2 fails to
            load."
    (is (re-find #"ui-serif" t/display-stack))
    (is (re-find #"Georgia" t/display-stack))
    (is (re-find #"serif$" t/display-stack)
        "the chain terminates at the generic `serif` family")))
