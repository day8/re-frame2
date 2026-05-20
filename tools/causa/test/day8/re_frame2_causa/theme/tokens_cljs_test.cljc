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
            [clojure.set :as set]
            [day8.re-frame2-causa.theme.tokens :as t]
            [day8.re-frame2-machines-viz.theme.tokens :as mv]))

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
  (testing "rf2-5kfxe.8 — the 8 L4 tabs each get a domain colour, so
            panels are distinguishable at a glance via the 3px left
            border on their header. Machines Canvas (rf2-mkpnb)
            shares :green with Machines (the two are sibling
            sub-domain tabs)."
    (let [tabs #{:event :app-db :views :trace :machines :machines-canvas
                 :routing :issues}]
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

;; ---- rf2-n8i2c — font-size CSS var anchor ------------------------------

(deftest font-size-var-name-matches-css-publication
  (testing "rf2-n8i2c — `font-size-var-name` is the CSS custom property
            that `theme/global-styles/motion-css` publishes on `:root`.
            One knob — change it and every `type-scale` entry rescales
            in lockstep."
    (is (= "--rf-causa-font-size" t/font-size-var-name))))

(deftest font-size-default-is-the-causa-baseline
  (testing "rf2-n8i2c — the default knob value is the historical
            `:body` size (13px). All multipliers are expressed
            RELATIVE to this so the emitted pixel sizes match the
            pre-migration fixed-px table at the default."
    (is (= "13px" t/font-size-default))))

(deftest type-scale-multipliers-anchor-body-at-one
  (testing "rf2-n8i2c — `:body` is the 1.0 anchor; every other size
            is a fraction of it. Display rises slightly above; mono,
            caption, micro fall below."
    (is (= 1.0 (:body t/type-scale-multipliers)))
    (is (> (:display t/type-scale-multipliers) 1.0))
    (is (< (:caption t/type-scale-multipliers) 1.0))
    (is (< (:micro   t/type-scale-multipliers) 1.0))))

(deftest type-scale-keys-stable
  (testing "rf2-n8i2c — the migration changes VALUES (fixed px →
            calc-strings) not KEYS. Every existing call site that
            reads `(:body type-scale)` continues to resolve."
    (let [expected #{:display :body :body-tight :mono-body :caption :micro
                     :line-height-tight :line-height-mono}]
      (is (= expected (set (keys t/type-scale)))))))

(deftest type-scale-font-size-entries-are-calc-strings
  (testing "rf2-n8i2c — every typographic size resolves through
            `calc(var(--rf-causa-font-size, 13px) * <multiplier>)`
            so a single `:root` override rescales the entire shell."
    (doseq [k [:display :body :body-tight :mono-body :caption :micro]]
      (let [v (get t/type-scale k)]
        (is (string? v) (str k " is a CSS string"))
        (is (re-find #"^calc\(" v)
            (str k " starts with calc("))
        (is (re-find #"var\(--rf-causa-font-size,\s*13px\)" v)
            (str k " references the --rf-causa-font-size knob with
                  the 13px fallback so unstyled consumers (no install
                  of theme/global-styles) still see the baseline"))
        (is (re-find #"\*\s*[0-9.]+\)" v)
            (str k " carries a numeric multiplier so the relative
                  scale is preserved across knob overrides"))))))

(deftest type-scale-line-height-stays-unitless
  (testing "rf2-n8i2c — line-height values are unitless ratios. They
            scale with the resolved font-size automatically; the
            calc-string migration is for absolute sizes only."
    (is (= 1.35 (:line-height-tight t/type-scale)))
    (is (= 1.4  (:line-height-mono  t/type-scale)))))

(deftest font-size-css-builds-calc-with-var-and-fallback
  (testing "rf2-n8i2c — `font-size-css` is the pure-data helper that
            shapes each calc-string. Consumers (the `type-scale`
            map) pipe their multiplier through it so the var name +
            fallback default stay one source of truth.

            The numeric literal is stringified by the host runtime
            (`1.0` → `1` on CLJS, `1.0` on the JVM). We assert the
            shape via regex rather than strict equality so both
            runtimes pass."
    (let [css (t/font-size-css 1.0)]
      (is (string? css))
      (is (re-find #"^calc\(var\(--rf-causa-font-size,\s*13px\)\s*\*\s*1(\.0+)?\)$" css)))))

(deftest font-size-css-respects-distinct-multipliers
  (testing "different multipliers produce distinct calc-strings —
            the relative scale is preserved across the type table."
    (let [body    (t/font-size-css (:body    t/type-scale-multipliers))
          display (t/font-size-css (:display t/type-scale-multipliers))
          caption (t/font-size-css (:caption t/type-scale-multipliers))]
      (is (not= body display))
      (is (not= body caption))
      (is (not= display caption)))))

(deftest type-scale-uses-font-size-css-helper
  (testing "rf2-n8i2c — every size entry in `type-scale` is the
            output of `font-size-css` applied to the matching
            multiplier. Asserts the indirection is the only path to
            the calc-string (no fixed-px regressions)."
    (doseq [[k mult] t/type-scale-multipliers]
      (is (= (t/font-size-css mult) (get t/type-scale k))
          (str k " is font-size-css of its multiplier")))))

;; ---- rf2-0fr6v — WCAG 2.1 AA contrast for `:text-tertiary` --------------
;;
;; The pre-rf2-0fr6v hex `#6B7080` landed at ~3.5:1 on the dark bg-1
;; surface — fails WCAG 2.1 AA's 4.5:1 floor for small body text. The
;; token is consumed in ~50 inline-style call sites (relative-time
;; chip, hint text, settings field hints, inactive tab labels, "no
;; events" empty state, palette result-count, etc.) — most at the
;; caption/micro size where the small-text threshold applies. The
;; new hex `#8990A0` lands at ~4.7:1 on `:bg-1` (passes AA) without
;; flipping the visual rhythm (still reads as muted/secondary).
;;
;; Contrast formula: WCAG 2.1 §1.4.3 relative luminance ratio.
;; The helpers below are pure data — JVM-portable so the .cljc test
;; surface validates the relationship on both runners.

(defn- hex->rgb-channels
  "Parse a `#RRGGBB` hex string into a 3-tuple of channels in the
  [0, 1] range. Pure data."
  [hex]
  (let [s (subs hex 1)
        ;; subs/parse-int on the JVM cannot pass a radix as a 2nd arg
        ;; to `subs`; build the 2-char substrings manually.
        r (subs s 0 2)
        g (subs s 2 4)
        b (subs s 4 6)
        parse #?(:clj  #(/ (Long/parseLong % 16) 255.0)
                 :cljs #(/ (js/parseInt % 16) 255.0))]
    [(parse r) (parse g) (parse b)]))

(defn- channel-luminance
  "WCAG 2.1 §1.4.3 per-channel relative luminance. Input in [0, 1]."
  [c]
  (if (<= c 0.03928)
    (/ c 12.92)
    (Math/pow (/ (+ c 0.055) 1.055) 2.4)))

(defn- relative-luminance
  "WCAG 2.1 §1.4.3 relative luminance of a `#RRGGBB` colour."
  [hex]
  (let [[r g b] (hex->rgb-channels hex)
        rl (channel-luminance r)
        gl (channel-luminance g)
        bl (channel-luminance b)]
    (+ (* 0.2126 rl) (* 0.7152 gl) (* 0.0722 bl))))

(defn- contrast-ratio
  "WCAG 2.1 §1.4.3 contrast ratio between two colours. Returns a
  number in [1.0, 21.0]. Order-independent — `(contrast-ratio fg bg)`
  equals `(contrast-ratio bg fg)`."
  [hex-a hex-b]
  (let [la (relative-luminance hex-a)
        lb (relative-luminance hex-b)
        lighter (max la lb)
        darker  (min la lb)]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(deftest text-tertiary-passes-wcag-aa-on-dark-bg-1
  (testing "rf2-0fr6v + audit finding #7 — `:text-tertiary` on `:bg-1`
            must clear WCAG 2.1 AA's 4.5:1 floor for small body text.
            The pre-rf2-0fr6v hex `#6B7080` landed at ~3.5:1 — below
            the floor. The bumped hex `#8990A0` lands ~4.7:1."
    (let [ratio (contrast-ratio (:text-tertiary t/tokens)
                                (:bg-1 t/tokens))]
      (is (>= ratio 4.5)
          (str ":text-tertiary " (:text-tertiary t/tokens)
               " on :bg-1 " (:bg-1 t/tokens)
               " contrast ratio " ratio
               " must clear WCAG 2.1 AA 4.5:1")))))

(deftest text-tertiary-passes-wcag-aa-on-dark-bg-2
  (testing "rf2-0fr6v — same token on `:bg-2` (`#1B1E24`). The bg-2
            surface is slightly lighter than bg-1, so the contrast
            ratio is marginally LOWER (the foreground hex sits closer
            to bg-2 in luminance). Pre-fix hex landed ~3.2:1 — even
            further below the floor — so the bumped hex must clear AA
            on this surface too."
    (let [ratio (contrast-ratio (:text-tertiary t/tokens)
                                (:bg-2 t/tokens))]
      (is (>= ratio 4.5)
          (str ":text-tertiary " (:text-tertiary t/tokens)
               " on :bg-2 " (:bg-2 t/tokens)
               " contrast ratio " ratio
               " must clear WCAG 2.1 AA 4.5:1")))))

(deftest text-tertiary-is-bumped-from-pre-fix-hex
  (testing "rf2-0fr6v — guard against silent revert. The pre-fix
            value `#6B7080` was below AA; the new value differs."
    (is (not= "#6B7080" (:text-tertiary t/tokens))
        "the audit-flagged pre-fix hex must not regress")))

(deftest text-secondary-still-passes-wcag-aaa
  (testing "Sanity guard — bumping `:text-tertiary` must not have
            collateral effect on `:text-secondary`. Per the audit
            inventory `:text-secondary` lands at ~9:1 on bg-1 (AAA)."
    (let [ratio (contrast-ratio (:text-secondary t/tokens)
                                (:bg-1 t/tokens))]
      (is (>= ratio 7.0)
          (str ":text-secondary must remain >= 7:1 (AAA), got " ratio)))))

(deftest text-primary-still-passes-wcag-aaa
  (testing "Sanity guard — `:text-primary` at AAA."
    (let [ratio (contrast-ratio (:text-primary t/tokens)
                                (:bg-1 t/tokens))]
      (is (>= ratio 7.0)
          (str ":text-primary must remain >= 7:1 (AAA), got " ratio)))))

;; ---- rf2-z7ms8 — Causa ↔ machines-viz palette drift CI gate ------------

(deftest causa-and-machines-viz-dark-palettes-match-key-set
  (testing "rf2-z7ms8 — the machines-viz dark-palette must expose
            EVERY key Causa's dark-palette does (it's an explicit
            mirror). Asserting the key-set rather than equality lets
            Causa's dark-palette grow (e.g. new utility tokens) without
            forcing machines-viz to re-publish — only the SHARED keys
            must agree on value (see the next test)."
    (let [causa-keys (set (keys t/dark-palette))
          mv-keys    (set (keys mv/dark-palette))]
      (is (= causa-keys mv-keys)
          (str "key drift: causa-only "
               (vec (sort (set/difference causa-keys mv-keys)))
               " vs machines-viz-only "
               (vec (sort (set/difference mv-keys causa-keys))))))))

(deftest causa-and-machines-viz-dark-palettes-match-values
  (testing "rf2-z7ms8 — for every key SHARED between the two
            dark-palettes the HEX values must agree. machines-viz
            mirrors Causa at the values level (per the doc-string in
            tools/machines-viz/src/.../theme/tokens.cljc) so the chart
            renders identically whether embedded by Causa, Story, the
            read-only viewer, or a user dev shell. A drift here is a
            silent visual-fidelity bug — the chart paints a different
            colour than the surrounding panel chrome."
    (let [shared (set/intersection (set (keys t/dark-palette))
                                           (set (keys mv/dark-palette)))]
      (doseq [k shared]
        (is (= (get t/dark-palette  k)
               (get mv/dark-palette k))
            (str "dark-palette drift on " k
                 ": causa=" (pr-str (get t/dark-palette k))
                 " vs machines-viz=" (pr-str (get mv/dark-palette k))))))))

(deftest causa-and-machines-viz-light-palettes-match-values
  (testing "rf2-z7ms8 + rf2-usord — same drift gate for the light
            palette: every key shared between the two light-palettes
            must agree on hex value. Asserting shared-key equality
            (rather than full set equality) lets each side carry extra
            theme-internal entries without forcing the other to
            mirror them."
    (let [shared (set/intersection (set (keys t/light-palette))
                                           (set (keys mv/light-palette)))]
      (is (seq shared)
          "the light palettes share at least the canonical 7-axis token
           set (no empty-intersection footgun)")
      (doseq [k shared]
        (is (= (get t/light-palette  k)
               (get mv/light-palette k))
            (str "light-palette drift on " k
                 ": causa=" (pr-str (get t/light-palette k))
                 " vs machines-viz=" (pr-str (get mv/light-palette k))))))))

(deftest causa-and-machines-viz-mono-and-sans-stacks-match
  (testing "rf2-z7ms8 — the font stacks are part of the shared visual
            contract. Drift here lands the chart's labels on a
            different face than the surrounding chrome — visually
            obvious, structurally silent."
    (is (= t/mono-stack mv/mono-stack))
    (is (= t/sans-stack mv/sans-stack))))
