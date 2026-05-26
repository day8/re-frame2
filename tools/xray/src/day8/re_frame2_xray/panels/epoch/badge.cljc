(ns day8.re-frame2-xray.panels.epoch.badge
  "Pure-data badge taxonomy for the Epoch panel (rf2-sc3r1).

  Maps each of the 7 cascade step badges → its visual chrome (colour
  token + label text). The colour resolver returns a CSS-variable
  string off `theme/tokens` so the panel's badges flow through the
  active theme like every other Xray chrome element.

  ## Why a separate file

  The badge table is referenced by both the projection (for badge
  validation) and the view (for paint). Pulling it into a tiny shared
  ns keeps the visual contract one source of truth — the view's
  numbered cascade renders the same colour the spec/021 §10.1 badge
  catalogue commits to.

  ## Pure-data + JVM-portable

  Hex resolution happens via `theme/tokens` keyword → CSS-variable
  string lookup; no DOM, no substrate runtime."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- badge taxonomy -----------------------------------------------------
;;
;; Per the bead body's badge colour table (rf2-sc3r1 §Badge Colors):
;;
;;     :DISPATCH      "#8c959f"                  ; mid grey
;;     :COEFFECT      "#a855f7"                  ; light purple
;;     :HANDLER       "var(--devtools-active)"   ; blue
;;     :FLOW          "var(--devtools-active)"   ; blue
;;     :FX            "rgb(154, 103, 0)"         ; orange/brown
;;     :SUBSCRIPTIONS "#ec4899"                  ; pink
;;     :VIEWS         "var(--devtools-success)"  ; green
;;
;; The hex values from the bead map onto Xray's theme tokens:
;;
;;     #8c959f                — `:text-tertiary` (the muted grey already
;;                              in the palette)
;;     #a855f7                — :magenta (violet-500; the COEFFECT mock
;;                              hue. The dark `:magenta` token's hex was
;;                              tuned to this exact value in rf2-cgm4f so
;;                              the badge and the mock match — and the
;;                              shared `:magenta` consumers (redacted
;;                              sentinel chip, filter "out" mode, etc.)
;;                              continue to read a violet-family hue.)
;;     var(--devtools-active) — `:accent` (the single blue identity)
;;     rgb(154,103,0)         — :orange (functional amber, perf-slow
;;                              tier — close enough hue for the FX
;;                              step's irreversible/post-commit signal)
;;     #ec4899                — :magenta-pink (the SUBSCRIPTIONS mock
;;                              hue, added as a new palette token in
;;                              rf2-cgm4f so the SUBSCRIPTIONS pill is
;;                              visually distinct from COEFFECT's
;;                              violet-magenta. Pre-rf2-cgm4f both pills
;;                              collapsed onto `:magenta` and the eye
;;                              couldn't separate them at a glance.)
;;     var(--devtools-success) — `:success` / `:green`
;;
;; The 7-badge inventory is binding: the view never paints a badge
;; whose keyword is not in this map.

(def ^:private badge->token-key
  "Map from badge keyword → theme-token keyword. Resolves through
  `tokens/tokens` so the badge background reads off the active
  theme's CSS variable (one badge across light + dark).

  COEFFECT pulls `:magenta` (violet `#a855f7`); SUBSCRIPTIONS pulls
  `:magenta-pink` (`#ec4899`). The two were collapsed onto the same
  token pre-rf2-cgm4f and operators couldn't separate them at a
  glance — Mike-ruled 2026-05-26: visual distinguishability earns
  one extra palette token. The two hues map onto the original mock's
  assignment, which the implementer had folded for palette-minimality
  reasons that the mock didn't actually support.

  HANDLER + FLOW share `:accent` — the Figma export's single-accent
  identity (rf2-ad7zx.13).

  rf2-17vxj — SCHEMA-VIOLATIONS pulls `:warning` so the section
  reads as load-bearing without rising to `:error`'s alarmist tone.
  rf2-yx1ae — CHILD-DISPATCHES pulls `:text-tertiary` (the same muted
  grey as DISPATCH — same hue family, same cascade-link semantics).
  rf2-rrykz — APP-DB-DIFF pulls `:accent` (the same blue as HANDLER /
  FLOW — same state-mutation lens semantics)."
  {:DISPATCH          :text-tertiary
   :COEFFECT          :magenta
   :HANDLER           :accent
   :FLOW              :accent
   :FX                :orange
   :SUBSCRIPTIONS     :magenta-pink
   :VIEWS             :success
   :SCHEMA-VIOLATIONS :warning
   :CHILD-DISPATCHES  :text-tertiary
   :APP-DB-DIFF       :accent})

(def ^:private badge->label
  "Map from badge keyword → uppercase label rendered in the badge
  pill. Pure data."
  {:DISPATCH          "DISPATCH"
   :COEFFECT          "COEFFECT"
   :HANDLER           "HANDLER"
   :FLOW              "FLOW"
   :FX                ":fx"
   :SUBSCRIPTIONS     "SUBSCRIPTIONS"
   :VIEWS             "VIEWS"
   :SCHEMA-VIOLATIONS "SCHEMA VIOLATIONS"
   :CHILD-DISPATCHES  "DISPATCHED EVENTS"
   :APP-DB-DIFF       "APP-DB DIFF"})

(defn token-key
  "Return the theme-token KEYWORD for `badge` (e.g. `:accent` for
  `:HANDLER`). Useful in helpers + tests where the keyword is the
  primary value. Falls back to `:text-tertiary` for unknown badges so
  the view always paints something."
  [badge]
  (get badge->token-key badge :text-tertiary))

(defn colour
  "Return the CSS-variable string that paints `badge` (e.g.
  `\"var(--rf-xray-accent)\"` for `:HANDLER`). Falls back to muted
  text colour for unknown badges so the view never paints `nil`."
  [badge]
  (get tokens/tokens (token-key badge)
       (get tokens/tokens :text-tertiary)))

(defn label
  "Return the uppercase label string the badge pill renders (e.g.
  `\"HANDLER\"` for `:HANDLER`). Falls back to `(name badge)` for
  unknown badges so a future taxonomy extension still paints text."
  [badge]
  (or (get badge->label badge)
      (when (keyword? badge) (str/upper-case (name badge)))
      "?"))

(def step-numbered-circle-diameter-px
  "21px per the bead body's numbered cascade pattern (§Numbered
  Cascade Pattern: '21px diameter')."
  21)

(def vertical-line-offset-px
  "13px per the bead body — the vertical line starts at 13px from the
  top of the pipeline section."
  13)

(def circle-left-offset-px
  "-44px per the bead body — the numbered circle's left anchor."
  -44)

(def line-left-offset-px
  "-34px per the bead body — the vertical line's left anchor (between
  the circle column and the content column)."
  -34)

(def fib
  "Fibonacci spacing scale per the bead body's §Fibonacci Spacing
  System (3 · 5 · 8 · 13 · 21 · 34 · 55 · 89).

  Catalogued as data so view sites read keyed values rather than
  scattered magic numbers. Pure data; JVM-portable."
  {:f3   3
   :f5   5
   :f8   8
   :f13  13
   :f21  21
   :f34  34
   :f55  55
   :f89  89})

(defn fib-px
  "Resolve a fibonacci-key to a CSS px string (e.g. `:f13` →
  `\"13px\"`). Returns `\"0\"` for unknown keys so the view never
  paints `nil`."
  [k]
  (if-let [n (get fib k)]
    (str n "px")
    "0"))
