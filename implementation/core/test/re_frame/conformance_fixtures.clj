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

  The JVM-side conformance runner (re-frame.conformance-test) reads
  fixtures the conventional way (`slurp` + `clojure.edn/read-string`).
  This macro applies the same `::name` → `:rf.machine.timer/name`
  rewrite the JVM runner uses (per rf2-lu3f) so the CLJS corpus loads
  the same data without an EDN-reader resolver."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def fixtures-dir
  ;; Per the JVM runner: the corpus lives at the repo root under
  ;; spec/conformance/fixtures. Shadow-cljs runs from
  ;; implementation/, so the relative path is ../spec/conformance/
  ;; fixtures.
  (let [nested  (io/file "../spec/conformance/fixtures")
        legacy  (io/file "../../spec/conformance/fixtures")]
    (cond
      (.exists nested) nested
      (.exists legacy) legacy
      :else
      (throw (ex-info "conformance fixtures dir not found"
                      {:tried [(.getPath nested) (.getPath legacy)]})))))

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. See
  `re-frame.conformance-test/read-one-form` for the full rationale
  (rf2-98ni).

  This is the CLJS arm's seam, and it is the loudest of the seven: the
  macro below runs on the JVM at CLJS COMPILE time, so a malformed
  fixture fails the build outright rather than reaching a runner that
  could classify it as a skip."
  [text fixture-name]
  (let [forms (edn/read-string (str "[\n" text "\n]"))]
    (when-not (= 1 (count forms))
      (throw (ex-info (str "conformance fixture " fixture-name
                           " must hold exactly ONE top-level EDN form, found "
                           (count forms)
                           " — clojure.edn/read-string returns only the first"
                           " and silently discards the rest (rf2-98ni,"
                           " rf2-5mr6)")
                      {:fixture/file  fixture-name
                       :fixture/forms (count forms)})))
    (first forms)))

(defn- load-fixture-from-file
  "Read one EDN fixture file, applying the same `::name` rewrite the
  JVM runner uses so `clojure.edn/read-string` (which has no reader
  resolver) accepts auto-resolved keywords."
  [file]
  (let [raw   (slurp file)
        ;; Rewrite ONLY a standalone auto-resolved keyword `::name` (one
        ;; beginning a token — preceded by `(` / `[` / `{` / whitespace).
        ;; The lookbehind keeps the rewrite from corrupting a `::` INSIDE a
        ;; value, e.g. the CEDN-1 keyword token `"k::answer"` in an EP-0012
        ;; `:expect` string (rf2-qyb9l1). Mirror of the JVM runner's
        ;; load-fixture rewrite.
        fixed (str/replace raw
                           #"(?<=[\s(\[{])::([a-zA-Z][a-zA-Z0-9_-]*)"
                           ":rf.machine.timer/$1")]
    (read-one-form fixed (.getName file))))

(defn- load-all-fixtures
  "Return a sorted-by-filename vector of `[filename fixture-data]`
  pairs for every `.edn` file under [[fixtures-dir]]. Sorting keeps
  the CLJS suite's reporting order deterministic and matches a
  fixture-file `ls -1` traversal — same pattern other long-running
  CLJS test suites use."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))
       (mapv (fn [f]
               [(.getName f) (load-fixture-from-file f)]))))

(defmacro all-fixtures
  "Inline the corpus at compile time. Returns a vector of
  `[filename fixture-map]` pairs, sorted by filename. Used by the
  CLJS conformance runner (`conformance_corpus_cljs_test.cljs`) so
  the .edn files do not need to be fs-readable at CLJS runtime."
  []
  (let [pairs (load-all-fixtures)]
    `(quote ~pairs)))
