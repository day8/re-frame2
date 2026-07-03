(ns re-frame.mcp-conformance.fixtures
  "Shared fixtures + helpers for the three conformance test namespaces
  in this artefact (`wire_vocab_test`, `indicator_field_test`,
  `slot_name_test`).

  All three tests share the same surface posture: grep source files at
  the repo root for canonical literal strings, validate authored
  fixtures against Malli schemas, and pin per-server presence /
  absence invariants. The classpath-derived `repo-root`, the
  `read-source` slurp helper, and the `known-servers` set are
  centralised here so each test namespace pulls them in via one
  `:require` rather than re-deriving the classpath-walk + slurp
  ceremony.

  ## What this ns owns

  - **`repo-root`** — absolute path to the repo root, derived from the
    classpath URL of this file. Walks seven `.getParentFile` levels
    (mcp_conformance → re_frame → test → wire-vocab → mcp-conformance
    → tools → repo-root).
  - **`read-source`** — slurp a file at a repo-relative path. Fails
    loudly via `slurp`'s IOException if the path doesn't resolve;
    that's the right signal — a moved/removed file under conformance
    needs the test to surface it.
  - **`known-servers`** — the canonical `#{:re-frame2-pair-mcp :story-mcp}`
    pair. A typo in any test's `:servers` set surfaces against this.
    (xray ships as a Clojars-only library, not an MCP server.)

  ## What this ns does NOT own

  Per-marker schemas + the `canonical-markers` catalogue live in
  `wire_vocab/schemas.clj`; the shared source-text pin inventories +
  near-miss helpers live in `wire_vocab/source_pins.clj`.
  Marker-family-LOCAL schemas +
  fixtures (`ResultEnvelope`, `EventBundle`, and the per-family fixture
  maps) stay co-located with their tests in the focused `*_test.clj`
  namespaces — they ARE the contract for that family, not boilerplate.
  Same posture for `canonical-slots` (`slot_name_test.clj`) and
  `envelope-indicator-slots` (`wire_vocab_test.clj`). The split is:
  data-tables that ARE a single family's contract live next to their
  assertions; the cross-family schema/pin data lives in the `wire_vocab/`
  support nses; classpath-walk + slurp ceremony lives here."
  (:require [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Repo-root resolution. This file lives at
;; `tools/mcp-conformance/wire-vocab/test/re_frame/mcp_conformance/fixtures.clj`
;; relative to the repo root. We resolve THIS file's classpath URL and
;; walk seven parents up to reach the repo root (one `.getParentFile`
;; per path segment). `io/resource` returns the absolute classpath URL
;; whose first parent is the `mcp_conformance/` leaf directly.
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

(def read-source
  "Slurp a source file inside the repo. `rel-path` is a string path
  segment relative to the repo root, using `/` as the separator. The
  test fails loudly (via `slurp`'s default IOException) if the path
  doesn't resolve — that's the right signal: a source file under
  conformance was moved or removed.

  ## Memoisation (rf2-re2tv)

  Callers iterate `doseq` over the same files dozens of times across
  the three conformance test namespaces (`wire-vocab-test`,
  `slot-name-test`, `indicator-field-test`); the wire-vocab suite was
  ~4356ms wall-clock and almost entirely I/O-bound on a cold disk
  cache. Wrapping in `memoize` keyed on the rel-path drops the
  re-slurp cost to zero for every repeated access. Source files do
  not change mid-test-run, so cache invalidation is a non-concern.

  The bound name stays `read-source` (no underscore prefix or
  `read-source*` indirection) so the call sites read identically to
  the un-memoised form."
  (memoize
    (fn read-source* [rel-path]
      (slurp (io/file repo-root rel-path)))))

;; ---------------------------------------------------------------------------
;; Cross-server registry. The MCP servers under conformance. A typo in
;; any test's `:servers` set surfaces here against this canonical set.
;; ---------------------------------------------------------------------------

(def known-servers
  "The canonical set of MCP servers under conformance. Adding a new
  server means extending this set AND adding per-server source/spec
  coverage to every relevant test catalogue.

  xray-mcp was dropped in rf2-bu21t — `tools/xray-mcp/` is no longer
  in the repo; xray now ships as a Clojars-only library."
  #{:re-frame2-pair-mcp :story-mcp})

;; ---------------------------------------------------------------------------
;; story-mcp source-file inventory — single source of truth (rf2-ee38b.20).
;;
;; Several absence-tripwires across the three conformance test namespaces
;; grep the same canonical set of story-mcp source files (the day story-mcp
;; adopts a cross-MCP marker / envelope slot / near-miss slot name, the
;; tripwire flips RED). The list was duplicated verbatim across
;; `wire_vocab_test.clj` (two deftests) and `slot_name_test.clj` — exactly
;; the drift the gates exist to prevent, reproduced inside the gate: a new
;; story-mcp tool file added to one tripwire's list silently escaped the
;; others. Centralised here so "the files story-mcp ships" has one home.
;; ---------------------------------------------------------------------------

;; rf2-ribu5a — filesystem-derived, NOT hand-maintained. The previous
;; hand list ran through `recorder.cljc` but silently omitted
;; `dedup.cljc` (emits the cross-MCP `:rf.mcp/dedup-table` marker) and
;; `cursor.cljc` (routes the cross-MCP `:rf.mcp/cursor-stale` marker) —
;; exactly the drift these absence-tripwires exist to prevent,
;; reproduced inside the gate. A dropped/added tool file could escape
;; the generic near-miss / uncontracted-marker sweeps because the
;; inventory was edited by hand and fell behind the directory. Deriving
;; the set from the directory listing makes "the files story-mcp ships"
;; literally the files on disk: a new tool file is swept the moment it
;; lands, with zero list maintenance.
(def story-mcp-tools-dir
  "Repo-relative path to story-mcp's `tools/` source directory — the
  single home of the per-category tool handlers plus the shared
  registry / wire-pipeline / result / args / egress / cljs-resolve /
  schemas plumbing. `story-mcp-tool-source-files` is derived from a
  filesystem listing of `*.cljc` files here."
  "tools/story-mcp/src/re_frame/story_mcp/tools")

(def story-mcp-tool-source-files
  "The story-mcp `tools/*.cljc` source files — derived from a
  filesystem listing of `story-mcp-tools-dir`, NOT a hand-maintained
  list (rf2-ribu5a). This is the surface the slot-name and indicator
  near-miss tripwires grep (the slots live in the tool bodies, not the
  wire framing). Sorted for deterministic iteration order. Fails loudly
  if the directory is missing or empty — a story-mcp source-tree move
  must surface here, not silently shrink the sweep to nothing."
  (let [dir (io/file repo-root story-mcp-tools-dir)
        files (->> (.listFiles dir)
                   (filter #(.isFile %))
                   (map #(.getName %))
                   (filter #(re-find #"\.cljc$" %))
                   sort
                   (mapv #(str story-mcp-tools-dir "/" %)))]
    (when (empty? files)
      (throw (ex-info (str "story-mcp tools dir yielded zero *.cljc files — "
                           "the source tree moved or the path drifted: "
                           story-mcp-tools-dir)
                      {:dir (.getAbsolutePath dir)})))
    files))

(def story-mcp-source-files
  "Every story-mcp source file the cross-MCP-marker absence tripwires
  grep — the tool handlers (`story-mcp-tool-source-files`) plus the
  wire-framing `protocol.cljc` (which an overflow/elision adoption would
  also plausibly touch). The wire-marker tripwires use this superset;
  the slot-name / indicator near-miss tripwires use the tool-only subset
  (`story-mcp-tool-source-files`) since the framing layer never names a
  slot."
  (into ["tools/story-mcp/src/re_frame/story_mcp/protocol.cljc"]
        story-mcp-tool-source-files))

;; ---------------------------------------------------------------------------
;; Source-text helper. Conformance tests grep .cljs/.cljc/.md files for
;; canonical literals; some absence-pins want to distinguish *emissions*
;; from *documentation* (docstring mentions, comment references). This
;; state-machine over raw Clojure/CLJS source strips string literals
;; and line comments to single spaces — preserving line numbering for
;; accurate error reporting up the stack.
;;
;; Originally `defn-` in `wire_vocab_test.clj` (rf2-7dnct → rf2-xx42k),
;; promoted to a public helper here so all three conformance test
;; namespaces can share it (rf2-rto1l): the wire-vocab gate, the
;; story-mcp absence tripwire, AND the indicator-field inline-emit
;; anti-pin all need the same documentation-vs-emission distinction.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Keyword-extender-aware variant regex (rf2-qnmne).
;;
;; Conformance tests grep server source for canonical literal keywords and
;; near-miss variants. Raw `str/includes?` false-positives when a variant
;; is a prefix of a longer legitimate keyword — e.g. `":dropped-sensitive"`
;; is a substring of a future `":dropped-sensitive-warning"` extension.
;; The regex pins the variant as a complete keyword token: matched only
;; when not immediately followed by a keyword-extender character.
;;
;; Originally `defn-` in `slot_name_test.clj` (rf2-zvv65); promoted here
;; so `indicator_field_test.clj`'s inline-emit anti-pin can use the same
;; elegant pattern.
;; ---------------------------------------------------------------------------

(defn variant-regex
  "Build a Java regex that matches `variant-str` only when it is NOT
  immediately followed by a character that would extend it into a
  longer keyword. `[\\w\\-?/!*+'<>=]` is the conservative set of
  characters Clojure allows mid-keyword; matching one of those after
  the variant means we're actually looking at a longer keyword that
  happens to share a prefix with the variant — not the variant itself."
  [variant-str]
  (re-pattern (str (java.util.regex.Pattern/quote variant-str)
                   "(?![\\w\\-?/!*+'<>=])")))

(def ^:private strip-comments-and-strings*
  "Uncached implementation — exposed only so the public memoised
  `strip-comments-and-strings` can delegate. State machine over raw
  text; tracks in-string and in-comment with `\\` escape handling.
  Not a full Clojure reader — character literals (`\\;`), regex
  literals (`#\"...\"`), and `#_` reader-discards are not modelled.
  Conservative-strip semantics make those edge cases harmless: a
  missed string would only matter if a canonical marker appeared
  inside it, and those cases don't occur in the repo source under
  conformance."
  (fn [src]
    (let [n  (count src)
          sb (StringBuilder. n)]
    (loop [i 0, in-string? false, in-comment? false]
      (if (>= i n)
        (.toString sb)
        (let [c (.charAt ^String src i)]
          (cond
            in-comment?
            (do (.append sb (if (= c \newline) c \space))
                (recur (inc i) false (not= c \newline)))

            in-string?
            (cond
              ;; escape: skip the next char (consume both as space, preserving newlines)
              (= c \\)
              (do (.append sb \space)
                  (when (< (inc i) n)
                    (let [nx (.charAt ^String src (inc i))]
                      (.append sb (if (= nx \newline) nx \space))))
                  (recur (+ i 2) true false))

              (= c \")
              (do (.append sb \space)
                  (recur (inc i) false false))

              :else
              (do (.append sb (if (= c \newline) c \space))
                  (recur (inc i) true false)))

            (= c \;)
            (do (.append sb \space)
                (recur (inc i) false true))

            (= c \")
            (do (.append sb \space)
                (recur (inc i) true false))

            :else
            (do (.append sb c)
                (recur (inc i) false false)))))))))

(def strip-comments-and-strings
  "Memoised wrapper over `strip-comments-and-strings*` (rf2-re2tv).
  Every conformance test reaches for this on top of `read-source`
  output; both caches together collapse the wire-vocab suite's
  ~4356ms wall-clock by avoiding repeated state-machine walks of
  the same source text. Keyed on the input string itself — pure
  function of `src`, so identity-keyed equality is fine."
  (memoize strip-comments-and-strings*))
