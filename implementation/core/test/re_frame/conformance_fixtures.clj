(ns re-frame.conformance-fixtures
  "Compile-time loader for the conformance EDN corpus.

  The CLJS conformance runner (rf2-3oi9x) cannot use `clojure.java.io`
  / `slurp` / `file-seq` at runtime — there is no fs in CLJS. Instead,
  this `.clj` namespace provides a macro that reads every fixture EDN
  file at *compile time* (when the CLJS file that calls the macro is
  being compiled, this Clojure ns runs on the JVM) and inlines the
  resulting `{filename → fixture-data}` map into the consumer's
  bytecode. The CLJS test then iterates a plain in-memory map — no
  fs, no async, host-portable to `:node-test` and `:browser-test`.

  THE READS ARE RECORDED, AND THAT IS NOT A DETAIL (rf2-1tlr4, the
  defect class CLAUDE.md catalogues as item (x)). Inlining a file at
  macro-expansion time by itself HIDES it from the build: an `.edn` is
  not an input the ClojureScript compiler tracks, so a cached consumer
  holds no edge back to the bytes this macro froze. Correct a fixture
  and the incremental compile truthfully reports `1 compiled` while the
  suite goes on grading the PREVIOUS expansion — a confident verdict
  over a corpus nobody re-read, which only a COLD rebuild moves, and
  which therefore reads as a phantom and sends the reader hunting a
  second cause for a failure already fixed.

  So every fixture is read through
  `re-frame.build.spec-resource/slurp-resource`, the shared wrapper over
  shadow-cljs's recording reader: it registers each file's classpath
  path and last-modified against the compiling namespace, and
  shadow-cljs re-checks both before reusing that namespace's cache.
  Edit a fixture, the consumer recompiles, no cold rebuild and no
  ritual. [[fixtures-resource-dir]] is therefore a classpath RESOURCE
  name under the `spec` `:source-path` root that
  `implementation/shadow-cljs.edn` already carries — this macro needed
  no new root, unlike the sibling repair under rf2-uttbj.

  That reader is SHARED rather than reimplemented here: resolving
  shadow's own reader is a cold-load race that two independent
  resolvers lose however carefully each guards itself — see the
  reader's namespace docstring. It has a ClojureScript lane and no
  other, so [[all-fixtures]] hands its `&env` down.

  ONE EDGE THIS DOES NOT BUY, stated because the gap is silent. The
  directory LISTING is not itself a recorded read — shadow records
  resources that were slurped, and there is no file to slurp for `what
  is in this directory`. So ADDING a fixture leaves every recorded
  path matching and the cached consumer is reused: a brand
  new fixture is invisible to an incremental compile until a cold
  rebuild, or until any source namespace this consumer depends on is
  itself edited. EDITING a fixture is covered by its recorded
  last-modified, and DELETING one is covered too, because shadow
  invalidates when a recorded resource no longer resolves at all. Those
  two are the overwhelming majority of how this corpus moves.

  The JVM-side conformance runner (re-frame.conformance-test) reads
  fixtures the conventional way (`slurp` + `clojure.edn/read-string`)
  and needs no such edge: `clojure -M:test` keeps no analyzer cache, so
  every run re-reads the corpus. This macro applies the same `::name`
  → `:rf.machine.timer/name` rewrite that runner uses (per rf2-lu3f) so
  the CLJS corpus loads the same data without an EDN-reader resolver."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.build.spec-resource :as rf.build.spec-resource]))

(def ^:private fixtures-resource-dir
  "The corpus directory as a classpath resource name, relative to the
  `spec` `:source-path` root. Enumeration and reading both resolve
  through this one root, so there is no compile-CWD-relative path
  (`../spec` from `implementation/`, `../../spec` from an artefact dir)
  left to keep in step with it."
  "conformance/fixtures")

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. See
  `re-frame.conformance-test/read-one-form` for the full rationale
  (rf2-98ni).

  This is the CLJS arm's seam, and it is the loudest of the seven: the
  macro below runs on the JVM at CLJS COMPILE time, so a malformed
  fixture fails the build outright rather than reaching a runner that
  could classify it as a skip."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (rf2-98ni, rf2-5mr6)")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- fixture-file-names
  "Sorted names of every `.edn` file in the corpus, enumerated off the
  classpath. Sorting keeps the CLJS suite's reporting order
  deterministic and matches a fixture-file `ls -1` traversal — same
  pattern other long-running CLJS test suites use.

  The corpus is flat by construction, so a one-level listing is the
  whole of it and the returned names double as the leaf of each
  resource name. This listing is not a recorded read; the namespace
  docstring says what that does and does not cost."
  []
  (let [url (io/resource fixtures-resource-dir)]
    (when-not url
      (throw (ex-info (str "conformance fixtures not found on the classpath as \""
                           fixtures-resource-dir "\" — the `spec` :source-path root "
                           "in implementation/shadow-cljs.edn is what makes it "
                           "resolvable, and this macro is a ClojureScript-lane "
                           "macro only")
                      {:resource fixtures-resource-dir})))
    (->> (.listFiles (io/file (.toURI url)))
         (filter (fn [^java.io.File f] (.isFile f)))
         (map (fn [^java.io.File f] (.getName f)))
         (filter #(str/ends-with? % ".edn"))
         sort
         vec)))

(defn- load-fixture
  "Read the fixture named `file-name` in the ClojureScript
  macro-expansion environment `env`, applying the same `::name` rewrite
  the JVM runner uses so `clojure.edn/read-string` (which has no reader
  resolver) accepts auto-resolved keywords.

  The read goes through the recording reader, so this file becomes a
  build dependency of the namespace being compiled."
  [env file-name]
  (let [raw   (rf.build.spec-resource/slurp-resource
                env
                (str fixtures-resource-dir "/" file-name))
        ;; Rewrite ONLY a standalone auto-resolved keyword `::name` (one
        ;; beginning a token — preceded by `(` / `[` / `{` / whitespace).
        ;; The lookbehind keeps the rewrite from corrupting a `::` INSIDE a
        ;; value, e.g. the CEDN-1 keyword token `"k::answer"` in an EP-0012
        ;; `:expect` string (rf2-qyb9l1). Mirror of the JVM runner's
        ;; load-fixture rewrite.
        fixed (str/replace raw
                           #"(?<=[\s(\[{])::([a-zA-Z][a-zA-Z0-9_-]*)"
                           ":rf.machine.timer/$1")]
    (read-one-form fixed file-name)))

(defn- load-all-fixtures
  "Return a sorted-by-filename vector of `[filename fixture-data]`
  pairs for every `.edn` file in the corpus, each read through the
  recording reader in the macro-expansion environment `env`."
  [env]
  (mapv (fn [file-name] [file-name (load-fixture env file-name)])
        (fixture-file-names)))

(defmacro all-fixtures
  "Inline the corpus at compile time. Returns a vector of
  `[filename fixture-map]` pairs, sorted by filename. Used by the
  CLJS conformance runner (`conformance_corpus_cljs_test.cljs`) so
  the .edn files do not need to be fs-readable at CLJS runtime.

  Every fixture read is recorded against the calling namespace, so
  editing a fixture invalidates that namespace's cache and an
  incremental compile re-expands this macro — see the namespace
  docstring for the one case that still needs a cold rebuild."
  []
  (let [pairs (load-all-fixtures &env)]
    `(quote ~pairs)))
