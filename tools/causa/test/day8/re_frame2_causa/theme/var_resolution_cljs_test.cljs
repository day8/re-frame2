(ns day8.re-frame2-causa.theme.var-resolution-cljs-test
  "Pins the rf2-on4cm contract: every Causa inline `:style` site reads
  from `theme/tokens` resolves to a `var(--rf-causa-<key>)` CSS-variable
  reference, NOT a literal hex string.

  ## Why this matters

  Pre-rf2-on4cm the `tokens` map was an alias for `dark-palette` — every
  inline `:style` declaration that referenced `(:bg-1 tokens)` painted
  the dark-palette hex regardless of the active theme class. The
  light-theme class toggle was wired (`rf-causa-theme-light` on the
  shell root) and the CSS-variable block was emitted, but inline styles
  ignored both, so light mode rendered as paint-only-the-edges broken.

  Post rf2-on4cm `tokens` is a CSS-variable map (`{:bg-1
  \"var(--rf-causa-bg-1)\"}`), so every inline-style call site flows
  through the theme's class scope. The active theme class on the shell
  root decides which palette's hex actually paints.

  ## What this test pins

  Three layers:

    1. **`tokens` is a var-map** — every entry resolves to
       `var(--rf-causa-<key>)`. Pinned in `tokens_cljs_test.cljc`
       too; reinforced here to keep the contract co-located.

    2. **Helpers route through the var-map** — `panel-accent`,
       `accent-stripe-style`, `panel-icon-style`, `tier-colour`,
       `severity-colour`, `op-type-colour`, `event-status-colour` —
       every public colour helper returns a CSS-variable string.

    3. **Rendered hiccup carries var() references** — render small
       view fragments and walk every `:style` map: no value is a
       palette hex literal (`#7C5CFF`, `#15171B`, …); every colour
       value is either a `var(--rf-causa-…)` reference, a
       `color-mix(...)` composition, a non-colour string (`\"transparent\"`,
       `\"none\"`, etc.), or a non-string (numeric padding, line-height
       multipliers).

  ## Posture (test-direction memory)

  Per the user's `feedback_causa_story_cljs_unit_tests_not_playwright`
  memory: validate the visual contract via CLJS unit tests reading
  rendered hiccup, NOT new Playwright probes. The existing browser-
  test infra is unchanged."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [day8.re-frame2-causa.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as issues-h]
            [day8.re-frame2-causa.panels.trace-helpers :as trace-h]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]
            [day8.re-frame2-causa.theme.perf-tier :as perf-tier]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- (1) tokens IS the var-map ------------------------------------------

(deftest tokens-map-resolves-every-key-through-css-variables
  (testing "rf2-on4cm — `tokens` is the CSS-variable surface. Every
            entry is `\"var(--rf-causa-<key>)\"`."
    (is (seq tokens/tokens)
        "the map is non-empty (sanity guard against an empty palette)")
    (doseq [[k v] tokens/tokens]
      (is (string? v)
          (str k " value is a string"))
      (is (re-find #"^var\(--rf-causa-" v)
          (str k " (" v ") starts with the rf-causa CSS-variable prefix"))
      (is (re-find (re-pattern (str "--rf-causa-" (name k) "\\)")) v)
          (str k " value references --rf-causa-" (name k))))))

(deftest tokens-map-has-no-hex-literals
  (testing "rf2-on4cm — no entry in `tokens` is a hex literal. Guards
            against an accidental regression to the pre-sweep
            dark-palette alias shape."
    (doseq [[k v] tokens/tokens]
      (is (not (re-find #"^#[0-9A-Fa-f]" v))
          (str k " (" v ") is NOT a hex literal — it's a var() reference")))))

(deftest tokens-keys-match-dark-palette-keys
  (testing "rf2-on4cm — every key in the dark palette has a matching
            entry in `tokens` (the var-map covers the same surface)."
    (is (= (set (keys tokens/dark-palette))
           (set (keys tokens/tokens))))))

;; ---- (2) helpers route through the var-map ------------------------------

(deftest css-var-helper-builds-rf-causa-prefixed-reference
  (testing "rf2-on4cm — `tokens/css-var` is the canonical helper that
            shapes the var() reference. Pure data, JVM-portable."
    (is (= "var(--rf-causa-bg-1)" (tokens/css-var :bg-1)))
    (is (= "var(--rf-causa-text-tertiary)"
           (tokens/css-var :text-tertiary)))
    (is (= "var(--rf-causa-accent-violet)"
           (tokens/css-var :accent-violet)))))

(deftest panel-accent-returns-css-variable-string
  (testing "rf2-on4cm — `panel-accent` materialises the panel-domain
            accent through the var-map. Used by the 3px left-border
            on every L4 panel <h1>."
    (doseq [tab (keys tokens/panel-domain->token)]
      (let [v (tokens/panel-accent tab)]
        (is (string? v))
        (is (re-find #"^var\(--rf-causa-" v)
            (str "panel-accent " tab " resolves to a CSS variable"))))))

(deftest accent-stripe-style-border-references-css-variable
  (testing "rf2-on4cm — the canonical 3px-left-border builder produces
            a border-left value that references a CSS variable, not
            a hardcoded hex."
    (doseq [tab (keys tokens/panel-domain->token)]
      (let [border (:border-left (tokens/accent-stripe-style tab))]
        (is (string? border))
        (is (re-find #"3px solid var\(--rf-causa-" border)
            (str tab " stripe references the canonical var() prefix"))
        (is (not (re-find #"#[0-9A-Fa-f]" border))
            (str tab " stripe has no hex literal in the border declaration"))))))

(deftest panel-icon-style-colour-references-css-variable
  (testing "rf2-on4cm — the panel-icon header glyph rides the same
            domain colour as the accent stripe, resolved through the
            var-map."
    (doseq [tab (keys tokens/panel-icon)]
      (let [colour (:color (tokens/panel-icon-style tab))]
        (is (string? colour))
        (is (re-find #"^var\(--rf-causa-" colour)
            (str tab " icon colour resolves to a CSS variable"))))))

(deftest tier-colour-returns-css-variable-string
  (testing "rf2-on4cm — `perf-tier/tier-colour` is read through tokens
            so the four tier accents land on var(--rf-causa-<key>)."
    (doseq [tier perf-tier/tier-order]
      (let [v (perf-tier/tier-colour tier)]
        (is (string? v))
        (is (re-find #"^var\(--rf-causa-" v)
            (str "tier-colour " tier " resolves to a CSS variable"))))))

(deftest severity-colour-returns-css-variable-string
  (testing "rf2-on4cm — `issues-ribbon-helpers/severity-colour` is
            read through tokens so the per-row severity dot resolves
            to a CSS variable."
    (doseq [severity [:error :warning :advisory]]
      (let [v (issues-h/severity-colour severity)]
        (is (string? v))
        (is (re-find #"^var\(--rf-causa-" v)
            (str "severity-colour " severity " resolves to a CSS variable"))))))

(deftest op-type-colour-returns-css-variable-string
  (testing "rf2-on4cm — `trace-helpers/op-type-colour` returns the
            per-row Trace dot colour as a CSS variable."
    (doseq [op-type [:error :warning :info :event :fx :view/render]]
      (let [v (trace-h/op-type-colour op-type)]
        (is (string? v))
        (is (re-find #"^var\(--rf-causa-" v)
            (str "op-type-colour " op-type " resolves to a CSS variable"))))))

(deftest event-status-colour-returns-css-variable-string
  (testing "rf2-on4cm — the lifecycle-status helper consumed by the
            L2 row + Event header + Trace timeline returns a CSS
            variable."
    (doseq [state [{:outcome :ok} {:outcome :error} {:in-flight? true}
                   {:paused? true} {:stale? true}]]
      (let [v (event-status/event-status-colour state)]
        (is (string? v))
        (is (re-find #"^var\(--rf-causa-" v)
            (str "event-status-colour " state " resolves to a CSS variable"))))))

;; ---- (3) with-alpha builds color-mix ------------------------------------

(deftest with-alpha-composites-against-css-variable
  (testing "rf2-on4cm — the alpha-tail-suffix idiom (`(str token \"55\")`)
            is replaced by `tokens/with-alpha` which builds a CSS-Color-4
            color-mix(...) string that composites the active theme's
            CSS variable with `transparent`."
    (doseq [k [:accent-violet :red :green :cyan :yellow]
            pct [10 33 50 75]]
      (let [v (tokens/with-alpha k pct)]
        (is (string? v))
        (is (re-find #"^color-mix\(in srgb" v)
            (str "with-alpha " k " " pct " starts with color-mix"))
        (is (re-find (re-pattern (str "var\\(--rf-causa-" (name k) "\\)")) v)
            (str "with-alpha " k " " pct " references --rf-causa-" (name k)))
        (is (re-find (re-pattern (str pct "%")) v)
            (str "with-alpha " k " " pct " carries the requested percentage"))
        (is (re-find #"transparent\)$" v)
            (str "with-alpha " k " " pct " ends with `transparent)`"))))))

;; ---- (4) palette source-of-truth integrity ------------------------------

(deftest dark-palette-and-light-palette-are-hex-maps
  (testing "rf2-on4cm — `dark-palette` + `light-palette` remain the
            hex source of truth (consumed by themes-css to register
            the `--rf-causa-<key>` custom properties). They MUST stay
            hex maps so the CSS-variable block emits actual paint values
            and so the few raw-hex consumers (mount.cljs popout overlay,
            config/default-accent) keep landing on real colours."
    (doseq [palette-name [:dark :light]
            :let [palette (get tokens/themes palette-name)]
            [k v] palette]
      (is (string? v)
          (str palette-name " " k " is a string"))
      (is (re-find #"^#[0-9A-Fa-f]+$" v)
          (str palette-name " " k " (" v ") is a hex literal")))))

(deftest light-and-dark-palettes-share-the-canonical-key-set
  (testing "rf2-on4cm — every dark token has a light counterpart so
            the class-toggle flip is total (no `var(--rf-causa-foo)`
            resolves to the property's default initial value because
            `:foo` was missing from the active theme's block)."
    (is (= (set (keys tokens/dark-palette))
           (set (keys tokens/light-palette))))))

;; ---- (5) rendered hiccup carries no palette hex literals ----------------
;;
;; The strongest pin: walk a rendered hiccup tree and assert NO `:style`
;; value is a palette hex literal. Catches a regression where a new
;; inline-style site sneaks in a hardcoded `\"#1B1E24\"` instead of
;; reading through the token map.

(defn- collect-style-strings
  "Depth-first walk: collect every string value found in any `:style`
  map across the hiccup tree. Skips non-string values (numerics,
  line-height multipliers, etc.) since the contract is about colour
  values."
  [tree]
  (let [out (atom [])]
    (letfn [(walk-node [node]
              (when (vector? node)
                (let [attrs (when (map? (second node)) (second node))]
                  (when-let [style (:style attrs)]
                    (when (map? style)
                      (doseq [[_ v] style]
                        (when (string? v)
                          (swap! out conj v)))))
                  (doseq [child (rest node)]
                    (cond
                      (vector? child) (walk-node child)
                      (seq? child)    (doseq [c child] (walk-node c)))))))]
      (walk-node tree))
    @out))

(def ^:private palette-hex-pattern
  ;; A palette hex literal: `#` + 3-to-8 hex digits at a word boundary.
  ;; Matches `#7C5CFF`, `#a83a3a`, `#fff` — but does NOT match the inside
  ;; of a `var(--rf-causa-…)` reference (no hex digits in the var name).
  #"#[0-9A-Fa-f]{3,8}\b")

(deftest data-inspector-rendered-hiccup-has-no-palette-hex-literals
  (testing "rf2-on4cm — the canonical L4-panel value renderer
            (`theme/data-inspector/inspect`) emits hiccup whose every
            `:style` colour value flows through a CSS variable. Walk
            the rendered tree across a representative mix of values
            (collection, keyword, string, number, nil, sentinel) and
            assert NO `:style` string is a `#xxxxxx` palette hex
            literal. Guards against a regression where a new inline-
            style site is added with a hardcoded hex."
    (doseq [v [{:a 1 :b 2 :c [1 2 3]}      ; map + nested vec
               [:foo :bar {:baz nil}]      ; vector with primitives + map
               #{1 2 3}                    ; set
               "a string"                  ; string leaf
               :keyword                    ; keyword leaf
               42                          ; number leaf
               true                        ; boolean leaf
               nil                         ; nil leaf
               :rf/redacted                ; redacted sentinel
               {:rf/large {:bytes 1234     ; large sentinel
                           :head "abc"}}]]
      (let [tree (inspector/inspect v "test")
            styles (collect-style-strings tree)]
        (is (seq styles)
            (str "render of " (pr-str v) " produced style strings to inspect"))
        (doseq [s styles]
          (is (not (re-find palette-hex-pattern s))
              (str "style string " (pr-str s)
                   " in render of " (pr-str v)
                   " contains a palette hex literal "
                   "(should be a var(--rf-causa-…) reference)")))))))

(deftest accent-stripe-style-output-has-no-palette-hex-literal
  (testing "rf2-on4cm — every panel's accent-stripe style map (the
            3px left border on L4 panel <h1>) is hex-free."
    (doseq [tab (keys tokens/panel-domain->token)]
      (let [s (tokens/accent-stripe-style tab)]
        (doseq [[k v] s]
          (when (string? v)
            (is (not (re-find palette-hex-pattern v))
                (str tab " stripe style key " k " value "
                     (pr-str v) " contains a palette hex literal"))))))))

(deftest panel-icon-style-output-has-no-palette-hex-literal
  (testing "rf2-on4cm — every panel's header-icon style map is hex-free."
    (doseq [tab (keys tokens/panel-icon)]
      (let [s (tokens/panel-icon-style tab)]
        (doseq [[k v] s]
          (when (string? v)
            (is (not (re-find palette-hex-pattern v))
                (str tab " icon style key " k " value "
                     (pr-str v) " contains a palette hex literal"))))))))
