(ns day8.re-frame2-causa.open-in-editor
  "Causa-side 'Open in editor' chip (rf2-evgf5).

  Mirrors `re-frame.story.ui.open-in-editor` — same affordance shape,
  reads Causa's editor preference from `day8.re-frame2-causa.config`.

  Per the bead's scope expansion (2026-05-13): Causa panels that
  display a source-coord (event-detail hero, six-domino cascade,
  machine inspector state/edge/guard/action, hydration debugger, etc.)
  wrap the coord in a clickable chip that launches the user's editor
  at file:line. Phase 1 ships the helper + the shell-stub demo
  surface; the live panels consume this in subsequent phases.

  ## Defense-in-depth scheme allowlist (rf2-cm93v / rf2-p887o)

  `editor-uri/editor-uri` already rejects `javascript:` / `data:` /
  `vbscript:` for `{:custom ...}` templates (per rf2-vwcsq). The
  click-time seam below layers a positive-allowlist gate on top
  (`editor-uri/allowed-uri?`): before assigning to `window.location`
  we verify the final URI's scheme is in
  `editor-uri/allowed-editor-uri-schemes`. Anything outside the set
  (in particular `http:` / `https:` / new bad schemes we did not
  anticipate) is rejected at the click-time seam. Per spec/Security.md
  §Pragmatic stance the rationale is 'gate accidents, not theoretical
  attacks' — a custom template that resolves to `https://...` would
  navigate the page rather than launch an editor; the allowlist makes
  that an obvious no-op rather than a silent surprise.

  The allowlist itself lives in the shared `editor-uri` ns (rf2-p887o)
  so Story consumes the same predicate Causa does."
  (:require [day8.re-frame2-causa.config :as config]
            [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  ;; Aligned with the Causa shell's dark-theme tokens
  ;; (`day8.re-frame2-causa.shell/tokens`). Inline styles in Phase 1;
  ;; the v1 styling pass moves these to CSS variables when the per-
  ;; panel beads land.
  {:chip {:padding         "1px 8px"
          :background      "transparent"
          :color           "#7C5CFF"
          :border          "1px solid #2F3441"
          :border-radius   "3px"
          :cursor          "pointer"
          :font-family     "JetBrains Mono, ui-monospace, SF Mono, Menlo, monospace"
          :font-size       "10px"
          :margin-left     "8px"
          :text-decoration "none"
          :display         "inline-block"
          :line-height     "16px"}})

;; ---- side-effect: open the editor ----------------------------------------

(defn- open!
  "Set `window.location.href` to `uri`. Custom URI schemes hand off to
  the OS handler chain. Returns nothing.

  Per rf2-vwcsq: `uri` is the return of `editor-uri/editor-uri`, which
  already rejects `javascript:` / `data:` / `vbscript:` schemes by
  returning `nil`. Per rf2-cm93v this fn applies a second, positive
  allowlist gate (`editor-uri/allowed-uri?`) before handing the URI
  off — closing the `http:` / `https:` / unknown-scheme path that a
  `{:custom ...}` template could otherwise resolve to. A rejected URI
  is a click-time no-op (no navigation, no console noise — the chip's
  `(when uri ...)` earlier guard handles the absent case; the
  allowlist handles the shaped-but-disallowed case)."
  [uri]
  (when (and uri (editor-uri/allowed-uri? uri) js/window)
    (set! (.-location js/window) uri)
    nil))

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'open' chip for a Causa source-coord. Reads the current
  editor preference from `config/get-editor`; builds the URI via
  `editor-uri/editor-uri`; click fires `window.location.href`.

  Returns nil when the source-coord lacks a usable `:file` slot, when
  `editor-uri/editor-uri` returns nil (a scheme rejected by the
  `editor-uri`-side gate per rf2-vwcsq), or when the resolved URI's
  scheme is not in `editor-uri/allowed-editor-uri-schemes` (the
  shared positive allowlist per rf2-cm93v / rf2-p887o). The UI hides
  the chip rather than rendering an unclickable affordance.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords`. Causa receives source-coords on the trace
  events it buffers (`:source-coord` slot) and on the registry's
  `(rf/handler-meta kind id)` reads."
  [source-coord]
  (when (editor-uri/has-source? source-coord)
    (let [editor (config/get-editor)
          uri    (editor-uri/editor-uri editor source-coord)]
      (when (and uri (editor-uri/allowed-uri? uri))
        [:a {:style       (:chip chip-styles)
             :href        uri
             :title       (editor-uri/open-button-title source-coord)
             :data-testid "causa-open-in-editor"
             :data-editor (cond
                            (map? editor) "custom"
                            :else         (name editor))
             :on-click    (fn [e]
                            ;; Stop the browser from trying to render
                            ;; the custom URI inline; fire the handoff
                            ;; explicitly so the OS scheme handler
                            ;; dispatches.
                            (.preventDefault e)
                            (open! uri))}
         "open"]))))
