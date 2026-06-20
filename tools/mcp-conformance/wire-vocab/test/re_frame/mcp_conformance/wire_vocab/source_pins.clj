(ns re-frame.mcp-conformance.wire-vocab.source-pins
  "Shared source-text pin inventories + near-miss helpers.

  The wire-vocab conformance corpus pins each canonical marker literal
  against the server source/spec files where it MUST (emit-sources) or
  SHOULD (doc-sources) appear, and an anti-pin that forbids near-miss
  spellings anywhere in the conformance-tracked tree. The per-server
  file inventories and the near-miss generator are shared by several
  focused test namespaces (`wire_vocab_test`, `cursor_stale_test`,
  `result_envelope_test`, `progress_notification_test`,
  `redacted_sentinel_test`) — they live here so a new marker family adds
  ONE `:require` rather than re-deriving the inventories.

  ## The two-tier pin

  - **emit-sources** — source files where the literal MUST appear as
    actual data (not in a comment or docstring). Stripped via
    `fx/strip-comments-and-strings` before the grep. A rename in any of
    these files MUST trip the test even if a docstring elsewhere still
    carries the old form.
  - **doc-sources** — spec docs and prose-y descriptors where the
    literal SHOULD appear for human readers. Looser — a `str/includes?`
    against raw text suffices; documentation reorganisation may move
    the mention around without tripping the gate.

  The emit-side pin is the load-bearing one: a `some`-over-doc-sources
  check would pass merely because the spec docs prose-reference every
  marker, even though four of five literals never appear in
  re-frame2-pair-mcp source code at all (they are imported via
  `re-frame.mcp-base.vocab/<key>`), so a rename inside
  `mcp-base/vocab.cljc` (the canonical home of every literal) would not
  trip a doc-only gate. The emit-side pin closes that hole."
  (:require [clojure.string :as str]))

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

  story-mcp also consumes from `mcp-base/vocab.cljc` (the
  `dedup-table-key` symbol — `tools/story-mcp/src/re_frame/
  story_mcp/tools/dedup.cljc` `:require`s it). Same single-source-of-
  truth posture as pair-mcp; the emit-source path is the same file."
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

(def all-source-files
  "Union of emit-sources and doc-sources, by server. Used by the
  near-miss anti-pin: we want to forbid near-miss spellings anywhere
  in any conformance-tracked file, not just emit-sites."
  (merge-with into emit-source-files doc-source-files))

(defn marker-key->literal
  "Render a marker key as the literal string that MUST appear in the
  source. The renderer prints with the `:` prefix and the full
  namespaced form — that's what `clojure.core/pr-str` emits and what
  the source files use verbatim."
  [k]
  (pr-str k))

(defn near-miss-variants
  "Generate near-miss spellings of a marker keyword. A rename to any
  of these forms MUST NOT slip through. We check:
  - snake_case form  (`:rf.mcp/dedup_table`)
  - pluralised tail  (`:rf.mcp/overflows`)
  - all-lowercase ns (`:rf.mcp/Overflow` -> none; we already are
                      lowercase, so this variant is irrelevant for
                      these markers; included for future-proofing)
  - underscore-in-ns (`:rf_mcp/overflow`)
  The list is conservative — false positives here would block
  legitimate text in surrounding docs."
  [k]
  (let [s (pr-str k)
        ns* (namespace k)
        nm  (name k)]
    (cond-> []
      (str/includes? nm "-")
      (conj (str ":" ns* "/" (str/replace nm #"-" "_"))) ;; snake_case
      (str/includes? ns* ".")
      (conj (str ":" (str/replace ns* #"\." "_") "/" nm)) ;; ns dots -> underscores
      true
      (into [(str s "s")                                   ;; pluralised
             (str s "?")]))))                              ;; predicate form
