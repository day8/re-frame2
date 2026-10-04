(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  "Pure-data tests for the machines-viz theme/tokens helpers
  (with-alpha; motion/duration-css).

  `chart.nodes/tag-pill` renders every chip in one neutral style
  (structure wins over annotation colour for the topology view), so the
  tokens carry no per-tag colour rotation. The shared `edge-color` stroke
  + arrowhead colour helper is pinned at the end of this ns."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]))

;; ---- with-alpha --------------------------------------------------------

(deftest with-alpha-builds-rgba-string-from-hex-token
  (testing "with-alpha resolves :info (#79c0ff) to rgba(121, 192, 255, alpha)"
    (let [s (tokens/with-alpha :info 0.5)]
      (is (= "rgba(121, 192, 255, 0.5)" s)))))

(deftest with-alpha-resolves-zero-alpha
  (testing "with-alpha accepts alpha=0 — used by callers that compute
            opacity dynamically"
    (is (re-find #"^rgba\(\d+, \d+, \d+, 0\)$"
                 (tokens/with-alpha :accent 0)))))

(deftest with-alpha-resolves-through-supplied-palette
  (testing "with-alpha resolves through a custom palette so callers
            (theming hosts) can swap the palette without forking the
            chart"
    (let [custom {:info "#000000"}]
      (is (= "rgba(0, 0, 0, 0.25)"
             (tokens/with-alpha :info 0.25 custom))))))

(deftest with-alpha-fallbacks
  (testing "the defensive arms: a nil alpha returns the token's solid
            value, an absent token returns nil, and a value that is not a
            parseable hex passes through unchanged rather than blanking
            the tint"
    (let [palette {:hex "#79c0ff" :rgba "rgba(1, 2, 3, 0.4)"}]
      (is (= "#79c0ff" (tokens/with-alpha :hex nil palette)) "nil alpha → solid hex")
      (is (nil? (tokens/with-alpha :absent 0.5 palette)) "absent token → nil")
      (is (= "rgba(1, 2, 3, 0.4)" (tokens/with-alpha :rgba 0.5 palette))
          "unparseable hex → the value unchanged"))))

;; ---- light palette -----------------------------------------------------

(deftest light-palette-exists-and-mirrors-dark-shape
  (testing "light-palette exposes the same keys as
            dark-palette so callers can swap palettes without losing
            tokens. The HEX values are independent (light theme inverts
            lightness + darkens accents for contrast on a white
            canvas)."
    (is (= (set (keys tokens/dark-palette))
           (set (keys tokens/light-palette))))))

;; ---- css-var -----------------------------------------------------------

(deftest css-var-resolves-to-var-with-hex-fallback
  (testing "css-var builds var(--rf-xray-<key>, <hex>) so a host that
            publishes the Xray CSS custom-property surface drives
            light + dark, while a standalone embed degrades to the
            dark-palette hex"
    (is (= "var(--rf-xray-green, #3fb950)" (tokens/css-var :green)))
    (is (= "var(--rf-xray-text-tertiary, #8b949e)"
           (tokens/css-var :text-tertiary)))))

(deftest css-var-falls-back-to-supplied-palette-hex
  (testing "css-var resolves the fallback hex from the supplied palette
            arg (light theme), not just the dark default"
    (is (= "var(--rf-xray-info, #0550ae)"
           (tokens/css-var :info tokens/light-palette)))))

(deftest css-var-no-fallback-for-unknown-key
  (testing "an unknown token has no hex → bare var() (host MUST define
            it; no garbage fallback)"
    (is (= "var(--rf-xray-not-a-token)" (tokens/css-var :not-a-token)))))

;; ---- motion seam -------------------------------------------------------

(deftest duration-css-interpolates-scale-var
  (testing "duration-css produces a calc() string that interpolates
            the --rf-xray-motion-scale custom property"
    (is (= "calc(2000ms * var(--rf-xray-motion-scale, 1))"
           (tokens/duration-css 2000)))))

(deftest motion-publishes-canonical-durations
  (testing "motion catalogues the glow ms so the chart can read it
            without forking the numbers. It carries no
            `:pulse-duration-ms` entry: the chart has no heartbeat-pulse
            animation."
    (is (pos? (:glow-duration-ms tokens/motion)))
    (is (nil? (:pulse-duration-ms tokens/motion))
        "no pulse-duration-ms entry")))

;; ---- fired/focused glow is event-driven + finite -----------------------
;;
;; The fired/focused edge + event-node glow MUST NOT loop. Per
;; `spec/Principles.md` §chart animation it fires ONE iteration on a
;; changed epoch token, then settles to a stable end-state while the
;; static fired/focused affordance (stroke/hue/glow) persists. The
;; duration MUST flow through the `--rf-xray-motion-scale` seam so
;; `prefers-reduced-motion: reduce` collapses it to a settle frame.
;; A shorthand like `mv-chart-transition-glow 720ms ease-out infinite`
;; would violate all three; these pins guard against it.

(deftest glow-animation-plumbs-motion-scale
  (testing "the glow duration interpolates the
            `--rf-xray-motion-scale` custom property (the canonical
            reduced-motion seam), so `prefers-reduced-motion: reduce`
            collapses the flash to a single settle frame."
    (let [css (tokens/glow-animation-css)]
      (is (str/includes? css "var(--rf-xray-motion-scale, 1)")
          "duration flows through the motion-scale seam")
      (is (str/includes? css (str (:glow-duration-ms tokens/motion) "ms"))
          "uses the canonical glow-duration-ms (no forked literal)"))))

(deftest glow-animation-settles-to-stable-end-state
  (testing "the glow plays ONE iteration and holds its
            final keyframe (`forwards`), settling to a stable
            end-state rather than reverting / re-pulsing."
    (let [css (tokens/glow-animation-css)]
      (is (str/includes? css "forwards")
          "`forwards` holds the end-state after the single flash")
      (is (str/includes? css "mv-chart-transition-glow")
          "names the chart-stylesheet keyframe (no name drift)")
      ;; one iteration: the shorthand carries no explicit iteration-
      ;; count token (the CSS default of 1 applies) — neither
      ;; `infinite` nor a numeric count.
      (is (not (str/includes? css "infinite"))
          "no `infinite` — exactly one flash (default iteration-count 1)"))))

;; ---- chart semantic tokens + theme resolution --------------------------

(deftest theme-palette-resolves-dark-and-light
  (testing "`theme-palette` maps the `:theme` prop keyword
            to its base palette; nil / unknown falls back to the dark
            palette (the Xray default surface)."
    (is (= tokens/dark-palette  (tokens/theme-palette :dark)))
    (is (= tokens/light-palette (tokens/theme-palette :light)))
    (is (= tokens/dark-palette  (tokens/theme-palette nil)))
    (is (= tokens/dark-palette  (tokens/theme-palette :sepia)))))

(deftest chart-tokens-is-a-map-of-strings
  (testing "`chart-tokens` resolves every semantic chart
            role to a non-nil hex / rgba string. A nil here would
            propagate a literal 'null' into a node/edge inline style."
    (doseq [palette [tokens/dark-palette tokens/light-palette]]
      (let [ct (tokens/chart-tokens palette)]
        (is (map? ct))
        (is (every? string? (vals ct))
            "every chart-token role resolves to a string")))))

(deftest chart-tokens-runtime-accents-reserved
  (testing "structure wins over annotation colour: the
            STATIC structural roles (container/region border, state
            border, event-chip) are neutral (palette border/bg tokens),
            while the accent (`:focus`) + active (`:active`) roles are
            reserved for RUNTIME state. The container border MUST NOT be
            the accent hue (no saturated accent wash on a resting
            compound)."
    (let [ct (tokens/chart-tokens tokens/dark-palette)]
      (is (= (:border-default tokens/dark-palette) (:container-border ct)))
      (is (= (:border-default tokens/dark-palette) (:region-border ct)))
      (is (= (:info tokens/dark-palette)   (:active ct)))
      (is (= (:accent tokens/dark-palette) (:focus ct)))
      (is (not= (:accent tokens/dark-palette) (:container-border ct))))))

(deftest chart-tokens-final-error-is-error-hue
  (testing "`:final-error` (the error-final OUTER-RING hue)
            resolves to the palette's `:error` token in BOTH themes, and
            is DISTINCT from the quiet success-final ring role (`:final`).
            A static error-hue signalling an `:error?` terminal — a
            re-frame2 extension routing the parent's `:on-error` — that the
            chart must not hide."
    (doseq [palette [tokens/dark-palette tokens/light-palette]]
      (let [ct (tokens/chart-tokens palette)]
        (is (= (:error palette) (:final-error ct))
            "error-final ring tracks the palette error hue")
        (is (not= (:final ct) (:final-error ct))
            "the error ring is visually distinct from the quiet success ring")))))

(deftest chart-label-stack-is-sans
  (testing "the chart-label font token is sans (structure-
            first reading); mono is reserved for the raw-EDN context
            panel."
    (is (= tokens/sans-stack tokens/chart-label-stack))))

;; ---- edge-color — shared stroke + arrowhead colour ---------------------
;;
;; `tokens/edge-color` is the SINGLE source both `chart.edges/edge-stroke`
;; (the SVG path) and `chart.projection` (the arrowhead `:markerEnd`
;; colour) route through, so a stroke + its arrowhead cannot disagree.
;; Ordering (XState/Stately gold standard, plus the guard-blocked hue):
;; blocked > fired > focused/active > quiet.

(deftest edge-color-follows-the-precedence-ladder
  (testing "`blocked?` wins outright, so an attempted-and-rejected edge paints
            the pink guard-blocked hue; `fired?` beats focused / active, so a
            fired-this-epoch edge reads as 'what just happened'; focused OR
            active share the active hue; no flags, or all false, is quiet"
    (let [ct (tokens/chart-tokens)]
      (doseq [[label flags role]
              [["blocked alone"                  {:blocked? true}                   :edge-guard-blocked]
               ["blocked beats fired + focused + active"
                {:blocked? true :fired? true :focused? true :active? true}         :edge-guard-blocked]
               ["fired beats focused + active"   {:fired? true :focused? true :active? true} :edge-fired]
               ["fired alone"                    {:fired? true}                     :edge-fired]
               ["unblocked fired"                {:blocked? false :fired? true}     :edge-fired]
               ["focused"                        {:focused? true}                   :edge-active]
               ["active"                         {:active? true}                    :edge-active]
               ["focused + active"               {:focused? true :active? true}     :edge-active]
               ["unblocked focused"              {:blocked? false :focused? true}   :edge-active]
               ["no flags"                       {}                                 :edge-quiet]
               ["all flags false"                {:fired? false :focused? false :active? false} :edge-quiet]
               ["unblocked, nothing else"        {:blocked? false}                  :edge-quiet]]]
        (is (= (role ct) (tokens/edge-color ct flags)) label)))))

;; ---- guard-blocked edge hue --------------------------------------------

(deftest chart-tokens-guard-blocked-is-pink
  (testing "`:edge-guard-blocked` resolves to the palette's
            PINK `:magenta-pink` token in BOTH themes, and is DISTINCT
            from the fired/active/quiet edge hues AND the red `:final-error`
            terminal ring (a pink 'blocked' signal, not a red error)."
    (doseq [palette [tokens/dark-palette tokens/light-palette]]
      (let [ct (tokens/chart-tokens palette)]
        (is (= (:magenta-pink palette) (:edge-guard-blocked ct))
            "guard-blocked edge tracks the palette pink hue")
        (is (not= (:edge-fired ct)  (:edge-guard-blocked ct)))
        (is (not= (:edge-active ct) (:edge-guard-blocked ct)))
        (is (not= (:edge-quiet ct)  (:edge-guard-blocked ct)))
        (is (not= (:final-error ct) (:edge-guard-blocked ct))
            "pink blocked-edge is distinct from the red error-final ring")))))
