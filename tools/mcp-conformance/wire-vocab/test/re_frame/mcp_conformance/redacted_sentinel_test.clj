(ns re-frame.mcp-conformance.redacted-sentinel-test
  "`:rf/redacted` scalar-sentinel vocabulary gate.

  Unlike the wrapper-shaped markers in `schemas/canonical-markers`,
  `:rf/redacted` rides the wire as a **bare keyword scalar** — a literal
  value substituted in-place for a sensitive leaf by the framework's
  `rf/elide-wire-value` walker and by the `redact-interceptor`
  interceptor. There is no map payload, no `:handle`, no re-fetch
  affordance; the value is gone. Per Spec 009 §Privacy and
  `mcp-base/vocab.cljc` `redacted-sentinel`.

  ## Why this scalar needs its own gate

  The `canonical-markers` table validates wrapped-marker-map shapes
  (`{<marker-key> <body>}`) with per-marker Malli schemas. A bare
  keyword scalar has no body to schema-validate — but it IS a wire-
  protocol contract: every agent reading sensitive-leaf data pattern-
  matches on the literal `:rf/redacted`. A rename in `vocab.cljc`
  (e.g. `:rf.size/redacted`) or a near-miss spelling that escapes the canonical-marker
  gate would slip past silently because the existing near-miss anti-
  pin only checks `:rf.mcp/*` / `:rf.size/*` marker keys (the namespace
  pattern fixed in `rf.mcp-conformance.wire-vocab.source-pins/near-miss-variants`).

  The pin shape mirrors `:rf.mcp/cursor-stale` (the other scalar
  reserved value in the cross-MCP vocabulary):
    1. literal-presence pin in the canonical declaration site
       (`mcp-base/vocab.cljc`), AFTER stripping docstrings/comments.
    2. near-miss anti-pin across all conformance-tracked sources.
    3. doc-source mention pin in re-frame2-pair-mcp prose docs (soft —
       raw `str/includes?`)."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]))

(def ^:private redacted-sentinel-near-miss-variants
  "Near-miss spellings of `:rf/redacted`. The default
  `rf.mcp-conformance.wire-vocab.source-pins/near-miss-variants` generator targets the marker-key namespace
  patterns (`:rf.mcp/*` / `:rf.size/*`) — `:rf/redacted` rides the
  single-segment `:rf/*` namespace and needs bespoke variants.

  Conservative list — false positives here would block legitimate
  occurrences in surrounding prose.

  - snake_case tail (`:rf/redact_ed`) — irrelevant for `redacted`
    (no hyphen), included as a guard if a future scalar lands with a
    multi-segment name.
  - pluralised (`:rf/redacteds`).
  - predicate `?` suffix (`:rf/redacted?`).
  - wrong-namespace forms.
  - capitalised name (`:rf/Redacted`) — caught here even though
    canonical Clojure idiom is all-lowercase."
  #{":rf/redacteds"
    ":rf/redacted?"
    ":rf.size/redacted"
    ":rf.mcp/redacted"
    ":rf.privacy/redacted"
    ":rf/Redacted"})

(deftest redacted-sentinel-literal-in-re-frame2-pair-mcp-emit-source
  ;; The canonical declaration lives in mcp-base/vocab.cljc as
  ;; `redacted-sentinel` (the single-source-of-truth `def`). Strip
  ;; comments/docstrings before grep — a rename of the `def` value
  ;; trips the gate even if old docstrings still mention the prior
  ;; literal. Mirrors `cursor-stale-literal-in-re-frame2-pair-mcp-emit-source`.
  (let [literal  ":rf/redacted"
        rel      "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc"
        stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings (rf.mcp-conformance.fixtures/read-source rel))]
    (is (str/includes? stripped literal)
        (str literal " missing from " rel
             " AFTER stripping docstrings/comments. The canonical "
             "scalar sentinel declaration moved — restore the literal "
             "or update this test."))))

(deftest redacted-sentinel-literal-in-re-frame2-pair-mcp-doc-sources
  ;; Doc-source pin — looser, raw `str/includes?` against the prose
  ;; docs catalogue. Drift here means the docs lag, not that the emit
  ;; broke.
  (let [literal ":rf/redacted"
        files   (get rf.mcp-conformance.wire-vocab.source-pins/doc-source-files :re-frame2-pair-mcp)]
    (is (some (fn [rel] (str/includes? (rf.mcp-conformance.fixtures/read-source rel) literal)) files)
        (str literal " missing from re-frame2-pair-mcp doc-sources " files
             ". The docs may have re-organised the prose; either "
             "restore the mention or update `doc-source-files`."))))

(deftest redacted-sentinel-no-near-miss-in-any-server-source
  ;; Defence-in-depth: the bespoke near-miss set above MUST NOT appear
  ;; anywhere in the conformance-tracked sources. The rf2-pv7we
  ;; doc-drift (a `:rf.size/redacted` row in `tools/mcp-base/spec/vocab.md`)
  ;; would surface here if it returned — the anti-pin catches it before
  ;; the doc ships.
  (doseq [variant        redacted-sentinel-near-miss-variants
          [server files] rf.mcp-conformance.wire-vocab.source-pins/all-source-files
          rel            files]
    (testing (str server " — " rel " — near-miss " variant)
      (is (not (str/includes? (rf.mcp-conformance.fixtures/read-source rel) variant))
          (str "Found near-miss variant " variant
               " for :rf/redacted scalar sentinel in " server "/" rel
               " — vocabulary-drift bug. The canonical form is "
               ":rf/redacted (per mcp-base/vocab.cljc `redacted-sentinel`).")))))
