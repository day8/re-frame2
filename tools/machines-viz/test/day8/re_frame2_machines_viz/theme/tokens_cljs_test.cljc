(ns day8.re-frame2-machines-viz.theme.tokens-cljs-test
  "Pure-data tests for the machines-viz theme/tokens helpers
  (rf2-pyvmr — with-alpha;
   rf2-xfx6l — motion/duration-css).

  rf2-so5b0 retired the `tag-pill-color` / `tag-pill-palette` helpers
  with the visible state-tag pill row; rf2-a2b55 reinstates BOTH
  helpers AND the pill row (positioned BELOW the state name per
  Stately graph view convention). Determinism + reserved-token tests
  for the helper restored at the end of this ns."
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

;; ---- chart semantic tokens + theme resolution (rf2-az6e2) --------------

(deftest theme-palette-resolves-dark-and-light
  (testing "rf2-az6e2 — `theme-palette` maps the `:theme` prop keyword
            to its base palette; nil / unknown falls back to the dark
            palette (the Xray default surface)."
    (is (= tokens/dark-palette  (tokens/theme-palette :dark)))
    (is (= tokens/light-palette (tokens/theme-palette :light)))
    (is (= tokens/dark-palette  (tokens/theme-palette nil)))
    (is (= tokens/dark-palette  (tokens/theme-palette :sepia)))))

(deftest chart-tokens-is-a-map-of-strings
  (testing "rf2-az6e2 — `chart-tokens` resolves every semantic chart
            role to a non-nil hex / rgba string. A nil here would
            propagate a literal 'null' into a node/edge inline style."
    (doseq [palette [tokens/dark-palette tokens/light-palette]]
      (let [ct (tokens/chart-tokens palette)]
        (is (map? ct))
        (is (every? string? (vals ct))
            "every chart-token role resolves to a string")))))

(deftest chart-tokens-default-is-dark
  (testing "rf2-az6e2 — the no-arg arity resolves the dark surface so a
            renderer that never threads a palette still paints dark."
    (is (= (tokens/chart-tokens) (tokens/chart-tokens tokens/dark-palette)))))

(deftest chart-tokens-theme-differs-between-palettes
  (testing "rf2-az6e2 — theme support is REAL: the resolved chart-token
            map differs between dark and light (a renderer reading the
            active palette paints the active theme, not a hardwired dark
            alias). The :state-body-bg role tracks the palette's bg-2,
            which inverts between themes."
    (let [dark  (tokens/chart-tokens tokens/dark-palette)
          light (tokens/chart-tokens tokens/light-palette)]
      (is (not= (:state-body-bg dark) (:state-body-bg light)))
      (is (= (:bg-2 tokens/dark-palette)  (:state-body-bg dark)))
      (is (= (:bg-2 tokens/light-palette) (:state-body-bg light))))))

(deftest chart-tokens-runtime-accents-reserved
  (testing "rf2-az6e2 — structure wins over annotation colour: the
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

(deftest chart-label-stack-is-sans
  (testing "rf2-az6e2 — the chart-label font token is sans (structure-
            first reading); recorded decision: mono retained only for
            the raw-EDN context panel."
    (is (= tokens/sans-stack tokens/chart-label-stack))))

;; ---- tag-pill colour rotation (rf2-m1b88; rf2-a2b55 restored) ----------

(deftest tag-pill-color-is-deterministic
  (testing "rf2-m1b88 — `tag-pill-color` resolves the same tag id to
            the same palette entry across calls, so the human eye can
            track 'tag X = blue pill' across renders. Pure data →
            keyword; the same id MUST resolve to the same hue."
    (let [tag :ws/connecting]
      (is (= (tokens/tag-pill-color tag)
             (tokens/tag-pill-color tag))))))

(deftest tag-pill-color-skips-reserved-tokens
  (testing "rf2-m1b88 — `:red` / `:accent` / `:info` are reserved for
            chart semantics (cancellation glyph; initial-state +
            FROM-highlight; TO-highlight + active). The palette
            rotation MUST NOT land on any of those — a state-tag pill
            sharing a hue with an active-state border would read as
            'this state is active' on a tag that has nothing to do
            with activity. Pinned across a small sweep of tag ids."
    (let [reserved #{:red :accent :info}]
      (doseq [tag [:a :b :c :ws/active :ws/connecting :foo/bar
                   :idle :running :error :timeout :loaded]]
        (is (not (contains? reserved (tokens/tag-pill-color tag)))
            (str "tag " tag " maps to a reserved chart-semantic token"))))))
