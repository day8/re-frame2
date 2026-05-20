(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  "Pure-data tests for the machines-viz theme/tokens helpers
  (rf2-pyvmr — with-alpha; rf2-m1b88 — tag-pill-color;
   rf2-xfx6l — motion/duration-css)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
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
