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
  "Keyframes + the reduced-motion seam (rf2-5kfxe.5 expands this in a
  later commit). The seam is a `:root` CSS variable
  `--rf-causa-motion-scale` — consumers interpolate it into their
  inline `animation-duration: calc(…ms * var(--rf-causa-motion-scale, 1))`
  so a single media-query write at the top of the cascade disables every
  downstream animation."
  (str
    ;; rf2-5kfxe.2 — diff-flash. Yellow tint at ~20% alpha (hex32 ≈ 20%)
    ;; holds for the first 12% of the run so the eye locks on, then
    ;; eases to transparent. The downstream `:animation-fill-mode:
    ;; forwards` on the section element pins the end state.
    "@keyframes rf-causa-diff-flash {\n"
    "  0%   { background-color: rgba(251, 191, 36, 0.20); }\n"
    "  12%  { background-color: rgba(251, 191, 36, 0.20); }\n"
    "  100% { background-color: rgba(251, 191, 36, 0); }\n"
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
  load + motion keyframes on first paint of the shell. Future cluster
  commits add the L4 tab cross-fade keyframes, the reduced-motion
  seam, the atmospheric grain overlay, and the display-face font."
  []
  (when-not @installed?
    (inject-fonts!)
    (inject-motion-style!)
    (reset! installed? true))
  ;; Reference `tokens/tokens` so the future global-CSS injection (which
  ;; *will* consume token values) keeps this require honest under
  ;; shadow-cljs's dead-require pruning. Cheap — single keyword lookup.
  tokens/tokens
  nil)
