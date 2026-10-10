(ns re-frame.mcp-conformance.redacted-sentinel-test
  "`:rf/redacted` scalar-sentinel vocabulary gate.

  Unlike the wrapper-shaped markers in `schemas/canonical-markers`,
  `:rf/redacted` rides the wire as a **bare keyword scalar** — a literal
  value substituted in-place for a sensitive leaf by the framework's
  `rf/elide-wire-value` walker. There is no map payload, no `:handle`,
  no re-fetch
  affordance; the value is gone. Per Spec 009 §Privacy and
  `mcp-base/vocab.cljc` `redacted-sentinel`.

  A bare keyword scalar has no body to schema-validate, but it IS a
  wire-protocol contract: every agent reading sensitive-leaf data
  pattern-matches on the literal `:rf/redacted`. The pins:
    1. literal-presence pin in the canonical declaration site
       (`mcp-base/vocab.cljc`), AFTER stripping docstrings/comments.
    2. doc-source mention pin in re-frame2-pair-mcp prose docs (soft —
       raw `str/includes?`)."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]))

(deftest redacted-sentinel-literal-in-re-frame2-pair-mcp-emit-source
  ;; The canonical declaration lives in mcp-base/vocab.cljc as
  ;; `redacted-sentinel` (the single-source-of-truth `def`). Strip
  ;; comments/docstrings before grep — a rename of the `def` value
  ;; trips the gate even if old docstrings still mention the prior
  ;; literal.
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
