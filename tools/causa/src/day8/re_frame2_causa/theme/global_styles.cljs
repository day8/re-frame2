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
    ;; font-size knob (rf2-n8i2c) anchors the entire type scale —
    ;; every `type-scale` entry resolves as
    ;; `calc(var(--rf-causa-font-size, 13px) * <multiplier>)` so
    ;; overriding the knob at `:root` (host stylesheet or DevTools)
    ;; rescales the whole UI in lockstep.
    ":root {\n"
    "  --rf-causa-motion-scale: 1;\n"
    "  " tokens/font-size-var-name ": " tokens/font-size-default ";\n"
    "}\n"
    ;; rf2-5kfxe.5 — reduced-motion override. Single rule, every
    ;; downstream calc(…ms * var(--rf-causa-motion-scale, 1)) collapses
    ;; to a vanishingly small duration. See ns docstring for the
    ;; 0.001-vs-0 rationale.
    "@media (prefers-reduced-motion: reduce) {\n"
    "  :root { --rf-causa-motion-scale: 0.001; }\n"
    "}\n"
    ;; rf2-ybjkx — user-side override of the OS media query. A body /
    ;; <html> class set by `settings/effects.cljs/apply-reduced-motion-
    ;; override!` overrides the media-query-derived value:
    ;;
    ;;   .rf-causa-motion-override-always  — always reduced (matches
    ;;                                       the @media rule above)
    ;;   .rf-causa-motion-override-never   — always full motion (even
    ;;                                       when the OS prefers reduce)
    ;;
    ;; Higher specificity (a single class selector outranks `:root`)
    ;; and authored AFTER the media-query rule so it wins on equal
    ;; specificity collisions too — covers the legacy media rule
    ;; injection order. The selector targets `:where(html, body)` so
    ;; either node carrying the class flips the var without bumping
    ;; specificity to the point that downstream consumer overrides
    ;; can't beat it.
    ".rf-causa-motion-override-always:where(html, body), \n"
    ":where(html, body).rf-causa-motion-override-always {\n"
    "  --rf-causa-motion-scale: 0.001;\n"
    "}\n"
    ".rf-causa-motion-override-never:where(html, body), \n"
    ":where(html, body).rf-causa-motion-override-never {\n"
    "  --rf-causa-motion-scale: 1;\n"
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
    "}\n"
    ;; rf2-fxde5 — global `:focus-visible` focus ring. Causa-wide
    ;; keyboard-only focus indicator scoped to descendants of the
    ;; shell roots (`[data-testid="rf-causa-shell"]` for Runtime,
    ;; `[data-testid="rf-causa-static-shell"]` for Static). Many
    ;; interactive elements set `:border "none"` and rely on the
    ;; UA outline, which is suppressed by various theme resets and
    ;; reads weakly against the dark `#0E0F12` background. The
    ;; palette input explicitly sets `outline: none` (palette/view
    ;; line 107). Without this rule keyboard-only users have no
    ;; reliable focus indicator anywhere in Causa.
    ;;
    ;; Token: `:yellow #FBBF24` from `theme/tokens.cljc` — the warm
    ;; amber matches Causa's design language (sibling to Story's
    ;; `#F5A524` amber focus ring at `theme/motion.cljc:173`). 2px
    ;; outline + 2px offset is the documented high-contrast hit
    ;; threshold. `:focus-visible` (rather than `:focus`) ensures
    ;; the ring only paints for keyboard navigation, not mouse
    ;; clicks — matches platform expectations.
    "[data-testid=\"rf-causa-shell\"] *:focus-visible,\n"
    "[data-testid=\"rf-causa-static-shell\"] *:focus-visible,\n"
    "[data-testid=\"rf-causa-palette-backdrop\"] *:focus-visible {\n"
    "  outline: 2px solid #FBBF24;\n"
    "  outline-offset: 2px;\n"
    "  border-radius: 3px;\n"
    "}\n"
    ;; rf2-wxepo — Windows High Contrast Mode (forced-colors). The UA
    ;; strips inline `:background` and `:color` declarations and forces
    ;; its own palette (Canvas / CanvasText / Highlight / …), which
    ;; collapses every author-encoded signal across the Causa chrome:
    ;;
    ;;   - L1 ribbon mode stripe (violet/cyan accent on the left edge)
    ;;   - L2 row focused border (cyan)
    ;;   - L2 row status accent (the 2px inset box-shadow on the
    ;;     trailing edge — box-shadow itself is also dropped in HCM)
    ;;   - L2 row gutter causal-chain thread (1px violet inset)
    ;;   - L4 panel-domain accent stripes (3px left border in each
    ;;     panel's hue — violet/cyan/orange/green/yellow/red)
    ;;   - Focus-visible amber outline (the #FBBF24 hex above; the UA
    ;;     forces this to its own Highlight regardless of author intent)
    ;;   - Secondary / tertiary text (drifted greys collapse to a
    ;;     single CanvasText hue)
    ;;
    ;; The remedy is to map each signal onto a CSS *system colour
    ;; keyword* inside `@media (forced-colors: active)`. System tokens
    ;; are the ONE class of colour the UA accepts and honours in HCM
    ;; — every other hex is overridden. Each rule is `!important` so
    ;; the per-element inline-style declarations are beaten on
    ;; specificity (inline style normally wins over external CSS;
    ;; `!important` in CSS reverses that).
    ;;
    ;; Token mapping:
    ;;   Highlight    — focus ring, focused row, mode stripe, gutter
    ;;                  thread, focus markers (the user's "what's
    ;;                  selected/active" hue under their HCM theme)
    ;;   CanvasText   — primary text + neutral border accents (the
    ;;                  default ink colour of the HCM theme)
    ;;   Mark         — status-relevant emphasis (errored row accent;
    ;;                  semantically the "important emphasis" system
    ;;                  hue, distinct from Highlight so the operator
    ;;                  can still tell "error" from "selected")
    ;;   GrayText     — secondary / tertiary text + stale / paused
    ;;                  state (the HCM theme's "disabled / muted" hue)
    ;;   ButtonText   — interactive button/icon ink (chevrons, ✕,
    ;;                  ribbon icons) so they read as actionable
    ;;   LinkText     — hyperlink ink (only sparingly used in Causa
    ;;                  chrome, but covered for completeness)
    ;;
    ;; Signal preservation is the criterion: under HCM the operator
    ;; must still distinguish focused-vs-not, error-vs-success,
    ;; in-flight-vs-stale, primary-vs-secondary text. The mapping
    ;; below preserves those distinctions even when every author hex
    ;; is forced.
    "@media (forced-colors: active) {\n"
    ;; Focus-visible amber → Highlight. The UA already forces the
    ;; outline colour, but writing it explicitly guarantees the
    ;; correct semantic system-token is requested (some UAs honour
    ;; author-specified system colours and skip the forced override).
    "  [data-testid=\"rf-causa-shell\"] *:focus-visible,\n"
    "  [data-testid=\"rf-causa-static-shell\"] *:focus-visible,\n"
    "  [data-testid=\"rf-causa-palette-backdrop\"] *:focus-visible {\n"
    "    outline-color: Highlight !important;\n"
    "  }\n"
    ;; L1 ribbon mode stripe (violet runtime / cyan static) → Highlight.
    ;; The 2px left border is the operator's "which mode am I in"
    ;; signal; preserve it as the user's selected-emphasis hue.
    "  [data-testid=\"rf-causa-ribbon\"] {\n"
    "    border-left-color: Highlight !important;\n"
    "  }\n"
    ;; L2 focused event row — `aria-pressed=\"true\"` rides on the
    ;; focused row's `<li>`. The 1px solid cyan border becomes a
    ;; Highlight outline (outline composes over the existing border
    ;; without disturbing layout; HCM strips inline background, so
    ;; the row's fill becomes Canvas + the Highlight outline reads
    ;; as the selection signal).
    "  [data-testid^=\"rf-causa-event-row-\"][aria-pressed=\"true\"] {\n"
    "    outline: 2px solid Highlight !important;\n"
    "    outline-offset: -1px !important;\n"
    "  }\n"
    ;; L2 row status accents — read off `data-rf-causa-status`. The
    ;; box-shadow inset that paints the trailing-edge stripe is
    ;; dropped by HCM, so we re-introduce the signal as a right-edge
    ;; outline-ish border via box-shadow with a system colour (which
    ;; HCM honours). `Mark` reads as 'important emphasis' and is the
    ;; idiomatic token for error / warning accents distinct from
    ;; selection (Highlight).
    "  [data-rf-causa-status=\"settled-error\"] {\n"
    "    box-shadow: inset -2px 0 0 0 Mark !important;\n"
    "  }\n"
    ;; In-flight (still running) → Highlight (it's the 'active' row).
    "  [data-rf-causa-status=\"in-flight\"] {\n"
    "    box-shadow: inset -2px 0 0 0 Highlight !important;\n"
    "  }\n"
    ;; Settled-success → CanvasText (neutral; success is the absence
    ;; of an alarm, so a quiet ink-coloured stripe reads as 'done,
    ;; no problem'). Distinguishable from in-flight + error because
    ;; the system tokens render differently under every HCM theme.
    "  [data-rf-causa-status=\"settled-success\"] {\n"
    "    box-shadow: inset -2px 0 0 0 CanvasText !important;\n"
    "  }\n"
    ;; Stale / paused-by-tool → GrayText. Both states are the 'muted /
    ;; not-currently-live' family; the disabled-text system hue is
    ;; the canonical match.
    "  [data-rf-causa-status=\"stale\"],\n"
    "  [data-rf-causa-status=\"paused-by-tool\"] {\n"
    "    box-shadow: inset -2px 0 0 0 GrayText !important;\n"
    "  }\n"
    ;; L2 row gutter — the 1px causal-chain thread (violet) and the
    ;; focus markers (⦿ / ◌) need a system-token landing. The gutter
    ;; <span> doesn't carry a testid hook, so we target the
    ;; row-gutter testid pattern. The inset box-shadow becomes
    ;; Highlight; the marker colour becomes Highlight too so the
    ;; focus-set anchor reads at a glance.
    "  [data-testid^=\"rf-causa-row-gutter-\"] {\n"
    "    box-shadow: inset 1px 0 0 0 Highlight !important;\n"
    "    color: Highlight !important;\n"
    "  }\n"
    ;; L4 panel domain accent stripes (3px left border on the panel
    ;; <h1>) — every panel hue (violet/cyan/orange/green/yellow/red)
    ;; collapses to CanvasText so the stripe still paints as a
    ;; visible left edge. Panels remain visually distinguishable by
    ;; their L3 tab label and their content; the stripe drops its
    ;; per-panel colour information but keeps its presence as a
    ;; rhythm marker. We target the `<h1>` elements inside the L4
    ;; panel slot via the `rf-causa-detail-panel-*` testid prefix.
    "  [data-testid^=\"rf-causa-detail-panel-\"] h1 {\n"
    "    border-left-color: CanvasText !important;\n"
    "  }\n"
    ;; Ribbon icons + close-X — keep them as ButtonText so they
    ;; read as actionable. The Settings ✕ accent + the right-cluster
    ;; icons inherit through this rule.
    "  [data-testid=\"rf-causa-icon-settings\"],\n"
    "  [data-testid=\"rf-causa-icon-close\"],\n"
    "  [data-testid=\"rf-causa-nav-prev\"],\n"
    "  [data-testid=\"rf-causa-nav-next\"],\n"
    "  [data-testid=\"rf-causa-nav-head\"],\n"
    "  [data-testid=\"rf-causa-focus-chip-clear\"] {\n"
    "    color: ButtonText !important;\n"
    "  }\n"
    ;; Hyperlinks inside Causa chrome → LinkText. Sparingly used
    ;; (open-in-editor anchors, doc links) but covered so every
    ;; HCM-relevant interactive surface has a system-token landing.
    "  [data-testid=\"rf-causa-shell\"] a,\n"
    "  [data-testid=\"rf-causa-static-shell\"] a {\n"
    "    color: LinkText !important;\n"
    "  }\n"
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
