(ns re-frame.mcp-conformance.fixtures
  "Repository and source-inventory helpers shared by vocabulary tests.

  This namespace owns CWD-independent repo resolution, memoized source
  reads, the conformed server set, and filesystem-derived story tool
  sources. Schemas and marker source pins live under `wire_vocab/`; a
  focused family's fixtures remain beside its tests."
  (:require [clojure.java.io :as io]))

;; Resolve from the classpath resource rather than the invoking CWD.

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

  Reads are memoized because many vocabulary families scan the same
  immutable source files during one test process."
  (memoize
    (fn read-source* [rel-path]
      (slurp (io/file repo-root rel-path)))))

(def known-servers
  "The canonical set of MCP servers under conformance. Adding a new
  server means extending this set AND adding per-server source/spec
  coverage to every relevant test catalogue."
  #{:re-frame2-pair-mcp :story-mcp})

;; Derive the story tool surface from disk so a new source file enters every
;; marker/slot absence tripwire automatically.
(def story-mcp-tools-dir
  "Repo-relative path to story-mcp's `tools/` source directory — the
  single home of the per-category tool handlers plus the shared
  registry / wire-pipeline / result / args / egress / cljs-resolve /
  schemas plumbing. `story-mcp-tool-source-files` is derived from a
  filesystem listing of `*.cljc` files here."
  "tools/story-mcp/src/re_frame/story_mcp/tools")

(def story-mcp-tool-source-files
  "The story-mcp `tools/*.cljc` source files — derived from a
  filesystem listing of `story-mcp-tools-dir`, not a hand-maintained
  list. This is the surface the slot-name and indicator
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
    (let [source-length (count src)
          output        (StringBuilder. source-length)]
    (loop [index 0, in-string? false, in-comment? false]
      (if (>= index source-length)
        (.toString output)
        (let [current-char (.charAt ^String src index)]
          (cond
            in-comment?
            (do (.append output (if (= current-char \newline) current-char \space))
                (recur (inc index) false (not= current-char \newline)))

            in-string?
            (cond
              ;; escape: skip the next char (consume both as space, preserving newlines)
              (= current-char \\)
              (do (.append output \space)
                  (when (< (inc index) source-length)
                    (let [next-char (.charAt ^String src (inc index))]
                      (.append output (if (= next-char \newline) next-char \space))))
                  (recur (+ index 2) true false))

              (= current-char \")
              (do (.append output \space)
                  (recur (inc index) false false))

              :else
              (do (.append output (if (= current-char \newline) current-char \space))
                  (recur (inc index) true false)))

            (= current-char \;)
            (do (.append output \space)
                (recur (inc index) false true))

            (= current-char \")
            (do (.append output \space)
                (recur (inc index) true false))

            :else
            (do (.append output current-char)
                (recur (inc index) false false)))))))))

(def strip-comments-and-strings
  "Memoised wrapper over `strip-comments-and-strings*` (rf2-re2tv).
  Every conformance test reaches for this on top of `read-source`
  output; both caches together collapse the wire-vocab suite's
  ~4356ms wall-clock by avoiding repeated state-machine walks of
  the same source text. Keyed on the input string itself — pure
  function of `src`, so identity-keyed equality is fine."
  (memoize strip-comments-and-strings*))
