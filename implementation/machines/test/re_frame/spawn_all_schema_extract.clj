(ns re-frame.spawn-all-schema-extract
  "Reads the canonical `:spawn-all` runtime-db schema forms out of
  `spec/Spec-Schemas.md`, and the text of `spec/009-Instrumentation.md`, so
  tests validate against the documents rather than a hand-copied mirror.

  The extracted `Machines` form names its `:spawned` union arms as bare symbols
  and the snapshot as `:rf/machine-snapshot`; `canonical-machines-schema`
  substitutes the arms with their extracted forms and the snapshot with a
  permissive `:map` (its `MachineSnapshot` form carries a `(fn …)` that cannot
  be read as data)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- locate ^java.io.File [candidates what]
  (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f))) candidates)
      (throw (ex-info (str "spawn-all-schema-extract: cannot locate " what) {:candidates candidates}))))

(def ^:private spec-schemas-candidates
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(def ^:private spec-009-candidates
  ["../../spec/009-Instrumentation.md" "../spec/009-Instrumentation.md" "spec/009-Instrumentation.md"])

(defn spec-schemas-text [] (slurp (locate spec-schemas-candidates "spec/Spec-Schemas.md")))
(defn spec-009-text     [] (slurp (locate spec-009-candidates "spec/009-Instrumentation.md")))

(defn extract-def-form
  "The schema vector of `(def <sym> …)` in `text`. Throws if the `def` is
  absent or its value is not a vector."
  [text sym]
  (let [start (str/index-of text (str "(def " sym))]
    (when (nil? start)
      (throw (ex-info (str "spawn-all-schema-extract: (def " sym " …) not found in Spec-Schemas.md") {:sym sym})))
    (let [schema (nth (edn/read-string (subs text start)) 2 nil)]
      (when-not (vector? schema)
        (throw (ex-info (str "spawn-all-schema-extract: " sym " is not a schema vector form") {:sym sym :read schema})))
      schema)))

(defn canonical-invoke-all-join-schema
  "The canonical `InvokeAllJoinState` `[:map …]` form."
  []
  (extract-def-form (spec-schemas-text) 'InvokeAllJoinState))

(defn canonical-machines-schema
  "The canonical `Machines` runtime-db form, its `:spawned` union arms resolved
  to the extracted `InvokeAllJoinState` / `InvokeAllRejectedState` forms."
  []
  (let [text (spec-schemas-text)]
    (walk/postwalk-replace
      {'InvokeAllJoinState     (extract-def-form text 'InvokeAllJoinState)
       'InvokeAllRejectedState (extract-def-form text 'InvokeAllRejectedState)
       :rf/machine-snapshot    :map}
      (extract-def-form text 'Machines))))
