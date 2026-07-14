(ns re-frame.spawn-all-schema-extract
  "Compile-time extraction of the CANONICAL `:spawn-all` runtime-db schema
  forms from `spec/Spec-Schemas.md`, and narrow reads of the authoritative
  `spec/009-Instrumentation.md` catalogue rows (rf2-2ai15g).

  The `:spawn-all` exact-authority proof must validate runtime-produced
  records against the EXECUTABLE canonical schema — not a hand-copied mirror
  that can silently drift from the markdown (the precursor rf2-p53o47 proof
  carried a `^:private` handwritten `InvokeAllJoinState` copy; this replaces
  it). Mirrors the `re-frame.observation-schema-extract` pattern (rf2-qyvyes):
  read the markdown on the JVM, find exactly the NAMED `def` forms, and return
  the parsed EDN `[…]` forms as data. It reads exactly these enumerated forms
  and fails loudly if any is absent or is not a vector form (removing a schema
  from the markdown is then a test-build error, so the two surfaces cannot
  drift silently). This is NOT a general markdown/schema generator — it names
  its inputs.

  The extracted `Machines` form references two `:spawned`-union arms as bare
  symbols (`InvokeAllJoinState` / `InvokeAllRejectedState`) and the machine
  snapshot as `:rf/machine-snapshot`. `resolve-machines-refs` substitutes the
  two union arms with their VERBATIM extracted forms (the shapes under test)
  and the snapshot ref with a permissive `:map` — the `MachineSnapshot` form
  carries a live `(fn …)` `:multi` dispatch that cannot be read as pure data,
  AND the snapshot shape is not what this proof guards (it has its own
  dedicated conformance gates). So the two arms under test come straight from
  the authoritative markdown; the snapshot slot is validated structurally."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; File location — resolve the spec docs relative to whichever directory the
;; JVM test runner starts from: `clojure -M:test` runs from
;; implementation/machines.
;; ---------------------------------------------------------------------------

(defn- locate ^java.io.File [candidates what]
  (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f))) candidates)
      (throw (ex-info (str "rf2-2ai15g: cannot locate " what) {:candidates candidates}))))

(def ^:private spec-schemas-candidates
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(def ^:private spec-009-candidates
  ["../../spec/009-Instrumentation.md" "../spec/009-Instrumentation.md" "spec/009-Instrumentation.md"])

(defn spec-schemas-text [] (slurp (locate spec-schemas-candidates "spec/Spec-Schemas.md")))
(defn spec-009-text     [] (slurp (locate spec-009-candidates "spec/009-Instrumentation.md")))

;; ---------------------------------------------------------------------------
;; Schema extraction
;; ---------------------------------------------------------------------------

(defn extract-def-form
  "Read `text`, find `(def <sym> …)`, and return its schema VECTOR (the form at
  position 2 — the `;;` comments are dropped by the EDN reader, so the result is
  the pure canonical form). Throws if the `def` is absent or is not a vector."
  [text sym]
  (let [start (str/index-of text (str "(def " sym))]
    (when (nil? start)
      (throw (ex-info (str "rf2-2ai15g: (def " sym " …) not found in Spec-Schemas.md") {:sym sym})))
    (let [schema (nth (edn/read-string (subs text start)) 2 nil)]
      (when-not (vector? schema)
        (throw (ex-info (str "rf2-2ai15g: " sym " is not a schema vector form") {:sym sym :read schema})))
      schema)))

(defn canonical-invoke-all-join-schema
  "The canonical `InvokeAllJoinState` `[:map …]` form, extracted from
  Spec-Schemas.md. `:rf/attempt` is REQUIRED here (the authoritative document);
  a token-less child-bearing join is not a valid live shape."
  []
  (extract-def-form (spec-schemas-text) 'InvokeAllJoinState))

(defn canonical-invoke-all-rejected-schema
  "The canonical `InvokeAllRejectedState` `[:map {:closed true} …]` form — the
  childless `:spawn-all` reject sentinel — extracted from Spec-Schemas.md."
  []
  (extract-def-form (spec-schemas-text) 'InvokeAllRejectedState))

(defn- resolve-machines-refs
  "Substitute the `Machines` form's three named refs with self-contained forms:
  the two `:spawned`-union arms come VERBATIM from the authoritative markdown
  (the shapes under test); the snapshot ref becomes a permissive `:map` (see ns
  docstring). Yields a fully inline malli schema — no registry needed."
  [machines-form join-form reject-form]
  (walk/postwalk-replace
    {'InvokeAllJoinState     join-form
     'InvokeAllRejectedState reject-form
     :rf/machine-snapshot    :map}
    machines-form))

(defn canonical-machines-schema
  "The canonical `Machines` runtime-db validator form (`:rf.runtime/machines`),
  extracted from Spec-Schemas.md with its `:spawned` union arms resolved to the
  authoritative `InvokeAllJoinState` / `InvokeAllRejectedState` forms."
  []
  (let [text (spec-schemas-text)]
    (resolve-machines-refs (extract-def-form text 'Machines)
                           (extract-def-form text 'InvokeAllJoinState)
                           (extract-def-form text 'InvokeAllRejectedState))))

(defn canonical-spawned-slot-schema
  "The canonical `:spawned` slot schema — `[:map-of :keyword [:map-of [:vector
  :keyword] [:or :keyword InvokeAllJoinState InvokeAllRejectedState]]]` — pulled
  from the resolved `Machines` form, so the three-arm union under test is the
  ACTUAL Spec-Schemas union, not a reconstruction."
  []
  (->> (rest (canonical-machines-schema))
       (filter #(and (vector? %) (= :spawned (first %))))
       first
       last))
