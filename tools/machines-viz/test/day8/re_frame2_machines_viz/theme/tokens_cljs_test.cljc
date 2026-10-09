(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

(deftest with-alpha-resolves-a-palette-token-to-rgba
  (is (= "rgba(121, 192, 255, 0.5)" (tokens/with-alpha :info 0.5))
      "the two-arity reads the dark palette")
  (testing "the three-arity resolves through the supplied palette; nil alpha
            returns the solid value, an absent token nil, and an unparseable
            value passes through rather than blanking the tint"
    (let [palette {:hex "#79c0ff" :rgba "rgba(1, 2, 3, 0.4)"}]
      (doseq [[k alpha expected] [[:hex    0.25 "rgba(121, 192, 255, 0.25)"]
                                  [:hex    0    "rgba(121, 192, 255, 0)"]
                                  [:hex    nil  "#79c0ff"]
                                  [:absent 0.5  nil]
                                  [:rgba   0.5  "rgba(1, 2, 3, 0.4)"]]]
        (is (= expected (tokens/with-alpha k alpha palette)) (str k " " alpha))))))

(deftest light-palette-exists-and-mirrors-dark-shape
  (is (= (set (keys tokens/dark-palette))
         (set (keys tokens/light-palette)))))

(deftest css-var-resolves-to-var-with-hex-fallback
  (testing "the Xray custom property with the palette hex as fallback (dark by
            default), and no fallback for an unknown token"
    (is (= "var(--rf-xray-green, #3fb950)" (tokens/css-var :green)))
    (is (= "var(--rf-xray-info, #0550ae)" (tokens/css-var :info tokens/light-palette)))
    (is (= "var(--rf-xray-not-a-token)" (tokens/css-var :not-a-token)))))

(deftest duration-css-interpolates-scale-var
  (is (= "calc(2000ms * var(--rf-xray-motion-scale, 1))"
         (tokens/duration-css 2000))))

(deftest glow-animation-is-a-single-motion-scaled-flash
  (testing "spec/Principles.md §chart animation: one finite flash through the
            reduced-motion seam, holding its end state"
    (let [css (tokens/glow-animation-css)]
      (is (str/includes? css "mv-chart-transition-glow")
          "names the keyframe chart-stylesheet defines")
      (is (str/includes? css (tokens/duration-css (:glow-duration-ms tokens/motion))))
      (is (str/includes? css "forwards"))
      (is (not (str/includes? css "infinite"))))))

(deftest theme-palette-resolves-dark-and-light
  (is (= [tokens/light-palette tokens/dark-palette tokens/dark-palette]
         (map tokens/theme-palette [:light nil :sepia]))
      "nil and an unknown theme fall back to dark"))

(deftest chart-tokens-keep-structure-neutral-and-runtime-hues-distinct
  (doseq [palette [tokens/dark-palette tokens/light-palette]
          :let [ct (tokens/chart-tokens palette)]]
    (is (every? string? (vals ct)) "a nil role would paint a literal null")
    (is (= [(:border-default palette) (:border-default palette)
            (:error palette) (:magenta-pink palette)]
           (map ct [:container-border :region-border :final-error :edge-guard-blocked]))
        "structural borders are neutral; the error ring and the blocked edge
         take the error and pink hues")
    (is (apply distinct? (map ct [:edge-guard-blocked :edge-fired :edge-active
                                  :edge-quiet :final-error :final])))))

(deftest chart-label-stack-is-sans
  (is (= tokens/sans-stack tokens/chart-label-stack)))

(deftest edge-color-follows-the-precedence-ladder
  (testing "blocked > fired > focused / active > quiet"
    (let [ct (tokens/chart-tokens)]
      (doseq [[flags role]
              [[{:blocked? true :fired? true :focused? true :active? true} :edge-guard-blocked]
               [{:fired? true :focused? true :active? true}                :edge-fired]
               [{:focused? true}                                           :edge-active]
               [{:active? true}                                            :edge-active]
               [{}                                                         :edge-quiet]]]
        (is (= (role ct) (tokens/edge-color ct flags)) (pr-str flags))))))
