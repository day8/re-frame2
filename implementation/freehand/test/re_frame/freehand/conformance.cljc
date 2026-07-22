(ns re-frame.freehand.conformance
  "Cross-host loader for the Freehand conformance fixtures, plus the two
  assertion helpers the `FH-*` suites share.

  ## Why a macro

  A Freehand law is proven by running ONE fixture value on BOTH hosts.
  ClojureScript cannot read a file at runtime, so the fixture is read at
  MACRO-EXPANSION time — which happens on the JVM for both hosts — and
  the value is inlined, quoted, into the caller. The JVM suite and the
  ClojureScript suite therefore assert against the same bytes of
  `spec/conformance/freehand/fixtures/`, not against two hand-kept
  copies that can drift.

  A fixture that cannot be resolved fails the COMPILE, loudly, rather
  than yielding an empty table that would make a suite pass vacuously —
  a table-driven test whose table is empty is the worst failure mode
  available, so it is made impossible here.

  ## Why the ClojureScript read goes through shadow-cljs

  Inlining at macro-expansion time hides the fixture from the build: a
  ClojureScript compile that caches a test namespace has no edge back to
  the `.edn` whose bytes it froze, so a FIXTURE-ONLY edit leaves the
  cached namespace asserting the PREVIOUS expectations — a confident
  green over an expectation nobody is checking, which is the worst
  failure a table-driven gate can have.

  So the ClojureScript read goes through `shadow.resource/slurp-resource`,
  which records the fixture's classpath path and last-modified against
  the compiling namespace; shadow-cljs re-checks both before reusing that
  namespace's cache. Edit a fixture and the suites that read it
  recompile — no cache clearing, no ritual. `spec/conformance/` is a
  shadow-cljs `:source-path` (a classpath root of `.edn` data, no
  sources) so `:FH-PROPS-001` resolves as
  `freehand/fixtures/fh-props-001.edn`.

  The JVM lane needs no such edge — `clojure -M:test` has no persistent
  analyzer cache, so every run re-reads the file — and it does not carry
  the fixture root on its classpath, so it locates the same file by
  walking up from the compile CWD to the repository root."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io]
                    [clojure.string :as str]))
  #?(:cljs (:require-macros [re-frame.freehand.conformance :refer [fixture]])))

#?(:clj
   (def ^:private fixture-root
     "Where the fixtures live, as path segments below the repository root.
     The tail of this path is also the classpath path shadow-cljs
     resolves, because `spec/conformance/` is the `:source-path` root."
     ["spec" "conformance" "freehand" "fixtures"]))

#?(:clj
   (defn ^:private fixture-resource-path
     "The classpath path of the fixture for `id`, relative to the
     `spec/conformance/` root — e.g. `freehand/fixtures/fh-props-001.edn`.
     The filename mirrors the id in lower case, per the fixture
     convention in `spec/conformance/freehand/README.md`."
     [id]
     (str/join "/" (conj (subvec fixture-root 2)
                         (str (str/lower-case (name id)) ".edn")))))

#?(:clj
   (defn ^:private fixture-file
     "Locate the fixture file for classpath path `path` by walking up from
     the compile CWD looking for the fixture root — the JVM lane's
     locator, where `spec/conformance/` is not on the classpath. Throws
     an actionable error rather than yielding an absent table."
     [path]
     (loop [dir (io/file (System/getProperty "user.dir"))]
       (when (nil? dir)
         (throw (ex-info (str "Freehand conformance fixture " path " not found: no "
                              (str/join "/" fixture-root) " directory walking up from "
                              (System/getProperty "user.dir") ".")
                         {:path path :root fixture-root})))
       (let [candidate (apply io/file dir (conj (subvec fixture-root 0 2) path))]
         (if (.exists candidate)
           candidate
           (recur (.getParentFile dir)))))))

#?(:clj
   (defn ^:private slurp-fixture
     "Return the text of the fixture at `path`.

     Under a ClojureScript compile (`&env` carries the compiling `:ns`)
     the read goes through shadow-cljs, which registers `path` as a build
     dependency of that namespace — the edge that makes a fixture edit
     invalidate the cached suite. Under the JVM there is no cache to
     invalidate, and the file is read from the tree directly."
     [env path]
     (if (:ns env)
       ((requiring-resolve 'shadow.resource/slurp-resource) env path)
       (slurp (fixture-file path)))))

#?(:clj
   (defn ^:no-doc read-fixture
     "Read the fixture for conformance id `id` (e.g. `:FH-PROPS-003`) in
     the macro-expansion environment `env`. Throws when the fixture is
     absent or declares a different `:fh/id`."
     [env id]
     (let [path  (fixture-resource-path id)
           value (edn/read-string (slurp-fixture env path))]
       (when-not (= (name id) (:fh/id value))
         (throw (ex-info (str "Freehand conformance fixture " id " declares :fh/id "
                              (pr-str (:fh/id value)) " — the file and the id disagree.")
                         {:id id :declared (:fh/id value)})))
       value)))

#?(:clj
   (defmacro fixture
     "Inline the conformance fixture for `id` as a quoted literal, read at
     macro-expansion time. Identical data on the JVM and in
     ClojureScript, and — in ClojureScript — a build dependency, so an
     edit to the fixture recompiles this call site."
     [id]
     (list 'quote (read-fixture &env id))))

(def no-throw
  "What [[caught-id]] answers when the thunk returned normally. Named so a
  suite can assert acceptance and rejection through one helper."
  ::no-throw)

(defn caught-id
  "Run `thunk` and return the `:rf.error/id` of the error it raised,
  [[no-throw]] when it returned normally, or `::no-id` when it raised
  something carrying no diagnostic id. The one cross-host shape the
  suites assert diagnostic identity through — a message is stable in
  meaning, never in bytes, so the id is what a test pins."
  [thunk]
  (try
    (thunk)
    no-throw
    (catch #?(:clj Throwable :cljs :default) e
      (or (:rf.error/id (ex-data e)) ::no-id))))

(defn caught-message
  "Run `thunk` and return the message of the error it raised, or nil."
  [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj Throwable :cljs :default) e
      (ex-message e))))
