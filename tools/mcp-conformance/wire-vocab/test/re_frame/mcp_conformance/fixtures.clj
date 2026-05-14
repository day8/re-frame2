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

(defn strip-comments-and-strings
  "Return `src` with Clojure line comments (`;` to EOL) and string
  literals (`\"...\"`, including docstrings) replaced by single
  spaces. Preserves line structure for accurate error reporting up
  the stack.

  Implementation: simple state machine over the raw text. Tracks two
  states (in-string vs in-comment) with `\\` escape handling inside
  strings. Not a full Clojure reader — character literals (`\\;`),
  regex literals (`#\"...\"`), and `#_` reader-discards are not
  modelled. Those edge cases don't matter for the conformance pins:
  we strip conservatively (false-positive whitelisting would be the
  bug; a missed string is OK because the marker still wouldn't appear
  in a bare character literal or a regex pattern targeting it)."
  [src]
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
                (recur (inc i) false false))))))))
