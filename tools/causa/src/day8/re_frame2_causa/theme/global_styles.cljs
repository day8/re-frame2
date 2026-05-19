(ns day8.re-frame2-causa.theme.global-styles
  "One-shot global style injection for the Causa shell.

  Owns the `<head>` writes that the panel inline-style discipline
  can't reach: web-font `<link>`s today; future cluster commits add
  `@keyframes` and the reduced-motion seam here so the public surface
  stays one `install!` call from `shell-view`.

  Idempotent — `defonce`-guarded *and* DOM-probed via fixed `id`
  attributes — so shadow-cljs `:after-load` reloads, repeated shell
  mounts, and `defonce` resets all converge to a single set of nodes
  in `<head>`.

  ## Why a separate ns from `shell.cljs`

  `shell.cljs` already carries `inject-scrollbar-style!` scoped to the
  L2 list. The styles installed here are *global* (every paint of the
  shell needs the fonts loaded; every animation downstream reads from
  the `:root` motion-scale seam) so they want their own lifetime + a
  clean test surface. Owning a dedicated ns also keeps the public
  contract obvious: a single `install!` call from `shell-view`.

  ## Lifetime

  `install!` is invoked once from `shell.cljs`'s `shell-view` reg-view
  body. It guards against `js/document` being absent (node-test) and
  uses fixed `id` attributes on the `<style>` / `<link>` nodes so a
  hot-reload that resets the `defonce` atom would still no-op when
  the DOM node is already present."
  (:require [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- font loading (rf2-5kfxe.1) ----------------------------------------
;;
;; Inter + JetBrains Mono are the brand faces per spec/007 §Typography
;; (~80KB Inter + ~100KB JetBrains Mono). They appear in `tokens/sans-
;; stack` + `tokens/mono-stack` as the FIRST entries of their fallback
;; cascades, but no `@font-face` / stylesheet was wired anywhere —
;; bare-metal dev machines that don't have them installed silently
;; resolved to `system-ui` / `Menlo`, never seeing the brand face.
;;
;; Mechanism: a `<link rel='stylesheet'>` to Google Fonts. The endpoint
;; serves WOFF2s with `display=swap`, which renders the fallback
;; immediately and swaps to the brand face when the WOFF2 lands — no
;; FOIT, no perceived layout shift (the metrics-bounding boxes of
;; Inter vs. system-ui are within ~2% so the swap is a font weight
;; change, not a reflow). Two `<link rel='preconnect'>` hints open the
;; sockets to the CSS host + the font-file host in parallel with the
;; stylesheet parse — shaves 100-200ms off the WOFF2 round-trip on
;; cold loads.

(def ^:private fonts-link-id
  "rf-causa-fonts-link")

(def ^:private fonts-href
  "Google Fonts CSS endpoint that ships Inter (400/500/600/700) and
  JetBrains Mono (400/500/600/700) as WOFF2 with `font-display: swap`.
  Single CDN request → two font families → file fetches on demand."
  (str "https://fonts.googleapis.com/css2"
       "?family=Inter:wght@400;500;600;700"
       "&family=JetBrains+Mono:wght@400;500;600;700"
       "&display=swap"))

(defn- append-link!
  "Idempotent `<link>` append. `attrs` is a vector of [k v] tuples
  applied via setAttribute (crossorigin needs `setAttribute` rather
  than property assignment to render `crossorigin=\"\"`)."
  [id rel href attrs]
  (when-not (.getElementById js/document id)
    (let [node (.createElement js/document "link")]
      (set! (.-id node) id)
      (set! (.-rel node) rel)
      (set! (.-href node) href)
      (doseq [[k v] attrs]
        (.setAttribute node k v))
      (.appendChild (.-head js/document) node))))

(defn- inject-fonts!
  "Append the Google Fonts `<link>` + the two preconnect hints to
  `<head>`. No-op when the nodes already exist or when `js/document`
  is absent (node-test)."
  []
  (when (and (exists? js/document)
             (.-head js/document)
             (.-createElement js/document)
             (.-getElementById js/document))
    ;; Preconnect hints — open the TCP/TLS sockets to both Google Fonts
    ;; hosts in parallel with the stylesheet parse. The font-files host
    ;; needs `crossorigin` (it serves WOFF2s with CORS).
    (append-link! "rf-causa-fonts-preconnect-css"
                  "preconnect"
                  "https://fonts.googleapis.com"
                  [])
    (append-link! "rf-causa-fonts-preconnect-files"
                  "preconnect"
                  "https://fonts.gstatic.com"
                  [["crossorigin" ""]])
    ;; The stylesheet link itself.
    (append-link! fonts-link-id "stylesheet" fonts-href [])))

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
  "Idempotent — call from `shell-view`'s reg-view body. Triggers font
  load + motion keyframes + per-theme CSS custom properties on first
  paint of the shell. Future cluster commits add the atmospheric
  grain overlay and the display-face font."
  []
  (when-not @installed?
    (inject-fonts!)
    (inject-motion-style!)
    (inject-themes-style! tokens/themes)
    (reset! installed? true))
  nil)
