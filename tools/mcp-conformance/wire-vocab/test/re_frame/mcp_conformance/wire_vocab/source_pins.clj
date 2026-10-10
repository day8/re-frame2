(ns re-frame.mcp-conformance.wire-vocab.source-pins
  "Shared emit/doc source inventories.

  Emit pins scan comment/string-stripped source so documentation cannot
  satisfy a data-emission assertion. Doc pins scan raw contract text.
  Focused vocabulary tests reuse these inventories instead of defining
  partial source sets.")

(def emit-source-files
  "Per-server source files where the marker literal MUST appear as
  DATA (not in a docstring/comment). The literal is `pr-str`'d on a
  per-marker basis and grepped against the file's text AFTER
  `strip-comments-and-strings` has neutered docstring/comment mentions.

  For re-frame2-pair-mcp the canonical literal home is `mcp-base/vocab.cljc` —
  every wire marker keyword is declared once there (`overflow-key`,
  `summary-key`, `dedup-table-key`, `diff-from-key`,
  `large-elided-key`) and re-frame2-pair-mcp consumes the symbol, not the
  literal. A rename to ANY of those `def` values trips this pin
  regardless of which re-frame2-pair-mcp tool source emits the marker — which
  is the right invariant; emit-sites that import from vocab.cljc
  cannot drift independently of the canonical declaration.

  story-mcp also consumes these names from `mcp-base/vocab.cljc`, so both
  server entries intentionally pin the same production owner."
  {:re-frame2-pair-mcp ["tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"]
   :story-mcp ["tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"]})

(def doc-source-files
  "Per-server prose-y sources where the marker literal SHOULD appear
  for human readers (specs, descriptors, catalogues). Looser
  match — a raw `str/includes?` suffices; docs may rearrange prose
  without tripping the gate. Drift here means the docs lag, not that
  the emit broke."
  {:re-frame2-pair-mcp ["tools/re-frame2-pair-mcp/spec/Principles.md"
               "tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md"]
   :story-mcp []})

(defn marker-key->literal
  "Render a marker key as the literal string that MUST appear in the
  source. The renderer prints with the `:` prefix and the full
  namespaced form — that's what `clojure.core/pr-str` emits and what
  the source files use verbatim."
  [marker-key]
  (pr-str marker-key))
