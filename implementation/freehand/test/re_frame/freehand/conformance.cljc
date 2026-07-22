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
  available, so it is made impossible here."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io]
                    [clojure.string :as str]))
  #?(:cljs (:require-macros [re-frame.freehand.conformance :refer [fixture]])))

#?(:clj
   (def ^:private fixture-roots
     "Candidate fixture directories, in resolution order — the compile CWD
     differs per gate:

       - `implementation/freehand/` — this artefact's JVM `:test` alias;
       - `implementation/`          — the shadow-cljs builds;
       - the repository root        — a REPL or tool run from the top."
     ["../../spec/conformance/freehand/fixtures"
      "../spec/conformance/freehand/fixtures"
      "spec/conformance/freehand/fixtures"]))

#?(:clj
   (defn ^:no-doc read-fixture
     "Read the fixture for conformance id `id` (e.g. `:FH-PROPS-003`).
     The filename mirrors the id in lower case, per the fixture
     convention in `spec/conformance/freehand/README.md`. Throws when no
     candidate root holds the file."
     [id]
     (let [filename (str (str/lower-case (name id)) ".edn")
           file     (->> fixture-roots
                         (map #(io/file % filename))
                         (filter #(.exists ^java.io.File %))
                         first)]
       (when-not file
         (throw (ex-info (str "Freehand conformance fixture " id " (" filename ") not found. "
                              "Looked under " (pr-str fixture-roots) " relative to "
                              (System/getProperty "user.dir") ".")
                         {:id id :filename filename :roots fixture-roots})))
       (let [value (edn/read-string (slurp file))]
         (when-not (= (name id) (:fh/id value))
           (throw (ex-info (str "Freehand conformance fixture " id " declares :fh/id "
                                (pr-str (:fh/id value)) " — the file and the id disagree.")
                           {:id id :declared (:fh/id value)})))
         value))))

#?(:clj
   (defmacro fixture
     "Inline the conformance fixture for `id` as a quoted literal, read at
     macro-expansion time. Identical data on the JVM and in
     ClojureScript."
     [id]
     (list 'quote (read-fixture id))))

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
