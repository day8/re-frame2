(ns day8.re-frame2-machines-viz.theme.tokens
  "Design tokens for the Machines-Viz chart (rf2-o9arp).

  ## Why machines-viz owns its own tokens

  Machines-Viz is bundle-isolated from Causa per the
  tools/README.md contract — a host that embeds `MachineChart`
  outside Causa (Story, the read-only viewer page, a user dev
  shell) must not transitively pull Causa's full theme module just
  to render a chart. The slice the chart needs is small (palette +
  two font stacks + the motion seam) so we publish it here.

  The palette mirrors Causa's `theme/tokens` at the values level —
  the two will track each other until a CSS-variable migration
  consolidates them. Host applications can override the palette by
  wrapping `MachineChart` in a context that rebinds these vars (a
  v1.x affordance; the current chart consumes the dark palette
  directly).

  ## Light + dark palettes (rf2-usord)

  Both `dark-palette` (the default Causa surface) and `light-palette`
  (lighter hosts) are catalogued here. The `palettes` map keys both
  by theme name (`:dark` / `:light`) so the substrate-adapter
  `MachineChart` re-exports resolve via the `:theme` prop. `tokens`
  remains an alias of `dark-palette` for back-compat with the existing
  inline-style call sites — a light-theme host either passes the light
  palette through `with-alpha`'s three-arg arity OR (downstream)
  consumes the per-theme CSS-variable surface once the consolidation
  lands.

  ## Tint helper

  rf2-pyvmr — `with-alpha` resolves a token key to its hex value
  and returns an `rgba(...)` string. Eliminates inline hex literals
  in chart code; a palette shift propagates through every tint
  without a per-call-site edit.

  ## Motion seam

  rf2-xfx6l / rf2-2sez0 — the chart's transition glow (the sole
  remaining continuous animation surface, fired one beat on event-
  fire) interpolates its `animation-duration` through the same
  `--rf-causa-motion-scale` CSS custom property Causa publishes at
  `:root`. The chart's host (Causa today) ensures the variable is
  set; when machines-viz ships standalone the chart's own SVG
  `<style>` block declares the variable + the
  `prefers-reduced-motion` override so the chart honours the user's
  setting without a host-side install. See `chart/svg`
  `inline-stylesheet`. The heartbeat-pulse animation was retired
  2026-05-20 (rf2-2sez0) — only `:glow-duration-ms` remains in the
  motion catalogue.

  ## Visual constants

  rf2-g6cig — chart-shape constants (corner radius, stroke widths,
  paddings, font sizes, durations) live in `visual-constants` so
  the chart's visual character is locked in one map rather than
  scattered across magic numbers."
  {:no-doc true}
  (:require [clojure.string :as str]))

;; ---- palette -----------------------------------------------------------

(def dark-palette
  "Dark-theme colour tokens. Mirrors `day8.re-frame2-causa.theme.tokens`
  at the values level so the chart renders identically whether embedded
  by Causa, Story, the read-only viewer, or a user dev shell."
  {:bg-0           "#0E0F12"
   :bg-1           "#15171B"
   :bg-2           "#1B1E24"
   :bg-3           "#232730"
   :bg-active      "#2A2F3D"

   :border-subtle  "#232730"
   :border-default "#2F3441"

   :text-primary   "#E8EAF0"
   :text-secondary "#A8AEC0"
   ;; `:text-tertiary` mirrors Causa's rf2-0fr6v WCAG-AA bump (from
   ;; `#6B7080` to `#8990A0`). The drift-gate test
   ;; `causa-and-machines-viz-dark-palettes-match-values` (rf2-z7ms8)
   ;; locks this value to Causa's at the .cljc test surface.
   :text-tertiary  "#8990A0"

   :accent-violet  "#7C5CFF"
   :cyan           "#43C3D0"
   :green          "#4ADE80"
   :yellow         "#FBBF24"
   :orange         "#FB923C"
   :red            "#F87171"
   :magenta        "#E879F9"

   :red-deep       "#a83a3a"
   :white          "#ffffff"})

(def light-palette
  "Light-theme colour tokens (rf2-usord). Mirrors
  `day8.re-frame2-causa.theme.tokens/light-palette` at the values level
  so the chart renders identically whether embedded by Causa, Story,
  the read-only viewer, or a user dev shell whose host runs a light
  theme. Surfaces invert (`bg-2` is the brightest 'top' canvas, `bg-0`
  the gentlest 'recess'); text inverts so primary is near-black;
  accents darken ~15-20% to maintain AA contrast on a white canvas.

  Hosts that want the chart to render against a light background pass
  this palette through the `:palette` render option (or via the
  `:theme :light` prop on the substrate-adapter `MachineChart`
  re-exports — wiring lives downstream)."
  {:bg-0           "#FAFBFC"
   :bg-1           "#F1F3F6"
   :bg-2           "#FFFFFF"
   :bg-3           "#E6E9EE"
   :bg-active      "#DCE0E7"

   :border-subtle  "#E6E9EE"
   :border-default "#CFD4DC"

   :text-primary   "#15171B"
   :text-secondary "#4B5160"
   :text-tertiary  "#8B92A1"

   :accent-violet  "#5538D8"
   :cyan           "#2A8B96"
   :green          "#2F9E5C"
   :yellow         "#B07A05"
   :orange         "#C2570F"
   :red            "#C84444"
   :magenta        "#B146C2"

   :red-deep       "#9A3030"
   :white          "#ffffff"})

(def palettes
  "Map of theme-name → palette map (rf2-usord). Mirrors Causa's
  `theme/tokens/themes`. The downstream substrate-adapter
  `MachineChart` exports look this up by the `:theme :dark | :light`
  prop; absent that, hosts can pick a palette from this map directly
  and pass it through the `with-alpha` helper's three-arg arity."
  {:dark  dark-palette
   :light light-palette})

(def tokens
  "Backwards-compatible alias for the dark palette — same shape
  Causa's `tokens` map exposes so the chart's `(:bg-1 tokens)` style
  call sites read identically. The light theme is layered on top via
  the `:palette` render option (rf2-usord); the 200+ inline-style
  reads in `chart/svg` continue to resolve through this alias until
  the CSS-variable migration consolidates the indirection."
  dark-palette)

;; ---- font stacks -------------------------------------------------------

(def mono-stack
  "JetBrains Mono stack — used by chart node + edge labels (data
  surface)."
  "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace")

(def sans-stack
  "Inter stack — used by chrome (caption strip, empty-state fallback,
  compound-container title)."
  "Inter, system-ui, -apple-system, Segoe UI, sans-serif")

;; ---- tint helper (rf2-pyvmr) -------------------------------------------

(defn- parse-hex-byte
  "Parse a two-character hex string to an int (0..255). Portable
  JVM+CLJS. Returns nil for malformed input."
  [^String s]
  #?(:clj  (try (Integer/parseInt s 16) (catch Exception _ nil))
     :cljs (let [n (js/parseInt s 16)]
             (when-not (js/isNaN n) n))))

(defn- hex->rgb
  "Parse a 6- or 8-digit hex colour into `[r g b]`. Returns nil for
  malformed input so callers can degrade gracefully (the chart
  defaults to opaque token when this returns nil)."
  [^String hex]
  (let [s (str/replace (or hex "") #"^#" "")
        n (count s)]
    (when (or (= 6 n) (= 8 n))
      (let [r (parse-hex-byte (subs s 0 2))
            g (parse-hex-byte (subs s 2 4))
            b (parse-hex-byte (subs s 4 6))]
        (when (and r g b) [r g b])))))

(defn with-alpha
  "Resolve `token-key` to its hex value through `tokens` and return an
  `rgba(<r>, <g>, <b>, <alpha>)` string. `alpha` is a fraction in
  `[0.0, 1.0]`.

  Pure data → string; JVM-portable. rf2-pyvmr eliminates inline hex /
  rgba literals from chart code — a palette shift on the source token
  propagates to every tint without a per-call-site edit.

  Returns the token's solid hex unchanged when `alpha` is `nil` or
  the hex fails to parse (defensive fallback so a malformed entry
  cannot blank out the chart)."
  ([token-key alpha]
   (with-alpha token-key alpha tokens))
  ([token-key alpha palette]
   (let [hex (get palette token-key)]
     (cond
       (nil? alpha) hex
       (nil? hex)   nil
       :else (if-let [[r g b] (hex->rgb hex)]
               (str "rgba(" r ", " g ", " b ", " alpha ")")
               hex)))))

;; ---- CSS-variable theming (rf2-uv1on) ----------------------------------

(defn css-var
  "Resolve `token-key` to a `var(--rf-causa-<key>, <hex-fallback>)`
  CSS string so a host that publishes the Causa CSS custom-property
  surface (light + dark themes both live there) gets live theme-
  switching, while a standalone embed degrades to the dark-palette
  hex.

  This mirrors Causa's own `theme/tokens/css-var` naming so the
  variables resolve against the SAME host-published `:root` block —
  the chart and the host paint from one palette. The fallback comes
  from `dark-palette` (the chart's default surface) so a host that
  does NOT publish the variables still renders correctly.

  Pure data → string; JVM-portable. rf2-uv1on uses it for the
  `:after`-ring overlay chrome so light + dark both flow through
  `var(--*)` per the bead's theming requirement."
  ([token-key] (css-var token-key dark-palette))
  ([token-key palette]
   (let [hex (get palette token-key)]
     (if hex
       (str "var(--rf-causa-" (name token-key) ", " hex ")")
       (str "var(--rf-causa-" (name token-key) ")")))))

;; ---- motion seam (rf2-xfx6l) -------------------------------------------

(def motion
  "Motion-axis tokens (mirrors Causa's `theme/tokens/motion`).

  - `:scale-var-name`        — CSS custom property name; consumers
                               interpolate it into their inline
                               `animation-duration: calc(...)`.
  - `:glow-duration-ms`      — transition-edge glow flash duration
                               (rf2-xfx6l).

  The duration interpolates through `--rf-causa-motion-scale`
  (collapsed to 0.001 under `prefers-reduced-motion: reduce`).

  rf2-2sez0 — `:pulse-duration-ms` retired 2026-05-20 with the
  heartbeat-pulse animation. Active-state emphasis is now static."
  {:scale-var-name   "--rf-causa-motion-scale"
   :glow-duration-ms 400})

(defn duration-css
  "Build the canonical `calc(<ms>ms * var(--rf-causa-motion-scale, 1))`
  CSS string a consumer passes as `animation-duration`. Under
  `prefers-reduced-motion: reduce` the seam collapses durations so
  the keyframes resolve in a single frame.

  Pure data → string; JVM-portable."
  [ms]
  (str "calc(" ms "ms * var(" (:scale-var-name motion) ", 1))"))

;; ---- tag-pill palette (rf2-m1b88) --------------------------------------

(def tag-pill-palette
  "Deterministic colour rotation for state-tag pills (rf2-m1b88).
  Skips `:red` and `:accent-violet` — both are reserved for chart
  semantics (`:red` for cancellation glyphs, `:accent-violet` for the
  initial-state marker + FROM-highlight). The remaining five spread
  across cool/warm/neutral so a typical 2-4 tag count reads
  distinctly."
  [:cyan :green :yellow :orange :magenta])

(defn- char-code
  "Portable char → int. JVM uses `int`; CLJS reaches for
  `charCodeAt` on the string at the per-char index."
  [^String s idx]
  #?(:clj  (int (.charAt s ^int idx))
     :cljs (.charCodeAt s idx)))

(defn tag-pill-color
  "Map a tag keyword to a stable palette entry. Deterministic — the
  same tag id resolves to the same token across renders. Pure data
  → keyword."
  [tag]
  (let [^String s (str (when-let [n (and (keyword? tag) (namespace tag))]
                         (str n "/"))
                       (if (keyword? tag) (name tag) (str tag)))
        n (count s)
        h (loop [i 0 acc 0]
            (if (< i n)
              (recur (inc i) (+ acc (char-code s i)))
              acc))]
    (nth tag-pill-palette (mod h (count tag-pill-palette)))))
