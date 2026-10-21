(ns re-frame.conformance-fixtures
  "Compile-time loader for the conformance EDN corpus.

  The CLJS conformance runner cannot use `clojure.java.io`
  / `slurp` / `file-seq` at runtime — there is no fs in CLJS. Instead,
  this `.clj` namespace provides a macro that reads every fixture EDN
  file at *compile time* (when the CLJS file that calls the macro is
  being compiled, this Clojure ns runs on the JVM) and inlines the
  resulting `{filename → fixture-data}` map into the consumer's
  bytecode. The CLJS test then iterates a plain in-memory map — no
  fs, no async, host-portable to `:node-test` and `:browser-test`.

  THE READS ARE RECORDED, AND THAT IS NOT A DETAIL (the defect class
  CLAUDE.md catalogues as item (x)). Inlining a file at
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
  `implementation/shadow-cljs.edn` carries.

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

  The reference-only corpus is read the same way, from
  `conformance-reference/` under the `core/test` `:source-path` root: its
  fixtures by [[reference-fixtures]] and its overlays file by
  [[reference-overlays]].

  The JVM-side conformance runner (re-frame.conformance-test) reads
  fixtures the conventional way (`slurp` + `clojure.edn/read-string`)
  and needs no such edge: `clojure -M:test` keeps no analyzer cache, so
  every run re-reads the corpus. Both read the same plain EDN, so the
  CLJS corpus loads the same data with no EDN-reader resolver."
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

(def ^:private reference-resource-dir
  "The reference-only corpus as a classpath resource name, relative to the
  `core/test` `:source-path` root."
  "conformance-reference")

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. See
  `re-frame.conformance-test/read-one-form` for the full rationale.

  This is the CLJS arm's seam, and it is the loudest of the seven: the
  macro below runs on the JVM at CLJS COMPILE time, so a malformed
  fixture fails the build outright rather than reaching a runner that
  could classify it as a skip."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (see re-frame.conformance-test/read-one-form)")
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
  "Sorted names of every `.edn` file in the resource directory `dir`,
  enumerated off the classpath. Sorting keeps the CLJS suite's reporting order
  deterministic and matches a fixture-file `ls -1` traversal — same
  pattern other long-running CLJS test suites use.

  The corpus is flat by construction, so a one-level listing is the
  whole of it and the returned names double as the leaf of each
  resource name. This listing is not a recorded read; the namespace
  docstring says what that does and does not cost."
  [dir]
  (let [url (io/resource dir)]
    (when-not url
      (throw (ex-info (str "conformance fixtures not found on the classpath as \""
                           dir "\" — the `spec` and `core/test` :source-path roots "
                           "in implementation/shadow-cljs.edn are what make it "
                           "resolvable, and this macro is a ClojureScript-lane "
                           "macro only")
                      {:resource dir})))
    (->> (.listFiles (io/file (.toURI url)))
         (filter (fn [^java.io.File f] (.isFile f)))
         (map (fn [^java.io.File f] (.getName f)))
         (filter #(str/ends-with? % ".edn"))
         sort
         vec)))

(defn- load-resource
  "Read the plain-EDN file at resource path `path` in the ClojureScript
  macro-expansion environment `env`, exactly as the JVM runner reads it.

  The read goes through the recording reader, so this file becomes a
  build dependency of the namespace being compiled."
  [env path]
  (read-one-form (rf.build.spec-resource/slurp-resource env path) path))

(defn- load-all-fixtures
  "Return a sorted-by-filename vector of `[filename fixture-data]`
  pairs for every `.edn` file in the resource directory `dir`, each read
  through the recording reader in the macro-expansion environment `env`."
  [env dir]
  (mapv (fn [file-name] [file-name (load-resource env (str dir "/" file-name))])
        (fixture-file-names dir)))

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
  (let [pairs (load-all-fixtures &env fixtures-resource-dir)]
    `(quote ~pairs)))

(defmacro reference-fixtures
  "Inline the reference-only corpus's fixtures at compile time, as
  [[all-fixtures]] inlines the portable corpus."
  []
  (let [pairs (load-all-fixtures &env (str reference-resource-dir "/fixtures"))]
    `(quote ~pairs)))

(defmacro reference-overlays
  "Inline the reference-only corpus's overlays file at compile time: a map
  from a portable fixture's filename to the reference-only expectations it
  adds (`re-frame.conformance-runner/overlay-fixtures`)."
  []
  `(quote ~(load-resource &env (str reference-resource-dir "/overlays.edn"))))
