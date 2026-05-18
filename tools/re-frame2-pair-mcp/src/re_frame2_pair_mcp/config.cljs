(ns re-frame2-pair-mcp.config
  "Runtime configuration for the re-frame2-pair-mcp server (rf2-cibp8).

  Phase 1 owns one concern: the 'Open in editor' preference that
  controls the URI scheme attached to every `:source-coord` map crossing
  the wire (rf2-cibp8).

  ## Why a separate config ns

  Mirrors `day8.re-frame2-causa.config/get-editor` and
  `re-frame.story.config/get-editor` — every consumer of
  `re-frame.source-coords.editor-uri/editor-uri` reads its preference
  from a per-tool atom so hosts can run multiple tools side-by-side
  with different editors. Re-frame2-pair-mcp's user is the AI agent on the
  other end of the stdio channel; the chosen editor governs the URI
  shape the agent renders as a clickable link.

  Default is `:vscode` — the most-installed editor in 2026 (Stack
  Overflow Developer Survey 2025 + JetBrains DevEcosystem 2025 both
  put it >70%) and the scheme every supported AI host recognises.

  ## Configuration surface

  Hosts pick the editor once at server start via the
  `RE_FRAME2_PAIR_MCP_EDITOR` env var (read at namespace load) or
  programmatically via `set-editor!`. The env-var path is the
  expected onboarding flow — an MCP launcher script that already
  wires `SHADOW_CLJS_BUILD_ID` adds one line:

      RE_FRAME2_PAIR_MCP_EDITOR=cursor

  ## Accepted values

  Same vocabulary as the underlying `editor-uri/editor-uri`:
  `:vscode` / `:cursor` / `:windsurf` / `:zed` / `:idea` plus the
  `{:custom \"<uri-template>\"}` form. Unknown keywords fall through
  to `:vscode` per the editor-uri ns's accommodating policy."
  (:require [applied-science.js-interop :as j]))

(def ^:private env-editor
  "Editor keyword read from the `RE_FRAME2_PAIR_MCP_EDITOR` env var at
  namespace load. Returns nil when unset / blank so the atom's default
  (`:vscode`) wins."
  (let [raw (j/get-in js/process [:env :RE_FRAME2_PAIR_MCP_EDITOR])]
    (when (and (string? raw) (seq raw))
      (keyword raw))))

(defonce
  ^{:doc "Atom holding re-frame2-pair-mcp's 'Open in editor' preference. Default
         `:vscode` (overridable via `RE_FRAME2_PAIR_MCP_EDITOR` at
         server start). Accepts the keywords `:vscode` / `:cursor` /
         `:windsurf` / `:zed` / `:idea` plus the
         `{:custom \"<uri-template>\"}` form (see
         `re-frame.source-coords.editor-uri/editor-uri`)."}
  editor
  (atom (or env-editor :vscode)))

(defn set-editor!
  "Replace re-frame2-pair-mcp's 'Open in editor' preference. `nil` resets to
  `:vscode`. Returns nothing — call for side-effect."
  [e]
  (reset! editor (or e :vscode))
  nil)

(defn get-editor
  "Return the current editor preference. Read by the wire-pipeline's
  source-URI decorator to build `:rf.source/uri` strings."
  []
  @editor)
