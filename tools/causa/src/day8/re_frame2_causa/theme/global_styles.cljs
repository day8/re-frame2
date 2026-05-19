(ns day8.re-frame2-causa.theme.global-styles
  "One-shot global style injection for the Causa shell.

  Owns the `<head>` writes that the panel inline-style discipline
  can't reach: `@font-face` declarations, `@keyframes`, the reduced-
  motion seam, per-theme CSS custom properties, and the atmospheric
  grain overlay. Public surface is one `install!` call from
  `shell-view`.

  Idempotent — `defonce`-guarded *and* DOM-probed via fixed `id`
  attributes — so shadow-cljs `:after-load` reloads, repeated shell
  mounts, and `defonce` resets all converge to a single set of nodes
  in `<head>`.

  ## No third-party egress by default

  The `@font-face` block ships `local()`-only `src:` candidates so an
  OS-installed Inter / JetBrains Mono / Fraunces resolves
  automatically and ABSENT THAT no HTTP fetch is attempted. The
  re-frame2 testbed enforces a 'no third-party egress by default'
  gate; an earlier wiring to Google Fonts (a `<link rel='stylesheet'>`
  to `fonts.googleapis.com`) tripped it. Consuming projects opt-in
  to web-hosted fonts by layering their own `@font-face` rules with
  `url()` entries — see the `font-faces-css` docstring.

  ## Why a separate ns from `shell.cljs`

  `shell.cljs` already carries `inject-scrollbar-style!` scoped to the
  L2 list. The styles installed here are *global* (every paint of the
  shell needs the fonts resolved; every animation downstream reads
  from the `:root` motion-scale seam) so they want their own lifetime
  + a clean test surface. Owning a dedicated ns also keeps the public
  contract obvious: a single `install!` call from `shell-view`.

  ## Lifetime

  `install!` is invoked once from `shell.cljs`'s `shell-view` reg-view
  body. It guards against `js/document` being absent (node-test) and
  uses fixed `id` attributes on the `<style>` nodes so a hot-reload
  that resets the `defonce` atom would still no-op when the DOM node
  is already present."
  (:require [clojure.string :as string]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- font loading (rf2-5kfxe.1 + rf2-5kfxe.1 follow-up) ----------------
;;
;; Inter + JetBrains Mono are the brand faces per spec/007 §Typography;
;; Fraunces (rf2-5kfxe.9) is the variable serif display face for L4
;; panel <h1>s. They appear in `tokens/sans-stack` + `tokens/mono-stack`
;; + `tokens/display-stack` as the FIRST entries of their fallback
;; cascades — when an OS-installed copy is present the page renders in
;; the brand face; otherwise the cascade falls through to the platform
;; defaults catalogued in those stacks.
;;
;; Mechanism: a `<style>` block carrying `local()`-only `@font-face`
;; declarations. No `url()` entries, no third-party HTTP fetch, no
;; preconnect hints — the re-frame2 testbed enforces a 'no third-party
;; egress by default' gate (Causa's previous wiring to Google Fonts
;; tripped it), and most consuming projects do not vendor WOFF2s at
;; predictable URLs anyway. With `local()`-only rules an OS-installed
;; Inter / JetBrains Mono / Fraunces (Mike's machines; design-system
;; users) still resolves automatically; absent that, the fallback chain
;; in each stack (system-ui / Menlo / ui-serif / Georgia / …) takes
;; over with zero network activity.
;;
;; ## Consumer opt-in for web-hosted fonts
;;
;; Consuming projects that want the canonical webfont resolution inject
;; their own `@font-face` rules with `url()` entries pointing at their
;; self-hosted or CDN-hosted WOFF2s. CSS allows a later `@font-face`
;; with the same family + weight to layer additional `src:` candidates
;; on top of the dev-time `local()` default, so the opt-in path
;; composes cleanly — the host app's `url()` declarations are tried
;; alongside the local() candidates without any coordination with
;; Causa itself.
;;
;; Fraunces in particular is unlikely to be locally installed for most
;; users; consumers who care about the display-face hierarchy SHOULD
;; provide a webfont URL. The fallback chain in `tokens/display-stack`
;; lands on `ui-serif` / Georgia / Cambria / Times so panel titles
;; still render in *some* serif even when Fraunces is absent.

(def ^:private fonts-style-id
  "rf-causa-fonts")

(def ^:private font-faces-css
  "Auto-injected `@font-face` declarations for Inter (sans), JetBrains
  Mono (mono), and Fraunces (display serif). One CSS string so callers
  drop it into a single `<style>` element.

  ## `local()`-only by design

  Every rule below ships `src: local('<face name>')` and NO `url()`
  entry. The re-frame2 testbed enforces a 'no third-party egress by
  default' gate; an earlier wiring to Google Fonts tripped it. With
  `local()`-only rules an OS-installed copy is picked up automatically
  and absent that the per-stack fallback chain in `tokens/sans-stack`
  / `tokens/mono-stack` / `tokens/display-stack` takes over.

  ## Consumer opt-in for webfonts

  Consuming projects that want web-hosted Inter / JetBrains Mono /
  Fraunces inject their own `@font-face` rules with `url()` entries.
  CSS layers additional `src:` candidates by family + weight so the
  host-side declarations compose with the `local()` defaults.

  ## What is requested

  - Inter — weights 400 / 500 / 600 / 700 (chrome, labels, prose).
  - JetBrains Mono — weights 400 / 500 / 600 / 700 (code, EDN).
  - Fraunces — weights 500 / 600 / 700 / 900 (L4 panel `<h1>` only,
    rf2-5kfxe.9). Variable optical-size axis 9-144 isn't expressible
    in a `local()` reference so the per-weight family names are used.

  `font-display: swap` is set on every rule so when a webfont DOES
  resolve (via consumer opt-in `url()` layering) the fallback renders
  immediately and swaps to the brand face on WOFF2 arrival — no FOIT,
  no perceived layout shift."
  (str
    ;; Inter — 4 weights.
    "@font-face{font-family:'Inter';font-style:normal;font-weight:400;"
    "font-display:swap;src:local('Inter'),local('Inter Regular');}\n"
    "@font-face{font-family:'Inter';font-style:normal;font-weight:500;"
    "font-display:swap;src:local('Inter Medium');}\n"
    "@font-face{font-family:'Inter';font-style:normal;font-weight:600;"
    "font-display:swap;src:local('Inter SemiBold');}\n"
    "@font-face{font-family:'Inter';font-style:normal;font-weight:700;"
    "font-display:swap;src:local('Inter Bold');}\n"
    ;; JetBrains Mono — 4 weights.
    "@font-face{font-family:'JetBrains Mono';font-style:normal;"
    "font-weight:400;font-display:swap;"
    "src:local('JetBrains Mono'),local('JetBrains Mono Regular');}\n"
    "@font-face{font-family:'JetBrains Mono';font-style:normal;"
    "font-weight:500;font-display:swap;"
    "src:local('JetBrains Mono Medium');}\n"
    "@font-face{font-family:'JetBrains Mono';font-style:normal;"
    "font-weight:600;font-display:swap;"
    "src:local('JetBrains Mono SemiBold');}\n"
    "@font-face{font-family:'JetBrains Mono';font-style:normal;"
    "font-weight:700;font-display:swap;"
    "src:local('JetBrains Mono Bold');}\n"
    ;; Fraunces — display face. Variable optical-size axis is not
    ;; expressible via local(); per-weight family names match the
    ;; standard naming for installed copies. Most users won't have
    ;; Fraunces locally — they SHOULD layer their own `url()` rules.
    "@font-face{font-family:'Fraunces';font-style:normal;font-weight:500;"
    "font-display:swap;src:local('Fraunces Medium'),local('Fraunces');}\n"
    "@font-face{font-family:'Fraunces';font-style:normal;font-weight:600;"
    "font-display:swap;src:local('Fraunces SemiBold');}\n"
    "@font-face{font-family:'Fraunces';font-style:normal;font-weight:700;"
    "font-display:swap;src:local('Fraunces Bold');}\n"
    "@font-face{font-family:'Fraunces';font-style:normal;font-weight:900;"
    "font-display:swap;src:local('Fraunces Black');}\n"))

(defn- inject-fonts!
  "Append the `local()`-only `@font-face` `<style>` block to `<head>`.
  No-op when the node already exists or when `js/document` is absent
  (node-test). No third-party HTTP fetch is initiated — consumer
  projects opt-in to webfont URLs by injecting their own `@font-face`
  rules with `url()` entries (CSS layers candidates by family +
  weight)."
  []
  (when (and (exists? js/document)
             (.-head js/document)
             (.-createElement js/document)
             (.-getElementById js/document))
    (when-not (.getElementById js/document fonts-style-id)
      (let [node (.createElement js/document "style")]
        (set! (.-id node) fonts-style-id)
        (.appendChild node (.createTextNode js/document font-faces-css))
        (.appendChild (.-head js/document) node)))))

;; ---- per-theme CSS custom properties (rf2-5kfxe.6) ---------------------
;;
;; Emit one CSS custom-property block per theme keyed by the theme
;; class the shell carries (`rf-causa-theme-dark` / `rf-causa-theme-
;; light`). Properties land at `:root` for the active theme so any
;; descendant can read them via `var(--rf-causa-bg-1)`. The dark
;; block also publishes at `:root` *unconditionally* as a default —
;; until the shell mounts (or under a host that never adds a theme
;; class) the dark palette is the safe fallback.
;;
;; The 357 inline-style call sites keep reading dark hexes through
;; `(:bg-1 tokens)` for now; the v1.0 styling pass migrates each one
;; through to the CSS-variable surface so the toggle takes effect
;; everywhere.

(def ^:private themes-style-id
  "rf-causa-themes")

(defn- token-key->css-var
  "Map a `:bg-1` token key to a `--rf-causa-bg-1` CSS variable name.
  Pure data → string."
  [k]
  (str "--rf-causa-" (name k)))

(defn- palette->declarations
  "Build the body of a CSS rule from a palette map: `--rf-causa-<key>:
  <hex>;` one per token. Sorted for deterministic output."
  [palette]
  (->> palette
       (sort-by key)
       (map (fn [[k v]] (str "  " (token-key->css-var k) ": " v ";\n")))
       (apply str)))

(defn- themes-css
  "Build the per-theme CSS block. The dark palette publishes at `:root`
  (the safe fallback) AND at `.rf-causa-theme-dark` (so the class
  toggle has a matched landing). The light palette publishes at
  `.rf-causa-theme-light` so the class toggle activates it."
  [themes]
  (str
    ;; Default — :root carries the dark palette so any descendant
    ;; reading `var(--rf-causa-bg-1)` resolves it even before the
    ;; shell class is attached.
    ":root {\n"
    (palette->declarations (:dark themes))
    "}\n"
    ;; Explicit class blocks — `apply-theme!` (settings/effects.cljs)
    ;; writes one of these classes on the shell + `<html>` root.
    ".rf-causa-theme-dark {\n"
    (palette->declarations (:dark themes))
    "}\n"
    ".rf-causa-theme-light {\n"
    (palette->declarations (:light themes))
    "}\n"))

(defn- inject-themes-style!
  "Append the per-theme `<style>` block to `<head>`. Idempotent —
  id-keyed DOM probe."
  [themes]
  (when (and (exists? js/document)
             (.-head js/document)
             (.-createElement js/document)
             (.-getElementById js/document))
    (when-not (.getElementById js/document themes-style-id)
      (let [node (.createElement js/document "style")]
        (set! (.-id node) themes-style-id)
        (.appendChild node (.createTextNode js/document
                                            (themes-css themes)))
        (.appendChild (.-head js/document) node)))))

;; ---- atmospheric grain overlay (rf2-5kfxe.7) ---------------------------
;;
;; Spec/007 §Colour system flags 'defaulting to solid colors' as an
;; anti-pattern. Every Causa surface (bg-0 through bg-3) is currently
;; a solid hex; this commit lifts the shell root with a fractal-
;; turbulence SVG noise overlay at ~3.5% opacity. Zero JS, zero extra
;; DOM nodes — the grain is a CSS `::before` pseudo-element with a
;; data-URI SVG filter, tiled across the shell root. The browser's
;; rasterizer GPU-tiles the small SVG so the perf cost is negligible.
;;
;; The pseudo-element sits BEHIND the shell's flex children via
;; `z-index: 0` + a companion rule that lifts every direct child of
;; the shell to `z-index: 1` with `position: relative` so each region
;; (L1 ribbon / L2 list / L3 tabs / L4 panel) renders crisp on top
;; of the textured backdrop.
;;
;; The grain renders in both themes — under the light theme it
;; manifests as a subtle paper grain over the white canvas (warmer
;; than a sterile flat fill); under dark it reads as a soft 'film
;; grain' over the recessed canvas.

(def ^:private grain-style-id
  "rf-causa-grain")

(def ^:private grain-svg
  "Inline SVG: a fractalNoise filter painted into a 200x200 rect. The
  browser tiles this across the shell root via `background-repeat:
  repeat`. `baseFrequency 0.85` produces a fine-grained noise (not
  blotchy); `numOctaves 2` gives the grain a touch of structure
  without becoming a visible pattern; `stitchTiles` makes the tile
  edges seamless so the repeat is invisible."
  (str "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'>"
       "<filter id='n'>"
       "<feTurbulence type='fractalNoise' baseFrequency='0.85' "
       "numOctaves='2' stitchTiles='stitch' seed='7'/>"
       "<feColorMatrix values='0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  "
       "0 0 0 0.6 0'/>"
       "</filter>"
       "<rect width='100%' height='100%' filter='url(#n)' "
       "opacity='1'/>"
       "</svg>"))

(defn- url-encode-svg
  "Encode a small set of characters that browsers' data-URI parsers
  refuse to accept inline (`<`/`>`/`#`/`%`). The SVG's structure-
  punctuation (`'`/spaces/`=`) is left as-is for legibility — modern
  browsers accept those un-encoded inside a `data:image/svg+xml`
  payload, and the wire size is smaller for it."
  [s]
  (-> s
      (string/replace "%" "%25")  ;; must run first to not double-encode
      (string/replace "<" "%3C")
      (string/replace ">" "%3E")
      (string/replace "#" "%23")
      (string/replace "\"" "%22")))

(def ^:private grain-css
  "Rule pinned to `[data-testid='rf-causa-shell']`. The pseudo-element
  paints the noise SVG behind the shell's flex children; the
  companion rule lifts every direct child to `position: relative;
  z-index: 1` so their content paints crisp on top of the grain."
  (str
    "[data-testid=\"rf-causa-shell\"]::before {\n"
    "  content: \"\";\n"
    "  position: absolute;\n"
    "  inset: 0;\n"
    "  pointer-events: none;\n"
    "  z-index: 0;\n"
    "  opacity: 0.035;\n"
    "  background-image: url(\"data:image/svg+xml;utf8,"
    (url-encode-svg grain-svg)
    "\");\n"
    "  background-size: 200px 200px;\n"
    "  background-repeat: repeat;\n"
    "  mix-blend-mode: overlay;\n"
    "}\n"
    "[data-testid=\"rf-causa-shell\"] > * {\n"
    "  position: relative;\n"
    "  z-index: 1;\n"
    "}\n"))

(defn- inject-grain-style!
  "Append the grain `<style>` block to `<head>`. Idempotent — id-keyed
  DOM probe."
  []
  (when (and (exists? js/document)
             (.-head js/document)
             (.-createElement js/document)
             (.-getElementById js/document))
    (when-not (.getElementById js/document grain-style-id)
      (let [node (.createElement js/document "style")]
        (set! (.-id node) grain-style-id)
        (.appendChild node (.createTextNode js/document grain-css))
        (.appendChild (.-head js/document) node)))))

;; ---- motion keyframes (rf2-5kfxe.2 + rf2-5kfxe.3) ----------------------
;;
;; Both motion surfaces share one injected `<style>` block — the
;; diff-flash for App-db section changes (rf2-5kfxe.2) and the L4 tab
;; cross-fade (rf2-5kfxe.3, lands in the next commit). Co-locating
;; them keeps the global CSS surface one node instead of two.
;;
;; The diff-flash keyframes are designed so the wash holds at full
;; alpha for ~12% of the run before easing out. This is the standard
;; "snap then settle" curve — the eye locks onto the bright start
;; before the wash decays. Pure linear interpolation reads as a soft,
;; aimless fade; the brief plateau gives the motion a beat.
;;
;; Yellow ~20% alpha (#FBBF2433) is loud enough that the eye notices on
;; quick cascades but muted enough that a long burst of consecutive
;; cascades doesn't strobe.

(def ^:private motion-style-id
  "rf-causa-motion-keyframes")

(def ^:private motion-css
  "Keyframes + the reduced-motion seam (rf2-5kfxe.5).

  ## The single motion seam (rf2-5kfxe.5)

  `--rf-causa-motion-scale` is a `:root` CSS custom property —
  consumers interpolate it into their inline `animation-duration:
  calc(…ms * var(--rf-causa-motion-scale, 1))`. Default `1` runs
  motion at full duration; the `prefers-reduced-motion: reduce`
  media-query rule below sets it to `0.001` (effectively zero — a
  hair above so the keyframes still resolve to their `to` state
  rather than collapsing to undefined). One media-query write at the
  top of the cascade disables every downstream Causa animation
  without any per-component branching.

  ## Why 0.001 and not 0

  Some browsers (older Chrome) treat `animation-duration: 0s` as
  'never animate' AND 'never apply fill-mode forwards' — the element
  is stuck at the `from` state. A vanishingly small duration runs
  the keyframes to completion within a single frame, so the end
  state (transparent flash; opacity-1 tab) is reached immediately
  and the user perceives an instant resolve. That is the spirit of
  `prefers-reduced-motion: reduce` — eliminate motion but keep the
  end-state legible."
  (str
    ;; Root-level CSS custom-property defaults. The motion-scale is
    ;; the single seam every downstream animation reads through; the
    ;; accent var is published for host stylesheets too (per
    ;; config/default-accent-css-var).
    ":root {\n"
    "  --rf-causa-motion-scale: 1;\n"
    "}\n"
    ;; rf2-5kfxe.5 — reduced-motion override. Single rule, every
    ;; downstream calc(…ms * var(--rf-causa-motion-scale, 1)) collapses
    ;; to a vanishingly small duration. See ns docstring for the
    ;; 0.001-vs-0 rationale.
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-causa-motion-scale: 0.001; }\n"
    "}\n"
    ;; rf2-5kfxe.2 — diff-flash. Yellow tint at ~20% alpha (hex32 ≈ 20%)
    ;; holds for the first 12% of the run so the eye locks on, then
    ;; eases to transparent. The downstream `:animation-fill-mode:
    ;; forwards` on the section element pins the end state.
    "@keyframes rf-causa-diff-flash {\n"
    "  0%   { background-color: rgba(251, 191, 36, 0.20); }\n"
    "  12%  { background-color: rgba(251, 191, 36, 0.20); }\n"
    "  100% { background-color: rgba(251, 191, 36, 0); }\n"
    "}\n"
    ;; rf2-5kfxe.3 — L4 tab cross-fade. Opacity 0 → 1 with a 2px
    ;; translateY (the new tab rises *into* place rather than appearing
    ;; statically). Subtle enough to feel like a settle, not a slide;
    ;; characterful enough to read as a beat rather than a hard cut.
    ;; The wrapper around the case-switch in shell.cljs `detail-panel`
    ;; carries `:animation rf-causa-fade-in 180ms ease-out forwards`
    ;; on a `^{:key selected}` div so a tab switch unmounts + remounts
    ;; → keyframes auto-play from frame 0.
    "@keyframes rf-causa-fade-in {\n"
    "  from { opacity: 0; transform: translateY(2px); }\n"
    "  to   { opacity: 1; transform: translateY(0); }\n"
    "}\n"))

(defn- inject-motion-style!
  "Append the motion `<style>` block to `<head>`. Idempotent — id-keyed
  DOM probe before write."
  []
  (when (and (exists? js/document)
             (.-head js/document)
             (.-createElement js/document)
             (.-getElementById js/document))
    (when-not (.getElementById js/document motion-style-id)
      (let [node (.createElement js/document "style")]
        (set! (.-id node) motion-style-id)
        (.appendChild node (.createTextNode js/document motion-css))
        (.appendChild (.-head js/document) node)))))

;; ---- public entry ------------------------------------------------------

(defonce ^:private installed?
  ;; defonce so shadow-cljs `:after-load` doesn't re-inject. The DOM
  ;; probes inside each helper are the *real* guard; this atom just
  ;; saves the work on every render of the shell.
  (atom false))

(defn install!
  "Idempotent — call from `shell-view`'s reg-view body. Injects the
  `local()`-only `@font-face` block + motion keyframes + per-theme
  CSS custom properties + the atmospheric grain overlay on first
  paint of the shell. No third-party HTTP fetch is initiated; see
  `font-faces-css` for consumer opt-in posture on webfont URLs."
  []
  (when-not @installed?
    (inject-fonts!)
    (inject-motion-style!)
    (inject-themes-style! tokens/themes)
    (inject-grain-style!)
    (reset! installed? true))
  nil)
