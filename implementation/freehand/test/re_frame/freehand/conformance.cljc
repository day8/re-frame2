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

  ## Why the read goes through the shared build-time reader

  Inlining at macro-expansion time hides the fixture from the build: a
  ClojureScript compile that caches a test namespace has no edge back to
  the `.edn` whose bytes it froze, so a FIXTURE-ONLY edit leaves the
  cached namespace asserting the PREVIOUS expectations — a confident
  green over an expectation nobody is checking, which is the worst
  failure a table-driven gate can have.

  `re-frame.build.spec-resource` is the one reader that closes that gap,
  shared with every other macro that inlines committed `spec/` data. It
  reads through shadow-cljs under a ClojureScript compile, which records
  the fixture as a build dependency of the compiling namespace, and walks
  up from the compile CWD everywhere else. Edit a fixture and the suites
  that read it recompile — no cache clearing, no ritual. The reader is
  shared rather than copied because resolving shadow's reader is a
  cold-load race, and a per-consumer solution only makes ONE consumer
  safe with itself; that namespace carries the whole argument.

  Fixtures resolve below the `spec/` root, so `:FH-PROPS-001` is
  `conformance/freehand/fixtures/fh-props-001.edn`."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.string :as str]
                    [re-frame.build.spec-resource :as spec-resource]))
  #?(:cljs (:require-macros [re-frame.freehand.conformance :refer [fixture]])))

#?(:clj
   (defn ^:private fixture-resource-path
     "The fixture for `id` as a path below the `spec/` root — e.g.
     `conformance/freehand/fixtures/fh-props-001.edn`. The filename
     mirrors the id in lower case, per the fixture convention in
     `spec/conformance/freehand/README.md`."
     [id]
     (str "conformance/freehand/fixtures/" (str/lower-case (name id)) ".edn")))

#?(:clj
   (defn ^:no-doc read-fixture
     "Read the fixture for conformance id `id` (e.g. `:FH-PROPS-003`) in
     the macro-expansion environment `env`. Throws when the fixture is
     absent or declares a different `:fh/id`."
     [env id]
     (let [path  (fixture-resource-path id)
           value (edn/read-string (spec-resource/slurp-resource env path))]
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
