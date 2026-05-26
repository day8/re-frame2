(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  "Pure-data tests for the machines-viz theme/tokens helpers
  (rf2-pyvmr — with-alpha;
   rf2-xfx6l — motion/duration-css).

  rf2-so5b0 — the `tag-pill-color` / `tag-pill-palette` tests retired
  with the visible state-tag pill row; tags now surface as a hover
  tooltip + data-tags attr on the state-node body (no per-tag chip →
  no per-tag colour to map)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

;; ---- with-alpha (rf2-pyvmr) --------------------------------------------

(deftest with-alpha-builds-rgba-string-from-hex-token
  (testing "with-alpha resolves :info (#79c0ff) to rgba(121, 192, 255, alpha)"
    (let [s (tokens/with-alpha :info 0.5)]
      (is (= "rgba(121, 192, 255, 0.5)" s)))))

(deftest with-alpha-resolves-zero-alpha
  (testing "with-alpha accepts alpha=0 — used by callers that compute
            opacity dynamically"
    (is (re-find #"^rgba\(\d+, \d+, \d+, 0\)$"
                 (tokens/with-alpha :accent 0)))))

(deftest with-alpha-roundtrips-palette-shift
  (testing "with-alpha resolves through a custom palette so callers
            (theming hosts) can swap the palette without forking the
            chart"
    (let [custom {:info "#000000"}]
      (is (= "rgba(0, 0, 0, 0.25)"
             (tokens/with-alpha :info 0.25 custom))))))

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
  (testing "rf2-ad7zx.13 — light theme bg-0 is the LIGHTEST recess
            (#fbfbfb) while dark theme bg-0 is the DEEPEST canvas
            (#161616)."
    (is (= "#161616" (:bg-0 tokens/dark-palette)))
    (is (= "#fbfbfb" (:bg-0 tokens/light-palette)))
    (is (= "#e6edf3" (:text-primary tokens/dark-palette)))
    (is (= "#24292f" (:text-primary tokens/light-palette)))))

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
    (let [s (tokens/with-alpha :info 0.5 tokens/light-palette)]
      ;; Light :info is #0550ae → rgba(5, 80, 174, 0.5)
      (is (= "rgba(5, 80, 174, 0.5)" s)))))

;; ---- css-var (rf2-uv1on) -----------------------------------------------

(deftest css-var-resolves-to-var-with-hex-fallback
  (testing "css-var builds var(--rf-xray-<key>, <hex>) so a host that
            publishes the Xray CSS custom-property surface drives
            light + dark, while a standalone embed degrades to the
            dark-palette hex"
    (is (= "var(--rf-xray-green, #3fb950)" (tokens/css-var :green)))
    (is (= "var(--rf-xray-text-tertiary, #8b949e)"
           (tokens/css-var :text-tertiary)))))

(deftest css-var-name-matches-xray-convention
  (testing "the variable name mirrors Xray's `var(--rf-xray-<key>)`
            so the chart + host paint from ONE :root palette"
    (is (str/starts-with? (tokens/css-var :info)
                          "var(--rf-xray-info"))))

(deftest css-var-falls-back-to-supplied-palette-hex
  (testing "css-var resolves the fallback hex from the supplied palette
            arg (light theme), not just the dark default"
    (is (= "var(--rf-xray-info, #0550ae)"
           (tokens/css-var :info tokens/light-palette)))))

(deftest css-var-no-fallback-for-unknown-key
  (testing "an unknown token has no hex → bare var() (host MUST define
            it; no garbage fallback)"
    (is (= "var(--rf-xray-not-a-token)" (tokens/css-var :not-a-token)))))

;; ---- motion seam (rf2-xfx6l) -------------------------------------------

(deftest duration-css-interpolates-scale-var
  (testing "duration-css produces a calc() string that interpolates
            the --rf-xray-motion-scale custom property"
    (is (= "calc(2000ms * var(--rf-xray-motion-scale, 1))"
           (tokens/duration-css 2000)))))

(deftest motion-publishes-canonical-durations
  (testing "motion catalogues the glow ms so the chart can read it
            without forking the numbers. The `:pulse-duration-ms`
            entry was retired with rf2-2sez0 (heartbeat-pulse
            animation removed 2026-05-20)."
    (is (pos? (:glow-duration-ms tokens/motion)))
    (is (nil? (:pulse-duration-ms tokens/motion))
        "pulse-duration-ms removed (rf2-2sez0)")))
