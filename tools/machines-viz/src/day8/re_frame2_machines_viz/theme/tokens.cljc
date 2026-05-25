(ns day8.re-frame2-machines-viz.theme.tokens
  "Design tokens for the Machines-Viz chart (rf2-o9arp).

  ## Why machines-viz owns its own tokens

  Machines-Viz is bundle-isolated from Xray per the
  tools/README.md contract — a host that embeds `MachineChart`
  outside Xray (Story, the read-only viewer page, a user dev
  shell) must not transitively pull Xray's full theme module just
  to render a chart. The slice the chart needs is small (palette +
  two font stacks + the motion seam) so we publish it here.

  The palette mirrors Xray's `theme/tokens` at the values level —
  the two will track each other until a CSS-variable migration
  consolidates them. Host applications can override the palette by
  wrapping `MachineChart` in a context that rebinds these vars (a
  v1.x affordance; the current chart consumes the dark palette
  directly).

  ## Light + dark palettes (rf2-usord)

  Both `dark-palette` (the default Xray surface) and `light-palette`
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
  `--rf-xray-motion-scale` CSS custom property Xray publishes at
  `:root`. The chart's host (Xray today) ensures the variable is
  set; when machines-viz ships standalone the chart's own inline
  `<style>` block declares the variable + the
  `prefers-reduced-motion` override so the chart honours the user's
  setting without a host-side install. See `chart`
  `chart-stylesheet`. The heartbeat-pulse animation was retired
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
  "Dark-theme colour tokens — the GitHub-style blue/neutral Figma
  palette (rf2-ad7zx.13). Mirrors `day8.re-frame2-xray.theme.tokens`
  at the values level (drift-gate `xray-and-machines-viz-dark-palettes-
  match-values`, rf2-z7ms8) so the chart renders identically whether
  embedded by Xray, Story, the read-only viewer, or a user dev shell.

  The orange-identity scheme (`brand`, `accent-dynamic`, `accent-static`,
  the per-mode colour swap) is removed; the Figma export carries a
  SINGLE accent (blue). `:info` is the cool categorical blue distinct
  from `:accent`."
  {:bg-0           "#161616"
   :bg-1           "#1c1c1c"
   :bg-2           "#242424"
   :bg-3           "#2a2a2a"
   :bg-active      "#2a2a2a"

   ;; chrome ribbon (rf2-xawwb · Figma-Make surface) — mirrored from
   ;; Xray's dark-palette to satisfy the `xray-and-machines-viz-dark-
   ;; palettes-match-key-set` drift gate. These are Xray-chrome tokens
   ;; (the chart itself doesn't read them) but the gate requires an exact
   ;; key-set mirror, so they're published here at the same values.
   :chrome-ribbon-bg              "#0d1117"
   :chrome-ribbon-text            "#e6edf3"
   :chrome-ribbon-text-muted      "#8b949e"
   :chrome-ribbon-tab-active      "#2a2a2a"
   :chrome-ribbon-tab-active-text "#e6edf3"
   :active-bg                     "#1f6feb"
   :active-text                   "#ffffff"

   :border-subtle  "#2a2a2a"
   :border-default "#373737"

   :text-primary   "#e6edf3"
   :text-secondary "#adbac7"
   :text-tertiary  "#8b949e"

   ;; single accent (GitHub blue — Figma --devtools-active).
   :accent         "#539bf5"

   ;; semantic + change (Figma devtools.css)
   :error          "#f85149"
   :warning        "#d29922"
   :advisory       "#79c0ff"
   :success        "#3fb950"
   :dim            "#6e7681"
   :hover          "#2a2a2a"

   ;; functional categorical hues (spec/022 carve-out)
   :green          "#3fb950"
   :yellow         "#d29922"
   :orange         "#FB923C"
   :red            "#F87171"
   :magenta        "#E879F9"
   :info           "#79c0ff"

   ;; syntax-highlighter palette (rf2-79ojx · One Dark / Atom One Dark)
   ;; mirrored from Xray's dark-palette so the `xray-and-machines-viz-
   ;; dark-palettes-match-key-set` drift gate stays green. Tokens are
   ;; Xray-source-highlighter + data-display surface; the chart itself
   ;; does not read them. See Xray's `tokens.cljc` for the palette
   ;; rationale (CLJS-editor-syntax-highlight scheme — keyword magenta /
   ;; string green / number orange / boolean gold / nil grey).
   :syntax-keyword     "#c678dd"
   :syntax-string      "#98c379"
   :syntax-number      "#d19a66"
   :syntax-boolean     "#e5c07b"
   :syntax-nil         "#7f848e"
   :syntax-symbol      "#61afef"
   :syntax-builtin     "#61afef"
   :syntax-punctuation "#abb2bf"

   :red-deep       "#a83a3a"

   ;; diff row chrome (rf2-awqts) — mirrored from Xray's dark-palette
   ;; so the key-set drift gate stays green. The chart doesn't read
   ;; these tokens directly (diff signalling is Xray-specific), but
   ;; mirroring keeps the two palette surfaces symmetric.
   :diff-gutter          "#5fbcb6"
   :diff-added-wash      "#3fb9501a"
   :diff-modified-wash   "#d299221f"
   :diff-removed-wash    "#f851491a"
   :diff-added-stripe    "#3fb950"
   :diff-modified-stripe "#d29922"
   :diff-removed-stripe  "#f85149"

   :white          "#ffffff"})

(def light-palette
  "Light-theme colour tokens (rf2-usord). Mirrors
  `day8.re-frame2-xray.theme.tokens/light-palette` at the values level
  so the chart renders identically whether embedded by Xray, Story,
  the read-only viewer, or a user dev shell whose host runs a light
  theme. Surfaces invert (`bg-2` is the brightest 'top' canvas, `bg-0`
  the gentlest 'recess'); text inverts so primary is near-black; the
  single accent darkens to GitHub blue `#0969da` to maintain AA
  contrast on a white canvas.

  Hosts that want the chart to render against a light background pass
  this palette through the `:palette` render option (or via the
  `:theme :light` prop on the substrate-adapter `MachineChart`
  re-exports — wiring lives downstream)."
  {:bg-0           "#fbfbfb"
   :bg-1           "#f5f5f5"
   :bg-2           "#ffffff"
   :bg-3           "#e8e8e8"
   :bg-active      "#e8e8e8"

   ;; chrome ribbon (rf2-xawwb · Figma-Make surface) — mirrored from
   ;; Xray's light-palette so the two palettes stay symmetric (the dark
   ;; drift gate is key-set equality; the light gate checks shared-key
   ;; values agree). Xray-chrome tokens; the chart doesn't read them.
   :chrome-ribbon-bg              "#2a2a2a"
   :chrome-ribbon-text            "#ffffff"
   :chrome-ribbon-text-muted      "#b0b0b0"
   :chrome-ribbon-tab-active      "#ffffff"
   :chrome-ribbon-tab-active-text "#24292f"
   :active-bg                     "#0969da"
   :active-text                   "#ffffff"

   :border-subtle  "#e8e8e8"
   :border-default "#d1d1d1"

   :text-primary   "#24292f"
   :text-secondary "#656d76"
   :text-tertiary  "#8c959f"

   ;; single accent (GitHub blue — Figma --devtools-active)
   :accent         "#0969da"

   ;; semantic + change
   :error          "#cf222e"
   :warning        "#9a6700"
   :advisory       "#0550ae"
   :success        "#1a7f37"
   :dim            "#8c959f"
   :hover          "#e8e8e8"

   ;; functional categorical hues
   :green          "#1a7f37"
   :yellow         "#9a6700"
   :orange         "#C2570F"
   :red            "#C84444"
   :magenta        "#B146C2"
   :info           "#0550ae"

   ;; syntax-highlighter palette (rf2-79ojx · One Light / Atom One Light)
   ;; mirrored from Xray's light-palette so the light drift gate
   ;; (`xray-and-machines-viz-light-palettes-match-values`) stays green.
   ;; See Xray's `tokens.cljc` for the palette rationale.
   :syntax-keyword     "#a626a4"
   :syntax-string      "#50a14f"
   :syntax-number      "#986801"
   :syntax-boolean     "#c18401"
   :syntax-nil         "#a0a1a7"
   :syntax-symbol      "#4078f2"
   :syntax-builtin     "#4078f2"
   :syntax-punctuation "#383a42"

   :red-deep       "#9A3030"

   ;; diff row chrome (rf2-awqts) — mirrored from Xray's light-palette
   ;; per the dark-palette pattern above.
   :diff-gutter          "#178f86"
   :diff-added-wash      "#1a7f371a"
   :diff-modified-wash   "#9a67001f"
   :diff-removed-wash    "#c844441a"
   :diff-added-stripe    "#1a7f37"
   :diff-modified-stripe "#9a6700"
   :diff-removed-stripe  "#cf222e"

   :white          "#ffffff"})

(def palettes
  "Map of theme-name → palette map (rf2-usord). Mirrors Xray's
  `theme/tokens/themes`. The downstream substrate-adapter
  `MachineChart` exports look this up by the `:theme :dark | :light`
  prop; absent that, hosts can pick a palette from this map directly
  and pass it through the `with-alpha` helper's three-arg arity."
  {:dark  dark-palette
   :light light-palette})

(def tokens
  "Backwards-compatible alias for the dark palette — same shape
  Xray's `tokens` map exposes so the chart's `(:bg-1 tokens)` style
  call sites read identically. The light theme is layered on top via
  the `:palette` render option (rf2-usord); the inline-style reads in
  `chart.nodes`, `chart.edges`, `chart.cljs` and the overlays continue
  to resolve through this alias until the CSS-variable migration
  consolidates the indirection."
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
  "Resolve `token-key` to a `var(--rf-xray-<key>, <hex-fallback>)`
  CSS string so a host that publishes the Xray CSS custom-property
  surface (light + dark themes both live there) gets live theme-
  switching, while a standalone embed degrades to the dark-palette
  hex.

  This mirrors Xray's own `theme/tokens/css-var` naming so the
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
       (str "var(--rf-xray-" (name token-key) ", " hex ")")
       (str "var(--rf-xray-" (name token-key) ")")))))

;; ---- motion seam (rf2-xfx6l) -------------------------------------------

(def motion
  "Motion-axis tokens (mirrors Xray's `theme/tokens/motion`).

  - `:scale-var-name`        — CSS custom property name; consumers
                               interpolate it into their inline
                               `animation-duration: calc(...)`.
  - `:glow-duration-ms`      — transition-edge glow flash duration
                               (rf2-xfx6l).

  The duration interpolates through `--rf-xray-motion-scale`
  (collapsed to 0.001 under `prefers-reduced-motion: reduce`).

  rf2-2sez0 — `:pulse-duration-ms` retired 2026-05-20 with the
  heartbeat-pulse animation. Active-state emphasis is now static."
  {:scale-var-name   "--rf-xray-motion-scale"
   :glow-duration-ms 400})

(defn duration-css
  "Build the canonical `calc(<ms>ms * var(--rf-xray-motion-scale, 1))`
  CSS string a consumer passes as `animation-duration`. Under
  `prefers-reduced-motion: reduce` the seam collapses durations so
  the keyframes resolve in a single frame.

  Pure data → string; JVM-portable."
  [ms]
  (str "calc(" ms "ms * var(" (:scale-var-name motion) ", 1))"))

;; ---- tag-pill palette (rf2-m1b88) --------------------------------------

(def tag-pill-palette
  "Deterministic colour rotation for state-tag pills (rf2-m1b88).
  Skips `:red`, `:accent` and `:info` — all reserved for chart
  semantics (`:red` for cancellation glyphs, the single `:accent` for
  the initial-state marker + FROM-highlight, `:info` for the
  TO-highlight / active state). The remaining five spread across
  cool/warm/neutral so a typical 2-4 tag count reads distinctly."
  [:advisory :green :yellow :orange :magenta])

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
