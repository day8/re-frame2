(ns re-frame.story.ui.open-in-editor
  "The 'Open in editor' affordance — a small chip / button that opens
  the editor at a source-coord's file:line. Per rf2-evgf5 + Spec 005-
  SOTA-Features.md §'Open in editor' per variant.

  The component reads the user's editor preference from
  `re-frame.story.config/editor` (set at boot via `story/configure!`)
  and consults `re-frame.source-coords.editor-uri/editor-uri` to build
  the URI. Click sets `window.location.href` — the OS handler chain
  dispatches the URI to the registered editor.

  ## Why `window.location.href` rather than `window.open`

  Custom URI schemes don't open new windows; they hand off to the OS
  handler. `window.open(\"vscode://...\")` opens a blank popup which
  the user has to close manually. `set-href!` triggers the same OS
  dispatch without leaving an orphaned window.

  ## When it renders nothing

  The chip render-fn returns nil when `source-coord` lacks `:file` —
  the macro layer didn't capture a usable file path (the
  `\"NO_SOURCE_PATH\"` sentinel under CLJS without a form-meta
  fallback, per rf2-mdjp). The UI hides the chip entirely so the user
  doesn't click a no-op.

  ## Bundle isolation

  Lives in the Story CLJS bundle; production builds elide the entire
  UI shell so this ns never enters a release bundle."
  (:require [re-frame.story.config :as config]
            [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---- styling -------------------------------------------------------------

(def ^:private chip-styles
  {:chip      {:padding         "2px 8px"
               :background      "#37373d"
               :color           "#9cdcfe"
               :border          "1px solid #555"
               :border-radius   "3px"
               :cursor          "pointer"
               :font-family     "monospace"
               :font-size       "10px"
               :margin-left     "8px"
               :text-decoration "none"
               :display         "inline-block"}
   :chip-test {:padding         "1px 6px"
               :background      "#252526"
               :color           "#9cdcfe"
               :border          "1px solid #444"
               :border-radius   "2px"
               :cursor          "pointer"
               :font-family     "monospace"
               :font-size       "10px"
               :margin-left     "8px"
               :text-decoration "none"
               :display         "inline-block"}})

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
  "Render an 'Open' chip for a source-coord. Reads the current editor
  preference from `config/editor`; constructs the URI via
  `editor-uri/editor-uri`; click fires `window.location.href := uri`.

  Returns nil when the source-coord has no usable `:file` slot — the
  UI hides the chip rather than showing a no-op affordance.

  `variant-of-style` is `:title` (default — for the canvas title bar)
  or `:test-detail` (for the per-test failure detail box, slightly
  more compact).

  `data-test` attribute is `\"story-open-in-editor\"` so the e2e suite
  can target the chip without relying on style selectors.

  Source-coord shape: `{:file :line :column :ns}` per
  `re-frame.source-coords` / `re-frame.story.registrar`."
  ([source-coord]
   (open-chip source-coord :title))
  ([source-coord variant-of-style]
   (when (editor-uri/has-source? source-coord)
     (let [editor (config/get-editor)
           uri    (editor-uri/editor-uri editor source-coord)
           style  (case variant-of-style
                    :test-detail (:chip-test chip-styles)
                    (:chip chip-styles))]
       (when uri
         [:a {:style       style
              :href        uri
              :title       (editor-uri/open-button-title source-coord)
              :data-test   "story-open-in-editor"
              :data-editor (cond
                             (map? editor) "custom"
                             :else         (name editor))
              :on-click    (fn [e]
                             ;; Prevent React/Reagent's default link
                             ;; navigation (otherwise the browser
                             ;; tries to render the custom URI inside
                             ;; the tab); explicitly call `open!` so
                             ;; the OS handler fires.
                             (.preventDefault e)
                             (open! uri))}
          "open"])))))

(defn open-chip-for-variant
  "Render an open-chip for a variant — reads the source-coord off the
  variant body's `:source` slot (the registrar/spec/001-captured map).
  Returns nil when no coord is present.

  `variant-body` is the body map returned by
  `re-frame.story.registrar/handler-meta :variant variant-id`."
  [variant-body]
  (open-chip (:source variant-body) :title))
