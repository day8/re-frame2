(ns day8.re-frame2-xray.theme.tokens-cljs-test
  "Pure-data tests for the Xray palette, type scale, motion seam and the
  machines-viz drift gates."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.set :as set]
            [day8.re-frame2-xray.theme.tokens :as t]
            [day8.re-frame2-machines-viz.theme.tokens :as mv]))

;; ---- palette -----------------------------------------------------------

(deftest every-palette-value-resolves-to-hex
  (testing "`themes-css` publishes each value verbatim as a CSS custom
            property, so a nil or malformed entry paints nothing."
    (doseq [[theme palette] {:dark t/dark-palette :light t/light-palette}
            [k v] palette]
      (is (re-find #"^#[0-9A-Fa-f]{3,8}$" v)
          (str theme " " k " value " v " is a # hex string")))))

(deftest light-palette-has-same-keys-as-dark
  (testing "every dark token has a light counterpart — no unresolved
            variable when the theme flips at runtime."
    (is (= (set (keys t/dark-palette))
           (set (keys t/light-palette))))))

(deftest dark-accent-is-the-published-default-accent
  (testing "`config/default-accent` publishes this hex to hosts as the
            `--rf-xray-accent` default, and the API docs state it."
    (is (= "#539bf5" (:accent t/dark-palette)))))

(deftest tokens-is-the-css-variable-surface
  (testing "inline-style reads of `(:bg-1 tokens)` resolve to
            `\"var(--rf-xray-bg-1)\"`, so the active theme's class scope
            decides which palette's hex paints."
    (is (= (into {} (for [k (keys t/dark-palette)]
                      [k (str "var(--rf-xray-" (name k) ")")]))
           t/tokens))))

(deftest with-alpha-builds-color-mix-string
  (testing "a two-digit alpha suffix is not valid CSS on a `var(--…)`
            string, so tints compose through `color-mix`."
    (is (= "color-mix(in srgb, var(--rf-xray-accent) 33%, transparent)"
           (t/with-alpha :accent 33)))))

;; ---- L4 panel accent stripe --------------------------------------------

(deftest panel-accent-is-tab-independent
  (testing "the L4 header stripe is ONE accent for every argument
            (spec/022, spec/021 §17.1.3). The rows are a sample — a tab
            id, a keyword that is no tab, and nil — not a roster; the live
            roster is pinned by
            `registry-cljs-test/focus-valid-panels-mirrors-live-dynamic-registry`."
    (doseq [tab [:machines :never-a-tab nil]]
      (is (= "var(--rf-xray-accent)" (t/panel-accent tab))
          (str "panel-accent " (pr-str tab))))))

(deftest accent-stripe-style-emits-3px-left-border
  (is (= {:border-left  "3px solid var(--rf-xray-accent)"
          :padding-left "10px"}
         (t/accent-stripe-style :machines))))

;; ---- motion seam -------------------------------------------------------

(deftest motion-durations-match-spec
  (testing "no element applies a diff flash, so there is no
            `:flash-duration-ms`; the 180ms tab cross-fade is the control
            (tools/xray spec 004 and 007 point at this pin)."
    (is (nil? (:flash-duration-ms t/motion)))
    (is (= 180 (:fade-duration-ms  t/motion)))))

(deftest duration-css-builds-calc-with-seam
  (testing "the `, 1` fallback keeps full-duration motion where
            theme/global-styles never published the scale variable."
    (is (= "calc(400ms * var(--rf-xray-motion-scale, 1))"
           (t/duration-css 400)))))

;; ---- type scale --------------------------------------------------------

(deftest type-scale-multipliers-anchor-body-at-one
  (testing "the density setting writes the body size into
            `--rf-xray-font-size`, which holds only while `:body` is 1.0."
    (is (= 1.0 (:body t/type-scale-multipliers)))))

(deftest font-size-css-builds-calc-with-var-and-fallback
  (is (= "calc(var(--rf-xray-font-size, 13px) * 0.846)"
         (t/font-size-css 0.846))))

(deftest type-scale-uses-font-size-css-helper
  (testing "every size resolves through the one `--rf-xray-font-size`
            knob, so no fixed-px entry escapes a density change."
    (doseq [[k mult] t/type-scale-multipliers]
      (is (= (t/font-size-css mult) (get t/type-scale k))
          (str k " is font-size-css of its multiplier")))))

;; ---- WCAG 2.1 contrast of the text levels ------------------------------

(defn- relative-luminance
  "WCAG 2.1 §1.4.3 relative luminance of a `#RRGGBB` colour."
  [hex]
  (let [channel (fn [i]
                  (let [c (/ #?(:clj  (Long/parseLong (subs hex i (+ i 2)) 16)
                                :cljs (js/parseInt (subs hex i (+ i 2)) 16))
                             255.0)]
                    (if (<= c 0.03928)
                      (/ c 12.92)
                      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (channel 1)) (* 0.7152 (channel 3)) (* 0.0722 (channel 5)))))

(defn- contrast-ratio
  [hex-a hex-b]
  (let [[darker lighter] (sort [(relative-luminance hex-a)
                                (relative-luminance hex-b)])]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(deftest text-levels-clear-wcag-contrast-on-dark-surfaces
  (testing "spec/022: the three text levels clear AA (4.5:1) on the dark
            surfaces, primary and secondary AAA (7:1). `:text-tertiary` is
            read at caption/micro size, where AA's small-text floor applies."
    (doseq [[fg bg floor] [[:text-tertiary  :bg-1 4.5]
                           [:text-tertiary  :bg-2 4.5]
                           [:text-secondary :bg-1 7.0]
                           [:text-primary   :bg-1 7.0]]
            :let [ratio (contrast-ratio (fg t/dark-palette) (bg t/dark-palette))]]
      (is (>= ratio floor)
          (str fg " " (fg t/dark-palette) " on " bg " " (bg t/dark-palette)
               " contrast " ratio " is below " floor)))))

;; ---- Xray ↔ machines-viz drift gates -----------------------------------

(deftest machines-viz-dark-palette-keys-subset-of-xray
  (testing "machines-viz publishes only the tokens its chart reads, a
            subset of Xray's palette. A machines-viz-only key is a token
            Xray's source of truth does not define, which the value gates
            below cannot see."
    (let [mv-only (set/difference (set (keys mv/dark-palette))
                                  (set (keys t/dark-palette)))]
      (is (empty? mv-only)
          (str "machines-viz dark-palette keys absent from Xray's: "
               (vec (sort mv-only)))))))

(deftest xray-and-machines-viz-dark-palettes-match-values
  (testing "the embedded chart paints the same hex as the surrounding
            Xray chrome for every shared key."
    (doseq [k (set/intersection (set (keys t/dark-palette))
                                (set (keys mv/dark-palette)))]
      (is (= (get t/dark-palette k) (get mv/dark-palette k))
          (str "dark-palette drift on " k)))))

(deftest xray-and-machines-viz-light-palettes-match-values
  (let [shared (set/intersection (set (keys t/light-palette))
                                 (set (keys mv/light-palette)))]
    (is (seq shared) "the light palettes share keys, so the gate is not vacuous")
    (doseq [k shared]
      (is (= (get t/light-palette k) (get mv/light-palette k))
          (str "light-palette drift on " k)))))

(deftest xray-and-machines-viz-mono-and-sans-stacks-match
  (is (= [t/mono-stack t/sans-stack] [mv/mono-stack mv/sans-stack])))

;; ---- spacing scale -----------------------------------------------------

(deftest spacing-scale-emits-px-strings
  (testing "the 4px grid spec/021 §17.1.1 publishes, as inline-style strings."
    (is (= {:gap-0 "0"    :gap-1 "4px"  :gap-2 "8px"  :gap-3 "12px"
            :gap-4 "16px" :gap-5 "20px" :gap-6 "24px"}
           t/spacing))))
