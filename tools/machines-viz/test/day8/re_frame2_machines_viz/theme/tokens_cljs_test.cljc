(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  "Pure-data tests for the machines-viz theme/tokens helpers
  (rf2-pyvmr — with-alpha; rf2-m1b88 — tag-pill-color;
   rf2-xfx6l — motion/duration-css)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

;; ---- with-alpha (rf2-pyvmr) --------------------------------------------

(deftest with-alpha-builds-rgba-string-from-hex-token
  (testing "with-alpha resolves :cyan (#43C3D0) to rgba(67, 195, 208, alpha)"
    (let [s (tokens/with-alpha :cyan 0.5)]
      (is (= "rgba(67, 195, 208, 0.5)" s)))))

(deftest with-alpha-resolves-zero-alpha
  (testing "with-alpha accepts alpha=0 — used by callers that compute
            opacity dynamically"
    (is (re-find #"^rgba\(\d+, \d+, \d+, 0\)$"
                 (tokens/with-alpha :accent-violet 0)))))

(deftest with-alpha-roundtrips-palette-shift
  (testing "with-alpha resolves through a custom palette so callers
            (theming hosts) can swap the palette without forking the
            chart"
    (let [custom {:cyan "#000000"}]
      (is (= "rgba(0, 0, 0, 0.25)"
             (tokens/with-alpha :cyan 0.25 custom))))))

;; ---- tag-pill-color (rf2-m1b88) ----------------------------------------

(deftest tag-pill-color-deterministic
  (testing "tag-pill-color is deterministic — same tag → same palette
            entry across renders"
    (is (= (tokens/tag-pill-color :risky)   (tokens/tag-pill-color :risky)))
    (is (= (tokens/tag-pill-color :paid)    (tokens/tag-pill-color :paid)))
    (is (= (tokens/tag-pill-color :auth/ok) (tokens/tag-pill-color :auth/ok)))))

(deftest tag-pill-color-skips-reserved-tokens
  (testing "tag-pill-color never returns :red or :accent-violet —
            both are reserved for chart semantics"
    ;; Walk every palette entry; assert none is :red / :accent-violet.
    (is (every? (fn [t]
                  (not (#{:red :accent-violet} (tokens/tag-pill-color t))))
                [:risky :paid :auth/ok :final :loading :error
                 :rec :checkout :session/active :flow/start]))))

;; ---- light palette (rf2-usord) -----------------------------------------

(deftest light-palette-exists-and-mirrors-dark-shape
  (testing "rf2-usord — light-palette exposes the same keys as
            dark-palette so callers can swap palettes without losing
            tokens. The HEX values are independent (light theme inverts
            lightness + darkens accents for contrast on a white
            canvas)."
    (is (= (set (keys tokens/dark-palette))
           (set (keys tokens/light-palette))))))

(deftest light-palette-inverts-surface-lightness
  (testing "rf2-usord — light theme bg-0 is the LIGHTEST recess (FAFBFC)
            while dark theme bg-0 is the DEEPEST canvas (0E0F12)."
    (is (= "#0E0F12" (:bg-0 tokens/dark-palette)))
    (is (= "#FAFBFC" (:bg-0 tokens/light-palette)))
    (is (= "#E8EAF0" (:text-primary tokens/dark-palette)))
    (is (= "#15171B" (:text-primary tokens/light-palette)))))

(deftest palettes-map-exposes-both-themes
  (testing "rf2-usord — palettes map keys both themes by name so the
            substrate-adapter MachineChart re-exports can resolve via
            `(get palettes theme)` without per-theme conditionals."
    (is (= tokens/dark-palette  (:dark  tokens/palettes)))
    (is (= tokens/light-palette (:light tokens/palettes)))))

(deftest with-alpha-resolves-through-custom-palette
  (testing "rf2-usord — with-alpha already supports a custom palette
            arg; this test pins the LIGHT-palette path so chart code
            can resolve tints against the light theme without forking
            the helper."
    (let [s (tokens/with-alpha :cyan 0.5 tokens/light-palette)]
      ;; Light :cyan is #2A8B96 → rgba(42, 139, 150, 0.5)
      (is (= "rgba(42, 139, 150, 0.5)" s)))))

;; ---- css-var (rf2-uv1on) -----------------------------------------------

(deftest css-var-resolves-to-var-with-hex-fallback
  (testing "css-var builds var(--rf-causa-<key>, <hex>) so a host that
            publishes the Causa CSS custom-property surface drives
            light + dark, while a standalone embed degrades to the
            dark-palette hex"
    (is (= "var(--rf-causa-green, #4ADE80)" (tokens/css-var :green)))
    (is (= "var(--rf-causa-text-tertiary, #8990A0)"
           (tokens/css-var :text-tertiary)))))

(deftest css-var-name-matches-causa-convention
  (testing "the variable name mirrors Causa's `var(--rf-causa-<key>)`
            so the chart + host paint from ONE :root palette"
    (is (str/starts-with? (tokens/css-var :cyan)
                          "var(--rf-causa-cyan"))))

(deftest css-var-falls-back-to-supplied-palette-hex
  (testing "css-var resolves the fallback hex from the supplied palette
            arg (light theme), not just the dark default"
    (is (= "var(--rf-causa-cyan, #2A8B96)"
           (tokens/css-var :cyan tokens/light-palette)))))

(deftest css-var-no-fallback-for-unknown-key
  (testing "an unknown token has no hex → bare var() (host MUST define
            it; no garbage fallback)"
    (is (= "var(--rf-causa-not-a-token)" (tokens/css-var :not-a-token)))))

;; ---- motion seam (rf2-xfx6l) -------------------------------------------

(deftest duration-css-interpolates-scale-var
  (testing "duration-css produces a calc() string that interpolates
            the --rf-causa-motion-scale custom property"
    (is (= "calc(2000ms * var(--rf-causa-motion-scale, 1))"
           (tokens/duration-css 2000)))))

(deftest motion-publishes-canonical-durations
  (testing "motion catalogues the glow ms so the chart can read it
            without forking the numbers. The `:pulse-duration-ms`
            entry was retired with rf2-2sez0 (heartbeat-pulse
            animation removed 2026-05-20)."
    (is (pos? (:glow-duration-ms tokens/motion)))
    (is (nil? (:pulse-duration-ms tokens/motion))
        "pulse-duration-ms removed (rf2-2sez0)")))
