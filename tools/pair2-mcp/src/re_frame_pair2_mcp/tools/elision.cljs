(ns re-frame-pair2-mcp.tools.elision
  "Size-elision wire markers (rf2-urjnc).

  The sixth wire-protocol mechanism — alongside `:rf.mcp/summary`,
  `:rf.mcp/overflow`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, and
  `:rf.mcp/cache-hit` (rf2-3rt1f). After diff-encoding (rf2-1wdzp)
  collapses each `:db-after`, and dedup (rf2-obpa9) pools repeated
  subtrees, a single large slot — say a 100KB uploaded PDF base64 on
  `[:user :uploaded-pdf]` — still rides the wire verbatim. The
  framework's size-elision walker (`rf/elide-wire-value`, rf2-v9tw2)
  substitutes such slots with a `{:rf.size/large-elided {...}}` marker
  carrying a fetch handle (`[:rf.elision/at <path>]`). Agents drill
  back into the slot via `get-path` using the handle's path.

  ## Where in the pipeline

  Elision runs FIRST — server-side inside the eval form, where the
  frame's `[:rf/elision]` registry is reachable. The MCP server gets
  back data that already carries `:rf.size/large-elided` markers in
  place of declared / over-threshold slots. The downstream pipeline
  (path-slicing → diff-encode → dedup → wire-cap) operates on the
  post-elision payload — cap measures post-elision bytes, so a single
  declared-large slot can't blow the cap on its own.

  ## Where it fires

  - `snapshot` tool: each frame's `:app-db` slice is run through the
    walker before slice-app-db-in-snapshot sees it.
  - `get-path` tool: the value at the requested path is run through
    the walker before pr-str.

  ## `:elision` MCP arg

  Boolean opt-out. Default `true`."
  (:require [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.vocab :as base-vocab]))

(defn parse-elision-arg
  "Normalise the `elision` MCP arg into a boolean. Default `true` —
  least-surprise on the budget-sensitive default fires elision.
  Delegates to `re-frame.mcp-base.args/parse-boolean` (rf2-vw4sq)."
  [raw]
  (base-args/parse-boolean raw true))

(defn elision-opts-edn
  "Render the elision opts map as an EDN string for inlining into a
  CLJS eval form sent over nREPL. Today the only knob is the
  on/off boolean (`base-vocab/include-large-opt`): when elision is
  enabled we pass `{:rf.size/include-large? false}` so the walker
  emits markers; when disabled we set `:rf.size/include-large? true`
  so values pass through unmodified. `:frame` and `:path` are
  caller-supplied at the call-site inside the form."
  [enabled?]
  (pr-str {base-vocab/include-large-opt (not enabled?)}))
