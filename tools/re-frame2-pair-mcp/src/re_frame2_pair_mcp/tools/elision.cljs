(ns re-frame2-pair-mcp.tools.elision
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

  - `snapshot` tool: each frame's `:app-db` AND `:sub-cache` slices are
    run through the walker before slice-app-db-in-snapshot sees them.
    The `:sub-cache` arm pins the Tool-Pair contract that direct reads
    of `(rf/sub-cache frame-id)` MUST route through `elide-wire-value`
    (rf2-vflrg).
  - `get-path` tool: the value at the requested path is run through
    the walker before pr-str.

  ## `:elision` MCP arg

  Boolean opt-out. Default `true`. The arg is parsed by the shared
  `re-frame2-pair-mcp.tools.args/parse-bool-arg` table (rf2-c4fmh).

  ## `:include-sensitive` MCP arg (rf2-vflrg)

  The same `:include-sensitive` flag that gates trace / epoch
  forwarding (spec/009 §Privacy) also gates whether the walker treats
  declared-sensitive slots as pass-through (`:rf.size/include-sensitive?
  true`) or substitutes them with the `:rf/redacted` sentinel
  (`:rf.size/include-sensitive? false`, the default). Off-box default
  per Tool-Pair §`Direct-read privacy posture for sub-cache and
  get-path`: sensitive slots are dropped unless the caller opts in
  explicitly. The MCP wire-key drops the trailing `?` per rf2-y710n +
  rf2-ihq4d (Anthropic's tool-input-schema regex rejects `?`); the
  walker-option keyword `:rf.size/include-sensitive?` is a namespaced
  framework key (not on the wire) and retains the predicate `?`."
  (:require [re-frame.mcp-base.vocab :as base-vocab]))

(defn elision-opts-edn
  "Render the elision opts map as an EDN string for inlining into a
  CLJS eval form sent over nREPL.

  Knobs:

  - `enabled?`            — when true, `:rf.size/include-large?` is
                            `false` so the walker emits markers; when
                            false, `true` so values pass through
                            unmodified.
  - `include-sensitive?`  — when true, `:rf.size/include-sensitive?` is
                            `true` so the walker passes declared-
                            sensitive slots through unmodified; when
                            false (the default), `false` so the walker
                            substitutes the `:rf/redacted` sentinel.

  Both knobs default off-box-safe per the Tool-Pair §Direct-read
  privacy posture contract — large slots elide, sensitive slots
  redact, unless the caller opts in explicitly.

  Single-arity form retains the legacy default (`include-sensitive?`
  false) so legacy call-sites don't need to spell it out."
  ([enabled?]
   (elision-opts-edn enabled? false))
  ([enabled? include-sensitive?]
   (pr-str {base-vocab/include-large-opt     (not enabled?)
            base-vocab/include-sensitive-opt (boolean include-sensitive?)})))
