(ns re-frame.mcp-conformance.fixtures
  "Shared fixtures + helpers for the three conformance test namespaces
  in this artefact (`wire_vocab_test`, `indicator_field_test`,
  `slot_name_test`).

  All three tests share the same surface posture: grep source files at
  the repo root for canonical literal strings, validate authored
  fixtures against Malli schemas, and pin per-server presence /
  absence invariants. The classpath-derived `repo-root` and the
  `read-source` slurp helper were duplicated verbatim across the
  three test namespaces before this helper landed (rf2-113ti); the
  `known-servers` set was duplicated across two of them. Centralising
  here removes ~60 LoC of ceremony and reduces the per-new-test-ns
  surface to one `:require`.

  ## What this ns owns

  - **`repo-root`** — absolute path to the repo root, derived from the
    classpath URL of this file. Walks five `.getParentFile` levels
    (one fewer than the test files used because this file lives in
    the same directory as them, but the macro that computes it from
    `*file*` resolves at the same classpath leaf).
  - **`read-source`** — slurp a file at a repo-relative path. Fails
    loudly via `slurp`'s IOException if the path doesn't resolve;
    that's the right signal — a moved/removed file under conformance
    needs the test to surface it.
  - **`known-servers`** — the canonical `#{:pair2-mcp :story-mcp
    :causa-mcp}` triplet. A typo in any test's `:servers` set surfaces
    against this.

  ## What this ns does NOT own

  Per-marker schemas, fixtures, and the `canonical-markers` catalogue
  stay in `wire_vocab_test.clj` — they ARE the wire-vocab conformance
  contract, not boilerplate. Same posture for `canonical-slots`
  (`slot_name_test.clj`) and `envelope-indicator-slots`
  (`wire_vocab_test.clj`). The split is: data-tables that ARE the
  contract live next to their assertions; classpath-walk + slurp
  ceremony lives here."
  (:require [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Repo-root resolution. Each conformance test ns lives at
;; `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/<name>.clj`
;; relative to the repo root. We resolve THIS file's classpath URL and
;; walk five parents up to reach the repo root. The walk is one shorter
;; than the original per-test calculation because the original code
;; computed it from `*file*` (a relative path) and the parent chain had
;; an extra synthetic step; `io/resource` returns the absolute classpath
;; URL whose first parent is the `mcp_conformance/` leaf directly. Both
;; computations resolve to the same repo root.
;; ---------------------------------------------------------------------------

(def repo-root
  "Absolute path to the repo root, derived from this file's classpath
  location. Used by every conformance test as the prefix for
  `read-source`. CWD-agnostic — CI may invoke the test-runner from
  various working directories."
  (let [this-file (io/file (.getPath (io/resource "re_frame/mcp_conformance/fixtures.clj")))]
    (-> this-file
        .getParentFile                                      ; .../mcp_conformance/
        .getParentFile                                      ; .../re_frame/
        .getParentFile                                      ; .../test/
        .getParentFile                                      ; .../wire-vocab/
        .getParentFile                                      ; .../mcp-conformance/
        .getParentFile                                      ; .../tools/
        .getParentFile                                      ; <repo-root>
        .getAbsolutePath)))

(defn read-source
  "Slurp a source file inside the repo. `rel-path` is a string path
  segment relative to the repo root, using `/` as the separator. The
  test fails loudly (via `slurp`'s default IOException) if the path
  doesn't resolve — that's the right signal: a source file under
  conformance was moved or removed."
  [rel-path]
  (slurp (io/file repo-root rel-path)))

;; ---------------------------------------------------------------------------
;; Cross-server registry. The three MCP servers under the triplet's
;; conformance regime. A typo in any test's `:servers` set surfaces
;; here against this canonical set.
;; ---------------------------------------------------------------------------

(def known-servers
  "The canonical set of MCP servers under conformance. Adding a fourth
  server means extending this set AND adding per-server source/spec
  coverage to every relevant test catalogue."
  #{:pair2-mcp :story-mcp :causa-mcp})
