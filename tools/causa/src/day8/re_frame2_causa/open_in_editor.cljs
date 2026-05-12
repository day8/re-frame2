(ns day8.re-frame2-causa.open-in-editor
  "Causa-side 'Open in editor' chip (rf2-evgf5).

  Mirrors `re-frame.story.ui.open-in-editor` — same affordance shape,
  reads Causa's editor preference from `day8.re-frame2-causa.config`.

  Per the bead's scope expansion (2026-05-13): Causa panels that
  display a source-coord (event-detail hero, six-domino cascade,
  machine inspector state/edge/guard/action, hydration debugger, etc.)
  wrap the coord in a clickable chip that launches the user's editor
  at file:line. Phase 1 ships the helper + the shell-stub demo
  surface; the live panels consume this in subsequent phases."
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
  the OS handler chain. Returns nothing."
  [uri]
  (when (and uri js/window)
    (set! (.-location js/window) uri)
    nil))

;; ---- public: the open-in-editor chip ------------------------------------

(defn open-chip
  "Render an 'open' chip for a Causa source-coord. Reads the current
  editor preference from `config/get-editor`; builds the URI via
  `editor-uri/editor-uri`; click fires `window.location.href`.

  Returns nil when the source-coord lacks a usable `:file` slot — the
  UI hides the chip rather than rendering a no-op.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords`. Causa receives source-coords on the trace
  events it buffers (`:source-coord` slot) and on the registry's
  `(rf/handler-meta kind id)` reads."
  [source-coord]
  (when (editor-uri/has-source? source-coord)
    (let [editor (config/get-editor)
          uri    (editor-uri/editor-uri editor source-coord)]
      (when uri
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
