(ns day8.re-frame2-causa.config
  "Compile-time and runtime configuration for Causa.

  Phase 1 holds a single config concern: the 'Open in editor' preference
  (rf2-evgf5). Future phases extend this with theme defaults, buffer
  depth, panel placement, etc.

  ## Why a separate config ns

  The host application sets the Causa editor preference via
  `(day8.re-frame2-causa.config/set-editor! :cursor)` at boot. Holding
  the preference behind an atom in a dedicated ns lets the UI shell
  read it without importing the host's boot code.

  Causa's editor preference is **independent** of Story's. Hosts that
  run both tools may want different editors (e.g. VS Code for app
  development, IntelliJ for the test reading workflow); two atoms,
  two `configure!` surfaces.

  ## Production posture

  The atom is a plain Clojure data store. Production builds DCE the
  Causa shell (gated on `interop/debug-enabled?` in preload.cljs); the
  atom survives but is never read. CLJC so the JVM test corpus can
  cover the round-trip without a CLJS runtime."
  (:require [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---- editor preference ---------------------------------------------------

(defonce
  ^{:doc "Atom holding Causa's 'Open in editor' preference. Default
         `:vscode`. Accepts the keywords `:vscode` / `:cursor` /
         `:idea` plus the `{:custom \"<uri-template>\"}` form (see
         `re-frame.source-coords.editor-uri/editor-uri`)."}
  editor
  (atom :vscode))

(defn set-editor!
  "Set Causa's 'Open in editor' preference. Hosts call this once at
  boot (typically inside their `app.core` ns alongside any Causa-
  specific setup).

  Accepts:
    - `:vscode` (default) — `vscode://file/<path>:<line>:<column>`
    - `:cursor`           — `cursor://file/<path>:<line>:<column>`
    - `:idea`             — `idea://open?file=<path>&line=<line>&column=<column>`
    - `{:custom <uri-template>}` — user template with `{path}` / `{file}` /
                                   `{line}` / `{column}` placeholders.
    - `nil`               — reset to `:vscode` default.

  Returns nothing."
  [e]
  (reset! editor (or e :vscode))
  nil)

(defn get-editor
  "Return the current editor preference."
  []
  @editor)

;; ---- configure! convenience ---------------------------------------------

(defn configure!
  "Top-level Causa configuration. Phase 1 accepts `{:editor <kw>}` per
  rf2-evgf5; future phases extend with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-causa.config :as causa-config])
      (causa-config/configure! {:editor :cursor})

  Returns nothing."
  [{:keys [editor] :as _opts}]
  (when (some? editor)
    (set-editor! editor))
  nil)

;; ---- pass-through: editor-uri --------------------------------------------

(defn editor-uri
  "Build an 'Open in editor' URI for `source-coord` using Causa's
  configured editor. Thin wrapper around
  `re-frame.source-coords.editor-uri/editor-uri` that reads the current
  preference from the atom. Returns a string URI, or nil when the
  source-coord has no `:file`."
  [source-coord]
  (editor-uri/editor-uri (get-editor) source-coord))
